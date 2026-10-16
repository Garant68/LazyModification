package com.example.lazymodification

import com.example.lazymodification.utils.DexPatcher
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DexVersionEndToEndTest {

    @Test
    fun patchedDexKeepsOriginalVersion() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dv_e2e_${System.nanoTime()}")
        assertTrue(dir.mkdirs() || dir.isDirectory)
        try {
            val inF = File(dir, "in.dex")
            val outF = File(dir, "out.dex")
            buildTinyDex(inF)
            assertEquals("035", String(inF.readBytes(), 4, 3, Charsets.US_ASCII))

            val res = DexPatcher.patchDex(inF, outF, patchRemoveAnalytics = true)
            assertTrue("analytics должен быть пропатчен", res.analyticsPatched > 0)

            val outB = outF.readBytes()
            assertEquals("версия выхода", "035", String(outB, 4, 3, Charsets.US_ASCII))

            val outDex = DexFileFactory.loadDexFile(outF, Opcodes.forApi(28))
            val cls = outDex.classes.first { it.type == "Ltest/lm/A;" }
            val m = cls.methods.first { it.name == "foo" }
            val strings = m.implementation!!.instructions
                .filterIsInstance<ReferenceInstruction>()
                .mapNotNull { (it.reference as? StringReference)?.string }
            assertTrue("URL должен быть подменён", strings.any { it.contains("127.0.0.1") })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun buildTinyDex(out: File) {
        val pool = DexPool(Opcodes.forApi(23))
        val insns = listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("https://www.google-analytics.com/collect")),
            ImmutableInstruction10x(Opcode.RETURN_VOID)
        )
        val foo = ImmutableMethod(
            "Ltest/lm/A;", "foo", emptyList(), "V", 0x1, emptySet(), emptySet(),
            ImmutableMethodImplementation(1, insns, emptyList(), emptySet())
        )
        val cls = ImmutableClassDef(
            "Ltest/lm/A;", 0x1, "Ljava/lang/Object;", emptyList(), null, emptySet(),
            emptyList(), emptyList(), listOf(foo), emptyList()
        )
        pool.internClass(cls)
        pool.writeTo(FileDataStore(out))
    }
}