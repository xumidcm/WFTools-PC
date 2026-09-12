package com.ailife.wftools.pc

import java.io.BufferedReader
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Small, platform-neutral localisation layer for the Compose Desktop UI.
 * The source text remains the Simplified Chinese fallback so existing documents
 * and diagnostics stay readable even when a key is not yet translated.
 */
object DesktopI18n {
    private const val BUNDLE = "i18n.messages"
    private val english by lazy { loadEnglish() }

    private fun systemLocale(): Locale = Locale.getDefault().let { if (it.language.equals("zh", true)) it else Locale.ENGLISH }

    var locale: Locale by mutableStateOf(systemLocale())
        private set

    val isEnglish: Boolean get() = locale.language.equals(Locale.ENGLISH.language, ignoreCase = true)

    fun setLanguage(language: DesktopLanguage) {
        locale = when (language) {
            DesktopLanguage.SYSTEM -> systemLocale()
            DesktopLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            DesktopLanguage.ENGLISH -> Locale.ENGLISH
        }
    }

    fun language(): DesktopLanguage = when {
        locale.language.equals("zh", true) -> DesktopLanguage.CHINESE
        else -> DesktopLanguage.ENGLISH
    }

    fun text(source: String, requestedLocale: Locale = locale): String {
        if (!requestedLocale.language.equals(Locale.ENGLISH.language, ignoreCase = true)) return source
        val key = source.trim()
        if (key.isEmpty()) return source
        return english[key] ?: translateFragments(source)
    }

    private fun loadEnglish(): Map<String, String> {
        val resource = "/${BUNDLE.replace('.', '/')}_en.properties"
        val stream = DesktopI18n::class.java.getResourceAsStream(resource) ?: return emptyMap()
        return stream.bufferedReader().use(BufferedReader::readLines).asSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith('#') && !it.trimStart().startsWith('!') }
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { index -> unescape(line.substring(0, index)) to unescape(line.substring(index + 1)) } }
            .toMap()
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                when (val escaped = value[index + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(escaped)
                }
                index += 2
            } else {
                append(value[index++])
            }
        }
    }

    private val fragments = listOf(
        "正在保存 " to "Saving ", "正在导入 " to "Importing ", "正在替换 " to "Replacing ",
        "正在删除" to "Deleting", "正在生成截图…" to "Generating screenshot…",
        "正在录制 GIF…" to "Recording GIF…", "正在关闭…" to "Closing…",
        "正在重新加载原文件…" to "Reloading the original file…", "正在处理…" to "Processing…",
        "正在保存全局脚本…" to "Saving global script…", "正在创建素材图层…" to "Creating asset layer…",
        "正在创建文字图层…" to "Creating text layer…", "正在导入表盘素材…" to "Importing watch-face assets…",
        "正在导入素材…" to "Importing assets…", "正在导入素材并创建图层…" to "Importing assets and creating layers…",
        "正在导入音频…" to "Importing audio…", "正在调整图层顺序…" to "Reordering layers…",
        "正在生成受保护表盘…" to "Generating protected watch face…", "正在替换素材…" to "Replacing assets…",
        "正在应用图层变换…" to "Applying layer transform…", "正在粘贴图层与素材…" to "Pasting layers and assets…",
        "正在粘贴图片并创建图层…" to "Pasting image and creating layer…", "正在删除素材…" to "Deleting asset…",
        "正在删除音频…" to "Deleting audio…", "文件已保存，但恢复草稿写入失败：" to "File saved, but writing the recovery draft failed: ",
        "已导出：" to "Exported: ", "导出失败：" to "Export failed: ", "失败：" to "Failed: ",
        "已将 " to "Copied ", " 个素材复制到素材库。" to " assets to the library.",
        "已将 " to "Copied ", "个素材复制到素材库。" to " assets to the library.",
        "已用时间：" to "Elapsed time: ", "预览偏好保存失败：" to "Failed to save preview preferences: ",
        "项目已打开，但最近项目记录失败：" to "Project opened, but recording it in recent projects failed: ",
        "未保存草稿 · " to "Unsaved draft · ", "查看 " to "View ", "项" to "items",
        "脚本：" to "Script: ", "预览：" to "Preview: ", "选择" to "Choose ", "替换" to "Replace ",
        "参数 " to "Parameter ", "图层 " to "Layer ", "主题 " to "Theme ", "正在打开 " to "Opening ",
        "正在替换素材" to "Replacing assets", "正在导入表盘" to "Importing watch face",
        "当前由 WFTools-PC 打开；" to "Currently opened by WFTools-PC; ",
        "将 .watch 文件直接关联到当前安装的 WFTools-PC。" to "Associate .watch files with the installed WFTools-PC.",
        "可以在新窗口打开 " to "Open ", "或只把其中的素材导入当前项目。" to " in a new window, or import only its assets into the current project.",
        "无法打开" to "Cannot open ", "无法删除" to "Cannot delete ", "无法读取" to "Cannot read ",
        "无法保存" to "Cannot save ", "请先" to "Please ", "请使用" to "Please use ",
        "已存在，要替换它吗？" to " already exists. Replace it?", "仅安装版" to "Only the installed version ",
        " 个图层" to " layers", " 个字符" to " characters", " 个文件" to " files",
        " 个素材" to " assets", " 项" to " items", " 帧" to " frames",
        "已保存" to "Saved", "正在充电" to "Charging", "已开启" to "Enabled",
        "当前温度" to "Current temperature", "手表电量" to "Watch battery",
        "手机电量" to "Phone battery", "设备宽度" to "Device width", "设备高度" to "Device height",
        "保存对“" to "Save changes to “", "”的修改？" to "”?", "新建表盘" to "New Watch Face",
    )

    private fun translateFragments(source: String): String {
        var result = source
        fragments.sortedByDescending { it.first.length }.forEach { (from, to) -> result = result.replace(from, to) }
        return result
    }
}

enum class DesktopLanguage { SYSTEM, CHINESE, ENGLISH }
