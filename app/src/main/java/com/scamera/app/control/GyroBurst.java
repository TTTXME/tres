package com.scamera.app.control;

/**
 * The gyro's record of one frame (or of the ring outside a shot): the angle step of every sample per axis (rad), the
 * samples' times, the integrated angles and the frame's shake (its angular path squared).
 */
public class GyroBurst {
    /** Angle steps per axis, [axis][sample] (rad), and each sample's time (ns). */
    public float[][] angleSteps;
    public long[] sampleTimes;
    /** Samples recorded (the arrays may hold more room), and the room they have. */
    public int sampleCount;
    private int capacity;
    /** The frame's shake: its angular path squared ({@link #recomputeShake}). */
    public float shakeScore;
    /** The angles integrated over the samples, per axis. */
    public float[] integratedAngles = new float[3];

    public GyroBurst(int maxSamples) {
        capacity = maxSamples;
        angleSteps = new float[3][maxSamples];
        sampleTimes = new long[maxSamples];
    }

    /** Squared angular path, consistent across ZSL and post-shutter captures; a non-finite step leaves no samples. */
    public void recomputeShake() {
        double path = 0;
        for (int i = 0; i < sampleCount; i++) {
            for (float[] axis : angleSteps) {
                final float step = axis[i];
                if (!Float.isFinite(step)) {
                    sampleCount = 0;
                    shakeScore = 0;
                    return;
                }
                path += Math.abs(step);
            }
        }
        shakeScore = (float) Math.min(path * path, Float.MAX_VALUE);
    }

    /** A full copy (the same room). */
    @Override
    public GyroBurst clone() {
        return copy(capacity);
    }

    /** A copy with room for its samples only (a finished frame grows no more). */
    public GyroBurst compactCopy() {
        return copy(sampleCount);
    }

    private GyroBurst copy(int room) {
        final GyroBurst out = new GyroBurst(0);
        out.capacity = room;
        out.angleSteps = new float[][]{java.util.Arrays.copyOf(angleSteps[0], room),
                java.util.Arrays.copyOf(angleSteps[1], room), java.util.Arrays.copyOf(angleSteps[2], room)};
        out.sampleTimes = java.util.Arrays.copyOf(sampleTimes, room);
        out.sampleCount = sampleCount;
        out.shakeScore = shakeScore;
        out.integratedAngles = integratedAngles.clone();
        return out;
    }
}
