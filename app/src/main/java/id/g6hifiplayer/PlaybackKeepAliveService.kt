package id.g6hifiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundStarted = false
    private var lastToggleAtMs = Long.MIN_VALUE
    // One player event usually fires 2-3 listener callbacks (isPlaying, state, playWhenReady).
    // Coalesce them so the notification is rebuilt once instead of once per callback.
    private val publishRunnable = Runnable { publishNotification(attachedPlayer ?: sharedPlayer) }

    private fun scheduleNotificationPublish() {
        mainHandler.removeCallbacks(publishRunnable)
        mainHandler.postDelayed(publishRunnable, NOTIFICATION_COALESCE_MS)
    }

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
        when (val action = intent?.action) {
            ACTION_STOP -> {
                stopSessionAndPlayer()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE, ACTION_PLAY, ACTION_PAUSE, ACTION_NEXT, ACTION_PREVIOUS -> handleTransport(action.orEmpty())
            ACTION_DISMISS -> {
                // A notification dismissal never stops the foreground playback session.
                if (!foregroundStarted) ensureForeground()
                publishNotification(sharedPlayer)
                return START_STICKY
            }
        }
        // startForegroundService() must be answered with startForeground(), but only once:
        // repeating it on every transport tap rebuilt the notification a second time for nothing.
        if (!foregroundStarted) ensureForeground()
        sharedPlayer?.let { attachPlayer(it); publishSessionState(it); scheduleNotificationPublish() }
        return START_STICKY
    }

    /** Runs a transport command on the shared player. Must be called on the main thread. */
    private fun handleTransport(serviceAction: String) {
        val p = sharedPlayer ?: return
        when (serviceAction) {
            ACTION_TOGGLE -> {
                // Ignore a contact bounce / accidental double tap that would pause and resume instantly.
                val now = SystemClock.elapsedRealtime()
                if (lastToggleAtMs != Long.MIN_VALUE && now - lastToggleAtMs < TOGGLE_GUARD_MS) return
                lastToggleAtMs = now
                if (p.isPlaying || p.playWhenReady) p.pause() else p.play()
            }
            ACTION_PLAY -> p.play()
            ACTION_PAUSE -> p.pause()
            ACTION_NEXT -> if (p.mediaItemCount > 0) {
                if (p.hasNextMediaItem()) p.seekToNextMediaItem() else p.seekToDefaultPosition(0)
            }
            ACTION_PREVIOUS -> {
                if (p.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) p.seekTo(0L)
                else if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                else p.seekTo(0L)
            }
        }
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
        foregroundStarted = true
    }

    private fun stopSessionAndPlayer() {
        mainHandler.removeCallbacks(publishRunnable)
        foregroundStarted = false
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
        try { playbackStoppedCallback?.invoke() } catch (e: Exception) { android.util.Log.w(TAG, "UI stop callback failed", e) }
        stopForegroundCompat()
        stopSelf()
    }

    private fun attachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player && serviceListener != null) return
        try { attachedPlayer?.let { old -> serviceListener?.let { old.removeListener(it) } } } catch (_: Exception) { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishSessionState(player)
                scheduleNotificationPublish()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // The notification icon follows playWhenReady, so it flips on the tap, not after buffering.
                publishSessionState(player)
                scheduleNotificationPublish()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                publishSessionState(player)
                scheduleNotificationPublish()
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                publishSessionState(player)
                scheduleNotificationPublish()
            }
        }
        attachedPlayer = player
        serviceListener = listener
        player.addListener(listener)
        publishSessionState(player)
        scheduleNotificationPublish()
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
        // MainActivity publishes the real tagged cover into this service-owned MediaSession.
        // Read it back when rebuilding the foreground notification so a Pause state update
        // cannot replace the artwork-bearing notification with an artwork-free one.
        val sessionMetadata = try { session.controller.metadata } catch (_: Exception) { null }
        // The current media item is always up to date (also when the Activity is gone); the session
        // metadata may still describe the previous track for a moment after a skip.
        val itemTitle = itemMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
        val sessionTitle = sessionMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val sessionIsCurrent = itemTitle == null || sessionTitle == itemTitle
        val title = itemTitle ?: sessionTitle ?: "Music playback"
        val artist = itemMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: sessionMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = itemMetadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: sessionMetadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val cover = if (!sessionIsCurrent) null else try {
            sessionMetadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: sessionMetadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: sessionMetadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) { null }
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        fun actionPending(action: String, code: Int): PendingIntent {
            // Build 1.55: every button has its own intent action + data URI, so the system can never treat two buttons as the
            // same PendingIntent (extras alone are ignored when intents are compared).
            val intent = Intent(this, NotificationActionReceiver::class.java)
                .setAction("id.g6hifiplayer.notification." + action)
                .setData(android.net.Uri.parse("g6hifi://notification/" + action))
                .putExtra("notification_action", action)
            return PendingIntent.getBroadcast(this, code, intent, flags)
        }
        val open = PendingIntent.getActivity(this, 6100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags)
        val playing = player?.let { it.isPlaying || it.playWhenReady } == true
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(when {
                artist.isBlank() -> "Playback session active"
                album.isBlank() -> artist
                else -> artist + " \u2022 " + album
            })
            .setContentIntent(open)
            // Swiping away the notification hides it without stopping playback.
            .setDeleteIntent(actionPending("dismiss", 6105))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            // Same vector icons as the in-app transport buttons (previous / play-pause / next).
            .addAction(Notification.Action.Builder(R.drawable.ic_previous_24, "Previous", actionPending("previous", 6101)).build())
            .addAction(Notification.Action.Builder(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24,
                if (playing) "Pause" else "Play", actionPending("toggle", 6102)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_next_24, "Next", actionPending("next", 6103)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_close_24, "Close player", actionPending("close", 6104)).build())
        // Preserve the last known artwork on state-only updates (especially PLAYING -> PAUSED).
        // The service can publish before MainActivity's next UI callback, so read MediaSession's
        // stored bitmap rather than depending on an Activity view still being attached.
        if (cover != null && !cover.isRecycled) builder.setLargeIcon(cover)
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
        private const val NOTIFICATION_COALESCE_MS = 30L
        private const val TOGGLE_GUARD_MS = 150L
        private val playerLock = Any()
        @Volatile private var sharedPlayer: ExoPlayer? = null
        @Volatile private var serviceInstance: PlaybackKeepAliveService? = null
        @Volatile private var mediaSession: MediaSession? = null
        @Volatile private var audioTrackCallback: ((AudioTrack) -> Unit)? = null
        @Volatile private var playbackStoppedCallback: (() -> Unit)? = null

        fun setAudioTrackCallback(callback: ((AudioTrack) -> Unit)?) { audioTrackCallback = callback }
        fun setPlaybackStoppedCallback(callback: (() -> Unit)?) { playbackStoppedCallback = callback }

        fun existingPlayer(): ExoPlayer? = sharedPlayer

        /** Ask the service to rebuild the notification from the current player + MediaSession metadata. */
        @JvmStatic fun requestNotificationRefresh() { serviceInstance?.scheduleNotificationPublish() }

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
            // The service is already running (normal case while music is loaded): call it directly.
            // This skips the startForegroundService() round trip, which delayed every tap.
            val live = serviceInstance
            if (live != null && serviceAction != ACTION_STOP && serviceAction != ACTION_DISMISS) {
                if (Looper.myLooper() == Looper.getMainLooper()) live.handleTransport(serviceAction)
                else live.mainHandler.post { live.handleTransport(serviceAction) }
                return
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
                            audioTrackCallback?.invoke(track)
                        }
                        // Build 1.60: eligible PCM goes to the native DIRECT output; anything else (or any failure) uses `tracking`.
                        val direct = DirectAudioOutputProvider(tracking) { DirectOutputSettings.isEnabled(appContext) }
                        return DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                            .setAudioOutputProvider(direct)
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
