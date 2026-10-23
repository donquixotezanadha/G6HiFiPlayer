package id.g6hifiplayer

import android.media.AudioTrack
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Build 1.31 experiment: request AUDIO_OUTPUT_FLAG_DIRECT only through a clearly exposed
 * OEM-specific AudioTrack.Builder hook. A request is never treated as proof of a DIRECT track.
 * If the stock Android framework exposes no hook, the normal AudioTrack path is preserved.
 */
internal object DirectPcmOutputRequest {
    private const val DIRECT_FLAG = 0x1

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
            } catch (error: Throwable) {
                // Continue to field probing; this setter may exist but be blocked by framework access rules.
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

        return "DIRECT_REQUEST=UNAVAILABLE_NO_OEM_HOOK api=${android.os.Build.VERSION.SDK_INT} framework=public_AudioTrack_Builder_has_no_DIRECT_flag_API fallback=normal_AudioTrack"
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
