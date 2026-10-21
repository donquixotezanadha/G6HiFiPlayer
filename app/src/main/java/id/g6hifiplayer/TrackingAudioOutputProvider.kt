package id.g6hifiplayer

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Observes the AudioTrack Media3 actually uses. This does not claim DIRECT output:
 * Android's public AudioTrack API does not expose the selected AudioPolicy output flags.
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
            onTrackCreated(track)
            logTrack("created", track)

            if (android.os.Build.VERSION.SDK_INT >= 23) {
                try {
                    track.addOnRoutingChangedListener(
                        AudioRouting.OnRoutingChangedListener { routing ->
                            if (routing is AudioTrack) logTrack("route-changed", routing)
                        },
                        Handler(Looper.getMainLooper())
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to observe AudioTrack routing", error)
                }
            }
        }
        return output
    }

    private fun logTrack(event: String, track: AudioTrack) {
        val route = try {
            if (android.os.Build.VERSION.SDK_INT >= 23) track.routedDevice else null
        } catch (_: Exception) { null }
        val routeType = when (route?.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            null -> "UNCONFIRMED"
            else -> "TYPE_${route.type}"
        }
        val bufferFrames = try { track.bufferSizeInFrames } catch (_: Exception) { -1 }
        val underruns = try {
            if (android.os.Build.VERSION.SDK_INT >= 24) track.underrunCount else -1
        } catch (_: Exception) { -1 }
        Log.i(TAG, "AUDIO_ENGINE event=$event sampleRate=${track.sampleRate} " +
            "state=${track.state} playState=${track.playState} session=${track.audioSessionId} " +
            "bufferFrames=$bufferFrames underruns=$underruns route=$routeType " +
            "routeName=${route?.productName ?: "unknown"} " +
            "routeId=${route?.id ?: -1}. DIRECT/HAL format are not exposed by this public API.")
    }

    private companion object {
        const val TAG = "G6AudioEngine"
    }
}
