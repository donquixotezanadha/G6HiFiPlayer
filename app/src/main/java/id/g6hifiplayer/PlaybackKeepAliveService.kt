package id.g6hifiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Service-owned lifetime for the single ExoPlayer used by the UI, notification and queue. */
class PlaybackKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls and information"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releasePlayer()
            stopForegroundCompat()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val openApp = PendingIntent.getActivity(
            this, 6100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle("Music playback").setContentText("Playback session active")
            .setContentIntent(openApp).setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val active = sharedPlayer?.let { it.isPlaying || it.playWhenReady } == true
        if (active) android.util.Log.i(TAG, "Recent task removed; service-owned player remains active")
        else {
            releasePlayer()
            stopForegroundCompat()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun releasePlayer() {
        synchronized(playerLock) {
            try { sharedPlayer?.release() } catch (e: Exception) { android.util.Log.w(TAG, "Player release failed", e) }
            sharedPlayer = null
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        if (sharedPlayer?.let { !it.isPlaying && !it.playWhenReady } == true) releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "id.g6hifiplayer.action.START_PLAYBACK_GUARD"
        const val ACTION_STOP = "id.g6hifiplayer.action.STOP_PLAYBACK_GUARD"
        private const val CHANNEL_ID = "g6_hifi_playback"
        private const val NOTIFICATION_ID = 6106
        private const val TAG = "G6PlaybackService"
        private val playerLock = Any()
        @Volatile private var sharedPlayer: ExoPlayer? = null

        fun existingPlayer(): ExoPlayer? = sharedPlayer

        @OptIn(UnstableApi::class)
        @JvmStatic
        fun getOrCreatePlayer(context: Context): ExoPlayer {
            synchronized(playerLock) {
                sharedPlayer?.let { return it }
                val appContext = context.applicationContext
                val renderersFactory = object : DefaultRenderersFactory(appContext) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean
                    ): AudioSink {
                        val base = AudioTrackAudioOutputProvider.Builder(context).build()
                        val tracking = TrackingAudioOutputProvider(base) { track ->
                            MainActivity.onServiceAudioTrackCreated(track)
                        }
                        return DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                            .setAudioOutputProvider(tracking)
                            .build()
                    }
                }
                renderersFactory.setEnableAudioFloatOutput(false)
                return ExoPlayer.Builder(appContext, renderersFactory).build().apply {
                    setHandleAudioBecomingNoisy(true)
                    sharedPlayer = this
                }
            }
        }

        fun stopPlayback(context: Context) {
            synchronized(playerLock) {
                try { sharedPlayer?.stop(); sharedPlayer?.clearMediaItems(); sharedPlayer?.release() } catch (_: Exception) { }
                sharedPlayer = null
            }
            val intent = Intent(context, PlaybackKeepAliveService::class.java).setAction(ACTION_STOP)
            try { context.startService(intent) } catch (_: Exception) { context.stopService(Intent(context, PlaybackKeepAliveService::class.java)) }
        }
    }
}
