package com.ailife.wftools.pc.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WatchDocumentStoreTest {
    private val dir = Files.createTempDirectory("watch-core-test-")
    private val store = WatchDocumentStore(dir.resolve("sessions"))
    private val xml = """<?xml version="1.0"?><Watch name="Sample" width="454" height="390" shape="Square" format="2.0" custom="preserve"><!--keep--><ThemeSets><ThemeSet future="yes"><Deep flag="1"/></ThemeSet></ThemeSets><Layers><Layer type="text" x="{dh}*2" y="5" text="A"><Future><![CDATA[a < b]]><Nested n="7"/></Future></Layer><Layer type="image" path="old.png"/><Layer type="shape"/></Layers><Unknown attr="yes">tail</Unknown></Watch>"""
    private fun archive(content: String = xml): Path {
        val path = dir.resolve("${System.nanoTime()}.watch")
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            mapOf("watch.xml" to content.toByteArray(), "images/old.png" to byteArrayOf(1,2,3), "orphan.bin" to byteArrayOf(9), "scripts/script.txt" to "var_a = 3".toByteArray()).forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return path
    }
    private fun read(path: Path, entry: String) = ZipFile(path.toFile()).use { it.getInputStream(it.getEntry(entry)).readBytes() }

    @Test fun cleanSaveAndPreviewNeverModifyOriginalBytes() {
        val path = archive(); val original = Files.readAllBytes(path)
        store.open(path).use { s ->
            assertEquals(454, s.toWatchProject().width); assertEquals(390, s.toWatchProject().height)
            assertEquals("Square", s.toWatchProject().shape); assertFalse(s.hasUnsavedChanges)
            store.save(s); assertArrayEquals(original, Files.readAllBytes(path))
        }
    }
    @Test fun editsKeepUnknownSubtreesScriptsAndUnreferencedResources() {
        val path = archive(); val original = Files.readAllBytes(path)
        store.open(path).use { s ->
            s.setAttribute(setOf(s.layers.first().id), "text", "B & C")
            assertArrayEquals(original, Files.readAllBytes(path))
            val target = dir.resolve("saved.watch"); store.save(s,target)
            val saved = read(target,"watch.xml").toString(Charsets.UTF_8)
            assertTrue(saved.contains("<Nested n=\"7\"")); assertTrue(saved.contains("<![CDATA[a < b]]>"))
            assertTrue(saved.contains("<!--keep-->")); assertTrue(saved.contains("custom=\"preserve\""))
            assertArrayEquals(byteArrayOf(9),read(target,"orphan.bin"))
            assertEquals("var_a = 3",read(target,"scripts/script.txt").toString(Charsets.UTF_8))
            assertEquals("B & C",store.open(target).use { it.layers.first().attrs["text"] })
        }
    }
    @Test fun stableSelectionSurvivesReorderUndoAndRedo() {
        store.open(archive()).use { s ->
            val ids=s.layers.map { it.id }; s.select(ids[1],ids); s.select(ids[2],ids,ctrl=true)
            s.moveSelected(0); assertEquals(listOf(ids[1],ids[2],ids[0]),s.layers.map { it.id })
            assertEquals(setOf(ids[1],ids[2]),s.selection.selected)
            s.undo(); assertEquals(ids,s.layers.map { it.id }); assertEquals(ids[2],s.selection.primary)
            s.redo(); assertEquals(ids[1],s.layers.first().id)
        }
    }
    @Test fun shiftSelectionUsesVisibleOrderAndStableAnchor() {
        store.open(archive()).use { s ->
            val ids=s.layers.map { it.id }.reversed(); s.select(ids.first(),ids); s.select(ids.last(),ids,shift=true)
            assertEquals(ids.toSet(),s.selection.selected); assertEquals(ids.first(),s.selection.anchor)
        }
    }
    @Test fun batchNudgePreservesExpressionAndIsSingleUndo() {
        store.open(archive()).use { s ->
            val ids=s.layers.take(2).map { it.id }; s.selectAll(ids); s.nudge(3.0,-2.0)
            assertEquals("({dh}*2)+(3)",s.layers.first().attrs["x"]); assertEquals("3",s.layers.first().attrs["y"])
            s.undo(); assertEquals("{dh}*2",s.layers.first().attrs["x"]); assertFalse(s.canUndo); assertFalse(s.hasUnsavedChanges)
        }
    }
    @Test fun duplicateAndClipboardPreserveNestedLayerXmlWithFreshIds() {
        store.open(archive()).use { s ->
            val id=s.layers.first().id; s.select(id,s.layers.map { it.id }); val copy=s.copySelection()
            s.duplicateSelected(); assertEquals(4,s.layers.size); assertNotEquals(id,s.selection.primary)
            s.pasteLayers(copy); assertEquals(5,s.layers.size); assertEquals(5,s.layers.map { it.id }.toSet().size)
            val target=dir.resolve("clipboard.watch"); store.save(s,target)
            assertEquals(3,Regex("<Nested").findAll(read(target,"watch.xml").toString(Charsets.UTF_8)).count())
        }
    }
    @Test fun externallyChangedSourceIsNeverOverwritten() {
        val path=archive(); store.open(path).use { s ->
            s.setRootAttribute("name","Edited"); Files.write(path,byteArrayOf(4,5,6))
            assertThrows(WatchDocumentException.ExternalModification::class.java) { store.save(s) }
            assertArrayEquals(byteArrayOf(4,5,6),Files.readAllBytes(path)); assertTrue(s.hasUnsavedChanges)
        }
    }
    @Test fun failedSaveRetainsOriginalAndDirtyState() {
        val path=archive(); val original=Files.readAllBytes(path)
        store.open(path).use { s ->
            s.setRootAttribute("name","Edited")
            val invalid=dir.resolve("blocking-file"); Files.writeString(invalid,"block")
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.save(s,invalid.resolve("out.watch")) }
            assertArrayEquals(original,Files.readAllBytes(path)); assertTrue(s.hasUnsavedChanges)
        }
    }
    @Test fun importsAreTransactionalAndUndoableWithCaseInsensitiveNames() {
        store.open(archive()).use { s ->
            val one=dir.resolve("A.png"); val two=dir.resolve("a.PNG"); Files.write(one,byteArrayOf(7))
            // On Windows these paths alias, still each selected import receives a unique destination.
            if (!Files.exists(two)) Files.write(two,byteArrayOf(8))
            s.importAssets(listOf(one,two)); val imported=s.layers.takeLast(2)
            assertEquals(2,imported.map { it.attrs.getValue("path").lowercase() }.toSet().size)
            assertArrayEquals(byteArrayOf(7),Files.readAllBytes(one)); s.undo(); assertEquals(3,s.layers.size)
            imported.forEach { assertFalse(Files.exists(s.workDir.resolve("images").resolve(it.attrs.getValue("path")))) }
            assertTrue(Files.exists(s.workDir.resolve("images/old.png")))
            s.redo(); assertEquals(imported.map { it.id },s.layers.takeLast(2).map { it.id })
            val before=s.layers
            assertThrows(WatchDocumentException.AssetImportFailed::class.java) { s.importAssets(listOf(one,dir.resolve("absent.png"))) }
            assertEquals(before,s.layers)
        }
    }
    @Test fun bitmapFontAtlasIsCopiedAndRenamedTogether() {
        store.open(archive()).use { s ->
            val font=dir.resolve("test.fnt"); Files.writeString(font,"info face=\"Test\" size=32\ncommon lineHeight=32 base=26 scaleW=1 scaleH=1 pages=1\npage id=0 file=\"atlas.png\"\n")
            Files.write(dir.resolve("atlas.png"),byteArrayOf(1))
            s.importAssets(listOf(font)); s.importAssets(listOf(font))
            val names=Files.list(s.workDir.resolve("fonts_bm")).use { it.map { p -> p.fileName.toString() }.toList() }
            assertEquals(4,names.size); assertEquals(4,names.map { it.lowercase() }.toSet().size)
            s.undo(); assertEquals(4,s.layers.size)
        }
    }
    @Test fun unsafeZipAndXmlExternalEntitiesAreRejected() {
        val path=dir.resolve("unsafe.watch")
        ZipOutputStream(Files.newOutputStream(path)).use { z -> z.putNextEntry(ZipEntry("../escape")); z.write(1); z.closeEntry() }
        assertThrows(WatchDocumentException.UnsafeArchive::class.java) { store.open(path) }
        assertThrows(WatchDocumentException.InvalidDocument::class.java) { store.open(archive("<!DOCTYPE Watch [<!ENTITY ext SYSTEM 'file:///secret'>]><Watch name='&ext;'/ >")) }
    }
    @Test fun draftsRecoverUnsavedEditsAndStableSelection() {
        val source=archive(); val original=Files.readAllBytes(source)
        val session=store.open(source); val id=session.layers.first().id; session.select(id,session.layers.map { it.id }); session.setAttribute(setOf(id),"text","Draft")
        val draft=store.listRecoveryDrafts().single(); session.close()
        store.recover(draft.id).use { s ->
            assertEquals("Draft",s.layers.first().attrs["text"]); assertEquals(id,s.selection.primary); assertTrue(s.hasUnsavedChanges)
            assertTrue(s.canUndo); s.undo(); assertEquals("A",s.layers.first().attrs["text"])
        }
        assertArrayEquals(original,Files.readAllBytes(source))
    }
    @Test fun fiftyCommandHistoryAndDeleteUndoKeepIds() {
        store.open(archive()).use { s ->
            val id=s.layers.first().id
            repeat(55) { s.setAttribute(setOf(id),"text","Edit $it") }
            repeat(50) { assertTrue(s.canUndo); s.undo() }
            assertFalse(s.canUndo); assertEquals("Edit 4",s.layers.first().attrs["text"])
            s.select(id,s.layers.map { it.id }); s.deleteSelected(); assertEquals(2,s.layers.size)
            s.undo(); assertEquals(id,s.layers.first().id); assertEquals(id,s.selection.primary)
        }
    }
    @Test fun formatOneWithoutDimensionsRemainsWithoutDimensions() {
        store.open(archive("<Watch name='One' shape='R'><Layer type='text' text='first\nsecond'/><Extra/></Watch>")).use { s ->
            assertEquals("first\nsecond",s.layers.single().attrs["text"]); assertEquals(512,s.toWatchProject().width)
            s.setRootAttribute("name","Renamed"); val target=dir.resolve("one.watch"); store.save(s,target)
            val result=read(target,"watch.xml").toString(Charsets.UTF_8)
            assertFalse(result.contains("width=")); assertFalse(result.contains("height=")); assertTrue(result.contains("shape=\"R\""))
        }
    }
    @Test fun newDocumentAndInvalidLayerInputHaveActionableResults() {
        store.newDocument().use { s ->
            assertNull(s.sourcePath); assertTrue(s.hasUnsavedChanges)
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { s.setAttribute(setOf("unknown"),"x","3") }
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { s.pasteLayers("not XML") }
            val target=dir.resolve("new.watch"); store.save(s,target); assertFalse(s.hasUnsavedChanges)
            store.open(target).use { assertEquals(s.title,it.title) }
        }
    }
    @Test fun cleanSaveAsAfterAnEditedSaveCopiesLatestDocument() {
        val source=archive()
        store.open(source).use { s ->
            s.setRootAttribute("name","Latest"); store.save(s)
            val second=dir.resolve("latest-copy.watch"); store.save(s,second)
            store.open(second).use { assertEquals("Latest",it.title) }
        }
    }
    @Test fun saveFailureAfterArchiveConstructionLeavesOriginalUntouched() {
        val source=archive(); val original=Files.readAllBytes(source)
        store.open(source).use { s ->
            val asset=dir.resolve("asset.png"); Files.write(asset,byteArrayOf(10)); s.importAssets(listOf(asset))
            val blob=s.workDir.parent.resolve("blobs"); val blobFile=Files.list(blob).use { it.findFirst().get() }
            Files.write(blobFile,byteArrayOf(11))
            assertThrows(WatchDocumentException.SaveFailed::class.java) { store.save(s) }
            assertArrayEquals(original,Files.readAllBytes(source))
        }
    }
    @Test fun scriptEditIsUndoableDurableAndPreservesOriginalUntilSave() {
        val source=archive(); val original=Files.readAllBytes(source)
        val s=store.open(source); s.setGlobalScript("var_a = 20\nfunction test() return 2 end")
        assertEquals("var_a = 20\nfunction test() return 2 end",s.toWatchProject().globalScript)
        assertArrayEquals(original,Files.readAllBytes(source)); s.undo(); assertEquals("var_a = 3",s.toWatchProject().globalScript)
        s.redo(); val id=s.id; s.close()
        store.recover(id).use { recovered ->
            val target=dir.resolve("script-edited.watch"); store.save(recovered,target)
            assertEquals(recovered.toWatchProject().globalScript,read(target,"scripts/script.txt").toString(Charsets.UTF_8))
        }
    }
    @Test fun replaceAssetsForManyLayersIsOneAtomicUndoStep() {
        store.open(archive("<Watch name='Replace'><Layer type='image' path='old.png'/><Layer type='image'/></Watch>")).use { s ->
            val replacement=dir.resolve("replacement.png"); Files.write(replacement,byteArrayOf(5))
            val ids=s.layers.map { it.id }.toSet(); val before=s.layers
            s.replaceAssetForLayers(ids,listOf(replacement))
            assertEquals(1,s.layers.map { it.attrs["path"] }.toSet().size); assertNotEquals(before,s.layers)
            s.undo(); assertEquals(before,s.layers); assertFalse(s.canUndo)
            assertThrows(WatchDocumentException.AssetImportFailed::class.java) { s.replaceAssetForLayers(ids,listOf(replacement,dir.resolve("missing.png"))) }
            assertEquals(before,s.layers)
        }
    }
    @Test fun multiLayerAttributeBatchIsOneUndoAndPreservesExpressions() {
        store.open(archive()).use { session ->
            val before=session.layers;val first=before[0].id;val second=before[1].id
            session.setLayerAttributes(mapOf(first to mapOf("x" to "({dh}*2)+3", "width" to "120"),second to mapOf("y" to "7", "height" to "150")))
            assertEquals("({dh}*2)+3",session.layers[0].attrs["x"]);assertEquals("150",session.layers[1].attrs["height"])
            session.undo();assertEquals(before,session.layers);assertFalse(session.canUndo)
            val revision=session.revision
            session.setLayerAttributes(emptyMap());session.setLayerAttributes(mapOf(first to mapOf("text" to "A")))
            assertEquals(revision,session.revision)
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.setLayerAttributes(mapOf("missing" to emptyMap())) }
            assertEquals(before,session.layers)
        }
    }
    @Test fun optionalRealSampleUsesCopyAndKeepsInputImmutable() {
        val sample=System.getProperty("watch.sample",""); assumeTrue(sample.isNotBlank())
        val input=Path.of(sample); val original=Files.readAllBytes(input); val fixture=dir.resolve("fixture.watch"); Files.copy(input,fixture)
        store.open(fixture).use { s ->
            assertTrue(s.layers.isNotEmpty()); store.save(s); assertArrayEquals(original,Files.readAllBytes(fixture))
            s.setRootAttribute("name",s.title+" test"); store.save(s,dir.resolve("sample-export.watch"))
        }
        assertArrayEquals(original,Files.readAllBytes(input))
    }
}
