package com.ailife.wftools.agent.runtime

import kotlinx.coroutines.CancellationException

data class AgentContextBudget(
    val contextWindowTokens: Long,
    val maxOutputTokens: Long,
    val safetyMarginTokens: Long,
    val availableInputTokens: Long,
    val currentInputTokens: Long,
    val usagePercent: Int,
    val requiresCompression: Boolean,
)

object AgentContextBudgetManager {
    const val DEFAULT_SAFETY_MARGIN_TOKENS = 4_096L

    fun calculate(
        contextWindowTokens: Long,
        maxOutputTokens: Int,
        safetyMarginTokens: Long = DEFAULT_SAFETY_MARGIN_TOKENS,
        systemPromptTokens: Long = 0L,
        toolSchemaTokens: Long = 0L,
        messageTokens: Long = 0L,
        attachmentTokens: Long = 0L,
    ): AgentContextBudget {
        val context = contextWindowTokens.coerceAtLeast(0L)
        val output = maxOutputTokens.toLong().coerceAtLeast(0L)
        val margin = safetyMarginTokens.coerceAtLeast(0L)
        val available = (context - output - margin).coerceAtLeast(0L)
        val current = (systemPromptTokens + toolSchemaTokens + messageTokens + attachmentTokens).coerceAtLeast(0L)
        val percent = if (available == 0L) {
            if (current == 0L) 0 else 100
        } else {
            ((current.toDouble() * 100.0) / available.toDouble()).let { kotlin.math.ceil(it).toInt() }
                .coerceIn(0, 100)
        }
        return AgentContextBudget(
            contextWindowTokens = context,
            maxOutputTokens = output,
            safetyMarginTokens = margin,
            availableInputTokens = available,
            currentInputTokens = current,
            usagePercent = percent,
            requiresCompression = current > available,
        )
    }
}

data class AgentContextCompressionItem(
    val id: String,
    val tokenCount: Long,
    val mustKeep: Boolean = false,
)

object AgentContextCompressor {
    const val SUMMARY_ID = "__compressed_history_summary__"

    /** Keeps required items and the newest optional items, preserving original order. */
    fun compact(items: List<AgentContextCompressionItem>, targetTokens: Long): List<AgentContextCompressionItem> {
        if (items.isEmpty()) return emptyList()
        val target = targetTokens.coerceAtLeast(0L)
        val required = items.filter { it.mustKeep }
        var used = required.sumOf { it.tokenCount.coerceAtLeast(0L) }
        val selected = required.toMutableSet()
        val optional = items.asReversed().filterNot { it.mustKeep }
        for (item in optional) {
            val tokens = item.tokenCount.coerceAtLeast(0L)
            if (used + tokens > target) continue
            selected += item
            used += tokens
        }
        val dropped = items.any { it !in selected }
        val ordered = items.filter { it in selected }.toMutableList()
        if (dropped) ordered += AgentContextCompressionItem(SUMMARY_ID, tokenCount = 0L, mustKeep = true)
        return ordered
    }
}

object AgentContextRetryPolicy {
    fun isContextLimitError(error: Throwable): Boolean {
        val messages = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.lowercase() }
            .toList()
        return messages.any { message ->
            CONTEXT_MARKERS.any { marker -> marker in message }
        }
    }

    private val CONTEXT_MARKERS = listOf(
        "context_length_exceeded",
        "context length exceeded",
        "maximum context",
        "context window",
        "too many tokens",
        "token limit",
        "上下文长度",
        "上下文超限",
    )
}

object AgentContextRetryExecutor {
    suspend fun <Req, Res> execute(
        initial: Req,
        send: suspend (Req) -> Res,
        compress: suspend (Req) -> Req,
    ): Res {
        return try {
            send(initial)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!AgentContextRetryPolicy.isContextLimitError(error)) throw error
            val compressed = compress(initial)
            try {
                send(compressed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryError: Throwable) {
                // The second attempt is deliberately not wrapped or retried again.
                throw retryError
            }
        }
    }

    suspend fun <Req, Res> retryIfContextOverflow(
        initial: Req,
        send: suspend (Req) -> Res,
        compress: suspend (Req) -> Req,
    ): Res = execute(initial, send, compress)
}


