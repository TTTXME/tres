package com.particlesdevs.photoncamera.processing.watermark;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;
import com.particlesdevs.photoncamera.util.Log;

import java.util.TimeZone;

/**
 * P628: the watermark of a shot, drawn into the finished photo (ScamProcessor, after the zoom / Sabre / ISZ crops and the resizes,
 * before the thumbnails and the encoders), so every format - JPEG, Ultra HDR, HEIC, WebP, AVIF and the DNG's preview made from the
 * photo - carries it upright in the chosen corner. Before P628 the GL rotate pass blended a signature bitmap into the frame before
 * those crops (a zoomed shot lost it, a resize scaled it).
 */
public final class PhotoWatermark {
    private static final String TAG = "PhotoWatermark";

    private PhotoWatermark() {}

    @Nullable
    private static SharedPreferences prefs() {
        Context c = ScameraApp.getAppContext();
        return c == null ? null : PreferenceManager.getDefaultSharedPreferences(c);
    }

    /** Whether the watermark switch is on (the photo then takes the bitmap route: the streamed JPEG has no bitmap to draw on). */
    public static boolean on() {
        try {
            return com.particlesdevs.photoncamera.settings.PreferenceKeys.isShowWatermarkOn();
        } catch (RuntimeException noSettings) {
            return false;
        }
    }

    /** The facts of {@code exif} (the shot's EXIF as it is written). */
    public static WatermarkText.Info info(@Nullable ParseExif.ExifData exif) {
        WatermarkText.Info i = new WatermarkText.Info();
        if (exif == null) return i;
        i.device = com.particlesdevs.photoncamera.gallery.ui.PhotoInfo.device(exif.MAKE, exif.MODEL);
        i.focalLength = rational(exif.FOCAL_LENGTH);
        i.focal35 = (int) Math.round(PreferenceNumber.read(exif.FOCAL_LENGTH_35MM, 0));
        i.fNumber = PreferenceNumber.read(exif.F_NUMBER, Double.NaN);
        i.exposureTime = PreferenceNumber.read(exif.EXPOSURE_TIME, Double.NaN);
        i.iso = (int) Math.round(PreferenceNumber.read(exif.ISO_SPEED != null ? exif.ISO_SPEED : exif.PHOTOGRAPHIC_SENSITIVITY, 0));
        i.timeMs = exif.captureTimeMs;
        i.zone = TimeZone.getDefault();
        return i;
    }

    /** «690/100» -> 6.9; a plain number as it is; NaN for none. */
    static double rational(@Nullable String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        int slash = s.indexOf('/');
        if (slash < 0) return PreferenceNumber.read(s, Double.NaN);
        double n = PreferenceNumber.read(s.substring(0, slash), Double.NaN), d = PreferenceNumber.read(s.substring(slash + 1), Double.NaN);
        return d > 0 ? n / d : Double.NaN;
    }

    /**
     * {@code photo} with the watermark of the stored settings drawn in (the same bitmap when it is mutable, a copy otherwise); the
     * photo as it was when the switch is off, nothing is to be drawn or anything fails (a shot is never lost to its watermark).
     */
    public static Bitmap apply(@Nullable Bitmap photo, @Nullable ParseExif.ExifData exif) {
        return applyRecorded(photo, exif).photo;
    }

    /** A photo with its watermark drawn and the record that takes it off again (null: none drawn, or the pixels not kept). */
    public static final class Drawn {
        @Nullable public final Bitmap photo;
        /** The record, still being coded when it was asked for later (the camera: beside the photo's encoding). */
        @Nullable public final WatermarkPatch.Pending record;
        @Nullable public final WatermarkPainter.Plan plan;

        Drawn(@Nullable Bitmap photo, @Nullable WatermarkPatch.Pending record, @Nullable WatermarkPainter.Plan plan) {
            this.photo = photo;
            this.record = record;
            this.plan = plan;
        }

        /** The record (waits for its coding), null for none or when its coding failed (logged). */
        @Nullable
        public WatermarkPatch patch() {
            if (record == null) return null;
            try {
                return record.get();
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.e(TAG, "watermark record not kept (the watermark cannot be taken off later): " + e);
                return null;
            }
        }
    }

    /**
     * P633: {@link #apply} that also keeps the original pixels under the watermark and its spec (WatermarkPatch), which PhotoOutput
     * appends to every file of the shot, so the gallery's editor can take it off or change it.
     */
    public static Drawn applyRecorded(@Nullable Bitmap photo, @Nullable ParseExif.ExifData exif) {
        if (photo == null || !on()) return new Drawn(photo, null, null);
        try {
            return draw(photo, WatermarkSpec.from(prefs()), info(exif), new WatermarkFonts(ScameraApp.getAppContext()), true, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.e(TAG, "watermark not drawn: " + e);
            return new Drawn(photo, null, null);
        }
    }

    /**
     * The watermark of {@code spec} with the facts {@code info} drawn into {@code photo} (in place when it is mutable, else into a
     * copy, the original recycled) - the camera's drawing, which the gallery's editor uses for its copy too; {@code record}: the
     * pixels under it kept first (WatermarkArea, WatermarkPatch; a failure there costs the record only). Nothing drawn: the photo
     * as it was.
     */
    public static Drawn draw(Bitmap photo, WatermarkSpec spec, WatermarkText.Info info, WatermarkPainter.Fonts fonts, boolean record) {
        return draw(photo, spec, info, fonts, record, false);
    }

    /** {@code later}: the record's pieces are coded on a worker while the photo is encoded (WatermarkPatch.captureLater). */
    public static Drawn draw(Bitmap photo, WatermarkSpec spec, WatermarkText.Info info, WatermarkPainter.Fonts fonts, boolean record,
                             boolean later) {
        WatermarkText text = WatermarkText.of(spec, info);
        if (!text.any() && spec.layout != WatermarkSpec.Layout.FRAME) {
            Log.i(TAG, "nothing to draw: every field and the glyph off");
            return new Drawn(photo, null, null);
        }
        WatermarkPainter.Plan plan = WatermarkPainter.plan(spec, text, photo.getWidth(), photo.getHeight(), fonts);
        if (plan.empty()) return new Drawn(photo, null, null);
        Bitmap out = photo.isMutable() ? photo : photo.copy(photo.getConfig(), true);
        if (out == null) return new Drawn(photo, null, null);
        WatermarkPainter.chooseInk(plan, spec, out);
        WatermarkPatch.Pending patch = null;
        if (record && WatermarkPatch.supports(out)) {
            final long t0 = System.nanoTime();
            try {
                java.util.List<android.graphics.Rect> rects = WatermarkArea.of(plan);
                patch = WatermarkPatch.captureLater(out, rects, spec.toJson(), later);
                Log.i(TAG, "watermark record: " + rects.size() + " rects, " + WatermarkArea.pixels(rects) + " px kept"
                        + (later ? " (coded beside the encoding)" : "") + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.e(TAG, "watermark record not kept (the watermark cannot be taken off later): " + e);
            }
        }
        WatermarkPainter.draw(new android.graphics.Canvas(out), plan, spec, fonts);
        Log.i(TAG, "watermark " + spec.layout + " " + spec.position + " on " + out.getWidth() + "x" + out.getHeight()
                + " at " + plan.bounds.toShortString() + (plan.whiteInk ? " white" : " black") + " lines " + WatermarkPainter.texts(plan).size());
        if (out != photo) photo.recycle();
        return new Drawn(out, patch, plan);
    }
}
