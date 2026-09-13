package com.ailife.wftools.agent.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface AgentSecretStore {
    fun read(id: String): String?
    fun write(id: String, value: String)
    fun remove(id: String)
}

/** AES-GCM encrypted provider secrets stored below the application data directory. */
class LocalEncryptedSecretStore(private val root: Path) : AgentSecretStore {
    private val keyFile = root.resolve("agent-secrets.key")
    private val valuesFile = root.resolve("agent-secrets.bin")
    private val lock = Any()
    private val key: SecretKey by lazy { loadOrCreateKey() }

    override fun read(id: String): String? = synchronized(lock) { decryptAll()[id] }
    override fun write(id: String, value: String) = synchronized(lock) {
        require(id.isNotBlank())
        val next = decryptAll().toMutableMap().apply { put(id, value) }
        encryptAll(next)
    }
    override fun remove(id: String) = synchronized(lock) {
        val next = decryptAll().toMutableMap().apply { remove(id) }
        encryptAll(next)
    }

    private fun loadOrCreateKey(): SecretKey {
        Files.createDirectories(root)
        if (Files.isRegularFile(keyFile)) return SecretKeySpec(Files.readAllBytes(keyFile), "AES")
        val generator = KeyGenerator.getInstance("AES").apply { init(256) }
        return generator.generateKey().also { Files.write(keyFile, it.encoded, StandardOpenOption.CREATE_NEW) }
    }

    private fun decryptAll(): Map<String, String> {
        if (!Files.isRegularFile(valuesFile)) return emptyMap()
        return runCatching<Map<String, String>> {
            val bytes = Files.readAllBytes(valuesFile)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
                .lineSequence().mapNotNull { line -> line.indexOf('\t').takeIf { it > 0 }?.let { decode(line.substring(0, it)) to decode(line.substring(it + 1)) } }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encryptAll(values: Map<String, String>) {
        Files.createDirectories(root)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val body = values.entries.joinToString("\n") { "${encode(it.key)}\t${encode(it.value)}" }.toByteArray(Charsets.UTF_8)
        val temp = Files.createTempFile(root, "agent-secrets-", ".tmp")
        Files.write(temp, iv + cipher.doFinal(body))
        Files.move(temp, valuesFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
