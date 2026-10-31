package com.example.lazymodification

import com.example.lazymodification.utils.DexComparer
import com.example.lazymodification.utils.patches.MethodPatchGenerator
import com.example.lazymodification.utils.patches.PatchFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Сквозная проверка генератора на двух реальных сборках приложения.
 * Файлы кладутся вручную в app/build/e2e (base.apk, new.apk); без них тест пропускается.
 */
class MethodPatchE2ETest {

    private val dir = File("build/e2e").absoluteFile

    @Test
    fun generatedPatchReproducesNewVersion() {
        val base = File(dir, "base.apk")
        val updated = File(dir, "new.apk")
        assumeTrue("нужны $base и $updated", base.isFile && updated.isFile)
        val work = File(dir, "work").apply { deleteRecursively(); mkdirs() }

        var started = System.currentTimeMillis()
        val snapshot = DexComparer.compare(base, updated, File(work, "cmp"), true, false, false, false)
            .associate { it.type to it.kind }
        println("TIMING compare=${System.currentTimeMillis() - started} ms, changed=${snapshot.size}")

        started = System.currentTimeMillis()
        val result = MethodPatchGenerator.generate(base, updated, work, "test", snapshot)
        println("TIMING generate=${System.currentTimeMillis() - started} ms")
        assertTrue("должны появиться правила по методам", result.methodRules > 0)

        val zip = File(work, "patch.zip")
        PatchFormat.writeZip(zip, result.text, result.payloads)
        println("TIMING patch=${zip.length()} bytes, rules=${result.methodRules}, files=${result.fileRules}")

        started = System.currentTimeMillis()
        val remaining = MethodPatchGenerator.remainingTypes(base, zip, updated, work)
        println("TIMING verify=${System.currentTimeMillis() - started} ms")
        assertTrue("после применения остались расхождения: $remaining", remaining.isEmpty())
    }
}
