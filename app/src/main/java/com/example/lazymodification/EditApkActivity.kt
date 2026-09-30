package com.example.lazymodification

import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.FileSaver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.IconReplacer
import com.example.lazymodification.utils.SigningUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.reandroid.apk.ApkModule
import com.reandroid.xml.XMLFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EditApkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.EDIT_APK_PATH"
        private const val TAG = "EditApkActivity"
    }

    private lateinit var btnSave: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollContent: ScrollView
    private lateinit var ivAppIcon: ImageView
    private lateinit var tvAppName: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvVersionCode: TextView
    private lateinit var tvMinSdk: TextView
    private lateinit var tvTargetSdk: TextView
    private lateinit var cbRemoveInternet: CheckBox
    private lateinit var cbDisableDirectBoot: CheckBox
    private lateinit var cbDisableBootCompleted: CheckBox
    private lateinit var cbFixGoogleMaps: CheckBox
    private lateinit var cbConvertAtv: CheckBox
    private lateinit var apkPath: String

    private var selectedBannerPath: String? = null
    private var selectedIconPath: String? = null
    private var currentAppName = ""
    private var currentVersion = ""
    private var currentVersionCode = ""
    private var currentMinSdk = ""
    private var currentTargetSdk = ""
    private var isRemoveInternet = false
    private var isDisableDirectBoot = false
    private var isDisableBootCompleted = false
    private var isFixGoogleMaps = false
    private var originalAppName = ""
    private var originalVersion = ""
    private var originalVersionCode = ""
    private var originalMinSdk = ""
    private var originalTargetSdk = ""
    private var isModified = false

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

    private val pickBannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null && path.endsWith(".png", ignoreCase = true)) {
                selectedBannerPath = path
                cbConvertAtv.isChecked = true
                checkModified()
                Toast.makeText(this, getString(R.string.icon_selected), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.choose_png_file), Toast.LENGTH_SHORT).show()
                cbConvertAtv.isChecked = false
                selectedBannerPath = null
                checkModified()
            }
        } else {
            cbConvertAtv.isChecked = false
            selectedBannerPath = null
            checkModified()
        }
    }

    // Тап по иконке: выбираем новую картинку (как в ApkEditor / APKTool M)
    private val pickIconLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            val lower = path?.lowercase() ?: ""
            if (path != null && (lower.endsWith(".png") || lower.endsWith(".webp") ||
                        lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                selectedIconPath = path
                try {
                    val opts = BitmapFactory.Options()
                    opts.inSampleSize = 4
                    ivAppIcon.setImageBitmap(BitmapFactory.decodeFile(path, opts))
                } catch (_: Exception) { }
                checkModified()
                Toast.makeText(this, getString(R.string.icon_selected), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.choose_png_file), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_edit_apk)

        btnSave = findViewById(R.id.btnSave)
        progressBar = findViewById(R.id.progressBar)
        scrollContent = findViewById(R.id.scrollContent)
        ivAppIcon = findViewById(R.id.ivAppIcon)
        tvAppName = findViewById(R.id.tvAppName)
        tvVersion = findViewById(R.id.tvVersion)
        tvVersionCode = findViewById(R.id.tvVersionCode)
        tvMinSdk = findViewById(R.id.tvMinSdk)
        tvTargetSdk = findViewById(R.id.tvTargetSdk)
        cbRemoveInternet = findViewById(R.id.cbRemoveInternet)
        cbDisableDirectBoot = findViewById(R.id.cbDisableDirectBoot)
        cbDisableBootCompleted = findViewById(R.id.cbDisableBootCompleted)
        cbFixGoogleMaps = findViewById(R.id.cbFixGoogleMaps)
        cbConvertAtv = findViewById(R.id.cbConvertAtv)

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

        btnSave.setOnClickListener { showSigningDialog() }

        ivAppIcon.setOnClickListener {
            pickIconLauncher.launch(
                Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_IMAGE)
                }
            )
        }

        cbRemoveInternet.setOnCheckedChangeListener { _, isChecked ->
            isRemoveInternet = isChecked; checkModified()
        }
        cbDisableDirectBoot.setOnCheckedChangeListener { _, isChecked ->
            isDisableDirectBoot = isChecked; checkModified()
        }
        cbDisableBootCompleted.setOnCheckedChangeListener { _, isChecked ->
            isDisableBootCompleted = isChecked; checkModified()
        }
        cbFixGoogleMaps.setOnCheckedChangeListener { _, isChecked ->
            isFixGoogleMaps = isChecked; checkModified()
        }
        cbConvertAtv.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedBannerPath = null
                pickBannerLauncher.launch(
                    Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_IMAGE)
                    }
                )
            } else {
                selectedBannerPath = null
                checkModified()
            }
        }

        setupEditClicks()
        loadApkInfo()
    }

    private fun setupEditClicks() {
        findViewById<MaterialCardView>(R.id.cardAppName).setOnClickListener {
            showEditDialog(getString(R.string.name), currentAppName, InputType.TYPE_CLASS_TEXT) { newValue ->
                currentAppName = newValue; tvAppName.text = newValue; checkModified()
            }
        }
        findViewById<MaterialCardView>(R.id.cardVersion).setOnClickListener {
            showEditDialog(getString(R.string.version), currentVersion, InputType.TYPE_CLASS_TEXT) { newValue ->
                currentVersion = newValue; tvVersion.text = newValue; checkModified()
            }
        }
        findViewById<MaterialCardView>(R.id.cardVersionCode).setOnClickListener {
            showEditDialog(getString(R.string.build_number), currentVersionCode, InputType.TYPE_CLASS_NUMBER) { newValue ->
                currentVersionCode = newValue; tvVersionCode.text = newValue; checkModified()
            }
        }
        findViewById<MaterialCardView>(R.id.cardMinSdk).setOnClickListener {
            showAndroidVersionDialog(getString(R.string.min_sdk_version), currentMinSdk) { newApi ->
                currentMinSdk = newApi
                tvMinSdk.text = "Android ${getAndroidVersionName(newApi)} (API $newApi)"
                checkModified()
            }
        }
        findViewById<MaterialCardView>(R.id.cardTargetSdk).setOnClickListener {
            showAndroidVersionDialog(getString(R.string.target_sdk_version), currentTargetSdk) { newApi ->
                currentTargetSdk = newApi
                tvTargetSdk.text = "Android ${getAndroidVersionName(newApi)} (API $newApi)"
                checkModified()
            }
        }
    }

    private fun showAndroidVersionDialog(title: String, currentApi: String, onSelected: (String) -> Unit) {
        val displayNames = arrayOf(
            "Android 5.0 Lollipop (API 21)", "Android 5.1 Lollipop (API 22)",
            "Android 6.0 Marshmallow (API 23)", "Android 7.0 Nougat (API 24)",
            "Android 7.1 Nougat (API 25)", "Android 8.0 Oreo (API 26)",
            "Android 8.1 Oreo (API 27)", "Android 9 Pie (API 28)",
            "Android 10 (API 29)", "Android 11 (API 30)",
            "Android 12 (API 31)", "Android 12L (API 32)",
            "Android 13 (API 33)", "Android 14 (API 34)",
            "Android 15 (API 35)", "Android 16 (API 36)"
        )
        val apiValues = arrayOf("21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36")
        val currentIndex = apiValues.indexOf(currentApi).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(displayNames, currentIndex) { dialog, which ->
                onSelected(apiValues[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditDialog(title: String, currentValue: String, inputType: Int, onConfirm: (String) -> Unit) {
        val editText = EditText(this).apply {
            setText(currentValue)
            this.inputType = inputType
            setPadding(48, 32, 48, 32)
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_title, title))
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newValue = editText.text.toString().trim()
                if (newValue.isNotEmpty()) onConfirm(newValue)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun checkModified() {
        isModified = currentAppName != originalAppName ||
                currentVersion != originalVersion ||
                currentVersionCode != originalVersionCode ||
                currentMinSdk != originalMinSdk ||
                currentTargetSdk != originalTargetSdk ||
                isRemoveInternet || isDisableDirectBoot || isDisableBootCompleted ||
                isFixGoogleMaps || cbConvertAtv.isChecked || selectedIconPath != null
        btnSave.isEnabled = isModified
        btnSave.visibility = if (isModified) View.VISIBLE else View.GONE
    }

    private fun loadApkInfo() {
        progressBar.visibility = View.VISIBLE
        scrollContent.visibility = View.GONE

        Thread {
            try {
                val pm = packageManager
                val packageInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA)

                if (packageInfo == null) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this, getString(R.string.apk_read_failed), Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@Thread
                }

                packageInfo.applicationInfo.sourceDir = apkPath
                packageInfo.applicationInfo.publicSourceDir = apkPath

                val appName = try { packageInfo.applicationInfo.loadLabel(pm).toString() } catch (e: Exception) { File(apkPath).nameWithoutExtension }
                val versionName = packageInfo.versionName ?: "1.0"
                val versionCode = packageInfo.longVersionCode.toString()
                val minSdk = packageInfo.applicationInfo.minSdkVersion.toString()
                val targetSdk = packageInfo.applicationInfo.targetSdkVersion.toString()
                val iconDrawable = try { packageInfo.applicationInfo.loadIcon(pm) } catch (e: Exception) { null }

                originalAppName = appName; originalVersion = versionName; originalVersionCode = versionCode
                originalMinSdk = minSdk; originalTargetSdk = targetSdk
                currentAppName = appName; currentVersion = versionName; currentVersionCode = versionCode
                currentMinSdk = minSdk; currentTargetSdk = targetSdk

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    scrollContent.visibility = View.VISIBLE

                    if (iconDrawable != null) ivAppIcon.setImageDrawable(iconDrawable)
                    else ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)

                    tvAppName.text = appName; tvVersion.text = versionName; tvVersionCode.text = versionCode
                    tvMinSdk.text = "Android ${getAndroidVersionName(minSdk)} (API $minSdk)"
                    tvTargetSdk.text = "Android ${getAndroidVersionName(targetSdk)} (API $targetSdk)"

                    cbRemoveInternet.isChecked = false; isRemoveInternet = false
                    cbDisableDirectBoot.isChecked = false; isDisableDirectBoot = false
                    cbDisableBootCompleted.isChecked = false; isDisableBootCompleted = false
                    cbFixGoogleMaps.isChecked = false; isFixGoogleMaps = false
                    cbConvertAtv.isChecked = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка чтения APK", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun showSigningDialog() {
        pendingSignConfirm = { config -> saveChanges(config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> saveChanges(config) }
        )
    }

    private fun saveChanges(config: SignatureConfig) {
        btnSave.isEnabled = false
        btnSave.text = getString(R.string.status_saving)

        Thread {
            try {
                val apkFile = File(apkPath)
                val apkModule = ApkModule.loadApkFile(apkFile)
                val manifest = apkModule.androidManifest ?: throw Exception("AndroidManifest.xml не найден")
                val packageBlockForManifest = apkModule.tableBlock.pickOne()
                if (packageBlockForManifest != null) manifest.setPackageBlock(packageBlockForManifest)

                var xmlContent = manifest.serializeToXml()

                if (currentVersion != originalVersion) {
                    xmlContent = xmlContent.replace(Regex("""android:versionName=["'][^"']*["']"""), """android:versionName="$currentVersion"""")
                }
                if (currentVersionCode != originalVersionCode) {
                    xmlContent = xmlContent.replace(Regex("""android:versionCode=["'][^"']*["']"""), """android:versionCode="$currentVersionCode"""")
                }
                if (currentMinSdk != originalMinSdk) {
                    xmlContent = xmlContent.replace(Regex("""android:minSdkVersion=["'][^"']*["']"""), """android:minSdkVersion="$currentMinSdk"""")
                }
                if (currentTargetSdk != originalTargetSdk) {
                    xmlContent = xmlContent.replace(Regex("""android:targetSdkVersion=["'][^"']*["']"""), """android:targetSdkVersion="$currentTargetSdk"""")
                }
                if (currentAppName != originalAppName) {
                    xmlContent = xmlContent.replace(Regex("""(<application[^>]*?)android:label=["'][^"']*["']"""), """$1android:label="$currentAppName"""")
                }
                if (isRemoveInternet) {
                    xmlContent = xmlContent.replace("android.permission.INTERNET", "disabled_android.permission.INTERNET")
                }
                if (isDisableDirectBoot) {
                    val original = xmlContent
                    xmlContent = xmlContent.replace(
                        Regex("""android:directBootAware=["']true["']""", RegexOption.IGNORE_CASE),
                        """android:directBootAware="false""""
                    )
                    if (xmlContent != original) Log.d(TAG, "✅ Direct Boot отключён")
                    else Log.d(TAG, "ℹ️ Атрибут directBootAware не найден")
                }
                if (isDisableBootCompleted) {
                    val original = xmlContent
                    xmlContent = xmlContent.replace(
                        Regex("""<uses-permission\s+android:name=["']android\.permission\.RECEIVE_BOOT_COMPLETED["']\s*/>"""),
                        """<uses-permission android:name="disabled_android.permission.RECEIVE_BOOT_COMPLETED" />"""
                    )
                    if (xmlContent != original) Log.d(TAG, "✅ Автозапуск отключён")
                    else Log.d(TAG, "ℹ️ RECEIVE_BOOT_COMPLETED не найдено")
                }
                if (isFixGoogleMaps) {
                    val fixedKey = "AIzaSyCVqD1_AkEk9eW5HWbZw3A34bNIHJY90zI"
                    xmlContent = xmlContent.replace(
                        Regex("""(<meta-data[^>]*android:name="com\.google\.android\.geo\.API_KEY"[^>]*?)android:value=["'][^"']*["']""", RegexOption.IGNORE_CASE),
                        """$1android:value="$fixedKey""""
                    )
                    xmlContent = xmlContent.replace(
                        Regex("""android:name="com\.google\.android\.maps\.[pv]\d+\.API_KEY""", RegexOption.IGNORE_CASE),
                        """android:name="com.google.android.maps.v2.API_KEY""""
                    )
                    xmlContent = xmlContent.replace(
                        Regex("""(<meta-data[^>]*android:name="com\.google\.android\.maps\.v2\.API_KEY"[^>]*?)android:value=["'][^"']*["']""", RegexOption.IGNORE_CASE),
                        """$1android:value="$fixedKey""""
                    )
                }

                var needsBannerInjection = false
                if (cbConvertAtv.isChecked && selectedBannerPath != null) {
                    Log.d(TAG, "Начало конвертации для ATV...")
                    xmlContent = xmlContent.replace(
                        Regex("""<category android:name="android.intent.category.LAUNCHER" />"""),
                        """<category android:name="android.intent.category.LAUNCHER" />\n        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />"""
                    )
                    val appTagRegex = Regex("""(<application\s[^>]*)""")
                    val match = appTagRegex.find(xmlContent)
                    if (match != null) {
                        val featuresXml = """
                            <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
                            <uses-feature android:name="android.software.leanback" android:required="true" />
                        """.trimIndent()
                        val insertIndex = match.range.first
                        xmlContent = xmlContent.substring(0, insertIndex) + featuresXml + "\n    " + xmlContent.substring(insertIndex)

                        val newMatch = appTagRegex.find(xmlContent)
                        if (newMatch != null) {
                            var newAppTag = newMatch.groupValues[1]
                            if (!newAppTag.contains("android:banner=")) {
                                newAppTag += """ android:banner="@drawable/banner""""
                                xmlContent = xmlContent.replace(newMatch.groupValues[1], newAppTag)
                            }
                        }
                    }

                    val tableBlock = apkModule.tableBlock
                    val pkgBlock = tableBlock.pickOne() ?: throw Exception("Не найден PackageBlock")
                    val newEntry = pkgBlock.getOrCreate("", "drawable", "banner")
                    newEntry.setValueAsString("res/drawable/banner.png")
                    Log.d(TAG, "Ресурс banner добавлен с ID: ${newEntry.resourceId}")
                    needsBannerInjection = true
                }

                try { manifest.javaClass.getMethod("clear").invoke(manifest) }
                catch (_: Exception) { try { manifest.javaClass.getMethod("reset").invoke(manifest) } catch (_: Exception) {} }

                val parser = XMLFactory.newPullParser(StringReader(xmlContent))
                parser.setInput(StringReader(xmlContent))
                manifest.parse(parser)

                if (selectedIconPath != null) {
                    try {
                        val iconFile = File(selectedIconPath!!)
                        if (iconFile.exists()) {
                            IconReplacer.replaceIcon(apkModule, iconFile) { msg -> Log.d(TAG, msg) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка замены иконки", e)
                    }
                }

                val tempModified = File(cacheDir, "temp_edit_modified.apk")
                tempModified.delete()
                apkModule.writeApk(tempModified)
                apkModule.close()

                var finalApk = tempModified

                if (needsBannerInjection && selectedBannerPath != null) {
                    val bannerFile = File(selectedBannerPath!!)
                    if (bannerFile.exists()) {
                        val tempWithBanner = File(cacheDir, "temp_edit_with_banner.apk")
                        injectFileIntoApk(tempModified, bannerFile, "res/drawable/banner.png", tempWithBanner)
                        tempModified.delete()
                        finalApk = tempWithBanner
                        Log.d(TAG, "Файл banner.png внедрён в APK")
                    }
                }

                val signedApk = if (config.noSign) {
                    // ✅ СОХРАНЯЕМ ОРИГИНАЛЬНУЮ ПОДПИСЬ
                    val preservedApk = File(cacheDir, "temp_edit_preserved.apk")
                    preservedApk.delete()
                    SigningUtils.preserveOriginalSignature(File(apkPath), finalApk, preservedApk)
                    if (finalApk != tempModified) finalApk.delete()
                    else finalApk.delete()
                    Log.d(TAG, getString(R.string.original_signature_preserved))
                    preservedApk
                } else {
                    val tempSigned = File(cacheDir, "temp_edit_signed.apk")
                    tempSigned.delete()
                    SigningUtils.signApk(this, finalApk, tempSigned, config)
                    finalApk.delete()
                    tempSigned
                }

                val baseName = apkFile.nameWithoutExtension
                val outputName = "${baseName}_edited.apk"
                FileSaver.saveApk(this, signedApk, outputName)
                signedApk.delete()

                val signInfo = if (config.noSign) getString(R.string.original_signature_preserved)
                else "🔐 V1=${config.signV1}, V2=${config.signV2}, V3=${config.signV3}${if (config.useCustom) " (своя подпись)" else ""}"

                runOnUiThread {
                    btnSave.text = getString(R.string.edit)
                    btnSave.isEnabled = false
                    btnSave.visibility = View.GONE
                    originalAppName = currentAppName; originalVersion = currentVersion
                    originalVersionCode = currentVersionCode; originalMinSdk = currentMinSdk
                    originalTargetSdk = currentTargetSdk
                    isModified = false
                    cbConvertAtv.isChecked = false
                    selectedBannerPath = null
                    selectedIconPath = null
                    Toast.makeText(
                        this,
                        getString(R.string.saved_success, outputName, signInfo, SaveFolderHelper.getLocationLabel(this)),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка сохранения", e)
                runOnUiThread {
                    btnSave.text = getString(R.string.edit)
                    btnSave.isEnabled = true
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun injectFileIntoApk(inputApk: File, fileToAdd: File, entryName: String, outputApk: File) {
        val buffer = ByteArray(8192)
        ZipInputStream(FileInputStream(inputApk)).use { zis ->
            ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
                var entry: ZipEntry?
                var fileAdded = false

                while (zis.nextEntry.also { entry = it } != null) {
                    val name = entry!!.name
                    if (name == entryName) {
                        val newEntry = ZipEntry(entryName)
                        newEntry.method = ZipEntry.DEFLATED
                        zos.putNextEntry(newEntry)
                        FileInputStream(fileToAdd).use { fis ->
                            var len: Int
                            while (fis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                        }
                        zos.closeEntry()
                        fileAdded = true
                    } else {
                        zos.putNextEntry(entry)
                        zis.copyTo(zos)
                        zos.closeEntry()
                    }
                    zis.closeEntry()
                }

                if (!fileAdded) {
                    val newEntry = ZipEntry(entryName)
                    newEntry.method = ZipEntry.DEFLATED
                    zos.putNextEntry(newEntry)
                    FileInputStream(fileToAdd).use { fis ->
                        var len: Int
                        while (fis.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }


    private fun getAndroidVersionName(apiLevel: String): String {
        return when (apiLevel.toIntOrNull() ?: 0) {
            21 -> "5.0 Lollipop"; 22 -> "5.1 Lollipop"; 23 -> "6.0 Marshmallow"
            24 -> "7.0 Nougat"; 25 -> "7.1 Nougat"; 26 -> "8.0 Oreo"
            27 -> "8.1 Oreo"; 28 -> "9 Pie"; 29 -> "10"
            30 -> "11"; 31 -> "12"; 32 -> "12L"
            33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
            else -> apiLevel
        }
    }
}