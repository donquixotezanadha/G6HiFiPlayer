package id.g6hifiplayer;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Hifi 1.28: minimal MP4 / M4A reader that finds the ALAC track and lists its packets (offset + size) and cookie.
 * Used offline to decode a whole file for the MD5 check, and by the PC self-test. Pure Java, no Android classes.
 */
public final class AlacMp4Reader {
    public interface Source {
        long length() throws IOException;
        /** Reads exactly [len] bytes at [pos] into dst[off ..]. */
        void read(long pos, byte[] dst, int off, int len) throws IOException;
    }

    private static final int MOOV = 0x6d6f6f76, TRAK = 0x7472616b, MDIA = 0x6d646961, MINF = 0x6d696e66, STBL = 0x7374626c;
    private static final int STSD = 0x73747364, STSZ = 0x7374737a, STSC = 0x73747363, STCO = 0x7374636f, CO64 = 0x636f3634;
    private static final int ALAC = 0x616c6163;

    /** The 24-byte ALACSpecificConfig of the first ALAC track. */
    public byte[] cookie;
    /** File offset and size of every packet of that track, in playback order. */
    public long[] offsets;
    public int[] sizes;

    private final Source src;

    private static final class Track {
        byte[] cookie;
        int[] sizes;
        int[] stscFirst, stscCount;
        long[] chunkOffsets;
    }

    private Track best;
    private Track cur;

    private AlacMp4Reader(Source src) { this.src = src; }

    /** Returns null when the file has no ALAC track this reader understands. */
    public static AlacMp4Reader open(Source src) throws IOException {
        AlacMp4Reader r = new AlacMp4Reader(src);
        r.walk(0, src.length());
        if (r.best == null || r.best.cookie == null || r.best.sizes == null || r.best.chunkOffsets == null || r.best.stscFirst == null) return null;
        r.build(r.best);
        return r;
    }

    private byte[] read(long pos, int len) throws IOException {
        byte[] b = new byte[len];
        src.read(pos, b, 0, len);
        return b;
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private void walk(long start, long end) throws IOException {
        long pos = start;
        while (pos + 8 <= end) {
            byte[] h = read(pos, 8);
            long size = u32(h, 0);
            int type = (int) u32(h, 4);
            long hdr = 8;
            if (size == 1) { size = (u32(read(pos + 8, 8), 0) << 32) | u32(read(pos + 8, 8), 4); hdr = 16; }
            else if (size == 0) size = end - pos;
            if (size < hdr || pos + size > end) break;
            long body = pos + hdr, boxEnd = pos + size;
            switch (type) {
                case MOOV: case MDIA: case MINF: case STBL:
                    walk(body, boxEnd);
                    break;
                case TRAK:
                    cur = new Track();
                    walk(body, boxEnd);
                    if (best == null && cur.cookie != null) best = cur;
                    cur = null;
                    break;
                case STSD:
                    if (cur != null) parseStsd(body, boxEnd);
                    break;
                case STSZ:
                    if (cur != null) parseStsz(body, (int) (boxEnd - body));
                    break;
                case STSC:
                    if (cur != null) parseStsc(body, (int) (boxEnd - body));
                    break;
                case STCO:
                    if (cur != null) parseChunkOffsets(body, (int) (boxEnd - body), false);
                    break;
                case CO64:
                    if (cur != null) parseChunkOffsets(body, (int) (boxEnd - body), true);
                    break;
                default:
                    break;
            }
            pos = boxEnd;
        }
    }

    private void parseStsd(long body, long end) throws IOException {
        // full box (4) + entry count (4), then the sample entries; look for an 'alac' entry that holds an 'alac' child box.
        int len = (int) Math.min(end - body, 4096);
        byte[] d = read(body, len);
        for (int i = 8; i + 12 <= len; i++) {
            if ((int) u32(d, i + 4) == ALAC && u32(d, i) == 36 && i + 36 <= len) {
                // child box: size 36 = header 8 + version/flags 4 + cookie 24
                cur.cookie = java.util.Arrays.copyOfRange(d, i + 12, i + 36);
                return;
            }
        }
    }

    private void parseStsz(long body, int len) throws IOException {
        byte[] d = read(body, len);
        long fixed = u32(d, 4);
        int count = (int) u32(d, 8);
        int[] s = new int[count];
        for (int i = 0; i < count; i++) s[i] = fixed != 0 ? (int) fixed : (int) u32(d, 12 + i * 4);
        cur.sizes = s;
    }

    private void parseStsc(long body, int len) throws IOException {
        byte[] d = read(body, len);
        int count = (int) u32(d, 4);
        cur.stscFirst = new int[count];
        cur.stscCount = new int[count];
        for (int i = 0; i < count; i++) {
            cur.stscFirst[i] = (int) u32(d, 8 + i * 12);
            cur.stscCount[i] = (int) u32(d, 12 + i * 12);
        }
    }

    private void parseChunkOffsets(long body, int len, boolean wide) throws IOException {
        byte[] d = read(body, len);
        int count = (int) u32(d, 4);
        long[] o = new long[count];
        for (int i = 0; i < count; i++) {
            o[i] = wide ? (u32(d, 8 + i * 8) << 32) | u32(d, 12 + i * 8) : u32(d, 8 + i * 4);
        }
        cur.chunkOffsets = o;
    }

    private void build(Track t) {
        cookie = t.cookie;
        sizes = t.sizes;
        offsets = new long[t.sizes.length];
        int sample = 0;
        for (int chunk = 0; chunk < t.chunkOffsets.length && sample < sizes.length; chunk++) {
            int run = 0;
            for (int i = 0; i < t.stscFirst.length; i++) if (t.stscFirst[i] <= chunk + 1) run = i;
            long off = t.chunkOffsets[chunk];
            for (int j = 0; j < t.stscCount[run] && sample < sizes.length; j++) {
                offsets[sample] = off;
                off += sizes[sample];
                sample++;
            }
        }
    }
}
