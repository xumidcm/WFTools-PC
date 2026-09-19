package com.ailife.wftools.pc.core

import com.ailife.wftools2.domain.editor.watchmaker.*
import org.junit.Assert.*
import org.junit.Test

class LuaRuntimeTest {
    @Test fun numericExpressionsKeepDecimalPointUnderCommaLocale() {
        val previous=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("14.125",WmExpressionEval.eval("14.125"))
            assertEquals("14.125",WmExpressionEval.eval("113 / 8"))
            val runtime=WmScriptRuntime();assertTrue(runtime.runScript("var_x=113/8"))
            assertEquals("14.125",runtime.value("var_x"))
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test fun scriptStateIsIsolatedAndFormattingIsPreserved() {
        val first=WmScriptRuntime(); val second=WmScriptRuntime()
        assertTrue(first.runScript("var_count = 4; var_string = '007'; function next_count() var_count=var_count+1 end"))
        assertTrue(second.runScript("var_count = 20")); first.runSnippet("next_count()")
        assertEquals("5",first.value("var_count")); assertEquals("20",second.value("var_count")); assertEquals("007",first.value("var_string"))
        assertEquals("3",WmExpressionEval.eval("-5 % 4"))
    }

    @Test fun largeWatchMakerScriptWithParallelAssignmentsRunsWithoutLuaTableRehashFailure() {
        val script = buildString {
            appendLine("var_c1_max,var_c1_min,var_c1_val=100,0,50")
            repeat(240) { index ->
                val n = index + 1
                appendLine("var_f${n}_x,var_f${n}_y,var_f${n}_r,var_f${n}_kx,var_f${n}_sx,var_f${n}_sy,var_f${n}_op=0,0,0,0,100,100,0")
            }
        }
        val runtime = WmScriptRuntime()
        assertTrue("large WatchMaker scripts must execute: ${runtime.lastError}", runtime.runScript(script))
        assertEquals("100", runtime.value("var_f240_sx"))
        assertEquals("100", runtime.value("var_f240_sy"))
        assertEquals("0", runtime.value("var_f240_op"))
    }

    @Test fun largeScriptKeepsImplicitLocalChainsAndInlineVariableAssignments() {
        val script = buildString {
            repeat(240) { index ->
                appendLine("var_seed_${index + 1}=${index + 0.125}")
            }
            appendLine("local radians=1 degrees=57.2958 offset=2")
            appendLine("local low=.1 medium=.2 high=.3")
            appendLine("var_inline_a=degrees var_inline_b=offset var_inline_c=high")
            appendLine("var_result=var_inline_a+var_inline_b+var_inline_c")
        }
        val runtime = WmScriptRuntime()
        assertTrue("inline WatchMaker statements must execute: ${runtime.lastError}", runtime.runScript(script))
        assertEquals("59.5958", runtime.value("var_result"))
        assertEquals("0.3", runtime.value("var_inline_c"))
    }

    @Test fun longLuaStringsAndCommentsAreOpaqueToWatchMakerAssignmentNormalization() {
        val script = """
            --[=[ var_comment_should_stay_comment=1 ]=]
            var_before=1
            var_text=[[VAR_INSIDE_LONG_STRING=2]]
            local left=1 right=2
            var_after=left+right
        """.trimIndent()
        val runtime = WmScriptRuntime()
        assertTrue("long Lua regions must not break execution: ${runtime.lastError}", runtime.runScript(script))
        assertEquals("1", runtime.value("var_before"))
        assertEquals("VAR_INSIDE_LONG_STRING=2", runtime.value("var_text"))
        assertEquals("3", runtime.value("var_after"))
        assertNull(runtime.value("var_comment_should_stay_comment"))
    }

    @Test(timeout=3000) fun infiniteLoopIsCancelledEvenInsideProtectedCall() {
        val runtime=WmScriptRuntime()
        assertFalse(runtime.runScript("while true do pcall(function() while true do end end) end"))
        assertTrue(runtime.lastError.orEmpty().contains("budget",ignoreCase=true))
        assertTrue(runtime.runScript("var_ok = 1")); assertEquals("1",runtime.value("var_ok"))
    }

    @Test(timeout=3000) fun callbackAndExpressionLoopsAreBounded() {
        val runtime=WmScriptRuntime(); assertTrue(runtime.runScript("function forever() while true do end end; function on_second() forever() end"))
        runtime.fireTimeCallbacks(1,2,3); assertNotNull(runtime.lastError)
        assertEquals("forever()",runtime.eval("forever()"))
        val expression="(function() while true do end end)()"; assertEquals(expression,WmExpressionEval.eval(expression))
    }

    @Test fun filesystemJavaModulesAndDebugHooksAreUnavailable() {
        val g=WmLuaEnv.newGlobals()
        for (name in listOf("io","luajava","package","require","dofile","loadfile","debug","coroutine")) assertTrue(name,g.get(name).isnil())
        assertTrue(g.get("os").isnil())
    }
}
