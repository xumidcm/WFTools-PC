package com.ailife.wftools.agent.provider

import java.io.IOException

class AgentProviderException(
    val displayMessage: String,
    cause: Throwable? = null,
) : IOException(displayMessage, cause)

object AgentErrorSanitizer {
    fun forHttp(
        providerName: String,
        statusCode: Int,
        body: String?,
        secrets: Collection<String> = emptyList(),
    ): String {
        val safeProvider = providerName.trim().take(80).ifBlank { "AI 服务商" }
        val raw = body.orEmpty()
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val redacted = redact(raw, secrets).take(240)
        val detail = redacted.ifBlank { "服务端未提供详细信息" }
        return "$safeProvider 请求失败（HTTP $statusCode）：$detail"
    }

    fun forThrowable(providerName: String, error: Throwable, secrets: Collection<String> = emptyList()): String {
        val message = redact(error.message.orEmpty(), secrets).replace(Regex("\\s+"), " ").trim()
        return "${providerName.trim().take(80).ifBlank { "AI 服务商" }} 请求失败：${message.take(240).ifBlank { "网络连接失败" }}"
    }

    private fun redact(value: String, secrets: Collection<String>): String {
        var output = value
        secrets.filter { it.isNotBlank() }.forEach { output = output.replace(it, "[已隐藏]") }
        output = output.replace(
            Regex("(?i)(api[-_ ]?key|authorization|x-api-key|access[-_ ]?token)\\s*[:=]\\s*[^,; ]+"),
            "$1=[已隐藏]",
        )
        return output
    }
}


