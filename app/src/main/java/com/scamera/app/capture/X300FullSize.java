package com.scamera.app.capture;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Size;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * P684: the X300 Ultra stock camera's full-size recipe (its 200 MP stills), as our still-only 200 MP route takes it
 * (StillOnly200's stock-key variants). What the HAL needs to read the sensor at full size for a still:
 * <ul>
 * <li>the vivo SDK gate (VivoCameraManager key 40 = 1) while the streams are configured - our lease (VivoSdkRawSession);</li>
 * <li>the streams: the viewfinder RAW, a small YUV companion of the same shape (its frames dropped; the decision frame targets it)
 * and the full-size RAW16, default use cases;</li>
 * <li>{@link #SESSION} on the session parameters and on every request (the X300 Ultra lists them as session keys), {@link #REQUEST}
 * on every request;</li>
 * <li>before the stills, a decision frame (the viewfinder request without the viewfinder RAW, the companion only, at the
 * viewfinder's shutter and the lowest ISO, the widest AE range) with com.vivo.current_ui_module 74 repeated until its result is
 * in (at most {@link #DECISION_MS}), then the repeating request stops;</li>
 * <li>each still a fresh TEMPLATE_STILL_CAPTURE request (never a copy of the viewfinder's), the viewfinder's OIS and AF copied,
 * {@link #applyStill}: com.vivo.current_ui_module 74, isCapture 0, isPAPNeeded 1, onlyDng 1 (variant), the remosaic pair
 * sensorMode 0 / isUpscale 1 (only StillOnly200.Variant.HAL: the HAL's Bayer) and the per-still ids requestId = frameFlag = a
 * millisecond stamp, globalCaptureId = stamp + 5 (a still without them crashes the provider's request handling).</li>
 * </ul>
 * The HAL's selector (/vendor/etc/camera/sensormode-selector/PD2547.json) turns ultra_highresolution 1 at 16320 wide into its
 * full-size use cases (FULLSATREMOSAIC / SATREMOSAIC: QuadCFA, «NORemosaic» - the sensor's own mosaic); the remosaic to Bayer
 * the stock camera gets is the HAL's own on the still. Every still is checked ({@link #expectedSeamless}, {@link #binnedCorner}).
 * Keys a firmware does not know are skipped and logged (never thrown into the camera).
 */
final class X300FullSize {
    private X300FullSize() { }

    static final long DECISION_MS = 2000;
    /** com.vivo.current_ui_module of the stock camera's full-size stills (the selector's landscape photo module). */
    static final int UI_MODULE = 74;
    static final String SEAMLESS = "vcf.parameter.seamlessMode";
    /** Result tags logged on the first still of a series. */
    static final String[] PROBES = {SEAMLESS, "vivo.parameter.highResolutionDngType", "vivo.control.seamless.remosaic.enable",
            "vivo.control.sensorMode"};

    /** Session parameters and every request: {tag, value} int32. */
    static final Object[][] SESSION = {
            {"vivo.control.ultra_highresolution", 1},
            {"vivo.control.is_ProRaw_on", 2},
            {"vivo.control.echo.mode", 2},
            {"vivo.control.scenery.subfunction", 5},
            {"vivo.control.session.uiSwitch_state", 2},
    };

    /** The session's keys (for the log: the ones the firmware knows). */
    static List<String> applySession(CaptureRequest.Builder b) {
        final List<String> failed = new ArrayList<>();
        for (Object[] k : SESSION) ints(b, (String) k[0], (Integer) k[1], failed);
        return failed;
    }

    /** Every request of the session (viewfinder, decision, stills): the session's keys and the request-only ones. */
    static List<String> applyRequest(CaptureRequest.Builder b) {
        final List<String> failed = applySession(b);
        ints(b, "vivo.control.is_pro_mode", 1, failed);
        bytes(b, "vivo.control.camera_scenery_pro", (byte) 1, failed);
        return failed;
    }

    /** The decision frame's own key. */
    static List<String> applyDecision(CaptureRequest.Builder b) {
        final List<String> failed = new ArrayList<>();
        ints(b, "com.vivo.current_ui_module", UI_MODULE, failed);
        return failed;
    }

    /** The decision frame's key taken off the viewfinder builder again. */
    static void clearDecision(CaptureRequest.Builder b) {
        try {
            b.set(new CaptureRequest.Key<>("com.vivo.current_ui_module", int[].class), null);
        } catch (RuntimeException ignored) {
        }
    }

    /** A still's keys of {@code variant}: {tag, value} (int32, byte or int64 by the value's class), its ids from {@code stamp} (ms). */
    static List<Object[]> stillKeys(StillOnly200.Variant variant, long stamp) {
        final List<Object[]> keys = new ArrayList<>();
        keys.add(new Object[]{"com.vivo.current_ui_module", UI_MODULE});
        if (variant.onlyDng) keys.add(new Object[]{"vivo.control.onlyDng", 1});
        keys.add(new Object[]{"vivo.control.isCapture", 0});
        if (variant.halRemosaic) {
            keys.add(new Object[]{"vivo.control.sensorMode", 0});
            keys.add(new Object[]{"vivo.control.isUpscale", 1});
        }
        keys.add(new Object[]{"vivo.control.isPAPNeeded", (byte) 1});
        keys.add(new Object[]{"vivo.control.requestId", stamp});
        keys.add(new Object[]{"vivo.control.frameFlag", stamp});
        keys.add(new Object[]{"vivo.control.globalCaptureId", stamp + 5});
        return keys;
    }

    /** A still's keys of {@code variant} ({@link #stillKeys}); the tags the firmware does not know. */
    static List<String> applyStill(CaptureRequest.Builder b, StillOnly200.Variant variant, long stamp) {
        final List<String> failed = new ArrayList<>();
        for (Object[] k : stillKeys(variant, stamp)) {
            final String tag = (String) k[0];
            if (k[1] instanceof Byte) bytes(b, tag, (Byte) k[1], failed);
            else if (k[1] instanceof Long) longs(b, tag, (Long) k[1], failed);
            else ints(b, tag, (Integer) k[1], failed);
        }
        return failed;
    }

    private static void ints(CaptureRequest.Builder b, String tag, int value, List<String> failed) {
        try {
            b.set(new CaptureRequest.Key<>(tag, int[].class), new int[]{value});
        } catch (RuntimeException e) {
            failed.add(tag);
        }
    }

    private static void bytes(CaptureRequest.Builder b, String tag, byte value, List<String> failed) {
        try {
            b.set(new CaptureRequest.Key<>(tag, byte[].class), new byte[]{value});
        } catch (RuntimeException e) {
            failed.add(tag);
        }
    }

    private static void longs(CaptureRequest.Builder b, String tag, long value, List<String> failed) {
        try {
            b.set(new CaptureRequest.Key<>(tag, long[].class), new long[]{value});
        } catch (RuntimeException e) {
            failed.add(tag);
        }
    }

    /** A result tag as a number (int32, int64 or byte; the first value), null when the firmware has none or it is absent. */
    @Nullable
    static Long number(@Nullable CaptureResult result, String tag) {
        if (result == null) return null;
        try {
            final long[] v = result.get(new CaptureResult.Key<>(tag, long[].class));
            if (v != null && v.length > 0) return v[0];
        } catch (RuntimeException ignored) {
        }
        try {
            final int[] v = result.get(new CaptureResult.Key<>(tag, int[].class));
            if (v != null && v.length > 0) return (long) v[0];
        } catch (RuntimeException ignored) {
        }
        try {
            final byte[] v = result.get(new CaptureResult.Key<>(tag, byte[].class));
            if (v != null && v.length > 0) return (long) v[0];
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** The probes of a still's result for the log ("tag=value" or "tag=-"). */
    static String probes(@Nullable CaptureResult result) {
        final StringBuilder sb = new StringBuilder();
        for (String tag : PROBES) {
            final Long v = number(result, tag);
            sb.append(sb.length() == 0 ? "" : " ").append(tag).append('=').append(v == null ? "-" : String.valueOf(v));
        }
        return sb.toString();
    }

    /**
     * The seamlessMode a still in the full-size mode reports: 768 for the 200 MP mode (4x the viewfinder's sides), 259 for the 50 MP
     * one (2x); 0 for another size (not checked).
     */
    static int expectedSeamless(Size still, Size viewfinder) {
        if (still == null || viewfinder == null || viewfinder.getWidth() <= 0 || viewfinder.getHeight() <= 0) return 0;
        final double k = (double) still.getWidth() / viewfinder.getWidth();
        if (Math.abs(k - 4) < 0.05) return 768;
        if (Math.abs(k - 2) < 0.05) return 259;
        return 0;
    }

    /** A still's reported seamlessMode is not the full-size mode's (an absent tag says nothing). */
    static boolean seamlessWrong(@Nullable Long reported, int expected) {
        return reported != null && expected != 0 && reported != expected;
    }

    /**
     * The HAL skipped the sensor switch and returned the binned readout (width/d x height/d) in the top-left corner of the
     * full-size buffer, the rest exactly zero (sensor data never reads 0 above its black): d (4 or 2), else 1. RAW16 samples, an 8 x 8
     * grid of single samples.
     */
    static int binnedCorner(ByteBuffer px, int width, int height, int rowStride) {
        if (px == null || width < 64 || height < 64 || rowStride < width * 2) return 1;
        if (!cornerFits(width, height, 2) || !emptyOutside(px, width, height, rowStride, 2)) return 1;
        return cornerFits(width, height, 4) && emptyOutside(px, width, height, rowStride, 4) ? 4 : 2;
    }

    private static boolean cornerFits(int width, int height, int d) {
        return width % d == 0 && height % d == 0 && (width / d) % 2 == 0 && (height / d) % 2 == 0;
    }

    private static boolean emptyOutside(ByteBuffer px, int width, int height, int rowStride, int d) {
        final int grid = 8, cw = width / d, ch = height / d, base = px.position();
        int outside = 0, inside = 0;
        for (int gy = 0; gy < grid; gy++) {
            for (int gx = 0; gx < grid; gx++) {
                final int x = (2 * gx + 1) * width / (2 * grid), y = (2 * gy + 1) * height / (2 * grid);
                final int at = base + y * rowStride + x * 2;
                if (at + 1 >= px.limit()) return false;
                final int v = (px.get(at) & 0xff) | (px.get(at + 1) & 0xff) << 8;
                if (x >= cw || y >= ch) {
                    if (v != 0) return false;
                    outside++;
                } else if (v != 0) inside++;
            }
        }
        return outside > 0 && inside > 0;
    }

    /**
     * The companion YUV stream: the smallest output of at least 320 x 240 with the viewfinder RAW's shape (within 2 %), else the
     * smallest of at least 320 x 240; null without one.
     */
    @Nullable
    static Size companion(@Nullable Size[] yuv, int rawWidth, int rawHeight) {
        if (yuv == null) return null;
        Size base = null, shaped = null;
        for (Size s : yuv) {
            if (s.getWidth() < 320 || s.getHeight() < 240) continue;
            final long area = (long) s.getWidth() * s.getHeight();
            if (base == null || area < (long) base.getWidth() * base.getHeight()) base = s;
            final long a = (long) s.getWidth() * rawHeight, b = (long) s.getHeight() * rawWidth;
            if (rawWidth > 0 && rawHeight > 0 && Math.abs(a - b) * 50 <= b
                    && (shaped == null || area < (long) shaped.getWidth() * shaped.getHeight())) shaped = s;
        }
        return shaped != null ? shaped : base;
    }
}
