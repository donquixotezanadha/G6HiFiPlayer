package id.g6hifiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.audio.AudioSink
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

    // Build 2.3: set by Close player; after that nothing may post the notification again.
    private var closing = false

    private fun scheduleNotificationPublish() {
        if (closing) return
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
        if (exclusiveEnabled()) registerJackWatcher()
    }

    // ---- Build 1.66: exclusive mode (jack plug-in confirmation) --------------------------------------------------
    private var jackCallback: AudioDeviceCallback? = null

    private fun exclusiveEnabled(): Boolean = exclusiveEnabled(applicationContext)

    private fun isWired(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET

    private fun wiredNow(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isWired(it) } } catch (_: Exception) { false }
    }

    /** Listens for the headphone jack while the service lives. A new plug-in raises the confirmation notification. */
    private fun registerJackWatcher() {
        if (jackCallback != null) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        var initialReport = true // the system first reports the devices that are already connected; that is not a plug-in
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (initialReport) return
                // Build 1.67: no question any more. Plugging in the jack takes over all audio at once.
                if (addedDevices.any { isWired(it) } && !ExclusiveAudioFocus.takeover) autoTakeover()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.none { isWired(it) }) return
                // Debounce: a route hand-off can briefly report the jack as removed.
                mainHandler.postDelayed({
                    if (!wiredNow()) {
                        cancelTakeoverPrompt()
                        if (ExclusiveAudioFocus.takeover) ExclusiveAudioFocus.stopTakeover()
                        scheduleNotificationPublish()
                    }
                }, 400L)
            }
        }
        jackCallback = cb
        am.registerAudioDeviceCallback(cb, mainHandler)
        mainHandler.post {
            initialReport = false
            // The jack is already plugged in when the service starts: take over right away.
            if (wiredNow() && !ExclusiveAudioFocus.takeover) autoTakeover()
        }
    }

    private fun unregisterJackWatcher() {
        val cb = jackCallback ?: return
        try { (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(cb) } catch (_: Exception) { }
        jackCallback = null
    }

    /** Build 1.67: exclusive mode is automatic while a headset is plugged in. */
    private fun autoTakeover() {
        if (!wiredNow()) return
        cancelTakeoverPrompt() // removes the old "Take over?" notification of Build 1.66 if it is still shown
        if (!foregroundStarted) ensureForeground()
        ExclusiveAudioFocus.startTakeover(applicationContext)
        scheduleNotificationPublish()
    }

    private fun actionBroadcast(action: String, code: Int): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val intent = Intent(this, NotificationActionReceiver::class.java)
            .setAction("id.g6hifiplayer.notification." + action)
            .setData(android.net.Uri.parse("g6hifi://notification/" + action))
            .putExtra("notification_action", action)
        return PendingIntent.getBroadcast(this, code, intent, flags)
    }

    private fun cancelTakeoverPrompt() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(PROMPT_NOTIFICATION_ID) } catch (_: Exception) { }
    }

    /** Foreground notification while nothing is loaded and exclusive mode is on. */
    private fun buildStandbyNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 6100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val active = ExclusiveAudioFocus.takeover
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(if (active) "Exclusive mode ON" else "Exclusive mode ready")
            .setContentText(if (active) "This player holds all audio on the jack." else "Plug in headphones: this player takes over all audio by itself.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // Build 2.3: "Close player" now really closes (ACTION_EXIT): the notification goes away and exclusive mode ends.
        builder.addAction(Notification.Action.Builder(R.drawable.ic_close_24, "Close player", actionBroadcast("exit", 6104)).build())
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_STOP -> {
                // Build 2.4: Close (also the in-app close) ends everything, including the "Exclusive mode" notification.
                // Takeover starts again only when the player is opened again (MainActivity -> armExclusive).
                stopSessionAndPlayer(stopForGood = true)
                return START_NOT_STICKY
            }
            ACTION_EXIT -> {
                stopSessionAndPlayer(stopForGood = true)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE, ACTION_PLAY, ACTION_PAUSE, ACTION_NEXT, ACTION_PREVIOUS,
            ACTION_EXCLUSIVE_ON, ACTION_EXCLUSIVE_OFF, ACTION_EXCLUSIVE_NO, ACTION_EXCLUSIVE_ARM -> handleTransport(action.orEmpty())
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
        when (serviceAction) {
            ACTION_EXCLUSIVE_ON -> { registerJackWatcher(); autoTakeover(); return }
            ACTION_EXCLUSIVE_OFF -> { ExclusiveAudioFocus.stopTakeover(); scheduleNotificationPublish(); return }
            ACTION_EXCLUSIVE_NO -> { cancelTakeoverPrompt(); return }
            ACTION_EXCLUSIVE_ARM -> {
                if (!foregroundStarted) ensureForeground()
                registerJackWatcher()
                // Opening the app with the headset plugged in (e.g. after closing the player) takes over again.
                if (wiredNow() && !ExclusiveAudioFocus.takeover) autoTakeover() else scheduleNotificationPublish()
                return
            }
        }
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
        else stopSessionAndPlayer(stopForGood = true) // Build 2.4: nothing playing -> no standby notification; takeover returns when the app is opened
        super.onTaskRemoved(rootIntent)
    }

    private fun ensureForeground() {
        val notification = buildNotification(sharedPlayer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }

    private fun stopSessionAndPlayer(stopForGood: Boolean = false) {
        if (stopForGood) closing = true
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
        ExclusiveAudioFocus.release()
        if (exclusiveEnabled() && !stopForGood) {
            // Close player: playback ends but the jack watcher stays armed (Setting -> Exclusive mode OFF to stop it).
            try { G6MusicWidgetProvider.refresh(this, "", "", "", false, null, false, 0, 0) } catch (_: Exception) { }
            try { playbackStoppedCallback?.invoke() } catch (e: Exception) { android.util.Log.w(TAG, "UI stop callback failed", e) }
            registerJackWatcher()
            if (!foregroundStarted) ensureForeground()
            foregroundStarted = true
            publishNotification(null)
            return
        }
        unregisterJackWatcher()
        cancelTakeoverPrompt()
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
        if (closing) return
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(player)) }
        catch (e: Exception) { android.util.Log.w(TAG, "Playback notification update failed", e) }
    }

    private fun buildNotification(player: ExoPlayer?): Notification {
        if (exclusiveEnabled() && player?.currentMediaItem == null) return buildStandbyNotification()
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
            .addAction(Notification.Action.Builder(R.drawable.ic_close_24, "Close player", actionPending("exit", 6104)).build())
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
        unregisterJackWatcher()
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
        /** Build 2.3: Close player from a notification button: stops everything for good (no standby notification left behind). */
        const val ACTION_EXIT = "id.g6hifiplayer.action.EXIT"
        const val ACTION_TOGGLE = "id.g6hifiplayer.action.TOGGLE"
        const val ACTION_PLAY = "id.g6hifiplayer.action.PLAY"
        const val ACTION_PAUSE = "id.g6hifiplayer.action.PAUSE"
        const val ACTION_NEXT = "id.g6hifiplayer.action.NEXT"
        const val ACTION_PREVIOUS = "id.g6hifiplayer.action.PREVIOUS"
        const val ACTION_DISMISS = "id.g6hifiplayer.action.DISMISS"
        const val ACTION_EXCLUSIVE_ON = "id.g6hifiplayer.action.EXCLUSIVE_ON"
        const val ACTION_EXCLUSIVE_OFF = "id.g6hifiplayer.action.EXCLUSIVE_OFF"
        const val ACTION_EXCLUSIVE_NO = "id.g6hifiplayer.action.EXCLUSIVE_NO"
        const val ACTION_EXCLUSIVE_ARM = "id.g6hifiplayer.action.EXCLUSIVE_ARM"
        private const val PROMPT_CHANNEL_ID = "g6_exclusive_prompt"
        private const val PROMPT_NOTIFICATION_ID = 6107
        const val PREFS_NAME = "id.g6hifiplayer.MainActivity" // the file MainActivity.getPreferences() uses

        /** Exclusive mode setting (Setting tab). On by default. */
        @JvmStatic fun exclusiveEnabled(context: Context): Boolean = true // Build 1.67: no setting, always on

        /** Starts the service in standby so the jack plug-in prompt works (called when the app opens / the setting is turned on). */
        @JvmStatic fun armExclusive(context: Context) {
            val ctx = context.applicationContext
            if (!exclusiveEnabled(ctx)) return
            val live = serviceInstance
            if (live != null) { live.mainHandler.post { live.handleTransport(ACTION_EXCLUSIVE_ARM) }; return }
            val intent = Intent(ctx, PlaybackKeepAliveService::class.java).setAction(ACTION_EXCLUSIVE_ARM)
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent) else ctx.startService(intent) }
            catch (e: Exception) { android.util.Log.w(TAG, "Arming exclusive mode failed", e) }
        }

        /** Setting turned off: leave exclusive mode and stop the standby service if nothing is playing. */
        @JvmStatic fun disarmExclusive(context: Context) {
            ExclusiveAudioFocus.stopTakeover()
            val live = serviceInstance ?: return
            val idle = sharedPlayer?.let { !it.isPlaying && !it.playWhenReady } != false
            if (idle) live.mainHandler.post { live.stopSessionAndPlayer(stopForGood = true) } else live.scheduleNotificationPublish()
        }

        @JvmStatic fun exclusiveActive(): Boolean = ExclusiveAudioFocus.takeover
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
        @Volatile private var playbackStoppedCallback: (() -> Unit)? = null

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
                "exit" -> ACTION_EXIT
                "dismiss" -> ACTION_DISMISS
                "exclusive_on" -> ACTION_EXCLUSIVE_ON
                "exclusive_off" -> ACTION_EXCLUSIVE_OFF
                "exclusive_no" -> ACTION_EXCLUSIVE_NO
                else -> return
            }
            // The service is already running (normal case while music is loaded): call it directly.
            // This skips the startForegroundService() round trip, which delayed every tap.
            val live = serviceInstance
            if (serviceAction == ACTION_EXIT) {
                // Close player: handled right here so it works even when the service has to be taken down.
                if (live != null) {
                    live.mainHandler.post { live.stopSessionAndPlayer(stopForGood = true) }
                } else {
                    // The service is gone but a notification may still be on screen: remove it.
                    try {
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(NOTIFICATION_ID); nm.cancel(PROMPT_NOTIFICATION_ID)
                    } catch (_: Exception) { }
                    ExclusiveAudioFocus.release()
                }
                return
            }
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
                        // Build 1.62: float output ON. With float OFF, Media3 1.10 puts ToInt16PcmAudioProcessor in front of
                        // the output and cuts 24-bit files to 16-bit before they reach us. With float ON, 24-bit PCM travels
                        // as float (exact) and DirectAudioOutput converts it back to 24-bit integers. 16-bit stays 16-bit.
                        return DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(true)
                            .setEnableAudioOutputPlaybackParameters(false)
                            .setAudioOutputProvider(DirectAudioOutputProvider())
                            .build()
                    }
                }
                renderersFactory.setEnableAudioFloatOutput(true)
                val player = ExoPlayer.Builder(appContext, renderersFactory)
                    .build().apply {
                        setHandleAudioBecomingNoisy(true)
                        volume = 1.0f
                    }
                ExclusiveAudioFocus.attach(appContext, player)
                sharedPlayer = player
                serviceInstance?.attachPlayer(player)
                return player
            }
        }


        private fun releasePlayer() {
            synchronized(playerLock) {
                try { sharedPlayer?.release() } catch (e: Exception) { android.util.Log.w(TAG, "Player release failed", e) }
                ExclusiveAudioFocus.release()
                sharedPlayer = null
            }
        }

        fun stopPlayback(context: Context) {
            dispatchAction(context.applicationContext, "close")
        }
    }
}
