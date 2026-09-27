package com.example.lazymodification.utils

import com.reandroid.apk.ApkModule
import com.reandroid.archive.BlockInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.xml.XMLFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry

object InterfaceModifier {

    fun processApk(
        inputFile: File,
        outputFile: File,
        menuItemText: String,
        onStatus: (String) -> Unit
    ) {
        processApkBatch(inputFile, outputFile, listOf(menuItemText), onStatus)
    }

    fun processApkBatch(
        inputFile: File,
        outputFile: File,
        menuItems: List<String>,
        onStatus: (String) -> Unit
    ) {
        val log = StringBuilder()
        fun log(msg: String) {
            log.appendLine(msg)
            onStatus(msg)
        }

        if (menuItems.isEmpty()) {
            inputFile.copyTo(outputFile, overwrite = true)
            log("✅ Нет пунктов для удаления, копируем как есть")
            return
        }

        try {
            log("1. Загрузка APK...")
            val apkModule = ApkModule.loadApkFile(inputFile)
            val tableBlock = apkModule.tableBlock
                ?: throw IllegalStateException("Нет resources.arsc")

            // Собираем все target-строки (ref + text) для всех пунктов
            data class TargetInfo(
                val text: String,
                val ref1: String,
                val ref2: String
            )
            val targets = mutableListOf<TargetInfo>()
            for (itemText in menuItems) {
                val id = findStringResourceIdByValue(tableBlock, itemText)
                if (id == null) {
                    log("⚠️ Строка '$itemText' не найдена в ресурсах, пропускаем")
                    continue
                }
                val hexId = Integer.toHexString(id)
                val name = findStringResourceName(tableBlock, id)
                log("✅ '$itemText' → @0x$hexId / @string/$name")
                targets.add(TargetInfo(
                    text = itemText,
                    ref1 = "@0x$hexId",
                    ref2 = "@string/$name"
                ))
            }
            if (targets.isEmpty()) {
                throw IllegalStateException("Ни один из пунктов меню не найден в ресурсах")
            }

            log("2. Модификация XML (${targets.size} пунктов)...")
            var modifiedCount = 0

            val xmlPaths = apkModule.listInputSources()
                .map { it.alias ?: it.name }
                .filter { it.endsWith(".xml", ignoreCase = true) && !it.contains("res/raw", ignoreCase = true) }

            for (path in xmlPaths) {
                try {
                    val document = apkModule.getResXmlDocument(path) ?: continue
                    val originalXml = document.serializeToXml()
                    var currentXml = originalXml

                    for (t in targets) {
                        if (!currentXml.contains(t.ref1, ignoreCase = true) &&
                            !currentXml.contains(t.ref2, ignoreCase = true) &&
                            !containsExactText(currentXml, t.text)) {
                            continue
                        }
                        log("🔍 Найдено в: $path (${t.text})")
                        currentXml = processXml(currentXml, t.ref1, t.ref2, t.text)
                    }

                    if (currentXml != originalXml) {
                        try {
                            document.javaClass.getMethod("clear").invoke(document)
                        } catch (_: Exception) {
                            try { document.javaClass.getMethod("reset").invoke(document) } catch (_: Exception) {}
                        }
                        val parser = XMLFactory.newPullParser(StringReader(currentXml))
                        document.parse(parser)
                        apkModule.removeInputSource(path)
                        val blockInputSource = BlockInputSource(path, document)
                        blockInputSource.setMethod(ZipEntry.STORED)
                        apkModule.add(blockInputSource)
                        modifiedCount++
                        log("💾 Изменено: $path")
                    }
                } catch (e: Exception) {
                    log("⚠️ Ошибка $path: ${e.message}")
                }
            }

            if (modifiedCount == 0) {
                log("❌ Не найдено ни одного тега с этими строками")
            } else {
                log("✅ Изменено документов: $modifiedCount")
            }

            log("3. Сборка APK...")
            if (outputFile.exists()) outputFile.delete()
            apkModule.writeApk(outputFile)
            apkModule.close()

            log("📦 Готово! Размер: ${outputFile.length()} байт")
        } catch (e: Exception) {
            onStatus("❌ ${e.message}\n\n$log")
            e.printStackTrace()
        }
    }

    private const val TEXT_ATTRS = "android:(?:text|title|label|hint|summary|contentDescription|dialogTitle|positiveButtonText|negativeButtonText|prompt)"

    /**
     * Правило удаления «пункта меню» — работает для любых приложений с похожей разметкой:
     * 1. Пункт как тег <item> в res/menu — тег удаляется целиком.
     * 2. Пункт как <Preference*>/<CheckBoxPreference> в res/xml — блок закомментируется.
     * 3. Пункт как любой другой элемент (RadioButton / CheckBox / TextView / Button / ToggleButton …),
     *    ссылающийся на строку через @string/имя или @0xHEX в любом атрибуте
     *    (text/title/label/hint/summary/contentDescription/...), либо содержащий сам текст —
     *    такому элементу добавляется android:visibility="gone" (скрывается только он,
     *    а не весь родительский контейнер).
     */
    private fun containsExactText(xml: String, targetText: String): Boolean {
        val escapedText = Regex.escape(targetText)
        val pattern = Regex(
            """$TEXT_ATTRS\s*=\s*["']$escapedText["']""",
            RegexOption.IGNORE_CASE
        )
        return pattern.containsMatchIn(xml) || xml.contains(targetText, ignoreCase = true)
    }

    private fun processXml(xml: String, ref1: String, ref2: String, targetText: String): String {
        var result = xml
        result = removeItemTags(result, ref1, ref2, targetText)
        result = hideTargetElements(result, ref1, ref2, targetText)
        result = commentPreferenceBlocks(result, ref1, ref2, targetText)
        return result
    }

    private fun removeItemTags(xml: String, ref1: String, ref2: String, targetText: String): String {
        val escapedRef1 = Regex.escape(ref1)
        val escapedRef2 = Regex.escape(ref2)
        val escapedText = Regex.escape(targetText)
        val pattern = Regex(
            """<item\s[^>]*?(?:$escapedRef1|$escapedRef2|$TEXT_ATTRS\s*=\s*["']$escapedText["'])[^>]*?/>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.replace(xml, "")
    }

    private fun hideTargetElements(xml: String, ref1: String, ref2: String, targetText: String): String {
        var result = xml
        val targetPattern = Regex(
            "(?:" + Regex.escape(ref1) + "|" + Regex.escape(ref2) +
                "|" + TEXT_ATTRS + "\\s*=\\s*[\"']" + Regex.escape(targetText) + "[\"'])",
            RegexOption.IGNORE_CASE
        )
        var searchFrom = 0
        while (true) {
            val targetMatch = targetPattern.find(result, searchFrom) ?: break
            val targetPos = targetMatch.range.first
            val tagStart = result.lastIndexOf('<', targetPos)
            if (tagStart < 0) {
                searchFrom = targetMatch.range.last + 1
                continue
            }
            if (result.startsWith("<!--", tagStart) || result.startsWith("</", tagStart)) {
                searchFrom = targetMatch.range.last + 1
                continue
            }
            val tagEnd = result.indexOf('>', targetPos)
            if (tagEnd < 0) {
                searchFrom = targetMatch.range.last + 1
                continue
            }
            val tag = result.substring(tagStart, tagEnd + 1)
            val newTag = hideTag(tag)
            result = result.substring(0, tagStart) + newTag + result.substring(tagEnd + 1)
            searchFrom = tagStart + newTag.length
        }
        return result
    }

    private fun hideTag(tag: String): String {
        if (tag.contains("android:visibility", ignoreCase = true)) return tag
        return if (tag.endsWith("/>")) {
            tag.substring(0, tag.length - 2) + " android:visibility=\"gone\"/>"
        } else if (tag.endsWith(">")) {
            tag.substring(0, tag.length - 1) + " android:visibility=\"gone\">"
        } else {
            tag
        }
    }

    private fun commentPreferenceBlocks(xml: String, ref1: String, ref2: String, targetText: String): String {
        var result = xml
        val preferenceSelfClosing = Regex(
            """<Preference[^>]*?(?:$ref1|$ref2|$TEXT_ATTRS\s*=\s*["']${Regex.escape(targetText)}["'])[^>]*?/>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        result = preferenceSelfClosing.replace(result) { "<!-- ${it.value} -->" }
        val preferenceBlock = Regex(
            """<Preference[^>]*?(?:$ref1|$ref2|$TEXT_ATTRS\s*=\s*["']${Regex.escape(targetText)}["'])[^>]*?>.*?</Preference>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        result = preferenceBlock.replace(result) { "<!-- \n${it.value}\n-->" }
        return result
    }

    private fun findStringResourceIdByValue(tableBlock: TableBlock, targetString: String): Int? {
        for (pkg in tableBlock.listPackages()) {
            val stringResources = pkg.getResources("string")
            while (stringResources.hasNext()) {
                val resourceEntry = stringResources.next()
                val stringValues = resourceEntry.getStringValues()
                while (stringValues.hasNext()) {
                    val str = stringValues.next()
                    if (str != null && str.trim().equals(targetString.trim(), ignoreCase = true)) {
                        return resourceEntry.getResourceId()
                    }
                }
            }
        }
        return null
    }

    private fun findStringResourceName(tableBlock: TableBlock, resId: Int): String {
        for (pkg in tableBlock.listPackages()) {
            val stringResources = pkg.getResources("string")
            while (stringResources.hasNext()) {
                val resourceEntry = stringResources.next()
                if (resourceEntry.getResourceId() == resId) {
                    return resourceEntry.getName()?.toString() ?: ""
                }
            }
        }
        return ""
    }
}