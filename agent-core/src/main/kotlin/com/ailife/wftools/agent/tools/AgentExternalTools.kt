package com.ailife.wftools.agent.tools

/** Optional desktop services configured in the global Agent settings. */
interface AgentExternalTools {
    val imageGenerationAvailable: Boolean
    val webSearchAvailable: Boolean
    fun generateImage(prompt: String): WatchAgentImageResult
    fun searchWeb(query: String): String
}
