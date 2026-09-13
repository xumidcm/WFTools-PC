package com.ailife.wftools.agent.provider

import com.ailife.wftools.agent.model.AiModelDescriptor
import com.ailife.wftools.agent.model.AiProviderProfile
import com.ailife.wftools.agent.model.ModelMetadataSource

/** Applies the documented precedence for the context window shown to the user. */
object AgentModelContextResolver {
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000L

    fun contextWindow(profile: AiProviderProfile, descriptor: AiModelDescriptor): Long =
        profile.contextWindowOverride
            ?: descriptor.contextWindowTokens
            ?: DEFAULT_CONTEXT_WINDOW_TOKENS

    fun resolve(profile: AiProviderProfile, descriptor: AiModelDescriptor): AiModelDescriptor {
        val manual = profile.contextWindowOverride
        return descriptor.copy(
            contextWindowTokens = manual ?: descriptor.contextWindowTokens ?: DEFAULT_CONTEXT_WINDOW_TOKENS,
            metadataSource = if (manual != null) ModelMetadataSource.MANUAL
            else if (descriptor.contextWindowTokens == null) ModelMetadataSource.DEFAULT
            else descriptor.metadataSource,
        )
    }
}


