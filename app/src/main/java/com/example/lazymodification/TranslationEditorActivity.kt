package com.example.lazymodification

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lazymodification.utils.AppTranslator
import com.example.lazymodification.utils.FileSaver
import com.example.lazymodification.utils.MtDictionary
import com.example.lazymodification.utils.TranslationCache
import com.example.lazymodification.utils.TranslationRules
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import java.io.File

/**
 * Two-column translation editor: original strings on the left, editable
 * translations on the right. The bottom button translates missing rows and
 * becomes a dictionary action once all rows are done.
 */
class TranslationEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.EDITOR_APK_PATH"
        const val EXTRA_TARGET_LANGUAGE = "com.example.lazymodification.EDITOR_TARGET_LANGUAGE"
        const val EXTRA_PROVIDER = "com.example.lazymodification.EDITOR_PROVIDER"
        const val EXTRA_AZURE_KEY = "com.example.lazymodification.EDITOR_AZURE_KEY"
        const val EXTRA_AZURE_REGION = "com.example.lazymodification.EDITOR_AZURE_REGION"
        const val EXTRA_DEEPL_KEY = "com.example.lazymodification.EDITOR_DEEPL_KEY"
        const val EXTRA_DICTIONARY = "com.example.lazymodification.EDITOR_DICTIONARY"

        const val EXTRA_IDS = "com.example.lazymodification.EDITOR_IDS"
        const val EXTRA_NAMES = "com.example.lazymodification.EDITOR_NAMES"
        const val EXTRA_ORIGINALS = "com.example.lazymodification.EDITOR_ORIGINALS"
        const val EXTRA_TRANSLATIONS = "com.example.lazymodification.EDITOR_TRANSLATIONS"
        const val EXTRA_EXCLUDED = "com.example.lazymodification.EDITOR_EXCLUDED"
        const val EXTRA_ACTION = "com.example.lazymodification.EDITOR_ACTION"

        const val ACTION_APPLY = "apply_translations"
        private const val DEFAULT_TARGET_LANGUAGE = "ru"
        private const val TAG = "TranslationEditor"
    }

    private data class Row(
        val resourceId: Int,
        val name: String,
        val original: String,
        var translated: String,
        var excluded: Boolean = false,
        var copiedOriginal: Boolean = false
    )

    private val rows = mutableListOf<Row>()
    private val excludedColor = 0xFFBBDEFB.toInt()
    private lateinit var rvEditor: RecyclerView
    private lateinit var scrollThumb: View
    private lateinit var adapter: EditorAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnTranslate: MaterialButton
    private lateinit var btnApply: MaterialButton
    private lateinit var headerOriginal: TextView
    private lateinit var headerTranslated: TextView
    private lateinit var headerDivider: View

    private lateinit var cache: TranslationCache
    private var translating = false
    private var columnWidth = 0
    private var apkPath: String? = null
    private var targetLanguage = DEFAULT_TARGET_LANGUAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TARGET_LANGUAGE
        if (apkPath == null || !File(apkPath!!).exists()) {
            Toast.makeText(this, getString(R.string.apk_file_not_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        cache = TranslationCache(this)
        buildUi()
        loadStrings()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        tvTitle = TextView(this).apply {
            text = getString(R.string.translation_editor_title)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(tvTitle)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), dp(6))
        }
        headerOriginal = TextView(this).apply {
            text = getString(R.string.original_label)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        }
        headerDivider = View(this).apply {
            setBackgroundColor(0xFFBDBDBD.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }
        headerTranslated = TextView(this).apply {
            text = getString(R.string.translated_label)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(headerOriginal)
        header.addView(headerDivider)
        header.addView(headerTranslated, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val listHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            clipChildren = true
        }
        rvEditor = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TranslationEditorActivity)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, dp(8), 0)
        }
        scrollThumb = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF78909C.toInt())
                cornerRadius = dp(2).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                dp(4),
                dp(36),
                Gravity.END or Gravity.TOP
            ).apply {
                marginEnd = dp(2)
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        listHost.addView(rvEditor)
        listHost.addView(scrollThumb)
        root.addView(listHost)
        adapter = EditorAdapter()
        rvEditor.adapter = adapter
        rvEditor.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollThumb()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                updateScrollThumb()
            }
        })
        rvEditor.post {
            applyColumnWidths(rvEditor.width)
            updateScrollThumb()
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(progress)

        tvStatus = TextView(this).apply {
            textSize = 13f
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(tvStatus)

        btnTranslate = MaterialButton(this).apply {
            text = getString(R.string.translate_rows_action)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                val complete = rows.isNotEmpty() &&
                    rows.all { it.excluded || it.translated.isNotBlank() }
                if (complete) {
                    createDictionary()
                } else {
                    translateMissing()
                }
            }
        }
        root.addView(btnTranslate)

        btnApply = MaterialButton(this).apply {
            text = getString(R.string.apply_translations)
            textSize = 16f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { returnResult(ACTION_APPLY) }
        }
        root.addView(btnApply)

        setContentView(root)
        updateButtons()
    }

    private fun applyColumnWidths(viewWidth: Int) {
        if (viewWidth <= 0) return
        val cell = (viewWidth - dp(8) - dp(1) - dp(12)) / 2
        if (cell <= 0) return
        columnWidth = cell
        headerOriginal.layoutParams = LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT)
        headerTranslated.layoutParams = LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT)
        (headerDivider.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.width = dp(1)
            it.height = ViewGroup.LayoutParams.MATCH_PARENT
            it.marginStart = dp(6)
            it.marginEnd = dp(6)
            headerDivider.layoutParams = it
        }
        adapter.notifyDataSetChanged()
        updateScrollThumb()
    }

    private fun updateScrollThumb() {
        if (!::scrollThumb.isInitialized || !::rvEditor.isInitialized) return
        val viewport = rvEditor.height
        val range = rvEditor.computeVerticalScrollRange()
        val extent = rvEditor.computeVerticalScrollExtent()
        if (viewport <= 0 || extent <= 0 || range <= extent) {
            scrollThumb.visibility = View.GONE
            return
        }
        val thumbHeight = maxOf(dp(36), (viewport.toFloat() * extent / range).toInt())
        if (thumbHeight >= viewport) {
            scrollThumb.visibility = View.GONE
            return
        }
        val maxTop = viewport - thumbHeight
        val maxScroll = (range - extent).coerceAtLeast(1)
        val top = (maxTop.toFloat() * rvEditor.computeVerticalScrollOffset() / maxScroll)
            .toInt()
            .coerceIn(0, maxTop)
        val lp = scrollThumb.layoutParams as FrameLayout.LayoutParams
        if (lp.height != thumbHeight || lp.topMargin != top) {
            lp.height = thumbHeight
            lp.topMargin = top
            scrollThumb.layoutParams = lp
        }
        scrollThumb.visibility = View.VISIBLE
    }

    private fun cellParams(): LinearLayout.LayoutParams =
        if (columnWidth > 0) {
            LinearLayout.LayoutParams(columnWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun loadStrings() {
        val path = apkPath ?: return
        keepScreenOn(true)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.translation_editor_loading)
        btnTranslate.isEnabled = false

        Thread {
            try {
                val dictionary = readDictionary()
                val loaded = mutableListOf<Row>()
                val seenIds = HashSet<Int>()
                val module = ApkModule.loadApkFile(File(path))
                try {
                    for (pkg in module.tableBlock.listPackages()) {
                        val strings = pkg.getResources("string")
                        while (strings.hasNext()) {
                            val entry = strings.next()
                            val value = entry.get(ResConfig.getDefault()) ?: entry.get() ?: continue
                            if (value.isNull()) continue
                            val text = value.getValueAsString() ?: continue
                            if (!TranslationRules.isTranslatable(entry.getName(), text)) continue
                            val id = entry.getResourceId()
                            if (id != 0 && !seenIds.add(id)) continue
                            val prefill = dictionary?.get(text)
                                ?.takeIf { it.isNotBlank() && it != text }
                                ?: ""
                            loaded.add(Row(id, entry.getName() ?: "", text, prefill))
                        }
                    }
                } finally {
                    module.close()
                }
                if (loaded.isEmpty()) throw IllegalStateException(getString(R.string.err_translate_empty))
                runOnUiThread {
                    rows.clear()
                    rows.addAll(loaded)
                    adapter.notifyDataSetChanged()
                    rvEditor.post { updateScrollThumb() }
                    updateTitle()
                    progress.visibility = View.GONE
                    progress.isIndeterminate = false
                    tvStatus.visibility = View.GONE
                    btnTranslate.isEnabled = true
                    keepScreenOn(false)
                    updateButtons()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "editor load error", e)
                runOnUiThread {
                    progress.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                    btnTranslate.isEnabled = true
                    keepScreenOn(false)
                    Toast.makeText(
                        this@TranslationEditorActivity,
                        getString(R.string.err_translate_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }.start()
    }

    private fun readDictionary(): Map<String, String>? {
        val flat = intent.getStringArrayListExtra(EXTRA_DICTIONARY) ?: return null
        if (flat.size < 2) return null
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < flat.size) {
            map[flat[i]] = flat[i + 1]
            i += 2
        }
        return map
    }

    private fun createDictionary() {
        if (translating || rows.isEmpty()) return

        val entries = linkedMapOf<String, String>()
        for (row in rows) {
            if (row.excluded) continue
            val from = row.original
            val to = row.translated
            if (from.isBlank() || to.isBlank() || from == to) continue
            if (!entries.containsKey(from)) entries[from] = to
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.dictionary_empty), Toast.LENGTH_LONG).show()
            return
        }

        translating = true
        keepScreenOn(true)
        btnTranslate.isEnabled = false
        btnApply.isEnabled = false
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_dictionary_write)

        Thread {
            try {
                val xml = MtDictionary.build(entries)
                val fileName = File(apkPath!!).nameWithoutExtension + MtDictionary.FILE_SUFFIX
                val temp = File(cacheDir, fileName)
                temp.writeText(xml, Charsets.UTF_8)
                val savedPath = FileSaver.saveDictionary(this, temp, fileName)
                temp.delete()
                runOnUiThread {
                    translating = false
                    progress.visibility = View.GONE
                    progress.isIndeterminate = false
                    tvStatus.visibility = View.GONE
                    btnTranslate.isEnabled = true
                    keepScreenOn(false)
                    updateButtons()
                    Toast.makeText(
                        this@TranslationEditorActivity,
                        getString(R.string.dictionary_saved, savedPath),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "dictionary error", e)
                runOnUiThread {
                    translating = false
                    progress.visibility = View.GONE
                    progress.isIndeterminate = false
                    tvStatus.visibility = View.GONE
                    btnTranslate.isEnabled = true
                    keepScreenOn(false)
                    updateButtons()
                    Toast.makeText(
                        this@TranslationEditorActivity,
                        getString(R.string.err_dictionary_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun translateMissing() {
        if (translating || rows.isEmpty()) return
        val target = targetLanguage
        val missing = rows.indices.filter {
            !rows[it].excluded && rows[it].translated.isBlank()
        }
        if (missing.isEmpty()) {
            updateButtons()
            return
        }
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: AppTranslator.GOOGLE
        val key = when (provider) {
            AppTranslator.AZURE -> intent.getStringExtra(EXTRA_AZURE_KEY)?.trim()
            AppTranslator.DEEPL -> intent.getStringExtra(EXTRA_DEEPL_KEY)?.trim()
            else -> null
        }
        if ((provider == AppTranslator.AZURE || provider == AppTranslator.DEEPL) &&
            key.isNullOrEmpty()
        ) {
            Toast.makeText(this, getString(R.string.err_translator_key_required), Toast.LENGTH_LONG).show()
            return
        }
        val region = intent.getStringExtra(EXTRA_AZURE_REGION)?.trim()

        translating = true
        keepScreenOn(true)
        btnTranslate.isEnabled = false
        btnApply.visibility = View.GONE
        progress.isIndeterminate = false
        progress.max = missing.size.coerceAtLeast(1)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_translating, 0, missing.size)

        Thread {
            var done = 0
            var failed = 0
            for (index in missing) {
                val row = rows[index]
                if (row.excluded) {
                    done++
                    val skippedDone = done
                    runOnUiThread {
                        progress.progress = skippedDone
                        tvStatus.text = getString(
                            R.string.status_translating,
                            skippedDone,
                            missing.size
                        )
                    }
                    continue
                }
                try {
                    val cacheKey = cache.key(provider, "auto", target, row.original)
                    var result = cache.get(cacheKey)
                    if (result == null) {
                        result = AppTranslator.translateOnce(
                            provider,
                            key,
                            region,
                            "auto",
                            target,
                            row.original
                        )
                        cache.put(cacheKey, result)
                    }
                    if (result.isBlank()) {
                        // Short labels (note names such as "La", "Do", "Sol")
                        // are often returned as empty by Google. Keep the
                        // original instead of failing the whole translation.
                        if (row.original.trim().length <= 3) {
                            result = row.original
                        } else {
                            throw IllegalStateException("empty translation")
                        }
                    }
                    val translated = result
                    runOnUiThread {
                        row.translated = translated
                        adapter.notifyItemChanged(index)
                    }
                } catch (e: Throwable) {
                    if (row.original.trim().length <= 3) {
                        runOnUiThread {
                            row.translated = row.original
                            adapter.notifyItemChanged(index)
                        }
                    } else {
                        failed++
                        Log.w(TAG, "skip: ${e.message}")
                    }
                }
                done++
                val currentDone = done
                runOnUiThread {
                    progress.progress = currentDone
                    tvStatus.text = getString(R.string.status_translating, currentDone, missing.size)
                }
            }
            cache.save()
            runOnUiThread {
                translating = false
                progress.visibility = View.GONE
                tvStatus.visibility = View.GONE
                btnTranslate.isEnabled = true
                keepScreenOn(false)
                updateButtons()
                if (failed > 0) {
                    Toast.makeText(
                        this@TranslationEditorActivity,
                        getString(R.string.err_translate_failed, failed.toString()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun updateButtons() {
        if (!::btnTranslate.isInitialized) return
        val missing = rows.any { !it.excluded && it.translated.isBlank() }
        btnTranslate.visibility = View.VISIBLE
        btnTranslate.text = getString(
            if (missing) R.string.translate_rows_action else R.string.create_dictionary
        )
        btnTranslate.isEnabled = !translating && rows.isNotEmpty()
        btnApply.visibility = if (!missing && rows.isNotEmpty()) View.VISIBLE else View.GONE
        btnApply.isEnabled = !translating
    }

    private fun toggleExcluded(position: Int) {
        if (position !in rows.indices) return
        val row = rows[position]
        row.excluded = !row.excluded
        if (row.excluded) {
            if (row.translated.isBlank()) {
                row.translated = row.original
                row.copiedOriginal = true
            }
        } else if (row.copiedOriginal && row.translated == row.original) {
            row.translated = ""
            row.copiedOriginal = false
        }
        adapter.notifyItemChanged(position)
        updateTitle()
        updateButtons()
    }

    private fun updateTitle() {
        if (!::tvTitle.isInitialized) return
        if (rows.isEmpty()) {
            tvTitle.text = getString(R.string.translation_editor_title)
            return
        }
        val text = StringBuilder()
            .append(getString(R.string.translation_editor_title))
            .append(" · ")
            .append(getString(R.string.translation_editor_count, rows.size))
        val excluded = rows.count { it.excluded }
        if (excluded > 0) {
            text.append(" · ").append(getString(R.string.translation_editor_excluded, excluded))
        }
        tvTitle.text = text
    }

    private fun returnResult(action: String?) {
        val data = Intent().apply {
            putExtra(EXTRA_IDS, rows.map { it.resourceId }.toIntArray())
            putExtra(EXTRA_EXCLUDED, rows.map { it.excluded }.toBooleanArray())
            putStringArrayListExtra(EXTRA_NAMES, ArrayList(rows.map { it.name }))
            putStringArrayListExtra(EXTRA_ORIGINALS, ArrayList(rows.map { it.original }))
            // Exclusion only skips automatic translation. A filled value
            // must still be applied to the APK when the user requests it.
            putStringArrayListExtra(EXTRA_TRANSLATIONS, ArrayList(rows.map { it.translated }))
            if (action != null) putExtra(EXTRA_ACTION, action)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        returnResult(null)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class EditorAdapter : RecyclerView.Adapter<EditorHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EditorHolder {
            return EditorHolder(buildRow())
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: EditorHolder, position: Int) {
            holder.bind(rows[position])
        }
    }

    private inner class EditorHolder(root: View) : RecyclerView.ViewHolder(root) {
        private val original: TextView
        private val edit: EditText
        private var row: Row? = null
        private var binding = false

        init {
            val container = root as LinearLayout
            original = container.getChildAt(0) as TextView
            edit = container.getChildAt(2) as EditText
            val toggle = View.OnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) toggleExcluded(position)
            }
            container.isClickable = true
            container.setOnClickListener(toggle)
            original.isClickable = true
            original.setOnClickListener(toggle)
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (binding) return
                    row?.translated = s?.toString() ?: ""
                    row?.copiedOriginal = false
                    updateButtons()
                }
            })
        }

        fun bind(value: Row) {
            row = value
            binding = true
            itemView.setBackgroundColor(if (value.excluded) excludedColor else Color.TRANSPARENT)
            original.text = value.original
            original.layoutParams = cellParams()
            edit.layoutParams = cellParams()
            edit.setText(value.translated)
            binding = false
        }
    }

    private fun buildRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val original = TextView(this).apply {
            textSize = 14f
            minWidth = 0
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = cellParams()
        }
        val divider = View(this).apply {
            setBackgroundColor(0xFFBDBDBD.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        }
        val edit = EditText(this).apply {
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 8
            layoutParams = cellParams()
        }
        row.addView(original)
        row.addView(divider)
        row.addView(edit)
        return row
    }
}
