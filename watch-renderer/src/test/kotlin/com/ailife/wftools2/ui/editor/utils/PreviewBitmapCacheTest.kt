package com.ailife.wftools2.ui.editor.utils

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewBitmapCacheTest {
    private fun picture() = Files.createTempDirectory("preview-cache-test-").resolve("image.png").toFile().also {
        val image = BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xffff0000.toInt())
        check(ImageIO.write(image, "png", it))
    }

    @Test fun requestedSizeDecodesOnlyTheNeededPixels() = runBlocking {
        val file = picture()
        val small = requireNotNull(PreviewBitmapCache.load(file.absolutePath, 128, 64))
        assertEquals(128, small.width)
        assertEquals(64, small.height)
    }

    @Test fun cacheSeparatesFullResolutionAndDifferentHeightRequests() = runBlocking {
        val file = picture()
        val thumbnail = requireNotNull(PreviewBitmapCache.load(file.absolutePath, 128, 64))
        val tallRequest = requireNotNull(PreviewBitmapCache.load(file.absolutePath, 128, 128))
        val original = requireNotNull(PreviewBitmapCache.load(file.absolutePath))
        assertEquals(128, thumbnail.width)
        assertEquals(256, tallRequest.width)
        assertEquals(512, original.width)
        assertNotSame(thumbnail, tallRequest)
        assertNotSame(thumbnail, original)
    }

    @Test fun directoryEvictionKeepsAlreadyReturnedBitmapUsable() = runBlocking {
        val file = picture()
        val image = requireNotNull(PreviewBitmapCache.load(file.absolutePath))
        PreviewBitmapCache.clearDirectory(file.parentFile)
        assertNull(PreviewBitmapCache.peek(file.absolutePath))
        val pixels = IntArray(1)
        image.readPixels(pixels, startX = 0, startY = 0, width = 1, height = 1)
        assertEquals(0xffff0000.toInt(), pixels[0])
    }

    @Test fun memoryBudgetEvictsLeastRecentlyUsedImageWithoutClosingIt() {
        val cache=PreviewBitmapMemoryCache(8L*8*4*2)
        val first=androidx.compose.ui.graphics.ImageBitmap(8,8)
        val second=androidx.compose.ui.graphics.ImageBitmap(8,8)
        val third=androidx.compose.ui.graphics.ImageBitmap(8,8)
        cache.put("first",first);cache.put("second",second)
        assertSame(first,cache["first"])
        cache.put("third",third)
        assertNull(cache["second"])
        assertSame(first,cache["first"])
        assertSame(third,cache["third"])
        assertEquals(8L*8*4*2,cache.byteCount)
        second.readPixels(IntArray(1),startX=0,startY=0,width=1,height=1)
    }

    @Test fun imageLargerThanBudgetIsReturnedToCallerButNotRetainedByCache() {
        val cache=PreviewBitmapMemoryCache(8L*8*4)
        val image=androidx.compose.ui.graphics.ImageBitmap(16,16)
        cache.put("oversized",image)
        assertNull(cache["oversized"])
        assertEquals(0L,cache.byteCount)
        image.readPixels(IntArray(1),startX=0,startY=0,width=1,height=1)
    }
}
