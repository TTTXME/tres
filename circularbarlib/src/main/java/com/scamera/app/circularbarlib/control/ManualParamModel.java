package com.scamera.app.circularbarlib.control;

import java.util.Observable;

/**
 * The manual values the camera uses (focus in diopters, EV as a compensation index, exposure in ns, ISO, white balance in
 * kelvin; each at its *_AUTO value while automatic). Every change tells the observers which value changed (the ID_* names).
 */
public class ManualParamModel extends Observable {
    /** The values that mean «auto»: shutter, EV index and ISO 0; focus -1 diopters (white balance: 0 K). */
    public static final double SHUTTER_AUTO_NS = 0, EV_AUTO_STEP = 0, ISO_AUTO_VALUE = 0, FOCUS_AUTO_DIOPTERS = -1.0d;
    /** What an observer is told changed (the texts are the observers' contract). */
    public static final String CHANGED_FOCUS = "focus", CHANGED_EV = "ev", CHANGED_SHUTTER = "shutter", CHANGED_ISO = "iso",
            CHANGED_WB = "wb";
    /** Sent by {@link #reset}: everything went back to auto. */
    public static final String ALL_AUTO = "panel_invisibility";

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

    public double focusValue() { return focus; }

    public void setFocusValue(double diopters) { focus = diopters; changed(CHANGED_FOCUS); }

    public double evIndex() { return ev; }

    public void setEvIndex(double index) { ev = index; changed(CHANGED_EV); }

    public double shutterValueNs() { return exposureNs; }

    public void setShutterValueNs(double ns) { exposureNs = ns; changed(CHANGED_SHUTTER); }

    public double isoSetting() { return iso; }

    public void setIsoSetting(double value) { iso = value; changed(CHANGED_ISO); }

    public int kelvinSetting() { return kelvin; }

    public void setKelvinSetting(int value) { kelvin = value; changed(CHANGED_WB); }

    /** Any value off auto. */
    public boolean offAuto() {
        boolean allAuto = exposureNs == SHUTTER_AUTO_NS && focus == FOCUS_AUTO_DIOPTERS && iso == ISO_AUTO_VALUE && ev == EV_AUTO_STEP && kelvin == 0;
        return !allAuto;
    }

    /** Everything back to auto, observers told once. */
    public void reset() {
        focus = FOCUS_AUTO_DIOPTERS;
        ev = EV_AUTO_STEP;
        exposureNs = SHUTTER_AUTO_NS;
        iso = ISO_AUTO_VALUE;
        kelvin = 0;
        changed(ALL_AUTO);
    }
}
