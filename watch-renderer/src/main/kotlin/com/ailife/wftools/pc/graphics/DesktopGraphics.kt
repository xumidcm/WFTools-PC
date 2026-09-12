package com.ailife.wftools.pc.graphics

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import org.jetbrains.skia.Font as SkFont
import org.jetbrains.skia.Paint as SkPaint

/** JVM/Skia equivalents for the small graphics surface used by the migrated renderer. */
object Color {
    const val WHITE = -1
    const val BLACK = -16777216
    const val TRANSPARENT = 0
    fun parseColor(value: String): Int {
        val raw = value.removePrefix("#")
        return when (raw.length) {
            6 -> (0xff000000L or raw.toLong(16)).toInt()
            8 -> raw.toLong(16).toInt()
            3 -> parseColor("#" + raw.flatMap { listOf(it,it) }.joinToString(""))
            else -> when(value.lowercase()) { "white" -> WHITE; "black" -> BLACK; "transparent" -> TRANSPARENT; else -> error("Invalid color: $value") }
        }
    }
    fun HSVToColor(values: FloatArray): Int = java.awt.Color.HSBtoRGB(values[0] / 360f,values[1],values[2])
}

class Typeface private constructor(val native: org.jetbrains.skia.Typeface, val file: File? = null) {
    // Each collection owns the authored font and delegates missing characters to
    // Windows fonts. A single SkFont does not perform that fallback or shaping.
    private val fontCollection by lazy {
        TypefaceFontProvider().use { provider ->
            provider.registerTypeface(native, "WFTools authored font")
            FontCollection().setAssetFontManager(provider)
                .setDefaultFontManager(org.jetbrains.skia.FontMgr.default).setEnableFallback(true)
        }
    }
    internal fun paragraph(text: String, size: Float, paint: SkPaint? = null): Paragraph = synchronized(this) {
        TextStyle().use { style ->
            style.fontFamilies = arrayOf("WFTools authored font")
            style.fontSize = size.coerceAtLeast(0f)
            style.locale = Locale.getDefault().toLanguageTag()
            if (paint != null) style.foreground = paint
            ParagraphStyle().use { paragraphStyle ->
                paragraphStyle.textStyle = style
                ParagraphBuilder(paragraphStyle, fontCollection).use { builder ->
                    builder.addText(text).build().apply { layout(Float.POSITIVE_INFINITY) }
                }
            }
        }
    }
    companion object {
        val DEFAULT = Typeface(Typeface::class.java.getResourceAsStream("/fonts/Roboto-Regular.ttf")?.use { input ->
            org.jetbrains.skia.Data.makeFromBytes(input.readBytes()).use { data -> org.jetbrains.skia.FontMgr.default.makeFromData(data) }
        } ?: requireNotNull(org.jetbrains.skia.FontMgr.default.matchFamilyStyle("Arial",org.jetbrains.skia.FontStyle.NORMAL)))
        const val NORMAL=0
        const val BOLD=1
        private val cache = ConcurrentHashMap<String,Typeface>()
        fun createFromFile(file: File): Typeface = cache.computeIfAbsent(pathIdentity(file) + ":" + file.lastModified()) {
            Typeface(requireNotNull(org.jetbrains.skia.FontMgr.default.makeFromFile(file.absolutePath)){"无法读取字体：${file.name}"},file)
        }
        fun createFromFile(path: String) = createFromFile(File(path))
        fun clearDirectory(directory:File) {
            val prefix=pathIdentity(directory)+File.separator
            // Compositions may still hold a Typeface after its window closes.
            // Drop cache ownership; its native objects follow managed GC lifetime.
            cache.keys.removeIf {it.startsWith(prefix)}
        }
    }
}
fun desktopFontFamily(typeface: Typeface): FontFamily = typeface.file?.let {
    FontFamily(androidx.compose.ui.text.platform.Font(it))
} ?: FontFamily(androidx.compose.ui.text.platform.Font("fonts/Roboto-Regular.ttf"))

class Paint(flags: Int = 0) {
    enum class Align { LEFT,CENTER,RIGHT }
    enum class Style { FILL,STROKE,FILL_AND_STROKE }
    var isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
    var color = Color.BLACK
    var alpha = 255
    var textSize = 16f
    var typeface = Typeface.DEFAULT
    var textAlign = Align.LEFT
    var style = Style.FILL
    var strokeWidth = 1f
    var colorFilter: ColorMatrixColorFilter? = null
    var xfermode: PorterDuffXfermode? = null
    val fontMetrics: Metrics get() = SkFont(typeface.native,textSize).use { font ->
        font.metrics.let { Metrics(it.ascent,it.descent,it.top,it.bottom,it.leading) }
    }
    data class Metrics(val ascent:Float,val descent:Float,val top:Float,val bottom:Float,val leading:Float)
    fun measureText(text:String):Float = typeface.paragraph(text,textSize).use { it.maxIntrinsicWidth }
    fun getTextWidths(text:String, widths:FloatArray):Int {
        // The renderer draws curved text one character at a time; measure those
        // same runs with the same fallback as drawText, including surrogate pairs.
        var i = 0
        while (i < minOf(text.length, widths.size)) {
            val count = Character.charCount(text.codePointAt(i))
            widths[i] = measureText(text.substring(i, (i + count).coerceAtMost(text.length)))
            if (count == 2 && i + 1 < widths.size) widths[i + 1] = 0f
            i += count
        }
        return minOf(text.length,widths.size)
    }
    internal fun native():SkPaint = SkPaint().also { p ->
        p.isAntiAlias=isAntiAlias
        p.color = (color and 0xffffff) or (((color ushr 24) * alpha.coerceIn(0,255) / 255) shl 24)
        p.mode = if(style==Style.STROKE) org.jetbrains.skia.PaintMode.STROKE else org.jetbrains.skia.PaintMode.FILL
        p.strokeWidth=strokeWidth
        colorFilter?.let { filter ->
            val matrix=filter.matrix.values.copyOf()
            for(i in listOf(4,9,14,19)) matrix[i]/=255f
            p.colorFilter=org.jetbrains.skia.ColorFilter.makeMatrix(org.jetbrains.skia.ColorMatrix(*matrix))
        }
        xfermode?.let { p.blendMode = when(it.mode) { PorterDuff.Mode.DST_IN -> org.jetbrains.skia.BlendMode.DST_IN; PorterDuff.Mode.SRC_IN -> org.jetbrains.skia.BlendMode.SRC_IN; PorterDuff.Mode.CLEAR -> org.jetbrains.skia.BlendMode.CLEAR } }
    }
    companion object { const val ANTI_ALIAS_FLAG=1 }
}
fun org.jetbrains.skia.Canvas.drawText(text:String,x:Float,y:Float,paint:Paint) {
    paint.native().use { nativePaint ->
        paint.typeface.paragraph(text,paint.textSize,nativePaint).use { paragraph ->
            val offset=when(paint.textAlign) { Paint.Align.LEFT->0f; Paint.Align.CENTER->paragraph.maxIntrinsicWidth/2; Paint.Align.RIGHT->paragraph.maxIntrinsicWidth }
            paragraph.paint(this,x-offset,y-paragraph.alphabeticBaseline)
        }
    }
}
fun org.jetbrains.skia.Canvas.drawText(text:String,start:Int,end:Int,x:Float,y:Float,paint:Paint) = drawText(text.substring(start,end),x,y,paint)
fun org.jetbrains.skia.Canvas.rotate(degrees:Float,x:Float,y:Float) { translate(x,y); rotate(degrees); translate(-x,-y) }
fun org.jetbrains.skia.Canvas.saveLayer(bounds:RectF?,paint:Paint) {
    paint.native().use { saveLayer(bounds?.let { b -> org.jetbrains.skia.Rect.makeLTRB(b.left,b.top,b.right,b.bottom) },it) }
}

class ColorMatrix(val values:FloatArray = floatArrayOf(1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f)) {
    val array get()=values
    fun set(array:FloatArray) {array.copyInto(values)}
    fun setSaturation(s:Float) {
        val inv=1-s; val r=.213f*inv;val g=.715f*inv;val b=.072f*inv
        floatArrayOf(r+s,g,b,0f,0f,r,g+s,b,0f,0f,r,g,b+s,0f,0f,0f,0f,0f,1f,0f).copyInto(values)
    }
    fun postConcat(after:ColorMatrix) {
        val result=FloatArray(20)
        for(row in 0..3) for(col in 0..4) {
            result[row*5+col]=(0..3).sumOf { k -> (after.values[row*5+k]*values[k*5+col]).toDouble() }.toFloat()
            if(col==4) result[row*5+col]+=after.values[row*5+4]
        }
        result.copyInto(values)
    }
}
data class ColorMatrixColorFilter(val matrix:ColorMatrix)
object PorterDuff { enum class Mode { DST_IN,SRC_IN,CLEAR } }
data class PorterDuffXfermode(val mode:PorterDuff.Mode)
data class RectF(val left:Float,val top:Float,val right:Float,val bottom:Float)
class Path { enum class Direction { CW,CCW } }
class DesktopPath(private val path:androidx.compose.ui.graphics.Path) {
    fun addRoundRect(rect:RectF,radii:FloatArray,direction:Path.Direction) {
        path.addRoundRect(RoundRect(rect.left,rect.top,rect.right,rect.bottom,
            CornerRadius(radii[0],radii[1]),CornerRadius(radii[2],radii[3]),
            CornerRadius(radii[4],radii[5]),CornerRadius(radii[6],radii[7])))
    }
}
fun androidx.compose.ui.graphics.Path.asDesktopPath()=DesktopPath(this)

class Bitmap(val image:org.jetbrains.skia.Image) {
    val width get()=image.width
    val height get()=image.height
    fun recycle() { image.close() }
}
fun Bitmap.asImageBitmap():ImageBitmap = image.toComposeImageBitmap()
object BitmapFactory {
    class Options { var inJustDecodeBounds=false; var outWidth=0; var outHeight=0; var inScaled=false;var inSampleSize=1 }
    private val dimensions=object:LinkedHashMap<String,Pair<Int,Int>>(64,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Pair<Int,Int>>?) = size > 512
    }
    internal const val MAX_DECODE_BYTES = 128L * 1024 * 1024
    fun clearDirectory(directory:File) = synchronized(dimensions) {
        val prefix=pathIdentity(directory)+File.separator
        dimensions.keys.removeIf { it.startsWith(prefix) }
    }
    private fun dimensions(file:File):Pair<Int,Int> {
        val key=pathIdentity(file)+":"+file.lastModified()+":"+file.length()
        synchronized(dimensions){dimensions[key]}?.let{return it}
        val size=ImageIO.createImageInputStream(file)?.use { input ->
            val reader=ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {reader.input=input;reader.getWidth(0) to reader.getHeight(0)} finally {reader.dispose()}
        } ?: org.jetbrains.skia.Data.makeFromBytes(file.readBytes()).use { data ->
            org.jetbrains.skia.Codec.makeFromData(data).use { it.width to it.height }
        }
        synchronized(dimensions){dimensions[key]=size}
        return size
    }
    fun decodeFile(path:String,options:Options?=null):Bitmap? {
        val file=File(path)
        if(!file.isFile)return null
        return runCatching {
            val (width,height)=dimensions(file)
            options?.apply {outWidth=width;outHeight=height}
            if(options?.inJustDecodeBounds==true)return null
            val sample=options?.inSampleSize?.coerceAtLeast(1) ?: 1
            val targetWidth=((width.toLong()+sample-1)/sample).toInt()
            val targetHeight=((height.toLong()+sample-1)/sample).toInt()
            require(targetWidth.toLong()*targetHeight*4 <= MAX_DECODE_BYTES){"Image decoding exceeds the 128 MB limit"}
            val sampled=if(sample>1)decodeSampled(file,sample,targetWidth,targetHeight) else null
            Bitmap(sampled ?: org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()))
        }.getOrNull()
    }
    private fun decodeSampled(file:File,sample:Int,width:Int,height:Int):org.jetbrains.skia.Image {
        val buffered=ImageIO.createImageInputStream(file)?.use { input ->
            val reader=ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input=input
                val params=reader.defaultReadParam.apply {setSourceSubsampling(sample,sample,0,0)}
                reader.read(0,params)
            } finally {reader.dispose()}
        }
        if(buffered!=null) {
            try {
                val pixels=ByteBuffer.allocate(buffered.width*buffered.height*4).order(ByteOrder.nativeOrder())
                val row=IntArray(buffered.width)
                for(y in 0 until buffered.height) {
                    buffered.getRGB(0,y,buffered.width,1,row,0,buffered.width)
                    row.forEach { pixels.putInt(it) }
                }
                return org.jetbrains.skia.Image.makeRaster(
                    org.jetbrains.skia.ImageInfo.makeN32(buffered.width,buffered.height,org.jetbrains.skia.ColorAlphaType.UNPREMUL),
                    pixels.array(),buffered.width*4)
            } finally {buffered.flush()}
        }
        // Skia supports scaled decoding for formats such as WebP not provided by
        // ImageIO. If a codec cannot decode this scale, do not silently allocate
        // the full source image and defeat the requested memory bound.
        org.jetbrains.skia.Data.makeFromBytes(file.readBytes()).use { data ->
            org.jetbrains.skia.Codec.makeFromData(data).use { codec ->
                org.jetbrains.skia.Bitmap().use { bitmap ->
                    check(bitmap.allocPixels(org.jetbrains.skia.ImageInfo.makeN32Premul(width,height)))
                    codec.readPixels(bitmap)
                    return org.jetbrains.skia.Image.makeFromBitmap(bitmap)
                }
            }
        }
    }
    fun decodeByteArray(bytes:ByteArray,offset:Int,length:Int):Bitmap? = runCatching { Bitmap(org.jetbrains.skia.Image.makeFromEncoded(bytes.copyOfRange(offset,offset+length))) }.getOrNull()
}

// A cache lookup must not resolve every path through NTFS on the drawing thread.
internal fun pathIdentity(file:File):String = file.toPath().toAbsolutePath().normalize().toString().let {
    if(File.separatorChar=='\\')it.lowercase(Locale.ROOT)else it
}
