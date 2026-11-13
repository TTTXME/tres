package com.scamera.app.circularbarlib.ui.views.knobview;

/**
 * One stop of a manual parameter's scale (LinearScaleView): its label, its place on the scale and the value the camera
 * gets. {@link #majorTick} marks a labelled stop of the ruler.
 */
public final class KnobItemInfo {
    /** What the stop shows, its place on the scale and what the camera gets there. */
    public final String label;
    public final int position;
    public final double cameraValue;
    public boolean majorTick;

    public KnobItemInfo(String label, int position, double cameraValue) {
        this.label = label;
        this.position = position;
        this.cameraValue = cameraValue;
    }

    @Override
    public String toString() {
        return "stop " + position + " «" + label + "» = " + cameraValue;
    }
}
