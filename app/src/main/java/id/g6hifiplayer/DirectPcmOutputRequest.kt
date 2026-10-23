package id.g6hifiplayer

import android.media.AudioFormat
import android.media.AudioTrack
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Build 1.32 experiment: inspect Android/OEM PCM encoding availability before interpreting
 * an AudioTrack request as compatible with LG's direct_pcm_24 profile.
 *
 * A public AudioTrack.Builder has no standard AUDIO_OUTPUT_FLAG_DIRECT setter. An OEM hook,
 * if present, is probed reflectively, but only AudioFlinger/HAL evidence can confirm the result.
 */
internal object DirectPcmOutputRequest {
    private const val DIRECT_FLAG = 0x1

    fun encodingCapabilityReport(sampleRate: Int, channelMask: Int): List<String> {
        val candidates = listOf(
            "ENCODING_PCM_16BIT",
            "ENCODING_PCM_FLOAT",
            "ENCODING_PCM_8_24BIT",
            "ENCODING_PCM_24BIT_PACKED",
            "ENCODING_PCM_32BIT"
        )
        return candidates.map { name ->
            val encoding = try {
                AudioFormat::class.java.getField(name).getInt(null)
            } catch (_: Throwable) {
                null
            }
            if (encoding == null) {
                "PCM_ENCODING_CAPABILITY name=$name available=false api=${android.os.Build.VERSION.SDK_INT}"
            } else {
                val minBytes = try {
                    AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
                } catch (error: Throwable) {
                    Int.MIN_VALUE
                }
                "PCM_ENCODING_CAPABILITY name=$name available=true value=0x${encoding.toString(16)} minBufferBytes=$minBytes rate=$sampleRate"
            }
        } + listOf(
            "DIRECT_PROFILE_NOTE public_AudioTrack_Builder_has_no_standard_DIRECT_flag_API=true",
            "DIRECT_PROFILE_NOTE getMinBufferSize_is_only_a_format_capability_probe_not_proof_of_output_open_or_DIRECT=true"
        )
    }

    fun apply(builder: AudioTrack.Builder): String {
        val methods = try {
            (builder.javaClass.methods.asList() + declaredMethods(builder.javaClass))
                .distinctBy { it.toGenericString() }
        } catch (error: Throwable) {
            return "DIRECT_REQUEST=INSPECTION_FAILED stage=methods error=${error.javaClass.simpleName}"
        }

        val setterNames = setOf("setOutputFlags", "setAudioOutputFlags", "setAudioTrackFlags", "setFlags")
        val setter = methods.firstOrNull {
            it.name in setterNames && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                    it.parameterTypes[0] == Int::class.javaObjectType)
        }
        if (setter != null) {
            try {
                setter.isAccessible = true
                setter.invoke(builder, DIRECT_FLAG)
                return "DIRECT_REQUEST=OEM_HOOK_INVOKED hook=method:${setter.name} flag=0x1 verification=AudioFlinger_required"
            } catch (_: Throwable) {
                // Continue to field probing; the setter may be blocked by framework access rules.
            }
        }

        val fieldNames = setOf("mOutputFlags", "mAudioOutputFlags", "mFlags")
        val field = try {
            declaredFields(builder.javaClass).firstOrNull {
                it.name in fieldNames && it.type == Int::class.javaPrimitiveType
            }
        } catch (error: Throwable) {
            return "DIRECT_REQUEST=INSPECTION_FAILED stage=fields error=${error.javaClass.simpleName}"
        }
        if (field != null) {
            try {
                field.isAccessible = true
                val old = field.getInt(builder)
                field.setInt(builder, old or DIRECT_FLAG)
                return "DIRECT_REQUEST=OEM_HOOK_INVOKED hook=field:${field.name} previous=0x${old.toString(16)} requested=0x${(old or DIRECT_FLAG).toString(16)} verification=AudioFlinger_required"
            } catch (error: Throwable) {
                return "DIRECT_REQUEST=OEM_HOOK_BLOCKED hook=field:${field.name} error=${error.javaClass.simpleName}; fallback=normal_AudioTrack"
            }
        }

        val interestingMethods = methods
            .filter { it.name.contains("output", true) || it.name.contains("flag", true) || it.name.contains("performance", true) }
            .map { it.name }
            .distinct()
            .sorted()
            .joinToString(",")
            .ifEmpty { "none" }

        return "DIRECT_REQUEST=UNAVAILABLE_NO_OEM_HOOK api=${android.os.Build.VERSION.SDK_INT} framework=public_AudioTrack_Builder_has_no_DIRECT_flag_API inspectedRelevantMethods=$interestingMethods fallback=normal_AudioTrack"
    }

    private fun declaredMethods(type: Class<*>): List<Method> {
        val result = mutableListOf<Method>()
        var current: Class<*>? = type
        while (current != null) {
            result += current.declaredMethods
            current = current.superclass
        }
        return result
    }

    private fun declaredFields(type: Class<*>): List<Field> {
        val result = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null) {
            result += current.declaredFields
            current = current.superclass
        }
        return result
    }
}
