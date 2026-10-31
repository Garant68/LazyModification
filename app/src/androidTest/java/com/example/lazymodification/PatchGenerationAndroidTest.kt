package com.example.lazymodification

import androidx.test.platform.app.InstrumentationRegistry
import com.example.lazymodification.utils.DexComparer
import com.example.lazymodification.utils.FileSaver
import com.example.lazymodification.utils.patches.MethodPatchGenerator
import com.example.lazymodification.utils.patches.PatchFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Проверка на устройстве: слепок изменений, генерация патча, применение к базовой версии
 * и сохранение ZIP в Download/LazyModification/Патч.
 *
 * APK кладутся вручную: /sdcard/Download/lazy_e2e/base.apk и new.apk.
 */
class PatchGenerationAndroidTest {

    @Test
    fun generatesAppliesAndSavesPatch() {
        val dir = File("/sdcard/Download/lazy_e2e")
        val base = File(dir, "base.apk")
        val updated = File(dir, "new.apk")
        assumeTrue("нужны $base и $updated", base.isFile && updated.isFile)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "e2e_patch").apply { deleteRecursively(); mkdirs() }

        var started = System.currentTimeMillis()
        val snapshot = DexComparer.compare(base, updated, File(work, "cmp"), true, false, false, false)
            .associate { it.type to it.kind }
        println("TIMING compare=${System.currentTimeMillis() - started} ms, changed=${snapshot.size}")

        started = System.currentTimeMillis()
        val result = MethodPatchGenerator.generate(base, updated, work, "android-test", snapshot)
        println("TIMING generate=${System.currentTimeMillis() - started} ms, methods=${result.methodRules}, files=${result.fileRules}")
        assertTrue("должны быть правила по методам", result.methodRules > 0)

        val zip = File(work, "patch.zip")
        started = System.currentTimeMillis()
        PatchFormat.writeZip(zip, result.text, result.payloads)
        println("TIMING patch=${System.currentTimeMillis() - started} ms, ${zip.length()} bytes")

        started = System.currentTimeMillis()
        val remaining = MethodPatchGenerator.remainingTypes(base, zip, updated, work)
        println("TIMING verify=${System.currentTimeMillis() - started} ms")
        assertTrue("после применения остались расхождения: $remaining", remaining.isEmpty())

        val saved = FileSaver.savePatch(context, zip, "e2e_patch.zip")
        assertTrue("патч должен попасть в папку Патч: $saved", saved.contains("Патч"))
        assertTrue(
            "файл патча не найден (сохранено как $saved)",
            File("/sdcard/Download/LazyModification/Патч/e2e_patch.zip").isFile
        )
    }
}
