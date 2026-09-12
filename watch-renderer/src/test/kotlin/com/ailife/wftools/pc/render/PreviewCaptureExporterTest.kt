package com.ailife.wftools.pc.render

import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import com.ailife.wftools2.domain.editor.watchmaker.WmThemeSet
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceLayer
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCaptureExporterTest {
    @Test fun pngUsesFixed512CanvasDocumentMaskAndCurrentPreviewSettings() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("preview-png-").toFile()
        val project = WmWatchfaceProject(
            width = 320,
            height = 386,
            shape = "Round",
            background = "000000",
            rootAttrs = linkedMapOf("bg_color" to "{ucolor2}"),
            themeSets = listOf(
                WmThemeSet(linkedMapOf("name" to "first", "ucolor" to "ff00ff")),
                WmThemeSet(linkedMapOf("name" to "second", "ucolor" to "0066ff")),
            ),
            wmLayers = listOf(
                solidLayer(color = "ff0000", display = "b", size = 128),
                solidLayer(color = "{ucolor}", display = "d", size = 128),
            ),
        )
        val state = WmTagsData.WmPreviewState(
            now = LocalDateTime.of(2026, 9, 12, 8, 30),
            accentColor1 = "00ff00",
            accentColor2 = "112233",
        )

        val bytes = PreviewCaptureExporter.capturePng(
            project = project,
            directory = directory,
            state = state,
            isBright = false,
            themeIndex = 1,
        )

        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), bytes.copyOf(4))
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        assertArgb("document shape must mask the PNG corner", 0x00000000, image.getRGB(0, 0))
        assertArgb("state accentColor2 must drive the document background", 0xff112233.toInt(), image.getRGB(256, 64))
        assertArgb("selected theme and dim-only visibility must drive the layer", 0xff0066ff.toInt(), image.getRGB(256, 256))
    }

    @Test fun defaultGifIsAThreeSecondTwelveFpsLoopWithChangingFrames() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("preview-gif-").toFile()
        val initial = LocalDateTime.of(2026, 9, 12, 8, 30, 0)
        val project = WmWatchfaceProject(
            shape = "Square",
            background = "000000",
            rootAttrs = linkedMapOf("bg_color" to "000000"),
            wmLayers = listOf(
                WmWatchfaceLayer(
                    "text",
                    linkedMapOf(
                        "text" to "{dsz}  {c_elapsed}",
                        "text_size" to "96",
                        "color" to "ffffff",
                    ),
                ),
            ),
        )

        val bytes = PreviewCaptureExporter.captureGif(
            project = project,
            directory = directory,
            state = WmTagsData.WmPreviewState(now = initial),
            isBright = true,
            themeIndex = 0,
        )

        assertArrayEquals("GIF89a".toByteArray(Charsets.US_ASCII), bytes.copyOf(6))
        val gif = decodeGif(bytes)
        assertEquals(36, gif.frames.size)
        assertTrue(gif.frames.all { it.width == 512 && it.height == 512 })
        assertEquals("NETSCAPE loop count 0 means infinite looping", 0, gif.loopCount)
        assertEquals("frame delays must add up to the requested three seconds", 300, gif.delaysHundredths.sum())
        assertTrue("12 fps needs GIF delays distributed between 8 and 9 centiseconds", gif.delaysHundredths.all { it in 8..9 })
        assertNotEquals("elapsed/time tags must produce changing animation frames", fingerprint(gif.frames.first()), fingerprint(gif.frames[12]))
        assertNotEquals("the animation must keep advancing instead of repeating one frame", fingerprint(gif.frames[12]), fingerprint(gif.frames[24]))
    }

    @Test fun gifAdvancesOneRenderSessionClockWithoutReloadingRuntime() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("preview-session-").toFile()
        val initial = LocalDateTime.of(2026, 9, 12, 8, 30, 0)
        val states = mutableListOf<WmTagsData.WmPreviewState>()
        var creations = 0
        lateinit var owned: RenderSession
        var script: Any? = null
        var media: Any? = null
        val project = dynamicProject(globalScript = "var_label='same runtime'")

        PreviewCaptureExporter.captureGif(
            project = project,
            directory = directory,
            state = WmTagsData.WmPreviewState(now = initial, elapsedSeconds = 7.0),
            isBright = true,
            themeIndex = 0,
            durationMillis = 1_000,
            fps = 4,
            onProgress = { _, _ ->
                states += owned.frame
                if (script == null) {
                    script = owned.script
                    media = owned.media
                } else {
                    assertSame(script, owned.script)
                    assertSame(media, owned.media)
                }
            },
            sessionFactory = { workDir ->
                creations++
                RenderSession(workDir, autoPlay = false, silentMedia = true).also { owned = it }
            },
        )

        assertEquals(1, creations)
        assertEquals(4, states.size)
        assertEquals(listOf(7.0, 7.25, 7.5, 7.75), states.map { it.elapsedSeconds })
        assertEquals(
            listOf(0L, 250L, 500L, 750L),
            states.map { java.time.Duration.between(initial, it.now).toMillis() },
        )
        assertSessionClosed(owned)
    }

    @Test fun gifRejectsOutOfRangeDurationAndFrameRate() {
        val project = dynamicProject()
        val directory = java.nio.file.Files.createTempDirectory("preview-limits-").toFile()
        fun assertInvalid(durationMillis: Long, fps: Int) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    PreviewCaptureExporter.captureGif(
                        project,
                        directory,
                        WmTagsData.WmPreviewState(),
                        isBright = true,
                        themeIndex = 0,
                        durationMillis = durationMillis,
                        fps = fps,
                    )
                }
            }
        }

        assertInvalid(durationMillis = 999, fps = 12)
        assertInvalid(durationMillis = 10_001, fps = 12)
        assertInvalid(durationMillis = 1_000, fps = 0)
        assertInvalid(durationMillis = 1_000, fps = 31)
    }

    @Test fun cancellationAndCallbackFailureCloseTheOwnedRenderSession() = runBlocking {
        val project = dynamicProject()
        val cancelDirectory = java.nio.file.Files.createTempDirectory("preview-cancel-").toFile()
        lateinit var cancelledSession: RenderSession
        val firstFrame = CompletableDeferred<Unit>()
        val job = launch {
            PreviewCaptureExporter.captureGif(
                project = project,
                directory = cancelDirectory,
                state = WmTagsData.WmPreviewState(),
                isBright = true,
                themeIndex = 0,
                durationMillis = 10_000,
                fps = 30,
                onProgress = { completed, _ ->
                    if (completed == 1) {
                        firstFrame.complete(Unit)
                        awaitCancellation()
                    }
                },
                sessionFactory = { workDir ->
                    RenderSession(workDir, autoPlay = false, silentMedia = true).also { cancelledSession = it }
                },
            )
        }
        withTimeout(10_000) { firstFrame.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertSessionClosed(cancelledSession)

        val failureDirectory = java.nio.file.Files.createTempDirectory("preview-failure-").toFile()
        lateinit var failedSession: RenderSession
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                PreviewCaptureExporter.captureGif(
                    project = project,
                    directory = failureDirectory,
                    state = WmTagsData.WmPreviewState(),
                    isBright = true,
                    themeIndex = 0,
                    durationMillis = 1_000,
                    fps = 2,
                    onProgress = { _, _ -> error("progress failed") },
                    sessionFactory = { workDir ->
                        RenderSession(workDir, autoPlay = false, silentMedia = true).also { failedSession = it }
                    },
                )
            }
        }
        assertEquals("progress failed", failure.message)
        assertSessionClosed(failedSession)
    }

    private fun dynamicProject(globalScript: String = "") = WmWatchfaceProject(
        shape = "Square",
        background = "000000",
        rootAttrs = linkedMapOf("bg_color" to "000000"),
        globalScript = globalScript,
        wmLayers = listOf(
            WmWatchfaceLayer(
                "text",
                linkedMapOf("text" to "{c_elapsed}", "text_size" to "72", "color" to "ffffff"),
            ),
        ),
    )

    private fun solidLayer(color: String, display: String, size: Int) = WmWatchfaceLayer(
        "shape",
        linkedMapOf(
            "width" to size.toString(),
            "height" to size.toString(),
            "shape" to "Square",
            "color" to color,
            "display" to display,
        ),
    )

    private fun assertSessionClosed(session: RenderSession) {
        assertThrows(IllegalStateException::class.java) {
            session.media.video(File(session.directory, "closed-session-probe.mp4"))
        }
    }

    private fun assertArgb(message: String, expected: Int, actual: Int) {
        assertEquals(message, expected.toUInt().toString(16), actual.toUInt().toString(16))
    }

    private fun fingerprint(image: BufferedImage): Int =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width).contentHashCode()

    private fun decodeGif(bytes: ByteArray): GifInfo {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        return MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            reader.input = input
            try {
                val count = reader.getNumImages(true)
                val frames = (0 until count).map { reader.read(it) }
                val metadata = (0 until count).map { reader.getImageMetadata(it) }
                GifInfo(
                    frames = frames,
                    delaysHundredths = metadata.map { it.intAttribute("GraphicControlExtension", "delayTime") },
                    loopCount = metadata.asSequence().mapNotNull(::loopCount).firstOrNull(),
                )
            } finally {
                reader.dispose()
            }
        }
    }

    private fun IIOMetadata.intAttribute(nodeName: String, attribute: String): Int {
        val node = nodes(nodeName).single()
        return node.getAttribute(attribute).toInt()
    }

    private fun loopCount(metadata: IIOMetadata): Int? {
        val extension = metadata.nodes("ApplicationExtension").firstOrNull {
            it.getAttribute("applicationID") == "NETSCAPE" && it.getAttribute("authenticationCode") == "2.0"
        } ?: return null
        val payload = extension.userObject as? ByteArray ?: return null
        if (payload.size < 3 || payload[0].toInt() != 1) return null
        return (payload[1].toInt() and 0xff) or ((payload[2].toInt() and 0xff) shl 8)
    }

    private fun IIOMetadata.nodes(name: String): List<IIOMetadataNode> {
        val format = nativeMetadataFormatName
        val matches = mutableListOf<IIOMetadataNode>()
        fun visit(node: org.w3c.dom.Node) {
            if (node.nodeName == name) matches += node as IIOMetadataNode
            val children = node.childNodes
            for (index in 0 until children.length) visit(children.item(index))
        }
        visit(getAsTree(format))
        return matches
    }

    private data class GifInfo(
        val frames: List<BufferedImage>,
        val delaysHundredths: List<Int>,
        val loopCount: Int?,
    )
}
