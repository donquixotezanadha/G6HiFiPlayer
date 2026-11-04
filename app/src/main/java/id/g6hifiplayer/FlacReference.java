package id.g6hifiplayer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/**
 * Hifi 1.25: a small, independent FLAC decoder used only as a REFERENCE for the MD5 check. It is not used for playback.
 * It decodes a stereo FLAC file completely and returns the MD5 of the decoded audio, computed the way FLAC itself defines it
 * (interleaved, signed, little-endian, ceil(bits/8) bytes per sample), plus the number of samples per channel.
 * Used for files whose header carries no MD5 (many hi-res files), so the audio the player sends can still be compared with
 * what the file really contains, decoded by code that has nothing to do with Android's decoder.
 */
public final class FlacReference {
    public static final class Result {
        public final byte[] md5;
        public final long totalSamples;
        public final int bits;
        public final int sampleRate;
        Result(byte[] md5, long totalSamples, int bits, int sampleRate) {
            this.md5 = md5; this.totalSamples = totalSamples; this.bits = bits; this.sampleRate = sampleRate;
        }
    }

    /** Returns null when the file is not a stereo FLAC this decoder handles, is damaged, or [cancelled] turned true. */
    public static Result decode(InputStream in, BooleanSupplier cancelled) {
        try {
            return new FlacReference(in, cancelled).run();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private final InputStream in;
    private final BooleanSupplier cancelled;
    private final byte[] buf = new byte[1 << 16];
    private int bufLen = 0, bufPos = 0;
    private long acc = 0;
    private int accBits = 0;

    private int streamBits, streamRate, streamChannels;
    private long streamTotal;
    private int[] a = new int[0], b = new int[0];
    private byte[] pcm = new byte[0];
    private int[] coefs = new int[32];

    private FlacReference(InputStream in, BooleanSupplier cancelled) { this.in = in; this.cancelled = cancelled; }

    // ---------------------------------------------------------------- bit reader
    private int readByte() throws IOException {
        if (bufPos >= bufLen) {
            bufLen = in.read(buf, 0, buf.length);
            bufPos = 0;
            if (bufLen <= 0) { bufLen = 0; return -1; }
        }
        return buf[bufPos++] & 0xFF;
    }

    private boolean hasMore() throws IOException {
        if (accBits > 0) return true;
        int v = readByte();
        if (v < 0) return false;
        acc = v; accBits = 8;
        return true;
    }

    private int readBits(int n) throws IOException {   // 0..32
        while (accBits < n) {
            int v = readByte();
            if (v < 0) throw new EOFException();
            acc = (acc << 8) | v;
            accBits += 8;
        }
        accBits -= n;
        int r = (int) ((acc >>> accBits) & ((1L << n) - 1));
        acc &= (1L << accBits) - 1;
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
            if (accBits == 0) {
                int v = readByte();
                if (v < 0) throw new EOFException();
                acc = v; accBits = 8;
            }
            if (acc == 0) { q += accBits; accBits = 0; continue; }
            int lz = accBits - (64 - Long.numberOfLeadingZeros(acc));
            q += lz;
            accBits -= lz + 1;
            acc &= (1L << accBits) - 1;
            return q;
        }
    }

    private void alignToByte() { accBits = 0; acc = 0; }

    private void skipBytes(long n) throws IOException {
        for (long i = 0; i < n; i++) if (readByte() < 0) throw new EOFException();
    }

    // ---------------------------------------------------------------- stream
    private Result run() throws IOException {
        int b0 = readByte(), b1 = readByte(), b2 = readByte(), b3 = readByte();
        if (b3 < 0) return null;
        if (b0 == 'I' && b1 == 'D' && b2 == '3') {
            skipBytes(2);                       // version
            skipBytes(1);                       // flags
            int s = 0;
            for (int i = 0; i < 4; i++) s = (s << 7) | (readByte() & 0x7F);
            skipBytes(s);
            b0 = readByte(); b1 = readByte(); b2 = readByte(); b3 = readByte();
        }
        if (b0 != 'f' || b1 != 'L' || b2 != 'a' || b3 != 'C') return null;
        boolean last = false;
        boolean gotInfo = false;
        while (!last) {
            int h = readBits(8);
            last = (h & 0x80) != 0;
            int type = h & 0x7F;
            int len = readBits(24);
            if (type == 0 && !gotInfo) {
                readBits(16); readBits(16); readBits(24); readBits(24);
                streamRate = readBits(20);
                streamChannels = readBits(3) + 1;
                streamBits = readBits(5) + 1;
                streamTotal = ((long) readBits(4) << 32) | (readBits(32) & 0xFFFFFFFFL);
                skipBytes(16);                  // MD5 of the file (not used here)
                skipBytes(len - 34);
                gotInfo = true;
            } else {
                skipBytes(len);
            }
        }
        if (!gotInfo || streamChannels != 2 || streamBits < 8 || streamBits > 24) return null;
        alignToByte();
        MessageDigest md;
        try { md = MessageDigest.getInstance("MD5"); } catch (Exception e) { return null; }
        long total = 0;
        while (hasMore()) {
            if (cancelled != null && cancelled.getAsBoolean()) return null;
            total += decodeFrame(md);
        }
        if (streamTotal > 0 && total != streamTotal) return null;
        return new Result(md.digest(), total, streamBits, streamRate);
    }

    private int decodeFrame(MessageDigest md) throws IOException {
        if (readBits(14) != 0x3FFE) throw new IOException("sync");
        readBits(1);
        readBits(1);
        int bsCode = readBits(4);
        int srCode = readBits(4);
        int chCode = readBits(4);
        int bpsCode = readBits(3);
        readBits(1);
        int first = readBits(8);                // UTF-8 coded frame / sample number: only its length matters
        int extra = 0;
        if ((first & 0x80) != 0) { int m = 0x40; while ((first & m) != 0) { extra++; m >>= 1; } }
        for (int i = 0; i < extra; i++) readBits(8);
        int blockSize;
        if (bsCode == 1) blockSize = 192;
        else if (bsCode >= 2 && bsCode <= 5) blockSize = 576 << (bsCode - 2);
        else if (bsCode == 6) blockSize = readBits(8) + 1;
        else if (bsCode == 7) blockSize = readBits(16) + 1;
        else if (bsCode >= 8) blockSize = 256 << (bsCode - 8);
        else throw new IOException("block size");
        if (srCode == 12) readBits(8); else if (srCode == 13 || srCode == 14) readBits(16);
        readBits(8);                            // CRC-8
        int bps;
        switch (bpsCode) {
            case 0: bps = streamBits; break;
            case 1: bps = 8; break;
            case 2: bps = 12; break;
            case 4: bps = 16; break;
            case 5: bps = 20; break;
            case 6: bps = 24; break;
            default: throw new IOException("sample size");
        }
        if (chCode != 1 && !(chCode >= 8 && chCode <= 10)) throw new IOException("channel assignment " + chCode);
        if (a.length < blockSize) { a = new int[blockSize]; b = new int[blockSize]; }
        int bytes = (bps + 7) / 8;
        if (pcm.length < blockSize * 2 * bytes) pcm = new byte[blockSize * 2 * bytes];
        for (int c = 0; c < 2; c++) {
            int sbps = bps;
            if ((chCode == 8 && c == 1) || (chCode == 9 && c == 0) || (chCode == 10 && c == 1)) sbps++;
            subframe(c == 0 ? a : b, blockSize, sbps);
        }
        alignToByte();
        readBits(16);                           // CRC-16
        int o = 0;
        for (int i = 0; i < blockSize; i++) {
            int l, r;
            switch (chCode) {
                case 8: l = a[i]; r = l - b[i]; break;
                case 9: r = b[i]; l = a[i] + r; break;
                case 10: { int side = b[i]; int mid = (a[i] << 1) | (side & 1); l = (mid + side) >> 1; r = (mid - side) >> 1; break; }
                default: l = a[i]; r = b[i]; break;
            }
            for (int k = 0; k < bytes; k++) pcm[o++] = (byte) (l >> (8 * k));
            for (int k = 0; k < bytes; k++) pcm[o++] = (byte) (r >> (8 * k));
        }
        md.update(pcm, 0, o);
        return blockSize;
    }

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
