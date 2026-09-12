package com.ailife.wftools.pc

import com.ailife.wftools.pc.core.WatchDocumentStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import java.util.zip.ZipFile

class DocumentControllerTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `commands preserve input order across IO dispatch and publish assets before next mutation`(): Unit = runBlocking {
        val store = WatchDocumentStore(temp.newFolder("work").toPath())
        val session = store.newDocument()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val doc = DocumentController.create(session,store,scope,EditorPreferences(temp.newFolder("prefs").toPath()))
        val image = temp.newFile("image.png"); ImageIO.write(BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"png",image)
        try {
            doc.assetCommand { Thread.sleep(80); importAssets(listOf(image.toPath())) }
            doc.command { nudge(1.0,0.0) }
            doc.command { nudge(10.0,0.0) }
            withTimeout(5000) { while(doc.busy) delay(5) }
            assertNull(doc.error); assertEquals("11",doc.view.layers.single().attrs["x"])
            assertEquals(1,doc.view.assets.size); assertTrue(doc.view.assets.single().relative.endsWith("image.png"))
            val project = doc.view.project; val layers = doc.view.layers
            doc.command { clearSelection() }
            withTimeout(5000) { while(doc.busy) delay(5) }
            assertSame(project,doc.view.project); assertSame(layers,doc.view.layers)
        } finally { doc.render.close(); session.close(); scope.cancel() }
    }
    @Test fun `successful save can immediately close and choosing discard keeps a recoverable draft`(): Unit = runBlocking {
        val store = WatchDocumentStore(temp.newFolder("work").toPath())
        val session = store.newDocument(); val path = temp.root.toPath().resolve("face.watch"); store.save(session,path)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val doc = DocumentController.create(session,store,scope,EditorPreferences(temp.newFolder("prefs").toPath()))
        try {
            val closed = CompletableDeferred<Unit>()
            doc.save(path) { doc.finish(false) { closed.complete(Unit) } }
            withTimeout(5000) { closed.await() }
            assertNull(doc.error)
            val draft = store.newDocument(); draft.setRootAttribute("name","仍可恢复")
            val discarded = DocumentController.create(draft,store,scope,EditorPreferences(temp.newFolder("prefs2").toPath()))
            val done = CompletableDeferred<Unit>()
            discarded.finish(true) { done.complete(Unit) }; withTimeout(5000) { done.await() }
            assertTrue(store.listRecoveryDrafts().any { it.id == draft.id && it.title == "仍可恢复" })
        } finally { doc.render.close(); session.close(); scope.cancel() }
    }
    @Test fun `same canonical file and repeated recovery ID each reuse one session`(): Unit = runBlocking {
        val store = WatchDocumentStore(temp.newFolder("work").toPath())
        val original = store.newDocument(); val source = temp.root.toPath().resolve("face.watch"); store.save(original,source); original.close()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val app = EditorApplicationModel(scope,EditorPreferences(temp.newFolder("prefs").toPath()),store)
        try {
            app.open(listOf(source,source.parent.resolve(".").resolve(source.fileName)))
            withTimeout(5000) { while(app.documents.size != 1 || app.status != null) delay(5) }
            assertEquals(1,app.documents.size)
            val draft = store.newDocument(); val id = draft.id; draft.close()
            app.refreshRecovery().join(); app.recover(id)
            withTimeout(5000) { while(app.documents.size != 2 || app.status != null) delay(5) }
            app.recover(id).join()
            withTimeout(5000) { while(app.status != null) delay(5) }
            assertEquals(2,app.documents.size); assertEquals(1,app.documents.count { it.session.id == id })
        } finally { app.documents.forEach { it.render.close(); it.session.close() }; scope.cancel() }
    }
    @Test fun `external modification blocks save and failed reload preserves the current session`(): Unit = runBlocking {
        val store = WatchDocumentStore(temp.newFolder("work").toPath())
        val session = store.newDocument(); val source = temp.root.toPath().resolve("face.watch"); store.save(session,source)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val preferences = EditorPreferences(temp.newFolder("prefs").toPath())
        val doc = DocumentController.create(session,store,scope,preferences)
        var replacement: DocumentController? = null
        try {
            Files.writeString(source,"external incomplete file")
            var saved = false
            doc.save(source) { saved = true }
            withTimeout(5000) { while(doc.busy) delay(5) }
            assertFalse(saved); assertTrue(doc.externalModification); assertEquals("external incomplete file",Files.readString(source))
            doc.reload(preferences) { replacement = it }
            withTimeout(5000) { while(doc.busy) delay(5) }
            assertNull(replacement); assertEquals("新建表盘",doc.view.title)
            doc.command { setRootAttribute("name","still editable") }
            withTimeout(5000) { while(doc.busy) delay(5) }
            assertEquals("still editable",doc.view.title)
            val external = store.newDocument(); external.setRootAttribute("name","external version")
            val otherPath = temp.root.toPath().resolve("replacement.watch"); store.save(external,otherPath); external.close()
            Files.copy(otherPath,source,StandardCopyOption.REPLACE_EXISTING)
            doc.reload(preferences) { replacement = it }
            withTimeout(5000) { while(doc.busy) delay(5) }
            assertEquals("external version",replacement?.view?.title); assertEquals(doc.windowId,replacement?.windowId)
            assertFalse(store.listRecoveryDrafts().any { it.id == session.id })
        } finally { doc.render.close(); session.close(); replacement?.let { it.render.close(); it.session.close() }; scope.cancel() }
    }

    @Test fun `deleting a draft recent record does not parse its synthetic path`() = runBlocking {
        val work = temp.newFolder("draft-delete-work").toPath()
        val prefs = EditorPreferences(temp.newFolder("draft-delete-prefs").toPath())
        val store = WatchDocumentStore(work)
        val draft = store.newDocument()
        draft.setRootAttribute("name", "待删除草稿")
        draft.close()
        prefs.recordRecentDraft(draft.id, "待删除草稿")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val app = EditorApplicationModel(scope, prefs, store)
        try {
            withTimeout(5000) { while (app.recentProjects.isEmpty()) delay(5) }
            val recent = app.recentProjects.single()
            app.deleteRecentWatch(recent).join()
            assertNull(app.error)
            assertTrue(app.recentProjects.isEmpty())
            assertFalse(prefs.recentProjects().any { it.draftId == draft.id })
            assertFalse(store.listRecoveryDrafts().any { it.id == draft.id })
        } finally {
            app.documents.forEach { it.render.close(); it.session.close() }
            scope.cancel()
        }
    }

    @Test fun `screenshot and gif exports create real files without changing document state`(): Unit = runBlocking {
        val store = WatchDocumentStore(temp.newFolder("work-capture").toPath())
        val session = store.newDocument(); session.addLayer("shape")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val doc = DocumentController.create(session,store,scope,EditorPreferences(temp.newFolder("prefs-capture").toPath()))
        val png = temp.root.toPath().resolve("preview.png")
        val gif = temp.root.toPath().resolve("preview.gif")
        try {
            val revision = doc.view.revision
            val dirty = doc.view.dirty
            doc.exportScreenshot(png)
            withTimeout(15_000) { while (doc.busy) delay(5) }
            assertNull(doc.error); assertTrue(Files.isRegularFile(png))
            assertEquals(512,ImageIO.read(png.toFile()).width)
            assertEquals(revision,doc.view.revision); assertEquals(dirty,doc.view.dirty)

            doc.exportGif(gif,durationMillis=1_000,fps=2)
            withTimeout(15_000) { while (doc.busy) delay(5) }
            assertNull(doc.error); assertTrue(Files.isRegularFile(gif))
            assertEquals("GIF89a",Files.readAllBytes(gif).copyOf(6).toString(Charsets.US_ASCII))
            assertEquals(revision,doc.view.revision); assertEquals(dirty,doc.view.dirty)
        } finally { doc.render.close(); session.close(); scope.cancel() }
    }

    @Test fun `protected export creates an independent archive without changing document state`(): Unit = runBlocking {
        val store = WatchDocumentStore(temp.newFolder("work-protected").toPath())
        val session = store.newDocument(); session.addLayer("shape")
        val source = temp.root.toPath().resolve("source.watch"); store.save(session,source)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val doc = DocumentController.create(session,store,scope,EditorPreferences(temp.newFolder("prefs-protected").toPath()))
        val target = temp.newFolder("protected-output").toPath().resolve("protected.watch")
        try {
            val revision = doc.view.revision
            doc.exportProtected(target)
            withTimeout(15_000) { while (doc.busy) delay(5) }
            assertNull(doc.error); assertTrue(Files.isRegularFile(target))
            ZipFile(target.toFile()).use { archive -> assertNotNull(archive.getEntry("watch.pxml")) }
            assertEquals(revision,doc.view.revision); assertFalse(doc.view.dirty)
        } finally { doc.render.close(); session.close(); scope.cancel() }
    }
}
