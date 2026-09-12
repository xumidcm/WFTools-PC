package com.ailife.wftools2.domain.editor.watchmaker

data class BitmapFont(
    val face: String,
    val size: Int,
    val lineHeight: Int,
    val base: Int,
    val scaleW: Int,
    val scaleH: Int,
    val pages: List<String>,
    val chars: Map<Int, BitmapChar>,
    val kernings: Map<Long, Int> = emptyMap()
) {
    data class BitmapChar(
        val id: Int, val x: Int, val y: Int, val width: Int, val height: Int,
        val xoffset: Int, val yoffset: Int, val xadvance: Int, val page: Int
    )

    companion object {
        fun parse(text: String): BitmapFont {
            var face = ""; var size = 0; var lineHeight = 0; var base = 0; var scaleW = 0; var scaleH = 0
            val pages = mutableListOf<String>()
            val chars = mutableMapOf<Int, BitmapChar>()
            val kernings = mutableMapOf<Long, Int>()
            val kvRegex = Regex("(\\w+)=([^\\s\"]+|\"[^\"]*\")")

            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val type = trimmed.substringBefore(" ")
                val matches = kvRegex.findAll(trimmed).toList()
                val params = matches.associate { it.groupValues[1] to it.groupValues[2].trim('"') }

                when (type) {
                    "info" -> {
                        face = params["face"] ?: ""
                        size = params["size"]?.toIntOrNull() ?: 0
                    }
                    "common" -> {
                        lineHeight = params["lineHeight"]?.toIntOrNull() ?: 0
                        base = params["base"]?.toIntOrNull() ?: 0
                        scaleW = params["scaleW"]?.toIntOrNull() ?: 0
                        scaleH = params["scaleH"]?.toIntOrNull() ?: 0
                    }
                    "page" -> params["file"]?.let { pages.add(it) }
                    "char" -> {
                        val id = params["id"]?.toIntOrNull() ?: 0
                        chars[id] = BitmapChar(
                            id, params["x"]?.toIntOrNull() ?: 0, params["y"]?.toIntOrNull() ?: 0,
                            params["width"]?.toIntOrNull() ?: 0, params["height"]?.toIntOrNull() ?: 0,
                            params["xoffset"]?.toIntOrNull() ?: 0, params["yoffset"]?.toIntOrNull() ?: 0,
                            params["xadvance"]?.toIntOrNull() ?: 0, params["page"]?.toIntOrNull() ?: 0
                        )
                    }
                    "kerning" -> {
                        val first = params["first"]?.toIntOrNull() ?: 0
                        val second = params["second"]?.toIntOrNull() ?: 0
                        val amount = params["amount"]?.toIntOrNull() ?: 0
                        kernings[(first.toLong() shl 32) or (second.toLong() and 0xFFFFFFFFL)] = amount
                    }
                }
            }
            return BitmapFont(face, size, lineHeight, base, scaleW, scaleH, pages, chars, kernings)
        }
    }
}
