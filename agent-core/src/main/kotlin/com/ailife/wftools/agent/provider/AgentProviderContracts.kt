package com.ailife.wftools.agent.provider

import com.ailife.wftools.agent.model.AgentMessageRole
import com.ailife.wftools.agent.model.AiProtocol
import com.ailife.wftools.agent.model.AiProviderProfile

/** A request message in the provider-neutral representation. */
data class AgentRequestMessage(
    val role: AgentMessageRole,
    val content: String,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AgentAssistantToolCall> = emptyList(),
    val images: List<AgentRequestImage> = emptyList(),
    val thinkingBlocks: List<AgentThinkingBlock> = emptyList(),
)

data class AgentThinkingBlock(val thinking: String = "", val signature: String = "", val redactedData: String? = null)

data class AgentRequestImage(val mimeType: String, val base64: String) {
    val dataUrl: String get() = "data:$mimeType;base64,$base64"
}

data class AgentAssistantToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AgentToolDefinition(
    val name: String,
    val description: String? = null,
    /** JSON object containing the tool parameters schema. */
    val parametersJson: String = "{\"type\":\"object\"}",
)

data class AgentProviderRequest(
    val messages: List<AgentRequestMessage>,
    val stream: Boolean = true,
    val tools: List<AgentToolDefinition> = emptyList(),
    val maxOutputTokens: Int? = null,
)

/**
 * Serializes the small common request model into each provider's wire format.
 * Secrets are deliberately not part of this object.
 */
object AgentRequestBuilder {
    fun build(
        profile: AiProviderProfile,
        messages: List<AgentRequestMessage>,
        stream: Boolean = true,
        tools: List<AgentToolDefinition> = emptyList(),
        maxOutputTokens: Int? = null,
    ): String = build(
        profile,
        AgentProviderRequest(messages, stream, tools, maxOutputTokens),
    )

    fun build(profile: AiProviderProfile, request: AgentProviderRequest): String = when (profile.protocol) {
        AiProtocol.CHAT_COMPLETIONS -> chatCompletions(profile, request).toString()
        AiProtocol.RESPONSES -> responses(profile, request).toString()
        AiProtocol.CLAUDE_MESSAGES -> claudeMessages(profile, request).toString()
    }

    private fun chatCompletions(profile: AiProviderProfile, request: AgentProviderRequest): String = AgentJsonWriter.objectValue(
        buildMap {
            put("model", profile.modelId.orEmpty()); put("stream", request.stream)
            (request.maxOutputTokens ?: profile.maxOutputTokens)?.let { put("max_tokens", it) }
            profile.reasoningEffort?.let { put("reasoning_effort", it) }
            put("messages", request.messages.map(::chatMessage))
            if (request.tools.isNotEmpty()) put("tools", request.tools.map { tool ->
                mapOf("type" to "function", "function" to buildMap {
                    put("name", tool.name); tool.description?.let { put("description", it) }
                    put("parameters", parseSchema(tool.parametersJson))
                })
            })
        },
    )

    private fun responses(profile: AiProviderProfile, request: AgentProviderRequest): String = AgentJsonWriter.objectValue(buildMap {
        put("model", profile.modelId.orEmpty()); put("stream", request.stream)
        put("max_output_tokens", request.maxOutputTokens ?: profile.maxOutputTokens ?: 8_192)
        profile.reasoningEffort?.let { put("reasoning", mapOf("effort" to it)) }
        request.messages.firstOrNull { it.role == AgentMessageRole.SYSTEM }?.let { put("instructions", it.content) }
        put("input", request.messages.filterNot { it.role == AgentMessageRole.SYSTEM }.flatMap { message ->
                if (message.role == AgentMessageRole.TOOL) {
                    listOf(mapOf("type" to "function_call_output", "call_id" to message.toolCallId.orEmpty(), "output" to message.content))
                } else {
                    buildList {
                        if (message.content.isNotEmpty() || message.images.isNotEmpty()) add(mapOf("role" to message.role.responsesRole(), "content" to buildList {
                            if (message.content.isNotEmpty()) add(mapOf("type" to if (message.role == AgentMessageRole.ASSISTANT) "output_text" else "input_text", "text" to message.content))
                            message.images.forEach { add(mapOf("type" to "input_image", "image_url" to it.dataUrl)) }
                        }))
                        message.toolCalls.forEach { call -> add(mapOf("type" to "function_call", "call_id" to call.id, "name" to call.name, "arguments" to call.argumentsJson)) }
                    }
                }
        })
        if (request.tools.isNotEmpty()) put("tools", request.tools.map { tool -> buildMap {
            put("type", "function"); put("name", tool.name); tool.description?.let { put("description", it) }
            put("parameters", parseSchema(tool.parametersJson))
        } })
    })

    private fun claudeMessages(profile: AiProviderProfile, request: AgentProviderRequest): String = AgentJsonWriter.objectValue(buildMap {
        put("model", profile.modelId.orEmpty()); put("stream", request.stream)
        put("max_tokens", request.maxOutputTokens ?: profile.maxOutputTokens ?: 8_192)
        profile.reasoningEffort?.let { effort ->
            if (effort == "none") put("thinking", mapOf("type" to "disabled"))
            else {
                val modern = Regex("(?:opus|sonnet)[-.]4[-.](?:[6-9]|[1-9][0-9])").containsMatchIn(profile.modelId.orEmpty())
                if (modern) {
                    put("thinking", mapOf("type" to "adaptive"))
                    put("output_config", mapOf("effort" to effort))
                } else {
                    val outputLimit = request.maxOutputTokens ?: profile.maxOutputTokens ?: 8_192
                    require(outputLimit > 1024) { "Claude 扩展思考需要输出上限大于 1024" }
                    val budget = when (effort) { "low" -> 1024; "medium" -> 4096; else -> 8192 }.coerceAtMost(outputLimit - 1)
                    put("thinking", mapOf("type" to "enabled", "budget_tokens" to budget))
                }
            }
        }
        request.messages.firstOrNull { it.role == AgentMessageRole.SYSTEM }?.let { put("system", it.content) }
        put("messages", request.messages.filterNot { it.role == AgentMessageRole.SYSTEM }.map { message ->
            mapOf("role" to if (message.role == AgentMessageRole.TOOL) "user" else message.role.claudeRole(), "content" to buildList {
                        if (message.role == AgentMessageRole.TOOL) {
                            add(mapOf("type" to "tool_result", "tool_use_id" to message.toolCallId.orEmpty(), "content" to message.content))
                        } else {
                            message.thinkingBlocks.forEach { block ->
                                if (block.redactedData != null) add(mapOf("type" to "redacted_thinking", "data" to block.redactedData))
                                else add(mapOf("type" to "thinking", "thinking" to block.thinking, "signature" to block.signature))
                            }
                            if (message.content.isNotEmpty()) add(mapOf("type" to "text", "text" to message.content))
                            message.images.forEach { image -> add(mapOf("type" to "image", "source" to mapOf("type" to "base64", "media_type" to image.mimeType, "data" to image.base64))) }
                            message.toolCalls.forEach { call -> add(mapOf("type" to "tool_use", "id" to call.id, "name" to call.name, "input" to parseSchema(call.argumentsJson))) }
                        }
                    })
        })
        if (request.tools.isNotEmpty()) put("tools", request.tools.map { tool -> buildMap {
            put("name", tool.name); tool.description?.let { put("description", it) }; put("input_schema", parseSchema(tool.parametersJson))
        } })
    })

    private fun parseSchema(value: String): Any = AgentJsonParser.parse(value) ?: emptyMap<String, Any>()

    private fun chatMessage(message: AgentRequestMessage): Map<String, Any?> = buildMap {
        put("role", message.role.wireRole())
        put("content", if (message.images.isEmpty()) message.content else buildList {
            if (message.content.isNotEmpty()) add(mapOf("type" to "text", "text" to message.content))
            message.images.forEach { add(mapOf("type" to "image_url", "image_url" to mapOf("url" to it.dataUrl))) }
        })
        message.name?.let { put("name", it) }; message.toolCallId?.let { put("tool_call_id", it) }
        if (message.toolCalls.isNotEmpty()) put("tool_calls", message.toolCalls.map { call -> mapOf("id" to call.id, "type" to "function", "function" to mapOf("name" to call.name, "arguments" to call.argumentsJson)) })
    }

    private fun AgentMessageRole.wireRole(): String = when (this) {
        AgentMessageRole.USER -> "user"
        AgentMessageRole.ASSISTANT -> "assistant"
        AgentMessageRole.SYSTEM -> "system"
        AgentMessageRole.TOOL -> "tool"
    }

    private fun AgentMessageRole.responsesRole(): String = when (this) {
        AgentMessageRole.USER, AgentMessageRole.TOOL -> "user"
        AgentMessageRole.ASSISTANT -> "assistant"
        AgentMessageRole.SYSTEM -> "system"
    }

    private fun AgentMessageRole.claudeRole(): String = when (this) {
        AgentMessageRole.ASSISTANT -> "assistant"
        else -> "user"
    }
}


