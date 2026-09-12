package com.ailife.wftools2.domain.editor.watchmaker

import io.github.taoguan.luaj.Globals
import io.github.taoguan.luaj.LuaTable
import io.github.taoguan.luaj.LuaValue
import io.github.taoguan.luaj.Varargs
import io.github.taoguan.luaj.compiler.LuaC
import io.github.taoguan.luaj.lib.OneArgFunction
import io.github.taoguan.luaj.lib.TwoArgFunction
import io.github.taoguan.luaj.lib.VarArgFunction
import java.io.ByteArrayInputStream

/**
 * Runs a WatchMaker global script (`scripts/script.txt`) as a real Lua chunk and
 * executes `script:` tap actions against the same persistent Lua state, so the
 * face's functions (`wf_next_page`, `on_second`, ...) and their `var_*` mutations
 * behave like on the device.
 *
 * - `{tag}` references are transpiled to `wm_tag('{tag}')` by a string/comment
 *   aware scanner before compilation.
 * - Unknown bare identifiers resolve through the same `wm_tag` path (WatchMaker
 *   exposes template variables as globals to the script).
 * - Scalars the script leaves in the global environment are exposed as the
 *   [variables] map consumed by the tag engine, formatted like [WmExpressionEval].
 * - `wm_*` helpers that cannot work in a preview are inert no-ops.
 *
 * VM entry points synchronize per instance; renderer reads and background clock
 * callbacks may share a session safely. When a script cannot run,
 * [runScript] returns false and the caller falls back to static variable parsing.
 */
class WmScriptRuntime {

    private val globals: Globals = WmLuaEnv.newGlobals()

    /** Whether the global script ran successfully in this state. */
    @Volatile var hasScript: Boolean = false
        private set

    /** Last Lua error raised while running a script/snippet, or null. */
    @Volatile var lastError: String? = null
        private set

    /** Scalar globals (numbers/strings/booleans) left by the script. */
    @Volatile var variables: Map<String, String> = emptyMap()
        private set

    @Volatile private var tagResolver: (String) -> String = { it }

    /**
     * Receives non-Lua actions the script triggers via `wm_action("…")`,
     * `wm_vibrate(ms)`, `wm_sfx("name")` or `wm_set_alarm("HH:MM")`. The value is a
     * canonical `tap_action`-style string (`w_app:…`, `vibrate:…`, `play_sound:…`,
     * `set_alarm_HH:MM`, …) so the caller can reuse the same tap-action dispatcher.
     * The preview uses this to show Toast/vibration feedback; the editor's CHECK run
     * leaves it as the default no-op.
     */
    @Volatile var actionHandler: (String) -> Unit = {}

    // Compiled expression chunks, bound to this state's globals (so they see the
    // script's functions and live vars). Access-order LinkedHashMap = LRU.
    private val exprCache = LinkedHashMap<String, LuaValue>(128, 0.75f, true)
    private var lastHour = -1
    private var lastMinute = -1
    private var lastSecond = -1

    init {
        installHelpers()
    }

    /**
     * Evaluate a single expression against this state's globals — where the script's
     * functions (wf_tiletext, ...) and live var_* globals actually live. Returns the
     * original string on any failure. This is how layer attributes referencing
     * script helpers resolve.
     */
    @Synchronized fun eval(expr: String): String {
        if (expr.isBlank()) return expr
        expr.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return WmExpressionEval.formatNumber(it) }
        return try {
            val chunk = compiled(expr.trim())
            WmLuaEnv.bounded(globals) { WmExpressionEval.formatResult(chunk.call()) }
        } catch (_: Exception) {
            expr
        }
    }

    /**
     * Fire the face's `on_second`/`on_minute`/`on_hour` callbacks when the clock
     * crosses those boundaries (on_second(minute, second) per the launcher). [resolver]
     * resolves any `{tag}` the callbacks reference. Returns true when a callback fired
     * (caller should republish [variables]).
     */
    @Synchronized fun fireTimeCallbacks(hour: Int, minute: Int, second: Int, resolver: (String) -> String = this.tagResolver): Boolean {
        tagResolver = resolver
        var fired = false
        if (hour != lastHour) {
            lastHour = hour
            fire("on_hour", LuaValue.valueOf(hour))
            fired = true
        }
        if (minute != lastMinute) {
            lastMinute = minute
            fire("on_minute", LuaValue.valueOf(hour), LuaValue.valueOf(minute))
            fired = true
        }
        if (second != lastSecond) {
            lastSecond = second
            fire("on_second", LuaValue.valueOf(minute), LuaValue.valueOf(second))
            fired = true
        }
        if (fired) refreshVariables()
        return fired
    }

    private fun fire(name: String, vararg args: LuaValue) {
        val cb = globals.get(name)
        if (cb.isfunction()) {
            try {
                WmLuaEnv.bounded(globals) { cb.invoke(LuaValue.varargsOf(args)) }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
    }

    /**
     * Fire the face's `on_millisecond(dt)` callback — the launcher's per-frame
     * tween/animation hook — with the frame delta in seconds. Returns true when
     * the callback fired (caller should republish [variables]).
     */
    @Synchronized fun fireMillisecond(dtSeconds: Double, resolver: (String) -> String = this.tagResolver): Boolean {
        tagResolver = resolver
        val cb = globals.get("on_millisecond")
        if (!cb.isfunction()) return false
        return try {
            WmLuaEnv.bounded(globals) { cb.invoke(LuaValue.valueOf(dtSeconds)) }
            refreshVariables()
            true
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    private fun compiled(src: String): LuaValue {
        exprCache[src]?.let { return it }
        val chunk = globals.load("return " + src, "@wm_expr")
        exprCache[src] = chunk
        if (exprCache.size > CACHE_MAX) {
            val it = exprCache.keys.iterator()
            it.next()
            it.remove()
        }
        return chunk
    }

    /**
     * Current value of a scalar global ([null] when absent or non-scalar), formatted
     * like the tag engine expects. This is authoritative for `{name}` reads: the value
     * is already final (e.g. the 2048 board string keeps its leading zeros).
     */
    @Synchronized fun value(name: String): String? {
        val v = globals.rawget(name)
        return when {
            v.isnil() -> null
            v.isboolean() -> if (v.toboolean()) "true" else "false"
            // type() (not isnumber()): numeric-looking strings stay strings.
            v.type() == LuaValue.TNUMBER -> WmExpressionEval.formatNumber(v.todouble())
            v.isstring() -> v.tojstring()
            else -> null
        }
    }

    /** Whether the state has a global with [name] (scalar, table or function). */
    @Synchronized fun has(name: String): Boolean = !globals.rawget(name).isnil()

    /**
     * Run the whole global script chunk. [tagResolver] turns a `{tag}` reference
     * into its current preview value (wrapped in braces). Returns false when the
     * script cannot be compiled or errors at runtime.
     */
    @Synchronized fun runScript(script: String, tagResolver: (String) -> String = { it }): Boolean {
        this.tagResolver = tagResolver
        lastError = null
        if (script.isBlank()) return false
        return runChunk(preprocess(script))
    }

    /**
     * Compile only, using the same tag preprocessing as [runScript]. No chunk is
     * installed or executed, including on success. Error positions refer to the
     * original source; LuaJ reports a token rather than an exact parser column.
     */
    fun validateScript(source: String): String? {
        if (source.isBlank()) return null
        val positions = ArrayList<Int>(source.length)
        val processed = preprocess(source, positions)
        val bytes = processed.toByteArray(Charsets.UTF_8)
        val input = object : ByteArrayInputStream(bytes) {
            fun consumed(): Int = pos
        }
        return try {
            // LuaC creates a Prototype only; it never sees this instance's globals.
            LuaC.instance.compile(input, "@wm_script")
            null
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            val generatedLine = Regex(":(\\d+):").find(message)?.groupValues?.get(1)?.toIntOrNull()
            val consumed = String(bytes, 0, input.consumed(), Charsets.UTF_8).length.coerceAtMost(processed.length)
            val token = Regex("near '([^']*)'").find(message)?.groupValues?.get(1)
            val lineStart = if (generatedLine == null) 0 else {
                var offset = 0
                repeat((generatedLine - 1).coerceAtLeast(0)) {
                    offset = (processed.indexOf('\n', offset).takeIf { it >= 0 }?.plus(1) ?: processed.length)
                }
                offset
            }
            val tokenOffset = if (token != null && token != "<eof>" && token.isNotEmpty()) {
                processed.lastIndexOf(token, (consumed - 1).coerceAtLeast(0)).takeIf { it >= lineStart }
            } else null
            val generatedOffset = tokenOffset ?: if (token == "<eof>") processed.length else (consumed - 1).coerceAtLeast(lineStart)
            val originalOffset = positions.getOrNull(generatedOffset) ?: source.length
            val line = source.take(originalOffset).count { it == '\n' } + 1
            val column = originalOffset - source.lastIndexOf('\n', (originalOffset - 1).coerceAtLeast(-1))
            "Lua 第 $line 行，第 $column 列附近：$message"
        }
    }

    /** Execute a `script:` tap-action body in the running script's state. */
    @Synchronized fun runSnippet(code: String, tagResolver: (String) -> String = this.tagResolver): Boolean {
        this.tagResolver = tagResolver
        lastError = null
        if (code.isBlank()) return false
        val body = if (code.startsWith("script:")) code.substring(7) else code
        return runChunk(preprocess(body))
    }

    private fun runChunk(src: String): Boolean {
        return try {
            val chunk = globals.load(src, "@wm_script")
            WmLuaEnv.bounded(globals) { chunk.call() }
            hasScript = true
            refreshVariables()
            true
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    // ── Environment ───────────────────────────────────────────────────────────

    private fun installHelpers() {
        globals.set("wm_tag", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = resolveTagValue(arg.tojstring())
        })
        // wm_* actions from inside the script route to the caller's action handler
        // (the preview maps them to Toast/vibration feedback like layer tap actions).
        globals.set("wm_action", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                actionHandler(arg.tojstring())
                return LuaValue.NIL
            }
        })
        globals.set("wm_sfx", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                actionHandler(if (arg.isnil()) "play_tap" else "play_sound:${arg.tojstring()}")
                return LuaValue.NIL
            }
        })
        globals.set("wm_vibrate", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                actionHandler(if (arg.isnil()) "vibrate" else "vibrate:${arg.tojstring()}")
                return LuaValue.NIL
            }
        })
        globals.set("wm_set_alarm", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                actionHandler(if (arg.isnil()) "set_alarm" else "set_alarm_${arg.tojstring()}")
                return LuaValue.NIL
            }
        })
        val noop1 = object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.NIL
        }
        val noopVar = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = LuaValue.NIL
        }
        globals.set("wm_schedule", noop1)
        globals.set("wm_transition", noop1)
        globals.set("wm_unschedule_all", noopVar)
        // Unknown bare identifiers fall back to tag resolution, mirroring how the
        // on-device runtime exposes template variables to the script. LuaJ calls
        // the __index metamethod with (table, key), so the key is the 2nd arg.
        val mt = LuaTable()
        mt.rawset("__index", object : TwoArgFunction() {
            override fun call(self: LuaValue, key: LuaValue): LuaValue =
                resolveTagValue(key.tojstring())
        })
        globals.setmetatable(mt)
    }

    /**
     * Resolve a `{name}` (or bare `name`) against the running state: live scalar
     * globals first, then the caller's tag resolver, then nil.
     */
    private fun resolveTagValue(raw: String): LuaValue {
        var name = raw.trim()
        if (name.length >= 2 && name.startsWith("{") && name.endsWith("}")) {
            name = name.substring(1, name.length - 1)
        }
        if (name.isEmpty()) return LuaValue.NIL
        val existing = globals.rawget(name)
        if (existing.isboolean() || existing.isnumber() || existing.isstring()) {
            return existing
        }
        val wrapped = "{$name}"
        val resolved = tagResolver(wrapped)
        if (resolved.isEmpty() || resolved == wrapped) return LuaValue.NIL
        return toLuaValue(resolved)
    }

    private fun toLuaValue(s: String): LuaValue {
        val t = s.trim()
        when (t) {
            "true", "TRUE" -> return LuaValue.valueOf(true)
            "false", "FALSE" -> return LuaValue.valueOf(false)
        }
        t.toDoubleOrNull()?.let { return LuaValue.valueOf(it) }
        return LuaValue.valueOf(s)
    }

    // ── {tag} transpilation ───────────────────────────────────────────────────

    private fun preprocess(src: String, positions: MutableList<Int>? = null): String {
        val sb = StringBuilder(src.length)
        fun append(c: Char, originalIndex: Int) {
            sb.append(c)
            positions?.add(originalIndex)
        }
        var inStr = false
        var quote = ' '
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inStr -> {
                    append(c, i)
                    if (c == '\\' && next != '\u0000') { append(next, i + 1); i += 2; continue }
                    if (c == quote) inStr = false
                    i++
                }
                c == '"' || c == '\'' -> { inStr = true; quote = c; append(c, i); i++ }
                c == '-' && next == '-' -> {
                    // comment — drop through to end of line (keep the newline)
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '{' -> {
                    val close = src.indexOf('}', i)
                    if (close > i) {
                        val inner = src.substring(i + 1, close).trim()
                        // Only `{name}` looks like a tag reference; leave Lua table
                        // constructors ({1,2}, {k=v}) untouched.
                        if (inner.isNotEmpty() && !inner.contains('{') && TAG_NAME.matches(inner)) {
                            "wm_tag('{".forEach { append(it, i) }
                            val nameStart = src.indexOf(inner, i + 1)
                            inner.forEachIndexed { index, ch -> append(ch, nameStart + index) }
                            "}')".forEach { append(it, close) }
                            i = close + 1
                            continue
                        }
                    }
                    append(c, i); i++
                }
                else -> { append(c, i); i++ }
            }
        }
        return sb.toString()
    }

    private fun refreshVariables() {
        val out = LinkedHashMap<String, String>()
        for (k in globals.keys()) {
            val name = k.tojstring()
            if (name in RESERVED) continue
            val v = globals.rawget(k)
            when {
                v.isboolean() -> out[name] = if (v.toboolean()) "true" else "false"
                // type() (not isnumber()): numeric-looking strings stay strings.
                v.type() == LuaValue.TNUMBER -> out[name] = WmExpressionEval.formatNumber(v.todouble())
                v.isstring() -> out[name] = v.tojstring()
            }
        }
        variables = out
    }

    private companion object {
        val RESERVED = setOf("_G", "_ENV", "_VERSION", "arg")
        val TAG_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        const val CACHE_MAX = 128
    }
}
