package com.scamera.app.ui.camera;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.View;

import androidx.annotation.Nullable;

import com.scamera.app.capture.CaptureController;
import com.scamera.app.capture.XiaomiTeleZoom;
import com.scamera.app.processing.parameters.ExposureIndex;
import com.scamera.app.processing.parameters.IsoExpoSelector;
import com.scamera.app.ui.camera.views.viewfinder.SurfaceViewOverViewfinder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The viewfinder's information line (exposure, ISO, lens, focus, white balance, OIS, tripod), its live histogram and the
 * debug field names of «Данные AF» 3. The text is built from a preview result; the camera screen draws it on the overlay.
 */
final class ViewfinderHud {
    /** The overlay's line is redrawn at most this often: a steady 6.6 Hz instead of every frame's jitter. */
    static final long LINE_PERIOD_MS = 150;
    /** The histogram samples the viewfinder at most this often (8.3 Hz). */
    static final long HISTOGRAM_PERIOD_MS = 120;
    private static final int HIST_W = 128, HIST_H = 96, HIST_BINS = 64;

    private long lineAtMs;
    private long histogramAtMs;
    private Bitmap histogramFrame;
    private final int[] histogramPixels = new int[HIST_W * HIST_H];
    private final int[][] histogramBins = new int[3][HIST_BINS];

    /** The HUD runnable is posted at most this often (an AF change always goes). */
    static final long POST_PERIOD_MS = 50;

    /**
     * P705: whether a preview result posts the HUD runnable to the main thread: an AF change always (the focus ring's colour);
     * otherwise at most every {@link #POST_PERIOD_MS}, and only when it has something to do - «Данные AF» shown ({@code afData}
     * above 0), the controls turned (the horizon and the overlay follow), or the overlay still holds a drawing to clear.
     */
    static boolean postDue(long sinceLastMs, boolean afChanged, int afData, boolean orientationChanged, boolean overlayDrawn) {
        if (afChanged) return true;
        if (sinceLastMs < POST_PERIOD_MS) return false;
        return afData > 0 || orientationChanged || overlayDrawn;
    }

    /** The line is due again (and is taken now) when {@link #LINE_PERIOD_MS} passed since the last one. */
    boolean lineDue() {
        final long now = SystemClock.uptimeMillis();
        if (now - lineAtMs < LINE_PERIOD_MS) return false;
        lineAtMs = now;
        return true;
    }

    /**
     * Exposure and ISO as the shot will take them: in the ZSL routes the viewfinder's own (the result's, else the controller's
     * last), otherwise the manual selector's pair with its limit marks («UNLMT» tripod, «LMT» limited, «&gt;LMT» manual over the
     * limit). {@code isoPrefix} goes before the ISO number.
     */
    static String[] exposureAndIso(CaptureResult result, @Nullable CaptureController controller, String isoPrefix) {
        if (controller != null && controller.isZslMode()) {
            final Long ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            final long shownNs = ns != null ? ns : controller.mPreviewExposureTime;
            final int shownIso = iso != null ? iso : controller.mPreviewIso;
            return new String[]{ExposureIndex.sec2string(ExposureIndex.time2sec(shownNs)) + "s", isoPrefix + shownIso};
        }
        final IsoExpoSelector.ExpoPair pair = IsoExpoSelector.meteredPair(-1, controller);
        final String shutterMark = pair.isShutterTripodBypassed ? " · UNLMT"
                : pair.isShutterLimited ? " · LMT" : pair.isShutterManualOverLimit ? " · >LMT" : "";
        final String isoMark = pair.isIsoLimited ? " · LMT" : pair.isIsoManualOverLimit ? " · >LMT" : "";
        return new String[]{pair.timeText() + "s" + shutterMark, isoPrefix + pair.iso + isoMark};
    }

    /**
     * «24mm · f/1.8»: the 35 mm equivalent of the lens at the result's zoom (the Xiaomi logical tele: its dial's focal length)
     * and the aperture the result reports, else the lens's first one.
     */
    static String lens(CaptureResult result, @Nullable CameraCharacteristics lens) {
        int equivalentMm = 24;
        float aperture = 1.8f;
        if (lens != null) {
            final float[] focals = lens.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            final float focal = focals != null && focals.length > 0 ? focals[0] : 4.75f;
            final SizeF sensor = lens.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (sensor != null && sensor.getWidth() > 0)
                equivalentMm = Math.round((36.0f / sensor.getWidth()) * focal * zoomOf(result, lens));
            final XiaomiTeleZoom.Plan xiaomi = XiaomiTeleZoom.last();
            if (xiaomi != null && xiaomi.logical) equivalentMm = Math.round(xiaomi.mm); // P41b: the main camera's zoom scale
            final Float reported = result.get(CaptureResult.LENS_APERTURE);
            if (reported != null && reported > 0.0f) aperture = reported;
            else {
                final float[] apertures = lens.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                if (apertures != null && apertures.length > 0) aperture = apertures[0];
            }
        }
        return equivalentMm + "mm · f/" + String.format(Locale.ROOT, "%.1f", aperture);
    }

    /** The result's zoom: its zoom ratio (Android 11+), else the active array over the crop's width; 1 when unknown. */
    private static float zoomOf(CaptureResult result, CameraCharacteristics lens) {
        if (Build.VERSION.SDK_INT >= 30) {
            final Float ratio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
            return ratio != null ? ratio : 1.0f;
        }
        final Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
        final Rect active = lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        return crop != null && active != null && crop.width() > 0 ? (float) active.width() / crop.width() : 1.0f;
    }

    /** «MF · 1.2m» / «MF · ∞» for a hand-set focus, else the AF mode (AF-S, AF-Macro, AF-C) with its state (SCAN, LOCK, FAIL, LOST). */
    static String focus(@Nullable Integer afMode, @Nullable Integer afState, @Nullable Float diopters, boolean manualPanel) {
        if (manualPanel || afMode != null && afMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            if (diopters == null || diopters == 0.0f) return "MF · ∞";
            return String.format(Locale.ROOT, "MF · %.1fm", 1.0f / diopters);
        }
        final String mode = afMode != null && afMode == CaptureRequest.CONTROL_AF_MODE_AUTO ? "AF-S"
                : afMode != null && afMode == CaptureRequest.CONTROL_AF_MODE_MACRO ? "AF-Macro" : "AF-C";
        if (afState == null) return mode;
        final int state = afState;
        final String tag = state == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN || state == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ? "SCAN"
                : state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ? "LOCK"
                : state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ? "FAIL"
                : state == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ? "LOST" : null;
        return tag == null ? mode : mode + " · " + tag;
    }

    /**
     * «AWB · 5200K» («MWB» for a manual balance): an approximate colour temperature from the neutral point (the result's, else
     * the controller's last), 3000 K x (B / R)^0.75 in 100 K steps within 2000 - 10000 K; the prefix alone when unknown.
     */
    static String whiteBalance(CaptureResult result, @Nullable Rational[] lastNeutral) {
        final Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE);
        final String prefix = awb != null && awb == CaptureRequest.CONTROL_AWB_MODE_OFF ? "MWB" : "AWB";
        Rational[] neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if ((neutral == null || neutral.length < 3) && lastNeutral != null && lastNeutral.length >= 3) neutral = lastNeutral;
        if (neutral == null || neutral.length < 3) return prefix;
        final double red = neutral[0].doubleValue();
        if (red <= 0.001) return prefix;
        final double kelvin = 3000.0 * Math.pow(neutral[2].doubleValue() / red, 0.75);
        final int stepped = Math.max(2000, Math.min(10000, (int) (Math.round(kelvin / 100.0) * 100)));
        return prefix + " · " + stepped + "K";
    }

    /**
     * {OIS present, OIS working}: present when the lens lists OIS or the camera reports it on (P95: requested where Camera2 lists
     * OFF only); working as the module's setting says (2 off, 1 off on a tripod) unless the camera reports its state (P316).
     */
    static boolean[] ois(@Nullable CameraCharacteristics lens, @Nullable CaptureController controller, boolean tripod) {
        boolean present = false, working = true;
        if (lens == null) return new boolean[]{false, true};
        present = CaptureController.isOisSupported(lens.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
        final CaptureResult last = CaptureController.mPreviewCaptureResult;
        final Integer reported = last != null ? last.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) : null;
        final boolean reportedOn = reported != null && reported == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON;
        if (reportedOn) present = true;
        if (present && controller != null) {
            final int setting = controller.oisMode;
            working = setting != 2 && (setting != 1 || !tripod);
            if (reported != null) working = reportedOn;
        }
        return new boolean[]{present, working};
    }

    /**
     * The live histogram: at most every {@link #HISTOGRAM_PERIOD_MS} a 128 x 96 copy of the viewfinder (Android 8+), binned
     * on {@code pool} (every second pixel, 64 bins a channel, square-root compressed) and handed to the overlay.
     */
    void sampleHistogram(@Nullable View viewfinder, @Nullable SurfaceViewOverViewfinder overlay, Executor pool) {
        if (viewfinder == null || overlay == null || Build.VERSION.SDK_INT < 26) return;
        final long now = SystemClock.uptimeMillis();
        if (now - histogramAtMs < HISTOGRAM_PERIOD_MS) return;
        histogramAtMs = now;
        if (histogramFrame == null) histogramFrame = Bitmap.createBitmap(HIST_W, HIST_H, Bitmap.Config.ARGB_8888);
        final Handler callback = overlay.getHandler() != null ? overlay.getHandler() : new Handler(Looper.getMainLooper());
        try {
            android.view.PixelCopy.request((android.view.SurfaceView) viewfinder, histogramFrame, status -> {
                if (status == android.view.PixelCopy.SUCCESS) pool.execute(() -> binHistogram(overlay));
            }, callback);
        } catch (Exception ignored) {
            // the viewfinder's surface is not there (yet)
        }
    }

    private void binHistogram(SurfaceViewOverViewfinder overlay) {
        final Bitmap frame = histogramFrame;
        if (frame == null || frame.isRecycled()) return;
        final int w = frame.getWidth(), h = frame.getHeight();
        frame.getPixels(histogramPixels, 0, w, 0, 0, w, h);
        for (int[] channel : histogramBins) Arrays.fill(channel, 0);
        final int[] red = histogramBins[0], green = histogramBins[1], blue = histogramBins[2];
        final int pixels = w * h;
        for (int i = 0; i < pixels; i += 2) {
            final int argb = histogramPixels[i];
            red[((argb >> 16) & 0xFF) * HIST_BINS / 256]++;
            green[((argb >> 8) & 0xFF) * HIST_BINS / 256]++;
            blue[(argb & 0xFF) * HIST_BINS / 256]++;
        }
        int peak = 1;
        for (int[] channel : histogramBins)
            for (int bin = 0; bin < HIST_BINS; bin++) {
                channel[bin] = (int) Math.sqrt(channel[bin]);
                peak = Math.max(peak, channel[bin]);
            }
        final int top = peak;
        overlay.post(() -> overlay.showHistogram(histogramBins, top, HIST_BINS));
    }

    /**
     * A metering region (sensor coordinates, landscape) on the viewfinder view (portrait, mirrored across): the pixel array's
     * size scales it, the still reader's when the lens has none; an empty rectangle without a still reader.
     */
    static RectF screenRect(MeteringRectangle region, CaptureController controller, View viewfinder) {
        if (controller.mImageReaderPreview == null) return new RectF();
        Size array = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (array == null) array = new Size(controller.mImageReaderPreview.getWidth(), controller.mImageReaderPreview.getHeight());
        final float viewW = viewfinder.getWidth(), viewH = viewfinder.getHeight();
        final float fromRight = ((float) region.getY() / array.getHeight()) * viewW;
        final float top = ((float) region.getX() / array.getWidth()) * viewH;
        final float across = ((float) region.getHeight() / array.getHeight()) * viewW;
        final float along = ((float) region.getWidth() / array.getWidth()) * viewH;
        return new RectF(viewW - fromRight - across, top, viewW - fromRight, top + along);
    }

    /** CameraMetadata's constant names by prefix and value, read once (the debug table asks for them every frame). */
    private static final Map<String, String> sConstantNames = new HashMap<>();

    /** «FOCUSED_LOCKED(4)» for prefix «CONTROL_AF_STATE_» and 4; "" for null or a value without a constant. */
    static synchronized String constantName(String prefix, @Nullable Integer value) {
        if (value == null) return "";
        final String key = prefix + '#' + value;
        String name = sConstantNames.get(key);
        if (name == null) {
            name = "";
            for (Field field : CameraMetadata.class.getDeclaredFields()) {
                if (!field.getName().startsWith(prefix) || !Modifier.isStatic(field.getModifiers())) continue;
                try {
                    if (field.getInt(null) == value) {
                        name = field.getName().replace(prefix, "").concat("(" + value + ")");
                        break;
                    }
                } catch (Exception ignored) {
                    // not an int constant
                }
            }
            sConstantNames.put(key, name);
        }
        return name;
    }
}
