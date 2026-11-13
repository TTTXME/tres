package com.scamera.app.circularbarlib.api;

import android.app.Activity;
import android.hardware.camera2.CameraCharacteristics;

import com.scamera.app.circularbarlib.control.ManualParamModel;

import java.util.Observer;

/**
 * The manual-controls panel as the camera screen drives it: attached to the open camera's characteristics, following the
 * screen's lifecycle, its values observed through {@link #valueModel}, shown / hidden and reset from the screen.
 */
public interface ManualModeConsole {
    // ── lifecycle ──
    /** Builds the panel's parameters for {@code camera} (again after a lens switch). */
    void attachCamera(Activity activity, CameraCharacteristics camera);
    void resumePanel();
    void pausePanel();
    void destroyPanel();

    // ── the values ──
    /** The manual values the camera reads, and their observers. */
    ManualParamModel valueModel();
    void observeValues(Observer observer);
    void stopObservingValues();
    /** Any value off auto. */
    boolean offAuto();
    /** Every value back to auto (the observers are told). */
    void clearValues();

    // ── the panel on screen ──
    void setPanelShown(boolean visible);
    boolean isPanelShown();
    /** Closes every open knob. */
    void collapseKnobs();
    /** The focus knob is the open one; the focus is set by hand. */
    boolean focusKnobOpen();
    boolean manualFocusOn();

    /**
     * The camera's metered values from the latest preview result, shown by the chips of parameters in auto and used as
     * the ruler's start: ISO, exposure time (ns), focus distance (diopters, NaN while unknown) and the AWB colour
     * temperature (K, 0 while unknown). Call on the main thread.
     */
    void setMeteredValues(int iso, long exposureNs, float focusDiopters, int awbKelvin);

    /** Where the panel's toasts go («ISO: авто», the EV lock); a plain Toast without one. */
    void setMessageSink(java.util.function.Consumer<CharSequence> sink);

    /** «Всё на авто»: every parameter back to auto. */
    void resetAllToAuto();

    /** SIMPLE_UI §1, the viewfinder's sun: the Pro EV's compensation (0 in auto). */
    default float evCompensation() {
        return 0f;
    }

    /** The EV compensation's range {min, max} (0, 0 without one). */
    default float[] evRange() {
        return new float[]{0f, 0f};
    }

    /** EV is locked: ISO and shutter are both manual (the sun hides). */
    default boolean evLocked() {
        return false;
    }

    /** Sets the Pro EV to the nearest of its steps (0: auto); the chips follow. */
    default void setEvCompensation(float ev) {
    }
}
