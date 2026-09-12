package com.ailife.wftools.pc.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LinuxDesktopPlatformTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun `desktop operating system and reveal commands stay platform specific`() {
        val asset=Path.of("/tmp/watch assets/photo.jpg")
        assertEquals(DesktopOperatingSystem.LINUX,desktopOperatingSystem("Linux"))
        assertEquals(DesktopOperatingSystem.WINDOWS,desktopOperatingSystem("Windows 11"))
        assertEquals("Linux 桌面版",desktopPlatformLabel(DesktopOperatingSystem.LINUX))
        assertEquals(listOf("gio","open",asset.parent.toString()),fileManagerRevealCommand(asset,DesktopOperatingSystem.LINUX))
        assertEquals(listOf("explorer.exe","/select,",asset.toString()),fileManagerRevealCommand(asset,DesktopOperatingSystem.WINDOWS))
        assertEquals(listOf("xdg-open","https://example.com"),externalUrlCommand("https://example.com",DesktopOperatingSystem.LINUX))
    }

    @Test fun `relative XDG overrides are ignored`() {
        val home=Path.of("/home/tester")
        assertEquals(home.resolve(".local/share"),linuxXdgDataHome(home,mapOf("XDG_DATA_HOME" to "relative/data")))
        assertEquals(home.resolve(".config"),linuxXdgConfigHome(home,mapOf("XDG_CONFIG_HOME" to "relative/config")))
        assertEquals(listOf(Path.of("/valid/share")),linuxXdgDataDirectories(mapOf("XDG_DATA_DIRS" to "relative:/valid/share")))
    }

    @Test fun `linux watch association writes user XDG files and restores the previous default`() {
        val home=temp.newFolder("home").toPath()
        val dataHome=home.resolve("xdg-data")
        val configHome=home.resolve("xdg-config")
        val launcher=temp.newFile("WFTools-PC").toPath()
        val previous="org.example.Previous.desktop"
        val previousEntry=dataHome.resolve("applications/$previous")
        Files.createDirectories(previousEntry.parent);Files.writeString(previousEntry,"[Desktop Entry]\nName=Previous\n")
        var currentDefault=previous
        val commands=mutableListOf<List<String>>()
        val runner=DesktopCommandRunner { arguments ->
            commands+=arguments
            when {
                arguments==listOf("xdg-mime","query","default",WATCH_MIME_TYPE) -> DesktopCommandResult(0,currentDefault)
                arguments.take(2)==listOf("xdg-mime","default") -> {currentDefault=arguments[2];DesktopCommandResult(0,"")}
                else -> DesktopCommandResult(0,"")
            }
        }
        val association=LinuxWatchFileAssociation(runner,{launcher},home,dataHome,configHome,listOf(dataHome))

        assertTrue(association.available)
        assertFalse(association.isAssociated())
        association.associate()

        assertTrue(association.isAssociated())
        val desktop=Files.readString(dataHome.resolve("applications/wftools-pc.desktop"))
        assertTrue(desktop.contains("MimeType=$WATCH_MIME_TYPE;"))
        assertTrue(desktop.contains("Exec=\"")&&desktop.contains(launcher.fileName.toString())&&desktop.contains("\" %f"))
        val mime=Files.readString(dataHome.resolve("mime/packages/wftools-pc-watch.xml"))
        assertTrue(mime.contains("type=\"$WATCH_MIME_TYPE\""))
        assertTrue(mime.contains("*.watch"))
        assertTrue(commands.contains(listOf("xdg-mime","default","wftools-pc.desktop",WATCH_MIME_TYPE)))

        association.unassociate()

        assertEquals(previous,currentDefault)
        assertFalse(Files.exists(dataHome.resolve("applications/wftools-pc.desktop")))
        assertFalse(Files.exists(dataHome.resolve("mime/packages/wftools-pc-watch.xml")))
        assertTrue(commands.contains(listOf("xdg-mime","default",previous,WATCH_MIME_TYPE)))
    }

    @Test fun `linux unassociate removes only WFTools default when no prior application existed`() {
        val home=temp.newFolder("empty-home").toPath()
        val dataHome=home.resolve("data")
        val configHome=home.resolve("config")
        val launcher=temp.newFile("linux-launcher").toPath()
        var currentDefault=""
        val runner=DesktopCommandRunner { arguments ->
            when {
                arguments==listOf("xdg-mime","query","default",WATCH_MIME_TYPE) -> DesktopCommandResult(0,currentDefault)
                arguments.take(2)==listOf("xdg-mime","default") -> {currentDefault=arguments[2];DesktopCommandResult(0,"")}
                else -> DesktopCommandResult(0,"")
            }
        }
        val association=LinuxWatchFileAssociation(runner,{launcher},home,dataHome,configHome,emptyList())
        association.associate()
        val mimeApps=configHome.resolve("mimeapps.list")
        Files.createDirectories(mimeApps.parent)
        Files.writeString(mimeApps,"[Default Applications]\n$WATCH_MIME_TYPE=wftools-pc.desktop;fallback.desktop;\ntext/plain=editor.desktop;\n")

        association.unassociate()

        assertFalse(Files.readString(mimeApps).contains("$WATCH_MIME_TYPE=wftools-pc.desktop"))
        assertTrue(Files.readString(mimeApps).contains("$WATCH_MIME_TYPE=fallback.desktop;"))
        assertTrue(Files.readString(mimeApps).contains("text/plain=editor.desktop"))
    }

    @Test fun `linux association honors custom XDG roots and system data directories`() {
        val home=temp.newFolder("xdg-home").toPath()
        val dataHome=temp.newFolder("custom-data").toPath()
        val configHome=temp.newFolder("custom-config").toPath()
        val systemData=temp.newFolder("system-data").toPath()
        val previous="org.example.System.desktop"
        Files.createDirectories(systemData.resolve("applications"))
        Files.writeString(systemData.resolve("applications/$previous"),"[Desktop Entry]\nName=System\n")
        val launcher=temp.newFile("xdg-WFTools-PC").toPath()
        var currentDefault=previous
        val runner=DesktopCommandRunner { arguments ->
            when {
                arguments==listOf("xdg-mime","query","default",WATCH_MIME_TYPE) -> DesktopCommandResult(0,currentDefault)
                arguments.take(2)==listOf("xdg-mime","default") -> {currentDefault=arguments[2];DesktopCommandResult(0,"")}
                else -> DesktopCommandResult(0,"")
            }
        }
        val association=LinuxWatchFileAssociation(runner,{launcher},home,dataHome,configHome,listOf(systemData))

        association.associate();association.unassociate()

        assertEquals(previous,currentDefault)
        assertFalse(Files.exists(home.resolve(".local/share/applications/wftools-pc.desktop")))
        assertFalse(Files.exists(home.resolve(".config/WFTools-PC/watch-association.state")))
        assertFalse(Files.exists(dataHome.resolve("applications/wftools-pc.desktop")))
        assertFalse(Files.exists(configHome.resolve("WFTools-PC/watch-association.state")))
    }

    @Test fun `failed linux association removes its transient default and refreshes restored caches`() {
        val home=temp.newFolder("rollback-home").toPath()
        val dataHome=home.resolve("data")
        val configHome=home.resolve("config")
        val launcher=temp.newFile("rollback-WFTools-PC").toPath()
        val mimeApps=configHome.resolve("mimeapps.list")
        var queryCount=0
        val commands=mutableListOf<List<String>>()
        val runner=DesktopCommandRunner { arguments ->
            commands+=arguments
            when {
                arguments==listOf("xdg-mime","query","default",WATCH_MIME_TYPE) -> {
                    queryCount++
                    DesktopCommandResult(0,if(queryCount==1)"" else "unexpected.desktop")
                }
                arguments==listOf("xdg-mime","default","wftools-pc.desktop",WATCH_MIME_TYPE) -> {
                    Files.createDirectories(mimeApps.parent)
                    Files.writeString(mimeApps,"[Default Applications]\n$WATCH_MIME_TYPE=wftools-pc.desktop;fallback.desktop;\n")
                    DesktopCommandResult(0,"")
                }
                else -> DesktopCommandResult(0,"")
            }
        }
        val association=LinuxWatchFileAssociation(runner,{launcher},home,dataHome,configHome,emptyList())

        assertThrows(IllegalStateException::class.java){association.associate()}

        assertFalse(Files.exists(dataHome.resolve("applications/wftools-pc.desktop")))
        assertFalse(Files.exists(dataHome.resolve("mime/packages/wftools-pc-watch.xml")))
        assertFalse(Files.exists(configHome.resolve("WFTools-PC/watch-association.state")))
        assertTrue(Files.readString(mimeApps).contains("$WATCH_MIME_TYPE=fallback.desktop;"))
        assertEquals(2,commands.count{it.firstOrNull()=="update-mime-database"})
        assertEquals(2,commands.count{it.firstOrNull()=="update-desktop-database"})
    }

    @Test fun `linux desktop entry escapes percent field codes and rejects line breaks`() {
        val entry=linuxDesktopEntry(Path.of("/opt/WF%Tools/WFTools-PC"))
        assertTrue(entry.contains("WF%%Tools"))
        assertThrows(IllegalArgumentException::class.java){linuxDesktopEntry(Path.of("bad\nlauncher"))}
    }

    @Test fun `linux private files are owner only when POSIX permissions are available`() {
        val directory=temp.newFolder("private-directory").toPath()
        if(!Files.getFileStore(directory).supportsFileAttributeView("posix"))return
        val file=directory.resolve("secret")
        Files.writeString(file,"token")

        ensureOwnerOnlyDirectory(directory)
        ensureOwnerOnlyFile(file)

        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory))
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file))
    }
}
