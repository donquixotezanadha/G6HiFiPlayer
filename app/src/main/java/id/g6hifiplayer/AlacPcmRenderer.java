package id.g6hifiplayer;

import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import java.io.IOException;
import java.util.List;

/**
 * Hifi 1.28: plays ALAC files of 16 / 20 / 24 bits with [AlacPcmDecoder] instead of the phone's decoder (which cannot play hi-res
 * ALAC or cuts it to 16 bits). Placed before MediaCodecAudioRenderer in the renderer list, so it wins the tie for ALAC.
 */
@UnstableApi
final class AlacPcmRenderer extends DecoderAudioRenderer<AlacPcmDecoder> {

    private static final class Info {
        final int channels;
        final int bits;
        final int rate;
        final byte[] cookie;
        Info(int channels, int bits, int rate, byte[] cookie) {
            this.channels = channels; this.bits = bits; this.rate = rate; this.cookie = cookie;
        }
    }

    private Info lastInfo;

    AlacPcmRenderer(Handler eventHandler, AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "HifiAlacPcmRenderer";
    }

    private static Info info(Format format) {
        List<byte[]> init = format.initializationData;
        if (init == null || init.isEmpty() || init.get(0) == null) return null;
        byte[] cookie = init.get(0);   // ALACSpecificConfig, 24 bytes
        try {
            AlacFrameDecoder d = new AlacFrameDecoder(cookie);
            return new Info(d.channels(), d.sampleSize(), d.sampleRate(), cookie);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Format pcmFormat(Info info) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(info.channels)
                .setSampleRate(info.rate)
                .setPcmEncoding(C.ENCODING_PCM_24BIT)
                .build();
    }

    @Override
    protected @C.FormatSupport int supportsFormatInternal(Format format) {
        if (!MimeTypes.AUDIO_ALAC.equalsIgnoreCase(format.sampleMimeType)) return C.FORMAT_UNSUPPORTED_TYPE;
        Info info = info(format);
        if (info == null) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (info.bits < 16 || info.bits > 24 || info.channels < 1 || info.channels > 2) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (info.rate < 8000 || info.rate > 192000) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (!sinkSupportsFormat(pcmFormat(info))) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM;
        return C.FORMAT_HANDLED;
    }

    @Override
    protected AlacPcmDecoder createDecoder(Format format, CryptoConfig cryptoConfig) throws DecoderException {
        Info info = info(format);
        if (info == null) throw new DecoderException("ALAC cookie missing or invalid");
        lastInfo = info;
        DirectOutputState.INSTANCE.log("own ALAC decoder: " + info.rate + " Hz " + info.channels + " ch " + info.bits + "-bit");
        try {
            return new AlacPcmDecoder(info.cookie, format.maxInputSize);
        } catch (IOException e) {
            throw new DecoderException("ALAC decoder could not start: " + e.getMessage(), e);
        }
    }

    @Override
    protected Format getOutputFormat(AlacPcmDecoder decoder) {
        if (lastInfo == null) throw new IllegalStateException("ALAC cookie missing");
        return pcmFormat(lastInfo);
    }
}
