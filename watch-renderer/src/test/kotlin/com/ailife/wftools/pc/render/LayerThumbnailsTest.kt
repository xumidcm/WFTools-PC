package com.ailife.wftools.pc.render

import com.ailife.wftools.pc.core.EditorLayer
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LayerThumbnailsTest {
    @Test fun videoThumbnailUsesIndependentSilentDecoderWithoutStartingPausedPreview()=runBlocking {
        val directory=Files.createTempDirectory("watch-thumb-video")
        Files.createDirectories(directory.resolve("videos"))
        val file=directory.resolve("videos/colors.avi").toFile();file.writeBytes(RuntimeIntegrationTest().tinyAvi())
        RenderSession(directory.toFile(),false).use { renderer ->
            val previewPlayer=renderer.media.video(file)
            val layer=EditorLayer("video-id","video",mapOf("path" to "colors.avi"))
            assertNotNull(LayerThumbnails.render(layer,renderer,96))
            assertNull("thumbnail must not start the document's paused player",previewPlayer.frames.value)
            assertFalse(renderer.playing)
        }
    }
    @Test fun textThumbnailUsesResolvedContentAndIgnoresCanvasPositionAndHiddenState()=runBlocking {
        RenderSession(Files.createTempDirectory("watch-layer-thumb").toFile(),false).use { renderer ->
            renderer.prepare(WmWatchfaceProject(globalScript="var_label='12:34'"))
            val layer=EditorLayer("stable-id","text",linkedMapOf("text" to "{var_label}","x" to "9000","y" to "-4000","rotation" to "135","opacity" to "0","display" to "h","color" to "ffffff","text_size" to "42"))
            val image=LayerThumbnails.render(layer,renderer,96)
            assertNotNull(image)
            val pixels=IntArray(96*96);image!!.readPixels(pixels)
            assertTrue("font glyphs must exist",pixels.count{it and 0xffffff>0xdddddd}>100)
            val centered=LayerThumbnails.render(layer.copy(attrs=layer.attrs+mapOf("x" to "0","y" to "0","rotation" to "0")),renderer,96)
            val other=IntArray(96*96);centered!!.readPixels(other)
            assertArrayEquals(pixels,other)
        }
    }
}
