package com.ailife.wftools.pc.core

import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceLayer

/** Pure defaults ported from Android WmFormatAdapter.createLayer. */
internal object WmLayerDefaults {
    fun create(type: String, wfWidth: Int, wfHeight: Int): WmWatchfaceLayer {
        val attrs = LinkedHashMap<String, String>()
        attrs["x"] = "0"
        attrs["y"] = "0"
        // Shared transform base shown for every layer type — never leave these unset.
        // opacity + rotation are REQUIRED by the watch renderer: UL applies
        // setOpacity(opacity * 2.55), so a missing opacity becomes nil → the layer is
        // drawn fully transparent (invisible) on-device even though the editor preview
        // shows it. WatchMaker writes opacity="100" rotation="0" on every layer.
        attrs["opacity"] = "100"
        attrs["rotation"] = "0"
        attrs["anim_scale_x"] = "100"
        attrs["anim_scale_y"] = "100"
        attrs["skew_x"] = "0"
        attrs["skew_y"] = "0"
        attrs["gyro"] = "0"
        // Effect defaults (shadow / outline) — populated up front so the effect, once
        // enabled, always has complete, valid sub-fields. An enabled effect with a
        // missing color/size/etc. crashes the watch renderer.
        fun addEffectDefaults() {
            attrs["shadow"] = ""        // off
            attrs["w_color"] = "ffffff"
            attrs["w_distance"] = "10"
            attrs["w_angle"] = "135"
            attrs["w_opacity"] = "75"
            attrs["outline"] = ""       // off
            attrs["o_color"] = "ffffff"
            attrs["o_size"] = "2"
            attrs["o_opacity"] = "100"
        }
        when (type) {
            "image" -> {
                attrs["path"] = "background.jpg"
                attrs["width"] = wfWidth.toString()
                attrs["height"] = wfHeight.toString()
                attrs["opacity"] = "100"
                attrs["display"] = "bd"
            }
            "image_gif" -> {
                attrs["path"] = ""
                attrs["width"] = "100"
                attrs["height"] = "100"
                attrs["gif_delay"] = "50"
                attrs["display"] = "bd"
            }
            "image_cond" -> {
                attrs["path"] = "sprite.png"
                attrs["cond_grid"] = "3x3"
                attrs["cond_value"] = "1"
                attrs["width"] = "80"
                attrs["height"] = "80"
                attrs["display"] = "bd"
            }
            "text" -> {
                attrs["text"] = "Text"
                attrs["font"] = "Roboto-Regular"
                attrs["text_size"] = "40"
                attrs["color"] = "ffffff"
                attrs["color_dim"] = "ffffff"
                attrs["transform"] = "n"
                attrs["stencil"] = "n"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
                addEffectDefaults()
            }
            "shape" -> {
                attrs["shape"] = "Square"
                attrs["width"] = "100"
                attrs["height"] = "100"
                attrs["color"] = "ff0000"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
                addEffectDefaults()
            }
            "rounded" -> {
                attrs["shape"] = "Square"
                attrs["width"] = "100"
                attrs["height"] = "100"
                attrs["radius"] = "20"
                attrs["corner_type"] = "1"
                attrs["color"] = "ff0000"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
                addEffectDefaults()
            }
            "progress" -> {
                attrs["width"] = "200"
                attrs["height"] = "20"
                attrs["radius"] = "50"
                attrs["corner_type"] = "1"
                attrs["margin"] = "0"
                attrs["end_style"] = "round"
                attrs["color"] = "00ff00"
                attrs["color2"] = "008800"
                attrs["color3"] = "333333"
                attrs["color4"] = "ffffff"
                attrs["pct_complete"] = "50"
                attrs["angle"] = "90"
                attrs["outside_opacity"] = "100"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "ring" -> {
                attrs["radius_outer"] = "100"
                attrs["radius_inner"] = "80"
                attrs["angle"] = "270"
                attrs["angle_total"] = "360"
                attrs["is_clockwise"] = "Y"
                attrs["color"] = "00ff00"
                attrs["color2"] = "008800"
                attrs["color3"] = "003300"
                attrs["outside_opacity"] = "100"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "text_curved" -> {
                attrs["text"] = "Curved Text"
                attrs["font"] = "Roboto-Regular"
                attrs["text_size"] = "40"
                attrs["radius"] = "200"
                attrs["curve_dir"] = "Up"
                attrs["color"] = "ffffff"
                attrs["color_dim"] = "ffffff"
                attrs["transform"] = "n"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
                addEffectDefaults()
            }
            "markers" -> {
                attrs["radius"] = "120"
                attrs["m_count"] = "12"
                attrs["m_width"] = "6"
                attrs["m_height"] = "20"
                attrs["shape"] = "Square"
                attrs["squarify"] = "100"
                attrs["color"] = "ffffff"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "markers_hm" -> {
                attrs["radius"] = "120"
                attrs["m_hour"] = "Large"
                attrs["m_minute"] = "Small"
                attrs["squarify"] = "100"
                attrs["color"] = "ffffff"
                attrs["color2"] = "aaaaaa"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "text_ring" -> {
                attrs["radius"] = "120"
                attrs["ring_type"] = "1-12"
                attrs["show_every"] = "1"
                attrs["rotated_text"] = "n"
                attrs["angle_start"] = "0"
                attrs["angle_end"] = "360"
                attrs["squarify"] = "0"
                attrs["font"] = "Roboto-Regular"
                attrs["text_size"] = "32"
                attrs["color"] = "ffffff"
                attrs["transform"] = "n"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "series" -> {
                attrs["series"] = "dh24z"
                attrs["orientation"] = "v"
                attrs["current_pos"] = "m"
                attrs["spacing"] = "10"
                attrs["font"] = "Roboto-Regular"
                attrs["text_size"] = "40"
                attrs["color"] = "ffffff"
                attrs["font2"] = "Roboto-Regular"
                attrs["text_size2"] = "24"
                attrs["color2"] = "888888"
                attrs["transform"] = "n"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "calendar" -> {
                attrs["font"] = "Roboto-Regular"
                attrs["font2"] = "Roboto-Regular"
                attrs["text_size"] = "14"
                attrs["margin_x"] = "8"
                attrs["margin_y"] = "8"
                attrs["color"] = "ffffff"
                attrs["color2"] = "2196f3"
                attrs["transform"] = "n"
                attrs["dow_start"] = "1"
                attrs["no_letters_header"] = "1"
                attrs["outside_opacity"] = "60"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "chart" -> {
                attrs["width"] = "200"
                attrs["height"] = "100"
                attrs["chart_type"] = "line"
                attrs["data_points"] = "{shr_2};{shr_1};{shr}"
                attrs["color"] = "00ff00"
                attrs["color2"] = "008800"
                attrs["color3"] = "00ff00"
                attrs["color4"] = "ffffff"
                attrs["line_width"] = "10"
                attrs["marker_size"] = "10"
                attrs["bar_thickness"] = "50"
                attrs["top_opacity"] = "100"
                attrs["bottom_opacity"] = "0"
                attrs["alignment"] = "cc"
                attrs["display"] = "bd"
            }
            "video" -> {
                attrs["path"] = ""
                // CloX sizes the video node to width×height (default 512), stretched.
                attrs["width"] = "512"
                attrs["height"] = "512"
                attrs["color"] = "ffffff"
                attrs["alignment"] = "cc"
                attrs["display"] = "b"
            }
            "map" -> {
                // CloX map: single tile at lat/lon/map_zoom scaled to the 256×256 box × map_scale.
                attrs["width"] = "256"
                attrs["height"] = "256"
                attrs["lat"] = "{alat}"
                attrs["lon"] = "{alon}"
                attrs["map_zoom"] = "15"
                attrs["map_scale"] = "100"
                attrs["alignment"] = "cc"
                attrs["display"] = "b"
            }
            "gallery_2d" -> {
                attrs["width"] = "200"
                attrs["height"] = "200"
                attrs["dur_on"] = "5"
                attrs["alignment"] = "cc"
                attrs["display"] = "b"
            }
        }
        return WmWatchfaceLayer(type, attrs)
    }

}
