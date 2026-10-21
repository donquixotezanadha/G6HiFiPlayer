package id.g6hifiplayer

import android.media.AudioTrack
import android.util.Log
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Experimental OEM-specific DIRECT PCM request.
 *
 * Android's public AudioTrack.Builder API does not expose AUDIO_OUTPUT_FLAG_DIRECT.
 * This probes only OEM-added hooks/private builder fields. A successful request is NOT
 * proof that AudioPolicy selected a DIRECT output thread or that the LG Quad DAC is active.
 * If no usable OEM hook exists, the normal Media3 output remains unchanged.
 */
internal object DirectPcmRequest {
    private const val TAG = "G6DirectPcm"
    private const val AUDIO_OUTPUT_FLAG_DIRECT = 0x1

    @Volatile private var requestState: String = "NOT_ATTEMPTED"
    @Volatile private var requestedRate: Int = 0
    @Volatile private var requestedEncoding: Int = 0
    @Volatile private var hookUsed: String = "none"

    fun statusSummary(): String {
        val format = if (requestedRate > 0) "${requestedRate}Hz/encoding=${requestedEncoding}" else "format not captured"
        return "$requestState hook=$hookUsed ($format); request status only, not proof of active DIRECT output"
    }

    fun applyIfSupported(builder: AudioTrack.Builder, config: AudioOutputProvider.OutputConfig) {
        requestedRate = config.sampleRate
        requestedEncoding = config.encoding
        hookUsed = "none"
        requestState = "CHECKING_OEM_HOOK"

        val methods = try {
            (builder.javaClass.methods.asList() + allDeclaredMethods(builder.javaClass))
                .distinctBy { it.toGenericString() }
        } catch (e: Exception) {
            requestState = "OEM_HOOK_INSPECTION_FAILED"
            Log.w(TAG, "DIRECT_PCM_REQUEST status=$requestState rate=${config.sampleRate} encoding=${config.encoding}", e)
            return
        }

        val methodNames = setOf("setFlags", "setOutputFlags", "setAudioOutputFlags", "setAudioTrackFlags")
        val candidate = methods.firstOrNull {
            it.name in methodNames && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                    it.parameterTypes[0] == Int::class.javaObjectType)
        }

        if (candidate != null) {
            try {
                candidate.isAccessible = true
                candidate.invoke(builder, AUDIO_OUTPUT_FLAG_DIRECT)
                hookUsed = "method:${candidate.name}"
                requestState = "OEM_REQUEST_INVOKED_UNVERIFIED"
                logRequest(config, "OEM method invoked; active output mode remains unverified")
                return
            } catch (e: Exception) {
                Log.w(TAG, "DIRECT_PCM_REQUEST method=${candidate.name} failed; probing OEM fields", e)
            }
        }

        // Some vendor-modified frameworks expose no setter but retain an integer output
        // flags field in the builder. Only try clearly named flags fields on this builder.
        val fieldNames = setOf("mOutputFlags", "mAudioOutputFlags", "mFlags")
        val field = try { allDeclaredFields(builder.javaClass).firstOrNull {
            it.name in fieldNames && it.type == Int::class.javaPrimitiveType
        } } catch (e: Exception) {
            Log.w(TAG, "DIRECT_PCM_REQUEST field inspection failed", e)
            null
        }

        if (field != null) {
            try {
                field.isAccessible = true
                field.setInt(builder, AUDIO_OUTPUT_FLAG_DIRECT)
                hookUsed = "field:${field.name}"
                requestState = "OEM_REQUEST_INVOKED_UNVERIFIED"
                logRequest(config, "OEM builder field set; active output mode remains unverified")
                return
            } catch (e: Exception) {
                Log.w(TAG, "DIRECT_PCM_REQUEST field=${field.name} failed", e)
            }
        }

        requestState = "UNAVAILABLE_NO_OEM_DIRECT_HOOK"
        Log.w(
            TAG,
            "DIRECT_PCM_REQUEST status=$requestState rate=${config.sampleRate} encoding=${config.encoding}; " +
                "Android public AudioTrack.Builder has no DIRECT output-flag API; retaining normal Media3 output"
        )
    }

    private fun logRequest(config: AudioOutputProvider.OutputConfig, detail: String) {
        Log.i(
            TAG,
            "DIRECT_PCM_REQUEST status=$requestState hook=$hookUsed flags=0x1 rate=${config.sampleRate} " +
                "encoding=${config.encoding}; $detail"
        )
    }

    private fun allDeclaredMethods(type: Class<*>): List<Method> {
        val result = mutableListOf<Method>()
        var current: Class<*>? = type
        while (current != null) {
            result += current.declaredMethods
            current = current.superclass
        }
        return result
    }

    private fun allDeclaredFields(type: Class<*>): List<Field> {
        val result = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null) {
            result += current.declaredFields
            current = current.superclass
        }
        return result
    }
}
