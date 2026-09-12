package com.ailife.wftools.pc.render

import com.sun.jna.NativeLibrary
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Semaphore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

data class VideoFrame(val width:Int,val height:Int,val bytes:ByteArray,val sequence:Long)

/** Players belong to a document, never to a recomposable layer's temporary lifetime. */
class MediaPlayback internal constructor(private val factoryProvider:()->MediaPlayerFactory) : AutoCloseable {
    constructor():this(::createBundledMediaPlayerFactory)
    private val lifecycleLock=Any()
    private var closed=false
    private val videos=mutableMapOf<String,VideoPlayback>()
    private val sounds=mutableListOf<MediaPlayer>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var initializedFactory:MediaPlayerFactory?=null
    private fun factoryLocked():MediaPlayerFactory {
        check(!closed){"此文档的媒体会话已关闭。"}
        // Assign only after successful initialization. close() never calls the
        // provider, including after an initialization exception.
        return initializedFactory ?: factoryProvider().also{initializedFactory=it}
    }
    fun video(file:File):VideoPlayback = synchronized(lifecycleLock) {
        check(!closed){"此文档的媒体会话已关闭。"}
        val identity=file.canonicalPath.let{if(File.separatorChar=='\\')it.lowercase(java.util.Locale.ROOT)else it}
        // Creation and registration form one lifecycle operation. close() cannot
        // release the factory between these two steps or miss a late new player.
        videos.getOrPut(identity){VideoPlayback(factoryLocked(),file)}
    }
    fun sound(file:File) {
        synchronized(lifecycleLock) {
            if(closed || !file.isFile)return@synchronized
            sounds.removeAll { player -> if(!player.status().isPlaying) {player.release();true}else false }
            factoryLocked().mediaPlayers().newMediaPlayer().also { sounds.add(it);it.media().play(file.absolutePath) }
        }
    }
    fun pauseAll()=synchronized(lifecycleLock){videos.values.toList()}.forEach {it.setPlaying(false)}
    fun releaseOwner(player:VideoPlayback,owner:String) {scope.launch{player.removeOwner(owner)}}
    override fun close() {
        val (players,audio,factory)=synchronized(lifecycleLock) {
            if(closed)return
            closed=true;scope.cancel()
            Triple(videos.values.toList(),sounds.toList(),initializedFactory).also {
                videos.clear();sounds.clear();initializedFactory=null
            }
        }
        players.forEach{runCatching{it.close()}}
        audio.forEach{runCatching{it.release()}}
        factory?.let{runCatching{it.release()}}
    }
}

internal fun createBundledMediaPlayerFactory():MediaPlayerFactory=createBundledMediaPlayerFactory(emptyList())
internal fun createBundledMediaPlayerFactory(options:List<String>):MediaPlayerFactory {
    val directory=locateBundledVlcDirectory()
    NativeLibrary.addSearchPath("libvlc",directory.absolutePath)
    NativeLibrary.addSearchPath("libvlccore",directory.absolutePath)
    // The runtime path is already known. VLCJ's default constructor recursively
    // scans host directories before honoring these paths (54 seconds measured
    // here, versus 139 ms for direct initialization), even when plugins.dat exists.
    return MediaPlayerFactory(null as NativeDiscovery?,*(listOf("--quiet","--no-video-title-show")+options).toTypedArray())
}

internal fun locateBundledVlcDirectory():File {
    val packaged=System.getProperty("compose.application.resources.dir")?.let {File(it,"vlc")}
    val candidates=if(packaged!=null)listOf(packaged) else listOfNotNull(System.getenv("WFTOOLS_VLC_HOME")?.let(::File),File("D:/DevTools/WFTools-PC/vlc/vlc-3.0.23"))
    return candidates.firstOrNull{File(it,"libvlc.dll").isFile} ?: error("找不到随程序提供的 LibVLC 运行库。")
}

class VideoPlayback(factory:MediaPlayerFactory,private val file:File):AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val sequence=AtomicLong()
    private val mutableFrame=MutableStateFlow<VideoFrame?>(null)
    val frames:StateFlow<VideoFrame?> = mutableFrame
    private val player:EmbeddedMediaPlayer=factory.mediaPlayers().newEmbeddedMediaPlayer()
    private var started=false
    private var playing=false
    private val owners=mutableMapOf<String,Boolean>()
    init {
      try {
        val formats=object:BufferFormatCallback {
            override fun getBufferFormat(sourceWidth:Int,sourceHeight:Int):BufferFormat = RV32BufferFormat(sourceWidth,sourceHeight)
            override fun newFormatSize(bufferWidth:Int,bufferHeight:Int,displayWidth:Int,displayHeight:Int) {}
            override fun allocatedBuffers(buffers:Array<ByteBuffer>) {}
        }
        // JNA can attach the same native decoder thread as different Java Threads
        // across callbacks. A thread-owned lock cannot span lock()/unlock().
        val frameLock=Semaphore(1)
        val render=object:RenderCallback {
            override fun lock(mediaPlayer:MediaPlayer){frameLock.acquireUninterruptibly()}
            override fun unlock(mediaPlayer:MediaPlayer){frameLock.release()}
            override fun display(mediaPlayer:MediaPlayer,buffers:Array<ByteBuffer>,format:BufferFormat,displayWidth:Int,displayHeight:Int) {
              frameLock.acquireUninterruptibly()
              try {
              if(!closed.get()) {
                val width=format.width; val height=format.height
                val stride=format.pitches[0]
                val pixels=ByteArray(width*height*4)
                val source=buffers[0].duplicate()
                for(y in 0 until height){source.position(y*stride);source.get(pixels,y*width*4,width*4)}
                for(i in 3 until pixels.size step 4)pixels[i]=(-1).toByte()
                mutableFrame.value=VideoFrame(width,height,pixels,sequence.incrementAndGet())
              }
              } finally {frameLock.release()}
            }
        }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(formats,render,true))
        player.controls().setRepeat(true)
      } catch(failure:Throwable) {
        runCatching{player.release()}
        throw failure
      }
    }
    @Synchronized fun setPlaying(value:Boolean) {
        if(closed.get() || playing==value)return
        if(value && !started) {
            check(player.media().play(file.absolutePath)){"无法打开视频：${file.name}"}
            started=true
        } else if(started)player.controls().setPause(!value)
        playing=value
    }
    @Synchronized fun setOwnerPlaying(owner:String,value:Boolean) {if(closed.get())return;owners[owner]=value;setPlaying(owners.values.any{it})}
    @Synchronized fun removeOwner(owner:String) {if(closed.get())return;owners.remove(owner);setPlaying(owners.values.any{it})}
    @Synchronized override fun close(){if(closed.compareAndSet(false,true)){owners.clear();player.release();mutableFrame.value=null}}
}
