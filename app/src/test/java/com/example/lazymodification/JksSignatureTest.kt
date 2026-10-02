package com.example.lazymodification

import com.example.lazymodification.utils.JksSupport
import com.example.lazymodification.utils.SignatureCreator
import org.bouncycastle.x509.X509V3CertificateGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Тест формата JKS: наш writer проверяется настоящим JDK-провайдером "JKS",
 * наш reader — файлом, созданным JDK. Плюс негативные проверки паролей.
 * (юнит-тесты выполняются на десктопе, где доступны и JDK, и BouncyCastle)
 */
class JksSignatureTest {

    private val alias = "mykey"
    private val storePw = "secret123"
    private val keyPw = "secret123"

    @Suppress("DEPRECATION")
    private fun makeSelfSigned(): Pair<KeyPair, X509Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val gen = X509V3CertificateGenerator()
        gen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
        val dn = X500Principal("CN=JksUnitTest, O=LazyModification")
        gen.setIssuerDN(dn)
        gen.setSubjectDN(dn)
        gen.setPublicKey(kp.public)
        gen.setNotBefore(Date(System.currentTimeMillis() - 86400000L))
        gen.setNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000))
        gen.setSignatureAlgorithm("SHA256WithRSA")
        return kp to gen.generate(kp.private)
    }

    /** SUN JKS должен прочитать файл, созданный нашим writer-ом. */
    @Test
    fun sunReadsOurJks() {
        val bytes = SignatureCreator.createKeystore(
            type = SignatureCreator.TYPE_JKS,
            alias = alias,
            storePassword = storePw,
            keyPassword = keyPw,
            validityYears = 30,
            commonName = "JksUnitTest"
        )
        val sun = KeyStore.getInstance("JKS")
        sun.load(ByteArrayInputStream(bytes), storePw.toCharArray())
        assertTrue("alias должен присутствовать", sun.containsAlias(alias))
        val key = sun.getKey(alias, keyPw.toCharArray()) as RSAPrivateKey
        val cert = sun.getCertificate(alias) as X509Certificate
        val pub = cert.publicKey as RSAPublicKey
        assertEquals("ключ должен совпадать с сертификатом", pub.modulus, key.modulus)
        assertEquals(1, sun.getCertificateChain(alias).size)
    }

    /** Наш reader должен прочитать файл, созданный SUN (JDK). */
    @Test
    fun ourReaderReadsSunJks() {
        val (kp, cert) = makeSelfSigned()
        val sun = KeyStore.getInstance("JKS")
        sun.load(null, null)
        sun.setKeyEntry(alias, kp.private, keyPw.toCharArray(), arrayOf<Certificate>(cert))
        val out = ByteArrayOutputStream()
        sun.store(out, storePw.toCharArray())

        val mine = JksSupport.openJks()
        mine.load(ByteArrayInputStream(out.toByteArray()), storePw.toCharArray())
        assertTrue(mine.containsAlias(alias))
        val key = mine.getKey(alias, keyPw.toCharArray()) as RSAPrivateKey
        assertEquals("ключ должен совпасть", (kp.private as RSAPrivateKey).modulus, key.modulus)
        assertNotNull(mine.getCertificateChain(alias))
        assertEquals(1, mine.getCertificateChain(alias).size)
    }

    /** Взаимная проверка: наш writer -> наш reader. */
    @Test
    fun selfRoundTrip() {
        val (kp, cert) = makeSelfSigned()
        val bytes = JksSupport.createJks(alias, kp.private, cert, keyPw, storePw)
        val mine = JksSupport.openJks()
        mine.load(ByteArrayInputStream(bytes), storePw.toCharArray())
        val key = mine.getKey(alias, keyPw.toCharArray()) as RSAPrivateKey
        assertEquals((kp.private as RSAPrivateKey).modulus, key.modulus)
    }

    /** Неверные пароли должны отвергаться. */
    @Test
    fun wrongPasswordsRejected() {
        val bytes = SignatureCreator.createKeystore(
            type = SignatureCreator.TYPE_JKS,
            alias = alias,
            storePassword = storePw,
            keyPassword = keyPw,
            validityYears = 30,
            commonName = "JksUnitTest"
        )
        try {
            val ks = JksSupport.openJks()
            ks.load(ByteArrayInputStream(bytes), "wrong".toCharArray())
            fail("должна быть ошибка целостности")
        } catch (expected: java.io.IOException) {
            // ok
        }
        try {
            val ks = JksSupport.openJks()
            ks.load(ByteArrayInputStream(bytes), storePw.toCharArray())
            ks.getKey(alias, "wrong".toCharArray())
            fail("должна быть ошибка восстановления ключа")
        } catch (expected: java.security.UnrecoverableKeyException) {
            // ok
        }
    }

    /** Экспорт образцов для внешней проверки (keytool/apksigner из оболочки). */
    @Test
    fun exportForExternalTools() {
        val dir = File("build/jks_unit")
        dir.mkdirs()
        val jks = SignatureCreator.createKeystore(
            type = SignatureCreator.TYPE_JKS, alias = alias, storePassword = "toolpass",
            keyPassword = "toolpass", validityYears = 30, commonName = "LazyModification"
        )
        FileOutputStream(File(dir, "unit_test.jks")).use { it.write(jks) }
        val p12 = SignatureCreator.createKeystore(
            type = SignatureCreator.TYPE_PKCS12, alias = alias, storePassword = "toolpass",
            keyPassword = "toolpass", validityYears = 30, commonName = "LazyModification"
        )
        FileOutputStream(File(dir, "unit_test.p12")).use { it.write(p12) }
        println("EXPORTED TO: " + dir.absolutePath)
    }
}