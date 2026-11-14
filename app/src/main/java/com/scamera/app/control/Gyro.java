package com.scamera.app.control;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.scamera.app.api.CameraMode;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;

import java.util.ArrayList;

/**
 * The phone's motion for the camera: the gyroscope's angle steps (rad per sample) and the rotation vector's damped yaw /
 * pitch / roll. Outside a shot every step goes into a 1024-sample ring (the ZSL frames take their exposure's window of it)
 * and into the shake meter (the tripod detection); during a series each frame collects its own steps (one {@link GyroBurst}
 * a frame into the shot's list).
 */
public class Gyro {
    private static final String TAG = "Gyroscope";
    /** The shake meter's smoothing: 80 % of the previous value stays. */
    private static final float SHAKE_KEEP = 0.8f;
    private static final float NS_TO_S = 1.0f / 1000000000.0f;
    /** Samples a series frame holds at most (the burst-rate gyro's steps over the longest frame). */
    private static final int SERIES_FRAME_SAMPLES = 65535;
    /** Below this peak shake over the detection window the phone is on a tripod. */
    private static final int TRIPOD_SHAKE = 25;
    private static final int TRIPOD_WINDOW = 600;
    /** The gyro's preview rate (sensor delay, us); 0 (the fastest) during a series. */
    private static final int PREVIEW_DELAY_US = 500;
    private static final int AVERAGE_OF = 10;
    private static final int RING = 1024;

    private final SensorManager sensors;
    private final Sensor gyroscope;
    private final Sensor rotationVector;

    /** The latest gyro sample (rad/s per axis); a copy is kept when the sensors stop. */
    private float[] rates;
    private long previousTs;
    private double meanStepS;
    private int stepsMeasured;

    // the ring of steps outside a series, with each step's start for the exposure windows
    private final Object ringLock = new Object();
    private final GyroBurst ring = new GyroBurst(RING);
    private final long[] ringStepStarts = new long[RING];
    private int ringNext;

    // the series of a shot: the frame being collected, its path and the list the frames go to
    private volatile boolean settingUp;
    private boolean collecting;
    private GyroBurst frame;
    private float framePath;
    private int frameSamples;
    private ArrayList<GyroBurst> seriesFrames;
    private boolean integrating;
    private float sumX, sumY, sumZ;
    /** Frames of the series whose collection has started (the processing waits for all of them). */
    public int capturingNumber;
    private int delayUs = PREVIEW_DELAY_US;

    // the shake meter and the tripod detection over its last TRIPOD_WINDOW values
    private int shakeSmoothed = -1;
    private int tripodWindowPos;
    private long tripodWindowPeak;
    /** The peak shake of the last full detection window. */
    public int tripodShakiness = 1000;

    // the rotation vector's angles (degrees), averaged over the last AVERAGE_OF events (rings: no boxing per event)
    private final float[] rotation = new float[5];
    private final float[] rotationMatrix = new float[9], remapped = new float[9], angles = new float[3];
    private final float[][] angleHistory = new float[3][AVERAGE_OF];
    private final float[] angleSums = new float[3];
    private int historyNext, historyCount;

    private final SensorEventListener gyroListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onGyroSample(event.values, event.timestamp);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // not used
        }
    };

    private final SensorEventListener rotationListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
            System.arraycopy(event.values, 0, rotation, 0, event.values.length);
            onRotation();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // not used
        }
    };

    public Gyro(SensorManager sensorManager) {
        sensors = sensorManager;
        gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    /** One gyro sample (on the sensors' thread): its angle steps go to the series' frame or to the ring and the meter. */
    private void onGyroSample(float[] values, long ts) {
        rates = values;
        float dx = 0f, dy = 0f, dz = 0f;
        if (previousTs != 0) {
            final float dt = (ts - previousTs) * NS_TO_S;
            dx = values[0] * dt;
            dy = values[1] * dt;
            dz = values[2] * dt;
            final double weight = Math.min(1.0, 1.0 / (Math.min(stepsMeasured, 5000) + 1));
            meanStepS = meanStepS * (1.0 - weight) + (ts - previousTs) * NS_TO_S * weight;
            stepsMeasured++;
        }
        if (integrating) {
            sumX += dx;
            sumY += dy;
            sumZ += dz;
        }
        if (collecting && !settingUp) {
            framePath += Math.abs(dx);
            framePath += Math.abs(dy);
            framePath += Math.abs(dz);
            if (frameSamples < frame.angleSteps[0].length) {
                frame.angleSteps[0][frameSamples] = dx;
                frame.angleSteps[1][frameSamples] = dy;
                frame.angleSteps[2][frameSamples] = dz;
                frameSamples++;
            }
        } else {
            synchronized (ringLock) {
                ringNext %= RING;
                ring.angleSteps[0][ringNext] = dx;
                ring.angleSteps[1][ringNext] = dy;
                ring.angleSteps[2][ringNext] = dz;
                ring.sampleTimes[ringNext] = ts;
                ringStepStarts[ringNext] = previousTs;
                ringNext++;
            }
            shakeNow(); // keeps the meter and the tripod detection running
            if (collecting) closeFrame();
        }
        previousTs = ts;
    }

    // ---- registration -----------------------------------------------------------------------------------------------

    /**
     * W1.9: the gyro's rate changes of a shot (unregister + register, ~30-40 ms of sensor HAL calls each) run on this thread
     * instead of the camera thread: the burst rate after the series is submitted, the preview rate after the sequence. One
     * thread, so they keep their order; skipped once the camera screen unregistered the sensors.
     */
    private static final java.util.concurrent.ExecutorService REGISTRATION = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "SCAMERA-gyro-registration");
        thread.setDaemon(true);
        return thread;
    });
    private final Object registrationLock = new Object();
    /** The camera screen registered the sensors (register) and did not unregister them since. */
    private boolean sensorsWanted;
    /**
     * P662: the horizon indicator is shown - the rotation vector (at the sensors' fastest rate) feeds only its angles, so it is
     * registered only then (it ran whenever the camera was open; «Горизонт» is off by default).
     */
    private boolean rotationWanted;
    /** P662: the rotation vector's listener is registered now (tests). */
    private boolean rotationListening;
    /** prepareSeries(..., true) left the burst-rate registration to startBurstRegistration. */
    private volatile boolean burstRegistrationPending;

    public void register() {
        synchronized (registrationLock) {
            sensorsWanted = true;
            listen(delayUs);
        }
    }

    public void unregister() {
        synchronized (registrationLock) {
            sensorsWanted = false;
            stopListening();
        }
    }

    private void listen(int gyroDelayUs) {
        stepsMeasured = 0;
        // P551: delivered on the sensors' own thread, not the main looper (0.6 - 1 kHz of events competed with every UI animation)
        sensors.registerListener(gyroListener, gyroscope, gyroDelayUs, SensorThread.handler());
        if (rotationWanted) listenRotation();
    }

    /** P662: the rotation vector for the horizon; its averages start again before its events do (they come on another thread). */
    private void listenRotation() {
        if (rotationVector == null || rotationListening) return;
        for (float[] history : angleHistory) java.util.Arrays.fill(history, 0f);
        java.util.Arrays.fill(angleSums, 0f);
        historyNext = 0;
        historyCount = 0;
        rotationListening = sensors.registerListener(rotationListener, rotationVector, SensorManager.SENSOR_DELAY_FASTEST,
                SensorThread.handler());
        Log.d(TAG, "rotation vector on (the horizon is shown)");
    }

    private void stopRotation() {
        if (!rotationListening) return;
        rotationListening = false;
        sensors.unregisterListener(rotationListener, rotationVector);
        Log.d(TAG, "rotation vector off");
    }

    private void stopListening() {
        final float[] last = rates;
        if (last != null) rates = last.clone(); // the sensor's own array is reused by the framework
        sensors.unregisterListener(gyroListener, gyroscope);
        stopRotation();
    }

    /**
     * P662: the horizon indicator is shown ({@code true}) or hidden: the rotation vector runs only while it is shown and the
     * camera screen has the sensors registered. The gyroscope (the shot's motion, the shake meter) is not touched.
     */
    public void setRotationWanted(boolean wanted) {
        synchronized (registrationLock) {
            if (rotationWanted == wanted) return;
            rotationWanted = wanted;
            if (!sensorsWanted) return;
            if (wanted) listenRotation();
            else stopRotation();
        }
    }

    /** P662: whether the rotation vector's listener is registered now. */
    boolean rotationListening() {
        synchronized (registrationLock) {
            return rotationListening;
        }
    }

    private void relistenLater(final int gyroDelayUs) {
        REGISTRATION.execute(() -> {
            synchronized (registrationLock) {
                if (!sensorsWanted) return;
                stopListening();
                listen(gyroDelayUs);
            }
        });
    }

    /** W1.9: posts the burst-rate registration left by prepareSeries(..., true); nothing when none is pending. */
    public void startBurstRegistration() {
        if (!burstRegistrationPending) return;
        burstRegistrationPending = false;
        relistenLater(0);
    }

    // ---- a shot's series -------------------------------------------------------------------------------------------

    public void prepareSeries(long[] frameTimesNs, ArrayList<GyroBurst> frames) {
        prepareSeries(frameTimesNs, frames, false);
    }

    /**
     * A series is about to be captured: its frames' motion goes to {@code frames}, the gyro at its fastest rate.
     * deferRegistration (W1.9): the switch to the burst rate is left to {@link #startBurstRegistration} after the submit. The
     * gyro only feeds the logged shakiness, never the pixels.
     */
    public void prepareSeries(long[] frameTimesNs, ArrayList<GyroBurst> frames, boolean deferRegistration) {
        settingUp = true;
        capturingNumber = 0;
        sumX = sumY = sumZ = 0f;
        delayUs = 0;
        Log.d(TAG, "Gyro DelayUs:" + delayUs);
        frame = new GyroBurst(SERIES_FRAME_SAMPLES);
        seriesFrames = frames;
        burstRegistrationPending = deferRegistration;
        if (!deferRegistration) synchronized (registrationLock) {
            stopListening();
            listen(delayUs);
        }
        settingUp = false;
    }

    /** A frame of the series starts: the previous one is closed, this one collects the steps from now. */
    public void seriesFrameStarted() {
        if (frame == null) return; // no series prepared
        if (collecting) closeFrame();
        frameSamples = 0;
        integrating = true;
        framePath = 0;
        collecting = true;
        capturingNumber++;
        com.scamera.app.processing.ImageSaver.framesChanged(); // P680: the series wait counts the frames started
    }

    /**
     * The frame being collected goes to the series: its path squared, its samples, the integrated angles. Only the samples
     * it holds are copied (the frame's buffer has room for 65535 a series: ~1.3 MB copied for every frame before).
     */
    private void closeFrame() {
        if (!collecting) return;
        collecting = false;
        final GyroBurst done = frame;
        done.shakeScore = Math.min(framePath * framePath, Float.MAX_VALUE);
        done.sampleCount = frameSamples;
        done.integratedAngles[0] = -sumX;
        done.integratedAngles[1] = sumY;
        done.integratedAngles[2] = sumZ;
        seriesFrames.add(done.compactCopy());
    }

    /**
     * Fills {@code result} for ZSL frames: each frame's motion from the ring over its exposure window (from its sensor time).
     */
    public void ringFramesMotion(long[] frameTimestamps, long exposureTimeNs, ArrayList<GyroBurst> result,
                                       boolean comparableClock) {
        seriesFrames = result;
        synchronized (ringLock) {
            // W1.0: one line for the ZSL frames instead of one each (OPPO's log flow control dropped the shot's rows).
            final StringBuilder shake = new StringBuilder();
            for (long frameTs : frameTimestamps) {
                final GyroBurst motion = GyroExposureWindow.extract(ringStepStarts, ring, ringNext, frameTs, exposureTimeNs, comparableClock);
                result.add(motion);
                shake.append(' ').append(motion.shakeScore).append('/').append(motion.sampleCount);
            }
            Log.d(TAG, "ZSL gyro: frames=" + frameTimestamps.length + " exposure=" + exposureTimeNs + " comparableClock=" + comparableClock
                    + " shake/samples:" + shake);
        }
    }

    /** P78: gyro shakiness of one ring frame (no state of the series changes); NaN without gyro samples in its exposure window. */
    public float zslShake(long frameTs, long exposureNs, boolean comparableClock) {
        synchronized (ringLock) {
            final GyroBurst motion = GyroExposureWindow.extract(ringStepStarts, ring, ringNext, frameTs, exposureNs, comparableClock);
            return motion.sampleCount > 0 ? motion.shakeScore : Float.NaN;
        }
    }

    /** The series is complete: the frames' shake measured again from their samples, the gyro back to the preview rate. */
    public void seriesDone() {
        integrating = false;
        collecting = false;
        delayUs = PREVIEW_DELAY_US;
        burstRegistrationPending = false;
        // A long exposure naturally contains more samples. Never truncate it
        // to the burst average or interpolate unknown motion from other frames.
        final StringBuilder shake = new StringBuilder();
        for (GyroBurst done : seriesFrames) {
            done.recomputeShake();
            shake.append(' ').append(done.shakeScore).append('/').append(done.sampleCount);
        }
        // W1.0: one line instead of one per frame.
        Log.d(TAG, "GyroBurst Shakiness/sampleCount (" + seriesFrames.size() + "):" + shake);
        // W1.9: back to the preview rate off the shot's path (it was 27-42 ms before "complete matched").
        relistenLater(PREVIEW_DELAY_US);
    }

    // ---- shake and tripod ------------------------------------------------------------------------------------------

    /**
     * The shake now: the sum of the last sample's rates (mrad/s), smoothed; each call also advances the tripod detection
     * (its peak over {@link #TRIPOD_WINDOW} calls).
     */
    public int shakeNow() {
        final float[] last = rates;
        if (last == null) return 0;
        int raw = 0;
        for (float rate : last) raw += Math.abs((int) (rate * 1000));
        if (shakeSmoothed == -1) shakeSmoothed = raw;
        final int smoothed = (int) (raw * (1.0f - SHAKE_KEEP) + shakeSmoothed * SHAKE_KEEP);
        shakeSmoothed = smoothed;
        tripodWindowPos = (tripodWindowPos + 1) % TRIPOD_WINDOW;
        if (tripodWindowPos == TRIPOD_WINDOW - 1) {
            tripodShakiness = (int) tripodWindowPeak;
            tripodWindowPeak = 0;
        } else {
            tripodWindowPeak = Math.max(smoothed, tripodWindowPeak);
        }
        return smoothed;
    }

    /** The smoothed shake as the last sample left it (-1 before the first one). */
    public int getFilteredShakiness() {
        return shakeSmoothed;
    }

    /** On a tripod in a photo mode (P25: the detection moved from the retired Night mode to Photo). */
    public boolean getTripod() {
        return tripodShakiness < TRIPOD_SHAKE && CameraMode.isStill(ScameraApp.getSettings().mode);
    }

    // ---- the horizon's angles --------------------------------------------------------------------------------------

    private void onRotation() {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotation);
        SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped);
        SensorManager.getOrientation(remapped, angles);
        final int slot = historyNext;
        final boolean full = historyCount == AVERAGE_OF;
        for (int axis = 0; axis < 3; axis++) {
            final float degrees = (float) Math.toDegrees(angles[axis]);
            final float dropped = angleHistory[axis][slot];
            angleHistory[axis][slot] = degrees;
            angleSums[axis] += degrees;
            if (full) angleSums[axis] -= dropped; // the oldest value leaves after the newest came in, as before
        }
        historyNext = (slot + 1) % AVERAGE_OF;
        if (!full) historyCount++;
    }

    private float average(int axis) {
        final int count = historyCount;
        return count == 0 ? 0f : angleSums[axis] / count;
    }

    /** The damped yaw (azimuth) in degrees. */
    public float getYaw() {
        return average(0);
    }

    /** The damped pitch (tilt to the sky / the ground) in degrees. */
    public float getPitch() {
        return average(1);
    }

    /** The damped roll (the horizon's turn) in degrees. */
    public float getRoll() {
        return average(2);
    }
}
