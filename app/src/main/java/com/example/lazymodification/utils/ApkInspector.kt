package com.example.lazymodification.utils

import android.content.Context
import com.example.lazymodification.R
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.value.ResConfig
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Инспектор APK: читающий анализ (без модификации).
 * Показывает, «за что взяться»: тип приложения, реклама/аналитика, языки, размер, находки.
 */
object ApkInspector {

    private data class StringCounts(
        val total: Int,
        val russian: Int,
        val defaultIsRussian: Boolean
    )

    private val ADS = listOf(
        "applovin" to "AppLovin",
        "unity3d/ads" to "Unity Ads",
        "ironsource" to "ironSource",
        "vungle" to "Vungle",
        "chartboost" to "Chartboost",
        "mopub" to "MoPub",
        "adcolony" to "AdColony",
        "facebook/ads" to "Facebook Ads",
        "gms/ads" to "AdMob",
        "doubleclick" to "DoubleClick",
        "smaato" to "Smaato",
        "fyber" to "Fyber",
        "inmobi" to "InMobi",
        "startapp" to "StartApp",
        "tapjoy" to "Tapjoy",
        "mintegral" to "Mintegral",
        "bytedance" to "Pangle",
        "openadsdk" to "Pangle",
        "pangle" to "Pangle",
        "admost" to "AdMost"
    )

    private val ANALYTICS = listOf(
        "firebase/analytics" to "Firebase Analytics",
        "firebase/crashlytics" to "Crashlytics",
        "crashlytics" to "Crashlytics",
        "appsflyer" to "AppsFlyer",
        "adjust/sdk" to "Adjust",
        "amplitude" to "Amplitude",
        "mixpanel" to "Mixpanel",
        "sentry" to "Sentry",
        "flurry" to "Flurry",
        "metrica" to "Yandex Metrica",
        "my/tracker" to "myTracker",
        "facebook/appevents" to "Facebook Analytics",
        "bugsnag" to "Bugsnag",
        "newrelic" to "New Relic"
    )

    private val MODULES_ZIP = listOf(
        "libhermes" to "Hermes",
        "libreactnativejni" to "React Native",
        "libjsc" to "React Native (JSC)",
        "assets/index.android.bundle" to "React Native",
        "libunity" to "Unity",
        "libil2cpp" to "Unity IL2CPP",
        "libgodot" to "Godot",
        "libue4" to "Unreal Engine",
        "libunreal" to "Unreal Engine",
        "libmonodroid" to "Xamarin/.NET",
        "libmonosgen" to "Xamarin/.NET",
        "libnativescript" to "NativeScript",
        "libcocos" to "Cocos2d",
        "libdmengine" to "Defold",
        "libcorona" to "Solar2D",
        "libairapp" to "Adobe AIR",
        "capacitor.config" to "Capacitor",
        "assets/capacitor" to "Capacitor",
        "assets/expo" to "Expo"
    )

    private val MODULES_DEX = listOf(
        "com/facebook/hermes" to "Hermes",
        "com/facebook/react" to "React Native",
        "host/exp/exponent" to "Expo",
        "expo/modules" to "Expo",
        "com/unity3d/player" to "Unity",
        "mono/android" to "Xamarin/.NET",
        "com/getcapacitor" to "Capacitor",
        "org/apache/cordova" to "Cordova"
    )

    private val PROTECTIONS_ZIP = listOf(
        "libjiagu" to "360 Jiagu",
        "libsecexe" to "Bangcle",
        "libshella" to "Bangcle/Tencent",
        "libmobisec" to "Alibaba Security",
        "libdexprotector" to "DexProtector",
        "libnagain" to "Nagain",
        "libpromon" to "Promon SHIELD",
        "libappsealing" to "AppSealing",
        "libliapp" to "LIAPP",
        "libarxan" to "Arxan",
        "libvbox" to "Virbox",
        "libsgmain" to "Alibaba Security",
        "libsgsecuritybody" to "Alibaba Security",
        "libdexhelper" to "DexHelper",
        "libprotectclass" to "DexProtector",
        "libpairip" to "Play Integrity"
    )

    private val PROTECTIONS_DEX = listOf(
        "com/guardsquare/dexguard" to "DexGuard",
        "dexguard" to "DexGuard",
        "com/dexprotector" to "DexProtector",
        "com/secneo/apkwrapper" to "Bangcle",
        "com/qihoo" to "360 Jiagu",
        "com/tencent/stubshell" to "Tencent Legu",
        "com/tencent/legu" to "Tencent Legu",
        "com/google/android/play/core/integrity" to "Play Integrity",
        "com/google/android/gms/safetynet" to "SafetyNet",
        "safetynet" to "SafetyNet",
        "com/scottyab/rootbeer" to "RootBeer",
        "de/robv/android/xposed" to "Xposed",
        "frida" to "Frida",
        "substrate" to "Substrate",
        "tracerpid" to "Anti-debug (TracerPid)",
        "ptrace" to "Anti-debug (ptrace)",
        "magisk" to "Root detection (Magisk)",
        "superuser" to "Root detection",
        "certificatepinner" to "Certificate pinning",
        "x509trustmanager" to "Custom TrustManager",
        "goldfish" to "Emulator detection",
        "ranchu" to "Emulator detection"
    )

    private val FIND_NEEDLES = listOf(
        "оценит", "оцените", "rate us", "rate app", "rate the app",
        "поделит", "share app", "premium", "лиценз", "licens", "поддержк", "feedback"
    )

    fun inspect(context: Context, apk: File): String {
        val sb = StringBuilder()

        // --- основное ---
        var label = apk.name
        var pkgName = "?"
        var version = "?"
        var minSdk = 0
        var targetSdk = 0
        try {
            val pi = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            if (pi != null) {
                pkgName = pi.packageName
                @Suppress("DEPRECATION")
                version = "${pi.versionName} (${pi.versionCode})"
                val ai = pi.applicationInfo
                if (ai != null) {
                    ai.sourceDir = apk.absolutePath
                    ai.publicSourceDir = apk.absolutePath
                    label = ai.loadLabel(context.packageManager).toString()
                }
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    minSdk = pi.applicationInfo?.minSdkVersion ?: 0
                    targetSdk = pi.applicationInfo?.targetSdkVersion ?: 0
                }
            }
        } catch (_: Exception) {
        }

        val sizeMb = "%.1f".format(apk.length() / 1024.0 / 1024.0)
        sb.append("📦 ").append(label).append(" (").append(pkgName).append(")\n")
        sb.append("📋 v").append(version).append(" • ").append(sizeMb).append(" МБ")
        if (minSdk > 0) sb.append(" • Android ").append(minSdk).append("–").append(targetSdk)
        sb.append("\n\n")

        // --- тип приложения + детекция SDK + размеры (zip-скан) ---
        var kind = ""
        var hasFlutter = false
        var hasCompose = false
        var hasMenuXml = false
        var hasMenuDex = false
        var hasGPlayUpdate = false
        var hasRuStoreUpdate = false
        val adsFound = LinkedHashSet<String>()
        val analyticsFound = LinkedHashSet<String>()
        val modulesFound = LinkedHashSet<String>()
        val protectionsFound = LinkedHashSet<String>()
        var dexBytes = 0L
        var resBytes = 0L
        var assetsBytes = 0L
        var libBytes = 0L
        var otherBytes = 0L
        var arscSize = 0L
        var stringCounts: StringCounts? = null

        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name
                    val lowerName = n.lowercase(Locale.US)
                    if (lowerName.startsWith("res/menu/") && lowerName.endsWith(".xml")) {
                        hasMenuXml = true
                    }
                    for ((marker, name) in MODULES_ZIP) {
                        if (lowerName.contains(marker)) modulesFound.add(name)
                    }
                    for ((marker, name) in PROTECTIONS_ZIP) {
                        if (lowerName.contains(marker)) protectionsFound.add(name)
                    }
                    when {
                        n.endsWith(".dex") -> {
                            dexBytes += e.size
                            if (e.size in 1..40_000_000L) {
                                val bytes = readAll(zip, e.name)
                                scanDexTypes(bytes) { type ->
                                    val lower = type.lowercase(Locale.US)
                                    if (!hasFlutter && lower.startsWith("lio/flutter/")) hasFlutter = true
                                    if (!hasCompose && lower.startsWith("landroidx/compose/")) hasCompose = true
                                    if (!hasMenuDex &&
                                        (lower == "landroid/view/menu;" || lower == "landroid/view/menuitem;")
                                    ) {
                                        hasMenuDex = true
                                    }
                                    if (!hasGPlayUpdate &&
                                        lower.contains("lcom/google/android/play/core/appupdate")
                                    ) {
                                        hasGPlayUpdate = true
                                    }
                                    if (!hasRuStoreUpdate &&
                                        lower.contains("lru/rustore/sdk/appupdate")
                                    ) {
                                        hasRuStoreUpdate = true
                                    }
                                    for ((marker, name) in ADS) {
                                        if (lower.contains(marker)) adsFound.add(name)
                                    }
                                    for ((marker, name) in ANALYTICS) {
                                        if (lower.contains(marker)) analyticsFound.add(name)
                                    }
                                    for ((marker, name) in MODULES_DEX) {
                                        if (lower.contains(marker)) modulesFound.add(name)
                                    }
                                    for ((marker, name) in PROTECTIONS_DEX) {
                                        if (lower.contains(marker)) protectionsFound.add(name)
                                    }
                                }
                            }
                        }
                        n == "resources.arsc" -> {
                            resBytes += e.size
                            arscSize = e.size
                        }
                        n.startsWith("res/") -> resBytes += e.size
                        n.startsWith("assets/") -> {
                            assetsBytes += e.size
                            if (n.startsWith("assets/flutter_assets/")) hasFlutter = true
                        }
                        n.startsWith("lib/") -> {
                            libBytes += e.size
                            if (n.endsWith("libflutter.so")) hasFlutter = true
                        }
                        else -> otherBytes += e.size
                    }
                }
            }
        } catch (_: Exception) {
        }

        kind = when {
            hasFlutter -> "flutter"
            hasCompose -> "compose"
            else -> "normal"
        }
        sb.append(
            when (kind) {
                "flutter" -> context.getString(R.string.insp_type_flutter)
                "compose" -> context.getString(R.string.insp_type_compose)
                else -> context.getString(R.string.insp_type_normal)
            }
        ).append("\n")

        sb.append("💰 ").append(context.getString(R.string.insp_ads, if (adsFound.isEmpty()) context.getString(R.string.insp_none) else adsFound.joinToString(", "))).append("\n")
        sb.append("📊 ").append(context.getString(R.string.insp_analytics, if (analyticsFound.isEmpty()) context.getString(R.string.insp_none) else analyticsFound.joinToString(", "))).append("\n")
        sb.append("📦 ").append(
            context.getString(
                R.string.insp_modules,
                if (modulesFound.isEmpty()) context.getString(R.string.insp_none_plural)
                else modulesFound.joinToString(", ")
            )
        ).append("\n")
        sb.append("🛡 ").append(
            context.getString(
                R.string.insp_protections,
                if (protectionsFound.isEmpty()) context.getString(R.string.insp_none_plural)
                else protectionsFound.joinToString(", ")
            )
        ).append("\n")
        if (!hasFlutter) {
            when {
                hasMenuXml && hasCompose -> sb.append(context.getString(R.string.insp_menu_compose_xml))
                hasMenuXml -> sb.append(context.getString(R.string.insp_menu_xml_available))
                hasMenuDex -> sb.append(context.getString(R.string.insp_menu_dex_unavailable))
                else -> sb.append(context.getString(R.string.insp_menu_unknown))
            }
            sb.append("\n")
        }
        if (arscSize in 1L..16L * 1024 * 1024) {
            stringCounts = countStringLocales(apk)
            val counts = stringCounts
            if (counts != null) {
                sb.append(
                    context.getString(R.string.insp_string_counts, counts.total, counts.russian)
                ).append("\n")
                val missing = counts.total - counts.russian
                if (counts.total > 0 &&
                    (counts.russian == 0 ||
                        (missing >= 20 && counts.russian < counts.total * 0.9))
                ) {
                    sb.append(
                        "⚠ " + context.getString(R.string.insp_translation_needed, missing)
                    ).append("\n")
                }
            }
        } else if (arscSize > 16L * 1024 * 1024) {
            sb.append("⚠ ").append(context.getString(R.string.insp_string_counts_skipped)).append("\n")
        }
        val upd = mutableListOf<String>()
        if (hasGPlayUpdate) upd.add("Google Play")
        if (hasRuStoreUpdate) upd.add("RuStore")
        sb.append("🔔 ").append(context.getString(R.string.insp_update_check, if (upd.isEmpty()) context.getString(R.string.insp_none) else upd.joinToString(", "))).append("\n")

        // --- языки и находки (потоково, без тяжёлой таблицы ресурсов) ---
        val langs = sortedSetOf<String>()
        val found = LinkedHashSet<String>()
        try {
            for (q in DexPatcher.readLocalesFromApk(apk)) {
                val lang = qualifierToLanguage(q)
                if (lang != null) langs.add(lang)
            }
        } catch (_: Throwable) {
        }
        if (stringCounts?.defaultIsRussian == true) langs.add("ru")
        try {
            found.addAll(DexPatcher.readDefaultStringValues(apk, FIND_NEEDLES, 8))
        } catch (_: Throwable) {
        }
        if (langs.isNotEmpty()) {
            val preview = langs.take(8).joinToString(", ")
            sb.append("🌍 ").append(context.getString(R.string.insp_locales, langs.size, preview)).append("\n")
        }
        sb.append(if (langs.contains("ru")) context.getString(R.string.insp_ru_yes) else context.getString(R.string.insp_ru_no)).append("\n")

        val total = (dexBytes + resBytes + assetsBytes + libBytes + otherBytes).coerceAtLeast(1L)
        fun pct(v: Long) = (v * 100 / total).toInt()
        sb.append("💾 ").append(sizeMb).append(" МБ — dex ").append(pct(dexBytes)).append("%, res ").append(pct(resBytes))
            .append("%, assets ").append(pct(assetsBytes)).append("%, libs ").append(pct(libBytes)).append("%, other ").append(pct(otherBytes)).append("%\n")

        sb.append("\n")
        if (found.isEmpty()) {
            sb.append("🎯 ").append(context.getString(R.string.insp_found_none))
        } else {
            sb.append("🎯 ").append(context.getString(R.string.insp_found, found.joinToString(", ")))
        }
        return sb.toString()
    }

    /**
     * Counts string resources in values/ and values-ru/. The ARSC table is only
     * loaded for reasonably small APKs: very large resource tables can exhaust
     * the app heap and are not worth an inspector query.
     */
    private fun countStringLocales(apk: File): StringCounts? {
        return try {
            val module = ApkModule.loadApkFile(apk)
            try {
                val defaultConfig = ResConfig.getDefault()
                val ruConfig = ResConfig.parse("ru")
                var baseCount = 0
                var ruCount = 0
                var defaultSamples = 0
                var defaultCyrillic = 0
                for (pkg in module.tableBlock.listPackages()) {
                    val strings = pkg.getResources("string")
                    while (strings.hasNext()) {
                        val entry = strings.next()
                        val base = entry.get(defaultConfig) ?: entry.get()
                        if (base != null && !base.isNull() && base.getValueAsString() != null) {
                            baseCount++
                            val text = base.getValueAsString() ?: ""
                            if (text.length >= 2) {
                                defaultSamples++
                                if (hasCyrillic(text)) defaultCyrillic++
                            }
                        }
                        val ru = entry.get(ruConfig)
                        if (ru != null && !ru.isNull() && ru.getValueAsString() != null) {
                            ruCount++
                        }
                    }
                }
                val defaultIsRussian = defaultSamples >= 10 &&
                    defaultCyrillic * 100 / defaultSamples >= 50
                StringCounts(
                    total = baseCount,
                    russian = if (defaultIsRussian) baseCount else ruCount,
                    defaultIsRussian = defaultIsRussian
                )
            } finally {
                module.close()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readAll(zip: ZipFile, name: String): ByteArray {
        return zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    }

    /**
     * Reads actual DEX type descriptors, not raw bytes. This avoids matching
     * marker strings that an app (including this inspector) embeds as data.
     */
    private fun scanDexTypes(bytes: ByteArray, onType: (String) -> Unit) {
        try {
            val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
            for (type in dex.typeSection) {
                onType(type)
            }
        } catch (_: Throwable) {
        }
    }

    private fun hasCyrillic(value: String): Boolean {
        for (ch in value) {
            if (ch in '\u0400'..'\u04FF') return true
        }
        return false
    }

    private fun qualifierToLanguage(qualifier: String): String? {
        val clean = qualifier.trimStart('-')
        if (clean.startsWith("b+")) {
            val parts = clean.split('+')
            return if (parts.size >= 2 && parts[1].length in 2..3) parts[1] else null
        }
        val first = clean.substringBefore('-')
        return if (first.length in 2..3) first else null
    }}
