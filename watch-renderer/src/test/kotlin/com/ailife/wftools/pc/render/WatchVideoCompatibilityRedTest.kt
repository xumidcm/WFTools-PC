package com.ailife.wftools.pc.render

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class WatchVideoCompatibilityRedTest {
    @Test fun `watch video compatibility recognizes AVC names and requests H264 MP4 output`() {
        assertTrue(WatchVideoCompatibility.isH264Codec("h264"))
        assertTrue(WatchVideoCompatibility.isH264Codec("avc1"))
        assertFalse(WatchVideoCompatibility.isH264Codec("vp9"))
        val source=Path.of("C:/素材/source.webm");val target=Path.of("C:/输出/converted.mp4")
        val args=WatchVideoCompatibility.transcodeArguments(source,target)
        assertTrue(args.any{it.contains("vcodec=h264")})
        assertTrue(args.any{it.contains(target.toString())})
        assertTrue(args.contains(source.toString()))
    }
}
