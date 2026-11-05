package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.exoplayer.ExoPlayer

/**
 * Hifi 1.34 music volume guard. It does not care about the output (headset or speaker): while the player is playing, a
 * sudden jump of 4 steps or more of the music volume in one change (the system restoring another device's volume, a ring or
 * notification level taking over) is put back to the level before the jump, and a toast says so.
 * Small steps (the volume keys, the slider) are never touched.
 */
internal object VolumeGuard {
    private const val VOLUME_JUMP = 4
    private val main = Handler(Looper.getMainLooper())
    private var installedFor: ExoPlayer? = null
    private var app: Context? = null
    private var volumeReceiver: BroadcastReceiver? = null

    fun install(context: Context, player: ExoPlayer) {
        if (installedFor === player) return
        release()
        val ctx = context.applicationContext
        app = ctx
        installedFor = player
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
                val value = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                val prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                if (value < 0 || prev < 0) return
                if (value - prev >= VOLUME_JUMP && (player.playWhenReady || player.isPlaying)) {
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0) } catch (_: Exception) { }
                    val msg = "Volume jumped $prev -> $value: put back to $prev"
                    DirectOutputState.log(msg)
                    try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) { }
                }
            }
        }
        volumeReceiver = rcv
        try { ctx.registerReceiver(rcv, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), null, main) } catch (_: Exception) { }
    }

    fun release() {
        val ctx = app
        if (ctx != null) {
            volumeReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) { } }
        }
        volumeReceiver = null
        installedFor = null
    }
}
