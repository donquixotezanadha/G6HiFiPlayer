package id.g6hifiplayer

import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * Build 1.65: reads the sample rate, bit depth and channel count from the FLAC or WAV header, for the Now Playing info line.
 * (The FFmpeg 24-bit conversion of Build 1.61-1.64 is gone: Android's own FLAC decoder with float output delivers the real
 * 24-bit samples, see BUILD165_NOTES.md.)
 */
internal object AudioSniffer {
    data class SourceInfo(val codec: String, val bits: Int, val sampleRate: Int, val channels: Int)

    /** Reads the sample rate and bit depth from the FLAC or WAV header. Returns null for any other format. */
    fun sniff(context: Context, uri: Uri): SourceInfo? {
        val ordinary = try {
            val opened = context.contentResolver.openInputStream(uri)
            if (opened == null) null else opened.use { sniffStream(it) }
        } catch (_: Exception) { null }
        if (ordinary != null) return ordinary
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)
        if (extension !in setOf("m4a", "mp4", "alac")) return null
        return sniffAlac(context, uri)
    }

    /**
     * Build 2.42: ALAC-in-MP4/M4A stores its source depth in the ALACSpecificConfig atom,
     * not in the FLAC/WAV headers handled above. Parse that encoded configuration first,
     * then use MediaExtractor only as a fallback if the configuration cannot be read. Do not guess from extension:
     * an M4A/AAC file remains unbadged unless it is positively identified as ALAC 24-bit.
     */
    private fun sniffAlac(context: Context, uri: Uri): SourceInfo? {
        // The ALACSpecificConfig is the source of truth. Android 9's extractor can
        // expose decoder/output bit depth (often 16-bit) rather than the encoded ALAC depth.
        // Stream the entire container with a small overlap. The MP4 'moov' atom may
        // occur after a large 'mdat', so reading only the first 16 MiB misses valid ALAC files.
        try {
            val opened = context.contentResolver.openInputStream(uri) ?: return null
            opened.use { stream ->
                val chunk = ByteArray(64 * 1024)
                var tail = ByteArray(0)
                while (true) {
                    val n = stream.read(chunk)
                    if (n <= 0) break
                    val combined = ByteArray(tail.size + n)
                    System.arraycopy(tail, 0, combined, 0, tail.size)
                    System.arraycopy(chunk, 0, combined, tail.size, n)
                    val found = parseAlacConfig(combined)
                    if (found != null) return found
                    val keep = minOf(40, combined.size)
                    tail = combined.copyOfRange(combined.size - keep, combined.size)
                }
            }
        } catch (_: Exception) { }

        // Fallback only when the container config could not be read. Do not let an
        // extractor-reported 16-bit output override a valid 24-bit ALACSpecificConfig.
        var rate = 0
        var channels = 0
                try {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, uri, null)
                        for (i in 0 until extractor.trackCount) {
                            val format = extractor.getTrackFormat(i)
                            val mime = if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME).orEmpty() else ""
                            if (!mime.contains("alac", ignoreCase = true)) continue
                            rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
                            channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
                            val bits = listOf("bits-per-sample", "bit-depth").firstNotNullOfOrNull { key ->
                                if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrNull() else null
                            }
                            if (bits != null && bits in listOf(16, 20, 24, 32)) return SourceInfo("ALAC", bits, rate, channels)
                        }
                    } finally { try { extractor.release() } catch (_: Exception) { } }
                } catch (_: Exception) { }
        return null
    }

    /**
     * ALAC atom layout:
     * size[4], type[4], full-box flags[4], frameLength[4], compatibleVersion[1],
     * bitDepth[1], pb[1], mb[1], kb[1], numChannels[1], maxRun[2],
     * maxFrameBytes[4], avgBitRate[4], sampleRate[4].
     * Therefore bitDepth is at typeOffset + 13 (not +17; +17 is channel count).
     */
    private fun parseAlacConfig(data: ByteArray): SourceInfo? {
        var from = 0
        while (from + 32 < data.size) {
            val type = indexOfAlac(data, from)
            if (type < 0) return null
            from = type + 1
            if (type < 4 || type + 31 >= data.size) continue
            val boxSize = ((data[type - 4].toLong() and 0xFF) shl 24) or
                ((data[type - 3].toLong() and 0xFF) shl 16) or
                ((data[type - 2].toLong() and 0xFF) shl 8) or
                (data[type - 1].toLong() and 0xFF)
            if (boxSize < 36 || boxSize > Int.MAX_VALUE) continue
            val bits = data[type + 13].toInt() and 0xFF
            val channels = data[type + 17].toInt() and 0xFF
            val rate = ((data[type + 28].toLong() and 0xFF) shl 24) or
                ((data[type + 29].toLong() and 0xFF) shl 16) or
                ((data[type + 30].toLong() and 0xFF) shl 8) or
                (data[type + 31].toLong() and 0xFF)
            if (bits in listOf(16, 20, 24, 32) && channels in 1..8 && rate in 8000L..768000L) {
                return SourceInfo("ALAC", bits, rate.toInt(), channels)
            }
        }
        return null
    }

    private fun indexOfAlac(data: ByteArray, start: Int): Int {
        if (data.size < 4 || start > data.size - 4) return -1
        for (i in start..data.size - 4) {
            if (data[i] == 'a'.code.toByte() && data[i + 1] == 'l'.code.toByte() &&
                data[i + 2] == 'a'.code.toByte() && data[i + 3] == 'c'.code.toByte()) return i
        }
        return -1
    }

    private fun sniffStream(raw: InputStream): SourceInfo? {
        val input = DataInputStream(raw.buffered(8192))
        val head = ByteArray(12)
        input.readFully(head)
        if (head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() && head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()) {
            return sniffWav(input)
        }
        var buf = head
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            // ID3v2: 10-byte header, the size (synchsafe) follows. 2 bytes of the header's tail are already in `head`.
            val size = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            var toSkip = size.toLong() + 10L - head.size
            while (toSkip > 0) {
                val n = input.skip(toSkip)
                if (n <= 0) { if (input.read() < 0) return null else toSkip-- } else toSkip -= n
            }
            buf = ByteArray(12)
            input.readFully(buf)
        }
        if (buf[0] != 'f'.code.toByte() || buf[1] != 'L'.code.toByte() || buf[2] != 'a'.code.toByte() || buf[3] != 'C'.code.toByte()) return null
        // First metadata block must be STREAMINFO (type 0). Its payload starts 4 bytes after the "fLaC" marker + 4-byte block header.
        if ((buf[4].toInt() and 0x7F) != 0) return null
        val rest = ByteArray(10)
        input.readFully(rest)
        val all = buf + rest // offset 0 = "fLaC"
        val rate = ((all[18].toInt() and 0xFF) shl 12) or ((all[19].toInt() and 0xFF) shl 4) or ((all[20].toInt() and 0xFF) shr 4)
        val channels = ((all[20].toInt() shr 1) and 0x7) + 1
        val bits = (((all[20].toInt() and 0x1) shl 4) or ((all[21].toInt() and 0xFF) shr 4)) + 1
        return SourceInfo("FLAC", bits, rate, channels)
    }

    private fun sniffWav(input: DataInputStream): SourceInfo? {
        // Walk the chunks until "fmt ".
        var guard = 0
        while (guard++ < 64) {
            val id = ByteArray(4)
            input.readFully(id)
            val size = readLe32(input)
            if (id[0] == 'f'.code.toByte() && id[1] == 'm'.code.toByte() && id[2] == 't'.code.toByte() && id[3] == ' '.code.toByte()) {
                if (size < 16) return null
                val tag = readLe16(input)
                val channels = readLe16(input)
                val rate = readLe32(input).toInt()
                readLe32(input)
                readLe16(input)
                val bits = readLe16(input)
                val integer = tag == 1 || tag == 0xFFFE
                return if (integer) SourceInfo("WAV", bits, rate, channels) else null
            }
            var toSkip = size + (size and 1L)
            while (toSkip > 0) {
                val n = input.skip(toSkip)
                if (n <= 0) { if (input.read() < 0) return null else toSkip-- } else toSkip -= n
            }
        }
        return null
    }

    private fun readLe16(i: DataInputStream): Int { val a = i.readUnsignedByte(); val b = i.readUnsignedByte(); return a or (b shl 8) }
    private fun readLe32(i: DataInputStream): Long {
        val a = i.readUnsignedByte().toLong(); val b = i.readUnsignedByte().toLong()
        val c = i.readUnsignedByte().toLong(); val d = i.readUnsignedByte().toLong()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }
}
