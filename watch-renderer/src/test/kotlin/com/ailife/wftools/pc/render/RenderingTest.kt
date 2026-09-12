package com.ailife.wftools.pc.render

import com.ailife.wftools.pc.core.WatchDocumentStore
import com.ailife.wftools2.domain.editor.watchmaker.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.ailife.wftools.pc.graphics.Typeface
import org.jetbrains.skia.*

class RenderingTest {
    @Test fun layerOpacityCompositesAgainstDocumentBackground() = runBlocking {
        val project=WmWatchfaceProject(shape="Square",background="ffffff",wmLayers=listOf(
            WmWatchfaceLayer("shape",linkedMapOf("width" to "300","height" to "300","color" to "000000","opacity" to "25"))))
        val output=ThumbnailExporter.generate(project,Files.createTempDirectory("watch-opacity").toFile())
        val image=ImageIO.read(ByteArrayInputStream(output.getValue("preview.jpg")))
        val center=java.awt.Color(image.getRGB(256,256))
        assertTrue("25% black over white must be gray: $center",center.red in 183..199)
    }
    @Test fun textOpacityCompositesOnceAgainstDocumentBackground() = runBlocking {
        val project=WmWatchfaceProject(shape="Square",background="ffffff",wmLayers=listOf(
            WmWatchfaceLayer("text",linkedMapOf("text" to "HELLO","text_size" to "64","color" to "000000","opacity" to "25"))))
        val output=ThumbnailExporter.generate(project,Files.createTempDirectory("watch-text-opacity").toFile())
        Files.createDirectories(Path.of("build","review"));Files.write(Path.of("build","review","text-opacity.jpg"),output.getValue("preview.jpg"))
        val image=ImageIO.read(ByteArrayInputStream(output.getValue("preview.jpg")))
        var darkest=255
        for(y in 0 until image.height)for(x in 0 until image.width)darkest=minOf(darkest,image.getRGB(x,y) and 255)
        assertTrue("25% black text over white must stay gray, darkest=$darkest",darkest in 180..199)
    }
    @Test fun thumbnailsUseDocumentDimensionsAndActuallyRenderPixels() = runBlocking {
        val root = Files.createTempDirectory("watch-render-test")
        val project = WmWatchfaceProject(width=320,height=386,shape="Square",
            rootAttrs=linkedMapOf("width" to "320","height" to "386"),
            wmLayers=listOf(WmWatchfaceLayer("shape", linkedMapOf("x" to "0","y" to "0","width" to "100","height" to "100","color" to "ff0000","shape" to "Square","display" to "a"))))
        val thumbnails=ThumbnailExporter.generate(project,root.toFile())
        val image=ImageIO.read(ByteArrayInputStream(thumbnails.getValue("preview.jpg")))
        assertEquals(320,image.width);assertEquals(386,image.height)
        val center=java.awt.Color(image.getRGB(160,193))
        assertTrue("center must contain the red shape, was $center",center.red>200 && center.green<30)
        assertTrue(thumbnails.containsKey("preview_dim.jpg"))
    }

    @Test fun mainSampleRendersToReviewableArtifact() = runBlocking {
        val sample=System.getProperty("watch.sample")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: return@runBlocking
        val store=WatchDocumentStore(Files.createTempDirectory("watch-render-sample"))
        store.open(sample).use { doc ->
            assertEquals(377,doc.layers.size)
            val started=System.nanoTime()
            val output=ThumbnailExporter.generate(doc.toWatchProject(),doc.workDir.toFile())
            val artifacts=Path.of("build","review");Files.createDirectories(artifacts)
            output.forEach{(name,bytes)->Files.write(artifacts.resolve("312-$name"),bytes)}
            val digits=doc.toWatchProject().wmLayers.first{it.getAttr("text")=="{dh23z}:{dmz}"}
            RenderSession(doc.workDir.toFile(),false).use{runtime->
                runtime.prepare(doc.toWatchProject())
                doc.toWatchProject().wmLayers.filter{it.layerType=="text"}.take(15).forEach{l->println("TEXT ${runtime.evaluate(l.getAttr("text"))} OPACITY ${runtime.evaluate(l.getAttr("opacity","100"))}")}
            }
            println("DIGITS_EVAL="+WmTagsData.evaluatePreview(digits.getAttr("text")))
            val font=Typeface.createFromFile(doc.workDir.resolve("fonts/"+digits.getAttr("font")+".ttf").toFile())
            val isolated=doc.toWatchProject().copy(shape="Square",background="ffffff",wmLayers=listOf(digits.withAttr("x","0").withAttr("y","0").withAttr("opacity","100")))
            Files.write(artifacts.resolve("isolated-digits.jpg"),ThumbnailExporter.generate(isolated,doc.workDir.toFile()).getValue("preview.jpg"))
            val firstLayers=doc.toWatchProject().copy(wmLayers=doc.toWatchProject().wmLayers.take(16))
            Files.write(artifacts.resolve("first-16-layers.jpg"),ThumbnailExporter.generate(firstLayers,doc.workDir.toFile()).getValue("preview.jpg"))
            Surface.makeRasterN32Premul(520,100).use{surface->
                surface.canvas.clear(-1)
                Font(font.native,45f).use{nativeFont->Paint().use{paint->
                    paint.color=0xff000000.toInt()
                    surface.canvas.drawString("0123456789ABC$",8f,60f,nativeFont,paint)
                    println("DIGIT_GLYPHS="+nativeFont.getStringGlyphs("0123456789ABC$").contentToString())
                }}
                surface.makeImageSnapshot().use{it.encodeToData()!!.use{data->Files.write(artifacts.resolve("font-diagnostic.png"),data.bytes)}}
            }
            val image=ImageIO.read(ByteArrayInputStream(output.getValue("preview.jpg")))
            var varied=0
            for(y in 0 until image.height step 4)for(x in 0 until image.width step 4)
                if(image.getRGB(x,y) and 0xffffff > 0x101010)varied++
            assertTrue("sample must not render black",varied>100)
            println("SAMPLE_EXPORT_MS="+(System.nanoTime()-started)/1_000_000)
            assertFalse(doc.hasUnsavedChanges)
        }
    }
}
