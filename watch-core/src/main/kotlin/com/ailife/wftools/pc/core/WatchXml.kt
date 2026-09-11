package com.ailife.wftools.pc.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal object WatchXml {
    fun parse(xml: String): Document = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isCoalescing = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        factory.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
        }.parse(InputSource(StringReader(preserveAttributeWhitespace(xml))))
    } catch (e: Exception) {
        throw WatchDocumentException.InvalidDocument("表盘 XML 无法解析：${e.message}", e)
    }

    fun serialize(node: Node): String {
        val transformer = TransformerFactory.newInstance().apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, if (node is Document) "no" else "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        return StringWriter().also { transformer.transform(DOMSource(node), StreamResult(it)) }.toString()
    }

    fun validate(document: Document) {
        if (document.documentElement.tagName.lowercase() !in setOf("watch", "watchface"))
            throw WatchDocumentException.InvalidDocument("XML 根节点必须为 Watch 或 watchface。")
    }

    fun layers(document: Document): List<Element> = document.documentElement.elements().flatMap {
        when {
            it.tagName.equals("Layer", true) -> listOf(it)
            it.tagName.equals("Layers", true) -> it.elements().filter { child -> child.tagName.equals("Layer", true) }
            else -> emptyList()
        }
    }

    // WatchMaker permits literal line breaks in quoted attributes. XML normally
    // normalizes those to spaces; protect them without touching comments/CDATA/PI.
    private fun preserveAttributeWhitespace(xml: String): String {
        val out = StringBuilder(xml.length)
        var i = 0
        var inTag = false
        var quote: Char? = null
        while (i < xml.length) {
            if (!inTag && xml[i] == '<') {
                val endMarker = when {
                    xml.startsWith("<!--", i) -> "-->"
                    xml.startsWith("<![CDATA[", i) -> "]]>"
                    xml.startsWith("<?", i) -> "?>"
                    else -> null
                }
                if (endMarker != null) {
                    val end = xml.indexOf(endMarker, i + 2)
                    if (end < 0) { out.append(xml.substring(i)); break }
                    out.append(xml, i, end + endMarker.length); i = end + endMarker.length; continue
                }
                inTag = true
            }
            val c = xml[i]
            if (inTag && quote == null && (c == '\'' || c == '"')) quote = c
            else if (quote == c) quote = null
            else if (quote != null && c in "\r\n\t") {
                if (c == '\r' && i + 1 < xml.length && xml[i + 1] == '\n') i++
                out.append(if (c == '\t') "&#9;" else "&#10;"); i++; continue
            } else if (quote == null && c == '>') inTag = false
            out.append(c); i++
        }
        return out.toString()
    }
}

internal fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
internal fun Element.attributesMap(): LinkedHashMap<String, String> = LinkedHashMap<String, String>().also { result ->
    for (i in 0 until attributes.length) attributes.item(i).let { result[it.nodeName] = it.nodeValue }
}
