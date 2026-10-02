package com.example.lazymodification.utils

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import javax.security.auth.x500.X500Principal

/**
 * Создание новой подписи (файла ключа) для подписи APK.
 *
 * Поддерживаются два формата:
 * - PKCS12 (.p12) — современный универсальный формат (рекомендуется);
 * - JKS (.jks) — старый формат Java: совместим с keytool/apksigner на ПК,
 *   а на устройстве читается собственной поддержкой [JksSupport].
 *
 * Как это работает:
 * 1. Генерируется ключевая пара RSA-2048.
 * 2. Создаётся самоподписанный сертификат X.509 (SHA256withRSA) со сроком действия.
 * 3. Ключ и сертификат сохраняются в выбранный формат хранилища.
 */
object SignatureCreator {

    const val TYPE_PKCS12 = "PKCS12"
    const val TYPE_JKS = "JKS"

    /**
     * Экземпляр BC-провайдера. Глобально не регистрируем: на Android имя "BC" уже занято
     * системным провайдером, поэтому передаём провайдер явно (by instance).
     */
    private val bcProvider: BouncyCastleProvider by lazy { BouncyCastleProvider() }

    /**
     * Создаёт содержимое файла подписи.
     *
     * @param type формат: [TYPE_PKCS12] или [TYPE_JKS]
     * @param alias псевдоним ключа внутри хранилища
     * @param storePassword пароль хранилища
     * @param keyPassword пароль ключа (обычно совпадает с паролем хранилища)
     * @param validityYears срок действия в годах
     * @param commonName владелец сертификата (CN)
     */
    @Suppress("DEPRECATION")
    fun createKeystore(
        type: String,
        alias: String,
        storePassword: String,
        keyPassword: String,
        validityYears: Int,
        commonName: String
    ): ByteArray {
        // 1. Ключевая пара RSA-2048 — стандарт для подписи Android-приложений
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        // 2. Самоподписанный сертификат X.509
        val certGen = X509V3CertificateGenerator()
        certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
        val dn = X500Principal("CN=" + commonName + ", O=LazyModification")
        certGen.setIssuerDN(dn)
        certGen.setSubjectDN(dn)
        certGen.setPublicKey(keyPair.public)
        // Небольшой запас назад — на случай расхождения часов
        certGen.setNotBefore(Date(System.currentTimeMillis() - 24L * 3600 * 1000))
        certGen.setNotAfter(Date(System.currentTimeMillis() + validityYears * 365L * 24 * 3600 * 1000))
        certGen.setSignatureAlgorithm("SHA256WithRSA")
        val certificate = certGen.generate(keyPair.private)

        // 3. Запись выбранного формата
        return when (type.uppercase(Locale.ENGLISH)) {
            TYPE_JKS -> JksSupport.createJks(alias, keyPair.private, certificate, keyPassword, storePassword)
            else -> createPkcs12(alias, keyPair.private, certificate, keyPassword, storePassword)
        }
    }

    /** PKCS12-хранилище (свой экземпляр BC — надёжнее системного провайдера). */
    private fun createPkcs12(
        alias: String,
        privateKey: PrivateKey,
        certificate: X509Certificate,
        keyPassword: String,
        storePassword: String
    ): ByteArray {
        val keyStore = openPkcs12()
        keyStore.load(null, storePassword.toCharArray())
        keyStore.setKeyEntry(alias, privateKey, keyPassword.toCharArray(), arrayOf(certificate))

        val out = ByteArrayOutputStream()
        keyStore.store(out, storePassword.toCharArray())
        return out.toByteArray()
    }

    /** Открывает PKCS12-хранилище: сначала свой BC, затем системный провайдер. */
    private fun openPkcs12(): KeyStore {
        return try {
            KeyStore.getInstance("PKCS12", bcProvider)
        } catch (t: Throwable) {
            KeyStore.getInstance("PKCS12")
        }
    }
}