package com.ailife.wftools.pc.core

import com.ailife.wftools2.domain.editor.watchmaker.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

class CoreReviewRegressionTest {
    private val dir = Files.createTempDirectory("watch-review-")
    private val store = WatchDocumentStore(dir.resolve("sessions"))
    private fun fixture(xml: String = "<Watch name='Review'><Layer type='text'/></Watch>", scriptPath: String? = "scripts/script.txt", extra: Map<String,String> = emptyMap()): Path {
        val file = dir.resolve("${System.nanoTime()}.watch")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            val entries = linkedMapOf("watch.xml" to xml, "images/orphan.dat" to "ORIGINAL")
            if (scriptPath != null) entries[scriptPath] = "var_original = 7"
            entries.putAll(extra)
            entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return file
    }
    @Test fun videoImportCreatesVideoLayerAndRoundTripsBytes() {
        val external = dir.resolve("movie.mp4"); val bytes = byteArrayOf(0,0,0,24,102,116,121,112)
        Files.write(external, bytes)
        store.open(fixture()).use { session ->
            session.importAssets(listOf(external),x=12.0,y=34.0)
            val added = session.layers.last()
            assertEquals("video", added.type); assertEquals("12", added.attrs["x"])
            assertArrayEquals(bytes, Files.readAllBytes(session.workDir.resolve("videos/movie.mp4")))
            val target=dir.resolve("video.watch"); store.save(session,target)
            ZipFile(target.toFile()).use { zip -> assertArrayEquals(bytes,zip.getInputStream(zip.getEntry("videos/movie.mp4")).readBytes()) }
            session.undo(); assertEquals(1,session.layers.size)
            assertFalse(Files.exists(session.workDir.resolve("videos/movie.mp4")))
            session.redo(); assertEquals(added.id,session.layers.last().id)
        }
        assertArrayEquals(bytes,Files.readAllBytes(external))
    }
    @Test fun recoveryReconstructsDurableResourcesAfterInterruptedMaterialization() {
        val session=store.open(fixture()); session.setRootAttribute("name","Durable")
        val work=session.workDir; val id=session.id; session.close()
        Files.writeString(work.resolve("scripts/script.txt"),"UNCOMMITTED SCRIPT")
        Files.writeString(work.resolve("images/orphan.dat"),"UNCOMMITTED RESOURCE")
        Files.writeString(work.resolve("images/uncommitted.png"),"UNCOMMITTED IMPORT")
        store.recover(id).use { recovered ->
            assertEquals("var_original = 7",recovered.toWatchProject().globalScript)
            assertEquals("ORIGINAL",Files.readString(recovered.workDir.resolve("images/orphan.dat")))
            assertFalse(Files.exists(recovered.workDir.resolve("images/uncommitted.png")))
        }
    }
    @Test fun recoveryRestoresCommittedScriptOverrideEvenIfWorkingCopyIsStale() {
        val session=store.open(fixture()); session.setGlobalScript("var_saved = 99")
        val work=session.workDir; val id=session.id; session.close()
        Files.writeString(work.resolve("scripts/script.txt"),"STALE")
        store.recover(id).use { recovered -> assertEquals("var_saved = 99",Files.readString(recovered.workDir.resolve("scripts/script.txt"))) }
    }
    @Test fun embeddedScriptCanBeClearedSavedAndUndone() {
        store.open(fixture("<Watch name='Inline' global_script='var_inline = 7'><Layer type='text'/></Watch>",null)).use { session ->
            assertEquals("var_inline = 7",session.toWatchProject().globalScript)
            session.setGlobalScript(""); assertEquals("",session.toWatchProject().globalScript)
            val target=dir.resolve("inline.watch"); store.save(session,target)
            store.open(target).use { assertEquals("",it.toWatchProject().globalScript) }
            session.undo(); assertEquals("var_inline = 7",session.toWatchProject().globalScript)
        }
    }
    @Test fun caseVariantScriptEntryIsUpdatedInPlace() {
        store.open(fixture(scriptPath="Scripts/Script.txt")).use { session ->
            session.setGlobalScript("var_edited = 11")
            val target=dir.resolve("case.watch"); store.save(session,target)
            ZipFile(target.toFile()).use { zip ->
                val entries=zip.entries().asSequence().filter { it.name.equals("scripts/script.txt",true) }.toList()
                assertEquals(1,entries.size); assertEquals("Scripts/Script.txt",entries.single().name)
                assertEquals("var_edited = 11",zip.getInputStream(entries.single()).readBytes().toString(Charsets.UTF_8))
            }
        }
    }
    @Test fun standaloneExpressionsCannotLeakGlobalsOrLibraries() {
        WmExpressionEval.eval("(function() leaked=42; math.abs=function() return 42 end; string.upper=function() return 'LEAK' end; return 0 end)()")
        assertNotEquals("42",WmExpressionEval.eval("leaked"))
        assertEquals("2",WmExpressionEval.eval("math.abs(-2)"))
        assertEquals("SAFE",WmExpressionEval.eval("string.upper('safe')"))
    }
    @Test fun blankScriptDocumentsStillUseIndependentRuntimeScopes() {
        val a=WmScriptRuntime(); val b=WmScriptRuntime()
        val first=WmTagsData.WmPreviewState(scriptRuntime=a); val second=WmTagsData.WmPreviewState(scriptRuntime=b)
        WmTagsData.evaluatePreview("(function() leaked=42; math.abs=function() return 42 end; string.upper=function() return 'LEAK' end; return 0 end)()",first)
        assertEquals("42",a.value("leaked")); assertNull(b.value("leaked"))
        assertNotEquals("42",WmTagsData.evaluatePreview("leaked",second))
        assertEquals("2",WmTagsData.evaluatePreview("math.abs(-2)",second))
        assertEquals("SAFE",WmTagsData.evaluatePreview("string.upper('safe')",second))
    }
    @Test fun stringColonMethodsAndMetatablesCannotLeakBetweenRuntimes() {
        val first=WmScriptRuntime(); val second=WmScriptRuntime()
        assertTrue(first.runSnippet("string.upper=function() return 'FIRST' end"))
        assertEquals("FIRST",first.eval("('safe'):upper()"))
        first.runSnippet("getmetatable('').__index.upper=function() return 'LEAK' end")
        assertEquals("SAFE",second.eval("('safe'):upper()"))
    }
    @Test fun discardRecoveryHidesDraftWithoutDeletingSourceOrWorkspace() {
        val source=fixture(); val original=Files.readAllBytes(source)
        val session=store.open(source); session.setRootAttribute("name","Discarded")
        val id=session.id; val work=session.workDir
        store.discardRecovery(id); session.close()
        assertTrue(store.listRecoveryDrafts().none { it.id==id })
        assertTrue(Files.isDirectory(work)); assertTrue(Files.isRegularFile(work.parent.resolve("draft.json")))
        assertArrayEquals(original,Files.readAllBytes(source))
        assertThrows(WatchDocumentException.RecoveryFailed::class.java) { store.recover(id) }
    }
    @Test fun addingLayerUsesWatchMakerDefaultsAndOneStableUndoStep() {
        store.open(fixture()).use { session ->
            session.addLayer("text"); val added=session.layers.last()
            assertEquals("text",added.type); assertEquals("Text",added.attrs["text"])
            assertEquals("100",added.attrs["opacity"]); assertEquals("100",added.attrs["anim_scale_x"])
            assertEquals("0",added.attrs["rotation"]); assertEquals("2",added.attrs["o_size"])
            assertEquals(added.id,session.selection.primary)
            session.undo(); assertEquals(1,session.layers.size); assertFalse(session.canUndo)
            session.redo(); assertEquals(added.id,session.layers.last().id)
            session.addLayer("video"); assertEquals("512",session.layers.last().attrs["width"])
            assertEquals("b",session.layers.last().attrs["display"])
            val before=session.layers
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.addLayer("unsupported") }
            assertEquals(before,session.layers)
        }
    }
    @Test fun zipResourceCrcMismatchIsRejectedBeforeOpening() {
        val source=dir.resolve("corrupt-resource.watch")
        val marker="UNIQUE STORED RESOURCE BODY".toByteArray()
        ZipOutputStream(Files.newOutputStream(source)).use { zip ->
            zip.putNextEntry(ZipEntry("watch.xml")); zip.write("<Watch name='CRC'/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("resource.bin").apply {
                method=ZipEntry.STORED; size=marker.size.toLong(); compressedSize=size
                crc=CRC32().also { it.update(marker) }.value
            }); zip.write(marker); zip.closeEntry()
        }
        val corrupt=Files.readAllBytes(source)
        val index=(0..corrupt.size-marker.size).first { offset -> marker.indices.all { corrupt[offset+it]==marker[it] } }
        corrupt[index]=(corrupt[index].toInt() xor 1).toByte(); Files.write(source,corrupt)
        assertThrows(WatchDocumentException.InvalidArchive::class.java) { store.open(source) }
        assertArrayEquals(corrupt,Files.readAllBytes(source))
    }
    @Test(timeout=3000) fun tableMaxnUsesActualNumericKeysInsteadOfUntrustedLength() {
        assertEquals("0",WmExpressionEval.eval("table.maxn(setmetatable({}, {__len=function() return 500000000 end}))"))
        assertEquals("123456789",WmExpressionEval.eval("table.maxn({[123456789]=true, [2]=false})"))
    }
    @Test(timeout=3000) fun nativeForeachiLoopCannotEscapeExecutionBudget() {
        val runtime=WmScriptRuntime()
        assertFalse(runtime.runScript("table.foreachi(setmetatable({}, {__index=type}), tostring)"))
        assertTrue(runtime.lastError.orEmpty().contains("budget",true))
    }
    @Test fun fontImportsAndReplacementsReserveCaseInsensitiveFontIdentifiers() {
        val font=dir.resolve("test.otf"); Files.writeString(font,"NEW_FONT")
        store.open(fixture(extra=mapOf("fonts/Test.ttf" to "OLD_FONT"))).use { session ->
            session.importAssets(listOf(font))
            val imported=session.layers.last().attrs.getValue("font")
            assertNotEquals("test",imported.lowercase())
            assertTrue(Files.exists(session.workDir.resolve("fonts/$imported.otf")))
            val targetId=session.layers.first().id
            session.replaceAssetForLayers(setOf(targetId),listOf(font),attribute="font")
            val replacement=session.layers.first().attrs.getValue("font")
            assertNotEquals("test",replacement.lowercase()); assertNotEquals(imported.lowercase(),replacement.lowercase())
            assertEquals("OLD_FONT",Files.readString(session.workDir.resolve("fonts/Test.ttf")))
        }
    }
    @Test fun ordinaryEditsDoNotReadOrRewriteUnchangedImportedMedia() {
        val source=fixture(); val original=Files.readAllBytes(source)
        val video=dir.resolve("large-video.mp4"); Files.writeString(video,"IMMUTABLE_MEDIA")
        store.open(source).use { session ->
            session.importAssets(listOf(video)); val layer=session.layers.last()
            val working=session.workDir.resolve("videos").resolve(layer.attrs.getValue("path"))
            val blob=Files.list(session.workDir.parent.resolve("blobs")).use { it.findFirst().get() }
            val offline=blob.resolveSibling(blob.fileName.toString()+".offline")
            Files.move(blob,offline)
            try {
                Files.writeString(working,"WORKING_COPY")
                session.setAttribute(setOf(session.layers.first().id),"text","Changed")
                session.nudge(1.0,0.0); session.undo()
                assertEquals("WORKING_COPY",Files.readString(working))
                assertThrows(WatchDocumentException.SaveFailed::class.java) { store.save(session) }
                assertArrayEquals(original,Files.readAllBytes(source))
            } finally { Files.move(offline,blob) }
        }
    }
}
