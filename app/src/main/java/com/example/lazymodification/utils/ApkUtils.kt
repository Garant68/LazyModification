package com.example.lazymodification.utils

import android.util.Log
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

object ApkUtils {

    fun extractAllDex(apkFile: File, outputDir: File): List<File> {
        // Быстрый путь: потоковое чтение ZipInputStream (как раньше).
        // Минус: он сверяет CRC и падает на битом APK ("invalid entry CRC").
        // При ошибке CRC — запасной путь через reanimated, который CRC не проверяет.
        return try {
            extractViaZipStream(apkFile, outputDir)
        } catch (e: ZipException) {
            extractViaReandroid(apkFile, outputDir)
        }
    }

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
                apkModule.removeInputSource(dexName)
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