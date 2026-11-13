package com.scamera.app.control;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

/**
 * P551: one thread for the viewfinder's motion sensors (gyroscope, rotation vector, gravity). Registered without a Handler they were
 * delivered on the main looper - up to 1 kHz during a burst - competing with every UI animation.
 */
public final class SensorThread {
    private static Handler handler;

    private SensorThread() {}

    public static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread("ScamSensors", Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }
}
