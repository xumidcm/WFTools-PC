package com.ailife.wftools.pc

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class BrandingAssetsTest {
    @Test fun `desktop branding uses one square rounded rectangle master with transparent corners`() {
        val image = requireNotNull(javaClass.getResourceAsStream("/icons/wftools.png")?.use(ImageIO::read))
        assertEquals("The window and home icon master must be square",image.width,image.height)
        val corners = listOf(0 to 0,image.width-1 to 0,0 to image.height-1,image.width-1 to image.height-1)
        assertTrue("All four outer corners must be transparent",corners.all { (x,y) -> image.getRGB(x,y).ushr(24) == 0 })
        assertTrue("The rounded rectangle body must remain opaque",image.getRGB(image.width/2,image.height/2).ushr(24) > 240)
    }

    @Test fun `windows icon contains the common shell and title bar sizes`() {
        val cwd = Path.of("").toAbsolutePath()
        val icon = listOf(cwd.resolve("../packaging/WFTools-PC.ico"),cwd.resolve("packaging/WFTools-PC.ico")).first(Files::isRegularFile)
        val bytes = Files.readAllBytes(icon)
        fun u16(offset:Int) = (bytes[offset].toInt() and 0xff) or ((bytes[offset+1].toInt() and 0xff) shl 8)
        assertEquals(0,u16(0));assertEquals(1,u16(2))
        val count=u16(4)
        val sizes=(0 until count).map { index -> bytes[6+index*16].toInt().and(0xff).let { if(it==0)256 else it } }.toSet()
        assertTrue("ICO sizes were $sizes",sizes.containsAll(setOf(16,24,32,48,64,128,256)))
    }
}
