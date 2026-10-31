package com.example.lazymodification

import android.content.Intent
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Экран «Патчинг» должен открываться на реальном APK: чтение локалей, DPI и архитектур
 * идёт в фоне, а кнопка применения существует всегда.
 */
class PatchScreenAndroidTest {

    @Test
    fun patchScreenOpensWithApk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // При выключенном экране активити не доходит до RESUMED — тест не имеет смысла.
        val power = context.getSystemService(PowerManager::class.java)
        assumeTrue("экран выключен", power != null && power.isInteractive)
        val apk = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
        val intent = Intent(context, PatchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PatchActivity.EXTRA_APK_PATH, apk.absolutePath)

        val activity = instrumentation.startActivitySync(intent)
        try {
            assertNotNull(activity.findViewById<MaterialButton>(R.id.btnPatch))
            assertNotNull(activity.findViewById<android.widget.TextView>(R.id.customPatchSummary))
            assertNotNull(activity.findViewById<android.widget.TextView>(R.id.tvFileName))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
