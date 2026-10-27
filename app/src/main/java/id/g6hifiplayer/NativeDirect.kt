package id.g6hifiplayer

/** Build 1.57-1.59: native android::AudioTrack DIRECT test, the same way UAPP reaches it (dlopen(NULL) + dlsym). */
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

    // Build 1.59: automatic matrix, one native track at a time, so no adb dump is needed for a first answer.
    // Every variant logs NATIVE_PLAY_ROUTE (output handle, rate, flags). The DIRECT thread gets its own handle;
    // on this phone the mixer threads were AudioOut_D / AudioOut_15 / AudioOut_1D (handles 13 / 21 / 29).
    @Volatile
    private var matrixStop = false

    @Volatile
    var matrixRunning = false
        private set

    fun stopAll() {
        matrixStop = true
        stop()
    }

    fun runMatrix(log: (String) -> Unit) {
        if (matrixRunning) {
            log("NATIVE_MATRIX already running; press Stop first")
            return
        }
        matrixRunning = true
        matrixStop = false
        listener = log
        Thread {
            try {
                // rate, AUDIO_FORMAT (6 = PCM_24_BIT_PACKED, 1 = PCM_16_BIT)
                val variants = listOf(
                    Pair(96000, 6),   // reference: DIRECT in 1.58 (thread AudioOut_75)
                    Pair(44100, 6),   // CD rate padded to 24-bit
                    Pair(48000, 6),
                    Pair(88200, 6),
                    Pair(176400, 6),
                    Pair(192000, 6),
                    Pair(44100, 1),   // 16-bit at CD rate
                    Pair(48000, 1)    // 1.58 control: went to the mixer
                )
                log("NATIVE_MATRIX start: ${variants.size} variants, 6 s tone each, 4 s pause between")
                for ((i, v) in variants.withIndex()) {
                    if (matrixStop) break
                    log("NATIVE_MATRIX #${i + 1}/${variants.size} request rate=${v.first} format=${v.second} flags=1")
                    log(play(v.first, 6, 1, v.second))
                    var waited = 0
                    while (waited < 4000 && !matrixStop) {
                        Thread.sleep(200)
                        waited += 200
                    }
                }
                log(if (matrixStop) "NATIVE_MATRIX stopped by user" else "NATIVE_MATRIX finished")
            } catch (t: Throwable) {
                log("NATIVE_MATRIX ERROR ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                matrixRunning = false
            }
        }.start()
    }
}
