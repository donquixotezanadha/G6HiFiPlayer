package id.g6hifiplayer

import android.media.AudioFormat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider

/**
 * Build 1.61: the only audio output of the player. Integer PCM (16/24/32-bit, mono or stereo, 8-192 kHz) goes to the
 * native DIRECT track ([DirectAudioOutput]); anything else is refused and playback reports an error. There is no
 * fallback to the normal Android AudioTrack (that path goes through the 48 kHz mixer).
 *
 * Build 1.62: float PCM is accepted. Media3 sends 24-bit files as float (the sink's float output is ON; with it OFF the
 * sink cuts everything to 16-bit first). The float carries the 24-bit samples exactly and is converted back to 24-bit
 * integers in [DirectAudioOutput].
 */
@UnstableApi
internal class DirectAudioOutputProvider : AudioOutputProvider {

    private fun eligible(formatConfig: AudioOutputProvider.FormatConfig): Boolean {
        val f = formatConfig.format
        if (formatConfig.enableTunneling || formatConfig.enableOffload) return false
        if (f.sampleMimeType != MimeTypes.AUDIO_RAW) return false
        if (f.sampleRate <= 0 || f.channelCount <= 0) return false
        return DirectAudioOutput.isEligible(f.pcmEncoding, f.sampleRate, f.channelCount) && NativeDirectStream.ensureLoaded()
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        AudioOutputProvider.FormatSupport.UNSUPPORTED.buildUpon()
            .setFormatSupportLevel(if (eligible(formatConfig)) AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY else AudioOutputProvider.FORMAT_UNSUPPORTED)
            .build()

    @Throws(AudioOutputProvider.ConfigurationException::class)
    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (!eligible(formatConfig)) {
            val f = formatConfig.format
            throw AudioOutputProvider.ConfigurationException("not playable on the DIRECT output: ${f.sampleMimeType} pcmEncoding=${f.pcmEncoding} ${f.sampleRate} Hz ${f.channelCount} ch")
        }
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

    @Throws(AudioOutputProvider.InitializationException::class)
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = try {
            DirectAudioOutput.create(config)
        } catch (t: Throwable) {
            DirectOutputState.log("native DIRECT output failed: ${t.javaClass.simpleName}: ${t.message}")
            throw AudioOutputProvider.InitializationException(t)
        }
        if (output == null) {
            DirectOutputState.active = false
            throw AudioOutputProvider.InitializationException()
        }
        return output
    }

    override fun addListener(listener: AudioOutputProvider.Listener) { }
    override fun removeListener(listener: AudioOutputProvider.Listener) { }
    override fun release() { }
}
