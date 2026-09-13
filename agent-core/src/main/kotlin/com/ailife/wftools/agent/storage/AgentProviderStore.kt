package com.ailife.wftools.agent.storage

import com.ailife.wftools.agent.model.*
import java.nio.file.Files
import java.nio.file.Path

class AgentProviderStore(private val root: Path, private val secrets: AgentSecretStore = LocalEncryptedSecretStore(root)) {
    private val file get() = root.resolve("providers.json")
    @Synchronized fun profiles(): List<AiProviderProfile> = if (!Files.isRegularFile(file)) emptyList() else runCatching { AgentStorageJson.parseProviders(Files.readString(file)) }.getOrDefault(emptyList())
    @Synchronized fun save(profile: AiProviderProfile, apiKey: String? = null): AiProviderProfile {
        val values = profiles().filterNot { it.id == profile.id } + profile.copy(customHeaderNames = emptyMap())
        Files.createDirectories(file.parent); val temp = Files.createTempFile(file.parent, ".providers-", ".tmp"); Files.writeString(temp, AgentStorageJson.providers(values)); Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        if (apiKey != null) secrets.write(profile.id, apiKey)
        if (profile.customHeaderNames.isNotEmpty()) saveHeaders(profile.id, profile.customHeaderNames)
        return profile
    }
    fun active(preferredId: String? = null): AiProviderProfile? = profiles().firstOrNull { it.id == preferredId } ?: profiles().firstOrNull()
    fun apiKey(id: String): String? = secrets.read(id)
    fun headers(id: String): Map<String, String> = secrets.read("headers:$id")?.let { raw ->
        runCatching { AgentStorageJson.parseHeaders(raw) }.getOrDefault(emptyMap())
    } ?: emptyMap()
    fun saveHeaders(id: String, values: Map<String, String>) { secrets.write("headers:$id", AgentStorageJson.headers(values)) }
    fun imageApiKey(): String? = secrets.read("image:key")
    fun saveImageApiKey(value: String) { secrets.write("image:key", value) }
    fun searchApiKey(): String? = secrets.read("search:key")
    fun saveSearchApiKey(value: String) { secrets.write("search:key", value) }
    fun remove(id: String) { profiles().firstOrNull { it.id == id }?.let { profile -> saveProfiles(profiles().filterNot { it.id == profile.id }); secrets.remove(id); secrets.remove("headers:$id") } }
    private fun saveProfiles(values: List<AiProviderProfile>) { Files.createDirectories(file.parent); Files.writeString(file, AgentStorageJson.providers(values)) }
}
