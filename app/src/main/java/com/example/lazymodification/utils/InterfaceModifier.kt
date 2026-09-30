package com.example.lazymodification.utils

import com.example.lazymodification.R
import com.reandroid.apk.ApkModule
import com.reandroid.archive.BlockInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.xml.XMLFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry

object InterfaceModifier {

    // Ключи скрываемых Preference: для framework-приложений — перехват findPreference
    private val removedPreferenceKeys = mutableListOf<String>()

    // Отложенные элементы настроек (framework): скрываются перехватом findPreference
    private data class PendingPref(val key: String, val element: ResXmlElement, val path: String)

    private val pendingPrefs = mutableListOf<PendingPref>()

    // id атрибута isPreferenceVisible (androidx) — null для framework-приложений (Tetra)
    private var visibleAttrId: Int? = null

    fun processApk(
        inputFile: File,
        outputFile: File,
        menuItemText: String,
        onStatus: (String) -> Unit
    ) {
        processApkBatch(inputFile, outputFile, listOf(menuItemText), onStatus)
    }

    /**
     * ПРАВИЛО скрытия кнопок / пунктов меню в XML-макетах (для любых приложений):
     *
     * 1. Цель определяется по вводу пользователя:
     *    - ввод найден как значение строки-ресурса → цель по строке;
     *    - иначе (или с префиксом @id/ / @+id/) → цель по android:id.
     *    Поиск строки по значению: нормализация пробелов (включая неразрывный U+00A0),
     *    приоритет точного совпадения регистра («Оцените нас» ≠ «оцените нас») и
     *    игнорирование хвостового многоточия: ввод «О программе» находит ресурс
     *    «О программе…» (пример: Moon LWP, строка about = «О программе…»/«About…»).
     *
     * 2. ВАЖНО: скрытие по строке идёт по ЧИСЛОВОМУ id ресурса, а НЕ по имени (@string/...).
     *    Имена строк часто обфусцированы — у «Спидометра» почти все строки названы `arg`,
     *    поэтому поиск по имени `@string/arg` прятал весь экран. Числовой id уникален.
     *    Реализация: обходим дерево (recursiveElements) и ищем элемент, у которого ЛЮБОЙ
     *    атрибут — ссылка на этот id (attr.type == 1 && attr.data == id): так ловятся
     *    android:text / title / label / contentDescription и любые app:*.
     *
     * 3. Кнопка-пункт — это строка-контейнер (LinearLayout / TableRow / FrameLayout /
     *    RelativeLayout / ConstraintLayout и т.п.) с android:id, внутри: иконка (ImageView) +
     *    подпись (TextView со ссылкой @string/...). Если найденный элемент — такой «лист»
     *    (TextView/ImageView) внутри именованного контейнера — прячем РОДИТЕЛЬСКИЙ контейнер
     *    (pickHideTarget), иначе прячем сам элемент.
     *
     * 4. Скрытие = android:visibility="gone": getOrCreateAndroidAttribute("visibility",
     *    0x010100dc) + setValueAsString("gone"). Модификация идёт прямо в документе
     *    (без serialize→parse), затем apkModule.writeApk.
     *
     * 5. Jetpack Compose и Flutter (пример: «Регистратор», «DepthFX») — НЕ поддерживаются.
     *    Пункты меню/настроек в них рисуются КОДОМ (Compose — в dex, Flutter/Dart — в
     *    libapp.so + JSON-переводах), а не в XML; автоматически скрыть их нельзя
     *    (глушить composable-вызовы опасно — ломается таблица групп Compose → краш при
     *    рекомпозиции). Инструмент определяет такие приложения и СООБЩАЕТ пользователю,
     *    что удаление невозможно, БЕЗ пересборки APK.
     *
     * 6. Пункты настроек (Preference) — два случая:
     *    а) androidx-приложения (в ресурсах есть attr isPreferenceVisible — Text Editor):
     *       ставим isPreferenceVisible="false" — элемент остаётся, findPreference его
     *       находит, ничего не падает;
     *    б) framework (android.preference.*, атрибута нет — Tetra Filer): visibility
     *       игнорируется, а удаление ломает код (findPreference → null → NPE). Элемент
     *       ОСТАВЛЯЕМ, в dex внедряем перехват findPreference (PreferenceHider): реальный
     *       объект возвращается всегда, пункт открепляется от экрана при первом обращении.
     *       Ключи без обращений в коде — просто удаляем.
     */    fun processApkBatch(
        inputFile: File,
        outputFile: File,
        menuItems: List<String>,
        onStatus: (String) -> Unit,
        helperDex: ByteArray? = null
    ) {
        removedPreferenceKeys.clear()
        pendingPrefs.clear()
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
            visibleAttrId = PreferenceHider.findVisibleAttrId(tableBlock)

            // Собираем цели: текст → resourceId строки; иначе → id элемента
            data class TargetInfo(val text: String, val resourceId: Int)
            val stringTargets = mutableListOf<TargetInfo>()
            val idNames = mutableListOf<String>()
            for (itemText in menuItems) {
                val trimmed = itemText.trim()
                val resourceId = findStringResourceIdByValue(tableBlock, trimmed)
                if (resourceId != null) {
                    log("✅ '$trimmed' → 0x${Integer.toHexString(resourceId)}")
                    stringTargets.add(TargetInfo(trimmed, resourceId))
                } else {
                    val normalizedId = trimmed.removePrefix("@id/").removePrefix("@+id/")
                    if (normalizedId.isNotEmpty()) {
                        idNames.add(normalizedId)
                        log("✅ id: @id/$normalizedId")
                    } else {
                        log("⚠️ '$trimmed' не найдена ни как текст, ни как id")
                    }
                }
            }
            if (stringTargets.isEmpty() && idNames.isEmpty()) {
                val kind = detectAppKind(inputFile)
                if (kind == "flutter") {
                    throw UnsupportedAppType(
                        R.string.err_app_flutter,
                        "Приложение на Flutter (Dart): меню строится в коде — удаление пунктов автоматически невозможно."
                    )
                }
                if (kind == "compose") {
                    throw UnsupportedAppType(
                        R.string.err_app_compose,
                        "Приложение на Jetpack Compose: пункты строятся кодом — удаление автоматически невозможно."
                    )
                }
                throw IllegalStateException("Ни один из пунктов меню не найден")
            }

            log("2. Модификация XML (${stringTargets.size} пунктов)...")
            var modifiedCount = 0

            val xmlPaths = apkModule.listInputSources()
                .map { it.alias ?: it.name }
                .filter { it.endsWith(".xml", ignoreCase = true) && !it.contains("res/raw", ignoreCase = true) }

            for (path in xmlPaths) {
                try {
                    val document = apkModule.getResXmlDocument(path) ?: continue
                    var docModified = false
                    for (t in stringTargets) {
                        val n = hideElementByResourceId(document, t.resourceId, path)
                        if (n > 0) {
                            docModified = true
                            log("🔍 Найдено: $path (${t.text}, $n элем.)")
                        }
                    }
                    for (idName in idNames) {
                        val n = hideElementById(document, idName, path)
                        if (n > 0) {
                            docModified = true
                            log("🔍 Найдено по id: $path (@id/$idName)")
                        }
                    }
                    if (docModified) {
                        modifiedCount++
                        // ВАЖНО: заменить входной поток — иначе writeApk запишет
                        // СТАРЫЕ байты документа (модификации теряются).
                        apkModule.removeInputSource(path)
                        val src = BlockInputSource(path, document)
                        src.setMethod(ZipEntry.STORED)
                        apkModule.add(src)
                        log("💾 Изменено: $path")
                    }
                } catch (e: Exception) {
                    log("⚠️ Ошибка $path: ${e.message}")
                }
            }

            if (pendingPrefs.isNotEmpty()) {
                val uniqueKeys = removedPreferenceKeys.distinct()
                log("🛡 Пункты настроек (framework): ${uniqueKeys.joinToString()}")
                val hiderDir = File(inputFile.parentFile, ".prefhider_tmp")
                hiderDir.mkdirs()
                val res = PreferenceHider.apply(apkModule, inputFile, hiderDir, uniqueKeys, helperDex) { msg -> log(msg) }
                val affectedPaths = mutableSetOf<String>()
                for (p in pendingPrefs) {
                    if (p.key in res.noSites) {
                        p.element.parentElement?.removeElementsIf { it === p.element }
                        affectedPaths.add(p.path)
                    }
                }
                for (path in affectedPaths) {
                    val doc = apkModule.getResXmlDocument(path) ?: continue
                    apkModule.removeInputSource(path)
                    val src = BlockInputSource(path, doc)
                    src.setMethod(ZipEntry.STORED)
                    apkModule.add(src)
                }
                if (res.noSites.isNotEmpty()) log("🗑 Без обращений в коде — удалено: ${res.noSites.joinToString()}")
                if (res.otherSites.isNotEmpty()) log("⚠️ Обращения вне фрагмента: ${res.otherSites.joinToString()}")
                if (res.covered.isNotEmpty()) modifiedCount++
            }

            if (modifiedCount == 0) {
                val kind = detectAppKind(inputFile)
                if (kind == "flutter") {
                    throw UnsupportedAppType(
                        R.string.err_app_flutter,
                        "Приложение на Flutter (Dart): меню строится в коде — удаление пунктов автоматически невозможно."
                    )
                }
                if (kind == "compose") {
                    throw UnsupportedAppType(
                        R.string.err_app_compose,
                        "Приложение на Jetpack Compose: пункты строятся кодом — удаление автоматически невозможно."
                    )
                }
                log("❌ Не найдено ни одного тега с этими строками")
            } else {
                log("✅ Изменено документов: $modifiedCount")
            }


            log("3. Сборка APK...")
            if (outputFile.exists()) outputFile.delete()
            apkModule.writeApk(outputFile)
            apkModule.close()

            log("📦 Готово! Размер: ${outputFile.length()} байт")
        } catch (e: UnsupportedAppType) {
            // Сообщение «удаление невозможно (Compose/Flutter)» должно дойти до
            // пользователя как есть — пересборка APK в этом случае не выполняется.
            throw e
        } catch (e: Exception) {
            onStatus("❌ ${e.message}\n\n$log")
            e.printStackTrace()
        }
    }

    /** Приложение строит меню кодом (Compose/Flutter) — удаление пунктов невозможно. */
    class UnsupportedAppType(val resId: Int, message: String) : Exception(message)

    private fun hideElementByResourceId(document: ResXmlDocument, resId: Int, path: String): Int {
        var count = 0
        val elements = document.documentElement.recursiveElements()
        while (elements.hasNext()) {
            val element = elements.next()
            val attrs = element.attributes
            var matches = false
            while (attrs.hasNext()) {
                val attr = attrs.next()
                if (attr.type.toInt() == 1 && attr.data == resId) { matches = true; break }
            }
            if (!matches) continue
            hideOrRemove(pickHideTarget(element), path)
            count++
        }
        return count
    }

    private fun hideElementById(document: ResXmlDocument, idName: String, path: String): Int {
        var count = 0
        val elements = document.documentElement.recursiveElements()
        while (elements.hasNext()) {
            val element = elements.next()
            val idAttr = element.searchAttributeByName("id") ?: continue
            val value = idAttr.valueString ?: continue
            if (value == "@id/$idName" || value == "@+id/$idName" || value.endsWith("/$idName")) {
                hideOrRemove(element, path)
                count++
            }
        }
        return count
    }

    private fun pickHideTarget(element: ResXmlElement): ResXmlElement {
        val parent = element.parentElement
        if (parent != null && isContainerElement(parent) && parent.searchAttributeByName("id") != null) {
            return parent
        }
        return element
    }

    private fun isContainerElement(element: ResXmlElement): Boolean {
        val name = element.name.lowercase()
        return name == "linearlayout" || name.endsWith(".linearlayout") ||
            name == "tablerow" || name.endsWith(".tablerow") ||
            name == "framelayout" || name.endsWith(".framelayout") ||
            name == "relativelayout" || name.endsWith(".relativelayout") ||
            name == "tablelayout" || name.endsWith(".tablelayout") ||
            name == "gridlayout" || name.endsWith(".gridlayout") ||
            name == "constraintlayout" || name.endsWith(".constraintlayout") ||
            name == "coordinatorlayout" || name.endsWith(".coordinatorlayout")
    }

    private fun hideOrRemove(element: ResXmlElement, path: String) {
        if (isPreferenceElement(element)) {
            val key = element.searchAttributeByName("key")?.valueString
            if (key != null && key.isNotEmpty()) {
                val attrId = visibleAttrId
                if (attrId != null) {
                    // androidx-приложения (Text Editor и др.): штатный атрибут — элемент
                    // остаётся в дереве (findPreference его найдёт), но не отображается.
                    element.getOrCreateAttribute("isPreferenceVisible", attrId).setValueAsBoolean(false)
                } else {
                    // framework-приложения (Tetra Filer): android:visibility игнорируется,
                    // а удаление ломает код (findPreference → null → NPE). Элемент ОСТАВЛЯЕМ,
                    // скроем перехватом findPreference (PreferenceHider).
                    if (pendingPrefs.none { it.element === element }) {
                        removedPreferenceKeys.add(key)
                        pendingPrefs.add(PendingPref(key, element, path))
                    }
                }
            } else {
                // без android:key обращений из кода быть не может — удаляем как раньше
                element.parentElement?.removeElementsIf { it === element }
            }
        } else {
            setHidden(element)
        }
    }

    private fun isPreferenceElement(element: ResXmlElement): Boolean {
        val n = element.name.lowercase()
        return n == "preference" || n.endsWith(".preference") || n.endsWith("preference")
    }

    private fun setHidden(element: ResXmlElement) {
        if (isMenuItem(element)) {
            // У пунктов меню (<item> внутри <menu>/<group>) нет атрибута visibility —
            // инфлейтер его игнорирует. Такие пункты прячет android:visible="false".
            val vis = element.getOrCreateAndroidAttribute("visible", 0x01010194)
            vis.setValueAsBoolean(false)
        } else {
            // ВАЖНО: visibility — это ENUM. setValueAsString писал бы СТРОКУ «gone»,
            // а рантайм читает getInt → NumberFormatException (был вылет у Moon LWP).
            // Пишем enum напрямую: TYPE_INT_DEC (0x10) + ordinal 2 (gone).
            val vis = element.getOrCreateAndroidAttribute("visibility", 0x010100dc)
            vis.type = 0x10
            vis.data = 2
        }
    }

    private fun isMenuItem(element: ResXmlElement): Boolean {
        val name = element.name.lowercase()
        if (name != "item") return false
        val parent = element.parentElement ?: return false
        val pname = parent.name.lowercase()
        return pname == "menu" || pname == "group" || pname.contains("menu")
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
            if (tagStart < 0) { searchFrom = targetMatch.range.last + 1; continue }
            if (result.startsWith("<!--", tagStart) || result.startsWith("</", tagStart)) {
                searchFrom = targetMatch.range.last + 1
                continue
            }
            val tagEnd = result.indexOf('>', targetPos)
            if (tagEnd < 0) { searchFrom = targetMatch.range.last + 1; continue }

            // Если элемент лежит внутри именованной строки-контейнера (LinearLayout/TableRow и т.п. с @id),
            // прячем всю строку целиком (кнопку), а не только подпись.
            val parentStart = findParentOpenTagStart(result, tagStart)
            val parentName = if (parentStart >= 0) tagName(result, parentStart) else ""
            val parentEnd = if (parentStart >= 0) result.indexOf('>', parentStart) else -1
            val parentHasId = parentEnd >= 0 &&
                Regex("""android:id\s*=\s*["']@\+?id/[^"']+["']""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(result.substring(parentStart, parentEnd + 1))
            val hideParent = parentStart >= 0 && isContainerTagName(parentName) && parentHasId

            if (hideParent) {
                val parentTag = result.substring(parentStart, parentEnd + 1)
                val newParent = hideTag(parentTag)
                result = result.substring(0, parentStart) + newParent + result.substring(parentEnd + 1)
                val shift = newParent.length - parentTag.length
                searchFrom = targetMatch.range.last + 1 + shift
            } else {
                val tag = result.substring(tagStart, tagEnd + 1)
                val newTag = hideTag(tag)
                result = result.substring(0, tagStart) + newTag + result.substring(tagEnd + 1)
                searchFrom = tagStart + newTag.length
            }
        }
        return result
    }

    private fun findParentOpenTagStart(xml: String, elementStart: Int): Int {
        var depth = 0
        var i = elementStart - 1
        while (i >= 0) {
            if (xml[i] == '>') {
                val open = xml.lastIndexOf('<', i)
                if (open < 0) return -1
                val tag = xml.substring(open, i + 1)
                if (tag.startsWith("</")) {
                    depth++
                } else if (!tag.startsWith("<!--") && !tag.endsWith("/>")) {
                    if (depth == 0) return open
                    depth--
                }
                i = open - 1
            } else {
                i--
            }
        }
        return -1
    }

    private fun tagName(xml: String, tagStart: Int): String {
        var i = tagStart + 1
        val sb = StringBuilder()
        while (i < xml.length) {
            val c = xml[i]
            if (c == ' ' || c == '>' || c == '/' || c == '\n' || c == '\t' || c == '\r') break
            sb.append(c)
            i++
        }
        return sb.toString().lowercase()
    }

    private fun isContainerTagName(name: String): Boolean {
        return name == "linearlayout" || name == "tablerow" || name == "framelayout" ||
            name == "relativelayout" || name == "tablelayout" || name == "gridlayout" ||
            name == "constraintlayout" || name == "coordinatorlayout" ||
            name == "androidx.constraintlayout.widget.constraintlayout" ||
            name == "androidx.coordinatorlayout.widget.coordinatorlayout"
    }

    private fun hideTag(tag: String): String {
        if (Regex("""android:visibility\s*=\s*["']gone["']""", RegexOption.IGNORE_CASE).containsMatchIn(tag)) return tag
        val visRegex = Regex("""android:visibility\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE)
        if (visRegex.containsMatchIn(tag)) {
            return visRegex.replace(tag, "android:visibility=\"gone\"")
        }
        return if (tag.endsWith("/>")) {
            tag.substring(0, tag.length - 2) + " android:visibility=\"gone\"/>"
        } else if (tag.endsWith(">")) {
            tag.substring(0, tag.length - 1) + " android:visibility=\"gone\">"
        } else {
            tag
        }
    }

    private fun hideElementById(xml: String, idName: String): String {
        var result = xml
        val targetPattern = Regex(
            "android:id\\s*=\\s*[\"']@\\+?id/" + Regex.escape(idName) + "[\"']",
            RegexOption.IGNORE_CASE
        )
        var searchFrom = 0
        while (true) {
            val targetMatch = targetPattern.find(result, searchFrom) ?: break
            val targetPos = targetMatch.range.first
            val tagStart = result.lastIndexOf('<', targetPos)
            if (tagStart < 0) { searchFrom = targetMatch.range.last + 1; continue }
            if (result.startsWith("<!--", tagStart) || result.startsWith("</", tagStart)) {
                searchFrom = targetMatch.range.last + 1
                continue
            }
            val tagEnd = result.indexOf('>', targetPos)
            if (tagEnd < 0) { searchFrom = targetMatch.range.last + 1; continue }
            val tag = result.substring(tagStart, tagEnd + 1)
            val newTag = hideTag(tag)
            result = result.substring(0, tagStart) + newTag + result.substring(tagEnd + 1)
            searchFrom = tagStart + newTag.length
        }
        return result
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

    private fun normalizeForMatch(s: String): String = s.replace(Regex("[\\s\\u00A0]+"), " ").trim()

    /** Тип приложения: "flutter", "compose" или "" (обычное). */
    private fun detectAppKind(apkFile: File): String {
        try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                var flutter = false
                var compose = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name
                    if (n.startsWith("assets/flutter_assets/") || n.endsWith("libflutter.so")) {
                        flutter = true
                        if (compose) break
                        continue
                    }
                    if (!compose && !flutter && n.matches(Regex("classes\\d*\\.dex")) && e.size in 1..40000000L) {
                        zip.getInputStream(e).use { ins ->
                            if (bytesContain(ins.readBytes(), "androidx/compose/")) compose = true
                        }
                    }
                }
                if (flutter) return "flutter"
                if (compose) return "compose"
                return ""
            }
        } catch (_: Exception) {
            return ""
        }
    }

    private fun bytesContain(hay: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || hay.size < n.size) return false
        var i = 0
        val end = hay.size - n.size
        while (i <= end) {
            if (hay[i] == n[0]) {
                var j = 1
                while (j < n.size && hay[i + j] == n[j]) j++
                if (j == n.size) return true
            }
            i++
        }
        return false
    }

    private fun findStringResourceIdByValue(tableBlock: TableBlock, targetString: String): Int? {
        val normalizedTarget = normalizeForMatch(targetString)
        // Сначала точное совпадение (с учётом регистра), затем без учёта регистра
        for (ignoreCase in listOf(false, true)) {
            for (pkg in tableBlock.listPackages()) {
                val stringResources = pkg.getResources("string")
                while (stringResources.hasNext()) {
                    val resourceEntry = stringResources.next()
                    val stringValues = resourceEntry.getStringValues()
                    while (stringValues.hasNext()) {
                        val str = stringValues.next() ?: continue
                        val n = normalizeForMatch(str)
                        // Допускаем многоточие: «О программе…» в ресурсах vs «О программе» в вводе
                        val matched = if (ignoreCase) {
                            n.equals(normalizedTarget, ignoreCase = true) ||
                                n.trimEnd('…', '.').equals(normalizedTarget.trimEnd('…', '.'), ignoreCase = true)
                        } else {
                            n == normalizedTarget ||
                                n.trimEnd('…', '.') == normalizedTarget.trimEnd('…', '.')
                        }
                        if (matched) return resourceEntry.getResourceId()
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
