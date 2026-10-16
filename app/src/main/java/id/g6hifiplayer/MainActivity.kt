package id.g6hifiplayer

import android.app.Activity
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private var player: MediaPlayer? = null
    private var selectedUri: Uri? = null
    private var sourceSampleRate: Int? = null
    private lateinit var status: TextView
    private lateinit var outputDetails: TextView
    private lateinit var fileDetails: TextView
    private lateinit var playButton: Button
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var lastDeviceEvent = "Belum ada perubahan perangkat yang teramati sejak aplikasi dibuka."
    private val audioDeviceCallback = object : AudioManager.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            lastDeviceEvent = "Perangkat ditambahkan: " + addedDevices.joinToString { describeDevice(it) }
            refreshAudioInfo()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            lastDeviceEvent = "Perangkat dilepas: " + removedDevices.joinToString { describeDevice(it) }
            refreshAudioInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.addView(TextView(this).apply {
            text = "G6 HiFi Player"
            textSize = 28f
        })
        content.addView(TextView(this).apply {
            text = "Pemutar musik dan pemeriksa informasi jalur audio"
            textSize = 15f
            setPadding(0, 6, 0, 4)
        })
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionLabel = if (android.os.Build.VERSION.SDK_INT >= 28) {
            "Versi ${packageInfo.versionName} (Build ${packageInfo.longVersionCode})"
        } else {
            @Suppress("DEPRECATION")
            "Versi ${packageInfo.versionName} (Build ${packageInfo.versionCode})"
        }
        content.addView(TextView(this).apply {
            text = versionLabel
            textSize = 13f
            setPadding(0, 0, 0, 20)
        })
        content.addView(Button(this).apply {
            text = "Pilih file musik"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                startActivityForResult(intent, REQUEST_AUDIO)
            }
        })
        playButton = Button(this).apply {
            text = "Putar"
            isEnabled = false
            setOnClickListener { togglePlayback() }
        }
        content.addView(playButton)
        content.addView(Button(this).apply {
            text = "Perbarui info jalur audio"
            setOnClickListener { refreshAudioInfo() }
        })
        status = TextView(this).apply {
            text = "Pilih file audio untuk memulai."
            textSize = 17f
            setPadding(0, 18, 0, 8)
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "STATUS OUTPUT ANDROID"
            textSize = 18f
            setPadding(0, 16, 0, 4)
        })
        outputDetails = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        content.addView(outputDetails)
        content.addView(TextView(this).apply {
            text = "INFORMASI FILE"
            textSize = 18f
            setPadding(0, 16, 0, 4)
        })
        fileDetails = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        content.addView(fileDetails)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        refreshAudioInfo()
    }

    @Deprecated("Deprecated in Android but retained for compatibility with Android 9")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener {
                    playButton.isEnabled = true
                    status.text = "Siap diputar"
                    showMetadata(uri)
                    refreshAudioInfo()
                }
                setOnCompletionListener {
                    playButton.text = "Putar"
                    status.text = "Selesai"
                    refreshAudioInfo()
                }
                setOnErrorListener { _, what, extra ->
                    status.text = "Gagal memutar (kode $what/$extra)"
                    true
                }
                prepareAsync()
            }
            status.text = "Memuat file audio…"
            playButton.isEnabled = false
            showMetadata(uri)
        } catch (e: Exception) {
            status.text = "Tidak dapat membuka file: ${e.message}"
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                playButton.text = "Lanjutkan"
                status.text = "Dijeda"
            } else {
                p.start()
                playButton.text = "Jeda"
                status.text = "Sedang diputar"
            }
            refreshAudioInfo()
        } catch (e: Exception) {
            status.text = "Pemutaran gagal: ${e.message}"
        }
    }

    private fun showMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        var title = "Tidak tersedia"
        var artist = "Tidak tersedia"
        var album = "Tidak tersedia"
        var duration = "Tidak tersedia"
        var mime = "Tidak tersedia"
        var bitrate = "Tidak tersedia"
        try {
            retriever.setDataSource(this, uri)
            fun meta(key: Int): String = retriever.extractMetadata(key) ?: "Tidak tersedia"
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
            mime = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        } catch (_: Exception) {
            // Try MediaExtractor below for technical audio track properties.
        } finally {
            retriever.release()
        }

        var sampleRate = "Tidak tersedia dari decoder"
        var channels = "Tidak tersedia"
        var trackMime = mime
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackType = if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME) else null
                if (trackType?.startsWith("audio/") == true) {
                    trackMime = trackType
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toString()
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toString()
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toString()
                    }
                    break
                }
            }
        } catch (_: Exception) {
            // Some Android codecs do not expose all source properties.
        } finally {
            extractor.release()
        }

        sourceSampleRate = sampleRate.toIntOrNull()
        fileDetails.text = "Judul: $title\n" +
            "Artis: $artist\n" +
            "Album: $album\n" +
            "Durasi: $duration ms\n" +
            "Codec/MIME: $trackMime\n" +
            "Sample rate file: $sampleRate Hz\n" +
            "Kanal: $channels\n" +
            "Bitrate: $bitrate bit/s (jika dilaporkan decoder)\n" +
            "URI: $uri"
    }

    private fun refreshAudioInfo() {
        val devices = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
        catch (_: Exception) { emptyList() }
        val routeText = if (devices.isEmpty()) "Tidak ada perangkat output yang dilaporkan Android." else
            devices.joinToString("\n") { device ->
                "${deviceName(device)} (type=${device.type}, id=${device.id})"
            }
        val rates = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "Tidak dilaporkan"
        val frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "Tidak dilaporkan"
        val current = if (player?.isPlaying == true) "MediaPlayer sedang memutar" else "MediaPlayer tidak sedang memutar"
        val activeRoute = try { player?.routedDevice } catch (_: Exception) { null }
        val activeRouteText = when {
            player == null -> "Pilih dan mulai putar file untuk memeriksa rute."
            activeRoute != null -> "${deviceName(activeRoute)} (${deviceTypeName(activeRoute.type)}, type=${activeRoute.type}, id=${activeRoute.id})"
            else -> "Android/MediaPlayer belum melaporkan rute aktif."
        }
        val sourceRateText = sourceSampleRate?.let { "$it Hz" } ?: "Belum terbaca"
        val rateNote = if (sourceSampleRate != null && sourceSampleRate != rates.toIntOrNull()) {
            "Sample rate sumber berbeda dari nilai default Android; ini dapat mengindikasikan konversi/resampling, tetapi belum membuktikan sample rate aktual DAC."
        } else {
            "Nilai default Android bukan pengukuran langsung sample rate aktual DAC."
        }
        val wiredDevices = devices.filter {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        val wiredText = if (wiredDevices.isEmpty()) {
            "Jack/headphone kabel: tidak terdeteksi dalam daftar output saat ini."
        } else {
            "Jack/headphone kabel terdeteksi: " + wiredDevices.joinToString { describeDevice(it) } +
                ". Ini hanya menandakan tersedia, bukan bukti rute pemutaran aktif."
        }
        outputDetails.text = "$current\n" +
            "Peristiwa perangkat terakhir: $lastDeviceEvent\n" +
            "$wiredText\n" +
            "Rute aktif menurut MediaPlayer: $activeRouteText\n" +
            "Sample rate sumber (dibaca dari track): $sourceRateText\n" +
            "Sample rate output default Android: $rates Hz\n" +
            "Buffer output default Android: $frames frame\n" +
            "$rateNote\n" +
            "Perangkat output yang terdeteksi (bukan selalu rute aktif):\n$routeText\n" +
            "Catatan: informasi ini tidak membuktikan bit-perfect."
    }

    private fun deviceName(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Perangkat audio"
        return String.format(Locale.getDefault(), "%s", product)
    }

    private fun describeDevice(device: AudioDeviceInfo): String =
        "${deviceName(device)} (${deviceTypeName(device.type)}, type=${device.type}, id=${device.id})"

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker internal"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headset berkabel"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphone berkabel"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "headset USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        else -> "jenis perangkat lain"
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO = 1001
    }
}
