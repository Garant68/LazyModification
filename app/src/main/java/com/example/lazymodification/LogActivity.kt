package com.example.lazymodification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import java.io.File

class LogActivity : AppCompatActivity() {

    companion object {
        const val ACTION_CLEAR_LOGS = "com.example.lazymodification.CLEAR_LOGS"
        private const val EXTRA_SOURCE_APK_PATH = "SOURCE_APK_PATH"
    }

    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnClose: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnInstall: MaterialButton  // ✅ НОВАЯ КНОПКА

    private val logBuffer = StringBuilder()
    private val plainLogBuffer = StringBuilder()

    private var sourceApkPath: String? = null
    private var hasError = false

    // ✅ НОВОЕ: данные для установки
    private var patchedApkPath: String? = null
    private var patchedPackageName: String? = null
    private var pendingInstallAfterUninstall = false

    // ============================================================
    // ✅ ЛАУНЧЕРЫ
    // ============================================================

    // Лаунчер для удаления приложения (чтобы отследить результат)
    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (pendingInstallAfterUninstall) {
            pendingInstallAfterUninstall = false
            val pkg = patchedPackageName
            if (pkg != null && !isAppInstalled(pkg)) {
                // Приложение удалено — устанавливаем патченное
                val apk = patchedApkPath?.let { File(it) }
                if (apk != null && apk.exists()) {
                    Toast.makeText(this, getString(R.string.old_removed_installing_patched), Toast.LENGTH_SHORT).show()
                    installApk(apk)
                } else {
                    Toast.makeText(this, getString(R.string.patched_apk_not_found), Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.app_not_removed_install_cancelled), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Лаунчер для запроса разрешения на установку из неизвестных источников
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                proceedWithInstall()
            } else {
                Toast.makeText(this, getString(R.string.install_permission_denied), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ============================================================
    // BROADCAST RECEIVERS
    // ============================================================

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("LOG_MESSAGE") ?: return
            val logType = intent.getStringExtra("LOG_TYPE") ?: "INFO"
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

            val isDark = ThemeHelper.isDark(this@LogActivity)
            val color = when (logType) {
                "ERROR" -> if (isDark) "#FF6B6B" else "#D32F2F"
                "WARNING" -> if (isDark) "#FFB74D" else "#F57C00"
                "SUCCESS" -> if (isDark) "#81C784" else "#388E3C"
                "INFO" -> if (isDark) "#E0E0E0" else "#333333"
                else -> if (isDark) "#BDBDBD" else "#424242"
            }

            logBuffer.append("<font color='$color'>[$timestamp] $message</font><br>")
            plainLogBuffer.append("[$timestamp] [$logType] $message\n")

            if (logType == "ERROR") {
                hasError = true
                runOnUiThread {
                    progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#D32F2F")
                    )
                    btnClose.visibility = View.VISIBLE
                    btnSave.visibility = View.VISIBLE
                }
            }

            runOnUiThread {
                tvLog.text = android.text.Html.fromHtml(logBuffer.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.getIntExtra("PROGRESS_VALUE", 0) ?: return
            runOnUiThread {
                progressBar.progress = progress
                if (progress >= 100 && !hasError) {
                    progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#00AA00")
                    )
                    btnClose.visibility = View.VISIBLE
                    btnSave.visibility = View.VISIBLE
                    // ✅ Показываем кнопку getString(R.string.install) если есть APK
                    if (patchedApkPath != null) {
                        btnInstall.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ✅ НОВЫЙ RECEIVER: получение данных о готовом APK
    private val patchCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val apkPath = intent?.getStringExtra(PatchService.EXTRA_PATCHED_APK_PATH)
            val pkgName = intent?.getStringExtra(PatchService.EXTRA_PATCHED_PACKAGE_NAME)
            if (apkPath != null) {
                patchedApkPath = apkPath
                patchedPackageName = pkgName
                runOnUiThread {
                    if (!hasError && progressBar.progress >= 100) {
                        btnInstall.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_log)

        tvLog = findViewById(R.id.tvLog)
        scrollView = findViewById(R.id.scrollView)
        progressBar = findViewById(R.id.progressBar)
        btnClose = findViewById(R.id.btnClose)
        btnSave = findViewById(R.id.btnSave)
        btnInstall = findViewById(R.id.btnInstall)  // ✅ Инициализация

        sourceApkPath = intent.getStringExtra(EXTRA_SOURCE_APK_PATH)

        btnClose.visibility = View.GONE
        btnSave.visibility = View.GONE
        btnInstall.visibility = View.GONE

        btnClose.setOnClickListener {
            tvLog.text = ""
            logBuffer.clear()
            plainLogBuffer.clear()
            progressBar.progress = 0
            progressBar.progressTintList = null
            hasError = false
            patchedApkPath = null
            patchedPackageName = null
            try {
                startService(Intent(this, PatchService::class.java).apply {
                    action = ACTION_CLEAR_LOGS
                })
            } catch (e: Exception) {
                android.util.Log.e("LogActivity", "Clear logs failed: ${e.message}")
            }
            // ✅ Удаляем временный APK для установки
            val installCopy = File(cacheDir, "install_target.apk")
            if (installCopy.exists()) installCopy.delete()
            finish()
        }

        btnSave.setOnClickListener { saveLogToFile() }

        // ✅ Обработчик кнопки getString(R.string.install)
        btnInstall.setOnClickListener { handleInstallClick() }

        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(logReceiver, IntentFilter("PATCH_LOG"))
        lbm.registerReceiver(progressReceiver, IntentFilter("PATCH_PROGRESS"))
        lbm.registerReceiver(patchCompleteReceiver, IntentFilter(PatchService.ACTION_PATCH_COMPLETE))  // ✅ НОВЫЙ

        try {
            startService(Intent(this, PatchService::class.java).apply {
                action = PatchService.ACTION_FLUSH_LOGS
            })
        } catch (e: Exception) {
            android.util.Log.e("LogActivity", "Flush failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(logReceiver)
        lbm.unregisterReceiver(progressReceiver)
        lbm.unregisterReceiver(patchCompleteReceiver)
    }

    // ============================================================
    // ✅ ЛОГИКА УСТАНОВКИ
    // ============================================================

    private fun handleInstallClick() {
        val apkPath = patchedApkPath
        val pkgName = patchedPackageName

        if (apkPath == null) {
            Toast.makeText(this, getString(R.string.apk_file_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Toast.makeText(this, getString(R.string.apk_file_not_exists), Toast.LENGTH_SHORT).show()
            btnInstall.visibility = View.GONE
            return
        }

        // Если package name неизвестен — пытаемся прочитать из APK
        val effectivePkg = pkgName ?: getPackageNameFromApk(apkFile)
        if (effectivePkg == null) {
            // Не удалось определить package name — просто устанавливаем
            proceedWithInstall()
            return
        }

        patchedPackageName = effectivePkg

        // Проверяем, установлено ли приложение
        if (!isAppInstalled(effectivePkg)) {
            // Приложение не установлено — просто устанавливаем
            proceedWithInstall()
            return
        }

        // Приложение установлено — сравниваем подписи
        val installedSig = getInstalledAppSignature(effectivePkg)
        val apkSig = getApkFileSignature(apkPath)

        if (installedSig == null || apkSig == null) {
            // Не удалось определить подпись — пытаемся установить как есть
            proceedWithInstall()
            return
        }

        if (installedSig == apkSig) {
            // Подписи совпадают — можно обновить поверх
            proceedWithInstall()
        } else {
            // Подписи отличаются — нужно удалить старое
            showSignatureMismatchDialog(effectivePkg)
        }
    }

    private fun proceedWithInstall() {
        // Проверяем разрешение на установку из неизвестных источников (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, getString(R.string.allow_unknown_sources), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                installPermissionLauncher.launch(intent)
                return
            }
        }

        val apkPath = patchedApkPath ?: return
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Toast.makeText(this, getString(R.string.apk_file_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        installApk(apkFile)
    }

    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("LogActivity", "Install failed", e)
            Toast.makeText(this, getString(R.string.install_error_msg, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun showSignatureMismatchDialog(pkgName: String) {
        val appLabel = try {
            val appInfo = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkgName
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.signature_conflict))
            .setMessage(
                getString(R.string.sig_mismatch_part1, appLabel) +
                        getString(R.string.sig_mismatch_part2) +
                        getString(R.string.uninstall_and_install_question)
            )
            .setPositiveButton(getString(R.string.uninstall_and_install)) { _, _ ->
                pendingInstallAfterUninstall = true
                uninstallApp(pkgName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun uninstallApp(pkgName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$pkgName")
            }
            uninstallLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("LogActivity", "Uninstall failed", e)
            Toast.makeText(this, getString(R.string.uninstall_error_msg, e.message), Toast.LENGTH_LONG).show()
            pendingInstallAfterUninstall = false
        }
    }

    // ============================================================
    // ✅ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    private fun isAppInstalled(pkgName: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkgName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getPackageNameFromApk(apkFile: File): String? {
        return try {
            val pi = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            pi?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun getInstalledAppSignature(pkgName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = packageManager.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = pi.signingInfo
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.joinToString { it.toCharsString() }
                } else {
                    signingInfo.signingCertificateHistory.joinToString { it.toCharsString() }
                }
            } else {
                @Suppress("DEPRECATION")
                val pi = packageManager.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pi.signatures.joinToString { it.toCharsString() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getApkFileSignature(apkPath: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = pi?.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.joinToString { it.toCharsString() }
                } else {
                    signingInfo.signingCertificateHistory.joinToString { it.toCharsString() }
                }
            } else {
                @Suppress("DEPRECATION")
                val pi = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pi?.signatures?.joinToString { it.toCharsString() }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ============================================================
    // СОХРАНЕНИЕ ЛОГА
    // ============================================================

    private fun saveLogToFile() {
        val sourcePath = sourceApkPath
        if (sourcePath == null) {
            Toast.makeText(this, getString(R.string.unknown_source_path), Toast.LENGTH_SHORT).show()
            return
        }

        val sourceFile = File(sourcePath)
        val logFileName = "${sourceFile.nameWithoutExtension}_patch_log.txt"
        val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LazyModification")
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        val logFile = File(downloadDir, logFileName)
        try {
            logFile.writeText(plainLogBuffer.toString())
            Toast.makeText(
                this,
                getString(R.string.log_saved_success, logFile.absolutePath),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("LogActivity", "Save log failed", e)
            Toast.makeText(this, getString(R.string.log_save_error_msg, e.message), Toast.LENGTH_LONG).show()
        }
    }
}