@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.core.EditorLayer
import com.ailife.wftools2.domain.editor.watchmaker.*
import com.ailife.wftools2.ui.editor.components.WmLayerRender
import com.ailife.wftools2.ui.editor.components.wmLayerBounds
import com.ailife.wftools2.ui.editor.utils.PreviewBitmapCache
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Flue's independent material preview: normalized position, real rendering, alpha crop. */
object LayerThumbnails {
    private val dispatcher=Executors.newSingleThreadExecutor{task->Thread(task,"WFTools layer thumbnails").apply{isDaemon=true}}.asCoroutineDispatcher()
    private val videoFrames=object:LinkedHashMap<String,ImageBitmap>(32,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,ImageBitmap>?)=size>64}

    suspend fun render(layer:EditorLayer,session:RenderSession,sizePx:Int=96):ImageBitmap? = withContext(dispatcher) {
        require(sizePx in 16..256)
        if(layer.type in setOf("image","image_gif","image_cond")) {
            val file=resolveAsset(session.directory,layer.attrs["path"].orEmpty()) ?: return@withContext null
            return@withContext PreviewBitmapCache.load(file.absolutePath,sizePx,sizePx)
        }
        if(layer.type=="video") {
            val file=resolveAsset(session.directory,layer.attrs["path"].orEmpty()) ?: return@withContext null
            val key=file.absolutePath+":"+file.lastModified()+":"+file.length()+":"+sizePx
            videoFrames[key]?.let{return@withContext it}
            val frame=withContext(Dispatchers.IO) {
                MediaPlayback{createBundledMediaPlayerFactory(listOf("--no-audio"))}.use { media ->
                    val player=media.video(file)
                    player.setOwnerPlaying("thumbnail",true)
                    withTimeoutOrNull(5000){player.frames.filterNotNull().first()}
                }
            } ?: return@withContext null
            return@withContext Image.makeRaster(org.jetbrains.skia.ImageInfo.makeN32Premul(frame.width,frame.height),frame.bytes,frame.width*4).use{fitImage(it,sizePx,false)}?.also{videoFrames[key]=it}
        }
        if(layer.type !in setOf("text","text_curved","text_ring","shape","rounded","ring","progress","markers","markers_hm","calendar","chart","series"))return@withContext null
        val attributes=LinkedHashMap(layer.attrs).apply {
            put("x","0");put("y","0");put("rotation","0");put("skew_x","0");put("skew_y","0");put("gyro","0")
            put("alignment","cc");put("anim_scale_x","100");put("anim_scale_y","100");put("display","bd");put("opacity","100");put("stencil","n")
            if(get("text").isNullOrBlank() && !get("value").isNullOrBlank())put("text",getValue("value"))
        }
        val normalized=WmWatchfaceLayer(layer.type,attributes)
        val read=Snapshot.takeSnapshot()
        val frame=try {read.enter{session.frame}}finally{read.dispose()}
        val tags=attributes.values.flatMap{Regex("\\{[^{}]+}").findAll(it).map{it.value}.toList()}.distinct().associateWith{WmTagsData.resolveTag(it,frame)}
        val bounds=wmLayerBounds(normalized,256f,frame,session.directory,includeScale=false)
        val scale=minOf(1f,460f/maxOf(bounds.w,bounds.h,1f))
        // ImageComposeScene is confined to its own dispatcher, so a list of text
        // thumbnails cannot monopolize the desktop event thread.
        val scene=ImageComposeScene(512,512,Density(1f),coroutineContext=coroutineContext) {
            CompositionLocalProvider(LocalMediaPlayback provides session.media,LocalPreviewRunning provides false) {
                Box(Modifier.requiredSize(512.dp).graphicsLayer {
                    transformOrigin=TransformOrigin(.5f,.5f);scaleX=scale;scaleY=scale
                }) {
                    WmLayerRender(normalized,tags,frame.variables,rememberUpdatedState(frame),frame.now,session.directory,Density(1f),true,session.script)
                }
            }
        }
        try {
            scene.render(System.nanoTime()).close();yield()
            scene.render(System.nanoTime()).use{fitImage(it,sizePx,true)}
        } finally {scene.close()}
    }

    private fun resolveAsset(directory:File,raw:String):File? {
        val path=raw.substringBefore('`').trim().replace('\\','/')
        if(path.isBlank() || path.startsWith('/') || path.contains("..") || path.contains(':'))return null
        return listOf(File(directory,path),File(directory,"images/$path"),File(directory,"videos/$path")).firstOrNull{it.isFile}
    }
    private fun fitImage(image:Image,size:Int,crop:Boolean):ImageBitmap? {
        val pixels=IntArray(image.width*image.height)
        image.toComposeImageBitmap().readPixels(pixels)
        var left=image.width;var top=image.height;var right=-1;var bottom=-1;var light=0.0;var weight=0.0
        pixels.forEachIndexed{i,color->
            val alpha=color ushr 24
            if(alpha>0){val x=i%image.width;val y=i/image.width;left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
                light+=(((color ushr 16 and 255)*.2126+(color ushr 8 and 255)*.7152+(color and 255)*.0722)/255)*alpha;weight+=alpha}
        }
        if(right<left)return null
        val source=if(crop)Rect.makeLTRB((left-3).coerceAtLeast(0).toFloat(),(top-3).coerceAtLeast(0).toFloat(),(right+4).coerceAtMost(image.width).toFloat(),(bottom+4).coerceAtMost(image.height).toFloat())
            else Rect.makeWH(image.width.toFloat(),image.height.toFloat())
        val scale=minOf((size-6)/source.width,(size-6)/source.height)
        val w=source.width*scale;val h=source.height*scale
        return Surface.makeRasterN32Premul(size,size).use{output->
            // Keep both authored dark and light lettering readable without tinting it.
            output.canvas.clear(if(light/weight>.5)0xff302d36.toInt()else 0xffeeeaf2.toInt())
            output.canvas.drawImageRect(image,source,Rect.makeXYWH((size-w)/2,(size-h)/2,w,h))
            output.makeImageSnapshot().toComposeImageBitmap()
        }
    }
}

@Composable fun rememberLayerThumbnail(layer:EditorLayer,session:RenderSession,sizePx:Int=96,revision:Long=0):ImageBitmap? {
    val thumbnail by produceState<ImageBitmap?>(null,layer.id,layer.attrs,layer.type,session,sizePx,revision) {
        do {
            value=try{LayerThumbnails.render(layer,session,sizePx)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){null}
            if(!layer.type.contains("text"))break
            delay(1000)
        }while(true)
    }
    return thumbnail
}
