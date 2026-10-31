package com.example.lazymodification.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

object FileSaver {

    /** Патчи складываются в отдельную подпапку, чтобы не смешиваться с APK. */
    const val PATCH_DIR = "Download/LazyModification/Патч"

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

    /** Папка, куда складываются и откуда подхватываются готовые патчи. */
    fun patchDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "LazyModification/Патч"
    )

    /**
     * Сохраняет ZIP-патч: в выбранную папку, если она задана, иначе в Download/LazyModification/Патч.
     * Возвращает путь для показа пользователю.
     */
    fun savePatch(context: Context, file: File, fileName: String): String {
        // application/zip заставляет MediaStore добавить .zip к имени .lmpatch.
        val mime = if (fileName.endsWith(".lmpatch", ignoreCase = true)) {
            "application/octet-stream"
        } else {
            "application/zip"
        }
        // Для собственного формата сохраняем точное имя .lmpatch, без
        // переименования MediaStore в .lmpatch.zip.
        if (fileName.endsWith(".lmpatch", ignoreCase = true) && SaveFolderHelper.getUri(context) == null) {
            try {
                val dir = patchDirectory()
                if (dir.isDirectory || dir.mkdirs()) {
                    file.inputStream().use { input ->
                        File(dir, fileName).outputStream().use { output -> input.copyTo(output) }
                    }
                    return "$PATCH_DIR/$fileName"
                }
            } catch (_: Throwable) {
                // fallback на MediaStore ниже
            }
        }
        val folderUri = SaveFolderHelper.getUri(context)
        val folder = if (folderUri != null) DocumentFile.fromTreeUri(context, folderUri) else null
        if (folder != null && folder.canWrite()) {
            file.inputStream().use { saveToFolder(context, folder, it, fileName, mime) }
            return (SaveFolderHelper.getDisplayName(context) ?: PATCH_DIR) + "/" + fileName
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, PATCH_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Не удалось создать файл патча")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            context.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            )
            return "$PATCH_DIR/$fileName"
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LazyModification/Патч"
        )
        if (!dir.isDirectory) dir.mkdirs()
        val target = File(dir, fileName)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return "Download/LazyModification/Патч/$fileName"
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
