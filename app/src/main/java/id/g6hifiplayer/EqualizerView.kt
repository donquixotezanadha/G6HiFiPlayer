package id.g6hifiplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/**
 * Build 2.4: the small "now playing" bars shown on the right of the song that is playing.
 * Three bars move while [playing] is true and rest at a low height when it is paused.
 */
internal class EqualizerView(context: Context, barColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor; style = Paint.Style.FILL }
    private val rect = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
            invalidate()
        }

    fun setBarColor(color: Int) { paint.color = color; invalidate() }

    private fun updateAnimation() {
        if (playing && isAttachedToWindow && visibility == VISIBLE) {
            if (animator?.isRunning == true) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            animator?.cancel()
            animator = null
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updateAnimation() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bars = 3
        val gap = w * 0.14f
        val barW = (w - gap * (bars - 1)) / bars
        val offsets = floatArrayOf(0f, 0.33f, 0.66f)
        for (i in 0 until bars) {
            val level = if (playing) 0.25f + 0.75f * abs(sin((phase + offsets[i]) * Math.PI.toFloat())) else 0.25f
            val barH = h * level
            val left = i * (barW + gap)
            rect.set(left, h - barH, left + barW, h)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}
