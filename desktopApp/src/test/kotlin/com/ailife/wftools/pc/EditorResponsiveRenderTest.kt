@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc

import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.ailife.wftools.pc.core.WatchDocumentStore
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** Opt-in visual QA: WFT_UI_QA=1 writes the full breakpoint/DPI matrix for human inspection. */
class EditorResponsiveRenderTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `responsive workspace renders at all requested client sizes and DPI scales`(): Unit = runBlocking {
        assumeTrue(System.getenv("WFT_UI_QA") == "1")
        val directory = Path.of("build", "reports", "ui-qa").toAbsolutePath()
        Files.createDirectories(directory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = WatchDocumentStore(temp.newFolder("work").toPath())
        val sample = System.getenv("WFT_UI_SAMPLE")?.takeIf { it.isNotBlank() }
        val session = if(sample == null) store.newDocument() else store.open(Path.of(sample))
        if(sample == null) {
            session.addLayer("shape"); session.setAttribute(session.selection.selected,"width","420"); session.setAttribute(session.selection.selected,"height","420"); session.setAttribute(session.selection.selected,"color","6650A4")
            session.addLayer("text"); session.setAttribute(session.selection.selected,"text","12:34"); session.setAttribute(session.selection.selected,"text_size","64")
        }
        session.layers.lastOrNull { it.type == "text" }?.let { session.select(it.id,session.layers.asReversed().map { it.id }) }
        val preferences = EditorPreferences(temp.newFolder("prefs").toPath())
        val doc = DocumentController.create(session,store,scope,preferences)
        doc.render.playing = false
        val app = EditorApplicationModel(scope,preferences)
        val commands = CommandDispatcher(app,doc) {}
        try {
            val single = System.getenv("WFT_UI_QA_SINGLE") == "1"
            val dark = System.getenv("WFT_UI_DARK") == "1"
            for(width in if(single) listOf(1280) else listOf(720,800,1100,1280,1920)) for(height in if(single) listOf(800) else listOf(480,600,900)) for(dpi in if(single) listOf(1f) else listOf(1f,1.25f,1.5f,2f)) {
                withContext(QaUiDispatcher) {
                    val scene = ImageComposeScene((width*dpi).roundToInt(),(height*dpi).roundToInt(),Density(dpi),coroutineContext = coroutineContext) {
                        WFToolsTheme(dark) {
                            var panels by remember { mutableStateOf(PanelPreferences()) }
                            Surface(Modifier.fillMaxSize()) { EditorWorkspace(app,doc,commands,panels,{ panels = it },remember { DropRegistry() }) }
                        }
                    }
                    try {
                        repeat(3) { scene.render(System.nanoTime()).close(); delay(25) }
                        if(single) repeat(20) { scene.render(System.nanoTime()).close(); delay(100) }
                        scene.render(System.nanoTime()).use { image ->
                            assertEquals((width*dpi).roundToInt(),image.width); assertEquals((height*dpi).roundToInt(),image.height)
                            requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> Files.write(directory.resolve("${width}x${height}@${dpi}${if(dark) "-dark" else ""}.png"),data.bytes) }
                        }
                    } finally { scene.close() }
                }
            }
            Files.writeString(directory.resolve("index.html"),"<meta charset=\"utf-8\"><title>WFTools-PC 布局与 DPI 检查</title><style>body{font:14px Segoe UI;background:#eee}img{max-width:100%;display:block;border:1px solid #888}section{margin:24px}</style>" +
                Files.list(directory).use { files -> files.filter { it.toString().endsWith(".png") }.sorted().map { "<section><h3>${it.fileName}</h3><a href=\"${it.fileName}\"><img loading=\"lazy\" src=\"${it.fileName}\"></a></section>" }.toList().joinToString("") })
        } finally { doc.render.close(); session.close(); scope.cancel() }
    }
}
private object QaUiDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
    override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
}
