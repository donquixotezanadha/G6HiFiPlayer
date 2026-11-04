import id.g6hifiplayer.FlacFrameDecoder;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;

public class FlacTest {
    public static void main(String[] a) throws Exception {
        byte[] f = Files.readAllBytes(Paths.get(a[0]));
        int p = 4; // after fLaC
        int channels = 0, bits = 0;
        boolean last = false;
        while (!last) {
            int h = f[p] & 0xFF; last = (h & 0x80) != 0; int type = h & 0x7F;
            int len = ((f[p+1]&0xFF)<<16)|((f[p+2]&0xFF)<<8)|(f[p+3]&0xFF);
            if (type == 0) {
                channels = ((f[p+4+12] >> 1) & 7) + 1;
                bits = (((f[p+4+12] & 1) << 4) | ((f[p+4+13] & 0xFF) >> 4)) + 1;
            }
            p += 4 + len;
        }
        FlacFrameDecoder d = new FlacFrameDecoder(channels, bits);
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] pcm = new byte[65536 * 2 * 3];
        long frames = 0, lowZero = 0, samples = 0;
        while (p < f.length) {
            // hand over the remaining bytes; decode() reports how many it used
            byte[] rest = java.util.Arrays.copyOfRange(f, p, Math.min(f.length, p + (1 << 18)));
            int used = d.decode(rest, 0, rest.length);
            int n = d.writePcm24(pcm, 0);
            md.update(pcm, 0, n);
            for (int i = 0; i < n; i += 3) { samples++; if (pcm[i] == 0) lowZero++; }
            frames += d.blockSize();
            p += used;
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        System.out.println(sb + " frames=" + frames + " ch=" + channels + " bits=" + bits + " lowZero=" + (samples == 0 ? 0 : lowZero * 100 / samples) + "%");
    }
}
