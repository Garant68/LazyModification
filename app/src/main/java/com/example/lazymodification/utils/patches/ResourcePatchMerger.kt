package com.example.lazymodification.utils.patches

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Merges an APK Editor decoded resource/code ZIP, retaining base resource IDs. */
object ResourcePatchMerger {
    fun merge(root: File, bytes: ByteArray, stats: PatchStats) {
        val incoming = File(root.parentFile, "merge_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val file = PatchFormat.child(incoming, entry.name.trimEnd('/'))
                    if (entry.isDirectory) file.mkdirs() else {
                        val data = PatchFormat.readBounded(zip, 128L * 1024 * 1024)
                        total += data.size
                        if (total > 512L * 1024 * 1024) throw PatchException("MERGE: слишком большой архив")
                        file.parentFile?.mkdirs(); file.writeBytes(data)
                    }
                    zip.closeEntry()
                }
            }
            val ids = mergePublic(root, incoming, stats)
            incoming.walkTopDown().filter { it.isFile && it.relativeTo(incoming).invariantSeparatorsPath != "res/values/public.xml" }.forEach { source ->
                val path = source.relativeTo(incoming).invariantSeparatorsPath
                val target = PatchFormat.child(root, path)
                val textFile = source.extension in listOf("smali", "xml")
                if (textFile && ids.isNotEmpty()) {
                    val replaced = Regex("0[xX][0-9a-fA-F]{8}\\b").replace(source.readText()) { token ->
                        ids[token.value.substring(2).toLong(16).toInt()]?.let { hex(it) } ?: token.value
                    }
                    source.writeText(replaced)
                }
                target.parentFile?.mkdirs()
                if (source.extension == "xml" && path.substringBeforeLast('/').matches(Regex("res/values(?:-[^/]+)?"))) {
                    mergeValues(source, target, root, stats)
                } else if (source.extension == "xml" && target.isFile && path == "AndroidManifest.xml") {
                    val base = readXml(target)
                    val addition = readXml(source)
                    if (base.documentElement.tagName != addition.documentElement.tagName)
                        throw PatchException("MERGE: разные корни XML в $path")
                    mergeElement(base.documentElement, addition.documentElement)
                    writeXml(base, target)
                } else source.copyTo(target, overwrite = true)
                stats.changedPaths += path
                stats.added++
            }
        } finally { incoming.deleteRecursively() }
    }

    /** Resource identity is type/name within a qualifier, independent of the XML filename. */
    private fun mergeValues(source: File, target: File, root: File, stats: PatchStats) {
        val addition = readXml(source)
        if (addition.documentElement.tagName != "resources") throw PatchException("MERGE: ожидался корень resources")
        val documents = target.parentFile!!.listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }
            .associateWith { readXml(it) }.toMutableMap()
        val destination = documents.getOrPut(target) { newDocument("resources") }
        val changed = linkedSetOf<File>()
        fun resourceKey(node: Element): String? = node.getAttribute("name").takeIf { it.isNotEmpty() }?.let {
            val type = when (node.tagName) {
                "item" -> node.getAttribute("type")
                "string-array", "integer-array" -> "array"
                else -> node.tagName
            }
            "$type/$it"
        }
        elements(addition.documentElement).forEach { node ->
            val id = resourceKey(node)
            val existing = if (id == null) null else documents.entries.asSequence().flatMap { (file, doc) ->
                elements(doc.documentElement).asSequence().map { Triple(file, doc, it) }
            }.firstOrNull { resourceKey(it.third) == id }
            if (existing == null) {
                destination.documentElement.appendChild(destination.importNode(node, true)); changed += target
            } else {
                val (file, document, old) = existing
                if (node.tagName == "style" || node.tagName == "declare-styleable") mergeElement(old, node)
                else document.documentElement.replaceChild(document.importNode(node, true), old)
                changed += file
            }
        }
        changed.forEach { file ->
            writeXml(documents.getValue(file), file)
            stats.changedPaths += file.relativeTo(root).invariantSeparatorsPath
        }
    }

    private fun mergePublic(root: File, incoming: File, stats: PatchStats): Map<Int, Int> {
        val addedFile = File(incoming, "res/values/public.xml")
        if (!addedFile.isFile) {
            if (File(incoming, "res").isDirectory) throw PatchException("MERGE: в архиве ресурсов требуется res/values/public.xml")
            return emptyMap()
        }
        val baseFile = File(root, "res/values/public.xml")
        val base = if (baseFile.isFile) readXml(baseFile) else newDocument("resources")
        val incomingDocument = readXml(addedFile)
        val byName = mutableMapOf<String, Int>()
        val typeIds = mutableMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        elements(base.documentElement).filter { it.tagName == "public" }.forEach { node ->
            val id = resourceId(node)
            byName[node.getAttribute("type") + "/" + node.getAttribute("name")] = id
            typeIds[node.getAttribute("type")] = (id ushr 16) and 255
            used += id
        }
        val packageId = used.firstOrNull()?.let { it ushr 24 } ?: 0x7f
        val replacements = linkedMapOf<Int, Int>()
        elements(incomingDocument.documentElement).filter { it.tagName == "public" }.forEach { node ->
            val old = resourceId(node)
            val type = node.getAttribute("type")
            val key = type + "/" + node.getAttribute("name")
            val id = byName[key] ?: run {
                val typeId = typeIds[type] ?: (1..255).firstOrNull { it !in typeIds.values }
                    ?.also { typeIds[type] = it } ?: throw PatchException("MERGE: нет свободных типов ресурсов")
                val prefix = (packageId shl 24) or (typeId shl 16)
                val next = (used.filter { (it and -65536) == prefix }.maxOfOrNull { it and 65535 } ?: -1) + 1
                if (next > 65535) throw PatchException("MERGE: исчерпаны ID типа $type")
                (prefix or next).also { allocated ->
                    used += allocated; byName[key] = allocated
                    val copy = base.importNode(node, true) as Element
                    copy.setAttribute("id", hex(allocated))
                    base.documentElement.appendChild(copy)
                }
            }
            replacements[old] = id
        }
        baseFile.parentFile?.mkdirs(); writeXml(base, baseFile)
        stats.changedPaths += "res/values/public.xml"
        return replacements
    }

    private fun resourceId(element: Element): Int = try {
        element.getAttribute("id").removePrefix("0x").removePrefix("0X").toLong(16).toInt()
    } catch (_: Exception) { throw PatchException("MERGE: неправильный ID ресурса ${element.getAttribute("name")}") }

    private fun hex(id: Int): String = String.format(Locale.ROOT, "0x%08x", id)

    private fun key(element: Element): String? {
        val name = element.getAttribute("android:name").ifEmpty { element.getAttribute("name") }
        return if (name.isNotEmpty()) element.tagName + ":" + name
        else if (element.tagName in listOf("application", "uses-sdk", "supports-screens", "queries")) element.tagName
        else null
    }

    private fun mergeElement(base: Element, added: Element) {
        val attrs = added.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            if (attr.namespaceURI != null) base.setAttributeNS(attr.namespaceURI, attr.nodeName, attr.nodeValue)
            else base.setAttribute(attr.nodeName, attr.nodeValue)
        }
        elements(added).forEach { child ->
            val id = key(child)
            val same = if (id == null) null else elements(base).firstOrNull { key(it) == id }
            if (same == null) base.appendChild(base.ownerDocument.importNode(child, true))
            else if (child.tagName in listOf("application", "activity", "activity-alias", "service", "receiver", "provider", "style", "queries"))
                mergeElement(same, child)
            else base.replaceChild(base.ownerDocument.importNode(child, true), same)
        }
    }

    private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
        try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) { }
        try { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) { }
    }
    private fun readXml(file: File): Document {
        if (file.readText().contains("<!DOCTYPE", ignoreCase = true)) throw PatchException("DOCTYPE не поддерживается: ${file.name}")
        return factory().newDocumentBuilder().parse(file)
    }
    private fun newDocument(root: String): Document = factory().newDocumentBuilder().newDocument().apply { appendChild(createElement(root)) }
    private fun elements(root: Element): List<Element> = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
    private fun writeXml(document: Document, file: File) {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "utf-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        file.outputStream().use { transformer.transform(DOMSource(document), StreamResult(it)) }
    }
}
