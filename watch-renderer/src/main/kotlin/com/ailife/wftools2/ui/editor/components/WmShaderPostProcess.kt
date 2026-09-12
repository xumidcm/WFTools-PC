package com.ailife.wftools2.ui.editor.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.ailife.wftools.pc.graphics.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// ─── WatchMaker post-processing shaders ─────────────────────────────────────────
//
// These mirror the real WatchMaker GLSL shaders (RGB.fsh, HSV.fsh, Segment.fsh,
// Radar.fsh, Progress.fsh, etc.). Each runs as a per-layer post-process over the
// layer's already-rendered output ("texture"), so they apply to ANY layer type —
// text, image, shape — not just shapes. The layer Box must use
// CompositingStrategy.Offscreen so the blend operations stay confined to the layer.
//
// Categories:
//  • Color transform (RGB, HSV)            — recolor every pixel (saveLayer + ColorFilter)
//  • Color replace   (Gradient*, Rainbow)  — replace RGB, keep alpha   (BlendMode.SrcAtop)
//  • Alpha modulate  (Segment*, Radar, Progress*) — scale alpha by region (BlendMode.DstIn mask)

/** Shaders that replace the layer's color with a gradient fill, masked by its alpha. */
private val WM_GRADIENT_SHADERS = setOf("GradientLinear", "GradientRadial", "Rainbow")

/** Shaders that modulate the layer's alpha per region, keeping its color. */
private val WM_ALPHA_SHADERS = setOf("Segment", "SegmentBetween", "Radar", "Progress", "ProgressBetween")

/** Shaders that apply a per-pixel color transform. */
private val WM_COLOR_SHADERS = setOf("RGB", "HSV")

/** All shaders handled by the post-process pass. */
val WM_POST_SHADERS: Set<String> = WM_GRADIENT_SHADERS + WM_ALPHA_SHADERS + WM_COLOR_SHADERS

private const val WM_PI = Math.PI.toFloat()

private fun wmShaderColor(raw: String, fallback: String): Color {
    val h = (raw.ifEmpty { fallback }).trimStart('#').padStart(6, '0')
    return try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$h")) } catch (e: Exception) { Color.White }
}

private fun fOr(raw: String, default: Float): Float = raw.toFloatOrNull() ?: default

/**
 * Render this layer's content through a WatchMaker shader as a post-process step.
 * Call instead of [drawContent] when the layer has a [shader] in [WM_POST_SHADERS].
 * u1..u5 are the already-resolved (live-evaluated) u_1..u_5 attribute strings.
 */
fun ContentDrawScope.drawWmShadedContent(
    shader: String,
    u1: String, u2: String, u3: String, u4: String, u5: String
) {
    applyWmShader(shader, u1, u2, u3, u4, u5) { drawContent() }
}

/**
 * Runs the shader pass over a caller-supplied draw block instead of the layer
 * content: the color replace (SrcAtop), alpha mask (DstIn) or color transform is
 * applied to exactly what [content] draws.
 */
fun ContentDrawScope.applyWmShader(
    shader: String,
    u1: String, u2: String, u3: String, u4: String, u5: String,
    content: ContentDrawScope.() -> Unit
) {
    when (shader) {
        "RGB" -> drawWithColorMatrix(wmRgbMatrix(fOr(u1, 0f), fOr(u2, 0f), fOr(u3, 0f))) { content() }
        "HSV" -> drawWithColorMatrix(wmHsvMatrix(fOr(u1, 0f), fOr(u2, 0f), fOr(u3, 0f))) { content() }
        in WM_GRADIENT_SHADERS -> {
            content()
            drawColorReplace(shader, u1, u2, u3, u4)
        }
        in WM_ALPHA_SHADERS -> {
            content()
            drawAlphaModulate(shader, u1, u2, u3, u4, u5)
        }
        else -> content()
    }
}

/**
 * Host for TEXT layers with a shader: the glyphs are composited into their own
 * offscreen layer and the shader runs over only that layer, so effect copies
 * (shadow/outline/glow) drawn beneath keep their own colors — the launcher
 * bakes just the label into an RT and shades that, leaving effects untouched.
 */
@Composable
fun WmShadedTextHost(
    shader: String,
    u1: String, u2: String, u3: String, u4: String, u5: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = false
            }
            .drawWithContent {
                applyWmShader(shader, u1, u2, u3, u4, u5) { drawContent() }
            }
    ) { content() }
}

// ── Color transform (RGB / HSV) — saveLayer with a ColorFilter ───────────────────

private fun ContentDrawScope.drawWithColorMatrix(
    cm: com.ailife.wftools.pc.graphics.ColorMatrix,
    content: ContentDrawScope.() -> Unit
) {
    val paint = com.ailife.wftools.pc.graphics.Paint().apply {
        colorFilter = com.ailife.wftools.pc.graphics.ColorMatrixColorFilter(cm)
    }
    drawIntoCanvasSaveLayer(paint)
    content()
    drawIntoCanvas { it.nativeCanvas.restore() }
}

private fun ContentDrawScope.drawIntoCanvasSaveLayer(paint: com.ailife.wftools.pc.graphics.Paint) {
    // Null bounds: no clip, so skew-distorted overhang stays visible.
    drawIntoCanvas { it.nativeCanvas.saveLayer(null, paint) }
}

// RGB.fsh: color.rgb += u/100  (additive offset, -100..100)
private fun wmRgbMatrix(rAdd: Float, gAdd: Float, bAdd: Float): com.ailife.wftools.pc.graphics.ColorMatrix =
    com.ailife.wftools.pc.graphics.ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, rAdd / 100f * 255f,
        0f, 1f, 0f, 0f, gAdd / 100f * 255f,
        0f, 0f, 1f, 0f, bAdd / 100f * 255f,
        0f, 0f, 0f, 1f, 0f
    ))

// HSV.fsh: hue += u_1/100 (turns), sat += u_2/100, val += u_3/100.
// Approximated with a ColorMatrix: hue rotation + saturation scale + brightness offset.
private fun wmHsvMatrix(hueAdd: Float, satAdd: Float, valAdd: Float): com.ailife.wftools.pc.graphics.ColorMatrix {
    val cm = com.ailife.wftools.pc.graphics.ColorMatrix()

    // Saturation: additive in the shader; approximate as a multiplier around 1.0.
    cm.postConcat(com.ailife.wftools.pc.graphics.ColorMatrix().apply { setSaturation((1f + satAdd / 100f).coerceAtLeast(0f)) })

    // Hue rotation by hueAdd/100 of a full turn.
    val rad = (hueAdd / 100f) * 2f * WM_PI
    val cosA = cos(rad); val sinA = sin(rad)
    val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
    cm.postConcat(com.ailife.wftools.pc.graphics.ColorMatrix(floatArrayOf(
        lr + cosA * (1 - lr) + sinA * (-lr),     lg + cosA * (-lg) + sinA * (-lg),     lb + cosA * (-lb) + sinA * (1 - lb), 0f, 0f,
        lr + cosA * (-lr) + sinA * (0.143f),     lg + cosA * (1 - lg) + sinA * (0.140f), lb + cosA * (-lb) + sinA * (-0.283f), 0f, 0f,
        lr + cosA * (-lr) + sinA * (-(1 - lr)),  lg + cosA * (-lg) + sinA * (lg),       lb + cosA * (1 - lb) + sinA * (lb),  0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )))

    // Value (brightness): additive offset on RGB channels.
    val arr = cm.array
    val vOff = valAdd / 100f * 255f
    arr[4] += vOff; arr[9] += vOff; arr[14] += vOff
    cm.set(arr)
    return cm
}

// ── Color replace (Gradient* / Rainbow) — SrcAtop over the content's alpha ────────

private fun ContentDrawScope.drawColorReplace(shader: String, u1: String, u2: String, u3: String, u4: String) {
    val w = size.width; val h = size.height
    val cx = w / 2f; val cy = h / 2f
    val brush: Brush = when (shader) {
        "GradientLinear" -> {
            val angle = fOr(u3, 0f)
            val scale = (fOr(u4, 100f) / 100f).coerceAtLeast(0.01f)
            val rad = angle * WM_PI / 180f
            val halfLen = max(w, h) * scale / 2f
            // UL GradientLinear.fsh (verbatim in CloX wm_GradientLinear.frag):
            // yNew = 0.5 + (x-0.5)·sin(-θ) + (y-0.5)·cos(-θ), mixed u_1→u_2 by
            // clamped yNew. The u_1→u_2 axis therefore runs along (-sinθ, +cosθ):
            // u_1 at 12 o'clock for θ=0 (top→bottom), 3 o'clock for θ=90,
            // 6 o'clock for θ=180 — NOT along (cosθ, sinθ), which pointed the
            // θ=0 gradient left→right.
            val dX = -halfLen * sin(rad)
            val dY = halfLen * cos(rad)
            Brush.linearGradient(
                colors = listOf(wmShaderColor(u1, "ffffff"), wmShaderColor(u2, "000000")),
                start = Offset(cx - dX, cy - dY), end = Offset(cx + dX, cy + dY)
            )
        }
        "GradientRadial" -> {
            val scale = (fOr(u3, 100f) / 100f).coerceAtLeast(0.01f)
            Brush.radialGradient(
                colors = listOf(wmShaderColor(u1, "ffffff"), wmShaderColor(u2, "000000")),
                center = Offset(cx, cy), radius = max(w, h) / 2f * scale
            )
        }
        else -> { // Rainbow — hue sweep tinted by u_1
            val hueShift = fOr(u1, 0f)
            Brush.sweepGradient(
                *listOf(0f, 0.166f, 0.333f, 0.5f, 0.666f, 0.833f, 1f).map { frac ->
                    val hh = frac * 360f + hueShift
                    frac to wmHsvColor(hh - floor(hh / 360f) * 360f, 1f, 1f)
                }.toTypedArray(),
                center = Offset(cx, cy)
            )
        }
    }
    drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
}

// ── Alpha modulate (Segment* / Radar / Progress*) — DstIn mask layer ──────────────

private fun ContentDrawScope.drawAlphaModulate(
    shader: String, u1: String, u2: String, u3: String, u4: String, u5: String
) {
    // Build the opacity mask in its own layer, then composite onto the content with
    // DstIn (content.alpha *= mask.alpha, content color preserved).
    val dstIn = com.ailife.wftools.pc.graphics.Paint().apply {
        xfermode = com.ailife.wftools.pc.graphics.PorterDuffXfermode(com.ailife.wftools.pc.graphics.PorterDuff.Mode.DST_IN)
    }
    drawIntoCanvas { it.nativeCanvas.saveLayer(null, dstIn) }
    when (shader) {
        "Segment" -> drawSegmentMask(fOr(u1, 90f), opac(u2, 100f), opac(u3, 20f))
        "SegmentBetween" -> drawSegmentBetweenMask(fOr(u1, 0f), fOr(u2, 180f), opac(u3, 100f), opac(u4, 10f))
        "Radar" -> drawRadarMask(fOr(u1, 0f), opac(u2, 100f), opac(u3, 15f))
        "Progress" -> drawProgressMask(opac(u1, 50f), fOr(u2, 0f), opac(u3, 100f), opac(u4, 20f))
        "ProgressBetween" -> drawProgressBetweenMask(opac(u1, 0f), opac(u2, 50f), fOr(u3, 0f), opac(u4, 100f), opac(u5, 15f))
    }
    drawIntoCanvas { it.nativeCanvas.restore() }
}

private fun opac(raw: String, default: Float): Float = (fOr(raw, default) / 100f).coerceIn(0f, 1f)

private fun maskColor(alpha: Float) = Color.Black.copy(alpha = alpha.coerceIn(0f, 1f))

// Segment.fsh: angle 0..u_1 (from 12 o'clock, clockwise) at u_2 opacity, else u_3.
private fun ContentDrawScope.drawSegmentMask(span: Float, inOp: Float, outOp: Float) {
    val w = size.width; val h = size.height
    drawRect(color = maskColor(outOp))
    if (span > 0f && inOp > 0f) {
        drawArc(
            color = maskColor(inOp), startAngle = -90f, sweepAngle = span.coerceIn(0f, 360f),
            useCenter = true, topLeft = Offset(-w / 2f, -h / 2f), size = Size(w * 2f, h * 2f),
            blendMode = BlendMode.Src, style = Fill
        )
    }
}

// SegmentBetween.fsh: angle u_1..u_2 at u_3 opacity, else u_4.
private fun ContentDrawScope.drawSegmentBetweenMask(a1: Float, a2: Float, inOp: Float, outOp: Float) {
    val w = size.width; val h = size.height
    drawRect(color = maskColor(outOp))
    val diff = a2 - a1
    val sweep = diff - floor(diff / 360f) * 360f
    if (sweep > 0f && inOp > 0f) {
        drawArc(
            color = maskColor(inOp), startAngle = -90f + a1, sweepAngle = sweep,
            useCenter = true, topLeft = Offset(-w / 2f, -h / 2f), size = Size(w * 2f, h * 2f),
            blendMode = BlendMode.Src, style = Fill
        )
    }
}

// Radar.fsh: a 100°-wide comet tail behind leading-edge angle u_1.
// Opacity peaks at u_2 (at the edge) and fades to u_3 (base) over the tail and everywhere else.
private fun ContentDrawScope.drawRadarMask(edge: Float, peak: Float, base: Float) {
    val w = size.width; val h = size.height
    val cover = 100f
    val stops = ArrayList<Pair<Float, Color>>()
    var deg = 0
    while (deg <= 360) {
        val frac = deg / 360f
        // sweepGradient fraction (CW from 3 o'clock) → shader angle θ (0 at 12 o'clock, CW)
        val theta = ((frac * 360f) + 90f).let { it - floor(it / 360f) * 360f }
        var d = edge - theta
        d -= floor(d / 360f) * 360f // 0..360
        val op = if (d in 0f..cover) {
            val pct2 = d / cover            // 0 at edge → 1 at tail end
            pct2 * base + (1f - pct2) * peak
        } else base
        stops.add(frac to maskColor(op))
        deg += 4
    }
    drawRect(brush = Brush.sweepGradient(*stops.toTypedArray(), center = Offset(w / 2f, h / 2f)))
}

// Progress.fsh: yNew = 1 - clamp(0.5 + (x-0.5)sin(-a) + (y-0.5)cos(-a)); filled where yNew<=pct.
// Hard split: filled region at inOp, unfilled at outOp.
private fun ContentDrawScope.drawProgressMask(pct: Float, angle: Float, inOp: Float, outOp: Float) {
    val w = size.width; val h = size.height
    val a = angle * WM_PI / 180f
    val s = sin(a); val c = cos(a)
    // yNew increases along (sin a, -cos a) in normalized space; filled is low yNew.
    val dirX = s; val dirY = -c
    val cx = w / 2f; val cy = h / 2f
    val half = 0.5f * (abs(s) * w + abs(c) * h)
    val cIn = maskColor(inOp); val cOut = maskColor(outOp)
    when {
        pct <= 0f -> drawRect(color = cOut)
        pct >= 1f -> drawRect(color = cIn)
        else -> {
            val start = Offset(cx - dirX * half, cy - dirY * half)
            val end = Offset(cx + dirX * half, cy + dirY * half)
            drawRect(brush = Brush.linearGradient(
                0f to cIn, pct to cIn, pct to cOut, 1f to cOut, start = start, end = end))
        }
    }
}

// ProgressBetween.fsh: yNew = clamp(0.5 + (x-0.5)sin(-a) + (y-0.5)cos(-a)); filled where from<=yNew<=to.
private fun ContentDrawScope.drawProgressBetweenMask(from: Float, to: Float, angle: Float, inOp: Float, outOp: Float) {
    val w = size.width; val h = size.height
    val a = angle * WM_PI / 180f
    val s = sin(a); val c = cos(a)
    // GLSL: yNew = 0.5 - (x-0.5)sin a + (y_up-0.5)cos a, with y_up bottom-up (OpenGL).
    // Canvas y is top-down, so the cos term flips → direction is (-sin a, -cos a).
    val dirX = -s; val dirY = -c
    val cx = w / 2f; val cy = h / 2f
    val half = 0.5f * (abs(s) * w + abs(c) * h)
    val cIn = maskColor(inOp); val cOut = maskColor(outOp)
    val lo = from.coerceIn(0f, 1f); val hi = to.coerceIn(0f, 1f)
    if (hi <= lo) { drawRect(color = cOut); return }
    val start = Offset(cx - dirX * half, cy - dirY * half)
    val end = Offset(cx + dirX * half, cy + dirY * half)
    drawRect(brush = Brush.linearGradient(
        0f to cOut, lo to cOut, lo to cIn, hi to cIn, hi to cOut, 1f to cOut, start = start, end = end))
}