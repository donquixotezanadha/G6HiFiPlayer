package id.g6hifiplayer

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Hifi 2.1: the accent of the player (played part of the progress bar, playing marks, play button of the 4x1 widget, ripples) is no longer
 * the fixed purple. It is 70% white + 30% of the most striking colour of the cover; a small colourful detail on an otherwise black cover
 * is enough. A cover without any colour mixes in its average grey instead (a dark cover gives a light grey). Without a cover the accent is white.
 *
 * [Result.dark] is a darkened version of the same colour; the floating windows, the mini player and the dark widget backgrounds use it.
 * Covers without colour give the neutral dark grey those backgrounds had before.
 */
internal object AlbumAccent {
    const val DEFAULT_ACCENT = 0xFFFFFFFF.toInt()
    const val DEFAULT_DARK = 0xFF1E1E1E.toInt()

    // Hifi 2.6: the Light look is the mirror image: accent = 70% black + 30% cover colour, windows / veil = a pastel of the cover colour.
    const val DEFAULT_LIGHT_ACCENT = 0xFF111114.toInt()
    const val DEFAULT_LIGHT_TINT = 0xFFF8F8FC.toInt()

    data class Result(
        val accent: Int,
        val dark: Int,
        val lightAccent: Int = DEFAULT_LIGHT_ACCENT,
        val lightTint: Int = DEFAULT_LIGHT_TINT,
        /** Hifi 2.73 classic round 15: colour that only carries the BACKGROUND tint (hue = dominant colour by area, saturation = 0.45 x strength; grey = no tint). */
        val tint: Int = DEFAULT_DARK
    )

    val NEUTRAL = Result(DEFAULT_ACCENT, DEFAULT_DARK)

    private const val COLOUR_SHARE = 0.30f        // 70% white, 30% cover colour
    private const val GRID = 64                   // 64 x 64 samples of the cover
    private const val HUE_BINS = 36               // 10 degrees each; a colour is counted together with its two neighbour bins
    private const val MIN_SATURATION = 0.28f      // below this a pixel counts as grey
    private const val MIN_VALUE = 0.28f           // below this a pixel counts as black
    private const val MIN_AREA = 0.003f           // a colour needs at least 0.3% of the samples (a small detail is enough)
    private const val FULL_AREA = 0.12f           // from 12% of the samples a colour has full weight (2.2: was 4%, a big colour area now beats small vivid details)
    private const val LIFT_SATURATION = 0.45f     // the colour that is mixed in is lifted to at least this saturation ...
    private const val LIFT_VALUE = 0.85f          // ... and this brightness, so a dark or dull colour still shows in the white
    private const val DARK_VALUE = 0.20f          // brightness of the dark window colour

    fun from(bitmap: Bitmap?): Result {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return NEUTRAL
        val w = bitmap.width
        val h = bitmap.height
        val count = IntArray(HUE_BINS)
        val weight = FloatArray(HUE_BINS)
        val rs = FloatArray(HUE_BINS)
        val gs = FloatArray(HUE_BINS)
        val bs = FloatArray(HUE_BINS)
        var total = 0
        var avgR = 0L
        var avgG = 0L
        var avgB = 0L
        val hsv = FloatArray(3)
        try {
            for (j in 0 until GRID) {
                val y = ((j + 0.5f) * h / GRID).toInt().coerceIn(0, h - 1)
                for (i in 0 until GRID) {
                    val x = ((i + 0.5f) * w / GRID).toInt().coerceIn(0, w - 1)
                    val p = bitmap.getPixel(x, y)
                    if (Color.alpha(p) < 128) continue
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    total++
                    avgR += r; avgG += g; avgB += b
                    Color.colorToHSV(p, hsv)
                    if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) continue
                    val bin = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                    val wgt = hsv[1] * hsv[2]
                    count[bin]++
                    weight[bin] += wgt
                    rs[bin] += r * wgt
                    gs[bin] += g * wgt
                    bs[bin] += b * wgt
                }
            }
        } catch (_: Throwable) {
            return NEUTRAL
        }
        if (total == 0) return NEUTRAL

        // The most striking colour: the best average vividness (saturation x brightness), a little help from its area.
        val minCount = maxOf(6, (total * MIN_AREA).toInt())
        var best = -1
        var bestScore = 0f
        for (b in 0 until HUE_BINS) {
            val lo = (b + HUE_BINS - 1) % HUE_BINS
            val hi = (b + 1) % HUE_BINS
            val c = count[lo] + count[b] + count[hi]
            if (c < minCount) continue
            val vivid = (weight[lo] + weight[b] + weight[hi]) / c
            val area = 0.40f + 0.60f * minOf(1f, c / (total * FULL_AREA))
            val score = vivid * area
            if (score > bestScore) { bestScore = score; best = b }
        }

        if (best < 0) {
            // No colour at all: black and white or grey cover. Mix in the average grey of the cover.
            val avg = Color.rgb((avgR / total).toInt(), (avgG / total).toInt(), (avgB / total).toInt())
            return Result(mix(Color.WHITE, avg, COLOUR_SHARE), DEFAULT_DARK, mix(Color.BLACK, avg, COLOUR_SHARE) or 0xFF000000.toInt(), DEFAULT_LIGHT_TINT, tintOf(bitmap))
        }

        val lo = (best + HUE_BINS - 1) % HUE_BINS
        val hi = (best + 1) % HUE_BINS
        val wsum = weight[lo] + weight[best] + weight[hi]
        val raw = Color.rgb(
            ((rs[lo] + rs[best] + rs[hi]) / wsum).toInt().coerceIn(0, 255),
            ((gs[lo] + gs[best] + gs[hi]) / wsum).toInt().coerceIn(0, 255),
            ((bs[lo] + bs[best] + bs[hi]) / wsum).toInt().coerceIn(0, 255)
        )
        val rawHsv = FloatArray(3)
        Color.colorToHSV(raw, rawHsv)
        val lifted = Color.HSVToColor(floatArrayOf(rawHsv[0], maxOf(rawHsv[1], LIFT_SATURATION).coerceAtMost(1f), maxOf(rawHsv[2], LIFT_VALUE).coerceAtMost(1f)))
        val accent = mix(Color.WHITE, lifted, COLOUR_SHARE)
        val dark = Color.HSVToColor(floatArrayOf(rawHsv[0], rawHsv[1].coerceIn(0.45f, 0.70f), DARK_VALUE))
        // Light: a strong, bright version of the colour mixed 30% into black; a pastel of the hue for windows and the veil.
        val strong = Color.HSVToColor(floatArrayOf(rawHsv[0], maxOf(rawHsv[1], 0.75f).coerceAtMost(1f), 1f))
        val lightAccent = mix(Color.BLACK, strong, COLOUR_SHARE)
        val lightTint = Color.HSVToColor(floatArrayOf(rawHsv[0], (rawHsv[1] * 0.4f).coerceIn(0.16f, 0.30f), 0.96f))
        return Result(accent or 0xFF000000.toInt(), dark or 0xFF000000.toInt(), lightAccent or 0xFF000000.toInt(), lightTint or 0xFF000000.toInt(), tintOf(bitmap))
    }

    /**
     * Hifi 2.73 classic round 15: hue of the background tint. Unlike the accent (most striking colour, even a tiny detail) this follows the DOMINANT
     * colour by area: grey, cream / beige (saturation < 0.28) and black count as neutral, a coloured hue wins only with enough area, and the strength
     * fades in between 10% and 35% of the non-black samples, so a grey / cream cover with a small other colour stays grey. Returns a colour
     * (hue, saturation 0.45 x strength, value 0.20) that [BackdropBlur.setTintFromCoverDark] reads back; grey = no tint.
     */
    fun tintOf(bitmap: Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return DEFAULT_DARK
        val bins = 36
        val area = FloatArray(bins); val cx = FloatArray(bins); val cy = FloatArray(bins)
        var seen = 0; var coloured = 0
        val hsv = FloatArray(3)
        try {
            for (j in 0 until GRID) {
                val y = ((j + 0.5f) * bitmap.height / GRID).toInt().coerceIn(0, bitmap.height - 1)
                for (i in 0 until GRID) {
                    val x = ((i + 0.5f) * bitmap.width / GRID).toInt().coerceIn(0, bitmap.width - 1)
                    val p = bitmap.getPixel(x, y)
                    if (Color.alpha(p) < 128) continue
                    Color.colorToHSV(p, hsv)
                    if (hsv[2] < 0.12f) continue           // black does not count at all
                    seen++
                    if (hsv[1] < MIN_SATURATION || hsv[2] < 0.20f) continue   // grey / cream / beige / very dark = neutral
                    val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
                    val w = hsv[1] * hsv[2]
                    coloured++
                    area[bin] += w
                    val rad = Math.toRadians(hsv[0].toDouble())
                    cx[bin] += (Math.cos(rad) * w).toFloat(); cy[bin] += (Math.sin(rad) * w).toFloat()
                }
            }
        } catch (_: Throwable) { return DEFAULT_DARK }
        if (seen == 0 || coloured == 0) return DEFAULT_DARK
        var best = 0; var bestW = -1f
        for (b in 0 until bins) {
            val w = area[(b + bins - 1) % bins] + area[b] + area[(b + 1) % bins]
            if (w > bestW) { bestW = w; best = b }
        }
        var sx = 0f; var sy = 0f; var inWindow = 0f
        for (d in -1..1) { val b = (best + d + bins) % bins; sx += cx[b]; sy += cy[b]; inWindow += area[b] }
        if (inWindow <= 0f) return DEFAULT_DARK
        var hue = Math.toDegrees(Math.atan2(sy.toDouble(), sx.toDouble())).toFloat()
        if (hue < 0f) hue += 360f
        // strength by the share of the samples that really belong to the winning colour window (vividness-weighted share is not used: area decides)
        val share = coloured.toFloat() / seen
        val tt = ((share - 0.10f) / 0.25f).coerceIn(0f, 1f)
        val k = tt * tt * (3f - 2f * tt)
        if (k <= 0.01f) return DEFAULT_DARK
        return Color.HSVToColor(floatArrayOf(hue, 0.45f * k, 0.20f)) or 0xFF000000.toInt()
    }

    /** Hifi 2.73 classic 19: vivid end colour of the played part of the progress bar (the bar runs from the pure theme colour to this colour).
     *  Hue comes from the cover ([Result.dark] carries its hue; a grey cover has no saturation there -> null = plain black / white bar).
     *  Protection against the theme: fixed saturation and a lightness window, Dark L 0.62 (never near black or white), Light L 0.45. */
    fun vivid(dark: Int, light: Boolean): Int? {
        val hsv = FloatArray(3)
        Color.colorToHSV(dark, hsv)
        if (hsv[1] < 0.2f) return null
        return hslToColor(hsv[0], 0.88f, if (light) 0.45f else 0.62f)
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val hp = (h % 360f) / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(r1), ch(g1), ch(b1))
    }

    private fun mix(from: Int, to: Int, fraction: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * fraction).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(from), Color.red(to)), ch(Color.green(from), Color.green(to)), ch(Color.blue(from), Color.blue(to)))
    }
}
