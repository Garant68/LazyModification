package com.example.lazymodification.utils

import android.util.Log
import com.reandroid.apk.ApkModule
import java.io.File
import java.util.zip.ZipFile

object DpiRemove {

    private const val TAG = "DpiRemove"

    fun readDpisFromApk(apkFile: File): List<String> {
        val apkModule = ApkModule.loadApkFile(apkFile)
        val dpisSet = linkedSetOf<String>()
        val dpiOptions = listOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "tvdpi", "anydpi")

        try {
            for (inputSource in apkModule.listInputSources()) {
                val name = inputSource.alias ?: inputSource.name
                if (name.startsWith("res/drawable-")) {
                    val parts = name.split("/")
                    if (parts.size >= 2) {
                        val folder = parts[1]
                        val folderParts = folder.split("-")
                        for (part in folderParts) {
                            if (part in dpiOptions) {
                                dpisSet.add("-$part")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения DPI: ${e.message}", e)
        } finally {
            apkModule.close()
        }

        return dpisSet.toList()
    }

    /**
     * ✅ META-INF больше НЕ удаляется (сохраняется оригинальная подпись).
     */
    fun removeDpisFromApk(inputApk: File, outputApk: File, dpisToRemove: List<String>): Int {
        val apkModule = ApkModule.loadApkFile(inputApk)
        var removedCount = 0

        try {
            val entriesToRemove = mutableListOf<String>()

            ZipFile(inputApk).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    if (name.startsWith("res/drawable-")) {
                        val parts = name.split("/")
                        if (parts.size >= 2) {
                            val folder = parts[1]
                            val folderParts = folder.split("-")
                            val shouldDelete = dpisToRemove.any { dpi ->
                                folderParts.contains(dpi.substring(1))
                            }
                            if (shouldDelete) {
                                entriesToRemove.add(name)
                            }
                        }
                    }
                    // ✅ УБРАНО: META-INF не удаляется
                }
            }

            val zipEntryMap = apkModule.getZipEntryMap()
            for (name in entriesToRemove) {
                zipEntryMap.remove(name)
                removedCount++
            }

            apkModule.writeApk(outputApk)
            Log.d(TAG, "Удалено $removedCount файлов (DPI: ${dpisToRemove.joinToString()})")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка удаления DPI: ${e.message}", e)
            throw e
        } finally {
            apkModule.close()
        }

        return removedCount
    }
}