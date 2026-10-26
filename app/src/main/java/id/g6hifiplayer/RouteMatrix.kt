package id.g6hifiplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Build 1.56: route matrix. Plays a quiet 1 kHz tone through many public-API output variants, one after another,
 * so a single `dumpsys media.audio_flinger` polling run on the computer shows which variant (if any) makes the
 * G6 policy open a DIRECT output thread instead of the 48 kHz / 16-bit MIXER thread seen in the Build 1.38 dump.
 * No audio file is needed. Every variant writes START/END lines (wall-clock ms) to the diagnostics log.
 */
internal class RouteMatrix {
    private class Variant(val id: String, val kind: String, val rate: Int, val bits: Int, val perf: Int)

    @Volatile private var stop = false
    @Volatile private var running = false
    private var thread: Thread? = null

    // kind: SLES (OpenSL ES, bits 16/24, perf 0 none-config,1 NONE,2 POWER_SAVING,3 LATENCY,4 LATENCY_EFFECTS)
    //       JAVA (AudioTrack.Builder, bits 16 = PCM16, 32 = PCM_FLOAT, perf 0 NONE,1 POWER_SAVING,2 LOW_LATENCY, 3 = HW_AV_SYNC flag)
    private val variants = listOf(
        Variant("A1", "SLES", 44100, 16, 1),
        Variant("A2", "SLES", 48000, 16, 1),
        Variant("A3", "SLES", 96000, 16, 1),
        Variant("B1", "SLES", 44100, 24, 1),
        Variant("B2", "SLES", 48000, 24, 1),
        Variant("B3", "SLES", 96000, 24, 1),
        Variant("B4", "SLES", 192000, 24, 1),
        Variant("C1", "SLES", 96000, 24, 0),
        Variant("C2", "SLES", 96000, 24, 2),
        Variant("C3", "SLES", 96000, 24, 3),
        Variant("C4", "SLES", 96000, 24, 4),
        Variant("D1", "JAVA", 96000, 16, 0),
        Variant("D2", "JAVA", 96000, 32, 0),
        Variant("D3", "JAVA", 96000, 32, 1),
        Variant("D4", "JAVA", 96000, 16, 3),
    )

    fun isRunning() = running

    fun stop() { stop = true; try { SlesPacked24.nativeAbort() } catch (_: Throwable) { } }

    fun start(secondsPerVariant: Int, report: (String) -> Unit) {
        if (running) return
        stop = false
        running = true
        thread = Thread({
            try {
                report("MATRIX_BEGIN variants=${variants.size} secondsEach=$secondsPerVariant")
                val slesOk = SlesPacked24.ensureLoaded(report)
                for (v in variants) {
                    if (stop) break
                    report("MATRIX_VARIANT id=${v.id} kind=${v.kind} rate=${v.rate} bits=${v.bits} perf=${v.perf} START")
                    try {
                        if (v.kind == "SLES") { if (slesOk) runSles(v, secondsPerVariant, report) else report("MATRIX_VARIANT id=${v.id} SKIPPED native library not loaded") }
                        else runJava(v, secondsPerVariant, report)
                    } catch (t: Throwable) {
                        report("MATRIX_VARIANT id=${v.id} ERROR ${t.javaClass.simpleName}: ${t.message}")
                    }
                    report("MATRIX_VARIANT id=${v.id} END")
                    if (!stop) Thread.sleep(1200)  // gap so the output thread of the previous variant is easy to tell apart
                }
                report("MATRIX_DONE" + if (stop) " (stopped early)" else "")
            } finally {
                running = false
            }
        }, "route-matrix").also { it.start() }
    }

    private fun tone(frames: Int, rate: Int, phase: DoubleArray): FloatArray {
        val out = FloatArray(frames)
        val step = 2.0 * PI * 1000.0 / rate
        var p = phase[0]
        for (i in 0 until frames) { out[i] = (0.03 * sin(p)).toFloat(); p += step }
        phase[0] = p % (2.0 * PI)
        return out
    }

    private fun runSles(v: Variant, seconds: Int, report: (String) -> Unit) {
        val status = SlesPacked24.nativeOpenEx(v.rate, 2, v.bits, v.perf)
        report("MATRIX_VARIANT id=${v.id} SLES_OPEN $status")
        if (!status.startsWith("OK")) return
        val bytesPerSample = v.bits / 8
        val frames = 4096
        val phase = doubleArrayOf(0.0)
        val buf = ByteArray(frames * 2 * bytesPerSample)
        SlesPacked24.nativePlay()
        val endAt = System.currentTimeMillis() + seconds * 1000L
        try {
            while (!stop && System.currentTimeMillis() < endAt) {
                val t = tone(frames, v.rate, phase)
                var o = 0
                for (i in 0 until frames) {
                    val x = t[i]
                    repeat(2) {
                        if (v.bits == 16) {
                            val s = (x * 32767f).toInt()
                            buf[o++] = (s and 0xFF).toByte(); buf[o++] = ((s shr 8) and 0xFF).toByte()
                        } else {
                            val s = (x * 8388607f).toInt()
                            buf[o++] = (s and 0xFF).toByte(); buf[o++] = ((s shr 8) and 0xFF).toByte(); buf[o++] = ((s shr 16) and 0xFF).toByte()
                        }
                    }
                }
                val w = SlesPacked24.nativeWrite(buf, 0, buf.size)
                if (w < 0) { report("MATRIX_VARIANT id=${v.id} WRITE_RESULT $w"); break }
            }
        } finally {
            SlesPacked24.nativeClose()
        }
    }

    private fun runJava(v: Variant, seconds: Int, report: (String) -> Unit) {
        val encoding = if (v.bits == 32) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply { if (v.perf == 3) setFlags(16 /* AudioAttributes.FLAG_HW_AV_SYNC, hidden constant */) }
            .build()
        val fmt = AudioFormat.Builder().setSampleRate(v.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(encoding).build()
        val minBytes = AudioTrack.getMinBufferSize(v.rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes * 4, 16384))
            .setPerformanceMode(when (v.perf) { 1 -> AudioTrack.PERFORMANCE_MODE_POWER_SAVING; 2 -> AudioTrack.PERFORMANCE_MODE_LOW_LATENCY; else -> AudioTrack.PERFORMANCE_MODE_NONE })
            .build()
        report("MATRIX_VARIANT id=${v.id} JAVA_OPEN state=${track.state} rate=${track.sampleRate} format=${track.audioFormat} perf=${track.performanceMode} bufferFrames=${track.bufferSizeInFrames} routed=${track.routedDevice?.type}")
        if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); return }
        val frames = 4096
        val phase = doubleArrayOf(0.0)
        try {
            track.play()
            val endAt = System.currentTimeMillis() + seconds * 1000L
            while (!stop && System.currentTimeMillis() < endAt) {
                val t = tone(frames, v.rate, phase)
                if (v.bits == 32) {
                    val st = FloatArray(frames * 2) { t[it / 2] }
                    if (track.write(st, 0, st.size, AudioTrack.WRITE_BLOCKING) < 0) break
                } else {
                    val st = ShortArray(frames * 2) { (t[it / 2] * 32767f).toInt().toShort() }
                    if (track.write(st, 0, st.size, AudioTrack.WRITE_BLOCKING) < 0) break
                }
            }
        } finally {
            try { track.stop() } catch (_: Throwable) { }
            track.release()
        }
    }
}
