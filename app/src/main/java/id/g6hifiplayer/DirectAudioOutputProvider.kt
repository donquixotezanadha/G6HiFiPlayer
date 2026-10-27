package id.g6hifiplayer

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Build 1.60: routes eligible PCM (mono/stereo 16/24/32-bit int or float, 8-192 kHz) to the native DIRECT output
 * ([DirectAudioOutput]). Everything else, and any failure to open the native track, goes to the delegate
 * (the normal Media3 AudioTrack output), so playback never breaks because of this experiment.
 */
@UnstableApi
internal class DirectAudioOutputProvider(
    delegate: AudioOutputProvider,
    private val enabled: () -> Boolean
) : ForwardingAudioOutputProvider(delegate) {

    private fun wantsDirect(formatConfig: AudioOutputProvider.FormatConfig): Boolean {
        val f = formatConfig.format
        if (!enabled()) return false
        if (formatConfig.enableTunneling || formatConfig.enableOffload) return false
        if (f.sampleMimeType != MimeTypes.AUDIO_RAW) return false
        if (f.sampleRate <= 0 || f.channelCount <= 0) return false
        if (!DirectAudioOutput.isEligible(f.pcmEncoding, f.sampleRate, f.channelCount)) return false
        return NativeDirectStream.ensureLoaded()
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        if (wantsDirect(formatConfig)) {
            return super.getFormatSupport(formatConfig).buildUpon()
                .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
                .build()
        }
        return super.getFormatSupport(formatConfig)
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (wantsDirect(formatConfig)) {
            val f = formatConfig.format
            val mask = if (f.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val frameBytes = f.channelCount * DirectAudioOutput.bytesPerSample(f.pcmEncoding)
            return AudioOutputProvider.OutputConfig.Builder()
                .setEncoding(f.pcmEncoding)
                .setSampleRate(f.sampleRate)
                .setChannelMask(mask)
                .setIsTunneling(false)
                .setIsOffload(false)
                .setBufferSize(f.sampleRate * frameBytes * 2 / 5)
                .setAudioAttributes(formatConfig.audioAttributes)
                .setAudioSessionId(formatConfig.audioSessionId)
                .setUsePlaybackParameters(false)
                .build()
        }
        return super.getOutputConfig(formatConfig)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        if (enabled()) {
            try {
                DirectAudioOutput.create(config)?.let { return it }
            } catch (t: Throwable) {
                DirectOutputState.log("native DIRECT failed, using the normal AudioTrack: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        DirectOutputState.active = false
        return super.getAudioOutput(config)
    }
}
