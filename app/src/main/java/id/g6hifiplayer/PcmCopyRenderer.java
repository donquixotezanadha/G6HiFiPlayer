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

/**
 * Hifi 1.28: plays WAV (little-endian 16 / 24-bit PCM, mono or stereo, 8 to 192 kHz) by passing the samples straight through
 * [PcmCopyDecoder]. Anything else that arrives as raw PCM (float, 32-bit, big-endian, 8-bit) is reported as unsupported.
 */
@UnstableApi
final class PcmCopyRenderer extends DecoderAudioRenderer<PcmCopyDecoder> {

    PcmCopyRenderer(Handler eventHandler, AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "HifiPcmCopyRenderer";
    }

    private static int bytesPerSample(int pcmEncoding) {
        if (pcmEncoding == C.ENCODING_PCM_16BIT) return 2;
        if (pcmEncoding == C.ENCODING_PCM_24BIT) return 3;
        return 0;
    }

    @Override
    protected @C.FormatSupport int supportsFormatInternal(Format format) {
        if (!MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) return C.FORMAT_UNSUPPORTED_TYPE;
        if (bytesPerSample(format.pcmEncoding) == 0) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (format.channelCount < 1 || format.channelCount > 2) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (format.sampleRate < 8000 || format.sampleRate > 192000) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (!sinkSupportsFormat(outputFormat(format))) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM;
        return C.FORMAT_HANDLED;
    }

    private static Format outputFormat(Format in) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(in.channelCount)
                .setSampleRate(in.sampleRate)
                .setPcmEncoding(in.pcmEncoding)
                .build();
    }

    private Format lastInput;

    @Override
    protected PcmCopyDecoder createDecoder(Format format, CryptoConfig cryptoConfig) throws DecoderException {
        int bytes = bytesPerSample(format.pcmEncoding);
        if (bytes == 0) throw new DecoderException("Unsupported PCM encoding " + format.pcmEncoding);
        lastInput = format;
        DirectOutputState.INSTANCE.log("PCM passthrough: " + format.sampleRate + " Hz " + format.channelCount + " ch " + (bytes * 8) + "-bit");
        return new PcmCopyDecoder(format.channelCount, bytes, format.sampleRate, format.maxInputSize);
    }

    @Override
    protected Format getOutputFormat(PcmCopyDecoder decoder) {
        if (lastInput == null) throw new IllegalStateException("PCM format missing");
        return outputFormat(lastInput);
    }
}
