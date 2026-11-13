package com.scamera.app.control;

/** Integrates gyro intervals overlapping the first-row exposure of a ZSL frame. */
public final class GyroExposureWindow {
    private GyroExposureWindow() {}

    public static GyroBurst extract(long[] starts, GyroBurst ring, int next,
                                    long frameStart, long exposureNs, boolean comparableClock) {
        int capacity = ring.sampleTimes.length;
        GyroBurst out = new GyroBurst(capacity);
        if (!comparableClock || frameStart <= 0 || exposureNs <= 0
                || exposureNs > Long.MAX_VALUE-frameStart) return out;
        long frameEnd = frameStart + exposureNs;
        long coveredUntil = frameStart;
        float[] integrated = new float[3];
        // next is the oldest slot after wrap; unused slots have zero timestamps.
        for (int j = 0; j < capacity; j++) {
            int i = (next % capacity + j) % capacity;
            long start = starts[i], end = ring.sampleTimes[i];
            if (start <= 0 || end <= start || end-start > 100_000_000L) continue;
            long overlapStart = Math.max(start, frameStart);
            long overlapEnd = Math.min(end, frameEnd);
            if (overlapEnd <= overlapStart) continue;
            // A hole or out-of-order overlap is unknown motion, not a still frame.
            if (overlapStart != coveredUntil) return new GyroBurst(capacity);
            float fraction = (float)((double)(overlapEnd-overlapStart)/(end-start));
            boolean finite = true;
            for (int axis=0;axis<3;axis++) finite &= Float.isFinite(ring.angleSteps[axis][i]);
            if (!finite) return new GyroBurst(capacity);
            int k = out.sampleCount++;
            for (int axis=0;axis<3;axis++) {
                float movement = ring.angleSteps[axis][i]*fraction;
                out.angleSteps[axis][k] = movement;
                integrated[axis] += movement;
            }
            out.sampleTimes[k] = overlapEnd;
            coveredUntil = overlapEnd;
        }
        if (coveredUntil != frameEnd) return new GyroBurst(capacity);
        out.integratedAngles[0] = -integrated[0];
        out.integratedAngles[1] = integrated[1];
        out.integratedAngles[2] = integrated[2];
        out.recomputeShake();
        return out;
    }
}
