@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.core.WatchDocumentStore
import com.ailife.wftools.pc.preview.PreviewDocumentSnapshot
import com.ailife.wftools.pc.preview.WatchPreview
import com.ailife.wftools.agent.model.AiProtocol
import com.ailife.wftools.agent.model.AiProviderProfile
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class EditorDesktopUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var doc: DocumentController? = null
    private var agentServer: HttpServer? = null
    @Before fun useChineseFixtureLocale() { DesktopI18n.setLanguage(DesktopLanguage.CHINESE) }
    @After fun close() { agentServer?.stop(0); doc?.let { it.render.close(); it.session.close() }; scope.cancel() }
    private fun editor(saved: Boolean = false): DocumentController = runBlocking {
        val store = WatchDocumentStore(temp.newFolder("work").toPath())
        val session = store.newDocument()
        session.addLayer("shape"); val first = session.selection.selected; session.setAttribute(first,"name","底部形状")
        session.addLayer("text"); session.setAttribute(session.selection.selected,"name","顶部文字")
        if(saved) store.save(session,temp.root.toPath().resolve("saved-face.watch"))
        val preferences = EditorPreferences(temp.newFolder("preferences").toPath())
        DocumentController.create(session, store, scope, preferences).also { doc = it; it.render.playing = false }
    }
    @Test fun `property text input keeps select all delete arrows and undo inside text editor`() {
        val editor = editor(); val app = EditorApplicationModel(scope, EditorPreferences(temp.newFolder("app").toPath()))
        val commands = CommandDispatcher(app,editor) {}
        compose.setContent { MaterialTheme {
            Box(Modifier.width(330.dp).height(600.dp).onPreviewKeyEvent(commands::key)) { PropertiesPanel(editor,DropRegistry(),Modifier.fillMaxSize()) }
        } }
        val beforeRevision = editor.view.revision
        val field = compose.onNodeWithContentDescription("X 位置")
        field.performClick()
        field.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft) }
        field.performTextInput("123")
        field.performKeyInput { pressKey(Key.DirectionLeft); keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft); pressKey(Key.Delete) }
        field.assertTextEquals("")
        field.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Z); keyUp(Key.CtrlLeft) }
        compose.runOnIdle { assertEquals(EditorFocus.TEXT,editor.focus); assertEquals(2,editor.view.layers.size); assertEquals(beforeRevision,editor.view.revision) }
    }
    @Test fun `enter commits one property edit and keeps preview settings outside undo`() {
        val editor = editor()
        compose.setContent { MaterialTheme { Box(Modifier.width(330.dp).height(650.dp)) { PropertiesPanel(editor,DropRegistry(),Modifier.fillMaxSize()) } } }
        val revision = editor.view.revision
        val field = compose.onNodeWithContentDescription("X 位置")
        field.performClick(); field.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft) }; field.performTextInput("(12)+{ds}"); field.performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5000) { !editor.busy && editor.view.revision == revision + 1 }
        compose.runOnIdle {
            assertEquals("(12)+{ds}",editor.view.layers.last().attrs["x"])
            editor.preview = editor.preview.copy(device = editor.preview.device.copy(width = 320,height = 386))
            assertEquals(revision + 1,editor.view.revision)
        }
    }
    @Test fun `canvas arrow keys nudge in design units with shift and alt precision`() {
        val editor = editor()
        compose.setContent { WFToolsTheme(false) {
            val view = editor.view
            WatchPreview(editor.session,view.revision,editor.render,editor.preview.device,editor.preview.workspace,
                { editor.preview = editor.preview.copy(workspace = it) },editor::refreshFromPreview,
                modifier = Modifier.size(500.dp),snapshot = PreviewDocumentSnapshot(view.project,view.layers,view.selection),
                onDocumentCommand = { editor.command(block = it) })
        } }
        compose.onRoot().performMouseInput { click(center) }
        compose.waitUntil(5000) { !editor.busy }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] == "1" }
        compose.onRoot().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.DirectionRight); keyUp(Key.ShiftLeft) }
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] == "11" }
        compose.onRoot().performKeyInput { keyDown(Key.AltLeft); pressKey(Key.DirectionLeft); keyUp(Key.AltLeft) }
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] == "10.9" }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] == "13.9" }
    }
    @Test fun `layer list shift arrows extend visible selection and right click preserves group`() {
        val editor = editor(); val app = EditorApplicationModel(scope,EditorPreferences(temp.newFolder("app").toPath()))
        val commands = CommandDispatcher(app,editor) {}
        compose.setContent { WFToolsTheme(false) { EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry()) } }
        compose.onNodeWithText("底部形状").performMouseInput { click() }
        compose.waitUntil(5000) { !editor.busy && editor.view.selection.primary == editor.view.layers.first().id }
        compose.onRoot().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.DirectionUp); keyUp(Key.ShiftLeft) }
        compose.waitUntil(5000) { !editor.busy && editor.view.selection.selected.size == 2 }
        compose.onNodeWithText("顶部文字").performMouseInput { click(button = MouseButton.Secondary) }
        compose.runOnIdle { assertEquals(2,editor.view.selection.selected.size) }
        compose.onNodeWithTag("layer-context-menu").assertExists()
        compose.onNode(hasText("复制图层") and hasAnyAncestor(hasTestTag("layer-context-menu"))).performKeyInput { pressKey(Key.Escape) }
    }
    @Test fun `numeric plus preserves expressions and one slider drag creates one undo step`() {
        val editor = editor()
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(348.dp).height(700.dp)) { PropertiesPanel(editor,DropRegistry(),Modifier.fillMaxSize()) } } }
        val field = compose.onNodeWithContentDescription("X 位置")
        field.performClick(); field.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft) }; field.performTextInput("(12)+{ds}"); field.performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] == "(12)+{ds}" }
        compose.onNodeWithContentDescription("增大 X 位置").performClick()
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] != "(12)+{ds}" }
        compose.runOnIdle { assertTrue(editor.view.layers.last().attrs.getValue("x").contains("(12)+{ds}")) }
        field.performClick(); field.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft) }; field.performTextInput("0"); field.performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] == "0" }
        val revision = editor.view.revision
        compose.onNodeWithTag("slider-X 位置").performMouseInput { moveTo(center); press(); moveTo(center+Offset(10f,0f)); moveTo(center+Offset(20f,0f)); release() }
        compose.waitUntil(5000) { !editor.busy && editor.view.revision > revision }
        compose.runOnIdle { assertEquals(revision+1,editor.view.revision); editor.command { undo() } }
        compose.waitUntil(5000) { !editor.busy && editor.view.layers.last().attrs["x"] == "0" }
    }
    @Test fun `control S commits a focused numeric value before writing the source archive`() {
        val editor = editor(saved = true); val app = EditorApplicationModel(scope,EditorPreferences(temp.newFolder("app").toPath()))
        val commands = CommandDispatcher(app,editor) {}
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(348.dp).height(700.dp).onPreviewKeyEvent(commands::key)) { PropertiesPanel(editor,DropRegistry(),Modifier.fillMaxSize()) } } }
        val field = compose.onNodeWithContentDescription("X 位置")
        field.performClick(); field.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft) }; field.performTextInput("123")
        field.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.S); keyUp(Key.CtrlLeft) }
        compose.waitUntil(10000) { !editor.busy && !editor.view.dirty && editor.view.layers.last().attrs["x"] == "123" }
        compose.runOnIdle { assertNull(editor.error) }
        WatchDocumentStore(temp.newFolder("verify").toPath()).open(editor.view.source!!).use { assertEquals("123",it.layers.last().attrs["x"]) }
    }
    @Test fun `global script editor owns editing shortcuts and control S saves draft before archive`() {
        val editor=editor(saved=true);val app=EditorApplicationModel(scope,EditorPreferences(temp.newFolder("app-script").toPath()));val commands=CommandDispatcher(app,editor){}
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(420.dp).height(760.dp).onPreviewKeyEvent(commands::key)){ProjectPanel(editor,Modifier.fillMaxSize())} } }
        compose.onNodeWithText(DesktopI18n.text("编辑全局脚本")).performClick()
        val field=compose.onNodeWithTag("global-script-editor");val layers=editor.view.layers.map{it.id};val revision=editor.view.revision
        field.performClick();field.performTextInput("var_owned = 1")
        field.performKeyInput { keyDown(Key.CtrlLeft);pressKey(Key.A);keyUp(Key.CtrlLeft);pressKey(Key.Delete);keyDown(Key.CtrlLeft);pressKey(Key.Z);keyUp(Key.CtrlLeft) }
        field.performKeyInput { keyDown(Key.CtrlLeft);pressKey(Key.A);pressKey(Key.C);pressKey(Key.V);keyUp(Key.CtrlLeft) }
        compose.runOnIdle { assertEquals(EditorFocus.TEXT,editor.focus);assertEquals(layers,editor.view.layers.map{it.id});assertEquals(revision,editor.view.revision) }
        field.performKeyInput { keyDown(Key.CtrlLeft);pressKey(Key.S);keyUp(Key.CtrlLeft) }
        compose.waitUntil(10000){!editor.busy&&!editor.view.dirty&&editor.view.project.globalScript.contains("var_owned = 1")}
        WatchDocumentStore(temp.newFolder("verify-script").toPath()).open(editor.view.source!!).use { assertTrue(it.toWatchProject().globalScript.contains("var_owned = 1")) }
    }
    @Test fun `selecting a distant layer on canvas brings its full card into the visible list`() {
        val editor = editor()
        val target = editor.view.layers.first().id
        val additions = (1..30).joinToString("") { "<Layer type=\"shape\" shape=\"Square\" name=\"右侧图层 $it\" x=\"160\" y=\"0\" width=\"60\" height=\"60\" rotation=\"0\" anim_scale_x=\"100\" anim_scale_y=\"100\" opacity=\"100\" display=\"bd\" alignment=\"cc\" color=\"6650A4\"/>" }
        editor.command {
            setAttribute(setOf(target),"x","-160"); setAttribute(setOf(target),"width","80"); setAttribute(setOf(target),"height","80")
            pasteLayers("<WFToolsLayers>$additions</WFToolsLayers>")
        }
        runBlocking { withTimeout(5000) { while(editor.busy) delay(5) } }
        val revision = editor.view.revision
        val app = EditorApplicationModel(scope,EditorPreferences(temp.newFolder("app").toPath()))
        val commands = CommandDispatcher(app,editor) {}
        compose.setContent { WFToolsTheme(false) { EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry()) } }
        compose.onNodeWithTag("layer-row-$target").assertDoesNotExist()
        compose.onNodeWithTag("watch-preview").performMouseInput { click(center+Offset(-160f,0f)) }
        compose.waitUntil(5000) { !editor.busy && editor.view.selection.primary == target }
        val row = compose.onNodeWithTag("layer-row-$target").assertIsDisplayed().getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("layer-list").getUnclippedBoundsInRoot()
        assertTrue("Selected card must be completely inside the list viewport",row.top.value >= list.top.value-1f && row.bottom.value <= list.bottom.value+1f)
        assertEquals("Selection and reveal must not edit document content",revision,editor.view.revision)
    }
    @Test fun `toolbar automatic snapping changes workspace preference without editing the document`() {
        val editor = editor(saved = true)
        val app = EditorApplicationModel(scope,EditorPreferences(temp.newFolder("app").toPath()))
        val commands = CommandDispatcher(app,editor) {}
        val before = editor.view
        val archive = java.nio.file.Files.readAllBytes(before.source!!)
        compose.setContent { WFToolsTheme(false) { EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry()) } }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithTag("workspace-settings-entry").performClick()
        compose.onNodeWithTag("automatic-snapping-toggle").performClick()
        compose.runOnIdle {
            assertFalse(app.canvasInput.snapping); assertTrue(editor.preview.snapping); assertFalse(editor.view.dirty)
            assertEquals(before.revision,editor.view.revision); assertEquals(before.selection,editor.view.selection)
            assertEquals(before.canUndo,editor.view.canUndo); assertEquals(before.canRedo,editor.view.canRedo)
            assertFalse("Snapping is independent from action testing",editor.render.interactionMode)
        }
        compose.onNodeWithTag("automatic-snapping-toggle").performClick()
        compose.runOnIdle { assertTrue(app.canvasInput.snapping); assertEquals(before.project,editor.view.project) }
        assertArrayEquals(archive,java.nio.file.Files.readAllBytes(before.source))
    }
    @Test fun `project ordinary save as delegates through the same installed application hook`() {
        val editor=editor(saved=true);val app=EditorApplicationModel(scope,EditorPreferences(temp.newFolder("app").toPath()))
        val requested=mutableListOf<DocumentController>()
        val integration=installProjectPanelIntegration(app){requested.add(it)}
        try {
            compose.setContent { WFToolsTheme(false) { ProjectPanel(editor,Modifier.width(380.dp).height(700.dp)) } }
            compose.onNodeWithText(DesktopI18n.text("另存为"),substring=false).performScrollTo().assertIsEnabled().performClick()
            compose.runOnIdle { assertEquals(1,requested.size);assertSame(editor,requested.single()) }
        } finally { integration.close() }
    }

    @Test fun `double clicking text opens a focused editor at the pointer and enter saves while shift enter inserts a line`() {
        val editor=editor();val textId=editor.view.layers.last().id
        editor.command { setLayerAttributes(mapOf(textId to mapOf("text" to "原文","x" to "0","y" to "0","text_size" to "48"))) }
        runBlocking { withTimeout(5000){while(editor.busy)delay(5)} }
        val app=EditorApplicationModel(scope,EditorPreferences(temp.newFolder("inline-app").toPath()));val commands=CommandDispatcher(app,editor){}
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(1000.dp).height(720.dp)) { EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry()) } } }
        val preview=compose.onNodeWithTag("watch-preview")
        val previewBounds=preview.getUnclippedBoundsInRoot()
        preview.performMouseInput { doubleClick(center) }
        val inline=compose.onNodeWithTag("inline-text-editor").assertIsDisplayed().assertIsFocused()
        val inlineBounds=inline.getUnclippedBoundsInRoot()
        val pointerX=previewBounds.left+(previewBounds.right-previewBounds.left)/2
        val pointerY=previewBounds.top+(previewBounds.bottom-previewBounds.top)/2
        assertTrue("文本框应靠近双击位置",kotlin.math.abs((inlineBounds.left-pointerX).value)<80f&&kotlin.math.abs((inlineBounds.top-pointerY).value)<80f)
        inline.performTextReplacement("第一行")
        inline.performKeyInput { keyDown(Key.ShiftLeft);pressKey(Key.Enter);keyUp(Key.ShiftLeft) }
        inline.performTextInput("第二行")
        inline.assertTextContains("第一行\n第二行")
        inline.performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5000){!editor.busy&&editor.view.layers.last().attrs["text"]=="第一行\n第二行"}
        compose.onNodeWithTag("inline-text-editor").assertDoesNotExist()
    }
    @Test fun `AI inspector tab is absent until global switch is enabled`() {
        val editor = editor()
        val app = EditorApplicationModel(scope, EditorPreferences(temp.newFolder("agent-preferences").toPath()), agentRoot = temp.newFolder("agent-data").toPath())
        val commands = CommandDispatcher(app, editor) {}
        compose.setContent { WFToolsTheme(false) { EditorWorkspace(app, editor, commands, PanelPreferences(), {}, DropRegistry()) } }
        compose.onNodeWithText("AI", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { app.updateAgentSettings(app.agentSettings.copy(enabled = true)) }
        compose.onNodeWithText("AI", useUnmergedTree = true).assertExists()
        compose.runOnIdle { app.updateAgentSettings(app.agentSettings.copy(enabled = false)) }
        compose.onNodeWithText("AI", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun `AI sidebar streams a reply and keeps history across maximize`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val request = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val streaming = request.contains("\"stream\":true")
            val body = (if (streaming) "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n" else "{\"choices\":[{\"message\":{\"content\":\"表盘编辑请求\"}}]}").toByteArray()
            exchange.responseHeaders.set("Content-Type", if (streaming) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start(); agentServer = server
        val editor = editor()
        val app = EditorApplicationModel(scope, EditorPreferences(temp.newFolder("agent-ui-preferences").toPath()), agentRoot = temp.newFolder("agent-ui-data").toPath())
        app.agentProviderStore.save(AiProviderProfile("fake", "Fake", AiProtocol.CHAT_COMPLETIONS, "http://127.0.0.1:${server.address.port}/v1", modelId = "fake-model"), "")
        app.updateAgentSettings(app.agentSettings.copy(enabled = true, activeProviderId = "fake", defaultModelId = "fake-model"))
        val commands = CommandDispatcher(app, editor) {}
        compose.setContent { WFToolsTheme(false) { EditorWorkspace(app, editor, commands, PanelPreferences(), {}, DropRegistry()) } }
        compose.onNodeWithText("AI", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { app.agentModel(editor).activeConversation != null }
        val input = compose.onNodeWithTag("agent-input")
        input.performClick()
        input.performTextInput("Hello")
        input.performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        input.performTextInput("detail")
        input.performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(15000) { app.agentModel(editor).messages.any { it.content == "OK" } }
        compose.waitUntil(15000) { app.agentModel(editor).activeConversation?.title == "表盘编辑请求" }
        compose.onNodeWithTag("agent-input-resize").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("复制消息", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("最大化").performClick()
        compose.onNodeWithTag("agent-fullscreen").assertExists()
        compose.onNodeWithContentDescription("恢复").performClick()
        compose.onNodeWithTag("agent-fullscreen").assertDoesNotExist()
        compose.onNodeWithContentDescription("历史").performClick()
        compose.onAllNodesWithText("表盘编辑请求", useUnmergedTree = true).assertCountEquals(2)
    }

}
