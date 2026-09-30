package com.example.lazymodification.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Онлайн-перевод строк приложения.
 *
 * Провайдеры: Google (без ключа, неофиц. эндпоинт), MyMemory (без ключа),
 * Azure Translator (ключ + регион), DeepL Free (ключ).
 *
 * ВАЖНО: плейсхолдеры (%1$s, %d и т.п.) защищаются маркерами до перевода
 * и восстанавливаются после — переводчик их не портит.
 */
object AppTranslator {
    const val GOOGLE = "google"
    const val MYMEMORY = "mymemory"
    const val AZURE = "azure"
    const val DEEPL = "deepl"

    private val PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]")

    class TranslationException(message: String) : Exception(message)

    /** Переводит одну строку. Кэш — снаружи (TranslationCache). */
    fun translateOnce(
        provider: String,
        key: String?,
        region: String?,
        src: String,
        dst: String,
        text: String
    ): String {
        val (protectedText, tokens) = protect(text)
        val raw = when (provider) {
            GOOGLE -> google(protectedText, src, dst)
            MYMEMORY -> mymemory(protectedText, if (src == "auto") "en" else src, dst)
            AZURE -> azure(protectedText, if (src == "auto") "en" else src, dst, key, region)
            DEEPL -> deepl(protectedText, if (src == "auto") "" else src, dst, key)
            else -> throw TranslationException("unknown provider: $provider")
        }
        return restore(raw.trim(), tokens)
    }

    private fun protect(text: String): Pair<String, List<String>> {
        val tokens = mutableListOf<String>()
        val m = PLACEHOLDER.matcher(text)
        val sb = StringBuffer()
        while (m.find()) {
            tokens.add(m.group())
            m.appendReplacement(sb, "\uE000${tokens.size - 1}\uE001")
        }
        m.appendTail(sb)
        return sb.toString() to tokens
    }

    private fun restore(text: String, tokens: List<String>): String {
        var out = text
        for (i in tokens.indices) {
            out = out.replace("\uE000$i\uE001", tokens[i])
            out = out.replace("\uE000 $i\uE001", tokens[i])
            out = out.replace("\uE000$i \uE001", tokens[i])
            out = out.replace("\uE000 $i \uE001", tokens[i])
        }
        return out
    }

    private fun google(text: String, src: String, dst: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val body = httpGet("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$src&tl=$dst&dt=t&q=$q")
        val root = JSONArray(body)
        val parts = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val seg = parts.getJSONArray(i)
            if (!seg.isNull(0)) sb.append(seg.getString(0))
        }
        if (sb.isEmpty()) throw TranslationException("empty google result")
        return sb.toString()
    }

    private fun mymemory(text: String, src: String, dst: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val root = JSONObject(httpGet("https://api.mymemory.translated.net/get?q=$q&langpair=$src|$dst"))
        val data = root.optJSONObject("responseData") ?: throw TranslationException("no responseData")
        val t = data.optString("translatedText", "")
        if (t.isEmpty()) throw TranslationException(root.optString("responseDetails", "empty"))
        return t
    }

    private fun azure(text: String, src: String, dst: String, key: String?, region: String?): String {
        if (key.isNullOrEmpty()) throw TranslationException("Azure key required")
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Ocp-Apim-Subscription-Key" to key
        )
        if (!region.isNullOrEmpty()) headers["Ocp-Apim-Subscription-Region"] = region
        val body = "[{\"Text\": ${JSONObject.quote(text)}}]"
        val resp = httpPost(
            "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=$src&to=$dst",
            headers, body
        )
        return JSONArray(resp).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
    }

    private fun deepl(text: String, src: String, dst: String, key: String?): String {
        if (key.isNullOrEmpty()) throw TranslationException("DeepL key required")
        val form = StringBuilder("text=").append(URLEncoder.encode(text, "UTF-8"))
            .append("&target_lang=").append(dst.uppercase())
        if (src.isNotEmpty()) form.append("&source_lang=").append(src.uppercase())
        val resp = httpPost(
            "https://api-free.deepl.com/v2/translate",
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Authorization" to "DeepL-Auth-Key $key"
            ),
            form.toString()
        )
        return JSONObject(resp).getJSONArray("translations").getJSONObject(0).getString("text")
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LazyModification")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw TranslationException("HTTP $code: ${body.take(140)}")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, headers: Map<String, String>, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw TranslationException("HTTP $code: ${respBody.take(140)}")
            return respBody
        } finally {
            conn.disconnect()
        }
    }
}
