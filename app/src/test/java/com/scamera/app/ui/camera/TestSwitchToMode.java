package com.scamera.app.ui.camera;

import com.scamera.app.api.CameraMode;

import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in of the camera screen's mode switch (CameraUIViewImpl.switchToMode): each mode has its {@link CameraModeState},
 * the new state reconfigures the views for the mode; here the "views" are a log of what was configured.
 */
class TestSwitchToMode {
    final List<String> configured = new ArrayList<>();
    CameraModeState currentState = new PhotoMotionModeState();

    void switchToMode(CameraMode mode) {
        currentState = stateFor(mode, currentState);
        currentState.reConfigureModeViews(mode);
    }

    /** The state of a mode; a mode without its own (RAWVIDEO) keeps the current one. */
    CameraModeState stateFor(CameraMode mode, CameraModeState current) {
        switch (mode) {
            case VIDEO: return new VideoModeState();
            case UNLIMITED: return new UnlimitedModeState();
            case NIGHT: return new NightModeState();
            case PHOTO:
            case MOTION: return new PhotoMotionModeState();
            default: return current;
        }
    }

    private abstract class LoggingState implements CameraModeState {
        @Override
        public void reConfigureModeViews(CameraMode mode) {
            configured.add(getClass().getSimpleName() + ":" + mode);
        }
    }

    class VideoModeState extends LoggingState {
    }

    class UnlimitedModeState extends LoggingState {
    }

    class PhotoMotionModeState extends LoggingState {
    }

    class NightModeState extends LoggingState {
    }
}
