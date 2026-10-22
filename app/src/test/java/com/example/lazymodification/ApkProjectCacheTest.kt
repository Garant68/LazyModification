package com.example.lazymodification

import com.example.lazymodification.utils.patches.ApkProjectCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ApkProjectCacheTest {
    @Test
    fun savesAndLoadsProjectMetadata() {
        val root = Files.createTempDirectory("apk_cache_root").toFile()
        val work1 = Files.createTempDirectory("apk_cache_work1").toFile()
        val work2 = Files.createTempDirectory("apk_cache_work2").toFile()
        val source = File(work1, "source.apk").apply {
            writeBytes(ByteArray(2048) { (it % 251).toByte() })
        }
        val project = File(work1, "project").apply { mkdirs() }
        File(project, "smali/com/example").mkdirs()
        File(project, "smali/com/example/A.smali").writeText(".class Lcom/example/A;\n")
        File(project, "AndroidManifest.xml").writeText("<manifest/>")
        val resources = File(work1, "resources").apply { mkdirs() }
        File(resources, "res/values").mkdirs()
        File(resources, "res/values/strings.xml").writeText("<resources/>")

        ApkProjectCache.save(
            root, source, project, resources,
            listOf("AndroidManifest.xml", "smali/com/example/A.smali"),
            mapOf("smali" to 35),
            mapOf("smali" to mapOf("com/example/A.smali" to "abc")),
            mapOf("AndroidManifest.xml" to "def")
        )
        val cached = ApkProjectCache.load(root, source, work2)
        assertTrue(cached != null)
        assertEquals("abc", cached!!.beforeSmali["smali"]?.get("com/example/A.smali"))
        assertEquals("def", cached.baseline["AndroidManifest.xml"])
        assertEquals(35, cached.dexApis["smali"])
        assertTrue(File(work2, "project/smali/com/example/A.smali").isFile)
        assertTrue(File(work2, "resources/res/values/strings.xml").isFile)
        root.deleteRecursively()
        work1.deleteRecursively()
        work2.deleteRecursively()
    }
}
