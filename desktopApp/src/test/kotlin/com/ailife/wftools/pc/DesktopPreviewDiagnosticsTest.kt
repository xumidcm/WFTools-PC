package com.ailife.wftools.pc

import com.ailife.wftools.agent.tools.WatchAgentPreviewRequest
import com.ailife.wftools.agent.tools.WatchAgentToolResult
import com.ailife.wftools.agent.tools.InMemoryWatchAgentToolHost
import com.ailife.wftools.agent.tools.WatchAgentToolRunner
import com.ailife.wftools.agent.runtime.AgentCompletionExecutor
import com.ailife.wftools.agent.runtime.WatchAgentLoop
import com.ailife.wftools.pc.core.WatchDocumentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopPreviewDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `preview result exposes rendered layer and non background signals`() {
        val store = WatchDocumentStore(temp.newFolder("preview-diagnostics").toPath())
        val session = store.newDocument("空表盘")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val doc = runBlocking { DocumentController.create(session, store, scope, EditorPreferences(temp.newFolder("prefs").toPath())) }
        try {
            val result = DesktopPreviewRenderer(doc).render(WatchAgentPreviewRequest())
            val encoder = WatchAgentLoop(
                AgentCompletionExecutor { _, _ -> error("not used") },
                WatchAgentToolRunner(InMemoryWatchAgentToolHost("<Watch/>")),
            )
            val json = encoder.encodeToolResult(
                WatchAgentToolResult("preview", "preview_face", result.revision, content = result),
            )
            assertTrue(json.contains("rendered_layers"))
            assertTrue(json.contains("non_background_pixel_ratio"))
            assertTrue(json.contains("renderer_warning"))
            assertTrue(json.contains("no_rendered_layers"))
        } finally {
            doc.render.close()
            session.close()
            scope.cancel()
        }
    }
}
