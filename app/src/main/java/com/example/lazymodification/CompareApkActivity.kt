package com.example.lazymodification

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import com.example.lazymodification.utils.FileSaver
import com.example.lazymodification.utils.patches.LmPatchContainer
import com.example.lazymodification.utils.patches.MethodPatchGenerator
import com.example.lazymodification.utils.patches.PatchFormat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class CompareApkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "compare_mode"
    }

    private var apk1: File? = null
    private var apk2: File? = null
    private var changedCount = 0
    private var changedItems: List<CompareItem> = emptyList()
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

        // 🔆 Экран не гаснет во время сравнения
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mode = intent.getStringExtra(EXTRA_MODE) ?: "dex"
        findViewById<MaterialButton>(R.id.btnMakePatch).apply {
            visibility = if (mode == "dex") View.VISIBLE else View.GONE
            setOnClickListener { confirmPatch() }
        }
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
        findViewById<MaterialButton>(R.id.btnMakePatch).isEnabled = false
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
        changedCount = items.size
        changedItems = items
        findViewById<MaterialButton>(R.id.btnMakePatch).isEnabled = true

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

    private fun confirmPatch() {
        val base = apk1 ?: return
        val updated = apk2 ?: return
        val verifyBox = CheckBox(this).apply {
            text = getString(R.string.patch_generate_verify)
            isChecked = false
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(verifyBox)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.patch_generate_title)
            .setMessage(getString(R.string.patch_generate_message, base.name, updated.name))
            .setView(container)
            .setPositiveButton(R.string.patch_generate_ok) { _, _ -> makePatch(verifyBox.isChecked) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun makePatch(verifyPatch: Boolean) {
        val base = apk1 ?: return
        val updated = apk2 ?: return
        val status = findViewById<TextView>(R.id.tvCompareStatus)
        val progress = findViewById<ProgressBar>(R.id.progressCompare)
        val button = findViewById<MaterialButton>(R.id.btnMakePatch)
        progress.visibility = View.VISIBLE
        button.isEnabled = false
        status.text = getString(R.string.patch_generate_progress)
        val work = File(cacheDir, "patch_gen")
        // Сравнение уже нашло расхождения: генератору не нужно сканировать APK повторно.
        val snapshot = if (!ignoreAdCalls && !ignoreAnalytics) {
            changedItems.associate { it.type to it.kind }
        } else null
        Thread {
            try {
                val author = getString(R.string.app_name)
                fun stage(fraction: Int, text: String) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = getString(R.string.patch_generate_stage, fraction, text)
                        }
                    }
                }
                val budget = if (verifyPatch) 70 else 96
                var result = MethodPatchGenerator.generate(base, updated, work, author, snapshot) { p, s ->
                    stage((p * budget).toInt(), s)
                }
                var zip = File(work, "generated_patch.zip")
                PatchFormat.writeZip(zip, result.text, result.payloads)
                PatchFormat.read(zip)
                var mismatches = 0
                if (verifyPatch) {
                    var remaining = MethodPatchGenerator.remainingTypes(base, zip, updated, work) { p, s ->
                        stage((70 + p * 18).toInt(), s)
                    }
                    if (remaining.isNotEmpty()) {
                        stage(90, getString(R.string.patch_generate_retry, remaining.size))
                        result = MethodPatchGenerator.generate(base, updated, work, author, snapshot, remaining) { p, s ->
                            stage((90 + p * 4).toInt(), s)
                        }
                        zip = File(work, "generated_patch.zip")
                        PatchFormat.writeZip(zip, result.text, result.payloads)
                        remaining = MethodPatchGenerator.remainingTypes(base, zip, updated, work) { p, s ->
                            stage((94 + p * 4).toInt(), s)
                        }
                    }
                    mismatches = remaining.size
                }
                stage(99, getString(R.string.patch_generate_saving))
                val lmPatch = File(work, "generated_patch.lmpatch")
                LmPatchContainer.write(lmPatch, result, zip)
                val saved = FileSaver.savePatch(this, lmPatch, patchFileName(result))
                val methodRules = result.methodRules
                val fileRules = result.fileRules
                val removedRules = result.removedRules
                val checked = verifyPatch
                val left = mismatches
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progress.visibility = View.GONE
                    button.isEnabled = true
                    status.text = getString(R.string.compare_changed_files, changedCount)
                    showPatchResult(saved, methodRules, fileRules, removedRules, checked, left)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progress.visibility = View.GONE
                    button.isEnabled = true
                    status.text = getString(R.string.patch_generate_failed, t.message ?: t.javaClass.simpleName)
                }
            }
        }.start()
    }

    private fun patchFileName(result: MethodPatchGenerator.Result): String {
        val app = result.packageName.substringAfterLast('.')
        val from = result.versionName.ifBlank { result.versionCode.toString() }
        return "${app}_${from}.lmpatch".replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun showPatchResult(
        path: String,
        methodRules: Int,
        fileRules: Int,
        removedRules: Int,
        verified: Boolean,
        remaining: Int
    ) {
        val verification = when {
            !verified -> getString(R.string.patch_result_unverified)
            remaining == 0 -> getString(R.string.patch_result_verified)
            else -> getString(R.string.patch_result_mismatch, remaining)
        }
        val message = buildString {
            append(getString(R.string.patch_result_stats, methodRules, fileRules, removedRules))
            append('\n').append(verification)
            append("\n\n").append(getString(R.string.patch_result_saved, path))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.patch_result_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
