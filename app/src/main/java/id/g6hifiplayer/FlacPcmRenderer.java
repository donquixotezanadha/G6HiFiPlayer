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
 * Hifi 1.27: plays FLAC files of more than 16 bits with [FlacPcmDecoder] instead of the phone's MediaCodec decoder, which cuts
 * them to 16 bits (MD5 DIFFERENT, empty low byte). It is placed before MediaCodecAudioRenderer in the renderer list, so it wins
 * the tie for those files. Everything else (16-bit FLAC, MP3, AAC, ALAC, WAV ...) is reported as unsupported here and is
 * handled by the normal renderer exactly as before.
 */
@UnstableApi
final class FlacPcmRenderer extends DecoderAudioRenderer<FlacPcmDecoder> {

    /** channels, bits, sampleRate read from the STREAMINFO block that FlacExtractor puts in initializationData. */
    private static final class Info {
        final int channels;
        final int bits;
        final int rate;
        Info(int channels, int bits, int rate) { this.channels = channels; this.bits = bits; this.rate = rate; }
    }

    private Info lastInfo;

    FlacPcmRenderer(Handler eventHandler, AudioRendererEventListener eventListener, AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "HifiFlacPcmRenderer";
    }

    private static Info info(Format format) {
        List<byte[]> init = format.initializationData;
        if (init == null || init.isEmpty()) return null;
        byte[] all = init.get(0);   // "fLaC" + block header (4) + STREAMINFO (34)
        if (all == null || all.length < 26) return null;
        if (all[0] != 'f' || all[1] != 'L' || all[2] != 'a' || all[3] != 'C') return null;
        int rate = ((all[18] & 0xFF) << 12) | ((all[19] & 0xFF) << 4) | ((all[20] & 0xFF) >> 4);
        int channels = ((all[20] >> 1) & 0x7) + 1;
        int bits = (((all[20] & 0x1) << 4) | ((all[21] & 0xFF) >> 4)) + 1;
        return new Info(channels, bits, rate);
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
        if (!MimeTypes.AUDIO_FLAC.equalsIgnoreCase(format.sampleMimeType)) return C.FORMAT_UNSUPPORTED_TYPE;
        Info info = info(format);
        if (info == null) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        // 8..24 bits (16-bit included; shifted up inside 24 bits, same value). 32-bit is not handled.
        if (info.bits < 8 || info.bits > 24 || info.channels < 1 || info.channels > 2) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (info.rate < 8000 || info.rate > 192000) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (!sinkSupportsFormat(pcmFormat(info))) return C.FORMAT_UNSUPPORTED_SUBTYPE;
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM;
        return C.FORMAT_HANDLED;
    }

    @Override
    protected FlacPcmDecoder createDecoder(Format format, CryptoConfig cryptoConfig) throws DecoderException {
        Info info = info(format);
        if (info == null) throw new DecoderException("FLAC STREAMINFO missing");
        lastInfo = info;
        DirectOutputState.INSTANCE.log("own FLAC decoder: " + info.rate + " Hz " + info.channels + " ch " + info.bits + "-bit");
        return new FlacPcmDecoder(info.channels, info.bits, info.rate, format.maxInputSize);
    }

    @Override
    protected Format getOutputFormat(FlacPcmDecoder decoder) {
        // The stream is fixed for the whole file; use the format the decoder was created for.
        if (lastInfo == null) throw new IllegalStateException("FLAC STREAMINFO missing");
        return pcmFormat(lastInfo);
    }
}
