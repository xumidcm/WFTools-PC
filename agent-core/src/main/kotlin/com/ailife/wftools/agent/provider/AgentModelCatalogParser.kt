package com.ailife.wftools.agent.provider

import com.ailife.wftools.agent.model.AiModelDescriptor
import com.ailife.wftools.agent.model.ModelMetadataSource

/** Parses common provider catalogs without depending on Android's JSON stubs. */
object AgentModelCatalogParser {
    fun parse(payload: String, source: ModelMetadataSource = ModelMetadataSource.PROVIDER): List<AiModelDescriptor> {
        val root = AgentJsonParser.parse(payload) as? AgentJsonValue.Obj ?: return emptyList()
        val result = mutableListOf<AiModelDescriptor>()
        val data = root.array("data") ?: root.array("models")
        data?.items?.forEach { (it as? AgentJsonValue.Obj)?.let { item -> parseItem(item, source)?.let(result::add) } }
        root.fields.forEach { (providerKey, value) ->
            val group = value as? AgentJsonValue.Obj ?: return@forEach
            val grouped = group.array("models") ?: group.array("data")
            grouped?.items?.forEach { (it as? AgentJsonValue.Obj)?.let { item -> parseItem(item, source, providerKey)?.let(result::add) } }
            if (grouped == null) group.fields.forEach { (modelKey, modelValue) ->
                val item = modelValue as? AgentJsonValue.Obj ?: return@forEach
                parseItem(item, source, "$providerKey/$modelKey")?.let(result::add)
            }
        }
        return result.distinctBy { it.id }
    }

    fun parseModels(payload: String, source: ModelMetadataSource = ModelMetadataSource.PROVIDER): List<AiModelDescriptor> = parse(payload, source)

    private fun parseItem(item: AgentJsonValue.Obj, source: ModelMetadataSource, fallbackId: String? = null): AiModelDescriptor? {
        val id = item.string("id")?.ifBlank { null }
            ?: item.string("name")?.ifBlank { null }
            ?: fallbackId?.substringAfter('/')?.ifBlank { null }
            ?: return null
        val limits = item.obj("limit")
        val capabilities = item.obj("capabilities")
        val modalities = item.array("input_modalities") ?: item.array("modalities")
        return AiModelDescriptor(
            id = id.trim(),
            displayName = item.string("display_name") ?: item.string("displayName") ?: id,
            contextWindowTokens = firstLong(item, limits, "max_input_tokens", "context_length", "context_window", "max_context_length") ?: firstLong(item, limits, "context"),
            maxOutputTokens = firstLong(item, limits, "max_output_tokens", "output")?.toInt(),
            supportsVision = firstBool(item, capabilities, "vision", "supports_vision", "supportsVision") ?: modalities?.items?.any { (it as? AgentJsonValue.Str)?.value?.equals("image", true) == true },
            supportsTools = firstBool(item, capabilities, "tools", "supports_tools", "supportsTools", "tool_call") ?: item.obj("capabilities")?.bool("tool_call"),
            metadataSource = source,
        )
    }

    private fun firstLong(item: AgentJsonValue.Obj, limits: AgentJsonValue.Obj?, vararg keys: String): Long? = keys.firstNotNullOfOrNull { key -> item.long(key) ?: limits?.long(key) }

    private fun firstBool(item: AgentJsonValue.Obj, capabilities: AgentJsonValue.Obj?, vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key -> item.bool(key) ?: capabilities?.bool(key) }
}


