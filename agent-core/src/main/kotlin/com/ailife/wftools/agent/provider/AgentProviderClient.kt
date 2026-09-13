package com.ailife.wftools.agent.provider

import com.ailife.wftools.agent.model.AiModelDescriptor
import com.ailife.wftools.agent.model.AiProviderProfile
import com.ailife.wftools.agent.model.AiProtocol
import com.ailife.wftools.agent.storage.AgentSecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AgentHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class AgentHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface AgentHttpTransport {
    suspend fun execute(request: AgentHttpRequest, onChunk: (String) -> Unit): AgentHttpResponse
}

/**
 * A small cancellable transport. The worker owns the blocking URLConnection and cancellation
 * disconnects it, which also unblocks a read waiting on a long-lived SSE response.
 */
class UrlConnectionAgentHttpTransport : AgentHttpTransport {
    override suspend fun execute(request: AgentHttpRequest, onChunk: (String) -> Unit): AgentHttpResponse =
        suspendCancellableCoroutine { continuation ->
            var connection: HttpURLConnection? = null
            val worker = Thread {
                try {
                    val opened = (URL(request.url).openConnection() as HttpURLConnection).apply {
                        requestMethod = request.method
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        doInput = true
                        if (request.body != null) doOutput = true
                        request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    }
                    connection = opened
                    request.body?.let { body ->
                        opened.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val status = opened.responseCode
                    val stream = if (status in 200..299) opened.inputStream else opened.errorStream
                    val responseBody = buildString {
                        if (stream != null) {
                            val charset = charsetOf(opened.contentType)
                            stream.bufferedReader(charset).useLines { lines ->
                                lines.forEach { line ->
                                    append(line).append('\n')
                                    onChunk(line)
                                }
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(AgentHttpResponse(status, responseBody))
                    opened.disconnect()
                } catch (cancelled: InterruptedException) {
                    if (continuation.isActive) continuation.resumeWithException(CancellationException("请求已取消", cancelled))
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }.apply {
                name = "wftools-agent-provider"
                isDaemon = true
            }
            continuation.invokeOnCancellation {
                connection?.disconnect()
                worker.interrupt()
            }
            worker.start()
        }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.substringAfter("charset=", "")?.trim()?.trim(';')
        return name?.takeIf { it.isNotBlank() }?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charsets.UTF_8
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
    }
}

data class AgentCompletion(
    val text: String,
    val events: List<AgentStreamEvent>,
    val nativePayload: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

class AgentProviderClient(
    private val transport: AgentHttpTransport = UrlConnectionAgentHttpTransport(),
) {
    suspend fun complete(
        profile: AiProviderProfile,
        secrets: AgentSecretStore,
        request: AgentProviderRequest,
        extraHeaders: Map<String, String> = emptyMap(),
        onEvent: (AgentStreamEvent) -> Unit = {},
    ): AgentCompletion = complete(
        profile = profile,
        apiKey = withContext(Dispatchers.IO) { secrets.read(profile.id) },
        messages = request.messages,
        stream = request.stream,
        tools = request.tools,
        maxOutputTokens = request.maxOutputTokens,
        extraHeaders = extraHeaders,
        onEvent = onEvent,
    )

    suspend fun send(
        profile: AiProviderProfile,
        apiKey: String?,
        request: AgentProviderRequest,
        extraHeaders: Map<String, String> = emptyMap(),
        onEvent: (AgentStreamEvent) -> Unit = {},
    ): AgentCompletion = complete(
        profile = profile,
        apiKey = apiKey,
        messages = request.messages,
        stream = request.stream,
        tools = request.tools,
        maxOutputTokens = request.maxOutputTokens,
        extraHeaders = extraHeaders,
        onEvent = onEvent,
    )

    suspend fun generate(
        profile: AiProviderProfile,
        apiKey: String?,
        messages: List<AgentRequestMessage>,
        stream: Boolean = true,
        tools: List<AgentToolDefinition> = emptyList(),
        maxOutputTokens: Int? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        onEvent: (AgentStreamEvent) -> Unit = {},
    ): AgentCompletion = complete(
        profile,
        apiKey,
        messages,
        stream,
        tools,
        maxOutputTokens,
        extraHeaders,
        onEvent,
    )

    suspend fun complete(
        profile: AiProviderProfile,
        apiKey: String?,
        messages: List<AgentRequestMessage>,
        stream: Boolean = true,
        tools: List<AgentToolDefinition> = emptyList(),
        maxOutputTokens: Int? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        onEvent: (AgentStreamEvent) -> Unit = {},
    ): AgentCompletion {
        if (profile.modelId.isNullOrBlank()) throw AgentProviderException("请先为 ${profile.name.ifBlank { "AI 服务商" }} 选择模型")
        val endpoint = AgentEndpointResolver.generation(profile.baseUrl, profile.protocol)
        val body = AgentRequestBuilder.build(profile, messages, stream, tools, maxOutputTokens)
        val request = AgentHttpRequest(
            method = "POST",
            url = endpoint,
            headers = mergeHeaders(authHeaders(profile, apiKey), extraHeaders),
            body = body,
        )
        val decoder = AgentStreamParser.SseDecoder(profile.protocol)
        var delivered = 0
        return try {
            val response = transport.execute(request) { chunk ->
                if (!stream) return@execute
                chunk.lineSequence().forEach { line ->
                    decoder.acceptLine(line)
                    val all = decoder.snapshot()
                    while (delivered < all.size) onEvent(all[delivered++])
                }
            }
            if (response.statusCode !in 200..299) {
                throw AgentProviderException(
                    AgentErrorSanitizer.forHttp(profile.name, response.statusCode, response.body, listOfNotNull(apiKey)),
                )
            }
            if (!stream) {
                val parsed = AgentStreamParser.parseResponse(profile.protocol, response.body)
                parsed.events.forEach(onEvent)
                return parsed.toCompletion()
            }
            val allEvents = decoder.finish()
            while (delivered < allEvents.size) onEvent(allEvents[delivered++])
            if (allEvents.isEmpty() && response.body.isNotBlank()) {
                val parsed = AgentStreamParser.parseResponse(profile.protocol, response.body)
                parsed.events.forEach(onEvent)
                return parsed.toCompletion()
            }
            if (allEvents.none { it is AgentStreamEvent.TextDelta || it is AgentStreamEvent.ToolCallDelta || it is AgentStreamEvent.Completed }) {
                throw AgentProviderException("${profile.name.ifBlank { "AI 服务商" }} 返回了空响应")
            }
            val text = allEvents.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.text }
            val usage = allEvents.filterIsInstance<AgentStreamEvent.Usage>().lastOrNull()
            AgentCompletion(
                text = text,
                events = allEvents,
                nativePayload = allEvents.filterIsInstance<AgentStreamEvent.Completed>().lastOrNull()?.nativePayload,
                inputTokens = usage?.input,
                outputTokens = usage?.output,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (provider: AgentProviderException) {
            throw provider
        } catch (error: Throwable) {
            throw AgentProviderException(AgentErrorSanitizer.forThrowable(profile.name, error, listOfNotNull(apiKey)), error)
        }
    }

    suspend fun listModels(
        profile: AiProviderProfile,
        apiKey: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): List<AiModelDescriptor> {
        val endpoint = AgentEndpointResolver.modelList(profile.baseUrl, profile.protocol, profile.modelListUrlOverride)
            ?: throw AgentProviderException("${profile.name.ifBlank { "AI 服务商" }} 的模型列表地址需要手动配置")
        return try {
            val response = transport.execute(
                AgentHttpRequest("GET", endpoint, mergeHeaders(authHeaders(profile, apiKey), extraHeaders)),
                {},
            )
            if (response.statusCode !in 200..299) {
                throw AgentProviderException(
                    AgentErrorSanitizer.forHttp(profile.name, response.statusCode, response.body, listOfNotNull(apiKey)),
                )
            }
            AgentModelCatalogParser.parse(response.body)
        } catch (provider: AgentProviderException) {
            throw provider
        } catch (error: Throwable) {
            throw AgentProviderException(AgentErrorSanitizer.forThrowable(profile.name, error, listOfNotNull(apiKey)), error)
        }
    }

    suspend fun listModels(profile: AiProviderProfile, secrets: AgentSecretStore): List<AiModelDescriptor> =
        listModels(profile, withContext(Dispatchers.IO) { secrets.read(profile.id) })

    private fun authHeaders(profile: AiProviderProfile, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "text/event-stream, application/json")
        put("User-Agent", "Flue-Agent/${"0.1.5"}")
        if (!apiKey.isNullOrBlank()) {
            if (profile.protocol == AiProtocol.CLAUDE_MESSAGES) {
                put("x-api-key", apiKey)
                put("anthropic-version", "2023-06-01")
            } else {
                put("Authorization", "Bearer $apiKey")
            }
        } else if (profile.protocol == AiProtocol.CLAUDE_MESSAGES) {
            put("anthropic-version", "2023-06-01")
        }
        profile.userAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
        profile.customHeaderNames.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank() && name.lowercase() !in RESERVED_HEADERS) {
                put(name, value)
            }
        }
    }

    /** Merge caller headers without allowing auth/protocol framing to be replaced. */
    private fun mergeHeaders(base: Map<String, String>, extra: Map<String, String>): Map<String, String> {
        if (extra.isEmpty()) return base
        val result = LinkedHashMap(base)
        extra.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank() && name.lowercase() !in RESERVED_HEADERS) {
                result[name] = value
            }
        }
        return result
    }

    private companion object {
        val RESERVED_HEADERS = setOf(
            "authorization", "x-api-key", "content-type", "accept", "anthropic-version", "user-agent",
        )
    }

    private fun AgentParsedResponse.toCompletion() = AgentCompletion(
        text = text,
        events = events,
        nativePayload = nativePayload,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
    )
}


