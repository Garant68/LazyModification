package com.example.lazymodification

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.AxmlComparer
import com.example.lazymodification.utils.DexComparer
import java.io.File

class CompareApkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "compare_mode"
    }

    private var apk1: File? = null
    private var apk2: File? = null
    private var ignoreDebug = true
    private var ignoreNop = true
    private var ignoreAdCalls = false
    private var ignoreAnalytics = false
    private var mode = "dex"

    private val pickApk1 = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            apk1 = path?.let { File(it) }
            if (apk1 == null) { finish(); return@registerForActivityResult }
            selectSecondApk()
        } else {
            finish()
        }
    }

    private val pickApk2 = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            apk2 = path?.let { File(it) }
            if (apk2 == null) { finish(); return@registerForActivityResult }
            if (mode == "dex") showOptionsDialog() else runCompare()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare_apk)
        mode = intent.getStringExtra(EXTRA_MODE) ?: "dex"
        selectFirstApk()
    }

    private fun selectFirstApk() {
        pickApk1.launch(Intent(this, FileBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
        })
    }

    private fun selectSecondApk() {
        pickApk2.launch(Intent(this, FileBrowserActivity::class.java).apply {
            putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
        })
    }

    private fun showOptionsDialog() {
        val cbDebug = CheckBox(this).apply {
            text = getString(R.string.ignore_debug_info)
            isChecked = ignoreDebug
        }
        val cbNop = CheckBox(this).apply {
            text = getString(R.string.ignore_nop)
            isChecked = ignoreNop
        }
        val cbAds = CheckBox(this).apply {
            text = getString(R.string.ignore_ad_calls)
            isChecked = ignoreAdCalls
        }
        val cbAnalytics = CheckBox(this).apply {
            text = getString(R.string.ignore_analytics)
            isChecked = ignoreAnalytics
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(cbDebug)
            addView(cbNop)
            addView(cbAds)
            addView(cbAnalytics)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compare_options)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ignoreDebug = cbDebug.isChecked
                ignoreNop = cbNop.isChecked
                ignoreAdCalls = cbAds.isChecked
                ignoreAnalytics = cbAnalytics.isChecked
                runCompare()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun runCompare() {
        val a = apk1 ?: return
        val b = apk2 ?: return
        val status = findViewById<TextView>(R.id.tvCompareStatus)
        val progress = findViewById<ProgressBar>(R.id.progressCompare)
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.comparing_dex)

        Thread {
            try {
                val items = if (mode == "axml") {
                    AxmlComparer.compare(a, b).map { CompareItem(it.type, it.kind, true) }
                } else {
                    val workDir = File(cacheDir, "compare_dex")
                    DexComparer.compare(a, b, workDir, ignoreDebug, ignoreNop, ignoreAdCalls, ignoreAnalytics).map { CompareItem(it.type, it.kind, false) }
                }
                val sorted = items.sortedBy { it.type }
                runOnUiThread { progress.visibility = View.GONE; showList(sorted, a, b) }
            } catch (e: Exception) {
                runOnUiThread { progress.visibility = View.GONE; status.text = getString(R.string.error_with_msg, e.message) }
            }
        }.start()
    }

    private fun showList(items: List<CompareItem>, a: File, b: File) {
        val status = findViewById<TextView>(R.id.tvCompareStatus)
        val listView = findViewById<ListView>(R.id.lvChangedFiles)

        if (items.isEmpty()) {
            status.text = getString(R.string.compare_no_differences)
            return
        }

        status.text = getString(R.string.compare_changed_files, items.size)

        listView.adapter = ChangedFileAdapter(this, items)
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            startActivity(Intent(this, CompareFileActivity::class.java).apply {
                putExtra(CompareFileActivity.EXTRA_APK1, a.absolutePath)
                putExtra(CompareFileActivity.EXTRA_APK2, b.absolutePath)
                putExtra(CompareFileActivity.EXTRA_CLASS, item.type)
                putExtra(CompareFileActivity.EXTRA_IGNORE_DEBUG, ignoreDebug)
                putExtra(CompareFileActivity.EXTRA_KIND, if (item.isXml) "xml" else "dex")
            })
        }
    }

    private data class CompareItem(
        val type: String,
        val kind: DexComparer.ChangeKind,
        val isXml: Boolean
    )

    private class ChangedFileAdapter(
        context: Context,
        items: List<CompareItem>
    ) : ArrayAdapter<CompareItem>(context, 0, items) {

        private val green = 0xFF4CAF50.toInt()
        private val red = 0xFFF44336.toInt()
        private val blue = 0xFF2196F3.toInt()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_compare_file, parent, false)
            val item = getItem(position)!!
            val marker = view.findViewById<TextView>(R.id.tvMarker)
            val name = view.findViewById<TextView>(R.id.tvName)
            when (item.kind) {
                DexComparer.ChangeKind.ADDED -> {
                    marker.text = "+"
                    marker.setTextColor(green)
                }
                DexComparer.ChangeKind.REMOVED -> {
                    marker.text = "-"
                    marker.setTextColor(red)
                }
                DexComparer.ChangeKind.CHANGED -> {
                    marker.text = "~"
                    marker.setTextColor(blue)
                }
            }
            name.text = if (item.isXml) item.type else toDotted(item.type)
            return view
        }

        private fun toDotted(type: String): String {
            var s = type
            if (s.startsWith("L")) s = s.substring(1)
            if (s.endsWith(";")) s = s.substring(0, s.length - 1)
            return s.replace('/', '.')
        }
    }
}
