package com.ailife.wftools.pc.graphics

import java.nio.file.Files
import org.jetbrains.skia.Surface
import org.junit.Assert.*
import org.junit.Test

class DesktopGraphicsTest {
    @Test fun defaultChineseTextUsesSystemFallbackForMeasurementAndDrawing() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Color.WHITE }
        val width = paint.measureText("中文")
        assertTrue("CJK must measure real glyphs rather than Roboto missing-glyph boxes: $width", width > 50f)
        Surface.makeRasterN32Premul(200, 80).use { surface ->
            surface.canvas.clear(Color.TRANSPARENT)
            surface.canvas.drawText("中文", 8f, 50f, paint)
            org.jetbrains.skia.Bitmap().use { pixels ->
                pixels.allocN32Pixels(200, 80)
                assertTrue(surface.readPixels(pixels, 0, 0))
                val occupied = (0 until 200).filter { x -> (0 until 80).any { y -> pixels.getColor(x, y) ushr 24 != 0 } }
                assertTrue("Fallback glyphs must actually be painted", occupied.isNotEmpty())
                assertTrue("Measured width must contain painted glyphs", occupied.last() < 8f + width + 2f)
                assertTrue("Drawing must not keep the narrower missing-glyph boxes", occupied.last() - occupied.first() > 48)
            }
        }
    }

    @Test fun defaultLatinDigitsKeepTheirNativeWidth() {
        val paint = Paint().apply { textSize = 32f }
        val expected = org.jetbrains.skia.Font(Typeface.DEFAULT.native, 32f).use { it.measureTextWidth("1234567890") }
        assertEquals(expected, paint.measureText("1234567890"), 1f)
    }

    @Test fun directoryReleaseDropsCacheOwnershipWithoutClosingActiveTypeface() {
        val home = Files.createTempDirectory("desktop-font-test-")
        val file = home.resolve("test.ttf").toFile()
        Typeface::class.java.getResourceAsStream("/fonts/Roboto-Regular.ttf")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val before = Typeface.createFromFile(file)
        Typeface.clearDirectory(home.toFile())
        val after = Typeface.createFromFile(file)
        assertNotSame(before, after)
        assertTrue(Paint().apply { typeface = before }.measureText("Active") > 0)
    }
}
