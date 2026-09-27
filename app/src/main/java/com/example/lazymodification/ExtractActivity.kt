package com.example.lazymodification

import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.FileSaver
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractActivity : AppCompatActivity() {

    private lateinit var rvApps: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_extract)

        rvApps = findViewById(R.id.rvApps)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        rvApps.layoutManager = LinearLayoutManager(this)
        loadUserApps()
    }

    private fun loadUserApps() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        Thread {
            try {
                val pm = packageManager
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

                val userApps = installedApps.filter { app ->
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                            pm.getLaunchIntentForPackage(app.packageName) != null
                }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (userApps.isEmpty()) {
                        tvEmpty.text = getString(R.string.user_apps_not_found)
                        tvEmpty.visibility = View.VISIBLE
                    } else {
                        rvApps.adapter = AppAdapter(userApps) { app ->
                            extractApp(app)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvEmpty.text = getString(R.string.error_msg_plain, e.message)
                    tvEmpty.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun extractApp(app: ApplicationInfo) {
        val appName = packageManager.getApplicationLabel(app).toString()
        val safeName = sanitizeFileName(appName)
        val splits = app.splitSourceDirs
        val isSplit = !splits.isNullOrEmpty()

        Toast.makeText(this, getString(R.string.extracting_app, appName), Toast.LENGTH_SHORT).show()

        Thread {
            try {
                if (isSplit) {
                    // ✅ Split APK → .apks (с MIME type application/octet-stream)
                    val fileName = "$safeName.apks"
                    extractSplitApk(app, fileName)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.saved_to_downloads, fileName, SaveFolderHelper.getLocationLabel(this)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    // ✅ Обычный APK → .apk
                    val fileName = "$safeName.apk"
                    extractSingleApk(app.sourceDir, fileName)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.saved_to_downloads, fileName, SaveFolderHelper.getLocationLabel(this)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun extractSingleApk(sourcePath: String, fileName: String) {
        val sourceFile = File(sourcePath)
        // ✅ Для .apk используем правильный MIME тип
        FileSaver.saveStream(this, sourceFile.inputStream(), fileName, "application/vnd.android.package-archive")
    }

    private fun extractSplitApk(app: ApplicationInfo, fileName: String) {
        val tempFile = File(cacheDir, "temp_extract_${System.currentTimeMillis()}.apks")
        tempFile.delete()

        val buffer = ByteArray(64 * 1024)

        ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
            // base.apk
            val baseFile = File(app.sourceDir)
            addFileToZip(zos, baseFile, "base.apk", buffer)

            // split файлы
            app.splitSourceDirs?.forEachIndexed { index, splitPath ->
                val splitFile = File(splitPath)
                val splitName = splitFile.name
                val entryName = if (splitName.startsWith("split_") || splitName.contains("split")) {
                    splitName
                } else {
                    "split_$index.apk"
                }
                addFileToZip(zos, splitFile, entryName, buffer)
            }
        }

        // ✅ КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: для .apks используем application/octet-stream
        // Это предотвращает автоматическое добавление .apk системой
        FileSaver.saveStream(this, tempFile.inputStream(), fileName, "application/octet-stream")
        tempFile.delete()
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String, buffer: ByteArray) {
        val ze = ZipEntry(entryName)
        ze.method = ZipEntry.DEFLATED
        zos.putNextEntry(ze)
        FileInputStream(file).use { inp ->
            var bytesRead: Int
            while (inp.read(buffer).also { bytesRead = it } != -1) {
                zos.write(buffer, 0, bytesRead)
            }
        }
        zos.closeEntry()
    }


    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\- ]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(80)
    }
}