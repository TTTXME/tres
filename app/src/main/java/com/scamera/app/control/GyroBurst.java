package com.scamera.app.control;

/**
 * The gyro's record of one frame (or of the ring outside a shot): the angle step of every sample per axis (rad), the
 * samples' times, the integrated angles and the frame's shake (its angular path squared).
 */
public class GyroBurst {
    public float shakiness;
    /** Samples recorded (the arrays may hold more room). */
    public int samples;
    /** Angle steps per axis: [axis][sample]. */
    public float[][] movementss;
    public long[] timestampss;
    public float[] integrated = new float[3];
    private int capacity;

    public GyroBurst(int maxSamples) {
        capacity = maxSamples;
        movementss = new float[3][maxSamples];
        timestampss = new long[maxSamples];
    }

    /** Squared angular path, consistent across ZSL and post-shutter captures; a non-finite step leaves no samples. */
    public void recalculateShakiness() {
        double path = 0;
        for (int i = 0; i < samples; i++) {
            for (float[] axis : movementss) {
                final float step = axis[i];
                if (!Float.isFinite(step)) {
                    samples = 0;
                    shakiness = 0;
                    return;
                }
                path += Math.abs(step);
            }
        }
        shakiness = (float) Math.min(path * path, Float.MAX_VALUE);
    }

    /** A full copy (the same room). */
    @Override
    public GyroBurst clone() {
        return copy(capacity);
    }

    /** A copy with room for its samples only (a finished frame grows no more). */
    public GyroBurst compactCopy() {
        return copy(samples);
    }

    private GyroBurst copy(int room) {
        final GyroBurst out = new GyroBurst(0);
        out.capacity = room;
        out.movementss = new float[][]{java.util.Arrays.copyOf(movementss[0], room),
                java.util.Arrays.copyOf(movementss[1], room), java.util.Arrays.copyOf(movementss[2], room)};
        out.timestampss = java.util.Arrays.copyOf(timestampss, room);
        out.integrated = integrated.clone();
        out.shakiness = shakiness;
        out.samples = samples;
        return out;
    }
}
