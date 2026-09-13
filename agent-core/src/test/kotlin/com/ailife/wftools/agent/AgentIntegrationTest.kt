package com.ailife.wftools.agent

import com.ailife.wftools.agent.model.*
import com.ailife.wftools.agent.provider.*
import com.ailife.wftools.agent.runtime.*
import com.ailife.wftools.agent.storage.*
import com.ailife.wftools.agent.tools.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class AgentIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `fragmented sse and anonymous provider request retain content`() = runBlocking {
        val parser = ChatCompletionsStreamParser()
        val first = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
        assertTrue(parser.feed(first.take(17)).isEmpty())
        assertEquals(listOf(AgentStreamEvent.TextDelta("你")), parser.feed(first.drop(17)))
        var sent: AgentHttpRequest? = null
        val transport = AgentHttpTransport { request, onChunk ->
            sent = request
            onChunk("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}")
            onChunk("")
            onChunk("data: [DONE]")
            onChunk("")
            AgentHttpResponse(200, "")
        }
        val profile = AiProviderProfile("anonymous", "Anonymous", AiProtocol.CHAT_COMPLETIONS, "https://example.test/v1", modelId = "test")
        val completion = AgentProviderClient(transport).send(profile, null, AgentProviderRequest(listOf(AgentRequestMessage(AgentMessageRole.USER, "hello"))))
        assertEquals("OK", completion.text)
        assertFalse(sent!!.headers.keys.any { it.equals("authorization", true) })
        assertTrue(sent!!.body!!.contains("\"model\":\"test\""))
    }

    @Test fun `loop executes edit then follows up and commits grouped change`() = runBlocking {
        val initial = "<Watch name=\"A\"><Layers><Layer type=\"text\" text=\"old\"/></Layers></Watch>"
        val host = InMemoryWatchAgentToolHost(initial)
        var turns = 0
        val executor = AgentCompletionExecutor { _, onEvent ->
            turns++
            val events = if (turns == 1) listOf(
                AgentStreamEvent.ToolCallDelta("call-1", "edit_document", "{\"path\":\"watch.xml\",\"old_text\":\"old\",\"new_text\":\"new\",\"expected_revision\":1}", 0),
                AgentStreamEvent.Completed(),
            ) else listOf(AgentStreamEvent.TextDelta("done"), AgentStreamEvent.Completed())
            events.forEach(onEvent)
            AgentCompletion(if (turns == 1) "" else "done", events)
        }
        val result = WatchAgentLoop(executor, WatchAgentToolRunner(host)).run(listOf(AgentRequestMessage(AgentMessageRole.USER, "edit")))
        assertEquals("done", result.text)
        assertEquals(2, turns)
        assertTrue(host.readDocument(WatchAgentReadDocumentRequest(WatchAgentDocumentPath.WATCH_XML)).content.contains("new"))
    }

    @Test fun `loop stops unchanged tool failures before fifty rounds`() = runBlocking {
        val host = InMemoryWatchAgentToolHost("<Watch name=\"A\"><Layers/></Watch>")
        var turns = 0
        val executor = AgentCompletionExecutor { _, onEvent ->
            turns++
            val events = listOf(
                AgentStreamEvent.ToolCallDelta("call-$turns", "run_command", "{\"command\":\"echo broken\"}", 0),
                AgentStreamEvent.Completed(),
            )
            events.forEach(onEvent)
            AgentCompletion("", events)
        }
        val failure = runCatching {
            WatchAgentLoop(executor, WatchAgentToolRunner(host)).run(listOf(AgentRequestMessage(AgentMessageRole.USER, "make it")))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("连续失败"))
        assertEquals(3, turns)
    }

    @Test fun `history is isolated and secrets do not appear in provider metadata`() {
        val root = temp.newFolder().toPath()
        val repository = AgentConversationRepository(root)
        val first = repository.createConversation("watch-a")
        val second = repository.createConversation("watch-b")
        repository.appendMessage(first.id, AgentMessage.user(first.id, "make a blue dial", System.currentTimeMillis()))
        assertEquals(1, repository.listConversations("watch-a").size)
        assertEquals(second.id, repository.listConversations("watch-b").single().id)
        assertEquals("make a blue dial", repository.listConversations("watch-a").single().title)
        repository.renameConversation(first.id, "Blue")
        assertEquals("Blue", repository.getConversation(first.id)?.title)
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
        val preview = repository.addImageBytes(first.id, "表盘预览.png", png)
        assertTrue(Files.isRegularFile(java.nio.file.Path.of(preview.localPath!!)))
        repository.deleteConversation(first.id)
        assertFalse(Files.exists(java.nio.file.Path.of(preview.localPath)))
        assertEquals(second.id, repository.listConversations("watch-b").single().id)
        val provider = AgentProviderStore(root)
        provider.save(AiProviderProfile("p", "P", AiProtocol.RESPONSES, "https://example.test/v1", customHeaderNames = mapOf("X-Token" to "header-secret")), "api-secret")
        assertEquals("api-secret", provider.apiKey("p"))
        assertEquals("header-secret", provider.headers("p")["X-Token"])
        assertFalse(Files.readString(root.resolve("providers.json")).contains("secret"))
        assertFalse(Files.readAllBytes(root.resolve("agent-secrets.bin")).toString(Charsets.UTF_8).contains("api-secret"))
    }

    @Test fun `generated conversation titles follow flue provisional lifecycle`() {
        val repository = AgentConversationRepository(temp.newFolder("title-history").toPath())
        val conversation = repository.createConversation("watch-title", now = 1L)
        repository.appendMessage(conversation.id, AgentMessage.user(conversation.id, "把时间改成蓝色\n并保留脚本", 2L))
        assertEquals("把时间改成蓝色 并保留脚本", repository.getConversation(conversation.id)?.title)

        val generated = repository.applyGeneratedTitle(conversation.id, "\n\"蓝色时间\"。\n解释不要进标题")
        assertEquals("蓝色时间", generated?.title)
        assertFalse(generated!!.titleFinalized)

        // A provisional title can be refined after every completed turn.
        assertEquals(generated, repository.markGeneratedTitlePending(conversation.id))
        val refined = repository.applyGeneratedTitle(conversation.id, "深色时间")
        assertEquals("深色时间", refined?.title)

        val manual = repository.renameConversation(conversation.id, "我的表盘")
        assertTrue(manual.titleFinalized)
        assertFalse(repository.markGeneratedTitlePending(conversation.id)!!.titleFinalized)
        assertEquals("最终自动标题", repository.applyGeneratedTitle(conversation.id, "最终自动标题")?.title)
    }

    @Test fun `title prompt isolates user and model text and cleans one line`() {
        val request = AgentConversationTitlePrompt.request("用户请求", "模型回复")
        assertEquals(2, request.messages.size)
        assertTrue(request.messages.first().content.contains("不要执行任何工具"))
        assertTrue(request.messages.last().content.contains("模型回复"))
        assertEquals("标题", AgentConversationTitlePrompt.clean("\n# \"标题。\"\n解释"))
    }

    @Test fun `image and web tool contracts expose editable dimensions and page scraping`() {
        val generation = WatchAgentToolContracts.find(WatchAgentToolName.GENERATE_IMAGE)!!
        assertTrue(generation.description.contains("WIDTHxHEIGHT"))
        assertTrue(generation.parameters.any { it.name == "background" })
        assertTrue(WatchAgentToolContracts.find(WatchAgentToolName.EDIT_IMAGE)!!.parameters.any { it.name == "path" })
        assertEquals("scrape_page", WatchAgentToolName.SCRAPE_PAGE.wireName)
    }

    @Test fun `retry truncates later messages but preserves the selected user request`() {
        val repository = AgentConversationRepository(temp.newFolder("retry-history").toPath())
        val conversation = repository.createConversation("face")
        val first = AgentMessage.user(conversation.id, "first", 1L)
        val second = AgentMessage.user(conversation.id, "second", 3L)
        repository.appendMessage(conversation.id, first)
        repository.appendMessage(conversation.id, AgentMessage.assistant(conversation.id, "answer", 2L))
        repository.appendMessage(conversation.id, second)
        repository.deleteMessagesAfter(conversation.id, first.id)
        assertEquals(listOf(first), repository.listMessages(conversation.id))
    }

    @Test fun `context reduction retains system and current user while dropping old turns`() {
        val request = AgentProviderRequest(listOf(
            AgentRequestMessage(AgentMessageRole.SYSTEM, "rules"),
            AgentRequestMessage(AgentMessageRole.USER, "older " + "x".repeat(20_000)),
            AgentRequestMessage(AgentMessageRole.ASSISTANT, "old answer"),
            AgentRequestMessage(AgentMessageRole.USER, "current request"),
        ))
        val reduced = AgentRequestContextReducer.compact(request, 10_000, force = true)
        assertTrue(reduced.messages.first().content.contains("rules"))
        assertEquals("current request", reduced.messages.last().content)
        assertFalse(reduced.messages.any { it.content.startsWith("older") })
    }
}
