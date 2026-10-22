package id.g6hifiplayer.oboeprobe

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var rateSpinner: Spinner
    private lateinit var output: TextView

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
            text = "Build 1.17 — Oboe/AAudio path probe"
            textSize = 20f
        })
        content.addView(TextView(this).apply {
            text = "This is a separate test app. It does not replace Media3 or play your music files. It outputs a quiet 440 Hz test tone so the requested and actual Oboe stream properties can be checked safely."
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

        output = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
            text = "Choose a rate, then open the stream. Copy the diagnostics after starting the tone."
        }
        content.addView(output)
        setContentView(root)
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
        output.text = value
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
