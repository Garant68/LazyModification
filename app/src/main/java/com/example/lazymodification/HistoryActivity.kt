package com.example.lazymodification

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.PatchHistory
import com.example.lazymodification.utils.patches.ApkProjectCache
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var listView: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvCacheSize: TextView
    private var pendingRestore: PatchHistory.Entry? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val entry = pendingRestore
        pendingRestore = null
        if (uri != null && entry != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    entry.snapshot.inputStream().use { it.copyTo(out) }
                }
                Toast.makeText(this, getString(R.string.history_restored), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.history_restore_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        listView = findViewById(R.id.historyList)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvCacheSize = findViewById(R.id.tvCacheSize)
        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener { confirmClearCache() }
        loadHistory()
        refreshCacheSize()
    }

    private fun loadHistory() {
        Thread {
            val entries = try { PatchHistory.list(this) } catch (_: Throwable) { emptyList() }
            runOnUiThread { render(entries) }
        }.start()
    }

    private fun render(entries: List<PatchHistory.Entry>) {
        listView.removeAllViews()
        tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEach { listView.addView(buildCard(it)) }
    }

    private fun buildCard(entry: PatchHistory.Entry): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(MaterialColors.getColor(this@HistoryActivity, com.google.android.material.R.attr.colorOutline, 0xFFD5DADB.toInt()))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val title = TextView(this).apply {
            text = entry.name
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this@HistoryActivity, com.google.android.material.R.attr.colorOnSurface, 0xFF16191B.toInt()))
        }
        val meta = TextView(this).apply {
            val pkg = entry.packageName ?: "—"
            val version = listOfNotNull(entry.versionName, entry.versionCode?.toString()).joinToString(" / ")
            text = listOf(pkg, version.ifBlank { "—" }, formatDate(entry.createdAt), formatSize(entry.size)).joinToString(" · ")
            textSize = 12f
            setTextColor(MaterialColors.getColor(this@HistoryActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5F676B.toInt()))
            setPadding(0, dp(4), 0, 0)
        }
        val summary = TextView(this).apply {
            text = entry.summary.ifBlank { "—" }
            textSize = 13f
            setTextColor(MaterialColors.getColor(this@HistoryActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF333A3D.toInt()))
            setPadding(0, dp(8), 0, dp(4))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val restore = MaterialButton(this).apply {
            text = getString(R.string.history_restore)
            textSize = 13f
            setOnClickListener { confirmRestore(entry) }
        }
        val delete = MaterialButton(this).apply {
            text = getString(R.string.history_delete)
            textSize = 13f
            setOnClickListener { confirmDelete(entry) }
        }
        buttons.addView(restore)
        buttons.addView(delete)
        content.addView(title)
        content.addView(meta)
        content.addView(summary)
        content.addView(buttons)
        card.addView(content)
        card.setOnClickListener { showDetails(entry) }
        return card
    }

    private fun showDetails(entry: PatchHistory.Entry) {
        val details = buildString {
            append(entry.name).append('\n')
            append(getString(R.string.history_date, formatDate(entry.createdAt))).append('\n')
            append(getString(R.string.history_size, formatSize(entry.size))).append('\n')
            entry.packageName?.let { append("Package: ").append(it).append('\n') }
            if (entry.versionName != null || entry.versionCode != null) {
                append("Version: ").append(entry.versionName ?: "—")
                entry.versionCode?.let { append(" (").append(it).append(')') }
                append('\n')
            }
            entry.originalPath?.let { append(it).append('\n') }
            append('\n').append(entry.summary)
        }
        val tv = TextView(this).apply {
            text = details
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(4))
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_details)
            .setView(tv)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmRestore(entry: PatchHistory.Entry) {
        val original = entry.originalPath?.let { File(it) }
        val canOverwrite = original != null && original.parentFile?.canWrite() == true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_restore)
            .setMessage(getString(R.string.history_restore_confirm, entry.name))
            .setPositiveButton(R.string.history_restore) { _, _ ->
                if (canOverwrite) restoreTo(entry, original!!)
                else {
                    pendingRestore = entry
                    Toast.makeText(this, getString(R.string.history_no_path), Toast.LENGTH_LONG).show()
                    createDocument.launch(entry.name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restoreTo(entry: PatchHistory.Entry, target: File) {
        try {
            if (PatchHistory.restore(entry, target)) {
                Toast.makeText(this, getString(R.string.history_restored), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.history_restore_failed), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.history_restore_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(entry: PatchHistory.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_delete)
            .setMessage(entry.name)
            .setPositiveButton(R.string.history_delete) { _, _ ->
                PatchHistory.delete(this, entry)
                Toast.makeText(this, getString(R.string.history_deleted), Toast.LENGTH_SHORT).show()
                loadHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearCache() {
        Thread {
            val cacheBytes = runCatching { ApkProjectCache.size(this) }.getOrDefault(0L)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (cacheBytes <= 0L) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.history_clear_cache)
                        .setMessage(R.string.history_cache_empty)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }

                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.history_clear_cache)
                    .setMessage(
                        getString(
                            R.string.history_cache_confirm,
                            formatSize(cacheBytes)
                        )
                    )
                    .setPositiveButton(R.string.history_clear_cache) { _, _ ->
                        clearCache(cacheBytes)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    private fun clearCache(sizeBefore: Long) {
        Thread {
            runCatching { ApkProjectCache.clear(this) }
            val sizeAfter = runCatching { ApkProjectCache.size(this) }.getOrDefault(0L)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val freed = (sizeBefore - sizeAfter).coerceAtLeast(0L)
                tvCacheSize.text = getString(
                    R.string.history_cache_available,
                    formatSize(sizeAfter)
                )
                Toast.makeText(
                    this,
                    getString(R.string.history_cache_cleared, formatSize(freed)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun refreshCacheSize() {
        tvCacheSize.text = getString(R.string.history_cache_checking)
        Thread {
            val cacheBytes = runCatching { ApkProjectCache.size(this) }.getOrDefault(0L)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvCacheSize.text = getString(
                    R.string.history_cache_available,
                    formatSize(cacheBytes)
                )
            }
        }.start()
    }

    private fun formatDate(time: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(time))

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
