package com.turkishfacts.captions

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Speech models, downloaded once from the project's own GitHub release (free hosting). */
enum class SpeechModel(val title: String, val prefix: String, val sizes: LongArray) {
    ACCURATE("دقیق (حدود ۱ گیگابایت)", "turbo", longArrayOf(674_716_297L, 361_080_764L, 816_730L)),
    LIGHT("سبک (حدود ۳۷۵ مگابایت)", "small", longArrayOf(112_442_483L, 262_226_114L, 816_730L));

    val files get() = listOf("$prefix-encoder.int8.onnx", "$prefix-decoder.int8.onnx", "$prefix-tokens.txt")
}

class ModelManager(private val ctx: Context) {
    fun dir(m: SpeechModel) = File(ctx.filesDir, "models/${m.prefix}").apply { mkdirs() }
    fun file(m: SpeechModel, i: Int) = File(dir(m), m.files[i])

    fun isReady(m: SpeechModel) = m.files.indices.all { i ->
        val f = file(m, i); f.exists() && f.length() > 1000   // final name only appears after a complete download
    }

    fun delete(m: SpeechModel) { dir(m).deleteRecursively() }

    /** Resumable download. progress(downloadedBytes, totalBytes). Runs on the caller's (IO) thread. */
    fun download(m: SpeechModel, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val total = m.sizes.sum()
        var before = 0L
        for (i in m.files.indices) {
            val target = file(m, i)
            if (target.exists() && target.length() > 1000) {
                before += m.sizes[i]; progress(before, total); continue
            }
            val part = File(target.path + ".part")
            var tries = 0
            while (true) {
                try {
                    fetch(BuildConfig.MODEL_BASE_URL + m.files[i], part, cancelled) { got -> progress(before + got, total) }
                    break
                } catch (e: Exception) {
                    if (cancelled() || ++tries >= 5) throw e
                    Thread.sleep(2000L * tries)
                }
            }
            if (!part.renameTo(target)) throw IllegalStateException("rename failed")
            before += m.sizes[i]
        }
        progress(total, total)
    }

    private fun fetch(url: String, part: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        var u = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 8) {
                u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code == 416) { conn.disconnect(); return } // already complete
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code for $url")
            if (code == 200) have = 0L
            break
        }
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(have); raf.seek(have)
            conn.inputStream.use { inp ->
                val buf = ByteArray(1 shl 16)
                var got = have
                var last = 0L
                while (true) {
                    if (cancelled()) throw InterruptedException("cancelled")
                    val n = inp.read(buf); if (n < 0) break
                    raf.write(buf, 0, n); got += n
                    if (got - last > 1_000_000) { onBytes(got); last = got }
                }
                onBytes(got)
            }
        }
        conn.disconnect()
    }
}
