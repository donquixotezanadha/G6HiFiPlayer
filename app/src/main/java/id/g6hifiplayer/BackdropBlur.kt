package id.g6hifiplayer

import android.graphics.Bitmap

/**
 * Hifi 1.75: the soft cover used as page backdrop in the app and as background of the Blur 4x1 widget. One place for the blur strength:
 * raise [RADIUS] for more blur (1.75: 3 -> 4, one level more; 2 passes as before).
 */
internal object BackdropBlur {
    const val RADIUS = 4
    const val PASSES = 2
    private const val SIDE = 96
    /** Hifi 2.56: colour strength of the blurred cover (1.0 = as the cover, brightness kept). Grey stays grey. */
    const val SATURATION = 1.3f

    /**
     * Hifi 2.8: strength (alpha) of the white veil in Light. 70% (0xB3) as before, but over a mostly black cover the veil grows up to 85%
     * (0xD9) so the page stays light; covers that are not dominated by black keep exactly 70%.
     */
    fun lightVeilAlpha(soft: Bitmap?): Int {
        if (soft == null || soft.isRecycled) return 0xB3
        var sum = 0.0
        var n = 0
        val step = 4
        var y = 0
        while (y < soft.height) {
            var x = 0
            while (x < soft.width) {
                val p = soft.getPixel(x, y)
                sum += (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)) / 255.0
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return 0xB3
        val lum = sum / n
        val f = ((0.40 - lum) / 0.40).coerceIn(0.0, 1.0)
        return (0xB3 + f * (0xD9 - 0xB3)).toInt()
    }


    /**
     * Hifi 2.56: strength of the black veil in Dark. 70% (0xB3) as before for bright covers (white text stays readable), but down to 55% (0x8C)
     * over dark covers so their colour shows more. Linear in the mean brightness of the blurred cover: 55% up to 25%, 70% from 50%.
     */
    fun darkVeilAlpha(soft: Bitmap?): Int {
        if (soft == null || soft.isRecycled) return 0xB3
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < soft.height) {
            var x = 0
            while (x < soft.width) {
                val p = soft.getPixel(x, y)
                sum += (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)) / 255.0
                n++
                x += 4
            }
            y += 4
        }
        if (n == 0) return 0xB3
        val f = ((sum / n - 0.25) / 0.25).coerceIn(0.0, 1.0)
        return (0x8C + f * (0xB3 - 0x8C)).toInt()
    }

    /**
     * Hifi 2.50: the veil over the blurred cover, one function for the player and the widgets. Neutral theme veil (black 70% in Dark, white in
     * Light, see [lightVeilAlpha]) that takes on the colour of the cover only when the cover is really colourful: grey, black/white or almost
     * grey covers with a small coloured detail stay exactly neutral (the v2.22 fix), colourful covers get a dark (Dark) or pastel (Light) tint
     * of their own colour, growing smoothly with the mean colourfulness of the blurred cover.
     */
    fun veilColor(soft: Bitmap?, light: Boolean): Int {
        val neutralAlpha = if (light) lightVeilAlpha(soft) else darkVeilAlpha(soft)
        val neutral = if (light) 0xFFFFFF else 0x000000
        if (soft == null || soft.isRecycled) return (neutralAlpha shl 24) or neutral
        var chromaSum = 0.0
        var wr = 0.0; var wg = 0.0; var wb = 0.0
        var n = 0
        val step = 3
        var y = 0
        while (y < soft.height) {
            var x = 0
            while (x < soft.width) {
                val p = soft.getPixel(x, y)
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val c = (maxOf(r, g, b) - minOf(r, g, b)) / 255.0
                chromaSum += c
                wr += r * c; wg += g * c; wb += b * c
                n++
                x += step
            }
            y += step
        }
        if (n == 0 || chromaSum <= 0.0) return (neutralAlpha shl 24) or neutral
        val meanChroma = chromaSum / n
        val t = ((meanChroma - 0.08) / (0.30 - 0.08)).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }   // smoothstep: 0 below 8%, full from 30%
        if (t <= 0.0) return (neutralAlpha shl 24) or neutral
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((wr / chromaSum).toInt().coerceIn(0, 255), (wg / chromaSum).toInt().coerceIn(0, 255), (wb / chromaSum).toInt().coerceIn(0, 255), hsv)
        val tint = if (light) android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0.34f, 1f))
                   else android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 0.95f), 0.34f))
        val k = (0.85 * t).toFloat()
        val rr = (((neutral shr 16) and 0xFF) * (1 - k) + android.graphics.Color.red(tint) * k).toInt()
        val gg = (((neutral shr 8) and 0xFF) * (1 - k) + android.graphics.Color.green(tint) * k).toInt()
        val bb = ((neutral and 0xFF) * (1 - k) + android.graphics.Color.blue(tint) * k).toInt()
        return (neutralAlpha shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    fun soft(source: Bitmap): Bitmap? = try {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) null else {
            val ratio = SIDE.toFloat() / maxOf(source.width, source.height)
            val w = (source.width * ratio).toInt().coerceAtLeast(2)
            val h = (source.height * ratio).toInt().coerceAtLeast(2)
            val scaled = Bitmap.createScaledBitmap(source, w, h, true)
            val small = scaled.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            repeat(PASSES) { boxBlur(pixels, w, h, RADIUS) }
            saturate(pixels, SATURATION)
            small.setPixels(pixels, 0, w, 0, 0, w, h)
            small
        }
    } catch (_: Throwable) { null }

    private fun saturate(px: IntArray, k: Float) {
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val rr = (l + (r - l) * k).toInt().coerceIn(0, 255)
            val gg = (l + (g - l) * k).toInt().coerceIn(0, 255)
            val bb = (l + (b - l) * k).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val p = px[row + i.coerceIn(0, w - 1)]
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                val add = px[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = px[row + (x - r).coerceAtLeast(0)]
                rs += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gs += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                bs += (add and 0xFF) - (sub and 0xFF)
            }
        }
        for (x in 0 until w) {
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                val add = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = tmp[(y - r).coerceAtLeast(0) * w + x]
                rs += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gs += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                bs += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
