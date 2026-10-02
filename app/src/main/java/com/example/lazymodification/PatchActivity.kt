package com.example.lazymodification

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
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
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkModule
import java.io.File

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
    private lateinit var checkBoxRemoveInstallerCheck: MaterialButton
    private lateinit var checkBoxRemoveUpdate: MaterialButton
    private lateinit var checkBoxRemoveLocales: MaterialButton
    private lateinit var checkBoxRemoveDpi: MaterialButton
    private lateinit var checkBoxRemoveLibs: MaterialButton
    private lateinit var checkBoxOptimize: MaterialButton
    private lateinit var btnInspectorApk: MaterialButton
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
        checkBoxRemoveInstallerCheck = findViewById(R.id.checkBoxRemoveInstallerCheck)
        checkBoxRemoveUpdate = findViewById(R.id.checkBoxRemoveUpdate)
        checkBoxRemoveLocales = findViewById(R.id.checkBoxRemoveLocales)
        checkBoxRemoveDpi = findViewById(R.id.checkBoxRemoveDpi)
        checkBoxRemoveLibs = findViewById(R.id.checkBoxRemoveLibs)
        checkBoxOptimize = findViewById(R.id.checkBoxOptimize)
        btnInspectorApk = findViewById(R.id.btnInspectorApk)
        btnPatch = findViewById(R.id.btnPatch)

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
        btnPatch.setOnClickListener {
            if (otherPatchesSelected()) {
                showSigningDialog()
            }
        }
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
        checkBoxRemoveInstallerCheck.setToggleCheckedStateOnClick(true)
        checkBoxRemoveUpdate.setToggleCheckedStateOnClick(true)
        checkBoxRemoveLocales.setToggleCheckedStateOnClick(true)
        checkBoxRemoveDpi.setToggleCheckedStateOnClick(true)
        checkBoxRemoveLibs.setToggleCheckedStateOnClick(true)
        checkBoxOptimize.setToggleCheckedStateOnClick(true)

        for (b in listOf(
            checkBoxGooglePlay, checkBoxRemoveAds, checkBoxRemoveAnalytics, checkBoxRemoveGPServices,
            checkBoxRemoveVpn, checkBoxRemoveInstallerCheck, checkBoxRemoveUpdate, checkBoxRemoveLocales,
            checkBoxRemoveDpi, checkBoxRemoveLibs, checkBoxOptimize
        )) {
            refreshToggleText(b)
        }

        btnInspectorApk.setOnClickListener { runInspector() }
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
                checkBoxRemoveVpn.isChecked || checkBoxRemoveInstallerCheck.isChecked || checkBoxRemoveUpdate.isChecked || checkBoxRemoveLocales.isChecked ||
                checkBoxRemoveDpi.isChecked || checkBoxRemoveLibs.isChecked || checkBoxOptimize.isChecked
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
        dialog = AlertDialog.Builder(this)
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
        dialog = AlertDialog.Builder(this)
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
        dialog = AlertDialog.Builder(this)
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
        inspectorProgress = AlertDialog.Builder(this).setView(ll).setCancelable(false).create()
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
                checkBoxRemoveVpn.isChecked || checkBoxRemoveInstallerCheck.isChecked ||
                checkBoxRemoveUpdate.isChecked ||
                checkBoxRemoveLocales.isChecked || checkBoxRemoveDpi.isChecked ||
                checkBoxRemoveLibs.isChecked || checkBoxOptimize.isChecked
    }

    private fun showInspectorReport(report: String) {
        val tv = TextView(this).apply {
            text = report
            textSize = 14f
            setPadding(48, 24, 48, 24)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.inspector_title)
            .setView(sv)
            .setPositiveButton(android.R.string.ok, null)
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

    private fun startPatching(config: SignatureConfig) {
        val path = apkPath ?: return
        val serviceIntent = Intent(this, PatchService::class.java).apply {
            putExtra(PatchService.EXTRA_APK_PATH, path)
            putExtra(PatchService.EXTRA_PATCH_GOOGLE_PLAY, checkBoxGooglePlay.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_ADS, checkBoxRemoveAds.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_ANALYTICS, checkBoxRemoveAnalytics.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_GP_SERVICES, checkBoxRemoveGPServices.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_VPN, checkBoxRemoveVpn.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_INSTALLER_CHECK, checkBoxRemoveInstallerCheck.isChecked)
            putExtra(PatchService.EXTRA_PATCH_REMOVE_UPDATE, checkBoxRemoveUpdate.isChecked)
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
