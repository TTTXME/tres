package com.scamera.app.processing.opengl.postpipeline;

import android.hardware.camera2.CaptureResult;

/**
 * P501 (owner, 2026-11-04/05, Pixel 10 Pro and Pixel 7: dark and noisy photos and a dark viewfinder in dim rooms): the camera's
 * post-RAW gain (CONTROL_POST_RAW_SENSITIVITY_BOOST, 100 = none; the logs: 209 on the Pixel 10 Pro at ISO 289, 137 on the Pixel 7) is
 * the brightness its AE meant and the RAW never gets. The WHY tone develops that RAW, and its noise clamp held the gain below what the
 * camera itself applies; its gain limits widen by this factor (WhyAe.Settings.motionGain, with the «Движение» ceiling's), so the
 * picture gets at least the camera's own exposure.
 */
public final class RawBoost {
    private RawBoost() {}

    private static volatile float live = 1f;

    /** The factor of a result (1 without the key, at 100 or below; at most 64). */
    public static float of(CaptureResult result) {
        if (result == null) return 1f;
        try {
            final Integer boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
            return factor(boost);
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    static float factor(Integer boost) {
        return boost != null && boost > 100 ? Math.min(boost / 100f, 64f) : 1f;
    }

    /** The viewfinder's result (CaptureController's preview callback). */
    public static void observe(CaptureResult result) {
        live = of(result);
    }

    /** The viewfinder's current factor (1 before any result). */
    public static float live() {
        return live;
    }
}
