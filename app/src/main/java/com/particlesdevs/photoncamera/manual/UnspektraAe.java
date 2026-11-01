package com.particlesdevs.photoncamera.manual;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

// Adapted from Unspektrawesome 1.2.4 / 1.2.7 by JohnTheFarm3r:
// https://github.com/JohnTheFarm3r/Unspektrawesome-Releases
public final class UnspektraAe {
    public enum Mode {
        AUTO(false, false), ISO_PRIORITY(true, false),
        SHUTTER_PRIORITY(false, true), MANUAL(true, true);

        final boolean fixedIso, fixedShutter;

        Mode(boolean fixedIso, boolean fixedShutter) {
            this.fixedIso = fixedIso;
            this.fixedShutter = fixedShutter;
        }
    }

    public static final class Exposure {
        public final int iso;
        public final long timeNs;

        public Exposure(int iso, long timeNs) {
            if (iso <= 0 || timeNs <= 0) throw new IllegalArgumentException("Invalid exposure");
            this.iso = iso;
            this.timeNs = timeNs;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Exposure)) return false;
            Exposure e = (Exposure) other;
            return iso == e.iso && timeNs == e.timeNs;
        }

        @Override public int hashCode() {
            return 31 * iso + Long.hashCode(timeNs);
        }
    }

    public static final class Bounds {
        public final int minIso, maxIso;
        public final long minTimeNs, maxTimeNs;

        public Bounds(int minIso, int maxIso, long minTimeNs, long maxTimeNs) {
            if (minIso <= 0 || maxIso < minIso || minTimeNs <= 0 || maxTimeNs < minTimeNs)
                throw new IllegalArgumentException("Invalid exposure bounds");
            this.minIso = minIso;
            this.maxIso = maxIso;
            this.minTimeNs = minTimeNs;
            this.maxTimeNs = maxTimeNs;
        }

        public Exposure clamp(Exposure e) {
            return new Exposure(Math.max(minIso, Math.min(maxIso, e.iso)),
                    Math.max(minTimeNs, Math.min(maxTimeNs, e.timeNs)));
        }
    }

    private static final class Target {
        final long timestampNs;
        final double value;

        Target(long timestampNs, double value) {
            this.timestampNs = timestampNs;
            this.value = value;
        }
    }

    private Mode mode = Mode.AUTO;
    private Exposure base = new Exposure(1, 1), last = base;
    private long generation, meterRevision, lastTimestampNs;
    private double logIso, logTime, errorEv;
    private Double filteredTarget;
    private boolean startup = true, limited;
    private long startupDeadlineNs;
    private final ArrayDeque<Target> history = new ArrayDeque<>();

    public synchronized long reset(Mode mode, Exposure exposure) {
        this.mode = Objects.requireNonNull(mode);
        base = last = Objects.requireNonNull(exposure);
        lastTimestampNs = 0;
        logIso = log2(exposure.iso);
        logTime = log2(exposure.timeNs);
        resetMeter();
        return ++generation;
    }

    public synchronized void resetMeter() {
        meterRevision++;
        filteredTarget = null;
        history.clear();
        startup = true;
        startupDeadlineNs = 0;
        errorEv = 0;
        limited = false;
    }

    public synchronized long generation() { return generation; }
    public synchronized long meterRevision() { return meterRevision; }
    public synchronized double errorEv() { return errorEv; }
    public synchronized boolean limited() { return limited; }

    public synchronized Exposure update(double meterEv, Exposure measured,
            long sensorTimestampNs, long sampleGeneration, long sampleRevision,
            Bounds bounds, double compensationEv, float isoBiasStops, int preferredIso,
            Float flickerHz, int actualIso, boolean suppressTinyChanges) {
        if (mode == Mode.MANUAL || sampleGeneration != generation || sampleRevision != meterRevision
                || sensorTimestampNs <= lastTimestampNs || actualIso <= 0 || preferredIso <= 0
                || !Double.isFinite(meterEv) || !Double.isFinite(compensationEv)) return null;
        long previous = lastTimestampNs;
        if (previous > 0 && sensorTimestampNs - previous < 16_000_000L) return null;
        double dt = previous == 0 ? 1. / 30 : (sensorTimestampNs - previous) * 1e-9;
        lastTimestampNs = sensorTimestampNs;
        if (startup) {
            if (startupDeadlineNs == 0) startupDeadlineNs = sensorTimestampNs + 1_500_000_000L;
            if (sensorTimestampNs >= startupDeadlineNs) startup = false;
        }
        double targetTau = startup ? .025 : .1;
        double controlTau = startup ? .05 : .1;
        double rate = startup ? 12 : 4;
        double deadband = startup ? .025 : .04;
        errorEv = meterEv + compensationEv;
        double baseIso = log2(Math.max(bounds.minIso, Math.min(bounds.maxIso, base.iso)));
        double baseTime = log2(Math.max(bounds.minTimeNs, Math.min(bounds.maxTimeNs, base.timeNs)));
        double minIso = mode.fixedIso ? baseIso : log2(bounds.minIso);
        double maxIso = mode.fixedIso ? baseIso : log2(bounds.maxIso);
        double minTime = mode.fixedShutter ? baseTime : log2(bounds.minTimeNs);
        double maxTime = mode.fixedShutter ? baseTime : log2(bounds.maxTimeNs);
        logIso = clamp(logIso, minIso, maxIso);
        logTime = clamp(logTime, minTime, maxTime);
        double desired = log2(measured.timeNs) + log2(actualIso) + errorEv;
        double target = clamp(desired, minIso + minTime, maxIso + maxTime);
        limited = Math.abs(desired - target) > .08;
        history.addLast(new Target(sensorTimestampNs, target));
        while (history.getFirst().timestampNs < sensorTimestampNs - 300_000_000L)
            history.removeFirst();
        if (!startup) {
            double[] values = new double[history.size()];
            int n = 0;
            for (Target t : history) values[n++] = t.value;
            Arrays.sort(values);
            target = (values[values.length / 2] + values[(values.length - 1) / 2]) * .5;
        }
        if (filteredTarget != null)
            target = (1 - Math.exp(-dt / targetTau)) * (target - filteredTarget) + filteredTarget;
        filteredTarget = target;
        double sum = logIso + logTime;
        double difference = target - sum;
        double step = Math.min(.2, dt * rate);
        double change = Math.abs(difference) < deadband ? 0
                : clamp((1 - Math.exp(-dt / controlTau)) * difference, -step, step);
        double loIso = Math.max(minIso, logIso - step), hiIso = Math.min(maxIso, logIso + step);
        double loTime = Math.max(minTime, logTime - step), hiTime = Math.min(maxTime, logTime + step);
        double product = clamp(sum + change, loIso + loTime, hiIso + hiTime);
        double allocLo = Math.max(loIso, product - hiTime);
        double allocHi = Math.max(allocLo, Math.min(hiIso, product - loTime));
        double oldIso = logIso, oldTime = logTime;
        double hz = flickerHz != null && Float.isFinite(flickerHz) && flickerHz > 0 ? flickerHz : 50;
        double preferred = log2(preferredIso);
        double bias = Float.isFinite(isoBiasStops) ? Math.max(-3f, Math.min(3f, isoBiasStops)) : 0;
        logIso = clamp(Math.max(0, (product - (log2(1e9 / (hz * 2)) + preferred)) * .5)
                + preferred + bias, allocLo, allocHi);
        logTime = clamp(product - logIso, minTime, maxTime);
        if (startup && Math.abs(difference) < .025 && Math.abs(logIso - oldIso) < .025
                && Math.abs(logTime - oldTime) < .025) startup = false;
        int iso = (int) Math.min(Integer.MAX_VALUE, Math.round(Math.pow(2, logIso)));
        Exposure next = bounds.clamp(new Exposure(iso, Math.round(Math.pow(2, logTime))));
        if (next.equals(last)) return null;
        if (suppressTinyChanges && bounds.clamp(last).equals(last)
                && Math.abs(log2((double) next.timeNs / last.timeNs))
                + Math.abs(log2((double) next.iso / last.iso)) < .01) return null;
        last = next;
        return next;
    }

    private static double log2(double value) { return Math.log(value) / Math.log(2); }
    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
