package com.example.lazymodification

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import android.widget.HorizontalScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.example.lazymodification.utils.DexComparer
import com.example.lazymodification.utils.AxmlComparer
import java.io.File

class CompareFileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK1 = "compare_apk1"
        const val EXTRA_APK2 = "compare_apk2"
        const val EXTRA_CLASS = "compare_class"
        const val EXTRA_IGNORE_DEBUG = "compare_ignore_debug"
        const val EXTRA_IGNORE_NOP = "compare_ignore_nop"
        const val EXTRA_KIND = "compare_kind"
    }

    private var syncing = false
    private var syncingH = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compare_file)

        val apk1 = intent.getStringExtra(EXTRA_APK1)?.let { File(it) }
        val apk2 = intent.getStringExtra(EXTRA_APK2)?.let { File(it) }
        val classType = intent.getStringExtra(EXTRA_CLASS)
        val ignoreDebug = intent.getBooleanExtra(EXTRA_IGNORE_DEBUG, true)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "dex"

        if (apk1 == null || apk2 == null || classType == null) {
            finish()
            return
        }

        val status = findViewById<TextView>(R.id.tvCompareStatus)
        status.text = classType

        Thread {
            try {
                val workDir = File(cacheDir, "compare_dex")
                val diff = if (kind == "xml") AxmlComparer.diffFile(apk1, apk2, classType) else DexComparer.diffClass(apk1, apk2, classType, workDir, ignoreDebug)
                runOnUiThread { showDiff(diff) }
            } catch (e: Exception) {
                runOnUiThread { status.text = getString(R.string.error_with_msg, e.message) }
            }
        }.start()
    }

    private fun showDiff(diff: DexComparer.DiffResult) {
        val gutterLeft = findViewById<TextView>(R.id.tvGutterLeft)
        val codeLeft = findViewById<TextView>(R.id.tvCompareLeft)
        val gutterRight = findViewById<TextView>(R.id.tvGutterRight)
        val codeRight = findViewById<TextView>(R.id.tvCompareRight)
        val tvStatus = findViewById<TextView>(R.id.tvCompareStatus)

        tvStatus.text = getString(R.string.compare_diff_count, diff.changedCount) + "\n" + getString(R.string.compare_legend)

        val blue = 0xFFBBDEFB.toInt()
        val red = 0xFFFFCDD2.toInt()
        val green = 0xFFC8E6C9.toInt()
        val gutterLeftSb = SpannableStringBuilder()
        val codeLeftSb = SpannableStringBuilder()
        val gutterRightSb = SpannableStringBuilder()
        val codeRightSb = SpannableStringBuilder()

        for (line in diff.lines) {
            appendGutter(gutterLeftSb, line.leftNum, line.left != null)
            appendCode(codeLeftSb, line.left, when (line.status) {
                DexComparer.LineStatus.CHANGED -> blue
                DexComparer.LineStatus.REMOVED -> red
                else -> null
            })
            appendGutter(gutterRightSb, line.rightNum, line.right != null)
            appendCode(codeRightSb, line.right, when (line.status) {
                DexComparer.LineStatus.CHANGED -> blue
                DexComparer.LineStatus.ADDED -> green
                else -> null
            })
        }

        gutterLeft.text = gutterLeftSb
        codeLeft.text = codeLeftSb
        gutterRight.text = gutterRightSb
        codeRight.text = codeRightSb

        val svLeft = findViewById<NestedScrollView>(R.id.svCompareLeft)
        val svRight = findViewById<NestedScrollView>(R.id.svCompareRight)

        val firstChanged = diff.lines.indexOfFirst { it.status != DexComparer.LineStatus.SAME }
        if (firstChanged >= 0) {
            codeLeft.post {
                val layout = codeLeft.layout ?: return@post
                val y = maxOf(0, layout.getLineTop(firstChanged) - codeLeft.lineHeight)
                svLeft.scrollTo(0, y)
                svRight.scrollTo(0, y)
            }
        }
        svLeft.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!syncing) {
                syncing = true
                svRight.scrollTo(0, scrollY)
                syncing = false
            }
        }
        svRight.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!syncing) {
                syncing = true
                svLeft.scrollTo(0, scrollY)
                syncing = false
            }
        }

        val hsvLeft = findViewById<HorizontalScrollView>(R.id.hsvCompareLeft)
        val hsvRight = findViewById<HorizontalScrollView>(R.id.hsvCompareRight)
        hsvLeft.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (!syncingH) {
                syncingH = true
                hsvRight.scrollTo(scrollX, 0)
                syncingH = false
            }
        }
        hsvRight.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (!syncingH) {
                syncingH = true
                hsvLeft.scrollTo(scrollX, 0)
                syncingH = false
            }
        }
    }

    private fun appendGutter(sb: SpannableStringBuilder, num: Int?, hasText: Boolean) {
        if (hasText && num != null) {
            sb.append(num.toString()).append('\n')
        } else {
            sb.append('\n')
        }
    }

    private fun appendCode(sb: SpannableStringBuilder, text: String?, color: Int?) {
        if (text == null) {
            sb.append('\n')
            return
        }
        val start = sb.length
        sb.append(text)
        val end = sb.length
        if (color != null) {
            sb.setSpan(BackgroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(0xFF000000.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append('\n')
    }
}
