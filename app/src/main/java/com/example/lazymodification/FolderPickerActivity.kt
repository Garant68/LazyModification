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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.lazymodification.utils.StorageRoots
import com.example.lazymodification.utils.WorkingFolderHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class FolderPickerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SELECTED_PATH = "com.example.lazymodification.SELECTED_FOLDER_PATH"
    }

    private lateinit var listView: ListView
    private lateinit var tvPath: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnUp: MaterialButton
    private lateinit var btnRoots: MaterialButton
    private lateinit var btnSelect: MaterialButton

    private lateinit var currentDir: File
    private val folders = mutableListOf<File>()
    private var initialized = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            initPicker()
        } else {
            Toast.makeText(this, getString(R.string.storage_permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkStoragePermission()) {
            initPicker()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_folder_picker)

        listView = findViewById(R.id.lvFolders)
        tvPath = findViewById(R.id.tvFolderPath)
        tvEmpty = findViewById(R.id.tvFoldersEmpty)
        btnUp = findViewById(R.id.btnFolderUp)
        btnRoots = findViewById(R.id.btnStorageRoots)
        btnSelect = findViewById(R.id.btnSelectFolder)

        listView.emptyView = tvEmpty
        btnUp.setOnClickListener { if (!goUp()) showRootsDialog() }
        btnRoots.setOnClickListener { showRootsDialog() }
        btnSelect.setOnClickListener { selectCurrentFolder() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val folder = folders.getOrNull(position) ?: return@setOnItemClickListener
            currentDir = folder
            loadFolders()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goUp()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (!checkStoragePermission()) {
            requestStoragePermission()
            return
        }

        initPicker()
    }

    private fun initPicker() {
        if (initialized) return
        initialized = true

        val fallback = StorageRoots.getRoots(this).firstOrNull()?.dir
            ?: Environment.getExternalStorageDirectory()
        val saved = WorkingFolderHelper.getFolder(this)
        currentDir = if (saved != null && saved.isDirectory && saved.canRead()) saved else fallback
        loadFolders()
    }

    private fun loadFolders() {
        folders.clear()
        val listed = currentDir.listFiles()
        val children = listed
            ?.filter { it.isDirectory && it.canRead() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        folders.addAll(children)

        val labels = folders.map { "📁 ${it.name}" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        tvPath.text = currentDir.absolutePath
        btnSelect.isEnabled = currentDir.isDirectory && currentDir.canRead()
        tvEmpty.text = if (listed == null) {
            getString(R.string.no_files_access)
        } else {
            getString(R.string.folders_empty)
        }
    }

    private fun goUp(): Boolean {
        if (!::currentDir.isInitialized) return false
        if (StorageRoots.isRoot(this, currentDir)) return false
        if (currentDir.absolutePath == "/" || currentDir.absolutePath == "/storage") return false

        val parent = currentDir.parentFile ?: return false
        if (!parent.isDirectory || !parent.canRead()) return false

        currentDir = parent
        loadFolders()
        return true
    }

    private fun showRootsDialog() {
        val roots = StorageRoots.getRoots(this)
        if (roots.isEmpty()) return

        val labels = roots.map { "${it.label}\n${it.dir.absolutePath}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_roots)
            .setItems(labels) { _, which ->
                currentDir = roots[which].dir
                loadFolders()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectCurrentFolder() {
        if (!currentDir.isDirectory || !currentDir.canRead()) return
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_SELECTED_PATH, currentDir.absolutePath)
        )
        finish()
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                manageStorageLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                try {
                    manageStorageLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            val permissions = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
