package com.example.lazymodification.utils

import org.jf.dexlib2.Format
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.reference.Reference
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction12x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction20t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21s
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22b
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22s
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction30t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31i
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction32x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction51l
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference

/**
 * Правка кода: разбор smali-строки инструкции и построение новой
 * инструкции того же формата (размер не меняется).
 */
data class CodeEdit(
    val className: String,
    val methodName: String,
    val paramTypes: List<String>,
    val returnType: String,
    val addr: Int,
    val newText: String
)

object DexCodeEditor {

    class ParseException(message: String) : Exception(message)

    private val opcodesByName: HashMap<String, Opcode> by lazy {
        val m = HashMap<String, Opcode>()
        for (op in Opcode.values()) m[normalizeName(op.name)] = op
        m
    }

    fun normalizeName(n0: String): String {
        var n = n0.lowercase().replace('_', '-')
        if (n.endsWith("-jumbo")) n = n.removeSuffix("-jumbo") + "/jumbo"
        return n
    }

    fun parseInstruction(raw: String, old: Instruction, selfAddr: Int, validAddrs: Set<Int>, paramBase: Int = -1): Instruction {
        val text = raw.trim()
        if (text.isEmpty()) throw ParseException("Пустая инструкция")
        val sp = text.indexOf(' ')
        val opName = (if (sp < 0) text else text.substring(0, sp)).trim()
        val rest = (if (sp < 0) "" else text.substring(sp + 1)).trim()
        if (opName.contains("payload")) throw ParseException("Служебные инструкции (payload) не редактируются")
        val newOp = opcodesByName[opName.lowercase()] ?: throw ParseException("Неизвестная инструкция: " + opName)
        if (normalizeName(newOp.name).contains("payload")) throw ParseException("Служебные инструкции (payload) не редактируются")
        val fmt = newOp.format
        val result: Instruction = when (fmt) {
            Format.Format10x -> {
                if (rest.isNotEmpty()) throw ParseException("У этой инструкции нет операндов")
                ImmutableInstruction10x(newOp)
            }
            Format.Format11x -> ImmutableInstruction11x(newOp, reg(splitArgs(rest, 1)[0], 255, paramBase))
            Format.Format11n -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction11n(newOp, reg(parts[0], 255, paramBase), lit(parts[1], -8, 7).toInt())
            }
            Format.Format12x -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction12x(newOp, reg(parts[0], 15, paramBase), reg(parts[1], 15, paramBase))
            }
            Format.Format10t -> ImmutableInstruction10t(newOp, label(rest, selfAddr, validAddrs, -128, 127))
            Format.Format20t -> ImmutableInstruction20t(newOp, label(rest, selfAddr, validAddrs, -32768, 32767))
            Format.Format30t -> ImmutableInstruction30t(newOp, label(rest, selfAddr, validAddrs, Int.MIN_VALUE, Int.MAX_VALUE))
            Format.Format21t -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction21t(newOp, reg(parts[0], 255, paramBase), label(parts[1], selfAddr, validAddrs, -32768, 32767))
            }
            Format.Format22t -> {
                val parts = splitArgs(rest, 3)
                ImmutableInstruction22t(newOp, reg(parts[0], 15, paramBase), reg(parts[1], 15, paramBase), label(parts[2], selfAddr, validAddrs, -32768, 32767))
            }
            Format.Format21s -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction21s(newOp, reg(parts[0], 255, paramBase), lit(parts[1], -32768, 32767).toInt())
            }
            Format.Format31i -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction31i(newOp, reg(parts[0], 255, paramBase), lit32(parts[1]))
            }
            Format.Format51l -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction51l(newOp, reg(parts[0], 255, paramBase), lit(parts[1], Long.MIN_VALUE, Long.MAX_VALUE))
            }
            Format.Format21c -> {
                val rr = regAndRest(rest)
                ImmutableInstruction21c(newOp, reg(rr[0], 255, paramBase), parseRef(rr[1], newOp))
            }
            Format.Format31c -> {
                val rr = regAndRest(rest)
                ImmutableInstruction31c(newOp, reg(rr[0], 255, paramBase), parseRef(rr[1], newOp))
            }
            Format.Format22c -> {
                val first = rest.indexOf(',')
                if (first < 0) throw ParseException("Ожидалось: vA, vB, ссылка")
                val second = rest.indexOf(',', first + 1)
                if (second < 0) throw ParseException("Ожидалось: vA, vB, ссылка")
                ImmutableInstruction22c(newOp,
                    reg(rest.substring(0, first), 255, paramBase),
                    reg(rest.substring(first + 1, second), 255, paramBase),
                    parseRef(rest.substring(second + 1), newOp))
            }
            Format.Format22b -> {
                val parts = splitArgs(rest, 3)
                ImmutableInstruction22b(newOp, reg(parts[0], 255, paramBase), reg(parts[1], 255, paramBase), lit(parts[2], -128, 127).toInt())
            }
            Format.Format22s -> {
                val parts = splitArgs(rest, 3)
                ImmutableInstruction22s(newOp, reg(parts[0], 15, paramBase), reg(parts[1], 15, paramBase), lit(parts[2], -32768, 32767).toInt())
            }
            Format.Format22x -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction22x(newOp, reg(parts[0], 255, paramBase), reg(parts[1], 65535, paramBase))
            }
            Format.Format32x -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction32x(newOp, reg(parts[0], 65535, paramBase), reg(parts[1], 65535, paramBase))
            }
            Format.Format23x -> {
                val parts = splitArgs(rest, 3)
                ImmutableInstruction23x(newOp, reg(parts[0], 255, paramBase), reg(parts[1], 255, paramBase), reg(parts[2], 255, paramBase))
            }
            Format.Format31t -> {
                val parts = splitArgs(rest, 2)
                ImmutableInstruction31t(newOp, reg(parts[0], 255, paramBase), label(parts[1], selfAddr, validAddrs, Int.MIN_VALUE, Int.MAX_VALUE))
            }
            Format.Format35c -> {
                val close = rest.indexOf('}')
                if (close < 0 || !rest.startsWith("{")) throw ParseException("Ожидалось: {v0, v1}, ссылка")
                val inside = rest.substring(1, close).trim()
                val regs = ArrayList<Int>()
                if (inside.isNotEmpty()) {
                    for (t in inside.split(",")) {
                        val rt = t.trim()
                        if (rt.isNotEmpty()) regs.add(reg(rt, 15, paramBase))
                    }
                }
                if (regs.size > 5) throw ParseException("Максимум 5 регистров")
                val refText = rest.substring(close + 1).trim().removePrefix(",").trim()
                ImmutableInstruction35c(newOp, regs.size,
                    if (regs.size > 0) regs[0] else 0,
                    if (regs.size > 1) regs[1] else 0,
                    if (regs.size > 2) regs[2] else 0,
                    if (regs.size > 3) regs[3] else 0,
                    if (regs.size > 4) regs[4] else 0,
                    parseRef(refText, newOp))
            }
            Format.Format3rc -> {
                val close = rest.indexOf('}')
                if (close < 0 || !rest.startsWith("{")) throw ParseException("Ожидалось: {v0 .. v5}, ссылка")
                val inside = rest.substring(1, close).trim()
                var startReg = 0
                var cnt = 0
                if (inside.isNotEmpty()) {
                    if (inside.contains("..")) {
                        val dd = inside.indexOf("..")
                        startReg = reg(inside.substring(0, dd), 65535, paramBase)
                        val endReg = reg(inside.substring(dd + 2), 65535, paramBase)
                        if (endReg < startReg) throw ParseException("Неверный диапазон регистров")
                        cnt = endReg - startReg + 1
                    } else {
                        startReg = reg(inside, 65535, paramBase)
                        cnt = 1
                    }
                }
                if (cnt > 65535) throw ParseException("Слишком много регистров")
                val refText = rest.substring(close + 1).trim().removePrefix(",").trim()
                ImmutableInstruction3rc(newOp, startReg, cnt, parseRef(refText, newOp))
            }
            else -> throw ParseException("Этот формат (" + fmt + ") пока не редактируется")
        }
        if (result.codeUnits != old.codeUnits) throw ParseException("Размер не совпадает: у «" + normalizeName(old.opcode.name) + "» " + old.codeUnits + " ед., у «" + normalizeName(newOp.name) + "» " + result.codeUnits + " ед. Менять размер нельзя.")
        return result
    }

    // ---------------- helpers ----------------

    private fun splitArgs(rest: String, n: Int): List<String> {
        if (rest.isEmpty()) throw ParseException("Нужно " + n + " операнд(а)")
        val parts = rest.split(",").map { it.trim() }
        if (parts.size != n) throw ParseException("Неверное число операндов (" + parts.size + " вместо " + n + ")")
        return parts
    }

    private fun regAndRest(rest: String): List<String> {
        val c = rest.indexOf(',')
        if (c < 0) throw ParseException("Ожидалось: vN, значение")
        return listOf(rest.substring(0, c).trim(), rest.substring(c + 1).trim())
    }

    private fun reg(tok0: String, max: Int, paramBase: Int): Int {
        val tok = tok0.trim()
        if (tok.isEmpty()) throw ParseException("Пустой регистр")
        val c = tok[0]
        val isP = (c == 'p' || c == 'P')
        if (isP && paramBase < 0) throw ParseException("p-регистры доступны только в контексте метода")
        val digits: String = if (c == 'v' || c == 'V' || isP) tok.substring(1) else tok
        val n = digits.toIntOrNull() ?: throw ParseException("Не понял регистр: " + tok0)
        if (n < 0) throw ParseException("Не понял регистр: " + tok0)
        val v = if (isP) paramBase + n else n
        if (v > max) throw ParseException("Регистр " + tok0 + " вне диапазона (0.." + max + ")")
        return v
    }

    private fun lit(tok0: String, min: Long, max: Long): Long {
        var tok = tok0.trim()
        if (tok.endsWith("L") || tok.endsWith("l")) tok = tok.substring(0, tok.length - 1)
        var neg = false
        if (tok.startsWith("-")) {
            neg = true
            tok = tok.substring(1).trim()
        } else if (tok.startsWith("+")) {
            tok = tok.substring(1).trim()
        }
        val v: Long = if (tok.startsWith("0x") || tok.startsWith("0X")) {
            tok.substring(2).toLongOrNull(16) ?: throw ParseException("Не понял число: " + tok0)
        } else {
            tok.toLongOrNull() ?: throw ParseException("Не понял число: " + tok0)
        }
        val res = if (neg) -v else v
        if (res < min || res > max) throw ParseException("Значение " + tok0 + " вне диапазона (" + min + ".." + max + ")")
        return res
    }

    private fun lit32(tok0: String): Int {
        val v = lit(tok0, -2147483648L, 4294967295L)
        return if (v > Int.MAX_VALUE) (v - 4294967296L).toInt() else v.toInt()
    }

    private fun label(tok0: String, selfAddr: Int, validAddrs: Set<Int>, minOff: Int, maxOff: Int): Int {
        val tok = tok0.trim()
        if (!tok.startsWith(":")) throw ParseException("Не понял метку: " + tok0 + " (пример: :cond_d)")
        val m = Regex("^(?:[A-Za-z]+_)?L?(?:0[xX])?([0-9a-fA-F]+)$").find(tok.substring(1))
        if (m == null) throw ParseException("Не понял метку: " + tok0 + " (пример: :cond_d)")
        val target = m.groupValues[1].toIntOrNull(16) ?: throw ParseException("Не понял метку: " + tok0)
        if (!validAddrs.contains(target)) throw ParseException("Метка " + tok0 + " не указывает на инструкцию")
        val delta = target - selfAddr
        if (delta < minOff || delta > maxOff) throw ParseException("Переход слишком далеко: " + delta)
        return delta
    }

    private fun parseRef(text0: String, op: Opcode): Reference {
        val text = text0.trim()
        when (refKind(normalizeName(op.name))) {
            1 -> return ImmutableStringReference(unquote(text))
            2 -> return ImmutableTypeReference(parseTypeName(text))
            3 -> {
                val arrow = text.indexOf("->")
                if (arrow <= 0) throw ParseException("Ожидалось: Lкласс;->имя:тип")
                val cls = parseTypeName(text.substring(0, arrow))
                val right = text.substring(arrow + 2)
                val colon = right.lastIndexOf(':')
                if (colon <= 0) throw ParseException("Ожидалось: Lкласс;->имя:тип")
                return ImmutableFieldReference(cls, right.substring(0, colon), parseTypeName(right.substring(colon + 1)))
            }
            4 -> {
                val arrow = text.indexOf("->")
                if (arrow <= 0) throw ParseException("Ожидалось: Lкласс;->имя(параметры)возврат")
                val cls = parseTypeName(text.substring(0, arrow))
                val right = text.substring(arrow + 2)
                val lp = right.indexOf('(')
                val rp = right.indexOf(')', lp + 1)
                if (lp <= 0 || rp < 0) throw ParseException("Ожидалось: Lкласс;->имя(параметры)возврат")
                val params = splitParamTypes(right.substring(lp + 1, rp))
                val ret = parseTypeName(right.substring(rp + 1))
                return ImmutableMethodReference(cls, right.substring(0, lp), params, ret)
            }
            else -> throw ParseException("Ссылки такого вида пока не поддерживаются")
        }
    }

    private fun refKind(n: String): Int = when {
        n.startsWith("const-string") -> 1
        n == "const-class" || n == "check-cast" || n == "new-instance" ||
            n == "new-array" || n == "instance-of" || n.startsWith("filled-new-array") -> 2
        n.startsWith("iget") || n.startsWith("iput") || n.startsWith("sget") || n.startsWith("sput") -> 3
        n.startsWith("invoke-") -> 4
        else -> 0
    }

    private fun parseTypeName(s0: String): String {
        val s = s0.trim()
        if (s.isEmpty()) throw ParseException("Пустой тип")
        val c = s[0]
        if (c == '[' || c == 'L' || c == 'V' || c == 'Z' || c == 'B' || c == 'S' || c == 'C' || c == 'I' || c == 'J' || c == 'F' || c == 'D') return s
        throw ParseException("Не понял тип: " + s + " (пример: Lcom/foo/Bar;)")
    }

    private fun splitParamTypes(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val start = i
            while (i < s.length && s[i] == '[') i++
            if (i >= s.length) throw ParseException("Плохие параметры: " + s)
            if (s[i] == 'L') {
                val semi = s.indexOf(';', i)
                if (semi < 0) throw ParseException("Плохие параметры: " + s)
                i = semi + 1
            } else {
                i++
            }
            out.add(s.substring(start, i))
        }
        return out
    }

    private fun unquote(s0: String): String {
        val s = s0.trim()
        if (s.length < 2 || !s.startsWith("\"") || !s.endsWith("\"")) {
            throw ParseException("Строка должна быть в кавычках: \"текст\"")
        }
        val body = s.substring(1, s.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '\\' && i + 1 < body.length) {
                when (val n = body[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> {
                        sb.append(ch)
                        sb.append(n)
                    }
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}