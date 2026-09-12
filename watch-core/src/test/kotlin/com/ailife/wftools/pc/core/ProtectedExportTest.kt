package com.ailife.wftools.pc.core

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.*
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class ProtectedExportTest {
    private val root = Files.createTempDirectory("watch-protected-")
    private val store = WatchDocumentStore(root.resolve("sessions"))
    private fun fixture(xml: String, assets: Map<String, ByteArray> = emptyMap()): Path {
        val path = root.resolve("${System.nanoTime()}.watch")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            (linkedMapOf("watch.xml" to xml.toByteArray()) + assets).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return path
    }
    private fun read(path: Path, name: String) = ZipFile(path.toFile()).use { it.getInputStream(it.getEntry(name)).readBytes() }
    private fun names(path: Path) = ZipFile(path.toFile()).use { it.entries().asSequence().map { e -> e.name }.toSet() }
    // Independent copies of the frozen 201cde43 protocol, not the implementation under test.
    private fun decodeXml(bytes: ByteArray): String {
        var text = bytes.toString(Charsets.UTF_8).replace("g", "#").replace("D", "g").replace("#", "D")
        text = text.replace("4", "#").replace("L", "4").replace("#", "L")
        return Base64.getDecoder().decode(text).toString(Charsets.UTF_8)
    }
    private fun xor(bytes: ByteArray) = bytes.mapIndexed { index, value -> (value.toInt() xor "SWHn-"[index % 5].code).toByte() }.toByteArray()
    private fun tree(path: Path) = Files.walk(path).use { files -> files.filter { Files.isRegularFile(it) }.toList().associate { path.relativize(it).toString() to WatchDocumentStore.hashFile(it) } }
    private fun state(session: DocumentSession): List<Any?> = listOf(session.sourcePath, session.sourceHash, session.savedSignature,
        session.state, session.revision, session.hasUnsavedChanges, session.undoStack.toList(), session.redoStack.toList(), session.savedDerivedResources)
    private fun jpeg() = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"jpg",it) }.toByteArray()

    @Test fun protectedExportUsesFrozenCodecAndPreservesFullCurrentDocumentAndResources() {
        val image = byteArrayOf(0, 1, 2, 3, -1, 42)
        val xml = "<Watch name='源 &amp; 表盘' width='454' height='390' shape='S' author='作者' future='yes'><Layers><Layer type='image' path='天气/白天.png' x='({dh}+1)*2'/><Layer type='image' path='中文.JPG'/></Layers><Unknown><Deep flag='yes'/><![CDATA[untouched]]></Unknown></Watch>"
        val source = fixture(xml, mapOf("images/天气/白天.png" to image, "images/中文.JPG" to image,
            "images/orphan.png" to image, "fonts/orphan.ttf" to byteArrayOf(4), "fonts_bm/atlas.png" to image,
            "fonts_bm/font.fnt" to "page id=0 file=\"atlas.png\"".toByteArray(), "scripts/script.txt" to "var_count=1".toByteArray(),
            "unknown/data.bin" to byteArrayOf(9), "videos/clip.mp4" to byteArrayOf(8)))
        val sourceHash = WatchDocumentStore.hashFile(source)
        store.open(source).use { session ->
            session.setRootAttribute("name", "当前文档")
            val before = state(session); val workspace = tree(session.home)
            val target = root.resolve("protected.watch"); store.exportProtected(session,target)
            val decoded = WatchXml.parse(decodeXml(read(target,"watch.pxml")))
            assertEquals("当前文档",decoded.documentElement.getAttribute("name"));assertEquals("S",decoded.documentElement.getAttribute("shape"))
            assertEquals("454",decoded.documentElement.getAttribute("width"));assertEquals("390",decoded.documentElement.getAttribute("height"))
            assertEquals("({dh}+1)*2",WatchXml.layers(decoded)[0].getAttribute("x"))
            assertEquals("天气/白天.ppng",WatchXml.layers(decoded)[0].getAttribute("path"));assertEquals("中文.pjpg",WatchXml.layers(decoded)[1].getAttribute("path"))
            assertEquals(1,decoded.getElementsByTagName("Deep").length);assertTrue(WatchXml.serialize(decoded).contains("<![CDATA[untouched]]>"))
            val placeholder=WatchXml.parse(read(target,"watch.xml").toString(Charsets.UTF_8))
            assertEquals("y",placeholder.documentElement.getAttribute("protection"));assertTrue(WatchXml.layers(placeholder).isEmpty())
            val outputNames=names(target)
            assertFalse("images/天气/白天.png" in outputNames);assertFalse("images/中文.JPG" in outputNames);assertFalse("images/orphan.png" in outputNames)
            for(name in listOf("images/天气/白天.ppng","images/中文.pjpg","images/orphan.ppng")) assertArrayEquals(image,xor(read(target,name)))
            assertArrayEquals(image,read(target,"fonts_bm/atlas.png"));assertArrayEquals(byteArrayOf(4),read(target,"fonts/orphan.ttf"))
            assertArrayEquals(byteArrayOf(9),read(target,"unknown/data.bin"));assertArrayEquals(byteArrayOf(8),read(target,"videos/clip.mp4"))
            assertEquals("var_count=1",read(target,"scripts/script.txt").toString(Charsets.UTF_8))
            assertEquals(before,state(session));assertEquals(workspace,tree(session.home));assertEquals(sourceHash,WatchDocumentStore.hashFile(source))
        }
    }
    @Test fun importedRemovedScriptAndDerivedOutputsDoNotAlterSession() {
        val source=fixture("<Watch shape='R'><Layers/></Watch>",mapOf("images/deleted.png" to byteArrayOf(1),"Preview.JPG" to jpeg()))
        store.open(source).use { session ->
            session.deleteAsset("images/deleted.png")
            val external=root.resolve("导入.png");Files.write(external,byteArrayOf(7,8));session.importAssets(listOf(external))
            session.setGlobalScript("var_count=42")
            val before=state(session);val workspace=tree(session.home);val derived=jpeg()
            val target=root.resolve("edited-protected.watch");store.exportProtected(session,target,mapOf("preview.jpg" to derived,"preview_dim.jpg" to derived))
            assertFalse(names(target).any { it.contains("deleted") });assertArrayEquals(byteArrayOf(7,8),xor(read(target,"images/导入.ppng")))
            assertArrayEquals(derived,read(target,"Preview.JPG"));assertArrayEquals(derived,read(target,"preview_dim.jpg"))
            assertEquals("var_count=42",read(target,"scripts/script.txt").toString(Charsets.UTF_8))
            assertEquals(before,state(session));assertEquals(workspace,tree(session.home));assertArrayEquals(byteArrayOf(7,8),Files.readAllBytes(external))
        }
    }
    @Test fun cleanSessionExportsPreviewAndPreservesOriginalEmptyOrWhitespaceScriptBytes() {
        for (bytes in listOf(byteArrayOf(), " \r\n\t ".toByteArray())) {
            val source=fixture("<Watch shape='S'/>",mapOf("Scripts/Script.txt" to bytes))
            store.open(source).use { session ->
                val before=state(session);val target=root.resolve("clean-${System.nanoTime()}.watch");val preview=jpeg()
                store.exportProtected(session,target,mapOf("preview.jpg" to preview))
                assertArrayEquals(preview,read(target,"preview.jpg"));assertArrayEquals(bytes,read(target,"Scripts/Script.txt"));assertEquals(before,state(session))
            }
        }
    }
    @Test fun onlyExplicitlyClearedScriptIsOmitted() {
        store.open(fixture("<Watch/>",mapOf("scripts/script.txt" to "var_value=2".toByteArray()))).use { session ->
            session.setGlobalScript("");val before=state(session);val target=root.resolve("cleared.watch")
            store.exportProtected(session,target)
            assertFalse("scripts/script.txt" in names(target));assertEquals(before,state(session))
        }
    }
    @Test fun invalidResourceOrTwinCollisionLeavesDestinationAndSessionUntouched() {
        val source=fixture("<Watch/>",mapOf("images/a.jpg" to byteArrayOf(1),"images/a.jpeg" to byteArrayOf(2)))
        store.open(source).use { session ->
            val target=root.resolve("collision.watch");val before=state(session);val workspace=tree(session.home)
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.exportProtected(session,target) }
            assertFalse(Files.exists(target));assertEquals(before,state(session));assertEquals(workspace,tree(session.home))
            assertFalse(Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".wftools-") } })
        }
        store.newDocument().use { session ->
            val target=root.resolve("bad-preview.watch")
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.exportProtected(session,target,mapOf("preview.jpg" to byteArrayOf(1))) }
            assertFalse(Files.exists(target))
        }
    }
    @Test fun damagedImportedBlobFailsWithoutPublishingDestination() {
        store.newDocument().use { session ->
            val asset=root.resolve("damaged.png");Files.write(asset,byteArrayOf(1));session.importAssets(listOf(asset))
            val blob=session.home.resolve("blobs").resolve(session.state.importedAssets.values.single());Files.write(blob,byteArrayOf(2))
            val target=root.resolve("untouched.watch");val before=state(session)
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.exportProtected(session,target) }
            assertFalse(Files.exists(target));assertEquals(before,state(session))
            assertFalse(Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".wftools-") } })
        }
    }
    @Test fun sourceAndWorkspaceDestinationsAreRejected() {
        val source=fixture("<Watch/>");val hash=WatchDocumentStore.hashFile(source)
        store.open(source).use { session ->
            for(target in listOf(source,session.workDir.resolve("protected.watch")))
                assertThrows(WatchDocumentException.SaveFailed::class.java) { store.exportProtected(session,target) }
            assertEquals(hash,WatchDocumentStore.hashFile(source))
        }
    }
    @Test fun missingStaticImageFailsButDynamicImageExpressionsRemainUnevaluated() {
        store.open(fixture("<Watch><Layer type='image' path='missing.png'/></Watch>")).use { session ->
            val target=root.resolve("missing.watch")
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.exportProtected(session,target) }
            assertFalse(Files.exists(target));assertFalse(session.hasUnsavedChanges)
            assertFalse(Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".wftools-") } })
        }
        store.open(fixture("<Watch><Layer type='image' path=\"'frame_'..{ds}..'.png'\"/></Watch>",mapOf("images/frame_1.png" to byteArrayOf(3)))).use { session ->
            val target=root.resolve("dynamic.watch");store.exportProtected(session,target)
            val xml=WatchXml.parse(decodeXml(read(target,"watch.pxml")))
            assertEquals("'frame_'..{ds}..'.ppng'",WatchXml.layers(xml).single().getAttribute("path"))
            assertArrayEquals(byteArrayOf(3),xor(read(target,"images/frame_1.ppng")))
        }
    }
    @Test fun protectedValidationRejectsInvalidPxmlAndEncryptedResourceCrcDamage() {
        val image="unique encrypted image bytes".toByteArray()
        store.open(fixture("<Watch><Layer type='image' path='one.png'/></Watch>",mapOf("images/one.png" to image))).use { session ->
            val target=root.resolve("valid.watch");store.exportProtected(session,target)
            val entries=names(target).associateWith { read(target,it) }
            val originalXml=decodeXml(entries.getValue("watch.pxml"))
            val expected=ProtectedWatchArchive.Expected(originalXml,entries.mapValues { WatchDocumentStore.hashBytes(it.value) },
                mapOf("images/one.ppng" to WatchDocumentStore.hashBytes(image)))
            ProtectedWatchArchive.validate(target,expected)
            val invalidXml=entries+mapOf("watch.pxml" to "not valid base64!".toByteArray())
            val broken=root.resolve("broken-pxml.watch")
            ZipOutputStream(Files.newOutputStream(broken)).use { zip -> invalidXml.forEach { (name,bytes) ->
                zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
            } }
            assertThrows(WatchDocumentException.SaveFailed::class.java) {
                ProtectedWatchArchive.validate(broken,expected.copy(hashes=invalidXml.mapValues { WatchDocumentStore.hashBytes(it.value) }))
            }
            val stored=root.resolve("bad-crc.watch")
            ZipOutputStream(Files.newOutputStream(stored)).use { zip -> entries.forEach { (name,bytes) ->
                zip.putNextEntry(ZipEntry(name).apply {
                    method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size
                    crc=java.util.zip.CRC32().also { it.update(bytes) }.value
                });zip.write(bytes);zip.closeEntry()
            } }
            val ciphertext=entries.getValue("images/one.ppng");val corrupt=Files.readAllBytes(stored)
            val at=(0..corrupt.size-ciphertext.size).first { offset -> ciphertext.indices.all { corrupt[offset+it]==ciphertext[it] } }
            corrupt[at]=(corrupt[at].toInt() xor 1).toByte();Files.write(stored,corrupt)
            assertThrows(WatchDocumentException.InvalidArchive::class.java) { store.validateArchive(stored,allowProtected=true) }
        }
    }
    @Test fun existingDestinationIsNeverOverwritten() {
        store.open(fixture("<Watch/>")).use { session ->
            val target=root.resolve("existing.watch");Files.writeString(target,"EXISTING");val before=state(session)
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.exportProtected(session,target) }
            assertEquals("EXISTING",Files.readString(target));assertEquals(before,state(session))
        }
    }
    @Test fun creationAfterFinalCheckCannotBeOverwritten() {
        store.open(fixture("<Watch/>")).use { session ->
            val target=root.resolve("raced.watch");val before=state(session);val workspace=tree(session.home);var reached=false
            assertThrows(WatchDocumentException.SaveFailed::class.java) {
                store.exportProtected(session,target,emptyMap()) { path ->
                    assertFalse(Files.exists(path));reached=true
                    Files.writeString(path,"EXTERNAL",StandardOpenOption.CREATE_NEW)
                }
            }
            assertTrue(reached);assertEquals("EXTERNAL",Files.readString(target));assertEquals(before,state(session));assertEquals(workspace,tree(session.home))
            assertFalse(Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".wftools-") } })
        }
    }
    @Test fun resourceMappingLeavesTextUnknownNodesUrlsAndBitmapAtlasesUnchanged() {
        val xml="<Watch name='dial.png' description='manual.jpeg'><Layer type='text' text='Show image.png and photo.jpg'/><Layer type='image' path='album.png/截图.png.backup.JPG'/><Layer type='image' path='https://example.invalid/a.png'/><Layer type='shape' texture='fonts_bm/atlas.png'/><Unknown file='unknown/layout.png.json' texture='images/other.png'><![CDATA[notes.png]]></Unknown></Watch>"
        store.open(fixture(xml,mapOf("images/album.png/截图.png.backup.JPG" to byteArrayOf(1),"fonts_bm/atlas.png" to byteArrayOf(2),
            "unknown/layout.png.json" to byteArrayOf(3),"images/other.png" to byteArrayOf(4)))).use { session ->
            val target=root.resolve("mapped.watch");store.exportProtected(session,target)
            val document=WatchXml.parse(decodeXml(read(target,"watch.pxml")));val layers=WatchXml.layers(document)
            assertEquals("dial.png",document.documentElement.getAttribute("name"));assertEquals("manual.jpeg",document.documentElement.getAttribute("description"))
            assertEquals("Show image.png and photo.jpg",layers[0].getAttribute("text"))
            assertEquals("album.png/截图.png.backup.pjpg",layers[1].getAttribute("path"));assertEquals("https://example.invalid/a.png",layers[2].getAttribute("path"))
            assertEquals("fonts_bm/atlas.png",layers[3].getAttribute("texture"))
            val unknown=document.getElementsByTagName("Unknown").item(0) as org.w3c.dom.Element
            assertEquals("unknown/layout.png.json",unknown.getAttribute("file"));assertEquals("images/other.png",unknown.getAttribute("texture"));assertEquals("notes.png",unknown.textContent)
            assertArrayEquals(byteArrayOf(1),xor(read(target,"images/album.png/截图.png.backup.pjpg")))
            assertArrayEquals(byteArrayOf(3),read(target,"unknown/layout.png.json"));assertArrayEquals(byteArrayOf(2),read(target,"fonts_bm/atlas.png"))
        }
    }
    @Test fun optionalRealSampleProtectedExportUsesOnlyACopy() {
        val configured=System.getProperty("watch.sample","");org.junit.Assume.assumeTrue(configured.isNotBlank())
        val input=Path.of(configured).toAbsolutePath().normalize()
        require(!input.toString().equals("D:\\Administrator\\Downloads\\312.watch",true)) { "请配置已经复制到测试目录的样本。" }
        val originalHash=WatchDocumentStore.hashFile(input);val copied=root.resolve("copied-sample.watch");Files.copy(input,copied)
        store.open(copied).use { session ->
            val before=state(session);val work=tree(session.home);val target=root.resolve("protected-sample.watch")
            store.exportProtected(session,target)
            val decoded=WatchXml.parse(decodeXml(read(target,"watch.pxml")))
            assertEquals(session.layers.size,WatchXml.layers(decoded).size)
            assertEquals(session.toWatchProject().rootAttrs["shape"].orEmpty(),decoded.documentElement.getAttribute("shape"))
            assertEquals(before,state(session));assertEquals(work,tree(session.home))
        }
        assertEquals(originalHash,WatchDocumentStore.hashFile(input));assertEquals(originalHash,WatchDocumentStore.hashFile(copied))
    }
}
