package com.scamera.app.api;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Allocator;

/**
 * The settings a shot and the camera read often, copied from the preferences by {@link #loadCache()} (the camera screen calls
 * it when it comes up and after the settings screen).
 */
public class Settings {
    // ── the camera and its mode ──
    /** The camera id the screen opens ("logical-physical" or one id). */
    public String cameraId;
    /** N frames both routes take at least (the legacy frame-count setting is gone). */
    public int frameCount;
    /** Always false: both routes merge the full-resolution RAW (software binning was a legacy option). */
    public boolean binning;
    /** The capture mode (CameraMode.valueOf: the retired modes read as MOTION). */
    public CameraMode mode;
    /** The forced CFA order (-1: the camera's), the RAW stream choice, the remosaic switch and the preview frame rate. */
    public int forcedCfa, rawFormat, previewFps;
    public boolean remosaicOn;

    // ── the shot's output ──
    public boolean stampWatermark, wide169, fullDebug;
    /** Ultra HDR in effect: the switch is on and the shot writes a JPEG (JPEG format or «Также сохранять JPEG»). */
    public boolean ultraHdr;
    /** P46 «HDR в HEIC / AVIF» in effect for this shot (processing.color.HdrOutput.wanted): the HEIC / AVIF is HLG. */
    public boolean hdrOutput;
    /** P46 «Цветовое пространство»: the primaries the post pipeline renders in and the files declare. */
    public com.scamera.app.processing.color.OutputColour.Space colourSpace =
            com.scamera.app.processing.color.OutputColour.Space.SRGB;
    public int alignMethod, colourMethod, peakingColour, themeIndex;

    /** Re-reads every value from the preferences. */
    public void loadCache() {
        frameCount = 4;
        binning = Allocator.binning = false;
        cameraId = PreferenceKeys.getCameraID();
        mode = CameraMode.valueOf(PreferenceKeys.getCameraModeOrdinal());
        forcedCfa = PreferenceKeys.getCFAValue();
        rawFormat = PreferenceKeys.isSaveRaw();
        previewFps = PreferenceKeys.getFpsMode();
        remosaicOn = PreferenceKeys.isRemosaicOn();

        stampWatermark = PreferenceKeys.isShowWatermarkOn();
        wide169 = PreferenceKeys.getBool(PreferenceKeys.Key.KEY_WIDE169);
        fullDebug = PreferenceKeys.isFullDebugOn();
        // The gain-map pass (linear snapshot, no deferred GL teardown, no in-pipeline resize) runs only when a JPEG carries it.
        ultraHdr = PreferenceKeys.isUltraHdrActive();
        hdrOutput = com.scamera.app.processing.color.HdrOutput.wanted();
        colourSpace = PreferenceKeys.getOutputColourSpace();
        alignMethod = PreferenceKeys.getAlignMethodValue();
        colourMethod = PreferenceKeys.getColorMethodValue();
        peakingColour = PreferenceKeys.getFocusPeakValue();
        themeIndex = PreferenceKeys.getThemeValue();
    }

    /**
     * Whether the shot runs the Ultra HDR gain-map pass (linear snapshot, no deferred GL teardown, no in-pipeline resize):
     * for an Ultra HDR JPEG or for the HDR HEIC / AVIF (P46). Without «HDR в HEIC / AVIF» this is {@link #ultraHdr}.
     */
    public boolean gainMapPass() {
        return ultraHdr || hdrOutput;
    }

    /** The open camera's id back into the preferences. */
    public void saveID() {
        PreferenceKeys.setCameraID(cameraId);
    }
}
