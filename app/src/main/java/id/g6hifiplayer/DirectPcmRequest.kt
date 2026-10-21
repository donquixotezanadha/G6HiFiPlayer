package id.g6hifiplayer

import android.media.AudioTrack
import android.util.Log
import androidx.media3.exoplayer.audio.AudioOutputProvider

/**
 * Experimental hook for requesting AUDIO_OUTPUT_FLAG_DIRECT on devices whose
 * AudioTrack.Builder exposes a compatible OEM method. A normal Android public
 * AudioTrack.Builder does not expose this flag; in that case we deliberately
 * leave the builder untouched so the stable Media3 fallback continues to work.
 */
internal object DirectPcmRequest {
    private const val TAG = "G6DirectPcm"

    fun applyIfSupported(builder: AudioTrack.Builder, config: AudioOutputProvider.OutputConfig) {
        val method = try {
            (builder.javaClass.methods.asList() + builder.javaClass.declaredMethods.asList())
                .distinctBy { it.toGenericString() }
                .firstOrNull {
                    it.name == "setFlags" &&
                        it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                            it.parameterTypes[0] == Int::class.javaObjectType)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot inspect AudioTrack.Builder for OEM DIRECT hook", e)
            null
        }

        if (method == null) {
            Log.w(
                TAG,
                "DIRECT request unavailable: AudioTrack.Builder exposes no setFlags(int) hook; " +
                    "using normal Media3 AudioTrack path. format=" + config.sampleRate + "Hz/" +
                    "encoding=" + config.encoding + ". This is not DIRECT PCM."
            )
            return
        }

        try {
            method.isAccessible = true
            method.invoke(builder, 0x1) // AUDIO_OUTPUT_FLAG_DIRECT in Android audio_output_flags_t
            Log.i(
                TAG,
                "OEM setFlags(0x1) invoked for DIRECT request; request only, actual route still unverified. " +
                    "format=" + config.sampleRate + "Hz/encoding=" + config.encoding
            )
        } catch (e: Exception) {
            Log.w(TAG, "OEM DIRECT request failed; keeping Media3's normal output behavior", e)
        }
    }
}
