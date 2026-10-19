package com.example.lazymodification

import android.os.Bundle
import android.text.InputType
import android.graphics.Typeface
import android.net.Uri
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.patches.PatchFiles
import com.example.lazymodification.utils.patches.PatchFormat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID

class PatchEditorActivity : AppCompatActivity() {
    companion object { const val EXTRA_PATCH_PATH = "patch_path" }
    private lateinit var editor: EditText
    private lateinit var payloadView: TextView
    private lateinit var folder: File
    private var exporting: File? = null
    private var busy = false
    private val payloads: File get() = File(folder, "payloads").apply { mkdirs() }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work { loadArchive(PatchFiles.import(this, uri)) }
    }
    private val attachLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachmentName(uri)
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = exporting
        if (uri != null && source != null) work {
            contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error(getString(R.string.custom_patch_write_error))
            runOnUiThread { Toast.makeText(this, R.string.custom_patch_saved, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_patch_editor)
        editor = findViewById(R.id.patchEditorText)
        payloadView = findViewById(R.id.patchEditorPayloads)
        exporting = savedInstanceState?.getString("exporting")?.let(::File)?.takeIf { it.isFile }
        val previous = savedInstanceState?.getString("folder")
            ?: getPreferences(MODE_PRIVATE).getString("last_draft", null)
        folder = previous?.let(::File)?.takeIf { it.isDirectory && it.canonicalPath.startsWith(filesDir.canonicalPath + "/") }
            ?: File(filesDir, "patch_drafts/${UUID.randomUUID()}").apply { mkdirs() }
        editor.setText(File(folder, "draft.txt").takeIf { it.isFile }?.readText() ?: newText())
        findViewById<MaterialButton>(R.id.patchEditorImport).setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.patchEditorAttach).setOnClickListener { attachLauncher.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.patchEditorAddRule).setOnClickListener { chooseRule() }
        findViewById<MaterialButton>(R.id.patchEditorNew).setOnClickListener {
            MaterialAlertDialogBuilder(this).setMessage(R.string.custom_patch_new_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    payloads.deleteRecursively(); editor.setText(newText()); persist(); refreshPayloads()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<MaterialButton>(R.id.patchEditorValidate).setOnClickListener { validate() }
        findViewById<MaterialButton>(R.id.patchEditorSave).setOnClickListener { export() }
        findViewById<MaterialButton>(R.id.patchEditorExamples).setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.custom_patch_examples)
                .setItems(arrayOf("NoAlert", "IsPremium V.2")) { _, index -> work {
                    val name = if (index == 0) "NoAlert.zip" else "IsPremium-V.2.zip"
                    val file = File(cacheDir, name)
                    assets.open("patch_examples/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                    loadArchive(file)
                } }.show()
        }
        payloadView.setOnClickListener { removeAttachment() }
        refreshPayloads()
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_PATCH_PATH)?.let { path -> work { loadArchive(File(path)) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persist(); outState.putString("folder", folder.path)
        outState.putString("exporting", exporting?.path)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { persist(); super.onPause() }
    private fun persist() {
        if (::folder.isInitialized && ::editor.isInitialized) {
            try {
                File(folder, "draft.txt").writeText(editor.text.toString())
                getPreferences(MODE_PRIVATE).edit().putString("last_draft", folder.path).apply()
            } catch (e: Exception) { Toast.makeText(this, getString(R.string.custom_patch_write_error), Toast.LENGTH_LONG).show() }
        }
    }
    private fun newText() = "[MIN_ENGINE_VER]\n2\n\n[AUTHOR]\n\n[PACKAGE]\n*\n\n"
    private fun files(): Map<String, File> = payloads.walkTopDown().filter { it.isFile }.associate { it.relativeTo(payloads).invariantSeparatorsPath to it }
    private fun refreshPayloads() {
        val names = files().keys.sorted()
        payloadView.text = getString(R.string.custom_patch_payloads, names.size) +
            (if (names.isEmpty()) "" else "\n" + names.joinToString("\n"))
    }
    private fun loadArchive(archive: File) {
        val doc = PatchFormat.read(archive)
        val temporary = File(filesDir, "patch_drafts/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            PatchFiles.extractPayloads(archive, File(temporary, "payloads"))
            File(temporary, "draft.txt").writeText(doc.text)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                folder = temporary
                editor.setText(doc.text); persist(); refreshPayloads()
            }
        } catch (t: Throwable) { temporary.deleteRecursively(); throw t }
    }
    private fun validate(): Boolean = try {
        val document = PatchFormat.parse(editor.text.toString())
        val paths = files().keys
        document.rules.forEach { r ->
            val source = if (r.type == "EXECUTE_DEX") r.value("SCRIPT") else if (r.type in listOf("ADD_FILES", "MERGE")) r.value("SOURCE") else ""
            if (source.isNotBlank() && !source.contains("\${") && source.trim() !in paths)
                error(getString(R.string.custom_patch_missing_payload, source.trim()))
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.custom_patch_valid)
            .setMessage(getString(R.string.custom_patch_summary, document.author.ifBlank { "—" }, document.packagePattern, document.rules.size))
            .setPositiveButton(android.R.string.ok, null).show()
        true
    } catch (e: Exception) { showError(e); false }
    private fun export() {
        val text = editor.text.toString()
        try {
            val document = PatchFormat.parse(text)
            val known = files()
            document.rules.forEach { rule ->
                val p = if (rule.type == "EXECUTE_DEX") rule.value("SCRIPT") else if (rule.type in listOf("ADD_FILES", "MERGE")) rule.value("SOURCE") else ""
                if (p.isNotBlank() && !p.contains("\${") && p.trim() !in known) error(getString(R.string.custom_patch_missing_payload, p.trim()))
            }
            persist()
            work {
                val output = File(folder, "export.zip")
                PatchFormat.writeZip(output, text, known)
                exporting = output
                runOnUiThread { exportLauncher.launch("patch.zip") }
            }
        } catch (e: Exception) { showError(e) }
    }
    private fun attachmentName(uri: Uri) {
        val field = EditText(this).apply { setText(PatchFiles.displayName(this@PatchEditorActivity, uri)); setSingleLine(true) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.custom_patch_payload_path).setView(padded(field))
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val path = field.text.toString().trim(); PatchFormat.validatePath(path)
                    require(path != "patch.txt") { getString(R.string.custom_patch_reserved_name) }
                    dialog.dismiss()
                    work {
                        val target = PatchFormat.child(payloads, path)
                        val data = contentResolver.openInputStream(uri)?.use { PatchFormat.readBounded(it, 128L * 1024 * 1024) }
                            ?: error(getString(R.string.custom_patch_read_error))
                        target.parentFile?.mkdirs(); target.writeBytes(data)
                        runOnUiThread { refreshPayloads() }
                    }
                } catch (e: Exception) { showError(e) }
            }
        }
        dialog.show()
    }
    private fun removeAttachment() {
        val items = files()
        if (items.isEmpty()) return
        val names = items.keys.sorted().toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.custom_patch_remove_attachment).setItems(names) { _, index ->
            items[names[index]]?.delete(); refreshPayloads()
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun chooseRule() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.custom_patch_add_rule).setItems(PatchFormat.ruleTypes.toTypedArray()) { _, index -> ruleForm(PatchFormat.ruleTypes[index]) }.show()
    }
    private fun ruleForm(type: String) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val fields = linkedMapOf<String, EditText>()
        val flags = linkedMapOf<String, MaterialCheckBox>()
        val keys = listOf("NAME") + PatchFormat.required(type) + when (type) {
            "MATCH_REPLACE" -> listOf("REGEX", "DOTALL", "REPLACE")
            "MATCH_ASSIGN", "MATCH_GOTO" -> listOf("REGEX", "DOTALL")
            "ADD_FILES" -> listOf("EXTRACT")
            "EXECUTE_DEX" -> listOf("SMALI_NEEDED", "PARAM")
            else -> emptyList()
        }
        keys.distinct().forEach { key ->
            if (key in listOf("REGEX", "DOTALL", "EXTRACT", "SMALI_NEEDED")) {
                val check = MaterialCheckBox(this).apply { text = key; isChecked = key == "REGEX" || key == "SMALI_NEEDED" }
                flags[key] = check; container.addView(check)
            } else {
                container.addView(TextView(this).apply { text = key; setPadding(0, dp(10), 0, dp(4)) })
                val field = EditText(this).apply {
                    hint = getString(when (key) {
                        "NAME" -> R.string.custom_patch_hint_name
                        "TARGET" -> R.string.custom_patch_hint_target
                        "SOURCE", "SCRIPT" -> R.string.custom_patch_hint_source
                        "MATCH" -> R.string.custom_patch_hint_match
                        "REPLACE" -> R.string.custom_patch_hint_replace
                        "ASSIGN" -> R.string.custom_patch_hint_assign
                        "GOTO" -> R.string.custom_patch_hint_goto
                        "MAIN_CLASS" -> R.string.custom_patch_hint_class
                        "ENTRANCE" -> R.string.custom_patch_hint_entrance
                        else -> R.string.custom_patch_hint_param
                    })
                    typeface = Typeface.MONOSPACE
                    if (key == "TARGET") setText("smali*/*.smali")
                    minLines = if (key in listOf("MATCH", "REPLACE", "ASSIGN", "PARAM")) 3 else 1
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        (if (minLines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                }
                fields[key] = field
                container.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(type).setView(scroll).setPositiveButton(R.string.custom_patch_insert_rule, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = buildString {
                    append("\n[$type]\n")
                    keys.distinct().forEach { key ->
                        val value = fields[key]?.text?.toString() ?: flags[key]?.isChecked?.toString().orEmpty()
                        if (value.isNotEmpty() || key == "REPLACE") append("$key:\n$value\n")
                    }
                    append("[/$type]\n")
                }
                try {
                    // A GOTO may refer to a rule that will be added next; full validation is available separately.
                    PatchFormat.required(type).forEach { key -> require(!fields[key]?.text.isNullOrBlank()) { "$key: ${getString(R.string.custom_patch_required)}" } }
                    editor.append(text); persist(); dialog.dismiss()
                } catch (e: Exception) { showError(e) }
            }
        }
        dialog.show()
    }
    private fun padded(view: android.view.View) = LinearLayout(this).apply {
        setPadding(dp(24), dp(8), dp(24), dp(8)); addView(view, LinearLayout.LayoutParams(-1, -2))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun showError(error: Throwable) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.error).setMessage(error.message ?: error.javaClass.simpleName).setPositiveButton(android.R.string.ok, null).show()
    }
    private fun work(action: () -> Unit) {
        if (busy) return
        busy = true
        setBusy(true)
        Thread {
            try { action() } catch (t: Throwable) { runOnUiThread { if (!isFinishing && !isDestroyed) showError(t) } }
            finally { runOnUiThread { busy = false; if (!isDestroyed) setBusy(false) } }
        }.start()
    }
    private fun setBusy(value: Boolean) {
        listOf(R.id.patchEditorImport, R.id.patchEditorNew, R.id.patchEditorAddRule, R.id.patchEditorAttach,
            R.id.patchEditorExamples, R.id.patchEditorValidate, R.id.patchEditorSave, R.id.patchEditorText,
            R.id.patchEditorPayloads).forEach { findViewById<android.view.View>(it).isEnabled = !value }
        findViewById<android.view.View>(R.id.patchEditorProgress).visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
    }
}
