package com.ailife.wftools2.ui.editor.utils

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import com.ailife.wftools.pc.graphics.BitmapFactory
import com.ailife.wftools.pc.graphics.asImageBitmap
import com.ailife.wftools.pc.graphics.pathIdentity
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cache ownership is independent of composition ownership: eviction never closes an image. */
internal class PreviewBitmapMemoryCache(private val maxBytes:Long) {
    private val entries=LinkedHashMap<String,ImageBitmap>(64,.75f,true)
    var byteCount:Long=0
        private set
    init {require(maxBytes>=0)}
    private fun bytes(image:ImageBitmap)=image.width.toLong()*image.height*4
    @Synchronized operator fun get(key:String)=entries[key]
    @Synchronized fun put(key:String,image:ImageBitmap) {
        entries.remove(key)?.let{byteCount-=bytes(it)}
        val size=bytes(image)
        if(size>maxBytes)return
        entries[key]=image;byteCount+=size
        val iterator=entries.entries.iterator()
        while(byteCount>maxBytes && iterator.hasNext()) {
            byteCount-=bytes(iterator.next().value);iterator.remove()
        }
    }
    @Synchronized fun removeWhere(predicate:(String)->Boolean) {
        val iterator=entries.entries.iterator()
        while(iterator.hasNext()) {
            val entry=iterator.next()
            if(predicate(entry.key)){byteCount-=bytes(entry.value);iterator.remove()}
        }
    }
    @Synchronized fun clear(){entries.clear();byteCount=0}
}

object PreviewBitmapCache {
    private val cache=PreviewBitmapMemoryCache((Runtime.getRuntime().maxMemory()/8).coerceIn(16L*1024*1024,256L*1024*1024))
    private val decodeMutex=Mutex()
    private val generation=AtomicLong()
    private fun key(file:File,reqW:Int,reqH:Int)=pathIdentity(file)+":"+file.lastModified()+":"+file.length()+":"+reqW.coerceAtLeast(0)+":"+reqH.coerceAtLeast(0)
    fun peek(path:String,reqW:Int=0,reqH:Int=0):ImageBitmap? = cache[key(File(path),reqW,reqH)]
    suspend fun load(path:String,reqW:Int=0,reqH:Int=0):ImageBitmap?=withContext(Dispatchers.IO) {
        val file=File(path)
        if(!file.isFile)return@withContext null
        val initialKey=key(file,reqW,reqH)
        cache[initialKey]?.let{return@withContext it}
        val startedGeneration=generation.get()
        // Limit simultaneous native decodes across windows. A waiter probes the
        // cache again so prewarming and a visible layer do not duplicate work.
        decodeMutex.withLock {
            cache[initialKey]?.let{return@withLock it}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0)return@withLock null
            val targetW=if(reqW>0)reqW else Int.MAX_VALUE
            val targetH=if(reqH>0)reqH else Int.MAX_VALUE
            var sample=1
            if(reqW>0 || reqH>0) {
                // An unspecified dimension does not constrain the aspect ratio.
                while(sample<=Int.MAX_VALUE/2 &&
                    (reqW<=0 || bounds.outWidth.toLong()/(sample*2L)>=targetW) &&
                    (reqH<=0 || bounds.outHeight.toLong()/(sample*2L)>=targetH))sample*=2
            }
            val decoded=BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=sample})?.asImageBitmap()
            currentCoroutineContext().ensureActive()
            if(key(file,reqW,reqH)!=initialKey)return@withLock null
            if(decoded!=null)synchronized(this@PreviewBitmapCache) {
                if(generation.get()==startedGeneration)cache.put(initialKey,decoded)
            }
            decoded
        }
    }
    suspend fun prewarm(dir:File) = withContext(Dispatchers.IO) {
        dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("png","jpg","jpeg","webp","gif","bmp") }.forEach { load(it.absolutePath) }
    }
    @Synchronized fun clearDirectory(directory:File) {
        generation.incrementAndGet()
        val prefix=pathIdentity(directory)+File.separator
        cache.removeWhere{it.startsWith(prefix)}
        BitmapFactory.clearDirectory(directory)
    }
    @Synchronized fun clear(){generation.incrementAndGet();cache.clear()}
}
@Composable fun rememberPreviewBitmap(path:String?,reqW:Int=0,reqH:Int=0):ImageBitmap? {
    if(path.isNullOrBlank())return null
    val file=File(path)
    val value by produceState(PreviewBitmapCache.peek(path,reqW,reqH),path,file.lastModified(),file.length(),reqW,reqH) {
        value=PreviewBitmapCache.peek(path,reqW,reqH)
        value=PreviewBitmapCache.load(path,reqW,reqH)
    }
    return value
}
@Composable fun rememberPreviewBitmaps(paths:List<String>,reqW:Int=0,reqH:Int=0):List<ImageBitmap?> = paths.map { rememberPreviewBitmap(it,reqW,reqH) }
