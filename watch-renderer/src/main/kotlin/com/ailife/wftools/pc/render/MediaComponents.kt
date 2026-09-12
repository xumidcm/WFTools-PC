package com.ailife.wftools.pc.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import com.ailife.wftools2.ui.editor.utils.rememberPreviewBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

val LocalMediaPlayback = staticCompositionLocalOf<MediaPlayback> { error("Missing document media playback") }
val LocalPreviewRunning = staticCompositionLocalOf { true }
@Composable fun AsyncImage(model:Any?,contentDescription:String?,modifier:Modifier=Modifier,contentScale:ContentScale=ContentScale.Fit,colorFilter:ColorFilter?=null) {
    val file=when(model){is File->model;is String->File(model);else->null}
    val bitmap=rememberPreviewBitmap(file?.absolutePath)
    if(bitmap!=null)Image(bitmap,contentDescription,modifier,contentScale=contentScale,colorFilter=colorFilter)
}
@Composable fun WmVideoPlayerLayer(videoFile:File,playing:Boolean,colorFilter:ColorFilter?=null) {
    val media=LocalMediaPlayback.current
    val running=LocalPreviewRunning.current
    val owner=remember{java.util.UUID.randomUUID().toString()}
    var error by remember(videoFile){mutableStateOf<String?>(null)}
    val player by produceState<VideoPlayback?>(null,videoFile,media) {
        value=null;error=null
        val created=withContext(Dispatchers.IO){runCatching{media.video(videoFile)}}
        created.onSuccess{value=it}.onFailure{error=it.message}
    }
    val activePlayer=player
    if(activePlayer==null || error!=null){if(error!=null)Text(error!!,fontSize=12.sp);return}
    LaunchedEffect(activePlayer,playing,running) {
        val result=withContext(Dispatchers.IO){runCatching{activePlayer.setOwnerPlaying(owner,playing && running)}}
        result.onFailure{error=it.message}
    }
    DisposableEffect(activePlayer){onDispose{media.releaseOwner(activePlayer,owner)}}
    val frame by activePlayer.frames.collectAsState()
    val image=remember(activePlayer,frame?.sequence){frame?.let{org.jetbrains.skia.Image.makeRaster(org.jetbrains.skia.ImageInfo.makeN32Premul(it.width,it.height),it.bytes,it.width*4)}}
    DisposableEffect(image){onDispose{image?.close()}}
    image?.let{Image(it.toComposeImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds,colorFilter=colorFilter)}
}
private val http=OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build()
@Composable fun WmMapTileLayer(tileUrl:String,colorFilter:ColorFilter?) {
    val bitmap by produceState<ImageBitmap?>(null,tileUrl) {
        value=withContext(Dispatchers.IO){runCatching{
            http.newCall(Request.Builder().url(tileUrl).header("User-Agent","WFTools-PC/0.1 (watch editor)").build()).execute().use { response ->
                if(!response.isSuccessful) null else response.body?.bytes()?.let {org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap()}
            }
        }.getOrNull()}
    }
    val image=bitmap
    if(image!=null)Image(image,null,Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds,colorFilter=colorFilter)
    else Box(Modifier.fillMaxSize().background(Color(0xff263238)),contentAlignment=Alignment.Center){Text("地图加载中／离线",fontSize=12.sp,color=Color.White)}
}
@Composable fun VideoPlaceholderPanel(isGallery:Boolean=false) {
    Box(Modifier.fillMaxSize().background(Color(0xff263238)),contentAlignment=Alignment.Center){Text(if(isGallery)"上游未实现的图库图层" else "视频素材缺失",fontSize=12.sp,color=Color.White)}
}
