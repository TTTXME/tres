package com.scamera.app.circularbarlib.model;

import java.util.Observable;

/**
 * What the manual panel shows ({@link com.scamera.app.circularbarlib.ui.ViewObserver} observes it): the chosen chip, the
 * panel's visibility, and a note when a parameter's label changed (the observer reads the labels from the models).
 */
public class ManualModeModel extends Observable {
    private boolean panelShown;
    private int chosenChip;

    private void changed(ManualModelFields what) {
        setChanged();
        super.notifyObservers(what);
    }

    @Override
    public void notifyObservers(Object what) {
        setChanged();
        super.notifyObservers(what);
    }

    public int getSelectedTextViewId() {
        return chosenChip;
    }

    /** The chip whose ruler is open, -1 for none. */
    public void setCheckedTextViewId(int chipId) {
        chosenChip = chipId;
        changed(ManualModelFields.SELECTED_TV);
    }

    public boolean isManualPanelVisible() {
        return panelShown;
    }

    public void setManualPanelVisible(boolean shown) {
        panelShown = shown;
        changed(ManualModelFields.PANEL_VISIBILITY);
    }

    public void setIsoText(String label) { changed(ManualModelFields.ISO_TEXT); }

    public void setExposureText(String label) { changed(ManualModelFields.EXP_TEXT); }

    public void setEvText(String label) { changed(ManualModelFields.EV_TEXT); }

    public void setFocusText(String label) { changed(ManualModelFields.FOCUS_TEXT); }

    public void setWbText(String label) { changed(ManualModelFields.WB_TEXT); }

    public enum ManualModelFields {
        WB_TEXT, FOCUS_TEXT, EXP_TEXT, ISO_TEXT, EV_TEXT, PANEL_VISIBILITY, SELECTED_TV
    }
}
