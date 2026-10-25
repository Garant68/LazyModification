package com.example.lazymodification.utils

import android.content.Context
import java.io.File

/**
 * Папка, с которой должен открываться файловый менеджер.
 * Хранится как обычный путь, потому что FileBrowserActivity работает с java.io.File.
 */
object WorkingFolderHelper {
    private const val PREFS = "working_folder_prefs"
    private const val KEY_PATH = "path"

    fun save(context: Context, folder: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PATH, folder.absolutePath)
            .apply()
    }

    fun getPath(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PATH, null)
    }

    fun getFolder(context: Context): File? {
        val path = getPath(context) ?: return null
        val folder = File(path)
        return if (folder.isDirectory && folder.canRead()) folder else null
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PATH)
            .apply()
    }
}
