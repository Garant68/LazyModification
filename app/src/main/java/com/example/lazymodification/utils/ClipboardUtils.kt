package com.example.lazymodification.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

@Suppress("DEPRECATION")
fun Context.copyText(label: String, text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}
