package com.ailife.wftools.agent.runtime

import com.ailife.wftools.agent.model.AgentMessage
import com.ailife.wftools.agent.model.AgentMessageRole
import com.ailife.wftools.agent.provider.AgentProviderRequest
import com.ailife.wftools.agent.provider.AgentRequestMessage
import java.util.UUID

/** A tool-free, visible-text-only request for durable context compression. */
object AgentConversationCompressionPrompt {
    val system = """
你是纯会话上下文压缩器。只总结用户消息和模型最终可见的文字，保留目标、约束、已经确认的决定和未完成事项。
不要执行工具，不要编辑、验证或预览表盘，不要输出工具名称、参数、JSON、命令、日志、revision 或错误堆栈。
只输出简洁的中文摘要正文，不要标题前缀、Markdown 或解释。
""".trimIndent()

    fun request(messages: List<AgentMessage>): AgentProviderRequest {
        val transcript = visible(messages).joinToString("\n") { message ->
            val speaker = if (message.role == AgentMessageRole.USER) "用户" else "模型"
            "$speaker：${message.content.take(6000)}"
        }
        return AgentProviderRequest(
            messages = listOf(
                AgentRequestMessage(AgentMessageRole.SYSTEM, system),
                AgentRequestMessage(AgentMessageRole.USER, transcript.take(48_000)),
            ),
            stream = false,
            tools = emptyList(),
            maxOutputTokens = 2048,
        )
    }

    fun replacement(conversationId: String, messages: List<AgentMessage>, summary: String, now: Long = System.currentTimeMillis()): List<AgentMessage> {
        require(summary.isNotBlank()) { "上下文摘要不能为空" }
        val keep = visible(messages).takeLast(6)
        val summaryMessage = AgentMessage(
            id = UUID.randomUUID().toString(), conversationId = conversationId,
            role = AgentMessageRole.ASSISTANT, content = "上下文摘要：${summary.trim()}", createdAt = now,
        )
        return listOf(summaryMessage) + keep
    }

    private fun visible(messages: List<AgentMessage>): List<AgentMessage> = messages.filter { message ->
        message.content.isNotBlank() &&
            (message.role == AgentMessageRole.USER || message.role == AgentMessageRole.ASSISTANT && message.toolCalls.isEmpty())
    }
}
