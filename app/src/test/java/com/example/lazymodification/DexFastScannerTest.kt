package com.example.lazymodification

import com.example.lazymodification.utils.DexFastScanner
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DexFastScannerTest {

    @Test
    fun findsSuperclassChain() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dfx_${System.nanoTime()}")
        assertTrue(dir.mkdirs() || dir.isDirectory)
        try {
            val dexFile = File(dir, "t.dex")
            val emptyAnn = emptySet<Annotation>()
            val emptyFields = emptyList<Field>()
            val emptyMethods = emptyList<Method>()
            val classes = listOf(
                ImmutableClassDef("LA;", 0x1, "LB;", emptyList<String>(), null, emptyAnn, emptyFields, emptyFields, emptyMethods, emptyMethods),
                ImmutableClassDef("LB;", 0x1, "Landroid/app/Application;", emptyList<String>(), null, emptyAnn, emptyFields, emptyFields, emptyMethods, emptyMethods)
            )
            org.jf.dexlib2.DexFileFactory.writeDexFile(dexFile.absolutePath, ImmutableDexFile(Opcodes.forApi(28), classes))

            DexFastScanner(dexFile).use { s ->
                assertEquals("LB;", s.superOf("LA;"))
                assertEquals("Landroid/app/Application;", s.superOf("LB;"))
                assertNull(s.superOf("LC;"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}