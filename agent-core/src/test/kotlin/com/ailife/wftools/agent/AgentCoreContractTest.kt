package com.ailife.wftools.agent

import com.ailife.wftools.agent.model.AgentConversation
import com.ailife.wftools.agent.model.AgentFeatureSettings
import com.ailife.wftools.agent.provider.AgentJsonParser
import com.ailife.wftools.agent.provider.AgentStreamEvent
import com.ailife.wftools.agent.provider.ChatCompletionsStreamParser
import com.ailife.wftools.agent.runtime.AgentStreamAccumulator
import com.ailife.wftools.agent.runtime.WatchAgentLoop
import com.ailife.wftools.agent.tools.WatchAgentToolCall
import com.ailife.wftools.agent.tools.WatchAgentToolScheduler
import com.ailife.wftools.agent.tools.WatchAgentToolResult
import com.ailife.wftools.agent.tools.AgentWorkspaceImageTools
import com.ailife.wftools.agent.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class AgentCoreContractTest {
    @Test fun `watch agent allows up to fifty provider tool rounds`() {
        assertNull(WatchAgentLoop.DEFAULT_MAX_TURNS)
    }

    @Test fun `feature settings default to disabled and conversations are scoped`() {
        assertFalse(AgentFeatureSettings().enabled)
        val first = AgentConversation.new("face-a", 1L).withFirstUserMessage("调整时间颜色")
        assertEquals("调整时间颜色", first.title)
        assertNotEquals(first.id, AgentConversation.new("face-a", 2L).id)
        assertEquals("face-a", first.watchScope)
    }

    @Test fun `chat stream parser handles fragmented text and tool arguments`() {
        val parser = ChatCompletionsStreamParser()
        val events = parser.feed("data: {\"choices\":[{\"delta\":{\"content\":\"你好\",\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"inspect_face\",\"arguments\":\"{\\\"a\\\":\\\"\"}}]}}]}\n\n") +
            parser.feed("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"b\"}}]}}]}\n\n") +
            parser.feed("data: [DONE]\n\n")
        assertTrue(events.any { it is AgentStreamEvent.TextDelta && it.text == "你好" })
        val calls = events.filterIsInstance<AgentStreamEvent.ToolCallDelta>()
        assertEquals(2, calls.size)
        assertEquals("inspect_face", calls.first().name)
        assertEquals("{\"a\":\"", calls.first().jsonDelta)
        assertEquals("b", calls.last().jsonDelta)
        assertTrue(events.any { it is AgentStreamEvent.Completed })
    }

    @Test fun `tool scheduler never exceeds four concurrent calls`() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = (0 until 8).map { WatchAgentToolCall("$it", "inspect_face") }
        val scheduler = WatchAgentToolScheduler({ call ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, now) }
            Thread.sleep(20)
            active.decrementAndGet()
            WatchAgentToolResult(call.id, call.name, 0L, content = emptyMap<String, Any>())
        }, maxParallel = 4)
        val results = kotlinx.coroutines.runBlocking { scheduler.execute(calls) }
        assertEquals(8, results.size)
        assertTrue(maximum.get() <= 4)
    }

    @Test fun `cancelling a blocking write tool returns without waiting for its timeout`() = kotlinx.coroutines.runBlocking {
        coroutineScope {
        val started = CountDownLatch(1)
        val scheduler = WatchAgentToolScheduler({ call ->
            started.countDown()
            try { Thread.sleep(TimeUnit.SECONDS.toMillis(30)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            WatchAgentToolResult(call.id, call.name, 0L)
        })
        val job = launch(kotlinx.coroutines.Dispatchers.Default) { scheduler.execute(listOf(WatchAgentToolCall("write", "write_workspace_file"))) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(1_000) { job.join() }
        assertTrue(job.isCancelled)
        }
    }

    @Test fun `workspace rejects traversal and enforces quota`() {
        val root = Files.createTempDirectory("agent-workspace")
        val manager = WorkspaceManager(root, defaultQuotaBytes = 4)
        val workspace = manager.create("测试")
        assertThrows(IllegalArgumentException::class.java) {
            manager.resolveInside(workspace.id, "../escape")
        }
        val source = Files.createTempFile("source", ".txt").also { Files.writeString(it, "12345") }
        assertEquals(com.ailife.wftools.agent.workspace.ImportResult.QUOTA_EXCEEDED, manager.importFile(workspace.id, source, "workspace/a.txt"))
        assertThrows(IllegalArgumentException::class.java) { manager.syncFaceMirror(workspace.id, "12345", "") }
    }

    @Test fun `workspace browser lists every supported file under workspace roots and bound face mirror`() {
        val root = Files.createTempDirectory("agent-browser")
        val manager = WorkspaceManager(root)
        val workspace = manager.create("浏览", watchScope = "face")
        Files.writeString(workspace.paths.workspace.resolve("script.py"), "print('ok')")
        Files.writeString(workspace.paths.generated.resolve("notes.txt"), "generated")
        Files.writeString(workspace.paths.face.resolve("watch.xml"), "mirror")
        val faceImage = workspace.paths.face.resolve("images/face.png")
        Files.createDirectories(faceImage.parent)
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it); Files.write(faceImage, it.toByteArray()) }
        val files = AgentWorkspaceImageTools(manager, "face").listFiles()
        assertTrue(files.contains("workspace/script.py"))
        assertTrue(files.contains("generated/notes.txt"))
        assertTrue(files.contains("face/watch.xml"))
        assertEquals("face/images/face.png", AgentWorkspaceImageTools(manager, "face").readImage("images/face.png").path)
    }
}
