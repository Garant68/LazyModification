package com.example.lazymodification

import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.FileSaver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.DexPatcher
import com.reandroid.apk.ApkModule
import com.reandroid.xml.XMLFactory
import java.io.File
import java.io.StringReader

class CloneFileBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_PATH = "com.example.lazymodification.CLONE_SELECTED_PATH"
        private const val REQUEST_MANAGE_STORAGE = 1001
        private const val TAG = "CloneFileBrowser"
    }

    private lateinit var listView: ListView
    private lateinit var currentDir: File
    private lateinit var fileList: MutableList<String>

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
        setContentView(R.layout.activity_file_browser)

        listView = findViewById(R.id.lv_files)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val rootPath = Environment.getExternalStorageDirectory().absolutePath
                if (currentDir.absolutePath != rootPath && currentDir.parentFile != null) {
                    currentDir = currentDir.parentFile!!
                    loadFiles()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (!checkStoragePermission()) {
            requestStoragePermission()
            return
        }

        initBrowser()
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (checkStoragePermission()) {
                initBrowser()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initBrowser() {
        currentDir = Environment.getExternalStorageDirectory()
        loadFiles()

        listView.setOnItemClickListener { _, _, position, _ ->
            val item = fileList[position]
            val cleanName = item.replace("📁 ", "").replace("📄 ", "")
            val file = File(currentDir, cleanName)

            if (file.isDirectory) {
                currentDir = file
                loadFiles()
            } else {
                if (file.name.endsWith(".apk", ignoreCase = true) &&
                    !file.name.endsWith(".apks", ignoreCase = true) &&
                    !file.name.endsWith(".xapk", ignoreCase = true)) {
                    showCloneDialog(file)
                } else {
                    Toast.makeText(this, getString(R.string.choose_apk_file), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCloneDialog(apkFile: File) {
        Toast.makeText(this, getString(R.string.status_reading_package), Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val apkModule = ApkModule.loadApkFile(apkFile)
                val manifest = apkModule.androidManifest
                    ?: throw Exception("AndroidManifest.xml не найден")
                val packageBlock = apkModule.tableBlock.pickOne()
                if (packageBlock != null) {
                    manifest.setPackageBlock(packageBlock)
                }
                val xmlContent = manifest.serializeToXml()
                apkModule.close()

                val match = Regex("""package="([^"]+)"""").find(xmlContent)
                val packageName = match?.groupValues?.get(1)
                    ?: throw Exception("Не удалось прочитать package")

                runOnUiThread {
                    showCloneDialogWithPackage(apkFile, packageName)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showCloneDialogWithPackage(apkFile: File, packageName: String) {
        val editText = EditText(this).apply {
            hint = getString(R.string.new_package_name)
            setText(packageName)
            setPadding(48, 32, 48, 32)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clone))
            .setMessage(getString(R.string.enter_new_package_name))
            .setView(editText)
            .setPositiveButton("OK") { dialog, _ ->
                val newPackageName = editText.text.toString().trim()
                if (newPackageName.isNotEmpty()) {
                    dialog.dismiss()
                    showSigningDialog(apkFile, newPackageName)
                } else {
                    Toast.makeText(this, getString(R.string.enter_package_name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel_upper)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showSigningDialog(apkFile: File, newPackageName: String) {
        pendingSignConfirm = { config -> cloneApk(apkFile, newPackageName, config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> cloneApk(apkFile, newPackageName, config) }
        )
    }

    private fun cloneApk(
        apkFile: File,
        newPackageName: String,
        config: SignatureConfig
    ) {
        Toast.makeText(this, getString(R.string.status_cloning), Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val apkModule = ApkModule.loadApkFile(apkFile)
                val manifest = apkModule.androidManifest
                    ?: throw Exception("AndroidManifest.xml не найден")
                val packageBlock = apkModule.tableBlock.pickOne()
                if (packageBlock != null) {
                    manifest.setPackageBlock(packageBlock)
                    packageBlock.setName(newPackageName)
                }

                var xmlContent = manifest.serializeToXml()

                val oldPackageMatch = Regex("""package="([^"]+)"""").find(xmlContent)
                val oldPackage = oldPackageMatch?.groupValues?.get(1)
                    ?: throw Exception("Не удалось прочитать package")

                xmlContent = xmlContent.replace(Regex("""android:name="(\.[^"]*)"""")) { match ->
                    val relativeName = match.groupValues[1]
                    """android:name="$newPackageName$relativeName""""
                }
                xmlContent = xmlContent.replace(Regex("""android:targetActivity="(\.[^"]*)"""")) { match ->
                    val relativeName = match.groupValues[1]
                    """android:targetActivity="$newPackageName$relativeName""""
                }
                xmlContent = xmlContent.replace(Regex("""package="[^"]*"""")) { """package="$newPackageName"""" }
                xmlContent = xmlContent.replace("\${applicationId}", newPackageName)
                xmlContent = xmlContent.replace(oldPackage, newPackageName)
                xmlContent = xmlContent.replace(Regex("""android:authorities="([^"]*)"""")) { match ->
                    val authorities = match.groupValues[1]
                    val newAuthorities = authorities.replace(oldPackage, newPackageName)
                    """android:authorities="$newAuthorities""""
                }
                xmlContent = xmlContent.replace(Regex("""android:permission="([^"]*)"""")) { match ->
                    val perm = match.groupValues[1]
                    val newPerm = perm.replace(oldPackage, newPackageName)
                    """android:permission="$newPerm""""
                }
                xmlContent = xmlContent.replace(Regex("""android:value="([^"]*)"""")) { match ->
                    val value = match.groupValues[1]
                    val newValue = value.replace(oldPackage, newPackageName)
                    """android:value="$newValue""""
                }
                xmlContent = xmlContent.replace(Regex("""\s*android:sharedUserId="[^"]*""""), "")

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

                // ✅ Патчим DEX: заменяем имя пакета в байтах
                val dexDir = File(cacheDir, "clone_dex")
                dexDir.mkdirs()
                val dexFiles = ApkUtils.extractAllDex(tempModified, dexDir)
                val patchedDexMap = HashMap<String, File>()
                var totalReplaced = 0
                for (dex in dexFiles) {
                    val patchedDex = File(cacheDir, "patched_" + dex.name)
                    totalReplaced += DexPatcher.patchPackageName(dex, patchedDex, oldPackage, newPackageName)
                    patchedDexMap[dex.name] = patchedDex
                    dex.delete()
                }
                val tempRepacked = File(cacheDir, "temp_clone_repacked.apk")
                tempRepacked.delete()
                ApkUtils.repackApkWithMultipleDex(tempModified, patchedDexMap, tempRepacked)
                tempModified.delete()

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

                val outputName = "${apkFile.nameWithoutExtension}_cloned.apk"
                FileSaver.saveApk(this, finalFile, outputName)
                finalFile.delete()

                val signInfo = if (config.noSign) getString(R.string.original_signature_preserved)
                else " V1=${config.signV1}, V2=${config.signV2}, V3=${config.signV3}${if (config.useCustom) " (своя подпись)" else ""}"

                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.cloned_success_fb, newPackageName, signInfo + "\nDEX: " + totalReplaced + " replaced", SaveFolderHelper.getLocationLabel(this)),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка клонирования", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }


    private fun loadFiles() {
        fileList = mutableListOf()
        val files = currentDir.listFiles()
        if (files != null) {
            files.sortedBy { it.name.lowercase() }.forEach { file ->
                if (file.isDirectory) {
                    if (!file.name.startsWith(".") && file.name != "Android") {
                        fileList.add("📁 ${file.name}")
                    }
                } else {
                    if (file.name.endsWith(".apk", ignoreCase = true) &&
                        !file.name.endsWith(".apks", ignoreCase = true) &&
                        !file.name.endsWith(".xapk", ignoreCase = true)) {
                        fileList.add("📄 ${file.name}")
                    }
                }
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, fileList)
        listView.adapter = adapter
    }
}
