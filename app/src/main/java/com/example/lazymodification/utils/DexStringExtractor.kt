package com.example.lazymodification.utils

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.value.StringEncodedValue
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
    private val TECHNICAL_WORDS = Regex(
        "(?i)\\b(address|hashcode|bytecode|classpath|keystore|predicate|" +
            "iterator|adapter|fragment|activity|viewholder|manifest|resource|" +
            "parser|grammar|syntax|encoding|decoder|callback|parameter|" +
            "argument|exception|stacktrace|dex|apk|json|xml|http|https|url|" +
            "uri|stream|buffer|socket|certificate|reflection|library|" +
            "collection|sequence|overflow|compress|signature|subtree|framework)\\b"
    )
    private const val TECHNICAL_CHARS = "\\/{}<>;$=@#|~`[]()_:"
    private val UI_CLASS_SUFFIXES = listOf(
        "Activity;", "Fragment;", "Dialog;", "Adapter;", "ViewHolder;",
        "View;", "Screen;", "Page;", "Widget;", "Button;", "Menu;",
        "Item;", "Toast;", "Notification;", "Sheet;", "Panel;",
        "Tab;", "Card;", "Cell;", "Holder;", "ViewModel;", "Application;"
    )

    fun collect(
        apk: File,
        exclude: Set<String>,
        packageName: String? = null
    ): List<String> {
        val result = LinkedHashSet<String>()
        val packagePrefix = packageName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "L${it.replace('.', '/')}/" }
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
                    val candidates = LinkedHashSet<String>()
                    if (packagePrefix != null) {
                        var uiClasses = 0
                        for (clazz in dex.classes) {
                            if (!clazz.type.startsWith(packagePrefix)) continue
                            if (isUiClass(clazz)) {
                                uiClasses++
                                collectClassStrings(clazz, candidates)
                            }
                        }
                        if (uiClasses == 0) {
                            for (clazz in dex.classes) {
                                if (clazz.type.startsWith(packagePrefix)) {
                                    collectClassStrings(clazz, candidates)
                                }
                            }
                        }
                    } else {
                        for (reference in dex.stringReferences) {
                            candidates.add(reference.string)
                        }
                    }
                    for (text in candidates) {
                        if (text !in exclude && isTranslatable(text)) result.add(text)
                    }
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return result.toList()
    }

    private fun collectClassStrings(clazz: ClassDef, out: MutableSet<String>) {
        for (field in clazz.fields) {
            val value = field.initialValue
            if (value is StringEncodedValue) out.add(value.value)
        }
        for (method in clazz.directMethods) collectMethodStrings(method, out)
        for (method in clazz.virtualMethods) collectMethodStrings(method, out)
    }

    private fun isUiClass(clazz: ClassDef): Boolean {
        if (UI_CLASS_SUFFIXES.any { clazz.type.endsWith(it) }) return true
        val type = clazz.type.lowercase()
        return type.contains("/ui/") ||
            type.contains("/screens/") ||
            type.contains("/screen/") ||
            type.contains("/dialogs/") ||
            type.contains("/widgets/")
    }

    private fun collectMethodStrings(method: Method, out: MutableSet<String>) {
        val implementation = method.implementation ?: return
        for (instruction in implementation.instructions) {
            if (instruction !is ReferenceInstruction) continue
            val reference = instruction.reference
            if (reference is StringReference) out.add(reference.string)
        }
    }

    private fun isTranslatable(value: String): Boolean {
        val text = value.trim()
        if (text.length !in 2..300) return false
        if (text.none { it.isLetter() }) return false
        if (!text.first().isLetter()) return false
        if (text.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        if (URL_LIKE.matches(text) || EMAIL_LIKE.containsMatchIn(text) || FILE_LIKE.matches(text)) return false
        if (text.contains("://") || text.contains("@")) return false
        if (text.startsWith("L") && text.endsWith(";") && text.contains('/')) return false
        if (text.any { it in TECHNICAL_CHARS }) return false
        if (text.contains('%')) return false
        if (text.contains("  ")) return false
        if (text.contains('.')) {
            val withoutTrailingDots = text.dropLastWhile { it == '.' }
            if (withoutTrailingDots.contains('.') || !text.endsWith(".")) return false
        }
        if (!TranslationRules.isTranslatable(null, text)) return false
        if (PLACEHOLDER_ONLY.matches(text)) return false
        if (TECHNICAL_WORDS.containsMatchIn(text)) return false
        if (hasMixedDigitWord(text)) return false

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
        } else {
            // Library diagnostics are usually lowercase sentences with
            // code-like identifiers. UI copy normally starts uppercase and
            // does not contain camelCase words.
            if (!text.first().isUpperCase()) return false
            if (text.none { it.isLowerCase() }) return false
            if (hasCamelCaseToken(text)) return false
        }
        return true
    }

    private fun hasCamelCaseToken(text: String): Boolean =
        text.split(Regex("\\s+")).any { token ->
            val letters = token.filter { it.isLetter() }
            letters.length > 2 &&
                letters.any { it.isLowerCase() } &&
                letters.drop(1).any { it.isUpperCase() }
        }

    private fun hasMixedDigitWord(text: String): Boolean =
        text.split(Regex("\\s+")).any { token ->
            token.any { it.isDigit() } && token.any { it.isLetter() }
        }
}
