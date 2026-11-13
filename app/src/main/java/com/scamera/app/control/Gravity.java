package com.scamera.app.control;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * The phone's turn from the gravity sensor, for the shot's orientation: 0 / 90 / 180 / 270 on the scale where 90 is upright
 * portrait (flat on its back, and before any reading, counts as upright).
 */
public class Gravity {
    private final SensorManager sensors;
    private final Sensor gravity;
    /** The newest reading (a copy: read on the main thread while the sensors' thread delivers, P551). */
    private volatile float[] last;
    /**
     * P662: the copies rotate through these instead of a new array per event (SENSOR_DELAY_FASTEST: hundreds a second): the one
     * published in {@link #last} is never written again until two more readings came (a reader holds it for a few reads).
     */
    private final float[][] copies = new float[3][];
    private int nextCopy;
    private final SensorEventListener listener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent reading) {
            last = copyOf(reading.values);
        }

        @Override
        public void onAccuracyChanged(Sensor which, int accuracy) {
            // the readings' accuracy does not change the turn
        }
    };

    public Gravity(SensorManager manager) {
        sensors = manager;
        gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    }

    /** P662: {@code values} into the next of {@link #copies} (sensors' thread only). */
    float[] copyOf(float[] values) {
        float[] c = copies[nextCopy];
        if (c == null || c.length != values.length) copies[nextCopy] = c = new float[values.length];
        System.arraycopy(values, 0, c, 0, values.length);
        nextCopy = (nextCopy + 1) % copies.length;
        return c;
    }

    public void register() {
        sensors.registerListener(listener, gravity, SensorManager.SENSOR_DELAY_FASTEST, SensorThread.handler()); // P551
    }

    /** No more readings; the last one stays for the next shot's orientation. */
    public void unregister() {
        sensors.unregisterListener(listener, gravity);
    }

    public int getRotation() {
        return turnOf(last);
    }

    /** The turn of a gravity vector (m/s²): pointing at the ground or none: upright; else the axis that carries most of it. */
    static int turnOf(float[] g) {
        if (g == null || g[2] > 9f) return 90;
        if (Math.abs(g[0]) > Math.abs(g[1])) return g[0] > 0f ? 0 : 180;
        return g[1] > 1.5f ? 90 : 270;
    }

    /** The sensor's image turned for the phone's present turn (degrees, clockwise). */
    public int getCameraRotation(int sensorOrientation) {
        return (sensorOrientation + getRotation() + 270) % 360;
    }
}
