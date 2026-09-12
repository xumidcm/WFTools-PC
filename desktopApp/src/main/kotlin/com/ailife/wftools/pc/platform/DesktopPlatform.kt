package com.ailife.wftools.pc.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

enum class DesktopOperatingSystem { WINDOWS, LINUX, MACOS, OTHER }

fun desktopOperatingSystem(osName:String=System.getProperty("os.name").orEmpty()):DesktopOperatingSystem = when {
    osName.lowercase(Locale.ROOT).contains("win") -> DesktopOperatingSystem.WINDOWS
    osName.lowercase(Locale.ROOT).contains("linux") -> DesktopOperatingSystem.LINUX
    osName.lowercase(Locale.ROOT).contains("mac") || osName.lowercase(Locale.ROOT).contains("darwin") -> DesktopOperatingSystem.MACOS
    else -> DesktopOperatingSystem.OTHER
}

fun desktopPlatformLabel(os:DesktopOperatingSystem=desktopOperatingSystem()):String=when(os) {
    DesktopOperatingSystem.WINDOWS -> "Windows 桌面版"
    DesktopOperatingSystem.LINUX -> "Linux 桌面版"
    DesktopOperatingSystem.MACOS -> "macOS 桌面版"
    DesktopOperatingSystem.OTHER -> "桌面版"
}

fun fileManagerRevealCommand(path:Path,os:DesktopOperatingSystem=desktopOperatingSystem()):List<String> {
    val absolute=path.normalize()
    return when(os) {
        DesktopOperatingSystem.WINDOWS -> listOf("explorer.exe","/select,",absolute.toString())
        DesktopOperatingSystem.LINUX -> listOf("gio","open",(absolute.parent?:absolute).toString())
        DesktopOperatingSystem.MACOS -> listOf("open","-R",absolute.toString())
        DesktopOperatingSystem.OTHER -> listOf("xdg-open",(absolute.parent?:absolute).toString())
    }
}

fun showInFileManager(path:Path) {
    ProcessBuilder(fileManagerRevealCommand(path)).start()
}

internal fun linuxXdgDataHome(
    homeDirectory:Path=Path.of(System.getProperty("user.home")),
    environment:Map<String,String> = System.getenv(),
):Path=environment["XDG_DATA_HOME"]?.takeIf{it.startsWith('/')&&it.isNotBlank()}?.let(Path::of)
    ?:homeDirectory.resolve(".local/share")

internal fun linuxXdgConfigHome(
    homeDirectory:Path=Path.of(System.getProperty("user.home")),
    environment:Map<String,String> = System.getenv(),
):Path=environment["XDG_CONFIG_HOME"]?.takeIf{it.startsWith('/')&&it.isNotBlank()}?.let(Path::of)
    ?:homeDirectory.resolve(".config")

internal fun linuxXdgDataDirectories(environment:Map<String,String> = System.getenv()):List<Path> =
    environment["XDG_DATA_DIRS"]?.takeIf(String::isNotBlank)?.split(':')?.filter{it.startsWith('/')&&it.isNotBlank()}?.map(Path::of)
        ?.takeIf(List<Path>::isNotEmpty)?:listOf(Path.of("/usr/local/share"),Path.of("/usr/share"))

internal fun ensureOwnerOnlyDirectory(path:Path):Path {
    Files.createDirectories(path)
    if(desktopOperatingSystem()==DesktopOperatingSystem.LINUX&&runCatching{Files.getFileStore(path).supportsFileAttributeView("posix")}.getOrDefault(false))
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"))
    return path
}

internal fun ensureOwnerOnlyFile(path:Path):Path {
    if(desktopOperatingSystem()==DesktopOperatingSystem.LINUX&&runCatching{Files.getFileStore(path).supportsFileAttributeView("posix")}.getOrDefault(false))
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
    return path
}
