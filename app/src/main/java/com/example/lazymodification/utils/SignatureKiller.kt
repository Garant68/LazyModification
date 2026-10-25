package com.example.lazymodification.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlElement
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/**
 * ✅ Удаление проверки подписи — движок, совместимый по результату с MT Manager.
 *
 * Как работает:
 *  1. Из APK Signing Block входного файла извлекается ОРИГИНАЛЬНЫЙ сертификат.
 *  2. В приложение встраивается dex с классом lazy.sign.KillerApplication
 *     (исходник: tools/signature-killer), куда подставляются сертификат и
 *     имя пакета.
 *  3. У класса Application целевого приложения заменяется суперкласс на
 *     lazy.sign.KillerApplication — при инициализации класса до запуска кода
 *     приложения выполняется установка перехвата, и проверки подписи через
 *     PackageManager получают оригинальный сертификат.
 */
object SignatureKiller {

    private const val TAG = "SignatureKiller"
    private const val KILLER_CLASS_TYPE = "Llazy/sign/KillerApplication;"
    private const val APP_SUPER_TYPE = "Landroid/app/Application;"
    private const val PH_CERT = "__LM_CERT_B64__"
    private const val PH_PKG = "__LM_TARGET_PKG__"

    data class Result(val ok: Boolean, val message: String)

    /**
     * Полный шаг инъекции. Вызывается из PatchService после dex-патчинга,
     * до перепаковки. При успехе обновляет [dexMap]: патченный dex с заменённым
     * суперклассом + новый dex с движком.
     */
    fun inject(
        context: Context,
        originalApk: File,
        dexMap: HashMap<String, File>,
        workDir: File,
        log: (String) -> Unit
    ): Result {
        // 1) Пакет и класс Application из манифеста
        val info = readManifestInfo(originalApk)
            ?: return Result(false, "не удалось прочитать AndroidManifest.xml")
        val pkgName = info.first ?: return Result(false, "в манифесте не найден package")
        val appCls = info.second
            ?: return Result(false, "у приложения нет своего класса Application — пока не поддержано")

        // 2) Оригинальный сертификат: из APK, иначе из установленного приложения.
        // APK из APKS/старых сборок иногда приходит без APK Signing Block, но
        // установленная копия хранит исходную подпись.
        val cert = extractSignerCert(originalApk) ?: installedSignerCert(context, pkgName)
            ?: return Result(false, "не удалось прочитать оригинальный сертификат из подписи")
        val certB64 = Base64.encodeToString(cert, Base64.NO_WRAP)
        log("🛡️ Сертификат получен (${cert.size} байт)")

        val appType = "L" + appCls.replace('.', '/') + ";"

        // 3) Цепочка наследования: быстрый бинарный скан dex (dexlib2 — только fallback)
        val scanStart = System.currentTimeMillis()
        var swapFound: String? = null
        var tNameFound: String? = null
        try {
            val scanners = HashMap<String, DexFastScanner>()
            try {
                val cache = HashMap<String, Pair<String, String>>()
                fun lookup(t: String): Pair<String, String>? {
                    cache[t]?.let { return it }
                    for ((name, dexFile) in dexMap) {
                        val scanner = scanners[name] ?: DexFastScanner(dexFile).also { scanners[name] = it }
                        val sup = scanner.superOf(t) ?: continue
                        val r = sup to name
                        cache[t] = r
                        return r
                    }
                    return null
                }
                var cur = appType
                var depth = 0
                while (depth < 32) {
                    val found = lookup(cur) ?: break
                    val sup = found.first
                    if (sup == APP_SUPER_TYPE) {
                        swapFound = cur
                        tNameFound = found.second
                        break
                    }
                    if (sup == "Ljava/lang/Object;" || sup.isEmpty()) break
                    cur = sup
                    depth++
                }
            } finally {
                scanners.values.forEach { runCatching { it.close() } }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "быстрый скан dex не удался: ${t.message}")
        }

        if (swapFound == null || tNameFound == null) {
            // Fallback: полный индекс через dexlib2
            try {
                val superOf = HashMap<String, String>()
                val dexOf = HashMap<String, String>()
                for ((name, file) in dexMap) {
                    val dex = DexFileFactory.loadDexFile(file, null)
                    for (cd in dex.classes) {
                        superOf[cd.type] = cd.superclass ?: ""
                        dexOf[cd.type] = name
                    }
                }
                var cur = appType
                var depth = 0
                while (depth < 32) {
                    val sup = superOf[cur] ?: break
                    if (sup == APP_SUPER_TYPE) {
                        swapFound = cur
                        tNameFound = dexOf[cur]
                        break
                    }
                    if (sup == "Ljava/lang/Object;" || sup.isEmpty()) break
                    cur = sup
                    depth++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "fallback-индекс dex не удался: ${t.message}")
            }
        }

        val swap = swapFound
            ?: return Result(false, "класс цепочки Application не найден (нет выхода на android.app.Application)")
        val tName = tNameFound ?: return Result(false, "класс $swap не найден в dex")
        log("⚡ Цепочка классов: ${System.currentTimeMillis() - scanStart} ms")
        val tFile = dexMap[tName] ?: return Result(false, "dex $tName не найден")

        // 5) Подмена суперкласса
        val swapStart = System.currentTimeMillis()
        val patchedDex = File(workDir, "patched_kill_$tName")
        if (!patchSuperclass(tFile, swap, patchedDex)) {
            return Result(false, "не удалось изменить суперкласс $swap")
        }
        dexMap[tName] = patchedDex
        log("🛡️ Суперкласс $swap → движок")
        log("⚡ Замена суперкласса: ${System.currentTimeMillis() - swapStart} ms")

        // 5) Собираем dex движка с реальными значениями
        val killerDex = File(workDir, "killer_final.dex")
        buildKillerDex(context, certB64, pkgName, killerDex)

        // 6) Следующий свободный номер classesN.dex
        var maxIdx = 1
        for (k in dexMap.keys) {
            val m = Regex("^classes(\\d*)\\.dex$").find(k) ?: continue
            val n = if (m.groupValues[1].isEmpty()) 1 else (m.groupValues[1].toIntOrNull() ?: 1)
            if (n > maxIdx) maxIdx = n
        }
        val newName = "classes${maxIdx + 1}.dex"
        dexMap[newName] = killerDex
        log("🛡️ Добавлен $newName")

        return Result(true, "движок внедрён: $swap, сертификат ${cert.size} байт")
    }

    /** X.509 сертификат (DER) первого подписанта из APK Signing Block (v3/v2), иначе из v1. */
    fun extractSignerCert(apk: File): ByteArray? {
        extractSignerCertManually(apk)?.let { return it }

        // apksig умеет v2/v3/v3.1 и корректно разбирает блоки, которые
        // ручной сканер пропускает (например, APK от другого упаковщика).
        return try {
            ApkVerifier.Builder(apk).build().verify()
                .signerCertificates
                .firstOrNull()
                ?.encoded
        } catch (_: Throwable) {
            null
        }
    }

    private fun installedSignerCert(context: Context, packageName: String): ByteArray? {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            signatures?.firstOrNull()?.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractSignerCertManually(apk: File): ByteArray? {
        val block = try { SigningUtils.extractSigningBlock(apk) } catch (t: Throwable) { null }
        if (block != null) {
            val starts = ArrayList<Int>(2)
            findId(block, byteArrayOf(0xC0.toByte(), 0x68, 0x53, 0xF0.toByte()))?.let { starts.add(it) }  // v3
            findId(block, byteArrayOf(0x1A, 0x87.toByte(), 0x09, 0x71))?.let { starts.add(it) }          // v2
            if (starts.isEmpty()) starts.add(0)
            var best: ByteArray? = null
            for (start in starts) {
                var i = start
                while (i <= block.size - 8) {
                    if (block[i] == 0x30.toByte() &&
                        (block[i + 1] == 0x82.toByte() || block[i + 1] == 0x81.toByte())
                    ) {
                        val len = ((block[i + 2].toInt() and 0xFF) shl 8) or (block[i + 3].toInt() and 0xFF)
                        val size = len + 4
                        if (size in 600..4096 && i + size <= block.size) {
                            if (i >= 8 && u32le(block, i - 4) == size.toLong() && u32le(block, i - 8) == (size + 4).toLong()) {
                                val cand = block.copyOfRange(i, i + size)
                                if (isValidX509(cand)) { best = cand; break }
                            }
                        }
                        i += 2
                    } else i++
                }
                if (best != null) break
            }
            if (best != null) return best
        }
        // fallback: v1 подпись (PKCS7 .RSA/.DSA/.EC)
        return try {
            ZipFile(apk).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.startsWith("META-INF/") &&
                        (e.name.endsWith(".RSA") || e.name.endsWith(".DSA") || e.name.endsWith(".EC"))
                    ) {
                        val bytes = zf.getInputStream(e).readBytes()
                        val cf = CertificateFactory.getInstance("X.509")
                        val certs = cf.generateCertificates(ByteArrayInputStream(bytes))
                        if (certs.isNotEmpty()) {
                            val c = certs.first()
                            if (c is java.security.cert.X509Certificate) return c.encoded
                        }
                    }
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun findId(block: ByteArray, needle: ByteArray): Int? {
        outer@ for (i in 0..(block.size - needle.size)) {
            for (j in needle.indices) {
                if (block[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun isValidX509(der: ByteArray): Boolean = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        true
    } catch (t: Throwable) {
        false
    }

    /** (package, applicationClass) из AndroidManifest.xml; applicationClass может быть null. */
    private fun readManifestInfo(apk: File): Pair<String?, String?>? {
        return try {
            val module = ApkModule.loadApkFile(apk)
            try {
                val manifest = module.androidManifest ?: return null
                val root = manifest.documentElement ?: return null
                var pkg: String? = null
                var appCls: String? = null
                val at = root.attributes
                while (at.hasNext()) {
                    val a = at.next()
                    if (a.name == "package") pkg = a.valueString
                }
                val it = root.elements
                while (it.hasNext()) {
                    val n = it.next()
                    if (n is ResXmlElement && n.name == "application") {
                        val aat = n.attributes
                        while (aat.hasNext()) {
                            val a = aat.next()
                            if (a.name == "name") appCls = a.valueString
                        }
                        break
                    }
                }
                Pair(pkg, appCls)
            } finally {
                module.close()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "readManifestInfo: ${t.message}", t)
            null
        }
    }

    /** Перезаписывает dex, заменяя суперкласс [appType] на движок. false = не найден/не подходит. */
    private fun patchSuperclass(dexFile: File, appType: String, outFile: File): Boolean {
        // Не фиксируем API 28: в Compose-приложениях встречаются конструкции
        // новее, и запись dex с чужими opcodes может испортить try/catch.
        val dex = DexFileFactory.loadDexFile(dexFile, null)
        var found = false
        val classes = ArrayList<ClassDef>()
        for (cd in dex.classes) {
            if (cd.type == appType) {
                if (cd.superclass != APP_SUPER_TYPE) return false
                classes.add(ImmutableClassDef(
                    cd.type, cd.accessFlags, KILLER_CLASS_TYPE, cd.interfaces,
                    cd.sourceFile, cd.annotations, cd.staticFields, cd.instanceFields,
                    cd.directMethods, cd.virtualMethods
                ))
                found = true
            } else {
                classes.add(cd)
            }
        }
        if (!found) return false
        DexFileFactory.writeDexFile(outFile.absolutePath, ImmutableDexFile(dex.opcodes, classes))
        DexPatcher.normalizeDexVersion(dexFile, outFile)
        return try {
            DexFileFactory.loadDexFile(outFile, null)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Собирает итоговый dex движка: подставляет сертификат и имя пакета в шаблон. */
    fun buildKillerDex(context: Context, certB64: String, pkg: String, outDex: File): Boolean {
        val template = File(outDex.parentFile, "killer_template.dex")
        context.assets.open("killer/killer.dex").use { input ->
            template.outputStream().use { out -> input.copyTo(out) }
        }
        val dex = DexFileFactory.loadDexFile(template, null)
        val classes = ArrayList<ClassDef>()
        for (cd in dex.classes) {
            val nsf = ArrayList<Field>()
            for (f in cd.staticFields) {
                val v = f.initialValue
                if (v is StringEncodedValue) {
                    val rep = when (v.value) {
                        PH_CERT -> certB64
                        PH_PKG -> pkg
                        else -> null
                    }
                    if (rep != null) {
                        nsf.add(ImmutableField(
                            f.definingClass, f.name, f.type, f.accessFlags,
                            ImmutableStringEncodedValue(rep), f.annotations, f.hiddenApiRestrictions
                        ))
                        continue
                    }
                }
                nsf.add(f)
            }
            val ndm = cd.directMethods.map { rewriteStrings(it, certB64, pkg) }
            val nvm = cd.virtualMethods.map { rewriteStrings(it, certB64, pkg) }
            classes.add(ImmutableClassDef(
                cd.type, cd.accessFlags, cd.superclass, cd.interfaces, cd.sourceFile, cd.annotations,
                nsf, cd.instanceFields, ndm, nvm
            ))
        }
        DexFileFactory.writeDexFile(outDex.absolutePath, ImmutableDexFile(dex.opcodes, classes))
        template.delete()
        return true
    }

    private fun rewriteStrings(m: Method, certB64: String, pkg: String): Method {
        val mi = m.implementation ?: return m
        var changed = false
        val ni = ArrayList<org.jf.dexlib2.iface.instruction.Instruction>()
        for (ins in mi.instructions) {
            if (ins is ReferenceInstruction) {
                val r = ins.reference
                if (r is StringReference) {
                    val rep = when (r.string) {
                        PH_CERT -> certB64
                        PH_PKG -> pkg
                        else -> null
                    }
                    if (rep != null && ins is OneRegisterInstruction) {
                        ni.add(ImmutableInstruction21c(ins.opcode, ins.registerA, ImmutableStringReference(rep)))
                        changed = true
                        continue
                    }
                }
            }
            ni.add(ins)
        }
        if (!changed) return m
        val nimpl = ImmutableMethodImplementation(mi.registerCount, ni, mi.tryBlocks, mi.debugItems)
        return ImmutableMethod(
            m.definingClass, m.name, m.parameters, m.returnType, m.accessFlags,
            m.annotations, m.hiddenApiRestrictions, nimpl
        )
    }
}
