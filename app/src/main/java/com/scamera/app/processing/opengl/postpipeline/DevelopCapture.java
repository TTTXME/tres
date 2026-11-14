package com.scamera.app.processing.opengl.postpipeline;

import androidx.annotation.Nullable;

import com.scamera.app.processing.DevelopRecord;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P665: the camera's side of the gallery developer (DevelopRecord): ScamProcessor takes the record right before the post pipeline
 * of a shot whose merged DNG was saved - the same moment and the same Parameters the lab's snapshot takes (ScamLab.snapshot) - and
 * appends it to the DNG once the photo and the DNG's preview are saved, on a thread of its own (the deflate of the maps is not on
 * the shot's path). Nothing here touches the shot's pixels: the maps are copies, the JSON is read from the Parameters.
 */
public final class DevelopCapture {
    private static final String TAG = "DevelopRecord";
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "DevelopRecord");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private DevelopCapture() {}

    /**
     * The record of the shot about to be processed: {@code p} (the pipeline's Parameters), the settings and scam_dev.txt lines now,
     * the merge's numbers, copies of the merge's maps when the output is at most DevelopRecord.MAP_MAX_PIXELS, the DNG's
     * {@code headroom} and size, the crops made after the pipeline (the digital zoom's, the ISZ frame's 4:3). Null when it cannot be made (the shot goes on without it).
     */
    @Nullable
    public static DevelopRecord take(Parameters p, @Nullable ByteBuffer clipFlags, @Nullable ByteBuffer frames,
                                     @Nullable ScamHybridBurst.HlMask hl, float headroom, int dngWidth, int dngHeight,
                                     float zoomCrop, boolean isz4x3) {
        try {
            final long t0 = System.nanoTime();
            final Map<String, ?> prefs = com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getAll();
            final DevelopRecord r = record(p, prefs, com.scamera.app.settings.PreferenceKeys.scamDevLines(), worker(), headroom,
                    dngWidth, dngHeight, zoomCrop, isz4x3);
            final long pixels = p.rawSize == null ? 0 : (long) p.rawSize.x * p.rawSize.y;
            if (pixels > 0 && pixels <= DevelopRecord.MAP_MAX_PIXELS) {
                r.clipFlags = DevelopRecord.copy(clipFlags, pixels);
                if (r.clipFlags != null) {
                    r.clipW = p.rawSize.x;
                    r.clipH = p.rawSize.y;
                }
                r.frames = DevelopRecord.copy(frames, pixels);
                if (r.frames != null) {
                    r.framesW = p.rawSize.x;
                    r.framesH = p.rawSize.y;
                }
                if (hl != null) {
                    r.hlMask = DevelopRecord.copy(hl.halves, (long) hl.width * hl.height * 2);
                    if (r.hlMask != null) {
                        r.hlW = hl.width;
                        r.hlH = hl.height;
                    }
                }
            }
            Log.i(TAG, "develop record taken in " + (System.nanoTime() - t0) / 1_000_000 + " ms (maps: clip "
                    + (r.clipFlags != null) + ", frames " + (r.frames != null) + ", highlight mask " + (r.hlMask != null) + ")");
            return r;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "develop record not taken: " + e);
            return null;
        }
    }

    /** The merge's numbers the nodes read now (ScamHybridBurst's statics of this shot). */
    static DevelopRecord.Worker worker() {
        final DevelopRecord.Worker w = new DevelopRecord.Worker();
        w.bentoApplied = ScamHybridBurst.lastBentoApplied;
        w.bentoFactor = ScamHybridBurst.lastBentoFactor;
        w.bentoUsClipped = ScamHybridBurst.lastBentoUsClipped;
        w.effectiveMedian = ScamHybridBurst.lastEffectiveMedian;
        w.noiseFactor = ScamHybridBurst.lastNoiseFactor;
        w.normals = ScamHybridBurst.lastNormals;
        w.report = ScamHybridBurst.lastReport == null ? "" : ScamHybridBurst.lastReport;
        return w;
    }

    /** The record's JSON part (a pure function of its inputs: the tests build it on the host). */
    static DevelopRecord record(Parameters p, Map<String, ?> prefs, Map<String, Float> dev, DevelopRecord.Worker worker,
                                float headroom, int dngWidth, int dngHeight, float zoomCrop, boolean isz4x3) throws JSONException {
        final DevelopRecord r = new DevelopRecord();
        r.meta.put("v", DevelopRecord.VERSION);
        r.meta.put("build", com.scamera.app.BuildConfig.VERSION_CODE);
        r.meta.put("params", ScamLabParams.toJson(p));
        r.meta.put("prefs", ScamLabParams.prefs(prefs));
        final JSONObject lines = new JSONObject();
        if (dev != null)
            for (Map.Entry<String, Float> e : dev.entrySet())
                if (e.getValue() != null && Float.isFinite(e.getValue())) lines.put(e.getKey(), (double) e.getValue());
        r.meta.put("dev", lines);
        r.meta.put("worker", worker.toJson());
        r.meta.put("dng", new JSONObject().put("headroom", (double) Math.max(1f, headroom)).put("w", dngWidth).put("h", dngHeight));
        // the crops ScamProcessor makes after the pipeline: the digital zoom's centre crop, then the ISZ frame's 4:3
        r.meta.put("crop", new JSONObject().put("zoom", zoomCrop > 1.005f && Float.isFinite(zoomCrop) ? (double) zoomCrop : 1.0)
                .put("isz4x3", isz4x3));
        return r;
    }

    /** Appends {@code r} to the DNG at {@code dng} on the writer thread (after the photo and the DNG's preview are saved). */
    public static void appendLater(Path dng, DevelopRecord r) {
        appendLater(dng, r, null);
    }

    /** P681: {@link #appendLater}, then {@code then} on the writer thread (the DNG's one media scan, after its last part). */
    public static void appendLater(Path dng, DevelopRecord r, @androidx.annotation.Nullable Runnable then) {
        if (dng == null || r == null) {
            if (then != null) then.run();
            return;
        }
        WRITER.execute(() -> {
            final long t0 = System.nanoTime();
            try {
                DevelopRecord.append(dng.toFile(), r);
                Log.i(TAG, "develop record appended to " + dng.getFileName() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "develop record not appended to " + dng.getFileName() + ": " + e);
            } finally {
                if (then != null) try { then.run(); } catch (RuntimeException e) { Log.w(TAG, "after the develop record: " + e); }
            }
        });
    }
}
