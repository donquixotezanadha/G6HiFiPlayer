package id.g6hifiplayer

/** Build 1.57: native android::AudioTrack DIRECT test, the same way UAPP reaches it (dlopen(NULL) + dlsym). */
object NativeDirect {
    external fun probe(): String
    external fun play(rate: Int, seconds: Int, flags: Int, format: Int): String
}
