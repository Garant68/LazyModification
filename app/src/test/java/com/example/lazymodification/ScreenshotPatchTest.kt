package com.example.lazymodification

import com.example.lazymodification.utils.DexPatcher
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.formats.Instruction21s
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21s
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenshotPatchTest {

    private fun methodWith(vararg insns: Instruction): ImmutableMethod {
        val impl = ImmutableMethodImplementation(4, insns.toList(), emptyList(), emptyList())
        return ImmutableMethod("Ltest/Test;", "foo", emptyList(), "V", 0x9, null, null, impl)
    }

    private fun windowRef(name: String, n: Int): ImmutableMethodReference {
        val params = if (n == 1) listOf("I") else listOf("I", "I")
        return ImmutableMethodReference("Landroid/view/Window;", name, params, "V")
    }

    private fun invoke(regs: IntArray, ref: ImmutableMethodReference, count: Int): ImmutableInstruction35c =
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, count,
            regs.getOrElse(0) { 0 }, regs.getOrElse(1) { 0 }, regs.getOrElse(2) { 0 },
            regs.getOrElse(3) { 0 }, regs.getOrElse(4) { 0 }, ref)

    @Test
    fun removesSecureFlagFromConst16() {
        val m = methodWith(
            ImmutableInstruction21s(Opcode.CONST_16, 1, 0x2000),
            invoke(intArrayOf(0, 1), windowRef("addFlags", 1), 2)
        )
        val out = DexPatcher.patchScreenshots(m) {}
        assertNotSame(m, out)
        val first = out.implementation!!.instructions.first() as Instruction21s
        assertEquals(0, first.narrowLiteral)
    }

    @Test
    fun keepsOtherFlags() {
        val m = methodWith(
            ImmutableInstruction21s(Opcode.CONST_16, 1, 0x2080),
            invoke(intArrayOf(0, 1), windowRef("addFlags", 1), 2)
        )
        val out = DexPatcher.patchScreenshots(m) {}
        val first = out.implementation!!.instructions.first() as Instruction21s
        assertEquals(0x80, first.narrowLiteral)
    }

    @Test
    fun ignoresNonSecureFlag() {
        val m = methodWith(
            ImmutableInstruction21s(Opcode.CONST_16, 1, 0x80),
            invoke(intArrayOf(0, 1), windowRef("addFlags", 1), 2)
        )
        val out = DexPatcher.patchScreenshots(m) {}
        assertSame(m, out)
    }

    @Test
    fun handlesSetFlags() {
        val m = methodWith(
            ImmutableInstruction21s(Opcode.CONST_16, 1, 0x2000),
            ImmutableInstruction21s(Opcode.CONST_16, 2, 0x2000),
            invoke(intArrayOf(0, 1, 2), windowRef("setFlags", 2), 3)
        )
        val out = DexPatcher.patchScreenshots(m) {}
        assertNotSame(m, out)
        val first = out.implementation!!.instructions.first() as Instruction21s
        assertEquals(0, first.narrowLiteral)
    }

    @Test
    fun untouchedWhenConstMissing() {
        val m = methodWith(
            invoke(intArrayOf(0, 1), windowRef("addFlags", 1), 2)
        )
        val out = DexPatcher.patchScreenshots(m) {}
        assertSame(m, out)
    }
}