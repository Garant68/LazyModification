package com.example.lazymodification

import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.FileSaver
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.DexPatcher
import com.example.lazymodification.utils.SigningUtils
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkModule
import com.reandroid.xml.XMLFactory
import java.io.File
import java.io.StringReader

class CloneApkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.CLONE_APK_PATH"
        private const val TAG = "CloneApkActivity"
    }

    private lateinit var tvFileName: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollContent: ScrollView
    private lateinit var tvPackagePrefix: TextView
    private lateinit var etPackageSuffix: EditText
    private lateinit var btnClone: MaterialButton
    private lateinit var apkPath: String

    private var originalPackage = ""
    private var packagePrefix = ""
    private var originalSuffix = ""

    private var pendingSignConfirm: ((SignatureConfig) -> Unit)? = null
    private var pendingV1 = true
    private var pendingV2 = true
    private var pendingV3 = true

    private val keystoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val file = SigningUtils.copyKeystoreToCache(this, uri)
            if (file != null) {
                SigningUtils.showKeystorePasswordDialog(this, file, pendingV1, pendingV2, pendingV3) { config ->
                    pendingSignConfirm?.invoke(config)
                    pendingSignConfirm = null
                }
            } else {
                Toast.makeText(this, getString(R.string.err_keystore_read_failed), Toast.LENGTH_LONG).show()
                pendingSignConfirm = null
            }
        } else {
            Toast.makeText(this, getString(R.string.err_keystore_not_selected), Toast.LENGTH_SHORT).show()
            pendingSignConfirm = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_clone_apk)

        tvFileName = findViewById(R.id.tvFileName)
        progressBar = findViewById(R.id.progressBar)
        scrollContent = findViewById(R.id.scrollContent)
        tvPackagePrefix = findViewById(R.id.tvPackagePrefix)
        etPackageSuffix = findViewById(R.id.etPackageSuffix)
        btnClone = findViewById(R.id.btnClone)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: run {
            Toast.makeText(this, getString(R.string.file_not_specified), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val file = File(apkPath)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.file_not_exists), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        tvFileName.text = getString(R.string.file_label, file.name)
        btnClone.setOnClickListener { showSigningDialog() }
        loadPackageName()
    }

    private fun loadPackageName() {
        progressBar.visibility = View.VISIBLE
        scrollContent.visibility = View.GONE

        Thread {
            try {
                val apkFile = File(apkPath)
                val apkModule = ApkModule.loadApkFile(apkFile)
                val manifest = apkModule.androidManifest ?: throw Exception("AndroidManifest.xml не найден")
                val packageBlock = apkModule.tableBlock?.pickOne()
                if (packageBlock != null) {
                    manifest.setPackageBlock(packageBlock)
                }
                val xmlContent = manifest.serializeToXml()
                apkModule.close()

                val match = Regex("""package=["']([^"']+)["']""").find(xmlContent)
                val packageName = match?.groupValues?.get(1) ?: throw Exception("Не удалось прочитать package из манифеста")

                runOnUiThread {
                    originalPackage = packageName
                    if (packageName.length < 3) {
                        Toast.makeText(this, getString(R.string.package_name_too_short), Toast.LENGTH_LONG).show()
                        finish()
                        return@runOnUiThread
                    }

                    packagePrefix = packageName.dropLast(3)
                    originalSuffix = packageName.takeLast(3)

                    tvPackagePrefix.text = packagePrefix
                    etPackageSuffix.setText(originalSuffix)
                    etPackageSuffix.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            checkModified()
                        }
                    })

                    progressBar.visibility = View.GONE
                    scrollContent.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка чтения пакета", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun checkModified() {
        val currentSuffix = etPackageSuffix.text.toString()
        val isModified = currentSuffix.isNotEmpty() && currentSuffix != originalSuffix
        btnClone.isEnabled = isModified
        btnClone.visibility = if (isModified) View.VISIBLE else View.GONE
    }

    private fun showSigningDialog() {
        pendingSignConfirm = { config -> startCloning(config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> startCloning(config) }
        )
    }

    private fun startCloning(config: SignatureConfig) {
        val newSuffix = etPackageSuffix.text.toString().trim()
        if (newSuffix.isEmpty()) {
            Toast.makeText(this, getString(R.string.enter_new_chars), Toast.LENGTH_SHORT).show()
            return
        }

        val newPackage = packagePrefix + newSuffix
        btnClone.isEnabled = false
        btnClone.text = getString(R.string.status_cloning)

        Thread {
            try {
                val apkFile = File(apkPath)
                val apkModule = ApkModule.loadApkFile(apkFile)
                val manifest = apkModule.androidManifest ?: throw Exception("AndroidManifest.xml не найден")
                val packageBlock = apkModule.tableBlock?.pickOne()
                if (packageBlock != null) {
                    manifest.setPackageBlock(packageBlock)
                    packageBlock.setName(newPackage)
                }

                var xmlContent = manifest.serializeToXml()
                Log.d(TAG, "📜 Оригинальный пакет: $originalPackage")
                Log.d(TAG, "📜 Новый пакет: $newPackage")

                xmlContent = cloneManifestProperly(xmlContent, originalPackage, newPackage)
                Log.d(TAG, "✅ Манифест обработан")

                try {
                    manifest.javaClass.getMethod("clear").invoke(manifest)
                } catch (_: Exception) {
                    try { manifest.javaClass.getMethod("reset").invoke(manifest) } catch (_: Exception) {}
                }

                val parser = XMLFactory.newPullParser(StringReader(xmlContent))
                parser.setInput(StringReader(xmlContent))
                manifest.parse(parser)

                val tempModified = File(cacheDir, "temp_clone_modified.apk")
                tempModified.delete()
                apkModule.writeApk(tempModified)
                apkModule.close()
                Log.d(TAG, "✅ APK записан: ${tempModified.length()} bytes")

                // ✅ Патчим DEX: заменяем имя пакета в строках
                val dexDir = File(cacheDir, "clone_dex")
                dexDir.mkdirs()
                val dexFiles = ApkUtils.extractAllDex(tempModified, dexDir)
                val patchedDexMap = HashMap<String, File>()
                var totalReplaced = 0
                for (dex in dexFiles) {
                    val patchedDex = File(cacheDir, "patched_${dex.name}")
                    totalReplaced += DexPatcher.patchPackageName(dex, patchedDex, originalPackage, newPackage)
                    patchedDexMap[dex.name] = patchedDex
                    dex.delete()
                }
                Log.d(TAG, "✅ DEX пропатчен: $totalReplaced строк заменено")
                FileSaver.saveDebugText(this, "original=" + originalPackage + " new=" + newPackage + " dexFiles=" + dexFiles.size + " replaced=" + totalReplaced)

                val tempRepacked = File(cacheDir, "temp_clone_repacked.apk")
                tempRepacked.delete()
                ApkUtils.repackApkWithMultipleDex(tempModified, patchedDexMap, tempRepacked)
                tempModified.delete()
                Log.d(TAG, "✅ APK перепакован: ${tempRepacked.length()} bytes")

                val finalFile = if (config.noSign) {
                    // ✅ СОХРАНЯЕМ ОРИГИНАЛЬНУЮ ПОДПИСЬ
                    val preservedApk = File(cacheDir, "temp_clone_preserved.apk")
                    preservedApk.delete()
                    SigningUtils.preserveOriginalSignature(apkFile, tempRepacked, preservedApk)
                    tempRepacked.delete()
                    Log.d(TAG, getString(R.string.original_signature_preserved))
                    preservedApk
                } else {
                    val tempSigned = File(cacheDir, "temp_clone_signed.apk")
                    tempSigned.delete()
                    SigningUtils.signApk(this, tempRepacked, tempSigned, config)
                    tempRepacked.delete()
                    tempSigned
                }

                Log.d(TAG, "✅ APK готов: ${finalFile.length()} bytes")

                val baseName = apkFile.nameWithoutExtension
                val outputName = "${baseName}_cloned.apk"
                FileSaver.saveApk(this, finalFile, outputName)
                finalFile.delete()

                val signInfo = if (config.noSign) getString(R.string.original_signature_preserved)
                else "🔐 V1=${config.signV1}, V2=${config.signV2}, V3=${config.signV3}${if (config.useCustom) " (своя подпись)" else ""}"

                runOnUiThread {
                    btnClone.text = getString(R.string.clone)
                    btnClone.isEnabled = false
                    btnClone.visibility = View.GONE
                    Toast.makeText(
                        this,
                        getString(R.string.cloned_success, newPackage, signInfo + "\n DEX: " + totalReplaced + " replaced", SaveFolderHelper.getLocationLabel(this), outputName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка клонирования", e)
                FileSaver.saveDebugText(this, "CLONE ERROR: " + e.javaClass.name + ": " + e.message)
                runOnUiThread {
                    btnClone.text = getString(R.string.clone)
                    btnClone.isEnabled = true
                    btnClone.visibility = View.VISIBLE
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun cloneManifestProperly(xml: String, oldPkg: String, newPkg: String): String {
        var result = xml

        result = result.replace(Regex("""android:name=["'](\.[^"']*)["']""")) { match ->
            val relativeName = match.groupValues[1]
            """android:name="$newPkg$relativeName""""
        }
        result = result.replace(Regex("""android:targetActivity=["'](\.[^"']*)["']""")) { match ->
            val relativeName = match.groupValues[1]
            """android:targetActivity="$newPkg$relativeName""""
        }
        result = result.replace(Regex("""package=["'][^"']*["']""")) {
            """package="$newPkg""""
        }
        result = result.replace("\${applicationId}", newPkg)
        result = result.replace(oldPkg, newPkg)
        result = result.replace(Regex("""android:authorities=["']([^"']*)["']""")) { match ->
            val authorities = match.groupValues[1]
            val newAuthorities = authorities.replace(oldPkg, newPkg)
            """android:authorities="$newAuthorities""""
        }
        result = result.replace(Regex("""android:permission=["']([^"']*)["']""")) { match ->
            val perm = match.groupValues[1]
            val newPerm = perm.replace(oldPkg, newPkg)
            """android:permission="$newPerm""""
        }
        result = result.replace(Regex("""android:value=["']([^"']*)["']""")) { match ->
            val value = match.groupValues[1]
            val newValue = value.replace(oldPkg, newPkg)
            """android:value="$newValue""""
        }
        result = result.replace(Regex("""\s*android:sharedUserId=["'][^"']*["']"""), "")

        return result
    }

}
