package com.example.lazymodification.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object SaveFolderHelper {
    private const val PREFS = "save_folder_prefs"
    private const val KEY_URI = "folder_uri"

    fun saveUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URI, uri.toString()).apply()
    }

    fun getUri(context: Context): Uri? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null) ?: return null
        return try {
            Uri.parse(s)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_URI).apply()
    }

    fun getDisplayName(context: Context): String? {
        val uri = getUri(context) ?: return null
        return try {
            DocumentFile.fromTreeUri(context, uri)?.name
        } catch (e: Exception) {
            null
        }
    }

    fun getLocationLabel(context: Context): String {
        return getDisplayName(context) ?: "Download/LazyModification"
    }
}
