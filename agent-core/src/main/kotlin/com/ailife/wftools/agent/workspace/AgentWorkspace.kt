package com.ailife.wftools.agent.workspace

import java.nio.file.Path

enum class RootfsStatus {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    FAILED,
}

data class RootfsState(
    val status: RootfsStatus = RootfsStatus.NOT_INSTALLED,
    val architecture: String? = null,
    val version: String? = null,
    val archiveSha256: String? = null,
    val bashAvailable: Boolean = false,
    val pythonAvailable: Boolean = false,
)

data class WorkspaceQuota(
    val limitBytes: Long,
    val usedBytes: Long = 0L,
) {
    init {
        require(limitBytes >= 0L) { "Workspace quota cannot be negative" }
        require(usedBytes >= 0L) { "Workspace usage cannot be negative" }
    }

    val remainingBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0L)

    fun canAdd(deltaBytes: Long): Boolean =
        deltaBytes >= 0L && usedBytes <= limitBytes - deltaBytes

    fun withUsage(bytes: Long): WorkspaceQuota = copy(usedBytes = bytes.coerceAtLeast(0L))
}

data class FaceWorkspaceBinding(
    val watchScope: String,
    val workspaceId: String,
)

data class WorkspacePaths(val root: Path) {
    val linux: Path get() = root.resolve("linux")
    /** Mirror of the bound face documents, isolated from the general workspace. */
    val face: Path get() = root.resolve("face")
    val home: Path get() = root.resolve("home")
    val workspace: Path get() = root.resolve("workspace")
    val generated: Path get() = root.resolve("generated")
    val previews: Path get() = root.resolve("previews")
    val metadata: Path get() = root.resolve("workspace.json")
}

data class AgentWorkspace(
    val id: String,
    val displayName: String,
    val watchScope: String? = null,
    val rootfs: RootfsState = RootfsState(),
    val quota: WorkspaceQuota,
    val binding: FaceWorkspaceBinding? = null,
    val paths: WorkspacePaths,
)

enum class CapabilityReason {
    READY,
    ROOTFS_NOT_INSTALLED,
    BASH_NOT_AVAILABLE,
    PYTHON_NOT_AVAILABLE,
    WORKSPACE_NOT_FOUND,
}

data class WorkspaceCapability(
    val workspaceId: String,
    val rootfsInstalled: Boolean,
    val bashAvailable: Boolean,
    val pythonAvailable: Boolean,
    val reason: CapabilityReason,
)


