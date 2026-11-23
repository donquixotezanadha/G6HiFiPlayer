package id.g6hifiplayer;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

/**
 * Hifi iTunes 1: Media3 decoder that turns AAC-LC packets (one MP4 sample each, as Mp4Extractor hands them over) into 24-bit
 * little-endian PCM with the player's own copy of the JAAD AAC decoder (net.sourceforge.jaad.aac, patched: 24-bit output,
 * TNS applied, PNS and quantizer-table bugs fixed; checked against ffmpeg to about -100 dBFS). No MediaCodec, no gain, no dither.
 */
@UnstableApi
final class AacPcmDecoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private static final int NUM_BUFFERS = 4;

    private final Decoder aac;
    private final SampleBuffer sample = new SampleBuffer();
    private final int declaredRate;
    private final int declaredChannels;
    private int padFrames;
    private boolean needsPad = true;   // true at the start and after every flush (seek)
    private volatile int outRate;
    private volatile int outChannels;
    private byte[] frameBytes = new byte[1 << 13];
    private byte[] silence = new byte[0];

    AacPcmDecoder(byte[] audioSpecificConfig, int rate, int channels, int maxInputSize) throws Exception {
        super(new DecoderInputBuffer[NUM_BUFFERS], new SimpleDecoderOutputBuffer[NUM_BUFFERS]);
        this.aac = new Decoder(audioSpecificConfig);
        this.aac.getConfig().setSBREnabled(false);   // AAC-LC only: the output rate is the file's rate
        this.declaredRate = rate;
        this.declaredChannels = channels;
        this.outRate = rate;
        this.outChannels = channels;
        this.padFrames = DirectOutputState.INSTANCE.padFrames(rate);
        if (maxInputSize > 0) setInitialInputBufferSize(maxInputSize);
    }

    int outputRate() { return outRate; }

    int outputChannels() { return outChannels; }

    @Override
    public String getName() {
        return "HifiAacPcmDecoder";
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    }

    @Override
    protected SimpleDecoderOutputBuffer createOutputBuffer() {
        return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
        return new DecoderException("Unexpected AAC decode error", error);
    }

    @Override
    protected DecoderException decode(DecoderInputBuffer in, SimpleDecoderOutputBuffer out, boolean reset) {
        ByteBuffer src = in.data;
        if (src == null) return new DecoderException("AAC packet without data");
        int len = src.remaining();
        if (frameBytes.length < len) frameBytes = new byte[len];
        src.get(frameBytes, 0, len);
        byte[] packet = (frameBytes.length == len) ? frameBytes : java.util.Arrays.copyOf(frameBytes, len);
        try {
            aac.decodeFrame(packet, sample);
        } catch (Exception | StackOverflowError e) {
            return new DecoderException("AAC packet could not be decoded: " + e.getMessage(), e);
        }
        if (sample.getBitsPerSample() != 24) return new DecoderException("AAC decoder did not return 24-bit PCM");
        outRate = sample.getSampleRate();
        outChannels = sample.getChannels();
        if (reset) needsPad = true;
        byte[] pcm = sample.getData();
        int padBytes = needsPad ? padFrames * outChannels * 3 : 0;
        ByteBuffer dst = out.init(in.timeUs, padBytes + pcm.length);
        if (padBytes > 0) {
            if (silence.length < padBytes) silence = new byte[padBytes];
            dst.put(silence, 0, padBytes);
        }
        dst.put(pcm, 0, pcm.length);
        dst.flip();
        needsPad = false;
        DirectOutputState.INSTANCE.setOwnDecodedSamples(DirectOutputState.INSTANCE.getOwnDecodedSamples() + pcm.length / (3 * Math.max(1, outChannels)));
        return null;
    }
}
