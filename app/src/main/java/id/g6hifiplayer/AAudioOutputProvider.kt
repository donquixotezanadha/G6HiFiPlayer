package id.g6hifiplayer

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Experimental player-integrated AAudio backend.
 *
 * Only PCM16 stereo is routed to AAudio. Encoded audio, other PCM layouts, tunneling and
 * offload remain on Media3's normal AudioTrack output. AAudio's actual stream properties are
 * logged; opening an AAudio stream does not establish DIRECT or bit-perfect routing.
 */
@UnstableApi
internal class AAudioOutputProvider(
    private val fallback: AudioOutputProvider
) : AudioOutputProvider {
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        val eligible = format.pcmEncoding == C.ENCODING_PCM_16BIT &&
            format.channelCount == 2 &&
            !formatConfig.enableOffload &&
            !formatConfig.enableTunneling
        if (!eligible) return fallback.getFormatSupport(formatConfig)
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
            .build()
    }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        return fallback.getOutputConfig(formatConfig)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val eligible = config.encoding == C.ENCODING_PCM_16BIT &&
            config.channelMask == AudioFormat.CHANNEL_OUT_STEREO &&
            !config.isOffload &&
            !config.isTunneling
        if (!eligible) return fallback.getAudioOutput(config)
        return try {
            AAudioPcm16Output(config)
        } catch (error: Exception) {
            Log.w(TAG, "AAudio initialization failed; falling back to Media3 AudioTrack", error)
            fallback.getAudioOutput(config)
        }
    }

    override fun addListener(listener: AudioOutputProvider.Listener) = fallback.addListener(listener)
    override fun removeListener(listener: AudioOutputProvider.Listener) = fallback.removeListener(listener)
    override fun setClock(clock: androidx.media3.common.util.Clock) = fallback.setClock(clock)
    override fun release() = fallback.release()

    private class AAudioPcm16Output(
        private val config: AudioOutputProvider.OutputConfig
    ) : AudioOutput {
        private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
        private var released = false
        private var started = false
        private var lastPositionUs = 0L
        private val channels = Integer.bitCount(config.channelMask).coerceAtLeast(1)

        init {
            check(android.os.Build.VERSION.SDK_INT >= 26) { "AAudio requires Android 8.0 or newer" }
            val result = AAudioNative.nativeOpen(config.sampleRate, channels)
            check(result == 0) {
                "AAudio open failed ($result): ${AAudioNative.nativeReport()}"
            }
            Log.i(TAG, "AAUDIO_PLAYER_OUTPUT opened; requestedRate=${config.sampleRate}; ${AAudioNative.nativeReport()}")
        }

        @Synchronized
        override fun play() {
            if (released) return
            val result = AAudioNative.nativePlay()
            if (result == 0) started = true
            else Log.w(TAG, "AAudio play failed: $result")
        }

        @Synchronized
        override fun pause() {
            if (released) return
            val result = AAudioNative.nativePause()
            if (result == 0) started = false
            else Log.w(TAG, "AAudio pause failed: $result")
        }

        @Synchronized
        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            if (released) throw AudioOutput.WriteException(-1, false)
            if (!buffer.isDirect) throw AudioOutput.WriteException(-2, false)
            if (buffer.remaining() == 0) return true
            val frameBytes = channels * 2
            val writableBytes = buffer.remaining() - (buffer.remaining() % frameBytes)
            if (writableBytes <= 0) return false
            val frames = AAudioNative.nativeWrite(buffer, buffer.position(), writableBytes)
            if (frames < 0) {
                Log.e(TAG, "AAUDIO_PLAYER_OUTPUT write failed=$frames; ${AAudioNative.nativeReport()}")
                throw AudioOutput.WriteException(frames, true)
            }
            val bytesWritten = frames * frameBytes
            buffer.position((buffer.position() + bytesWritten).coerceAtMost(buffer.limit()))
            if (frames > 0 && lastPositionUs == 0L) {
                val now = System.currentTimeMillis()
                listeners.forEach { listener -> try { listener.onPositionAdvancing(now) } catch (_: Exception) { } }
            }
            return !buffer.hasRemaining()
        }

        @Synchronized
        override fun flush() {
            if (released) return
            val result = AAudioNative.nativeFlush()
            lastPositionUs = 0L
            if (result != 0) Log.w(TAG, "AAudio flush result=$result; ${AAudioNative.nativeReport()}")
        }

        @Synchronized
        override fun stop() {
            if (released) return
            val result = AAudioNative.nativeStop()
            if (result != 0) Log.w(TAG, "AAudio stop result=$result")
            started = false
        }

        @Synchronized
        override fun release() {
            if (released) return
            released = true
            try { AAudioNative.nativeRelease() } catch (error: Exception) {
                Log.w(TAG, "AAudio release failed", error)
            }
            listeners.forEach { listener -> try { listener.onReleased() } catch (_: Exception) { } }
            listeners.clear()
        }

        override fun setVolume(volume: Float) {
            // AAudio has no stream-volume API. Keep PCM samples untouched for the test backend.
        }

        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
        override fun getSampleRate() = AAudioNative.nativeSampleRate().takeIf { it > 0 } ?: config.sampleRate
        override fun getBufferSizeInFrames() = AAudioNative.nativeBufferSizeFrames().toLong().coerceAtLeast(0L)

        @Synchronized
        override fun getPositionUs(): Long {
            if (released) return lastPositionUs
            val rate = getSampleRate().coerceAtLeast(1)
            val frames = AAudioNative.nativeFramesWritten().coerceAtLeast(0L)
            val position = frames * 1_000_000L / rate
            lastPositionUs = maxOf(lastPositionUs, position)
            return lastPositionUs
        }

        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) { listeners.add(listener) }
        override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) { }
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) { }
        override fun setOffloadEndOfStream() { }
        override fun setPlayerId(playerId: PlayerId) { }
        override fun attachAuxEffect(effectId: Int) { }
        override fun setAuxEffectSendLevel(sendLevel: Float) { }

        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
            if (preferredDevice != null) {
                Log.i(TAG, "AAudio output uses AAudio default routing; preferred device requested id=${preferredDevice.id}, actual device id=${AAudioNative.nativeDeviceId()}")
            }
        }
    }

    private companion object {
        const val TAG = "G6AAudio"
    }
}
