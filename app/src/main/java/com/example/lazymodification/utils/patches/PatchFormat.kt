package com.example.lazymodification.utils.patches

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PatchException(message: String) : IOException(message)

data class PatchRule(val type: String, val fields: Map<String, String>, val line: Int) {
    val name: String get() = value("NAME").trim()
    fun value(key: String): String = fields[key].orEmpty()
    fun flag(key: String): Boolean = value(key).trim().equals("true", true)
}

data class PatchDocument(val text: String, val author: String, val packagePattern: String,
                         val engine: Int, val rules: List<PatchRule>,
                         val versionNamePattern: String = "*",
                         val minVersionCode: Int? = null,
                         val maxVersionCode: Int? = null) {
    val needsSmali: Boolean get() = rules.any { r ->
        r.flag("SMALI_NEEDED") || r.type == "MERGE" ||
            r.value("TARGET").let { it.contains("smali") || it.contains("[APPLICATION]") ||
                it.contains("[ACTIV") || it.contains("[LAUNCHER_ACTIV") }
    }
    val needsResources: Boolean get() = rules.any { r ->
        r.type == "MERGE" || r.type == "EXECUTE_DEX" || r.value("TARGET").let {
            it.contains("AndroidManifest") || it.contains(".xml") || it.lineSequence().any { p -> p.trim().startsWith("res/") }
        }
    }
}

/** APK Editor/PCompiler text format. Payload indentation, comments and empty REPLACE are preserved. */
object PatchFormat {
    val ruleTypes = listOf("MATCH_REPLACE", "ADD_FILES", "REMOVE_FILES", "MATCH_ASSIGN",
        "MATCH_GOTO", "GOTO", "MERGE", "EXECUTE_DEX", "DUMMY")
    private val headers = setOf("MIN_ENGINE_VER", "AUTHOR", "PACKAGE",
        "VERSION_NAME", "MIN_VERSION_CODE", "MAX_VERSION_CODE")
    private val keys = setOf("NAME", "TARGET", "SOURCE", "EXTRACT", "MATCH", "REGEX", "DOTALL",
        "REPLACE", "ASSIGN", "GOTO", "SCRIPT", "SMALI_NEEDED", "MAIN_CLASS", "ENTRANCE", "PARAM", "INTERFACE_VERSION")
    const val MAX_TEXT = 4 * 1024 * 1024

    fun parse(raw: String): PatchDocument {
        if (raw.length > MAX_TEXT) throw PatchException("patch.txt: размер больше 4 МБ")
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        val metadata = linkedMapOf<String, String>()
        val rules = mutableListOf<PatchRule>()
        var i = 0
        while (i < lines.size) {
            val tag = lines[i].trim()
            if (tag.isEmpty() || tag.startsWith("#")) { i++; continue }
            if (!tag.matches(Regex("\\[[A-Z_]+]"))) throw PatchException("Строка ${i + 1}: ожидалось [ПРАВИЛО]")
            val type = tag.substring(1, tag.length - 1)
            val start = ++i
            if (type in headers) {
                val value = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("[")) {
                    if (!lines[i].trim().startsWith("#")) value += lines[i]
                    i++
                }
                metadata[type] = value.joinToString("\n").trim()
                if (i < lines.size && lines[i].trim() == "[/$type]") i++
                continue
            }
            if (type !in ruleTypes) throw PatchException("Строка $start: неизвестное правило [$type]")
            val values = linkedMapOf<String, MutableList<String>>()
            var field: String? = null
            var closed = false
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                if (trimmed == "[/$type]") { closed = true; i++; break }
                // Component targets such as [APPLICATION] are field values, not section tags.
                if (trimmed.matches(Regex("\\[/?(?:${ruleTypes.joinToString("|")})]")))
                    throw PatchException("Строка ${i + 1}: пропущено [/$type]")
                val key = trimmed.removeSuffix(":")
                if (trimmed.endsWith(":") && key in keys) {
                    if (values.containsKey(key)) throw PatchException("Строка ${i + 1}: повтор поля $key")
                    field = key
                    values[key] = mutableListOf()
                } else if (field != null) {
                    values.getValue(field).add(line)
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    throw PatchException("Строка ${i + 1}: неизвестное поле $trimmed")
                }
                i++
            }
            if (!closed) throw PatchException("Строка $start: пропущено [/$type]")
            val fields = values.mapValues { (key, value) ->
                val v = value.joinToString("\n").trim('\n')
                if (key in setOf("MATCH", "REPLACE", "PARAM")) v else v.trim()
            }
            val rule = PatchRule(type, fields, start)
            required(type).forEach { key ->
                if (rule.value(key).isBlank()) throw PatchException("Строка $start, $type: не заполнено $key")
            }
            if (type == "MATCH_REPLACE" && !fields.containsKey("REPLACE"))
                throw PatchException("Строка $start: требуется REPLACE:, для удаления оставьте его пустым")
            if (type == "MATCH_ASSIGN" && !rule.flag("REGEX"))
                throw PatchException("Строка $start: MATCH_ASSIGN требует REGEX: true")
            listOf("REGEX", "DOTALL", "EXTRACT", "SMALI_NEEDED").forEach { key ->
                if (key in fields && fields[key] !in listOf("true", "false"))
                    throw PatchException("Строка $start: $key должен быть true или false")
            }
            rules += rule
        }
        val engine = metadata["MIN_ENGINE_VER"]?.toIntOrNull() ?: if ("MIN_ENGINE_VER" in metadata)
            throw PatchException("MIN_ENGINE_VER должен быть числом") else 1
        if (engine !in 1..2) throw PatchException("Требуется движок $engine; поддерживаются версии 1 и 2")
        val minVersionCode = metadata["MIN_VERSION_CODE"]?.let {
            it.trim().toIntOrNull() ?: throw PatchException("MIN_VERSION_CODE должен быть числом")
        }
        val maxVersionCode = metadata["MAX_VERSION_CODE"]?.let {
            it.trim().toIntOrNull() ?: throw PatchException("MAX_VERSION_CODE должен быть числом")
        }
        if (minVersionCode != null && maxVersionCode != null && minVersionCode > maxVersionCode)
            throw PatchException("MIN_VERSION_CODE больше MAX_VERSION_CODE")
        if (rules.isEmpty()) throw PatchException("В патче нет правил")
        val labels = rules.map { it.name }.filter { it.isNotEmpty() }
        if (labels.size != labels.toSet().size) throw PatchException("Имена NAME должны быть уникальными")
        rules.filter { it.type in listOf("GOTO", "MATCH_GOTO") }.forEach {
            if (it.value("GOTO").trim() !in labels) throw PatchException("Строка ${it.line}: неизвестная метка ${it.value("GOTO")}")
        }
        return PatchDocument(
            text, metadata["AUTHOR"].orEmpty(), metadata["PACKAGE"].orEmpty().ifBlank { "*" }, engine, rules,
            metadata["VERSION_NAME"].orEmpty().trim().ifBlank { "*" },
            minVersionCode, maxVersionCode
        )
    }

    fun required(type: String): List<String> = when (type) {
        "MATCH_REPLACE" -> listOf("TARGET", "MATCH")
        "MATCH_ASSIGN" -> listOf("TARGET", "MATCH", "ASSIGN")
        "MATCH_GOTO" -> listOf("TARGET", "MATCH", "GOTO")
        "ADD_FILES" -> listOf("SOURCE", "TARGET")
        "REMOVE_FILES" -> listOf("TARGET")
        "GOTO" -> listOf("GOTO")
        "MERGE" -> listOf("SOURCE")
        "EXECUTE_DEX" -> listOf("SCRIPT", "MAIN_CLASS", "ENTRANCE")
        else -> emptyList()
    }

    fun read(file: File): PatchDocument = if (file.extension.equals("txt", true)) parse(file.readText()) else
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("patch.txt") ?: throw PatchException("В корне ZIP отсутствует patch.txt")
            val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_TEXT.toLong()) }
            parse(String(bytes, Charsets.UTF_8))
        }

    fun writeZip(file: File, text: String, payloads: Map<String, File>) {
        parse(text)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("patch.txt")); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            payloads.forEach { (name, source) ->
                validatePath(name)
                if (name == "patch.txt") throw PatchException("patch.txt — зарезервированное имя")
                zip.putNextEntry(ZipEntry(name)); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    fun validatePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
            path.contains(':') || path.split('/').any { it == ".." || it == "." })
            throw PatchException("Недопустимый путь: $path")
    }

    fun child(root: File, path: String): File {
        validatePath(path)
        val f = File(root, path).canonicalFile
        if (!f.path.startsWith(root.canonicalPath + File.separator)) throw PatchException("Путь вне рабочей папки: $path")
        return f
    }

    fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            size += n; if (size > maximum) throw PatchException("Файл превышает допустимый размер")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
