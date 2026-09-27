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

    fun compare(apkA: File, apkB: File, workDir: File, ignoreDebug: Boolean, ignoreNop: Boolean, ignoreAdCalls: Boolean, ignoreAnalytics: Boolean): List<ChangedClass> {
        val opcodes = Opcodes.forApi(28)
        val classesA = loadClasses(apkA, File(workDir, "a"), opcodes)
        val classesB = loadClasses(apkB, File(workDir, "b"), opcodes)
        val result = mutableListOf<ChangedClass>()
        for (type in (classesA.keys + classesB.keys).toSortedSet()) {
            val ca = classesA[type]
            val cb = classesB[type]
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

    fun diffClass(apkA: File, apkB: File, type: String, workDir: File, ignoreDebug: Boolean): DiffResult {
        val opcodes = Opcodes.forApi(28)
        val classesA = loadClasses(apkA, File(workDir, "a"), opcodes)
        val classesB = loadClasses(apkB, File(workDir, "b"), opcodes)
        val ca = classesA[type]
        val cb = classesB[type]
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
        return if (ignoreNop) !equalIgnoringNop(sa, sb) else sa != sb
    }

    private fun equalIgnoringNop(a: List<Long>, b: List<Long>): Boolean {
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (!isNopSignature(a[i]) && !isNopSignature(b[j])) return false

            var ii = i
            while (ii < a.size && isNopSignature(a[ii])) ii++
            if (ii < a.size && a[ii] == b[j]) { i = ii; continue }

            var jj = j
            while (jj < b.size && isNopSignature(b[jj])) jj++
            if (jj < b.size && a[i] == b[jj]) { j = jj; continue }

            i++
            j++
        }
        while (i < a.size) { if (!isNopSignature(a[i])) return false; i++ }
        while (j < b.size) { if (!isNopSignature(b[j])) return false; j++ }
        return true
    }

    private fun isNopSignature(sig: Long): Boolean = sig == NOP_MARKER

    private fun classSignature(classDef: ClassDef, ignoreDebug: Boolean): List<Long> {
        val sig = mutableListOf<Long>()
        sig.add(classDef.type.hashCode().toLong())
        val fields = (classDef.staticFields + classDef.instanceFields).sortedBy { it.name }
        for (field in fields) {
            sig.add(field.name.hashCode().toLong() * 31 + field.type.hashCode())
        }
        val methods = (classDef.directMethods + classDef.virtualMethods)
            .sortedBy { it.name + it.parameterTypes.joinToString("") + it.returnType }
        for (method in methods) {
            var mh = method.name.hashCode().toLong()
            for (p in method.parameterTypes) mh = mh * 31 + p.toString().hashCode()
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
        var h = instr.opcode.name.hashCode().toLong()
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

    private fun loadClasses(apk: File, dir: File, opcodes: Opcodes): Map<String, ClassDef> {
        dir.mkdirs()
        val map = sortedMapOf<String, ClassDef>()
        val dexFiles = ApkUtils.extractAllDex(apk, dir)
        for (dexFile in dexFiles) {
            val dex = DexFileFactory.loadDexFile(dexFile, opcodes)
            for (c in dex.classes) map[c.type] = c
            dexFile.delete()
        }
        return map
    }

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
