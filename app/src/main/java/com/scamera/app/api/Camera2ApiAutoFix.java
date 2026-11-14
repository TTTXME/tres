package com.scamera.app.api;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.util.Range;

import androidx.annotation.Nullable;

import com.scamera.app.capture.CaptureController;
import com.scamera.app.processing.opengl.GLDrawParams;
import com.scamera.app.processing.parameters.ExposureIndex;
import com.scamera.app.util.Log;

import java.util.List;

/**
 * Corrections of what a camera declares, applied where the app reads it: the open lens's exposure-time, ISO and
 * compensation ranges (vendor keys that know better, a ceiling that is too low), the black level a shot's result lacks,
 * the viewfinder's OIS.
 */
public final class Camera2ApiAutoFix {
    private static final String TAG = "Camera2ApiAutoFix";

    private Camera2ApiAutoFix() {
    }

    /** The open lens's ranges: the exposure time first, then the compensation, then the ISO. */
    public static void Init() {
        final CameraCharacteristics lens = CaptureController.mCameraCharacteristics;
        // one reflective key listing for both vendor scans (it was read twice per camera open)
        List<Object> vendorKeys = null;
        final Range<Long> exposure = lens.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exposure != null) {
            vendorKeys = CameraReflectionApi.getCameraCharacteristicsKeys(lens, null, true);
            fixExposureTime(lens, exposure, vendorKeys);
        }
        final Range<Integer> compensation = exposureCompensationRange(lens.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE));
        CameraReflectionApi.set(lens, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE, compensation);
        final Range<Integer> iso = lens.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (iso != null) {
            if (vendorKeys == null) vendorKeys = CameraReflectionApi.getCameraCharacteristicsKeys(lens, null, true);
            fixIso(lens, iso, vendorKeys);
        }
    }

    /**
     * The longest exposure: a ceiling under 1/7 s becomes 1/3 s, one of 5/6 - 2 s becomes 5.2 s; then the first vendor key
     * (…exposureTimeRange, or hw-sensor-exposure-range in us) above the declared ceiling replaces the range.
     */
    private static void fixExposureTime(CameraCharacteristics lens, Range<Long> declared, @Nullable List<Object> keys) {
        final long ceiling = declared.getUpper();
        final long sec = ExposureIndex.sec;
        if (ceiling < sec / 7) {
            Log.d(TAG, "Applied Fix ExposureTime no CIT");
            CameraReflectionApi.set(lens, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE, new Range<>(declared.getLower(), sec / 3));
        } else if (ceiling > sec * 5 / 6 && ceiling < sec * 2) {
            Log.d(TAG, "Applied Fix ExposureTime2 CIT SHIFT");
            CameraReflectionApi.set(lens, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                    new Range<>(declared.getLower(), (long) (sec * 5.2)));
        }
        if (keys == null) return;
        for (Object entry : keys) {
            if (!(entry instanceof CameraCharacteristics.Key<?>)) continue;
            final CameraCharacteristics.Key<?> key = (CameraCharacteristics.Key<?>) entry;
            final String name = key.getName();
            final boolean microseconds = name.contains("hw-sensor-exposure-range");
            if (!(microseconds || name.contains("exposureTimeRange")) || name.contains("android")) continue;
            try {
                final Object value = lens.get(key);
                final long[] range;
                if (value instanceof long[]) range = ((long[]) value).clone();
                else if (value instanceof int[]) range = new long[]{((int[]) value)[0], ((int[]) value)[1]};
                else {
                    if (value != null) Log.w(TAG, "Unsupported exposureTimeRange type: " + value.getClass().getName());
                    continue;
                }
                final int scale = microseconds ? 1000 : 1;
                if (ceiling < range[1] * scale) {
                    Log.d(TAG, "Applied Fix ExposureTime vendor update");
                    CameraReflectionApi.set(lens, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                            new Range<>(range[0] * scale, range[1] * scale));
                    return;
                }
            } catch (RuntimeException unreadable) {
                // a vendor key of another shape: the next one
            }
        }
    }

    /** The highest ISO: the first vendor …sensitivityRange key above the declared top replaces the range. */
    private static void fixIso(CameraCharacteristics lens, Range<Integer> declared, @Nullable List<Object> keys) {
        if (keys == null) return;
        final int top = declared.getUpper();
        for (Object entry : keys) {
            if (!(entry instanceof CameraCharacteristics.Key<?>)) continue;
            final String name = ((CameraCharacteristics.Key<?>) entry).getName();
            if (!name.contains("sensitivityRange") || name.contains("android")) continue;
            try {
                final int[] range = (int[]) lens.get((CameraCharacteristics.Key<?>) entry);
                if (top < range[1]) {
                    Log.d(TAG, "Applied Fix sensitivityRange vendor update");
                    CameraReflectionApi.set(lens, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, new Range<>(range[0], range[1]));
                    return;
                }
            } catch (RuntimeException unreadable) {
                // a vendor key of another shape: the next one
            }
        }
    }

    /**
     * The widened compensation range: the former +-24 steps (+-8 EV at the usual 1/3 EV step), never narrower than declared.
     * P296 (owner, 2026-10-20, OPPO Find X8 Ultra: «в ручнике регулировка экспозиции только 0 и -0.1»): its step is 1/330 EV
     * (declared +-990 = +-3 EV), and the fixed +-24 steps left +-0.07 EV.
     */
    static Range<Integer> exposureCompensationRange(Range<Integer> declared) {
        if (declared == null) return new Range<>(-24, 24);
        return new Range<>(Math.min(declared.getLower(), -24), Math.max(declared.getUpper(), 24));
    }

    /**
     * Fixes of the shot's result before processing. P370 (A4): only into {@code captureResult} itself - the viewfinder's neutral and
     * colour transform were once written into the static CaptureController.mCaptureResult at processing time, so a queued shot's
     * metadata could be rewritten by a later shot. A result without SENSOR_DYNAMIC_BLACK_LEVEL gets the static pattern of
     * {@code shot} - the characteristics the shot was captured with (P370 review: not the camera open at its processing); null:
     * the open camera's.
     */
    @SuppressLint("NewApi")
    public static void ApplyRes(CaptureResult captureResult, @Nullable CameraCharacteristics shot) {
        if (captureResult == null) return;
        final CameraCharacteristics lens = shot != null ? shot : CaptureController.mCameraCharacteristics;
        final BlackLevelPattern pattern = lens == null ? null : lens.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (pattern == null || captureResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) != null) return;
        final float[] black = new float[4];
        for (int site = 0; site < 4; site++) black[site] = pattern.getOffsetForIndex(site % 2, site / 2);
        CameraReflectionApi.set(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL, black, captureResult);
    }

    /** Full-size GL tiles for a capture (P683: PostPipeline no longer drops them to 8 rows for small RAW sizes). */
    public static void resetTileSize() {
        GLDrawParams.TILE_ROWS = 256;
    }

    /** The viewfinder's OIS on where the lens lists it (the preview and the series then shake alike). */
    public static void applyPrev(CaptureRequest.Builder preview) {
        final int[] modes = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (!CaptureController.isOisSupported(modes)) return;
        Log.d(TAG, "LENS_OPTICAL_STABILIZATION_MODE");
        preview.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
    }
}
