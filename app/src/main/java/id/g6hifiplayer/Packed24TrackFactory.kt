package id.g6hifiplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Build 1.35: try to open the same client format that UAPP uses on the LG G6.
 * The audio_flinger dump shows UAPP's track as AUDIO_FORMAT_PCM_24_BIT_PACKED at the source
 * rate on a DIRECT output thread (direct_pcm profile) instead of the 48 kHz mixer.
 * Java encoding value 21 = ENCODING_PCM_24BIT_PACKED (hidden constant on Android 9).
 * Every attempt is logged so a rejection by the framework is visible in the diagnostics.
 */
internal object Packed24TrackFactory {
    const val ENCODING_PCM_24BIT_PACKED = 21

    fun create(sampleRate: Int, channels: Int, report: (String) -> Unit): AudioTrack? {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = try {
            AudioTrack.getMinBufferSize(sampleRate, mask, ENCODING_PCM_24BIT_PACKED)
        } catch (e: Throwable) { Int.MIN_VALUE }
        report("PACKED24_MINBUFFER rate=$sampleRate value=$minBytes")
        val frameBytes = channels * 3
        // About 250 ms, aligned to whole frames and never below the framework minimum.
        val bufferBytes = maxOf(if (minBytes > 0) minBytes * 2 else 0, sampleRate * frameBytes / 4)
            .let { it - it % frameBytes }

        // Attempt A: modern Builder path.
        try {
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .setEncoding(ENCODING_PCM_24BIT_PACKED)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                report("PACKED24_ATTEMPT=A_BUILDER result=OK bufferBytes=$bufferBytes")
                return track
            }
            report("PACKED24_ATTEMPT=A_BUILDER result=NOT_INITIALIZED state=${track.state}")
            track.release()
        } catch (e: Throwable) {
            report("PACKED24_ATTEMPT=A_BUILDER result=FAILED ${e.javaClass.simpleName}: ${e.message}")
        }

        // Attempt B: legacy constructor (different validation path on some OEM frameworks).
        try {
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, mask,
                ENCODING_PCM_24BIT_PACKED, bufferBytes, AudioTrack.MODE_STREAM
            )
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                report("PACKED24_ATTEMPT=B_LEGACY_CTOR result=OK bufferBytes=$bufferBytes")
                return track
            }
            report("PACKED24_ATTEMPT=B_LEGACY_CTOR result=NOT_INITIALIZED state=${track.state}")
            track.release()
        } catch (e: Throwable) {
            report("PACKED24_ATTEMPT=B_LEGACY_CTOR result=FAILED ${e.javaClass.simpleName}: ${e.message}")
        }

        // Build 1.37 attempts C/D: Android 9 rejects encoding 21 in AudioFormat.Builder.setEncoding(),
        // but the native layer knows AUDIO_FORMAT_PCM_24_BIT_PACKED (UAPP uses it). Lift the hidden-API
        // restriction, build a normal 16-bit AudioFormat, then overwrite its private mEncoding field.
        try {
            val ok = HiddenApiBypass.addHiddenApiExemptions("Landroid/media/")
            report("PACKED24_HIDDEN_API_EXEMPTION result=$ok")
        } catch (e: Throwable) {
            report("PACKED24_HIDDEN_API_EXEMPTION result=FAILED ${e.javaClass.simpleName}: ${e.message}")
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val forced = try {
            val base = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val field = AudioFormat::class.java.getDeclaredField("mEncoding")
            field.isAccessible = true
            field.setInt(base, ENCODING_PCM_24BIT_PACKED)
            report("PACKED24_FORCED_FORMAT encodingNow=${base.encoding} rate=${base.sampleRate}")
            base
        } catch (e: Throwable) {
            report("PACKED24_FORCED_FORMAT result=FAILED ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (forced != null) {
            // Attempt C: public 5-argument constructor with the patched format.
            try {
                val track = AudioTrack(attributes, forced, bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    report("PACKED24_ATTEMPT=C_FORCED_FORMAT_CTOR result=OK bufferBytes=$bufferBytes audioFormat=${track.audioFormat}")
                    return track
                }
                report("PACKED24_ATTEMPT=C_FORCED_FORMAT_CTOR result=NOT_INITIALIZED state=${track.state}")
                track.release()
            } catch (e: Throwable) {
                report("PACKED24_ATTEMPT=C_FORCED_FORMAT_CTOR result=FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
            // Attempt D: Builder with the patched format injected after setAudioFormat().
            try {
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                val bf = AudioTrack.Builder::class.java.getDeclaredField("mFormat")
                bf.isAccessible = true
                bf.set(builder, forced)
                val track = builder.build()
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    report("PACKED24_ATTEMPT=D_FORCED_FORMAT_BUILDER result=OK bufferBytes=$bufferBytes audioFormat=${track.audioFormat}")
                    return track
                }
                report("PACKED24_ATTEMPT=D_FORCED_FORMAT_BUILDER result=NOT_INITIALIZED state=${track.state}")
                track.release()
            } catch (e: Throwable) {
                report("PACKED24_ATTEMPT=D_FORCED_FORMAT_BUILDER result=FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        report("PACKED24_RESULT=UNAVAILABLE_FALLBACK_TO_FLOAT")
        return null
    }
}
