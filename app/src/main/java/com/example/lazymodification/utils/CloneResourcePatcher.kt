package com.example.lazymodification.utils

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Клонирование меняет package в манифесте и dex, но binary XML (layout,
 * menu, drawable) хранит имена custom View отдельными строками. Без этой
 * замены Activity падает с ClassNotFoundException при inflate.
 */
object CloneResourcePatcher {

    fun patch(input: File, output: File, oldPackage: String, newPackage: String): Int {
        if (oldPackage == newPackage) {
            input.copyTo(output, overwrite = true)
            return 0
        }
        val oldPath = oldPackage.replace('.', '/')
        val newPath = newPackage.replace('.', '/')
        val tempDir = File(output.parentFile, "clone_xml_${System.nanoTime()}").apply { mkdirs() }
        val replacements = linkedMapOf<String, File>()

        try {
            ZipFile(input).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith("res/") ||
                        !entry.name.endsWith(".xml", ignoreCase = true)
                    ) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (!ResXmlDocument.isResXmlBlock(bytes)) continue

                    val document = ResXmlDocument()
                    document.readBytes(ByteArrayInputStream(bytes))
                    var changed = false
                    for (item in document.stringPool) {
                        val text = item.get() ?: continue
                        var updated = text
                        if (updated.contains(oldPackage)) updated = updated.replace(oldPackage, newPackage)
                        if (updated.contains(oldPath)) updated = updated.replace(oldPath, newPath)
                        if (updated != text) {
                            item.set(updated)
                            changed = true
                        }
                    }
                    if (changed) {
                        val temp = File(tempDir, entry.name.replace('/', '_'))
                        document.writeBytes(temp)
                        replacements[entry.name] = temp
                    }
                }
            }
            if (replacements.isEmpty()) {
                input.copyTo(output, overwrite = true)
                return 0
            }
            FastZipRepack.repack(input, replacements, output, applyClean = false)
            return replacements.size
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
