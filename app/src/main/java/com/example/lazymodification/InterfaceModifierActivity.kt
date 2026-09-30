package com.example.lazymodification

import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.FileSaver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.lazymodification.utils.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File

class InterfaceModifierActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.INTERFACE_APK_PATH"
        const val EXTRA_MODE = "com.example.lazymodification.INTERFACE_MODE"
        const val MODE_MENU = "menu"
        const val MODE_MARQUEE = "marquee"
        private const val TAG = "InterfaceModifier"
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvFileName: TextView
    private lateinit var cbRemoveMenu: CheckBox
    private lateinit var cbMarquee: CheckBox
    private lateinit var tvLauncherInfo: TextView
    private lateinit var btnApply: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var scrollMenuItems: ScrollView
    private lateinit var containerMenuItems: LinearLayout
    private lateinit var btnAddMenuItem: Button
    private lateinit var llProgressContainer: LinearLayout

    private var apkPath: String? = null
    private var isProcessing = false
    private var finalApkFile: File? = null
    private var finalPackageName: String? = null

    private var launcherClassName: String? = null
    private var isSearchingLauncher = false

    private var pendingSignConfirm: ((SignatureConfig) -> Unit)? = null
    private var pendingV1 = true
    private var pendingV2 = true
    private var pendingV3 = true
    private var pendingInstallAfterUninstall = false

    private var marqueeConfig = MarqueeConfig()

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
        setContentView(R.layout.activity_interface_modifier)

        tvTitle = findViewById(R.id.tvTitle)
        tvFileName = findViewById(R.id.tvFileName)
        cbRemoveMenu = findViewById(R.id.cbRemoveMenu)
        cbMarquee = findViewById(R.id.cbMarquee)
        tvLauncherInfo = findViewById(R.id.tvLauncherInfo)
        btnApply = findViewById(R.id.btnApply)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        scrollMenuItems = findViewById(R.id.scrollMenuItems)
        containerMenuItems = findViewById(R.id.containerMenuItems)
        btnAddMenuItem = findViewById(R.id.btnAddMenuItem)
        llProgressContainer = findViewById(R.id.llProgressContainer)

        llProgressContainer.visibility = View.GONE

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

        cbRemoveMenu.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                scrollMenuItems.visibility = View.VISIBLE
                btnAddMenuItem.visibility = View.VISIBLE
                if (containerMenuItems.childCount == 0) addMenuItemInput()
            } else {
                scrollMenuItems.visibility = View.GONE
                btnAddMenuItem.visibility = View.GONE
                containerMenuItems.removeAllViews()
            }
            updateApplyButton()
        }

        cbMarquee.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (launcherClassName == null && !isSearchingLauncher) {
                    searchLauncherActivity()
                }
                showMarqueeConfigDialog()
            } else {
                tvLauncherInfo.visibility = View.GONE
            }
            updateApplyButton()
        }

        btnAddMenuItem.setOnClickListener { addMenuItemInput() }

        btnApply.setOnClickListener {
            val itemsToRemove = if (cbRemoveMenu.isChecked) getMenuItemsList() else emptyList()
            if (cbRemoveMenu.isChecked && itemsToRemove.isEmpty()) {
                Toast.makeText(this, getString(R.string.enter_at_least_one_menu_item), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!cbRemoveMenu.isChecked && !cbMarquee.isChecked) {
                Toast.makeText(this, getString(R.string.choose_at_least_one_option), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (cbMarquee.isChecked && launcherClassName == null) {
                Toast.makeText(this, getString(R.string.launcher_not_found), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            showSigningDialog(itemsToRemove)
        }

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_MENU -> {
                tvTitle.text = getString(R.string.remove_menu_items)
                cbMarquee.visibility = View.GONE
                cbRemoveMenu.isChecked = true
                cbRemoveMenu.visibility = View.GONE
            }
            MODE_MARQUEE -> {
                tvTitle.text = getString(R.string.insert_marquee)
                cbRemoveMenu.visibility = View.GONE
                cbMarquee.isChecked = true
                cbMarquee.visibility = View.GONE
            }
        }
    }

    private fun searchLauncherActivity() {
        isSearchingLauncher = true
        tvLauncherInfo.visibility = View.VISIBLE
        tvLauncherInfo.text = getString(R.string.status_searching_launcher)
        btnApply.isEnabled = false

        Thread {
            try {
                val name = MarqueeInjector.findLauncherActivity(File(apkPath!!)) { status ->
                    runOnUiThread { tvLauncherInfo.text = status }
                }
                runOnUiThread {
                    launcherClassName = name
                    isSearchingLauncher = false
                    tvLauncherInfo.text = getString(R.string.launcher_found, name)
                    updateApplyButton()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка поиска LAUNCHER", e)
                runOnUiThread {
                    isSearchingLauncher = false
                    tvLauncherInfo.text = "❌ ${e.message}"
                    cbMarquee.isChecked = false
                    updateApplyButton()
                }
            }
        }.start()
    }

    private fun updateApplyButton() {
        val hasMenu = cbRemoveMenu.isChecked && getMenuItemsList().isNotEmpty()
        val hasMarquee = cbMarquee.isChecked && launcherClassName != null
        val enabled = (hasMenu || hasMarquee) && !isProcessing
        btnApply.isEnabled = enabled
        btnApply.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun addMenuItemInput() {
        val cardView = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            radius = 8f
            elevation = 2f
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 8, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val editText = EditText(this).apply {
            hint = getString(R.string.enter_menu_item_text)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setPadding(0, 8, 8, 8)
        }
        val btnRemove = MaterialButton(this).apply {
            text = "✕"
            setPadding(12, 8, 12, 8)
            setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            background = null
            setOnClickListener {
                containerMenuItems.removeView(cardView)
                updateApplyButton()
            }
        }
        container.addView(editText)
        container.addView(btnRemove)
        cardView.addView(container)
        containerMenuItems.addView(cardView)

        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateApplyButton() }
        })

        editText.requestFocus()
    }

    private fun getMenuItemsList(): List<String> {
        val items = mutableListOf<String>()
        for (i in 0 until containerMenuItems.childCount) {
            val cardView = containerMenuItems.getChildAt(i) as? MaterialCardView ?: continue
            val container = cardView.getChildAt(0) as? LinearLayout ?: continue
            val editText = container.getChildAt(0) as? EditText ?: continue
            val text = editText.text.toString().trim()
            if (text.isNotEmpty()) items.add(text)
        }
        return items
    }

    private fun showMarqueeConfigDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        val etText = EditText(this).apply {
            hint = getString(R.string.marquee_text)
            setText(marqueeConfig.text)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }
        }

        // Цвета для выпадающего списка
        val colorNames = arrayOf(
            getString(R.string.white), getString(R.string.red), getString(R.string.orange), getString(R.string.yellow), getString(R.string.green),
            getString(R.string.light_blue), getString(R.string.blue), getString(R.string.purple), getString(R.string.pink), getString(R.string.gray), getString(R.string.black)
        )
        val colorValues = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFFFFA500.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF69B4.toInt(), 0xFF808080.toInt(), 0xFF000000.toInt()
        )

        val tvColor = TextView(this).apply {
            text = getString(R.string.text_color)
            textSize = 16f
            setPadding(0, 8, 0, 4)
        }
        val spinnerColor = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }
            adapter = ArrayAdapter(this@InterfaceModifierActivity, android.R.layout.simple_spinner_item, colorNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
        // Выставляем текущий цвет (по совпадению, иначе белый)
        val currentIdx = colorValues.indexOfFirst { it == marqueeConfig.color }.let { if (it >= 0) it else 0 }
        spinnerColor.setSelection(currentIdx)

        val etSize = EditText(this).apply {
            hint = getString(R.string.text_size_sp)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(marqueeConfig.textSizeDp.toString())
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }
        }

        val tvGravity = TextView(this).apply {
            text = getString(R.string.position)
            textSize = 16f
            setPadding(0, 8, 0, 4)
        }
        val rgGravity = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbTop = RadioButton(this).apply { id = View.generateViewId(); text = getString(R.string.top) }
        val rbCenter = RadioButton(this).apply { id = View.generateViewId(); text = getString(R.string.middle) }
        val rbBottom = RadioButton(this).apply { id = View.generateViewId(); text = getString(R.string.bottom) }
        rgGravity.addView(rbTop); rgGravity.addView(rbCenter); rgGravity.addView(rbBottom)
        when (marqueeConfig.gravity) {
            MarqueeConfig.GRAVITY_CENTER -> rgGravity.check(rbCenter.id)
            MarqueeConfig.GRAVITY_BOTTOM -> rgGravity.check(rbBottom.id)
            else -> rgGravity.check(rbTop.id)
        }

        val tvRepeat = TextView(this).apply {
            text = getString(R.string.repetitions)
            textSize = 16f
            setPadding(0, 8, 0, 4)
        }
        val rgRepeat = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rb3 = RadioButton(this).apply { id = View.generateViewId(); text = getString(R.string.three_times) }
        val rbInf = RadioButton(this).apply { id = View.generateViewId(); text = getString(R.string.infinite) }
        rgRepeat.addView(rb3); rgRepeat.addView(rbInf)
        if (marqueeConfig.repeatLimit == MarqueeConfig.REPEAT_INFINITE) rgRepeat.check(rbInf.id) else rgRepeat.check(rb3.id)

        container.addView(etText)
        container.addView(tvColor)
        container.addView(spinnerColor)
        container.addView(etSize)
        container.addView(tvGravity)
        container.addView(rgGravity)
        container.addView(tvRepeat)
        container.addView(rgRepeat)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.marquee_settings))
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val text = etText.text.toString().trim().ifEmpty { marqueeConfig.text }
                val color = colorValues[spinnerColor.selectedItemPosition]
                val size = etSize.text.toString().toIntOrNull() ?: marqueeConfig.textSizeDp
                val gravity = when (rgGravity.checkedRadioButtonId) {
                    rbCenter.id -> MarqueeConfig.GRAVITY_CENTER
                    rbBottom.id -> MarqueeConfig.GRAVITY_BOTTOM
                    else -> MarqueeConfig.GRAVITY_TOP
                }
                val repeat = if (rgRepeat.checkedRadioButtonId == rbInf.id) MarqueeConfig.REPEAT_INFINITE else MarqueeConfig.REPEAT_3

                marqueeConfig = MarqueeConfig(text = text, color = color, textSizeDp = size, gravity = gravity, repeatLimit = repeat)
                tvLauncherInfo.visibility = View.VISIBLE
                tvLauncherInfo.text = getString(R.string.marquee_settings_saved, text.take(20))
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                cbMarquee.isChecked = false
            }
            .show()
    }

    private fun showSigningDialog(itemsToRemove: List<String>) {
        pendingSignConfirm = { config -> startModification(config, itemsToRemove) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> startModification(config, itemsToRemove) }
        )
    }

    private fun startModification(config: SignatureConfig, itemsToRemove: List<String>) {
        if (isProcessing) return
        val inputFile = File(apkPath!!)
        isProcessing = true
        btnApply.isEnabled = false
        btnApply.visibility = View.GONE
        scrollMenuItems.visibility = View.GONE
        llProgressContainer.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_preparing)

        Thread {
            var currentFile = inputFile
            var isFirst = true
            try {
                if (cbMarquee.isChecked && launcherClassName != null) {
                    val tempOutput = File(cacheDir, "marquee_${System.currentTimeMillis()}.apk")
                    runOnUiThread { tvStatus.text = getString(R.string.status_insert_marquee) }

                    MarqueeInjector.injectMarquee(
                        inputApk = currentFile,
                        outputApk = tempOutput,
                        launcherClass = launcherClassName!!,
                        config = marqueeConfig,
                        onStatus = { status -> runOnUiThread { tvStatus.text = status } }
                    )

                    if (!tempOutput.exists() || tempOutput.length() == 0L) {
                        throw Exception("Ошибка инъекции бегущей строки")
                    }
                    if (!isFirst) currentFile.delete()
                    currentFile = tempOutput
                    isFirst = false
                }

                for (itemText in itemsToRemove) {
                    // (убрано — теперь обрабатываем пакетно)
                }

                runOnUiThread { tvStatus.text = getString(R.string.status_removing_menu) }
                if (itemsToRemove.isNotEmpty()) {
                    val tempOutput = File(cacheDir, "menu_${System.currentTimeMillis()}.apk")
                    InterfaceModifier.processApkBatch(
                        inputFile = currentFile,
                        outputFile = tempOutput,
                        menuItems = itemsToRemove,
                        onStatus = { status -> runOnUiThread { tvStatus.text = status } },
                        helperDex = runCatching { assets.open("preffix.dex").use { it.readBytes() } }.getOrNull(),
                    )

                    if (!tempOutput.exists() || tempOutput.length() == 0L) {
                        throw Exception("Ошибка обработки пунктов меню")
                    }
                    if (!isFirst) currentFile.delete()
                    currentFile = tempOutput
                    isFirst = false
                }

                runOnUiThread { tvStatus.text = getString(R.string.status_zipalign) }
                val alignedApk = File(cacheDir, "interface_aligned.apk")
                try {
                    ApkUtils.zipAlign(currentFile, alignedApk)
                    if (currentFile != inputFile) currentFile.delete()
                    currentFile = alignedApk
                } catch (e: Exception) {
                    Log.w(TAG, "ZipAlign не удался, продолжаем без него: ${e.message}")
                }

                runOnUiThread { tvStatus.text = getString(R.string.status_signing_apk) }
                val finalFile = if (config.noSign) {
                    val preservedApk = File(cacheDir, "interface_preserved.apk")
                    SigningUtils.preserveOriginalSignature(inputFile, currentFile, preservedApk)
                    if (currentFile != inputFile) currentFile.delete()
                    preservedApk
                } else {
                    val signedApk = File(cacheDir, "interface_signed.apk")
                    SigningUtils.signApk(this, currentFile, signedApk, config)
                    if (currentFile != inputFile) currentFile.delete()
                    signedApk
                }

                finalPackageName = getPackageNameFromApk(finalFile)
                finalApkFile = finalFile

                val outputName = "${inputFile.nameWithoutExtension}_interface_modified.apk"
                FileSaver.saveApk(this, finalFile, outputName)

                val signInfo = if (config.noSign) getString(R.string.original_signature_preserved)
                else "🔐 V1=${config.signV1}, V2=${config.signV2}, V3=${config.signV3}${if (config.useCustom) " (своя)" else ""}"

                runOnUiThread {
                    llProgressContainer.visibility = View.GONE
                    scrollMenuItems.visibility = if (cbRemoveMenu.isChecked) View.VISIBLE else View.GONE
                    btnApply.visibility = View.VISIBLE
                    btnApply.isEnabled = true
                    isProcessing = false
                    tvStatus.text = getString(R.string.done)
                    Toast.makeText(
                        this,
                        getString(R.string.done_success, SaveFolderHelper.getLocationLabel(this), outputName, signInfo),
                        Toast.LENGTH_LONG
                    ).show()
                    showInstallDialog()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка модификации", e)
                runOnUiThread {
                    llProgressContainer.visibility = View.GONE
                    scrollMenuItems.visibility = if (cbRemoveMenu.isChecked) View.VISIBLE else View.GONE
                    btnApply.visibility = View.VISIBLE
                    btnApply.isEnabled = true
                    isProcessing = false
                    val errText = if (e is InterfaceModifier.UnsupportedAppType) getString(e.resId) else (e.message ?: "")
                    tvStatus.text = getString(R.string.error_with_msg, errText)
                    // Диалог вместо Toast: висит, пока пользователь сам не закроет — текст успеешь прочитать
                    AlertDialog.Builder(this)
                        .setTitle(R.string.error)
                        .setMessage(errText)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun showInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.install_question))
            .setMessage(getString(R.string.install_modified_question))
            .setPositiveButton(getString(R.string.install)) { _, _ -> handleInstallClick() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun handleInstallClick() {
        val apkPath = finalApkFile?.absolutePath
        if (apkPath == null) {
            Toast.makeText(this, getString(R.string.apk_file_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Toast.makeText(this, getString(R.string.apk_file_not_exists), Toast.LENGTH_SHORT).show()
            return
        }
        val effectivePkg = finalPackageName ?: getPackageNameFromApk(apkFile)
        if (effectivePkg == null) { proceedWithInstall(); return }
        finalPackageName = effectivePkg
        if (!isAppInstalled(effectivePkg)) { proceedWithInstall(); return }

        val installedSig = getInstalledAppSignature(effectivePkg)
        val apkSig = getApkFileSignature(apkPath)
        if (installedSig == null || apkSig == null) { proceedWithInstall(); return }
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
        val apkPath = finalApkFile?.absolutePath ?: return
        installApk(File(apkPath))
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
        } catch (e: Exception) { pkgName }
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
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun getPackageNameFromApk(apkFile: File): String? = try {
        packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
    } catch (e: Exception) { null }

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
    } catch (e: Exception) { null }

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
    } catch (e: Exception) { null }

}
