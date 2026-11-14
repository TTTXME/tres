package com.scamera.app.processing.watermark;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.scamera.app.api.ParseExif;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.util.Log;

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

    /** Whether the watermark switch is on (P642: a streamed JPEG gets it through {@link #applyRecordedInto}). */
    public static boolean on() {
        try {
            return com.scamera.app.settings.PreferenceKeys.isShowWatermarkOn();
        } catch (RuntimeException noSettings) {
            return false;
        }
    }

    /** The facts of {@code exif} (the shot's EXIF as it is written). */
    public static WatermarkText.Info info(@Nullable ParseExif.ExifData exif) {
        WatermarkText.Info i = new WatermarkText.Info();
        if (exif == null) return i;
        i.device = com.scamera.app.gallery.ui.PhotoInfo.device(exif.make, exif.model);
        i.focalLength = rational(exif.focalLength);
        i.focal35 = (int) Math.round(PreferenceNumber.read(exif.focal35mm, 0));
        i.fNumber = PreferenceNumber.read(exif.fNumber, Double.NaN);
        i.exposureTime = PreferenceNumber.read(exif.exposureTime, Double.NaN);
        i.iso = (int) Math.round(PreferenceNumber.read(exif.isoSpeed != null ? exif.isoSpeed : exif.isoValue, 0));
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

    /** P642: the watermark's rectangle may take this share of a streamed photo at most (a frame around it: the bitmap route). */
    static final int STREAM_AREA_DIVISOR = 4;

    /** P642: a streamed photo whose watermark is left to the bitmap route (not an error: logged, the photo takes that route). */
    public static final class NotStreamed extends RuntimeException {
        NotStreamed(String why) { super(why); }
    }

    /** P642: what {@link #drawInto} did: the record and the plan as {@link Drawn}, and how to take the drawing back. */
    public static final class DrawnInto {
        @Nullable public final WatermarkPatch.Pending record;
        @Nullable public final WatermarkPainter.Plan plan;
        /** Null: nothing was drawn. */
        @Nullable public final RgbaRows.Undo undo;

        DrawnInto(@Nullable WatermarkPatch.Pending record, @Nullable WatermarkPainter.Plan plan, @Nullable RgbaRows.Undo undo) {
            this.record = record;
            this.plan = plan;
            this.undo = undo;
        }
    }

    /**
     * P642: {@link #applyRecorded} for a streamed JPEG - the stored settings' watermark drawn into the rows about to be encoded
     * ({@link #drawInto}); nothing drawn with the switch off. Throws {@link NotStreamed} (or what the drawing threw) with the
     * rows as they were: the photo then takes the bitmap route, which draws it as before.
     */
    public static DrawnInto applyRecordedInto(RgbaRows photo, @Nullable ParseExif.ExifData exif) {
        if (!on()) return new DrawnInto(null, null, null);
        return drawInto(photo, WatermarkSpec.from(prefs()), info(exif), new WatermarkFonts(ScameraApp.getAppContext()), true, true);
    }

    /**
     * P713: the first row {@link #applyRecordedInto} may read or change on a {@code width} x {@code height} photo ({@link #touched}'s
     * top; {@code height}: it draws nothing; 0 when it cannot be told) - the rows above go to the streamed JPEG's encoder early.
     */
    public static int firstTouchedRow(int width, int height, @Nullable ParseExif.ExifData exif) {
        if (!on()) return height;
        try {
            final android.graphics.Rect box = touched(WatermarkSpec.from(prefs()), info(exif), new WatermarkFonts(ScameraApp.getAppContext()),
                    width, height);
            return box == null ? height : Math.max(0, box.top);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * P642: the rectangle of a {@code width} x {@code height} photo {@link #draw} reads and changes for {@code spec}: WatermarkArea's
     * rectangles (every pixel the drawing may change and the record keeps) and the points «Авто» reads; null: it draws nothing.
     */
    @Nullable
    static android.graphics.Rect touched(WatermarkSpec spec, WatermarkText.Info info, WatermarkPainter.Fonts fonts, int width, int height) {
        WatermarkText text = WatermarkText.of(spec, info);
        if (!text.any() && spec.layout != WatermarkSpec.Layout.FRAME) return null;
        WatermarkPainter.Plan plan = WatermarkPainter.plan(spec, text, width, height, fonts);
        if (plan.empty()) return null;
        final android.graphics.Rect box = new android.graphics.Rect();
        for (android.graphics.Rect r : WatermarkArea.of(plan)) box.union(r);
        final android.graphics.Rect sampled = spec.colour == WatermarkSpec.Colour.AUTO ? WatermarkPainter.sampled(plan) : null;
        if (sampled != null) box.union(sampled);
        return box.isEmpty() || !box.intersect(0, 0, width, height) ? null : box;
    }

    /**
     * P642: {@link #draw} for a photo that has no bitmap (the streamed JPEG's rows): {@link #draw} itself, with its record, on a
     * window of the rows (RgbaRows.window: a bitmap of the photo's size holding the photo in {@link #touched} only), the
     * rectangle written back - the bytes the bitmap route's bitmap gets there, and the same record. A watermark whose rectangle
     * is more than 1 / {@link #STREAM_AREA_DIVISOR} of the photo (the frame layout) is not streamed.
     */
    public static DrawnInto drawInto(RgbaRows photo, WatermarkSpec spec, WatermarkText.Info info, WatermarkPainter.Fonts fonts,
                                     boolean record, boolean later) {
        final android.graphics.Rect box = touched(spec, info, fonts, photo.width, photo.height);
        if (box == null) {
            Log.i(TAG, "nothing to draw into the streamed JPEG's rows");
            return new DrawnInto(null, null, null);
        }
        if ((long) box.width() * box.height() * STREAM_AREA_DIVISOR > (long) photo.width * photo.height)
            throw new NotStreamed("the watermark's rectangle " + box.toShortString() + " is over 1/" + STREAM_AREA_DIVISOR + " of "
                    + photo.width + "x" + photo.height);
        final byte[] before = photo.bytes(box);
        final Bitmap window = photo.window();
        try {
            photo.load(window, box);
            final Drawn drawn = draw(window, spec, info, fonts, record, later);
            if (drawn.photo != window) throw new IllegalStateException("the watermark was drawn on a copy of the window");
            if (drawn.plan == null) return new DrawnInto(null, null, null);
            try {
                photo.store(window, box);
            } catch (RuntimeException e) {
                photo.write(box, before); // never half a watermark in the rows the bitmap route may still read
                throw e;
            }
            Log.i(TAG, "watermark drawn into the streamed JPEG's rows through a window of " + box.width() + "x" + box.height() + " px");
            return new DrawnInto(drawn.record, drawn.plan, photo.undo(box, before));
        } finally {
            window.recycle();
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
