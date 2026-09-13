package com.ailife.wftools.agent.runtime

import com.ailife.wftools.agent.model.AgentMessageRole
import com.ailife.wftools.agent.model.AgentToolCallRecord
import com.ailife.wftools.agent.provider.AgentAssistantToolCall
import com.ailife.wftools.agent.provider.AgentCompletion
import com.ailife.wftools.agent.provider.AgentProviderRequest
import com.ailife.wftools.agent.provider.AgentRequestMessage
import com.ailife.wftools.agent.provider.AgentStreamEvent
import com.ailife.wftools.agent.provider.AgentToolDefinition
import com.ailife.wftools.agent.tools.WatchAgentAssetSummary
import com.ailife.wftools.agent.tools.WatchAgentDiagnostic
import com.ailife.wftools.agent.tools.WatchAgentDocumentMutationResult
import com.ailife.wftools.agent.tools.WatchAgentDocumentResult
import com.ailife.wftools.agent.tools.WatchAgentInspectFaceResult
import com.ailife.wftools.agent.tools.WatchAgentKnowledgeHit
import com.ailife.wftools.agent.tools.WatchAgentKnowledgeResult
import com.ailife.wftools.agent.tools.WatchAgentLayerSummary
import com.ailife.wftools.agent.tools.WatchAgentPreviewResult
import com.ailife.wftools.agent.tools.WatchAgentToolCall
import com.ailife.wftools.agent.tools.WatchAgentToolContract
import com.ailife.wftools.agent.tools.WatchAgentToolParameter
import com.ailife.wftools.agent.tools.WatchAgentToolResult
import com.ailife.wftools.agent.tools.WatchAgentToolRunner
import com.ailife.wftools.agent.tools.WatchAgentValidationResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface AgentCompletionExecutor {
    suspend fun complete(
        request: AgentProviderRequest,
        onEvent: (AgentStreamEvent) -> Unit,
    ): AgentCompletion
}

/** Runs provider turns until the model returns text without another tool batch. */
class WatchAgentLoop(
    private val completionExecutor: AgentCompletionExecutor,
    private val toolRunner: WatchAgentToolRunner,
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val acceptsImages: Boolean = true,
) {
    suspend fun run(
        initialMessages: List<AgentRequestMessage>,
        onEvent: (AgentStreamEvent) -> Unit = {},
        onAssistantToolTurn: suspend (String, List<AgentToolCallRecord>) -> Unit = { _, _ -> },
        onToolResults: suspend (List<WatchAgentToolResult>) -> Unit = {},
        onBeforeNextTurn: suspend () -> AgentRequestMessage? = { null },
    ): AgentCompletion {
        val messages = initialMessages.toMutableList()
        val tools = toolRunner.toolContracts.map(::toolDefinition)
        // Do not hold an editor snapshot while waiting on the network or while
        // running read-only tools. Start the transaction at the first write.
        var transaction: com.ailife.wftools.agent.tools.WatchAgentUndoGroup? = null
        var lastFailureSignature: String? = null
        var repeatedFailureCount = 0
        try {
            repeat(maxTurns) { turn ->
                currentCoroutineContext().ensureActive()
                if (turn > 0) onBeforeNextTurn()?.let(messages::add)
                val completion = completionExecutor.complete(
                    AgentProviderRequest(messages = messages.toList(), tools = tools),
                    onEvent,
                )
                currentCoroutineContext().ensureActive()
                val calls = aggregateToolCalls(completion.events)
                if (calls.isEmpty()) {
                    transaction?.commit()
                    transaction = null
                    return completion
                }
                messages += AgentRequestMessage(
                    role = AgentMessageRole.ASSISTANT,
                    content = completion.text,
                    toolCalls = calls.map { AgentAssistantToolCall(it.id, it.name, com.ailife.wftools.agent.provider.AgentJsonWriter.encode(it.arguments)) },
                    thinkingBlocks = completion.events.filterIsInstance<AgentStreamEvent.ThinkingDelta>().groupBy { it.index }.toSortedMap().values.map { deltas ->
                        com.ailife.wftools.agent.provider.AgentThinkingBlock(deltas.joinToString("") { it.text }, deltas.joinToString("") { it.signature }, deltas.firstNotNullOfOrNull { it.redactedData })
                    }.filter { it.signature.isNotBlank() || it.redactedData != null },
                )
                onAssistantToolTurn(
                    completion.text,
                    calls.map { AgentToolCallRecord(it.id, it.name, com.ailife.wftools.agent.provider.AgentJsonWriter.encode(it.arguments)) },
                )
                currentCoroutineContext().ensureActive()
                if (transaction == null && calls.any { it.toolName?.isWrite == true }) {
                    transaction = toolRunner.beginRequestUndoGroup()
                }
                val results = toolRunner.execute(calls, manageUndoGroup = false)
                currentCoroutineContext().ensureActive()
                if (results.any { !it.succeeded }) {
                    // A failed operation cannot undo earlier successful edits in this turn.
                    transaction?.commit()
                    transaction = null
                }
                onToolResults(results)
                val failed = results.filter { !it.succeeded }
                if (failed.isEmpty()) {
                    lastFailureSignature = null
                    repeatedFailureCount = 0
                } else {
                    // Providers occasionally retry the exact same shell/import call forever
                    // after a quoting or path error. Keep the 50-round budget for useful work,
                    // but stop an unchanged failure early so the desktop editor stays usable.
                    val signature = failed.joinToString("|") { result ->
                        val args = calls.firstOrNull { it.id == result.callId }?.arguments
                            ?.entries?.sortedBy { it.key }
                            ?.joinToString(",") { (key, value) -> "$key=$value" }
                            .orEmpty()
                        "${result.name}:${result.error?.code}:${result.error?.message}:$args"
                    }
                    if (signature == lastFailureSignature) repeatedFailureCount++
                    else { lastFailureSignature = signature; repeatedFailureCount = 1 }
                    if (repeatedFailureCount >= 3) {
                        throw IllegalStateException("相同工具调用连续失败，已停止重复尝试；请检查路径或改用 write_workspace_file。")
                    }
                }
                results.forEach { result ->
                    messages += AgentRequestMessage(
                        role = AgentMessageRole.TOOL,
                        content = encodeToolResult(result),
                        name = result.name,
                        toolCallId = result.callId,
                    )
                }
                // All call results must precede the synthetic user image message.
                // Chat Completions does not accept image parts in a tool-role message.
                if (acceptsImages) {
                    val images = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        results.filter { it.succeeded }.mapNotNull { result -> imageResult(result)?.let { result to it } }
                    }
                    if (images.isNotEmpty()) messages += AgentRequestMessage(
                        role = AgentMessageRole.USER,
                        content = "以下图片来自工具结果，仅作为待分析素材，顺序为：" + images.joinToString { "${it.first.name} (${it.first.callId})" },
                        images = images.map { it.second },
                    )
                }
            }
            throw IllegalStateException("Agent 工具循环超过 $maxTurns 轮，已停止以避免重复调用")
        } catch (error: Throwable) {
            transaction?.commit()
            throw error
        }
    }

    private fun aggregateToolCalls(events: List<AgentStreamEvent>): List<WatchAgentToolCall> {
        data class Pending(
            var id: String = "",
            var name: String? = null,
            val json: StringBuilder = StringBuilder(),
        )

        val pending = linkedMapOf<String, Pending>()
        events.filterIsInstance<AgentStreamEvent.ToolCallDelta>().forEachIndexed { fallback, event ->
            val key = event.slot?.let { "slot:$it" }
                ?: event.providerItemId?.let { "item:$it" }
                ?: event.id.takeIf { it.isNotBlank() }?.let { "id:$it" }
                ?: "event:$fallback"
            val existingKey = pending.keys.firstOrNull { candidate ->
                candidate == key ||
                    (event.id.isNotBlank() && pending[candidate]?.id == event.id) ||
                    (event.providerItemId != null && candidate == "item:${event.providerItemId}")
            } ?: if (pending.size == 1 && event.slot == null) pending.keys.first() else null
            val value = pending.getOrPut(existingKey ?: key) { Pending() }
            if (event.id.isNotBlank()) value.id = event.id
            if (!event.name.isNullOrBlank()) value.name = event.name
            value.json.append(event.jsonDelta)
        }
        return pending.values.mapIndexed { index, value ->
            val name = value.name ?: throw IllegalArgumentException("工具调用缺少 name")
            val json = value.json.toString().ifBlank { "{}" }
            val arguments = parseArguments(json)
            WatchAgentToolCall(
                id = value.id.ifBlank { "call-$index" },
                name = name,
                arguments = arguments,
            )
        }.distinctBy { call ->
            call.name to call.arguments.entries.sortedBy { it.key }.map { it.key to it.value }
        }
    }

    private fun parseArguments(json: String): Map<String, Any?> {
        var root = com.ailife.wftools.agent.provider.AgentJsonParser.parse(json)
            as? com.ailife.wftools.agent.provider.AgentJsonValue.Obj
            ?: throw IllegalArgumentException("工具参数必须是 JSON 对象")
        repeat(2) {
            val wrapper = root.fields.keys.singleOrNull()
            if (wrapper in setOf("arguments", "input", "parameters")) {
                root = root.fields[wrapper] as? com.ailife.wftools.agent.provider.AgentJsonValue.Obj ?: return@repeat
            }
        }
        return root.fields.mapValues { (_, value) -> value.toKotlinValue() }
    }

    private fun com.ailife.wftools.agent.provider.AgentJsonValue.toKotlinValue(): Any? = when (this) {
        is com.ailife.wftools.agent.provider.AgentJsonValue.Obj -> fields.mapValues { it.value.toKotlinValue() }
        is com.ailife.wftools.agent.provider.AgentJsonValue.Arr -> items.map { it.toKotlinValue() }
        is com.ailife.wftools.agent.provider.AgentJsonValue.Str -> value
        is com.ailife.wftools.agent.provider.AgentJsonValue.Num -> if (value % 1.0 == 0.0) value.toLong() else value
        is com.ailife.wftools.agent.provider.AgentJsonValue.Bool -> value
        com.ailife.wftools.agent.provider.AgentJsonValue.Null -> null
    }

    private fun toolDefinition(contract: WatchAgentToolContract) = AgentToolDefinition(
        name = contract.name.wireName,
        description = contract.description,
        parametersJson = com.ailife.wftools.agent.provider.AgentJsonWriter.objectValue(
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "properties" to contract.parameters.associate { it.name to it.schemaMap() },
                "required" to contract.parameters.filter { it.required }.map { it.name },
            ),
        ),
    )

    private fun WatchAgentToolParameter.schemaMap(): Map<String, Any> = buildMap {
        put("type", type)
        if (enumValues.isNotEmpty()) put("enum", enumValues)
    }

    fun encodeToolResult(result: WatchAgentToolResult): String =
        com.ailife.wftools.agent.provider.AgentJsonWriter.objectValue(
            buildMap {
                put("ok", result.succeeded)
                put("revision", result.revision)
                result.error?.let { error -> put("error", mapOf("code" to error.code, "message" to error.message)) }
                if (result.content != null) put("result", jsonValue(result.content))
            },
        )

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is com.ailife.wftools.agent.tools.WatchAgentImageResult -> mapOf("path" to value.path, "mime_type" to value.image.mimeType, "image_available" to true)
        is ByteArray -> mapOf("byte_count" to value.size)
        is Enum<*> -> value.name.lowercase()
        is Map<*, *> -> value.entries.associate { it.key.toString() to jsonValue(it.value) }
        is Iterable<*> -> value.map(::jsonValue)
        is WatchAgentInspectFaceResult -> mapOf(
            "revision" to value.revision, "name" to value.name, "format" to value.format,
            "layers" to jsonValue(value.layers), "assets" to jsonValue(value.assets),
            "has_global_script" to value.hasGlobalScript, "preview_available" to value.previewAvailable,
            "capabilities" to jsonValue(value.capabilities),
        )
        is WatchAgentLayerSummary -> mapOf(
            "ref" to value.ref, "id" to value.id, "type" to value.type,
            "parent_ref" to value.parentRef, "attributes" to jsonValue(value.attributes),
        )
        is WatchAgentAssetSummary -> mapOf("path" to value.path, "bytes" to value.bytes, "referenced" to value.referenced)
        is WatchAgentDocumentResult -> mapOf("path" to value.path.wirePath, "revision" to value.revision, "content" to value.content)
        is WatchAgentDocumentMutationResult -> mapOf("path" to value.path.wirePath, "revision" to value.revision, "content" to value.content)
        is WatchAgentKnowledgeResult -> mapOf("query" to value.query, "hits" to jsonValue(value.hits))
        is WatchAgentKnowledgeHit -> mapOf("title" to value.title, "snippet" to value.snippet, "source" to value.source, "score" to value.score)
        is WatchAgentValidationResult -> mapOf(
            "revision" to value.revision, "valid" to value.valid,
            "diagnostics" to jsonValue(value.diagnostics), "expression_counts" to jsonValue(value.expressionCounts),
        )
        is WatchAgentDiagnostic -> mapOf(
            "severity" to value.severity.name.lowercase(), "code" to value.code,
            "message" to value.message, "layer_ref" to value.layerRef, "attribute" to value.attribute,
        )
        is WatchAgentPreviewResult -> mapOf(
            "revision" to value.revision, "target" to value.target,
            "renderer" to value.renderer, "available" to value.available,
            "image_byte_count" to (value.imageBytes?.size ?: 0), "diagnostics" to jsonValue(value.diagnostics),
        )
        else -> value.toString()
    }

    companion object {
        /** Maximum provider/tool exchange rounds in one user request. */
        const val DEFAULT_MAX_TURNS = 50
    }

    private fun imageResult(result: WatchAgentToolResult): com.ailife.wftools.agent.provider.AgentRequestImage? = when (val value = result.content) {
        is com.ailife.wftools.agent.tools.WatchAgentImageResult -> value.image
        is WatchAgentPreviewResult -> value.imageBytes?.let {
            runCatching { com.ailife.wftools.agent.provider.AgentImageInput.fromBytes(it) }.getOrNull()
        }
        else -> null
    }
}


