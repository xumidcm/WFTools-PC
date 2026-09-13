package com.ailife.wftools.agent.provider

import com.ailife.wftools.agent.model.AiProtocol

sealed interface AgentStreamEvent {
    data class TextDelta(val text: String) : AgentStreamEvent
    data class ReasoningDelta(val text: String) : AgentStreamEvent
    data class ThinkingDelta(val index: Int, val text: String = "", val signature: String = "", val redactedData: String? = null) : AgentStreamEvent
    data class ToolCallDelta(val id: String, val name: String? = null, val jsonDelta: String = "", val slot: Int? = null, val providerItemId: String? = null) : AgentStreamEvent
    data class Usage(val input: Long? = null, val output: Long? = null) : AgentStreamEvent
    data class Completed(val nativePayload: String? = null) : AgentStreamEvent
}

data class AgentParsedResponse(val text: String, val events: List<AgentStreamEvent>, val nativePayload: String?, val inputTokens: Long? = null, val outputTokens: Long? = null)

object AgentStreamParser {
    fun events(protocol: AiProtocol, payload: String): List<AgentStreamEvent> {
        val jsonText = payload.removePrefix("data:").trim()
        if (jsonText.isEmpty()) return emptyList()
        if (jsonText == "[DONE]") return listOf(AgentStreamEvent.Completed())
        val root = AgentJsonParser.parse(jsonText) as? AgentJsonValue.Obj ?: return emptyList()
        val result = mutableListOf<AgentStreamEvent>()
        when (protocol) {
            AiProtocol.CHAT_COMPLETIONS -> root.array("choices")?.items.orEmpty().forEachIndexed { index, item ->
                val choice = item as? AgentJsonValue.Obj ?: return@forEachIndexed
                val delta = choice.obj("delta")
                delta?.string("content")?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.TextDelta(it) }
                (delta?.string("reasoning_content") ?: delta?.string("reasoning"))?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.ReasoningDelta(it) }
                delta?.array("tool_calls")?.items.orEmpty().forEachIndexed { toolIndex, raw ->
                    val tool = raw as? AgentJsonValue.Obj ?: return@forEachIndexed
                    val fn = tool.obj("function")
                    result += AgentStreamEvent.ToolCallDelta(tool.string("id").orEmpty(), fn?.string("name"), fn?.string("arguments").orEmpty(), tool.long("index")?.toInt() ?: toolIndex)
                }
                if (!choice.string("finish_reason").isNullOrBlank()) result += AgentStreamEvent.Completed(jsonText)
            }
            AiProtocol.RESPONSES -> when (root.string("type")) {
                "response.output_text.delta" -> root.string("delta")?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.TextDelta(it) }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> root.string("delta")?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.ReasoningDelta(it) }
                "response.function_call_arguments.delta" -> result += AgentStreamEvent.ToolCallDelta(root.string("call_id").orEmpty(), jsonDelta = root.string("delta").orEmpty(), slot = root.long("output_index")?.toInt(), providerItemId = root.string("item_id"))
                "response.output_item.added" -> root.obj("item")?.takeIf { it.string("type") == "function_call" }?.let { item -> result += AgentStreamEvent.ToolCallDelta(item.string("call_id") ?: item.string("id").orEmpty(), item.string("name"), slot = root.long("output_index")?.toInt(), providerItemId = item.string("id")) }
                "response.completed", "response.done" -> result += AgentStreamEvent.Completed(jsonText)
                else -> root.array("output")?.items.orEmpty().forEachIndexed { index, raw ->
                    val item = raw as? AgentJsonValue.Obj ?: return@forEachIndexed
                    if (item.string("type") == "function_call") result += AgentStreamEvent.ToolCallDelta(item.string("call_id") ?: item.string("id").orEmpty(), item.string("name"), item.string("arguments").orEmpty(), index)
                    item.array("content")?.items.orEmpty().forEach { part -> (part as? AgentJsonValue.Obj)?.string("text")?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.TextDelta(it) } }
                }
            }
            AiProtocol.CLAUDE_MESSAGES -> when (root.string("type")) {
                "content_block_start" -> root.obj("content_block")?.let { block ->
                    when (block.string("type")) {
                        "thinking" -> result += AgentStreamEvent.ThinkingDelta(root.long("index")?.toInt() ?: 0, block.string("thinking").orEmpty(), block.string("signature").orEmpty())
                        "redacted_thinking" -> result += AgentStreamEvent.ThinkingDelta(root.long("index")?.toInt() ?: 0, redactedData = block.string("data"))
                        "tool_use" -> result += AgentStreamEvent.ToolCallDelta(block.string("id").orEmpty(), block.string("name"), slot = root.long("index")?.toInt())
                    }
                }
                "content_block_delta" -> root.obj("delta")?.let { delta ->
                    when (delta.string("type")) {
                        "text_delta" -> delta.string("text")?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.TextDelta(it) }
                        "thinking_delta" -> delta.string("thinking")?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.ReasoningDelta(it) }
                        "signature_delta" -> result += AgentStreamEvent.ThinkingDelta(root.long("index")?.toInt() ?: 0, signature = delta.string("signature").orEmpty())
                        "input_json_delta" -> result += AgentStreamEvent.ToolCallDelta("", jsonDelta = delta.string("partial_json").orEmpty(), slot = root.long("index")?.toInt())
                    }
                }
                "message_stop" -> result += AgentStreamEvent.Completed(jsonText)
                else -> root.array("content")?.items.orEmpty().forEachIndexed { index, raw ->
                    val part = raw as? AgentJsonValue.Obj ?: return@forEachIndexed
                    when (part.string("type")) {
                        "text" -> part.string("text")?.takeIf(String::isNotEmpty)?.let { result += AgentStreamEvent.TextDelta(it) }
                        "tool_use" -> result += AgentStreamEvent.ToolCallDelta(part.string("id").orEmpty(), part.string("name"), part.obj("input")?.let(AgentJsonWriter::encode).orEmpty(), index)
                    }
                }
            }
        }
        root.obj("usage")?.let { usage ->
            val input = usage.long("input_tokens") ?: usage.long("prompt_tokens")
            val output = usage.long("output_tokens") ?: usage.long("completion_tokens")
            if (input != null || output != null) result += AgentStreamEvent.Usage(input, output)
        }
        return result
    }

    fun parseSse(protocol: AiProtocol, payload: String): List<AgentStreamEvent> = SseDecoder(protocol).also { decoder -> payload.lineSequence().forEach(decoder::acceptLine) }.finish()

    fun parseResponse(protocol: AiProtocol, payload: String): AgentParsedResponse {
        require(AgentJsonParser.parse(payload) != null) { "Provider 返回了无效 JSON" }
        val events = events(protocol, payload)
        val text = events.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("").ifBlank { nonStreamingText(protocol, payload) }
        val usage = events.filterIsInstance<AgentStreamEvent.Usage>().lastOrNull()
        return AgentParsedResponse(text, events + AgentStreamEvent.Completed(payload), payload, usage?.input, usage?.output)
    }

    private fun nonStreamingText(protocol: AiProtocol, payload: String): String {
        val root = AgentJsonParser.parse(payload) as? AgentJsonValue.Obj ?: return ""
        return when (protocol) {
            AiProtocol.CHAT_COMPLETIONS -> (root.array("choices")?.items?.firstOrNull() as? AgentJsonValue.Obj)?.obj("message")?.string("content").orEmpty()
            AiProtocol.RESPONSES -> root.array("output")?.items.orEmpty().joinToString("") { (it as? AgentJsonValue.Obj)?.array("content")?.items.orEmpty().joinToString("") { p -> (p as? AgentJsonValue.Obj)?.string("text").orEmpty() } }
            AiProtocol.CLAUDE_MESSAGES -> root.array("content")?.items.orEmpty().joinToString("") { (it as? AgentJsonValue.Obj)?.string("text").orEmpty() }
        }
    }

    class SseDecoder(private val protocol: AiProtocol) {
        private val data = StringBuilder(); private val output = mutableListOf<AgentStreamEvent>()
        fun acceptLine(line: String) { val normalized = line.removeSuffix("\r"); when { normalized.isBlank() -> flush(); normalized.startsWith("data:") -> data.append(normalized.removePrefix("data:").trim()).append('\n') } }
        fun snapshot(): List<AgentStreamEvent> = output.toList()
        fun drain(): List<AgentStreamEvent> {
            val start = drained
            drained = output.size
            return output.drop(start)
        }
        fun finish(): List<AgentStreamEvent> { flush(); return output.toList() }
        private var drained = 0
        private fun flush() { if (data.isEmpty()) return; output += events(protocol, data.toString().trimEnd('\n')); data.setLength(0) }
    }
}

class ChatCompletionsStreamParser {
    private val decoder = AgentStreamParser.SseDecoder(AiProtocol.CHAT_COMPLETIONS)
    private val pending = StringBuilder()
    fun feed(chunk: String): List<AgentStreamEvent> {
        pending.append(chunk)
        while (true) {
            val end = pending.indexOf("\n")
            if (end < 0) break
            decoder.acceptLine(pending.substring(0, end))
            pending.delete(0, end + 1)
        }
        return decoder.drain()
    }
    fun finish(): List<AgentStreamEvent> {
        if (pending.isNotEmpty()) { decoder.acceptLine(pending.toString()); pending.setLength(0) }
        decoder.finish()
        return decoder.drain()
    }
}
