package com.ailife.wftools.pc.preview

import org.junit.Assert.*
import org.junit.Test
import com.ailife.wftools.pc.core.LayerSelection
import java.awt.Cursor

class CanvasEditingTest {
    @Test fun `direct drag captures the layer under the pointer instead of the old selection`() {
        assertEquals(setOf("other"), canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, "other", setOf("old")))
        assertEquals(setOf("a", "b"), canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, "b", setOf("a", "b")))
        assertTrue(canvasDragTargets(CanvasOperationMode.DIRECT_DRAG, null, setOf("old")).isEmpty())
    }

    @Test fun `pressing inside a multi selection keeps the group until click release`() {
        val current = LayerSelection(setOf("a", "b"), "a", "a")
        assertEquals(current, canvasPressSelection(current, "b", listOf("a", "b", "c"), ctrl = false, shift = false))
        assertEquals(setOf("c"), canvasPressSelection(current, "c", listOf("a", "b", "c"), ctrl = false, shift = false).selected)
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
        assertFalse(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.SELECT_THEN_DRAG,setOf("layer")))
        assertFalse(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.DIRECT_DRAG,emptySet()))
        assertTrue(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),spaceDown=true))
        assertTrue(canvasDragPans(CanvasDragButton.PRIMARY,CanvasOperationMode.DIRECT_DRAG,setOf("layer"),ctrlPrimary=true))
    }
}
