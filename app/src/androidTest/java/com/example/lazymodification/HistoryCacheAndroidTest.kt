package com.example.lazymodification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lazymodification.utils.PatchHistory
import com.example.lazymodification.utils.patches.ApkProjectCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryCacheAndroidTest {
    @Test
    fun projectCacheRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "cache_source_${UUID.randomUUID()}.apk").apply {
            writeBytes(ByteArray(4096) { it.toByte() })
        }
        val work1 = File(context.cacheDir, "cache_work1_${UUID.randomUUID()}").apply { mkdirs() }
        val work2 = File(context.cacheDir, "cache_work2_${UUID.randomUUID()}").apply { mkdirs() }
        val project = File(work1, "project").apply { mkdirs() }
        File(project, "smali/com/example").mkdirs()
        File(project, "smali/com/example/A.smali").writeText(".class Lcom/example/A;\n")
        File(project, "AndroidManifest.xml").writeText("<manifest/>")
        val resources = File(work1, "resources").apply { mkdirs() }
        File(resources, "res/values").mkdirs()
        File(resources, "res/values/strings.xml").writeText("<resources/>")
        val before = mapOf("smali" to mapOf("com/example/A.smali" to "abc"))
        val baseline = mapOf("AndroidManifest.xml" to "def")

        ApkProjectCache.save(
            context, source, project, resources, listOf("AndroidManifest.xml", "smali/com/example/A.smali"),
            mapOf("smali" to 35), before, baseline
        )
        val cached = ApkProjectCache.load(context, source, work2)
        assertTrue(cached != null)
        assertEquals("abc", cached!!.beforeSmali["smali"]?.get("com/example/A.smali"))
        assertEquals("def", cached.baseline["AndroidManifest.xml"])
        assertTrue(File(work2, "project/smali/com/example/A.smali").isFile)
        assertTrue(File(work2, "resources/res/values/strings.xml").isFile)

        File(context.filesDir, "apk_project_cache/${ApkProjectCache.key(source)}").deleteRecursively()
        source.delete()
        work1.deleteRecursively()
        work2.deleteRecursively()
    }

    @Test
    fun historyRecordRestoreAndDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "history_${UUID.randomUUID()}.apk").apply {
            writeText("original-apk-content")
        }
        val target = File(context.cacheDir, "restored_${UUID.randomUUID()}.apk")
        val entry = PatchHistory.record(context, source, "Реклама, UltraZip", "com.test", "1.0", 1)
        assertTrue(entry != null)
        val found = PatchHistory.list(context).firstOrNull { it.id == entry!!.id }
        assertTrue(found != null)
        assertTrue(PatchHistory.restore(found!!, target))
        assertEquals("original-apk-content", target.readText())
        PatchHistory.delete(context, found)
        source.delete()
        target.delete()
    }
}
