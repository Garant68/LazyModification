package com.example.lazymodification.utils

import android.util.Log
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

object ApkUtils {

    fun extractAllDex(apkFile: File, outputDir: File): List<File> {
        // ⚡ Быстрый путь: прямое чтение только dex-записей из центрального каталога
        // (не распаковываем остальное содержимое ZIP и не сверяем CRC — как reandroid).
        try {
            return extractViaRaw(apkFile, outputDir)
        } catch (t: Throwable) {
            Log.w("ApkUtils", "raw extract: ${t.message}")
        }
        // Запасной путь: потоковое чтение ZipInputStream (сверяет CRC).
        return try {
            extractViaZipStream(apkFile, outputDir)
        } catch (e: ZipException) {
            extractViaReandroid(apkFile, outputDir)
        }
    }

    /** Прямое чтение dex-записей из центрального каталога ZIP: STORED копируется, DEFLATE распаковывается. */
    private fun extractViaRaw(apkFile: File, outputDir: File): List<File> {
        val dexFiles = mutableListOf<File>()
        RandomAccessFile(apkFile, "r").use { raf ->
            val fileLen = raf.length()
            val scanLen = minOf(fileLen, 65557L).toInt()
            val tail = ByteArray(scanLen)
            raf.seek(fileLen - scanLen)
            raf.readFully(tail)
            var eocd = -1
            for (i in scanLen - 4 downTo 0) {
                if (tail[i].toInt() == 0x50 && tail[i + 1].toInt() == 0x4B &&
                    tail[i + 2].toInt() == 0x05 && tail[i + 3].toInt() == 0x06
                ) { eocd = i; break }
            }
            if (eocd < 0) throw ZipException("EOCD не найден")
            val cnt = u16(tail, eocd + 10)
            val cdOff = u32(tail, eocd + 16)
            if (cnt == 0xFFFF || cdOff == 0xFFFFFFFFL) throw ZipException("ZIP64 не поддержан")
            raf.seek(cdOff)
            val hdr = ByteArray(46)
            for (k in 0 until cnt) {
                raf.readFully(hdr)
                if (u32(hdr, 0) != 0x02014b50L) throw ZipException("CD повреждён")
                val method = u16(hdr, 10)
                val compSize = u32(hdr, 20)
                val uncompSize = u32(hdr, 24)
                val nameLen = u16(hdr, 28)
                val extraLen = u16(hdr, 30)
                val commentLen = u16(hdr, 32)
                val localOff = u32(hdr, 42)
                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                if (extraLen > 0) raf.skipBytes(extraLen)
                if (commentLen > 0) raf.skipBytes(commentLen)
                val savePos = raf.filePointer
                val name = String(nameBytes, Charsets.US_ASCII)
                if (!name.matches(Regex("classes\\d*\\.dex"))) continue
                if (compSize > Int.MAX_VALUE || uncompSize > Int.MAX_VALUE) throw ZipException("слишком большой dex")
                raf.seek(localOff)
                val lfh = ByteArray(30)
                raf.readFully(lfh)
                if (u32(lfh, 0) != 0x04034b50L) throw ZipException("LFH повреждён: $name")
                val lNameLen = u16(lfh, 26)
                val lExtraLen = u16(lfh, 28)
                raf.seek(localOff + 30L + lNameLen + lExtraLen)
                val comp = ByteArray(compSize.toInt())
                raf.readFully(comp)
                val data = if (method == 0) comp else inflateRaw(comp, uncompSize.toInt())
                val dexFile = File(outputDir, name)
                dexFile.parentFile?.mkdirs()
                dexFile.writeBytes(data)
                dexFiles.add(dexFile)
                raf.seek(savePos)
            }
        }
        if (dexFiles.isEmpty()) {
            throw RuntimeException("DEX файлы не найдены в APK")
        }
        return dexFiles
    }

    private fun inflateRaw(src: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(src)
        val out = ByteArray(expectedSize)
        var off = 0
        try {
            while (off < expectedSize && !inflater.finished()) {
                val n = inflater.inflate(out, off, expectedSize - off)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                off += n
            }
        } finally {
            inflater.end()
        }
        if (off != expectedSize) throw ZipException("inflate неполный")
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun extractViaZipStream(apkFile: File, outputDir: File): List<File> {
        val dexFiles = mutableListOf<File>()
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(FileInputStream(apkFile).buffered()).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val name = entry!!.name
                if (name.matches(Regex("classes\\d*\\.dex"))) {
                    val dexFile = File(outputDir, name)
                    dexFile.parentFile?.mkdirs()
                    FileOutputStream(dexFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    dexFiles.add(dexFile)
                }
                zis.closeEntry()
            }
        }
        if (dexFiles.isEmpty()) {
            throw RuntimeException("DEX файлы не найдены в APK")
        }
        return dexFiles
    }

    private fun extractViaReandroid(apkFile: File, outputDir: File): List<File> {
        val dexFiles = mutableListOf<File>()
        // reanimated (ApkModule) читает записи напрямую, не сверяя CRC, —
        // обходит "invalid entry CRC" на битых/криво перепакованных APK.
        val module = ApkModule.loadApkFile(apkFile)
        try {
            for (input in module.listInputSources()) {
                val name = input.alias ?: input.name
                if (name.matches(Regex("classes\\d*\\.dex"))) {
                    val dexFile = File(outputDir, name)
                    dexFile.parentFile?.mkdirs()
                    input.write(dexFile)
                    dexFiles.add(dexFile)
                }
            }
        } finally {
            module.close()
        }
        if (dexFiles.isEmpty()) {
            throw RuntimeException("DEX файлы не найдены в APK")
        }
        return dexFiles
    }

    // ============================================================
    // ✅ Перепаковка APK через ARSCLib (ApkModule) — надёжный способ
    // ============================================================
    fun repackApkWithMultipleDex(
        originalApk: File,
        dexFiles: HashMap<String, File>,
        outputApk: File
    ) {
        val apkModule = ApkModule.loadApkFile(originalApk)
        try {
            for ((dexName, dexFile) in dexFiles) {
                val dexData = dexFile.readBytes()
                // Может быть новый dex (например, движок удаления проверки подписи) —
                // тогда removeInputSource бросает/возвращает null, это нормально.
                try { apkModule.removeInputSource(dexName) } catch (_: Throwable) { }
                val inputSource = ByteInputSource(dexData, dexName)
                inputSource.setMethod(ZipEntry.STORED)
                apkModule.add(inputSource)
            }
            if (outputApk.exists()) outputApk.delete()
            apkModule.writeApk(outputApk)
        } finally {
            apkModule.close()
        }
    }

    // ============================================================
    // ✅ ZipAlign — через ARSCLib (writeApk выравнивает автоматически)
    // ============================================================
    fun zipAlign(inputApk: File, outputApk: File) {
        try {
            val apkModule = ApkModule.loadApkFile(inputApk)
            try {
                if (outputApk.exists()) outputApk.delete()
                apkModule.writeApk(outputApk)
            } finally {
                apkModule.close()
            }
        } catch (e: Exception) {
            Log.w("ApkUtils", "zipAlign failed, copying unaligned", e)
            if (inputApk != outputApk) inputApk.copyTo(outputApk, overwrite = true)
        }
    }
}