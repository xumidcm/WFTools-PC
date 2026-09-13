package com.ailife.wftools.pc.preview

import com.ailife.wftools.pc.core.LayerSelection
import java.math.BigDecimal
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/** Resolves the stable layer IDs owned by one pointer gesture. */
fun canvasDragTargets(mode: CanvasOperationMode, hitId: String?, selection: Set<String>): Set<String> = when (mode) {
    CanvasOperationMode.DIRECT_DRAG -> when {
        hitId == null -> emptySet()
        hitId in selection -> selection
        else -> setOf(hitId)
    }
    CanvasOperationMode.SELECT_THEN_DRAG -> selection
}

/** Keeps an existing group intact while the pointer may still become a drag. */
fun canvasPressSelection(current: LayerSelection, id: String, order: List<String>, ctrl: Boolean, shift: Boolean): LayerSelection {
    if (!ctrl && !shift && id in current.selected) return current
    val anchor = current.anchor?.takeIf { it in order }
    return if (shift && anchor != null) {
        val a = order.indexOf(anchor)
        val b = order.indexOf(id)
        val range = order.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
        LayerSelection(if (ctrl) current.selected + range else range, id, anchor)
    } else if (ctrl) {
        val selected = if (id in current.selected) current.selected - id else current.selected + id
        LayerSelection(selected, if (id in selected) id else order.lastOrNull { it in selected }, id)
    } else LayerSelection(setOf(id), id, id)
}

enum class CanvasWheelAction { ZOOM_WORKSPACE, SCALE_SELECTED_LAYER, IGNORE }

enum class CanvasDragButton { PRIMARY, SECONDARY, TERTIARY }

fun centeredWorkspaceZoom(state: WorkspaceViewState, currentScale: Double, scrollDelta: Double): WorkspaceViewState {
    val zoom = (currentScale * kotlin.math.exp(-scrollDelta * .14)).coerceIn(.05, 16.0)
    return state.copy(fit = false, zoom = zoom)
}

fun canvasWheelAction(hitId: String?, selectedIds: Set<String>, ctrl: Boolean, interactive: Boolean, gestureActive: Boolean, scaleSelectedLayer: Boolean = true): CanvasWheelAction = when {
    interactive || gestureActive -> CanvasWheelAction.IGNORE
    ctrl || !scaleSelectedLayer -> CanvasWheelAction.ZOOM_WORKSPACE
    hitId != null && hitId in selectedIds -> CanvasWheelAction.SCALE_SELECTED_LAYER
    else -> CanvasWheelAction.ZOOM_WORKSPACE
}

fun canvasDragPans(
    button: CanvasDragButton,
    mode: CanvasOperationMode,
    selection: Set<String>,
    hitId: String? = null,
    middleButtonPanEnabled: Boolean = true,
    rightButtonPanEnabled: Boolean = true,
    spaceDown: Boolean = false,
    ctrlPrimary: Boolean = false,
): Boolean = when (button) {
    CanvasDragButton.TERTIARY -> middleButtonPanEnabled
    CanvasDragButton.SECONDARY -> rightButtonPanEnabled
    CanvasDragButton.PRIMARY -> spaceDown || ctrlPrimary || (mode == CanvasOperationMode.SELECT_THEN_DRAG && selection.isEmpty() && hitId == null)
}

fun isCanvasPanGesture(tertiaryPressed: Boolean, spaceDown: Boolean, ctrlPrimary: Boolean): Boolean =
    (tertiaryPressed || spaceDown) && !ctrlPrimary

fun transformHandleCursor(handle: TransformHandle): Int = when (handle) {
    TransformHandle.TOP_LEFT -> Cursor.NW_RESIZE_CURSOR
    TransformHandle.TOP_RIGHT -> Cursor.NE_RESIZE_CURSOR
    TransformHandle.BOTTOM_RIGHT -> Cursor.SE_RESIZE_CURSOR
    TransformHandle.BOTTOM_LEFT -> Cursor.SW_RESIZE_CURSOR
    TransformHandle.TOP -> Cursor.N_RESIZE_CURSOR
    TransformHandle.BOTTOM -> Cursor.S_RESIZE_CURSOR
    TransformHandle.LEFT -> Cursor.W_RESIZE_CURSOR
    TransformHandle.RIGHT -> Cursor.E_RESIZE_CURSOR
    TransformHandle.ROTATE -> Cursor.CROSSHAIR_CURSOR
}

fun changedLayerAttributes(
    updates: Map<String, Map<String, String>>,
    current: Map<String, Map<String, String>>,
): Map<String, Map<String, String>> = updates.mapValues { (id, values) ->
    values.filter { (key, value) -> current[id]?.get(key) != value }
}.filterValues { it.isNotEmpty() }

/**
 * Keeps the release frame installed while the document command is in flight.
 * A newer document revision owns the same values and can take over without an
 * intermediate frame drawn from the old XML.
 */
data class LiveEditHandoff(
    val values: Map<String, Map<String, String>> = emptyMap(),
    val waitingForRevisionAfter: Long? = null,
) {
    val pending: Boolean get() = waitingForRevisionAfter != null
    fun update(next: Map<String, Map<String, String>>) = copy(values = next, waitingForRevisionAfter = null)
    fun release(baseRevision: Long) = if (values.isEmpty()) this else copy(waitingForRevisionAfter = baseRevision)
    fun observeDocument(revision: Long, attributes: Map<String, Map<String, String>>): LiveEditHandoff {
        val after = waitingForRevisionAfter ?: return this
        val installed = values.all { (id, expected) -> expected.all { (key, value) -> attributes[id]?.get(key) == value } }
        return if (revision > after && installed) LiveEditHandoff() else this
    }
    fun cancel() = LiveEditHandoff()
}

fun addEditorDelta(raw: String, delta: Double): String {
    require(delta.isFinite())
    val source = raw.ifBlank { "0" }
    if (delta == 0.0) return source
    val numeric = source.toDoubleOrNull()
    return if (numeric?.isFinite() == true) editorNumber(numeric + delta) else "($source)+(${editorNumber(delta)})"
}

fun multiplyEditorValue(raw: String, factor: Double): String {
    require(factor.isFinite() && factor > 0.0)
    val source = raw.ifBlank { "100" }
    if (factor == 1.0) return source
    val numeric = source.toDoubleOrNull()
    return if (numeric?.isFinite() == true) editorNumber(numeric * factor) else "($source)*(${editorNumber(factor)})"
}

private fun editorNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

enum class TransformHandle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, ROTATE }

data class SelectionFrame(
    val center: Point,
    val axisX: Point,
    val axisY: Point,
    val halfWidth: Double,
    val halfHeight: Double,
) {
    fun toLocal(point: Point): Point {
        val dx = point.x - center.x
        val dy = point.y - center.y
        val determinant = axisX.x * axisY.y - axisY.x * axisX.y
        require(abs(determinant) > 1e-8)
        return Point((dx * axisY.y - dy * axisY.x) / determinant, (axisX.x * dy - axisX.y * dx) / determinant)
    }

    fun toWorld(point: Point) = Point(
        center.x + point.x * axisX.x + point.y * axisY.x,
        center.y + point.x * axisX.y + point.y * axisY.y,
    )

    fun handle(handle: TransformHandle, rotateOffset: Double = 26.0): Point = when (handle) {
        TransformHandle.TOP_LEFT -> toWorld(Point(-halfWidth, -halfHeight))
        TransformHandle.TOP -> toWorld(Point(0.0, -halfHeight))
        TransformHandle.TOP_RIGHT -> toWorld(Point(halfWidth, -halfHeight))
        TransformHandle.RIGHT -> toWorld(Point(halfWidth, 0.0))
        TransformHandle.BOTTOM_RIGHT -> toWorld(Point(halfWidth, halfHeight))
        TransformHandle.BOTTOM -> toWorld(Point(0.0, halfHeight))
        TransformHandle.BOTTOM_LEFT -> toWorld(Point(-halfWidth, halfHeight))
        TransformHandle.LEFT -> toWorld(Point(-halfWidth, 0.0))
        TransformHandle.ROTATE -> toWorld(Point(0.0, -halfHeight - rotateOffset))
    }

    companion object {
        /** Corners are top-left, top-right, bottom-right, bottom-left. */
        fun fromCorners(corners: List<Point>): SelectionFrame {
            require(corners.size == 4)
            val center = Point(corners.sumOf { it.x } / 4.0, corners.sumOf { it.y } / 4.0)
            val x = Point(corners[1].x - corners[0].x, corners[1].y - corners[0].y)
            val y = Point(corners[3].x - corners[0].x, corners[3].y - corners[0].y)
            val width = hypot(x.x, x.y)
            val height = hypot(y.x, y.y)
            require(width > 1e-6 && height > 1e-6)
            val axisX = Point(x.x / width, x.y / width)
            val axisY = Point(y.x / height, y.y / height)
            require(abs(axisX.x * axisY.y - axisY.x * axisX.y) > 1e-6)
            return SelectionFrame(center, axisX, axisY, width / 2.0, height / 2.0)
        }

        fun axisAligned(points: List<Point>): SelectionFrame {
            require(points.isNotEmpty())
            val left = points.minOf { it.x }
            val right = points.maxOf { it.x }
            val top = points.minOf { it.y }
            val bottom = points.maxOf { it.y }
            return SelectionFrame(Point((left + right) / 2, (top + bottom) / 2), Point(1.0, 0.0), Point(0.0, 1.0),
                ((right - left) / 2).coerceAtLeast(.5), ((bottom - top) / 2).coerceAtLeast(.5))
        }
    }
}

data class HandleTransformDelta(val scaleX: Double = 1.0, val scaleY: Double = 1.0, val rotationDegrees: Double = 0.0)

fun transformHandleDelta(frame: SelectionFrame, handle: TransformHandle, start: Point, current: Point): HandleTransformDelta {
    if (handle == TransformHandle.ROTATE) return HandleTransformDelta(rotationDegrees = rotationHandleDelta(frame, start, current, snap = false, forceSnap = false))
    val a = frame.toLocal(start)
    val b = frame.toLocal(current)
    fun ratio(value: Double, initial: Double) = (abs(value) / abs(initial).coerceAtLeast(.5)).coerceIn(.01, 20.0)
    return when (handle) {
        TransformHandle.TOP_LEFT, TransformHandle.TOP_RIGHT, TransformHandle.BOTTOM_RIGHT, TransformHandle.BOTTOM_LEFT -> {
            val factor = (hypot(b.x, b.y) / hypot(a.x, a.y).coerceAtLeast(.5)).coerceIn(.01, 20.0)
            HandleTransformDelta(factor, factor)
        }
        TransformHandle.LEFT, TransformHandle.RIGHT -> HandleTransformDelta(scaleX = ratio(b.x, a.x))
        TransformHandle.TOP, TransformHandle.BOTTOM -> HandleTransformDelta(scaleY = ratio(b.y, a.y))
        TransformHandle.ROTATE -> error("handled above")
    }
}

fun rotationHandleDelta(frame: SelectionFrame, start: Point, current: Point, snap: Boolean, forceSnap: Boolean): Double {
    val initial = atan2(start.y - frame.center.y, start.x - frame.center.x)
    val latest = atan2(current.y - frame.center.y, current.x - frame.center.x)
    var degrees = Math.toDegrees(latest - initial)
    while (degrees <= -180.0) degrees += 360.0
    while (degrees > 180.0) degrees -= 360.0
    val initialOrientation = Math.toDegrees(atan2(frame.axisX.y, frame.axisX.x))
    val finalOrientation = initialOrientation + degrees
    val snappedFinal = round(finalOrientation / 45.0) * 45.0
    return if (forceSnap || (snap && abs(finalOrientation - snappedFinal) <= 3.0)) snappedFinal - initialOrientation else degrees
}
