package com.ailife.wftools.pc

import com.ailife.wftools.agent.model.AgentFeatureSettings
import com.ailife.wftools.agent.provider.AgentImageInput
import com.ailife.wftools.agent.provider.AgentJsonParser
import com.ailife.wftools.agent.provider.AgentJsonValue
import com.ailife.wftools.agent.provider.AgentJsonWriter
import com.ailife.wftools.agent.tools.AgentExternalTools
import com.ailife.wftools.agent.tools.WatchAgentImageResult
import com.ailife.wftools.agent.tools.WatchAgentToolException
import com.ailife.wftools.agent.workspace.WorkspaceManager
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

/** Optional OpenAI-style image endpoint and simple JSON search endpoint. Both are configured globally. */
class DesktopAgentExternalTools(
    private val manager: WorkspaceManager,
    private val watchScope: String,
    private val settings: AgentFeatureSettings,
    private val imageApiKey: String?,
    private val searchApiKey: String?,
) : AgentExternalTools {
    override val imageGenerationAvailable: Boolean get() = !settings.imageGenerationEndpoint.isNullOrBlank()
    override val webSearchAvailable: Boolean get() = !settings.searchEndpoint.isNullOrBlank()

    override fun generateImage(prompt: String): WatchAgentImageResult {
        require(prompt.isNotBlank()) { "图片描述不能为空" }
        val endpoint = settings.imageGenerationEndpoint?.takeIf(String::isNotBlank)
            ?: throw WatchAgentToolException("image_service_unavailable", "未配置图片生成接口")
        val body = AgentJsonWriter.objectValue(buildMap {
            put("prompt", prompt)
            put("size", "1024x1024")
            put("response_format", "b64_json")
            settings.imageModelId?.takeIf(String::isNotBlank)?.let { put("model", it) }
        })
        val raw = execute(endpoint, "POST", body, imageApiKey, 32 * 1024 * 1024)
        val root = AgentJsonParser.parse(raw) as? AgentJsonValue.Obj
            ?: throw WatchAgentToolException("invalid_image_response", "图片接口返回了无效 JSON")
        val first = root.array("data")?.items?.firstOrNull() as? AgentJsonValue.Obj
            ?: throw WatchAgentToolException("invalid_image_response", "图片接口没有返回图片")
        val bytes = first.string("b64_json")?.let { Base64.getDecoder().decode(it) }
            ?: first.string("url")?.let { executeBytes(it, "GET", null, null, 20 * 1024 * 1024) }
            ?: throw WatchAgentToolException("invalid_image_response", "图片接口没有返回 b64_json 或 URL")
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

    override fun searchWeb(query: String): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val endpoint = settings.searchEndpoint?.takeIf(String::isNotBlank)
            ?: throw WatchAgentToolException("search_service_unavailable", "未配置搜索接口")
        val url = if (endpoint.contains("{query}")) endpoint.replace("{query}", java.net.URLEncoder.encode(query, Charsets.UTF_8))
            else endpoint + (if (endpoint.contains('?')) "&" else "?") + "q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8)
        return execute(url, "GET", null, searchApiKey, 128 * 1024).take(128 * 1024)
    }

    private fun execute(url: String, method: String, body: String?, key: String?, maxBytes: Int): String =
        String(executeBytes(url, method, body, key, maxBytes), Charsets.UTF_8)

    private fun executeBytes(url: String, method: String, body: String?, key: String?, maxBytes: Int): ByteArray {
        val target = URI.create(url)
        require(target.scheme in setOf("https", "http") && target.host != null) { "接口地址必须是 HTTP(S) URL" }
        val connection = target.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "application/json")
            if (!key.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readNBytes(maxBytes + 1) } ?: byteArrayOf()
            if (bytes.size > maxBytes) throw WatchAgentToolException("response_too_large", "接口响应超过大小限制")
            if (status !in 200..299) throw WatchAgentToolException("external_http_error", "外部接口返回 HTTP $status")
            return bytes
        } finally { connection.disconnect() }
    }
}
