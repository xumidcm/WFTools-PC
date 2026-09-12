@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.ailife.wftools.pc.platform.*
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    if(args.firstOrNull() == "--verify-runtime") kotlin.system.exitProcess(runRuntimeVerification(args.drop(1)))
    val paths = args.filterNot { it.startsWith("--") }.map(Path::of)
    val broker = try { InstanceBroker.openOrForward(paths) } catch (e: Exception) {
        javax.swing.JOptionPane.showMessageDialog(null, e.message?.let(DesktopI18n::text), "WFTools-PC", javax.swing.JOptionPane.ERROR_MESSAGE); return
    } ?: return
    val preferences = EditorPreferences()
    preferences.savedLanguage()?.let(DesktopI18n::setLanguage)
    val initialTheme = preferences.theme(); val initialPanels = preferences.panels(); val initialWindow = preferences.window()
    try { application {
        val scope = rememberCoroutineScope()
        val app = remember { EditorApplicationModel(scope, preferences) }
        DisposableEffect(app) {
            val integration=installProjectPanelIntegration(app)
            onDispose { integration.close() }
        }
        var theme by remember { mutableStateOf(initialTheme) }
        var panels by remember { mutableStateOf(initialPanels) }
        var windowPreference by remember { mutableStateOf(initialWindow) }
        LaunchedEffect(theme, panels, windowPreference, app.canvasInput) {
            delay(450)
            withContext(Dispatchers.IO) { runCatching { preferences.saveApplication(theme, panels, windowPreference, app.canvasInput) } }
        }
        LaunchedEffect(broker) { app.refreshRecovery(); for (request in broker.requests) app.open(request) }
        val dark = when(theme) { ThemePreference.SYSTEM -> isSystemInDarkTheme(); ThemePreference.LIGHT -> false; ThemePreference.DARK -> true }
        WFToolsTheme(dark) {
            if (app.launcher) LauncherWindow(app, windowPreference, theme, { theme = it }, onExit = { if (app.documents.isEmpty()) exitApplication() else app.launcher = false })
            app.documents.forEach { doc -> key(doc.windowId) {
                EditorWindow(app, doc, windowPreference, panels, theme, { theme = it }, { panels = it }, { windowPreference = it })
            } }
        }
    } } finally { broker.close() }
}

/** Shared by the application and UI integration tests; the chooser remains replaceable without touching files. */
internal fun installProjectPanelIntegration(app: EditorApplicationModel,requestSaveAs: (DocumentController)->Unit = { doc -> CommandDispatcher(app,doc) {}.save(true) }): AutoCloseable {
    val previousSave=ProjectPanelIntegration.saveAs;val previousScreenshot=ProjectPanelIntegration.screenshot
    val previousRecord=ProjectPanelIntegration.recordGif;val previousRecording=ProjectPanelIntegration.isRecording
    ProjectPanelIntegration.saveAs={doc,protect->if(protect)CommandDispatcher(app,doc) {}.saveProtected() else requestSaveAs(doc)}
    ProjectPanelIntegration.screenshot={doc->chooseCapturePath(doc.window,doc.view.title,"png","导出预览截图")?.let(doc::exportScreenshot)}
    ProjectPanelIntegration.recordGif={doc->chooseCapturePath(doc.window,doc.view.title,"gif","录制预览 GIF")?.let(doc::exportGif)}
    ProjectPanelIntegration.isRecording={doc->doc.status == "正在录制 GIF…"}
    return AutoCloseable { ProjectPanelIntegration.saveAs=previousSave;ProjectPanelIntegration.screenshot=previousScreenshot;ProjectPanelIntegration.recordGif=previousRecord;ProjectPanelIntegration.isRecording=previousRecording }
}

@Composable internal fun WFToolsTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = Color(0xFFD0BCFF), secondary = Color(0xFFCCC2DC), tertiary = Color(0xFFEFB8C8))
        else lightColorScheme(primary = Color(0xFF6650A4), secondary = Color(0xFF625B71), tertiary = Color(0xFF7D5260))
    val body = TextStyle(fontFamily = FontFamily("Segoe UI"), fontSize = 14.sp)
    MaterialTheme(colorScheme = colors, typography = Typography(bodyLarge = body, bodyMedium = body, bodySmall = body.copy(fontSize = 12.sp), labelLarge = body, labelMedium = body.copy(fontSize = 12.sp), labelSmall = body.copy(fontSize = 12.sp)), content = content)
}
private fun clampedSize(preference: WindowPreferences): DpSize {
    val gc = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val area = gc.bounds; val inset = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return DpSize(preference.width.coerceAtMost((area.width-inset.left-inset.right-32).coerceAtLeast(640)).dp,
        preference.height.coerceAtMost((area.height-inset.top-inset.bottom-32).coerceAtLeast(420)).dp)
}
@Composable private fun LauncherWindow(app: EditorApplicationModel, preference: WindowPreferences, theme: ThemePreference, onTheme: (ThemePreference) -> Unit, onExit: () -> Unit) {
    Window(onCloseRequest = onExit,title = "WFTools-PC",state = rememberWindowState(size = clampedSize(preference))) {
        val density by rememberUpdatedState(LocalDensity.current.density)
        val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
        val open = { chooseWatchFiles(window).takeIf { it.isNotEmpty() }?.let(app::open); Unit }
        DisposableEffect(window) {
            app.launcherWindow = window; window.minimumSize = Dimension(640,420)
            val listener = object : WindowAdapter() { override fun windowOpened(e: WindowEvent) { applyWindowChrome(window,dark) } }
            window.addWindowListener(listener)
            val drop = FileDropRegistration(window,window,{ density },{ paths,_,_ ->
                if (classifyDrop(paths) == DropBatch.WATCHES) app.open(paths)
                else app.error = "主界面只能打开 .watch 表盘；请先打开表盘后再拖入图片、视频或字体素材。"
            },{ app.error = it })
            onDispose { drop.close(); window.removeWindowListener(listener); app.launcherWindow = null }
        }
        LaunchedEffect(window,dark) { applyWindowChrome(window,dark) }
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if(event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.O) { open(); true }
            else if(event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.N) { app.newDocument(); true } else false
        }) { HomeContent(app,open,{ app.newDocument() },Modifier.fillMaxSize(),theme,onTheme) }
    }
}
@Composable private fun EditorWindow(app: EditorApplicationModel, doc: DocumentController, preference: WindowPreferences,
    panels: PanelPreferences, theme: ThemePreference, onTheme: (ThemePreference) -> Unit, onPanels: (PanelPreferences) -> Unit, onWindow: (WindowPreferences) -> Unit) {
    val state = rememberWindowState(size = clampedSize(preference))
    val view = doc.view
    fun continueClose() { doc.afterPending { if (doc.hasUnsavedChanges || doc.error != null) doc.closing = true else doc.finish(false) { app.closed(doc) } } }
    val close = { if(doc.hasGlobalScriptDraft)doc.closing=true else { doc.commitFocusedField?.invoke();continueClose() };Unit }
    Window(onCloseRequest = close, title = "${if (doc.hasUnsavedChanges) "* " else ""}${view.title} — WFTools-PC", state = state) {
        val density by rememberUpdatedState(LocalDensity.current.density)
        val drops = remember(doc) { DropRegistry() }
        val nativeDark by rememberUpdatedState(MaterialTheme.colorScheme.surface.luminance() < .5f)
        val commands = remember(app, doc) { CommandDispatcher(app, doc, close) }
        commands.close = close
        DisposableEffect(window, doc) {
            doc.window = window
            doc.render.visible = window.isVisible && (window.extendedState and Frame.ICONIFIED) == 0
            window.minimumSize = Dimension(640 + window.insets.left + window.insets.right, 420 + window.insets.top + window.insets.bottom)
            val listener = object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent) { doc.render.visible = (window.extendedState and Frame.ICONIFIED) == 0; applyWindowChrome(window,nativeDark) }
                override fun windowActivated(e: WindowEvent) { doc.render.visible = true; app.markActive(doc) }
                override fun windowDeactivated(e: WindowEvent) { /* Visible document windows keep independent previews running. */ }
                override fun windowIconified(e: WindowEvent) { doc.render.visible = false; doc.render.pauseMedia() }
                override fun windowDeiconified(e: WindowEvent) { doc.render.visible = window.isVisible }
            }
            window.addWindowListener(listener)
            val drop = FileDropRegistration(window, window, { density }, { paths,x,y -> drops.drop(app, doc, paths,x,y) }, { doc.error = it })
            onDispose {
                drop.close(); window.removeWindowListener(listener); doc.window = null; doc.render.visible = false
                if(state.placement == WindowPlacement.Floating) onWindow(WindowPreferences(state.size.width.value.toInt(),state.size.height.value.toInt()))
            }
        }
        LaunchedEffect(window,nativeDark) { applyWindowChrome(window,nativeDark) }
        LaunchedEffect(state.size) { delay(450); if (state.placement == WindowPlacement.Floating) onWindow(WindowPreferences(state.size.width.value.toInt(), state.size.height.value.toInt())) }
        LaunchedEffect(doc) { doc.restoreRenderPreferences(); doc.render.bind(doc.view.project).join(); doc.preview.simulation["_theme"]?.toIntOrNull()?.let(doc.render::selectTheme) }
        LaunchedEffect(doc.preview, doc.preferenceIdentity) {
            delay(450)
            val saved = doc.preview; val identity = doc.preferenceIdentity
            try { withContext(Dispatchers.IO) { app.preferences.savePreview(identity, saved) } } catch (e: Exception) { doc.error = "预览偏好保存失败：${e.message}" }
        }

        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event -> commands.key(event) }) {
            EditorWorkspace(app, doc, commands, panels, onPanels, drops, theme, onTheme)
            if (doc.closing && !doc.externalModification) DocumentCloseDialog(doc,
                onSaveDocument={commands.save(false){doc.finish(false){app.closed(doc)}}},
                onDiscardDocument={doc.finish(true){app.closed(doc)}},
                onContinueAfterDraft={if(doc.view.dirty)doc.closing=true else doc.finish(false){app.closed(doc)}},
                onCancel={doc.closing=false},
                onSaveAs={commands.save(true){doc.finish(false){app.closed(doc)}}})
            if(doc.externalModification) AlertDialog(onDismissRequest = { doc.externalModification = false; doc.closing = false }, title = { Text("文件已在外部更改") },
                text = { Text("原文件没有被覆盖。重新加载将放弃此窗口的本地修改；另存为可保留它们到其他文件。") },
                confirmButton = { TextButton(enabled = !doc.busy, onClick = { doc.externalModification = false; doc.closing = false; commands.save(true) }) { Text("另存为…") } },
                dismissButton = { Row { TextButton(enabled = !doc.busy && view.source != null, onClick = { doc.closing = false; app.reload(doc) }) { Text("重新加载") }; TextButton(onClick = { doc.externalModification = false; doc.closing = false; doc.error = null }) { Text("取消") } } })
        }
    }
}

@Composable internal fun DocumentCloseDialog(doc:DocumentController,onSaveDocument:()->Unit,onDiscardDocument:()->Unit,onContinueAfterDraft:()->Unit={},onCancel:()->Unit,onSaveAs:()->Unit={}) {
    if(doc.hasGlobalScriptDraft) AlertDialog(onDismissRequest={if(!doc.busy)onCancel()},modifier=Modifier.testTag("script-close-guard"),title={Text("保存全局脚本草稿？")},
        text={Column{Text("脚本编辑页还有未保存内容。可以先保存脚本再继续关闭，或只放弃脚本草稿。");doc.globalScriptDraftError?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},
        confirmButton={TextButton(enabled=!doc.busy,onClick={doc.saveGlobalScriptDraft{success->if(success)onSaveDocument()}}){Text("保存脚本并继续")}},
        dismissButton={Row{TextButton(enabled=!doc.busy,onClick=onSaveAs){Text("另存为")};TextButton(enabled=!doc.busy,onClick={doc.discardGlobalScriptDraft(closeEditor=true);onContinueAfterDraft()}){Text("放弃脚本草稿")};TextButton(enabled=!doc.busy,onClick=onCancel){Text("取消")}}})
    else AlertDialog(onDismissRequest={if(!doc.busy)onCancel()},title={Text("保存对“${doc.view.title}”的修改？")},
        text={Column{Text("保存后关闭窗口，或放弃此文档的未保存修改。");doc.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},
        confirmButton={TextButton(enabled=!doc.busy,onClick=onSaveDocument){Text("保存")}},
        dismissButton={Row{TextButton(enabled=!doc.busy,onClick=onDiscardDocument){Text("放弃")};TextButton(enabled=!doc.busy,onClick=onCancel){Text("取消")};TextButton(enabled=!doc.busy,onClick=onSaveAs){Text("另存为")}}})
}

class CommandDispatcher(private val app: EditorApplicationModel, val doc: DocumentController, var close: () -> Unit) {
    fun enabled(command: EditorCommand): Boolean {
        if (!commandOwnsFocus(command, doc.focus)) return false
        if (command in setOf(EditorCommand.NEW, EditorCommand.OPEN)) return true
        if (doc.editingBlocked) return false
        return when(command) {
            EditorCommand.UNDO -> doc.view.canUndo || doc.busy; EditorCommand.REDO -> doc.view.canRedo || doc.busy
            EditorCommand.COPY, EditorCommand.DUPLICATE, EditorCommand.DELETE, EditorCommand.HIDE, EditorCommand.SHOW, EditorCommand.TOP, EditorCommand.BOTTOM, EditorCommand.UP, EditorCommand.DOWN -> doc.view.selection.selected.isNotEmpty() || doc.busy
            else -> true
        }
    }
    fun save(asNew: Boolean, after: () -> Unit = {}) {
        doc.error = null
        if(doc.hasGlobalScriptDraft)doc.saveGlobalScriptDraft() else doc.commitFocusedField?.invoke()
        doc.afterPending { if(doc.error == null) saveCommitted(asNew, after) }
    }
    fun saveProtected(after: () -> Unit = {}) {
        doc.error = null
        if(doc.hasGlobalScriptDraft)doc.saveGlobalScriptDraft() else doc.commitFocusedField?.invoke()
        doc.afterPending {
            if(doc.error != null) return@afterPending
            val path = chooseSavePath(doc.window,null,"${doc.view.title}-protected",allowExisting=false) ?: return@afterPending
            val identity = canonicalIdentity(path)
            val other = app.documents.firstOrNull { it !== doc && (it.view.identity == identity || it.reservedSaveIdentity == identity) }
            if(other != null) { doc.error = "此导出路径正在另一个窗口编辑。请关闭该窗口或选择其他位置。"; app.focus(other) }
            else doc.exportProtected(path,after)
        }
    }
    private fun saveCommitted(asNew: Boolean, after: () -> Unit) {
        val source = doc.view.source
        val path = if (asNew || source == null) chooseSavePath(doc.window, source, doc.view.title) else source
        if (path != null) {
            val identity = canonicalIdentity(path)
            val other = app.documents.firstOrNull { it !== doc && (it.view.identity == identity || it.reservedSaveIdentity == identity) }
            if (other != null) { doc.error = "此保存路径正在另一个窗口编辑。请关闭该窗口或选择其他位置。"; app.focus(other) }
            else doc.save(path) { app.recordOpened(doc); after() }
        }
    }
    fun execute(command: EditorCommand) {
        if (!enabled(command)) return
        if(command in setOf(EditorCommand.OPEN, EditorCommand.NEW)) doc.commitFocusedField?.invoke()
        when(command) {
            EditorCommand.NEW -> app.newDocument()
            EditorCommand.OPEN -> chooseWatchFiles(doc.window).takeIf { it.isNotEmpty() }?.let(app::open)
            EditorCommand.SAVE -> save(false); EditorCommand.SAVE_AS -> save(true); EditorCommand.CLOSE -> close()
            EditorCommand.UNDO -> doc.command(refreshAssets = true) { undo() }; EditorCommand.REDO -> doc.command(refreshAssets = true) { redo() }
            EditorCommand.COPY -> doc.copy { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null); app.copiedScriptContext = doc.session.id to doc.view.project.globalScript }
            EditorCommand.PASTE -> pasteFromSystemClipboard()
            EditorCommand.DUPLICATE -> doc.command { duplicateSelected() }; EditorCommand.DELETE -> doc.command { deleteSelected() }
            EditorCommand.SELECT_ALL -> { val visible = doc.visible.map { it.id }; doc.command { selectAll(visible) } }
            EditorCommand.HIDE -> doc.command { setAttribute(selection.selected, "display", "n") }; EditorCommand.SHOW -> doc.command { setAttribute(selection.selected, "display", "bd") }
            EditorCommand.TOP -> doc.command { moveSelected(layers.size - selection.selected.size) }
            EditorCommand.BOTTOM -> doc.command { moveSelected(0) }
            EditorCommand.UP, EditorCommand.DOWN -> {
                doc.command {
                    val visual = layers.asReversed().map { it.id }
                    val positions = visual.indices.filter { visual[it] in selection.selected }
                    val gap = if (command == EditorCommand.UP) (positions.minOrNull() ?: 0) - 1 else (positions.maxOrNull() ?: 0) + 2
                    moveSelected(documentInsertionIndex(visual, selection.selected, gap))
                }
            }
        }
    }
    private fun pasteFromSystemClipboard() {
        if (!documentPasteOwnsFocus(doc.focus)) return
        if (doc.busy) { doc.error = "文档正在处理其他操作，请完成后再粘贴。"; return }
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val text = if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else null
            val imageAvailable = clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
            when (classifyClipboardPayload(imageAvailable, text)) {
                ClipboardPayload.LAYERS -> {
                    val sourceId = clipboardSourceId(requireNotNull(text))
                    val sourceScript = app.copiedScriptContext?.takeIf { it.first == sourceId }?.second
                        ?: app.documents.firstOrNull { it.session.id == sourceId }?.view?.project?.globalScript.orEmpty()
                    val needsScript = clipboardNeedsScriptMerge(text, doc.session.id, sourceScript)
                    doc.assetCommand("正在粘贴图层与素材…",onSuccess = { if(needsScript) doc.showNotice("素材已复制；全局脚本需自行合并。", NoticeAction.SCRIPT) }) { pasteLayers(text) }
                }
                ClipboardPayload.IMAGE -> pasteClipboardImage(clipboard.getData(DataFlavor.imageFlavor) as Image)
                ClipboardPayload.TEXT -> pasteClipboardText(requireNotNull(text))
                ClipboardPayload.NONE -> doc.error = "剪贴板中没有可粘贴的图片、文字或 WFTools 图层。"
            }
        } catch (e: Exception) { doc.error = "无法读取剪贴板：${e.message ?: "内容不可用"}" }
    }
    private fun pasteClipboardImage(image: Image) {
        val width = image.getWidth(null).takeIf { it > 0 } ?: throw IllegalArgumentException("图片宽度无效")
        val height = image.getHeight(null).takeIf { it > 0 } ?: throw IllegalArgumentException("图片高度无效")
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try { graphics.drawImage(image, 0, 0, null) } finally { graphics.dispose() }
        val temporary = Files.createTempFile("wftools-clipboard-", ".png")
        try {
            if (!ImageIO.write(buffered, "png", temporary.toFile())) throw IllegalStateException("PNG 编码器不可用")
        } catch (e: Exception) {
            Files.deleteIfExists(temporary)
            throw e
        }
        doc.assetCommand("正在粘贴图片并创建图层…", onSuccess = { doc.showNotice("已从剪贴板粘贴图片并创建图层。") }) {
            try { importAssets(listOf(temporary), createLayers = true) }
            finally { Files.deleteIfExists(temporary) }
        }
    }
    private fun pasteClipboardText(text: String) {
        if (text.isEmpty()) return
        doc.command("正在创建文字图层…") {
            addLayer("text")
            setAttribute(selection.selected, "text", text)
        }
    }
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val command = when {
            event.isCtrlPressed -> when(event.key) { Key.O -> EditorCommand.OPEN; Key.N -> EditorCommand.NEW; Key.S -> if (event.isShiftPressed) EditorCommand.SAVE_AS else EditorCommand.SAVE; Key.W -> EditorCommand.CLOSE; Key.Z -> if (event.isShiftPressed) EditorCommand.REDO else EditorCommand.UNDO; Key.Y -> EditorCommand.REDO; Key.C -> EditorCommand.COPY; Key.V -> EditorCommand.PASTE; Key.D -> EditorCommand.DUPLICATE; Key.A -> EditorCommand.SELECT_ALL; else -> null }
            event.key == Key.Delete -> EditorCommand.DELETE
            else -> null
        } ?: return false
        if (!enabled(command)) return false
        execute(command); return true
    }
}
