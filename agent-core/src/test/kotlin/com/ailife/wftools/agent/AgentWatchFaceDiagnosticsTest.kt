package com.ailife.wftools.agent

import com.ailife.wftools.agent.prompt.WatchAgentPrompt
import com.ailife.wftools.agent.tools.InMemoryWatchAgentToolHost
import com.ailife.wftools.agent.tools.WatchAgentInspectFaceRequest
import com.ailife.wftools.agent.tools.WatchAgentKnowledgeCatalog
import com.ailife.wftools.agent.tools.WatchAgentSearchKnowledgeRequest
import com.ailife.wftools.agent.tools.WatchAgentToolResult
import com.ailife.wftools.agent.tools.WatchAgentToolRunner
import com.ailife.wftools.agent.tools.WatchAgentValidateFaceRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWatchFaceDiagnosticsTest {
    @Test fun `inspect and validate expose xml layer count when parser returns no layers`() = runBlocking {
        val xml = """
            <Watch name="broken-layer-schema"><Layers>
              <Image path="bg.png"/><Text text="12:34"/>
            </Layers></Watch>
        """.trimIndent()
        val host = InMemoryWatchAgentToolHost(xml)
        val inspect = host.inspectFace(WatchAgentInspectFaceRequest())
        val encoder = com.ailife.wftools.agent.runtime.WatchAgentLoop(
            com.ailife.wftools.agent.runtime.AgentCompletionExecutor { _, _ -> error("not used") },
            WatchAgentToolRunner(host),
        )
        val inspectJson = encoder.encodeToolResult(
            WatchAgentToolResult("inspect", "inspect_face", inspect.revision, content = inspect),
        )
        val validation = host.validateFace(WatchAgentValidateFaceRequest())

        assertTrue("inspect 必须暴露 XML 中的图层节点数", inspectJson.contains("layers_in_xml"))
        assertTrue("inspect 必须暴露解析出的图层数", inspectJson.contains("layers_parsed"))
        assertTrue("schema 不匹配必须阻止假绿", validation.diagnostics.any { it.code == "layer_parse_mismatch" })
        assertFalse(validation.valid)
    }

    @Test fun `knowledge search contains a minimal renderable template and centered coordinates`() {
        val store = com.ailife.wftools.agent.tools.WatchAgentKnowledgeStore(WatchAgentKnowledgeCatalog.entries)
        val result = store.search(WatchAgentSearchKnowledgeRequest("最小可渲染 watch.xml 坐标原点" ).query)
        val text = result.hits.joinToString("\n") { it.snippet }
        assertTrue("知识库必须给出 image/text/shape 最小模板", text.contains("type=\"image\"") && text.contains("type=\"text\"") && text.contains("type=\"shape\""))
        assertTrue("知识库必须明确中心原点", text.contains("(256,256)"))
    }

    @Test fun `prompt requires layer count self check after writes`() {
        assertTrue(WatchAgentPrompt.baseline.contains("layers_in_xml"))
        assertTrue(WatchAgentPrompt.baseline.contains("写入后必须核对"))
    }
}
