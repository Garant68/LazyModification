package com.example.lazymodification

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.StubEdit
import com.example.lazymodification.utils.SmaliPrinter
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.reference.StringReference
import java.io.File

/**
 * «Заглушить метод» — мини-редактор dex:
 * браузер классов (поиск по имени) → методы → замена тела на return 0/false/void/null.
 */
class DexEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.DEX_EDITOR_APK_PATH"
        private const val MODE_CLASS = 0
        private const val MODE_METHOD = 1
        private const val MODE_STRING = 2
    }

    private data class ClassEntry(val type: String, val display: String, val dexName: String)

    private sealed class Row {
        class PkgRow(val pkg: String) : Row()
        class ClassRow(val type: String, val display: String, val dexName: String) : Row()
        class MethodRow(val type: String, val dexName: String, val label: String, val methodName: String, val q: String) : Row()
        class InstrRow(val type: String, val dexName: String, val label: String, val methodName: String, val text: String, val q: String, val vStart: Int, val vEnd: Int, val addr: Int) : Row()
    }

    private class InstrInfo(val text: String, val vStart: Int, val vEnd: Int, val addr: Int, val hq: String = "")

    private class TempHit(val ei: Int, val type: String, val dexName: String, val mName: String, val label: String, val instrs: ArrayList<InstrInfo>)

    private lateinit var etSearch: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvQueue: TextView
    private lateinit var btnApply: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var listView: ListView
    private lateinit var rgSearchMode: RadioGroup
    private lateinit var btnSearchHistory: TextView
    private lateinit var adapter: ArrayAdapter<String>

    private val entries = ArrayList<ClassEntry>()
    private val filtered = ArrayList<ClassEntry>()
    private val dexCache = HashMap<String, DexBackedDexFile>()
    private val queue = ArrayList<StubEdit>()
    private val codeQueue = ArrayList<String>()
    private var apkPath: String? = null
    private var workDir: File? = null

    private var searchMode = MODE_METHOD
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var pendingHistorySave: Runnable? = null
    private var searchGen = 0
    private val rows = ArrayList<Row>()
    private var showingClasses = false
    private var resultAdapter: ResultAdapter? = null
    private val entryIndex = HashMap<String, Int>()

    private var pendingV1 = true
    private var pendingV2 = true
    private var pendingV3 = true
    private var pendingSignConfirm: ((SignatureConfig) -> Unit)? = null

    private val keystoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val file = SigningUtils.copyKeystoreToCache(this, uri)
            if (file != null) {
                SigningUtils.showKeystorePasswordDialog(this, file, pendingV1, pendingV2, pendingV3) { config ->
                    pendingSignConfirm?.invoke(config)
                    pendingSignConfirm = null
                }
            } else {
                Toast.makeText(this, getString(R.string.err_keystore_read_failed), Toast.LENGTH_LONG).show()
                pendingSignConfirm = null
            }
        } else {
            Toast.makeText(this, getString(R.string.err_keystore_not_selected), Toast.LENGTH_SHORT).show()
            pendingSignConfirm = null
        }
    }

    private val codeViewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val data = res.data ?: return@registerForActivityResult
        val edits = data.getStringArrayListExtra(MethodCodeActivity.RES_EDITS) ?: ArrayList<String>()
        if (edits.isNotEmpty()) {
            var added = 0
            var addedStubs = 0
            var lastStubLabel = ""
            var lastStubValue = ""
            for (e in edits) {
                if (e.startsWith("S|")) {
                    val p = e.split("|")
                    if (p.size >= 6) {
                        val params = if (p[3].isEmpty()) emptyList<String>() else p[3].split(",")
                        if (queue.any { it.className == p[1] && it.methodName == p[2] && it.paramTypes == params && it.returnType == p[4] }) {
                            Toast.makeText(this, getString(R.string.dex_editor_already), Toast.LENGTH_SHORT).show()
                        } else {
                            queue.add(StubEdit(p[1], p[2], params, p[4], p[5]))
                            addedStubs++
                            lastStubLabel = p[1].removePrefix("L").removeSuffix(";").substringAfterLast('/') + "." + p[2]
                            lastStubValue = p[5]
                        }
                    }
                } else if (!codeQueue.contains(e)) {
                    codeQueue.add(e)
                    added++
                }
            }
            refreshQueue()
            if (addedStubs > 0) {
                Toast.makeText(this, getString(R.string.dex_editor_added, lastStubLabel, lastStubValue, queue.size), Toast.LENGTH_SHORT).show()
            }
            if (added > 0) {
                Toast.makeText(this, getString(R.string.dex_editor_replace_added, added), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_dex_editor)

        etSearch = findViewById(R.id.etDexSearch)
        tvStatus = findViewById(R.id.tvDexStatus)
        tvQueue = findViewById(R.id.tvDexQueue)
        btnApply = findViewById(R.id.btnDexApply)
        btnClear = findViewById(R.id.btnDexClear)
        listView = findViewById(R.id.lvDexClasses)
        rgSearchMode = findViewById(R.id.rgSearchMode)
        btnSearchHistory = findViewById(R.id.btnSearchHistory)
        btnSearchHistory.setOnClickListener { showSearchHistory() }

        apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath == null) {
            Toast.makeText(this, getString(R.string.file_not_selected_err), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        listView.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dispatchSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        rgSearchMode.setOnCheckedChangeListener { _, checkedId ->
            searchMode = when (checkedId) {
                R.id.rbModeMethod -> MODE_METHOD
                R.id.rbModeString -> MODE_STRING
                else -> MODE_CLASS
            }
            etSearch.hint = when (searchMode) {
                MODE_METHOD -> getString(R.string.dex_editor_search_method_hint)
                MODE_STRING -> getString(R.string.dex_editor_search_string_hint)
                else -> getString(R.string.dex_editor_search_hint)
            }
            pendingSearch?.let { searchHandler.removeCallbacks(it) }
            dispatchSearch(etSearch.text?.toString() ?: "")
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (showingClasses) {
                val e = filtered.getOrNull(position) ?: return@setOnItemClickListener
                openClass(e)
            } else {
                val r = rows.getOrNull(position) ?: return@setOnItemClickListener
                when (r) {
                    is Row.PkgRow -> Unit
                    is Row.ClassRow -> openClass(ClassEntry(r.type, r.display, r.dexName))
                    is Row.MethodRow -> openMethodCode(r.type, r.dexName, r.label, r.methodName)
                    is Row.InstrRow -> openCode(r)
                }
            }
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (showingClasses) {
                val e = filtered.getOrNull(position)
                if (e != null) copyText(e.display)
            } else {
                val r = rows.getOrNull(position)
                if (r != null) {
                    val t = when (r) {
                        is Row.PkgRow -> r.pkg
                        is Row.ClassRow -> r.display
                        is Row.MethodRow -> r.label
                        is Row.InstrRow -> r.text
                    }
                    copyText(t)
                }
            }
            true
        }

        btnClear.setOnClickListener {
            queue.clear()
            codeQueue.clear()
            refreshQueue()
        }
        btnApply.setOnClickListener { showSigningDialog() }
        refreshQueue()

        loadDexes(apkPath!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { workDir?.deleteRecursively() } catch (_: Throwable) {}
    }

    private fun loadDexes(path: String) {
        Thread {
            try {
                val apk = File(path)
                val dir = File(cacheDir, "dexedit_" + System.currentTimeMillis())
                dir.mkdirs()
                workDir = dir
                val dexFiles = ApkUtils.extractAllDex(apk, dir)
                val list = ArrayList<ClassEntry>()
                for (df in dexFiles) {
                    try {
                        val dex = DexFileFactory.loadDexFile(df, Opcodes.forApi(28))
                        for (c in dex.classes) {
                            list.add(ClassEntry(c.type, prettyType(c.type), df.name))
                        }
                        dexCache[df.name] = dex
                    } catch (_: Throwable) {
                    }
                }
                val idxMap = HashMap<String, Int>(list.size * 2)
                for (i in list.indices) idxMap[list[i].type] = i
                runOnUiThread {
                    entries.clear()
                    entries.addAll(list)
                    entryIndex.clear()
                    entryIndex.putAll(idxMap)
                    applyClassFilter(etSearch.text?.toString() ?: "")
                    tvStatus.text = getString(R.string.dex_editor_classes_found, entries.size)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    tvStatus.text = getString(R.string.error_msg_plain, t.message ?: "?")
                }
            }
        }.start()
    }

    private fun applyClassFilter(query: String) {
        showingClasses = true
        if (listView.adapter !== adapter) listView.adapter = adapter
        filtered.clear()
        val q = query.trim()
        if (q.isEmpty()) {
            filtered.addAll(entries.take(300))
        } else {
            for (e in entries) {
                if (e.display.contains(q, ignoreCase = true)) {
                    filtered.add(e)
                    if (filtered.size >= 500) break
                }
            }
        }
        val labels = ArrayList<String>(filtered.size)
        for (e in filtered) labels.add(e.display)
        adapter.setNotifyOnChange(false)
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
        adapter.setNotifyOnChange(true)
    }

    // ============================================================
    // ПОИСК: пусто - список классов; иначе методы / строки
    // ============================================================
    private fun dispatchSearch(q: String) {
        if (searchMode == MODE_CLASS) {
            searchGen++
            applyClassFilter(q)
            tvStatus.text = getString(R.string.dex_editor_classes_found, entries.size)
        } else {
            scheduleSearch(q)
        }
    }

    private fun scheduleSearch(q: String) {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        val r = Runnable { startSearch(q) }
        pendingSearch = r
        searchHandler.postDelayed(r, 400L)
    }

    private fun startSearch(qRaw: String) {
        val q = qRaw.trim()
        val gen = ++searchGen
        if (searchMode == MODE_CLASS) return
        if (q.isEmpty()) {
            applyClassFilter("")
            tvStatus.text = getString(R.string.dex_editor_classes_found, entries.size)
            return
        }
        if (searchMode == MODE_METHOD && q.length < 2) return
        if (searchMode == MODE_STRING && q.length < 3) return
        pendingHistorySave?.let { searchHandler.removeCallbacks(it) }
        val hs = Runnable { addSearchHistory(q) }
        pendingHistorySave = hs
        searchHandler.postDelayed(hs, 3000L)
        when (searchMode) {
            MODE_METHOD -> {
                tvStatus.text = getString(R.string.dex_editor_searching)
                Thread {
                    try {
                        doMethodSearch(q, gen)
                    } catch (t: Throwable) {
                        val msg = t.message ?: t.javaClass.simpleName
                        runOnUiThread { if (gen == searchGen) tvStatus.text = getString(R.string.error_msg_plain, msg) }
                    }
                }.start()
            }
            MODE_STRING -> {
                tvStatus.text = getString(R.string.dex_editor_searching)
                Thread { doStringSearch(q, gen) }.start()
            }
        }
    }


    private fun loadSearchHistory(): MutableList<String> {
        val sp = getSharedPreferences("lm_search", Context.MODE_PRIVATE)
        val s = sp.getString("dex_history", "") ?: ""
        val out = ArrayList<String>()
        if (s.isNotEmpty()) {
            for (x in s.split("\n")) {
                if (x.isNotBlank()) out.add(x)
            }
        }
        return out
    }

    private fun saveSearchHistory(items: List<String>) {
        val sp = getSharedPreferences("lm_search", Context.MODE_PRIVATE)
        sp.edit().putString("dex_history", items.joinToString("\n")).apply()
    }

    private fun addSearchHistory(q: String) {
        val items = loadSearchHistory()
        items.remove(q)
        items.add(0, q)
        while (items.size > 20) items.removeAt(items.size - 1)
        saveSearchHistory(items)
    }

    private inner class HistoryAdapter(val items: MutableList<String>) : BaseAdapter() {
        var onDelete: ((Int) -> Unit)? = null
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = LinearLayout(this@DexEditorActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(dp(8), 0, 0, 0)
            val tv = TextView(this@DexEditorActivity)
            tv.text = items[position]
            tv.textSize = 15f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setPadding(dp(8), dp(12), dp(8), dp(12))
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val del = TextView(this@DexEditorActivity)
            del.text = "×"
            del.textSize = 18f
            del.setPadding(dp(16), dp(8), dp(16), dp(8))
            del.setOnClickListener { onDelete?.invoke(position) }
            row.addView(tv)
            row.addView(del)
            return row
        }
    }

    private fun showSearchHistory() {
        val items = loadSearchHistory()
        if (items.isEmpty()) {
            Toast.makeText(this, getString(R.string.search_history_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val list = ListView(this)
        val ad = HistoryAdapter(items)
        list.adapter = ad
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.search_history_title)
            .setView(list)
            .setNegativeButton(R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val q = items.getOrNull(position) ?: return@setOnItemClickListener
            etSearch.setText(q)
            etSearch.setSelection(q.length)
            dlg.dismiss()
        }
        ad.onDelete = { position ->
            if (position in items.indices) {
                items.removeAt(position)
                saveSearchHistory(items)
                ad.notifyDataSetChanged()
                if (items.isEmpty()) dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun doMethodSearch(q: String, gen: Int) {
        val re = try { Regex(q, RegexOption.IGNORE_CASE) } catch (_: Throwable) { null }
        val nameByEi = LinkedHashMap<Int, ArrayList<Pair<String, String>>>()
        val codeByEi = LinkedHashMap<Int, ArrayList<TempHit>>()
        val dexEntries = ArrayList(dexCache.entries)
        val total = dexEntries.size
        var di = 0
        var leafName = 0
        var leafCode = 0
        outer@ for (entry in dexEntries) {
            di++
            if (gen != searchGen) return
            val dexName = entry.key
            val dex = entry.value
            runOnUiThread {
                if (gen == searchGen) tvStatus.text = getString(R.string.dex_editor_searching_progress, di, total)
            }
            for (c in dex.classes) {
                if (gen != searchGen) return
                val ei = entryIndex[c.type] ?: continue
                for (m in c.methods) {
                    if (gen != searchGen) return
                    if (m.name == "<init>" || m.name == "<clinit>") continue
                    val impl = m.implementation ?: continue
                    val nmHit = if (re != null) re.containsMatchIn(m.name) || m.name.contains(q, ignoreCase = true) else m.name.contains(q, ignoreCase = true)
                    if (nmHit && leafName < 300) {
                        nameByEi.getOrPut(ei) { ArrayList() }.add(m.name to methodLabel(m))
                        leafName++
                    }
                    var instrs: ArrayList<InstrInfo>? = null
                    var addr = 0
                    val pb = impl.registerCount - paramRegCountInt(m)
                    try {
                        for (ins in impl.instructions) {
                            val text = SmaliPrinter.format(ins, addr, pb)
                            var hs = -1
                            var he = -1
                            if (re != null) {
                                val mres = re.find(text)
                                if (mres != null) {
                                    hs = mres.range.first
                                    he = mres.range.last + 1
                                }
                            }
                            if (hs < 0) {
                                val li = indexOfIgnoreCase(text, q)
                                if (li >= 0) {
                                    hs = li
                                    he = li + q.length
                                }
                            }
                            if (hs >= 0) {
                                if (instrs == null) instrs = ArrayList()
                                val cur = instrs
                                if (cur != null) {
                                    val hq = if (he > hs) text.substring(hs, he) else ""
                                    cur.add(InstrInfo(text, hs, he, addr, hq))
                                    if (cur.size >= 10) break
                                }
                            }
                            addr += ins.codeUnits
                        }
                    } catch (_: Throwable) {
                        instrs = null
                    }
                    val fin = instrs
                    if (fin != null && fin.isNotEmpty()) {
                        codeByEi.getOrPut(ei) { ArrayList() }.add(TempHit(ei, c.type, dexName, m.name, methodLabel(m), fin))
                        leafCode += fin.size
                        if (leafCode >= 300) break@outer
                    }
                }
            }
        }
        val involved = LinkedHashSet<Int>()
        involved.addAll(nameByEi.keys)
        involved.addAll(codeByEi.keys)
        val sortedEis = involved.sortedWith(Comparator { a, b ->
            val c = pkgOf(entries.getOrNull(a)?.type ?: "").compareTo(pkgOf(entries.getOrNull(b)?.type ?: ""))
            if (c != 0) c else a.compareTo(b)
        })
        val out = ArrayList<Row>()
        var lastPkg = ""
        var lastType = ""
        var leaf = 0
        for (ei in sortedEis) {
            if (gen != searchGen) return
            val e = entries.getOrNull(ei) ?: continue
            val pkg = pkgOf(e.type)
            if (pkg != lastPkg) {
                out.add(Row.PkgRow(pkg))
                lastPkg = pkg
                lastType = ""
            }
            if (e.type != lastType) {
                out.add(Row.ClassRow(e.type, shortOf(e.type), e.dexName))
                lastType = e.type
            }
            val mrows = nameByEi[ei]
            if (mrows != null) {
                mrows.sortWith(Comparator { a, b -> a.first.compareTo(b.first) })
                for ((nm, label) in mrows) {
                    out.add(Row.MethodRow(e.type, e.dexName, label, nm, methodHighlightQ(nm, q, re)))
                    leaf++
                }
            }
            val crows = codeByEi[ei]
            if (crows != null) {
                crows.sortWith(Comparator { a, b -> a.mName.compareTo(b.mName) })
                for (th in crows) {
                    for (ix in th.instrs) {
                        out.add(Row.InstrRow(th.type, th.dexName, th.label, th.mName, ix.text, ix.hq, ix.vStart, ix.vEnd, ix.addr))
                        leaf++
                    }
                }
            }
        }
        val res = out
        runOnUiThread {
            if (gen == searchGen) {
                if (leaf == 0) showRows(res, getString(R.string.dex_editor_not_found))
                else showRows(res, getString(R.string.dex_editor_found, leaf))
            }
        }
    }

    private fun doStringSearch(q: String, gen: Int) {
        val re = try { Regex(q, RegexOption.IGNORE_CASE) } catch (_: Throwable) { null }
        val temps = ArrayList<TempHit>()
        val dexEntries = ArrayList(dexCache.entries)
        val total = dexEntries.size
        var di = 0
        var leaf = 0
        outer@ for (entry in dexEntries) {
            di++
            if (gen != searchGen) return
            val dexName = entry.key
            val dex = entry.value
            runOnUiThread {
                if (gen == searchGen) tvStatus.text = getString(R.string.dex_editor_searching_progress, di, total)
            }
            for (c in dex.classes) {
                if (gen != searchGen) return
                val ei = entryIndex[c.type] ?: continue
                for (m in c.methods) {
                    if (m.name == "<init>" || m.name == "<clinit>") continue
                    val impl = m.implementation ?: continue
                    var instrs: ArrayList<InstrInfo>? = null
                    var addr = 0
                    for (ins in impl.instructions) {
                        if (ins is org.jf.dexlib2.iface.instruction.ReferenceInstruction) {
                            val r = ins.reference
                            if (r is StringReference && (if (re != null) re.containsMatchIn(r.string) || r.string.contains(q, ignoreCase = true) else r.string.contains(q, ignoreCase = true))) {
                                var reg = "?"
                                if (ins is org.jf.dexlib2.iface.instruction.OneRegisterInstruction) {
                                    reg = "v" + ins.registerA
                                }
                                var dispVal = escapeDisplay(r.string)
                                if (dispVal.length > 160) dispVal = dispVal.substring(0, 160) + "..."
                                val prefix = "const-string " + reg + ", \""
                                val text = prefix + dispVal + "\""
                                if (instrs == null) instrs = ArrayList<InstrInfo>()
                                val cur = instrs
                                if (cur != null) {
                                    cur.add(InstrInfo(text, prefix.length, prefix.length + dispVal.length, addr))
                                    if (cur.size >= 10) break
                                }
                            }
                        }
                        addr += ins.codeUnits
                    }
                    val fin = instrs
                    if (fin != null && fin.isNotEmpty()) {
                        val label = methodLabel(m)
                        temps.add(TempHit(ei, c.type, dexName, m.name, label, fin))
                        leaf += fin.size
                        if (leaf >= 500) break@outer
                    }
                }
            }
        }
        temps.sortWith(Comparator { a, b ->
            val c = pkgOf(a.type).compareTo(pkgOf(b.type))
            if (c != 0) c else a.ei.compareTo(b.ei)
        })
        val out = ArrayList<Row>()
        var lastPkg = ""
        var lastType = ""
        for (th in temps) {
            if (gen != searchGen) return
            val pkg = pkgOf(th.type)
            if (pkg != lastPkg) {
                out.add(Row.PkgRow(pkg))
                lastPkg = pkg
                lastType = ""
            }
            if (th.type != lastType) {
                out.add(Row.ClassRow(th.type, shortOf(th.type), th.dexName))
                lastType = th.type
            }
            for (ix in th.instrs) {
                out.add(Row.InstrRow(th.type, th.dexName, th.label, th.mName, ix.text, if (ix.hq.isEmpty()) q else ix.hq, ix.vStart, ix.vEnd, ix.addr))
            }
        }
        val res = out
        runOnUiThread {
            if (gen == searchGen) {
                if (leaf == 0) showRows(res, getString(R.string.dex_editor_not_found))
                else showRows(res, getString(R.string.dex_editor_found, leaf))
            }
        }
    }

    private fun hitLabelFor(e: ClassEntry, methodName: String): String? {
        val dex = dexCache[e.dexName] ?: return null
        var cd: ClassDef? = null
        for (c in dex.classes) {
            if (c.type == e.type) { cd = c; break }
        }
        val classDef = cd ?: return null
        for (m in classDef.methods) {
            if (m.name == "<init>" || m.name == "<clinit>") continue
            if (m.implementation == null) continue
            if (m.name == methodName) return methodLabel(m)
        }
        return null
    }

    private fun showRows(list: List<Row>, status: String?) {
        showingClasses = false
        rows.clear()
        rows.addAll(list)
        var ra = resultAdapter
        if (ra == null) {
            ra = ResultAdapter()
            resultAdapter = ra
        }
        if (listView.adapter !== ra) listView.adapter = ra
        ra.notifyDataSetChanged()
        listView.setSelection(0)
        if (status != null) tvStatus.text = status
    }

    private inner class ResultAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(this@DexEditorActivity).apply {
                layoutParams = AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT,
                    AbsListView.LayoutParams.WRAP_CONTENT
                )
            }
            when (val r = rows[position]) {
                is Row.PkgRow -> {
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.textSize = 14f
                    tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorPrimary))
                    tv.text = if (r.pkg.isEmpty()) getString(R.string.dex_editor_no_pkg) else r.pkg
                    tv.setPadding(dp(12), dp(12), dp(12), dp(4))
                }
                is Row.ClassRow -> {
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    tv.textSize = 14f
                    tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurface))
                    tv.text = r.display
                    tv.setPadding(dp(28), dp(6), dp(12), dp(4))
                }
                is Row.MethodRow -> {
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    tv.textSize = 14f
                    tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurface))
                    tv.text = highlightText(r.label, r.q, 0, r.label.length)
                    tv.setPadding(dp(44), dp(6), dp(12), dp(4))
                }
                is Row.InstrRow -> {
                    tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    tv.textSize = 13f
                    tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    tv.text = highlightText(r.text, r.q, r.vStart, r.vEnd)
                    tv.setPadding(dp(44), dp(4), dp(12), dp(6))
                }
            }
            return tv
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun highlightText(s: String, q: String, from: Int, to: Int): CharSequence {
        if (q.isEmpty()) return s
        val end = minOf(to, s.length)
        if (from >= end || end - from < q.length) return s
        val sp = SpannableString(s)
        var i = from
        val last = end - q.length
        while (i <= last) {
            if (s.regionMatches(i, q, 0, q.length, true)) {
                sp.setSpan(BackgroundColorSpan(0xFFFFF176.toInt()), i, i + q.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i += q.length
            } else {
                i++
            }
        }
        return sp
    }

    private fun escapeDisplay(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun pkgOf(type: String): String {
        val dotted = prettyType(type)
        val i = dotted.lastIndexOf('.')
        return if (i > 0) dotted.substring(0, i) else ""
    }

    private fun shortOf(type: String): String {
        val dotted = prettyType(type)
        val i = dotted.lastIndexOf('.')
        return if (i >= 0) dotted.substring(i + 1) else dotted
    }

    private fun openMethodCode(type: String, dexName: String, label: String, methodName: String) {
        val path = apkPath ?: return
        val i = Intent(this, MethodCodeActivity::class.java).apply {
            putExtra(MethodCodeActivity.EXTRA_APK_PATH, path)
            putExtra(MethodCodeActivity.EXTRA_DEX_NAME, dexName)
            putExtra(MethodCodeActivity.EXTRA_CLASS_TYPE, type)
            putExtra(MethodCodeActivity.EXTRA_METHOD_NAME, methodName)
            putExtra(MethodCodeActivity.EXTRA_METHOD_LABEL, label)
            putExtra(MethodCodeActivity.EXTRA_QUERY, "")
            putExtra(MethodCodeActivity.EXTRA_ADDR, -1)
        }
        codeViewerLauncher.launch(i)
    }

    private fun copyText(s: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lazymod", s))
        Toast.makeText(this, R.string.copy_done, Toast.LENGTH_SHORT).show()
    }
    private fun openCode(r: Row.InstrRow) {
        val path = apkPath ?: return
        val i = Intent(this, MethodCodeActivity::class.java).apply {
            putExtra(MethodCodeActivity.EXTRA_APK_PATH, path)
            putExtra(MethodCodeActivity.EXTRA_DEX_NAME, r.dexName)
            putExtra(MethodCodeActivity.EXTRA_CLASS_TYPE, r.type)
            putExtra(MethodCodeActivity.EXTRA_METHOD_NAME, r.methodName)
            putExtra(MethodCodeActivity.EXTRA_METHOD_LABEL, r.label)
            putExtra(MethodCodeActivity.EXTRA_QUERY, r.q)
            putExtra(MethodCodeActivity.EXTRA_ADDR, r.addr)
        }
        codeViewerLauncher.launch(i)
    }

    private fun openClass(entry: ClassEntry) {
        val dex = dexCache[entry.dexName] ?: return
        var found: ClassDef? = null
        for (c in dex.classes) {
            if (c.type == entry.type) { found = c; break }
        }
        val classDef = found ?: return
        val methods = ArrayList<Method>()
        for (m in classDef.methods) {
            if (m.name == "<init>" || m.name == "<clinit>") continue
            if (m.implementation == null) continue
            methods.add(m)
            if (methods.size >= 1500) break
        }
        if (methods.isEmpty()) {
            Toast.makeText(this, getString(R.string.dex_editor_no_methods), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = methods.map { m -> methodLabel(m) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(entry.display)
            .setItems(labels) { _, index -> pickStub(entry, methods[index]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickStub(entry: ClassEntry, m: Method) {
        val ret = m.returnType
        val opts: List<String> = when {
            ret == "V" -> listOf("void")
            ret == "Z" -> listOf("false", "true")
            ret == "I" || ret == "S" || ret == "B" || ret == "C" || ret == "F" -> listOf("0", "1")
            ret == "J" || ret == "D" -> listOf("0", "1")
            ret.startsWith("L") || ret.startsWith("[") -> listOf("null")
            else -> emptyList()
        }
        if (opts.isEmpty()) return
        val label = methodLabel(m)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 24, 56, 8)
        }
        val tvLabel = TextView(this).apply {
            text = label
            textSize = 15f
        }
        container.addView(tvLabel)
        val tvWarn = TextView(this).apply {
            text = getString(R.string.dex_editor_warn)
            textSize = 13f
            setPadding(0, 16, 0, 4)
        }
        tvWarn.setTextColor(MaterialColors.getColor(tvWarn, com.google.android.material.R.attr.colorOnSurfaceVariant))
        container.addView(tvWarn)
        val stubDialog = AlertDialog.Builder(this)
            .setTitle(R.string.dex_editor_stub_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .create()
        for (opt in opts) {
            val btn = MaterialButton(this).apply {
                text = opt
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }
            btn.setOnClickListener {
                stubDialog.dismiss()
                confirmStub(entry, m, opt)
            }
            container.addView(btn)
        }
        stubDialog.show()
    }

    private fun confirmStub(entry: ClassEntry, m: Method, stub: String) {
        val params = m.parameterTypes.map { it.toString() }
        confirmStubRaw(entry.type, m.name, params, m.returnType, stub, methodLabel(m))
    }

    private fun confirmStubRaw(type: String, methodName: String, params: List<String>, ret: String, stub: String, label: String) {
        if (queue.any { it.className == type && it.methodName == methodName && it.paramTypes == params && it.returnType == ret }) {
            Toast.makeText(this, getString(R.string.dex_editor_already), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dex_editor_confirm, label, stub))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                queue.add(StubEdit(type, methodName, params, ret, stub))
                refreshQueue()
                Toast.makeText(this, getString(R.string.dex_editor_added, label, stub, queue.size), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshQueue() {
        val total = queue.size + codeQueue.size
        tvQueue.text = getString(R.string.dex_editor_queue_hint, total)
        btnApply.text = getString(R.string.dex_editor_apply, total)
        btnApply.isEnabled = total > 0
    }

    private fun showSigningDialog() {
        if (queue.isEmpty() && codeQueue.isEmpty()) return
        pendingSignConfirm = { config -> startPatching(config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> startPatching(config) }
        )
    }

    private fun startPatching(config: SignatureConfig) {
        val path = apkPath ?: return
        val edits = ArrayList<String>()
        for (e in queue) {
            edits.add(e.className + "|" + e.methodName + "|" + e.paramTypes.joinToString(",") + "|" + e.returnType + "|" + e.stub)
        }
        val serviceIntent = Intent(this, PatchService::class.java).apply {
            putExtra(PatchService.EXTRA_APK_PATH, path)
            putStringArrayListExtra(PatchService.EXTRA_STUB_EDITS, edits)
            putStringArrayListExtra(PatchService.EXTRA_CODE_EDITS, codeQueue)
            putExtra(PatchService.EXTRA_NO_SIGN, config.noSign)
            putExtra(PatchService.EXTRA_SIGN_V1, config.signV1)
            putExtra(PatchService.EXTRA_SIGN_V2, config.signV2)
            putExtra(PatchService.EXTRA_SIGN_V3, config.signV3)
            putExtra(PatchService.EXTRA_CUSTOM_KEYSTORE_PATH, config.customKeystorePath)
            putExtra(PatchService.EXTRA_KEYSTORE_PASSWORD, config.keystorePassword)
            putExtra(PatchService.EXTRA_KEY_ALIAS, config.keyAlias)
            putExtra(PatchService.EXTRA_KEY_PASSWORD, config.keyPassword)
            putExtra(PatchService.EXTRA_KEYSTORE_TYPE, config.keystoreType)
        }
        try {
            startForegroundService(serviceIntent)
            startActivity(Intent(this, LogActivity::class.java).apply {
                putExtra("SOURCE_APK_PATH", path)
            })
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_msg_plain, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun methodLabel(m: Method): String {
        val params = m.parameterTypes.joinToString(", ") { prettyType(it.toString()) }
        return m.name + "(" + params + ") : " + prettyType(m.returnType)
    }

    private fun paramRegCountInt(m: Method): Int {
        var n = if ((m.accessFlags and 0x8) != 0) 0 else 1
        for (pt in m.parameterTypes) {
            val s = pt.toString()
            n += if (s == "J" || s == "D") 2 else 1
        }
        return n
    }

    private fun indexOfIgnoreCase(s: String, q: String): Int {
        if (q.isEmpty()) return -1
        val last = s.length - q.length
        var i = 0
        while (i <= last) {
            if (s.regionMatches(i, q, 0, q.length, true)) return i
            i++
        }
        return -1
    }

    private fun methodHighlightQ(nm: String, q: String, re: Regex?): String {
        if (re == null) return q
        val m = re.find(nm) ?: return q
        if (m.range.isEmpty()) return q
        return nm.substring(m.range.first, m.range.last + 1)
    }

    private fun prettyType(t: String): String {
        var s = t
        var dims = 0
        while (s.startsWith("[")) { dims++; s = s.substring(1) }
        val base = when (s) {
            "V" -> "void"
            "Z" -> "boolean"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            else -> s.removePrefix("L").removeSuffix(";").replace('/', '.')
        }
        return base + "[]".repeat(dims)
    }
}