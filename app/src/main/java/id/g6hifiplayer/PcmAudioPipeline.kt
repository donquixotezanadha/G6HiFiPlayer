package id.g6hifiplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Experimental PCM output stage for the future G6HiFiPlayer audio pipeline.
 *
 * Performs no DSP, EQ, gain adjustment, or sample-rate conversion. The caller supplies
 * already-decoded PCM frames and the format they were decoded to.
 *
 * AudioTrack may still route through Android AudioFlinger/HAL. Matching the requested format
 * does NOT prove bit-perfect delivery to the LG Quad DAC. This is not a verified bypass driver.
 */
internal class PcmAudioPipeline(context: Context) : AutoCloseable {
    data class PcmFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int
    ) {
        init {
            require(sampleRate in 8_000..384_000) { "Unsupported sample rate: $sampleRate" }
            require(channelCount == 1 || channelCount == 2) { "Only mono/stereo PCM is supported" }
            require(
                encoding == AudioFormat.ENCODING_PCM_16BIT ||
                    encoding == AudioFormat.ENCODING_PCM_FLOAT ||
                    (Build.VERSION.SDK_INT >= 31 &&
                        encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED)
            ) { "Unsupported PCM encoding: $encoding" }
        }

        val channelMask: Int
            get() = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO
                    else AudioFormat.CHANNEL_OUT_STEREO
    }

    data class OutputReport(
        val requestedSampleRate: Int,
        val requestedEncoding: Int,
        val requestedChannelCount: Int,
        val routedDeviceType: Int?,
        val routedDeviceName: String?,
        val audioTrackState: Int,
        val audioTrackPlayState: Int
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var track: AudioTrack? = null
    private var format: PcmFormat? = null

    @Synchronized
    fun open(pcmFormat: PcmFormat, preferWiredHeadphones: Boolean = true) {
        closeTrack()
        val minBufferBytes = AudioTrack.getMinBufferSize(
            pcmFormat.sampleRate, pcmFormat.channelMask, pcmFormat.encoding
        )
        check(minBufferBytes > 0) {
            "AudioTrack does not support " + pcmFormat.sampleRate + " Hz / encoding " +
                pcmFormat.encoding + " for " + pcmFormat.channelCount +
                " channel(s) (error " + minBufferBytes + ")"
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(pcmFormat.sampleRate)
                    .setChannelMask(pcmFormat.channelMask)
                    .setEncoding(pcmFormat.encoding)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferBytes * 4)
            .build()

        check(audioTrack.state == AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            "AudioTrack initialization failed for requested PCM format"
        }
        if (preferWiredHeadphones) findWiredOutput()?.let { audioTrack.setPreferredDevice(it) }
        track = audioTrack
        format = pcmFormat
    }

    /** Writes correctly packed PCM bytes unchanged; no conversion is performed here. */
    fun write(pcmBytes: ByteArray, offset: Int = 0, size: Int = pcmBytes.size): Int {
        require(offset >= 0 && size >= 0 && offset + size <= pcmBytes.size) {
            "Invalid PCM byte range"
        }
        return checkNotNull(track) { "Open the PCM pipeline before writing" }
            .write(pcmBytes, offset, size, AudioTrack.WRITE_BLOCKING)
    }

    fun play() {
        checkNotNull(track) { "Open the PCM pipeline before playback" }.play()
    }

    fun pause() { track?.pause() }

    fun flush() {
        track?.pause()
        track?.flush()
    }

    fun report(): OutputReport? {
        val activeTrack = track ?: return null
        val activeFormat = format ?: return null
        val device = if (Build.VERSION.SDK_INT >= 23) activeTrack.routedDevice else null
        return OutputReport(
            requestedSampleRate = activeFormat.sampleRate,
            requestedEncoding = activeFormat.encoding,
            requestedChannelCount = activeFormat.channelCount,
            routedDeviceType = device?.type,
            routedDeviceName = device?.productName?.toString(),
            audioTrackState = activeTrack.state,
            audioTrackPlayState = activeTrack.playState
        )
    }

    override fun close() { closeTrack() }

    @Synchronized
    private fun closeTrack() {
        val old = track
        track = null
        format = null
        if (old != null) {
            try {
                old.pause()
                old.flush()
                old.stop()
            } catch (_: IllegalStateException) {
                // Track may already be stopped or not yet started.
            } finally {
                old.release()
            }
        }
    }

    private fun findWiredOutput(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 23) return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
    }
}
