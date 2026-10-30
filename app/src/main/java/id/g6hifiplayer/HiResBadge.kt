package id.g6hifiplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Build 2.16: small "Hi-Res" badge in the bottom-right corner of a cover (used for 24-bit tracks).
 * Drawn as a view foreground, or onto a bitmap for the notification / widgets. A plain badge of our own, not the official logo.
 * Build 2.17: [circle] = the cover is round (2x2 widget): the badge sits further in, so it is not cut off by the circle.
 */
class HiResBadgeDrawable(private val minWidthPx: Float = 0f, private val circle: Boolean = false) : Drawable() {
    private val gold = 0xFFE8C468.toInt()
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9101010.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = gold }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        if (w <= 0f || b.height() <= 0) return
        val bw = maxOf(w * 0.30f, minWidthPx).coerceAtMost(w * 0.6f)
        val bh = bw * 0.38f
        if (circle) {
            // Bottom-right corner of the badge at 0.66 of the radius from the centre on both axes (inside the circle).
            val r = minOf(w, b.height().toFloat()) / 2f
            val right = b.exactCenterX() + r * 0.66f
            val bottom = b.exactCenterY() + r * 0.66f
            box.set(right - bw, bottom - bh, right, bottom)
        } else {
            val margin = maxOf(w * 0.04f, bw * 0.12f)
            box.set(b.right - margin - bw, b.bottom - margin - bh, b.right - margin, b.bottom - margin)
        }
        val corner = bh * 0.28f
        canvas.drawRoundRect(box, corner, corner, plate)
        edge.strokeWidth = maxOf(1f, bh * 0.06f)
        canvas.drawRoundRect(box, corner, corner, edge)
        label.textSize = bh * 0.62f
        val textWidth = label.measureText("Hi-Res")
        if (textWidth > bw * 0.84f) label.textSize = label.textSize * (bw * 0.84f / textWidth)
        val baseline = box.centerY() - (label.descent() + label.ascent()) / 2f
        canvas.drawText("Hi-Res", box.centerX(), baseline, label)
    }

    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
