package com.example.lazymodification.utils.patches

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class LmPatchMethod(
    val owner: String,
    val name: String,
    val descriptor: String,
    val oldHash: String,
    val newHash: String
) {
    val key: MethodPatchSupport.MethodKey get() = MethodPatchSupport.MethodKey(owner, name, descriptor)
}

data class LmPatchDocument(
    val targetPackage: String,
    val baseVersionName: String,
    val baseVersionCode: Int,
    val newVersionName: String,
    val newVersionCode: Int,
    val baseApkSha256: String,
    val newApkSha256: String,
    val methods: List<LmPatchMethod>
)

data class LmPatchLoaded(
    val document: LmPatchDocument,
    val payload: File
)

/**
 * Собственный формат Lazy Modification. Это не patch.zip APK Editor:
 * внутри нет patch.txt, а снаружи его читает только LmPatchEngine.
 */
object LmPatchFormat {

    const val EXTENSION = "lmpatch"
    const val FORMAT = "lazy-modification-method-patch"
    const val MAGIC = "LMPATCH"
    const val FORMAT_VERSION = 1

    private const val MANIFEST = "manifest.json"
    private const val PAYLOAD = "payload.dex"
    private const val MAX_MANIFEST = 4L * 1024 * 1024
    private const val MAX_PAYLOAD = 256L * 1024 * 1024

    fun read(file: File, workDir: File): LmPatchLoaded {
        if (!file.isFile || file.length() == 0L) throw PatchException("Файл патча пуст")
        val manifestBytes: ByteArray
        val payloadBytes: ByteArray
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST)
                ?: throw PatchException("В .lmpatch отсутствует $MANIFEST")
            if (manifestEntry.size > MAX_MANIFEST) throw PatchException("manifest.json слишком большой")
            manifestBytes = zip.getInputStream(manifestEntry).use { PatchFormat.readBounded(it, MAX_MANIFEST) }
            val payloadEntry = zip.getEntry(PAYLOAD)
                ?: throw PatchException("В .lmpatch отсутствует $PAYLOAD")
            if (payloadEntry.size > MAX_PAYLOAD) throw PatchException("payload.dex слишком большой")
            payloadBytes = zip.getInputStream(payloadEntry).use { PatchFormat.readBounded(it, MAX_PAYLOAD) }
        }
        val document = parseManifest(String(manifestBytes, Charsets.UTF_8))
        if (document.methods.isEmpty()) throw PatchException("В патче нет методов")
        if (document.baseApkSha256.isNotBlank() && !document.baseApkSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw PatchException("Некорректный SHA-256 базового APK")
        }
        workDir.mkdirs()
        val payload = File(workDir, PAYLOAD)
        payload.writeBytes(payloadBytes)
        if (!payload.isFile || payload.length() == 0L) throw PatchException("Не удалось извлечь payload.dex")
        return LmPatchLoaded(document, payload)
    }

    fun readDocument(file: File): LmPatchDocument {
        if (!file.isFile || file.length() == 0L) throw PatchException("Файл патча пуст")
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: throw PatchException("В .lmpatch отсутствует $MANIFEST")
            if (entry.size > MAX_MANIFEST) throw PatchException("manifest.json слишком большой")
            val bytes = zip.getInputStream(entry).use { PatchFormat.readBounded(it, MAX_MANIFEST) }
            return parseManifest(String(bytes, Charsets.UTF_8))
        }
    }

    fun write(file: File, document: LmPatchDocument, payload: File) {
        if (!payload.isFile || payload.length() == 0L) throw PatchException("Пустой payload.dex")
        val manifest = JSONObject().apply {
            put("magic", MAGIC)
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("producer", "Lazy Modification")
            put("payload", PAYLOAD)
            put("targetPackage", document.targetPackage)
            put("baseVersionName", document.baseVersionName)
            put("baseVersionCode", document.baseVersionCode)
            put("newVersionName", document.newVersionName)
            put("newVersionCode", document.newVersionCode)
            put("baseApkSha256", document.baseApkSha256)
            put("newApkSha256", document.newApkSha256)
            put("payloadSha256", MethodPatchSupport.fileSha256(payload))
            put("createdAt", System.currentTimeMillis())
            put("methods", JSONArray().apply {
                document.methods.forEach { method ->
                    put(JSONObject().apply {
                        put("class", method.owner)
                        put("name", method.name)
                        put("descriptor", method.descriptor)
                        put("oldHash", method.oldHash)
                        put("newHash", method.newHash)
                    })
                }
            })
        }
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PAYLOAD))
            payload.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun parseManifest(raw: String): LmPatchDocument {
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
        val packageName = json.optString("targetPackage").trim()
        if (packageName.isEmpty()) throw PatchException("В патче не указан целевой пакет")
        val array = json.optJSONArray("methods") ?: throw PatchException("В патче нет списка методов")
        if (array.length() == 0) throw PatchException("В патче нет методов")
        val methods = ArrayList<LmPatchMethod>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val owner = item.optString("class").trim()
            val name = item.optString("name").trim()
            val descriptor = item.optString("descriptor").trim()
            val oldHash = item.optString("oldHash").trim()
            val newHash = item.optString("newHash").trim()
            if (owner.isEmpty() || name.isEmpty() || descriptor.isEmpty() || oldHash.isEmpty()) {
                throw PatchException("Некорректная запись метода #${i + 1}")
            }
            methods += LmPatchMethod(owner, name, descriptor, oldHash, newHash)
        }
        return LmPatchDocument(
            targetPackage = packageName,
            baseVersionName = json.optString("baseVersionName"),
            baseVersionCode = json.optInt("baseVersionCode", 0),
            newVersionName = json.optString("newVersionName"),
            newVersionCode = json.optInt("newVersionCode", 0),
            baseApkSha256 = json.optString("baseApkSha256"),
            newApkSha256 = json.optString("newApkSha256"),
            methods = methods
        )
    }
}
