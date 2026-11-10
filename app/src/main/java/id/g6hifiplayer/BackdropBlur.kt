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
            small.setPixels(pixels, 0, w, 0, 0, w, h)
            small
        }
    } catch (_: Throwable) { null }

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
