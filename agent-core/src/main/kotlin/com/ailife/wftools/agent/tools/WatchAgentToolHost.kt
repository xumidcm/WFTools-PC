package com.ailife.wftools.agent.tools

interface WatchAgentToolHost {
    val currentRevision: Long

    fun inspectFace(request: WatchAgentInspectFaceRequest): WatchAgentInspectFaceResult
    fun readDocument(request: WatchAgentReadDocumentRequest): WatchAgentDocumentResult
    fun searchKnowledge(request: WatchAgentSearchKnowledgeRequest): WatchAgentKnowledgeResult
    fun validateFace(request: WatchAgentValidateFaceRequest): WatchAgentValidationResult

    /** The host owns the real renderer; the tool layer never fabricates a PNG. */
    fun previewFace(request: WatchAgentPreviewRequest): WatchAgentPreviewResult

    fun editDocument(request: WatchAgentEditDocumentRequest): WatchAgentDocumentMutationResult
    fun writeDocument(request: WatchAgentWriteDocumentRequest): WatchAgentDocumentMutationResult

    fun syncFaceMirror(): Any = throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定表盘镜像")
    /** Refreshes the editor-to-face/ baseline immediately before a shell command.
     * Hosts without a bound workspace intentionally keep this as a no-op. */
    fun prepareBashMirror() = Unit
    fun validateFaceMirror(): Any = throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定表盘镜像")
    fun commitFaceMirror(expectedRevision: Long): Any = throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定表盘镜像")
    fun importFaceResource(path: String): Any = throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定表盘镜像")
    fun writeWorkspaceFile(path: String, content: String): Any = throw WatchAgentToolException("workspace_unavailable", "当前宿主未绑定工作区")
}

interface WatchAgentUndoHost {
    fun beginUndoGroup(): WatchAgentUndoGroup
}

interface WatchAgentUndoGroup {
    fun commit()
    fun rollback()
}

data class WatchAgentLayerSummary(
    val ref: String,
    val id: String? = null,
    val type: String,
    val parentRef: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

data class WatchAgentAssetSummary(
    val path: String,
    val bytes: Long? = null,
    val referenced: Boolean? = null,
)

data class WatchAgentInspectFaceResult(
    val revision: Long,
    val name: String? = null,
    val format: String? = null,
    val layers: List<WatchAgentLayerSummary> = emptyList(),
    /** Number of layer elements understood by the host parser. */
    val layersParsed: Int = layers.size,
    /** Number of layer-looking elements found directly in the XML. */
    val layersInXml: Int = layers.size,
    /** `ok` when parser and XML agree, `mismatch` when the host ignored nodes. */
    val parseStatus: String = if (layersParsed == layersInXml) "ok" else "mismatch",
    val assets: List<WatchAgentAssetSummary> = emptyList(),
    val hasGlobalScript: Boolean = false,
    val previewAvailable: Boolean = false,
    val capabilities: Set<String> = emptySet(),
)

data class WatchAgentDocumentResult(
    val path: WatchAgentDocumentPath,
    val revision: Long,
    val content: String,
)

data class WatchAgentKnowledgeHit(
    val title: String,
    val snippet: String,
    val source: String? = null,
    val score: Int = 0,
)

data class WatchAgentKnowledgeResult(
    val query: String,
    val hits: List<WatchAgentKnowledgeHit>,
)

enum class WatchAgentDiagnosticSeverity { INFO, WARNING, ERROR }

data class WatchAgentDiagnostic(
    val severity: WatchAgentDiagnosticSeverity,
    val code: String,
    val message: String,
    val layerRef: String? = null,
    val attribute: String? = null,
)

data class WatchAgentValidationResult(
    val revision: Long,
    val diagnostics: List<WatchAgentDiagnostic> = emptyList(),
    val expressionCounts: Map<WatchAgentExpressionKind, Int> = emptyMap(),
) {
    val valid: Boolean get() = diagnostics.none { it.severity == WatchAgentDiagnosticSeverity.ERROR }
}

data class WatchAgentPreviewResult(
    val revision: Long,
    val target: String,
    val renderer: String,
    val available: Boolean = true,
    val imageBytes: ByteArray? = null,
    val diagnostics: List<WatchAgentDiagnostic> = emptyList(),
    /** Number of layers the renderer submitted for this frame, when known. */
    val renderedLayerCount: Int? = null,
    /** Ratio of pixels that differ from the dominant background colour, 0..1. */
    val nonBackgroundPixelRatio: Double? = null,
    /** Short machine-readable warning for blank/stale renderer output. */
    val rendererWarning: String? = null,
)

data class WatchAgentDocumentMutationResult(
    val path: WatchAgentDocumentPath,
    val revision: Long,
    val content: String,
)

class WatchAgentToolException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

fun staleRevision(expected: Long, actual: Long): WatchAgentToolException =
    WatchAgentToolException(
        code = "stale_revision",
        message = "文档版本已变化：expected_revision=$expected，current_revision=$actual；请重新读取后再写入",
    )


