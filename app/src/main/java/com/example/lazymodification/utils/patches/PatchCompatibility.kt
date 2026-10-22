package com.example.lazymodification.utils.patches

/** Проверка, что ZIP-патч предназначен для выбранного APK. */
object PatchCompatibility {
    data class Result(
        val compatible: Boolean,
        val packageOk: Boolean,
        val versionNameOk: Boolean,
        val versionCodeOk: Boolean,
        val message: String
    )

    fun check(
        document: PatchDocument,
        packageName: String?,
        versionName: String?,
        versionCode: Int?
    ): Result {
        val packages = patterns(document.packagePattern)
        val packageOk = packageName != null && (packages.isEmpty() || packages.any {
            PatchEngine.glob(it).matcher(packageName).matches()
        })
        val versions = patterns(document.versionNamePattern)
        val versionNameOk = versionName != null && (versions.isEmpty() || versions.any {
            PatchEngine.glob(it).matcher(versionName).matches()
        })
        val versionCodeOk = when {
            document.minVersionCode == null && document.maxVersionCode == null -> true
            versionCode == null -> false
            else -> (document.minVersionCode == null || versionCode >= document.minVersionCode) &&
                (document.maxVersionCode == null || versionCode <= document.maxVersionCode)
        }
        val compatible = packageOk && versionNameOk && versionCodeOk
        val message = when {
            !packageOk -> "Патч для пакета ${document.packagePattern}, а выбран ${packageName ?: "неизвестный"}"
            !versionNameOk -> "Патч для версии ${document.versionNamePattern}, а выбран ${versionName ?: "неизвестная"}"
            !versionCodeOk -> "Патч для versionCode ${versionRange(document)}, а выбран ${versionCode ?: "неизвестный"}"
            else -> "Патч совместим с выбранным APK"
        }
        return Result(compatible, packageOk, versionNameOk, versionCodeOk, message)
    }

    private fun patterns(value: String): List<String> =
        value.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && it != "*" }

    private fun versionRange(document: PatchDocument): String {
        val min = document.minVersionCode
        val max = document.maxVersionCode
        return when {
            min != null && max != null -> "$min..$max"
            min != null -> ">= $min"
            max != null -> "<= $max"
            else -> "*"
        }
    }
}
