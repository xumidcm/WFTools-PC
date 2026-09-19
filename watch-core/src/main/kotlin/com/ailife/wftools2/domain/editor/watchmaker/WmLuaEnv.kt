package com.ailife.wftools2.domain.editor.watchmaker

import io.github.taoguan.luaj.Globals
import io.github.taoguan.luaj.LoadState
import io.github.taoguan.luaj.LuaTable
import io.github.taoguan.luaj.LuaValue
import io.github.taoguan.luaj.LuaString
import io.github.taoguan.luaj.Varargs
import io.github.taoguan.luaj.compiler.LuaC
import io.github.taoguan.luaj.lib.Bit32Lib
import io.github.taoguan.luaj.lib.DebugLib
import io.github.taoguan.luaj.lib.OneArgFunction
import io.github.taoguan.luaj.lib.StringLib
import io.github.taoguan.luaj.lib.TableLib
import io.github.taoguan.luaj.lib.TwoArgFunction
import io.github.taoguan.luaj.lib.VarArgFunction
import io.github.taoguan.luaj.lib.jse.JseBaseLib
import io.github.taoguan.luaj.lib.jse.JseMathLib

/**
 * Shared factory for the pure-Java Lua environments (io.github.taoguan:luaj —
 * no native libraries) used by the expression evaluator and the global-script
 * runtime.
 *
 * LuaJ targets Lua 5.2, but WatchMaker faces are written against Lua 5.1, so the
 * renamed/removed base functions (`unpack`, `loadstring`, `table.maxn`,
 * `string.gfind`, ...) are restored as aliases/stubs, plus the math shims, and the
 * filesystem/os entry points a preview must never reach are sandboxed.
 */
object WmLuaEnv {

    // LuaJ's string metatable is process-wide. Dispatch colon-method calls through
    // the active document's string library, and hide the shared metatable from Lua.
    private val activeEnvironment = ThreadLocal<Globals?>()
    private val stringMetatable = LuaTable().apply {
        set("__index", object : TwoArgFunction() {
            override fun call(self: LuaValue, key: LuaValue): LuaValue = activeEnvironment.get()?.get("string")?.get(key) ?: LuaValue.NIL
        })
        set("__metatable", LuaValue.valueOf("protected"))
    }

    fun newGlobals(): Globals {
        LuaString.s_metatable = stringMetatable
        // Build the preview environment from an allow-list. Besides keeping host
        // I/O out of the VM, this avoids deleting entries from LuaJ's global table.
        // luaj 1.0.3 leaves a DeadSlot for each deletion and crashes in rehash()
        // once a large WatchMaker script declares enough global variables.
        val g = SandboxGlobals().apply {
            load(JseBaseLib())
            load(Bit32Lib())
            load(TableLib())
            load(StringLib())
            load(JseMathLib())
            LoadState.install(this)
            LuaC.install(this)
        }
        g.load(InstructionBudget())
        val math = g.get("math")
        // Lua 5.1 → 5.2 renames/removals WatchMaker faces still rely on.
        math.set("atan2", object : TwoArgFunction() {
            override fun call(y: LuaValue, x: LuaValue): LuaValue = math.get("atan").call(y, x)
        })
        math.set("mod", math.get("fmod"))
        val ln10 = Math.log(10.0)
        math.set("log10", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf(Math.log(arg.todouble()) / ln10)
        })
        // Lua 5.1 base-library functions removed in 5.2.
        val table = g.get("table")
        table.set("getn", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = LuaValue.valueOf(arg.length())
        })
        table.set("maxn", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                var max = 0.0
                if (arg.istable()) {
                    // __len is user code and may report billions of nonexistent
                    // entries. Walk actual keys; sparse numeric keys count too.
                    for (key in (arg as LuaTable).keys()) {
                        checkBudget(g)
                        if (key.type() == LuaValue.TNUMBER) max = maxOf(max, key.todouble())
                    }
                    val n = arg.get("n")
                    if (n.isnumber()) max = maxOf(max, n.todouble())
                }
                return LuaValue.valueOf(max)
            }
        })
        table.set("foreach", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val t = args.arg1()
                val f = args.arg(2)
                if (t.istable() && f.isfunction()) {
                    val lt = t as LuaTable
                    for (k in lt.keys()) { checkBudget(g); f.call(k, lt.rawget(k)) }
                }
                return LuaValue.NIL
            }
        })
        table.set("foreachi", object : TwoArgFunction() {
            override fun call(t: LuaValue, f: LuaValue): LuaValue {
                var i = 1
                while (true) {
                    checkBudget(g)
                    val v = t.get(i)
                    if (v.isnil()) break
                    f.call(LuaValue.valueOf(i), v)
                    i++
                }
                return LuaValue.NIL
            }
        })
        // `unpack`/`loadstring` were plain globals in Lua 5.1.
        g.set("unpack", table.get("unpack"))
        g.set("loadstring", g.get("load"))
        g.get("string").set("gfind", g.get("string").get("gmatch"))
        return g
    }

    /**
     * BaseLib installs a few host-facing functions while it initialises the safe
     * language primitives. Ignore those names before they ever enter the table;
     * assigning NIL afterwards creates the LuaJ DeadSlots that caused #rehash to
     * throw for faces with hundreds of script variables.
     */
    private class SandboxGlobals : Globals() {
        init {
            // WatchMaker scripts commonly expose hundreds of scalar globals. Keep
            // their writes below LuaJ 1.0.3's fragile rehash threshold.
            presize(0, 4096)
        }

        override fun set(key: LuaValue, value: LuaValue) {
            if (key.isstring() && key.tojstring() in HIDDEN_GLOBALS) return
            super.set(key, value)
        }
    }

    private val HIDDEN_GLOBALS = setOf("dofile", "loadfile", "debug", "print")

    private fun checkBudget(globals: Globals) = (globals.debuglib as InstructionBudget).checkpoint()

    /** Every outer Lua call owns a budget; nested tag evaluation shares it. */
    internal fun <T> bounded(globals: Globals, block: () -> T): T {
        val budget = globals.debuglib as InstructionBudget
        val previousEnvironment = activeEnvironment.get()
        activeEnvironment.set(globals)
        val outer = budget.depth++ == 0
        if (outer) budget.reset()
        return try { block() }
        catch (e: BudgetExceeded) { if (outer) throw IllegalStateException(e.message) else throw e }
        finally {
            budget.depth--
            if (previousEnvironment == null) activeEnvironment.remove() else activeEnvironment.set(previousEnvironment)
        }
    }

    // Error deliberately bypasses Lua pcall/xpcall's Exception catch. Convert it
    // only after the VM unwinds to our host boundary; otherwise Lua can loop forever
    // repeatedly swallowing a timeout. No background VM thread is left running.
    private class BudgetExceeded : Error("Lua instruction budget exceeded; simplify this script or callback.")
    private class InstructionBudget : DebugLib() {
        var depth = 0
        private var remaining = 500_000
        private var deadline = 0L
        fun reset() { remaining = 500_000; deadline = System.nanoTime() + 200_000_000L }
        override fun onInstruction(pc: Int, v: Varargs, top: Int) = checkpoint()
        fun checkpoint() {
            if (--remaining <= 0 || (remaining % 1024 == 0 &&
                    (System.nanoTime() > deadline || Thread.currentThread().isInterrupted))) throw BudgetExceeded()
        }
    }
}
