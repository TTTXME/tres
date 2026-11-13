package com.scamera.app.circularbarlib.ui.views.knobview;

/**
 * One stop of a manual parameter's scale (LinearScaleView): its label, its place on the scale and the value the camera
 * gets. {@link #majorTick} marks a labelled stop of the ruler.
 */
public final class KnobItemInfo {
    public final String text;
    public final int tick;
    public final double value;
    public boolean majorTick;

    public KnobItemInfo(String text, int tick, double value) {
        this.text = text;
        this.tick = tick;
        this.value = value;
    }

    @Override
    public String toString() {
        return "stop " + tick + " «" + text + "» = " + value;
    }
}
