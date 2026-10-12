package com.example.lazymodification.utils

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * ⚡ Быстрый бинарный доступ к DEX-файлу: находит суперкласс типа без полной
 * загрузки через dexlib2 (таблица строк отсортирована, поэтому имя ищется
 * бинарным поиском). Используется при инжекте движка удаления проверки подписи.
 *
 * Только java.io — класс юнит-тестируется на JVM.
 */
class DexFastScanner(file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    private val stringIdsOff: Int
    private val stringIdsSize: Int
    private val typeIdsOff: Int
    private val typeIdsSize: Int
    private val classDefsOff: Int
    private val classDefsSize: Int

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
            classDefsSize = u32(header, 96)
            classDefsOff = u32(header, 100)
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    /** Суперкласс типа [type] или null, если класса нет в этом dex. */
    fun superOf(type: String): String? {
        if (stringIdsSize <= 0 || classDefsSize <= 0 || typeIdsSize <= 0) return null
        val sIdx = findStringIndex(type) ?: return null
        val tIdx = findTypeIndex(sIdx) ?: return null
        val buf = ByteArray(classDefsSize * 32)
        raf.seek(classDefsOff.toLong())
        raf.readFully(buf)
        for (i in 0 until classDefsSize) {
            val off = i * 32
            if (i32(buf, off) == tIdx) {
                val superIdx = i32(buf, off + 8)
                if (superIdx < 0) return "Ljava/lang/Object;"
                if (superIdx >= typeIdsSize) return null
                val supS = typeStringIdx(superIdx) ?: return null
                return stringAt(supS)
            }
        }
        return null
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
        val buf = ByteArray(typeIdsSize * 4)
        raf.seek(typeIdsOff.toLong())
        raf.readFully(buf)
        for (i in 0 until typeIdsSize) {
            if (u32(buf, i * 4) == stringIdx) return i
        }
        return null
    }

    private fun typeStringIdx(typeIdx: Int): Int? {
        if (typeIdx < 0 || typeIdx >= typeIdsSize) return null
        raf.seek(typeIdsOff.toLong() + typeIdx * 4L)
        return readU32()
    }

    private fun stringAt(idx: Int): String? {
        if (idx < 0 || idx >= stringIdsSize) return null
        raf.seek(stringIdsOff.toLong() + idx * 4L)
        raf.seek(readU32().toLong())
        // uleb128 длина в UTF-16 юнитах — пропускаем
        while (true) {
            val b = raf.read()
            if (b < 0) return null
            if (b and 0x80 == 0) break
        }
        val out = ByteArrayOutputStream(64)
        var count = 0
        while (true) {
            val b = raf.read()
            if (b < 0 || b == 0) break
            out.write(b)
            count++
            if (count > (1 shl 16)) return null
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    override fun close() {
        raf.close()
    }

    private fun readU32(): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return u32(b, 0)
    }

    companion object {
        private fun u32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                    ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun i32(b: ByteArray, off: Int): Int = u32(b, off)
    }
}