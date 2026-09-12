package com.ailife.wftools.pc.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ailife.wftools2.ui.editor.utils.rememberPreviewBitmap
import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.*

data class DecodedAnimation(val frames:List<ImageBitmap>,val durations:List<Int>) {
    val totalMillis=durations.sum().coerceAtLeast(1)
    fun indexAt(elapsed:Long):Int {
        var t=Math.floorMod(elapsed,totalMillis.toLong())
        durations.forEachIndexed{index,duration->if(t<duration)return index else t-=duration}
        return frames.lastIndex.coerceAtLeast(0)
    }
}
fun decodeAnimation(file:File):DecodedAnimation {
    Data.makeFromBytes(file.readBytes()).use {data->Codec.makeFromData(data).use{codec->
        val count=codec.frameCount.coerceAtLeast(1)
        require(codec.width.toLong()*codec.height*4*count<=512L*1024*1024){"动画解码超过 512 MB，请使用较小素材。"}
        val images=ArrayList<ImageBitmap>(count)
        val times=ArrayList<Int>(count)
        for(index in 0 until count) {
            Bitmap().use {bitmap->
                bitmap.allocPixels(ImageInfo.makeN32Premul(codec.width,codec.height))
                codec.readPixels(bitmap,index)
                images+=Image.makeFromBitmap(bitmap).toComposeImageBitmap()
            }
            // Skia has no animation frame metadata for a still WebP, even though
            // frameCount reports one. Asking for it reads an undefined native record.
            times+=if(count>1)codec.getFrameInfo(index).duration.coerceAtLeast(1) else 100
        }
        return DecodedAnimation(images,times)
    }}
}
@Composable fun WmAnimatedImageLayer(paths:List<File>,delayMs:Long,clock:State<WmTagsData.WmPreviewState>,colorFilter:ColorFilter?=null) {
    if(paths.isEmpty())return
    val single=paths.singleOrNull()
    if(single!=null && single.extension.lowercase() in setOf("gif","webp")) {
        var error by remember(single){mutableStateOf<String?>(null)}
        val animation by produceState<DecodedAnimation?>(null,single.absolutePath,single.lastModified()) {
            value=withContext(Dispatchers.IO){runCatching{decodeAnimation(single)}.onFailure{error=it.message}.getOrNull()}
        }
        val decoded=animation
        if(decoded!=null) {
            val index by remember(decoded,clock){derivedStateOf{decoded.indexAt(clock.value.now.toLocalTime().toNanoOfDay()/1_000_000)}}
            Image(decoded.frames[index],null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds,colorFilter=colorFilter)
        }
        else if(error!=null)Text(error!!)
    } else {
        val index by remember(paths,delayMs,clock){derivedStateOf{
            Math.floorMod(clock.value.now.toLocalTime().toNanoOfDay()/1_000_000/delayMs.coerceAtLeast(1),paths.size.toLong()).toInt()
        }}
        val image=rememberPreviewBitmap(paths[index].absolutePath)
        image?.let{Image(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds,colorFilter=colorFilter)}
    }
}
