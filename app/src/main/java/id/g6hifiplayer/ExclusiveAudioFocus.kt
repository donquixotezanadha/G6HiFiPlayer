package id.g6hifiplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Build 1.61: holds EXCLUSIVE audio focus while the player is playing (AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: the system
 * does not play notification sounds, and other media apps are told to stop). The player has no software volume, so a
 * "duck" request from another app is ignored on purpose: the music is never turned down or mixed into by the app side.
 * A real takeover (phone call, alarm, another player that insists) pauses playback; it resumes by itself after a
 * transient loss and stays paused after a permanent one.
 */
internal object ExclusiveAudioFocus {
    private var request: AudioFocusRequest? = null
    private var holding = false
    private var resumeOnGain = false
    private var pausedByUs = false
    private var attachedTo: ExoPlayer? = null
    private var manager: AudioManager? = null

    fun attach(context: Context, player: ExoPlayer) {
        if (attachedTo === player) return
        attachedTo = player
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager = audioManager
        val handler = Handler(Looper.getMainLooper())
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val changeListener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnGain = false
                    pausedByUs = true
                    player.pause()
                    pausedByUs = false
                    abandon(audioManager)
                    DirectOutputState.log("audio focus lost for good: paused")
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (player.playWhenReady) {
                        resumeOnGain = true
                        pausedByUs = true
                        player.pause()
                        pausedByUs = false
                        DirectOutputState.log("audio focus lost for a moment (call/alarm): paused")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // No software volume in this player: keep playing at full level, untouched.
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (resumeOnGain) {
                        resumeOnGain = false
                        player.play()
                        DirectOutputState.log("audio focus back: resumed")
                    }
                }
            }
        }

        fun ensureRequest(): AudioFocusRequest = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(changeListener, handler)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .build().also { request = it }

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    if (!holding) {
                        val result = audioManager.requestAudioFocus(ensureRequest())
                        holding = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                        DirectOutputState.log("exclusive audio focus ${if (holding) "granted" else "refused ($result)"}")
                        if (!holding) player.pause()
                    }
                } else if (!pausedByUs) {
                    resumeOnGain = false
                    abandon(audioManager)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    resumeOnGain = false
                    abandon(audioManager)
                }
            }
        })
    }

    /** Called when the player is released (Close), so the focus never outlives the player. */
    fun release() {
        resumeOnGain = false
        manager?.let { abandon(it) }
        attachedTo = null
    }

    private fun abandon(audioManager: AudioManager) {
        val r = request ?: return
        if (holding) {
            try { audioManager.abandonAudioFocusRequest(r) } catch (_: Exception) { }
            holding = false
        }
    }
}
