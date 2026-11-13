package com.scamera.app.processing.oldstyle;

import java.util.Arrays;
import java.util.Locale;

/**
 * P630e «Oldstyle»: the exposures of the mode by the owner's supplement to tone-port (third_party/tone_supplement,
 * hdr-ae-color-27-31.md): its rules ported to Java in FP32 ({@link #plan27}, {@link #plan31}, {@link #capCaptureRatio},
 * {@link #compensateTet}, {@link #fusionGains}; the host check and OldstyleAeTest hold them to the document's 45 table rows and
 * to the C++ block), fed by our metering ({@link #meter}). The supplement leaves the per-frame AE estimator external; ours:
 * <ul>
 *   <li>S* (the short candidate) keeps the frame's light: its {@link #HIGH_PERCENTILE} of max(R, G, B) at {@link #SHORT_WHITE} of
 *       white; P643: a light clipped in the frame (it holds no more than "at least white") is kept where the frame has it - the
 *       frame is the short exposure then, as a burst exposed at S was ({@link #clipShare});</li>
 *   <li>L* / S* (the ratio the plans decide on) a log-space fit of the document's two tables ({@link #ratioFit}: features log of
 *       the light, of the luma's geometric and arithmetic means; one fit per profile; 14 of the 15 control scenes take the
 *       tables' HDR decision, the miss is scene 12, the checkerboard);</li>
 *   <li>F, the weighted share of the frame assigned to the long exposure: the samples the long candidate does not clip, weighted by
 *       a centred Gaussian of {@link #F_SIGMA} of the frame (fitted to the tables' F: scenes 09, 10, 13, 14 within 0.005).</li>
 * </ul>
 * Units: TET over the metered frame's own (TET 1 = the frame as it is). The tone's two virtual exposures come from one frame at its
 * actual TET S0 (fusion_gains: S0 in the denominator, no gain applied twice); without HDR one gain, L* / S0 (the SDR TET is the
 * long candidate). The style's «Экспозиция» acts here, before the tone ({@link Params#exposureEv}): both TETs times 2^EV, a real
 * exposure change for the curve and the fusion.
 */
public final class OldstyleAe {
    private OldstyleAe() {}

    /** S* keeps the light percentile at this share of white. */
    public static final float SHORT_WHITE = 0.95f;
    public static final float HIGH_PERCENTILE = 0.997f;
    /** The light percentile at or above this share of the frame's ceiling is clipped there (a clipped sample is the ceiling itself). */
    public static final float CLIP_LEVEL = 0.995f;
    /**
     * The clipped light's rule comes in softly (no jump at a threshold): S* leaves its place under white from {@link #CLIP_NEAR} of
     * the ceiling (below it a light is data: the tables' scenes go up to 0.9775).
     */
    public static final float CLIP_NEAR = 0.98f;

    /**
     * P643 (owner: «Для Oldstyle убери своей AE, используй такой же как и у режима фото»): the frames come at the photo mode's
     * exposure, the tone only decides its gains. How far a light at {@code share} of the frame's ceiling is a clipped one: 0 up to
     * {@code from}, 1 from {@link #CLIP_LEVEL}, linear between. A clipped light is "at least white": no exposure the tone
     * could ask for shows more of it, so for it the frame itself is the short exposure (S* = the frame's TET: fusion_gains then
     * gives the short gain base ^ (L / S), what a burst exposed at S got). P643d: this is our estimator's input only (the
     * supplement leaves the per-frame AE estimator external) - plan27 / plan31 / fusion_gains take this S* as they would any;
     * without HDR the frame is the one exposure as the AE gave it (OldstyleAe.plan), so P643's floor of L* went. An unclipped
     * frame: the meter as it was.
     */
    public static float clipShare(float share, float from) {
        return Math.max(0f, Math.min(1f, (share - from) / (CLIP_LEVEL - from)));
    }
    /** F's centred Gaussian, in shares of the frame's width / height. */
    public static final float F_SIGMA = 0.47f;
    /** The fit of log(L* / S*): {1, log light, log geometric mean luma, log mean luma} (L* fit minus S* fit, per profile). */
    static final float[] RATIO27 = {2.1303f - 2.9937f, -0.3401f + 0.7676f, -1.6873f - 0.0224f, 0.9155f + 0.3935f};
    static final float[] RATIO31 = {2.1886f - 3.0133f, -0.4974f + 0.7101f, -1.7148f - 0.0899f, 1.0632f + 0.5216f};

    // ---- the supplement's rules (tone_supplement.cpp), FP32 --------------------------------------------------------------------

    /** The supplement's numbers (tone_supplement.cpp): the defaults of {@link Params} (P630h: each a row of «Oldstyle Тон»). */
    public static final float V1_HDR_MIN = 1.4500000476837158f, V1_HDR_MAX = 6.803999900817871f, V1_RATIO_CAP = 3.5f;
    public static final float V2_HDR_MIN = 1.3300000429153442f, V2_RATIO_MAX = 8.0f, V2_FORGIVENESS = 1.3999999761581421f;
    public static final float FUSION_BASE = 0.9756097793579102f;
    /** P630h «HDR»: the supplement's rule, always two exposures when L* is above S*, never (one exposure at L*). */
    public static final int HDR_AUTO = 0, HDR_ON = 1, HDR_OFF = 2;

    /** tone_supplement::ExposurePlan. */
    public static final class ExposurePlan {
        public boolean hdrScene, useHdr;
        public float shortCandidate, longCandidate, shortPlan, longPlan;
    }

    static float powf(float a, float b) {
        return (float) Math.pow(a, b);
    }

    /** compensate_tet: clamp(tet * 2^ev, min, max). */
    public static float compensateTet(float tet, float ev, float minTet, float maxTet) {
        return Math.max(minTet, Math.min(maxTet, tet * powf(2.0f, ev)));
    }

    /** cap_capture_ratio: above {@code maxRatio} the excess split between S and L by F. */
    public static void capCaptureRatio(ExposurePlan p, float maxRatio, float fractionLong) {
        final float ratio = p.longCandidate / p.shortCandidate;
        if (ratio <= maxRatio) return;
        final float q = ratio / maxRatio;
        final float w = Math.max(0f, Math.min(1f, (fractionLong - 0.6000000238418579f) * 5.000000476837158f));
        p.shortPlan *= powf(q, w);
        p.longPlan /= powf(q, 1.0f - w);
    }

    /** plan27: HDR within 1.45 .. 6.804 (both included), the ratio capped at 3.5. */
    public static ExposurePlan plan27(float shortCandidate, float longCandidate, float fractionLong) {
        return plan27(shortCandidate, longCandidate, fractionLong, V1_HDR_MIN, V1_HDR_MAX, V1_RATIO_CAP);
    }

    /** P630h: plan27 with its three numbers (HDR from {@code min} to {@code max}, the ratio capped at {@code cap}). */
    public static ExposurePlan plan27(float shortCandidate, float longCandidate, float fractionLong, float min, float max, float cap) {
        final float ratio = longCandidate / shortCandidate;
        final boolean enoughRange = ratio >= min;
        final boolean use = enoughRange && ratio <= max;
        final ExposurePlan p = plan(enoughRange, use, shortCandidate, longCandidate);
        if (use) capCaptureRatio(p, cap, fractionLong);
        return p;
    }

    /** plan31: scene flag at 1.33; RAW max 8 x 1.4, YUV 3.5 x 1.944; capped, then EV and the TET bounds. */
    public static ExposurePlan plan31(float shortCandidate, float longCandidate, float fractionLong, boolean rawPayload,
                                      boolean hdrDisabled, float ev, float minTet, float maxTet) {
        return plan31(shortCandidate, longCandidate, fractionLong, hdrDisabled, ev, minTet, maxTet, V2_HDR_MIN,
                rawPayload ? V2_RATIO_MAX : 3.5f, rawPayload ? V2_FORGIVENESS : 1.9440000057220459f);
    }

    /** P630h: plan31 with its three numbers (the scene flag from {@code min}, the ratio's {@code maxRatio} x {@code forgiveness}). */
    public static ExposurePlan plan31(float shortCandidate, float longCandidate, float fractionLong, boolean hdrDisabled, float ev,
                                      float minTet, float maxTet, float min, float maxRatio, float forgiveness) {
        final float ratio = longCandidate / shortCandidate;
        final boolean scene = ratio >= min;
        final boolean use = scene && !hdrDisabled && ratio <= maxRatio * forgiveness;
        final ExposurePlan p = plan(scene, use, shortCandidate, longCandidate);
        if (use) {
            capCaptureRatio(p, maxRatio, fractionLong);
            p.shortPlan = compensateTet(p.shortPlan, ev, minTet, maxTet);
            p.longPlan = compensateTet(p.longPlan, ev, minTet, maxTet);
        }
        return p;
    }

    private static ExposurePlan plan(boolean scene, boolean use, float s, float l) {
        final ExposurePlan p = new ExposurePlan();
        p.hdrScene = scene;
        p.useHdr = use;
        p.shortCandidate = p.shortPlan = s;
        p.longCandidate = p.longPlan = l;
        return p;
    }

    /** fusion_gains: {short, long} from the actual short TET (tone::raw_hdr_gains_31 is the same with common gain 1). */
    public static float[] fusionGains(float actualShortTet, float desiredShortTet, float desiredLongTet, float commonGain,
                                      float maxGain) {
        return fusionGains(actualShortTet, desiredShortTet, desiredLongTet, commonGain, maxGain, FUSION_BASE);
    }

    /** P630h: fusion_gains with its base (the short exposure's trim: base ^ (L / S)). */
    public static float[] fusionGains(float actualShortTet, float desiredShortTet, float desiredLongTet, float commonGain,
                                      float maxGain, float base) {
        final float adjustedShort = powf(base, desiredLongTet / desiredShortTet) * desiredShortTet;
        final float shortGain = (adjustedShort / actualShortTet) * commonGain;
        final float longGain = (desiredLongTet / actualShortTet) * commonGain;
        final float threshold = 1.0010000467300415f * maxGain;
        return new float[]{shortGain > threshold ? maxGain : shortGain, longGain > threshold ? maxGain : longGain};
    }

    /** fusion_gains_yuv: the short TET from its gain relative to the actual one. */
    public static float[] fusionGainsYuv(float actualShortTet, float shortRelativeGain, float desiredLongTet, float commonGain,
                                         float maxGain) {
        return fusionGains(actualShortTet, actualShortTet * shortRelativeGain, desiredLongTet, commonGain, maxGain);
    }

    // ---- our metering ------------------------------------------------------------------------------------------------------------

    /** What {@link #meter} read of a frame (TET over the frame's own). */
    public static final class Meter {
        public float high, geoMean, mean;
        public boolean clipped;
        /** S* and L* over the frame's TET. */
        public float shortRel, longRel;
        public float fractionLong;
        /** P630g: max(R, G, B) of every sample, ascending (the light's distribution: OldstyleLive's white of a clipped frame). */
        public float[] sortedMax;
        /** P630g: the luma of every sample, ascending (OldstyleLive: the ISP's gain over this frame from the screen frame's mean). */
        public float[] sortedLuma;

        public float ratio() {
            return longRel / shortRel;
        }
    }

    /** The fitted L* / S* of the features (one profile). */
    public static float ratioFit(boolean v31, float high, float geoMean, float mean) {
        final float[] c = v31 ? RATIO31 : RATIO27;
        final double l = c[0] + c[1] * Math.log(high) + c[2] * Math.log(geoMean) + c[3] * Math.log(mean);
        return (float) Math.exp(l);
    }

    /**
     * Meters {@code rgb} (interleaved linear RGB of {@code w} x {@code h} samples, row-major; 1 = the frame's white): see the class
     * note. {@code ceiling}: the largest value the frame can hold (1 for a plain frame).
     */
    public static Meter meter(float[] rgb, int w, int h, boolean v31, float ceiling) {
        final Params p = new Params();
        p.v31 = v31;
        return meter(rgb, w, h, p, ceiling);
    }

    /**
     * P630h: the meter with the settings' numbers ({@code p}: the light's percentile and its place under white, the shadows' shift
     * of L*, F's centre weighting; the profile {@code p.v31}).
     */
    public static Meter meter(float[] rgb, int w, int h, Params p, float ceiling) {
        final boolean v31 = p.v31;
        final int n = Math.min(rgb.length / 3, Math.max(0, w) * Math.max(0, h));
        final Meter m = new Meter();
        final float[] max = new float[n], luma = new float[n];
        double logSum = 0, sum = 0;
        for (int i = 0; i < n; i++) {
            final float r = clean(rgb[i * 3], ceiling), g = clean(rgb[i * 3 + 1], ceiling), b = clean(rgb[i * 3 + 2], ceiling);
            max[i] = Math.max(r, Math.max(g, b));
            final float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            luma[i] = y;
            logSum += Math.log(Math.max(1e-4f, y));
            sum += y;
        }
        Arrays.sort(luma);
        final float[] sorted = max.clone();
        Arrays.sort(sorted);
        m.sortedMax = sorted;
        m.sortedLuma = luma;
        m.high = n == 0 ? 1f : Math.max(1e-3f, sorted[Math.max(0, Math.min(n - 1, (int) (p.highPercentile * (n - 1))))]);
        m.geoMean = n == 0 ? 0.18f : (float) Math.exp(logSum / n);
        m.mean = n == 0 ? 0.18f : Math.max(1e-4f, (float) (sum / n));
        m.clipped = m.high >= CLIP_LEVEL * ceiling;
        // P643: a clipped light stays where the frame has it (clipShare): S* from the settings' place under white up to the
        // frame's own TET, L* not below it
        final float light = m.high, near = clipShare(m.high / ceiling, CLIP_NEAR);
        final float white = near <= 0f || p.shortWhite >= 1f ? p.shortWhite : (float) Math.pow(p.shortWhite, 1.0 - near);
        m.shortRel = white / light;
        m.longRel = m.shortRel * ratioFit(v31, light, m.geoMean, m.mean) * (p.shadowEv == 0f ? 1f : powf(2f, p.shadowEv));
        double kept = 0, total = 0;
        final double k = 1.0 / (2.0 * p.centerSigma * p.centerSigma);
        for (int y = 0; y < h; y++) {
            final double dy = (h > 1 ? y / (double) (h - 1) : 0.5) - 0.5;
            for (int x = 0; x < w; x++) {
                final int i = y * w + x;
                if (i >= n) break;
                final double dx = (w > 1 ? x / (double) (w - 1) : 0.5) - 0.5;
                final double wt = Math.exp(-(dx * dx + dy * dy) * k);
                total += wt;
                if (max[i] * m.longRel < 1f) kept += wt;
            }
        }
        m.fractionLong = total > 0 ? (float) (kept / total) : 0f;
        return m;
    }

    private static float clean(float v, float ceiling) {
        return Float.isFinite(v) ? Math.max(0f, Math.min(ceiling, v)) : 0f;
    }

    // ---- the shot's plan -----------------------------------------------------------------------------------------------------------

    public static final class Params {
        public boolean v31;
        /** M of the gains (fusion_gains / raw_hdr_gains_31: the module's maxDigitalGain). */
        public float maxGain = 16f;
        /** V2: the module's saturation_adjustment (0: its own colour output). */
        public float saturation = 0f;
        /** The style's «Экспозиция» in EV (before the tone; OldstyleStyle.exposureEv). */
        public float exposureEv = 0f;
        /** The supplement's dehaze / tint on our RGB8 (its YUV444 pass). */
        public boolean dehaze = true;
        public int maxDark = DEHAZE_MAX_DARK;
        public float percentile = DEHAZE_PERCENTILE, cornerStrength = DEHAZE_CORNER;
        /** The dehaze's U / V gain grid from our lens-shading gain map (off: the unit grid). */
        public boolean dehazeShading = true;
        /** The supplement's output saturation (neutral: sat 0, vibrance 1 - the stage is skipped). */
        public float outSat = 0f, vibrance = 1f, satPerStop = 0f, vibrancePerStop = 0f;
        /**
         * V1 HDR: the low-frequency colour from our merge as the reference (the colour the 8-bit retone of the short exposure loses
         * in the shadows), and its threshold.
         */
        public boolean lowFrequency = true;
        public int lfThreshold = LF_THRESHOLD;
        // ---- P630h: the numbers behind the rows of «Oldstyle Тон»; every default the behaviour before the rows ----
        /** {@link #HDR_AUTO}, {@link #HDR_ON}, {@link #HDR_OFF}. */
        public int hdrMode = HDR_AUTO;
        /** plan27: HDR from / to this L* / S*, the ratio's cap. */
        public float v1HdrMin = V1_HDR_MIN, v1HdrMax = V1_HDR_MAX, v1RatioCap = V1_RATIO_CAP;
        /** plan31 (RAW): the scene flag, the ratio's max and its forgiveness. */
        public float v2HdrMin = V2_HDR_MIN, v2RatioMax = V2_RATIO_MAX, v2Forgiveness = V2_FORGIVENESS;
        /** fusion_gains' base: the short exposure's trim by base ^ (L / S). */
        public float fusionBase = FUSION_BASE;
        /** The mode's own exposure shift in EV (before the tone, with the style's). */
        public float baseEv = 0f;
        /** The meter: the light's percentile (share of the samples) and where S* keeps it (share of white). */
        public float highPercentile = HIGH_PERCENTILE, shortWhite = SHORT_WHITE;
        /** The meter: L* over its fit, in EV (the shadows' exposure). */
        public float shadowEv = 0f;
        /** The meter: F's centred Gaussian (shares of the frame's sides; large: the whole frame alike). */
        public float centerSigma = F_SIGMA;
        /** The module's tone curve shaped: the midtones in EV-like steps (0: the module's), the S-contrast (-1 .. 1, 0: the module's). */
        public float curveMid = 0f, curveContrast = 0f;
        /** V1: the colour matrix's share (1: the camera's CCM, 0: none). */
        public float ccmStrength = 1f;
        /** V1's low-frequency colour: only the colour (true), or the brightness too; its share of the transfer (0 .. 1). */
        public boolean lfChroma = true;
        public float lfStrength = 1f;
        /** The frames of a shot (0: the photo mode's count; P643: the count is all of the capture the mode still sets). */
        public int burstFrames = 0;
        /** The viewfinder's tone preview: on, its own brightness in EV, its share of the match to the photo (0 .. 1), its smoothing in seconds. */
        public boolean vfTone = true;
        public float vfEv = 0f, vfMatch = 1f, vfSmooth = 0.5f;
        /** V2: the share of the free memory its buffers may take before V1 runs instead. */
        public float memoryShare = 0.6f;

        /** OldstyleNative.render's post array. */
        public float[] post() {
            final boolean sat = Math.abs(outSat) >= 1f / 256f || Math.abs(vibrance - 1f) >= 1f / 256f
                    || Math.abs(satPerStop) >= 1e-4f || Math.abs(vibrancePerStop) >= 1e-4f;
            return new float[]{dehaze ? 1f : 0f, maxDark, percentile, cornerStrength, sat ? 1f : 0f, outSat, vibrance, satPerStop,
                    vibrancePerStop, lowFrequency ? 1f : 0f, lfThreshold, lfChroma ? 1f : 0f, lfStrength, curveMid, curveContrast};
        }
    }

    /** The dehaze defaults: max_dark 24 keeps its black point at 1.5 x a dark mode of 6..11 (48 lifted it to 18 on 140250, deep shadows crushed). */
    public static final int DEHAZE_MAX_DARK = 24;
    public static final float DEHAZE_PERCENTILE = 0.2f, DEHAZE_CORNER = 0.2f;
    public static final int LF_THRESHOLD = 16;

    /**
     * The plan of a frame: the meter's candidates through the profile's rule (TET over the metered frame's), with the EV
     * compensation as the supplement places it (2.7: candidates after the compensation; 3.1: compensate_tet after the cap, the
     * TET range open - our sensor ranges come at the factorisation).
     */
    public static ExposurePlan rule(boolean v31, float shortRel, float longRel, float fractionLong, float ev) {
        if (v31) return plan31(shortRel, longRel, fractionLong, true, false, ev, 0f, Float.MAX_VALUE);
        final float k = powf(2f, ev);
        return plan27(shortRel * k, longRel * k, fractionLong);
    }

    public static ExposurePlan rule(boolean v31, float shortRel, float longRel, float fractionLong) {
        return rule(v31, shortRel, longRel, fractionLong, 0f);
    }

    /**
     * P630h: the rule with the settings' numbers. «HDR» on: two exposures whenever L* is above S* (no lower or upper threshold, the
     * ratio still capped); off: never (the plan is the candidates, the picture one exposure at L*).
     */
    public static ExposurePlan rule(Params p, float shortRel, float longRel, float fractionLong, float ev) {
        final boolean on = p.hdrMode == HDR_ON, off = p.hdrMode == HDR_OFF;
        final float open = Math.nextUp(1f);
        if (p.v31) {
            return plan31(shortRel, longRel, fractionLong, off, ev, 0f, Float.MAX_VALUE, on ? open : p.v2HdrMin, p.v2RatioMax,
                    on ? Float.POSITIVE_INFINITY : p.v2Forgiveness);
        }
        final float k = powf(2f, ev);
        final ExposurePlan e = plan27(shortRel * k, longRel * k, fractionLong, on ? open : p.v1HdrMin,
                on ? Float.POSITIVE_INFINITY : p.v1HdrMax, p.v1RatioCap);
        if (off) {
            e.useHdr = false;
            e.shortPlan = e.shortCandidate;
            e.longPlan = e.longCandidate;
        }
        return e;
    }

    public static final class Plan {
        /** S*, L* and the plan's S, L over the module frame's TET S0. */
        public float shortCandidate, longCandidate, shortTet, longTet, fractionLong;
        public boolean hdrScene, hdr;
        /** The module frame's TET: 1 for V1, V2's input scale (its light within white). */
        public float actualTet = 1f;
        public float gainShort, gainLong;
        /** The frame's scale into the module (V2: its light within white, 1 for V1). */
        public float inputScale = 1f;
        public Meter meter;

        public float ratio() {
            return longTet / shortTet;
        }

        public String describe() {
            return String.format(Locale.ROOT, "plan S*=%.4g L*=%.4g F=%.3f scene=%s hdr=%s S=%.4g L=%.4g S0=%.4g gains=%.4g/%.4g",
                    shortCandidate, longCandidate, fractionLong, hdrScene, hdr, shortTet, longTet, actualTet, gainShort,
                    gainLong) + (meter != null ? String.format(Locale.ROOT, " light=%.3f%s", meter.high, meter.clipped ? " clipped" : "") : "");
        }
    }

    /**
     * {@code sw} x {@code sh} linear RGB samples of a RAW16 Bayer frame (row stride {@code stride} samples): one 2 x 2 quad at each
     * node of an even grid over the frame, (value - black) / (white - black) per CFA site ({@code cfa}: CameraCharacteristics
     * SENSOR_INFO_COLOR_FILTER_ARRANGEMENT 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR; {@code black} per site of the 2 x 2, or one mean), the
     * two greens averaged, times the frame's white balance {@code wb} (R, G, B); clipped sites stay at their white (1 x gain).
     */
    public static float[] bayerQuads(java.nio.ShortBuffer data, int stride, int w, int h, int cfa, float[] black, float white,
                                     float[] wb, int sw, int sh) {
        final int[][] site = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        final int[] map = site[Math.max(0, Math.min(3, cfa))];
        float mean = 0f;
        for (float b : black) mean += b / black.length;
        final float[] out = new float[sw * sh * 3];
        for (int j = 0; j < sh; j++) {
            final int y = Math.min(h - 2, (int) ((j + 0.5) * h / sh)) & ~1;
            for (int i = 0; i < sw; i++) {
                final int x = Math.min(w - 2, (int) ((i + 0.5) * w / sw)) & ~1;
                final float[] rgb = new float[3];
                for (int k = 0; k < 4; k++) {
                    final int v = data.get((y + (k >> 1)) * stride + x + (k & 1)) & 0xffff;
                    final float b = black.length == 4 ? black[k] : mean;
                    final float lin = Math.max(0f, Math.min(1f, (v - b) / Math.max(1f, white - b)));
                    rgb[map[k]] += map[k] == 1 ? lin * 0.5f : lin;
                }
                for (int c = 0; c < 3; c++) out[(j * sw + i) * 3 + c] = rgb[c] * (wb != null && wb.length == 3 ? wb[c] : 1f);
            }
        }
        return out;
    }

    /**
     * P643d: {@code sw} x {@code sh} linear RGB cells of a RAW16 Bayer frame (row stride {@code stride} samples), each the mean of
     * up to {@code k} x {@code k} 2 x 2 quads spread over the cell ({@link #bayerQuads}' per-site levels and white balance), times
     * the lens shading gain map at the cell's centre ({@code lsc}: lw x lh nodes of R, G even, G odd, B over the whole frame of
     * {@code fullW} x {@code fullH}, the window at {@code x0}, {@code y0} of it; null: none) - the frame the photo's colour
     * chain sees (our merge applies that map), at the size of a thumbnail.
     */
    public static float[] bayerBox(java.nio.ShortBuffer data, int stride, int w, int h, int cfa, float[] black, float white, float[] wb,
                                   int sw, int sh, int k, float[] lsc, int lw, int lh, int x0, int y0, int fullW, int fullH) {
        final int[][] site = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        final int[] map = site[Math.max(0, Math.min(3, cfa))];
        float mean = 0f;
        for (float b : black) mean += b / black.length;
        final float[] out = new float[sw * sh * 3];
        final int kk = Math.max(1, k);
        final float[] rgb = new float[3];
        for (int j = 0; j < sh; j++) {
            for (int i = 0; i < sw; i++) {
                rgb[0] = rgb[1] = rgb[2] = 0f;
                for (int qy = 0; qy < kk; qy++) {
                    final int y = Math.max(0, Math.min(h - 2, (int) ((j + (qy + 0.5) / kk) * h / sh))) & ~1;
                    for (int qx = 0; qx < kk; qx++) {
                        final int x = Math.max(0, Math.min(w - 2, (int) ((i + (qx + 0.5) / kk) * w / sw))) & ~1;
                        for (int q = 0; q < 4; q++) {
                            final int v = data.get((y + (q >> 1)) * stride + x + (q & 1)) & 0xffff;
                            final float b = black.length == 4 ? black[q] : mean;
                            final float lin = Math.max(0f, Math.min(1f, (v - b) / Math.max(1f, white - b)));
                            rgb[map[q]] += map[q] == 1 ? lin * 0.5f : lin;
                        }
                    }
                }
                float[] g = null;
                if (lsc != null && lw > 1 && lh > 1 && lsc.length >= lw * lh * 4 && fullW > 0 && fullH > 0) {
                    final float gx = Math.max(0f, Math.min(lw - 1f, (x0 + (i + 0.5f) * w / sw) / fullW * (lw - 1)));
                    final float gy = Math.max(0f, Math.min(lh - 1f, (y0 + (j + 0.5f) * h / sh) / fullH * (lh - 1)));
                    final int ax = Math.min(lw - 2, (int) gx), ay = Math.min(lh - 2, (int) gy);
                    final float fx = gx - ax, fy = gy - ay;
                    g = new float[4];
                    for (int c = 0; c < 4; c++) {
                        final float t = lsc[(ay * lw + ax) * 4 + c] * (1 - fx) + lsc[(ay * lw + ax + 1) * 4 + c] * fx;
                        final float bt = lsc[((ay + 1) * lw + ax) * 4 + c] * (1 - fx) + lsc[((ay + 1) * lw + ax + 1) * 4 + c] * fx;
                        g[c] = t * (1 - fy) + bt * fy;
                    }
                }
                final float n = kk * kk;
                for (int c = 0; c < 3; c++) {
                    final float shade = g == null ? 1f : c == 0 ? g[0] : c == 1 ? (g[1] + g[2]) * 0.5f : g[3];
                    out[(j * sw + i) * 3 + c] = rgb[c] / n * shade * (wb != null && wb.length == 3 ? wb[c] : 1f);
                }
            }
        }
        return out;
    }

    /**
     * P643d: {@link #shownPart} cut to the viewfinder stream's aspect ({@code aspect}: its width over its height in the sensor's
     * orientation; 0: as it is) round its centre, for a frame of {@code fullW} x {@code fullH} - the stream crops the sensor's
     * part to its own shape.
     */
    public static float[] shownPart(float[] part, int fullW, int fullH, float aspect) {
        if (!(aspect > 0f) || fullW <= 0 || fullH <= 0) return part;
        final float[] p = part.clone();
        final float own = p[2] * fullW / (p[3] * fullH);
        if (own > aspect * 1.01f) {
            final float w = p[2] * aspect / own;
            p[0] += (p[2] - w) * 0.5f;
            p[2] = w;
        } else if (own < aspect / 1.01f) {
            final float h = p[3] * own / aspect;
            p[1] += (p[3] - h) * 0.5f;
            p[3] = h;
        }
        return p;
    }

    /**
     * P643b: the part of the sensor's frame the viewfinder shows, as {x, y, width, height} in shares of the frame: the crop region
     * ({@code region}: left, top, width, height within an active array of {@code activeW} x {@code activeH}; null: all of it)
     * and the zoom ratio round its centre. Never less than a sixteenth of a side, never outside the frame.
     */
    public static float[] shownPart(int activeW, int activeH, int[] region, float zoom) {
        float x = 0f, y = 0f, w = 1f, h = 1f;
        if (region != null && region.length >= 4 && activeW > 0 && activeH > 0 && region[2] > 0 && region[3] > 0) {
            x = region[0] / (float) activeW;
            y = region[1] / (float) activeH;
            w = region[2] / (float) activeW;
            h = region[3] / (float) activeH;
        }
        if (zoom > 1f && Float.isFinite(zoom)) {
            x += w * (1f - 1f / zoom) * 0.5f;
            y += h * (1f - 1f / zoom) * 0.5f;
            w /= zoom;
            h /= zoom;
        }
        w = Math.max(1f / 16f, Math.min(1f, w));
        h = Math.max(1f / 16f, Math.min(1f, h));
        return new float[]{Math.max(0f, Math.min(1f - w, x)), Math.max(0f, Math.min(1f - h, y)), w, h};
    }

    /** V2's scale of the frame into the module: its brightest sample at most white (1 / max, at most 1). */
    public static float inputScale(float maxSample) {
        return maxSample > 1f && Float.isFinite(maxSample) ? 1f / maxSample : 1f;
    }

    /** The int16 sample of a linear value at a scale (4095 = white), FP32, rounded half up, saturated. */
    public static short sample(float v, float scale) {
        final float x = (float) Math.floor(v * scale * OldstyleNative.WHITE + 0.5f);
        return (short) Math.max(-32768f, Math.min(32767f, Float.isFinite(x) ? x : 0f));
    }

    /**
     * The tone's plan of a frame, metered on its samples ({@code rgb}, w x h; 1 = the frame's white at its own exposure - P643: the
     * photo mode's): S*, L* and F of {@link #meter} through the profile's rule, the gains by fusion_gains with the frame's TET (1,
     * or V2's input scale) as the actual short TET. {@code ceiling}: the largest linear value the frame can hold. The photo
     * (OldstyleTone, on the merge) and the viewfinder's preview (OldstyleLive, on the ring's frame) both take it.
     */
    public static Plan plan(float[] rgb, int w, int h, Params p, float ceiling) {
        final Meter m = meter(rgb, w, h, p, ceiling);
        final Plan plan = new Plan();
        plan.meter = m;
        float top = 0f;
        for (int i = 0; i + 2 < rgb.length; i += 3)
            for (int c = 0; c < 3; c++) if (Float.isFinite(rgb[i + c])) top = Math.max(top, rgb[i + c]);
        final float shortRel = m.shortRel, longRel = m.longRel, fraction = m.fractionLong;
        final ExposurePlan e = rule(p, shortRel, longRel, fraction, 0f);
        plan.shortCandidate = shortRel;
        plan.longCandidate = longRel;
        plan.fractionLong = fraction;
        plan.hdrScene = e.hdrScene;
        plan.hdr = e.useHdr;
        plan.shortTet = e.shortPlan;
        plan.longTet = e.longPlan;
        // the style's exposure: both TETs (a real exposure change; the ratio and the decision stay)
        final float k = powf(2f, p.exposureEv + p.baseEv);
        plan.shortTet *= k;
        plan.longTet *= k;
        // P643d: without HDR the frame is the one exposure, at the AE's TET (the photo mode's): the supplement - «Значения S* и L*
        // при SDR служат для диагностики решения» - so the gain is the exposure shifts alone (the style's, the mode's row), not
        // L* / S0 (P630e's reading, when the burst was exposed by the mode's own plan)
        final float sdr = k;
        // only V2's HDR operator needs the light within white (it never lifts a channel past it)
        plan.inputScale = p.v31 && plan.hdr ? inputScale(top) : 1f;
        plan.actualTet = plan.inputScale;
        if (plan.hdr) {
            final float[] g = fusionGains(plan.actualTet, plan.shortTet, plan.longTet, 1f, p.maxGain, p.fusionBase);
            plan.gainShort = g[0];
            plan.gainLong = g[1];
        } else {
            final float g = Math.min(p.maxGain, sdr / plan.actualTet);
            plan.gainShort = plan.gainLong = g;
        }
        return plan;
    }
}
