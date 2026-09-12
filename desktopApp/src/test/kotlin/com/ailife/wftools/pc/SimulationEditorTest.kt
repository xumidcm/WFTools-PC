package com.ailife.wftools.pc

import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class SimulationEditorTest {
    @Test fun `scalar and structured simulation edits preserve every unrelated field`() {
        val initial = WmTagsData.WmPreviewState(steps = 99, variables = mapOf("var_test" to "42"))
        val changed = SimulationEditor.replace(initial,"batteryPercentage","23")
        assertEquals(initial.copy(batteryPercentage = 23),changed)
        assertEquals(initial.copy(heartRateHistory = listOf(60,65,70)),SimulationEditor.replace(initial,"heartRateHistory","60,65,70"))
        assertEquals(initial.copy(acceleration = Triple(1f,2f,3f)),SimulationEditor.replace(initial,"acceleration","1,2,3"))
        val time = LocalDateTime.of(2026,9,11,13,14,15)
        assertEquals(time,SimulationEditor.replace(initial,"now",time.toString()).now)
        assertNull(SimulationEditor.replace(initial,"alarmTime","").alarmTime)
    }
    @Test fun `all user simulation data survives text serialization while runtime fields stay private`() {
        val initial = WmTagsData.WmPreviewState(weatherLocation = "上海", heartRateHistory = listOf(56,72), acceleration = Triple(2f,-1f,5f), alarmTime = null)
        val values = SimulationEditor.values(initial)
        assertFalse(values.containsKey("scriptRuntime")); assertFalse(values.containsKey("variables")); assertFalse(values.containsKey("tagOverride")); assertFalse(values.containsKey("elapsedSeconds"))
        assertEquals(initial,SimulationEditor.restore(WmTagsData.WmPreviewState(),values))
    }
    @Test fun `invalid numeric or internal edits fail without changing state`() {
        val original = WmTagsData.WmPreviewState()
        listOf("latitude" to "NaN", "steps" to "42px", "acceleration" to "1,2", "scriptRuntime" to "fake").forEach { (key,value) ->
            assertTrue(runCatching { SimulationEditor.replace(original,key,value) }.isFailure)
        }
        assertEquals(original,SimulationEditor.restore(original,mapOf("steps" to "bad")))
    }
}
