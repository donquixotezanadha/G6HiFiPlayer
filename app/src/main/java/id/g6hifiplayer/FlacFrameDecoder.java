package id.g6hifiplayer;

import java.io.IOException;

/**
 * Hifi 1.27: decodes ONE FLAC frame (exactly what Media3's FlacExtractor hands over per sample) to exact integer samples.
 * Pure Java, no Android classes, so it can be tested on a PC against the reference FLAC decoder.
 *
 * It exists because the phone's own MediaCodec FLAC decoder cuts hi-res files to 16 bits before the player ever sees them
 * (seen as "empty low byte sent 99% file 0%" and MD5 DIFFERENT). Nothing here applies gain, dither or rounding: every sample
 * is the integer that the FLAC stream stores.
 *
 * Supports mono and stereo, 8..24 bits per sample, all subframe types (constant, verbatim, fixed, LPC), wasted bits and the
 * three stereo decorrelation modes. 32-bit streams are refused.
 */
public final class FlacFrameDecoder {
    private final int channels;
    private final int streamBits;

    private byte[] data = new byte[0];
    private int bitPos;
    private int endBits;

    private int[][] ch;
    private final int[] coefs = new int[32];
    private int blockSize;
    private int bps;

    public FlacFrameDecoder(int channels, int streamBits) {
        if (channels < 1 || channels > 2) throw new IllegalArgumentException("channels " + channels);
        this.channels = channels;
        this.streamBits = streamBits;
        this.ch = new int[channels][0];
    }

    /** Samples per channel in the last decoded frame. */
    public int blockSize() { return blockSize; }

    /** Bits per sample of the last decoded frame. */
    public int bitsPerSample() { return bps; }

    /** Decoded samples of channel [c] (valid up to [blockSize]); stereo is already decorrelated to left / right. */
    public int[] channel(int c) { return ch[c]; }

    // ---------------------------------------------------------------- bit reader
    private int readBits(int n) throws IOException {   // 0..32
        if (n == 0) return 0;
        if (bitPos + n > endBits) throw new IOException("end of frame");
        int bytePos = bitPos >>> 3;
        int bitOff = bitPos & 7;
        long x = 0;
        for (int i = 0; i < 5; i++) {
            int p = bytePos + i;
            x = (x << 8) | (p < data.length ? (data[p] & 0xFF) : 0);
        }
        int r = (int) ((x >>> (40 - bitOff - n)) & ((1L << n) - 1));
        bitPos += n;
        return r;
    }

    private int readSigned(int n) throws IOException {
        if (n == 0) return 0;
        int v = readBits(n);
        return (v << (32 - n)) >> (32 - n);
    }

    private int readUnary() throws IOException {
        int q = 0;
        while (true) {
            if (bitPos >= endBits) throw new IOException("end of frame");
            int b = data[bitPos >>> 3] & 0xFF;
            int off = bitPos & 7;
            int rem = (b << off) & 0xFF;
            if (rem == 0) {
                q += 8 - off;
                bitPos += 8 - off;
                continue;
            }
            int lz = Integer.numberOfLeadingZeros(rem) - 24;
            q += lz;
            bitPos += lz + 1;
            return q;
        }
    }

    // ---------------------------------------------------------------- frame
    /**
     * Decodes the frame at buf[off .. off+len). Returns the number of bytes the frame used (header, subframes and CRC-16),
     * which equals [len] for a frame cut out by the extractor.
     */
    public int decode(byte[] buf, int off, int len) throws IOException {
        // Work on a view that starts at [off] so bit positions are relative to the frame.
        if (off != 0) {
            if (data.length < len) data = new byte[len];
            System.arraycopy(buf, off, data, 0, len);
        } else {
            data = buf;
        }
        bitPos = 0;
        endBits = len * 8;

        if (readBits(14) != 0x3FFE) throw new IOException("sync");
        readBits(1);                            // reserved
        readBits(1);                            // blocking strategy
        int bsCode = readBits(4);
        int srCode = readBits(4);
        int chCode = readBits(4);
        int bpsCode = readBits(3);
        readBits(1);
        int first = readBits(8);                // UTF-8 coded frame / sample number: only its length matters
        int extra = 0;
        if ((first & 0x80) != 0) {
            int m = 0x40;
            while ((first & m) != 0) { extra++; m >>= 1; }
        }
        for (int i = 0; i < extra; i++) readBits(8);
        int bs;
        if (bsCode == 1) bs = 192;
        else if (bsCode >= 2 && bsCode <= 5) bs = 576 << (bsCode - 2);
        else if (bsCode == 6) bs = readBits(8) + 1;
        else if (bsCode == 7) bs = readBits(16) + 1;
        else if (bsCode >= 8) bs = 256 << (bsCode - 8);
        else throw new IOException("block size");
        if (srCode == 12) readBits(8); else if (srCode == 13 || srCode == 14) readBits(16);
        readBits(8);                            // CRC-8
        int frameBps;
        switch (bpsCode) {
            case 0: frameBps = streamBits; break;
            case 1: frameBps = 8; break;
            case 2: frameBps = 12; break;
            case 4: frameBps = 16; break;
            case 5: frameBps = 20; break;
            case 6: frameBps = 24; break;
            default: throw new IOException("sample size " + bpsCode);
        }
        if (frameBps > 24) throw new IOException("more than 24 bits");
        int frameChannels;
        if (chCode <= 7) frameChannels = chCode + 1;
        else if (chCode <= 10) frameChannels = 2;
        else throw new IOException("channel assignment " + chCode);
        if (frameChannels != channels) throw new IOException("channel count changed");

        if (ch[0].length < bs) for (int c = 0; c < channels; c++) ch[c] = new int[bs];
        blockSize = bs;
        bps = frameBps;

        for (int c = 0; c < channels; c++) {
            int sbps = frameBps;
            if ((chCode == 8 && c == 1) || (chCode == 9 && c == 0) || (chCode == 10 && c == 1)) sbps++;
            subframe(ch[c], bs, sbps);
        }
        bitPos = (bitPos + 7) & ~7;             // align to byte
        readBits(16);                           // CRC-16 (not verified)

        if (channels == 2) {
            int[] a = ch[0], b = ch[1];
            switch (chCode) {
                case 8:  for (int i = 0; i < bs; i++) b[i] = a[i] - b[i]; break;                 // left / side
                case 9:  for (int i = 0; i < bs; i++) a[i] = a[i] + b[i]; break;                 // side / right
                case 10:                                                                        // mid / side
                    for (int i = 0; i < bs; i++) {
                        int side = b[i];
                        int mid = (a[i] << 1) | (side & 1);
                        a[i] = (mid + side) >> 1;
                        b[i] = (mid - side) >> 1;
                    }
                    break;
                default: break;
            }
        }
        return bitPos >>> 3;
    }

    /**
     * Writes the last frame as interleaved 24-bit little-endian PCM into [dst] at [dstOff]
     * (blockSize * channels * 3 bytes). Samples of fewer than 24 bits are shifted up so their value keeps its place.
     */
    public int writePcm24(byte[] dst, int dstOff) {
        int shift = 24 - bps;
        int o = dstOff;
        for (int i = 0; i < blockSize; i++) {
            for (int c = 0; c < channels; c++) {
                int v = ch[c][i] << shift;
                dst[o++] = (byte) v;
                dst[o++] = (byte) (v >> 8);
                dst[o++] = (byte) (v >> 16);
            }
        }
        return o - dstOff;
    }

    // ---------------------------------------------------------------- subframes
    private void subframe(int[] out, int n, int sbps) throws IOException {
        readBits(1);
        int type = readBits(6);
        int wasted = 0;
        if (readBits(1) == 1) wasted = readUnary() + 1;
        sbps -= wasted;
        if (sbps <= 0 || sbps > 32) throw new IOException("subframe bits");
        if (type == 0) {
            int v = readSigned(sbps);
            for (int i = 0; i < n; i++) out[i] = v;
        } else if (type == 1) {
            for (int i = 0; i < n; i++) out[i] = readSigned(sbps);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            if (order > n) throw new IOException("order");
            for (int i = 0; i < order; i++) out[i] = readSigned(sbps);
            residual(out, order, n);
            switch (order) {
                case 1: for (int i = 1; i < n; i++) out[i] += out[i - 1]; break;
                case 2: for (int i = 2; i < n; i++) out[i] += 2 * out[i - 1] - out[i - 2]; break;
                case 3: for (int i = 3; i < n; i++) out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3]; break;
                case 4: for (int i = 4; i < n; i++) out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4]; break;
                default: break;
            }
        } else if (type >= 32) {
            int order = (type & 31) + 1;
            if (order > n) throw new IOException("order");
            for (int i = 0; i < order; i++) out[i] = readSigned(sbps);
            int prec = readBits(4) + 1;
            if (prec == 16) throw new IOException("precision");
            int shift = readSigned(5);
            for (int j = 0; j < order; j++) coefs[j] = readSigned(prec);
            residual(out, order, n);
            for (int i = order; i < n; i++) {
                long sum = 0;
                for (int j = 0; j < order; j++) sum += (long) coefs[j] * out[i - 1 - j];
                out[i] += (int) (shift >= 0 ? (sum >> shift) : (sum << -shift));
            }
        } else {
            throw new IOException("subframe type " + type);
        }
        if (wasted > 0) for (int i = 0; i < n; i++) out[i] <<= wasted;
    }

    private void residual(int[] out, int order, int n) throws IOException {
        int method = readBits(2);
        if (method > 1) throw new IOException("residual method");
        int partOrder = readBits(4);
        int parts = 1 << partOrder;
        int paramBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int per = n >> partOrder;
        if (per < order) throw new IOException("partition");
        int pos = order;
        for (int p = 0; p < parts; p++) {
            int count = p == 0 ? per - order : per;
            int param = readBits(paramBits);
            if (param == escape) {
                int raw = readBits(5);
                for (int i = 0; i < count; i++) out[pos++] = raw == 0 ? 0 : readSigned(raw);
            } else {
                for (int i = 0; i < count; i++) {
                    int q = readUnary();
                    int v = (q << param) | (param == 0 ? 0 : readBits(param));
                    out[pos++] = (v >>> 1) ^ -(v & 1);
                }
            }
        }
    }
}
