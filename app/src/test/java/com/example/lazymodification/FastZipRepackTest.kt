package com.example.lazymodification

import com.example.lazymodification.utils.FastZipRepack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class FastZipRepackTest {

    @Test
    fun repackCopiesReplacesRemovesAndAligns() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fzr_${System.nanoTime()}")
        assertTrue(dir.mkdirs() || dir.isDirectory)
        try {
            val input = File(dir, "in.apk")
            ZipOutputStream(FileOutputStream(input)).use { zos ->
                fun add(name: String, data: ByteArray, stored: Boolean) {
                    val e = ZipEntry(name)
                    if (stored) {
                        val crc = CRC32()
                        crc.update(data)
                        e.method = ZipEntry.STORED
                        e.size = data.size.toLong()
                        e.compressedSize = data.size.toLong()
                        e.crc = crc.value
                    }
                    zos.putNextEntry(e)
                    zos.write(data)
                    zos.closeEntry()
                }
                add("AndroidManifest.xml", ByteArray(100) { it.toByte() }, false)
                add("classes.dex", ByteArray(5000) { (it * 7).toByte() }, true)
                add("lib/arm64-v8a/libnative.so", ByteArray(9000) { (it * 13).toByte() }, true)
                add("kotlin/kotlin.kotlin_builtins", ByteArray(50) { 1 }, false)
                add("assets/foo.txt", ByteArray(300) { 2 }, false)
            }

            val newDex = ByteArray(6000) { (it * 3).toByte() }
            val newDexFile = File(dir, "classes_new.dex")
            newDexFile.writeBytes(newDex)

            val out = File(dir, "out.apk")
            val counts = FastZipRepack.repack(input, mapOf("classes.dex" to newDexFile), out, applyClean = true)
            assertEquals(4, counts.kept)
            assertEquals(1, counts.removed)

            ZipFile(out).use { z ->
                val names = z.entries().toList().map { it.name }.sorted()
                assertEquals(
                    listOf("AndroidManifest.xml", "assets/foo.txt", "classes.dex", "lib/arm64-v8a/libnative.so"),
                    names
                )
                val dex = z.getInputStream(z.getEntry("classes.dex")!!).readBytes()
                assertArrayEquals(newDex, dex)
            }

            assertStoredDataAligned(out, "lib/arm64-v8a/libnative.so", 4096L)
            assertStoredDataAligned(out, "classes.dex", 4L)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertStoredDataAligned(zip: File, entryName: String, align: Long) {
        RandomAccessFile(zip, "r").use { raf ->
            val len = raf.length()
            val scan = minOf(len, 65557L).toInt()
            val tail = ByteArray(scan)
            raf.seek(len - scan)
            raf.readFully(tail)
            var eocd = -1
            for (i in scan - 4 downTo 0) {
                if (tail[i].toInt() == 0x50 && tail[i + 1].toInt() == 0x4B &&
                    tail[i + 2].toInt() == 0x05 && tail[i + 3].toInt() == 0x06
                ) {
                    eocd = i; break
                }
            }
            assertTrue("EOCD должен найтись", eocd >= 0)
            val cdCount = (tail[eocd + 10].toInt() and 0xFF) or ((tail[eocd + 11].toInt() and 0xFF) shl 8)
            val cdOff = (tail[eocd + 16].toLong() and 0xFF) or ((tail[eocd + 17].toLong() and 0xFF) shl 8) or
                    ((tail[eocd + 18].toLong() and 0xFF) shl 16) or ((tail[eocd + 19].toLong() and 0xFF) shl 24)
            raf.seek(cdOff)
            repeat(cdCount) {
                val hdr = ByteArray(46)
                raf.readFully(hdr)
                val nameLen = (hdr[28].toInt() and 0xFF) or ((hdr[29].toInt() and 0xFF) shl 8)
                val extraLen = (hdr[30].toInt() and 0xFF) or ((hdr[31].toInt() and 0xFF) shl 8)
                val commentLen = (hdr[32].toInt() and 0xFF) or ((hdr[33].toInt() and 0xFF) shl 8)
                val localOff = (hdr[42].toLong() and 0xFF) or ((hdr[43].toLong() and 0xFF) shl 8) or
                        ((hdr[44].toLong() and 0xFF) shl 16) or ((hdr[45].toLong() and 0xFF) shl 24)
                val nameB = ByteArray(nameLen)
                raf.readFully(nameB)
                val name = String(nameB, Charsets.UTF_8)
                if (extraLen > 0) raf.skipBytes(extraLen)
                if (commentLen > 0) raf.skipBytes(commentLen)
                if (name == entryName) {
                    raf.seek(localOff)
                    val lfh = ByteArray(30)
                    raf.readFully(lfh)
                    val lNameLen = (lfh[26].toInt() and 0xFF) or ((lfh[27].toInt() and 0xFF) shl 8)
                    val lExtraLen = (lfh[28].toInt() and 0xFF) or ((lfh[29].toInt() and 0xFF) shl 8)
                    val dataOff = localOff + 30 + lNameLen + lExtraLen
                    assertEquals("смещение данных % $align", 0L, dataOff % align)
                    return
                }
            }
            fail("запись $entryName не найдена")
        }
    }
}