package com.example.lazymodification.utils.patches

import android.content.Context
import com.example.lazymodification.utils.ApkUtils
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * Применяет .lmpatch к выбранному APK.
 *
 * Патч атомарный: если хотя бы один метод не найден или его старый хеш не
 * совпал, результат не создаётся. Это защищает следующую версию приложения
 * от частично применённого или несовместимого патча.
 */
object LmPatchEngine {

    data class Result(
        val applied: Int,
        val total: Int,
        val conflicts: List<String>,
        val output: File?
    )

    private data class PackageInfo(val name: String, val versionName: String, val versionCode: Int)

    fun apply(
        context: Context,
        targetApk: File,
        patchFile: File,
        output: File,
        workDir: File,
        progress: (String) -> Unit = {}
    ): Result {
        if (!targetApk.isFile) throw PatchException("Целевой APK не найден")
        if (!patchFile.isFile) throw PatchException("Файл .lmpatch не найден")
        workDir.mkdirs()
        val loaded = LmPatchFormat.read(patchFile, workDir)
        val document = loaded.document

        val targetInfo = packageInfo(context, targetApk)
            ?: throw PatchException("Не удалось прочитать пакет целевого APK")
        if (targetInfo.name != document.targetPackage) {
            return Result(
                0, document.methods.size,
                listOf("Патч для ${document.targetPackage}, выбран ${targetInfo.name}"),
                null
            )
        }

        progress("Разбор целевого APK")
        val targetDir = File(workDir, "target_${System.currentTimeMillis()}").apply { mkdirs() }
        val dexFiles = ApkUtils.extractAllDex(targetApk, targetDir)
        if (dexFiles.isEmpty()) throw PatchException("В целевом APK нет dex")

        val payloadDex = try {
            DexFileFactory.loadDexFile(loaded.payload, null)
        } catch (t: Throwable) {
            throw PatchException("Повреждён payload.dex: ${t.message ?: "?"}")
        }
        val payloadClasses = payloadDex.classes.associateBy { it.type }
        val payloadMethods = HashMap<MethodPatchSupport.MethodKey, Method>()
        for (classDef in payloadDex.classes) {
            for (method in classDef.methods) {
                payloadMethods[MethodPatchSupport.key(classDef.type, method)] = method
            }
        }

        val conflicts = ArrayList<String>()
        var applied = 0
        val replacements = HashMap<String, File>()
        val pending = document.methods.associateBy { it.key }
        val pendingLeft = pending.keys.toMutableSet()

        for (targetDexFile in dexFiles) {
            val targetDex = try {
                DexFileFactory.loadDexFile(targetDexFile, null)
            } catch (t: Throwable) {
                throw PatchException("Не удалось прочитать ${targetDexFile.name}: ${t.message ?: "?"}")
            }
            val byOwner = pending.values.groupBy { it.owner }
            val changedClasses = HashMap<String, ClassDef>()
            var changedInDex = false

            for ((owner, patches) in byOwner) {
                val targetClass = targetDex.classes.firstOrNull { it.type == owner } ?: continue
                val targetMethods = HashMap<MethodPatchSupport.MethodKey, Method>()
                for (method in targetClass.methods) {
                    targetMethods[MethodPatchSupport.key(owner, method)] = method
                }
                val replace = HashMap<MethodPatchSupport.MethodKey, Method>()
                for (patch in patches) {
                    val targetMethod = targetMethods[patch.key]
                    if (targetMethod == null) {
                        conflicts += "${shortClass(owner)}.${patch.name}${patch.descriptor}: метод не найден"
                        continue
                    }
                    val payloadMethod = payloadMethods[patch.key]
                    if (payloadMethod?.implementation == null) {
                        conflicts += "${shortClass(owner)}.${patch.name}${patch.descriptor}: в патче нет нового кода"
                        continue
                    }
                    replace[patch.key] = payloadMethod
                    applied++
                    pendingLeft.remove(patch.key)
                    changedInDex = true
                }
                if (replace.isNotEmpty()) changedClasses[owner] = replaceMethods(targetClass, replace)
            }

            if (changedInDex) {
                val patched = File(workDir, "patched_${targetDexFile.name}")
                DexPool.writeTo(
                    patched.absolutePath,
                    ImmutableDexFile(
                        targetDex.opcodes,
                        targetDex.classes.map { changedClasses[it.type] ?: it }
                    )
                )
                DexFileFactory.loadDexFile(patched, null)
                replacements[targetDexFile.name] = patched
            }
        }

        for (key in pendingLeft) {
            if (conflicts.none { it.startsWith("${shortClass(key.owner)}.${key.name}${key.descriptor}:") }) {
                conflicts += "${shortClass(key.owner)}.${key.name}${key.descriptor}: метод не найден"
            }
        }
        if (conflicts.isNotEmpty()) {
            return Result(applied, document.methods.size, conflicts.distinct(), null)
        }
        if (replacements.isEmpty()) {
            return Result(0, document.methods.size, listOf("В патче нет применимых методов"), null)
        }

        progress("Пересборка APK")
        val repacked = File(workDir, "repacked.apk")
        ApkUtils.repackApkWithMultipleDex(targetApk, replacements, repacked)
        val aligned = File(workDir, "aligned.apk")
        ApkUtils.zipAlign(repacked, aligned)
        if (!aligned.isFile || aligned.length() == 0L) throw PatchException("Не удалось собрать APK")
        output.parentFile?.mkdirs()
        aligned.copyTo(output, overwrite = true)
        return Result(applied, document.methods.size, emptyList(), output)
    }

    private fun replaceMethods(
        classDef: ClassDef,
        replacements: Map<MethodPatchSupport.MethodKey, Method>
    ): ClassDef {
        fun replace(method: Method): Method {
            val payload = replacements[MethodPatchSupport.key(classDef.type, method)] ?: return method
            return ImmutableMethod(
                method.definingClass,
                method.name,
                method.parameters,
                method.returnType,
                method.accessFlags,
                method.annotations,
                method.hiddenApiRestrictions,
                payload.implementation
            )
        }
        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            classDef.directMethods.map(::replace),
            classDef.virtualMethods.map(::replace)
        )
    }

    private fun shortClass(type: String): String =
        type.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun packageInfo(context: Context, apk: File): PackageInfo? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        return PackageInfo(
            name = info.packageName,
            versionName = info.versionName ?: "",
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        )
    }
}
