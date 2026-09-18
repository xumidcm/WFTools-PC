package com.ailife.wftools.pc

import com.ailife.wftools.agent.tools.*
import com.ailife.wftools.pc.render.PreviewCaptureExporter
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.LocalTime
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Uses the same native offscreen Skia capture path as the editor's screenshot action. */
class DesktopPreviewRenderer(private val doc: DocumentController) {
    private data class PreviousPreview(val revision: Long, val digest: String)
    private val previous = ConcurrentHashMap<String, PreviousPreview>()

    fun render(request: WatchAgentPreviewRequest): WatchAgentPreviewResult {
        val snapshot = doc.withAgentSession {
            val expected = request.expectedRevision
            if (expected != null && expected != doc.session.revision)
                throw staleRevision(expected, doc.session.revision)
            Triple(doc.session.revision, doc.session.toWatchProject(), doc.session.workDir.toFile())
        }
        val project = if (request.mode == WatchAgentPreviewMode.ISOLATED && request.target != "face") {
            val index = doc.view.layers.indexOfFirst { it.id == request.target || it.id == request.target.removePrefix("layer-") }
                .takeIf { it >= 0 } ?: request.target.removePrefix("layer-").toIntOrNull()
            val layer = index?.let(snapshot.second.wmLayers::getOrNull)
                ?: throw WatchAgentToolException("layer_not_found", "预览图层不存在：${request.target}")
            snapshot.second.copy(wmLayers = listOf(layer))
        } else snapshot.second
        val base = doc.render.frame
        val time = request.time?.let { raw ->
            runCatching { LocalDateTime.parse(raw) }.getOrNull()
                ?: runCatching { base.now.with(LocalTime.parse(raw)) }.getOrNull()
        } ?: base.now
        val frame = base.copy(now = time, batteryPercentage = request.battery?.coerceIn(0, 100) ?: base.batteryPercentage)
        val bright = when (request.display) { WatchAgentDisplayMode.BRIGHT -> true; WatchAgentDisplayMode.DIM -> false; null -> doc.render.isBright }
        val bytes = runBlocking { PreviewCaptureExporter.capturePng(project, snapshot.third, frame, bright, doc.render.themeIndex) }
        val analysis = analyse(bytes)
        val renderedLayers = project.wmLayers.size
        val key = "${request.target}:${request.mode}"
        val digest = sha256(bytes)
        val prior = previous[key]
        val diagnostics = mutableListOf<WatchAgentDiagnostic>()
        var warning: String? = null
        if (renderedLayers == 0) {
            warning = "no_rendered_layers"
            diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.WARNING, warning, "预览没有提交任何图层；输出可能只是背景占位图。")
        } else if (analysis == null) {
            warning = "preview_decode_failed"
            diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.WARNING, warning, "预览 PNG 无法解码，不能确认是否真的绘制了图层。")
        } else if (analysis.nonBackgroundRatio <= 0.0001) {
            warning = "blank_preview"
            diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.WARNING, warning, "预览只有背景像素，未检测到可见图层输出。")
        }
        if (prior != null && prior.revision != snapshot.first && prior.digest == digest) {
            warning = warning ?: "render_output_unchanged"
            diagnostics += WatchAgentDiagnostic(WatchAgentDiagnosticSeverity.WARNING, "render_output_unchanged", "当前版本的预览与上一稿字节完全一致，请核对图层是否真正写入或参与渲染。")
        }
        previous[key] = PreviousPreview(snapshot.first, digest)
        return WatchAgentPreviewResult(
            snapshot.first,
            request.target,
            "WFTools Skia",
            imageBytes = bytes,
            diagnostics = diagnostics,
            renderedLayerCount = renderedLayers,
            nonBackgroundPixelRatio = analysis?.nonBackgroundRatio,
            rendererWarning = warning,
        )
    }

    private data class PreviewAnalysis(val nonBackgroundRatio: Double)

    private fun analyse(bytes: ByteArray): PreviewAnalysis? = runCatching {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val histogram = HashMap<Int, Int>()
        var opaque = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            if ((argb ushr 24) == 0) continue
            opaque++
            histogram[argb] = (histogram[argb] ?: 0) + 1
        }
        if (opaque == 0) return PreviewAnalysis(0.0)
        val background = histogram.maxByOrNull { it.value }!!.key
        val different = histogram.filterKeys { it != background }.values.sum()
        PreviewAnalysis(different.toDouble() / opaque.toDouble())
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
