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
 * FFmpeg decodes the source to WAV; this class parses WAV itself, converts samples, and streams
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
                    require(info.bitsPerSample == 24 || info.bitsPerSample == 16 || info.bitsPerSample == 32) {
                        "Unsupported PCM bit depth: ${info.bitsPerSample}"
                    }
                    val outputMask = if (info.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    // Android 9 does not expose packed PCM24 AudioTrack output. Keep 24-bit integer
                    // samples in a PCM24 WAV, then map them to PCM_FLOAT (24 significant bits fit
                    // exactly in IEEE-754 float) for AudioTrack. PCM16 remains the known-good path.
                    val outputEncoding = if (info.bitsPerSample == 16) {
                        AudioFormat.ENCODING_PCM_16BIT
                    } else {
                        AudioFormat.ENCODING_PCM_FLOAT
                    }
                    val outputBytesPerSample = if (outputEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val minBytes = AudioTrack.getMinBufferSize(info.sampleRate, outputMask, outputEncoding)
                    check(minBytes > 0) { "AudioTrack rejected ${info.sampleRate} Hz encoding=$outputEncoding (getMinBufferSize=$minBytes)" }
                    val bufferBytes = maxOf(minBytes * 2, info.sampleRate * info.channels * outputBytesPerSample / 4)
                    val format = AudioFormat.Builder()
                        .setSampleRate(info.sampleRate)
                        .setChannelMask(outputMask)
                        .setEncoding(outputEncoding)
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
                        "AudioTrack failed to initialize at ${info.sampleRate} Hz encoding=$outputEncoding"
                    }
                    activeTrack = outputTrack
                    report("WAV_PARSED rate=${info.sampleRate}Hz channels=${info.channels} bits=${info.bitsPerSample} blockAlign=${info.blockAlign} dataBytes=${info.dataSize} dataOffset=${info.dataOffset}")
                    report("OUTPUT_FORMAT audioTrackEncoding=$outputEncoding outputBytesPerSample=$outputBytesPerSample mode=${if (outputEncoding == AudioFormat.ENCODING_PCM_FLOAT) "PCM_FLOAT preserving source integer precision" else "PCM16"}")
                    report("AUDIOTRACK_INITIALIZED state=${outputTrack.state} sampleRate=${outputTrack.sampleRate} channelCount=${outputTrack.channelCount} audioFormat=${outputTrack.audioFormat} sessionId=${outputTrack.audioSessionId} bufferBytes=${bufferBytes} minBufferBytes=${minBytes}")
                    report("ROUTE ${describeRoute(outputTrack)}; route data does not prove DIRECT or bit-perfect.")
                    var totalWritten = 0L
                    var chunksWritten = 0L
                    var lastProgressAt = System.currentTimeMillis()
                    val frameBytes = info.channels * outputBytesPerSample
                    val playbackStartedAt = System.currentTimeMillis()
                    wav.seek(info.dataOffset)
                    var remaining = info.dataSize
                    val framesPerChunk = 4096
                    val inputBytesPerFrame = info.blockAlign
                    val inputBuffer = ByteArray(framesPerChunk * inputBytesPerFrame)
                    val outputBuffer = ByteArray(framesPerChunk * info.channels * 2)
                    val floatOutputBuffer = FloatArray(framesPerChunk * info.channels)
                    val useFloatOutput = outputEncoding == AudioFormat.ENCODING_PCM_FLOAT

                    fun writeOutputChunk(outputCount: Int) {
                        var offset = 0
                        while (offset < outputCount && !token.get()) {
                            if (useFloatOutput) {
                                // AudioTrack's float API takes FloatArray and counts samples, not bytes.
                                val written = outputTrack.write(floatOutputBuffer, offset, outputCount - offset, AudioTrack.WRITE_BLOCKING)
                                check(written > 0) { "AudioTrack.write(float[]) failed: $written" }
                                check(written % info.channels == 0) {
                                    "AudioTrack.write(float[]) returned a non-frame-aligned sample count: written=$written channels=${info.channels}"
                                }
                                offset += written
                                totalWritten += written.toLong() * 4L
                            } else {
                                val written = outputTrack.write(outputBuffer, offset, outputCount - offset, AudioTrack.WRITE_BLOCKING)
                                check(written > 0) { "AudioTrack.write(byte[]) failed: $written" }
                                check(written % frameBytes == 0) {
                                    "AudioTrack.write returned a non-frame-aligned byte count: written=$written frameBytes=$frameBytes"
                                }
                                offset += written
                                totalWritten += written.toLong()
                            }
                        }
                    }

                    // Prime the track before calling play(). Starting an empty streaming track can
                    // expose a startup underrun/click; one bounded chunk fits inside the chosen buffer.
                    val firstRequested = minOf(inputBuffer.size.toLong(), remaining).toInt()
                    val firstAligned = firstRequested - firstRequested % inputBytesPerFrame
                    check(firstAligned > 0) { "WAV has no complete PCM frame to prefill" }
                    wav.readFully(inputBuffer, 0, firstAligned)
                    val firstFrames = firstAligned / inputBytesPerFrame
                    val firstOutputCount = if (useFloatOutput) {
                        convertToFloat(inputBuffer, floatOutputBuffer, firstFrames, info)
                    } else {
                        convertToPcm16(inputBuffer, outputBuffer, firstFrames, info)
                    }
                    writeOutputChunk(firstOutputCount)
                    val firstOutputBytes = firstOutputCount * outputBytesPerSample
                    remaining -= firstAligned
                    chunksWritten++
                    report("PCM_PREFILL_COMPLETE sourceBytes=$firstAligned outputBytes=$firstOutputBytes frames=$firstFrames; starting AudioTrack after prefill")

                    outputTrack.play()
                    report("AUDIOTRACK_PLAY_CALLED playState=${outputTrack.playState}")
                    report("PCM_WRITER_STARTED dataBytes=${info.dataSize} frameBytes=${frameBytes} framesExpected=${info.dataSize / info.blockAlign} transferMode=MODE_STREAM writeMode=WRITE_BLOCKING")

                    while (remaining > 0 && !token.get()) {
                        val requested = minOf(inputBuffer.size.toLong(), remaining).toInt()
                        val aligned = requested - requested % inputBytesPerFrame
                        if (aligned <= 0) break
                        wav.readFully(inputBuffer, 0, aligned)
                        val frames = aligned / inputBytesPerFrame
                        val outputCount = if (useFloatOutput) {
                            convertToFloat(inputBuffer, floatOutputBuffer, frames, info)
                        } else {
                            convertToPcm16(inputBuffer, outputBuffer, frames, info)
                        }
                        writeOutputChunk(outputCount)
                        remaining -= aligned
                        chunksWritten++
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 1000L) {
                            val submittedFrames = totalWritten / frameBytes
                            val playedFramesNow = outputTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            report("PCM_WRITE_PROGRESS chunks=$chunksWritten bytesWritten=$totalWritten framesSubmitted=$submittedFrames framesPlayed=$playedFramesNow remainingSourceBytes=$remaining elapsedMs=${now - playbackStartedAt}")
                            lastProgressAt = now
                        }
                    }
                    val framesToDrain = totalWritten / frameBytes
                    val drainStartedAt = System.currentTimeMillis()
                    val drainTimeoutMs = (framesToDrain * 1000L / info.sampleRate) + 5000L
                    while (!token.get() &&
                        (outputTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesToDrain &&
                        System.currentTimeMillis() - drainStartedAt < drainTimeoutMs) {
                        Thread.sleep(20)
                    }
                    val elapsedMs = System.currentTimeMillis() - playbackStartedAt
                    val playedFrames = outputTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (!token.get() && playedFrames >= framesToDrain) {
                        report("PLAYBACK_RESULT=STREAM_DRAINED bytesWritten=${totalWritten} framesSubmitted=${framesToDrain} framesPlayed=${playedFrames} sourceDataBytes=${info.dataSize} elapsedMs=${elapsedMs} playState=${outputTrack.playState}")
                        report("PLAYBACK_DRAIN_CONFIRMED playbackHead reached submitted frames; this confirms framework-level drain, not DIRECT output or bit-perfect delivery.")
                    } else if (!token.get()) {
                        report("PLAYBACK_RESULT=DRAIN_TIMEOUT bytesWritten=${totalWritten} framesSubmitted=${framesToDrain} framesPlayed=${playedFrames} drainTimeoutMs=${drainTimeoutMs} elapsedMs=${elapsedMs}")
                    } else {
                        report("PLAYBACK_RESULT=STOPPED bytesWritten=${totalWritten} framesPlayed=${playedFrames} elapsedMs=${elapsedMs}")
                    }
                }
            } catch (error: Throwable) {
                if (!token.get()) report("PLAYBACK_ERROR type=${error.javaClass.name} message=${error.message ?: "no message"}")
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
        // The writer thread owns flush/release in finally. Stop only signals cancellation and
        // interrupts a potentially blocking write; never release the track concurrently here.
        try { track?.stop() } catch (_: Exception) { }
    }

    private fun describeRoute(track: AudioTrack): String {
        if (Build.VERSION.SDK_INT < 23) return "route API unavailable"
        return try {
            val device = track.routedDevice
            if (device == null) "unconfirmed" else "type=${device.type}, id=${device.id}, product=${device.productName}"
        } catch (error: Exception) { "unconfirmed (${error.javaClass.simpleName})" }
    }

    private fun convertToPcm16(input: ByteArray, output: ByteArray, frames: Int, info: WavInfo): Int {
        var out = 0
        var src = 0
        repeat(frames * info.channels) {
            val sampleInt = readIntegerSample(input, src, info.bitsPerSample)
            val sample16 = when (info.bitsPerSample) {
                16 -> sampleInt
                24 -> sampleInt shr 8
                32 -> sampleInt shr 16
                else -> error("Unsupported PCM depth")
            }
            output[out++] = (sample16 and 0xFF).toByte()
            output[out++] = ((sample16 shr 8) and 0xFF).toByte()
            src += info.bitsPerSample / 8
        }
        return out
    }

    private fun convertToFloat(input: ByteArray, output: FloatArray, frames: Int, info: WavInfo): Int {
        var out = 0
        var src = 0
        repeat(frames * info.channels) {
            val sampleInt = readIntegerSample(input, src, info.bitsPerSample)
            val divisor = when (info.bitsPerSample) {
                16 -> 32768.0f
                24 -> 8388608.0f
                32 -> 2147483648.0f
                else -> error("Unsupported PCM depth")
            }
            output[out++] = sampleInt.toFloat() / divisor
            src += info.bitsPerSample / 8
        }
        return out
    }

    private fun readIntegerSample(input: ByteArray, src: Int, bits: Int): Int = when (bits) {
        16 -> {
            val lo = input[src].toInt() and 0xFF
            val hi = input[src + 1].toInt()
            (hi shl 8) or lo
        }
        24 -> (input[src].toInt() and 0xFF) or
            ((input[src + 1].toInt() and 0xFF) shl 8) or
            (input[src + 2].toInt() shl 16)
        32 -> (input[src].toInt() and 0xFF) or
            ((input[src + 1].toInt() and 0xFF) shl 8) or
            ((input[src + 2].toInt() and 0xFF) shl 16) or
            (input[src + 3].toInt() shl 24)
        else -> error("Unsupported PCM depth")
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
                                1 -> Unit // WAVE_FORMAT_PCM
                                0xFFFE -> {
                                    require(size >= 40) { "WAVE_FORMAT_EXTENSIBLE fmt chunk too short: $size" }
                                    val extensionSize = readU16(file)
                                    require(extensionSize >= 22) { "Invalid WAVE_FORMAT_EXTENSIBLE extension size=$extensionSize" }
                                    readU16(file) // valid bits per sample
                                    readU32(file) // channel mask
                                    val guid = ByteArray(16)
                                    file.readFully(guid)
                                    val pcmGuidTail = byteArrayOf(
                                        0, 0, 0, 0, 0, 0, 0x10, 0,
                                        0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71
                                    )
                                    require((guid[0].toInt() and 0xFF) == 1 &&
                                        guid[1].toInt() == 0 && guid[2].toInt() == 0 && guid[3].toInt() == 0 &&
                                        guid.sliceArray(4..15).contentEquals(pcmGuidTail.sliceArray(4..15))) {
                                        "WAV extensible subtype is not integer PCM"
                                    }
                                }
                                else -> error("WAV is not integer PCM (format tag=$formatTag)")
                            }
                        }
                        "data" -> {
                            dataOffset = start
                            dataSize = minOf(size, file.length() - start)
                        }
                    }
                    val next = start + size + (size and 1L)
                    if (next <= start || next > file.length() + 1) break
                    file.seek(next)
                    if (dataOffset >= 0 && sampleRate > 0) break
                }
                require(sampleRate > 0 && channels > 0 && blockAlign > 0 && dataOffset >= 0) {
                    "WAV is missing valid fmt/data chunks"
                }
                require(dataSize >= blockAlign) { "WAV data chunk is empty" }
                require(blockAlign == channels * (bits / 8)) { "Unsupported WAV block alignment" }
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
