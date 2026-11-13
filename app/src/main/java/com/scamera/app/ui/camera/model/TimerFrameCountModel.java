package com.scamera.app.ui.camera.model;

import androidx.databinding.BaseObservable;
import androidx.databinding.Bindable;

/** The series' two counters on screen (camera_shutter_row / camera_viewfinder): frames left and the time left. */
public class TimerFrameCountModel extends BaseObservable {
    private String framesLeft, timeLeft;

    @Bindable
    public String getFrameCount() {
        return framesLeft;
    }

    public void setFrameCount(String text) {
        framesLeft = text;
        notifyChange();
    }

    @Bindable
    public String getTimerCount() {
        return timeLeft;
    }

    public void setTimerCount(String text) {
        timeLeft = text;
        notifyChange();
    }
}
