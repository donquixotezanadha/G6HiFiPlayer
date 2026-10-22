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
import java.io.File

class MainActivity : Activity() {
    companion object {
        private const val TAG = "G6AudioTrackProbe"
        private const val CHANNELS = 2
    }

    private var selectedEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED
    private var selectedRate = 96000
    private var lastPreferredRouteResult = "not attempted"
    private var lastPolicyAudit = "Not scanned yet"

    private fun bytesPerFrame(encoding: Int): Int =
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) 4 else 6

    private fun encodingLabel(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24_PACKED"
        else -> "encoding=$encoding"
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
            text = "G6 AudioTrack Policy Audit — Build 1.21"
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
                setOnClickListener { startTest(rate, selectedEncoding) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(2), dp(10), dp(2), dp(4))
            })
        }
        content.addView(rates, params())

        content.addView(TextView(this).apply {
            text = "Format comparison: PCM16 control versus PCM24 packed candidate"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }, params(top = 8))
        val formats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        formats.addView(Button(this).apply {
            text = "Use PCM16"
            setOnClickListener {
                selectedEncoding = AudioFormat.ENCODING_PCM_16BIT
                statusView.text = "Selected PCM16. Choose a sample rate to start a fresh test."
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(4), dp(4), dp(4)) })
        formats.addView(Button(this).apply {
            text = "Use PCM24"
            setOnClickListener {
                selectedEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED
                statusView.text = "Selected PCM24 packed. Android 9 support is device/firmware dependent."
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), dp(4), dp(2), dp(4)) })
        content.addView(formats, params())

        content.addView(Button(this).apply {
            text = "Audit audio policy files"
            setOnClickListener {
                lastPolicyAudit = auditPolicyFiles()
                refreshReport()
            }
        }, params(top = 4))

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

    private fun startTest(rate: Int, encoding: Int) {
        stopTest("Previous test stopped.")
        selectedRate = rate
        selectedEncoding = encoding
        lastPreferredRouteResult = "not attempted"
        val minBytes = try {
            AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        } catch (e: Exception) {
            statusView.text = "FAILED: getMinBufferSize threw ${e.javaClass.simpleName}: ${e.message} for rate=$rate, format=${encodingLabel(encoding)}."
            lastReport = statusView.text.toString()
            Log.e(TAG, lastReport, e)
            return
        }
        if (minBytes <= 0) {
            statusView.text = "UNSUPPORTED/REJECTED: getMinBufferSize returned $minBytes for rate=$rate, format=${encodingLabel(encoding)}. No AudioTrack was opened."
            lastReport = statusView.text.toString()
            Log.e(TAG, lastReport)
            return
        }

        val frameBytes = bytesPerFrame(encoding)
        val bufferBytes = max(minBytes, 1024 * frameBytes)
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

        val wired = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
        } catch (_: Exception) { null }
        lastPreferredRouteResult = if (wired == null) {
            "no wired headphone device found"
        } else {
            try { "setPreferredDevice(${deviceLabel(wired)})=${track.setPreferredDevice(wired)}" }
            catch (e: Exception) { "setPreferredDevice failed: ${e.javaClass.simpleName}: ${e.message}" }
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
            val pcm = ByteArray(1024 * frameBytes)
            var phase = 0.0
            val step = 2.0 * PI * 440.0 / rate
            val peak = if (encoding == AudioFormat.ENCODING_PCM_16BIT) 32767.0 else 8388607.0
            try {
                while (writing && activeTrack === track) {
                    var offset = 0
                    for (frame in 0 until 1024) {
                        val sample = (sin(phase) * 0.02 * peak).toInt()
                        phase += step
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
                        for (channel in 0 until CHANNELS) {
                            pcm[offset++] = (sample and 0xFF).toByte()
                            pcm[offset++] = ((sample shr 8) and 0xFF).toByte()
                            if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
                                pcm[offset++] = ((sample shr 16) and 0xFF).toByte()
                            }
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
        lines += "APP: G6 AudioTrack Policy Audit 1.21.0"
        lines += "ANDROID: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        lines += "TEST: ${if (track == null) "NOT PLAYING" else "PLAYING"}"
        lines += "requested sample rate: $selectedRate"
        lines += "requested format: ${encodingLabel(selectedEncoding)}"
        lines += "preferred wired route request: $lastPreferredRouteResult"
        if (track != null) {
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
        lines += ""
        lines += "POLICY FILE AUDIT:"
        lines += lastPolicyAudit
        lines += ""
        lines += "DIRECT: not exposed by public AudioTrack properties."
        lines += "This test does NOT set AUDIO_OUTPUT_FLAG_DIRECT; AudioPolicy chooses the output path."
        lines += "Verify with: adb shell dumpsys media.audio_flinger"
        lines += "Check this app's PID/session, active thread type/rate, track format, and output flags."
        lines += "A same-rate track or wired route alone does not prove bit-perfect or physical DAC routing."
        return lines.joinToString("\n")
    }

    private fun auditPolicyFiles(): String {
        val paths = listOf(
            "/vendor/etc/audio_output_policy.conf",
            "/system/vendor/etc/audio_output_policy.conf",
            "/vendor/etc/audio_policy.conf",
            "/system/vendor/etc/audio_policy.conf",
            "/vendor/etc/audio_policy_configuration.xml",
            "/system/vendor/etc/audio_policy_configuration.xml"
        )
        val out = mutableListOf<String>()
        val needles = listOf("direct_pcm", "AUDIO_OUTPUT_FLAG_DIRECT", "pcm_24", "pcm_8_24",
            "sampling_rates", "samplingrates", "96000", "192000", "Wired Headphones")
        for (path in paths) {
            try {
                val file = File(path)
                if (!file.isFile || !file.canRead()) {
                    out += "$path: not readable by app"
                    continue
                }
                val lines = file.readLines()
                val hits = lines.indices.filter { index ->
                    needles.any { needle -> lines[index].contains(needle, ignoreCase = true) }
                }
                out += "$path: readable, ${lines.size} lines, ${hits.size} matching lines"
                val selected = hits.take(12).flatMap { index ->
                    (max(0, index - 1)..minOf(lines.lastIndex, index + 1)).map { lineIndex ->
                        "${lineIndex + 1}: ${lines[lineIndex].trim()}"
                    }
                }.distinct().take(24)
                if (selected.isNotEmpty()) out += selected.map { "  $it" }
            } catch (e: Exception) {
                out += "$path: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        if (out.isEmpty()) out += "No candidate policy files found."
        return out.joinToString("\n")
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
