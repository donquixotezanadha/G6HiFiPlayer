package id.g6hifiplayer.directprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

class MainActivity : Activity() {
    companion object {
        private const val TAG = "G6AudioTrackProbe"
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = 6 // stereo, packed 24-bit: 3 bytes per channel
    }

    @Volatile private var writing = false
    @Volatile private var activeTrack: AudioTrack? = null
    private var writerThread: Thread? = null
    private lateinit var statusView: TextView
    private lateinit var audioManager: AudioManager
    private var lastReport = "No test started."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = "G6 AudioTrack Probe — Build 1.20"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(title, params())
        content.addView(TextView(this).apply {
            text = "Android 9 / PCM_24BIT_PACKED / stereo. Start one low-level 440 Hz test tone, then inspect the active AudioFlinger track. Stop the tone before unplugging headphones."
            textSize = 14f
        }, params(top = 8))

        val rates = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(44100, 48000, 96000, 192000).forEach { rate ->
            rates.addView(Button(this).apply {
                text = if (rate == 192000) "192k" else "${rate / 1000.0}k".replace(".0", "")
                setOnClickListener { startTest(rate) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(2), dp(10), dp(2), dp(4))
            })
        }
        content.addView(rates, params())

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { stopTest("Stopped by user.") }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(4), dp(4), dp(4)) })
        controls.addView(Button(this).apply {
            text = "Refresh status"
            setOnClickListener { refreshReport() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(4), dp(2), dp(4)) })
        content.addView(controls, params())

        content.addView(Button(this).apply {
            text = "Copy diagnostics"
            setOnClickListener {
                refreshReport()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("G6 AudioTrack Probe diagnostics", lastReport))
                Toast.makeText(this@MainActivity, "Diagnostics copied", Toast.LENGTH_SHORT).show()
            }
        }, params(top = 4))

        statusView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Ready. Connect wired headphones, select a rate, and start the test."
        }
        content.addView(statusView, params(top = 10))
        content.addView(TextView(this).apply {
            text = "\nInterpretation:\n• AudioTrack initialized = the stream opened, not proof of DIRECT.\n• PCM format and sample rate shown here are the track's reported properties.\n• Confirm DIRECT/output rate with: adb shell dumpsys media.audio_flinger\n• Compare with Stock Music playing the same 96 kHz file.\n• Keep system volume low; the test tone is intentionally low amplitude."
            textSize = 13f
        }, params(top = 8))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshReport()
    }

    private fun startTest(rate: Int) {
        stopTest("Previous test stopped.")
        val encoding = AudioFormat.ENCODING_PCM_24BIT_PACKED
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        if (minBytes <= 0) {
            statusView.text = "FAILED: getMinBufferSize returned $minBytes for rate=$rate, encoding=PCM_24BIT_PACKED."
            lastReport = statusView.text.toString()
            Log.e(TAG, lastReport)
            return
        }

        val bufferBytes = max(minBytes, 1024 * BYTES_PER_FRAME)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            statusView.text = "FAILED to build AudioTrack: ${e.javaClass.simpleName}: ${e.message}"
            lastReport = statusView.text.toString()
            Log.e(TAG, lastReport, e)
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            statusView.text = "FAILED: AudioTrack state=${track.state}; requested rate=$rate, format=PCM_24BIT_PACKED."
            lastReport = statusView.text.toString()
            Log.e(TAG, lastReport)
            try { track.release() } catch (_: Exception) {}
            return
        }

        activeTrack = track
        writing = true
        try {
            track.play()
        } catch (e: Exception) {
            writing = false
            activeTrack = null
            try { track.release() } catch (_: Exception) {}
            statusView.text = "FAILED to start playback: ${e.javaClass.simpleName}: ${e.message}"
            lastReport = statusView.text.toString()
            Log.e(TAG, lastReport, e)
            return
        }

        writerThread = Thread({
            val pcm = ByteArray(1024 * BYTES_PER_FRAME)
            var phase = 0.0
            val step = 2.0 * PI * 440.0 / rate
            try {
                while (writing && activeTrack === track) {
                    var offset = 0
                    for (frame in 0 until 1024) {
                        val sample = (sin(phase) * 0.02 * 8388607.0).toInt()
                        phase += step
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
                        for (channel in 0 until CHANNELS) {
                            pcm[offset++] = (sample and 0xFF).toByte()
                            pcm[offset++] = ((sample shr 8) and 0xFF).toByte()
                            pcm[offset++] = ((sample shr 16) and 0xFF).toByte()
                        }
                    }
                    val written = track.write(pcm, 0, pcm.size)
                    if (written < 0) {
                        Log.e(TAG, "WRITE_FAILED code=$written requestedRate=$rate")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Writer stopped: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
                if (activeTrack === track) activeTrack = null
            }
        }, "G6AudioTrackWriter").apply { isDaemon = true }
        writerThread?.start()

        Log.i(TAG, "TEST_STARTED requestedRate=$rate encoding=$encoding(PCM_24BIT_PACKED) channelMask=STEREO bufferBytes=$bufferBytes")
        refreshReport()
    }

    private fun stopTest(message: String) {
        writing = false
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            try { track.stop() } catch (_: Exception) {}
        }
        writerThread = null
        if (::statusView.isInitialized) {
            statusView.text = "$message\n\n" + buildReport()
            lastReport = statusView.text.toString()
        }
    }

    private fun refreshReport() {
        if (!::statusView.isInitialized) return
        statusView.text = buildReport()
        lastReport = statusView.text.toString()
    }

    private fun buildReport(): String {
        val track = activeTrack
        val lines = mutableListOf<String>()
        lines += "APP: G6 AudioTrack Probe 1.20.0"
        lines += "ANDROID: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        lines += "TEST: ${if (track == null) "NOT PLAYING" else "PLAYING"}"
        if (track != null) {
            lines += "requested sample rate: see TEST_STARTED log / selected button"
            lines += "track state: ${track.state} (1=INITIALIZED)"
            lines += "play state: ${track.playState} (3=PLAYING)"
            lines += "track sample rate: ${track.sampleRate}"
            lines += "track format: 0x${track.audioFormat.toString(16)}"
            lines += "track channel count: ${track.channelCount}"
            lines += "buffer size frames: ${track.bufferSizeInFrames}"
            lines += "audio session ID: ${track.audioSessionId}"
            val routed = try { track.routedDevice } catch (_: Exception) { null }
            lines += "routed device: ${routed?.let { deviceLabel(it) } ?: "null / not reported"}"
        }
        lines += ""
        lines += "OUTPUT DEVICES:"
        val outputs = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (_: Exception) { emptyList() }
        if (outputs.isEmpty()) lines += "  none reported"
        outputs.forEach { lines += "  ${deviceLabel(it)}" }
        lines += ""
        lines += "DIRECT: not exposed by public AudioTrack properties."
        lines += "Verify with: adb shell dumpsys media.audio_flinger"
        lines += "Look for this app's track, PCM_24_BIT_PACKED/format 0x6, 96000 Hz and DIRECT output flag/thread."
        lines += "A same-rate track alone does not prove bit-perfect or physical DAC routing."
        return lines.joinToString("\n")
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            else -> "type=${device.type}"
        }
        return "$type id=${device.id} product=${device.productName}"
    }

    private fun params(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (top > 0) topMargin = dp(top)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopTest("Activity closed; test stopped.")
        super.onDestroy()
    }
}
