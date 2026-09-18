package com.ailife.wftools.pc.market

import com.ailife.wftools.pc.WatchMarket
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One watch face as listed by the market.
 *
 * [protected] mirrors the store's `wm_protected` flag for telemetry. It never gates downloads or
 * opening; the desktop app's hidden protected-face capability decides whether decryption is
 * allowed. New fields go at the end with a default so existing positional
 * construction keeps compiling.
 */
data class MarketItem(
    val id: String,
    val name: String,
    val previewPath: String,
    val category: String,
    val screenType: String,
    val downloadCount: Int,
    val favoriteCount: Int,
    val views: Int,
    val tags: List<String>,
    val version: String,
    val createdAt: String,
    val author: String = "",
    val description: String = "",
    val protected: Boolean = false,
)

/** A page of results plus the total the server reports. */
data class MarketPage(val items: List<MarketItem>, val total: Int)

/** How the market orders its listing. */
enum class MarketSort(val wire: String, val label: String) {
    LATEST("latest", "最新"),
    POPULAR("popular", "热门"),
    TRENDING("trending", "趋势"),
    ;

    companion object {
        fun parse(raw: String?): MarketSort = entries.firstOrNull { it.wire == raw?.trim() } ?: LATEST
    }
}

/** Which screen shape a listing is filtered to. */
enum class MarketScreenType(val wire: String, val label: String) {
    // Labels are deliberately distinct from the plain shape names ("圆形" and friends) so the
    // market chips do not inherit the shape translations used by the property inspector.
    ANY("any", "全部"),
    ROUND("round", "圆屏"),
    SQUARE("square", "方屏"),
    RECTANGULAR("rectangular", "矩形屏"),
    ;

    companion object {
        fun parse(raw: String?): MarketScreenType = entries.firstOrNull { it.wire == raw?.trim() } ?: ANY
    }
}

/** Raised when the market cannot serve a request. */
class MarketException(message: String) : Exception(message)

/**
 * Fetches bytes for a URL. Kept as an interface so the API client can be tested without
 * touching the network, and so the window can reuse one place for timeouts and size caps.
 */
fun interface MarketTransport {
    /** Returns the response body, or throws [MarketException]. */
    fun get(url: String, maxBytes: Int): ByteArray
}

/** Default transport: a bounded `HttpURLConnection` GET, matching the agent's external tools. */
class UrlMarketTransport(private val userAgent: String = "WFTools-PC/1.0") : MarketTransport {
    override fun get(url: String, maxBytes: Int): ByteArray {
        val target = URI.create(url)
        if (target.scheme !in setOf("http", "https") || target.host == null) throw MarketException("市场地址无效：$url")
        val connection = target.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, image/*")
            connection.setRequestProperty("User-Agent", userAgent)
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readNBytes(maxBytes + 1) } ?: byteArrayOf()
            if (bytes.size > maxBytes) throw MarketException("市场响应超过大小限制")
            if (status !in 200..299) throw MarketException("市场返回 HTTP $status")
            return bytes
        } finally { connection.disconnect() }
    }
}

/**
 * Watchmaker Community v2 client.
 *
 * Field access is deliberately defensive: the store is a third-party service with no published
 * version contract, so a missing or retyped field degrades a card instead of failing the page.
 */
class WatchMarketApi(
    private val transport: MarketTransport = UrlMarketTransport(),
    private val baseUrl: String = WatchMarket.API_ROOT,
) {
    fun list(
        page: Int = 1,
        limit: Int = DEFAULT_LIMIT,
        sort: MarketSort = MarketSort.LATEST,
        category: String = "",
        search: String = "",
        screenType: MarketScreenType = MarketScreenType.ANY,
    ): MarketPage {
        val url = buildString {
            append(baseUrl.trimEnd('/')).append("/watchfaces")
            append("?page=").append(page.coerceAtLeast(1))
            append("&limit=").append(limit.coerceIn(1, MAX_LIMIT))
            append("&sort=").append(encode(sort.wire))
            if (category.isNotBlank()) append("&category=").append(encode(category))
            if (search.isNotBlank()) append("&search=").append(encode(search))
            // "any" is expressed by omitting the parameter rather than sending the literal.
            if (screenType != MarketScreenType.ANY) append("&screen_type=").append(encode(screenType.wire))
        }
        val root = parseObject(transport.get(url, MAX_LIST_BYTES))
        val data = root.getAsJsonArray("data") ?: return MarketPage(emptyList(), 0)
        val total = root.getAsJsonObject("pagination")?.get("total")?.asInt ?: data.size()
        val items = data.mapNotNull { element -> runCatching { parseItem(element.asJsonObject) }.getOrNull() }
        return MarketPage(items, total)
    }

    /** Categories the store exposes, as (slug, display name). */
    fun categories(): List<Pair<String, String>> {
        val root = runCatching { parseObject(transport.get("${baseUrl.trimEnd('/')}/categories", MAX_LIST_BYTES)) }.getOrNull() ?: return emptyList()
        val data = root.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { element ->
            val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val slug = item.get("slug")?.asString.orEmpty()
            val name = item.get("name")?.asString.orEmpty()
            if (slug.isBlank() || name.isBlank()) null else slug to name
        }
    }

    /** Full detail for one face, including uploader and description. */
    fun detail(id: String): MarketItem? = runCatching {
        val root = parseObject(transport.get("${baseUrl.trimEnd('/')}/watchfaces/${encode(id)}", MAX_LIST_BYTES))
        parseItem(root)
    }.getOrNull()

    /** Downloads the `.watch` archive. */
    fun download(id: String): ByteArray = transport.get("${baseUrl.trimEnd('/')}/watchfaces/${encode(id)}/download", MAX_DOWNLOAD_BYTES)

    /** Preview image bytes for a card. */
    fun preview(id: String): ByteArray? = runCatching { transport.get("${baseUrl.trimEnd('/')}/watchfaces/${encode(id)}/preview", MAX_PREVIEW_BYTES) }.getOrNull()

    /**
     * Parses a JSON object body, normalising every failure to [MarketException]. Gson throws
     * unchecked parser errors, and a third-party store can return an HTML error page under a
     * 200, so callers must never see a raw parsing exception.
     */
    private fun parseObject(raw: ByteArray): com.google.gson.JsonObject =
        try {
            val element = JsonParser.parseString(String(raw, StandardCharsets.UTF_8))
            if (!element.isJsonObject) throw MarketException("市场返回了非对象 JSON")
            element.asJsonObject
        } catch (error: MarketException) {
            throw error
        } catch (error: Exception) {
            throw MarketException("市场返回了无效 JSON：${error.message ?: "无法解析"}")
        }

    private fun parseItem(item: com.google.gson.JsonObject): MarketItem {
        val id = item.get("id")?.asString.orEmpty()
        val tags = item.getAsJsonArray("tags")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
        return MarketItem(
            id = id,
            name = item.get("name")?.asString?.takeIf(String::isNotBlank) ?: "Watchface $id",
            previewPath = item.get("preview_url")?.asString.orEmpty(),
            category = item.getAsJsonObject("category")?.get("name")?.asString.orEmpty(),
            screenType = item.get("screen_type")?.asString.orEmpty(),
            downloadCount = item.get("download_count")?.asInt ?: 0,
            favoriteCount = item.get("favorite_count")?.asInt ?: 0,
            views = item.get("views")?.asInt ?: 0,
            tags = tags,
            version = item.get("wm_version")?.asString.orEmpty(),
            createdAt = item.get("created_at")?.asString.orEmpty().take(10),
            author = item.getAsJsonObject("uploader")?.get("username")?.asString.orEmpty(),
            description = item.get("description")?.asString.orEmpty(),
            // `wm_protected` is a plain boolean in both the list and the detail payload. Guard the
            // shape (an explicit JSON null is not a primitive) so a retyped field cannot fail the page.
            protected = item.get("wm_protected")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val MAX_LIST_BYTES = 512 * 1024
        const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024
    }
}
