package com.example.lazymodification.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.example.lazymodification.R
import java.io.File

/**
 * Корни доступных хранилищ: внутренняя память и SD-карта.
 * При наличии MANAGE_EXTERNAL_STORAGE пути доступны как обычные java.io.File.
 */
object StorageRoots {
    data class Root(val dir: File, val label: String, val removable: Boolean)

    fun getRoots(context: Context): List<Root> {
        val roots = LinkedHashMap<String, Root>()

        fun add(dir: File?, label: String, removable: Boolean) {
            if (dir == null || !dir.isDirectory || !dir.canRead()) return
            val key = canonical(dir)
            if (!roots.containsKey(key)) {
                roots[key] = Root(dir, label, removable)
            }
        }

        val primary = Environment.getExternalStorageDirectory()
        add(primary, context.getString(R.string.storage_internal), false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            manager?.storageVolumes?.forEach { volume ->
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory
                } else {
                    volume.uuid?.let { File("/storage/$it") }
                }
                if (dir != null && !samePath(dir, primary)) {
                    val label = if (volume.isRemovable) {
                        val name = volume.uuid ?: volume.getDescription(context)
                        context.getString(R.string.storage_sd_named, name)
                    } else {
                        context.getString(R.string.storage_internal)
                    }
                    add(dir, label, volume.isRemovable)
                }
            }
        }

        // Fallback для старых Android и нестандартных прошивок.
        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            val path = appDir.absolutePath
            val marker = path.indexOf("/Android/")
            if (marker > 0) {
                val root = File(path.substring(0, marker))
                val removable = !samePath(root, primary)
                val label = if (removable) {
                    context.getString(R.string.storage_sd_named, root.name)
                } else {
                    context.getString(R.string.storage_internal)
                }
                add(root, label, removable)
            }
        }

        File("/storage").listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != "emulated" && dir.name != "self") {
                add(dir, context.getString(R.string.storage_sd_named, dir.name), true)
            }
        }

        return roots.values.toList()
    }

    fun isRoot(context: Context, dir: File): Boolean {
        return getRoots(context).any { samePath(it.dir, dir) }
    }

    private fun canonical(file: File): String {
        return try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
    }

    private fun samePath(first: File, second: File): Boolean {
        return canonical(first) == canonical(second)
    }
}
