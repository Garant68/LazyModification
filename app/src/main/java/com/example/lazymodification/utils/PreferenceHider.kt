package com.example.lazymodification.utils

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21s
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import java.util.zip.ZipEntry

/**
 * Универсальное скрытие пунктов настроек (Preference) без вылетов.
 *
 * Проблема: если пункт удалить из XML, то findPreference("ключ") в коде приложения
 * вернёт null. Код настроек часто пишут без проверки на null (пример: Tetra Filer):
 *
 *     findPreference("rate_application").setOnPreferenceClickListener(...)
 *
 * → NullPointerException → вылет при открытии настроек.
 *
 * Решение для framework-приложений (android.preference.*):
 *  1) элемент настроек в XML НЕ удаляем;
 *  2) в класс фрагмента настроек добавляем свой findPreference, который ВСЕГДА возвращает
 *     настоящий объект Preference (никогда null): при первом обращении пункт запоминается
 *     и открепляется от экрана (removePreference) — визуально исчезает, а весь код
 *     приложения продолжает работать с реальным объектом (слушатели, summary и т.д.);
 *  3) служебный класс lm.PrefFix внедряется в dex приложения (ассет «preffix.dex»).
 *
 * Для androidx-приложений (в ресурсах есть attr app:isPreferenceVisible — Text Editor и др.)
 * перехват не нужен: штатный атрибут делает всё сам (см. InterfaceModifier).
 *
 * ВАЖНО: коды() служебного класса собираются здесь по инструкциям (new-array + aput-object);
 * индекс массива обязан лежать в регистре — не передавать номер итерации как регистр.
 */
object PreferenceHider {
    private const val FRAGMENT = "Landroid/preference/PreferenceFragment;"
    private const val PREF = "Landroid/preference/Preference;"
    private const val CHARSEQ = "Ljava/lang/CharSequence;"
    private const val HELPER = "Llm/PrefFix;"

    data class Result(
        /** ключи, скрытые перехватом findPreference (элемент остаётся в XML) */
        val covered: Set<String>,
        /** ключи без обращений в коде — можно безопасно удалить из XML */
        val noSites: Set<String>,
        /** ключи с обращениями вне фрагмента настроек — скрыть перехватом не удалось */
        val otherSites: Set<String>
    )

    /** id атрибута isPreferenceVisible (есть только у androidx-приложений), иначе null. */
    fun findVisibleAttrId(tableBlock: TableBlock): Int? {
        for (pkg in tableBlock.listPackages()) {
            val attrs = pkg.getResources("attr")
            while (attrs.hasNext()) {
                val entry = attrs.next()
                if (entry.name?.toString() == "isPreferenceVisible") return entry.getResourceId()
            }
        }
        return null
    }

    fun apply(
        apkModule: ApkModule,
        apkFile: File,
        workDir: File,
        keys: List<String>,
        helperDex: ByteArray?,
        onLog: (String) -> Unit
    ): Result {
        val keySet = keys.toSet()
        val covered = mutableSetOf<String>()
        val otherSites = mutableSetOf<String>()
        // все ключи, у которых в коде есть ЛЮБЫЕ обращения findPreference
        val sitedKeys = mutableSetOf<String>()
        if (helperDex == null) {
            onLog("⚠️ нет ассета preffix.dex — пропускаю перехват")
            return Result(emptySet(), keySet, emptySet())
        }
        try {
            val dexFiles = ApkUtils.extractAllDex(apkFile, workDir)
            for (dexFile in dexFiles) {
                try {
                    val outFile = File(dexFile.parentFile, dexFile.name + ".hider")
                    val injected = patchDex(dexFile, outFile, keySet, helperDex, onLog, covered, otherSites, sitedKeys)
                    if (injected > 0) {
                        apkModule.removeInputSource(dexFile.name)
                        val src = ByteInputSource(outFile.readBytes(), dexFile.name)
                        src.setMethod(ZipEntry.STORED)
                        apkModule.add(src)
                        onLog("🛡 ${dexFile.name}: перехватов findPreference: $injected")
                    }
                    outFile.delete()
                    dexFile.delete()
                } catch (e: Exception) {
                    onLog("⚠️ ${dexFile.name}: ${e.message}")
                }
            }
        } finally {
            try { workDir.deleteRecursively() } catch (_: Exception) {}
        }
        // Ключи БЕЗ обращений вообще — удаляем из XML (безопасно).
        // Ключи с обращениями, которые не удалось покрыть перехватом, НЕ удаляем:
        // лучше пункт останется видимым, чем приложение упадёт (страховка).
        val noSites = keySet - sitedKeys
        val uncovered = sitedKeys - covered - otherSites
        otherSites.addAll(uncovered)
        return Result(covered, noSites, otherSites)
    }

    private fun patchDex(
        dexFile: File,
        outFile: File,
        keys: Set<String>,
        helperDex: ByteArray,
        onLog: (String) -> Unit,
        covered: MutableSet<String>,
        otherSites: MutableSet<String>,
        sitedKeys: MutableSet<String>
    ): Int {
        val opcodes = Opcodes.forApi(28)
        val dex = DexFileFactory.loadDexFile(dexFile, opcodes)
        val byType = HashMap<String, ClassDef>()
        for (c in dex.classes) byType[c.type] = c

        // 1) скан вызовов findPreference; ключи по классам + приёмники
        val classKeys = HashMap<String, MutableSet<String>>()
        for (c in dex.classes) {
            for (m in c.methods) {
                val impl = m.implementation ?: continue
                val insns = impl.instructions.toList()
                for (idx in insns.indices) {
                    val ins = insns[idx]
                    if (ins !is ReferenceInstruction) continue
                    if (!ins.opcode.name.startsWith("invoke")) continue
                    val ref = ins.reference
                    if (ref !is MethodReference || ref.name != "findPreference") continue
                    val key = resolveKeyArg(ins, insns, idx) ?: continue
                    if (key !in keys) continue
                    sitedKeys.add(key)
                    classKeys.getOrPut(c.type) { mutableSetOf() }.add(key)
                    if (ref.definingClass != FRAGMENT) otherSites.add(key)
                }
            }
        }

        // 2) подклассы PreferenceFragment
        val fragments = mutableListOf<ClassDef>()
        for (c in dex.classes) {
            var t = c.superclass
            var guard = 0
            while (t != null && guard++ < 30) {
                if (t == FRAGMENT) { fragments.add(c); break }
                val sup = byType[t] ?: break
                t = sup.superclass
            }
        }
        if (fragments.isEmpty()) return 0

        // 3) внедрение перехвата в каждый фрагмент без своего findPreference
        val newClasses = mutableListOf<ClassDef>()
        var injected = 0
        val patchedTypes = mutableSetOf<String>()
        for (c in dex.classes) {
            val isFrag = fragments.any { it.type == c.type }
            if (!isFrag) { newClasses.add(c); continue }
            var has = false
            for (mm in c.methods) {
                if (mm.name == "findPreference" && mm.parameterTypes.size == 1 &&
                    mm.parameterTypes.first().toString() == CHARSEQ
                ) { has = true; break }
            }
            if (has) {
                onLog("⚠️ ${c.type}: уже есть findPreference — пропуск")
                newClasses.add(c)
                continue
            }
            val superFind = ImmutableMethodReference(FRAGMENT, "findPreference", listOf(CHARSEQ), PREF)
            val helperCheck = ImmutableMethodReference(HELPER, "check", listOf(FRAGMENT, CHARSEQ, PREF), PREF)
            val insns = mutableListOf<Instruction>()
            // ВАЖНО: параметры в dex лежат в ПОСЛЕДНИХ регистрах:
            // при registerCount=3 this=v1(p0), key=v2(p1), а v0 — локальная под результат.
            insns.add(ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2, 1, 2, 0, 0, 0, superFind))
            insns.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))
            insns.add(ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 2, 0, 0, 0, helperCheck))
            insns.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))
            insns.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            val impl = ImmutableMethodImplementation(3, insns, emptyList(), emptyList())
            val noAnnotations: Set<Annotation>? = null
            val newMethod = ImmutableMethod(
                c.type, "findPreference",
                listOf(ImmutableMethodParameter(CHARSEQ, noAnnotations, null)),
                PREF, 0x1, noAnnotations, null, impl
            )
            val virt = mutableListOf<Method>()
            virt.addAll(c.virtualMethods)
            virt.add(newMethod)
            newClasses.add(
                ImmutableClassDef(
                    c.type, c.accessFlags, c.superclass, c.interfaces, c.sourceFile,
                    c.annotations, c.staticFields, c.instanceFields, c.directMethods, virt
                )
            )
            injected++
            patchedTypes.add(c.type)
        }
        if (injected == 0) return 0

        // ключи, реально покрытые перехватом (обращения внутри пропатченных классов)
        for (t in patchedTypes) covered.addAll(classKeys[t] ?: emptySet())

        // 4) служебный класс lm.PrefFix с зашитыми ключами
        val helperFile = File(dexFile.parentFile, "preffix.helper.dex")
        helperFile.writeBytes(helperDex)
        val helperDexFile = DexFileFactory.loadDexFile(helperFile, opcodes)
        var helper: ClassDef? = null
        for (c in helperDexFile.classes) if (c.type == HELPER) helper = c
        helperFile.delete()
        val h = helper ?: throw IllegalStateException("в preffix.dex нет $HELPER")
        newClasses.add(bakeKeys(h, keys.toList()))

        DexFileFactory.writeDexFile(outFile.absolutePath, ImmutableDexFile(opcodes, newClasses))
        return injected
    }

    /** Ищет const-string, загружающий ключ в регистр последнего аргумента findPreference. */
    private fun resolveKeyArg(ins: Instruction, insns: List<Instruction>, idx: Int): String? {
        if (ins !is Instruction35c) return null
        if (ins.registerCount != 2) return null
        val keyReg = ins.registerD
        var j = idx - 1
        var steps = 0
        while (j >= 0 && steps < 14) {
            val pr = insns[j]
            if ((pr.opcode == Opcode.CONST_STRING || pr.opcode == Opcode.CONST_STRING_JUMBO) && pr is Instruction21c) {
                if (pr.registerA == keyReg) {
                    val ref = pr.reference
                    if (ref is StringReference) return ref.string
                }
            }
            j--
            steps++
        }
        return null
    }

    /** Заменяет keys() служебного класса на собранный по инструкциям список ключей. */
    private fun bakeKeys(helper: ClassDef, keys: List<String>): ClassDef {
        val direct = mutableListOf<Method>()
        for (m in helper.directMethods) {
            if (m.name != "keys") { direct.add(m); continue }
            val insns = mutableListOf<Instruction>()
            insns.add(ImmutableInstruction21s(Opcode.CONST_16, 0, keys.size))
            insns.add(ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 0, ImmutableTypeReference("[Ljava/lang/String;")))
            keys.forEachIndexed { i, k ->
                insns.add(ImmutableInstruction21s(Opcode.CONST_16, 2, i))
                insns.add(ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(k)))
                insns.add(ImmutableInstruction23x(Opcode.APUT_OBJECT, 1, 0, 2))
            }
            insns.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            val impl = ImmutableMethodImplementation(3, insns, emptyList(), emptyList())
            direct.add(
                ImmutableMethod(
                    m.definingClass, m.name, m.parameters, m.returnType,
                    m.accessFlags, m.annotations, m.hiddenApiRestrictions, impl
                )
            )
        }
        return ImmutableClassDef(
            helper.type, helper.accessFlags, helper.superclass, helper.interfaces, helper.sourceFile,
            helper.annotations, helper.staticFields, helper.instanceFields, direct, helper.virtualMethods.toList()
        )
    }
}
