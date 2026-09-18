package com.ailife.wftools.pc

import com.ailife.wftools.agent.tools.*
import com.ailife.wftools.agent.workspace.WorkspaceManager
import com.ailife.wftools.pc.core.*
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.nio.file.LinkOption
import javax.xml.parsers.DocumentBuilderFactory
import com.ailife.wftools.agent.workspace.ImportResult
import com.ailife.wftools.agent.workspace.WorkspacePathPolicy
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.roundToInt

/** Bridges the pure Agent contract to one desktop DocumentSession. */
class DesktopWatchAgentToolHost(
    private val doc: DocumentController,
    private val preview: ((WatchAgentPreviewRequest) -> WatchAgentPreviewResult)? = null,
    private val workspace: WorkspaceManager? = null,
    private val watchScope: String = doc.view.identity,
) : WatchAgentToolHost, WatchAgentUndoHost {
    @Volatile private var lastSyncedRevision: Long? = null
    override val currentRevision: Long get() = doc.session.revision

    override fun inspectFace(request: WatchAgentInspectFaceRequest): WatchAgentInspectFaceResult = doc.withAgentSession {
        checkRevision(request.expectedRevision)
        val project = doc.session.toWatchProject()
        val xmlInspection = WatchAgentXmlInspector.inspect(doc.session.agentCandidate().watchXml)
        val parsedCount = doc.session.layers.size
        WatchAgentInspectFaceResult(
            revision = currentRevision,
            name = project.title,
            format = "WatchMaker",
            layers = doc.session.layers.map { layer ->
                WatchAgentLayerSummary(layer.id, layer.id, layer.type, attributes = layer.attrs)
            },
            layersParsed = parsedCount,
            layersInXml = xmlInspection.layersInXml,
            parseStatus = if (parsedCount == xmlInspection.layersInXml) "ok" else "mismatch",
            assets = listAssets(),
            hasGlobalScript = project.globalScript.isNotBlank(),
            previewAvailable = preview != null,
            capabilities = buildSet {
                add("document")
                add("validation")
                if (preview != null) add("native_preview")
            },
        )
    }
    override fun readDocument(request: WatchAgentReadDocumentRequest): WatchAgentDocumentResult = doc.withAgentSession {
        checkRevision(request.expectedRevision)
        val candidate = doc.session.agentCandidate()
        WatchAgentDocumentResult(request.path, candidate.revision, if (request.path == WatchAgentDocumentPath.WATCH_XML) candidate.watchXml else candidate.globalScript)
    }
    override fun searchKnowledge(request: WatchAgentSearchKnowledgeRequest): WatchAgentKnowledgeResult = WatchAgentKnowledgeStore(WatchAgentKnowledgeCatalog.entries).search(request.query)
    override fun validateFace(request: WatchAgentValidateFaceRequest): WatchAgentValidationResult = doc.withAgentSession {
        checkRevision(request.expectedRevision)
        val candidate = doc.session.agentCandidate()
        val diagnostics = mutableListOf<WatchAgentDiagnostic>()
        runCatching { validateXml(candidate.watchXml) }.onSuccess {
            val inspection = WatchAgentXmlInspector.inspect(candidate.watchXml)
            val parsedCount = doc.session.layers.size
            if (inspection.layersInXml != parsedCount) diagnostics += WatchAgentDiagnostic(
                WatchAgentDiagnosticSeverity.ERROR,
                "layer_parse_mismatch",
                "XML 中发现 ${inspection.layersInXml} 个图层节点，但宿主只解析出 ${parsedCount} 个；支持的图层元素应为 Layer(type=...)。",
            )
        }.onFailure { diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.ERROR, "invalid_xml", it.message ?: "watch.xml 无法解析") }
        val scriptError = runCatching { validateAgentGlobalScript(candidate.globalScript) }.getOrElse { it.message ?: "脚本无法执行" }
        if (scriptError != null) diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.ERROR, "invalid_global_script", scriptError)
        WatchAgentValidationResult(currentRevision, diagnostics)
    }
    override fun previewFace(request: WatchAgentPreviewRequest): WatchAgentPreviewResult = preview?.invoke(request) ?: WatchAgentPreviewResult(
        currentRevision,
        request.target,
        "desktop",
        available = false,
        rendererWarning = "renderer_unavailable",
    )
    override fun editDocument(request: WatchAgentEditDocumentRequest): WatchAgentDocumentMutationResult {
        val candidate = doc.withAgentSession { doc.session.editAgentDocument(request.path.wirePath, request.oldText, request.newText, request.expectedRevision) }
        doc.refreshFromAgent()
        runCatching { syncFaceMirror() }
        return WatchAgentDocumentMutationResult(request.path, candidate.revision, if (request.path == WatchAgentDocumentPath.WATCH_XML) candidate.watchXml else candidate.globalScript)
    }
    override fun writeDocument(request: WatchAgentWriteDocumentRequest): WatchAgentDocumentMutationResult {
        val candidate = doc.withAgentSession { doc.session.replaceAgentDocument(request.path.wirePath, request.content, request.expectedRevision) }
        doc.refreshFromAgent()
        runCatching { syncFaceMirror() }
        return WatchAgentDocumentMutationResult(request.path, candidate.revision, if (request.path == WatchAgentDocumentPath.WATCH_XML) candidate.watchXml else candidate.globalScript)
    }
    override fun syncFaceMirror(): Any {
        val manager = workspace ?: throw WatchAgentToolException("mirror_unavailable", "当前宿主未绑定工作区")
        val id = manager.bindingFor(watchScope)?.workspaceId ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区")
        val (candidate, resources) = doc.withAgentSession {
            val candidate = doc.session.agentCandidate()
            val resources = linkedMapOf<String, ByteArray>()
            Files.walk(doc.session.workDir).use { files -> files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { source ->
                val relative = doc.session.workDir.relativize(source).toString().replace('\\', '/')
                if (relative != "watch.xml" && relative != "scripts/script.txt") {
                    resources[relative] = Files.readAllBytes(source)
                }
            } }
            candidate to resources
        }
        check(manager.syncFaceMirror(id, candidate.watchXml, candidate.globalScript, resources)) { "表盘镜像同步失败" }
        lastSyncedRevision = candidate.revision
        return mapOf("workspace_id" to id, "revision" to candidate.revision, "path" to "face/", "resources" to resources.keys.sorted())
    }
    override fun prepareBashMirror() {
        // Bash edits the face mirror in place. Refresh immediately before each
        // command so editor changes made while the model was thinking become
        // the baseline, while an older mirror can never overwrite them.
        val manager = workspace ?: return
        if (manager.bindingFor(watchScope) == null) return
        runCatching { syncFaceMirror() }.getOrElse { failure ->
            throw WatchAgentToolException("mirror_unavailable", "无法在命令执行前同步表盘镜像：${failure.message ?: "同步失败"}", failure)
        }
    }
    override fun validateFaceMirror(): Any {
        val face = boundFaceMirror()
        val xml = Files.readString(face.resolve("watch.xml"))
        val script = Files.readString(face.resolve("scripts/script.txt"))
        val problems = mutableListOf<String>()
        runCatching { validateXml(xml) }.onFailure { problems += it.message ?: "watch.xml 无法解析" }
        val scriptError = runCatching { validateAgentGlobalScript(script) }.getOrElse { it.message ?: "脚本无法执行" }
        if (scriptError != null) problems += scriptError
        return mapOf("valid" to problems.isEmpty(), "diagnostics" to problems, "revision" to currentRevision)
    }
    override fun commitFaceMirror(expectedRevision: Long): Any {
        checkRevision(expectedRevision)
        if (lastSyncedRevision != currentRevision) {
            syncFaceMirror()
            return mapOf("committed" to false, "skipped" to "stale_mirror", "revision" to currentRevision)
        }
        val face = boundFaceMirror()
        val xml = Files.readString(face.resolve("watch.xml"))
        val script = Files.readString(face.resolve("scripts/script.txt"))
        val resources = linkedMapOf<String, ByteArray>()
        Files.walk(face).use { files -> files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { source ->
            val relative = face.relativize(source).toString().replace('\\', '/')
            if (relative != "watch.xml" && relative != "scripts/script.txt") {
                resources[relative] = Files.readAllBytes(source)
            }
        } }
        val unchanged = doc.withAgentSession {
            val current = doc.session.agentCandidate()
            if (current.watchXml != xml || current.globalScript != script) false
            else {
                val currentResources = linkedMapOf<String, ByteArray>()
                Files.walk(doc.session.workDir).use { files -> files.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { source ->
                    val relative = doc.session.workDir.relativize(source).toString().replace('\\', '/')
                    if (relative != "watch.xml" && relative != "scripts/script.txt") currentResources[relative] = Files.readAllBytes(source)
                } }
                currentResources.keys == resources.keys && resources.all { (path, bytes) -> currentResources[path]?.contentEquals(bytes) == true }
            }
        }
        if (unchanged) return mapOf("committed" to false, "skipped" to "no_changes", "revision" to currentRevision)
        val candidate = doc.withAgentSession {
            checkRevision(expectedRevision)
            doc.session.restoreAgentMirror(xml, script, resources)
        }
        doc.refreshFromAgent()
        syncFaceMirror()
        return mapOf("committed" to true, "revision" to candidate.revision, "path" to "face/", "resources" to resources.keys.sorted())
    }
    override fun importFaceResource(path: String): Any {
        val manager = workspace ?: throw WatchAgentToolException("workspace_required", "未绑定工作区")
        val id = manager.bindingFor(watchScope)?.workspaceId ?: throw WatchAgentToolException("workspace_required", "未绑定工作区")
        val source = manager.resolveInside(id, path)
        require(Files.isRegularFile(source)) { "资源不存在：$path" }
        var temporary: Path? = null
        val importSource = if (source.fileName.toString().endsWith(".svg", ignoreCase = true)) {
            temporary = Files.createTempFile("agent-svg-", ".png")
            try {
                Files.write(temporary, rasterizeSvg(source))
            } catch (failure: Throwable) {
                Files.deleteIfExists(temporary)
                temporary = null
                throw WatchAgentToolException("svg_invalid", "SVG 无法解析或渲染：${failure.message ?: "格式无效"}", failure)
            }
            temporary
        } else source
        return try {
            val imported = doc.withAgentSession { doc.session.importAssets(listOf(importSource), createLayers = false) }
            // Publish the new asset immediately so the layer list, preview and
            // subsequent Agent turns all observe the same document snapshot.
            doc.refreshFromAgent()
            runCatching { syncFaceMirror() }
            mapOf(
                "imported" to path,
                "resource_path" to imported.singleOrNull(),
                "converted_from" to path.takeIf { importSource != source },
                "format" to if (importSource != source) "png" else source.fileName.toString().substringAfterLast('.', ""),
                "revision" to currentRevision,
            )
        } finally { temporary?.let { Files.deleteIfExists(it) } }
    }

    override fun writeWorkspaceFile(path: String, content: String): Any {
        val manager = workspace ?: throw WatchAgentToolException("workspace_required", "未绑定工作区")
        val id = manager.bindingFor(watchScope)?.workspaceId
            ?: throw WatchAgentToolException("workspace_required", "未绑定工作区")
        val relative = WorkspacePathPolicy.sanitizeRelative(path)
        require(relative.substringBefore('/').lowercase() in setOf("workspace", "generated", "home")) {
            "只能写入 workspace/、generated/ 或 home/"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WORKSPACE_FILE_BYTES) { "工作区文本文件超过 8 MiB 限制" }
        // Use WorkspaceManager's quota, symlink and atomic-write checks instead of
        // handing an arbitrary path to the shell. The temporary source is removed
        // even when quota validation or the copy fails.
        val temporary = Files.createTempFile("agent-workspace-", ".tmp")
        return try {
            Files.write(temporary, bytes)
            when (val result = manager.importFile(id, temporary, relative)) {
                ImportResult.IMPORTED -> mapOf(
                    "path" to relative,
                    "bytes" to bytes.size,
                    "workspace_id" to id,
                    "revision" to currentRevision,
                )
                ImportResult.QUOTA_EXCEEDED -> throw WatchAgentToolException("workspace_quota_exceeded", "工作区配额不足，无法写入 $relative")
                ImportResult.SOURCE_NOT_FOUND -> throw WatchAgentToolException("workspace_write_failed", "临时文件不存在")
                ImportResult.INVALID_SOURCE, ImportResult.IO_ERROR -> throw WatchAgentToolException("workspace_write_failed", "无法写入工作区文件：$relative")
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    override fun beginUndoGroup(): WatchAgentUndoGroup {
        val group = doc.withAgentSession { doc.session.beginAgentUndoGroup().also { doc.beginAgentWrite() } }
        return object : WatchAgentUndoGroup {
            override fun commit() { try { doc.withAgentSession { group.commit() } } finally { doc.endAgentWrite() } }
            override fun rollback() { try { doc.withAgentSession { group.rollback() } } finally { doc.endAgentWrite() } }
        }
    }
    private fun checkRevision(expected: Long?) { if (expected != null && expected != currentRevision) throw staleRevision(expected, currentRevision) }
    private fun boundFaceMirror() = workspace?.bindingFor(watchScope)?.workspaceId?.let { workspace.faceMirrorDirectory(it) }
        ?: throw WatchAgentToolException("mirror_unavailable", "当前表盘未绑定工作区镜像")
    private fun listAssets(): List<WatchAgentAssetSummary> = Files.walk(doc.session.workDir).use { stream -> stream.filter(Files::isRegularFile).map { path -> WatchAgentAssetSummary(doc.session.workDir.relativize(path).toString().replace('\\', '/'), runCatching { Files.size(path) }.getOrNull(), true) }.toList() }
    private fun validateXml(xml: String) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); setFeature("http://xml.org/sax/features/external-general-entities", false); setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        require(root != null && root.tagName.equals("Watch", true)) { "XML 根节点必须为 Watch" }
    }

    private companion object {
        const val MAX_WORKSPACE_FILE_BYTES = 8 * 1024 * 1024
    }

    /** Rasterizes an Agent-created SVG into the PNG image format supported by
     * the desktop WatchMaker renderer.  SVG is bounded before handing it to
     * Skia so malformed or oversized output cannot take down the editor. */
    private fun rasterizeSvg(source: Path): ByteArray {
        val bytes = Files.readAllBytes(source)
        require(bytes.size <= 8 * 1024 * 1024) { "SVG 文件超过 8 MiB 限制" }
        Data.makeFromBytes(bytes).use { data ->
            SVGDOM(data).use { dom ->
                val container = dom.containerSize
                val width = container.x.takeIf { it.isFinite() && it > 0f }?.roundToInt()?.coerceIn(1, 4096) ?: 512
                val height = container.y.takeIf { it.isFinite() && it > 0f }?.roundToInt()?.coerceIn(1, 4096) ?: 512
                dom.setContainerSize(width.toFloat(), height.toFloat())
                Surface.makeRasterN32Premul(width, height).use { surface ->
                    surface.canvas.clear(0)
                    dom.render(surface.canvas)
                    surface.flushAndSubmit()
                    surface.makeImageSnapshot().use { image ->
                        return requireNotNull(image.encodeToData(EncodedImageFormat.PNG, 100)).use { it.bytes }
                    }
                }
            }
        }
    }
}
