package com.example.lazymodification

import com.example.lazymodification.utils.DexPatcher
import com.example.lazymodification.utils.StubEdit
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.Instruction11n
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StubMethodTest {

    private val opcodes = Opcodes.forApi(28)

    private fun baseMethod(name: String, ret: String, vararg insns: Instruction): ImmutableMethod {
        val impl = ImmutableMethodImplementation(1, insns.toList(), emptyList(), emptyList())
        return ImmutableMethod("Ltest/T;", name, emptyList(), ret, 0x1, null, null, impl)
    }

    private fun buildDex(): File {
        val mFlag = baseMethod("flag", "Z",
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction11x(Opcode.RETURN, 0))
        val mNum = baseMethod("num", "I",
            ImmutableInstruction11n(Opcode.CONST_4, 0, 5),
            ImmutableInstruction11x(Opcode.RETURN, 0))
        val mVoid = baseMethod("run", "V",
            ImmutableInstruction10x(Opcode.RETURN_VOID))
        val mObj = baseMethod("obj", "Ljava/lang/String;",
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
        val cd = ImmutableClassDef("Ltest/T;", 0x1, "Ljava/lang/Object;", null, null, null,
            emptyList(), emptyList(), emptyList(), listOf(mFlag, mNum, mVoid, mObj))
        val dexFile = File.createTempFile("stubtest", ".dex")
        DexFileFactory.writeDexFile(dexFile.absolutePath, ImmutableDexFile(opcodes, listOf(cd)))
        return dexFile
    }

    @Test
    fun stubsTypicalMethods() {
        val dex = buildDex()
        val out = File.createTempFile("stubout", ".dex")
        val applied = DexPatcher.stubMethods(dex, out, listOf(
            StubEdit("Ltest/T;", "flag", emptyList(), "Z", "false"),
            StubEdit("Ltest/T;", "num", emptyList(), "I", "0"),
            StubEdit("Ltest/T;", "run", emptyList(), "V", "void"),
            StubEdit("Ltest/T;", "obj", emptyList(), "Ljava/lang/String;", "null")
        ))
        assertEquals(4, applied)
        val back = DexFileFactory.loadDexFile(out, opcodes)
        var found: ClassDef? = null
        for (c in back.classes) {
            if (c.type == "Ltest/T;") found = c
        }
        val cd = found ?: throw AssertionError("class not found")
        fun implOf(name: String): List<Instruction> {
            for (m in cd.methods) {
                if (m.name == name) return m.implementation!!.instructions.toList()
            }
            throw AssertionError("method not found: " + name)
        }
        val flag = implOf("flag")
        assertEquals(2, flag.size)
        assertEquals(0, (flag[0] as Instruction11n).narrowLiteral)
        assertEquals("return", flag[1].opcode.name)
        val run = implOf("run")
        assertEquals(1, run.size)
        assertEquals("return-void", run[0].opcode.name)
        val obj = implOf("obj")
        assertEquals(2, obj.size)
        assertEquals("return-object", obj[1].opcode.name)
    }

    @Test
    fun missingAndMismatchedEditsAreSkipped() {
        val dex = buildDex()
        val out = File.createTempFile("stubout2", ".dex")
        val applied = DexPatcher.stubMethods(dex, out, listOf(
            StubEdit("Ltest/T;", "nope", emptyList(), "Z", "false"),
            StubEdit("Ltest/T;", "run", emptyList(), "V", "false")
        ))
        assertEquals(0, applied)
        assertTrue(out.exists() && out.length() > 0)
    }
}