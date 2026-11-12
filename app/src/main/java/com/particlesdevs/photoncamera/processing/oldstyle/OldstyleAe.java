package com.particlesdevs.photoncamera.processing.oldstyle;

import java.util.Arrays;
import java.util.Locale;

/**
 * P630e «Oldstyle»: the exposures of the mode by the owner's supplement to tone-port (third_party/tone_supplement,
 * hdr-ae-color-27-31.md): its rules ported to Java in FP32 ({@link #plan27}, {@link #plan31}, {@link #capCaptureRatio},
 * {@link #compensateTet}, {@link #fusionGains}; the host check and OldstyleAeTest hold them to the document's 45 table rows and
 * to the C++ block), fed by our metering ({@link #meter}). The supplement leaves the per-frame AE estimator external; ours:
 * <ul>
 *   <li>S* (the short candidate) keeps the frame's light: its {@link #HIGH_PERCENTILE} of max(R, G, B) at {@link #SHORT_WHITE} of
 *       white (before a shot {@link #CLIPPED_STEP} lower when that light is clipped in the viewfinder's frame: it holds no more than
 *       "at least white"; on a merge the light as it is);</li>
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
    /** The light percentile at or above this share of the frame's ceiling is clipped there: S* one step lower. */
    public static final float CLIP_LEVEL = 0.98f, CLIPPED_STEP = 0.5f;
    /**
     * P630g: the step comes in softly from this share of the ceiling up to {@link #CLIP_LEVEL} ({@link #clippedFactor}): a light
     * that hovers at the clip level moved S* (and the whole live viewfinder with it) by a stop from one metering to the next.
     */
    public static final float CLIP_SOFT = 0.90f;

    /**
     * P630g: how much lower S* goes for a light at {@code share} of the frame's ceiling before a shot: 1 up to {@link #CLIP_SOFT},
     * {@link #CLIPPED_STEP} from {@link #CLIP_LEVEL} on, log-linear between (continuous: no jump at a threshold).
     */
    public static float clippedFactor(float share) {
        if (!(share > CLIP_SOFT)) return 1f;
        if (share >= CLIP_LEVEL) return CLIPPED_STEP;
        final double t = (share - CLIP_SOFT) / (CLIP_LEVEL - CLIP_SOFT);
        return (float) Math.exp(t * Math.log(CLIPPED_STEP));
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
        return meter(rgb, w, h, v31, ceiling, false);
    }

    /**
     * {@code capture}: the viewfinder's frame before a shot - a clipped light there takes S* {@link #CLIPPED_STEP} lower (the burst is
     * still to be exposed); on a merge the light is what it is (a lower S* would only darken the picture: nothing more to recover).
     */
    public static Meter meter(float[] rgb, int w, int h, boolean v31, float ceiling, boolean capture) {
        final Params p = new Params();
        p.v31 = v31;
        return meter(rgb, w, h, p, ceiling, capture);
    }

    /**
     * P630h: the meter with the settings' numbers ({@code p}: the light's percentile and its place under white, the shadows' shift
     * of L*, F's centre weighting; the profile {@code p.v31}).
     */
    public static Meter meter(float[] rgb, int w, int h, Params p, float ceiling, boolean capture) {
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
        // before a shot a light at the ceiling is taken for a brighter one (the frame holds no more than "at least white"): S* keeps
        // that light, and L* / S* is fitted for it (P630g: L* followed S* down by the whole step with the ratio of the clipped light)
        final float light = m.high / (capture ? clippedFactor(m.high / ceiling) : 1f);
        m.shortRel = p.shortWhite / light;
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
        /** The burst: frames (0: the photo mode's count), its longest capture in seconds, the shutter it may grow to in ms. */
        public int burstFrames = 0;
        public float burstSeconds = 0.7f, shutterFloorMs = 1000f / 60f;
        /** The viewfinder's live tone: on, its own brightness in EV, its match to the photo (0 .. 1), its smoothing in seconds. */
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

    /** The capture's plan (CaptureController, the viewfinder's frame metered before the shot), carried to the processing. */
    public static final class Capture {
        /** S*, L*, S, L in the sensor's TET unit (ns x ISO). */
        public final double shortCandidate, longCandidate, shortTet, longTet;
        public final float fractionLong;
        public final boolean hdrScene, hdr;
        /** The TET the frames were planned at (S with HDR, else L*). */
        public final double frameTet;
        /** The viewfinder's EV compensation, in the rule (plan27: in the candidates; plan31: its ev). */
        public final float ev;
        /** P630f: the metered RAW frame's mean luma (0: not known), for the screen's tone ({@link OldstyleAe#ispGain}). */
        public float meterMean;
        /** P630g: the metered frame's max(R, G, B) samples, ascending (null: not known), and its ceiling. */
        public float[] meterTop;
        public float meterCeiling = 1f;
        /** P630g: the metered frame's luma samples, ascending (null: not known). */
        public float[] meterLuma;
        /** P630g: the metered samples themselves (interleaved linear RGB, meterW x meterH; null: not kept). */
        public float[] meterRgb;
        public int meterW, meterH;

        public Capture(double shortCandidate, double longCandidate, float fractionLong, boolean hdrScene, boolean hdr,
                       double shortTet, double longTet, float ev) {
            this.ev = ev;
            this.shortCandidate = shortCandidate;
            this.longCandidate = longCandidate;
            this.fractionLong = fractionLong;
            this.hdrScene = hdrScene;
            this.hdr = hdr;
            this.shortTet = shortTet;
            this.longTet = longTet;
            frameTet = hdr ? shortTet : longCandidate * Math.pow(2, ev);
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "S*=%.4g L*=%.4g F=%.3f scene=%s hdr=%s S=%.4g L=%.4g frame=%.4g%s", shortCandidate,
                    longCandidate, fractionLong, hdrScene, hdr, shortTet, longTet, frameTet,
                    ev != 0f ? String.format(Locale.ROOT, " EV%+.2f", ev) : "");
        }
    }

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

    /** The capture's plan of the viewfinder's metered frame at TET {@code frameTet} (ns x ISO), EV compensation {@code ev}. */
    public static Capture capture(Meter m, boolean v31, double frameTet, float ev) {
        final Params p = new Params();
        p.v31 = v31;
        return capture(m, p, frameTet, ev);
    }

    /** P630h: the capture's plan through the settings' rule ({@link #rule(Params, float, float, float, float)}). */
    public static Capture capture(Meter m, Params params, double frameTet, float ev) {
        final ExposurePlan p = rule(params, m.shortRel, m.longRel, m.fractionLong, ev);
        final Capture c = new Capture(m.shortRel * frameTet, m.longRel * frameTet, m.fractionLong, p.hdrScene, p.useHdr,
                p.shortPlan * frameTet, p.longPlan * frameTet, ev);
        c.meterMean = m.mean;
        c.meterTop = m.sortedMax;
        c.meterLuma = m.sortedLuma;
        return c;
    }

    public static Capture capture(Meter m, boolean v31, double frameTet) {
        return capture(m, v31, frameTet, 0f);
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

    public static final class Plan {
        /** S*, L* and the plan's S, L over the module frame's TET S0. */
        public float shortCandidate, longCandidate, shortTet, longTet, fractionLong;
        public boolean hdrScene, hdr;
        /** The module frame's TET: 1 for V1, V2's input scale (its light within white). */
        public float actualTet = 1f;
        public float gainShort, gainLong;
        /** The frame's scale into the module (V2: its light within white, 1 for V1). */
        public float inputScale = 1f;
        /** "capture" (the shot's plan) or "merge" (metered on the merge itself). */
        public String source = "merge";
        public Meter meter;

        public float ratio() {
            return longTet / shortTet;
        }

        public String describe() {
            return String.format(Locale.ROOT, "plan(%s) S*=%.4g L*=%.4g F=%.3f scene=%s hdr=%s S=%.4g L=%.4g S0=%.4g gains=%.4g/%.4g",
                    source, shortCandidate, longCandidate, fractionLong, hdrScene, hdr, shortTet, longTet, actualTet, gainShort,
                    gainLong) + (meter != null ? String.format(Locale.ROOT, " light=%.3f%s", meter.high, meter.clipped ? " clipped" : "") : "");
        }
    }

    /**
     * P630e: the viewfinder's two gains of a capture plan made over the screen frame's own TET (1): the shot's fusion_gains with
     * S0 = 1 (HDR), else the SDR TET (L* with the EV) as one gain; at most {@code maxGain}.
     */
    public static float[] viewfinderGains(Capture c, float maxGain) {
        if (c.hdr) return fusionGains(1f, (float) c.shortTet, (float) c.longTet, 1f, maxGain);
        final float g = (float) Math.min(maxGain, c.frameTet);
        return new float[]{g, g};
    }

    /** The mean of an ISP viewfinder frame linearised by 2.2 (its AE and tone put a scene about there). */
    public static final float ISP_LINEAR_MEAN = 0.18f;

    /**
     * P630f: how much brighter than the RAW the ISP's viewfinder frame already is, linearised: {@link #ISP_LINEAR_MEAN} over the
     * RAW frame's mean luma (the meter's), 1..16; 1 when the mean is not known. The plan's gains are over the RAW frame; the
     * screen's tone is applied to the ISP frame, which its own gain and curve have brightened (owner's recording, a room: RAW
     * mean 0.051, gains 2.75 / 5.44 on top of the ISP's ~3.5 - the keys of a keyboard burnt out).
     */
    public static float ispGain(float rawMean) {
        return rawMean > 0f && Float.isFinite(rawMean) ? Math.max(1f, Math.min(16f, ISP_LINEAR_MEAN / rawMean)) : 1f;
    }

    /** P630f: {@link #viewfinderGains} for the ISP frame on the screen: both gains over {@link #ispGain} of the metered RAW mean. */
    public static float[] viewfinderGainsOnIsp(Capture c, float maxGain) {
        final float[] g = viewfinderGains(c, maxGain);
        final float k = ispGain(c.meterMean);
        return new float[]{g[0] / k, g[1] / k};
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
     * The tone's plan of the merge: from the shot's {@code capture} (its S and L over the merged frames' actual TET
     * {@code actualFrameTet}, ns x ISO) when there is one, else metered on the merge's samples ({@code rgb}, w x h). {@code ceiling}:
     * the largest linear value the merge can hold.
     */
    public static Plan plan(float[] rgb, int w, int h, Params p, float ceiling, Capture capture, double actualFrameTet) {
        final Meter m = meter(rgb, w, h, p, ceiling, false);
        final Plan plan = new Plan();
        plan.meter = m;
        float top = 0f;
        for (int i = 0; i + 2 < rgb.length; i += 3)
            for (int c = 0; c < 3; c++) if (Float.isFinite(rgb[i + c])) top = Math.max(top, rgb[i + c]);
        final float shortRel, longRel, fraction;
        if (capture != null && actualFrameTet > 0) {
            // the shot's candidates over the frames' actual TET, through this profile's rule (the same S and L as the capture's
            // when the version is the press's: the rules are scale-free; a V2 grid routed to V1 by memory takes V1's)
            plan.source = "capture";
            shortRel = (float) (capture.shortCandidate / actualFrameTet);
            longRel = (float) (capture.longCandidate / actualFrameTet);
            fraction = capture.fractionLong;
        } else {
            shortRel = m.shortRel;
            longRel = m.longRel;
            fraction = m.fractionLong;
        }
        final ExposurePlan e = rule(p, shortRel, longRel, fraction, capture != null && actualFrameTet > 0 ? capture.ev : 0f);
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
        final float sdr = plan.longCandidate * k * (capture != null && actualFrameTet > 0 ? powf(2f, capture.ev) : 1f);
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
