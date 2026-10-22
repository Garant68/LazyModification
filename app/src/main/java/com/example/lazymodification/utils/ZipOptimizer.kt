package com.example.lazymodification.utils

import com.googlecode.pngtastic.core.processing.zopfli.Options
import com.googlecode.pngtastic.core.processing.zopfli.Zopfli
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Экстремальное пересжатие APK: собственный ZIP-пережиматель.
 * Текстовые/DEX-записи пересжимаются Zopfli, для мелких файлов используется быстрый zlib.
 * Если сжатие не даёт выигрыша — запись хранится STORED.
 * Возвращает количество сэкономленных байт (может быть <= 0).
 */
object ZipOptimizer {

    private val zopfli = Zopfli(1 shl 20)

    fun recompressUltra(input: File, output: File, onProgress: ((Long, Long) -> Unit)? = null): Long {
        val before = input.length()
        val entries = ArrayList<EntryMeta>()
        val countingOut = CountingOutputStream(BufferedOutputStream(FileOutputStream(output)))
        val dos = DataOutputStream(countingOut)
        val cin = CountingInputStream(FileInputStream(input))
        val totalBytes = before.coerceAtLeast(1L)

        ZipInputStream(BufferedInputStream(cin)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                onProgress?.invoke(cin.count, totalBytes)
                val name = entry.name
                val raw = zis.readBytes()
                val crc = crc32(raw)

                val originalMethod = entry.method
                val compressed = if (shouldCompress(name, originalMethod)) compressUltra(raw) else null
                onProgress?.invoke(cin.count, totalBytes)
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

        onProgress?.invoke(totalBytes, totalBytes)
        val cdOffset = countingOut.count
        for (m in entries) writeCentralHeader(dos, m)
        val cdSize = countingOut.count - cdOffset
        writeEocd(dos, entries.size, cdSize, cdOffset)
        dos.flush()
        countingOut.flush()

        return before - output.length()
    }

    /** resources.arsc и уже сжатые медиа/нативные библиотеки не трогаем. */
    private fun shouldCompress(name: String, method: Int): Boolean {
        if (name == "resources.arsc") return false
        val ext = name.substringAfterLast('.', "").lowercase()
        if (method == ZipEntry.STORED) {
            if (ext in keepStoredExtensions) return false
            if (ext !in textExtensions) return false
        }
        return true
    }

    private fun compressUltra(data: ByteArray): ByteArray? {
        if (data.size < 16) return null
        if (data.size >= 4096) {
            try {
                val iterations = if (data.size > 2 * 1024 * 1024) 5 else 15
                val out = ByteArrayOutputStream((data.size / 2).coerceAtLeast(64))
                zopfli.compress(
                    Options(Options.OutputFormat.DEFLATE, Options.BlockSplitting.FIRST, iterations),
                    data,
                    out
                )
                return out.toByteArray()
            } catch (_: OutOfMemoryError) {
                System.gc()
            } catch (_: Throwable) {
                // Ниже — обычный zlib как страховка.
            }
        }
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

    private val textExtensions = setOf(
        "xml", "json", "txt", "version", "properties", "md", "html", "htm", "js", "css",
        "smali", "proto", "csv", "yml", "yaml", "kotlin_builtins", "builtins", "dex"
    )
    private val keepStoredExtensions = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "mp3", "mp4", "m4a", "ogg", "wav",
        "flac", "arsc", "so", "zip", "apk", "jar", "7z", "rar"
    )

    private data class EntryMeta(
        val name: String, val method: Int, val crc: Long,
        val compressedSize: Long, val uncompressedSize: Long,
        val localOffset: Long, val dosTime: Long
    )

    private class CountingInputStream(private val inp: InputStream) : InputStream() {
        var count: Long = 0L
            private set
        override fun read(): Int {
            val r = inp.read()
            if (r >= 0) count++
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = inp.read(b, off, len)
            if (r > 0) count += r
            return r
        }
        override fun available(): Int = inp.available()
        override fun close() { inp.close() }
    }

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
