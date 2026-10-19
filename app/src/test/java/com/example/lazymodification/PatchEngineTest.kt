package com.example.lazymodification

import com.example.lazymodification.utils.patches.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PatchEngineTest {
    private fun work(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("patch_test").toFile()
        try { action(dir) } finally { dir.deleteRecursively() }
    }
    private fun put(root: File, path: String, value: String): File = File(root, path).apply { parentFile.mkdirs(); writeText(value) }
    private fun zip(root: File, text: String, payloads: Map<String, File> = emptyMap()): File = File(root, "patch.zip").also { PatchFormat.writeZip(it, text.trimIndent(), payloads) }
    private fun apply(root: File, archive: File) = PatchEngine(root, PatchMetadata("test.app")).apply(PatchFormat.read(archive), archive)

    @Test fun literalGroupsEmptyReplacementAndMultiDexTargets() = work { dir ->
        val root = File(dir, "project").apply { mkdirs() }
        val original = "invoke a\$Inner;\\n\nremove me\n"
        val first = put(root, "smali/test/A.smali", original)
        val second = put(root, "smali_classes2/test/B.smali", original)
        val patch = zip(dir, """
            [MATCH_REPLACE]
            TARGET:
            smali*/*.smali
            MATCH:
            (invoke .*)
            REGEX:
            true
            REPLACE:
            #${'$'}{GROUP1}
            [/MATCH_REPLACE]
            [MATCH_REPLACE]
            TARGET:
            smali*/*.smali
            MATCH:
            remove me
            REPLACE:
            [/MATCH_REPLACE]
        """)
        val stats = apply(root, patch)
        assertEquals(4, stats.matches)
        assertEquals("#invoke a\$Inner;\\n\n\n", first.readText())
        assertEquals(first.readText(), second.readText())
    }
    @Test fun assignmentConditionalJumpAndPayloadRoundtrip() = work { dir ->
        val root = File(dir, "project").apply { mkdirs() }
        put(root, "assets/in.txt", "hello42")
        val payload = put(dir, "data", "new payload")
        val patch = zip(dir, """
            [MATCH_ASSIGN]
            TARGET:
            assets/in.txt
            MATCH:
            hello(\d+)
            REGEX:
            true
            ASSIGN:
            number = ${'$'}{GROUP1}
            [/MATCH_ASSIGN]
            [ADD_FILES]
            SOURCE:
            payload.txt
            TARGET:
            assets/${'$'}{number}.txt
            [/ADD_FILES]
            [MATCH_GOTO]
            TARGET:
            assets/in.txt
            MATCH:
            hello
            GOTO:
            end
            [/MATCH_GOTO]
            [REMOVE_FILES]
            TARGET:
            assets/in.txt
            [/REMOVE_FILES]
            [DUMMY]
            NAME:
            end
            [/DUMMY]
        """, mapOf("payload.txt" to payload))
        apply(root, patch)
        assertEquals("new payload", File(root, "assets/42.txt").readText())
        assertTrue(File(root, "assets/in.txt").isFile)
    }
    @Test fun rejectsTraversalPackageMismatchAndInfiniteLoop() = work { dir ->
        for (path in listOf("../secret", "/tmp/file", "assets/../../secret", "a\\b", "C:/x")) {
            try { PatchFormat.child(dir, path); fail(path) } catch (_: PatchException) { }
        }
        val wrongPackage = zip(dir, "[PACKAGE]\nother.app\n[DUMMY]\n[/DUMMY]")
        try { apply(dir, wrongPackage); fail() } catch (e: PatchException) { assertTrue(e.message!!.contains("other.app")) }
        val loop = zip(dir, "[GOTO]\nNAME:\nloop\nGOTO:\nloop\n[/GOTO]")
        try { apply(dir, loop); fail() } catch (e: PatchException) { assertTrue(e.message!!.contains("GOTO")) }
    }
    @Test fun mergeRemapsIdsAndReplacesResourcesAcrossDifferentFilenames() = work { dir ->
        val root = File(dir, "project").apply { mkdirs() }
        put(root,"res/values/public.xml", """<resources><public type="string" name="existing" id="0x7f010000"/></resources>""")
        put(root,"res/values/strings.xml", """<resources><string name="existing">old</string></resources>""")
        val payload = File(dir, "resources.zip")
        ZipOutputStream(payload.outputStream()).use { zip ->
            mapOf(
                "res/values/public.xml" to """<resources><public type="string" name="existing" id="0x7f030003"/><public type="string" name="added" id="0x7f010000"/></resources>""",
                "res/values/other.xml" to """<resources><string name="existing">updated</string><string name="added">new</string></resources>""",
                "smali/test/Added.smali" to "const v0, 0x7f010000\nconst v1, 0x7f030003"
            ).forEach { (path, text) -> zip.putNextEntry(ZipEntry(path));zip.write(text.toByteArray());zip.closeEntry() }
        }
        apply(root, zip(dir,"[MERGE]\nSOURCE:\nresources.zip\n[/MERGE]",mapOf("resources.zip" to payload)))
        assertTrue(File(root,"res/values/strings.xml").readText().contains("updated"))
        assertFalse(File(root,"res/values/other.xml").readText().contains("existing"))
        assertTrue(File(root,"res/values/public.xml").readText().contains("0x7f010001"))
        assertEquals("const v0, 0x7f010001\nconst v1, 0x7f010000",File(root,"smali/test/Added.smali").readText())
    }
    @Test fun extractedPayloadCannotEscapeTargetDirectory() = work { dir ->
        val root = File(dir,"project").apply { mkdirs() }
        val nested = File(dir,"nested.zip")
        ZipOutputStream(nested.outputStream()).use { it.putNextEntry(ZipEntry("../escaped.txt"));it.write(1);it.closeEntry() }
        val patch = zip(dir,"[ADD_FILES]\nSOURCE:\nnested.zip\nTARGET:\nassets\nEXTRACT:\ntrue\n[/ADD_FILES]",mapOf("nested.zip" to nested))
        try { apply(root,patch);fail() } catch (_:PatchException) { }
        assertFalse(File(root,"escaped.txt").exists())
    }
}
