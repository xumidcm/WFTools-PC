package com.ailife.wftools.agent.tools

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Small host implementation for JVM tests and tooling previews. Production
 * Android code should implement [WatchAgentToolHost] over WatchEditorSession
 * and bind [previewFace] to the native WatchMaker renderer.
 */
class InMemoryWatchAgentToolHost(
    initialWatchXml: String,
    initialGlobalScript: String = "",
    assets: List<WatchAgentAssetSummary> = emptyList(),
    private val knowledge: WatchAgentKnowledgeStore = WatchAgentKnowledgeStore.EMPTY,
) : WatchAgentToolHost, WatchAgentUndoHost {
    private var watchXml = initialWatchXml
    private var globalScript = initialGlobalScript
    private val assetsByPath = assets.associateBy { it.path }
    private var revision = 1L
    private var undoSnapshot: Snapshot? = null

    init {
        parseWatchXml(initialWatchXml)
    }

    override val currentRevision: Long
        get() = synchronized(this) { revision }

    @Synchronized
    override fun inspectFace(request: WatchAgentInspectFaceRequest): WatchAgentInspectFaceResult {
        checkReadRevision(request.expectedRevision)
        val document = parseWatchXml(watchXml)
        val root = document.documentElement
        val layers = mutableListOf<WatchAgentLayerSummary>()
        collectLayers(root, null, layers)
        val xmlInspection = WatchAgentXmlInspector.inspect(watchXml)
        return WatchAgentInspectFaceResult(
            revision = revision,
            name = root.getAttribute("name").takeIf(String::isNotBlank),
            format = if (root.getAttribute("build_source").equals("studio", true)) "V2" else "V1",
            layers = layers,
            layersParsed = layers.size,
            layersInXml = xmlInspection.layersInXml,
            parseStatus = if (layers.size == xmlInspection.layersInXml) "ok" else "mismatch",
            assets = assetsByPath.values.toList(),
            hasGlobalScript = globalScript.isNotBlank(),
            previewAvailable = false,
            capabilities = setOf("document", "validation"),
        )
    }

    @Synchronized
    override fun readDocument(request: WatchAgentReadDocumentRequest): WatchAgentDocumentResult {
        checkReadRevision(request.expectedRevision)
        return WatchAgentDocumentResult(
            request.path,
            revision,
            if (request.path == WatchAgentDocumentPath.WATCH_XML) watchXml else globalScript,
        )
    }

    override fun searchKnowledge(request: WatchAgentSearchKnowledgeRequest): WatchAgentKnowledgeResult =
        knowledge.search(request.query)

    @Synchronized
    override fun validateFace(request: WatchAgentValidateFaceRequest): WatchAgentValidationResult {
        checkReadRevision(request.expectedRevision)
        val diagnostics = mutableListOf<WatchAgentDiagnostic>()
        var parsedLayerCount = 0
        val classifications = try {
            WatchAgentExpressionClassifier.classifyXml(watchXml)
        } catch (error: Throwable) {
            diagnostics += WatchAgentDiagnostic(
                WatchAgentDiagnosticSeverity.ERROR,
                "invalid_xml",
                error.message ?: "watch.xml 无法解析",
            )
            emptyList()
        }
        runCatching { parseWatchXml(watchXml) }.onSuccess { document ->
            val parsed = mutableListOf<WatchAgentLayerSummary>()
            collectLayers(document.documentElement, null, parsed)
            parsedLayerCount = parsed.size
        }.onFailure { error ->
            diagnostics += WatchAgentDiagnostic(
                WatchAgentDiagnosticSeverity.ERROR,
                "invalid_xml",
                error.message ?: "watch.xml 无法解析",
            )
        }
        runCatching { WatchAgentXmlInspector.inspect(watchXml) }.onSuccess { inspection ->
            if (inspection.layersInXml != parsedLayerCount) {
                diagnostics += WatchAgentDiagnostic(
                    WatchAgentDiagnosticSeverity.ERROR,
                    "layer_parse_mismatch",
                    "XML 中发现 ${inspection.layersInXml} 个图层节点，但宿主只解析出 ${parsedLayerCount} 个；支持的图层元素应为 Layer(type=...)。",
                )
            }
        }
        validateGlobalScript(globalScript)?.let { diagnostics += it }
        PATH_PATTERN.findAll(watchXml).forEach { match ->
            val raw = match.groupValues[2]
            raw.split('`').forEach { segment ->
                runCatching { WatchAgentAssetPathNormalizer.normalizeImagePath(segment) }
                    .onFailure { error ->
                        diagnostics += WatchAgentDiagnostic(
                            WatchAgentDiagnosticSeverity.ERROR,
                            "invalid_asset_path",
                            error.message ?: "资源路径非法",
                        )
                    }
                if (segment.trim().replace('\\', '/').startsWith("images/", true)) {
                    diagnostics += WatchAgentDiagnostic(
                        WatchAgentDiagnosticSeverity.WARNING,
                        "noncanonical_asset_path",
                        "图片 path 带有 images/ 前缀，CloX/WatchMaker 规范要求相对 images/ 的文件名",
                    )
                }
            }
        }
        val counts = classifications.groupingBy { it.kind }.eachCount()
        return WatchAgentValidationResult(revision, diagnostics.distinct(), counts)
    }

    override fun previewFace(request: WatchAgentPreviewRequest): WatchAgentPreviewResult =
        throw WatchAgentToolException(
            "preview_unavailable",
            "当前宿主未绑定 Flue 原生 WatchMaker 渲染器，不能伪造 preview_face 结果",
        )

    @Synchronized
    override fun editDocument(request: WatchAgentEditDocumentRequest): WatchAgentDocumentMutationResult {
        checkWriteRevision(request.expectedRevision)
        val current = currentContent(request.path)
        require(request.oldText.isNotEmpty()) { "old_text 不能为空" }
        val first = current.indexOf(request.oldText)
        require(first >= 0 && current.indexOf(request.oldText, first + request.oldText.length) < 0) {
            "原文必须唯一匹配"
        }
        return write(request.path, current.replace(request.oldText, request.newText, ignoreCase = false))
    }

    @Synchronized
    override fun writeDocument(request: WatchAgentWriteDocumentRequest): WatchAgentDocumentMutationResult {
        checkWriteRevision(request.expectedRevision)
        require(request.content.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "文档超过 2 MiB UTF-8 限制"
        }
        if (request.path == WatchAgentDocumentPath.WATCH_XML) parseWatchXml(request.content)
        return write(request.path, request.content)
    }

    @Synchronized
    override fun beginUndoGroup(): WatchAgentUndoGroup {
        check(undoSnapshot == null) { "已有活动撤销组" }
        undoSnapshot = Snapshot(watchXml, globalScript, revision)
        return object : WatchAgentUndoGroup {
            private var done = false

            override fun commit() {
                synchronized(this@InMemoryWatchAgentToolHost) {
                    if (done) return
                    done = true
                    undoSnapshot = null
                }
            }

            override fun rollback() {
                synchronized(this@InMemoryWatchAgentToolHost) {
                    if (done) return
                    val snapshot = undoSnapshot ?: return
                    watchXml = snapshot.watchXml
                    globalScript = snapshot.globalScript
                    revision = maxOf(revision + 1, snapshot.revision + 1)
                    done = true
                    undoSnapshot = null
                }
            }
        }
    }

    private fun write(path: WatchAgentDocumentPath, content: String): WatchAgentDocumentMutationResult {
        val persisted = if (path == WatchAgentDocumentPath.WATCH_XML) {
            WatchAgentAssetPathNormalizer.normalizeImageLayerPaths(content)
        } else {
            content
        }
        if (path == WatchAgentDocumentPath.WATCH_XML) watchXml = persisted else globalScript = persisted
        revision += 1
        return WatchAgentDocumentMutationResult(path, revision, persisted)
    }

    private fun currentContent(path: WatchAgentDocumentPath): String =
        if (path == WatchAgentDocumentPath.WATCH_XML) watchXml else globalScript

    private fun checkReadRevision(expected: Long?) {
        if (expected != null && expected != revision) throw staleRevision(expected, revision)
    }

    private fun checkWriteRevision(expected: Long) {
        if (expected != revision) throw staleRevision(expected, revision)
    }

    private fun collectLayers(node: Node, parentRef: String?, into: MutableList<WatchAgentLayerSummary>) {
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child !is Element) continue
            if (child.tagName.equals("Layer", true)) {
                val ref = "layer-${into.size}"
                into += WatchAgentLayerSummary(
                    ref = ref,
                    id = child.getAttribute("id").takeIf(String::isNotBlank),
                    type = child.getAttribute("type").ifBlank { "unknown" },
                    parentRef = parentRef,
                    attributes = buildMap {
                        val attributes = child.attributes
                        for (attributeIndex in 0 until attributes.length) {
                            val attribute = attributes.item(attributeIndex)
                            put(attribute.nodeName, attribute.nodeValue.orEmpty())
                        }
                    },
                )
                collectLayers(child, ref, into)
            } else {
                collectLayers(child, parentRef, into)
            }
        }
    }

    private data class Snapshot(val watchXml: String, val globalScript: String, val revision: Long)

    companion object {
        private const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
        private val PATH_PATTERN = Regex("(?i)\\b(?:path|paths)\\s*=\\s*([\\\"'])(.*?)\\1")

        private fun parseWatchXml(xml: String): org.w3c.dom.Document {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            val document = factory.newDocumentBuilder().parse(
                ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)),
            )
            require(document.documentElement?.tagName?.equals("Watch", true) == true) {
                "watch.xml root must be <Watch>"
            }
            return document
        }
    }
}

data class WatchAgentKnowledgeEntry(
    val title: String,
    val text: String,
    val source: String? = null,
)

class WatchAgentKnowledgeStore(entries: List<WatchAgentKnowledgeEntry>) {
    private val entries = entries.toList()

    fun search(query: String, limit: Int = DEFAULT_LIMIT): WatchAgentKnowledgeResult {
        val terms = query.trim().lowercase().split(Regex("\\s+"))
            .filter(String::isNotBlank)
        val hits = entries.mapNotNull { entry ->
            val haystack = "${entry.title} ${entry.text}".lowercase()
            val score = terms.count { haystack.contains(it) }
            if (score == 0) null else WatchAgentKnowledgeHit(
                title = entry.title,
                snippet = entry.text.take(MAX_SNIPPET),
                source = entry.source,
                score = score,
            )
        }.sortedWith(compareByDescending<WatchAgentKnowledgeHit> { it.score }.thenBy { it.title })
            .take(limit)
        return WatchAgentKnowledgeResult(query, hits)
    }

    companion object {
        const val DEFAULT_LIMIT = 8
        const val MAX_SNIPPET = 600
        val EMPTY = WatchAgentKnowledgeStore(emptyList())
    }
}


