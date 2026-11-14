package com.scamera.app.ui.camera;

import android.content.Context;
import android.provider.Settings;
import android.view.OrientationEventListener;

/**
 * The phone's orientation in quarters (1 upright, 2 left side up, 3 upside down, 4 right side up) while the system's
 * auto-rotate is on. A quarter is entered inside a 40 degree band round its angle (upright: from 340 on, or below 20 when not
 * upright already); between the bands nothing changes.
 */
public abstract class CustomOrientationEventListener extends OrientationEventListener {
    static final int UPRIGHT = 1, LEFT_UP = 2, UPSIDE_DOWN = 3, RIGHT_UP = 4;

    private final Context context;
    private int quarter;
    /** The Surface rotation last reported (ORIENTATION_UNKNOWN between the bands). */
    private int reported = ORIENTATION_UNKNOWN;

    public CustomOrientationEventListener(Context context) {
        super(context);
        this.context = context;
    }

    /** The quarter {@code orientation} (degrees) enters from {@code current}; 0 when it enters none. */
    static int quarterFor(int orientation, int current) {
        if (orientation >= 340 || orientation < 20 && current != UPRIGHT) return UPRIGHT;
        if (orientation >= 70 && orientation < 110 && current != LEFT_UP) return LEFT_UP;
        if (orientation >= 160 && orientation < 200 && current != UPSIDE_DOWN) return UPSIDE_DOWN;
        if (orientation >= 250 && orientation < 290 && current != RIGHT_UP) return RIGHT_UP;
        return 0;
    }

    /**
     * P705: whether a reading would change anything - another quarter entered, or another Surface rotation to report. Only then is
     * the auto-rotate setting asked (it was read for every reading, several a second on the main thread).
     */
    static boolean changes(int entered, int quarter, int reported, int surface) {
        return (entered != 0 && entered != quarter) || reported != surface;
    }

    @Override
    public void onOrientationChanged(int orientation) {
        final int entered = quarterFor(orientation, quarter);
        final int surface = entered != 0 ? entered - 1 : ORIENTATION_UNKNOWN; // Surface.ROTATION_0 .. ROTATION_270
        if (!changes(entered, quarter, reported, surface)) return;
        // auto-rotate off: the screen keeps its orientation, so do the controls
        if (Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0) return;
        if (entered != 0) quarter = entered;
        if (reported == surface || orientation == ORIENTATION_UNKNOWN) return;
        reported = surface;
        if (surface != ORIENTATION_UNKNOWN) onSimpleOrientationChanged(quarter);
    }

    /** The phone turned to another quarter (UPRIGHT .. RIGHT_UP). */
    public abstract void onSimpleOrientationChanged(int orientation);
}
