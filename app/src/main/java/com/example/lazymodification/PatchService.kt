package com.example.lazymodification

import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.FileSaver
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.DexPatcher
import com.example.lazymodification.utils.StubEdit
import com.example.lazymodification.utils.CodeEdit
import com.example.lazymodification.utils.PatchResult
import com.example.lazymodification.utils.SignatureKiller
import com.example.lazymodification.utils.DpiRemove
import com.example.lazymodification.utils.FastZipRepack
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.PatchHistory
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.ZipOptimizer
import com.example.lazymodification.utils.patches.ApkPatchRunner
import com.example.lazymodification.utils.patches.PatchException
import com.reandroid.apk.ApkModule
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PatchService : Service() {

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_CUSTOM_PATCHES = "custom_patch_paths"
        const val EXTRA_PATCH_GOOGLE_PLAY = "patch_google_play"
        const val EXTRA_PATCH_REMOVE_ADS = "patch_remove_ads"
        const val EXTRA_PATCH_REMOVE_ANALYTICS = "patch_remove_analytics"
        const val EXTRA_PATCH_REMOVE_GP_SERVICES = "patch_remove_gp_services"
        const val EXTRA_PATCH_REMOVE_VPN = "patch_remove_vpn"
        const val EXTRA_PATCH_ALLOW_SCREENSHOTS = "patch_allow_screenshots"
        const val EXTRA_STUB_EDITS = "stub_edits"
        const val EXTRA_CODE_EDITS = "code_edits"
        const val EXTRA_PATCH_REMOVE_INSTALLER_CHECK = "patch_remove_installer_check"
        const val EXTRA_PATCH_REMOVE_UPDATE = "patch_remove_update"
        const val EXTRA_PATCH_REMOVE_SIGNATURE = "patch_remove_signature"
        const val EXTRA_TOOLS_KILL_ONLY = "tools_kill_only"
        const val EXTRA_PATCH_REMOVE_LOCALES = "patch_remove_locales"
        const val EXTRA_PATCH_REMOVE_LOCALES_LIST = "patch_remove_locales_list"
        const val EXTRA_PATCH_REMOVE_DPI = "patch_remove_dpi"
        const val EXTRA_PATCH_REMOVE_DPI_LIST = "patch_remove_dpi_list"
        const val EXTRA_PATCH_REMOVE_LIBS = "patch_remove_libs"
        const val EXTRA_PATCH_REMOVE_LIBS_LIST = "patch_remove_libs_list"
        const val EXTRA_PATCH_OPTIMIZE = "patch_optimize"
        const val EXTRA_SIGN_V1 = "sign_v1"
        const val EXTRA_SIGN_V2 = "sign_v2"
        const val EXTRA_SIGN_V3 = "sign_v3"
        const val EXTRA_NO_SIGN = "no_sign"
        const val EXTRA_CUSTOM_KEYSTORE_PATH = "custom_keystore_path"
        const val EXTRA_KEYSTORE_PASSWORD = "keystore_password"
        const val EXTRA_KEY_ALIAS = "key_alias"
        const val EXTRA_KEY_PASSWORD = "key_password"
        const val EXTRA_KEYSTORE_TYPE = "keystore_type"

        const val ACTION_FLUSH_LOGS = "com.example.lazymodification.FLUSH_LOGS"
        const val ACTION_QUERY_STATE = "com.example.lazymodification.QUERY_STATE"
        const val CHANNEL_ID = "patch_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "PatchService"
        const val ACTION_PATCH_COMPLETE = "com.example.lazymodification.PATCH_COMPLETE"
        const val EXTRA_PATCHED_APK_PATH = "patched_apk_path"
        const val EXTRA_PATCHED_PACKAGE_NAME = "patched_package_name"

        private val logBuffer = mutableListOf<Pair<String, String>>()
    }

    private var wakeLock: PowerManager.WakeLock? = null

    // ===== Этапы точного прогресса =====
    private val stageNames = mutableListOf<String>()
    private val stageWeights = mutableListOf<Float>()
    private var stageIdx = -1
    private var stageBaseW = 0f
    private var stageTotalW = 1f
    private var lastOverall = -1
    private var runActive = false
    private var completedPath: String? = null
    private var completedPkg: String? = null
    private var lastBcP = 0
    private var lastBcI = 0
    private var lastBcN = 0
    private var lastBcName = ""
    private var lastBcDetail = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LogActivity.ACTION_CLEAR_LOGS) {
            synchronized(logBuffer) { logBuffer.clear() }
            val installCopy = File(cacheDir, "install_target.apk")
            if (installCopy.exists()) installCopy.delete()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_QUERY_STATE) {
            val cp = completedPath
            if (cp != null) {
                broadcastStage(100, 0, 0, "", "")
                sendPatchComplete(cp, completedPkg)
            } else if (runActive && lastBcN > 0) {
                broadcastStage(lastBcP, lastBcI, lastBcN, lastBcName, lastBcDetail)
            } else {
                broadcastStage(0, 0, 0, "", "")
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_FLUSH_LOGS) {
            synchronized(logBuffer) {
                logBuffer.forEach { (msg, type) ->
                    val i = Intent("PATCH_LOG").apply {
                        putExtra("LOG_MESSAGE", msg)
                        putExtra("LOG_TYPE", type)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(i)
                }
                logBuffer.clear()
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.patching_apk_progress)))

        val apkPath = intent?.getStringExtra(EXTRA_APK_PATH)
        val customPatches = intent?.getStringArrayListExtra(EXTRA_CUSTOM_PATCHES).orEmpty()
        val patchGooglePlay = intent?.getBooleanExtra(EXTRA_PATCH_GOOGLE_PLAY, false) ?: false
        val patchRemoveAds = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_ADS, false) ?: false
        val patchRemoveAnalytics = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_ANALYTICS, false) ?: false
        val patchRemoveGPServices = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_GP_SERVICES, false) ?: false
        val patchRemoveVpn = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_VPN, false) ?: false
        val patchAllowScreenshots = intent?.getBooleanExtra(EXTRA_PATCH_ALLOW_SCREENSHOTS, false) ?: false
        val stubEditsRaw = intent?.getStringArrayListExtra(EXTRA_STUB_EDITS)
        val stubEdits = ArrayList<StubEdit>()
        stubEditsRaw?.forEach { s ->
            try {
                val parts = s.split("|")
                if (parts.size >= 5) {
                    val params = if (parts[2].isEmpty()) emptyList<String>() else parts[2].split(",")
                    stubEdits.add(StubEdit(parts[0], parts[1], params, parts[3], parts[4]))
                }
            } catch (_: Throwable) { }
        }
        val codeEditsRaw = intent?.getStringArrayListExtra(EXTRA_CODE_EDITS)
        val codeEdits = ArrayList<CodeEdit>()
        codeEditsRaw?.forEach { s ->
            try {
                val parts = s.split("|")
                if (parts.size >= 6) {
                    val params = if (parts[2].isEmpty()) emptyList<String>() else parts[2].split(",")
                    val addr = parts[4].toIntOrNull() ?: return@forEach
                    val newText = String(Base64.decode(parts[5], Base64.NO_WRAP), Charsets.UTF_8)
                    codeEdits.add(CodeEdit(parts[0], parts[1], params, parts[3], addr, newText))
                }
            } catch (_: Throwable) { }
        }
        val patchRemoveInstallerCheck = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_INSTALLER_CHECK, false) ?: false
        val patchRemoveUpdate = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_UPDATE, false) ?: false
        val patchRemoveSignature = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_SIGNATURE, false) ?: false
        val toolsKillOnly = intent?.getBooleanExtra(EXTRA_TOOLS_KILL_ONLY, false) ?: false
        val patchRemoveLocales = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_LOCALES, false) ?: false
        val localesToRemove = intent?.getStringArrayListExtra(EXTRA_PATCH_REMOVE_LOCALES_LIST)
        val patchRemoveDpi = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_DPI, false) ?: false
        val dpisToRemove = intent?.getStringArrayListExtra(EXTRA_PATCH_REMOVE_DPI_LIST)
        val patchRemoveLibs = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_LIBS, false) ?: false
        val libsToRemove = intent?.getStringArrayListExtra(EXTRA_PATCH_REMOVE_LIBS_LIST)
        val patchOptimize = intent?.getBooleanExtra(EXTRA_PATCH_OPTIMIZE, false) ?: false
        val noSign = intent?.getBooleanExtra(EXTRA_NO_SIGN, false) ?: false
        val signV1 = intent?.getBooleanExtra(EXTRA_SIGN_V1, true) ?: true
        val signV2 = intent?.getBooleanExtra(EXTRA_SIGN_V2, true) ?: true
        val signV3 = intent?.getBooleanExtra(EXTRA_SIGN_V3, true) ?: true
        val customKeystorePath = intent?.getStringExtra(EXTRA_CUSTOM_KEYSTORE_PATH)
        val keystorePassword = intent?.getStringExtra(EXTRA_KEYSTORE_PASSWORD) ?: ""
        val keyAlias = intent?.getStringExtra(EXTRA_KEY_ALIAS) ?: ""
        val keyPassword = intent?.getStringExtra(EXTRA_KEY_PASSWORD) ?: ""
        val keystoreType = intent?.getStringExtra(EXTRA_KEYSTORE_TYPE) ?: "PKCS12"

        val signConfig = SignatureConfig(
            noSign = noSign, signV1 = signV1, signV2 = signV2, signV3 = signV3,
            customKeystorePath = customKeystorePath,
            keystorePassword = keystorePassword,
            keyAlias = keyAlias, keyPassword = keyPassword, keystoreType = keystoreType
        )

        if (apkPath == null) {
            sendLog(getString(R.string.apk_path_not_set), "ERROR")
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                acquireWakeLock()
                patchApk(
                    File(apkPath),
                    patchGooglePlay, patchRemoveAds, patchRemoveAnalytics,
                    patchRemoveGPServices, patchRemoveVpn, patchRemoveInstallerCheck,
                    patchRemoveUpdate, patchAllowScreenshots,
                    patchRemoveSignature, toolsKillOnly,
                    patchRemoveLocales, localesToRemove,
                    patchRemoveDpi, dpisToRemove,
                    patchRemoveLibs, libsToRemove,
                    patchOptimize,
                    stubEdits,
                    codeEdits,
                    signConfig,
                    customPatches
                )
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "❌ OutOfMemoryError", e)
                sendLog(getString(R.string.oom_file_too_large), "ERROR")
            } catch (e: Exception) {
                Log.e(TAG, getString(R.string.error), e)
                sendLog(getString(R.string.error_with_msg, e.message), "ERROR")
            } catch (e: Throwable) {
                Log.e(TAG, getString(R.string.error), e)
                sendLog(getString(R.string.error_with_msg, e.message), "ERROR")
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LazyModification::PatchWakeLock").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun sendProgress(progress: Int) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("PATCH_PROGRESS").apply { putExtra("PROGRESS_VALUE", progress.coerceIn(0, 100)) }
        )
    }

    private fun addStage(name: String, weight: Float) {
        stageNames.add(name)
        stageWeights.add(weight)
    }

    private fun stageBegin() {
        stageIdx++
        val n = stageNames.size
        val name = stageNames.getOrNull(stageIdx) ?: return
        sendLog("⏳ ${stageIdx + 1}/$n $name…", "INFO")
        stageSend(0f, "")
    }

    private fun stageTick(fraction: Float, detail: String = "") {
        stageSend(fraction, detail)
    }

    private fun stageSend(fraction: Float, detail: String) {
        val n = stageNames.size
        if (stageIdx < 0 || stageIdx >= n) return
        val w = stageWeights.getOrElse(stageIdx) { 1f }
        val done = stageBaseW + w * fraction.coerceIn(0f, 1f)
        val overall = if (stageTotalW > 0f) (done / stageTotalW * 100f).toInt().coerceIn(0, 100) else 0
        if (overall > lastOverall) lastOverall = overall
        broadcastStage(lastOverall, stageIdx + 1, n, stageNames[stageIdx], detail)
    }

    private fun stageDone(suffix: String = "") {
        val n = stageNames.size
        if (stageIdx in 0 until n) {
            sendLog("✅ ${stageIdx + 1}/$n ${stageNames[stageIdx]}$suffix", "SUCCESS")
            stageBaseW += stageWeights.getOrElse(stageIdx) { 0f }
        }
        val overall = if (stageTotalW > 0f) (stageBaseW / stageTotalW * 100f).toInt().coerceIn(0, 100) else 0
        if (overall > lastOverall) lastOverall = overall
        val idxShow = if (stageIdx >= 0) stageIdx + 1 else 0
        broadcastStage(lastOverall, idxShow, n, stageNames.getOrNull(stageIdx) ?: "", "")
    }

    private fun stageAbort(p: Int = 0) {
        broadcastStage(p, 0, 0, "", "")
    }

    private fun broadcastStage(p: Int, i: Int, n: Int, name: String, detail: String) {
        val pf = p.coerceIn(0, 100)
        val header = if (n > 0) "Этап $i из $n — $name" else ""
        lastBcP = pf
        lastBcI = i
        lastBcN = n
        lastBcName = name
        lastBcDetail = detail
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("PATCH_PROGRESS").apply {
                putExtra("PROGRESS_VALUE", pf)
                putExtra("STAGE_COUNT", n)
                putExtra("STAGE_TEXT", header)
                putExtra("STAGE_DETAIL", detail)
            }
        )
        try {
            val text = when {
                n > 0 && detail.isNotEmpty() -> "Патчинг: $i/$n · $name — $detail · $pf%"
                n > 0 -> "Патчинг: $i/$n · $name · $pf%"
                else -> "Патчинг: $pf%"
            }
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
        } catch (_: Throwable) { }
    }

    private fun getPackageNameFromApk(apkFile: File): String? {
        return try {
            val pi = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            pi?.packageName
        } catch (e: Exception) { null }
    }

    private fun sendPatchComplete(apkPath: String, packageName: String?) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_PATCH_COMPLETE).apply {
                putExtra(EXTRA_PATCHED_APK_PATH, apkPath)
                putExtra(EXTRA_PATCHED_PACKAGE_NAME, packageName)
            }
        )
    }

    private fun prepareForInstall(finalApk: File) {
        try {
            val installCopy = File(cacheDir, "install_target.apk")
            finalApk.copyTo(installCopy, overwrite = true)
            val pkgName = getPackageNameFromApk(installCopy)
            completedPath = installCopy.absolutePath
            completedPkg = pkgName
            sendPatchComplete(installCopy.absolutePath, pkgName)
            sendLog(getString(R.string.log_apk_prepared, pkgName ?: getString(R.string.unknown)), "INFO")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подготовки к установке: ${e.message}")
        }
    }

    private fun patchApk(
        apkFile: File,
        patchGooglePlay: Boolean, patchRemoveAds: Boolean, patchRemoveAnalytics: Boolean,
        patchRemoveGPServices: Boolean, patchRemoveVpn: Boolean, patchRemoveInstallerCheck: Boolean,
        patchRemoveUpdate: Boolean, patchAllowScreenshots: Boolean,
        patchRemoveSignature: Boolean, toolsKillOnly: Boolean,
        patchRemoveLocales: Boolean, localesToRemove: List<String>?,
        patchRemoveDpi: Boolean, dpisToRemove: List<String>?,
        patchRemoveLibs: Boolean, libsToRemove: List<String>?,
        patchOptimize: Boolean,
        stubEdits: List<StubEdit>,
        codeEdits: List<CodeEdit>,
        signConfig: SignatureConfig,
        customPatches: List<String>
    ) {
        sendProgress(0)
        val totalStart = System.currentTimeMillis()
        val sizeMb = apkFile.length() / (1024.0 * 1024.0)
        sendLog(getString(R.string.log_start, apkFile.name, String.format("%.1f", sizeMb)), "INFO")

        // Снимок исходника и запись запуска в историю — до любых изменений.
        try {
            val info = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28)
                info?.longVersionCode?.toInt() else info?.versionCode
            val parts = ArrayList<String>()
            if (patchGooglePlay) parts += "Google Play"
            if (patchRemoveAds) parts += "Реклама"
            if (patchRemoveAnalytics) parts += "Аналитика"
            if (patchRemoveGPServices) parts += "GP-сервисы"
            if (patchRemoveVpn) parts += "VPN"
            if (patchRemoveInstallerCheck) parts += "Проверка установщика"
            if (patchRemoveUpdate) parts += "Обновления"
            if (patchAllowScreenshots) parts += "Скриншоты"
            if (patchRemoveSignature) parts += "Подпись"
            if (patchRemoveLocales) parts += "Локали"
            if (patchRemoveDpi) parts += "DPI"
            if (patchRemoveLibs) parts += "Библиотеки"
            if (patchOptimize) parts += "UltraZip"
            customPatches.forEach { parts += "Патч: ${File(it).name}" }
            PatchHistory.record(
                this, apkFile, parts.joinToString(", ").ifBlank { "Патчинг" },
                info?.packageName, info?.versionName, versionCode
            )
        } catch (e: Exception) {
            Log.e(TAG, "history record failed", e)
        }
        sendLog("🔧 GP=$patchGooglePlay, Ads=$patchRemoveAds, Analytics=$patchRemoveAnalytics, GP_Svc=$patchRemoveGPServices, VPN=$patchRemoveVpn, Installer=$patchRemoveInstallerCheck, Locales=$patchRemoveLocales, DPI=$patchRemoveDpi, Libs=$patchRemoveLibs, Optimize=$patchOptimize, Update=$patchRemoveUpdate, Shots=$patchAllowScreenshots, Stubs=${stubEdits.size}, CodeEdits=${codeEdits.size}, Sign=$patchRemoveSignature, Debug=${if (toolsKillOnly) "off" else "auto"}", "INFO")

        if (patchRemoveLocales && !localesToRemove.isNullOrEmpty()) sendLog(getString(R.string.log_locales_selected, localesToRemove.size), "INFO")
        if (patchRemoveDpi && !dpisToRemove.isNullOrEmpty()) sendLog(getString(R.string.log_dpi_selected, dpisToRemove.joinToString()), "INFO")
        if (patchRemoveLibs && !libsToRemove.isNullOrEmpty()) sendLog(getString(R.string.log_archs_selected, libsToRemove.joinToString()), "INFO")

        val signDesc = if (signConfig.noSign) getString(R.string.saving_original_signature)
        else if (signConfig.useCustom) getString(R.string.log_custom_signature, signConfig.signV1, signConfig.signV2, signConfig.signV3)
        else "🔐 V1=${signConfig.signV1}, V2=${signConfig.signV2}, V3=${signConfig.signV3}"
        sendLog(getString(R.string.log_signature, signDesc), "INFO")

        // ===== План этапов (точный прогресс) =====
        val needsDexPatching = patchGooglePlay || patchRemoveAds || patchRemoveAnalytics ||
                patchRemoveGPServices || patchRemoveVpn || patchRemoveInstallerCheck || patchRemoveUpdate || patchRemoveSignature || patchOptimize || patchAllowScreenshots || stubEdits.isNotEmpty() || codeEdits.isNotEmpty()
        val hasTail = needsDexPatching || patchRemoveLocales || patchRemoveDpi || patchRemoveLibs || customPatches.isNotEmpty()
        stageNames.clear()
        stageWeights.clear()
        addStage("Копирование APK", 3f)
        customPatches.forEach { addStage(getString(R.string.custom_patch_stage, File(it).name), 30f) }
        if (patchRemoveAnalytics) addStage("Патч манифеста", 3f)
        if (patchRemoveLocales) addStage("Удаление локалей", 3f)
        if (patchRemoveDpi) addStage("Удаление DPI", 3f)
        if (patchRemoveLibs) addStage("Удаление библиотек", 3f)
        if (needsDexPatching) addStage("Обработка dex", 50f)
        if (needsDexPatching && patchRemoveSignature) addStage("Внедрение движка подписи", 4f)
        if (needsDexPatching) addStage("Пересборка APK", 12f)
        if (patchOptimize && !signConfig.noSign) addStage("UltraZip (Zopfli)", 5f)
        if (hasTail) addStage("ZipAlign", 2f)
        if (hasTail) addStage("Подпись", 4f)
        if (hasTail) addStage("Сохранение результата", 3f)
        stageTotalW = stageWeights.sum()
        stageIdx = -1
        stageBaseW = 0f
        lastOverall = -1
        runActive = true
        completedPath = null
        completedPkg = null

        val workDir = File(cacheDir, "patch_${System.currentTimeMillis()}")
        workDir.mkdirs()

        var anyApplied = false
        try {
            stageBegin()
            var workingApk = File(workDir, apkFile.name)
            run {
                val copyTotal = apkFile.length()
                var copied = 0L
                var lastCopyPct = -1
                FileInputStream(apkFile).use { ins ->
                    FileOutputStream(workingApk).use { outs ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            outs.write(buf, 0, r)
                            copied += r
                            val pct = if (copyTotal > 0) ((copied * 100L) / copyTotal).toInt() else 100
                            if (pct != lastCopyPct) {
                                lastCopyPct = pct
                                stageTick(copied.toFloat() / copyTotal.coerceAtLeast(1L), "")
                            }
                        }
                    }
                }
            }
            stageDone(" — " + String.format("%.1f", workingApk.length() / (1024.0 * 1024.0)) + " МБ")

            var customApplied = false
            customPatches.forEachIndexed { index, path ->
                stageBegin()
                val customOutput = File(workDir, "custom_$index.apk")
                val result = ApkPatchRunner.apply(this, workingApk, File(path), customOutput, File(workDir, "custom_$index"),
                    log = { sendLog(it, "INFO") }, progress = { fraction, detail -> stageTick(fraction, detail) })
                if (result.changed) {
                    customOutput.copyTo(workingApk, overwrite = true)
                    customApplied = true; anyApplied = true
                    stageDone(getString(R.string.custom_patch_result, result.matches, result.changedPaths.size))
                } else {
                    sendLog(getString(R.string.custom_patch_no_changes, File(path).name), "WARNING")
                    stageDone()
                }
                customOutput.delete()
                File(workDir, "custom_$index").deleteRecursively()
            }
            if (customPatches.isNotEmpty() && !customApplied && !needsDexPatching && !patchRemoveLocales && !patchRemoveDpi && !patchRemoveLibs)
                throw PatchException(getString(R.string.custom_patch_nothing_applied))

            if (patchRemoveAnalytics) {
                stageBegin()
                val manifestPatchedApk = File(workDir, "manifest_patched.apk")
                try {
                    val manifestCount = DexPatcher.patchManifest(workingApk, manifestPatchedApk)
                    if (manifestCount > 0) {
                        manifestPatchedApk.copyTo(workingApk, overwrite = true)
                        stageDone(" — правок: $manifestCount")
                        anyApplied = true
                    } else {
                        sendLog(getString(R.string.manifest_nothing_found), "WARNING")
                        stageDone()
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_manifest_error, e.message), "WARNING")
                    stageDone()
                } finally {
                    manifestPatchedApk.delete()
                }
            }

            if (patchRemoveLocales) {
                stageBegin()
                val noLocalesApk = File(workDir, "no_locales.apk")
                try {
                    val removedCount = DexPatcher.removeLocalesFromApk(workingApk, noLocalesApk, localesToRemove)
                    if (removedCount > 0) {
                        noLocalesApk.copyTo(workingApk, overwrite = true)
                        stageDone(" — удалено: $removedCount")
                        anyApplied = true
                    } else {
                        sendLog(getString(R.string.locales_not_found), "WARNING")
                        stageDone()
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_locales_error, e.message), "WARNING")
                    stageDone()
                } finally {
                    noLocalesApk.delete()
                }
            }

            if (patchRemoveDpi) {
                stageBegin()
                val noDpiApk = File(workDir, "no_dpi.apk")
                try {
                    if (dpisToRemove.isNullOrEmpty()) {
                        sendLog(getString(R.string.dpi_not_selected), "WARNING")
                        stageDone()
                    } else {
                        val removedCount = DpiRemove.removeDpisFromApk(workingApk, noDpiApk, dpisToRemove)
                        if (removedCount > 0) {
                            noDpiApk.copyTo(workingApk, overwrite = true)
                            stageDone(" — удалено: $removedCount")
                            anyApplied = true
                        } else {
                            sendLog(getString(R.string.dpi_files_not_found), "WARNING")
                            stageDone()
                        }
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_dpi_error, e.message), "WARNING")
                    stageDone()
                } finally {
                    noDpiApk.delete()
                }
            }

            if (patchRemoveLibs) {
                stageBegin()
                val noLibsApk = File(workDir, "no_libs.apk")
                try {
                    if (libsToRemove.isNullOrEmpty()) {
                        sendLog(getString(R.string.archs_not_selected), "WARNING")
                        stageDone()
                    } else {
                        val removedCount = removeArchLibs(workingApk, noLibsApk, libsToRemove)
                        if (removedCount > 0) {
                            noLibsApk.copyTo(workingApk, overwrite = true)
                            stageDone(" — удалено: $removedCount")
                            anyApplied = true
                        } else {
                            sendLog(getString(R.string.libs_not_found), "WARNING")
                            stageDone()
                        }
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_libs_error, e.message), "WARNING")
                    stageDone()
                } finally {
                    noLibsApk.delete()
                }
            }

            if (needsDexPatching) {
                stageBegin()
                sendLog(getString(R.string.status_extracting_dex), "INFO")
                val dexDir = File(workDir, "dex")
                dexDir.mkdirs()
                val dexFiles = ApkUtils.extractAllDex(workingApk, dexDir)
                sendLog(getString(R.string.log_dex_count, dexFiles.size), "SUCCESS")
                stageTick(0f, "файлов: ${dexFiles.size}")

                val patchedDexMap = HashMap<String, File>()
                var totalGP = 0; var totalAds = 0; var totalAnalytics = 0
                var totalGPServices = 0; var totalVpn = 0; var totalInstallerCheck = 0; var totalDebug = 0; var totalUpdate = 0; var totalScreenshots = 0; var totalStubs = 0; var totalCodeEdits = 0

                val dexCount = dexFiles.size

                for ((i, dex) in dexFiles.withIndex()) {
                    val dexStart = System.currentTimeMillis()
                    val dexSizeMb = String.format("%.1f", dex.length() / (1024.0 * 1024.0))
                    stageTick(i.toFloat() / dexCount.coerceAtLeast(1), "${dex.name} · файл ${i + 1}/$dexCount")
                    sendLog("🔧 ${dex.name} (${dexSizeMb} MB)...", "INFO")

                    val patchedDex = File(workDir, "patched_${dex.name}")
                    try {
                        var stubsThisDex = 0
                        var codeEditsThisDex = 0
                        val result = if (stubEdits.isEmpty() && codeEdits.isEmpty()) {
                            DexPatcher.patchDex(
                                inputDex = dex, outputDex = patchedDex,
                                patchGooglePlay = patchGooglePlay, patchRemoveAds = patchRemoveAds,
                                patchRemoveAnalytics = patchRemoveAnalytics, patchRemoveGPServices = patchRemoveGPServices,
                                patchRemoveVpn = patchRemoveVpn, patchRemoveInstallerCheck = patchRemoveInstallerCheck, patchRemoveDebug = !toolsKillOnly,
                                patchRemoveUpdate = patchRemoveUpdate,
                                patchAllowScreenshots = patchAllowScreenshots
                            )
                        } else {
                            var cur = dex
                            var tmp: File? = null
                            if (stubEdits.isNotEmpty()) {
                                val t1 = File(workDir, "stub1_${dex.name}")
                                stubsThisDex = DexPatcher.stubMethods(cur, t1, stubEdits)
                                totalStubs += stubsThisDex
                                cur = t1
                                tmp = t1
                            }
                            if (codeEdits.isNotEmpty()) {
                                val cnt = DexPatcher.applyCodeEdits(cur, patchedDex, codeEdits)
                                if (cnt < 0) {
                                    sendLog("⚠️ ${dex.name}: правки кода не прошли проверку — dex сохранён без правок", "WARNING")
                                } else {
                                    codeEditsThisDex = cnt
                                    totalCodeEdits += cnt
                                }
                                tmp?.delete()
                            } else {
                                cur.copyTo(patchedDex, overwrite = true)
                                tmp?.delete()
                            }
                            PatchResult()
                        }

                        totalGP += result.googlePlayPatched
                        totalAds += result.adsPatched
                        totalAnalytics += result.analyticsPatched
                        totalGPServices += result.gpServicesPatched
                        totalVpn += result.vpnPatched
                        totalInstallerCheck += result.installerCheckPatched
                        totalDebug += result.debugItemsRemoved
                        totalUpdate += result.updateCheckPatched
                        totalScreenshots += result.screenshotsPatched

                        patchedDexMap[dex.name] = patchedDex

                        val elapsed = System.currentTimeMillis() - dexStart
                        sendLog("  GP:${result.googlePlayPatched} Ads:${result.adsPatched} Analytics:${result.analyticsPatched} GP_Svc:${result.gpServicesPatched} VPN:${result.vpnPatched} Installer:${result.installerCheckPatched} Update:${result.updateCheckPatched} Shots:${result.screenshotsPatched} Debug:${result.debugItemsRemoved}" + (if (stubEdits.isNotEmpty()) " Stubs:$stubsThisDex" else "") + (if (codeEditsThisDex > 0) " Code:$codeEditsThisDex" else "") + " (${elapsed}ms)", "INFO")
                    } catch (e: OutOfMemoryError) {
                        sendLog(getString(R.string.log_oom_dex, dex.name, dexSizeMb), "ERROR")
                        throw e
                    } finally {
                        // ✅ Сразу удаляем исходный DEX и освобождаем память
                        dex.delete()
                        System.gc()
                    }

                    stageTick((i + 1f) / dexCount.coerceAtLeast(1), "")
                }

                if (totalGP + totalAds + totalAnalytics + totalGPServices + totalVpn + totalInstallerCheck + totalScreenshots > 0) anyApplied = true
                if (totalStubs > 0) anyApplied = true
                if (totalCodeEdits > 0) anyApplied = true
                if (patchGooglePlay) sendLog("✅ Google Play: $totalGP", if (totalGP > 0) "SUCCESS" else "WARNING")
                if (patchRemoveAds) sendLog(getString(R.string.log_ads, totalAds), if (totalAds > 0) "SUCCESS" else "WARNING")
                if (patchRemoveAnalytics) sendLog(getString(R.string.log_analytics, totalAnalytics), if (totalAnalytics > 0) "SUCCESS" else "WARNING")
                if (patchRemoveGPServices) sendLog(getString(R.string.log_gp_services, totalGPServices), if (totalGPServices > 0) "SUCCESS" else "WARNING")
                if (patchRemoveVpn) sendLog("✅ VPN Detection: $totalVpn", if (totalVpn > 0) "SUCCESS" else "WARNING")
                if (patchRemoveInstallerCheck) sendLog("✅ Installer check: $totalInstallerCheck", if (totalInstallerCheck > 0) "SUCCESS" else "WARNING")
                if (patchRemoveUpdate) sendLog(getString(R.string.log_update_check, totalUpdate), if (totalUpdate > 0) "SUCCESS" else "WARNING")
                if (patchAllowScreenshots) sendLog("✅ Скриншоты: $totalScreenshots", if (totalScreenshots > 0) "SUCCESS" else "WARNING")
                if (stubEdits.isNotEmpty()) sendLog(getString(R.string.stub_log_result, totalStubs, stubEdits.size - totalStubs), if (totalStubs > 0) "SUCCESS" else "WARNING")
                if (codeEdits.isNotEmpty()) sendLog(getString(R.string.replace_log_result, totalCodeEdits, codeEdits.size), if (totalCodeEdits > 0) "SUCCESS" else "WARNING")
                sendLog(getString(R.string.log_debug, totalDebug), if (totalDebug > 0) "SUCCESS" else "INFO")

                if (patchRemoveSignature) {
                    stageBegin()
                    val killResult = try {
                        SignatureKiller.inject(
                            context = this,
                            originalApk = apkFile,
                            dexMap = patchedDexMap,
                            workDir = workDir,
                            log = { m -> sendLog(m, "INFO") }
                        )
                    } catch (t: Throwable) {
                        SignatureKiller.Result(false, t.message ?: "?")
                    }
                    if (killResult.ok) {
                        anyApplied = true
                        stageDone()
                    } else {
                        sendLog(getString(R.string.log_signature_kill_fail, killResult.message), "ERROR")
                        stageAbort(0)
                        return
                    }
                }

                if (patchRemoveUpdate && totalUpdate == 0 && !anyApplied && !patchOptimize) {
                    sendLog(getString(R.string.log_update_not_found_norebuild), "WARNING")
                    stageAbort(0)
                    return
                }

                stageBegin()
                val repackedApk = File(workDir, "repacked.apk")
                val repackStart = System.currentTimeMillis()
                var fastPacked = false
                var cleanedApk = File(workDir, "cleaned.apk")
                try {
                    try {
                        // ⚡ Быстро: сырое копирование + замена DEX + очистка + выравнивание за один проход
                        var lastRpt = -1
                        val counts = FastZipRepack.repack(workingApk, patchedDexMap, cleanedApk, applyClean = true, onProgress = { dn, dt ->
                            val pct = if (dt > 0) dn * 100 / dt else 100
                            if (pct != lastRpt) {
                                lastRpt = pct
                                stageTick(if (dt > 0) dn.toFloat() / dt else 1f, "записей: $dn/$dt")
                            }
                        })
                        sendLog(getString(R.string.status_cleaning_kotlin), "INFO")
                        sendLog(getString(R.string.log_kept_removed, counts.kept, counts.removed), "SUCCESS")
                        fastPacked = true
                    } catch (t: Throwable) {
                        Log.w(TAG, "⚡ FastZipRepack: fallback на ARSCLib", t)
                        ApkUtils.repackApkWithMultipleDex(workingApk, patchedDexMap, repackedApk)
                        if (!repackedApk.exists() || repackedApk.length() == 0L) {
                            throw Exception("Repack failed")
                        }
                        sendLog(getString(R.string.log_repacked_mb, String.format("%.1f", repackedApk.length() / (1024.0 * 1024.0))), "SUCCESS")
                        sendLog(getString(R.string.status_cleaning_kotlin), "INFO")
                        try {
                            val (kept, removed) = cleanApkContents(repackedApk, cleanedApk)
                            sendLog(getString(R.string.log_kept_removed, kept, removed), "SUCCESS")
                        } catch (e: Exception) {
                            sendLog(getString(R.string.log_cleanup_error, e.message), "WARNING")
                            repackedApk.copyTo(cleanedApk, overwrite = true)
                        }
                    }
                    if (!cleanedApk.exists() || cleanedApk.length() == 0L) {
                        throw Exception("Repack failed")
                    }
                    sendLog("⚡ Перепаковка: ${System.currentTimeMillis() - repackStart} ms, ${String.format("%.1f", cleanedApk.length() / (1024.0 * 1024.0))} MB", "SUCCESS")
                    stageDone(" — " + String.format("%.1f", cleanedApk.length() / (1024.0 * 1024.0)) + " МБ")
                } finally {
                    // ✅ Удаляем патченные DEX и оригинал сразу
                    patchedDexMap.values.forEach { it.delete() }
                    patchedDexMap.clear()
                    workingApk.delete()
                    repackedApk.delete()
                    System.gc()
                }

                if (patchOptimize && !signConfig.noSign) {
                    anyApplied = true
                    stageBegin()
                    val optimizedApk = File(workDir, "optimized.apk")
                    try {
                        var lastOptPct = -1
                        val savedBytes = ZipOptimizer.recompressUltra(cleanedApk, optimizedApk) { cb, ct ->
                            val pct = if (ct > 0) ((cb * 100) / ct).toInt() else 100
                            if (pct != lastOptPct) {
                                lastOptPct = pct
                                stageTick((cb.toFloat() / ct.coerceAtLeast(1L)).coerceIn(0f, 1f), "")
                            }
                        }
                        if (savedBytes > 0 && optimizedApk.exists()) {
                            cleanedApk.delete()
                            cleanedApk = optimizedApk
                            fastPacked = false
                            stageDone(" — -$savedBytes байт")
                        } else {
                            optimizedApk.delete()
                            sendLog("⚠️ Сжатие не дало выигрыша, оставляем без изменений", "WARNING")
                            stageDone()
                        }
                    } catch (e: Exception) {
                        optimizedApk.delete()
                        sendLog("⚠️ Оптимизация: ${e.message}", "WARNING")
                        stageDone()
                    }
                }

                stageBegin()
                val alignedApk = File(workDir, "aligned.apk")
                try {
                    if (fastPacked) {
                        // Уже очищено и выровнено за один проход — повторная пересборка не нужна
                        if (!cleanedApk.renameTo(alignedApk)) {
                            cleanedApk.copyTo(alignedApk, overwrite = true)
                            cleanedApk.delete()
                        }
                        if (!alignedApk.exists() || alignedApk.length() == 0L) throw Exception("ZipAlign failed")
                        stageDone(" — " + String.format("%.1f", alignedApk.length() / (1024.0 * 1024.0)) + " МБ")
                    } else {
                        zipAlignWithARSCLib(cleanedApk, alignedApk)
                        if (!alignedApk.exists() || alignedApk.length() == 0L) throw Exception("ZipAlign failed")
                        stageDone(" — " + String.format("%.1f", alignedApk.length() / (1024.0 * 1024.0)) + " МБ")
                    }
                } finally {
                    cleanedApk.delete()
                    System.gc()
                }

                stageBegin()
                val finalApk: File = if (signConfig.noSign) {
                    sendLog(getString(R.string.saving_original_signature_progress), "INFO")
                    val preservedApk = File(workDir, "presigned.apk")
                    SigningUtils.preserveOriginalSignature(apkFile, alignedApk, preservedApk)
                    if (SigningUtils.hasSignatureData(preservedApk)) {
                        sendLog(getString(R.string.signed_preserved_signinfo), "SUCCESS")
                    } else {
                        sendLog(getString(R.string.original_signature_not_saved), "WARNING")
                    }
                    stageDone()
                    preservedApk
                } else {
                    val signedApk = File(workDir, "signed.apk")
                    try {
                        SigningUtils.signApk(this, alignedApk, signedApk, signConfig)
                        if (!signedApk.exists() || signedApk.length() == 0L) throw Exception("Sign failed")
                        stageDone(" — " + String.format("%.1f", signedApk.length() / (1024.0 * 1024.0)) + " МБ")
                        signedApk
                    } catch (e: Exception) {
                        sendLog(getString(R.string.log_sign_error, e.message), "ERROR")
                        throw e
                    }
                }
                alignedApk.delete()
                System.gc()

                val outputName = "${apkFile.nameWithoutExtension}_patched.apk"
                stageBegin()
                try {
                    FileSaver.saveApk(this, finalApk, outputName)
                    prepareForInstall(finalApk)
                    sendLog(getString(R.string.log_done_in_ms, System.currentTimeMillis() - totalStart), "SUCCESS")
                    sendLog("📁 " + SaveFolderHelper.getLocationLabel(this) + "/$outputName", "SUCCESS")
                    stageDone(" — $outputName")
                    sendProgress(100)
                } finally {
                    finalApk.delete()
                }

            } else {
                if (patchOptimize && !signConfig.noSign && (patchRemoveLocales || patchRemoveDpi || patchRemoveLibs || customPatches.isNotEmpty())) {
                    stageBegin()
                    val optimizedApk = File(workDir, "optimized.apk")
                    try {
                        var lastOptPct = -1
                        val savedBytes = ZipOptimizer.recompressUltra(workingApk, optimizedApk) { cb, ct ->
                            val pct = if (ct > 0) ((cb * 100) / ct).toInt() else 100
                            if (pct != lastOptPct) {
                                lastOptPct = pct
                                stageTick((cb.toFloat() / ct.coerceAtLeast(1L)).coerceIn(0f, 1f), "")
                            }
                        }
                        if (savedBytes > 0 && optimizedApk.exists()) {
                            workingApk.delete()
                            workingApk = optimizedApk
                            stageDone(" — -$savedBytes байт")
                        } else {
                            optimizedApk.delete()
                            sendLog("⚠️ Сжатие не дало выигрыша, оставляем без изменений", "WARNING")
                            stageDone()
                        }
                    } catch (e: Exception) {
                        optimizedApk.delete()
                        sendLog("⚠️ Оптимизация: ${e.message}", "WARNING")
                        stageDone()
                    }
                }

                if (patchRemoveLocales || patchRemoveDpi || patchRemoveLibs || customPatches.isNotEmpty()) {
                    stageBegin()
                    val alignedApk = File(workDir, "aligned.apk")
                    try {
                        try {
                            FastZipRepack.repack(workingApk, emptyMap<String, File>(), alignedApk, applyClean = false)
                        } catch (t: Throwable) {
                            Log.w(TAG, "⚡ FastZipRepack (align): fallback", t)
                            zipAlignWithARSCLib(workingApk, alignedApk)
                        }
                        if (!alignedApk.exists() || alignedApk.length() == 0L) throw Exception("ZipAlign failed")
                        stageDone(" — " + String.format("%.1f", alignedApk.length() / (1024.0 * 1024.0)) + " МБ")
                    } catch (e: Exception) {
                        sendLog("⚠️ ZipAlign: ${e.message}", "WARNING")
                        workingApk.copyTo(alignedApk, overwrite = true)
                        stageDone()
                    } finally {
                        workingApk.delete()
                        System.gc()
                    }

                    stageBegin()
                    val finalApk: File = if (signConfig.noSign) {
                        sendLog(getString(R.string.saving_original_signature_progress), "INFO")
                        val preservedApk = File(workDir, "presigned.apk")
                        SigningUtils.preserveOriginalSignature(apkFile, alignedApk, preservedApk)
                        if (SigningUtils.hasSignatureData(preservedApk)) {
                            sendLog(getString(R.string.signed_preserved_signinfo), "SUCCESS")
                        } else {
                            sendLog(getString(R.string.original_signature_not_saved), "WARNING")
                        }
                        stageDone()
                        preservedApk
                    } else {
                        val signedApk = File(workDir, "signed.apk")
                        try {
                            SigningUtils.signApk(this, alignedApk, signedApk, signConfig)
                            if (!signedApk.exists() || signedApk.length() == 0L) throw Exception("Sign failed")
                            stageDone(" — " + String.format("%.1f", signedApk.length() / (1024.0 * 1024.0)) + " МБ")
                            signedApk
                        } catch (e: Exception) {
                            sendLog(getString(R.string.log_sign_error, e.message), "ERROR")
                            throw e
                        }
                    }
                    alignedApk.delete()
                    System.gc()

                    val outputName = "${apkFile.nameWithoutExtension}_patched.apk"
                    stageBegin()
                    try {
                        FileSaver.saveApk(this, finalApk, outputName)
                        prepareForInstall(finalApk)
                        sendLog(getString(R.string.log_done_in_ms, System.currentTimeMillis() - totalStart), "SUCCESS")
                        stageDone(" — $outputName")
                        sendProgress(100)
                    } finally {
                        finalApk.delete()
                    }
                } else {
                    sendLog(getString(R.string.no_option_selected), "WARNING")
                }
            }

        } catch (e: OutOfMemoryError) {
            sendLog(getString(R.string.oom_apk_too_large), "ERROR")
            stageAbort(0)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка", e)
            sendLog(getString(R.string.error_with_msg, e.message), "ERROR")
            stageAbort(0)
        } finally {
            workDir.deleteRecursively()
            System.gc()
            runActive = false
            sendLog(getString(R.string.temp_files_removed), "INFO")
        }
    }

    private fun removeArchLibs(inputApk: File, outputApk: File, archsToRemove: List<String>): Int {
        val apkModule = ApkModule.loadApkFile(inputApk)
        var removedCount = 0
        try {
            val entriesToRemove = mutableListOf<String>()
            for (inputSource in apkModule.listInputSources()) {
                val name = inputSource.alias ?: inputSource.name
                if (name.startsWith("lib/")) {
                    val parts = name.split("/")
                    if (parts.size >= 2 && parts[1] in archsToRemove) {
                        entriesToRemove.add(name)
                    }
                }
            }
            for (name in entriesToRemove) {
                apkModule.removeInputSource(name)
                removedCount++
            }
            apkModule.writeApk(outputApk)
        } finally {
            apkModule.close()
        }
        return removedCount
    }

    private fun cleanApkContents(inputApk: File, outputApk: File): Pair<Int, Int> {
        var keptCount = 0
        var removedCount = 0
        val buffer = ByteArray(64 * 1024)

        ZipInputStream(inputApk.inputStream().buffered()).use { zis ->
            ZipOutputStream(outputApk.outputStream().buffered()).use { zos ->
                zos.setMethod(ZipOutputStream.DEFLATED)
                zos.setLevel(6)

                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    try {
                        val name = entry.name
                        val isInRoot = !name.contains('/') && !name.endsWith('/')
                        val baseName = name.substringAfterLast('/')

                        val isKept = baseName.equals("AndroidManifest.xml", ignoreCase = true) ||
                                baseName.equals("resources.arsc", ignoreCase = true) ||
                                baseName.equals("resources.pb", ignoreCase = true) ||
                                baseName.endsWith(".dex") ||
                                baseName.equals("stamp-cert-hash", ignoreCase = true) ||
                                baseName.equals("stamp-cert-sha256", ignoreCase = true)

                        val shouldRemove = (isInRoot && !isKept) ||
                                name.startsWith("kotlin/") ||
                                name.startsWith("kotlin-debug/") ||
                                name.startsWith("META-INF/kotlin") ||
                                name == "DebugProbesKt.bin"

                        if (!shouldRemove) {
                            val newEntry = ZipEntry(name)
                            if (entry.method == ZipEntry.STORED) {
                                newEntry.method = ZipEntry.STORED
                                newEntry.size = entry.size
                                newEntry.compressedSize = entry.compressedSize
                                newEntry.crc = entry.crc
                            } else {
                                newEntry.method = ZipEntry.DEFLATED
                            }
                            zos.putNextEntry(newEntry)
                            var len: Int
                            while (zis.read(buffer).also { len = it } != -1) {
                                zos.write(buffer, 0, len)
                            }
                            zos.closeEntry()
                            keptCount++
                        } else {
                            removedCount++
                        }
                    } catch (e: Exception) {
                        Log.e("PatchService", "Error: ${entry.name}", e)
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return Pair(keptCount, removedCount)
    }
    private fun zipAlignWithARSCLib(input: File, output: File) {
        try {
            val apkModule = ApkModule.loadApkFile(input)
            try {
                apkModule.writeApk(output)
            } finally {
                apkModule.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ARSCLib failed", e)
            input.copyTo(output, overwrite = true)
        }
    }


    private fun sendLog(message: String, type: String) {
        Log.d(TAG, "[$type] $message")
        synchronized(logBuffer) { logBuffer.add(message to type) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("PATCH_LOG").apply {
                putExtra("LOG_MESSAGE", message)
                putExtra("LOG_TYPE", type)
            }
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.patching_apk), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.patching_service)
                }
            )
        }
    }

    private fun createNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lazy Modification")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
