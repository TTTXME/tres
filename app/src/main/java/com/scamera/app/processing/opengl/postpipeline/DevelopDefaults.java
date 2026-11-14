package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.scamera.app.processing.DevelopRecord;
import com.scamera.app.processing.render.ColorCorrectionTransform;
import com.scamera.app.processing.render.Converter;
import com.scamera.app.processing.render.NoiseModeler;
import com.scamera.app.processing.render.Parameters;

import org.json.JSONException;

import java.util.Arrays;
import java.util.Map;

/**
 * P696 (owner, 2026-11-14: «В галерее не даёт обрабатывать равы, хотя это Рав из нашей камеры»): the develop record of a DNG that
 * has none (a SCAMERA DNG shot before P665 wrote it, or another camera's Bayer DNG with its colour tags) made from the DNG's own
 * tags and the camera's settings now, so the gallery developer (DevelopRun) runs the camera's post pipeline on it as on a shot of
 * ours. A DNG with its record keeps it: this is only the fallback.
 *
 * <p>What the DNG gives: its grid, the as-shot neutral, camera RGB -> XYZ D50 at that white (DngColor: ForwardMatrix, else the
 * inverse ColorMatrix), the scene's colour temperature, the NoiseProfile, ISO, exposure time, f-number, focal length, orientation
 * and - a SCAMERA DNG only - the headroom its merged mosaic was written over (P165: BaselineExposure = log2 of it). What it cannot
 * give takes the camera's defaults: the merge's numbers of one frame (DevelopRecord.Worker's defaults: no Bento, no frame map, no
 * clip flags or highlight mask), no crop, no final size, and the settings and scam_dev.txt lines in force now (what a shot taken
 * now would be processed with). The lens shading: the Camera2 map our DNG writer put in its OpcodeList2 GainMaps, read back, as
 * the shot's own run takes it (its colour share, ScamRgb); a flat map without one.
 */
public final class DevelopDefaults {
    /** The record's "source" of a record made here (a camera's record has none). */
    public static final String SOURCE = "dng_tags";

    private DevelopDefaults() {}

    /** What the DNG says (the editor reads it: DngRaw.Info). */
    public static final class Facts {
        /** The DNG's Bayer grid (its decode at full size). */
        public int width, height;
        /** The as-shot camera neutral (green 1). */
        public float[] neutral = {1f, 1f, 1f};
        /** Camera RGB -> XYZ D50 at {@link #neutral} (white balance included), row-major; null when the DNG has no matrices. */
        @Nullable public float[] cameraToXyzD50;
        /** The as-shot white's correlated colour temperature (K). */
        public float sceneCct = 6504f;
        /** The DNG's NoiseProfile: (S, O) per plane (3 planes, or 1 for all) of the DNG's normalised level; null for none. */
        @Nullable public double[] noiseProfile;
        public int iso;
        /** Seconds, f-number, millimetres: 0 when the DNG does not say. */
        public double exposureTime;
        public float fNumber, focalMm;
        /** The photo's clockwise rotation (0, 90, 180, 270) from the DNG's orientation. */
        public int rotationDeg;
        /** The DNG's 16-bit white over the RGB's white (a SCAMERA merged DNG's headroom; 1 for any other). */
        public float headroom = 1f;
        public int whiteLevel = 65535;
        public float blackLevel;
        public String software = "";
        /** The lens-shading map (Camera2's: {@link #shadingW} x {@link #shadingH} points of R, G, G, B gains), null for none. */
        @Nullable public float[] shadingMap;
        public int shadingW, shadingH;
    }

    /** Whether {@code r} was made here (from the DNG's tags) rather than taken by the camera with the shot. */
    public static boolean fromTags(@Nullable DevelopRecord r) {
        return r != null && SOURCE.equals(r.meta.optString("source", ""));
    }

    /** The record of {@code f} with the settings and scam_dev.txt lines in force now. */
    public static DevelopRecord record(Facts f) throws JSONException {
        final Map<String, ?> prefs = com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getAll();
        return record(f, prefs, com.scamera.app.settings.PreferenceKeys.scamDevLines());
    }

    /** The record of {@code f} with {@code prefs} and {@code dev} (a pure function of its inputs: the tests build it on the host). */
    static DevelopRecord record(Facts f, Map<String, ?> prefs, Map<String, Float> dev) throws JSONException {
        final DevelopRecord r = DevelopCapture.record(parameters(f), prefs, dev, new DevelopRecord.Worker(), Math.max(1f, f.headroom),
                f.width, f.height, 1f, false);
        r.meta.put("source", SOURCE);
        r.meta.put("software", f.software == null ? "" : f.software);
        return r;
    }

    /** The post pipeline's Parameters of {@code f} (what PostPipeline.develop reads of a shot). */
    static Parameters parameters(Facts f) {
        final Parameters p = new Parameters();
        final int w = Math.max(2, f.width), h = Math.max(2, f.height);
        p.rawSize = new Point(w, h);
        p.activeArea = new Rect(0, 0, w, h);
        p.outputScale = 1f;
        p.scamHdrRawScale = 1f;
        p.rotationDeg = ((f.rotationDeg % 360) + 360) % 360;
        // the colour: the white balance, then camera -> ProPhoto (Parameters' DNG chain) and the standard ProPhoto -> sRGB; without
        // matrices the white-balanced camera RGB is taken as sRGB (Parameters' identity fallback)
        final float[] n = neutral(f.neutral);
        p.asShotNeutral = n;
        final boolean matrices = usable(f.cameraToXyzD50);
        if (matrices) {
            Converter.multiply(Converter.sXYZtoProPhoto, f.cameraToXyzD50, p.cameraToProPhoto);
            Converter.multiply(Converter.sXYZtoSRGB, Converter.sProPhotoToXYZ, p.proPhotoToSrgb);
        } else {
            for (int i = 0; i < 9; i++) p.cameraToProPhoto[i] = i % 4 == 0 ? 1f / n[i / 3] : 0f;
            p.proPhotoToSrgb = new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        }
        p.colorTransform = new ColorCorrectionTransform();
        p.colorTransform.matrix = p.proPhotoToSrgb;
        p.colourSource = (matrices ? Parameters.ColourSource.DNG_FM : Parameters.ColourSource.IDENTITY_FALLBACK).name();
        p.colourSourceWhy = "the DNG's own tags (no develop record)";
        p.sceneCct = f.sceneCct > 1000f && f.sceneCct < 50000f ? f.sceneCct : 6504f;
        p.sceneCctSource = "DNG";
        // the exposure of the shot (EXIF), defaults where the DNG does not say
        p.iso = f.iso > 0 ? f.iso : 100;
        if (f.exposureTime > 0 && Double.isFinite(f.exposureTime)) p.exposureTime = f.exposureTime;
        if (f.fNumber > 0 && Float.isFinite(f.fNumber)) p.fNumber = f.fNumber;
        if (f.focalMm > 0 && Float.isFinite(f.focalMm)) p.focalMm = f.focalMm;
        p.whiteLevel = f.whiteLevel > 0 ? f.whiteLevel : 65535;
        Arrays.fill(p.cfaBlack, Math.max(0f, f.blackLevel));
        // the DNG's lens-shading map, else a flat one
        if (f.shadingMap != null && f.shadingW > 0 && f.shadingH > 0 && f.shadingMap.length >= 4 * f.shadingW * f.shadingH) {
            p.shadingGrid = new Point(f.shadingW, f.shadingH);
            p.shadingMap = Arrays.copyOf(f.shadingMap, 4 * f.shadingW * f.shadingH);
        } else {
            p.shadingGrid = new Point(2, 2);
            p.shadingMap = new float[4 * 2 * 2];
            Arrays.fill(p.shadingMap, 1f);
        }
        p.noiseModel = noise(f, p.iso);
        p.multiFrameCount = 1;
        return p;
    }

    /**
     * The noise of the RGB the pipeline takes (the DNG's level times the headroom): the DNG's (S, O) as S H, O H^2 for every
     * frame merged into it (the record's one normal frame: the profile already holds the merge), else the generic phone sensor at
     * the ISO (NoiseModeler's own fallback).
     */
    @SuppressWarnings("unchecked")
    static NoiseModeler noise(Facts f, int iso) {
        final NoiseModeler m = new NoiseModeler(null, Math.max(100, iso), iso, 0);
        final double k = Math.max(1f, f.headroom);
        final double[] np = f.noiseProfile;
        if (np != null && (np.length == 2 || np.length >= 6)) {
            final Pair<Double, Double>[] planes = new Pair[3];
            boolean ok = true;
            for (int c = 0; c < 3; c++) {
                final int at = np.length == 2 ? 0 : 2 * c;
                final double s = np[at], o = np[at + 1];
                ok &= s > 0 && Double.isFinite(s) && o >= 0 && Double.isFinite(o);
                planes[c] = new Pair<>(s * k, o * k * k);
            }
            if (ok) m.rawModel = planes;
        }
        m.stackModel = m.rawModel.clone();
        return m;
    }

    private static float[] neutral(@Nullable float[] n) {
        if (n == null || n.length < 3) return new float[]{1f, 1f, 1f};
        final float[] out = new float[3];
        for (int c = 0; c < 3; c++) out[c] = n[c] > 1e-4f && Float.isFinite(n[c]) ? n[c] : 1f;
        return out;
    }

    private static boolean usable(@Nullable float[] m) {
        if (m == null || m.length < 9) return false;
        for (int i = 0; i < 9; i++) if (!Float.isFinite(m[i])) return false;
        final double det = m[0] * ((double) m[4] * m[8] - (double) m[5] * m[7]) - m[1] * ((double) m[3] * m[8] - (double) m[5] * m[6])
                + m[2] * ((double) m[3] * m[7] - (double) m[4] * m[6]);
        return Math.abs(det) > 1e-9;
    }
}
