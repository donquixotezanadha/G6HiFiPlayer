package id.g6hifiplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private var player: MediaPlayer? = null
    private var selectedUri: Uri? = null
    private var sourceRate: Int? = null
    private var durationMs = 0
    private var prepared = false
    private var seeking = false
    private var lastDeviceEvent = "Belum ada perubahan perangkat."
    private lateinit var playerPanel: LinearLayout
    private lateinit var audioPanel: LinearLayout
    private lateinit var filePanel: LinearLayout
    private lateinit var navPlayer: TextView
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
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (prepared) {
                val p = player
                if (p != null) {
                    if (!seeking) {
                        val pos = try { p.currentPosition } catch (_: Exception) { 0 }
                        seek.progress = pos
                        elapsed.text = time(pos)
                    }
                    if (try { p.isPlaying } catch (_: Exception) { false }) updateActiveRoute()
                }
            }
            handler.postDelayed(this, 500)
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            lastDeviceEvent = "Ditambahkan: " + addedDevices.joinToString { describe(it) }
            refreshAudioInfo()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            lastDeviceEvent = "Dilepas: " + removedDevices.joinToString { describe(it) }
            refreshAudioInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshAudioInfo()
        handler.post(ticker)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(12))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(text("G6", 28f, WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(text("HI-FI  •  LOCAL AUDIO", 10f, BG, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = shape(ACCENT, 20)
        })
        header.addView(brand)
        header.addView(text("MUSIC PLAYER     /     BUILD 0.5.0", 11f, MUTED, true).apply {
            letterSpacing = .1f
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(header)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(4), dp(14), dp(12)) }
        navPlayer = navButton("PLAYER") { show("player") }
        navAudio = navButton("AUDIO PATH") { show("audio") }
        navFile = navButton("FILE INFO") { show("file") }
        nav.addView(navPlayer, LinearLayout.LayoutParams(0, dp(44), 1f))
        nav.addView(navAudio, LinearLayout.LayoutParams(0, dp(44), 1f))
        nav.addView(navFile, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(nav)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(3), dp(18), dp(24)) }
        playerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        audioPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        makePlayerPanel()
        makeAudioPanel()
        makeFilePanel()
        content.addView(playerPanel)
        content.addView(audioPanel)
        content.addView(filePanel)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        show("player")
    }

    private fun makePlayerPanel() {
        val hero = card()
        hero.addView(text("NOW PLAYING", 11f, ACCENT, true))
        val art = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(ART, 24)
            setPadding(dp(12), dp(24), dp(12), dp(24))
        }
        art.addView(text("G6", 56f, WHITE, true).apply { gravity = Gravity.CENTER })
        art.addView(text("QUAD DAC  /  HI-RES", 10f, ACCENT, true).apply { gravity = Gravity.CENTER; letterSpacing = .08f })
        hero.addView(art, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(14) })
        titleView = text("Belum ada lagu dipilih", 22f, WHITE, true).apply { setPadding(0, dp(18), 0, dp(3)) }
        artistView = text("Pilih file lokal untuk mulai", 14f, MUTED, false)
        albumView = text("", 12f, MUTED, false).apply { setPadding(0, dp(4), 0, 0) }
        hero.addView(titleView); hero.addView(artistView); hero.addView(albumView)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        elapsed = text("0:00", 11f, MUTED, false)
        duration = text("0:00", 11f, MUTED, false)
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(duration)
        hero.addView(times)
        seek = SeekBar(this).apply {
            max = 1
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = time(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (prepared && durationMs > 0 && bar != null) player?.seekTo(bar.progress.coerceIn(0, durationMs))
                    seeking = false
                }
            })
        }
        hero.addView(seek)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(3)) }
        controls.addView(action("＋  PILIH LAGU", false) { pickFile() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        playView = action("▶", true) { togglePlayback() }
        controls.addView(playView, LinearLayout.LayoutParams(dp(66), dp(48)))
        hero.addView(controls)
        playerPanel.addView(hero)
        val state = card()
        state.addView(text("STATUS PEMUTARAN", 11f, MUTED, true))
        statusView = text("Pilih file audio untuk memulai.", 14f, WHITE, false).apply { setPadding(0, dp(8), 0, 0) }
        state.addView(statusView)
        playerPanel.addView(state)
    }

    private fun makeAudioPanel() {
        val summary = card()
        summary.addView(text("OUTPUT AKTIF", 11f, ACCENT, true))
        routeSummary = text("Memeriksa…", 22f, WHITE, true).apply { setPadding(0, dp(8), 0, dp(5)) }
        summary.addView(routeSummary)
        summary.addView(text("Rute dari MediaPlayer, jika dilaporkan.", 12f, MUTED, false))
        audioPanel.addView(summary)
        val details = card()
        details.addView(text("DIAGNOSTIK ROUTE", 11f, MUTED, true))
        routeInfo = text("", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        details.addView(routeInfo)
        details.addView(action("↻  PERBARUI INFORMASI", false) { refreshAudioInfo() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })
        audioPanel.addView(details)
        val note = card()
        note.addView(text("BATASAN PENGUKURAN", 11f, ACCENT, true))
        note.addView(text("API publik Android tidak melaporkan bit depth stream aktif atau sample rate aktual DAC. Sample rate default sistem bukan bukti stream aktif; aplikasi tidak akan menyebut bit-perfect tanpa data pendukung.", 13f, MUTED, false).apply { setPadding(0, dp(8), 0, 0) })
        audioPanel.addView(note)
    }

    private fun makeFilePanel() {
        val info = card()
        info.addView(text("INFORMASI FILE", 11f, ACCENT, true))
        fileInfo = text("Belum ada file yang dipilih.", 13f, WHITE, false).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }
        info.addView(fileInfo)
        info.addView(action("PILIH FILE AUDIO", false) { pickFile() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(16) })
        filePanel.addView(info)
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
        }, REQUEST_AUDIO)
    }

    @Deprecated("Kept for Android 9 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        try {
            player?.release()
            prepared = false
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener { mp ->
                    prepared = true
                    durationMs = mp.duration
                    seek.max = durationMs.coerceAtLeast(1)
                    duration.text = time(durationMs)
                    statusView.text = "Siap diputar • " + (sourceRate?.let { "%.1f kHz".format(Locale.US, it / 1000f) } ?: "sample rate tidak diketahui")
                    playView.text = "▶"
                    playView.isEnabled = true
                    showMetadata(uri)
                    refreshAudioInfo()
                }
                setOnCompletionListener { playView.text = "▶"; statusView.text = "Pemutaran selesai."; refreshAudioInfo() }
                setOnErrorListener { _, what, extra -> prepared = false; statusView.text = "Gagal memutar • $what/$extra"; true }
                prepareAsync()
            }
            titleView.text = "Memuat file…"
            artistView.text = uri.lastPathSegment ?: "Audio lokal"
            statusView.text = "Membuka file audio…"
            playView.isEnabled = false
            showMetadata(uri)
        } catch (e: Exception) {
            statusView.text = "Tidak dapat membuka file: ${e.message}"
        }
        show("player")
    }

    private fun togglePlayback() {
        val p = player
        if (p == null || !prepared) { statusView.text = "Pilih file audio terlebih dahulu."; return }
        try {
            if (p.isPlaying) {
                p.pause(); playView.text = "▶"; statusView.text = "Dijeda"
            } else {
                p.start(); playView.text = "Ⅱ"; statusView.text = "Sedang diputar"
            }
            refreshAudioInfo()
        } catch (e: Exception) { statusView.text = "Pemutaran gagal: ${e.message}" }
    }

    private fun showMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        var title = "Tidak tersedia"; var artist = "Tidak tersedia"; var album = "Tidak tersedia"
        var fileDuration = "Tidak tersedia"; var mime = "Tidak tersedia"; var bitrate = "Tidak tersedia"
        try {
            retriever.setDataSource(this, uri)
            fun meta(key: Int) = retriever.extractMetadata(key) ?: "Tidak tersedia"
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            fileDuration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
            mime = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        } catch (_: Exception) { } finally { retriever.release() }
        var rate = "Tidak tersedia"; var channels = "Tidak tersedia"; var trackMime = mime
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackType = if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME) else null
                if (trackType?.startsWith("audio/") == true) {
                    trackMime = trackType
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toString()
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toString()
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toString()
                    break
                }
            }
        } catch (_: Exception) { } finally { extractor.release() }
        sourceRate = rate.toIntOrNull()
        titleView.text = title.takeIf { it != "Tidak tersedia" } ?: (uri.lastPathSegment ?: "Audio lokal")
        artistView.text = artist.takeIf { it != "Tidak tersedia" } ?: "Artis tidak tersedia"
        albumView.text = album.takeIf { it != "Tidak tersedia" } ?: ""
        fileInfo.text = "Judul: ${titleView.text}\nArtis: ${artistView.text}\nAlbum: ${albumView.text.ifBlank { "Tidak tersedia" }}\nDurasi: $fileDuration ms\nCodec/MIME: $trackMime\nSample rate sumber: $rate Hz\nKanal: $channels\nBitrate: $bitrate bit/s (jika dilaporkan)\nURI: $uri"
    }

    private fun refreshAudioInfo() {
        val devices = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (_: Exception) { emptyList() }
        val active = try { player?.routedDevice } catch (_: Exception) { null }
        val route = when {
            active != null -> "${deviceName(active)} • ${deviceType(active.type)}"
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
        routeInfo.text = "Status: ${if (try { player?.isPlaying == true } catch (_: Exception) { false }) "SEDANG MEMUTAR" else "tidak memutar"}\nRute aktif MediaPlayer: $route\n$wiredText\nSample rate sumber: ${sourceRate?.let { "$it Hz" } ?: "Belum terbaca"}\nSample rate default Android: $defaultRate Hz\nBuffer default: $buffer frame\nPerangkat output terdeteksi:\n${if (devices.isEmpty()) "Tidak ada perangkat dilaporkan." else devices.joinToString("\n") { "• ${describe(it)}" }}\nPeristiwa terakhir: $lastDeviceEvent\n\n$rateNote\n\nBit depth stream aktif dan status bit-perfect tidak tersedia dari API publik ini."
    }

    private fun updateActiveRoute() {
        val active = try { player?.routedDevice } catch (_: Exception) { null }
        if (active != null) routeSummary.text = "${deviceName(active)} • ${deviceType(active.type)}"
    }

    private fun show(which: String) {
        playerPanel.visibility = if (which == "player") View.VISIBLE else View.GONE
        audioPanel.visibility = if (which == "audio") View.VISIBLE else View.GONE
        filePanel.visibility = if (which == "file") View.VISIBLE else View.GONE
        navState(navPlayer, which == "player"); navState(navAudio, which == "audio"); navState(navFile, which == "file")
    }

    private fun navButton(caption: String, click: () -> Unit) = text(caption, 11f, MUTED, true).apply {
        gravity = Gravity.CENTER
        background = shape(Color.TRANSPARENT, 14)
        setOnClickListener { click() }
    }
    private fun navState(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) ACCENT else MUTED)
        view.background = shape(if (selected) CARD else Color.TRANSPARENT, 14)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = shape(CARD, 22)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun action(caption: String, primary: Boolean, click: () -> Unit) = text(caption, if (primary) 18f else 12f, if (primary) BG else WHITE, true).apply {
        gravity = Gravity.CENTER
        background = shape(if (primary) ACCENT else LINE, 16)
        isClickable = true; isFocusable = true
        setOnClickListener { click() }
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun describe(device: AudioDeviceInfo) = "${deviceName(device)} (${deviceType(device.type)}, type=${device.type}, id=${device.id})"
    private fun deviceName(device: AudioDeviceInfo) = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Perangkat audio"
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
        val seconds = ms.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        player?.release(); player = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO = 1001
        private const val BG = 0xFF0B1015.toInt()
        private const val CARD = 0xFF151D24.toInt()
        private const val ART = 0xFF202C35.toInt()
        private const val LINE = 0xFF2A3741.toInt()
        private const val WHITE = 0xFFF3F6F8.toInt()
        private const val MUTED = 0xFF9BAAB5.toInt()
        private const val ACCENT = 0xFFB6F2D5.toInt()
    }
}