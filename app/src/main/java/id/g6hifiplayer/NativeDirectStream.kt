package id.g6hifiplayer

/**
 * Build 1.60/1.61: thin wrapper around the native DIRECT AudioTrack (libg6direct.so, g6direct.cpp).
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

/** What the native output is doing right now, for the Now Playing info line and the diagnostics text. */
internal object DirectOutputState {
    @Volatile var active: Boolean = false
    @Volatile var rate: Int = 0
    @Volatile var sourceLabel: String = ""   // e.g. "PCM 16-bit padded to 24-bit"
    @Volatile var inputBits: Int = 0         // bit depth of the PCM that reaches the output (16, 24 or 32)
    @Volatile var route: String = ""         // AudioOut_xx rate flags session
    @Volatile var lastEvent: String = "Native DIRECT output has not been used yet."

    // Build 1.65: is the data that reaches the output really 24-bit? Counted on the converted 24-bit samples:
    // a 16-bit signal padded to 24 bits (or a decoder that only gives 16 bits) always has an empty low byte.
    @Volatile var activeSamples = 0L     // samples that are not exactly 0
    @Volatile var lowByteSamples = 0L    // samples whose low 8 bits are not 0

    fun resetDepthCheck() { activeSamples = 0L; lowByteSamples = 0L }

    /**
     * 0 = not enough audio yet, 1 = the low 8 bits carry data (real 24-bit), 2 = low 8 bits empty so far.
     * Build 1.66: "empty" is only claimed after about 15 s of audio (3 million non-zero samples), not from a quiet intro,
     * and the counting goes on after that, so a later passage with real low bits corrects the answer.
     */
    fun depthVerdict(): Int = when {
        lowByteSamples >= 200L -> 1
        activeSamples >= 3_000_000L -> 2
        else -> 0
    }

    /**
     * Build 1.66: is the native track on a DIRECT output thread? Read from the route text ("... flags=0x1 ...").
     * true = DIRECT flag read, false = flags read and not DIRECT (mixer), null = flags could not be read.
     */
    fun routeIsDirect(): Boolean? {
        val m = Regex("flags=0x([0-9a-fA-F]+)").find(route) ?: return null
        val v = m.groupValues[1].toLongOrNull(16) ?: return null
        if (v == 0xFFFFFC19L) return null // getFlags was not resolvable (native placeholder -999)
        return (v and 1L) != 0L
    }

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
