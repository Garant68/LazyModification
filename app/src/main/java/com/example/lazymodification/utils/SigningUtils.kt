package com.example.lazymodification.utils

import com.example.lazymodification.R
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.Security
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SignatureConfig(
    val noSign: Boolean = false,
    val signV1: Boolean = true,
    val signV2: Boolean = true,
    val signV3: Boolean = true,
    val customKeystorePath: String? = null,
    val keystorePassword: String = "",
    val keyAlias: String = "",
    val keyPassword: String = "",
    val keystoreType: String = "PKCS12"
) {
    val useCustom: Boolean get() = !customKeystorePath.isNullOrEmpty()
}

object SigningUtils {

    private const val PREFS = "signing_prefs"
    private const val KEY_V1 = "sign_v1"
    private const val KEY_V2 = "sign_v2"
    private const val KEY_V3 = "sign_v3"
    private const val KEY_NO_SIGN = "no_sign"
    private const val KEY_USE_CUSTOM = "use_custom"
    private const val KEY_KEYSTORE_PATH = "keystore_path"
    private const val KEY_KEYSTORE_PASSWORD = "keystore_password"
    private const val KEY_KEY_ALIAS = "key_alias"
    private const val KEY_KEY_PASSWORD = "key_password"
    private const val KEY_KEYSTORE_TYPE = "keystore_type"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveConfig(context: Context, config: SignatureConfig) {
        prefs(context).edit().apply {
            putBoolean(KEY_V1, config.signV1)
            putBoolean(KEY_V2, config.signV2)
            putBoolean(KEY_V3, config.signV3)
            putBoolean(KEY_NO_SIGN, config.noSign)
            putBoolean(KEY_USE_CUSTOM, config.useCustom)
            if (config.useCustom) {
                putString(KEY_KEYSTORE_PATH, config.customKeystorePath)
                putString(KEY_KEYSTORE_PASSWORD, config.keystorePassword)
                putString(KEY_KEY_ALIAS, config.keyAlias)
                putString(KEY_KEY_PASSWORD, config.keyPassword)
                putString(KEY_KEYSTORE_TYPE, config.keystoreType)
            }
            apply()
        }
    }

    fun loadSavedConfig(context: Context): SignatureConfig? {
        val p = prefs(context)
        if (!p.contains(KEY_NO_SIGN) && !p.contains(KEY_V1)) return null
        val useCustom = p.getBoolean(KEY_USE_CUSTOM, false)
        val path = p.getString(KEY_KEYSTORE_PATH, null)
        val customPath = if (useCustom && path != null && File(path).exists()) path else null
        return SignatureConfig(
            noSign = p.getBoolean(KEY_NO_SIGN, false),
            signV1 = p.getBoolean(KEY_V1, true),
            signV2 = p.getBoolean(KEY_V2, true),
            signV3 = p.getBoolean(KEY_V3, true),
            customKeystorePath = customPath,
            keystorePassword = p.getString(KEY_KEYSTORE_PASSWORD, "") ?: "",
            keyAlias = p.getString(KEY_KEY_ALIAS, "") ?: "",
            keyPassword = p.getString(KEY_KEY_PASSWORD, "") ?: "",
            keystoreType = p.getString(KEY_KEYSTORE_TYPE, "PKCS12") ?: "PKCS12"
        )
    }

    fun resolveSigningConfig(
        activity: AppCompatActivity,
        onCustomSelected: (v1: Boolean, v2: Boolean, v3: Boolean) -> Unit,
        onConfirm: (SignatureConfig) -> Unit
    ) {
        val saved = loadSavedConfig(activity)
        if (saved != null) {
            onConfirm(saved)
        } else {
            showSigningDialog(activity, onCustomSelected, onConfirm)
        }
    }

    private fun ensureBouncyCastle() {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            }
        } catch (_: Throwable) {}
    }

    /** Свой экземпляр BouncyCastle: на Android имя "BC" занято системным провайдером. */
    private val bcProvider: Provider by lazy { org.bouncycastle.jce.provider.BouncyCastleProvider() }

    /**
     * Открывает хранилище ключей: сначала системный провайдер,
     * при неудаче — собственная поддержка JKS (Android) или BC (BKS и т.п.).
     */
    fun openKeyStore(type: String): KeyStore {
        return try {
            KeyStore.getInstance(type)
        } catch (t: Throwable) {
            if (type.equals("JKS", ignoreCase = true)) {
                JksSupport.openJks()
            } else {
                KeyStore.getInstance(type, bcProvider)
            }
        }
    }

    /** Проверяет наличие V1-подписи (META-INF/MANIFEST.MF) в APK. */
    fun hasV1Signature(apk: File): Boolean {
        return try {
            ZipFile(apk).use { zip -> zip.getEntry("META-INF/MANIFEST.MF") != null }
        } catch (t: Throwable) {
            false
        }
    }

    /** Проверяет наличие ДАННЫХ оригинальной подписи: v1-файлы или APK Signing Block. */
    fun hasSignatureData(apk: File): Boolean {
        return try {
            if (hasV1Signature(apk)) return true
            extractSigningBlock(apk) != null
        } catch (t: Throwable) {
            false
        }
    }

    private fun loadKeyStoreAuto(file: File, password: CharArray, preferredType: String): Pair<KeyStore, String> {
        ensureBouncyCastle()
        val candidates = listOf(preferredType, "PKCS12", "JKS", "BKS").distinct()
        var lastError: Exception? = null
        for (type in candidates) {
            try {
                val ks = openKeyStore(type)
                FileInputStream(file).use { ks.load(it, password) }
                return ks to type
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("Не удалось загрузить keystore")
    }

    // ============================================================
    // ✅ СОХРАНЕНИЕ ОРИГИНАЛЬНОЙ ПОДПИСИ — ПОТОКОВОЕ, БЕЗ OOM
    // ============================================================
    fun preserveOriginalSignature(originalApk: File, processedApk: File, outputApk: File) {
        val buffer = ByteArray(64 * 1024)

        // 1) Собираем только ИМЕНА META-INF записей (не содержимое!)
        val metaInfNames = mutableListOf<String>()
        ZipFile(originalApk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("META-INF/") }
                .forEach { metaInfNames.add(it.name) }
        }

        if (metaInfNames.isEmpty()) {
            processedApk.copyTo(outputApk, overwrite = true)
            copySigningBlock(originalApk, outputApk)
            return
        }

        // 2) Потоковая запись: processed APK (без META-INF) + оригинальный META-INF
        ZipOutputStream(FileOutputStream(outputApk).buffered()).use { zos ->
            zos.setLevel(6)

            // Копируем все записи из обработанного APK, КРОМЕ META-INF
            ZipInputStream(FileInputStream(processedApk).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.name.startsWith("META-INF/")) {
                        val newEntry = ZipEntry(entry.name)
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = entry.size
                            newEntry.compressedSize = entry.compressedSize
                            newEntry.crc = entry.crc
                        } else {
                            newEntry.method = ZipEntry.DEFLATED
                        }
                        zos.putNextEntry(newEntry)
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            zos.write(buffer, 0, len)
                        }
                        zos.closeEntry()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Добавляем META-INF из оригинального APK (по одному файлу)
            ZipFile(originalApk).use { zip ->
                for (metaName in metaInfNames) {
                    val zipEntry = zip.getEntry(metaName) ?: continue
                    val newEntry = ZipEntry(metaName)
                    newEntry.method = ZipEntry.DEFLATED
                    zos.putNextEntry(newEntry)
                    zip.getInputStream(zipEntry).use { input ->
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }

        // Перенос APK Signing Block (данные оригинальной подписи)
        copySigningBlock(originalApk, outputApk)
    }

    /** Переносит APK Signing Block из оригинала в результат (ошибки игнорируются). */
    private fun copySigningBlock(originalApk: File, outputApk: File) {
        try {
            val block = extractSigningBlock(originalApk) ?: return
            insertSigningBlockIntoApk(outputApk, block)
        } catch (t: Throwable) {
            // не критично: результат останется без данных подписи
        }
    }

    // ============================================================
    // ✅ ДАННЫЕ ОРИГИНАЛЬНОЙ ПОДПИСИ (APK SIGNING BLOCK)
    // ============================================================

    private const val EOCD_SIG_LONG = 0x06054b50L
    private const val ZIP64_LOCATOR_SIG_LONG = 0x07064b50L
    private const val ZIP64_EOCD_SIG_LONG = 0x06064b50L

    private fun readU32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun readU64LE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }

    /** Находит смещение начала Central Directory в zip/apk (или -1). */
    private fun findCentralDirOffset(file: File): Long {
        val len = file.length()
        if (len < 22) return -1
        val tailSize = minOf(len, 65557L).toInt()
        val tail = ByteArray(tailSize)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(len - tailSize)
            raf.readFully(tail)
        }
        for (i in tail.size - 22 downTo 0) {
            if (readU32LE(tail, i) != EOCD_SIG_LONG) continue
            val commentLen = (tail[i + 20].toInt() and 0xFF) or ((tail[i + 21].toInt() and 0xFF) shl 8)
            val eocdAbs = (len - tailSize) + i
            if (eocdAbs + 22 + commentLen != len) continue
            var cdOffset = readU32LE(tail, i + 16)
            if (cdOffset == 0xFFFFFFFFL) {
                // ZIP64: читаем локатор и EOCD64
                val locAbs = eocdAbs - 20
                if (locAbs >= 0) {
                    try {
                        RandomAccessFile(file, "r").use { raf ->
                            val loc = ByteArray(20)
                            raf.seek(locAbs)
                            raf.readFully(loc)
                            if (readU32LE(loc, 0) == ZIP64_LOCATOR_SIG_LONG) {
                                val eocd64Pos = readU64LE(loc, 8)
                                val hdr = ByteArray(56)
                                raf.seek(eocd64Pos)
                                raf.readFully(hdr)
                                if (readU32LE(hdr, 0) == ZIP64_EOCD_SIG_LONG) {
                                    cdOffset = readU64LE(hdr, 48)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // оставим cdOffset как есть
                    }
                }
            }
            return cdOffset
        }
        return -1
    }

    /** Извлекает APK Signing Block из файла или возвращает null. */
    fun extractSigningBlock(file: File): ByteArray? {
        return try {
            val cdStart = findCentralDirOffset(file)
            if (cdStart < 32 || cdStart > file.length()) return null
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(16)
                raf.seek(cdStart - 16)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "APK Sig Block 42") return null
                val sizeBuf = ByteArray(8)
                raf.seek(cdStart - 24)
                raf.readFully(sizeBuf)
                val size2 = readU64LE(sizeBuf, 0)
                if (size2 < 24 || size2 > cdStart - 8) return null
                val blockStart = cdStart - 8 - size2
                raf.seek(blockStart)
                raf.readFully(sizeBuf)
                if (readU64LE(sizeBuf, 0) != size2) return null
                val block = ByteArray(size2.toInt() + 8)
                raf.seek(blockStart)
                raf.readFully(block)
                block
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Вставляет APK Signing Block перед Central Directory. false — при любой проблеме (файл не изменяется). */
    fun insertSigningBlockIntoApk(apk: File, block: ByteArray): Boolean {
        if (block.isEmpty()) return false
        val parent = apk.parentFile ?: return false
        val tmpFile = File(parent, apk.name + ".blk.tmp")
        try {
            val cdStart = findCentralDirOffset(apk)
            if (cdStart <= 0 || cdStart > apk.length()) return false
            val origCount = try { ZipFile(apk).use { it.size() } } catch (t: Throwable) { return false }

            FileInputStream(apk).use { fis ->
                FileOutputStream(tmpFile).use { fos ->
                    val buf = ByteArray(1 shl 20)
                    var remaining = cdStart
                    while (remaining > 0) {
                        val n = fis.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) return false
                        fos.write(buf, 0, n)
                        remaining -= n
                    }
                    fos.write(block)
                    var n = fis.read(buf)
                    while (n >= 0) {
                        if (n > 0) fos.write(buf, 0, n)
                        n = fis.read(buf)
                    }
                }
            }

            if (!patchCentralDirOffset(tmpFile, block.size.toLong(), cdStart)) {
                tmpFile.delete()
                return false
            }
            val back = extractSigningBlock(tmpFile)
            if (back == null || !back.contentEquals(block)) {
                tmpFile.delete()
                return false
            }
            val newCount = try { ZipFile(tmpFile).use { it.size() } } catch (t: Throwable) { -1 }
            if (newCount != origCount) {
                tmpFile.delete()
                return false
            }

            val bak = File(parent, apk.name + ".blk.bak")
            if (bak.exists()) bak.delete()
            if (!apk.renameTo(bak)) {
                tmpFile.delete()
                return false
            }
            if (!tmpFile.renameTo(apk)) {
                bak.renameTo(apk)
                tmpFile.delete()
                return false
            }
            bak.delete()
            return true
        } catch (t: Throwable) {
            if (tmpFile.exists()) tmpFile.delete()
            return false
        }
    }

    /** Сдвигает в файле смещение Central Directory на delta (EOCD и, при наличии, ZIP64-записи). */
    private fun patchCentralDirOffset(file: File, delta: Long, expectedOldCd: Long): Boolean {
        return try {
            val len = file.length()
            val tailSize = minOf(len, 65557L).toInt()
            val tail = ByteArray(tailSize)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - tailSize)
                raf.readFully(tail)
            }
            for (i in tail.size - 22 downTo 0) {
                if (readU32LE(tail, i) != EOCD_SIG_LONG) continue
                val commentLen = (tail[i + 20].toInt() and 0xFF) or ((tail[i + 21].toInt() and 0xFF) shl 8)
                val eocdAbs = (len - tailSize) + i
                if (eocdAbs + 22 + commentLen != len) continue
                RandomAccessFile(file, "rw").use { raf ->
                    val cdField = readU32LE(tail, i + 16)
                    if (cdField != 0xFFFFFFFFL) {
                        if (cdField != expectedOldCd) return false
                        val newCd = cdField + delta
                        val b4 = ByteArray(4) { idx -> ((newCd ushr (8 * idx)) and 0xFF).toByte() }
                        raf.seek(eocdAbs + 16)
                        raf.write(b4)
                    } else {
                        val locAbs = eocdAbs - 20
                        if (locAbs < 0) return false
                        val loc = ByteArray(20)
                        raf.seek(locAbs)
                        raf.readFully(loc)
                        if (readU32LE(loc, 0) != ZIP64_LOCATOR_SIG_LONG) return false
                        val eocd64Old = readU64LE(loc, 8)
                        val eocd64New = eocd64Old + delta
                        val hdr = ByteArray(56)
                        raf.seek(eocd64New)
                        raf.readFully(hdr)
                        if (readU32LE(hdr, 0) != ZIP64_EOCD_SIG_LONG) return false
                        val cd64 = readU64LE(hdr, 48)
                        if (cd64 != expectedOldCd) return false
                        val newCd = cd64 + delta
                        val b8 = ByteArray(8) { idx -> ((newCd ushr (8 * idx)) and 0xFF).toByte() }
                        raf.seek(eocd64New + 48)
                        raf.write(b8)
                        val lb8 = ByteArray(8) { idx -> (eocd64New ushr (8 * idx) and 0xFF).toByte() }
                        raf.seek(locAbs + 8)
                        raf.write(lb8)
                    }
                }
                return true
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    // ============================================================
    // ДИАЛОГ ВЫБОРА ВАРИАНТОВ ПОДПИСИ
    // ============================================================
    fun showSigningDialog(
        activity: AppCompatActivity,
        onCustomSelected: (v1: Boolean, v2: Boolean, v3: Boolean) -> Unit,
        onConfirm: (SignatureConfig) -> Unit
    ) {
        val saved = loadSavedConfig(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val checkBoxV1 = CheckBox(activity).apply {
            text = activity.getString(R.string.signing_v1_label); isChecked = saved?.signV1 ?: true; textSize = 16f; setPadding(0, 16, 0, 16)
        }
        val checkBoxV2 = CheckBox(activity).apply {
            text = activity.getString(R.string.signing_v2_label); isChecked = saved?.signV2 ?: true; textSize = 16f; setPadding(0, 16, 0, 16)
        }
        val checkBoxV3 = CheckBox(activity).apply {
            text = activity.getString(R.string.signing_v3_label); isChecked = saved?.signV3 ?: true; textSize = 16f; setPadding(0, 16, 0, 16)
        }
        val checkBoxNoSign = CheckBox(activity).apply {
            text = activity.getString(R.string.signing_no_sign)
            isChecked = saved?.noSign ?: false; textSize = 16f; setPadding(0, 16, 0, 16)
        }
        val checkBoxCustom = CheckBox(activity).apply {
            text = activity.getString(R.string.signing_custom_keystore); isChecked = saved?.useCustom == true; textSize = 16f; setPadding(0, 16, 0, 16)
        }

        checkBoxNoSign.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkBoxV1.isChecked = false
                checkBoxV2.isChecked = false
                checkBoxV3.isChecked = false
                checkBoxCustom.isChecked = false
            }
        }

        val onVChecked = android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkBoxNoSign.isChecked = false
        }
        checkBoxV1.setOnCheckedChangeListener(onVChecked)
        checkBoxV2.setOnCheckedChangeListener(onVChecked)
        checkBoxV3.setOnCheckedChangeListener(onVChecked)
        checkBoxCustom.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkBoxNoSign.isChecked = false
        }

        container.addView(checkBoxV1)
        container.addView(checkBoxV2)
        container.addView(checkBoxV3)
        container.addView(checkBoxNoSign)
        container.addView(checkBoxCustom)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.signing_options_title))
            .setMessage(activity.getString(R.string.signing_options_message))
            .setView(container)
            .setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
                val noSign = checkBoxNoSign.isChecked
                val v1 = checkBoxV1.isChecked
                val v2 = checkBoxV2.isChecked
                val v3 = checkBoxV3.isChecked
                val custom = checkBoxCustom.isChecked

                if (noSign) {
                    val config = SignatureConfig(noSign = true, signV1 = false, signV2 = false, signV3 = false)
                    saveConfig(activity, config)
                    onConfirm(config)
                    return@setPositiveButton
                }

                if (!v1 && !v2 && !v3 && !custom) {
                    Toast.makeText(activity, activity.getString(R.string.signing_choose_scheme), Toast.LENGTH_SHORT).show()
                    showSigningDialog(activity, onCustomSelected, onConfirm)
                    return@setPositiveButton
                }

                if (custom) {
                    val savedCustom = loadSavedConfig(activity)
                    if (savedCustom != null && savedCustom.useCustom) {
                        showUseSavedKeystoreDialog(activity, savedCustom, v1, v2, v3, onCustomSelected, onConfirm)
                    } else {
                        onCustomSelected(v1, v2, v3)
                    }
                } else {
                    val config = SignatureConfig(signV1 = v1, signV2 = v2, signV3 = v3)
                    saveConfig(activity, config)
                    onConfirm(config)
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun showUseSavedKeystoreDialog(
        activity: AppCompatActivity,
        saved: SignatureConfig,
        v1: Boolean, v2: Boolean, v3: Boolean,
        onCustomSelected: (v1: Boolean, v2: Boolean, v3: Boolean) -> Unit,
        onConfirm: (SignatureConfig) -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.signing_custom_keystore))
            .setMessage(
                activity.getString(R.string.use_saved_keystore_question) +
                        activity.getString(R.string.file_with_newline, File(saved.customKeystorePath!!).name) +
                        activity.getString(R.string.alias_with_newline, saved.keyAlias) +
                        activity.getString(R.string.type_label, saved.keystoreType)
            )
            .setPositiveButton(activity.getString(R.string.use)) { _, _ ->
                val config = saved.copy(signV1 = v1, signV2 = v2, signV3 = v3)
                saveConfig(activity, config)
                onConfirm(config)
            }
            .setNeutralButton(activity.getString(R.string.choose_another)) { _, _ ->
                onCustomSelected(v1, v2, v3)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    fun copyKeystoreToCache(context: Context, uri: Uri): File? {
        return try {
            // Отдельная папка для копии хранилища ключей: чистим старые копии
            val dir = File(context.filesDir, "keystore").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            // легаси: старые копии под фиксированным именем
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith("custom_keystore.") }
                ?.forEach { it.delete() }

            // Сохраняем ИСХОДНОЕ имя файла — оно и показывается в диалоге подписи
            val originalName = getFileNameFromUri(context, uri) ?: "custom_keystore.p12"
            val ext = originalName.substringAfterLast('.', "p12").lowercase()
            val base = originalName.substringBeforeLast('.').ifBlank { "custom_keystore" }
            val safeBase = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "custom_keystore" }
            val storeFile = File(dir, "$safeBase.$ext")
            if (storeFile.exists()) storeFile.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                storeFile.outputStream().use { output -> input.copyTo(output) }
            }
            storeFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment
    }

    fun showKeystorePasswordDialog(
        activity: AppCompatActivity,
        keystoreFile: File,
        signV1: Boolean, signV2: Boolean, signV3: Boolean,
        onConfirm: (SignatureConfig) -> Unit
    ) {
        val saved = loadSavedConfig(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val typeLabel = TextView(activity).apply {
            text = activity.getString(R.string.keystore_type_label); textSize = 14f; setPadding(0, 8, 0, 4)
        }
        val radioGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val rbPkcs12 = RadioButton(activity).apply {
            id = View.generateViewId(); text = activity.getString(R.string.keystore_type_pkcs12); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val rbJks = RadioButton(activity).apply {
            id = View.generateViewId(); text = activity.getString(R.string.keystore_type_jks); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        radioGroup.addView(rbPkcs12)
        radioGroup.addView(rbJks)
        if (saved?.keystoreType == "JKS") radioGroup.check(rbJks.id) else radioGroup.check(rbPkcs12.id)

        val etPassword = EditText(activity).apply {
            hint = activity.getString(R.string.keystore_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(16, 16, 16, 16); setText(saved?.keystorePassword ?: "")
        }
        val etAlias = EditText(activity).apply {
            hint = activity.getString(R.string.key_alias_hint); inputType = InputType.TYPE_CLASS_TEXT
            setPadding(16, 16, 16, 16); setText(saved?.keyAlias ?: "")
        }
        val etKeyPassword = EditText(activity).apply {
            hint = activity.getString(R.string.key_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(16, 16, 16, 16); setText(saved?.keyPassword ?: "")
        }

        container.addView(typeLabel)
        container.addView(radioGroup)
        container.addView(etPassword)
        container.addView(etAlias)
        container.addView(etKeyPassword)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.signing_custom_keystore))
            .setMessage(activity.getString(R.string.keystore_file_message, keystoreFile.name))
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(activity.getString(R.string.sign_confirm)) { _, _ ->
                val selectedType = if (rbJks.isChecked) "JKS" else "PKCS12"
                val password = etPassword.text.toString()
                val alias = etAlias.text.toString().trim()
                val keyPassword = etKeyPassword.text.toString().ifEmpty { password }

                if (password.isEmpty() || alias.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.signing_password_alias_empty), Toast.LENGTH_SHORT).show()
                    showKeystorePasswordDialog(activity, keystoreFile, signV1, signV2, signV3, onConfirm)
                    return@setPositiveButton
                }

                try {
                    val (ks, detectedType) = loadKeyStoreAuto(keystoreFile, password.toCharArray(), selectedType)
                    if (detectedType != selectedType) {
                        Toast.makeText(activity, activity.getString(R.string.keystore_type_auto, detectedType), Toast.LENGTH_SHORT).show()
                    }
                    if (!ks.containsAlias(alias)) {
                        val aliases = ks.aliases().toList().joinToString(", ").ifEmpty { activity.getString(R.string.none) }
                        Toast.makeText(activity, activity.getString(R.string.keystore_alias_not_found, alias, aliases), Toast.LENGTH_LONG).show()
                        showKeystorePasswordDialog(activity, keystoreFile, signV1, signV2, signV3, onConfirm)
                        return@setPositiveButton
                    }
                    ks.getKey(alias, keyPassword.toCharArray())

                    val config = SignatureConfig(
                        signV1 = signV1, signV2 = signV2, signV3 = signV3,
                        customKeystorePath = keystoreFile.absolutePath,
                        keystorePassword = password, keyAlias = alias,
                        keyPassword = keyPassword, keystoreType = detectedType
                    )
                    saveConfig(activity, config)
                    onConfirm(config)
                } catch (e: Exception) {
                    Toast.makeText(activity, activity.getString(R.string.keystore_error, e.message), Toast.LENGTH_LONG).show()
                    showKeystorePasswordDialog(activity, keystoreFile, signV1, signV2, signV3, onConfirm)
                    return@setPositiveButton
                }
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    fun signApk(context: Context, input: File, output: File, config: SignatureConfig, originalApk: File? = null) {
        if (!input.exists() || input.length() == 0L) throw Exception("APK invalid")

        if (config.noSign) {
            val source = originalApk ?: input
            if (source.exists() && source != output) {
                preserveOriginalSignature(source, input, output)
            } else {
                input.copyTo(output, overwrite = true)
            }
            return
        }

        if (!config.signV1 && !config.signV2 && !config.signV3) {
            throw Exception("Не выбрана ни одна схема подписи")
        }

        val keyStore: KeyStore
        val alias: String
        val keyPassword: CharArray

        if (config.useCustom) {
            ensureBouncyCastle()
            keyStore = openKeyStore(config.keystoreType)
            FileInputStream(File(config.customKeystorePath!!)).use {
                keyStore.load(it, config.keystorePassword.toCharArray())
            }
            alias = config.keyAlias
            keyPassword = config.keyPassword.toCharArray()
        } else {
            keyStore = KeyStore.getInstance("PKCS12")
            context.assets.open("testkey.p12").use {
                keyStore.load(it, "android".toCharArray())
            }
            alias = "androiddebugkey"
            keyPassword = "android".toCharArray()
        }

        val key = keyStore.getKey(alias, keyPassword) as PrivateKey
        val certs = keyStore.getCertificateChain(alias).map { it as X509Certificate }

        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("cert", key, certs).build()))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(config.signV1)
            .setV2SigningEnabled(config.signV2)
            .setV3SigningEnabled(config.signV3)
            .setMinSdkVersion(21)
            .build()
            .sign()
    }
}