package com.scamera.app.ui.camera;

import com.scamera.app.api.CameraMode;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** The state pattern of the mode switch: each mode gets its state, which configures the views for that mode. */
public class TestSwitchToModeTest {
    private TestSwitchToMode screen;

    @Before
    public void setup() {
        screen = new TestSwitchToMode();
    }

    private void expect(CameraMode mode, Class<?> state) {
        screen.switchToMode(mode);
        assertEquals(state, screen.currentState.getClass());
        assertEquals(state.getSimpleName() + ":" + mode, screen.configured.get(screen.configured.size() - 1));
    }

    @Test
    public void testSwitchToMode_PhotoMode() {
        expect(CameraMode.PHOTO, TestSwitchToMode.PhotoMotionModeState.class);
        expect(CameraMode.MOTION, TestSwitchToMode.PhotoMotionModeState.class);
    }

    @Test
    public void testSwitchToMode_VideoMode() {
        expect(CameraMode.VIDEO, TestSwitchToMode.VideoModeState.class);
    }

    @Test
    public void testSwitchToMode_UnlimitedMode() {
        expect(CameraMode.UNLIMITED, TestSwitchToMode.UnlimitedModeState.class);
    }

    @Test
    public void testSwitchToMode_NightMode() {
        expect(CameraMode.NIGHT, TestSwitchToMode.NightModeState.class);
    }

    @Test
    public void aModeWithoutAStateKeepsTheCurrentOne() {
        final CameraModeState before = screen.currentState;
        screen.switchToMode(CameraMode.RAWVIDEO);
        assertSame(before, screen.currentState);
    }
}
