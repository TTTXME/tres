package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.util.Arrays;

/** Noise model statistics and exact selection, independent of filtering. */
final class NoiseMath {
    private NoiseMath() {}
    static float snr(float mu, float g01, float sG, float rG, float rhoS, float rhoR) {
        double var = Math.max(g01, 1e-6) * 0.5 * (rhoS * sG * Math.max(mu, 0f) + rhoR * rG);
        return var > 0 ? (float) (mu / Math.sqrt(var)) : 1000f;
    }

    /**
     * P320: the noise terms of a DCG shot with measured zones in ScamDenoise's white-balanced RGB. {@code zones} = {slope S,
     * high-gain offset, low-gain offset, knee t} (NoiseModeler.dcgZones: validated), measured by the worker on one green phase
     * in normalised units; per channel S_c = S camS_c / camS_G and O_c = O camO_c / camO_G (the camera model's channel ratios,
     * 1 without a model or for a non-positive green term), weighted as the single curve: sY = sum wY^2 g S_c, rY = sum
     * wY^2 g^2 O_c, the green terms for the SNR, g = 1 / white point (1 for a missing or non-positive one); the knee in Y is
     * green's level g_G t (a neutral grey's luminance; 0 for one measured zone, whose two offsets are equal). Offsets at least
     * 1e-12 and slopes at least 1e-9 per channel (as the camera model's).
     *
     * @return {sY, rY high gain, rY low gain, sG, rG high gain, rG low gain, knee in Y}
     */
    static float[] dcgTerms(float[] zones, float[] camS, float[] camO, float[] whitePoint) {
        final float[] wY = {0.2126f, 0.7152f, 0.0721996f};
        final float sG0 = camS != null && camS.length > 2 && camS[1] > 0f ? camS[1] : 0f;
        final float oG0 = camO != null && camO.length > 2 && camO[1] > 0f ? camO[1] : 0f;
        final float[] out = new float[7];
        for (int c = 0; c < 3; c++) {
            final float rs = sG0 > 0f && camS[c] > 0f && Float.isFinite(camS[c] / sG0) ? camS[c] / sG0 : 1f;
            final float ro = oG0 > 0f && camO[c] > 0f && Float.isFinite(camO[c] / oG0) ? camO[c] / oG0 : 1f;
            final float s = Math.max(1e-9f, zones[0] * rs), oh = Math.max(1e-12f, zones[1] * ro), ol = Math.max(1e-12f, zones[2] * ro);
            final float wp = whitePoint != null && whitePoint.length > c && whitePoint[c] > 1e-6f ? whitePoint[c] : 1f;
            final float g = 1f / wp;
            out[0] += wY[c] * wY[c] * g * s;
            out[1] += wY[c] * wY[c] * g * g * oh;
            out[2] += wY[c] * wY[c] * g * g * ol;
            if (c == 1) {
                out[3] = g * s;
                out[4] = g * g * oh;
                out[5] = g * g * ol;
                out[6] = g * zones[3];
            }
        }
        return out;
    }

    /** IEEE half to float (packHalf2x16 halves of the statistics readback). */
    static float half(int h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
        float v;
        if (e == 0) v = m * (1f / 16777216f);
        else if (e == 31) v = m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = Float.intBitsToFloat(((e - 15 + 127) << 23) | (m << 13));
        return s != 0 ? -v : v;
    }

    /**
     * Noise of one pyramid level from the block statistics (8 floats per block: mean Y, texture T, gY1, gY2, gU1, gU2,
     * gV1, gV2): the median of each g over the flattest quarter of the valid blocks (lowest T; valid = 1e-5 < mean Y <
     * clip), and the colour scale uvs = sqrt(gY / gU), sqrt(gY / gV) per stride as the median of the block ratios over the
     * darker half of those blocks (the reference: the read-noise ratio). Returns null when fewer than 16 blocks are valid.
     * Layout of the result: {gY1, gY2, gU1, gU2, gV1, gV2, uvsU1, uvsU2, uvsV1, uvsV2, blocks used}.
     */
    static float[] reduce(float[] blocks, int count, float clip) {
        int[] valid = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int o = 8 * i;
            float y = blocks[o];
            boolean ok = y > 1e-5f && y < clip && blocks[o + 2] > 0f;
            for (int k = 1; k < 8 && ok; k++) ok = Float.isFinite(blocks[o + k]);
            if (ok) valid[n++] = i;
        }
        if (n < 16) return null;
        // Shot speed: the two stable sorts of reduceLegacy (boxed Integer[] with a Float.compare lambda) as sorts of primitive
        // keys: the float's Float.compare order in the high 32 bits, the position in the input in the low 32 bits. The keys
        // are distinct, so any sort gives the order of the stable sort, element by element.
        final long[] keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = ((long) sortableBits(blocks[8 * valid[i] + 1]) << 32) | i;
        int m = Math.max(16, n / 4);
        // P364: only the m lowest keys are read - selected (O(n)), then sorted: the same first m as the whole sort
        selectSmallest(keys, n, m);
        Arrays.sort(keys, 0, m);
        final int[] byT = new int[m];
        for (int i = 0; i < m; i++) byT[i] = valid[(int) keys[i]];
        float[] out = new float[11];
        float[] tmp = new float[m];
        for (int k = 0; k < 6; k++) {
            for (int i = 0; i < m; i++) tmp[i] = blocks[8 * byT[i] + 2 + k];
            out[k] = medianSelect(tmp, m);
        }
        final long[] keysY = new long[m];
        for (int i = 0; i < m; i++) keysY[i] = ((long) sortableBits(blocks[8 * byT[i]]) << 32) | i;
        Arrays.sort(keysY);
        final int[] byY = new int[m];
        for (int i = 0; i < m; i++) byY[i] = byT[(int) keysY[i]];
        int d = Math.max(8, m / 2);
        float[] r = new float[d];
        for (int s = 0; s < 2; s++) {
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < d; i++) {
                    int o = 8 * byY[i];
                    r[i] = blocks[o + 2 + s] / Math.max(blocks[o + 4 + 2 * c + s], 1e-6f);
                }
                out[6 + 2 * c + s] = (float) Math.sqrt(Math.max(medianSelect(r, d), 1e-4f));
            }
        }
        out[10] = m;
        return out;
    }

    /**
     * P364: {@link #median} without the sort - the middle element(s) of a[0..n) in Arrays.sort's order (Float.compare: the
     * order of {@link #sortableBits}) by selection. The same value, bit for bit (a is not changed).
     */
    static float medianSelect(float[] a, int n) {
        final int[] c = new int[n];
        for (int i = 0; i < n; i++) c[i] = sortableBits(a[i]);
        final float hi = floatOfSortable(selectInt(c, n, n / 2));
        if ((n & 1) == 1) return hi;
        int lo = c[0]; // c[0 .. n/2) holds the n/2 smallest: the largest of them is the element below the middle
        for (int i = 1; i < n / 2; i++) lo = Math.max(lo, c[i]);
        return 0.5f * (floatOfSortable(lo) + hi);
    }

    /** P364: the element at k of a[0..n) in Arrays.sort's order (Float.compare), by selection (a is not changed). */
    static float selectFloat(float[] a, int n, int k) {
        final int[] c = new int[n];
        for (int i = 0; i < n; i++) c[i] = sortableBits(a[i]);
        return floatOfSortable(selectInt(c, n, k));
    }

    /** The inverse of {@link #sortableBits} (NaN: the canonical NaN). */
    static float floatOfSortable(int key) {
        return Float.intBitsToFloat(key ^ ((key >> 31) & 0x7fffffff));
    }

    /** Quickselect: a[0..n) reordered so that a[k] is the k-th smallest, a[0..k) <= a[k] <= a[k+1..n); returns a[k]. */
    static int selectInt(int[] a, int n, int k) {
        int lo = 0, hi = n - 1;
        while (hi > lo) {
            final int mid = (lo + hi) >>> 1;
            // median of three as the pivot (sorted into a[lo], a[mid], a[hi])
            if (a[mid] < a[lo]) { int t = a[mid]; a[mid] = a[lo]; a[lo] = t; }
            if (a[hi] < a[lo]) { int t = a[hi]; a[hi] = a[lo]; a[lo] = t; }
            if (a[hi] < a[mid]) { int t = a[hi]; a[hi] = a[mid]; a[mid] = t; }
            final int pivot = a[mid];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) { int t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else break; // j < k < i: a[k] equals the pivot, in place
        }
        return a[k];
    }

    /** Quickselect on distinct-or-not long keys: a[0..n) reordered so that a[0..m) are its m smallest (in any order). */
    static void selectSmallest(long[] a, int n, int m) {
        if (m <= 0 || m >= n) return;
        final int k = m - 1;
        int lo = 0, hi = n - 1;
        while (hi > lo) {
            final int mid = (lo + hi) >>> 1;
            if (a[mid] < a[lo]) { long t = a[mid]; a[mid] = a[lo]; a[lo] = t; }
            if (a[hi] < a[lo]) { long t = a[hi]; a[hi] = a[lo]; a[lo] = t; }
            if (a[hi] < a[mid]) { long t = a[hi]; a[hi] = a[mid]; a[mid] = t; }
            final long pivot = a[mid];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) { long t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else break;
        }
    }

    /**
     * Signed int whose order is Float.compare's order of the float: -0.0 before 0.0, every NaN (canonical bits) after
     * +Infinity.
     */
    static int sortableBits(float f) {
        final int bits = Float.floatToIntBits(f);
        return bits ^ ((bits >> 31) & 0x7fffffff);
    }

    /** The original {@link #reduce} (boxed sorts), the reference of the primitive one (ShotSpeedBitExactTest) and the old post mode. */
    static float[] reduceLegacy(float[] blocks, int count, float clip) {
        int[] valid = new int[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            int o = 8 * i;
            float y = blocks[o];
            boolean ok = y > 1e-5f && y < clip && blocks[o + 2] > 0f;
            for (int k = 1; k < 8 && ok; k++) ok = Float.isFinite(blocks[o + k]);
            if (ok) valid[n++] = i;
        }
        if (n < 16) return null;
        Integer[] byT = new Integer[n];
        for (int i = 0; i < n; i++) byT[i] = valid[i];
        Arrays.sort(byT, (a, b) -> Float.compare(blocks[8 * a + 1], blocks[8 * b + 1]));
        int m = Math.max(16, n / 4);
        float[] out = new float[11];
        float[] tmp = new float[m];
        for (int k = 0; k < 6; k++) {
            for (int i = 0; i < m; i++) tmp[i] = blocks[8 * byT[i] + 2 + k];
            out[k] = median(tmp, m);
        }
        Integer[] byY = Arrays.copyOf(byT, m);
        Arrays.sort(byY, (a, b) -> Float.compare(blocks[8 * a], blocks[8 * b]));
        int d = Math.max(8, m / 2);
        float[] r = new float[d];
        for (int s = 0; s < 2; s++) {
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < d; i++) {
                    int o = 8 * byY[i];
                    r[i] = blocks[o + 2 + s] / Math.max(blocks[o + 4 + 2 * c + s], 1e-6f);
                }
                out[6 + 2 * c + s] = (float) Math.sqrt(Math.max(median(r, d), 1e-4f));
            }
        }
        out[10] = m;
        return out;
    }

    static float median(float[] a, int n) {
        float[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return (n & 1) == 1 ? c[n / 2] : 0.5f * (c[n / 2 - 1] + c[n / 2]);
    }

    /** White-noise level gains when nothing could be measured: difference variance 2 sigma^2, kernel energy per level. */
    static float whiteGain(int level, boolean chroma) {
        return (float) (2.0 * Math.pow(chroma ? 0.09765625 : 0.19269013, level));
    }

    static String fmt(float[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append('/'); sb.append(Math.round(v[i] * 100f) / 100f); }
        return sb.toString();
    }
}
