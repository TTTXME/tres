package com.scamera.app.capture;

import android.content.SharedPreferences;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.control.ZoomController;
import com.scamera.app.settings.ModuleRegistry;
import com.scamera.app.settings.PreferenceKeys;

public final class SensorModeSwitches {
    private SensorModeSwitches() { }

    public static boolean sensorPreset(String slot) {
        SharedPreferences prefs = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        if (ModuleRegistry.sensorMode(slot) != 0 || ModuleRegistry.sensorCrop(slot)) return true;
        if (NubiaFullRes.iszModule(slot)) return true; // P575: the nubia «2×» (a module with its own sensor-mode row)
        if (pixelBase(ModuleRegistry.camera(slot)) != null) return true;
        String source = ModuleRegistry.duplicateOf(slot);
        if (source == null || !ModuleRegistry.slots().contains(source) || !ModuleRegistry.visible(source)
                || !ModuleRegistry.camera(source).equals(ModuleRegistry.camera(slot))) return false;
        float ratio = ModuleRegistry.zoom(slot) / ModuleRegistry.zoom(source);
        String camera = ModuleRegistry.camera(slot);
        return VivoQcomModes.device() && ("3".equals(camera) || "4".equals(camera))
                && prefs.getInt("module_vivo_qcom_isz_" + slot, 0) > 1 && Math.abs(ratio - 2f) < 0.08f;
    }

    public static void onModuleSelected(String slot) {
        if ((VivoQcomModes.device() || VivoNativeFullRes.device() || OppoNativeFullRes.device()) && PixelPro10.fullResOn()
                && !VivoSdkFullRes.offered(ModuleRegistry.camera(slot)))
            PixelPro10.setFullRes(false);
        // P575: a nubia module whose camera has no FULLRES row (the front, a failed row) leaves the size switch at 12 Мп
        if (NubiaFullRes.device() && PixelPro10.fullResOn() && NubiaFullRes.known(ModuleRegistry.camera(slot))
                && !NubiaFullRes.offered(ModuleRegistry.camera(slot)))
            PixelPro10.setFullRes(false);
        if (!sensorPreset(slot)) return;
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(false);
    }

    /** P641: the DCG switch's state (Dcg14Mode.OFF / DCG / LOFIC); a mode that is on needs a plain module of the camera. */
    public static boolean setDcgMode(int mode) {
        final boolean on = mode != Dcg14Mode.OFF;
        if (on && !selectPlain()) return false;
        if (on) PixelPro10.setFullRes(false);
        Dcg14Mode.setMode(mode);
        return true;
    }

    public static boolean setFullRes(boolean on) {
        if (on && !selectPlain()) return false;
        if (on) Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(on);
        return true;
    }

    public static boolean selectPlain() {
        String active = ModuleRegistry.active();
        if (!sensorPreset(active)) return true;
        String id = ModuleRegistry.camera(active);
        String pixelBase = pixelBase(id);
        String source = ModuleRegistry.duplicateOf(active);
        String chosen = null;
        for (String slot : ModuleRegistry.slots()) {
            String other = ModuleRegistry.camera(slot);
            boolean sameCamera = pixelBase == null ? other.equals(id) : physical(other).equals(pixelBase);
            if (!ModuleRegistry.visible(slot) || !sameCamera
                    || !sameSide(active, slot) || sensorPreset(slot)) continue;
            if (slot.equals(source)) {
                chosen = slot;
                break;
            }
            if (chosen == null || ModuleRegistry.zoom(slot) < ModuleRegistry.zoom(chosen)) chosen = slot;
        }
        if (chosen == null) return false;
        ModuleRegistry.select(chosen);
        PreferenceKeys.setCameraID(ModuleRegistry.camera(chosen));
        ScameraApp.getSettings().mCameraID = ModuleRegistry.camera(chosen);
        ZoomController.onButton(chosen);
        return true;
    }

    private static boolean sameSide(String first, String second) {
        return first.startsWith("front") == second.startsWith("front");
    }

    private static String pixelBase(String id) {
        if (!PixelPro10.device()) return null;
        switch (physical(id)) {
            case "5": return "2";
            case "6": return "4";
            case "9": return "3";
            default: return null;
        }
    }

    private static String physical(String id) {
        String[] parts = id.split("[-/]", 2);
        return parts.length == 2 ? parts[1] : id;
    }
}
