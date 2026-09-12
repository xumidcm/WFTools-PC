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
        if(isLinux()) {
            val process=ProcessBuilder(listOf(pathExecutable("ffprobe").toString())+ffprobeArguments(source)).redirectErrorStream(true).start()
            check(process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();"视频编码检测超时。"}
            val output=process.inputStream.bufferedReader().use{it.readText()}.trim()
            check(process.exitValue()==0){"无法检测视频编码：${output.takeLast(600)}"}
            return output.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotBlank)
        }
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
        return listOf("-I","dummy","--quiet","--no-video-title-show","--play-and-exit","--sout",sout,input)
    }

    fun ffprobeArguments(source:Path):List<String> = listOf(
        "-v","error","-select_streams","v:0","-show_entries","stream=codec_name",
        "-of","default=noprint_wrappers=1:nokey=1",source.toAbsolutePath().normalize().toString(),
    )

    fun ffmpegTranscodeArguments(source:Path,target:Path):List<String> = listOf(
        "-hide_banner","-loglevel","error","-nostdin","-y","-i",source.toAbsolutePath().normalize().toString(),
        "-map","0:v:0","-map","0:a?","-c:v","libx264","-profile:v","baseline","-pix_fmt","yuv420p",
        "-c:a","aac","-b:a","128k","-movflags","+faststart",target.toAbsolutePath().normalize().toString(),
    )

    fun transcodeToH264(source:Path,target:Path) {
        require(Files.isRegularFile(source)){"找不到视频文件：$source"}
        Files.createDirectories(target.toAbsolutePath().normalize().parent)
        Files.deleteIfExists(target)
        val command=if(isLinux())listOf(pathExecutable("ffmpeg").toString())+ffmpegTranscodeArguments(source,target)
            else listOf(locateVlcExecutable().absolutePath)+transcodeArguments(source,target)
        val process=ProcessBuilder(command).redirectErrorStream(true).start()
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

    private fun isLinux():Boolean=System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("linux")

    private fun pathExecutable(name:String):Path = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)
        .asSequence().filter(String::isNotBlank).map{Path.of(it).resolve(name)}.firstOrNull{Files.isRegularFile(it)&&Files.isExecutable(it)}
        ?:error("找不到 $name。Linux 请安装 ffmpeg；Windows 请重新安装 WFTools-PC。")
}
