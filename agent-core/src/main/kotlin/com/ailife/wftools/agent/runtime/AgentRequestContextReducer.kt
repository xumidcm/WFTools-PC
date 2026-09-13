package com.ailife.wftools.agent.runtime

import com.ailife.wftools.agent.model.AgentMessageRole
import com.ailife.wftools.agent.provider.AgentProviderRequest
import com.ailife.wftools.agent.provider.AgentRequestMessage

/** Drops oldest completed conversation turns while retaining system guidance and the current user turn. */
object AgentRequestContextReducer {
    fun estimateTokens(request: AgentProviderRequest): Long = request.messages.sumOf { message ->
        (message.content.length + message.toolCalls.sumOf { it.argumentsJson.length }) / 4L + message.images.size * 1_500L + 16L
    } + request.tools.sumOf { (it.description.orEmpty().length + it.parametersJson.length) / 4L + 16L }

    fun compact(request: AgentProviderRequest, contextWindowTokens: Long, force: Boolean = false): AgentProviderRequest {
        val target = (contextWindowTokens - (request.maxOutputTokens ?: 8192) - AgentContextBudgetManager.DEFAULT_SAFETY_MARGIN_TOKENS).coerceAtLeast(1024L)
        if (!force && estimateTokens(request) <= target) return request
        val system = request.messages.filter { it.role == AgentMessageRole.SYSTEM }
        val ordinary = request.messages.filterNot { it.role == AgentMessageRole.SYSTEM }
        val lastUser = ordinary.indexOfLast { it.role == AgentMessageRole.USER }.coerceAtLeast(0)
        val current = ordinary.drop(lastUser)
        val history = ordinary.take(lastUser).toMutableList()
        val forceUntil = if (force) (history.size / 2 + 1) else 0
        var removed = 0
        while (history.isNotEmpty() && (removed < forceUntil || estimateTokens(request.copy(messages = system + history + current)) > target)) {
            val nextUser = history.indexOfFirst { it.role == AgentMessageRole.USER }
            val removeCount = if (nextUser > 0) nextUser else history.indexOfFirst { it.role == AgentMessageRole.USER && it !== history.first() }.takeIf { it > 0 } ?: history.size
            repeat(removeCount.coerceAtMost(history.size)) { history.removeAt(0); removed++ }
            if (history.isEmpty()) break
        }
        val summary = if (removed > 0) listOf(AgentRequestMessage(AgentMessageRole.SYSTEM, "较早的对话已压缩，保留了最近的表盘操作和当前请求。需要旧细节时请重新读取文档。")) else emptyList()
        return request.copy(messages = system + summary + history + current)
    }
}
