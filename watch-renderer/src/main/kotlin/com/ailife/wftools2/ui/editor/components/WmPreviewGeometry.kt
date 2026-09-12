package com.ailife.wftools2.ui.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import com.ailife.wftools2.domain.editor.WatchfaceLayer
import com.ailife.wftools2.domain.editor.WatchfaceProject
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceLayer
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import com.ailife.wftools2.domain.editor.watchmaker.WmExpressionEval
import com.ailife.wftools2.domain.editor.watchmaker.WmScriptRuntime
import com.ailife.wftools2.domain.editor.watchmaker.BitmapFont
import androidx.compose.ui.text.style.TextAlign
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.Snapshot
import com.ailife.wftools2.ui.editor.utils.LunarUtils
import com.ailife.wftools2.ui.editor.utils.rememberPreviewBitmap
import com.ailife.wftools2.ui.editor.utils.rememberPreviewBitmaps
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import com.ailife.wftools.pc.graphics.*
import com.ailife.wftools.pc.render.*
import com.ailife.wftools.pc.preview.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.Image

internal fun wmEvalFloat(
    expr: String,
    previewState: WmTagsData.WmPreviewState? = null
): Float {
    // Try direct float first
    expr.toFloatOrNull()?.let { return it }

    // Resolve tags and evaluate math/logic (e.g., {ddw0} -> 3). When a live preview
    // state is available, evaluate through it so script-helper expressions resolve.
    val resolvedExpr = if (previewState != null)
        WmTagsData.evaluatePreview(expr, previewState)
    else
        WmTagsData.evaluatePreview(expr)

    return resolvedExpr.toFloatOrNull() ?: 0f
}

/** Bounds of a WM layer in canvas pixel space (top-left x/y + width/height). */
internal data class WmBounds(val x: Float, val y: Float, val w: Float, val h: Float)

// Parsing the .fnt descriptor is pure file I/O — cache it per file so the (hot) bounds
// computation never re-reads/parses it on every recomposition or gesture event.
private val wmBitmapFontCache = java.util.concurrent.ConcurrentHashMap<String, BitmapFont?>()

/** Loads (and caches) the BitmapFont descriptor for a `bm:` [fontName], or null if absent. */
internal fun wmBitmapFontFor(projectDir: File?, fontName: String): BitmapFont? {
    if (projectDir == null || !fontName.startsWith("bm:")) return null
    val bmName = fontName.substring(3)
    val fontFile = File(File(projectDir, "fonts_bm"), "$bmName.fnt")
    if (!fontFile.exists()) return null
    return wmBitmapFontCache.getOrPut(fontFile.absolutePath) {
        try { BitmapFont.parse(fontFile.readText(Charsets.UTF_8)) } catch (e: Exception) { null }
    }
}

/**
 * Measured on-screen advance of a `bm:` bitmap-text layer, matching the renderer's layout exactly
 * (uppercased glyphs, per-char xadvance + kerning, scale = 1.6×text_size/fontH × anim_scale%).
 * Returns (width, height) or null when the font descriptor can't be resolved.
 */
internal fun wmBitmapTextMetrics(
    layer: WmWatchfaceLayer,
    textStr: String,
    textSizePx: Float,
    projectDir: File?
): Pair<Float, Float>? {
    val bitmapFont = wmBitmapFontFor(projectDir, layer.getAttr("font")) ?: return null
    val fontH = when {
        bitmapFont.size > 0 -> bitmapFont.size.toFloat()
        bitmapFont.lineHeight > 0 -> bitmapFont.lineHeight.toFloat()
        else -> textSizePx
    }
    val baseScale = if (fontH > 0) 1.6f * textSizePx / fontH else 1.6f
    fun animMul(key: String): Float {
        val v = layer.getAttr(key).toFloatOrNull() ?: 100f
        return (if (v == 0f) 100f else v) / 100f
    }
    val scaleX = baseScale * animMul("anim_scale_x")
    val scaleY = baseScale * animMul("anim_scale_y")

    var totalWidth = 0f
    var lastCharId = -1
    textStr.uppercase().forEach { char ->
        val charId = char.code
        val bmChar = bitmapFont.chars[charId] ?: return@forEach
        if (lastCharId != -1) {
            val kerningKey = (lastCharId.toLong() shl 32) or (charId.toLong() and 0xFFFFFFFFL)
            totalWidth += (bitmapFont.kernings[kerningKey] ?: 0) * scaleX
        }
        totalWidth += bmChar.xadvance * scaleX
        lastCharId = charId
    }
    val totalHeight = (bitmapFont.chars.values.firstOrNull()?.height ?: bitmapFont.lineHeight) * scaleY
    return (totalWidth.coerceAtLeast(1f)) to (totalHeight.coerceAtLeast(1f))
}

internal fun wmLayerBounds(
    layer: WmWatchfaceLayer,
    wmCenter: Float,
    previewState: WmTagsData.WmPreviewState? = null,
    projectDir: File? = null,
    includeScale: Boolean = true
): WmBounds {
    val xRaw = layer.getAttr("x", "0")
    val yRaw = layer.getAttr("y", "0")
    val wmX = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(xRaw, it) } ?: xRaw)
    val wmY = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(yRaw, it) } ?: yRaw)

    val alignment = layer.getAttr("alignment").ifEmpty { "cc" }

    // anim_scale may be a live expression (tags / script vars) — evaluate against
    // the preview state when present, exactly like the render's graphicsLayer.
    fun liveScale(key: String): Float {
        val raw = layer.getAttr(key, "100")
        val v = previewState?.let { WmTagsData.evaluatePreview(raw, it) } ?: raw
        return (v.toFloatOrNull() ?: 100f) / 100f
    }

    val layerW: Float
    val layerH: Float
    when (layer.layerType) {
        "text" -> {
            val textRaw = layer.getAttr("text", "T")
            val textStr = previewState?.let { WmTagsData.evaluatePreview(textRaw, it) } ?: WmTagsData.evaluatePreview(textRaw)
            val fontName = layer.getAttr("font")
            val animScaleX = layer.getAttr("anim_scale_x", "0").toFloatOrNull() ?: 0f
            val animScaleY = layer.getAttr("anim_scale_y", "0").toFloatOrNull() ?: 0f
            // WM `text` may be multi-line (raw newlines). Box width = widest line,
            // height = line count × line height — so the anchor stays centered.
            val lines = textStr.split('\n')
            val maxLen = lines.maxOf { it.length }.coerceAtLeast(1)
            val lineCount = lines.size.coerceAtLeast(1)
            val sizeRaw = layer.getAttr("text_size", "24")
            // The renderer uses the raw text_size (no floor) — guard only against degenerate 0.
            val textSizePx = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(sizeRaw, it) } ?: sizeRaw).coerceAtLeast(1f)
            // anim_scale_x/y is a plain percent multiplier (100 = normal). Both bitmap
            // and vector text are sized by text_size; anim_scale is folded into the bitmap
            // box (bm layers are not re-scaled by the renderer) and applied by the layer
            // graphicsLayer scale for vector text (so that box stays unscaled).
            val aX = (if (animScaleX == 0f) 100f else animScaleX) / 100f
            val aY = (if (animScaleY == 0f) 100f else animScaleY) / 100f
            if (fontName.startsWith("bm:")) {
                // Bitmap text width is per-char xadvance (+kerning) at the bm scale, NOT
                // maxLen×size×1.6 — the sprite-sheet metrics are authoritative, so the box
                // hugs the actual glyphs. The box already carries anim_scale (matches the
                // renderer, which does not re-apply scaleX on bm layers), so the final
                // scaleX multiply below is skipped for bm text via `isBmText`.
                val metrics = wmBitmapTextMetrics(layer, textStr, textSizePx, projectDir)
                if (metrics != null) {
                    layerW = metrics.first
                    layerH = metrics.second
                } else {
                    layerW = (maxLen * textSizePx * 1.6f * aX).coerceAtLeast(textSizePx)
                    layerH = lineCount * textSizePx * 1.6f * aY
                }
            } else {
                // Vector text: measure the widest rendered line (the estimate maxLen×0.6 is
                // off for proportional fonts, and the box width anchors non-center alignment).
                val measured = wmVectorTextWidth(textStr, textSizePx, layer.getAttr("transform"), projectDir, fontName)
                layerW = measured
                layerH = lineCount * textSizePx * 1.4f
            }
        }
        "text_curved" -> {
            val rRaw = layer.getAttr("radius", "100")
            val radius = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(rRaw, it) } ?: rRaw)
            val sizeRaw = layer.getAttr("text_size", "24")
            val textSize = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(sizeRaw, it) } ?: sizeRaw)
            val curveDir = layer.getAttr("curve_dir", "Up")
            layerW = (radius + textSize) * 2f
            layerH = radius + textSize

            val anchorPxX = wmX + wmCenter
            val anchorPxY = wmY + wmCenter

            val scaleX = if (includeScale) liveScale("anim_scale_x") else 1f
            val scaleY = if (includeScale) liveScale("anim_scale_y") else 1f
            val finalW = layerW * scaleX
            val finalH = layerH * scaleY

            val tlx = anchorPxX - finalW / 2f
            val tly = if (curveDir == "Up") anchorPxY - finalH else anchorPxY
            return WmBounds(tlx, tly, finalW, finalH)
        }
        "ring" -> {
            val rRaw = layer.getAttr("radius_outer", "100")
            val radius = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(rRaw, it) } ?: rRaw)
            layerW = radius * 2f
            layerH = radius * 2f
        }
        "series" -> {
            val sizeRaw = layer.getAttr("text_size", "24")
            val textSizePx = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(sizeRaw, it) } ?: sizeRaw).coerceAtLeast(8f)
            val spacing = layer.getAttr("spacing").toFloatOrNull() ?: 0f
            val window = wmSeriesWindow(layer.getAttr("series", "dh24z"))
            val count = window * 2 + 1
            val advance = spacing + textSizePx * 1.4f
            val vertical = layer.getAttr("orientation", "v") != "h"
            layerW = if (vertical) textSizePx * 6f else count * advance
            layerH = if (vertical) count * advance else textSizePx * 1.4f
            // current item sits at the anchor; box centered there
            val ax = wmX + wmCenter; val ay = wmY + wmCenter
            return WmBounds(ax - layerW / 2f, ay - layerH / 2f, layerW, layerH)
        }
        "calendar" -> {
            val sizeRaw = layer.getAttr("text_size", "10")
            val textSizePx = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(sizeRaw, it) } ?: sizeRaw).coerceAtLeast(6f)
            val marginX = layer.getAttr("margin_x").toFloatOrNull() ?: 5f
            val marginY = layer.getAttr("margin_y").toFloatOrNull() ?: 5f
            layerW = 7f * (textSizePx + marginX)
            layerH = 7f * (textSizePx + marginY) // 6 week rows + header
        }
        // Ring-arranged clock layers: centered on the anchor (x,y), box spans the
        // diameter plus a little padding for tick length / label size.
        "markers", "markers_hm", "text_ring" -> {
            val rRaw = layer.getAttr("radius", "100")
            val radius = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(rRaw, it) } ?: rRaw)
            val pad = when (layer.layerType) {
                "markers" -> wmEvalFloat(layer.getAttr("m_height", "20"), previewState)
                "text_ring" -> wmEvalFloat(layer.getAttr("text_size", "24"), previewState)
                else -> 12f
            }
            val half = radius + pad
            val scaleXv = if (includeScale) liveScale("anim_scale_x") else 1f
            val scaleYv = if (includeScale) liveScale("anim_scale_y") else 1f
            val finalWh = half * 2f * scaleXv
            val finalHh = half * 2f * scaleYv
            val ax = wmX + wmCenter
            val ay = wmY + wmCenter
            return WmBounds(ax - finalWh / 2f, ay - finalHh / 2f, finalWh, finalHh)
        }
        // Media layers: CloX sizes the video content node to width×height (default 512),
        // the map tile to width×height (default 256) — but the map builder ALSO scales the
        // tile by map_scale (%) over that box (setScaleX = width/tileW * map_scale), so the
        // rendered extent grows with it. gallery_2d has no CloX builder (not rendered).
        "video", "map", "gallery_2d" -> {
            val isVideo = layer.layerType == "video"
            val wRaw = layer.getAttr("width", if (isVideo) "512" else "256")
            val hRaw = layer.getAttr("height", if (isVideo) "512" else "256")
            val baseW = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(wRaw, it) } ?: wRaw).coerceAtLeast(1f)
            val baseH = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(hRaw, it) } ?: hRaw).coerceAtLeast(1f)
            val mscale = if (layer.layerType == "map") {
                val sRaw = layer.getAttr("map_scale", "100")
                (wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(sRaw, it) } ?: sRaw) / 100f).coerceAtLeast(0.01f)
            } else 1f
            layerW = baseW * mscale
            layerH = baseH * mscale
        }
        else -> {
            val wRaw = layer.getAttr("width", "100")
            val hRaw = layer.getAttr("height", "100")
            layerW = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(wRaw, it) } ?: wRaw).coerceAtLeast(1f)
            layerH = wmEvalFloat(previewState?.let { WmTagsData.evaluatePreview(hRaw, it) } ?: hRaw).coerceAtLeast(1f)
        }
    }

    val anchorPxX = wmX + wmCenter
    val anchorPxY = wmY + wmCenter

    // Bitmap-text boxes already include anim_scale in their measured metrics and the renderer
    // deliberately does not re-apply scaleX on bm layers — so skip the layer-scale multiply.
    val isBmText = layer.layerType == "text" && layer.getAttr("font").startsWith("bm:")
    val scaleX = if (includeScale && !isBmText) liveScale("anim_scale_x") else 1f
    val scaleY = if (includeScale && !isBmText) liveScale("anim_scale_y") else 1f
    val finalW = layerW * scaleX
    val finalH = layerH * scaleY

    val topLeftX = when (alignment) {
        "tl", "bl", "cl" -> anchorPxX
        "tr", "br", "cr" -> anchorPxX - finalW
        else              -> anchorPxX - finalW / 2f
    }
    val topLeftY = when (alignment) {
        "tl", "tr", "tc" -> anchorPxY
        "bl", "br", "bc" -> anchorPxY - finalH
        else              -> anchorPxY - finalH / 2f
    }
    return WmBounds(topLeftX, topLeftY, finalW, finalH)
}

/**
 * Returns a new layer resized by dragging its top-left corner toward [dragWm] (canvas space) while
 * the bottom-right corner of [orig] stays fixed. The anchor x/y is rewritten per alignment so the
 * box stays pinned. For `text` the box derives from text_size, so that is scaled instead of
 * width/height; every other layer type grows/shrinks its width/height attributes.
 */
internal fun wmCommitResize(
    layer: WmWatchfaceLayer,
    orig: WmBounds,
    dragWm: Offset,
    wmCenter: Float,
    minSize: Float = 4f
): WmWatchfaceLayer {
    val right = orig.x + orig.w
    val bottom = orig.y + orig.h
    val newLeft = dragWm.x.coerceAtMost(right - minSize)
    val newTop = dragWm.y.coerceAtMost(bottom - minSize)
    val newW = right - newLeft
    val newH = bottom - newTop

    val alignment = layer.getAttr("alignment").ifEmpty { "cc" }
    val hAnchor = when (alignment.getOrNull(1)) { 'l' -> 0f; 'r' -> 1f; else -> 0.5f }
    val vAnchor = when (alignment.getOrNull(0)) { 't' -> 0f; 'b' -> 1f; else -> 0.5f }
    val newAnchorX = (newLeft + newW * hAnchor) - wmCenter
    val newAnchorY = (newTop + newH * vAnchor) - wmCenter

    return when (layer.layerType) {
        "text" -> {
            val sizeRaw = layer.getAttr("text_size", "24")
            val size = wmEvalFloat(sizeRaw).coerceAtLeast(4f)
            val ratio = if (orig.w > 0f) newW / orig.w else 1f
            layer.withAttr("x", newAnchorX.toString())
                .withAttr("y", newAnchorY.toString())
                .withAttr("text_size", (size * ratio).coerceAtLeast(4f).toString())
        }
        else -> layer.withAttr("x", newAnchorX.toString())
            .withAttr("y", newAnchorY.toString())
            .withAttr("width", (newW / layer.scaleX).toString())
            .withAttr("height", (newH / layer.scaleY).toString())
    }
}

/** Returns the 8-element float array [TL,TL, TR,TR, BR,BR, BL,BL] for Android Path.addRoundRect. */
internal fun wmCornerRadii(cornerType: Int, r: Float): FloatArray {
    // Single source of truth for ALL corner_type users (rounded, progress, chart):
    // the renderer's bar mapping decodeCornerType (WatchSkin.lua:5321), which the
    // WM_FORMAT_1.0.md corner_type table documents. (tl, tr, br, bl)
    val (tl, tr, br, bl) = when (cornerType) {
        0, 16 -> listOf(0f, 0f, 0f, 0f)
        1     -> listOf(r,  r,  r,  r)
        2     -> listOf(r,  r,  0f, r)   // all except bottom-right
        3     -> listOf(r,  r,  r,  0f)  // all except bottom-left
        4     -> listOf(r,  0f, r,  r)   // all except top-right
        5     -> listOf(0f, r,  r,  r)   // all except top-left
        6     -> listOf(r,  r,  0f, 0f)  // top (TL + TR)
        7     -> listOf(0f, 0f, r,  r)   // bottom (BL + BR)
        8     -> listOf(r,  0f, 0f, r)   // left (TL + BL)
        9     -> listOf(0f, r,  r,  0f)  // right (TR + BR)
        10    -> listOf(r,  0f, r,  0f)  // diagonal TL + BR
        11    -> listOf(0f, r,  0f, r)   // diagonal TR + BL
        12    -> listOf(r,  0f, 0f, 0f)  // TL only
        13    -> listOf(0f, r,  0f, 0f)  // TR only
        14    -> listOf(0f, 0f, 0f, r)   // BL only
        15    -> listOf(0f, 0f, r,  0f)  // BR only
        else  -> listOf(r,  r,  r,  r)
    }
    return floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
}

/**
 * Applies flat-end override to fill radii:
 * - horizontal fill (left→right): flatten TR + BR (the "end" side)
 * - vertical fill (bottom→top): flatten TL + TR (the "end" side)
 */
internal fun wmFillRadii(cornerType: Int, r: Float, endStyle: String, isVertical: Boolean): FloatArray {
    val base = wmCornerRadii(cornerType, r)
    if (endStyle != "flat") return base
    return base.copyOf().also { a ->
        if (isVertical) { a[0] = 0f; a[1] = 0f; a[2] = 0f; a[3] = 0f }  // flatten TL+TR (top = fill end)
        else            { a[2] = 0f; a[3] = 0f; a[4] = 0f; a[5] = 0f }  // flatten TR+BR (right = fill end)
    }
}

/**
 * Squarify morph (ports the renderer's getSquarifyRadius): scales a ring radius by
 * angle so the layout bends from circle (squarify=0) toward square (squarify=100).
 * [angleRad] is the math angle (CCW, 0 = +X).
 */
internal fun wmSquarifyRadius(radius: Float, angleRad: Double, squarify: Float): Float {
    if (squarify <= 0f) return radius
    val f = (squarify / 100f).coerceIn(0f, 1f)
    val maxCs = maxOf(kotlin.math.abs(kotlin.math.cos(angleRad)), kotlin.math.abs(kotlin.math.sin(angleRad)))
        .coerceAtLeast(0.0001)
    val scale = (1.0 / maxCs).toFloat()
    return radius * ((1f - f) + f * scale)
}

/** Builds a WatchMaker shape path inside the rect (left, top, w, h). */
internal fun wmShapePath(shape: String, left: Float, top: Float, w: Float, h: Float): androidx.compose.ui.graphics.Path {
    fun x(f: Float) = left + w * f
    fun y(f: Float) = top + h * f
    return androidx.compose.ui.graphics.Path().apply {
        when (shape) {
            "Circle" -> addOval(androidx.compose.ui.geometry.Rect(left, top, left + w, top + h))
            "Triangle" -> { moveTo(x(0.5f), y(0f)); lineTo(x(1f), y(1f)); lineTo(x(0f), y(1f)); close() }
            "Pentagon" -> { moveTo(x(0.5f), y(0f)); lineTo(x(1f), y(0.4f)); lineTo(x(0.8f), y(1f)); lineTo(x(0.2f), y(1f)); lineTo(x(0f), y(0.4f)); close() }
            "Hexagon" -> { moveTo(x(0.5f), y(0f)); lineTo(x(1f), y(0.25f)); lineTo(x(1f), y(0.75f)); lineTo(x(0.5f), y(1f)); lineTo(x(0f), y(0.75f)); lineTo(x(0f), y(0.25f)); close() }
            "Star" -> {
                moveTo(x(0.5f), y(0f)); lineTo(x(0.65f), y(0.35f)); lineTo(x(1f), y(0.35f))
                lineTo(x(0.7f), y(0.6f)); lineTo(x(0.85f), y(1f)); lineTo(x(0.5f), y(0.75f))
                lineTo(x(0.15f), y(1f)); lineTo(x(0.3f), y(0.6f)); lineTo(x(0f), y(0.35f)); lineTo(x(0.35f), y(0.35f)); close()
            }
            "Heart" -> {
                moveTo(x(0.5f), y(0.2f))
                cubicTo(x(0.35f), y(0f), x(0f), y(0.06f), x(0.035f), y(0.4f))
                cubicTo(x(0.07f), y(0.66f), x(0.43f), y(0.83f), x(0.5f), y(1f))
                cubicTo(x(0.57f), y(0.83f), x(0.93f), y(0.66f), x(0.965f), y(0.4f))
                cubicTo(x(1f), y(0.06f), x(0.65f), y(0f), x(0.5f), y(0.2f))
            }
            else -> addRect(androidx.compose.ui.geometry.Rect(left, top, left + w, top + h))
        }
    }
}

/** Draws a single `markers` tick shape centered at (cx,cy), size w×h, tinted [color]. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.wmDrawMarkerShape(
    shape: String, cx: Float, cy: Float, w: Float, h: Float, color: Color
) {
    if (shape == "Square" || shape.isEmpty()) {
        drawRect(color, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = androidx.compose.ui.geometry.Size(w, h))
    } else {
        drawPath(wmShapePath(shape, cx - w / 2f, cy - h / 2f, w, h), color)
    }
}

/**
 * Ordered label set for a `text_ring` `ring_type`. Handles the named sets the
 * renderer recognizes (I-XII roman, Mo-Su / Mon-Sun days, Jan-Dec months), the
 * "A to B" form, and plain numeric "A-B" ranges (e.g. "1-12", "0-59").
 */
internal fun wmTextRingLabels(ringType: String): List<String> {
    val roman = listOf("I", "II", "III", "IIII", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    return when {
        ringType == "I-XII" -> roman
        ringType == "Mo-Su" -> listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        ringType == "Mon-Sun" -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        ringType == "Jan-Dec" -> listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        ringType.contains(" to ") -> {
            val s = ringType.split(" to ")
            val a = s.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val b = s.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            if (a <= b) (a..b).map { it.toString() } else (a downTo b).map { it.toString() }
        }
        else -> {
            val s = ringType.split("-")
            val a = s.getOrNull(0)?.trim()?.toIntOrNull()
            val b = s.getOrNull(1)?.trim()?.toIntOrNull()
            if (a != null && b != null) {
                if (a <= b) (a..b).map { it.toString() } else (a downTo b).map { it.toString() }
            } else listOf(ringType)
        }
    }
}

// chart `lines`: type|style|width|color|opacity|count|dashWidth|dashGap (backtick-separated entries)
internal data class WmChartLine(
    val type: String, val style: String, val width: Float, val color: String,
    val opacity: Float, val count: Int, val dashW: Float, val dashGap: Float
)
internal fun wmParseChartLines(raw: String): List<WmChartLine> =
    raw.split("`").filter { it.isNotBlank() }.mapNotNull { entry ->
        val p = entry.split("|")
        if (p.isEmpty()) return@mapNotNull null
        WmChartLine(
            type = p.getOrNull(0)?.trim()?.uppercase() ?: "H",
            style = p.getOrNull(1)?.trim() ?: "Solid",
            width = p.getOrNull(2)?.trim()?.toFloatOrNull() ?: 2f,
            color = p.getOrNull(3)?.trim() ?: "ffffff",
            opacity = (p.getOrNull(4)?.trim()?.toFloatOrNull() ?: 100f) / 100f,
            count = p.getOrNull(5)?.trim()?.toIntOrNull() ?: 1,
            dashW = p.getOrNull(6)?.trim()?.toFloatOrNull() ?: 0f,
            dashGap = p.getOrNull(7)?.trim()?.toFloatOrNull() ?: 0f
        )
    }

// chart `labels`: position|alignment|maxCount|text|font|color|size|opacity|xOffset|yOffset
internal data class WmChartLabel(
    val position: String, val alignment: String, val maxCount: Int, val text: String,
    val font: String, val color: String, val size: Float, val opacity: Float,
    val xOff: Float, val yOff: Float
)
internal fun wmParseChartLabels(raw: String): List<WmChartLabel> =
    raw.split("`").filter { it.isNotBlank() }.mapNotNull { entry ->
        val p = entry.split("|")
        if (p.isEmpty()) return@mapNotNull null
        WmChartLabel(
            position = p.getOrNull(0)?.trim()?.uppercase()?.ifEmpty { "P" } ?: "P",
            alignment = p.getOrNull(1)?.trim() ?: "",
            maxCount = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
            text = p.getOrNull(3) ?: "{value0dp}",
            font = p.getOrNull(4)?.trim() ?: "",
            color = p.getOrNull(5)?.trim() ?: "ffffff",
            size = p.getOrNull(6)?.trim()?.toFloatOrNull() ?: 16f,
            opacity = (p.getOrNull(7)?.trim()?.toFloatOrNull() ?: 100f) / 100f,
            xOff = p.getOrNull(8)?.trim()?.toFloatOrNull() ?: 0f,
            yOff = p.getOrNull(9)?.trim()?.toFloatOrNull() ?: 0f
        )
    }

/** Loads a project TTF/OTF [Typeface] by name (falls back to default; bm: → default). Cached per file. */
internal fun wmLoadTypeface(projectDir: File?, fontName: String): com.ailife.wftools.pc.graphics.Typeface {
    if (fontName.isEmpty() || fontName.startsWith("bm:")) return com.ailife.wftools.pc.graphics.Typeface.DEFAULT
    val dir = File(projectDir ?: File("."), "fonts")
    var target = File(dir, fontName)
    if (!target.exists()) target = File(dir, "$fontName.ttf")
    if (!target.exists()) target = File(dir, "$fontName.otf")
    return if (target.exists()) try { com.ailife.wftools.pc.graphics.Typeface.createFromFile(target) } catch (e: Exception) { com.ailife.wftools.pc.graphics.Typeface.DEFAULT }
        else com.ailife.wftools.pc.graphics.Typeface.DEFAULT
}

/**
 * Measures the widest rendered line of a vector (non-bm) text layer using the same font sizing
 * the renderer uses (1.28×text_size). The renderer lays the text out inside a box whose width is
 * the alignment anchor, so an estimate makes non-center aligned text drift — measure for real.
 */
internal fun wmVectorTextWidth(
    textStr: String,
    textSizePx: Float,
    transform: String,
    projectDir: File?,
    fontName: String
): Float {
    val paint = com.ailife.wftools.pc.graphics.Paint(com.ailife.wftools.pc.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx * 1.28f
        typeface = wmLoadTypeface(projectDir, fontName)
    }
    val display = applyCaseTransform(textStr, transform)
    val widest = display.split('\n').maxOfOrNull { paint.measureText(it) } ?: 0f
    return widest.coerceAtLeast(textSizePx)
}

/** How many items appear before (and after) the current one for a `series` type. */
internal fun wmSeriesWindow(series: String): Int = when (series) {
    "ddw", "dd_ddw" -> 3
    "dnnn" -> 5
    else -> 7
}

/**
 * Builds the ordered label list for a `series` layer and the index of the current
 * item. Mirrors the renderer's value generation (minutes/hours/day/month windows).
 */
internal fun wmSeriesItems(series: String, now: java.time.LocalDateTime): Pair<List<String>, Int> {
    val window = wmSeriesWindow(series)
    val loc = java.util.Locale.US
    fun at(offset: Int): String = when (series) {
        "dmz" -> "%02d".format(now.plusMinutes(offset.toLong()).minute)
        "dm" -> now.plusMinutes(offset.toLong()).minute.toString()
        "dhz" -> "%02d".format(((now.plusHours(offset.toLong()).hour % 12).let { if (it == 0) 12 else it }))
        "dh" -> ((now.plusHours(offset.toLong()).hour % 12).let { if (it == 0) 12 else it }).toString()
        "dh24z", "dh23z" -> "%02d".format(now.plusHours(offset.toLong()).hour)
        "dh24" -> now.plusHours(offset.toLong()).hour.toString()
        "ddw" -> now.plusDays(offset.toLong()).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, loc)
        "dd_ddw" -> now.plusDays(offset.toLong()).let {
            "${it.dayOfMonth} ${it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, loc)}"
        }
        "dnnn" -> now.plusMonths(offset.toLong()).month.getDisplayName(java.time.format.TextStyle.SHORT, loc)
        else -> "%02d".format(now.plusHours(offset.toLong()).hour)
    }
    val list = (-window..window).map { at(it) }
    return list to window
}

/** Convert HSV (0..360, 0..1, 0..1) to a Compose [Color]. */
internal fun wmHsvColor(h: Float, s: Float, v: Float): Color {
    val hsv = floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    val rgb = com.ailife.wftools.pc.graphics.Color.HSVToColor(hsv)
    return Color(rgb)
}
