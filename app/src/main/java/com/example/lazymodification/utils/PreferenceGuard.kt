package com.example.lazymodification.utils

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import java.io.File
import java.util.zip.ZipEntry

/**
 * Защита от NPE после удаления Preference из XML настроек.
 *
 * Фрагменты настроек часто пишут так БЕЗ проверки на null:
 *
 *     findPreference("rate_application").setOnPreferenceClickListener(...)
 *
 * Если пункт удалён из XML, findPreference возвращает null → вызов сеттера на null →
 * NullPointerException → вылет при открытии экрана настроек
 * (пример: Tetra Filer, jp.main.brits.android.filer).
 *
 * Патч: находим в dex последовательность
 *
 *     const-string "KEY"; invoke findPreference; move-result-object vX;
 *     invoke vX->setOnPreference(Click|Change)Listener
 *
 * и заменяем move-result-object + сеттер на NOP (1-в-1 по код-юнитам).
 * Если между move-result и сеттером есть проверка (if-eqz и т.п.) — не трогаем:
 * такой код сам безопасен (пример: Text Editor, ключ credits — там if-eqz есть).
 */
object PreferenceGuard {

    private val LISTENER_NAMES = setOf("setOnPreferenceClickListener", "setOnPreferenceChangeListener")

    fun patchDex(inputDex: File, outputDex: File, keys: List<String>): Int {
        if (keys.isEmpty()) return 0
        val keySet = keys.toSet()
        val opcodes = Opcodes.forApi(28)
        val dex = DexFileFactory.loadDexFile(inputDex, opcodes)
        var patched = 0

        val newClasses = dex.classes.map { classDef ->
            var classChanged = false
            val newDirect = classDef.directMethods.map { m ->
                val pm = patchMethod(m, keySet) { patched++ }
                if (pm !== m) classChanged = true
                pm
            }
            val newVirtual = classDef.virtualMethods.map { m ->
                val pm = patchMethod(m, keySet) { patched++ }
                if (pm !== m) classChanged = true
                pm
            }
            if (!classChanged) classDef
            else ImmutableClassDef(
                classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces,
                classDef.sourceFile, classDef.annotations, classDef.staticFields,
                classDef.instanceFields, newDirect, newVirtual
            )
        }

        if (patched > 0) {
            DexFileFactory.writeDexFile(outputDex.absolutePath, ImmutableDexFile(opcodes, newClasses))
        }
        return patched
    }

    private fun patchMethod(m: Method, keys: Set<String>, onPatch: () -> Unit): Method {
        val impl = m.implementation ?: return m
        val insns = impl.instructions.toList()
        var changed = false
        val out = ArrayList<Instruction>(insns.size + 4)
        var i = 0
        while (i < insns.size) {
            val ins = insns[i]
            val isKeyConst = (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                    ins is ReferenceInstruction &&
                    (ins.reference as? StringReference)?.string in keys
            if (!isKeyConst) {
                out.add(ins)
                i++
                continue
            }
            // const-string с нашим ключом; ищем рядом invoke findPreference (до 3 инструкций)
            var j = i + 1
            var findIdx = -1
            while (j < insns.size && j <= i + 3) {
                val nx = insns[j]
                if (nx.opcode.name.startsWith("invoke") && nx is ReferenceInstruction &&
                    (nx.reference as? MethodReference)?.name == "findPreference"
                ) {
                    findIdx = j
                    break
                }
                val n = nx.opcode.name
                if (n.startsWith("const") || n.startsWith("move")) {
                    j++
                    continue
                }
                break
            }
            if (findIdx < 0) {
                out.add(ins)
                i++
                continue
            }
            // копируем [const..findPreference]
            while (i <= findIdx) {
                out.add(insns[i])
                i++
            }
            // после findPreference должен идти move-result-object и сразу сеттер (без if-проверки)
            if (i + 1 < insns.size && insns[i].opcode == Opcode.MOVE_RESULT_OBJECT) {
                val moveIns = insns[i]
                val nxt = insns[i + 1]
                val isListener = nxt.opcode.name.startsWith("invoke") && nxt is ReferenceInstruction &&
                        (nxt.reference as? MethodReference)?.name in LISTENER_NAMES
                if (isListener) {
                    for (u in 0 until moveIns.codeUnits) out.add(ImmutableInstruction10x(Opcode.NOP))
                    for (u in 0 until nxt.codeUnits) out.add(ImmutableInstruction10x(Opcode.NOP))
                    i += 2
                    changed = true
                    onPatch()
                    continue
                }
            }
            // не патчим — const+find уже скопированы, продолжаем
        }
        if (!changed) return m
        val newImpl = ImmutableMethodImplementation(impl.registerCount, out, impl.tryBlocks, impl.debugItems)
        return ImmutableMethod(
            m.definingClass, m.name, m.parameters, m.returnType,
            m.accessFlags, m.annotations, m.hiddenApiRestrictions, newImpl
        )
    }

    /** Извлекает dex, патчит, заменяет в открытом ApkModule. */
    fun applyToModule(apkModule: ApkModule, sourceApk: File, workDir: File, keys: List<String>, onLog: (String) -> Unit): Int {
        var total = 0
        try {
            val dexFiles = ApkUtils.extractAllDex(sourceApk, workDir)
            for (dexFile in dexFiles) {
                val outFile = File(dexFile.parentFile, dexFile.name + ".guard")
                val n = patchDex(dexFile, outFile, keys)
                if (n > 0) {
                    total += n
                    apkModule.removeInputSource(dexFile.name)
                    val src = ByteInputSource(outFile.readBytes(), dexFile.name)
                    src.setMethod(ZipEntry.STORED)
                    apkModule.add(src)
                    onLog("🛡 ${dexFile.name}: обойдено NPE-вызовов: $n")
                }
                outFile.delete()
                dexFile.delete()
            }
        } finally {
            try { workDir.deleteRecursively() } catch (_: Exception) {}
        }
        return total
    }
}
