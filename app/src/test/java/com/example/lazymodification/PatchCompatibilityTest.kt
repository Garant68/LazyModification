package com.example.lazymodification

import com.example.lazymodification.utils.patches.PatchCompatibility
import com.example.lazymodification.utils.patches.PatchFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchCompatibilityTest {
    private fun document(pkg: String = "com.example.*", version: String = "1.2.*", min: Int = 100, max: Int = 200) =
        PatchFormat.parse(
            """
            [MIN_ENGINE_VER]
            2
            [PACKAGE]
            $pkg
            [VERSION_NAME]
            $version
            [MIN_VERSION_CODE]
            $min
            [MAX_VERSION_CODE]
            $max
            [DUMMY]
            NAME:
            test
            [/DUMMY]
            """.trimIndent()
        )

    @Test
    fun compatiblePackageVersionAndCode() {
        val result = PatchCompatibility.check(document(), "com.example.app", "1.2.7", 150)
        assertTrue(result.compatible)
    }

    @Test
    fun rejectsWrongPackage() {
        val result = PatchCompatibility.check(document(pkg = "org.other.*"), "com.example.app", "1.2.7", 150)
        assertFalse(result.compatible)
    }

    @Test
    fun rejectsWrongVersionCode() {
        val result = PatchCompatibility.check(document(min = 100, max = 200), "com.example.app", "1.2.7", 201)
        assertFalse(result.compatible)
    }

    @Test
    fun wildcardMatchesAnyPackageAndVersion() {
        val result = PatchCompatibility.check(document(pkg = "*", version = "*", min = 0, max = Int.MAX_VALUE), "any.app", "0.1", 1)
        assertTrue(result.compatible)
        assertEquals(1, PatchFormat.parse(document().text).rules.size)
    }
}
