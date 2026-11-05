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
    // Hifi 1.27: samples (per channel) decoded by the player's own FLAC decoder since the app started. The decoder thread is
    // the only writer. OutputMd5 compares it before and after a track to tell which decoder produced that track.
    @Volatile var ownDecodedSamples = 0L

    /**
     * Hifi 1.28: Media3's DefaultAudioSink fades the first 20 ms of every start / seek in (a volume ramp applied to the samples
     * themselves), which changes the music data. Every own decoder therefore puts this many silent frames in front of the first
     * block after a start or flush, so the ramp only ever touches silence; OutputMd5 skips exactly these frames. 20 ms plus one.
     */
    fun padFrames(rate: Int): Int = rate / 50 + 1

    fun resetDepthCheck() { activeSamples = 0L; lowByteSamples = 0L }

    /**
     * Hifi 1.24: MD5 check. [expected] describes the track that is playing now (set by MainActivity once its header was read).
     * OutputMd5 hashes the samples that reach the DIRECT track and, when a whole track has gone through, writes its verdict to
     * [checksumText]; [checksumSerial] counts the verdicts so the UI can show each one once.
     */
    class Expected(val label: String, val source: String, val md5: ByteArray?, val totalSamples: Long,
                   val bits: Int, val channels: Int, val rate: Int,
                   val refChunks: List<ByteArray>? = null, val refLowZero: Long = -1L, val refHead: ByteArray? = null) {
        val supported: Boolean get() = md5 != null && totalSamples > 0 && channels == 2 && (bits == 16 || bits == 24)
    }
    @Volatile var expected: Expected? = null
        set(value) { field = value; checksumResult = CHECK_NONE; checksumText = "" }
    /** Hifi 1.27: result of the MD5 check for the CURRENT track (reset when the track's expectation is set). */
    const val CHECK_NONE = 0        // not finished yet, or the track cannot be checked
    const val CHECK_MATCH = 1
    const val CHECK_DIFFERENT = 2
    @Volatile var checksumResult: Int = CHECK_NONE
    @Volatile var checksumOwnDecoder: Boolean = false   // the track just checked was decoded by the own FLAC decoder
    @Volatile var checksumText: String = ""
    @Volatile var checksumToast: String = ""
    @Volatile var checksumSerial: Int = 0

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
