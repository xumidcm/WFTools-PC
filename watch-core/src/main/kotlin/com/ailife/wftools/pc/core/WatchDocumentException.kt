package com.ailife.wftools.pc.core

/** Recoverable document errors; display [message] and keep the current editor open. */
sealed class WatchDocumentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidDocument(message: String, cause: Throwable? = null) : WatchDocumentException(message, cause)
    class InvalidArchive(message: String, cause: Throwable? = null) : WatchDocumentException(message, cause)
    class UnsafeArchive(message: String) : WatchDocumentException(message)
    class UnsupportedProtection : WatchDocumentException("此表盘包含受保护的 watch.pxml；请先在 WatchMaker 中导出可编辑的未保护版本。")
    class OpenFailed(message: String, cause: Throwable? = null) : WatchDocumentException(message, cause)
    class SaveFailed(message: String, cause: Throwable? = null) : WatchDocumentException(message, cause)
    class ExternalModification : WatchDocumentException("源文件已被其他程序修改或删除。请重新打开，或另存为新文件以保留当前编辑。")
    class InvalidCommand(message: String, cause: Throwable? = null) : WatchDocumentException(message, cause)
    class AssetImportFailed(message: String, cause: Throwable? = null) : WatchDocumentException(message, cause)
    class RecoveryFailed(message: String, cause: Throwable? = null) : WatchDocumentException(message, cause)
    class ClosedSession : WatchDocumentException("此编辑会话已关闭，请重新打开表盘。")
}

data class EditorLayer(val id: String, val type: String, val attrs: Map<String, String>)
data class LayerSelection(val selected: Set<String> = emptySet(), val primary: String? = null, val anchor: String? = null)
data class RecoveryDraft(val id: String, val title: String, val sourcePath: java.nio.file.Path?, val updatedAt: java.time.Instant)

internal data class DocumentSnapshot(
    val xml: String,
    val layerIds: List<String>,
    val selection: LayerSelection = LayerSelection(),
    val importedAssets: Map<String, String> = emptyMap(),
    val scriptOverride: String? = null
)

internal data class DraftJournal(
    val version: Int = 1,
    val id: String,
    val sourcePath: String?,
    val sourceHash: String?,
    val baseArchiveHash: String?,
    val savedSignature: String?,
    val revision: Long,
    val xmlEntry: String,
    val state: DocumentSnapshot,
    val undo: List<DocumentSnapshot>,
    val redo: List<DocumentSnapshot>,
    val derivedResources: Map<String, String>? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
