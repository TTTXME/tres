package com.scamera.app.api;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;

import com.scamera.app.capture.CaptureEvents;
import com.scamera.app.processing.ProcessingEvents;
import com.scamera.app.util.Log;

/**
 * What the camera screen hears from the camera, the capture and the processing: one object takes all three (the camera screen
 * implements it; CaptureController and the processors call it, from any thread unless a method says otherwise).
 */
public abstract class CameraEvents implements CaptureEvents, ProcessingEvents {
    /** The log tag of the implementation's lines. */
    protected String logTag = "CameraEvents";

    protected void debug(String line) {
        Log.d(logTag, line);
    }

    protected void error(String line) {
        Log.e(logTag, line);
    }

    // ── the camera device ──

    /** The camera is about to be opened through {@code manager}. */
    public abstract void cameraOpening(CameraManager manager);

    /** The open camera was closed and opened again (a lens or session change). */
    public abstract void cameraRestarted();

    /**
     * P240 (speed-ux C4): the camera is being brought back after an error ({@code on}), or that recovery ended (a session runs
     * again, or it gave up and said so). The camera screen shows «Камера перезапускается…» meanwhile. Any thread.
     */
    public void onCameraRecovery(boolean on, String why) {}

    /** The open lens changed: its characteristics. */
    public abstract void lensChanged(CameraCharacteristics characteristics);

    /** A camera error the screen reports and recovers from. */
    public abstract void cameraError(Object what);

    /** A camera error the screen cannot recover from: {@code message} is shown. */
    public abstract void cameraFatal(String message);

    /** A saved file the media scanner should index. */
    public abstract void scanMedia(Uri file);
}
