package id.g6hifiplayer

import android.media.AudioTrack
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Keeps a reference to the AudioTrack that Media3 actually created for playback.
 * This allows route/output diagnostics to inspect the real track instead of guessing
 * from AudioManager's list of connected devices.
 */
@UnstableApi
internal class TrackingAudioOutputProvider(
    delegate: AudioOutputProvider,
    private val onTrackCreated: (AudioTrack) -> Unit
) : ForwardingAudioOutputProvider(delegate) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        if (output is AudioTrackAudioOutput) {
            onTrackCreated(output.audioTrack)
        }
        return output
    }
}
