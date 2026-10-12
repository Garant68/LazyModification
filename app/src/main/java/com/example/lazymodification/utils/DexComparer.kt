package com.example.lazymodification.utils

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.debug.LineNumber
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import java.io.File
import java.security.MessageDigest

object DexComparer {

    private const val NOP_MARKER = Long.MIN_VALUE

    enum class LineStatus { SAME, CHANGED, REMOVED, ADDED }

    enum class ChangeKind { CHANGED, ADDED, REMOVED }

    data class DiffLine(
        val status: LineStatus,
        val left: String?,
        val right: String?,
        val leftNum: Int?,
        val rightNum: Int?
    )

    data class DiffResult(val lines: List<DiffLine>) {
        val changedCount: Int get() = lines.count { it.status != LineStatus.SAME }
    }

    data class ChangedClass(val type: String, val kind: ChangeKind)

    /** Гроверный примитивный буфер подписей (без боксинга Long — в разы меньше мусора). */
    private class LongSig {
        var array = LongArray(128)
        var size = 0

        fun add(v: Long) {
            if (size == array.size) array = array.copyOf(array.size * 2)
            array[size++] = v
        }

        fun sameAs(other: LongSig): Boolean {
            if (size != other.size) return false
            for (i in 0 until size) if (array[i] != other.array[i]) return false
            return true
        }
    }

    private fun compareLegacy(apkA: File, apkB: File, workDir: File, ignoreDebug: Boolean, ignoreNop: Boolean, ignoreAdCalls: Boolean, ignoreAnalytics: Boolean): List<ChangedClass> {
        val opcodes = Opcodes.forApi(28)
        val maps = loadBoth(apkA, apkB, workDir, opcodes)
        val classesA = maps.first
        val classesB = maps.second
        val result = mutableListOf<ChangedClass>()
        val allTypes = java.util.TreeSet<String>()
        allTypes.addAll(classesA.keys)
        allTypes.addAll(classesB.keys)
        for (type in allTypes) {
            val ca = classesA[type]
            val cb = classesB[type]
            // ⚡ Классы из байт-идентичных dex переиспользованы — сравнивать нечего
            if (ca != null && ca === cb) continue
            if (ignoreAdCalls) {
                if (ca != null && DexPatcher.hasAdCalls(ca)) continue
                if (cb != null && DexPatcher.hasAdCalls(cb)) continue
            }
            if (ignoreAnalytics) {
                if (ca != null && DexPatcher.hasAnalyticsCalls(ca)) continue
                if (cb != null && DexPatcher.hasAnalyticsCalls(cb)) continue
            }
            val kind = when {
                ca == null -> ChangeKind.ADDED
                cb == null -> ChangeKind.REMOVED
                isClassChanged(ca, cb, ignoreDebug, ignoreNop) -> ChangeKind.CHANGED
                else -> null
            }
            if (kind != null) result.add(ChangedClass(type, kind))
        }
        return result
    }

    fun compare(apkA: File, apkB: File, workDir: File, ignoreDebug: Boolean, ignoreNop: Boolean, ignoreAdCalls: Boolean, ignoreAnalytics: Boolean): List<ChangedClass> {
        if (ignoreDebug && !ignoreAdCalls && !ignoreAnalytics) {
            try {
                return compareFast(apkA, apkB, workDir, ignoreNop)
            } catch (_: Throwable) {
                // аварийный откат на полный dexlib2-путь
            }
        }
        return compareLegacy(apkA, apkB, workDir, ignoreDebug, ignoreNop, ignoreAdCalls, ignoreAnalytics)
    }

    /**
     * ⚡ Быстрый путь: подписи классов считает FastDexSig (без dexlib2-объектов),
     * байт-идентичные (по MD5) dex-файлы переиспользуются без сканирования.
     */
    private fun compareFast(apkA: File, apkB: File, workDir: File, ignoreNop: Boolean): List<ChangedClass> {
        val dirA = File(workDir, "a")
        dirA.mkdirs()
        val dirB = File(workDir, "b")
        dirB.mkdirs()
        val dexesA = ApkUtils.extractAllDex(apkA, dirA)
        val dexesB = ApkUtils.extractAllDex(apkB, dirB)
        val hashA = HashMap<String, String>()
        for (f in dexesA) hashA[f.name] = fileHash(f)
        val hashB = HashMap<String, String>()
        for (f in dexesB) hashB[f.name] = fileHash(f)

        val scanners = HashMap<String, FastDexSig>()
        val typeDexA = HashMap<String, String>()
        val typeDexB = HashMap<String, String>()
        try {
            for (f in dexesA) {
                val s = FastDexSig(f)
                scanners["A/" + f.name] = s
                s.forEachClassType { ty -> typeDexA[ty] = f.name }
                f.delete()
            }
            for (f in dexesB) {
                if (hashA[f.name] != null && hashA[f.name] == hashB[f.name]) {
                    f.delete()
                    continue
                }
                val s = FastDexSig(f)
                scanners["B/" + f.name] = s
                s.forEachClassType { ty -> typeDexB[ty] = f.name }
                f.delete()
            }
            for ((ty, dexName) in typeDexA) {
                if (hashA[dexName] != null && hashA[dexName] == hashB[dexName] && !typeDexB.containsKey(ty)) {
                    typeDexB[ty] = dexName
                }
            }
            val result = mutableListOf<ChangedClass>()
            val allTypes = java.util.TreeSet<String>()
            allTypes.addAll(typeDexA.keys)
            allTypes.addAll(typeDexB.keys)
            for (type in allTypes) {
                val da = typeDexA[type]
                val db = typeDexB[type]
                // классы байт-идентичного dex не изменились
                if (da != null && da == db && hashA[da] != null && hashA[da] == hashB[db]) continue
                val ca = da?.let { scanners["A/" + it] }?.classSignature(type)
                val cb = db?.let { scanners["B/" + it] }?.classSignature(type)
                val changed = if (ca != null && cb != null) {
                    if (ignoreNop) !sigEqIgnoringNop(ca, cb) else !sigSame(ca, cb)
                } else false
                val kind = when {
                    ca == null && cb == null -> null
                    ca == null -> ChangeKind.ADDED
                    cb == null -> ChangeKind.REMOVED
                    changed -> ChangeKind.CHANGED
                    else -> null
                }
                if (kind != null) result.add(ChangedClass(type, kind))
            }
            return result
        } finally {
            scanners.values.forEach { runCatching { it.close() } }
        }
    }

    private fun sigSame(a: FastDexSig.LongSig, b: FastDexSig.LongSig): Boolean {
        if (a.size != b.size) return false
        for (i in 0 until a.size) if (a.array[i] != b.array[i]) return false
        return true
    }

    private fun sigEqIgnoringNop(a: FastDexSig.LongSig, b: FastDexSig.LongSig): Boolean {
        var i = 0
        var j = 0
        val aa = a.array
        val bb = b.array
        while (i < a.size && j < b.size) {
            if (aa[i] == bb[j]) { i++; j++; continue }
            if (!isNopSig(aa[i]) && !isNopSig(bb[j])) return false
            var ii = i
            while (ii < a.size && isNopSig(aa[ii])) ii++
            if (ii < a.size && aa[ii] == bb[j]) { i = ii; continue }
            var jj = j
            while (jj < b.size && isNopSig(bb[jj])) jj++
            if (jj < b.size && aa[i] == bb[jj]) { j = jj; continue }
            i++
            j++
        }
        while (i < a.size) { if (!isNopSig(aa[i])) return false; i++ }
        while (j < b.size) { if (!isNopSig(bb[j])) return false; j++ }
        return true
    }

    private fun isNopSig(v: Long): Boolean = v == Long.MIN_VALUE
    fun diffClass(apkA: File, apkB: File, type: String, workDir: File, ignoreDebug: Boolean): DiffResult {
        val opcodes = Opcodes.forApi(28)
        val maps = loadBoth(apkA, apkB, workDir, opcodes)
        val ca = maps.first[type]
        val cb = maps.second[type]
        val ta = ca?.let { classToText(it, ignoreDebug) } ?: emptyList()
        val tb = cb?.let { classToText(it, ignoreDebug) } ?: emptyList()
        return DiffResult(diffLines(ta, tb, 1, 1))
    }

    fun diffTexts(a: List<String>, b: List<String>): DiffResult {
        return DiffResult(diffLines(a, b, 1, 1))
    }

    private fun isClassChanged(ca: ClassDef, cb: ClassDef, ignoreDebug: Boolean, ignoreNop: Boolean): Boolean {
        val sa = classSignature(ca, ignoreDebug)
        val sb = classSignature(cb, ignoreDebug)
        return if (ignoreNop) !equalIgnoringNop(sa, sb) else !sa.sameAs(sb)
    }

    private fun equalIgnoringNop(a: LongSig, b: LongSig): Boolean {
        var i = 0
        var j = 0
        val aa = a.array
        val bb = b.array
        while (i < a.size && j < b.size) {
            if (aa[i] == bb[j]) { i++; j++; continue }
            if (!isNopSignature(aa[i]) && !isNopSignature(bb[j])) return false

            var ii = i
            while (ii < a.size && isNopSignature(aa[ii])) ii++
            if (ii < a.size && aa[ii] == bb[j]) { i = ii; continue }

            var jj = j
            while (jj < b.size && isNopSignature(bb[jj])) jj++
            if (jj < b.size && aa[i] == bb[jj]) { j = jj; continue }

            i++
            j++
        }
        while (i < a.size) { if (!isNopSignature(aa[i])) return false; i++ }
        while (j < b.size) { if (!isNopSignature(bb[j])) return false; j++ }
        return true
    }

    private fun isNopSignature(sig: Long): Boolean = sig == NOP_MARKER

    private fun classSignature(classDef: ClassDef, ignoreDebug: Boolean): LongSig {
        val sig = LongSig()
        sig.add(classDef.type.hashCode().toLong())
        sig.add((classDef.superclass ?: "").hashCode().toLong())
        for (iface in classDef.interfaces) sig.add(iface.hashCode().toLong())
        val fields = (classDef.staticFields + classDef.instanceFields).sortedBy { it.name }
        for (field in fields) {
            sig.add(field.name.hashCode().toLong() * 31 + field.type.hashCode())
        }
        val methods = (classDef.directMethods + classDef.virtualMethods)
            .map { it to (it.name + it.parameterTypes.joinToString("") + it.returnType) }
            .sortedBy { it.second }
            .map { it.first }
        for (method in methods) {
            var mh = method.name.hashCode().toLong()
            for (p in method.parameterTypes) mh = mh * 31 + p.hashCode()
            mh = mh * 31 + method.returnType.hashCode()
            sig.add(mh)
            val impl = method.implementation
            if (impl != null) {
                for (instr in impl.instructions) {
                    sig.add(instructionSignature(instr))
                }
                if (!ignoreDebug) {
                    for (item in impl.debugItems) {
                        if (item is LineNumber) sig.add(item.lineNumber.toLong())
                    }
                }
            }
        }
        return sig
    }

    private fun instructionSignature(instr: Instruction): Long {
        if (instr.opcode == Opcode.NOP) return NOP_MARKER
        if (instr is TwoRegisterInstruction && instr.registerA == instr.registerB) {
            val op = instr.opcode
            if (op == Opcode.MOVE || op == Opcode.MOVE_OBJECT || op == Opcode.MOVE_WIDE) return NOP_MARKER
        }
        var h = instr.opcode.ordinal.toLong()
        if (instr is OneRegisterInstruction) h = h * 31 + instr.registerA
        if (instr is TwoRegisterInstruction) h = h * 31 + instr.registerA * 997 + instr.registerB
        if (instr is ThreeRegisterInstruction) h = h * 31 + instr.registerA * 994009 + instr.registerB * 997 + instr.registerC
        if (instr is FiveRegisterInstruction) h = h * 31 + instr.registerC + instr.registerD * 997 + instr.registerE * 994009
        if (instr is RegisterRangeInstruction) h = h * 31 + instr.startRegister + instr.registerCount * 997
        if (instr is NarrowLiteralInstruction) h = h * 31 + instr.narrowLiteral
        if (instr is WideLiteralInstruction) h = h * 31 + (instr.wideLiteral xor (instr.wideLiteral ushr 32)).toInt()
        if (instr is ReferenceInstruction) {
            when (val ref = instr.reference) {
                is StringReference -> h = h * 31 + ref.string.hashCode()
                is TypeReference -> h = h * 31 + ref.type.hashCode()
                is FieldReference -> h = h * 31 + ref.definingClass.hashCode() * 31 + ref.name.hashCode() * 31 + ref.type.hashCode()
                is MethodReference -> h = h * 31 + ref.definingClass.hashCode() * 31 + ref.name.hashCode()
            }
        }
        return h
    }

    /**
     * Загружает классы обоих APK. Dex-файлы, совпадающие по имени и содержимому (MD5),
     * переиспользуют объекты из A — для типичных сравнений (изменена пара dex) это
     * убирает почти всю работу.
     */
    private fun loadBoth(apkA: File, apkB: File, workDir: File, opcodes: Opcodes): Pair<Map<String, ClassDef>, Map<String, ClassDef>> {
        val dirA = File(workDir, "a")
        dirA.mkdirs()
        val dirB = File(workDir, "b")
        dirB.mkdirs()
        val dexesA = ApkUtils.extractAllDex(apkA, dirA)
        val dexesB = ApkUtils.extractAllDex(apkB, dirB)

        val hashA = HashMap<String, String>()
        for (f in dexesA) hashA[f.name] = fileHash(f)
        val hashB = HashMap<String, String>()
        for (f in dexesB) hashB[f.name] = fileHash(f)

        val mapA = HashMap<String, ClassDef>()
        val mapB = HashMap<String, ClassDef>()
        val typesInDexA = HashMap<String, List<String>>()
        for (f in dexesA) {
            val dex = DexFileFactory.loadDexFile(f, opcodes)
            val list = ArrayList<String>()
            for (c in dex.classes) {
                mapA[c.type] = c
                list.add(c.type)
            }
            typesInDexA[f.name] = list
            f.delete()
        }
        for (f in dexesB) {
            if (hashA[f.name] != null && hashA[f.name] == hashB[f.name]) {
                // dex байт-в-байт совпадает с таким же из A — переиспользуем классы
                for (t in typesInDexA[f.name] ?: emptyList<String>()) {
                    val cd = mapA[t]
                    if (cd != null) mapB[t] = cd
                }
                f.delete()
                continue
            }
            val dex = DexFileFactory.loadDexFile(f, opcodes)
            for (c in dex.classes) mapB[c.type] = c
            f.delete()
        }
        return mapA to mapB
    }

    private fun fileHash(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().buffered(1 shl 16).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        val d = md.digest()
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun classToText(classDef: ClassDef, ignoreDebug: Boolean): List<String> {
        val lines = mutableListOf<String>()
        lines.add(".class " + classDef.type)
        val fields = (classDef.staticFields + classDef.instanceFields).sortedBy { it.name }
        for (field in fields) {
            lines.add("  .field " + field.name + ":" + field.type)
        }
        val methods = (classDef.directMethods + classDef.virtualMethods)
            .sortedBy { it.name + it.parameterTypes.joinToString("") + it.returnType }
        for (method in methods) {
            lines.add("  .method " + method.name + "(" + method.parameterTypes.joinToString("") + ")" + method.returnType)
            val impl = method.implementation
            if (impl != null) {
                for (instr in impl.instructions) {
                    lines.add("    " + formatInstruction(instr))
                }
                if (!ignoreDebug) {
                    for (item in impl.debugItems) {
                        if (item is LineNumber) {
                            lines.add("    .line " + item.lineNumber)
                        }
                    }
                }
            }
        }
        return lines
    }

    private fun formatInstruction(instr: Instruction): String {
        val sb = StringBuilder(instr.opcode.name)
        when (instr) {
            is OneRegisterInstruction -> sb.append(" v").append(instr.registerA)
            is TwoRegisterInstruction -> sb.append(" v").append(instr.registerA).append(", v").append(instr.registerB)
            is ThreeRegisterInstruction -> sb.append(" v").append(instr.registerA).append(", v").append(instr.registerB).append(", v").append(instr.registerC)
            is FiveRegisterInstruction -> {
                val regs = listOf(instr.registerC, instr.registerD, instr.registerE, instr.registerF, instr.registerG)
                val used = regs.take(instr.registerCount)
                sb.append(" {v").append(used.joinToString(", v")).append("}")
            }
            is RegisterRangeInstruction -> {
                val start = instr.startRegister
                val end = start + instr.registerCount - 1
                sb.append(" {v").append(start).append(" .. v").append(end).append("}")
            }
        }
        when (instr) {
            is NarrowLiteralInstruction -> sb.append(", #0x").append(instr.narrowLiteral.toString(16))
            is WideLiteralInstruction -> sb.append(", #0x").append(instr.wideLiteral.toString(16))
        }
        if (instr is ReferenceInstruction) {
            val ref = instr.reference
            when (ref) {
                is StringReference -> sb.append(", \"").append(ref.string).append("\"")
                is TypeReference -> sb.append(", ").append(ref.type)
                is FieldReference -> sb.append(", ").append(ref.definingClass).append("->").append(ref.name).append(":").append(ref.type)
                is MethodReference -> sb.append(", ").append(ref.definingClass).append("->").append(ref.name)
            }
        }
        return sb.toString()
    }

    private fun diffLines(a: List<String>, b: List<String>, lineAOffset: Int, lineBOffset: Int): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        val window = 50
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> {
                    result.add(DiffLine(LineStatus.SAME, a[i], b[j], i + 1 + lineAOffset, j + 1 + lineBOffset))
                    i++
                    j++
                }
                i < a.size && j < b.size -> {
                    var matchJ = -1
                    for (jj in j + 1 until minOf(j + window, b.size)) {
                        if (a[i] == b[jj]) { matchJ = jj; break }
                    }
                    var matchI = -1
                    for (ii in i + 1 until minOf(i + window, a.size)) {
                        if (a[ii] == b[j]) { matchI = ii; break }
                    }
                    when {
                        matchJ != -1 && (matchI == -1 || matchJ - j <= matchI - i) -> {
                            for (jj in j until matchJ) {
                                result.add(DiffLine(LineStatus.ADDED, null, b[jj], null, jj + 1 + lineBOffset))
                            }
                            j = matchJ
                        }
                        matchI != -1 -> {
                            for (ii in i until matchI) {
                                result.add(DiffLine(LineStatus.REMOVED, a[ii], null, ii + 1 + lineAOffset, null))
                            }
                            i = matchI
                        }
                        else -> {
                            result.add(DiffLine(LineStatus.CHANGED, a[i], b[j], i + 1 + lineAOffset, j + 1 + lineBOffset))
                            i++
                            j++
                        }
                    }
                }
                i < a.size -> {
                    result.add(DiffLine(LineStatus.REMOVED, a[i], null, i + 1 + lineAOffset, null))
                    i++
                }
                else -> {
                    result.add(DiffLine(LineStatus.ADDED, null, b[j], null, j + 1 + lineBOffset))
                    j++
                }
            }
        }
        return result
    }
}