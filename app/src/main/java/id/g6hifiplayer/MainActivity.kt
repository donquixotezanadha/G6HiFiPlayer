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
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
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
import android.widget.FrameLayout
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
    private data class Track(val uri: Uri, val title: String, val artist: String, val album: String, val coverUri: Uri?, val trackNumber: Int = Int.MAX_VALUE)
    private var player: ExoPlayer? = null
    @Volatile private var activeAudioTrack: AudioTrack? = null
    private var audioTrackRoutingListener: AudioRouting.OnRoutingChangedListener? = null
    private var mediaSession: MediaSession? = null
    private var sourceRate: Int? = null
    private var sourceBitrate: Int? = null
    private var durationMs = 0
    private var prepared = false
    private var seeking = false
    private var pendingAutoplay = false
    private var pendingRestorePositionMs: Long? = null
    private var lastPlaybackPersistAtMs = 0L
    private var lastPlaybackRestoreAttempted = false
    private var tracks: List<Track> = emptyList()
    private var currentIndex = -1
    // Queue stores indices into the scanned library; album playback stays inside that album.
    private var playbackQueue: List<Int> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var selectedAlbum: String? = null
    // A dismissed notification stays hidden until the user starts another track.
    private var notificationDismissed = false
    private var artToken = 0
    private var accentColor = DEFAULT_ACCENT
    private var lightTheme = false
    private val BG get() = if (lightTheme) 0xFFF8F8FC.toInt() else 0xFF000000.toInt()
    private val CARD get() = if (lightTheme) 0xFFFFFFFF.toInt() else 0xFF101014.toInt()
    private val ART get() = if (lightTheme) 0xFFE9E9F0.toInt() else 0xFF181820.toInt()
    private val LINE get() = if (lightTheme) 0xFFE1E1EC.toInt() else 0xFF292932.toInt()
    private val WHITE get() = if (lightTheme) 0xFF171720.toInt() else 0xFFF3F3FA.toInt()
    private val MUTED get() = if (lightTheme) 0xFF686875.toInt() else 0xFFA4A4B2.toInt()
    private var albumGridColumns = 2
    private val bitmapArtworkCache = object : android.util.LruCache<String, android.graphics.Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    private var visibleTrackLimit = 80
    private val artworkExecutor = Executors.newFixedThreadPool(2)
    // Coalesce duplicate thumbnail requests when grid cells are rebuilt after album expansion.
    private val artworkTargetsInFlight = mutableMapOf<String, MutableList<java.lang.ref.WeakReference<ImageView>>>()
    // Keep current-player artwork independent of the library thumbnails backlog.
    private val playbackArtworkExecutor = Executors.newSingleThreadExecutor()
    private val metadataExecutor = Executors.newFixedThreadPool(2)
    private val artworkBytesCache = java.util.Collections.synchronizedMap(java.util.LinkedHashMap<String, ByteArray>(16, 0.75f, true))
    private val diagnosticEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var lastDeviceEvent = "No device changes reported yet."
    private var lastActiveRouteKey: String? = null
    private var lastRouteApiError: String? = null
    private var lastBufferDiagnosticKey: String? = null
    private var lastLegacyRouteHints = "Not checked yet"
    private var lastAudioConfigLog = "No audio configuration scan results yet."
    private lateinit var scanProgress: ProgressBar
    private lateinit var scanProgressText: TextView
    private lateinit var albumScanSummary: TextView
    private lateinit var audioInfoInline: TextView
    private lateinit var playerPanel: FrameLayout
    private lateinit var statusBarScrim: View
    private lateinit var libraryPanel: LinearLayout
    private lateinit var songsPanel: LinearLayout
    private lateinit var songRows: LinearLayout
    private lateinit var songSearchInput: EditText
    private var songSearchQuery = ""
    private lateinit var queuePanel: FrameLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var queueRows: LinearLayout
    private lateinit var audioPanel: LinearLayout
    private lateinit var filePanel: LinearLayout
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
    private lateinit var routeSummary: TextView
    private lateinit var routeInfo: TextView
    private lateinit var fileInfo: TextView
    private lateinit var artImage: ImageView
    private lateinit var playerBackdropImage: ImageView
    private lateinit var artPlaceholder: TextView
    private lateinit var librarySummary: TextView
    private lateinit var albumRows: LinearLayout
    private lateinit var trackRows: LinearLayout
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            // Keep frame-time work low on older devices: update playback position only.
            // Audio route inspection is event-driven elsewhere, not polled every half-second.
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
                }
            }
            handler.postDelayed(this, 350)
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val details = addedDevices.joinToString { describe(it) }
            lastDeviceEvent = "Output device connected: " + details
            recordDiagnostic("AUDIO_DEVICE_ADDED devices=" + details)
            activeAudioTrack?.let { preferWiredHeadphoneRoute(it, "output device added") }
            updateActiveRoute(); refreshAudioInfo()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val details = removedDevices.joinToString { describe(it) }
            lastDeviceEvent = "Output device disconnected: " + details
            recordDiagnostic("AUDIO_DEVICE_REMOVED devices=" + details)
            updateActiveRoute(); refreshAudioInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lightTheme = getPreferences(MODE_PRIVATE).getBoolean("light_theme", false)
        albumGridColumns = getPreferences(MODE_PRIVATE).getInt("album_grid_columns", 2).coerceIn(2, 3)
        // Subtle translucent status bar blends with the page while keeping system icons legible.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = if (lightTheme) 0xFFF8F8FC.toInt() else Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or (if (lightTheme) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        activeInstance = this
        getPreferences(MODE_PRIVATE).getString("diagnostic_events", "")?.split("\n\n")?.filter { it.isNotBlank() }?.takeLast(300)?.let { synchronized(diagnosticEvents) { diagnosticEvents.addAll(it) } }
        buildUi(); audioManager.registerAudioDeviceCallback(deviceCallback, null)
        createNotificationChannel()
        createMediaSession()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 303)
        refreshAudioInfo(); handler.post(ticker)
        getPreferences(MODE_PRIVATE).getString("music_tree_uri", null)?.let { savedTree ->
            restoreOrScanLibrary(Uri.parse(savedTree))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
            setBackgroundColor(BG)
            clipChildren = false
            clipToPadding = false
        }
        navPlayer = navButton(android.R.drawable.ic_media_play, "Now playing") { if (::queuePanel.isInitialized && queuePanel.visibility == View.VISIBLE) closeQueueOverlay() else show("player") }
        navLibrary = navButton(android.R.drawable.ic_menu_gallery, "Albums") { show("library") }
        navSongs = navButton(android.R.drawable.ic_menu_agenda, "Songs") { show("songs") }
        navFile = navButton(android.R.drawable.ic_menu_preferences, "Settings") { show("settings") }
        listOf(navPlayer, navLibrary, navSongs, navFile).forEach {
            nav.addView(it, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val scroll = ScrollView(this).apply { clipToPadding = false; isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val statusBarInset = resources.getIdentifier("status_bar_height", "dimen", "android").let { id -> if (id > 0) resources.getDimensionPixelSize(id) else dp(24) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0) }
        playerPanel = SwipeFrameLayout(this, onSwipeDown = { openQueueOverlay() }, onSwipeUp = { closeQueueOverlay() }).apply {
            // Keep Now Playing swipe gestures on the artwork surface without stealing control touches.
            minimumHeight = (resources.displayMetrics.heightPixels - dp(150)).coerceAtLeast(dp(500))
        }
        libraryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        songsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queuePanel = SwipeFrameLayout(this, onSwipeDown = { }, onSwipeUp = { closeQueueOverlay() })
        audioPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        makePlayerPanel(); makeLibraryPanel(); makeSongsPanel(); makeQueuePanel(); makeAudioPanel(); makeFilePanel()
        songsPanel.setPadding(dp(16), statusBarInset + dp(6), dp(16), dp(8))
        settingsPanel.setPadding(0, statusBarInset + dp(6), 0, dp(8))
        settingsPanel.addView(pageHeader("SETTINGS", leftInsetDp = 20), LinearLayout.LayoutParams(-1, dp(44)))
        val themeCard = card()
        themeCard.addView(text("APP THEME", 12f, MUTED, true))
        themeCard.addView(text("Choose an appearance independently of the Android system theme.", 13f, WHITE, false).apply { setPadding(0, dp(6), 0, dp(10)) })
        themeCard.addView(action(if (lightTheme) "USE DARK THEME" else "USE LIGHT THEME", false) {
            lightTheme = !lightTheme
            accentColor = DEFAULT_ACCENT
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = if (lightTheme) 0xFFF8F8FC.toInt() else Color.BLACK
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or (if (lightTheme) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            getPreferences(MODE_PRIVATE).edit().putBoolean("light_theme", lightTheme).apply()
            buildUi(); show("settings"); refreshAudioInfo(); renderLibrary()
            if (currentIndex in tracks.indices) loadArtwork(tracks[currentIndex])
            syncPlaybackIcon()
        }, LinearLayout.LayoutParams(-1, dp(44)))
        settingsPanel.addView(themeCard)
        val scanCard = card()
        scanCard.addView(text("MUSIC LIBRARY SCAN", 12f, MUTED, true))
        scanCard.addView(text("Choose a music folder to scan albums and subfolders. The last folder is checked automatically when the app opens.", 13f, WHITE, false).apply { setPadding(0, dp(6), 0, dp(10)) })
        scanCard.addView(action("CHOOSE MUSIC FOLDER", true) { pickFolder() }, LinearLayout.LayoutParams(-1, dp(46)))
        librarySummary = text("No folder has been scanned yet.", 12f, MUTED, false).apply { setPadding(0, dp(10), 0, 0) }
        scanCard.addView(librarySummary)
        settingsPanel.addView(scanCard)
        val batteryCard = card()
        batteryCard.addView(text("BATTERY OPTIMIZATION", 12f, MUTED, true))
        batteryCard.addView(text("Allow Android to exempt playback from standard battery optimization when supported.", 13f, WHITE, false).apply { setPadding(0, dp(6), 0, dp(10)) })
        batteryCard.addView(action("OPEN BATTERY SETTINGS", false) {
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                } else {
                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }
                startActivity(intent)
            } catch (_: Exception) {
                try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Exception) { Toast.makeText(this, "Battery settings are unavailable on this device.", Toast.LENGTH_LONG).show() }
            }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        settingsPanel.addView(batteryCard)
        settingsPanel.addView(filePanel)
        settingsPanel.addView(audioPanel)
        content.addView(playerPanel); content.addView(libraryPanel); content.addView(songsPanel); content.addView(settingsPanel)
        scroll.addView(content)
        // Queue overlay is a sibling of the page scroll, not nested inside Now Playing.
        // This gives its internal ScrollView independent gesture ownership and stable height.
        val contentStage = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        contentStage.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        contentStage.addView(queuePanel, FrameLayout.LayoutParams(-1, -1))
        // Shared semi-transparent status-bar overlay for Now Playing, Albums, Songs, and Settings.
        contentStage.addView(statusBarScrim, FrameLayout.LayoutParams(-1, statusBarInset, Gravity.TOP))
        root.addView(contentStage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root); show("player"); renderLibrary(); restoreNowPlayingMetadata()
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
        label, 18f, if (lightTheme) Color.BLACK else Color.WHITE, true
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
        // Full-bleed artwork is exclusive to Now Playing; the bottom dock stays fixed.
        playerBackdropImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(accentColor)
            contentDescription = "Album artwork background"
        }
        playerPanel.setBackgroundColor(accentColor)
        playerPanel.addView(playerBackdropImage, FrameLayout.LayoutParams(-1, -1))

        val backdropGradient = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (lightTheme) intArrayOf(0x00000000, 0x22F8F8FC, 0xBDF8F8FC.toInt(), BG)
                else intArrayOf(0x00000000, 0x18000000, 0xA8000000.toInt(), Color.BLACK)
            )
            isClickable = false
        }
        playerPanel.addView(backdropGradient, FrameLayout.LayoutParams(-1, -1))

        artImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        artPlaceholder = text("NO MUSIC PLAYING", 22f, WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(220))
            background = android.graphics.drawable.ColorDrawable(accentColor)
        }
        playerPanel.addView(artPlaceholder, FrameLayout.LayoutParams(-1, -1))

        val foreground = FrameLayout(this).apply {
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val statusBarInset = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
            if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
        }
        // Match every other tab's 44dp header slot and text left edge. The compact
        // rounded background wraps the text, while the slot keeps its vertical alignment stable.
        val playerHeader = pageHeader("NOW PLAYING").apply {
            setPadding(dp(4), dp(4), dp(8), dp(4))
            background = shape(if (lightTheme) Color.WHITE else Color.BLACK, 12)
        }
        val playerHeaderSlot = FrameLayout(this)
        playerHeaderSlot.addView(playerHeader, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))
        foreground.addView(playerHeaderSlot, FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(16)
            topMargin = statusBarInset + dp(6)
        })
        // Draw a controlled translucent scrim behind the Android status icons on Now Playing.
        // Keep the actual system bar transparent so the scrim is visible on Android 9.
        statusBarScrim = View(this).apply {
            setBackgroundColor(if (lightTheme) 0x33000000 else 0x66000000)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Attached to the shared page stage in buildUi so every tab gets the same translucent status bar.

        audioInfoInline = text("FLAC • source — kHz → output — kHz\nBIT-PERFECT NOT CONFIRMED", 9f, MUTED, false).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 2
            setPadding(0, dp(2), 0, dp(4))
            visibility = View.VISIBLE
            setTextIsSelectable(true)
        }
        titleView = text("", 20f, WHITE, true).apply {
            setPadding(0, 0, 0, dp(2))
            maxLines = 2; isSingleLine = false
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        artistView = text("", 15f, MUTED, false)
        albumView = text("", 13f, MUTED, false).apply {
            setPadding(0, dp(2), 0, 0); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val metadata = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        metadata.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        metadata.addView(artistView, LinearLayout.LayoutParams(-1, -2))
        metadata.addView(albumView, LinearLayout.LayoutParams(-1, -2))

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), 0, dp(18), 0)
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
            // A custom rounded drawable makes the played thickness visibly 5dp on Android 9.
            progressDrawable = RoundedProgressDrawable(
                if (lightTheme) 0xFFD8D5F0.toInt() else 0xFF343148.toInt(),
                accentColor,
                dp(4)
            )
            thumbTintList = ColorStateList.valueOf(accentColor)
            minimumHeight = dp(28)
            setPadding(dp(18), 0, dp(18), 0)
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
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        transport.addView(iconAction("⏮") { playPrevious() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        playView = ImageView(this).apply {
            contentDescription = "Play or pause"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = RippleDrawable(
                ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x55000000),
                shape(if (lightTheme) 0xFFE0DFFF.toInt() else 0xFF29265A.toInt(), 16), null
            )
            imageTintList = ColorStateList.valueOf(accentColor)
            isClickable = true; isFocusable = true
            setOnClickListener { togglePlayback() }
        }
        syncPlaybackIcon()
        // Slightly lengthen the rounded rectangle versus Build 44 without returning to the oversized original.
        transport.addView(playView, LinearLayout.LayoutParams(dp(76), dp(56)))
        transport.addView(iconAction("⏭") { playNext() }, LinearLayout.LayoutParams(0, dp(48), 1f))

        val bottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (lightTheme) intArrayOf(0x00F8F8FC, 0xE6F8F8FC.toInt(), 0xFFF8F8FC.toInt())
                else intArrayOf(0x00000000, 0xD9000000.toInt(), Color.BLACK)
            )
        }
        // Track details and transport controls stay together; diagnostics remain below the controls.
        bottomDock.addView(metadata, LinearLayout.LayoutParams(-1, -2))
        bottomDock.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        bottomDock.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 0; bottomMargin = dp(1) })
        bottomDock.addView(transport, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        bottomDock.addView(audioInfoInline, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(18); rightMargin = dp(18); topMargin = dp(4); bottomMargin = dp(2)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        foreground.addView(bottomDock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        playerPanel.addView(foreground, FrameLayout.LayoutParams(-1, -1))
        statusView = text("Choose a music folder or audio file to begin.", 14f, WHITE, false)
        // The no-track state is represented by the accent-colored artwork area, not the title field.
    }

    private fun makeLibraryPanel() {
        val inset = resources.getIdentifier("status_bar_height", "dimen", "android").let { id -> if (id > 0) resources.getDimensionPixelSize(id) else dp(24) }
        // Match the inner horizontal content edge used by the Queue card (16dp).
        libraryPanel.setPadding(dp(16), inset + dp(6), dp(16), dp(8))
        libraryPanel.addView(pageHeader("ALBUM"), LinearLayout.LayoutParams(-1, dp(44)))
        val gridControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        }
        gridControls.addView(action("2 COLUMNS", albumGridColumns == 2) {
            albumGridColumns = 2
            getPreferences(MODE_PRIVATE).edit().putInt("album_grid_columns", albumGridColumns).apply()
            buildUi(); show("library"); renderLibrary()
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        gridControls.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        gridControls.addView(action("3 COLUMNS", albumGridColumns == 3) {
            albumGridColumns = 3
            getPreferences(MODE_PRIVATE).edit().putInt("album_grid_columns", albumGridColumns).apply()
            buildUi(); show("library"); renderLibrary()
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        libraryPanel.addView(gridControls, LinearLayout.LayoutParams(-1, -2))
        albumScanSummary = text("No folder has been scanned yet.", 12f, MUTED, false).apply {
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        libraryPanel.addView(albumScanSummary, LinearLayout.LayoutParams(-1, -2))
        scanProgressText = text("", 12f, accentColor, false).apply {
            setPadding(dp(4), dp(4), dp(4), dp(3)); visibility = View.GONE
        }
        libraryPanel.addView(scanProgressText, LinearLayout.LayoutParams(-1, -2))
        scanProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
            progressTintList = ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = ColorStateList.valueOf(if (lightTheme) 0xFFE3E1F5.toInt() else 0xFF242238.toInt())
        }
        libraryPanel.addView(scanProgress, LinearLayout.LayoutParams(-1, dp(5)).apply { bottomMargin = dp(6) })
        albumRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, 0) }
        libraryPanel.addView(albumRows, LinearLayout.LayoutParams(-1, -2))
    }

    private fun makeSongsPanel() {
        val heading = flatSection().apply { setPadding(0, 0, 0, dp(12)) }
        heading.addView(pageHeader("SONGS"), LinearLayout.LayoutParams(-1, dp(44)))
        songSearchInput = EditText(this).apply {
            hint = "Search songs, artists, albums"
            textSize = 14f
            setTextColor(WHITE)
            setHintTextColor(MUTED)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = shape(LINE, 14)
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

    private fun makeQueuePanel() {
        queuePanel.setBackgroundColor(BG)
        queuePanel.visibility = View.GONE
        val surface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.ColorDrawable(BG)
        }
        val statusInset = resources.getIdentifier("status_bar_height", "dimen", "android").let { id ->
            if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
        }
        surface.setPadding(dp(16), statusInset + dp(6), dp(16), dp(8))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(pageHeader("UP NEXT"), LinearLayout.LayoutParams(0, dp(44), 1f))
        surface.addView(header)

        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = RippleDrawable(
                ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000),
                shape(CARD, 14), null
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { closeQueueOverlay() }
            // Swipe up on the mini-player dock to return to Now Playing.
            var downX = 0f
            var downY = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        if (dy < -dp(32) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.2f) {
                            closeQueueOverlay()
                            true
                        } else false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        false
                    }
                    else -> false
                }
            }
        }
        queueMiniArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(ART, 8)
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
            progressTintList = ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = ColorStateList.valueOf(if (lightTheme) 0xFFD8D5F0.toInt() else 0xFF343148.toInt())
        }
        miniInfo.addView(queueMiniTitle)
        miniInfo.addView(queueMiniArtist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        miniInfo.addView(queueMiniProgress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(7) })
        mini.addView(miniInfo, LinearLayout.LayoutParams(0, -2, 1f))
        queueMiniPlayView = ImageView(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(accentColor)
            setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
            contentDescription = if (player?.isPlaying == true) "Pause" else "Play"
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x55000000),
                shape(Color.TRANSPARENT, 24), null
            )
            setOnClickListener { togglePlayback() }
        }
        mini.addView(queueMiniPlayView, LinearLayout.LayoutParams(dp(44), dp(48)))
        val queueActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queueActions.addView(text("PLAYBACK QUEUE", 12f, MUTED, true), LinearLayout.LayoutParams(0, dp(38), 1f))
        queueActions.addView(action("CLEAR", false) {
            playbackQueue = emptyList()
            playbackOrder = if (currentIndex in tracks.indices) listOf(currentIndex) else emptyList()
            renderQueueRows()
            Toast.makeText(this, "Queue cleared.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(82), dp(36)))
        surface.addView(queueActions)

        queueRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val queueScroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            addView(queueRows, ViewGroup.LayoutParams(-1, -2))
        }
        // The list owns the flexible middle area; the mini-player stays docked directly
        // above the app's bottom navigation instead of scrolling away with queue rows.
        surface.addView(queueScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        surface.addView(mini, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        queuePanel.addView(surface, FrameLayout.LayoutParams(-1, -1))
        updateQueueMiniPlayer()
    }

    private fun openQueueOverlay() {
        if (!::queuePanel.isInitialized || !::playerPanel.isInitialized || playerPanel.visibility != View.VISIBLE) return
        if (queuePanel.visibility == View.VISIBLE) return
        queuePanel.animate().cancel()
        renderQueueRows()
        val travel = (queuePanel.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(64))).toFloat()
            .coerceAtLeast(dp(160).toFloat())
        queuePanel.translationY = -travel
        queuePanel.alpha = 1f
        queuePanel.visibility = View.VISIBLE
        queuePanel.bringToFront()
        queuePanel.postOnAnimation {
            if (queuePanel.visibility != View.VISIBLE) return@postOnAnimation
            queuePanel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            queuePanel.animate().cancel()
            queuePanel.animate().translationY(0f).setDuration(300L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction { queuePanel.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
        }
        recordDiagnostic("QUEUE_OVERLAY opened=true animation=300ms-cubic")
    }

    private fun closeQueueOverlay() {
        if (!::queuePanel.isInitialized || queuePanel.visibility != View.VISIBLE) return
        queuePanel.animate().cancel()
        val travel = (queuePanel.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(64))).toFloat()
            .coerceAtLeast(dp(160).toFloat())
        queuePanel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        queuePanel.animate().translationY(-travel).setDuration(240L)
            .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                queuePanel.visibility = View.GONE
                queuePanel.translationY = 0f
                queuePanel.alpha = 1f
                queuePanel.setLayerType(View.LAYER_TYPE_NONE, null)
            }.start()
        recordDiagnostic("QUEUE_OVERLAY opened=false animation=240ms-cubic")
    }

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

    private fun makeAudioPanel() {
        val summary = card(); summary.addView(text("ACTIVE OUTPUT", 11f, accentColor, true))
        routeSummary = text("Memeriksa…", 22f, WHITE, true).apply { setPadding(0, dp(8), 0, dp(5)) }
        summary.addView(routeSummary); summary.addView(text("The stream route is read from Media3 AudioTrack. The internal LG Quad DAC backend is not exposed through the public API; detecting headphones does not prove a specific DAC backend.", 12f, MUTED, false)); audioPanel.addView(summary)
        val detail = card(); detail.addView(text("ROUTE DIAGNOSTICS", 11f, MUTED, true))
        routeInfo = text("", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        detail.addView(routeInfo)
        detail.addView(action("PERBARUI INFORMASI", false) { refreshAudioInfo() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
        val logActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        logActions.addView(action("COPY LOG", false) { copyAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        logActions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        logActions.addView(action("SHARE LOG", false) { shareAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        detail.addView(logActions)
        detail.addView(action("COLLECT & SHARE DIAGNOSTICS", true) { collectAndShareDiagnostics() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        audioPanel.addView(detail)
        val lg = card(); lg.addView(text("LG AUDIO INSPECTION", 11f, accentColor, true))
        lg.addView(text("Searching readable LG configuration files and testing direct-playback support. Results do not prove the route used by the current track.", 12f, MUTED, false).apply { setPadding(0, dp(7), 0, dp(8)) })
        lg.addView(action("SCAN LG CONFIGURATION", false) { scanAudioConfiguration() }, LinearLayout.LayoutParams(-1, dp(44))); audioPanel.addView(lg)
        val note = card(); note.addView(text("MEASUREMENT LIMITATIONS", 11f, accentColor, true))
        note.addView(text("The public Android API cannot conclusively report the active stream bit depth, actual DAC sample rate, or bit-perfect output. A source rate differing from the default does not by itself prove resampling.", 13f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) })
        audioPanel.addView(note)
    }

    private fun makeFilePanel() {
        val info = card(); info.addView(text("FILE INFORMATION", 11f, accentColor, true))
        fileInfo = text("No file selected.", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        info.addView(fileInfo)
        info.addView(action("CHOOSE AUDIO FILE", false) { pickFile() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) })
        filePanel.addView(info)
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_AUDIO)
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
        if (requestCode == REQUEST_AUDIO) {
            try {
                val flag = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (flag != 0) contentResolver.takePersistableUriPermission(uri, flag)
            } catch (_: Exception) { }
            tracks = listOf(readTrack(uri, null, null)); currentIndex = 0; selectedAlbum = null; playbackQueue = emptyList(); playbackOrder = listOf(0); playTrackAt(0, false, listOf(0))
        }
    }

    private fun appVersionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = info.longVersionCode
        "${info.versionName ?: "unknown"} ($code)"
    } catch (_: Exception) { "unknown" }

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
                    if (root.optString("treeUri") == treeUri.toString()) {
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
                                item.optInt("trackNumber", Int.MAX_VALUE)
                            ))
                        }
                        if (list.isNotEmpty()) cached = list
                    }
                }
            } catch (e: Exception) {
                recordDiagnostic("LIBRARY_CACHE_READ_FAILED", e)
            }
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
                } else {
                    scanMusicFolder(treeUri)
                }
            }
        }, "g6-library-cache").start()
    }

    private fun restoreLastPlayback(library: List<Track>) {
        if (lastPlaybackRestoreAttempted || library.isEmpty()) return
        val prefs = getPreferences(MODE_PRIVATE)
        val savedUri = prefs.getString(KEY_LAST_TRACK_URI, null)?.takeIf { it.isNotBlank() } ?: return
        val index = library.indexOfFirst { it.uri.toString() == savedUri }
        if (index < 0) {
            recordDiagnostic("LAST_PLAYBACK_RESTORE_TRACK_NOT_FOUND uri=$savedUri tracks=${library.size}")
            return
        }
        lastPlaybackRestoreAttempted = true
        pendingRestorePositionMs = prefs.getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
        recordDiagnostic("LAST_PLAYBACK_RESTORE_START index=$index positionMs=${pendingRestorePositionMs}")
        playTrackAt(index, autoplay = false)
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
                    .put("trackNumber", track.trackNumber))
            }
            val root = org.json.JSONObject().put("treeUri", treeUri.toString()).put("tracks", array)
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
            handler.post {
                tracks = sorted; currentIndex = -1; selectedAlbum = null; playbackQueue = emptyList(); playbackOrder = emptyList()
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

    private fun scanDirectory(dir: DocumentFile, output: MutableList<Track>, covers: MutableMap<String, Uri?>, depth: Int, count: IntArray) {
        if (depth > 12) return
        val children = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
        for (file in children) try {
            if (file.isDirectory) scanDirectory(file, output, covers, depth + 1, count)
            else if (isAudioFile(file)) {
                val key = dir.uri.toString()
                if (!covers.containsKey(key)) covers[key] = findCover(dir)
                synchronized(output) { output.add(readTrack(file.uri, covers[key], dir.name)) }
                count[0]++
                if (count[0] == 1 || count[0] % 50 == 0) {
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
                                tracks = partial.sortedWith(compareBy<Track> { it.album.lowercase(Locale.ROOT) }.thenBy { it.trackNumber }.thenBy { it.title.lowercase(Locale.ROOT) })
                                currentIndex = previousUri?.let { uri -> tracks.indexOfFirst { it.uri == uri } } ?: -1
                                playbackQueue = emptyList()
                                playbackOrder = if (currentIndex in tracks.indices) listOf(currentIndex) else emptyList()
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
        val images = files.filter {
            val n = it.name?.lowercase(Locale.ROOT) ?: ""
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
        }
        for (prefix in listOf("cover", "folder", "front", "album", "artwork", "albumart")) {
            images.firstOrNull { it.name?.substringBeforeLast('.')?.lowercase(Locale.ROOT) == prefix }?.let { return it.uri }
        }
        return images.firstOrNull()?.uri
    }
    private fun readTrack(uri: Uri, coverUri: Uri?, folderName: String?): Track {
        val r = MediaMetadataRetriever()
        var title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Local audio"
        var artist = "Artist unavailable"; var album = folderName ?: "Unknown album"; var trackNumber = Int.MAX_VALUE
        try {
            r.setDataSource(this, uri)
            fun meta(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
            artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
            trackNumber = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore("/")?.toIntOrNull() ?: Int.MAX_VALUE
        } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) { } }
        return Track(uri, title, artist, album, coverUri, trackNumber)
    }

    private fun renderLibrary() {
        if (!::albumRows.isInitialized) return
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
                val detailHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(10))
                }
                detailHeader.addView(action("‹ ALBUMS", false) {
                    selectedAlbum = null
                    renderLibrary()
                }, LinearLayout.LayoutParams(dp(112), dp(42)))
                detailHeader.addView(text(album, 18f, WHITE, true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                albumRows.addView(detailHeader, LinearLayout.LayoutParams(-1, -2))
                albumRows.addView(text(entries.size.toString() + " tracks", 12f, MUTED, false).apply {
                    setPadding(dp(4), 0, 0, dp(10))
                })
                val albumActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(4), dp(2), dp(4), dp(8))
                }
                albumActions.addView(action("PLAY ALBUM", true) {
                    val order = entries.map { it.index }
                    if (order.isNotEmpty()) { playTrackAt(order.first(), true, order); show("player") }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                albumActions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
                albumActions.addView(action("ADD TO QUEUE", false) {
                    addAlbumToQueue(entries.map { it.index }, album)
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                albumRows.addView(albumActions, LinearLayout.LayoutParams(-1, -2))
                entries.forEach { entry -> addLibraryTrackRow(albumRows, entry, true, entries.map { it.index }) }
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
                background = RippleDrawable(
                    ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x25000000),
                    shape(Color.TRANSPARENT, 12), null
                )
                isClickable = true
                isFocusable = true
                contentDescription = "Open songs in album $album"
                setOnClickListener {
                    selectedAlbum = album
                    renderLibrary()
                    show("library")
                }
                setOnLongClickListener {
                    showAlbumOptions(album, indices)
                    true
                }
            }
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = shape(Color.TRANSPARENT, 8)
                contentDescription = "Artwork $album"
            }
            cell.addView(cover, LinearLayout.LayoutParams(-1, dp(if (albumGridColumns == 2) 148 else 108)))
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
            background = shape(if (entry.index == currentIndex) selectedHighlight() else BG, 12)
            isClickable = true
            isFocusable = true
        }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(ART, 8) }
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
        val addQueue = iconAction("+") { addToQueue(entry.index) }.apply { textSize = 22f; setTextColor(accentColor); contentDescription = "Add ${t.title} to queue" }
        row.addView(addQueue, LinearLayout.LayoutParams(dp(40), dp(44)))
        row.setOnClickListener {
            val albumOrder = tracks.withIndex().filter { it.value.album == t.album }.map { it.index }
            playTrackAt(entry.index, true, albumOrder.ifEmpty { queueIndices })
            show("player")
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        loadArtworkInto(cover, t)
    }
    private fun rowButton(label: String, selected: Boolean, click: () -> Unit) = text(label, 13f, if (selected) accentColor else WHITE, selected).apply {
        setPadding(dp(12), dp(11), dp(12), dp(11)); background = shape(if (selected) LINE else BG, 12)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) }
    }

    @OptIn(UnstableApi::class)
    private fun createHiFiPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                val baseProvider = AudioTrackAudioOutputProvider.Builder(context).build()
                val trackingProvider = TrackingAudioOutputProvider(baseProvider) { track ->
                    attachActiveAudioTrack(track)
                }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioOutputProvider(trackingProvider)
                    .build()
            }
        }
        // Disable float PCM: the LG G6 direct_pcm policy lists integer PCM formats, not PCM_FLOAT.
        // This improves format compatibility but does NOT force AUDIO_OUTPUT_FLAG_DIRECT or prove Quad DAC use.
        renderersFactory.setEnableAudioFloatOutput(false)
        return ExoPlayer.Builder(this, renderersFactory).build()
    }

    private fun attachActiveAudioTrack(track: AudioTrack) {
        val previous = activeAudioTrack
        val previousListener = audioTrackRoutingListener
        if (previous != null && previousListener != null) {
            try { previous.removeOnRoutingChangedListener(previousListener) } catch (_: Exception) { }
        }
        activeAudioTrack = track
        // Android may not report routedDevice on this LG Android 9 HAL. Ask policy to
        // prefer the connected 3.5 mm device, but this is not proof of DIRECT/DAC use.
        preferWiredHeadphoneRoute(track, "AudioTrack dibuat")
        val listener = object : AudioRouting.OnRoutingChangedListener {
            override fun onRoutingChanged(router: AudioRouting) {
                val routed = try { router.routedDevice } catch (_: Exception) { null }
                val label = routed?.let { describe(it) } ?: "route not reported by AudioTrack"
                lastDeviceEvent = "AudioTrack route changed: " + label
                recordDiagnostic("AUDIOTRACK_ROUTING_CHANGED route=" + label)
                handler.post { updateActiveRoute(); refreshAudioInfo() }
            }
        }
        audioTrackRoutingListener = listener
        try { track.addOnRoutingChangedListener(listener, handler) }
        catch (e: Exception) { recordDiagnostic("AUDIOTRACK_ROUTING_LISTENER_FAILED", e) }
        recordDiagnostic(
            "AUDIOTRACK_CREATED rate=${try { track.sampleRate } catch (_: Exception) { -1 }} " +
                "encoding=${try { encodingName(track.audioFormat) } catch (_: Exception) { "unknown" }} " +
                "channels=${try { track.channelCount } catch (_: Exception) { -1 }} " +
                "bufferFrames=${try { track.bufferSizeInFrames } catch (_: Exception) { -1 }} " +
                "session=${try { track.audioSessionId } catch (_: Exception) { -1 }}"
        )
        handler.post { updateActiveRoute(); refreshAudioInfo() }
    }

    private fun preferWiredHeadphoneRoute(track: AudioTrack, reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val outputs = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Exception) { emptyArray<AudioDeviceInfo>() }
        val wired = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        if (wired == null) {
            recordDiagnostic("WIRED_ROUTE_PREFERENCE skipped reason=$reason connectedWiredDevice=false")
            return
        }
        val accepted = try { track.setPreferredDevice(wired) } catch (e: Exception) {
            recordDiagnostic("WIRED_ROUTE_PREFERENCE failed device=${describe(wired)} reason=$reason", e)
            false
        }
        recordDiagnostic(
            "WIRED_ROUTE_PREFERENCE requested=${describe(wired)} accepted=$accepted reason=$reason " +
                "note=policy-hint-only-not-proof-of-DIRECT-or-Quad-DAC"
        )
        handler.post { updateActiveRoute(); refreshAudioInfo() }
    }

    private fun clearActiveAudioTrack() {
        val track = activeAudioTrack
        val listener = audioTrackRoutingListener
        if (track != null && listener != null) {
            try { track.removeOnRoutingChangedListener(listener) } catch (_: Exception) { }
        }
        audioTrackRoutingListener = null
        activeAudioTrack = null
    }

    private fun playTrackAt(index: Int, autoplay: Boolean, queue: List<Int>? = null) {
        if (index !in tracks.indices) return
        // Starting any song from an album places the complete album in the visible queue.
        val requestedOrder = queue?.filter { it in tracks.indices }?.distinct().orEmpty()
        if (requestedOrder.isNotEmpty() && index in requestedOrder) {
            playbackOrder = requestedOrder
            playbackQueue = requestedOrder
        } else if (playbackOrder.isEmpty() || index !in playbackOrder) {
            val albumOrder = tracks.withIndex().filter { it.value.album == tracks[index].album }.map { it.index }
            playbackOrder = albumOrder.ifEmpty { listOf(index) }
            playbackQueue = playbackOrder
        }
        persistPlaybackState(force = true)
        currentIndex = index
        notificationDismissed = false
        if (::queueRows.isInitialized) renderQueueRows()
        val t = tracks[index]
        pendingAutoplay = autoplay
        prepared = false
        sourceRate = null
        sourceBitrate = null
        durationMs = 0
        clearActiveAudioTrack()
        try { player?.release() } catch (_: Exception) { }
        player = null
        lastActiveRouteKey = null
        titleView.text = t.title; titleView.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT); titleView.gravity = Gravity.START or Gravity.CENTER_VERTICAL; titleView.setPadding(0, dp(2), 0, dp(1)); artistView.text = t.artist; albumView.text = t.album
        elapsed.text = "0:00"; duration.text = "0:00"; seek.progress = 0; seek.max = 1
        playView.isEnabled = false; syncPlaybackIcon(false)
        statusView.text = "Loading with the Media3 engine: " + t.title + "…"
        showMetadata(t.uri, t); loadArtwork(t)
        try {
            val exo = createHiFiPlayer()
            player = exo
            exo.setHandleAudioBecomingNoisy(true)
            var ffmpegFallbackAttempted = false
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            prepared = true
                            durationMs = exo.duration.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt() ?: 0
                            seek.max = durationMs.coerceAtLeast(1); this@MainActivity.duration.text = time(durationMs)
                            pendingRestorePositionMs?.let { savedPosition ->
                                val boundedPosition = if (durationMs > 0) savedPosition.coerceAtMost(durationMs.toLong()) else savedPosition
                                try { exo.seekTo(boundedPosition) } catch (e: Exception) { recordDiagnostic("LAST_PLAYBACK_SEEK_RESTORE_FAILED positionMs=$boundedPosition", e) }
                                pendingRestorePositionMs = null
                                recordDiagnostic("LAST_PLAYBACK_RESTORE_POSITION_APPLIED positionMs=$boundedPosition durationMs=$durationMs")
                            }
                            playView.isEnabled = true
                            statusView.text = "Ready • Media3/ExoPlayer • " + (sourceRate?.let { "%.1f kHz".format(Locale.US, it / 1000f) } ?: "source sample rate unknown")
                            if (pendingAutoplay) { exo.play(); syncPlaybackIcon(true); statusView.text = "Playing • " + t.title }
                            else syncPlaybackIcon(false)
                            pendingAutoplay = false
                            mediaSession?.isActive = true
                            refreshAudioInfo(); updatePlaybackNotification(exo.isPlaying)
                        }
                        Player.STATE_ENDED -> {
                            syncPlaybackIcon(false)
                            val q = playbackOrder.indexOf(currentIndex)
                            if (q >= 0 && q + 1 < playbackOrder.size) playTrackAt(playbackOrder[q + 1], true)
                            else { statusView.text = "Album/queue finished."; refreshAudioInfo(); updatePlaybackNotification(false) }
                        }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (prepared) {
                        // A genuine transition back to playback must restore a notification dismissed earlier.
                        if (isPlaying) {
                            notificationDismissed = false
                            // Re-apply the wired-device preference after playback actually starts.
                            // AudioTrack can be created before AudioPolicy finishes establishing its route;
                            // this remains a routing hint, not a request for DIRECT output or proof of DAC use.
                            activeAudioTrack?.let { track ->
                                preferWiredHeadphoneRoute(track, "playback started")
                            }
                        }
                        syncPlaybackIcon(isPlaying)
                        refreshAudioInfo()
                        updatePlaybackNotification(isPlaying)
                        updateActiveRoute()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    prepared = false
                    if (!ffmpegFallbackAttempted) {
                        ffmpegFallbackAttempted = true
                        val shouldResumePlayback = pendingAutoplay || exo.playWhenReady || exo.isPlaying
                        playView.isEnabled = false
                        statusView.text = "Native decoder failed; trying FFmpeg fallback…"
                        recordDiagnostic("MEDIA3_PLAYBACK_ERROR_FFMPEG_FALLBACK_START uri=${t.uri} code=${error.errorCodeName}", error)
                        FfmpegAudioDecoder(this@MainActivity).decodeToWav(t.uri) { result ->
                            handler.post {
                                if (player !== exo || isFinishing) {
                                    result.getOrNull()?.delete()
                                    return@post
                                }
                                result.onSuccess { wav ->
                                    try {
                                        exo.stop()
                                        exo.clearMediaItems()
                                        pendingAutoplay = shouldResumePlayback
                                        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
                                        exo.prepare()
                                        statusView.text = "FFmpeg PCM fallback • preparing " + t.title
                                        recordDiagnostic("FFMPEG_FALLBACK_READY file=${wav.name} note=decoded-to-24bit-PCM-WAV-no-explicit-resampling")
                                    } catch (e: Exception) {
                                        playView.isEnabled = true
                                        statusView.text = "FFmpeg fallback could not start: " + (e.message ?: e.javaClass.simpleName)
                                        recordDiagnostic("FFMPEG_FALLBACK_PLAYER_FAILED", e)
                                    }
                                }.onFailure { e ->
                                    playView.isEnabled = true
                                    statusView.text = "Audio decode failed (Media3 + FFmpeg): " + (e.message ?: e.javaClass.simpleName)
                                    recordDiagnostic("FFMPEG_FALLBACK_FAILED", e)
                                }
                            }
                        }
                    } else {
                        playView.isEnabled = true
                        statusView.text = "Media3/FFmpeg playback failed • " + error.errorCodeName + ": " + (error.message ?: "unsupported format")
                        recordDiagnostic("MEDIA3_PLAYBACK_ERROR_AFTER_FFMPEG code=${error.errorCodeName}", error)
                    }
                    refreshAudioInfo()
                }
            })
            exo.setMediaItem(MediaItem.fromUri(t.uri)); exo.prepare()
        } catch (e: Exception) {
            playView.isEnabled = true; statusView.text = "Unable to open file: " + e.message
            recordDiagnostic("MEDIA3_INITIALIZATION_FAILED", e)
        }
        // Avoid rebuilding the entire album grid for every playback state change.
    }
    private fun playPrevious() {
        if (tracks.isEmpty()) { statusView.text = "Choose a folder or audio file first."; return }
        if (playbackOrder.isEmpty()) playbackOrder = tracks.indices.toList()
        val position = playbackOrder.indexOf(currentIndex).let { if (it < 0) 0 else it }
        val target = playbackOrder[(position - 1 + playbackOrder.size) % playbackOrder.size]
        playTrackAt(target, true)
    }
    private fun playNext() {
        if (tracks.isEmpty()) { statusView.text = "Choose a folder or audio file first."; return }
        if (playbackOrder.isEmpty()) playbackOrder = tracks.indices.toList()
        val position = playbackOrder.indexOf(currentIndex).let { if (it < 0) -1 else it }
        val target = playbackOrder[(position + 1).mod(playbackOrder.size)]
        playTrackAt(target, true)
    }
    private fun syncPlaybackIcon(isPlaying: Boolean? = null) {
        if (!::playView.isInitialized) return
        val playing = isPlaying ?: try { player?.isPlaying == true } catch (_: Exception) { false }
        playView.setImageResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        playView.contentDescription = if (playing) "Pause" else "Play"
        playView.imageTintList = ColorStateList.valueOf(accentColor)
        if (::queueMiniPlayView.isInitialized) {
            queueMiniPlayView.setImageResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
            queueMiniPlayView.contentDescription = if (playing) "Pause" else "Play"
            queueMiniPlayView.imageTintList = ColorStateList.valueOf(accentColor)
        }
    }

    private fun togglePlayback() {
        val p = player
        if (p == null || !prepared) { statusView.text = "Choose a track from the library first."; return }
        try {
            if (p.isPlaying) {
                p.pause()
                syncPlaybackIcon(false)
                statusView.text = "Dijeda"
            } else {
                // Starting playback again must re-enable the notification after the user dismissed it.
                notificationDismissed = false
                p.play()
                syncPlaybackIcon(true)
                statusView.text = "Playing"
            }
            // Keep transport controls snappy: route/device diagnostics can be comparatively expensive.
            // Playback listener updates the notification immediately; refresh diagnostics on the next UI turn.
            handler.post { if (!isFinishing) refreshAudioInfo() }
            updatePlaybackNotification(try { p.isPlaying } catch (_: Exception) { false })
        } catch (e: Exception) { statusView.text = "Playback failed: " + e.message }
    }

    private fun showMetadata(uri: Uri, t: Track) {
        // SAF/MediaMetadataRetriever/MediaExtractor may block on slow storage. Never run them on the UI thread,
        // otherwise the artwork bitmap cannot be displayed even when its background task has already finished.
        metadataExecutor.execute {
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
            val infoText = "Title: " + t.title + "\nArtist: " + t.artist + "\nAlbum: " + t.album + "\nDuration: " + fileDuration + " ms\nCodec/MIME: " + trackMime + "\nSource sample rate: " + rate + " Hz\nChannels: " + channels + "\nBitrate: " + bitrate + " bit/s (if reported)\nURI: " + uri
            handler.post {
                if (isFinishing || tracks.getOrNull(currentIndex)?.uri != uri) return@post
                sourceRate = parsedRate
                sourceBitrate = parsedBitrate
                fileInfo.text = infoText
                refreshAudioInfo()
            }
        }
    }

    private fun vividArtworkColor(bitmap: android.graphics.Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return DEFAULT_ACCENT
        var bestColor = DEFAULT_ACCENT
        var bestScore = 0f
        val stepX = (w / 40).coerceAtLeast(1)
        val stepY = (h / 40).coerceAtLeast(1)
        val hsv = FloatArray(3)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 180) {
                    Color.colorToHSV(pixel, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    if (saturation >= 0.38f && value >= 0.22f && value <= 0.88f) {
                        // Prefer clearly saturated colors while avoiding near-black and near-white pixels.
                        val brightnessScore = 1f - kotlin.math.abs(value - 0.58f)
                        val score = saturation * brightnessScore
                        if (score > bestScore) {
                            bestScore = score
                            bestColor = Color.HSVToColor(floatArrayOf(hsv[0], saturation.coerceAtMost(0.9f), value.coerceIn(0.58f, 0.86f)))
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }
        return if (bestScore >= 0.38f) bestColor else DEFAULT_ACCENT
    }

    private fun applyArtworkAccent(bitmap: android.graphics.Bitmap) {
        val picked = DEFAULT_ACCENT
        if (picked == accentColor) return
        accentColor = picked
        if (::seek.isInitialized) {
            seek.progressDrawable = RoundedProgressDrawable(
                if (lightTheme) 0xFFD8D5F0.toInt() else 0xFF343148.toInt(),
                accentColor,
                dp(5)
            )
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            seek.invalidate()
        }
        if (::playView.isInitialized) {
            playView.imageTintList = ColorStateList.valueOf(accentColor)
            playView.background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x55000000), shape(if (lightTheme) 0xFFE0DFFF.toInt() else 0xFF29265A.toInt(), 16), null)
            syncPlaybackIcon()
        }
        if (::scanProgress.isInitialized) {
            scanProgress.progressTintList = ColorStateList.valueOf(DEFAULT_ACCENT)
            scanProgress.progressBackgroundTintList = ColorStateList.valueOf(if (lightTheme) 0xFFD8D5F0.toInt() else 0xFF343148.toInt())
        }
        if (::scanProgressText.isInitialized) scanProgressText.setTextColor(accentColor)
        if (::titleView.isInitialized) titleView.setTextColor(accentColor)
        if (::audioInfoInline.isInitialized) audioInfoInline.setTextColor(MUTED)
        if (::statusView.isInitialized) statusView.setTextColor(accentColor)
        fun recolor(view: View) {
            if (view is TextView && view.tag == "g6_dynamic_accent_text") view.setTextColor(accentColor)
            if (view is ViewGroup) for (i in 0 until view.childCount) recolor(view.getChildAt(i))
        }
        recolor(window.decorView)
        if (::albumRows.isInitialized) renderLibrary()
        if (::songRows.isInitialized) renderSongs()
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

    private fun readArtworkBytes(track: Track): ByteArray? {
        val key = track.uri.toString()
        synchronized(artworkBytesCache) { artworkBytesCache[key]?.let { return it } }
        var bytes: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(this@MainActivity, track.uri); bytes = retriever.embeddedPicture }
        catch (_: Exception) { }
        finally { try { retriever.release() } catch (_: Exception) { } }
        if (bytes == null && track.coverUri != null) {
            try { contentResolver.openInputStream(track.coverUri)?.use { bytes = it.readBytes() } } catch (_: Exception) { }
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
            val bitmap = bitmapArtworkCache.get(key) ?: readArtworkBytes(track)
                ?.let { decodeArtwork(it, 320, android.graphics.Bitmap.Config.RGB_565) }
                ?.also { bitmapArtworkCache.put(key, it) }
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

    private fun loadArtwork(t: Track) {
        val token = ++artToken
        artImage.visibility = View.GONE; artImage.setImageDrawable(null); artPlaceholder.visibility = View.VISIBLE
        // Dedicated executor means library thumbnails can never block Now Playing artwork.
        playbackArtworkExecutor.execute {
            val key = "player:" + t.uri.toString()
            val bitmap = bitmapArtworkCache.get(key) ?: readArtworkBytes(t)?.let { decodeArtwork(it, 1600, android.graphics.Bitmap.Config.ARGB_8888) }?.also { bitmapArtworkCache.put(key, it) }
            handler.post {
                if (token != artToken) return@post
                if (bitmap != null && !isFinishing) {
                    artImage.setImageBitmap(bitmap); artImage.visibility = View.VISIBLE; artPlaceholder.visibility = View.GONE
                    if (::playerBackdropImage.isInitialized) playerBackdropImage.setImageBitmap(bitmap)
                    applyArtworkAccent(bitmap)
                    if (prepared) updatePlaybackNotification(try { player?.isPlaying == true } catch (_: Exception) { false })
                } else {
                    artImage.visibility = View.GONE; artPlaceholder.visibility = View.VISIBLE; artPlaceholder.text = "NO COVER ART"; artPlaceholder.background = android.graphics.drawable.ColorDrawable(accentColor)
                    if (::playerBackdropImage.isInitialized) playerBackdropImage.setImageDrawable(null)
                }
            }
        }
    }

    private fun refreshAudioInfo() {
        val devices = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (_: Exception) { emptyList() }
        val isPlayingNow = try { player?.isPlaying == true } catch (_: Exception) { false }
        val outputTrack = activeAudioTrack
        val outputTrackPlaying = try { isPlayingNow && outputTrack != null && outputTrack.playState == AudioTrack.PLAYSTATE_PLAYING } catch (_: Exception) { false }
        val active = try {
            val routed = if (outputTrackPlaying) outputTrack?.routedDevice else null
            lastRouteApiError = null
            routed
        } catch (e: Exception) {
            val message = e.javaClass.simpleName + ": " + (e.message ?: "no details")
            if (lastRouteApiError != message) recordDiagnostic("AUDIOTRACK_ROUTED_DEVICE_QUERY_FAILED " + message, e)
            lastRouteApiError = message
            null
        }
        val legacyHints = buildList {
            try { if (audioManager.isWiredHeadsetOn) add("isWiredHeadsetOn=true (jack connection hint, not the active route)") } catch (_: Exception) { }
            try { if (audioManager.isBluetoothA2dpOn) add("isBluetoothA2dpOn=true (Bluetooth A2DP hint, not the active route)") } catch (_: Exception) { }
            try { if (audioManager.isBluetoothScoOn) add("isBluetoothScoOn=true (Bluetooth SCO hint, not the active route)") } catch (_: Exception) { }
            try { if (audioManager.isSpeakerphoneOn) add("isSpeakerphoneOn=true (phone speaker flag, not proof of media routing)") } catch (_: Exception) { }
        }.ifEmpty { listOf("No legacy flags are active; this does not prove that no jack/headphones are connected.") }.joinToString("; ")
        if (legacyHints != lastLegacyRouteHints) {
            recordDiagnostic("LEGACY_ROUTE_HINTS " + legacyHints)
            lastLegacyRouteHints = legacyHints
        }
        val connectedWired = devices.filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        val connectedBluetooth = devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 28 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
        val connectionSummary = buildString {
            append("CONNECTED devices according to AudioManager: ")
            append(if (devices.isEmpty()) "no output devices reported" else devices.joinToString { describe(it) })
            append("\nIndikasi legacy AudioManager: ").append(legacyHints)
            append("\nRegistered headphones/jack: ").append(if (connectedWired.isEmpty()) "none" else connectedWired.joinToString { describe(it) })
            append("\nBluetooth devices listed: ").append(if (connectedBluetooth.isEmpty()) "none" else connectedBluetooth.joinToString { describe(it) })
            append("\nIMPORTANT: The entries/flags above indicate connections or legacy hints, not the active stream route.")
        }
        val route = when {
            active != null -> deviceName(active) + " • " + deviceType(active.type) + " (type=" + active.type + ", id=" + active.id + ")"
            player == null -> "No active stream"
            !isPlayingNow -> "Not playing; active routing is not being measured"
            outputTrack == null -> "Media3 AudioTrack is not available for inspection"
            else -> "ROUTE UNCONFIRMED — getRoutedDevice() returned null; this does not prove headphones are disconnected"
        }
        val playbackState = when {
            player == null -> "no player"
            !prepared -> "player not ready"
            isPlayingNow -> "memutar"
            else -> "paused / not playing"
        }
        val audioMode = try {
            when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "mode=" + audioManager.mode
            }
        } catch (_: Exception) { "unavailable" }
        val sessionId = try { if (outputTrackPlaying) outputTrack?.audioSessionId?.toString() ?: "unavailable" else "inactive" } catch (_: Exception) { "unavailable" }
        val outputRate = try { if (outputTrackPlaying) outputTrack?.sampleRate else null } catch (_: Exception) { null }
        val outputEncoding = try { if (outputTrackPlaying) outputTrack?.audioFormat?.let { encodingName(it) } else null } catch (_: Exception) { null }
        val outputChannels = try { if (outputTrackPlaying) outputTrack?.channelCount else null } catch (_: Exception) { null }
        val outputBufferSizeFrames = try { if (outputTrackPlaying) outputTrack?.bufferSizeInFrames else null } catch (_: Exception) { null }
        val outputBufferCapacityFrames = try { if (outputTrackPlaying) outputTrack?.bufferCapacityInFrames else null } catch (_: Exception) { null }
        // Buffer capacity converted to time; this is not measured end-to-end latency.
        val outputBufferCapacityMs = if (outputTrackPlaying && outputBufferCapacityFrames != null && outputRate != null && outputRate > 0)
            outputBufferCapacityFrames * 1000.0 / outputRate else null
        val outputPerformance = try {
            if (outputTrackPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) outputTrack?.performanceMode?.let {
                when (it) { AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "LOW_LATENCY"; AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "POWER_SAVING"; else -> "NONE/UNKNOWN" }
            } else null
        } catch (_: Exception) { null }
        val activeCapabilities = if (active == null) "Active device capabilities cannot yet be linked to the actual route." else describeCapabilities(active)
        val defaultRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "Not reported"
        val buffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "Not reported"
        val systemBufferFrames = buffer.toIntOrNull()
        val bufferRatio = if (outputTrackPlaying && outputBufferSizeFrames != null && systemBufferFrames != null && systemBufferFrames > 0) outputBufferSizeFrames.toDouble() / systemBufferFrames else null
        val outputUnderruns = try { if (outputTrackPlaying) outputTrack?.underrunCount else null } catch (_: Exception) { null }
        if (outputTrackPlaying) {
            val key = "session=${sessionId};rate=${outputRate};bufferSizeFrames=${outputBufferSizeFrames};bufferCapacityFrames=${outputBufferCapacityFrames};systemFrames=${systemBufferFrames};underruns=${outputUnderruns};mode=${outputPerformance}"
            if (key != lastBufferDiagnosticKey) {
                recordDiagnostic("AUDIOTRACK_BUFFER_DIAGNOSTIC $key bufferSizeToSystemBufferRatio=${bufferRatio?.let { String.format(Locale.US, "%.1fx", it) } ?: "unavailable"} note=AudioTrack-bufferSizeInFrames-is-current-track-buffer-setting; bufferCapacityInFrames-is-maximum-track-capacity; PROPERTY_OUTPUT_FRAMES_PER_BUFFER-is-a-system-property-not-a-measurement-of-this-track-buffer; capacity-time-is-not-measured-latency; no-buffer-size-forcing")
                lastBufferDiagnosticKey = key
            }
        }
        val wired = devices.filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        val wiredText = if (wired.isEmpty()) "Wired jack: not detected" else "Wired jack detected: " + wired.joinToString { describe(it) }
        val rateNote = when {
            sourceRate == null -> "Source sample rate has not been read."
            outputTrackPlaying && outputRate != null && outputRate == sourceRate ->
                "The source sample rate matches the AudioTrack configuration (${outputRate} Hz). This confirms the track configuration, not the physical DAC sample rate or bit-perfect output."
            outputTrackPlaying && outputRate != null && outputRate != sourceRate ->
                "The source sample rate (${sourceRate} Hz) differs from the AudioTrack configuration (${outputRate} Hz); format/rate conversion in the output pipeline should be investigated. This does not reveal what the HAL/DSP/DAC is doing."
            sourceRate.toString() == defaultRate ->
                "The source sample rate matches the system default property; this does not prove the stream format or DAC behavior."
            else ->
                "The source sample rate differs from the system default property, but the AudioTrack rate is unreadable; this is insufficient to conclude that resampling occurred."
        }
        routeSummary.text = route
        routeInfo.text = "Status: " + (if (isPlayingNow) "PLAYING" else "not playing") +
            "\nActual active route: " + route + "\n" + wiredText +
            "\nStatus stream: " + playbackState + "\nAudio session ID: " + sessionId +
            "\nActive AudioTrack output (track configuration, not a DAC measurement): " +
                (if (outputTrackPlaying) (outputRate?.toString() ?: "?") + " Hz / " + (outputEncoding ?: "encoding unreadable") +
                    " / " + (outputChannels?.toString() ?: "?") + " kanal / buffer size " + (outputBufferSizeFrames?.toString() ?: "unavailable") +
                    " frame / capacity " + (outputBufferCapacityFrames?.toString() ?: "unavailable") + " frame (~" +
                    (outputBufferCapacityMs?.let { String.format(Locale.US, "%.1f ms capacity", it) } ?: "duration unavailable") +
                    "; bukan latensi terukur) / mode " + (outputPerformance ?: "unavailable") +
                    " / underruns " + (outputUnderruns?.toString() ?: "unavailable") +
                    " / rasio buffer size ke buffer sistem " + (bufferRatio?.let { String.format(Locale.US, "%.1fx", it) } ?: "unavailable")
                    else "inactive") +
            "\nAudioManager mode: " + audioMode +
            "\nRoute API status: " + (when { active != null -> "AudioTrack reports the actual route"; !outputTrackPlaying -> "AudioTrack is not in PLAYING state"; lastRouteApiError != null -> "Route API failed: " + lastRouteApiError; else -> "ROUTE UNCONFIRMED — getRoutedDevice() returned null; Android has not exposed the active route (not proof of disconnection)" }) +
            "\nActive DIRECT output mode: NOT EXPOSED by the public AudioTrack/Media3 inspection used here; capability checks do not identify the mode selected for this stream." +
            "\nLG Quad DAC backend status: NOT CONFIRMED by the public API (the HAL backend name is not exposed to regular apps)." +
            "\n" + connectionSummary +
            "\nSource sample rate: " + (sourceRate?.toString() ?: "Not read yet") + " Hz" +
            "\nSample rate default Android (properti low-latency/native, bukan pengukuran stream ini): " + defaultRate + " Hz\nBuffer default sistem: " + buffer + " frame" +
            "\n" + activeCapabilities + "\n\nOutput devices reported by Android (not necessarily active):\n" +
            (if (devices.isEmpty()) "No devices reported." else devices.joinToString("\n") { "• " + describe(it) }) +
            "\nLast event: " + lastDeviceEvent + "\n\n" + rateNote +
            "\n\nAudioTrack shows the format configured for the app stream. The audio HAL/DSP/DAC may still convert it; this is not proof of bit-perfect output or the physical DAC sample rate."
        if (::audioInfoInline.isInitialized) {
            val fileDetails = if (::fileInfo.isInitialized) fileInfo.text.toString() else "Choose a track to read file metadata."
            val track = tracks.getOrNull(currentIndex)
            val extension = track?.uri?.lastPathSegment?.substringAfterLast('.', "")?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "AUDIO"
            val sourceKhz = sourceRate?.let { String.format(Locale.US, "%.1f", it / 1000f) } ?: "?"
            val outputTrack = activeAudioTrack
            val playingOutput = try { player?.isPlaying == true && outputTrack?.playState == AudioTrack.PLAYSTATE_PLAYING } catch (_: Exception) { false }
            val outputRate = try { if (playingOutput) outputTrack?.sampleRate else null } catch (_: Exception) { null }
            val outputKhz = outputRate?.let { String.format(Locale.US, "%.1f", it / 1000f) } ?: "?"
            val routeLabel = try { if (playingOutput) outputTrack?.routedDevice?.let { deviceType(it.type) } ?: "route not reported" else "inactive" } catch (_: Exception) { "route not reported" }
            val bitrateLabel = sourceBitrate?.let { " • ${it / 1000} kbps" } ?: ""
            val bitPerfectText = when {
                playingOutput && sourceRate != null && outputRate != null && sourceRate != outputRate -> "● RESAMPLING / NOT BIT-PERFECT"
                else -> "● BIT-PERFECT NOT CONFIRMED"
            }
            val formatLine = "$extension$bitrateLabel • source $sourceKhz kHz → output $outputKhz kHz • $routeLabel"
            val compact = "$formatLine\n$bitPerfectText"
            val colored = android.text.SpannableString(compact)
            val start = compact.indexOf(bitPerfectText)
            if (start >= 0) {
                // Android's public APIs cannot confirm bit-perfect delivery; orange means unknown or conversion indicated.
                colored.setSpan(android.text.style.ForegroundColorSpan(0xFFFFA43A.toInt()), start, compact.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            audioInfoInline.text = colored
        }
    }

    private fun copyText(value: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("G6 HiFi Player log", value))
            Toast.makeText(this, "Log disalin ke clipboard.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Unable to copy log: " + e.message, Toast.LENGTH_LONG).show() }
    }
    private fun shareText(value: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "G6 HiFi Player — log audio"); putExtra(Intent.EXTRA_TEXT, value)
            }, "Share audio log"))
        } catch (e: Exception) { Toast.makeText(this, "Unable to open the share menu.", Toast.LENGTH_SHORT).show() }
    }
    private fun copyAudioLog() {
        val route = if (::routeInfo.isInitialized) routeInfo.text.toString() else "Route information is unavailable."
        val file = if (::fileInfo.isInitialized) fileInfo.text.toString() else "File information is unavailable."
        val events = synchronized(diagnosticEvents) { diagnosticEvents.joinToString("\n\n") }
        copyText("G6 HiFi Player — AUDIO DIAGNOSTICS / SCAN\nTime: ${System.currentTimeMillis()}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\nApp version: ${appVersionLabel()}\n\nROUTE:\n$route\n\nFILE:\n$file\n\nLG CONFIGURATION:\n$lastAudioConfigLog\n\nLATEST EVENTS:\n$events")
    }
    private fun shareAudioLog() {
        val route = if (::routeInfo.isInitialized) routeInfo.text.toString() else "Route information is unavailable."
        val file = if (::fileInfo.isInitialized) fileInfo.text.toString() else "File information is unavailable."
        val events = synchronized(diagnosticEvents) { diagnosticEvents.joinToString("\n\n") }
        shareText("G6 HiFi Player — AUDIO DIAGNOSTICS / SCAN\nTime: ${System.currentTimeMillis()}\nDevice: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\nApp version: ${appVersionLabel()}\n\nROUTE:\n$route\n\nFILE:\n$file\n\nLG CONFIGURATION:\n$lastAudioConfigLog\n\nLATEST EVENTS:\n$events")
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
        mediaSession = MediaSession(this, "G6HiFiPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    val activity = activeInstance ?: return
                    if (activity.player != null && activity.prepared && activity.player?.isPlaying != true) activity.togglePlayback()
                }
                override fun onPause() {
                    val activity = activeInstance ?: return
                    if (activity.player?.isPlaying == true) activity.togglePlayback()
                }
                override fun onSkipToNext() { activeInstance?.playNext() }
                override fun onSkipToPrevious() { activeInstance?.playPrevious() }
                override fun onSeekTo(pos: Long) {
                    activeInstance?.player?.let { p -> try { p.seekTo(pos.coerceIn(0L, activityDuration(p).toLong())) } catch (_: Exception) { } }
                }
            })
            isActive = false
        }
    }
    private fun activityDuration(p: ExoPlayer): Int = try { p.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } catch (_: Exception) { 0 }

    private fun updatePlaybackNotification(isPlaying: Boolean) {
        if (notificationDismissed || !prepared || currentIndex !in tracks.indices) return
        val track = tracks[currentIndex]
        val widgetBitmap = try {
            (artImage.drawable as? BitmapDrawable)?.bitmap
        } catch (_: Exception) { null }
        G6MusicWidgetProvider.refresh(
            this, track.title, track.artist, track.album, isPlaying, widgetBitmap, lightTheme,
            try { player?.currentPosition?.toInt() ?: 0 } catch (_: Exception) { 0 },
            try { player?.duration?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0 } catch (_: Exception) { 0 }
        )
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        fun actionIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(this, NotificationActionReceiver::class.java).apply {
                putExtra("notification_action", action)
            }
            return PendingIntent.getBroadcast(this, requestCode, intent, flags)
        }
        val contentIntent = PendingIntent.getActivity(this, 6100, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, flags)
        recordDiagnostic("NOTIFICATION_UPDATE playing=$isPlaying title=${track.title} sourceRate=${sourceRate ?: -1} engine=Media3/ExoPlayer trackRoute=queried-separately backend=not-exposed-by-public-api")
        val cover = (artImage.drawable as? BitmapDrawable)?.bitmap
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
        val session = mediaSession
        session?.setMetadata(metadata.build())
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(state, try { player?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }, if (isPlaying) 1f else 0f)
            .build())
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(track.title)
            .setContentText(track.artist + " • " + track.album)
            // Android 9 may show these fields in the right side of the notification header.
            // Keep the system app name on the left, but do not duplicate it on the right.
            .setSubText(null)
            .setContentInfo(null)
            .setContentIntent(contentIntent)
            // Swiping away the notification hides it without stopping playback.
            .setDeleteIntent(actionIntent("dismiss", 6104))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", actionIntent("previous", 6101)).build())
            .addAction(Notification.Action.Builder(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Pause" else "Play", actionIntent("toggle", 6102)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", actionIntent("next", 6103)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss notification", actionIntent("dismiss", 6104)).build())
        if (cover != null && !cover.isRecycled) builder.setLargeIcon(cover)
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, builder.build())
    }
    private fun dismissBroadcastIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val intent = Intent(this, NotificationActionReceiver::class.java).putExtra("notification_action", "dismiss")
        return PendingIntent.getBroadcast(this, 6104, intent, flags)
    }

    private fun stopAndDismissPlayback() {
        pendingAutoplay = false
        prepared = false
        seeking = false
        clearActiveAudioTrack()
        try { player?.stop(); player?.clearMediaItems(); player?.release() } catch (_: Exception) { }
        player = null
        currentIndex = -1
        sourceRate = null
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
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { }
        refreshAudioInfo()
        recordDiagnostic("NOTIFICATION_DISMISS playback=stopped player=released mediaSession=inactive")
    }

    private fun handleNotificationAction(action: String) {
        when (action) {
            "previous" -> { notificationDismissed = false; playPrevious() }
            "next" -> { notificationDismissed = false; playNext() }
            "toggle" -> {
                notificationDismissed = false
                togglePlayback()
                updatePlaybackNotification(try { player?.isPlaying == true } catch (_: Exception) { false })
            }
            "dismiss" -> {
                notificationDismissed = true
                try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("notification_action")?.let { handleNotificationAction(it) }
    }

    private fun scanAudioConfiguration(showDialog: Boolean = true) {
        val paths = listOf(
            "/vendor/etc/audio_output_policy.conf",
            "/vendor/etc/audio/mixer_paths.xml",
            "/vendor/etc/audio/mixer_paths_wcd9335.xml",
            "/vendor/etc/audio/mixer_paths_wcd9340.xml",
            "/vendor/etc/audio/mixer_paths_msm8996.xml",
            "/vendor/etc/audio/audio_platform_info.xml",
            "/vendor/etc/audio/audio_platform_info_extcodec.xml",
            "/odm/etc/audio_output_policy.conf",
            "/odm/etc/audio_policy.conf",
            "/odm/etc/audio_policy_configuration.xml",
            "/odm/etc/mixer_paths.xml",
            "/odm/etc/audio_platform_info.xml",
            "/system/vendor/etc/audio_output_policy.conf",
            "/system/vendor/etc/audio_policy.conf",
            "/system/vendor/etc/audio_policy_configuration.xml",
            "/system/vendor/etc/mixer_paths.xml",
            "/system/vendor/etc/audio_platform_info.xml",
            "/system/vendor/etc/audio_platform_info_extcodec.xml",
            "/vendor/etc/audio_policy.conf",
            "/vendor/etc/audio_policy_configuration.xml",
            "/vendor/etc/mixer_paths.xml",
            "/vendor/etc/mixer_paths_wcd9335.xml",
            "/vendor/etc/mixer_paths_wcd9340.xml",
            "/vendor/etc/audio_platform_info.xml",
            "/vendor/etc/audio_platform_info_extcodec.xml",
            "/vendor/etc/audio_effects.conf",
            "/vendor/etc/audio_effects.xml",
            "/system/etc/audio_policy.conf",
            "/system/etc/audio_policy_configuration.xml",
            "/system/etc/audio_effects.conf",
            "/system/etc/audio_effects.xml"
        )
        val keys = listOf("direct_pcm", "direct_hd", "pcm_8_24", "pcm_24", "192000", "176400", "96000",
            "88200", "flag_dsd", "supports_pcm", "samplingrates", "sampling_rates", "audio_output_flag_direct",
            "compress_offload", "deep_buffer", "headphone", "headset", "hifi", "quad", "wcd9335", "wcd9340", "SND_DEVICE_LGE_OUT_HEADPHONES_HIFI_DAC", "tert-mi2s-headphones", "SEC_MI2S_RX", "hifi_dac", "mixer_path")
        val report = StringBuilder()
        var readable = 0
        for (path in paths) {
            val file = File(path)
            try {
                if (!file.exists()) {
                    report.append("\n").append(path).append("\nStatus: not present at this path.\n")
                    continue
                }
                if (!file.isFile || !file.canRead()) {
                    report.append("\n").append(path).append("\nStatus: present but inaccessible to a regular app.\n")
                    continue
                }
                val matches = file.useLines { lines ->
                    lines.filter { line -> keys.any { key -> line.lowercase(Locale.ROOT).contains(key) } }
                        .take(24).toList()
                }
                readable++
                report.append("\n").append(path).append('\n').append("Status: readable.\n")
                if (matches.isEmpty()) report.append("No lines matched the audio search keywords.\n")
                else matches.forEach { report.append(it.trim()).append('\n') }
            } catch (e: Exception) {
                report.append("\n").append(path).append("\nStatus: failed to read (").append(e.javaClass.simpleName).append(": ").append(e.message ?: "no details").append(").\n")
            }
        }
        report.append("\nDirect playback PCM stereo (system capability, not the active stream):\n")
        report.append("IMPORTANT: isDirectPlaybackSupported() reports whether a format is supported by the device/API; it does not reveal whether the current Media3 AudioTrack was opened with DIRECT output flags. The active output mode is not exposed by the public inspection APIs used by this app.\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            for (rate in listOf(44100, 48000, 96000, 192000)) {
                val supported = try {
                    val f = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                    AudioTrack.isDirectPlaybackSupported(f, attrs)
                } catch (_: Exception) { false }
                report.append(rate).append(" Hz / PCM 16-bit: ").append(if (supported) "reported as supported by the API" else "not reported as supported").append('\n')
            }
        } else report.append("The direct-playback API is unavailable on this Android version.\n")
        report.append("\nReadable configuration files: ").append(readable).append(" of ").append(paths.size)
        report.append("\nConfiguration files indicate capabilities declared by the system; they do not prove Media3 is using DIRECT_HD/direct_pcm_24 or that the DAC receives a specific sample rate.")
        report.append("\nBUFFER DATA PROVENANCE: bufferSizeInFrames and bufferCapacityInFrames come from the active AudioTrack object; PROPERTY_OUTPUT_FRAMES_PER_BUFFER is a separate AudioManager system property. These values are not interchangeable, and capacity-derived time is not measured end-to-end latency.")
        report.append("\n\nLG G6-SPECIFIC INTERPRETATION: SND_DEVICE_LGE_OUT_HEADPHONES_HIFI_DAC together with the headphones backend tert-mi2s-headphones and SEC_MI2S_RX indicates a possible Quad DAC path configuration. Configuration files only show that a path is defined, not that the current stream is using it.")
        report.append("\nLG QUAD DAC BACKEND STATUS: not confirmed through the public API. Wired-device preference only asks AudioPolicy to select the 3.5 mm jack; it does not force AUDIO_OUTPUT_FLAG_DIRECT or prove the HAL/DAC backend.")
        report.append("\n\nTo inspect the backend while playing through the 3.5 mm jack, run these commands from a computer with USB debugging enabled:")
        report.append("\nadb shell dumpsys media.audio_flinger")
        report.append("\nadb shell dumpsys media.audio_policy")
        report.append("\nSend both outputs with the app log. If system output does not include a relevant backend or output thread name, physical DAC verification may require vendor/root access; do not infer DAC activity from the AudioTrack rate alone.")
        lastAudioConfigLog = report.toString()
        if (showDialog) {
            AlertDialog.Builder(this).setTitle("LG audio scan results").setMessage(lastAudioConfigLog)
                .setNeutralButton("COPY LOG") { _, _ -> copyText(lastAudioConfigLog) }
                .setNegativeButton("SHARE LOG") { _, _ -> shareText(lastAudioConfigLog) }
                .setPositiveButton("CLOSE", null).show()
        }
    }

    private fun collectAndShareDiagnostics() {
        refreshAudioInfo()
        scanAudioConfiguration(showDialog = false)
        shareAudioLog()
    }

    private fun updateActiveRoute() {
        val track = activeAudioTrack
        val playing = try { player?.isPlaying == true && track?.playState == AudioTrack.PLAYSTATE_PLAYING } catch (_: Exception) { false }
        val active = try { val routed = if (playing) track?.routedDevice else null; lastRouteApiError = null; routed } catch (e: Exception) {
            val message = e.javaClass.simpleName + ": " + (e.message ?: "no details")
            if (lastRouteApiError != message) recordDiagnostic("AUDIOTRACK_ROUTED_DEVICE_QUERY_FAILED " + message, e)
            lastRouteApiError = message
            null
        }
        val routeKey = when {
            player == null -> "no-player"
            !playing -> "not-playing"
            active != null -> "${active.id}:${active.type}:${deviceName(active)}"
            else -> "audio-track-route-unreported"
        }
        if (routeKey != lastActiveRouteKey) {
            val previous = lastActiveRouteKey
            lastActiveRouteKey = routeKey
            val label = when {
                player == null -> "No active stream"
                !playing -> "Not playing"
                active != null -> deviceName(active) + " • " + deviceType(active.type) + " (type=" + active.type + ", id=" + active.id + ")"
                else -> "AudioTrack is active; the routed device has not been reported"
            }
            if (::routeSummary.isInitialized) routeSummary.text = label
            if (previous != null && previous != routeKey) {
                lastDeviceEvent = "AudioTrack route: " + label
                recordDiagnostic("OUTPUT_ROUTE_CHANGED previous=" + previous + " current=" + routeKey + " routedDevice=" + (active?.let { describe(it) } ?: "null") + " playing=" + playing)
            }
            if (::routeInfo.isInitialized) refreshAudioInfo()
        }
    }
    private fun show(which: String) {
        if (which == "queue") {
            show("player")
            openQueueOverlay()
            return
        }
        currentPage = which
        if (which != "player" && ::queuePanel.isInitialized && queuePanel.visibility == View.VISIBLE) {
            queuePanel.animate().cancel()
            queuePanel.visibility = View.GONE
            queuePanel.translationY = 0f
            queuePanel.alpha = 1f
        }
        // Only Now Playing uses the translucent scrim; all pages retain a transparent system bar
        // so the activity can draw its page-specific background beneath it.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or (if (lightTheme) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        window.statusBarColor = Color.TRANSPARENT
        playerPanel.visibility = if (which == "player") View.VISIBLE else View.GONE
        libraryPanel.visibility = if (which == "library") View.VISIBLE else View.GONE
        songsPanel.visibility = if (which == "songs") View.VISIBLE else View.GONE
        settingsPanel.visibility = if (which == "settings" || which == "audio" || which == "file") View.VISIBLE else View.GONE
        navState(navPlayer, which == "player")
        navState(navLibrary, which == "library")
        navState(navSongs, which == "songs")
        navState(navFile, which == "settings" || which == "audio" || which == "file")
        if (which == "songs") renderSongs()
    }
    private fun navButton(iconRes: Int, description: String, click: () -> Unit) = FrameLayout(this).apply {
        contentDescription = description
        // Keep the whole equal-width tab as the touch target. The icon is a separate,
        // fixed-size child centered by FrameLayout gravity, never by a drawable matrix.
        clipChildren = false
        clipToPadding = false
        val icon = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon, FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
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
        return RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), pill, null)
    }
    private fun selectedHighlight(): Int = (accentColor and 0x00FFFFFF) or 0x66000000

    private fun navState(view: FrameLayout, selected: Boolean) {
        (view.getChildAt(0) as? ImageView)?.imageTintList =
            ColorStateList.valueOf(if (selected) accentColor else MUTED)
        view.background = navBackground(selected)
    }
    private fun iconAction(label: String, click: () -> Unit) = text(label, 20f, WHITE, true).apply {
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), shape(Color.TRANSPARENT, 24), null)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun addToQueue(index: Int) {
        if (index !in tracks.indices) return
        val albumIndices = tracks.withIndex().filter { it.value.album == tracks[index].album }.map { it.index }
        playbackQueue = (playbackQueue + albumIndices.ifEmpty { listOf(index) }).distinct()
        renderQueueRows()
        Toast.makeText(this, "Album added to queue: ${tracks[index].album}", Toast.LENGTH_SHORT).show()
    }

    private fun addAlbumToQueue(indices: List<Int>, album: String) {
        val valid = indices.filter { it in tracks.indices }.distinct()
        if (valid.isEmpty()) return
        playbackQueue = (playbackQueue + valid).distinct()
        renderQueueRows()
        Toast.makeText(this, "Album added to queue: $album", Toast.LENGTH_SHORT).show()
    }

    private fun renderQueueRows() {
        if (!::queueRows.isInitialized) return
        updateQueueMiniPlayer()
        queueRows.removeAllViews()
        val queue = playbackQueue.filter { it in tracks.indices }
        if (queue.isEmpty()) { queueRows.addView(text("The queue is empty. Add an album from the Albums tab.", 13f, MUTED, false)); return }
        queue.forEachIndexed { position, trackIndex ->
            val track = tracks[trackIndex]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = RippleDrawable(
                    ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x38000000),
                    shape(if (trackIndex == currentIndex) selectedHighlight() else Color.TRANSPARENT, 12),
                    null
                )
                isClickable = true; isFocusable = true
                setOnClickListener { playTrackAt(trackIndex, true, playbackQueue); show("player") }
            }
            val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(ART, 8); contentDescription = "Artwork " + track.album }
            row.addView(cover, LinearLayout.LayoutParams(dp(54), dp(54)))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
            info.addView(text((position + 1).toString() + ". " + track.title, 12f, WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.addView(text(track.artist + " • " + track.album, 12f, MUTED, false).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(iconAction("×") {
                if (trackIndex == currentIndex) Toast.makeText(this, "The currently playing track cannot be removed from the active queue.", Toast.LENGTH_SHORT).show()
                else { playbackQueue = playbackQueue.filter { it != trackIndex }; playbackOrder = playbackQueue; renderQueueRows() }
            }, LinearLayout.LayoutParams(dp(38), dp(44)))
            queueRows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            loadArtworkInto(cover, track)
        }
    }
    private fun showAlbumOptions(album: String, indices: List<Int>) {
        val dialog = AlertDialog.Builder(this).create()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = shape(if (lightTheme) 0xFFFFFFFF.toInt() else 0xFF17171D.toInt(), 24)
        }
        panel.addView(text(album, 18f, WHITE, true).apply {
            setPadding(dp(2), dp(2), dp(2), dp(14)); maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        fun option(label: String, onClick: () -> Unit) {
            val item = text(label, 14f, WHITE, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), shape(if (lightTheme) 0xFFF1F0F8.toInt() else 0xFF24242D.toInt(), 14), null)
                isClickable = true; isFocusable = true
                setOnClickListener { dialog.dismiss(); onClick() }
            }
            panel.addView(item, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
        }
        option("▶  Play entire album") {
            if (indices.isNotEmpty()) { playTrackAt(indices.first(), true, indices); show("player") }
        }
        option("＋  Add album to queue") { addAlbumToQueue(indices, album) }
        dialog.setView(panel)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.55f)
    }

    private fun flatSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)); setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = shape(CARD, 21)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun action(label: String, primary: Boolean, click: () -> Unit) = text(label, 11f, if (primary) BG else WHITE, true).apply {
        gravity = Gravity.CENTER; background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), shape(if (primary) accentColor else LINE, 14), null)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun describe(d: AudioDeviceInfo) = deviceName(d) + " (" + deviceType(d.type) + ", type=" + d.type + ", id=" + d.id + ")"
    private fun describeCapabilities(d: AudioDeviceInfo): String {
        val rates = try { d.sampleRates.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "not reported" } catch (_: Exception) { "unavailable" }
        val channels = try { d.channelCounts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "not reported" } catch (_: Exception) { "unavailable" }
        val encodings = try { d.encodings.takeIf { it.isNotEmpty() }?.joinToString(", ") { encodingName(it) } ?: "not reported" } catch (_: Exception) { "unavailable" }
        return "Capabilities reported for the active device (not proof of the active stream format):\nSample rates: " + rates + " Hz\nChannel count: " + channels + "\nEncoding: " + encodings
    }
    private fun encodingName(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit packed"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        else -> "encoding=" + encoding
    }
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
    override fun onBackPressed() {
        if (::queuePanel.isInitialized && queuePanel.visibility == View.VISIBLE) {
            closeQueueOverlay()
            return
        }
        if (currentPage == "library" && selectedAlbum != null) {
            selectedAlbum = null
            renderLibrary()
            return
        }
        if (currentPage != "player") {
            show("player")
            return
        }
        super.onBackPressed()
    }

    private inner class SwipeFrameLayout(
        context: Context,
        private val onSwipeDown: () -> Unit,
        private val onSwipeUp: () -> Unit
    ) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f

        init { isClickable = true }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val dx = event.x - downX
                val dy = event.y - downY
                if (kotlin.math.abs(dy) > dp(72) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.25f) {
                    val overlayOpen = ::queuePanel.isInitialized && queuePanel.visibility == View.VISIBLE
                    if (dy > 0 && !overlayOpen) onSwipeDown()
                    else if (dy < 0 && overlayOpen && downY < dp(180)) onSwipeUp()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
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
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) { }
        clearActiveAudioTrack()
        try { player?.release() } catch (_: Exception) { }
        try { mediaSession?.isActive = false; mediaSession?.release() } catch (_: Exception) { }
        mediaSession = null
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { }
        if (activeInstance === this) activeInstance = null
        player = null; super.onDestroy()
    }
    companion object {
        private const val REQUEST_AUDIO = 1001
        private const val CHANNEL_ID = "g6_hifi_playback"
        private const val NOTIFICATION_ID = 6106
        private const val KEY_LAST_TRACK_URI = "last_playback_track_uri"
        private const val KEY_LAST_POSITION_MS = "last_playback_position_ms"
        private var activeInstance: MainActivity? = null
        @JvmStatic fun notificationAction(action: String) { val activity = activeInstance ?: return; activity.handler.post { if (!activity.isFinishing && !activity.isDestroyed) activity.handleNotificationAction(action) } }
        private const val REQUEST_FOLDER = 1002
        private const val DEFAULT_ACCENT = 0xFF6662C6.toInt()
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "ape", "dsf", "dff", "wma", "3gp", "amr")
    }
}
