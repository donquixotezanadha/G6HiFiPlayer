package id.g6hifiplayer

import android.media.AudioDeviceInfo
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Build 1.60/1.61: Media3 AudioOutput that writes to the native DIRECT track (UAPP method).
 *
 * The native track is always 24-bit packed stereo at the source sample rate, flags = DIRECT. Input PCM from the sink is
 * converted without any gain or dither: 24-bit is copied byte for byte, 16-bit is padded with a zero low byte (same value,
 * lossless), 32-bit int keeps its top 24 bits. Float is not accepted (see DirectAudioOutputProvider).
 * Volume is left to the hardware/stream volume; setVolume() is ignored on purpose.
 */
@UnstableApi
internal class DirectAudioOutput private constructor(
    private val handle: Long,
    private val sampleRateHz: Int,
    private val channels: Int,
    private val inEncoding: Int,
    private val inBytes: Int,
    private val bufferFrames: Int,
    private val sessionId: Int
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private val outBuf = ByteArray(CHUNK_FRAMES * 6)
    private var released = false
    private var writtenFrames = 0L
    private var started = false          // native start() has been called since the last flush
    private var playing = false
    private var positionAnnounced = false

    // Fallback clock used when AudioTrack::getPosition is unavailable or stays at 0 while playing.
    private var clockMode = false
    private var clockAccumNs = 0L
    private var clockStartNs = 0L
    private var playingSinceMs = 0L

    private var stopAtMs = 0L

    override fun play() {
        if (released) return
        if (!playing) {
            playing = true
            clockStartNs = System.nanoTime()
            playingSinceMs = SystemClock.elapsedRealtime()
        }
        val st = NativeDirectStream.start(handle)
        if (!started) {
            started = true
            val route = NativeDirectStream.routeInfo(handle)
            DirectOutputState.route = route
            DirectOutputState.log("start=$st route: $route")
        }
    }

    override fun pause() {
        if (released) return
        if (playing) {
            clockAccumNs += System.nanoTime() - clockStartNs
            playing = false
        }
        NativeDirectStream.pause(handle)
    }

    @Throws(AudioOutput.WriteException::class)
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        val frameBytes = channels * inBytes
        while (buffer.remaining() >= frameBytes) {
            val frames = minOf(buffer.remaining() / frameBytes, CHUNK_FRAMES)
            val outLen = convert(buffer, frames)
            val n = NativeDirectStream.write(handle, outBuf, 0, outLen)
            if (n < 0) {
                if (n == WOULD_BLOCK) return false
                throw AudioOutput.WriteException(n, n == DEAD_OBJECT)
            }
            val accepted = n / 6
            if (accepted <= 0) return false
            buffer.position(buffer.position() + accepted * frameBytes)
            writtenFrames += accepted
            if (accepted < frames) return false
        }
        // A trailing partial frame cannot be played; drop it so the sink does not wait on it forever.
        if (buffer.hasRemaining()) buffer.position(buffer.limit())
        return true
    }

    override fun flush() {
        if (released) return
        NativeDirectStream.pause(handle)
        NativeDirectStream.flush(handle)
        writtenFrames = 0
        started = false
        playing = false
        clockMode = false
        clockAccumNs = 0
        clockStartNs = 0
        stopAtMs = 0
        positionAnnounced = false
    }

    override fun stop() {
        if (released) return
        if (stopAtMs == 0L) stopAtMs = SystemClock.elapsedRealtime()
        NativeDirectStream.stop(handle)
    }

    override fun release() {
        if (released) return
        released = true
        try { NativeDirectStream.close(handle) } catch (_: Throwable) { }
        DirectOutputState.active = false
        DirectOutputState.log("released")
        for (l in listeners) l.onReleased()
    }

    override fun setVolume(volume: Float) {
        // Bit-perfect: no software gain. The stream/hardware volume stays in charge.
    }

    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = sessionId
    override fun getSampleRate(): Int = sampleRateHz
    override fun getBufferSizeInFrames(): Long = bufferFrames.toLong()

    override fun getPositionUs(): Long {
        if (released) return 0L
        var frames = if (clockMode) -1L else NativeDirectStream.position(handle)
        if (!clockMode && playing && frames <= 0L && writtenFrames > 0 &&
            SystemClock.elapsedRealtime() - playingSinceMs > 2000L) {
            clockMode = true
            DirectOutputState.log("getPosition unavailable or stuck at 0; using the clock for the playback position")
        }
        if (clockMode || frames < 0L) {
            val ns = clockAccumNs + if (playing) System.nanoTime() - clockStartNs else 0L
            frames = ns * sampleRateHz / 1_000_000_000L
        }
        // After stop() everything written must be reported as played within the buffer duration, otherwise the
        // sink would wait for the end of the track forever.
        if (stopAtMs != 0L && frames < writtenFrames &&
            SystemClock.elapsedRealtime() - stopAtMs > bufferFrames * 1000L / sampleRateHz + 700L) {
            frames = writtenFrames
        }
        if (frames > writtenFrames) frames = writtenFrames
        if (!positionAnnounced && frames > 0 && playing) {
            positionAnnounced = true
            for (l in listeners) l.onPositionAdvancing(System.currentTimeMillis())
        }
        return frames * 1_000_000L / sampleRateHz
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled(): Boolean = false
    override fun addListener(listener: AudioOutput.Listener) { listeners.add(listener) }
    override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) { }
    override fun setOffloadEndOfStream() { }
    override fun attachAuxEffect(effectId: Int) { }
    override fun setAuxEffectSendLevel(level: Float) { }
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) { }

    /** Converts [frames] frames starting at the buffer position to stereo 24-bit packed little-endian in [outBuf]. */
    private fun convert(buffer: ByteBuffer, frames: Int): Int {
        var src = buffer.position()
        var o = 0
        val out = outBuf
        val mono = channels == 1
        for (i in 0 until frames) {
            for (c in 0 until 2) {
                val s = src + (if (mono) 0 else c) * inBytes
                when (inEncoding) {
                    C.ENCODING_PCM_16BIT -> {
                        out[o] = 0
                        out[o + 1] = buffer.get(s)
                        out[o + 2] = buffer.get(s + 1)
                    }
                    C.ENCODING_PCM_24BIT -> {
                        out[o] = buffer.get(s)
                        out[o + 1] = buffer.get(s + 1)
                        out[o + 2] = buffer.get(s + 2)
                    }
                    C.ENCODING_PCM_32BIT -> {
                        out[o] = buffer.get(s + 1)
                        out[o + 1] = buffer.get(s + 2)
                        out[o + 2] = buffer.get(s + 3)
                    }
                    else -> error("unsupported PCM encoding $inEncoding")
                }
                o += 3
            }
            src += channels * inBytes
        }
        return o
    }

    companion object {
        private const val CHUNK_FRAMES = 2048
        private const val BUFFER_MS = 400
        private const val WOULD_BLOCK = -11
        private const val DEAD_OBJECT = -32
        private const val FORMAT_PCM_24_BIT_PACKED = 6
        private const val FLAG_DIRECT = 1

        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            else -> 0
        }

        fun isEligible(encoding: Int, sampleRate: Int, channelCount: Int): Boolean =
            bytesPerSample(encoding) > 0 && sampleRate in 8000..192000 && (channelCount == 1 || channelCount == 2)

        fun describeInput(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_16BIT -> "PCM 16-bit padded to 24-bit"
            C.ENCODING_PCM_24BIT -> "PCM 24-bit"
            C.ENCODING_PCM_32BIT -> "PCM 32-bit (top 24 bits)"
            else -> "encoding $encoding"
        }

        /** Opens the native DIRECT track for this configuration, or returns null (the provider then reports an error; there is no other output). */
        fun create(config: AudioOutputProvider.OutputConfig): DirectAudioOutput? {
            val channels = if (config.channelMask == android.media.AudioFormat.CHANNEL_OUT_MONO) 1 else 2
            if (config.isTunneling || config.isOffload) return null
            if (!isEligible(config.encoding, config.sampleRate, channels)) return null
            if (!NativeDirectStream.ensureLoaded()) {
                DirectOutputState.log("native DIRECT unavailable: ${NativeDirectStream.loadError}")
                return null
            }
            // The previous track's DIRECT output may still be closing; the policy allows one DIRECT track at a time.
            var handle = NativeDirectStream.open(config.sampleRate, FORMAT_PCM_24_BIT_PACKED, FLAG_DIRECT, BUFFER_MS)
            var attempts = 0
            while (handle == 0L && attempts++ < 15) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                handle = NativeDirectStream.open(config.sampleRate, FORMAT_PCM_24_BIT_PACKED, FLAG_DIRECT, BUFFER_MS)
            }
            if (handle == 0L) {
                DirectOutputState.log("native DIRECT open failed (${config.sampleRate} Hz): ${NativeDirectStream.lastLog()}")
                return null
            }
            val bufferFrames = (config.sampleRate.toLong() * BUFFER_MS / 1000).toInt()
            val session = NativeDirectStream.session(handle)
            DirectOutputState.rate = config.sampleRate
            DirectOutputState.sourceLabel = describeInput(config.encoding)
            DirectOutputState.inputBits = bytesPerSample(config.encoding) * 8
            DirectOutputState.active = true
            DirectOutputState.route = NativeDirectStream.routeInfo(handle)
            DirectOutputState.log("opened ${config.sampleRate} Hz ${channels}ch from ${describeInput(config.encoding)} -> 24-bit packed DIRECT, buffer $bufferFrames frames, route: ${DirectOutputState.route}")
            return DirectAudioOutput(handle, config.sampleRate, channels, config.encoding, bytesPerSample(config.encoding),
                bufferFrames, session)
        }
    }
}
