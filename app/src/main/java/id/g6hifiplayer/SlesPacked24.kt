package id.g6hifiplayer

/**
 * Build 1.38: native OpenSL ES output for 24-bit packed PCM (AUDIO_FORMAT_PCM_24_BIT_PACKED).
 * Java AudioTrack on Android 9 rejects encoding 21, and libaudioclient/libmedia cannot be dlopen'ed,
 * but libOpenSLES is a public NDK library and its 24-bit signed PCM source maps to the same native
 * track format that UAPP uses on the G6 DIRECT output.
 */
object SlesPacked24 {
    @Volatile private var loaded = false

    fun ensureLoaded(report: (String) -> Unit): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("g6sles")
            loaded = true
            true
        } catch (e: Throwable) {
            report("SLES_LOAD_FAILED ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    external fun nativeOpen(rate: Int, channels: Int): String
    external fun nativeOpenEx(rate: Int, channels: Int, bits: Int, perfMode: Int): String
    external fun nativeWrite(data: ByteArray, offset: Int, length: Int): Int
    external fun nativePlay(): Int
    external fun nativeFramesPlayed(): Long
    external fun nativeAbort()
    external fun nativeClose()
}
