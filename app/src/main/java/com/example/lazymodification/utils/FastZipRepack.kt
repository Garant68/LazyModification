package com.example.lazymodification.utils

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * ⚡ Быстрая однопроходная перепаковка APK (подход как у MT Manager):
 *  - неизменённые записи копируются СЫРЫМИ байтами (без распаковки и пере-сжатия);
 *  - заменяемые/новые записи (dex и т.п.) пишутся из файлов как STORED;
 *  - лишние записи (kotlin-метаданные и пр.) отбрасываются;
 *  - STORED-записи выравниваются: lib/(.so) — 4096 байт (page align, как
 *    zipalign -p 4), остальные — 4 байта. Выравнивание реализовано «зазорами»
 *    между записями, что для читателей ZIP (и для платформы Android) равнозначно
 *    выравниванию zipalign.
 *
 * Только java.io/java.util.zip — класс юнит-тестируется на JVM.
 */
object FastZipRepack {

    data class Counts(val kept: Int, val removed: Int)

    private const val EOCD_SIG = 0x06054b50L
    private const val CD_SIG = 0x02014b50L
    private const val LFH_SIG = 0x04034b50L

    /** Правила очистки — синхронизированы с PatchService.cleanApkContents. */
    fun shouldRemove(name: String): Boolean {
        val isInRoot = !name.contains('/') && !name.endsWith('/')
        val baseName = name.substringAfterLast('/')
        val isKept = baseName.equals("AndroidManifest.xml", ignoreCase = true) ||
                baseName.equals("resources.arsc", ignoreCase = true) ||
                baseName.equals("resources.pb", ignoreCase = true) ||
                baseName.endsWith(".dex") ||
                baseName.equals("stamp-cert-hash", ignoreCase = true) ||
                baseName.equals("stamp-cert-sha256", ignoreCase = true)
        return (isInRoot && !isKept) ||
                name.startsWith("kotlin/") ||
                name.startsWith("kotlin-debug/") ||
                name.startsWith("META-INF/kotlin") ||
                name == "DebugProbesKt.bin"
    }

    private class Entry(
        val name: String, val method: Int, val flags: Int,
        val crc: Long, val compSize: Long, val uncompSize: Long,
        val localOffset: Long, val time: Int, val date: Int, val extAttr: Int
    )

    fun repack(
        inputApk: File,
        replacements: Map<String, File>,
        outputApk: File,
        applyClean: Boolean
    ): Counts {
        if (outputApk.exists()) outputApk.delete()
        var kept = 0
        var removed = 0

        RandomAccessFile(inputApk, "r").use { raf ->
            // --- EOCD ---
            val fileLen = raf.length()
            val scanLen = minOf(fileLen, 65557L).toInt()
            val tail = ByteArray(scanLen)
            raf.seek(fileLen - scanLen)
            raf.readFully(tail)
            var eocdIdx = -1
            for (i in scanLen - 4 downTo 0) {
                if (tail[i].toInt() == 0x50 && tail[i + 1].toInt() == 0x4B &&
                    tail[i + 2].toInt() == 0x05 && tail[i + 3].toInt() == 0x06
                ) {
                    eocdIdx = i; break
                }
            }
            if (eocdIdx < 0) throw IOException("EOCD не найден")
            val entriesDisk = u16(tail, eocdIdx + 8)
            val totalEntries = u16(tail, eocdIdx + 10)
            val cdOffset = u32(tail, eocdIdx + 16)
            if (totalEntries == 0 || totalEntries == 0xFFFF || cdOffset == 0xFFFFFFFFL || entriesDisk != totalEntries) {
                throw IOException("ZIP64/неподдерживаемый архив")
            }

            // --- центральный каталог ---
            raf.seek(cdOffset)
            val src = ArrayList<Entry>(totalEntries)
            repeat(totalEntries) {
                val hdr = ByteArray(46)
                raf.readFully(hdr)
                if (u32(hdr, 0) != CD_SIG) throw IOException("Повреждён центральный каталог")
                val flags = u16(hdr, 8)
                val method = u16(hdr, 10)
                val time = u16(hdr, 12)
                val date = u16(hdr, 14)
                val crc = u32(hdr, 16)
                val compSize = u32(hdr, 20)
                val uncompSize = u32(hdr, 24)
                val nameLen = u16(hdr, 28)
                val extraLen = u16(hdr, 30)
                val commentLen = u16(hdr, 32)
                val extAttr = u32(hdr, 38).toInt()
                val localOffset = u32(hdr, 42)
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                if (extraLen > 0) raf.skipBytes(extraLen)
                if (commentLen > 0) raf.skipBytes(commentLen)
                src.add(
                    Entry(
                        String(nameBytes, Charsets.UTF_8), method, flags, crc, compSize,
                        uncompSize, localOffset, time, date, extAttr
                    )
                )
            }
            src.sortBy { it.localOffset }

            val out = BufferedOutputStream(FileOutputStream(outputApk), 1 shl 16)
            try {
                var pos = 0L
                val cdRecords = ArrayList<ByteArray>(totalEntries + replacements.size)
                val buf = ByteArray(1 shl 16)

                fun write(bytes: ByteArray, len: Int = bytes.size) {
                    out.write(bytes, 0, len); pos += len
                }
                // Паддинг выравнивания в extra-field — тем же форматом, что и zipalign:
                // запись [35 D9][size=padLen-4][align(u16 LE)][нули].
                fun buildAlignPad(rawEndPos: Long, align: Long): ByteArray? {
                    val raw = rawEndPos % align
                    val need = if (raw == 0L) 0L else align - raw
                    if (need == 0L) return null
                    var padLen = need
                    if (padLen < 6L) padLen += ((6L - padLen + align - 1) / align) * align
                    val pad = ByteArray(padLen.toInt())
                    pad[0] = 0x35; pad[1] = 0xD9.toByte()
                    val size = padLen - 4L
                    pad[2] = (size and 0xFF).toByte(); pad[3] = ((size ushr 8) and 0xFF).toByte()
                    pad[4] = (align and 0xFF).toByte(); pad[5] = ((align ushr 8) and 0xFF).toByte()
                    return pad
                }
                fun alignFor(name: String): Long =
                    if (name.startsWith("lib/") && name.endsWith(".so")) 16384L else 4L
                fun writeFresh(name: String, data: ByteArray, time: Int, date: Int, extAttr: Int) {
                    val nameBytes = name.toByteArray(Charsets.UTF_8)
                    val alignPad = buildAlignPad(pos + 30L + nameBytes.size, 4L)
                    val off = pos
                    val crc = CRC32()
                    crc.update(data)
                    val h = ByteArray(30)
                    putU32(h, 0, LFH_SIG)
                    putU16(h, 4, 20); putU16(h, 6, 0); putU16(h, 8, 0)
                    putU16(h, 10, time); putU16(h, 12, date)
                    putU32(h, 14, crc.value)
                    putU32(h, 18, data.size.toLong()); putU32(h, 22, data.size.toLong())
                    putU16(h, 26, nameBytes.size); putU16(h, 28, if (alignPad != null) alignPad.size else 0)
                    write(h); write(nameBytes)
                    if (alignPad != null) write(alignPad)
                    write(data)
                    cdRecords.add(
                        buildCenterRecord(nameBytes, 0, 0, crc.value, data.size.toLong(), data.size.toLong(), off, time, date, extAttr)
                    )
                }

                val usedNames = HashSet<String>()
                for (e in src) {
                    usedNames.add(e.name)
                    if (applyClean && shouldRemove(e.name)) { removed++; continue }
                    val repl = replacements[e.name]
                    if (repl != null) {
                        writeFresh(e.name, repl.readBytes(), e.time, e.date, e.extAttr)
                        kept++
                        continue
                    }
                    // --- сырое копирование записи ---
                    raf.seek(e.localOffset)
                    val lfh = ByteArray(30)
                    raf.readFully(lfh)
                    if (u32(lfh, 0) != LFH_SIG) throw IOException("LFH повреждён: ${e.name}")
                    val lNameLen = u16(lfh, 26)
                    val lExtraLen = u16(lfh, 28)
                    var alignPad: ByteArray? = null
                    if (e.method == 0 && e.compSize > 0L) {
                        alignPad = buildAlignPad(pos + 30L + lNameLen + lExtraLen, alignFor(e.name))
                    }
                    // Убираем data descriptor: чистим бит 3 и вписываем crc/размеры прямо в LFH
                    putU16(lfh, 6, u16(lfh, 6) and 0x0008.inv())
                    putU32(lfh, 14, e.crc)
                    putU32(lfh, 18, e.compSize)
                    putU32(lfh, 22, e.uncompSize)
                    if (alignPad != null) putU16(lfh, 28, lExtraLen + alignPad.size)
                    val off = pos
                    write(lfh)
                    var remaining = lNameLen.toLong() + lExtraLen
                    raf.seek(e.localOffset + 30L)
                    while (remaining > 0L) {
                        val r = raf.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                        if (r <= 0) throw IOException("EOF: ${e.name}")
                        write(buf, r)
                        remaining -= r
                    }
                    if (alignPad != null) write(alignPad)
                    remaining = e.compSize
                    while (remaining > 0L) {
                        val r = raf.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                        if (r <= 0) throw IOException("EOF: ${e.name}")
                        write(buf, r)
                        remaining -= r
                    }
                    val flags = e.flags and 0x0008.inv() // дескриптора данных в новом файле нет
                    cdRecords.add(
                        buildCenterRecord(
                            e.name.toByteArray(Charsets.UTF_8), flags, e.method, e.crc,
                            e.compSize, e.uncompSize, off, e.time, e.date, e.extAttr
                        )
                    )
                    kept++
                }
                // --- новые записи (например, classes6.dex) ---
                for ((name, f) in replacements) {
                    if (usedNames.contains(name)) continue
                    if (applyClean && shouldRemove(name)) { removed++; continue }
                    writeFresh(name, f.readBytes(), 0x5000, 0x5A21, 0)
                    kept++
                }
                // --- центральный каталог ---
                val cdStart = pos
                for (rec in cdRecords) write(rec)
                val cdLen = pos - cdStart
                // --- EOCD ---
                val eo = ByteArray(22)
                putU32(eo, 0, EOCD_SIG)
                putU16(eo, 8, cdRecords.size)
                putU16(eo, 10, cdRecords.size)
                putU32(eo, 12, cdLen)
                putU32(eo, 16, cdStart)
                write(eo)
                out.flush()
            } finally {
                out.close()
            }
        }
        return Counts(kept, removed)
    }

    private fun buildCenterRecord(
        nameBytes: ByteArray, flags: Int, method: Int, crc: Long, comp: Long, uncomp: Long,
        localOffset: Long, time: Int, date: Int, extAttr: Int
    ): ByteArray {
        val rec = ByteArray(46 + nameBytes.size)
        putU32(rec, 0, CD_SIG)
        putU16(rec, 4, 20); putU16(rec, 6, 20)
        putU16(rec, 8, flags); putU16(rec, 10, method)
        putU16(rec, 12, time); putU16(rec, 14, date)
        putU32(rec, 16, crc); putU32(rec, 20, comp); putU32(rec, 24, uncomp)
        putU16(rec, 28, nameBytes.size); putU16(rec, 30, 0); putU16(rec, 32, 0)
        putU16(rec, 34, 0); putU16(rec, 36, 0)
        putU32(rec, 38, extAttr.toLong() and 0xFFFFFFFFL)
        putU32(rec, 42, localOffset)
        System.arraycopy(nameBytes, 0, rec, 46, nameBytes.size)
        return rec
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        for (i in 0..3) b[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}