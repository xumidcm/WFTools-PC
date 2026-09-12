@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.core.*
import com.ailife.wftools.pc.preview.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Acceptance RED tests for the latest user corrections. Production is deliberately untouched. */
class EditorRedContractsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val editors = mutableListOf<DocumentController>()
    private val gates = mutableListOf<CountDownLatch>()
    @After fun cleanup() {
        gates.forEach { it.countDown() }
        runBlocking { withTimeoutOrNull(5000) { while(editors.any { it.busy }) delay(5) } }
        editors.forEach { it.render.close(); it.session.close() }; scope.cancel()
    }
    private fun editor(layerCount: Int = 1): DocumentController = runBlocking {
        val store = WatchDocumentStore(temp.newFolder().toPath())
        val session = store.newDocument()
        val layers = (0 until layerCount).joinToString("") { index ->
            "<Layer type=\"shape\" name=\"测试图层 $index\" shape=\"Square\" x=\"0\" y=\"0\" width=\"80\" height=\"80\" rotation=\"0\" anim_scale_x=\"100\" anim_scale_y=\"100\" opacity=\"100\" display=\"bd\" alignment=\"cc\" color=\"FF2244\"/>"
        }
        session.pasteLayers("<WFToolsLayers>$layers</WFToolsLayers>")
        session.select(session.layers.last().id,session.layers.asReversed().map { it.id })
        DocumentController.create(session,store,scope,EditorPreferences(temp.newFolder().toPath())).also { it.render.playing = false; editors.add(it) }
    }
    private fun workspace(editor: DocumentController) {
        val app = EditorApplicationModel(scope,EditorPreferences(temp.newFolder().toPath()))
        val commands = CommandDispatcher(app,editor) {}
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) { EditorWorkspace(app,editor,commands,PanelPreferences(),{},remember { DropRegistry() }) } } }
    }
    @Test fun `new document is a rectangular 512 by 512 design`() {
        WatchDocumentStore(temp.newFolder().toPath()).newDocument().use { session ->
            val project = session.toWatchProject()
            assertEquals(512,project.width); assertEquals(512,project.height)
            assertTrue("New documents must be rectangular, not round",project.shape.lowercase() in setOf("s","square","rect","rectangle"))
        }
    }
    @Test fun `device preview starts disabled and no resolution chooser is shown`() {
        workspace(editor())
        val resolution = Regex("^\\d{2,4}\\s*[×x]\\s*\\d{2,4}$")
        val resolutionText = SemanticsMatcher("resolution selector text") { node ->
            runCatching { node.config[SemanticsProperties.Text] }.getOrDefault(emptyList()).any { resolution.matches(it.text) }
        }
        compose.onAllNodes(resolutionText).assertCountEquals(0)
        compose.onNodeWithTag("device-preview-enabled").assertDoesNotExist()
    }
    @Test fun `preview defaults keep device simulation off and Flue handles on`() {
        val defaults = PreviewPreferences()
        val type = defaults.javaClass
        val devicePreview = type.methods.firstOrNull { it.name == "getDevicePreviewEnabled" }
        val handles = type.methods.firstOrNull { it.name == "getShowTransformHandles" }
        assertNotNull("PreviewPreferences needs a persisted devicePreviewEnabled setting",devicePreview)
        assertNotNull("PreviewPreferences needs a persisted showTransformHandles setting",handles)
        assertEquals(false,devicePreview!!.invoke(defaults))
        assertEquals(true,handles!!.invoke(defaults))
    }
    @Test fun `preview canvas note points to shared global settings without stale document state`() {
        val editor=editor()
        compose.setContent { WFToolsTheme(false) { PreviewSettingsPanel(editor,Modifier.width(380.dp).height(760.dp)) } }
        compose.onNodeWithText("首页“设置”",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前使用",substring=true).assertDoesNotExist()
    }
    @Test fun `custom device size stays selected and exposes editable dimensions`() {
        val editor=editor()
        compose.setContent { WFToolsTheme(false) { PreviewSettingsPanel(editor,Modifier.width(380.dp).height(760.dp)) } }
        compose.onNodeWithTag("device-preview-enabled").onChildren().filter(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("480 × 480").performClick()
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithText("自定义").assertIsDisplayed()
        compose.onNodeWithTag("device-custom-width").assertIsDisplayed()
        compose.onNodeWithTag("device-custom-height").assertIsDisplayed()
        compose.onNodeWithTag("device-custom-width").performTextReplacement("333")
        compose.onNodeWithTag("device-custom-width").performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(3000) { editor.preview.device.width == 333 }
        compose.onNodeWithText("自定义").assertIsDisplayed()
    }
    @Test fun `asset page stays available and layer list has its own add layer pill`() {
        workspace(editor())
        compose.onNodeWithText("素材").assertExists()
        compose.onNodeWithTag("add-layer-pill").assert(hasText("添加图层") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("素材").performClick()
        compose.onNodeWithTag("add-layer-pill").assertDoesNotExist()
        compose.onNodeWithText("图层").performClick()
        compose.onNodeWithTag("add-layer-pill").assertIsDisplayed()
    }
    @Test fun `wide layout keeps add layer inside the layer tab`() {
        val editor = editor()
        val app = EditorApplicationModel(scope,EditorPreferences(temp.newFolder().toPath()))
        val commands = CommandDispatcher(app,editor) {}
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(1280.dp).height(800.dp)) { EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry()) } } }
        compose.onNodeWithTag("add-layer-pill").assertIsDisplayed()
        val searchBounds = compose.onNodeWithText("搜索图层").getUnclippedBoundsInRoot()
        val addBounds = compose.onNodeWithTag("add-layer-pill").getUnclippedBoundsInRoot()
        assertTrue("添加图层应位于搜索框下方",addBounds.top >= searchBounds.bottom)
        compose.onNodeWithText("素材").performClick()
        compose.onNodeWithTag("add-layer-pill").assertDoesNotExist()
    }
    @Test fun `operation choices use the approved direct drag and select first copy`() {
        val editor = editor()
        workspace(editor)
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithTag("workspace-settings-entry").performClick()
        compose.onNodeWithText("鼠标直拖").assertExists(); compose.onNodeWithText("先选后拖").assertExists()
        compose.onNodeWithText("按住图层即可移动，拖动未选中的图层时自动切换目标。").assertExists()
        compose.onNodeWithText("先选后拖").performClick()
        compose.onNodeWithText("先点击选择图层，再从画布任意位置拖动当前选区。").assertExists()
    }

    @Test fun `preview zoom control offers ten to two hundred percent without a current summary row`() {
        workspace(editor())
        compose.onNodeWithTag("workspace-zoom-control").performClick()
        compose.onNodeWithText("10%").assertExists()
        compose.onNodeWithText("200%").assertExists()
        compose.onNodeWithText("100% · 当前100%").assertDoesNotExist()
    }
    @Test fun `top finger action mode and overflow automatic snapping are independent`() {
        val editor=editor();val original=editor.view;val originalPreview=editor.preview
        val app=EditorApplicationModel(scope,EditorPreferences(temp.newFolder("snapping-app-prefs").toPath()))
        val commands=CommandDispatcher(app,editor){}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry())}}}
        compose.onNodeWithContentDescription("开启动作模式").performClick()
        compose.runOnIdle { assertTrue(editor.render.interactionMode); assertTrue(app.canvasInput.snapping) }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithTag("workspace-settings-entry").performClick()
        compose.onNodeWithTag("automatic-snapping-toggle").performClick()
        compose.runOnIdle { assertFalse(app.canvasInput.snapping); assertTrue(editor.render.interactionMode) }
        // Closing a popup is intentionally outside the behavioural assertion: mode flags remain independent.
        compose.runOnIdle {
            editor.render.interactionMode=false
            assertEquals(originalPreview.snapping,editor.preview.snapping);assertEquals(originalPreview.showTransformHandles,editor.preview.showTransformHandles);assertEquals(originalPreview.showOperationStatus,editor.preview.showOperationStatus)
            assertEquals(original.revision,editor.view.revision);assertEquals(original.project,editor.view.project)
        }
    }
    @Test fun `overflow canvas page edits the same six application input switches`() {
        val editor=editor();val app=EditorApplicationModel(scope,EditorPreferences(temp.newFolder("overflow-input-prefs").toPath()))
        val commands=CommandDispatcher(app,editor){}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry())}}}
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithTag("workspace-settings-entry").performClick()
        compose.onNodeWithTag("editor-middle-button-pan-toggle").assertExists().performClick()
        compose.onNodeWithTag("editor-right-button-pan-toggle").assertExists()
        compose.onNodeWithTag("editor-wheel-layer-scale-toggle").assertExists().performClick()
        compose.onNodeWithTag("automatic-snapping-toggle").assertExists().performClick()
        compose.onNodeWithTag("transform-handles-toggle").assertExists().performClick()
        compose.onNodeWithTag("operation-status-toggle").assertExists().performClick()
        compose.runOnIdle{assertFalse(app.canvasInput.middleButtonPan);assertTrue(app.canvasInput.rightButtonPan);assertFalse(app.canvasInput.wheelScalesSelectedLayer);assertFalse(app.canvasInput.snapping);assertFalse(app.canvasInput.showTransformHandles);assertFalse(app.canvasInput.showOperationStatus)}
    }
    @Test fun `overflow exposes file edit layer and appearance settings entries`() {
        workspace(editor())
        compose.onNodeWithContentDescription("更多操作").performClick()
        listOf("文件","编辑","图层","外观设置").forEach { title ->
            compose.onNode(hasText(title) and hasAnyAncestor(isPopup())).assertExists()
        }
    }
    @Test fun `folder control opens file context menu and save as sits beside save`() {
        workspace(editor())
        compose.onNodeWithContentDescription("文件操作").performClick()
        val popup = hasAnyAncestor(isPopup())
        compose.onNode(hasText("打开表盘") and popup).assertExists()
        compose.onNode(hasText("新建表盘") and popup).assertExists()
        compose.onNode(hasText("另存为…") and popup).assertExists()
        compose.onNodeWithTag("toolbar-save-as").assertIsDisplayed()
    }
    @Test fun `global script is edited in its own page with real checking and one explicit save`() {
        val editor = editor(); editor.requestedInspectorTab = "项目"
        workspace(editor)
        compose.onNodeWithText("编辑全局脚本").performScrollTo().performClick()
        val field = compose.onNodeWithTag("global-script-editor")
        val page = hasAnyAncestor(hasTestTag("global-script-page"))
        val before = editor.view.revision; val original = editor.view.project.globalScript
        field.performTextReplacement("function broken(")
        compose.onNode(hasText("检查") and page).performClick()
        compose.onNodeWithTag("script-error").assertIsDisplayed()
        compose.runOnIdle { assertEquals(original,editor.view.project.globalScript); assertEquals(before,editor.view.revision) }
        field.performTextReplacement("var_red = 42")
        compose.onNode(hasText("检查") and page).performClick()
        compose.onNodeWithTag("script-error").assertDoesNotExist()
        compose.onNode(hasText("保存") and page).performClick()
        compose.waitUntil(5000) { !editor.busy && editor.view.project.globalScript == "var_red = 42" }
        assertEquals(before+1,editor.view.revision)
        field.performTextReplacement("var_red = 99")
        compose.onNodeWithContentDescription("返回项目设置").performClick()
        compose.onNodeWithTag("script-unsaved-dialog").assertIsDisplayed()
        assertEquals("var_red = 42",editor.view.project.globalScript)
    }
    @Test fun `global script draft survives inspector tab and layout reconstruction`() {
        val editor=editor();val app=EditorApplicationModel(scope,EditorPreferences(temp.newFolder().toPath()));val commands=CommandDispatcher(app,editor){};var width by mutableStateOf(900.dp)
        compose.setContent { WFToolsTheme(false){Box(Modifier.width(width).height(760.dp)){EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry())}} }
        compose.onNodeWithText("项目").performClick();compose.onNodeWithText("编辑全局脚本").performScrollTo().performClick()
        compose.onNodeWithTag("global-script-editor").performTextReplacement("var_survives = 88")
        compose.runOnIdle { width=1200.dp }
        compose.onNodeWithText("项目").performClick()
        compose.onNodeWithTag("global-script-editor").assertTextContains("var_survives = 88")
        compose.onNodeWithText("预览").performClick();compose.onNodeWithText("项目").performClick()
        compose.onNodeWithTag("global-script-editor").assertTextContains("var_survives = 88")
        assertEquals("Draft must not enter document history merely by rebuilding UI","",editor.view.project.globalScript)
    }
    @Test fun `close guard keeps script draft on cancel and offers explicit save or discard`() {
        val editor=editor();var mode by mutableStateOf("project")
        compose.setContent { WFToolsTheme(false){if(mode=="project")ProjectPanel(editor,Modifier.width(420.dp).height(760.dp)) else DocumentCloseDialog(editor,onSaveDocument={},onDiscardDocument={},onCancel={mode="project"})} }
        compose.onNodeWithText("编辑全局脚本").performClick();compose.onNodeWithTag("global-script-editor").performTextReplacement("var_close_guard = true")
        compose.runOnIdle { mode="close" }
        compose.onNodeWithText("保存脚本并继续").assertExists();compose.onNodeWithText("放弃脚本草稿").assertExists();compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("global-script-editor").assertTextContains("var_close_guard = true")
        assertEquals("",editor.view.project.globalScript)
    }
    @Test fun `close dialog offers save as without discarding the current document`() {
        val editor=editor();var saveAs=false
        compose.setContent { WFToolsTheme(false) { DocumentCloseDialog(editor,onSaveDocument={},onDiscardDocument={},onCancel={},onSaveAs={saveAs=true}) } }
        compose.onNodeWithText("另存为").performClick()
        assertTrue(saveAs)
    }
    @Test fun `close dialog buttons follow discard cancel save as save order`() {
        val editor=editor()
        compose.setContent { WFToolsTheme(false) { DocumentCloseDialog(editor,onSaveDocument={},onDiscardDocument={},onCancel={}) } }
        val discard=compose.onNodeWithText("放弃",substring=false).getUnclippedBoundsInRoot()
        val cancel=compose.onNodeWithText("取消",substring=false).getUnclippedBoundsInRoot()
        val saveAs=compose.onNodeWithText("另存为",substring=false).getUnclippedBoundsInRoot()
        val save=compose.onNodeWithText("保存",substring=false).getUnclippedBoundsInRoot()
        assertTrue("关闭弹窗按钮必须依次为放弃、取消、另存为、保存",discard.left<cancel.left&&cancel.left<saveAs.left&&saveAs.left<save.left)
    }
    @Test fun `multi selection x slider preserves numeric spacing with one undo step`() {
        val editor=editor(2);val ids=editor.view.layers.map{it.id}
        editor.command { setLayerAttributes(mapOf(ids[0] to mapOf("x" to "0"),ids[1] to mapOf("x" to "100")));selectAll(ids) }
        compose.waitUntil(5000){!editor.busy&&editor.view.selection.selected.size==2};val revision=editor.view.revision
        compose.setContent { WFToolsTheme(false){PropertiesPanel(editor,DropRegistry(),Modifier.width(380.dp).height(760.dp))} }
        compose.onNodeWithTag("slider-X 位置").performMouseInput { moveTo(center);press();moveTo(center+Offset(30f,0f));release() }
        compose.waitUntil(5000){!editor.busy&&editor.view.revision==revision+1}
        val values=editor.view.layers.map{it.attrs.getValue("x").toDouble()};assertEquals(100.0,values[1]-values[0],.001)
        editor.command{undo()};compose.waitUntil(5000){!editor.busy};assertEquals(listOf("0","100"),editor.view.layers.map{it.attrs["x"]})
    }
    @Test fun `multi selection plus offsets each expression from its own base`() {
        val editor=editor(2);val ids=editor.view.layers.map{it.id};val expressions=listOf("{ds}" ,"({ds})+100")
        editor.command { setLayerAttributes(mapOf(ids[0] to mapOf("rotation" to expressions[0]),ids[1] to mapOf("rotation" to expressions[1])));selectAll(ids) }
        compose.waitUntil(5000){!editor.busy&&editor.view.selection.selected.size==2};val revision=editor.view.revision
        compose.setContent { WFToolsTheme(false){PropertiesPanel(editor,DropRegistry(),Modifier.width(380.dp).height(760.dp))} }
        compose.onNodeWithContentDescription("增大 旋转 °").performClick();compose.waitUntil(5000){!editor.busy&&editor.view.revision==revision+1}
        editor.view.layers.zip(expressions).forEach{(layer,base)->assertTrue(layer.attrs.getValue("rotation").contains(base))}
    }
    @Test fun `released canvas drag remains at its final location while persistence is delayed`() = deferredCanvasDrag(actionMode = false)
    private fun deferredCanvasDrag(actionMode: Boolean) {
        val editor = editor(); editor.render.interactionMode = actionMode
        var snapshot by mutableStateOf(PreviewDocumentSnapshot(editor.view.project,editor.view.layers,editor.view.selection))
        var revision by mutableStateOf(editor.view.revision)
        val initialWorkspace = WorkspaceViewState(fit = false,zoom = 1.0)
        var workspace by mutableStateOf(initialWorkspace)
        val queued = mutableListOf<DocumentSession.() -> Unit>()
        compose.setContent { WFToolsTheme(false) {
            WatchPreview(editor.session,revision,editor.render,DevicePreviewSettings(),workspace,{ workspace = it },{},
                Modifier.size(500.dp).testTag("deferred-canvas"),snapping = false,snapshot = snapshot,onDocumentCommand = { queued.add(it) })
        } }
        val canvas = compose.onNodeWithTag("deferred-canvas")
        val start = redCentroid(canvas.captureToImage())
        canvas.performMouseInput { moveTo(center); press(); moveTo(center+Offset(80f,40f)) }
        val inFlight = redCentroid(canvas.captureToImage())
        assertTrue("Mouse drag must move the authored shape, even in action mode",inFlight.x > start.x+40f)
        canvas.performMouseInput { release() }
        compose.runOnIdle { assertEquals(initialWorkspace,workspace); assertEquals("A drag commits exactly once",1,queued.size) }
        val pending = redCentroid(canvas.captureToImage())
        assertEquals("Release must not flash back to the old persistent x",inFlight.x,pending.x,2f)
        assertEquals("Release must not flash back to the old persistent y",inFlight.y,pending.y,2f)
        val committed = runBlocking(Dispatchers.IO) {
            queued.single().invoke(editor.session)
            editor.session.revision to PreviewDocumentSnapshot(editor.session.toWatchProject(),editor.session.layers,editor.session.selection)
        }
        compose.runOnIdle { revision = committed.first; snapshot = committed.second }
        val confirmed = redCentroid(canvas.captureToImage())
        assertEquals(pending.x,confirmed.x,2f); assertEquals(pending.y,confirmed.y,2f)
        assertEquals(initialWorkspace,workspace)
    }
    @Test fun `property slider preview survives release until its persistent revision arrives`() {
        val editor = editor(); editor.requestedInspectorTab = "属性"
        workspace(editor)
        val canvas = compose.onNodeWithTag("watch-preview")
        val original = redCentroid(canvas.captureToImage())
        val gate = CountDownLatch(1).also(gates::add)
        val entered = CountDownLatch(1)
        editor.command { entered.countDown(); check(gate.await(10,TimeUnit.SECONDS)) }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val before = editor.view.revision
        try {
            compose.onNodeWithTag("slider-X 位置").performMouseInput { moveTo(center); press(); moveTo(center+Offset(20f,0f)) }
            val transient = redCentroid(canvas.captureToImage())
            assertTrue("Slider changes must reach the actual preview before persistence",transient.x > original.x+5f)
            compose.onNodeWithTag("slider-X 位置").performMouseInput { release() }
            compose.runOnIdle { assertEquals(before,editor.view.revision) }
            val pending = redCentroid(canvas.captureToImage())
            assertEquals("Transient preview must survive mouse release",transient.x,pending.x,2f)
            gate.countDown()
            compose.waitUntil(5000) { !editor.busy && editor.view.revision > before }
            val confirmed = redCentroid(canvas.captureToImage())
            assertEquals("Persistent acknowledgement must not create a second jump",pending.x,confirmed.x,2f)
        } finally { gate.countDown() }
    }
    @Test fun `layer drag keeps its stable source id while the list scrolls and creates one undo step`() {
        val editor = editor(40)
        val before = editor.view.layers.asReversed().map { it.id }; val source = before[14]; val target = before[18]
        editor.command { select(source,before) }
        runBlocking { withTimeout(5000) { while(editor.busy) delay(5) } }
        val revision = editor.view.revision
        workspace(editor)
        val list = compose.onNodeWithTag("layer-list")
        list.performScrollToIndex(12)
        compose.onNodeWithTag("layer-row-$source").performMouseInput { moveTo(center); press(); moveTo(center+Offset(0f,70f)) }
        list.performScrollToIndex(16)
        compose.onNodeWithTag("layer-row-$target").performMouseInput { moveTo(Offset(center.x,center.y*1.7f)); release() }
        compose.waitUntil(5000) { !editor.busy }
        compose.runOnIdle {
            val after = editor.view.layers.asReversed().map { it.id }
            assertTrue("The grabbed ID must move after the current visual drop target",after.indexOf(source) > after.indexOf(target))
            assertEquals(setOf(source),editor.view.selection.selected)
            assertEquals("One reorder gesture creates one persistent edit",revision+1,editor.view.revision)
            editor.command { undo() }
        }
        compose.waitUntil(5000) { !editor.busy }
        assertEquals(before,editor.view.layers.asReversed().map { it.id })
    }
    private fun redCentroid(image: ImageBitmap): Offset {
        val pixels = image.toPixelMap(); var xSum = 0.0; var ySum = 0.0; var count = 0
        for(y in 0 until image.height) for(x in 0 until image.width) {
            val color = pixels[x,y]
            if(color.alpha > .9f && color.red > .85f && color.green < .25f && color.blue in .15f.. .4f) { xSum += x; ySum += y; count++ }
        }
        assertTrue("The solid authored shape must be visible",count > 100)
        return Offset((xSum/count).toFloat(),(ySum/count).toFloat())
    }

    @Test fun `wide inspector embeds color picker inside the properties pane`() {
        val editor = editor()
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(720.dp).height(800.dp)) {
            PropertiesPanel(editor, DropRegistry(), Modifier.fillMaxSize(), wideEditor = true)
        } } }
        compose.onNodeWithContentDescription("选择填充颜色").performScrollTo().performClick()
        compose.onNodeWithTag("color-side-panel").assertIsDisplayed()
    }

    @Test fun `wide inspector embeds click action editor inside the properties pane`() {
        val editor = editor()
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(720.dp).height(800.dp)) {
            PropertiesPanel(editor, DropRegistry(), Modifier.fillMaxSize(), wideEditor = true)
        } } }
        compose.onNodeWithTag("edit-tap-action").performScrollTo().performClick()
        compose.onNodeWithTag("action-side-panel").assertIsDisplayed()
    }

    @Test fun `expression variables use a vertical expandable list in the side panel`() {
        val editor = editor()
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(720.dp).height(800.dp)) {
            PropertiesPanel(editor, DropRegistry(), Modifier.fillMaxSize(), wideEditor = true)
        } } }
        compose.onAllNodesWithText("fx").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("expression-side-panel").assertIsDisplayed()
        val time=compose.onNodeWithTag("expression-group-时间").getUnclippedBoundsInRoot()
        val date=compose.onNodeWithTag("expression-group-日期").getUnclippedBoundsInRoot()
        assertTrue("变量分类应纵向排列",date.top >= time.bottom)
        compose.onNodeWithTag("expression-group-时间").performClick()
        compose.onNodeWithTag("expression-token-{dh}").assertIsDisplayed()
    }

    @Test fun `multiline fields expose a large dedicated resize handle`() {
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(360.dp).height(260.dp)) {
            CompactField("测试",{},Modifier.fillMaxWidth(),multiline=true)
        } } }
        val handle=compose.onNodeWithTag("field-resize-handle").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("拉杆命中区至少应为 28dp",(handle.right-handle.left).value>=28f&&(handle.bottom-handle.top).value>=28f)
    }

    @Test fun `side splitters follow each mouse delta without a touch slop threshold`() {
        var moved=0f
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(120.dp).height(500.dp)) { Splitter(false,"left-panel-splitter"){moved+=it} } } }
        compose.onNodeWithTag("left-panel-splitter").performMouseInput { moveTo(center);press();moveBy(Offset(36f,0f));release() }
        compose.runOnIdle { assertTrue("侧栏必须跟随这次短拖动",moved>20f) }
    }

    @Test fun `save as is placed immediately to the right of save`() {
        val editor=editor();val app=EditorApplicationModel(scope,EditorPreferences(temp.newFolder().toPath()));val commands=CommandDispatcher(app,editor){}
        compose.setContent { WFToolsTheme(false) { Box(Modifier.width(1280.dp).height(800.dp)) { EditorWorkspace(app,editor,commands,PanelPreferences(),{},DropRegistry()) } } }
        val save=compose.onNodeWithText("保存",substring=false).getUnclippedBoundsInRoot()
        val saveAs=compose.onNodeWithTag("toolbar-save-as").getUnclippedBoundsInRoot()
        assertTrue("另存为必须位于保存按钮右边",save.left<saveAs.left)
    }

    @Test fun `routine document edits do not publish a progress status`() {
        val editor=editor();val gate=CountDownLatch(1);gates+=gate
        editor.command("正在应用图层变换…") { gate.await(5,TimeUnit.SECONDS) }
        compose.waitUntil(3000){editor.busy}
        compose.runOnIdle { assertNull("普通编辑不能显示长任务进度状态",editor.status) }
    }

    @Test fun `middle button drag pans the workspace without changing the document`() {
        val editor = editor()
        val before = editor.view.revision
        var workspace by mutableStateOf(WorkspaceViewState())
        compose.setContent { WFToolsTheme(false) {
            WatchPreview(editor.session,editor.view.revision,editor.render,DevicePreviewSettings(),workspace,
                { workspace = it },{},Modifier.size(500.dp).testTag("middle-pan-canvas"))
        } }
        compose.onNodeWithTag("middle-pan-canvas").performMouseInput {
            moveTo(center)
            press(button = MouseButton.Tertiary)
            moveTo(center + Offset(80f,40f))
            release(button = MouseButton.Tertiary)
        }
        compose.runOnIdle {
            assertFalse(workspace.fit)
            assertEquals(80.0,workspace.panX,0.001)
            assertEquals(40.0,workspace.panY,0.001)
            assertEquals(before,editor.view.revision)
        }
    }

    @Test fun `select then drag with no selected layer pans the workspace`() {
        val editor=editor();editor.session.clearSelection()
        val before=editor.view.revision
        var workspace by mutableStateOf(WorkspaceViewState())
        compose.setContent{WFToolsTheme(false){WatchPreview(
            editor.session,editor.view.revision,editor.render,DevicePreviewSettings(),workspace,
            {workspace=it},{},Modifier.size(500.dp).testTag("empty-selection-pan-canvas"),
            operationMode=CanvasOperationMode.SELECT_THEN_DRAG,
        )}}
        compose.onNodeWithTag("empty-selection-pan-canvas").performMouseInput{
            moveTo(center);press();moveTo(center+Offset(70f,35f));release()
        }
        compose.runOnIdle{
            assertFalse(workspace.fit)
            assertEquals(70.0,workspace.panX,0.001);assertEquals(35.0,workspace.panY,0.001)
            assertEquals(before,editor.view.revision)
        }
    }

    @Test fun `disabled middle button pan leaves the workspace unchanged`() {
        val editor=editor();var workspace by mutableStateOf(WorkspaceViewState())
        compose.setContent{WFToolsTheme(false){WatchPreview(
            editor.session,editor.view.revision,editor.render,DevicePreviewSettings(),workspace,
            {workspace=it},{},Modifier.size(500.dp).testTag("disabled-middle-pan-canvas"),
            middleButtonPanEnabled=false,
        )}}
        compose.onNodeWithTag("disabled-middle-pan-canvas").performMouseInput{
            moveTo(center);press(button=MouseButton.Tertiary);moveTo(center+Offset(80f,40f));release(button=MouseButton.Tertiary)
        }
        compose.runOnIdle{assertEquals(WorkspaceViewState(),workspace)}
    }

    @Test fun `right button drag pans without opening the context menu but a short click opens it`() {
        val editor=editor();val before=editor.view.revision
        var workspace by mutableStateOf(WorkspaceViewState());var contextMenuAt:Offset?=null
        compose.setContent{WFToolsTheme(false){WatchPreview(
            editor.session,editor.view.revision,editor.render,DevicePreviewSettings(),workspace,
            {workspace=it},{},Modifier.size(500.dp).testTag("right-pan-canvas"),
            rightButtonPanEnabled=true,onContextMenu={contextMenuAt=it},
        )}}
        val canvas=compose.onNodeWithTag("right-pan-canvas")
        canvas.performMouseInput{
            moveTo(center);press(button=MouseButton.Secondary);moveTo(center+Offset(90f,45f));release(button=MouseButton.Secondary)
        }
        compose.runOnIdle{
            assertEquals(90.0,workspace.panX,0.001);assertEquals(45.0,workspace.panY,0.001)
            assertNull(contextMenuAt);assertEquals(before,editor.view.revision)
        }
        canvas.performMouseInput{moveTo(center);click(button=MouseButton.Secondary)}
        compose.runOnIdle{assertNotNull(contextMenuAt)}
    }
}
