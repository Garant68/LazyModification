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
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.lazymodification.utils.ApkUtils
import com.example.lazymodification.utils.DexPatcher
import com.example.lazymodification.utils.DpiRemove
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.example.lazymodification.utils.ZipOptimizer
import com.reandroid.apk.ApkModule
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PatchService : Service() {

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_PATCH_GOOGLE_PLAY = "patch_google_play"
        const val EXTRA_PATCH_REMOVE_ADS = "patch_remove_ads"
        const val EXTRA_PATCH_REMOVE_ANALYTICS = "patch_remove_analytics"
        const val EXTRA_PATCH_REMOVE_GP_SERVICES = "patch_remove_gp_services"
        const val EXTRA_PATCH_REMOVE_VPN = "patch_remove_vpn"
        const val EXTRA_PATCH_REMOVE_INSTALLER_CHECK = "patch_remove_installer_check"
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
        const val CHANNEL_ID = "patch_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "PatchService"
        const val ACTION_PATCH_COMPLETE = "com.example.lazymodification.PATCH_COMPLETE"
        const val EXTRA_PATCHED_APK_PATH = "patched_apk_path"
        const val EXTRA_PATCHED_PACKAGE_NAME = "patched_package_name"

        private val logBuffer = mutableListOf<Pair<String, String>>()
    }

    private var wakeLock: PowerManager.WakeLock? = null

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
        val patchGooglePlay = intent?.getBooleanExtra(EXTRA_PATCH_GOOGLE_PLAY, false) ?: false
        val patchRemoveAds = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_ADS, false) ?: false
        val patchRemoveAnalytics = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_ANALYTICS, false) ?: false
        val patchRemoveGPServices = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_GP_SERVICES, false) ?: false
        val patchRemoveVpn = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_VPN, false) ?: false
        val patchRemoveInstallerCheck = intent?.getBooleanExtra(EXTRA_PATCH_REMOVE_INSTALLER_CHECK, false) ?: false
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
                    patchRemoveLocales, localesToRemove,
                    patchRemoveDpi, dpisToRemove,
                    patchRemoveLibs, libsToRemove,
                    patchOptimize,
                    signConfig
                )
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "❌ OutOfMemoryError", e)
                sendLog(getString(R.string.oom_file_too_large), "ERROR")
            } catch (e: Exception) {
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
        patchRemoveLocales: Boolean, localesToRemove: List<String>?,
        patchRemoveDpi: Boolean, dpisToRemove: List<String>?,
        patchRemoveLibs: Boolean, libsToRemove: List<String>?,
        patchOptimize: Boolean,
        signConfig: SignatureConfig
    ) {
        sendProgress(0)
        val totalStart = System.currentTimeMillis()
        val sizeMb = apkFile.length() / (1024.0 * 1024.0)
        sendLog(getString(R.string.log_start, apkFile.name, String.format("%.1f", sizeMb)), "INFO")
        sendLog("🔧 GP=$patchGooglePlay, Ads=$patchRemoveAds, Analytics=$patchRemoveAnalytics, GP_Svc=$patchRemoveGPServices, VPN=$patchRemoveVpn, Installer=$patchRemoveInstallerCheck, Locales=$patchRemoveLocales, DPI=$patchRemoveDpi, Libs=$patchRemoveLibs, Optimize=$patchOptimize, Debug=auto", "INFO")

        if (patchRemoveLocales && !localesToRemove.isNullOrEmpty()) sendLog(getString(R.string.log_locales_selected, localesToRemove.size), "INFO")
        if (patchRemoveDpi && !dpisToRemove.isNullOrEmpty()) sendLog(getString(R.string.log_dpi_selected, dpisToRemove.joinToString()), "INFO")
        if (patchRemoveLibs && !libsToRemove.isNullOrEmpty()) sendLog(getString(R.string.log_archs_selected, libsToRemove.joinToString()), "INFO")

        val signDesc = if (signConfig.noSign) getString(R.string.saving_original_signature)
        else if (signConfig.useCustom) getString(R.string.log_custom_signature, signConfig.signV1, signConfig.signV2, signConfig.signV3)
        else "🔐 V1=${signConfig.signV1}, V2=${signConfig.signV2}, V3=${signConfig.signV3}"
        sendLog(getString(R.string.log_signature, signDesc), "INFO")

        val workDir = File(cacheDir, "patch_${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            sendLog(getString(R.string.status_copying_apk), "INFO")
            var workingApk = File(workDir, apkFile.name)
            apkFile.copyTo(workingApk, overwrite = true)
            sendLog(getString(R.string.log_copied_mb, String.format("%.1f", workingApk.length() / (1024.0 * 1024.0))), "SUCCESS")
            sendProgress(5)

            if (patchRemoveAnalytics) {
                sendLog(getString(R.string.status_patching_manifest), "INFO")
                val manifestPatchedApk = File(workDir, "manifest_patched.apk")
                try {
                    val manifestCount = DexPatcher.patchManifest(workingApk, manifestPatchedApk)
                    if (manifestCount > 0) {
                        manifestPatchedApk.copyTo(workingApk, overwrite = true)
                        sendLog(getString(R.string.log_manifest_removed, manifestCount), "SUCCESS")
                    } else {
                        sendLog(getString(R.string.manifest_nothing_found), "WARNING")
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_manifest_error, e.message), "WARNING")
                } finally {
                    manifestPatchedApk.delete()
                }
            }
            sendProgress(10)

            if (patchRemoveLocales) {
                sendLog(getString(R.string.status_removing_locales), "INFO")
                val noLocalesApk = File(workDir, "no_locales.apk")
                try {
                    val removedCount = DexPatcher.removeLocalesFromApk(workingApk, noLocalesApk, localesToRemove)
                    if (removedCount > 0) {
                        noLocalesApk.copyTo(workingApk, overwrite = true)
                        sendLog(getString(R.string.log_locales_removed, removedCount), "SUCCESS")
                    } else {
                        sendLog(getString(R.string.locales_not_found), "WARNING")
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_locales_error, e.message), "WARNING")
                } finally {
                    noLocalesApk.delete()
                }
                sendProgress(13)
            }

            if (patchRemoveDpi) {
                sendLog(getString(R.string.status_removing_dpi), "INFO")
                val noDpiApk = File(workDir, "no_dpi.apk")
                try {
                    if (dpisToRemove.isNullOrEmpty()) {
                        sendLog(getString(R.string.dpi_not_selected), "WARNING")
                    } else {
                        val removedCount = DpiRemove.removeDpisFromApk(workingApk, noDpiApk, dpisToRemove)
                        if (removedCount > 0) {
                            noDpiApk.copyTo(workingApk, overwrite = true)
                            sendLog(getString(R.string.log_dpi_removed, removedCount), "SUCCESS")
                        } else {
                            sendLog(getString(R.string.dpi_files_not_found), "WARNING")
                        }
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_dpi_error, e.message), "WARNING")
                } finally {
                    noDpiApk.delete()
                }
                sendProgress(16)
            }

            if (patchRemoveLibs) {
                sendLog(getString(R.string.status_removing_libs), "INFO")
                val noLibsApk = File(workDir, "no_libs.apk")
                try {
                    if (libsToRemove.isNullOrEmpty()) {
                        sendLog(getString(R.string.archs_not_selected), "WARNING")
                    } else {
                        val removedCount = removeArchLibs(workingApk, noLibsApk, libsToRemove)
                        if (removedCount > 0) {
                            noLibsApk.copyTo(workingApk, overwrite = true)
                            sendLog(getString(R.string.log_libs_removed, removedCount), "SUCCESS")
                        } else {
                            sendLog(getString(R.string.libs_not_found), "WARNING")
                        }
                    }
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_libs_error, e.message), "WARNING")
                } finally {
                    noLibsApk.delete()
                }
                sendProgress(19)
            }

            val needsDexPatching = patchGooglePlay || patchRemoveAds || patchRemoveAnalytics ||
                    patchRemoveGPServices || patchRemoveVpn || patchRemoveInstallerCheck || patchOptimize

            if (needsDexPatching) {
                sendLog(getString(R.string.status_extracting_dex), "INFO")
                val dexDir = File(workDir, "dex")
                dexDir.mkdirs()
                val dexFiles = ApkUtils.extractAllDex(workingApk, dexDir)
                sendLog(getString(R.string.log_dex_count, dexFiles.size), "SUCCESS")
                sendProgress(21)

                val patchedDexMap = HashMap<String, File>()
                var totalGP = 0; var totalAds = 0; var totalAnalytics = 0
                var totalGPServices = 0; var totalVpn = 0; var totalInstallerCheck = 0; var totalDebug = 0

                val dexCount = dexFiles.size
                val progressPerDex = if (dexCount > 0) 50.0 / dexCount else 0.0

                for ((i, dex) in dexFiles.withIndex()) {
                    val dexStart = System.currentTimeMillis()
                    val dexSizeMb = String.format("%.1f", dex.length() / (1024.0 * 1024.0))
                    sendLog("🔧 ${dex.name} (${dexSizeMb} MB)...", "INFO")

                    val patchedDex = File(workDir, "patched_${dex.name}")
                    try {
                        val result = DexPatcher.patchDex(
                            inputDex = dex, outputDex = patchedDex,
                            patchGooglePlay = patchGooglePlay, patchRemoveAds = patchRemoveAds,
                            patchRemoveAnalytics = patchRemoveAnalytics, patchRemoveGPServices = patchRemoveGPServices,
                            patchRemoveVpn = patchRemoveVpn, patchRemoveInstallerCheck = patchRemoveInstallerCheck, patchRemoveDebug = true
                        )

                        totalGP += result.googlePlayPatched
                        totalAds += result.adsPatched
                        totalAnalytics += result.analyticsPatched
                        totalGPServices += result.gpServicesPatched
                        totalVpn += result.vpnPatched
                        totalInstallerCheck += result.installerCheckPatched
                        totalDebug += result.debugItemsRemoved

                        patchedDexMap[dex.name] = patchedDex

                        val elapsed = System.currentTimeMillis() - dexStart
                        sendLog("  GP:${result.googlePlayPatched} Ads:${result.adsPatched} Analytics:${result.analyticsPatched} GP_Svc:${result.gpServicesPatched} VPN:${result.vpnPatched} Installer:${result.installerCheckPatched} Debug:${result.debugItemsRemoved} (${elapsed}ms)", "INFO")
                    } catch (e: OutOfMemoryError) {
                        sendLog(getString(R.string.log_oom_dex, dex.name, dexSizeMb), "ERROR")
                        throw e
                    } finally {
                        // ✅ Сразу удаляем исходный DEX и освобождаем память
                        dex.delete()
                        System.gc()
                    }

                    sendProgress((21 + progressPerDex * (i + 1)).toInt().coerceAtMost(71))
                }

                if (patchGooglePlay) sendLog("✅ Google Play: $totalGP", if (totalGP > 0) "SUCCESS" else "WARNING")
                if (patchRemoveAds) sendLog(getString(R.string.log_ads, totalAds), if (totalAds > 0) "SUCCESS" else "WARNING")
                if (patchRemoveAnalytics) sendLog(getString(R.string.log_analytics, totalAnalytics), if (totalAnalytics > 0) "SUCCESS" else "WARNING")
                if (patchRemoveGPServices) sendLog(getString(R.string.log_gp_services, totalGPServices), if (totalGPServices > 0) "SUCCESS" else "WARNING")
                if (patchRemoveVpn) sendLog("✅ VPN Detection: $totalVpn", if (totalVpn > 0) "SUCCESS" else "WARNING")
                if (patchRemoveInstallerCheck) sendLog("✅ Installer check: $totalInstallerCheck", if (totalInstallerCheck > 0) "SUCCESS" else "WARNING")
                sendLog(getString(R.string.log_debug, totalDebug), if (totalDebug > 0) "SUCCESS" else "INFO")

                sendLog(getString(R.string.status_repacking), "INFO")
                val repackedApk = File(workDir, "repacked.apk")
                try {
                    ApkUtils.repackApkWithMultipleDex(workingApk, patchedDexMap, repackedApk)
                    if (!repackedApk.exists() || repackedApk.length() == 0L) {
                        throw Exception("Repack failed")
                    }
                    sendLog(getString(R.string.log_repacked_mb, String.format("%.1f", repackedApk.length() / (1024.0 * 1024.0))), "SUCCESS")
                } finally {
                    // ✅ Удаляем патченные DEX и оригинал сразу
                    patchedDexMap.values.forEach { it.delete() }
                    patchedDexMap.clear()
                    workingApk.delete()
                    System.gc()
                }
                sendProgress(73)

                sendLog(getString(R.string.status_cleaning_kotlin), "INFO")
                var cleanedApk = File(workDir, "cleaned.apk")
                try {
                    val (kept, removed) = cleanApkContents(repackedApk, cleanedApk)
                    sendLog(getString(R.string.log_kept_removed, kept, removed), "SUCCESS")
                } catch (e: Exception) {
                    sendLog(getString(R.string.log_cleanup_error, e.message), "WARNING")
                    repackedApk.copyTo(cleanedApk, overwrite = true)
                } finally {
                    repackedApk.delete()
                    System.gc()
                }
                sendProgress(80)

                if (patchOptimize) {
                    sendLog("📦 Оптимизация (сжатие)...", "INFO")
                    val optimizedApk = File(workDir, "optimized.apk")
                    try {
                        val savedBytes = ZipOptimizer.recompressUltra(cleanedApk, optimizedApk)
                        if (savedBytes > 0 && optimizedApk.exists()) {
                            cleanedApk.delete()
                            cleanedApk = optimizedApk
                            sendLog("✅ Оптимизировано: -$savedBytes байт", "SUCCESS")
                        } else {
                            optimizedApk.delete()
                            sendLog("⚠️ Сжатие не дало выигрыша, оставляем без изменений", "WARNING")
                        }
                    } catch (e: Exception) {
                        optimizedApk.delete()
                        sendLog("⚠️ Оптимизация: ${e.message}", "WARNING")
                    }
                }

                sendLog("📐 ZipAlign...", "INFO")
                val alignedApk = File(workDir, "aligned.apk")
                try {
                    zipAlignWithARSCLib(cleanedApk, alignedApk)
                    if (!alignedApk.exists() || alignedApk.length() == 0L) throw Exception("ZipAlign failed")
                    sendLog("✅ ZipAlign: ${String.format("%.1f", alignedApk.length() / (1024.0 * 1024.0))} MB", "SUCCESS")
                } finally {
                    cleanedApk.delete()
                    System.gc()
                }
                sendProgress(85)

                sendLog(getString(R.string.status_signing), "INFO")
                val finalApk: File = if (signConfig.noSign) {
                    sendLog(getString(R.string.saving_original_signature_progress), "INFO")
                    val preservedApk = File(workDir, "presigned.apk")
                    SigningUtils.preserveOriginalSignature(apkFile, alignedApk, preservedApk)
                    sendLog(getString(R.string.signed_preserved_signinfo), "SUCCESS")
                    preservedApk
                } else {
                    val signedApk = File(workDir, "signed.apk")
                    try {
                        SigningUtils.signApk(this, alignedApk, signedApk, signConfig)
                        if (!signedApk.exists() || signedApk.length() == 0L) throw Exception("Sign failed")
                        sendLog(getString(R.string.log_signed_mb, String.format("%.1f", signedApk.length() / (1024.0 * 1024.0))), "SUCCESS")
                        signedApk
                    } catch (e: Exception) {
                        sendLog(getString(R.string.log_sign_error, e.message), "ERROR")
                        throw e
                    }
                }
                alignedApk.delete()
                System.gc()
                sendProgress(90)

                val outputName = "${apkFile.nameWithoutExtension}_patched.apk"
                sendLog(getString(R.string.log_saving, outputName), "INFO")
                try {
                    FileSaver.saveApk(this, finalApk, outputName)
                    prepareForInstall(finalApk)
                    sendProgress(100)
                    sendLog(getString(R.string.log_done_in_ms, System.currentTimeMillis() - totalStart), "SUCCESS")
                    sendLog("📁 " + SaveFolderHelper.getLocationLabel(this) + "/$outputName", "SUCCESS")
                } finally {
                    finalApk.delete()
                }

            } else {
                if (patchRemoveLocales || patchRemoveDpi || patchRemoveLibs) {
                    sendLog("📐 ZipAlign...", "INFO")
                    val alignedApk = File(workDir, "aligned.apk")
                    try {
                        zipAlignWithARSCLib(workingApk, alignedApk)
                        if (!alignedApk.exists() || alignedApk.length() == 0L) throw Exception("ZipAlign failed")
                        sendLog("✅ ZipAlign: ${String.format("%.1f", alignedApk.length() / (1024.0 * 1024.0))} MB", "SUCCESS")
                    } catch (e: Exception) {
                        sendLog("⚠️ ZipAlign: ${e.message}", "WARNING")
                        workingApk.copyTo(alignedApk, overwrite = true)
                    } finally {
                        workingApk.delete()
                        System.gc()
                    }
                    sendProgress(85)

                    sendLog(getString(R.string.status_signing), "INFO")
                    val finalApk: File = if (signConfig.noSign) {
                        sendLog(getString(R.string.saving_original_signature_progress), "INFO")
                        val preservedApk = File(workDir, "presigned.apk")
                        SigningUtils.preserveOriginalSignature(apkFile, alignedApk, preservedApk)
                        sendLog(getString(R.string.signed_preserved_signinfo), "SUCCESS")
                        preservedApk
                    } else {
                        val signedApk = File(workDir, "signed.apk")
                        try {
                            SigningUtils.signApk(this, alignedApk, signedApk, signConfig)
                            if (!signedApk.exists() || signedApk.length() == 0L) throw Exception("Sign failed")
                            sendLog(getString(R.string.signed), "SUCCESS")
                            signedApk
                        } catch (e: Exception) {
                            sendLog(getString(R.string.log_sign_error, e.message), "ERROR")
                            throw e
                        }
                    }
                    alignedApk.delete()
                    System.gc()
                    sendProgress(90)

                    val outputName = "${apkFile.nameWithoutExtension}_patched.apk"
                    sendLog(getString(R.string.log_saving, outputName), "INFO")
                    try {
                        FileSaver.saveApk(this, finalApk, outputName)
                        prepareForInstall(finalApk)
                        sendProgress(100)
                        sendLog(getString(R.string.log_done_in_ms, System.currentTimeMillis() - totalStart), "SUCCESS")
                    } finally {
                        finalApk.delete()
                    }
                } else {
                    sendLog(getString(R.string.no_option_selected), "WARNING")
                }
            }

        } catch (e: OutOfMemoryError) {
            sendLog(getString(R.string.oom_apk_too_large), "ERROR")
            sendProgress(0)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка", e)
            sendLog(getString(R.string.error_with_msg, e.message), "ERROR")
            sendProgress(0)
        } finally {
            workDir.deleteRecursively()
            System.gc()
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
        } catch (e: Exception) {
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
