package id.g6hifiplayer

import java.security.MessageDigest
import java.util.Locale

/**
 * Hifi 1.24: MD5 of the samples that are handed to the DIRECT track, compared with the MD5 of the original audio
 * (FLAC header, or the data chunk of a WAV). It never changes the audio; it only reads the bytes that were accepted by the track.
 *
 * Two digests run side by side because the file's bit depth is only known a moment after the track starts: one over the full
 * 24-bit samples, one over the top 16 bits (a 16-bit sample is padded with a zero low byte on its way to the track). The right
 * one is chosen at the end. A hash starts at the first frame of a new output, or after a flush that restarts at the very
 * beginning of a track; a seek (flush in the middle of a track) switches the check off until the next start.
 */
internal class OutputMd5(private val sampleRate: Int) {
    private val md24 = MessageDigest.getInstance("MD5")
    private val md16 = MessageDigest.getInstance("MD5")
    private val pack16 = ByteArray(CHUNK_FRAMES * 4)
    private var total = 0L
    private var armed = true      // a new output starts at the start of a stream
    private var pending = false   // after flush(): the next accepted data decides whether this is a track start or a seek

    fun onFlush() {
        armed = false; pending = true; total = 0L
        md24.reset(); md16.reset()
    }

    /** [out] holds [frames] stereo frames of 24-bit packed little-endian samples that the track accepted. */
    fun feed(out: ByteArray, frames: Int, presentationTimeUs: Long) {
        if (pending) {
            pending = false
            armed = presentationTimeUs in 0..START_WINDOW_US
            total = 0L
            md24.reset(); md16.reset()
        }
        if (!armed || frames <= 0) return
        var offset = 0
        var remaining = frames
        while (remaining > 0) {
            val exp = DirectOutputState.expected
            val room = if (exp != null && exp.rate == sampleRate) exp.totalSamples - total else Long.MAX_VALUE
            val take = if (room <= 0L) remaining else minOf(remaining.toLong(), room).toInt()
            update(out, offset, take)
            total += take; offset += take; remaining -= take
            if (exp != null && exp.rate == sampleRate && total >= exp.totalSamples) {
                finish(exp)
                total = 0L   // whatever follows in this chunk is the start of the next track
            }
        }
    }

    private fun update(out: ByteArray, frameOffset: Int, frames: Int) {
        md24.update(out, frameOffset * 6, frames * 6)
        var i = 0
        var o = frameOffset * 6
        while (i < frames * 4) {   // keep the top two bytes of each 3-byte sample
            pack16[i] = out[o + 1]; pack16[i + 1] = out[o + 2]
            pack16[i + 2] = out[o + 4]; pack16[i + 3] = out[o + 5]
            i += 4; o += 6
        }
        md16.update(pack16, 0, frames * 4)
    }

    private fun finish(exp: DirectOutputState.Expected) {
        val got24 = md24.digest()
        val got16 = md16.digest()
        val got = if (exp.bits == 24) got24 else got16
        val name = exp.label.take(48)
        val detail = "${exp.source} ${exp.bits}-bit ${String.format(Locale.US, "%.1f", exp.rate / 1000f)} kHz"
        val short: String
        val text: String
        when {
            !exp.supported || exp.md5 == null -> return
            exp.md5.all { it.toInt() == 0 } -> { short = "no MD5 in file"; text = short }
            total != exp.totalSamples -> {
                short = "MD5 not comparable (length)"; text = short
            }
            got.contentEquals(exp.md5) -> { short = "MD5 MATCH"; text = short }
            else -> { short = "MD5 DIFFERENT"; text = short }
        }
        DirectOutputState.log("MD5 check: $short | $name | $detail | frames=$total of ${exp.totalSamples} | file=${hex(exp.md5)} sent=${hex(got)}" +
            if (exp.bits == 24) " | top16=${hex(got16)}" else "")
        DirectOutputState.checksumText = "$text: $name"
        DirectOutputState.checksumToast = "$short\n$name\n$detail"
        DirectOutputState.checksumSerial++
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format(Locale.US, "%02x", it) }

    companion object {
        private const val CHUNK_FRAMES = 2048
        private const val START_WINDOW_US = 150_000L
    }
}
