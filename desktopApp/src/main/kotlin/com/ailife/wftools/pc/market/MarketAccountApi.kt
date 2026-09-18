package com.ailife.wftools.pc.market

import com.ailife.wftools.pc.WatchMarket
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MarketUser(val id: String, val username: String, val role: String)
data class MarketSession(val token: String, val user: MarketUser)

/** Values restored into the native login form when the user opts into remembering them. */
data class MarketRememberedCredentials(
    val username: String = "",
    val password: String = "",
    val rememberUsername: Boolean = false,
    val rememberPassword: Boolean = false,
)

interface MarketCredentialStore {
    fun load(): MarketRememberedCredentials
    fun save(username: String, password: String, rememberUsername: Boolean, rememberPassword: Boolean)
    fun clear()
}

/**
 * Small per-user credential store for the login form. The bearer token deliberately remains
 * session-only; an opted-in password is encrypted before it reaches the Preferences backend.
 */
class PersistentMarketCredentialStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(PersistentMarketCredentialStore::class.java),
) : MarketCredentialStore {
    override fun load(): MarketRememberedCredentials = synchronized(preferences) {
        val rememberUsername = preferences.getBoolean(KEY_REMEMBER_USERNAME, false)
        val rememberPassword = preferences.getBoolean(KEY_REMEMBER_PASSWORD, false)
        val username = preferences.get(KEY_USERNAME, "").takeIf { rememberUsername }.orEmpty()
        val password = if (rememberPassword) decrypt(preferences.get(KEY_PASSWORD, "")) else null
        MarketRememberedCredentials(
            username = username,
            password = password.orEmpty(),
            rememberUsername = rememberUsername && username.isNotBlank(),
            rememberPassword = rememberPassword && !password.isNullOrEmpty(),
        )
    }

    override fun save(username: String, password: String, rememberUsername: Boolean, rememberPassword: Boolean) {
        synchronized(preferences) {
            val cleanUsername = username.trim()
            val keepUsername = rememberUsername || rememberPassword
            val keepPassword = rememberPassword && password.isNotEmpty()
            if (keepUsername) preferences.put(KEY_USERNAME, cleanUsername) else preferences.remove(KEY_USERNAME)
            preferences.putBoolean(KEY_REMEMBER_USERNAME, keepUsername)
            if (keepPassword) preferences.put(KEY_PASSWORD, encrypt(password)) else preferences.remove(KEY_PASSWORD)
            preferences.putBoolean(KEY_REMEMBER_PASSWORD, keepPassword)
            runCatching { preferences.flush() }
        }
    }

    override fun clear() {
        synchronized(preferences) {
            listOf(KEY_USERNAME, KEY_PASSWORD, KEY_REMEMBER_USERNAME, KEY_REMEMBER_PASSWORD, KEY_KEY).forEach(preferences::remove)
            runCatching { preferences.flush() }
        }
    }

    private fun encrypt(value: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes(), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val payload = Base64.getDecoder().decode(encoded)
        if (payload.size <= GCM_IV_BYTES) return null
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(payload.copyOfRange(GCM_IV_BYTES, payload.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun keyBytes(): ByteArray {
        val existing = preferences.getByteArray(KEY_KEY, null)
        if (existing != null && existing.size == AES_KEY_BYTES) return existing
        val generated = ByteArray(AES_KEY_BYTES).also(SecureRandom()::nextBytes)
        preferences.putByteArray(KEY_KEY, generated)
        runCatching { preferences.flush() }
        return generated
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "passwordCiphertext"
        const val KEY_REMEMBER_USERNAME = "rememberUsername"
        const val KEY_REMEMBER_PASSWORD = "rememberPassword"
        const val KEY_KEY = "encryptionKey"
        const val CIPHER = "AES/GCM/NoPadding"
        const val AES_KEY_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

data class MarketAccount(
    val id: String,
    val username: String,
    val role: String,
    val disabled: Boolean = false,
    val createdAt: String = "",
    val description: String = "",
    val timezone: String = "UTC",
    val language: String = "en",
    val avatarUrl: String? = null,
)

data class MarketCategory(val id: String, val name: String, val slug: String)

data class MarketScan(
    val success: Boolean = false,
    val verdict: String = "review",
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val security: List<String> = emptyList(),
    val validation: List<String> = emptyList(),
)

data class MarketParsedWatchface(
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val author: String = "",
    val screenType: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val wmVersion: String = "",
    val protected: Boolean = false,
    val layerCount: Int = 0,
    val layerTypes: List<String> = emptyList(),
    val hasScript: Boolean = false,
)

data class MarketParseResult(val parsed: MarketParsedWatchface, val scan: MarketScan)

data class MarketOwnedWatchface(
    val id: String,
    val name: String,
    val description: String = "",
    val screenType: String = "",
    val status: String = "pending",
    val featured: Boolean = false,
    val rejectionReason: String? = null,
    val category: MarketCategory? = null,
    val tags: List<String> = emptyList(),
    val wmVersion: String = "",
    val protected: Boolean = false,
    val layerCount: Int = 0,
    val downloadCount: Int = 0,
    val favorites: Int = 0,
    val views: Int = 0,
    val comments: Int = 0,
    val createdAt: String = "",
    val previewUrl: String = "",
    val scan: MarketScan? = null,
)

data class MarketWatchfaceForm(
    val name: String,
    val description: String,
    val screenType: String,
    val categoryId: String,
    val tags: String = "",
)

data class MarketTrendPoint(val day: String, val count: Int)

data class MarketStatsTotals(
    val watchfaces: Int = 0,
    val published: Int = 0,
    val pending: Int = 0,
    val rejected: Int = 0,
    val delisted: Int = 0,
    val downloads: Int = 0,
    val favorites: Int = 0,
    val views: Int = 0,
)

data class MarketStats(
    val totals: MarketStatsTotals = MarketStatsTotals(),
    val downloadTrend: List<MarketTrendPoint> = emptyList(),
    val viewsTrend: List<MarketTrendPoint> = emptyList(),
    val favoritesTrend: List<MarketTrendPoint> = emptyList(),
)

data class MarketNotification(
    val id: String,
    val type: String,
    val title: String,
    val message: String,
    val targetType: String = "",
    val targetId: String? = null,
    val readAt: String? = null,
    val createdAt: String = "",
) {
    val unread: Boolean get() = readAt.isNullOrBlank()
}

data class MarketNotifications(val items: List<MarketNotification>, val unread: Int)

data class MarketConfig(val registrationEnabled: Boolean = true, val maxUploadMb: Int = 20)

/** A request object keeps the authenticated transport easy to fake in desktop tests. */
data class MarketMultipartFile(val field: String, val path: Path, val contentType: String = "application/octet-stream")

data class MarketMultipart(
    val fields: Map<String, String> = emptyMap(),
    val file: MarketMultipartFile? = null,
)

data class MarketAccountRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val multipart: MarketMultipart? = null,
)

data class MarketAccountResponse(val status: Int, val body: ByteArray, val contentType: String = "")

fun interface MarketAccountTransport {
    fun execute(request: MarketAccountRequest): MarketAccountResponse
}

class MarketAccountException(message: String, val status: Int? = null) : Exception(message)

/**
 * Authenticated client for the Watchmaker Community Store user API.
 *
 * The token intentionally lives in [MarketSession] owned by the running desktop session. It is
 * never written to logs or the watch-face document; a future OS credential-store integration can
 * persist it without changing this API surface.
 */
class MarketAccountApi(
    private val baseUrl: String = WatchMarket.API_ROOT,
    private val transport: MarketAccountTransport = UrlMarketAccountTransport(),
) {
    fun login(username: String, password: String): MarketSession =
        parseSession(json("POST", "/auth/login", body = jsonBody("username" to username, "password" to password)))

    fun register(username: String, password: String, confirmPassword: String): MarketSession =
        parseSession(json("POST", "/auth/register", body = jsonBody(
            "username" to username,
            "password" to password,
            "confirmPassword" to confirmPassword,
        )))

    fun config(): MarketConfig {
        val root = json("GET", "/config").asObjectOrEmpty()
        val features = root.objectValue("features")
        return MarketConfig(
            registrationEnabled = features.bool("registration_enabled", true),
            maxUploadMb = features.int("max_upload_size_mb", 20).coerceIn(1, 200),
        )
    }

    fun me(token: String): MarketAccount = parseAccount(json("GET", "/my/me", token = token).asObjectOrEmpty())

    /** Downloads the public avatar bytes while keeping the URL on the market origin. */
    fun avatarBytes(token: String, avatarUrl: String): ByteArray {
        val request = MarketAccountRequest("GET", avatarRequestUrl(avatarUrl), authHeaders(token))
        val response = execute(request)
        if (response.status !in 200..299) throw MarketAccountException(errorText(response), response.status)
        if (response.body.size > MAX_AVATAR_BYTES) throw MarketAccountException("头像响应超过大小限制")
        return response.body
    }

    fun updateProfile(token: String, description: String? = null, timezone: String? = null, language: String? = null): MarketAccount {
        val values = buildMap {
            description?.let { put("description", it) }
            timezone?.let { put("timezone", it) }
            language?.let { put("language", it) }
        }
        if (values.isEmpty()) throw MarketAccountException("没有要保存的个人资料修改")
        return parseAccount(json("PATCH", "/my/profile", token = token, body = jsonBody(values)).asObjectOrEmpty())
    }

    fun changePassword(token: String, currentPassword: String, newPassword: String) {
        json("POST", "/my/password", token = token, body = jsonBody(
            "currentPassword" to currentPassword,
            "newPassword" to newPassword,
        ))
    }

    fun uploadAvatar(token: String, file: Path) {
        val readable = requireReadableFile(file)
        multipart("POST", "/my/profile/avatar", token, MarketMultipart(file = MarketMultipartFile("file", readable, imageContentType(readable))))
    }

    fun categories(): List<MarketCategory> = json("GET", "/categories").asObjectOrEmpty().array("data").mapNotNull(::parseCategory)

    fun myWatchfaces(token: String): List<MarketOwnedWatchface> =
        json("GET", "/my/watchfaces", token = token).asObjectOrEmpty().array("data").mapNotNull(::parseOwnedWatchface)

    fun stats(token: String, days: Int = 30): MarketStats {
        val root = json("GET", "/my/stats?days=${days.coerceIn(1, 90)}", token = token).asObjectOrEmpty()
        return MarketStats(
            totals = parseTotals(root.objectValue("totals")),
            downloadTrend = parseTrend(root.array("downloadTrend")),
            viewsTrend = parseTrend(root.array("viewsTrend")),
            favoritesTrend = parseTrend(root.array("favoritesTrend")),
        )
    }

    fun notifications(token: String, limit: Int = 20): MarketNotifications {
        val root = json("GET", "/my/notifications?limit=${limit.coerceIn(1, 100)}", token = token).asObjectOrEmpty()
        return MarketNotifications(root.array("data").mapNotNull(::parseNotification), root.int("unread", 0))
    }

    fun unreadCount(token: String): Int = json("GET", "/my/notifications/unread-count", token = token).asObjectOrEmpty().int("unread", 0)

    fun markNotificationRead(token: String, id: String) { json("POST", "/my/notifications/${segment(id)}/read", token = token) }

    fun markAllNotificationsRead(token: String) { json("POST", "/my/notifications/read-all", token = token) }

    fun parseWatchface(token: String, file: Path): MarketParseResult {
        val root = multipart("POST", "/my/watchfaces/parse", token, MarketMultipart(file = watchPart(requireReadableFile(file)))).asObjectOrEmpty()
        return MarketParseResult(parseParsed(root.objectValue("parsed")), parseScan(root.objectValue("scan")))
    }

    fun uploadWatchface(token: String, file: Path, form: MarketWatchfaceForm): MarketOwnedWatchface =
        parseOwnedWatchface(multipart("POST", "/my/watchfaces", token, MarketMultipart(formFields(form), watchPart(requireReadableFile(file)))).asObjectOrEmpty())
            ?: throw MarketAccountException("服务器返回的表盘信息无效")

    fun editWatchface(token: String, id: String, form: MarketWatchfaceForm, file: Path? = null): MarketOwnedWatchface =
        parseOwnedWatchface(multipart("PUT", "/my/watchfaces/${segment(id)}", token, MarketMultipart(formFields(form), file?.let { watchPart(requireReadableFile(it)) })).asObjectOrEmpty())
            ?: throw MarketAccountException("服务器返回的表盘信息无效")

    fun deleteWatchface(token: String, id: String) { json("DELETE", "/my/watchfaces/${segment(id)}", token = token) }

    fun unlistWatchface(token: String, id: String) { json("POST", "/my/watchfaces/${segment(id)}/unlist", token = token) }

    fun republishWatchface(token: String, id: String) { json("POST", "/my/watchfaces/${segment(id)}/republish", token = token) }

    private fun formFields(form: MarketWatchfaceForm): Map<String, String> = mapOf(
        "name" to form.name,
        "description" to form.description,
        "screen_type" to form.screenType,
        "category_id" to form.categoryId,
        "tags" to form.tags,
    )

    private fun watchPart(file: Path): MarketMultipartFile = MarketMultipartFile("file", file, "application/octet-stream")

    private fun requireReadableFile(file: Path): Path {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw MarketAccountException("找不到可读取的文件：${file.fileName}")
        }
        return file
    }

    private fun multipart(method: String, path: String, token: String, payload: MarketMultipart): JsonElement =
        request(MarketAccountRequest(method, url(path), authHeaders(token), multipart = payload))

    private fun json(method: String, path: String, token: String? = null, body: ByteArray? = null): JsonElement =
        request(MarketAccountRequest(method, url(path), authHeaders(token) + if (body != null) mapOf("Content-Type" to "application/json; charset=utf-8") else emptyMap(), body = body))

    private fun request(request: MarketAccountRequest): JsonElement {
        val response = execute(request)
        if (response.status !in 200..299) throw MarketAccountException(errorText(response), response.status)
        if (response.body.isEmpty()) return JsonNull.INSTANCE
        return try { JsonParser.parseString(String(response.body, StandardCharsets.UTF_8)) }
        catch (error: Exception) { throw MarketAccountException("市场返回了无效数据：${error.message ?: "无法解析"}", response.status) }
    }

    private fun execute(request: MarketAccountRequest): MarketAccountResponse = try {
            transport.execute(request)
        } catch (error: MarketAccountException) {
            // Preserve useful local validation and server-facing domain errors verbatim.
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MarketAccountException("市场请求被中断")
        } catch (error: IOException) {
            throw MarketAccountException("无法连接表盘市场：${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            throw MarketAccountException("市场请求失败：${error.message ?: error.javaClass.simpleName}")
        }

    private fun authHeaders(token: String? = null): Map<String, String> = buildMap {
        put("Accept", "application/json")
        token?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private fun avatarRequestUrl(rawPath: String): String {
        val path = rawPath.trim()
        if (path.isBlank()) throw MarketAccountException("头像地址为空")
        val base = runCatching { URI.create(baseUrl) }.getOrElse { throw MarketAccountException("市场地址无效") }
        val candidate = runCatching { URI.create(path) }.getOrElse { throw MarketAccountException("头像地址无效") }
        if (candidate.isAbsolute) {
            val sameOrigin = candidate.scheme.equals(base.scheme, true) && candidate.host.equals(base.host, true) &&
                effectivePort(candidate) == effectivePort(base)
            if (!sameOrigin) throw MarketAccountException("头像地址不是市场同源地址")
            return candidate.toString()
        }
        if (path.startsWith("//")) throw MarketAccountException("头像地址无效")
        return if (path.startsWith("/")) {
            runCatching { URI(base.scheme, base.authority, path, null, null).toString() }
                .getOrElse { throw MarketAccountException("头像地址无效") }
        } else url(path)
    }

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun errorText(response: MarketAccountResponse): String {
        val fallback = "市场请求失败（HTTP ${response.status}）"
        if (response.body.isEmpty()) return fallback
        return runCatching {
            val root = JsonParser.parseString(String(response.body, StandardCharsets.UTF_8)).asJsonObject
            root.string("error").ifBlank {
                root.array("errors").mapNotNull { error ->
                    error.asJsonObjectOrNull()?.string("msg")?.takeIf(String::isNotBlank)
                        ?: runCatching { error.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()?.takeIf(String::isNotBlank)
                }.joinToString(", ").ifBlank { fallback }
            }
        }.getOrDefault(fallback)
    }

    private fun parseSession(root: JsonElement): MarketSession {
        val obj = root.asObjectOrEmpty()
        val token = obj.string("token")
        if (token.isBlank()) throw MarketAccountException("登录响应缺少令牌")
        return MarketSession(token, parseUser(obj.objectValue("user")))
    }

    private fun parseUser(obj: JsonObject): MarketUser = MarketUser(obj.string("id"), obj.string("username"), obj.string("role", "user"))

    private fun parseAccount(obj: JsonObject): MarketAccount = MarketAccount(
        id = obj.string("id"), username = obj.string("username"), role = obj.string("role", "user"),
        disabled = obj.bool("disabled"), createdAt = obj.string("created_at"), description = obj.string("description"),
        timezone = obj.string("timezone", "UTC"), language = obj.string("language", "en"), avatarUrl = obj.stringOrNull("avatar_url"),
    )

    private fun parseCategory(element: JsonElement): MarketCategory? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val id = obj.string("id")
        val name = obj.string("name")
        if (id.isBlank() || name.isBlank()) return null
        return MarketCategory(id, name, obj.string("slug"))
    }

    private fun parseOwnedWatchface(element: JsonElement): MarketOwnedWatchface? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val id = obj.string("id")
        if (id.isBlank()) return null
        return MarketOwnedWatchface(
            id = id, name = obj.string("name", "未命名表盘"), description = obj.string("description"),
            screenType = obj.string("screen_type"), status = obj.string("status", "pending"), featured = obj.bool("featured"),
            rejectionReason = obj.stringOrNull("rejection_reason"), category = parseCategory(obj.objectValue("category")),
            tags = obj.strings("tags"), wmVersion = obj.string("wm_version"), protected = obj.bool("wm_protected"),
            layerCount = obj.int("layer_count"), downloadCount = obj.int("download_count"), favorites = obj.int("favorite_count", obj.int("favorites")),
            views = obj.int("views"), comments = obj.int("comment_count"), createdAt = obj.string("created_at"),
            previewUrl = obj.string("preview_url"),
            scan = (obj.objectValueOrNull("scan") ?: obj.objectValueOrNull("scan_result"))?.let(::parseScan),
        )
    }

    private fun parseScan(obj: JsonObject): MarketScan = MarketScan(
        success = obj.bool("success"), verdict = obj.string("verdict", "review"), errors = obj.strings("errors"),
        warnings = obj.strings("warnings"), security = obj.strings("security"), validation = obj.strings("validation"),
    )

    private fun parseParsed(obj: JsonObject): MarketParsedWatchface = MarketParsedWatchface(
        title = obj.string("title"), description = obj.string("description"), tags = obj.strings("tags"), author = obj.string("author"),
        screenType = obj.string("screen_type", obj.string("shape")), width = obj.int("width", 512), height = obj.int("height", 512),
        wmVersion = obj.string("wm_version"), protected = obj.bool("wm_protected"), layerCount = obj.int("layer_count"),
        layerTypes = obj.strings("layer_types"), hasScript = obj.bool("has_script"),
    )

    private fun parseTotals(obj: JsonObject): MarketStatsTotals = MarketStatsTotals(
        watchfaces = obj.int("watchfaces"), published = obj.int("published"), pending = obj.int("pending"),
        rejected = obj.int("rejected"), delisted = obj.int("delisted"), downloads = obj.int("downloads"),
        favorites = obj.int("favorites"), views = obj.int("views"),
    )

    private fun parseTrend(elements: List<JsonElement>): List<MarketTrendPoint> = elements.mapNotNull { element ->
        element.asJsonObjectOrNull()?.let { MarketTrendPoint(it.string("day"), it.int("count")) }
    }

    private fun parseNotification(element: JsonElement): MarketNotification? {
        val obj = element.asJsonObjectOrNull() ?: return null
        val id = obj.string("id")
        if (id.isBlank()) return null
        return MarketNotification(id, obj.string("type"), obj.string("title"), obj.string("message"), obj.string("target_type"), obj.stringOrNull("target_id"), obj.stringOrNull("read_at"), obj.string("created_at"))
    }

    private fun jsonBody(vararg values: Pair<String, String>): ByteArray = jsonBody(values.toMap())

    private fun jsonBody(values: Map<String, String>): ByteArray = JsonObject().apply { values.forEach { (key, value) -> addProperty(key, value) } }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun imageContentType(file: Path): String = when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private companion object {
        const val MAX_AVATAR_BYTES = 4 * 1024 * 1024
    }
}

/** Real network transport with bounded responses and streamed multipart uploads. */
class UrlMarketAccountTransport(private val userAgent: String = "WFTools-PC/1.0") : MarketAccountTransport {
    override fun execute(request: MarketAccountRequest): MarketAccountResponse {
        // HttpURLConnection rejects PATCH on the JDK even though the store uses it for profile
        // updates. Keep the streamed HttpURLConnection path for uploads, and use the JDK client
        // for this small JSON-only method so profile editing works on a stock desktop runtime.
        if (request.method.equals("PATCH", ignoreCase = true)) return executePatch(request)
        val connection = URI.create(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            // Never follow redirects for authenticated requests: a redirect to another origin
            // must not receive the caller's Bearer token.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", userAgent)
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val multipart = request.multipart
            if (multipart != null) {
                val boundary = "----WFTools-${UUID.randomUUID()}"
                connection.doOutput = true
                connection.setChunkedStreamingMode(64 * 1024)
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                DataOutputStream(connection.outputStream).use { writeMultipart(it, boundary, multipart) }
            } else if (request.body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) } ?: byteArrayOf()
            if (bytes.size > MAX_RESPONSE_BYTES) throw MarketAccountException("市场响应过大")
            return MarketAccountResponse(status, bytes, connection.contentType.orEmpty())
        } finally { connection.disconnect() }
    }

    private fun writeMultipart(out: DataOutputStream, boundary: String, payload: MarketMultipart) {
        fun write(value: String) { out.write(value.toByteArray(StandardCharsets.UTF_8)) }
        payload.fields.forEach { (name, value) ->
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            write(value)
            write("\r\n")
        }
        payload.file?.let { file ->
            val safeName = file.path.fileName.toString().replace(Regex("[\\\"\\r\\n]"), "_")
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"${file.field}\"; filename=\"$safeName\"\r\n")
            write("Content-Type: ${file.contentType}\r\n\r\n")
            Files.size(file.path).takeIf { it <= MAX_UPLOAD_BYTES } ?: throw MarketAccountException("上传文件超过 200 MB 限制")
            Files.newInputStream(file.path).use { it.copyTo(out, 64 * 1024) }
            write("\r\n")
        }
        write("--$boundary--\r\n")
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_UPLOAD_BYTES = 200L * 1024L * 1024L
    }

    private fun executePatch(request: MarketAccountRequest): MarketAccountResponse {
        val builder = HttpRequest.newBuilder(URI.create(request.url))
            .timeout(java.time.Duration.ofSeconds(120))
            .header("User-Agent", userAgent)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        val body = request.body ?: ByteArray(0)
        val httpRequest = builder.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body)).build()
        val response = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
        if (response.body().size > MAX_RESPONSE_BYTES) throw MarketAccountException("市场响应过大")
        return MarketAccountResponse(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse(""))
    }
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonElement?.asObjectOrEmpty(): JsonObject = asJsonObjectOrNull() ?: JsonObject()
private fun JsonObject.objectValue(name: String): JsonObject = get(name).asJsonObjectOrNull() ?: JsonObject()
private fun JsonObject.objectValueOrNull(name: String): JsonObject? = get(name).asJsonObjectOrNull()
private fun JsonObject.string(name: String, fallback: String = ""): String = runCatching { get(name)?.takeIf { !it.isJsonNull }?.asString ?: fallback }.getOrDefault(fallback)
private fun JsonObject.stringOrNull(name: String): String? = string(name).takeIf(String::isNotBlank)
private fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean = runCatching { get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: fallback }.getOrDefault(fallback)
private fun JsonObject.int(name: String, fallback: Int = 0): Int = runCatching { get(name)?.takeIf { !it.isJsonNull }?.asInt ?: fallback }.getOrDefault(fallback)
private fun JsonObject.strings(name: String): List<String> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
