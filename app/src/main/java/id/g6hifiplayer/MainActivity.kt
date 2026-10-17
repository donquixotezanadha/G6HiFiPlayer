package id.g6hifiplayer

import android.app.Activity
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
import android.media.AudioTrack
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

class MainActivity : Activity() {
    private data class Track(val uri: Uri, val title: String, val artist: String, val album: String, val coverUri: Uri?)
    private var player: MediaPlayer? = null
    private var sourceRate: Int? = null
    private var durationMs = 0
    private var prepared = false
    private var seeking = false
    private var pendingAutoplay = false
    private var tracks: List<Track> = emptyList()
    private var currentIndex = -1
    private var selectedAlbum: String? = null
    private var artToken = 0
    private var lastDeviceEvent = "Belum ada perubahan perangkat."
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
                    val pos = try { p.currentPosition } catch (_: Exception) { 0 }
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
        buildUi(); audioManager.registerAudioDeviceCallback(deviceCallback, null)
        createNotificationChannel()
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
            gravity = Gravity.CENTER; setPadding(dp(12), dp(9), dp(12), dp(9)); background = shape(ACCENT, 20)
        })
        header.addView(brand)
        header.addView(text("MUSIC PLAYER / BUILD 0.6.0", 11f, MUTED, true).apply { setPadding(0, dp(5), 0, 0) })
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
        val hero = card(); hero.addView(text("NOW PLAYING", 11f, ACCENT, true))
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
            max = 1; progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) elapsed.text = time(value) }
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (prepared && bar != null) player?.seekTo(bar.progress.coerceIn(0, durationMs))
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
        scanProgressText = text("", 12f, ACCENT, false).apply { setPadding(0, dp(10), 0, dp(3)); visibility = View.GONE }
        head.addView(scanProgressText)
        scanProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        head.addView(scanProgress, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(3) })
        libraryPanel.addView(head)
        val albums = card(); albums.addView(text("ALBUM", 11f, ACCENT, true))
        albumRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        albums.addView(albumRows); libraryPanel.addView(albums)
        val songs = card(); songs.addView(text("LAGU", 11f, ACCENT, true))
        trackRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        songs.addView(trackRows); libraryPanel.addView(songs)
    }

    private fun makeAudioPanel() {
        val summary = card(); summary.addView(text("OUTPUT AKTIF", 11f, ACCENT, true))
        routeSummary = text("Memeriksa…", 22f, WHITE, true).apply { setPadding(0, dp(8), 0, dp(5)) }
        summary.addView(routeSummary); summary.addView(text("Rute dari MediaPlayer, jika dilaporkan.", 12f, MUTED, false)); audioPanel.addView(summary)
        val detail = card(); detail.addView(text("DIAGNOSTIK ROUTE", 11f, MUTED, true))
        routeInfo = text("", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        detail.addView(routeInfo)
        detail.addView(action("PERBARUI INFORMASI", false) { refreshAudioInfo() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
        val logActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        logActions.addView(action("SALIN LOG", false) { copyAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        logActions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        logActions.addView(action("KIRIM LOG", false) { shareAudioLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        detail.addView(logActions); audioPanel.addView(detail)
        val lg = card(); lg.addView(text("PEMERIKSAAN AUDIO LG", 11f, ACCENT, true))
        lg.addView(text("Mencari konfigurasi LG yang dapat dibaca dan menguji dukungan direct playback. Hasilnya bukan bukti jalur lagu yang sedang aktif.", 12f, MUTED, false).apply { setPadding(0, dp(7), 0, dp(8)) })
        lg.addView(action("SCAN KONFIGURASI LG", false) { scanAudioConfiguration() }, LinearLayout.LayoutParams(-1, dp(44))); audioPanel.addView(lg)
        val note = card(); note.addView(text("BATASAN PENGUKURAN", 11f, ACCENT, true))
        note.addView(text("API publik Android tidak memberi bukti pasti tentang bit depth stream aktif, sample rate DAC aktual, atau bit-perfect. Rate sumber yang berbeda dari default bukan bukti resampling dengan sendirinya.", 13f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) })
        audioPanel.addView(note)
    }

    private fun makeFilePanel() {
        val info = card(); info.addView(text("INFORMASI FILE", 11f, ACCENT, true))
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
            scanMusicFolder(uri); show("library"); return
        }
        if (requestCode == REQUEST_AUDIO) {
            try {
                val flag = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (flag != 0) contentResolver.takePersistableUriPermission(uri, flag)
            } catch (_: Exception) { }
            tracks = listOf(readTrack(uri, null, null)); currentIndex = 0; selectedAlbum = null; playTrackAt(0, false)
        }
    }

    private fun scanMusicFolder(uri: Uri) {
        librarySummary.text = "Memindai folder dan subfolder…"; statusView.text = "Memindai pustaka musik…"
        scanProgress.visibility = View.VISIBLE; scanProgressText.visibility = View.VISIBLE
        scanProgressText.text = "Memulai pemindaian…"
        Thread {
            val found = mutableListOf<Track>()
            val count = intArrayOf(0)
            var error: String? = null
            try { DocumentFile.fromTreeUri(this, uri)?.let { scanDirectory(it, found, mutableMapOf(), 0, count) } }
            catch (e: Exception) { error = e.message ?: "akses ditolak" }
            val sorted = found.sortedWith(compareBy<Track> { it.album.lowercase(Locale.ROOT) }.thenBy { it.title.lowercase(Locale.ROOT) })
            handler.post {
                tracks = sorted; currentIndex = -1; selectedAlbum = null; renderLibrary()
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
        } catch (_: Exception) { }
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
            trackRows.addView(text("Lagu yang ditemukan akan muncul di sini.", 13f, MUTED, false)); return
        }
        val albums = tracks.map { it.album }.distinct().sortedBy { it.lowercase(Locale.ROOT) }
        albumRows.addView(rowButton("Semua album • " + tracks.size + " lagu", selectedAlbum == null) { selectedAlbum = null; renderLibrary() })
        albums.forEach { album ->
            val count = tracks.count { it.album == album }
            albumRows.addView(rowButton(album + " • " + count + " lagu", selectedAlbum == album) { selectedAlbum = album; renderLibrary() })
        }
        tracks.withIndex().filter { selectedAlbum == null || it.value.album == selectedAlbum }.forEach { entry ->
            val t = entry.value
            val row = text(t.title + "\n" + t.artist + " • " + t.album, 13f, WHITE, true).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12)); background = shape(if (entry.index == currentIndex) LINE else BG, 12)
                isClickable = true; isFocusable = true
                setOnClickListener { playTrackAt(entry.index, true); show("player") }
            }
            trackRows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
    }
    private fun rowButton(label: String, selected: Boolean, click: () -> Unit) = text(label, 13f, if (selected) ACCENT else WHITE, selected).apply {
        setPadding(dp(12), dp(11), dp(12), dp(11)); background = shape(if (selected) LINE else BG, 12)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) }
    }

    private fun playTrackAt(index: Int, autoplay: Boolean) {
        if (index !in tracks.indices) return
        currentIndex = index
        val t = tracks[index]; pendingAutoplay = autoplay; prepared = false; sourceRate = null; durationMs = 0
        try { player?.release() } catch (_: Exception) { }; player = null
        titleView.text = t.title; artistView.text = t.artist; albumView.text = t.album
        elapsed.text = "0:00"; duration.text = "0:00"; seek.progress = 0; seek.max = 1
        playView.isEnabled = false; playView.text = "▶"; statusView.text = "Memuat " + t.title + "…"
        showMetadata(t.uri, t); loadArtwork(t)
        try {
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, t.uri)
                setOnPreparedListener { mp ->
                    prepared = true; durationMs = mp.duration; seek.max = durationMs.coerceAtLeast(1)
                    this@MainActivity.duration.text = time(durationMs); playView.isEnabled = true
                    statusView.text = "Siap diputar • " + (sourceRate?.let { "%.1f kHz".format(Locale.US, it / 1000f) } ?: "sample rate tidak diketahui")
                    if (pendingAutoplay) try {
                        mp.start(); playView.text = "Ⅱ"; statusView.text = "Sedang diputar • " + t.title
                    } catch (e: Exception) { statusView.text = "Gagal memulai: " + e.message }
                    pendingAutoplay = false; refreshAudioInfo()
                }
                setOnCompletionListener {
                    playView.text = "▶"
                    if (currentIndex >= 0 && currentIndex + 1 < tracks.size) playTrackAt(currentIndex + 1, true)
                    else { statusView.text = "Pemutaran selesai."; refreshAudioInfo() }
                }
                setOnErrorListener { _, what, extra ->
                    prepared = false; playView.isEnabled = true
                    statusView.text = "Gagal memutar • " + what + "/" + extra + " (format mungkin tidak didukung)"; true
                }
                prepareAsync()
            }
        } catch (e: Exception) { playView.isEnabled = true; statusView.text = "Tidak dapat membuka file: " + e.message }
        renderLibrary()
    }
    private fun playPrevious() {
        if (tracks.isEmpty()) { statusView.text = "Pilih folder atau file audio terlebih dahulu."; return }
        playTrackAt(if (currentIndex <= 0) tracks.lastIndex else currentIndex - 1, true)
    }
    private fun playNext() {
        if (tracks.isEmpty()) { statusView.text = "Pilih folder atau file audio terlebih dahulu."; return }
        playTrackAt(if (currentIndex < 0 || currentIndex >= tracks.lastIndex) 0 else currentIndex + 1, true)
    }
    private fun togglePlayback() {
        val p = player
        if (p == null || !prepared) { statusView.text = "Pilih lagu dari pustaka terlebih dahulu."; return }
        try {
            if (p.isPlaying) { p.pause(); playView.text = "▶"; statusView.text = "Dijeda" }
            else { p.start(); playView.text = "Ⅱ"; statusView.text = "Sedang diputar" }
            refreshAudioInfo()
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

    private fun loadArtwork(t: Track) {
        val token = ++artToken
        artImage.visibility = View.GONE; artImage.setImageDrawable(null); artPlaceholder.visibility = View.VISIBLE
        Thread {
            var bytes: ByteArray? = null
            val r = MediaMetadataRetriever()
            try { r.setDataSource(this@MainActivity, t.uri); bytes = r.embeddedPicture } catch (_: Exception) { }
            finally { try { r.release() } catch (_: Exception) { } }
            if (bytes == null && t.coverUri != null) try { contentResolver.openInputStream(t.coverUri)?.use { bytes = it.readBytes() } } catch (_: Exception) { }
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            handler.post {
                if (token != artToken) { bitmap?.recycle(); return@post }
                if (bitmap != null) {
                    artImage.setImageBitmap(bitmap); artImage.visibility = View.VISIBLE; artPlaceholder.visibility = View.GONE
                } else { artImage.visibility = View.GONE; artPlaceholder.visibility = View.VISIBLE; artPlaceholder.text = "G6\nNO COVER ART" }
            }
        }.start()
    }

    private fun refreshAudioInfo() {
        val devices = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (_: Exception) { emptyList() }
        val active = try { player?.routedDevice } catch (_: Exception) { null }
        val route = when {
            active != null -> deviceName(active) + " • " + deviceType(active.type)
            player == null -> "Belum ada stream"
            else -> "Rute aktif belum dilaporkan"
        }
        val defaultRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "Tidak dilaporkan"
        val buffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "Tidak dilaporkan"
        val wired = devices.filter { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        val wiredText = if (wired.isEmpty()) "Jack kabel: tidak terdeteksi" else "Jack kabel terdeteksi: " + wired.joinToString { describe(it) }
        val rateNote = when {
            sourceRate == null -> "Sample rate sumber belum terbaca."
            sourceRate.toString() == defaultRate -> "Sumber sama dengan default sistem; bukan bukti rate stream aktual."
            else -> "Sumber berbeda dari default sistem; konversi mungkin terjadi, tetapi rate DAC aktual tidak tersedia lewat API ini."
        }
        routeSummary.text = route
        routeInfo.text = "Status: " + (if (try { player?.isPlaying == true } catch (_: Exception) { false }) "SEDANG MEMUTAR" else "tidak memutar") +
            "\nRute aktif MediaPlayer: " + route + "\n" + wiredText +
            "\nSample rate sumber: " + (sourceRate?.toString() ?: "Belum terbaca") + " Hz" +
            "\nSample rate default Android: " + defaultRate + " Hz\nBuffer default: " + buffer + " frame\nPerangkat output terdeteksi:\n" +
            (if (devices.isEmpty()) "Tidak ada perangkat dilaporkan." else devices.joinToString("\n") { "• " + describe(it) }) +
            "\nPeristiwa terakhir: " + lastDeviceEvent + "\n\n" + rateNote +
            "\n\nBit depth stream aktif dan status bit-perfect belum dapat diverifikasi lewat API publik ini."
    }

    private fun scanAudioConfiguration() {
        val paths = listOf("/vendor/etc/audio_output_policy.conf", "/vendor/etc/audio_policy.conf", "/vendor/etc/audio_policy_configuration.xml", "/system/etc/audio_policy.conf", "/system/etc/audio_policy_configuration.xml")
        val report = StringBuilder()
        var readable = 0
        for (path in paths) try {
            val file = File(path)
            if (!file.isFile || !file.canRead()) continue
            val matches = file.readLines().filter {
                val line = it.lowercase(Locale.ROOT)
                listOf("direct_pcm_24", "direct_hd", "pcm_8_24", "pcm_24", "192000", "96000", "flag_dsd", "supports_pcm").any { key -> line.contains(key) }
            }.take(18)
            readable++; report.append("\n").append(file).append('\n')
            if (matches.isEmpty()) report.append("File terbaca; tidak ada baris relevan yang cocok.\n")
            else matches.forEach { report.append(it.trim()).append('\n') }
        } catch (_: Exception) { }
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
        report.append("\nKonfigurasi bukan bukti MediaPlayer sedang memakai DIRECT_HD atau direct_pcm_24.")
        AlertDialog.Builder(this).setTitle("Hasil scan audio LG").setMessage(report.toString()).setPositiveButton("OK", null).show()
    }

    private fun updateActiveRoute() {
        val active = try { player?.routedDevice } catch (_: Exception) { null }
        if (active != null) routeSummary.text = deviceName(active) + " • " + deviceType(active.type)
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
        view.setTextColor(if (selected) ACCENT else MUTED); view.background = shape(if (selected) CARD else Color.TRANSPARENT, 12)
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
        gravity = Gravity.CENTER; background = shape(if (primary) ACCENT else LINE, 14)
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun describe(d: AudioDeviceInfo) = deviceName(d) + " (" + deviceType(d.type) + ", type=" + d.type + ", id=" + d.id + ")"
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
        try { player?.release() } catch (_: Exception) { }
        player = null; super.onDestroy()
    }
    companion object {
        private const val REQUEST_AUDIO = 1001
        private const val REQUEST_FOLDER = 1002
        private const val BG = 0xFF0B1015.toInt()
        private const val CARD = 0xFF151D24.toInt()
        private const val ART = 0xFF202C35.toInt()
        private const val LINE = 0xFF2A3741.toInt()
        private const val WHITE = 0xFFF3F6F8.toInt()
        private const val MUTED = 0xFF9BAAB5.toInt()
        private const val ACCENT = 0xFFB6F2D5.toInt()
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "ape", "dsf", "dff", "wma", "3gp", "amr")
    }
}
