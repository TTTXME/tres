package com.scamera.app.circularbarlib.model;

import com.scamera.app.circularbarlib.control.models.ManualModel;

import java.util.Observable;

/** The ruler card's state ({@link com.scamera.app.circularbarlib.ui.ViewObserver} observes it): its parameter and visibility. */
public class KnobModel extends Observable {
    private boolean shown;
    private ManualModel<?> parameter;

    private void changed(KnobModelFields what) {
        setChanged();
        super.notifyObservers(what);
    }

    @Override
    public void notifyObservers(Object what) {
        setChanged();
        super.notifyObservers(what);
    }

    public ManualModel<?> getManualModel() {
        return parameter;
    }

    /** The parameter the ruler shows, null for none. */
    public void setManualModel(ManualModel<?> model) {
        parameter = model;
        changed(KnobModelFields.MANUAL_MODEL);
    }

    public boolean isKnobVisible() {
        return shown;
    }

    public void setKnobVisible(boolean visible) {
        shown = visible;
        changed(KnobModelFields.VISIBILITY);
    }

    public enum KnobModelFields {
        MANUAL_MODEL, VISIBILITY
    }
}
