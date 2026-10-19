package id.g6hifiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Service owns ExoPlayer lifetime, transport commands, MediaSession and fallback notification. */
class PlaybackKeepAliveService : Service() {
    private var attachedPlayer: ExoPlayer? = null
    private var serviceListener: Player.Listener? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls and information"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        ensureMediaSession(applicationContext)
        sharedPlayer?.let { attachPlayer(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSessionAndPlayer()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> sharedPlayer?.let { if (it.isPlaying || it.playWhenReady) it.pause() else it.play() }
            ACTION_PLAY -> sharedPlayer?.play()
            ACTION_PAUSE -> sharedPlayer?.pause()
            ACTION_NEXT -> sharedPlayer?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
            ACTION_PREVIOUS -> sharedPlayer?.let {
                if (it.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) it.seekTo(0L)
                else if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem()
                else it.seekTo(0L)
            }
            ACTION_DISMISS -> {
                // A notification dismissal never stops the foreground playback session.
                publishNotification(sharedPlayer)
                return START_STICKY
            }
        }
        ensureForeground()
        sharedPlayer?.let { attachPlayer(it); publishSessionState(it); publishNotification(it) }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val active = sharedPlayer?.let { it.isPlaying || it.playWhenReady } == true
        if (active) android.util.Log.i(TAG, "Recent task removed; service-owned player and MediaSession remain active")
        else stopSessionAndPlayer()
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureForeground() {
        val notification = buildNotification(sharedPlayer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopSessionAndPlayer() {
        synchronized(playerLock) {
            try { attachedPlayer?.let { p -> serviceListener?.let { p.removeListener(it) } } } catch (_: Exception) { }
            attachedPlayer = null
            serviceListener = null
            try { sharedPlayer?.stop(); sharedPlayer?.clearMediaItems(); sharedPlayer?.release() } catch (_: Exception) { }
            sharedPlayer = null
        }
        try { mediaSession?.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).setActions(0L).build()) } catch (_: Exception) { }
        try { mediaSession?.isActive = false } catch (_: Exception) { }
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { }
        try { G6MusicWidgetProvider.refresh(this, "", "", "", false, null, false, 0, 0) } catch (_: Exception) { }
        stopForegroundCompat()
        stopSelf()
    }

    private fun attachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player && serviceListener != null) return
        try { attachedPlayer?.let { old -> serviceListener?.let { old.removeListener(it) } } } catch (_: Exception) { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishSessionState(player)
                publishNotification(player)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                publishSessionState(player)
                publishNotification(player)
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                publishSessionState(player)
                publishNotification(player)
            }
        }
        attachedPlayer = player
        serviceListener = listener
        player.addListener(listener)
        publishSessionState(player)
        publishNotification(player)
    }

    private fun publishSessionState(player: ExoPlayer) {
        val session = ensureMediaSession(applicationContext)
        val playing = player.isPlaying || player.playWhenReady
        val state = when {
            player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        try {
            session.setPlaybackState(PlaybackState.Builder()
                .setActions(actions)
                .setState(state, player.currentPosition.coerceAtLeast(0L), if (playing) 1f else 0f)
                .build())
            session.isActive = player.mediaItemCount > 0
        } catch (e: Exception) { android.util.Log.w(TAG, "MediaSession state update failed", e) }
    }

    private fun publishNotification(player: ExoPlayer?) {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(player)) }
        catch (e: Exception) { android.util.Log.w(TAG, "Playback notification update failed", e) }
    }

    private fun buildNotification(player: ExoPlayer?): Notification {
        val session = ensureMediaSession(applicationContext)
        val itemMetadata = player?.currentMediaItem?.mediaMetadata
        val title = itemMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Music playback"
        val artist = itemMetadata?.artist?.toString().orEmpty()
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        fun actionPending(action: String, code: Int): PendingIntent {
            val intent = Intent(this, NotificationActionReceiver::class.java).putExtra("notification_action", action)
            return PendingIntent.getBroadcast(this, code, intent, flags)
        }
        val open = PendingIntent.getActivity(this, 6100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags)
        val playing = player?.let { it.isPlaying || it.playWhenReady } == true
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(if (artist.isBlank()) "Playback session active" else artist)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", actionPending("previous", 6101)).build())
            .addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", actionPending("toggle", 6102)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", actionPending("next", 6103)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Close player", actionPending("close", 6104)).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        return builder.build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else { @Suppress("DEPRECATION") stopForeground(true) }
    }

    override fun onDestroy() {
        if (serviceInstance === this) serviceInstance = null
        try { attachedPlayer?.let { p -> serviceListener?.let { p.removeListener(it) } } } catch (_: Exception) { }
        attachedPlayer = null
        serviceListener = null
        if (sharedPlayer?.let { !it.isPlaying && !it.playWhenReady } == true) releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "id.g6hifiplayer.action.START_PLAYBACK_GUARD"
        const val ACTION_STOP = "id.g6hifiplayer.action.STOP_PLAYBACK_GUARD"
        const val ACTION_TOGGLE = "id.g6hifiplayer.action.TOGGLE"
        const val ACTION_PLAY = "id.g6hifiplayer.action.PLAY"
        const val ACTION_PAUSE = "id.g6hifiplayer.action.PAUSE"
        const val ACTION_NEXT = "id.g6hifiplayer.action.NEXT"
        const val ACTION_PREVIOUS = "id.g6hifiplayer.action.PREVIOUS"
        const val ACTION_DISMISS = "id.g6hifiplayer.action.DISMISS"
        private const val CHANNEL_ID = "g6_hifi_playback"
        private const val NOTIFICATION_ID = 6106
        private const val TAG = "G6PlaybackService"
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 3000L
        private val playerLock = Any()
        @Volatile private var sharedPlayer: ExoPlayer? = null
        @Volatile private var serviceInstance: PlaybackKeepAliveService? = null
        @Volatile private var mediaSession: MediaSession? = null

        fun existingPlayer(): ExoPlayer? = sharedPlayer

        fun getOrCreateMediaSession(context: Context): MediaSession = ensureMediaSession(context.applicationContext)

        private fun ensureMediaSession(context: Context): MediaSession {
            mediaSession?.let { return it }
            synchronized(playerLock) {
                mediaSession?.let { return it }
                return MediaSession(context, "G6HiFiPlayer").apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPlay() { dispatchAction(context, "play") }
                        override fun onPause() { dispatchAction(context, "pause") }
                        override fun onSkipToNext() { dispatchAction(context, "next") }
                        override fun onSkipToPrevious() { dispatchAction(context, "previous") }
                        override fun onSeekTo(pos: Long) { sharedPlayer?.let { p -> try { p.seekTo(pos.coerceAtLeast(0L)) } catch (_: Exception) { } } }
                    })
                    isActive = false
                    mediaSession = this
                }
            }
        }

        @JvmStatic fun dispatchAction(context: Context?, action: String) {
            val ctx = context ?: serviceInstance?.applicationContext ?: return
            val serviceAction = when (action.lowercase()) {
                "toggle" -> ACTION_TOGGLE
                "play" -> ACTION_PLAY
                "pause" -> ACTION_PAUSE
                "next" -> ACTION_NEXT
                "previous" -> ACTION_PREVIOUS
                "close", "stop" -> ACTION_STOP
                "dismiss" -> ACTION_DISMISS
                else -> return
            }
            val intent = Intent(ctx, PlaybackKeepAliveService::class.java).setAction(serviceAction)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceAction != ACTION_STOP) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (e: Exception) { android.util.Log.e(TAG, "Transport command failed: $action", e) }
        }

        @OptIn(UnstableApi::class)
        @JvmStatic fun getOrCreatePlayer(context: Context): ExoPlayer {
            synchronized(playerLock) {
                sharedPlayer?.let { player ->
                    serviceInstance?.let { it.attachPlayer(player) }
                    return player
                }
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
                val player = ExoPlayer.Builder(appContext, renderersFactory).build().apply {
                    setHandleAudioBecomingNoisy(true)
                }
                sharedPlayer = player
                serviceInstance?.attachPlayer(player)
                return player
            }
        }

        private fun releasePlayer() {
            synchronized(playerLock) {
                try { sharedPlayer?.release() } catch (e: Exception) { android.util.Log.w(TAG, "Player release failed", e) }
                sharedPlayer = null
            }
        }

        fun stopPlayback(context: Context) {
            dispatchAction(context.applicationContext, "close")
        }
    }
}
