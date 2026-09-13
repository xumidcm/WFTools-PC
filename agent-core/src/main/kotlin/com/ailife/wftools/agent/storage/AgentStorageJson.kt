package com.ailife.wftools.agent.storage

import com.ailife.wftools.agent.model.*
import com.ailife.wftools.agent.provider.AgentJsonParser
import com.ailife.wftools.agent.provider.AgentJsonValue
import com.ailife.wftools.agent.provider.AgentJsonWriter

/** Explicit storage codecs keep agent-core independent from Android and reflection JSON libraries. */
internal object AgentStorageJson {
    fun providers(values: List<AiProviderProfile>): String = AgentJsonWriter.encode(values.map(::provider))
    fun parseProviders(raw: String): List<AiProviderProfile> = (AgentJsonParser.parse(raw) as? AgentJsonValue.Arr)?.items?.mapNotNull { parseProvider(it) }.orEmpty()
    fun provider(value: AiProviderProfile): Map<String, Any?> = linkedMapOf(
        "id" to value.id, "name" to value.name, "protocol" to value.protocol.name,
        "baseUrl" to value.baseUrl, "modelListUrlOverride" to value.modelListUrlOverride,
        "modelId" to value.modelId, "contextWindowOverride" to value.contextWindowOverride,
        "maxOutputTokens" to value.maxOutputTokens, "customHeaderNames" to value.customHeaderNames,
        "parallelToolCalls" to value.parallelToolCalls.name, "userAgent" to value.userAgent,
        "reasoningEffort" to value.reasoningEffort,
    )

    fun conversations(values: List<AgentConversation>): String = AgentJsonWriter.encode(values.map {
        mapOf("id" to it.id, "watchScope" to it.watchScope, "title" to it.title, "createdAt" to it.createdAt,
            "updatedAt" to it.updatedAt, "endedAt" to it.endedAt, "titleFinalized" to it.titleFinalized)
    })
    fun parseConversations(raw: String): List<AgentConversation> = (AgentJsonParser.parse(raw) as? AgentJsonValue.Arr)?.items?.mapNotNull { value ->
        val o = value as? AgentJsonValue.Obj ?: return@mapNotNull null
        AgentConversation(o.string("id") ?: return@mapNotNull null, o.string("watchScope") ?: return@mapNotNull null,
            o.string("title").orEmpty(), o.long("createdAt") ?: 0L, o.long("updatedAt") ?: 0L,
            o.long("endedAt"), o.bool("titleFinalized") ?: false)
    }.orEmpty()

    fun messages(values: List<AgentMessage>): String = AgentJsonWriter.encode(values.map { message(it) })
    fun parseMessages(raw: String): List<AgentMessage> = (AgentJsonParser.parse(raw) as? AgentJsonValue.Arr)?.items?.mapNotNull(::parseMessage).orEmpty()
    private fun message(value: AgentMessage): Map<String, Any?> = mapOf(
        "id" to value.id, "conversationId" to value.conversationId, "role" to value.role.name,
        "content" to value.content, "createdAt" to value.createdAt, "status" to value.status.name,
        "toolName" to value.toolName, "toolCallId" to value.toolCallId,
        "toolCalls" to value.toolCalls.map { mapOf("id" to it.id, "name" to it.name, "argumentsJson" to it.argumentsJson) },
        "attachments" to value.attachments.map { mapOf("id" to it.id, "displayName" to it.displayName, "mimeType" to it.mimeType, "sizeBytes" to it.sizeBytes, "sha256" to it.sha256, "localPath" to it.localPath) },
        "reasoningContent" to value.reasoningContent, "toolResultJson" to value.toolResultJson, "toolInputJson" to value.toolInputJson,
    )
    private fun parseMessage(value: AgentJsonValue): AgentMessage? {
        val o = value as? AgentJsonValue.Obj ?: return null
        val id = o.string("id") ?: return null; val conversation = o.string("conversationId") ?: return null
        val role = o.string("role")?.let { runCatching { AgentMessageRole.valueOf(it) }.getOrNull() } ?: return null
        val calls = (o.array("toolCalls")?.items.orEmpty()).mapNotNull { item ->
            val call = item as? AgentJsonValue.Obj ?: return@mapNotNull null
            AgentToolCallRecord(call.string("id").orEmpty(), call.string("name").orEmpty(), call.string("argumentsJson").orEmpty())
        }
        val attachments = (o.array("attachments")?.items.orEmpty()).mapNotNull { item ->
            val a = item as? AgentJsonValue.Obj ?: return@mapNotNull null
            AgentAttachment(a.string("id").orEmpty(), a.string("displayName").orEmpty(), a.string("mimeType"), a.long("sizeBytes") ?: 0L, a.string("sha256"), a.string("localPath"))
        }
        return AgentMessage(id, conversation, role, o.string("content").orEmpty(), o.long("createdAt") ?: 0L,
            o.string("status")?.let { runCatching { AgentMessageStatus.valueOf(it) }.getOrNull() } ?: AgentMessageStatus.COMPLETE,
            o.string("toolName"), o.string("toolCallId"), calls, attachments, o.string("reasoningContent"), o.string("toolResultJson"), o.string("toolInputJson"))
    }

    private fun parseProvider(value: AgentJsonValue): AiProviderProfile? {
        val o = value as? AgentJsonValue.Obj ?: return null
        val id = o.string("id") ?: return null
        val protocol = o.string("protocol")?.let { runCatching { AiProtocol.valueOf(it) }.getOrNull() } ?: AiProtocol.CHAT_COMPLETIONS
        val headers = (o.obj("customHeaderNames")?.fields.orEmpty()).mapNotNull { (key, v) -> (v as? AgentJsonValue.Str)?.value?.let { key to it } }.toMap()
        return AiProviderProfile(id, o.string("name").orEmpty(), protocol, o.string("baseUrl").orEmpty(), o.string("modelListUrlOverride"), o.string("modelId"), o.long("contextWindowOverride"), o.long("maxOutputTokens")?.toInt(), headers,
            o.string("parallelToolCalls")?.let { runCatching { ParallelToolCallMode.valueOf(it) }.getOrNull() } ?: ParallelToolCallMode.UNKNOWN,
            o.string("userAgent"), o.string("reasoningEffort"))
    }

    fun headers(values: Map<String, String>): String = AgentJsonWriter.encode(values)
    fun parseHeaders(raw: String): Map<String, String> = ((AgentJsonParser.parse(raw) as? AgentJsonValue.Obj)?.fields.orEmpty()).mapNotNull { (key, value) -> (value as? AgentJsonValue.Str)?.value?.let { key to it } }.toMap()
}
