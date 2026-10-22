package com.example.lazymodification

import com.example.lazymodification.utils.ZipOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipOptimizerTest {
    @Test
    fun recompressesTextEntryAndKeepsContent() {
        val dir = Files.createTempDirectory("zip_optimizer").toFile()
        val input = File(dir, "input.zip")
        val output = File(dir, "output.zip")
        val text = "Lazy Modification UltraZip test line\n".repeat(8000)
        try {
            ZipOutputStream(input.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets/text.txt"))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            val saved = ZipOptimizer.recompressUltra(input, output)
            assertTrue("saved=$saved", saved > 0)
            ZipFile(output).use { zip ->
                val entry = zip.getEntry("assets/text.txt")
                assertTrue(entry != null)
                assertEquals(text, zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
