package com.ailife.wftools.pc.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Owns immutable source copies and durable per-document drafts. No source writes occur on open. */
class WatchDocumentStore(val workspaceRoot: Path = defaultWorkspace()) {
    private val gson = Gson()
    private val snapshotGson = GsonBuilder().disableHtmlEscaping().create()
    init { ensurePrivateDirectory(workspaceRoot) }

    fun open(path: Path): DocumentSession {
        val source = path.toAbsolutePath().normalize()
        val id = UUID.randomUUID().toString()
        val home = workspaceRoot.resolve(id)
        try {
            if (!Files.isRegularFile(source)) throw WatchDocumentException.OpenFailed("找不到表盘文件：$source")
            ensurePrivateDirectory(home)
            Files.createDirectories(home.resolve("content"))
            val before = hashFile(source)
            Files.copy(source, home.resolve("original.zip"))
            if (before != hashFile(source) || before != hashFile(home.resolve("original.zip")))
                throw WatchDocumentException.ExternalModification()
            val entry = extract(home.resolve("original.zip"), home.resolve("content"))
            val xml = Files.readString(home.resolve("content").resolve(entry), Charsets.UTF_8).removePrefix("\uFEFF")
            val document = WatchXml.parse(xml); WatchXml.validate(document)
            val snapshot = DocumentSnapshot(WatchXml.serialize(document), WatchXml.layers(document).map { UUID.randomUUID().toString() })
            return DocumentSession(this, id, source, home, entry, before, snapshot).also {
                it.savedSignature = signature(snapshot)
                persist(it)
            }
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.OpenFailed("无法打开表盘：${e.message}", e) }
    }

    fun newDocument(defaultTitle: String = "新建表盘"): DocumentSession {
        try {
            val id = UUID.randomUUID().toString(); val home = workspaceRoot.resolve(id)
            ensurePrivateDirectory(home)
            Files.createDirectories(home.resolve("content"))
            val safeTitle = defaultTitle.ifBlank { "新建表盘" }.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
            val xml = """<?xml version="1.0" encoding="UTF-8"?><Watch name="$safeTitle" width="512" height="512" shape="S" bg_color="000000" protection="n"><Layers/></Watch>"""
            val state = DocumentSnapshot(WatchXml.serialize(WatchXml.parse(xml)), emptyList())
            return DocumentSession(this, id, null, home, "watch.xml", null, state).also { persist(it) }
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.OpenFailed("无法创建表盘工作区：${e.message}", e) }
    }

    fun save(session: DocumentSession, target: Path = session.sourcePath
        ?: throw WatchDocumentException.SaveFailed("新建表盘需要选择另存为路径。"),
        derivedResources: Map<String, ByteArray> = emptyMap()) {
        session.ensureOpen()
        val absolute = target.toAbsolutePath().normalize()
        var temp: Path? = null
        try {
            if (absolute.startsWith(workspaceRoot.toAbsolutePath().normalize()))
                throw WatchDocumentException.SaveFailed("请选择会话工作区以外的保存位置。")
            val sameSource = session.sourcePath?.toAbsolutePath()?.normalize() == absolute
            if (sameSource) verifySource(session)
            if (!session.hasUnsavedChanges && sameSource) return
            // Derived previews are save outputs, not editing commands. Clean Save
            // As also ignores them to retain the latest source archive byte-for-byte.
            val prepared = if (session.hasUnsavedChanges) prepareDerivedResources(session, derivedResources) else emptyMap()
            val derived = session.savedDerivedResources + prepared
            val previousTargetHash = if (Files.exists(absolute)) {
                if (!Files.isRegularFile(absolute)) throw WatchDocumentException.SaveFailed("保存位置不是普通文件：$absolute")
                hashFile(absolute)
            } else null
            val parent = absolute.parent ?: throw WatchDocumentException.SaveFailed("无效的保存路径。")
            Files.createDirectories(parent)
            temp = Files.createTempFile(parent, ".wftools-", ".watch.tmp")
            val cleanSource = session.sourcePath?.takeIf {
                !session.hasUnsavedChanges && Files.isRegularFile(it) && hashFile(it) == session.sourceHash
            }
            if (cleanSource != null) Files.copy(cleanSource, temp, REPLACE_EXISTING)
            else writeArchive(session, temp, derived)
            // Re-open and consume every entry before committing, checking XML and CRC/structure.
            validateArchive(temp)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            if (sameSource) verifySource(session)
            val currentTargetHash = if (Files.isRegularFile(absolute)) hashFile(absolute) else null
            if (currentTargetHash != previousTargetHash || (Files.exists(absolute) && !Files.isRegularFile(absolute)))
                throw WatchDocumentException.ExternalModification()
            val newHash = hashFile(temp)
            // Deliberately no non-atomic fallback: an unsupported filesystem leaves
            // the original untouched and reports a recoverable save error.
            Files.move(temp, absolute, ATOMIC_MOVE, REPLACE_EXISTING)
            temp = null
            session.sourcePath = absolute
            session.sourceHash = newHash
            session.savedSignature = signature(session.state)
            session.savedDerivedResources = derived
            // Source commit succeeded. A draft failure is reported separately and
            // never misrepresented as a failed or rolled-back source save.
            try {
                persist(session, verifySnapshots = true)
                materializeDerivedResources(session)
                session.lastDraftError = null
            } catch (e: Exception) { session.lastDraftError = "表盘已保存，但恢复草稿或缩略图工作副本更新失败：${e.message}" }
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.SaveFailed("保存失败，原文件保持不变：${e.message}", e) }
        finally { temp?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} } }
    }

    /** Export an independent protected archive without committing any editing or draft state. */
    fun exportProtected(session: DocumentSession, target: Path, derivedResources: Map<String, ByteArray> = emptyMap()) {
        exportProtected(session, target, derivedResources) { }
    }

    /** Commit seam for deterministic filesystem-race regression tests. */
    internal fun exportProtected(session: DocumentSession, target: Path, derivedResources: Map<String, ByteArray>, beforeCommit: (Path) -> Unit) {
        session.ensureOpen()
        val absolute = target.toAbsolutePath().normalize()
        var plain: Path? = null
        var protected: Path? = null
        try {
            if (absolute.startsWith(workspaceRoot.toAbsolutePath().normalize()) ||
                session.sourcePath?.let { source -> source.toAbsolutePath().normalize() == absolute ||
                    (Files.exists(absolute) && Files.exists(source) && Files.isSameFile(source, absolute)) } == true)
                throw WatchDocumentException.SaveFailed("受保护导出必须选择源文件及会话工作区以外的新位置。")
            val parent = absolute.parent ?: throw WatchDocumentException.SaveFailed("无效的导出路径。")
            Files.createDirectories(parent)
            if (Files.exists(workspaceRoot) && parent.toRealPath().startsWith(workspaceRoot.toRealPath()))
                throw WatchDocumentException.SaveFailed("不能通过目录链接导出到会话工作区。")
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS))
                throw WatchDocumentException.SaveFailed("受保护导出目标已存在，请选择新的文件名：$absolute")
            val prepared = validateDerivedResources(derivedResources)
            plain = Files.createTempFile(parent, ".wftools-plain-", ".watch.tmp")
            protected = Files.createTempFile(parent, ".wftools-protected-", ".watch.tmp")
            writeArchive(session, plain, session.savedDerivedResources)
            validateArchive(plain)
            val expected = ProtectedWatchArchive.write(plain, protected, session.state.xml, prepared, session.state.scriptOverride?.isBlank() == true)
            validateArchive(protected, allowProtected = true)
            ProtectedWatchArchive.validate(protected, expected)
            FileChannel.open(protected, StandardOpenOption.WRITE).use { it.force(true) }
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS))
                throw WatchDocumentException.SaveFailed("导出期间目标已被创建，请选择新的文件名：$absolute")
            beforeCommit(absolute)
            // Windows ATOMIC_MOVE may replace an existing target despite omitting
            // REPLACE_EXISTING. CreateHardLink instead atomically publishes the
            // already-forced file under a new name and cannot replace any entry.
            // Both names are on the same volume; unsupported filesystems fail closed.
            try { Files.createLink(absolute, protected) }
            catch (e: FileAlreadyExistsException) {
                throw WatchDocumentException.SaveFailed("目标文件已被其他程序创建，已保留其内容。请换一个导出文件名：$absolute", e)
            } catch (e: Exception) {
                throw WatchDocumentException.SaveFailed("此位置无法安全创建独立导出文件，请选择支持硬链接的本地 NTFS 目录：${e.message}", e)
            }
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.SaveFailed("受保护导出失败，原文件保持不变：${e.message}", e) }
        finally { listOfNotNull(plain, protected).forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    fun listRecoveryDrafts(): List<RecoveryDraft> {
        if (!Files.isDirectory(workspaceRoot)) return emptyList()
        return Files.list(workspaceRoot).use { homes ->
            homes.filter { Files.isRegularFile(it.resolve("draft.json")) && !Files.exists(it.resolve("discarded")) }.map { home ->
                try {
                    val journal = readJournal(home, includeHistory = false)
                    if (journal.savedSignature == signature(journal.state)) null
                    else RecoveryDraft(journal.id, WatchXml.parse(journal.state.xml).documentElement.getAttribute("name"),
                        journal.sourcePath?.let { Path.of(it) }, Instant.ofEpochMilli(journal.updatedAt))
                } catch (_: Exception) { null }
            }.filter { it != null }.map { it!! }.toList().sortedByDescending { it.updatedAt }
        }
    }

    fun recover(id: String): DocumentSession {
        try {
            UUID.fromString(id)
            val home = workspaceRoot.resolve(id)
            ensurePrivateDirectory(home)
            if (Files.exists(home.resolve("discarded"))) throw WatchDocumentException.RecoveryFailed("此草稿已被放弃。请重新打开源表盘开始新的编辑。")
            val knownHashes = java.util.IdentityHashMap<DocumentSnapshot, String>()
            val journal = readJournal(home, knownHashes = knownHashes)
            if (journal.version !in setOf(1, 2) || journal.id != id) throw WatchDocumentException.RecoveryFailed("草稿版本或会话标识无效。")
            validateSnapshot(journal.state)
            val original = home.resolve("original.zip")
            if (journal.baseArchiveHash != null && (!Files.isRegularFile(original) || hashFile(original) != journal.baseArchiveHash))
                throw WatchDocumentException.RecoveryFailed("原始表盘工作副本缺失或损坏；为避免丢失资源，无法恢复此草稿。")
            // A crash can leave content ahead of the committed journal. Rebuild a
            // fresh working tree from the immutable archive, then apply only the
            // durable snapshot. The old tree is retained for diagnosis.
            val recoveredContent = Files.createTempDirectory(home, "recovered-content-")
            if (Files.isRegularFile(original)) extract(original, recoveredContent)
            val session = DocumentSession(this, id, journal.sourcePath?.let { Path.of(it) }, home, journal.xmlEntry,
                journal.sourceHash, journal.state, recoveredContent, journal.baseArchiveHash)
            session.savedDerivedResources = journal.derivedResources.orEmpty()
            validateDerivedHashes(session.savedDerivedResources)
            session.savedSignature = journal.savedSignature; session.revision = journal.revision
            session.snapshotHashes.putAll(knownHashes)
            session.committedSnapshotRefs = knownHashes.values.toSet()
            session.undoStack.addAll(journal.undo.takeLast(50)); session.redoStack.addAll(journal.redo.takeLast(50))
            session.materializeAssets(journal.state, fullVerification = true)
            materializeDerivedResources(session)
            return session
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.RecoveryFailed("无法恢复草稿：${e.message}", e) }
    }

    /** Hides a discarded draft without deleting its workspace or touching its source. */
    fun discardRecovery(id: String) {
        try {
            UUID.fromString(id)
            val home = workspaceRoot.resolve(id)
            if (!Files.isRegularFile(home.resolve("draft.json"))) throw WatchDocumentException.RecoveryFailed("找不到此恢复草稿。")
            val temp = Files.createTempFile(home, ".discard-", ".tmp")
            try {
                Files.writeString(temp, "Discarded ${Instant.now()}")
                FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(temp, home.resolve("discarded"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.RecoveryFailed("无法标记放弃草稿：${e.message}", e) }
    }

    internal fun persist(session: DocumentSession, verifySnapshots: Boolean = false) {
        var temp: Path? = null
        var committed = false
        try {
            // Only a new snapshot is serialized. Known object identities reuse
            // their hashes without re-reading or serializing all fifty XML trees.
            fun reference(snapshot: DocumentSnapshot) = writeSnapshot(session, snapshot, verifySnapshots)
            val journal = ReferencedDraftJournal(id = session.id, sourcePath = session.sourcePath?.toString(), sourceHash = session.sourceHash,
                baseArchiveHash = session.baseArchiveHash, savedSignature = session.savedSignature, revision = session.revision, xmlEntry = session.xmlEntry,
                stateRef = reference(session.state), undoRefs = session.undoStack.map(::reference), redoRefs = session.redoStack.map(::reference),
                derivedResources = session.savedDerivedResources)
            temp = Files.createTempFile(session.home, ".draft-", ".tmp")
            Files.writeString(temp, gson.toJson(journal), Charsets.UTF_8)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, session.home.resolve("draft.json"), ATOMIC_MOVE, REPLACE_EXISTING)
            committed = true
            val retained = (listOf(journal.stateRef) + journal.undoRefs + journal.redoRefs).toSet()
            session.pendingSnapshotGc.addAll((session.committedSnapshotRefs + session.snapshotHashes.values) - retained)
            session.committedSnapshotRefs = retained
            // The journal is already committed. Cleanup must never turn success
            // into an exception that rolls the editor back behind the disk state.
            runCatching { collectSnapshots(session, retained, verifySnapshots) }
        } catch (e: Exception) {
            throw WatchDocumentException.RecoveryFailed("无法写入恢复草稿；本次编辑未提交：${e.message}", e)
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
            if (!committed) session.pendingSnapshotGc.addAll(session.snapshotHashes.values - session.committedSnapshotRefs)
            val live = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DocumentSnapshot, Boolean>())
            live.add(session.state); live.addAll(session.undoStack); live.addAll(session.redoStack)
            session.snapshotHashes.keys.retainAll(live)
        }
    }

    private fun writeSnapshot(session: DocumentSession, snapshot: DocumentSnapshot, verify: Boolean): String {
        val directory = session.home.resolve("states")
        val known = session.snapshotHashes[snapshot]
        if (known != null && Files.isRegularFile(directory.resolve("$known.json")) && (!verify || hashFile(directory.resolve("$known.json")) == known)) return known
        val bytes = snapshotGson.toJson(snapshot).toByteArray(Charsets.UTF_8)
        val hash = hashBytes(bytes)
        Files.createDirectories(directory)
        val destination = directory.resolve("$hash.json")
        if (!Files.isRegularFile(destination) || hashFile(destination) != hash) {
            val temp = Files.createTempFile(directory, ".state-", ".tmp")
            try {
                Files.write(temp, bytes)
                FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(temp, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        }
        session.snapshotHashes[snapshot] = hash
        return hash
    }

    private fun collectSnapshots(session: DocumentSession, retained: Set<String>, fullScan: Boolean) {
        val directory = session.home.resolve("states")
        session.snapshotGcCommits++
        if (session.snapshotGcNeedsScan || fullScan || session.snapshotGcCommits >= 32) {
            Files.list(directory).use { paths -> paths.forEach { path ->
                val name = path.fileName.toString()
                if (STATE_FILE.matches(name) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    val hash = name.removeSuffix(".json")
                    if (hash !in retained) session.pendingSnapshotGc.add(hash)
                }
            } }
            session.snapshotGcNeedsScan = false; session.snapshotGcCommits = 0
        }
        val iterator = session.pendingSnapshotGc.iterator()
        while (iterator.hasNext()) {
            val hash = iterator.next()
            if (hash in retained || !STATE_HASH.matches(hash)) { iterator.remove(); continue }
            val path = directory.resolve("$hash.json")
            try {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path)
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) iterator.remove()
            } catch (_: Exception) { /* Keep queued for a later successful commit. */ }
        }
    }

    private fun readJournal(home: Path, includeHistory: Boolean = true, knownHashes: MutableMap<DocumentSnapshot, String>? = null): DraftJournal {
        val text = Files.readString(home.resolve("draft.json"))
        val root = JsonParser.parseString(text).asJsonObject
        val version = root.get("version")?.asInt ?: 1
        if (version == 1) {
            val legacy = gson.fromJson(text, DraftJournal::class.java)
            validateSnapshot(legacy.state)
            if (includeHistory) (legacy.undo + legacy.redo).forEach(::validateSnapshot)
            return legacy
        }
        if (version != 2) throw WatchDocumentException.RecoveryFailed("不支持此恢复草稿版本：$version")
        val refs = gson.fromJson(text, ReferencedDraftJournal::class.java)
        if (refs.undoRefs.size > 50 || refs.redoRefs.size > 50) throw WatchDocumentException.RecoveryFailed("草稿历史索引超过允许范围。")
        val loaded = HashMap<String, DocumentSnapshot>()
        fun load(hash: String): DocumentSnapshot = loaded.getOrPut(hash) {
            if (!STATE_HASH.matches(hash)) throw WatchDocumentException.RecoveryFailed("草稿快照引用无效。")
            val path = home.resolve("states").resolve("$hash.json")
            if (!Files.isRegularFile(path) || Files.size(path) > 512L * 1024 * 1024) throw WatchDocumentException.RecoveryFailed("草稿快照缺失或过大：$hash")
            val bytes = Files.readAllBytes(path)
            if (hashBytes(bytes) != hash) throw WatchDocumentException.RecoveryFailed("草稿快照校验失败，恢复已停止：$hash")
            val snapshot = snapshotGson.fromJson(bytes.toString(Charsets.UTF_8), DocumentSnapshot::class.java)
            validateSnapshot(snapshot)
            knownHashes?.put(snapshot, hash)
            snapshot
        }
        return DraftJournal(version = 2, id = refs.id, sourcePath = refs.sourcePath, sourceHash = refs.sourceHash, baseArchiveHash = refs.baseArchiveHash,
            savedSignature = refs.savedSignature, revision = refs.revision, xmlEntry = refs.xmlEntry, state = load(refs.stateRef),
            undo = if (includeHistory) refs.undoRefs.map(::load) else emptyList(), redo = if (includeHistory) refs.redoRefs.map(::load) else emptyList(),
            derivedResources = refs.derivedResources, updatedAt = refs.updatedAt)
    }

    private fun verifySource(session: DocumentSession) {
        val path = session.sourcePath ?: return
        if (!Files.isRegularFile(path) || session.sourceHash != hashFile(path)) throw WatchDocumentException.ExternalModification()
    }

    private fun extract(archive: Path, content: Path): String {
        val xmlEntry = validateArchive(archive)
        ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val destination = content.resolve(entry.name).normalize()
                if (entry.isDirectory) Files.createDirectories(destination)
                else {
                    Files.createDirectories(destination.parent)
                    zip.getInputStream(entry).use { Files.copy(it, destination) }
                }
            }
        }
        return xmlEntry
    }

    internal fun validateArchive(archive: Path, allowProtected: Boolean = false): String {
        try {
            ZipFile(archive.toFile()).use { zip ->
                val names = HashSet<String>(); var total = 0L; var xmlEntry: String? = null
                val entries = zip.entries().asSequence().toList()
                if (entries.size > 50_000) throw WatchDocumentException.UnsafeArchive("压缩包条目过多，无法安全打开。")
                if (!allowProtected && entries.any { it.name.equals("watch.pxml", true) }) throw WatchDocumentException.UnsupportedProtection()
                entries.forEach { entry ->
                    validateArchiveName(entry.name)
                    if (!names.add(entry.name.trimEnd('/').lowercase(Locale.ROOT)))
                        throw WatchDocumentException.UnsafeArchive("压缩包含重复或大小写冲突路径：${entry.name}")
                    if (!entry.isDirectory) {
                        var size = 0L
                        val crc = CRC32()
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                crc.update(buffer, 0, n)
                                size += n; total += n
                                if (size > 256L * 1024 * 1024 || total > 1024L * 1024 * 1024)
                                    throw WatchDocumentException.UnsafeArchive("表盘解压大小超过安全限制。")
                            }
                        }
                        if ((entry.crc >= 0 && crc.value != entry.crc) || (entry.size >= 0 && size != entry.size))
                            throw WatchDocumentException.InvalidArchive("压缩包资源校验失败：${entry.name}。文件可能已损坏，请重新复制或下载表盘。")
                    }
                    if (entry.name.equals("watch.xml", true)) xmlEntry = entry.name
                }
                val name = xmlEntry ?: throw WatchDocumentException.InvalidDocument("压缩包根目录缺少 watch.xml。")
                val xml = zip.getInputStream(zip.getEntry(name)).use { it.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF") }
                WatchXml.validate(WatchXml.parse(xml))
                return name
            }
        } catch (e: WatchDocumentException) { throw e }
        catch (e: Exception) { throw WatchDocumentException.InvalidArchive("ZIP 数据无效或不完整，请重新复制或下载表盘：${e.message}", e) }
    }

    private fun writeArchive(session: DocumentSession, target: Path, derived: Map<String, String>) {
        val original = session.home.resolve("original.zip")
        if (session.baseArchiveHash != null && (!Files.isRegularFile(original) || hashFile(original) != session.baseArchiveHash))
            throw WatchDocumentException.SaveFailed("原始表盘工作副本缺失或损坏，无法安全保留原有资源。请恢复工作副本或重新打开源表盘。")
        validateDerivedHashes(derived)
        val removed = session.state.removedAssetKeys()
        val written = HashSet<String>()
        ZipOutputStream(Files.newOutputStream(target)).use { out ->
            if (Files.isRegularFile(original)) ZipFile(original.toFile()).use { zip ->
                zip.comment?.let { out.setComment(it) }
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name.lowercase(Locale.ROOT) in removed) return@forEach
                    val copy = ZipEntry(entry.name).apply { time = entry.time; comment = entry.comment; extra = entry.extra }
                    out.putNextEntry(copy)
                    if (entry.name == session.xmlEntry) out.write(session.state.xml.toByteArray(Charsets.UTF_8))
                    else if (entry.name.equals("scripts/script.txt", true) && session.state.scriptOverride != null)
                        out.write(session.state.scriptOverride!!.toByteArray(Charsets.UTF_8))
                    else if (entry.name.lowercase(Locale.ROOT) in derived)
                        writeBlob(session, derived.getValue(entry.name.lowercase(Locale.ROOT)), entry.name, out)
                    else if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry(); written.add(entry.name.lowercase(Locale.ROOT))
                }
            }
            if (session.xmlEntry.lowercase(Locale.ROOT) !in written) {
                out.putNextEntry(ZipEntry(session.xmlEntry)); out.write(session.state.xml.toByteArray(Charsets.UTF_8)); out.closeEntry()
            }
            if (session.state.scriptOverride != null && "scripts/script.txt" !in written) {
                out.putNextEntry(ZipEntry("scripts/script.txt")); out.write(session.state.scriptOverride!!.toByteArray(Charsets.UTF_8)); out.closeEntry()
            }
            session.state.importedAssets.forEach { (name, blob) ->
                if (name.lowercase(Locale.ROOT) in removed) return@forEach
                if (!written.add(name.lowercase(Locale.ROOT))) throw WatchDocumentException.SaveFailed("资源名冲突：$name")
                validateArchiveName(name)
                val blobPath = session.home.resolve("blobs").resolve(blob)
                if (!Files.isRegularFile(blobPath) || hashFile(blobPath) != blob)
                    throw WatchDocumentException.SaveFailed("导入资源缺失或损坏，请撤销后重新导入：$name")
                out.putNextEntry(ZipEntry(name))
                Files.newInputStream(blobPath).use { it.copyTo(out) }
                out.closeEntry()
            }
            derived.forEach { (name, hash) ->
                if (written.add(name.lowercase(Locale.ROOT))) {
                    out.putNextEntry(ZipEntry(name)); writeBlob(session, hash, name, out); out.closeEntry()
                }
            }
        }
    }

    /** Force blob bytes to disk before a draft or completed save can refer to them. */
    internal fun stageBlob(home: Path, bytes: ByteArray): String {
        val hash = hashBytes(bytes); val folder = home.resolve("blobs"); val destination = folder.resolve(hash)
        Files.createDirectories(folder)
        if (Files.isRegularFile(destination) && hashFile(destination) == hash) return hash
        val temp = Files.createTempFile(folder, ".blob-", ".tmp")
        try {
            Files.write(temp, bytes)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temp, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        return hash
    }

    /** Streaming staging for large clipboard media, independent of the source document lifetime. */
    internal fun stageBlob(home: Path, input: InputStream, maximumBytes: Long): String {
        val folder = home.resolve("blobs"); Files.createDirectories(folder)
        val temp = Files.createTempFile(folder, ".blob-", ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
            Files.newOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    size += count
                    if (size > maximumBytes) throw WatchDocumentException.AssetImportFailed("资源超过允许的大小。")
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = folder.resolve(hash)
            if (!Files.isRegularFile(destination) || hashFile(destination) != hash) Files.move(temp, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            return hash
        } finally { Files.deleteIfExists(temp) }
    }

    private fun prepareDerivedResources(session: DocumentSession, input: Map<String, ByteArray>): Map<String, String> {
        return validateDerivedResources(input).mapValues { (_, bytes) -> stageBlob(session.home, bytes) }
    }

    private fun validateDerivedResources(input: Map<String, ByteArray>): Map<String, ByteArray> {
        val prepared = LinkedHashMap<String, ByteArray>()
        input.forEach { (name, bytes) ->
            val key = name.lowercase(Locale.ROOT)
            if (key !in DERIVED_NAMES || key in prepared)
                throw WatchDocumentException.SaveFailed("派生资源仅允许唯一的 preview.jpg 和 preview_dim.jpg。")
            if (bytes.isEmpty() || bytes.size > 16 * 1024 * 1024)
                throw WatchDocumentException.SaveFailed("缩略图数据为空或超过 16 MB：$name")
            val snapshot = bytes.copyOf()
            try {
                MemoryCacheImageInputStream(ByteArrayInputStream(snapshot)).use { stream ->
                    val readers = ImageIO.getImageReaders(stream)
                    if (!readers.hasNext()) throw IllegalArgumentException("无法解码图像")
                    val reader = readers.next()
                    try {
                        reader.input = stream
                        if (!reader.formatName.equals("JPEG", true) || reader.getWidth(0) !in 1..4096 || reader.getHeight(0) !in 1..4096)
                            throw IllegalArgumentException("必须为不超过 4096×4096 的 JPEG")
                        reader.read(0) ?: throw IllegalArgumentException("图像内容不完整")
                    } finally { reader.dispose() }
                }
            } catch (e: Exception) { throw WatchDocumentException.SaveFailed("缩略图校验失败：$name，${e.message}", e) }
            prepared[key] = snapshot
        }
        return prepared
    }

    private fun validateDerivedHashes(derived: Map<String, String>) {
        if (derived.any { (name, hash) -> name !in DERIVED_NAMES || !Regex("[a-f0-9]{64}").matches(hash) })
            throw WatchDocumentException.RecoveryFailed("派生缩略图索引无效。")
    }

    private fun writeBlob(session: DocumentSession, hash: String, name: String, out: OutputStream) {
        val blob = session.home.resolve("blobs").resolve(hash)
        if (!Files.isRegularFile(blob) || hashFile(blob) != hash)
            throw WatchDocumentException.SaveFailed("派生缩略图缺失或损坏，请重新生成后保存：$name")
        Files.newInputStream(blob).use { it.copyTo(out) }
    }

    private fun materializeDerivedResources(session: DocumentSession) {
        if (session.savedDerivedResources.isEmpty()) return
        val original = session.home.resolve("original.zip")
        val names = if (Files.isRegularFile(original)) ZipFile(original.toFile()).use { zip ->
            zip.entries().asSequence().associate { it.name.lowercase(Locale.ROOT) to it.name }
        } else emptyMap()
        session.savedDerivedResources.forEach { (key, hash) ->
            val blob = session.home.resolve("blobs").resolve(hash)
            if (!Files.isRegularFile(blob) || hashFile(blob) != hash) throw WatchDocumentException.RecoveryFailed("派生缩略图缺失或损坏：$key")
            val destination = session.workDir.resolve(names[key] ?: key)
            Files.copy(blob, destination, REPLACE_EXISTING)
        }
    }

    companion object {
        private val STATE_HASH = Regex("[a-f0-9]{64}")
        private val STATE_FILE = Regex("[a-f0-9]{64}\\.json")
        private val DERIVED_NAMES = setOf("preview.jpg", "preview_dim.jpg")
        internal fun defaultWorkspace(
            environment:Map<String,String> = System.getenv(),
            userHome:Path = Path.of(System.getProperty("user.home")),
            osName:String = System.getProperty("os.name").orEmpty(),
        ):Path = if(osName.lowercase(Locale.ROOT).contains("linux")) {
            val data=environment["XDG_DATA_HOME"]?.takeIf{it.startsWith('/')&&it.isNotBlank()}?.let(Path::of)?:userHome.resolve(".local/share")
            data.resolve("WFTools-PC/workspace")
        } else Path.of(environment["LOCALAPPDATA"] ?: userHome.toString(),"WFTools-PC","workspace")
        private fun ensurePrivateDirectory(path:Path) {
            Files.createDirectories(path)
            if(System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("linux")&&
                runCatching{Files.getFileStore(path).supportsFileAttributeView("posix")}.getOrDefault(false))
                Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"))
        }
        internal fun signature(snapshot: DocumentSnapshot): String = hashBytes((snapshot.xml + "\u0000" +
            snapshot.importedAssets.toSortedMap().entries.joinToString("\u0000") { "${it.key}=${it.value}" } +
            "\u0000script:" + (snapshot.scriptOverride?.let { "override:$it" } ?: "original") +
            snapshot.removedAssetKeys().takeIf { it.isNotEmpty() }?.sorted()?.joinToString("\u0000", prefix = "\u0000removed:").orEmpty()).toByteArray(Charsets.UTF_8))
        internal fun hashBytes(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        internal fun hashFile(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input -> val buffer = ByteArray(64 * 1024); while (true) {
                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun validateArchiveName(name: String) {
            val parts = name.trimEnd('/').split('/')
            val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")
            if (name.isBlank() || name.startsWith('/') || name.contains('\\') || parts.any { part ->
                    part.isEmpty() || part == "." || part == ".." || part.last() in " ." || reserved.matches(part) ||
                    part.any { it.code < 32 || it in ":*?\"<>|" }
                }) throw WatchDocumentException.UnsafeArchive("压缩包路径不安全或不适用于 Windows：$name")
        }
        internal fun validateSnapshot(state: DocumentSnapshot) {
            val doc = WatchXml.parse(state.xml); WatchXml.validate(doc)
            if (WatchXml.layers(doc).size != state.layerIds.size || state.layerIds.toSet().size != state.layerIds.size)
                throw WatchDocumentException.RecoveryFailed("草稿图层索引不完整。")
            state.importedAssets.forEach { (name, blob) -> validateArchiveName(name)
                if (!Regex("[a-f0-9]{64}").matches(blob)) throw WatchDocumentException.RecoveryFailed("草稿资源索引无效。")
            }
            state.removedAssets.orEmpty().forEach { name ->
                validateArchiveName(name)
                if (!name.contains('/') || name.substringBefore('/').lowercase(Locale.ROOT) !in MATERIAL_FOLDERS)
                    throw WatchDocumentException.RecoveryFailed("草稿包含不允许删除的非素材路径：$name")
            }
        }
        internal val MATERIAL_FOLDERS = setOf("images", "videos", "fonts", "fonts_bm", "sfx", "sounds")
    }
}
