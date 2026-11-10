package id.g6hifiplayer

import android.app.Activity
import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.ProgressBar
import android.widget.Toast
import android.graphics.drawable.BitmapDrawable
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.util.UnstableApi
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class Track(val uri: Uri, val title: String, val artist: String, val album: String, val coverUri: Uri?, val trackNumber: Int = Int.MAX_VALUE,
                                  /** Build 2.4: year from the ID tag. null = not read yet (library saved by an older build), "" = the file has none. */
                                  val year: String? = null,
                                  /** Build 2.31: source bit depth cached during library scan for persistent Hi-Res cover badges. */
                                  val bitDepth: Int? = null)
    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var strictBlocked = false   // Hifi 1.29: the current file has no renderer (not FLAC / ALAC / WAV)
    private var mediaSession: MediaSession? = null
    private val notificationWidgetExecutor = Executors.newSingleThreadExecutor()
    private var sourceRate: Int? = null
    private var sourceBits: Int? = null
    private var sourceBitrate: Int? = null
    private var durationMs = 0
    private var prepared = false
    private var seeking = false
    private var pendingAutoplay = false
    private var pendingRestorePositionMs: Long? = null
    private var lastPlaybackPersistAtMs = 0L
    private var lastWidgetProgressUpdateAtMs = 0L
    private var lastPlaybackRestoreAttempted = false
    private var tracks: List<Track> = emptyList()
    private var currentIndex = -1
    // Queue stores indices into the scanned library; album playback stays inside that album.
    private var playbackQueue: List<Int> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var selectedAlbum: String? = null
    // A dismissed notification stays hidden until the user starts another track.
    private var notificationDismissed = false
    // Avoid rebuilding MediaSession metadata/artwork on every Play/Pause notification tap.
    private var lastNotificationMetadataKey: String? = null
    private var artToken = 0
    // Build 1.42: one palette for every page. Surface = dominant cover colour, ink = white/near-black by contrast.
    // Build 1.45: in the Classic theme the accent is the original purple instead of the page ink.
    // Hifi 2.1: the accent is no longer the fixed purple: it comes from the cover (see AlbumAccent). Light keeps the old purple.
    private var albumAccent = AlbumAccent.DEFAULT_ACCENT
    private var albumDark = AlbumAccent.DEFAULT_DARK
    private var albumAccentLight = AlbumAccent.DEFAULT_LIGHT_ACCENT   // Hifi 2.6
    private var albumTint = AlbumAccent.DEFAULT_LIGHT_TINT           // Hifi 2.6: pastel of the cover colour (Light)
    private val accentColor get() = if (lightTheme) albumAccentLight else albumAccent
    private val accentCache = java.util.concurrent.ConcurrentHashMap<String, AlbumAccent.Result>()
    // Now Playing look (Build 1.41): the surface is the dominant colour of the cover, the ink is the
    // text/button colour with the best contrast on it. This is the default ("Adaptive" in Setting).
    private var playerSurface = DEFAULT_ACCENT
    private var playerInk = Color.WHITE
    private var appliedPlayerSurface: Int? = null
    private val dominantColorCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // Build 1.45 Classic theme: the cover, softened and darkened, behind every page; purple accent, white and grey text.
    private var darkBackdropBitmap: android.graphics.Bitmap? = null
    private var darkBackdropSource: java.lang.ref.WeakReference<android.graphics.Bitmap>? = null
    private val darkDrawables = mutableListOf<DarkBackdropDrawable>()
    /** True when the surface is light and the ink is dark (drives the status/navigation bar icon style). */
    private var lightTheme = false
    // Build 3.08: show the startup splash only on the Activity's first creation, not on theme rebuilds.
    private var startupSplashShown = false
    private val BG get() = if (lightTheme) LIGHT_SURFACE else playerSurface
    // Hifi 2.2: the dark greys follow the darkened accent of the cover (the old greys stay for covers without colour / no cover).
    private val hasAlbumTint get() = if (lightTheme) albumTint != AlbumAccent.DEFAULT_LIGHT_TINT else albumDark != AlbumAccent.DEFAULT_DARK
    private val CARD get() = if (lightTheme) (if (hasAlbumTint) withAlpha(albumTint, 0xCC) else 0xCCFFFFFF.toInt()) else if (hasAlbumTint) withAlpha(mixColors(albumDark, Color.BLACK, 0.35f), 0xCC) else 0xCC101014.toInt()
    private val ART get() = if (lightTheme) (if (hasAlbumTint) mixColors(albumTint, Color.BLACK, 0.06f) or 0xFF000000.toInt() else 0xFFE9E9F0.toInt()) else if (hasAlbumTint) mixColors(albumDark, Color.BLACK, 0.20f) or 0xFF000000.toInt() else 0xFF181820.toInt()
    private val LINE get() = if (lightTheme) (if (hasAlbumTint) 0x1F000000 else 0xCCE1E1EC.toInt()) else if (hasAlbumTint) withAlpha(mixColors(albumDark, Color.WHITE, 0.10f), 0xCC) else 0xCC292932.toInt()
    /** Veil over the blurred cover: black, or a very dark version of the cover colour. */
    private fun backdropVeil(): Int = if (lightTheme) (if (hasAlbumTint) withAlpha(albumTint, 0xB3) else 0xB3FFFFFF.toInt()) else if (!hasAlbumTint) 0xB3000000.toInt() else withAlpha(mixColors(albumDark, Color.BLACK, 0.45f), 0xB3)
    private val WHITE get() = if (lightTheme) 0xFF171720.toInt() else playerInk
    // Hifi 1.22: Light grey text 0xFF686875 -> 0xFF484855 (darker). Dark is unchanged (0xFFA4A4B2).
    private val MUTED get() = if (lightTheme) 0xFF484855.toInt() else 0xFFA4A4B2.toInt()
    private var pageRoot: LinearLayout? = null
    private var tabsPaletteDirty = false
    private var buildingUi = false
    private val themers = java.util.WeakHashMap<View, MutableList<(View) -> Unit>>()
    private var albumGridColumns = 2
    // Library thumbnails (Hifi 1.15: 512 px, full colour; ~1 MB each, so the cache holds about 60) and the album-detail covers.
    private val bitmapArtworkCache = object : android.util.LruCache<String, android.graphics.Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    // Now Playing covers (Hifi 1.44: up to NOW_PLAYING_COVER_PX = 1440 px (the screen width), ARGB_8888 = up to ~8 MB each; the cover is fitted to the width
    // at screen size). Kept apart from the thumbnails so the prefetch of neighbouring covers can no longer be evicted by (or evict) the library grid.
    private val playerArtworkCache = object : android.util.LruCache<String, android.graphics.Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    // 512 px copies used for the notification, MediaSession and lock screen (small Binder payload).
    private val notificationArtworkCache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    private val noArtworkTracks = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val artworkDecodeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val artworkDiskDir by lazy { File(cacheDir, "player_art").apply { mkdirs() } }
    // Build 2.2: small (320 px) JPEG copies of the album-grid covers, so the grid paints from disk at start-up.
    private val thumbDiskDir by lazy {
        File(cacheDir, "thumbs").apply {
            mkdirs()
            // Hifi 1.15: grid thumbnails are 512 px now; the old 320 px files (and "no cover" markers) are dropped once and rebuilt sharp.
            val prefs = getPreferences(MODE_PRIVATE)
            if (prefs.getInt("thumb_px", 0) != THUMB_PX) {
                listFiles()?.forEach { it.delete() }
                prefs.edit().putInt("thumb_px", THUMB_PX).apply()
            }
        }
    }
    // Build 2.2: every library scan gets a number; a result from an older scan is dropped.
    private var scanGeneration = 0
    @Volatile private var backgroundScanRunning = false
    private var lastBackgroundScanAt = 0L
    private var visibleTrackLimit = 80
    private val artworkExecutor = Executors.newFixedThreadPool(2)
    // Coalesce duplicate thumbnail requests when grid cells are rebuilt after album expansion.
    private val artworkTargetsInFlight = mutableMapOf<String, MutableList<java.lang.ref.WeakReference<ImageView>>>()
    // Keep current-player artwork independent of the library thumbnails backlog.
    private val playbackArtworkExecutor = Executors.newSingleThreadExecutor()
    // Neighbour prefetch and disk writes run at background priority and never delay the current cover.
    private var prefetchToken = 0
    private val artworkBackgroundExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) { }
            r.run()
        }, "g6-art-bg")
    }
    private val metadataExecutor = Executors.newFixedThreadPool(2)
    private val artworkBytesCache = java.util.Collections.synchronizedMap(java.util.LinkedHashMap<String, ByteArray>(16, 0.75f, true))
    private val diagnosticEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
    private lateinit var scanProgress: ProgressBar
    private lateinit var scanProgressText: TextView
    private lateinit var albumScanSummary: TextView
    private lateinit var audioInfoInline: TextView
    private lateinit var audioStatusInline: TextView
    private var sourceFormat: String? = null
    private lateinit var playerPanel: FrameLayout
    private lateinit var libraryPanel: LinearLayout
    private lateinit var songsPanel: LinearLayout
    private lateinit var songRows: LinearLayout
    private lateinit var songSearchInput: EditText
    private var songSearchQuery = ""
    private lateinit var settingsPanel: LinearLayout
    private lateinit var queueRows: LinearLayout
    private lateinit var navPlayer: FrameLayout
    private lateinit var navLibrary: FrameLayout
    private lateinit var navSongs: FrameLayout
    private lateinit var queueMiniArt: ImageView
    private lateinit var queueMiniTitle: TextView
    private lateinit var queueMiniArtist: TextView
    private lateinit var queueMiniProgress: ProgressBar
    private lateinit var queueMiniPlayView: ImageView
    private var currentPage = "player"
    private lateinit var navFile: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var playView: ImageView
    private lateinit var statusView: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var playerBottomDock: LinearLayout
    private lateinit var prevView: ImageView
    private lateinit var nextView: ImageView
    private lateinit var artPlaceholder: TextView
    private lateinit var librarySummary: TextView
    // Hifi 1.4: page header (title left, gear right), swipe between the two pages, floating settings window.
    private lateinit var headerTitle: TextView
    private var pageContentView: View? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeBlocked = false
    private var swipeDragging = false
    private var swipeAnimating = false
    private var swipeVelocity: android.view.VelocityTracker? = null
    // Hifi 1.39: slide left on Now Playing goes to Album (the mini player rises from the bottom); slide right on Album, tapping the mini player, Back and play actions slide back to Now Playing.
    private var pageAnimToken = 0   // Hifi 1.39: a newer page slide makes the end action of an older one do nothing
    private var headerGear: View? = null
    private var headerThemeIcons: List<View> = emptyList()   // Hifi 2.7: moon (Dark) + sun (Light) on Now Playing
    private var queueRowsSig = Int.MIN_VALUE
    private var pageScroll: ScrollView? = null
    private var listScrollY = 0
    private var restoringScroll = false
    private var queueMiniHost: FrameLayout? = null
    private var scrollFadeHost: EdgeCutLayout? = null
    private var playerTitleRow: View? = null
    private var playerCoverView: NowPlayingCoverView? = null   // Hifi 1.43: the clear cover behind the Now Playing page (and its header)
    private var playerCoverBitmap: android.graphics.Bitmap? = null
    private var hiResLogoView: ImageView? = null      // Hifi 1.44: Hi-Res logo above the queue button
    private var queueButton: ImageView? = null      // Hifi 1.37: the 3-line queue button next to the title
    private var queueDialog: AlertDialog? = null     // Hifi 1.37: the queue is a floating window
    private var bottomInsetPx = 0   // Hifi 1.18: real navigation-bar height, kept for the docked mini player
    private lateinit var albumRows: LinearLayout
    private lateinit var trackRows: LinearLayout
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())
    private var lastDepthVerdict = 0
    private var lastChecksumSerial = 0
    private val ticker = object : Runnable {
        override fun run() {
            // Keep frame-time work low on older devices: update playback position only.
            // Audio route inspection is event-driven elsewhere, not polled every half-second.
            // Build 1.65: the real-24-bit check needs a few seconds of audio; refresh the info line when its answer changes.
            val depthNow = DirectOutputState.depthVerdict()
            if (depthNow != lastDepthVerdict) { lastDepthVerdict = depthNow; refreshAudioInfo() }
            // Hifi 1.24: a whole track went through the MD5 check -> show the verdict once and keep it in the status line.
            val sumNow = DirectOutputState.checksumSerial
            if (sumNow != lastChecksumSerial) {
                lastChecksumSerial = sumNow
                refreshAudioInfo()   // the green / orange label follows the verdict; the details are in LOG AUDIO PATH (Hifi 1.40)
            }
            if (DirectOutputState.strictStopText.isNotEmpty()) {   // Hifi 1.29 / 1.34: a strict stop (check mismatch, another app took the audio)
                statusView.text = DirectOutputState.strictStopText
                DirectOutputState.strictStopText = ""
                pendingAutoplay = false
                syncPlaybackIcon(false)
            }
            if (prepared) player?.let { p ->
                persistPlaybackState(force = false)
                if (!seeking) {
                    val pos = try { p.currentPosition.toInt() } catch (_: Exception) { 0 }
                    if (seek.max != durationMs.coerceAtLeast(1)) seek.max = durationMs.coerceAtLeast(1)
                    if (!seeking && seek.progress != pos) seek.progress = pos.coerceIn(0, seek.max)
                    if (::queueMiniProgress.isInitialized) {
                        if (queueMiniProgress.max != durationMs.coerceAtLeast(1)) queueMiniProgress.max = durationMs.coerceAtLeast(1)
                        queueMiniProgress.progress = pos.coerceIn(0, queueMiniProgress.max)
                    }
                    val label = time(pos)
                    if (elapsed.text.toString() != label) elapsed.text = label
                    val widgetNow = System.currentTimeMillis()
                    if (p.isPlaying && widgetNow - lastWidgetProgressUpdateAtMs >= 1000L) {
                        lastWidgetProgressUpdateAtMs = widgetNow
                        G6Widgets.refreshProgress(this@MainActivity, pos, durationMs)
                    }
                }
            }
            handler.postDelayed(this, 350)
        }
    }
    private val wiredDisconnectCheck = Runnable {
        // AudioDeviceCallback can arrive before Android finishes updating its device list.
        // Recheck after a short debounce so a transient route handoff does not pause playback.
        val outputs = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            catch (_: Exception) { emptyArray<AudioDeviceInfo>() }
        val wiredStillConnected = outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        if (!wiredStillConnected) {
            val p = player
            val wasScheduledToPlay = pendingAutoplay || p?.playWhenReady == true || p?.isPlaying == true
            if (wasScheduledToPlay) {
                pendingAutoplay = false
                PauseWatch.mark("PAUSE: JACK UNPLUGGED", "the wired headset was reported as disconnected")
                try { p?.pause() } catch (e: Exception) {
                    recordDiagnostic("WIRED_UNPLUG_PAUSE_FAILED", e)
                }
                syncPlaybackIcon(false)
                statusView.text = "Playback paused • headphone jack disconnected"
                recordDiagnostic("WIRED_UNPLUG_PLAYBACK_STOPPED action=pause preventSpeakerFallback=true")
                // Player.Listener publishes the paused state; do not rebuild the notification twice.
            }
        } else {
            recordDiagnostic("WIRED_UNPLUG_CHECK_CANCELLED wiredDeviceStillConnected=true")
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val details = addedDevices.joinToString { describe(it) }
            recordDiagnostic("AUDIO_DEVICE_ADDED devices=" + details)
            val wiredAdded = addedDevices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            // A speaker-added event can accompany jack removal; it must not cancel the stop check.
            if (wiredAdded) handler.removeCallbacks(wiredDisconnectCheck)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val details = removedDevices.joinToString { describe(it) }
            recordDiagnostic("AUDIO_DEVICE_REMOVED devices=" + details)
            val wiredRemoved = removedDevices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
            if (wiredRemoved) {
                recordDiagnostic("WIRED_UNPLUG_DETECTED debounceMs=120 action=verify-then-pause")
                handler.removeCallbacks(wiredDisconnectCheck)
                handler.postDelayed(wiredDisconnectCheck, 120L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Build 3.17: one launcher entry only; the theme comes from the saved preference (starting window is disabled).
        val savedLightTheme = getPreferences(MODE_PRIVATE).getBoolean("light_theme", false)
        setTheme(if (savedLightTheme) R.style.Theme_G6HiFiPlayer else R.style.Theme_G6HiFiPlayer_Dark)
        super.onCreate(savedInstanceState)
        AudioPathLog.init(this)   // Hifi 1.40
        lightTheme = savedLightTheme
        albumGridColumns = getPreferences(MODE_PRIVATE).getInt("album_grid_columns", 2).coerceIn(2, 3)
        playerSurface = if (lightTheme) LIGHT_SURFACE else DARK_SURFACE
        playerInk = if (lightTheme) Color.BLACK else Color.WHITE
        // Fully transparent status bar; icon colour follows the page ink.
        applySystemBars()
        activeInstance?.let { previous ->
            previous.player?.let { oldPlayer -> previous.playerListener?.let { oldPlayer.removeListener(it) } }
            previous.playerListener = null
        }
        activeInstance = this
        pruneArtworkDiskCache()
        PlaybackKeepAliveService.setPlaybackStoppedCallback { MainActivity.onServicePlaybackStopped() }
        player = PlaybackKeepAliveService.existingPlayer()
        getPreferences(MODE_PRIVATE).getString("diagnostic_events", "")?.split("\n\n")?.filter { it.isNotBlank() }?.takeLast(300)?.let { synchronized(diagnosticEvents) { diagnosticEvents.addAll(it) } }
        volumeControlStream = AudioManager.STREAM_MUSIC   // Hifi 1.34: the volume keys always move the music volume, never the ringer
        buildUi(); audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        PlaybackKeepAliveService.armExclusive(this) // Build 1.67: exclusive mode is always on; it follows the headphone jack
        createNotificationChannel()
        createMediaSession()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 303)
        refreshAudioInfo(); handler.post(ticker)
        getPreferences(MODE_PRIVATE).getString("music_tree_uri", null)?.let { savedTree ->
            restoreOrScanLibrary(Uri.parse(savedTree))
        }
    }

    private fun buildUi() {
        buildingUi = true
        // Build 3.01: newly-created Now Playing artwork needs its Hi-Res foreground rebound after a theme rebuild.
        hiResBadgeShown = false
        darkDrawables.clear()
        // Classic theme: the page is transparent and the softened cover is drawn behind it (see DarkBackdropDrawable).
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.TRANSPARENT) }
        pageRoot = root
        // The bottom bar has no background of its own: it shows the same surface as the page above it.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        navPlayer = navButton("Player", "Player") { show("player") }
        navLibrary = navButton("Albums", "Albums") { show("library") }
        navSongs = navButton("Songs", "Songs") { show("songs") }
        navFile = navButton("Setting", "Setting") { show("settings") }
        // Three-tab navigation: removing Songs reduces page-switch rebuild work.
        // Equal weights keep the existing icon size and center each remaining hit target.
        // Hifi 1.4: no bottom bar. Pages are switched by swiping; the nav views stay created (unattached) so existing state code is untouched.
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        pageScroll = scroll
        // Hifi 1.9: remember where the Album list was, so Back from an opened album (or returning from Now Playing) lands there.
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            if (currentPage == "library" && selectedAlbum == null && !restoringScroll) listScrollY = y
        }
        // Build 1.55: Now Playing is exactly as tall as the visible page, so the controls dock always sits at the bottom
        // (Adaptive and Classic). The old fixed "screen height - 150dp" guess could leave the dock at the top in Classic.
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val viewport = bottom - top - scroll.paddingBottom
            if (viewport > 0 && ::playerPanel.isInitialized && playerPanel.minimumHeight != viewport) {
                playerPanel.minimumHeight = viewport
                playerPanel.requestLayout()
            }
        }
        val statusBarInset = resources.getIdentifier("status_bar_height", "dimen", "android").let { id -> if (id > 0) resources.getDimensionPixelSize(id) else dp(24) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0) }
        playerPanel = TouchFrameLayout(this).apply {
            // Keep Now Playing swipe gestures on the artwork surface without stealing control touches.
            minimumHeight = (resources.displayMetrics.heightPixels - dp(150)).coerceAtLeast(dp(500))
        }
        libraryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        songsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        makePlayerPanel(); makeLibraryPanel(); makeSongsPanel(); makeMiniPlayer()
        songsPanel.setPadding(dp(16), statusBarInset + dp(6), dp(16), dp(8))
        settingsPanel.setPadding(0, statusBarInset + dp(6), 0, dp(8))
        val scanCard = card()
        scanCard.addView(text("MUSIC LIBRARY SCAN", 12f, MUTED, true))
        val scanButtons = buttonArea(false)
        scanButtons.addView(action("CHOOSE MUSIC FOLDER", true) { pickFolder() }, LinearLayout.LayoutParams(-1, dp(46)))
        scanCard.addView(scanButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        librarySummary = text("No folder has been scanned yet.", 12f, MUTED, false).apply { setPadding(0, dp(10), 0, 0) }
        scanCard.addView(librarySummary)
        settingsPanel.addView(scanCard)
        // Build 3.00: Light mirrors Dark composition; only backdrop veil and ink/surface palette invert.
        val themeCard = card()
        themeCard.addView(text("THEME", 12f, MUTED, true))
        val themeButtons = buttonArea()
        fillThemeButtons(themeButtons)
        themeCard.addView(themeButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        themeCard.addView(text("Dark uses a darkened album-art blur. Light is the mirror image: a light album-art blur, dark text, accent from the cover.", 11f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) })
        settingsPanel.addView(themeCard)
        // Build 1.44: the album column option lives here (it used to sit above the Albums grid).
        val columnsCard = card()
        columnsCard.addView(text("ALBUM COLUMNS", 12f, MUTED, true))
        val columnButtons = buttonArea()
        fillColumnButtons(columnButtons)
        columnsCard.addView(columnButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        settingsPanel.addView(columnsCard)
        // Build 1.44: Setting shows only the folder scan and the column option. The audio/file info panels
        // are still created (other code updates their text) but are no longer attached to the screen.
        content.addView(playerPanel); content.addView(libraryPanel)
        // Hifi 1.44b: the Hi-Res logo hangs above the title row, so no ancestor may cut it (this was the clipped top edge in 1.44).
        content.clipChildren = false; content.clipToPadding = false; playerPanel.clipChildren = false; playerPanel.clipToPadding = false
        scroll.clipChildren = false; scroll.clipToPadding = false
        scroll.addView(content)
        pageContentView = content
        // Queue overlay is a sibling of the page scroll, not nested inside Now Playing.
        // This gives its internal ScrollView independent gesture ownership and stable height.
        val contentStage = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val fadeHost = EdgeCutLayout(this)
        fadeHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scrollFadeHost = fadeHost
        contentStage.addView(fadeHost, FrameLayout.LayoutParams(-1, -1))
        queueMiniHost?.let { contentStage.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        root.addView(makeHeaderBar(statusBarInset), LinearLayout.LayoutParams(-1, statusBarInset + dp(HEADER_H_DP)))
        root.addView(contentStage, LinearLayout.LayoutParams(-1, 0, 1f))
        val stage = FrameLayout(this).apply { setBackgroundColor(if (lightTheme) LIGHT_SURFACE else DARK_SURFACE) }
        stage.addView(View(this).apply { background = newDarkDrawable() }, FrameLayout.LayoutParams(-1, -1))
        // Hifi 1.43: Now Playing only: the cover itself, sharp, fills the top half of the screen behind the header and fades into the blurred backdrop.
        val coverView = NowPlayingCoverView(this).apply { bitmap = playerCoverBitmap; visibility = View.GONE }
        playerCoverView = coverView
        coverView.topInset = 0   // Hifi 1.49: cover and fade go up to the very top edge (behind the status bar)
        stage.addView(coverView, FrameLayout.LayoutParams(-1, -1))
        stage.addView(root, FrameLayout.LayoutParams(-1, -1))
        // Build 1.55: in Classic the page draws behind the (transparent) Android navigation bar; the app's own bottom bar
        // is lifted by the real bar height so its buttons stay tappable.
        stage.setOnApplyWindowInsetsListener { _, insets ->
            val bottomInset = insets.systemWindowInsetBottom
            // Hifi 1.5: the page runs down to the real navigation bar; the inset is only padding inside the scroller and queue.
            bottomInsetPx = bottomInset
            if (scroll.paddingBottom != bottomInset) scroll.setPadding(0, 0, 0, bottomInset)
            queueMiniHost?.let { if (it.paddingBottom != bottomInset + dp(8)) it.setPadding(dp(16), 0, dp(16), bottomInset + dp(8)) }
            updateBottomFade()
            insets
        }
        var startupSplash: FrameLayout? = null
        var splashLogo: ImageView? = null
        if (!startupSplashShown) {
            val splash = FrameLayout(this).apply {
                setBackgroundColor(if (lightTheme) LIGHT_SURFACE else DARK_SURFACE)
                isClickable = true
                isFocusable = true
            }
            val logo = ImageView(this).apply {
                // Build 3.25: splash is the text "PLAY / PURE / DIRECT" (Thin sans). Dark theme = white text on black, light theme = dark text on white.
                setImageResource(if (lightTheme) R.drawable.splash_text_light else R.drawable.splash_text_dark)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Play Pure Direct"
            }
            splashLogo = logo
            // The word DIRECT spans 48% of the screen width; the image (715x900) has 20px transparent padding per side around the 675px-wide word.
            val splashLogoWidth = (resources.displayMetrics.widthPixels * 0.48f * (715f / 675f)).toInt()
            val splashLogoHeight = (splashLogoWidth * (900f / 715f)).toInt()
            splash.addView(logo, FrameLayout.LayoutParams(splashLogoWidth, splashLogoHeight, Gravity.CENTER))
            stage.addView(splash, FrameLayout.LayoutParams(-1, -1))
            startupSplashShown = true
            startupSplash = splash
        }
        setContentView(stage)
        startupSplash?.let { splash ->
            // Start the ~2-second minimum after the first frame is ready, then fade smoothly.
            stage.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (stage.viewTreeObserver.isAlive) stage.viewTreeObserver.removeOnPreDrawListener(this)
                    handler.postDelayed({
                        if (!isFinishing && !isDestroyed && splash.parent === stage) {
                            // Build 3.25: soft Claude-style exit. The text drifts out first (fades + grows 4%), then the background dissolves into the app.
                            val ease = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                            splashLogo?.animate()
                                ?.alpha(0f)?.scaleX(1.04f)?.scaleY(1.04f)
                                ?.setDuration(520L)
                                ?.setInterpolator(ease)
                                ?.start()
                            splash.animate()
                                .alpha(0f)
                                .setStartDelay(120L)
                                .setDuration(780L)
                                .setInterpolator(ease)
                                .withEndAction { if (splash.parent === stage) stage.removeView(splash) }
                                .start()
                        }
                    }, 1500L)
                    return true
                }
            })
        }
        stage.requestApplyInsets()
        buildingUi = false
        show("player"); renderLibrary(); restoreNowPlayingMetadata(); updateHiResBadges()
    }


    /**
     * Rebind the selected track to newly-created Now Playing views after a UI/theme rebuild.
     * buildUi() recreates TextViews, so the live player can keep playing while its metadata
     * would otherwise be left blank.
     */
    private fun restoreNowPlayingMetadata() {
        if (!::titleView.isInitialized || !::artistView.isInitialized || !::albumView.isInitialized ||
            !::seek.isInitialized || !::elapsed.isInitialized || !::duration.isInitialized) return
        if (currentIndex !in tracks.indices) {
            if (player == null) {
                titleView.text = "Nothing playing"
                artistView.text = ""
                albumView.text = ""
                elapsed.text = "0:00"
                duration.text = "0:00"
                seek.max = 1
                seek.progress = 0
            }
            return
        }
        val track = tracks[currentIndex]
        titleView.text = track.title
        artistView.text = track.artist
        albumView.text = track.album
        val position = try { player?.currentPosition?.toInt()?.coerceAtLeast(0) ?: 0 } catch (_: Exception) { 0 }
        val liveDuration = try {
            player?.duration?.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt()
        } catch (_: Exception) { null }
        if (liveDuration != null) durationMs = liveDuration
        seek.max = durationMs.coerceAtLeast(1)
        seek.progress = position.coerceIn(0, seek.max)
        elapsed.text = time(position)
        duration.text = time(durationMs)
        syncPlaybackIcon()
        loadArtwork(track)
    }


    /** Common typography, weight, theme-aware color and left edge for every page header. */
    private fun pageHeader(label: String, leftInsetDp: Int = 4) = text(
        label, 18f, WHITE, true
    ).apply {
        typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding(dp(leftInsetDp), 0, dp(8), 0)
    }

    /**
     * Explicit rounded progress track. The native SeekBar's minimumHeight does not change
     * its thin drawable on Android 9, and a custom ClipDrawable previously failed to render.
     */
    private class RoundedProgressDrawable(
        private val trackColor: Int,
        private val playedColor: Int,
        private val thicknessPx: Int
    ) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val thickness = thicknessPx.coerceAtLeast(1).coerceAtMost(b.height())
            val top = b.top + (b.height() - thickness) / 2f
            val bottom = top + thickness
            val radius = thickness / 2f
            rect.set(b.left.toFloat(), top, b.right.toFloat(), bottom)
            paint.color = trackColor
            paint.alpha = android.graphics.Color.alpha(trackColor)
            canvas.drawRoundRect(rect, radius, radius, paint)
            val fraction = level.coerceIn(0, 10000) / 10000f
            val playedRight = b.left + b.width() * fraction
            if (playedRight > b.left) {
                rect.set(b.left.toFloat(), top, playedRight, bottom)
                paint.color = playedColor
                paint.alpha = android.graphics.Color.alpha(playedColor)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }

        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicHeight(): Int = thicknessPx
        override fun getIntrinsicWidth(): Int = -1
    }

    private fun makePlayerPanel() {
        // Build 2.0: Dark theme only. The page backdrop is shared (see DarkBackdropDrawable); Now Playing shows the cover in a small box.
        playerPanel.setBackgroundColor(Color.TRANSPARENT)

        artPlaceholder = text("NO MUSIC PLAYING", 22f, WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(220))
            themed { background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) }
        }

        // Hifi 1.4: the header bar sits above the page, so the page itself needs no status-bar inset.
        val topInset = dp(4)
        // The "no cover" / "no music" label sits in the middle of the page above the controls.
        playerPanel.addView(artPlaceholder, FrameLayout.LayoutParams(-1, -1))

        // Build 1.44: no status-bar scrim. The system bar is fully transparent and the pages show their own surface.

        // Build 2.0: two single-line rows. Each one scrolls sideways (marquee) when it is wider than the screen.
        audioInfoInline = text("FLAC • — kHz → — kHz", 10f, MUTED, false).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(0, dp(2), 0, 0)
            visibility = View.VISIBLE
        }
        audioStatusInline = text("BIT-PERFECT NOT CONFIRMED", 10f, MUTED, false).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(0, 0, 0, dp(4))
            visibility = View.VISIBLE
        }
        // Hifi 1.41: the title is always exactly one line (a long title is cut with "..." at the queue button), the same padding as when a track
        // starts, and the artist is one line too, so the cover size and the positions of title, queue button and the rest never move.
        titleView = text("", 19f, WHITE, true).apply {
            setPadding(0, dp(2), 0, dp(1))
            // (no isSingleLine = false here: that call resets maxLines to "unlimited", which let long titles take 2+ lines in 1.37 - 1.41)
            minLines = 1; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        artistView = text("", 15f, MUTED, false).apply {
            minLines = 1; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        albumView = text("", 13f, MUTED, false).apply {
            setPadding(0, dp(2), 0, 0); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // Hifi 1.48: the Hi-Res logo (24-bit tracks) sits on the right, level with the title / artist / album block (centred on its height); the text
        // always leaves room for it, so the lines never shift. The queue button moved to the bottom centre, below the info lines.
        val logoSide = (resources.displayMetrics.widthPixels * 0.09f).toInt()
        val metadata = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SEEK_SIDE_PAD_DP), 0, dp(SEEK_SIDE_PAD_DP) + logoSide + dp(10), 0)
        }
        val queueIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_queue_24)
            // Hifi 1.55: exactly the colour of the unplayed part of the progress bar (same ARGB incl. its opacity), also after a theme switch
            imageTintList = ColorStateList.valueOf(MUTED)   // Hifi 1.56: the grey text colour of the theme, no extra opacity
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        queueButton = queueIcon
        val queueBtn = FrameLayout(this).apply {
            contentDescription = "Queue"
            isClickable = true; isFocusable = true
            setOnClickListener { openQueueWindow() }
            addView(queueIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))   // Hifi 1.55: icon 22 -> 24dp, the 56 x 40dp touch area is unchanged
        }
        val titleRow = FrameLayout(this)
        playerTitleRow = titleRow
        titleRow.addView(titleView, FrameLayout.LayoutParams(-1, -2))
        val hiResLogo = ImageView(this).apply {
            setImageResource(R.drawable.hires_logo)
            scaleType = ImageView.ScaleType.FIT_END
            contentDescription = "Hi-Res audio"
            visibility = View.GONE
        }
        hiResLogoView = hiResLogo
        metadata.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        metadata.addView(artistView, LinearLayout.LayoutParams(-1, -2))
        metadata.addView(albumView, LinearLayout.LayoutParams(-1, -2))
        val metaFrame = FrameLayout(this)
        metaFrame.addView(metadata, FrameLayout.LayoutParams(-1, -2))
        metaFrame.addView(hiResLogo, FrameLayout.LayoutParams(logoSide, logoSide, Gravity.END or Gravity.TOP).apply { rightMargin = dp(SEEK_SIDE_PAD_DP); topMargin = dp(2) })   // Hifi 1.49: top level with the title

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(SEEK_SIDE_PAD_DP), 0, dp(SEEK_SIDE_PAD_DP), 0)
        }
        elapsed = text("0:00", 11f, MUTED, false)
        duration = text("0:00", 11f, MUTED, false)
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(duration, LinearLayout.LayoutParams(-2, -2))

        seek = SeekBar(this).apply {
            // Keep Android's native SeekBar drawable: it renders the played segment reliably on API 28.
            // The previous custom ClipDrawable made the Now Playing progress invisible.
            max = durationMs.coerceAtLeast(1)
            progress = (player?.currentPosition?.toInt() ?: 0).coerceIn(0, max)
            // Hifi 1.43: no thumb; the track and played segment share the same rounded path, now 4dp thick (was 2dp).
            progressDrawable = RoundedProgressDrawable(if (lightTheme) LIGHT_PROGRESS_TRACK_GREY else DARK_PROGRESS_TRACK, accentColor, dp(PROGRESS_THICKNESS_DP))
            thumb = null
            thumbOffset = 0
            splitTrack = false
            minimumHeight = dp(28)
            setPadding(dp(SEEK_SIDE_PAD_DP), 0, dp(SEEK_SIDE_PAD_DP), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = time(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (prepared && bar != null) player?.seekTo(bar.progress.coerceIn(0, durationMs).toLong())
                    seeking = false
                }
            })
        }

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(DOCK_SIDE_PAD_DP), dp(6), dp(DOCK_SIDE_PAD_DP), dp(6))
        }
        // Build 2.11: hit areas are back to the Build 2.8 sizes (52dp / 60dp) so every position is the same as in 2.8, but the
        // icon itself now fills the whole area (FIT_CENTER, no padding). Before, CENTER_INSIDE kept the 24dp vector at 24dp
        // whatever the area was, so Builds 2.9 and 2.10 only enlarged the invisible area.
        // Build 2.13: all three icons shrink together. Play/pause: 60dp area - 2*11dp = 38dp icon (was 42dp in 2.12). Previous/Next:
        // 52dp area - 2*10dp = 32dp icon (were 52dp in 2.12, so only Play had changed). The hit areas stay 60dp / 52dp, so the
        // centres (positions) do not move; the difference is only padding around the icons.
        prevView = transportButton(R.drawable.ic_previous_24, "Previous", SIDE_ICON_PAD_DP) { playPrevious() }
        playView = transportButton(R.drawable.ic_play_24, "Play or pause", PLAY_ICON_PAD_DP) { togglePlayback() }
        nextView = transportButton(R.drawable.ic_next_24, "Next", SIDE_ICON_PAD_DP) { playNext() }
        syncPlaybackIcon()
        transport.clipChildren = false
        transport.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, 1, 1.3f))
        transport.addView(prevView, LinearLayout.LayoutParams(dp(TRANSPORT_SIDE_DP), dp(TRANSPORT_SIDE_DP)))
        transport.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, 1, 0.5f))
        transport.addView(playView, LinearLayout.LayoutParams(dp(TRANSPORT_PLAY_DP), dp(TRANSPORT_PLAY_DP)))
        transport.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, 1, 0.5f))
        transport.addView(nextView, LinearLayout.LayoutParams(dp(TRANSPORT_SIDE_DP), dp(TRANSPORT_SIDE_DP)))
        transport.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, 1, 1.3f))

        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(DOCK_TOP_PAD_DP), 0, dp(12))
            clipChildren = false; clipToPadding = false   // Hifi 1.44: the Hi-Res logo hangs above the title row
        }
        playerBottomDock = bottomDock
        // Build 1.66: measured positions of the control dock, so Classic and Adaptive can be compared from the diagnostics.
        var lastDockLog = Int.MIN_VALUE
        bottomDock.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            if (kotlin.math.abs(top - lastDockLog) > 2) {
                lastDockLog = top
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val seekLoc = IntArray(2); seek.getLocationOnScreen(seekLoc)
                recordDiagnostic("DOCK_LAYOUT theme=dark" +
                    " dockTopOnScreen=" + loc[1] + " dockHeight=" + (bottom - top) + " seekTopOnScreen=" + seekLoc[1] +
                    " panelMinHeight=" + playerPanel.minimumHeight + " rootPaddingBottom=" + (pageRoot?.paddingBottom ?: -1) +
                    " screenHeight=" + resources.displayMetrics.heightPixels)
            }
        }
        // Track details and transport controls stay together; diagnostics remain below the controls.
        bottomDock.addView(metaFrame, LinearLayout.LayoutParams(-1, -2))
        // Build 2.7: even, slightly larger gaps: cover - title/artist - progress - controls - info.
        bottomDock.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(PROGRESS_TOP_GAP_DP) + dp(META_RAISE_DP) - dp(4) })   // Hifi 1.50: extra gap = title block rides up, progress and below stay
        bottomDock.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(1) })   // Hifi 1.51: time labels 4dp higher above the bar (title block, bar and below unchanged)
        bottomDock.addView(transport, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(DOCK_GAP_DP - 2) })
        // Hifi 1.49: the queue button now sits right under the transport controls, the info lines are back at the very bottom.
        bottomDock.addView(queueBtn, LinearLayout.LayoutParams(dp(56), dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = -dp(2) })   // Hifi 1.51: queue button 4dp higher
        bottomDock.addView(audioInfoInline, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(DOCK_SIDE_PAD_DP); rightMargin = dp(DOCK_SIDE_PAD_DP); topMargin = dp(14)   // Hifi 1.52: +4dp: everything above the info rides up 4dp, the info stays
            gravity = Gravity.CENTER_HORIZONTAL
        })
        bottomDock.addView(audioStatusInline, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(DOCK_SIDE_PAD_DP); rightMargin = dp(DOCK_SIDE_PAD_DP); bottomMargin = dp(2)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Hifi 1.43: no album art box: the cover is the clear top of the page backdrop (see NowPlayingCoverView); only the controls column is left.
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, topInset, 0, 0)
            clipChildren = false; clipToPadding = false   // Hifi 1.44: the Hi-Res logo hangs above the title row
        }
        // Build 2.1: the column sits lower on the page (anchored to the bottom) with a larger gap under the cover.
        stack.setPadding(0, topInset, 0, dp(STACK_BOTTOM_PAD_DP))
        stack.addView(bottomDock, LinearLayout.LayoutParams(-1, -2))
        playerPanel.addView(stack, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        statusView = text("Choose a music folder or audio file to begin.", 14f, WHITE, false)
        // The no-track state is represented by the accent-colored artwork area, not the title field.
        // The views were just rebuilt: paint them with the current surface (default colour until a cover arrives).
        appliedPlayerSurface = null
        applyPlayerPalette(playerSurface)
    }

    /** Dominant colour of a cover: the most common colour family, counting saturated pixels more than greys. */
    private fun dominantArtworkColor(bitmap: android.graphics.Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || bitmap.isRecycled) return DEFAULT_ACCENT
        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        val weightSum = FloatArray(4096)
        val rSum = FloatArray(4096)
        val gSum = FloatArray(4096)
        val bSum = FloatArray(4096)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 128) {
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val mx = maxOf(r, g, b)
                    val mn = minOf(r, g, b)
                    val saturation = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
                    val weight = 0.25f + saturation
                    val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    weightSum[bucket] += weight
                    rSum[bucket] += r * weight
                    gSum[bucket] += g * weight
                    bSum[bucket] += b * weight
                }
                x += stepX
            }
            y += stepY
        }
        var best = -1
        var bestWeight = 0f
        for (i in 0 until 4096) {
            if (weightSum[i] > bestWeight) { bestWeight = weightSum[i]; best = i }
        }
        if (best < 0) return DEFAULT_ACCENT
        return Color.rgb(
            (rSum[best] / bestWeight).toInt().coerceIn(0, 255),
            (gSum[best] / bestWeight).toInt().coerceIn(0, 255),
            (bSum[best] / bestWeight).toInt().coerceIn(0, 255)
        )
    }

    /** Dominant colour of a cover, remembered by track so a repeat play does not sample the bitmap again. */
    private fun dominantColorFor(key: String?, bitmap: android.graphics.Bitmap, remember: Boolean): Int {
        if (key != null) dominantColorCache[key]?.let { return it }
        val color = dominantArtworkColor(bitmap)
        if (key != null && remember) {
            if (dominantColorCache.size > 400) dominantColorCache.clear()
            dominantColorCache[key] = color
        }
        return color
    }

    private fun relativeLuminance(color: Int): Float {
        fun channel(value: Int): Float {
            val s = value / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * channel(Color.red(color)) + 0.7152f * channel(Color.green(color)) + 0.0722f * channel(Color.blue(color))
    }

    /**
     * Same light/dark rule as the Android media notification (ContrastColorUtil.isColorLight):
     * the surface counts as light only when its luminance is above 0.5; then the ink is near-black,
     * otherwise white. Mid-tone covers therefore get white ink, like the notification.
     */
    private fun inkFor(surface: Int): Int =
        if (relativeLuminance(surface) > 0.5f) 0xFF14141C.toInt() else Color.WHITE

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun mixColors(from: Int, to: Int, fraction: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * fraction).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(from), Color.red(to)), ch(Color.green(from), Color.green(to)), ch(Color.blue(from), Color.blue(to)))
    }

    /** Runs [block] now and again whenever the page palette changes (see [retintPages]). */
    private fun <T : View> T.themed(block: T.() -> Unit): T {
        block()
        @Suppress("UNCHECKED_CAST")
        val stored: (View) -> Unit = { v -> (v as T).block() }
        themers.getOrPut(this) { mutableListOf() }.add(stored)
        return this
    }

    /** Re-applies every themed colour (backgrounds, ripples, text) of the pages that are built. */
    private fun retintPages() {
        tabsPaletteDirty = false
        val snapshot = themers.entries.map { it.key to it.value.toList() }
        for ((view, blocks) in snapshot) {
            if (!view.isAttachedToWindow) continue
            blocks.forEach { it(view) }
        }
        refreshNav()
    }

    private fun refreshNav() {
        if (!::navPlayer.isInitialized || !::navLibrary.isInitialized || !::navFile.isInitialized) return
        navState(navPlayer, currentPage == "player")
        navState(navLibrary, currentPage == "library")
        navState(navFile, currentPage == "settings" || currentPage == "audio" || currentPage == "file")
    }

    /** Status/navigation bars follow the page surface so no black strip is left at the bottom. */
    private fun applySystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (lightTheme) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }

    /**
     * Hifi 2.1: switches the accent (progress, playing marks, ripples, play icon of the mini player) and the dark window colour to the ones of the
     * cover. Pages that are built are re-tinted at once (or when Now Playing is left). Light keeps the old purple and surfaces.
     */
    private fun applyAccent(result: AlbumAccent.Result) {
        if (result.accent == albumAccent && result.dark == albumDark && result.lightAccent == albumAccentLight && result.lightTint == albumTint) return
        albumAccent = result.accent
        albumDark = result.dark
        albumAccentLight = result.lightAccent
        albumTint = result.lightTint
        darkDrawables.forEach { it.veil = backdropVeil() }
        if (::seek.isInitialized) {
            seek.progressDrawable = RoundedProgressDrawable(if (lightTheme) LIGHT_PROGRESS_TRACK_GREY else DARK_PROGRESS_TRACK, accentColor, dp(PROGRESS_THICKNESS_DP))
            seek.invalidate()
        }
        updateAlbumPlayingMarks(try { player?.isPlaying == true } catch (_: Exception) { false })
        if (buildingUi) return
        if (currentPage == "player") tabsPaletteDirty = true else retintPages()
    }

    /** Dark theme palette: black page, white and grey text, accent from the cover (Hifi 2.1). Only the backdrop follows the cover. */
    private fun applyPlayerPalette(@Suppress("UNUSED_PARAMETER") requestedSurface: Int) {
        if (!::playerBottomDock.isInitialized) return
        val targetSurface = if (lightTheme) LIGHT_SURFACE else DARK_SURFACE
        if (appliedPlayerSurface == targetSurface) return
        appliedPlayerSurface = targetSurface
        playerSurface = targetSurface
        playerInk = if (lightTheme) Color.BLACK else Color.WHITE
        val ink = playerInk
        val inkMuted = MUTED
        playerBottomDock.background = null
        pageRoot?.setBackgroundColor(Color.TRANSPARENT)
        applySystemBars()
        if (::titleView.isInitialized) titleView.setTextColor(ink)
        if (::artistView.isInitialized) artistView.setTextColor(inkMuted)
        if (::albumView.isInitialized) albumView.setTextColor(inkMuted)
        if (::elapsed.isInitialized) elapsed.setTextColor(inkMuted)
        if (::duration.isInitialized) duration.setTextColor(inkMuted)
        if (::audioInfoInline.isInitialized) audioInfoInline.setTextColor(inkMuted)
        if (::audioStatusInline.isInitialized) audioStatusInline.setTextColor(inkMuted)
        if (::prevView.isInitialized) prevView.imageTintList = ColorStateList.valueOf(ink)
        if (::nextView.isInitialized) nextView.imageTintList = ColorStateList.valueOf(ink)
        queueButton?.imageTintList = ColorStateList.valueOf(MUTED)   // Hifi 1.56: grey text colour of the theme
        if (::seek.isInitialized) {
            // Hifi 1.43: no thumb in both themes; only the rounded progress track is visible.
            seek.progressDrawable = RoundedProgressDrawable(if (lightTheme) LIGHT_PROGRESS_TRACK_GREY else DARK_PROGRESS_TRACK, accentColor, dp(PROGRESS_THICKNESS_DP))
            seek.invalidate()
        }
        // Build 3.01: keep the existing glow geometry, alpha stops and animation; only switch its ink for Light.
        if (::prevView.isInitialized) prevView.background = GlowDrawable(dp(GLOW_EXTRA_DP).toFloat(), lightTheme)
        if (::playView.isInitialized) {
            playView.background = GlowDrawable(dp(GLOW_EXTRA_DP).toFloat(), lightTheme)
            syncPlaybackIcon()
        }
        if (::nextView.isInitialized) nextView.background = GlowDrawable(dp(GLOW_EXTRA_DP).toFloat(), lightTheme)
        // The bottom bar and the system bars change at once; the lists are repainted only when a tab is visible.
        if (buildingUi) return
        refreshNav()
        if (currentPage == "player") tabsPaletteDirty = true else retintPages()
    }

    private fun makeLibraryPanel() {
        val inset = resources.getIdentifier("status_bar_height", "dimen", "android").let { id -> if (id > 0) resources.getDimensionPixelSize(id) else dp(24) }
        // Match the inner horizontal content edge used by the Queue card (16dp).
        libraryPanel.setPadding(dp(16), dp(5), dp(16), dp(88))   // Hifi 1.36: 112 -> 88dp, the last row rests just above the mini player card (inside the soft fade)
        // Hifi 2.4: the "No folder has been scanned yet" / "N tracks" line is gone from the page (kept hidden for the code that sets its text);
        // the scan progress now lives in the header, to the right of the "Album" title (see makeHeaderBar).
        albumScanSummary = text("", 12f, MUTED, false).apply { visibility = View.GONE }
        albumRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, 0) }
        libraryPanel.addView(albumRows, LinearLayout.LayoutParams(-1, -2))
    }



    /** Dark and Light share identical geometry; switching rebuilds the UI, never the player. */
    private fun fillThemeButtons(container: LinearLayout) {
        container.removeAllViews()
        container.addView(action("DARK", !lightTheme) { setLightTheme(false) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        container.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        container.addView(action("LIGHT", lightTheme) { setLightTheme(true) }, LinearLayout.LayoutParams(0, dp(40), 1f))
    }

    private fun setLightTheme(enabled: Boolean) {
        if (lightTheme == enabled) return
        lightTheme = enabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("light_theme", enabled).apply()
        appliedPlayerSurface = null
        playerSurface = if (enabled) LIGHT_SURFACE else DARK_SURFACE
        playerInk = if (enabled) Color.BLACK else Color.WHITE
        applySystemBars()
        buildUi()
        G6Widgets.renderAll(this)
    }

    /** Cover for the Classic backdrop: scaled to ~96 px and box-blurred (see [BackdropBlur], shared with the blur widget). */
    private fun softCoverForBackdrop(source: android.graphics.Bitmap): android.graphics.Bitmap? = BackdropBlur.soft(source)

    private fun newDarkDrawable(): DarkBackdropDrawable =
        DarkBackdropDrawable(lightTheme).also { it.bitmap = darkBackdropBitmap; it.veil = backdropVeil(); darkDrawables.add(it) }

    /** Called with the cover that is on screen (or null when the track has none); repaints every Classic backdrop. */
    private fun updateDarkBackdrop(source: android.graphics.Bitmap?) {
        if (source != null && source === darkBackdropSource?.get() && darkBackdropBitmap != null) return
        darkBackdropSource = source?.let { java.lang.ref.WeakReference(it) }
        darkBackdropBitmap = source?.let { softCoverForBackdrop(it) }
        darkDrawables.forEach { it.bitmap = darkBackdropBitmap }
    }

    /**
     * Classic theme page background: the softened cover filling the screen (centre-crop), a black veil so the
     * page reads as "semi black".
     */
    private class DarkBackdropDrawable(private val light: Boolean) : android.graphics.drawable.Drawable() {
        var bitmap: android.graphics.Bitmap? = null
            set(value) { field = value; lightAlpha = BackdropBlur.lightVeilAlpha(value); invalidateSelf() }
        private var lightAlpha = 0xB3   // Hifi 2.8: Light veil is stronger over a mostly black cover
        var veil: Int = 0xB3000000.toInt()
            set(value) { field = value; invalidateSelf() }
        private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val srcRect = android.graphics.Rect()

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            canvas.drawColor(if (light) LIGHT_SURFACE else DARK_SURFACE)
            bitmap?.takeIf { !it.isRecycled }?.let { bmp ->
                val scale = maxOf(b.width() / bmp.width.toFloat(), b.height() / bmp.height.toFloat())
                val sw = (b.width() / scale).toInt().coerceIn(1, bmp.width)
                val sh = (b.height() / scale).toInt().coerceIn(1, bmp.height)
                val sx = (bmp.width - sw) / 2
                val sy = (bmp.height - sh) / 2
                srcRect.set(sx, sy, sx + sw, sy + sh)
                canvas.drawBitmap(bmp, srcRect, b, bitmapPaint)
            }
            canvas.drawColor(if (light) ((lightAlpha shl 24) or 0x00FFFFFF) else veil)   // Hifi 1.53: veil 60% -> 70%; Hifi 1.36: veil 50% -> 60% (still one flat layer, no gradient)
        }

        override fun setAlpha(alpha: Int) { }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    /**
     * Hifi 1.44: the Now Playing cover is the whole picture, fitted to the full screen width (no left/right cut), sitting right above the title row.
     * It is sharp and plain (no veil, no extra layer). Its top and bottom edges fade completely into the blurred backdrop behind it, so the blur
     * closes both edges exactly; whatever room is left above it (status bar / header) is the blur too. The bottom fade ends at the top of
     * the title row (the header gap is ignored); the Hi-Res logo and queue button are drawn in front of it.
     */
    private class NowPlayingCoverView(context: Context) : View(context) {
        var topInset = 0
        var bitmap: android.graphics.Bitmap? = null
            set(value) { field = value; invalidate() }
        private val bitmapPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val maskPaint = android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        private val dstRect = android.graphics.RectF()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val bmp = bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 } ?: return
            val w = width.toFloat(); val viewH = height.toFloat()
            if (w <= 0f || viewH <= 0f) return
            val coverH = w * bmp.height / bmp.width          // whole picture, fitted to the width
            val top = topInset.toFloat()                     // Hifi 1.46: starts under the status bar (hard cut, the status bar shows the plain backdrop)
            val bottom = top + coverH
            val save = canvas.saveLayer(0f, top, w, bottom, null)
            dstRect.set(0f, top, w, bottom)
            canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
            // Hifi 1.46: much softer fade: it starts earlier (the lower 50% of the cover) and follows a smootherstep curve with many stops,
            // so there is no visible start or end of the gradient.
            val start = 1f - BOTTOM_FADE
            val n = 16
            val colors = IntArray(n + 2); val pos = FloatArray(n + 2)
            colors[0] = -0x1000000; pos[0] = 0f
            for (i in 0..n) {
                val t = i / n.toFloat()
                val sm = t * t * t * (t * (t * 6f - 15f) + 10f)
                colors[i + 1] = (Math.round(255f * (1f - sm)) shl 24)
                pos[i + 1] = start + (1f - start) * t
            }
            pos[0] = 0f
            maskPaint.shader = android.graphics.LinearGradient(
                0f, top, 0f, bottom, colors, pos, android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, top, w, bottom, maskPaint)
            canvas.restoreToCount(save)
        }

        companion object {
            const val BOTTOM_FADE = 0.40f  // share of the cover height used to fade its bottom into the blur
        }
    }

    /** The 2/3 column buttons shown in Setting. Changing the choice repaints only the buttons and the album grid. */
    private fun fillColumnButtons(container: LinearLayout) {
        container.removeAllViews()
        container.addView(action("2 COLUMNS", albumGridColumns == 2) { setAlbumColumns(2, container) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        container.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        container.addView(action("3 COLUMNS", albumGridColumns == 3) { setAlbumColumns(3, container) }, LinearLayout.LayoutParams(0, dp(40), 1f))
    }

    private fun setAlbumColumns(columns: Int, container: LinearLayout) {
        if (albumGridColumns == columns) return
        albumGridColumns = columns
        getPreferences(MODE_PRIVATE).edit().putInt("album_grid_columns", columns).apply()
        fillColumnButtons(container)
        renderLibrary()
    }

    private fun makeSongsPanel() {
        val heading = flatSection().apply { setPadding(0, 0, 0, dp(12)) }
         songSearchInput = EditText(this).apply {
            hint = "Search songs, artists, albums"
            textSize = 14f
            themed { setTextColor(WHITE); setHintTextColor(MUTED); background = shape(LINE, 14) }
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setText(songSearchQuery)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    songSearchQuery = text?.toString().orEmpty()
                    renderSongs()
                }
                override fun afterTextChanged(text: android.text.Editable?) {}
            })
        }
        heading.addView(songSearchInput, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })
        songRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        heading.addView(songRows)
        songsPanel.addView(heading)
    }

    /** Hifi 1.38: builds the Album mini player and the (empty) queue row list; the queue itself is the floating window of openQueueWindow(). */
    private fun makeMiniPlayer() {
        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            // Hifi 1.36: same background as the floating windows (Setting, album options), 12dp corners; the Album list fades softly behind it (as in 1.20).
            themed { background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), floatingBackground(), null) }
            isClickable = true
            isFocusable = true
            setOnClickListener { showPlayerAnimated() }
        }
        queueMiniArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            themed { background = shape(ART, 8) }
            roundCorners(8)   // Hifi 1.21: the picture itself is clipped, the background alone did not round it
            contentDescription = "Currently playing artwork"
        }
        mini.addView(queueMiniArt, LinearLayout.LayoutParams(dp(52), dp(52)))
        val miniInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        queueMiniTitle = text("Nothing playing", 13f, WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        queueMiniArtist = text("", 11f, MUTED, false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        queueMiniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1
            progress = 0
            themed {
                progressTintList = ColorStateList.valueOf(accentColor)
                progressBackgroundTintList = ColorStateList.valueOf(withAlpha(accentColor, 0x40))
            }
        }
        miniInfo.addView(queueMiniTitle)
        miniInfo.addView(queueMiniArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        miniInfo.addView(queueMiniProgress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(7) })
        mini.addView(miniInfo, LinearLayout.LayoutParams(0, -2, 1f))
        queueMiniPlayView = ImageView(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            themed { imageTintList = ColorStateList.valueOf(accentColor) }
            setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause_s else R.drawable.ic_play_s)
            contentDescription = if (player?.isPlaying == true) "Pause" else "Play"
            isClickable = true
            isFocusable = true
            themed { background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x55)), shape(Color.TRANSPARENT, 24), null) }
            setOnClickListener { togglePlayback() }
        }
        mini.addView(queueMiniPlayView, LinearLayout.LayoutParams(dp(44), dp(48)))
        queueRowsSig = Int.MIN_VALUE
        // The rows of the queue window: they are moved into the floating window while it is open (see openQueueWindow).
        queueRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        // The mini player lives on the Album page, at the bottom (see show()).
        queueMiniHost = FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), bottomInsetPx + dp(8))
            visibility = View.GONE
            addView(mini, FrameLayout.LayoutParams(-1, -2))
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) post { updateBottomFade() }
            }
        }
        updateQueueMiniPlayer()
    }

    /** Hifi 1.36: the mini player is not part of the queue view any more, so the queue has no animation for it (kept as an empty hook). */
    @Suppress("UNUSED_PARAMETER")
    private fun updateQueueMiniPlayer() {
        if (!::queueMiniTitle.isInitialized) return
        if (currentIndex !in tracks.indices) {
            queueMiniTitle.text = "Nothing playing"
            queueMiniArtist.text = "Choose a track to begin"
            queueMiniArt.setImageDrawable(null)
            queueMiniProgress.max = 1
            queueMiniProgress.progress = 0
            return
        }
        val track = tracks[currentIndex]
        queueMiniTitle.text = track.title
        queueMiniArtist.text = track.artist
        val total = try { player?.duration?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: durationMs } catch (_: Exception) { durationMs }
        queueMiniProgress.max = total.coerceAtLeast(1)
        queueMiniProgress.progress = try { player?.currentPosition?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()?.coerceIn(0, queueMiniProgress.max) ?: 0 } catch (_: Exception) { 0 }
        loadArtworkInto(queueMiniArt, track)
    }



    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_FOLDER)
    }

    @Deprecated("Kept for Android 9 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == REQUEST_FOLDER) {
            try {
                val flag = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (flag != 0) contentResolver.takePersistableUriPermission(uri, flag)
            } catch (_: Exception) { }
            getPreferences(MODE_PRIVATE).edit().putString("music_tree_uri", uri.toString()).apply()
            try { File(cacheDir, "library-cache.json").delete() } catch (_: Exception) { }
            visibleTrackLimit = 80
            scanMusicFolder(uri); show("library"); return
        }
    }


    private fun recordDiagnostic(event: String, error: Throwable? = null) {
        val timestamp = System.currentTimeMillis()
        val detail = buildString {
            append(timestamp).append(" [").append(Thread.currentThread().name).append("] ").append(event)
            if (error != null) append("\n").append(android.util.Log.getStackTraceString(error))
        }
        val snapshot = synchronized(diagnosticEvents) {
            diagnosticEvents.add(detail)
            while (diagnosticEvents.size > 300) diagnosticEvents.removeAt(0)
            diagnosticEvents.joinToString("\n\n").takeLast(30000)
        }
        try { getPreferences(MODE_PRIVATE).edit().putString("diagnostic_events", snapshot).apply() } catch (_: Exception) { }
        if (error == null) android.util.Log.i("G6HiFiPlayer", event) else android.util.Log.e("G6HiFiPlayer", event, error)
    }

    private fun restoreOrScanLibrary(treeUri: Uri) {
        Thread({
            var cached: List<Track>? = null
            try {
                val cacheFile = File(cacheDir, "library-cache.json")
                if (cacheFile.isFile) {
                    val root = org.json.JSONObject(cacheFile.readText(Charsets.UTF_8))
                    // Build 1.44: a library saved by an older build is scanned again once, so folder pictures are found.
                    if (root.optString("treeUri") == treeUri.toString() && root.optInt("cacheVersion", 0) == LIBRARY_CACHE_VERSION) {
                        val array = root.getJSONArray("tracks")
                        val list = ArrayList<Track>(array.length())
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            val coverText = item.optString("coverUri", "")
                            list.add(Track(
                                Uri.parse(item.getString("uri")),
                                item.optString("title", "Local audio"),
                                item.optString("artist", "Artist unavailable"),
                                item.optString("album", "Unknown album"),
                                coverText.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                                item.optInt("trackNumber", Int.MAX_VALUE),
                                if (item.has("year")) item.optString("year", "") else null,
                                item.optInt("bitDepth", 0).takeIf { it > 0 }
                            ))
                        }
                        if (list.isNotEmpty()) cached = list
                    }
                }
            } catch (e: Exception) {
                recordDiagnostic("LIBRARY_CACHE_READ_FAILED", e)
            }
            cached?.let { prewarmAlbumThumbs(it) }
            handler.post {
                val library = cached
                if (library != null) {
                    tracks = library
                    currentIndex = -1
                    selectedAlbum = null
                    playbackQueue = emptyList()
                    playbackOrder = emptyList()
                    renderLibrary()
                    librarySummary.text = library.size.toString() + " tracks • local cache"
                    statusView.text = "Library restored from cache • " + library.size + " tracks."
                    recordDiagnostic("LIBRARY_CACHE_RESTORED tracks=" + library.size)
                    restoreLastPlayback(library)
                    // Build 2.2: the saved library is already on screen; check the folder for changes quietly.
                    backgroundRefreshLibrary(treeUri)
                } else {
                    scanMusicFolder(treeUri)
                }
            }
        }, "g6-library-cache").start()
    }

    private fun restoreLastPlayback(library: List<Track>) {
        if (lastPlaybackRestoreAttempted || library.isEmpty()) return
        val live = player ?: PlaybackKeepAliveService.existingPlayer()
        if (live != null && live.mediaItemCount > 0) {
            val restoredOrder = (0 until live.mediaItemCount).mapNotNull { position ->
                val item = live.getMediaItemAt(position)
                val uri = item.localConfiguration?.uri
                library.indexOfFirst { it.uri == uri || it.uri.toString() == item.mediaId }.takeIf { it >= 0 }
            }.distinct()
            val currentItem = live.currentMediaItem
            val currentUri = currentItem?.localConfiguration?.uri
            val restoredIndex = library.indexOfFirst { it.uri == currentUri || it.uri.toString() == currentItem?.mediaId }
            if (restoredIndex >= 0) {
                lastPlaybackRestoreAttempted = true
                playbackOrder = restoredOrder.ifEmpty { listOf(restoredIndex) }
                // The service kept the player alive, but the queue the user built (every added album) is only in the saved state.
                playbackQueue = readSavedQueueState(library)?.queue ?: playbackOrder.toList()
                currentIndex = restoredIndex
                player = live
                notificationDismissed = false
                val activeTrack = library[restoredIndex]
                titleView.text = activeTrack.title
                artistView.text = activeTrack.artist
                albumView.text = activeTrack.album
                pendingAutoplay = false
                prepared = live.playbackState == Player.STATE_READY
                attachPlaybackListener(live, activeTrack)
                showMetadata(activeTrack.uri, activeTrack)
                loadArtwork(activeTrack)
                syncPlaybackIcon(live.isPlaying)
                if (prepared) {
                    durationMs = live.duration.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt() ?: 0
                    duration.text = time(durationMs)
                    seek.max = durationMs.coerceAtLeast(1)
                    playView.isEnabled = true
                }
                recordDiagnostic("SERVICE_PLAYER_REATTACHED index=" + restoredIndex + " queue=" + playbackOrder.size + " playing=" + live.isPlaying + " positionMs=" + live.currentPosition)
                return
            }
        }
        val prefs = getPreferences(MODE_PRIVATE)
        val savedUri = prefs.getString(KEY_LAST_TRACK_URI, null)?.takeIf { it.isNotBlank() } ?: return
        val index = library.indexOfFirst { it.uri.toString() == savedUri }
        if (index < 0) {
            recordDiagnostic("LAST_PLAYBACK_RESTORE_TRACK_NOT_FOUND uri=$savedUri tracks=${library.size}")
            return
        }
        lastPlaybackRestoreAttempted = true
        pendingRestorePositionMs = prefs.getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
        val savedQueue = readSavedQueueState(library)
        if (savedQueue != null && index in savedQueue.order) {
            recordDiagnostic("LAST_PLAYBACK_RESTORE_START index=$index positionMs=${pendingRestorePositionMs} queue=${savedQueue.queue.size} order=${savedQueue.order.size}")
            playTrackAt(index, autoplay = false, queue = savedQueue.order)
            // playTrackAt makes the visible queue equal to the play order; put back what the user had
            // (for example an emptied queue, or queued albums shown while the order is shorter).
            playbackQueue = savedQueue.queue
            if (::queueRows.isInitialized) renderQueueRows()
            persistQueueState()
            return
        }
        recordDiagnostic("LAST_PLAYBACK_RESTORE_START index=$index positionMs=${pendingRestorePositionMs}")
        playTrackAt(index, autoplay = false)
    }

    // ---- Queue persistence -------------------------------------------------------------------------
    // Only the last track and its position used to be saved, so after "Close" or a process restart the
    // queue was rebuilt from that one track's album and every other queued album was lost. The queue
    // (display list) and the play order are now saved as track URIs, which stay valid when the library
    // is rescanned and its indices change.
    private class SavedQueue(val queue: List<Int>, val order: List<Int>)
    private val queueStateExecutor = Executors.newSingleThreadExecutor()
    private val queueStateFile get() = File(filesDir, "playback_queue.json")

    private fun persistQueueState() {
        val queueUris = playbackQueue.mapNotNull { tracks.getOrNull(it)?.uri?.toString() }
        val orderUris = playbackOrder.mapNotNull { tracks.getOrNull(it)?.uri?.toString() }
        try {
            queueStateExecutor.execute {
                try {
                    val root = org.json.JSONObject()
                        .put("queue", org.json.JSONArray(queueUris))
                        .put("order", org.json.JSONArray(orderUris))
                    val file = queueStateFile
                    val temporary = File(filesDir, "playback_queue.json.tmp")
                    temporary.writeText(root.toString(), Charsets.UTF_8)
                    if (!temporary.renameTo(file)) { file.writeText(root.toString(), Charsets.UTF_8); temporary.delete() }
                } catch (e: Exception) { recordDiagnostic("QUEUE_STATE_WRITE_FAILED", e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** Saved queue mapped onto [library] indices; tracks that no longer exist are dropped. Null = nothing saved. */
    private fun readSavedQueueState(library: List<Track>): SavedQueue? {
        val file = queueStateFile
        if (!file.exists()) return null
        return try {
            val root = org.json.JSONObject(file.readText(Charsets.UTF_8))
            val byUri = HashMap<String, Int>(library.size * 2)
            library.forEachIndexed { i, t -> byUri[t.uri.toString()] = i }
            fun mapped(name: String): List<Int> {
                val array = root.optJSONArray(name) ?: return emptyList()
                val out = ArrayList<Int>(array.length())
                for (i in 0 until array.length()) byUri[array.optString(i)]?.let { out.add(it) }
                return out.distinct()
            }
            SavedQueue(mapped("queue"), mapped("order"))
        } catch (e: Exception) { recordDiagnostic("QUEUE_STATE_READ_FAILED", e); null }
    }

    /** Keep the queue and play order pointing at the same tracks after the library list was replaced. */
    private fun remapPlaybackLists(oldTracks: List<Track>, newTracks: List<Track>) {
        val byUri = HashMap<String, Int>(newTracks.size * 2)
        newTracks.forEachIndexed { i, t -> byUri[t.uri.toString()] = i }
        fun remap(list: List<Int>): List<Int> =
            list.mapNotNull { oldIndex -> oldTracks.getOrNull(oldIndex)?.uri?.toString()?.let { byUri[it] } }
        playbackQueue = remap(playbackQueue)
        playbackOrder = remap(playbackOrder)
    }

    private fun persistPlaybackState(force: Boolean) {
        val index = currentIndex
        if (index !in tracks.indices) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPlaybackPersistAtMs < 1500L) return
        lastPlaybackPersistAtMs = now
        val position = try { player?.currentPosition?.coerceAtLeast(0L) ?: 0L } catch (_: Exception) { 0L }
        try {
            getPreferences(MODE_PRIVATE).edit()
                .putString(KEY_LAST_TRACK_URI, tracks[index].uri.toString())
                .putLong(KEY_LAST_POSITION_MS, position)
                .apply()
        } catch (e: Exception) {
            recordDiagnostic("LAST_PLAYBACK_PERSIST_FAILED", e)
        }
    }

    private fun saveLibraryCache(treeUri: Uri, library: List<Track>) {
        try {
            val array = org.json.JSONArray()
            library.forEach { track ->
                array.put(org.json.JSONObject()
                    .put("uri", track.uri.toString())
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("coverUri", track.coverUri?.toString() ?: "")
                    .put("trackNumber", track.trackNumber)
                    .apply {
                        track.year?.let { put("year", it) }
                        track.bitDepth?.let { put("bitDepth", it) }
                    })
            }
            val root = org.json.JSONObject().put("cacheVersion", LIBRARY_CACHE_VERSION).put("treeUri", treeUri.toString()).put("tracks", array)
            val file = File(cacheDir, "library-cache.json")
            val temporary = File(cacheDir, "library-cache.json.tmp")
            temporary.writeText(root.toString(), Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                temporary.delete()
            }
            recordDiagnostic("LIBRARY_CACHE_SAVED tracks=" + library.size)
        } catch (e: Exception) {
            recordDiagnostic("LIBRARY_CACHE_WRITE_FAILED", e)
        }
    }

    private fun scanMusicFolder(uri: Uri) {
        // Scanning is a library workflow: move to Albums first so progress and newly found art are visible.
        show("library")
        scanGeneration++
        recordDiagnostic("SCAN_START uri=$uri")
        librarySummary.text = "Scanning folders and subfolders…"
        albumScanSummary.text = "Scanning folders and subfolders…"
        statusView.text = "Scanning music library…"
        scanProgress.visibility = View.VISIBLE; scanProgressText.visibility = View.VISIBLE
        scanProgressText.text = "Starting scan…"
        Thread {
            val found = mutableListOf<Track>()
            val count = intArrayOf(0)
            var error: String? = null
            try { DocumentFile.fromTreeUri(this, uri)?.let { scanDirectory(it, found, mutableMapOf(), 0, count) } }
            catch (e: Exception) { error = e.javaClass.simpleName + ": " + (e.message ?: "akses ditolak"); recordDiagnostic("SCAN_FAILED uri=$uri", e) }
            recordDiagnostic("SCAN_ENUMERATION_COMPLETE found=${found.size} processed=${count[0]} error=${error ?: "none"}")
            val sorted = synchronized(found) { found.toList() }.sortedWith(compareBy<Track> { it.album.lowercase(Locale.ROOT) }.thenBy { it.trackNumber }.thenBy { it.title.lowercase(Locale.ROOT) })
            prewarmAlbumThumbs(sorted)
            handler.post {
                // A rescan used to wipe the queue and the current track. Carry both over by track URI.
                val previousCurrentUri = tracks.getOrNull(currentIndex)?.uri
                remapPlaybackLists(tracks, sorted)
                tracks = sorted
                currentIndex = previousCurrentUri?.let { u -> sorted.indexOfFirst { it.uri == u } } ?: -1
                selectedAlbum = null
                // Tracks marked "no cover" earlier get another chance now that the folders were read again.
                noArtworkTracks.clear()
                Thread({ saveLibraryCache(uri, sorted) }, "g6-library-cache-save").start()
                try { renderLibrary() } catch (e: Throwable) {
                    recordDiagnostic("LIBRARY_RENDER_FAILED tracks=${sorted.size}", e)
                    librarySummary.text = "Found ${sorted.size} tracks, but the library view could not be rendered. Diagnostic log is available."
                    statusView.text = "Failed to render library: " + e.javaClass.simpleName
                }
                scanProgress.visibility = View.GONE; scanProgressText.visibility = View.GONE
                librarySummary.text = when {
                    error != null -> "Failed to scan folder: " + error
                    sorted.isEmpty() -> "No audio files found. Choose a Music folder containing tracks."
                    else -> sorted.size.toString() + " tracks • " + sorted.map { it.album }.distinct().size + " albums found"
                }
                albumScanSummary.text = librarySummary.text
                statusView.text = if (sorted.isEmpty()) "The library is empty. Check the selected folder." else "Library ready • " + sorted.size + " tracks."
                if (sorted.isNotEmpty()) restoreLastPlayback(sorted)
            }
        }.start()
    }

    private fun scanDirectory(dir: DocumentFile, output: MutableList<Track>, covers: MutableMap<String, Uri?>, depth: Int, count: IntArray, known: Map<String, Track>? = null) {
        if (depth > 12) return
        val children = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
        for (file in children) try {
            if (file.isDirectory) scanDirectory(file, output, covers, depth + 1, count, known)
            else if (isAudioFile(file)) {
                val key = dir.uri.toString()
                if (!covers.containsKey(key)) covers[key] = findCover(dir)
                // Build 2.2: a file already in the saved library keeps its saved tags (no tag reading again); only its folder picture is refreshed.
                val prior = known?.get(file.uri.toString())?.takeIf { it.year != null } // Build 2.4: entries without a year are read again once
                synchronized(output) { output.add(if (prior != null) prior.copy(coverUri = covers[key]) else readTrack(file.uri, covers[key], dir.name)) }
                count[0]++
                if (known == null && (count[0] == 1 || count[0] % 50 == 0)) {
                    val processedCount = count[0]
                    val folderName = dir.name ?: "folder"
                    handler.post {
                        if (scanProgress.visibility == View.VISIBLE) {
                            scanProgressText.text = "Found " + processedCount + " tracks • scanning " + folderName + "…"
                            // Publish modest batches so albums and their artwork appear during the scan,
                            // without rebuilding the grid for every single file on older devices.
                            val partial = synchronized(output) { output.toList() }
                            if (partial.isNotEmpty()) {
                                val previousUri = tracks.getOrNull(currentIndex)?.uri
                                val previousTracks = tracks
                                tracks = partial.sortedWith(compareBy<Track> { it.album.lowercase(Locale.ROOT) }.thenBy { it.trackNumber }.thenBy { it.title.lowercase(Locale.ROOT) })
                                currentIndex = previousUri?.let { uri -> tracks.indexOfFirst { it.uri == uri } } ?: -1
                                remapPlaybackLists(previousTracks, tracks)
                                renderLibrary()
                                albumScanSummary.text = tracks.size.toString() + " tracks found so far • " + tracks.map { it.album }.distinct().size + " albums"
                                librarySummary.text = albumScanSummary.text
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { recordDiagnostic("SCAN_ENTRY_FAILED name=${file.name} uri=${file.uri}", e) }
    }
    private fun isAudioFile(file: DocumentFile): Boolean {
        val name = file.name?.lowercase(Locale.ROOT) ?: return file.type?.startsWith("audio/") == true
        return file.type?.startsWith("audio/") == true || name.substringAfterLast('.', "") in AUDIO_EXTENSIONS
    }
    private fun findCover(dir: DocumentFile): Uri? {
        val files = try { dir.listFiles().filter { !it.isDirectory } } catch (_: Exception) { emptyList() }
        // Build 1.44: any jpg/jpeg/png/webp in the track's own folder counts. Hidden files (e.g. "._cover.jpg"
        // left by a Mac copy of the album) are not real pictures and are skipped.
        val images = files.filter {
            val n = it.name?.lowercase(Locale.ROOT) ?: ""
            !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
        }.sortedBy { it.name?.lowercase(Locale.ROOT) ?: "" }
        val preferred = listOf("cover", "folder", "front", "album", "artwork", "albumart")
        for (prefix in preferred) {
            images.firstOrNull { it.name?.substringBeforeLast('.')?.lowercase(Locale.ROOT) == prefix }?.let { return it.uri }
        }
        images.firstOrNull { f -> val n = f.name?.lowercase(Locale.ROOT) ?: ""; preferred.any { n.contains(it) } }?.let { return it.uri }
        return images.firstOrNull()?.uri
    }
    private fun readTrack(uri: Uri, coverUri: Uri?, folderName: String?): Track {
        val r = MediaMetadataRetriever()
        var title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Local audio"
        var artist = "Artist unavailable"; var album = folderName ?: "Unknown album"; var trackNumber = Int.MAX_VALUE
        var year = ""
        try {
            r.setDataSource(this, uri)
            fun meta(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
            artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
            trackNumber = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore("/")?.toIntOrNull() ?: Int.MAX_VALUE
            year = WavTagReader.yearOf(meta(MediaMetadataRetriever.METADATA_KEY_YEAR)) ?: WavTagReader.yearOf(meta(MediaMetadataRetriever.METADATA_KEY_DATE)) ?: ""
        } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) { } }
        // Build 1.45: MediaMetadataRetriever returns no tags for most WAV files; read the LIST/INFO and ID3 chunks ourselves.
        if (WavTagReader.isWavName(uri.lastPathSegment)) {
            WavTagReader.read(this, uri, wantPicture = false)?.let { wav ->
                wav.title?.let { title = it }
                wav.artist?.let { artist = it }
                wav.album?.let { album = it }
                wav.trackNumber?.let { trackNumber = it }
                wav.year?.let { year = it }
            }
        }
        // AudioSniffer currently identifies integer bit depth for FLAC/WAV; skip unrelated formats during a large library scan.
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val bitDepth = if (extension == "flac" || extension == "wav" || extension == "wave" || extension == "m4a" || extension == "mp4" || extension == "alac") AudioSniffer.sniff(this, uri)?.bits else null
        return Track(uri, title, artist, album, coverUri, trackNumber, year, bitDepth)
    }

    private fun renderLibrary() {
        if (!::albumRows.isInitialized) return
        albumPlayingMarks.clear()
        albumRows.removeAllViews()
        if (tracks.isEmpty()) {
            albumRows.addView(text("Your album grid will appear here after scanning a music folder in Settings.", 13f, MUTED, false))
            return
        }

        // Group once per render; selected album rows are shown before the grid so tapping a
        // cover immediately reveals its tracks instead of appending them below the entire library.
        val groupedAlbums = tracks.withIndex().groupBy { it.value.album }
        val albums = groupedAlbums.keys.sortedBy { it.lowercase(Locale.ROOT) }

        selectedAlbum?.let { album ->
            val entries = groupedAlbums[album].orEmpty().sortedWith(
                compareBy<IndexedValue<Track>> { it.value.trackNumber }.thenBy { it.value.title.lowercase(Locale.ROOT) }
            )
            if (entries.isEmpty()) {
                selectedAlbum = null
            } else {
                val albumArtist = entries.groupingBy { it.value.artist }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                val queueIndices = entries.map { it.index }

                // Build 2.3: the cover sits in the middle, fairly large; the songs are a plain numbered list below it.
                val contentWidth = resources.displayMetrics.widthPixels - dp(32)
                val artSide = minOf((resources.displayMetrics.widthPixels * ALBUM_DETAIL_ART_FRACTION).toInt(), contentWidth)
                val bigCover = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = shape(ART, 10)
                    roundCorners(10)
                    elevation = dp(6).toFloat()
                    contentDescription = "Artwork $album"
                }
                applyAlbumHiResBadge(bigCover, entries.map { it.value })
                // Build 2.2: no "‹ ALBUMS" button any more; a plain left arrow sits at the left of the big cover.
                val coverRow = FrameLayout(this)
                coverRow.addView(bigCover, FrameLayout.LayoutParams(artSide, artSide, Gravity.CENTER_HORIZONTAL))
                val backArrow = ImageView(this).apply {
                    setImageDrawable(backArrowDrawable())
                    scaleType = ImageView.ScaleType.CENTER
                    isClickable = true; isFocusable = true
                    contentDescription = "Back to albums"
                    themed { background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), shape(Color.TRANSPARENT, 20), null) }
                    setOnClickListener {
                        selectedAlbum = null
                        renderLibrary()
                        restoreListScroll()
                    }
                }
                coverRow.addView(backArrow, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START))
                albumRows.addView(coverRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                loadBigArtworkInto(bigCover, entries.first().value)
                albumRows.addView(text(album, 18f, WHITE, true).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(16), dp(8), 0)
                }, LinearLayout.LayoutParams(-1, -2))
                albumRows.addView(text((if (albumArtist.isBlank()) "" else albumArtist + " • ") + entries.size + " tracks", 12f, MUTED, false).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(3), dp(8), dp(12))
                }, LinearLayout.LayoutParams(-1, -2))
                val albumActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(4), dp(0), dp(4), dp(6))
                }
                albumActions.addView(flatAction("PLAY ALBUM", true) {
                    if (queueIndices.isNotEmpty()) { playTrackAt(queueIndices.first(), true, queueIndices); showPlayerAnimated() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                albumActions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
                albumActions.addView(flatAction("ADD TO QUEUE", false) {
                    addAlbumToQueue(queueIndices, album)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                albumRows.addView(albumActions, LinearLayout.LayoutParams(-1, -2))
                entries.forEachIndexed { position, entry -> addNumberedTrackRow(albumRows, entry, position + 1, albumArtist, queueIndices) }
                updateAlbumPlayingMarks(try { player?.isPlaying == true } catch (_: Exception) { false })

                // More albums of the same artist: a row that scrolls sideways when it does not fit on one screen.
                val moreAlbums = if (albumArtist.isBlank()) emptyList() else albums.filter { other ->
                    other != album && groupedAlbums[other].orEmpty().any { it.value.artist == albumArtist }
                }
                if (moreAlbums.isNotEmpty()) {
                    albumRows.addView(text("More albums by $albumArtist", 14f, WHITE, true).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(4), dp(24), dp(4), dp(10))
                    }, LinearLayout.LayoutParams(-1, -2))
                    val scroller = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                    }
                    val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val cardSide = dp(112)
                    moreAlbums.forEach { other ->
                        val otherEntries = groupedAlbums[other].orEmpty()
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            setPadding(dp(4), dp(4), dp(4), dp(6))
                            themed { background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x25)), shape(Color.TRANSPARENT, 12), null) }
                            isClickable = true
                            isFocusable = true
                            contentDescription = "Open album $other"
                            setOnClickListener {
                                selectedAlbum = other
                                renderLibrary()
                                scrollLibraryToTop()
                            }
                        }
                        val otherCover = ImageView(this).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = shape(ART, 10)
                            roundCorners(10)
                            contentDescription = "Artwork $other"
                        }
                        applyAlbumHiResBadge(otherCover, otherEntries.map { it.value })
                        card.addView(otherCover, LinearLayout.LayoutParams(cardSide, cardSide))
                        card.addView(text(other, 12f, MUTED, true).apply {
                            setPadding(dp(2), dp(6), dp(2), dp(1))
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER_HORIZONTAL
                        }, LinearLayout.LayoutParams(cardSide, -2))
                        card.addView(text(otherEntries.size.toString() + " tracks", 10f, MUTED, false).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                        }, LinearLayout.LayoutParams(cardSide, -2))
                        strip.addView(card, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                        otherEntries.firstOrNull()?.let { loadArtworkInto(otherCover, it.value) }
                    }
                    scroller.addView(strip)
                    albumRows.addView(scroller, LinearLayout.LayoutParams(-1, -2))
                }
                librarySummary.text = tracks.size.toString() + " tracks • " + albums.size + " albums"
                return
            }
        }

        var gridRow: LinearLayout? = null
        albums.forEachIndexed { position, album ->
            if (position % albumGridColumns == 0) {
                gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                albumRows.addView(gridRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            val entries = groupedAlbums[album].orEmpty()
            val indices = entries.sortedWith(
                compareBy<IndexedValue<Track>> { it.value.trackNumber }.thenBy { it.value.title.lowercase(Locale.ROOT) }
            ).map { it.index }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(3), dp(4), dp(3), dp(7))
                themed { background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x25)), shape(Color.TRANSPARENT, 12), null) }
                isClickable = true
                isFocusable = true
                contentDescription = "Open songs in album $album"
                setOnClickListener {
                    selectedAlbum = album
                    renderLibrary()
                    show("library")
                    scrollLibraryToTop()
                }
                setOnLongClickListener {
                    showAlbumOptions(album, indices)
                    true
                }
            }
            // Build 2.4: square cover with rounded corners (the corners were not clipped before).
            val cover = object : ImageView(this) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
            }.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(ART, 10)
                roundCorners(10)
                contentDescription = "Artwork $album"
            }
            applyAlbumHiResBadge(cover, entries.map { it.value })
            cell.addView(cover, LinearLayout.LayoutParams(-1, -2))
            cell.addView(text(album, 12f, MUTED, true).apply {
                setPadding(dp(2), dp(7), dp(2), dp(2))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
            }, LinearLayout.LayoutParams(-1, -2))
            cell.addView(text(entries.size.toString() + " tracks", 10f, MUTED, false).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }, LinearLayout.LayoutParams(-1, -2))
            val lp = LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (position % albumGridColumns != 0) leftMargin = dp(3)
                if (position % albumGridColumns != albumGridColumns - 1) rightMargin = dp(3)
            }
            gridRow?.addView(cell, lp)
            entries.firstOrNull()?.let { loadArtworkInto(cover, it.value) }
        }
        // Build 2.8: the last row may hold fewer albums than columns. Empty cells of the same weight keep every
        // cover the same size as in a full row (before, a single album stretched across the whole width).
        val lastCount = albums.size % albumGridColumns
        if (lastCount != 0) {
            for (position in lastCount until albumGridColumns) {
                val filler = View(this)
                val flp = LinearLayout.LayoutParams(0, 1, 1f).apply {
                    if (position != 0) leftMargin = dp(3)
                    if (position != albumGridColumns - 1) rightMargin = dp(3)
                }
                gridRow?.addView(filler, flp)
            }
        }
        librarySummary.text = tracks.size.toString() + " tracks • " + albums.size + " albums"
    }

    private fun renderSongs() {
        if (!::songRows.isInitialized) return
        songRows.removeAllViews()
        if (tracks.isEmpty()) {
            songRows.addView(text("No songs yet. Scan your music folder in Settings.", 13f, MUTED, false))
            return
        }
        val query = songSearchQuery.trim().lowercase(Locale.ROOT)
        val filtered = tracks.withIndex().filter { (_, track) ->
            query.isEmpty() || track.title.lowercase(Locale.ROOT).contains(query) ||
                track.artist.lowercase(Locale.ROOT).contains(query) || track.album.lowercase(Locale.ROOT).contains(query)
        }
        if (filtered.isEmpty()) {
            songRows.addView(text("No songs match the search.", 13f, MUTED, false).apply { setPadding(dp(4), dp(12), dp(4), dp(12)) })
            return
        }
        val grouped = filtered.groupBy { it.value.album }
        grouped.keys.sortedBy { it.lowercase(Locale.ROOT) }.forEach { album ->
            val entries = grouped[album].orEmpty().sortedWith(
                compareBy<IndexedValue<Track>> { it.value.trackNumber }.thenBy { it.value.title.lowercase(Locale.ROOT) }
            )
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(6))
            }
            heading.addView(text(album, 14f, WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(text(entries.size.toString(), 11f, MUTED, false))
            songRows.addView(heading)
            entries.forEach { entry -> addLibraryTrackRow(songRows, entry, false, entries.map { it.index }) }
        }
    }
    private fun addLibraryTrackRow(parent: LinearLayout, entry: IndexedValue<Track>, nested: Boolean, queueIndices: List<Int>) {
        val t = entry.value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (nested) 18 else 8), dp(8), dp(8), dp(8))
            themed { background = shape(if (entry.index == currentIndex) selectedHighlight() else Color.TRANSPARENT, 12) }
            isClickable = true
            isFocusable = true
        }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; themed { background = shape(ART, 8) } }
        applyTrackHiResBadge(cover, t)
        row.addView(cover, LinearLayout.LayoutParams(dp(54), dp(54)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(4), 0)
        }
        details.addView(text(t.title, 12f, WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        details.addView(text(t.artist + " • " + t.album, 12f, MUTED, false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val addQueue = iconAction("+") { addToQueue(entry.index) }.apply { textSize = 22f; setTextColor(ACTION_GREY); contentDescription = "Add ${t.title} to queue" }
        row.addView(addQueue, LinearLayout.LayoutParams(dp(40), dp(44)))
        row.setOnClickListener {
            val albumOrder = tracks.withIndex().filter { it.value.album == t.album }.map { it.index }
            playTrackAt(entry.index, true, albumOrder.ifEmpty { queueIndices })
            showPlayerAnimated()
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        loadArtworkInto(cover, t)
    }
    /** Build 2.3/2.4: one song of the opened album: number, title, a grey line with file type + year (+ artist when it differs), playing bars on the right. */
    private class AlbumRowMark(val index: Int, val row: View, val number: TextView, val title: TextView, val bars: EqualizerView, val action: TextView? = null)
    private val albumPlayingMarks = ArrayList<AlbumRowMark>()
    /** Build 2.5: the queue list uses the same row design as the album list, so it has its own playing marks. */
    private val queuePlayingMarks = ArrayList<AlbumRowMark>()

    private fun updateAlbumPlayingMarks(playing: Boolean) {
        if (albumPlayingMarks.isEmpty() && queuePlayingMarks.isEmpty()) return
        (albumPlayingMarks + queuePlayingMarks).forEach { m ->
            val current = currentIndex >= 0 && m.index == currentIndex
            m.bars.visibility = if (current) View.VISIBLE else View.INVISIBLE
            m.bars.setBarColor(accentColor)
            m.bars.playing = current && playing
            m.title.setTextColor(if (current) accentColor else WHITE)
            m.number.setTextColor(if (current) accentColor else MUTED)
            // Build 2.10: the queue's "x" is grey except on the selected track, where it keeps the accent.
            m.action?.setTextColor(if (current) accentColor else ACTION_GREY)
            m.row.background = shape(if (current) selectedHighlight() else Color.TRANSPARENT, 12)
        }
    }

    private fun addNumberedTrackRow(parent: LinearLayout, entry: IndexedValue<Track>, number: Int, albumArtist: String, queueIndices: List<Int>) {
        val t = entry.value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
            isClickable = true
            isFocusable = true
        }
        val numberView = text(number.toString(), 13f, MUTED, false).apply { gravity = Gravity.CENTER }
        row.addView(numberView, LinearLayout.LayoutParams(dp(34), -2))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(4), 0) }
        val titleView = text(t.title, 13f, WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(titleView)
        // File type from the file name, year from the ID tag; both are left out when the file does not have them.
        val fileType = t.uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }?.uppercase(Locale.ROOT).orEmpty()
        val parts = listOf(fileType, t.year.orEmpty(), if (t.artist.isNotBlank() && t.artist != albumArtist) t.artist else "").filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            details.addView(text(parts.joinToString(" • "), 11f, MUTED, false).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val bars = EqualizerView(this, accentColor).apply { visibility = View.INVISIBLE }
        row.addView(bars, LinearLayout.LayoutParams(dp(16), dp(14)).apply { rightMargin = dp(2) })
        val addQueue = iconAction("+") { addToQueue(entry.index) }.apply { textSize = 22f; setTextColor(ACTION_GREY); contentDescription = "Add ${t.title} to queue" }
        row.addView(addQueue, LinearLayout.LayoutParams(dp(40), dp(36)))
        row.setOnClickListener {
            val albumOrder = tracks.withIndex().filter { it.value.album == t.album }.map { it.index }
            playTrackAt(entry.index, true, albumOrder.ifEmpty { queueIndices })
            showPlayerAnimated()
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        albumPlayingMarks.add(AlbumRowMark(entry.index, row, numberView, titleView, bars))
    }

    /** Large cover for the opened-album page: the saved grid cover shows at once, the sharper one replaces it when decoded. */
    private fun loadBigArtworkInto(target: ImageView, track: Track) {
        val key = "big:" + track.uri.toString()
        target.setTag(key)
        val cached = bitmapArtworkCache.get(key)
        if (cached != null && !cached.isRecycled) {
            target.setImageBitmap(cached)
            return
        }
        bitmapArtworkCache.get("thumb:" + track.uri.toString())?.takeIf { !it.isRecycled }?.let { target.setImageBitmap(it) }
        artworkExecutor.execute {
            val bitmap = try { readArtworkBytes(track)?.let { decodeArtwork(it, 800, android.graphics.Bitmap.Config.RGB_565) } } catch (_: Throwable) { null }
            if (bitmap != null) bitmapArtworkCache.put(key, bitmap)
            handler.post {
                if (isFinishing) return@post
                if (bitmap != null) { if (target.getTag() == key) target.setImageBitmap(bitmap) }
                else { target.setTag(null); loadArtworkInto(target, track) }
            }
        }
    }

    /** The album pages live inside the page ScrollView; after opening another album it must start at the top. */
    /** Puts the Album list back where it was (after layout, so the saved offset is not clamped by the old content height). */
    private fun restoreListScroll() {
        val sv = pageScroll ?: return
        val y = listScrollY
        restoringScroll = true
        sv.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (sv.viewTreeObserver.isAlive) sv.viewTreeObserver.removeOnPreDrawListener(this)
                sv.scrollTo(0, y)
                restoringScroll = false
                return true
            }
        })
        sv.invalidate()
    }

    private fun scrollLibraryToTop() {
        libraryPanel.post {
            var v: View? = libraryPanel
            while (v != null && v !is ScrollView) v = v.parent as? View
            (v as? ScrollView)?.scrollTo(0, 0)
        }
    }

    private fun rowButton(label: String, selected: Boolean, click: () -> Unit) = text(label, 13f, if (selected) accentColor else WHITE, selected).apply {
        setPadding(dp(12), dp(11), dp(12), dp(11)); themed { background = shape(if (selected) LINE else Color.TRANSPARENT, 12) }
        isClickable = true; isFocusable = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) }
    }




    /**
     * Build 3.23: when the last track of the playlist is the one playing, the next album (alphabetical, same order as the
     * Albums list) is appended to the live Media3 playlist so playback continues gapless into it. Albums the user queued but
     * that are not in the live playlist yet are appended first. Returns the index of the first appended item, or -1.
     */
    private fun ensureNextAlbumQueued(exo: ExoPlayer): Int {
        try {
            val count = exo.mediaItemCount
            if (count == 0 || tracks.isEmpty() || exo.currentMediaItemIndex != count - 1) return -1
            val byUri = HashMap<String, Int>(tracks.size * 2)
            tracks.forEachIndexed { i, t -> byUri[t.uri.toString()] = i }
            val inPlayer = (0 until count).mapNotNull { i -> byUri[exo.getMediaItemAt(i).mediaId] }
            val inPlayerSet = inPlayer.toHashSet()
            val pending = playbackOrder.filter { it in tracks.indices && it !in inPlayerSet }
            val toAdd: List<Int>
            val queuedByUser = pending.isNotEmpty()
            if (queuedByUser) {
                toAdd = pending
            } else {
                val lastAlbum = tracks.getOrNull(inPlayer.lastOrNull() ?: return -1)?.album ?: return -1
                val albums = tracks.map { it.album }.distinct().sortedBy { it.lowercase(Locale.ROOT) }
                val position = albums.indexOf(lastAlbum)
                if (position < 0 || position + 1 >= albums.size) return -1
                val nextAlbum = albums[position + 1]
                toAdd = tracks.withIndex()
                    .filter { it.value.album == nextAlbum && it.index !in inPlayerSet }
                    .sortedWith(compareBy<IndexedValue<Track>> { it.value.trackNumber }.thenBy { it.value.title.lowercase(Locale.ROOT) })
                    .map { it.index }
            }
            if (toAdd.isEmpty()) return -1
            val items = toAdd.map { trackIndex ->
                val itemTrack = tracks[trackIndex]
                MediaItem.Builder()
                    .setMediaId(itemTrack.uri.toString())
                    .setUri(itemTrack.uri)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(itemTrack.title)
                        .setArtist(itemTrack.artist)
                        .setAlbumTitle(itemTrack.album)
                        .build())
                    .build()
            }
            exo.addMediaItems(items)
            playbackOrder = inPlayer + toAdd
            if (!queuedByUser) playbackQueue = playbackOrder.toList()
            persistQueueState()
            if (::queueRows.isInitialized) renderQueueRows()
            recordDiagnostic("AUTO_NEXT_ALBUM_QUEUED album=" + tracks[toAdd.first()].album + " tracks=" + toAdd.size + " userQueued=" + queuedByUser + " playlist=" + playbackOrder.size)
            return count
        } catch (e: Exception) {
            recordDiagnostic("AUTO_NEXT_ALBUM_FAILED", e)
            return -1
        }
    }

    private fun attachPlaybackListener(exo: ExoPlayer, initialTrack: Track) {
        playerListener?.let { try { exo.removeListener(it) } catch (_: Exception) { } }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                val mediaId = mediaItem?.mediaId
                val itemPosition = playbackOrder.indexOfFirst { index ->
                    tracks.getOrNull(index)?.let { it.uri == uri || it.uri.toString() == mediaId } == true
                }
                if (itemPosition >= 0) {
                    currentIndex = playbackOrder[itemPosition]
                    updateAlbumPlayingMarks(try { exo.isPlaying } catch (_: Exception) { false })
                    val activeTrack = tracks.getOrNull(currentIndex) ?: return
                    notificationDismissed = false
                    titleView.text = activeTrack.title
                    artistView.text = activeTrack.artist
                    albumView.text = activeTrack.album
                    strictBlocked = false
                    sourceRate = null
                    sourceBits = null; sourceFormat = null
                    sourceBitrate = null
                    updateHiResBadges()
                    DirectOutputState.resetDepthCheck() // Build 1.66: a new track gets a new real-24-bit check
                    DirectOutputState.expected = null    // Hifi 1.24: the MD5 of this track is set again once its header was read
                    showMetadata(activeTrack.uri, activeTrack)
                    loadArtwork(activeTrack)
                    if (::queueRows.isInitialized) renderQueueRows()
                    recordDiagnostic("GAPLESS_MEDIA_ITEM_TRANSITION index=" + currentIndex + " playlistIndex=" + itemPosition + " reason=" + reason)
                    ensureNextAlbumQueued(exo) // Build 3.23: last track of the playlist -> line up the next album
                    if (exo.playbackState == Player.STATE_READY) {
                        prepared = true
                        durationMs = exo.duration.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt() ?: 0
                        duration.text = time(durationMs)
                        seek.max = durationMs.coerceAtLeast(1)
                        updatePlaybackNotification(exo.isPlaying)
                    }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        prepared = true
                        val activeTrack = tracks.getOrNull(currentIndex) ?: initialTrack
                        durationMs = exo.duration.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt() ?: 0
                        seek.max = durationMs.coerceAtLeast(1)
                        duration.text = time(durationMs)
                        pendingRestorePositionMs?.let { savedPosition ->
                            val bounded = if (durationMs > 0) savedPosition.coerceAtMost(durationMs.toLong()) else savedPosition
                            try { exo.seekTo(bounded) } catch (e: Exception) { recordDiagnostic("LAST_PLAYBACK_SEEK_RESTORE_FAILED positionMs=" + bounded, e) }
                            pendingRestorePositionMs = null
                            recordDiagnostic("LAST_PLAYBACK_RESTORE_POSITION_APPLIED positionMs=" + bounded + " durationMs=" + durationMs)
                        }
                        playView.isEnabled = true
                        if (!strictBlocked) {
                            statusView.text = if (exo.isPlaying || (pendingAutoplay && exo.playWhenReady)) "Playing • " + activeTrack.title
                                else "Ready • Media3/ExoPlayer • " + (sourceRate?.let { "%.1f kHz".format(Locale.US, it / 1000f) } ?: "source sample rate unknown")
                        }
                        if (pendingAutoplay && !strictBlocked) exo.play()
                        pendingAutoplay = false
                        mediaSession?.isActive = true
                        refreshAudioInfo()
                        ensureNextAlbumQueued(exo) // Build 3.23: also covers a one-album queue or a restored last track
                        updatePlaybackNotification(exo.isPlaying)
                    }
                    Player.STATE_ENDED -> {
                        // Build 3.23: if the playlist finished without the next album lined up, start it now.
                        val firstNew = ensureNextAlbumQueued(exo)
                        if (firstNew >= 0) {
                            exo.seekTo(firstNew, 0L)
                            exo.play()
                            return
                        }
                        syncPlaybackIcon(false)
                        statusView.text = "Album/queue finished."
                        refreshAudioInfo()
                        updatePlaybackNotification(false)
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && strictBlocked) { PauseWatch.markUser(); try { exo.pause() } catch (_: Exception) { }; return }   // Hifi 1.29
                if (prepared) {
                    if (isPlaying) notificationDismissed = false
                    syncPlaybackIcon(isPlaying)
                    refreshAudioInfo()
                    updatePlaybackNotification(isPlaying)
                }
            }
            // Hifi 1.29 strict mode: only FLAC, ALAC and WAV have a renderer. If the file's audio track has none, nothing may play.
            override fun onTracksChanged(trackInfo: androidx.media3.common.Tracks) {
                if (trackInfo.groups.isNotEmpty() && !trackInfo.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_AUDIO)) {
                    strictBlocked = true
                    PauseWatch.markUser()
                    try { exo.pause() } catch (_: Exception) { }
                    val mime = trackInfo.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                        ?.let { g -> try { g.getTrackFormat(0).sampleMimeType } catch (_: Exception) { null } }
                    val title = this@MainActivity.tracks.getOrNull(currentIndex)?.title ?: ""
                    statusView.text = "Not supported (strict mode: FLAC, ALAC, WAV only)" + (mime?.let { " • $it" } ?: "") + (if (title.isNotEmpty()) "\n$title" else "")
                    try { Toast.makeText(this@MainActivity, "Not played: only FLAC, ALAC and WAV are supported" + (mime?.let { "\n($it)" } ?: ""), Toast.LENGTH_LONG).show() } catch (_: Exception) { }
                    recordDiagnostic("STRICT_UNSUPPORTED mime=" + mime)
                    AudioPathLog.add(AudioPathLog.KIND_ERROR, "NOT PLAYED", title.ifEmpty { "Unknown track" }, "strict mode: FLAC, ALAC and WAV only" + (mime?.let { " • $it" } ?: ""))
                    syncPlaybackIcon(false)
                    refreshAudioInfo()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Build 1.61: there is no second decoder and no second output. A failure is reported as it is.
                playView.isEnabled = true
                val chain = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }.distinct().take(3).toList()
                val detail = chain.joinToString(" ← ").ifEmpty { "unsupported format" }
                val recent = DirectOutputState.historyText().lines().takeLast(3).joinToString("\n") { it.substringAfter(" | ") }
                statusView.text = "Playback failed • " + error.errorCodeName + ": " + detail + (if (recent.isNotBlank()) "\n" + recent else "")
                recordDiagnostic("PLAYBACK_ERROR code=" + error.errorCodeName + " detail=" + detail, error)
                AudioPathLog.add(AudioPathLog.KIND_ERROR, "PLAYBACK ERROR", this@MainActivity.tracks.getOrNull(currentIndex)?.title ?: "Unknown track", error.errorCodeName + ": " + detail)
                refreshAudioInfo()
            }
        }
        playerListener = listener
        exo.addListener(listener)
    }

    private fun playTrackAt(index: Int, autoplay: Boolean, queue: List<Int>? = null) {
        if (index !in tracks.indices) return
        startPlaybackKeepAlive()
        val requestedOrder = queue?.filter { it in tracks.indices }?.distinct().orEmpty()
        if (requestedOrder.isNotEmpty() && index in requestedOrder) {
            playbackOrder = requestedOrder
            playbackQueue = requestedOrder
        } else if (playbackOrder.isEmpty() || index !in playbackOrder) {
            val albumOrder = tracks.withIndex().filter { it.value.album == tracks[index].album }.map { it.index }
            playbackOrder = albumOrder.ifEmpty { listOf(index) }
            playbackQueue = playbackOrder
        }
        if (index !in playbackOrder) {
            playbackOrder = listOf(index)
            playbackQueue = playbackOrder
        }
        persistPlaybackState(force = true)
        persistQueueState()
        currentIndex = index
        notificationDismissed = false
        if (::queueRows.isInitialized) renderQueueRows()
        val track = tracks[index]
        pendingAutoplay = autoplay
        prepared = false
        sourceRate = null
        sourceBits = null; sourceFormat = null
        sourceBitrate = null
        updateHiResBadges()
        durationMs = 0
        titleView.text = track.title
        titleView.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        titleView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        titleView.setPadding(0, dp(2), 0, dp(1))
        artistView.text = track.artist
        albumView.text = track.album
        elapsed.text = "0:00"
        duration.text = "0:00"
        seek.progress = 0
        seek.max = 1
        playView.isEnabled = false
        syncPlaybackIcon(false)
        statusView.text = "Loading with the Media3 engine: " + track.title + "…"
        showMetadata(track.uri, track)
        loadArtwork(track)
        try {
            val exo = PlaybackKeepAliveService.getOrCreatePlayer(applicationContext)
            player = exo
            exo.setHandleAudioBecomingNoisy(true)
            attachPlaybackListener(exo, track)
            val items = playbackOrder.map { trackIndex ->
                val itemTrack = tracks[trackIndex]
                MediaItem.Builder()
                    .setMediaId(itemTrack.uri.toString())
                    .setUri(itemTrack.uri)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(itemTrack.title)
                        .setArtist(itemTrack.artist)
                        .setAlbumTitle(itemTrack.album)
                        .build())
                    .build()
            }
            val startPosition = playbackOrder.indexOf(index).coerceAtLeast(0)
            exo.setMediaItems(items, startPosition, 0L)
            exo.prepare()
            recordDiagnostic("PLAYBACK_QUEUE_SUBMITTED serviceOwned=true items=" + items.size + " startIndex=" + startPosition + " gapless=Media3-playlist")
        } catch (e: Exception) {
            playView.isEnabled = true
            statusView.text = "Unable to open file: " + e.message
            recordDiagnostic("MEDIA3_INITIALIZATION_FAILED", e)
        }
    }
    private fun playPrevious() {
        val p = player
        if (tracks.isEmpty() || p == null || p.mediaItemCount == 0) {
            statusView.text = "Choose a folder or audio file first."
            return
        }
        PlaybackKeepAliveService.dispatchAction(applicationContext, "previous")
    }
    private fun playNext() {
        val p = player
        if (tracks.isEmpty() || p == null || p.mediaItemCount == 0) {
            statusView.text = "Choose a folder or audio file first."
            return
        }
        PlaybackKeepAliveService.dispatchAction(applicationContext, "next")
    }
    private fun syncPlaybackIcon(isPlaying: Boolean? = null) {
        if (!::playView.isInitialized) return
        val playing = isPlaying ?: try { player?.isPlaying == true } catch (_: Exception) { false }
        updateAlbumPlayingMarks(playing)
        playView.setImageResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        playView.contentDescription = if (playing) "Pause" else "Play"
        playView.imageTintList = ColorStateList.valueOf(WHITE)
        if (::queueMiniPlayView.isInitialized) {
            queueMiniPlayView.setImageResource(if (playing) R.drawable.ic_pause_s else R.drawable.ic_play_s)
            queueMiniPlayView.contentDescription = if (playing) "Pause" else "Play"
            queueMiniPlayView.imageTintList = ColorStateList.valueOf(accentColor)
        }
    }

    /**
     * Build 2.9: soft light behind a button instead of the circular ripple. A radial white gradient that is brightest in
     * the centre and fades to fully transparent at the edge (so no ring or hard circle is ever visible). It fades in
     * quickly on touch and fades out slowly on release.
     */
    private class GlowDrawable(private val extraPx: Float = 0f, private val light: Boolean = false) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var glow = 0f
        private var baseAlpha = 255
        private var animator: android.animation.ValueAnimator? = null

        fun glowTo(target: Float, durationMs: Long) {
            animator?.cancel()
            val a = android.animation.ValueAnimator.ofFloat(glow, target)
            a.duration = durationMs
            a.interpolator = DecelerateInterpolator()
            a.addUpdateListener { glow = it.animatedValue as Float; invalidateSelf() }
            animator = a
            a.start()
        }

        /** Build 2.12: on release the light first jumps to (at least) a clearly visible level, then fades out slowly, so even a quick tap shows it. */
        fun fadeOut(durationMs: Long) {
            if (glow < 0.85f) glow = 0.85f
            glowTo(0f, durationMs)
        }

        /** Build 2.12: the light is as big as the button plus a little margin (extraPx), instead of fading out under the icon.
         *  Build 2.15: glow radius cut to 50%; Build 2.16: raised to 75% (GLOW_SIZE_SCALE); shape, opacity and animation unchanged. */
        private fun radius(b: android.graphics.Rect): Float =
            ((minOf(b.width(), b.height()) / 2f + extraPx) * GLOW_SIZE_SCALE).coerceAtLeast(1f)

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            paint.shader = android.graphics.RadialGradient(
                bounds.exactCenterX(), bounds.exactCenterY(), radius(bounds),
                // Build 2.14: peak opacity 30% (was 50%: 0x80); the other stops scaled by the same factor (0.6).
                if (light) intArrayOf(0x4D000000, 0x3D000000, 0x1D000000, 0x00000000)
                else intArrayOf(0x4DFFFFFF, 0x3DFFFFFF, 0x1DFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 0.82f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: android.graphics.Canvas) {
            if (glow <= 0.01f) return
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            paint.alpha = (glow * baseAlpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius(b), paint)
        }

        override fun setAlpha(alpha: Int) { baseAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Build 2.9: white icon button with a soft glow on touch and a tiny press (scale down, smooth return). */
    private fun transportButton(iconRes: Int, description: String, paddingDp: Int, click: () -> Unit) = ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(WHITE)
        contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        background = GlowDrawable(dp(GLOW_EXTRA_DP).toFloat())
        isClickable = true; isFocusable = true
        setOnClickListener { click() }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    (v.background as? GlowDrawable)?.glowTo(1f, 120L)
                    v.animate().cancel()
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (v.background as? GlowDrawable)?.fadeOut(520L)
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(DecelerateInterpolator(1.4f)).start()
                }
            }
            false
        }
    }

    private fun startPlaybackKeepAlive() {
        val serviceIntent = Intent(this, PlaybackKeepAliveService::class.java).setAction(PlaybackKeepAliveService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            recordDiagnostic("PLAYBACK_SERVICE_START_REQUESTED foreground=true")
        } catch (e: Exception) {
            recordDiagnostic("PLAYBACK_SERVICE_START_FAILED", e)
        }
    }

    private fun togglePlayback() {
        val p = player
        if (p == null || !prepared) { statusView.text = "Choose a track from the library first."; return }
        // The service is the sole transport-command owner. Player.Listener updates the UI.
        notificationDismissed = false
        // A bounce or double tap within 150 ms is ignored here and in the service; accepting it would
        // pause and resume at once and leave the user unsure which state the player is in.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleTapAtMs < 150L) return
        lastToggleTapAtMs = now
        // Flip the icon on the tap itself instead of waiting for the player callback, so the button
        // visibly reacts at once and a second tap is not triggered by "nothing happened".
        val willPlay = !(try { p.isPlaying || p.playWhenReady } catch (_: Exception) { false })
        syncPlaybackIcon(willPlay)
        PlaybackKeepAliveService.dispatchAction(applicationContext, "toggle")
        // Safety net: if the command was dropped (for example a notification tap just before), show the real state.
        handler.removeCallbacks(resyncPlaybackIcon)
        handler.postDelayed(resyncPlaybackIcon, 350L)
    }

    private var lastToggleTapAtMs = 0L
    private val resyncPlaybackIcon = Runnable {
        if (!isFinishing) syncPlaybackIcon(try { player?.let { it.isPlaying || it.playWhenReady } == true } catch (_: Exception) { false })
    }

    private fun showMetadata(uri: Uri, t: Track) {
        // SAF/MediaMetadataRetriever/MediaExtractor may block on slow storage. Never run them on the UI thread,
        // otherwise the artwork bitmap cannot be displayed even when its background task has already finished.
        // Give the cover decode a head start on the same storage: the technical details are only
        // needed for the audio-info text, so they start 250 ms later.
        handler.postDelayed({ if (!isFinishing) metadataExecutor.execute { readFileMetadata(uri, t) } }, 250L)
    }

    /** Build 2.0: short file type for the info row (FLAC / WAV / M4A ...), from the container and track MIME types. */
    private fun fileTypeLabel(containerMime: String, trackMime: String): String? {
        val m = (containerMime + " " + trackMime).lowercase(Locale.ROOT)
        return when {
            m.contains("flac") -> "FLAC"
            m.contains("wav") || m.contains("audio/raw") || m.contains("x-wav") -> "WAV"
            m.contains("alac") -> "M4A"
            m.contains("mp4") || m.contains("m4a") || m.contains("aac") || m.contains("mp4a") -> "M4A"
            m.contains("mpeg") || m.contains("mp3") -> "MP3"
            m.contains("ogg") || m.contains("vorbis") || m.contains("opus") -> "OGG"
            m.contains("aiff") -> "AIFF"
            else -> null
        }
    }

    private fun readFileMetadata(uri: Uri, t: Track) {
        run {
            val r = MediaMetadataRetriever()
            var fileDuration = "Unavailable"; var mime = "Unavailable"; var bitrate = "Unavailable"
            try {
                r.setDataSource(this@MainActivity, uri)
                fileDuration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: fileDuration
                mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mime
                bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: bitrate
            } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) { } }
            var rate = "Unavailable"; var channels = "Unavailable"; var trackMime = mime
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(this@MainActivity, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val type = if (f.containsKey(MediaFormat.KEY_MIME)) f.getString(MediaFormat.KEY_MIME) else null
                    if (type?.startsWith("audio/") == true) {
                        trackMime = type
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE).toString()
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toString()
                        if (f.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = f.getInteger(MediaFormat.KEY_BIT_RATE).toString()
                        break
                    }
                }
            } catch (_: Exception) { } finally { try { extractor.release() } catch (_: Exception) { } }
            val parsedRate = rate.toIntOrNull()
            val parsedBitrate = bitrate.toIntOrNull()
            val typeName = fileTypeLabel(mime, trackMime)
            val sniffed = AudioSniffer.sniff(this@MainActivity, uri)
            handler.post {
                if (isFinishing || tracks.getOrNull(currentIndex)?.uri != uri) return@post
                sourceRate = parsedRate
                sourceBitrate = parsedBitrate
                sourceBits = sniffed?.bits
                sourceFormat = typeName
                refreshAudioInfo()
            }
            // Hifi 1.24: what the MD5 check has to reproduce for this track (FLAC: MD5 in the header; WAV: MD5 of the data chunk).
            val expected = try { AudioSniffer.expectedFor(this@MainActivity, uri, sniffed, uri.lastPathSegment?.substringAfterLast('/') ?: t.title) } catch (_: Throwable) { null }
            handler.post {
                if (!isFinishing && tracks.getOrNull(currentIndex)?.uri == uri) DirectOutputState.expected = expected
            }
        }
    }

    private fun decodeArtwork(
        bytes: ByteArray,
        maxDimension: Int = 1200,
        config: android.graphics.Bitmap.Config = android.graphics.Bitmap.Config.ARGB_8888
    ): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1); inPreferredConfig = config }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Hifi 1.15: decodes so that the longest side is exactly [targetMax] (never upscaled). [decodeArtwork] only halves by powers of two,
     * so a 1400 px cover became 175 px for a 320 px target. Here the largest power-of-two sample that stays >= target is used,
     * then the result is scaled down smoothly to the exact size.
     */
    private fun decodeArtworkExact(
        bytes: ByteArray,
        targetMax: Int,
        config: android.graphics.Bitmap.Config = android.graphics.Bitmap.Config.ARGB_8888
    ): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= targetMax) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = config }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val decodedLongest = maxOf(decoded.width, decoded.height)
        if (decodedLongest <= targetMax) return decoded
        val ratio = targetMax.toFloat() / decodedLongest
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Cheap check (bounds only, no pixels) that the bytes really are a decodable picture. */
    private fun looksLikeImage(bytes: ByteArray): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0
    } catch (_: Throwable) { false }

    private fun readArtworkBytes(track: Track): ByteArray? {
        val key = track.uri.toString()
        synchronized(artworkBytesCache) { artworkBytesCache[key]?.let { return it } }
        var bytes: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(this@MainActivity, track.uri); bytes = retriever.embeddedPicture }
        catch (_: Exception) { }
        finally { try { retriever.release() } catch (_: Exception) { } }
        // Build 1.44: a cover embedded in the file wins; when there is none (WAV usually has none) or it cannot
        // be read as a picture, the jpg/png found in the track's folder is used instead.
        if (bytes != null && !looksLikeImage(bytes!!)) bytes = null
        // Build 1.45: WAV keeps its cover in an ID3 chunk, which MediaMetadataRetriever does not read.
        if (bytes == null && WavTagReader.isWavName(track.uri.lastPathSegment)) {
            bytes = WavTagReader.read(this@MainActivity, track.uri, wantPicture = true)?.picture
            if (bytes != null && !looksLikeImage(bytes!!)) bytes = null
        }
        if (bytes == null && track.coverUri != null) {
            try { contentResolver.openInputStream(track.coverUri)?.use { bytes = it.readBytes() } } catch (_: Exception) { }
            if (bytes != null && !looksLikeImage(bytes!!)) bytes = null
        }
        // Keep the in-memory cache bounded; exceptionally large embedded covers are not retained.
        if (bytes != null && bytes!!.size <= 8 * 1024 * 1024) synchronized(artworkBytesCache) {
            artworkBytesCache[key] = bytes!!
            // Bound by total bytes, not only entry count: 24 large embedded covers could
            // otherwise retain close to 192 MB on the LG G6 before bitmap memory is counted.
            var cachedBytes = artworkBytesCache.values.sumOf { it.size.toLong() }
            while (artworkBytesCache.isNotEmpty() && cachedBytes > 16L * 1024L * 1024L) {
                val oldestKey = artworkBytesCache.keys.iterator().next()
                cachedBytes -= artworkBytesCache.remove(oldestKey)?.size?.toLong() ?: 0L
            }
        }
        return bytes
    }

    private fun loadArtworkInto(target: ImageView, track: Track) {
        val key = "thumb:" + track.uri.toString()
        // Tag each rebuilt view so stale asynchronous results cannot paint the wrong album.
        target.setTag(key)
        val cached = bitmapArtworkCache.get(key)
        if (cached != null && !cached.isRecycled) {
            target.setImageBitmap(cached)
            return
        }
        // Build 2.2: a cover found to be missing is remembered on disk, so no file is opened for it at every start.
        if (thumbNoneMarker(track).exists()) return

        // A grid rebuild can request the same album artwork several times before the first
        // decode finishes. Join the existing request instead of queuing duplicate MediaMetadataRetriever
        // work; every still-valid target receives the result.
        synchronized(artworkTargetsInFlight) {
            val waiting = artworkTargetsInFlight[key]
            if (waiting != null) {
                waiting.add(java.lang.ref.WeakReference(target))
                return
            }
            artworkTargetsInFlight[key] = mutableListOf(java.lang.ref.WeakReference(target))
        }
        artworkExecutor.execute {
            val bitmap = bitmapArtworkCache.get(key) ?: loadThumbFromDisk(track) ?: (readArtworkBytes(track)
                ?.let { decodeArtworkExact(it, THUMB_PX, android.graphics.Bitmap.Config.ARGB_8888) }
                ?.also { bitmapArtworkCache.put(key, it); saveThumbToDisk(track, it) }
                ?: run { try { thumbNoneMarker(track).createNewFile() } catch (_: Exception) { }; null })
            handler.post {
                val targets = synchronized(artworkTargetsInFlight) {
                    artworkTargetsInFlight.remove(key).orEmpty().toList()
                }
                if (bitmap != null && !isFinishing) {
                    targets.forEach { ref ->
                        val view = ref.get()
                        if (view != null && view.getTag() == key) view.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun thumbDiskFile(t: Track): File {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest((t.uri.toString() + "|" + (t.coverUri?.toString() ?: "") + "|thumb" + LIBRARY_CACHE_VERSION).toByteArray())
        return File(thumbDiskDir, digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }
    private fun thumbNoneMarker(t: Track): File = File(thumbDiskFile(t).path + ".none")

    /** Reads the saved 512 px cover of a track from disk (a few ms) and puts it in the memory cache. */
    private fun loadThumbFromDisk(t: Track): android.graphics.Bitmap? {
        return try {
            val file = thumbDiskFile(t)
            if (file.length() <= 0L) return null
            val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(file.path, options)?.also { bitmapArtworkCache.put("thumb:" + t.uri.toString(), it) }
        } catch (_: Throwable) { null }
    }

    private fun saveThumbToDisk(t: Track, bitmap: android.graphics.Bitmap) {
        try {
            val file = thumbDiskFile(t)
            val temporary = File(file.path + ".tmp")
            java.io.FileOutputStream(temporary).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            if (!temporary.renameTo(file)) temporary.delete()
        } catch (_: Throwable) { }
    }

    /** Loads the grid cover of every album from disk into memory, so the grid paints at once with no loading. Call off the UI thread. */
    private fun prewarmAlbumThumbs(library: List<Track>) {
        // Hifi 1.15: thumbnails are bigger now, so only the first albums of the (alphabetical) grid are loaded up front.
        val firstTrackOfAlbum = LinkedHashMap<String, Track>()
        for (t in library) if (!firstTrackOfAlbum.containsKey(t.album)) firstTrackOfAlbum[t.album] = t
        val firstAlbums = firstTrackOfAlbum.entries.sortedBy { it.key.lowercase(Locale.ROOT) }.take(PREWARM_ALBUMS)
        for ((_, t) in firstAlbums) {
            if (bitmapArtworkCache.get("thumb:" + t.uri.toString()) == null) loadThumbFromDisk(t)
        }
    }

    /**
     * Build 2.2: runs after the saved library is shown. Reads the folder quietly in the background (no page change,
     * no progress bar), reuses the saved tag data of files already known, and updates the lists only if something changed.
     */
    private fun backgroundRefreshLibrary(treeUri: Uri) {
        if (backgroundScanRunning) return
        backgroundScanRunning = true
        lastBackgroundScanAt = System.currentTimeMillis()
        val generation = scanGeneration
        val known = HashMap<String, Track>().apply { tracks.forEach { put(it.uri.toString(), it) } }
        recordDiagnostic("BG_SCAN_START known=" + known.size)
        Thread({
            try {
                val found = mutableListOf<Track>()
                val count = intArrayOf(0)
                var failed = false
                try { DocumentFile.fromTreeUri(this, treeUri)?.let { scanDirectory(it, found, mutableMapOf(), 0, count, known) } }
                catch (e: Exception) { failed = true; recordDiagnostic("BG_SCAN_FAILED", e) }
                // A failed or empty read never replaces a good saved library.
                if (failed || found.isEmpty()) return@Thread
                val sorted = synchronized(found) { found.toList() }.sortedWith(compareBy<Track> { it.album.lowercase(Locale.ROOT) }.thenBy { it.trackNumber }.thenBy { it.title.lowercase(Locale.ROOT) })
                val oldSignature = tracks.map { it.uri.toString() + "|" + (it.coverUri?.toString() ?: "") }.toSet()
                val newSignature = sorted.map { it.uri.toString() + "|" + (it.coverUri?.toString() ?: "") }.toSet()
                if (oldSignature == newSignature) { recordDiagnostic("BG_SCAN_NO_CHANGE tracks=" + sorted.size); return@Thread }
                prewarmAlbumThumbs(sorted)
                handler.post {
                    if (isFinishing || generation != scanGeneration) return@post
                    val previousCurrentUri = tracks.getOrNull(currentIndex)?.uri
                    remapPlaybackLists(tracks, sorted)
                    tracks = sorted
                    currentIndex = previousCurrentUri?.let { u -> sorted.indexOfFirst { it.uri == u } } ?: -1
                    noArtworkTracks.clear()
                    Thread({ saveLibraryCache(treeUri, sorted) }, "g6-library-cache-save").start()
                    try { renderLibrary() } catch (e: Throwable) { recordDiagnostic("BG_LIBRARY_RENDER_FAILED", e) }
                    librarySummary.text = sorted.size.toString() + " tracks • " + sorted.map { it.album }.distinct().size + " albums"
                    albumScanSummary.text = librarySummary.text
                    recordDiagnostic("BG_SCAN_UPDATED tracks=" + sorted.size)
                }
            } finally { backgroundScanRunning = false }
        }, "g6-library-bgscan").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    override fun onStart() {
        super.onStart()
        // Build 2.4: the takeover notification is gone after Close; opening the player again arms it again.
        if (!isFinishing) PlaybackKeepAliveService.armExclusive(this)
        // Coming back to the app later also checks the folder again (at most once per minute).
        if (tracks.isNotEmpty() && System.currentTimeMillis() - lastBackgroundScanAt > 60_000L) {
            getPreferences(MODE_PRIVATE).getString("music_tree_uri", null)?.let { backgroundRefreshLibrary(Uri.parse(it)) }
        }
    }

    /**
     * [provisional] = a low-resolution stand-in (library thumbnail) shown while the full cover decodes.
     * It must not trigger a notification/widget refresh; the real cover does that when it arrives.
     */
    private fun showPlayerArtwork(bitmap: android.graphics.Bitmap?, provisional: Boolean = false, key: String? = null) {
        if (bitmap != null && !isFinishing) {
            playerCoverBitmap = bitmap; playerCoverView?.bitmap = bitmap; artPlaceholder.visibility = View.GONE
                        // Classic theme: the cover is softened and darkened behind every page instead of recolouring them.
            updateDarkBackdrop(bitmap)
            applyAccent(key?.let { accentCache[it] } ?: AlbumAccent.from(bitmap))   // Hifi 2.1
            // Colour comes from the cover that is on screen. A provisional thumbnail is sampled but not remembered.
            applyPlayerPalette(DARK_SURFACE)
            if (provisional) return
            if (prepared) updatePlaybackNotification(try { player?.isPlaying == true } catch (_: Exception) { false })
        } else {
            playerCoverBitmap = null; playerCoverView?.bitmap = null; artPlaceholder.visibility = View.VISIBLE; artPlaceholder.text = "NO COVER ART"; artPlaceholder.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            updateDarkBackdrop(null)
            applyAccent(AlbumAccent.NEUTRAL)   // Hifi 2.1: no cover = white accent
            applyPlayerPalette(DARK_SURFACE)
        }
    }

    private fun artworkDiskFile(t: Track): File {
        // Build 1.44: the folder picture is part of the key, so a track that was cached as "no cover"
        // before its folder picture was found gets a fresh lookup.
        // Build 1.45: the key also carries the cache version, so covers cached as "none" before the WAV tag reader existed are tried again.
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest((t.uri.toString() + "|" + (t.coverUri?.toString() ?: "") + "|v" + LIBRARY_CACHE_VERSION + "|np" + NOW_PLAYING_COVER_PX).toByteArray())   // Hifi 1.44: bigger covers get their own cache files
        return File(artworkDiskDir, digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    /** Remove the oldest cached covers once the on-disk cache passes ~96 MB. Runs once, in the background. */
    private fun pruneArtworkDiskCache() {
        artworkBackgroundExecutor.execute {
            try {
                val files = artworkDiskDir.listFiles()?.filter { it.isFile } ?: return@execute
                var total = files.sumOf { it.length() }
                val limit = 96L * 1024L * 1024L
                if (total <= limit) return@execute
                for (f in files.sortedBy { it.lastModified() }) {
                    if (total <= limit * 3 / 4) break
                    total -= f.length()
                    f.delete()
                }
            } catch (_: Throwable) { }
        }
    }

    /** Small cover for the notification, lock screen and MediaSession; created off the UI thread when possible. */
    private fun notificationArtworkFor(track: Track, full: android.graphics.Bitmap?, badge: Boolean = true): android.graphics.Bitmap? {
        if (full == null || full.isRecycled) return null
        // Build 2.16: a 24-bit track gets the Hi-Res badge on the notification / lock screen cover.
        // Build 2.17: the widgets get the plain cover (badge = false) and draw the badge themselves after cropping (round cover on the 2x2).
        val hires = badge && isHiResNow() && tracks.getOrNull(currentIndex)?.uri == track.uri
        val key = track.uri.toString() + if (hires) "|hires" else ""
        notificationArtworkCache.get(key)?.let { if (!it.isRecycled) return it }
        val longest = maxOf(full.width, full.height).coerceAtLeast(1)
        var small = if (longest <= 512) full else {
            val ratio = 512f / longest
            android.graphics.Bitmap.createScaledBitmap(full, (full.width * ratio).toInt().coerceAtLeast(1), (full.height * ratio).toInt().coerceAtLeast(1), true)
        }
        if (hires) {
            try {
                val copy = small.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                if (copy != null) {
                    val badge = HiResBadgeDrawable(this)
                    badge.setBounds(0, 0, copy.width, copy.height)
                    badge.draw(android.graphics.Canvas(copy))
                    small = copy
                }
            } catch (_: Throwable) { }
        }
        notificationArtworkCache.put(key, small)
        return small
    }

    private fun decodePlayerArtwork(t: Track): android.graphics.Bitmap? {
        val key = t.uri.toString()
        playerArtworkCache.get(key)?.let { if (!it.isRecycled) return it }
        if (noArtworkTracks.contains(key)) return null
        // The prefetch and the on-screen request can ask for the same track at the same moment:
        // let the second one wait for the first instead of reading the file twice.
        synchronized(artworkDecodeLocks.getOrPut(key) { Any() }) {
            playerArtworkCache.get(key)?.let { if (!it.isRecycled) return it }
            if (noArtworkTracks.contains(key)) return null
            val diskFile = artworkDiskFile(t)
            val noneMarker = File(diskFile.path + ".none")
            var bitmap: android.graphics.Bitmap? = null
            if (diskFile.length() > 0L) {
                // Fast path: the cover was already extracted on an earlier play (JPEG on app cache storage).
                bitmap = try {
                    BitmapFactory.decodeFile(diskFile.path, BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 })
                } catch (_: Throwable) { null }
                if (bitmap == null) diskFile.delete()
            } else if (noneMarker.exists()) {
                noArtworkTracks.add(key)
                return null
            }
            if (bitmap == null) {
                // Hifi 1.44: the cover is now the clear top of the Now Playing page (zoomed to the screen), so it keeps up to 2400 px
                // (it was 1200 px, and the old power-of-two halving could leave a big cover at only 600 - 750 px). Never upscaled.
                bitmap = readArtworkBytes(t)?.let { decodeArtworkExact(it, NOW_PLAYING_COVER_PX, android.graphics.Bitmap.Config.ARGB_8888) }
                if (bitmap == null) {
                    noArtworkTracks.add(key)
                    try { noneMarker.createNewFile() } catch (_: Throwable) { }
                    return null
                }
                val toSave: android.graphics.Bitmap = bitmap
                artworkBackgroundExecutor.execute {
                    try {
                        val tmp = File(diskFile.path + ".tmp")
                        tmp.outputStream().use { out -> toSave.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out) }
                        if (!tmp.renameTo(diskFile)) tmp.delete()
                    } catch (_: Throwable) { }
                }
            }
            val result: android.graphics.Bitmap = bitmap ?: return null
            playerArtworkCache.put(key, result)
            // Dominant colour is worked out here, off the UI thread, together with the cover itself.
            dominantColorFor(key, result, remember = true)
            if (accentCache.size > 400) accentCache.clear()
            accentCache.getOrPut(key) { AlbumAccent.from(result) }
            notificationArtworkFor(t, result)
            return result
        }
    }

    /** Warm the cache for neighbouring tracks so the next skip shows its cover immediately. */
    private fun prefetchNeighbourArtwork() {
        val order = playbackOrder
        if (order.isEmpty()) return
        val pos = order.indexOf(currentIndex).takeIf { it >= 0 } ?: return
        val neighbours = listOfNotNull(order.getOrNull(pos + 1), order.getOrNull(pos - 1), order.getOrNull(pos + 2))
            .mapNotNull { tracks.getOrNull(it) }
        if (neighbours.isEmpty()) return
        // A newer request (the user skipped again) cancels the remaining work of the older one.
        val myToken = ++prefetchToken
        artworkBackgroundExecutor.execute {
            for (n in neighbours) {
                if (myToken != prefetchToken) break
                try { decodePlayerArtwork(n) } catch (_: Throwable) { }
            }
        }
    }

    private fun loadArtwork(t: Track) {
        val token = ++artToken
        val key = t.uri.toString()
        // Cache hit: swap the cover on the same frame, no hide/placeholder flash and no thread hop.
        val cached = playerArtworkCache.get(key)
        if (cached != null && !cached.isRecycled) {
            showPlayerArtwork(cached, key = key)
            prefetchNeighbourArtwork()
            return
        }
        if (noArtworkTracks.contains(key)) {
            showPlayerArtwork(null)
            prefetchNeighbourArtwork()
            return
        }
        // Cache miss: never blank the screen. Show the library thumbnail right away if it is already
        // decoded; otherwise the previous cover stays until the new one is ready.
        bitmapArtworkCache.get("thumb:$key")?.takeIf { !it.isRecycled }?.let { showPlayerArtwork(it, provisional = true) }
        // Dedicated executor means library thumbnails can never block Now Playing artwork.
        playbackArtworkExecutor.execute {
            // The user already skipped past this track: do not spend I/O on it.
            if (token != artToken) return@execute
            val bitmap = decodePlayerArtwork(t)
            handler.post {
                if (token != artToken) return@post
                showPlayerArtwork(bitmap, key = key)
                prefetchNeighbourArtwork()
            }
        }
    }



    /** Build 2.16: the playing track is 24-bit (or more) = hi-res. */
    /** Prefer the live header read, but use the scan cache immediately while a new track is preparing. */
    private fun isHiResNow(): Boolean = ((sourceBits ?: tracks.getOrNull(currentIndex)?.bitDepth) ?: 0) >= 24
    private var hiResBadgeShown = false

    /** Build 2.31: the cached source depth keeps the badge stable across 24-bit track changes and visible before playback prepares. */
    private fun updateHiResBadges() {
        val hires = isHiResNow()
        if (hires == hiResBadgeShown) return
        hiResBadgeShown = hires
        hiResLogoView?.visibility = if (hires) View.VISIBLE else View.GONE
        if (::queueMiniArt.isInitialized) queueMiniArt.foreground = if (hires) HiResBadgeDrawable(this, dp(11).toFloat()) else null
        if (prepared) updatePlaybackNotification(try { player?.isPlaying == true } catch (_: Exception) { false })
    }

    /** Build 2.31: persistent badge for each cached album/track cover, independent of the currently playing song. */
    private fun applyTrackHiResBadge(target: ImageView, track: Track) {
        target.foreground = if ((track.bitDepth ?: 0) >= 24) HiResBadgeDrawable(this) else null
    }

    private fun applyAlbumHiResBadge(target: ImageView, albumTracks: List<Track>) {
        target.foreground = if (albumTracks.any { (it.bitDepth ?: 0) >= 24 }) HiResBadgeDrawable(this) else null
    }

    /**
     * Build 1.61: the info line under the progress bar. It says what really reaches the native DIRECT output:
     * the source (rate / bit depth read from the file header) and the PCM that the decoder handed to the output.
     * BIT-PERFECT is only shown when the output is the DIRECT track, the rate is unchanged and the depth is not reduced.
     */
    private fun refreshAudioInfo() {
        updateHiResBadges()
        if (!::audioInfoInline.isInitialized || !::audioStatusInline.isInitialized) return
        val srcRate = sourceRate
        val srcBits = sourceBits
        val sourceKhz = srcRate?.let { String.format(Locale.US, "%.1f", it / 1000f) } ?: "?"
        val bitsLabel = srcBits?.let { " / $it-bit" } ?: ""
        val direct = prepared && player != null && DirectOutputState.active
        val jack = wiredJackConnected()
        val device = if (jack) "headset" else "speaker"
        val green = 0xFF5BD98C.toInt()
        val orange = 0xFFFFA43A.toInt()
        val grey = 0xFFA4A4B2.toInt()
        // Build 2.0: row 1 = file type + source -> output (no headset / speaker here).
        // Row 2 = route verdict + headset / speaker + a grey note about the data. Both rows are one line and scroll when too long.
        val typeLabel = sourceFormat?.let { "$it • " } ?: ""
        val formatLine: String
        var verdict: String
        var note = ""
        val color: Int
        if (!direct) {
            formatLine = "$typeLabel$sourceKhz kHz$bitsLabel"
            verdict = "● DIRECT OUTPUT NOT ACTIVE"
            color = orange
        } else {
            val outRate = DirectOutputState.rate
            val outKhz = String.format(Locale.US, "%.1f", outRate / 1000f)
            formatLine = "$typeLabel$sourceKhz kHz$bitsLabel → $outKhz kHz"
            // The green label is about the ROUTE only (same as the adb dump): DIRECT flag, not the mixer thread,
            // headphone jack. What the samples contain is a separate grey note, never an orange failure.
            val routeDirect = DirectOutputState.routeIsDirect()
            when {
                srcRate != null && srcRate != outRate -> { verdict = "● SAMPLE RATE CHANGED"; color = orange }
                srcBits != null && DirectOutputState.inputBits < srcBits -> { verdict = "● DEPTH REDUCED TO ${DirectOutputState.inputBits}-BIT"; color = orange }
                routeDirect == false -> { verdict = "● NOT ON THE DIRECT THREAD (MIXER)"; color = orange }
                !jack -> { verdict = "● NOT BIT-PERFECT"; color = orange }
                srcBits != null -> {
                    // Hifi 1.27: the label now follows the DATA as well as the route. The MD5 verdict of THIS track is
                    // authoritative: DIFFERENT -> orange, MATCH -> green (also for a genuine 16-bit recording in a 24-bit
                    // file, whose low byte is legitimately empty). Without a verdict yet (or for formats that cannot be
                    // checked) a 24-bit file whose low 8 bits stay empty is NOT shown as bit-perfect.
                    val checkResult = DirectOutputState.checksumResult
                    when {
                        checkResult == DirectOutputState.CHECK_DIFFERENT -> { verdict = "● DATA DIFFERS FROM FILE"; color = orange }
                        checkResult == DirectOutputState.CHECK_MATCH -> { verdict = "● BIT-PERFECT"; color = green }
                        srcBits > 16 && DirectOutputState.depthVerdict() == 2 -> { verdict = "● LOW 8 BITS EMPTY"; color = orange }
                        else -> { verdict = "● BIT-PERFECT"; color = green }
                    }
                }
                else -> { verdict = "● NATIVE DIRECT • 24-bit packed"; color = green }
            }
        }
        // Hifi 1.40: the MD5 verdict is no longer written here (it went by too fast); see LOG AUDIO PATH in the Setting window.
        val tail = " • $device$note"
        val status = android.text.SpannableString(verdict + tail)
        status.setSpan(android.text.style.ForegroundColorSpan(color), 0, verdict.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        status.setSpan(android.text.style.ForegroundColorSpan(grey), verdict.length, status.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (audioInfoInline.text.toString() != formatLine) audioInfoInline.text = formatLine
        audioStatusInline.text = status
        audioInfoInline.isSelected = true
        audioStatusInline.isSelected = true
    }

    /** Build 1.66: is a wired headphone / jack output connected right now? */
    private fun wiredJackConnected(): Boolean {
        val outputs = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Exception) { return true }
        return outputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Playback controls and information"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
    private fun createMediaSession() {
        // The playback service owns the session and all transport callbacks.
        mediaSession = PlaybackKeepAliveService.getOrCreateMediaSession(applicationContext)
    }
    private fun updatePlaybackNotification(isPlaying: Boolean) {
        if (notificationDismissed || !prepared || currentIndex !in tracks.indices) return
        val track = tracks[currentIndex]
        // Cover of THIS track from the caches (never whatever the ImageView still shows), at 512 px at most.
        val trackKey = track.uri.toString()
        val fullCover = playerArtworkCache.get(trackKey)?.takeIf { !it.isRecycled }
        val cover: android.graphics.Bitmap? = if (fullCover != null) notificationArtworkFor(track, fullCover)
            else bitmapArtworkCache.get("thumb:$trackKey")?.takeIf { !it.isRecycled }
        // Build 2.17: widgets get the cover without the badge; the widget code draws it on its own crop.
        val widgetBitmap = if (fullCover != null) notificationArtworkFor(track, fullCover, badge = false) else cover
        val widgetHiRes = isHiResNow()
        val widgetContext = applicationContext
        val widgetTitle = track.title
        val widgetArtist = track.artist
        val widgetAlbum = track.album
        val widgetPosition = try { player?.currentPosition?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0 } catch (_: Exception) { 0 }
        val widgetDuration = try { player?.duration?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0 } catch (_: Exception) { 0 }
        // Widget rendering may write artwork to disk and rebuild RemoteViews. Serialize it away
        // from the UI thread so a notification Play/Pause tap does not wait on file I/O.
        try {
            notificationWidgetExecutor.execute {
                try {
                    G6Widgets.refresh(
                        widgetContext, widgetTitle, widgetArtist, widgetAlbum, isPlaying,
                        widgetBitmap, widgetPosition, widgetDuration, widgetHiRes
                    )
                } catch (e: Exception) {
                    android.util.Log.w("G6HiFiPlayer", "Async widget refresh failed", e)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            android.util.Log.w("G6HiFiPlayer", "Widget refresh skipped during shutdown")
        }
        // Avoid rebuilding and persisting the full diagnostic event snapshot for every toggle.
        android.util.Log.d("G6HiFiPlayer", "NOTIFICATION_UPDATE playing=" + isPlaying + " title=" + track.title)
        val session = mediaSession
        // Track metadata does not change during Play/Pause. Avoid rebuilding the metadata
        // bundle and copying artwork on every notification tap.
        val metadataKey = track.uri.toString() + ":" + (cover?.let { System.identityHashCode(it) } ?: 0)
        if (metadataKey != lastNotificationMetadataKey) {
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
            // Publish the real album cover to MediaSession; Android may apply its own lock-screen blur.
            if (cover != null && !cover.isRecycled) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
                metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, cover)
            }
            session?.setMetadata(metadata.build())
            lastNotificationMetadataKey = metadataKey
        }
        // The playback service is the single owner of the notification and of the playback state.
        // It rebuilds the notification once (coalesced) from the player and the metadata above, so the
        // Activity and the service no longer post two competing notifications for every event.
        PlaybackKeepAliveService.requestNotificationRefresh()
    }
    private fun stopAndDismissPlayback() {
        pendingAutoplay = false
        prepared = false
        seeking = false
        try { PlaybackKeepAliveService.stopPlayback(this) } catch (_: Exception) { }
        playerListener?.let { listener -> player?.let { p -> try { p.removeListener(listener) } catch (_: Exception) { } } }
        playerListener = null
        player = null
        currentIndex = -1
        sourceRate = null
        sourceBits = null; sourceFormat = null
        sourceBitrate = null
        durationMs = 0
        playView.isEnabled = false
        syncPlaybackIcon(false)
        titleView.text = "Nothing playing"
        artistView.text = ""
        albumView.text = ""
        elapsed.text = "0:00"
        duration.text = "0:00"
        seek.progress = 0
        seek.max = 1
        statusView.text = "Playback stopped."
        try {
            mediaSession?.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).setActions(0L).build())
            mediaSession?.isActive = false
        } catch (_: Exception) { }
        try { stopService(Intent(this, PlaybackKeepAliveService::class.java)) } catch (_: Exception) { }
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { }
        try { G6Widgets.refresh(this, "", "", "", false, null, 0, 0) } catch (_: Exception) { }
        refreshAudioInfo()
        recordDiagnostic("PLAYER_CLOSE playback=stopped player=released mediaSession=inactive foregroundService=stopped")
    }

    private fun resetPlaybackUiAfterServiceStop() {
        pendingAutoplay = false
        prepared = false
        seeking = false
        playerListener?.let { listener -> player?.let { p -> try { p.removeListener(listener) } catch (_: Exception) { } } }
        playerListener = null
        player = null
        currentIndex = -1
        sourceRate = null
        sourceBits = null; sourceFormat = null
        sourceBitrate = null
        durationMs = 0
        if (::playView.isInitialized) {
            playView.isEnabled = false
            syncPlaybackIcon(false)
        }
        if (::titleView.isInitialized) titleView.text = "Nothing playing"
        if (::artistView.isInitialized) artistView.text = ""
        if (::albumView.isInitialized) albumView.text = ""
        if (::elapsed.isInitialized) elapsed.text = "0:00"
        if (::duration.isInitialized) duration.text = "0:00"
        if (::seek.isInitialized) { seek.progress = 0; seek.max = 1 }
        if (::statusView.isInitialized) statusView.text = "Playback stopped."
        mediaSession = null
    }

    private fun handleNotificationAction(action: String) {
        when (action) {
            "previous" -> { notificationDismissed = false; playPrevious() }
            "next" -> { notificationDismissed = false; playNext() }
            "toggle" -> {
                notificationDismissed = false
                // Player.Listener owns notification publication to avoid duplicate, laggy updates.
                togglePlayback()
            }
            "close", "dismiss" -> {
                notificationDismissed = true
                stopAndDismissPlayback()
                try { finishAndRemoveTask() } catch (_: Exception) { finish() }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("g6_open_player", false)) show("player")
        intent.getStringExtra("notification_action")?.let { handleNotificationAction(it) }
    }



    private fun show(which: String) {
        val previousPage = currentPage
        currentPage = which
        if (::headerTitle.isInitialized) headerTitle.text = if (which == "library") "Album" else ""   // Hifi 1.51: no "Now Playing" title on Now Playing (the icons stay)
        headerGear?.visibility = if (which == "library") View.VISIBLE else View.GONE
        headerThemeIcons.forEach { it.visibility = if (which == "library") View.GONE else View.VISIBLE }
        queueMiniHost?.visibility = if (which == "library") View.VISIBLE else View.GONE   // the mini player only lives on Album
        if (which == "library" && previousPage != "library" && selectedAlbum == null) restoreListScroll()
        // All pages share the cover-coloured surface; the system bars follow it.
        applySystemBars()
        if (which != "player" && tabsPaletteDirty) retintPages()
        playerPanel.visibility = if (which == "player") View.VISIBLE else View.GONE
        playerCoverView?.visibility = if (which == "player") View.VISIBLE else View.GONE
        libraryPanel.visibility = if (which == "library") View.VISIBLE else View.GONE
        songsPanel.visibility = if (which == "songs") View.VISIBLE else View.GONE
        settingsPanel.visibility = if (which == "settings" || which == "audio" || which == "file") View.VISIBLE else View.GONE
        navState(navPlayer, which == "player")
        navState(navLibrary, which == "library")
        navState(navFile, which == "settings" || which == "audio" || which == "file")
        updateBottomFade()
    }

    /**
     * Hifi 1.38: Album: the list fades softly (as in 1.20) only behind the mini player card, toward the page backdrop: starts 24dp above
     * the card; Hifi 1.80: the fade keeps going under the card and through the navigation-bar area, fully gone at the middle of the navigation bar (Hifi 1.81; 1.80: bottom edge). The top is a plain cut under the header.
     * Distances are measured from the bottom of the screen.
     */
    private fun updateBottomFade() {
        val hostH = queueMiniHost?.height?.takeIf { it > 0 } ?: (dp(76) + bottomInsetPx)
        scrollFadeHost?.configureFade(currentPage == "library", hostH + dp(24), bottomInsetPx / 2)   // Hifi 1.81: fully gone at the middle of the navigation bar (1.80: at the bottom edge; 1.79: middle of the mini player card)
    }

    private fun navButton(label: String, description: String, click: () -> Unit) = FrameLayout(this).apply {
        contentDescription = description
        // Preserve Build 78 equal-width hit targets and the centered selection pill;
        // replace only the icon content with a centered text label.
        clipChildren = false
        clipToPadding = false
        val tabLabel = text(label, 13f, MUTED, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(tabLabel, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        background = navBackground(false)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun navBackground(selected: Boolean): RippleDrawable {
        // Symmetric horizontal insets keep the selected rectangle centered inside
        // each equal-width tab; icon sizing is independent from this background.
        val pill = android.graphics.drawable.InsetDrawable(
            shape(if (selected) selectedHighlight() else Color.TRANSPARENT, 16),
            dp(16), dp(4), dp(16), dp(4)
        )
        return RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), pill, null)
    }
    private fun selectedHighlight(): Int = withAlpha(accentColor, 0x33)

    private fun navState(view: FrameLayout, selected: Boolean) {
        (view.getChildAt(0) as? TextView)?.setTextColor(if (selected) accentColor else MUTED)
        view.background = navBackground(selected)
    }
    private fun iconAction(label: String, click: () -> Unit) = text(label, 20f, WHITE, true).apply {
        gravity = Gravity.CENTER
        themed { background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), shape(Color.TRANSPARENT, 24), null) }
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun addToQueue(index: Int) {
        if (index !in tracks.indices) return
        val albumIndices = tracks.withIndex()
            .filter { it.value.album == tracks[index].album }
            .map { it.index }
        playbackQueue = (playbackQueue + albumIndices.ifEmpty { listOf(index) }).distinct()
        // The visible queue and the sequence used by STATE_ENDED/Next must be identical.
        playbackOrder = playbackQueue.toList()
        persistQueueState()
        renderQueueRows()
        recordDiagnostic("QUEUE_ALBUM_ADDED album=${tracks[index].album} tracks=${albumIndices.size} queueSize=${playbackOrder.size}")
        Toast.makeText(this, "Album added to queue: ${tracks[index].album}", Toast.LENGTH_SHORT).show()
    }

    private fun addAlbumToQueue(indices: List<Int>, album: String) {
        val valid = indices.filter { it in tracks.indices }.distinct()
        if (valid.isEmpty()) return
        playbackQueue = (playbackQueue + valid).distinct()
        // Keep queue display and playback traversal in sync; otherwise playback can stop at
        // the end of the original album even though another album is visibly queued.
        playbackOrder = playbackQueue.toList()
        persistQueueState()
        renderQueueRows()
        recordDiagnostic("QUEUE_ALBUM_ADDED album=$album tracks=${valid.size} queueSize=${playbackOrder.size}")
        Toast.makeText(this, "Album added to queue: $album", Toast.LENGTH_SHORT).show()
    }

    private fun renderQueueRows() {
        if (!::queueRows.isInitialized) return
        updateQueueMiniPlayer()
        queueRowsSig = playbackQueue.hashCode() * 31 + tracks.size * 7 + currentIndex
        queueRows.removeAllViews()
        queuePlayingMarks.clear()
        val queue = playbackQueue.filter { it in tracks.indices }
        if (queue.isEmpty()) { queueRows.addView(text("The queue is empty. Add an album from the Albums tab.", 13f, MUTED, false)); return }
        queue.forEachIndexed { position, trackIndex ->
            val track = tracks[trackIndex]
            // Build 2.5: same row as the opened-album list (size, spacing, playing highlight and bars), text only, "x" instead of "+".
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                isClickable = true
                isFocusable = true
                setOnClickListener { playTrackAt(trackIndex, true, playbackQueue); queueDialog?.dismiss(); if (currentPage != "player") showPlayerAnimated() }   // Hifi 1.37: back to Now Playing at once
            }
            val numberView = text((position + 1).toString(), 13f, MUTED, false).apply { gravity = Gravity.CENTER }
            row.addView(numberView, LinearLayout.LayoutParams(dp(34), -2))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(4), 0) }
            val titleView = text(track.title, 13f, WHITE, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            details.addView(titleView)
            val fileType = track.uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }?.uppercase(Locale.ROOT).orEmpty()
            val parts = listOf(fileType, track.year.orEmpty(), track.artist).filter { it.isNotBlank() }
            if (parts.isNotEmpty()) {
                details.addView(text(parts.joinToString(" • "), 11f, MUTED, false).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val bars = EqualizerView(this, accentColor).apply { visibility = View.INVISIBLE }
            row.addView(bars, LinearLayout.LayoutParams(dp(16), dp(14)).apply { rightMargin = dp(2) })
            val remove = iconAction("×") {
                if (trackIndex == currentIndex) Toast.makeText(this, "The currently playing track cannot be removed from the active queue.", Toast.LENGTH_SHORT).show()
                else { playbackQueue = playbackQueue.filter { it != trackIndex }; playbackOrder = playbackQueue; persistQueueState(); renderQueueRows() }
            }.apply { textSize = 22f; setTextColor(if (trackIndex == currentIndex) accentColor else ACTION_GREY); contentDescription = "Remove ${track.title} from queue" }
            row.addView(remove, LinearLayout.LayoutParams(dp(40), dp(36)))
            queueRows.addView(row, LinearLayout.LayoutParams(-1, -2))
            queuePlayingMarks.add(AlbumRowMark(trackIndex, row, numberView, titleView, bars, remove))
        }
        updateAlbumPlayingMarks(try { player?.isPlaying == true } catch (_: Exception) { false })
    }
    /**
     * Hifi 1.37: the queue is a floating window like Setting and the album options: same background and corners (floatingBackground), same width
     * (screen minus 16dp each side), 80% of the screen height. Tapping a track plays it and closes the window (back on Now Playing); tapping outside
     * or Back closes it too. No mini player here. The rows are the same ones as before (renderQueueRows), moved into this window while it is open.
     */
    private fun openQueueWindow() {
        if (!::queueRows.isInitialized || queueDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this).create()
        val windowH = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            minimumWidth = floatingWindowWidth()
            minimumHeight = windowH
            background = floatingBackground()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("PLAYBACK QUEUE", 12f, MUTED, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, 0) },
            LinearLayout.LayoutParams(0, dp(38), 1f))
        header.addView(action("CLEAR", false) {
            playbackQueue = emptyList()
            playbackOrder = if (currentIndex in tracks.indices) listOf(currentIndex) else emptyList()
            persistQueueState()
            renderQueueRows()
            Toast.makeText(this, "Queue cleared.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(82), dp(36)))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        (queueRows.parent as? ViewGroup)?.removeView(queueRows)
        renderQueueRows()
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            addView(queueRows, ViewGroup.LayoutParams(-1, -2))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setView(panel)
        dialog.setOnDismissListener {
            (queueRows.parent as? ViewGroup)?.removeView(queueRows)
            if (queueDialog === dialog) queueDialog = null
        }
        queueDialog = dialog
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(floatingWindowWidth(), windowH)
        dialog.window?.setDimAmount(0.55f)
    }

    private fun showAlbumOptions(album: String, indices: List<Int>) {
        val dialog = AlertDialog.Builder(this).create()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            minimumWidth = floatingWindowWidth()
            background = floatingBackground()
        }
        panel.addView(text(album, 18f, WHITE, true).apply {
            setPadding(dp(2), dp(2), dp(2), dp(14)); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        fun option(label: String, onClick: () -> Unit) {
            val item = text(label, 14f, WHITE, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), shape(LINE, 14), null)   // 2.3: same colour as the unselected buttons in Setting
                isClickable = true; isFocusable = true
                setOnClickListener { dialog.dismiss(); onClick() }
            }
            panel.addView(item, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
        }
        option("▶  Play entire album") {
            if (indices.isNotEmpty()) { playTrackAt(indices.first(), true, indices); showPlayerAnimated() }
        }
        option("＋  Add album to queue") { addAlbumToQueue(indices, album) }
        dialog.setView(panel)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(floatingWindowWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setDimAmount(0.55f)
    }

    private fun flatSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)); setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    /** Build 2.0: in Setting only the button areas keep the dark rounded rectangle; descriptions sit on the plain page. */
    private fun buttonArea(horizontal: Boolean = true) = LinearLayout(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // Build 2.1: no dark rectangle behind the buttons either; the buttons sit directly on the page.
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        // Text drawn in the page ink / muted ink follows the cover palette.
        if (color == WHITE) themed { setTextColor(WHITE) } else if (color == MUTED) themed { setTextColor(MUTED) }
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun action(label: String, primary: Boolean, click: () -> Unit) = text(label, 11f, if (primary) BG else WHITE, true).apply {
        gravity = Gravity.CENTER
        themed {
            setTextColor(if (primary) BG else WHITE)
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), shape(if (primary) withAlpha(accentColor, 0xCC) else LINE, 14), null)
        }
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    /** Build 2.2: album page buttons without a background box: larger text, accent colour for the main one. */
    private fun flatAction(label: String, primary: Boolean, click: () -> Unit) = text(label, 15f, WHITE, true).apply {
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        setPadding(dp(4), dp(2), dp(4), dp(8))
        themed {
            setTextColor(WHITE)   // 2.3: both buttons white; they are plain buttons, not a selected state
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), shape(Color.TRANSPARENT, 10), null)
        }
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }

    private fun backArrowDrawable(): android.graphics.drawable.Drawable {
        val d = dp(40)
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() + 0.5f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path().apply {
            moveTo(d * 0.60f, d * 0.26f); lineTo(d * 0.36f, d * 0.50f); lineTo(d * 0.60f, d * 0.74f)
        }
        c.drawPath(path, paint)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    /** Build 2.4: clips a view (an album cover) to rounded corners, whatever its background is. */
    private fun View.roundCorners(radiusDp: Int) {
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
    }
    private fun describe(d: AudioDeviceInfo) = deviceName(d) + " (" + deviceType(d.type) + ", type=" + d.type + ", id=" + d.id + ")"
    private fun deviceName(d: AudioDeviceInfo) = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
    private fun deviceType(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker internal"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        else -> "other device"
    }
    private fun time(ms: Int): String {
        val s = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    /**
     * Hifi 1.35: ONE background for every floating window (Setting, long-press on an album) and for the mini player card: the page surface
     * mixed 12% toward the ink colour, solid, 24dp corners. It follows the theme because callers re-apply it through themed {}.
     */
    private fun floatingBackground() = shape(if (lightTheme) (if (hasAlbumTint) mixColors(albumTint, Color.BLACK, 0.05f) or 0xFF000000.toInt() else mixColors(playerSurface, playerInk, 0.12f)) else albumDark, 16)   // Hifi 2.1: dark = the darkened accent colour of the cover (grey 0xFF1E1E1E without a colour)   // Hifi 1.85: corners 16dp (1.83/1.84: 18dp; 1.36: 24 -> 12dp); the widget cards are 4dp more (20dp)

    /** Hifi 1.23: floating windows (Setting, long-press on an album) are as wide as the mini player card: screen minus 16dp on each side. */
    private fun floatingWindowWidth() = resources.displayMetrics.widthPixels - dp(32)

    /** Hifi 1.5: header row above the page: title on the left; Album shows the gear. Hifi 2.0: the dark/light icons on Now Playing are removed. */
    private fun makeHeaderBar(statusInset: Int): FrameLayout {
        // The bar's own top padding is the status-bar inset; children are NOT offset again (that clipped them in 1.4).
        val bar = FrameLayout(this).apply { setPadding(0, statusInset, 0, 0); setBackgroundColor(Color.TRANSPARENT) }
        headerTitle = pageHeader("Now Playing", 20).apply { textSize = 20f }
        // Hifi 2.4: scan progress (small text + thin bar) to the right of the title, as long as the space up to the settings icon.
        scanProgressText = text("", 10f, MUTED, false).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
        }
        scanProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(accentColor)
            themed {
                progressTintList = ColorStateList.valueOf(accentColor)
                progressBackgroundTintList = ColorStateList.valueOf(withAlpha(accentColor, 0x40))
            }
        }
        val scanBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(scanProgressText, LinearLayout.LayoutParams(-1, -2))
            addView(scanProgress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(3) })
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(headerTitle, LinearLayout.LayoutParams(-2, dp(HEADER_H_DP)))
        titleRow.addView(scanBox, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        bar.addView(titleRow, FrameLayout.LayoutParams(-1, dp(HEADER_H_DP), Gravity.START or Gravity.TOP).apply { rightMargin = dp(HEADER_H_DP) + dp(8) })
        val gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings_fill)
            contentDescription = "Settings"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            themed {
                imageTintList = ColorStateList.valueOf(WHITE)
                background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), null, shape(Color.WHITE, 24))
            }
            isClickable = true; isFocusable = true
            setOnClickListener { openSettingsWindow() }
            visibility = View.GONE
        }
        headerGear = gear
        // Hifi 2.7: Dark / Light as two icons (moon, sun) at the right of the Now Playing header; the current mode is full strength, the other dimmed.
        fun themeIcon(res: Int, desc: String, forLight: Boolean) = ImageView(this).apply {
            setImageResource(res)
            contentDescription = desc
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            themed {
                imageTintList = ColorStateList.valueOf(WHITE)
                alpha = if (lightTheme == forLight) 1f else 0.45f
                background = RippleDrawable(ColorStateList.valueOf(withAlpha(accentColor, 0x30)), null, shape(Color.WHITE, 24))
            }
            isClickable = true; isFocusable = true
            setOnClickListener { setLightTheme(forLight) }
            visibility = View.GONE
        }
        val moon = themeIcon(R.drawable.ic_moon_outline, "Dark theme", false)
        val sun = themeIcon(R.drawable.ic_sun_outline, "Light theme", true)
        headerThemeIcons = listOf(moon, sun)
        bar.addView(sun, FrameLayout.LayoutParams(dp(HEADER_H_DP), dp(HEADER_H_DP), Gravity.END or Gravity.TOP).apply { rightMargin = dp(8) })
        bar.addView(moon, FrameLayout.LayoutParams(dp(HEADER_H_DP), dp(HEADER_H_DP), Gravity.END or Gravity.TOP).apply { rightMargin = dp(8) + dp(HEADER_H_DP) })
        bar.addView(gear, FrameLayout.LayoutParams(dp(HEADER_H_DP), dp(HEADER_H_DP), Gravity.END or Gravity.TOP).apply { rightMargin = dp(8) })
        return bar
    }

    /** Hifi 1.4: floating settings window (Album page only: folder scan, columns, audio path log). Hifi 2.0: the Now Playing theme icons are gone. */
    /**
     * Hifi 1.41: LOG AUDIO PATH: the last 50 tracks (newest first), ONE line each: the track name on the left, cut with "..." where the result
     * starts, and the result on the right (MD5 MATCH, MD5 DIFFERENT, NOT PLAYED ...). Normal lines use the regular text colour; DIFFERENT / not
     * comparable are orange, refused files and errors are red. (The time and the details are still saved with every entry.)
     */
    private fun audioPathLogView(): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val entries = AudioPathLog.snapshot()
        if (entries.isEmpty()) {
            list.addView(text("No tracks logged yet. Play a FLAC, ALAC or WAV track.", 12f, MUTED, false))
        }
        for (e in entries) {
            val warn = when (e.kind) { AudioPathLog.KIND_ERROR -> 0xFFFF5252.toInt(); AudioPathLog.KIND_UNMATCH -> 0xFFFFA040.toInt(); else -> 0 }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            val name = text(e.name, 12f, WHITE, false).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                if (warn != 0) setTextColor(warn)
            }
            val result = text(e.head, 12f, WHITE, true).apply {
                maxLines = 1; gravity = Gravity.END; setPadding(dp(10), 0, 0, 0)
                if (warn != 0) setTextColor(warn)
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(result, LinearLayout.LayoutParams(-2, -2))
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun openSettingsWindow() {
        val dialog = AlertDialog.Builder(this).create()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            minimumWidth = floatingWindowWidth()
            background = floatingBackground()
        }
        if (currentPage == "library") {
            panel.addView(text("MUSIC LIBRARY SCAN", 12f, MUTED, true))
            panel.addView(action("CHOOSE MUSIC FOLDER", true) { dialog.dismiss(); pickFolder() },
                LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
            val previous = if (::librarySummary.isInitialized) librarySummary.text else "No folder has been scanned yet."
            librarySummary = text(previous.toString(), 12f, MUTED, false).apply { setPadding(0, dp(10), 0, 0) }
            panel.addView(librarySummary)
            panel.addView(text("ALBUM COLUMNS", 12f, MUTED, true).apply { setPadding(0, dp(18), 0, 0) })
            val columnButtons = buttonArea()
            fillColumnButtons(columnButtons)
            panel.addView(columnButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            panel.addView(text("LOG AUDIO PATH", 12f, MUTED, true).apply { setPadding(0, dp(18), 0, 0) })
            panel.addView(audioPathLogView(), LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(10) })
        }
        dialog.setView(panel)
        dialog.show()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setDimAmount(0.45f)
            w.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            w.setLayout(floatingWindowWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
            val lp = w.attributes
            val statusInset = resources.getIdentifier("status_bar_height", "dimen", "android").let { id -> if (id > 0) resources.getDimensionPixelSize(id) else dp(24) }
            lp.y = statusInset + dp(HEADER_H_DP); lp.x = 0
            w.attributes = lp
        }
    }

    /**
     * Hifi 1.38: the page follows the finger while dragging sideways: slide left on Now Playing goes to Album, slide right on Album comes back.
     * On release the page slides out and the other one slides in (distance or fling past the threshold) or springs back. The seek bar, horizontal
     * lists and a running animation never start a drag.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swipeDragging) {
            swipeVelocity?.addMovement(ev)
            val dx = ev.rawX - swipeDownX
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragPage(dx)
                MotionEvent.ACTION_UP -> {
                    swipeVelocity?.computeCurrentVelocity(1000)
                    finishPageDrag(dx, swipeVelocity?.xVelocity ?: 0f)
                }
                MotionEvent.ACTION_CANCEL -> finishPageDrag(0f, 0f)
            }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX; swipeDownY = ev.rawY
                val busy = swipeAnimating
                // Pages cannot be swiped on the seek bar, on a horizontal list, or mid-animation.
                swipeBlocked = busy ||
                    touchesSeekBar(ev.rawX.toInt(), ev.rawY.toInt()) ||
                    touchesHorizontalScroller(window.decorView, ev.rawX.toInt(), ev.rawY.toInt())
                swipeVelocity?.recycle()
                swipeVelocity = if (busy) null else android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                swipeVelocity?.addMovement(ev)
                val dx = ev.rawX - swipeDownX
                val dy = ev.rawY - swipeDownY
                if (!swipeBlocked && kotlin.math.abs(dx) > dp(18) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f &&
                    (currentPage == "player" || currentPage == "library")) {
                    swipeDragging = true
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    dragPage(dx)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> { swipeVelocity?.recycle(); swipeVelocity = null }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Album -> Now Playing (tapping the mini player, Back, play actions): the same slide as dragging right on Album. */
    private fun showPlayerAnimated() = slidePageTo(false)

    /**
     * Slides the current page out and the other one in (the same motion as a finished sideways drag, also used by taps). Going to Album the
     * mini player rises from the bottom with a soft overshoot; going back it sinks while the page leaves.
     */
    private fun slidePageTo(toLibrary: Boolean) {
        val v = pageContentView
        val token = ++pageAnimToken
        v?.animate()?.cancel()
        queueMiniHost?.animate()?.cancel()
        v?.let { it.translationX = 0f; it.alpha = 1f; it.translationY = 0f }
        coverSet(0f, 1f)
        queueMiniHost?.let { it.translationY = 0f; it.alpha = 1f }
        if (v == null || !canSwipeTo(toLibrary)) { swipeAnimating = false; show(if (toLibrary) "library" else "player"); return }
        slideCommit(v, toLibrary, token)
    }

    private fun slideCommit(v: View, toLibrary: Boolean, token: Int) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val sign = if (toLibrary) -1f else 1f
        val mini = queueMiniHost
        swipeAnimating = true
        if (!toLibrary && mini != null && mini.visibility == View.VISIBLE) {
            mini.animate().translationY(mini.height.toFloat()).alpha(0f).setDuration(110L)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f)).start()
        }
        coverFollow(sign * width * 0.6f, 0f, 110L, android.view.animation.AccelerateInterpolator(1.2f))
        v.animate().translationX(sign * width * 0.6f).alpha(0f).setDuration(110L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
            .withEndAction {
                if (token != pageAnimToken) return@withEndAction
                show(if (toLibrary) "library" else "player")
                mini?.animate()?.cancel()
                mini?.let { it.translationY = 0f; it.alpha = 1f }
                v.translationX = -sign * width * 0.25f
                v.alpha = 0f
                if (toLibrary) coverSet(0f, 1f) else { coverSet(-sign * width * 0.25f, 0f); coverFollow(0f, 1f, 240L, DecelerateInterpolator(1.8f)) }
                if (toLibrary) riseMini(token)
                v.animate().translationX(0f).alpha(1f).setDuration(240L).setInterpolator(DecelerateInterpolator(1.8f))
                    .withEndAction { if (token == pageAnimToken) swipeAnimating = false }.start()
            }.start()
    }

    /** The Album mini player comes up from the bottom edge with a soft overshoot (as in 1.17). */
    private fun riseMini(token: Int) {
        val m = queueMiniHost ?: return
        val from = (m.height.takeIf { it > 0 } ?: dp(90)).toFloat()
        val bounce = android.view.animation.OvershootInterpolator(1.15f)
        m.translationY = from; m.alpha = 0f
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 440L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                if (token != pageAnimToken) { cancel(); return@addUpdateListener }
                val t = a.animatedValue as Float
                m.translationY = from * (1f - bounce.getInterpolation(t))
                m.alpha = (t / 0.3f).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (token == pageAnimToken) { m.translationY = 0f; m.alpha = 1f }
                }
            })
            start()
        }
    }

    private fun canSwipeTo(toLibrary: Boolean) = (toLibrary && currentPage == "player") || (!toLibrary && currentPage == "library")

    private fun dragPage(dx: Float) {
        val v = pageContentView ?: return
        val width = resources.displayMetrics.widthPixels.toFloat()
        val shown = if (canSwipeTo(dx < 0)) dx else dx * 0.18f   // nothing on that side: only a small rubber-band
        v.translationX = shown
        v.alpha = 1f - (kotlin.math.abs(shown) / width).coerceIn(0f, 1f) * 0.55f
        coverSet(v.translationX, v.alpha)
    }

    /** The Now Playing cover lives behind the header, outside the page view, so every page slide is mirrored on it. */
    private fun coverSet(tx: Float, alpha: Float) {
        playerCoverView?.let { it.animate().cancel(); it.translationX = tx; it.alpha = alpha }
    }

    private fun coverFollow(tx: Float, alpha: Float, duration: Long, interpolator: android.animation.TimeInterpolator) {
        playerCoverView?.let { it.animate().cancel(); it.animate().translationX(tx).alpha(alpha).setDuration(duration).setInterpolator(interpolator).start() }
    }

    private fun finishPageDrag(dx: Float, velocityX: Float) {
        swipeDragging = false
        swipeVelocity?.recycle(); swipeVelocity = null
        val v = pageContentView ?: return
        val width = resources.displayMetrics.widthPixels.toFloat()
        val toLibrary = dx < 0
        val flung = kotlin.math.abs(velocityX) > dp(600) && (velocityX < 0) == toLibrary
        val commit = canSwipeTo(toLibrary) && (kotlin.math.abs(dx) > width * 0.28f || (flung && kotlin.math.abs(dx) > dp(30)))
        val token = ++pageAnimToken
        swipeAnimating = true
        v.animate().cancel()
        if (!commit) {
            coverFollow(0f, 1f, 180L, DecelerateInterpolator(1.6f))
            v.animate().translationX(0f).alpha(1f).setDuration(180L).setInterpolator(DecelerateInterpolator(1.6f))
                .withEndAction { if (token == pageAnimToken) swipeAnimating = false }.start()
            return
        }
        slideCommit(v, toLibrary, token)
    }

    private fun touchesSeekBar(x: Int, y: Int): Boolean {
        if (!::seek.isInitialized || seek.visibility != View.VISIBLE) return false
        val loc = IntArray(2); seek.getLocationOnScreen(loc)
        val pad = dp(14)
        return x >= loc[0] && x <= loc[0] + seek.width && y >= loc[1] - pad && y <= loc[1] + seek.height + pad
    }

    private fun touchesHorizontalScroller(v: View, x: Int, y: Int): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (x < loc[0] || x > loc[0] + v.width || y < loc[1] || y > loc[1] + v.height) return false
        if (v is HorizontalScrollView) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (touchesHorizontalScroller(v.getChildAt(i), x, y)) return true
        return false
    }

    override fun onBackPressed() {
        if (currentPage == "library" && selectedAlbum != null) {
            selectedAlbum = null
            renderLibrary()
            restoreListScroll()
            return
        }
        if (currentPage != "player") {
            showPlayerAnimated()
            return
        }
        // Build 2.8: Back on the Now Playing page only sends the app to the background (like Home).
        // It used to run the "close" action, which stopped the music and removed the task. The real
        // close stays on the "Close player" button of the notification.
        try { moveTaskToBack(true) } catch (_: Exception) { }
    }

    /**
     * Hifi 1.38: the Album list sits in this layout. Its content is clipped at the top edge (the spacer under the header) and fades softly toward
     * the page backdrop behind the mini player, as in 1.20: full strength [fadeFull] px above the bottom edge, gone at [fadeGone] px. Only that
     * band goes through an offscreen layer.
     */
    private inner class EdgeCutLayout(context: Context) : FrameLayout(context) {
        private var fadeOn = false
        private var fadeFull = 0
        private var fadeGone = 0
        private val maskPaint = android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        }

        fun configureFade(enabled: Boolean, full: Int, gone: Int) {
            val g = gone.coerceAtMost(full - dp(8))
            if (fadeOn == enabled && fadeFull == full && fadeGone == g) return
            fadeOn = enabled; fadeFull = full; fadeGone = g
            invalidate()
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val bandTop = h - fadeFull
            if (!fadeOn || bandTop <= 0f || bandTop >= h) { super.dispatchDraw(canvas); return }
            val bandEnd = h - fadeGone
            canvas.save()
            canvas.clipRect(0f, 0f, w, bandTop)
            super.dispatchDraw(canvas)
            canvas.restore()
            val save = canvas.saveLayer(0f, bandTop, w, h, null)
            canvas.clipRect(0f, bandTop, w, h)
            super.dispatchDraw(canvas)
            maskPaint.shader = android.graphics.LinearGradient(
                0f, bandTop, 0f, bandEnd,
                intArrayOf(0x00000000, 0x80000000.toInt(), -0x1000000), floatArrayOf(0f, 0.4f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, bandTop, w, h, maskPaint)
            canvas.restoreToCount(save)
        }
    }

    /** Now Playing panel: keeps the parent scroller from stealing its touches (same behaviour as before, without the unused swipe callbacks). */
    private inner class TouchFrameLayout(context: Context) : FrameLayout(context) {
        init { isClickable = true }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }
    }

    override fun onStop() {
        persistPlaybackState(force = true)
        super.onStop()
    }

    override fun onDestroy() {
        persistPlaybackState(force = true)
        handler.removeCallbacks(ticker)

        // Removing the task can destroy the Activity while the foreground playback session
        // must continue. Keep the live owner, MediaSession and
        // player intact until explicit Close or a task removal while playback is inactive.
        playerListener?.let { listener -> player?.let { p -> try { p.removeListener(listener) } catch (_: Exception) { } } }
        playerListener = null
        val keepPlaybackAlive = player?.let { it.isPlaying || it.playWhenReady } == true
        if (keepPlaybackAlive) {
            try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) { }
            handler.removeCallbacks(wiredDisconnectCheck)
                if (activeInstance === this) activeInstance = null
            mediaSession = null
            recordDiagnostic("ACTIVITY_DESTROYED playback=retained serviceOwnsPlayer=true mediaSession=service-owned")
            super.onDestroy()
            return
        }

        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) { }
        handler.removeCallbacks(wiredDisconnectCheck)
        notificationWidgetExecutor.shutdown()
        // PlaybackKeepAliveService owns the ExoPlayer; Activity teardown never releases it.
        // The service, not this Activity, owns the MediaSession and foreground notification.
        mediaSession = null
        if (activeInstance === this) activeInstance = null
        player = null
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL_ID = "g6_hifi_playback"
        private const val NOTIFICATION_ID = 6106
        private const val KEY_LAST_TRACK_URI = "last_playback_track_uri"
        private const val KEY_LAST_POSITION_MS = "last_playback_position_ms"
        private var activeInstance: MainActivity? = null

        /**
         * Returns true when a Recent Apps dismissal must not tear down an active playback
         * session. A paused/inactive session may be closed with the task.
         */
        @JvmStatic fun handleRecentTaskRemoval(): Boolean {
            val activity = activeInstance ?: return false
            val active = try {
                activity.player?.let { it.isPlaying || it.playWhenReady } == true
            } catch (_: Exception) { false }
            if (active) {
                activity.recordDiagnostic("RECENTS_TASK_REMOVED playback=kept-alive foreground-service=retained")
                return true
            }
            activity.recordDiagnostic("RECENTS_TASK_REMOVED playback=inactive action=close-session")
            activity.stopAndDismissPlayback()
            return false
        }

        @JvmStatic fun notificationAction(action: String) {
            // Compatibility entry point for widget/legacy callers; dispatch directly to service.
            PlaybackKeepAliveService.dispatchAction(activeInstance?.applicationContext, action)
        }

        @JvmStatic fun onServicePlaybackStopped() {
            val activity = activeInstance ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.resetPlaybackUiAfterServiceStop()
                    try { activity.finishAndRemoveTask() } catch (_: Exception) { activity.finish() }
                }
            }
        }
        private const val REQUEST_FOLDER = 1002
        /** Bump when the scan changes what it stores, so an older saved library is scanned again once. */
        private const val LIBRARY_CACHE_VERSION = 147
        /** Hifi 1.44: longest side of the Now Playing cover (1200 before). */
        private const val NOW_PLAYING_COVER_PX = 1440
        private const val DEFAULT_ACCENT = 0xFF6662C6.toInt()
        // Build 2.3: cover as wide as the progress bar; side padding of the bar, space under the controls, smallest gap around the cover.
        private const val SEEK_SIDE_PAD_DP = 20   // Hifi 1.50: 30 -> 20dp: title block and progress bar use the same side gap as the header and the Album content
        private const val DOCK_SIDE_PAD_DP = 30   // transport buttons and info lines keep their old 30dp so they do not move
        private const val HEADER_H_DP = 42
        private const val META_RAISE_DP = 16   // Hifi 1.50: how much higher title / artist / album / logo sit above the progress bar
        private const val STACK_BOTTOM_PAD_DP = 4
        /** Build 2.7: gap between the Now Playing blocks, space above the title, and the grey of the progress track. */
        private const val DOCK_GAP_DP = 14
        private const val DOCK_TOP_PAD_DP = 14
        private const val DARK_PROGRESS_TRACK = 0x2EFFFFFF   // Hifi 2.5: unplayed progress = white at 18% (only the progress bar; other surfaces unchanged)
        private const val PROGRESS_TRACK_GREY = 0xB35E5E66.toInt()
        /** Build 3.01: unplayed progress track for the Light theme only. */
        private const val LIGHT_PROGRESS_TRACK_GREY = 0x2E000000   // Hifi 2.6: mirror of the Dark track (white 18%): black 18%
        /** Hifi 1.43: progress track thickness (2dp before; 4dp = bolder). */
        private const val PROGRESS_THICKNESS_DP = 4
        /** Build 2.10: grey of the "+" / "x" buttons in the lists. Build 2.11: the gap above the progress times and the transport hit areas are back to the Build 2.8 values. */
        private const val ACTION_GREY = 0xFF8E8E98.toInt()
        private const val PROGRESS_TOP_GAP_DP = DOCK_GAP_DP
        private const val TRANSPORT_SIDE_DP = 56   // Hifi 1.52: 52 -> 56 (icon 32 -> 36dp)
        private const val TRANSPORT_PLAY_DP = 66   // Hifi 1.52: 60 -> 66 (icon 38 -> 44dp)
        /** Build 2.13: padding around the play/pause icon inside its 60dp area -> icon 38dp (2.12: 9dp -> 42dp; 2.11: 0 -> 60dp). */
        private const val PLAY_ICON_PAD_DP = 11
        /** Build 2.13: padding around the previous/next icons inside their 52dp area -> icon 32dp (2.12 and 2.11: 0 -> 52dp). */
        private const val SIDE_ICON_PAD_DP = 10
        /** Build 2.12: how far the touch glow reaches beyond the button edge (the transport row has 6dp spare above and below). */
        private const val GLOW_EXTRA_DP = 5
        /** Build 2.15: glow size factor (1.0 = size of 2.14). */
        private const val GLOW_SIZE_SCALE = 0.75f
        /** Build 2.3: size of the cover on the opened-album page (share of the screen width). */
        private const val ALBUM_DETAIL_ART_FRACTION = 0.66f
        private const val THUMB_PX = 512
        private const val PREWARM_ALBUMS = 48
        /** Classic theme page colour (the original black). */
        private const val DARK_SURFACE = 0xFF000000.toInt()
        private const val LIGHT_SURFACE = 0xFFF8F8FC.toInt()
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "ape", "dsf", "dff", "wma", "3gp", "amr")
    }
}