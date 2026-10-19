package com.example.lazymodification

import com.example.lazymodification.utils.patches.*
import com.reandroid.apk.ApkModule
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPatchRunnerTest {
    private fun work(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("apk_patch").toFile()
        try { action(dir) } finally { dir.deleteRecursively() }
    }
    private fun resource(dir: File, name: String): File = File(dir,name).apply {
        ApkPatchRunnerTest::class.java.getResourceAsStream("/patches/$name")!!.use { source -> outputStream().use { source.copyTo(it) } }
    }
    private fun smali(dir: File, path: String, text: String) = File(dir,path).apply { parentFile.mkdirs();writeText(text.trimIndent()) }
    private fun assemble(dir: File, out: File) {
        assertTrue(Smali.assemble(SmaliOptions().apply { apiLevel=23;jobs=1;outputDexFile=out.path },dir.path))
    }
    private fun fixture(dir: File): File {
        val main = File(dir,"main")
        smali(main,"Ads.smali", """
            .class public Lcom/android/facebook/ads;
            .super Ljava/lang/Object;
            .method public static get(Landroid/content/Context;)V
                .registers 1
                invoke-static {p0}, Ltest/Injected;->show(Landroid/content/Context;)V
                return-void
            .end method
        """)
        val secondary = File(dir,"secondary")
        smali(secondary,"Main.smali", """
            .class public Ltest/patch/fixture/MainActivity;
            .super Landroid/app/Activity;
            .method public test()Z
                .locals 1
                invoke-static {}, Ltest/License;->isPremium()Z
                move-result v0
                return v0
            .end method
        """)
        val dex=File(dir,"classes.dex");val dex2=File(dir,"classes2.dex")
        assemble(main,dex);assemble(secondary,dex2)
        val fixture=resource(dir,"fixture.apk")
        return File(dir,"source.apk").also { output ->
            ZipOutputStream(output.outputStream()).use { zip ->
                ZipFile(fixture).use { original -> original.entries().asSequence().forEach { e ->
                    zip.putNextEntry(ZipEntry(e.name));original.getInputStream(e).use { it.copyTo(zip) };zip.closeEntry()
                } }
                mapOf("classes.dex" to dex.readBytes(),"classes2.dex" to dex2.readBytes(),
                    "assets/untouched.bin" to ByteArray(131) { it.toByte() },
                    "lib/arm64-v8a/libfixture.so" to ByteArray(211) { (it*3).toByte() },
                    "META-INF/OLD.SF" to "old signature".toByteArray(),
                    "META-INF/services/keep" to "keep service".toByteArray()).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
                }
            }
        }
    }
    private fun entry(file: File, name: String) = ZipFile(file).use { z -> z.getInputStream(z.getEntry(name)).use { it.readBytes() } }
    private fun writePatch(dir: File, text: String) = File(dir,"custom.zip").also { PatchFormat.writeZip(it,text,emptyMap()) }
    @Test fun suppliedExamplesPatchMultiDexAndPreserveUnrelatedEntries() = work { dir ->
        val input=fixture(dir)
        val premium=resource(dir,"IsPremium-V.2.zip")
        val first=File(dir,"premium.apk")
        val result=ApkPatchRunner.apply(null,input,premium,first,File(dir,"premium-work"))
        assertTrue(result.matches>0)
        val dex2=File(dir,"read.dex").apply { writeBytes(entry(first,"classes2.dex")) }
        val caller=DexFileFactory.loadDexFile(dex2,null).classes.single().methods.first { it.name=="test" }
        val calls=caller.implementation!!.instructions.filterIsInstance<ReferenceInstruction>().mapNotNull { it.reference as? MethodReference }
        assertTrue(calls.any { it.definingClass=="Lapk/tool/patcher/Premium;" && it.name=="Premium" })
        val mainDex=File(dir,"main-read.dex").apply { writeBytes(entry(first,"classes.dex")) }
        assertTrue(DexFileFactory.loadDexFile(mainDex,null).classes.any { it.type=="Lapk/tool/patcher/Premium;" })
        val second=File(dir,"noalert.apk")
        assertTrue(ApkPatchRunner.apply(null,first,resource(dir,"NoAlert.zip"),second,File(dir,"alert-work")).matches>0)
        mainDex.writeBytes(entry(second,"classes.dex"))
        val get=DexFileFactory.loadDexFile(mainDex,null).classes.first { it.type=="Lcom/android/facebook/ads;" }.methods.single()
        assertEquals(1,get.implementation!!.instructions.count())
        for(name in listOf("AndroidManifest.xml","resources.arsc","assets/untouched.bin","lib/arm64-v8a/libfixture.so","META-INF/services/keep"))
            assertArrayEquals(name,entry(input,name),entry(second,name))
        assertArrayEquals(entry(first,"classes2.dex"),entry(second,"classes2.dex"))
        ZipFile(second).use { assertNull(it.getEntry("META-INF/OLD.SF")) }
        assertEquals("035",String(entry(second,"classes.dex"),4,3))
    }
    @Test fun invalidSmaliDoesNotProduceApkOrModifySource() = work { dir ->
        val input=fixture(dir);val original=input.readBytes();val output=File(dir,"bad.apk")
        val patch=writePatch(dir,"[MATCH_REPLACE]\nTARGET:\nsmali*/*.smali\nMATCH:\nreturn-void\nREPLACE:\nnot-an-opcode\n[/MATCH_REPLACE]")
        try { ApkPatchRunner.apply(null,input,patch,output,File(dir,"bad-work"));fail() } catch (_:PatchException) { }
        assertFalse(output.exists());assertArrayEquals(original,input.readBytes())
    }
    @Test fun noMatchesCopiesApkWithoutChangingItsSignatureOrContents() = work { dir ->
        val input=fixture(dir);val output=File(dir,"noop.apk")
        val patch=writePatch(dir,"[MATCH_REPLACE]\nTARGET:\nassets/*.txt\nMATCH:\nabsent\nREPLACE:\nunused\n[/MATCH_REPLACE]")
        assertFalse(ApkPatchRunner.apply(null,input,patch,output,File(dir,"noop-work")).changed)
        assertArrayEquals(input.readBytes(),output.readBytes())
    }
    @Test fun decodedXmlCanBeRebuiltAndKeepsDexAndAssets() = work { dir ->
        val input=fixture(dir);val output=File(dir,"xml.apk")
        val patch=writePatch(dir,"[MATCH_REPLACE]\nTARGET:\nres/values/*.xml\nMATCH:\nPatch fixture\nREPLACE:\nUpdated fixture\n[/MATCH_REPLACE]")
        val result=ApkPatchRunner.apply(null,input,patch,output,File(dir,"xml-work"))
        assertTrue(result.matches>0)
        ApkModule.loadApkFile(output).use { assertEquals("test.patch.fixture",it.androidManifest.packageName) }
        assertArrayEquals(entry(input,"classes.dex"),entry(output,"classes.dex"))
        assertArrayEquals(entry(input,"classes2.dex"),entry(output,"classes2.dex"))
        assertArrayEquals(entry(input,"assets/untouched.bin"),entry(output,"assets/untouched.bin"))
        assertFalse(entry(input,"resources.arsc").contentEquals(entry(output,"resources.arsc")))
    }
}
