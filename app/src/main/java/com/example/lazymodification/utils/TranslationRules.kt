package com.example.lazymodification.utils

/**
 * Values that must stay executable/technical and must not be sent to a
 * translation provider. Material Components, for example, stores easing
 * functions in string resources and parses them at runtime.
 */
object TranslationRules {
    private val TECHNICAL_PREFIXES = listOf(
        "cubic-bezier",
        "path(",
        "path (",
        "spring(",
        "spring ("
    )

    fun isTranslatable(name: String?, value: String): Boolean {
        if (value.isBlank()) return false
        if (!value.any { it.isLetter() }) return false
        if (value.length > 1500) return false
        if (value.contains("http://") || value.contains("https://")) return false
        if (value.contains("@")) return false
        val trimmed = value.trimStart()
        if (TECHNICAL_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }) return false
        val resourceName = name?.lowercase().orEmpty()
        if (resourceName.startsWith("material_motion_easing_") ||
            resourceName.startsWith("m3_sys_motion_easing_") ||
            resourceName.startsWith("motion_easing_")
        ) return false
        return true
    }
}
