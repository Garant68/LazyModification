package com.example.lazymodification

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.DexCodeEditor
import com.example.lazymodification.utils.SmaliPrinter
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.reference.MethodReference
import java.io.File

/**
 * Просмотр кода метода (как в MT): открывается из результатов поиска,
 * прокручивает к найденной строке и подсвечивает её.
 */
class MethodCodeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.CODE_APK_PATH"
        const val EXTRA_DEX_NAME = "com.example.lazymodification.CODE_DEX_NAME"
        const val EXTRA_CLASS_TYPE = "com.example.lazymodification.CODE_CLASS_TYPE"
        const val EXTRA_METHOD_NAME = "com.example.lazymodification.CODE_METHOD_NAME"
        const val EXTRA_METHOD_LABEL = "com.example.lazymodification.CODE_METHOD_LABEL"
        const val EXTRA_QUERY = "com.example.lazymodification.CODE_QUERY"
        const val EXTRA_ADDR = "com.example.lazymodification.CODE_ADDR"

        const val RES_EDITS = "com.example.lazymodification.CODE_RES_EDITS"
        const val EXTRA_FROM_JUMP = "com.example.lazymodification.CODE_FROM_JUMP"
    }

    private lateinit var tvInfo: TextView
    private lateinit var listView: ListView

    private val rows = ArrayList<CharSequence>()
    private val kinds = ArrayList<Int>()
    private val rowAddrs = ArrayList<Int>()
    private val insRefs = ArrayList<Instruction?>()
    private val validAddrs = HashSet<Int>()
    private val editMap = HashMap<Int, String>()
    private var adapter: RowAdapter? = null
    private var targetIndex = -1

    private var baseLabel = ""
    private var classType: String = ""
    private var dexName: String = ""
    private var mName = ""
    private var mParams = ""
    private var mRet = "V"
    private var pBase = -1
    private val extraEdits = ArrayList<String>()
    private lateinit var btnClear: MaterialButton
    private var stubChoice: String? = null

    private val gotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val d = res.data ?: return@registerForActivityResult
        val arr = d.getStringArrayListExtra(RES_EDITS) ?: return@registerForActivityResult
        if (arr.isNotEmpty()) {
            extraEdits.addAll(arr)
            updateEditCount()
        }
    }
    private var workDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_method_code)

        tvInfo = findViewById(R.id.tvCodeInfo)
        listView = findViewById(R.id.lvCode)
        btnClear = findViewById(R.id.btnCodeClear)
        btnClear.isEnabled = false
        btnClear.visibility = if (intent.getBooleanExtra(EXTRA_FROM_JUMP, false)) View.VISIBLE else View.GONE
        btnClear.setOnClickListener { showClearMethodDialog() }

        classType = intent.getStringExtra(EXTRA_CLASS_TYPE) ?: ""
        dexName = intent.getStringExtra(EXTRA_DEX_NAME) ?: "classes.dex"
        val methodName = intent.getStringExtra(EXTRA_METHOD_NAME) ?: ""
        val methodLabel = intent.getStringExtra(EXTRA_METHOD_LABEL) ?: ""
        val targetAddr = intent.getIntExtra(EXTRA_ADDR, -1)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath == null) {
            finish()
            return
        }

        setTitle(R.string.code_title)
        tvInfo.text = getString(R.string.dex_editor_loading)
        val ad = RowAdapter()
        adapter = ad
        listView.adapter = ad
        listView.setOnItemClickListener { _, _, position, _ ->
            val a = rowAddrs.getOrNull(position) ?: return@setOnItemClickListener
            if (a < 0) return@setOnItemClickListener
            val ins = insRefs.getOrNull(position) ?: return@setOnItemClickListener
            val cur = rows.getOrNull(position)?.toString() ?: return@setOnItemClickListener
            showInsMenu(position, a, ins, cur)
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val txt = rows.getOrNull(position)?.toString() ?: return@setOnItemLongClickListener false
            AlertDialog.Builder(this)
                .setItems(
                    arrayOf(
                        getString(R.string.code_copy_line),
                        getString(R.string.code_copy_all)
                    )
                ) { _, idx ->
                    if (idx == 0) {
                        copyText(txt)
                    } else {
                        copyText(rows.joinToString("\n"))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }

        Thread {
            try {
                val apk = File(apkPath)
                val dir = File(cacheDir, "codeview_" + System.currentTimeMillis())
                dir.mkdirs()
                workDir = dir
                val dexFiles = ApkUtils.extractAllDex(apk, dir)
                val ordered = ArrayList<File>()
                val preferred = dexFiles.firstOrNull { it.name == dexName }
                if (preferred != null) ordered.add(preferred)
                for (f in dexFiles) {
                    if (preferred == null || f.name != preferred.name) ordered.add(f)
                }
                var classDefOpt: ClassDef? = null
                for (f in ordered) {
                    val d = DexFileFactory.loadDexFile(f, Opcodes.forApi(28))
                    var cd: ClassDef? = null
                    for (c in d.classes) {
                        if (c.type == classType) {
                            cd = c
                            break
                        }
                    }
                    if (cd != null) {
                        classDefOpt = cd
                        break
                    }
                }
                val classDef = classDefOpt ?: throw IllegalStateException(getString(R.string.code_not_found))
                var found: Method? = null
                for (m in classDef.methods) {
                    if (m.implementation == null) continue
                    if (m.name == methodName && SmaliPrinter.methodLabel(m) == methodLabel) {
                        found = m
                        break
                    }
                }
                val mth = found ?: throw IllegalStateException(getString(R.string.code_not_found))
                val impl = mth.implementation ?: throw IllegalStateException(getString(R.string.code_not_found))
                val labels = SmaliPrinter.labelsFor(impl)
                val pb = impl.registerCount - paramRegCount(mth)
                val lineMap = HashMap<Int, Int>()
                for (d in impl.debugItems) {
                    if (d is org.jf.dexlib2.iface.debug.LineNumber) lineMap[d.codeAddress] = d.lineNumber
                }
                val newRows = ArrayList<CharSequence>()
                val newKinds = ArrayList<Int>()
                val newAddrs = ArrayList<Int>()
                val newRefs = ArrayList<Instruction?>()
                val newValid = HashSet<Int>()
                var ti = -1
                var addr = 0
                fun addMeta(t: String) {
                    newRows.add(t)
                    newKinds.add(0)
                    newAddrs.add(-1)
                    newRefs.add(null)
                }
                addMeta(methodHeader(mth))
                addMeta(".registers " + impl.registerCount)
                var lastLine = -1
                for (ins in impl.instructions) {
                    newValid.add(addr)
                    val ln = lineMap[addr]
                    if (ln != null && ln != lastLine) {
                        addMeta("")
                        addMeta(".line " + ln)
                        lastLine = ln
                    }
                    val names = labels[addr]
                    if (names != null) {
                        for (nm in names) addMeta(nm)
                    }
                    if (addr == targetAddr) ti = newRows.size
                    newRows.add(SmaliPrinter.format(ins, addr, pb))
                    newKinds.add(if (addr == targetAddr) 2 else 1)
                    newAddrs.add(addr)
                    newRefs.add(ins)
                    addr += ins.codeUnits
                }
                addMeta(".end method")
                val tiFinal = ti
                runOnUiThread {
                    baseLabel = SmaliPrinter.methodLabel(mth)
                    pBase = pb
                    tvInfo.text = baseLabel
                    mName = mth.name
                    mParams = mth.parameterTypes.joinToString(",") { it.toString() }
                    mRet = mth.returnType
                    btnClear.isEnabled = true
                    rows.clear()
                    rows.addAll(newRows)
                    kinds.clear()
                    kinds.addAll(newKinds)
                    rowAddrs.clear()
                    rowAddrs.addAll(newAddrs)
                    insRefs.clear()
                    insRefs.addAll(newRefs)
                    validAddrs.clear()
                    validAddrs.addAll(newValid)
                    targetIndex = tiFinal
                    adapter?.notifyDataSetChanged()
                    if (tiFinal >= 0) {
                        listView.post { listView.setSelection(maxOf(0, tiFinal - 5)) }
                    }
                }
            } catch (t: Throwable) {
                val msg = t.message ?: "?"
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_msg_plain, msg), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            workDir?.deleteRecursively()
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val s = stubChoice
        if (s != null || editMap.isNotEmpty() || extraEdits.isNotEmpty()) {
            val arr = ArrayList<String>()
            if (s != null) {
                arr.add("S|" + classType + "|" + mName + "|" + mParams + "|" + mRet + "|" + s)
                for (e in extraEdits) {
                    val p = e.split("|")
                    val same = p.size >= 6 && p[0] == classType && p[1] == mName && p[2] == mParams && p[3] == mRet
                    if (!same) arr.add(e)
                }
            } else {
                for ((a, t) in editMap) {
                    arr.add(classType + "|" + mName + "|" + mParams + "|" + mRet + "|" + a + "|" + b64(t))
                }
                arr.addAll(extraEdits)
            }
            if (arr.isNotEmpty()) setResult(RESULT_OK, Intent().putStringArrayListExtra(RES_EDITS, arr))
        }
        super.onBackPressed()
    }

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    private fun copyText(s: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lazymod", s))
        Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show()
    }
    private fun methodRefOf(ins: Instruction): MethodReference? {
        if (ins is org.jf.dexlib2.iface.instruction.ReferenceInstruction) {
            val r = ins.reference
            if (r is MethodReference) return r
        }
        return null
    }

    private fun showInsMenu(position: Int, addr: Int, ins: Instruction, cur: String) {
        val mr = methodRefOf(ins)
        if (mr == null) {
            showInsEditDialog(position, addr, ins, cur)
            return
        }
        val clsDisp = mr.definingClass.removePrefix("L").removeSuffix(";").replace('/', '.')
        val shortCls = clsDisp.substringAfterLast('.')
        val items = arrayOf(
            "✏️ " + getString(R.string.code_edit_title),
            "→ " + getString(R.string.code_menu_goto) + ": " + shortCls + "." + mr.name
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.code_menu_title)
            .setItems(items) { _, idx ->
                if (idx == 0) {
                    showInsEditDialog(position, addr, ins, cur)
                } else {
                    gotoMethod(mr)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun gotoMethod(mref: MethodReference) {
        val path = intent.getStringExtra(EXTRA_APK_PATH) ?: return
        val params = mref.parameterTypes.joinToString(", ") { SmaliPrinter.prettyType(it.toString()) }
        val label = mref.name + "(" + params + ") : " + SmaliPrinter.prettyType(mref.returnType)
        val i = Intent(this, MethodCodeActivity::class.java).apply {
            putExtra(EXTRA_APK_PATH, path)
            putExtra(EXTRA_DEX_NAME, "")
            putExtra(EXTRA_CLASS_TYPE, mref.definingClass)
            putExtra(EXTRA_METHOD_NAME, mref.name)
            putExtra(EXTRA_METHOD_LABEL, label)
            putExtra(EXTRA_ADDR, -1)
            putExtra(EXTRA_FROM_JUMP, true)
        }
        gotoLauncher.launch(i)
    }

    private fun showInsEditDialog(position: Int, addr: Int, ins: Instruction, cur: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 4)
        }
        val tvWarn = TextView(this).apply {
            text = getString(R.string.code_edit_warn)
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        tvWarn.setTextColor(MaterialColors.getColor(tvWarn, com.google.android.material.R.attr.colorOnSurfaceVariant))
        container.addView(tvWarn)
        val et = EditText(this).apply {
            setText(cur)
            setSelection(cur.length)
        }
        container.addView(et)
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.code_edit_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val nv = et.text?.toString()?.trim() ?: return@setOnClickListener
            if (nv == cur) {
                dlg.dismiss()
                return@setOnClickListener
            }
            val newIns: Instruction
            try {
                newIns = DexCodeEditor.parseInstruction(nv, ins, addr, validAddrs, pBase)
            } catch (e: DexCodeEditor.ParseException) {
                Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            } catch (t: Throwable) {
                Toast.makeText(this, t.message ?: "?", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val canonical = SmaliPrinter.format(newIns, addr, pBase)
            if (stubChoice != null) {
                stubChoice = null
                Toast.makeText(this, getString(R.string.code_clear_cancelled), Toast.LENGTH_SHORT).show()
            }
            editMap[addr] = canonical
            insRefs[position] = newIns
            rows[position] = canonical
            adapter?.notifyDataSetChanged()
            updateEditCount()
            dlg.dismiss()
        }
    }

    private fun showClearMethodDialog() {
        val ret = mRet
        val opts: List<String> = when {
            ret == "V" -> listOf("void")
            ret == "Z" -> listOf("false", "true")
            ret == "I" || ret == "S" || ret == "B" || ret == "C" || ret == "F" -> listOf("0", "1")
            ret == "J" || ret == "D" -> listOf("0", "1")
            ret.startsWith("L") || ret.startsWith("[") -> listOf("null")
            else -> emptyList()
        }
        if (opts.isEmpty()) return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 8)
        }
        val tvLabel = TextView(this).apply {
            text = baseLabel
            textSize = 15f
        }
        container.addView(tvLabel)
        val tvWarn = TextView(this).apply {
            text = getString(R.string.code_clear_warn)
            textSize = 13f
            setPadding(0, 16, 0, 4)
        }
        tvWarn.setTextColor(MaterialColors.getColor(tvWarn, com.google.android.material.R.attr.colorOnSurfaceVariant))
        container.addView(tvWarn)
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.code_clear_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .create()
        for (opt in opts) {
            val b = MaterialButton(this).apply {
                text = opt
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }
            b.setOnClickListener {
                stubChoice = opt
                editMap.clear()
                updateEditCount()
                Toast.makeText(this, getString(R.string.code_clear_done, opt), Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
            container.addView(b)
        }
        dlg.show()
    }

    private fun updateEditCount() {
        val s = stubChoice
        if (s != null) {
            tvInfo.text = baseLabel + " · " + getString(R.string.code_clear_state, s)
            return
        }
        val n = editMap.size + extraEdits.size
        if (n == 0) {
            tvInfo.text = baseLabel
        } else {
            tvInfo.text = baseLabel + " · " + getString(R.string.code_edits_n, n)
        }
    }

    private fun paramRegCount(m: Method): Int {
        var n = if ((m.accessFlags and 0x8) != 0) 0 else 1
        for (pt in m.parameterTypes) {
            val s = pt.toString()
            n += if (s == "J" || s == "D") 2 else 1
        }
        return n
    }

    private fun methodHeader(m: Method): String {
        val sb = StringBuilder(".method ")
        val af = m.accessFlags
        if ((af and 0x1) != 0) sb.append("public ")
        else if ((af and 0x2) != 0) sb.append("private ")
        else if ((af and 0x4) != 0) sb.append("protected ")
        if ((af and 0x8) != 0) sb.append("static ")
        if ((af and 0x10) != 0) sb.append("final ")
        if ((af and 0x20) != 0) sb.append("synchronized ")
        if ((af and 0x40) != 0) sb.append("bridge ")
        if ((af and 0x80) != 0) sb.append("varargs ")
        if ((af and 0x100) != 0) sb.append("native ")
        if ((af and 0x400) != 0) sb.append("abstract ")
        if ((af and 0x1000) != 0) sb.append("strictfp ")
        if ((af and 0x10000) != 0) sb.append("constructor ")
        sb.append(m.name).append('(')
        for (pt in m.parameterTypes) sb.append(pt.toString())
        sb.append(')').append(m.returnType)
        return sb.toString()
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(this@MethodCodeActivity).apply {
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
            }
            tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            when (kinds.getOrElse(position) { 1 }) {
                0 -> {
                    tv.textSize = 12f
                    tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorPrimary))
                    tv.setBackgroundColor(Color.TRANSPARENT)
                    tv.setPadding(dp(8), dp(2), dp(8), dp(2))
                }
                2 -> {
                    tv.textSize = 13f
                    val hlDark = ThemeHelper.isDark(this@MethodCodeActivity)
                    tv.setTextColor(if (hlDark) 0xFFFFF176.toInt() else 0xFF000000.toInt())
                    tv.setBackgroundColor(if (hlDark) 0xFF665500.toInt() else 0xFFFFF176.toInt())
                    tv.setPadding(dp(8), dp(3), dp(8), dp(3))
                }
                else -> {
                    tv.textSize = 13f
                    tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurface))
                    tv.setBackgroundColor(Color.TRANSPARENT)
                    tv.setPadding(dp(8), dp(3), dp(8), dp(3))
                }
            }
            tv.text = rows[position]
            return tv
        }
    }
}