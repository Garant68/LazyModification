package com.example.lazymodification.utils

import com.reandroid.apk.ApkModule
import java.io.File
import java.util.zip.ZipFile

object AxmlComparer {

    const val MANIFEST_NAME = "AndroidManifest.xml"

    fun compare(apkA: File, apkB: File): List<DexComparer.ChangedClass> {
        val filesA = decodeAll(apkA)
        val filesB = decodeAll(apkB)
        val changed = mutableListOf<DexComparer.ChangedClass>()
        for (name in (filesA.keys + filesB.keys).toSortedSet()) {
            val ta = filesA[name]
            val tb = filesB[name]
            val kind = when {
                ta == null -> DexComparer.ChangeKind.ADDED
                tb == null -> DexComparer.ChangeKind.REMOVED
                ta != tb -> DexComparer.ChangeKind.CHANGED
                else -> null
            }
            if (kind != null) changed.add(DexComparer.ChangedClass(name, kind))
        }
        return changed
    }

    fun diffFile(apkA: File, apkB: File, fileName: String): DexComparer.DiffResult {
        val ta = decode(apkA, fileName)
        val tb = decode(apkB, fileName)
        return DexComparer.diffTexts(ta, tb)
    }

    private fun decodeAll(apk: File): Map<String, List<String>> {
        val map = mutableMapOf<String, List<String>>()
        val apkModule = ApkModule.loadApkFile(apk)
        try {
            map[MANIFEST_NAME] = manifestText(apkModule).lines()
            for (name in listXmlFiles(apk)) {
                val text = decodeResXml(apkModule, name)
                if (text != null) map[name] = text.lines()
            }
        } finally {
            apkModule.close()
        }
        return map
    }

    private fun decode(apk: File, fileName: String): List<String> {
        val apkModule = ApkModule.loadApkFile(apk)
        try {
            val text = if (fileName == MANIFEST_NAME) {
                manifestText(apkModule)
            } else {
                decodeResXml(apkModule, fileName) ?: ""
            }
            return text.lines()
        } finally {
            apkModule.close()
        }
    }

    private fun manifestText(apkModule: ApkModule): String {
        return try {
            val manifest = apkModule.androidManifest ?: return ""
            val packageBlock = apkModule.tableBlock?.pickOne()
            if (packageBlock != null) manifest.setPackageBlock(packageBlock)
            manifest.serializeToXml()
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeResXml(apkModule: ApkModule, name: String): String? {
        return try {
            apkModule.loadResXmlDocument(name).serializeToXml()
        } catch (_: Exception) {
            null
        }
    }

    private fun listXmlFiles(apk: File): List<String> {
        val files = mutableListOf<String>()
        try {
            ZipFile(apk).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    val name = e.name
                    if (!e.isDirectory && name.startsWith("res/") && name.endsWith(".xml")) {
                        files.add(name)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return files.sorted()
    }
}
