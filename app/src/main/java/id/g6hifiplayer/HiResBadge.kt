package id.g6hifiplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Build 2.30: small Hi-Res Audio logo (picture, not text) in the bottom-right corner of a cover, like UAPP.
 * Only used for 24-bit (hi-res) tracks; every other cover stays untouched.
 * Drawn as a view foreground, or onto a bitmap for the notification / widgets.
 * [circle] = the cover is round (2x2 widget): the logo sits further in, so it is not cut off by the circle.
 */
class HiResBadgeDrawable(context: Context, private val minWidthPx: Float = 0f, private val circle: Boolean = false) : Drawable() {
    private val logo: Bitmap? = loadLogo(context.applicationContext)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val img = logo ?: return
        val b = bounds
        val w = b.width().toFloat()
        if (w <= 0f || b.height() <= 0) return
        // Build 2.31: reduce the logo to 60% of its previous size (12% vs 20% of the cover width).
        val side = maxOf(w * 0.12f, minWidthPx).coerceAtMost(w * 0.4f)
        if (circle) {
            // Bottom-right corner of the logo at 0.66 of the radius from the centre on both axes (inside the circle).
            val r = minOf(w, b.height().toFloat()) / 2f
            val right = b.exactCenterX() + r * 0.66f
            val bottom = b.exactCenterY() + r * 0.66f
            box.set(right - side, bottom - side, right, bottom)
        } else {
            val margin = maxOf(w * 0.04f, side * 0.12f)
            box.set(b.right - margin - side, b.bottom - margin - side, b.right - margin, b.bottom - margin)
        }
        src.set(0, 0, img.width, img.height)
        canvas.drawBitmap(img, src, box, paint)
    }

    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        @Volatile private var cached: Bitmap? = null

        private fun loadLogo(context: Context): Bitmap? {
            cached?.let { if (!it.isRecycled) return it }
            return try {
                BitmapFactory.decodeResource(context.resources, R.drawable.hires_logo)?.also { cached = it }
            } catch (_: Throwable) { null }
        }
    }
}
