package com.scamera.app.ui.camera;

import com.scamera.app.capture.Dcg14Mode;
import com.scamera.app.util.Log;

/**
 * P104 / SIMPLE_UI §1: the DCG 14-bit switch - now the «DCG» button of the top bar's pill card (only on modules with a DCG
 * mode, Dcg14Mode.available), the same setting as «Для опытных → 14 бит (DCG)». A tap toggles Dcg14Mode and restarts the
 * camera (its keys are session keys). The old square button in the manual strip's end slot is gone.
 */
final class Dcg14Button {
    private static final String TAG = "Dcg14Button";

    private Dcg14Button() {}

    /** Toggles DCG; returns the message for the band («14 бит (DCG): вкл. · перезапуск») or why it did not switch. */
    static String toggleMode(CameraFragment fragment) {
        // P641: a third state, DCG + LOFIC, on a camera that has it (the Xiaomi 17 Ultra's main)
        int mode = Dcg14Mode.next(Dcg14Mode.mode(), com.scamera.app.capture.XiaomiDualGain.loficOffered(
                com.scamera.app.capture.CaptureController.mCameraCharacteristics));
        if (!com.scamera.app.capture.SensorModeSwitches.setDcgMode(mode))
            return com.scamera.app.util.Lang.t("Нет обычного модуля этой камеры для DCG", "No plain module of this camera for DCG");
        Log.i(TAG, "DCG 14 bit mode " + Dcg14Mode.name(mode));
        if (fragment.captureController != null) fragment.captureController.restartCamera();
        // P586: «DAG (телевик)» on the OnePlus 16 tele
        return Dcg14Mode.title() + (mode != Dcg14Mode.OFF ? com.scamera.app.util.Lang.t(": вкл. · перезапуск", ": on · restart")
                : com.scamera.app.util.Lang.t(": выкл. · перезапуск", ": off · restart"));
    }
}
