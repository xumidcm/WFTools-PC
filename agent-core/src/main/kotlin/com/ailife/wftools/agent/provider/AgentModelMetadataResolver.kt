package com.ailife.wftools.agent.provider

import com.ailife.wftools.agent.model.AiModelDescriptor
import com.ailife.wftools.agent.model.AiProviderProfile
import com.ailife.wftools.agent.model.ModelMetadataSource

/** Merges provider, public catalog and built-in metadata without guessing model identity. */
object AgentModelMetadataResolver {
    fun resolve(
        profile: AiProviderProfile,
        provider: AiModelDescriptor?,
        publicCatalog: AiModelDescriptor? = null,
        builtin: AiModelDescriptor? = null,
    ): AiModelDescriptor {
        val modelId = profile.modelId ?: provider?.id ?: publicCatalog?.id ?: builtin?.id.orEmpty()
        require(modelId.isNotBlank()) { "模型 ID 不能为空" }
        val candidates = listOfNotNull(provider, publicCatalog, builtin).filter { it.id == modelId }
        val first = candidates.firstOrNull() ?: AiModelDescriptor(modelId)
        val manualContext = profile.contextWindowOverride
        return first.copy(
            id = modelId,
            displayName = candidates.firstOrNull { it.displayName.isNotBlank() }?.displayName ?: modelId,
            contextWindowTokens = manualContext
                ?: candidates.firstNotNullOfOrNull { it.contextWindowTokens }
                ?: AgentModelContextResolver.DEFAULT_CONTEXT_WINDOW_TOKENS,
            maxOutputTokens = candidates.firstNotNullOfOrNull { it.maxOutputTokens },
            supportsVision = candidates.firstNotNullOfOrNull { it.supportsVision },
            supportsTools = candidates.firstNotNullOfOrNull { it.supportsTools },
            metadataSource = when {
                manualContext != null -> ModelMetadataSource.MANUAL
                candidates.isEmpty() -> ModelMetadataSource.DEFAULT
                else -> candidates.first().metadataSource
            },
        )
    }

    fun resolve(
        profile: AiProviderProfile,
        providerCatalog: List<AiModelDescriptor>,
        publicCatalog: List<AiModelDescriptor> = emptyList(),
        builtinCatalog: List<AiModelDescriptor> = emptyList(),
    ): AiModelDescriptor? {
        val id = profile.modelId ?: return null
        val provider = providerCatalog.firstOrNull { it.id == id }
        val public = publicCatalog.firstOrNull { it.id == id }
        val builtin = builtinCatalog.firstOrNull { it.id == id }
        if (provider == null && public == null && builtin == null) return null
        return resolve(profile, provider, public, builtin)
    }
}


