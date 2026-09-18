package com.ailife.wftools.pc.preview

import org.junit.Assert.*
import org.junit.Test
import com.ailife.wftools.pc.core.LayerSelection
import com.ailife.wftools.pc.input.TouchTargets
import com.ailife.wftools.pc.input.isLongPress
import java.awt.Cursor

class CanvasEditingTest {
    @Test fun `direct drag keeps the sidebar selection instead of stealing the layer under the pointer`() {
        assertEquals(setOf("selected"), canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, "other", setOf("selected")))
    }

    @Test fun `direct drag follows the selection even when pressing empty canvas`() {
        assertEquals(setOf("selected"), canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, null, setOf("selected")))
    }

    @Test fun `direct drag adopts the pointer layer only while nothing is selected`() {
        assertEquals(setOf("first"), canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, "first", emptySet()))
        assertTrue(canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, null, emptySet()).isEmpty())
    }

    @Test fun `a press adopts the hit layer only in direct drag with an empty selection`() {
        assertTrue(canvasPressAdoptsHit(CanvasOperationMode.DIRECT_DRAG, emptySet()))
        assertFalse(canvasPressAdoptsHit(CanvasOperationMode.DIRECT_DRAG, setOf("selected")))
        assertFalse(canvasPressAdoptsHit(CanvasOperationMode.SELECT_THEN_DRAG, emptySet()))
    }

    @Test fun `pressing inside a multi selection keeps the group until click release`() {
        val current = LayerSelection(setOf("a", "b"), "a", "a")
        assertEquals(current, canvasPressSelection(current, "b", listOf("a", "b", "c"), ctrl = false, shift = false))
        assertEquals(setOf("c"), canvasPressSelection(current, "c", listOf("a", "b", "c"), ctrl = false, shift = false).selected)
    }

    @Test fun `direct drag preserves the whole group wherever the pointer lands`() {
        assertEquals(setOf("a", "b"), canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, "b", setOf("a", "b")))
        assertEquals(setOf("a", "b"), canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, "c", setOf("a", "b")))
    }

    @Test fun `empty and already persisted live attributes are removed before handoff`() {
        val actual = changedLayerAttributes(
            mapOf("a" to emptyMap(), "b" to mapOf("x" to "10", "y" to "4")),
            mapOf("a" to mapOf("x" to "0"), "b" to mapOf("x" to "10", "y" to "3")),
        )
        assertEquals(mapOf("b" to mapOf("y" to "4")), actual)
    }

    @Test fun `select then drag moves the current selection from any canvas point`() {
        assertEquals(setOf("selected"), canvasDragTargets(CanvasOperationMode.SELECT_THEN_DRAG, "other", setOf("selected")))
        assertEquals(setOf("a", "b"), canvasDragTargets(CanvasOperationMode.SELECT_THEN_DRAG, null, setOf("a", "b")))
        assertTrue(canvasDragTargets(CanvasOperationMode.SELECT_THEN_DRAG, "first", emptySet()).isEmpty())
    }

    @Test fun `released live edit remains visible until a newer document revision takes over`() {
        val values = mapOf("layer" to mapOf("x" to "25", "y" to "10"))
        val dragging = LiveEditHandoff().update(values)
        val released = dragging.release(baseRevision = 7)
        assertEquals(values, released.values)
        assertEquals(values, released.observeDocument(7,mapOf("layer" to mapOf("x" to "0", "y" to "0"))).values)
        assertEquals("An unrelated queued edit must not clear this gesture",values,
            released.observeDocument(8,mapOf("layer" to mapOf("x" to "0", "y" to "0"))).values)
        assertTrue(released.observeDocument(9,mapOf("layer" to mapOf("x" to "25", "y" to "10"))).values.isEmpty())
        assertTrue(released.cancel().values.isEmpty())
    }

    @Test fun `incremental transform expressions retain dynamic source expressions`() {
        assertEquals("12.5", addEditorDelta("10", 2.5))
        assertEquals("150", multiplyEditorValue("100", 1.5))
        assertTrue(addEditorDelta("{dh}+3", 2.5).contains("{dh}+3"))
        assertTrue(multiplyEditorValue("{blp}", 1.5).contains("{blp}"))
    }

    @Test fun `corner and edge handles use the selection local axes`() {
        val frame = SelectionFrame.fromCorners(
            listOf(Point(-50.0, -25.0), Point(50.0, -25.0), Point(50.0, 25.0), Point(-50.0, 25.0))
        )
        val corner = transformHandleDelta(frame, TransformHandle.BOTTOM_RIGHT, Point(50.0, 25.0), Point(75.0, 37.5))
        assertEquals(1.5, corner.scaleX, 0.000001)
        assertEquals(1.5, corner.scaleY, 0.000001)

        val right = transformHandleDelta(frame, TransformHandle.RIGHT, Point(50.0, 0.0), Point(75.0, 20.0))
        assertEquals(1.5, right.scaleX, 0.000001)
        assertEquals(1.0, right.scaleY, 0.000001)
    }

    @Test fun `selection frame local conversion remains invertible after skew`() {
        val frame = SelectionFrame.fromCorners(
            listOf(Point(-75.0,-25.0),Point(25.0,-25.0),Point(75.0,25.0),Point(-25.0,25.0))
        )
        val point = Point(10.0,20.0)
        val roundTrip = frame.toWorld(frame.toLocal(point))
        assertEquals(point.x,roundTrip.x,0.000001); assertEquals(point.y,roundTrip.y,0.000001)
    }

    @Test fun `rotation handle snaps near forty five degrees unless alt disables snapping`() {
        val frame = SelectionFrame.fromCorners(
            listOf(Point(-50.0, -25.0), Point(50.0, -25.0), Point(50.0, 25.0), Point(-50.0, 25.0))
        )
        val almost = Math.toRadians(43.5)
        val current = Point(kotlin.math.sin(almost) * 60.0, -kotlin.math.cos(almost) * 60.0)
        assertEquals(45.0, rotationHandleDelta(frame, Point(0.0, -60.0), current, snap = true, forceSnap = false), 0.000001)
        assertEquals(43.5, rotationHandleDelta(frame, Point(0.0, -60.0), current, snap = false, forceSnap = false), 0.000001)
    }


    @Test fun `rotation snapping uses final orientation rather than only the gesture delta`() {
        fun turn(point: Point, degrees: Double): Point {
            val angle = Math.toRadians(degrees)
            return Point(point.x*kotlin.math.cos(angle)-point.y*kotlin.math.sin(angle),point.x*kotlin.math.sin(angle)+point.y*kotlin.math.cos(angle))
        }
        val frame = SelectionFrame.fromCorners(listOf(Point(-50.0,-25.0),Point(50.0,-25.0),Point(50.0,25.0),Point(-50.0,25.0)).map { turn(it,30.0) })
        val start = frame.handle(TransformHandle.ROTATE,35.0)
        val current = turn(start,13.5)
        assertEquals("30 + 13.5 degrees should land on the 45-degree guide",15.0,
            rotationHandleDelta(frame,start,current,snap=true,forceSnap=false),0.000001)
    }

    @Test fun `blank canvas wheel zooms workspace while selected layer wheel scales only a selected hit`() {
        assertEquals(CanvasWheelAction.ZOOM_WORKSPACE,canvasWheelAction(hitId=null,selectedIds=emptySet(),ctrl=false,interactive=false,gestureActive=false))
        assertEquals(CanvasWheelAction.ZOOM_WORKSPACE,canvasWheelAction(hitId="other",selectedIds=setOf("selected"),ctrl=false,interactive=false,gestureActive=false))
        assertEquals(CanvasWheelAction.SCALE_SELECTED_LAYER,canvasWheelAction(hitId="selected",selectedIds=setOf("selected"),ctrl=false,interactive=false,gestureActive=false))
        assertEquals(CanvasWheelAction.ZOOM_WORKSPACE,canvasWheelAction(hitId="selected",selectedIds=setOf("selected"),ctrl=true,interactive=false,gestureActive=false))
        assertEquals(CanvasWheelAction.IGNORE,canvasWheelAction(hitId=null,selectedIds=emptySet(),ctrl=false,interactive=true,gestureActive=false))
    }

    @Test fun `text transform handles update font size instead of bitmap scaling percentages`() {
        val frame = SelectionFrame.axisAligned(listOf(Point(-50.0, -20.0), Point(50.0, 20.0)))
        val layer = com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceLayer(
            "text", linkedMapOf("text" to "Hello", "text_size" to "40", "anim_scale_x" to "100", "anim_scale_y" to "100")
        )
        val updates = textTransformAttributes(layer, HandleTransformDelta(scaleX = 1.5, scaleY = 1.5))
        assertEquals("60", updates["text_size"])
        assertFalse(updates.containsKey("anim_scale_x"))
        assertFalse(updates.containsKey("anim_scale_y"))
    }

    @Test fun `wheel layer scaling switch sends every wheel gesture to workspace zoom when disabled`() {
        assertEquals(CanvasWheelAction.ZOOM_WORKSPACE,canvasWheelAction(
            hitId="selected",selectedIds=setOf("selected"),ctrl=false,interactive=false,gestureActive=false,
            scaleSelectedLayer=false,
        ))
        assertEquals(CanvasWheelAction.SCALE_SELECTED_LAYER,canvasWheelAction(
            hitId="selected",selectedIds=setOf("selected"),ctrl=false,interactive=false,gestureActive=false,
            scaleSelectedLayer=true,
        ))
    }

    @Test fun `wheel zoom changes the whole workspace around its center`() {
        val state = centeredWorkspaceZoom(WorkspaceViewState(true,1.0,18.0,-9.0), currentScale = 1.0, scrollDelta = -1.0)
        assertFalse(state.fit)
        assertTrue(state.zoom > 1.0)
        assertEquals(18.0,state.panX,0.000001)
        assertEquals(-9.0,state.panY,0.000001)
    }

    @Test fun `middle button and space are pan gestures and handles expose matching cursors`() {
        assertTrue(isCanvasPanGesture(tertiaryPressed=true,spaceDown=false,ctrlPrimary=false))
        assertTrue(isCanvasPanGesture(tertiaryPressed=false,spaceDown=true,ctrlPrimary=false))
        assertFalse(isCanvasPanGesture(tertiaryPressed=false,spaceDown=false,ctrlPrimary=true))
        assertEquals(Cursor.NW_RESIZE_CURSOR,transformHandleCursor(TransformHandle.TOP_LEFT))
        assertEquals(Cursor.SE_RESIZE_CURSOR,transformHandleCursor(TransformHandle.BOTTOM_RIGHT))
        assertEquals(Cursor.NE_RESIZE_CURSOR,transformHandleCursor(TransformHandle.TOP_RIGHT))
        assertEquals(Cursor.SW_RESIZE_CURSOR,transformHandleCursor(TransformHandle.BOTTOM_LEFT))
        assertEquals(Cursor.N_RESIZE_CURSOR,transformHandleCursor(TransformHandle.TOP))
        assertEquals(Cursor.S_RESIZE_CURSOR,transformHandleCursor(TransformHandle.BOTTOM))
        assertEquals(Cursor.W_RESIZE_CURSOR,transformHandleCursor(TransformHandle.LEFT))
        assertEquals(Cursor.E_RESIZE_CURSOR,transformHandleCursor(TransformHandle.RIGHT))
        assertEquals(Cursor.CROSSHAIR_CURSOR,transformHandleCursor(TransformHandle.ROTATE))
    }


    @Test fun `canvas drag panning honours independent middle and right switches`() {
        assertTrue(canvasDragPans(CanvasDragButton.TERTIARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),middleButtonPanEnabled=true,rightButtonPanEnabled=false))
        assertFalse(canvasDragPans(CanvasDragButton.TERTIARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),middleButtonPanEnabled=false,rightButtonPanEnabled=true))
        assertTrue(canvasDragPans(CanvasDragButton.SECONDARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),middleButtonPanEnabled=false,rightButtonPanEnabled=true))
        assertFalse(canvasDragPans(CanvasDragButton.SECONDARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),middleButtonPanEnabled=true,rightButtonPanEnabled=false))
    }

    @Test fun `select then drag uses empty selection as a primary workspace pan gesture`() {
        assertTrue(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.SELECT_THEN_DRAG,emptySet()))
        assertFalse(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.SELECT_THEN_DRAG,emptySet(),hitId="first"))
        assertFalse(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.SELECT_THEN_DRAG,setOf("layer")))
        assertFalse(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.DIRECT_DRAG,emptySet()))
        assertTrue(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),spaceDown=true))
        assertTrue(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),ctrlPrimary=true))
    }

    @Test fun `a still touch hold becomes the context menu because touch has no right button`() {
        val slop = touchHoldSlopDp(touch = true)
        assertTrue("长按容差必须为正，否则手指抖动会让长按永不成立", slop > 0f)
        assertEquals(
            TouchGestureIntent.CONTEXT_MENU,
            touchHoldIntent(TouchTargets.LONG_PRESS_MILLIS, 0f, slop),
        )
        assertEquals(
            "刚好到阈值也应成立",
            TouchGestureIntent.CONTEXT_MENU,
            touchHoldIntent(TouchTargets.LONG_PRESS_MILLIS + 200, slop, slop),
        )
    }

    @Test fun `a touch hold that moves becomes a pan instead of a menu`() {
        val slop = touchHoldSlopDp(touch = true)
        assertEquals(TouchGestureIntent.MOVE, touchHoldIntent(TouchTargets.LONG_PRESS_MILLIS + 500, slop + 1f, slop))
        assertEquals("移动后不再有长按", TouchGestureIntent.MOVE, touchHoldIntent(50, slop + 1f, slop))
    }

    @Test fun `a short still touch is neither a menu nor a pan yet`() {
        val slop = touchHoldSlopDp(touch = true)
        assertEquals(TouchGestureIntent.NONE, touchHoldIntent(120, 0f, slop))
        assertEquals(TouchGestureIntent.NONE, touchHoldIntent(TouchTargets.LONG_PRESS_MILLIS - 1, 0f, slop))
    }

    @Test fun `the hold intent stays consistent with the shared long press contract`() {
        val slop = touchHoldSlopDp(touch = true)
        listOf(0L, 100L, 499L, 500L, 800L).forEach { elapsed ->
            listOf(0f, slop, slop + 0.5f).forEach { movement ->
                val longPress = isLongPress(elapsed, movement, slop)
                assertEquals(
                    "elapsed=$elapsed movement=$movement 时两者必须一致",
                    longPress,
                    touchHoldIntent(elapsed, movement, slop) == TouchGestureIntent.CONTEXT_MENU,
                )
            }
        }
    }

    @Test fun `a long press outranks a double tap so the two cannot swallow each other`() {
        val slop = touchHoldSlopDp(touch = true)
        assertEquals(
            "长按成立时优先是菜单，不能变成文字编辑",
            TouchGestureIntent.CONTEXT_MENU,
            touchHoldIntent(TouchTargets.LONG_PRESS_MILLIS, 0f, slop, doubleTapCandidate = true),
        )
        assertEquals(
            "没到长按时，双击候选才生效",
            TouchGestureIntent.TEXT_EDIT,
            touchHoldIntent(120, 0f, slop, doubleTapCandidate = true),
        )
    }

    @Test fun `text edit double tap needs the same target inside the window and a close landing`() {
        val slop = doubleTapSlopDp(touch = true)
        assertTrue(isTextEditDoubleTap(true, 200, 0f, slop))
        assertFalse("不同目标不是双击", isTextEditDoubleTap(false, 200, 0f, slop))
        assertFalse("超出时间窗不是双击", isTextEditDoubleTap(true, DOUBLE_TAP_WINDOW_MILLIS + 1, 0f, slop))
        assertFalse("落点太远不是双击", isTextEditDoubleTap(true, 200, slop + 1f, slop))
        assertFalse("同一时刻的重复事件不算双击", isTextEditDoubleTap(true, 0, 0f, slop))
    }

    @Test fun `touch gets larger handles and a slacker double tap than the mouse`() {
        assertTrue(transformHandleHitRadiusDp(true) > transformHandleHitRadiusDp(false))
        assertTrue(doubleTapSlopDp(true) > doubleTapSlopDp(false))
        assertEquals("触屏命中半径应按 44dp 目标放宽", TouchTargets.MIN_TOUCH_DP / 2f, transformHandleHitRadiusDp(true))
    }

    @Test fun `the mouse keeps its previous handle and double tap tolerances`() {
        assertEquals(12f, transformHandleHitRadiusDp(false))
        assertEquals("鼠标双击落点容差保持原值", 12f, doubleTapSlopDp(false))
    }

    @Test fun `the context menu probe starts at the finger and stays inside its slop`() {
        val probes = contextMenuHitProbes(10f)
        assertEquals("落点本身必须第一个被取样", Point(0.0, 0.0), probes.first())
        assertEquals(5, probes.size)
        assertEquals(5, probes.distinct().size)
        probes.forEach { probe ->
            assertTrue("探针不能超出容差", kotlin.math.abs(probe.x) <= 10.0 && kotlin.math.abs(probe.y) <= 10.0)
        }
        assertTrue("必须包含邻近取样点，细图层才选得中", probes.any { it != Point(0.0, 0.0) })
        assertEquals("鼠标不需要落点补偿", 0f, contextMenuHitSlopDp(touch = false))
        assertTrue(contextMenuHitSlopDp(touch = true) > 0f)
    }
}
