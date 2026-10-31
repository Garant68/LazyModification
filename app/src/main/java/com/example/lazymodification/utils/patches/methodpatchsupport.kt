package com.example.lazymodification.utils.patches

import org.jf.dexlib2.iface.ExceptionHandler
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.TryBlock
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.instruction.formats.ArrayPayload
import org.jf.dexlib2.iface.instruction.formats.PackedSwitchPayload
import org.jf.dexlib2.iface.instruction.formats.SparseSwitchPayload
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import java.security.MessageDigest

/**
 * Общая логика method-патчей: сигнатура, нормализованное тело и хеш.
 *
 * В хеш не попадает отладочная информация (.line/.local/.param), иначе любая
 * пересборка APK ломала бы совместимость. Инструкции, ссылки и try/catch
 * входят в хеш: если поведение метода изменилось, патч не должен применяться.
 */
object MethodPatchSupport {

    data class MethodKey(val owner: String, val name: String, val descriptor: String)

    fun key(owner: String, method: Method): MethodKey =
        MethodKey(owner, method.name, methodDescriptor(method))

    fun methodDescriptor(method: Method): String =
        "(" + method.parameterTypes.joinToString("") + ")" + method.returnType

    private fun methodDescriptor(method: MethodReference): String =
        "(" + method.parameterTypes.joinToString("") + ")" + method.returnType

    fun bodyHash(method: Method): String {
        val impl = method.implementation ?: return ""
        return sha256(bodyText(method, impl).toByteArray(Charsets.UTF_8))
    }

    fun bodyText(method: Method, impl: MethodImplementation): String {
        val sb = StringBuilder()
        sb.append(method.accessFlags).append('|')
        sb.append(method.name).append(methodDescriptor(method)).append('|')
        sb.append(impl.registerCount).append('\n')
        for (instruction in impl.instructions) {
            appendInstruction(sb, instruction)
            sb.append('\n')
        }
        for (tryBlock in impl.tryBlocks) {
            appendTryBlock(sb, tryBlock)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun appendInstruction(sb: StringBuilder, instruction: org.jf.dexlib2.iface.instruction.Instruction) {
        sb.append(instruction.opcode.name)
        when (instruction) {
            is OneRegisterInstruction -> sb.append(" v").append(instruction.registerA)
            is TwoRegisterInstruction -> sb.append(" v").append(instruction.registerA)
                .append(", v").append(instruction.registerB)
            is ThreeRegisterInstruction -> sb.append(" v").append(instruction.registerA)
                .append(", v").append(instruction.registerB)
                .append(", v").append(instruction.registerC)
            is FiveRegisterInstruction -> {
                val regs = listOf(
                    instruction.registerC, instruction.registerD, instruction.registerE,
                    instruction.registerF, instruction.registerG
                )
                sb.append(" {v").append(regs.take(instruction.registerCount).joinToString(", v")).append('}')
            }
            is RegisterRangeInstruction -> sb.append(" {v").append(instruction.startRegister)
                .append(" .. v").append(instruction.startRegister + instruction.registerCount - 1).append('}')
        }
        when (instruction) {
            is NarrowLiteralInstruction -> sb.append(", #").append(instruction.narrowLiteral)
            is WideLiteralInstruction -> sb.append(", ##").append(instruction.wideLiteral)
        }
        if (instruction is ReferenceInstruction) {
            when (val ref = instruction.reference) {
                is StringReference -> sb.append(", str:").append(ref.string)
                is TypeReference -> sb.append(", type:").append(ref.type)
                is FieldReference -> sb.append(", field:")
                    .append(ref.definingClass).append("->").append(ref.name).append(':').append(ref.type)
                is MethodReference -> sb.append(", method:")
                    .append(ref.definingClass).append("->").append(ref.name)
                    .append(methodDescriptor(ref))
            }
        }
        // Payload-инструкции не являются обычными register-инструкциями.
        when (instruction) {
            is PackedSwitchPayload -> {
                sb.append(", packed:")
                instruction.switchElements.forEach { sb.append(it.key).append(':').append(it.offset).append(',') }
            }
            is SparseSwitchPayload -> {
                sb.append(", sparse:")
                instruction.switchElements.forEach { sb.append(it.key).append(':').append(it.offset).append(',') }
            }
            is ArrayPayload -> {
                sb.append(", array:").append(instruction.elementWidth).append(':')
                instruction.arrayElements.forEach { sb.append(it).append(',') }
            }
        }
    }

    private fun appendTryBlock(sb: StringBuilder, tryBlock: TryBlock<out ExceptionHandler>) {
        sb.append("try ").append(tryBlock.startCodeAddress)
            .append('+').append(tryBlock.codeUnitCount)
        for (handler in tryBlock.exceptionHandlers) {
            sb.append(" -> ").append(handler.handlerCodeAddress)
                .append(':').append(handler.exceptionType ?: "*")
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun fileSha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
