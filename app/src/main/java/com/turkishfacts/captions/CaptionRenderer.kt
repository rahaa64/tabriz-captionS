package com.turkishfacts.captions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class CaptionStyle(val title: String) {
    GLASS("شیشه‌ای"),
    POP("پاپ"),
    KARAOKE("کارائوکه"),
    BLOCK("بلوک رنگی"),
    TYPE("تایپی"),
}

data class CaptionLook(
    val style: CaptionStyle = CaptionStyle.GLASS,
    val size: Float = 1f,      // 0.7 .. 1.5
    val yFrac: Float = 0.74f,  // vertical centre of the caption
    val accent: Int = 0xFFFFD400.toInt(),
)

/** Draws the caption active at time t onto a transparent canvas of the output frame size. */
class CaptionRenderer(ctx: Context) {
    private val font: Typeface = try {
        Typeface.createFromAsset(ctx.assets, "fonts/Vazirmatn-Bold.ttf")
    } catch (e: Exception) { Typeface.DEFAULT_BOLD }

    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun active(caps: List<Caption>, tMs: Long): Caption? {
        // binary search would be faster, but lists are short (< 500 lines)
        for (c in caps) if (tMs >= c.startMs && tMs < c.endMs) return c
        return null
    }

    fun draw(canvas: Canvas, w: Int, h: Int, caps: List<Caption>, tMs: Long, look: CaptionLook) {
        val c = active(caps, tMs) ?: return
        drawCaption(canvas, w, h, c, tMs - c.startMs, look)
    }

    fun drawCaption(canvas: Canvas, w: Int, h: Int, c: Caption, local: Long, look: CaptionLook) {
        val text = c.text.trim(); if (text.isEmpty()) return
        val dur = max(1L, c.endMs - c.startMs)
        val textSize = min(w, h) * 0.068f * look.size
        val maxW = (w * 0.86f).toInt()
        tp.textSize = textSize
        tp.style = Paint.Style.FILL
        tp.color = Color.WHITE
        tp.alpha = 255
        val exit = ((dur - local) / 140f).coerceIn(0f, 1f)          // fade out in last 140 ms
        val cy = h * look.yFrac
        val pad = textSize * 0.42f

        when (look.style) {
            CaptionStyle.GLASS -> {
                val e = easeOut((local / 260f).coerceIn(0f, 1f))
                val lay = layout(text, maxW)
                val dy = (1 - e) * textSize * 0.7f
                val a = (e * exit)
                withLayout(canvas, lay, w, cy + dy) { left, top ->
                    rect.set(left - pad * 1.3f, top - pad, left + lay.width + pad * 1.3f, top + lay.height + pad)
                    box.color = Color.argb((150 * a).toInt(), 10, 14, 20)
                    canvas.drawRoundRect(rect, pad * 1.2f, pad * 1.2f, box)
                    box.color = Color.argb((60 * a).toInt(), 255, 255, 255)
                    box.style = Paint.Style.STROKE; box.strokeWidth = textSize * 0.04f
                    canvas.drawRoundRect(rect, pad * 1.2f, pad * 1.2f, box)
                    box.style = Paint.Style.FILL
                    tp.alpha = (255 * a).toInt(); lay.draw(canvas)
                }
            }
            CaptionStyle.POP -> {
                val s = popScale((local / 240f).coerceIn(0f, 1f))
                val lay = layout(text, maxW)
                canvas.save(); canvas.scale(s, s, w / 2f, cy)
                withLayout(canvas, lay, w, cy) { _, _ -> stroked(canvas, lay, textSize, exit) }
                canvas.restore()
            }
            CaptionStyle.KARAOKE -> {
                val plain = layout(text, maxW)
                val spanned = SpannableString(text)
                val p = (local.toFloat() / (dur * 0.92f)).coerceIn(0f, 1f)
                val words = Regex("\\S+").findAll(text).toList()
                val totalLen = words.sumOf { it.value.length }.coerceAtLeast(1)
                var acc = 0
                for (m in words) {
                    val startFrac = acc.toFloat() / totalLen
                    acc += m.value.length
                    if (p >= startFrac) spanned.setSpan(ForegroundColorSpan(look.accent), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val colored = layout(spanned, maxW)
                val e = easeOut((local / 180f).coerceIn(0f, 1f))
                canvas.save(); canvas.scale(0.9f + 0.1f * e, 0.9f + 0.1f * e, w / 2f, cy)
                withLayout(canvas, plain, w, cy) { _, _ ->
                    tp.style = Paint.Style.STROKE; tp.strokeWidth = textSize * 0.16f; tp.strokeJoin = Paint.Join.ROUND
                    tp.color = Color.BLACK; tp.alpha = (230 * exit * e).toInt(); plain.draw(canvas)
                    tp.style = Paint.Style.FILL; tp.color = Color.WHITE; tp.alpha = (255 * exit * e).toInt(); colored.draw(canvas)
                }
                canvas.restore()
            }
            CaptionStyle.BLOCK -> {
                val g = easeOut((local / 220f).coerceIn(0f, 1f))
                val ta = ((local - 120) / 200f).coerceIn(0f, 1f)
                val lay = layout(text, maxW)
                withLayout(canvas, lay, w, cy) { left, top ->
                    val fullL = left - pad; val fullR = left + lay.width + pad
                    val mid = (fullL + fullR) / 2; val half = (fullR - fullL) / 2 * g
                    rect.set(mid - half, top - pad * 0.7f, mid + half, top + lay.height + pad * 0.7f)
                    box.color = look.accentBlock(); box.alpha = (255 * exit).toInt()
                    canvas.drawRoundRect(rect, pad * 0.35f, pad * 0.35f, box)
                    tp.alpha = (255 * ta * exit).toInt(); lay.draw(canvas)
                }
            }
            CaptionStyle.TYPE -> {
                val typeMs = min(dur * 0.6f, text.length * 38f)
                val n = ((local / max(1f, typeMs)) * text.length).toInt().coerceIn(1, text.length)
                val full = layout(text, maxW)          // fixed box size: no jumping
                val part = layout(text.substring(0, n), maxW)
                withLayout(canvas, full, w, cy) { left, top ->
                    rect.set(left - pad * 1.2f, top - pad * 0.8f, left + full.width + pad * 1.2f, top + full.height + pad * 0.8f)
                    box.color = Color.argb((170 * exit).toInt(), 0, 0, 0)
                    canvas.drawRoundRect(rect, pad, pad, box)
                }
                withLayout(canvas, part, w, cy - (full.height - part.height) / 2f) { _, _ ->
                    tp.alpha = (255 * exit).toInt(); part.draw(canvas)
                }
            }
        }
    }

    private fun stroked(canvas: Canvas, lay: StaticLayout, textSize: Float, alpha: Float) {
        tp.style = Paint.Style.STROKE; tp.strokeWidth = textSize * 0.16f; tp.strokeJoin = Paint.Join.ROUND
        tp.color = Color.BLACK; tp.alpha = (235 * alpha).toInt(); lay.draw(canvas)
        tp.style = Paint.Style.FILL; tp.color = Color.WHITE; tp.alpha = (255 * alpha).toInt(); lay.draw(canvas)
    }

    private fun layout(text: CharSequence, maxW: Int): StaticLayout {
        val width = min(maxW, Layout.getDesiredWidth(text, tp).toInt() + 2).coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setLineSpacing(0f, 1.08f)
            .setIncludePad(false)
            .build()
    }

    /** Translates the canvas so the layout is horizontally centred with its middle at cy. */
    private inline fun withLayout(canvas: Canvas, lay: StaticLayout, w: Int, cy: Float, body: (Float, Float) -> Unit) {
        val left = (w - lay.width) / 2f
        val top = cy - lay.height / 2f
        canvas.save(); canvas.translate(left, top)
        body(0f, 0f)
        canvas.restore()
    }

    private fun easeOut(x: Float) = 1 - (1 - x).pow(3)
    private fun popScale(x: Float): Float = when {
        x < 0.6f -> 0.55f + 0.6f * easeOut(x / 0.6f)      // 0.55 -> 1.15
        else -> 1.15f - 0.15f * easeOut((x - 0.6f) / 0.4f) // 1.15 -> 1.0
    }
}

private fun CaptionLook.accentBlock(): Int = if (accent == 0xFFFFD400.toInt()) 0xFFE53935.toInt() else accent
