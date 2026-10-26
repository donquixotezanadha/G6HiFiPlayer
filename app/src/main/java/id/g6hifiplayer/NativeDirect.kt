package id.g6hifiplayer

/** Build 1.57/1.58: native android::AudioTrack DIRECT test, the same way UAPP reaches it (dlopen(NULL) + dlsym). */
object NativeDirect {
    external fun probe(): String
    external fun play(rate: Int, seconds: Int, flags: Int, format: Int): String
    external fun stop()

    /** Set by the activity; native code calls report() from the play thread while the tone is still running. */
    @Volatile
    var listener: ((String) -> Unit)? = null

    @JvmStatic
    fun report(text: String) {
        listener?.invoke(text)
    }
}
