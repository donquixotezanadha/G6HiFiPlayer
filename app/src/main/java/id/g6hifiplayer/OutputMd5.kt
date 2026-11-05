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
    private val chunkMd = MessageDigest.getInstance("MD5")
    private val sentChunks = ArrayList<ByteArray>()
    private var chunkFill = 0L
    private var lowZero = 0L
    private var ownAtStart = DirectOutputState.ownDecodedSamples
    private val sentHead = ByteArray(48)
    private var headLen = 0
    private var padLeft = DirectOutputState.padFrames(sampleRate)   // silent frames the own decoders put in front of each track
    private var armed = true      // a new output starts at the start of a stream
    private var pending = false   // after flush(): the next accepted data decides whether this is a track start or a seek

    fun onFlush() {
        armed = false; pending = true; total = 0L
        md24.reset(); md16.reset(); resetDiag()
    }

    private fun resetDiag() {
        chunkMd.reset(); sentChunks.clear(); chunkFill = 0L; lowZero = 0L; headLen = 0
        ownAtStart = DirectOutputState.ownDecodedSamples
        padLeft = DirectOutputState.padFrames(sampleRate)
    }

    /** [out] holds [frames] stereo frames of 24-bit packed little-endian samples that the track accepted. */
    fun feed(out: ByteArray, frames: Int, presentationTimeUs: Long) {
        if (pending) {
            pending = false
            armed = presentationTimeUs in 0..START_WINDOW_US
            total = 0L
            md24.reset(); md16.reset(); resetDiag()
        }
        if (!armed || frames <= 0) return
        var offset = 0
        var remaining = frames
        while (remaining > 0) {
            if (padLeft > 0) {      // Hifi 1.28: not music, only the pad that keeps the sink's 20 ms fade-in off the data
                val skip = minOf(remaining, padLeft)
                offset += skip; remaining -= skip; padLeft -= skip
                continue
            }
            val exp = DirectOutputState.expected
            val room = if (exp != null && exp.rate == sampleRate) exp.totalSamples - total else Long.MAX_VALUE
            val take = if (room <= 0L) remaining else minOf(remaining.toLong(), room).toInt()
            update(out, offset, take)
            total += take; offset += take; remaining -= take
            if (exp != null && exp.rate == sampleRate && total >= exp.totalSamples) {
                finish(exp)
                total = 0L; resetDiag()   // whatever follows in this chunk is the start of the next track
            }
        }
    }

    private fun update(out: ByteArray, frameOffset: Int, frames: Int) {
        md24.update(out, frameOffset * 6, frames * 6)
        // diagnostics: per-second digests, empty low bytes, first bytes
        if (headLen < sentHead.size) { val c = minOf(sentHead.size - headLen, frames * 6); System.arraycopy(out, frameOffset * 6, sentHead, headLen, c); headLen += c }
        var z = frameOffset * 6
        val zEnd = z + frames * 6
        while (z < zEnd) { if (out[z].toInt() == 0) lowZero++; z += 3 }
        var done = 0
        while (done < frames) {
            val take = minOf((frames - done).toLong(), sampleRate - chunkFill).toInt()
            chunkMd.update(out, (frameOffset + done) * 6, take * 6)
            chunkFill += take; done += take
            if (chunkFill == sampleRate.toLong()) { sentChunks.add(chunkMd.digest()); chunkFill = 0L }
        }
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
        if (chunkFill > 0) { sentChunks.add(chunkMd.digest()); chunkFill = 0L }
        var short: String
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
        // Hifi 1.27: which decoder made this track? The own decoder runs a little ahead of the output (a second or so), so allow
        // 3 seconds of slack; MediaCodec tracks never move the counter.
        val ownDecoder = DirectOutputState.ownDecodedSamples - ownAtStart + 3L * exp.rate >= exp.totalSamples
        DirectOutputState.checksumOwnDecoder = ownDecoder
        DirectOutputState.checksumResult = when {
            short.startsWith("MD5 MATCH") -> DirectOutputState.CHECK_MATCH
            short == "MD5 DIFFERENT" -> DirectOutputState.CHECK_DIFFERENT
            else -> DirectOutputState.CHECK_NONE
        }
        var extra = ""
        if (exp.bits == 24 && exp.refChunks != null && !short.startsWith("MD5 MATCH") && exp.supported) {
            var first = -1
            var bad = 0
            val n = minOf(sentChunks.size, exp.refChunks.size)
            for (i in 0 until n) if (!sentChunks[i].contentEquals(exp.refChunks[i])) { bad++; if (first < 0) first = i }
            val samples = total * 2
            val sentZ = if (samples > 0) lowZero * 100 / samples else 0
            val refZ = if (exp.refLowZero >= 0 && samples > 0) exp.refLowZero * 100 / samples else -1
            extra = "seconds differing: $bad of $n, first at ${if (first < 0) "-" else first}s | empty low byte sent $sentZ% file $refZ%"
            DirectOutputState.log("MD5 diag: $extra | headSent=${hex(sentHead)} headFile=${exp.refHead?.let { hex(it) }}")
        }
        DirectOutputState.log("MD5 check: $short | decoder=${if (ownDecoder) "own" else "mediacodec"} | $name | $detail | frames=$total of ${exp.totalSamples} | file=${hex(exp.md5)} sent=${hex(got)}" +
            if (exp.bits == 24) " | top16=${hex(got16)}" else "")
        DirectOutputState.checksumText = "$text: $name"
        DirectOutputState.checksumToast = "$short\n$name\n$detail" + (if (extra.isNotEmpty()) "\n$extra" else "") +
            "\ndecoder: " + (if (ownDecoder) "own (FLAC / ALAC / WAV)" else "Android MediaCodec")
        DirectOutputState.checksumSerial++
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format(Locale.US, "%02x", it) }

    companion object {
        private const val CHUNK_FRAMES = 2048
        private const val START_WINDOW_US = 150_000L
    }
}
