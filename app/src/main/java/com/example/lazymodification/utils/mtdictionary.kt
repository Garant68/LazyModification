package com.example.lazymodification.utils

import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * MT Manager dictionary (AeDict XML).
 *
 * MT Manager stores/imports dictionaries in the following format:
 *
 * ```xml
 * <?xml version="1.0" encoding="utf-8" standalone="yes"?>
 * <translations name="AeDict" version="1.0">
 *   <translate from="source" to="translation" />
 * </translations>
 * ```
 */
object MtDictionary {
    const val FILE_SUFFIX = "_dictionary.xml"
    private const val HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n"

    fun build(entries: Map<String, String>): String = buildString {
        append(HEADER)
        append("<translations name=\"AeDict\" version=\"1.0\">\n")
        entries.forEach { (from, to) ->
            append("  <translate from=\"")
            append(escape(from))
            append("\" to=\"")
            append(escape(to))
            append("\" />\n")
        }
        append("</translations>\n")
    }

    /** Читает словарь MT Manager и возвращает пары from -> to. */
    fun parse(input: InputStream): Map<String, String> {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Throwable) {
            // Старые версии Android могут не поддерживать этот feature.
        }
        val result = linkedMapOf<String, String>()
        val handler = object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?
            ) {
                val name = localName?.takeIf { it.isNotEmpty() } ?: qName ?: return
                if (name != "translate" || attributes == null) return
                val from = attributes.getValue("from")
                val to = attributes.getValue("to")
                if (from.isNullOrBlank() || to.isNullOrBlank()) return
                if (!result.containsKey(from)) result[from] = to
            }
        }
        factory.newSAXParser().parse(input, handler)
        return result
    }

    private fun escape(value: String): String = buildString(value.length + 16) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> if (char.code >= 0x20 && char.code != 0x7F) append(char) else append(' ')
            }
        }
    }
}
