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
 * Build 1.61/1.62: holds EXCLUSIVE audio focus while the player is playing (AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: the
 * system does not play notification sounds, and other media apps are told to stop). The player has no software volume,
 * so a "duck" request from another app is ignored on purpose: the music is never turned down or mixed into by the app
 * side. A real takeover (phone call, alarm, another player that insists) pauses playback; it resumes by itself after a
 * transient loss and stays paused after a permanent one.
 *
 * Build 1.62 fixes: the focus is no longer given back while a track change passes through IDLE with playback still
 * wanted, it is asked for again when playback becomes active without it, and a pause caused by a focus loss can no
 * longer be mistaken for the user's own pause (which used to cancel the automatic resume).
 *
 * Build 1.66 EXCLUSIVE MODE ("takeover"): confirmed by the user from the notification that appears when the headphone jack
 * is plugged in. While it is on, the focus is held even when nothing is playing (so other apps and system sounds stay
 * away from the jack until the jack is unplugged or the mode is released), and when another app takes the focus for good
 * the player asks for it back instead of pausing (limited to 4 times in 20 s, so two stubborn apps cannot fight forever).
 * Phone calls and alarms (transient focus losses) still pause the music and it resumes afterwards.
 */
internal object ExclusiveAudioFocus {
    private var request: AudioFocusRequest? = null
    private var holding = false
    private var pausedByFocus = false
    private var attachedTo: ExoPlayer? = null
    private var manager: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reclaimTimes = ArrayDeque<Long>()

    /** Exclusive mode confirmed by the user and not released yet. */
    @Volatile var takeover = false
        private set

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val changeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val player = attachedTo ?: return@OnAudioFocusChangeListener
        val audioManager = manager ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (takeover && reclaimAllowed()) {
                    // Another app took the focus for good: exclusive mode asks for it back (the music keeps playing).
                    holding = false
                    DirectOutputState.log("exclusive mode: another app took the audio focus, asking for it back")
                    handler.postDelayed({ if (takeover) ensureFocus() }, 1200L)
                } else {
                    pausedByFocus = false
                    player.pause()
                    abandon(audioManager)
                    DirectOutputState.log("audio focus lost for good: paused")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player.playWhenReady) {
                    pausedByFocus = true
                    player.pause()
                    DirectOutputState.log("audio focus lost for a moment (call/alarm): paused")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // No software volume in this player: keep playing at full level, untouched.
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocus) {
                    pausedByFocus = false
                    player.play()
                    DirectOutputState.log("audio focus back: resumed")
                }
            }
        }
    }

    private fun reclaimAllowed(): Boolean {
        val now = System.currentTimeMillis()
        while (reclaimTimes.isNotEmpty() && now - reclaimTimes.first() > 20_000L) reclaimTimes.removeFirst()
        if (reclaimTimes.size >= 4) return false
        reclaimTimes.addLast(now)
        return true
    }

    private fun ensureRequest(): AudioFocusRequest = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(changeListener, handler)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(false)
        .build().also { request = it }

    private fun ensureFocus() {
        val audioManager = manager ?: return
        if (holding) return
        val result = audioManager.requestAudioFocus(ensureRequest())
        holding = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DirectOutputState.log("exclusive audio focus ${if (holding) "granted" else "refused ($result)"}")
        if (!holding && attachedTo?.playWhenReady == true) attachedTo?.pause()
    }

    fun attach(context: Context, player: ExoPlayer) {
        if (attachedTo === player) return
        attachedTo = player
        manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioManager = manager!!

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    pausedByFocus = false
                    ensureFocus()
                } else if (!pausedByFocus && !takeover) {
                    abandon(audioManager)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        pausedByFocus = false
                        if (!takeover) abandon(audioManager)
                    }
                    Player.STATE_IDLE -> {
                        // A track change or a new queue passes through IDLE while playback is still wanted: keep the focus.
                        if (!player.playWhenReady) {
                            pausedByFocus = false
                            if (!takeover) abandon(audioManager)
                        }
                    }
                    Player.STATE_BUFFERING, Player.STATE_READY -> {
                        if (player.playWhenReady && !holding && !pausedByFocus) ensureFocus()
                    }
                }
            }
        })
    }

    /** Build 1.66: exclusive mode on. Holds the focus now, playing or not. Creates the shared player if it does not exist yet. */
    fun startTakeover(context: Context) {
        PlaybackKeepAliveService.getOrCreatePlayer(context.applicationContext) // attaches this object to the player
        takeover = true
        reclaimTimes.clear()
        ensureFocus()
        DirectOutputState.log("exclusive mode ON (focus held until the jack is unplugged or the mode is released)")
    }

    /** Build 1.66: exclusive mode off. The focus is kept only if the music is still playing. */
    fun stopTakeover() {
        if (!takeover) return
        takeover = false
        handler.removeCallbacksAndMessages(null)
        val playing = attachedTo?.let { it.playWhenReady && !pausedByFocus } == true
        if (!playing) manager?.let { abandon(it) }
        DirectOutputState.log("exclusive mode OFF")
    }

    /** Called when the player is released (Close), so the focus never outlives the player. */
    fun release() {
        takeover = false
        handler.removeCallbacksAndMessages(null)
        pausedByFocus = false
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
