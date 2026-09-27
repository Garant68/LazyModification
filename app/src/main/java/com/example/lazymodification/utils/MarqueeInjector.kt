package com.example.lazymodification.utils

import android.util.Log
import com.reandroid.apk.ApkModule
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.ExceptionHandler
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.TryBlock
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction10t
import org.jf.dexlib2.iface.instruction.formats.Instruction20t
import org.jf.dexlib2.iface.instruction.formats.Instruction21t
import org.jf.dexlib2.iface.instruction.formats.Instruction22t
import org.jf.dexlib2.iface.instruction.formats.Instruction30t
import org.jf.dexlib2.iface.instruction.formats.Instruction31t
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableExceptionHandler
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.ImmutableTryBlock
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.*
import java.io.File
import java.io.RandomAccessFile

data class MarqueeConfig(
    val text: String = " ★ LazyModification ★ ",
    val color: Int = 0xFFFFFFFF.toInt(),
    val textSizeDp: Int = 14,
    val gravity: Int = 0x30,
    val repeatLimit: Int = -1
) {
    companion object {
        const val GRAVITY_TOP = 0x30
        const val GRAVITY_CENTER = 0x10
        const val GRAVITY_BOTTOM = 0x50
        const val REPEAT_3 = 3
        const val REPEAT_INFINITE = -1
    }
}

object MarqueeInjector {
    private const val TAG = "MarqueeInjector"

    fun findLauncherActivity(apkFile: File, onStatus: (String) -> Unit): String {
        onStatus("🔍 Поиск главной активности...")
        val apkModule = ApkModule.loadApkFile(apkFile)
        val manifest = apkModule.androidManifest
            ?: throw IllegalStateException("AndroidManifest.xml не найден")
        val packageBlock = apkModule.tableBlock?.pickOne()
        if (packageBlock != null) manifest.setPackageBlock(packageBlock)
        val xml = manifest.serializeToXml()
        apkModule.close()

        val packageName = Regex("""package=["']([^"']+)["']""").find(xml)?.groupValues?.get(1)
            ?: throw Exception("Не удалось прочитать package из манифеста")

        val activityPattern = Regex(
            """<activity[^>]*?android:name=["']([^"']+)["'][^>]*?>([\s\S]*?)</activity>""",
            RegexOption.IGNORE_CASE
        )

        for (match in activityPattern.findAll(xml)) {
            val activityName = match.groupValues[1]
            val body = match.groupValues[2]
            if (body.contains("android.intent.action.MAIN") &&
                body.contains("android.intent.category.LAUNCHER")
            ) {
                val fullName = if (activityName.startsWith(".")) {
                    packageName + activityName
                } else activityName
                onStatus("✅ Главная активность: $fullName")
                return fullName
            }
        }
        throw Exception("LAUNCHER-активность не найдена в манифесте")
    }

    fun injectMarquee(
        inputApk: File,
        outputApk: File,
        launcherClass: String,
        config: MarqueeConfig,
        onStatus: (String) -> Unit
    ) {
        val launcherType = "L${launcherClass.replace('.', '/')};"

        val workDir = File(inputApk.parentFile ?: File("/sdcard"),
            "marquee_work_${System.currentTimeMillis()}")
        workDir.mkdirs()
        val dexDir = File(workDir, "dex")
        dexDir.mkdirs()

        try {
            onStatus("📦 Извлечение DEX...")
            val dexFiles = ApkUtils.extractAllDex(inputApk, dexDir)

            var targetDexIndex = -1
            for ((i, dex) in dexFiles.withIndex()) {
                val api = readDexApi(dex)
                val opcodes = Opcodes.forApi(api)
                val dexFile = DexFileFactory.loadDexFile(dex, opcodes)
                if (dexFile.classes.any { it.type == launcherType }) {
                    targetDexIndex = i
                    break
                }
            }
            if (targetDexIndex < 0) {
                throw Exception("Класс $launcherClass не найден ни в одном DEX")
            }

            onStatus("🔧 Патчинг DEX #${targetDexIndex + 1}/${dexFiles.size}...")
            val targetDex = dexFiles[targetDexIndex]
            val patchedDex = File(workDir, "patched_${targetDex.name}")
            val api = readDexApi(targetDex)
            val opcodes = Opcodes.forApi(api)
            patchDexForMarquee(targetDex, patchedDex, launcherType, opcodes, config, onStatus)

            onStatus("📦 Перепаковка APK...")
            val dexMap = HashMap<String, File>()
            dexMap[targetDex.name] = patchedDex
            ApkUtils.repackApkWithMultipleDex(inputApk, dexMap, outputApk)

            onStatus("✅ Бегущая строка успешно добавлена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инъекции marquee", e)
            throw e
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun readDexApi(dexFile: File): Int {
        RandomAccessFile(dexFile, "r").use { raf ->
            raf.seek(0)
            val magic = ByteArray(8)
            raf.read(magic)
            val version = String(magic, 4, 3, Charsets.US_ASCII)
            return when (version) {
                "035" -> 13
                "037" -> 17
                "038" -> 21
                "039" -> 28
                "040" -> 28
                else -> 28
            }
        }
    }

    private fun patchDexForMarquee(
        inputDex: File,
        outputDex: File,
        launcherType: String,
        opcodes: Opcodes,
        config: MarqueeConfig,
        onStatus: (String) -> Unit
    ) {
        val dex = DexFileFactory.loadDexFile(inputDex, opcodes)

        val patchedClasses = dex.classes.map { classDef ->
            if (classDef.type == launcherType) {
                patchLauncherClass(classDef, launcherType, config, onStatus)
            } else classDef
        }

        DexFileFactory.writeDexFile(
            outputDex.absolutePath,
            ImmutableDexFile(opcodes, patchedClasses)
        )
    }

    private fun patchLauncherClass(
        classDef: ClassDef,
        launcherType: String,
        config: MarqueeConfig,
        onStatus: (String) -> Unit
    ): ClassDef {
        // Ищем метод жизненного цикла с реализацией, в который можно вставить вызов.
        // Приоритет: onCreate(Bundle), onPostCreate(Bundle), onStart(), onResume().
        val hook = findLifecycleHook(classDef)
            ?: return patchWithSyntheticOnCreate(classDef, launcherType, config, onStatus)

        val onCreate = hook
        val onCreateImpl = onCreate.implementation
            ?: throw Exception("Метод ${onCreate.name} не имеет реализации")

        val registerCount = onCreateImpl.registerCount
        // p0 (this) = registerCount - (количество параметров + 1)
        // для onCreate(Bundle): 1 параметр → p0 = registerCount - 2
        // для onStart()/onResume(): 0 параметров → p0 = registerCount - 1
        val paramCount = onCreate.parameterTypes.size
        val p0Register = registerCount - paramCount - 1
        onStatus("  📝 ${onCreate.name}: регистров=$registerCount, p0=v$p0Register")

        val instructions = onCreateImpl.instructions.toList()
        val returnVoidIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        if (returnVoidIndex < 0) {
            throw Exception("return-void не найден в ${onCreate.name}")
        }

        // Считаем code-address каждой инструкции и адрес return-void
        val instructionAddresses = IntArray(instructions.size)
        var running = 0
        for (i in instructions.indices) {
            instructionAddresses[i] = running
            running += instructions[i].codeUnits
        }
        val returnVoidCodeAddress = instructionAddresses[returnVoidIndex]

        // Инструкция invoke-direct (35c) = 3 code units
        val INSERT_SHIFT = 3

        val marqueeMethod = createMarqueeMethod(launcherType, config)

        val invokeInstruction = ImmutableInstruction35c(
            Opcode.INVOKE_DIRECT,
            1,
            p0Register,
            0, 0, 0, 0,
            ImmutableMethodReference(
                launcherType,
                "addMarqueeText",
                emptyList(),
                "V"
            )
        )

        // 👉 Пересоздаём ВСЕ инструкции ветвления (goto/if/packed-switch/etc),
        //     target-адрес которых >= insertCodeAddress, увеличивая их codeOffset на 3.
        val insertCodeAddress = returnVoidCodeAddress
        val remappedInstructions = instructions.mapIndexed { i, ins ->
            val addr = instructionAddresses[i]
            shiftBranchIfNeeded(ins, addr, insertCodeAddress, INSERT_SHIFT)
        }

        val newInstructions = remappedInstructions.toMutableList()
        newInstructions.add(returnVoidIndex, invokeInstruction)

        // 👇 КРИТИЧЕСКИ ВАЖНО: сдвигаем адреса try-блоков и обработчиков.
        //   Инструкция invoke-direct (35c) = 3 code units.
        //   Все адреса (try-start, try-end, handler) >= точки вставки двигаем на +3.

        val shiftedTryBlocks: List<TryBlock<out ExceptionHandler>> = onCreateImpl.tryBlocks.map { tb ->
            val start = tb.startCodeAddress
            val end = tb.startCodeAddress + tb.codeUnitCount
            val newStart = if (start >= insertCodeAddress) start + INSERT_SHIFT else start
            val newEnd = if (end > insertCodeAddress) end + INSERT_SHIFT else end
            val shiftedHandlers = tb.exceptionHandlers.map { eh ->
                ImmutableExceptionHandler(
                    eh.exceptionType,
                    if (eh.handlerCodeAddress >= insertCodeAddress) eh.handlerCodeAddress + INSERT_SHIFT else eh.handlerCodeAddress
                )
            }
            ImmutableTryBlock(newStart, newEnd - newStart, shiftedHandlers)
        }

        // Debug items НЕ сдвигаем — они не влияют на выполнение, а их Immutable-конструктор
        // не гарантирует пересчёт адресов. Просто копируем как есть.
        val shiftedDebugItems = onCreateImpl.debugItems

        val newOnCreateImpl = ImmutableMethodImplementation(
            registerCount,
            newInstructions,
            shiftedTryBlocks,
            shiftedDebugItems
        )

        val newOnCreate = ImmutableMethod(
            onCreate.definingClass,
            onCreate.name,
            onCreate.parameters,
            onCreate.returnType,
            onCreate.accessFlags,
            onCreate.annotations,
            onCreate.hiddenApiRestrictions,
            newOnCreateImpl
        )

        val newDirectMethods = classDef.directMethods.map { m ->
            if (m.name == onCreate.name &&
                m.parameters == onCreate.parameters
            ) newOnCreate else m
        }.toMutableList()

        val newVirtualMethods = classDef.virtualMethods.map { m ->
            if (m.name == onCreate.name &&
                m.parameters == onCreate.parameters
            ) newOnCreate else m
        }.toMutableList()

        newDirectMethods.add(marqueeMethod)

        onStatus("  ✅ Метод addMarqueeText() создан (PRIVATE)")
        onStatus("  ✅ Вызов вставлен в ${onCreate.name} (перед return-void)")

        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            newDirectMethods,
            newVirtualMethods
        )
    }

    // ============================================================
    // ✅ Поиск метода жизненного цикла с реализацией
    // ============================================================
    private fun findLifecycleHook(classDef: ClassDef): Method? {
        val candidates = listOf(
            "onCreate" to listOf("Landroid/os/Bundle;"),
            "onPostCreate" to listOf("Landroid/os/Bundle;"),
            "onStart" to emptyList(),
            "onResume" to emptyList()
        )
        val allMethods = classDef.directMethods + classDef.virtualMethods
        for ((name, params) in candidates) {
            val m = allMethods.find {
                it.name == name &&
                        it.parameterTypes == params &&
                        it.implementation != null
            } ?: continue
            return m
        }
        return null
    }

    // ============================================================
    // ✅ Синтез onCreate(Bundle) override, если в классе нет ни одного хука.
    //    Вызывает super.onCreate(bundle) + addMarqueeText().
    // ============================================================
    private fun patchWithSyntheticOnCreate(
        classDef: ClassDef,
        launcherType: String,
        config: MarqueeConfig,
        onStatus: (String) -> Unit
    ): ClassDef {
        val marqueeMethod = createMarqueeMethod(launcherType, config)

        val ins = mutableListOf<org.jf.dexlib2.iface.instruction.Instruction>()
        val superclassType = classDef.superclass ?: "Landroid/app/Activity;"
        // invoke-super {p0, p1}, <superclass>->onCreate(Landroid/os/Bundle;)V
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2,
            0, 1, 0, 0, 0,
            ImmutableMethodReference(
                superclassType,
                "onCreate",
                listOf("Landroid/os/Bundle;"),
                "V"
            )))
        // invoke-direct {p0}, launcherType->addMarqueeText()V
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1,
            0, 0, 0, 0, 0,
            ImmutableMethodReference(
                launcherType,
                "addMarqueeText",
                emptyList(),
                "V"
            )))
        ins.add(ImmutableInstruction10x(Opcode.RETURN_VOID))

        val impl = ImmutableMethodImplementation(2, ins, emptyList(), emptyList())
        val syntheticOnCreate = ImmutableMethod(
            launcherType,
            "onCreate",
            listOf<org.jf.dexlib2.iface.MethodParameter>(
                ImmutableMethodParameter(
                    "Landroid/os/Bundle;",
                    emptySet<org.jf.dexlib2.iface.Annotation>(),
                    null
                )
            ),
            "V",
            AccessFlags.PUBLIC.value,
            emptySet<org.jf.dexlib2.iface.Annotation>(),
            emptySet<org.jf.dexlib2.HiddenApiRestriction>(),
            impl
        )

        val newDirectMethods = classDef.directMethods.toMutableList()
        newDirectMethods.add(marqueeMethod)

        val newVirtualMethods = classDef.virtualMethods.toMutableList()
        // Не заменяем ничего — onCreate в классе отсутствовал, просто добавляем
        newVirtualMethods.add(syntheticOnCreate)

        onStatus("  ⚠️ onCreate не найден — создан override с super.onCreate(Bundle)")
        onStatus("  ✅ Метод addMarqueeText() создан (PRIVATE)")

        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            classDef.staticFields,
            classDef.instanceFields,
            newDirectMethods,
            newVirtualMethods
        )
    }

    // ===========================================================
    // Регистры (registerCount = 14):
    //   p0 = v13 = this
    //   v0 = TextView
    //   v1 = temp (string, bool, int)
    //   v2 = temp (SGET, int, gravity)
    //   v3 = Window / decorView
    //   v4 = content ViewGroup
    //   v5 = FrameLayout.LayoutParams
    //   v6 = temp (int/float, MATCH_PARENT, color 1.0f и т.п.)
    //   v7 = temp (int/float, WRAP_CONTENT, 0.0f и т.п.)
    //   v8 = AlphaAnimation (только в блоке repeatLimit > 0)
    //   v9 = color int (если не белый)
    //   v10 = textSize float bits (если не 14dp)
    //   v11:v12 = long-пара (ТОЛЬКО для setDuration/setStartOffset; никогда как int)
    // ===========================================================
    private fun createMarqueeMethod(launcherType: String, config: MarqueeConfig): ImmutableMethod {
        val ins = mutableListOf<org.jf.dexlib2.iface.instruction.Instruction>()

        // Удлиняем текст для гарантированной прокрутки: если меньше 80 символов —
        // повторяем с пробелами, чтобы текст гарантированно не помещался на экране.
        val marqueeText = if (config.text.length < 80) {
            config.text.trimEnd() + " ".repeat(20) + config.text.trimEnd() + " ".repeat(20) + config.text.trimEnd()
        } else config.text

        // v0 = new TextView(this)
        ins.add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0,
            ImmutableTypeReference("Landroid/widget/TextView;")))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 2,
            0, 13, 0, 0, 0,
            ImmutableMethodReference("Landroid/widget/TextView;", "<init>",
                listOf("Landroid/content/Context;"), "V")))

        // v1 = text
        ins.add(ImmutableInstruction21c(Opcode.CONST_STRING, 1,
            ImmutableStringReference(marqueeText)))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
            0, 1, 0, 0, 0,
            ImmutableMethodReference("Landroid/widget/TextView;", "setText",
                listOf("Ljava/lang/CharSequence;"), "V")))

        // setSingleLine(true)
        ins.add(ImmutableInstruction11n(Opcode.CONST_4, 1, 1))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
            0, 1, 0, 0, 0,
            ImmutableMethodReference("Landroid/widget/TextView;", "setSingleLine",
                listOf("Z"), "V")))

        // setEllipsize(MARQUEE)
        ins.add(ImmutableInstruction21c(Opcode.SGET_OBJECT, 2,
            ImmutableFieldReference(
                "Landroid/text/TextUtils\$TruncateAt;",
                "MARQUEE",
                "Landroid/text/TextUtils\$TruncateAt;"
            )))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
            0, 2, 0, 0, 0,
            ImmutableMethodReference("Landroid/widget/TextView;", "setEllipsize",
                listOf("Landroid/text/TextUtils\$TruncateAt;"), "V")))

        // setMarqueeRepeatLimit
        ins.add(ImmutableInstruction11n(Opcode.CONST_4, 1, config.repeatLimit))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
            0, 1, 0, 0, 0,
            ImmutableMethodReference("Landroid/widget/TextView;", "setMarqueeRepeatLimit",
                listOf("I"), "V")))

        // setHorizontallyScrolling(true)
        ins.add(ImmutableInstruction11n(Opcode.CONST_4, 1, 1))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
            0, 1, 0, 0, 0,
            ImmutableMethodReference("Landroid/widget/TextView;", "setHorizontallyScrolling",
                listOf("Z"), "V")))

        // setSelected(true)
        ins.add(ImmutableInstruction11n(Opcode.CONST_4, 1, 1))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
            0, 1, 0, 0, 0,
            ImmutableMethodReference("Landroid/widget/TextView;", "setSelected",
                listOf("Z"), "V")))

        // setTextColor (если не белый)
        val defaultColor = 0xFFFFFFFF.toInt()
        if (config.color != defaultColor) {
            ins.add(ImmutableInstruction31i(Opcode.CONST, 9, config.color))
            ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
                0, 9, 0, 0, 0,
                ImmutableMethodReference("Landroid/widget/TextView;", "setTextColor",
                    listOf("I"), "V")))
        }

        // setTextSize(COMPLEX_UNIT_DIP, size) — если не 14
        if (config.textSizeDp != 14) {
            val floatBits = java.lang.Float.floatToRawIntBits(config.textSizeDp.toFloat())
            ins.add(ImmutableInstruction11n(Opcode.CONST_4, 1, 0))  // COMPLEX_UNIT_DIP = 0
            ins.add(ImmutableInstruction31i(Opcode.CONST, 10, floatBits))
            ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3,
                0, 1, 10, 0, 0,
                ImmutableMethodReference("Landroid/widget/TextView;", "setTextSize",
                    listOf("I", "F"), "V")))
        }

        // v3 = getWindow().getDecorView()
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1,
            13, 0, 0, 0, 0,
            ImmutableMethodReference("Landroid/app/Activity;", "getWindow",
                emptyList(), "Landroid/view/Window;")))
        ins.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1,
            3, 0, 0, 0, 0,
            ImmutableMethodReference("Landroid/view/Window;", "getDecorView",
                emptyList(), "Landroid/view/View;")))
        ins.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3))

        // v4 = v3.findViewById(android.R.id.content)
        ins.add(ImmutableInstruction31i(Opcode.CONST, 1, 0x01020002))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
            3, 1, 0, 0, 0,
            ImmutableMethodReference("Landroid/view/View;", "findViewById",
                listOf("I"), "Landroid/view/View;")))
        ins.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4))
        ins.add(ImmutableInstruction21c(Opcode.CHECK_CAST, 4,
            ImmutableTypeReference("Landroid/view/ViewGroup;")))

        // v5 = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        ins.add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 5,
            ImmutableTypeReference("Landroid/widget/FrameLayout\$LayoutParams;")))
        ins.add(ImmutableInstruction11n(Opcode.CONST_4, 6, -1))
        ins.add(ImmutableInstruction11n(Opcode.CONST_4, 7, -2))
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3,
            5, 6, 7, 0, 0,
            ImmutableMethodReference("Landroid/widget/FrameLayout\$LayoutParams;",
                "<init>", listOf("I", "I"), "V")))

        // iput gravity, v5->gravity — если не TOP
        if (config.gravity != MarqueeConfig.GRAVITY_TOP) {
            ins.add(ImmutableInstruction31i(Opcode.CONST, 2, config.gravity))
            ins.add(ImmutableInstruction22c(Opcode.IPUT, 2, 5,
                ImmutableFieldReference(
                    "Landroid/widget/FrameLayout\$LayoutParams;",
                    "gravity",
                    "I"
                )))
        }

        // addView(tv, params)
        ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3,
            4, 0, 5, 0, 0,
            ImmutableMethodReference("Landroid/view/ViewGroup;", "addView",
                listOf("Landroid/view/View;", "Landroid/view/ViewGroup\$LayoutParams;"), "V")))

        // Если repeatLimit > 0 — через AlphaAnimation (fadeOut с fillAfter) скрываем текст
        // после завершения всех циклов прокрутки.
        if (config.repeatLimit > 0) {
            // Время одного прохода примерно пропорционально длине текста:
            // ~5 символов/сек (эмпирически), минимум 4 сек, +2 сек запаса.
            val timePerCycle = maxOf(4000L, (config.text.length * 1000L) / 5L)
            val delayMillis = timePerCycle * config.repeatLimit + 2000L

            // v6 = 1.0f bits, v7 = 0.0f
            ins.add(ImmutableInstruction31i(Opcode.CONST, 6, 0x3F800000))
            ins.add(ImmutableInstruction31i(Opcode.CONST, 7, 0))
            // v8 = new AlphaAnimation(1.0f, 0.0f)
            ins.add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 8,
                ImmutableTypeReference("Landroid/view/animation/AlphaAnimation;")))
            ins.add(ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3,
                8, 6, 7, 0, 0,
                ImmutableMethodReference("Landroid/view/animation/AlphaAnimation;", "<init>",
                    listOf("F", "F"), "V")))
            // setDuration(1000) — const-wide v11:v12 (dedicated long pair)
            ins.add(ImmutableInstruction51l(Opcode.CONST_WIDE, 11, 1000L))
            ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3,
                8, 11, 12, 0, 0,
                ImmutableMethodReference("Landroid/view/animation/Animation;", "setDuration",
                    listOf("J"), "V")))
            // setStartOffset(delayMillis) — const-wide v11:v12
            ins.add(ImmutableInstruction51l(Opcode.CONST_WIDE, 11, delayMillis))
            ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3,
                8, 11, 12, 0, 0,
                ImmutableMethodReference("Landroid/view/animation/Animation;", "setStartOffset",
                    listOf("J"), "V")))
            // setFillAfter(true)
            ins.add(ImmutableInstruction11n(Opcode.CONST_4, 6, 1))
            ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
                8, 6, 0, 0, 0,
                ImmutableMethodReference("Landroid/view/animation/Animation;", "setFillAfter",
                    listOf("Z"), "V")))
            // tv.startAnimation(fadeOut)
            ins.add(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2,
                0, 8, 0, 0, 0,
                ImmutableMethodReference("Landroid/view/View;", "startAnimation",
                    listOf("Landroid/view/animation/Animation;"), "V")))
        }

        ins.add(ImmutableInstruction10x(Opcode.RETURN_VOID))

        val impl = ImmutableMethodImplementation(
            14,
            ins,
            emptyList(),
            emptyList()
        )

        return ImmutableMethod(
            launcherType,
            "addMarqueeText",
            emptyList(),
            "V",
            AccessFlags.PRIVATE.value,
            emptySet(),
            emptySet(),
            impl
        )
    }

    // ============================================================
    // ✅ Пересчёт относительных смещений переходов (goto/if/switch)
    //
    // Правильная формула: offset = target - source.
    // При вставке 3 code units в позицию insertCodeAddress:
    //   - инструкция с адресом >= insertCodeAddress сдвигается на +3 (sourceShift)
    //   - цель с адресом >= insertCodeAddress сдвигается на +3 (targetShift)
    //   newOffset = oldOffset + targetShift - sourceShift
    // ============================================================
    private fun shiftBranchIfNeeded(
        ins: Instruction,
        insAddress: Int,
        insertCodeAddress: Int,
        shift: Int
    ): Instruction {
        if (ins !is OffsetInstruction) return ins
        val oldOffset = ins.codeOffset
        val targetAddress = insAddress + oldOffset
        val sourceShifted = insAddress >= insertCodeAddress
        val targetShifted = targetAddress >= insertCodeAddress
        if (sourceShifted == targetShifted) return ins
        val newOffset = oldOffset + (if (targetShifted) shift else -shift)
        return when (ins) {
            is Instruction10t -> ImmutableInstruction10t(ins.opcode, newOffset)
            is Instruction20t -> ImmutableInstruction20t(ins.opcode, newOffset)
            is Instruction21t -> ImmutableInstruction21t(ins.opcode, ins.registerA, newOffset)
            is Instruction22t -> ImmutableInstruction22t(ins.opcode, ins.registerA, ins.registerB, newOffset)
            is Instruction30t -> ImmutableInstruction30t(ins.opcode, newOffset)
            is Instruction31t -> ImmutableInstruction31t(ins.opcode, ins.registerA, newOffset)
            else -> ins
        }
    }
}