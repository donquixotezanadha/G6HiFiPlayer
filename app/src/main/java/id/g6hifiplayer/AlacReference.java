package id.g6hifiplayer;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/**
 * Hifi 1.28: decodes a whole ALAC file with [AlacFrameDecoder] and returns the MD5 of the 24-bit packed PCM the player is
 * supposed to send, plus the same per-second digests / empty-low-byte count / first bytes that [FlacReference] returns.
 *
 * ALAC files carry no MD5 of their own, so this is a PIPELINE check (decoder output vs what reaches the DIRECT track), not an
 * independent decoder: the decoder itself is proven bit-exact against ffmpeg by tools/alac-selftest in CI.
 */
public final class AlacReference {
    private AlacReference() { }

    /** Returns null when the file is not an ALAC file this code handles, is damaged, or [cancelled] turned true. */
    public static FlacReference.Result decode(AlacMp4Reader.Source source, BooleanSupplier cancelled) {
        try {
            AlacMp4Reader r = AlacMp4Reader.open(source);
            if (r == null) return null;
            AlacFrameDecoder d = new AlacFrameDecoder(r.cookie);
            if (d.channels() != 2) return null;
            int rate = d.sampleRate();
            MessageDigest md = MessageDigest.getInstance("MD5");
            MessageDigest chunkMd = MessageDigest.getInstance("MD5");
            ArrayList<byte[]> chunks = new ArrayList<>();
            byte[] head = new byte[48];
            int headLen = 0;
            long total = 0, lowZero = 0, chunkFill = 0;
            byte[] packet = new byte[1 << 16];
            byte[] pcm = new byte[d.frameLength() * 6];
            for (int p = 0; p < r.sizes.length; p++) {
                if (cancelled != null && cancelled.getAsBoolean()) return null;
                int size = r.sizes[p];
                if (packet.length < size) packet = new byte[size];
                source.read(r.offsets[p], packet, 0, size);
                int frames = d.decode(packet, 0, size);
                int n = d.writePcm24(pcm, 0);
                md.update(pcm, 0, n);
                if (headLen < head.length) {
                    int c = Math.min(head.length - headLen, n);
                    System.arraycopy(pcm, 0, head, headLen, c);
                    headLen += c;
                }
                for (int i = 0; i < n; i += 3) if (pcm[i] == 0) lowZero++;
                int done = 0;
                while (done < frames) {
                    int take = (int) Math.min(frames - done, rate - chunkFill);
                    chunkMd.update(pcm, done * 6, take * 6);
                    chunkFill += take;
                    done += take;
                    if (chunkFill == rate) { chunks.add(chunkMd.digest()); chunkFill = 0; }
                }
                total += frames;
            }
            if (chunkFill > 0) chunks.add(chunkMd.digest());
            // bits = 24: the carrier that reaches the DIRECT track (16 / 20-bit samples are shifted up inside 24 bits)
            return new FlacReference.Result(md.digest(), total, 24, rate, chunks, lowZero, head);
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
