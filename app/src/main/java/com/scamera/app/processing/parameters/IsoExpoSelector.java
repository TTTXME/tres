package com.scamera.app.processing.parameters;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;

import com.scamera.app.capture.CaptureController;

import java.util.ArrayList;

/**
 * The exposure pairs (time, ISO) of a burst. Both SCAM routes take the metered sensor exposure of the viewfinder as their
 * base pair ({@link #GenerateExpoPair}); {@link #fullpairs} is the plan of the shot being captured.
 */
public class IsoExpoSelector {
    public static boolean HDR = false;
    public static boolean useTripod = false;
    /** The exposure plan of the shot being captured (a queued shot keeps its own copy: ScamProcessor.ownedPairs). */
    public static ArrayList<ExpoPair> fullpairs = new ArrayList<>();

    private IsoExpoSelector() {
    }

    /** The viewfinder's metered exposure within the open camera's ranges; throws when the viewfinder has none. */
    public static ExpoPair GenerateExpoPair(int step, CaptureController capture) {
        final Range<Long> times = range(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        final Range<Integer> isos = range(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        final Integer analogue = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        final ExpoPair pair = new ExpoPair(capture.mPreviewExposureTime,
                times != null ? times.getLower() : ExposureIndex.sec / 1000, times != null ? times.getUpper() : ExposureIndex.sec,
                capture.mPreviewIso, isos != null ? isos.getLower() : 100, isos != null ? isos.getUpper() : 3200,
                analogue != null ? analogue : 100);
        if (pair.exposure <= 0 || pair.iso <= 0) throw new IllegalStateException("SCAM: missing preview exposure/ISO");
        return pair;
    }

    /** ISO 100 over the camera's lowest ISO. */
    public static double getMPY() {
        final Range<Integer> isos = range(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        return 100.0 / (isos != null ? isos.getLower() : 100);
    }

    private static <T extends Comparable<? super T>> Range<T> range(CameraCharacteristics.Key<Range<T>> key) {
        return CaptureController.mCameraCharacteristics.get(key);
    }

    /** One frame's exposure: time (ns) and ISO, the camera's limits, and its role and layer in the merge. */
    public static class ExpoPair {
        public enum exposureLayer {Low, Normal, High}

        public exposureLayer curlayer;
        /** Capture role survives radiometric layer reclassification in ScamProcessor. */
        public boolean isHighlightFrame;
        public boolean isLongFrame;
        /** Exposure relative to the shortest frame of the burst. */
        public float layerMpy = 1.f;
        public long exposure;
        public int iso;
        long exposurehigh, exposurelow;
        int isolow, isohigh, isoanalog;
        /** What limited the pair when it was planned (logged with the plan). */
        public boolean isIsoLimited, isShutterLimited, isShutterTripodBypassed, isIsoManualOverLimit, isShutterManualOverLimit;

        public ExpoPair(ExpoPair pair) {
            copyfrom(pair);
        }

        public ExpoPair(long time, long minTime, long maxTime, int sensitivity, int minIso, int maxIso, int analogIso) {
            exposure = time;
            iso = sensitivity;
            exposurelow = minTime;
            exposurehigh = maxTime;
            isolow = minIso;
            isohigh = maxIso;
            isoanalog = analogIso;
        }

        /** Seconds x ISO: the light the frame collects, in arbitrary units. */
        public double Exposure() {
            return ExposureIndex.time2sec(exposure) * iso;
        }

        /** The exposure, limits, layer and role of {@code other} (not its limit flags). */
        public void copyfrom(ExpoPair other) {
            exposure = other.exposure;
            iso = other.iso;
            exposurelow = other.exposurelow;
            exposurehigh = other.exposurehigh;
            isolow = other.isolow;
            isohigh = other.isohigh;
            isoanalog = other.isoanalog;
            curlayer = other.curlayer;
            layerMpy = other.layerMpy;
            isHighlightFrame = other.isHighlightFrame;
            isLongFrame = other.isLongFrame;
        }

        /** Clamps the pair into the camera's ranges (the ISO on the ISO-100 basis of the lowest ISO). */
        public void normalize() {
            final double basis = 100.0 / isolow;
            if (iso / basis > isohigh) iso = isohigh;
            if (iso / basis < isolow) iso = isolow;
            exposure = Math.max(exposurelow, Math.min(exposurehigh, exposure));
        }

        public String ExposureString() {
            return ExposureIndex.sec2string(ExposureIndex.time2sec(exposure));
        }
    }
}
