package id.g6hifiplayer

import android.app.Activity
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private var player: MediaPlayer? = null
    private var selectedUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var playButton: Button
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

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
            setPadding(0, 6, 0, 20)
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
        details = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        content.addView(details)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
                }
                setOnCompletionListener {
                    playButton.text = "Putar"
                    status.text = "Selesai"
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
                refreshAudioInfo()
            }
        } catch (e: Exception) {
            status.text = "Pemutaran gagal: ${e.message}"
        }
    }

    private fun showMetadata(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            fun meta(key: Int): String = retriever.extractMetadata(key) ?: "Tidak tersedia"
            details.text = "INFORMASI FILE\n" +
                "Judul: ${meta(MediaMetadataRetriever.METADATA_KEY_TITLE)}\n" +
                "Artis: ${meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)}\n" +
                "Album: ${meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)}\n" +
                "Durasi: ${meta(MediaMetadataRetriever.METADATA_KEY_DURATION)} ms\n" +
                "Codec: ${meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)}\n" +
                "Resolusi audio: ${meta(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)} Hz, " +
                "${meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)} bit/s (jika disediakan decoder)\n" +
                "URI: $uri\n\n" + details.text
        } catch (e: Exception) {
            details.text = "Metadata tidak dapat dibaca: ${e.message}\nURI: $uri"
        } finally {
            retriever.release()
        }
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
        details.text = "STATUS OUTPUT ANDROID\n$current\n" +
            "Sample rate output default Android: $rates Hz\n" +
            "Buffer output default Android: $frames frame\n" +
            "Perangkat output yang terdeteksi:\n$routeText\n\n" + details.text
    }

    private fun deviceName(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Perangkat audio"
        return String.format(Locale.getDefault(), "%s", product)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO = 1001
    }
}
