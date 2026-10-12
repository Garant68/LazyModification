package com.example.lazymodification

import com.example.lazymodification.utils.SigningUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Проверки переноса APK Signing Block (данных оригинальной подписи) в режиме «Не подписывать».
 * Тест 2 использует реальные файлы локальной машины разработчика и скипается, если их нет.
 */
class SignatureDataTest {

    private fun writeU32LE(out: ByteArrayOutputStream, v: Long) {
        for (i in 0..3) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun writeU64LE(out: ByteArrayOutputStream, v: Long) {
        for (i in 0..7) out.write(((v ushr (8 * i)) and 0xFF).toInt())
    }

    private fun leU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun buildZip(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            val e1 = ZipEntry("a.txt")
            zos.putNextEntry(e1)
            zos.write("hello world".toByteArray())
            zos.closeEntry()
            val e2 = ZipEntry("dir/b.bin")
            zos.putNextEntry(e2)
            zos.write(ByteArray(2048) { (it and 0xFF).toByte() })
            zos.closeEntry()
        }
    }

    /** Вставляет тестовый блок вручную (своими байтами), с правкой EOCD.cdOffset. Возвращает байты блока. */
    private fun injectFakeBlockManually(zip: File): ByteArray {
        val bytes = zip.readBytes()
        var eocd = -1
        for (i in bytes.size - 22 downTo 0) {
            if (bytes[i].toInt() == 0x50 && bytes[i + 1].toInt() == 0x4B &&
                bytes[i + 2].toInt() == 0x05 && bytes[i + 3].toInt() == 0x06) {
                eocd = i
                break
            }
        }
        check(eocd >= 0) { "EOCD не найден" }
        val cdOld = leU32(bytes, eocd + 16).toInt()

        val payload = ByteArray(100) { (0x40 + (it % 0x40)).toByte() }
        val size = payload.size + 8 + 16
        val blk = ByteArrayOutputStream()
        writeU64LE(blk, size.toLong())
        blk.write(payload)
        writeU64LE(blk, size.toLong())
        blk.write("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
        val block = blk.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(bytes, 0, cdOld)
        out.write(block)
        out.write(bytes, cdOld, bytes.size - cdOld)
        val result = out.toByteArray()

        val newEocd = eocd + block.size
        val newCd = cdOld + block.size
        for (i in 0..3) result[newEocd + 16 + i] = ((newCd ushr (8 * i)) and 0xFF).toByte()
        zip.writeBytes(result)
        return block
    }

    @Test
    fun syntheticBlockRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lm_sigtest_" + System.nanoTime())
        dir.mkdirs()
        try {
            val zip1 = File(dir, "one.zip")
            buildZip(zip1)
            val block = injectFakeBlockManually(zip1)

            val extracted1 = SigningUtils.extractSigningBlock(zip1)
            assertNotNull("блок должен извлекаться", extracted1)
            assertArrayEquals(block, extracted1)

            val zip2 = File(dir, "two.zip")
            buildZip(zip2)
            val countBefore = ZipFile(zip2).use { it.size() }
            assertTrue("вставка блока", SigningUtils.insertSigningBlockIntoApk(zip2, block))

            val extracted2 = SigningUtils.extractSigningBlock(zip2)
            assertArrayEquals(block, extracted2)
            assertEquals(countBefore, ZipFile(zip2).use { it.size() })
            ZipFile(zip2).use { z ->
                assertNotNull(z.getEntry("a.txt"))
                assertNotNull(z.getEntry("dir/b.bin"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun realFileBlockTransfer() {
        val src = File("C:\\Users\\Andrey\\.openclaw-autoclaw\\agents\\auto-coder\\workspace\\.openclaw\\tmp\\notein\\base.apk")
        val tgt = File("C:\\Users\\Andrey\\.openclaw-autoclaw\\agents\\auto-coder\\workspace\\.openclaw\\tmp\\notein\\out_lazymod.apk")
        assumeTrue("реальные файлы недоступны - скип", src.exists() && tgt.exists())

        val block = SigningUtils.extractSigningBlock(src)
        assertNotNull("в base.apk должен найтись APK Signing Block", block)
        val b = block!!
        val sha = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        println("REAL TEST: block size=" + b.size + ", sha256=" + sha)

        val work = File(System.getProperty("java.io.tmpdir"), "lm_realtest_" + System.nanoTime() + ".apk")
        try {
            tgt.copyTo(work, overwrite = true)
            val lenBefore = work.length()
            val countBefore = ZipFile(work).use { it.size() }
            assertTrue("вставка блока в копию выхода", SigningUtils.insertSigningBlockIntoApk(work, b))
            val back = SigningUtils.extractSigningBlock(work)
            assertArrayEquals(b, back)
            assertEquals(lenBefore + b.size, work.length())
            assertEquals(countBefore, ZipFile(work).use { it.size() })
            println("REAL TEST OK: +" + (work.length() - lenBefore) + " байт, записей " + countBefore)
        } finally {
            work.delete()
        }
    }
}