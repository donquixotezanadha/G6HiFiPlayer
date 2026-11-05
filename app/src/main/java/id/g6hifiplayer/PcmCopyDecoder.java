package id.g6hifiplayer;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;

/**
 * Hifi 1.28: "decoder" for WAV. The samples are already PCM, so they are passed on byte for byte. The only addition is the same
 * silent pad in front of each start / seek that the FLAC and ALAC decoders add (see DirectOutputState.padFrames).
 */
@UnstableApi
final class PcmCopyDecoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private static final int NUM_BUFFERS = 4;

    private final int frameBytes;     // bytes of one frame (channels * bytes per sample)
    private final int padFrames;
    private boolean needsPad = true;
    private byte[] zeros = new byte[0];

    PcmCopyDecoder(int channels, int bytesPerSample, int sampleRate, int maxInputSize) {
        super(new DecoderInputBuffer[NUM_BUFFERS], new SimpleDecoderOutputBuffer[NUM_BUFFERS]);
        this.frameBytes = channels * bytesPerSample;
        this.padFrames = DirectOutputState.INSTANCE.padFrames(sampleRate);
        if (maxInputSize > 0) setInitialInputBufferSize(maxInputSize);
    }

    @Override
    public String getName() {
        return "HifiPcmCopyDecoder";
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
        return new DecoderException("Unexpected PCM copy error", error);
    }

    @Override
    protected DecoderException decode(DecoderInputBuffer in, SimpleDecoderOutputBuffer out, boolean reset) {
        ByteBuffer src = in.data;
        if (src == null) return new DecoderException("PCM block without data");
        int len = src.remaining();
        if (len % frameBytes != 0) return new DecoderException("PCM block is not a whole number of frames");
        if (reset) needsPad = true;
        int padBytes = needsPad ? padFrames * frameBytes : 0;
        ByteBuffer dst = out.init(in.timeUs, padBytes + len);
        if (padBytes > 0) {
            if (zeros.length < padBytes) zeros = new byte[padBytes];
            dst.put(zeros, 0, padBytes);
        }
        dst.put(src);
        dst.flip();
        needsPad = false;
        DirectOutputState.INSTANCE.setOwnDecodedSamples(DirectOutputState.INSTANCE.getOwnDecodedSamples() + len / frameBytes);
        return null;
    }
}
