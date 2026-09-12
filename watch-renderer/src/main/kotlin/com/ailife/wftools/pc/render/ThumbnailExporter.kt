package com.ailife.wftools.pc.render

import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import java.io.File
import com.ailife.wftools.pc.preview.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import javax.swing.SwingUtilities
import org.jetbrains.skia.EncodedImageFormat

object ThumbnailExporter {
    /** Device/workspace settings deliberately cannot be supplied to this API. */
    suspend fun generate(project: WmWatchfaceProject, directory: File): Map<String, ByteArray> {
        val render=RenderSession(directory,autoPlay=false,silentMedia=true)
        try {
            render.prepare(project)
            primeVideoFrames(project,directory,render,"thumbnail-prime")
            val device=DevicePreviewSettings(project.width.coerceIn(64,2048),project.height.coerceIn(64,2048),preset=CanvasPreset.FIT)
            return withOffscreenWatchScene(project,render,device) {
                val output=linkedMapOf<String,ByteArray>()
                for(bright in listOf(true,false)) {
                    render.isBright=bright
                    render.bind(project).join()
                    pump()
                    output[if(bright)"preview.jpg" else "preview_dim.jpg"]=renderEncoded(EncodedImageFormat.JPEG,92)
                }
                output
            }
        } finally {withContext(NonCancellable+Dispatchers.IO){render.close()}}
    }
}

internal object RenderUiDispatcher:CoroutineDispatcher() {
    override fun isDispatchNeeded(context:CoroutineContext)=!SwingUtilities.isEventDispatchThread()
    override fun dispatch(context:CoroutineContext,block:Runnable)=SwingUtilities.invokeLater(block)
}
