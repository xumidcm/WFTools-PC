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
import java.net.InetSocketAddress
import java.nio.file.Files
import javax.imageio.ImageIO
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
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

    @Test fun `openai image tools pass dynamic options edit an asset and scrape page text`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(png)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        fun respond(exchange: com.sun.net.httpserver.HttpExchange, contentType: String, body: String) {
            exchange.responseHeaders.set("Content-Type", contentType)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/images/generations") { exchange -> requests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8); respond(exchange, "application/json", "{\"data\":[{\"b64_json\":\"$encoded\"}]}") }
        server.createContext("/v1/images/edits") { exchange -> requests += exchange.requestBody.readAllBytes().toString(Charsets.ISO_8859_1); respond(exchange, "application/json", "{\"data\":[{\"b64_json\":\"$encoded\"}]}") }
        server.createContext("/search") { exchange -> requests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8); respond(exchange, "application/json", "{\"results\":[{\"title\":\"结果\",\"url\":\"http://example.test\",\"content\":\"摘要\"}]}") }
        server.createContext("/extract") { exchange -> requests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8); respond(exchange, "application/json", "{\"results\":[{\"raw_content\":\"API 抽取正文\"}]}") }
        server.createContext("/page") { exchange -> respond(exchange, "text/html", "<html><head><title>测试页面</title></head><body><h1>正文标题</h1><p>页面正文 &amp; 内容</p><script>ignore()</script></body></html>") }
        server.start()
        try {
            val manager = WorkspaceManager(temp.newFolder("image-tools").toPath())
            val workspace = manager.create("图片工作区", watchScope = "face")
            Files.write(workspace.paths.generated.resolve("source.png"), png)
            val settings = com.ailife.wftools.agent.model.AgentFeatureSettings(
                imageGenerationEndpoint = "http://127.0.0.1:${server.address.port}/v1",
                searchEndpoint = "http://127.0.0.1:${server.address.port}/search",
            )
            val tools = DesktopAgentExternalTools(manager, "face", settings, null, null)
            val generated = tools.generateImage(WatchAgentImageGenerationRequest("蓝色表盘背景", size = "640x480", quality = "high", background = "transparent"))
            assertTrue(generated.path.startsWith("generated/"))
            assertTrue(requests.first().contains("640x480"))
            assertTrue(requests.first().contains("transparent"))
            tools.editImage(WatchAgentImageEditRequest("generated/source.png", "把背景改成红色", inputFidelity = "high"))
            assertTrue(requests[1].contains("name=\"image\""))
            assertTrue(tools.searchWeb("表盘素材").contains("结果"))
            assertTrue(requests.any { it.contains("表盘素材") })
            val page = tools.scrapePage("http://127.0.0.1:${server.address.port}/page")
            assertTrue(page.contains("API 抽取正文"))
        } finally {
            server.stop(0)
        }
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
                val first = host.editDocument(WatchAgentEditDocumentRequest(WatchAgentDocumentPath.WATCH_XML, "新建表盘", "Agent 验收", revision))
                val candidate = host.readDocument(WatchAgentReadDocumentRequest(WatchAgentDocumentPath.WATCH_XML, first.revision)).content
                host.writeDocument(WatchAgentWriteDocumentRequest(WatchAgentDocumentPath.WATCH_XML, candidate.replace("Agent 验收", "Agent 验收完成"), first.revision))
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

    @Test fun `face mirror changes update draft while stale mirror cannot overwrite newer editor revision`() {
        val store = WatchDocumentStore(temp.newFolder("mirror-host-work").toPath())
        val session = store.newDocument("Original")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val doc = runBlocking { DocumentController.create(session, store, scope, EditorPreferences(temp.newFolder("mirror-host-prefs").toPath())) }
        try {
            val manager = WorkspaceManager(temp.newFolder("mirror-host-agent").toPath())
            val workspace = manager.create("镜像工作区", watchScope = doc.view.identity)
            val host = DesktopWatchAgentToolHost(doc, workspace = manager, watchScope = doc.view.identity)
            host.syncFaceMirror()
            val mirror = workspace.paths.face
            val unchangedRevision = host.currentRevision
            val unchanged = host.commitFaceMirror(unchangedRevision) as Map<*, *>
            assertEquals(false, unchanged["committed"])
            assertEquals("no_changes", unchanged["skipped"])
            assertEquals(unchangedRevision, host.currentRevision)
            Files.writeString(mirror.resolve("watch.xml"), Files.readString(mirror.resolve("watch.xml")).replace("Original", "From Shell"))
            val result = host.commitFaceMirror(host.currentRevision) as Map<*, *>
            assertEquals("From Shell", doc.view.title)
            assertEquals(true, result["committed"])

            val oldXml = Files.readString(mirror.resolve("watch.xml"))
            doc.withAgentSession { doc.session.replaceAgentDocument("watch.xml", oldXml.replace("From Shell", "Newer Editor"), host.currentRevision) }
            doc.refreshFromAgent()
            Files.writeString(mirror.resolve("watch.xml"), oldXml)
            val stale = host.commitFaceMirror(host.currentRevision) as Map<*, *>
            assertEquals("Newer Editor", doc.view.title)
            assertEquals("stale_mirror", stale["skipped"])
        } finally {
            doc.render.close(); session.close(); scope.cancel()
        }
    }

    @Test fun `bash preflight refreshes the face mirror after an editor-side change`() {
        val store = WatchDocumentStore(temp.newFolder("bash-preflight-work").toPath())
        val session = store.newDocument("Before")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val doc = runBlocking { DocumentController.create(session, store, scope, EditorPreferences(temp.newFolder("bash-preflight-prefs").toPath())) }
        try {
            val manager = WorkspaceManager(temp.newFolder("bash-preflight-agent").toPath())
            val workspace = manager.create("Bash 前置同步", watchScope = doc.view.identity)
            val host = DesktopWatchAgentToolHost(doc, workspace = manager, watchScope = doc.view.identity)
            host.syncFaceMirror()
            val revision = host.currentRevision
            val xml = host.readDocument(WatchAgentReadDocumentRequest(WatchAgentDocumentPath.WATCH_XML, revision)).content
            doc.withAgentSession { doc.session.replaceAgentDocument("watch.xml", xml.replace("Before", "Edited"), revision) }
            doc.refreshFromAgent()
            Files.writeString(workspace.paths.face.resolve("watch.xml"), "<Watch name=\"stale\"><Layers/></Watch>")
            host.prepareBashMirror()
            assertTrue(Files.readString(workspace.paths.face.resolve("watch.xml")).contains("Edited"))
        } finally {
            doc.render.close(); session.close(); scope.cancel()
        }
    }
}
