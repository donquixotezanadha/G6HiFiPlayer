package id.g6hifiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Hifi 1.30 headset-only mode. The player plays ONLY while a wired headset / headphone (the jack) is the connected output:
 *  - pressing play without the headset is refused (paused at once, message in the status line),
 *  - unplugging the headset while playing stops playback (checked again after 150 ms so a route hand-off does not fool it),
 *  - the DIRECT output itself refuses to open or start without the headset (see DirectAudioOutput), so there is no
 *    way to reach the speaker, Bluetooth or USB through this app.
 * It also guards the music volume: a sudden jump of 4 steps or more in one change while playing (the system restoring another
 * device's volume, a ring / notification level taking over) is put back to the level before the jump.
 */
internal object HeadsetGuard {
    private const val VOLUME_JUMP = 4
    private val main = Handler(Looper.getMainLooper())
    private var installedFor: ExoPlayer? = null
    private var app: Context? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var volumeReceiver: BroadcastReceiver? = null

    private fun isWired(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET

    /** True only when a wired headset / headphone output is connected right now. Any doubt counts as "not connected". */
    fun wired(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isWired(it) }
    } catch (_: Exception) { false }

    private fun deny(player: ExoPlayer, message: String) {
        DirectOutputState.strictStopText = message
        DirectOutputState.log(message)
        try { player.pause() } catch (_: Exception) { }
    }

    fun install(context: Context, player: ExoPlayer) {
        if (installedFor === player) return
        release()
        val ctx = context.applicationContext
        app = ctx
        installedFor = player
        DirectOutputState.headsetCheck = { wired(ctx) }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && !wired(ctx)) deny(player, "Headset-only mode: plug in the headset first")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !wired(ctx)) deny(player, "Headset-only mode: plug in the headset first")
            }
        })

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.none { isWired(it) }) return
                main.postDelayed({
                    if (!wired(ctx) && (player.playWhenReady || player.isPlaying)) {
                        deny(player, "Headset removed: playback stopped (headset-only mode)")
                    }
                }, 150L)
            }
        }
        deviceCallback = cb
        am.registerAudioDeviceCallback(cb, main)

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
            deviceCallback?.let { try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it) } catch (_: Exception) { } }
            volumeReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) { } }
        }
        deviceCallback = null
        volumeReceiver = null
        installedFor = null
    }
}
