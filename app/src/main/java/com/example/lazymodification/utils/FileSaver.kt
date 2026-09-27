package com.example.lazymodification.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

object FileSaver {

    fun saveApk(context: Context, file: File, fileName: String) {
        saveStream(context, file.inputStream(), fileName, "application/vnd.android.package-archive")
    }

    fun saveStream(context: Context, inputStream: InputStream, fileName: String, mimeType: String) {
        val folderUri = SaveFolderHelper.getUri(context)
        val folder = if (folderUri != null) DocumentFile.fromTreeUri(context, folderUri) else null
        if (folder != null && folder.canWrite()) {
            saveToFolder(context, folder, inputStream, fileName, mimeType)
        } else {
            saveToDownloads(context, inputStream, fileName, mimeType)
        }
    }

    private fun saveToFolder(
        context: Context,
        folder: DocumentFile,
        inputStream: InputStream,
        fileName: String,
        mimeType: String
    ) {
        var target = folder.findFile(fileName)
        if (target == null || !target.isFile) {
            target = folder.createFile(mimeType, fileName)
        }
        val fileUri = target?.uri ?: throw IllegalStateException("Не удалось создать файл")
        context.contentResolver.openOutputStream(fileUri, "wt")?.use { out ->
            inputStream.use { it.copyTo(out) }
        } ?: throw IllegalStateException("Не удалось открыть файл")
    }

    private fun saveToDownloads(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        mimeType: String
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LazyModification")
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            inputStream.use { it.copyTo(out) }
        }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun saveDebugText(context: Context, text: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "clone_debug.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LazyModification")
                }
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (_: Exception) {
        }
    }
}
