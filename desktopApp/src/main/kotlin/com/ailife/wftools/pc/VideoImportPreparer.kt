package com.ailife.wftools.pc

import com.ailife.wftools.pc.render.Mp4Rotation
import com.ailife.wftools.pc.render.WatchVideoCompatibility
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale

class PreparedVideoImports internal constructor(val paths:List<Path>,private val temporaryDirectory:Path?):AutoCloseable {
    override fun close() {
        temporaryDirectory?.takeIf(Files::exists)?.let { directory ->
            runCatching{Files.walk(directory).use{stream->stream.sorted(Comparator.reverseOrder()).forEach{runCatching{Files.deleteIfExists(it)}}}}
        }
    }
}

class VideoImportPreparer(
    private val codec:(Path)->String?=WatchVideoCompatibility::codecName,
    private val transcode:(Path,Path)->Unit=WatchVideoCompatibility::transcodeToH264,
    private val stripRotation:(Path,Path)->Boolean=Mp4Rotation::stripRotation,
    private val isRotated:(Path)->Boolean=Mp4Rotation::isRotated,
) {
    fun prepare(paths:List<Path>):PreparedVideoImports {
        var temporaryDirectory:Path?=null
        return try {
            val prepared=paths.map { source ->
                if(!isVideo(source))source
                else if(WatchVideoCompatibility.isH264Codec(codec(source))) {
                    // 已经是 H.264 也要处理旋转标记：编辑器用的 LibVLC 会应用它，而手表上的
                    // CloX/axmol 忽略它（上游 ece0bd5 修的是同一处不一致）。去掉提示不重编码，
                    // 两个播放器看到的就是同一画面；没有旋转标记时原样放行。
                    if(!isRotated(source))source
                    else {
                        val directory=temporaryDirectory ?: Files.createTempDirectory("wftools-h264-").also{temporaryDirectory=it}
                        val target=uniqueTarget(directory,stemOf(source))
                        if(stripRotation(source,target))target else source
                    }
                } else {
                    val directory=temporaryDirectory ?: Files.createTempDirectory("wftools-h264-").also{temporaryDirectory=it}
                    val target=uniqueTarget(directory,stemOf(source))
                    transcode(source,target)
                    target
                }
            }
            PreparedVideoImports(prepared,temporaryDirectory)
        } catch(error:Exception) {
            PreparedVideoImports(emptyList(),temporaryDirectory).close()
            throw error
        }
    }

    private fun stemOf(source:Path)=source.fileName.toString().substringBeforeLast('.',source.fileName.toString()).replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"),"_").ifBlank{"video"}

    private fun uniqueTarget(directory:Path,stem:String):Path {
        var target=directory.resolve("$stem.mp4");var suffix=2
        while(Files.exists(target))target=directory.resolve("${stem}_${suffix++}.mp4")
        return target
    }

    companion object {
        private val videoExtensions=setOf("mp4","webm","mov","m4v","avi","mkv")
        fun isVideo(path:Path)=path.fileName.toString().substringAfterLast('.',"").lowercase(Locale.ROOT) in videoExtensions
        fun containsVideo(paths:List<Path>)=paths.any(::isVideo)
    }
}
