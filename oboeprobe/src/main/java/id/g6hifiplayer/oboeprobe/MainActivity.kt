package id.g6hifiplayer.oboeprobe

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var rateSpinner: Spinner
    private lateinit var output: TextView
    private val diagnosticLog = StringBuilder()
    private val createFileRequestCode = 1181

    external fun nativeOpen(sampleRate: Int, requestExclusive: Boolean): String
    external fun nativeStart(): String
    external fun nativeStop(): String
    external fun nativeClose(): String

    companion object {
        init {
            System.loadLibrary("oboe_probe")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "G6 Oboe Probe"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        root.addView(scroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        content.addView(TextView(this).apply {
            text = "Build 1.18 — Oboe diagnostics"
            textSize = 20f
        })
        content.addView(TextView(this).apply {
            text = "Separate test app. It does not replace Media3 or play music files. It outputs a quiet 440 Hz test tone. The report records each action and the negotiated stream properties; it cannot by itself prove DIRECT, bit-perfect output, or LG Quad DAC activation."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })

        rateSpinner = Spinner(this)
        rateSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("44100 Hz", "48000 Hz", "96000 Hz", "192000 Hz")
        )
        rateSpinner.setSelection(2)
        content.addView(rateSpinner)

        addButton(content, "Open EXCLUSIVE (fallback to SHARED)") {
            showResult(nativeOpen(selectedRate(), true))
        }
        addButton(content, "Open SHARED only") {
            showResult(nativeOpen(selectedRate(), false))
        }
        addButton(content, "Start 440 Hz test tone") { showResult(nativeStart()) }
        addButton(content, "Stop tone") { showResult(nativeStop()) }
        addButton(content, "Close stream") { showResult(nativeClose()) }
        addButton(content, "Save complete diagnostics as .txt") { requestSaveDiagnostics() }

        output = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(output)
        record(
            "probeStarted=true\n" +
                "appVersion=1.18.0-oboe-diagnostics\n" +
                "deviceManufacturer=${Build.MANUFACTURER}\n" +
                "deviceModel=${Build.MODEL}\n" +
                "androidRelease=${Build.VERSION.RELEASE}\n" +
                "androidSdk=${Build.VERSION.SDK_INT}\n" +
                "note=Connect wired headphones before opening the stream."
        )
    }

    private fun selectedRate(): Int = when (rateSpinner.selectedItemPosition) {
        0 -> 44100
        1 -> 48000
        2 -> 96000
        else -> 192000
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener {
                try {
                    action()
                } catch (e: Exception) {
                    showResult("UI error: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) })
    }

    private fun showResult(value: String) {
        record(value)
    }

    private fun record(value: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        diagnosticLog.append("\n=== ").append(stamp).append(" ===\n")
            .append(value).append("\n")
        if (::output.isInitialized) {
            output.text = diagnosticLog.toString()
        }
    }

    private fun requestSaveDiagnostics() {
        if (diagnosticLog.isEmpty()) {
            record("save=FAILED\nreason=no diagnostic data")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "G6OboeDiagnostics.txt")
        }
        try {
            startActivityForResult(intent, createFileRequestCode)
        } catch (e: Exception) {
            record("save=FAILED\nreason=${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Deprecated("Deprecated by Android API; retained for Android 9 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != createFileRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri == null) {
            record("save=FAILED\nreason=no destination selected")
            return
        }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(diagnosticLog.toString().toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Could not open selected destination")
            record("save=SUCCESS\nfileUri=$uri")
        } catch (e: Exception) {
            record("save=FAILED\nreason=${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
