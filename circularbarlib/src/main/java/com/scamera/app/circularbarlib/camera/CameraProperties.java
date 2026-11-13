package com.scamera.app.circularbarlib.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Log;
import android.util.Range;

/**
 * The manual ranges of one camera: focus (diopters, null for a fixed-focus lens), ISO, exposure time (ns) and exposure
 * compensation (EV). ISO and exposure come from {@link #rangeProvider} when the app installed one (P87), else from the
 * declared sensor ranges.
 */
public class CameraProperties {
    private static final String TAG = "CameraProperties";
    private static final long SECOND_NS = 1_000_000_000L;

    public final Range<Float> focusRange;
    public final Range<Integer> isoRange;
    public final Range<Long> expRange;
    public final Range<Float> evRange;

    /**
     * P87: the app's ISO / shutter range of a module (vendor pro-mode keys, the frame-duration ceiling, the AE's own extremes)
     * instead of the declared SENSOR_INFO ranges, which some HALs publish narrowed (vivo X200 Ultra: ISO 1144..3200, 0.33 s).
     */
    public interface RangeProvider {
        Range<Integer> iso(CameraCharacteristics characteristics);

        Range<Long> exposure(CameraCharacteristics characteristics);
    }

    public static volatile RangeProvider rangeProvider;

    public CameraProperties(CameraCharacteristics c) {
        // Focus runs from infinity (0 diopters) to the closest distance; no minimum distance = a fixed-focus lens.
        Float closest = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        focusRange = closest == null || closest == 0f ? null : new Range<>(Math.min(closest, 0f), Math.max(closest, 0f));

        Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> declaredExposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Long> exposure = declaredExposure != null ? new Range<>(declaredExposure.getLower(), declaredExposure.getUpper())
                : new Range<>(SECOND_NS / 1000, SECOND_NS);
        if (iso == null) iso = new Range<>(100, 3200);
        RangeProvider app = rangeProvider;
        if (app != null) {
            try {
                Range<Integer> appIso = app.iso(c);
                Range<Long> appExposure = app.exposure(c);
                if (appIso != null) iso = appIso;
                if (appExposure != null) exposure = appExposure;
            } catch (RuntimeException e) {
                Log.w(TAG, "range provider failed: " + e);
            }
        }
        isoRange = iso;
        expRange = exposure;

        float step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
        Range<Integer> steps = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        evRange = new Range<>(steps.getLower() * step, steps.getUpper() * step);
        Log.d(TAG, "focus " + (focusRange == null ? "fixed" : focusRange) + ", iso " + isoRange + ", exposure " + expRange
                + ", ev " + evRange);
    }
}
