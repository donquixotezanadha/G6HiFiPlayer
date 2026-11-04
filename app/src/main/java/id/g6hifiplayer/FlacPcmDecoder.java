package id.g6hifiplayer;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Hifi 1.27: Media3 decoder that turns FLAC frames into exact 24-bit PCM with [FlacFrameDecoder]. One input buffer is one FLAC
 * frame (that is how FlacExtractor hands the stream over), one output buffer is the matching block of interleaved 24-bit
 * little-endian samples. No gain, dither or rounding.
 */
@UnstableApi
final class FlacPcmDecoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private static final int NUM_BUFFERS = 4;

    private final FlacFrameDecoder frames;
    private final int channels;
    private byte[] frameBytes = new byte[1 << 14];
    private byte[] pcm = new byte[1 << 14];

    FlacPcmDecoder(int channels, int streamBits, int maxInputSize) {
        super(new DecoderInputBuffer[NUM_BUFFERS], new SimpleDecoderOutputBuffer[NUM_BUFFERS]);
        this.channels = channels;
        this.frames = new FlacFrameDecoder(channels, streamBits);
        if (maxInputSize > 0) setInitialInputBufferSize(maxInputSize);
    }

    @Override
    public String getName() {
        return "HifiFlacPcmDecoder";
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
        return new DecoderException("Unexpected FLAC decode error", error);
    }

    @Override
    protected DecoderException decode(DecoderInputBuffer in, SimpleDecoderOutputBuffer out, boolean reset) {
        ByteBuffer src = in.data;
        if (src == null) return new DecoderException("FLAC frame without data");
        int len = src.remaining();
        if (frameBytes.length < len) frameBytes = new byte[len];
        src.get(frameBytes, 0, len);
        try {
            frames.decode(frameBytes, 0, len);
        } catch (IOException | RuntimeException e) {
            return new DecoderException("FLAC frame could not be decoded: " + e.getMessage(), e);
        }
        int size = frames.blockSize() * channels * 3;
        if (pcm.length < size) pcm = new byte[size];
        frames.writePcm24(pcm, 0);
        ByteBuffer dst = out.init(in.timeUs, size);
        dst.put(pcm, 0, size);
        dst.flip();
        DirectOutputState.INSTANCE.setOwnDecodedSamples(DirectOutputState.INSTANCE.getOwnDecodedSamples() + frames.blockSize());
        return null;
    }
}
