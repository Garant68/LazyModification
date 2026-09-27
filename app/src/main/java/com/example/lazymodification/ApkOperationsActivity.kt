package com.example.lazymodification

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File

class ApkOperationsActivity : AppCompatActivity() {

    private val pickApksLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startActivity(Intent(this, ConversionActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_SELECTED_PATH, path)
                    })
                } else {
                    Toast.makeText(this, getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val pickApkForSplitLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startActivity(Intent(this, SplitApkActivity::class.java).apply {
                        putExtra(SplitApkActivity.EXTRA_APK_PATH, path)
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
        setContentView(R.layout.activity_apk_operations)

        findViewById<MaterialButton>(R.id.btnConvert).setOnClickListener {
            pickApksLauncher.launch(
                Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APKS_XAPK)
                }
            )
        }

        findViewById<MaterialButton>(R.id.btnExtract).setOnClickListener {
            startActivity(Intent(this, ExtractActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnSplit).setOnClickListener {
            pickApkForSplitLauncher.launch(
                Intent(this, FileBrowserActivity::class.java).apply {
                    putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
                }
            )
        }

        findViewById<MaterialButton>(R.id.btnClone).setOnClickListener {
            startActivity(Intent(this, CloneFileBrowserActivity::class.java))
        }
    }
}
