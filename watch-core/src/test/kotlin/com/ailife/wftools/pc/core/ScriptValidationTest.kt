package com.ailife.wftools.pc.core

import com.ailife.wftools2.domain.editor.watchmaker.WmScriptRuntime
import org.junit.Assert.*
import org.junit.Test

class ScriptValidationTest {
    @Test(timeout=3000) fun validationCompilesWithoutExecutingOrChangingRuntimeState() {
        val runtime=WmScriptRuntime();val actions=mutableListOf<String>();runtime.actionHandler={actions.add(it)}
        assertTrue(runtime.runScript("var_count=7"));assertFalse(runtime.runSnippet("local )"))
        val variables=runtime.variables;val hasScript=runtime.hasScript;val error=runtime.lastError
        assertNull(runtime.validateScript("var_count=99; wm_action('side-effect'); while true do end"))
        assertEquals("7",runtime.value("var_count"));assertEquals(variables,runtime.variables)
        assertEquals(hasScript,runtime.hasScript);assertEquals(error,runtime.lastError);assertTrue(actions.isEmpty())
    }
    @Test fun errorsReportOriginalLineAndColumnAfterWatchMakerTagExpansion() {
        val runtime=WmScriptRuntime();val code="local first=1\nlocal value={dh} + ) -- ignored )"
        val error=runtime.validateScript(code)
        assertNotNull(error);assertTrue(error!!,error.contains("第 2 行"))
        val column=code.lines()[1].indexOf(')')+1
        assertTrue(error,error.contains("第 $column 列"))
        assertNull(runtime.lastError);assertFalse(runtime.hasScript)
    }
    @Test fun blankAndTaggedScriptsAreValidWithoutInstallingVariables() {
        val runtime=WmScriptRuntime()
        assertNull(runtime.validateScript(" \n\t"))
        assertNull(runtime.validateScript("var_time={dh24}*3600+{dm}*60; var_literal='{dh}'"))
        assertFalse(runtime.has("var_time"));assertFalse(runtime.has("var_literal"));assertFalse(runtime.hasScript)
    }
}
