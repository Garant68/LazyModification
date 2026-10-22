package com.example.lazymodification.utils.patches

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Дисковый кэш разобранного APK: project/ + resources/ + хеши smali.
 * Нужен, чтобы повторный патч не распаковывал и не дизассемблировал APK заново.
 */
object ApkProjectCache {
    private const val VERSION = 2
    private const val ROOT = "apk_project_cache"
    private const val MAX_BYTES = 2L * 1024L * 1024L * 1024L

    data class Cached(
        val project: File,
        val resources: File?,
        val resourceDir: File?,
        val originalNames: List<String>,
        val dexApis: Map<String, Int>,
        val beforeSmali: Map<String, Map<String, String>>,
        val baseline: Map<String, String>
    )

    fun key(input: File): String = input.inputStream().use { inputStream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = inputStream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun load(context: Context, input: File, workDir: File): Cached? =
        load(File(context.filesDir, ROOT), input, workDir)

    fun load(root: File, input: File, workDir: File): Cached? {
        val dir = File(root, key(input))
        val meta = File(dir, "meta.txt")
        val sourceProject = File(dir, "project")
        if (!meta.isFile || !sourceProject.isDirectory) return null

        val lines = try { meta.readLines() } catch (_: Throwable) { return null }
        if (lines.firstOrNull() != "v$VERSION") return null

        val originalNames = ArrayList<String>()
        val dexApis = HashMap<String, Int>()
        val beforeSmali = HashMap<String, MutableMap<String, String>>()
        val baseline = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val p = line.split('\t')
            when (p.getOrNull(0)) {
                "O" -> p.getOrNull(1)?.let { originalNames += it }
                "D" -> {
                    val folder = p.getOrNull(1) ?: continue
                    val api = p.getOrNull(2)?.toIntOrNull() ?: continue
                    dexApis[folder] = api
                }
                "B" -> {
                    val folder = p.getOrNull(1) ?: continue
                    val path = p.getOrNull(2) ?: continue
                    val hash = p.getOrNull(3) ?: continue
                    beforeSmali.getOrPut(folder) { HashMap() }[path] = hash
                }
                "H" -> {
                    val path = p.getOrNull(1) ?: continue
                    val hash = p.getOrNull(2) ?: continue
                    baseline[path] = hash
                }
            }
        }
        if (originalNames.isEmpty()) return null

        val workProject = File(workDir, "project")
        if (workProject.exists()) workProject.deleteRecursively()
        sourceProject.copyRecursively(workProject, overwrite = true)

        val sourceResources = File(dir, "resources")
        val workResources: File?
        if (sourceResources.isDirectory) {
            workResources = File(workDir, "resources")
            if (workResources.exists()) workResources.deleteRecursively()
            sourceResources.copyRecursively(workResources, overwrite = true)
        } else workResources = null

        val resourceDir = workResources?.walkTopDown()?.firstOrNull { it.isDirectory && it.name == "res" }
        return Cached(workProject, workResources, resourceDir, originalNames, dexApis, beforeSmali, baseline)
    }

    fun save(
        context: Context,
        input: File,
        project: File,
        resources: File?,
        originalNames: Collection<String>,
        dexApis: Map<String, Int>,
        beforeSmali: Map<String, Map<String, String>>,
        baseline: Map<String, String>
    ) = save(File(context.filesDir, ROOT), input, project, resources, originalNames, dexApis, beforeSmali, baseline)

    fun save(
        root: File,
        input: File,
        project: File,
        resources: File?,
        originalNames: Collection<String>,
        dexApis: Map<String, Int>,
        beforeSmali: Map<String, Map<String, String>>,
        baseline: Map<String, String>
    ) {
        root.mkdirs()
        val dir = File(root, key(input))
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        project.copyRecursively(File(dir, "project"), overwrite = true)
        resources?.takeIf { it.isDirectory }?.copyRecursively(File(dir, "resources"), overwrite = true)

        val text = StringBuilder()
        text.append("v").append(VERSION).append('\n')
        originalNames.forEach { text.append("O\t").append(it).append('\n') }
        dexApis.forEach { (name, api) -> text.append("D\t").append(name).append('\t').append(api).append('\n') }
        beforeSmali.forEach { (folder, files) ->
            files.forEach { (path, hash) ->
                text.append("B\t").append(folder).append('\t').append(path).append('\t').append(hash).append('\n')
            }
        }
        baseline.forEach { (path, hash) ->
            text.append("H\t").append(path).append('\t').append(hash).append('\n')
        }
        File(dir, "meta.txt").writeText(text.toString())
        trim(root, dir)
    }

    fun clear(context: Context) {
        File(context.filesDir, ROOT).deleteRecursively()
    }

    fun size(context: Context): Long = size(File(context.filesDir, ROOT))

    private fun trim(root: File, keep: File) {
        val dirs = root.listFiles().orEmpty().filter { it.isDirectory }
        if (dirs.sumOf { size(it) } <= MAX_BYTES) return
        dirs.filter { it != keep }.sortedBy { it.lastModified() }.forEach { dir ->
            dir.deleteRecursively()
            if (root.listFiles().orEmpty().sumOf { size(it) } <= MAX_BYTES) return
        }
    }

    private fun size(file: File): Long = if (file.isDirectory) {
        file.listFiles().orEmpty().sumOf { size(it) }
    } else if (file.isFile) file.length() else 0L
}
