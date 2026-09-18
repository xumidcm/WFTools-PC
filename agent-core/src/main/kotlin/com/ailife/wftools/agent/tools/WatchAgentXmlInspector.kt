package com.ailife.wftools.agent.tools

import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import java.io.ByteArrayInputStream

/**
 * Compares the layer nodes visible in watch.xml with the host's parsed layer list.
 *
 * The desktop editor intentionally supports the canonical `<Layer type="…">` schema, but a
 * malformed or future schema can still contain obvious `<Image>`, `<Text>` or `<Shape>` nodes.
 * Counting those nodes prevents an empty parsed list from being reported as a healthy empty face.
 */
data class WatchAgentXmlInspection(
    val layersInXml: Int,
    val candidateNames: List<String>,
    val recognizedLayers: Int,
) {
    val parseStatus: String get() = if (layersInXml == recognizedLayers) "ok" else "mismatch"
}

object WatchAgentXmlInspector {
    private val candidateNames = setOf(
        "layer", "image", "text", "shape", "rounded", "progress", "ring", "text_curved",
        "markers", "markers_hm", "text_ring", "series", "calendar", "chart", "video", "map",
        "gallery_2d",
    )

    fun inspect(xml: String): WatchAgentXmlInspection {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isCoalescing = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
        require(root.tagName.equals("Watch", true) || root.tagName.equals("watchface", true)) {
            "XML 根节点必须为 Watch 或 watchface"
        }
        val names = mutableListOf<String>()
        fun visit(element: Element) {
            val name = element.tagName.lowercase()
            if (name in candidateNames) names += element.tagName
            for (index in 0 until element.childNodes.length) {
                (element.childNodes.item(index) as? Element)?.let(::visit)
            }
        }
        visit(root)
        val recognized = names.count { it.equals("Layer", true) }
        return WatchAgentXmlInspection(names.size, names, recognized)
    }
}
