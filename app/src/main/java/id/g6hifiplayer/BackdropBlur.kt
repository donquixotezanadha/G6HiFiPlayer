package id.g6hifiplayer

/**
 * Hifi 2.67b: pure Classic. The blur (soft cover, veils, saturation) is gone; this object only keeps the Classic base colours and the two
 * helpers that draw the colourless cover with its fade (page backdrop, Blur widgets).
 */
internal object BackdropBlur {
    /** Hifi 2.61: Classic UI base colours = the original theme colours (Light 0xFFF8F8FC, Dark 0F1114); since 2.73 classic 14 their thin cold-blue
     *  touch is replaced by the same thin touch in the colour of the cover (see [setTintFromCoverDark]); lightness (luminance) stays exactly the same. */
    const val CLASSIC_LIGHT_BASE = 0xFFF8F8FC.toInt()
    const val CLASSIC_DARK_BASE = 0xFF0F1114.toInt()
    const val CLASSIC_WINDOW_DARK_BASE = 0xFF1E2128.toInt()
    const val CLASSIC_CARD_LIGHT_BASE = 0xFFDADADD.toInt()

    @Volatile private var tintHue = 0f        // hue (0..360) of the cover colour
    @Volatile private var tintK = 0f          // 0 = grey cover (no tint), 1 = colourful cover (full thin tint)

    val CLASSIC_LIGHT: Int get() = tinted(CLASSIC_LIGHT_BASE)
    val CLASSIC_DARK: Int get() = tinted(CLASSIC_DARK_BASE)
    fun windowDark(): Int = tinted(CLASSIC_WINDOW_DARK_BASE)
    fun cardLight(): Int = tinted(CLASSIC_CARD_LIGHT_BASE)

    /** Takes hue and strength from AlbumAccent.Result.dark (the darkened cover colour; neutral grey for a grey cover). True when the tint changed. */
    fun setTintFromCoverDark(dark: Int): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(dark, hsv)
        val k = (hsv[1] / 0.45f).coerceIn(0f, 1f)
        val h = if (k <= 0.001f) 0f else hsv[0]
        val changed = Math.abs(h - tintHue) > 0.5f || Math.abs(k - tintK) > 0.01f
        tintHue = h; tintK = k
        return changed
    }
    fun tintState(): Pair<Float, Float> = Pair(tintHue, tintK)
    fun restoreTint(hue: Float, k: Float) { tintHue = hue; tintK = k }

    private fun lum(c: Int) = 0.299f * android.graphics.Color.red(c) + 0.587f * android.graphics.Color.green(c) + 0.114f * android.graphics.Color.blue(c)

    /** Same lightness (luminance) and the same thinness (saturation) as [base], but the hue of the cover; a grey cover gives a plain grey. */
    private fun tinted(base: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        hsv[0] = tintHue
        hsv[1] = hsv[1] * tintK
        val c = android.graphics.Color.HSVToColor(hsv)
        val y1 = lum(c)
        val f = if (y1 > 0.5f) lum(base) / y1 else 1f
        fun ch(v: Int) = Math.round(v * f).coerceIn(0, 255)
        return android.graphics.Color.rgb(ch(android.graphics.Color.red(c)), ch(android.graphics.Color.green(c)), ch(android.graphics.Color.blue(c)))
    }

    /** Hifi 2.60: Classic tone band. 2.73 classic 14: the cover band takes the tint of the background it lies on (like an Overlay blend), so it
     *  follows the cover-tinted background: channel gains = background channel / strongest channel. */
    fun classicTone(light: Boolean, lightLo: Float, lightHi: Float, darkLo: Float, darkHi: Float): android.graphics.ColorMatrixColorFilter {
        val base = if (light) CLASSIC_LIGHT else CLASSIC_DARK
        val br = android.graphics.Color.red(base).toFloat(); val bg = android.graphics.Color.green(base).toFloat(); val bb = android.graphics.Color.blue(base).toFloat()
        val mx = maxOf(br, bg, bb).coerceAtLeast(1f)
        val gr = br / mx; val gg = bg / mx; val gb = bb / mx
        if (light) {
            val a = lightHi - lightLo; val o = lightLo * 255f
            return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
                gr * a * 0.299f, gr * a * 0.587f, gr * a * 0.114f, 0f, gr * o,
                gg * a * 0.299f, gg * a * 0.587f, gg * a * 0.114f, 0f, gg * o,
                gb * a * 0.299f, gb * a * 0.587f, gb * a * 0.114f, 0f, gb * o,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        val span = darkHi - darkLo
        val ar = span * gr; val ag = span * gg; val ab = span * gb
        return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
            ar * 0.299f, ar * 0.587f, ar * 0.114f, 0f, br,
            ag * 0.299f, ag * 0.587f, ag * 0.114f, 0f, bg,
            ab * 0.299f, ab * 0.587f, ab * 0.114f, 0f, bb,
            0f, 0f, 0f, 1f, 0f
        )))
    }

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
