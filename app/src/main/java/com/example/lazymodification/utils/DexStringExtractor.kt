package com.example.lazymodification.utils

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.util.zip.ZipFile

/**
 * Collects user-facing candidates from the DEX string pools. DEX contains
 * thousands of technical strings (types, method names, URLs, keys), so the
 * extraction is deliberately conservative: only text-like values whose
 * translation cannot change executable identifiers are offered.
 */
object DexStringExtractor {

    private val DEX_ENTRY = Regex("^classes\\d*\\.dex$")
    private val URL_LIKE = Regex("(?i)^(https?://|www\\.).*")
    private val EMAIL_LIKE = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val FILE_LIKE = Regex(
        "(?i).*\\.(png|jpe?g|gif|webp|xml|json|txt|html?|css|js|so|dex|zip|apk|mp[34]|wav|ogg|ttf|otf)(\\?.*)?$"
    )
    private val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(?:[./$][A-Za-z0-9_$]+)*;?$")
    private val LOWER_IDENTIFIER = Regex("^[a-z][a-z0-9_.-]+$")
    private val PLACEHOLDER_ONLY = Regex("""^(%[0-9]+\$[a-zA-Z]|%[sd]|\{[0-9]+\})+$""")

    fun collect(apk: File, exclude: Set<String>): List<String> {
        val result = LinkedHashSet<String>()
        try {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (!DEX_ENTRY.matches(entry.name)) continue
                    val bytes = try {
                        zip.getInputStream(entry).use { it.readBytes() }
                    } catch (_: Throwable) {
                        continue
                    }
                    val dex = try {
                        DexBackedDexFile(Opcodes.getDefault(), bytes)
                    } catch (_: Throwable) {
                        continue
                    }
                    for (reference in dex.stringReferences) {
                        val text = reference.string
                        if (text !in exclude && isTranslatable(text)) result.add(text)
                    }
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return result.toList()
    }

    private fun isTranslatable(value: String): Boolean {
        val text = value.trim()
        if (text.length !in 2..300) return false
        if (text.none { it.isLetter() }) return false
        if (text.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        if (URL_LIKE.matches(text) || EMAIL_LIKE.containsMatchIn(text) || FILE_LIKE.matches(text)) return false
        if (text.contains("://") || text.contains("@")) return false
        if (text.startsWith("L") && text.endsWith(";") && text.contains('/')) return false
        if (text.any { it in "\\/{}<>;$=" }) return false
        if (!TranslationRules.isTranslatable(null, text)) return false
        if (PLACEHOLDER_ONLY.matches(text)) return false

        val hasSpace = text.any { it.isWhitespace() }
        if (!hasSpace) {
            if (text.length < 3) return false
            if (LOWER_IDENTIFIER.matches(text)) return false
            // camelCase and CamelCaseWithUppercaseInside are almost always
            // class/method names rather than UI labels.
            if (text.any { it.isUpperCase() } && !text.first().isUpperCase()) return false
            if (text.drop(1).any { it.isUpperCase() }) return false
            if (IDENTIFIER.matches(text)) return false
        } else if (IDENTIFIER.matches(text)) {
            return false
        }
        return true
    }
}
