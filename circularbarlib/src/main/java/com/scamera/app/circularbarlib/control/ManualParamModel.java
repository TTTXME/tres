package com.scamera.app.circularbarlib.control;

import java.util.Observable;

/**
 * The manual values the camera uses (focus in diopters, EV as a compensation index, exposure in ns, ISO, white balance in
 * kelvin; each at its *_AUTO value while automatic). Every change tells the observers which value changed (the ID_* names).
 */
public class ManualParamModel extends Observable {
    public static final double EXPOSURE_AUTO = 0;
    public static final double EV_AUTO = 0;
    public static final double ISO_AUTO = 0;
    public static final double FOCUS_AUTO = -1.0d;
    public static final String ID_FOCUS = "focus";
    public static final String ID_EV = "ev";
    public static final String ID_SHUTTER = "shutter";
    public static final String ID_ISO = "iso";
    public static final String ID_WB = "wb";
    /** Sent by {@link #reset}: everything went back to auto. */
    public static final String PANEL_INVISIBILITY = "panel_invisibility";

    private double focus, ev, exposureNs, iso;
    private int kelvin;

    private void changed(String what) {
        setChanged();
        super.notifyObservers(what);
    }

    @Override
    public void notifyObservers(Object what) {
        setChanged();
        super.notifyObservers(what);
    }

    public double getCurrentFocusValue() { return focus; }

    public void setCurrentFocusValue(double diopters) { focus = diopters; changed(ID_FOCUS); }

    public double getCurrentEvValue() { return ev; }

    public void setCurrentEvValue(double index) { ev = index; changed(ID_EV); }

    public double getCurrentExposureValue() { return exposureNs; }

    public void setCurrentExposureValue(double ns) { exposureNs = ns; changed(ID_SHUTTER); }

    public double getCurrentISOValue() { return iso; }

    public void setCurrentISOValue(double value) { iso = value; changed(ID_ISO); }

    public int getWhiteBalanceKelvin() { return kelvin; }

    public void setWhiteBalanceKelvin(int value) { kelvin = value; changed(ID_WB); }

    /** Any value off auto. */
    public boolean isManualMode() {
        boolean allAuto = exposureNs == EXPOSURE_AUTO && focus == FOCUS_AUTO && iso == ISO_AUTO && ev == EV_AUTO && kelvin == 0;
        return !allAuto;
    }

    /** Everything back to auto, observers told once. */
    public void reset() {
        focus = FOCUS_AUTO;
        ev = EV_AUTO;
        exposureNs = EXPOSURE_AUTO;
        iso = ISO_AUTO;
        kelvin = 0;
        changed(PANEL_INVISIBILITY);
    }
}
