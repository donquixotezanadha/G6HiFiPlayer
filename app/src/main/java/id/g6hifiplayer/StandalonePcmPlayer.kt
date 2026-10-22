package id.g6hifiplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental PCM pipeline isolated from ExoPlayer/Media3.
 * FFmpeg decodes the source to PCM16 WAV; this class parses WAV itself and streams
 * bounded PCM chunks into AudioTrack. The final output is still the normal Android path.
 */
internal class StandalonePcmPlayer {
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var cancelled: AtomicBoolean? = null

    fun play(wavFile: File, report: (String) -> Unit) {
        stop()
        val token = AtomicBoolean(false)
        cancelled = token
        executor.execute {
            var trackForCleanup: AudioTrack? = null
            try {
                RandomAccessFile(wavFile, "r").use { wav ->
                    val info = WavInfo.read(wav)
                    require(info.channels == 1 || info.channels == 2) {
                        "Stage-1 supports mono/stereo PCM; decoded file has ${info.channels} channels"
                    }
                    require(info.bitsPerSample == 16) {
                        "Expected PCM16 WAV from decoder, received ${info.bitsPerSample}-bit"
                    }
                    require(info.sampleRate in 8000..192000) {
                        "Unsupported sample rate in WAV: ${info.sampleRate} Hz"
                    }
                    val outputMask = if (info.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minBytes = AudioTrack.getMinBufferSize(info.sampleRate, outputMask, AudioFormat.ENCODING_PCM_16BIT)
                    check(minBytes > 0) {
                        "AudioTrack.getMinBufferSize failed: $minBytes at ${info.sampleRate} Hz, ${info.channels} ch PCM16"
                    }
                    val bufferBytes = maxOf(minBytes * 2, info.sampleRate * info.channels * 2 / 4)
                    val format = AudioFormat.Builder()
                        .setSampleRate(info.sampleRate)
                        .setChannelMask(outputMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    val outputTrack = AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                    trackForCleanup = outputTrack
                    check(outputTrack.state == AudioTrack.STATE_INITIALIZED) {
                        "AudioTrack initialization failed (state=${outputTrack.state}) at ${info.sampleRate} Hz PCM16"
                    }
                    activeTrack = outputTrack
                    report("PLAYING: custom WAV parser → PCM16 writer; source=${info.sampleRate} Hz / ${info.bitsPerSample}-bit / ${info.channels} ch; AudioTrack buffer=$bufferBytes bytes.")
                    report("Output route: ${describeRoute(outputTrack)}. This does not prove DIRECT or bit-perfect.")
                    outputTrack.play()

                    wav.seek(info.dataOffset)
                    var remaining = info.dataSize
                    val framesPerChunk = 4096
                    val inputBytesPerFrame = info.blockAlign
                    val inputBuffer = ByteArray(framesPerChunk * inputBytesPerFrame)
                    var submittedFrames = 0L
                    while (remaining > 0 && !token.get()) {
                        val requested = minOf(inputBuffer.size.toLong(), remaining).toInt()
                        val aligned = requested - requested % inputBytesPerFrame
                        if (aligned <= 0) break
                        wav.readFully(inputBuffer, 0, aligned)
                        var offset = 0
                        while (offset < aligned && !token.get()) {
                            val written = outputTrack.write(inputBuffer, offset, aligned - offset, AudioTrack.WRITE_BLOCKING)
                            check(written > 0) { "AudioTrack.write failed: $written" }
                            offset += written
                            submittedFrames += written / (info.channels * 2)
                        }
                        remaining -= aligned
                    }
                    // Wait for the output track to consume queued frames before releasing it.
                    while (!token.get() && submittedFrames > 0L) {
                        val playedFrames = outputTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        if (playedFrames >= submittedFrames) break
                        Thread.sleep(10)
                    }
                    if (!token.get()) report("Finished: AudioTrack consumed the submitted PCM frames.")
                    else report("Stopped.")
                }
            } catch (error: Throwable) {
                if (!token.get()) report("ERROR: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                try { trackForCleanup?.pause() } catch (_: Exception) { }
                try { trackForCleanup?.flush() } catch (_: Exception) { }
                try { trackForCleanup?.release() } catch (_: Exception) { }
                if (activeTrack === trackForCleanup) activeTrack = null
                if (cancelled === token) cancelled = null
            }
        }
    }

    fun stop() {
        cancelled?.set(true)
        val track = activeTrack
        try { track?.pause() } catch (_: Exception) { }
        try { track?.flush() } catch (_: Exception) { }
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        activeTrack = null
    }

    private fun describeRoute(track: AudioTrack): String {
        if (Build.VERSION.SDK_INT < 23) return "route API unavailable"
        return try {
            val device = track.routedDevice
            if (device == null) "unconfirmed" else "type=${device.type}, id=${device.id}, product=${device.productName}"
        } catch (error: Exception) { "unconfirmed (${error.javaClass.simpleName})" }
    }

    private fun convertToPcm16(input: ByteArray, output: ByteArray, frames: Int, info: WavInfo): Int {
        val bytesPerSample = info.bitsPerSample / 8
        var out = 0
        var src = 0
        repeat(frames * info.channels) {
            val sample16 = when (info.bitsPerSample) {
                16 -> {
                    val lo = input[src].toInt() and 0xFF
                    val hi = input[src + 1].toInt()
                    (hi shl 8) or lo
                }
                24 -> {
                    val value = (input[src].toInt() and 0xFF) or
                        ((input[src + 1].toInt() and 0xFF) shl 8) or
                        (input[src + 2].toInt() shl 16)
                    value shr 8
                }
                32 -> {
                    val value = (input[src].toInt() and 0xFF) or
                        ((input[src + 1].toInt() and 0xFF) shl 8) or
                        ((input[src + 2].toInt() and 0xFF) shl 16) or
                        (input[src + 3].toInt() shl 24)
                    value shr 16
                }
                else -> error("Unsupported PCM depth")
            }
            output[out++] = (sample16 and 0xFF).toByte()
            output[out++] = ((sample16 shr 8) and 0xFF).toByte()
            src += bytesPerSample
        }
        return out
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val dataSize: Long
    ) {
        companion object {
            fun read(file: RandomAccessFile): WavInfo {
                require(readFourCc(file) == "RIFF") { "Not a RIFF/WAV file" }
                readU32(file)
                require(readFourCc(file) == "WAVE") { "RIFF container is not WAVE" }
                var sampleRate = 0
                var channels = 0
                var bits = 0
                var blockAlign = 0
                var dataOffset = -1L
                var dataSize = 0L
                while (file.filePointer + 8 <= file.length()) {
                    val chunk = readFourCc(file)
                    val size = readU32(file)
                    val start = file.filePointer
                    val end = start + size
                    require(end >= start && end <= file.length()) { "Invalid WAV chunk '$chunk' size=$size" }
                    when (chunk) {
                        "fmt " -> {
                            require(size >= 16) { "Invalid WAV fmt chunk" }
                            val formatTag = readU16(file)
                            channels = readU16(file)
                            sampleRate = readU32(file).toInt()
                            readU32(file)
                            blockAlign = readU16(file)
                            bits = readU16(file)
                            when (formatTag) {
                                1 -> Unit
                                0xFFFE -> {
                                    require(size >= 40) { "WAVE_FORMAT_EXTENSIBLE fmt chunk is too short" }
                                    val extensionSize = readU16(file)
                                    require(extensionSize >= 22) { "Invalid WAVE_FORMAT_EXTENSIBLE extension size=$extensionSize" }
                                    readU16(file) // valid bits per sample
                                    readU32(file) // channel mask
                                    val subtype = readU32(file).toInt()
                                    require(subtype == 1) { "WAV extensible subtype is not integer PCM (subtype=$subtype)" }
                                }
                                else -> error("WAV is not integer PCM (format tag=$formatTag)")
                            }
                        }
                        "data" -> {
                            dataOffset = start
                            dataSize = size
                        }
                    }
                    val next = end + (size and 1L)
                    if (next > file.length()) break
                    file.seek(next)
                    if (dataOffset >= 0 && sampleRate > 0) break
                }
                require(sampleRate > 0 && channels > 0 && blockAlign > 0 && dataOffset >= 0) {
                    "WAV is missing valid fmt/data chunks (rate=$sampleRate channels=$channels blockAlign=$blockAlign dataOffset=$dataOffset)"
                }
                require(dataSize >= blockAlign) { "WAV data chunk is empty or truncated (size=$dataSize)" }
                require(blockAlign == channels * (bits / 8)) { "Invalid WAV block alignment: $blockAlign for $channels channels / $bits-bit" }
                return WavInfo(sampleRate, channels, bits, blockAlign, dataOffset, dataSize)
            }

            private fun readFourCc(file: RandomAccessFile): String {
                val bytes = ByteArray(4)
                file.readFully(bytes)
                return String(bytes, Charsets.US_ASCII)
            }
            private fun readU16(file: RandomAccessFile): Int {
                val a = file.read()
                val b = file.read()
                require(a >= 0 && b >= 0) { "Unexpected end of WAV" }
                return a or (b shl 8)
            }
            private fun readU32(file: RandomAccessFile): Long {
                val a = file.read()
                val b = file.read()
                val c = file.read()
                val d = file.read()
                require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "Unexpected end of WAV" }
                return (a.toLong() and 0xFF) or ((b.toLong() and 0xFF) shl 8) or
                    ((c.toLong() and 0xFF) shl 16) or ((d.toLong() and 0xFF) shl 24)
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "g6-standalone-pcm").apply { isDaemon = true }
        }
    }
}
