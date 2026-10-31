package com.example.lazymodification.utils.patches

import android.content.Context
import com.example.lazymodification.utils.ApkUtils
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.UUID

/**
 * Создаёт .lmpatch из двух APK: база A и новая версия B.
 *
 * В патч попадают только методы, у которых изменилось нормализованное тело.
 * Новые/удалённые методы пока пропускаются и учитываются в skipped: это
 * безопаснее, чем менять структуру класса без явного правила.
 */
object LmPatchGenerator {

    data class Generated(
        val output: File,
        val methods: Int,
        val classes: Int,
        val skipped: Int
    )

    private data class ApkInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: Int
    )

    fun generate(
        context: Context,
        baseApk: File,
        newApk: File,
        output: File,
        workDir: File,
        progress: (String) -> Unit = {}
    ): Generated {
        if (!baseApk.isFile) throw PatchException("Базовый APK не найден")
        if (!newApk.isFile) throw PatchException("Новый APK не найден")
        val baseInfo = packageInfo(context, baseApk)
            ?: throw PatchException("Не удалось прочитать пакет базового APK")
        val newInfo = packageInfo(context, newApk)
            ?: throw PatchException("Не удалось прочитать пакет нового APK")
        if (baseInfo.packageName != newInfo.packageName) {
            throw PatchException(
                "APK относятся к разным пакетам: ${baseInfo.packageName} и ${newInfo.packageName}"
            )
        }

        workDir.mkdirs()
        val baseDir = File(workDir, "base_${UUID.randomUUID()}").apply { mkdirs() }
        val newDir = File(workDir, "new_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress("Разбор базового APK")
            val baseDex = ApkUtils.extractAllDex(baseApk, baseDir)
            if (baseDex.isEmpty()) throw PatchException("В базовом APK нет dex")
            progress("Разбор новой версии")
            val newDex = ApkUtils.extractAllDex(newApk, newDir)
            if (newDex.isEmpty()) throw PatchException("В новом APK нет dex")

            var opcodes: Opcodes? = null
            val baseClasses = HashMap<String, ClassDef>()
            for (file in baseDex) {
                val dex = DexFileFactory.loadDexFile(file, null)
                if (opcodes == null) opcodes = dex.opcodes
                for (classDef in dex.classes) baseClasses[classDef.type] = classDef
            }
            val newClasses = HashMap<String, ClassDef>()
            for (file in newDex) {
                val dex = DexFileFactory.loadDexFile(file, null)
                if (opcodes == null) opcodes = dex.opcodes
                for (classDef in dex.classes) newClasses[classDef.type] = classDef
            }
            val o = opcodes ?: throw PatchException("Не удалось определить версию dex")

            progress("Сравнение методов")
            data class Changed(val old: Method, val new: Method)

            val changed = ArrayList<Changed>()
            var skipped = 0
            for ((owner, baseClass) in baseClasses) {
                val newClass = newClasses[owner] ?: continue
                val newMethods = methodsByKey(newClass)
                for (oldMethod in baseClass.methods) {
                    val key = MethodPatchSupport.key(owner, oldMethod)
                    val newMethod = newMethods[key]
                    if (newMethod == null) {
                        skipped++
                        continue
                    }
                    val oldHash = MethodPatchSupport.bodyHash(oldMethod)
                    val newHash = MethodPatchSupport.bodyHash(newMethod)
                    if (oldHash.isEmpty() || newHash.isEmpty()) {
                        skipped++
                        continue
                    }
                    if (oldHash != newHash) changed += Changed(oldMethod, newMethod)
                }
            }
            if (changed.isEmpty()) {
                throw PatchException("В методах нет изменений для патча")
            }

            val byOwner = changed.groupBy { it.new.definingClass }
            val payloadClasses = ArrayList<ClassDef>()
            for ((owner, methods) in byOwner) {
                val newClass = newClasses[owner] ?: continue
                val changedKeys = methods.map { MethodPatchSupport.key(owner, it.new) }.toSet()
                val direct = newClass.directMethods
                    .filter { MethodPatchSupport.key(owner, it) in changedKeys }
                    .map { ImmutableMethod.of(it) }
                val virtual = newClass.virtualMethods
                    .filter { MethodPatchSupport.key(owner, it) in changedKeys }
                    .map { ImmutableMethod.of(it) }
                if (direct.isEmpty() && virtual.isEmpty()) continue
                payloadClasses += ImmutableClassDef(
                    newClass.type,
                    newClass.accessFlags,
                    newClass.superclass,
                    newClass.interfaces,
                    newClass.sourceFile,
                    newClass.annotations,
                    newClass.staticFields,
                    newClass.instanceFields,
                    direct,
                    virtual
                )
            }
            if (payloadClasses.isEmpty()) throw PatchException("Не удалось собрать методы для патча")

            progress("Сборка payload.dex")
            val payload = File(workDir, "payload.dex")
            DexPool.writeTo(payload.absolutePath, ImmutableDexFile(o, payloadClasses))
            DexFileFactory.loadDexFile(payload, null) // проверка, что payload читается

            val manifestMethods = changed.map {
                LmPatchMethod(
                    owner = it.new.definingClass,
                    name = it.new.name,
                    descriptor = MethodPatchSupport.methodDescriptor(it.new),
                    oldHash = MethodPatchSupport.bodyHash(it.old),
                    newHash = MethodPatchSupport.bodyHash(it.new)
                )
            }
            val document = LmPatchDocument(
                targetPackage = baseInfo.packageName,
                baseVersionName = baseInfo.versionName,
                baseVersionCode = baseInfo.versionCode,
                newVersionName = newInfo.versionName,
                newVersionCode = newInfo.versionCode,
                baseApkSha256 = MethodPatchSupport.fileSha256(baseApk),
                newApkSha256 = MethodPatchSupport.fileSha256(newApk),
                methods = manifestMethods
            )
            progress("Запись .lmpatch")
            LmPatchFormat.write(output, document, payload)
            return Generated(output, manifestMethods.size, payloadClasses.size, skipped)
        } finally {
            baseDir.deleteRecursively()
            newDir.deleteRecursively()
            File(workDir, "payload.dex").delete()
        }
    }

    private fun methodsByKey(classDef: ClassDef): Map<MethodPatchSupport.MethodKey, Method> {
        val map = HashMap<MethodPatchSupport.MethodKey, Method>()
        for (method in classDef.methods) map[MethodPatchSupport.key(classDef.type, method)] = method
        return map
    }

    private fun packageInfo(context: Context, apk: File): ApkInfo? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        return ApkInfo(
            packageName = info.packageName,
            versionName = info.versionName ?: "",
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        )
    }
}
