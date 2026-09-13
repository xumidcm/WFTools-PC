package com.ailife.wftools.agent.tools

import java.util.Locale

enum class WatchAgentAttributeKind {
    NUMBER,
    TEXT,
    PATH,
    COLOR,
    OTHER,
}

enum class WatchAgentExpressionKind {
    LITERAL,
    FAST_EXPRESSION,
    LUA_EXPRESSION,
    INVALID,
}

data class WatchAgentExpressionClassification(
    val kind: WatchAgentExpressionKind,
    val source: String,
    val referencesFaceState: Boolean = false,
    val reason: String? = null,
)

/**
 * Import-time classification shared by validation and any renderer adapter.
 * It intentionally distinguishes a Lua program from a tag substitution: a
 * local-only IIFE must still reach the Lua backend when no global script exists.
 */
object WatchAgentExpressionClassifier {
    @JvmStatic
    fun classify(
        raw: String?,
        attributeKind: WatchAgentAttributeKind = WatchAgentAttributeKind.OTHER,
    ): WatchAgentExpressionClassification {
        val source = raw.orEmpty()
        val trimmed = source.trim()
        if (trimmed.isEmpty() || trimmed.toDoubleOrNull() != null) {
            return WatchAgentExpressionClassification(WatchAgentExpressionKind.LITERAL, source)
        }
        if (hasUnbalancedBraces(trimmed)) {
            return WatchAgentExpressionClassification(
                WatchAgentExpressionKind.INVALID,
                source,
                reason = "花括号未闭合",
            )
        }
        if (attributeKind == WatchAgentAttributeKind.PATH || attributeKind == WatchAgentAttributeKind.COLOR) {
            if (!trimmed.contains('{') && !looksLikeLua(trimmed)) {
                return WatchAgentExpressionClassification(WatchAgentExpressionKind.LITERAL, source)
            }
        }
        if (containsLuaProgram(trimmed)) {
            if (looksMalformedLua(trimmed)) {
                return WatchAgentExpressionClassification(
                    WatchAgentExpressionKind.INVALID,
                    source,
                    referencesFaceState = referencesFaceState(trimmed),
                    reason = "Lua 表达式语法不完整",
                )
            }
            return WatchAgentExpressionClassification(
                WatchAgentExpressionKind.LUA_EXPRESSION,
                source,
                referencesFaceState = referencesFaceState(trimmed),
            )
        }
        if (containsDynamicTag(trimmed) || looksLikeFastExpression(trimmed, attributeKind)) {
            return WatchAgentExpressionClassification(
                WatchAgentExpressionKind.FAST_EXPRESSION,
                source,
                referencesFaceState = referencesFaceState(trimmed),
            )
        }
        if (attributeKind == WatchAgentAttributeKind.NUMBER && looksLikeFastExpression(trimmed, attributeKind)) {
            return WatchAgentExpressionClassification(
                WatchAgentExpressionKind.FAST_EXPRESSION,
                source,
                referencesFaceState = referencesFaceState(trimmed),
            )
        }
        return WatchAgentExpressionClassification(WatchAgentExpressionKind.LITERAL, source)
    }

    fun classifyXml(xml: String): List<WatchAgentExpressionClassification> {
        val result = mutableListOf<WatchAgentExpressionClassification>()
        LAYER_PATTERN.findAll(xml).forEach { match ->
            val attributes = ATTRIBUTE_PATTERN.findAll(match.value).associate { it.groupValues[1] to it.groupValues[4] }
            attributes.forEach { (name, value) ->
                if (name == "path" || name == "paths" || name == "type" || name == "id") return@forEach
                val kind = attributeKind(name)
                val classification = classify(value, kind)
                if (classification.kind != WatchAgentExpressionKind.LITERAL) result += classification
            }
            attributes["tap_action"]?.let { action ->
                val classification = classify(action, WatchAgentAttributeKind.TEXT)
                if (classification.kind != WatchAgentExpressionKind.LITERAL) result += classification
            }
        }
        SCRIPT_ACTION_PATTERN.findAll(xml).forEach { match ->
            result += WatchAgentExpressionClassification(
                WatchAgentExpressionKind.LUA_EXPRESSION,
                match.groupValues[1],
            )
        }
        return result
    }

    fun uniqueLuaSources(xml: String): Set<String> =
        classifyXml(xml)
            .filter { it.kind == WatchAgentExpressionKind.LUA_EXPRESSION }
            .mapTo(linkedSetOf()) { it.source }

    fun referencesFaceState(source: String): Boolean {
        val lower = source.lowercase(Locale.ROOT)
        return FACE_STATE_TAG_PATTERN.containsMatchIn(lower) ||
            lower.contains("wf.") ||
            lower.contains("thirdparty")
    }

    private fun containsDynamicTag(value: String): Boolean = TAG_PATTERN.containsMatchIn(value)

    private fun containsLuaProgram(value: String): Boolean =
        IIFE_PATTERN.containsMatchIn(value) ||
            LUA_KEYWORD_PATTERN.containsMatchIn(value) ||
            value.contains("WF.", ignoreCase = false) ||
            value.contains("WF[", ignoreCase = false) ||
            BARE_SCRIPT_VALUE_PATTERN.containsMatchIn(value) ||
            value.contains("..")

    private fun looksLikeLua(value: String): Boolean =
        containsLuaProgram(value) || LUA_CALL_PATTERN.containsMatchIn(value)

    private fun looksLikeFastExpression(value: String, attributeKind: WatchAgentAttributeKind): Boolean =
        containsDynamicTag(value) ||
            (FAST_OPERATOR_PATTERN.containsMatchIn(value) &&
                (attributeKind != WatchAgentAttributeKind.TEXT ||
                    value.contains("==") || value.contains("~=") || value.contains('<') ||
                    value.contains('>') || (value.contains(" and ") && value.contains(" or ")))) ||
            (value.startsWith("{") && value.endsWith("}"))

    private fun hasUnbalancedBraces(value: String): Boolean =
        value.count { it == '{' } != value.count { it == '}' } ||
            value.contains("{}")

    private fun looksMalformedLua(value: String): Boolean {
        if (value.count { it == '(' } != value.count { it == ')' }) return true
        if (Regex("(?i)\\bfunction\\s*\\(").containsMatchIn(value) && !Regex("(?i)\\bend\\b").containsMatchIn(value)) return true
        if (Regex("(?i)\\blocal\\s+[A-Za-z_][A-Za-z0-9_]*\\s*$").containsMatchIn(value)) return true
        if (Regex("(?i)\\breturn\\s*$").containsMatchIn(value)) return true
        return false
    }

    private fun attributeKind(name: String): WatchAgentAttributeKind = when (name.lowercase(Locale.ROOT)) {
        "x", "y", "z", "width", "height", "rotation", "opacity", "font_size",
        "fontsize", "anim_scale", "anim_scale_x", "anim_scale_y", "scale", "scale_x", "scale_y",
        "radius", "radius_inner", "radius_outer", "angle", "angle_start", "angle_total",
        "pct_complete", "border_width", "o_size", "w_distance", "w_angle" -> WatchAgentAttributeKind.NUMBER
        "text", "value", "label", "name", "font" -> WatchAgentAttributeKind.TEXT
        "path", "paths", "src", "resource" -> WatchAgentAttributeKind.PATH
        "color", "color_dim", "o_color", "w_color", "ucolor" -> WatchAgentAttributeKind.COLOR
        else -> WatchAgentAttributeKind.OTHER
    }

    private val TAG_PATTERN = Regex("\\{[^\\{\\}]+\\}")
    private val IIFE_PATTERN = Regex("(?is)\\(\\(\\s*function\\s*\\(")
    private val LUA_KEYWORD_PATTERN = Regex("(?is)\\bfunction\\s*\\(|\\blocal\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=|\\breturn\\s+[^\\}\\s]|\\bend\\s*(?:\\)|;|$)")
    private val LUA_CALL_PATTERN = Regex("(?i)\\b(math|string|table|tonumber|tostring|format)\\s*\\.")
    private val BARE_SCRIPT_VALUE_PATTERN = Regex("(?<![A-Za-z0-9_])(?:var_[A-Za-z0-9_]+|tweens\\.[A-Za-z0-9_]+)(?![A-Za-z0-9_])")
    private val FAST_OPERATOR_PATTERN = Regex("(?:[+*/%]|\\d\\s*-\\s*\\d|==|~=|<=|>=|\\s(?:and|or)\\s|\\b(?:floor|ceil|round|min|max)\\s*\\()")
    private val FACE_STATE_TAG_PATTERN = Regex("\\{[^\\{\\}]*(?:d[a-z0-9_]+|s[a-z0-9_]+|w[a-z0-9_]+|wf[a-z0-9_]+)[^\\{\\}]*\\}")
    private val LAYER_PATTERN = Regex("(?is)<Layer\\b[^>]*>")
    private val ATTRIBUTE_PATTERN = Regex("([A-Za-z_:][A-Za-z0-9_.:-]*)\\s*([=])\\s*([\\\"'])(.*?)\\3")
    private val SCRIPT_ACTION_PATTERN = Regex("(?is)\\btap_action\\s*=\\s*[\\\"']([^\\\"']*\\b(?:script|lua)\\b[^\\\"']*)[\\\"']")
}

object WatchAgentAssetPathNormalizer {
    fun normalizeImagePath(raw: String): String = normalizeDelimited(raw) { segment ->
        normalizeOne(segment, "images")
    }

    fun normalizeDelimited(raw: String, normalizer: (String) -> String): String =
        raw.split('`').joinToString("`") { normalizer(it) }

    /** Normalize image path attributes while leaving the surrounding XML text untouched. */
    fun normalizeImageLayerPaths(xml: String): String = LAYER_PATTERN.replace(xml) { match ->
        val layer = match.value
        val pathMatch = PATH_ATTRIBUTE_PATTERN.find(layer) ?: return@replace layer
        val raw = pathMatch.groupValues[4]
        if (!raw.contains("images/", ignoreCase = true)) return@replace layer
        val normalized = normalizeImagePath(raw)
        layer.replaceRange(
            pathMatch.range,
            "${pathMatch.groupValues[1]}${pathMatch.groupValues[2]}${pathMatch.groupValues[3]}$normalized${pathMatch.groupValues[3]}",
        )
    }

    private fun normalizeOne(raw: String, directory: String): String {
        val value = raw.trim().replace('\\', '/')
        require(value.isNotBlank()) { "资源路径不能为空" }
        require(!value.startsWith('/') && !value.matches(Regex("^[A-Za-z]:.*"))) {
            "不允许绝对资源路径：$raw"
        }
        val withoutPrefix = if (value.startsWith("$directory/", ignoreCase = true)) {
            value.substring(directory.length + 1)
        } else {
            value
        }
        require(withoutPrefix.split('/').none { it == ".." }) {
            "不允许父目录资源路径：$raw"
        }
        return withoutPrefix.split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")
            .also { require(it.isNotBlank()) { "资源路径不能为空" } }
    }

    private val LAYER_PATTERN = Regex("(?is)<Layer\\b[^>]*>")
    private val PATH_ATTRIBUTE_PATTERN = Regex("(\\bpath\\s*)(=\\s*)([\\\"'])(.*?)\\3", RegexOption.IGNORE_CASE)
}

fun classifyWatchAgentExpression(
    raw: String?,
    attributeKind: WatchAgentAttributeKind = WatchAgentAttributeKind.OTHER,
): WatchAgentExpressionClassification = WatchAgentExpressionClassifier.classify(raw, attributeKind)

fun normalizeWatchImagePath(raw: String): String = WatchAgentAssetPathNormalizer.normalizeImagePath(raw)


