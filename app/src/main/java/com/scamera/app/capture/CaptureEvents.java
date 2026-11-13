package com.scamera.app.capture;

import android.hardware.camera2.CaptureResult;

/** The capture's progress as the camera screen follows it (shutter animation, burst counter, preview metering). */
public interface CaptureEvents {
    /** The burst about to be taken has {@code frames} frames. */
    void burstSize(int frames);

    /** The shutter was pressed and the still capture began. */
    void stillStarted(Object what);

    /** The burst's requests are built and about to be sent. */
    void burstPrepared(Object what);

    /** A frame of the burst began exposing. */
    void frameStarted(Object what);

    /** A frame of the burst was captured. */
    void frameCaptured(Object what);

    /** The burst is over (every frame captured, or the sequence aborted). */
    void burstCompleted(Object what);

    /** A preview frame's result (metering, focus, the HUD). */
    void previewResult(CaptureResult result);
}
