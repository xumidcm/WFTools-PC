package com.ailife.wftools.pc.render

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/** WatchMaker video layers are normalized to H.264 in an MP4 container at import time. */
object WatchVideoCompatibility {
    fun isH264Codec(codec:String?):Boolean {
        val normalized=codec?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized=="h264"||normalized=="avc"||normalized=="avc1"||normalized.contains("h.264")||normalized.contains("h264")
    }

    fun codecName(source:Path):String? {
        require(Files.isRegularFile(source)){"找不到视频文件：$source"}
        val factory=createBundledMediaPlayerFactory()
        try {
            val media=factory.media().newMedia(source.toAbsolutePath().normalize().toString())
            try {
                media.parsing().parse(5_000)
                return media.info().videoTracks().firstOrNull()?.codecName()
            } finally { media.release() }
        } finally { factory.release() }
    }

    fun transcodeArguments(source:Path,target:Path):List<String> {
        val input=source.toAbsolutePath().normalize().toString()
        val output=target.toAbsolutePath().normalize().toString().replace("\"","\\\"")
        val sout="#transcode{vcodec=h264,venc=x264{profile=baseline,preset=medium},acodec=mp4a,ab=128,channels=2}:standard{access=file,mux=mp4,dst=\"$output\"}"
        return listOf("-I","dummy","--dummy-quiet","--no-video-title-show","--play-and-exit","--sout",sout,input,"vlc://quit")
    }

    fun transcodeToH264(source:Path,target:Path) {
        require(Files.isRegularFile(source)){"找不到视频文件：$source"}
        Files.createDirectories(target.toAbsolutePath().normalize().parent)
        Files.deleteIfExists(target)
        val executable=locateBundledVlcDirectory().resolve("vlc.exe")
        require(executable.isFile){"找不到随程序提供的视频转换器。"}
        val process=ProcessBuilder(listOf(executable.absolutePath)+transcodeArguments(source,target)).redirectErrorStream(true).start()
        val output=StringBuilder()
        val drain=Thread({process.inputStream.bufferedReader().useLines{lines->lines.forEach{line->if(output.length<8_000)output.appendLine(line)}}},"wftools-vlc-output").apply{isDaemon=true;start()}
        if(!process.waitFor(10,TimeUnit.MINUTES)) {
            process.destroyForcibly()
            drain.join(2_000)
            throw IllegalStateException("视频转码超时，已停止转换。")
        }
        drain.join(2_000)
        check(process.exitValue()==0&&Files.isRegularFile(target)&&Files.size(target)>0){"视频转码失败：${output.takeLast(600).trim()}"}
        check(isH264Codec(codecName(target))){"视频转换完成，但输出编码不是 H.264。"}
    }
}
