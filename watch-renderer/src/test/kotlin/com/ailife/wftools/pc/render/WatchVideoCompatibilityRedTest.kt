package com.ailife.wftools.pc.render

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class WatchVideoCompatibilityRedTest {
    @Test fun `watch video compatibility recognizes AVC names and requests H264 MP4 output`() {
        assertTrue(WatchVideoCompatibility.isH264Codec("h264"))
        assertTrue(WatchVideoCompatibility.isH264Codec("avc1"))
        assertFalse(WatchVideoCompatibility.isH264Codec("vp9"))
        val root=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val source=root.resolve("素材/source.webm");val target=root.resolve("输出/converted.mp4")
        val args=WatchVideoCompatibility.transcodeArguments(source,target)
        assertTrue(args.any{it.contains("vcodec=h264")})
        assertTrue(args.any{it.contains(target.toString())})
        assertTrue(args.contains(source.toString()))
        assertFalse(args.contains("--dummy-quiet"))
        assertFalse(args.contains("vlc://quit"))
    }

    @Test fun `linux video normalization uses ffprobe and ffmpeg libx264`() {
        val root=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val source=root.resolve("input source.webm");val target=root.resolve("output target.mp4")
        val probe=WatchVideoCompatibility.ffprobeArguments(source)
        val transcode=WatchVideoCompatibility.ffmpegTranscodeArguments(source,target)

        assertTrue(probe.contains("-select_streams"));assertTrue(probe.contains("v:0"))
        assertTrue(probe.contains(source.toString()))
        assertTrue(transcode.contains("libx264"));assertTrue(transcode.contains("yuv420p"))
        assertTrue(transcode.contains("0:a?"));assertTrue(transcode.contains(source.toString()));assertTrue(transcode.contains(target.toString()))
    }
}
