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
import java.util.List;

/**
 * Hifi iTunes 1: the ONLY audio renderer of this build. It plays AAC-LC (iTunes / Apple Music style .m4a, mono or stereo) with
 * [AacPcmDecoder] straight into the player's direct output. HE-AAC, AAC with more than two channels and every other format
 * have no renderer and are refused by the strict mode.
 */
@UnstableApi
final class AacPcmRenderer extends DecoderAudioRenderer<AacPcmDecoder> {

    private int lastRate;
    private int lastChannels;

    AacPcmRenderer(Handler eventHandler, AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "HifiAacPcmRenderer";
    }

    private static byte[] config(Format format) {
        List<byte[]> init = format.initializationData;
        if (init == null || init.isEmpty() || init.get(0) == null || init.get(0).length < 2) return null;
        return init.get(0);
    }

    /** audioObjectType is the first 5 bits of the AudioSpecificConfig; 2 = AAC-LC. */
    private static int objectType(byte[] asc) {
        return (asc[0] & 0xFF) >> 3;
    }

    private static Format pcmFormat(int rate, int channels) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(channels)
                .setSampleRate(rate)
                .setPcmEncoding(C.ENCODING_PCM_24BIT)
                .build();
    }

    @Override
    protected @C.FormatSupport int supportsFormatInternal(Format format) {
        if (!MimeTypes.AUDIO_AAC.equalsIgnoreCase(format.sampleMimeType)) return C.FORMAT_UNSUPPORTED_TYPE;
        byte[] asc = config(format);
        if (asc == null) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (objectType(asc) != 2) return C.FORMAT_UNSUPPORTED_SUBTYPE;   // AAC-LC only (no HE-AAC / PS / Main / LTP ...)
        if (format.channelCount < 1 || format.channelCount > 2) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (format.sampleRate < 8000 || format.sampleRate > 96000) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (!sinkSupportsFormat(pcmFormat(format.sampleRate, format.channelCount))) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM;
        return C.FORMAT_HANDLED;
    }

    @Override
    protected AacPcmDecoder createDecoder(Format format, CryptoConfig cryptoConfig) throws DecoderException {
        byte[] asc = config(format);
        if (asc == null) throw new DecoderException("AAC AudioSpecificConfig missing");
        lastRate = format.sampleRate;
        lastChannels = format.channelCount;
        DirectOutputState.INSTANCE.log("own AAC decoder: " + format.sampleRate + " Hz " + format.channelCount + " ch AAC-LC -> 24-bit");
        try {
            return new AacPcmDecoder(asc, format.sampleRate, format.channelCount, format.maxInputSize);
        } catch (Exception e) {
            throw new DecoderException("AAC decoder could not start: " + e.getMessage(), e);
        }
    }

    @Override
    protected Format getOutputFormat(AacPcmDecoder decoder) {
        int rate = decoder.outputRate() > 0 ? decoder.outputRate() : lastRate;
        int channels = decoder.outputChannels() > 0 ? decoder.outputChannels() : lastChannels;
        return pcmFormat(rate, channels);
    }
}
