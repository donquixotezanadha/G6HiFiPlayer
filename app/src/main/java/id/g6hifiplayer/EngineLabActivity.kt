package id.g6hifiplayer

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Isolated playback test harness. It does not replace or modify the stable MainActivity player.
 * The first milestone is audible playback through the normal Android output path, not bit-perfect.
 */
class EngineLabActivity : Activity() {
    private lateinit var selectedLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var playButton: Button
    private var selectedUri: Uri? = null
    private var decodedWav: File? = null
    private lateinit var decoder: FfmpegAudioDecoder
    private lateinit var pcmPlayer: StandalonePcmPlayer
    private val diagnosticLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decoder = FfmpegAudioDecoder(this)
        pcmPlayer = StandalonePcmPlayer()
        title = "G6 Engine Lab"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "G6 Engine Lab · Build 1.28"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Standalone PCM pipeline test. First prove audible playback; no bit-perfect claim."
            textSize = 15f
            setPadding(0, dp(10), 0, dp(18))
        })
        selectedLabel = TextView(this).apply {
            text = "No audio file selected"
            textSize = 14f
            setPadding(0, 0, 0, dp(14))
        }
        content.addView(selectedLabel)

        content.addView(Button(this).apply {
            text = "1 · Choose audio file"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                }
                startActivityForResult(intent, REQUEST_AUDIO)
            }
        }, matchWrap())
        playButton = Button(this).apply {
            text = "2 · Decode and play"
            isEnabled = false
            setOnClickListener { decodeAndPlay() }
        }
        content.addView(playButton, matchWrap())
        content.addView(Button(this).apply {
            text = "Stop playback"
            setOnClickListener {
                pcmPlayer.stop()
                statusLabel.text = "Stopped by user."
                playButton.isEnabled = selectedUri != null
            }
        }, matchWrap())

        statusLabel = TextView(this).apply {
            text = "Ready. Select one local audio track to begin."
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(statusLabel)
        content.addView(Button(this).apply {
            text = "COPY DIAGNOSTICS AS TEXT"
            setOnClickListener {
                val text = diagnosticsText()
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("G6 Engine Lab diagnostics", text))
                Toast.makeText(this@EngineLabActivity, "Diagnostics copied as text", Toast.LENGTH_SHORT).show()
            }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "SHARE DIAGNOSTICS"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "G6 Engine Lab Build 1.28 diagnostics")
                    putExtra(Intent.EXTRA_TEXT, diagnosticsText())
                }
                startActivity(Intent.createChooser(send, "Share diagnostic text"))
            }
        }, matchWrap())
        recordDiagnostic("APP_START build=1.28 androidSdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MODEL}")
        setContentView(root)
    }

    @Deprecated("Uses the Android 9-compatible activity result API for this isolated test screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        decodedWav = null
        selectedLabel.text = "Selected: ${uri.lastPathSegment ?: uri}"
        recordDiagnostic("FILE_SELECTED displayName=${uri.lastPathSegment ?: "unknown"}")
        recordDiagnostic("READY_TO_DECODE source will be decoded to PCM16 WAV before custom PCM writer starts.")
        playButton.isEnabled = true
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
    }

    private fun decodeAndPlay() {
        val uri = selectedUri ?: return
        pcmPlayer.stop()
        playButton.isEnabled = false
        recordDiagnostic("DECODE_START target=PCM16_WAV uriDisplayName=${uri.lastPathSegment ?: "unknown"}")
        decoder.decodeToWav(uri) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { wav ->
                        decodedWav = wav
                        recordDiagnostic("DECODE_SUCCESS file=${wav.name} bytes=${wav.length()}; starting standalone PCM output")
                        pcmPlayer.play(wav) { message ->
                            runOnUiThread {
                                recordDiagnostic(message)
                                if (message.startsWith("Finished") || message.startsWith("Stopped") || message.startsWith("ERROR")) {
                                    playButton.isEnabled = selectedUri != null
                                }
                            }
                        }
                    },
                    onFailure = { error ->
                        recordDiagnostic("DECODE_ERROR type=${error.javaClass.name} message=${error.message ?: "no message"}")
                        playButton.isEnabled = true
                    }
                )
            }
        }
    }

    private fun recordDiagnostic(message: String) {
        val line = "${System.currentTimeMillis()} | ${message.replace('\\n', ' ')}"
        synchronized(diagnosticLog) {
            diagnosticLog.append(line).append('\\n')
            if (diagnosticLog.length > 40000) {
                val trimTo = diagnosticLog.indexOf("\\n", diagnosticLog.length - 30000)
                if (trimTo >= 0) diagnosticLog.delete(0, trimTo + 1)
            }
        }
        runOnUiThread { statusLabel.text = message }
    }

    private fun diagnosticsText(): String = synchronized(diagnosticLog) {
        "G6 Engine Lab Build 1.28 diagnostics\\n" + diagnosticLog.toString()
    }

    override fun onDestroy() {
        pcmPlayer.stop()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(8)
    }

    companion object { private const val REQUEST_AUDIO = 1250 }
}
