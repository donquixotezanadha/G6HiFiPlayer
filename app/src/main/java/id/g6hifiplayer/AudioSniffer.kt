package id.g6hifiplayer

import android.content.Context
import android.net.Uri
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
    fun sniff(context: Context, uri: Uri): SourceInfo? = try {
        val opened = context.contentResolver.openInputStream(uri)
        if (opened == null) null else opened.use { sniffStream(it) }
    } catch (_: Exception) { null }

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
