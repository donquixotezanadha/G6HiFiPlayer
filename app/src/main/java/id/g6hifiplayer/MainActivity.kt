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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private var tracks: List<Track> = emptyList()
    private var currentIndex = -1
    // Queue stores indices into the scanned library; album playback stays inside that album.
    private var playbackQueue: List<Int> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var selectedAlbum: String? = null
    private var artToken = 0
    private var accentColor = DEFAULT_ACCENT
    private var lightTheme = false
    private val BG get() = if (lightTheme) 0xFFF8F8FC.toInt() else 0xFF000000.toInt()
    private val CARD get() = if (lightTheme) 0xFFFFFFFF.toInt() else 0xFF101014.toInt()
    private val ART get() = if (lightTheme) 0xFFE9E9F0.toInt() else 0xFF181820.toInt()
    private val LINE get() = if (lightTheme) 0xFFE1E1EC.toInt() else 0xFF292932.toInt()
    private val WHITE get() = if (lightTheme) 0xFF171720.toInt() else 0xFFF3F3FA.toInt()
    private val MUTED get() = if (lightTheme) 0xFF686875.toInt() else 0xFFA4A4B2.toInt()
    private var visibleTrackLimit = 80
    private val artworkExecutor = Executors.newFixedThreadPool(3)
    // Keep current-player artwork independent of the library thumbnails backlog.
    private val playbackArtworkExecutor = Executors.newSingleThreadExecutor()
    private val metadataExecutor = Executors.newFixedThreadPool(2)
    private val artworkBytesCache = java.util.Collections.synchronizedMap(java.util.LinkedHashMap<String, ByteArray>(16, 0.75f, true))
    private val diagnosticEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var lastDeviceEvent = "Belum ada perubahan perangkat."
    private var lastActiveRouteKey: String? = null
    private var lastRouteApiError: String? = null
    private var lastLegacyRouteHints = "Belum diperiksa"
    private var lastAudioConfigLog = "Belum ada hasil scan konfigurasi audio."
    private lateinit var scanProgress: ProgressBar
    private lateinit var scanProgressText: TextView
    private lateinit var audioInfoInline: TextView
    private lateinit var playerPanel: LinearLayout
    private lateinit var libraryPanel: LinearLayout
    private lateinit var queuePanel: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var queueRows: LinearLayout
    private lateinit var audioPanel: LinearLayout
    private lateinit var filePanel: LinearLayout
    private lateinit var navPlayer: TextView
    private lateinit var navLibrary: TextView
    private lateinit var navAudio: TextView
    private lateinit var navFile: TextView
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
    private lateinit var artPlaceholder: TextView
    private lateinit var librarySummary: TextView
    private lateinit var albumRows: LinearLayout
    private lateinit var trackRows: LinearLayout
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (prepared) player?.let { p ->
                if (!seeking) {
                    val pos = try { p.currentPosition.toInt() } catch (_: Exception) { 0 }
                    seek.progress = pos; elapsed.text = time(pos)
                }
                if (try { p.isPlaying } catch (_: Exception) { false }) updateActiveRoute()
            }
            handler.postDelayed(this, 500)
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val details = addedDevices.joinToString { describe(it) }
            lastDeviceEvent = "Perangkat output terhubung: " + details
            recordDiagnostic("AUDIO_DEVICE_ADDED devices=" + details)
            activeAudioTrack?.let { preferWiredHeadphoneRoute(it, "perangkat output ditambahkan") }
            updateActiveRoute(); refreshAudioInfo()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val details = removedDevices.joinToString { describe(it) }
            lastDeviceEvent = "Perangkat output dilepas: " + details
            recordDiagnostic("AUDIO_DEVICE_REMOVED devices=" + details)
            updateActiveRoute(); refreshAudioInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lightTheme = getPreferences(MODE_PRIVATE).getBoolean("light_theme", false)
        window.statusBarColor = BG
        window.navigationBarColor = if (lightTheme) 0xFFF8F8FC.toInt() else Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or (if (lightTheme) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        activeInstance = this
        getPreferences(MODE_PRIVATE).getString("diagnostic_events", "")?.split("\n\n")?.filter { it.isNotBlank() }?.takeLast(300)?.let { synchronized(diagnosticEvents) { diagnosticEvents.addAll(it) } }
        buildUi(); audioManager.registerAudioDeviceCallback(deviceCallback, null)
        createNotificationChannel()
        createMediaSession()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 303)
        refreshAudioInfo(); handler.post(ticker)
        getPreferences(MODE_PRIVATE).getString("music_tree_uri", null)?.let {
            try { scanMusicFolder(Uri.parse(it)) } catch (_: Exception) { }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(7)); setBackgroundColor(Color.TRANSPARENT)
        }
        navPlayer = navButton("〰\nPUTAR") { show("player") }
        navLibrary = navButton("♫\nALBUM") { show("library") }
        navAudio = navButton("☷\nANTRIAN") { show("queue") }
        navFile = navButton("⚙\nSETTINGS") { show("settings") }
        listOf(navPlayer, navLibrary, navAudio, navFile).forEach { nav.addView(it, LinearLayout.LayoutParams(0, dp(54), 1f)) }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        playerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        libraryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queuePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        audioPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        makePlayerPanel(); makeLibraryPanel(); makeQueuePanel(); makeAudioPanel(); makeFilePanel()
        settingsPanel.addView(text("SETTINGS", 22f, WHITE, true).apply { setPadding(dp(4), dp(8), 0, dp(14)) })
        val themeCard = card()
        themeCard.addView(text("TEMA APLIKASI", 12f, MUTED, true))
        themeCard.addView(text("Pilih tampilan tanpa bergantung pada tema sistem Android.", 13f, WHITE, false).apply { setPadding(0, dp(6), 0, dp(10)) })
        themeCard.addView(action(if (lightTheme) "GUNAKAN TEMA GELAP" else "GUNAKAN TEMA PUTIH", false) {
            lightTheme = !lightTheme
            accentColor = DEFAULT_ACCENT
            window.statusBarColor = BG
            window.navigationBarColor = if (lightTheme) 0xFFF8F8FC.toInt() else Color.BLACK
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or (if (lightTheme) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            getPreferences(MODE_PRIVATE).edit().putBoolean("light_theme", lightTheme).apply()
            buildUi(); show("settings"); refreshAudioInfo(); renderLibrary()
            if (currentIndex in tracks.indices) loadArtwork(tracks[currentIndex])
            syncPlaybackIcon()
        }, LinearLayout.LayoutParams(-1, dp(44)))
        settingsPanel.addView(themeCard)
        settingsPanel.addView(audioPanel); settingsPanel.addView(filePanel)
        content.addView(playerPanel); content.addView(libraryPanel); content.addView(queuePanel); content.addView(settingsPanel)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(12); rightMargin = dp(12); topMargin = dp(3); bottomMargin = dp(8) })
        setContentView(root); show("player"); renderLibrary()
    }

    private fun makePlayerPanel() {
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(6)) }
        val frame = FrameLayout(this).apply { background = shape(ART, 22); clipToOutline = true }
        artImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        artPlaceholder = text("G6\nQUAD DAC / HI-RES", 24f, MUTED, true).apply { gravity = Gravity.CENTER }
        frame.addView(artImage, FrameLayout.LayoutParams(-1, -1)); frame.addView(artPlaceholder, FrameLayout.LayoutParams(-1, -1))
        val artworkSize = minOf(292, ((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 56).coerceAtLeast(220))
        hero.gravity = Gravity.CENTER_HORIZONTAL
        hero.addView(frame, LinearLayout.LayoutParams(dp(artworkSize), dp(artworkSize)).apply { topMargin = dp(14); bottomMargin = dp(8) })
        audioInfoInline = text("FLAC • sumber — kHz → output — kHz • status belum terverifikasi", 9f, MUTED, false).apply {
            setPadding(dp(4), dp(2), dp(4), dp(4)); visibility = View.VISIBLE; setTextIsSelectable(true)
        }
        hero.addView(audioInfoInline, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        titleView = text("Belum ada lagu dipilih", 20f, WHITE, true).apply { setPadding(0, dp(2), 0, dp(1)); maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        artistView = text("Pilih folder musik atau satu file", 14f, MUTED, false)
        albumView = text("", 12f, MUTED, false).apply { setPadding(0, dp(2), 0, dp(4)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        hero.addView(titleView); hero.addView(artistView); hero.addView(albumView)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        elapsed = text("0:00", 11f, MUTED, false); duration = text("0:00", 11f, MUTED, false)
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(duration); hero.addView(times)
        seek = SeekBar(this).apply {
            max = 1; progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) elapsed.text = time(value) }
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (prepared && bar != null) player?.seekTo(bar.progress.coerceIn(0, durationMs).toLong())
                    seeking = false
                }
            })
        }
        hero.addView(seek)
        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(5), 0, dp(2)) }
        transport.addView(iconAction("⏮") { playPrevious() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        transport.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        playView = ImageView(this).apply {
            contentDescription = "Putar atau jeda"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), shape(CARD, 16), null)
            imageTintList = ColorStateList.valueOf(accentColor)
            isClickable = true; isFocusable = true
            setOnClickListener { togglePlayback() }
        }
        syncPlaybackIcon()
        transport.addView(playView, LinearLayout.LayoutParams(0, dp(44), 1.1f))
        transport.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        transport.addView(iconAction("⏭") { playNext() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        hero.addView(transport)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        buttons.addView(action("PILIH FILE", false) { pickFile() }, LinearLayout.LayoutParams(0, dp(36), 1f))
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        buttons.addView(action("SCAN FOLDER", false) { pickFolder() }, LinearLayout.LayoutParams(0, dp(36), 1f))
        hero.addView(buttons); playerPanel.addView(hero)
        val state = card(); state.visibility = View.GONE; state.addView(text("STATUS PEMUTARAN", 11f, MUTED, true))
        statusView = text("Pilih folder musik atau file audio untuk memulai.", 14f, WHITE, false).apply { setPadding(0, dp(8), 0, 0) }
        state.addView(statusView); playerPanel.addView(state)
    }

    private fun makeLibraryPanel() {
        val head = card(); head.addView(text("ALBUM", 16f, WHITE, true))
        head.addView(text("Pilih satu folder Music; subfolder album dipindai otomatis.", 12f, MUTED, false).apply { setPadding(0, dp(5), 0, dp(12)) })
        head.addView(action("PILIH FOLDER MUSIC", true) { pickFolder() }, LinearLayout.LayoutParams(-1, dp(48)))
        librarySummary = text("Belum ada folder yang dipindai.", 12f, MUTED, false).apply { setPadding(0, dp(10), 0, 0) }
        head.addView(librarySummary)
        scanProgressText = text("", 12f, accentColor, false).apply { setPadding(0, dp(10), 0, dp(3)); visibility = View.GONE }
        head.addView(scanProgressText)
        scanProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        head.addView(scanProgress, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(3) })
        libraryPanel.addView(head)
        val albums = card(); albums.addView(text("ALBUM", 11f, accentColor, true))
        albumRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        albums.addView(albumRows); libraryPanel.addView(albums)
        val songs = card(); songs.addView(text("LAGU", 11f, accentColor, true))
        trackRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        songs.addView(trackRows); libraryPanel.addView(songs)
    }

    private fun makeQueuePanel() {
        val head = card()
        head.addView(text("ANTRIAN PUTAR", 18f, WHITE, true))
        head.addView(text("Hanya lagu yang kamu tambahkan ke antrian akan tampil di sini.", 12f, MUTED, false).apply { setPadding(0, dp(6), 0, dp(10)) })
        queueRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        head.addView(queueRows)
        queuePanel.addView(head)
    }

    private fun makeAudioPanel() {
        val summary = card(); summary.addView(text("OUTPUT AKTIF", 11f, accentColor, true))
        routeSummary = text("Memeriksa…", 22f, WHITE, true).apply { setPadding(0, dp(8), 0, dp(5)) }
        summary.addView(routeSummary); summary.addView(text("Rute stream dibaca dari AudioTrack milik Media3. Backend internal LG Quad DAC tidak tersedia melalui API publik; perangkat headphone terdeteksi bukan bukti backend DAC tertentu.", 12f, MUTED, false)); audioPanel.addView(summary)
        val detail = card(); detail.addView(text("DIAGNOSTIK ROUTE", 11f, MUTED, true))
        routeInfo = text("", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        detail.addView(routeInfo)
        detail.addView(action("PERBARUI INFORMASI", false) { refreshAudioInfo() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
        val logActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        logActions.addView(action("SALIN LOG", false) { copyAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        logActions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        logActions.addView(action("KIRIM LOG", false) { shareAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        detail.addView(logActions)
        detail.addView(action("KUMPULKAN & KIRIM DIAGNOSTIK", true) { collectAndShareDiagnostics() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        audioPanel.addView(detail)
        val lg = card(); lg.addView(text("PEMERIKSAAN AUDIO LG", 11f, accentColor, true))
        lg.addView(text("Mencari konfigurasi LG yang dapat dibaca dan menguji dukungan direct playback. Hasilnya bukan bukti jalur lagu yang sedang aktif.", 12f, MUTED, false).apply { setPadding(0, dp(7), 0, dp(8)) })
        lg.addView(action("SCAN KONFIGURASI LG", false) { scanAudioConfiguration() }, LinearLayout.LayoutParams(-1, dp(44))); audioPanel.addView(lg)
        val note = card(); note.addView(text("BATASAN PENGUKURAN", 11f, accentColor, true))
        note.addView(text("API publik Android tidak memberi bukti pasti tentang bit depth stream aktif, sample rate DAC aktual, atau bit-perfect. Rate sumber yang berbeda dari default bukan bukti resampling dengan sendirinya.", 13f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) })
        audioPanel.addView(note)
    }

    private fun makeFilePanel() {
        val info = card(); info.addView(text("INFORMASI FILE", 11f, accentColor, true))
        fileInfo = text("Belum ada file yang dipilih.", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        info.addView(fileInfo)
        info.addView(action("PILIH FILE AUDIO", false) { pickFile() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) })
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

    private fun scanMusicFolder(uri: Uri) {
        recordDiagnostic("SCAN_START uri=$uri")
        librarySummary.text = "Memindai folder dan subfolder…"; statusView.text = "Memindai pustaka musik…"
        scanProgress.visibility = View.VISIBLE; scanProgressText.visibility = View.VISIBLE
        scanProgressText.text = "Memulai pemindaian…"
        Thread {
            val found = mutableListOf<Track>()
            val count = intArrayOf(0)
            var error: String? = null
            try { DocumentFile.fromTreeUri(this, uri)?.let { scanDirectory(it, found, mutableMapOf(), 0, count) } }
            catch (e: Exception) { error = e.javaClass.simpleName + ": " + (e.message ?: "akses ditolak"); recordDiagnostic("SCAN_FAILED uri=$uri", e) }
            recordDiagnostic("SCAN_ENUMERATION_COMPLETE found=${found.size} processed=${count[0]} error=${error ?: "none"}")
            val sorted = found.sortedWith(compareBy<Track> { it.album.lowercase(Locale.ROOT) }.thenBy { it.trackNumber }.thenBy { it.title.lowercase(Locale.ROOT) })
            handler.post {
                tracks = sorted; currentIndex = -1; selectedAlbum = null; playbackQueue = emptyList(); playbackOrder = emptyList()
                try { renderLibrary() } catch (e: Throwable) {
                    recordDiagnostic("LIBRARY_RENDER_FAILED tracks=${sorted.size}", e)
                    librarySummary.text = "Daftar ditemukan (${sorted.size} lagu), tetapi tampilan gagal dibuat. Log diagnostik tersedia."
                    statusView.text = "Gagal menampilkan pustaka: " + e.javaClass.simpleName
                }
                scanProgress.visibility = View.GONE; scanProgressText.visibility = View.GONE
                librarySummary.text = when {
                    error != null -> "Gagal memindai folder: " + error
                    sorted.isEmpty() -> "Tidak ditemukan file audio. Pilih folder Music yang berisi lagu."
                    else -> sorted.size.toString() + " lagu • " + sorted.map { it.album }.distinct().size + " album terdeteksi"
                }
                statusView.text = if (sorted.isEmpty()) "Pustaka kosong. Periksa folder." else "Pustaka siap • " + sorted.size + " lagu."
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
                output.add(readTrack(file.uri, covers[key], dir.name)); count[0]++
                if (count[0] == 1 || count[0] % 5 == 0) handler.post {
                    if (scanProgress.visibility == View.VISIBLE) scanProgressText.text = "Ditemukan " + count[0] + " lagu • memindai " + (dir.name ?: "folder") + "…"
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
        var title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Audio lokal"
        var artist = "Artis tidak tersedia"; var album = folderName ?: "Album tidak diketahui"; var trackNumber = Int.MAX_VALUE
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
        if (!::albumRows.isInitialized || !::trackRows.isInitialized) return
        albumRows.removeAllViews(); trackRows.removeAllViews()
        if (tracks.isEmpty()) {
            albumRows.addView(text("Pilih folder untuk membangun pustaka.", 13f, MUTED, false))
            trackRows.addView(text("Lagu yang ditemukan akan muncul di sini.", 13f, MUTED, false))
            return
        }

        val albums = tracks.map { it.album }.distinct().sortedBy { it.lowercase(Locale.ROOT) }
        albumRows.addView(rowButton("Semua album • " + tracks.size + " lagu", selectedAlbum == null) {
            selectedAlbum = null
            visibleTrackLimit = 80
            renderLibrary()
        })
        albums.forEach { album ->
            val albumEntries = tracks.withIndex().filter { it.value.album == album }
            val albumRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = shape(if (selectedAlbum == album) LINE else BG, 12)
                isClickable = true
                isFocusable = true
                contentDescription = if (selectedAlbum == album) "Tutup album $album" else "Buka album $album"
            }
            val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(ART, 8) }
            albumRow.addView(cover, LinearLayout.LayoutParams(dp(58), dp(58)))
            val label = text((if (selectedAlbum == album) "▾ " else "▸ ") + album + "\n" + albumEntries.size + " lagu", 13f, if (selectedAlbum == album) accentColor else WHITE, true)
                .apply { setPadding(dp(12), 0, 0, 0) }
            albumRow.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            albumRow.setOnClickListener {
                selectedAlbum = if (selectedAlbum == album) null else album
                visibleTrackLimit = 80
                renderLibrary()
            }
            albumRows.addView(albumRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
            albumEntries.firstOrNull()?.let { loadArtworkInto(cover, it.value) }

            if (selectedAlbum == album) {
                albumRows.addView(action("▶  PUTAR SELURUH ALBUM", true) {
                    val queue = albumEntries.map { it.index }
                    if (queue.isNotEmpty()) {
                        playTrackAt(queue.first(), true, queue)
                        show("player")
                    }
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5); bottomMargin = dp(5) })
                albumRows.addView(action("＋  TAMBAHKAN ALBUM KE ANTRIAN", false) {
                    addAlbumToQueue(albumEntries.map { it.index }, album)
                }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(7) })
                albumEntries.take(visibleTrackLimit).forEach { entry ->
                    addLibraryTrackRow(albumRows, entry, true, albumEntries.map { it.index })
                }
                if (albumEntries.size > visibleTrackLimit) {
                    albumRows.addView(action("MUAT LAGU BERIKUTNYA (${albumEntries.size - visibleTrackLimit} tersisa)", false) {
                        visibleTrackLimit = (visibleTrackLimit + 80).coerceAtMost(albumEntries.size)
                        renderLibrary()
                    }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5); bottomMargin = dp(8) })
                }
            }
        }

        trackRows.visibility = if (selectedAlbum == null) View.VISIBLE else View.GONE
        if (selectedAlbum == null) {
            tracks.withIndex().take(visibleTrackLimit).forEach { entry ->
                addLibraryTrackRow(trackRows, entry, false, tracks.indices.toList())
            }
            if (tracks.size > visibleTrackLimit) {
                trackRows.addView(action("MUAT LAGU BERIKUTNYA (${tracks.size - visibleTrackLimit} tersisa)", false) {
                    visibleTrackLimit = (visibleTrackLimit + 80).coerceAtMost(tracks.size)
                    renderLibrary()
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
            }
        }
    }

    private fun addLibraryTrackRow(parent: LinearLayout, entry: IndexedValue<Track>, nested: Boolean, queueIndices: List<Int>) {
        val t = entry.value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (nested) 18 else 8), dp(8), dp(8), dp(8))
            background = shape(if (entry.index == currentIndex) LINE else BG, 12)
            isClickable = true
            isFocusable = true
        }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(ART, 8) }
        row.addView(cover, LinearLayout.LayoutParams(dp(54), dp(54)))
        val label = text(t.title + "\n" + t.artist + " • " + t.album, 12f, WHITE, true).apply { setPadding(dp(10), 0, 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        val addQueue = iconAction("+") { addToQueue(entry.index) }.apply { textSize = 22f; setTextColor(accentColor); contentDescription = "Tambahkan ${t.title} ke antrian" }
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
                val label = routed?.let { describe(it) } ?: "rute belum dilaporkan oleh AudioTrack"
                lastDeviceEvent = "Rute aktual AudioTrack berubah: " + label
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
        currentIndex = index
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
        titleView.text = t.title; artistView.text = t.artist; albumView.text = t.album
        elapsed.text = "0:00"; duration.text = "0:00"; seek.progress = 0; seek.max = 1
        playView.isEnabled = false; syncPlaybackIcon(false)
        statusView.text = "Memuat dengan mesin Media3: " + t.title + "…"
        showMetadata(t.uri, t); loadArtwork(t)
        try {
            val exo = createHiFiPlayer()
            player = exo
            exo.setHandleAudioBecomingNoisy(true)
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            prepared = true
                            durationMs = exo.duration.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt() ?: 0
                            seek.max = durationMs.coerceAtLeast(1); this@MainActivity.duration.text = time(durationMs)
                            playView.isEnabled = true
                            statusView.text = "Siap diputar • Media3/ExoPlayer • " + (sourceRate?.let { "%.1f kHz".format(Locale.US, it / 1000f) } ?: "sample rate sumber tidak diketahui")
                            if (pendingAutoplay) { exo.play(); syncPlaybackIcon(true); statusView.text = "Sedang diputar • " + t.title }
                            else syncPlaybackIcon(false)
                            pendingAutoplay = false
                            refreshAudioInfo(); updatePlaybackNotification(exo.isPlaying)
                        }
                        Player.STATE_ENDED -> {
                            syncPlaybackIcon(false)
                            val q = playbackOrder.indexOf(currentIndex)
                            if (q >= 0 && q + 1 < playbackOrder.size) playTrackAt(playbackOrder[q + 1], true)
                            else { statusView.text = "Album/antrean selesai."; refreshAudioInfo(); updatePlaybackNotification(false) }
                        }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (prepared) { syncPlaybackIcon(isPlaying); refreshAudioInfo(); updatePlaybackNotification(isPlaying); updateActiveRoute() }
                }
                override fun onPlayerError(error: PlaybackException) {
                    prepared = false; playView.isEnabled = true
                    statusView.text = "Media3 gagal memutar • " + error.errorCodeName + ": " + (error.message ?: "format atau decoder tidak didukung")
                    recordDiagnostic("MEDIA3_PLAYBACK_ERROR code=${error.errorCodeName}", error); refreshAudioInfo()
                }
            })
            exo.setMediaItem(MediaItem.fromUri(t.uri)); exo.prepare()
        } catch (e: Exception) {
            playView.isEnabled = true; statusView.text = "Tidak dapat membuka file: " + e.message
            recordDiagnostic("MEDIA3_INITIALIZATION_FAILED", e)
        }
        renderLibrary()
    }
    private fun playPrevious() {
        if (tracks.isEmpty()) { statusView.text = "Pilih folder atau file audio terlebih dahulu."; return }
        if (playbackOrder.isEmpty()) playbackOrder = tracks.indices.toList()
        val position = playbackOrder.indexOf(currentIndex).let { if (it < 0) 0 else it }
        val target = playbackOrder[(position - 1 + playbackOrder.size) % playbackOrder.size]
        playTrackAt(target, true)
    }
    private fun playNext() {
        if (tracks.isEmpty()) { statusView.text = "Pilih folder atau file audio terlebih dahulu."; return }
        if (playbackOrder.isEmpty()) playbackOrder = tracks.indices.toList()
        val position = playbackOrder.indexOf(currentIndex).let { if (it < 0) -1 else it }
        val target = playbackOrder[(position + 1).mod(playbackOrder.size)]
        playTrackAt(target, true)
    }
    private fun syncPlaybackIcon(isPlaying: Boolean? = null) {
        if (!::playView.isInitialized) return
        val playing = isPlaying ?: try { player?.isPlaying == true } catch (_: Exception) { false }
        playView.setImageResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
        playView.contentDescription = if (playing) "Jeda" else "Putar"
        playView.imageTintList = ColorStateList.valueOf(accentColor)
    }

    private fun togglePlayback() {
        val p = player
        if (p == null || !prepared) { statusView.text = "Pilih lagu dari pustaka terlebih dahulu."; return }
        try {
            if (p.isPlaying) { p.pause(); syncPlaybackIcon(false); statusView.text = "Dijeda" }
            else { p.play(); syncPlaybackIcon(true); statusView.text = "Sedang diputar" }
            refreshAudioInfo()
            updatePlaybackNotification(try { p.isPlaying } catch (_: Exception) { false })
        } catch (e: Exception) { statusView.text = "Pemutaran gagal: " + e.message }
    }

    private fun showMetadata(uri: Uri, t: Track) {
        // SAF/MediaMetadataRetriever/MediaExtractor may block on slow storage. Never run them on the UI thread,
        // otherwise the artwork bitmap cannot be displayed even when its background task has already finished.
        metadataExecutor.execute {
            val r = MediaMetadataRetriever()
            var fileDuration = "Tidak tersedia"; var mime = "Tidak tersedia"; var bitrate = "Tidak tersedia"
            try {
                r.setDataSource(this@MainActivity, uri)
                fileDuration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: fileDuration
                mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mime
                bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: bitrate
            } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) { } }
            var rate = "Tidak tersedia"; var channels = "Tidak tersedia"; var trackMime = mime
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
            val infoText = "Judul: " + t.title + "\nArtis: " + t.artist + "\nAlbum: " + t.album + "\nDurasi: " + fileDuration + " ms\nCodec/MIME: " + trackMime + "\nSample rate sumber: " + rate + " Hz\nKanal: " + channels + "\nBitrate: " + bitrate + " bit/s (jika dilaporkan)\nURI: " + uri
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
            seek.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        if (::playView.isInitialized) {
            playView.imageTintList = ColorStateList.valueOf(accentColor)
            playView.background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), shape(CARD, 16), null)
            syncPlaybackIcon()
        }
        if (::scanProgress.isInitialized) scanProgress.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
        if (::scanProgressText.isInitialized) scanProgressText.setTextColor(accentColor)
        if (::titleView.isInitialized) titleView.setTextColor(accentColor)
        if (::audioInfoInline.isInitialized) audioInfoInline.setTextColor(MUTED)
        if (::statusView.isInitialized) statusView.setTextColor(accentColor)
        fun recolor(view: View) {
            if (view is TextView && view.tag == "g6_dynamic_accent_text") view.setTextColor(accentColor)
            if (view is ViewGroup) for (i in 0 until view.childCount) recolor(view.getChildAt(i))
        }
        recolor(window.decorView)
        if (::albumRows.isInitialized && ::trackRows.isInitialized) renderLibrary()
    }

    private fun decodeArtwork(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
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
            while (artworkBytesCache.size > 24) artworkBytesCache.remove(artworkBytesCache.keys.iterator().next())
        }
        return bytes
    }

    private fun loadArtworkInto(target: ImageView, track: Track) {
        artworkExecutor.execute {
            val bitmap = readArtworkBytes(track)?.let { decodeArtwork(it) }
            handler.post {
                if (bitmap != null && !isFinishing && target.isAttachedToWindow) { target.setImageBitmap(bitmap) }
                else bitmap?.recycle()
            }
        }
    }

    private fun loadArtwork(t: Track) {
        val token = ++artToken
        artImage.visibility = View.GONE; artImage.setImageDrawable(null); artPlaceholder.visibility = View.VISIBLE
        // Dedicated executor means library thumbnails can never block Now Playing artwork.
        playbackArtworkExecutor.execute {
            val bitmap = readArtworkBytes(t)?.let { decodeArtwork(it) }
            handler.post {
                if (token != artToken) { bitmap?.recycle(); return@post }
                if (bitmap != null && !isFinishing) {
                    artImage.setImageBitmap(bitmap); artImage.visibility = View.VISIBLE; artPlaceholder.visibility = View.GONE; applyArtworkAccent(bitmap)
                    if (prepared) updatePlaybackNotification(try { player?.isPlaying == true } catch (_: Exception) { false })
                } else { artImage.visibility = View.GONE; artPlaceholder.visibility = View.VISIBLE; artPlaceholder.text = "G6\nNO COVER ART" }
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
            val message = e.javaClass.simpleName + ": " + (e.message ?: "tanpa detail")
            if (lastRouteApiError != message) recordDiagnostic("AUDIOTRACK_ROUTED_DEVICE_QUERY_FAILED " + message, e)
            lastRouteApiError = message
            null
        }
        val legacyHints = buildList {
            try { if (audioManager.isWiredHeadsetOn) add("isWiredHeadsetOn=true (indikasi jack terhubung, bukan rute aktif)") } catch (_: Exception) { }
            try { if (audioManager.isBluetoothA2dpOn) add("isBluetoothA2dpOn=true (indikasi Bluetooth A2DP, bukan rute aktif)") } catch (_: Exception) { }
            try { if (audioManager.isBluetoothScoOn) add("isBluetoothScoOn=true (indikasi Bluetooth SCO, bukan rute aktif)") } catch (_: Exception) { }
            try { if (audioManager.isSpeakerphoneOn) add("isSpeakerphoneOn=true (flag speaker telepon, bukan bukti rute media)") } catch (_: Exception) { }
        }.ifEmpty { listOf("Tidak ada flag legacy aktif; ini tidak membuktikan tidak ada jack/headphone.") }.joinToString("; ")
        if (legacyHints != lastLegacyRouteHints) {
            recordDiagnostic("LEGACY_ROUTE_HINTS " + legacyHints)
            lastLegacyRouteHints = legacyHints
        }
        val connectedWired = devices.filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        val connectedBluetooth = devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 28 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
        val connectionSummary = buildString {
            append("Perangkat TERHUBUNG menurut AudioManager: ")
            append(if (devices.isEmpty()) "tidak ada perangkat output yang dilaporkan" else devices.joinToString { describe(it) })
            append("\nIndikasi legacy AudioManager: ").append(legacyHints)
            append("\nHeadphone/jack terdaftar: ").append(if (connectedWired.isEmpty()) "tidak" else connectedWired.joinToString { describe(it) })
            append("\nBluetooth terdaftar: ").append(if (connectedBluetooth.isEmpty()) "tidak" else connectedBluetooth.joinToString { describe(it) })
            append("\nPENTING: daftar/flag di atas menunjukkan koneksi atau petunjuk legacy, bukan rute aktif stream.")
        }
        val route = when {
            active != null -> deviceName(active) + " • " + deviceType(active.type) + " (type=" + active.type + ", id=" + active.id + ")"
            player == null -> "Belum ada stream"
            !isPlayingNow -> "Tidak sedang memutar; rute aktif tidak sedang diukur"
            outputTrack == null -> "AudioTrack Media3 belum tersedia untuk diperiksa"
            else -> "AudioTrack aktif, tetapi Android belum melaporkan perangkat rute"
        }
        val playbackState = when {
            player == null -> "tidak ada player"
            !prepared -> "player belum siap"
            isPlayingNow -> "memutar"
            else -> "jeda / tidak memutar"
        }
        val audioMode = try {
            when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "mode=" + audioManager.mode
            }
        } catch (_: Exception) { "tidak tersedia" }
        val sessionId = try { if (outputTrackPlaying) outputTrack?.audioSessionId?.toString() ?: "tidak tersedia" else "tidak aktif" } catch (_: Exception) { "tidak tersedia" }
        val outputRate = try { if (outputTrackPlaying) outputTrack?.sampleRate else null } catch (_: Exception) { null }
        val outputEncoding = try { if (outputTrackPlaying) outputTrack?.audioFormat?.let { encodingName(it) } else null } catch (_: Exception) { null }
        val outputChannels = try { if (outputTrackPlaying) outputTrack?.channelCount else null } catch (_: Exception) { null }
        val outputBufferFrames = try { if (outputTrackPlaying) outputTrack?.bufferSizeInFrames else null } catch (_: Exception) { null }
        // Buffer capacity converted to time; this is not measured end-to-end latency.
        val outputBufferCapacityMs = if (outputTrackPlaying && outputBufferFrames != null && outputRate != null && outputRate > 0)
            outputBufferFrames * 1000.0 / outputRate else null
        val outputPerformance = try {
            if (outputTrackPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) outputTrack?.performanceMode?.let {
                when (it) { AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "LOW_LATENCY"; AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "POWER_SAVING"; else -> "NONE/UNKNOWN" }
            } else null
        } catch (_: Exception) { null }
        val activeCapabilities = if (active == null) "Kapabilitas perangkat aktif: belum dapat dikaitkan dengan rute aktual." else describeCapabilities(active)
        val defaultRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "Tidak dilaporkan"
        val buffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "Tidak dilaporkan"
        val wired = devices.filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        val wiredText = if (wired.isEmpty()) "Jack kabel: tidak terdeteksi" else "Jack kabel terdeteksi: " + wired.joinToString { describe(it) }
        val rateNote = when {
            sourceRate == null -> "Sample rate sumber belum terbaca."
            outputTrackPlaying && outputRate != null && outputRate == sourceRate ->
                "Sample rate sumber sama dengan konfigurasi AudioTrack (${outputRate} Hz). Ini bukti konfigurasi track, bukan bukti sample rate fisik DAC atau bit-perfect."
            outputTrackPlaying && outputRate != null && outputRate != sourceRate ->
                "Sample rate sumber (${sourceRate} Hz) berbeda dari konfigurasi AudioTrack (${outputRate} Hz); ada konversi format/rate di pipeline output yang perlu diperiksa. Ini belum mengungkap apa yang dilakukan HAL/DSP/DAC."
            sourceRate.toString() == defaultRate ->
                "Sample rate sumber sama dengan properti default sistem; ini tidak membuktikan format stream atau DAC."
            else ->
                "Sample rate sumber berbeda dari properti default sistem, tetapi rate AudioTrack belum terbaca; belum cukup untuk menyimpulkan resampling."
        }
        routeSummary.text = route
        routeInfo.text = "Status: " + (if (isPlayingNow) "SEDANG MEMUTAR" else "tidak memutar") +
            "\nRute aktif aktual: " + route + "\n" + wiredText +
            "\nStatus stream: " + playbackState + "\nAudio session ID: " + sessionId +
            "\nAudioTrack output aktif (konfigurasi track, bukan pengukuran DAC): " +
                (if (outputTrackPlaying) (outputRate?.toString() ?: "?") + " Hz / " + (outputEncoding ?: "encoding tidak terbaca") +
                    " / " + (outputChannels?.toString() ?: "?") + " kanal / buffer " + (outputBufferFrames?.toString() ?: "?") +
                    " frame (~" + (outputBufferCapacityMs?.let { String.format(Locale.US, "%.1f ms kapasitas", it) } ?: "durasi tidak tersedia") +
                    "; bukan latensi terukur) / mode " + (outputPerformance ?: "tidak tersedia") else "tidak sedang aktif") +
            "\nAudioManager mode: " + audioMode +
            "\nStatus API rute: " + (when { active != null -> "AudioTrack melaporkan rute aktual"; !outputTrackPlaying -> "AudioTrack tidak sedang dalam status PLAYING"; lastRouteApiError != null -> "API rute gagal: " + lastRouteApiError; else -> "getRoutedDevice() mengembalikan null; rute aktif tidak dapat dipastikan" }) +
            "\nStatus backend LG Quad DAC: TIDAK TERKONFIRMASI oleh API publik (nama backend HAL tidak diekspos ke aplikasi biasa)." +
            "\n" + connectionSummary +
            "\nSample rate sumber: " + (sourceRate?.toString() ?: "Belum terbaca") + " Hz" +
            "\nSample rate default Android (properti low-latency/native, bukan pengukuran stream ini): " + defaultRate + " Hz\nBuffer default sistem: " + buffer + " frame" +
            "\n" + activeCapabilities + "\n\nPerangkat output yang dilaporkan Android (belum tentu aktif):\n" +
            (if (devices.isEmpty()) "Tidak ada perangkat dilaporkan." else devices.joinToString("\n") { "• " + describe(it) }) +
            "\nPeristiwa terakhir: " + lastDeviceEvent + "\n\n" + rateNote +
            "\n\nAudioTrack menunjukkan format yang dikonfigurasi untuk stream aplikasi. Audio HAL/DSP/DAC masih dapat melakukan konversi; ini bukan bukti bit-perfect atau sample rate fisik DAC."
        if (::audioInfoInline.isInitialized) {
            val fileDetails = if (::fileInfo.isInitialized) fileInfo.text.toString() else "Pilih lagu untuk membaca metadata file."
            val track = tracks.getOrNull(currentIndex)
            val extension = track?.uri?.lastPathSegment?.substringAfterLast('.', "")?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "AUDIO"
            val sourceKhz = sourceRate?.let { String.format(Locale.US, "%.1f", it / 1000f) } ?: "?"
            val outputTrack = activeAudioTrack
            val playingOutput = try { player?.isPlaying == true && outputTrack?.playState == AudioTrack.PLAYSTATE_PLAYING } catch (_: Exception) { false }
            val outputRate = try { if (playingOutput) outputTrack?.sampleRate else null } catch (_: Exception) { null }
            val outputKhz = outputRate?.let { String.format(Locale.US, "%.1f", it / 1000f) } ?: "?"
            val routeLabel = try { if (playingOutput) outputTrack?.routedDevice?.let { deviceType(it.type) } ?: "rute belum dilaporkan" else "belum aktif" } catch (_: Exception) { "rute belum dilaporkan" }
            val bitrateLabel = sourceBitrate?.let { " • ${it / 1000} kbps" } ?: ""
            val bitPerfectText = when {
                playingOutput && sourceRate != null && outputRate != null && sourceRate != outputRate -> "● RESAMPLE / BUKAN BIT-PERFECT"
                else -> "● BELUM TERKONFIRMASI BIT-PERFECT"
            }
            val compact = "$extension$bitrateLabel • sumber $sourceKhz kHz → output $outputKhz kHz • $routeLabel   $bitPerfectText"
            audioInfoInline.text = compact
            val start = compact.indexOf(bitPerfectText)
            if (start >= 0) {
                val colored = android.text.SpannableString(compact)
                // Android's public APIs cannot confirm bit-perfect delivery; orange means unknown or conversion indicated.
                colored.setSpan(android.text.style.ForegroundColorSpan(0xFFFFA43A.toInt()), start, compact.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                audioInfoInline.text = colored
            }
        }
    }

    private fun copyText(value: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("G6 HiFi Player log", value))
            Toast.makeText(this, "Log disalin ke clipboard.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Tidak dapat menyalin log: " + e.message, Toast.LENGTH_LONG).show() }
    }
    private fun shareText(value: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "G6 HiFi Player — log audio"); putExtra(Intent.EXTRA_TEXT, value)
            }, "Kirim log audio"))
        } catch (e: Exception) { Toast.makeText(this, "Tidak dapat membuka menu berbagi.", Toast.LENGTH_SHORT).show() }
    }
    private fun copyAudioLog() {
        val route = if (::routeInfo.isInitialized) routeInfo.text.toString() else "Informasi route belum tersedia."
        val file = if (::fileInfo.isInitialized) fileInfo.text.toString() else "Informasi file belum tersedia."
        val events = synchronized(diagnosticEvents) { diagnosticEvents.joinToString("\n\n") }
        copyText("G6 HiFi Player — DIAGNOSTIK AUDIO / SCAN\nWaktu: ${System.currentTimeMillis()}\nPerangkat: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\nVersi aplikasi: ${appVersionLabel()}\n\nROUTE:\n$route\n\nFILE:\n$file\n\nKONFIGURASI LG:\n$lastAudioConfigLog\n\nEVENT TERAKHIR:\n$events")
    }
    private fun shareAudioLog() {
        val route = if (::routeInfo.isInitialized) routeInfo.text.toString() else "Informasi route belum tersedia."
        val file = if (::fileInfo.isInitialized) fileInfo.text.toString() else "Informasi file belum tersedia."
        val events = synchronized(diagnosticEvents) { diagnosticEvents.joinToString("\n\n") }
        shareText("G6 HiFi Player — DIAGNOSTIK AUDIO / SCAN\nWaktu: ${System.currentTimeMillis()}\nPerangkat: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\nVersi aplikasi: ${appVersionLabel()}\n\nROUTE:\n$route\n\nFILE:\n$file\n\nKONFIGURASI LG:\n$lastAudioConfigLog\n\nEVENT TERAKHIR:\n$events")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Pemutaran musik", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kontrol dan informasi lagu yang sedang diputar"
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
                    if (activity.player?.isPlaying != true) activity.togglePlayback()
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
            isActive = true
        }
    }
    private fun activityDuration(p: ExoPlayer): Int = try { p.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } catch (_: Exception) { 0 }

    private fun updatePlaybackNotification(isPlaying: Boolean) {
        if (!prepared || currentIndex !in tracks.indices) return
        val track = tracks[currentIndex]
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        fun activityIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("notification_action", action)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(this, requestCode, intent, flags)
        }
        val contentIntent = PendingIntent.getActivity(this, 6100, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, flags)
        recordDiagnostic("NOTIFICATION_UPDATE playing=$isPlaying title=${track.title} sourceRate=${sourceRate ?: -1} engine=Media3/ExoPlayer trackRoute=queried-separately backend=not-exposed-by-public-api")
        val session = mediaSession
        session?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.toLong())
            .build())
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
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Sebelumnya", activityIntent("previous", 6101)).build())
            .addAction(Notification.Action.Builder(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Jeda" else "Putar", activityIntent("toggle", 6102)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Berikutnya", activityIntent("next", 6103)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Tutup notifikasi", activityIntent("dismiss", 6104)).build())
        val cover = (artImage.drawable as? BitmapDrawable)?.bitmap
        if (cover != null) builder.setLargeIcon(cover)
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, builder.build())
    }
    private fun handleNotificationAction(action: String) {
        when (action) {
            "previous" -> playPrevious()
            "next" -> playNext()
            "toggle" -> togglePlayback()
            "dismiss" -> { try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) { } }
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
                    report.append("\n").append(path).append("\nStatus: tidak ada pada jalur ini.\n")
                    continue
                }
                if (!file.isFile || !file.canRead()) {
                    report.append("\n").append(path).append("\nStatus: ada tetapi tidak dapat dibaca oleh aplikasi biasa.\n")
                    continue
                }
                val matches = file.useLines { lines ->
                    lines.filter { line -> keys.any { key -> line.lowercase(Locale.ROOT).contains(key) } }
                        .take(24).toList()
                }
                readable++
                report.append("\n").append(path).append('\n').append("Status: terbaca.\n")
                if (matches.isEmpty()) report.append("Tidak ada baris yang cocok dengan kata kunci audio yang dicari.\n")
                else matches.forEach { report.append(it.trim()).append('\n') }
            } catch (e: Exception) {
                report.append("\n").append(path).append("\nStatus: gagal dibaca (").append(e.javaClass.simpleName).append(": ").append(e.message ?: "tanpa detail").append(").\n")
            }
        }
        report.append("\nDirect playback PCM stereo (dukungan sistem, bukan stream aktif):\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            for (rate in listOf(44100, 48000, 96000, 192000)) {
                val supported = try {
                    val f = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                    AudioTrack.isDirectPlaybackSupported(f, attrs)
                } catch (_: Exception) { false }
                report.append(rate).append(" Hz / PCM 16-bit: ").append(if (supported) "didukung menurut API" else "tidak dilaporkan didukung").append('\n')
            }
        } else report.append("API direct-playback tidak tersedia pada Android versi ini.\n")
        report.append("\nFile konfigurasi yang dapat dibaca: ").append(readable).append(" dari ").append(paths.size)
        report.append("\nKonfigurasi adalah petunjuk kemampuan yang dinyatakan sistem, bukan bukti Media3 sedang memakai DIRECT_HD/direct_pcm_24 atau DAC menerima sample rate tertentu.")
        report.append("\n\nINTERPRETASI KHUSUS LG G6: SND_DEVICE_LGE_OUT_HEADPHONES_HIFI_DAC bersama backend headphones tert-mi2s-headphones dan SEC_MI2S_RX menunjukkan konfigurasi kandidat jalur Quad DAC. File konfigurasi hanya membuktikan jalur didefinisikan, bukan bahwa stream ini sedang menggunakannya.")
        report.append("\nSTATUS BACKEND LG QUAD DAC: tidak terkonfirmasi melalui API publik. Preferensi perangkat kabel hanya meminta AudioPolicy memilih jack 3,5 mm; ini tidak memaksa AUDIO_OUTPUT_FLAG_DIRECT dan tidak membuktikan backend HAL/DAC.")
        report.append("\n\nUntuk memeriksa backend saat lagu sedang diputar melalui jack 3,5 mm, jalankan dari komputer dengan USB debugging aktif:")
        report.append("\nadb shell dumpsys media.audio_flinger")
        report.append("\nadb shell dumpsys media.audio_policy")
        report.append("\nKirim kedua keluaran itu bersama log aplikasi. Jika keluaran sistem tidak memuat nama backend atau output thread yang relevan, verifikasi fisik DAC mungkin memerlukan akses vendor/root; jangan simpulkan DAC aktif hanya dari rate AudioTrack.")
        lastAudioConfigLog = report.toString()
        if (showDialog) {
            AlertDialog.Builder(this).setTitle("Hasil scan audio LG").setMessage(lastAudioConfigLog)
                .setNeutralButton("SALIN LOG") { _, _ -> copyText(lastAudioConfigLog) }
                .setNegativeButton("KIRIM LOG") { _, _ -> shareText(lastAudioConfigLog) }
                .setPositiveButton("TUTUP", null).show()
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
            val message = e.javaClass.simpleName + ": " + (e.message ?: "tanpa detail")
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
                player == null -> "Belum ada stream"
                !playing -> "Tidak sedang memutar"
                active != null -> deviceName(active) + " • " + deviceType(active.type) + " (type=" + active.type + ", id=" + active.id + ")"
                else -> "AudioTrack aktif; perangkat rute belum dilaporkan"
            }
            if (::routeSummary.isInitialized) routeSummary.text = label
            if (previous != null && previous != routeKey) {
                lastDeviceEvent = "Rute AudioTrack: " + label
                recordDiagnostic("OUTPUT_ROUTE_CHANGED previous=" + previous + " current=" + routeKey + " routedDevice=" + (active?.let { describe(it) } ?: "null") + " playing=" + playing)
            }
            if (::routeInfo.isInitialized) refreshAudioInfo()
        }
    }
    private fun show(which: String) {
        playerPanel.visibility = if (which == "player") View.VISIBLE else View.GONE
        libraryPanel.visibility = if (which == "library") View.VISIBLE else View.GONE
        queuePanel.visibility = if (which == "queue") View.VISIBLE else View.GONE
        settingsPanel.visibility = if (which == "settings" || which == "audio" || which == "file") View.VISIBLE else View.GONE
        navState(navPlayer, which == "player"); navState(navLibrary, which == "library")
        navState(navAudio, which == "queue")
        navState(navFile, which == "settings" || which == "audio" || which == "file")
        if (which == "queue") renderQueueRows()
    }
    private fun navButton(label: String, click: () -> Unit) = text(label, 10f, MUTED, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(3), dp(4), dp(3), dp(4))
        background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), shape(Color.TRANSPARENT, 18), null)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun navState(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) accentColor else MUTED)
        view.background = RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x30000000), shape(if (selected) (accentColor and 0x20FFFFFF) else Color.TRANSPARENT, 18), null)
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
        Toast.makeText(this, "Album ditambahkan ke antrian: ${tracks[index].album}", Toast.LENGTH_SHORT).show()
    }

    private fun addAlbumToQueue(indices: List<Int>, album: String) {
        val valid = indices.filter { it in tracks.indices }.distinct()
        if (valid.isEmpty()) return
        playbackQueue = (playbackQueue + valid).distinct()
        renderQueueRows()
        Toast.makeText(this, "Album ditambahkan ke antrian: $album", Toast.LENGTH_SHORT).show()
    }

    private fun renderQueueRows() {
        if (!::queueRows.isInitialized) return
        queueRows.removeAllViews()
        val queue = playbackQueue.filter { it in tracks.indices }
        if (queue.isEmpty()) { queueRows.addView(text("Antrian masih kosong. Putar lagu atau tambahkan album dari tab ALBUM.", 13f, MUTED, false)); return }
        queue.forEachIndexed { position, trackIndex ->
            val track = tracks[trackIndex]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(5), dp(2), dp(5))
                background = if (trackIndex == currentIndex) shape((accentColor and 0x20FFFFFF), 10) else RippleDrawable(ColorStateList.valueOf((accentColor and 0x00FFFFFF) or 0x25000000), shape(Color.TRANSPARENT, 10), null)
            }
            val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(ART, 6) }
            row.addView(cover, LinearLayout.LayoutParams(dp(42), dp(42)))
            val label = text("${position + 1}. ${track.title}\n${track.artist} • ${track.album}", 12f, WHITE, true).apply {
                setPadding(dp(9), 0, dp(6), 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val remove = iconAction("×") {
                if (trackIndex == currentIndex) Toast.makeText(this, "Lagu yang sedang diputar tidak dapat dihapus dari antrian aktif.", Toast.LENGTH_SHORT).show()
                else { playbackQueue = playbackQueue.filter { it != trackIndex }; playbackOrder = playbackQueue; renderQueueRows() }
            }.apply { textSize = 24f; setTextColor(MUTED) }
            row.addView(remove, LinearLayout.LayoutParams(dp(42), dp(42)))
            row.setOnClickListener { playTrackAt(trackIndex, true, playbackQueue); show("player") }
            queueRows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            loadArtworkInto(cover, track)
        }
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
        val rates = try { d.sampleRates.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "tidak dilaporkan" } catch (_: Exception) { "tidak tersedia" }
        val channels = try { d.channelCounts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "tidak dilaporkan" } catch (_: Exception) { "tidak tersedia" }
        val encodings = try { d.encodings.takeIf { it.isNotEmpty() }?.joinToString(", ") { encodingName(it) } ?: "tidak dilaporkan" } catch (_: Exception) { "tidak tersedia" }
        return "Kapabilitas yang dilaporkan untuk perangkat aktif (bukan bukti format stream aktif):\nSample rates: " + rates + " Hz\nJumlah kanal: " + channels + "\nEncoding: " + encodings
    }
    private fun encodingName(encoding: Int) = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit packed"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        else -> "encoding=" + encoding
    }
    private fun deviceName(d: AudioDeviceInfo) = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Perangkat audio"
    private fun deviceType(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker internal"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headset berkabel"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphone berkabel"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "headset USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        else -> "perangkat lain"
    }
    private fun time(ms: Int): String {
        val s = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    override fun onDestroy() {
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
        private var activeInstance: MainActivity? = null
        @JvmStatic fun notificationAction(action: String) { activeInstance?.handleNotificationAction(action) }
        private const val REQUEST_FOLDER = 1002
        private const val DEFAULT_ACCENT = 0xFF6662C6.toInt()
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "ape", "dsf", "dff", "wma", "3gp", "amr")
    }
}
