@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.ailife.wftools.pc.platform.chooseAssets
import com.ailife.wftools.pc.preview.*
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import kotlin.math.roundToInt

class DropRegistry {
    var library: DropBounds? = null
    var layerList: DropBounds? = null
    var preview: PreviewDropGeometry? = null
    var pendingWatch by mutableStateOf<PendingWatchDrop?>(null)
    val fields = mutableMapOf<String, PropertyDrop>()
    fun drop(app: EditorApplicationModel, doc: DocumentController, paths: List<Path>, x: Double, y: Double) {
        when(classifyDrop(paths)) {
            DropBatch.WATCHES -> {
                if (paths.size == 1) pendingWatch = PendingWatchDrop(paths.single())
                else doc.error = "编辑页面一次只能处理一个 .watch 文件；请分别拖入。"
                return
            }
            DropBatch.MIXED -> { doc.error = "表盘和素材不能混合拖入。请分别拖放 .watch 或素材文件。"; return }
            DropBatch.EMPTY -> return
            DropBatch.ASSETS -> Unit
        }
        if (doc.busy) { doc.error = "文档正在处理其他操作，请完成后再次拖入素材。"; return }
        val geometry = preview
        if (routeExternalDrop(x, y, layerList, library, geometry?.windowBounds?.dropBounds()) == ExternalDropTarget.LayerList) {
            doc.importExternalAssets(paths,createLayers=true,label="正在导入素材并创建图层…",onSuccess={doc.showNotice("素材已复制，并在图层列表中创建图层。")})
            return
        }
        when(val target = routeAssetDrop(x, y, fields.values.toList(), library, geometry?.windowBounds?.dropBounds())) {
            is AssetDropTarget.Property -> doc.replaceExternalAssets(target.ids,paths,target.attribute,"正在替换 ${paths.size} 个素材…",onSuccess={doc.showNotice("素材已替换，外部原文件保持不变。")})
            AssetDropTarget.Library -> doc.importExternalAssets(paths,createLayers=false,label="正在导入 ${paths.size} 个素材…",onSuccess={doc.showNotice("已将 ${paths.size} 个素材复制到素材库。")})
            AssetDropTarget.Preview -> {
                val point = geometry?.toDesign(x, y)
                doc.importExternalAssets(paths,x=point?.x?:0.0,y=point?.y?:0.0,label="正在导入 ${paths.size} 个素材并创建图层…",onSuccess={doc.showNotice("素材已复制并创建图层。")})
            }
            AssetDropTarget.Invalid -> doc.importExternalAssets(paths,createLayers=true,label="正在导入素材并创建图层…",onSuccess={doc.showNotice("素材已复制，并在画布中心创建图层。")})
        }
    }
}
data class PendingWatchDrop(val path: Path)
fun Rect.dropBounds() = DropBounds(left, top, right, bottom)

@Composable fun EditorWorkspace(app: EditorApplicationModel, doc: DocumentController, commands: CommandDispatcher,
    panels: PanelPreferences, onPanels: (PanelPreferences) -> Unit, drops: DropRegistry,
    theme: ThemePreference = ThemePreference.SYSTEM, onTheme: (ThemePreference) -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth.value
        val availableHeight = maxHeight.value
        val layout = editorLayout(availableWidth)
        if (!doc.agentFullscreen) Column(Modifier.fillMaxSize()) {
            Toolbar(doc,commands,theme,onTheme,app.canvasInput){app.canvasInput=it}
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when(layout) {
                    EditorLayout.THREE_COLUMNS -> Row(Modifier.fillMaxSize()) {
                        LibraryPanel(doc, commands, drops, Modifier.width(panels.left.dp).fillMaxHeight())
                        Splitter(false,"left-panel-splitter") { onPanels(panels.copy(left = (panels.left + it).coerceIn(180f, 520f))) }
                        PreviewPanel(doc,commands,drops,app.canvasInput,Modifier.weight(1f).fillMaxHeight())
                        Splitter(false,"right-panel-splitter") { onPanels(panels.copy(right = (panels.right - it).coerceIn(240f, 560f))) }
                        InspectorPanel(app, doc, drops, false, commands, Modifier.width(panels.right.dp).fillMaxHeight(), wideEditor = true, theme, onTheme)
                    }
                    EditorLayout.SIDE_INSPECTOR -> Row(Modifier.fillMaxSize()) {
                        PreviewPanel(doc,commands,drops,app.canvasInput,Modifier.weight(1f).fillMaxHeight())
                        Splitter(false,"right-panel-splitter") { onPanels(panels.copy(right = (panels.right - it).coerceIn(260f, 560f))) }
                        InspectorPanel(app, doc, drops, true, commands, Modifier.width(panels.right.coerceIn(260f, 560f).dp).fillMaxHeight(), wideEditor = true, theme, onTheme)
                    }
                    EditorLayout.STACKED -> Column(Modifier.fillMaxSize()) {
                        PreviewPanel(doc,commands,drops,app.canvasInput,Modifier.weight(1f).fillMaxWidth())
                        Splitter(true,"bottom-panel-splitter") { onPanels(panels.copy(bottom = (panels.bottom - it).coerceIn(160f, (availableHeight - 280f).coerceAtLeast(160f)))) }
                        InspectorPanel(app, doc, drops, true, commands, Modifier.fillMaxWidth().height(panels.bottom.coerceIn(160f, (availableHeight - 280f).coerceAtLeast(160f)).dp), wideEditor = false, theme, onTheme)
                    }
                }
            }
            doc.error?.let { ErrorBanner(it) { doc.error = null } }
            doc.notice?.let { message -> Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(message,Modifier.weight(1f),style = MaterialTheme.typography.bodySmall)
                    if (doc.noticeAction == NoticeAction.SCRIPT) TextButton({ doc.requestedInspectorTab = "项目"; doc.clearNotice() }) { Text("查看脚本") }
                    TextButton({ doc.clearNotice() }) { Text(if (doc.noticeAction == NoticeAction.SCRIPT) "关闭" else "确定") }
                }
            } }
            doc.view.draftError?.let { ErrorBanner("文件已保存，但恢复草稿写入失败：$it") { } }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (app.canvasInput.showOperationStatus) doc.status ?: "${doc.view.layers.size} 个图层 · 已选 ${doc.view.selection.selected.size}" else "${doc.view.layers.size} 个图层 · 已选 ${doc.view.selection.selected.size}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(doc.view.source?.fileName?.toString() ?: "尚未保存", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(12.dp)); Text(if(doc.hasUnsavedChanges) "有未保存修改" else "已保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (doc.showProgress && app.canvasInput.showOperationStatus) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 420.dp), shape = RoundedCornerShape(10.dp), tonalElevation = 4.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                Text(doc.status ?: "正在处理…", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (doc.agentFullscreen && app.agentSettings.enabled) Surface(Modifier.fillMaxSize().testTag("agent-fullscreen"), color = MaterialTheme.colorScheme.surface) {
            DesktopAgentPanel(app, doc, Modifier.fillMaxSize(), theme, onTheme)
        }
        drops.pendingWatch?.let { pending ->
            AlertDialog(
                onDismissRequest = { drops.pendingWatch = null },
                title = { Text("打开这个表盘？") },
                text = { Text("可以在新窗口打开 ${pending.path.fileName}，或只把其中的素材导入当前项目。项目属性和全局脚本不会被合并。") },
                confirmButton = { TextButton(onClick = { drops.pendingWatch = null; app.open(listOf(pending.path)) }) { Text("新窗口打开") } },
                dismissButton = { Row {
                    TextButton(onClick = { drops.pendingWatch = null; doc.importExternalWatchAssets(pending.path){doc.showNotice("表盘素材已导入当前项目。")} }) { Text("导入到当前项目") }
                    TextButton(onClick = { drops.pendingWatch = null }) { Text("取消") }
                } },
            )
        }
    }
}
@Composable private fun Toolbar(doc: DocumentController,commands: CommandDispatcher,theme: ThemePreference,onTheme: (ThemePreference)->Unit,canvasInput:CanvasInputPreferences,onCanvasInput:(CanvasInputPreferences)->Unit) {
    var more by remember { mutableStateOf(false) }
    var fileMenu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 4.dp,shadowElevation = 2.dp,color = MaterialTheme.colorScheme.surface) {
     Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
         Box {
             WmIconButton(Icons.Default.FolderOpen,"文件操作") { fileMenu = true }
             DropdownMenu(fileMenu,{ fileMenu = false },Modifier.testTag("file-context-menu")) {
                 listOf(EditorCommand.OPEN to "打开表盘",EditorCommand.NEW to "新建表盘",EditorCommand.SAVE to "保存",EditorCommand.SAVE_AS to "另存为…",EditorCommand.CLOSE to "关闭窗口").forEach { (command,label) ->
                     DropdownMenuItem(text = { Text(label) },enabled = commands.enabled(command),onClick = { fileMenu = false; commands.execute(command) })
                 }
             }
         }
        Text(doc.view.title,Modifier.weight(1f).padding(horizontal = 7.dp),style = MaterialTheme.typography.titleMedium,maxLines = 1,overflow = TextOverflow.Ellipsis)
        WmIconButton(Icons.Default.Undo,"撤销 · Ctrl+Z",commands.enabled(EditorCommand.UNDO)) { commands.execute(EditorCommand.UNDO) }
        WmIconButton(Icons.Default.Redo,"重做 · Ctrl+Y",commands.enabled(EditorCommand.REDO)) { commands.execute(EditorCommand.REDO) }
        WmIconButton(Icons.Default.TouchApp,if(doc.render.interactionMode) "关闭动作模式" else "开启动作模式",selected = doc.render.interactionMode) { doc.render.interactionMode = !doc.render.interactionMode; doc.saveRenderPreferences() }
        VerticalDivider(Modifier.height(20.dp).padding(horizontal = 2.dp))
         Button(onClick = { commands.execute(EditorCommand.SAVE) },enabled = commands.enabled(EditorCommand.SAVE),modifier = Modifier.height(34.dp),contentPadding = PaddingValues(horizontal = 14.dp),shape = RoundedCornerShape(18.dp)) {
             Icon(Icons.Default.Save,null,Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("保存")
         }
         Button(onClick = { commands.execute(EditorCommand.SAVE_AS) },enabled = commands.enabled(EditorCommand.SAVE_AS),modifier = Modifier.height(34.dp).testTag("toolbar-save-as"),contentPadding = PaddingValues(horizontal = 14.dp),shape = RoundedCornerShape(18.dp)) {
             Icon(Icons.Default.SaveAs,null,Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("另存为")
         }
        Box {
            WmIconButton(Icons.Default.MoreVert,"更多操作") { more = true }
            EditorOverflow(more,{ more = false },doc,commands,theme,onTheme,canvasInput,onCanvasInput)
        }
     }
    }
}
val layerTypes = listOf("image" to "图片", "image_gif" to "GIF 动画", "video" to "视频", "text" to "文字", "shape" to "形状", "rounded" to "圆角矩形", "progress" to "进度条", "ring" to "圆环", "text_curved" to "曲线文字", "markers" to "刻度", "markers_hm" to "时分刻度", "text_ring" to "文字环", "series" to "序列", "calendar" to "日历", "image_cond" to "条件图片", "chart" to "图表", "map" to "地图", "gallery_2d" to "二维图库")
@Composable internal fun Splitter(horizontal: Boolean, tag:String, onDelta: (Float) -> Unit) {
    val density = LocalDensity.current.density
    val current by rememberUpdatedState(onDelta)
    Box((if(horizontal) Modifier.fillMaxWidth().height(12.dp) else Modifier.fillMaxHeight().width(12.dp))
        .testTag(tag)
        .pointerHoverIcon(PointerIcon(Cursor(if(horizontal) Cursor.N_RESIZE_CURSOR else Cursor.E_RESIZE_CURSOR)))
        .pointerInput(horizontal) {
            // Use Compose's drag stream instead of sampling AWT screen
            // coordinates.  Screen events are occasionally absent on desktop
            // (and become stale while the Row is remeasured), which made the
            // splitter stop following the pointer.  dragAmount is local px and
            // remains correct when the surrounding layout changes mid-drag.
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    val delta = if (horizontal) dragAmount.y else dragAmount.x
                    if (delta != 0f) current(delta / density)
                    change.consume()
                },
            )
        },contentAlignment=Alignment.Center) {
        Box(if(horizontal)Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant)
            else Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
@Composable private fun LibraryPanel(doc: DocumentController, commands: CommandDispatcher, drops: DropRegistry, modifier: Modifier) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier) {
        CompactTabs(listOf("图层", "素材"), tab) { tab = it }
        if (tab == 0) LayerBrowser(doc, commands, Modifier.weight(1f),showAdd = true,drops = drops) else AssetsPanel(doc, drops, Modifier.weight(1f))
    }
}
@Composable private fun InspectorPanel(app: EditorApplicationModel, doc: DocumentController, drops: DropRegistry, all: Boolean, commands: CommandDispatcher, modifier: Modifier, wideEditor: Boolean, theme: ThemePreference, onTheme: (ThemePreference) -> Unit) {
    var tab by remember(all) { mutableStateOf(if(all) 0 else 0) }
    val tabs = (if(all) listOf("图层", "素材", "属性", "预览", "项目") else listOf("属性", "预览", "项目")) + if (app.agentSettings.enabled) listOf("AI") else emptyList()
    LaunchedEffect(doc.requestedInspectorTab) { doc.requestedInspectorTab?.let { requested -> tabs.indexOf(requested).takeIf { it >= 0 }?.let { tab = it; doc.requestedInspectorTab = null } } }
    Column(modifier) {
        CompactTabs(tabs, tab.coerceIn(0, tabs.lastIndex)) { selected -> tab = selected; if (!wideEditor && tabs[selected] == "AI") doc.agentFullscreen = true }
        when(tabs.getOrElse(tab) { "属性" }) { "图层" -> LayersPanel(doc, commands, Modifier.weight(1f),drops); "素材" -> AssetsPanel(doc, drops, Modifier.weight(1f)); "属性" -> PropertiesPanel(doc, drops, Modifier.weight(1f), wideEditor); "预览" -> PreviewSettingsPanel(doc, Modifier.weight(1f)); "项目" -> ProjectPanel(doc, Modifier.weight(1f)); "AI" -> DesktopAgentPanel(app, doc, Modifier.weight(1f), theme, onTheme) }
    }
}
@Composable private fun CompactTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    TabRow(selectedTabIndex = selected,modifier = Modifier.fillMaxWidth().height(42.dp),containerColor = MaterialTheme.colorScheme.surface,
        indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selected]),height = 3.dp,color = MaterialTheme.colorScheme.primary) },
        divider = { HorizontalDivider(thickness = .5.dp,color = MaterialTheme.colorScheme.outlineVariant) }) {
        tabs.forEachIndexed { index,title -> Tab(selected == index,{ focus.clearFocus(); onSelect(index) },modifier = Modifier.height(42.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(when(title) { "图层" -> Icons.Default.Layers; "素材" -> Icons.Default.PhotoLibrary; "属性" -> Icons.Default.Tune; "预览" -> Icons.Default.Visibility; "AI" -> Icons.Default.AutoAwesome; else -> Icons.Default.Watch },null,Modifier.size(15.dp))
                Text(title,fontSize = 12.sp,maxLines = 1)
            }
        } }
    }
}
@Composable private fun LayersPanel(doc: DocumentController, commands: CommandDispatcher, modifier: Modifier, drops: DropRegistry? = null) = LayerBrowser(doc,commands,modifier,drops = drops)
@Composable private fun AssetsPanel(doc: DocumentController, drops: DropRegistry, modifier: Modifier) = AssetBrowser(doc,drops,modifier)
private val layerContextCommands = listOf(EditorCommand.COPY, EditorCommand.PASTE, EditorCommand.DUPLICATE, EditorCommand.DELETE, EditorCommand.HIDE, EditorCommand.SHOW, EditorCommand.UP, EditorCommand.DOWN, EditorCommand.TOP, EditorCommand.BOTTOM)
@Composable private fun PreviewPanel(doc:DocumentController,commands:CommandDispatcher,drops:DropRegistry,canvasInput:CanvasInputPreferences,modifier:Modifier) {
    var more by remember { mutableStateOf(false) }
    var zoomMenu by remember { mutableStateOf(false) }
    var inlineTextEdit by remember { mutableStateOf<InlineTextEditRequest?>(null) }
    var contextMenu by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    BoxWithConstraints(modifier) {
      val compact = maxHeight.value < 360f || maxWidth.value < 420f
      val width = maxWidth.value
      Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp,color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WmIconButton(Icons.Default.FitScreen,"适合工作区",selected = doc.preview.workspace.fit) { doc.preview = doc.preview.copy(workspace = WorkspaceViewState()) }
            if(width > 550f) Box(Modifier.testTag("workspace-zoom-control")) {
                CompactButton(if(doc.preview.workspace.fit) "自动" else "${(doc.preview.workspace.zoom*100).roundToInt()}%", tooltip = "选择画布缩放比例") { zoomMenu = true }
                DropdownMenu(zoomMenu,{zoomMenu=false},modifier=Modifier.testTag("workspace-zoom-menu")) {
                    (10..200 step 10).forEach { percent ->
                        DropdownMenuItem(text={Text("$percent%")},onClick={zoomMenu=false;doc.preview=doc.preview.copy(workspace=doc.preview.workspace.copy(fit=false,zoom=percent/100.0))})
                    }
                }
            }
            if(compact) {
                WmIconButton(if(doc.render.playing) Icons.Default.Pause else Icons.Default.PlayArrow,if(doc.render.playing) "暂停" else "播放",selected = true) { doc.render.playing = !doc.render.playing; doc.saveRenderPreferences() }
                if(width > 600f) {
                    WmIconButton(if(doc.render.isBright) Icons.Default.LightMode else Icons.Default.DarkMode,if(doc.render.isBright) "亮屏预览" else "息屏预览") { doc.render.isBright = !doc.render.isBright; doc.saveRenderPreferences() }
                }
            } else if(width > 470f) Text(if(doc.preview.workspace.fit) "自动" else "${(doc.preview.workspace.zoom*100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Box {
                WmIconButton(Icons.Default.MoreHoriz,"缩放与预览选项") { more = true }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text(if(doc.render.isBright) "切换息屏预览" else "切换亮屏预览") }, onClick = { more = false; doc.render.isBright = !doc.render.isBright; doc.saveRenderPreferences() })
                    DropdownMenuItem(text = { Text("设备与模拟设置…") },onClick = { more = false; doc.requestedInspectorTab = "预览" })
                }
            }
        }
        }
        Box(Modifier.weight(1f).fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
          Box(Modifier.fillMaxSize().onFocusChanged { if(it.hasFocus) doc.focus = EditorFocus.CANVAS }) {
            val view = doc.view
            WatchPreview(doc.session, view.revision, doc.render, effectivePreviewDevice(doc.preview), doc.preview.workspace,
                onWorkspaceChange = { doc.preview = doc.preview.copy(workspace = it) }, onDocumentChange = doc::refreshFromPreview,
                modifier = Modifier.fillMaxSize().testTag("watch-preview"),snapping=canvasInput.snapping,operationMode=doc.preview.operationMode,showTransformHandles=canvasInput.showTransformHandles,
                middleButtonPanEnabled=canvasInput.middleButtonPan,rightButtonPanEnabled=canvasInput.rightButtonPan,wheelScalesSelectedLayer=canvasInput.wheelScalesSelectedLayer,
                onLayerAttributesCommit = doc::commitLayerAttributes, onDropGeometry = { drops.preview = it },
                snapshot = doc.previewSnapshot, committedLayerAttributes = view.layers.associate { it.id to it.attrs }, onDocumentCommand = { command -> if(!doc.editingBlocked) doc.command(block = command) },
                onTextEdit = { id,position -> inlineTextEdit = InlineTextEditRequest(id,position) },onContextMenu={contextMenu=it})
            inlineTextEdit?.let { request ->
                val id=request.id
                val layer = doc.view.layers.firstOrNull { it.id == id }
                if (layer != null) {
                    InlineCanvasTextEditor(layer.attrs["text"].orEmpty(),request.position,
                        onPreview={doc.previewLayerText(setOf(id),it)},
                        onCommit={text->doc.commitLayerText(setOf(id),text);inlineTextEdit=null})
                }
            }
            if(doc.editingBlocked) Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while(true) awaitPointerEvent().changes.forEach { it.consume() } } })
          }
          Md3LayerMenu(contextMenu,{contextMenu=null},commands)
        }
        if(!compact) Surface(tonalElevation = 2.dp,color = MaterialTheme.colorScheme.surfaceContainer) {
         Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WmIconButton(if(doc.render.playing) Icons.Default.Pause else Icons.Default.PlayArrow,if(doc.render.playing) "暂停预览" else "播放预览",selected = true,size = 36.dp) { doc.render.playing = !doc.render.playing; doc.saveRenderPreferences() }
            PreviewClock(doc)
            CompactButton("${doc.render.timeSpeed.toInt()}×",tooltip = "设置时间机器") { doc.requestedInspectorTab = "预览" }
            Spacer(Modifier.weight(1f))
            WmSegmented(listOf("b" to "亮屏","d" to "息屏"),if(doc.render.isBright) "b" else "d",{ doc.render.isBright = it == "b"; doc.saveRenderPreferences() },Modifier.width(140.dp))
         }
        }
        doc.render.error?.let { Text("脚本：$it", Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        doc.render.actionMessage?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
      }
    }
    DisposableEffect(drops) { onDispose { drops.preview = null } }
}
@Composable private fun PreviewClock(doc: DocumentController) { Text(doc.render.frame.now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }

private data class InlineTextEditRequest(val id:String,val position:androidx.compose.ui.geometry.Offset)

@Composable private fun InlineCanvasTextEditor(initial:String,position:androidx.compose.ui.geometry.Offset,onPreview:(String)->Unit,onCommit:(String)->Unit) {
    var value by remember(initial){mutableStateOf(TextFieldValue(initial,TextRange(initial.length)))}
    var receivedFocus by remember{mutableStateOf(false)}
    var committed by remember{mutableStateOf(false)}
    val requester=remember{FocusRequester()}
    fun commit(){if(!committed){committed=true;onCommit(value.text)}}
    LaunchedEffect(Unit){withFrameNanos{};requester.requestFocus()}
    BasicTextField(value,{value=it;onPreview(it.text)},Modifier
        .offset{IntOffset(position.x.roundToInt(),position.y.roundToInt())}
        .widthIn(min=180.dp,max=360.dp).heightIn(min=42.dp,max=180.dp)
        .background(MaterialTheme.colorScheme.surface,RoundedCornerShape(8.dp))
        .border(2.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(8.dp))
        .focusRequester(requester)
        .onFocusChanged{state->if(state.isFocused)receivedFocus=true else if(receivedFocus)commit()}
        .onPreviewKeyEvent{event->
            when {
                event.type==KeyEventType.KeyDown&&event.key==Key.Enter&&event.isShiftPressed->{
                    val start=value.selection.min;val end=value.selection.max
                    val next=value.text.substring(0,start)+"\n"+value.text.substring(end)
                    value=TextFieldValue(next,TextRange(start+1));onPreview(next);true
                }
                event.type==KeyEventType.KeyDown&&event.key==Key.Enter->{commit();true}
                else->false
            }
        }
        .pointerHoverIcon(PointerIcon(Cursor(Cursor.TEXT_CURSOR)))
        .padding(9.dp).testTag("inline-text-editor"),
        textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),singleLine=false)
}
