package id.g6hifiplayer

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Slow-path FFmpeg decoder used only when Media3's platform decoder fails.
 *
 * It converts to a PCM WAV cache file without specifying -ar or -ac, so FFmpeg does not
 * explicitly resample or remix. PCM WAV playback still goes through Android AudioTrack/HAL;
 * this is not proof of bit-perfect delivery or a Quad DAC bypass.
 */
internal class FfmpegAudioDecoder(context: Context) {
    private val appContext = context.applicationContext

    fun decodeToWav(uri: Uri, callback: (Result<File>) -> Unit) {
        executor.execute {
            var input: File? = null
            try {
                val key = sha256(uri.toString())
                val output = File(appContext.cacheDir, "ffmpeg-pcm16-$key.wav")
                if (output.isFile && output.length() > 44L) {
                    callback(Result.success(output))
                    return@execute
                }

                val extension = uri.lastPathSegment
                    ?.substringAfterLast('.', "audio")
                    ?.take(8)
                    ?.filter { it.isLetterOrDigit() }
                    ?.ifBlank { "audio" } ?: "audio"
                val inputFile = File(appContext.cacheDir, "ffmpeg-input-$key.$extension")
                input = inputFile
                appContext.contentResolver.openInputStream(uri).use { source ->
                    requireNotNull(source) { "Cannot open source audio URI" }.use { stream ->
                        inputFile.outputStream().use { target -> stream.copyTo(target) }
                    }
                }

                val tempOutput = File(appContext.cacheDir, "ffmpeg-pcm16-$key.tmp.wav")
                tempOutput.delete()
                val command = "-y -hide_banner -loglevel error -i ${quote(inputFile.absolutePath)} " +
                    "-map 0:a:0 -vn -sn -dn -c:a pcm_s16le -f wav ${quote(tempOutput.absolutePath)}"
                val session = FFmpegKit.execute(command)
                if (!ReturnCode.isSuccess(session.returnCode) || !tempOutput.isFile || tempOutput.length() <= 44L) {
                    val logs = try { session.allLogsAsString ?: "" } catch (_: Exception) { "" }
                    val detail = logs.takeLast(1800).ifBlank { "FFmpeg produced no diagnostic log." }
                    throw IllegalStateException("FFmpeg decode failed (return code ${session.returnCode}). Details: $detail")
                }
                check(hasRiffHeader(tempOutput)) { "FFmpeg output is not a RIFF/WAVE file" }
                if (!tempOutput.renameTo(output)) {
                    tempOutput.copyTo(output, overwrite = true)
                    tempOutput.delete()
                }
                pruneCache(output)
                callback(Result.success(output))
            } catch (error: Throwable) {
                callback(Result.failure(error))
            } finally {
                try { input?.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun hasRiffHeader(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val header = ByteArray(12)
            stream.read(header) == header.size &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    } catch (_: Exception) { false }

    private fun pruneCache(keep: File) {
        appContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith("ffmpeg-pcm16-") && it.name.endsWith(".wav") && it != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(1)
            ?.forEach { try { it.delete() } catch (_: Exception) { } }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "g6-ffmpeg-decoder").apply { isDaemon = true }
        }
    }
}
