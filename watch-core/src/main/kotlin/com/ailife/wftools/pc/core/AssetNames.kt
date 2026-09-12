package com.ailife.wftools.pc.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal object AssetKinds {
    val images = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    val videos = setOf("mp4", "webm", "mov", "m4v", "avi", "mkv")
    val fonts = setOf("ttf", "otf", "fnt")
    val sounds = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
    val fontLayers = setOf("text", "text_curved", "text_ring", "series", "calendar", "chart")
    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun validateReplacement(layer: EditorLayer, attribute: String, source: Path) {
        if (!Files.isRegularFile(source)) throw WatchDocumentException.AssetImportFailed("找不到替换资源：$source")
        val ext = extension(source.fileName.toString())
        val valid = when (attribute) {
            "path" -> when (layer.type) {
                "image", "image_gif", "image_cond" -> ext in images
                "video" -> ext in videos
                else -> false
            }
            "font" -> layer.type in fontLayers && ext in fonts
            "font2" -> layer.type in setOf("series", "calendar", "chart") && ext in fonts
            else -> false
        }
        if (!valid) throw WatchDocumentException.AssetImportFailed(".${ext.ifBlank { "无扩展名" }} 不能替换 ${layer.type} 图层的 $attribute；请选择对应的图片、视频或字体。")
    }
}

internal class AssetNameAllocator(existing: Collection<String>) {
    private val names = existing.map { it.lowercase(Locale.ROOT) }.toMutableSet()
    private val directories = mutableSetOf<String>()
    fun claim(path: String): String {
        WatchDocumentStore.validateArchiveName(path)
        if (!names.add(path.lowercase(Locale.ROOT))) throw WatchDocumentException.InvalidCommand("资源名称分配冲突：$path")
        return path
    }
    fun reserveDirectory(requested: String): String {
        val normalized = requested.trimEnd('/')
        val initial = if (normalized.lowercase(Locale.ROOT) in setOf("images", "videos", "fonts", "fonts_bm", "sounds", "sfx")) "$normalized/pasted" else normalized
        var ancestor = initial.substringBeforeLast('/', "")
        while (ancestor.isNotEmpty()) {
            if (ancestor.lowercase(Locale.ROOT) in names || ancestor.lowercase(Locale.ROOT) in directories)
                throw WatchDocumentException.InvalidCommand("无法创建素材文件夹，父路径已被文件或本次转移的其它目录占用：$ancestor")
            ancestor = ancestor.substringBeforeLast('/', "")
        }
        var candidate = initial; var suffix = 2
        fun conflicts(value: String): Boolean {
            val key = value.lowercase(Locale.ROOT)
            return names.any { it == key || it.startsWith("$key/") || key.startsWith("$it/") } || directories.any { it == key || it.startsWith("$key/") || key.startsWith("$it/") }
        }
        while (conflicts(candidate)) candidate = "${initial}_${suffix++}"
        WatchDocumentStore.validateArchiveName(candidate); directories.add(candidate.lowercase(Locale.ROOT)); return candidate
    }
    fun reserve(requested: String, related: (String) -> List<String> = { emptyList() }): String {
        val folder = requested.substringBeforeLast('/', "")
        val filename = requested.substringAfterLast('/').replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_").trimEnd(' ', '.').ifBlank { "asset" }
        val base = filename.substringBeforeLast('.', filename); val ext = filename.substringAfterLast('.', "")
        fun path(name: String) = if (folder.isBlank()) name else "$folder/$name"
        var candidate = path(filename); var suffix = 2
        fun conflicts(value: String): Boolean {
            val lower = value.lowercase(Locale.ROOT)
            if (lower in names || lower in directories) return true
            val folderKey = folder.lowercase(Locale.ROOT)
            val fontNamespace = folderKey.startsWith("fonts/") || folderKey == "fonts" ||
                ((folderKey == "fonts_bm" || folderKey.startsWith("fonts_bm/")) && ext.equals("fnt", true))
            if (!fontNamespace) return false
            val root = folderKey.substringBefore('/')
            val stem = lower.substringAfterLast('/').substringBeforeLast('.')
            return names.any { it.startsWith("$root/") && (root != "fonts_bm" || it.endsWith(".fnt")) && it.substringAfterLast('/').substringBeforeLast('.') == stem }
        }
        while ((listOf(candidate) + related(candidate)).any(::conflicts)) candidate = path("${base}_${suffix++}" + if (ext.isEmpty()) "" else ".$ext")
        (listOf(candidate) + related(candidate)).distinct().forEach {
            WatchDocumentStore.validateArchiveName(it)
            names.add(it.lowercase(Locale.ROOT))
        }
        return candidate
    }
}
