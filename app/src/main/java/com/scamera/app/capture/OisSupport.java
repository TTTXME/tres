package com.scamera.app.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

/**
 * P707: the lens's optical / video stabilisation support, moved out of {@link CaptureController} unchanged (the controller keeps
 * its public entry points, which call these): whether a lens has OIS (listed, or hidden by the HAL), and the viewfinder's
 * stabilisation mode.
 */
final class OisSupport {
    private OisSupport() {
    }

    /**
     * Returns true if the optical image stabilization modes indicate OIS hardware support.
     * Android spec: LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION contains supported modes.
     * Qualcomm HALs typically return [0, 1] (OFF, ON); MediaTek/Vivo HALs often return [1] (ON only).
     * Any array containing LENS_OPTICAL_STABILIZATION_MODE_ON (1) supports OIS.
     */
    public static boolean isOisSupported(int[] modes) {
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOisSupported(CameraCharacteristics chars) {
        if (chars == null) return false;
        return isOisSupported(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
    }

    /**
     * Whether the lens is optically stabilised, also where Camera2 does not say so: the OPPO Find X7 / X8 / X9 Ultra list OIS
     * OFF only on every module (SCAM TEST reports, 2026-10-11) while every module has OIS (owner; qcom,ois nodes in the X7U
     * device tree) that the HAL drives itself. Only for decisions about the frames; OIS modes are still sent only where listed.
     */
    static boolean hasOisHardware(CameraCharacteristics chars) {
        return isOisSupported(chars) || halHiddenOis(Build.MANUFACTURER, Build.MODEL);
    }

    static boolean halHiddenOis(String manufacturer, String model) {
        if (model == null) return false;
        final String m = model.toUpperCase(java.util.Locale.ROOT);
        // OnePlus 15 (SCAM TEST 2026-10-11): OIS OFF only in the characteristics, ON in the main / tele templates (0, 2, 4)
        if ("OnePlus".equalsIgnoreCase(manufacturer)) return m.equals("CPH2747");
        if (!"OPPO".equalsIgnoreCase(manufacturer)) return false;
        return m.equals("PHY110") || m.equals("PKJ110") || m.equals("CPH2841");
    }

    /**
     * The viewfinder's CONTROL_VIDEO_STABILIZATION_MODE: with EIS Photo on, on vivo only (P54d),
     * CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (Android 13+, API 33: the viewfinder stabilised, the still framing
     * not); a camera without OIS (e.g. the ultra wide, the front) and without that mode CONTROL_VIDEO_STABILIZATION_MODE_ON.
     */
    public static int getPreferredVideoStabilizationMode(CameraCharacteristics chars, boolean eisPhotoOn) {
        if (!eisPhotoOn || chars == null) {
            return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
        int[] vsModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (vsModes == null || vsModes.length == 0) {
            return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
        boolean hasPreviewStab = false;
        boolean hasOn = false;
        for (int m : vsModes) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) {
                hasPreviewStab = true;
            }
            if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                hasOn = true;
            }
        }
        if (vivoBrand()) {
            // P54d (vivo X200 FE / X300 Ultra) only. Elsewhere the photo preview keeps no stabilisation mode, as
            // before P54d (Xiaomi 13 Ultra / OPPO Find X9 Ultra: mode 2 next to the RAW stream stalled or killed the HAL).
            if (hasPreviewStab) {
                return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
            }
            if (!isOisSupported(chars) && hasOn) {
                return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            }
        }
        return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
    }

    /** vivo / iQOO: the brands P54d's photo-mode stabilisation (PREVIEW_STABILIZATION, session parameter) was made for. */
    static boolean vivoBrand() {
        return "vivo".equalsIgnoreCase(Build.BRAND) || "iqoo".equalsIgnoreCase(Build.BRAND) || "vivo".equalsIgnoreCase(Build.MANUFACTURER);
    }
}
