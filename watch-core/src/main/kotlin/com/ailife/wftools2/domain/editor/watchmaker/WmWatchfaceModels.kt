package com.ailife.wftools2.domain.editor.watchmaker

import com.ailife.wftools2.domain.editor.WatchfaceLayer
import com.ailife.wftools2.domain.editor.WatchfaceProject

data class WmWatchfaceProject(
    override val id: String = "",
    override val title: String = "New WatchMaker Face",
    override val width: Int = 512,
    override val height: Int = 512,
    val description: String = "",
    val tags: String = "",
    val background: String = "000000",
    val shape: String = "Round",
    val author: String = "",
    val link: String = "",
    val globalScript: String = "",
    val protection: String = "n",
    // All <Watch> root attributes captured verbatim from the source XML so that
    // attributes the editor has no typed field for (ucolor_default, features,
    // build_source, mode_3d, ind_*, hotword_*, …) survive a load→save round-trip.
    // Typed fields above override these on serialization.
    val rootAttrs: LinkedHashMap<String, String> = LinkedHashMap(),
    // Format 2.0 (Studio) color themes (<ThemeSets>/<ThemeSet>). Empty for 1.0 faces.
    val themeSets: List<WmThemeSet> = emptyList(),
    // Format 2.0 scheduled audio (<Sounds>/<Sound> + <Spoken>). Empty for 1.0 faces.
    val sounds: List<WmSound> = emptyList(),
    // Format 2.0 style pickers (<FlavorSets>/<FlavorSet>/<Option>). Empty for 1.0 faces.
    val flavorSets: List<WmFlavorSet> = emptyList(),
    val wmLayers: List<WmWatchfaceLayer> = emptyList()
) : WatchfaceProject {
    override val layers: List<WatchfaceLayer> get() = wmLayers
}

// One <ThemeSet> entry. Stored as a raw attribute map (name, ucolor, ucolor2,
// ucolor3, plus any future attrs) so themes round-trip losslessly.
data class WmThemeSet(
    val attrs: LinkedHashMap<String, String> = LinkedHashMap()
)

// One <Sound> entry (schedule chime/speech). Kept as raw attrs so sound fields
// round-trip losslessly; the optional spoken text is a <Spoken> child.
data class WmSound(
    val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    val spokenText: String = ""
)

// One <FlavorSet> ("style") with its selector options. Each <Option> is a raw
// attribute map; the set-level "default" picks the initially-active option.
data class WmFlavorSet(
    val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    val options: List<LinkedHashMap<String, String>> = emptyList()
)

// A child element of a <Layer> in format 2.0: <Ambient>, <FlavorAssignments>,
// <Segment>, <TapSpoken>, <TapAudio>, ... Stored as name + raw attrs so nothing
// is lost and any 2.0 layer type can be faithfully downgraded by the flattener.
data class WmLayerChild(
    val name: String,
    val attrs: LinkedHashMap<String, String> = LinkedHashMap()
)

data class WmWatchfaceLayer(
    val layerType: String = "image",
    val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    val children: List<WmLayerChild> = emptyList()
) : WatchfaceLayer {

    fun getAttr(key: String, default: String = "") = attrs[key] ?: default

    fun evalNum(key: String, defaultVal: Float): Float {
        val raw = attrs[key] ?: return defaultVal
        raw.toFloatOrNull()?.takeIf { it.isFinite() }?.let { return it }
        return WmTagsData.evaluatePreview(raw).toFloatOrNull() ?: defaultVal
    }

    fun withAttr(key: String, value: String): WmWatchfaceLayer {
        val newAttrs = LinkedHashMap(attrs)
        newAttrs[key] = value
        return copy(attrs = newAttrs)
    }

    override val type: String get() = layerType
    override val x: Float get() = evalNum("x", 0f)
    override val y: Float get() = evalNum("y", 0f)
    override val width: Int get() = evalNum("width", 100f).toInt()
    override val height: Int get() = evalNum("height", 100f).toInt()
    // "h" = hidden convention; any other display value = visible
    override val isVisible: Boolean get() = attrs["display"] != "h"
    override val alpha: Int get() = (evalNum("opacity", 100f).toInt() * 255 / 100).coerceIn(0, 255)
    override val scale: Float get() = evalNum("anim_scale_x", 100f) / 100f
    val scaleX: Float get() = evalNum("anim_scale_x", 100f) / 100f
    val scaleY: Float get() = evalNum("anim_scale_y", 100f) / 100f
    val skewX: Float get() = evalNum("skew_x", 0f)
    val skewY: Float get() = evalNum("skew_y", 0f)
    override val rotate: Int get() = evalNum("rotation", 0f).toInt()
    override val thumbnailImagePath: String? get() = when (layerType) {
        "image", "image_gif", "image_cond" -> attrs["path"]?.substringBefore("`")?.removePrefix("images/")?.let { "images/$it" }
        else -> null
    }
    override val displayText: String get() = attrs["text"] ?: ""
}

// ─── JSON helpers used by serialize/deserializeProject ───────────────────────

data class WmProjectJson(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val description: String? = null,
    val tags: String? = null,
    val background: String? = null,
    val shape: String? = null,
    val author: String? = null,
    val link: String? = null,
    val global_script: String? = null,
    val protection: String,
    val root_attrs: Map<String, String>? = null,
    val theme_sets: List<Map<String, String>>? = null,
    val sounds: List<WmSoundJson>? = null,
    val flavor_sets: List<WmFlavorSetJson>? = null,
    val layers: List<WmLayerJson>
)

data class WmSoundJson(
    val attrs: Map<String, String>,
    val spoken_text: String? = null
)

data class WmFlavorSetJson(
    val attrs: Map<String, String>,
    val options: List<Map<String, String>>? = null
)

data class WmLayerJson(
    val type: String,
    val attrs: Map<String, String>,
    val children: List<WmChildJson>? = null
)

data class WmChildJson(
    val name: String,
    val attrs: Map<String, String>
)
