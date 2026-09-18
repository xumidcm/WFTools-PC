package com.ailife.wftools.pc

import com.ailife.wftools.pc.platform.DesktopOperatingSystem
import com.ailife.wftools.pc.platform.desktopOperatingSystem

/**
 * 表盘市场入口。桌面端目前只接入 Watchmaker Community v2，并把下载后的
 * `.watch` 交给现有的打开/另存为流程，不在编辑器里解包未知归档。
 */
object WatchMarket {
    /** 市场网页版；「在浏览器打开」与首页入口都指向这里。 */
    const val HOME_URL: String = "https://jbwatchface.ai-life.xyz/"

    /** Watchmaker Community v2 的 API 根地址。 */
    const val API_ROOT: String = "https://jbwatchface.ai-life.xyz/api"
}

/** 下载完成后可供用户选择的动作。 */
enum class MarketDownloadAction {
    /** 直接在当前应用里打开成编辑窗口。 */
    OPEN_IN_EDITOR,

    /** 在系统的文件管理器里定位文件。 */
    REVEAL_IN_FILE_MANAGER,

    /** 复制到用户指定的位置。 */
    SAVE_AS,

    /** 把绝对路径放进系统剪贴板。 */
    COPY_PATH,
}

/**
 * 按平台实际能力给出下载完成后的动作集合。
 *
 * Windows 与 Linux 都支持打开、另存为和复制路径；只有在文件管理器命令确实
 * 可用时才提供「在文件管理器中显示」，避免给出点了没有反应的按钮。
 */
fun marketDownloadActions(
    os: DesktopOperatingSystem = desktopOperatingSystem(),
    fileManagerAvailable: Boolean = true,
): List<MarketDownloadAction> = buildList {
    add(MarketDownloadAction.OPEN_IN_EDITOR)
    if (fileManagerAvailable) add(MarketDownloadAction.REVEAL_IN_FILE_MANAGER)
    add(MarketDownloadAction.SAVE_AS)
    add(MarketDownloadAction.COPY_PATH)
}

/** 「在文件管理器中显示」在各平台上的按钮文案。 */
fun marketRevealLabel(os: DesktopOperatingSystem = desktopOperatingSystem()): String = when (os) {
    DesktopOperatingSystem.WINDOWS -> "在资源管理器中显示"
    DesktopOperatingSystem.LINUX -> "在文件管理器中显示"
    DesktopOperatingSystem.MACOS -> "在 Finder 中显示"
    DesktopOperatingSystem.OTHER -> "打开所在目录"
}

/**
 * The actions this machine can actually perform.
 *
 * Windows always has Explorer. Linux shells out to `gio` or `xdg-open`, which are not present
 * on every install, so the reveal action is dropped rather than shown as a dead button.
 */
fun marketWatchActions(os: DesktopOperatingSystem = desktopOperatingSystem()): List<MarketDownloadAction> =
    marketDownloadActions(os, fileManagerAvailable = fileManagerAvailable(os))

/** True when a file-manager command for this platform resolves on PATH. */
fun fileManagerAvailable(os: DesktopOperatingSystem = desktopOperatingSystem()): Boolean = when (os) {
    DesktopOperatingSystem.WINDOWS, DesktopOperatingSystem.MACOS -> true
    DesktopOperatingSystem.LINUX -> listOf("gio", "xdg-open").any(::commandOnPath)
    DesktopOperatingSystem.OTHER -> commandOnPath("xdg-open")
}

private fun commandOnPath(command: String): Boolean = runCatching {
    val path = System.getenv("PATH").orEmpty()
    path.split(java.io.File.pathSeparatorChar).any { directory ->
        directory.isNotBlank() && java.io.File(directory, command).let { it.isFile && it.canExecute() }
    }
}.getOrDefault(false)
