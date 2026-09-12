package com.ailife.wftools.pc.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DraftSnapshotStoreTest {
    private val root=Files.createTempDirectory("watch-state-store-")
    private val store=WatchDocumentStore(root.resolve("sessions"))
    private fun source():Path {
        val source=root.resolve("${System.nanoTime()}.watch")
        ZipOutputStream(Files.newOutputStream(source)).use { zip ->
            zip.putNextEntry(ZipEntry("watch.xml"));zip.write("<Watch name='Source'><Layer type='text' text='Original'/></Watch>".toByteArray());zip.closeEntry()
        };return source
    }
    private fun journal(home:Path)=JsonParser.parseString(Files.readString(home.resolve("draft.json"))).asJsonObject
    @Test fun historyUsesSmallReferencesAndNeverRewritesOldSnapshotFiles() {
        store.open(source()).use { session ->
            session.setRootAttribute("description","x".repeat(120_000))
            val home=session.home; val previousRef=journal(home).get("stateRef").asString
            val previousFile=home.resolve("states/$previousRef.json")
            val stamp=FileTime.fromMillis(1_234_567_890L);Files.setLastModifiedTime(previousFile,stamp)
            repeat(10) { session.setRootAttribute("name","Edit $it") }
            val current=journal(home)
            assertEquals(2,current.get("version").asInt);assertFalse(current.has("state"))
            assertEquals(11,current.getAsJsonArray("undoRefs").size())
            assertTrue(Files.size(home.resolve("draft.json"))<16_000)
            assertEquals(stamp,Files.getLastModifiedTime(previousFile))
            session.undo();assertEquals("Edit 8",session.title)
        }
    }
    @Test fun missingAndCorruptStateReferencesFailRecoveryWithoutSourceChanges() {
        val source=source();val original=Files.readAllBytes(source)
        val session=store.open(source);session.setRootAttribute("name","Draft");val id=session.id;val home=session.home;session.close()
        val ref=journal(home).get("stateRef").asString;val file=home.resolve("states/$ref.json");val bytes=Files.readAllBytes(file)
        val offline=home.resolve("state.offline");Files.move(file,offline)
        assertThrows(WatchDocumentException.RecoveryFailed::class.java) { store.recover(id) }
        Files.move(offline,file);Files.writeString(file,"corrupt")
        assertThrows(WatchDocumentException.RecoveryFailed::class.java) { store.recover(id) }
        assertArrayEquals(original,Files.readAllBytes(source));Files.write(file,bytes)
        store.recover(id).use { assertEquals("Draft",it.title) }
    }
    @Test fun recoveryOnlyUsesTheLastCommittedJournal() {
        val session=store.open(source());session.setRootAttribute("name","Committed")
        val id=session.id;val home=session.home
        val orphan=session.state.copy(xml=session.state.xml.replace("Committed","Uncommitted"));session.close()
        val bytes=GsonBuilder().disableHtmlEscaping().create().toJson(orphan).toByteArray()
        val hash=WatchDocumentStore.hashBytes(bytes)
        Files.write(home.resolve("states/$hash.json"),bytes)
        Files.writeString(home.resolve(".draft-uncommitted.tmp"),"uncommitted temporary index")
        store.recover(id).use { assertEquals("Committed",it.title);assertTrue(it.canUndo) }
    }
    @Test fun embeddedVersionOneDraftStillRecoversAndMigratesOnNextEdit() {
        val source=source();val original=Files.readAllBytes(source)
        val session=store.open(source);val layer=session.layers.single().id
        session.selectAll(listOf(layer));session.setAttribute(setOf(layer),"text","Legacy")
        val legacy=DraftJournal(id=session.id,sourcePath=session.sourcePath.toString(),sourceHash=session.sourceHash,baseArchiveHash=session.baseArchiveHash,
            savedSignature=session.savedSignature,revision=session.revision,xmlEntry=session.xmlEntry,state=session.state,
            undo=session.undoStack.toList(),redo=session.redoStack.toList(),derivedResources=session.savedDerivedResources)
        val id=session.id;val home=session.home;session.close()
        Files.writeString(home.resolve("draft.json"),Gson().toJson(legacy))
        Files.move(home.resolve("states"),home.resolve("states-offline"))
        store.recover(id).use { recovered ->
            assertEquals("Legacy",recovered.layers.single().attrs["text"]);assertEquals(layer,recovered.selection.primary)
            recovered.undo();assertEquals("Original",recovered.layers.single().attrs["text"])
            assertEquals(2,journal(home).get("version").asInt)
        }
        assertArrayEquals(original,Files.readAllBytes(source))
    }
    @Test fun diskSnapshotsStayBoundedAfterHistoryLimitAndSelectionChanges() {
        store.open(source()).use { session ->
            repeat(65) { session.setRootAttribute("name","Edit $it") }
            val layer=session.layers.single().id
            repeat(12) { session.selectAll(listOf(layer));session.clearSelection() }
            val files=Files.list(session.home.resolve("states")).use { paths -> paths.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }.toList() }
            assertTrue("state files=${files.size}",files.size<=51)
            assertTrue(files.sumOf { Files.size(it) }<64_000)
            repeat(50) { session.undo() };assertFalse(session.canUndo);assertEquals("Edit 14",session.title)
        }
    }
    @Test fun snapshotGcFailureCannotRollBackCommittedSelection() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows",true))
        store.open(source()).use { session ->
            val previous=journal(session.home).get("stateRef").asString
            val previousFile=session.home.resolve("states/$previous.json")
            Files.setAttribute(previousFile,"dos:readonly",true)
            try {
                val id=session.layers.single().id;session.selectAll(listOf(id))
                assertEquals(id,session.selection.primary)
                assertNotEquals(previous,journal(session.home).get("stateRef").asString)
            } finally { if(Files.exists(previousFile))Files.setAttribute(previousFile,"dos:readonly",false) }
        }
    }
}
