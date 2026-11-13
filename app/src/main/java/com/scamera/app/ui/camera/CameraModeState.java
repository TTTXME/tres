package com.scamera.app.ui.camera;

import com.scamera.app.api.CameraMode;

/**
 * Execute the reConfigureModeViews method based on the CameraMode state
 */
public interface CameraModeState {
    void reConfigureModeViews(CameraMode mode);
}