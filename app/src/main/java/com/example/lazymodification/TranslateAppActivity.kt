package com.example.lazymodification

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.AppTranslator
import com.example.lazymodification.utils.FileSaver
import com.example.lazymodification.utils.MtDictionary
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.TranslationCache
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import java.io.File
import java.util.Locale

/**
 * «Перевести приложение»: перевод строк приложения выбранным онлайн-переводчиком
 * с предпросмотром, кэшем и созданием выбранной локали в resources.arsc.
 *
 * Плейсхолдеры (%1$s и т.п.) защищаются от перевода (AppTranslator).
 * Перевод применяется только по кнопке «Применить переводы».
 */
class TranslateAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.INTERFACE_APK_PATH"
        private const val TAG = "TranslateApp"
        private const val DEFAULT_TARGET_LANG = "ru"
        private const val PREFS_NAME = "translate_app"
        private const val PREF_TARGET_LANG = "target_language"
    }

    private var apkPath: String? = null
    private var isProcessing = false
    private var targetLanguage = DEFAULT_TARGET_LANG

    private val targetLanguages = listOf(
        "ru", "en", "bg", "uk", "de", "fr", "es", "it", "pt",
        "nl", "pl", "cs", "sk", "ro", "hu", "el", "tr", "sr", "hr",
        "ar", "hi", "zh", "ja", "ko", "vi", "th", "id", "he"
    )

    private lateinit var tvFileName: TextView
    private lateinit var spTargetLanguage: Spinner
    private lateinit var rgMode: RadioGroup
    private lateinit var rbModeFull: RadioButton
    private lateinit var rbModeFast: RadioButton
    private lateinit var cbDictionary: CheckBox
    private lateinit var llDictionary: LinearLayout
    private lateinit var tvDictionaryName: TextView
    private lateinit var btnPickDictionary: MaterialButton
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
    private lateinit var btnCreateDictionary: MaterialButton
    private lateinit var btnContinueTranslate: MaterialButton
    private lateinit var scrollMain: ScrollView
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewGeneration = 0

    private lateinit var cache: TranslationCache

    private var progressDialog: AlertDialog? = null
    private var progressDialogText: TextView? = null

    private var finalApkFile: File? = null
    private var finalPackageName: String? = null
    private var pendingInstallAfterUninstall = false
    private var dictionaryEntries: Map<String, String>? = null
    private var dictionaryPreviewMode = false

    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private data class Row(
        val resourceId: Int,
        val name: String,
        val original: String,
        var translated: String,
        var excluded: Boolean = false
    )

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

    private val dictionaryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadDictionary(uri)
    }

    private val translationEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val ids = data.getIntArrayExtra(TranslationEditorActivity.EXTRA_IDS)
            ?: return@registerForActivityResult
        val names = data.getStringArrayListExtra(TranslationEditorActivity.EXTRA_NAMES)
            ?: return@registerForActivityResult
        val originals = data.getStringArrayListExtra(TranslationEditorActivity.EXTRA_ORIGINALS)
            ?: return@registerForActivityResult
        val translations = data.getStringArrayListExtra(TranslationEditorActivity.EXTRA_TRANSLATIONS)
            ?: return@registerForActivityResult
        val excluded = data.getBooleanArrayExtra(TranslationEditorActivity.EXTRA_EXCLUDED)
        if (ids.size != names.size || names.size != originals.size ||
            originals.size != translations.size
        ) return@registerForActivityResult
        rows.clear()
        for (i in ids.indices) {
            rows.add(
                Row(
                    ids[i],
                    names[i],
                    originals[i],
                    translations[i],
                    excluded?.getOrNull(i) == true
                )
            )
        }
        val action = data.getStringExtra(TranslationEditorActivity.EXTRA_ACTION)
        if (action == TranslationEditorActivity.ACTION_APPLY) {
            llSetup.visibility = View.GONE
            llPreview.removeAllViews()
            tvPreviewTitle.visibility = View.GONE
            applyTranslations()
        } else {
            // The old preview form is intentionally not shown anymore: Back
            // from the two-column editor returns to the previous screen.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_translate_app)

        tvFileName = findViewById(R.id.tvFileName)
        spTargetLanguage = findViewById(R.id.spTargetLanguage)
        rgMode = findViewById(R.id.rgMode)
        rbModeFull = findViewById(R.id.rbModeFull)
        rbModeFast = findViewById(R.id.rbModeFast)
        cbDictionary = findViewById(R.id.cbDictionary)
        llDictionary = findViewById(R.id.llDictionary)
        tvDictionaryName = findViewById(R.id.tvDictionaryName)
        btnPickDictionary = findViewById(R.id.btnPickDictionary)
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
        btnCreateDictionary = findViewById(R.id.btnCreateDictionary)
        btnContinueTranslate = findViewById(R.id.btnContinueTranslate)
        scrollMain = findViewById(R.id.scrollMain)
        findViewById<com.example.lazymodification.utils.DragScrollThumb>(R.id.translateScrollThumb).bind(scrollMain)

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
        setupTargetLanguage()

        rgTranslator.setOnCheckedChangeListener { _, _ -> updateProviderFields() }
        rgMode.setOnCheckedChangeListener { _, _ -> updateModeUI() }
        cbDictionary.setOnCheckedChangeListener { _, checked ->
            llDictionary.visibility = if (checked) View.VISIBLE else View.GONE
            updateModeUI()
        }
        btnPickDictionary.setOnClickListener {
            dictionaryPicker.launch(arrayOf("application/xml", "text/xml", "text/plain", "*/*"))
        }
        updateModeUI()

        btnStart.setOnClickListener {
            openTranslationEditor()
        }
        btnApply.setOnClickListener { applyTranslations() }
        btnCreateDictionary.setOnClickListener { createDictionary() }
        btnContinueTranslate.setOnClickListener { continueTranslation() }
    }

    private fun setupTargetLanguage() {
        val labels = targetLanguages.map { targetLanguageName(it) }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spTargetLanguage.adapter = adapter

        val saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_TARGET_LANG, DEFAULT_TARGET_LANG)
        val index = targetLanguages.indexOf(saved).takeIf { it >= 0 } ?: 0
        targetLanguage = targetLanguages[index]
        spTargetLanguage.setSelection(index, false)
        spTargetLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val code = targetLanguages.getOrNull(position) ?: return
                targetLanguage = code
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_TARGET_LANG, code)
                    .apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun targetLanguageName(code: String): String {
        val display = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        if (display.isBlank()) return code.uppercase(Locale.US)
        return display.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    override fun onDestroy() {
        previewHandler.removeCallbacksAndMessages(null)
        previewGeneration++
        keepScreenOn(false)
        super.onDestroy()
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
        // Кнопка перевода по словарю появляется только после выбора файла.
        btnStart.visibility = View.VISIBLE
        btnStart.text = getString(R.string.start_translate)
    }

    private fun loadDictionary(uri: Uri) {
        tvDictionaryName.text = getString(R.string.dictionary_not_selected)
        Thread {
            try {
                val entries = contentResolver.openInputStream(uri)?.use { MtDictionary.parse(it) }
                    ?: throw IllegalStateException("empty dictionary stream")
                if (entries.isEmpty()) throw IllegalStateException("dictionary has no entries")
                runOnUiThread {
                    dictionaryEntries = entries
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
                    tvDictionaryName.text = "${getString(R.string.dictionary_loaded, entries.size)} • $name"
                    updateModeUI()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "dictionary load error", e)
                runOnUiThread {
                    dictionaryEntries = null
                    tvDictionaryName.text = getString(R.string.dictionary_not_selected)
                    updateModeUI()
                    showErrorDialog(getString(R.string.err_dictionary_load, e.message ?: ""))
                }
            }
        }.start()
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

    private fun openTranslationEditor() {
        if (isProcessing) return
        val useDictionary = cbDictionary.isChecked
        if (useDictionary && dictionaryEntries == null) {
            Toast.makeText(this, getString(R.string.err_dictionary_required), Toast.LENGTH_LONG).show()
            return
        }
        val provider = currentProvider()
        val key = when (provider) {
            AppTranslator.AZURE -> etAzureKey.text.toString().trim()
            AppTranslator.DEEPL -> etDeeplKey.text.toString().trim()
            else -> null
        }
        if ((provider == AppTranslator.AZURE || provider == AppTranslator.DEEPL) &&
            key.isNullOrEmpty()
        ) {
            Toast.makeText(this, getString(R.string.err_translator_key_required), Toast.LENGTH_LONG).show()
            return
        }
        val dictionary = ArrayList<String>()
        dictionaryEntries?.forEach { (from, to) ->
            dictionary.add(from)
            dictionary.add(to)
        }
        val intent = Intent(this, TranslationEditorActivity::class.java).apply {
            putExtra(TranslationEditorActivity.EXTRA_APK_PATH, apkPath)
            putExtra(TranslationEditorActivity.EXTRA_TARGET_LANGUAGE, targetLanguage)
            putExtra(TranslationEditorActivity.EXTRA_PROVIDER, provider)
            putExtra(TranslationEditorActivity.EXTRA_AZURE_KEY, etAzureKey.text.toString().trim())
            putExtra(TranslationEditorActivity.EXTRA_AZURE_REGION, etAzureRegion.text.toString().trim())
            putExtra(TranslationEditorActivity.EXTRA_DEEPL_KEY, etDeeplKey.text.toString().trim())
            putStringArrayListExtra(TranslationEditorActivity.EXTRA_DICTIONARY, dictionary)
        }
        translationEditorLauncher.launch(intent)
    }

    private fun startTranslation() {
        if (isProcessing) return
        if (rbModeFast.isChecked) {
            startFastTranslation()
            return
        }
        val provider = currentProvider()
        val useDictionary = cbDictionary.isChecked
        val dictionary = dictionaryEntries
        if (useDictionary && dictionary == null) {
            Toast.makeText(this, getString(R.string.err_dictionary_required), Toast.LENGTH_LONG).show()
            return
        }
        val key = when (provider) {
            AppTranslator.AZURE -> etAzureKey.text.toString().trim()
            AppTranslator.DEEPL -> etDeeplKey.text.toString().trim()
            else -> null
        }
        if (!useDictionary && (provider == AppTranslator.AZURE || provider == AppTranslator.DEEPL) && key.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.err_translator_key_required), Toast.LENGTH_LONG).show()
            return
        }
        val region = etAzureRegion.text.toString().trim()
        dictionaryPreviewMode = useDictionary

        keepScreenOn(true)
        isProcessing = true
        btnStart.isEnabled = false
        btnApply.visibility = View.GONE
        btnCreateDictionary.visibility = View.GONE
        btnContinueTranslate.visibility = View.GONE
        tvPreviewTitle.visibility = View.GONE
        llPreview.removeAllViews()
        llProgress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_preparing_strings)
        val target = targetLanguage

        Thread {
            try {
                val collected = mutableListOf<Row>()
                val targetConfig = ResConfig.parse(target)
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
                            val existing = e.get(targetConfig)?.takeIf { !it.isNull() }?.getValueAsString()
                            val initial = if (useDictionary) {
                                dictionary?.get(v)?.takeIf { it.isNotBlank() && it != v }
                                    ?: existing?.takeIf { it.isNotBlank() && it != v }
                                    ?: v
                            } else v
                            collected.add(Row(e.getResourceId(), e.getName() ?: "", v, initial))
                        }
                    }
                } finally {
                    module.close()
                }
                if (collected.isEmpty()) {
                    throw Exception(getString(R.string.err_translate_empty))
                }
                if (!useDictionary) {
                    var i = 0
                    for (r in collected) {
                        i++
                        val ck = cache.key(provider, "auto", target, r.original)
                        val cached = cache.get(ck)
                        if (cached != null) {
                            r.translated = cached
                        } else {
                            try {
                                r.translated = AppTranslator.translateOnce(provider, key, region, "auto", target, r.original)
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
                }
                cache.save()
                runOnUiThread { showPreview(collected) }
            } catch (e: Throwable) {
                Log.e(TAG, "translate error", e)
                runOnUiThread {
                    llProgress.visibility = View.GONE
                    btnStart.isEnabled = true
                    isProcessing = false
                    keepScreenOn(false)
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
        val useDictionary = cbDictionary.isChecked
        val dictionary = dictionaryEntries
        if (useDictionary && dictionary == null) {
            Toast.makeText(this, getString(R.string.err_dictionary_required), Toast.LENGTH_LONG).show()
            return
        }
        dictionaryPreviewMode = useDictionary

        keepScreenOn(true)
        isProcessing = true
        btnStart.isEnabled = false
        btnApply.visibility = View.GONE
        btnCreateDictionary.visibility = View.GONE
        btnContinueTranslate.visibility = View.GONE
        tvPreviewTitle.visibility = View.GONE
        llPreview.removeAllViews()
        llProgress.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_preparing_strings)
        scrollMain.scrollTo(0, 0)
        val target = targetLanguage

        Thread {
            try {
                val result = mutableListOf<Row>()
                var i = 0
                for (item in items) {
                    i++
                    val fromDictionary = if (useDictionary) dictionary?.get(item) else null
                    val translated: String
                    val usedDictionary = !fromDictionary.isNullOrBlank() && fromDictionary != item
                    if (usedDictionary) {
                        translated = fromDictionary!!
                    } else if (useDictionary) {
                        // В режиме словаря не подставляем машинный перевод:
                        // оставшиеся строки пользователь отправит кнопкой «Доперевести».
                        translated = item
                    } else {
                        val ck = cache.key(AppTranslator.MYMEMORY, "auto", target, item)
                        val cached = cache.get(ck)
                        if (cached != null) {
                            translated = cached
                        } else {
                            var t = item
                            var ok = false
                            try {
                                t = AppTranslator.translateOnce(AppTranslator.MYMEMORY, null, null, "auto", target, item)
                                ok = true
                            } catch (ex: Exception) {
                                Log.w(TAG, "skip \"${item.take(40)}\": ${ex.message}")
                            }
                            if (ok) cache.put(ck, t)
                            translated = t
                        }
                    }
                    result.add(Row(0, "", item, translated))
                    val current = i
                    val total = items.size
                    runOnUiThread { tvStatus.text = getString(R.string.status_translating, current, total) }
                    if (!usedDictionary && !useDictionary) Thread.sleep(100)
                }
                cache.save()
                runOnUiThread { showPreview(result) }
            } catch (e: Throwable) {
                Log.e(TAG, "fast translate error", e)
                runOnUiThread {
                    llProgress.visibility = View.GONE
                    btnStart.isEnabled = true
                    isProcessing = false
                    keepScreenOn(false)
                    showErrorDialog(getString(R.string.err_translate_failed, e.message ?: ""))
                }
            }
        }.start()
    }

    private fun showPreview(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        previewHandler.removeCallbacksAndMessages(null)
        val generation = ++previewGeneration
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
        // Показываем все строки. Крупные списки добавляем порциями,
        // чтобы не блокировать главный поток на несколько секунд.
        val batchSize = 40
        var index = 0
        val appendBatch = object : Runnable {
            override fun run() {
                if (generation != previewGeneration) return
                val end = (index + batchSize).coerceAtMost(list.size)
                for (i in index until end) llPreview.addView(buildPreviewRow(list[i]))
                index = end
                if (index < list.size) previewHandler.post(this)
            }
        }
        if (list.size <= batchSize) appendBatch.run() else previewHandler.post(appendBatch)

        btnApply.visibility = View.VISIBLE
        btnApply.isEnabled = true
        btnCreateDictionary.visibility = View.VISIBLE
        btnCreateDictionary.isEnabled = true
        btnContinueTranslate.visibility = if (dictionaryPreviewMode) View.VISIBLE else View.GONE
        btnContinueTranslate.isEnabled = dictionaryPreviewMode &&
            list.any { it.translated.isBlank() || it.translated == it.original }
        isProcessing = false
        btnStart.isEnabled = true
        keepScreenOn(false)
        scrollMain.scrollTo(0, 0)
    }

    private fun buildPreviewRow(r: Row): View {
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
        return rowView
    }

    /** Переводит онлайн только те строки, которые словарь не заполнил. */
    private fun continueTranslation() {
        if (isProcessing || rows.isEmpty()) return
        val missing = rows.filter { it.translated.isBlank() || it.translated == it.original }
        if (missing.isEmpty()) {
            Toast.makeText(this, getString(R.string.nothing_to_continue), Toast.LENGTH_LONG).show()
            return
        }

        val fast = rows.all { it.resourceId == 0 }
        val provider = if (fast) AppTranslator.MYMEMORY else currentProvider()
        val key = when (provider) {
            AppTranslator.AZURE -> etAzureKey.text.toString().trim()
            AppTranslator.DEEPL -> etDeeplKey.text.toString().trim()
            else -> null
        }
        if (!fast && (provider == AppTranslator.AZURE || provider == AppTranslator.DEEPL) && key.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.err_translator_key_required), Toast.LENGTH_LONG).show()
            return
        }
        val region = etAzureRegion.text.toString().trim()

        keepScreenOn(true)
        isProcessing = true
        btnContinueTranslate.isEnabled = false
        btnApply.isEnabled = false
        btnCreateDictionary.isEnabled = false
        btnStart.isEnabled = false
        showProgressDialog(getString(R.string.status_translating, 0, missing.size))
        val target = targetLanguage

        Thread {
            try {
                var i = 0
                for (row in missing) {
                    i++
                    val ck = cache.key(provider, "auto", target, row.original)
                    val cached = cache.get(ck)
                    if (cached != null) {
                        row.translated = cached
                    } else {
                        try {
                            row.translated = AppTranslator.translateOnce(
                                provider, key, region, "auto", target, row.original
                            )
                            cache.put(ck, row.translated)
                        } catch (ex: Exception) {
                            Log.w(TAG, "continue skip \"${row.original.take(40)}\": ${ex.message}")
                        }
                        Thread.sleep(120)
                    }
                    val current = i
                    val total = missing.size
                    runOnUiThread {
                        val text = getString(R.string.status_translating, current, total)
                        tvStatus.text = text
                        updateProgressDialog(text)
                    }
                }
                cache.save()
                runOnUiThread {
                    dismissProgressDialog()
                    llProgress.visibility = View.GONE
                    isProcessing = false
                    keepScreenOn(false)
                    btnStart.isEnabled = true
                    btnApply.isEnabled = true
                    btnCreateDictionary.isEnabled = true
                    showPreview(rows.toList())
                }
            } catch (e: Throwable) {
                Log.e(TAG, "continue error", e)
                runOnUiThread {
                    dismissProgressDialog()
                    llProgress.visibility = View.GONE
                    isProcessing = false
                    keepScreenOn(false)
                    btnStart.isEnabled = true
                    btnApply.isEnabled = true
                    btnCreateDictionary.isEnabled = true
                    btnContinueTranslate.isEnabled = true
                    showErrorDialog(getString(R.string.err_translate_failed, e.message ?: ""))
                }
            }
        }.start()
    }

    /** Создаёт XML-словарь в формате, который импортирует MT Manager. */
    private fun createDictionary() {
        if (isProcessing || rows.isEmpty()) return
        val entries = linkedMapOf<String, String>()
        for (row in rows) {
            val from = row.original
            val to = row.translated
            if (from.isBlank() || to.isBlank() || from == to) continue
            if (!entries.containsKey(from)) entries[from] = to
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.dictionary_empty), Toast.LENGTH_LONG).show()
            return
        }

        isProcessing = true
        btnCreateDictionary.isEnabled = false
        btnApply.isEnabled = false
        btnContinueTranslate.isEnabled = false
        btnStart.isEnabled = false
        showProgressDialog(getString(R.string.status_dictionary_write))

        Thread {
            try {
                val xml = MtDictionary.build(entries)
                val fileName = File(apkPath!!).nameWithoutExtension + MtDictionary.FILE_SUFFIX
                val temp = File(cacheDir, fileName)
                temp.writeText(xml, Charsets.UTF_8)
                val savedPath = FileSaver.saveDictionary(this, temp, fileName)
                temp.delete()
                runOnUiThread {
                    dismissProgressDialog()
                    isProcessing = false
                    btnStart.isEnabled = true
                    btnApply.isEnabled = true
                    btnCreateDictionary.isEnabled = true
                    btnContinueTranslate.isEnabled = true
                    Toast.makeText(this, getString(R.string.dictionary_saved, savedPath), Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "dictionary error", e)
                runOnUiThread {
                    dismissProgressDialog()
                    isProcessing = false
                    btnStart.isEnabled = true
                    btnApply.isEnabled = true
                    btnCreateDictionary.isEnabled = true
                    btnContinueTranslate.isEnabled = true
                    showErrorDialog(getString(R.string.err_dictionary_failed, e.message ?: ""))
                }
            }
        }.start()
    }

    private fun applyTranslations() {
        if (isProcessing || rows.isEmpty()) return
        val target = targetLanguage
        val targetName = targetLanguageName(target)
        keepScreenOn(true)
        isProcessing = true
        btnApply.isEnabled = false
        btnCreateDictionary.isEnabled = false
        btnContinueTranslate.isEnabled = false
        llProgress.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_translations_write, targetName)
        scrollMain.scrollTo(0, 0)
        showProgressDialog(getString(R.string.status_translations_write, targetName))

        Thread {
            var notFound: List<String> = emptyList()
            try {
                val work = File(cacheDir, "translate_${System.currentTimeMillis()}.apk")
                val module = ApkModule.loadApkFile(File(apkPath!!))
                try {
                    // Rows without a translation must not blank out the
                    // original string. The editor can create a dictionary
                    // from a partially filled list as well.
                    val byId = rows
                        .filter { it.resourceId != 0 && it.translated.isNotBlank() }
                        .associateBy { it.resourceId }
                    val byValue = rows
                        .filter { it.resourceId == 0 && it.translated.isNotBlank() }
                        .associateBy { it.original }
                    val matchedValues = mutableSetOf<String>()
                    val targetConfig = ResConfig.parse(target)
                    for (pkg in module.tableBlock.listPackages()) {
                        val strings = pkg.getResources("string")
                        while (strings.hasNext()) {
                            val e = strings.next()
                            if (byId.isNotEmpty()) {
                                val row = byId[e.getResourceId()]
                                if (row != null) {
                                    if (row.excluded || row.translated != row.original) {
                                        e.getOrCreate(targetConfig).setValueAsString(row.translated)
                                    }
                                    continue
                                }
                            }
                            if (byValue.isNotEmpty()) {
                                val def = e.get(ResConfig.getDefault()) ?: e.get() ?: continue
                                if (def.isNull()) continue
                                val v = def.getValueAsString() ?: continue
                                val row = byValue[v] ?: continue
                                matchedValues.add(v)
                                if (row.excluded || row.translated != row.original) {
                                    e.getOrCreate(targetConfig).setValueAsString(row.translated)
                                }
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
                    keepScreenOn(false)
                    btnApply.isEnabled = true
                    btnCreateDictionary.isEnabled = true
                    btnContinueTranslate.isEnabled = true
                    Toast.makeText(
                        this,
                        getString(R.string.translations_applied, targetName),
                        Toast.LENGTH_LONG
                    ).show()
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
                    keepScreenOn(false)
                    btnApply.isEnabled = true
                    btnCreateDictionary.isEnabled = true
                    btnContinueTranslate.isEnabled = true
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
