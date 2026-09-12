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

internal fun wmLayerOutputSignature(
    layer: WmWatchfaceLayer,
    tagValues: Map<String, String>,
    fixedNow: java.time.LocalDateTime,
    globalVars: Map<String, String>,
    scriptRuntime: WmScriptRuntime?
): String {
    val st = WmTagsData.WmPreviewState(
        tagOverride = tagValues, now = fixedNow, variables = globalVars, scriptRuntime = scriptRuntime
    )
    val sb = StringBuilder()
    val hasVars = globalVars.isNotEmpty()
    for ((k, v) in layer.attrs) {
        // Gate on braced {tag}s AND bare global-script variable references (e.g.
        // `x = var_mx`): a script tap mutates vars on every frame, so these attrs
        // must re-evaluate whenever the variable snapshot changes or the layer
        // would freeze at its first-composed output.
        if (v.contains('{') || (hasVars && globalVars.keys.any { v.contains(it) })) {
            sb.append(k).append('=').append(WmTagsData.evaluatePreview(v, st)).append('')
        }
    }
    return sb.toString()
}

/**
 * markers_hm preset sizes from WM Studio's renderer `K` map: (glyph shape, width, height)
 * in canvas px. Hour ticks scale these by 1.6×w / 1.8×h; `None` (width 0) is skipped.
 */
internal val WM_HM_PRESETS: Map<String, Triple<String, Float, Float>> = mapOf(
    "None" to Triple("Square", 0f, 0f),
    "Tiny" to Triple("Square", 1.5f, 4f),
    "Small" to Triple("Square", 3f, 8f),
    "Medium" to Triple("Square", 4.5f, 12f),
    "Large" to Triple("Square", 6f, 16f),
    "XLarge" to Triple("Square", 7.5f, 20f),
    "Circle" to Triple("Circle", 7f, 7f),
    "Triangle" to Triple("Triangle", 8f, 16f)
)

/**
 * WM `display` visibility for a given render mode. `h`=hidden and `n`=never are
 * always off; `b`=bright-only, `d`=dim-only; empty/`bd` shows in both. Matches
 * CloX `visibleIn` (wf_render.lua) so the bright/dim toggle shows the correct
 * layer set for each mode.
 */
internal fun wmDisplayVisible(display: String, isBright: Boolean): Boolean = when (display) {
    "h", "n" -> false
    "d" -> !isBright
    "b" -> isBright
    else -> true // "" / "bd"
}

// Configurable preset shortcut names (CloX TapActionHandler.CONFIGURABLE_ACTIONS).
// No app is mapped to them in the preview, so they're simulated with a Toast.
private val WM_PRESET_ACTIONS = setOf(
    "tap_launcher", "widget_weather", "widget_health", "widget_calendar",
    "w_agenda", "w_fit", "w_heart_rate", "w_compass", "w_keep", "m_email",
    "m_calendar", "voice_search", "w_contacts", "w_sports", "w_gallery",
    "w_messaging", "w_camera", "w_dialer", "w_music", "w_clock",
    "w_calculator", "w_payment", "w_map", "browser", "flashlight", "socialmedia"
)

/**
 * Preview feedback for a non-`script:` tap action (from a layer's `tap_action` or from
 * `wm_action`/`wm_vibrate`/`wm_sfx`/`wm_set_alarm` inside the global script). Preset
 * shortcut names have no app mapped in the preview, so they're simulated with a Toast;
 * every other action is carried out for real (volume, media keys, alarm, settings, app
 * launch, deep link, vibrate, sound) with errors caught and reported via Toast.
 */

private fun osmTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
    val n = 1 shl zoom
    val tx = ((lon + 180.0) / 360.0 * n).toInt()
    val latC = lat.coerceIn(-85.05112878, 85.05112878)
    val la = Math.toRadians(latC)
    val ty = ((1.0 - Math.log(Math.tan(la) + 1.0 / Math.cos(la)) / Math.PI) / 2.0 * n).toInt()
    val max = n - 1
    return tx.coerceIn(0, max) to ty.coerceIn(0, max)
}

// OSM's tile usage policy requires an identifying User-Agent; Coil/OkHttp's default
// UA gets a 403, so the map tile is fetched through its own client with one.
private val wmMapTileClient = okhttp3.OkHttpClient.Builder()
    .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
    .build()

/** Renders the OSM tile at [tileUrl] stretched to the layer box (CloX's map sprite
 *  scaled to width×height×map_scale), tinted by [colorFilter]; a grid+pin falls back
 *  while loading or when the tile can't be fetched. */

@Composable
internal fun WmLayerRender(
    layer: WmWatchfaceLayer,
    tagValues: Map<String, String>,
    globalVars: Map<String, String>,
    fastStateHolder: State<WmTagsData.WmPreviewState>,
    fixedNow: java.time.LocalDateTime,
    projectDir: File,
    density: androidx.compose.ui.unit.Density,
    isBright: Boolean,
    scriptRuntime: WmScriptRuntime?
) {

    val WM_CENTER = 256f
    // Fine-grained propagation: this layer resolves its {tag}s through [tagValues]
    // (WmPreviewState.tagOverride). Each lookup is a snapshot read of that specific key, so the
    // layer recomposes ONLY when a tag its expressions actually reference changes value — no
    // per-tick signature diffing, no re-evaluating every layer. A layer with no live tags reads
    // nothing from the map and never recomposes. Intrinsically-animated types (gif/series/
    // calendar) additionally read `now` from the 60fps holder; everyone else uses a fixed `now`.
    // Output-gated propagation. `sig` re-evaluates this layer's tagged attributes whenever a tag
    // it references changes (driven by the tagValues map), but its value changes only when the
    // layer's RESOLVED output changes. The scope subscribes to `sig` only; the render body then
    // reads an UNOBSERVED snapshot of the tag map, so it never independently re-subscribes per
    // tag. Net: a layer recomposes only when what it draws actually changes. `globalVars` is a
    // key so a script-var change (tap action / on_second) re-drives this layer's output too.
    val sig = remember(layer, tagValues, globalVars) {
        derivedStateOf { wmLayerOutputSignature(layer, tagValues, fixedNow, globalVars, scriptRuntime) }
    }
    val sigVal = sig.value
    val tagSnapshot = remember(sigVal) {
        Snapshot.withoutReadObservation { HashMap(tagValues) }
    }
    // `now` is FIXED so reading it never subscribes this layer to the 60fps tick. Intrinsically-
    // animated types (gif/series/calendar) read `now` through their own derivedStateOf gated on
    // their change unit (frame index / current item / date).
    val previewState = WmTagsData.WmPreviewState(
        tagOverride = tagSnapshot,
        now = fixedNow,
        variables = globalVars,
        scriptRuntime = scriptRuntime
    )
    val now = previewState.now
    // Bright/dim (AOD) visibility: shows the correct layer set per mode.
    if (!wmDisplayVisible(layer.getAttr("display"), isBright)) return

    val opacityRaw = WmTagsData.evaluatePreview(layer.getAttr("opacity", "100"), previewState)
    val opacity = (opacityRaw.toFloatOrNull() ?: 100f) / 100f
    if(opacity<=0f)return // Invisible language/variant layers must not allocate render targets.

    val rotationRaw = WmTagsData.evaluatePreview(layer.getAttr("rotation", "0"), previewState)
    val rotation = wmEvalFloat(rotationRaw)

    // In dim mode use color_dim (1.0 AOD color) when the layer defines it.
    // Evaluate like every other live attribute: faces write color="var_col"
    // or color="wf_col(0)" — a raw read would feed the expression string
    // to parseColor and silently drop the tint (layer renders white).
    val colorAttr = if (!isBright && layer.getAttr("color_dim").isNotBlank()) "color_dim" else "color"
    val colorEval = WmTagsData.evaluatePreview(layer.getAttr(colorAttr, "ffffff"), previewState)
    val colorRaw = colorEval.ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
    val colorFilter = try {
        if (colorRaw.lowercase(java.util.Locale.ROOT) != "ffffff" && colorRaw.isNotEmpty()) {
            // WM's renderer (Cocos2D) multiplies the node color with the
            // texture — Compose's default tint (SrcIn) would flatten
            // textured images into solid silhouettes
            androidx.compose.ui.graphics.ColorFilter.tint(
                Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$colorRaw")),
                androidx.compose.ui.graphics.BlendMode.Modulate
            )
        } else null
    } catch(e: Exception) { null }

    val (topLeftX, topLeftY, layerW, layerH) = wmLayerBounds(layer, WM_CENTER, previewState, projectDir, includeScale = false)

    // Gyro parallax (spec §11 / CloX): screen_x += sgy*gyro,
    // screen_y += sgx*gyro*-1, driven by the live gyroscope. Only
    // layers with a nonzero `gyro` read the 60fps holder (others don't
    // subscribe, so they never recompose on sensor updates).
    val gyroMult = layer.getAttr("gyro").toFloatOrNull() ?: 0f
    val gyroDx: Float
    val gyroDy: Float
    if (gyroMult != 0f) {
        val g = fastStateHolder.value.gyro
        gyroDx = g.second * gyroMult
        gyroDy = -g.first * gyroMult
    } else { gyroDx = 0f; gyroDy = 0f }

    // Post-processing shader (applies to ANY layer type). u_1..u_5 resolve live.
    val postShader = layer.getAttr("shader")
    val hasPostShader = postShader in WM_POST_SHADERS
    val sU1 = WmTagsData.evaluatePreview(layer.getAttr("u_1"), previewState)
    val sU2 = WmTagsData.evaluatePreview(layer.getAttr("u_2"), previewState)
    val sU3 = WmTagsData.evaluatePreview(layer.getAttr("u_3"), previewState)
    val sU4 = WmTagsData.evaluatePreview(layer.getAttr("u_4"), previewState)
    val sU5 = WmTagsData.evaluatePreview(layer.getAttr("u_5"), previewState)

    val isStencil = layer.getAttr("stencil").equals("y", ignoreCase = true)
    val stencilSheetColor = try {
        Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$colorRaw"))
    } catch (e: Exception) { Color.White }
    StencilMaskBox(enabled = isStencil, sheetColor = stencilSheetColor) {
    Box(
        modifier = Modifier
            .absoluteOffset(
                x = with(density) { (topLeftX + gyroDx).toDp() },
                y = with(density) { (topLeftY + gyroDy).toDp() }
            )
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .size(
                width = with(density) { layerW.toDp() },
                height = with(density) { layerH.toDp() }
            )
            .graphicsLayer {
                val wmLayer = layer
                this.alpha = 1f
                rotationZ = rotation
                // The viewport owns clipping. Opacity is composited below through
                // an unbounded native layer so text and skew overhang remain intact.
                clip = false
                if (hasPostShader) {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                // Bitmap font layers: anim_scale encodes char cell size (% of canvas),
                // already baked into the layer box and char rendering — don't scale again.
                val isBitmapFont = wmLayer.layerType == "text" &&
                    wmLayer.getAttr("font").startsWith("bm:") &&
                    (wmLayer.getAttr("anim_scale_x", "0").toFloatOrNull() ?: 0f) > 0
                // anim_scale is a live expression (tags/script vars allowed) — resolve
                // against the preview state like the launcher's bindNum, so expression
                // scales animate instead of collapsing to the static 100 default.
                val scaleExprX = WmTagsData.evaluatePreview(wmLayer.getAttr("anim_scale_x", "100"), previewState)
                val scaleExprY = WmTagsData.evaluatePreview(wmLayer.getAttr("anim_scale_y", "100"), previewState)
                scaleX = if (isBitmapFont) 1f else (scaleExprX.toFloatOrNull() ?: 100f) / 100f
                scaleY = if (isBitmapFont) 1f else (scaleExprY.toFloatOrNull() ?: 100f) / 100f

                val alignment = layer.getAttr("alignment").ifEmpty { "cc" }
                val originX = when (alignment) {
                    "tl", "bl", "cl" -> 0f
                    "tr", "br", "cr" -> 1f
                    else -> 0.5f
                }
                val originY = when (alignment) {
                    "tl", "tr", "tc" -> 0f
                    "bl", "br", "bc" -> 1f
                    else -> 0.5f
                }
                // Curved text rotates around its CIRCLE CENTER (the layer
                // anchor), which sits at the box's bottom edge for "Up" and
                // top edge for "Down" — not at the alignment-derived origin.
                if (wmLayer.layerType == "text_curved") {
                    val up = wmLayer.getAttr("curve_dir", "Up") == "Up"
                    transformOrigin = TransformOrigin(0.5f, if (up) 1f else 0f)
                } else {
                    transformOrigin = TransformOrigin(originX, originY)
                }
            }
            .drawWithContent {
                // Desktop paragraph draws do not inherit ModulateAlpha reliably.
                // A single unbounded saveLayer includes text/effects/media and keeps
                // skew/outline overflow; the viewport provides the final clip.
                val alphaPaint=if(opacity<1f)org.jetbrains.skia.Paint().apply {
                    color=((opacity.coerceIn(0f,1f)*255).toInt() shl 24) or 0xffffff
                }else null
                if(alphaPaint!=null)drawContext.canvas.nativeCanvas.saveLayer(null,alphaPaint)
                try {
                // Tan is the shear factor a 2D skew transform expects. Cocos applies
                // skew in Y-up space (x'=x+tanX·y, y'=tanY·x+y); the Y-up→screen flip
                // conjugates that to negate BOTH terms in this Compose Y-down canvas.
                // (Rotation doesn't flip because cocos negates its own rotation.)
                val skewX = Math.tan(Math.toRadians(wmEvalFloat(WmTagsData.evaluatePreview(layer.getAttr("skew_x","0"),previewState)).toDouble())).toFloat()
                val skewY = Math.tan(Math.toRadians(wmEvalFloat(WmTagsData.evaluatePreview(layer.getAttr("skew_y","0"),previewState)).toDouble())).toFloat()

                fun drawLayerContent() {
                    // Text layers shade ONLY their glyphs (inside
                    // WmShadedTextHost) so effect copies keep their own
                    // colors — the box-level pass would eat the outline.
                    when {
                        hasPostShader && layer.layerType == "text" -> drawContent()
                        hasPostShader -> drawWmShadedContent(postShader, sU1, sU2, sU3, sU4, sU5)
                        else -> drawContent()
                    }
                }

                if (skewX != 0f || skewY != 0f) {
                    val alignment = layer.getAttr("alignment").ifEmpty { "cc" }
                    val originX = when (alignment) {
                        "tl", "bl", "cl" -> 0f
                        "tr", "br", "cr" -> 1f
                        else -> 0.5f
                    }
                    val originY = when (alignment) {
                        "tl", "tr", "tc" -> 0f
                        "bl", "br", "bc" -> 1f
                        else -> 0.5f
                    }
                    val px = originX * size.width
                    val py = originY * size.height
                    // Column-major 4x4 shear: m01 = x-shear (skew_x), m10 = y-shear
                    // (skew_y). Applied through Compose's own transform stack
                    // (DrawTransform) so it reliably reaches child draw content.
                    val skewMatrix = androidx.compose.ui.graphics.Matrix()
                    skewMatrix.values[0] = 1f
                    skewMatrix.values[1] = -skewY
                    skewMatrix.values[4] = -skewX
                    skewMatrix.values[5] = 1f
                    skewMatrix.values[10] = 1f
                    skewMatrix.values[15] = 1f
                    withTransform({
                        translate(px, py)
                        transform(skewMatrix)
                        translate(-px, -py)
                    }) {
                        drawLayerContent()
                    }
                } else {
                    drawLayerContent()
                }
                } finally {
                    if(alphaPaint!=null){drawContext.canvas.nativeCanvas.restore();alphaPaint.close()}
                }
            }
    ) {
        when (layer.layerType) {
            "image" -> {
                val path = layer.getAttr("path")
                if (path.isNotEmpty()) {
                    val parsedPath = path.removePrefix("images/")
                    AsyncImage(
                        model = File(projectDir, "images/$parsedPath"),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        colorFilter = colorFilter
                    )
                }
            }
            "image_gif" -> {
                val paths = layer.getAttr("path").split("`").filter { it.isNotEmpty() }
                    .map { File(projectDir, "images/" + it.removePrefix("images/")) }
                WmAnimatedImageLayer(paths, layer.getAttr("gif_delay").toLongOrNull() ?: 100,fastStateHolder,colorFilter)
            }
            "image_cond" -> {
                val rawPath = layer.getAttr("path")
                if (rawPath.isNotEmpty()) {
                    val gridStr = layer.getAttr("cond_grid", "1x1")
                    val gridParts = gridStr.lowercase().split("x")
                    val gridCols = gridParts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val gridRows = gridParts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

                    // weather_night="Y" (CloX): at night show the "_n" sheet and remap
                    // the sun cells (1->4, 2->3, 3->3, 4->9). Night = before sunrise or
                    // after sunset (from the sample sunrise/sunset).
                    val nightSet = layer.getAttr("weather_night") == "Y"
                    val isNight = nightSet && run {
                        fun mins(s: String): Int { val p = s.split(":"); return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0) }
                        val nowMin = previewState.now.hour * 60 + previewState.now.minute
                        nowMin < mins(previewState.sunrise) || nowMin >= mins(previewState.sunset)
                    }
                    val path = if (isNight) {
                        val dot = rawPath.lastIndexOf('.')
                        if (dot > 0) {
                            var stem = rawPath.substring(0, dot); val ext = rawPath.substring(dot)
                            if (stem.endsWith("_n")) stem = stem.dropLast(2)
                            val cand = "${stem}_n$ext"
                            if (File(projectDir, "images/" + cand.removePrefix("images/")).exists()) cand else rawPath
                        } else rawPath
                    } else rawPath

                    val condRaw = WmTagsData.evaluatePreview(layer.getAttr("cond_value", "1"), previewState)
                    var cell = wmEvalFloat(condRaw).toInt().coerceIn(1, gridCols * gridRows)
                    if (isNight) cell = when (cell) { 1 -> 4; 2 -> 3; 3 -> 3; 4 -> 9; else -> cell }
                    val condIdx = (cell - 1).coerceIn(0, gridCols * gridRows - 1)
                    val colIdx = condIdx % gridCols
                    val rowIdx = condIdx / gridCols
                    val parsedPath = path.removePrefix("images/")
                    val imageFile = File(projectDir, "images/$parsedPath")
                    val bitmap = rememberPreviewBitmap(imageFile.absolutePath)
                    if (bitmap != null) {
                        val cellW = bitmap.width / gridCols
                        val cellH = bitmap.height / gridRows
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawImage(
                                image = bitmap,
                                srcOffset = IntOffset(colIdx * cellW, rowIdx * cellH),
                                srcSize = IntSize(cellW, cellH),
                                dstOffset = IntOffset(0, 0),
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                colorFilter = colorFilter
                            )
                        }
                    }
                }
            }
            "text" -> {
                val displayText = WmTagsData.evaluatePreview(layer.getAttr("text"), previewState)
                val textSizePxRaw = WmTagsData.evaluatePreview(layer.getAttr("text_size", "24"), previewState)
                val textSizePx = textSizePxRaw.toFloatOrNull() ?: 24f

                // Dim mode uses color_dim (AOD text color) when present.
                val textColorAttr = if (!isBright && layer.getAttr("color_dim").isNotBlank()) "color_dim" else "color"
                val colorHexRaw = WmTagsData.evaluatePreview(layer.getAttr(textColorAttr, "ffffff"), previewState)
                val colorHex = colorHexRaw.ifEmpty { "ffffff" }
                val textColor = try {
                    val h = colorHex.trimStart('#').padStart(6, '0')
                    Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$h"))
                } catch (e: Exception) { Color.White }

                val fontName = layer.getAttr("font")
                var bitmapFontLoaded = false
                if (fontName.startsWith("bm:")) {
                    val bmFontName = fontName.substring(3)
                    val bmDir = File(projectDir, "fonts_bm")

                    val fontFile = remember(bmFontName, projectDir) {
                        File(bmDir, "$bmFontName.fnt")
                    }

                    val bitmapFont = remember(fontFile.absolutePath) {
                        if (fontFile.exists()) {
                            try {
                                BitmapFont.parse(fontFile.readText(Charsets.UTF_8))
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }

                    if (bitmapFont != null) {
                        val atlasFile = remember(bmFontName, projectDir) {
                            // Prefer the atlas the .fnt descriptor points at (its `page file=`),
                            // then <name>.png, then any image whose name starts with the font name.
                            val pageName = bitmapFont.pages.firstOrNull()
                                ?.substringAfterLast('/')?.substringAfterLast('\\')
                            val byPage = pageName?.let { File(bmDir, it) }
                            when {
                                byPage?.exists() == true -> byPage
                                File(bmDir, "$bmFontName.png").exists() -> File(bmDir, "$bmFontName.png")
                                else -> bmDir.listFiles()?.find {
                                    it.name.startsWith(bmFontName, ignoreCase = true) &&
                                        it.extension.lowercase() in listOf("png", "jpg", "jpeg")
                                } ?: File(bmDir, "$bmFontName.png")
                            }
                        }

                        val atlasBitmap = rememberPreviewBitmap(atlasFile.absolutePath)

                        if (atlasBitmap != null) {
                            bitmapFontLoaded = true
                            // BM atlases are uppercase: CloX/UL always upper() every
                            // bitmap-font string (transform uc/lc included), so mixed-case
                            // renders uppercase to match the atlas glyphs.
                            val textToDraw = displayText.uppercase()

                            // Bitmap text is sized by text_size, exactly like the watch renderer:
                            // scale = 1.6 * text_size / fontHeight. anim_scale_x/y is a plain
                            // percent multiplier on top (100 = normal); a new text layer is
                            // seeded with 100/100, so it must map to 1.0 — NOT to a char-cell
                            // size, which made manually-added bitmap layers render huge/distorted.
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
                            val scaleX: Float = baseScale * animMul("anim_scale_x")
                            val scaleY: Float = baseScale * animMul("anim_scale_y")

                            var totalWidth = 0f
                            var lastCharId = -1
                            textToDraw.forEach { char ->
                                val charId = char.code
                                val bmChar = bitmapFont.chars[charId] ?: return@forEach
                                if (lastCharId != -1) {
                                    val kerningKey = (lastCharId.toLong() shl 32) or (charId.toLong() and 0xFFFFFFFFL)
                                    totalWidth += (bitmapFont.kernings[kerningKey] ?: 0) * scaleX
                                }
                                totalWidth += bmChar.xadvance * scaleX
                                lastCharId = charId
                            }

                            val alignment = layer.getAttr("alignment").ifEmpty { "cc" }

                            val shadowAttr = layer.getAttr("shadow")
                            val shadowDist = layer.getAttr("w_distance").toFloatOrNull() ?: 0f
                            val shadowAng = layer.getAttr("w_angle").toFloatOrNull() ?: 135f
                            val shadowOp = (layer.getAttr("w_opacity").toFloatOrNull() ?: 100f) / 100f
                            val shadowColStr = WmTagsData.evaluatePreview(layer.getAttr("w_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                            val shadowCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$shadowColStr")) } catch (e: Exception) { Color.White }

                            val outlineAttr = layer.getAttr("outline")
                            val oColStr = WmTagsData.evaluatePreview(layer.getAttr("o_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                            val oCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$oColStr")) } catch (e: Exception) { Color.White }
                            val oSize = layer.getAttr("o_size").toFloatOrNull() ?: if (outlineAttr == "Glow") 8f else 2f
                            val oOp = (layer.getAttr("o_opacity").toFloatOrNull() ?: 100f) / 100f

                            // DrawScope extension (glyph box derives from the receiver's
                            // size) so both the effects canvas and the shaded glyph host
                            // can draw the same glyphs.
                            fun androidx.compose.ui.graphics.drawscope.DrawScope.runTextDraw(
                                drawColor: Color, xOff: Float = 0f, yOff: Float = 0f, drawAlpha: Float = 1f
                            ) {
                                // WM's renderer (Cocos2D) multiplies the node color with the
                                // texture — same Modulate tint image layers use (see above) —
                                // so a colored/gradient glyph keeps its shading instead of
                                // being flattened to a solid SrcIn silhouette. Tinting with
                                // white (the default color) is a no-op, matching CloX.
                                val charFilter = try {
                                    androidx.compose.ui.graphics.ColorFilter.tint(
                                        drawColor,
                                        androidx.compose.ui.graphics.BlendMode.Modulate
                                    )
                                } catch (e: Exception) { null }
                                val baseX = when (alignment.getOrNull(1)) {
                                    'l' -> 0f
                                    'r' -> size.width - totalWidth
                                    else -> (size.width - totalWidth) / 2f
                                }
                                val totalHeight = (bitmapFont.chars.values.firstOrNull()?.height ?: bitmapFont.lineHeight) * scaleY
                                val startY = when (alignment.getOrNull(0)) {
                                    't' -> 0f
                                    'b' -> size.height - totalHeight
                                    else -> (size.height - totalHeight) / 2f
                                }
                                var curX = baseX + xOff
                                var lastId = -1
                                textToDraw.forEach { char ->
                                    val charId = char.code
                                    val bmChar = bitmapFont.chars[charId] ?: return@forEach
                                    if (lastId != -1) {
                                        val kerningKey = (lastId.toLong() shl 32) or (charId.toLong() and 0xFFFFFFFFL)
                                        curX += (bitmapFont.kernings[kerningKey] ?: 0) * scaleX
                                    }
                                    val drawX = curX + bmChar.xoffset * scaleX
                                    val drawY = startY + yOff + bmChar.yoffset * scaleY
                                    drawImage(
                                        image = atlasBitmap,
                                        srcOffset = IntOffset(bmChar.x, bmChar.y),
                                        srcSize = IntSize(bmChar.width, bmChar.height),
                                        dstOffset = IntOffset(drawX.toInt(), drawY.toInt()),
                                        dstSize = IntSize((bmChar.width * scaleX).toInt(), (bmChar.height * scaleY).toInt()),
                                        colorFilter = charFilter,
                                        alpha = drawAlpha
                                    )
                                    curX += bmChar.xadvance * scaleX
                                    lastId = charId
                                }
                            }

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                if (shadowAttr.isNotEmpty() && shadowOp > 0f && shadowDist > 0f) {
                                    val sCol = shadowCol.copy(alpha = shadowOp)
                                    val rad = ((shadowAng + 180f) * Math.PI / 180.0)
                                    val dx = (shadowDist * Math.cos(rad)).toFloat()
                                    val dy = -(shadowDist * Math.sin(rad)).toFloat()
                                    if (shadowAttr == "Flat") {
                                        // Long/flat shadow: solid trail of copies out to full distance.
                                        val steps = Math.abs(shadowDist).toInt().coerceIn(1, 64)
                                        for (i in steps downTo 1) {
                                            val t = i.toFloat() / steps
                                            runTextDraw(sCol, dx * t, dy * t)
                                        }
                                    } else {
                                        runTextDraw(sCol, dx, dy)
                                    }
                                }
                                // Outline (hard stroke) / Glow (rings) — sample evenly
                                // around a circle of radius oSize for a uniform edge.
                                if (outlineAttr == "Outline" && oOp > 0f && oSize > 0f) {
                                    val samples = 16
                                    for (k in 0 until samples) {
                                        val a = (2.0 * Math.PI * k / samples)
                                        runTextDraw(oCol, (oSize * Math.cos(a)).toFloat(), (oSize * Math.sin(a)).toFloat(), oOp)
                                    }
                                } else if (outlineAttr == "Glow" && oOp > 0f && oSize > 0f) {
                                    val numRings = maxOf(2, (kotlin.math.ceil(oSize / 2f)).toInt())
                                    val samples = 16
                                    for (ring in 1..numRings) {
                                        val radius = oSize * ring / numRings
                                        val alpha = oOp * (1f - (ring - 1).toFloat() / numRings) / numRings
                                        for (k in 0 until samples) {
                                            val a = (2.0 * Math.PI * k / samples)
                                            runTextDraw(oCol, (radius * Math.cos(a)).toFloat(), (radius * Math.sin(a)).toFloat(), alpha)
                                        }
                                    }
                                }
                                // Main text — a shader shades only the
                                // glyph fill (separate offscreen host
                                // below); the effect copies in this
                                // canvas keep their own colors.
                                if (!hasPostShader) runTextDraw(textColor)
                            }
                            if (hasPostShader) {
                                WmShadedTextHost(postShader, sU1, sU2, sU3, sU4, sU5) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        runTextDraw(textColor)
                                    }
                                }
                            }
                        }
                    }
                }

                if (!bitmapFontLoaded) {
                    val fontFamily = remember(fontName, projectDir) {
                        if (fontName.isEmpty()) desktopFontFamily(com.ailife.wftools.pc.graphics.Typeface.DEFAULT)
                        else {
                            val dir = File(projectDir, "fonts")
                            var target = File(dir, fontName)
                            if (!target.exists()) target = File(dir, "$fontName.ttf")
                            if (!target.exists()) target = File(dir, "$fontName.otf")
                            if (!target.exists()) {
                                val match = dir.listFiles()?.find { it.name.startsWith(fontName, ignoreCase = true) }
                                if (match != null) target = match
                            }
                            if (target.exists()) try { desktopFontFamily(com.ailife.wftools.pc.graphics.Typeface.createFromFile(target)) } catch (e: Exception) { desktopFontFamily(com.ailife.wftools.pc.graphics.Typeface.DEFAULT) } else desktopFontFamily(com.ailife.wftools.pc.graphics.Typeface.DEFAULT)
                        }
                    }

                    val shadowAttr = layer.getAttr("shadow")
                    val shadowDist = layer.getAttr("w_distance").toFloatOrNull() ?: 0f
                    val shadowAng = layer.getAttr("w_angle").toFloatOrNull() ?: 135f
                    val shadowOp = (layer.getAttr("w_opacity").toFloatOrNull() ?: 100f) / 100f
                    val shadowColStr = WmTagsData.evaluatePreview(layer.getAttr("w_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                    val shadowCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$shadowColStr")) } catch (e: Exception) { Color.White }

                    val outlineAttr = layer.getAttr("outline")
                    val oColStr = WmTagsData.evaluatePreview(layer.getAttr("o_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                    val oCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$oColStr")) } catch (e: Exception) { Color.White }
                    val oSize = layer.getAttr("o_size").toFloatOrNull() ?: if (outlineAttr == "Glow") 8f else 2f
                    val oOp = (layer.getAttr("o_opacity").toFloatOrNull() ?: 100f) / 100f

                    val sp = with(density) { (textSizePx * 1.28f).toSp() }
                    // Tight line spacing so multi-line text matches WatchMaker:
                    // line advance = 1.4 × text_size, no extra font padding/leading,
                    // and each line center-justified relative to the widest line.
                    val tightTextStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = fontFamily,
                        fontSize = sp,
                        lineHeight = with(density) { (textSizePx * 1.4f).toSp() },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                        )
                    )
                    val transformedText = applyCaseTransform(displayText, layer.getAttr("transform"))
                    @Composable
                    fun DrawText(c: Color, offX: androidx.compose.ui.unit.Dp = 0.dp, offY: androidx.compose.ui.unit.Dp = 0.dp) {
                        androidx.compose.material3.Text(
                            text = transformedText,
                            color = c,
                            softWrap = false,
                            style = tightTextStyle,
                            modifier = Modifier.offset(offX, offY).wrapContentWidth(unbounded = true).wrapContentHeight(unbounded = true)
                        )
                    }

                    // The layer box width is an estimate (maxLen × size × 0.6); WM
                    // justifies the rendered text at the anchor, so edge-anchored
                    // text must hug the matching box edge or it drifts by half the
                    // estimation error (string-dependent).
                    val wmTextAlign = layer.getAttr("alignment").ifEmpty { "cc" }
                    val textBoxAlign = when (wmTextAlign) {
                        "tl" -> Alignment.TopStart; "tc" -> Alignment.TopCenter; "tr" -> Alignment.TopEnd
                        "cl" -> Alignment.CenterStart; "cr" -> Alignment.CenterEnd
                        "bl" -> Alignment.BottomStart; "bc" -> Alignment.BottomCenter; "br" -> Alignment.BottomEnd
                        else -> Alignment.Center
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = textBoxAlign
                    ) {
                        // Shadow (Drop or Flat). Light source angle: shadow falls 180° opposite.
                        if (shadowAttr.isNotEmpty() && shadowOp > 0f && shadowDist > 0f) {
                            val rad = ((shadowAng + 180f) * Math.PI / 180.0)
                            val dx = (shadowDist * Math.cos(rad)).toFloat()
                            val dy = -(shadowDist * Math.sin(rad)).toFloat()
                            val sCol = shadowCol.copy(alpha = shadowOp)
                            if (shadowAttr == "Flat") {
                                val steps = Math.abs(shadowDist).toInt().coerceIn(1, 64)
                                for (i in steps downTo 1) {
                                    val t = i.toFloat() / steps
                                    DrawText(sCol, with(density) { (dx * t).toDp() }, with(density) { (dy * t).toDp() })
                                }
                            } else {
                                DrawText(sCol, with(density) { dx.toDp() }, with(density) { dy.toDp() })
                            }
                        }
                        // Outline (hard stroke) / Glow (rings) — sample evenly
                        // around a circle of radius oSize for a uniform edge.
                        if (outlineAttr == "Outline" && oOp > 0f && oSize > 0f) {
                            val samples = 16
                            for (k in 0 until samples) {
                                val a = (2.0 * Math.PI * k / samples)
                                DrawText(oCol.copy(alpha = oOp),
                                    with(density) { (oSize * Math.cos(a)).toFloat().toDp() },
                                    with(density) { (oSize * Math.sin(a)).toFloat().toDp() })
                            }
                        } else if (outlineAttr == "Glow" && oOp > 0f && oSize > 0f) {
                            val numRings = maxOf(2, (kotlin.math.ceil(oSize / 2f)).toInt())
                            val samples = 16
                            for (ring in 1..numRings) {
                                val radius = oSize * ring / numRings
                                val alpha = oOp * (1f - (ring - 1).toFloat() / numRings) / numRings
                                for (k in 0 until samples) {
                                    val a = (2.0 * Math.PI * k / samples)
                                    DrawText(oCol.copy(alpha = alpha),
                                        with(density) { (radius * Math.cos(a)).toFloat().toDp() },
                                        with(density) { (radius * Math.sin(a)).toFloat().toDp() })
                                }
                            }
                        }
                        // Main text — a shader shades the glyphs ONLY
                        // (own offscreen layer); the effect copies drawn
                        // above stay plain beneath it.
                        if (hasPostShader) {
                            WmShadedTextHost(postShader, sU1, sU2, sU3, sU4, sU5) {
                                DrawText(textColor)
                            }
                        } else {
                            DrawText(textColor)
                        }
                    }
                }
            }
            "shape", "rounded" -> {
                // Dim mode uses color_dim (AOD color) when the layer defines it.
                val shapeColorAttr = if (!isBright && layer.getAttr("color_dim").isNotBlank()) "color_dim" else "color"
                val shapeType = if (layer.layerType == "rounded") "Square" else layer.getAttr("shape", "Square")
                // Evaluate like every other live attribute: faces write color="var_col"
                // or color="wf_col(0)" — a raw read would feed the expression string
                // to parseColor and silently drop the tint (layer renders white).
                val colorEval = WmTagsData.evaluatePreview(layer.getAttr(shapeColorAttr, "ffffff"), previewState)
                val colorRaw = colorEval.ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                val fillColor = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$colorRaw")) } catch (e: Exception) { Color.White }

                val shadow = layer.getAttr("shadow")
                val shadowColorStr = WmTagsData.evaluatePreview(layer.getAttr("w_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                val shadowColor = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$shadowColorStr")) } catch (e: Exception) { Color.White }
                val shadowDist = layer.getAttr("w_distance").toFloatOrNull() ?: 0f
                val shadowAngle = layer.getAttr("w_angle").toFloatOrNull() ?: 135f
                val shadowOpacity = (layer.getAttr("w_opacity").toFloatOrNull() ?: 100f) / 100f

                val outline = layer.getAttr("outline")
                val oColorStr = WmTagsData.evaluatePreview(layer.getAttr("o_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                val oColor = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$oColorStr")) } catch (e: Exception) { Color.White }
                val oSize = layer.getAttr("o_size").toFloatOrNull() ?: if (outline == "Glow") 8f else 2f
                val oOpacity = (layer.getAttr("o_opacity").toFloatOrNull() ?: 100f) / 100f

                val radiusRaw = if (layer.layerType == "rounded") layer.getAttr("radius").toFloatOrNull() ?: 0f else 0f
                val roundedCornerType = layer.getAttr("corner_type", "1").toIntOrNull() ?: 1

                // Shader fills are handled by the universal post-process pass
                // (drawWmShadedContent); the shape itself just draws its plain fill.
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    fun buildPath(): androidx.compose.ui.graphics.Path = when (shapeType) {
                        "Square" -> {
                            if (radiusRaw > 0f) {
                                val r = radiusRaw.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
                                androidx.compose.ui.graphics.Path().also {
                                    it.asDesktopPath().addRoundRect(
                                        com.ailife.wftools.pc.graphics.RectF(0f, 0f, w, h),
                                        wmCornerRadii(roundedCornerType, r),
                                        com.ailife.wftools.pc.graphics.Path.Direction.CW
                                    )
                                }
                            } else androidx.compose.ui.graphics.Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
                        }
                        "Circle" -> androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
                        "Triangle" -> androidx.compose.ui.graphics.Path().apply {
                            moveTo(w / 2f, 0f); lineTo(w, h); lineTo(0f, h); close()
                        }
                        "Pentagon" -> androidx.compose.ui.graphics.Path().apply {
                            moveTo(w / 2f, 0f); lineTo(w, h * 0.4f); lineTo(w * 0.8f, h)
                            lineTo(w * 0.2f, h); lineTo(0f, h * 0.4f); close()
                        }
                        "Hexagon" -> androidx.compose.ui.graphics.Path().apply {
                            moveTo(w / 2f, 0f); lineTo(w, h * 0.25f); lineTo(w, h * 0.75f)
                            lineTo(w / 2f, h); lineTo(0f, h * 0.75f); lineTo(0f, h * 0.25f); close()
                        }
                        "Star" -> androidx.compose.ui.graphics.Path().apply {
                            moveTo(w / 2f, 0f); lineTo(w * 0.65f, h * 0.35f); lineTo(w, h * 0.35f)
                            lineTo(w * 0.7f, h * 0.6f); lineTo(w * 0.85f, h); lineTo(w / 2f, h * 0.75f)
                            lineTo(w * 0.15f, h); lineTo(w * 0.3f, h * 0.6f); lineTo(0f, h * 0.35f)
                            lineTo(w * 0.35f, h * 0.35f); close()
                        }
                        "Heart" -> androidx.compose.ui.graphics.Path().apply {
                            moveTo(w / 2f, h * 0.2f)
                            cubicTo(w * 0.35f, 0f, 0f, h * 0.06f, w * 0.035f, h * 0.4f)
                            cubicTo(w * 0.07f, h * 0.66f, w * 0.43f, h * 0.83f, w / 2f, h)
                            cubicTo(w * 0.57f, h * 0.83f, w * 0.93f, h * 0.66f, w * 0.965f, h * 0.4f)
                            cubicTo(w, h * 0.06f, w * 0.65f, 0f, w / 2f, h * 0.2f)
                        }
                        else -> androidx.compose.ui.graphics.Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
                    }
                    val shapePath = buildPath()

                    fun drawPathWith(c: Color) { drawPath(shapePath, color = c) }
                    fun drawPathStroke(c: Color, strokeW: Float) {
                        drawPath(shapePath, color = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW))
                    }

                    // ── 1) Shadow (Drop or Flat) ──────────────────────────
                    // Light source angle: shadow falls 180° opposite.
                    if (shadow.isNotEmpty() && shadowOpacity > 0f && shadowDist > 0f) {
                        val rad = (shadowAngle + 180f) * Math.PI / 180.0
                        val dx = (shadowDist * Math.cos(rad)).toFloat()
                        val dy = -(shadowDist * Math.sin(rad)).toFloat() // Y is down on screen
                        val sColor = shadowColor.copy(alpha = shadowOpacity)
                        if (shadow == "Flat") {
                            // Long/flat shadow: solid trail of copies out to full distance.
                            val steps = Math.abs(shadowDist).toInt().coerceIn(1, 64)
                            for (i in steps downTo 1) {
                                val t = i.toFloat() / steps
                                translate(dx * t, dy * t) { drawPathWith(sColor) }
                            }
                        } else {
                            translate(dx, dy) { drawPathWith(sColor) }
                        }
                    }

                    // ── 2) Outline (hard stroke) or Glow (soft halo) ─────
                    if (outline == "Outline" && oOpacity > 0f) {
                        drawPathStroke(oColor.copy(alpha = oOpacity), oSize)
                    } else if (outline == "Glow" && oOpacity > 0f && oSize > 0f) {
                        val numRings = maxOf(1, (kotlin.math.ceil(oSize / 2f)).toInt())
                        for (ring in 1..numRings) {
                            val ringW = oSize * ring / numRings
                            val ringAlpha = oOpacity * (1f - (ring - 1).toFloat() / numRings)
                            drawPathStroke(oColor.copy(alpha = ringAlpha), ringW * 2f)
                        }
                    }

                    // Main fill — plain color; shader fills handled by post-process.
                    drawPathWith(fillColor)
                }
            }
            "progress" -> {
                val pctRaw = WmTagsData.evaluatePreview(layer.getAttr("pct_complete", "0"), previewState)
                val pct = (pctRaw.toFloatOrNull() ?: 0f).coerceIn(0f, 100f) / 100f
                val angleRaw = layer.getAttr("angle", "90").toFloatOrNull() ?: 90f
                val isVertical = angleRaw == 0f || angleRaw == 180f
                val radiusRaw = layer.getAttr("radius").toFloatOrNull() ?: 50f
                val cornerType = layer.getAttr("corner_type", "1").toIntOrNull() ?: 1
                // CloX default end_style is flat (round only when explicitly set).
                val endStyle = layer.getAttr("end_style", "flat")
                val margin = layer.getAttr("margin").toFloatOrNull() ?: 0f

                val color1Str = WmTagsData.evaluatePreview(layer.getAttr("color", "00ff00"), previewState).trimStart('#').padStart(6, '0')
                val color2Str = WmTagsData.evaluatePreview(layer.getAttr("color2", color1Str), previewState).trimStart('#').padStart(6, '0')
                val color3Str = WmTagsData.evaluatePreview(layer.getAttr("color3", "333333"), previewState).trimStart('#').padStart(6, '0')
                val color4Str = WmTagsData.evaluatePreview(layer.getAttr("color4", "ffffff"), previewState).trimStart('#').padStart(6, '0')

                val c1 = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$color1Str")) } catch(e: Exception) { Color.Green }
                val c2 = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$color2Str")) } catch(e: Exception) { c1 }
                val c3 = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$color3Str")) } catch(e: Exception) { Color.DarkGray }
                val c4 = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$color4Str")) } catch(e: Exception) { Color.White }
                val outOpacity = (layer.getAttr("outside_opacity").toFloatOrNull() ?: 100f) / 100f

                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f; val cy = h / 2f
                    // CloX: radius is canvas UNITS clamped to half-min, not a percent.
                    val baseR = minOf(radiusRaw, w / 2f, h / 2f).coerceAtLeast(0f)
                    val tw = (w - 2 * margin).coerceAtLeast(0f)
                    val th = (h - 2 * margin).coerceAtLeast(0f)
                    val innerR = minOf(baseR, tw / 2f, th / 2f).coerceAtLeast(0f)

                    fun pathForRect(left: Float, top: Float, right: Float, bottom: Float, radii: FloatArray): androidx.compose.ui.graphics.Path {
                        return androidx.compose.ui.graphics.Path().also {
                            it.asDesktopPath().addRoundRect(
                                com.ailife.wftools.pc.graphics.RectF(left, top, right, bottom),
                                radii, com.ailife.wftools.pc.graphics.Path.Direction.CW
                            )
                        }
                    }

                    // Track + border exist ONLY when margin>0 (CloX): the c4 border
                    // pill, then the c3 track blended over it by outside_opacity. At
                    // margin=0 the unfilled area is transparent (a margin=0 progress
                    // shows only its fill — matches CloX; the converter emits margin=0).
                    if (margin > 0f && tw > 0f && th > 0f) {
                        drawPath(pathForRect(0f, 0f, w, h, wmCornerRadii(cornerType, baseR)), color = c4)
                        drawPath(
                            pathForRect(margin, margin, margin + tw, margin + th, wmCornerRadii(cornerType, innerR)),
                            color = androidx.compose.ui.graphics.lerp(c4, c3, outOpacity)
                        )
                    }

                    // Fill: gradient color→color2 along the FILLED span — c1 at the
                    // start edge, c2 at the leading edge (not across the whole box).
                    if (pct > 0f && tw > 0f && th > 0f) {
                        val pw = if (isVertical) tw else tw * pct
                        val ph = if (isVertical) th * pct else th
                        val pl = margin
                        val pt = if (isVertical) margin + th * (1f - pct) else margin
                        val gStart = if (isVertical) Offset(pl, margin + th) else Offset(pl, cy)
                        val gEnd = if (isVertical) Offset(pl, pt) else Offset(pl + pw, cy)
                        val fillBrush = if (c1 == c2) androidx.compose.ui.graphics.SolidColor(c1)
                            else androidx.compose.ui.graphics.Brush.linearGradient(listOf(c1, c2), start = gStart, end = gEnd)
                        drawPath(
                            pathForRect(pl, pt, pl + pw, pt + ph, wmFillRadii(cornerType, innerR, endStyle, isVertical)),
                            brush = fillBrush
                        )
                    }
                }
            }
            "ring" -> {
                val rOuter = layer.getAttr("radius_outer").toFloatOrNull() ?: 100f
                // CloX defaults radius_inner to radius_outer*0.8 (not a fixed 80).
                val rInner = layer.getAttr("radius_inner").toFloatOrNull() ?: (rOuter * 0.8f)
                val thickness = (rOuter - rInner).coerceAtLeast(1f)
                // End-cap radius (CloX's exposed-but-currently-dead "Corner Radius"
                // ring field — see CloX docs/PENDING-RING-RADIUS-CAP.md). Defaults to
                // the full half-thickness disc (today's unconditional round cap) so
                // faces that never set `radius` render exactly as before.
                val capR = (layer.getAttr("radius").toFloatOrNull() ?: (thickness / 2f))
                    .coerceIn(0f, thickness / 2f)

                val angleTotal = layer.getAttr("angle_total").toFloatOrNull() ?: 360f
                // CloX defaults angle to 0 (empty), not 360 (full) — a ring missing
                // `angle` should render unfilled, not completely filled.
                val angleRaw = WmTagsData.evaluatePreview(layer.getAttr("angle", "0"), previewState)
                val fillAngle = wmEvalFloat(angleRaw).coerceIn(0f, angleTotal)
                val isClockwise = layer.getAttr("is_clockwise", "Y") == "Y"
                val outOpacity = (layer.getAttr("outside_opacity").toFloatOrNull() ?: 100f) / 100f

                val color1Str = WmTagsData.evaluatePreview(layer.getAttr("color", "00ff00"), previewState).trimStart('#').padStart(6, '0')
                val color2Str = WmTagsData.evaluatePreview(layer.getAttr("color2", color1Str), previewState).trimStart('#').padStart(6, '0')
                val color3Str = WmTagsData.evaluatePreview(layer.getAttr("color3", "003300"), previewState).trimStart('#').padStart(6, '0')
                val c1 = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$color1Str")) } catch(e: Exception) { Color.Green }
                val c2 = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$color2Str")) } catch(e: Exception) { c1 }
                val c3 = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$color3Str")) } catch(e: Exception) { Color.DarkGray }

                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeW = thickness
                    val arcSize = size.width - strokeW
                    val topLeft = strokeW / 2f

                    // Renderer (CloX wf_render ring): track spans angle_total from
                    // 12 o'clock in the is_clockwise direction; the fill reveals a
                    // color→color2 gradient over `angle`. Compose degrees: 0°=3 o'clock,
                    // +ve clockwise, so 12 o'clock = -90°.
                    val dir = if (isClockwise) 1f else -1f
                    val arcRect = androidx.compose.ui.geometry.Size(arcSize, arcSize)
                    val arcTL = Offset(topLeft, topLeft)
                    // Caps are always drawn manually now (see drawCap below) — the arc
                    // body itself is always a flat Butt cut.
                    val capStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Butt)
                    val rc = arcSize / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // End cap = a chamfered-corner tab (same corner-radius idea as
                    // rounded/progress), not a shrunk inscribed circle: it always spans
                    // the FULL stroke thickness (both edges stay straight where they
                    // meet the arc body — those two corners are sharp/unrounded) and
                    // protrudes `capR` beyond the flat cut, with only the two far
                    // (protruding) corners rounded by capR. At capR == thickness/2 this
                    // degenerates to an exact semicircle (classic round cap); at
                    // capR == 0 there is no tab at all (flat cap).
                    fun drawCap(sweepDeg: Float, isEnd: Boolean, color: Color, alpha: Float = 1f) {
                        if (capR <= 0.05f) return
                        // Bulge points away from the body this cap terminates: after
                        // the end of a segment (continuing forward), before the start
                        // (continuing backward).
                        val bulgeSign = if (isEnd) dir else -dir
                        val rotationDeg = -90f + dir * sweepDeg
                        rotate(degrees = rotationDeg, pivot = center) {
                            val x0 = center.x + rc - thickness / 2f
                            val x1 = center.x + rc + thickness / 2f
                            val farR = androidx.compose.ui.geometry.CornerRadius(capR, capR)
                            val nearR = androidx.compose.ui.geometry.CornerRadius.Zero
                            val rect = if (bulgeSign > 0)
                                androidx.compose.ui.geometry.Rect(x0, center.y, x1, center.y + capR)
                            else
                                androidx.compose.ui.geometry.Rect(x0, center.y - capR, x1, center.y)
                            val roundRect = if (bulgeSign > 0)
                                androidx.compose.ui.geometry.RoundRect(
                                    rect, topLeft = nearR, topRight = nearR,
                                    bottomRight = farR, bottomLeft = farR)
                            else
                                androidx.compose.ui.geometry.RoundRect(
                                    rect, topLeft = farR, topRight = farR,
                                    bottomRight = nearR, bottomLeft = nearR)
                            drawPath(
                                androidx.compose.ui.graphics.Path().apply { addRoundRect(roundRect) },
                                color = color, alpha = alpha
                            )
                        }
                    }

                    // Track (unfilled) = color3 at outside_opacity
                    drawArc(
                        color = c3,
                        startAngle = -90f,
                        sweepAngle = dir * angleTotal,
                        useCenter = false,
                        topLeft = arcTL,
                        size = arcRect,
                        style = capStroke,
                        alpha = outOpacity
                    )
                    if (fillAngle < angleTotal) {
                        drawCap(fillAngle, isEnd = false, color = c3, alpha = outOpacity)
                        drawCap(angleTotal, isEnd = true, color = c3, alpha = outOpacity)
                    }

                    // Fill: gradient color→color2 stepped along the filled arc so the
                    // colour follows the sweep direction (Compose sweepGradient starts
                    // at 3 o'clock and can't be arc-aligned, so segment it). The segments
                    // tile edge-to-edge (Butt caps); the two real ends get the chamfered
                    // cap tab drawn separately above/below.
                    if (fillAngle > 0f) {
                        if (c1 == c2) {
                            drawArc(
                                color = c1,
                                startAngle = -90f,
                                sweepAngle = dir * fillAngle,
                                useCenter = false,
                                topLeft = arcTL,
                                size = arcRect,
                                style = capStroke
                            )
                        } else {
                            // Gradient parameter follows the launcher
                            // (fillArc col = lerp(color, color2, sweep/total)):
                            // u_1 at 12 o'clock (sweep 0), u_2 reached only at
                            // sweep == angle_total — NOT at the fill's leading
                            // edge, so a partially-filled ring stops partway
                            // through the gradient like the launcher.
                            val segs = kotlin.math.ceil(fillAngle / 4f).toInt().coerceAtLeast(1)
                            val segSweep = fillAngle / segs
                            for (i in 0 until segs) {
                                val s0 = segSweep * i
                                val s1 = segSweep * (i + 1)
                                val t = (s0 + s1) / 2f / angleTotal.coerceAtLeast(1f)
                                drawArc(
                                    color = androidx.compose.ui.graphics.lerp(c1, c2, t),
                                    startAngle = -90f + dir * s0,
                                    sweepAngle = dir * segSweep,
                                    useCenter = false,
                                    topLeft = arcTL,
                                    size = arcRect,
                                    style = capStroke
                                )
                            }
                        }
                        drawCap(0f, isEnd = false, color = c1)
                        drawCap(fillAngle, isEnd = true,
                            color = androidx.compose.ui.graphics.lerp(c1, c2, fillAngle / angleTotal.coerceAtLeast(1f)))
                    }
                }
            }
            "text_curved" -> {
                val displayText = applyCaseTransform(
                    WmTagsData.evaluatePreview(layer.getAttr("text"), previewState),
                    layer.getAttr("transform"))
                val textSizePx = layer.getAttr("text_size", "24").toFloatOrNull() ?: 24f
                val radius = layer.getAttr("radius", "100").toFloatOrNull() ?: 100f
                val curveDir = layer.getAttr("curve_dir", "Up")

                val colorHex = WmTagsData.evaluatePreview(layer.getAttr("color", "ffffff"), previewState).trimStart('#').padStart(6, '0')
                val textColor = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$colorHex")) } catch (e: Exception) { Color.White }

                val shadowAttr = layer.getAttr("shadow")
                val shadowDist = layer.getAttr("w_distance").toFloatOrNull() ?: 0f
                val shadowAng = layer.getAttr("w_angle").toFloatOrNull() ?: 135f
                val shadowOp = (layer.getAttr("w_opacity").toFloatOrNull() ?: 100f) / 100f
                val shadowColStr = WmTagsData.evaluatePreview(layer.getAttr("w_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                val shadowCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$shadowColStr")) } catch (e: Exception) { Color.White }

                val outlineAttr = layer.getAttr("outline")
                val oColStr = WmTagsData.evaluatePreview(layer.getAttr("o_color", "ffffff"), previewState).ifBlank { "ffffff" }.trimStart('#').padStart(6, '0')
                val oCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$oColStr")) } catch (e: Exception) { Color.White }
                val oSize = layer.getAttr("o_size").toFloatOrNull() ?: if (outlineAttr == "Glow") 8f else 2f
                val oOp = (layer.getAttr("o_opacity").toFloatOrNull() ?: 100f) / 100f

                val fontName = layer.getAttr("font")
                // The arc is drawn with a native Paint — it needs the project
                // Typeface itself, not a Compose FontFamily
                val curvedTypeface = remember(fontName, projectDir) {
                    if (fontName.isEmpty()) com.ailife.wftools.pc.graphics.Typeface.DEFAULT
                    else {
                        val dir = File(projectDir, "fonts")
                        var target = File(dir, fontName)
                        if (!target.exists()) target = File(dir, "$fontName.ttf")
                        if (!target.exists()) target = File(dir, "$fontName.otf")
                        if (target.exists()) try {
                            com.ailife.wftools.pc.graphics.Typeface.createFromFile(target)
                        } catch (e: Exception) { com.ailife.wftools.pc.graphics.Typeface.DEFAULT }
                        else com.ailife.wftools.pc.graphics.Typeface.DEFAULT
                    }
                }

                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { composeCanvas ->
                        val w = size.width
                        val h = size.height
                        val cx = w / 2f
                        val cy = if (curveDir == "Up") h else 0f
                        fun paintFor(color: Color, alpha: Float = 1f): com.ailife.wftools.pc.graphics.Paint = com.ailife.wftools.pc.graphics.Paint().apply {
                            isAntiAlias = true
                            textSize = textSizePx * 1.28f
                            this.color = color.copy(alpha = color.alpha * alpha).toArgb()
                            textAlign = com.ailife.wftools.pc.graphics.Paint.Align.CENTER
                            typeface = curvedTypeface
                        }

                        // Per-glyph placement matching WM Studio / CloX (wf_render
                        // text_curved, WM_STUDIO_2.0_EDITOR §4.7): each glyph spans an
                        // arc slot = glyphWidthPx * 60 / radius — a FLAT 60°/px, not
                        // the true arc-length 57.3°/px that drawTextOnPath used (which
                        // packed strings ~5% too tight). Spaces advance by 0.3*fontPx.
                        // The string is centered on the arc midpoint (top for "Up",
                        // bottom for "Down"); glyph rotation follows the tangent.
                        fun drawAt(hOffset: Float, vOffset: Float, p: com.ailife.wftools.pc.graphics.Paint) {
                            val fontPx = p.textSize
                            val spaceW = 0.3f * fontPx
                            val widths = FloatArray(displayText.length)
                            p.getTextWidths(displayText, widths)
                            val slotDeg = FloatArray(displayText.length)
                            var totalDeg = 0f
                            for (i in displayText.indices) {
                                val wpx = if (displayText[i] == ' ') spaceW else widths[i]
                                slotDeg[i] = wpx * 60f / radius
                                totalDeg += slotDeg[i]
                            }
                            val up = curveDir == "Up"
                            var wdeg = -totalDeg / 2f
                            for (i in displayText.indices) {
                                wdeg += slotDeg[i] / 2f
                                val rad = Math.toRadians(wdeg.toDouble())
                                val sinv = kotlin.math.sin(rad).toFloat()
                                val cosv = kotlin.math.cos(rad).toFloat()
                                val gx = cx + radius * sinv + hOffset
                                val gy = (if (up) cy - radius * cosv else cy + radius * cosv) + vOffset
                                composeCanvas.nativeCanvas.save()
                                composeCanvas.nativeCanvas.translate(gx, gy)
                                composeCanvas.nativeCanvas.rotate(if (up) wdeg else -wdeg)
                                composeCanvas.nativeCanvas.drawText(displayText, i, i + 1, 0f, 0f, p)
                                composeCanvas.nativeCanvas.restore()
                                wdeg += slotDeg[i] / 2f
                            }
                        }

                        // Shadow. Light source angle: shadow falls 180° opposite.
                        if (shadowAttr.isNotEmpty() && shadowOp > 0f && shadowDist > 0f) {
                            val sCol = shadowCol.copy(alpha = shadowOp)
                            val rad = ((shadowAng + 180f) * Math.PI / 180.0)
                            val dx = (shadowDist * Math.cos(rad)).toFloat()
                            val dy = -(shadowDist * Math.sin(rad)).toFloat()
                            if (shadowAttr == "Flat") {
                                val steps = Math.abs(shadowDist).toInt().coerceIn(1, 64)
                                for (i in steps downTo 1) {
                                    val t = i.toFloat() / steps
                                    drawAt(dx * t, dy * t, paintFor(sCol))
                                }
                            } else {
                                drawAt(dx, dy, paintFor(sCol))
                            }
                        }
                        // Outline / Glow (rings)
                        if (outlineAttr == "Outline" && oOp > 0f && oSize > 0f) {
                            val samples = 16
                            for (k in 0 until samples) {
                                val a = (2.0 * Math.PI * k / samples)
                                drawAt((oSize * Math.cos(a)).toFloat(), (oSize * Math.sin(a)).toFloat(), paintFor(oCol, oOp))
                            }
                        } else if (outlineAttr == "Glow" && oOp > 0f && oSize > 0f) {
                            val numRings = maxOf(2, (kotlin.math.ceil(oSize / 2f)).toInt())
                            val samples = 16
                            for (ring in 1..numRings) {
                                val radiusR = oSize * ring / numRings
                                val alpha = oOp * (1f - (ring - 1).toFloat() / numRings) / numRings
                                for (k in 0 until samples) {
                                    val a = (2.0 * Math.PI * k / samples)
                                    drawAt((radiusR * Math.cos(a)).toFloat(), (radiusR * Math.sin(a)).toFloat(), paintFor(oCol, alpha))
                                }
                            }
                        }
                        // Main text
                        drawAt(0f, 0f, paintFor(textColor))
                    }
                }
            }
            "markers" -> {
                val radius = WmTagsData.evaluatePreview(layer.getAttr("radius", "200"), previewState).toFloatOrNull() ?: 200f
                val count = (WmTagsData.evaluatePreview(layer.getAttr("m_count", "12"), previewState)
                    .toFloatOrNull() ?: 12f).toInt().coerceIn(1, 360)
                // Renderer: shape drawn at full m_width × m_height; placement radius
                // pulled in by m_height/2 (CloX: radius - m_height/2).
                val mw = WmTagsData.evaluatePreview(layer.getAttr("m_width", "10"), previewState).toFloatOrNull() ?: 10f
                val mh = WmTagsData.evaluatePreview(layer.getAttr("m_height", "20"), previewState).toFloatOrNull() ?: 20f
                val rOffset = mh / 2f
                val shapeType = layer.getAttr("shape", stringResource(RendererText.wm_square))
                val squarify = WmTagsData.evaluatePreview(layer.getAttr("squarify", "0"), previewState).toFloatOrNull() ?: 0f
                val mColStr = WmTagsData.evaluatePreview(layer.getAttr("color", "ffffff"), previewState).trimStart('#').padStart(6, '0')
                val mCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$mColStr")) } catch (e: Exception) { Color.White }
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val a = 2.0 * Math.PI / count
                    val rotPI = -Math.PI / 2.0
                    for (i in 0 until count) {
                        val angle = a * i + rotPI
                        val adjR = wmSquarifyRadius(radius - rOffset, angle, squarify)
                        // Renderer places markers at (R·cosθ, −R·sinθ); mapping that
                        // Y-up point into our Y-down canvas gives cy + R·sinθ.
                        val px = cx + (adjR * kotlin.math.cos(angle)).toFloat()
                        val py = cy + (adjR * kotlin.math.sin(angle)).toFloat()
                        // Rotate the tip-up shape so it points radially OUTWARD (CloX).
                        // The old `- 180` flipped triangles to point toward the center.
                        val deg = (180.0 * a * i / Math.PI).toFloat()
                        rotate(deg, Offset(px, py)) {
                            wmDrawMarkerShape(shapeType, px, py, mw, mh, mCol)
                        }
                    }
                }
            }
            "markers_hm" -> {
                // WM Studio r2: per-preset K table (width×height); hour ticks 1.6×w/1.8×h;
                // Circle/Triangle glyphs; None skipped; minute ticks omit the 12 hour slots.
                val radius = WmTagsData.evaluatePreview(layer.getAttr("radius", "222"), previewState).toFloatOrNull() ?: 222f
                val mHour = layer.getAttr("m_hour", "Medium")
                val mMinute = layer.getAttr("m_minute", "Medium")
                val squarify = WmTagsData.evaluatePreview(layer.getAttr("squarify", "0"), previewState).toFloatOrNull() ?: 0f
                val cColStr = WmTagsData.evaluatePreview(layer.getAttr("color", "ffffff"), previewState).trimStart('#').padStart(6, '0')
                val c2ColStr = WmTagsData.evaluatePreview(layer.getAttr("color2", cColStr), previewState).trimStart('#').padStart(6, '0')
                val cCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$cColStr")) } catch (e: Exception) { Color.White }   // hour
                val c2Col = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$c2ColStr")) } catch (e: Exception) { cCol }        // minute
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    fun drawSet(minute: Boolean) {
                        val preset = WM_HM_PRESETS[if (minute) mMinute else mHour] ?: WM_HM_PRESETS["Medium"]!!
                        val (shape, pw, ph) = preset
                        if (pw <= 0f) return   // None
                        var l = if (minute) pw else 1.6f * pw          // width
                        var c = if (minute) ph else 1.8f * ph          // length
                        when (shape) {
                            "Circle" -> { if (!minute) c *= 1.6f; l = c }
                            "Triangle" -> if (!minute) { l *= 1.5f; c *= 1.5f }
                        }
                        val d = if (minute) 60 else 12
                        val col = if (minute) c2Col else cCol
                        for (t in 0 until d) {
                            if (minute && t % 5 == 0) continue     // skip the hour positions
                            val u = t.toDouble() / d * 2.0 * Math.PI
                            val adjR = wmSquarifyRadius(radius, u, squarify)
                            // outer edge at radius; glyph center pulled inward by length/2
                            val gx = cx + (adjR * kotlin.math.cos(u)).toFloat() - (kotlin.math.cos(u) * (c / 2)).toFloat()
                            val gy = cy + (adjR * kotlin.math.sin(u)).toFloat() - (kotlin.math.sin(u) * (c / 2)).toFloat()
                            val degRot = Math.toDegrees(u + Math.PI / 2).toFloat()
                            rotate(degRot, Offset(gx, gy)) {
                                when (shape) {
                                    "Circle" -> drawOval(col, topLeft = Offset(gx - l / 2, gy - c / 2),
                                        size = androidx.compose.ui.geometry.Size(l, c))
                                    "Triangle" -> {
                                        val path = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(gx, gy + c / 2); lineTo(gx - l / 2, gy - c / 2); lineTo(gx + l / 2, gy - c / 2); close()
                                        }
                                        drawPath(path, col)
                                    }
                                    else -> drawRect(col, topLeft = Offset(gx - l / 2, gy - c / 2),
                                        size = androidx.compose.ui.geometry.Size(l, c))
                                }
                            }
                        }
                    }
                    drawSet(minute = true)
                    drawSet(minute = false)
                }
            }
            "text_ring" -> {
                // angle_start/angle_end (and radius/size/squarify) may be WM
                // expressions, e.g. angle_end="360+165" for a full ring. Parse
                // them through the expression evaluator, not a bare toFloat.
                val radius = WmTagsData.evaluatePreview(layer.getAttr("radius", "100"), previewState).toFloatOrNull() ?: 100f
                val textSizePx = WmTagsData.evaluatePreview(layer.getAttr("text_size", "24"), previewState).toFloatOrNull() ?: 24f
                val ringType = layer.getAttr("ring_type", "1-12")
                val showEvery = (layer.getAttr("show_every", "1").toIntOrNull() ?: 1).coerceAtLeast(1)
                val angleStart = WmTagsData.evaluatePreview(layer.getAttr("angle_start", "0"), previewState).toFloatOrNull() ?: 0f
                val angleEnd = WmTagsData.evaluatePreview(layer.getAttr("angle_end", "360"), previewState).toFloatOrNull() ?: 360f
                val rotatedText = layer.getAttr("rotated_text", "n")
                val squarify = WmTagsData.evaluatePreview(layer.getAttr("squarify", "0"), previewState).toFloatOrNull() ?: 0f
                val transform = layer.getAttr("transform", "n")
                // Renderer: hide_text is comma-separated, spaces stripped, compared
                // numerically against the loop value (the numeral for numeric rings,
                // the 1-based position for named sets like Mon-Sun / I-XII).
                val hideNums = layer.getAttr("hide_text").split(",")
                    .mapNotNull { it.trim().toIntOrNull() }.toSet()
                val colStr = WmTagsData.evaluatePreview(layer.getAttr("color", "ffffff"), previewState).trimStart('#').padStart(6, '0')
                val trCol = try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$colStr")) } catch (e: Exception) { Color.White }
                val fontName = layer.getAttr("font")
                val typeface = remember(fontName, projectDir) {
                    if (fontName.isEmpty() || fontName.startsWith("bm:")) com.ailife.wftools.pc.graphics.Typeface.DEFAULT
                    else {
                        val dir = File(projectDir, "fonts")
                        var target = File(dir, fontName)
                        if (!target.exists()) target = File(dir, "$fontName.ttf")
                        if (!target.exists()) target = File(dir, "$fontName.otf")
                        if (target.exists()) try { com.ailife.wftools.pc.graphics.Typeface.createFromFile(target) } catch (e: Exception) { com.ailife.wftools.pc.graphics.Typeface.DEFAULT }
                        else com.ailife.wftools.pc.graphics.Typeface.DEFAULT
                    }
                }
                val labels = wmTextRingLabels(ringType)
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val n = labels.size.coerceAtLeast(1)
                        val a = (angleEnd - angleStart) / n
                        val rot = -angleStart
                        val paint = com.ailife.wftools.pc.graphics.Paint().apply {
                            isAntiAlias = true
                            textSize = textSizePx * 1.28f
                            color = trCol.toArgb()
                            textAlign = com.ailife.wftools.pc.graphics.Paint.Align.CENTER
                            this.typeface = typeface
                        }
                        val fm = paint.fontMetrics
                        labels.forEachIndexed { ang, raw ->
                            // Renderer keeps numerals whose VALUE is a multiple of
                            // show_every (5,10,… on a 1-60 ring) — NOT the index. Named
                            // sets (I-XII, Mon-Sun) fall back to 1-based position.
                            val loopVal = raw.toIntOrNull() ?: (ang + 1)
                            if (loopVal % showEvery != 0) return@forEachIndexed
                            if (loopVal in hideNums) return@forEachIndexed
                            val label = applyCaseTransform(raw, transform)
                            // CloX/UL place item k (1-based) at k steps from angle_start,
                            // so the LAST value lands at 12 o'clock (e.g. "12" at top on a
                            // 1-12 ring). Index `ang` is 0-based → use (ang+1).
                            val angleRad = Math.toRadians((rot - a * (ang + 1) + 90f).toDouble())
                            val adjR = wmSquarifyRadius(radius, angleRad, squarify)
                            val px = cx + (adjR * kotlin.math.cos(angleRad)).toFloat()
                            val py = cy - (adjR * kotlin.math.sin(angleRad)).toFloat()
                            val baseY = py - (fm.ascent + fm.descent) / 2f
                            canvas.nativeCanvas.save()
                            if (rotatedText == "y") canvas.nativeCanvas.rotate(a * (ang + 1) - rot, px, py)
                            canvas.nativeCanvas.drawText(label, px, baseY, paint)
                            canvas.nativeCanvas.restore()
                        }
                    }
                }
            }
            "series" -> {
                val seriesType = layer.getAttr("series", "dh24z")
                // Gate on second-truncated now so the series recomposes ≤1x/sec, not per frame.
                val seriesNow by remember(layer) { derivedStateOf { fastStateHolder.value.now.withNano(0) } }
                val (items, curIdx) = wmSeriesItems(seriesType, seriesNow)
                val vertical = layer.getAttr("orientation", "v") != "h"
                val currentPos = layer.getAttr("current_pos", "m")
                val spacing = layer.getAttr("spacing").toFloatOrNull() ?: 0f
                val ts1 = layer.getAttr("text_size", "24").toFloatOrNull() ?: 24f
                val ts2 = layer.getAttr("text_size2").toFloatOrNull() ?: ts1
                val transform = layer.getAttr("transform", "n")
                fun pcol(raw: String, fb: String): Color {
                    val h = raw.ifEmpty { fb }.trimStart('#').padStart(6, '0')
                    return try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$h")) } catch (e: Exception) { Color.White }
                }
                val cCur = pcol(WmTagsData.evaluatePreview(layer.getAttr("color"), previewState), "ffffff")
                val cOther = pcol(WmTagsData.evaluatePreview(layer.getAttr("color2"), previewState), "888888")
                // CloX step = spacing + text_size (no 1.4× factor).
                val advance = spacing + ts1
                val font1 = layer.getAttr("font")
                val font2 = layer.getAttr("font2").ifEmpty { font1 }
                val tf1 = remember(font1, projectDir) { wmLoadTypeface(projectDir, font1) }
                val tf2 = remember(font2, projectDir) { wmLoadTypeface(projectDir, font2) }
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val cx = size.width / 2f; val cy = size.height / 2f
                        items.forEachIndexed { i, raw ->
                            val rel = i - curIdx
                            // f/u = first (hide past), l/b = last (hide future), m = all.
                            if ((currentPos == "f" || currentPos == "u") && rel < 0) return@forEachIndexed
                            if ((currentPos == "l" || currentPos == "b") && rel > 0) return@forEachIndexed
                            val isCur = rel == 0
                            val label = applyCaseTransform(raw, transform)
                            val paint = com.ailife.wftools.pc.graphics.Paint().apply {
                                isAntiAlias = true
                                textSize = (if (isCur) ts1 else ts2) * 1.28f
                                color = (if (isCur) cCur else cOther).toArgb()
                                textAlign = com.ailife.wftools.pc.graphics.Paint.Align.CENTER
                                typeface = if (isCur) tf1 else tf2
                            }
                            val fm = paint.fontMetrics
                            // CloX: past items (rel<0) sit ABOVE current, future below,
                            // so vertical y grows WITH rel (was inverted).
                            val px = if (vertical) cx else cx + rel * advance
                            val py = (if (vertical) cy + rel * advance else cy) - (fm.ascent + fm.descent) / 2f
                            canvas.nativeCanvas.drawText(label, px, py, paint)
                        }
                    }
                }
            }
            "calendar" -> {
                val ts = layer.getAttr("text_size", "10").toFloatOrNull() ?: 10f
                val marginX = layer.getAttr("margin_x").toFloatOrNull() ?: 5f
                val marginY = layer.getAttr("margin_y").toFloatOrNull() ?: 5f
                val dowStart = layer.getAttr("dow_start", "1").toIntOrNull() ?: 1
                val noLetters = (layer.getAttr("no_letters_header", "1").toIntOrNull() ?: 1).coerceAtLeast(0)
                val outsideOp = (layer.getAttr("outside_opacity", "60").toFloatOrNull() ?: 60f) / 100f
                val transform = layer.getAttr("transform", "n")
                fun pcol(raw: String, fb: String): Color {
                    val h = raw.ifEmpty { fb }.trimStart('#').padStart(6, '0')
                    return try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$h")) } catch (e: Exception) { Color.White }
                }
                val headerCol = pcol(WmTagsData.evaluatePreview(layer.getAttr("color"), previewState), "ffffff")
                val dateCol = pcol(WmTagsData.evaluatePreview(layer.getAttr("color2"), previewState), layer.getAttr("color").ifEmpty { "ffffff" })
                val fontName = layer.getAttr("font")
                val headerFontName = layer.getAttr("font2").ifEmpty { fontName }
                val tf = remember(fontName, projectDir) { wmLoadTypeface(projectDir, fontName) }
                val headerTf = remember(headerFontName, projectDir) { wmLoadTypeface(projectDir, headerFontName) }
                // Gate on the date so the calendar recomposes ~daily, not per frame.
                val today by remember(layer) { derivedStateOf { fastStateHolder.value.now.toLocalDate() } }
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val cellW = ts + marginX; val cellH = ts + marginY
                        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                        val daysInMonth = today.lengthOfMonth()
                        val dow0 = today.withDayOfMonth(1).dayOfWeek.value % 7 // Sun=0
                        val firstDay = (dow0 - dowStart + 7) % 7
                        val rows = kotlin.math.ceil((daysInMonth + firstDay) / 7.0).toInt() + (if (noLetters > 0) 1 else 0)
                        val totalW = 7 * cellW; val totalH = rows * cellH
                        val startX = (size.width - totalW) / 2f + cellW / 2f
                        val startY = (size.height - totalH) / 2f + cellH / 2f
                        val dateRowOffset = if (noLetters > 0) 1 else 0
                        val paint = com.ailife.wftools.pc.graphics.Paint().apply {
                            isAntiAlias = true; textSize = ts * 1.28f
                            textAlign = com.ailife.wftools.pc.graphics.Paint.Align.CENTER; typeface = tf
                        }
                        val fm = paint.fontMetrics
                        fun baseY(cy: Float) = cy - (fm.ascent + fm.descent) / 2f
                        // header (skipped when no_letters_header="0" — no weekday row)
                        if (noLetters > 0) {
                            for (i in 0 until 7) {
                                val name = dayNames[(i + dowStart) % 7].take(noLetters)
                                paint.typeface = headerTf
                                paint.color = headerCol.toArgb(); paint.alpha = 255
                                canvas.nativeCanvas.drawText(applyCaseTransform(name, transform),
                                    startX + i * cellW, baseY(startY), paint)
                            }
                        }
                        // dates
                        var date = 1; var row = 0
                        while (date <= daysInMonth) {
                            for (col in 0 until 7) {
                                if (row == 0 && col < firstDay) continue
                                if (date > daysInMonth) break
                                val px = startX + col * cellW
                                val py = startY + (row + dateRowOffset) * cellH
                                if (date == today.dayOfMonth) {
                                    drawRect(color = dateCol,
                                        topLeft = Offset(px - (ts + 2) / 2f, py - (ts + 2) / 2f),
                                        size = androidx.compose.ui.geometry.Size(ts + 2, ts + 2))
                                    val br = (dateCol.red * 299 + dateCol.green * 587 + dateCol.blue * 114) / 1000f * 255
                                    paint.color = (if (br > 128) Color.Black else Color.White).toArgb()
                                    paint.alpha = 255
                                } else {
                                    paint.color = dateCol.toArgb()
                                    paint.alpha = if (date < today.dayOfMonth) (outsideOp * 255).toInt() else 255
                                }
                                canvas.nativeCanvas.drawText(date.toString(), px, baseY(py), paint)
                                date++
                            }
                            row++
                        }
                    }
                }
            }
            "chart" -> {
                val chartType = layer.getAttr("chart_type", "line")
                val pts = layer.getAttr("data_points").split(";")
                    .mapNotNull { WmTagsData.evaluatePreview(it.trim(), previewState).toFloatOrNull() }
                fun pcol(raw: String, fb: String): Color {
                    val h = raw.ifEmpty { fb }.trimStart('#').padStart(6, '0')
                    return try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$h")) } catch (e: Exception) { Color.White }
                }
                val c1 = pcol(WmTagsData.evaluatePreview(layer.getAttr("color"), previewState), "00ff00")
                val c2 = pcol(WmTagsData.evaluatePreview(layer.getAttr("color2"), previewState), "008800")
                val c3 = pcol(WmTagsData.evaluatePreview(layer.getAttr("color3"), previewState), "00ff00")
                val c4 = pcol(WmTagsData.evaluatePreview(layer.getAttr("color4"), previewState), "ffffff")
                val topOp = (layer.getAttr("top_opacity", "100").toFloatOrNull() ?: 100f) / 100f
                val botOp = (layer.getAttr("bottom_opacity", "0").toFloatOrNull() ?: 0f) / 100f
                val lineWidth = (layer.getAttr("line_width").toFloatOrNull() ?: 10f) / 4f
                val markerSize = (layer.getAttr("marker_size").toFloatOrNull() ?: 10f) / 4f
                val barThickness = layer.getAttr("bar_thickness").toFloatOrNull() ?: 50f
                val tension = (layer.getAttr("tension").toFloatOrNull() ?: 0f).coerceIn(0f, 100f)
                // min_max is "MAX to MIN" (first value = max), per WM/UL parseMinMax.
                val mm = layer.getAttr("min_max").split(" to ").mapNotNull { it.trim().toFloatOrNull() }
                val chartLines = wmParseChartLines(layer.getAttr("lines"))
                val chartLabels = wmParseChartLabels(layer.getAttr("labels"))
                val labelTf = remember(chartLabels.firstOrNull()?.font, projectDir) {
                    wmLoadTypeface(projectDir, chartLabels.firstOrNull()?.font ?: "")
                }
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    if (pts.isEmpty()) return@Canvas
                    val w = size.width; val h = size.height
                    val maxV = mm.getOrNull(0) ?: (pts.max())
                    val minV = mm.getOrNull(1) ?: (pts.min())
                    val range = (maxV - minV).let { if (it == 0f) 1f else it }
                    fun yOf(v: Float) = h - ((v - minV) / range) * h
                    fun parseHex(s: String): Color {
                        val hx = s.trimStart('#').padStart(6, '0')
                        return try { Color(com.ailife.wftools.pc.graphics.Color.parseColor("#$hx")) } catch (e: Exception) { Color.White }
                    }
                    // Reference lines (drawn under the data)
                    chartLines.forEach { ln ->
                        val col = parseHex(ln.color).copy(alpha = ln.opacity)
                        val effect = if (ln.dashW > 0f)
                            androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(ln.dashW, ln.dashGap)) else null
                        val stroke = (ln.width / 4f).coerceAtLeast(1f)
                        fun hLine(y: Float) = drawLine(col, Offset(0f, y), Offset(w, y), stroke, pathEffect = effect)
                        when (ln.type) {
                            "MAX" -> hLine(yOf(pts.max()))
                            "MIN" -> hLine(yOf(pts.min()))
                            "AVG" -> hLine(yOf(pts.average().toFloat()))
                            "V" -> {
                                // CloX: `count` INTERIOR verticals at (i-0.5)*w/n (not n+1 edge-to-edge).
                                val n = ln.count.coerceAtLeast(1)
                                for (i in 1..n) { val x = (i - 0.5f) * w / n; drawLine(col, Offset(x, 0f), Offset(x, h), stroke, pathEffect = effect) }
                            }
                            else -> { // H: `count` interior horizontals, spacing h/(n+1)
                                val n = ln.count.coerceAtLeast(1)
                                for (i in 1..n) hLine(h * i / (n + 1))
                            }
                        }
                    }
                    if (chartType == "bar") {
                        val slot = w / pts.size
                        val cornerType = layer.getAttr("corner_type", "1").toIntOrNull() ?: 1
                        val barR = layer.getAttr("radius").toFloatOrNull() ?: 0f
                        pts.forEachIndexed { i, v ->
                            val cx = slot * (i + 0.5f)
                            val top = yOf(v)
                            // CloX: barW = min(bar_thickness, slot*0.8) — was halved.
                            val bw = minOf(barThickness, slot * 0.8f).coerceAtLeast(1f)
                            val left = cx - bw / 2f
                            // CloX bars: a SINGLE color faded by opacity, bottom_opacity
                            // at the base → top_opacity at the top (NOT a color→color2
                            // gradient — that's why bottom_opacity did nothing).
                            val brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(c1.copy(alpha = topOp), c1.copy(alpha = botOp)),
                                startY = top, endY = h)
                            if (barR > 0f) {
                                val radii = wmCornerRadii(cornerType, barR.coerceAtMost(bw / 2f))
                                val path = androidx.compose.ui.graphics.Path().also {
                                    it.asDesktopPath().addRoundRect(
                                        com.ailife.wftools.pc.graphics.RectF(left, top, left + bw, h),
                                        radii, com.ailife.wftools.pc.graphics.Path.Direction.CW)
                                }
                                drawPath(path, brush = brush)
                            } else {
                                drawRect(brush = brush,
                                    topLeft = Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(bw, h - top))
                            }
                        }
                    } else {
                        val step = if (pts.size > 1) w / (pts.size - 1) else w
                        val pts2d = pts.mapIndexed { i, v -> Offset(i * step, yOf(v)) }
                        // tension (curve smoothing, 0-100): Catmull-Rom style cubic
                        // segments whose control points pull toward the neighbours
                        // scaled by tension. 0 = straight lines.
                        val path = androidx.compose.ui.graphics.Path()
                        if (tension > 0f && pts2d.size >= 3) {
                            val k = (tension / 100f) / 3f // 0..~0.33
                            pts2d.forEachIndexed { i, p ->
                                if (i == 0) { path.moveTo(p.x, p.y); return@forEachIndexed }
                                val p0 = pts2d[i - 2]
                                val p1 = pts2d[i - 1]
                                val p3 = if (i + 1 < pts2d.size) pts2d[i + 1] else p
                                val c1 = Offset(p1.x + (p.x - p0.x) * k, p1.y + (p.y - p0.y) * k)
                                val c2 = Offset(p.x - (p3.x - p1.x) * k, p.y - (p3.y - p1.y) * k)
                                path.cubicTo(c1.x, c1.y, c2.x, c2.y, p.x, p.y)
                            }
                        } else {
                            pts2d.forEachIndexed { i, p ->
                                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            }
                        }
                        if (topOp > 0f || botOp > 0f) {
                            val fill = androidx.compose.ui.graphics.Path().apply {
                                addPath(path); lineTo(w, h); lineTo(0f, h); close()
                            }
                            drawPath(fill, brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(c1.copy(alpha = topOp), c2.copy(alpha = botOp))))
                        }
                        drawPath(path, color = c3,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = lineWidth.coerceAtLeast(1f)))
                        if (markerSize > 0f) pts.forEachIndexed { i, v ->
                            drawCircle(c4, radius = markerSize, center = Offset(i * step, yOf(v)))
                        }
                    }
                    // Data labels (text at point)
                    if (chartLabels.isNotEmpty()) drawIntoCanvas { canvas ->
                        val slot = w / pts.size
                        val step = if (pts.size > 1) w / (pts.size - 1) else w
                        val dMin = pts.min(); val dMax = pts.max()
                        val dAvg = pts.average().toFloat(); val cnt = pts.size
                        fun fmtVal(v: Float) = if (v == v.toLong().toFloat()) v.toLong().toString()
                            else String.format("%.2f", v).trimEnd('0').trimEnd('.')
                        chartLabels.forEach { lab ->
                            val paint = com.ailife.wftools.pc.graphics.Paint().apply {
                                isAntiAlias = true
                                textSize = lab.size * 1.28f
                                color = parseHex(lab.color).copy(alpha = lab.opacity).toArgb()
                                textAlign = com.ailife.wftools.pc.graphics.Paint.Align.CENTER
                                typeface = labelTf
                            }
                            // CloX label {key} set: value/value0dp/1dp/2dp, index, min/max/avg/count.
                            fun subst(v: Float, idx: Int) = lab.text
                                .replace("{value0dp}", String.format("%.0f", v))
                                .replace("{value1dp}", String.format("%.1f", v))
                                .replace("{value2dp}", String.format("%.2f", v))
                                .replace("{value}", fmtVal(v))
                                .replace("{index}", idx.toString())
                                .replace("{min}", fmtVal(dMin))
                                .replace("{max}", fmtVal(dMax))
                                .replace("{avg}", fmtVal(dAvg))
                                .replace("{count}", cnt.toString())
                            // "P" thinning: step through ALL points at ceil(n/maxCount),
                            // not the LAST maxCount (CloX).
                            val stepN = if (lab.maxCount > 0)
                                maxOf(1, kotlin.math.ceil(pts.size.toFloat() / lab.maxCount).toInt()) else 1
                            when (lab.position) {
                                "S" -> // static: single label (stats only)
                                    canvas.nativeCanvas.drawText(
                                        subst(dAvg, 0), w / 2f + lab.xOff, h / 2f - lab.yOff, paint)
                                "A" -> { // at axis (bottom)
                                    for (i in pts.indices step stepN) {
                                        val x = (if (chartType == "bar") slot * (i + 0.5f) else i * step) + lab.xOff
                                        canvas.nativeCanvas.drawText(subst(pts[i], i), x, h - lab.yOff, paint)
                                    }
                                }
                                else -> { // "P": at each data point
                                    for (i in pts.indices step stepN) {
                                        val x = (if (chartType == "bar") slot * (i + 0.5f) else i * step) + lab.xOff
                                        canvas.nativeCanvas.drawText(subst(pts[i], i), x, yOf(pts[i]) - lab.yOff, paint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "video", "map", "gallery_2d" -> {
                val isMap = layer.layerType == "map"
                val isGallery = layer.layerType == "gallery_2d"
                if (isMap) {
                    // CloX map builder: ONE OSM tile centred on lat/lon at map_zoom, its
                    // texture stretched to the width×height box scaled by map_scale (%).
                    // Same tile CloX's bridge fetches — loaded live in the editor.
                    val lat = WmTagsData.evaluatePreview(layer.getAttr("lat", "0"), previewState).toDoubleOrNull() ?: 0.0
                    val lon = WmTagsData.evaluatePreview(layer.getAttr("lon", "0"), previewState).toDoubleOrNull() ?: 0.0
                    val zoom = (WmTagsData.evaluatePreview(layer.getAttr("map_zoom", "15"), previewState).toIntOrNull() ?: 15).coerceIn(1, 19)
                    val (tx, ty) = osmTile(lat, lon, zoom)
                    WmMapTileLayer(
                        tileUrl = "https://tile.openstreetmap.org/$zoom/$tx/$ty.png",
                        colorFilter = colorFilter
                    )
                } else if (!isGallery) {
                    // Video layer — CloX plays videos/<path> stretched to width×height
                    // (setKeepAspectRatioEnabled(false)) and only in bright mode.
                    val videoPath = layer.getAttr("path").replace('\\', '/').removePrefix("videos/")
                    val videoFile = File(projectDir, "videos/$videoPath")
                    if (videoPath.isNotEmpty() && videoFile.exists()) {
                        WmVideoPlayerLayer(videoFile = videoFile, playing = isBright,colorFilter=colorFilter)
                    } else {
                        VideoPlaceholderPanel()
                    }
                } else {
                    // gallery_2d has no CloX builder (never rendered on-device) — marker only.
                    VideoPlaceholderPanel(isGallery = true)
                }
            }
        }
    }
    } // StencilMaskBox
}


@Composable
internal fun StencilMaskBox(enabled: Boolean, sheetColor: Color, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                clip = false
            }
            .drawWithContent {
                drawRect(sheetColor)
                val paint = androidx.compose.ui.graphics.Paint().apply {
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstOut
                }
                // Null bounds: no clip, so skew-distorted overhang stays visible.
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
                drawContent()
                drawContext.canvas.restore()
            }
    ) { content() }
}

/** Apply a WatchMaker case transform ("uc"=upper, "lc"=lower, else unchanged) to text. */
internal fun applyCaseTransform(text: String, transform: String): String = when (transform) {
    "uc" -> text.uppercase()
    "lc" -> text.lowercase()
    else -> text
}
