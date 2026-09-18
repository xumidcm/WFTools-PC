package com.ailife.wftools.pc.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.core.DocumentSession
import com.ailife.wftools.pc.core.EditorLayer
import com.ailife.wftools.pc.core.LayerSelection
import com.ailife.wftools.pc.input.TouchTargets
import com.ailife.wftools.pc.input.isTouchOnly
import com.ailife.wftools.pc.render.RenderSession
import com.ailife.wftools2.domain.editor.watchmaker.*
import com.ailife.wftools2.ui.editor.components.wmDisplayVisible
import com.ailife.wftools2.ui.editor.components.wmLayerBounds
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.Cursor
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

data class PreviewDropGeometry(
    val windowBounds: Rect,
    val viewport: ViewportTransform,
    val workspace: WorkspaceTransform,
    val device: DevicePreviewSettings,
    val round: Boolean,
) {
    fun toDesign(x: Double, y: Double): Point? {
        val p = workspace.toDevice(Point(x - windowBounds.left, y - windowBounds.top))
        if (!viewport.containsDevice(p, device, round)) return null
        return viewport.deviceToDesign(p)
    }
}

data class PreviewDocumentSnapshot(val project: WmWatchfaceProject, val layers: List<EditorLayer>, val selection: LayerSelection)
private data class MiddlePanGesture(val start: Offset, val workspace: WorkspaceViewState, val scale: Double)
private data class RightPanGesture(
    val start:Offset,
    val workspace:WorkspaceViewState,
    val scale:Double,
    val hitId:String?,
    val order:List<String>,
    val selection:LayerSelection,
    val moved:Boolean=false,
)

private data class RuntimeBindingKey(val script: String, val themes: List<WmThemeSet>, val tags: Set<String>)
private val runtimeTagPattern = Regex("\\{[^{}]+}")

private fun runtimeBindingKey(project: WmWatchfaceProject) = RuntimeBindingKey(
    project.globalScript,
    project.themeSets,
    project.wmLayers.flatMapTo(linkedSetOf()) { layer ->
        layer.attrs.values.flatMap { value -> runtimeTagPattern.findAll(value).map { it.value }.toList() }
    },
)

private fun layerMatrix(layer: WmWatchfaceLayer, state: WmTagsData.WmPreviewState, dir: java.io.File): Pair<AffineTransform, Pair<Double, Double>> {
    val bounds = wmLayerBounds(layer, 0f, state, dir, includeScale = false)
    fun eval(key: String, default: String) = WmTagsData.evaluatePreview(layer.getAttr(key, default), state).toDoubleOrNull() ?: default.toDouble()
    val alignment = layer.getAttr("alignment", "cc")
    val ax = if (layer.layerType == "text_curved") .5 else when (alignment.getOrNull(1)) { 'l' -> 0.0; 'r' -> 1.0; else -> .5 }
    val ay = if (layer.layerType == "text_curved") {
        if (layer.getAttr("curve_dir", "Up") == "Up") 1.0 else 0.0
    } else when (alignment.getOrNull(0)) { 't' -> 0.0; 'b' -> 1.0; else -> .5 }
    val bitmapFont = layer.getAttr("font").startsWith("bm:") && layer.layerType == "text"
    val matrix = AffineTransform()
    val gyro = layer.getAttr("gyro").toDoubleOrNull() ?: 0.0
    matrix.translate(bounds.x + bounds.w * ax + state.gyro.second * gyro, bounds.y + bounds.h * ay - state.gyro.first * gyro)
    matrix.rotate(Math.toRadians(eval("rotation", "0")))
    if (!bitmapFont) matrix.scale(eval("anim_scale_x", "100") / 100, eval("anim_scale_y", "100") / 100)
    matrix.translate(-bounds.w * ax, -bounds.h * ay)
    val skewAx = when (alignment.getOrNull(1)) { 'l' -> 0.0; 'r' -> 1.0; else -> .5 }
    val skewAy = when (alignment.getOrNull(0)) { 't' -> 0.0; 'b' -> 1.0; else -> .5 }
    matrix.translate(bounds.w * skewAx, bounds.h * skewAy)
    matrix.shear(-tan(Math.toRadians(eval("skew_x", "0"))), -tan(Math.toRadians(eval("skew_y", "0"))))
    matrix.translate(-bounds.w * skewAx, -bounds.h * skewAy)
    return matrix to (bounds.w.toDouble() to bounds.h.toDouble())
}

private fun layerCorners(layer: WmWatchfaceLayer, state: WmTagsData.WmPreviewState, dir: java.io.File): List<Point> {
    val (matrix, size) = layerMatrix(layer, state, dir)
    return listOf(Point(0.0, 0.0), Point(size.first, 0.0), Point(size.first, size.second), Point(0.0, size.second)).map {
        val p = matrix.transform(Point2D.Double(it.x, it.y), null)
        Point(p.x, p.y)
    }
}

private fun WmWatchfaceLayer.withUpdates(values: Map<String, String>?): WmWatchfaceLayer {
    var result = this
    values?.forEach { (key, value) -> result = result.withAttr(key, value) }
    return result
}

private fun moveUpdates(layers: List<Pair<String, WmWatchfaceLayer>>, delta: Point): Map<String, Map<String, String>> =
    layers.associate { (id, layer) -> id to buildMap {
        if (delta.x != 0.0) put("x", addEditorDelta(layer.getAttr("x", "0"), delta.x))
        if (delta.y != 0.0) put("y", addEditorDelta(layer.getAttr("y", "0"), delta.y))
    } }.filterValues { it.isNotEmpty() }

private fun center(points: List<Point>) = Point(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)

private fun rotate(point: Point, center: Point, degrees: Double): Point {
    val angle = Math.toRadians(degrees)
    val dx = point.x - center.x
    val dy = point.y - center.y
    return Point(center.x + dx * cos(angle) - dy * sin(angle), center.y + dx * sin(angle) + dy * cos(angle))
}

internal fun textTransformAttributes(original: WmWatchfaceLayer, delta: HandleTransformDelta): Map<String, String> {
    val epsilon=1e-9
    val factor=when {
        abs(delta.scaleX-1.0)>epsilon && abs(delta.scaleY-1.0)<=epsilon -> delta.scaleX
        abs(delta.scaleY-1.0)>epsilon && abs(delta.scaleX-1.0)<=epsilon -> delta.scaleY
        else -> sqrt((delta.scaleX*delta.scaleY).coerceAtLeast(.0001))
    }
    return if (abs(factor-1.0)>epsilon) mapOf("text_size" to multiplyEditorValue(original.getAttr("text_size", "24"),factor)) else emptyMap()
}

private fun transformUpdates(
    layers: List<Pair<String, WmWatchfaceLayer>>,
    frame: SelectionFrame,
    delta: HandleTransformDelta,
    state: WmTagsData.WmPreviewState,
    dir: java.io.File,
): Map<String, Map<String, String>> = layers.associate { (id, original) ->
    val oldCenter = center(layerCorners(original, state, dir))
    val local = frame.toLocal(oldCenter)
    val scaledCenter = frame.toWorld(Point(local.x * delta.scaleX, local.y * delta.scaleY))
    val desiredCenter = rotate(scaledCenter, frame.center, delta.rotationDegrees)
    val changed = linkedMapOf<String, String>()
    if (original.layerType == "text" || original.layerType == "text_curved") changed.putAll(textTransformAttributes(original,delta))
    else {
        if (abs(delta.scaleX - 1.0) > 1e-9) changed["anim_scale_x"] = multiplyEditorValue(original.getAttr("anim_scale_x", "100"), delta.scaleX)
        if (abs(delta.scaleY - 1.0) > 1e-9) changed["anim_scale_y"] = multiplyEditorValue(original.getAttr("anim_scale_y", "100"), delta.scaleY)
    }
    if (abs(delta.rotationDegrees) > 1e-9) changed["rotation"] = addEditorDelta(original.getAttr("rotation", "0"), delta.rotationDegrees)
    val provisional = original.withUpdates(changed)
    val provisionalCenter = center(layerCorners(provisional, state, dir))
    val dx = desiredCenter.x - provisionalCenter.x
    val dy = desiredCenter.y - provisionalCenter.y
    if (abs(dx) > 1e-9) changed["x"] = addEditorDelta(original.getAttr("x", "0"), dx)
    if (abs(dy) > 1e-9) changed["y"] = addEditorDelta(original.getAttr("y", "0"), dy)
    id to changed
}.filterValues { it.isNotEmpty() }

private fun selectedFrame(
    ids: Set<String>,
    project: WmWatchfaceProject,
    layers: List<EditorLayer>,
    state: WmTagsData.WmPreviewState,
    directory: java.io.File,
): SelectionFrame? {
    val points = project.wmLayers.flatMapIndexed { index, layer ->
        if (layers.getOrNull(index)?.id in ids) layerCorners(layer, state, directory) else emptyList()
    }
    if (points.isEmpty()) return null
    return runCatching {
        if (ids.size == 1 && points.size == 4) SelectionFrame.fromCorners(points) else SelectionFrame.axisAligned(points)
    }.getOrNull()
}

private fun rotateOffsetInDesign(frame: SelectionFrame, viewport: ViewportTransform, workspace: WorkspaceTransform, screenPixels: Double): Double {
    val screenLength = hypot(viewport.scaleX * frame.axisY.x, viewport.scaleY * frame.axisY.y) * workspace.scale
    return screenPixels / screenLength.coerceAtLeast(.01)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WatchPreview(
    document: DocumentSession,
    revision: Long,
    render: RenderSession,
    device: DevicePreviewSettings,
    workspace: WorkspaceViewState,
    onWorkspaceChange: (WorkspaceViewState) -> Unit,
    onDocumentChange: () -> Unit,
    modifier: Modifier = Modifier,
    snapping: Boolean = true,
    operationMode: CanvasOperationMode = CanvasOperationMode.DIRECT_DRAG,
    showTransformHandles: Boolean = true,
    middleButtonPanEnabled: Boolean = true,
    rightButtonPanEnabled: Boolean = true,
    wheelScalesSelectedLayer: Boolean = true,
    onDropGeometry: (PreviewDropGeometry) -> Unit = {},
    snapshot: PreviewDocumentSnapshot? = null,
    committedLayerAttributes: Map<String, Map<String, String>>? = null,
    onDocumentCommand: ((DocumentSession.() -> Unit) -> Unit)? = null,
    onLayerAttributesCommit: ((Map<String, Map<String, String>>, (Boolean) -> Unit) -> Unit)? = null,
    onTextEdit: (String, Offset) -> Unit = { _,_ -> },
    onContextMenu: (Offset) -> Unit = {},
) {
    val project = snapshot?.project ?: remember(document.id, revision) { document.toWatchProject() }
    val layers = snapshot?.layers ?: remember(document.id, revision) { document.layers }
    val selection = snapshot?.selection ?: document.selection
    val design = remember { DesignExtent(512.0, 512.0) }
    val round = device.shape.isRound(project.shape)
    val viewport = remember(device) { ViewportTransform.create(design, device) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val work = remember(size, device, workspace) { WorkspaceTransform.create(size.width.toDouble(), size.height.toDouble(), device, workspace) }
    var liveEdit by remember(document.id) { mutableStateOf(LiveEditHandoff()) }
    var gestureActive by remember(document.id) { mutableStateOf(false) }
    var middlePan by remember(document.id) { mutableStateOf<MiddlePanGesture?>(null) }
    var rightPan by remember(document.id) { mutableStateOf<RightPanGesture?>(null) }
    var pointerPanActive by remember(document.id) { mutableStateOf(false) }
    var spaceDown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current.density
    val currentWorkspace by rememberUpdatedState(workspace)
    val currentWork by rememberUpdatedState(work)
    val currentProject by rememberUpdatedState(project)
    val currentLayers by rememberUpdatedState(layers)
    val notify by rememberUpdatedState(onDocumentChange)
    val dispatch by rememberUpdatedState(onDocumentCommand)
    val commitAttributes by rememberUpdatedState(onLayerAttributesCommit)
    val currentSelection by rememberUpdatedState(selection)
    val currentSnapping by rememberUpdatedState(snapping)
    val currentMode by rememberUpdatedState(operationMode)
    val currentShowHandles by rememberUpdatedState(showTransformHandles)
    val currentMiddleButtonPanEnabled by rememberUpdatedState(middleButtonPanEnabled)
    val currentRightButtonPanEnabled by rememberUpdatedState(rightButtonPanEnabled)
    val currentWheelScalesSelectedLayer by rememberUpdatedState(wheelScalesSelectedLayer)
    val openContextMenu by rememberUpdatedState(onContextMenu)
    val currentRevision by rememberUpdatedState(revision)
    val currentLiveEdit by rememberUpdatedState(liveEdit)
    val updateWorkspace by rememberUpdatedState(onWorkspaceChange)
    val frame = render.frame
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    val externalEditActive = committedLayerAttributes != null && layers.any { layer -> committedLayerAttributes[layer.id] != layer.attrs }
    val currentExternalEditActive by rememberUpdatedState(externalEditActive)
    var hoveredHandle by remember { mutableStateOf<TransformHandle?>(null) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapPosition by remember { mutableStateOf(Offset.Unspecified) }
    var lastTapId by remember { mutableStateOf<String?>(null) }
    fun handleAt(position: Offset): TransformHandle? {
        if (!currentShowHandles || render.interactionMode || currentSelection.selected.isEmpty()) return null
        val selectionFrame = selectedFrame(currentSelection.selected,currentProject,currentLayers,render.frame,document.workDir.toFile()) ?: return null
        val handles = if (currentSelection.selected.size > 1) listOf(TransformHandle.TOP_LEFT,TransformHandle.TOP_RIGHT,TransformHandle.BOTTOM_RIGHT,TransformHandle.BOTTOM_LEFT,TransformHandle.ROTATE) else TransformHandle.entries.toList()
        val offset = rotateOffsetInDesign(selectionFrame,viewport,currentWork,26.0*density)
        return handles.map { kind ->
            val point = currentWork.toWorkspace(viewport.designToDevice(selectionFrame.handle(kind,offset)))
            kind to hypot(point.x-position.x,point.y-position.y)
        }.minByOrNull { it.second }?.takeIf { it.second <= transformHandleHitRadiusDp(touch = false) * density }?.first
    }
    val hoverCursor = remember(hoveredHandle,middlePan,pointerPanActive,spaceDown) { PointerIcon(Cursor(when {
        middlePan != null || pointerPanActive || spaceDown -> Cursor.MOVE_CURSOR
        hoveredHandle != null -> transformHandleCursor(requireNotNull(hoveredHandle))
        else -> Cursor.DEFAULT_CURSOR
    })) }

    val bindingKey = remember(project) { runtimeBindingKey(project) }
    LaunchedEffect(bindingKey, render) { render.bind(project) }
    val committedAttributes = committedLayerAttributes ?: layers.associate { it.id to it.attrs }
    LaunchedEffect(revision, committedAttributes) {
        liveEdit = liveEdit.observeDocument(revision, committedAttributes)
    }
    SideEffect { onDropGeometry(PreviewDropGeometry(bounds, viewport, work, device, round)) }
    fun command(action: DocumentSession.() -> Unit) {
        val handler = dispatch
        if (handler != null) handler(action) else {
            document.action()
            notify()
        }
    }

    fun commit(updates: Map<String, Map<String, String>>) {
        if (liveEdit.pending) return
        val changed = changedLayerAttributes(updates, currentLayers.associate { it.id to it.attrs })
        if (changed.isEmpty()) { liveEdit = LiveEditHandoff(); return }
        liveEdit = LiveEditHandoff().update(changed).release(currentRevision)
        val handler = commitAttributes
        if (handler != null) handler(changed) { success -> if (!success) liveEdit = LiveEditHandoff() }
        else command { setLayerAttributes(changed) }
    }

    fun visualLayer(index: Int, original: WmWatchfaceLayer, edit: LiveEditHandoff = currentLiveEdit, layerList: List<EditorLayer> = currentLayers) =
        original.withUpdates(layerList.getOrNull(index)?.id?.let(edit.values::get))

    fun hit(p: Point): Int? = currentProject.wmLayers.indices.reversed().firstOrNull { index ->
        val layer = visualLayer(index, currentProject.wmLayers[index])
        val opacity = render.evaluate(layer.getAttr("opacity", "100")).toDoubleOrNull() ?: 100.0
        val action = layer.getAttr("tap_action").ifBlank { layer.getAttr("tap") }
        if (!wmDisplayVisible(layer.getAttr("display"), render.isBright) ||
            (render.interactionMode && action.isBlank()) || (!render.interactionMode && opacity <= 0.0)
        ) false else runCatching {
            val (matrix, wh) = layerMatrix(layer, render.frame, document.workDir.toFile())
            val local = matrix.inverseTransform(Point2D.Double(p.x, p.y), null)
            local.x in 0.0..wh.first && local.y in 0.0..wh.second
        }.getOrDefault(false)
    }

    /**
     * 触摸长按呼出菜单时的命中：先在落点取样，再在容差内取邻近取样点。
     * 手指比鼠标粗，只有一两像素高的细图层否则点得到却选不中。
     */
    fun touchMenuHitId(position: Offset): String? {
        val slopPx = contextMenuHitSlopDp(touch = true) * density
        return contextMenuHitProbes(slopPx).firstNotNullOfOrNull { probe ->
            val devicePoint = currentWork.toDevice(Point(position.x + probe.x, position.y + probe.y))
            if (!viewport.containsDevice(devicePoint, device, round)) null
            else hit(viewport.deviceToDesign(devicePoint))?.let { currentLayers[it].id }
        }
    }

    Box(
        modifier.clipToBounds().background(Color(0xff24212a))
            .onSizeChanged { size = it }
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .focusRequester(focus)
            .onFocusChanged { if (!it.hasFocus) { spaceDown = false; middlePan = null; rightPan = null; pointerPanActive = false; gestureActive = false } }
            .onPointerEvent(PointerEventType.Press,PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull()
                if (event.buttons.isTertiaryPressed && change != null && currentLiveEdit.values.isEmpty() && !currentExternalEditActive &&
                    canvasDragPans(CanvasDragButton.TERTIARY,currentMode,currentSelection.selected,middleButtonPanEnabled=currentMiddleButtonPanEnabled,rightButtonPanEnabled=currentRightButtonPanEnabled)) {
                    focus.requestFocus()
                    middlePan = MiddlePanGesture(change.position,currentWorkspace,currentWork.scale)
                    pointerPanActive = true
                    gestureActive = true
                    change.consume()
                } else if(event.buttons.isSecondaryPressed && change != null && currentLiveEdit.values.isEmpty() && !currentExternalEditActive) {
                    focus.requestFocus()
                    val devicePoint=currentWork.toDevice(Point(change.position.x.toDouble(),change.position.y.toDouble()))
                    val index=if(viewport.containsDevice(devicePoint,device,round))hit(viewport.deviceToDesign(devicePoint))else null
                    rightPan=RightPanGesture(change.position,currentWorkspace,currentWork.scale,index?.let{currentLayers[it].id},currentLayers.map{it.id}.reversed(),currentSelection)
                    gestureActive=true
                    change.consume()
                }
            }
            .onPointerEvent(PointerEventType.Move,PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull()
                val pan = middlePan
                val secondaryPan=rightPan
                if(secondaryPan!=null&&change!=null&&event.buttons.isSecondaryPressed) {
                    val moved=secondaryPan.moved||(change.position-secondaryPan.start).getDistance()>=3.0*density
                    if(moved) {
                        rightPan=secondaryPan.copy(moved=true)
                        if(currentRightButtonPanEnabled) {
                            pointerPanActive=true
                            updateWorkspace(secondaryPan.workspace.copy(fit=false,zoom=secondaryPan.scale,
                                panX=secondaryPan.workspace.panX+change.position.x-secondaryPan.start.x,
                                panY=secondaryPan.workspace.panY+change.position.y-secondaryPan.start.y))
                        }
                    }
                    change.consume()
                } else if (pan != null && change != null && event.buttons.isTertiaryPressed) {
                    updateWorkspace(pan.workspace.copy(fit = false,zoom = pan.scale,
                        panX = pan.workspace.panX + change.position.x - pan.start.x,
                        panY = pan.workspace.panY + change.position.y - pan.start.y))
                    change.consume()
                } else if (change != null && !event.isTouchOnly()) hoveredHandle = handleAt(change.position)
            }
            .onPointerEvent(PointerEventType.Release,PointerEventPass.Initial) { event ->
                val secondaryPan=rightPan
                if(secondaryPan!=null&&!event.buttons.isSecondaryPressed) {
                    if(!secondaryPan.moved) {
                        val hitId=secondaryPan.hitId
                        if(!render.interactionMode&&hitId!=null&&hitId !in secondaryPan.selection.selected)command{select(hitId,secondaryPan.order)}
                        openContextMenu(secondaryPan.start)
                    }
                    rightPan=null
                    pointerPanActive=false
                    gestureActive=false
                    event.changes.forEach{it.consume()}
                } else if (middlePan != null && !event.buttons.isTertiaryPressed) {
                    middlePan = null
                    pointerPanActive = false
                    gestureActive = false
                    event.changes.forEach { it.consume() }
                }
            }
            .onPointerEvent(PointerEventType.Exit) { hoveredHandle = null }
            .pointerHoverIcon(hoverCursor)
            .onKeyEvent { event ->
                if (event.key == Key.Spacebar) {
                    spaceDown = event.type == KeyEventType.KeyDown
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown || render.interactionMode || gestureActive || externalEditActive) return@onKeyEvent false
                val step = if (event.isAltPressed) .1 else if (event.isShiftPressed) 10.0 else 1.0
                val delta = when (event.key) {
                    Key.DirectionLeft -> Point(-step, 0.0)
                    Key.DirectionRight -> Point(step, 0.0)
                    Key.DirectionUp -> Point(0.0, -step)
                    Key.DirectionDown -> Point(0.0, step)
                    else -> null
                }
                if (delta != null) {
                    // Keyboard nudges are discrete FIFO commands. Keeping them out
                    // of the pointer handoff lets fast repeated key presses queue
                    // without dropping samples while a prior nudge is persisted.
                    command { nudge(delta.x, delta.y) }
                    true
                } else false
            }
            .focusable()
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (gestureActive || liveEdit.values.isNotEmpty() || currentExternalEditActive) return@onPointerEvent
                val mouse = event.changes.firstOrNull() ?: return@onPointerEvent
                val devicePoint = currentWork.toDevice(Point(mouse.position.x.toDouble(),mouse.position.y.toDouble()))
                val index = if (viewport.containsDevice(devicePoint,device,round)) hit(viewport.deviceToDesign(devicePoint)) else null
                val hitId = index?.let { currentLayers[it].id }
                when (canvasWheelAction(hitId,currentSelection.selected,event.keyboardModifiers.isCtrlPressed,render.interactionMode,false,currentWheelScalesSelectedLayer)) {
                    CanvasWheelAction.IGNORE -> Unit
                    CanvasWheelAction.ZOOM_WORKSPACE -> {
                        val previous = currentWork
                        updateWorkspace(centeredWorkspaceZoom(currentWorkspace,previous.scale,mouse.scrollDelta.y.toDouble()))
                        mouse.consume()
                    }
                    CanvasWheelAction.SCALE_SELECTED_LAYER -> {
                        val ids = currentSelection.selected
                        val originals = currentProject.wmLayers.mapIndexedNotNull { i, layer -> currentLayers[i].id.takeIf { it in ids }?.let { it to layer } }
                        val frameForScale = selectedFrame(ids,currentProject,currentLayers,render.frame,document.workDir.toFile())
                        if (frameForScale != null) {
                            val factor = exp(-mouse.scrollDelta.y*.08).coerceIn(.25,4.0)
                            commit(transformUpdates(originals,frameForScale,HandleTransformDelta(factor,factor),render.frame,document.workDir.toFile()))
                            mouse.consume()
                        }
                    }
                }
            }
            .pointerInput(document.id, device, viewport, render.interactionMode, operationMode, showTransformHandles, round, density) {
                awaitEachGesture {
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        focus.requestFocus()
                        if (currentLiveEdit.values.isNotEmpty() || currentExternalEditActive) return@awaitEachGesture
                        val downButtons = currentEvent.buttons
                        // Middle-button presses are handled by the raw desktop pointer events above;
                        // awaitFirstDown does not reliably expose them as a pressed change on Windows.
                        if (downButtons.isTertiaryPressed || downButtons.isSecondaryPressed) return@awaitEachGesture
                        gestureActive = true
                        // 触屏没有右键、中键，也没有悬停：长按既负责呼出上下文菜单，也是画布平移的起点。
                        // 桌面 Compose 的多指支持参差，这里只用单指长按，任何触摸设备都能用。
                        val touch = currentEvent.isTouchOnly()
                        val touchHoldSlop = touchHoldSlopDp(touch) * density
                        val downAt = System.currentTimeMillis()
                        val ctrlAtDown = currentEvent.keyboardModifiers.isCtrlPressed
                        val shiftAtDown = currentEvent.keyboardModifiers.isShiftPressed
                        val ctrlPanCandidate = ctrlAtDown && downButtons.isPrimaryPressed && !spaceDown
                        val originalWorkspace = currentWorkspace
                        val initialWork = currentWork
                        val startDevice = initialWork.toDevice(Point(down.position.x.toDouble(), down.position.y.toDouble()))
                        val start = viewport.deviceToDesign(startDevice)
                        val index = if (viewport.containsDevice(startDevice, device, round)) hit(start) else null
                        val hitId = index?.let { currentLayers[it].id }
                        val order = currentLayers.map { it.id }.reversed()
                        val initialSelection = currentSelection
                        val hardPan = canvasDragPans(
                            CanvasDragButton.PRIMARY,
                            currentMode,initialSelection.selected,hitId=hitId,middleButtonPanEnabled=currentMiddleButtonPanEnabled,rightButtonPanEnabled=currentRightButtonPanEnabled,
                            spaceDown=spaceDown,ctrlPrimary=ctrlPanCandidate,
                        )
                        val expectedSelection = hitId?.let { canvasPressSelection(initialSelection, it, order, ctrlAtDown, shiftAtDown) }

                        val initialFrame = selectedFrame(initialSelection.selected, currentProject, currentLayers, render.frame, document.workDir.toFile())
                        val rotateOffset = initialFrame?.let { rotateOffsetInDesign(it, viewport, initialWork, 26.0 * density) } ?: 0.0
                        val handles = if (initialSelection.selected.size > 1) {
                            listOf(TransformHandle.TOP_LEFT, TransformHandle.TOP_RIGHT, TransformHandle.BOTTOM_RIGHT, TransformHandle.BOTTOM_LEFT, TransformHandle.ROTATE)
                        } else TransformHandle.entries
                        val handle = if (currentShowHandles && !render.interactionMode && !hardPan && !ctrlPanCandidate && initialFrame != null) {
                            handles.map { kind ->
                                val point = initialWork.toWorkspace(viewport.designToDevice(initialFrame.handle(kind, rotateOffset)))
                                kind to hypot(point.x - down.position.x, point.y - down.position.y)
                            }.minByOrNull { it.second }?.takeIf { it.second <= transformHandleHitRadiusDp(touch) * density }?.first
                        } else null

                        // Ctrl/Shift always edit the selection up front; an empty selection lets
                        // direct drag adopt the pressed layer. An existing plain selection keeps
                        // ownership so a press on a higher layer cannot steal the drag.
                        val selectionEditedOnPress = ctrlAtDown || shiftAtDown || canvasPressAdoptsHit(currentMode, initialSelection.selected)
                        if (handle == null && !hardPan && !ctrlPanCandidate && !render.interactionMode && selectionEditedOnPress && hitId != null) {
                            if (expectedSelection != initialSelection) command { select(hitId, order, ctrlAtDown, shiftAtDown) }
                        }

                        val gestureSelection = if (selectionEditedOnPress) expectedSelection?.selected ?: initialSelection.selected else initialSelection.selected
                        val targetIds = when {
                            hardPan || ctrlPanCandidate || render.interactionMode -> emptySet()
                            handle != null -> initialSelection.selected
                            else -> canvasDragTargets(currentMode, hitId, gestureSelection)
                        }
                        val originals = currentProject.wmLayers.mapIndexedNotNull { i, layer -> currentLayers[i].id.takeIf { it in targetIds }?.let { it to layer } }
                        val gestureFrame = selectedFrame(targetIds, currentProject, currentLayers, render.frame, document.workDir.toFile())
                        val selectedPoints = originals.flatMap { (_, layer) -> layerCorners(layer, render.frame, document.workDir.toFile()) }
                        val others = if (selectedPoints.isEmpty()) emptyList() else currentProject.wmLayers.flatMapIndexed { i, layer ->
                            if (currentLayers[i].id !in targetIds && wmDisplayVisible(layer.getAttr("display"), render.isBright)) layerCorners(layer, render.frame, document.workDir.toFile()) else emptyList()
                        }
                        var moved = false
                        var panning = hardPan
                        var finalUpdates = emptyMap<String, Map<String, String>>()
                        // 触摸长按：成立后这一手势变成画布平移；抬手时若手指没有再移动，就呼出上下文菜单。
                        var touchHoldArmed = touch
                        var touchHold = false
                        var touchHoldMoved = false
                        var holdMovement = 0f
                        var lastPosition = down.position
                        var panFrom = down.position

                        while (true) {
                            if (touchHoldArmed && !touchHold) {
                                when (touchHoldIntent(System.currentTimeMillis() - downAt, holdMovement, touchHoldSlop)) {
                                    TouchGestureIntent.CONTEXT_MENU -> {
                                        // 长按成立：按住期间的细微抖动不算编辑，清掉它可能留下的临时帧。
                                        touchHold = true
                                        panning = true
                                        panFrom = lastPosition
                                        liveEdit = LiveEditHandoff()
                                    }
                                    TouchGestureIntent.MOVE -> touchHoldArmed = false
                                    else -> Unit
                                }
                            }
                            // 手指静止时不会有 Move 事件，长按只能由时间驱动。
                            val event = if (touchHoldArmed && !touchHold) {
                                val remaining = (TouchTargets.LONG_PRESS_MILLIS - (System.currentTimeMillis() - downAt)).coerceAtLeast(1L)
                                withTimeoutOrNull(remaining) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (event == null) continue
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            lastPosition = change.position
                            holdMovement = (change.position - down.position).getDistance()
                            if (!change.pressed) {
                                if (touchHold && !touchHoldMoved) {
                                    // 触屏没有右键：长按不动是上下文菜单的唯一入口，复用右键菜单回调。
                                    val target = touchMenuHitId(down.position)
                                    if (!render.interactionMode && target != null && target !in initialSelection.selected) command { select(target, order) }
                                    openContextMenu(down.position)
                                    lastTapAt = 0L
                                    lastTapId = null
                                    liveEdit = LiveEditHandoff()
                                } else if (!render.interactionMode && moved && !panning && finalUpdates.isNotEmpty()) {
                                    liveEdit = LiveEditHandoff().update(finalUpdates)
                                    commit(finalUpdates)
                                } else {
                                    liveEdit = LiveEditHandoff()
                                    if (!moved && !render.interactionMode && handle == null && !panning) {
                                        val now = System.currentTimeMillis()
                                        val tappedId = hitId
                                        val doubleTap = isTextEditDoubleTap(
                                            sameTarget = tappedId != null && tappedId == lastTapId,
                                            elapsedMillis = now - lastTapAt,
                                            movementPx = (lastTapPosition - down.position).getDistance(),
                                            movementSlopPx = doubleTapSlopDp(touch) * density,
                                        )
                                        if (doubleTap && tappedId != null && currentLayers.firstOrNull { it.id == tappedId }?.type in setOf("text", "text_curved")) {
                                            onTextEdit(tappedId,down.position)
                                            lastTapAt = 0L
                                            lastTapId = null
                                        } else {
                                            lastTapAt = now
                                            lastTapPosition = down.position
                                            lastTapId = tappedId
                                        }
                                        when {
                                            ctrlPanCandidate && hitId != null -> command { select(hitId, order, ctrl = true, shift = shiftAtDown) }
                                            // A plain click retargets the selection. When the press already
                                            // adopted the layer (direct drag from an empty selection) or
                                            // applied ctrl/shift, the selection is settled and must not be
                                            // recomputed on release.
                                            !ctrlAtDown && !shiftAtDown && !selectionEditedOnPress && hitId != null && hitId !in initialSelection.selected -> command { select(hitId, order) }
                                            currentMode == CanvasOperationMode.DIRECT_DRAG && hitId != null && !ctrlAtDown && !shiftAtDown && initialSelection.selected.size > 1 && hitId in initialSelection.selected -> command { select(hitId, order) }
                                            currentMode == CanvasOperationMode.DIRECT_DRAG && hitId == null && !ctrlAtDown -> command { clearSelection() }
                                        }
                                    } else if (!moved && render.interactionMode && index != null) {
                                        val currentIndex = hitId?.let { id -> currentLayers.indexOfFirst { it.id == id } } ?: -1
                                        currentProject.wmLayers.getOrNull(currentIndex)?.let { layer ->
                                            render.runTap(layer.getAttr("tap_action").ifBlank { layer.getAttr("tap") })
                                        }
                                    }
                                }
                                break
                            }
                            if (holdMovement < 3 && !moved) continue
                            moved = true
                            // 长按成立之后还在移动 = 平移画布，抬手不再弹菜单。
                            if (touchHold) touchHoldMoved = touchHoldMoved || (change.position - panFrom).getDistance() >= 3.0
                            if (ctrlPanCandidate) panning = true
                            if (panning) {
                                pointerPanActive = true
                                updateWorkspace(originalWorkspace.copy(fit = false, zoom = initialWork.scale,
                                    panX = originalWorkspace.panX + change.position.x - panFrom.x,
                                    panY = originalWorkspace.panY + change.position.y - panFrom.y))
                            } else if (!render.interactionMode && originals.isNotEmpty()) {
                                val current = viewport.deviceToDesign(initialWork.toDevice(Point(change.position.x.toDouble(), change.position.y.toDouble())))
                                finalUpdates = if (handle != null && gestureFrame != null) {
                                    val handleStart = gestureFrame.handle(handle, rotateOffsetInDesign(gestureFrame, viewport, initialWork, 26.0 * density))
                                    val delta = if (handle == TransformHandle.ROTATE) {
                                        val snap = currentSnapping && !event.keyboardModifiers.isAltPressed
                                        HandleTransformDelta(rotationDegrees = rotationHandleDelta(gestureFrame, handleStart, current, snap,
                                            event.keyboardModifiers.isShiftPressed && !event.keyboardModifiers.isAltPressed))
                                    } else transformHandleDelta(gestureFrame, handle, handleStart, current)
                                    transformUpdates(originals, gestureFrame, delta, render.frame, document.workDir.toFile())
                                } else {
                                    var delta = Point(current.x - start.x, current.y - start.y)
                                    if (event.keyboardModifiers.isShiftPressed) delta = if (abs(delta.x) > abs(delta.y)) Point(delta.x, 0.0) else Point(0.0, delta.y)
                                    if (currentSnapping && !event.keyboardModifiers.isAltPressed && selectedPoints.isNotEmpty()) {
                                        val tx = 6 * density / (initialWork.scale * viewport.scaleX)
                                        val ty = 6 * density / (initialWork.scale * viewport.scaleY)
                                        val left = selectedPoints.minOf { it.x }; val right = selectedPoints.maxOf { it.x }
                                        val top = selectedPoints.minOf { it.y }; val bottom = selectedPoints.maxOf { it.y }
                                        fun snap(values: List<Double>, targets: List<Double>, amount: Double, threshold: Double): Double {
                                            val correction = values.flatMap { value -> targets.map { it - value - amount } }.minByOrNull { abs(it) } ?: return amount
                                            return if (abs(correction) <= threshold) amount + correction else amount
                                        }
                                        if (!(event.keyboardModifiers.isShiftPressed && delta.x == 0.0)) delta = delta.copy(x = snap(listOf(left, (left + right) / 2, right), listOf(-256.0, 0.0, 256.0) + others.map { it.x }, delta.x, tx))
                                        if (!(event.keyboardModifiers.isShiftPressed && delta.y == 0.0)) delta = delta.copy(y = snap(listOf(top, (top + bottom) / 2, bottom), listOf(-256.0, 0.0, 256.0) + others.map { it.y }, delta.y, ty))
                                    }
                                    moveUpdates(originals, delta)
                                }
                                liveEdit = LiveEditHandoff().update(finalUpdates)
                            }
                            change.consume()
                        }
                    } finally {
                        gestureActive = false
                        pointerPanActive = false
                        if (!liveEdit.pending) liveEdit = LiveEditHandoff()
                    }
                }
            },
    ) {
        WatchSurface(project, render, device, Modifier.graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = work.left.toFloat(); translationY = work.top.toFloat()
            scaleX = work.scale.toFloat(); scaleY = work.scale.toFloat()
        }) { index, original -> visualLayer(index, original, liveEdit) }

        if (!render.interactionMode) Canvas(Modifier.fillMaxSize()) {
            val deviceClip = Path().apply {
                val left = work.left.toFloat(); val top = work.top.toFloat()
                val right = (left + device.width * work.scale).toFloat(); val bottom = (top + device.height * work.scale).toFloat()
                if (round) {
                    val radius = (min(device.width, device.height) * work.scale / 2).toFloat()
                    val cx = (left + right) / 2; val cy = (top + bottom) / 2
                    addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
                } else addRect(Rect(left, top, right, bottom))
            }
            clipPath(deviceClip) {
                val visualIds = liveEdit.values.keys.takeIf { it.isNotEmpty() } ?: selection.selected
                val visualCorners = mutableListOf<List<Point>>()
                project.wmLayers.forEachIndexed { index, original ->
                    if (layers[index].id in visualIds) {
                        val adjusted = visualLayer(index, original, liveEdit)
                        val points = layerCorners(adjusted, frame, document.workDir.toFile())
                        visualCorners += points
                        val screen = points.map { work.toWorkspace(viewport.designToDevice(it)) }
                        val path = Path().apply {
                            screen.forEachIndexed { i, point -> if (i == 0) moveTo(point.x.toFloat(), point.y.toFloat()) else lineTo(point.x.toFloat(), point.y.toFloat()) }
                            close()
                        }
                        drawPath(path, accent, style = Stroke(1.5.dp.toPx()))
                    }
                }
                val frameForHandles = when {
                    visualCorners.isEmpty() -> null
                    visualCorners.size == 1 -> runCatching { SelectionFrame.fromCorners(visualCorners.single()) }.getOrNull()
                    else -> SelectionFrame.axisAligned(visualCorners.flatten())
                }
                frameForHandles?.takeIf { showTransformHandles }?.let { selectionFrame ->
                    val kinds = if (visualIds.size > 1) listOf(TransformHandle.TOP_LEFT, TransformHandle.TOP_RIGHT, TransformHandle.BOTTOM_RIGHT, TransformHandle.BOTTOM_LEFT, TransformHandle.ROTATE) else TransformHandle.entries
                    val offset = rotateOffsetInDesign(selectionFrame, viewport, work, 26.0 * density)
                    val top = work.toWorkspace(viewport.designToDevice(selectionFrame.handle(TransformHandle.TOP)))
                    val rotate = work.toWorkspace(viewport.designToDevice(selectionFrame.handle(TransformHandle.ROTATE, offset)))
                    drawLine(accent, Offset(top.x.toFloat(), top.y.toFloat()), Offset(rotate.x.toFloat(), rotate.y.toFloat()), 1.5.dp.toPx())
                    kinds.forEach { kind ->
                        val point = work.toWorkspace(viewport.designToDevice(selectionFrame.handle(kind, offset)))
                        drawCircle(accent, 6.dp.toPx(), Offset(point.x.toFloat(), point.y.toFloat()))
                        drawCircle(handleFill, 3.5.dp.toPx(), Offset(point.x.toFloat(), point.y.toFloat()))
                    }
                }
            }
        }
    }
}
