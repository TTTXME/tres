package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.util.Locale;

/**
 * P614 «Why тон» V2: the reference tone's statistics and exposure, ported one to one from its native post processor (v2.07
 * stage 1, 0x136c78-0x137810: histogram_cs on the clean RGB with a stride, then the CPU maths) and its fusion strengths, which
 * the reference writes back into its settings (shadow_str, highlight_str, contrast_boost and the curve parameters moved by the
 * flat-scene contrast). Pure Java (no GL, no Android): WhyV2Stats feeds it the strided samples, the unit tests the same maths.
 * Every constant is the reference's; float arithmetic as there (the log sum in double, as its fmadd into d8).
 */
public final class WhyV2Ae {
    private WhyV2Ae() {}

    /** The owner's own-curve points (pref_custom_tone_curve_points_key of the reference configuration). */
    public static final String OWNER_POINTS = "0.0000:0.0000,0.0625:0.0314,0.1250:0.0599,0.1875:0.0881,0.2500:0.1282,0.3125:0.1971,"
            + "0.3750:0.2867,0.4375:0.3915,0.5000:0.5100,0.5625:0.6347,0.6250:0.7542,0.6875:0.8577,0.7500:0.9350,0.8125:0.9757,"
            + "0.8750:0.9937,0.9375:1.0000,1.0000:1.0000";

    /** Histogram bins (histogram_cs: 4096 luma and 4096 max-channel bins, scaled by 1 / clip ceiling). */
    public static final int BINS = 4096;

    /**
     * The reference tone's parameters (its settings struct), with the owner's configuration of the reference app as the defaults
     * where it sets them - the float the config stores (0.14999999, not 0.15) - else the default the reference's native reader
     * passes for a key the config does not hold (SettingsManager.getFloat returns it: skin hue centre 41.25, not the menu's 41.2).
     * research/handoff/PLAN.md P614 lists both. Fixed: V2 has no menu rows.
     */
    public static final class Params {
        // Smart HDR AE (adj_cat_smart_hdr_ae)
        public float aeTarget = 0.14999999f, maxBoost = 5.0f, minLimit = 0.5f, facePriority = 0.7f;
        public float nightDim = 0.049999997f, hlOverflow = 2.0f, hlBlend = 0.5f;
        public float brightThresh = 0.90000004f, darkThresh = 12.5f, darkPixelThresh = 22f, hlBoost = 1.1f, contrastBoost = 0.3f;
        /** pref_exp_ev: the exposure compensation the AE reads as 2^ev (0 in the reference config). */
        public float expEv = 0f;
        // local tonemap (adj_cat_local_tonemap)
        public float highlightStr = 2.1000001f, shadowStr = 2.0f;
        public float weightExtHl = 0.7f, weightHl = 1.0f, weightMid = 2.0f, weightShadow = 1.0f;
        public float weightCenter = 1.0f, blendSmoothness = 0.45000002f, macroContrast = 1.1f, clarity = 0f;
        // global tonemap (adj_cat_global_tonemap)
        public int toneOperator = 3, agxLook = 4, gammaType = 0;
        public float gamma = 2.2f, filmToe = 0f;
        public float agxSlope = 2.45f, agxSp = 1.35f, agxTp = 2.0f, agxMinEv = -8.5f, agxMaxEv = 3.5f, agxSat = 1.0f, agxEv = 0f;
        public boolean agxHueLock = true;
        public float agxInset = 50f;
        public float acesToe = 0.05f, acesPreGain = 1.0f, acesA = 2.61f, acesD = 0.59f;
        public float uchiPreGain = 1.0f, uchiPostGain = 1.0f, uchiP = 1.0f, uchiA = 1.0f, uchiM = 0.22f, uchiL = 0.4f, uchiC = 1.33f, uchiB = 0f;
        // colour (adj_cat_vibrance_optics)
        public float vibStrength = 0f, vibSaturated = 0f, skinProtect = 1.0f, vibSky = 0.25f, vibGreen = 0.05f, vibHl = 0.25f;
        public float vibMid = 0f, vibShadow = 0f, skinHueShift = 0f, skinSatShift = 0f, skinHueCenterDeg = 41.25f, skinLumaMin = 0.16f;
        // HDR settings (adj_cat_hdr_settings)
        public float shoulderStrength = 0.6f, shoulderKnee = 0.6f, shoulderHeadroom = 0.35f;

        /** u_skin_hue_center: the setting in degrees x 0.017453292 in float, as the native reader stores it. */
        public float skinHueCenterRad() {
            return skinHueCenterDeg * 0.017453292f;
        }
        /** The reference's own 3D LUT is off in its config; our «Плёночный LUT» takes its place in the shader (WhyV2Tone). */
        public float lutStrength = 1.0f;
        // the own curve (ToneCurveHelper: its points and seven modifiers, WhyV2Curve) and the stages before the fusion
        // (WhyV2Highlights: bracket_denoise with short frames merged, hr_extract / hr_apply; both 0 in the owner's configuration)
        public String curvePoints = OWNER_POINTS;
        public final float[] curveMods = new float[7];
        public float bracketDenoise = 0f, hrAmount = 0f;
    }

    /** The reference's histograms of one shot (histogram_cs: counts, every strided sample once). */
    public static final class Hist {
        public final int[] luma = new int[BINS], hl = new int[BINS];
        public int total, facePixels;
        /** face_luma_sum_fixed: the sum of uint(luma x 1000) over the face's samples (a uint in the shader: wraps at 2^32). */
        public long faceLumaSum;
    }

    /** The exposure and the fusion strengths of one shot, and the tone parameters the flat-scene contrast moved. */
    public static final class Result {
        public float ae = 1f, clip = 1f, deficit = 1f, geo, meter, p10, p50, p98, p995, ovShare, spread, rr, m, area, realPeak, flat;
        public float effS, effH, contrastBoost, noiseCap;
        public float agxSlope, acesD, uchiA, macroContrast;
        public boolean face;

        public String describe() {
            return String.format(Locale.ROOT, "ae=%.4f clip=%.3f geo=%.5f meter=%.5f p10=%.5f p50=%.5f p98=%.5f p995=%.5f deficit=%.4f"
                            + " noiseCap=%.3f ov=%.4f spread=%.4f rr=%.3f m=%.3f area=%.3f peak=%.4f flat=%.3f effS=%.4f effH=%.4f cb=%.4f"
                            + " slope=%.3f acesD=%.3f uchiA=%.3f macro=%.3f face=%d",
                    ae, clip, geo, meter, p10, p50, p98, p995, deficit, noiseCap, ovShare, spread, rr, m, area, realPeak, flat, effS, effH,
                    contrastBoost, agxSlope, acesD, uchiA, macroContrast, face ? 1 : 0);
        }
    }

    /** histogram_cs u_stride: max(1, max(width / 1024, height / 768)) (integer division, as the native code). */
    public static int stride(int width, int height) {
        return Math.max(1, Math.max(width / 1024, height / 768));
    }

    /** The number of samples along a side of {@code n} pixels at {@code stride}: x = 0, s, 2s, ... below n. */
    public static int samples(int n, int stride) {
        return (n + stride - 1) / stride;
    }

    /** The clip ceiling the reference passes (u_clip_ceiling): the setting above 1.05, else 1. */
    public static float clipCeiling(float ceiling) {
        return ceiling > 1.05f ? ceiling : 1f;
    }

    /** One sample into the histograms (histogram_cs main; {@code inFace}: inside u_face_rect with u_has_face). */
    public static void add(Hist h, float r, float g, float b, float clip, boolean inFace) {
        final float vLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        final float vHl = Math.max(vLuma, Math.max(r, Math.max(g, b)));
        final float inv = 1.0f / Math.max(clip, 0.0001f);
        h.luma[bin(vLuma * inv)]++;
        h.hl[bin(vHl * inv)]++;
        h.total++;
        if (inFace) {
            h.facePixels++;
            h.faceLumaSum = (h.faceLumaSum + uintOf(vLuma * 1000.0f)) & 0xffffffffL;
        }
    }

    /** clamp(int(clamp(v, 0, 1) * 4095.99), 0, 4095); a NaN sample counts in bin 0 (clamp of a NaN is undefined in GLSL). */
    static int bin(float v) {
        if (Float.isNaN(v)) return 0;
        final float c = Math.max(0f, Math.min(1f, v));
        return Math.max(0, Math.min(BINS - 1, (int) (c * 4095.99f)));
    }

    /** GLSL uint(float) of a non-negative value (truncation); a negative luma converts to 0 here (undefined in GLSL). */
    static long uintOf(float v) {
        return v > 0f ? (long) v & 0xffffffffL : 0L;
    }

    /**
     * The native code's fmadd (a x b + c rounded once). The float product is exact in double, so the one double add then the
     * float conversion gives the fused result but for a double rounding about once in 2^29 (Math.fma(float) needs API 33).
     */
    static float fma(float a, float b, float c) {
        return (float) ((double) a * (double) b + (double) c);
    }

    /**
     * The exposure (0x137000-0x137440), the bright share (0x137440-0x137514) and the fusion strengths with the flat-scene contrast
     * (0x137514-0x137810). {@code noiseS} / {@code noiseO}: the noise model (variance S x + O of the normalised raw; both 0: none).
     */
    public static Result finish(Hist h, Params p, float clipCeiling, float noiseS, float noiseO) {
        final Result r = new Result();
        final float clip = clipCeiling(clipCeiling);
        r.clip = clip;
        // the log mean of the luma histogram at the bins' centres (2^-13 = half a bin of 2^-12), each value at least 1e-4
        double logSum = 0;
        for (int i = 0; i < BINS; i++) {
            final int n = h.luma[i];
            if (n == 0) continue;
            float v = ((float) i / 4096f + 0.0001220703125f) * clip;
            if (v < 1e-4f) v = 1e-4f;
            logSum += (double) (float) Math.log(v) * n;
        }
        final int total = Math.max(h.total, 1);
        final float totalF = total, invTotal = 1f / totalF;
        final float step = clip * 0.000244140625f;
        // p10 / p50 on the luma histogram: p10 is taken where the share first reaches 0.1 while it is still 0 (a bin 0 p10 moves
        // on to the next bin, as there); the scan stops at p50
        float p10 = 0f, p50 = 0f;
        int cum = 0;
        boolean half = false;
        for (int i = 0; i < BINS; i++) {
            cum += h.luma[i];
            final float share = (float) cum * invTotal;
            final float v = step * (float) i;
            if (share >= 0.1f && p10 == 0f) p10 = v;
            if (share >= 0.5f) { p50 = v; half = true; break; }
        }
        if (!half) p50 = 0f;
        // p98 / p995 on the max-channel histogram (p98 starts at the clip, p995 at 0; each set once)
        float p98 = clip, p995 = 0f;
        cum = 0;
        for (int i = 0; i < BINS; i++) {
            cum += h.hl[i];
            final float share = (float) cum * invTotal;
            final float v = step * (float) i;
            if (share >= 0.98f && p98 == clip) p98 = v;
            if (share >= 0.995f && p995 == 0f) p995 = v;
        }
        if (p98 == clip) p98 = clip * 0.99975586f;
        if (p995 == 0f) p995 = p98;
        r.p10 = p10; r.p50 = p50; r.p98 = p98; r.p995 = p995;
        final float geo = (float) Math.exp(logSum / (double) total);
        r.geo = geo;
        // the face's luminance towards the meter by the face priority
        float meter = geo;
        final boolean face = h.facePixels > 0;
        r.face = face;
        if (face) {
            float fm = (float) (h.faceLumaSum * 0.001) / (float) h.facePixels;
            fm = Math.max(Math.min(fm, 1f), 0.001f);
            float prio = p.facePriority > 1f ? 1f : p.facePriority;
            if (p.facePriority < 0f) prio = 0f;
            meter = fma(prio, fm - geo, geo);
        }
        r.meter = meter;
        final float ev = (float) Math.pow(2.0, p.expEv);
        final float maxB = Math.max(ev, 1f) * p.maxBoost, minL = Math.min(ev, 1f) * p.minLimit;
        float deficit = 1f, capped;
        float noiseCap = 0f;
        if (meter > 1e-4f) {
            final float a = p.aeTarget * ev / meter;
            float aeC = maxB < a ? maxB : a;
            if (aeC < minL) aeC = minL;
            float aeF = aeC;
            if (p.hlOverflow < 9.99f && p995 > 0.01f && aeC * p995 > p.hlOverflow) {
                final float lim = p.hlOverflow / p995;
                final float blend = fma(face ? p.facePriority : 0f, 1f - p.hlBlend, p.hlBlend);
                aeF = fma(blend, aeC - lim, lim);
            }
            if (aeF < minL) aeF = minL;
            if (aeF > 0.001f && aeC > 0.001f && aeF < aeC) deficit = aeC / aeF;
            capped = aeF;
            if (noiseS > 0f || noiseO > 0f) {
                float v = fma(noiseS, 0.5f, noiseO);
                if (v < 1e-10f) v = 1e-10f;
                final float sigma = (float) Math.sqrt(v);
                float d = Math.max(sigma * 0.5f, 0.026f);
                if (!(d < sigma)) d = sigma;
                float nd = p.nightDim > 0.2f ? 0.2f : p.nightDim;
                if (p.nightDim < 0.01f) nd = 0.01f;
                float cap = nd / d;
                if (cap < 1f) cap = 1f;
                noiseCap = cap;
                if (aeF > cap) capped = cap;
            }
        } else {
            capped = 1f;
        }
        float ae = maxB < capped ? maxB : capped;
        if (ae < minL) ae = minL;
        r.ae = ae;
        r.deficit = deficit;
        r.noiseCap = noiseCap;
        // the share of the frame above the bright threshold after the exposure (max-channel histogram)
        float t = p.brightThresh / (Math.max(ae, 0.001f) * clip);
        t = Math.max(Math.min(t, 1f), 0f);
        final int j = (int) (t * 4095.99f);
        long ov = 0;
        for (int i = Math.max(0, j); i < BINS; i++) ov += h.hl[i];
        final float f = (float) ov / totalF;
        r.ovShare = f;
        // the fusion strengths
        final float sp10 = (float) Math.sqrt(Math.max(ae * p10, 0f));
        float a98 = Math.max(ae * p98, 0f);
        final float sp98 = (float) Math.sqrt(a98 > ae ? ae : a98);
        final float spread = sp98 - sp10;
        final float rr = spread / Math.max(p.darkThresh * 0.15f, 0.001f);
        final float sp50 = (float) Math.sqrt(Math.max(ae * p50, 0f));
        final float m = Math.max(Math.min(fma(sp50, 4.5454545f, -0.9090909f), 1f), 0f);
        // ((shadow_str x (1 - 0.35 m^2)) x rr) x (1 + rr / 2) / ((1 + rr / 2) + rr), the native code's order and fmadds
        final float rrHalf = fma(rr, 0.5f, 1f);
        float effS = p.shadowStr * fma(m * m, -0.35f, 1f) * rr * rrHalf / (rrHalf + rr);
        effS = fma((float) (Math.log(Math.max(deficit, 1f)) / Math.log(2.0)), 0.5f, effS);
        effS = Math.max(effS, 0f);
        float s20 = f * 20f;
        float area = s20 > 1f ? 1f : s20;
        if (s20 < 0f) area = 0f;
        area = area * area * (3f - 2f * area);
        float tail = Math.max(ae * p995 - a98, 0f);
        final float lim = 1.5f * Math.max(p.brightThresh, 0.5f);
        if (lim < tail) tail = lim;
        final float realPeak = fma(area, 0.25f * tail, a98);
        final float bt85 = p.brightThresh * 0.85f;
        float soft = 0f;
        if (realPeak > bt85) {
            final float ex = (float) (Math.log(realPeak / bt85) / Math.log(2.0));
            final float knee = (float) (Math.log(p.brightThresh / bt85) / Math.log(2.0));
            if (ex < knee) {
                final float u = ex / knee;
                soft = u * u * (knee * 0.5f);
            } else {
                soft = fma(knee, -0.5f, ex);
            }
            soft *= 0.8333333f;
        }
        float bigArea = fma(f, 5f, -0.25f);
        final float gate = bigArea < 0f ? 1f : fma(bigArea > 1f ? 1f : bigArea, 0.35f, 1f);
        float q = 1f - spread / Math.max(p.darkPixelThresh * 0.05f, 0.001f);
        q = Math.max(q, 0f);
        float effH = fma(area, 0.8f, 0.2f) * (gate * soft * Math.max(p.hlBoost, 0f));
        if (!(effH < p.highlightStr)) effH = p.highlightStr;
        final float flat = q * q * (3f - 2f * q);
        // (q^2 x contrast_boost) x (3 - 2q), the native order
        final float cb = q * q * p.contrastBoost * (3f - 2f * q);
        r.spread = spread; r.rr = rr; r.m = m; r.area = area; r.realPeak = realPeak; r.flat = flat;
        r.effS = effS;
        r.effH = effH;
        r.contrastBoost = cb;
        r.agxSlope = p.agxSlope; r.acesD = p.acesD; r.uchiA = p.uchiA; r.macroContrast = p.macroContrast;
        if (cb > 1e-4f) {
            r.acesD = Math.max(fma(cb, -0.8f, p.acesD), 0.1f);
            r.agxSlope = p.agxSlope + cb;
            r.uchiA = fma(cb, 0.75f, p.uchiA);
            r.macroContrast = fma(cb, 0.3f, p.macroContrast);
        }
        return r;
    }

    /**
     * ef_pyr_prep u_exp_multipliers (0x13a634-0x13a770): the darkest 2^(-2.5 effH), the second 2^(-1.2 effH), the base 1, the
     * bright min(2^(1.8 effS), 16) - in the order of the layer weights (ext_hl, hl, mid, shadow).
     */
    public static float[] exposures(Result r) {
        return new float[]{(float) Math.pow(2.0, -2.5f * r.effH), (float) Math.pow(2.0, -1.2f * r.effH), 1f,
                (float) Math.min(Math.pow(2.0, 1.8f * r.effS), 16.0)};
    }

    /** ef_pyr_prep u_layer_weights: (ext_hl, hl, mid, shadow). */
    public static float[] layerWeights(Params p) {
        return new float[]{p.weightExtHl, p.weightHl, p.weightMid, p.weightShadow};
    }

    /**
     * The Laplacian pyramid's level sizes (0x139f70-0x13a300): level 0 the full image, then halving (the width w / 2, the height
     * h / 2 but 1 for h <= 3) while both sides of the last level are above 48 and at most 12 levels in all (the native loop stops
     * at 12 entries).
     */
    public static int[][] levels(int width, int height) {
        final java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int w = width, h = height;
        out.add(new int[]{w, h});
        while (w > 48 && h > 48 && out.size() <= 11) {
            w = w >> 1;
            h = h > 3 ? h / 2 : 1;
            out.add(new int[]{w, h});
        }
        return out.toArray(new int[0][]);
    }

    /**
     * tonemap_final u_agx_mat_inset: lerp(I, the AgX inset, t) with t = inset strength (a value above 1 is a percentage),
     * column-major for glUniformMatrix3fv without transpose (0x13c068-0x13c230).
     */
    public static float[] insetMatrix(float strength) {
        float t = strength > 1f ? strength * 0.01f : strength;
        t = Math.max(0f, Math.min(1f, t));
        final double[] m = {0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                0.0784335999999992, 0.878468636469772, 0.0784336,
                0.0792237451477643, 0.0791661274605434, 0.879142973793104};
        final float[] out = new float[9];
        for (int i = 0; i < 9; i++) {
            final double id = i % 4 == 0 ? 1.0 : 0.0;
            out[i] = (float) ((1.0 - t) * id + t * m[i]);
        }
        return out;
    }

    /** u_agx_mat_outset: the inverse of {@link #insetMatrix} (column-major), computed in double as there. */
    public static float[] outsetMatrix(float strength) {
        final float[] a = insetMatrix(strength);
        // column-major a[c * 3 + r]
        final double a00 = a[0], a10 = a[1], a20 = a[2], a01 = a[3], a11 = a[4], a21 = a[5], a02 = a[6], a12 = a[7], a22 = a[8];
        // the adjugate, row-major: r[i * 3 + j] = det x (A^-1)(row i, col j)
        final double[] r = {
                a11 * a22 - a12 * a21, a02 * a21 - a01 * a22, a01 * a12 - a02 * a11,
                a12 * a20 - a10 * a22, a00 * a22 - a02 * a20, a02 * a10 - a00 * a12,
                a10 * a21 - a11 * a20, a01 * a20 - a00 * a21, a00 * a11 - a01 * a10};
        final double det = a00 * r[0] + a01 * r[3] + a02 * r[6];
        final double inv = Math.abs(det) > 1e-12 ? 1.0 / det : 1.0;
        // to column-major: element (row i, col j) at [j * 3 + i]
        final float[] out = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) out[j * 3 + i] = (float) (r[i * 3 + j] * inv);
        return out;
    }
}
