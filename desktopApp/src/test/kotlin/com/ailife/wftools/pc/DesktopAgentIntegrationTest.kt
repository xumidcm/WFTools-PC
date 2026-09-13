package com.ailife.wftools.pc

import com.ailife.wftools.agent.tools.*
import com.ailife.wftools.agent.workspace.WorkspaceManager
import com.ailife.wftools.pc.core.WatchDocumentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import java.util.zip.ZipFile

class DesktopAgentIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `new conversation prompt mentions global Python only when available`() {
        val withoutPython = buildWatchAgentSystemPrompt(false)
        val withPython = buildWatchAgentSystemPrompt(true)
        assertFalse(withoutPython.contains("全局 Python"))
        assertTrue(withPython.contains("全局 Python"))
        assertTrue(withPython.contains("run_command"))
    }

    @Test fun `agent imports generated SVG as a real PNG asset`() {
        val store = WatchDocumentStore(temp.newFolder("svg-work").toPath())
        val session = store.newDocument()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val doc = runBlocking { DocumentController.create(session, store, scope, EditorPreferences(temp.newFolder("svg-prefs").toPath())) }
        try {
            val manager = WorkspaceManager(temp.newFolder("svg-agent").toPath())
            val workspace = manager.create("SVG 工作区", watchScope = doc.view.identity)
            val svg = workspace.paths.generated.resolve("badge.svg")
            Files.writeString(svg, """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="24"><rect width="32" height="24" fill="#3366ff"/></svg>""")
            val host = DesktopWatchAgentToolHost(doc, workspace = manager, watchScope = doc.view.identity)
            @Suppress("UNCHECKED_CAST")
            val result = host.importFaceResource("generated/badge.svg") as Map<String, Any?>
            val resource = result["resource_path"]?.toString().orEmpty()
            assertEquals("png", result["format"])
            assertTrue(resource.startsWith("images/"))
            assertTrue(doc.view.assets.any { it.relative == resource })
            assertTrue(Files.size(doc.session.workDir.resolve(resource)) > 64)
        } finally {
            doc.render.close()
            session.close()
            scope.cancel()
        }
    }

    @Test fun `agent writes generated text through workspace manager`() {
        val store = WatchDocumentStore(temp.newFolder("workspace-write-work").toPath())
        val session = store.newDocument()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val doc = runBlocking { DocumentController.create(session, store, scope, EditorPreferences(temp.newFolder("workspace-write-prefs").toPath())) }
        try {
            val manager = WorkspaceManager(temp.newFolder("workspace-write-agent").toPath())
            val workspace = manager.create("写入工作区", watchScope = doc.view.identity)
            val host = DesktopWatchAgentToolHost(doc, workspace = manager, watchScope = doc.view.identity)
            @Suppress("UNCHECKED_CAST")
            val result = host.writeWorkspaceFile("generated/badge.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>") as Map<String, Any?>
            assertEquals("generated/badge.svg", result["path"])
            assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\"/>", Files.readString(workspace.paths.generated.resolve("badge.svg")))
        } finally {
            doc.render.close()
            session.close()
            scope.cancel()
        }
    }

    @Test fun `agent edits and previews a saved watch archive then reopens it`() {
        val store = WatchDocumentStore(temp.newFolder("work").toPath())
        val source = temp.root.toPath().resolve("agent-roundtrip.watch")
        store.newDocument().also { initial ->
            initial.addLayer("text")
            store.save(initial, source)
            initial.close()
        }
        val session = store.open(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val doc = runBlocking { DocumentController.create(session, store, scope, EditorPreferences(temp.newFolder("prefs").toPath())) }
        try {
            val host = DesktopWatchAgentToolHost(doc, DesktopPreviewRenderer(doc)::render)
            val revision = host.currentRevision
            assertEquals(1, host.inspectFace(WatchAgentInspectFaceRequest(revision)).layers.size)
            val before = host.readDocument(WatchAgentReadDocumentRequest(WatchAgentDocumentPath.WATCH_XML, revision)).content
            assertTrue(host.validateFace(WatchAgentValidateFaceRequest(revision)).valid)
            val image = host.previewFace(WatchAgentPreviewRequest(expectedRevision = revision)).imageBytes
            assertNotNull(ImageIO.read(ByteArrayInputStream(image)))

            val undo = host.beginUndoGroup()
            try {
                host.editDocument(WatchAgentEditDocumentRequest(WatchAgentDocumentPath.WATCH_XML, "新建表盘", "Agent 验收", revision))
                val candidate = host.readDocument(WatchAgentReadDocumentRequest(WatchAgentDocumentPath.WATCH_XML, revision)).content
                host.writeDocument(WatchAgentWriteDocumentRequest(WatchAgentDocumentPath.WATCH_XML, candidate.replace("Agent 验收", "Agent 验收完成"), revision))
                undo.commit()
            } catch (failure: Throwable) {
                undo.rollback()
                throw failure
            }
            assertTrue(host.currentRevision > revision)
            assertEquals("Agent 验收完成", doc.view.title)
            assertTrue(host.validateFace(WatchAgentValidateFaceRequest(host.currentRevision)).valid)
            store.save(session, source)
            ZipFile(source.toFile()).use { archive ->
                assertNotNull(archive.getEntry("watch.xml"))
                assertTrue(archive.getInputStream(archive.getEntry("watch.xml")).bufferedReader().readText().contains("Agent 验收完成"))
            }
            assertTrue(Files.size(source) > 0)
            store.open(source).use { reopened ->
                assertEquals("Agent 验收完成", reopened.toWatchProject().title)
                assertEquals(1, reopened.layers.size)
            }
            assertFalse(before.contains("Agent 验收完成"))
        } finally {
            doc.render.close()
            session.close()
            scope.cancel()
        }
    }
}
