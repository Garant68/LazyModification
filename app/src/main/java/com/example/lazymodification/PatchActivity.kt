package com.example.lazymodification

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import com.google.android.material.checkbox.MaterialCheckBox as CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.ApkInspector
import com.example.lazymodification.utils.DexPatcher
import com.example.lazymodification.utils.DpiRemove
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.copyText
import com.example.lazymodification.utils.patches.PatchCompatibility
import com.example.lazymodification.utils.patches.PatchDocument
import com.example.lazymodification.utils.patches.PatchFiles
import com.example.lazymodification.utils.patches.PatchFormat
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkModule
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class PatchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.PATCH_APK_PATH"
        const val TAG = "PatchActivity"
    }

    private lateinit var tvFileName: TextView
    private lateinit var checkBoxGooglePlay: MaterialButton
    private lateinit var checkBoxRemoveAds: MaterialButton
    private lateinit var checkBoxRemoveAnalytics: MaterialButton
    private lateinit var checkBoxRemoveGPServices: MaterialButton
    private lateinit var checkBoxRemoveVpn: MaterialButton
    private lateinit var checkBoxAllowScreenshots: MaterialButton
    private lateinit var checkBoxRemoveInstallerCheck: MaterialButton
    private lateinit var checkBoxRemoveUpdate: MaterialButton
    private lateinit var checkBoxRemoveLocales: MaterialButton
    private lateinit var checkBoxRemoveDpi: MaterialButton
    private lateinit var checkBoxRemoveLibs: MaterialButton
    private lateinit var checkBoxOptimize: MaterialButton
    private lateinit var btnInspectorApk: MaterialButton
    private lateinit var btnRemoveSignatureCheck: MaterialButton
    
    private var isInspecting = false
    private var inspectorProgress: AlertDialog? = null
    private lateinit var btnPatch: MaterialButton
    private var apkPath: String? = null
    private var availableLocales: List<String> = emptyList()
    private var selectedLocales: List<String> = emptyList()
    private var isLoadingLocales = false
    private var availableDpis: List<String> = emptyList()
    private var selectedDpis: List<String> = emptyList()
    private var isLoadingDpis = false
    private var availableArchs: List<String> = emptyList()
    private var selectedArchs: List<String> = emptyList()
    private var isLoadingArchs = false
    private val customPatches = arrayListOf<String>()
    private lateinit var customPatchSummary: TextView
    private val customPatchPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            Thread {
                val imported = mutableListOf<File>()
                try {
                    uris.forEach { imported += PatchFiles.import(this, it) }
                    runOnUiThread {
                        customPatches.addAll(imported.map { it.path })
                        refreshCustomPatches()
                    }
                } catch (t: Throwable) {
                    imported.forEach { it.parentFile?.deleteRecursively() }
                    runOnUiThread { Toast.makeText(this, t.message ?: getString(R.string.custom_patch_read_error), Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
    }

    // ✅ Пользовательская подпись
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

    private var killApkPath: String? = null

    private val pickApkForKillLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startKillFlow(path)
                } else {
                    Toast.makeText(this, getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_patch)

        tvFileName = findViewById(R.id.tvFileName)
        checkBoxGooglePlay = findViewById(R.id.checkBoxGooglePlay)
        checkBoxRemoveAds = findViewById(R.id.checkBoxRemoveAds)
        checkBoxRemoveAnalytics = findViewById(R.id.checkBoxRemoveAnalytics)
        checkBoxRemoveGPServices = findViewById(R.id.checkBoxRemoveGPServices)
        checkBoxRemoveVpn = findViewById(R.id.checkBoxRemoveVpn)
        checkBoxAllowScreenshots = findViewById(R.id.checkBoxAllowScreenshots)
        checkBoxRemoveInstallerCheck = findViewById(R.id.checkBoxRemoveInstallerCheck)
        checkBoxRemoveUpdate = findViewById(R.id.checkBoxRemoveUpdate)
        checkBoxRemoveLocales = findViewById(R.id.checkBoxRemoveLocales)
        checkBoxRemoveDpi = findViewById(R.id.checkBoxRemoveDpi)
        checkBoxRemoveLibs = findViewById(R.id.checkBoxRemoveLibs)
        checkBoxOptimize = findViewById(R.id.checkBoxOptimize)
        btnInspectorApk = findViewById(R.id.btnInspectorApk)
        btnRemoveSignatureCheck = findViewById(R.id.btnRemoveSignatureCheck)
        
        btnPatch = findViewById(R.id.btnPatch)
        customPatchSummary = findViewById(R.id.customPatchSummary)
        savedInstanceState?.getStringArrayList("custom_patch_paths")?.filter { File(it).isFile }?.let { customPatches.addAll(it) }
        findViewById<MaterialButton>(R.id.btnCustomPatches).setOnClickListener { customPatchPicker.launch(arrayOf("*/*")) }
        customPatchSummary.setOnClickListener {
            if (customPatches.isNotEmpty()) {
                val names = customPatches.map { File(it).name }.toTypedArray()
                MaterialAlertDialogBuilder(this).setTitle(R.string.custom_patch_selected).setItems(names) { _, index ->
                    startActivity(Intent(this, PatchEditorActivity::class.java).putExtra(PatchEditorActivity.EXTRA_PATCH_PATH, customPatches[index]))
                }.show()
            }
        }

        apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath == null) {
            Toast.makeText(this, getString(R.string.file_not_selected_err), Toast.LENGTH_SHORT).show()
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

        preloadLocales()
        preloadDpis()
        preloadArchs()
        setupCheckboxes()
        refreshCustomPatches()
        btnPatch.setOnClickListener {
            if (otherPatchesSelected()) {
                showSigningDialog()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("custom_patch_paths", ArrayList(customPatches))
        super.onSaveInstanceState(outState)
    }

    private fun refreshCustomPatches() {
        val hasCustomPatches = customPatches.isNotEmpty()
        customPatchSummary.visibility = if (hasCustomPatches) View.VISIBLE else View.GONE
        customPatchSummary.text = customPatches.joinToString("\n") { File(it).name }
        listOf(
            checkBoxRemoveAds,
            checkBoxRemoveAnalytics,
            checkBoxRemoveUpdate,
            checkBoxRemoveGPServices,
            checkBoxRemoveVpn,
            checkBoxAllowScreenshots
        ).forEach { button ->
            button.isEnabled = !hasCustomPatches
            if (hasCustomPatches) button.isChecked = false
        }
        updatePatchButtonState()
    }

    private fun preloadLocales() {
        Thread {
            try {
                val locales = DexPatcher.readLocalesFromApk(File(apkPath!!))
                runOnUiThread {
                    availableLocales = locales
                    Log.d(TAG, "🌍 Найдено языков: ${locales.size}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Ошибка чтения локалей", e)
            }
        }.start()
    }

    private fun preloadDpis() {
        Thread {
            try {
                val dpis = DpiRemove.readDpisFromApk(File(apkPath!!))
                runOnUiThread {
                    availableDpis = dpis
                    Log.d(TAG, "📱 Найдено DPI: ${dpis.size}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Ошибка чтения DPI", e)
            }
        }.start()
    }

    private fun preloadArchs() {
        Thread {
            try {
                val archs = readArchitecturesFromApk(File(apkPath!!))
                runOnUiThread {
                    availableArchs = archs
                    Log.d(TAG, "🏗️ Найдено архитектур: ${archs.size}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Ошибка чтения архитектур", e)
            }
        }.start()
    }

    private fun readArchitecturesFromApk(apkFile: File): List<String> {
        val archSet = mutableSetOf<String>()
        try {
            val apkModule = ApkModule.loadApkFile(apkFile)
            for (inputSource in apkModule.listInputSources()) {
                val name = inputSource.alias ?: inputSource.name
                if (name.startsWith("lib/")) {
                    val parts = name.split("/")
                    if (parts.size >= 2 && parts[1].isNotBlank()) {
                        archSet.add(parts[1])
                    }
                }
            }
            apkModule.close()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения архитектур: ${e.message}", e)
        }
        return archSet.sorted()
    }

    private fun setupCheckboxes() {
        val listener = MaterialButton.OnCheckedChangeListener { buttonView, isChecked ->
            // Отметка ☐/☑ прямо в тексте кнопки — всегда видно
            refreshToggleText(buttonView)
            when (buttonView.id) {
                R.id.checkBoxRemoveLocales -> handleLocalesCheckbox(isChecked)
                R.id.checkBoxRemoveDpi -> handleDpiCheckbox(isChecked)
                R.id.checkBoxRemoveLibs -> handleLibsCheckbox(isChecked)
                else -> updatePatchButtonState()
            }
        }
        checkBoxGooglePlay.addOnCheckedChangeListener(listener)
        checkBoxRemoveAds.addOnCheckedChangeListener(listener)
        checkBoxRemoveAnalytics.addOnCheckedChangeListener(listener)
        checkBoxRemoveGPServices.addOnCheckedChangeListener(listener)
        checkBoxRemoveVpn.addOnCheckedChangeListener(listener)
        checkBoxAllowScreenshots.addOnCheckedChangeListener(listener)
        checkBoxRemoveInstallerCheck.addOnCheckedChangeListener(listener)
        checkBoxRemoveUpdate.addOnCheckedChangeListener(listener)
        checkBoxRemoveLocales.addOnCheckedChangeListener(listener)
        checkBoxRemoveDpi.addOnCheckedChangeListener(listener)
        checkBoxRemoveLibs.addOnCheckedChangeListener(listener)
        checkBoxOptimize.addOnCheckedChangeListener(listener)

        // MaterialButton: переключение отметки по нажатию
        checkBoxGooglePlay.setToggleCheckedStateOnClick(true)
        checkBoxRemoveAds.setToggleCheckedStateOnClick(true)
        checkBoxRemoveAnalytics.setToggleCheckedStateOnClick(true)
        checkBoxRemoveGPServices.setToggleCheckedStateOnClick(true)
        checkBoxRemoveVpn.setToggleCheckedStateOnClick(true)
        checkBoxAllowScreenshots.setToggleCheckedStateOnClick(true)
        checkBoxRemoveInstallerCheck.setToggleCheckedStateOnClick(true)
        checkBoxRemoveUpdate.setToggleCheckedStateOnClick(true)
        checkBoxRemoveLocales.setToggleCheckedStateOnClick(true)
        checkBoxRemoveDpi.setToggleCheckedStateOnClick(true)
        checkBoxRemoveLibs.setToggleCheckedStateOnClick(true)
        checkBoxOptimize.setToggleCheckedStateOnClick(true)
        checkBoxOptimize.isChecked = true

        for (b in listOf(
            checkBoxGooglePlay, checkBoxRemoveAds, checkBoxRemoveAnalytics, checkBoxRemoveGPServices,
            checkBoxRemoveVpn, checkBoxAllowScreenshots, checkBoxRemoveInstallerCheck, checkBoxRemoveUpdate, checkBoxRemoveLocales,
            checkBoxRemoveDpi, checkBoxRemoveLibs, checkBoxOptimize
        )) {
            refreshToggleText(b)
        }

        btnInspectorApk.setOnClickListener { runInspector() }

        btnRemoveSignatureCheck.setOnClickListener {
            pickApkForKillLauncher.launch(Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
            })
        }
    }

    private fun handleLibsCheckbox(isChecked: Boolean) {
        if (isChecked) {
            if (isLoadingArchs) {
                Toast.makeText(this, getString(R.string.status_reading_archs), Toast.LENGTH_SHORT).show()
                checkBoxRemoveLibs.isChecked = false
                return
            }
            if (availableArchs.isEmpty()) {
                isLoadingArchs = true
                Toast.makeText(this, getString(R.string.status_reading_archs_apk), Toast.LENGTH_SHORT).show()
                checkBoxRemoveLibs.isChecked = false
                Thread {
                    try {
                        val archs = readArchitecturesFromApk(File(apkPath!!))
                        runOnUiThread {
                            isLoadingArchs = false
                            availableArchs = archs
                            if (archs.isEmpty()) {
                                Toast.makeText(this, getString(R.string.archs_not_found), Toast.LENGTH_LONG).show()
                            } else {
                                selectedArchs = archs.toList()
                                checkBoxRemoveLibs.isChecked = true
                                showArchsDialog()
                            }
                            updatePatchButtonState()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            isLoadingArchs = false
                            Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
                return
            } else {
                if (selectedArchs.isEmpty()) selectedArchs = availableArchs.toList()
                showArchsDialog()
            }
        } else {
            selectedArchs = emptyList()
        }
        updatePatchButtonState()
    }

    private fun handleDpiCheckbox(isChecked: Boolean) {
        if (isChecked) {
            if (isLoadingDpis) {
                Toast.makeText(this, getString(R.string.status_reading_dpi), Toast.LENGTH_SHORT).show()
                checkBoxRemoveDpi.isChecked = false
                return
            }
            if (availableDpis.isEmpty()) {
                isLoadingDpis = true
                Toast.makeText(this, getString(R.string.status_reading_dpi_apk), Toast.LENGTH_SHORT).show()
                checkBoxRemoveDpi.isChecked = false
                Thread {
                    try {
                        val dpis = DpiRemove.readDpisFromApk(File(apkPath!!))
                        runOnUiThread {
                            isLoadingDpis = false
                            availableDpis = dpis
                            if (dpis.isEmpty()) {
                                Toast.makeText(this, getString(R.string.dpi_not_found), Toast.LENGTH_LONG).show()
                            } else {
                                selectedDpis = dpis.toList()
                                checkBoxRemoveDpi.isChecked = true
                                showDpiDialog()
                            }
                            updatePatchButtonState()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            isLoadingDpis = false
                            Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
                return
            } else {
                if (selectedDpis.isEmpty()) selectedDpis = availableDpis.toList()
                showDpiDialog()
            }
        } else {
            selectedDpis = emptyList()
        }
        updatePatchButtonState()
    }

    private fun handleLocalesCheckbox(isChecked: Boolean) {
        if (isChecked) {
            if (isLoadingLocales) {
                Toast.makeText(this, getString(R.string.status_reading_locales), Toast.LENGTH_SHORT).show()
                checkBoxRemoveLocales.isChecked = false
                return
            }
            if (availableLocales.isEmpty()) {
                isLoadingLocales = true
                Toast.makeText(this, getString(R.string.status_reading_locales_apk), Toast.LENGTH_SHORT).show()
                checkBoxRemoveLocales.isChecked = false
                Thread {
                    try {
                        val locales = DexPatcher.readLocalesFromApk(File(apkPath!!))
                        runOnUiThread {
                            isLoadingLocales = false
                            availableLocales = locales
                            if (locales.isEmpty()) {
                                Toast.makeText(this, getString(R.string.locales_not_found), Toast.LENGTH_LONG).show()
                            } else {
                                selectedLocales = locales.toList()
                                checkBoxRemoveLocales.isChecked = true
                                showLocalesDialog()
                            }
                            updatePatchButtonState()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            isLoadingLocales = false
                            Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
                return
            } else {
                if (selectedLocales.isEmpty()) selectedLocales = availableLocales.toList()
                showLocalesDialog()
            }
        } else {
            selectedLocales = emptyList()
        }
        updatePatchButtonState()
    }

    private fun updatePatchButtonState() {
        val any = checkBoxGooglePlay.isChecked || checkBoxRemoveAds.isChecked ||
                checkBoxRemoveAnalytics.isChecked || checkBoxRemoveGPServices.isChecked ||
                checkBoxAllowScreenshots.isChecked || checkBoxRemoveVpn.isChecked || checkBoxRemoveInstallerCheck.isChecked || checkBoxRemoveUpdate.isChecked || checkBoxRemoveLocales.isChecked ||
                checkBoxRemoveDpi.isChecked || checkBoxRemoveLibs.isChecked || checkBoxOptimize.isChecked || customPatches.isNotEmpty()
        btnPatch.isEnabled = any
        btnPatch.visibility = if (any) View.VISIBLE else View.GONE
    }

    private fun showArchsDialog() {
        if (availableArchs.isEmpty()) {
            Toast.makeText(this, getString(R.string.archs_not_found), Toast.LENGTH_LONG).show()
            checkBoxRemoveLibs.isChecked = false
            return
        }
        var dialog: AlertDialog? = null
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16)
        }
        val checkBoxes = mutableListOf<CheckBox>()
        val archContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (arch in availableArchs) {
            val cb = CheckBox(this).apply {
                text = arch; textSize = 15f; setPadding(8, 8, 8, 8)
                isChecked = selectedArchs.contains(arch)
            }
            checkBoxes.add(cb)
            archContainer.addView(cb)
        }
        val scrollView = ScrollView(this).apply { addView(archContainer) }
        mainContainer.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialogButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 16, 0, 0)
        }
        val btnCancel = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.cancel); textSize = 14f
            setOnClickListener {
                if (selectedArchs.isEmpty()) checkBoxRemoveLibs.isChecked = false
                updatePatchButtonState(); dialog?.dismiss()
            }
        }
        val btnOk = MaterialButton(this).apply {
            text = "OK"; textSize = 14f
            setOnClickListener {
                selectedArchs = checkBoxes.mapIndexedNotNull { i, cb -> if (cb.isChecked) availableArchs[i] else null }
                if (selectedArchs.isEmpty()) checkBoxRemoveLibs.isChecked = false
                updatePatchButtonState(); dialog?.dismiss()
            }
        }
        dialogButtonsRow.addView(btnCancel); dialogButtonsRow.addView(btnOk)
        mainContainer.addView(dialogButtonsRow)
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.choose_archs))
            .setMessage(getString(R.string.libs_will_be_removed))
            .setView(mainContainer).setCancelable(false).create()
        dialog?.show()
    }

    private fun showDpiDialog() {
        if (availableDpis.isEmpty()) {
            Toast.makeText(this, getString(R.string.dpi_not_found), Toast.LENGTH_LONG).show()
            checkBoxRemoveDpi.isChecked = false
            return
        }
        var dialog: AlertDialog? = null
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16)
        }
        val checkBoxes = mutableListOf<CheckBox>()
        val dpiOrder = listOf("-ldpi", "-mdpi", "-hdpi", "-xhdpi", "-xxhdpi", "-xxxhdpi", "-nodpi", "-tvdpi", "-anydpi")
        val sortedDpis = availableDpis.sortedWith(compareBy({ dpiOrder.indexOf(it).takeIf { idx -> idx >= 0 } ?: 99 }, { it }))
        val dpiContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (dpi in sortedDpis) {
            val cb = CheckBox(this).apply {
                text = dpi; textSize = 15f; setPadding(8, 8, 8, 8)
                isChecked = selectedDpis.contains(dpi)
            }
            checkBoxes.add(cb)
            dpiContainer.addView(cb)
        }
        val scrollView = ScrollView(this).apply { addView(dpiContainer) }
        mainContainer.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialogButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 16, 0, 0)
        }
        val btnCancel = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.cancel); textSize = 14f
            setOnClickListener {
                if (selectedDpis.isEmpty()) checkBoxRemoveDpi.isChecked = false
                updatePatchButtonState(); dialog?.dismiss()
            }
        }
        val btnOk = MaterialButton(this).apply {
            text = "OK"; textSize = 14f
            setOnClickListener {
                selectedDpis = checkBoxes.mapIndexedNotNull { i, cb -> if (cb.isChecked) sortedDpis[i] else null }
                if (selectedDpis.isEmpty()) checkBoxRemoveDpi.isChecked = false
                updatePatchButtonState(); dialog?.dismiss()
            }
        }
        dialogButtonsRow.addView(btnCancel); dialogButtonsRow.addView(btnOk)
        mainContainer.addView(dialogButtonsRow)
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.choose_dpi))
            .setView(mainContainer).setCancelable(false).create()
        dialog?.show()
    }

    private fun showLocalesDialog() {
        if (availableLocales.isEmpty()) {
            Toast.makeText(this, getString(R.string.locales_not_found), Toast.LENGTH_LONG).show()
            checkBoxRemoveLocales.isChecked = false
            return
        }
        var dialog: AlertDialog? = null
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16)
        }
        val checkBoxes = mutableListOf<CheckBox>()
        val localesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (locale in availableLocales) {
            val cb = CheckBox(this).apply {
                text = getLocaleDisplayName(locale); textSize = 15f; setPadding(8, 8, 8, 8)
                isChecked = selectedLocales.contains(locale)
            }
            checkBoxes.add(cb)
            localesContainer.addView(cb)
        }
        val scrollView = ScrollView(this).apply { addView(localesContainer) }
        mainContainer.addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val dialogButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 16, 0, 0)
        }
        val btnCancel = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.cancel); textSize = 14f
            setOnClickListener {
                if (selectedLocales.isEmpty()) checkBoxRemoveLocales.isChecked = false
                updatePatchButtonState(); dialog?.dismiss()
            }
        }
        val btnOk = MaterialButton(this).apply {
            text = "OK"; textSize = 14f
            setOnClickListener {
                selectedLocales = checkBoxes.mapIndexedNotNull { i, cb -> if (cb.isChecked) availableLocales[i] else null }
                if (selectedLocales.isEmpty()) checkBoxRemoveLocales.isChecked = false
                updatePatchButtonState(); dialog?.dismiss()
            }
        }
        dialogButtonsRow.addView(btnCancel); dialogButtonsRow.addView(btnOk)
        mainContainer.addView(dialogButtonsRow)
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.choose_locales))
            .setView(mainContainer).setCancelable(false).create()
        dialog?.show()
    }

    private val extraLocaleNames = mapOf(
        "bho" to R.string.locale_bho,
        "ceb" to R.string.locale_ceb,
        "ckb" to R.string.locale_ckb,
        "doi" to R.string.locale_doi,
        "fil" to R.string.locale_fil,
        "haw" to R.string.locale_haw,
        "hmn" to R.string.locale_hmn,
        "ilo" to R.string.locale_ilo,
        "jw" to R.string.locale_jw,
        "kri" to R.string.locale_kri,
        "lus" to R.string.locale_lus,
        "mai" to R.string.locale_mai,
        "nso" to R.string.locale_nso
    )

    private fun getLocaleDisplayName(qualifier: String): String {
        val clean = qualifier.trimStart('-')
        val lang = if (clean.startsWith("b+")) {
            clean.split('+').getOrNull(1) ?: ""
        } else {
            clean.split('-').firstOrNull() ?: ""
        }
        extraLocaleNames[lang]?.let { return "${getString(it)} $clean" }
        return try {
            if (clean.startsWith("b+")) {
                val parts = clean.split('+')
                if (parts.size >= 2) {
                    val locale = java.util.Locale(parts[1])
                    val displayName = locale.getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() }
                    "$displayName $clean"
                } else qualifier
            } else {
                val parts = clean.split('-')
                val country = when {
                    parts.size > 1 && parts[1].startsWith("r") && parts[1].length == 3 -> parts[1].substring(1)
                    parts.size > 1 && parts[1].length == 2 -> parts[1]
                    else -> null
                }
                val locale = if (country != null) java.util.Locale(lang, country) else java.util.Locale(lang)
                val displayName = locale.getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() }
                if (country != null) {
                    val countryName = locale.getDisplayCountry(java.util.Locale.getDefault())
                    if (countryName.isNotEmpty()) "$displayName ($countryName) $clean" else "$displayName $clean"
                } else "$displayName $clean"
            }
        } catch (e: Exception) { qualifier }
    }

    /** Инспектор APK: читающий анализ, показать отчёт. Если выбраны и патчи — продолжить после отчёта. */
    private fun runInspector() {
        val path = apkPath ?: return
        if (isInspecting) return
        isInspecting = true
        btnPatch.isEnabled = false

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 24)
            gravity = Gravity.CENTER
        }
        ll.addView(ProgressBar(this))
        ll.addView(TextView(this).apply {
            text = getString(R.string.inspector_title) + "…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })
        inspectorProgress = MaterialAlertDialogBuilder(this).setView(ll).setCancelable(false).create()
        inspectorProgress?.show()

        Thread {
            try {
                val report = ApkInspector.inspect(this, File(path))
                runOnUiThread {
                    inspectorProgress?.dismiss(); inspectorProgress = null
                    isInspecting = false
                    updatePatchButtonState()
                    showInspectorReport(report)
                }
            } catch (e: Exception) {
                Log.e(TAG, "inspector error", e)
                runOnUiThread {
                    inspectorProgress?.dismiss(); inspectorProgress = null
                    isInspecting = false
                    updatePatchButtonState()
                    Toast.makeText(this, getString(R.string.error_msg_plain, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Отметка в тексте кнопки: ☐ — не выбрано, ☑ — выбрано. */
    private fun refreshToggleText(btn: MaterialButton) {
        val base = btn.text.toString().removePrefix("☐ ").removePrefix("☑ ")
        btn.text = (if (btn.isChecked) "☑ " else "☐ ") + base
    }

    private fun otherPatchesSelected(): Boolean {
        return checkBoxGooglePlay.isChecked || checkBoxRemoveAds.isChecked ||
                checkBoxRemoveAnalytics.isChecked || checkBoxRemoveGPServices.isChecked ||
                checkBoxRemoveVpn.isChecked || checkBoxAllowScreenshots.isChecked || checkBoxRemoveInstallerCheck.isChecked ||
                checkBoxRemoveUpdate.isChecked ||
                checkBoxRemoveLocales.isChecked || checkBoxRemoveDpi.isChecked ||
                checkBoxRemoveLibs.isChecked || checkBoxOptimize.isChecked || customPatches.isNotEmpty()
    }

    private fun showPatchUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 4)
        }
        val etUrl = EditText(this).apply {
            hint = getString(R.string.patch_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val etSha = EditText(this).apply {
            hint = getString(R.string.patch_url_checksum_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        container.addView(etUrl)
        container.addView(etSha)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.patch_url_title)
            .setView(container)
            .setPositiveButton(R.string.patch_url_download, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = etUrl.text.toString().trim()
                if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
                    Toast.makeText(this, getString(R.string.patch_url_invalid_url), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (url.startsWith("http://", true)) {
                    Toast.makeText(this, getString(R.string.patch_url_http_warning), Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
                downloadPatchFromUrl(url, etSha.text.toString())
            }
        }
        dialog.show()
    }

    private fun downloadPatchFromUrl(rawUrl: String, rawSha: String) {
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.patch_url_downloading))
            setCancelable(false)
            show()
        }
        Thread {
            var temp: File? = null
            var connection: HttpURLConnection? = null
            try {
                val url = URL(rawUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "LazyModification/1.26")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw Exception("HTTP $code")
                val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                if (length > 256L * 1024 * 1024) throw Exception("ZIP больше 256 МБ")

                val dir = File(cacheDir, "url_patches").apply { mkdirs() }
                temp = File(dir, "patch_${System.currentTimeMillis()}.zip")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > 256L * 1024 * 1024) throw Exception("ZIP больше 256 МБ")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                val expectedSha = normalizeSha(rawSha)
                if (expectedSha.isNotEmpty() && expectedSha != actualSha) {
                    throw Exception(getString(R.string.patch_url_checksum_mismatch, expectedSha, actualSha))
                }
                val document = PatchFormat.read(temp!!)
                val info = packageManager.getPackageArchiveInfo(apkPath!!, 0)
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28)
                    info?.longVersionCode?.toInt() else info?.versionCode
                val compatibility = PatchCompatibility.check(
                    document, info?.packageName, info?.versionName, versionCode
                )
                runOnUiThread {
                    progress.dismiss()
                    showPatchUrlResult(temp!!, document, actualSha, expectedSha, compatibility)
                }
            } catch (t: Throwable) {
                temp?.delete()
                runOnUiThread {
                    progress.dismiss()
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.patch_url_title)
                        .setMessage(getString(R.string.patch_url_download_error, t.message ?: "?"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun showPatchUrlResult(
        file: File,
        document: PatchDocument,
        actualSha: String,
        expectedSha: String,
        compatibility: PatchCompatibility.Result
    ) {
        val version = buildString {
            if (document.versionNamePattern != "*") append(document.versionNamePattern)
            if (document.minVersionCode != null || document.maxVersionCode != null) {
                if (isNotEmpty()) append(" / ")
                append("code ")
                when {
                    document.minVersionCode != null && document.maxVersionCode != null ->
                        append("${document.minVersionCode}..${document.maxVersionCode}")
                    document.minVersionCode != null -> append(">= ${document.minVersionCode}")
                    else -> append("<= ${document.maxVersionCode}")
                }
            }
            if (isEmpty()) append("*")
        }
        val summary = getString(
            R.string.patch_url_summary,
            document.author.ifBlank { getString(R.string.patch_url_unknown) },
            document.packagePattern,
            version,
            document.rules.size
        )
        val message = buildString {
            append(summary)
            append("\n\nSHA-256: ").append(actualSha)
            if (expectedSha.isNotEmpty()) append("\n").append(getString(R.string.patch_url_checksum_ok))
            append("\n\n")
            append(if (compatibility.compatible) getString(R.string.patch_url_compatible)
                else getString(R.string.patch_url_incompatible, compatibility.message))
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.patch_url_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> file.delete() }
            .setOnCancelListener { file.delete() }
        if (compatibility.compatible) {
            builder.setPositiveButton(R.string.patch_url_add) { _, _ -> addDownloadedPatch(file) }
        } else {
            builder.setPositiveButton(R.string.patch_url_add_anyway) { _, _ -> addDownloadedPatch(file) }
        }
        builder.show()
    }

    private fun addDownloadedPatch(file: File) {
        try {
            val imported = PatchFiles.importFile(this, file)
            customPatches.add(imported.path)
            refreshCustomPatches()
            Toast.makeText(this, getString(R.string.patch_url_added), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.patch_url_title)
                .setMessage(getString(R.string.patch_url_download_error, t.message ?: "?"))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } finally {
            file.delete()
        }
    }

    private fun normalizeSha(value: String): String = value.trim().lowercase(Locale.US)
        .removePrefix("sha256:").replace(Regex("\\s+"), "")

    private fun showInspectorReport(report: String) {
        val tv = TextView(this).apply {
            text = report
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inspector_title)
            .setView(sv)
            .setPositiveButton(R.string.copy_report) { _, _ ->
                copyText(getString(R.string.inspector_title), report)
                Toast.makeText(this, getString(R.string.copy_done), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .create()
            .show()
    }

    private fun showSigningDialog() {
        pendingSignConfirm = { config -> startPatching(config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> startPatching(config) }
        )
    }

    private fun startKillFlow(path: String) {
        killApkPath = path
        pendingSignConfirm = { config -> startKillPatching(config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> startKillPatching(config) }
        )
    }

    private fun startKillPatching(config: SignatureConfig) {
        val path = killApkPath ?: return
        val serviceIntent = Intent(this, PatchService::class.java).apply {
            putExtra(PatchService.EXTRA_APK_PATH, path)
            putExtra(PatchService.EXTRA_PATCH_GOOGLE_PLAY, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_ADS, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_ANALYTICS, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_GP_SERVICES, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_VPN, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_INSTALLER_CHECK, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_UPDATE, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_SIGNATURE, true)
            putExtra(PatchService.EXTRA_TOOLS_KILL_ONLY, true)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_LOCALES, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_DPI, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_LIBS, false)
            putExtra(PatchService.EXTRA_PATCH_OPTIMIZE, false)
            putExtra(PatchService.EXTRA_NO_SIGN, config.noSign)
            putExtra(PatchService.EXTRA_SIGN_V1, config.signV1)
            putExtra(PatchService.EXTRA_SIGN_V2, config.signV2)
            putExtra(PatchService.EXTRA_SIGN_V3, config.signV3)
            putExtra(PatchService.EXTRA_CUSTOM_KEYSTORE_PATH, config.customKeystorePath)
            putExtra(PatchService.EXTRA_KEYSTORE_PASSWORD, config.keystorePassword)
            putExtra(PatchService.EXTRA_KEY_ALIAS, config.keyAlias)
            putExtra(PatchService.EXTRA_KEY_PASSWORD, config.keyPassword)
            putExtra(PatchService.EXTRA_KEYSTORE_TYPE, config.keystoreType)
        }
        try {
            startForegroundService(serviceIntent)
            startActivity(Intent(this, LogActivity::class.java).apply {
                putExtra("SOURCE_APK_PATH", path)
            })
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_msg_plain, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun startPatching(config: SignatureConfig) {
        val path = apkPath ?: return
        val serviceIntent = Intent(this, PatchService::class.java).apply {
            putExtra(PatchService.EXTRA_APK_PATH, path)
            putStringArrayListExtra(PatchService.EXTRA_CUSTOM_PATCHES, ArrayList(customPatches))
            putExtra(PatchService.EXTRA_PATCH_GOOGLE_PLAY, checkBoxGooglePlay.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_ADS, checkBoxRemoveAds.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_ANALYTICS, checkBoxRemoveAnalytics.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_GP_SERVICES, checkBoxRemoveGPServices.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_VPN, checkBoxRemoveVpn.isChecked)
            putExtra(PatchService.EXTRA_PATCH_ALLOW_SCREENSHOTS, checkBoxAllowScreenshots.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_INSTALLER_CHECK, checkBoxRemoveInstallerCheck.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_UPDATE, checkBoxRemoveUpdate.isChecked)

        putExtra(PatchService.EXTRA_PATCH_REMOVE_SIGNATURE, false)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_LOCALES, checkBoxRemoveLocales.isChecked)
            putStringArrayListExtra(PatchService.EXTRA_PATCH_REMOVE_LOCALES_LIST, ArrayList(selectedLocales))
            putExtra(PatchService.EXTRA_PATCH_REMOVE_DPI, checkBoxRemoveDpi.isChecked)
            putStringArrayListExtra(PatchService.EXTRA_PATCH_REMOVE_DPI_LIST, ArrayList(selectedDpis))
            putExtra(PatchService.EXTRA_PATCH_REMOVE_LIBS, checkBoxRemoveLibs.isChecked)
            putStringArrayListExtra(PatchService.EXTRA_PATCH_REMOVE_LIBS_LIST, ArrayList(selectedArchs))
            putExtra(PatchService.EXTRA_PATCH_OPTIMIZE, checkBoxOptimize.isChecked)
            // ✅ Параметры подписи
            putExtra(PatchService.EXTRA_NO_SIGN, config.noSign)
            putExtra(PatchService.EXTRA_SIGN_V1, config.signV1)
            putExtra(PatchService.EXTRA_SIGN_V2, config.signV2)
            putExtra(PatchService.EXTRA_SIGN_V3, config.signV3)
            // ✅ Пользовательский keystore
            putExtra(PatchService.EXTRA_CUSTOM_KEYSTORE_PATH, config.customKeystorePath)
            putExtra(PatchService.EXTRA_KEYSTORE_PASSWORD, config.keystorePassword)
            putExtra(PatchService.EXTRA_KEY_ALIAS, config.keyAlias)
            putExtra(PatchService.EXTRA_KEY_PASSWORD, config.keyPassword)
            putExtra(PatchService.EXTRA_KEYSTORE_TYPE, config.keystoreType)
        }
        try {
            startForegroundService(serviceIntent)
            startActivity(Intent(this, LogActivity::class.java).apply {
                putExtra("SOURCE_APK_PATH", path)
            })
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска", e)
            Toast.makeText(this, getString(R.string.error_msg_plain, e.message), Toast.LENGTH_LONG).show()
        }
    }
}
