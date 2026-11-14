package com.scamera.app.processing.yuv;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;

import com.scamera.app.capture.YuvMergeRoute;
import com.scamera.app.processing.ImageFrame;
import com.scamera.app.processing.render.ColorCorrectionTransform;
import com.scamera.app.processing.render.NoiseModeler;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Allocator;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

/**
 * P646 «Склейка YUV»: a YUV burst (packed frames, ScamProcessor's sorted list: the reference N frame first) to the linear RGB the
 * post pipeline takes from the RAW merges ({@link Parameters#scamRgb}): 1 = the white of the normal exposure, the bracket's
 * highlights above it. The frames already carry the camera's white balance and colour matrix, so the shot's colour route is the
 * identity (white point 1, no lens shading map): our tone, colour look, denoise and sharpening act on it as on a RAW shot after
 * demosaic. The noise model of the post pipeline is the merge's own (the burst's measured noise in linear light) and its
 * per-pixel frame count is the denoise's effective-frame map.
 */
public final class YuvBurst {
    private YuvBurst() {}

    static final String TAG = "SCAM_HDR";

    /** The merge's per-pixel frame count of the last YUV shot (ScamHybridBurst.lastEffectiveFrames' layout), or null. */
    public static volatile ByteBuffer lastEffectiveFrames;

    public static ByteBuffer process(List<ImageFrame> images, Parameters p, CameraCharacteristics c, CaptureResult reference) {
        if (!YuvMergeNative.load()) throw new IllegalStateException(Lang.t("Склейка YUV: нет библиотеки scameraYuv", "YUV merge: no scameraYuv library"));
        final long t0 = System.nanoTime();
        final ImageFrame first = images.get(0);
        final int w = first.width, h = first.height, bits = first.yuvBits <= 8 ? 8 : first.yuvBits;
        final boolean hlg = first.yuvHlg;
        final int n = images.size();
        final ByteBuffer[] frames = new ByteBuffer[n];
        final float[] ratios = new float[n];
        final int[] roles = new int[n];
        final double base = (double) first.measuredExposure * first.measuredIso;
        int shorts = 0, longs = 0, merged = n;
        for (int i = 0; i < n; i++) {
            final ImageFrame f = images.get(i);
            if (f.yuvBits != first.yuvBits || f.width != w || f.height != h || f.buffer == null)
                throw new IllegalStateException("YUV merge: frame " + i + " differs from the reference (bits " + f.yuvBits + ", " + f.width + "x" + f.height + ")");
            frames[i] = f.buffer;
            ratios[i] = base > 0 ? (float) ((double) f.measuredExposure * f.measuredIso / base) : 1f;
            final ImageFrame.CaptureRole role = f.getCaptureRole();
            roles[i] = role == ImageFrame.CaptureRole.LONG ? 2
                    : role == ImageFrame.CaptureRole.SHORT || role == ImageFrame.CaptureRole.EXTRA_SHORT ? 1 : 0;
            shorts += roles[i] == 1 ? 1 : 0;
            longs += roles[i] == 2 ? 1 : 0;
        }
        // the frames' curve: HLG10 (P010), our points when the reference frame's result reports them, else the camera's own (an
        // sRGB estimate; brackets off: their light cannot be compared)
        final Integer tonemap = reference == null ? null : reference.get(CaptureResult.TONEMAP_MODE);
        final boolean points = !hlg && tonemap != null && tonemap == CaptureResult.TONEMAP_MODE_CONTRAST_CURVE;
        final int transfer = hlg ? 2 : points ? 1 : 0;
        final boolean bracket = PreferenceKeys.hybridSwitch("yuv_bracket", true) && (hlg || points);
        if ((shorts + longs) > 0 && !hlg && !points)
            Log.w(TAG, "YUV MERGE: the frames report tonemap mode " + tonemap + ", not our curve: the camera's curve (sRGB estimate), "
                    + (shorts + longs) + " bracket frames left out");
        float[] curve = null;
        if (points) {
            final float[] pairs = YuvMergeRoute.curvePairs(c);
            curve = mergeCurve(pairs);
            // P691: what the frame's result says the camera applied (the merge inverts the points asked for)
            float[] reported = null;
            try {
                final android.hardware.camera2.params.TonemapCurve tc = reference.get(CaptureResult.TONEMAP_CURVE);
                if (tc != null) {
                    reported = new float[2 * tc.getPointCount(android.hardware.camera2.params.TonemapCurve.CHANNEL_GREEN)];
                    tc.copyColorCurve(android.hardware.camera2.params.TonemapCurve.CHANNEL_GREEN, reported, 0);
                }
            } catch (RuntimeException e) {
                reported = null;
            }
            Log.i(TAG, "YUV MERGE capture curve: " + curveReport(pairs, reported));
        }
        final float profile = "27".equals(PreferenceKeys.hybridString("yuv_profile", "31")) ? 0f : 1f;
        final int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        final float[] settings = {profile, PreferenceKeys.hybridValue("yuv_search", 48f), PreferenceKeys.hybridValue("yuv_local", 6f),
                PreferenceKeys.hybridValue("yuv_threshold", 1f), PreferenceKeys.hybridSwitch("yuv_chroma_check", true) ? 1 : 0, 1f,
                1f, PreferenceKeys.hybridSwitch("yuv_own_noise", true) ? 1 : 0, PreferenceKeys.hybridSwitch("yuv_sharpest", true) ? 1 : 0,
                PreferenceKeys.hybridSwitch("yuv_guided_chroma", true) ? 1 : 0, bracket ? 1 : 0, PreferenceKeys.hybridValue("yuv_deghost", 3f),
                threads, transfer, YuvFrames.decodeMode(hlg, first.yuvRange)};
        // the fallback noise: the camera's NOISE_PROFILE of the reference (its mean channel, normalised)
        final float[] noise = {Float.isFinite(first.noiseSlope) ? first.noiseSlope : 0f, Float.isFinite(first.noiseOffset) ? first.noiseOffset : 0f};
        final long rgbBytes = (long) w * h * 12;
        if (rgbBytes > Integer.MAX_VALUE) throw new IllegalStateException("YUV merge: frame too large " + w + "x" + h);
        final ByteBuffer rgb = Allocator.allocate((int) rgbBytes);
        if (rgb == null) throw new IllegalStateException("YUV merge: no memory for the RGB of " + w + "x" + h);
        rgb.order(ByteOrder.nativeOrder());
        ByteBuffer counts = null;
        try {
            counts = ByteBuffer.allocateDirect(w * h);
        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "YUV MERGE: no frame-count map (" + oom + ")");
        }
        final float[] stats = new float[10];
        String report;
        try {
            report = YuvMergeNative.merge(frames, w, h, bits, ratios, roles, settings, curve, noise, rgb, counts, stats);
        } catch (RuntimeException e) {
            // the shot is never lost: the reference frame alone (decoded to linear light, no merge, no bracket)
            Log.e(TAG, "YUV MERGE failed (" + e.getMessage() + "): the reference frame alone");
            try {
                settings[10] = 0;
                report = "single frame after a failed merge: " + YuvMergeNative.merge(new ByteBuffer[]{frames[0]}, w, h, bits,
                        new float[]{1f}, new int[]{0}, settings, curve, noise, rgb, counts, stats);
                shorts = longs = 0;
                merged = 1;
            } catch (RuntimeException single) {
                Allocator.free(rgb);
                throw single;
            }
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        Log.i(TAG, String.format(Locale.ROOT, "YUV MERGE %dx%d %d-bit %s, %d frames (ES %d, L %d) in %d ms: %s", w, h, bits,
                hlg ? "HLG10" : points ? "our curve" : "camera curve (estimate)", merged, shorts, longs, ms, report));
        identityColour(p);
        clearRawMergeState(w, h);
        // the post pipeline's noise: the merged frame's linear model of one frame, the merge's frame count per pixel
        final double s = Math.max(0, stats[2]), o = Math.max(0, stats[3]);
        @SuppressWarnings("unchecked") final android.util.Pair<Double, Double>[] model = new android.util.Pair[4];
        for (int i = 0; i < 4; i++) model[i] = new android.util.Pair<>(s, o);
        final int analog = p.noiseModel != null ? p.noiseModel.analogIso : first.measuredIso;
        if (s > 0 || o > 0) p.noiseModel = new NoiseModeler(model, analog, first.measuredIso, p.cfaPattern);
        p.effectiveStackSamples = Math.max(1, stats[1]);
        p.multiFrameCount = merged - shorts - longs;
        final int median = Math.max(1, Math.round(stats[9]));
        if (counts != null) {
            // code 64 = the median count (ScamHybridBurst.lastEffectiveFrames' layout)
            counts.rewind();
            for (int i = 0; i < w * h; i++) counts.put(i, (byte) Math.min(255, Math.round(64f * (counts.get(i) & 0xff) / median)));
            counts.rewind();
        }
        lastEffectiveFrames = counts;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastEffectiveFrames = counts;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastEffectiveMedian = median;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastNormals = Math.max(1, merged - shorts - longs);
        Log.i(TAG, String.format(Locale.ROOT, "YUV MERGE result: base frame %d, mean frames %.2f (median %d), noise %s (%d bins), "
                        + "linear noise S %.3g O %.3g, bracket %s (ES pixels %.3f, L pixels %.3f)", (int) stats[0], stats[1], median,
                stats[7] > 0 ? "measured" : "camera profile", (int) stats[8], s, o, stats[4] > 0 ? "used" : "not used", stats[5], stats[6]));
        rgb.rewind();
        return rgb;
    }

    /** The merge's curve (YuvMergeNative.merge): the points' linear inputs, then their encoded outputs, of interleaved pairs. */
    static float[] mergeCurve(float[] pairs) {
        final int n = pairs.length / 2;
        final float[] curve = new float[2 * n];
        for (int i = 0; i < n; i++) {
            curve[i] = pairs[2 * i];
            curve[n + i] = pairs[2 * i + 1];
        }
        return curve;
    }

    /**
     * P691: the curve the frame's result reports ({@code reported}: green (in, out) pairs, null when absent) against the one asked
     * for: the merge decodes through the points asked for, so a difference is said in the log.
     */
    static String curveReport(float[] asked, @androidx.annotation.Nullable float[] reported) {
        final int n = asked.length / 2;
        final String mine = n + " points at evenly spaced inputs";
        if (reported == null || reported.length < 4) return mine + " (the result reports no curve)";
        if (reported.length != asked.length)
            return mine + "; the result reports " + reported.length / 2 + " points (decoded through the points asked for)";
        float worst = 0f;
        for (int i = 0; i < asked.length; i++) worst = Math.max(worst, Math.abs(asked[i] - reported[i]));
        return worst < 1e-3f ? mine + ", the result reports them"
                : String.format(Locale.ROOT, "%s; the result's points differ by up to %.4f (decoded through the points asked for)", mine, worst);
    }

    /** Nothing of an earlier RAW merge applies to this shot (as ScamHybridBurst's CPU fallback clears it): no Bento, no flags. */
    static void clearRawMergeState(int w, int h) {
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastOutputSize = new android.graphics.Point(w, h);
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastFinalSize = null;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastBentoApplied = false;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastRgbHalf = false;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastDcgNoise = null;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastMergeFrames = Double.NaN;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastHlMask = null;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.dropMergedDng(); // P681: freed, not dropped
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastDngMosaic = 1;
        com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst.lastMergeTier = "yuv";
        com.scamera.app.processing.opengl.postpipeline.ScamRgb.dropClipFlags(
                com.scamera.app.processing.opengl.postpipeline.ScamRgb.lastClipFlags);
    }

    /** The colour route of a YUV shot: the camera's own balance and matrix are in the frames already. */
    static void identityColour(Parameters p) {
        p.asShotNeutral = new float[]{1f, 1f, 1f};
        for (int i = 0; i < 9; i++) p.cameraToProPhoto[i] = i % 4 == 0 ? 1f : 0f;
        p.proPhotoToSrgb = new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        if (p.colorTransform == null) p.colorTransform = new ColorCorrectionTransform();
        p.colorTransform.matrix = p.proPhotoToSrgb;
        p.colourSource = "YUV_ISP";
        p.colourSourceWhy = "the camera's YUV: its white balance and colour matrix";
        p.shadingMapUsed = false;
        p.shadingGrid = new android.graphics.Point(1, 1);
        p.shadingMap = new float[]{1f, 1f, 1f, 1f};
        Log.i(TAG, "colour: YUV_ISP (the frames carry the camera's white balance and colour matrix: identity, no lens shading map)");
    }
}
