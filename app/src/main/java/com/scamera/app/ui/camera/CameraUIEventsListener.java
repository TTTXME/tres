package com.scamera.app.ui.camera;

import android.view.View;

import com.scamera.app.api.CameraMode;

interface CameraUIEventsListener {
    void onClick(View v);

    void modeChosen(CameraMode mode);

    void onPause();
}