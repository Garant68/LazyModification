package com.example.lazymodification.utils.patches

import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class PatchMetadata(val packageName: String, val application: String = "",
                         val activities: List<String> = emptyList(), val launchers: List<String> = emptyList())
data class PatchStats(var matches: Int = 0, var added: Int = 0, var removed: Int = 0,
                      var scripts: Int = 0, val changedPaths: MutableSet<String> = linkedSetOf()) {
    val changed: Boolean get() = changedPaths.isNotEmpty() || scripts > 0
}

/** Pure JVM rule interpreter. All writes are confined to a disposable decoded APK project. */
class PatchEngine(rootDir: File, private val metadata: PatchMetadata,
                  private val log: (String) -> Unit = {},
                  private val executeDex: ((PatchRule, ZipFile, File) -> Unit)? = null) {
    /** Корень проекта в каноническом виде: пути сравниваются одинаково при любом рабочем каталоге. */
    private val root: File = rootDir.canonicalFile
    private val variablePattern = Regex("\\$\\{([A-Za-z_][A-Za-z_0-9]*)[}]")
    private val variables = mutableMapOf("PACKAGE" to metadata.packageName,
        "APPLICATION" to metadata.application, "LAUNCHER_ACTIVITY" to metadata.launchers.firstOrNull().orEmpty())
    private val stats = PatchStats()

    fun apply(document: PatchDocument, archive: File, progress: (Int, Int) -> Unit = { _, _ -> }): PatchStats {
        val packages = document.packagePattern.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (packages.none { glob(it).matcher(metadata.packageName).matches() })
            throw PatchException("Патч для ${document.packagePattern}, выбран ${metadata.packageName}")
        val labels = document.rules.withIndex().filter { it.value.name.isNotEmpty() }.associate { it.value.name to it.index }
        ZipFile(archive).use { zip ->
            var pc = 0
            var steps = 0
            while (pc < document.rules.size) {
                if (Thread.currentThread().isInterrupted) throw PatchException("Патчинг отменён")
                if (++steps > maxOf(1000, document.rules.size * 100)) throw PatchException("Зацикливание GOTO")
                val rule = document.rules[pc]
                progress(pc, document.rules.size)
                try {
                    var next: String? = null
                    when (rule.type) {
                        "MATCH_REPLACE" -> replace(rule)
                        "MATCH_ASSIGN" -> assign(rule)
                        "MATCH_GOTO" -> if (findFirst(rule) != null) next = expand(rule.value("GOTO")).trim()
                        "GOTO" -> next = expand(rule.value("GOTO")).trim()
                        "DUMMY" -> Unit
                        "ADD_FILES" -> add(rule, zip)
                        "REMOVE_FILES" -> remove(rule)
                        "MERGE" -> {
                            val source = payload(zip, expand(rule.value("SOURCE")).trim())
                            ResourcePatchMerger.merge(root, source, stats)
                        }
                        "EXECUTE_DEX" -> {
                            val executor = executeDex ?: throw PatchException("DEX-скрипты доступны при запуске на Android")
                            executor(rule.copy(fields = rule.fields.mapValues { expand(it.value) }), zip, root)
                            stats.scripts++
                        }
                    }
                    log("${pc + 1}/${document.rules.size} ${rule.type}${if (rule.name.isEmpty()) "" else " (${rule.name})"}: совпадений ${stats.matches}, файлов ${stats.changedPaths.size}")
                    pc = if (next == null) pc + 1 else labels[next] ?: throw PatchException("Неизвестная метка $next")
                } catch (e: Exception) {
                    throw PatchException("Строка ${rule.line}, ${rule.type}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            progress(document.rules.size, document.rules.size)
        }
        return stats
    }

    private fun pattern(rule: PatchRule): Pattern {
        val raw = expand(rule.value("MATCH"))
        if (raw.isEmpty()) throw PatchException("Пустое выражение MATCH")
        return Pattern.compile(if (rule.flag("REGEX")) raw else Pattern.quote(raw), if (rule.flag("DOTALL")) Pattern.DOTALL else 0)
    }

    private fun replace(rule: PatchRule) {
        val pattern = pattern(rule)
        var count = 0
        targets(rule.value("TARGET")).filter { it.isFile }.forEach { file ->
            val input = text(file)
            val matcher = pattern.matcher(input)
            val output = StringBuilder()
            var last = 0
            var n = 0
            while (matcher.find()) {
                output.append(input, last, matcher.start())
                output.append(expand(rule.value("REPLACE"), matcher))
                last = matcher.end(); n++
            }
            if (n > 0) {
                output.append(input, last, input.length)
                val result = output.toString()
                if (result != input) {
                    file.writeText(result)
                    stats.changedPaths += relative(file)
                }
                count += n
            }
        }
        stats.matches += count
        if (count == 0) log("${rule.type}, строка ${rule.line}: совпадений нет")
    }

    private fun assign(rule: PatchRule) {
        val match = findFirst(rule) ?: return
        rule.value("ASSIGN").lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith('#') }.forEach { line ->
            val at = line.indexOf('=')
            if (at < 1) throw PatchException("ASSIGN: ожидается имя = значение")
            val name = line.substring(0, at).trim()
            if (!name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) throw PatchException("Недопустимое имя переменной $name")
            variables[name] = expand(line.substring(at + 1).trim(), match)
        }
    }

    private fun findFirst(rule: PatchRule): Matcher? {
        val pattern = pattern(rule)
        for (file in targets(rule.value("TARGET")).filter { it.isFile }) {
            val m = pattern.matcher(text(file))
            if (m.find()) { stats.matches++; return m }
        }
        return null
    }

    private fun add(rule: PatchRule, zip: ZipFile) {
        val source = expand(rule.value("SOURCE")).trim()
        val target = expand(rule.value("TARGET")).trim()
        val data = payload(zip, source)
        if (rule.flag("EXTRACT")) {
            val parent = if (target == "/" || target.isBlank()) root else PatchFormat.child(root, target)
            ZipInputStream(data.inputStream()).use { input ->
                var total = 0L
                while (true) {
                    val entry = input.nextEntry ?: break
                    val file = PatchFormat.child(parent, entry.name.trimEnd('/'))
                    if (entry.isDirectory) file.mkdirs() else {
                        val bytes = PatchFormat.readBounded(input, 256L * 1024 * 1024)
                        total += bytes.size
                        if (total > 512L * 1024 * 1024) throw PatchException("Слишком большой вложенный ZIP")
                        write(file, bytes)
                    }
                    input.closeEntry()
                }
            }
        } else {
            val file = PatchFormat.child(root, target)
            write(if (target.endsWith('/') || file.isDirectory) File(file, source.substringAfterLast('/')) else file, data)
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw PatchException("Файл вне проекта")
        if (!file.isFile || !file.readBytes().contentEquals(bytes)) {
            file.parentFile?.mkdirs(); file.writeBytes(bytes)
            stats.changedPaths += relative(file); stats.added++
        }
    }

    private fun remove(rule: PatchRule) {
        targets(rule.value("TARGET"), includeDirectories = true).sortedByDescending { it.path.length }.forEach { file ->
            if (file.exists()) {
                if (file.isDirectory) file.walkTopDown().filter { it.isFile }.forEach { stats.changedPaths += relative(it); stats.removed++ }
                else { stats.changedPaths += relative(file); stats.removed++ }
                if (!file.deleteRecursively()) throw PatchException("Не удалось удалить ${relative(file)}")
            }
        }
    }

    private fun targets(raw: String, includeDirectories: Boolean = false): List<File> {
        val found = linkedSetOf<File>()
        expand(raw).lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { target ->
            val components = when (target) {
                "[APPLICATION]" -> listOf(metadata.application)
                "[ACTIVITIES]", "[ACTIVTIES]" -> metadata.activities
                "[LAUNCHER_ACTIVITIES]", "[LAUNCHER_ACTIVTIES]" -> metadata.launchers
                else -> null
            }
            if (components != null) {
                val suffixes = components.filter { it.isNotEmpty() }.map { it.replace('.', '/') + ".smali" }.toSet()
                root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("smali") }.forEach { folder ->
                    suffixes.forEach { name -> File(folder, name).takeIf { it.isFile }?.let { found += it } }
                }
            } else {
                val clean = target.trimEnd('/')
                PatchFormat.validatePath(clean)
                if ('*' !in clean && '?' !in clean) {
                    PatchFormat.child(root, clean).takeIf { it.exists() }?.let { found += it }
                } else {
                    val regex = glob(clean)
                    root.walkTopDown().filter { it != root && (it.isFile || includeDirectories) }.forEach {
                        if (regex.matcher(relative(it)).matches()) found += it
                    }
                }
            }
        }
        return found.toList().sortedBy { relative(it) }
    }

    private fun expand(value: String, match: Matcher? = null): String = variablePattern.replace(value) { token ->
        val name = token.groupValues[1]
        if (name.startsWith("GROUP") && name.substring(5).toIntOrNull() != null && match != null) {
            val index = name.substring(5).toInt()
            if (index > match.groupCount()) throw PatchException("Нет группы $name")
            match.group(index).orEmpty()
        } else variables[name] ?: token.value
    }

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
    private fun text(file: File): String {
        if (file.length() > 32L * 1024 * 1024) throw PatchException("Текстовый файл больше 32 МБ: ${relative(file)}")
        val bytes = file.readBytes()
        if (bytes.any { it == 0.toByte() }) throw PatchException("Двоичный файл ${relative(file)}; для кода используйте smali")
        return String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
    }

    companion object {
        fun glob(pattern: String): Pattern = Pattern.compile("^" + buildString {
            pattern.forEach { c -> append(when (c) { '*' -> ".*"; '?' -> "."; else -> Pattern.quote(c.toString()) }) }
        } + "$")
        fun payload(zip: ZipFile, path: String): ByteArray {
            PatchFormat.validatePath(path)
            val entry = zip.getEntry(path) ?: throw PatchException("В ZIP нет файла $path")
            return zip.getInputStream(entry).use { PatchFormat.readBounded(it, 256L * 1024 * 1024) }
        }
    }
}
