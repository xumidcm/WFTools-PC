package com.ailife.wftools.pc.core

import com.ailife.wftools2.domain.editor.watchmaker.BitmapFont
import com.google.gson.Gson
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/** Local durable clipboard payloads; the system clipboard contains XML plus a UUID, never large media. */
internal object WatchClipboard {
    private val gson = Gson()
    private data class Resource(val name: String, val hash: String, val size: Long)
    private data class Manifest(val version: Int, val id: String, val sourceDocumentId: String, val xmlHash: String, val resources: List<Resource>)
    internal data class Prepared(val document: Document, val assets: Map<String, String>)
    private data class Source(val name: String, val size: Long, val expectedHash: String?, val open: () -> InputStream)

    /** Reference checking is read-only; backup is staged only after every check passes. */
    fun prepareDeletion(session: DocumentSession, requested: String): Pair<String, String> = guarded("无法删除素材") {
        val path = requested.replace('\\', '/')
        WatchDocumentStore.validateArchiveName(path)
        if (path.equals("scripts/script.txt", true))
            fail("全局脚本由脚本编辑器管理，不能作为普通素材删除。")
        if (!path.contains('/') || path.substringBefore('/').lowercase(Locale.ROOT) !in WatchDocumentStore.MATERIAL_FOLDERS)
            fail("只能删除素材目录中的文件；watch.xml、脚本、预览和文档元数据禁止删除。")
        Inventory(session).use { inventory ->
            val asset = inventory.sources.values.firstOrNull { it.name.equals(path, true) }
                ?: fail("素材不存在、已删除或尚未纳入当前文档：$path")
            val document = WatchXml.parse(session.state.xml)
            val all = WatchXml.parse("<WFToolsLayers/>")
            all.documentElement.appendChild(all.importNode(document.documentElement, true))
            val references = try { References(all, inventory.sources) } catch (e: WatchDocumentException.InvalidCommand) {
                fail("文档存在动态或无法解析的素材引用，不能确认删除安全；请先改为静态路径或修复引用。${e.message}")
            }
            if (references.required.any { it.equals(asset.name, true) }) fail("素材仍被图层、字体或音效引用；请先替换或解除引用：${asset.name}")

            // Unused bitmap descriptors still own their atlas: deleting the atlas
            // first would damage a reusable font in the asset library.
            inventory.sources.values.filter { it.name.startsWith("fonts_bm/", true) && AssetKinds.extension(it.name) == "fnt" && !it.name.equals(asset.name, true) }.forEach { descriptor ->
                val pages = BitmapFont.parse(readDescriptor(descriptor)).pages
                if (pages.isEmpty()) fail("存在无法解析的位图字体 ${descriptor.name}，请先修复或删除该未使用字体，再删除图集。")
                pages.forEach { page ->
                    val relative = Path.of(descriptor.name).parent.resolve(page.replace('\\', '/')).normalize().toString().replace('\\', '/')
                    val fallback = "fonts_bm/${page.substringAfterLast('/').substringAfterLast('\\')}"
                    if (asset.name.equals(relative, true) || asset.name.equals(fallback, true)) fail("素材仍被位图字体 ${descriptor.name} 用作图集，不能单独删除。")
                }
            }
            val scripts = mutableListOf(session.toWatchProject().globalScript)
            inventory.sources.values.filter { it.name.startsWith("scripts/", true) && !it.name.equals("scripts/script.txt", true) }.forEach { scripts.add(readSourceText(it, "脚本")) }
            fun collectCode(element: Element) {
                for (i in 0 until element.attributes.length) {
                    val attribute = element.attributes.item(i)
                    val key = attribute.nodeName.lowercase(Locale.ROOT); val value = attribute.nodeValue
                    if (key.contains("script") || value.startsWith("script:") || value.startsWith("lua:") || WM_RESOURCE_CALL.containsMatchIn(value)) scripts.add(value)
                }
                if (element.tagName.contains("script", true)) scripts.add(element.textContent)
                element.elements().forEach(::collectCode)
            }
            collectCode(document.documentElement)
            scripts.filter { it.isNotBlank() }.forEach { code ->
                if (scriptMayReference(code, asset.name)) fail("脚本仍引用或可能动态生成该素材路径；请先修改脚本后再删除：${asset.name}")
            }
            val hash = asset.open().use { session.store.stageBlob(session.home, it, MAX_RESOURCE_BYTES) }
            if (asset.expectedHash != null && hash != asset.expectedHash) fail("素材字节已损坏，删除已停止：${asset.name}")
            asset.name to hash
        }
    }

    fun copy(session: DocumentSession): String = guarded("无法复制图层") {
        session.ensureOpen()
        val source = WatchXml.parse(session.state.xml)
        val marker = WatchXml.parse("<WFToolsLayers version=\"2\"/>")
        val token = UUID.randomUUID().toString()
        marker.documentElement.setAttribute("transfer", token)
        marker.documentElement.setAttribute("sourceDocumentId", session.id)
        WatchXml.layers(source).zip(session.state.layerIds).filter { it.second in session.selection.selected }.forEach { (layer, _) ->
            marker.documentElement.appendChild(marker.importNode(layer, true))
        }
        validateLayers(marker)
        val serialized = WatchXml.serialize(marker)
        val clipboardRoot = session.store.workspaceRoot.resolve("clipboard")
        val resources = Inventory(session).use { inventory ->
            val references = References(marker, inventory.sources)
            var total = 0L
            references.required.map { name ->
                val asset = inventory.sources.getValue(name)
                total += asset.size
                if (asset.size > MAX_RESOURCE_BYTES || total > MAX_PACKAGE_BYTES) fail("选中资源超过剪贴板大小限制。")
                val hash = asset.open().use { session.store.stageBlob(clipboardRoot, it, MAX_RESOURCE_BYTES) }
                if (asset.expectedHash != null && asset.expectedHash != hash) fail("源资源已损坏：$name")
                Resource(name, hash, asset.size)
            }
        }
        val directory = clipboardRoot.resolve(token)
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, ".manifest-", ".tmp")
        try {
            val manifest = Manifest(2, token, session.id, WatchDocumentStore.hashBytes(serialized.toByteArray()), resources)
            Files.writeString(temp, gson.toJson(manifest))
            FileChannel.open(temp, WRITE).use { it.force(true) }
            Files.move(temp, directory.resolve("manifest.json"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        serialized
    }

    fun prepare(session: DocumentSession, text: String): Prepared = guarded("无法粘贴图层") {
        session.ensureOpen()
        val marker = WatchXml.parse(text); validateLayers(marker)
        if (marker.documentElement.getAttribute("version") !in setOf("", "1", "2")) fail("不支持此剪贴板版本，请重新复制图层。")
        if (marker.documentElement.getAttribute("version") != "2") {
            // Legacy/external XML is accepted only when every static resource is
            // already available in this document. It must not create broken refs.
            Inventory(session).use { References(marker, it.sources) }
            return@guarded Prepared(marker, emptyMap())
        }
        val token = marker.documentElement.getAttribute("transfer")
        UUID.fromString(token)
        val clipboardRoot = session.store.workspaceRoot.resolve("clipboard")
        val manifestPath = clipboardRoot.resolve(token).resolve("manifest.json")
        if (!Files.isRegularFile(manifestPath)) fail("剪贴板资源包已丢失，请在源窗口重新复制。")
        val manifest = gson.fromJson(Files.readString(manifestPath), Manifest::class.java)
        if (manifest.version != 2 || manifest.id != token || manifest.sourceDocumentId != marker.documentElement.getAttribute("sourceDocumentId") ||
            manifest.xmlHash != WatchDocumentStore.hashBytes(WatchXml.serialize(marker).toByteArray())) fail("剪贴板标记与资源包不一致，请重新复制。")
        if (manifest.resources.size > 50_000) fail("剪贴板资源条目过多。")
        val seen = HashSet<String>(); var total = 0L
        val sources = LinkedHashMap<String, Source>()
        manifest.resources.forEach { resource ->
            WatchDocumentStore.validateArchiveName(resource.name)
            if (!seen.add(resource.name.lowercase(Locale.ROOT)) || !HASH.matches(resource.hash)) fail("剪贴板资源索引无效。")
            val path = clipboardRoot.resolve("blobs").resolve(resource.hash)
            if (!Files.isRegularFile(path) || Files.size(path) != resource.size || WatchDocumentStore.hashFile(path) != resource.hash) fail("剪贴板资源缺失或损坏：${resource.name}")
            total += resource.size
            if (resource.size < 0 || resource.size > MAX_RESOURCE_BYTES || total > MAX_PACKAGE_BYTES) fail("剪贴板资源超过大小限制。")
            sources[resource.name] = Source(resource.name, resource.size, resource.hash) { Files.newInputStream(path) }
        }
        val references = References(marker, sources)
        if (references.required.toSet() != sources.keys) fail("剪贴板资源清单与图层依赖不一致。")
        Inventory(session).use { existing ->
            val occupied = existing.sources.keys + session.state.removedAssetKeys() + Files.walk(session.workDir).use { paths -> paths.filter { Files.isRegularFile(it) }.map { session.workDir.relativize(it).toString().replace('\\', '/') }.toList() }
            val names = AssetNameAllocator(occupied)
            val mappings = LinkedHashMap<String, String>()
            val folderMappings = LinkedHashMap<String, String>()
            val atlasMappings = LinkedHashMap<String, String>()
            val additions = LinkedHashMap<String, String>()
            fun allocate(sourceName: String, requested: String, reuse: Boolean): String {
                val asset = sources.getValue(sourceName)
                val current = existing.sources.values.firstOrNull { it.name.equals(requested, true) }
                if (reuse && current != null && current.size == asset.size && Files.isRegularFile(session.workDir.resolve(current.name))) {
                    val currentHash = current.expectedHash ?: current.open().use { hashStream(it) }
                    if (currentHash == asset.expectedHash && WatchDocumentStore.hashFile(session.workDir.resolve(current.name)) == asset.expectedHash) return current.name
                }
                return names.reserve(requested)
            }
            references.folders.keys.sortedBy { it.length }.forEach { sourceFolder ->
                val parent = folderMappings.keys.filter { sourceFolder.startsWith("$it/", true) }.maxByOrNull { it.length }
                val root = sourceFolder.substringBefore('/').lowercase(Locale.ROOT)
                val normalized = if (root in ASSET_ROOTS) root + sourceFolder.substring(sourceFolder.substringBefore('/').length) else sourceFolder
                folderMappings[sourceFolder] = if (parent != null) folderMappings.getValue(parent) + sourceFolder.substring(parent.length)
                    else names.reserveDirectory(normalized)
            }
            fun folderTarget(sourceName: String): String? {
                val folder = folderMappings.keys.filter { sourceName.startsWith("$it/", true) }.maxByOrNull { it.length } ?: return null
                return folderMappings.getValue(folder) + sourceName.substring(folder.length)
            }
            references.direct.forEach { sourceName ->
                folderTarget(sourceName)?.let { mappings[sourceName] = names.claim(it) }
            }
            // The renderer derives night filenames from the day filename. Reserve
            // the pair together, even when the source has no optional night sheet,
            // so unrelated destination images cannot change its day fallback.
            references.nightVariants.entries.sortedBy { if (it.key == it.value) 1 else 0 }.forEach { (day, night) ->
                if (day in mappings) return@forEach
                val requested = "images/" + day.substringAfter('/')
                val mappedDay = names.reserve(requested) { listOf(nightImagePath(it)) }
                mappings[day] = mappedDay
                if (night != null && night != day) mappings[night] = nightImagePath(mappedDay)
            }
            references.direct.forEach { sourceName ->
                if (sourceName in mappings) return@forEach
                val root = sourceName.substringBefore('/').lowercase(Locale.ROOT)
                val normalized = if (root in ASSET_ROOTS && sourceName.contains('/')) root + "/" + sourceName.substringAfter('/') else sourceName
                val requested = if (sourceName in references.atlases) "fonts_bm/${sourceName.substringAfterLast('/')}" else normalized
                mappings[sourceName] = allocate(sourceName, requested, reuse = AssetKinds.extension(sourceName) != "fnt")
            }
            references.atlases.values.flatten().forEach { atlas ->
                atlasMappings.getOrPut(atlas.source) {
                    mappings[atlas.source]?.takeIf { it.substringBeforeLast('/', "").equals("fonts_bm", true) }
                        ?: allocate(atlas.source, "fonts_bm/${atlas.source.substringAfterLast('/')}", reuse = true)
                }
            }
            fun stage(sourceName: String, targetName: String, bytes: ByteArray? = null) {
                val asset = sources.getValue(sourceName)
                val current = existing.sources.values.firstOrNull { it.name.equals(targetName, true) }
                if (current != null) return // allocation already verified exact immutable and working bytes
                val hash = if (bytes != null) session.store.stageBlob(session.home, bytes)
                    else asset.open().use { session.store.stageBlob(session.home, it, MAX_RESOURCE_BYTES) }
                if (bytes == null && hash != asset.expectedHash) fail("粘贴期间资源发生变化：$sourceName")
                additions[targetName] = hash
            }
            mappings.forEach { (sourceName, targetName) ->
                val pages = references.atlases[sourceName]
                if (pages != null) {
                    val original = readDescriptor(sources.getValue(sourceName))
                    val parent = Path.of(targetName).parent ?: Path.of("")
                    val pageNames = pages.associate { it.page to parent.relativize(Path.of(atlasMappings.getValue(it.source))).toString().replace('\\', '/') }
                    val rewritten = PAGE_LINE.replace(original) { match ->
                        val page = match.groupValues[2].trim('"')
                        match.groupValues[1] + "\"" + (pageNames[page] ?: fail("字体图集重映射失败：$page")) + "\""
                    }
                    stage(sourceName, targetName, rewritten.toByteArray())
                } else stage(sourceName, targetName)
            }
            atlasMappings.forEach { (sourceName, targetName) -> if (targetName !in additions) stage(sourceName, targetName) }
            references.rewrite(mappings + folderMappings.mapKeys { "@folder:${it.key}" })
            Prepared(marker, additions)
        }
    }

    private class Inventory(session: DocumentSession) : AutoCloseable {
        private val archive: ZipFile?
        val sources = LinkedHashMap<String, Source>()
        init {
            val original = session.home.resolve("original.zip")
            if (session.baseArchiveHash != null && (!Files.isRegularFile(original) || WatchDocumentStore.hashFile(original) != session.baseArchiveHash)) fail("源表盘工作副本缺失或损坏。")
            archive = if (Files.isRegularFile(original)) ZipFile(original.toFile()) else null
            val removed = session.state.removedAssetKeys()
            try {
                archive?.let { zip -> zip.entries().asSequence().filterNot { it.isDirectory || it.name.lowercase(Locale.ROOT) in removed }.forEach { entry ->
                    sources[entry.name] = Source(entry.name, entry.size, null) { zip.getInputStream(entry) }
                } }
                (session.state.importedAssets + session.savedDerivedResources).forEach { (name, hash) ->
                    if (name.lowercase(Locale.ROOT) in removed) return@forEach
                    val actual = sources.keys.firstOrNull { it.equals(name, true) } ?: name
                    val path = session.home.resolve("blobs").resolve(hash)
                    if (!Files.isRegularFile(path)) fail("源资源已丢失：$name")
                    sources[actual] = Source(actual, Files.size(path), hash) { Files.newInputStream(path) }
                }
            } catch (error: Exception) { archive?.close(); throw error }
        }
        override fun close() { archive?.close() }
    }

    private data class Atlas(val page: String, val source: String)
    private data class Part(val original: String, val source: String?, val format: (String) -> String)
    private data class Binding(val node: Node, val parts: List<Part>)
    private class References(document: Document, private val sources: Map<String, Source>) {
        val direct = linkedSetOf<String>()
        val folders = LinkedHashMap<String, List<String>>()
        val atlases = LinkedHashMap<String, List<Atlas>>()
        val nightVariants = LinkedHashMap<String, String?>()
        val required: Set<String> get() = direct + atlases.values.flatten().map { it.source }
        private val bindings = mutableListOf<Binding>()
        private val names = sources.keys.associateBy { it.lowercase(Locale.ROOT) }
        init { document.documentElement.elements().forEach { inspect(it, it.getAttribute("type")) } }

        private fun inspect(element: Element, layerType: String) {
            val effectiveType = if (element.tagName.equals("Layer", true)) element.getAttribute("type") else layerType
            for (i in 0 until element.attributes.length) {
                val attribute = element.attributes.item(i)
                val key = attribute.nodeName.lowercase(Locale.ROOT)
                val raw = attribute.nodeValue
                if (raw.isBlank() || key == "xmlns" || key.startsWith("xmlns:") || key.endsWith("_script")) continue
                if (FONT_KEY.matches(key)) {
                    val part = font(raw)
                    if (part.source != null) bindings.add(Binding(attribute, listOf(part)))
                } else if (key in setOf("tap_action", "tap") && (raw.startsWith("play_sound:") || raw.startsWith("sfx:"))) {
                    bindings.add(Binding(attribute, listOf(sound(raw))))
                } else if (key !in TEXT_KEYS) {
                    val folder = if (element.tagName.equals("Layer", true) && key == "path") when (effectiveType) {
                        "image", "image_gif", "image_cond" -> "images"; "video" -> "videos"; else -> null
                    } else if (element.tagName.contains("Audio", true) && key == "path") "sfx" else null
                    val strict = key in RESOURCE_ATTRIBUTES || key.endsWith("_path") || key.endsWith("_file")
                    val parts = raw.split('`').map { token -> file(token, folder, strict) }
                    if (parts.any { it.source != null }) bindings.add(Binding(attribute, parts))
                    if (element.tagName.equals("Layer", true) && effectiveType == "image_cond" && key == "path" && element.getAttribute("weather_night") == "Y") {
                        parts.forEach { part ->
                            val day = part.source ?: return@forEach
                            val night = canonical(nightImagePath(normalized(part.original.trim(), "images")))
                            nightVariants[day] = night
                            if (night != null) direct.add(night)
                        }
                    }
                }
            }
            element.elements().forEach { inspect(it, effectiveType) }
            if (element.elements().isEmpty() && RESOURCE_KEY.containsMatchIn(element.tagName.lowercase(Locale.ROOT))) {
                val text = element.firstChild
                if (text != null && text.nodeType == Node.TEXT_NODE) {
                    val part = file(text.nodeValue, null, true)
                    if (part.source != null) bindings.add(Binding(text, listOf(part)))
                }
            }
        }

        private fun canonical(path: String): String? = names[path.lowercase(Locale.ROOT)]
        private fun normalized(raw: String, folder: String? = null): String {
            val value = raw.replace('\\', '/')
            if (value.startsWith('/') || value.contains(':')) fail("资源路径超出文档范围：$raw")
            val path = if (folder != null && !value.startsWith("$folder/", true)) Path.of(folder).resolve(value) else Path.of(value)
            val result = path.normalize().toString().replace('\\', '/')
            WatchDocumentStore.validateArchiveName(result)
            return result
        }

        private fun file(raw: String, folder: String?, strict: Boolean): Part {
            val token = raw.trim()
            if (token.isEmpty()) return Part(raw, null) { raw }
            if (folder == null && URI_SCHEME.containsMatchIn(token)) return Part(raw, null) { raw }
            val extension = AssetKinds.extension(token)
            val looksLikeResource = extension in AssetKinds.images + AssetKinds.videos + AssetKinds.fonts + AssetKinds.sounds || (token.contains('/') && extension.isNotEmpty())
            if (!strict && !looksLikeResource && !token.contains('/')) return Part(raw, null) { raw }
            val resolved = runCatching {
                val first = canonical(normalized(token, folder))
                if (first != null || folder != null) first else {
                    val inferred = when (extension) {
                        in AssetKinds.images -> "images"; in AssetKinds.videos -> "videos"; in AssetKinds.sounds -> "sfx"
                        "fnt" -> "fonts_bm"; "ttf", "otf" -> "fonts"; else -> null
                    }
                    inferred?.let { canonical(normalized(token, it)) }
                        ?: sources.keys.filter { it.substringAfterLast('/').equals(token, true) }.singleOrNull()
                }
            }.getOrNull()
            if (resolved == null) {
                if (folder == null && (strict || token.contains('/'))) {
                    val normalized = runCatching { normalized(token) }.getOrNull()
                    val children = if (normalized == null) emptyList() else sources.keys.filter { it.startsWith("$normalized/", true) }
                    if (children.isNotEmpty()) {
                        val depth = normalized!!.split('/').size
                        val actual = children.first().split('/').take(depth).joinToString("/")
                        if (children.any { it.equals("scripts/script.txt", true) || it.equals("watch.xml", true) || it.equals("watch.pxml", true) }) fail("此文件夹包含文档或全局脚本，不能自动作为素材转移。")
                        folders[actual] = children; direct.addAll(children)
                        return Part(raw, "@folder:$actual") { mapped -> raw.replaceRange(raw.indexOf(token), raw.indexOf(token) + token.length, mapped) }
                    }
                }
                if (!strict && !looksLikeResource) return Part(raw, null) { raw }
                if (strict || looksLikeResource) {
                    if (token.any { it in "{}'\"" } || token.contains("..")) fail("动态资源路径无法无损转移，请先设为静态路径：$token")
                    fail("图层引用的资源不存在，或引用了尚不支持的文件夹：$token")
                }
                return Part(raw, null) { raw }
            }
            if (resolved.equals("watch.xml", true) || resolved.equals("watch.pxml", true) || resolved.equals("scripts/script.txt", true)) fail("不能把文档本身或全局脚本作为图层资源自动转移：$resolved")
            direct.add(resolved)
            if (AssetKinds.extension(resolved) == "fnt") addAtlas(resolved)
            return Part(raw, resolved) { mapped ->
                val replacement = when {
                    folder != null && token.replace('\\', '/').startsWith("$folder/", true) && mapped.startsWith("$folder/", true) -> folder + "/" + mapped.substring(folder.length + 1)
                    folder != null -> Path.of(folder).relativize(Path.of(mapped)).toString().replace('\\', '/')
                    token.contains('/') || token.contains('\\') -> mapped
                    else -> mapped.substringAfterLast('/')
                }
                raw.replaceRange(raw.indexOf(token), raw.indexOf(token) + token.length, replacement)
            }
        }

        private fun sound(raw: String): Part {
            val prefix = raw.substringBefore(':') + ":"
            val name = raw.substringAfter(':')
            if (name.isBlank()) return Part(raw, null) { raw }
            val candidates = listOf(name) + listOf(".mp3", ".ogg", ".wav", ".m4a").map { name + it }
            val resolved = candidates.mapNotNull { runCatching {
                val path = normalized("sfx/$it")
                if (path.startsWith("sfx/", true)) canonical(path) else null
            }.getOrNull() }.firstOrNull()
                ?: fail("音效素材不存在或是动态路径：$name")
            direct.add(resolved)
            return Part(raw, resolved) { mapped -> prefix + Path.of("sfx").relativize(Path.of(mapped)).toString().replace('\\', '/') }
        }

        private fun font(raw: String): Part {
            val value = raw.trim().replace('\\', '/')
            val bitmap = value.startsWith("bm:", true)
            val fontName = if (bitmap) value.substring(3) else if (value.startsWith("fonts/", true)) value.substring(6) else value
            val resolved = if (bitmap) canonical(normalized("${if (fontName.endsWith(".fnt", true)) fontName.dropLast(4) else fontName}.fnt", "fonts_bm")) else {
                listOf("fonts/$fontName", "fonts/$fontName.ttf", "fonts/$fontName.otf").mapNotNull { runCatching { canonical(normalized(it)) }.getOrNull() }.firstOrNull()
            }
            if (resolved == null) {
                if (!bitmap && isSystemFont(value)) return Part(raw, null) { raw }
                fail("字体依赖不存在或是动态表达式：$raw")
            }
            direct.add(resolved)
            if (bitmap || AssetKinds.extension(resolved) == "fnt") addAtlas(resolved)
            return Part(raw, resolved) { mapped ->
                if (bitmap) "bm:" + Path.of("fonts_bm").relativize(Path.of(mapped)).toString().replace('\\', '/').substringBeforeLast('.')
                else {
                    val relative = Path.of("fonts").relativize(Path.of(mapped)).toString().replace('\\', '/')
                    val result = if (AssetKinds.extension(fontName) in setOf("ttf", "otf")) relative else relative.substringBeforeLast('.')
                    result
                }
            }
        }

        private fun addAtlas(descriptor: String) {
            if (descriptor in atlases) return
            val font = BitmapFont.parse(readDescriptor(sources.getValue(descriptor)))
            if (font.pages.isEmpty()) fail("位图字体没有 atlas page：$descriptor")
            atlases[descriptor] = font.pages.distinct().map { page ->
                val parent = descriptor.substringBeforeLast('/', "")
                val resolved = canonical(normalized(page, parent)) ?: canonical(normalized(page.substringAfterLast('/').substringAfterLast('\\'), "fonts_bm"))
                    ?: fail("位图字体图集不存在：$page")
                Atlas(page, resolved)
            }
        }

        fun rewrite(mappings: Map<String, String>) {
            bindings.forEach { binding -> binding.node.nodeValue = binding.parts.joinToString("`") { part -> part.source?.let { part.format(mappings.getValue(it)) } ?: part.original } }
        }
    }

    private fun validateLayers(document: Document) {
        if (document.documentElement.tagName != "WFToolsLayers" || document.documentElement.elements().any { !it.tagName.equals("Layer", true) } || document.documentElement.elements().size > 5000)
            fail("剪贴板不是有效的 WFTools 图层包，或图层数量超过 5000。")
    }
    private fun readDescriptor(source: Source): String {
        return readSourceText(source, "位图字体描述文件")
    }
    private fun readSourceText(source: Source, label: String): String {
        if (source.size > 16L * 1024 * 1024) fail("$label 过大，无法安全检查引用：${source.name}")
        val bytes = source.open().use { it.readNBytes(16 * 1024 * 1024 + 1) }
        if (bytes.size.toLong() != source.size || (source.expectedHash != null && WatchDocumentStore.hashBytes(bytes) != source.expectedHash)) fail("$label 已损坏：${source.name}")
        return bytes.toString(Charsets.UTF_8)
    }
    private fun scriptMayReference(script: String, asset: String): Boolean {
        val text = script.replace('\\', '/').lowercase(Locale.ROOT)
        val name = asset.lowercase(Locale.ROOT); val filename = name.substringAfterLast('/'); val stem = filename.substringBeforeLast('.')
        if (text.contains(name) || text.contains(filename)) return true
        val folder = name.substringBefore('/'); val extension = AssetKinds.extension(filename)
        val audio = folder in setOf("sfx", "sounds")
        if ((audio || folder in setOf("fonts", "fonts_bm")) && (text.contains("'$stem'") || text.contains("\"$stem\""))) return true
        if (audio && (text.contains("play_sound:$stem") || text.contains("sfx:$stem"))) return true
        val buildsNames = text.contains("..") || text.contains("string.format")
        if (buildsNames && (text.contains("$folder/") || (extension.isNotBlank() && (text.contains(".$extension'") || text.contains(".$extension\""))))) return true
        if (audio) {
            for (match in WM_RESOURCE_CALL.findAll(text)) {
                val tail = text.substring(match.range.last + 1).substringBefore(')').trim()
                val literal = tail.length >= 2 && ((tail.first() == '\'' && tail.last() == '\'') || (tail.first() == '"' && tail.last() == '"')) && !tail.contains("..")
                if (tail.isNotBlank() && tail != "nil" && !literal) return true
            }
            if (buildsNames && (text.contains("play_sound:") || text.contains("sfx:"))) return true
        }
        return false
    }
    private fun hashStream(input: InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun isSystemFont(value: String): Boolean = value.lowercase(Locale.ROOT) in BUILTIN_FONTS || systemFonts.any { it.equals(value, true) }
    private val systemFonts by lazy { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }
    private val BUILTIN_FONTS = setOf("roboto", "roboto-regular", "roboto-bold", "roboto-light", "roboto-thin", "roboto-medium", "roboto-italic", "sans-serif", "serif", "monospace", "default")
    private val FONT_KEY = Regex("font\\d*")
    private val RESOURCE_KEY = Regex("path|file|image|texture|resource|atlas|icon|mask|audio|video|asset|src|href")
    private val RESOURCE_ATTRIBUTES = setOf("path", "file", "filename", "file_name", "image", "texture", "resource", "src", "href", "atlas", "audio", "video", "mask", "asset")
    private val ASSET_ROOTS = setOf("images", "videos", "fonts", "fonts_bm", "sounds", "sfx")
    private val TEXT_KEYS = setOf("text", "name", "description", "tap_action", "tap", "spoken", "script", "type")
    private fun nightImagePath(path: String): String {
        val dot = path.lastIndexOf('.')
        if (dot <= 0) return path
        return path.substring(0, dot).removeSuffix("_n") + "_n" + path.substring(dot)
    }
    private val PAGE_LINE = Regex("(?m)^(\\s*page\\b[^\\r\\n]*?\\bfile=)(\"[^\"]*\"|[^\\s]+)")
    private val HASH = Regex("[a-f0-9]{64}")
    private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val WM_RESOURCE_CALL = Regex("\\bwm_(?:sfx|action)\\s*\\(", RegexOption.IGNORE_CASE)
    private const val MAX_RESOURCE_BYTES = 256L * 1024 * 1024
    private const val MAX_PACKAGE_BYTES = 1024L * 1024 * 1024
    private fun fail(message: String): Nothing = throw WatchDocumentException.InvalidCommand(message)
    private inline fun <T> guarded(operation: String, block: () -> T): T = try { block() }
        catch (e: WatchDocumentException.InvalidCommand) { throw e }
        catch (e: Exception) { throw WatchDocumentException.InvalidCommand("$operation：${e.message}", e) }
}
