package com.ailife.wftools.pc.core

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class DerivedResourcesSaveTest {
    private val dir=Files.createTempDirectory("watch-derived-")
    private val store=WatchDocumentStore(dir.resolve("sessions"))
    private fun jpeg(color: Color): ByteArray {
        val image=BufferedImage(64,48,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { it.color=color; it.fillRect(0,0,64,48); it.dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image,"jpg",it) }.toByteArray()
    }
    private fun fixture(): Path {
        val file=dir.resolve("input-${System.nanoTime()}.watch")
        val entries=linkedMapOf(
            "watch.xml" to "<Watch name='Source' width='454' height='390' shape='Square' future='keep'><Layer type='text' text='A'/><Unknown><Nested flag='kept'/></Unknown></Watch>".toByteArray(),
            "Preview.JPG" to jpeg(Color.BLUE), "preview_dim.jpg" to jpeg(Color.BLACK), "orphan.bin" to byteArrayOf(9)
        )
        ZipOutputStream(Files.newOutputStream(file)).use { zip -> entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        return file
    }
    private fun read(path: Path,name: String)=ZipFile(path.toFile()).use { it.getInputStream(it.getEntry(name)).readBytes() }

    @Test fun dirtySaveUpdatesDerivedJpegsWithoutChangingDesignSpaceOrHistory() {
        val source=fixture(); val original=Files.readAllBytes(source); val preview=jpeg(Color.RED); val dim=jpeg(Color.DARK_GRAY)
        store.open(source).use { session ->
            session.setAttribute(setOf(session.layers.first().id),"text","B"); val revision=session.revision
            val target=dir.resolve("updated.watch")
            store.save(session,target,mapOf("preview.jpg" to preview,"preview_dim.jpg" to dim))
            assertArrayEquals(original,Files.readAllBytes(source))
            assertArrayEquals(preview,read(target,"Preview.JPG")); assertArrayEquals(dim,read(target,"preview_dim.jpg"))
            assertEquals(revision,session.revision); assertTrue(session.canUndo); assertFalse(session.hasUnsavedChanges)
            store.open(target).use { saved ->
                assertEquals(454,saved.toWatchProject().width); assertEquals(390,saved.toWatchProject().height); assertEquals("Square",saved.toWatchProject().shape)
            }
            val xml=read(target,"watch.xml").toString(Charsets.UTF_8)
            assertTrue(xml.contains("future=\"keep\"")); assertTrue(xml.contains("<Nested flag=\"kept\""))
            assertArrayEquals(byteArrayOf(9),read(target,"orphan.bin"))
            session.setRootAttribute("name","Later")
            store.save(session,target)
            assertArrayEquals(preview,read(target,"Preview.JPG"))
        }
    }
    @Test fun cleanSaveAndSaveAsIgnoreDerivedUpdatesAndRetainExactBytes() {
        val source=fixture(); val original=Files.readAllBytes(source)
        store.open(source).use { session ->
            store.save(session,derivedResources=mapOf("preview.jpg" to jpeg(Color.GREEN)))
            assertArrayEquals(original,Files.readAllBytes(source))
            val target=dir.resolve("clean-copy.watch"); store.save(session,target,mapOf("preview.jpg" to jpeg(Color.GREEN)))
            assertArrayEquals(original,Files.readAllBytes(target))
        }
    }
    @Test fun invalidDerivedResourcesLeaveOriginalAndDirtyStateIntact() {
        val source=fixture(); val original=Files.readAllBytes(source)
        store.open(source).use { session ->
            session.setRootAttribute("name","Edited")
            for (resources in listOf(mapOf("watch.xml" to jpeg(Color.RED)), mapOf("preview.jpg" to byteArrayOf(1,2,3)),
                mapOf("preview.jpg" to jpeg(Color.RED),"PREVIEW.JPG" to jpeg(Color.BLUE)))) {
                assertThrows(WatchDocumentException.SaveFailed::class.java) { store.save(session,derivedResources=resources) }
                assertArrayEquals(original,Files.readAllBytes(source)); assertTrue(session.hasUnsavedChanges)
            }
        }
    }
    @Test fun failedSaveDoesNotCommitStagedDerivedResource() {
        val source=fixture(); val original=Files.readAllBytes(source); val originalPreview=read(source,"Preview.JPG")
        store.open(source).use { session ->
            val asset=dir.resolve("asset.png"); Files.write(asset,byteArrayOf(1)); session.importAssets(listOf(asset))
            val blob=Files.list(session.workDir.parent.resolve("blobs")).use { it.findFirst().get() }
            Files.write(blob,byteArrayOf(2))
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.save(session,derivedResources=mapOf("preview.jpg" to jpeg(Color.RED))) }
            assertArrayEquals(original,Files.readAllBytes(source)); assertTrue(session.hasUnsavedChanges)
            Files.write(blob,byteArrayOf(1)); store.save(session)
            assertArrayEquals(originalPreview,read(source,"Preview.JPG"))
        }
    }
    @Test fun committedDerivedResourcesSurviveRecovery() {
        val source=fixture(); val preview=jpeg(Color.ORANGE); val session=store.open(source)
        session.setRootAttribute("name","Saved"); store.save(session,derivedResources=mapOf("preview.jpg" to preview))
        session.setRootAttribute("name","Draft"); val id=session.id; session.close()
        store.recover(id).use { recovered ->
            assertArrayEquals(preview,Files.readAllBytes(recovered.workDir.resolve("Preview.JPG")))
            val target=dir.resolve("recovered.watch"); store.save(recovered,target)
            assertArrayEquals(preview,read(target,"Preview.JPG"))
        }
    }
    @Test fun missingImmutableBaseRejectsSaveInsteadOfDroppingResources() {
        val source=fixture(); val original=Files.readAllBytes(source)
        store.open(source).use { session ->
            val base=session.workDir.parent.resolve("original.zip"); val offline=base.resolveSibling("original.offline")
            session.setRootAttribute("name","Edited"); Files.move(base,offline)
            try {
                assertThrows(WatchDocumentException.SaveFailed::class.java) { store.save(session) }
                assertArrayEquals(original,Files.readAllBytes(source)); assertTrue(session.hasUnsavedChanges)
            } finally { Files.move(offline,base) }
        }
    }
}
