package com.turkishfacts.captions

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes the video's audio track to 16 kHz mono float PCM (what Whisper wants). */
object AudioDecoder {
    const val RATE = 16000

    fun decode(ctx: Context, uri: Uri, progress: (Float) -> Unit = {}): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { track = i; break }
        }
        if (track < 0) { ex.release(); throw IllegalArgumentException("این ویدیو صدا ندارد") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val mono = FloatArrayBuilder(((durUs / 1e6) * srcRate).toInt().coerceAtLeast(16000))
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                        if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                        ex.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            } else if (oi >= 0) {
                val out = codec.getOutputBuffer(oi)!!
                out.position(info.offset); out.limit(info.offset + info.size)
                out.order(ByteOrder.LITTLE_ENDIAN)
                if (floatPcm) {
                    val fb = out.asFloatBuffer()
                    val frames = fb.remaining() / channels
                    for (f in 0 until frames) {
                        var s = 0f; for (c in 0 until channels) s += fb.get(); mono.add(s / channels)
                    }
                } else {
                    val sb = out.asShortBuffer()
                    val frames = sb.remaining() / channels
                    for (f in 0 until frames) {
                        var s = 0f; for (c in 0 until channels) s += sb.get(); mono.add(s / channels / 32768f)
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        return resample(mono.toArray(), srcRate, RATE)
    }

    /** Linear resampling with a simple box low-pass when downsampling. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val ratio = from.toDouble() / to
        val n = (x.size / ratio).toInt()
        val y = FloatArray(n)
        val win = if (ratio > 1.5) ratio.toInt() else 1
        for (i in 0 until n) {
            val pos = i * ratio
            val j = pos.toInt()
            if (win > 1) {
                var s = 0f; var c = 0
                for (k in j until minOf(j + win, x.size)) { s += x[k]; c++ }
                y[i] = if (c > 0) s / c else 0f
            } else {
                val a = x[j.coerceAtMost(x.size - 1)]; val b = x[(j + 1).coerceAtMost(x.size - 1)]
                y[i] = a + (b - a) * (pos - j).toFloat()
            }
        }
        return y
    }
}

class FloatArrayBuilder(cap: Int) {
    private var a = FloatArray(cap); private var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
    fun toArray(): FloatArray = a.copyOf(n)
}
