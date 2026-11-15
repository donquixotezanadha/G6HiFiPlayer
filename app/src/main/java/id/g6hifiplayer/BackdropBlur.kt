package id.g6hifiplayer

/**
 * Hifi 2.67b: pure Classic. The blur (soft cover, veils, saturation) is gone; this object only keeps the Classic base colours and the two
 * helpers that draw the colourless cover with its fade (page backdrop, Blur widgets).
 */
internal object BackdropBlur {
    /** Hifi 2.61: Classic UI base colours = the original theme colours (Light 0xFFF8F8FC, Dark black). */
    const val CLASSIC_LIGHT = 0xFFF8F8FC.toInt()
    const val CLASSIC_DARK = 0xFF141416.toInt()   // Hifi 2.67c: Dark base = soft charcoal instead of pure black

        /** Hifi 2.60: Classic tone: grey value of every pixel mapped into the band [lo, hi] (0..1), so a cover loses its colour but stays visible. */
    fun toneFilter(lo: Float, hi: Float): android.graphics.ColorMatrixColorFilter {
        val a = hi - lo
        val o = lo * 255f
        return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
            a * 0.299f, a * 0.587f, a * 0.114f, 0f, o,
            a * 0.299f, a * 0.587f, a * 0.114f, 0f, o,
            a * 0.299f, a * 0.587f, a * 0.114f, 0f, o,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    /** Hifi 2.61: alpha mask for the bottom fade of the Now Playing cover (smootherstep over the lower [fade] share), as NowPlayingCoverView draws it. */
    fun fadeShader(top: Float, bottom: Float, fade: Float): android.graphics.LinearGradient {
        val start = 1f - fade
        val n = 16
        val colors = IntArray(n + 2); val pos = FloatArray(n + 2)
        colors[0] = -0x1000000; pos[0] = 0f
        for (i in 0..n) {
            val tt = i / n.toFloat()
            val sm = tt * tt * tt * (tt * (tt * 6f - 15f) + 10f)
            colors[i + 1] = (Math.round(255f * (1f - sm)) shl 24)
            pos[i + 1] = start + (1f - start) * tt
        }
        return android.graphics.LinearGradient(0f, top, 0f, bottom, colors, pos, android.graphics.Shader.TileMode.CLAMP)
    }
}
