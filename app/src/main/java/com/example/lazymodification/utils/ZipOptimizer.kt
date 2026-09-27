package com.example.lazymodification.utils

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Экстремальное пересжатие APK: собственный ZIP-пережиматель.
 * Каждая запись сжимается DEFLATE level 9 с перебором стратегий (default/filtered/huffman-only),
 * выбирается минимальный результат; если сжатие не даёт выигрыша — запись хранится STORED.
 * Возвращает количество сэкономленных байт (может быть <= 0).
 */
object ZipOptimizer {

    fun recompressUltra(input: File, output: File): Long {
        val before = input.length()
        val entries = ArrayList<EntryMeta>()
        val countingOut = CountingOutputStream(BufferedOutputStream(FileOutputStream(output)))
        val dos = DataOutputStream(countingOut)

        ZipInputStream(BufferedInputStream(FileInputStream(input))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val raw = zis.readBytes()
                val crc = crc32(raw)

                // Сохраняем исходный метод: STORED-записи (resources.arsc, PNG, .so) не сжимаем,
                // иначе на Android 11+ APK не установится (сжатый resources.arsc = повреждённый пакет).
                val originalMethod = entry.method
                val compressed = if (originalMethod == ZipEntry.DEFLATED) compressUltra(raw) else null
                val method: Int
                val data: ByteArray
                if (compressed != null && compressed.size < raw.size) {
                    method = METHOD_DEFLATED
                    data = compressed
                } else {
                    method = METHOD_STORED
                    data = raw
                }

                val localOffset = countingOut.count
                val dosTime = if (entry.time < 0) 0L else entry.time
                writeLocalHeader(dos, name, method, crc, data.size.toLong(), raw.size.toLong(), dosTime)
                dos.write(data)
                entries.add(EntryMeta(name, method, crc, data.size.toLong(), raw.size.toLong(), localOffset, dosTime))
                entry = zis.nextEntry
            }
        }

        val cdOffset = countingOut.count
        for (m in entries) writeCentralHeader(dos, m)
        val cdSize = countingOut.count - cdOffset
        writeEocd(dos, entries.size, cdSize, cdOffset)
        dos.flush()
        countingOut.flush()

        return before - output.length()
    }

    private fun compressUltra(data: ByteArray): ByteArray? {
        if (data.size < 16) return null
        var best: ByteArray? = null
        val strategies = intArrayOf(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)
        for (strategy in strategies) {
            val d = Deflater(9, true) // level 9, nowrap (raw DEFLATE, как нужно для ZIP)
            try {
                d.setStrategy(strategy)
                d.setInput(data)
                d.finish()
                val buf = ByteArrayOutputStream((data.size / 2).coerceAtLeast(64))
                val tmp = ByteArray(65536)
                while (!d.finished()) {
                    val n = d.deflate(tmp)
                    if (n > 0) buf.write(tmp, 0, n)
                }
                val out = buf.toByteArray()
                if (best == null || out.size < best.size) best = out
            } finally {
                d.end()
            }
        }
        return best
    }

    private fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    private data class EntryMeta(
        val name: String, val method: Int, val crc: Long,
        val compressedSize: Long, val uncompressedSize: Long,
        val localOffset: Long, val dosTime: Long
    )

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() { out.flush() }
        override fun close() { out.close() }
    }

    private fun writeLocalHeader(dos: DataOutputStream, name: String, method: Int, crc: Long, compressed: Long, uncompressed: Long, dosTime: Long) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val time = (dosTime and 0xFFFF).toInt()
        val date = ((dosTime shr 16) and 0xFFFF).toInt()
        writeIntLE(dos, 0x04034b50L)
        writeShortLE(dos, 20)
        writeShortLE(dos, 0x0800)
        writeShortLE(dos, method)
        writeShortLE(dos, time)
        writeShortLE(dos, date)
        writeIntLE(dos, crc)
        writeIntLE(dos, compressed)
        writeIntLE(dos, uncompressed)
        writeShortLE(dos, nameBytes.size)
        writeShortLE(dos, 0)
        dos.write(nameBytes)
    }

    private fun writeCentralHeader(dos: DataOutputStream, m: EntryMeta) {
        val nameBytes = m.name.toByteArray(Charsets.UTF_8)
        val time = (m.dosTime and 0xFFFF).toInt()
        val date = ((m.dosTime shr 16) and 0xFFFF).toInt()
        writeIntLE(dos, 0x02014b50L)
        writeShortLE(dos, 20)
        writeShortLE(dos, 20)
        writeShortLE(dos, 0x0800)
        writeShortLE(dos, m.method)
        writeShortLE(dos, time)
        writeShortLE(dos, date)
        writeIntLE(dos, m.crc)
        writeIntLE(dos, m.compressedSize)
        writeIntLE(dos, m.uncompressedSize)
        writeShortLE(dos, nameBytes.size)
        writeShortLE(dos, 0)
        writeShortLE(dos, 0)
        writeShortLE(dos, 0)
        writeShortLE(dos, 0)
        writeIntLE(dos, 0L)
        writeIntLE(dos, m.localOffset)
        dos.write(nameBytes)
    }

    private fun writeEocd(dos: DataOutputStream, entries: Int, cdSize: Long, cdOffset: Long) {
        writeIntLE(dos, 0x06054b50L)
        writeShortLE(dos, 0)
        writeShortLE(dos, 0)
        writeShortLE(dos, entries)
        writeShortLE(dos, entries)
        writeIntLE(dos, cdSize)
        writeIntLE(dos, cdOffset)
        writeShortLE(dos, 0)
    }

    private fun writeShortLE(dos: DataOutputStream, v: Int) {
        dos.write(v and 0xFF)
        dos.write((v shr 8) and 0xFF)
    }

    private fun writeIntLE(dos: DataOutputStream, v: Long) {
        dos.write((v and 0xFF).toInt())
        dos.write(((v shr 8) and 0xFF).toInt())
        dos.write(((v shr 16) and 0xFF).toInt())
        dos.write(((v shr 24) and 0xFF).toInt())
    }
}
