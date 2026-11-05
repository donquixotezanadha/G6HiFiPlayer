package id.g6hifiplayer;

import java.io.IOException;

/**
 * Hifi 1.28: decodes ONE ALAC packet (one MP4 sample, as Media3's Mp4Extractor hands it over) to exact integer samples.
 * Pure Java, no Android classes, so it can be tested on a PC against ffmpeg's ALAC decoder.
 *
 * The phone's own decoder cannot play (or cuts) hi-res ALAC. Nothing here applies gain, dither or rounding: every sample is
 * the integer the ALAC stream stores. Supports mono and stereo, 16 / 20 / 24 bits, compressed (adaptive FIR + Rice) and
 * uncompressed elements, the uncompressed "extra bits" of 20 / 24-bit files, and the stereo mixing matrix.
 *
 * The cookie is the ALACSpecificConfig (24 bytes: frameLength, compatibleVersion, bitDepth, pb, mb, kb, channels, maxRun,
 * maxFrameBytes, avgBitRate, sampleRate); a cookie that still carries its 12-byte "alac" box header is accepted too.
 */
public final class AlacFrameDecoder {
    private final int frameLength;
    private final int sampleSize;
    private final int pb;            // rice history multiplier from the cookie
    private final int mb;            // initial rice history
    private final int kb;            // rice limit
    private final int channels;
    private final int sampleRate;

    private byte[] data = new byte[0];
    private int bitPos;
    private int endBits;

    private int[][] out;
    private int[][] err;
    private int[][] extra;
    private final int[][] coefs = new int[2][32];
    private int blockSize;

    public AlacFrameDecoder(byte[] cookie) throws IOException {
        int o = 0;
        if (cookie.length >= 36 && cookie[4] == 'a' && cookie[5] == 'l' && cookie[6] == 'a' && cookie[7] == 'c') o = 12;
        if (cookie.length - o < 24) throw new IOException("ALAC cookie too short");
        frameLength = be32(cookie, o);
        sampleSize = cookie[o + 5] & 0xFF;
        pb = cookie[o + 6] & 0xFF;
        mb = cookie[o + 7] & 0xFF;
        kb = cookie[o + 8] & 0xFF;
        channels = cookie[o + 9] & 0xFF;
        sampleRate = be32(cookie, o + 20);
        if (frameLength <= 0 || frameLength > 65536) throw new IOException("ALAC frame length " + frameLength);
        if (channels < 1 || channels > 2) throw new IOException("ALAC channels " + channels);
        if (sampleSize != 16 && sampleSize != 20 && sampleSize != 24) throw new IOException("ALAC bit depth " + sampleSize);
        out = new int[channels][frameLength];
        err = new int[channels][frameLength];
        extra = new int[channels][frameLength];
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public int channels() { return channels; }
    public int sampleRate() { return sampleRate; }
    public int sampleSize() { return sampleSize; }
    public int frameLength() { return frameLength; }
    /** Samples per channel in the last decoded packet. */
    public int blockSize() { return blockSize; }

    // ---------------------------------------------------------------- bit reader
    private int peekBits(int n) throws IOException {   // 0..32
        if (n == 0) return 0;
        int bytePos = bitPos >>> 3;
        int bitOff = bitPos & 7;
        long x = 0;
        for (int i = 0; i < 5; i++) {
            int p = bytePos + i;
            x = (x << 8) | (p < data.length ? (data[p] & 0xFF) : 0);
        }
        return (int) ((x >>> (40 - bitOff - n)) & ((1L << n) - 1));
    }

    private int readBits(int n) throws IOException {
        if (bitPos + n > endBits) throw new IOException("end of packet");
        int r = peekBits(n);
        bitPos += n;
        return r;
    }

    private int readSigned(int n) throws IOException {
        if (n == 0) return 0;
        int v = readBits(n);
        return (v << (32 - n)) >> (32 - n);
    }

    // ---------------------------------------------------------------- packet
    /** Decodes the packet at buf[off .. off+len); returns the number of samples per channel. */
    public int decode(byte[] buf, int off, int len) throws IOException {
        if (off != 0) {
            if (data.length < len) data = new byte[len];
            System.arraycopy(buf, off, data, 0, len);
        } else {
            data = buf;
        }
        bitPos = 0;
        endBits = len * 8;
        blockSize = -1;
        int done = 0;
        while (done < channels) {
            int tag = readBits(3);
            int n;
            if (tag == 0) n = 1;            // single channel element
            else if (tag == 1) n = 2;       // channel pair element
            else if (tag == 7) break;       // end
            else throw new IOException("ALAC element " + tag);
            if (done + n > channels) throw new IOException("too many channels");
            element(done, n);
            done += n;
        }
        if (done != channels || blockSize <= 0) throw new IOException("ALAC packet incomplete");
        return blockSize;
    }

    private void element(int chOff, int n) throws IOException {
        readBits(4);                                    // element instance tag
        readBits(12);                                   // unused header bits
        boolean hasSize = readBits(1) == 1;
        int extraBits = readBits(2) << 3;
        int bps = sampleSize - extraBits + n - 1;
        if (bps < 1 || bps > 32) throw new IOException("ALAC bps " + bps);
        boolean compressed = readBits(1) == 0;
        int samples = hasSize ? readBits(32) : frameLength;
        if (samples <= 0 || samples > frameLength) throw new IOException("ALAC samples " + samples);
        if (blockSize < 0) blockSize = samples; else if (blockSize != samples) throw new IOException("ALAC element sizes differ");

        int decorrShift = 0;
        int decorrWeight = 0;
        if (compressed) {
            decorrShift = readBits(8);
            decorrWeight = readBits(8);
            int[] order = new int[2];
            int[] quant = new int[2];
            int[] mult = new int[2];
            for (int c = 0; c < n; c++) {
                int predType = readBits(4);
                quant[c] = readBits(4);
                mult[c] = readBits(3);
                order[c] = readBits(5);
                if (predType != 0) throw new IOException("ALAC prediction type " + predType);
                if (order[c] >= samples) throw new IOException("ALAC lpc order");
                for (int i = 0; i < order[c]; i++) coefs[c][i] = readSigned(16);   // first read = most recent sample
            }
            if (extraBits > 0) {
                for (int i = 0; i < samples; i++)
                    for (int c = 0; c < n; c++) extra[c][i] = readBits(extraBits);
            }
            for (int c = 0; c < n; c++) {
                riceDecode(err[c], samples, bps, mult[c] * pb / 4);
                lpc(err[c], out[chOff + c], samples, bps, coefs[c], order[c], quant[c]);
            }
        } else {
            for (int i = 0; i < samples; i++)
                for (int c = 0; c < n; c++) out[chOff + c][i] = readSigned(sampleSize);
            extraBits = 0;
        }

        if (n == 2 && decorrWeight != 0) {
            int[] a = out[chOff], b = out[chOff + 1];
            for (int i = 0; i < samples; i++) {
                int u = a[i], v = b[i];
                u -= (v * decorrWeight) >> decorrShift;
                v += u;
                a[i] = v;      // left
                b[i] = u;      // right
            }
        }
        if (extraBits > 0) {
            for (int c = 0; c < n; c++) {
                int[] a = out[chOff + c];
                for (int i = 0; i < samples; i++) a[i] = (a[i] << extraBits) | extra[c][i];
            }
        }
    }

    // ---------------------------------------------------------------- entropy decoding
    private static int log2(int v) { return v <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(v); }

    private int decodeScalar(int k, int bps) throws IOException {
        int x = 0;
        while (x < 9 && readBits(1) == 1) x++;
        if (x > 8) {
            x = readBits(bps);
        } else if (k != 1) {
            int extrabits = peekBits(k);
            x = (x << k) - x;
            if (extrabits > 1) {
                x += extrabits - 1;
                bitPos += k;
            } else {
                bitPos += k - 1;
            }
            if (bitPos > endBits) throw new IOException("end of packet");
        }
        return x;
    }

    private void riceDecode(int[] o, int n, int bps, int mult) throws IOException {
        int history = mb;
        int signModifier = 0;
        for (int i = 0; i < n; i++) {
            int k = Math.min(log2((history >>> 9) + 3), kb);
            int x = decodeScalar(k, bps);
            x += signModifier;
            signModifier = 0;
            o[i] = (x >>> 1) ^ -(x & 1);
            if (x > 0xFFFF) history = 0xFFFF;
            else history += x * mult - ((history * mult) >>> 9);

            if (history < 128 && i + 1 < n) {          // run of zeros
                k = Math.min(7 - log2(history) + ((history + 16) >> 6), kb);
                int block = decodeScalar(k, 16);
                if (block > 0) {
                    if (block >= n - i) throw new IOException("ALAC zero run");
                    for (int j = 1; j <= block; j++) o[i + j] = 0;
                    i += block;
                }
                if (block <= 0xFFFF) signModifier = 1;
                history = 0;
            }
        }
    }

    // ---------------------------------------------------------------- prediction
    private static int signExtend(int v, int bits) { return (v << (32 - bits)) >> (32 - bits); }
    private static int sign(int v) { return (v > 0 ? 1 : 0) - (v < 0 ? 1 : 0); }

    private static void lpc(int[] e, int[] o, int n, int bps, int[] c, int order, int quant) {
        o[0] = e[0];
        if (n <= 1) return;
        if (order == 0) {
            for (int i = 1; i < n; i++) o[i] = e[i];
            return;
        }
        if (order == 31) {
            for (int i = 1; i < n; i++) o[i] = signExtend(o[i - 1] + e[i], bps);
            return;
        }
        int i;
        for (i = 1; i <= order && i < n; i++) o[i] = signExtend(o[i - 1] + e[i], bps);
        int half = quant > 0 ? 1 << (quant - 1) : 0;
        for (; i < n; i++) {
            int top = o[i - order - 1];
            int sum = 0;
            for (int k = 0; k < order; k++) sum += (o[i - 1 - k] - top) * c[k];
            int val = ((sum + half) >> quant) + top + e[i];
            o[i] = signExtend(val, bps);

            int errVal = e[i];
            int es = sign(errVal);
            if (es != 0) {                               // sign-sign adaptation, oldest tap first
                for (int k = order - 1; k >= 0 && errVal * es > 0; k--) {
                    int v = top - o[i - 1 - k];
                    int s = sign(v) * es;
                    c[k] = (short) (c[k] - s);
                    v *= s;
                    errVal -= (v >> quant) * (order - k);
                }
            }
        }
    }

    /**
     * Writes the last packet as interleaved 24-bit little-endian PCM into [dst] at [dstOff]
     * (blockSize * channels * 3 bytes). 16 / 20-bit samples are shifted up so their value keeps its place.
     */
    public int writePcm24(byte[] dst, int dstOff) {
        int shift = 24 - sampleSize;
        int o = dstOff;
        for (int i = 0; i < blockSize; i++) {
            for (int c = 0; c < channels; c++) {
                int v = out[c][i] << shift;
                dst[o++] = (byte) v;
                dst[o++] = (byte) (v >> 8);
                dst[o++] = (byte) (v >> 16);
            }
        }
        return o - dstOff;
    }
}
