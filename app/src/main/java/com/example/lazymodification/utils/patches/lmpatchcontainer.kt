package com.example.lazymodification.utils.patches

import org.json.JSONObject
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Внутренний контейнер Lazy Modification для патча методов.
 *
 * Снаружи это .lmpatch, внутри — manifest.json и payload.zip с правилами
 * MethodPatchGenerator. Формат не совпадает с patch.zip APK Editor: обычный
 * импорт APK Editor его не читает.
 */
object LmPatchContainer {

    const val EXTENSION = "lmpatch"
    const val MAGIC = "LMPATCH"
    const val FORMAT = "lazy-modification-method-patch"
    const val FORMAT_VERSION = 1

    private const val MANIFEST = "manifest.json"
    private const val PAYLOAD = "payload.zip"
    private const val MAX_MANIFEST = 4L * 1024 * 1024
    private const val MAX_PAYLOAD = 512L * 1024 * 1024

    /** Старые сборки сохраняли файл как .lmpatch.zip из-за MIME application/zip. */
    fun isLmPatchName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".lmpatch") || lower.endsWith(".lmpatch.zip")
    }

    fun isLmPatch(file: File): Boolean = isLmPatchName(file.name)

    data class Manifest(
        val targetPackage: String,
        val baseVersionName: String,
        val baseVersionCode: Int,
        val newVersionName: String,
        val newVersionCode: Int,
        val methodRules: Int,
        val fileRules: Int,
        val removedRules: Int
    )

    data class Loaded(val manifest: Manifest, val payload: File)

    fun write(output: File, result: MethodPatchGenerator.Result, payloadZip: File) {
        if (!payloadZip.isFile || payloadZip.length() == 0L) throw PatchException("Пустой payload.zip")
        val manifest = JSONObject().apply {
            put("magic", MAGIC)
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("producer", "Lazy Modification")
            put("targetPackage", result.packageName)
            put("baseVersionName", result.versionName)
            put("baseVersionCode", result.versionCode)
            put("newVersionName", result.newVersionName)
            put("newVersionCode", result.newVersionCode)
            put("methodRules", result.methodRules)
            put("fileRules", result.fileRules)
            put("removedRules", result.removedRules)
            put("payload", PAYLOAD)
            put("payloadSha256", fileSha256(payloadZip))
        }
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PAYLOAD))
            payloadZip.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    fun read(file: File, workDir: File): Loaded {
        if (!file.isFile || file.length() == 0L) throw PatchException("Файл .lmpatch пуст")
        val manifestText: String
        val payloadBytes: ByteArray
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST)
                ?: throw PatchException("В .lmpatch отсутствует $MANIFEST")
            if (manifestEntry.size > MAX_MANIFEST) throw PatchException("manifest.json слишком большой")
            manifestText = zip.getInputStream(manifestEntry).use {
                String(PatchFormat.readBounded(it, MAX_MANIFEST), Charsets.UTF_8)
            }
            val payloadEntry = zip.getEntry(PAYLOAD)
                ?: throw PatchException("В .lmpatch отсутствует $PAYLOAD")
            if (payloadEntry.size > MAX_PAYLOAD) throw PatchException("payload.zip слишком большой")
            payloadBytes = zip.getInputStream(payloadEntry).use { PatchFormat.readBounded(it, MAX_PAYLOAD) }
        }
        val manifest = parse(manifestText)
        workDir.mkdirs()
        val payload = File(workDir, PAYLOAD)
        payload.writeBytes(payloadBytes)
        return Loaded(manifest, payload)
    }

    fun extractPayload(file: File, workDir: File): File = read(file, workDir).payload

    /** Читает patch.txt из payload для встроенного просмотра. */
    fun readPatchText(file: File, workDir: File): String {
        val loaded = read(file, workDir)
        ZipFile(loaded.payload).use { zip ->
            val entry = zip.getEntry("patch.txt")
                ?: throw PatchException("В payload отсутствует patch.txt")
            return String(
                zip.getInputStream(entry).use { PatchFormat.readBounded(it, PatchFormat.MAX_TEXT.toLong()) },
                Charsets.UTF_8
            )
        }
    }

    fun readManifest(file: File): Manifest {
        if (!file.isFile || file.length() == 0L) throw PatchException("Файл .lmpatch пуст")
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: throw PatchException("В .lmpatch отсутствует $MANIFEST")
            if (entry.size > MAX_MANIFEST) throw PatchException("manifest.json слишком большой")
            val text = zip.getInputStream(entry).use {
                String(PatchFormat.readBounded(it, MAX_MANIFEST), Charsets.UTF_8)
            }
            return parse(text)
        }
    }

    fun parse(raw: String): Manifest {
        val json = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw PatchException("Некорректный manifest.json: ${t.message ?: "?"}")
        }
        if (json.optString("magic") != MAGIC) throw PatchException("Это не патч Lazy Modification")
        if (json.optString("format") != FORMAT) throw PatchException("Неизвестный формат патча")
        if (json.optInt("formatVersion", -1) != FORMAT_VERSION) {
            throw PatchException("Неподдерживаемая версия формата")
        }
        val pkg = json.optString("targetPackage").trim()
        if (pkg.isEmpty()) throw PatchException("В патче не указан целевой пакет")
        return Manifest(
            targetPackage = pkg,
            baseVersionName = json.optString("baseVersionName"),
            baseVersionCode = json.optInt("baseVersionCode", 0),
            newVersionName = json.optString("newVersionName"),
            newVersionCode = json.optInt("newVersionCode", 0),
            methodRules = json.optInt("methodRules", 0),
            fileRules = json.optInt("fileRules", 0),
            removedRules = json.optInt("removedRules", 0)
        )
    }

    private fun fileSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
