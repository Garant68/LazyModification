package com.example.lazymodification.utils

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Снимки исходных APK и история запусков патчинга. */
object PatchHistory {
    private const val ROOT = "patch_history"

    data class Entry(
        val id: String,
        val hash: String,
        val name: String,
        val packageName: String?,
        val versionName: String?,
        val versionCode: Int?,
        val size: Long,
        val originalPath: String?,
        val createdAt: Long,
        val summary: String,
        val snapshot: File
    )

    private fun root(context: Context) = File(context.filesDir, ROOT).apply { mkdirs() }

    fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun record(
        context: Context,
        source: File,
        summary: String,
        packageName: String?,
        versionName: String?,
        versionCode: Int?
    ): Entry? {
        if (!source.isFile) return null
        val hash = hash(source)
        val dir = File(root(context), hash).apply { mkdirs() }
        val snapshot = File(dir, "source.apk")
        if (!snapshot.isFile || snapshot.length() != source.length()) {
            val temp = File(dir, "source.apk.tmp")
            source.copyTo(temp, overwrite = true)
            if (snapshot.exists()) snapshot.delete()
            if (!temp.renameTo(snapshot)) temp.copyTo(snapshot, overwrite = true)
            temp.delete()
        }
        val now = System.currentTimeMillis()
        val meta = JSONObject()
            .put("name", source.name)
            .put("packageName", packageName ?: "")
            .put("versionName", versionName ?: "")
            .put("versionCode", versionCode ?: 0)
            .put("size", source.length())
            .put("originalPath", source.absolutePath)
            .put("createdAt", now)
        File(dir, "meta.json").writeText(meta.toString())

        val runs = File(dir, "runs").apply { mkdirs() }
        val runId = now.toString()
        val run = JSONObject()
            .put("createdAt", now)
            .put("summary", summary)
        File(runs, "$runId.json").writeText(run.toString())
        return Entry(
            id = "$hash/$runId",
            hash = hash,
            name = source.name,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            size = source.length(),
            originalPath = source.absolutePath,
            createdAt = now,
            summary = summary,
            snapshot = snapshot
        )
    }

    fun list(context: Context): List<Entry> {
        val dirs = root(context).listFiles().orEmpty().filter { it.isDirectory }
        val result = ArrayList<Entry>()
        for (dir in dirs) {
            val metaFile = File(dir, "meta.json")
            val snapshot = File(dir, "source.apk")
            if (!metaFile.isFile || !snapshot.isFile) continue
            val meta = try { JSONObject(metaFile.readText()) } catch (_: Throwable) { continue }
            val runsDir = File(dir, "runs")
            val runs = runsDir.listFiles().orEmpty().filter { it.extension == "json" }
            if (runs.isEmpty()) {
                result += entry(dir, meta, snapshot, "0", meta.optLong("createdAt"), "")
            } else {
                runs.forEach { runFile ->
                    val run = try { JSONObject(runFile.readText()) } catch (_: Throwable) { return@forEach }
                    result += entry(dir, meta, snapshot, runFile.nameWithoutExtension, run.optLong("createdAt"), run.optString("summary"))
                }
            }
        }
        return result.sortedByDescending { it.createdAt }.take(100)
    }

    private fun entry(dir: File, meta: JSONObject, snapshot: File, runId: String, createdAt: Long, summary: String): Entry {
        return Entry(
            id = "${dir.name}/$runId",
            hash = dir.name,
            name = meta.optString("name", snapshot.name),
            packageName = meta.optString("packageName").ifBlank { null },
            versionName = meta.optString("versionName").ifBlank { null },
            versionCode = meta.optInt("versionCode", 0).takeIf { it > 0 },
            size = meta.optLong("size", snapshot.length()),
            originalPath = meta.optString("originalPath").ifBlank { null },
            createdAt = createdAt,
            summary = summary,
            snapshot = snapshot
        )
    }

    fun restore(entry: Entry, target: File): Boolean {
        if (!entry.snapshot.isFile) return false
        entry.snapshot.copyTo(target, overwrite = true)
        return target.isFile && hash(target) == entry.hash
    }

    fun delete(context: Context, entry: Entry): Boolean {
        val dir = File(root(context), entry.hash)
        val runFile = File(File(dir, "runs"), "${entry.id.substringAfterLast('/')}.json")
        runFile.delete()
        if (File(dir, "runs").listFiles().orEmpty().none { it.extension == "json" }) {
            dir.deleteRecursively()
        }
        return true
    }

    fun clear(context: Context) {
        root(context).deleteRecursively()
    }
}
