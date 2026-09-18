package com.ailife.wftools.pc.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Wire format from WmProjectManager at upstream 201cde43: Base64 character swaps
 * for XML, repeating SWHn- XOR for images only. Unlike the upstream export filter,
 * this desktop implementation retains every resource not explicitly deleted.
 */
internal object ProtectedWatchArchive {
    internal data class Expected(val xml: String, val hashes: Map<String, String>, val imageHashes: Map<String, String>)

    /** 解密后的受保护归档：真实 XML 与还原成明文的图片。 */
    internal data class Decrypted(val xml: String, val images: Map<String, ByteArray>)

    private const val PXML_ENTRY = "watch.pxml"
    private const val XML_ENTRY = "watch.xml"
    private val key = byteArrayOf(83, 87, 72, 110, 45)
    private fun encryptedName(path: String) = path.substringBeforeLast('.') + if (AssetKinds.extension(path) == "png") ".ppng" else ".pjpg"
    private fun isPlainImage(name: String) = name.startsWith("images/", true) && AssetKinds.extension(name) in setOf("png", "jpg", "jpeg")

    /** 加密孪生图片（.ppng/.pjpg）。只有受保护导出才会产生，是受保护归档的特征条目。 */
    internal fun isEncryptedTwin(name: String) = AssetKinds.extension(name) in setOf("ppng", "pjpg")

    /** 只可能出现在受保护归档里的条目；转换出的可编辑文档不应把它们写回新归档。 */
    internal fun isProtectedOnlyEntry(name: String) = name.equals(PXML_ENTRY, true) || isEncryptedTwin(name)

    /** 加密孪生名还原成明文名，与 [encryptedName] 互逆（.jpeg 也走 .pjpg，收回成 .jpg）。 */
    internal fun plainName(name: String): String = when (AssetKinds.extension(name)) {
        "ppng" -> name.substringBeforeLast('.') + ".png"
        "pjpg" -> name.substringBeforeLast('.') + ".jpg"
        else -> name
    }

    /**
     * 解密受保护归档。
     *
     * 返回真实 XML（图片引用已还原为 .png/.jpg）与逐字节还原的图片。无法解密时返回 null，
     * 调用方必须回退到"此表盘受保护"的提示——绝不把解不开的内容当成可编辑文档交出去。
     */
    fun decrypt(archive: Path): Decrypted? = runCatching {
        ZipFile(archive.toFile()).use { zip -> decryptEntries(zip) }
    }.getOrNull()

    /** 归档是否带受保护标记（存在 watch.pxml）。 */
    fun hasProtection(archive: Path): Boolean = runCatching {
        ZipFile(archive.toFile()).use { zip -> zip.entries().asSequence().any { it.name.equals(PXML_ENTRY, true) } }
    }.getOrDefault(false)

    /**
     * 把 [base] 就地改写成等价的**明文**归档：真实 XML 作为 watch.xml，图片还原成明文，
     * 加密专用条目（watch.pxml 与 .ppng/.pjpg）不再保留，其余条目逐字节复制。
     *
     * 这样工作副本的基准归档与普通表盘完全一致，解包与保存都无需再为受保护格式做特例。
     */
    fun replaceWithPlain(base: Path, decrypted: Decrypted) {
        val temporary = Files.createTempFile(base.toAbsolutePath().normalize().parent, ".wftools-plain-", ".zip.tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { output ->
                ZipFile(base.toFile()).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    zip.comment?.let(output::setComment)
                    val written = hashSetOf<String>()
                    fun emit(name: String, bytes: ByteArray, original: ZipEntry? = null) {
                        WatchDocumentStore.validateArchiveName(name)
                        if (!written.add(name.trimEnd('/').lowercase(Locale.ROOT))) fail("解密后资源名称冲突：$name")
                        val entry = ZipEntry(name).apply {
                            if (original != null) { time = original.time; comment = original.comment; extra = original.extra }
                        }
                        output.putNextEntry(entry); output.write(bytes); output.closeEntry()
                    }
                    // 真实 XML 替换掉占位 watch.xml。
                    emit(XML_ENTRY, decrypted.xml.toByteArray(Charsets.UTF_8),
                        entries.firstOrNull { it.name.equals(XML_ENTRY, true) })
                    decrypted.images.forEach { (name, bytes) -> emit(name, bytes) }
                    entries.forEach { entry ->
                        if (entry.isDirectory) return@forEach
                        if (isProtectedOnlyEntry(entry.name) || entry.name.equals(XML_ENTRY, true)) return@forEach
                        if (entry.name.lowercase(Locale.ROOT) in written) return@forEach
                        emit(entry.name, zip.getInputStream(entry).use { it.readBytes() }, entry)
                    }
                }
            }
            Files.move(temporary, base, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun decryptEntries(zip: ZipFile): Decrypted? {
        val entries = zip.entries().asSequence().toList()
        val pxml = entries.firstOrNull { it.name.equals(PXML_ENTRY, true) } ?: return null
        val raw = zip.getInputStream(pxml).use { it.readBytes().toString(Charsets.UTF_8) }
        // 导出时图片引用被改写成加密孪生名，这里还原回明文名，与解出的图片文件名保持一致。
        val xml = decodeXml(raw).replace(".ppng", ".png", true).replace(".pjpg", ".jpg", true)
        // 密钥不对会得到非 Base64 或非 XML 的内容；这里顺带验证文档结构，避免放行垃圾数据。
        WatchXml.validate(WatchXml.parse(xml))
        val images = LinkedHashMap<String, ByteArray>()
        entries.filter { !it.isDirectory && isEncryptedTwin(it.name) }.forEach { entry ->
            images[plainName(entry.name)] = zip.getInputStream(entry).use { xorDecrypt(it.readBytes()) }
        }
        return Decrypted(xml, images)
    }

    private fun xorDecrypt(input: ByteArray): ByteArray =
        ByteArray(input.size) { index -> (input[index].toInt() xor key[index % key.size].toInt()).toByte() }

    private fun encodeXml(xml: String): String = Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8))
        .replace("L", "#").replace("4", "L").replace("#", "4")
        .replace("D", "#").replace("g", "D").replace("#", "g")
    private fun decodeXml(text: String): String = Base64.getDecoder().decode(text
        .replace("g", "#").replace("D", "g").replace("#", "D")
        .replace("4", "#").replace("L", "4").replace("#", "L")).toString(Charsets.UTF_8)

    fun write(plain: Path, target: Path, xml: String, derived: Map<String, ByteArray>, scriptWasCleared: Boolean = false): Expected {
        var protectedXml: String
        val document = WatchXml.parse(xml)
        val root = document.documentElement
        val placeholder = WatchXml.parse("<Watch/>")
        val shell = placeholder.documentElement
        for (attr in listOf("name", "description", "author")) shell.setAttribute(attr, root.getAttribute(attr))
        for (attr in listOf("width", "height")) shell.setAttribute(attr, root.getAttribute(attr).ifBlank { "512" })
        shell.setAttribute("protection", "y")
        val hashes = LinkedHashMap<String, String>()
        val imageHashes = LinkedHashMap<String, String>()
        val seen = hashSetOf<String>()
        ZipFile(plain.toFile()).use { input ->
            val entries = input.entries().asSequence().toList()
            val mappings = entries.filter { !it.isDirectory && isPlainImage(it.name) }.associate { it.name.lowercase(Locale.ROOT) to encryptedName(it.name) }
            rewriteImageReferences(document.documentElement, mappings)
            protectedXml = WatchXml.serialize(document)
            val scriptEntry = entries.firstOrNull { it.name.equals("scripts/script.txt", true) }
            val script = scriptEntry?.let { input.getInputStream(it).use { stream -> stream.readBytes() } }
                ?: root.getAttribute("global_script").toByteArray(Charsets.UTF_8)
            ZipOutputStream(Files.newOutputStream(target)).use { output ->
                input.comment?.let(output::setComment)
                fun emit(name: String, original: ZipEntry? = null, encrypted: Boolean = false, source: () -> InputStream) {
                    WatchDocumentStore.validateArchiveName(name)
                    if (!seen.add(name.trimEnd('/').lowercase(Locale.ROOT))) fail("受保护资源名称冲突：$name。请先重命名同名 JPG/JPEG 或已有加密副本。")
                    val entry = ZipEntry(name).apply {
                        if (original != null) { time = original.time; comment = original.comment; extra = original.extra }
                    }
                    output.putNextEntry(entry)
                    val result = source().use { transfer(it, output, encrypted) }
                    hashes[name] = result.first
                    if (encrypted) imageHashes[name] = result.second
                    output.closeEntry()
                }
                emit("watch.xml") { ByteArrayInputStream(WatchXml.serialize(placeholder).toByteArray(Charsets.UTF_8)) }
                emit("watch.pxml") { ByteArrayInputStream(encodeXml(protectedXml).toByteArray(Charsets.UTF_8)) }
                entries.forEach { entry ->
                    val lower = entry.name.lowercase(Locale.ROOT)
                    if (lower == "watch.xml" || lower == "watch.pxml" || lower == "scripts/script.txt") return@forEach
                    val encrypted = !entry.isDirectory && isPlainImage(entry.name)
                    val name = if (encrypted) mappings.getValue(lower) else entry.name
                    emit(name, entry, encrypted) {
                        derived[lower]?.let(::ByteArrayInputStream) ?: input.getInputStream(entry)
                    }
                }
                if (!scriptWasCleared && (scriptEntry != null || script.isNotEmpty()))
                    emit(scriptEntry?.name ?: "scripts/script.txt", scriptEntry) { ByteArrayInputStream(script) }
                derived.forEach { (name, bytes) ->
                    if (name !in seen) emit(name) { ByteArrayInputStream(bytes) }
                }
            }
        }
        return Expected(protectedXml, hashes, imageHashes)
    }

    private val imageTypes = setOf("image", "image_gif", "image_cond")
    private val resourceAttributes = setOf("path", "image", "texture", "src", "resource", "file", "mask", "atlas")
    private val externalUri = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val stringLiteral = Regex("'([^'\\\\]|\\\\.)*'|\"([^\"\\\\]|\\\\.)*\"")

    /** Visit supported resource-bearing nodes only; unknown subtrees stay opaque. */
    private fun imageAttributes(root: Element, action: (org.w3c.dom.Node, Boolean) -> Unit) {
        fun layer(element: Element, imageLayer: Boolean) {
            for (i in 0 until element.attributes.length) {
                val attribute = element.attributes.item(i)
                if (attribute.nodeName in resourceAttributes && (attribute.nodeName != "path" || imageLayer)) action(attribute, imageLayer)
            }
            element.elements().filter { it.tagName in setOf("Ambient", "Segment") }.forEach { layer(it, imageLayer) }
        }
        WatchXml.layers(root.ownerDocument).forEach { layer(it, it.getAttribute("type") in imageTypes) }
    }

    private fun rewriteImageReferences(root: Element, mappings: Map<String, String>) {
        fun mapped(value: String): String? {
            val normalized = value.replace('\\', '/')
            if (externalUri.containsMatchIn(normalized) || normalized.startsWith("fonts_bm/", true)) return null
            val result = mappings[normalized.lowercase(Locale.ROOT)] ?: mappings["images/${normalized.lowercase(Locale.ROOT)}"] ?: return null
            return if (normalized.startsWith("images/", true)) result else result.substringAfter('/')
        }
        val extensions = mappings.keys.map { AssetKinds.extension(it) }.toSet()
        imageAttributes(root) { attribute, imageLayer ->
            attribute.nodeValue = attribute.nodeValue.split('`').joinToString("`") { raw ->
                val value = raw.trim()
                when {
                    externalUri.containsMatchIn(value) || value.startsWith("fonts_bm/", true) -> raw
                    mapped(value) != null -> raw.replaceRange(raw.indexOf(value), raw.indexOf(value) + value.length, mapped(value)!!)
                    value.any { it in "{}'\"" } || value.contains("..") && !value.startsWith("../") -> {
                        stringLiteral.replace(raw) { match ->
                            val literal = match.value.substring(1, match.value.length - 1)
                            val replacement = mapped(literal) ?: if (externalUri.containsMatchIn(literal) || literal.startsWith("fonts_bm/", true)) literal
                                else if (AssetKinds.extension(literal) in extensions) encryptedName(literal) else literal
                            match.value.first() + replacement + match.value.last()
                        }
                    }
                    imageLayer && attribute.nodeName == "path" && AssetKinds.extension(value) in setOf("png", "jpg", "jpeg") -> fail("图片引用不存在：$value。请先修复路径再导出。")
                    else -> raw
                }
            }
        }
    }

    /** Called after generic ZIP CRC, size and path checks, before the atomic commit. */
    fun validate(path: Path, expected: Expected) {
        try {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.map { it.name }.toSet() != expected.hashes.keys) fail("受保护归档条目不完整。")
                entries.forEach { entry ->
                    if (!entry.isDirectory && isPlainImage(entry.name)) fail("受保护归档仍包含明文图片：${entry.name}")
                    val result = zip.getInputStream(entry).use { transfer(it, OutputStream.nullOutputStream(), entry.name in expected.imageHashes) }
                    // transfer hashes its output and input. On validation XOR recovers
                    // the original image, while the input hash verifies archived bytes.
                    if (result.second != expected.hashes[entry.name] || (entry.name in expected.imageHashes && result.first != expected.imageHashes[entry.name]))
                        fail("受保护资源校验失败：${entry.name}")
                }
                val pxml = zip.getEntry("watch.pxml") ?: fail("缺少 watch.pxml。")
                val decoded = decodeXml(zip.getInputStream(pxml).use { it.readBytes().toString(Charsets.UTF_8) })
                if (decoded != expected.xml) fail("受保护 XML 解码后与当前文档不一致。")
                val document = WatchXml.parse(decoded); WatchXml.validate(document)
                val placeholder = WatchXml.parse(zip.getInputStream(zip.getEntry("watch.xml")).use { it.readBytes().toString(Charsets.UTF_8) })
                if (placeholder.documentElement.getAttribute("protection") != "y" || WatchXml.layers(placeholder).isNotEmpty()) fail("受保护占位 XML 无效。")
                validateImageReferences(document.documentElement, expected.hashes.keys)
            }
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.SaveFailed("受保护归档验证失败：${e.message}", e) }
    }

    private fun validateImageReferences(root: Element, entries: Set<String>) {
        val names = entries.map { it.lowercase(Locale.ROOT) }.toSet()
        imageAttributes(root) { attr, imageLayer ->
                attr.nodeValue.split('`').forEach { raw ->
                    val value = raw.trim().replace('\\', '/')
                    if (AssetKinds.extension(value) !in setOf("ppng", "pjpg")) return@forEach
                    // WatchMaker expressions are preserved, never evaluated at export.
                    if (value.any { it in "{}'\"" } || value.contains("..") && !value.startsWith("../")) return@forEach
                    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)) return@forEach
                    val full = if (imageLayer && !value.startsWith("images/", true)) "images/$value" else value
                    WatchDocumentStore.validateArchiveName(full)
                    if (full.lowercase(Locale.ROOT) !in names && "images/${value.lowercase(Locale.ROOT)}" !in names)
                        fail("受保护图片引用不存在：$value。请先修复路径再导出。")
                }
        }
    }

    private fun transfer(input: InputStream, output: OutputStream, encrypted: Boolean): Pair<String, String> {
        val before = MessageDigest.getInstance("SHA-256"); val after = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024); var position = 0L
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            before.update(buffer, 0, count)
            if (encrypted) for (index in 0 until count) buffer[index] = (buffer[index].toInt() xor key[((position + index) % key.size).toInt()].toInt()).toByte()
            after.update(buffer, 0, count); output.write(buffer, 0, count); position += count
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        return hex(after.digest()) to hex(before.digest())
    }
    private fun fail(message: String): Nothing = throw WatchDocumentException.SaveFailed(message)
}
