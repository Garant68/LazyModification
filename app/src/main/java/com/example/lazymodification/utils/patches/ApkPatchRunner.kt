package com.example.lazymodification.utils.patches

import android.content.Context
import com.example.lazymodification.utils.FastZipRepack
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import dalvik.system.DexClassLoader
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile as DexFileIface
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Bridges the format interpreter to DEX/AXML and the existing align/sign/save pipeline. */
object ApkPatchRunner {
    private val dexNameRegex = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
    private const val SMALL_CHANGED_SET = 300
    private const val CHANGED_CHUNK_SIZE = 500

    fun apply(context: Context?, input: File, archive: File, output: File, directory: File,
              log: (String) -> Unit = {}, progress: (Float, String) -> Unit = { _, _ -> }): PatchStats {
        directory.mkdirs()
        val isLmPatch = LmPatchContainer.isLmPatch(archive)
        val patchArchive = if (archive.extension.equals("txt", true)) File(directory, "imported.zip").also {
            PatchFormat.writeZip(it, archive.readText(), emptyMap())
        } else if (isLmPatch) {
            LmPatchContainer.extractPayload(archive, File(directory, "lmpatch_payload"))
        } else archive
        val document = PatchFormat.read(patchArchive)
        val requiredMethodRules = if (isLmPatch) {
            document.rules.count { it.type == "MATCH_REPLACE" }
        } else 0
        // Для method-патча цели уже записаны как smali/...; разбираем только
        // эти классы, а не все dex выбранного APK.
        val selectiveSmali = if (document.needsSmali) selectiveSmaliTargets(document) else null
        val methodOnly = isLmPatch && selectiveSmali != null && !document.needsResources &&
            document.rules.isNotEmpty() &&
            document.rules.all { it.type in setOf("MATCH_REPLACE", "MATCH_ASSIGN", "MATCH_GOTO") }
        var project = File(directory, "project")
        val originalNames = linkedSetOf<String>()
        // Для точечного .lmpatch полный кэш smali только вредит: его копирование
        // и повторный хеш занимают больше времени, чем разбор двух классов.
        val cached = if (context != null && document.needsSmali && selectiveSmali == null) {
            try { ApkProjectCache.load(context, input, directory) } catch (_: Throwable) { null }
        } else null
        val useCache = cached != null &&
            (!document.needsResources || cached.resourceDir != null) &&
            (!document.needsSmali || (cached.beforeSmali.isNotEmpty() && File(cached.project, "smali").isDirectory))
        if (useCache) {
            project = cached!!.project
            originalNames.addAll(cached.originalNames)
            log("Использую кэш разобранного APK — повторная распаковка не нужна")
        } else {
            if (cached != null) {
                project.deleteRecursively()
                File(directory, "resources").deleteRecursively()
            }
            project = File(directory, "project").apply { mkdirs() }
            ZipFile(input).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (!originalNames.add(entry.name)) throw PatchException("Повтор ZIP-записи ${entry.name}")
                    // Для чистого method-патча ресурсы и assets не нужны:
                    // FastZipRepack возьмёт их прямо из исходного APK.
                    if (methodOnly && !dexNameRegex.matches(entry.name)) continue
                    val file = PatchFormat.child(project, entry.name)
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { src -> file.outputStream().use { src.copyTo(it) } }
                }
            }
        }
        val module = ApkModule.loadApkFile(input)
        var resourceProject: File? = if (useCache) cached!!.resources else null
        var resourceDirectory: File? = if (useCache) cached!!.resourceDir else null
        val decodeSmali = document.needsSmali
        var sentinelVersion: String? = null
        var versionCodeString = ""
        val metadata: PatchMetadata
        try {
            val manifest = module.androidManifest
            val pkg = manifest.packageName ?: throw PatchException("Не найдено имя пакета")
            versionCodeString = manifest.versionCode?.toString().orEmpty()
            fun qualify(name: String?): String = when {
                name.isNullOrEmpty() -> ""
                name.startsWith('.') -> pkg + name
                '.' !in name -> "$pkg.$name"
                else -> name
            }
            val activities = mutableListOf<String>()
            val iterator = manifest.getActivities(true)
            while (iterator.hasNext()) {
                val element = iterator.next()
                val name = element.searchAttributeByResourceId(0x01010003)?.valueAsString
                qualify(name).takeIf { it.isNotEmpty() }?.let { activities += it }
            }
            metadata = PatchMetadata(pkg, qualify(manifest.applicationClassName), activities,
                listOf(qualify(manifest.mainActivityClassName)).filter { it.isNotEmpty() })
            if (document.needsResources && !useCache) {
                progress(0.05f, "XML / ресурсы")
                val decoded = File(directory, "resources")
                val decoder = ApkModuleXmlDecoder(module)
                decoder.decode(decoded)
                val roots = File(decoded, "resources").walkTopDown().filter { it.isDirectory && it.name == "res" }.toList()
                if (roots.size != 1) throw PatchException("Патч XML требует APK с одним пакетом ресурсов")
                resourceProject = decoded
                resourceDirectory = roots.single()
                File(project, "res").deleteRecursively()
                resourceDirectory.copyRecursively(File(project, "res"), overwrite = true)
                File(decoded, "AndroidManifest.xml").takeIf { it.isFile }?.copyTo(File(project, "AndroidManifest.xml"), overwrite = true)
            }
        } finally { module.close() }

        val dexApis = mutableMapOf<String, Int>()
        val beforeSmali = mutableMapOf<String, Map<String, String>>()
        if (useCache) {
            dexApis.putAll(cached!!.dexApis)
            beforeSmali.putAll(cached.beforeSmali)
        } else if (decodeSmali) {
            val dexFiles = project.listFiles().orEmpty().filter { dexNameRegex.matches(it.name) }.sortedBy { it.name }
            for ((index, dex) in dexFiles.withIndex()) {
                progress(0.08f + 0.22f * index / dexFiles.size.coerceAtLeast(1), dex.name)
                val name = if (dex.name == "classes.dex") "smali" else "smali_" + dex.nameWithoutExtension
                val dir = File(project, name).apply { mkdirs() }
                val file = DexFileFactory.loadDexFile(dex, null)
                dexApis[name] = file.opcodes.api
                val options = BaksmaliOptions().apply { apiLevel = file.opcodes.api; debugInfo = false; parameterRegisters = true }
                val selected = if (selectiveSmali == null) null else selectedClasses(selectiveSmali, name, file)
                if (selected != null && selected.isEmpty()) {
                    dir.deleteRecursively()
                    dexApis.remove(name)
                    continue
                }
                val source = if (selected == null) file else object : DexFileIface {
                    override fun getClasses(): Set<ClassDef> = LinkedHashSet(selected)
                    override fun getOpcodes() = file.opcodes
                }
                if (!Baksmali.disassembleDexFile(source, dir, 2, options)) throw PatchException("Не удалось разобрать ${dex.name}")
                beforeSmali[name] = hashes(dir, skipSmali = false)
            }
        }
        val baseline = when {
            useCache -> cached!!.baseline
            methodOnly -> emptyMap()
            else -> hashes(project, skipSmali = true)
        }
        if (!useCache && context != null && document.needsSmali && selectiveSmali == null) {
            try {
                ApkProjectCache.save(context, input, project, resourceProject, originalNames, dexApis, beforeSmali, baseline)
                log("Кэш разобранного APK сохранён — следующий патч будет быстрее")
            } catch (_: Throwable) {
                // Кэш не критичен: патч продолжит работу без него.
            }
        }
        if (decodeSmali && versionCodeString.length in 1..3 && document.rules.any { it.type == "EXECUTE_DEX" }) {
            // DEX-скрипт ищет versionCode по всему smali. Короткий "52" встречается
            // в тысячах несвязанных констант; подменяем его уникальным значением
            // только в рабочей копии манифеста, чтобы скрипт менял реальные строки версии.
            sentinelVersion = "2147483647"
            log("SMALI: versionCode временно заменён на $sentinelVersion, чтобы не переписать случайные константы")
            val manifestFile = File(project, "AndroidManifest.xml")
            if (manifestFile.isFile) {
                val text = manifestFile.readText()
                val old = Regex("(android:versionCode\\s*=\\s*\")" + Regex.escape(versionCodeString) + "(\")")
                manifestFile.writeText(text.replace(old, "$1$sentinelVersion$2"))
            }
        }
        val stats = PatchEngine(project, metadata, log) { rule, zip, root ->
            val ctx = context ?: throw PatchException("DEX-скрипт требует Android")
            if (rule.value("INTERFACE_VERSION").ifBlank { "1" } != "1") throw PatchException("Поддерживается INTERFACE_VERSION: 1")
            val script = File(directory, "script_${UUID.randomUUID()}.dex")
            script.writeBytes(PatchEngine.payload(zip, rule.value("SCRIPT").trim()))
            // Android 14+ requires dynamically loaded code to be read-only.
            if (!script.setReadOnly()) throw PatchException("Не удалось защитить файл DEX-скрипта от записи")
            try {
                val optimized = File(directory, "optimized").apply { mkdirs() }
                val loader = DexClassLoader(script.path, optimized.path, null, ctx.classLoader)
                val type = loader.loadClass(rule.value("MAIN_CLASS").trim())
                val method = type.getMethod(rule.value("ENTRANCE").trim(), String::class.java, String::class.java, String::class.java, String::class.java)
                val instance = if (Modifier.isStatic(method.modifiers)) null else type.getDeclaredConstructor().newInstance()
                method.invoke(instance, input.absolutePath, patchArchive.absolutePath, root.absolutePath, rule.value("PARAM"))
            } finally { script.delete() }
        }.apply(document, patchArchive) { done, total ->
            progress(0.3f + 0.4f * done / total.coerceAtLeast(1), "${done.coerceAtMost(total)}/$total")
        }
        val methodMissTolerance = if (requiredMethodRules >= 1000) 10 else 0
        if (requiredMethodRules > 0 && stats.matches < requiredMethodRules - methodMissTolerance) {
            if (stats.unmatchedRules.isNotEmpty()) {
                log("Не совпали правила: " + stats.unmatchedRules.take(5).joinToString(", "))
            }
            throw PatchException(
                "Не все методы совпали со старой версией: " +
                    "${stats.matches} из $requiredMethodRules. Патч не применён."
            )
        }
        val methodMisses = (requiredMethodRules - stats.matches).coerceAtLeast(0)
        if (methodMisses in 1..methodMissTolerance) {
            log("Патч применён с предупреждением: не совпало $methodMisses из $requiredMethodRules method-правил")
        }
        val afterRules = if (methodOnly) emptyMap() else hashes(project, skipSmali = true)
        stats.changedPaths += (baseline.keys + afterRules.keys).filter { baseline[it] != afterRules[it] }
        val folders = project.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("smali(?:_classes[0-9]+)?")) }.sortedBy { it.name }
        for ((index, dir) in folders.withIndex()) {
            val beforeHashes = beforeSmali[dir.name] ?: emptyMap()
            val afterHashes = hashes(dir, skipSmali = false)
            val changedPaths = (beforeHashes.keys + afterHashes.keys).filter { beforeHashes[it] != afterHashes[it] }
            if (changedPaths.isEmpty()) continue

            val dexName = if (dir.name == "smali") "classes.dex" else dir.name.removePrefix("smali_") + ".dex"
            val dest = File(directory, "built_$dexName")
            if (dir.walkTopDown().none { it.isFile && it.extension == "smali" }) {
                File(project, dexName).delete(); stats.changedPaths += dexName; continue
            }

            // Reassemble only changed classes and merge them into the original DEX.
            // A full smali->dex rebuild of a large APK needs hundreds of MB and can OOM.
            val changedDir = File(directory, "changed_${dir.name}").apply {
                deleteRecursively()
                mkdirs()
            }
            val removedTypes = linkedSetOf<String>()
            var changedFiles = 0
            changedPaths.forEach { path ->
                val source = File(dir, path)
                if (source.isFile && source.extension == "smali") {
                    val target = File(changedDir, path)
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    changedFiles++
                } else if (path.endsWith(".smali")) {
                    removedTypes += "L" + path.removeSuffix(".smali") + ";"
                }
            }

            if (changedFiles > SMALL_CHANGED_SET) {
                // A whole-file .lmpatch can replace thousands of classes at once.
                // Assembling them into one DEX overflows the 16-bit method index
                // (Unsigned short value out of range: 65561).  Keep the original
                // DEX for unchanged classes and put the replacements into small
                // additional classesN.dex files instead.
                rebuildLargeChangedFolder(
                    project, directory, dir, dexName, changedDir, removedTypes,
                    dexApis[dir.name] ?: dexApis["smali"] ?: 23, stats, log
                )
                changedDir.deleteRecursively()
                continue
            }

            val changedDex = File(directory, "changed_${dir.name}.dex")
            val changedClasses = if (changedFiles > 0) {
                val options = SmaliOptions().apply {
                    apiLevel = dexApis[dir.name] ?: dexApis["smali"] ?: 23
                    jobs = 1; verboseErrors = false; outputDexFile = changedDex.absolutePath
                }
                progress(0.72f + 0.15f * index / folders.size.coerceAtLeast(1), dexName)
                if (!Smali.assemble(options, changedDir.absolutePath))
                    throw PatchException("Ошибка сборки $dexName. Патч создал некорректный smali; исходный APK сохранён.")
                DexFileFactory.loadDexFile(changedDex, null).classes.toList()
            } else emptyList()

            val originalFile = File(project, dexName)
            if (originalFile.isFile) {
                val original = DexFileFactory.loadDexFile(originalFile, null)
                val originalTypes = original.classes.map { it.type }.toSet()
                val replacements = changedClasses.associateBy { it.type }
                val merged = object : DexFileIface {
                    override fun getClasses(): Set<ClassDef> {
                        val result = linkedSetOf<ClassDef>()
                        original.classes.forEach { cls ->
                            if (cls.type !in removedTypes) result += replacements[cls.type] ?: cls
                        }
                        changedClasses.forEach { cls -> if (cls.type !in originalTypes) result += cls }
                        return result
                    }
                    override fun getOpcodes() = original.opcodes
                }
                dest.delete()
                DexPool.writeTo(dest.absolutePath, merged)
            } else if (changedClasses.isNotEmpty()) {
                changedDex.copyTo(dest, overwrite = true)
            } else {
                File(project, dexName).delete(); stats.changedPaths += dexName
                changedDir.deleteRecursively(); changedDex.delete()
                continue
            }

            DexFileFactory.loadDexFile(dest, null) // Fail before producing an output APK if DEX is invalid.
            dest.copyTo(File(project, dexName), overwrite = true)
            stats.changedPaths += dexName
            changedDir.deleteRecursively()
            changedDex.delete()
        }
        // A patch can remove an entire smali directory.
        beforeSmali.keys.filter { !File(project, it).exists() }.forEach {
            val dexName = if (it == "smali") "classes.dex" else it.removePrefix("smali_") + ".dex"
            File(project, dexName).delete(); stats.changedPaths += dexName
        }
        if (resourceProject != null && stats.changedPaths.any { it == "AndroidManifest.xml" || it.startsWith("res/") }) {
            progress(0.9f, "Сборка XML")
            val resDir = resourceDirectory!!
            resDir.deleteRecursively()
            File(project, "res").takeIf { it.exists() }?.copyRecursively(resDir, overwrite = true)
            File(project, "AndroidManifest.xml").copyTo(File(resourceProject, "AndroidManifest.xml"), overwrite = true)
            val encoder = ApkModuleXmlEncoder()
            val rebuilt = File(directory, "resources.apk")
            try {
                encoder.buildResources(resourceProject)
                encoder.apkModule.writeApk(rebuilt)
            } finally { encoder.apkModule.close() }
            File(project, "res").deleteRecursively()
            ZipFile(rebuilt).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !(entry.name.startsWith("res/") || entry.name in listOf("AndroidManifest.xml", "resources.arsc"))) continue
                    val dest = PatchFormat.child(project, entry.name)
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { source -> dest.outputStream().use { source.copyTo(it) } }
                }
            }
        } else if (resourceProject != null) {
            // XML decoding by itself must not change a resource or the original manifest.
            ZipFile(input).use { zip ->
                File(project, "res").deleteRecursively()
                zip.entries().asSequence().filter { !it.isDirectory && (it.name.startsWith("res/") || it.name == "AndroidManifest.xml") }.forEach { entry ->
                    val dest = PatchFormat.child(project, entry.name); dest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { source -> dest.outputStream().use { source.copyTo(it) } }
                }
            }
        }
        val replacements = linkedMapOf<String, File>()
        val removed = linkedSetOf<String>()
        val finalPaths = if (methodOnly) emptyMap() else hashes(project, skipSmali = true)
        ZipFile(input).use { zip ->
            originalNames.forEach { name ->
                if (isSignature(name)) { removed += name; return@forEach }
                if (methodOnly) return@forEach
                val file = File(project, name)
                if (!file.isFile) removed += name
                else {
                    val oldHash = zip.getInputStream(zip.getEntry(name)).use { hashStream(it) }
                    if (finalPaths[name] != oldHash) replacements[name] = file
                }
            }
        }
        if (methodOnly) {
            stats.changedPaths.filter { it.endsWith(".dex") }.forEach { name ->
                File(project, name).takeIf { it.isFile }?.let { replacements[name] = it }
            }
        } else {
            finalPaths.keys.filter { it !in originalNames && !isSignature(it) }.forEach { replacements[it] = File(project, it) }
        }
        if (replacements.isEmpty() && removed.all { isSignature(it) }) {
            input.copyTo(output, overwrite = true)
            stats.changedPaths.clear()
            return stats
        }
        progress(0.98f, "Перепаковка")
        // Чистим служебные файлы (kotlin/, DebugProbesKt.bin и мусор в корне)
        // так же, как это делает основной путь патчинга PatchService.
        FastZipRepack.repack(input, replacements, output, applyClean = true, remove = removed)
        return stats
    }

    private fun rebuildLargeChangedFolder(
        project: File,
        directory: File,
        sourceDir: File,
        dexName: String,
        changedDir: File,
        removedTypes: Set<String>,
        apiLevel: Int,
        stats: PatchStats,
        log: (String) -> Unit
    ) {
        val changedFiles = changedDir.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .map { it.relativeTo(changedDir).invariantSeparatorsPath }
            .sorted()
            .toList()
        if (changedFiles.isEmpty()) return

        val parts = assembleChangedDexParts(
            changedDir, File(directory, "parts_${sourceDir.name}"), changedFiles, apiLevel
        )
        val changedTypes = linkedSetOf<String>()
        parts.forEach { part ->
            DexFileFactory.loadDexFile(part, null).classes.mapTo(changedTypes) { it.type }
        }
        val replacedTypes = removedTypes + changedTypes
        val originalFile = File(project, dexName)
        val original = if (originalFile.isFile) DexFileFactory.loadDexFile(originalFile, null) else null
        val keptClasses = original?.classes?.filter { it.type !in replacedTypes }.orEmpty()

        if (keptClasses.isEmpty()) {
            originalFile.delete()
        } else if (original != null) {
            val filtered = object : DexFileIface {
                override fun getClasses(): Set<ClassDef> = LinkedHashSet(keptClasses)
                override fun getOpcodes() = original.opcodes
            }
            val dest = File(directory, "built_$dexName")
            dest.delete()
            DexPool.writeTo(dest.absolutePath, filtered)
            DexFileFactory.loadDexFile(dest, null)
            dest.copyTo(originalFile, overwrite = true)
        }
        stats.changedPaths += dexName

        val usedNames = linkedSetOf<String>()
        var preferredName: String? = if (!originalFile.exists() && !File(project, dexName).exists()) dexName else null
        parts.forEachIndexed { index, part ->
            val name = preferredName?.also { preferredName = null } ?: nextDexName(project, usedNames)
            part.copyTo(File(project, name), overwrite = true)
            usedNames += name
            stats.changedPaths += name
            if (index == 0) {
                log("Крупный набор изменённых классов разбит на ${parts.size} DEX-файлов")
            }
        }
        parts.forEach { it.delete() }
        File(directory, "parts_${sourceDir.name}").deleteRecursively()
    }

    private fun assembleChangedDexParts(
        changedDir: File,
        outputDir: File,
        relativeFiles: List<String>,
        apiLevel: Int
    ): List<File> {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val result = mutableListOf<File>()
        var nextPart = 0

        fun tryAssemble(group: List<String>, label: String): List<File> {
            if (group.isEmpty()) return emptyList()
            val partDir = File(outputDir, "src_$label").apply { mkdirs() }
            group.forEach { relative ->
                val target = File(partDir, relative)
                target.parentFile?.mkdirs()
                File(changedDir, relative).copyTo(target, overwrite = true)
            }
            val dex = File(outputDir, "part_$label.dex")
            dex.delete()
            val assembled = try {
                val options = SmaliOptions().apply {
                    this.apiLevel = apiLevel
                    jobs = 1
                    verboseErrors = false
                    outputDexFile = dex.absolutePath
                }
                Smali.assemble(options, partDir.absolutePath) &&
                    DexFileFactory.loadDexFile(dex, null).classes.isNotEmpty()
            } catch (_: Throwable) {
                false
            }
            partDir.deleteRecursively()
            if (assembled) return listOf(dex)
            dex.delete()
            if (group.size <= 1) {
                throw PatchException("Не удалось собрать DEX для ${group.firstOrNull()}")
            }
            val middle = group.size / 2
            return tryAssemble(group.subList(0, middle), "${label}a") +
                tryAssemble(group.subList(middle, group.size), "${label}b")
        }

        var start = 0
        while (start < relativeFiles.size) {
            val end = (start + CHANGED_CHUNK_SIZE).coerceAtMost(relativeFiles.size)
            val label = (nextPart++).toString()
            result += tryAssemble(relativeFiles.subList(start, end), label)
            start = end
        }
        return result
    }

    private fun nextDexName(project: File, used: MutableSet<String>): String {
        var number = 2
        while (true) {
            val name = "classes$number.dex"
            if (name !in used && !File(project, name).exists()) {
                used += name
                return name
            }
            number++
        }
    }

    private fun isSignature(name: String): Boolean = name == "stamp-cert-sha256" || name == "stamp-cert-hash" ||
        (name.startsWith("META-INF/") && '/' !in name.removePrefix("META-INF/") &&
            (name == "META-INF/MANIFEST.MF" || name.uppercase().endsWith(".SF") || name.uppercase().endsWith(".RSA") || name.uppercase().endsWith(".DSA") || name.uppercase().endsWith(".EC")))
    /**
     * Возвращает точные smali-файлы из MATCH/REMOVE-правил. null означает,
     * что безопасно разобрать весь dex (широкие glob/скрипты/DEX-merge).
     */
    private fun selectiveSmaliTargets(document: PatchDocument): Set<String>? {
        if (document.rules.any { it.type == "EXECUTE_DEX" || it.type == "MERGE" }) return null
        val result = linkedSetOf<String>()
        for (rule in document.rules) {
            if (rule.type !in setOf("MATCH_REPLACE", "MATCH_ASSIGN", "MATCH_GOTO", "REMOVE_FILES", "ADD_FILES")) continue
            val raw = rule.value("TARGET").trim()
            if (raw.isEmpty()) continue
            for (line in raw.lineSequence()) {
                val target = line.trim().trimStart('/')
                if (target.isEmpty() || target.startsWith("#") || !target.startsWith("smali")) continue
                if (target.any { it == '*' || it == '?' } || '[' in target || "\${" in target) return null
                if (rule.type in setOf("REMOVE_FILES", "ADD_FILES") && !target.endsWith(".smali")) return null
                if (!target.endsWith(".smali")) return null
                result += target
            }
        }
        return result.ifEmpty { null }
    }

    private fun selectedClasses(
        paths: Set<String>,
        folder: String,
        dex: DexFileIface
    ): Set<ClassDef> {
        val prefix = "$folder/"
        val types = paths.asSequence()
            .filter { it.startsWith(prefix) && it.endsWith(".smali") }
            .map { "L" + it.removePrefix(prefix).removeSuffix(".smali") + ";" }
            .toSet()
        if (types.isEmpty()) return emptySet()
        return dex.classes.filter { it.type in types }.toSet()
    }

    private fun hashes(root: File, skipSmali: Boolean): Map<String, String> = root.walkTopDown().onEnter {
        !skipSmali || it == root || !it.name.matches(Regex("smali(?:_classes[0-9]+)?"))
    }.filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.inputStream().use(::hashStream) }
    private fun hashStream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
