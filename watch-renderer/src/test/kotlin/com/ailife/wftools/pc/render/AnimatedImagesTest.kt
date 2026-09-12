package com.ailife.wftools.pc.render

import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import org.jetbrains.skia.*
import org.junit.Assert.*
import org.junit.Test

class AnimatedImagesTest {
    @Test fun gifFramesAndDurationsDecodeAndLoop() {
        val file=Files.createTempFile("watch-animation-",".gif").toFile()
        val writer=ImageIO.getImageWritersByFormatName("gif").next()
        FileImageOutputStream(file).use { stream ->
            writer.output=stream;writer.prepareWriteSequence(null)
            for(index in 0..1) {
                val image=BufferedImage(8,8,BufferedImage.TYPE_INT_RGB)
                image.createGraphics().also{it.color=if(index==0)java.awt.Color.RED else java.awt.Color.BLUE;it.fillRect(0,0,8,8);it.dispose()}
                val metadata=writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image),null)
                val format=metadata.nativeMetadataFormatName
                val root=metadata.getAsTree(format) as IIOMetadataNode
                val control=root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                control.setAttribute("delayTime",if(index==0)"5" else "10")
                metadata.setFromTree(format,root)
                writer.writeToSequence(IIOImage(image,null,metadata),null)
            }
            writer.endWriteSequence()
        };writer.dispose()
        val decoded=decodeAnimation(file)
        assertEquals(2,decoded.frames.size);assertEquals(listOf(50,100),decoded.durations)
        assertEquals(0,decoded.indexAt(49));assertEquals(1,decoded.indexAt(50));assertEquals(0,decoded.indexAt(150))
        assertTrue(decoded.frames[0].toPixelMap()[4,4].red>.9f)
        assertTrue(decoded.frames[1].toPixelMap()[4,4].blue>.9f)
    }
    @Test fun webpDecodesThroughTheSameAnimationPath() {
        val file=Files.createTempFile("watch-webp-",".webp").toFile()
        Surface.makeRasterN32Premul(16,12).use { surface ->
            surface.canvas.clear(0xff00ff00.toInt())
            surface.makeImageSnapshot().use{it.encodeToData(EncodedImageFormat.WEBP,100)!!.use{data->file.writeBytes(data.bytes)}}
        }
        val decoded=decodeAnimation(file)
        assertEquals(1,decoded.frames.size);assertEquals(16,decoded.frames[0].width)
        assertTrue(decoded.frames[0].toPixelMap()[4,4].green>.9f)
    }
}
