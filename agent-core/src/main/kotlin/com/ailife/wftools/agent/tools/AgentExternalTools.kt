package com.ailife.wftools.agent.tools

/** Optional desktop services configured in the global Agent settings. */
interface AgentExternalTools {
    val imageGenerationAvailable: Boolean
    val imageEditingAvailable: Boolean
    val webSearchAvailable: Boolean
    fun generateImage(request: WatchAgentImageGenerationRequest): WatchAgentImageResult
    fun editImage(request: WatchAgentImageEditRequest): WatchAgentImageResult
    fun searchWeb(query: String): String
    fun scrapePage(url: String): String
}

/** Parameters shared by OpenAI-compatible image generation endpoints. */
data class WatchAgentImageGenerationRequest(
    val prompt: String,
    val model: String? = null,
    val size: String? = null,
    val quality: String? = null,
    val background: String? = null,
    val outputFormat: String? = null,
    val outputCompression: Int? = null,
    val moderation: String? = null,
)

/** An image edit request. The source and optional mask are workspace-relative or face-relative paths. */
data class WatchAgentImageEditRequest(
    val path: String,
    val prompt: String,
    val maskPath: String? = null,
    val model: String? = null,
    val size: String? = null,
    val quality: String? = null,
    val background: String? = null,
    val outputFormat: String? = null,
    val outputCompression: Int? = null,
    val inputFidelity: String? = null,
)
