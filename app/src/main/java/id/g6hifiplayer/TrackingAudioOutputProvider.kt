package id.g6hifiplayer

import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Captures the AudioTrack created by Media3 and logs the format Android actually
 * configured. This is diagnostic evidence only; it does not claim a HAL/direct-DAC bypass.
 */
@UnstableApi
internal class TrackingAudioOutputProvider(
    delegate: AudioOutputProvider,
    private val onTrackCreated: (AudioTrack) -> Unit
) : ForwardingAudioOutputProvider(delegate) {

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        if (output is AudioTrackAudioOutput) {
            val track = output.audioTrack
            logTrackConfiguration(track)
            onTrackCreated(track)
        }
        return output
    }

    private fun logTrackConfiguration(track: AudioTrack) {
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                track.routedDevice?.let { device ->
                    "type=${device.type}, name=${device.productName}"
                } ?: "unconfirmed"
            } catch (_: Exception) {
                "unavailable"
            }
        } else {
            "API<23"
        }

        Log.i(
            TAG,
            "AudioTrack created: state=${track.state}, sampleRate=${track.sampleRate}Hz, " +
                "encoding=${track.audioFormat}, channelCount=${track.channelCount}, " +
                "bufferFrames=${track.bufferSizeInFrames}, sessionId=${track.audioSessionId}, " +
                "routedDevice=$routed. Requested format / route does not prove bit-perfect or direct DAC."
        )
    }

    private companion object {
        const val TAG = "G6AudioTrackDiag"
    }
}
