package com.ailife.wftools.pc.render

import com.ailife.wftools.pc.preview.CanvasPreset
import com.ailife.wftools.pc.preview.DevicePreviewSettings
import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadataNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

object PreviewCaptureExporter {
    const val DEFAULT_DURATION_MILLIS=3_000L
    const val DEFAULT_FPS=12
    private const val MIN_DURATION_MILLIS=1_000L
    private const val MAX_DURATION_MILLIS=10_000L
    private const val MIN_FPS=1
    private const val MAX_FPS=30
    private const val CAPTURE_SIZE=512

    suspend fun capturePng(
        project:WmWatchfaceProject,
        directory:File,
        state:WmTagsData.WmPreviewState,
        isBright:Boolean,
        themeIndex:Int,
    ):ByteArray = withContext(Dispatchers.Default) {
        val render=newCaptureSession(directory)
        try {
            render.prepareForCapture(project,state,isBright,themeIndex)
            if(isBright)primeVideoFrames(project,directory,render,"preview-png-prime")
            withOffscreenWatchScene(project,render,captureDevice()) {
                pump()
                val pixels=renderPixels()
                withContext(Dispatchers.Default){encodePng(pixels)}
            }
        } finally {
            withContext(NonCancellable+Dispatchers.IO){render.close()}
        }
    }

    suspend fun captureGif(
        project:WmWatchfaceProject,
        directory:File,
        state:WmTagsData.WmPreviewState,
        isBright:Boolean,
        themeIndex:Int,
        durationMillis:Long=DEFAULT_DURATION_MILLIS,
        fps:Int=DEFAULT_FPS,
        onProgress:suspend (completedFrames:Int,totalFrames:Int)->Unit={_,_->},
    ):ByteArray = captureGif(
        project,directory,state,isBright,themeIndex,durationMillis,fps,onProgress,::newCaptureSession,
    )

    internal suspend fun captureGif(
        project:WmWatchfaceProject,
        directory:File,
        state:WmTagsData.WmPreviewState,
        isBright:Boolean,
        themeIndex:Int,
        durationMillis:Long,
        fps:Int,
        onProgress:suspend (completedFrames:Int,totalFrames:Int)->Unit,
        sessionFactory:(File)->RenderSession,
    ):ByteArray = withContext(Dispatchers.Default) {
        require(durationMillis in MIN_DURATION_MILLIS..MAX_DURATION_MILLIS) {
            "GIF duration must be between 1 and 10 seconds."
        }
        require(fps in MIN_FPS..MAX_FPS) { "GIF frame rate must be between 1 and 30 fps." }
        val frameCount=ceil(durationMillis/1_000.0*fps).toInt().coerceAtLeast(1)
        val render=sessionFactory(directory)
        try {
            render.prepareForCapture(project,state,isBright,themeIndex)
            if(isBright)primeVideoFrames(project,directory,render,"preview-gif-prime")
            ByteArrayOutputStream().use { bytes ->
                requireNotNull(ImageIO.createImageOutputStream(bytes)).use { output ->
                    val writer=GifSequenceWriter(output)
                    try {
                        withOffscreenWatchScene(project,render,captureDevice()) {
                            pump()
                            val started=System.nanoTime()
                            var previousOffset=0L
                            repeat(frameCount) { index ->
                                currentCoroutineContext().ensureActive()
                                val offset=(index*1_000L)/fps
                                awaitCaptureTime(started,offset)
                                render.advanceCaptureFrame(
                                    now=state.now.plusNanos(offset*1_000_000),
                                    captureElapsedSeconds=state.elapsedSeconds+offset/1_000.0,
                                    deltaMillis=offset-previousOffset,
                                )
                                previousOffset=offset
                                sceneRenderAndWrite(writer,index,frameCount,durationMillis)
                                withContext(Dispatchers.Default){onProgress(index+1,frameCount)}
                            }
                        }
                    } finally {
                        writer.close()
                    }
                }
                bytes.toByteArray()
            }
        } finally {
            withContext(NonCancellable+Dispatchers.IO){render.close()}
        }
    }

    private fun newCaptureSession(directory:File)=
        RenderSession(directory,autoPlay=false,silentMedia=true)

    private fun captureDevice()=DevicePreviewSettings(
        width=CAPTURE_SIZE,
        height=CAPTURE_SIZE,
        preset=CanvasPreset.FIT,
    )

    private suspend fun awaitCaptureTime(startedNanos:Long,offsetMillis:Long) {
        while(true) {
            val remaining=offsetMillis-(System.nanoTime()-startedNanos)/1_000_000
            if(remaining<=0)return
            delay(remaining)
        }
    }

    private suspend fun OffscreenWatchScene.sceneRenderAndWrite(
        writer:GifSequenceWriter,
        frameIndex:Int,
        frameCount:Int,
        durationMillis:Long,
    ) {
        val pixels=renderPixels()
        val delayHundredths=frameDelayHundredths(frameIndex,frameCount,durationMillis)
        withContext(Dispatchers.Default) {
            writer.write(toBufferedImage(pixels),delayHundredths,frameIndex==0)
        }
    }

    private fun frameDelayHundredths(index:Int,count:Int,durationMillis:Long):Int {
        val total=(durationMillis/10.0).roundToInt()
        val start=(index*total.toDouble()/count).roundToInt()
        val end=((index+1)*total.toDouble()/count).roundToInt()
        return (end-start).coerceAtLeast(1)
    }

    private fun encodePng(pixels:IntArray):ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(toBufferedImage(pixels),"png",output)){"No PNG ImageIO writer is available."}
        output.toByteArray()
    }

    private fun toBufferedImage(pixels:IntArray)=BufferedImage(
        CAPTURE_SIZE,CAPTURE_SIZE,BufferedImage.TYPE_INT_ARGB,
    ).also { image ->
        image.setRGB(0,0,CAPTURE_SIZE,CAPTURE_SIZE,pixels,0,CAPTURE_SIZE)
    }
}

private class GifSequenceWriter(private val output:javax.imageio.stream.ImageOutputStream):AutoCloseable {
    private val writer:ImageWriter=ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
        ?: error("No GIF ImageIO writer is available.")
    private var sequenceStarted=false
    private var closed=false

    init {
        try {
            writer.output=output
            writer.prepareWriteSequence(null)
            sequenceStarted=true
        } catch(failure:Throwable) {
            writer.dispose()
            throw failure
        }
    }

    fun write(image:BufferedImage,delayHundredths:Int,first:Boolean) {
        check(!closed)
        val metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),writer.defaultWriteParam)
        val format=metadata.nativeMetadataFormatName
        val root=metadata.getAsTree(format) as IIOMetadataNode
        val control=root.children("GraphicControlExtension").single()
        control.setAttribute("disposalMethod","none")
        control.setAttribute("delayTime",delayHundredths.toString())
        control.setAttribute("userInputFlag","FALSE")
        if(first) {
            val extensions=root.children("ApplicationExtensions").firstOrNull()
                ?: IIOMetadataNode("ApplicationExtensions").also(root::appendChild)
            IIOMetadataNode("ApplicationExtension").also { loop ->
                loop.setAttribute("applicationID","NETSCAPE")
                loop.setAttribute("authenticationCode","2.0")
                loop.userObject=byteArrayOf(1,0,0)
                extensions.appendChild(loop)
            }
        }
        metadata.setFromTree(format,root)
        writer.writeToSequence(IIOImage(image,null,metadata),writer.defaultWriteParam)
    }

    override fun close() {
        if(closed)return
        closed=true
        try {
            if(sequenceStarted)writer.endWriteSequence()
        } finally {
            writer.dispose()
        }
    }

    private fun IIOMetadataNode.children(name:String):List<IIOMetadataNode> = buildList {
        val nodes=getElementsByTagName(name)
        for(index in 0 until nodes.length)add(nodes.item(index) as IIOMetadataNode)
    }
}
