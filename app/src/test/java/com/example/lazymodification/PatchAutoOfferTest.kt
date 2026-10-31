package com.example.lazymodification

import com.example.lazymodification.utils.patches.PatchAutoOffer
import com.example.lazymodification.utils.patches.PatchFormat
import com.example.lazymodification.utils.patches.LmPatchContainer
import com.example.lazymodification.utils.patches.MethodPatchGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PatchAutoOfferTest {

    private val matching = """
        [MIN_ENGINE_VER]
        2

        [PACKAGE]
        com.example.app

        [VERSION_NAME]
        1.28

        [MIN_VERSION_CODE]
        129

        [MAX_VERSION_CODE]
        129

        [DUMMY]
        [/DUMMY]
    """.trimIndent()

    private fun directory(): File = Files.createTempDirectory("patch_offer").toFile()

    private fun patch(dir: File, name: String, text: String): File =
        File(dir, name).apply { PatchFormat.writeZip(this, text, emptyMap()) }

    @Test
    fun findsOnlyMatchingPatch() {
        val dir = directory()
        val expected = patch(dir, "match.zip", matching)
        patch(dir, "other.zip", matching.replace("com.example.app", "com.other.app"))
        File(dir, "broken.txt").writeText("это не патч")

        val found = PatchAutoOffer.find(dir, "com.example.app", "1.28", 129)

        assertEquals(listOf(expected), found)
    }

    @Test
    fun skipsPatchForAnotherVersionCode() {
        val dir = directory()
        patch(dir, "match.zip", matching)
        assertTrue(PatchAutoOffer.find(dir, "com.example.app", "1.28", 130).isEmpty())
    }

    @Test
    fun withoutPackageNothingIsOffered() {
        val dir = directory()
        patch(dir, "match.zip", matching)
        assertTrue(PatchAutoOffer.find(dir, null, "1.28", 129).isEmpty())
    }

    @Test
    fun missingDirectoryIsSafe() {
        assertTrue(PatchAutoOffer.find(File(directory(), "absent"), "com.example.app", "1.28", 129).isEmpty())
    }

    @Test
    fun extensionFilterHidesGenericZipPatches() {
        val dir = directory()
        patch(dir, "match.zip", matching)
        assertTrue(
            PatchAutoOffer.find(
                dir, "com.example.app", "1.28", 129,
                LmPatchContainer.EXTENSION
            ).isEmpty()
        )
    }

    @Test
    fun findsLmPatchByPackage() {
        val dir = directory()
        val payload = File(dir, "payload.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = MethodPatchGenerator.Result(
            text = "",
            payloads = emptyMap(),
            methodRules = 1,
            fileRules = 0,
            removedRules = 0,
            packageName = "com.example.app",
            versionName = "1.28",
            versionCode = 129,
            newVersionName = "1.29",
            newVersionCode = 130
        )
        val lmpatch = File(dir, "match.lmpatch")
        LmPatchContainer.write(lmpatch, result, payload)
        lmpatch.copyTo(File(dir, "match.lmpatch.zip"), overwrite = true)

        assertEquals(
            listOf(lmpatch),
            PatchAutoOffer.find(
                dir, "com.example.app", "1.28", 129,
                LmPatchContainer.EXTENSION
            )
        )
        assertTrue(
            PatchAutoOffer.find(
                dir, "com.other.app", "1.28", 129,
                LmPatchContainer.EXTENSION
            ).isEmpty()
        )
    }
}
