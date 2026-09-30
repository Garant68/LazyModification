package com.example.lazymodification.utils

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Файловый кэш переводов: одну и ту же фразу дважды не переводим. */
class TranslationCache(context: Context) {

    private val file = File(context.cacheDir, "translation_cache.json")
    private val map = ConcurrentHashMap<String, String>()
    private var dirty = false

    init {
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = json.getString(k)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun key(provider: String, src: String, dst: String, text: String): String =
        "$provider|$src|$dst|$text"

    fun get(k: String): String? = map[k]

    fun put(k: String, v: String) {
        map[k] = v
        dirty = true
    }

    fun save() {
        if (!dirty) return
        try {
            val json = JSONObject()
            for ((k, v) in map) json.put(k, v)
            file.writeText(json.toString(), Charsets.UTF_8)
            dirty = false
        } catch (_: Exception) {
        }
    }
}
