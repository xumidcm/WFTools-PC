@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.ailife.wftools.pc.render

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.ailife.wftools.pc.preview.DevicePreviewSettings
import com.ailife.wftools.pc.preview.WatchSurface
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat

internal class OffscreenWatchScene(private val scene:ImageComposeScene) {
    suspend fun pump(frameCount:Int=4,delayMillis:Long=20) {
        repeat(frameCount) {
            scene.render(System.nanoTime()).close()
            if(delayMillis>0)delay(delayMillis)
        }
    }

    fun renderPixels(presentationTimeNanos:Long=System.nanoTime()):IntArray =
        scene.render(presentationTimeNanos).use { image ->
            IntArray(image.width*image.height).also { pixels ->
                image.toComposeImageBitmap().readPixels(pixels)
            }
        }

    fun renderEncoded(format:EncodedImageFormat,quality:Int=100):ByteArray =
        scene.render(System.nanoTime()).use { image ->
            requireNotNull(image.encodeToData(format,quality)).use { it.bytes }
        }
}

internal suspend fun <T> withOffscreenWatchScene(
    project:WmWatchfaceProject,
    render:RenderSession,
    device:DevicePreviewSettings,
    block:suspend OffscreenWatchScene.()->T,
):T = withContext(RenderUiDispatcher) {
    val scene=ImageComposeScene(device.width,device.height,Density(1f),coroutineContext=coroutineContext) {
        WatchSurface(project,render,device)
    }
    try {
        OffscreenWatchScene(scene).block()
    } finally {
        scene.close()
    }
}

/** Starts each real video once so its first decoded frame is ready for offscreen composition. */
internal suspend fun primeVideoFrames(
    project:WmWatchfaceProject,
    directory:File,
    render:RenderSession,
    owner:String,
) {
    val root=directory.canonicalFile.toPath()
    val videos=project.wmLayers.asSequence()
        .filter { it.layerType=="video" }
        .mapNotNull { layer ->
            runCatching {
                val relative=layer.getAttr("path").replace('\\','/').removePrefix("videos/")
                root.resolve("videos").resolve(relative).normalize().takeIf { it.startsWith(root) && it.toFile().isFile }
            }.getOrNull()
        }
        .distinct()
        .toList()
    for(path in videos) {
        try {
            withContext(Dispatchers.IO) {
                val player=render.media.video(path.toFile())
                try {
                    player.setOwnerPlaying(owner,true)
                    withTimeoutOrNull(5_000){player.frames.filterNotNull().first()}
                } finally {
                    player.removeOwner(owner)
                }
            }
        } catch(cancel:CancellationException) {
            throw cancel
        } catch(_:Exception) {
            // The surface will show its normal media error/placeholder state.
        }
    }
}
