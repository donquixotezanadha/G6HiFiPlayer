package id.g6hifiplayer

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Tags (and optionally the embedded cover picture) found in a WAV file. Every field is null when the file has none. */
internal class WavTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
    val picture: ByteArray?
)

/**
 * Android's MediaMetadataRetriever returns nothing for the tags of most WAV files. WAV stores them in a
 * "LIST"/"INFO" chunk and/or an "id3 " chunk (an ID3v2 tag), before or after the audio data, and some taggers
 * put an ID3v2 block in front of the RIFF header. This reader walks the chunk list and reads all of them.
 * It uses random access, so the audio data is never read.
 */
internal object WavTagReader {
    private const val MAX_BLOCK_BYTES = 24 * 1024 * 1024
    private const val MAX_INFO_BYTES = 2 * 1024 * 1024
    private val WINDOWS_1252: Charset = try { Charset.forName("windows-1252") } catch (_: Throwable) { Charsets.ISO_8859_1 }

    fun isWavName(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return ext == "wav" || ext == "wave"
    }

    /** Returns null when the file is not a readable WAV (or cannot be read with random access). */
    fun read(context: Context, uri: Uri, wantPicture: Boolean): WavTags? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // The descriptor is closed by use{}; the stream built on it is only a handle for its channel.
                parse(FileInputStream(pfd.fileDescriptor).channel, wantPicture)
            }
        } catch (_: Throwable) { null }
    }

    private class Found {
        var id3Title: String? = null
        var id3Artist: String? = null
        var id3AlbumArtist: String? = null
        var id3Album: String? = null
        var id3Track: Int? = null
        var infoTitle: String? = null
        var infoArtist: String? = null
        var infoAlbum: String? = null
        var infoTrack: Int? = null
        var picture: ByteArray? = null
        var pictureType = -1
    }

    private fun parse(ch: FileChannel, wantPicture: Boolean): WavTags? {
        val size = ch.size()
        if (size < 12) return null
        val found = Found()
        var pos = 0L

        // Some taggers write an ID3v2 block in front of the RIFF header.
        val lead = readBytes(ch, 0, 10)
        if (lead != null && lead.size == 10 && lead[0] == 'I'.code.toByte() && lead[1] == 'D'.code.toByte() && lead[2] == '3'.code.toByte()) {
            val footer = if ((lead[5].toInt() and 0x10) != 0) 10 else 0
            val tagLength = syncsafe(lead, 6).toLong() + 10L + footer
            parseId3(readBytes(ch, 0, tagLength.coerceAtMost(MAX_BLOCK_BYTES.toLong()).toInt()), found, wantPicture)
            pos = tagLength
        }

        val riff = readBytes(ch, pos, 12) ?: return null
        if (riff.size < 12) return null
        val magic = String(riff, 0, 4, Charsets.ISO_8859_1)
        if ((magic != "RIFF" && magic != "RF64") || String(riff, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        pos += 12

        var rf64DataSize = -1L
        var guard = 0
        while (pos + 8 <= size && guard++ < 1024) {
            val header = readBytes(ch, pos, 8) ?: break
            if (header.size < 8) break
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            var length = u32(header, 4)
            val body = pos + 8
            when (id) {
                "ds64" -> readBytes(ch, body, 16)?.takeIf { it.size == 16 }?.let { rf64DataSize = le64(it, 8) }
                "data" -> {
                    if (length == 0xFFFFFFFFL && rf64DataSize >= 0) length = rf64DataSize
                    if (length < 0 || body + length > size) length = size - body
                }
                "LIST" -> if (length in 4..MAX_INFO_BYTES.toLong()) {
                    val block = readBytes(ch, body, length.toInt())
                    if (block != null && block.size >= 4 && String(block, 0, 4, Charsets.ISO_8859_1) == "INFO") {
                        parseInfo(block, 4, block.size, found)
                    }
                }
                "id3 ", "ID3 ", "ID32" -> if (length in 10..MAX_BLOCK_BYTES.toLong()) {
                    parseId3(readBytes(ch, body, length.toInt()), found, wantPicture)
                }
            }
            if (length < 0) break
            pos = body + length + (length and 1L)
        }

        return WavTags(
            title = found.id3Title ?: found.infoTitle,
            artist = found.id3Artist ?: found.id3AlbumArtist ?: found.infoArtist,
            album = found.id3Album ?: found.infoAlbum,
            trackNumber = found.id3Track ?: found.infoTrack,
            picture = found.picture
        )
    }

    // ---- LIST/INFO ---------------------------------------------------------------------------------------------

    private fun parseInfo(b: ByteArray, start: Int, end: Int, out: Found) {
        var p = start
        while (p + 8 <= end) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val len = u32(b, p + 4)
            val s = p + 8
            if (len < 0 || s + len > end) break
            val value = decodeLenient(b, s, len.toInt())
            when (id) {
                "INAM" -> if (out.infoTitle == null) out.infoTitle = value
                "IART" -> if (out.infoArtist == null) out.infoArtist = value
                "IPRD" -> if (out.infoAlbum == null) out.infoAlbum = value
                "ITRK", "IPRT" -> if (out.infoTrack == null) out.infoTrack = trackNumberOf(value)
            }
            p = (s + len + (len and 1L)).toInt()
        }
    }

    // ---- ID3v2 -------------------------------------------------------------------------------------------------

    private fun parseId3(tag: ByteArray?, out: Found, wantPicture: Boolean) {
        if (tag == null || tag.size < 10) return
        if (tag[0] != 'I'.code.toByte() || tag[1] != 'D'.code.toByte() || tag[2] != '3'.code.toByte()) return
        val major = tag[3].toInt() and 0xFF
        if (major !in 2..4) return
        val flags = tag[5].toInt() and 0xFF
        val declared = syncsafe(tag, 6)
        var body = tag.copyOfRange(10, (10 + declared).coerceAtMost(tag.size))
        // Tag-level unsynchronisation (v2.2/2.3): every 0xFF 0x00 stands for a single 0xFF.
        if ((flags and 0x80) != 0 && major < 4) body = removeUnsync(body)
        var p = 0
        if ((flags and 0x40) != 0 && major >= 3 && body.size >= 4) {
            val extended = if (major == 3) be32(body, 0) + 4 else syncsafe(body, 0)
            if (extended in 4..body.size) p = extended
        }
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        while (p + headerLen <= body.size) {
            if (body[p].toInt() == 0) break // padding
            val id = String(body, p, idLen, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = when (major) {
                2 -> ((body[p + 3].toInt() and 0xFF) shl 16) or ((body[p + 4].toInt() and 0xFF) shl 8) or (body[p + 5].toInt() and 0xFF)
                3 -> be32(body, p + 4)
                else -> syncsafe(body, p + 4)
            }
            val frameFlags = if (major >= 3) ((body[p + 8].toInt() and 0xFF) shl 8) or (body[p + 9].toInt() and 0xFF) else 0
            val s = p + headerLen
            if (size < 0 || s + size > body.size) break
            p = s + size
            val wanted = when (id) {
                "TIT2", "TT2", "TPE1", "TP1", "TPE2", "TP2", "TALB", "TAL", "TRCK", "TRK" -> true
                "APIC", "PIC" -> wantPicture
                else -> false
            }
            if (!wanted || size < 2) continue
            var data = body.copyOfRange(s, s + size)
            if (major == 4) {
                // v2.4 frame flags: 0x0008 compressed, 0x0004 encrypted (skip); 0x0002 unsynchronised; 0x0001 length prefix.
                if ((frameFlags and 0x000C) != 0) continue
                if ((frameFlags and 0x0002) != 0) data = removeUnsync(data)
                if ((frameFlags and 0x0001) != 0 && data.size > 4) data = data.copyOfRange(4, data.size)
            } else if (major == 3 && (frameFlags and 0x00C0) != 0) {
                continue // v2.3 compressed / encrypted
            }
            when (id) {
                "TIT2", "TT2" -> if (out.id3Title == null) out.id3Title = id3Text(data)
                "TPE1", "TP1" -> if (out.id3Artist == null) out.id3Artist = id3Text(data)
                "TPE2", "TP2" -> if (out.id3AlbumArtist == null) out.id3AlbumArtist = id3Text(data)
                "TALB", "TAL" -> if (out.id3Album == null) out.id3Album = id3Text(data)
                "TRCK", "TRK" -> if (out.id3Track == null) out.id3Track = trackNumberOf(id3Text(data))
                "APIC", "PIC" -> id3Picture(data, major, out)
            }
        }
    }

    private fun id3Text(frame: ByteArray): String? {
        if (frame.size < 2) return null
        val parts = decodeId3(frame[0].toInt() and 0xFF, frame, 1, frame.size - 1)
            .split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun id3Picture(frame: ByteArray, major: Int, out: Found) {
        val encoding = frame[0].toInt() and 0xFF
        var q = 1
        if (major == 2) {
            q += 3 // three-letter image format
        } else {
            while (q < frame.size && frame[q].toInt() != 0) q++ // MIME type
            q++
        }
        if (q >= frame.size) return
        val type = frame[q].toInt() and 0xFF
        q++
        // Description, terminated per its encoding.
        if (encoding == 1 || encoding == 2) {
            while (q + 1 < frame.size && !(frame[q].toInt() == 0 && frame[q + 1].toInt() == 0)) q += 2
            q += 2
        } else {
            while (q < frame.size && frame[q].toInt() != 0) q++
            q++
        }
        if (q >= frame.size) return
        // Prefer the front cover (type 3); otherwise keep the first picture found.
        if (out.picture == null || (type == 3 && out.pictureType != 3)) {
            out.picture = frame.copyOfRange(q, frame.size)
            out.pictureType = type
        }
    }

    private fun decodeId3(encoding: Int, b: ByteArray, off: Int, len: Int): String = when (encoding) {
        1 -> String(b, off, len, Charsets.UTF_16)      // byte order mark decides the order
        2 -> String(b, off, len, Charsets.UTF_16BE)
        3 -> String(b, off, len, Charsets.UTF_8)
        else -> decodeLenient(b, off, len, keepNuls = true) ?: ""
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** UTF-8 when the bytes are valid UTF-8 (many taggers write it even when the file says Latin-1), otherwise Windows-1252. */
    private fun decodeLenient(b: ByteArray, off: Int, len: Int, keepNuls: Boolean = false): String? {
        if (len <= 0) return null
        var end = off + len
        if (!keepNuls) {
            // INFO strings are NUL-terminated; ignore anything after the first NUL.
            var i = off
            while (i < end && b[i].toInt() != 0) i++
            end = i
        }
        if (end <= off) return null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, end - off)).toString()
        } catch (_: CharacterCodingException) {
            String(b, off, end - off, WINDOWS_1252)
        }
        return if (keepNuls) text else text.trim().takeIf { it.isNotEmpty() }
    }

    private fun trackNumberOf(value: String?): Int? =
        value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun removeUnsync(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            val v = src[i].toInt() and 0xFF
            out.write(v)
            if (v == 0xFF && i + 1 < src.size && src[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun readBytes(ch: FileChannel, pos: Long, n: Int): ByteArray? {
        if (n < 0 || n > MAX_BLOCK_BYTES || pos < 0) return null
        val buffer = ByteBuffer.allocate(n)
        var p = pos
        while (buffer.hasRemaining()) {
            val r = ch.read(buffer, p)
            if (r <= 0) break
            p += r
        }
        return if (buffer.position() == n) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun le64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)
}
