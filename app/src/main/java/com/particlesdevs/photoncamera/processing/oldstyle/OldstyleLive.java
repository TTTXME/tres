package com.particlesdevs.photoncamera.processing.oldstyle;

/**
 * P630g «Oldstyle»: the viewfinder's live tone state (owner, 2026-11-12, a vivo's screen recording: «Вот так работает Oldstyle,
 * исправь видоискатель» - the wall burnt to white in the mode, right in «Фото»; the saved photo right).
 * <p>
 * The plan's gains are over the RAW frame (OldstyleAe.Capture, metered on the ring's newest RAW), but the screen's tone multiplies
 * the frame on the screen - the ISP's, which its own gain and curve have already brightened by a factor nobody reports. So the
 * gains are not used as they are. What the photo fixes is where it puts the scene's mean: (gain x the RAW frame's mean), before
 * the curve - a number that does not depend on the viewfinder's exposure. The screen's gains put the screen frame's own mean
 * (linearised, measured on the GPU: LiveSceneMeter) on the same place:
 * <pre>  gain on the screen = gain of the plan / k,   k = the screen frame's gain over the RAW frame</pre>
 * for the short and the long exposure alike - the two keep the plan's ratio (the shadow / highlight balance), the scene's light
 * lands where the photo will put it, whatever the ISP did to the frame. k is not the two means' ratio: the screen frame clips what
 * its gain takes past white, so k is the gain that makes the RAW samples, clipped at the screen's white, give the measured mean
 * ({@link #screenGain}); and the screen's white, which stands for all that clipped light, is drawn at its mean ({@link #whiteBoost}). Nothing is drawn with the tone before both are known (a plan and a
 * measured level): never a half-made state.
 * <p>
 * Steady: the plan's mean targets are smoothed in the log domain ({@link #PLAN_TAU_NS}); the HDR / SDR decision changes only after
 * the other one has held for {@link #DECISION_NS} (and SDR is the HDR formula with both gains equal: no jump of the look, only of
 * the short gain, smoothed); the level follows the frame quickly ({@link #LEVEL_TAU_NS}) and at once when it jumps
 * ({@link #LEVEL_SNAP}: the series frames of a shot, AE off, are another brightness) - the output then stays where it was.
 * <p>
 * Thread-safe; times are System.nanoTime of the callers (the camera thread's plan, the GL thread's level and gains).
 */
public final class OldstyleLive {
    private static final OldstyleLive SHARED = new OldstyleLive();

    public static OldstyleLive shared() {
        return SHARED;
    }

    /** The plan's mean targets follow a new metering with this time constant. */
    public static final double PLAN_TAU_NS = 0.5e9;
    /** The screen frame's level follows its measurements with this time constant ... */
    public static final double LEVEL_TAU_NS = 0.15e9;
    /** ... and at once when it changes by more than this factor (another kind of frame: a shot's series). */
    public static final double LEVEL_SNAP = 1.25;
    /** HDR <-> SDR flips when the other decision has held this long. */
    public static final long DECISION_NS = 900_000_000L;
    /** A level older than this is not a level (the meter stopped). */
    public static final long LEVEL_STALE_NS = 1_500_000_000L;
    /** The screen's gains stay within these. */
    public static final float MIN_GAIN = 1f / 16f, MAX_GAIN = 16f;
    /**
     * Where the screen frame is clipped it holds no more light, but the RAW frame the plan was metered on does (the ISP's gain
     * took a wall past the screen's white that the RAW keeps), or the plan assumes it (a RAW-clipped window: OldstyleAe's
     * clippedFactor). The photo paints those places by their real light; the screen has one value for all of them - so its white
     * stands for their mean: the RAW samples the ISP's gain takes to white or past it, the mean of what the photo makes of them
     * ({@link #whiteBoost}). The frame's top ({@link #WHITE_FROM} of white up, linear) is lifted to it before the gains:
     * {@code lin x (1 + (boost - 1) x smoothstep(WHITE_FROM, 1, max(lin)))}. 1 where the screen frame is not clipped.
     */
    public static final float WHITE_FROM = 0.92f, WHITE_BOOST_MAX = 8f;
    /** A RAW sample the screen's gain takes to this share of the screen's white or past it is white on the screen. */
    public static final float WHITE_AT = 0.97f;

    /**
     * The lift of the screen frame's white for the RAW samples ({@code top}: their max(R, G, B), ascending, {@code ceiling} the RAW
     * frame's white) that the screen's gain {@code k} over the RAW takes to the screen's white or past it: drawn at the lowest
     * screen gain {@code gain}, the white comes out at the mean of what the photo makes of those samples - each at its own light
     * (a sample at the RAW's own ceiling: the light the plan assumes there), at most white, averaged as the eye sees them (display
     * values, a 1 / 2.2 power for the curve). 1 when no sample reaches the screen's white (or nothing is known); never below 1.
     */
    public static float whiteBoost(float[] top, float ceiling, double k, double gain) {
        if (top == null || top.length == 0 || !(k > 0) || !(gain > 0)) return 1f;
        final double from = WHITE_AT / k;
        double sum = 0;
        int n = 0;
        for (int i = top.length - 1; i >= 0 && top[i] >= from; i--) {
            sum += Math.pow(Math.min(1.0, gain * k * top[i] / OldstyleAe.clippedFactor(top[i] / ceiling)), 1 / 2.2);
            n++;
        }
        return n == 0 ? 1f : (float) Math.max(1.0, Math.min(WHITE_BOOST_MAX, Math.pow(sum / n, 2.2) / gain));
    }

    private int version;
    private boolean hdr, pendingHdr;
    private long pendingSince;
    /** ln of (gain x RAW mean): the short and long exposure's with HDR, and the one exposure's without. */
    private double targetShort = Double.NaN, targetLong = Double.NaN;
    private double logShort = Double.NaN, logLong = Double.NaN;
    private long planAt;
    private double logLevel = Double.NaN;
    private long levelAt;
    /** The plan's RAW frame: its mean luma, its samples' max(R, G, B) ascending, its ceiling; the white boost, smoothed (ln). */
    private double rawMean;
    private float[] rawTop, rawLuma;
    private float rawCeiling = 1f;
    private double logBoost = Double.NaN, logUnclipped = Double.NaN;
    private long boostAt;
    /** P630h, the rows of «Видоискатель»: the plan's time constant, the calibration's share (0 .. 1). */
    private double planTauNs = PLAN_TAU_NS;
    private double match = 1.0;

    /** Outside the mode (or the camera closed): nothing is drawn with the tone until a new plan and level. */
    public synchronized void off() {
        version = 0;
        targetShort = targetLong = logShort = logLong = logLevel = logBoost = logUnclipped = Double.NaN;
        rawTop = rawLuma = null;
    }

    /** The tone wants the screen frame's level measured (a plan is there). */
    public synchronized boolean wantsLevel() {
        return version != 0;
    }

    /**
     * A new plan of the RAW frame (over its own TET: {@code c} made with frame TET 1, its {@code meterMean} the RAW mean luma);
     * {@code version} 1 (2.7) or 2 (3.1). A plan without a mean or with no light is not taken.
     */
    public void plan(long nowNs, int version, OldstyleAe.Capture c, float maxGain) {
        plan(nowNs, version, c, maxGain, -1f, null);
    }

    /**
     * P630h: the plan with the settings ({@code p}: the gains' limit and fusion base, the mode's exposure shift, and the rows of
     * «Видоискатель» - the live tone off (nothing drawn: {@link #off}), its own brightness, its match to the photo, its smoothing).
     */
    public synchronized void plan(long nowNs, int version, OldstyleAe.Capture c, OldstyleAe.Params p, float photoMean, byte[] lut) {
        if (!p.vfTone) {
            off();
            return;
        }
        planTauNs = Math.max(1e6, Math.min(10e9, p.vfSmooth * 1e9));
        match = Math.max(0.0, Math.min(1.0, p.vfMatch));
        plan(nowNs, version, c, p.maxGain, photoMean, lut, p.fusionBase, p.baseEv, p.vfEv);
    }

    /**
     * As above, calibrated on the photo: {@code photoMean} the mean display luma (0..1) of the tone-port module's own picture of
     * the metered samples ({@link #photoMean}; <= 0: none), {@code lut} the version's viewfinder LUT ({@link #lut}). The screen's
     * shader is the module's curve of a neutral on a ramp - the module's picture of a real frame is a little other (its shadow
     * curve of 3.1 reads the frame, the dehaze takes the black point down): the gains are scaled so that the shader's picture of
     * the same samples ({@link #screenMean}) has the module's mean ({@link #CALIBRATION_MAX} either way at most).
     */
    public synchronized void plan(long nowNs, int version, OldstyleAe.Capture c, float maxGain, float photoMean, byte[] lut) {
        plan(nowNs, version, c, maxGain, photoMean, lut, OldstyleAe.FUSION_BASE, 0f, 0f);
    }

    private void plan(long nowNs, int version, OldstyleAe.Capture c, float maxGain, float photoMean, byte[] lut, float fusionBase,
                      float baseEv, float screenEv) {
        if (c == null || version <= 0 || !(c.meterMean > 0f) || !(c.shortTet > 0) || !(c.longTet > 0) || !(c.longCandidate > 0)) return;
        if (version != this.version) {
            // another LUT: its own plan state from the first plan on (the frame's level is the frame's: kept)
            targetShort = targetLong = logShort = logLong = Double.NaN;
            this.version = version;
            hdr = pendingHdr = c.hdr;
        } else if (c.hdr == hdr) {
            pendingHdr = hdr;
        } else if (pendingHdr != c.hdr) {
            pendingHdr = c.hdr;
            pendingSince = nowNs;
        } else if (nowNs - pendingSince >= DECISION_NS) {
            hdr = c.hdr;
        }
        // the mode's exposure shift as the tone's plan places it (both TETs, the SDR TET), then the limit
        final float shift = baseEv == 0f ? 1f : (float) Math.pow(2.0, baseEv);
        final float sdr = (float) Math.min(maxGain, c.longCandidate * Math.pow(2.0, c.ev) * shift);
        final float[] g = hdr ? OldstyleAe.fusionGains(1f, (float) c.shortTet * shift, (float) c.longTet * shift, 1f, maxGain, fusionBase)
                : new float[]{sdr, sdr};
        if (photoMean > 0f && lut != null && c.meterRgb != null) {
            final float screen = screenMean(c.meterRgb, lut, version, g[0], g[1]);
            if (screen > 0f) {
                // the curve is about a 1 / 2.2 power: a display ratio is that ratio to the 2.2 in gain
                final float k = (float) Math.pow(Math.max(1.0 / CALIBRATION_MAX, Math.min(CALIBRATION_MAX,
                        Math.pow(photoMean / screen, 2.2))), match);
                g[0] *= k;
                g[1] *= k;
            }
        }
        if (screenEv != 0f) {
            // the row «Яркость видоискателя»: the screen's own shift, after the match to the photo
            final float k = (float) Math.pow(2.0, screenEv);
            g[0] *= k;
            g[1] *= k;
        }
        advance(nowNs);
        rawMean = c.meterMean;
        rawTop = c.meterTop;
        rawLuma = c.meterLuma;
        rawCeiling = c.meterCeiling;
        targetShort = Math.log(Math.max(1e-6, g[0] * (double) c.meterMean));
        targetLong = Math.log(Math.max(1e-6, g[1] * (double) c.meterMean));
        if (Double.isNaN(logShort)) {
            logShort = targetShort;
            logLong = targetLong;
            planAt = nowNs;
        }
    }

    /**
     * The gain k of the screen frame over the RAW frame: the one for which the RAW's luma samples ({@code luma}, ascending), times
     * k and clipped at the screen's white, have the screen frame's measured linear mean {@code level}; without samples the two
     * means' ratio. Within 1 / 64 .. 64.
     */
    public static double screenGain(float[] luma, double rawMean, double level) {
        final double plain = Math.max(1.0 / 64, Math.min(64.0, level / Math.max(1e-6, rawMean)));
        if (luma == null || luma.length == 0 || !(level > 0)) return plain;
        if (level >= 0.995) return 64.0;
        double lo = Math.log(1.0 / 64), hi = Math.log(64.0);
        for (int it = 0; it < 24; it++) {
            final double mid = 0.5 * (lo + hi), k = Math.exp(mid);
            double sum = 0;
            for (float y : luma) sum += Math.min(1.0, k * y);
            if (sum / luma.length < level) lo = mid; else hi = mid;
        }
        return Math.exp(0.5 * (lo + hi));
    }

    /** The calibration on the module's picture moves the gains by this factor at most. */
    public static final double CALIBRATION_MAX = 2.0;

    private static final byte[][] LUTS = new byte[3][];
    /** P630h: the tone curve's shape the LUTs are made with (OldstyleAe.Params.curveMid / curveContrast), and how often it changed. */
    private static float curveMid, curveContrast;
    private static int curveGeneration;

    /** P630h: the settings' tone curve for the viewfinder's LUT (the shot's: «Средние тона», «Контраст кривой»); a change remakes it. */
    public static synchronized void curve(float mid, float contrast) {
        if (!Float.isFinite(mid)) mid = 0f;
        if (!Float.isFinite(contrast)) contrast = 0f;
        if (mid == curveMid && contrast == curveContrast) return;
        curveMid = mid;
        curveContrast = contrast;
        curveGeneration++;
        LUTS[1] = LUTS[2] = null;
    }

    /** What a renderer keeps with its LUT texture: another value, another LUT (the version, or the curve's shape changed). */
    public static synchronized int lutKey(int version) {
        return version + 4 * curveGeneration;
    }

    /** The version's viewfinder LUT (OldstyleNative.previewLut: 1024 x RGBA8; with the settings' curve), null without the library. */
    public static synchronized byte[] lut(int version) {
        if (version < 1 || version > 2) return null;
        if (LUTS[version] == null && OldstyleNative.load()) {
            final byte[] l = new byte[4096];
            try {
                if (curveMid == 0f && curveContrast == 0f) OldstyleNative.previewLut(version, l);
                else OldstyleNative.previewLutCurve(version, curveMid, curveContrast, l);
                LUTS[version] = l;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return LUTS[version];
    }

    private static float lut(byte[] lut, double x, int channel) {
        final double p = Math.max(0.0, Math.min(1.0, x)) * 1023.0;
        final int i = (int) Math.min(1022, Math.floor(p));
        final double f = p - i;
        return (float) (((lut[i * 4 + channel] & 0xFF) * (1 - f) + (lut[(i + 1) * 4 + channel] & 0xFF) * f) / 255.0);
    }

    /**
     * The mean display luma (0..1) of the screen shader's picture (utils/import_oldstyle_tone_live, without its low-pass) of
     * linear RGB {@code rgb} at the two gains: each channel through the LUT; with two exposures blended by the LUT's long weight
     * of the long exposure's light - version 1 in the output, version 2 in the encoded domain before the curve.
     */
    public static float screenMean(float[] rgb, byte[] lut, int version, float gainShort, float gainLong) {
        final int n = rgb.length / 3;
        if (n == 0 || lut == null) return 0f;
        final boolean two = gainShort < gainLong * 0.985f;
        double sum = 0;
        final double[] o = new double[3];
        for (int i = 0; i < n; i++) {
            final double r = Math.max(0f, rgb[i * 3]), g = Math.max(0f, rgb[i * 3 + 1]), b = Math.max(0f, rgb[i * 3 + 2]);
            final double[] in = {r, g, b};
            final double w = two ? lut(lut, Math.max(r, Math.max(g, b)) * gainLong, 3) : 1.0;
            for (int c = 0; c < 3; c++) {
                if (!two) o[c] = lut(lut, in[c] * gainLong, c);
                else if (version == 1) o[c] = (1 - w) * lut(lut, in[c] * gainShort, c) + w * lut(lut, in[c] * gainLong, c);
                else {
                    final double dark = Math.pow(Math.min(1.0, in[c] * gainShort), 1 / 2.2), bright = Math.pow(Math.min(1.0, in[c] * gainLong), 1 / 2.2);
                    o[c] = lut(lut, Math.pow((1 - w) * dark + w * bright, 2.2), c);
                }
            }
            sum += 0.2126 * o[0] + 0.7152 * o[1] + 0.0722 * o[2];
        }
        return (float) (sum / n);
    }

    /**
     * The mean display luma (0..1) of the tone-port module's own picture of a capture plan's metered samples, made as the shot
     * makes it: the samples at the burst's exposure (the plan's S or its SDR TET over the metered frame's), the tone's plan from
     * the capture over that exposure, the module with {@code p}'s stages; -1 without the library or the samples. A thumbnail
     * (64 x 48): the mean, not the detail.
     */
    public static float photoMean(OldstyleAe.Capture c, OldstyleAe.Params p) {
        if (c == null || c.meterRgb == null || c.meterW < 8 || c.meterH < 8 || !(c.frameTet > 0) || !OldstyleNative.load()) return -1f;
        try {
            final int w = c.meterW, h = c.meterH, n = w * h;
            final float tet = (float) c.frameTet;
            final float[] merged = new float[n * 3];
            for (int i = 0; i < merged.length; i++) merged[i] = Math.max(0f, Math.min(1f, c.meterRgb[i] * tet));
            final OldstyleAe.Plan plan = OldstyleAe.plan(merged, w, h, p, 1f, c, tet);
            final java.nio.ByteBuffer planar = OldstyleNative.planar(w, h);
            final java.nio.ShortBuffer s = planar.asShortBuffer();
            for (int i = 0; i < n; i++)
                for (int ch = 0; ch < 3; ch++) s.put(ch * n + i, OldstyleAe.sample(merged[i * 3 + ch], plan.inputScale));
            final java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(n * 3);
            OldstyleNative.render(p.v31 ? OldstyleNative.V2 : OldstyleNative.V1, planar, w, h, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1},
                    plan.gainShort, plan.gainLong, plan.hdr, false, p.saturation, p.post(), null, 0, 0, null, out);
            double sum = 0;
            for (int i = 0; i < n; i++)
                sum += 0.2126 * (out.get(i * 3) & 0xFF) + 0.7152 * (out.get(i * 3 + 1) & 0xFF) + 0.0722 * (out.get(i * 3 + 2) & 0xFF);
            return (float) (sum / n / 255.0);
        } catch (RuntimeException e) {
            return -1f;
        }
    }

    /** The plan's smoothed targets up to {@code nowNs}. */
    private void advance(long nowNs) {
        if (Double.isNaN(logShort) || Double.isNaN(targetShort)) {
            planAt = nowNs;
            return;
        }
        final double a = 1.0 - Math.exp(-Math.max(0L, nowNs - planAt) / planTauNs);
        logShort += a * (targetShort - logShort);
        logLong += a * (targetLong - logLong);
        planAt = nowNs;
    }

    /** The screen frame's mean, linear (its display values to the power 2.2, its series gain applied), measured now. */
    public synchronized void level(long nowNs, float linearMean) {
        if (!(linearMean > 0f) || !Float.isFinite(linearMean)) return;
        final double l = Math.log(Math.max(1e-5, linearMean));
        if (Double.isNaN(logLevel) || nowNs - levelAt > LEVEL_STALE_NS || Math.abs(l - logLevel) > Math.log(LEVEL_SNAP)) {
            logLevel = l;
            logBoost = logUnclipped = Double.NaN; // another kind of frame: its gain and its white anew
        } else {
            logLevel += (1.0 - Math.exp(-Math.max(0L, nowNs - levelAt) / LEVEL_TAU_NS)) * (l - logLevel);
        }
        levelAt = nowNs;
    }

    /**
     * The tone to draw with now: {version, gain short, gain long, HDR (1 / 0), white boost}, or null while there is no plan or no measured
     * level of the frame ({@code assumedLevel} > 0: that level instead of a measured one - a frame nobody measures).
     */
    public synchronized float[] gains(long nowNs, float assumedLevel) {
        if (version == 0 || Double.isNaN(targetShort)) return null;
        final double level;
        if (assumedLevel > 0f) level = Math.log(assumedLevel);
        else if (Double.isNaN(logLevel) || nowNs - levelAt > LEVEL_STALE_NS) return null;
        else level = logLevel;
        advance(nowNs);
        // the screen frame's gain over the RAW frame, and the level the frame would have without its clipping
        final double mean = Math.max(1e-6, rawMean);
        final double k = assumedLevel > 0f ? Math.exp(level) / mean : screenGain(rawLuma, mean, Math.exp(level));
        // (smoothed as the plan: each metering's samples move the solved gain a little)
        final double a = 1.0 - Math.exp(-Math.max(0L, nowNs - boostAt) / planTauNs);
        final double unclipped = Math.log(k * mean);
        if (Double.isNaN(logUnclipped) || assumedLevel > 0f) logUnclipped = unclipped;
        else logUnclipped += a * (unclipped - logUnclipped);
        final float s = clamp((float) Math.exp(logShort - logUnclipped)), l = clamp((float) Math.exp(logLong - logUnclipped));
        // the frame's white: the RAW light that gain takes there, smoothed too
        final double boost = Math.log(whiteBoost(rawTop, rawCeiling, Math.exp(logUnclipped) / mean, Math.min(s, l)));
        if (Double.isNaN(logBoost)) logBoost = boost;
        else logBoost += a * (boost - logBoost);
        boostAt = nowNs;
        // one exposure: both gains equal - the fusion's formula gives the plain curve, the low-pass taps are skipped
        final boolean two = s < l * 0.985f;
        return new float[]{version, two ? s : l, l, two ? 1f : 0f, (float) Math.exp(logBoost)};
    }

    /** {@link #gains(long, float)} with the measured level. */
    public float[] gains(long nowNs) {
        return gains(nowNs, 0f);
    }

    /** The HDR / SDR decision in force (after its hold). */
    public synchronized boolean hdr() {
        return hdr;
    }

    private static float clamp(float g) {
        return Math.max(MIN_GAIN, Math.min(MAX_GAIN, g));
    }
}
