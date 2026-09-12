package com.ailife.wftools.pc.preview

import org.junit.Assert.*
import org.junit.Test

class ViewportTest {
    @Test fun `Flue default fill crops square faces without changing design extent`() {
        val design = DesignExtent.fromAttributes(emptyMap())
        assertEquals(DesignExtent(512.0, 512.0), design)
        listOf(Triple(480, 480, 0.0), Triple(320, 386, -33.0), Triple(368, 448, -40.0), Triple(410, 502, -46.0)).forEach { (w,h,left) ->
            val transform = ViewportTransform.create(design, DevicePreviewSettings(w,h))
            assertEquals(left, transform.left, 0.00001)
            assertEquals(0.0, transform.top, 0.00001)
            assertEquals(w / 2.0, transform.designToDevice(Point(0.0,0.0)).x, 0.00001)
            assertEquals(h / 2.0, transform.designToDevice(Point(0.0,0.0)).y, 0.00001)
        }
    }
    @Test fun `desktop editing space remains square regardless of archived root dimensions`() {
        assertEquals(DesignExtent(512.0,512.0), DesignExtent.fromAttributes(mapOf("width" to "480","height" to "480")))
        assertEquals(DesignExtent(512.0,512.0), DesignExtent.fromAttributes(mapOf("width" to "368", "height" to "448")))
        assertEquals(DesignExtent(512.0,512.0), DesignExtent.fromAttributes(mapOf("width" to "bad", "height" to "2")))
    }
    @Test fun `hit coordinates invert runtime and DPI transforms in every preset`() {
        for(preset in CanvasPreset.entries) for(dpi in listOf(1.0,1.25,1.5,2.0)) {
            val device = DevicePreviewSettings(320,386,preset=preset,zoomX=1.25,zoomY=0.8,offsetX=17.0,offsetY=-9.0)
            val viewport = ViewportTransform.create(DesignExtent(512.0,623.3),device)
            val workspace = WorkspaceTransform.create(800*dpi,600*dpi,device,WorkspaceViewState(false,1.7,22.0,-31.0))
            for(p in listOf(Point(0.0,0.0),Point(-178.4,202.6),Point(32.125,-35.5))) {
                val actual = viewport.deviceToDesign(workspace.toDevice(workspace.toWorkspace(viewport.designToDevice(p))))
                assertEquals(p.x,actual.x,0.000001); assertEquals(p.y,actual.y,0.000001)
            }
        }
    }
    @Test fun `fit and stretch match Flue contracts`() {
        val fit = ViewportTransform.create(DesignExtent(512.0,512.0),DevicePreviewSettings(320,386,preset=CanvasPreset.FIT))
        assertEquals(33.0,fit.top,0.00001)
        val stretch = ViewportTransform.create(DesignExtent(512.0,512.0),DevicePreviewSettings(320,386,preset=CanvasPreset.STRETCH))
        assertEquals(320/512.0,stretch.scaleX,0.00001)
        assertEquals(386/512.0,stretch.scaleY,0.00001)
    }
    @Test fun `invalid runtime zoom cannot make hit matrix singular`() {
        val actual = ViewportTransform.create(DesignExtent(512.0,512.0),DevicePreviewSettings(zoomX=Double.NaN,zoomY=0.0,offsetX=Double.POSITIVE_INFINITY))
        assertTrue(actual.scaleX.isFinite() && actual.scaleX > 0)
        assertTrue(actual.scaleY.isFinite() && actual.scaleY > 0)
        assertTrue(actual.left.isFinite())
    }
    @Test fun `clipped design and fit letterbox cannot receive drops or clicks`() {
        val fitDevice=DevicePreviewSettings(320,386,preset=CanvasPreset.FIT,shape=ScreenShape.RECTANGLE)
        val transform=ViewportTransform.create(DesignExtent(512.0,512.0),fitDevice)
        assertFalse(transform.containsDevice(Point(160.0,10.0),fitDevice,false))
        assertTrue(transform.containsDevice(Point(160.0,193.0),fitDevice,false))
        val shifted=fitDevice.copy(offsetX=100.0)
        assertFalse(ViewportTransform.create(transform.design,shifted).containsDevice(Point(20.0,193.0),shifted,false))
    }
    @Test fun `round mask includes the original design circle after unequal scales`() {
        val device=DevicePreviewSettings(480,480,preset=CanvasPreset.STRETCH,zoomX=.5,zoomY=1.0)
        val transform=ViewportTransform.create(DesignExtent(512.0,512.0),device)
        assertTrue(transform.containsDevice(Point(240.0,240.0),device,true))
        assertFalse(transform.containsDevice(Point(370.0,240.0),device,true))
        assertTrue(ScreenShape.DOCUMENT.isRound("Circular"))
    }
}
