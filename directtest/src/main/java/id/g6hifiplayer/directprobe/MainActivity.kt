package id.g6hifiplayer.directprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        init { System.loadLibrary("aaudio_probe") }
    }

    private external fun nativeStart(sampleRate: Int, deviceId: Int, exclusive: Boolean): String
    private external fun nativeGetReport(): String
    private external fun nativeStop(): String

    private lateinit var audioManager: AudioManager
    private lateinit var statusView: TextView
    private var selectedRate = 96000
    private var exclusiveMode = true
    private var lastNativeReport = "No AAudio test started."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply {
            text = "G6 AAudio PCM16 Probe — Build 1.23"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        }, params())
        content.addView(TextView(this).apply {
            text = "Native AAudio test for LG G6 / Android 9. PCM16 stereo, 440 Hz low-level tone. This is an isolated diagnostic app; it does not replace or modify the main player's audio engine."
            textSize = 14f
        }, params(top = 8))

        content.addView(TextView(this).apply {
            text = "1. Choose sharing mode"
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
        }, params(top = 12))
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.addView(Button(this).apply {
            text = "Exclusive"
            setOnClickListener {
                exclusiveMode = true
                updateModeButtons(this, modes, exclusiveMode)
                statusView.text = "Selected EXCLUSIVE. Choose a sample rate to open a new AAudio stream."
            }
        }, weightParams())
        modes.addView(Button(this).apply {
            text = "Shared"
            setOnClickListener {
                exclusiveMode = false
                updateModeButtons(this, modes, exclusiveMode)
                statusView.text = "Selected SHARED. Choose a sample rate to open a new AAudio stream."
            }
        }, weightParams())
        content.addView(modes, params())
        updateModeButtons(null, modes, exclusiveMode)

        content.addView(TextView(this).apply {
            text = "2. Choose sample rate and start"
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
        }, params(top = 10))
        val rates = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(44100, 48000, 96000, 192000).forEach { rate ->
            rates.addView(Button(this).apply {
                text = when (rate) {
                    44100 -> "44.1k"
                    48000 -> "48k"
                    96000 -> "96k"
                    else -> "192k"
                }
                setOnClickListener { startTest(rate) }
            }, weightParams())
        }
        content.addView(rates, params())

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply {
            text = "Stop stream"
            setOnClickListener {
                lastNativeReport = try { nativeStop() } catch (e: Exception) { "nativeStop failed: ${e.message}" }
                refreshReport()
            }
        }, weightParams())
        controls.addView(Button(this).apply {
            text = "Refresh report"
            setOnClickListener { refreshReport() }
        }, weightParams())
        content.addView(controls, params(top = 8))

        content.addView(Button(this).apply {
            text = "Copy diagnostics"
            setOnClickListener {
                refreshReport()
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("G6 AAudio diagnostics", statusView.text))
                Toast.makeText(this@MainActivity, "Diagnostics copied", Toast.LENGTH_SHORT).show()
            }
        }, params(top = 4))

        statusView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Ready. Connect wired headphones, then test one rate at a time."
        }
        content.addView(statusView, params(top = 10))
        content.addView(TextView(this).apply {
            text = "\nTest protocol:\n• Connect the 3.5 mm headphones before each test.\n• Start with EXCLUSIVE at 44.1 kHz, then 48, 96, and 192 kHz.\n• If exclusive fails, repeat that rate in SHARED mode.\n• Stop each stream before switching modes/rates.\n• Copy the report after each result.\n\nThe report includes requested/actual sample rate, PCM format, sharing mode, device ID, frames per burst, buffer size, and xrun count. AAudio success alone does not prove DIRECT routing or bit-perfect output. Verify the active output thread with dumpsys media.audio_flinger."
            textSize = 13f
        }, params(top = 8))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshReport()
    }

    private fun startTest(rate: Int) {
        try { nativeStop() } catch (_: Exception) {}
        selectedRate = rate
        val wired = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            }
        } catch (_: Exception) { null }
        val deviceId = wired?.id ?: 0
        lastNativeReport = try {
            nativeStart(rate, deviceId, exclusiveMode)
        } catch (e: Exception) {
            "NATIVE START FAILED: ${e.javaClass.simpleName}: ${e.message}"
        }
        refreshReport()
    }

    private fun refreshReport() {
        if (!::statusView.isInitialized) return
        val native = try { nativeGetReport() } catch (e: Exception) { "nativeGetReport failed: ${e.message}" }
        val outputs = try { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (_: Exception) { emptyList() }
        val devices = if (outputs.isEmpty()) "  none reported" else outputs.joinToString("\n") { d ->
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
                else -> "type=${d.type}"
            }
            "  $kind id=${d.id} product=${d.productName}"
        }
        statusView.text = buildString {
            appendLine("APP: G6 AAudio PCM16 Probe 1.23.0")
            appendLine("ANDROID: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("requested rate: $selectedRate")
            appendLine("requested sharing mode: ${if (exclusiveMode) "EXCLUSIVE" else "SHARED"}")
            appendLine("wired device ID passed to AAudio: ${outputs.firstOrNull { it.id > 0 && (it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET) }?.id ?: "none; AAudio default route requested"}")
            appendLine()
            appendLine("NATIVE AAudio report:")
            appendLine(native.ifBlank { lastNativeReport })
            appendLine()
            appendLine("ANDROID OUTPUT DEVICES:")
            appendLine(devices)
            appendLine()
            appendLine("DIRECT/bit-perfect: NOT established by this test.")
            appendLine("Verify while tone is active: adb shell dumpsys media.audio_flinger")
            appendLine("Record this app PID's output thread type, thread sample rate, track format, and output flags.")
        }
    }

    private fun updateModeButtons(clicked: Button?, container: LinearLayout, exclusive: Boolean) {
        for (i in 0 until container.childCount) {
            val button = container.getChildAt(i) as? Button ?: continue
            button.alpha = if ((i == 0) == exclusive) 1.0f else 0.65f
        }
    }

    private fun params(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (top > 0) topMargin = dp(top)
        }

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { nativeStop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
