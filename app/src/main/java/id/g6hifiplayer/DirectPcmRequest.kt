package id.g6hifiplayer

import android.media.AudioTrack
import android.util.Log
import androidx.media3.exoplayer.audio.AudioOutputProvider

/**
 * Requests DIRECT only through an OEM-specific AudioTrack.Builder hook, when present.
 *
 * Stock Android's public AudioTrack.Builder does not expose AUDIO_OUTPUT_FLAG_DIRECT.
 * Never treat a successful reflective call as proof that AudioPolicy selected a direct
 * output thread or that the LG Quad DAC backend is active.
 */
internal object DirectPcmRequest {
    private const val TAG = "G6DirectPcm"
    private const val AUDIO_OUTPUT_FLAG_DIRECT = 0x1

    @Volatile private var requestState: String = "NOT_ATTEMPTED"
    @Volatile private var requestedRate: Int = 0
    @Volatile private var requestedEncoding: Int = 0

    fun statusSummary(): String {
        val format = if (requestedRate > 0) "${requestedRate}Hz/encoding=${requestedEncoding}" else "format not captured"
        return "$requestState ($format); request status only, not proof of active DIRECT output"
    }

    fun applyIfSupported(builder: AudioTrack.Builder, config: AudioOutputProvider.OutputConfig) {
        requestedRate = config.sampleRate
        requestedEncoding = config.encoding
        requestState = "CHECKING_OEM_HOOK"

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
            requestState = "OEM_HOOK_INSPECTION_FAILED"
            Log.w(TAG, "DIRECT_PCM_REQUEST status=$requestState rate=${config.sampleRate} encoding=${config.encoding}", e)
            return
        }

        if (method == null) {
            requestState = "UNAVAILABLE_NO_OEM_SETFLAGS"
            Log.w(
                TAG,
                "DIRECT_PCM_REQUEST status=$requestState rate=${config.sampleRate} encoding=${config.encoding}; " +
                    "stock public AudioTrack.Builder has no setFlags(int); retaining normal Media3 output"
            )
            return
        }

        try {
            method.isAccessible = true
            method.invoke(builder, AUDIO_OUTPUT_FLAG_DIRECT)
            requestState = "OEM_REQUEST_INVOKED_UNVERIFIED"
            Log.i(
                TAG,
                "DIRECT_PCM_REQUEST status=$requestState flags=0x1 rate=${config.sampleRate} encoding=${config.encoding}; " +
                    "this is a request only; active output mode and DAC backend remain unverified"
            )
        } catch (e: Exception) {
            requestState = "OEM_REQUEST_FAILED"
            Log.w(TAG, "DIRECT_PCM_REQUEST status=$requestState rate=${config.sampleRate} encoding=${config.encoding}; normal Media3 fallback retained", e)
        }
    }
}
