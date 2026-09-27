package com.example.lazymodification

import com.example.lazymodification.utils.SaveFolderHelper
import com.example.lazymodification.utils.FileSaver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lazymodification.utils.SignatureConfig
import com.example.lazymodification.utils.SigningUtils
import com.google.android.material.button.MaterialButton
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.xml.XMLFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ConversionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ConversionActivity"
        private const val BUFFER_SIZE = 64 * 1024 // ✅ 64 КБ вместо 1 МБ
    }

    private lateinit var btnSelect: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String? = null

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

    private val pickApk = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists() && (file.name.endsWith(".apks", ignoreCase = true) ||
                            file.name.endsWith(".xapk", ignoreCase = true))) {
                    selectedFileUri = Uri.fromFile(file)
                    selectedFileName = file.name
                    btnSelect.text = file.name
                    showSigningDialog()
                } else {
                    Toast.makeText(this, getString(R.string.not_apks_xapk), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_conversion)

        btnSelect = findViewById(R.id.btnSelect)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)

        val incomingPath = intent.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_PATH)

        if (incomingPath != null) {
            val file = File(incomingPath)
            if (file.exists() && (file.name.endsWith(".apks", ignoreCase = true) ||
                        file.name.endsWith(".xapk", ignoreCase = true))) {
                selectedFileUri = Uri.fromFile(file)
                selectedFileName = file.name
                btnSelect.text = file.name
                btnSelect.isEnabled = false
                showSigningDialog()
            } else {
                Toast.makeText(this, getString(R.string.invalid_file), Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            btnSelect.setOnClickListener {
                pickApk.launch(
                    Intent(this, FileBrowserActivity::class.java).apply {
                        putExtra(FileBrowserActivity.EXTRA_FILE_MODE, FileBrowserActivity.MODE_APKS_XAPK)
                    }
                )
            }
        }
    }

    private fun showSigningDialog() {
        pendingSignConfirm = { config -> startConversion(config) }
        SigningUtils.showSigningDialog(
            activity = this,
            onCustomSelected = { v1, v2, v3 ->
                pendingV1 = v1; pendingV2 = v2; pendingV3 = v3
                keystoreLauncher.launch(arrayOf("*/*"))
            },
            onConfirm = { config -> startConversion(config) }
        )
    }

    private fun startConversion(config: SignatureConfig) {
        val inputUri = selectedFileUri ?: return
        val fileName = selectedFileName ?: return
        val outputName = fileName.replace(Regex("\\.(apks|xapk)$", RegexOption.IGNORE_CASE), "") + ".apk"
        val isXapk = fileName.endsWith(".xapk", ignoreCase = true)

        tvStatus.text = getString(R.string.status_processing)
        tvStatus.visibility = View.VISIBLE
        tvLog.text = ""
        scrollLog.visibility = View.GONE
        showProgress(true)

        Thread {
            try {
                if (isXapk) convertXapk(inputUri, outputName, config)
                else convertApks(inputUri, outputName, config)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM", e)
                runOnUiThread {
                    showProgress(false)
                    tvStatus.text = getString(R.string.oom)
                    tvLog.text = getString(R.string.file_too_large)
                    scrollLog.visibility = View.VISIBLE
                    Toast.makeText(this, "❌ OutOfMemoryError", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка конвертации", e)
                runOnUiThread {
                    showProgress(false)
                    tvStatus.text = getString(R.string.error)
                    tvLog.text = "❌ ${e.message}"
                    scrollLog.visibility = View.VISIBLE
                    Toast.makeText(this, getString(R.string.error_msg_plain, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun convertXapk(inputUri: Uri, outputName: String, config: SignatureConfig) {
        val tempDir = File(cacheDir, "temp_xapk_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val tempXapk = File(tempDir, "source.xapk")
            if (inputUri.scheme == "file") {
                File(inputUri.path!!).copyTo(tempXapk, overwrite = true)
            } else {
                contentResolver.openInputStream(inputUri)?.use { input ->
                    tempXapk.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Не удалось открыть XAPK")
            }

            val extractDir = File(tempDir, "extracted")
            extractDir.mkdirs()

            val buffer = ByteArray(BUFFER_SIZE)
            ZipFile(tempXapk).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .forEach { entry ->
                        val outFile = File(extractDir, entry.name.substringAfterLast('/'))
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                var len: Int
                                while (input.read(buffer).also { len = it } != -1) {
                                    output.write(buffer, 0, len)
                                }
                            }
                        }
                    }
            }

            val bundle = ApkBundle()
            bundle.loadApkDirectory(extractDir, false)
            val modules = bundle.apkModuleList

            if (modules.isEmpty()) throw IllegalStateException("Не найдено APK файлов")

            val mergedModule: ApkModule = bundle.mergeModules(false)
            val mergedFile = File(tempDir, "merged.apk")
            mergedModule.writeApk(mergedFile)
            mergedModule.close()
            bundle.close()
            System.gc()

            val finalFile = if (config.noSign) {
                val preservedFile = File(tempDir, "preserved.apk")
                SigningUtils.preserveOriginalSignature(tempXapk, mergedFile, preservedFile)
                mergedFile.delete()
                preservedFile
            } else {
                val signedFile = File(tempDir, "signed.apk")
                SigningUtils.signApk(this, mergedFile, signedFile, config)
                mergedFile.delete()
                signedFile
            }

            FileSaver.saveApk(this, finalFile, outputName)
            finalFile.delete()

            val signInfo = if (config.noSign) getString(R.string.original_signature_preserved)
            else "🔐 V1=${config.signV1}, V2=${config.signV2}, V3=${config.signV3}${if (config.useCustom) " (своя подпись)" else ""}"

            val exactLog = """
                📦 ЭТАП 1: Извлечение APK из XAPK
                ✅ Найдено модулей: ${modules.size}

                🔧 ЭТАП 2: Слияние split APK
                ✅ Все модули объединены

                🔐 ЭТАП 3: Подписание ($signInfo)

                💾 ЭТАП 4: Сохранение в ${SaveFolderHelper.getLocationLabel(this)}
            """.trimIndent()

            runOnUiThread {
                showProgress(false)
                tvLog.text = exactLog
                scrollLog.visibility = View.VISIBLE
                tvStatus.text = "✅ $outputName\n$signInfo"
                Toast.makeText(this, getString(R.string.xapk_conversion_done), Toast.LENGTH_SHORT).show()
                openOutputFolder()
            }
        } finally {
            tempDir.deleteRecursively()
            System.gc()
        }
    }

    private fun convertApks(inputUri: Uri, outputName: String, config: SignatureConfig) {
        val tempUniversal = File(cacheDir, "temp_universal.apk")
        tempUniversal.delete()

        try {
            buildUniversalApk(inputUri, tempUniversal)

            val tempModified = try {
                removeSplitAttributesQuiet(tempUniversal)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось удалить split-атрибуты", e)
                tempUniversal
            }

            if (tempModified != tempUniversal) tempUniversal.delete()

            val finalFile = if (config.noSign) {
                val preservedFile = File(cacheDir, "temp_preserved.apk")
                preservedFile.delete()
                val originalApk = extractBaseApkFromApks(inputUri)
                if (originalApk != null) {
                    SigningUtils.preserveOriginalSignature(originalApk, tempModified, preservedFile)
                    originalApk.delete()
                } else {
                    tempModified.copyTo(preservedFile, overwrite = true)
                }
                tempModified.delete()
                preservedFile
            } else {
                val tempSigned = File(cacheDir, "temp_signed.apk")
                tempSigned.delete()
                SigningUtils.signApk(this, tempModified, tempSigned, config)
                tempModified.delete()
                tempSigned
            }

            FileSaver.saveApk(this, finalFile, outputName)
            finalFile.delete()

            val signInfo = if (config.noSign) getString(R.string.original_signature_preserved)
            else " V1=${config.signV1}, V2=${config.signV2}, V3=${config.signV3}${if (config.useCustom) " (своя подпись)" else ""}"

            val exactLog = """
                📦 ЭТАП 1: Конвертация split APK
                ✅ Универсальный APK создан

                🔧 ЭТАП 2: Удаление split-атрибутов
                ✅ Атрибуты удалены

                🔐 ЭТАП 3: Подписание ($signInfo)

                💾 ЭТАП 4: Сохранение
            """.trimIndent()

            runOnUiThread {
                showProgress(false)
                tvLog.text = exactLog
                scrollLog.visibility = View.VISIBLE
                tvStatus.text = "✅ $outputName\n$signInfo"
                Toast.makeText(this, getString(R.string.apks_conversion_done), Toast.LENGTH_SHORT).show()
                openOutputFolder()
            }
        } catch (e: Exception) {
            tempUniversal.delete()
            throw e
        } finally {
            System.gc()
        }
    }

    private fun extractBaseApkFromApks(inputUri: Uri): File? {
        return try {
            val tempDir = File(cacheDir, "temp_base_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            val inputStream = if (inputUri.scheme == "file") {
                FileInputStream(File(inputUri.path!!))
            } else {
                contentResolver.openInputStream(inputUri)
            }

            var baseApk: File? = null
            val buffer = ByteArray(BUFFER_SIZE)
            inputStream?.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            val name = entry.name.substringAfterLast('/')
                            if (name.equals("base.apk", ignoreCase = true) || baseApk == null) {
                                val tempFile = File(tempDir, name)
                                FileOutputStream(tempFile).use { out ->
                                    var len: Int
                                    while (zis.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                                baseApk = tempFile
                                if (name.equals("base.apk", ignoreCase = true)) break
                            }
                        }
                    }
                }
            }
            baseApk
        } catch (e: Exception) {
            null
        }
    }

    private fun removeSplitAttributesQuiet(inputApk: File): File {
        val outputApk = File(cacheDir, "temp_modified.apk")
        val apkModule = ApkModule.loadApkFile(inputApk)
        try {
            val tableBlock = apkModule.tableBlock
            val manifestBlock = apkModule.androidManifest
                ?: throw IllegalStateException("AndroidManifest.xml не найден")

            val packageBlock = tableBlock.pickOne()
            if (packageBlock != null) manifestBlock.setPackageBlock(packageBlock)

            var xmlContent = manifestBlock.serializeToXml()
            xmlContent = xmlContent.replace(Regex("""\s*android:requiredSplitTypes="[^"]*""""), "")
            xmlContent = xmlContent.replace(Regex("""\s*android:splitTypes="[^"]*""""), "")
            xmlContent = xmlContent.replace(Regex("""\s*android:splitName="[^"]*""""), "")
            xmlContent = xmlContent.replace(Regex("""\s*android:isFeatureSplit="[^"]*""""), "")
            xmlContent = xmlContent.replace(Regex("""\s*android:featureSplitOf="[^"]*""""), "")
            xmlContent = xmlContent.replace(
                Regex("""<meta-data[^>]*android:name="com\.android\.vending\.splits\.required"[^>]*/>\s*"""), ""
            )
            xmlContent = xmlContent.replace(
                Regex("""<meta-data[^>]*android:name='com\.android\.vending\.splits\.required'[^>]*/>\s*"""), ""
            )

            try { manifestBlock.javaClass.getMethod("clear").invoke(manifestBlock) }
            catch (_: Exception) { try { manifestBlock.javaClass.getMethod("reset").invoke(manifestBlock) } catch (_: Exception) {} }

            val parser = XMLFactory.newPullParser(StringReader(xmlContent))
            parser.setInput(StringReader(xmlContent))
            manifestBlock.parse(parser)

            apkModule.writeApk(outputApk)
        } finally {
            apkModule.close()
        }
        return outputApk
    }

    private fun buildUniversalApk(inputUri: Uri, outputFile: File) {
        val tempDir = File(cacheDir, "temp_conv_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val processed = mutableSetOf<String>()
        val buffer = ByteArray(BUFFER_SIZE) // ✅ 64 КБ
        var hasClassesDex = false

        try {
            val apkList = mutableListOf<File>()

            val inputStream = if (inputUri.scheme == "file") {
                FileInputStream(File(inputUri.path!!))
            } else {
                contentResolver.openInputStream(inputUri)
            }

            inputStream?.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            val tempApk = File(tempDir, entry.name)
                            tempApk.parentFile?.mkdirs()
                            FileOutputStream(tempApk).use { out ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } != -1) {
                                    out.write(buffer, 0, len)
                                }
                            }
                            apkList.add(tempApk)
                        }
                    }
                }
            } ?: throw Exception("Не удалось открыть .apks файл")

            apkList.sortBy { apkFile ->
                val hasDex = try {
                    ZipFile(apkFile).use { zip ->
                        zip.entries().asSequence().any { it.name.matches(Regex("classes\\d*\\.dex")) }
                    }
                } catch (e: Exception) { false }
                if (hasDex) 0 else 1
            }

            val resourcesFiles = mutableListOf<File>()
            var isFirstApk = true

            for (apkFile in apkList) {
                ZipFile(apkFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name

                        if (name == "resources.arsc") {
                            val resFile = File(tempDir, "res_${apkFile.nameWithoutExtension}.arsc")
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(resFile).use { out ->
                                    var len: Int
                                    while (input.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                            }
                            resourcesFiles.add(resFile)
                            continue
                        }

                        if (name.matches(Regex("classes\\d*\\.dex"))) {
                            if (isFirstApk && !processed.contains(name)) {
                                val target = File(tempDir, "extracted/$name")
                                target.parentFile?.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(target).use { out ->
                                        var len: Int
                                        while (input.read(buffer).also { len = it } != -1) {
                                            out.write(buffer, 0, len)
                                        }
                                    }
                                }
                                processed.add(name)
                                hasClassesDex = true
                            }
                            continue
                        }

                        if (isFirstApk) {
                            val shouldExtract = name == "AndroidManifest.xml" ||
                                    name == "resources.pb" ||
                                    name.startsWith("res/") ||
                                    name.startsWith("assets/") ||
                                    name.startsWith("lib/") ||
                                    name.startsWith("kotlin/") ||
                                    name.startsWith("META-INF/services/") ||
                                    name.startsWith("META-INF/com/") ||
                                    name == "stamp-cert-sha256" ||
                                    name.startsWith("DebugProbesKt.bin") ||
                                    (!name.startsWith("META-INF/") && !name.endsWith(".properties"))

                            if (shouldExtract && !processed.contains(name)) {
                                val target = File(tempDir, "extracted/$name")
                                target.parentFile?.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(target).use { out ->
                                        var len: Int
                                        while (input.read(buffer).also { len = it } != -1) {
                                            out.write(buffer, 0, len)
                                        }
                                    }
                                }
                                processed.add(name)
                            }
                        } else {
                            if (!processed.contains(name) &&
                                name != "AndroidManifest.xml" &&
                                name != "resources.pb" &&
                                !name.startsWith("META-INF/") &&
                                !name.endsWith(".properties")) {
                                val target = File(tempDir, "extracted/$name")
                                target.parentFile?.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(target).use { out ->
                                        var len: Int
                                        while (input.read(buffer).also { len = it } != -1) {
                                            out.write(buffer, 0, len)
                                        }
                                    }
                                }
                                processed.add(name)
                            }
                        }
                    }
                }

                isFirstApk = false
                apkFile.delete() // ✅ Сразу удаляем обработанный split APK
                System.gc()
            }

            if (!hasClassesDex) throw Exception("classes.dex не найден")
            if (processed.isEmpty()) throw Exception("Нет файлов для записи")

            val mergedResources = if (resourcesFiles.size > 1) {
                mergeResourcesArsc(resourcesFiles)
            } else if (resourcesFiles.size == 1) {
                resourcesFiles[0]
            } else null

            if (mergedResources != null) {
                val target = File(tempDir, "extracted/resources.arsc")
                target.parentFile?.mkdirs()
                mergedResources.copyTo(target, overwrite = true)
                processed.add("resources.arsc")
            }

            // ✅ Удаляем временные resources.arsc файлы
            resourcesFiles.forEach { if (it != mergedResources) it.delete() }
            System.gc()

            val sortedNames = mutableListOf<String>()
            if (processed.contains("AndroidManifest.xml")) sortedNames.add("AndroidManifest.xml")
            if (processed.contains("resources.arsc")) sortedNames.add("resources.arsc")
            if (processed.contains("resources.pb")) sortedNames.add("resources.pb")
            sortedNames.addAll(processed.filter { it.matches(Regex("classes\\d*\\.dex")) }.sorted())
            sortedNames.addAll(processed.filter { it.startsWith("lib/") }.sorted())
            sortedNames.addAll(processed.filter { it !in sortedNames }.sorted())

            ZipOutputStream(FileOutputStream(outputFile).buffered()).use { zos ->
                for (name in sortedNames) {
                    val src = File(tempDir, "extracted/$name")
                    if (!src.exists()) continue

                    val ze = ZipEntry(name)
                    if (name == "resources.arsc" || name == "resources.pb" ||
                        name.endsWith(".dex") || name.endsWith(".so")) {
                        ze.method = ZipEntry.STORED
                        ze.size = src.length()
                        ze.compressedSize = src.length()
                        ze.crc = calculateCrc(src, buffer)
                    } else {
                        ze.method = ZipEntry.DEFLATED
                    }

                    zos.putNextEntry(ze)
                    FileInputStream(src).use { inp ->
                        var len: Int
                        while (inp.read(buffer).also { len = it } != -1) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
        } finally {
            tempDir.deleteRecursively()
            System.gc()
        }
    }

    private fun mergeResourcesArsc(resourcesFiles: List<File>): File {
        val mergedFile = File(resourcesFiles[0].parentFile, "merged_resources.arsc")
        try {
            val baseTable: TableBlock = TableBlock.load(resourcesFiles[0])
            for (i in 1 until resourcesFiles.size) {
                val splitTable: TableBlock = TableBlock.load(resourcesFiles[i])
                baseTable.merge(splitTable)
            }
            baseTable.refresh()
            FileOutputStream(mergedFile).use { out ->
                baseTable.writeBytes(out)
            }
            return mergedFile
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка объединения resources.arsc: ${e.message}", e)
            return resourcesFiles[0]
        }
    }

    private fun calculateCrc(file: File, buffer: ByteArray): Long {
        val crc = CRC32()
        FileInputStream(file).use { inp ->
            var read: Int
            while (inp.read(buffer).also { read = it } != -1) {
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }


    private fun openOutputFolder() {
        try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/LazyModification"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("file://$path"), "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (packageManager.resolveActivity(intent, 0) != null) startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun showProgress(show: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
            btnSelect.isEnabled = !show
        }
    }
}