package com.example.lazymodification

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.FileSaver
import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SignatureCreator
import com.example.lazymodification.utils.SigningUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Форма «Инструменты»: «Редактор Dex» и «Сравнить APK».
 */
class ToolsActivity : AppCompatActivity() {


    private val pickApkForDexEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startActivity(Intent(this, DexEditorActivity::class.java).apply {
                        putExtra(DexEditorActivity.EXTRA_APK_PATH, path)
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
        setContentView(R.layout.activity_tools)


        findViewById<MaterialButton>(R.id.btnDexEditor).setOnClickListener {
            pickApkForDexEditorLauncher.launch(Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
            })
        }

        findViewById<MaterialButton>(R.id.btnCompare).setOnClickListener {
            showCompareDialog()
        }
        findViewById<MaterialButton>(R.id.btnPatchEditor).setOnClickListener {
            startActivity(Intent(this, PatchEditorActivity::class.java))
        }
    }

    // ============================================================
    // СРАВНЕНИЕ APK (выбор режима: Axml / Dex)
    // ============================================================
    private fun showCompareDialog() {
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