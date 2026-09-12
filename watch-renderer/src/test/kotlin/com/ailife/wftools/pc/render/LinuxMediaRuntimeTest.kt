package com.ailife.wftools.pc.render

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinuxMediaRuntimeTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun `VLC runtime names and locations follow the host platform`() {
        assertEquals(listOf("libvlc.so","libvlc.so.5"),vlcLibraryNames("Linux"))
        assertEquals(listOf("libvlc.dll"),vlcLibraryNames("Windows 11"))
        assertEquals("vlc",vlcExecutableName("Linux"))
        assertEquals("vlc.exe",vlcExecutableName("Windows 11"))

        val linuxLib=temp.newFolder("linux-lib")
        File(linuxLib,"libvlc.so").writeText("fixture")
        assertEquals(linuxLib,findVlcLibraryDirectory(listOf(linuxLib),"Linux"))
        val windowsLib=temp.newFolder("windows-lib")
        File(windowsLib,"libvlc.dll").writeText("fixture")
        assertEquals(windowsLib,findVlcLibraryDirectory(listOf(windowsLib),"Windows 11"))
    }

    @Test fun `Linux VLC executable is resolved from PATH without requiring a bundled exe`() {
        val bin=temp.newFolder("bin")
        val vlc=File(bin,"vlc").apply{writeText("#!/bin/sh\n");setExecutable(true)}
        assertEquals(vlc.canonicalFile,findVlcExecutable(null,listOf(bin),"Linux").canonicalFile)
    }
}
