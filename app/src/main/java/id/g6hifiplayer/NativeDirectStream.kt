package id.g6hifiplayer

import android.content.Context

/**
 * Build 1.60: thin wrapper around the native DIRECT AudioTrack (libg6direct.so, g6direct.cpp).
 * Same method as UAPP: dlopen(NULL) + dlsym of android::AudioTrack, flags = DIRECT, format = 24-bit packed.
 */
internal object NativeDirectStream {
    @Volatile private var loadState = 0 // 0 = not tried, 1 = ready, -1 = unavailable
    @Volatile var loadError: String? = null
        private set

    fun ensureLoaded(): Boolean {
        if (loadState != 0) return loadState == 1
        synchronized(this) {
            if (loadState != 0) return loadState == 1
            loadState = try {
                System.loadLibrary("g6direct")
                if (available()) 1 else {
                    loadError = "native symbols missing: " + lastLog()
                    -1
                }
            } catch (t: Throwable) {
                loadError = "${t.javaClass.simpleName}: ${t.message}"
                -1
            }
            return loadState == 1
        }
    }

    external fun available(): Boolean
    external fun lastLog(): String
    external fun open(rate: Int, format: Int, flags: Int, bufferMs: Int): Long
    external fun start(handle: Long): Int
    external fun pause(handle: Long): Int
    external fun flush(handle: Long)
    external fun stop(handle: Long)
    external fun write(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    external fun position(handle: Long): Long
    external fun session(handle: Long): Int
    external fun routeInfo(handle: Long): String
    external fun close(handle: Long)
}

/** The Setting switch for the native DIRECT output (default ON; the player falls back to the normal AudioTrack on any failure). */
internal object DirectOutputSettings {
    private const val PREFS = "g6_output"
    private const val KEY = "native_direct"

    fun isEnabled(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    } catch (_: Exception) { true }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        } catch (_: Exception) { }
    }
}

/** What the native output is doing right now, for the Now Playing info line and the diagnostics text. */
internal object DirectOutputState {
    @Volatile var active: Boolean = false
    @Volatile var rate: Int = 0
    @Volatile var sourceLabel: String = ""   // e.g. "PCM 16-bit padded to 24-bit"
    @Volatile var route: String = ""         // AudioOut_xx rate flags session
    @Volatile var lastEvent: String = "Native DIRECT output has not been used yet."

    private val history = ArrayDeque<String>()

    fun log(message: String) {
        android.util.Log.i("G6Direct", message)
        synchronized(history) {
            history.addLast("${System.currentTimeMillis()} | $message")
            while (history.size > 60) history.removeFirst()
        }
        lastEvent = message
    }

    fun historyText(): String = synchronized(history) { history.joinToString("\n") }
}
