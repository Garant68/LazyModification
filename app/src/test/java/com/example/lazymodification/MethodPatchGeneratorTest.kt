package com.example.lazymodification

import com.example.lazymodification.utils.patches.MethodPatchGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MethodPatchGeneratorTest {

    private fun file(text: String): File {
        val f = File.createTempFile("smali", ".smali")
        f.writeText(text)
        f.deleteOnExit()
        return f
    }

    private fun classFile(vararg methods: String): String = buildString {
        append(".class public Lcom/example/A;\n")
        append(".super Ljava/lang/Object;\n\n")
        methods.forEach { append(it).append("\n\n") }
    }

    private fun method(name: String, vararg body: String): String = buildString {
        append(".method public static ").append(name).append("()I\n")
        append("    .registers 1\n")
        body.forEach { append("    ").append(it).append('\n') }
        append(".end method")
    }

    private val path = "smali/com/example/A.smali"

    @Test
    fun unchangedClassHasNoRules() {
        val text = classFile(method("value", "const/4 v0, 0x1", "return v0"))
        val diff = MethodPatchGenerator.diffClass(path, file(text), file(text), false)
        assertFalse(diff.replaceWhole)
        assertTrue(diff.rules.isEmpty())
    }

    @Test
    fun changedBodyBecomesMethodRule() {
        val base = classFile(method("value", "const/4 v0, 0x1", "return v0"))
        val updated = classFile(method("value", "const/4 v0, 0x2", "return v0"))
        val diff = MethodPatchGenerator.diffClass(path, file(base), file(updated), false)
        assertFalse(diff.replaceWhole)
        assertEquals(1, diff.rules.size)
        val rule = diff.rules.single()
        assertEquals("com.example.A.value()I", rule.name)
        assertTrue(rule.match.startsWith(".method public static value()I"))
        assertTrue(rule.match.contains("const/4 v0, 0x1"))
        assertTrue(rule.match.endsWith(".end method"))
        assertTrue(rule.replace.contains("const/4 v0, 0x2"))
        assertFalse(rule.replace.contains("0x1"))
    }

    @Test
    fun largeMethodUsesCompactRegexRule() {
        val oldBody = Array(40) { "const/4 v0, 0x1" }
        val newBody = oldBody.copyOf().also { it[20] = "const/4 v0, 0x2" }
        val base = classFile(method("value", *oldBody))
        val updated = classFile(method("value", *newBody))
        val diff = MethodPatchGenerator.diffClass(path, file(base), file(updated), false)
        assertFalse(diff.replaceWhole)
        val rule = diff.rules.single()
        assertNotNull(rule.compactMatch)
        assertTrue(rule.compactMatch!!.startsWith("(?s)"))
    }

    @Test
    fun labelRenumberingDoesNotExpandInsertionPatch() {
        val base = classFile(
            method(
                "value",
                "const/4 v0, 0x0",
                "if-eqz v0, :cond_1",
                "const/4 v0, 0x1",
                "return v0",
                ":cond_1",
                "const/4 v0, 0x2",
                "return v0"
            )
        )
        val updated = classFile(
            method(
                "value",
                "const/4 v0, 0x1",
                "nop",
                "const/4 v0, 0x0",
                "if-eqz v0, :cond_3",
                "const/4 v0, 0x1",
                "return v0",
                ":cond_3",
                "const/4 v0, 0x2",
                "return v0"
            )
        )
        val diff = MethodPatchGenerator.diffClass(path, file(base), file(updated), false)
        assertFalse(diff.replaceWhole)
        assertEquals(1, diff.rules.size)
        val rule = diff.rules.single()
        assertNotNull(rule.compactMatch)
        assertTrue(rule.compactMatch!!.contains(".registers 1"))
        assertTrue(rule.compactMatch!!.length < 100)
        assertTrue(rule.compactReplace!!.contains("nop"))
    }

    @Test
    fun addedMethodBecomesLocalInsertionRule() {
        val base = classFile(method("value", "const/4 v0, 0x1", "return v0"))
        val updated = classFile(
            method("value", "const/4 v0, 0x1", "return v0"),
            method("extra", "const/4 v0, 0x0", "return v0")
        )
        val diff = MethodPatchGenerator.diffClass(path, file(base), file(updated), false)
        assertFalse(diff.replaceWhole)
        assertEquals(1, diff.rules.size)
        assertTrue(diff.rules.single().replace.contains(".method public static extra()I"))
    }

    @Test
    fun changedFieldReplacesWholeFile() {
        val base = classFile(method("value", "const/4 v0, 0x1", "return v0"))
        val updated = base.replace(
            ".super Ljava/lang/Object;\n",
            ".super Ljava/lang/Object;\n\n.field public static final VERSION:I = 0x2\n"
        )
        val diff = MethodPatchGenerator.diffClass(path, file(base), file(updated), false)
        assertTrue(diff.replaceWhole)
    }

    @Test
    fun forceWholeSkipsMethodRules() {
        val base = classFile(method("value", "const/4 v0, 0x1", "return v0"))
        val updated = classFile(method("value", "const/4 v0, 0x3", "return v0"))
        val diff = MethodPatchGenerator.diffClass(path, file(base), file(updated), true)
        assertTrue(diff.replaceWhole)
        assertTrue(diff.rules.isEmpty())
    }

    @Test
    fun variableSyntaxFallsBackToWholeFile() {
        val base = classFile(method("value", "const/4 v0, 0x1", "return v0"))
        val updated = classFile(method("value", "const/4 v0, 0x1", "return v0", "const-string v0, \"" + "$" + "{PACKAGE}\""))
        val diff = MethodPatchGenerator.diffClass(path, file(base), file(updated), false)
        assertTrue(diff.replaceWhole)
    }

    @Test
    fun parseSplitsMethodsAndSkeleton() {
        val text = classFile(
            method("value", "const/4 v0, 0x1", "return v0"),
            method("other", "const/4 v0, 0x0", "return v0")
        )
        val parsed = MethodPatchGenerator.parseSmali(text)
        assertEquals(2, parsed.methods.size)
        assertTrue(parsed.skeleton.contains(".class public Lcom/example/A;"))
        assertFalse(parsed.duplicate)
    }
}
