package com.ailife.wftools.agent.provider

import com.ailife.wftools.agent.model.AiProtocol
import java.net.URI

/** Resolves documented provider paths while leaving unknown custom paths untouched. */
object AgentEndpointResolver {
    fun generation(baseUrl: String, protocol: AiProtocol): String {
        val normalized = normalize(baseUrl)
        val suffix = protocol.generationSuffix()
        val uri = parse(normalized) ?: return fallbackGeneration(normalized, suffix)
        val path = uri.path.orEmpty().trimEnd('/')
        val known = KNOWN_SUFFIXES.firstOrNull { path.endsWith(it) }
        if (known != null) return withPath(uri, path.removeSuffix(known) + suffix)
        if (path.endsWith("/v1")) return withPath(uri, path + suffix)
        if (path.isNotEmpty()) return normalized
        return withPath(uri, "/v1$suffix")
    }

    /** Returns null for an unknown custom path unless the caller supplied an override. */
    fun modelList(baseUrl: String, protocol: AiProtocol, override: String? = null): String? {
        override?.trim()?.takeIf { it.isNotEmpty() }?.let { return normalize(it) }
        val normalized = normalize(baseUrl)
        val uri = parse(normalized) ?: return fallbackModelList(normalized)
        val path = uri.path.orEmpty().trimEnd('/')
        KNOWN_SUFFIXES.firstOrNull { path.endsWith(it) }?.let {
            return withPath(uri, path.removeSuffix(it) + "/models")
        }
        if (path.endsWith("/v1")) return withPath(uri, path + "/models")
        if (path.isEmpty()) return withPath(uri, "/v1/models")
        return null
    }

    fun modelListUrl(baseUrl: String, protocol: AiProtocol, override: String? = null): String? =
        modelList(baseUrl, protocol, override)

    fun models(baseUrl: String, protocol: AiProtocol, override: String? = null): String? =
        modelList(baseUrl, protocol, override)

    private fun normalize(value: String): String = value.trim().trimEnd('/')

    private fun parse(value: String): URI? = runCatching { URI(value) }.getOrNull()

    private fun withPath(uri: URI, path: String): String = URI(
        uri.scheme,
        uri.rawAuthority,
        path,
        uri.rawQuery,
        uri.rawFragment,
    ).toASCIIString().trimEnd('/')

    private fun fallbackGeneration(value: String, suffix: String): String = when {
        KNOWN_SUFFIXES.any { value.endsWith(it) } -> value.substringBeforeLast('/').substringBeforeLast('/') + suffix
        value.endsWith("/v1") -> value + suffix
        value.substringAfter("//", "").substringAfter('/', "").isBlank() -> value + "/v1$suffix"
        else -> value
    }

    private fun fallbackModelList(value: String): String? = when {
        KNOWN_SUFFIXES.any { value.endsWith(it) } -> value.substringBeforeLast('/').substringBeforeLast('/') + "/models"
        value.endsWith("/v1") -> value + "/models"
        else -> null
    }

    private fun AiProtocol.generationSuffix(): String = when (this) {
        AiProtocol.CHAT_COMPLETIONS -> "/chat/completions"
        AiProtocol.RESPONSES -> "/responses"
        AiProtocol.CLAUDE_MESSAGES -> "/messages"
    }

    private val KNOWN_SUFFIXES = listOf("/chat/completions", "/responses", "/messages")
}


