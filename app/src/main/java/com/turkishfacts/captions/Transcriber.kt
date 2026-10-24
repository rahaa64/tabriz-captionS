package com.turkishfacts.captions

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** auto = text as generated (before the user edited it); used to learn from corrections. */
data class Caption(val startMs: Long, val endMs: Long, val latin: String, val text: String, val auto: String = "")

/** Energy-based speech phrases: 0.6–7 s pieces cut at pauses. Returns sample ranges. */
object Segmenter {
    fun split(x: FloatArray, rate: Int = AudioDecoder.RATE, maxSec: Double = 7.0): List<IntRange> {
        val hop = rate / 100 // 10 ms
        val nF = x.size / hop
        if (nF == 0) return emptyList()
        val db = FloatArray(nF) { f ->
            var s = 0.0; val o = f * hop
            for (k in o until o + hop) s += x[k] * x[k]
            (10 * log10(s / hop + 1e-10)).toFloat()
        }
        val sorted = db.sortedArray()
        val floor = sorted[(nF * 0.15).toInt()]
        val peak = sorted[(nF * 0.98).toInt().coerceAtMost(nF - 1)]
        val thr = max(floor + 8f, peak - 35f)
        // smooth speech flags: fill gaps < 250 ms
        val sp = BooleanArray(nF) { db[it] > thr }
        var f = 0
        val regions = ArrayList<IntArray>()
        while (f < nF) {
            if (!sp[f]) { f++; continue }
            var e = f
            var gap = 0
            var g = f
            while (g < nF) {
                if (sp[g]) { e = g; gap = 0 } else if (++gap > 25) break
                g++
            }
            regions += intArrayOf(f, e + 1)
            f = g
        }
        val out = ArrayList<IntRange>()
        val maxF = (maxSec * 100).toInt()
        for (r in regions) {
            var a = max(0, r[0] - 15); val b = minOf(nF, r[1] + 15)
            if (b - a < 25) continue // < 0.25 s: noise
            while (b - a > maxF) {
                // cut at the quietest frame in the last 40 % of the window
                val lo = a + (maxF * 0.6).toInt(); val hi = a + maxF
                var cut = lo
                for (k in lo until hi) if (db[k] < db[cut]) cut = k
                out += (a * hop) until (cut * hop)
                a = cut
            }
            out += (a * hop) until minOf(x.size, b * hop)
        }
        return out
    }
}

class Transcriber(mm: ModelManager, model: SpeechModel, private val converter: TabrizConverter) {
    private val recognizer: OfflineRecognizer

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioDecoder.RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = mm.file(model, 0).absolutePath,
                    decoder = mm.file(model, 1).absolutePath,
                    language = "az",
                    task = "transcribe",
                ),
                tokens = mm.file(model, 2).absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
        )
        recognizer = OfflineRecognizer(config = cfg)
    }

    fun transcribe(audio: FloatArray, maxWords: Int, cancelled: () -> Boolean, progress: (Float) -> Unit): List<Caption> {
        val segs = Segmenter.split(audio)
        val out = ArrayList<Caption>()
        segs.forEachIndexed { i, r ->
            if (cancelled()) return out
            val piece = audio.copyOfRange(r.first, r.last + 1)
            val st = recognizer.createStream()
            st.acceptWaveform(piece, AudioDecoder.RATE)
            recognizer.decode(st)
            val text = cleanRepeats(recognizer.getResult(st).text.trim())
            st.release()
            if (text.isNotEmpty() && text.any { it.isLetterOrDigit() }) {
                val t0 = r.first * 1000L / AudioDecoder.RATE
                val t1 = (r.last + 1) * 1000L / AudioDecoder.RATE
                out += splitLines(text, t0, t1, maxWords)
            }
            progress((i + 1f) / segs.size)
        }
        return out
    }

    /** Break a phrase into short caption lines, timed by character length. */
    private fun splitLines(text: String, t0: Long, t1: Long, maxWords: Int): List<Caption> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (w in words) {
            cur += w
            val endsSentence = w.last() in ".?!؟,،:;"
            if (cur.size >= maxWords || (endsSentence && cur.size >= 2)) { groups += cur; cur = ArrayList() }
        }
        if (cur.isNotEmpty()) {
            if (cur.size == 1 && groups.isNotEmpty() && groups.last().size < maxWords + 1) groups[groups.size - 1] = groups.last() + cur
            else groups += cur
        }
        val weights = groups.map { g -> g.sumOf { it.length + 1 }.toDouble() }
        val total = weights.sum().coerceAtLeast(1.0)
        var t = t0.toDouble()
        val res = ArrayList<Caption>()
        groups.forEachIndexed { k, g ->
            val d = (t1 - t0) * weights[k] / total
            val lat = g.joinToString(" ")
            val (tab, fa) = converter.convert(lat)
            res += Caption(t.toLong(), (t + d).toLong(), tab, fa)
            t += d
        }
        return res
    }

    fun release() = recognizer.release()

    companion object {
        /** Whisper sometimes loops: "100, 100, 100, ..." -> keep at most 2 repeats of any 1–3 word chunk. */
        fun cleanRepeats(s: String): String {
            val w = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            for (n in 1..3) {
                var i = 0
                while (i + 2 * n <= w.size) {
                    var reps = 1
                    while (i + (reps + 1) * n <= w.size &&
                        (0 until n).all { k -> norm(w[i + k]) == norm(w[i + reps * n + k]) }) reps++
                    if (reps > 2) { repeat((reps - 2) * n) { w.removeAt(i + 2 * n) } }
                    i++
                }
            }
            return w.joinToString(" ")
        }
        private fun norm(x: String) = x.lowercase().trim(',', '.', '!', '?')

        @Suppress("unused")
        fun rms(x: FloatArray) = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size.coerceAtLeast(1))
    }
}
