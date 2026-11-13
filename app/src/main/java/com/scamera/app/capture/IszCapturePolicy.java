package com.scamera.app.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;

import com.scamera.app.api.VendorTagUtils;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.control.ZoomController;
import com.scamera.app.settings.ModuleRegistry;
import com.scamera.app.settings.TunableKeyManager;

public final class IszCapturePolicy {
    private IszCapturePolicy() { }

    public static boolean isIsz(String slot, CameraCharacteristics characteristics, CaptureResult result,
                                String physicalId, String logicalId, boolean croppedRawSession) {
        if (Dcg14Mode.on() || PixelPro10.fullResOn()) return false;
        int mode = sessionForceMode(slot);
        if (mode != 0) {
            if (VivoQcomModes.device()) {
                if ("3".equals(physicalId) || "4".equals(physicalId)) return mode == 3;
                return "5".equals(physicalId) && (mode == 5 || mode == 7 || mode == 6 || mode == 17);
            }
            if (!VivoQcomIsz.enabled() || characteristics == null) return false;
            // P214i: the role also without a vivo CameraType (by the focal length), the modes from the owner's list
            return VivoQcomIsz.iszModule(VivoQcomIsz.role(physicalId, characteristics), mode);
        }
        if (ModuleRegistry.sensorMode(slot) != 0) return false;
        // P575: the nubia «2×» is the full-size row at zoom 2 (a digital crop of the whole field), not a sensor crop: the photo's aspect applies
        if (NubiaFullRes.iszModule(slot)) return false;
        if (SensorModeSwitches.sensorPreset(slot)) return true;
        if (!croppedRawSession || !CroppedRaw.supported(characteristics)) return false;
        return CroppedRaw.factor(CroppedRaw.of(result, physicalId, logicalId),
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)) > 1f
                || ZoomController.residual() >= 1.8f;
    }

    /**
     * P223: the module runs a mosaic sensor mode - an ISZ / vendor session mode, a sensor-mode preset or a module sensor mode
     * (FULLRES sessions are known to the controller itself).
     */
    public static boolean mosaicModule(String slot) {
        try {
            return sessionForceMode(slot) != 0 || ModuleRegistry.sensorMode(slot) != 0 || SensorModeSwitches.sensorPreset(slot);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int sessionForceMode(String slot) {
        int mode = 0;
        for (VendorTagUtils.TunableKey key : TunableKeyManager.loadKeys(
                ScameraApp.getSettingsManagerStatic().getContext(), slot)) {
            if (key == null || !key.enabled || !VendorTagUtils.TunableKey.TYPE_SESSION.equals(key.type)
                    || !VivoQcomIsz.KEY.equals(key.name) || !"Integer".equals(key.valueType)) continue;
            try {
                mode = (Integer) key.parseValue();
            } catch (RuntimeException invalid) { }
        }
        return mode;
    }
}
