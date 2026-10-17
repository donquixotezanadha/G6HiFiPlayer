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
    private data class Track(val uri: Uri, val title: String, val artist: String, val album: String, val coverUri: Uri?)
    private var player: ExoPlayer? = null
    @Volatile private var activeAudioTrack: AudioTrack? = null
    private var audioTrackRoutingListener: AudioRouting.OnRoutingChangedListener? = null
    private var mediaSession: MediaSession? = null
    private var sourceRate: Int? = null
    private var durationMs = 0
    private var prepared = false
    private var seeking = false
    private var pendingAutoplay = false
    private var tracks: List<Track> = emptyList()
    private var currentIndex = -1
    // Queue stores indices into the scanned library; album playback stays inside that album.
    private var playbackQueue: List<Int> = emptyList()
    private var selectedAlbum: String? = null
    private var artToken = 0
    private var accentColor = DEFAULT_ACCENT
    private var visibleTrackLimit = 80
    private val artworkExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var lastDeviceEvent = "Belum ada perubahan perangkat."
    private var lastActiveRouteKey: String? = null
    private var lastAudioConfigLog = "Belum ada hasil scan konfigurasi audio."
    private lateinit var scanProgress: ProgressBar
    private lateinit var scanProgressText: TextView
    private lateinit var audioInfoToggle: TextView
    private lateinit var audioInfoInline: TextView
    private lateinit var playerPanel: LinearLayout
    private lateinit var libraryPanel: LinearLayout
    private lateinit var audioPanel: LinearLayout
    private lateinit var filePanel: LinearLayout
    private lateinit var navPlayer: TextView
    private lateinit var navLibrary: TextView
    private lateinit var navAudio: TextView
    private lateinit var navFile: TextView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var playView: TextView
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
            lastDeviceEvent = "Ditambahkan: " + addedDevices.joinToString { describe(it) }; refreshAudioInfo()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            lastDeviceEvent = "Dilepas: " + removedDevices.joinToString { describe(it) }; refreshAudioInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG; window.navigationBarColor = BG
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
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(10)) }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(text("G6", 28f, WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(text("HI-FI • LOCAL LIBRARY", 10f, BG, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(9), dp(12), dp(9)); background = shape(accentColor, 20)
        })
        header.addView(brand)
        header.addView(text("MUSIC PLAYER / BUILD 0.10.0", 11f, MUTED, true).apply { setPadding(0, dp(5), 0, 0) })
        root.addView(header)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(10)) }
        navPlayer = navButton("PLAYER") { show("player") }
        navLibrary = navButton("PUSTAKA") { show("library") }
        navAudio = navButton("AUDIO") { show("audio") }
        navFile = navButton("FILE") { show("file") }
        listOf(navPlayer, navLibrary, navAudio, navFile).forEach { nav.addView(it, LinearLayout.LayoutParams(0, dp(43), 1f)) }
        root.addView(nav)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(3), dp(16), dp(24)) }
        playerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        libraryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        audioPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        makePlayerPanel(); makeLibraryPanel(); makeAudioPanel(); makeFilePanel()
        listOf(playerPanel, libraryPanel, audioPanel, filePanel).forEach { content.addView(it) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); show("player"); renderLibrary()
    }

    private fun makePlayerPanel() {
        val hero = card(); hero.addView(text("NOW PLAYING", 11f, accentColor, true))
        val frame = FrameLayout(this).apply { background = shape(ART, 22); clipToOutline = true }
        artImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        artPlaceholder = text("G6\nQUAD DAC / HI-RES", 24f, MUTED, true).apply { gravity = Gravity.CENTER }
        frame.addView(artImage, FrameLayout.LayoutParams(-1, -1)); frame.addView(artPlaceholder, FrameLayout.LayoutParams(-1, -1))
        hero.addView(frame, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(13); bottomMargin = dp(8) })
        audioInfoToggle = action("ⓘ  INFO AUDIO  ·  KETUK UNTUK MELIHAT", false) {
            audioInfoInline.visibility = if (audioInfoInline.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            audioInfoToggle.text = if (audioInfoInline.visibility == View.VISIBLE) "ⓘ  SEMBUNYIKAN INFO AUDIO" else "ⓘ  INFO AUDIO  ·  KETUK UNTUK MELIHAT"
            refreshAudioInfo()
        }
        hero.addView(audioInfoToggle, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(8) })
        audioInfoInline = text("Informasi output akan muncul di sini.", 12f, MUTED, false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = shape(BG, 12); visibility = View.GONE; setTextIsSelectable(true)
        }
        hero.addView(audioInfoInline, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        titleView = text("Belum ada lagu dipilih", 22f, WHITE, true).apply { setPadding(0, dp(5), 0, dp(3)) }
        artistView = text("Pilih folder musik atau satu file", 14f, MUTED, false)
        albumView = text("", 12f, MUTED, false).apply { setPadding(0, dp(4), 0, dp(10)) }
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
        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(3)) }
        transport.addView(action("|◀", false) { playPrevious() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        transport.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        playView = action("▶", true) { togglePlayback() }
        transport.addView(playView, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        transport.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        transport.addView(action("▶|", false) { playNext() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        hero.addView(transport)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        buttons.addView(action("PILIH FILE", false) { pickFile() }, LinearLayout.LayoutParams(0, dp(43), 1f))
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        buttons.addView(action("SCAN FOLDER", false) { pickFolder() }, LinearLayout.LayoutParams(0, dp(43), 1f))
        hero.addView(buttons); playerPanel.addView(hero)
        val state = card(); state.addView(text("STATUS PEMUTARAN", 11f, MUTED, true))
        statusView = text("Pilih folder musik atau file audio untuk memulai.", 14f, WHITE, false).apply { setPadding(0, dp(8), 0, 0) }
        state.addView(statusView); playerPanel.addView(state)
    }

    private fun makeLibraryPanel() {
        val head = card(); head.addView(text("PUSTAKA MUSIK", 16f, WHITE, true))
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

    private fun makeAudioPanel() {
        val summary = card(); summary.addView(text("OUTPUT AKTIF", 11f, accentColor, true))
        routeSummary = text("Memeriksa…", 22f, WHITE, true).apply { setPadding(0, dp(8), 0, dp(5)) }
        summary.addView(routeSummary); summary.addView(text("Media3 tidak melaporkan rute output aktual melalui Player API; perangkat terdeteksi belum tentu rute aktif.", 12f, MUTED, false)); audioPanel.addView(summary)
        val detail = card(); detail.addView(text("DIAGNOSTIK ROUTE", 11f, MUTED, true))
        routeInfo = text("", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        detail.addView(routeInfo)
        detail.addView(action("PERBARUI INFORMASI", false) { refreshAudioInfo() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
        val logActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        logActions.addView(action("SALIN LOG", false) { copyAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        logActions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        logActions.addView(action("KIRIM LOG", false) { shareAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        detail.addView(logActions); audioPanel.addView(detail)
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
            tracks = listOf(readTrack(uri, null, null)); currentIndex = 0; selectedAlbum = null; playbackQueue = listOf(0); playTrackAt(0, false, playbackQueue)
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
            val sorted = found.sortedWith(compareBy<Track> { it.album.lowercase(Locale.ROOT) }.thenBy { it.title.lowercase(Locale.ROOT) })
            handler.post {
                tracks = sorted; currentIndex = -1; selectedAlbum = null; playbackQueue = tracks.indices.toList()
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
        var artist = "Artis tidak tersedia"; var album = folderName ?: "Album tidak diketahui"
        try {
            r.setDataSource(this, uri)
            fun meta(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: title
            artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: artist
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: album
        } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) { } }
        return Track(uri, title, artist, album, coverUri)
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
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5); bottomMargin = dp(7) })
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
        val label = text(t.title + "\n" + t.artist + " • " + t.album, 12f, WHITE, true).apply { setPadding(dp(10), 0, 0, 0) }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { playTrackAt(entry.index, true, queueIndices); show("player") }
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
        // Prefer float output for high-resolution PCM when supported. Vendor HAL may still convert it.
        renderersFactory.setEnableAudioFloatOutput(true)
        return ExoPlayer.Builder(this, renderersFactory).build()
    }

    private fun attachActiveAudioTrack(track: AudioTrack) {
        val previous = activeAudioTrack
        val previousListener = audioTrackRoutingListener
        if (previous != null && previousListener != null) {
            try { previous.removeOnRoutingChangedListener(previousListener) } catch (_: Exception) { }
        }
        activeAudioTrack = track
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
        if (queue != null) playbackQueue = queue.filter { it in tracks.indices }.distinct()
        if (playbackQueue.isEmpty()) playbackQueue = tracks.indices.toList()
        if (index !in playbackQueue) playbackQueue = tracks.indices.toList()
        currentIndex = index
        val t = tracks[index]
        pendingAutoplay = autoplay
        prepared = false
        sourceRate = null
        durationMs = 0
        clearActiveAudioTrack()
        try { player?.release() } catch (_: Exception) { }
        player = null
        lastActiveRouteKey = null
        titleView.text = t.title; artistView.text = t.artist; albumView.text = t.album
        elapsed.text = "0:00"; duration.text = "0:00"; seek.progress = 0; seek.max = 1
        playView.isEnabled = false; playView.text = "▶"
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
                            if (pendingAutoplay) { exo.play(); playView.text = "Ⅱ"; statusView.text = "Sedang diputar • " + t.title }
                            pendingAutoplay = false
                            refreshAudioInfo(); updatePlaybackNotification(exo.isPlaying)
                        }
                        Player.STATE_ENDED -> {
                            playView.text = "▶"
                            val q = playbackQueue.indexOf(currentIndex)
                            if (q >= 0 && q + 1 < playbackQueue.size) playTrackAt(playbackQueue[q + 1], true)
                            else { statusView.text = "Album/antrean selesai."; refreshAudioInfo(); updatePlaybackNotification(false) }
                        }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (prepared) { playView.text = if (isPlaying) "Ⅱ" else "▶"; refreshAudioInfo(); updatePlaybackNotification(isPlaying); updateActiveRoute() }
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
        if (playbackQueue.isEmpty()) playbackQueue = tracks.indices.toList()
        val position = playbackQueue.indexOf(currentIndex).let { if (it < 0) 0 else it }
        val target = playbackQueue[(position - 1 + playbackQueue.size) % playbackQueue.size]
        playTrackAt(target, true)
    }
    private fun playNext() {
        if (tracks.isEmpty()) { statusView.text = "Pilih folder atau file audio terlebih dahulu."; return }
        if (playbackQueue.isEmpty()) playbackQueue = tracks.indices.toList()
        val position = playbackQueue.indexOf(currentIndex).let { if (it < 0) -1 else it }
        val target = playbackQueue[(position + 1).mod(playbackQueue.size)]
        playTrackAt(target, true)
    }
    private fun togglePlayback() {
        val p = player
        if (p == null || !prepared) { statusView.text = "Pilih lagu dari pustaka terlebih dahulu."; return }
        try {
            if (p.isPlaying) { p.pause(); playView.text = "▶"; statusView.text = "Dijeda" }
            else { p.play(); playView.text = "Ⅱ"; statusView.text = "Sedang diputar" }
            refreshAudioInfo()
            updatePlaybackNotification(try { p.isPlaying } catch (_: Exception) { false })
        } catch (e: Exception) { statusView.text = "Pemutaran gagal: " + e.message }
    }

    private fun showMetadata(uri: Uri, t: Track) {
        val r = MediaMetadataRetriever()
        var fileDuration = "Tidak tersedia"; var mime = "Tidak tersedia"; var bitrate = "Tidak tersedia"
        try {
            r.setDataSource(this, uri)
            fileDuration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: fileDuration
            mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mime
            bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: bitrate
        } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) { } }
        var rate = "Tidak tersedia"; var channels = "Tidak tersedia"; var trackMime = mime
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
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
        sourceRate = rate.toIntOrNull()
        fileInfo.text = "Judul: " + t.title + "\nArtis: " + t.artist + "\nAlbum: " + t.album + "\nDurasi: " + fileDuration + " ms\nCodec/MIME: " + trackMime + "\nSample rate sumber: " + rate + " Hz\nKanal: " + channels + "\nBitrate: " + bitrate + " bit/s (jika dilaporkan)\nURI: " + uri
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
        val picked = vividArtworkColor(bitmap)
        if (picked == accentColor) return
        accentColor = picked
        if (::seek.isInitialized) {
            seek.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        if (::playView.isInitialized) {
            playView.setTextColor(accentColor)
            playView.background = shape(accentColor, 14)
        }
        if (::scanProgress.isInitialized) scanProgress.progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
        if (::scanProgressText.isInitialized) scanProgressText.setTextColor(accentColor)
        if (::titleView.isInitialized) titleView.setTextColor(accentColor)
        if (::audioInfoToggle.isInitialized) audioInfoToggle.setTextColor(accentColor)
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

    private fun loadArtworkInto(target: ImageView, track: Track) {
        artworkExecutor.execute {
            var bytes: ByteArray? = null
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(this@MainActivity, track.uri); bytes = retriever.embeddedPicture }
            catch (_: Exception) { }
            finally { try { retriever.release() } catch (_: Exception) { } }
            if (bytes == null && track.coverUri != null) {
                try { contentResolver.openInputStream(track.coverUri)?.use { bytes = it.readBytes() } } catch (_: Exception) { }
            }
            val bitmap = bytes?.let { decodeArtwork(it) }
            handler.post {
                if (bitmap != null && !isFinishing && target.isAttachedToWindow) { target.setImageBitmap(bitmap) }
                else bitmap?.recycle()
            }
        }
    }

    private fun loadArtwork(t: Track) {
        val token = ++artToken
        artImage.visibility = View.GONE; artImage.setImageDrawable(null); artPlaceholder.visibility = View.VISIBLE
        artworkExecutor.execute {
            var bytes: ByteArray? = null
            val r = MediaMetadataRetriever()
            try { r.setDataSource(this@MainActivity, t.uri); bytes = r.embeddedPicture } catch (_: Exception) { }
            finally { try { r.release() } catch (_: Exception) { } }
            if (bytes == null && t.coverUri != null) try { contentResolver.openInputStream(t.coverUri)?.use { bytes = it.readBytes() } } catch (_: Exception) { }
            val bitmap = bytes?.let { decodeArtwork(it) }
            handler.post {
                if (token != artToken) { bitmap?.recycle(); return@post }
                if (bitmap != null) {
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
        val active = try { if (outputTrackPlaying) outputTrack?.routedDevice else null } catch (_: Exception) { null }
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
                    " frame / mode " + (outputPerformance ?: "tidak tersedia") else "tidak sedang aktif") +
            "\nAudioManager mode: " + audioMode +
            "\nSample rate sumber: " + (sourceRate?.toString() ?: "Belum terbaca") + " Hz" +
            "\nSample rate default Android (properti low-latency/native, bukan pengukuran stream ini): " + defaultRate + " Hz\nBuffer default sistem: " + buffer + " frame" +
            "\n" + activeCapabilities + "\n\nPerangkat output tersedia/terdeteksi (bukan berarti semuanya aktif):\n" +
            (if (devices.isEmpty()) "Tidak ada perangkat dilaporkan." else devices.joinToString("\n") { "• " + describe(it) }) +
            "\nPeristiwa terakhir: " + lastDeviceEvent + "\n\n" + rateNote +
            "\n\nAudioTrack menunjukkan format yang dikonfigurasi untuk stream aplikasi. Audio HAL/DSP/DAC masih dapat melakukan konversi; ini bukan bukti bit-perfect atau sample rate fisik DAC."
        if (::audioInfoInline.isInitialized) {
            val fileDetails = if (::fileInfo.isInitialized) fileInfo.text.toString() else "Pilih lagu untuk membaca metadata file."
            audioInfoInline.text = "INFO FILE / LAGU\n$fileDetails\n\nINFO OUTPUT / ROUTE\n${routeInfo.text}"
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
        recordDiagnostic("NOTIFICATION_UPDATE playing=$isPlaying title=${track.title} sourceRate=${sourceRate ?: -1} engine=Media3/ExoPlayer route=not-exposed-by-player-api")
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
            .setSubText("G6 HiFi Player")
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_music, "Sebelumnya", activityIntent("previous", 6101)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_music, if (isPlaying) "Jeda" else "Putar", activityIntent("toggle", 6102)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_stat_music, "Berikutnya", activityIntent("next", 6103)).build())
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
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("notification_action")?.let { handleNotificationAction(it) }
    }

    private fun scanAudioConfiguration() {
        val paths = listOf(
            "/vendor/etc/audio_output_policy.conf",
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
            "compress_offload", "deep_buffer", "headphone", "headset", "hifi", "quad", "wcd9335", "wcd9340")
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
        lastAudioConfigLog = report.toString()
        AlertDialog.Builder(this).setTitle("Hasil scan audio LG").setMessage(lastAudioConfigLog)
            .setNeutralButton("SALIN LOG") { _, _ -> copyText(lastAudioConfigLog) }
            .setNegativeButton("KIRIM LOG") { _, _ -> shareText(lastAudioConfigLog) }
            .setPositiveButton("TUTUP", null).show()
    }

    private fun updateActiveRoute() {
        val track = activeAudioTrack
        val playing = try { player?.isPlaying == true && track?.playState == AudioTrack.PLAYSTATE_PLAYING } catch (_: Exception) { false }
        val active = try { if (playing) track?.routedDevice else null } catch (_: Exception) { null }
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
                recordDiagnostic("OUTPUT_ROUTE_CHANGED previous=" + previous + " current=" + routeKey)
            }
            if (::routeInfo.isInitialized) refreshAudioInfo()
        }
    }
    private fun show(which: String) {
        playerPanel.visibility = if (which == "player") View.VISIBLE else View.GONE
        libraryPanel.visibility = if (which == "library") View.VISIBLE else View.GONE
        audioPanel.visibility = if (which == "audio") View.VISIBLE else View.GONE
        filePanel.visibility = if (which == "file") View.VISIBLE else View.GONE
        navState(navPlayer, which == "player"); navState(navLibrary, which == "library")
        navState(navAudio, which == "audio"); navState(navFile, which == "file")
    }
    private fun navButton(label: String, click: () -> Unit) = text(label, 10f, MUTED, true).apply {
        gravity = Gravity.CENTER; background = shape(Color.TRANSPARENT, 12); setOnClickListener { click() }
    }
    private fun navState(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) accentColor else MUTED); view.background = shape(if (selected) CARD else Color.TRANSPARENT, 12)
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
        gravity = Gravity.CENTER; background = shape(if (primary) accentColor else LINE, 14)
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
        private const val BG = 0xFF0B1015.toInt()
        private const val CARD = 0xFF151D24.toInt()
        private const val ART = 0xFF202C35.toInt()
        private const val LINE = 0xFF2A3741.toInt()
        private const val WHITE = 0xFFF3F6F8.toInt()
        private const val MUTED = 0xFF9BAAB5.toInt()
        private const val DEFAULT_ACCENT = 0xFFB6F2D5.toInt()
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "ape", "dsf", "dff", "wma", "3gp", "amr")
    }
}
