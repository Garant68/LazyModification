package com.example.lazymodification

import android.content.Context
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

object ThemeHelper {
    private const val PREFS = "theme_prefs"
    private const val KEY_DARK = "is_dark"

    fun apply(context: Context) {
        val isDark = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun toggle(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean(KEY_DARK, false)
        prefs.edit().putBoolean(KEY_DARK, !isDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (!isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun isDark(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, false)

    fun updateIcon(btn: ImageButton, context: Context) {
        val iconRes = if (isDark(context)) R.drawable.ic_theme_dark else R.drawable.ic_theme_light
        btn.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
    }
}