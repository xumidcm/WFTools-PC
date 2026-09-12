package com.ailife.wftools.pc

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
) {
    fun prepare(paths:List<Path>):PreparedVideoImports {
        var temporaryDirectory:Path?=null
        return try {
            val prepared=paths.map { source ->
                if(!isVideo(source)||WatchVideoCompatibility.isH264Codec(codec(source)))source
                else {
                    val directory=temporaryDirectory ?: Files.createTempDirectory("wftools-h264-").also{temporaryDirectory=it}
                    val stem=source.fileName.toString().substringBeforeLast('.',source.fileName.toString()).replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"),"_").ifBlank{"video"}
                    var target=directory.resolve("$stem.mp4");var suffix=2
                    while(Files.exists(target))target=directory.resolve("${stem}_${suffix++}.mp4")
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

    companion object {
        private val videoExtensions=setOf("mp4","webm","mov","m4v","avi","mkv")
        fun isVideo(path:Path)=path.fileName.toString().substringAfterLast('.',"").lowercase(Locale.ROOT) in videoExtensions
        fun containsVideo(paths:List<Path>)=paths.any(::isVideo)
    }
}
