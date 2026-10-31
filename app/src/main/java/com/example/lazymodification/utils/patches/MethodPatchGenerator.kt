package com.example.lazymodification.utils.patches

import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.DexComparer
import com.reandroid.apk.ApkModule
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile as DexFileIface
import java.io.File

/**
 * Строит патч из двух версий APK.
 *
 * Изменённые методы записываются правилами MATCH_REPLACE: MATCH — тело метода в базовой
 * версии, REPLACE — тело в новой. Структурные изменения (новые методы, поля, классы)
 * переносятся целыми smali-файлами через ADD_FILES, удалённые классы — через REMOVE_FILES.
 *
 * Разбираются только изменённые классы: список расхождений берётся из сравнения
 * ([DexComparer]) и не требует baksmali всего APK. Опции разбора совпадают с
 * [ApkPatchRunner], поэтому текст MATCH совпадает с базой при применении.
 */
object MethodPatchGenerator {

    /** Предел текста patch.txt — 4 МБ в [PatchFormat]; оставляем запас на заголовки. */
    private const val MAX_RULES_TEXT = 3_400_000
    private const val COMPACT_RULES = true

    data class Result(
        val text: String,
        val payloads: Map<String, File>,
        val methodRules: Int,
        val fileRules: Int,
        val removedRules: Int,
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val newVersionName: String,
        val newVersionCode: Int
    )

    data class MethodRule(
        val name: String,
        val match: String,
        val replace: String,
        val compactMatch: String? = null,
        val compactReplace: String? = null
    )

    /** Изменения одного класса: либо набор правил по методам, либо замена файла целиком. */
    data class ClassDiff(val rules: List<MethodRule>, val replaceWhole: Boolean)

    private class ManifestInfo(val packageName: String, val versionName: String, val versionCode: Int)

    private class SmaliRef(val file: File, val path: String)

    internal class Smali(
        val skeleton: String,
        val methods: LinkedHashMap<String, String>,
        val duplicate: Boolean
    )

    /**
     * @param changed готовый слепок сравнения (тип → характер изменения). Если null,
     *                сравнение выполняется внутри; экран сравнения передаёт свой список,
     *                чтобы не сканировать APK повторно.
     * @param forceWhole типы классов, которые нужно перенести целиком (повторный проход
     *                   после неудачной проверки).
     */
    fun generate(
        oldApk: File,
        newApk: File,
        workDir: File,
        author: String,
        changed: Map<String, DexComparer.ChangeKind>? = null,
        forceWhole: Set<String> = emptySet(),
        progress: (Float, String) -> Unit = { _, _ -> }
    ): Result {
        val root = File(workDir, "method_patch").apply { deleteRecursively(); mkdirs() }
        val oldInfo = readManifest(oldApk)
        val newInfo = readManifest(newApk)
        if (oldInfo.packageName != newInfo.packageName)
            throw PatchException("Разные приложения: ${oldInfo.packageName} и ${newInfo.packageName}")

        val snapshot = changed ?: run {
            progress(0.02f, "Сравнение")
            DexComparer.compare(oldApk, newApk, File(root, "cmp"), true, false, false, false)
                .associate { it.type to it.kind }
        }
        if (snapshot.isEmpty()) throw PatchException("Версии совпадают — патч не требуется")
        val types = snapshot.keys

        progress(0.08f, "Разбор ${oldInfo.versionName}")
        val old = disassembleSubset(oldApk, File(root, "old"), types) { p, s ->
            progress(0.08f + 0.36f * p, s)
        }
        progress(0.45f, "Разбор ${newInfo.versionName}")
        val new = disassembleSubset(newApk, File(root, "new"), types) { p, s ->
            progress(0.45f + 0.36f * p, s)
        }

        val payloadDir = File(root, "payloads").apply { mkdirs() }
        val payloads = linkedMapOf<String, File>()
        val text = StringBuilder(header(oldInfo, author))
        var methodRules = 0
        var fileRules = 0
        var removedRules = 0
        var overBudget = false

        snapshot.keys.sorted().forEachIndexed { index, type ->
            if (index % 32 == 0) {
                progress(0.82f + 0.12f * index / snapshot.size.coerceAtLeast(1), "${index}/${snapshot.size}")
            }
            val kind = snapshot.getValue(type)
            val base = old[type]
            val updated = new[type]
            if (overBudget) {
                when {
                    kind == DexComparer.ChangeKind.REMOVED || updated == null -> {
                        if (base != null) removedRules += appendRemove(text, base.path)
                    }
                    else -> {
                        val target = base?.path ?: updated.path
                        fileRules += appendWhole(text, payloads, payloadDir, updated, target)
                    }
                }
                return@forEachIndexed
            }
            when {
                kind == DexComparer.ChangeKind.REMOVED || (updated == null && base != null) -> {
                    removedRules += appendRemove(text, base!!.path)
                }
                base == null && updated != null -> {
                    fileRules += appendWhole(text, payloads, payloadDir, updated, updated.path)
                }
                base != null && updated != null -> {
                    val diff = diffClass(base.path, base.file, updated.file, type in forceWhole)
                    if (diff.replaceWhole) {
                        fileRules += appendWhole(text, payloads, payloadDir, updated, base.path)
                        return@forEachIndexed
                    }
                    var switched = false
                    for (rule in diff.rules) {
                        val ruleSize = (rule.compactMatch ?: rule.match).length +
                            (rule.compactReplace ?: rule.replace).length
                        if (text.length + ruleSize > MAX_RULES_TEXT) {
                            switched = true
                            break
                        }
                        text.append(methodRule(rule, base.path))
                        methodRules++
                    }
                    if (switched) {
                        fileRules += appendWhole(text, payloads, payloadDir, updated, base.path)
                        overBudget = text.length > MAX_RULES_TEXT
                    }
                }
            }
        }
        if (methodRules + fileRules + removedRules == 0)
            throw PatchException("Изменений в коде не найдено — патч не требуется")
        return Result(
            text.toString(), payloads, methodRules, fileRules, removedRules,
            oldInfo.packageName, oldInfo.versionName, oldInfo.versionCode,
            newInfo.versionName, newInfo.versionCode
        )
    }

    /**
     * Применяет готовый патч к базовой версии и возвращает классы, которые всё ещё
     * отличаются от новой версии. Пусто — патч воспроизводит новую версию полностью.
     *
     * Операция тяжёлая: применяется полный проход патчинга, поэтому вызывается только
     * по явному запросу.
     */
    fun remainingTypes(
        baseApk: File,
        patchZip: File,
        newApk: File,
        workDir: File,
        progress: (Float, String) -> Unit = { _, _ -> }
    ): Set<String> {
        val dir = File(workDir, "verify_types").apply { mkdirs() }
        val patched = File(dir, "patched.apk")
        progress(0.1f, "Применение патча")
        ApkPatchRunner.apply(null, baseApk, patchZip, patched, dir)
        progress(0.7f, "Сверка с новой версией")
        return DexComparer.compare(patched, newApk, File(dir, "cmp"), true, false, false, false)
            .map { it.type }.toSet()
    }

    // ------------------------------------------------------------------ smali

    /** Разбирает файл на «скелет» (класс, поля, аннотации) и блоки методов. */
    internal fun parseSmali(text: String): Smali {
        val lines = text.replace("\r\n", "\n").split('\n')
        val skeleton = StringBuilder()
        val methods = LinkedHashMap<String, String>()
        var duplicate = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith(".method")) {
                val start = i
                while (i < lines.size && lines[i].trim() != ".end method") i++
                if (i >= lines.size) {
                    skeleton.append(lines.subList(start, lines.size).joinToString("\n")).append('\n')
                    break
                }
                val block = lines.subList(start, i + 1).joinToString("\n")
                if (methods.put(line, block) != null) duplicate = true
                i++
                continue
            }
            skeleton.append(line).append('\n')
            i++
        }
        return Smali(skeleton.toString(), methods, duplicate)
    }

    /**
     * Сравнивает два smali-файла одного класса. Совпадение скелета и набора методов
     * позволяет записать изменение тела метода точечным правилом.
     */
    internal fun diffClass(oldPath: String, oldFile: File, newFile: File, forceWhole: Boolean): ClassDiff {
        if (forceWhole) return ClassDiff(emptyList(), true)
        val base = parseSmali(oldFile.readText())
        val updated = parseSmali(newFile.readText())
        if (base.duplicate || updated.duplicate) return ClassDiff(emptyList(), true)
        if (normalizedSkeleton(base.skeleton) != normalizedSkeleton(updated.skeleton))
            return ClassDiff(emptyList(), true)
        val rules = ArrayList<MethodRule>(4)
        for ((key, block) in base.methods) {
            val other = updated.methods[key] ?: continue
            if (other == block) continue
            // ${...} движок раскрывает как переменную — такой метод переносим файлом.
            if (block.contains("\${") || other.contains("\${")) return ClassDiff(emptyList(), true)
            rules += compactRule(ruleName(oldPath, key), block, other)
        }
        // Удалённые методы удаляем только их телом, а не целым классом.
        for ((key, block) in base.methods) {
            if (key in updated.methods) continue
            if (block.contains("\${")) return ClassDiff(emptyList(), true)
            rules += MethodRule(ruleName(oldPath, key), block, "")
        }
        // Новые методы вставляем локально перед следующим старым методом.
        val added = updated.methods.keys.filter { it !in base.methods }
        if (added.isNotEmpty()) {
            val additions = insertionRules(oldPath, base, updated)
                ?: return ClassDiff(emptyList(), true)
            rules += additions
        }
        return ClassDiff(rules, false)
    }

    private fun normalizedSkeleton(value: String): String =
        value.lineSequence().filter { it.isNotBlank() }.joinToString("\n")

    private fun insertionRules(
        oldPath: String,
        base: Smali,
        updated: Smali
    ): List<MethodRule>? {
        val newOrder = updated.methods.keys.toList()
        val added = newOrder.filter { it !in base.methods }
        if (added.isEmpty()) return emptyList()
        if (base.methods.isEmpty()) return null
        val rules = ArrayList<MethodRule>(added.size)
        // Обратный порядок сохраняет порядок методов при вставке в одну точку.
        for (key in added.asReversed()) {
            val block = updated.methods.getValue(key)
            if (block.contains("\${")) return null
            val index = newOrder.indexOf(key)
            val nextOld = newOrder.drop(index + 1).firstOrNull { it in base.methods }
            val previousOld = newOrder.take(index).lastOrNull { it in base.methods }
            if (nextOld != null) {
                val anchor = quoteRegex(nextOld)
                val replacement = block + "\n\n" + nextOld
                rules += MethodRule(
                    ruleName(oldPath, key), nextOld, replacement,
                    "(?s)$anchor", replacement
                )
            } else if (previousOld != null) {
                // Перед концом файла: последний .end method единственный,
                // если после него остались только пробелы/переводы строк.
                val replacement = ".end method\n\n" + block + "\n"
                rules += MethodRule(
                    ruleName(oldPath, key), ".end method", replacement,
                    "(?s)\\Q.end method\\E\\s*\\z", replacement
                )
            } else {
                return null
            }
        }
        return rules
    }

    private fun ruleName(path: String, methodLine: String): String {
        val type = path.substringAfter('/').removeSuffix(".smali").replace('/', '.')
        val signature = methodLine.trim().substringAfterLast(' ')
        return "$type.$signature"
    }

    /**
 * Вместо полного старого и нового метода хранит regex вокруг изменённых
 * строк. Проверка по SHA-256 намеренно не используется: патч должен
 * применяться и к следующему обновлению приложения.
     */
    private fun compactRule(name: String, oldBlock: String, newBlock: String): MethodRule {
        if (!COMPACT_RULES) return MethodRule(name, oldBlock, newBlock)
        if ("\${" in oldBlock || "\${" in newBlock) return MethodRule(name, oldBlock, newBlock)
        val oldLines = oldBlock.split('\n')
        val newLines = newBlock.split('\n')
        if (oldLines.size < 2 || newLines.size < 2) return MethodRule(name, oldBlock, newBlock)
        // Baksmali перенумеровывает :cond_/ :goto_ при вставке кода. Возвращаем
        // новые метки к старым именам — старые switch-таблицы остаются валидными.
        val labelMap = buildLabelMap(oldBlock, newBlock)
        val mappedNewLines = newLines.map { mapLabels(it, labelMap) }
        var prefix = 0
        val common = minOf(oldLines.size, newLines.size)
        while (prefix < common && oldLines[prefix] == mappedNewLines[prefix]) prefix++
        var suffix = 0
        while (suffix < common - prefix &&
            oldLines[oldLines.size - 1 - suffix] == mappedNewLines[mappedNewLines.size - 1 - suffix]
        ) suffix++

        val oldChanged = oldLines.subList(prefix, oldLines.size - suffix).joinToString("\n")
        val newChanged = mappedNewLines.subList(prefix, mappedNewLines.size - suffix).joinToString("\n")
        if (oldChanged.isEmpty() && newChanged.isEmpty()) return MethodRule(name, oldBlock, newBlock)

        // Вставка в начале метода: префикса нет, но суффикс может быть целым телом.
        // Ставим правило вокруг ближайшего заголовка/контекста, а не вокруг метода.
        if (oldChanged.isEmpty()) {
            if (prefix > 0) {
                val count = minOf(2, prefix)
                val anchor = oldLines.subList(prefix - count, prefix).joinToString("\n")
                val pattern = "(?s)(" + quoteRegex(anchor) + ")"
                val replacement = "\${GROUP1}\n" + newChanged
                return MethodRule(name, oldBlock, newBlock, pattern, replacement)
            }
            if (suffix > 0) {
                val count = minOf(2, suffix)
                val anchor = oldLines.subList(
                    oldLines.size - suffix,
                    oldLines.size - suffix + count
                ).joinToString("\n")
                val pattern = "(?s)(" + quoteRegex(anchor) + ")"
                val replacement = newChanged + "\n\${GROUP1}"
                return MethodRule(name, oldBlock, newBlock, pattern, replacement)
            }
        }

        // Локальный участок + одна строка контекста. Заголовок и .end method
        // намеренно не включаем — они остаются в целевом файле нетронутыми.
        val oldParts = ArrayList<String>()
        val newParts = ArrayList<String>()
        if (prefix > 0) {
            oldParts += oldLines[prefix - 1]
            newParts += mappedNewLines[prefix - 1]
        }
        if (oldChanged.isNotEmpty()) oldParts += oldChanged
        if (newChanged.isNotEmpty()) newParts += newChanged
        if (suffix > 0) {
            oldParts += oldLines[oldLines.size - suffix]
            newParts += mappedNewLines[mappedNewLines.size - suffix]
        }
        if (oldParts.isEmpty() || newParts.isEmpty()) return MethodRule(name, oldBlock, newBlock)
        val oldRegion = oldParts.joinToString("\n")
        val newRegion = newParts.joinToString("\n")
        if (oldRegion == oldBlock) return MethodRule(name, oldBlock, newBlock)
        val pattern = "(?s)" + quoteRegex(oldRegion)
        // В обычном method-патче всегда используем regex, даже если метод
        // короткий: иначе в patch.txt снова оказываются целые старые/новые тела.
        return MethodRule(name, oldBlock, newBlock, pattern, newRegion)
    }

    private val smaliLabel = Regex(":(?:[A-Za-z_][A-Za-z0-9_]*)")

    private fun labelsOf(value: String): List<String> =
        smaliLabel.findAll(value).map { it.value }.toList()

    private fun buildLabelMap(oldBlock: String, newBlock: String): Map<String, String> {
        val oldByCategory = labelsOf(oldBlock).distinct()
            .groupBy { it.trimStart(':').substringBeforeLast('_') }
        val newByCategory = labelsOf(newBlock).distinct()
            .groupBy { it.trimStart(':').substringBeforeLast('_') }
        val result = HashMap<String, String>()
        newByCategory.forEach { (category, newLabels) ->
            val oldLabels = oldByCategory[category] ?: return@forEach
            newLabels.zip(oldLabels).forEach { (newLabel, oldLabel) ->
                result[newLabel] = oldLabel
            }
        }
        return result
    }

    private fun mapLabels(value: String, labels: Map<String, String>): String =
        smaliLabel.replace(value) { labels[it.value] ?: it.value }

    private fun quoteRegex(value: String): String =
        "\\Q" + value.replace("\\E", "\\E\\\\E\\Q") + "\\E"

    // ------------------------------------------------------------------ текст

    private fun header(info: ManifestInfo, author: String): String = buildString {
        append("[MIN_ENGINE_VER]\n2\n\n")
        append("[AUTHOR]\n").append(author).append("\n\n")
        append("[PACKAGE]\n").append(info.packageName).append("\n\n")
    }

    private fun methodRule(rule: MethodRule, target: String): String = buildString {
        append("[MATCH_REPLACE]\n")
        append("NAME:\n").append(rule.name).append("\n\n")
        append("TARGET:\n").append(target).append("\n\n")
        val match = rule.compactMatch ?: rule.match
        val replace = rule.compactReplace ?: rule.replace
        if (rule.compactMatch != null) {
            append("REGEX:\ntrue\n\n")
            append("DOTALL:\ntrue\n\n")
        }
        append("MATCH:\n").append(match).append("\n")
        append("REPLACE:\n").append(replace).append("\n")
        append("[/MATCH_REPLACE]\n\n")
    }

    private fun appendWhole(
        text: StringBuilder,
        payloads: MutableMap<String, File>,
        payloadDir: File,
        source: SmaliRef,
        target: String
    ): Int {
        val name = "code/$target"
        val copy = File(payloadDir, name).apply { parentFile?.mkdirs() }
        source.file.copyTo(copy, overwrite = true)
        payloads[name] = copy
        text.append("[ADD_FILES]\n")
        text.append("TARGET:\n").append(target).append("\n\n")
        text.append("SOURCE:\n").append(name).append("\n")
        text.append("[/ADD_FILES]\n\n")
        return 1
    }

    private fun appendRemove(text: StringBuilder, target: String): Int {
        text.append("[REMOVE_FILES]\n")
        text.append("TARGET:\n").append(target).append("\n")
        text.append("[/REMOVE_FILES]\n\n")
        return 1
    }

    // ------------------------------------------------------------------ apk

    private fun readManifest(apk: File): ManifestInfo {
        val module = ApkModule.loadApkFile(apk)
        try {
            val manifest = module.androidManifest ?: throw PatchException("В ${apk.name} нет AndroidManifest.xml")
            return ManifestInfo(
                manifest.packageName ?: throw PatchException("В ${apk.name} не найдено имя пакета"),
                manifest.versionName.orEmpty(),
                manifest.versionCode ?: 0
            )
        } finally {
            module.close()
        }
    }

    /**
     * Разбирает в smali только запрошенные типы: каждый dex загружается один раз,
     * baksmali получает отфильтрованное представление и пишет лишь нужные классы.
     * Опции совпадают с [ApkPatchRunner] — иначе MATCH не совпадёт с базой.
     */
    private fun disassembleSubset(
        apk: File,
        root: File,
        types: Set<String>,
        progress: (Float, String) -> Unit
    ): Map<String, SmaliRef> {
        root.mkdirs()
        val dexDir = File(root, "dex").apply { mkdirs() }
        val dexFiles = ApkUtils.extractAllDex(apk, dexDir).sortedBy { it.name }
        val result = LinkedHashMap<String, SmaliRef>()
        val pending = types.toMutableSet()
        dexFiles.forEachIndexed { index, dex ->
            if (pending.isNotEmpty()) {
                val folder = if (dex.name == "classes.dex") "smali" else "smali_" + dex.nameWithoutExtension
                val loaded = DexFileFactory.loadDexFile(dex, null)
                val selected = loaded.classes.filter { it.type in pending }
                if (selected.isNotEmpty()) {
                    val dir = File(root, folder).apply { mkdirs() }
                    val subset = object : DexFileIface {
                        override fun getClasses(): Set<ClassDef> = LinkedHashSet(selected)
                        override fun getOpcodes(): Opcodes = loaded.opcodes
                    }
                    val options = BaksmaliOptions().apply {
                        apiLevel = loaded.opcodes.api
                        debugInfo = false
                        parameterRegisters = true
                    }
                    if (!Baksmali.disassembleDexFile(subset, dir, 2, options))
                        throw PatchException("Не удалось разобрать ${dex.name}")
                    selected.forEach { cls ->
                        val path = folder + "/" + cls.type.substring(1, cls.type.length - 1) + ".smali"
                        result[cls.type] = SmaliRef(File(root, path), path)
                        pending.remove(cls.type)
                    }
                }
            }
            dex.delete()
            progress((index + 1f) / dexFiles.size.coerceAtLeast(1), dex.name)
        }
        dexDir.deleteRecursively()
        return result
    }
}
