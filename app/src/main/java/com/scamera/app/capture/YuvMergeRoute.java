package com.scamera.app.capture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;

import androidx.annotation.Nullable;

import com.scamera.app.processing.yuv.YuvFrames;
import com.scamera.app.settings.ModuleRegistry;
import com.scamera.app.settings.ModuleSensorSettings;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.settings.SensorConfigPreferenceGenerator;
import com.scamera.app.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P646 «Склейка YUV» (owner, 2026-11-12: «переключатель должен быть в настройках линзы ... По дефолту включи этот метод склейки на
 * Vivo X200 Pro на фронтальной камере, там нет raw, есть только yuv»): the lens's route of merging YUV frames
 * (processing/yuv/YuvBurst) instead of RAW ones. Its switch is the lens's (pref_sensorconfig_&lt;slot&gt;_yuvmerge, like every
 * per-lens switch: a module's copy and reset take it); not stored, it is on only for the vivo X200 Pro's front camera "1" (no RAW
 * stream: before P646 its shots were never processed). On a lens with RAW it replaces the RAW route only when switched on.
 * <p>
 * The session of a lens with the route on: the camera's largest YUV_420_888 stream as the shot's reader (the existing RAW-less
 * session of CaptureController), or 10-bit YCBCR_P010 of the HLG10 profile where the camera lists it at that size and allows it
 * beside the standard viewfinder (Android 13). The shot: by default the whole series after the shutter with our tone curve
 * (TONEMAP_MODE_CONTRAST_CURVE, sRGB points), the camera's noise reduction at its minimum and its edge enhancement off, exposed by
 * YuvMergePlan (N + ES + L); «Из буфера ZSL»: the ring's frames at the camera's own curve, no brackets.
 */
public final class YuvMergeRoute {
    private YuvMergeRoute() {}

    static final String TAG = "SCAM_CAPTURE";
    public static final String FIELD = "yuvmerge";
    public static final int YUV = ImageFormat.YUV_420_888, P010 = YuvFrames.P010;
    /** DynamicRangeProfiles.HLG10 / STANDARD (API 33). */
    static final long HLG10 = 2L, STANDARD = 1L;

    public static String key(String slot) {
        return ModuleSensorSettings.prefix(slot) + FIELD;
    }

    /** The vivo X200 Pro (the owner's report: MODEL V2405A, DEVICE PD2405; its global models V2405DA / PD2405F as well). */
    public static boolean x200Pro(@Nullable String manufacturer, @Nullable String model, @Nullable String device) {
        if (manufacturer == null || !manufacturer.trim().equalsIgnoreCase("vivo")) return false;
        final String m = model == null ? "" : model.trim().toUpperCase(Locale.ROOT);
        final String d = device == null ? "" : device.trim().toUpperCase(Locale.ROOT);
        return m.startsWith("V2405") || d.startsWith("PD2405");
    }

    public static boolean x200Pro() {
        return x200Pro(Build.MANUFACTURER, Build.MODEL, Build.DEVICE);
    }

    /** The route without a stored switch: the X200 Pro's front camera "1" (YUV only). */
    public static boolean defaultOn(boolean x200Pro, @Nullable String camera) {
        return x200Pro && "1".equals(camera);
    }

    /** The switch of {@code slot} (its camera {@code camera}) in {@code values}: stored, else {@link #defaultOn}. */
    public static boolean on(@Nullable Map<String, ?> values, @Nullable String slot, @Nullable String camera, boolean x200Pro) {
        if (slot == null) return false;
        final Object stored = values == null ? null : values.get(key(slot));
        return stored != null ? PreferenceNumber.bool(stored, false) : defaultOn(x200Pro, camera);
    }

    /** The physical camera of a lens page's / module's {@code slot}. */
    @Nullable
    public static String cameraOf(@Nullable String slot) {
        if (slot == null) return null;
        try {
            return ModuleRegistry.slots().contains(slot) ? ModuleSensorSettings.physical(slot) : SensorConfigPreferenceGenerator.toPhysicalId(slot);
        } catch (RuntimeException e) {
            return SensorConfigPreferenceGenerator.toPhysicalId(slot);
        }
    }

    /**
     * P667: the module slot whose switch decides the route of a session on {@code physicalId}: the active module when it is this
     * camera, else the first visible module of this camera, else any module of it; null when no module is this camera (the
     * route then follows {@link #defaultOn}). Before P667 only the active module counted: a session that opened camera "1" while
     * the stored module was still a back one (the app's first open, a restart that reopened the front camera; owner's logs of
     * builds 31262 / 31266) ran the plain RAW-less session at 8 bits from the ring, the next one after a lens flip the route's
     * P010 / HLG10 series - two different photos from one camera.
     */
    @Nullable
    public static String slotFor(@Nullable String physicalId, @Nullable String active, @Nullable java.util.List<String> slots,
                                 java.util.function.Function<String, String> cameraOf, java.util.function.Predicate<String> visible) {
        if (physicalId == null) return null;
        if (active != null && physicalId.equals(cameraOf.apply(active))) return active;
        String any = null;
        if (slots != null)
            for (String slot : slots) {
                if (!physicalId.equals(cameraOf.apply(slot))) continue;
                if (visible.test(slot)) return slot;
                if (any == null) any = slot;
            }
        return any;
    }

    /** P667: the route of a session on {@code physicalId}: the switch of its {@link #slotFor} module, else the default. */
    public static boolean onFor(@Nullable Map<String, ?> values, @Nullable String physicalId, @Nullable String slot, boolean x200Pro) {
        if (physicalId == null) return false;
        return slot != null ? on(values, slot, physicalId, x200Pro) : defaultOn(x200Pro, physicalId);
    }

    /**
     * P667: the default of «YUV: 10 бит» - off. The X200 Pro front camera's HLG10 session (build 31266) gave a washed-out white
     * photo and viewfinder: its HAL renders the whole session for HLG (the STANDARD viewfinder stream too), and where its HLG
     * puts the scene's white is the camera's choice (BT.2408 says 75 % of the signal; the merge has to assume one), while the
     * 8-bit stream's white is the code 255 by definition. 8 bits, our capture curve, give the photo of the 8-bit session.
     */
    public static final boolean TEN_BIT_DEFAULT = false;

    /** The current module's lens has the route on (the settings' greying: DeviceAvailability); never throws. */
    public static boolean activeOn(@Nullable Map<String, ?> values) {
        try {
            if (!PreferenceKeys.scamDevSwitch("yuv_merge", true)) return false;
            final String slot = ModuleRegistry.active();
            return on(values, slot, cameraOf(slot), x200Pro());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What the session of a lens with the route on runs. */
    public static final class Session {
        public final String camera;
        public final int format;
        public final boolean hlg;
        /** The series after the shutter (our curve, brackets); false: the ZSL ring at the camera's curve. */
        public final boolean afterShutter;
        /** The camera takes our tone curve (CONTRAST_CURVE, MANUAL_POST_PROCESSING); 10-bit HLG needs none. */
        public final boolean curve;
        public final String why;
        /** P656: bytes of one packed frame of the session's stream (0: unknown), for the ring's memory cap (YuvMergePlan.ringFrames). */
        public long frameBytes;

        Session(String camera, int format, boolean hlg, boolean afterShutter, boolean curve, String why) {
            this.camera = camera;
            this.format = format;
            this.hlg = hlg;
            this.afterShutter = afterShutter;
            this.curve = curve;
            this.why = why;
        }

        @Override public String toString() {
            return "camera " + camera + " " + (format == P010 ? "P010" + (hlg ? " HLG10" : "") : "YUV_420_888") + ", "
                    + (afterShutter ? "series after the shutter" : "series from the ZSL ring") + (curve ? ", our tone curve" : "")
                    + (why.isEmpty() ? "" : " (" + why + ")");
        }
    }

    private static final Set<String> sTenBitRefused = Collections.synchronizedSet(new HashSet<>());
    private static volatile Session sCurrent;

    /** The running session's route (null: a RAW session or the route off). */
    @Nullable
    public static Session current() {
        return sCurrent;
    }

    /** P646: a P010 session of {@code camera} failed to configure: 8 bits from now on (this process); true when it was P010. */
    public static boolean refuseTenBit(@Nullable String camera) {
        final Session s = sCurrent;
        if (camera == null || s == null || s.format != P010 || !camera.equals(s.camera)) return false;
        sTenBitRefused.add(camera);
        Log.w(TAG, "YUV MERGE camera " + camera + ": the P010 session did not configure, 8-bit YUV from now on");
        return true;
    }

    /**
     * The route of the session about to open on {@code physicalId} (CaptureController.loadLens): null when the
     * active module is not this camera, its switch is off, scam_dev "yuv_merge 0", or the camera lists no YUV size.
     */
    @Nullable
    public static Session session(@Nullable String physicalId, @Nullable StreamConfigurationMap map, @Nullable CameraCharacteristics c) {
        sCurrent = null;
        YuvFrames.sessionHlg = false;
        try {
            if (physicalId == null || map == null || c == null || !PreferenceKeys.scamDevSwitch("yuv_merge", true)) return null;
            final String active = ModuleRegistry.active();
            final String slot = slotFor(physicalId, active, ModuleRegistry.slots(), YuvMergeRoute::cameraOf, ModuleRegistry::visible);
            final Map<String, ?> values = com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getAll();
            if (!onFor(values, physicalId, slot, x200Pro())) return null;
            if (slot != null && !slot.equals(active))
                Log.i(TAG, "YUV MERGE camera " + physicalId + ": the stored module " + active + " is another camera, module " + slot + "'s switch (P667)");
            else if (slot == null) Log.i(TAG, "YUV MERGE camera " + physicalId + ": no module of this camera yet, the default switch (P667)");
            final Size largest = largest(map.getOutputSizes(YUV));
            if (largest == null) {
                Log.w(TAG, "YUV MERGE camera " + physicalId + ": no YUV_420_888 size, the route is off");
                return null;
            }
            final boolean after = !"zsl".equals(PreferenceKeys.hybridString("yuv_capture", "after"));
            final StringBuilder why = new StringBuilder();
            final boolean ten = tenBit(physicalId, map, c, largest, why);
            final boolean curve = !ten && after && curveSupported(c);
            if (after && !ten && !curve) why.append(why.length() == 0 ? "" : "; ").append("no CONTRAST_CURVE tonemap: the camera's curve, no brackets");
            final Session s = new Session(physicalId, ten ? P010 : YUV, ten, after, curve, why.toString());
            s.frameBytes = YuvFrames.packedBytes(largest.getWidth(), largest.getHeight(), ten ? 10 : 8);
            sCurrent = s;
            YuvFrames.sessionHlg = ten;
            Log.i(TAG, "YUV MERGE session: " + s + ", stream " + largest + " (P646)");
            return s;
        } catch (RuntimeException e) {
            Log.w(TAG, "YUV MERGE session: " + e);
            return null;
        }
    }

    @Nullable
    static Size largest(@Nullable Size[] sizes) {
        Size best = null;
        if (sizes != null) for (Size s : sizes) if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        return best;
    }

    /** P010 at the YUV still size with HLG10 allowed beside STANDARD (the viewfinder), Android 13, not refused, the row on. */
    static boolean tenBit(String camera, StreamConfigurationMap map, CameraCharacteristics c, Size still, StringBuilder why) {
        if (!PreferenceKeys.hybridSwitch("yuv_10bit", TEN_BIT_DEFAULT)) { why.append("10 bit off"); return false; }
        if (Build.VERSION.SDK_INT < 33) { why.append("10 bit needs Android 13"); return false; }
        if (sTenBitRefused.contains(camera)) { why.append("10 bit refused by this camera before"); return false; }
        boolean size = false;
        final Size[] p010 = map.getOutputSizes(P010);
        if (p010 != null) for (Size s : p010) size |= s.equals(still);
        if (!size) { why.append("no P010 ").append(still); return false; }
        try {
            final android.hardware.camera2.params.DynamicRangeProfiles profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
            if (profiles == null || !profiles.getSupportedProfiles().contains(HLG10)) { why.append("no HLG10 profile"); return false; }
            final Set<Long> together = profiles.getProfileCaptureRequestConstraints(HLG10);
            // an empty set: no constraint (any profile may share the request)
            if (!together.isEmpty() && !together.contains(STANDARD)) { why.append("HLG10 cannot share a request with the viewfinder"); return false; }
            return true;
        } catch (RuntimeException e) {
            why.append("dynamic range profiles: ").append(e.getMessage());
            return false;
        }
    }

    /** MANUAL_POST_PROCESSING with TONEMAP_MODE_CONTRAST_CURVE and at least two curve points. */
    static boolean curveSupported(CameraCharacteristics c) {
        final int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        final int[] modes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        final Integer points = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        return contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
                && contains(modes, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) && points != null && points >= 2;
    }

    static boolean contains(@Nullable int[] values, int v) {
        if (values != null) for (int x : values) if (x == v) return true;
        return false;
    }

    /**
     * The capture tone curve: {@code n} points (the camera's maximum, at most 64) of the sRGB curve, denser in the shadows (input
     * (i / (n - 1))^2.2). Interleaved (in, out) pairs as TonemapCurve takes them; the processing inverts exactly these points.
     */
    public static float[] curvePairs(int n) {
        n = Math.max(2, Math.min(64, n));
        final float[] pairs = new float[2 * n];
        for (int i = 0; i < n; i++) {
            final float x = (float) Math.pow((double) i / (n - 1), 2.2);
            pairs[2 * i] = x;
            pairs[2 * i + 1] = srgb(x);
        }
        return pairs;
    }

    static float srgb(float x) {
        return x <= 0.0031308f ? 12.92f * x : (float) (1.055 * Math.pow(x, 1 / 2.4) - 0.055);
    }

    /** The points of {@link #curvePairs} for {@code c} (the camera's maximum count). */
    public static float[] curvePairs(@Nullable CameraCharacteristics c) {
        final Integer points = c == null ? null : c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        return curvePairs(points == null ? 64 : points);
    }

    /**
     * The series' own controls on {@code b} (after the preview's were copied): our curve where the session takes it, the camera's
     * noise reduction at MINIMAL (OFF where MINIMAL is not listed) and EDGE_MODE OFF - the merge denoises, our pipeline sharpens.
     */
    public static String applyCaptureControls(CaptureRequest.Builder b, CameraCharacteristics c, Session s) {
        final StringBuilder done = new StringBuilder();
        if (s.curve) {
            final float[] pairs = curvePairs(c);
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE);
            b.set(CaptureRequest.TONEMAP_CURVE, new android.hardware.camera2.params.TonemapCurve(pairs, pairs, pairs));
            done.append("tonemap CONTRAST_CURVE ").append(pairs.length / 2).append(" sRGB points");
        }
        final int[] nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        final Integer nrMode = contains(nr, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) ? Integer.valueOf(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
                : contains(nr, CameraMetadata.NOISE_REDUCTION_MODE_OFF) ? Integer.valueOf(CameraMetadata.NOISE_REDUCTION_MODE_OFF) : null;
        if (nrMode != null) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode);
            done.append(done.length() == 0 ? "" : ", ").append("noise reduction ").append(nrMode == CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL ? "MINIMAL" : "OFF");
        }
        if (contains(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CameraMetadata.EDGE_MODE_OFF)) {
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF);
            done.append(done.length() == 0 ? "" : ", ").append("edge OFF");
        }
        // P650: the after-shutter series' frames carry the camera's white balance baked in (YUV_ISP, identity colour in the merge).
        // If the camera's AWB drifts frame to frame, the merge blends a different subset of colours per tile (deghost / chroma
        // rejection) and the face splits (yellow-green vs magenta). Locking AWB at the shutter's metered balance keeps every frame
        // on one white point, so the merge is colour-consistent; the balance value is the preview's, so the look is unchanged.
        // scam_dev "yuv_awb_lock 0" turns it off.
        final Boolean awbLockable = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
        if (s.afterShutter && Boolean.TRUE.equals(awbLockable) && PreferenceKeys.scamDevSwitch("yuv_awb_lock", true)) {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, Boolean.TRUE);
            done.append(done.length() == 0 ? "" : ", ").append("AWB locked");
        }
        return done.toString();
    }

    /** The shot's metering of the newest YUV ring frame's Y: {clipped share (Y >= 250 of 255), deep-shadow share (Y <= 24)}. */
    public static float[] meter(@Nullable com.scamera.app.processing.RingFrame newest) {
        final float[] out = {0f, 0f};
        if (newest == null) return out;
        try {
            final java.nio.ByteBuffer y = newest.buffer();
            final int w = newest.getWidth(), h = newest.getHeight(), row = newest.rowStride(), px = Math.max(1, newest.pixelStride());
            final boolean ten = newest.getFormat() == P010;
            long clipped = 0, dark = 0, n = 0;
            for (int r = 0; r < h; r += 8)
                for (int x = 0; x < w; x += 8) {
                    final int at = r * row + x * px;
                    if (at + (ten ? 1 : 0) >= y.capacity()) continue;
                    final int v = ten ? ((y.get(at) & 0xff) | (y.get(at + 1) & 0xff) << 8) >>> 8 : y.get(at) & 0xff;
                    clipped += v >= 250 ? 1 : 0;
                    dark += v <= 24 ? 1 : 0;
                    n++;
                }
            if (n > 0) {
                out[0] = (float) clipped / n;
                out[1] = (float) dark / n;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "YUV MERGE metering: " + e);
        }
        return out;
    }
}
