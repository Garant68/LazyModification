package com.example.lazymodification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lazymodification.utils.patches.PatchEngine
import com.example.lazymodification.utils.patches.PatchFormat
import com.example.lazymodification.utils.patches.PatchMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PatchEngineAndroidTest {
    @Test
    fun expandsGroupVariablesOnAndroidRegexEngine() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "patch_engine_android_${UUID.randomUUID()}"
        ).apply { mkdirs() }
        try {
            val target = File(root, "values.txt").apply { writeText("value=42") }
            val archive = File(root, "patch.zip")
            PatchFormat.writeZip(
                archive,
                """
                [MATCH_ASSIGN]
                TARGET:
                values.txt
                MATCH:
                value=(\d+)
                REGEX:
                true
                ASSIGN:
                number = ${'$'}{GROUP1}
                [/MATCH_ASSIGN]

                [MATCH_REPLACE]
                TARGET:
                values.txt
                MATCH:
                value=\d+
                REGEX:
                true
                REPLACE:
                value=${'$'}{number}
                [/MATCH_REPLACE]
                """.trimIndent(),
                emptyMap()
            )
            PatchEngine(root, PatchMetadata("test.app")).apply(PatchFormat.read(archive), archive)
            assertEquals("value=42", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun executeDexExpandsVariablesBeforeScriptCall() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "patch_engine_dex_${UUID.randomUUID()}"
        ).apply { mkdirs() }
        try {
            val target = File(root, "values.txt").apply { writeText("value=42") }
            val archive = File(root, "patch.zip")
            PatchFormat.writeZip(
                archive,
                """
                [MATCH_ASSIGN]
                TARGET:
                values.txt
                MATCH:
                value=(\d+)
                REGEX:
                true
                ASSIGN:
                number = ${'$'}{GROUP1}
                [/MATCH_ASSIGN]

                [EXECUTE_DEX]
                SCRIPT:
                dummy.dex
                MAIN_CLASS:
                test.Script
                ENTRANCE:
                patch
                PARAM:
                ${'$'}{number}
                [/EXECUTE_DEX]
                """.trimIndent(),
                emptyMap()
            )
            var received: String? = null
            val engine = PatchEngine(root, PatchMetadata("test.app"), executeDex = { rule, _, _ ->
                received = rule.value("PARAM")
            })
            engine.apply(PatchFormat.read(archive), archive)
            assertEquals("42", received)
        } finally {
            root.deleteRecursively()
        }
    }
}
