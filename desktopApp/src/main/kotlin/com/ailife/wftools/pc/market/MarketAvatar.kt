package com.ailife.wftools.pc.market

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

private const val MARKET_AVATAR_CACHE_LIMIT = 32

/** Small process cache shared by the market header and the creator dashboard. */
internal object MarketAvatarCache {
    private val values = object : LinkedHashMap<String, ImageBitmap>(MARKET_AVATAR_CACHE_LIMIT + 1, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MARKET_AVATAR_CACHE_LIMIT
    }

    fun key(username: String, avatarUrl: String?, version: Long = 0L): String =
        "${username.trim().lowercase()}|${avatarUrl?.trim().orEmpty()}#$version"

    fun get(key: String): ImageBitmap? = synchronized(values) { values[key] }

    fun put(key: String, bitmap: ImageBitmap) = synchronized(values) { values[key] = bitmap }

    fun invalidate(username: String) = synchronized(values) {
        val prefix = "${username.trim().lowercase()}|"
        values.keys.removeIf { it.startsWith(prefix) }
    }
}

internal fun decodeMarketAvatar(bytes: ByteArray): ImageBitmap? = runCatching {
    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    try {
        image.toComposeImageBitmap()
    } finally {
        image.close()
    }
}.getOrNull()

/** Circular avatar with the same first-letter fallback used by Flue's store UI. */
@Composable
internal fun MarketAvatar(
    api: MarketAccountApi,
    username: String,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    version: Long = 0L,
    tag: String? = null,
) {
    val key = remember(username, avatarUrl, version) { MarketAvatarCache.key(username, avatarUrl, version) }
    var bitmap by remember(key) { mutableStateOf(MarketAvatarCache.get(key)) }
    LaunchedEffect(api, key) {
        if (bitmap != null || username.isBlank()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching { api.avatarBytesForUser(username, avatarUrl) }.getOrNull()?.let(::decodeMarketAvatar)
        }
        if (loaded != null) {
            MarketAvatarCache.put(key, loaded)
            bitmap = loaded
        }
    }
    val shaped = modifier.size(size).clip(CircleShape).then(if (tag != null) Modifier.testTag(tag) else Modifier)
    Box(
        modifier = shaped.background(
            if (bitmap == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                text = username.trim().take(1).uppercase().ifBlank { "?" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
