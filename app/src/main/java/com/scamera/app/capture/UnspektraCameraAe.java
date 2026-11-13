package com.scamera.app.capture;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import android.util.Rational;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.manual.MotionShutter;
import com.scamera.app.manual.UnspektraAe;
import com.scamera.app.manual.UnspektraMeter;
import com.scamera.app.settings.MotionMode;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.UnspektraAePreferences;
import com.scamera.app.util.Log;

public final class UnspektraCameraAe {
    private final CaptureController camera;
    private final UnspektraAe loop = new UnspektraAe();
    private final MotionShutter.Levels motion = new MotionShutter.Levels();
    private volatile UnspektraAe.Exposure applied;
    private volatile boolean refused;
    private volatile boolean acknowledged;
    private long previewTimestamp, previewArrival, usedTimestamp, logAt;
    private long sentTimestamp;
    /**
     * P476 (owner, 2026-11-03: «unspektra AE ... работает ступенчато, должен работать плавно»): the exposures sent lately. A frame
     * whose own request is one of them pairs its meter with its own exposure, so the loop steps on every frame instead of waiting
     * the HAL's 3-5 frame latency for the last request and then moving up to 0.2 EV at once (visible steps ~6 times a second).
     */
    private final java.util.ArrayDeque<UnspektraAe.Exposure> sent = new java.util.ArrayDeque<>();
    private int misses;
    private String configuration;
    private Long previousFrameDuration;
    private long ownedFrameDuration;

    public UnspektraCameraAe(CaptureController camera) { this.camera = camera; }

    public boolean eligible() {
        if (refused || camera.previewAeOffUnsafe() || !UnspektraAePreferences.selected(camera.physicalId())) return false;
        if (camera.getParamController().manualIsoValue() > 0 && camera.getParamController().manualPreviewShutterNs() > 0) return false;
        CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        int[] capabilities = c == null ? null : c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null) for (int capability : capabilities)
            if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) return true;
        return false;
    }

    public boolean ownsExposure() {
        return applied != null && eligible() && !(camera.getParamController().manualIsoValue() > 0
                && camera.getParamController().manualPreviewShutterNs() > 0);
    }

    public boolean stable() { return ownsExposure() && acknowledged && (Math.abs(loop.errorEv()) < .2 || loop.limited()); }

    public synchronized void reset() {
        loop.resetMeter();
        applied = null; refused = false; configuration = null;
        acknowledged = false;
        previousFrameDuration = null; ownedFrameDuration = 0;
        previewTimestamp = previewArrival = usedTimestamp = logAt = 0; misses = 0; motion.reset();
        sentTimestamp = 0;
        sent.clear();
    }

    public synchronized void invalidate() { applied = null; configuration = null; usedTimestamp = 0; acknowledged = false; sent.clear(); loop.resetMeter(); }
    public void resetMeter() { loop.resetMeter(); }
    public long revision() { return loop.meterRevision(); }

    public synchronized void noteResult(CaptureResult result) {
        if (android.os.Build.VERSION.SDK_INT >= 28 && result instanceof android.hardware.camera2.TotalCaptureResult) {
            CaptureResult physical = ((android.hardware.camera2.TotalCaptureResult)result).getPhysicalCameraResults().get(camera.physicalId());
            if (physical != null && physical.get(CaptureResult.SENSOR_TIMESTAMP) != null) result = physical;
        }
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts != null && ts > previewTimestamp) {
            previewTimestamp = ts; previewArrival = android.os.SystemClock.elapsedRealtimeNanos();
        }
    }

    public synchronized void apply(CaptureRequest.Builder builder) {
        if (!ownsExposure()) {
            if (ownedFrameDuration != 0) {
                Long current = builder.get(CaptureRequest.SENSOR_FRAME_DURATION);
                if (current != null && current == ownedFrameDuration) builder.set(CaptureRequest.SENSOR_FRAME_DURATION, previousFrameDuration);
                ownedFrameDuration = 0; previousFrameDuration = null;
            }
            return;
        }
        UnspektraAe.Exposure e = applied;
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, e.iso);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e.timeNs);
        Long duration = builder.get(CaptureRequest.SENSOR_FRAME_DURATION);
        if (ownedFrameDuration == 0) previousFrameDuration = duration;
        long frameTime = Math.max(previousFrameDuration == null ? 33_333_333L : previousFrameDuration, e.timeNs);
        if (ownedFrameDuration != 0 || duration == null && e.timeNs > 33_333_333L || duration != null && duration < frameTime) {
            ownedFrameDuration = frameTime;
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameTime);
        }
    }

    public UnspektraMeter.Method method() {
        return number(UnspektraAePreferences.METHOD, 0) == 1 ? UnspektraMeter.Method.MEDIAN : UnspektraMeter.Method.CENTRE_WEIGHTED;
    }

    private double number(String key, double fallback) {
        SharedPreferences prefs = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        double n = UnspektraAePreferences.value(prefs, UnspektraAePreferences.currentPrefix(camera.physicalId()), key, fallback);
        return Double.isFinite(n) && n >= 0 ? n : fallback;
    }

    // The caller holds the matched RAW only for this call; no Image or buffer is retained.
    public boolean observe(CaptureResult result, double meterEv, long revision) {
        boolean rebuild = false;
        synchronized (this) {
            if (revision != loop.meterRevision() || !eligible() || !Double.isFinite(meterEv)) return false;
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP), time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (ts == null || time == null || iso == null || time <= 0 || iso <= 0 || ts <= usedTimestamp
                    || previewTimestamp < ts || previewTimestamp - ts > 250_000_000L
                    || android.os.SystemClock.elapsedRealtimeNanos() - previewArrival > 500_000_000L) return false;
            int manualIso = camera.getParamController().manualIsoValue();
            long manualTime = camera.getParamController().manualPreviewShutterNs();
            if (manualIso > 0 && manualTime > 0) { invalidate(); return false; }
            CameraCharacteristics c = CaptureController.mCameraCharacteristics;
            Range<Integer> ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> tr = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (ir == null || tr == null) return false;
            int minIso = ir.getLower(), maxIso = Math.min(ir.getUpper(), camera.honouredIsoCap());
            maxIso = Math.max(minIso, maxIso);
            Long maxDuration = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
            long minTime = tr.getLower(), maxTime = Math.min(tr.getUpper(), maxDuration == null ? tr.getUpper() : maxDuration);
            if (manualIso == 0) {
                double lo = number(UnspektraAePreferences.MIN_ISO, 0), hi = number(UnspektraAePreferences.MAX_ISO, 0);
                if (lo > 0) minIso = (int)Math.max(minIso, Math.min(maxIso, lo));
                if (hi > 0) maxIso = (int)Math.max(minIso, Math.min(maxIso, hi));
            }
            if (manualTime == 0) {
                double fast = number(UnspektraAePreferences.FAST, 0), slow = number(UnspektraAePreferences.SLOW, 30);
                if (fast > 0) minTime = Math.max(minTime, Math.min(maxTime, Math.round(1e9 / fast)));
                if (slow > 0) maxTime = Math.max(minTime, Math.min(maxTime, Math.round(1e9 / slow)));
                if (MotionMode.on()) {
                    MotionMeter.Sample sample = MotionMeter.sample();
                    long cap = motion.capNs(MotionShutter.settingNs(PreferenceKeys.hybridValue("mm_shutter", 250f)),
                            sample == null ? 0 : sample.pxPerS(), sample == null ? 0 : sample.atNs, System.nanoTime());
                    maxTime = Math.max(minTime, Math.min(maxTime, cap));
                }
            }
            UnspektraAe.Bounds bounds = new UnspektraAe.Bounds(minIso, maxIso, minTime, Math.max(minTime, maxTime));
            UnspektraAe.Mode mode = manualIso > 0 ? UnspektraAe.Mode.ISO_PRIORITY
                    : manualTime > 0 ? UnspektraAe.Mode.SHUTTER_PRIORITY : UnspektraAe.Mode.AUTO;
            String config = UnspektraAePreferences.currentPrefix(camera.physicalId()) + ':' + mode + ':' + manualIso
                    + ':' + manualTime + ':' + minIso + ':' + maxIso + ':' + minTime + ':' + number(UnspektraAePreferences.SLOW, 30)
                    + ':' + method();
            usedTimestamp = ts;
            if (!config.equals(configuration) || applied == null) {
                configuration = config; misses = 0;
                acknowledged = false;
                applied = bounds.clamp(new UnspektraAe.Exposure(manualIso > 0 ? manualIso : iso, manualTime > 0 ? manualTime : time));
                sentTimestamp = ts;
                sent.clear(); sent.addLast(applied);
                loop.reset(mode, applied); rebuild = true;
                Log.i("SCAM_AE", "Unspektra AE start " + camera.physicalId() + " " + mode + " ISO " + applied.iso + " ns " + applied.timeNs);
            } else {
                CaptureRequest request = result.getRequest();
                Integer ae = result.get(CaptureResult.CONTROL_AE_MODE);
                Integer requestedIso = request.get(CaptureRequest.SENSOR_SENSITIVITY);
                Long requestedTime = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
                if (requestedIso == null || requestedTime == null || requestedIso <= 0 || requestedTime <= 0
                        || !sent.contains(new UnspektraAe.Exposure(requestedIso, requestedTime))) {
                    if (ts - sentTimestamp > 2_000_000_000L) {
                        refused = true; applied = null;
                        Log.w("SCAM_AE", "Unspektra AE: preview request was overridden, restoring Android AE for this session");
                    }
                    return false;
                }
                if (ae == null || ae != CaptureResult.CONTROL_AE_MODE_OFF || Math.abs((double)time / requestedTime - 1) > .05
                        || Math.abs((double)iso / requestedIso - 1) > .05) {
                    if (++misses >= 24) {
                        refused = true; applied = null;
                        Log.w("SCAM_AE", "Unspektra AE: HAL did not apply exposure, restoring Android AE for this session");
                    }
                    return false;
                }
                misses = 0;
                acknowledged = requestedIso == applied.iso && requestedTime == applied.timeNs;
                Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                double ev = camera.getParamController().effectiveEv() * (step == null ? 1. / 3 : step.doubleValue());
                UnspektraAe.Exposure next = loop.update(meterEv, new UnspektraAe.Exposure(requestedIso, time), ts,
                        loop.generation(), loop.meterRevision(), bounds, ev, 0, ir.getLower(),
                        MainsFlicker.hz() > 0 ? MainsFlicker.hz() * .5f : null, iso, true);
                if (next != null) {
                    applied = next; sentTimestamp = ts; acknowledged = false; rebuild = true;
                    sent.addLast(next);
                    while (sent.size() > 16) sent.removeFirst();
                }
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - logAt >= 1000) {
                    logAt = now;
                    Log.i("SCAM_AE", "Unspektra AE frame " + ts + " errorEV " + meterEv + " actual ISO " + iso + " ns " + time
                            + " -> ISO " + applied.iso + " ns " + applied.timeNs + " limited " + loop.limited());
                }
            }
        }
        return rebuild;
    }
}
