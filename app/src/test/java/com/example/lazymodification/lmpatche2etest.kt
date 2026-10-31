package com.example.lazymodification

import com.example.lazymodification.utils.DexComparer
import com.example.lazymodification.utils.patches.ApkPatchRunner
import com.example.lazymodification.utils.patches.LmPatchContainer
import com.example.lazymodification.utils.patches.MethodPatchGenerator
import com.example.lazymodification.utils.patches.PatchFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Сквозная проверка собственного .lmpatch: генерация → упаковка → применение
 * к базовой версии → сравнение с новой версией.
 *
 * APK кладутся вручную в app/build/e2e (base.apk, new.apk); без них тест
 * пропускается. Это не part of normal CI, а проверка реального сценария.
 */
class LmPatchE2ETest {

    private val dir = File("build/e2e").absoluteFile

    @Test
    fun containerPatchAppliesAndReproducesNewVersion() {
        val base = File(dir, "base.apk")
        val updated = File(dir, "new.apk")
        assumeTrue("нужны $base и $updated", base.isFile && updated.isFile)
        val work = File(dir, "lm_work").apply { deleteRecursively(); mkdirs() }

        val snapshot = DexComparer.compare(base, updated, File(work, "cmp"), true, false, false, false)
            .associate { it.type to it.kind }
        assertTrue("должны быть изменения", snapshot.isNotEmpty())

        val result = MethodPatchGenerator.generate(base, updated, work, "e2e", snapshot)
        assertTrue("должны быть правила по методам", result.methodRules > 0)

        val inner = File(work, "payload.zip")
        PatchFormat.writeZip(inner, result.text, result.payloads)
        val patch = File(work, "e2e.lmpatch")
        LmPatchContainer.write(patch, result, inner)

        val patched = File(work, "patched.apk")
        ApkPatchRunner.apply(null, base, patch, patched, File(work, "apply"))
        assertTrue("патч должен создать APK", patched.isFile && patched.length() > 0L)

        val remaining = DexComparer.compare(patched, updated, File(work, "cmp_after"), true, false, false, false)
            .map { it.type }
        assertTrue("после применения остались расхождения: $remaining", remaining.isEmpty())
    }
}
