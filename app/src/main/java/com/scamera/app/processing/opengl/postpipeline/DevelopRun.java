package com.scamera.app.processing.opengl.postpipeline;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;

import androidx.annotation.Nullable;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.DevelopRecord;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.PreferenceValue;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * P665: the gallery developer's run of the camera's post pipeline on a DNG of ours (its DevelopRecord): the same PostPipeline the
 * shot took, with the shot's Parameters (sized to the DNG's RGB), the shot's settings with the developer's changes over them
 * (PreferenceValue.overlay - the lab's way, P277: every node reads its setting through it), the shot's scam_dev.txt lines and the
 * merge's numbers and maps (ScamHybridBurst / ScamRgb statics, set for the run and put back after). Under ScamLab.LOCK, so a shot's
 * processing and a develop never overlap (they share those statics and the process's GL context).
 *
 * <p>What differs from the shot's run is the input: the shot's pipeline took the merge's float RGB, the DNG holds a Bayer mosaic
 * sampled from it (P165), which the developer demosaics (EditDevelop). With the shot's settings unchanged the develop is the
 * camera's pipeline on that RGB - the same code, the same Parameters and settings ({@link #parameters}, {@link #values}).
 */
public final class DevelopRun {
    private static final String TAG = "DevelopRun";
    /** How long a develop waits for a shot being processed. */
    static final long LOCK_WAIT_S = 120;

    private DevelopRun() {}

    /**
     * The shot's Parameters for an RGB of {@code w x h}: the DNG's grid (the full develop) or a reduced copy of it (the preview).
     * The pipeline's size and active area follow the RGB; the final size (P503 «Уменьшить до») keeps its share of the DNG's grid
     * (a Sabre 2x shot's RGB was twice the DNG's: its final size against the DNG's grid), none when it does not shrink it.
     */
    public static Parameters parameters(DevelopRecord r, int w, int h) throws JSONException {
        final Parameters p = ScamLabParams.fromJson(r.meta.getJSONObject("params"));
        final Point full = p.rawSize;
        final JSONObject dng = r.meta.optJSONObject("dng");
        final int dw = dng != null ? dng.optInt("w", 0) : 0, dh = dng != null ? dng.optInt("h", 0) : 0;
        final Point base = dw > 0 && dh > 0 ? new Point(dw, dh) : full;
        if (p.hybridFinalSize != null && base != null && base.x > 0 && base.y > 0) {
            final double fx = (double) p.hybridFinalSize.x / base.x, fy = (double) p.hybridFinalSize.y / base.y;
            final int fw = Math.max(2, (int) Math.round(w * fx)) & ~1, fh = Math.max(2, (int) Math.round(h * fy)) & ~1;
            // the final size only ever shrinks the grid (HybridFinalResize); at or above it, none
            p.hybridFinalSize = fx < 1 && fy < 1 && fw < w && fh < h ? new Point(fw, fh) : null;
        }
        if (full != null && full.x > 0 && full.y > 0 && (full.x != w || full.y != h)) {
            final double sx = (double) w / full.x, sy = (double) h / full.y;
            if (p.activeArea != null)
                p.activeArea = new Rect((int) Math.round(p.activeArea.left * sx), (int) Math.round(p.activeArea.top * sy),
                        (int) Math.round(p.activeArea.right * sx), (int) Math.round(p.activeArea.bottom * sy));
            p.outputScale = (float) (p.outputScale * sx);
        }
        p.rawSize = new Point(w, h);
        return p;
    }

    /**
     * The settings the develop reads: the shot's (the record's), never the diagnostics, the kept bursts, the lab's switch or the
     * merge stamp (ScamLabRun.basePrefs' rule), then {@code changes} (key -> value; null: the key unset, its default).
     */
    public static Map<String, Object> values(DevelopRecord r, @Nullable Map<String, Object> changes) throws JSONException {
        final Map<String, Object> m = shotValues(r);
        if (changes != null)
            for (Map.Entry<String, Object> e : changes.entrySet()) {
                if (e.getValue() == null) m.remove(e.getKey());
                else m.put(e.getKey(), e.getValue());
            }
        return m;
    }

    /** The shot's settings as the develop reads them (without changes). */
    public static Map<String, Object> shotValues(DevelopRecord r) throws JSONException {
        final JSONObject prefs = r.meta.optJSONObject("prefs");
        final Map<String, Object> m = prefs == null ? new HashMap<>() : ScamLabParams.prefs(prefs);
        m.put("pref_scam_hybrid_diagnostics", false);
        m.put("pref_keep_raw_burst", false);
        m.put(ScamLab.PREF, false);
        m.put(com.scamera.app.processing.MergeStamp.KEY, false);
        return m;
    }

    /** The scam_dev.txt lines of the shot (its develop reads these, not today's file). */
    static Map<String, Float> devLines(DevelopRecord r) {
        final Map<String, Float> out = new HashMap<>();
        final JSONObject d = r.meta.optJSONObject("dev");
        if (d == null) return out;
        for (Iterator<String> it = d.keys(); it.hasNext(); ) {
            final String k = it.next();
            final double v = d.optDouble(k, Double.NaN);
            if (Double.isFinite(v)) out.put(k, (float) v);
        }
        return out;
    }

    /** The merge's per-pixel map (one byte a pixel) of {@code r} for a {@code w x h} grid, null without one. */
    @Nullable
    static ByteBuffer map(@Nullable byte[] data, int mw, int mh, int w, int h, boolean or) {
        if (data == null || mw <= 0 || mh <= 0) return null;
        final ByteBuffer out = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder());
        if (mw == w && mh == h) {
            out.put(data);
            out.clear();
            return out;
        }
        out.put(resampled(data, mw, mh, w, h, or));
        out.clear();
        return out;
    }

    /**
     * A one-byte-a-pixel map of {@code mw x mh} on a {@code w x h} grid: the source pixels a target pixel covers OR-ed (the clip
     * flags: a clipped site anywhere in it) or averaged (the frame map).
     */
    static byte[] resampled(byte[] data, int mw, int mh, int w, int h, boolean or) {
        final byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            final int y0 = (int) ((long) y * mh / h), y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * mh / h));
            for (int x = 0; x < w; x++) {
                final int x0 = (int) ((long) x * mw / w), x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * mw / w));
                int acc = 0, n = 0;
                for (int sy = y0; sy < Math.min(mh, y1); sy++)
                    for (int sx = x0; sx < Math.min(mw, x1); sx++) {
                        final int v = data[sy * mw + sx] & 255;
                        if (or) acc |= v;
                        else acc += v;
                        n++;
                    }
                out[y * w + x] = (byte) (or ? acc : n == 0 ? 0 : (acc + n / 2) / n);
            }
        }
        return out;
    }

    /** The highlight mask (halves, half grid) for a {@code w x h} RGB: its own size when the grid is the shot's, else nearest. */
    @Nullable
    static ScamHybridBurst.HlMask hlMask(DevelopRecord r, int w, int h, int shotW) {
        if (r.hlMask == null || r.hlW <= 0 || r.hlH <= 0) return null;
        if (w == shotW) {
            final ByteBuffer b = ByteBuffer.allocateDirect(r.hlMask.length).order(ByteOrder.nativeOrder());
            b.put(r.hlMask);
            b.clear();
            return new ScamHybridBurst.HlMask(b, r.hlW, r.hlH);
        }
        final double k = (double) w / Math.max(1, shotW);
        final int tw = Math.max(1, (int) Math.round(r.hlW * k)), th = Math.max(1, (int) Math.round(r.hlH * k));
        final ByteBuffer b = ByteBuffer.allocateDirect(tw * th * 2).order(ByteOrder.nativeOrder());
        for (int y = 0; y < th; y++) {
            final int sy = Math.min(r.hlH - 1, (int) ((y + .5) * r.hlH / th));
            for (int x = 0; x < tw; x++) {
                final int sx = Math.min(r.hlW - 1, (int) ((x + .5) * r.hlW / tw));
                final int at = (sy * r.hlW + sx) * 2;
                b.put(r.hlMask[at]).put(r.hlMask[at + 1]);
            }
        }
        b.clear();
        return new ScamHybridBurst.HlMask(b, tw, th);
    }

    /** What the develop changes of the process's state for its run, and puts back. */
    private static final class Saved {
        final boolean bento = ScamHybridBurst.lastBentoApplied, rgbHalf = ScamHybridBurst.lastRgbHalf;
        final float bentoFactor = ScamHybridBurst.lastBentoFactor, bentoUs = ScamHybridBurst.lastBentoUsClipped,
                median = ScamHybridBurst.lastEffectiveMedian, noise = ScamHybridBurst.lastNoiseFactor;
        final int normals = ScamHybridBurst.lastNormals;
        final String report = ScamHybridBurst.lastReport;
        final ByteBuffer frames = ScamHybridBurst.lastEffectiveFrames;
        final ScamHybridBurst.HlMask hl = ScamHybridBurst.lastHlMask;

        void restore() {
            ScamHybridBurst.lastBentoApplied = bento;
            ScamHybridBurst.lastRgbHalf = rgbHalf;
            ScamHybridBurst.lastBentoFactor = bentoFactor;
            ScamHybridBurst.lastBentoUsClipped = bentoUs;
            ScamHybridBurst.lastEffectiveMedian = median;
            ScamHybridBurst.lastNoiseFactor = noise;
            ScamHybridBurst.lastNormals = normals;
            ScamHybridBurst.lastReport = report;
            ScamHybridBurst.lastEffectiveFrames = frames;
            ScamHybridBurst.lastHlMask = hl;
        }
    }

    /** Sets the merge's numbers of the record for the run. */
    static void applyWorker(DevelopRecord.Worker w, boolean half) {
        ScamHybridBurst.lastBentoApplied = w.bentoApplied;
        ScamHybridBurst.lastBentoFactor = w.bentoFactor;
        ScamHybridBurst.lastBentoUsClipped = w.bentoUsClipped;
        ScamHybridBurst.lastEffectiveMedian = w.effectiveMedian;
        ScamHybridBurst.lastNoiseFactor = w.noiseFactor;
        ScamHybridBurst.lastNormals = w.normals;
        ScamHybridBurst.lastReport = w.report;
        ScamHybridBurst.lastRgbHalf = half;
    }

    /**
     * Develops {@code rgb} - the DNG's RGB of {@code w x h} (float32 RGB, or IEEE halves when {@code half}), allocated by Allocator:
     * the pipeline frees it as it frees the merge's (ScamRgb), or this does at the end - with the record's settings and
     * {@code changes}. Returns the photo (upright sRGB, ARGB_8888), the record's crops applied. Not on the main thread.
     */
    public static Bitmap develop(DevelopRecord r, ByteBuffer rgb, int w, int h, boolean half, @Nullable Map<String, Object> changes)
            throws Exception {
        boolean locked;
        try {
            locked = ScamLab.LOCK.tryLock(LOCK_WAIT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            com.scamera.app.util.Allocator.free(rgb);
            throw new IOException(Lang.t("Камера обрабатывает снимок. Повторите, когда она закончит.",
                    "The camera is processing a shot. Try again when it is done."));
        }
        final Saved saved = new Saved();
        Parameters p = null;
        PreferenceKeys.beginShotProfile(true);
        try {
            final SharedPreferences prefs = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            PreferenceValue.overlay(prefs, values(r, changes));
            PreferenceKeys.labDevOverlay(devLines(r));
            applyWorker(r.worker(), half);
            final Parameters shot = ScamLabParams.fromJson(r.meta.getJSONObject("params"));
            final int shotW = shot.rawSize != null ? shot.rawSize.x : w;
            ScamRgb.dropClipFlags();
            ScamRgb.lastClipFlags = map(r.clipFlags, r.clipW, r.clipH, w, h, true);
            ScamHybridBurst.lastEffectiveFrames = map(r.frames, r.framesW, r.framesH, w, h, false);
            ScamHybridBurst.lastHlMask = hlMask(r, w, h, shotW);
            p = parameters(r, w, h);
            p.scamRgb = rgb;
            p.scamRgbOwned = true;
            p.scamHdrRawScale = 1f;
            final long t0 = System.nanoTime();
            final PostPipeline pipeline = new PostPipeline();
            Bitmap photo;
            try {
                photo = pipeline.develop(null, p);
            } finally {
                pipeline.close();
            }
            Log.i(TAG, "developed " + w + "x" + h + (half ? " (half RGB)" : "") + " -> " + (photo == null ? "none"
                    : photo.getWidth() + "x" + photo.getHeight()) + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms, "
                    + (changes == null ? 0 : changes.size()) + " setting(s) changed");
            if (photo == null) throw new IOException("develop: no picture");
            return crops(r, photo);
        } finally {
            PreferenceValue.overlay(null, null);
            PreferenceKeys.labDevOverlay(null);
            PreferenceKeys.endShotProfile();
            if (p == null || p.scamRgb == rgb) com.scamera.app.util.Allocator.free(rgb);
            ScamRgb.dropClipFlags();
            saved.restore();
            ScamLab.LOCK.unlock();
        }
    }

    /** The crops ScamProcessor makes after the pipeline (DevelopCapture): the digital zoom's centre crop, then the ISZ 4:3. */
    static Bitmap crops(DevelopRecord r, Bitmap photo) {
        final JSONObject c = r.meta.optJSONObject("crop");
        if (c == null) return photo;
        Bitmap img = com.scamera.app.control.ZoomController.crop(photo, (float) c.optDouble("zoom", 1));
        if (c.optBoolean("isz4x3", false) && img.getWidth() >= 8 && img.getHeight() >= 8) {
            try {
                img = com.scamera.app.processing.IszAspectCrop.cropOwned(img,
                        com.scamera.app.processing.IszAspectCrop.bounds(img.getWidth(), img.getHeight()));
            } catch (IllegalArgumentException small) {
                Log.d(TAG, "ISZ crop: " + small.getMessage());
            }
        }
        return img;
    }
}
