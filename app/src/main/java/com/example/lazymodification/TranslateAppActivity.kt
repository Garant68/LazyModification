package com.example.lazymodification

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.AppTranslator
import com.example.lazymodification.utils.FileSaver
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.TranslationCache
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import java.io.File

/**
 * «Перевести приложение»: перевод строк приложения выбранным онлайн-переводчиком
 * с предпросмотром, кэшем и созданием русской локали в resources.arsc.
 *
 * Плейсхолдеры (%1$s и т.п.) защищаются от перевода (AppTranslator).
 * Перевод применяется только по кнопке «Применить переводы».
 */
class TranslateAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.INTERFACE_APK_PATH"
        private const val TAG = "TranslateApp"
        private const val PREVIEW_LIMIT = 400
        private const val TARGET_LANG = "ru"
    }

    private var apkPath: String? = null
    private var isProcessing = false

    private lateinit var tvFileName: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rbModeFull: RadioButton
    private lateinit var rbModeFast: RadioButton
    private lateinit var tvTranslatorLabel: TextView
    private lateinit var llFastInput: LinearLayout
    private lateinit var etFastStrings: EditText
    private lateinit var rgTranslator: RadioGroup
    private lateinit var rbGoogle: RadioButton
    private lateinit var rbMyMemory: RadioButton
    private lateinit var rbAzure: RadioButton
    private lateinit var rbDeepl: RadioButton
    private lateinit var llSetup: LinearLayout
    private lateinit var llAzure: LinearLayout
    private lateinit var etAzureKey: EditText
    private lateinit var etAzureRegion: EditText
    private lateinit var llDeepl: LinearLayout
    private lateinit var etDeeplKey: EditText
    private lateinit var btnStart: MaterialButton
    private lateinit var llProgress: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvPreviewTitle: TextView
    private lateinit var llPreview: LinearLayout
    private lateinit var btnApply: MaterialButton
    private lateinit var scrollMain: ScrollView

    private lateinit var cache: TranslationCache

    private var progressDialog: AlertDialog? = null
    private var progressDialogText: TextView? = null

    private var finalApkFile: File? = null
    private var finalPackageName: String? = null
    private var pendingInstallAfterUninstall = false

    private data class Row(val resourceId: Int, val name: String, val original: String, var translated: String)

    private val rows = mutableListOf<Row>()

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (pendingInstallAfterUninstall) {
            pendingInstallAfterUninstall = false
            val pkg = finalPackageName
            if (pkg != null && !isAppInstalled(pkg)) {
                Toast.makeText(this, getString(R.string.old_removed_installing), Toast.LENGTH_SHORT).show()
                proceedWithInstall()
            } else {
                Toast.makeText(this, getString(R.string.app_not_removed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) proceedWithInstall()
            else Toast.makeText(this, getString(R.string.install_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_translate_app)

        tvFileName = findViewById(R.id.tvFileName)
        rgMode = findViewById(R.id.rgMode)
        rbModeFull = findViewById(R.id.rbModeFull)
        rbModeFast = findViewById(R.id.rbModeFast)
        tvTranslatorLabel = findViewById(R.id.tvTranslatorLabel)
        llFastInput = findViewById(R.id.llFastInput)
        etFastStrings = findViewById(R.id.etFastStrings)
        rgTranslator = findViewById(R.id.rgTranslator)
        rbGoogle = findViewById(R.id.rbGoogle)
        rbMyMemory = findViewById(R.id.rbMyMemory)
        rbAzure = findViewById(R.id.rbAzure)
        rbDeepl = findViewById(R.id.rbDeepl)
        llSetup = findViewById(R.id.llSetup)
        llAzure = findViewById(R.id.llAzure)
        etAzureKey = findViewById(R.id.etAzureKey)
        etAzureRegion = findViewById(R.id.etAzureRegion)
        llDeepl = findViewById(R.id.llDeepl)
        etDeeplKey = findViewById(R.id.etDeeplKey)
        btnStart = findViewById(R.id.btnStartTranslate)
        llProgress = findViewById(R.id.llProgress)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvPreviewTitle = findViewById(R.id.tvPreviewTitle)
        llPreview = findViewById(R.id.llPreview)
        btnApply = findViewById(R.id.btnApply)
        scrollMain = findViewById(R.id.scrollMain)

        cache = TranslationCache(this)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath == null) {
            Toast.makeText(this, getString(R.string.file_not_specified), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val file = File(apkPath!!)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.file_not_exists), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tvFileName.text = getString(R.string.file_label, file.name)

        rgTranslator.setOnCheckedChangeListener { _, _ -> updateProviderFields() }
        rgMode.setOnCheckedChangeListener { _, _ -> updateModeUI() }
        updateModeUI()

        btnStart.setOnClickListener { startTranslation() }
        btnApply.setOnClickListener { applyTranslations() }
    }

    private fun currentProvider(): String = when {
        rbAzure.isChecked -> AppTranslator.AZURE
        rbDeepl.isChecked -> AppTranslator.DEEPL
        rbMyMemory.isChecked -> AppTranslator.MYMEMORY
        else -> AppTranslator.GOOGLE
    }

    private fun updateProviderFields() {
        llAzure.visibility = if (rbAzure.isChecked) View.VISIBLE else View.GONE
        llDeepl.visibility = if (rbDeepl.isChecked) View.VISIBLE else View.GONE
    }

    /** Быстрый режим: перевод вручную введённых строк через DeepL Free. */
    private fun updateModeUI() {
        val fast = rbModeFast.isChecked
        tvTranslatorLabel.visibility = if (fast) View.GONE else View.VISIBLE
        rgTranslator.visibility = if (fast) View.GONE else View.VISIBLE
        llFastInput.visibility = if (fast) View.VISIBLE else View.GONE
        if (fast) {
            llAzure.visibility = View.GONE
            llDeepl.visibility = View.GONE
        } else {
            updateProviderFields()
        }
        btnStart.text = getString(if (fast) R.string.fast_translate_action else R.string.start_translate)
    }

    /** Нужно ли переводить строку. */
    private fun shouldTranslate(value: String): Boolean {
        if (value.isBlank()) return false
        if (!value.any { it.isLetter() }) return false
        if (value.length > 1500) return false
        if (value.contains("http://") || value.contains("https://")) return false
        if (value.contains("@")) return false
        return true
    }

    private fun startTranslation() {
        if (isProcessing) return
        if (rbModeFast.isChecked) {
            startFastTranslation()
            return
        }
        val provider = currentProvider()
        val key = when (provider) {
            AppTranslator.AZURE -> etAzureKey.text.toString().trim()
            AppTranslator.DEEPL -> etDeeplKey.text.toString().trim()
            else -> null
        }
        if ((provider == AppTranslator.AZURE || provider == AppTranslator.DEEPL) && key.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.err_translator_key_required), Toast.LENGTH_LONG).show()
            return
        }
        val region = etAzureRegion.text.toString().trim()

        isProcessing = true
        btnStart.isEnabled = false
        btnApply.visibility = View.GONE
        tvPreviewTitle.visibility = View.GONE
        llPreview.removeAllViews()
        llProgress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_preparing_strings)

        Thread {
            try {
                val collected = mutableListOf<Row>()
                val module = ApkModule.loadApkFile(File(apkPath!!))
                try {
                    for (pkg in module.tableBlock.listPackages()) {
                        val strings = pkg.getResources("string")
                        while (strings.hasNext()) {
                            val e = strings.next()
                            val def = e.get(ResConfig.getDefault()) ?: e.get() ?: continue
                            if (def.isNull()) continue
                            val v = def.getValueAsString() ?: continue
                            if (!shouldTranslate(v)) continue
                            collected.add(Row(e.getResourceId(), e.getName() ?: "", v, v))
                        }
                    }
                } finally {
                    module.close()
                }
                if (collected.isEmpty()) {
                    throw Exception(getString(R.string.err_translate_empty))
                }
                var i = 0
                for (r in collected) {
                    i++
                    val ck = cache.key(provider, "auto", TARGET_LANG, r.original)
                    val cached = cache.get(ck)
                    if (cached != null) {
                        r.translated = cached
                    } else {
                        try {
                            r.translated = AppTranslator.translateOnce(provider, key, region, "auto", TARGET_LANG, r.original)
                            cache.put(ck, r.translated)
                        } catch (ex: Exception) {
                            Log.w(TAG, "skip \"${r.original.take(40)}\": ${ex.message}")
                        }
                        Thread.sleep(120)
                    }
                    val current = i
                    val total = collected.size
                    runOnUiThread { tvStatus.text = getString(R.string.status_translating, current, total) }
                }
                cache.save()
                runOnUiThread { showPreview(collected) }
            } catch (e: Throwable) {
                Log.e(TAG, "translate error", e)
                runOnUiThread {
                    llProgress.visibility = View.GONE
                    btnStart.isEnabled = true
                    isProcessing = false
                    showErrorDialog(getString(R.string.err_translate_failed, e.message ?: ""))
                }
            }
        }.start()
    }

    /** Быстрый перевод: строки, введённые через запятую; переводчик — MyMemory. */
    private fun startFastTranslation() {
        val items = etFastStrings.text.toString()
            .split(",", "，", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_fast_empty), Toast.LENGTH_LONG).show()
            return
        }

        isProcessing = true
        btnStart.isEnabled = false
        btnApply.visibility = View.GONE
        tvPreviewTitle.visibility = View.GONE
        llPreview.removeAllViews()
        llProgress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_preparing_strings)
        scrollMain.scrollTo(0, 0)

        Thread {
            try {
                val result = mutableListOf<Row>()
                var i = 0
                for (item in items) {
                    i++
                    val ck = cache.key(AppTranslator.MYMEMORY, "auto", TARGET_LANG, item)
                    val cached = cache.get(ck)
                    val translated: String
                    if (cached != null) {
                        translated = cached
                    } else {
                        var t = item
                        var ok = false
                        try {
                            t = AppTranslator.translateOnce(AppTranslator.MYMEMORY, null, null, "auto", TARGET_LANG, item)
                            ok = true
                        } catch (ex: Exception) {
                            Log.w(TAG, "skip \"${item.take(40)}\": ${ex.message}")
                        }
                        if (ok) cache.put(ck, t)
                        translated = t
                    }
                    result.add(Row(0, "", item, translated))
                    val current = i
                    val total = items.size
                    runOnUiThread { tvStatus.text = getString(R.string.status_translating, current, total) }
                    Thread.sleep(100)
                }
                cache.save()
                runOnUiThread { showPreview(result) }
            } catch (e: Throwable) {
                Log.e(TAG, "fast translate error", e)
                runOnUiThread {
                    llProgress.visibility = View.GONE
                    btnStart.isEnabled = true
                    isProcessing = false
                    showErrorDialog(getString(R.string.err_translate_failed, e.message ?: ""))
                }
            }
        }.start()
    }

    private fun showPreview(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        llSetup.visibility = View.GONE
        llProgress.visibility = View.GONE
        tvPreviewTitle.visibility = View.VISIBLE
        tvPreviewTitle.text = getString(R.string.translations_preview, list.size)

        llPreview.removeAllViews()
        llPreview.addView(TextView(this).apply {
            text = getString(R.string.translations_edit_hint)
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary, 0xFF808080.toInt()))
            setPadding(0, 0, 0, 8)
        })
        val show = list.take(PREVIEW_LIMIT)
        for (r in show) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            rowView.addView(TextView(this).apply {
                text = r.original
                textSize = 12f
                setTextColor(themeColor(android.R.attr.textColorSecondary, 0xFF808080.toInt()))
                maxLines = 2
            })
            rowView.addView(EditText(this).apply {
                setText(r.translated)
                textSize = 15f
                setTextColor(themeColor(android.R.attr.textColorPrimary, 0xFF000000.toInt()))
                maxLines = 4
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(0, 4, 0, 4)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        r.translated = s?.toString() ?: ""
                    }
                })
            })
            llPreview.addView(rowView)
        }
        if (list.size > show.size) {
            llPreview.addView(TextView(this).apply {
                text = getString(R.string.preview_truncated, show.size)
                textSize = 13f
            })
        }

        btnApply.visibility = View.VISIBLE
        btnApply.isEnabled = true
        isProcessing = false
        btnStart.isEnabled = true
        scrollMain.scrollTo(0, 0)
    }

    private fun applyTranslations() {
        if (isProcessing || rows.isEmpty()) return
        isProcessing = true
        btnApply.isEnabled = false
        llProgress.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_translations_write)
        scrollMain.scrollTo(0, 0)
        showProgressDialog(getString(R.string.status_translations_write))

        Thread {
            var notFound: List<String> = emptyList()
            try {
                val work = File(cacheDir, "translate_${System.currentTimeMillis()}.apk")
                val module = ApkModule.loadApkFile(File(apkPath!!))
                try {
                    val byId = rows.filter { it.resourceId != 0 }.associateBy { it.resourceId }
                    val byValue = rows.filter { it.resourceId == 0 }.associate { it.original to it.translated }
                    val matchedValues = mutableSetOf<String>()
                    val ru = ResConfig.parse(TARGET_LANG)
                    for (pkg in module.tableBlock.listPackages()) {
                        val strings = pkg.getResources("string")
                        while (strings.hasNext()) {
                            val e = strings.next()
                            if (byId.isNotEmpty()) {
                                val row = byId[e.getResourceId()]
                                if (row != null) {
                                    if (row.translated != row.original) e.getOrCreate(ru).setValueAsString(row.translated)
                                    continue
                                }
                            }
                            if (byValue.isNotEmpty()) {
                                val def = e.get(ResConfig.getDefault()) ?: e.get() ?: continue
                                if (def.isNull()) continue
                                val v = def.getValueAsString() ?: continue
                                val t = byValue[v] ?: continue
                                matchedValues.add(v)
                                if (t != v) e.getOrCreate(ru).setValueAsString(t)
                            }
                        }
                    }
                    notFound = byValue.keys.filter { it !in matchedValues }
                    if (work.exists()) work.delete()
                    module.writeApk(work)
                } finally {
                    module.close()
                }

                runOnUiThread {
                    tvStatus.text = getString(R.string.status_zipalign)
                    updateProgressDialog(getString(R.string.status_zipalign))
                }
                var toSign = work
                val aligned = File(cacheDir, "translate_aligned.apk")
                try {
                    ApkUtils.zipAlign(work, aligned)
                    if (work.exists()) work.delete()
                    toSign = aligned
                } catch (e: Exception) {
                    Log.w(TAG, "zipAlign failed: ${e.message}")
                }

                runOnUiThread {
                    tvStatus.text = getString(R.string.status_signing_apk)
                    updateProgressDialog(getString(R.string.status_signing_apk))
                }
                val signed = File(cacheDir, "translate_signed.apk")
                if (signed.exists()) signed.delete()
                SigningUtils.signApk(this, toSign, signed, SignatureConfig())

                val outputName = File(apkPath!!).nameWithoutExtension + "_translated.apk"
                FileSaver.saveApk(this, signed, outputName)

                finalApkFile = signed
                finalPackageName = getPackageNameFromApk(signed)

                runOnUiThread {
                    dismissProgressDialog()
                    llProgress.visibility = View.GONE
                    isProcessing = false
                    btnApply.isEnabled = true
                    Toast.makeText(this, getString(R.string.translations_applied), Toast.LENGTH_LONG).show()
                    if (notFound.isEmpty()) {
                        showInstallDialog()
                    } else {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.msg_strings_not_found, notFound.take(10).joinToString(", ")))
                            .setPositiveButton(android.R.string.ok) { _, _ -> showInstallDialog() }
                            .show()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "apply error", e)
                runOnUiThread {
                    dismissProgressDialog()
                    llProgress.visibility = View.GONE
                    isProcessing = false
                    btnApply.isEnabled = true
                    showErrorDialog(getString(R.string.err_translate_failed, e.message ?: ""))
                }
            }
        }.start()
    }

    private fun showProgressDialog(initialText: String) {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 24)
            gravity = android.view.Gravity.CENTER
        }
        ll.addView(ProgressBar(this))
        val tvText = TextView(this).apply {
            text = initialText
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        ll.addView(tvText)
        progressDialogText = tvText
        progressDialog = AlertDialog.Builder(this)
            .setView(ll)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun updateProgressDialog(text: String) {
        progressDialogText?.text = text
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
        progressDialogText = null
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        if (theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
        }
        return fallback
    }
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ----- установка результата (паттерн как в InterfaceModifierActivity) -----

    private fun showInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.install_question))
            .setMessage(getString(R.string.install_modified_question))
            .setPositiveButton(getString(R.string.install)) { _, _ -> handleInstallClick() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun handleInstallClick() {
        val path = finalApkFile?.absolutePath
        if (path == null) {
            Toast.makeText(this, getString(R.string.apk_file_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        val apkFile = File(path)
        if (!apkFile.exists()) {
            Toast.makeText(this, getString(R.string.apk_file_not_exists), Toast.LENGTH_SHORT).show()
            return
        }
        val effectivePkg = finalPackageName ?: getPackageNameFromApk(apkFile)
        if (effectivePkg == null) {
            proceedWithInstall(); return
        }
        finalPackageName = effectivePkg
        if (!isAppInstalled(effectivePkg)) {
            proceedWithInstall(); return
        }
        val installedSig = getInstalledAppSignature(effectivePkg)
        val apkSig = getApkFileSignature(path)
        if (installedSig == null || apkSig == null) {
            proceedWithInstall(); return
        }
        if (installedSig == apkSig) proceedWithInstall()
        else showSignatureMismatchDialog(effectivePkg)
    }

    private fun proceedWithInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, getString(R.string.allow_unknown_sources), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                installPermissionLauncher.launch(intent)
                return
            }
        }
        val path = finalApkFile?.absolutePath ?: return
        installApk(File(path))
    }

    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.install_error_msg, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun showSignatureMismatchDialog(pkgName: String) {
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkgName
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.signature_conflict))
            .setMessage(
                getString(R.string.sig_mismatch_part1, appLabel) +
                        getString(R.string.uninstall_and_install_question)
            )
            .setPositiveButton(getString(R.string.uninstall_and_install)) { _, _ ->
                pendingInstallAfterUninstall = true
                uninstallApp(pkgName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun uninstallApp(pkgName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$pkgName")
            }
            uninstallLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.uninstall_error_msg, e.message), Toast.LENGTH_LONG).show()
            pendingInstallAfterUninstall = false
        }
    }

    private fun isAppInstalled(pkgName: String): Boolean = try {
        packageManager.getPackageInfo(pkgName, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun getPackageNameFromApk(apkFile: File): String? = try {
        packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
    } catch (e: Exception) {
        null
    }

    private fun getInstalledAppSignature(pkgName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pi = packageManager.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = pi.signingInfo
            if (si.hasMultipleSigners()) si.apkContentsSigners.joinToString { it.toCharsString() }
            else si.signingCertificateHistory.joinToString { it.toCharsString() }
        } else {
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            pi.signatures.joinToString { it.toCharsString() }
        }
    } catch (e: Exception) {
        null
    }

    private fun getApkFileSignature(apkPath: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pi = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = pi?.signingInfo ?: return null
            if (si.hasMultipleSigners()) si.apkContentsSigners.joinToString { it.toCharsString() }
            else si.signingCertificateHistory.joinToString { it.toCharsString() }
        } else {
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            pi?.signatures?.joinToString { it.toCharsString() }
        }
    } catch (e: Exception) {
        null
    }
}
