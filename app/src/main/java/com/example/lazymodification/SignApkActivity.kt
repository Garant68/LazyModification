package com.example.lazymodification

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.FileSaver
import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SignatureCreator
import com.example.lazymodification.utils.SigningUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Форма «Подпись APK»: переподпись выбранного APK и создание собственного ключа подписи.
 */
class SignApkActivity : AppCompatActivity() {

    private lateinit var pbSign: ProgressBar
    private lateinit var tvStatus: TextView

    private val uiHandler = Handler(Looper.getMainLooper())

    private var pendingSignConfirm: ((SignatureConfig) -> Unit)? = null
    private var pendingV1 = true
    private var pendingV2 = true
    private var pendingV3 = true
    private var signingInProgress = false

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

    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    startResign(file)
                } else {
                    Toast.makeText(this, getString(R.string.file_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_sign_apk)

        pbSign = findViewById(R.id.pbSign)
        tvStatus = findViewById(R.id.tvSignStatus)

        findViewById<MaterialButton>(R.id.btnSignApk).setOnClickListener {
            if (signingInProgress) {
                Toast.makeText(this, getString(R.string.sign_apk_signing), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickApkLauncher.launch(Intent(this, FileBrowserActivity::class.java).apply {
                putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APK)
            })
        }

        findViewById<MaterialButton>(R.id.btnCreateSignature).setOnClickListener {
            showCreateSignatureDialog()
        }
    }

    // ============================================================
    // ПЕРЕПОДПИСЬ APK
    // ============================================================
    private fun startResign(input: File) {
        pendingSignConfirm = { config -> doResign(input, config) }
        SigningUtils.resolveSigningConfig(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> doResign(input, config) }
        )
    }

    private fun doResign(input: File, config: SignatureConfig) {
        if (signingInProgress) return
        signingInProgress = true
        pbSign.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.sign_apk_signing)

        Thread {
            var errorText: String? = null
            val outName = input.nameWithoutExtension + "_signed.apk"
            try {
                val tmpOut = File(cacheDir, "resign_" + System.currentTimeMillis() + ".apk")
                SigningUtils.signApk(this, input, tmpOut, config)
                FileSaver.saveApk(this, tmpOut, outName)
                tmpOut.delete()
            } catch (t: Throwable) {
                errorText = t.message ?: t.javaClass.simpleName
            }
            val finalError = errorText
            uiHandler.post {
                signingInProgress = false
                pbSign.visibility = View.GONE
                if (isFinishing || isDestroyed) return@post
                if (finalError == null) {
                    val folder = SaveFolderHelper.getLocationLabel(this)
                    tvStatus.text = getString(R.string.sign_apk_done, outName) + "\n" + folder + "/" + outName
                    Toast.makeText(this, getString(R.string.sign_apk_done, outName), Toast.LENGTH_LONG).show()
                } else {
                    tvStatus.text = getString(R.string.sign_apk_failed, finalError)
                    Toast.makeText(this, getString(R.string.sign_apk_failed, finalError), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ============================================================
    // ДИАЛОГ СОЗДАНИЯ ПОДПИСИ
    // ============================================================
    private fun showCreateSignatureDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 12)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val intro = TextView(this).apply {
            text = getString(R.string.sig_intro)
            textSize = 14f
            setPadding(16, 0, 16, 4)
        }
        intro.setTextColor(MaterialColors.getColor(intro, com.google.android.material.R.attr.colorOnSurfaceVariant))
        container.addView(intro)

        val typeLabel = TextView(this).apply {
            text = getString(R.string.sig_field_type)
            textSize = 14f
            setPadding(16, 8, 16, 4)
        }
        container.addView(typeLabel)
        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rbPkcs12 = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.keystore_type_pkcs12)
            textSize = 16f
        }
        val rbJks = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.keystore_type_jks)
            textSize = 16f
        }
        radioGroup.addView(rbPkcs12)
        radioGroup.addView(rbJks)
        radioGroup.check(rbPkcs12.id)
        container.addView(radioGroup)
        val typeDesc = TextView(this).apply {
            text = getString(R.string.sig_type_desc)
            textSize = 12f
            setPadding(16, 2, 16, 10)
        }
        typeDesc.setTextColor(MaterialColors.getColor(typeDesc, com.google.android.material.R.attr.colorOnSurfaceVariant))
        container.addView(typeDesc)

        val etName = createField(container, R.string.sig_field_file, getString(R.string.sig_default_name), InputType.TYPE_CLASS_TEXT, R.string.sig_field_file_desc)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val ext = if (checkedId == rbJks.id) ".jks" else ".p12"
            val current = etName.text.toString()
            if (current.endsWith(".p12", true) || current.endsWith(".jks", true)) {
                etName.setText(current.substringBeforeLast('.') + ext)
            }
        }
        val etAlias = createField(container, R.string.sig_field_alias, getString(R.string.sig_default_alias), InputType.TYPE_CLASS_TEXT, R.string.sig_field_alias_desc)
        val etStorePass = createField(container, R.string.sig_field_store_pass, null, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, R.string.sig_field_store_pass_desc)
        val etKeyPass = createField(container, R.string.sig_field_key_pass, null, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, R.string.sig_field_key_pass_desc)
        val etYears = createField(container, R.string.sig_field_years, getString(R.string.sig_default_years), InputType.TYPE_CLASS_NUMBER, R.string.sig_field_years_desc)
        val etCn = createField(container, R.string.sig_field_cn, getString(R.string.sig_default_cn), InputType.TYPE_CLASS_TEXT, R.string.sig_field_cn_desc)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sig_create_title)
            .setView(scroll)
            .setPositiveButton(R.string.sig_action_create, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val dm = resources.displayMetrics
            dialog.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.85f).toInt())

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val isJks = rbJks.isChecked
                val fileName = sanitizeFileName(etName.text.toString(), isJks)
                val alias = etAlias.text.toString().trim()
                val storePass = etStorePass.text.toString()
                val keyPass = etKeyPass.text.toString().ifEmpty { storePass }
                val years = etYears.text.toString().trim().toIntOrNull()
                val cn = sanitizeCn(etCn.text.toString())

                if (fileName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.sig_err_name), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (alias.isEmpty()) {
                    Toast.makeText(this, getString(R.string.sig_err_alias), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (storePass.isEmpty()) {
                    Toast.makeText(this, getString(R.string.sig_err_pass), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (years == null || years < 1 || years > 100) {
                    Toast.makeText(this, getString(R.string.sig_err_years), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.dismiss()
                startSignatureCreation(fileName, alias, storePass, keyPass, years, cn, if (isJks) SignatureCreator.TYPE_JKS else SignatureCreator.TYPE_PKCS12)
            }
        }
        dialog.show()
    }

    /** Поле ввода + пояснение под ним. */
    private fun createField(
        container: LinearLayout,
        hintRes: Int,
        defaultText: String?,
        inputType: Int,
        descRes: Int
    ): EditText {
        val et = EditText(this).apply {
            hint = getString(hintRes)
            if (!defaultText.isNullOrEmpty()) setText(defaultText)
            this.inputType = inputType
            setPadding(16, 16, 16, 16)
        }
        container.addView(et)

        val desc = TextView(this).apply {
            text = getString(descRes)
            textSize = 12f
            setPadding(16, 2, 16, 10)
        }
        desc.setTextColor(MaterialColors.getColor(desc, com.google.android.material.R.attr.colorOnSurfaceVariant))
        container.addView(desc)
        return et
    }

    /** Имя файла: убираем недопустимые символы, добавляем расширение. */
    private fun sanitizeFileName(raw: String, jks: Boolean): String {
        var name = raw.trim()
        val bad = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        for (ch in bad) name = name.replace(ch, '_')
        name = name.trim().trim('.', ' ')
        if (name.isEmpty()) return ""
        if (!name.contains('.')) name += if (jks) ".jks" else ".p12"
        return name
    }

    /** Владелец сертификата (CN): убираем символы, ломающие формат X.500. */
    private fun sanitizeCn(raw: String): String {
        var s = raw
        val bad = charArrayOf(',', '"', '\\', '=', '+', '<', '>', '#', ';')
        for (ch in bad) s = s.replace(ch, ' ')
        s = s.trim()
        return s.ifEmpty { "LazyModification" }
    }

    // ============================================================
    // СОЗДАНИЕ ПОДПИСИ (в фоне) + СОХРАНЕНИЕ В ПАПКУ ВЫВОДА
    // ============================================================
    private fun startSignatureCreation(fileName: String, alias: String, storePass: String, keyPass: String, years: Int, cn: String, type: String) {
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.sig_creating))
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            var errorText: String? = null
            try {
                val bytes = SignatureCreator.createKeystore(
                    type = type,
                    alias = alias,
                    storePassword = storePass,
                    keyPassword = keyPass,
                    validityYears = years,
                    commonName = cn
                )
                FileSaver.saveStream(
                    this@SignApkActivity,
                    ByteArrayInputStream(bytes),
                    fileName,
                    if (type == SignatureCreator.TYPE_JKS) "application/octet-stream" else "application/x-pkcs12"
                )
            } catch (t: Throwable) {
                errorText = t.message ?: t.javaClass.simpleName
            }
            val finalError = errorText
            uiHandler.post {
                try { progress.dismiss() } catch (_: Throwable) { }
                if (isFinishing || isDestroyed) return@post
                if (finalError == null) {
                    showSignatureCreatedDialog(fileName, alias)
                } else {
                    AlertDialog.Builder(this@SignApkActivity)
                        .setTitle(R.string.sig_create_title)
                        .setMessage(getString(R.string.sig_create_error, finalError))
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun showSignatureCreatedDialog(fileName: String, alias: String) {
        val folder = SaveFolderHelper.getLocationLabel(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.sig_created_title)
            .setMessage(getString(R.string.sig_created_msg, fileName, folder, alias))
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}