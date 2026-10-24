package com.turkishfacts.captions

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Online speech recognition with ElevenLabs Scribe (the user's own API key).
 * Much better than Whisper for Iranian Azeri; returns per-word timings.
 */
object Scribe {
    data class Word(val text: String, val start: Double, val end: Double)

    private const val URL_STT = "https://api.elevenlabs.io/v1/speech-to-text"

    /** 16-bit PCM mono WAV from float samples. */
    fun wav(x: FloatArray, rate: Int): ByteArray {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + x.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(x.size * 2)
        return h.array() + data.array()
    }

    fun transcribe(apiKey: String, audio: FloatArray, rate: Int): List<Word> {
        val boundary = "----tabriz" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { o ->
            fun field(name: String, value: String) {
                o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                o.write(value.toByteArray()); o.writeBytes("\r\n")
            }
            field("model_id", "scribe_v1")
            field("language_code", "aze")
            field("tag_audio_events", "false")
            field("timestamps_granularity", "word")
            o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            o.write(wav(audio, rate))
            o.writeBytes("\r\n--$boundary--\r\n")
        }
        val bytes = body.toByteArray()
        val conn = URL(URL_STT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 15 * 60_000
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("xi-api-key", apiKey.trim())
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code == 401) throw IllegalStateException("کلید API ElevenLabs درست نیست (401).")
            if (code !in 200..299) throw IllegalStateException("ElevenLabs خطا داد ($code): ${errorDetail(text)}")
            val arr = JSONObject(text).optJSONArray("words") ?: return emptyList()
            val out = ArrayList<Word>()
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                if (w.optString("type", "word") != "word") continue
                val t = w.optString("text").trim()
                if (t.isEmpty()) continue
                out += Word(t, w.optDouble("start", 0.0), w.optDouble("end", 0.0))
            }
            return out
        } finally { conn.disconnect() }
    }

    private fun errorDetail(s: String): String = try {
        val o = JSONObject(s)
        val d = o.opt("detail")
        when (d) {
            is JSONObject -> d.optString("message", d.toString())
            null -> s.take(200)
            else -> d.toString().take(200)
        }
    } catch (e: Exception) { s.take(200) }

    /** Group timed words into short caption lines (break at maxWords, sentence ends, or pauses). */
    fun toCaptions(words: List<Word>, maxWords: Int, converter: TabrizConverter): List<Caption> {
        val groups = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        for ((i, w) in words.withIndex()) {
            cur += w
            val next = words.getOrNull(i + 1)
            val pause = next != null && next.start - w.end > 0.7
            val endsSentence = w.text.last() in ".?!؟"
            val comma = w.text.last() in ",،:;"
            if (cur.size >= maxWords || pause || (endsSentence && cur.isNotEmpty()) || (comma && cur.size >= 2)) {
                groups += cur; cur = ArrayList()
            }
        }
        if (cur.isNotEmpty()) {
            if (cur.size == 1 && groups.isNotEmpty() && groups.last().size < maxWords) groups[groups.size - 1] = groups.last() + cur
            else groups += cur
        }
        val res = ArrayList<Caption>()
        for ((k, g) in groups.withIndex()) {
            val start = (g.first().start * 1000).toLong()
            var end = (g.last().end * 1000).toLong().coerceAtLeast(start + 400)
            val nextStart = groups.getOrNull(k + 1)?.first()?.start?.times(1000)?.toLong()
            if (nextStart != null && nextStart - end in 0..400) end = nextStart  // no flicker on short gaps
            if (nextStart != null && end > nextStart) end = nextStart
            val (tab, fa) = converter.convert(g.joinToString(" ") { it.text })
            res += Caption(start, end, tab, fa)
        }
        return res
    }
}
