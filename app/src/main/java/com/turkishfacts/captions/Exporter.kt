package com.turkishfacts.captions

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Aspect(val title: String) { ORIGINAL("اندازه اصلی"), VERTICAL("عمودی ۹:۱۶") }

data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

object VideoProbe {
    fun info(ctx: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return VideoInfo(w, h, d)
        } finally { r.release() }
    }

    fun frame(ctx: Context, uri: Uri, atMs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) { null } finally { r.release() }
    }

    fun outputSize(info: VideoInfo, aspect: Aspect): Pair<Int, Int> {
        if (aspect == Aspect.VERTICAL) return 1080 to 1920
        val scale = minOf(1f, 1920f / maxOf(info.width, info.height))
        fun even(x: Float) = (x.toInt() / 2) * 2
        return even(info.width * scale) to even(info.height * scale)
    }
}

/** Animated captions as a GPU overlay. Two bitmaps alternate so Media3 re-uploads only when the picture changes. */
@OptIn(UnstableApi::class)
class CaptionOverlay(
    private val renderer: CaptionRenderer,
    private val caps: List<Caption>,
    private val look: CaptionLook,
    private val w: Int, private val h: Int,
) : BitmapOverlay() {
    private val bmps = arrayOf(
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
    )
    private var idx = 0
    private var lastKey: Any? = Unit

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1000
        val c = renderer.active(caps, t)
        val key: Any? = when {
            c == null -> null
            look.style == CaptionStyle.KARAOKE || look.style == CaptionStyle.TYPE -> t
            t - c.startMs < 700 || c.endMs - t < 200 -> t
            else -> c  // static middle part of the line: reuse
        }
        if (key == lastKey) return bmps[idx]
        idx = idx xor 1
        val b = bmps[idx]
        b.eraseColor(0)
        if (c != null) renderer.drawCaption(Canvas(b), w, h, c, t - c.startMs, look)
        lastKey = key
        return b
    }
}

@OptIn(UnstableApi::class)
class Exporter(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: Transformer? = null

    /** Must be called from the main thread. Returns the output file. */
    suspend fun export(uri: Uri, caps: List<Caption>, look: CaptionLook, aspect: Aspect, progress: (Int) -> Unit): File {
        val info = VideoProbe.info(ctx, uri)
        val (ow, oh) = VideoProbe.outputSize(info, aspect)
        val outDir = File(ctx.cacheDir, "out").apply { mkdirs() }
        val out = File(outDir, "tabriz_${System.currentTimeMillis()}.mp4")
        val overlay = CaptionOverlay(CaptionRenderer(ctx), caps, look, ow, oh)
        val effects = Effects(
            emptyList(),
            listOf(
                Presentation.createForWidthAndHeight(ow, oh, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)),
            ),
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
        val bitrate = if (ow * oh >= 1080 * 1920) 9_000_000 else 6_000_000
        val enc = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()

        return suspendCancellableCoroutine { cont ->
            val holder = ProgressHolder()
            lateinit var poll: Runnable
            val tr = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(enc)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        main.removeCallbacks(poll); current = null
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        main.removeCallbacks(poll); current = null
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            current = tr
            poll = Runnable {
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
                main.postDelayed(poll, 400)
            }
            tr.start(item, out.absolutePath)
            main.postDelayed(poll, 400)
            cont.invokeOnCancellation { main.post { main.removeCallbacks(poll); tr.cancel(); current = null } }
        }
    }

    fun cancel() { main.post { current?.cancel(); current = null } }

    /** Copies the result into the phone's gallery (Movies/TabrizCaptions). Returns the gallery Uri or null. */
    fun saveToGallery(file: File): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TabrizCaptions")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val res = ctx.contentResolver
            val u = res.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            res.openOutputStream(u)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            cv.clear(); cv.put(MediaStore.Video.Media.IS_PENDING, 0)
            res.update(u, cv, null, null)
            u
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
            val dst = File(dir, file.name); file.copyTo(dst, overwrite = true)
            Uri.fromFile(dst)
        }
    }
}
