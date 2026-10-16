package com.example.lazymodification

import com.example.lazymodification.utils.DexPatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DexVersionNormalizeTest {

    @Test
    fun normalizeDexVersionDowngradesOnlyWhenInputIsOlder() {
        val dir = File(System.getProperty("java.io.tmpdir"), "dv_${System.nanoTime()}")
        assert(dir.mkdirs() || dir.isDirectory)
        try {
            check(dir, "035", "039", "035")
            check(dir, "039", "039", "039")
            check(dir, "041", "039", "039")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun check(dir: File, inVer: String, outVer: String, expected: String) {
        val inF = File(dir, "in_$inVer.dex")
        val outF = File(dir, "out_$outVer.dex")
        val inB = ByteArray(64)
        "dex\n$inVer".toByteArray(Charsets.US_ASCII).copyInto(inB, 0)
        inB[7] = 0
        inF.writeBytes(inB)
        val outB = ByteArray(64)
        "dex\n$outVer".toByteArray(Charsets.US_ASCII).copyInto(outB, 0)
        outB[7] = 0
        outF.writeBytes(outB)
        DexPatcher.normalizeDexVersion(inF, outF)
        val res = outF.readBytes()
        val got = String(res, 4, 3, Charsets.US_ASCII)
        assertEquals("in=$inVer out=$outVer", expected, got)
    }
}