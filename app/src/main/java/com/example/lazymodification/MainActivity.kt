package com.example.lazymodification

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.widget.PopupMenu
import android.widget.Toast
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.SaveFolderHelper
import com.google.android.material.button.MaterialButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnSettings: ImageButton
    private lateinit var btnApkOperations: MaterialButton
    private lateinit var btnEdit: MaterialButton
    private lateinit var btnInterface: MaterialButton
    private lateinit var btnCompare: MaterialButton
    private lateinit var btnPatch: MaterialButton
    private lateinit var btnExit: MaterialButton

    private var pendingV1 = true
    private var pendingV2 = true
    private var pendingV3 = true

    private val keystoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val file = SigningUtils.copyKeystoreToCache(this, uri)
            if (file != null) {
                SigningUtils.showKeystorePasswordDialog(this, file, pendingV1, pendingV2, pendingV3) { _ ->
                    Toast.makeText(this, getString(R.string.signature_saved), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.keystore_read_failed), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.keystore_not_selected), Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                SaveFolderHelper.saveUri(this, uri)
                Toast.makeText(
                    this,
                    getString(R.string.save_folder_set, SaveFolderHelper.getDisplayName(this) ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val pickApkForEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startActivity(Intent(this, EditApkActivity::class.java).apply {
                        putExtra(EditApkActivity.EXTRA_APK_PATH, path)
                    })
                } else {
                    Toast.makeText(this, getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickApkForInterfaceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startActivity(Intent(this, InterfaceModifierActivity::class.java).apply {
                        putExtra(InterfaceModifierActivity.EXTRA_APK_PATH, path)
                    })
                } else {
                    Toast.makeText(this, getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickApkForPatchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startActivity(Intent(this, PatchActivity::class.java).apply {
                        putExtra(PatchActivity.EXTRA_APK_PATH, path)
                    })
                } else {
                    Toast.makeText(this, getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_main)

        btnSettings = findViewById(R.id.btnSettings)
        btnApkOperations = findViewById(R.id.btnApkOperations)
        btnEdit = findViewById(R.id.btnEdit)
        btnInterface = findViewById(R.id.btnInterface)
        btnCompare = findViewById(R.id.btnCompare)
        btnPatch = findViewById(R.id.btnPatch)
        btnExit = findViewById(R.id.btnExit)

        setupSettingsButton()
        setupApkOperationsButton()
        setupEditButton()
        setupInterfaceButton()
        setupCompareButton()
        setupPatchButton()
        setupExitButton()
    }

    private fun setupSettingsButton() {
        btnSettings.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.menu_main_settings, popup.menu)
            forcePopupMenuIcons(popup)
            val themeItem = popup.menu.findItem(R.id.action_toggle_theme)
            themeItem.icon = ContextCompat.getDrawable(
                this,
                if (ThemeHelper.isDark(this)) R.drawable.ic_theme_dark else R.drawable.ic_theme_light
            )
            when (loadSortMode()) {
                FileBrowserActivity.SORT_DATE -> popup.menu.findItem(R.id.action_sort_date).isChecked = true
                FileBrowserActivity.SORT_SIZE -> popup.menu.findItem(R.id.action_sort_size).isChecked = true
                else -> popup.menu.findItem(R.id.action_sort_name).isChecked = true
            }
            when (LocaleHelper.currentLang()) {
                "en" -> popup.menu.findItem(R.id.action_lang_en).isChecked = true
                "bg" -> popup.menu.findItem(R.id.action_lang_bg).isChecked = true
                else -> popup.menu.findItem(R.id.action_lang_ru).isChecked = true
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_toggle_theme -> {
                        ThemeHelper.toggle(this)
                        recreate()
                        true
                    }
                    R.id.action_sort_name -> { saveSortMode(FileBrowserActivity.SORT_NAME); true }
                    R.id.action_sort_date -> { saveSortMode(FileBrowserActivity.SORT_DATE); true }
                    R.id.action_sort_size -> { saveSortMode(FileBrowserActivity.SORT_SIZE); true }
                    R.id.action_signature -> { showSigningSettings(); true }
                    R.id.action_lang_ru -> { LocaleHelper.setLang("ru"); recreate(); true }
                    R.id.action_lang_en -> { LocaleHelper.setLang("en"); recreate(); true }
                    R.id.action_lang_bg -> { LocaleHelper.setLang("bg"); recreate(); true }
                    R.id.action_choose_folder -> { folderPickerLauncher.launch(null); true }
                    R.id.action_default_folder -> {
                        SaveFolderHelper.clear(this)
                        Toast.makeText(this, getString(R.string.save_folder_reset), Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun loadSortMode(): String {
        return getSharedPreferences(FileBrowserActivity.PREF_SORT, MODE_PRIVATE)
            .getString(FileBrowserActivity.PREF_SORT_KEY, FileBrowserActivity.SORT_NAME)
            ?: FileBrowserActivity.SORT_NAME
    }

    private fun saveSortMode(mode: String) {
        getSharedPreferences(FileBrowserActivity.PREF_SORT, MODE_PRIVATE)
            .edit().putString(FileBrowserActivity.PREF_SORT_KEY, mode).apply()
    }

    private fun forcePopupMenuIcons(popup: PopupMenu) {
        try {
            val field = popup.javaClass.getDeclaredField("mPopup")
            field.isAccessible = true
            val helper = field.get(popup)
            val clazz = Class.forName("androidx.appcompat.view.menu.MenuPopupHelper")
            val method = clazz.getMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
            method.invoke(helper, true)
        } catch (e: Exception) {
            // ignore: icons may stay hidden on some versions
        }
    }

    private fun showSigningSettings() {
        SigningUtils.showSigningDialog(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { _ ->
                Toast.makeText(this, getString(R.string.signature_saved), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun setupApkOperationsButton() {
        btnApkOperations.setOnClickListener {
            startActivity(Intent(this, ApkOperationsActivity::class.java))
        }
    }

    private fun setupEditButton() {
        btnEdit.setOnClickListener {
            pickApkForEditLauncher.launch(
                Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
                }
            )
        }
    }

    private fun setupInterfaceButton() {
        btnInterface.setOnClickListener {
            pickApkForInterfaceLauncher.launch(
                Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
                }
            )
        }
    }

    private fun setupCompareButton() {
        btnCompare.setOnClickListener {
            val axmlCheck = CheckBox(this).apply {
                text = getString(R.string.compare_axml)
                isChecked = false
            }
            val dexCheck = CheckBox(this).apply {
                text = getString(R.string.compare_dex)
                isChecked = true
            }
            axmlCheck.setOnCheckedChangeListener { _, checked -> if (checked) dexCheck.isChecked = false }
            dexCheck.setOnCheckedChangeListener { _, checked -> if (checked) axmlCheck.isChecked = false }
            val pad = (24 * resources.displayMetrics.density).toInt()
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(axmlCheck)
                addView(dexCheck)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.compare_apk)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val mode = when {
                        axmlCheck.isChecked -> "axml"
                        dexCheck.isChecked -> "dex"
                        else -> null
                    }
                    if (mode == null) {
                        Toast.makeText(this, getString(R.string.select_option), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    startActivity(Intent(this, CompareApkActivity::class.java).apply {
                        putExtra(CompareApkActivity.EXTRA_MODE, mode)
                    })
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupPatchButton() {
        btnPatch.setOnClickListener {
            pickApkForPatchLauncher.launch(
                Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
                }
            )
        }
    }

    private fun setupExitButton() {
        btnExit.setOnClickListener {
            finishAffinity()
        }
    }
}
