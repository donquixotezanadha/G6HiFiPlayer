package id.g6hifiplayer

/** JNI bridge for the experimental API-26+ AAudio PCM16 output. */
internal object AAudioNative {
    init {
        System.loadLibrary("g6aaudio")
    }

    external fun nativeOpen(sampleRate: Int, channelCount: Int): Int
    external fun nativeWrite(buffer: java.nio.ByteBuffer, offsetBytes: Int, sizeBytes: Int): Int
    external fun nativePlay(): Int
    external fun nativePause(): Int
    external fun nativeFlush(): Int
    external fun nativeStop(): Int
    external fun nativeRelease()
    external fun nativeSampleRate(): Int
    external fun nativeChannelCount(): Int
    external fun nativeDeviceId(): Int
    external fun nativeSharingMode(): Int
    external fun nativeFramesWritten(): Long
    external fun nativeBufferSizeFrames(): Int
    external fun nativeXRunCount(): Int
    external fun nativeReport(): String
}
