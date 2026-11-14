package com.scamera.app.ui.camera;

import org.junit.Test;

import static com.scamera.app.ui.camera.CustomOrientationEventListener.LEFT_UP;
import static com.scamera.app.ui.camera.CustomOrientationEventListener.RIGHT_UP;
import static com.scamera.app.ui.camera.CustomOrientationEventListener.UPRIGHT;
import static com.scamera.app.ui.camera.CustomOrientationEventListener.UPSIDE_DOWN;
import static com.scamera.app.ui.camera.CustomOrientationEventListener.quarterFor;
import static org.junit.Assert.assertEquals;

/** The quarter bands of the camera screen's orientation listener. */
public class CustomOrientationEventListenerTest {

    @Test
    public void eachQuarterIsEnteredInsideItsFortyDegreeBand() {
        assertEquals(UPRIGHT, quarterFor(0, RIGHT_UP));
        assertEquals(UPRIGHT, quarterFor(19, LEFT_UP));
        assertEquals(UPRIGHT, quarterFor(340, LEFT_UP));
        assertEquals(LEFT_UP, quarterFor(70, UPRIGHT));
        assertEquals(LEFT_UP, quarterFor(109, UPRIGHT));
        assertEquals(UPSIDE_DOWN, quarterFor(160, UPRIGHT));
        assertEquals(UPSIDE_DOWN, quarterFor(199, LEFT_UP));
        assertEquals(RIGHT_UP, quarterFor(250, UPRIGHT));
        assertEquals(RIGHT_UP, quarterFor(289, UPSIDE_DOWN));
    }

    @Test
    public void betweenTheBandsNothingChanges() {
        for (int angle : new int[]{20, 45, 69, 110, 135, 159, 200, 225, 249, 290, 315, 339})
            assertEquals("angle " + angle, 0, quarterFor(angle, UPRIGHT));
    }

    @Test
    public void theQuarterAlreadyTakenIsNotEnteredAgain() {
        assertEquals(0, quarterFor(90, LEFT_UP));
        assertEquals(0, quarterFor(180, UPSIDE_DOWN));
        assertEquals(0, quarterFor(270, RIGHT_UP));
        assertEquals(0, quarterFor(10, UPRIGHT));
        // from 340 on the upright quarter is always the answer (the listener reports it once: the Surface rotation is unchanged)
        assertEquals(UPRIGHT, quarterFor(350, UPRIGHT));
    }

    @Test
    public void theAutoRotateSettingIsAskedOnlyWhenAReadingChangesSomething() {
        // upright and reported upright (Surface.ROTATION_0): a reading at 350 enters the same quarter - nothing to ask
        assertEquals(false, CustomOrientationEventListener.changes(UPRIGHT, UPRIGHT, 0, 0));
        // between the bands after an unknown report: nothing either
        assertEquals(false, CustomOrientationEventListener.changes(0, UPRIGHT, -1, -1));
        // another quarter, or a report that moves (into or out of the bands)
        assertEquals(true, CustomOrientationEventListener.changes(LEFT_UP, UPRIGHT, 0, 1));
        assertEquals(true, CustomOrientationEventListener.changes(0, UPRIGHT, 0, -1));
        assertEquals(true, CustomOrientationEventListener.changes(UPRIGHT, UPRIGHT, -1, 0));
    }
}
