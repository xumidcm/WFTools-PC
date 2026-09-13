package com.ailife.wftools.agent.storage

import com.ailife.wftools.agent.model.*
import com.ailife.wftools.agent.runtime.AgentConversationTitlePrompt
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.Comparator
import com.ailife.wftools.agent.provider.AgentImageInput
import com.ailife.wftools.agent.provider.AgentJsonWriter

class AgentConversationRepository(private val root: Path) {
    private val lock = Any()
    private fun scopeDir(scope: String): Path = root.resolve("conversations").resolve(hash(scope))
    private fun conversationFile(scope: String) = scopeDir(scope).resolve("index.json")
    private fun messagesFile(id: String, scope: String) = scopeDir(scope).resolve("$id.messages.json")

    @Synchronized fun createConversation(watchScope: String, now: Long = System.currentTimeMillis()): AgentConversation {
        val value = AgentConversation.new(watchScope, now)
        writeConversations(watchScope, listConversations(watchScope) + value)
        return value
    }
    @Synchronized fun listConversations(watchScope: String): List<AgentConversation> = readList(conversationFile(watchScope)).sortedByDescending { it.updatedAt }
    @Synchronized fun getConversation(id: String): AgentConversation? = allConversations().firstOrNull { it.id == id }
    @Synchronized fun listMessages(conversationId: String): List<AgentMessage> {
        val conversation = getConversation(conversationId) ?: return emptyList()
        val file = messagesFile(conversationId, conversation.watchScope)
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching { AgentStorageJson.parseMessages(Files.readString(file)) }.getOrDefault(emptyList())
    }
    @Synchronized fun appendMessage(conversationId: String, message: AgentMessage): AgentMessage {
        val conversation = requireNotNull(getConversation(conversationId))
        require(message.conversationId == conversationId)
        val messages = listMessages(conversationId).filterNot { it.id == message.id } + message
        writeJson(messagesFile(conversationId, conversation.watchScope), messages)
        val title = if (message.role == AgentMessageRole.USER) conversation.withFirstUserMessage(message.content) else conversation
        writeConversations(conversation.watchScope, listConversations(conversation.watchScope).filterNot { it.id == conversation.id } + title.copy(updatedAt = maxOf(title.updatedAt, message.createdAt)))
        return message
    }
    @Synchronized fun deleteMessagesAfter(conversationId: String, messageId: String) {
        val conversation = getConversation(conversationId) ?: return
        val all = listMessages(conversationId)
        val index = all.indexOfFirst { it.id == messageId }
        if (index < 0) return
        writeJson(messagesFile(conversationId, conversation.watchScope), all.take(index + 1))
    }
    @Synchronized fun renameConversation(id: String, title: String): AgentConversation {
        val current = requireNotNull(getConversation(id)); require(title.isNotBlank())
        val updated = current.copy(title = title.trim().replace(Regex("\\s+"), " ").take(120), titleFinalized = true, updatedAt = System.currentTimeMillis())
        writeConversations(current.watchScope, listConversations(current.watchScope).filterNot { it.id == id } + updated); return updated
    }

    /** Persists a generated title as provisional so later turns may refine it. */
    @Synchronized fun applyGeneratedTitle(id: String, rawTitle: String): AgentConversation? {
        val current = getConversation(id) ?: return null
        if (current.titleFinalized) return current
        val cleaned = AgentConversationTitlePrompt.clean(rawTitle) ?: return current
        val updated = current.copy(title = cleaned, titleFinalized = false)
        writeConversations(current.watchScope, listConversations(current.watchScope).filterNot { it.id == id } + updated)
        return updated
    }

    /** Mirrors Flue: a manually finalized title becomes eligible for refinement after a later turn. */
    @Synchronized fun markGeneratedTitlePending(id: String): AgentConversation? {
        val current = getConversation(id) ?: return null
        if (!current.titleFinalized) return current
        val updated = current.copy(titleFinalized = false)
        writeConversations(current.watchScope, listConversations(current.watchScope).filterNot { it.id == id } + updated)
        return updated
    }
    @Synchronized fun deleteConversation(id: String) {
        val current = getConversation(id) ?: return
        writeConversations(current.watchScope, listConversations(current.watchScope).filterNot { it.id == id })
        Files.deleteIfExists(messagesFile(id, current.watchScope))
        val attachments = attachmentDir(current)
        if (Files.isDirectory(attachments)) Files.walk(attachments).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
    @Synchronized fun markEnded(id: String, now: Long = System.currentTimeMillis()) {
        val current = getConversation(id) ?: return
        writeConversations(current.watchScope, listConversations(current.watchScope).filterNot { it.id == id } + current.copy(endedAt = now, updatedAt = now))
    }
    @Synchronized fun addImageAttachment(conversationId: String, source: Path): AgentAttachment {
        AgentImageInput.fromFile(source)
        return addFileAttachment(conversationId, source, requireImage = true)
    }

    /** Copies any user-selected file into the conversation attachment store.
     * Image validation remains opt-in so scripts, archives and other workspace
     * inputs can be attached without pretending they are images. */
    @Synchronized fun addFileAttachment(conversationId: String, source: Path): AgentAttachment =
        addFileAttachment(conversationId, source, requireImage = false)

    private fun addFileAttachment(conversationId: String, source: Path, requireImage: Boolean): AgentAttachment {
        val conversation = requireNotNull(getConversation(conversationId))
        require(Files.isRegularFile(source) && !Files.isSymbolicLink(source)) { "附件不存在或不是普通文件" }
        if (requireImage) AgentImageInput.fromFile(source)
        require(Files.size(source) <= MAX_ATTACHMENT_BYTES) { "附件超过 100 MB" }
        val fileName = source.fileName.toString().ifBlank { "附件" }
        val extension = fileName.substringAfterLast('.', "bin").lowercase().takeIf { it.length in 1..12 } ?: "bin"
        val id = UUID.randomUUID().toString()
        val directory = attachmentDir(conversation)
        Files.createDirectories(directory)
        val target = directory.resolve("$id.$extension")
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        val digest = hash(Files.readAllBytes(target))
        val mime = Files.probeContentType(source)
            ?: if (requireImage) "image/${if (extension == "jpg") "jpeg" else extension}" else "application/octet-stream"
        return AgentAttachment(id, fileName, mime, Files.size(target), digest, target.toString())
    }
    @Synchronized fun addImageBytes(conversationId: String, displayName: String, bytes: ByteArray): AgentAttachment {
        val conversation = requireNotNull(getConversation(conversationId))
        val image = AgentImageInput.fromBytes(bytes)
        val normalizedBytes = java.util.Base64.getDecoder().decode(image.base64)
        val id = UUID.randomUUID().toString()
        val directory = attachmentDir(conversation)
        Files.createDirectories(directory)
        val target = directory.resolve("$id.png")
        Files.write(target, normalizedBytes)
        return AgentAttachment(id, displayName, image.mimeType, normalizedBytes.size.toLong(), hash(normalizedBytes), target.toString())
    }
    private fun allConversations(): List<AgentConversation> {
        val root = root.resolve("conversations"); if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream -> stream.filter(Files::isDirectory).flatMap { dir -> Files.list(dir).filter { it.fileName.toString() == "index.json" }.flatMap { file -> readListStream(file).stream() } }.toList() }
    }
    private fun attachmentDir(conversation: AgentConversation): Path = scopeDir(conversation.watchScope).resolve("attachments").resolve(conversation.id)
    private fun readListStream(file: Path): List<AgentConversation> = readList(file)
    private fun readList(file: Path): List<AgentConversation> = if (!Files.isRegularFile(file)) emptyList() else runCatching { AgentStorageJson.parseConversations(Files.readString(file)) }.getOrDefault(emptyList())
    private fun writeConversations(scope: String, values: List<AgentConversation>) = writeJson(conversationFile(scope), values)
    private fun writeJson(file: Path, value: Any) { Files.createDirectories(file.parent); val temp = Files.createTempFile(file.parent, ".agent-", ".tmp"); val json = when (value) { is List<*> -> if (value.firstOrNull() is AgentConversation || value.isEmpty()) AgentStorageJson.conversations(value.filterIsInstance<AgentConversation>()) else AgentStorageJson.messages(value.filterIsInstance<AgentMessage>()); else -> AgentJsonWriter.encode(value) }; Files.writeString(temp, json); Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
    }
}
