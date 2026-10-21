package id.g6hifiplayer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Experimental raw-PCM AudioTrack backend for Build 107.
 *
 * This is deliberately isolated from the existing Media3 playback path until
 * decoded PCM can be routed here end-to-end. AudioAttributes flags are NOT
 * equivalent to AudioPolicy AUDIO_OUTPUT_FLAG_DIRECT, and successful
 * AudioTrack construction does not prove DIRECT/HAL/Quad-DAC operation.
 */
class G6AudioEngine(private val context: Context) {
    private var audioTrack: AudioTrack? = null
    private val tag = "G6HiFiEngine"

    @Synchronized
    fun initBitPerfectAndHighImpedanceTrack(
        sampleRate: Int,
        channels: Int,
        is24Bit: Boolean
    ): Boolean {
        release()
        if (sampleRate <= 0 || (channels != 1 && channels != 2)) {
            Log.e(tag, "Invalid PCM format: rate=$sampleRate channels=$channels")
            return false
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                // Vendor-specific keys are best-effort hints only. They are not
                // documented Android APIs and may be ignored by this firmware.
                audioManager.setParameters("cal_anc_enable=false;hifi_mode=1;ext_earphone_impedance=high")
                Log.i(tag, "Submitted best-effort LG vendor parameters; hardware mode is unverified.")
            } catch (e: Exception) {
                Log.w(tag, "Vendor audio parameters rejected/unsupported: ${e.message}")
            }

            val encoding = if (is24Bit) {
                if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_24BIT_PACKED
                else {
                    Log.w(tag, "Packed 24-bit PCM encoding is unavailable on Android API ${Build.VERSION.SDK_INT}; using PCM 16-bit.")
                    AudioFormat.ENCODING_PCM_16BIT
                }
            } else AudioFormat.ENCODING_PCM_16BIT

            val channelConfig = if (channels == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()
            val attributesBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)

            // This reproduces the proposed experiment, but setFlags accepts
            // AudioAttributes flags, not AudioPolicy output flags. Do not treat
            // this as proof that AUDIO_OUTPUT_FLAG_DIRECT was selected.
            try {
                val method = AudioAttributes.Builder::class.java.getMethod(
                    "setFlags", Int::class.javaPrimitiveType
                )
                method.invoke(attributesBuilder, 0x2000 or 0x1)
                Log.i(tag, "Submitted experimental AudioAttributes flags; DIRECT remains unconfirmed.")
            } catch (e: Exception) {
                Log.w(tag, "Could not set experimental AudioAttributes flags: ${e.message}")
            }

            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            if (minBuffer <= 0) {
                Log.e(tag, "getMinBufferSize failed: result=$minBuffer rate=$sampleRate encoding=$encoding")
                return false
            }
            val bufferBytes = maxOf(minBuffer * 2, sampleRate * channels * 2)
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributesBuilder.build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (created.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(tag, "AudioTrack initialization failed: state=${created.state}")
                created.release()
                return false
            }
            audioTrack = created
            Log.i(tag, "AudioTrack initialized: rate=${created.sampleRate} encoding=$encoding channels=$channels bufferBytes=$bufferBytes. DIRECT/HAL/DAC are not verified.")
            return true
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack initialization exception", e)
            release()
            return false
        }
    }

    /** Writes already-decoded PCM bytes; this class does not decode media files. */
    @Synchronized
    fun writePcm(data: ByteArray, offset: Int = 0, size: Int = data.size): Int {
        val track = audioTrack ?: return AudioTrack.ERROR_INVALID_OPERATION
        if (offset < 0 || size < 0 || offset + size > data.size) return AudioTrack.ERROR_BAD_VALUE
        return try {
            track.write(data, offset, size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.e(tag, "PCM write failed", e)
            AudioTrack.ERROR_INVALID_OPERATION
        }
    }

    @Synchronized
    fun play() {
        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack play failed", e)
        }
    }

    @Synchronized
    fun pause() {
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.e(tag, "AudioTrack pause failed", e)
        }
    }

    @Synchronized
    fun release() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            } catch (e: Exception) {
                Log.w(tag, "AudioTrack stop during release failed: ${e.message}")
            }
            try {
                track.release()
            } catch (e: Exception) {
                Log.w(tag, "AudioTrack release failed: ${e.message}")
            }
        }
    }
}
