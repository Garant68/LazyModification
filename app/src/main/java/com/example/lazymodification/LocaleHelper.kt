package com.example.lazymodification

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleHelper {
    private const val LANG_EN = "en"
    private const val LANG_RU = "ru"
    private const val LANG_BG = "bg"

    fun currentLang(): String {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tags.contains(LANG_EN) -> LANG_EN
            tags.contains(LANG_BG) -> LANG_BG
            else -> LANG_RU
        }
    }

    fun setLang(lang: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
    }
}