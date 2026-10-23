package com.example.lazymodification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lazymodification.utils.SigningUtils
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SigningAndroidTest {
    @Test
    fun bundledTestKeyLoadsThroughBouncyCastle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyStore = SigningUtils.openKeyStore("PKCS12")
        context.assets.open("testkey.p12").use { keyStore.load(it, "android".toCharArray()) }
        assertTrue(keyStore.containsAlias("androiddebugkey"))
        assertNotNull(keyStore.getKey("androiddebugkey", "android".toCharArray()))
    }
}
