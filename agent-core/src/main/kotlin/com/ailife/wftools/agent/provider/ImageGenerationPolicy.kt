package com.ailife.wftools.agent.provider

/** Optional image parameters selected from the requested watch-face intent. */
data class ImageGenerationOptions(
    val size: String? = null,
    val background: String? = null,
    val quality: String? = null,
)

/**
 * Keeps image generation defaults out of user settings. Parameters are only
 * sent when the host has enough context to make a useful choice; otherwise
 * the provider's defaults apply.
 */
object ImageGenerationPolicy {
    fun forWatchFace(width: Int, height: Int, transparentAsset: Boolean): ImageGenerationOptions {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val background = if (transparentAsset) "transparent" else null
        val size = when {
            transparentAsset && height > width * 1.05f -> "1024x1536"
            transparentAsset && width > height * 1.05f -> "1536x1024"
            else -> null
        }
        return ImageGenerationOptions(size = size, background = background)
    }
}


