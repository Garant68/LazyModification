package com.example.lazymodification.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry

/**
 * Замена иконки приложения (как в ApkEditor / APKTool M):
 *
 * 1. Из AndroidManifest берём android:icon / android:roundIcon (@mipmap/xxx или @drawable/xxx).
 * 2. Для каждого ресурса находим ВСЕ его файлы во всех плотностях (res/mipmap-*dpi/...).
 * 3. Каждый файл заменяем на новую картинку, масштабируя её под ИСХОДНЫЙ размер файла
 *    (набор плотностей сохраняется как в оригинале).
 * 4. Дополнительно заменяем <имя>_foreground — слой адаптивной иконки (API 26+).
 * 5. Файлы .xml (adaptive-icon) не трогаем.
 *
 * Замена идёт прямо во входных потоках ApkModule — последующий writeApk запишет новые байты.
 */
object IconReplacer {

    private val ICON_REGEX = Regex("""android:icon="@(\w+)/([\w.]+)"""")
    private val ROUND_REGEX = Regex("""android:roundIcon="@(\w+)/([\w.]+)"""")

    fun replaceIcon(apkModule: ApkModule, newIconFile: File, onLog: (String) -> Unit): Int {
        val newIcon = BitmapFactory.decodeFile(newIconFile.absolutePath)
            ?: throw IllegalStateException("Не удалось прочитать изображение")
        try {
            val pkg = apkModule.tableBlock.pickOne()
                ?: throw IllegalStateException("resources.arsc: пакет не найден")
            val manifest = apkModule.androidManifest
                ?: throw IllegalStateException("AndroidManifest.xml не найден")
            manifest.setPackageBlock(pkg)
            val xml = manifest.serializeToXml()

            val targets = LinkedHashSet<Pair<String, String>>()
            ICON_REGEX.find(xml)?.let {
                val type = it.groupValues[1]
                val name = it.groupValues[2]
                targets.add(type to name)
                targets.add("mipmap" to name + "_foreground")
                targets.add("drawable" to name + "_foreground")
            }
            ROUND_REGEX.find(xml)?.let {
                targets.add(it.groupValues[1] to it.groupValues[2])
            }
            if (targets.isEmpty()) {
                onLog("⚠️ android:icon не найден в манифесте")
                return 0
            }

            var replaced = 0
            for ((type, name) in targets) {
                replaced += replaceResourceFiles(apkModule, pkg, type, name, newIcon)
            }
            onLog("🎨 Иконка заменена: $replaced файлов")
            return replaced
        } finally {
            newIcon.recycle()
        }
    }

    private fun replaceResourceFiles(
        apkModule: ApkModule, pkg: PackageBlock, type: String, name: String, newIcon: Bitmap
    ): Int {
        var count = 0
        val entries = pkg.getResources(type)
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.getName() != name) continue
            val values = entry.getStringValues()
            while (values.hasNext()) {
                val raw = values.next() ?: continue
                val path = raw.trimStart('/')
                if (!path.startsWith("res/")) continue
                if (path.endsWith(".xml", ignoreCase = true)) continue // adaptive-icon xml
                val source = findSource(apkModule, path) ?: continue
                val originalBytes = try {
                    source.openStream().use { it.readBytes() }
                } catch (e: Exception) {
                    null
                } ?: continue
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, opts)
                val w = opts.outWidth
                val h = opts.outHeight
                if (w <= 0 || h <= 0) continue
                val scaled = if (newIcon.width == w && newIcon.height == h) newIcon
                else Bitmap.createScaledBitmap(newIcon, w, h, true)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                if (scaled !== newIcon) scaled.recycle()
                apkModule.removeInputSource(path)
                val src = ByteInputSource(out.toByteArray(), path)
                src.setMethod(ZipEntry.DEFLATED)
                apkModule.add(src)
                count++
            }
        }
        return count
    }

    private fun findSource(apkModule: ApkModule, path: String) =
        apkModule.listInputSources().firstOrNull { (it.alias ?: it.name) == path }
}
