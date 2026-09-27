package com.example.lazymodification

import com.example.lazymodification.utils.FileSaver
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkModule
import java.io.File

class SplitApkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.APK_PATH"
        private const val TAG = "SplitApkActivity"
    }

    private lateinit var tvFileName: TextView
    private lateinit var layoutCheckboxes: LinearLayout
    private lateinit var btnSplit: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var apkPath: String

    private val architectures = mutableListOf<String>()
    private val selectedArchs = mutableSetOf<String>()

    private var pendingSignConfirm: ((SignatureConfig) -> Unit)? = null
    private var pendingV1 = true
    private var pendingV2 = true
    private var pendingV3 = true

    private val keystoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val file = SigningUtils.copyKeystoreToCache(this, uri)
            if (file != null) {
                SigningUtils.showKeystorePasswordDialog(this, file, pendingV1, pendingV2, pendingV3) { config ->
                    pendingSignConfirm?.invoke(config)
                    pendingSignConfirm = null
                }
            } else {
                Toast.makeText(this, getString(R.string.err_keystore_read_failed), Toast.LENGTH_LONG).show()
                pendingSignConfirm = null
            }
        } else {
            Toast.makeText(this, getString(R.string.err_keystore_not_selected), Toast.LENGTH_SHORT).show()
            pendingSignConfirm = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_split_apk)

        try {
            tvFileName = findViewById(R.id.tvFileName)
            layoutCheckboxes = findViewById(R.id.layoutCheckboxes)
            btnSplit = findViewById(R.id.btnSplit)
            btnBack = findViewById(R.id.btnBack)
            progressBar = findViewById(R.id.progressBar)
            tvStatus = findViewById(R.id.tvStatus)

            apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: run {
                Toast.makeText(this, getString(R.string.file_not_specified), Toast.LENGTH_LONG).show()
                finish()
                return
            }

            val file = File(apkPath)
            if (!file.exists()) {
                Toast.makeText(this, getString(R.string.file_not_exists), Toast.LENGTH_LONG).show()
                finish()
                return
            }

            tvFileName.text = getString(R.string.file_label, file.name)
            btnBack.setOnClickListener { finish() }
            btnSplit.setOnClickListener { showSigningDialog() }
            detectArchitectures()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска", e)
            Toast.makeText(this, getString(R.string.launch_error_msg, e.message), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun detectArchitectures() {
        progressBar.visibility = View.VISIBLE
        btnSplit.isEnabled = false
        tvStatus.visibility = View.GONE

        Thread {
            try {
                val archSet = mutableSetOf<String>()
                val apkModule = ApkModule.loadApkFile(File(apkPath))
                for (inputSource in apkModule.listInputSources()) {
                    val name = inputSource.alias ?: inputSource.name
                    if (name.startsWith("lib/")) {
                        val parts = name.split("/")
                        if (parts.size >= 2 && parts[1].isNotBlank()) {
                            archSet.add(parts[1])
                        }
                    }
                }
                apkModule.close()

                architectures.clear()
                architectures.addAll(archSet.sorted())

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    if (architectures.isEmpty()) {
                        Toast.makeText(this, getString(R.string.archs_not_found), Toast.LENGTH_LONG).show()
                        btnSplit.isEnabled = false
                    } else {
                        createCheckboxes()
                        btnSplit.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка чтения APK", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun createCheckboxes() {
        layoutCheckboxes.removeAllViews()
        selectedArchs.clear()
        for (arch in architectures) {
            val checkBox = CheckBox(this).apply {
                text = "  $arch"
                textSize = 16f
                setPadding(16, 8, 16, 8)
                isChecked = true
                selectedArchs.add(arch)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedArchs.add(arch) else selectedArchs.remove(arch)
                }
            }
            layoutCheckboxes.addView(checkBox)
        }
    }

    private fun showSigningDialog() {
        pendingSignConfirm = { config -> startSplit(config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> startSplit(config) }
        )
    }

    private fun startSplit(config: SignatureConfig) {
        if (selectedArchs.isEmpty()) {
            Toast.makeText(this, getString(R.string.choose_at_least_one_arch), Toast.LENGTH_SHORT).show()
            return
        }

        if (!config.useCustom && !config.noSign) {
            try {
                assets.open("testkey.p12").close()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.testkey_not_found), Toast.LENGTH_LONG).show()
                return
            }
        }

        progressBar.visibility = View.VISIBLE
        btnSplit.isEnabled = false
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.status_preparing)

        Thread {
            val workDir = File(cacheDir, "split_${System.currentTimeMillis()}")
            try {
                workDir.mkdirs()
                val apkFile = File(apkPath)
                val baseName = apkFile.nameWithoutExtension

                Log.d(TAG, "Разделение APK...")
                val createdFiles = mutableListOf<String>()

                selectedArchs.forEach { arch ->
                    runOnUiThread { tvStatus.text = getString(R.string.creating_apk_for, arch) }

                    val outputApk = File(workDir, "temp_${arch.replace("-", "_")}.apk")
                    createArchSpecificApk(apkFile, outputApk, arch)

                    if (!outputApk.exists() || outputApk.length() == 0L) {
                        throw Exception("Создание APK не удалось для $arch")
                    }

                    Log.d(TAG, "Подпись APK для $arch...")
                    val signedApk = File(workDir, "signed_${arch.replace("-", "_")}.apk")

                    if (config.noSign) {
                        // ✅ СОХРАНЯЕМ ОРИГИНАЛЬНУЮ ПОДПИСЬ
                        SigningUtils.preserveOriginalSignature(apkFile, outputApk, signedApk)
                        Log.d(TAG, "Оригинальная подпись сохранена для $arch")
                    } else {
                        SigningUtils.signApk(this, outputApk, signedApk, config)
                    }

                    outputApk.delete()

                    val outName = "${baseName}_${arch}.apk"
                    FileSaver.saveApk(this, signedApk, outName)
                    signedApk.delete()
                    createdFiles.add(outName)
                }

                val signInfo = if (config.noSign) getString(R.string.original_signature_preserved)
                else "✅ V1=${config.signV1}, V2=${config.signV2}, V3=${config.signV3}${if (config.useCustom) " (своя подпись)" else ""}"

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                    Toast.makeText(
                        this,
                        getString(R.string.created_success, createdFiles.joinToString("\n"), signInfo),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка разделения APK", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                    btnSplit.isEnabled = true
                    Toast.makeText(this, getString(R.string.error_with_msg, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                workDir.deleteRecursively()
            }
        }.start()
    }

    private fun createArchSpecificApk(inputApk: File, outputApk: File, targetArch: String) {
        val apkModule = ApkModule.loadApkFile(inputApk)
        val entriesToRemove = mutableListOf<String>()

        for (inputSource in apkModule.listInputSources()) {
            val name = inputSource.alias ?: inputSource.name
            if (name.startsWith("lib/")) {
                val parts = name.split("/")
                if (parts.size >= 2) {
                    val archInLib = parts[1]
                    if (archInLib != targetArch) {
                        entriesToRemove.add(name)
                    }
                }
            }
        }

        for (name in entriesToRemove) {
            apkModule.removeInputSource(name)
        }

        apkModule.writeApk(outputApk)
        apkModule.close()
    }

}