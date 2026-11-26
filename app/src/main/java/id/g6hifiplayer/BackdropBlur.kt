package id.g6hifiplayer

/**
 * Hifi 2.67b: pure Classic. The blur (soft cover, veils, saturation) is gone; this object only keeps the Classic base colours and the two
 * helpers that draw the colourless cover with its fade (page backdrop, Blur widgets).
 */
internal object BackdropBlur {
    /** Hifi 2.61: Classic UI base colours = the original theme colours (Light 0xFFF8F8FC, Dark 0F1114); since 2.73 classic 14 their thin cold-blue
     *  touch is replaced by the same thin touch in the colour of the cover (see [setTintFromCoverDark]); lightness (luminance) stays exactly the same. */
    const val CLASSIC_LIGHT_BASE = 0xFFF5F5FF.toInt()
    const val CLASSIC_DARK_BASE = 0xFF0F1114.toInt()
    const val CLASSIC_WINDOW_DARK_BASE = 0xFF1E2128.toInt()
    const val CLASSIC_CARD_LIGHT_BASE = 0xFFD9D9E1.toInt()

    private const val TINT_GAIN_DARK = 3.5f    // Hifi 2.73 classic round 14: Dark keeps round 13 (x3.5)
    private const val TINT_GAIN_LIGHT = 6f     // Light bases start almost white (saturation ~4% vs 25% in Dark), so Light gets its own, higher gain

    @Volatile private var tintHue = 0f        // hue (0..360) of the cover colour
    @Volatile private var tintVivid = 0f      // Hifi 2.73 classic round 18: 0 = pastel / dull cover colour, 1 = very vivid (carried in the value channel of the tint colour)
    private const val LIGHT_VIVID_EXTRA_CUT = 0.85f  // round 21: Light only: strongly vivid colours get up to 85% less tint on top of the shared protection (round 18-20: 50%, later ramp)
    private const val LIGHT_VIVID_FROM = 0.32f       // round 21: Light starts softening at saturation x brightness 0.32 and is at full cut from 0.70 (pastel / cream stay below 0.32)
    private const val LIGHT_VIVID_SPAN = 0.38f
    /** Hifi 2.73 classic round 24: Light gain as ONE monotonic curve over the vividness (saturation x brightness): pastel / weak colours (<= 0.30) get a soft, small tint
     *  (gain 4.0, round 14-21: 6), strong colours (>= 0.70) stay very soft (0.8), and the middle falls quickly in between (0.45 -> about 2.0, 0.65 -> about 0.9).
     *  The Dark-style shared vivid cut already inside tintK is divided out first, so Light is shaped only by this curve. */
    private fun lightGain(): Float { val t = ((tintVivid - 0.30f) / 0.40f).coerceIn(0f, 1f); val e = 1f - (1f - t) * (1f - t); return 4.0f - 3.2f * e }
    /** Hifi 2.73 classic round 25: Light only: green, cyan and blue read as much stronger than warm hues at the same saturation, so their tint is softened
     *  (yellow-green 70 deg to blue-violet 260 deg: x0.55, smooth 25 deg edges); reds, oranges, yellows, pinks and violet-magenta keep the full curve. */
    private fun lightHueFactor(): Float {
        val h = tintHue
        fun ss(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        val inBand = ss((h - 45f) / 25f) * (1f - ss((h - 260f) / 25f))
        return 1f - 0.55f * inBand   // round 26: x0.45 in the green..blue band (round 25: x0.55)
    }
    private fun lightBaseK(): Float { val x = ((tintVivid - 0.45f) / 0.45f).coerceIn(0f, 1f); val keep = (1f - 0.85f * (x * x * (3f - 2f * x))).coerceAtLeast(0.15f); return (tintK / keep).coerceAtMost(1f) }
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
        val v = ((hsv[2] - 0.20f) / 0.60f).coerceIn(0f, 1f)
        val changed = Math.abs(h - tintHue) > 0.5f || Math.abs(k - tintK) > 0.01f || Math.abs(v - tintVivid) > 0.02f
        tintHue = h; tintK = k; tintVivid = v
        return changed
    }
    fun tintState(): Pair<Float, Float> = Pair(tintHue, tintK)
    fun vividState(): Float = tintVivid
    fun restoreTint(hue: Float, k: Float, vivid: Float = 0f) { tintHue = hue; tintK = k; tintVivid = vivid }

    private fun lum(c: Int) = 0.299f * android.graphics.Color.red(c) + 0.587f * android.graphics.Color.green(c) + 0.114f * android.graphics.Color.blue(c)

    /** Same lightness (luminance) and the same thinness (saturation) as [base], but the hue of the cover; a grey cover gives a plain grey. */
    private fun tinted(base: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        hsv[0] = tintHue
        hsv[1] = (hsv[1] * (if (lum(base) > 128f) lightBaseK() * lightGain() * lightHueFactor() else tintK * TINT_GAIN_DARK)).coerceAtMost(1f)   // Hifi 2.73 classic round 12: tint gain 3.5 (round 11 = x1, round 12 = x2)
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
