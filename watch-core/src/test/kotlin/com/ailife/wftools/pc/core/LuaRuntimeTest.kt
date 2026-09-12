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
        assertTrue(g.get("os").get("execute").isnil())
    }
}
