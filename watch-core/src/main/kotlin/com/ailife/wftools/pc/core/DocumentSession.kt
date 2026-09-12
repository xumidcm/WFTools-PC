package com.ailife.wftools.pc.core

import com.ailife.wftools2.domain.editor.watchmaker.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.math.BigDecimal
import java.nio.file.*
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/** Single-thread confined editor state. Snapshots retain raw XML, resource identities and selection. */
class DocumentSession internal constructor(
    internal val store: WatchDocumentStore,
    val id: String,
    var sourcePath: Path?,
    internal val home: Path,
    internal val xmlEntry: String,
    internal var sourceHash: String?,
    internal var state: DocumentSnapshot,
    val workDir: Path = home.resolve("content"),
    internal val baseArchiveHash: String? = sourceHash
) : AutoCloseable {
    internal var savedSignature: String? = null
    internal var savedDerivedResources: Map<String, String> = emptyMap()
    internal val undoStack = ArrayDeque<DocumentSnapshot>()
    internal val redoStack = ArrayDeque<DocumentSnapshot>()
    internal val snapshotHashes = java.util.IdentityHashMap<DocumentSnapshot, String>()
    internal var committedSnapshotRefs: Set<String> = emptySet()
    internal val pendingSnapshotGc = linkedSetOf<String>()
    internal var snapshotGcNeedsScan = true
    internal var snapshotGcCommits = 0
    private var closed = false
    private var inTransaction = false
    var revision: Long = 0L
        internal set
    /** Nonfatal failure after a successful source save; the editor may display this separately. */
    var lastDraftError: String? = null
        internal set
    val title: String get() = document().documentElement.getAttribute("name").ifBlank { "未命名表盘" }
    val layers: List<EditorLayer> get() = WatchXml.layers(document()).mapIndexed { index, layer ->
        EditorLayer(state.layerIds[index], layer.getAttribute("type").ifBlank { "image" }, layer.attributesMap().apply { remove("type") })
    }
    val selection: LayerSelection get() = state.selection
    val hasUnsavedChanges: Boolean get() = savedSignature != WatchDocumentStore.signature(state)
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun toWatchProject(): WmWatchfaceProject {
        val doc = document(); val root = doc.documentElement; val attrs = root.attributesMap()
        fun descendants(name: String) = root.getElementsByTagName(name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        val script = workDir.resolve(scriptEntryName())
        return WmWatchfaceProject(id = id, title = attrs["name"] ?: "", width = attrs["width"]?.toIntOrNull() ?: 512,
            height = attrs["height"]?.toIntOrNull() ?: 512, description = attrs["description"] ?: "", tags = attrs["tags"] ?: "",
            background = attrs["bg_color"] ?: "000000", shape = when (attrs["shape"]?.uppercase()) {
                null, "", "R" -> "Round"; "S" -> "Square"; else -> attrs.getValue("shape")
            }, author = attrs["author"] ?: "", link = attrs["web_link"] ?: "", protection = attrs["protection"] ?: "n",
            globalScript = state.scriptOverride ?: if (Files.isRegularFile(script)) Files.readString(script) else attrs["global_script"] ?: "", rootAttrs = attrs,
            themeSets = descendants("ThemeSet").map { WmThemeSet(it.attributesMap()) },
            sounds = descendants("Sound").map { sound -> WmSound(sound.attributesMap(), sound.elements().firstOrNull { it.tagName == "Spoken" }?.let { it.getAttribute("text").ifBlank { it.textContent } } ?: "") },
            flavorSets = descendants("FlavorSet").map { set -> WmFlavorSet(set.attributesMap(), set.elements().filter { it.tagName == "Option" }.map { it.attributesMap() }) },
            wmLayers = WatchXml.layers(doc).map { layer -> WmWatchfaceLayer(layer.getAttribute("type").ifBlank { "image" },
                layer.attributesMap().apply { remove("type") }, layer.elements().map { WmLayerChild(it.tagName, it.attributesMap()) }) })
    }

    fun setAttribute(ids: Set<String>, key: String, value: String) {
        requireIds(ids)
        change { doc, _, _ -> WatchXml.layers(doc).forEachIndexed { index, layer -> if (state.layerIds[index] in ids) layer.setAttribute(key, value) } }
    }

    /** Applies one completed multi-layer gesture as one undoable command. */
    fun setLayerAttributes(updates: Map<String, Map<String, String>>) {
        requireIds(updates.keys)
        if (updates.values.all { it.isEmpty() }) return
        change { doc, ids, _ -> WatchXml.layers(doc).zip(ids).forEach { (layer, id) ->
            updates[id]?.forEach { (key, value) -> layer.setAttribute(key, value) }
        } }
    }

    fun addLayer(type: String) {
        if (type !in setOf("image", "image_gif", "image_cond", "text", "shape", "rounded", "progress", "ring", "text_curved",
                "markers", "markers_hm", "text_ring", "series", "calendar", "chart", "video", "map", "gallery_2d"))
            throw WatchDocumentException.InvalidCommand("不支持的新图层类型：$type")
        change { doc, ids, _ ->
            val root = doc.documentElement
            val defaults = WmLayerDefaults.create(type, root.getAttribute("width").toIntOrNull() ?: 512, root.getAttribute("height").toIntOrNull() ?: 512)
            val node = doc.createElement("Layer").apply {
                setAttribute("type", type); defaults.attrs.forEach { (key, value) -> setAttribute(key, value) }
            }
            layerContainer(doc).appendChild(node)
            val newId = UUID.randomUUID().toString(); ids.add(newId); selected(listOf(newId))
        }
    }

    fun setRootAttribute(key: String, value: String) {
        if (key in setOf("width", "height") && (value.toIntOrNull()?.let { it in 1..16384 } != true))
            throw WatchDocumentException.InvalidCommand("表盘尺寸必须为 1–16384 的整数。")
        change { doc, _, _ -> doc.documentElement.setAttribute(key, value) }
    }

    fun setGlobalScript(text: String) {
        ensureOpen()
        val doc = document()
        val embedded = doc.documentElement.hasAttribute("global_script")
        val originalFile = originalScript()
        if (embedded) doc.documentElement.setAttribute("global_script", text)
        val override = if (embedded && originalFile == null) null
            else text.takeUnless { it == (originalFile?.toString(Charsets.UTF_8) ?: "") }
        commit(state.copy(xml = WatchXml.serialize(doc), scriptOverride = override))
    }

    /** One asset applies to every layer; otherwise assets follow document order. */
    fun replaceAssetForLayers(ids: Set<String>, paths: List<Path>, attribute: String = "path") {
        requireIds(ids)
        if (ids.isEmpty()) return
        if (paths.size != 1 && paths.size != ids.size)
            throw WatchDocumentException.AssetImportFailed("请选择一个共享资源，或为每个选中图层选择一个资源。")
        val orderedLayers = layers.filter { it.id in ids }
        orderedLayers.forEachIndexed { index, layer -> AssetKinds.validateReplacement(layer, attribute, paths[if (paths.size == 1) 0 else index]) }
        transaction {
            val imported = importAssetsInternal(paths, false, 0.0, 0.0)
            val ordered = state.layerIds.filter { it in ids }
            ordered.forEachIndexed { index, id ->
                val name = imported[if (imported.size == 1) 0 else index]
                val value = if (attribute == "font" || attribute == "font2") (if (name.startsWith("fonts_bm/")) "bm:" else "") + name.substringAfter('/').substringBeforeLast('.')
                    else name.substringAfter('/')
                setAttribute(setOf(id), attribute, value)
            }
        }
    }

    fun deleteSelected() {
        if (selection.selected.isEmpty()) return
        change { doc, ids, _ ->
            WatchXml.layers(doc).zip(ids.toList()).filter { it.second in selection.selected }.forEach { (node, id) -> node.parentNode.removeChild(node); ids.remove(id) }
            LayerSelection()
        }
    }

    fun duplicateSelected() {
        if (selection.selected.isEmpty()) return
        change { doc, ids, _ ->
            val newIds = mutableListOf<String>()
            WatchXml.layers(doc).zip(ids.toList()).filter { it.second in selection.selected }.forEach { (node, id) ->
                val newId = UUID.randomUUID().toString(); node.parentNode.insertBefore(node.cloneNode(true), node.nextSibling)
                ids.add(ids.indexOf(id) + 1, newId); newIds.add(newId)
            }
            selected(newIds)
        }
    }

    /** targetIndex is the final insertion index in document (bottom-to-top) order. */
    fun moveSelected(targetIndex: Int) {
        if (selection.selected.isEmpty()) return
        change { doc, ids, _ ->
            val nodes = WatchXml.layers(doc)
            if (nodes.map { it.parentNode }.distinct().size > 1)
                throw WatchDocumentException.InvalidCommand("此表盘包含多个图层容器，暂不能跨容器移动。")
            val moving = nodes.zip(ids).filter { it.second in selection.selected }
            val remaining = nodes.zip(ids).filterNot { it.second in selection.selected }.toMutableList()
            remaining.addAll(targetIndex.coerceIn(0, remaining.size), moving)
            if (remaining.map { it.second } != ids) {
                // Temporary placeholders keep comments and unknown non-layer nodes
                // exactly in place while replacing only the layer slots.
                val parent = nodes.first().parentNode
                val markers = nodes.map { doc.createComment("wftools-layer-slot").also { marker -> parent.replaceChild(marker, it) } }
                markers.zip(remaining).forEach { (marker, item) -> parent.replaceChild(item.first, marker) }
                ids.clear(); ids.addAll(remaining.map { it.second })
            }
        }
    }

    fun nudge(dx: Double, dy: Double) {
        if (!dx.isFinite() || !dy.isFinite()) throw WatchDocumentException.InvalidCommand("移动距离必须是有限数值。")
        if (selection.selected.isEmpty() || (dx == 0.0 && dy == 0.0)) return
        change { doc, ids, _ -> WatchXml.layers(doc).zip(ids).filter { it.second in selection.selected }.forEach { (layer, _) ->
            if (dx != 0.0) layer.setAttribute("x", offset(layer.getAttribute("x").ifBlank { "0" }, dx))
            if (dy != 0.0) layer.setAttribute("y", offset(layer.getAttribute("y").ifBlank { "0" }, dy))
        } }
    }

    fun undo() = navigateHistory(undoStack, redoStack)
    fun redo() = navigateHistory(redoStack, undoStack)

    fun copySelection(): String = WatchClipboard.copy(this)

    fun pasteLayers(serialized: String) {
        val prepared = WatchClipboard.prepare(this, serialized)
        val incoming = prepared.document.documentElement.elements()
        if (incoming.isEmpty()) return
        change { doc, ids, assets ->
            assets.putAll(prepared.assets)
            val container = layerContainer(doc); val newIds = incoming.map { layer ->
                container.appendChild(doc.importNode(layer, true)); UUID.randomUUID().toString()
            }
            ids.addAll(newIds); selected(newIds)
        }
    }

    /** Transactional copy: validate/read all inputs before adding any document state. */
    fun importAssets(paths: List<Path>, createLayers: Boolean = true, x: Double = 0.0, y: Double = 0.0) {
        importAssetsInternal(paths, createLayers, x, y)
    }

    /** Imports only resource entries from another WatchMaker archive. */
    fun importWatchAssets(source: Path,prepareAsset:(Path)->Path={it}) {
        ensureOpen()
        val archive = source.toAbsolutePath().normalize()
        if (!Files.isRegularFile(archive)) throw WatchDocumentException.AssetImportFailed("找不到要导入的表盘：$archive")
        store.validateArchive(archive)
        val staging = Files.createTempDirectory(home, "watch-import-")
        try {
            val candidates = mutableListOf<Path>()
            ZipFile(archive.toFile()).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val normalized = entry.name.replace('\\', '/')
                    val folder = normalized.substringBefore('/', "").lowercase(Locale.ROOT)
                    val extension = AssetKinds.extension(normalized)
                    val accepted = when (folder) {
                        "images" -> extension in AssetKinds.images
                        "videos" -> extension in AssetKinds.videos
                        "fonts" -> extension in setOf("ttf", "otf")
                        "fonts_bm" -> extension == "fnt" || extension in AssetKinds.images
                        "sfx", "sounds" -> extension in AssetKinds.sounds
                        else -> false
                    }
                    if (!accepted) return@forEach
                    val destination = staging.resolve(normalized).normalize()
                    if (!destination.startsWith(staging)) throw WatchDocumentException.AssetImportFailed("表盘资源路径无效：${entry.name}")
                    Files.createDirectories(destination.parent)
                    zip.getInputStream(entry).use { input -> Files.copy(input, destination) }
                    if (folder != "fonts_bm" || extension == "fnt") candidates.add(destination)
                }
            }
            importAssetsInternal(candidates.map(prepareAsset), createLayers = true, x = 0.0, y = 0.0)
        } finally {
            Files.walk(staging).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    fun deleteAsset(relativePath: String) {
        ensureOpen()
        val deletion = WatchClipboard.prepareDeletion(this, relativePath)
        commit(state.copy(importedAssets = state.importedAssets + (deletion.first to deletion.second),
            removedAssets = state.removedAssets.orEmpty() + deletion.first))
    }

    private fun importAssetsInternal(paths: List<Path>, createLayers: Boolean, x: Double, y: Double): List<String> {
        ensureOpen()
        if (paths.isEmpty()) return emptyList()
        if (!x.isFinite() || !y.isFinite()) throw WatchDocumentException.AssetImportFailed("导入位置必须是有限数值。")
        try {
            val names = Files.walk(workDir).use { stream -> stream.filter { Files.isRegularFile(it) }.map { workDir.relativize(it).toString().replace('\\', '/').lowercase(Locale.ROOT) }.toList().toMutableSet() }
            names.addAll(state.importedAssets.keys.map { it.lowercase(Locale.ROOT) })
            names.addAll(state.removedAssetKeys())
            val additions = LinkedHashMap<String, ByteArray>()
            val primaryNames = mutableListOf<String>()
            val newLayers = mutableListOf<Pair<String, Map<String, String>>>()
            fun reserve(folder: String, filename: String): String {
                val safe = filename.replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_").trimEnd(' ', '.').ifBlank { "asset" }
                val base = safe.substringBeforeLast('.', safe); val ext = safe.substringAfterLast('.', "")
                var name = "$folder/$safe"; var suffix = 2
                fun conflicts(candidate: String): Boolean {
                    val lower = candidate.lowercase(Locale.ROOT)
                    if (lower in names) return true
                    // WatchMaker stores font identifiers without their extension.
                    // Test.ttf and test.otf must not both become font="Test".
                    val fontNamespace = folder == "fonts" || (folder == "fonts_bm" && ext.equals("fnt", true))
                    if (!fontNamespace) return false
                    val stem = lower.substringAfterLast('/').substringBeforeLast('.')
                    return names.any { existing ->
                        existing.startsWith("$folder/") && (folder != "fonts_bm" || existing.endsWith(".fnt")) &&
                            existing.substringAfterLast('/').substringBeforeLast('.') == stem
                    }
                }
                while (conflicts(name)) { name = "$folder/${base}_${suffix++}" + if (ext.isEmpty()) "" else ".$ext" }
                names.add(name.lowercase(Locale.ROOT))
                WatchDocumentStore.validateArchiveName(name)
                return name
            }
            paths.forEach { path ->
                if (!Files.isRegularFile(path)) throw WatchDocumentException.AssetImportFailed("找不到资源文件：$path")
                if (Files.size(path) > 256L * 1024 * 1024) throw WatchDocumentException.AssetImportFailed("资源过大：$path")
                val ext = path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)
                val folder = when (ext) {
                    "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "images"
                    "mp4", "webm", "mov", "m4v", "avi", "mkv" -> "videos"
                    "ttf", "otf" -> "fonts"
                    "fnt" -> "fonts_bm"
                    "mp3", "wav", "ogg", "m4a", "aac", "flac" -> "sfx"
                    else -> throw WatchDocumentException.AssetImportFailed("不支持的资源类型 .$ext；请选择图片、视频、TTF/OTF 字体或 BMFont .fnt。")
                }
                val name = reserve(folder, path.fileName.toString())
                primaryNames.add(name)
                var data = Files.readAllBytes(path)
                if (ext == "fnt") {
                    var descriptor = data.toString(Charsets.UTF_8)
                    val font = BitmapFont.parse(descriptor)
                    if (font.pages.isEmpty()) throw WatchDocumentException.AssetImportFailed("位图字体没有 atlas page：$path")
                    val replacements = LinkedHashMap<String, String>()
                    font.pages.distinct().forEach { page ->
                        if (page.contains('/') || page.contains('\\') || page in setOf(".", ".."))
                            throw WatchDocumentException.AssetImportFailed("位图字体 atlas 必须位于字体文件旁：$page")
                        val atlas = path.toAbsolutePath().parent.resolve(page)
                        if (!Files.isRegularFile(atlas)) throw WatchDocumentException.AssetImportFailed("缺少位图字体 atlas：$atlas")
                        val atlasName = reserve(folder, page)
                        additions[atlasName] = Files.readAllBytes(atlas); replacements[page] = atlasName.substringAfterLast('/')
                    }
                    val pageLine = Regex("(?m)^(\\s*page\\b[^\\r\\n]*?\\bfile=)(\"[^\"]*\"|[^\\s]+)")
                    descriptor = pageLine.replace(descriptor) { match ->
                        val old = match.groupValues[2].trim('"'); match.groupValues[1] + "\"" + (replacements[old] ?: old) + "\""
                    }
                    data = descriptor.toByteArray(Charsets.UTF_8)
                }
                additions[name] = data
                if (createLayers && folder != "sfx") {
                    val attrs = linkedMapOf("x" to number(x), "y" to number(y), "opacity" to "100", "rotation" to "0", "display" to "bd", "alignment" to "cc")
                    val type = if (folder == "images") {
                        attrs["path"] = name.substringAfter('/')
                        val image = try { ImageIO.read(path.toFile()) } catch (_: Exception) { null }
                        attrs["width"] = (image?.width ?: 100).toString(); attrs["height"] = (image?.height ?: 100).toString()
                        if (ext == "gif") "image_gif" else "image"
                    } else if (folder == "videos") {
                        attrs["path"] = name.substringAfter('/')
                        attrs["width"] = "512"; attrs["height"] = "512"
                        attrs["color"] = "ffffff"; attrs["display"] = "b"; "video"
                    } else {
                        attrs["font"] = (if (ext == "fnt") "bm:" else "") + name.substringAfter('/').substringBeforeLast('.')
                        attrs["text"] = "{dh}:{dmz}"; attrs["text_size"] = "40"; attrs["color"] = "ffffff"; "text"
                    }
                    newLayers.add(type to attrs)
                }
            }
            val blobs = additions.mapValues { (_, bytes) -> store.stageBlob(home, bytes) }
            change { doc, ids, assets ->
                assets.putAll(blobs)
                val addedIds = newLayers.map { (type, attrs) ->
                    val node = doc.createElement("Layer"); node.setAttribute("type", type); attrs.forEach { (key, value) -> node.setAttribute(key, value) }
                    layerContainer(doc).appendChild(node); UUID.randomUUID().toString()
                }
                ids.addAll(addedIds)
                if (addedIds.isNotEmpty()) selected(addedIds) else selection
            }
            return primaryNames
        } catch (e: WatchDocumentException.AssetImportFailed) { throw e }
        catch (e: Exception) { throw WatchDocumentException.AssetImportFailed("导入失败，文档保持不变：${e.message}", e) }
    }

    fun select(id: String, visibleOrder: List<String>, ctrl: Boolean = false, shift: Boolean = false) {
        requireIds(setOf(id)); val order = visibleOrder.filter { it in state.layerIds }.distinct()
        if (id !in order) throw WatchDocumentException.InvalidCommand("选中图层不在当前可见顺序中。")
        val anchor = selection.anchor?.takeIf { it in order }
        val next = if (shift && anchor != null) {
            val a = order.indexOf(anchor); val b = order.indexOf(id); val range = order.subList(minOf(a,b), maxOf(a,b) + 1).toSet()
            LayerSelection(if (ctrl) selection.selected + range else range, id, anchor)
        } else if (ctrl) {
            val ids = if (id in selection.selected) selection.selected - id else selection.selected + id
            LayerSelection(ids, if (id in ids) id else order.lastOrNull { it in ids }, id)
        } else LayerSelection(setOf(id), id, id)
        updateSelection(next)
    }

    fun selectAll(ids: List<String>) { requireIds(ids.toSet()); updateSelection(selected(ids.distinct())) }
    fun clearSelection() = updateSelection(LayerSelection())

    override fun close() { if (!closed) { store.persist(this); closed = true } }
    internal fun ensureOpen() { if (closed) throw WatchDocumentException.ClosedSession() }

    internal fun materializeAssets(snapshot: DocumentSnapshot, previous: DocumentSnapshot = state, fullVerification: Boolean = false) {
        // Ordinary text/position edits must not reread or recopy large media.
        // Explicit recovery verifies all blobs; saves verify the exported blobs.
        val removed = snapshot.removedAssetKeys(); val previousRemoved = previous.removedAssetKeys()
        val visible = snapshot.importedAssets.filterKeys { it.lowercase(Locale.ROOT) !in removed }
        val previousVisible = previous.importedAssets.filterKeys { it.lowercase(Locale.ROOT) !in previousRemoved }
        val changed = (if (fullVerification) visible else visible.filter { (name, hash) -> previousVisible[name] != hash }).toMutableMap()
        // A deleted original asset has its immutable backup in the deleting
        // snapshot, even when the prior (undo) snapshot did not import that file.
        previous.removedAssets.orEmpty().filter { it.lowercase(Locale.ROOT) !in removed }.forEach { name ->
            val backup = snapshot.importedAssets.entries.firstOrNull { it.key.equals(name, true) }?.value
                ?: previous.importedAssets.entries.firstOrNull { it.key.equals(name, true) }?.value
                ?: throw WatchDocumentException.RecoveryFailed("删除素材的撤销备份缺失：$name")
            changed[name] = backup
        }
        changed.forEach { (name, hash) ->
            WatchDocumentStore.validateArchiveName(name)
            val from = home.resolve("blobs").resolve(hash)
            if (!Files.isRegularFile(from) || WatchDocumentStore.hashFile(from) != hash) throw WatchDocumentException.RecoveryFailed("草稿资源缺失或损坏：$name")
        }
        changed.forEach { (name, hash) ->
            val from = home.resolve("blobs").resolve(hash)
            copyWorkingAsset(from, name)
        }
        // Only imports introduced by this session are removable. Original archive
        // resources, including unknown and orphan files, never enter this set.
        val toRemove = (previousVisible.keys - visible.keys).filterNot { name -> name in changed } +
            (if (fullVerification) snapshot.removedAssets.orEmpty() else snapshot.removedAssets.orEmpty().filter { it.lowercase(Locale.ROOT) !in previousRemoved })
        toRemove.distinct().forEach { name ->
            Files.deleteIfExists(workingAssetPath(name))
        }
        if (fullVerification || snapshot.scriptOverride != previous.scriptOverride) {
            val script = workDir.resolve(scriptEntryName())
            val bytes = snapshot.scriptOverride?.toByteArray(Charsets.UTF_8) ?: originalScript()
            if (bytes == null) Files.deleteIfExists(script)
            else { Files.createDirectories(script.parent); Files.write(script, bytes) }
        }
    }

    private fun document(): Document { ensureOpen(); return WatchXml.parse(state.xml) }
    private fun requireIds(ids: Set<String>) { ensureOpen(); if (!state.layerIds.containsAll(ids)) throw WatchDocumentException.InvalidCommand("图层已不存在，请重新选择。") }
    private fun selected(ids: List<String>) = LayerSelection(ids.toSet(), ids.lastOrNull(), ids.firstOrNull())
    private fun layerContainer(doc: Document): Element = WatchXml.layers(doc).lastOrNull()?.parentNode as? Element
        ?: doc.documentElement.elements().firstOrNull { it.tagName.equals("Layers", true) } ?: doc.documentElement

    private fun updateSelection(next: LayerSelection) {
        ensureOpen(); if (next == selection) return
        val previous = state; state = state.copy(selection = next)
        try { store.persist(this) } catch (e: Exception) { state = previous; throw e }
    }

    private fun change(block: (Document, MutableList<String>, MutableMap<String, String>) -> Any?) {
        ensureOpen()
        val doc = document(); val ids = state.layerIds.toMutableList(); val assets = state.importedAssets.toMutableMap()
        val result = try { block(doc, ids, assets) } catch (e: WatchDocumentException) { throw e }
            catch (e: Exception) { throw WatchDocumentException.InvalidCommand("编辑内容无效：${e.message}", e) }
        val next = DocumentSnapshot(WatchXml.serialize(doc), ids, (result as? LayerSelection) ?: selection, assets, state.scriptOverride, state.removedAssets)
        commit(next)
    }

    private fun transaction(block: () -> Unit) {
        if (inTransaction) { block(); return }
        val previous = state
        inTransaction = true
        val next = try { block(); state } finally { state = previous; inTransaction = false }
        commit(next)
    }

    private fun commit(next: DocumentSnapshot) {
        if (next.xml == state.xml && next.importedAssets == state.importedAssets && next.layerIds == state.layerIds && next.scriptOverride == state.scriptOverride && next.removedAssetKeys() == state.removedAssetKeys()) return
        WatchDocumentStore.validateSnapshot(next)
        if (inTransaction) { state = next; return }
        val previous = state; val oldUndo = undoStack.toList(); val oldRedo = redoStack.toList(); val oldRevision = revision
        try {
            materializeAssets(next)
            undoStack.addLast(previous); while (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear(); state = next; revision++; store.persist(this)
        } catch (e: Exception) {
            state = previous; revision = oldRevision; undoStack.clear(); undoStack.addAll(oldUndo); redoStack.clear(); redoStack.addAll(oldRedo)
            try { materializeAssets(previous, next) } catch (restore: Exception) { e.addSuppressed(restore) }
            throw e
        }
    }

    private fun navigateHistory(from: ArrayDeque<DocumentSnapshot>, to: ArrayDeque<DocumentSnapshot>) {
        ensureOpen(); if (from.isEmpty()) return
        val previous = state; val next = from.last(); val oldRevision = revision
        try { materializeAssets(next) } catch (e: Exception) {
            try { materializeAssets(previous, next) } catch (restore: Exception) { e.addSuppressed(restore) }
            throw e
        }
        from.removeLast(); to.addLast(previous); state = next; revision++
        try { store.persist(this) } catch (e: Exception) {
            state = previous; revision = oldRevision; to.removeLast(); from.addLast(next)
            try { materializeAssets(previous, next) } catch (restore: Exception) { e.addSuppressed(restore) }
            throw e
        }
    }

    private fun offset(raw: String, delta: Double): String {
        val numeric = raw.toDoubleOrNull()
        return if (numeric != null && numeric.isFinite()) number(numeric + delta) else "($raw)+(${number(delta)})"
    }
    private fun number(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun workingAssetPath(name: String): Path {
        WatchDocumentStore.validateArchiveName(name)
        val root = workDir.toRealPath()
        val path = workDir.resolve(name).normalize()
        var existing = path
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
        if (!existing.toRealPath().startsWith(root)) throw WatchDocumentException.InvalidCommand("素材路径超出当前工作区，操作已停止：$name")
        return path
    }

    private fun copyWorkingAsset(from: Path, name: String) {
        val destination = workingAssetPath(name); Files.createDirectories(destination.parent)
        val temp = Files.createTempFile(destination.parent, ".asset-", ".tmp")
        try {
            Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { runCatching { Files.deleteIfExists(temp) } }
    }

    private fun originalScript(): ByteArray? {
        val archive = home.resolve("original.zip")
        if (!Files.isRegularFile(archive)) return null
        return java.util.zip.ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().firstOrNull { it.name.equals("scripts/script.txt", true) }?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
        }
    }

    private fun scriptEntryName(): String {
        val archive = home.resolve("original.zip")
        if (!Files.isRegularFile(archive)) return "scripts/script.txt"
        return java.util.zip.ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().firstOrNull { it.name.equals("scripts/script.txt", true) }?.name ?: "scripts/script.txt"
        }
    }
}
