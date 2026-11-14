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

    /**
     * P705: every (un)registration of the camera's motion sensors runs on this one thread, in the order asked: the gyroscope's
     * rate changes of a shot (W1.9) and the camera screen's register at resume and unregister at pause - the latter ran on the
     * main thread inside onPause (a sensor HAL call each, milliseconds to tens of ms on some phones, while the screen went).
     */
    private static final java.util.concurrent.ExecutorService REGISTRATION = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "SCAMERA-sensor-registration");
        thread.setDaemon(true);
        return thread;
    });

    /** P705: {@code job} on the registration thread, after every registration asked for before it. */
    public static void register(Runnable job) {
        REGISTRATION.execute(job);
    }

    public static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread("ScamSensors", Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }
}
