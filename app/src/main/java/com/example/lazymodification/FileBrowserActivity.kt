package com.example.lazymodification

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class FileBrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SELECTED_PATH = "com.example.lazymodification.SELECTED_PATH"
        const val EXTRA_FILE_MODE = "com.example.lazymodification.FILE_MODE"
        const val MODE_ALL = "all"
        const val MODE_APKS = "apks"
        const val MODE_APKS_XAPK = "apks_xapk"
        const val MODE_APK = "apk"
        const val MODE_IMAGE = "image"
        const val PREF_SORT = "sort_prefs"
        const val PREF_SORT_KEY = "sort_mode"
        const val SORT_NAME = "name"
        const val SORT_DATE = "date"
        const val SORT_SIZE = "size"
        private const val REQUEST_MANAGE_STORAGE = 1001
    }

    private lateinit var listView: ListView
    private lateinit var currentDir: File
    private lateinit var fileList: MutableList<String>
    private val currentFiles = mutableListOf<File?>()
    private var fileMode: String = MODE_ALL

    // ✅ Лаунчер для запроса разрешений на Android 10 и ниже
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initBrowser()
        } else {
            Toast.makeText(this, getString(R.string.storage_permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_file_browser)

        listView = findViewById(R.id.lv_files)
        fileMode = intent.getStringExtra(EXTRA_FILE_MODE) ?: MODE_ALL

        // ✅ Обработка системной кнопки "Назад" — возврат в родительскую папку
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val rootPath = Environment.getExternalStorageDirectory().absolutePath
                if (currentDir.absolutePath != rootPath && currentDir.parentFile != null) {
                    currentDir = currentDir.parentFile!!
                    loadFiles()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (!checkStoragePermission()) {
            requestStoragePermission()
            return
        }

        initBrowser()
    }

    // ✅ ИСПРАВЛЕННАЯ ПРОВЕРКА РАЗРЕШЕНИЙ
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ требует getString(R.string.all_files)
            Environment.isExternalStorageManager()
        } else {
            // Android 10 и ниже требуют READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ✅ ИСПРАВЛЕННЫЙ ЗАПРОС РАЗРЕШЕНИЙ
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            }
        } else {
            // Android 10 и ниже: запрашиваем через современный ActivityResult API
            val permissionsToRequest = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)

            // Для Android 9 (API 28) и ниже также критически важен WRITE_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (checkStoragePermission()) {
                initBrowser()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initBrowser() {
        currentDir = Environment.getExternalStorageDirectory()
        loadFiles()
        listView.setOnItemClickListener { _, _, position, _ ->
            val file = currentFiles.getOrNull(position) ?: return@setOnItemClickListener
            if (file.isDirectory) {
                currentDir = file
                loadFiles()
            } else {
                val result = Intent().apply {
                    putExtra(EXTRA_SELECTED_PATH, file.absolutePath)
                }
                setResult(RESULT_OK, result)
                finish()
            }
        }
    }

    private fun loadFiles() {
        fileList = mutableListOf()
        currentFiles.clear()
        val files = currentDir.listFiles()

        if (files != null) {
            sortFiles(files.toList()).forEach { file ->
                if (file.isDirectory) {
                    if (!file.name.startsWith(".") && file.name != "Android") {
                        fileList.add("📁 ${file.name}")
                        currentFiles.add(file)
                    }
                } else {
                    val shouldShow = when (fileMode) {
                        MODE_APKS -> file.name.endsWith(".apks", ignoreCase = true)
                        MODE_APKS_XAPK -> file.name.endsWith(".apks", ignoreCase = true) ||
                                file.name.endsWith(".xapk", ignoreCase = true)
                        MODE_APK -> file.name.endsWith(".apk", ignoreCase = true) &&
                                !file.name.endsWith(".apks", ignoreCase = true) &&
                                !file.name.endsWith(".xapk", ignoreCase = true)
                        MODE_IMAGE -> file.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp")
                        MODE_ALL -> true
                        else -> true
                    }
                    if (shouldShow) {
                        fileList.add("📄 ${file.name}")
                        currentFiles.add(file)
                    }
                }
            }
        } else {
            // ✅ Если files == null (нет прав или папка пуста), показываем сообщение
            fileList.add(getString(R.string.no_files_access))
            currentFiles.add(null)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, fileList)
        listView.adapter = adapter
    }

    private fun sortFiles(files: List<File>): List<File> {
        val dirs = files.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val filesOnly = files.filter { it.isFile }
        val sortedFiles = when (loadSortMode()) {
            SORT_DATE -> filesOnly.sortedByDescending { it.lastModified() }
            SORT_SIZE -> filesOnly.sortedByDescending { it.length() }
            else -> filesOnly.sortedBy { it.name.lowercase() }
        }
        return dirs + sortedFiles
    }

    private fun loadSortMode(): String {
        return getSharedPreferences(PREF_SORT, MODE_PRIVATE)
            .getString(PREF_SORT_KEY, SORT_NAME) ?: SORT_NAME
    }
}