package id.g6hifiplayer;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Hifi 1.28: Media3 decoder that turns ALAC packets into exact 24-bit PCM with [AlacFrameDecoder]. One input buffer is one ALAC
 * packet (one MP4 sample, as Mp4Extractor hands it over), one output buffer is the matching block of interleaved 24-bit
 * little-endian samples. No gain, dither or rounding.
 */
@UnstableApi
final class AlacPcmDecoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private static final int NUM_BUFFERS = 4;

    private final AlacFrameDecoder frames;
    private final int channels;
    private final int padFrames;
    private boolean needsPad = true;   // true at the start and after every flush (seek)
    private byte[] frameBytes = new byte[1 << 14];
    private byte[] pcm = new byte[1 << 14];

    AlacPcmDecoder(byte[] cookie, int maxInputSize) throws IOException {
        super(new DecoderInputBuffer[NUM_BUFFERS], new SimpleDecoderOutputBuffer[NUM_BUFFERS]);
        this.frames = new AlacFrameDecoder(cookie);
        this.channels = frames.channels();
        this.padFrames = DirectOutputState.INSTANCE.padFrames(frames.sampleRate());
        if (maxInputSize > 0) setInitialInputBufferSize(maxInputSize);
    }

    @Override
    public String getName() {
        return "HifiAlacPcmDecoder";
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
        return new DecoderException("Unexpected ALAC decode error", error);
    }

    @Override
    protected DecoderException decode(DecoderInputBuffer in, SimpleDecoderOutputBuffer out, boolean reset) {
        ByteBuffer src = in.data;
        if (src == null) return new DecoderException("ALAC packet without data");
        int len = src.remaining();
        if (frameBytes.length < len) frameBytes = new byte[len];
        src.get(frameBytes, 0, len);
        try {
            frames.decode(frameBytes, 0, len);
        } catch (IOException | RuntimeException e) {
            return new DecoderException("ALAC packet could not be decoded: " + e.getMessage(), e);
        }
        if (reset) needsPad = true;
        int padBytes = needsPad ? padFrames * channels * 3 : 0;
        int size = frames.blockSize() * channels * 3;
        if (pcm.length < padBytes + size) pcm = new byte[padBytes + size];
        if (padBytes > 0) java.util.Arrays.fill(pcm, 0, padBytes, (byte) 0);
        frames.writePcm24(pcm, padBytes);
        ByteBuffer dst = out.init(in.timeUs, padBytes + size);
        dst.put(pcm, 0, padBytes + size);
        dst.flip();
        needsPad = false;
        DirectOutputState.INSTANCE.setOwnDecodedSamples(DirectOutputState.INSTANCE.getOwnDecodedSamples() + frames.blockSize());
        return null;
    }
}
