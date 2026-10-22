package com.example.lazymodification.utils.patches

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

object PatchFiles {
    fun displayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "patch.zip"
    } catch (_: Exception) { "patch.zip" }

    fun import(context: Context, uri: Uri): File {
        val folder = File(context.cacheDir, "imported_patches/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val name = displayName(context, uri).replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val file = File(folder, name)
            context.contentResolver.openInputStream(uri)?.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val n = source.read(buffer); if (n < 0) break
                        size += n; if (size > 256L * 1024 * 1024) throw PatchException("ZIP патча больше 256 МБ")
                        target.write(buffer, 0, n)
                    }
                }
            } ?: throw PatchException("Не удалось прочитать файл")
            return normalizeImported(file, folder)
        } catch (t: Throwable) { folder.deleteRecursively(); throw t }
    }

    /** Импорт уже скачанного файла (например, по URL). */
    fun importFile(context: Context, source: File, fileName: String = source.name): File {
        val folder = File(context.cacheDir, "imported_patches/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "patch.zip" }
            val file = File(folder, safeName)
            source.copyTo(file, overwrite = true)
            return normalizeImported(file, folder)
        } catch (t: Throwable) { folder.deleteRecursively(); throw t }
    }

    private fun normalizeImported(file: File, folder: File): File {
        val document = PatchFormat.read(file)
        if (file.extension.equals("txt", true)) {
            val zip = File(folder, file.nameWithoutExtension + ".zip")
            PatchFormat.writeZip(zip, document.text, emptyMap())
            file.delete()
            return zip
        }
        return file
    }

    fun extractPayloads(archive: File, directory: File) {
        directory.mkdirs()
        ZipFile(archive).use { zip ->
            var total = 0L
            zip.entries().asSequence().filter { !it.isDirectory && it.name != "patch.txt" }.forEach { entry ->
                val dest = PatchFormat.child(directory, entry.name)
                dest.parentFile?.mkdirs()
                val bytes = zip.getInputStream(entry).use { PatchFormat.readBounded(it, 128L * 1024 * 1024) }
                total += bytes.size
                if (total > 256L * 1024 * 1024) throw PatchException("Содержимое патча больше 256 МБ")
                dest.writeBytes(bytes)
            }
        }
    }
}
