package com.example.lazymodification.utils.patches

import java.io.File

/**
 * Ищет в папке готовые патчи, подходящие к выбранному APK по пакету и версии.
 * Используется экраном «Патчинг» для предложения «Применить / Нет».
 */
object PatchAutoOffer {

    private val extensions = setOf("zip", "txt", LmPatchContainer.EXTENSION)

    fun find(
        directory: File,
        packageName: String?,
        versionName: String?,
        versionCode: Int?,
        extension: String? = null
    ): List<File> {
        if (packageName == null || !directory.isDirectory) return emptyList()
        val lmOnly = extension.equals(LmPatchContainer.EXTENSION, ignoreCase = true)
        val accepted = if (extension == null) extensions else setOf(extension.lowercase())
        val candidates = directory.listFiles { file ->
            file.isFile && if (lmOnly) LmPatchContainer.isLmPatch(file) else file.extension.lowercase() in accepted
        }?.sortedWith(
            compareByDescending<File> {
                LmPatchContainer.isLmPatch(it) && it.name.endsWith(".lmpatch", ignoreCase = true)
            }.thenByDescending { it.lastModified() }
        )?.filter { file ->
            isCompatible(file, packageName, versionName, versionCode)
        }.orEmpty()
        return candidates.distinctBy(::dedupeKey)
    }

    private fun isCompatible(file: File, packageName: String, versionName: String?, versionCode: Int?): Boolean {
        // Битый или чужой ZIP не должен ломать открытие экрана.
        return try {
            if (LmPatchContainer.isLmPatch(file)) {
                return LmPatchContainer.readManifest(file).targetPackage == packageName
            }
            PatchCompatibility.check(PatchFormat.read(file), packageName, versionName, versionCode).compatible
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Старые сборки могли сохранить один и тот же патч как .lmpatch и
     * .lmpatch.zip. Не предлагаем оба: второй уже не совпадёт по старому коду.
     */
    private fun dedupeKey(file: File): String {
        if (!LmPatchContainer.isLmPatch(file)) return file.absolutePath
        return runCatching {
            val m = LmPatchContainer.readManifest(file)
            "lm:${m.targetPackage}:${m.baseVersionCode}:${m.newVersionCode}:" +
                "${m.methodRules}:${m.fileRules}:${m.removedRules}"
        }.getOrElse { file.absolutePath }
    }
}
