package net.sourceforge.jaad.aac.tools;

import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.SampleFrequency;
import net.sourceforge.jaad.aac.syntax.BitStream;
import net.sourceforge.jaad.aac.syntax.Constants;
import net.sourceforge.jaad.aac.syntax.ICSInfo;
import net.sourceforge.jaad.aac.syntax.ICStream;

/**
 * Temporal Noise Shaping
 * @author in-somnia
 */
public class TNS implements Constants, TNSTables {

	private static final int TNS_MAX_ORDER = 20;
	private static final int[] SHORT_BITS = {1, 4, 3}, LONG_BITS = {2, 6, 5};
	//bitstream
	private int[] nFilt;
	private int[][] length, order;
	private boolean[][] direction;
	private float[][][] coef;

	public TNS() {
		nFilt = new int[8];
		length = new int[8][4];
		direction = new boolean[8][4];
		order = new int[8][4];
		coef = new float[8][4][TNS_MAX_ORDER];
	}

	public void decode(BitStream in, ICSInfo info) throws AACException {
		final int windowCount = info.getWindowCount();
		final int[] bits = info.isEightShortFrame() ? SHORT_BITS : LONG_BITS;

		int w, i, filt, coefLen, coefRes, coefCompress, tmp;
		for(w = 0; w<windowCount; w++) {
			if((nFilt[w] = in.readBits(bits[0]))!=0) {
				coefRes = in.readBit();

				for(filt = 0; filt<nFilt[w]; filt++) {
					length[w][filt] = in.readBits(bits[1]);

					if((order[w][filt] = in.readBits(bits[2]))>20) throw new AACException("TNS filter out of range: "+order[w][filt]);
					else if(order[w][filt]!=0) {
						direction[w][filt] = in.readBool();
						coefCompress = in.readBit();
						coefLen = coefRes+3-coefCompress;
						tmp = 2*coefCompress+coefRes;

						for(i = 0; i<order[w][filt]; i++) {
							coef[w][filt][i] = TNS_TABLES[tmp][in.readBits(coefLen)];
						}
					}
				}
			}
		}
	}

	/**
	 * Hifi iTunes 1: TNS maximum number of bands per sampling-frequency index (long, short window), AAC LC / Main.
	 */
	private static final int[] MAX_BANDS_LONG = {31, 31, 34, 40, 42, 51, 46, 46, 42, 42, 42, 39, 39, 0, 0, 0};
	private static final int[] MAX_BANDS_SHORT = {9, 9, 10, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 0, 0, 0};

	/**
	 * Hifi iTunes 1: the original library only parsed TNS and never applied it (empty method), so every frame that uses
	 * TNS decoded slightly wrong. This is the decoder-side TNS: an all-pole (AR) filter over the spectral coefficients,
	 * built from the transmitted reflection coefficients, run upward or downward through the filtered bands.
	 */
	public void process(ICStream ics, float[] spec, SampleFrequency sf, boolean decode) {
		final ICSInfo info = ics.getInfo();
		final boolean shortWin = info.isEightShortFrame();
		final int windowCount = info.getWindowCount();
		final int winLen = shortWin ? 128 : 1024;
		final int[] offsets = info.getSWBOffsets();
		final int numSwb = info.getSWBCount();
		final int maxSfb = info.getMaxSFB();
		final int sfIdx = Math.max(0, Math.min(15, sf.getIndex()));
		final int maxBands = shortWin ? MAX_BANDS_SHORT[sfIdx] : MAX_BANDS_LONG[sfIdx];

		final float[] lpc = new float[TNS_MAX_ORDER+1];
		final float[] b = new float[TNS_MAX_ORDER+1];
		final float[] state = new float[TNS_MAX_ORDER];

		for(int w = 0; w<windowCount; w++) {
			int bottom = numSwb;
			for(int filt = 0; filt<nFilt[w]; filt++) {
				final int top = bottom;
				bottom = Math.max(top-length[w][filt], 0);
				final int ord = Math.min(order[w][filt], TNS_MAX_ORDER);
				if(ord==0) continue;

				//reflection coefficients -> LPC (step-up recursion); the tables carry the opposite sign of the usual convention
				lpc[0] = 1f;
				for(int m = 1; m<=ord; m++) {
					final float refl = -coef[w][filt][m-1];
					lpc[m] = refl;
					for(int i = 1; i<m; i++) b[i] = lpc[i]+refl*lpc[m-i];
					for(int i = 1; i<m; i++) lpc[i] = b[i];
				}

				int start = Math.min(Math.min(bottom, maxBands), maxSfb);
				start = offsets[start];
				int end = Math.min(Math.min(top, maxBands), maxSfb);
				end = offsets[end];
				final int size = end-start;
				if(size<=0) continue;

				final int inc;
				int pos;
				if(direction[w][filt]) {
					inc = -1;
					pos = w*winLen+end-1;
				}
				else {
					inc = 1;
					pos = w*winLen+start;
				}

				java.util.Arrays.fill(state, 0, ord, 0f);
				for(int n = 0; n<size; n++, pos += inc) {
					float y = spec[pos];
					for(int j = 0; j<ord; j++) y -= state[j]*lpc[j+1];
					for(int j = ord-1; j>0; j--) state[j] = state[j-1];
					state[0] = y;
					spec[pos] = y;
				}
			}
		}
	}
}
