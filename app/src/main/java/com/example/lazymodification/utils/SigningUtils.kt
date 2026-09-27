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
import java.security.KeyStore
import java.security.PrivateKey
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

    private fun loadKeyStoreAuto(file: File, password: CharArray, preferredType: String): Pair<KeyStore, String> {
        ensureBouncyCastle()
        val candidates = listOf(preferredType, "PKCS12", "JKS", "BKS").distinct()
        var lastError: Exception? = null
        for (type in candidates) {
            try {
                val ks = KeyStore.getInstance(type)
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
            context.filesDir.listFiles()
                ?.filter { it.name.startsWith("custom_keystore.") }
                ?.forEach { it.delete() }
            val fileName = getFileNameFromUri(context, uri) ?: "custom_keystore"
            val ext = fileName.substringAfterLast('.', "p12").lowercase()
            val storeFile = File(context.filesDir, "custom_keystore.$ext")
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
            keyStore = KeyStore.getInstance(config.keystoreType)
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