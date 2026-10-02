package com.example.lazymodification.utils

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.Locale

/**
 * Поддержка формата JKS (старый формат хранилищ ключей Java).
 *
 * Android не содержит встроенной поддержки JKS, поэтому формат реализован здесь:
 * - [createJks] — запись хранилища (совместимость с keytool/apksigner проверена);
 * - [openJks] + [JksKeyStoreSpi] — чтение (загрузка) JKS-файлов на устройстве.
 *
 * Формат: magic 0xFEEDFEED, версия 2, записи (alias, дата, защищённый ключ,
 * цепочка сертификатов), в конце — SHA-1 MAC по паролю.
 * Защита ключа — алгоритм JAVASOFT_JDKKeyProtector (OID 1.3.6.1.4.1.42.2.17.1.1):
 * ключ XOR-ится с цепочкой SHA-1-дайджестов, построенной от пароля и соли,
 * и дополняется контрольным дайджестом.
 */
internal object JksSupport {

    private const val PROTECTOR_OID = "1.3.6.1.4.1.42.2.17.1.1"
    private const val SALT_LEN = 20
    private const val DIGEST_LEN = 20
    private const val MAGIC = 0xFEEDFEED.toInt()
    private const val VERSION_2 = 2

    private val provider: Provider by lazy { BouncyCastleProvider() }

    // ============================================================
    // ЗАПИСЬ: создание нового JKS
    // ============================================================

    /** Создаёт JKS-хранилище с одной записью ключа. */
    fun createJks(
        alias: String,
        privateKey: PrivateKey,
        certificate: X509Certificate,
        keyPassword: String,
        storePassword: String
    ): ByteArray {
        val blob = protectKey(privateKey.encoded, utf16be(keyPassword.toCharArray()))
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)

        out.writeInt(MAGIC)
        out.writeInt(VERSION_2)
        out.writeInt(1)                                 // одна запись
        out.writeInt(1)                                 // tag: ключ
        out.writeUTF(alias.lowercase(Locale.ENGLISH))   // JKS хранит alias в нижнем регистре
        out.writeLong(System.currentTimeMillis())
        out.writeInt(blob.size)
        out.write(blob)
        out.writeInt(1)                                 // цепочка из одного сертификата
        out.writeUTF(certificate.type)                  // "X.509"
        val der = certificate.encoded
        out.writeInt(der.size)
        out.write(der)
        out.flush()

        val bodyBytes = body.toByteArray()
        val md = MessageDigest.getInstance("SHA-1")
        md.update(utf16be(storePassword.toCharArray()))
        md.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
        val mac = md.digest(bodyBytes)

        val result = ByteArrayOutputStream()
        result.write(bodyBytes)
        result.write(mac)
        return result.toByteArray()
    }

    /** Защищает PKCS#8-ключ алгоритмом JAVASOFT_JDKKeyProtector (как JDK). */
    private fun protectKey(pkcs8: ByteArray, passwdUtf16: ByteArray): ByteArray {
        val salt = ByteArray(SALT_LEN)
        SecureRandom().nextBytes(salt)

        val xorKey = xorKeyFor(pkcs8.size, passwdUtf16, salt)

        val tmpKey = ByteArray(pkcs8.size) { i -> (pkcs8[i].toInt() xor xorKey[i].toInt()).toByte() }

        val md = MessageDigest.getInstance("SHA-1")
        md.update(passwdUtf16)
        md.update(pkcs8)
        val integrity = md.digest()
        md.reset()

        val encrKey = ByteArray(SALT_LEN + tmpKey.size + DIGEST_LEN)
        System.arraycopy(salt, 0, encrKey, 0, SALT_LEN)
        System.arraycopy(tmpKey, 0, encrKey, SALT_LEN, tmpKey.size)
        System.arraycopy(integrity, 0, encrKey, SALT_LEN + tmpKey.size, DIGEST_LEN)

        val algId = AlgorithmIdentifier(ASN1ObjectIdentifier(PROTECTOR_OID), DERNull.INSTANCE)
        return DERSequence(arrayOf<org.bouncycastle.asn1.ASN1Encodable>(algId, DEROctetString(encrKey)))
            .getEncoded(ASN1Encoding.DER)
    }

    /** Цепочка дайджестов: d1 = SHA1(пароль + соль); dn = SHA1(пароль + dn-1). */
    private fun xorKeyFor(length: Int, passwdUtf16: ByteArray, salt: ByteArray): ByteArray {
        val xorKey = ByteArray(length)
        val md = MessageDigest.getInstance("SHA-1")
        var digest = salt
        var offset = 0
        val numRounds = (length + DIGEST_LEN - 1) / DIGEST_LEN
        for (i in 0 until numRounds) {
            md.update(passwdUtf16)
            md.update(digest)
            digest = md.digest()
            md.reset()
            val len = minOf(DIGEST_LEN, xorKey.size - offset)
            System.arraycopy(digest, 0, xorKey, offset, len)
            offset += DIGEST_LEN
        }
        return xorKey
    }

    // ============================================================
    // ЧТЕНИЕ: загрузка JKS на устройстве
    // ============================================================

    /** Пустое JKS-хранилище: далее ks.load(поток, пароль). */
    fun openJks(): KeyStore = WrappedKeyStore(JksKeyStoreSpi(), provider, "JKS")

    /** Расшифровка защищённого ключа (EncryptedPrivateKeyInfo -> PKCS#8). */
    internal fun recoverKeyBytes(blob: ByteArray, passwdUtf16: ByteArray): ByteArray {
        val seq = ASN1Sequence.getInstance(blob)
        val algId = AlgorithmIdentifier.getInstance(seq.getObjectAt(0))
        if (algId.algorithm.id != PROTECTOR_OID) {
            throw UnrecoverableKeyException("Неподдерживаемый алгоритм защиты ключа")
        }
        val protectedKey = ASN1OctetString.getInstance(seq.getObjectAt(1)).octets
        if (protectedKey.size < SALT_LEN + DIGEST_LEN) throw UnrecoverableKeyException("Повреждённый ключ")

        val salt = protectedKey.copyOfRange(0, SALT_LEN)
        val encrLen = protectedKey.size - SALT_LEN - DIGEST_LEN
        val encrKey = protectedKey.copyOfRange(SALT_LEN, SALT_LEN + encrLen)

        val xorKey = xorKeyFor(encrLen, passwdUtf16, salt)
        val plain = ByteArray(encrLen) { i -> (encrKey[i].toInt() xor xorKey[i].toInt()).toByte() }

        val md = MessageDigest.getInstance("SHA-1")
        md.update(passwdUtf16)
        md.update(plain)
        val computed = md.digest()
        md.reset()
        for (i in 0 until DIGEST_LEN) {
            if (computed[i] != protectedKey[SALT_LEN + encrLen + i]) {
                throw UnrecoverableKeyException("Cannot recover key")
            }
        }
        return plain
    }

    /** PKCS#8 -> PrivateKey (RSA/EC/DSA/Ed25519). */
    internal fun toPrivateKey(pkcs8: ByteArray): PrivateKey {
        val oid = try {
            org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(pkcs8).privateKeyAlgorithm.algorithm.id
        } catch (t: Throwable) {
            null
        }
        val algorithm = when (oid) {
            "1.2.840.113549.1.1.1" -> "RSA"
            "1.2.840.10040.4.1" -> "DSA"
            "1.2.840.10045.2.1" -> "EC"
            "1.3.101.112" -> "Ed25519"
            else -> "RSA"
        }
        return try {
            KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (t: Throwable) {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        }
    }

    internal fun utf16be(password: CharArray): ByteArray {
        val b = ByteArray(password.size * 2)
        for (i in password.indices) {
            b[i * 2] = (password[i].code shr 8).toByte()
            b[i * 2 + 1] = password[i].code.toByte()
        }
        return b
    }
}

/** Обёртка KeyStore над собственным SPI (доступ к protected-конструктору). */
internal class WrappedKeyStore(spi: KeyStoreSpi, provider: Provider, type: String) : KeyStore(spi, provider, type)

/**
 * JKS только для чтения: загрузка файла, доступ к ключу и цепочке сертификатов.
 * Для создания используется [JksSupport.createJks].
 */
internal class JksKeyStoreSpi : KeyStoreSpi() {

    private companion object {
        const val DIGEST_LEN = 20
        const val VERSION_2 = 2
    }

    private class Entry(
        val alias: String,
        val protectedKey: ByteArray?,
        val chain: List<X509Certificate>,
        val date: Long,
        val isKey: Boolean
    )

    private val entries = LinkedHashMap<String, Entry>()

    override fun engineLoad(stream: InputStream?, password: CharArray?) {
        entries.clear()
        if (stream == null) return
        val data = stream.readBytes()

        // 1) Проверка контрольной суммы всего файла (как в JDK)
        if (password != null) {
            if (data.size < 12 + DIGEST_LEN) throw IOException("Invalid keystore format")
            val body = data.copyOfRange(0, data.size - DIGEST_LEN)
            val macActual = data.copyOfRange(data.size - DIGEST_LEN, data.size)
            val md = MessageDigest.getInstance("SHA-1")
            md.update(JksSupport.utf16be(password))
            md.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
            val macComputed = md.digest(body)
            if (!MessageDigest.isEqual(macComputed, macActual)) {
                throw IOException("Keystore was tampered with, or password was incorrect")
            }
        }

        // 2) Разбор записей
        val din = DataInputStream(ByteArrayInputStream(data))
        if (din.readInt() != 0xFEEDFEED.toInt()) throw IOException("Invalid keystore format")
        val version = din.readInt()
        if (version != 1 && version != VERSION_2) throw IOException("Invalid keystore format")
        val count = din.readInt()
        if (count < 0 || count > 10000) throw IOException("Invalid keystore format")

        val certFactory = CertificateFactory.getInstance("X.509")
        repeat(count) {
            val tag = din.readInt()
            val alias = din.readUTF()
            val date = din.readLong()
            when (tag) {
                1 -> {
                    val keyLen = din.readInt()
                    if (keyLen <= 0 || keyLen > 20_000_000) throw IOException("Invalid keystore format")
                    val blob = ByteArray(keyLen)
                    din.readFully(blob)
                    val chainCount = din.readInt()
                    if (chainCount < 0 || chainCount > 1000) throw IOException("Invalid keystore format")
                    val chain = ArrayList<X509Certificate>(chainCount)
                    repeat(chainCount) {
                        if (version == VERSION_2) din.readUTF()
                        val certLen = din.readInt()
                        if (certLen <= 0 || certLen > 5_000_000) throw IOException("Invalid keystore format")
                        val certBytes = ByteArray(certLen)
                        din.readFully(certBytes)
                        chain.add(certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate)
                    }
                    entries[alias.lowercase(Locale.ENGLISH)] =
                        Entry(alias.lowercase(Locale.ENGLISH), blob, chain, date, true)
                }
                2 -> {
                    if (version == VERSION_2) din.readUTF()
                    val certLen = din.readInt()
                    if (certLen <= 0 || certLen > 5_000_000) throw IOException("Invalid keystore format")
                    val certBytes = ByteArray(certLen)
                    din.readFully(certBytes)
                    val cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                    entries[alias.lowercase(Locale.ENGLISH)] =
                        Entry(alias.lowercase(Locale.ENGLISH), null, listOf(cert), date, false)
                }
                else -> throw IOException("Unrecognized keystore entry")
            }
        }
    }

    override fun engineGetKey(alias: String, password: CharArray?): Key? {
        val entry = entries[alias.lowercase(Locale.ENGLISH)] ?: return null
        if (!entry.isKey) return null
        if (password == null) throw UnrecoverableKeyException("Password must not be null")
        val pkcs8 = JksSupport.recoverKeyBytes(entry.protectedKey!!, JksSupport.utf16be(password))
        return JksSupport.toPrivateKey(pkcs8)
    }

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? {
        val entry = entries[alias.lowercase(Locale.ENGLISH)] ?: return null
        if (!entry.isKey || entry.chain.isEmpty()) return null
        return entry.chain.toTypedArray()
    }

    override fun engineGetCertificate(alias: String): Certificate? =
        entries[alias.lowercase(Locale.ENGLISH)]?.chain?.firstOrNull()

    override fun engineGetCreationDate(alias: String): Date? =
        entries[alias.lowercase(Locale.ENGLISH)]?.let { Date(it.date) }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys)

    override fun engineContainsAlias(alias: String): Boolean =
        entries.containsKey(alias.lowercase(Locale.ENGLISH))

    override fun engineSize(): Int = entries.size

    override fun engineIsKeyEntry(alias: String): Boolean =
        entries[alias.lowercase(Locale.ENGLISH)]?.isKey == true

    override fun engineIsCertificateEntry(alias: String): Boolean {
        val e = entries[alias.lowercase(Locale.ENGLISH)] ?: return false
        return !e.isKey && e.chain.isNotEmpty()
    }

    override fun engineGetCertificateAlias(cert: Certificate?): String? =
        entries.values.firstOrNull { it.chain.isNotEmpty() && it.chain[0] == cert }?.alias

    // Изменение не поддерживается: JKS-файлы на устройстве только читаются
    override fun engineStore(stream: OutputStream?, password: CharArray?) {
        throw UnsupportedOperationException("JKS: только чтение")
    }

    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray, chain: Array<out Certificate>) {
        throw UnsupportedOperationException("JKS: только чтение")
    }

    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>) {
        throw UnsupportedOperationException("JKS: только чтение")
    }

    override fun engineSetCertificateEntry(alias: String, cert: Certificate) {
        throw UnsupportedOperationException("JKS: только чтение")
    }

    override fun engineDeleteEntry(alias: String) {
        throw UnsupportedOperationException("JKS: только чтение")
    }
}