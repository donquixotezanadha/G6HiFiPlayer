package id.g6hifiplayer

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Build 1.61: keeps the real bit depth of hi-res files.
 *
 * Android's built-in FLAC decoder hands 16-bit PCM to the player even for a 24-bit FLAC, so the low 8 bits are lost
 * before the output ever sees the data. For FLAC files deeper than 16 bits this class lets FFmpeg decode the file to a
 * 24-bit WAV (pcm_s24le, no -ar, no -ac: no resampling, no dither, no gain) and the player reads that WAV instead.
 * WAV files and 16-bit FLAC need no conversion and are played as they are.
 */
internal object Pcm24Transcoder {
    private const val CACHE_PREFIX = "pcm24-"
    private const val KEEP_FILES = 4

    data class SourceInfo(val codec: String, val bits: Int, val sampleRate: Int, val channels: Int)

    /** Set while FFmpeg is decoding, so the UI can say what it is waiting for. */
    @Volatile var busyTitle: String? = null
        private set

    /**
     * Build 1.64: why the last 24-bit conversion failed (null when the last one worked). The Now Playing info line shows it,
     * so a failure can be read off the screen instead of ending in silence.
     */
    @Volatile var lastFailure: String? = null
        private set

    private val locks = ConcurrentHashMap<String, Any>()
    private val served = ConcurrentHashMap<String, String>()
    private val failed = ConcurrentHashMap<String, String>()

    private val prefetchQueued = ConcurrentHashMap.newKeySet<String>()
    private val prefetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pcm24-prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** Starts decoding [uri] in the background (if it is a hi-res FLAC that needs it) so it is ready when the player asks. */
    fun prefetch(context: Context, uri: Uri) {
        if (uri.scheme != "content" && uri.scheme != "file") return
        val key = uri.toString()
        if (served.containsKey(key) || !prefetchQueued.add(key)) return
        val app = context.applicationContext
        prefetchExecutor.execute {
            try { servedUri(app, uri) } catch (_: Throwable) { } finally { prefetchQueued.remove(key) }
        }
    }

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

    fun needsTranscode(info: SourceInfo?): Boolean = info != null && info.codec == "FLAC" && info.bits > 16

    /**
     * The URI the player should read for [uri]: the 24-bit WAV for deep FLAC files, otherwise [uri] itself.
     * Blocks while FFmpeg decodes (the caller is a loader thread); throws IOException when the 24-bit file cannot be made,
     * so the player reports an error instead of silently playing 16-bit.
     */
    @Throws(IOException::class)
    fun servedUri(context: Context, uri: Uri): Uri {
        if (uri.scheme != "content" && uri.scheme != "file") return uri
        val key = uri.toString()
        served[key]?.let { path ->
            val f = File(path)
            if (f.isFile && f.length() > 44L) { f.setLastModified(System.currentTimeMillis()); return Uri.fromFile(f) }
            served.remove(key)
        }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            served[key]?.let { path -> val f = File(path); if (f.isFile && f.length() > 44L) return Uri.fromFile(f) }
            // Build 1.64: a track that FFmpeg cannot convert is no longer refused. It is played as it is (Android's own decoder,
            // float output ON) and the info line says honestly which depth arrived and why the 24-bit conversion failed.
            failed[key]?.let { return uri }
            val info = sniff(context, uri)
            if (!needsTranscode(info)) return uri
            val wav = try {
                transcode(context, uri, info!!)
            } catch (e: Throwable) {
                val why = (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(300)
                failed[key] = why
                lastFailure = "${e.javaClass.simpleName}: $why"
                DirectOutputState.log("24-bit conversion failed, playing the original file instead: ${lastFailure}")
                return uri
            }
            lastFailure = null
            served[key] = wav.absolutePath
            return Uri.fromFile(wav)
        }
    }

    private fun transcode(context: Context, uri: Uri, info: SourceInfo): File {
        val app = context.applicationContext
        val hash = sha(uri.toString() + "|pcm24v1")
        val out = File(app.cacheDir, "$CACHE_PREFIX$hash.wav")
        if (out.isFile && out.length() > 44L && verify(out, info)) { out.setLastModified(System.currentTimeMillis()); return out }
        val input = File(app.cacheDir, "pcm24-input-$hash.flac")
        val temp = File(app.cacheDir, "$CACHE_PREFIX$hash.tmp.wav")
        busyTitle = uri.lastPathSegment ?: "track"
        try {
            app.contentResolver.openInputStream(uri).use { src ->
                requireNotNull(src) { "cannot open the source file" }
                input.outputStream().use { dst -> src.copyTo(dst, 256 * 1024) }
            }
            temp.delete()
            // No -ar, no -ac, no filters: FFmpeg's FLAC decoder gives 32-bit integers with the 24 sample bits on top and
            // pcm_s24le stores exactly those 24 bits. Nothing is resampled, dithered or scaled.
            val command = "-y -hide_banner -loglevel error -i ${quote(input.absolutePath)} " +
                "-map 0:a:0 -vn -sn -dn -map_metadata -1 -c:a pcm_s24le -f wav ${quote(temp.absolutePath)}"
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode) || !temp.isFile || temp.length() <= 44L) {
                val logs = try { session.allLogsAsString ?: "" } catch (_: Exception) { "" }
                throw IOException("FFmpeg could not decode to 24-bit (code ${session.returnCode}): ${logs.takeLast(600)}")
            }
            if (!verify(temp, info)) throw IOException("FFmpeg output is not ${info.sampleRate} Hz / 24-bit / ${info.channels} ch; refusing to play a changed signal")
            if (!temp.renameTo(out)) { temp.copyTo(out, overwrite = true); temp.delete() }
            prune(out)
            DirectOutputState.log("decoded ${info.bits}-bit ${info.sampleRate} Hz FLAC to 24-bit WAV (${out.length() / 1024 / 1024} MB), no resampling")
            return out
        } finally {
            busyTitle = null
            try { input.delete() } catch (_: Exception) { }
            if (temp.exists() && !out.exists()) try { temp.delete() } catch (_: Exception) { }
        }
    }

    /** The finished WAV must carry the same rate and channel count as the source, at 24 bits. */
    private fun verify(wav: File, info: SourceInfo): Boolean = try {
        RandomAccessFile(wav, "r").use { f ->
            val head = ByteArray(4096)
            val n = f.read(head)
            val s = sniffStream(head.copyOf(maxOf(n, 0)).inputStream())
            s != null && s.codec == "WAV" && s.bits == 24 && s.sampleRate == info.sampleRate && s.channels == info.channels
        }
    } catch (_: Exception) { false }

    private fun prune(keep: File) {
        // Keep the newest few files (current track, the one being prefetched, and a spare for a seek or skip back).
        val dir = keep.parentFile ?: return
        dir.listFiles { f -> f.name.startsWith(CACHE_PREFIX) && f.name.endsWith(".wav") && !f.name.endsWith(".tmp.wav") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES)
            ?.filter { it != keep }
            ?.forEach { old ->
                try { old.delete() } catch (_: Exception) { }
                served.entries.removeAll { it.value == old.absolutePath }
            }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
