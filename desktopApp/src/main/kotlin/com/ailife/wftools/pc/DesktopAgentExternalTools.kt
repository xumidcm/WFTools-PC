package com.ailife.wftools.pc

import com.ailife.wftools.agent.model.AgentFeatureSettings
import com.ailife.wftools.agent.provider.AgentImageInput
import com.ailife.wftools.agent.provider.AgentJsonParser
import com.ailife.wftools.agent.provider.AgentJsonValue
import com.ailife.wftools.agent.provider.AgentJsonWriter
import com.ailife.wftools.agent.tools.AgentExternalTools
import com.ailife.wftools.agent.tools.WatchAgentImageEditRequest
import com.ailife.wftools.agent.tools.WatchAgentImageGenerationRequest
import com.ailife.wftools.agent.tools.WatchAgentImageResult
import com.ailife.wftools.agent.tools.WatchAgentToolException
import com.ailife.wftools.agent.workspace.WorkspaceManager
import com.ailife.wftools.agent.workspace.WorkspacePathPolicy
import com.ailife.wftools.pc.SearchDepth
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/** OpenAI-compatible image generation/editing plus web search and page extraction. */
class DesktopAgentExternalTools(
    private val manager: WorkspaceManager,
    private val watchScope: String,
    private val settings: AgentFeatureSettings,
    private val imageApiKey: String?,
    private val searchApiKey: String?,
    /** Root of the currently opened face, used to select an image from the editor. */
    private val documentRoot: Path? = null,
) : AgentExternalTools {
    override val imageGenerationAvailable: Boolean get() = !settings.imageGenerationEndpoint.isNullOrBlank()
    override val imageEditingAvailable: Boolean get() = imageGenerationAvailable
    override val webSearchAvailable: Boolean get() = !settings.searchEndpoint.isNullOrBlank()

    override fun generateImage(request: WatchAgentImageGenerationRequest): WatchAgentImageResult {
        require(request.prompt.isNotBlank()) { "图片描述不能为空" }
        val body = AgentJsonWriter.objectValue(buildMap {
            put("prompt", request.prompt)
            request.model?.let { put("model", it) } ?: settings.imageModelId?.takeIf(String::isNotBlank)?.let { put("model", it) }
            request.size?.let { put("size", it) }
            request.quality?.let { put("quality", it) }
            request.background?.let { put("background", it) }
            request.outputFormat?.let { put("output_format", it) }
            request.outputCompression?.let { put("output_compression", it) }
            request.moderation?.let { put("moderation", it) }
        })
        return saveGenerated(parseImageResponse(execute(imageEndpoint("generations"), "POST", body, imageApiKey, 32 * 1024 * 1024, "application/json")))
    }

    override fun editImage(request: WatchAgentImageEditRequest): WatchAgentImageResult {
        require(request.prompt.isNotBlank()) { "图片修改描述不能为空" }
        val source = resolveImagePath(request.path)
        val image = runCatching { AgentImageInput.fromFile(source) }
            .getOrElse { throw WatchAgentToolException("invalid_image", "无法读取待修改图片：${it.message}") }
        val mask = request.maskPath?.let { path ->
            val maskPath = resolveImagePath(path)
            runCatching { AgentImageInput.fromFile(maskPath) }
                .getOrElse { throw WatchAgentToolException("invalid_mask", "无法读取蒙版图片：${it.message}") }
        }
        val fields = buildMap<String, String> {
            put("prompt", request.prompt)
            request.model?.let { put("model", it) } ?: settings.imageModelId?.takeIf(String::isNotBlank)?.let { put("model", it) }
            request.size?.let { put("size", it) }
            request.quality?.let { put("quality", it) }
            request.background?.let { put("background", it) }
            request.outputFormat?.let { put("output_format", it) }
            request.outputCompression?.let { put("output_compression", it.toString()) }
            request.inputFidelity?.let { put("input_fidelity", it) }
        }
        val response = executeMultipart(
            imageEndpoint("edits"), fields,
            listOfNotNull(
                MultipartPart("image", "source.png", "image/png", Base64.getDecoder().decode(image.base64)),
                mask?.let { MultipartPart("mask", "mask.png", "image/png", Base64.getDecoder().decode(it.base64)) },
            ), imageApiKey, 32 * 1024 * 1024,
        )
        return saveGenerated(parseImageResponse(response))
    }

    override fun searchWeb(query: String): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val endpoint = settings.searchEndpoint?.takeIf(String::isNotBlank)
            ?: throw WatchAgentToolException("search_service_unavailable", "未配置搜索接口")
        if (endpoint.contains("{query}")) {
            return execute(endpoint.replace("{query}", encode(query)), "GET", null, searchApiKey, 256 * 1024, "application/json, text/plain")
        }
        // Tavily-compatible APIs use POST /search when the setting contains the API root.
        val apiSearch = endpoint.trimEnd('/').let { if (it.endsWith("/search")) it else "$it/search" }
        val request = AgentJsonWriter.objectValue(mapOf(
            "query" to query,
            "search_depth" to searchDepthSetting(),
            "max_results" to searchResultCount(),
            "include_answer" to false, "include_images" to false,
        ))
        return runCatching { execute(apiSearch, "POST", request, searchApiKey, 256 * 1024, "application/json") }
            .getOrElse {
                val url = endpoint + (if (endpoint.contains('?')) "&" else "?") + "q=" + encode(query)
                execute(url, "GET", null, searchApiKey, 256 * 1024, "application/json, text/plain")
            }
    }

    /** Fetches a page as readable text. APIs with a {url} template are used directly; otherwise the URL is fetched. */
    override fun scrapePage(url: String): String {
        val target = URI.create(url)
        require(target.scheme in setOf("http", "https") && target.host != null) { "网页地址必须是 HTTP(S) URL" }
        require(target.userInfo == null) { "网页地址不能包含用户凭据" }
        val configured = settings.searchEndpoint?.takeIf(String::isNotBlank)
        if (configured?.contains("{url}") == true) {
            val raw = execute(configured.replace("{url}", encode(url)), "GET", null, searchApiKey, 1024 * 1024, "text/html, text/plain, application/json")
            return readablePage(url, raw).take(768 * 1024)
        }
        // Prefer the configured research API's page extraction (Tavily uses POST /extract),
        // then fall back to a bounded native GET when an API does not expose extraction.
        configured?.let { base ->
            val root = base.trimEnd('/').let { it.removeSuffix("/search").removeSuffix("/extract") }
            val extractBody = AgentJsonWriter.objectValue(mapOf("urls" to listOf(url)))
            val extracted = runCatching { execute("$root/extract", "POST", extractBody, searchApiKey, 1024 * 1024, "application/json") }.getOrNull()
            val content = extracted?.let { parseExtractedContent(it) }
            if (!content.isNullOrBlank()) return readablePage(url, content).take(768 * 1024)
        }
        val raw = execute(url, "GET", null, null, 1024 * 1024, "text/html, text/plain, application/json")
        return readablePage(url, raw).take(768 * 1024)
    }

    /** Tavily `max_results`; the API accepts 1..20. */
    private fun searchResultCount(): Int = SearchResultCount.clamp(settings.searchMaxResults)

    /** Tavily `search_depth`; unknown values fall back to `basic`. */
    private fun searchDepthSetting(): String = SearchDepth.parse(settings.searchDepth).wire

    private fun imageEndpoint(operation: String): String {
        val configured = settings.imageGenerationEndpoint?.trim()?.trimEnd('/')
            ?: throw WatchAgentToolException("image_service_unavailable", "未配置图片接口")
        if (configured.endsWith("/images/$operation")) return configured
        if (configured.endsWith("/images/generations") || configured.endsWith("/images/edits")) {
            return configured.substringBeforeLast('/').substringBeforeLast('/') + "/images/$operation"
        }
        if (configured.endsWith("/images")) return "$configured/$operation"
        return "$configured/images/$operation"
    }

    private fun parseImageResponse(raw: String): ByteArray {
        val root = AgentJsonParser.parse(raw) as? AgentJsonValue.Obj
            ?: throw WatchAgentToolException("invalid_image_response", "图片接口返回了无效 JSON")
        val first = root.array("data")?.items?.firstOrNull() as? AgentJsonValue.Obj
            ?: throw WatchAgentToolException("invalid_image_response", "图片接口没有返回图片")
        return first.string("b64_json")?.let { encoded -> runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { throw WatchAgentToolException("invalid_image_response", "图片 base64 无效") } }
            ?: first.string("url")?.let { imageUrl ->
                if (imageUrl.startsWith("data:image/")) Base64.getDecoder().decode(imageUrl.substringAfter("base64,"))
                else executeBytes(imageUrl, "GET", null, null, 20 * 1024 * 1024, "image/*")
            }
            ?: throw WatchAgentToolException("invalid_image_response", "图片接口没有返回 b64_json 或 URL")
    }

    private fun saveGenerated(bytes: ByteArray): WatchAgentImageResult {
        val image = AgentImageInput.fromBytes(bytes)
        val png = Base64.getDecoder().decode(image.base64)
        val workspaceId = manager.bindingFor(watchScope)?.workspaceId
            ?: throw WatchAgentToolException("workspace_required", "当前表盘尚未绑定工作区")
        val workspace = manager.get(workspaceId) ?: throw WatchAgentToolException("workspace_required", "工作区不存在")
        if (!workspace.quota.canAdd(png.size.toLong())) throw WatchAgentToolException("workspace_quota_exceeded", "生成图片超过工作区配额")
        val name = "agent-${UUID.randomUUID()}.png"
        val directory = workspace.paths.generated
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".agent-image-", ".tmp")
        try {
            Files.write(temporary, png)
            Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
        manager.refreshQuota(workspaceId)
        return WatchAgentImageResult("generated/$name", image)
    }

    private fun resolveImagePath(path: String): Path {
        val relative = WorkspacePathPolicy.sanitizeRelative(path)
        val top = relative.substringBefore('/')
        if (top in setOf("workspace", "generated", "previews", "home")) {
            val workspaceId = manager.bindingFor(watchScope)?.workspaceId
                ?: throw WatchAgentToolException("workspace_required", "请先选择工作区")
            val workspace = manager.get(workspaceId) ?: throw WatchAgentToolException("workspace_required", "工作区不存在")
            val file = WorkspacePathPolicy.resolveInside(workspace.paths.root, relative)
            require(Files.isRegularFile(file)) { "图片不存在：$path" }
            return file
        }
        val root = documentRoot?.toAbsolutePath()?.normalize()
            ?: throw WatchAgentToolException("face_path_unavailable", "当前表盘目录不可用，请先通过 list_workspace_files 选择图片")
        val file = WorkspacePathPolicy.resolveInside(root, relative)
        require(Files.isRegularFile(file)) { "表盘图片不存在：$path" }
        return file
    }

    private fun execute(url: String, method: String, body: String?, key: String?, maxBytes: Int, accept: String): String =
        String(executeBytes(url, method, body, key, maxBytes, accept), StandardCharsets.UTF_8)

    private fun executeBytes(url: String, method: String, body: String?, key: String?, maxBytes: Int, accept: String): ByteArray {
        val target = URI.create(url)
        require(target.scheme in setOf("https", "http") && target.host != null) { "接口地址必须是 HTTP(S) URL" }
        val connection = target.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "WF-Tools-Agent/1.0")
            if (!key.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readNBytes(maxBytes + 1) } ?: byteArrayOf()
            if (bytes.size > maxBytes) throw WatchAgentToolException("response_too_large", "接口响应超过大小限制")
            if (status !in 200..299) throw WatchAgentToolException("external_http_error", "外部接口返回 HTTP $status")
            return bytes
        } finally { connection.disconnect() }
    }

    private data class MultipartPart(val name: String, val filename: String, val contentType: String, val bytes: ByteArray)

    private fun executeMultipart(url: String, fields: Map<String, String>, files: List<MultipartPart>, key: String?, maxBytes: Int): String {
        val boundary = "----WFToolsAgent${UUID.randomUUID().toString().replace("-", "")}"
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (!key.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { output ->
                fun line(value: String) = output.write((value + "\r\n").toByteArray(StandardCharsets.UTF_8))
                fields.forEach { (name, value) ->
                    line("--$boundary"); line("Content-Disposition: form-data; name=\"$name\""); line(""); line(value)
                }
                files.forEach { file ->
                    line("--$boundary"); line("Content-Disposition: form-data; name=\"${file.name}\"; filename=\"${file.filename}\""); line("Content-Type: ${file.contentType}"); line("")
                    output.write(file.bytes); line("")
                }
                line("--$boundary--")
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readNBytes(maxBytes + 1) } ?: byteArrayOf()
            if (bytes.size > maxBytes) throw WatchAgentToolException("response_too_large", "接口响应超过大小限制")
            if (status !in 200..299) throw WatchAgentToolException("external_http_error", "外部接口返回 HTTP $status")
            return String(bytes, StandardCharsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun readablePage(url: String, body: String): String {
        val trimmed = body.trim()
        if (!trimmed.contains('<')) return "网页：$url\n\n$trimmed"
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(trimmed)?.groupValues?.getOrNull(1)?.let(::decodeHtml).orEmpty()
        val text = trimmed
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .let(::decodeHtml)
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return buildString {
            append("网页：").append(url)
            if (title.isNotBlank()) append("\n标题：").append(title)
            append("\n\n").append(text)
        }
    }

    private fun decodeHtml(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }

    private fun parseExtractedContent(raw: String): String? {
        val root = AgentJsonParser.parse(raw) as? AgentJsonValue.Obj ?: return null
        val first = root.array("results")?.items?.firstOrNull() as? AgentJsonValue.Obj ?: return null
        return first.string("raw_content") ?: first.string("content")
    }
}
