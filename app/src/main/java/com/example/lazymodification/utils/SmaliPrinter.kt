package com.example.lazymodification.utils

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.SwitchPayload
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.CallSiteReference
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodProtoReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.Reference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference

/**
 * Отображение инструкций dex в smali-подобный текст (просмотр кода метода).
 */
object SmaliPrinter {

    fun prettyType(t: String): String {
        var s = t
        var dims = 0
        while (s.startsWith("[")) {
            dims++
            s = s.substring(1)
        }
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

    fun methodLabel(m: Method): String {
        val params = m.parameterTypes.joinToString(", ") { prettyType(it.toString()) }
        return m.name + "(" + params + ") : " + prettyType(m.returnType)
    }

    fun collectTargets(impl: MethodImplementation): Set<Int> {
        val out = HashSet<Int>()
        var addr = 0
        for (ins in impl.instructions) {
            if (ins is OffsetInstruction) out.add(addr + ins.codeOffset)
            addr += ins.codeUnits
        }
        return out
    }

    fun labelName(addr: Int): String = ":L" + Integer.toHexString(addr)

    fun branchLabel(ins: OffsetInstruction, addr: Int): String {
        val target = addr + ins.codeOffset
        val n = ins.opcode.name
        val p = when {
            n.startsWith("if-") -> "cond_"
            n.startsWith("goto") -> "goto_"
            else -> "pswitch_"
        }
        return ":" + p + Integer.toHexString(target)
    }

    fun labelsFor(impl: MethodImplementation): Map<Int, List<String>> {
        val acc = LinkedHashMap<Int, LinkedHashSet<String>>()
        var addr = 0
        for (ins in impl.instructions) {
            if (ins is OffsetInstruction) {
                val t = addr + ins.codeOffset
                acc.getOrPut(t) { LinkedHashSet() }.add(branchLabel(ins, addr))
            }
            addr += ins.codeUnits
        }
        val out = LinkedHashMap<Int, List<String>>()
        for ((k, v) in acc) out[k] = v.toList()
        return out
    }

    private fun regName(r: Int, paramBase: Int): String =
        if (paramBase >= 0 && r >= paramBase) "p" + (r - paramBase) else "v" + r

    fun format(ins: Instruction, addr: Int, paramBase: Int = -1): String {
        val sb = StringBuilder(opName(ins.opcode))
        val parts = ArrayList<String>(4)
        regsPart(ins, paramBase)?.let { parts.add(it) }
        if (ins is NarrowLiteralInstruction) {
            parts.add(intHex(ins.narrowLiteral))
        } else if (ins is WideLiteralInstruction) {
            parts.add(longHex(ins.wideLiteral) + "L")
        }
        if (ins is OffsetInstruction) {
            parts.add(branchLabel(ins, addr))
        }
        if (ins is ReferenceInstruction) {
            parts.add(refText(ins.reference))
        }
        if (ins is SwitchPayload) {
            parts.add("(" + ins.switchElements.size + " cases)")
        } else if (ins.opcode.name == "ARRAY_PAYLOAD") {
            parts.add("(array)")
        }
        if (parts.isNotEmpty()) {
            sb.append(' ')
            sb.append(parts.joinToString(", "))
        }
        return sb.toString()
    }

    private fun opName(op: Opcode): String {
        var n = op.name.lowercase().replace('_', '-')
        if (n.endsWith("-jumbo")) n = n.removeSuffix("-jumbo") + "/jumbo"
        return n
    }

    private fun regsPart(ins: Instruction, paramBase: Int): String? = when {
        ins is FiveRegisterInstruction -> {
            val rs = ArrayList<Int>(5)
            if (ins.registerCount >= 1) rs.add(ins.registerC)
            if (ins.registerCount >= 2) rs.add(ins.registerD)
            if (ins.registerCount >= 3) rs.add(ins.registerE)
            if (ins.registerCount >= 4) rs.add(ins.registerF)
            if (ins.registerCount >= 5) rs.add(ins.registerG)
            "{" + rs.joinToString(", ") { regName(it, paramBase) } + "}"
        }
        ins is RegisterRangeInstruction -> {
            if (ins.registerCount <= 0) "{}"
            else {
                val s = ins.startRegister
                val e = s + ins.registerCount - 1
                if (paramBase >= 0 && s >= paramBase) "{p" + (s - paramBase) + " .. p" + (e - paramBase) + "}"
                else "{v" + s + " .. v" + e + "}"
            }
        }
        ins is ThreeRegisterInstruction -> regName(ins.registerA, paramBase) + ", " + regName(ins.registerB, paramBase) + ", " + regName(ins.registerC, paramBase)
        ins is TwoRegisterInstruction -> regName(ins.registerA, paramBase) + ", " + regName(ins.registerB, paramBase)
        ins is OneRegisterInstruction -> regName(ins.registerA, paramBase)
        else -> null
    }

    private fun intHex(v: Int): String = if (v < 0) "-0x" + Integer.toHexString(-v) else "0x" + Integer.toHexString(v)

    private fun longHex(v: Long): String = if (v < 0) "-0x" + java.lang.Long.toHexString(-v) else "0x" + java.lang.Long.toHexString(v)

    private fun refText(ref: Reference): String = when (ref) {
        is StringReference -> "\"" + escape(ref.string) + "\""
        is TypeReference -> ref.type
        is FieldReference -> ref.definingClass + "->" + ref.name + ":" + ref.type
        is MethodReference -> ref.definingClass + "->" + ref.name + "(" + ref.parameterTypes.joinToString("") + ")" + ref.returnType
        is MethodProtoReference -> "(" + ref.parameterTypes.joinToString("") + ")" + ref.returnType
        is CallSiteReference -> ref.methodName
        else -> ref.toString()
    }

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}