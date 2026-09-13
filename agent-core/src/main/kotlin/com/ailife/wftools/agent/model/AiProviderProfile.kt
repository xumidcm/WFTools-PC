package com.ailife.wftools.agent.model

enum class AiProtocol { CHAT_COMPLETIONS, RESPONSES, CLAUDE_MESSAGES }

enum class ParallelToolCallMode { ENABLED, DISABLED, UNKNOWN }

enum class ModelMetadataSource { MANUAL, PROVIDER, PUBLIC_CATALOG, BUILTIN, DEFAULT }

data class AiProviderProfile(
    val id: String,
    val name: String,
    val protocol: AiProtocol,
    val baseUrl: String,
    val modelListUrlOverride: String? = null,
    val modelId: String? = null,
    val contextWindowOverride: Long? = null,
    val maxOutputTokens: Int? = null,
    val customHeaderNames: Map<String, String> = emptyMap(),
    val parallelToolCalls: ParallelToolCallMode = ParallelToolCallMode.UNKNOWN,
    /** Optional request User-Agent. Kept in provider metadata, never in the secret store. */
    val userAgent: String? = null,
    val reasoningEffort: String? = null,
)

data class AiModelDescriptor(
    val id: String,
    val displayName: String = id,
    val contextWindowTokens: Long? = null,
    val maxOutputTokens: Int? = null,
    val supportsVision: Boolean? = null,
    val supportsTools: Boolean? = null,
    val metadataSource: ModelMetadataSource = ModelMetadataSource.DEFAULT,
)


