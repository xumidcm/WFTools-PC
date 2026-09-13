package com.ailife.wftools.agent.workspace

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.ailife.wftools.agent.provider.AgentJsonParser
import com.ailife.wftools.agent.provider.AgentJsonValue
import com.ailife.wftools.agent.provider.AgentJsonWriter

enum class ImportResult {
    IMPORTED,
    QUOTA_EXCEEDED,
    SOURCE_NOT_FOUND,
    INVALID_SOURCE,
    IO_ERROR,
}

enum class ExportResult {
    EXPORTED,
    WORKSPACE_NOT_FOUND,
    INVALID_TARGET,
    UNSUPPORTED_SYMLINK,
    IO_ERROR,
}

/** Owns only managed workspace paths; it never invokes a shell or follows external SAF paths. */
class WorkspaceManager private constructor(
    root: Path,
    private val defaultQuotaBytes: Long,
    private val rootfsBackupCleaner: ((Path) -> Unit)?,
) {
    data class DirectoryEntry(val relativePath: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long)
    constructor(
        root: Path,
        defaultQuotaBytes: Long = DEFAULT_QUOTA_BYTES,
    ) : this(root, defaultQuotaBytes, rootfsBackupCleaner = null)

    internal constructor(
        root: Path,
        rootfsBackupCleaner: (Path) -> Unit,
    ) : this(root, DEFAULT_QUOTA_BYTES, rootfsBackupCleaner)

    val runtimeRoot: Path = root.toAbsolutePath().normalize()
    private val workspacesRoot: Path = runtimeRoot.resolve("workspaces")
    private val bindingsFile: Path = runtimeRoot.resolve("bindings.json")

    init {
        require(defaultQuotaBytes >= 0L) { "Default workspace quota cannot be negative" }
        Files.createDirectories(workspacesRoot)
    }

    @Synchronized
    fun create(
        displayName: String,
        quotaBytes: Long = defaultQuotaBytes,
        watchScope: String? = null,
    ): AgentWorkspace {
        require(displayName.isNotBlank()) { "Workspace name cannot be blank" }
        require(quotaBytes >= 0L) { "Workspace quota cannot be negative" }
        val id = UUID.randomUUID().toString()
        val paths = WorkspacePaths(workspacesRoot.resolve(id))
        Files.createDirectories(paths.root)
        listOf(paths.linux, paths.home, paths.workspace, paths.generated, paths.previews, paths.face)
            .forEach(Files::createDirectories)
        val workspace = AgentWorkspace(
            id = id,
            displayName = displayName.trim(),
            watchScope = watchScope?.takeIf { it.isNotBlank() },
            quota = WorkspaceQuota(quotaBytes),
            paths = paths,
        )
        writeMetadata(workspace)
        if (!watchScope.isNullOrBlank()) bind(watchScope, id)
        return get(id) ?: error("Created workspace disappeared")
    }

    fun createWorkspace(displayName: String, quotaBytes: Long = defaultQuotaBytes): AgentWorkspace =
        create(displayName, quotaBytes)

    @Synchronized
    fun rename(workspaceId: String, displayName: String): AgentWorkspace {
        require(displayName.isNotBlank()) { "Workspace name cannot be blank" }
        val current = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        writeMetadata(current.copy(displayName = displayName.trim()))
        return get(workspaceId) ?: error("Renamed workspace disappeared")
    }

    @Synchronized
    fun delete(workspaceId: String): Boolean {
        val paths = pathsFor(workspaceId) ?: return false
        if (!Files.isDirectory(paths.root)) return false
        val bindings = readBindings().filterValues { it != workspaceId }
        writeBindings(bindings)
        deleteRecursively(paths.root)
        return !Files.exists(paths.root)
    }

    @Synchronized
    fun get(workspaceId: String): AgentWorkspace? {
        val paths = pathsFor(workspaceId) ?: return null
        if (!Files.isRegularFile(paths.metadata)) return null
        return runCatching {
            val workspace = decodeMetadata(String(Files.readAllBytes(paths.metadata), Charsets.UTF_8), paths)
            workspace.copy(
                quota = workspace.quota.withUsage(calculateUsage(paths.root)),
                binding = firstBindingFor(workspace.id),
            )
        }.getOrNull()
    }

    fun workspace(workspaceId: String): AgentWorkspace? = get(workspaceId)

    fun resolveInside(workspaceId: String, relativePath: String): Path {
        val workspace = get(workspaceId) ?: throw WorkspacePathViolation("Unknown workspace: $workspaceId")
        return WorkspacePathPolicy.resolveInside(workspace.paths.root, relativePath)
    }

    @Synchronized
    fun list(): List<AgentWorkspace> {
        val result = mutableListOf<AgentWorkspace>()
        Files.list(workspacesRoot).use { stream ->
            stream.filter(Files::isDirectory).forEach { directory ->
                get(directory.fileName.toString())?.let(result::add)
            }
        }
        return result.sortedBy { it.displayName.lowercase() }
    }

    @Synchronized
    fun bind(watchScope: String, workspaceId: String): FaceWorkspaceBinding {
        require(watchScope.isNotBlank()) { "Watch scope cannot be blank" }
        require(get(workspaceId) != null) { "Unknown workspace: $workspaceId" }
        val binding = FaceWorkspaceBinding(watchScope.trim(), workspaceId)
        val bindings = readBindings().toMutableMap()
        bindings[binding.watchScope] = binding.workspaceId
        writeBindings(bindings)
        return binding
    }

    fun bindWatchScope(watchScope: String, workspaceId: String): FaceWorkspaceBinding =
        bind(watchScope, workspaceId)

    @Synchronized
    fun unbind(watchScope: String): Boolean {
        val bindings = readBindings().toMutableMap()
        val removed = bindings.remove(watchScope) != null
        if (removed) writeBindings(bindings)
        return removed
    }

    @Synchronized
    fun bindingFor(watchScope: String): FaceWorkspaceBinding? {
        val workspaceId = readBindings()[watchScope]?.takeIf { it.isNotBlank() } ?: return null
        return if (get(workspaceId) != null) FaceWorkspaceBinding(watchScope, workspaceId) else null
    }

    @Synchronized
    fun capability(workspaceId: String): WorkspaceCapability {
        val workspace = get(workspaceId)
            ?: return WorkspaceCapability(workspaceId, false, false, false, CapabilityReason.WORKSPACE_NOT_FOUND)
        val rootfsInstalled = workspace.rootfs.status == RootfsStatus.INSTALLED
        val reason = when {
            !rootfsInstalled -> CapabilityReason.ROOTFS_NOT_INSTALLED
            !workspace.rootfs.bashAvailable -> CapabilityReason.BASH_NOT_AVAILABLE
            !workspace.rootfs.pythonAvailable -> CapabilityReason.PYTHON_NOT_AVAILABLE
            else -> CapabilityReason.READY
        }
        return WorkspaceCapability(
            workspaceId = workspace.id,
            rootfsInstalled = rootfsInstalled,
            bashAvailable = rootfsInstalled && workspace.rootfs.bashAvailable,
            pythonAvailable = rootfsInstalled && workspace.rootfs.pythonAvailable,
            reason = reason,
        )
    }

    /** Used by the installer adapter only after its own atomic extraction and self-check succeed. */
    @Synchronized
    fun markRootfsInstalled(
        workspaceId: String,
        bashAvailable: Boolean,
        pythonAvailable: Boolean,
        architecture: String? = null,
        version: String? = null,
        archiveSha256: String? = null,
    ): AgentWorkspace {
        val current = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        val updated = current.copy(
            rootfs = RootfsState(
                status = RootfsStatus.INSTALLED,
                architecture = architecture,
                version = version,
                archiveSha256 = archiveSha256,
                bashAvailable = bashAvailable,
                pythonAvailable = pythonAvailable,
            ),
        )
        writeMetadata(updated)
        return get(workspaceId) ?: updated
    }

    /**
     * Commits a fully extracted and checked Rootfs from a sibling staging directory.
     * The previous Rootfs stays restorable until both the directory swap and metadata
     * update have completed.
     */
    @Synchronized
    fun replaceRootfs(
        workspaceId: String,
        stagingRootfs: Path,
        bashAvailable: Boolean,
        pythonAvailable: Boolean,
        architecture: String,
        version: String,
        archiveSha256: String,
    ): AgentWorkspace {
        val current = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        val workspaceRoot = current.paths.root.toAbsolutePath().normalize()
        val staging = stagingRootfs.toAbsolutePath().normalize()
        require(staging.parent == workspaceRoot && staging.fileName.toString().startsWith(".rootfs-install-")) {
            "Rootfs staging directory is not owned by this workspace"
        }
        require(Files.isDirectory(staging)) { "Rootfs staging directory does not exist" }

        val installed = current.paths.linux.toAbsolutePath().normalize()
        val backup = workspaceRoot.resolve(".linux-backup-${UUID.randomUUID()}")
        var previousMoved = false
        var candidateMoved = false
        val updated = try {
            if (Files.exists(installed)) {
                moveAtomically(installed, backup)
                previousMoved = true
            }
            moveAtomically(staging, installed)
            candidateMoved = true
            val updated = current.copy(
                rootfs = RootfsState(
                    status = RootfsStatus.INSTALLED,
                    architecture = architecture,
                    version = version,
                    archiveSha256 = archiveSha256,
                    bashAvailable = bashAvailable,
                    pythonAvailable = pythonAvailable,
                ),
            )
            writeMetadata(updated)
            updated
        } catch (failure: Throwable) {
            if (candidateMoved && Files.exists(installed)) deleteRecursively(installed)
            if (previousMoved && Files.exists(backup)) moveAtomically(backup, installed)
            throw failure
        }

        // Metadata publication is the commit point. Once it succeeds, cleanup is
        // best-effort and must never turn a valid new Rootfs into a partial rollback.
        if (previousMoved) {
            try {
                rootfsBackupCleaner?.invoke(backup) ?: deleteRecursively(backup)
            } catch (_: IOException) {
                // A later install or workspace deletion can reclaim the orphaned backup.
            }
        }
        return get(workspaceId) ?: updated
    }

    @Synchronized
    fun refreshQuota(workspaceId: String): WorkspaceQuota {
        val workspace = get(workspaceId) ?: error("Unknown workspace: $workspaceId")
        val quota = workspace.quota.withUsage(calculateUsage(workspace.paths.root))
        writeMetadata(workspace.copy(quota = quota))
        return quota
    }

    /** Lists one managed workspace directory without exposing metadata or Rootfs. */
    @Synchronized
    fun listDirectory(workspaceId: String, relativeDirectory: String = "workspace"): List<DirectoryEntry> {
        val workspace = get(workspaceId) ?: return emptyList()
        val relative = WorkspacePathPolicy.sanitizeRelative(relativeDirectory)
        val directory = WorkspacePathPolicy.resolveInside(workspace.paths.root, relative)
        require(Files.isDirectory(directory)) { "Workspace directory does not exist" }
        Files.list(directory).use { stream ->
            return stream.map { path ->
                val childRelative = workspace.paths.workspace.relativize(path).toString().replace('\\', '/')
                DirectoryEntry(childRelative, path.fileName.toString(), Files.isDirectory(path), if (Files.isRegularFile(path)) Files.size(path) else 0L)
            }.sorted(compareBy<DirectoryEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }).toList()
        }
    }

    /** Deletes a file or directory inside /workspace, never protected runtime paths. */
    @Synchronized
    fun deleteWorkspaceEntry(workspaceId: String, relativePath: String): Boolean {
        val workspace = get(workspaceId) ?: return false
        val relative = WorkspacePathPolicy.sanitizeRelative(relativePath)
        require(relative == "workspace" || relative.startsWith("workspace/")) { "Only workspace files can be deleted" }
        val target = WorkspacePathPolicy.resolveInside(workspace.paths.root, relative)
        require(target != workspace.paths.workspace && Files.exists(target)) { "Invalid workspace entry" }
        require(!Files.isSymbolicLink(target)) { "Symbolic links cannot be deleted" }
        deleteRecursively(target)
        refreshQuota(workspaceId)
        return !Files.exists(target)
    }

    @Synchronized
    fun importFile(workspaceId: String, source: Path, targetRelativePath: String): ImportResult {
        val workspace = get(workspaceId) ?: return ImportResult.INVALID_SOURCE
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) return ImportResult.SOURCE_NOT_FOUND
        val target = WorkspacePathPolicy.resolveInside(workspace.paths.root, targetRelativePath)
        val oldSize = if (Files.isRegularFile(target)) Files.size(target) else 0L
        val newSize = Files.size(source)
        val usage = calculateUsage(workspace.paths.root)
        val projected = usage - oldSize + newSize
        if (projected > workspace.quota.limitBytes) return ImportResult.QUOTA_EXCEEDED
        return try {
            Files.createDirectories(target.parent)
            val staging = target.parent.resolve(".${target.fileName}.import-${UUID.randomUUID()}.tmp")
            Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
            moveAtomically(staging, target)
            refreshQuota(workspaceId)
            ImportResult.IMPORTED
        } catch (_: WorkspacePathViolation) {
            throw WorkspacePathViolation("Import target escapes workspace")
        } catch (_: IOException) {
            ImportResult.IO_ERROR
        }
    }

    fun importWorkspaceFile(workspaceId: String, source: Path, targetRelativePath: String): ImportResult =
        importFile(workspaceId, source, targetRelativePath)

    @Synchronized
    fun exportWorkspace(workspaceId: String, targetArchive: Path): ExportResult {
        val workspace = get(workspaceId) ?: return ExportResult.WORKSPACE_NOT_FOUND
        val target = targetArchive.toAbsolutePath().normalize()
        if (target.startsWith(workspace.paths.root.toAbsolutePath().normalize())) return ExportResult.INVALID_TARGET
        return try {
            Files.createDirectories(target.parent)
            val staging = target.resolveSibling(".${target.fileName}.export-${UUID.randomUUID()}.tmp")
            ZipOutputStream(Files.newOutputStream(staging)).use { zip ->
                Files.walk(workspace.paths.root).use { stream ->
                    stream.filter { it != workspace.paths.root }.forEach { path ->
                        if (Files.isSymbolicLink(path)) throw SymlinkExportException()
                        val entryName = workspace.paths.root.relativize(path).toString().replace('\\', '/')
                        if (Files.isDirectory(path)) zip.putNextEntry(ZipEntry("$entryName/"))
                        else {
                            zip.putNextEntry(ZipEntry(entryName))
                            Files.newInputStream(path).use { it.copyTo(zip) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            moveAtomically(staging, target)
            ExportResult.EXPORTED
        } catch (_: SymlinkExportException) {
            ExportResult.UNSUPPORTED_SYMLINK
        } catch (_: IOException) {
            ExportResult.IO_ERROR
        }
    }

    fun exportWorkspaceArchive(workspaceId: String, targetArchive: Path): ExportResult =
        exportWorkspace(workspaceId, targetArchive)

    /** Returns the isolated mirror directory for the face bound to this workspace. */
    @Synchronized
    fun faceMirrorDirectory(workspaceId: String): Path? =
        get(workspaceId)?.paths?.face?.also { Files.createDirectories(it) }

    /** Replaces the complete face mirror with one validated workspace snapshot. */
    @Synchronized
    fun syncFaceMirror(workspaceId: String, watchXml: String, globalScript: String, resources: Map<String, ByteArray> = emptyMap()): Boolean {
        val workspace = get(workspaceId) ?: return false
        val root = workspace.paths.root
        val face = workspace.paths.face
        val entries = linkedMapOf("watch.xml" to watchXml.toByteArray(Charsets.UTF_8), "scripts/script.txt" to globalScript.toByteArray(Charsets.UTF_8))
        resources.forEach { (path, bytes) ->
            val clean = WorkspacePathPolicy.sanitizeRelative(path)
            require(clean == path && clean != "watch.xml" && clean != "scripts/script.txt" && !clean.startsWith("face/")) { "表盘镜像资源路径无效：$path" }
            require(bytes.size <= 256 * 1024 * 1024) { "表盘镜像资源过大：$path" }
            entries[clean] = bytes
        }
        require(entries.values.sumOf { it.size.toLong() } <= 1024L * 1024 * 1024) { "表盘镜像总量超过限制" }
        val oldFaceBytes = if (Files.isDirectory(face)) calculateUsage(face) else 0L
        require(calculateUsage(root) - oldFaceBytes + entries.values.sumOf { it.size.toLong() } <= workspace.quota.limitBytes) { "表盘镜像超出工作区配额" }
        val staging = Files.createTempDirectory(root, ".face-sync-")
        val backup = root.resolve(".face-backup-${UUID.randomUUID()}")
        var backedUp = false
        var published = false
        try {
            entries.forEach { (path, bytes) ->
                val target = WorkspacePathPolicy.resolveInside(staging, path)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
            if (Files.exists(face)) { moveAtomically(face, backup); backedUp = true }
            try { moveAtomically(staging, face); published = true }
            catch (error: Throwable) {
                if (backedUp) moveAtomically(backup, face)
                throw error
            }
            return true
        } finally {
            if (!published && Files.exists(staging)) deleteRecursively(staging)
            if (published && backedUp && Files.exists(backup)) deleteRecursively(backup)
            if (published) refreshQuota(workspaceId)
        }
    }

    /** Imports an archive into a new UUID after validating every entry as a relative path. */
    @Synchronized
    fun importWorkspaceArchive(
        sourceArchive: Path,
        displayName: String,
        quotaBytes: Long = defaultQuotaBytes,
    ): AgentWorkspace {
        require(Files.isRegularFile(sourceArchive)) { "Workspace archive not found" }
        val staging = runtimeRoot.resolve(".archive-import-${UUID.randomUUID()}")
        Files.createDirectories(staging)
        return try {
            var extractedBytes = 0L
            ZipInputStream(Files.newInputStream(sourceArchive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = WorkspacePathPolicy.resolveInside(staging, entry.name)
                    if (entry.isDirectory) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                require(extractedBytes <= quotaBytes) { "Workspace archive exceeds quota" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val workspace = create(displayName, quotaBytes)
            Files.walk(staging).use { stream ->
                stream.filter { it != staging }.forEach { path ->
                    val destination = WorkspacePathPolicy.resolveInside(
                        workspace.paths.root,
                        staging.relativize(path).toString(),
                    )
                    if (Files.isDirectory(path)) Files.createDirectories(destination)
                    else {
                        Files.createDirectories(destination.parent)
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            refreshQuota(workspace.id)
            get(workspace.id) ?: workspace
        } finally {
            deleteRecursively(staging)
        }
    }

    private fun pathsFor(workspaceId: String): WorkspacePaths? {
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(workspaceId)) return null
        val path = workspacesRoot.resolve(workspaceId).normalize()
        if (!path.startsWith(workspacesRoot)) return null
        return WorkspacePaths(path)
    }

    private fun writeMetadata(workspace: AgentWorkspace) {
        val rootfs = mapOf(
            "status" to workspace.rootfs.status.name,
            "architecture" to workspace.rootfs.architecture,
            "version" to workspace.rootfs.version,
            "archiveSha256" to workspace.rootfs.archiveSha256,
            "bashAvailable" to workspace.rootfs.bashAvailable,
            "pythonAvailable" to workspace.rootfs.pythonAvailable,
        )
        val json = mapOf(
            "id" to workspace.id,
            "displayName" to workspace.displayName,
            "watchScope" to workspace.watchScope,
            "rootfs" to rootfs,
            "quotaLimitBytes" to workspace.quota.limitBytes,
            "quotaUsedBytes" to workspace.quota.usedBytes,
        )
        writeAtomically(workspace.paths.metadata, AgentJsonWriter.objectValue(json))
    }

    private fun decodeMetadata(content: String, paths: WorkspacePaths): AgentWorkspace {
        val json = AgentJsonParser.parse(content) as? AgentJsonValue.Obj ?: error("Malformed workspace metadata")
        val rootfs = json.obj("rootfs")
        val status = runCatching { RootfsStatus.valueOf(rootfs?.string("status") ?: RootfsStatus.NOT_INSTALLED.name) }
            .getOrDefault(RootfsStatus.NOT_INSTALLED)
        return AgentWorkspace(
            id = json.string("id") ?: error("Workspace metadata missing id"),
            displayName = json.string("displayName") ?: json.string("id").orEmpty(),
            watchScope = json.string("watchScope"),
            rootfs = RootfsState(
                status = status,
                architecture = rootfs?.string("architecture"),
                version = rootfs?.string("version"),
                archiveSha256 = rootfs?.string("archiveSha256"),
                bashAvailable = rootfs?.bool("bashAvailable") ?: false,
                pythonAvailable = rootfs?.bool("pythonAvailable") ?: false,
            ),
            quota = WorkspaceQuota(
                limitBytes = json.long("quotaLimitBytes") ?: defaultQuotaBytes,
                usedBytes = json.long("quotaUsedBytes") ?: 0L,
            ),
            paths = paths,
        )
    }

    private fun readBindings(): Map<String, String> = if (!Files.isRegularFile(bindingsFile)) emptyMap() else runCatching {
        val root = AgentJsonParser.parse(String(Files.readAllBytes(bindingsFile), Charsets.UTF_8)) as? AgentJsonValue.Obj
            ?: return@runCatching emptyMap()
        root.fields.mapNotNull { (key, value) -> (value as? AgentJsonValue.Str)?.value?.let { key to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeBindings(bindings: Map<String, String>) = writeAtomically(bindingsFile, AgentJsonWriter.objectValue(bindings))

    private fun firstBindingFor(workspaceId: String): FaceWorkspaceBinding? {
        val bindings = readBindings()
        bindings.entries.firstOrNull { it.value == workspaceId }?.let { return FaceWorkspaceBinding(it.key, workspaceId) }
        return null
    }

    private fun calculateUsage(root: Path): Long {
        if (!Files.exists(root)) return 0L
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() != "workspace.json" }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                .sum()
        }
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val staging = target.resolveSibling(".${target.fileName}.tmp-${UUID.randomUUID()}")
        Files.write(staging, content.toByteArray(Charsets.UTF_8))
        moveAtomically(staging, target)
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class SymlinkExportException : IOException()

    companion object {
        const val DEFAULT_QUOTA_BYTES: Long = 1024L * 1024L * 1024L
    }
}


