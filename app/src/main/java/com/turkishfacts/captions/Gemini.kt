package com.turkishfacts.captions

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Smart correction": a language model reads all caption lines together (with the recognizer's Latin text)
 * and rewrites each line in correct Tabriz Azeri Persian-script spelling, fixing loanwords, names and
 * words that were clearly misheard. Line count and timings never change.
 */
class Gemini(private val apiKey: String, private var model: String?, private val onModel: (String) -> Unit) {

    private val base = "https://generativelanguage.googleapis.com/v1beta"

    private class HttpErr(val code: Int, val body: String) : Exception("HTTP $code")

    private fun http(method: String, url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 30_000; c.readTimeout = 5 * 60_000
            c.setRequestProperty("x-goog-api-key", apiKey.trim())
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw HttpErr(code, text)
            return text
        } finally { c.disconnect() }
    }

    /** Newest usable "flash" text model on this key. */
    private fun discoverModel(): String {
        val o = JSONObject(http("GET", "$base/models?pageSize=200", null))
        val arr = o.optJSONArray("models") ?: JSONArray()
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            val name = m.optString("name").removePrefix("models/")
            if ("generateContent" !in methods) continue
            if (!name.contains("flash")) continue
            if (listOf("image", "tts", "live", "audio", "embedding", "exp").any { name.contains(it) }) continue
            names += name
        }
        if (names.isEmpty()) throw IllegalStateException("هیچ مدل Gemini قابل استفاده‌ای برای این کلید پیدا نشد.")
        fun score(n: String): Double {
            val v = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            var s = v * 10
            if (n.contains("lite")) s -= 3
            if (n.contains("preview")) s -= 1
            if (n.endsWith("latest")) s += 0.5
            return s
        }
        return names.maxByOrNull { score(it) }!!
    }

    private fun generate(m: String, system: String, user: String): String {
        val req = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
        val o = JSONObject(http("POST", "$base/models/$m:generateContent", req.toString()))
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini پاسخی نداد.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    private fun generateAnyModel(system: String, user: String): String {
        val tried = LinkedHashSet<String>()
        val first = listOfNotNull(model, "gemini-flash-latest")
        for (m in first) {
            if (!tried.add(m)) continue
            try { val r = generate(m, system, user); remember(m); return r }
            catch (e: HttpErr) { if (e.code != 404 && e.code != 400) throw friendly(e) }
        }
        val m = try { discoverModel() } catch (e: HttpErr) { throw friendly(e) }
        try { val r = generate(m, system, user); remember(m); return r } catch (e: HttpErr) { throw friendly(e) }
    }

    private fun remember(m: String) { if (m != model) { model = m; onModel(m) } }

    private fun friendly(e: HttpErr): Exception {
        val msg = try { JSONObject(e.body).optJSONObject("error")?.optString("message") ?: e.body } catch (x: Exception) { e.body }
        return IllegalStateException(when (e.code) {
            400, 401, 403 -> if (msg.contains("API key", true)) "کلید Gemini درست نیست." else "Gemini: ${msg.take(160)}"
            429 -> "سهمیه رایگان Gemini فعلاً تمام شده؛ کمی بعد دوباره امتحان کن."
            else -> "Gemini خطا داد (${e.code}): ${msg.take(160)}"
        })
    }

    /** Returns corrected Persian-script text for each caption, same size and order. */
    fun correct(caps: List<Caption>): List<String> {
        val out = ArrayList<String>()
        for (chunk in caps.chunked(60)) {
            val input = JSONArray()
            chunk.forEachIndexed { i, c -> input.put(JSONObject().put("n", i + 1).put("latin", c.latin).put("auto", c.text)) }
            val user = "Full transcript for context:\n" + caps.joinToString(" ") { it.latin } +
                "\n\nLines to correct (${chunk.size}):\n" + input.toString()
            val res = generateAnyModel(SYSTEM, user)
            val lines = parseLines(res)
            if (lines.size != chunk.size) {
                // never break timing: keep the automatic text if the model changed the line count
                out += chunk.map { it.text }
            } else out += lines.mapIndexed { i, s -> s.trim().ifEmpty { chunk[i].text } }
        }
        return out
    }

    private fun parseLines(s: String): List<String> {
        val t = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("lines") ?: JSONArray()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    companion object {
        val SYSTEM = """
You are an expert editor of Iranian (South) Azerbaijani Turkish as spoken in Tabriz and Urmia, written in the Persian (Arabic) script used by Iranian Azerbaijanis.
You receive caption lines from an automatic speech recognizer. For each line you get "latin" (the recognizer's text in the Baku Latin alphabet, sometimes misheard) and "auto" (a rough automatic transliteration into Persian script).
Rewrite every line as a correct caption in Persian script:
- Keep it Azerbaijani Turkish as spoken in Tabriz/Urmia. Never translate into Persian and never convert it to Baku or Istanbul forms. Use spoken Tabriz forms where natural (e.g. ائلیرم، گئدیرم، اولاجاخ، اوشاخ).
- Use common Iranian Azerbaijani spelling: ائ for e, اؤ for ö, او for u/ü/o, ی for ı/i; write Persian and Arabic loanwords and all proper names in standard Persian spelling (تحصیل، متأسفانه، وعده، مسئولیت، پزشکیان، تبریز).
- Fix words that were clearly misheard, using the meaning of the whole transcript. If unsure, keep the recognizer's word. Do not add new content, do not summarize, do not drop words.
- Use Persian punctuation (، ؟ .), keep lines short as given.
- Output exactly one string per input line, same order, same count.
Return JSON only: {"lines": ["...", "..."]}
""".trimIndent()
    }
}
