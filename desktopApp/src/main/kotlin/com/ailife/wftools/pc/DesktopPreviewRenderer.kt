package com.ailife.wftools.pc

import com.ailife.wftools.agent.tools.*
import com.ailife.wftools.pc.render.PreviewCaptureExporter
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.LocalTime

/** Uses the same native offscreen Skia capture path as the editor's screenshot action. */
class DesktopPreviewRenderer(private val doc: DocumentController) {
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
        return WatchAgentPreviewResult(snapshot.first, request.target, "WFTools Skia", imageBytes = bytes)
    }
}
