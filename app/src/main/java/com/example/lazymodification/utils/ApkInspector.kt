package com.example.lazymodification.utils

import android.content.Context
import com.example.lazymodification.R
import com.reandroid.apk.ApkModule
import java.io.File
import java.util.zip.ZipFile

/**
 * Инспектор APK: читающий анализ (без модификации).
 * Показывает, «за что взяться»: тип приложения, реклама/аналитика, языки, размер, находки.
 */
object ApkInspector {

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
        var hasGPlayUpdate = false
        var hasRuStoreUpdate = false
        val adsFound = LinkedHashSet<String>()
        val analyticsFound = LinkedHashSet<String>()
        var dexBytes = 0L
        var resBytes = 0L
        var assetsBytes = 0L
        var libBytes = 0L
        var otherBytes = 0L

        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val n = e.name
                    when {
                        n.endsWith(".dex") -> {
                            dexBytes += e.size
                            if (e.size in 1..40_000_000L) {
                                val bytes = readAll(zip, e.name)
                                if (!hasCompose && containsBytes(bytes, "androidx/compose/")) hasCompose = true
                                if (!hasGPlayUpdate && containsBytes(bytes, "com/google/android/play/core/appupdate")) hasGPlayUpdate = true
                                if (!hasRuStoreUpdate && containsBytes(bytes, "ru/rustore/sdk/appupdate")) hasRuStoreUpdate = true
                                for ((marker, name) in ADS) if (containsBytes(bytes, marker)) adsFound.add(name)
                                for ((marker, name) in ANALYTICS) if (containsBytes(bytes, marker)) analyticsFound.add(name)
                            }
                        }
                        n.startsWith("res/") || n == "resources.arsc" -> resBytes += e.size
                        n.startsWith("assets/") -> {
                            assetsBytes += e.size
                            if (n.startsWith("assets/flutter_assets/")) hasFlutter = true
                        }
                        n.startsWith("lib/") -> { libBytes += e.size; if (n.endsWith("libflutter.so")) hasFlutter = true }
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

    private fun readAll(zip: ZipFile, name: String): ByteArray {
        return zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    }

    /** Поиск подстроки в байтах dex (маркеры — в нижнем регистре, как в путях классов). */
    private fun containsBytes(hay: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || hay.size < n.size) return false
        var i = 0
        val end = hay.size - n.size
        while (i <= end) {
            if (hay[i] == n[0]) {
                var j = 1
                while (j < n.size && hay[i + j] == n[j]) j++
                if (j == n.size) return true
            }
            i++
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
