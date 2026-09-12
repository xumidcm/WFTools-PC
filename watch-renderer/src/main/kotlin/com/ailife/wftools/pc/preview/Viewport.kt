package com.ailife.wftools.pc.preview

enum class CanvasPreset { FIT, FILL, VERTICAL, HORIZONTAL, STRETCH, CUSTOM }
enum class ScreenShape { DOCUMENT, ROUND, RECTANGLE }
enum class CanvasOperationMode { DIRECT_DRAG, SELECT_THEN_DRAG }
fun ScreenShape.isRound(documentShape:String)=when(this) {
    ScreenShape.ROUND->true
    ScreenShape.RECTANGLE->false
    ScreenShape.DOCUMENT->documentShape.trim().lowercase() in setOf("r","round","circle","circular")
}
data class Point(val x: Double, val y: Double)
data class DesignExtent(val width: Double, val height: Double) {
    companion object {
        /** WatchMaker editing coordinates are always the legacy 512 square. */
        fun fromAttributes(@Suppress("UNUSED_PARAMETER") attrs: Map<String, String>) = DesignExtent(512.0, 512.0)
    }
}
data class DevicePreviewSettings(
    val width: Int = 480, val height: Int = 480,
    val shape: ScreenShape = ScreenShape.DOCUMENT,
    val preset: CanvasPreset = CanvasPreset.FILL,
    val zoomX: Double = 1.0, val zoomY: Double = 1.0,
    val offsetX: Double = 0.0, val offsetY: Double = 0.0,
) {
    init { require(width in 64..2048 && height in 64..2048) }
    companion object { val presets = listOf(480 to 480, 320 to 386, 368 to 448, 410 to 502) }
}
data class ViewportTransform(
    val scaleX: Double, val scaleY: Double, val left: Double, val top: Double,
    val design: DesignExtent,
) {
    fun designToDevice(p: Point) = Point(left+(p.x+design.width/2)*scaleX,top+(p.y+design.height/2)*scaleY)
    fun deviceToDesign(p: Point) = Point((p.x-left)/scaleX-design.width/2,(p.y-top)/scaleY-design.height/2)
    fun containsDevice(p:Point,device:DevicePreviewSettings,round:Boolean):Boolean {
        if(p.x !in 0.0..device.width.toDouble() || p.y !in 0.0..device.height.toDouble())return false
        if(round && kotlin.math.hypot(p.x-device.width/2.0,p.y-device.height/2.0)>minOf(device.width,device.height)/2.0)return false
        val original=deviceToDesign(p)
        return kotlin.math.abs(original.x)<=design.width/2 && kotlin.math.abs(original.y)<=design.height/2 &&
            (!round || kotlin.math.hypot(original.x,original.y)<=minOf(design.width,design.height)/2)
    }
    companion object {
        fun create(design: DesignExtent, device: DevicePreviewSettings): ViewportTransform {
            require(design.width.isFinite() && design.width > 0 && design.height.isFinite() && design.height > 0)
            val fitX=device.width/design.width;val fitY=device.height/design.height
            val (baseX,baseY)=when(device.preset) {
                CanvasPreset.FIT -> minOf(fitX,fitY).let { it to it }
                CanvasPreset.FILL -> maxOf(fitX,fitY).let { it to it }
                CanvasPreset.VERTICAL -> fitY to fitY
                CanvasPreset.HORIZONTAL -> fitX to fitX
                CanvasPreset.STRETCH -> fitX to fitY
                CanvasPreset.CUSTOM -> 1.0 to 1.0
            }
            fun zoom(v:Double)=if(v.isFinite())v.coerceIn(.01,20.0) else 1.0
            fun offset(v:Double)=if(v.isFinite())v.coerceIn(-16384.0,16384.0) else 0.0
            val sx=baseX*zoom(device.zoomX);val sy=baseY*zoom(device.zoomY)
            return ViewportTransform(sx,sy,(device.width-design.width*sx)/2+offset(device.offsetX),(device.height-design.height*sy)/2+offset(device.offsetY),design)
        }
    }
}
data class WorkspaceViewState(
    val fit: Boolean = true, val zoom: Double = 1.0,
    val panX: Double = 0.0, val panY: Double = 0.0,
)
data class WorkspaceTransform(val scale: Double, val left: Double, val top: Double) {
    fun toDevice(p: Point) = Point((p.x - left) / scale, (p.y - top) / scale)
    fun toWorkspace(p: Point) = Point(p.x * scale + left, p.y * scale + top)
    companion object {
        fun create(widthPx: Double, heightPx: Double, device: DevicePreviewSettings, state: WorkspaceViewState): WorkspaceTransform {
            val fitScale=minOf((widthPx-48).coerceAtLeast(1.0)/device.width,(heightPx-48).coerceAtLeast(1.0)/device.height)
            val scale=if(state.fit)fitScale else state.zoom.takeIf { it.isFinite() }?.coerceIn(.05,16.0) ?: 1.0
            return WorkspaceTransform(scale,(widthPx-device.width*scale)/2+state.panX,(heightPx-device.height*scale)/2+state.panY)
        }
    }
}
