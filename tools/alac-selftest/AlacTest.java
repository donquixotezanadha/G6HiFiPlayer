import id.g6hifiplayer.AlacFrameDecoder;
import id.g6hifiplayer.AlacMp4Reader;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

/** Decodes an ALAC .m4a with AlacFrameDecoder and prints the MD5 of the 24-bit little-endian PCM. */
public class AlacTest {
    public static void main(String[] a) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(a[0], "r")) {
            AlacMp4Reader r = AlacMp4Reader.open(new AlacMp4Reader.Source() {
                public long length() throws java.io.IOException { return f.length(); }
                public void read(long pos, byte[] dst, int off, int len) throws java.io.IOException { f.seek(pos); f.readFully(dst, off, len); }
            });
            if (r == null) { System.out.println("NO-ALAC-TRACK"); System.exit(2); }
            AlacFrameDecoder d = new AlacFrameDecoder(r.cookie);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] pkt = new byte[1 << 16];
            byte[] pcm = new byte[d.frameLength() * d.channels() * 3];
            long frames = 0, samples = 0, lowZero = 0;
            for (int p = 0; p < r.sizes.length; p++) {
                if (pkt.length < r.sizes[p]) pkt = new byte[r.sizes[p]];
                f.seek(r.offsets[p]); f.readFully(pkt, 0, r.sizes[p]);
                frames += d.decode(pkt, 0, r.sizes[p]);
                int n = d.writePcm24(pcm, 0);
                md.update(pcm, 0, n);
                for (int i = 0; i < n; i += 3) { samples++; if (pcm[i] == 0) lowZero++; }
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x));
            System.out.println(sb + " frames=" + frames + " ch=" + d.channels() + " bits=" + d.sampleSize() + " rate=" + d.sampleRate()
                + " lowZero=" + (samples == 0 ? 0 : lowZero * 100 / samples) + "%");
        }
    }
}
