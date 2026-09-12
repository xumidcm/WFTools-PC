package com.ailife.wftools2.domain.editor.watchmaker

import io.github.taoguan.luaj.Globals
import io.github.taoguan.luaj.LuaValue
import kotlin.math.floor

/**
 * Lua expression evaluator for WatchMaker attributes, backed by a pure-Java
 * Lua 5.2 VM (`io.github.taoguan:luaj` — no native libraries).
 *
 * WatchMaker faces are evaluated by a real Lua runtime on-device; a hand-written
 * expression parser can only ever approximate that. Delegating to a full Lua VM
 * gives accurate operator semantics (floored `%`, Lua truthiness, string/number
 * coercion) and the complete string/math/table/bit32 libraries.
 *
 * Scope handling:
 *  - Global-script variables from `scripts/script.txt` are registered as Lua
 *    globals, so bare identifiers (`var_face == 0 and 100 or -1`) resolve.
 *  - Numeric-looking variable values are registered as numbers (matching how the
 *    old evaluator read them) so arithmetic on them works.
 *  - Lua 5.1 compatibility shims (`math.atan2`, `math.mod`, `math.log10`,
 *    `table.getn`) cover faces written against WatchMaker's Lua 5.1 runtime.
 *
 * Result formatting matches CloX / Lua `"%.6g"` (not the full-precision
 * `tostring` a bare VM produces), so rendered numbers keep the same shape.
 *
 * Standalone evaluation gets a fresh VM. Document previews use their persistent
 * WmScriptRuntime instead, so global/library mutations cannot cross documents.
 */
object WmExpressionEval {

    // Global-script variables in scope for the current eval, name → raw value.
    // Held per-thread; nested evals inherit the caller's scope.
    private val activeVars = ThreadLocal.withInitial { emptyMap<String, String>() }

    /**
     * Evaluate a Lua expression; returns the original string on failure.
     * [vars] supplies global-script variables resolvable as bare identifiers.
     */
    fun eval(expr: String, vars: Map<String, String> = emptyMap()): String {
        if (expr.isBlank()) return expr
        expr.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return formatNumber(it) }
        val prev = activeVars.get()
        // Inherit the caller's scope when no vars are supplied (nested evals).
        activeVars.set(if (vars.isEmpty()) prev else vars)
        return try {
            val g = newGlobals()
            registerVars(g, activeVars.get())
            val chunk = compile(expr.trim(), g) ?: return expr
            WmLuaEnv.bounded(g) { fmt(luaToKotlin(chunk.call())) }
        } catch (_: Exception) {
            expr
        } finally {
            activeVars.set(prev)
        }
    }

    private fun fmt(value: Any): String = when (value) {
        is Boolean -> if (value) "true" else "false"
        is Double -> formatNumber(value)
        else -> value.toString()
    }

    /**
     * Render a Lua number the way CloX __wn / Lua "%.6g" does: ~6 significant
     * digits, trailing zeros trimmed, whole numbers without ".0". Shared with the
     * global-script runtime so variables format identically everywhere.
     */
    internal fun formatNumber(value: Double): String =
        if (!value.isInfinite() && !value.isNaN() && value == floor(value))
            value.toLong().toString()
        else {
            // Match CloX __wn / Lua "%.6g": ~6 significant digits, trailing
            // zeros trimmed (was "%.4f" — 4 decimals, which diverges for
            // values >1, e.g. 123.456789 → "123.4568" vs CloX "123.457").
            val g = "%.6g".format(java.util.Locale.ROOT, value)
            if (g.contains('.') && !g.contains('e') && !g.contains('E'))
                g.trimEnd('0').trimEnd('.')
            else g
        }

    private fun luaToKotlin(v: LuaValue): Any = when {
        // Model nil as Boolean false, as the old evaluator did.
        v.isnil() -> false
        v.isboolean() -> v.toboolean()
        // type() (not isnumber()): a numeric STRING is still a string — "007" must
        // keep its leading zeros, only a real number value formats numerically.
        v.type() == LuaValue.TNUMBER -> v.todouble()
        else -> v.tojstring()
    }

    /** Render any Lua result value the way expressions render. Shared with the script runtime. */
    internal fun formatResult(v: LuaValue): String = fmt(luaToKotlin(v))

    private fun registerVars(g: Globals, vars: Map<String, String>) {
        for ((k, v) in vars) {
            val num = v.trim().toDoubleOrNull()
            g.set(k, if (num != null) LuaValue.valueOf(num) else LuaValue.valueOf(v))
        }
    }

    private fun compile(src: String, g: Globals): LuaValue? {
        if (src.isBlank()) return null
        return try {
            g.load("return " + src, "@wm_expr")
        } catch (_: Exception) {
            null
        }
    }

    private fun newGlobals(): Globals = WmLuaEnv.newGlobals()
}
