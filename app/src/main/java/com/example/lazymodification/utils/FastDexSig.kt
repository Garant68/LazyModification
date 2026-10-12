package com.example.lazymodification.utils

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

/**
 * ⚡ Быстрый движок подписей классов DEX: читает файл напрямую без dexlib2-объектов
 * и без строковых хэшей на каждую инструкцию (ссылки резолвятся через кэшированные
 * таблицы). Даёт ТЕ ЖЕ значения подписей, что dexlib2-путь DexComparer (проверяется
 * отдельным тестом SigVerify на реальных dex).
 *
 * raf — только последовательный разбор class_data; raf2 — все произвольные чтения.
 */
class FastDexSig(file: File) : Closeable {

    /** Примитивный буфер подписи. */
    class LongSig {
        var array = LongArray(256)
        var size = 0

        fun add(v: Long) {
            if (size == array.size) array = array.copyOf(array.size * 2)
            array[size++] = v
        }
    }

    /** Простой in-memory reader: все чтения — из массива, без системных вызовов. */
    class MemFile(val data: ByteArray) {
        var pos = 0L
        fun seek(p: Long) { pos = p }
        fun read(): Int {
            if (pos >= data.size) return -1
            return data[pos++.toInt()].toInt() and 0xFF
        }
        fun read(b: ByteArray): Int {
            val n = minOf(b.size.toLong(), data.size - pos)
            if (n <= 0) return -1
            System.arraycopy(data, pos.toInt(), b, 0, n.toInt())
            pos += n
            return n.toInt()
        }
        fun readFully(b: ByteArray) {
            System.arraycopy(data, pos.toInt(), b, 0, b.size)
            pos += b.size
        }
        fun skipBytes(n: Int) { pos += n }
        val filePointer: Long get() = pos
    }

    private val fileBytes: ByteArray = file.readBytes()
    private val raf = MemFile(fileBytes)
    private val raf2 = MemFile(fileBytes)

    private val stringIdsOff: Int
    private val stringIdsSize: Int
    private val typeIdsOff: Int
    private val typeIdsSize: Int
    private val protoIdsOff: Int
    private val protoIdsSize: Int
    private val fieldIdsOff: Int
    private val fieldIdsSize: Int
    private val methodIdsOff: Int
    private val methodIdsSize: Int
    private val classDefsOff: Int
    private val classDefsSize: Int

    private val stringHashCache: IntArray
    private val typeHashCache: IntArray
    private val fieldRefHashCache: IntArray
    private val methodRefHashCache: IntArray

    init {
        try {
            val header = ByteArray(112)
            raf.readFully(header)
            if (header[0].toInt() != 0x64 || header[1].toInt() != 0x65 || header[2].toInt() != 0x78) {
                throw IllegalArgumentException("это не DEX-файл")
            }
            stringIdsSize = u32(header, 56)
            stringIdsOff = u32(header, 60)
            typeIdsSize = u32(header, 64)
            typeIdsOff = u32(header, 68)
            protoIdsSize = u32(header, 72)
            protoIdsOff = u32(header, 76)
            fieldIdsSize = u32(header, 80)
            fieldIdsOff = u32(header, 84)
            methodIdsSize = u32(header, 88)
            methodIdsOff = u32(header, 92)
            classDefsSize = u32(header, 96)
            classDefsOff = u32(header, 100)
            stringHashCache = IntArray(stringIdsSize)
            typeHashCache = IntArray(typeIdsSize)
            fieldRefHashCache = IntArray(fieldIdsSize)
            methodRefHashCache = IntArray(methodIdsSize)
        } catch (t: Throwable) {
            throw t
        }
    }

    /** Перебирает типы всех классов этого dex. */
    fun forEachClassType(cb: (String) -> Unit) {
        val buf = classDefsBuffer()
        for (i in 0 until classDefsSize) {
            val classIdx = i32(buf, i * 32)
            if (classIdx < 0 || classIdx >= typeIdsSize) continue
            val sIdx = typeStringIdx(classIdx) ?: continue
            val name = stringAt(sIdx) ?: continue
            cb(name)
        }
    }

    /** Подпись класса или null, если класса нет в этом dex. */
    fun classSignature(type: String): LongSig? {
        val sIdx = findStringIndex(type) ?: return null
        val tIdx = findTypeIndex(sIdx) ?: return null
        var classDefOff = -1L
        val buf = classDefsBuffer()
        for (i in 0 until classDefsSize) {
            if (i32(buf, i * 32) == tIdx) {
                classDefOff = classDefsOff.toLong() + i * 32L
                break
            }
        }
        if (classDefOff < 0) return null

        raf2.seek(classDefOff)
        val cd = ByteArray(32)
        raf2.readFully(cd)
        val superIdx = i32(cd, 8)
        val interfacesOff = u32(cd, 12).toLong()
        val classDataOff = u32(cd, 24).toLong()

        val sig = LongSig()
        sig.add(stringHashOf(sIdx).toLong())
        sig.add((if (superIdx < 0) 0L else stringHashOf(typeStringIdx(superIdx) ?: -1).toLong()))
        if (interfacesOff != 0L) {
            raf2.seek(interfacesOff)
            val n = readU32(raf2)
            val ifIdxs = IntArray(n)
            for (j in 0 until n) {
                val b = readBytes(raf2, 2)
                ifIdxs[j] = u16(b, 0)
            }
            for (ifIdx in ifIdxs) {
                sig.add(stringHashOf(typeStringIdx(ifIdx) ?: -1).toLong())
            }
        }

        val fieldEntries = ArrayList<Pair<String, Long>>()
        val methodEntries = ArrayList<Pair<String, Long>>()
        val methodInstr = ArrayList<LongSig?>()
        if (classDataOff != 0L) {
            raf.seek(classDataOff)
            val staticFields = readUleb(raf)
            val instanceFields = readUleb(raf)
            val directMethods = readUleb(raf)
            val virtualMethods = readUleb(raf)
            var fieldIdx = 0
            fun addField() {
                fieldIdx += readUleb(raf)
                readUleb(raf) // access
                val nameS = fieldNameIdx(fieldIdx)
                val typeS = fieldTypeIdx(fieldIdx)
                if (nameS >= 0 && typeS >= 0) {
                    val name = stringAt(nameS) ?: ""
                    val h = stringHashOf(nameS).toLong() * 31 + typeHashOf(typeS).toLong()
                    fieldEntries.add(name to h)
                }
            }
            repeat(staticFields) { addField() }
            fieldIdx = 0
            repeat(instanceFields) { addField() }

            var methodIdx = 0
            fun addMethod() {
                methodIdx += readUleb(raf)
                readUleb(raf) // access
                val codeOff = readUleb(raf).toLong()
                val nameS = methodNameIdx(methodIdx)
                val protoIdx = methodProtoIdx(methodIdx)
                if (nameS < 0 || protoIdx < 0) {
                    methodEntries.add("" to 0L)
                    methodInstr.add(null)
                    return
                }
                val name = stringAt(nameS) ?: ""
                val retT = protoReturnStringIdx(protoIdx)
                val params = protoParams(protoIdx)
                val keyBuilder = StringBuilder(name)
                var mh = stringHashOf(nameS).toLong()
                for (p in params) {
                    val ps = stringAt(p) ?: ""
                    keyBuilder.append(ps)
                    mh = mh * 31 + stringHashOf(p)
                }
                val retS = if (retT >= 0) stringAt(retT) ?: "" else ""
                keyBuilder.append(retS)
                mh = mh * 31 + stringHashOf(retT)
                methodEntries.add(keyBuilder.toString() to mh)
                methodInstr.add(if (codeOff != 0L) codeSignature(codeOff) else null)
            }
            repeat(directMethods) { addMethod() }
            methodIdx = 0
            repeat(virtualMethods) { addMethod() }
        }

        fieldEntries.sortBy { it.first }
        for (fe in fieldEntries) sig.add(fe.second)

        val order = methodEntries.indices.sortedBy { methodEntries[it].first }
        for (oi in order) {
            sig.add(methodEntries[oi].second)
            val ins = methodInstr[oi]
            if (ins != null) {
                for (k in 0 until ins.size) sig.add(ins.array[k])
            }
        }
        return sig
    }

    /** Подпись кода метода: последовательность Long по правилам dexlib2-пути. */
    /** Подпись кода метода: последовательность Long по правилам dexlib2-пути (flag-driven). */
    private fun codeSignature(codeOff: Long): LongSig {
        val sig = LongSig()
        raf2.seek(codeOff + 12)
        val insnsSize = readU32(raf2)
        raf2.seek(codeOff + 16)
        val units = ByteArray(insnsSize * 2)
        raf2.readFully(units)
        var i = 0
        val n = insnsSize
        while (i < n) {
            val u0 = (units[2 * i].toInt() and 0xFF) or ((units[2 * i + 1].toInt() and 0xFF) shl 8)
            val op = u0 and 0xFF
            if (op == 0) {
                if (u0 == 0x0100) {
                    sig.add(PAYLOAD_PACKED_ORD.toLong())
                    val sz = u16Safe(units, 2 * i + 2)
                    i += 4 + sz * 2
                    continue
                }
                if (u0 == 0x0200) {
                    sig.add(PAYLOAD_SPARSE_ORD.toLong())
                    val sz = u16Safe(units, 2 * i + 2)
                    i += 2 + sz * 4
                    continue
                }
                if (u0 == 0x0300) {
                    sig.add(PAYLOAD_ARRAY_ORD.toLong())
                    val w = u16Safe(units, 2 * i + 2)
                    val sz = u16Safe(units, 2 * i + 4) or (u16Safe(units, 2 * i + 6) shl 16)
                    i += 4 + (w * sz + 1) / 2
                    continue
                }
                sig.add(NOP_MARKER)
                i += 1
                continue
            }
            val len = LEN[op]
            var h = OP_HASH[op].toLong()
            val fmt = FMT[op]
            if (op == 0x01 || op == 0x04 || op == 0x07) {
                val b1 = ub(units, 2 * i + 1)
                val a = b1 and 0xF
                val b = (b1 shr 4) and 0xF
                if (a == b) { sig.add(NOP_MARKER); i += len; continue }
            }
            var a = 0; var b = 0; var c = 0
            var start = 0; var count = 0
            var cC = 0; var cD = 0; var cE = 0
            var narrow = 0
            var wide = 0L
            when (fmt) {
                F_12X, F_22T, F_22C, F_22CS, F_22S -> {
                    val b1 = ub(units, 2 * i + 1)
                    a = b1 and 0xF
                    b = (b1 shr 4) and 0xF
                }
                F_22B -> {
                    a = ub(units, 2 * i + 1)
                    b = ub(units, 2 * i + 2)
                }
                F_11X, F_21T, F_21S, F_21IH, F_21LH, F_21C, F_31I, F_31T, F_31C, F_51L -> {
                    a = ub(units, 2 * i + 1)
                }
                F_11N -> {
                    val b1 = ub(units, 2 * i + 1)
                    a = b1 and 0xF
                }
                F_22X -> {
                    a = ub(units, 2 * i + 1)
                    b = u16Safe(units, 2 * i + 2)
                }
                F_32X -> {
                    a = u16Safe(units, 2 * i + 2)
                    b = u16Safe(units, 2 * i + 4)
                }
                F_23X -> {
                    a = ub(units, 2 * i + 1)
                    b = ub(units, 2 * i + 2)
                    c = ub(units, 2 * i + 3)
                }
                F_35C, F_35MS, F_35MI, F_45CC -> {
                    cC = ub(units, 2 * i + 4) and 0xF
                    cD = (ub(units, 2 * i + 4) shr 4) and 0xF
                    cE = ub(units, 2 * i + 5) and 0xF
                }
                F_3RC, F_3RMS, F_3RMI, F_4RCC -> {
                    count = ub(units, 2 * i + 1)
                    start = u16Safe(units, 2 * i + 4)
                }
                else -> { }
            }
            when (fmt) {
                F_11N -> {
                    val b1 = ub(units, 2 * i + 1)
                    var lit = (b1 shr 4) and 0xF
                    if (lit >= 8) lit -= 16
                    narrow = lit
                    wide = lit.toLong()
                }
                F_21S -> {
                    narrow = u16Safe(units, 2 * i + 2).toShort().toInt()
                    wide = narrow.toLong()
                }
                F_21IH -> {
                    narrow = u16Safe(units, 2 * i + 2).toShort().toInt() shl 16
                    wide = narrow.toLong()
                }
                F_21LH -> {
                    wide = (u16Safe(units, 2 * i + 2).toShort().toLong()) shl 48
                }
                F_22B -> {
                    narrow = sb(units, 2 * i + 3)
                    wide = narrow.toLong()
                }
                F_22S -> {
                    narrow = u16Safe(units, 2 * i + 2).toShort().toInt()
                    wide = narrow.toLong()
                }
                F_31I -> {
                    narrow = u16Safe(units, 2 * i + 2) or (u16Safe(units, 2 * i + 4) shl 16)
                    wide = narrow.toLong()
                }
                F_51L -> {
                    var w = 0L
                    for (k in 0 until 4) w = w or (u16Safe(units, 2 * i + 2 + 2 * k).toLong() shl (16 * k))
                    wide = w
                }
                else -> { }
            }
            val fl = FLAGS[op]
            if (fl and B_ONE != 0) h = h * 31 + a
            if (fl and B_TWO != 0) h = h * 31 + a * 997 + b
            if (fl and B_THREE != 0) h = h * 31 + a * 994009 + b * 997 + c
            if (fl and B_FIVE != 0) h = h * 31 + cC + cD * 997 + cE * 994009
            if (fl and B_RANGE != 0) h = h * 31 + start + count * 997
            if (fl and B_NARROW != 0) h = h * 31 + narrow
            if (fl and B_WIDE != 0) h = h * 31 + (wide xor (wide ushr 32)).toInt()
            if (fl and B_REF != 0) {
                val kind = REF_KIND[op]
                if (kind >= 0) {
                    val refIdx = when (fmt) {
                        F_21C, F_22C, F_22CS, F_35C, F_35MS, F_35MI, F_45CC, F_3RC, F_3RMS, F_3RMI, F_4RCC, F_20BC ->
                            u16Safe(units, 2 * i + 2)
                        F_31C -> u16Safe(units, 2 * i + 2) or (u16Safe(units, 2 * i + 4) shl 16)
                        else -> -1
                    }
                    if (refIdx >= 0) h = applyRef(h, kind, refIdx)
                }
            }
            sig.add(h)
            i += len
        }
        return sig
    }
    private fun applyRef(h: Long, kind: Int, refIdx: Int): Long {
        return when (kind) {
            0 -> h * 31 + stringHashOf(refIdx)
            1 -> h * 31 + typeHashOf(refIdx)
            2 -> {
                if (refIdx < 0 || refIdx >= fieldIdsSize) h * 31
                else {
                    raf2.seek(fieldIdsOff.toLong() + refIdx * 8L)
                    val b1 = readBytes(raf2, 2)
                    val classIdx = u16(b1, 0)
                    val b2 = readBytes(raf2, 2)
                    val typeIdx = u16(b2, 0)
                    val nameS = readU32(raf2)
                    (h * 31 + typeHashOf(classIdx) * 31 + stringHashOf(nameS) * 31 + typeHashOf(typeIdx)) as Long
                }
            }
            else -> {
                if (refIdx < 0 || refIdx >= methodIdsSize) h * 31
                else {
                    raf2.seek(methodIdsOff.toLong() + refIdx * 8L)
                    val b1 = readBytes(raf2, 2)
                    val classIdx = u16(b1, 0)
                    readBytes(raf2, 2) // protoIdx
                    val nameS = readU32(raf2)
                    (h * 31 + typeHashOf(classIdx) * 31 + stringHashOf(nameS)) as Long
                }
            }
        }
    }
    private var typeTableCache: IntArray? = null
    private var classDefsCache: ByteArray? = null

    private fun typeTableGet(idx: Int): Int {
        var t = typeTableCache
        if (t == null) {
            t = IntArray(typeIdsSize)
            val b = ByteArray(typeIdsSize * 4)
            raf2.seek(typeIdsOff.toLong())
            raf2.readFully(b)
            for (i in 0 until typeIdsSize) t[i] = u32(b, i * 4)
            typeTableCache = t
        }
        return if (idx in 0 until typeIdsSize) t[idx] else -1
    }

    private fun classDefsBuffer(): ByteArray {
        var b = classDefsCache
        if (b == null) {
            b = ByteArray(classDefsSize * 32)
            raf2.seek(classDefsOff.toLong())
            raf2.readFully(b)
            classDefsCache = b
        }
        return b
    }

    private fun typeStringIdx(typeIdx: Int): Int? {
        val v = typeTableGet(typeIdx)
        return if (v >= 0) v else null
    }

    private fun typeHashOf(typeIdx: Int): Int {
        if (typeIdx < 0 || typeIdx >= typeIdsSize) return 0
        val cached = typeHashCache[typeIdx]
        if (cached != 0) return cached
        val sIdx = typeStringIdx(typeIdx) ?: return 0
        val h = stringHashOf(sIdx)
        typeHashCache[typeIdx] = h
        return h
    }

    private fun fieldNameIdx(fieldIdx: Int): Int {
        if (fieldIdx < 0 || fieldIdx >= fieldIdsSize) return -1
        raf2.seek(fieldIdsOff.toLong() + fieldIdx * 8L + 4)
        return readU32(raf2)
    }

    private fun fieldTypeIdx(fieldIdx: Int): Int {
        if (fieldIdx < 0 || fieldIdx >= fieldIdsSize) return -1
        raf2.seek(fieldIdsOff.toLong() + fieldIdx * 8L + 2)
        val b = readBytes(raf2, 2)
        return u16(b, 0)
    }

    private fun methodNameIdx(methodIdx: Int): Int {
        if (methodIdx < 0 || methodIdx >= methodIdsSize) return -1
        raf2.seek(methodIdsOff.toLong() + methodIdx * 8L + 4)
        return readU32(raf2)
    }

    private fun methodProtoIdx(methodIdx: Int): Int {
        if (methodIdx < 0 || methodIdx >= methodIdsSize) return -1
        raf2.seek(methodIdsOff.toLong() + methodIdx * 8L + 2)
        val b = readBytes(raf2, 2)
        return u16(b, 0)
    }

    private fun protoReturnStringIdx(protoIdx: Int): Int {
        if (protoIdx < 0 || protoIdx >= protoIdsSize) return -1
        raf2.seek(protoIdsOff.toLong() + protoIdx * 12L + 4)
        val typeIdx = readU32(raf2)
        return typeStringIdx(typeIdx) ?: -1
    }

    private fun protoParams(protoIdx: Int): List<Int> {
        if (protoIdx < 0 || protoIdx >= protoIdsSize) return emptyList()
        raf2.seek(protoIdsOff.toLong() + protoIdx * 12L + 8)
        val paramsOff = readU32(raf2)
        if (paramsOff == 0) return emptyList()
        raf2.seek(paramsOff.toLong())
        val n = readU32(raf2)
        val typeIdxs = IntArray(n)
        for (i in 0 until n) {
            val b = readBytes(raf2, 2)
            typeIdxs[i] = u16(b, 0)
        }
        val out = ArrayList<Int>(n)
        for (typeIdx in typeIdxs) {
            val sIdx = typeStringIdx(typeIdx) ?: continue
            out.add(sIdx)
        }
        return out
    }

    private fun stringHashOf(stringIdx: Int): Int {
        if (stringIdx < 0 || stringIdx >= stringIdsSize) return 0
        val cached = stringHashCache[stringIdx]
        if (cached != 0) return cached
        val s = stringAt(stringIdx) ?: return 0
        val h = s.hashCode()
        stringHashCache[stringIdx] = h
        return h
    }

    private fun stringAt(idx: Int): String? {
        if (idx < 0 || idx >= stringIdsSize) return null
        raf2.seek(stringIdsOff.toLong() + idx * 4L)
        val dataOff = readU32(raf2).toLong()
        raf2.seek(dataOff)
        val buf = ByteArray(512)
        val got = raf2.read(buf)
        if (got <= 0) return null
        var p = 0
        while (p < got && (buf[p].toInt() and 0x80) != 0) p++
        p++
        var end = -1
        var q = p
        while (q < got) {
            if (buf[q].toInt() == 0) { end = q; break }
            q++
        }
        if (end < 0) {
            // очень длинная строка — медленный путь от начала данных
            raf2.seek(dataOff)
            while (true) {
                val b = raf2.read()
                if (b < 0) return null
                if (b and 0x80 == 0) break
            }
            val sb = StringBuilder(128)
            var count = 0
            while (true) {
                val b = raf2.read()
                if (b < 0 || b == 0) break
                when {
                    b < 0x80 -> sb.append(b.toChar())
                    b and 0xE0 == 0xC0 -> {
                        val b2 = raf2.read()
                        sb.append((((b and 0x1F) shl 6) or (b2 and 0x3F)).toChar())
                    }
                    b and 0xF0 == 0xE0 -> {
                        val b2 = raf2.read()
                        val b3 = raf2.read()
                        sb.append((((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar())
                    }
                    else -> sb.append('\uFFFD')
                }
                count++
                if (count > (1 shl 16)) return null
            }
            return sb.toString()
        }
        val sb = StringBuilder(end - p)
        var k = p
        while (k < end) {
            val b = buf[k].toInt() and 0xFF
            when {
                b < 0x80 -> { sb.append(b.toChar()); k++ }
                b and 0xE0 == 0xC0 -> {
                    val b2 = buf[k + 1].toInt() and 0xFF
                    sb.append((((b and 0x1F) shl 6) or (b2 and 0x3F)).toChar())
                    k += 2
                }
                b and 0xF0 == 0xE0 -> {
                    val b2 = buf[k + 1].toInt() and 0xFF
                    val b3 = buf[k + 2].toInt() and 0xFF
                    sb.append((((b and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar())
                    k += 3
                }
                else -> { sb.append('\uFFFD'); k++ }
            }
        }
        return sb.toString()
    }
    private fun findStringIndex(target: String): Int? {
        var lo = 0
        var hi = stringIdsSize - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = stringAt(mid) ?: return null
            val c = s.compareTo(target)
            if (c == 0) return mid
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return null
    }

    private fun findTypeIndex(stringIdx: Int): Int? {
        // прогрев кэша один раз
        typeTableGet(0)
        val tt = typeTableCache ?: return null
        for (i in 0 until typeIdsSize) {
            if (tt[i] == stringIdx) return i
        }
        return null
    }

    fun debugOpInfo(op: Int): String = "op=0x" + Integer.toHexString(op) + " len=" + LEN[op] + " fmt=" + FMT[op] + " ref=" + REF_KIND[op] + " hash=" + OP_HASH[op]

    fun fieldIdsCount(): Int = fieldIdsSize

    /** Отладка: по-инструкционные значения для метода (по methodIdx). */
    fun debugInstrValuesFor(type: String, methodIdxWanted: Int): String {
        val sIdx = findStringIndex(type) ?: return "no class"
        val tIdx = findTypeIndex(sIdx) ?: return "no type"
        var classDefOff = -1L
        val buf = classDefsBuffer()
        for (i in 0 until classDefsSize) {
            if (i32(buf, i * 32) == tIdx) { classDefOff = classDefsOff.toLong() + i * 32L; break }
        }
        if (classDefOff < 0) return "not found"
        raf2.seek(classDefOff)
        val cd = ByteArray(32)
        raf2.readFully(cd)
        val classDataOff = u32(cd, 24).toLong()
        if (classDataOff == 0L) return "no classdata"
        raf.seek(classDataOff)
        val sf = readUleb(raf)
        val insf = readUleb(raf)
        val dm = readUleb(raf)
        val vm = readUleb(raf)
        var fieldIdx = 0
        repeat(sf + insf) {
            fieldIdx += readUleb(raf)
            readUleb(raf)
        }
        var codeOffWanted = 0L
        var methodIdx = 0
        repeat(dm + vm) {
            if (it == 0 || it == dm) methodIdx = 0
            methodIdx += readUleb(raf)
            readUleb(raf)
            val codeOff = readUleb(raf).toLong()
            if (methodIdx == methodIdxWanted) codeOffWanted = codeOff
        }
        if (codeOffWanted == 0L) return "no code for method $methodIdxWanted"
        // дамп инструкций — копия логики codeSignature c выводом
        val sb = StringBuilder()
        raf2.seek(codeOffWanted + 12)
        val insnsSize = readU32(raf2)
        raf2.seek(codeOffWanted + 16)
        val units = ByteArray(insnsSize * 2)
        raf2.readFully(units)
        var i = 0
        val n = insnsSize
        while (i < n) {
            val u0 = (units[2 * i].toInt() and 0xFF) or ((units[2 * i + 1].toInt() and 0xFF) shl 8)
            val op = u0 and 0xFF
            if (op == 0) { sb.append("[").append(i).append("] op=0x00 len=1 h=NOP\n"); i += 1; continue }
            val len = LEN[op]
            var h = OP_HASH[op].toLong()
            if (op == 0x01 || op == 0x04 || op == 0x07) {
                val b1 = ub(units, 2 * i + 1)
                val a = b1 and 0xF
                val b = (b1 shr 4) and 0xF
                if (a == b) { sb.append("[").append(i).append("] op=0x").append(Integer.toHexString(op)).append(" len=").append(len).append(" h=NOP(move-self)\n"); i += len; continue }
            }
            when (FMT[op]) {
                F_12X, F_22T, F_22C, F_22CS, F_22S -> {
                    val b1 = ub(units, 2 * i + 1)
                    val a = b1 and 0xF
                    val b = (b1 shr 4) and 0xF
                    h = h * 31 + a * 997 + b
                }
                F_22B -> {
                    val a = ub(units, 2 * i + 1)
                    val b = ub(units, 2 * i + 2)
                    h = h * 31 + a * 997 + b
                }
                F_11X, F_21T, F_21S, F_21IH, F_21LH, F_21C, F_31I, F_31T, F_31C, F_51L -> {
                    val a = ub(units, 2 * i + 1)
                    h = h * 31 + a
                }
                F_11N -> {
                    val b1 = ub(units, 2 * i + 1)
                    val a = b1 and 0xF
                    h = h * 31 + a
                }
                F_22X -> {
                    val a = ub(units, 2 * i + 1)
                    val b = u16Safe(units, 2 * i + 2)
                    h = h * 31 + a * 997 + b
                }
                F_32X -> {
                    val a = u16Safe(units, 2 * i + 2)
                    val b = u16Safe(units, 2 * i + 4)
                    h = h * 31 + a * 997 + b
                }
                F_23X -> {
                    val a = ub(units, 2 * i + 1)
                    val b = ub(units, 2 * i + 2)
                    val c = ub(units, 2 * i + 3)
                    h = h * 31 + a * 994009 + b * 997 + c
                }
                F_35C, F_35MS, F_35MI, F_45CC -> {
                    val c = ub(units, 2 * i + 4) and 0xF
                    val d = (ub(units, 2 * i + 4) shr 4) and 0xF
                    val e = ub(units, 2 * i + 5) and 0xF
                    h = h * 31 + c + d * 997 + e * 994009
                }
                F_3RC, F_3RMS, F_3RMI, F_4RCC -> {
                    val count = ub(units, 2 * i + 1)
                    val start = u16Safe(units, 2 * i + 4)
                    h = h * 31 + start + count * 997
                }
                else -> { }
            }
            when (FMT[op]) {
                F_11N -> {
                    val b1 = ub(units, 2 * i + 1)
                    var lit = (b1 shr 4) and 0xF
                    if (lit >= 8) lit -= 16
                    h = h * 31 + lit
                    val w = lit.toLong()
                    h = h * 31 + (w xor (w ushr 32)).toInt()
                }
                F_21S -> {
                    val lit = u16Safe(units, 2 * i + 2).toShort().toInt()
                    h = h * 31 + lit
                    val w = lit.toLong()
                    h = h * 31 + (w xor (w ushr 32)).toInt()
                }
                F_21IH -> {
                    val lit = u16Safe(units, 2 * i + 2).toShort().toInt() shl 16
                    h = h * 31 + lit
                    val w = lit.toLong()
                    h = h * 31 + (w xor (w ushr 32)).toInt()
                }
                F_21LH -> {
                    val wide = (u16Safe(units, 2 * i + 2).toShort().toLong()) shl 48
                    h = h * 31 + (wide xor (wide ushr 32)).toInt()
                }
                F_22B -> {
                    val lit = sb(units, 2 * i + 3)
                    h = h * 31 + lit
                    val w = lit.toLong()
                    h = h * 31 + (w xor (w ushr 32)).toInt()
                }
                F_22S -> {
                    val lit = u16Safe(units, 2 * i + 2).toShort().toInt()
                    h = h * 31 + lit
                    val w = lit.toLong()
                    h = h * 31 + (w xor (w ushr 32)).toInt()
                }
                F_31I -> {
                    val lit = u16Safe(units, 2 * i + 2) or (u16Safe(units, 2 * i + 4) shl 16)
                    h = h * 31 + lit
                    val w = lit.toLong()
                    h = h * 31 + (w xor (w ushr 32)).toInt()
                }
                F_51L -> {
                    var wide = 0L
                    for (k in 0 until 4) wide = wide or (u16Safe(units, 2 * i + 2 + 2 * k).toLong() shl (16 * k))
                    h = h * 31 + (wide xor (wide ushr 32)).toInt()
                }
                else -> { }
            }
            when (FMT[op]) {
                F_21C, F_31C -> {
                    val kind = REF_KIND[op]
                    if (kind >= 0) {
                        val refIdx = if (FMT[op] == F_21C) u16Safe(units, 2 * i + 2) else (u16Safe(units, 2 * i + 2) or (u16Safe(units, 2 * i + 4) shl 16))
                        h = applyRef(h, kind, refIdx)
                    }
                }
                F_22C, F_22CS -> {
                    val kind = REF_KIND[op]
                    if (kind >= 0) {
                        val refIdx = u16Safe(units, 2 * i + 2)
                        h = applyRef(h, kind, refIdx)
                    }
                }
                F_35C, F_35MS, F_35MI, F_45CC -> {
                    val kind = REF_KIND[op]
                    if (kind >= 0) {
                        val refIdx = u16Safe(units, 2 * i + 2)
                        h = applyRef(h, kind, refIdx)
                    }
                }
                F_3RC, F_3RMS, F_3RMI, F_4RCC -> {
                    val kind = REF_KIND[op]
                    if (kind >= 0) {
                        val refIdx = u16Safe(units, 2 * i + 2)
                        h = applyRef(h, kind, refIdx)
                    }
                }
                else -> { }
            }
            sb.append("[").append(i).append("] op=0x").append(Integer.toHexString(op)).append(" len=").append(len).append(" h=").append(h).append('\n')
            i += len
        }
        return sb.toString()
    }
    /** Отладка: СЫРЫЕ счётчики и записи class_data. */
    fun debugClassDataRaw(type: String): String {
        val sIdx = findStringIndex(type) ?: return "no class"
        val tIdx = findTypeIndex(sIdx) ?: return "no type"
        var classDefOff = -1L
        val buf = classDefsBuffer()
        for (i in 0 until classDefsSize) {
            if (i32(buf, i * 32) == tIdx) { classDefOff = classDefsOff.toLong() + i * 32L; break }
        }
        if (classDefOff < 0) return "not found"
        raf2.seek(classDefOff)
        val cd = ByteArray(32)
        raf2.readFully(cd)
        val classDataOff = u32(cd, 24).toLong()
        if (classDataOff == 0L) return "no classdata"
        val sb = StringBuilder()
        raf.seek(classDataOff)
        val pos0 = raf.filePointer
        val sf = readUleb(raf)
        val insf = readUleb(raf)
        val dm = readUleb(raf)
        val vm = readUleb(raf)
        sb.append("counts sf=").append(sf).append(" insf=").append(insf).append(" dm=").append(dm).append(" vm=").append(vm).append('\n')
        sb.append("bytes@classData=")
        val b = ByteArray(48)
        raf.seek(classDataOff)
        raf.readFully(b)
        sb.append(b.joinToString("") { String.format("%02X", it) }).append('\n')
        var fieldIdx = 0
        repeat(sf + insf) {
            fieldIdx += readUleb(raf)
            val acc = readUleb(raf)
            sb.append("FIELD idx=").append(fieldIdx).append(" acc=0x").append(Integer.toHexString(acc)).append('\n')
        }
        var methodIdx = 0
        repeat(dm + vm) {
            methodIdx += readUleb(raf)
            val acc = readUleb(raf)
            val codeOff = readUleb(raf)
            sb.append("METHOD idx=").append(methodIdx).append(" acc=0x").append(Integer.toHexString(acc)).append(" codeOff=").append(codeOff)
            val nameS = methodNameIdx(methodIdx)
            val proto = methodProtoIdx(methodIdx)
            sb.append(" nameS=").append(nameS).append("[").append(stringAt(nameS)).append("] proto=").append(proto).append('\n')
        }
        return sb.toString()
    }

    fun methodIdsCount(): Int = methodIdsSize

    /** Отладка: куски вклада ссылки. */
    fun debugRefPieces(kind: Int, refIdx: Int): String {
        return when (kind) {
            2 -> {
                raf2.seek(fieldIdsOff.toLong() + refIdx * 8L)
                val b1 = readBytes(raf2, 2)
                val classIdx = u16(b1, 0)
                val b2 = readBytes(raf2, 2)
                val typeIdx = u16(b2, 0)
                val nameS = readU32(raf2)
                val ch = typeHashOf(classIdx)
                val nh = stringHashOf(nameS)
                val th = typeHashOf(typeIdx)
                "fieldRef idx=$refIdx cls=$classIdx[${stringAt(typeStringIdx(classIdx) ?: -1)}] name=$nameS[${stringAt(nameS)}] type=$typeIdx[${stringAt(typeStringIdx(typeIdx) ?: -1)}] ch=$ch nh=$nh th=$th"
            }
            else -> {
                raf2.seek(methodIdsOff.toLong() + refIdx * 8L)
                val b1 = readBytes(raf2, 2)
                val classIdx = u16(b1, 0)
                readBytes(raf2, 2)
                val nameS = readU32(raf2)
                val ch = typeHashOf(classIdx)
                val nh = stringHashOf(nameS)
                "methodRef idx=$refIdx cls=$classIdx[${stringAt(typeStringIdx(classIdx) ?: -1)}] name=$nameS[${stringAt(nameS)}] ch=$ch nh=$nh"
            }
        }
    }

    /** Отладка: методы класса (ключ, mh и куски). */
    fun debugMethods(type: String): String {
        val sIdx = findStringIndex(type) ?: return "no class"
        val tIdx = findTypeIndex(sIdx) ?: return "no type"
        var classDefOff = -1L
        val buf = classDefsBuffer()
        for (i in 0 until classDefsSize) {
            if (i32(buf, i * 32) == tIdx) { classDefOff = classDefsOff.toLong() + i * 32L; break }
        }
        if (classDefOff < 0) return "not found"
        raf2.seek(classDefOff)
        val cd = ByteArray(32)
        raf2.readFully(cd)
        val classDataOff = u32(cd, 24).toLong()
        if (classDataOff == 0L) return "no classdata"
        val sb = StringBuilder()
        raf.seek(classDataOff)
        val sf = readUleb(raf)
        val insf = readUleb(raf)
        val dm = readUleb(raf)
        val vm = readUleb(raf)
        var fieldIdx = 0
        repeat(sf) {
            fieldIdx += readUleb(raf)
            readUleb(raf)
        }
        fieldIdx = 0
        repeat(insf) {
            fieldIdx += readUleb(raf)
            readUleb(raf)
        }
        var methodIdx = 0
        repeat(dm + vm) {
            if (it == 0 || it == dm) methodIdx = 0
            methodIdx += readUleb(raf)
            readUleb(raf)
            readUleb(raf)
            val nameS = methodNameIdx(methodIdx)
            val protoIdx = methodProtoIdx(methodIdx)
            val name = stringAt(nameS) ?: "?"
            val retT = protoReturnStringIdx(protoIdx)
            val ret = if (retT >= 0) stringAt(retT) ?: "?" else "?"
            val params = protoParams(protoIdx)
            val paramStr = params.joinToString("") { stringAt(it) ?: "?" }
            var mh = stringHashOf(nameS).toLong()
            val pieces = StringBuilder("name=$nameS[$name] ret=$retT[$ret] params=$params[$paramStr] mh0=${stringHashOf(nameS)}")
            for (p in params) mh = mh * 31 + stringHashOf(p)
            mh = mh * 31 + stringHashOf(retT)
            sb.append("M methodIdx=").append(methodIdx).append(' ').append(pieces).append(" mh=").append(mh).append('\n')
        }
        return sb.toString()
    }
    /** Отладочный дамп: разбор class_data по классу (поля). */
    fun debugClassFields(type: String): String {
        val sIdx = findStringIndex(type) ?: return "no class"
        val tIdx = findTypeIndex(sIdx) ?: return "no type"
        var classDefOff = -1L
        val buf = classDefsBuffer()
        for (i in 0 until classDefsSize) {
            if (i32(buf, i * 32) == tIdx) { classDefOff = classDefsOff.toLong() + i * 32L; break }
        }
        if (classDefOff < 0) return "not found"
        raf2.seek(classDefOff)
        val cd = ByteArray(32)
        raf2.readFully(cd)
        val classDataOff = u32(cd, 24).toLong()
        if (classDataOff == 0L) return "no classdata"
        val sb = StringBuilder()
        raf.seek(classDataOff)
        val sf = readUleb(raf)
        val insf = readUleb(raf)
        readUleb(raf); readUleb(raf)
        var fieldIdx = 0
        repeat(sf + insf) {
            fieldIdx += readUleb(raf)
            val acc = readUleb(raf)
            val nameS = fieldNameIdx(fieldIdx)
            val typeS = fieldTypeIdx(fieldIdx)
            val h = stringHashOf(nameS).toLong() * 31 + typeHashOf(typeS).toLong()
            sb.append("FIELD fieldIdx=").append(fieldIdx)
                .append(" acc=0x").append(Integer.toHexString(acc))
                .append(" nameS=").append(nameS).append('[').append(stringAt(nameS)).append(']')
                .append(" typeS=").append(typeS).append('[').append(stringAt(typeS)).append(']')
                .append(" h=").append(h).append('\n')
        }
        return sb.toString()
    }
    /** Отладочный дамп: сырые field_id записи и мои резолвы по классу. */
    fun debugFieldRefs(from: Int, count: Int): String {
        val sb = StringBuilder()
        val n = minOf(fieldIdsSize, from + count)
        for (i in from until n) {
            raf2.seek(fieldIdsOff.toLong() + i * 8L)
            val raw = readBytes(raf2, 8)
            val hex = raw.joinToString("") { String.format("%02X", it) }
            val classIdx = u16(raw, 0)
            val typeIdx = u16(raw, 2)
            val nameS = u32(raw, 4)
            sb.append("FID ").append(i).append(" raw=").append(hex)
                .append(" cls=").append(classIdx).append('[').append(stringAt(typeStringIdx(classIdx) ?: -1)).append(']')
                .append(" type=").append(typeIdx).append('[').append(stringAt(typeStringIdx(typeIdx) ?: -1)).append(']')
                .append(" name=").append(nameS).append('[').append(stringAt(nameS)).append(']')
                .append('\n')
        }
        return sb.toString()
    }

    /** Отладочный резолв типа: строка типа по индексу. */
    fun debugType(typeIdx: Int): String = "typeIdx=$typeIdx -> " + (typeStringIdx(typeIdx)?.let { "strIdx=$it [${stringAt(it)}]" } ?: "null")
    private fun u16Safe(units: ByteArray, off: Int): Int =
        if (off >= 0 && off + 1 < units.size) u16(units, off) else 0

    private fun ub(units: ByteArray, off: Int): Int =
        if (off >= 0 && off < units.size) units[off].toInt() and 0xFF else 0

    private fun sb(units: ByteArray, off: Int): Int =
        if (off >= 0 && off < units.size) units[off].toInt() else 0

    private fun readBytes(h: MemFile, n: Int): ByteArray {
        val b = ByteArray(n)
        h.readFully(b)
        return b
    }

    private fun readU32(h: MemFile): Int {
        val b = readBytes(h, 4)
        return u32(b, 0)
    }

    private fun readUleb(h: MemFile): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = h.read()
            if (b < 0) throw IllegalStateException("uleb EOF")
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    override fun close() {
        // in-memory — освобождать нечего
    }

    companion object {
        private const val NOP_MARKER = Long.MIN_VALUE

        // id форматов (см. генератор GenOpTables)
        private const val F_10X = 1
        private const val F_12X = 2
        private const val F_11N = 3
        private const val F_11X = 4
        private const val F_10T = 5
        private const val F_20T = 6
        private const val F_30T = 7
        private const val F_22X = 8
        private const val F_21T = 9
        private const val F_21S = 10
        private const val F_21IH = 11
        private const val F_21LH = 12
        private const val F_21C = 13
        private const val F_23X = 14
        private const val F_22B = 15
        private const val F_22T = 16
        private const val F_22S = 17
        private const val F_22C = 18
        private const val F_32X = 19
        private const val F_31I = 20
        private const val F_31T = 21
        private const val F_31C = 22
        private const val F_35C = 23
        private const val F_35MS = 24
        private const val F_35MI = 25
        private const val F_3RC = 26
        private const val F_3RMS = 27
        private const val F_3RMI = 28
        private const val F_51L = 29
        private const val F_45CC = 30
        private const val F_4RCC = 31
        private const val F_20BC = 32
        private const val F_22CS = 33
        // биты применимости вкладов (наборы интерфейсов инструкции, как в dexlib2)
        private const val B_ONE = 1
        private const val B_TWO = 2
        private const val B_THREE = 4
        private const val B_FIVE = 8
        private const val B_RANGE = 16
        private const val B_NARROW = 32
        private const val B_WIDE = 64
        private const val B_REF = 128

private val LEN = intArrayOf(1, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 3, 2, 2, 3, 5, 2, 2, 3, 2, 1, 1, 2, 2, 1, 2, 2, 3, 3, 3, 1, 1, 2, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 1, 3, 3, 3, 3, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 2, 3, 3, 3, 1, 2, 2, 2, 2, 2, 2, 3, 3, 4, 4, 2, 2, 2, 2)

        private val FMT = intArrayOf(1, 2, 8, 19, 2, 8, 19, 2, 8, 19, 4, 4, 4, 4, 1, 4, 4, 4, 3, 10, 20, 11, 10, 20, 29, 12, 13, 22, 13, 4, 4, 13, 18, 2, 13, 18, 23, 26, 21, 4, 5, 6, 7, 21, 21, 14, 14, 14, 14, 14, 16, 16, 16, 16, 16, 16, 9, 9, 9, 9, 9, 9, 0, 0, 0, 0, 0, 0, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 23, 23, 23, 23, 23, 0, 26, 26, 26, 26, 26, 0, 0, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 17, 17, 17, 17, 17, 17, 17, 17, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 15, 18, 18, 13, 13, 18, 18, 18, 13, 13, 0, 32, 25, 28, 26, 1, 33, 33, 33, 33, 33, 33, 24, 27, 30, 31, 18, 13, 13, 13)

        private val REF_KIND = intArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, -1, 3, 3, 3, 3, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2, 2, 2, 2, 2, 2, 2, 2, 2, -1, -1, -1, -1, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, 3, 3, 2, 2, -1, -1)

        private val OP_HASH = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 0, 0, 0, 0, 0, 0, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 0, 109, 110, 111, 112, 113, 0, 0, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 0, 227, 228, 229, 231, 232, 234, 235, 236, 237, 238, 239, 248, 249, 258, 259, 252, 253, 262, 263)

        private val FLAGS = intArrayOf(0, 3, 3, 3, 3, 3, 3, 3, 3, 3, 1, 1, 1, 1, 0, 1, 1, 1, 97, 97, 97, 97, 97, 97, 65, 65, 129, 129, 129, 1, 1, 129, 131, 3, 129, 131, 136, 144, 1, 1, 0, 0, 0, 1, 1, 7, 7, 7, 7, 7, 3, 3, 3, 3, 3, 3, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 131, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 129, 136, 136, 136, 136, 136, 0, 144, 144, 144, 144, 144, 0, 0, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 131, 131, 129, 129, 131, 131, 131, 129, 129, 0, 128, 8, 16, 144, 0, 3, 3, 3, 3, 3, 3, 8, 16, 136, 144, 131, 129, 129, 129)

        private const val PAYLOAD_PACKED_ORD = 255

        private const val PAYLOAD_SPARSE_ORD = 256

        private const val PAYLOAD_ARRAY_ORD = 257

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                    ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun i32(b: ByteArray, off: Int): Int = u32(b, off)
    }
}