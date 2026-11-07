package id.g6hifiplayer

import android.os.SystemClock
import androidx.media3.common.Player
import java.util.Locale

/**
 * Hifi 1.58: records WHY playback paused on its own, as one line in LOG AUDIO PATH (orange), with the position in the track.
 * Display / diagnostics only: it never pauses, resumes or changes anything in the audio path.
 *
 * Every place in the app that pauses the player on purpose calls [mark] just before ([markUser] for the user's own taps, which are not
 * logged). When the player then reports a pause, the fresh mark says who did it; a pause without a mark is named after the reason
 * ExoPlayer reports (another audio focus owner, the output becoming noisy, a remote / media-button command ...).
 */
internal object PauseWatch {
    @Volatile private var head: String? = null
    @Volatile private var detail: String = ""
    @Volatile private var at = 0L
    private const val FRESH_MS = 2500L

    /** A pause the app does for a known cause: logged with this head line. */
    fun mark(head: String, detail: String = "") { this.head = head; this.detail = detail; at = SystemClock.elapsedRealtime() }

    /** The user's own pause (button in the app, notification): not logged. */
    fun markUser() { mark("", "") }

    /** The user's own pause, unless a more specific mark was just set (for example by the media-session callback). */
    fun markUserIfNone() { if (SystemClock.elapsedRealtime() - at > FRESH_MS || head == null) markUser() }

    private fun take(): Pair<String, String>? {
        val h = head; val d = detail; val fresh = SystemClock.elapsedRealtime() - at <= FRESH_MS
        head = null; detail = ""
        return if (h != null && fresh) Pair(h, d) else null
    }

    private fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun install(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) return
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
                val marked = take()
                if (marked != null && marked.first.isEmpty()) return   // the user's own pause
                val (h, why) = marked ?: when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> Pair("PAUSE: FOCUS LOST", "ExoPlayer: audio focus taken by another owner")
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> Pair("PAUSE: OUTPUT CHANGED", "audio becoming noisy (headset unplugged or output switched)")
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> Pair("PAUSE: REMOTE COMMAND", "remote / media-button command")
                    Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> Pair("PAUSE: SUPPRESSED", "playback suppressed too long")
                    else -> Pair("PAUSE: UNKNOWN (APP)", "pause by app code without a known cause")
                }
                log(player, h, why + " • reason " + reason)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) return
                log(player, "HELD: FOCUS (TEMP)", "playback suppressed (transient audio focus loss), reason $playbackSuppressionReason")
            }
        })
    }

    private fun log(player: Player, head: String, detail: String) {
        try {
            val pos = player.currentPosition
            val dur = player.duration
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                ?: player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment ?: "Current track"
            val at = clock(pos) + (if (dur > 0) " / " + clock(dur) else "")
            AudioPathLog.add(AudioPathLog.KIND_UNMATCH, head + " " + clock(pos), title.take(80), detail + " • at " + at)
        } catch (_: Throwable) { }
    }
}
