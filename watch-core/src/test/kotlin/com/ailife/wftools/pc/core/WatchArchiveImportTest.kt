package com.ailife.wftools.pc.core

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class WatchArchiveImportTest {
    private val root = Files.createTempDirectory("watch-import-archive-")
    private val store = WatchDocumentStore(root.resolve("sessions"))

    @Test fun `watch archive import copies supported resources creates visual layers and ignores project script`() {
        val existing = root.resolve("planet.png")
        ImageIO.write(BufferedImage(9,7,BufferedImage.TYPE_INT_ARGB),"png",existing.toFile())
        val source = root.resolve("source.watch")
        ZipOutputStream(Files.newOutputStream(source)).use { zip ->
            write(zip,"watch.xml","<Watch name='Source' width='512' height='512' shape='S'><Layers/></Watch>".toByteArray())
            write(zip,"images/planet.png",Files.readAllBytes(existing))
            write(zip,"videos/clip.mp4","video".toByteArray())
            write(zip,"fonts/Regular.ttf","font".toByteArray())
            write(zip,"fonts_bm/digits.fnt","info face=\"Digits\" size=12\ncommon lineHeight=12 base=10 scaleW=1 scaleH=1 pages=1\npage id=0 file=\"atlas.png\"\n".toByteArray())
            write(zip,"fonts_bm/atlas.png","atlas".toByteArray())
            write(zip,"sfx/tap.mp3","sound".toByteArray())
            write(zip,"scripts/script.txt","should_not_be_imported = true".toByteArray())
        }
        store.newDocument().use { session ->
            session.importAssets(listOf(existing),createLayers=false)
            session.importWatchAssets(source)
            val assets = Files.walk(session.workDir).use { it.filter(Files::isRegularFile).map { path -> session.workDir.relativize(path).toString().replace('\\','/') }.toList() }
            assertTrue(assets.any { it.equals("images/planet.png",true) })
            assertTrue(assets.any { it.equals("images/planet_2.png",true) })
            assertTrue(assets.any { it.equals("videos/clip.mp4",true) })
            assertTrue(assets.any { it.equals("fonts/Regular.ttf",true) })
            assertTrue(assets.any { it.equals("fonts_bm/digits.fnt",true) })
            assertTrue(assets.any { it.equals("fonts_bm/atlas.png",true) })
            assertTrue(assets.any { it.equals("sfx/tap.mp3",true) })
            assertFalse(assets.any { it.startsWith("scripts/") })
            assertEquals(4,session.layers.size)
            assertFalse("A BMFont atlas is a companion resource, not a standalone image layer",session.layers.any { it.type == "image" && it.attrs["path"] == "atlas.png" })
            assertTrue(session.layers.any { it.type == "image" && it.attrs["path"] == "planet_2.png" })
            assertTrue(session.layers.any { it.type == "video" && it.attrs["path"] == "clip.mp4" })
            assertTrue(session.layers.any { it.type == "text" && it.attrs["font"] == "Regular" })
            assertTrue(session.layers.any { it.type == "text" && it.attrs["font"] == "bm:digits" })
            assertEquals("",session.toWatchProject().globalScript)
        }
    }

    private fun write(zip: ZipOutputStream,name: String,bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    }
}
