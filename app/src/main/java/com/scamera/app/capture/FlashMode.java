package com.scamera.app.capture;

import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import com.scamera.app.R;
import com.scamera.app.util.Lang;

import java.util.Locale;

/**
 * P590 (owner, 2026-11-09: «Добавь так же разные режимы вспышки, у нас есть только фонарик»): the flash modes of pref_ae_mode
 * (PhotonCamera's values: 0 torch, 1 off, 2 auto flash, 3 always flash) - what a tap chooses, the preview's AE and flash keys of
 * each, and when a press is a flash shot. A flash shot is never taken from the ZSL ring (its frames are exposed before the press,
 * without the flash): the AE precapture runs (the pre-flash meters the scene), then one fresh RAW frame with the flash firing
 * (AE ON_ALWAYS_FLASH / ON_AUTO_FLASH, still capture intent) is merged as a 1-frame shot. In manual exposure the frame is AE off
 * at the manual values with FLASH_MODE_SINGLE. The sensor-mode routes (ISZ, DCG / DAG, FULLRES; owner, 2026-11-09: «Тогда отключи
 * вспышку с ISZ, DCG, dag») take no flash shot: Auto / On act as off there; the torch stays.
 */
public final class FlashMode {
    private FlashMode() {}

    public static final int TORCH = 0, OFF = 1, AUTO = 2, ON = 3;
    /** A tap's order: Выкл. -> Авто -> Вкл. -> Фонарик -> Выкл. */
    static final int[] CYCLE = {OFF, AUTO, ON, TORCH};
    /** The flash shot's wait for the AE precapture (the pre-flash and its metering), as the shutter's own precapture bound. */
    public static final long PRECAPTURE_TIMEOUT_MS = 1000;
    /** Results after the trigger's own result without a PRECAPTURE state: the HAL metered at once (or runs no precapture). */
    static final int NO_PRECAPTURE_RESULTS = 4;

    /** The open lens has a flash unit (FLASH_INFO_AVAILABLE; TopBar.bindCamera on every characteristics update). */
    private static volatile boolean sFlashUnit = true;

    public static void bindLens(boolean flashUnit) {
        sFlashUnit = flashUnit;
    }

    public static boolean flashUnit() {
        return sFlashUnit;
    }

    /** The mode the top row and the shade show: the stored one as this lens allows it. */
    public static int shown() {
        return effective(com.scamera.app.settings.PreferenceKeys.getAeMode(), sFlashUnit);
    }

    /** The mode a tap on the top row's item or the shade's tile chooses. */
    public static int tap() {
        return next(com.scamera.app.settings.PreferenceKeys.getAeMode(), sFlashUnit);
    }

    /** A stored value outside 0..3 is off (the default). */
    public static int normalize(int mode) {
        return mode >= TORCH && mode <= ON ? mode : OFF;
    }

    /**
     * The mode in force: a lens without a flash unit (the front lens: its torch is the screen light, P507) takes the torch or
     * off - Auto / On are off there.
     */
    public static int effective(int mode, boolean flashUnit) {
        mode = normalize(mode);
        if ((mode == AUTO || mode == ON) && !flashUnit) return OFF;
        return mode;
    }

    /** The mode a tap chooses after {@code mode}: the whole cycle with a flash unit, else off <-> torch. */
    public static int next(int mode, boolean flashUnit) {
        final int current = effective(mode, flashUnit);
        final boolean flashModes = flashUnit;
        int at = 0;
        for (int i = 0; i < CYCLE.length; i++) if (CYCLE[i] == current) at = i;
        for (int k = 1; k <= CYCLE.length; k++) {
            final int m = CYCLE[(at + k) % CYCLE.length];
            if (flashModes || m == OFF || m == TORCH) return m;
        }
        return OFF;
    }

    /**
     * The preview's CONTROL_AE_MODE: Auto ON_AUTO_FLASH, On ON_ALWAYS_FLASH (the AE then reports FLASH_REQUIRED and the still
     * request fires the flash); otherwise ON. {@code flashShots} false (no flash unit, a sensor-mode route): ON.
     */
    public static int previewAeMode(int mode, boolean flashShots) {
        switch (effective(mode, flashShots)) {
            case AUTO: return CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
            case ON: return CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            default: return CameraMetadata.CONTROL_AE_MODE_ON;
        }
    }

    /** The preview's FLASH_MODE: the torch while it is chosen (a lens with a flash unit), else off (the AE runs the flash). */
    public static int previewFlashMode(int mode, boolean flashUnit) {
        return flashUnit && normalize(mode) == TORCH ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF;
    }

    /** The still request's AE mode of a flash shot in {@code mode} (Auto ON_AUTO_FLASH, else ON_ALWAYS_FLASH). */
    public static int stillAeMode(int mode) {
        return normalize(mode) == AUTO ? CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH : CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
    }

    /**
     * A tap's AE mode (TouchFocus): the preview's own automatic mode (its flash mode kept), ON for anything else (none, off: the
     * manual exposure is kept by the caller).
     */
    public static int touchAeMode(@Nullable Integer previewAeMode) {
        if (previewAeMode != null && (previewAeMode == CameraMetadata.CONTROL_AE_MODE_ON
                || previewAeMode == CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                || previewAeMode == CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)) return previewAeMode;
        return CameraMetadata.CONTROL_AE_MODE_ON;
    }

    /**
     * The press: a flash shot in On, and in Auto when the latest preview result's AE asks for the flash (FLASH_REQUIRED); off,
     * torch, Auto without FLASH_REQUIRED, a lens without a flash unit: the normal multi-frame shot.
     */
    public static boolean flashShot(int mode, @Nullable Integer aeState, boolean flashUnit) {
        if (!flashUnit) return false;
        mode = normalize(mode);
        return mode == ON || mode == AUTO && aeState != null && aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
    }

    /**
     * Why the session's route takes no flash shot (null: it can): the sensor-mode routes - FULLRES (50 / 200 MP), DCG / DAG, ISZ
     * (a sensor-crop module, a picked sensor-mode row, CROPPED_RAW) - keep their mode and their own capture (owner, 2026-11-09:
     * «Тогда отключи вспышку с ISZ, DCG, dag»).
     */
    @Nullable
    public static String routeBlock(boolean fullRes, boolean dcg, boolean isz, boolean pickedRow) {
        if (fullRes) return "FULLRES";
        if (dcg) return "DCG / DAG";
        if (isz) return "ISZ";
        if (pickedRow) return "sensor mode";
        return null;
    }

    /** The toast of a route without the flash shot (once a session). */
    public static String routeBlockText(String route) {
        return Lang.t("Вспышка в режиме " + route + " не работает: снимок без вспышки",
                "The flash does not work in " + route + ": the shot is taken without it");
    }

    /** The frame's flash fired (FLASH_STATE FIRED; PARTIAL lit part of the frame only). */
    public static boolean fired(@Nullable Integer flashState) {
        return flashState != null && flashState == CaptureResult.FLASH_STATE_FIRED;
    }

    /** The log's name of the mode. */
    public static String name(int mode) {
        switch (normalize(mode)) {
            case TORCH: return "torch";
            case AUTO: return "auto";
            case ON: return "on";
            default: return "off";
        }
    }

    /** The toast and the top-row item's description. */
    public static String label(int mode) {
        switch (normalize(mode)) {
            case TORCH: return Lang.t("Фонарик: вкл.", "Torch: on");
            case AUTO: return Lang.t("Вспышка: авто", "Flash: auto");
            case ON: return Lang.t("Вспышка: вкл.", "Flash: on");
            default: return Lang.t("Вспышка: выкл.", "Flash: off");
        }
    }

    /** The concept icon of the mode: a bolt with a slash, a bolt with «A», the filled bolt, the flashlight. */
    public static int icon(int mode) {
        switch (normalize(mode)) {
            case TORCH: return R.drawable.cv_torch;
            case AUTO: return R.drawable.cv_bolt_auto;
            case ON: return R.drawable.cv_bolt_fill;
            default: return R.drawable.cv_bolt_off;
        }
    }

    public static String aeStateName(@Nullable Integer state) {
        if (state == null) return "none";
        switch (state) {
            case CaptureResult.CONTROL_AE_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "SEARCHING";
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGED";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "LOCKED";
            case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED: return "FLASH_REQUIRED";
            case CaptureResult.CONTROL_AE_STATE_PRECAPTURE: return "PRECAPTURE";
            default: return String.valueOf(state);
        }
    }

    public static String flashStateName(@Nullable Integer state) {
        if (state == null) return "none";
        switch (state) {
            case CaptureResult.FLASH_STATE_UNAVAILABLE: return "UNAVAILABLE";
            case CaptureResult.FLASH_STATE_CHARGING: return "CHARGING";
            case CaptureResult.FLASH_STATE_READY: return "READY";
            case CaptureResult.FLASH_STATE_FIRED: return "FIRED";
            case CaptureResult.FLASH_STATE_PARTIAL: return "PARTIAL";
            default: return String.valueOf(state);
        }
    }

    /** A press's flash shot: its mode, the AE state at the press, the manual exposure (FLASH_MODE_SINGLE) and its precapture. */
    public static final class Shot {
        public final int mode;
        @Nullable public final Integer aeState;
        /** Manual exposure: AE off at these values with FLASH_MODE_SINGLE, no precapture (0: the AE's flash). */
        public final long manualShutterNs;
        public final int manualIso;
        public long precaptureMs = -1;
        public String precaptureEnd = "none";

        public Shot(int mode, @Nullable Integer aeState, long manualShutterNs, int manualIso) {
            this.mode = normalize(mode);
            this.aeState = aeState;
            this.manualShutterNs = manualShutterNs;
            this.manualIso = manualIso;
        }

        public boolean manual() {
            return manualShutterNs > 0 && manualIso > 0;
        }

        /** «flash shot: mode auto, AE state FLASH_REQUIRED, precapture 412 ms (CONVERGED)» */
        public String describe() {
            return String.format(Locale.ROOT, "flash shot: mode %s, AE state %s, %s", name(mode), aeStateName(aeState),
                    manual() ? String.format(Locale.ROOT, "manual %.3f ms ISO %d, FLASH_MODE_SINGLE, no precapture",
                            manualShutterNs / 1e6, manualIso)
                            : "precapture " + precaptureMs + " ms (" + precaptureEnd + ")");
        }
    }

    /**
     * The precapture wait of a flash shot: the trigger's result, then PRECAPTURE until it ends (CONVERGED / FLASH_REQUIRED / any
     * other state). A HAL that never reports PRECAPTURE ends after {@link #NO_PRECAPTURE_RESULTS} results past the trigger's; no
     * AE state ends it at once; {@link #PRECAPTURE_TIMEOUT_MS} bounds it.
     */
    public static final class Precapture {
        final long startMs;
        boolean triggered, seenPrecapture;
        int afterTrigger;

        public Precapture(long startMs) {
            this.startMs = startMs;
        }

        /** The wait's end for a completed result (null: still waiting). {@code trigger}: the result's request's precapture trigger. */
        @Nullable
        public String offer(@Nullable Integer trigger, @Nullable Integer aeState, long nowMs) {
            if (trigger != null && trigger == CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START) triggered = true;
            if (nowMs - startMs > PRECAPTURE_TIMEOUT_MS) return "timeout, AE state " + aeStateName(aeState);
            if (!triggered) return null;
            if (aeState == null) return "no AE state";
            if (aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                seenPrecapture = true;
                return null;
            }
            if (seenPrecapture) return aeStateName(aeState);
            if (++afterTrigger >= NO_PRECAPTURE_RESULTS) return "no PRECAPTURE state, AE state " + aeStateName(aeState);
            return null;
        }

        public long elapsed(long nowMs) {
            return nowMs - startMs;
        }
    }
}
