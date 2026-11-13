package com.scamera.app.api;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import com.scamera.app.util.Log;
import androidx.exifinterface.media.ExifInterface;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.parameters.IsoExpoSelector;
import com.scamera.app.processing.render.Parameters;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import static android.hardware.camera2.CaptureResult.*;
import static androidx.exifinterface.media.ExifInterface.*;

/**
 * The EXIF of a saved photo: what a shot's result says (its exposure, ISO, lens, time), synchronised with the processing's
 * parameters, and written into the file with ExifInterface.
 */
public class ParseExif {
    public static final SimpleDateFormat sFormatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
    private static final String TAG = "ParseExif";

    static {
        sFormatter.setTimeZone(TimeZone.getDefault());
    }

    /** Nanoseconds as EXIF's decimal seconds (the double's own text, e.g. "0.01"). */
    public static String getTime(long exposureTime) {
        return String.valueOf(exposureTime / (double) 1000000000L);
    }

    /** The result's value as text, "" when it has none. */
    public static String resultget(CaptureResult res, Key<?> key) {
        final Object value = res.get(key);
        return value != null ? value.toString() : "";
    }

    /** The EXIF Orientation of a camera rotation (0 / 90 / 180 / 270 degrees; anything else: normal). */
    static int exifOrientation(int rotation) {
        switch (rotation) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    public static ExifData parse(CaptureResult result, CaptureRequest request) {
        return parse(result, request, System.currentTimeMillis());
    }

    /**
     * P618: {@code captureTimeMs} is the shot's wall clock (the moment ImageSaver named its files): DateTime, DateTimeOriginal /
     * Digitized, SubSecTime* and OffsetTime* (the processing start, a queued shot's minutes later, stood here, without a zone).
     */
    public static ExifData parse(CaptureResult result, CaptureRequest request, long captureTimeMs) {
        final ExifData data = new ExifData();
        // P319: only logged here. A queued shot can start after the camera screen is gone (no controller): no NPE for it.
        final com.scamera.app.capture.CaptureController controller = ScameraApp.getCaptureController();
        final int rotation = controller != null ? controller.cameraRotation : 0;
        Log.d(TAG, "rotation: gravity " + ScameraApp.getGravity().getRotation() + ", sensor "
                + (controller != null ? controller.mSensorOrientation : -1) + ", camera " + rotation
                + " -> orientation " + exifOrientation(rotation));
        final Integer sensitivity = result.get(SENSOR_SENSITIVITY);
        final int iso = sensitivity != null ? (int) (sensitivity * IsoExpoSelector.getMPY()) : 100;
        Log.d(TAG, "sensitivity:" + iso);
        iso(data, iso);
        data.F_NUMBER = resultget(result, LENS_APERTURE);
        final Float focal = result.get(LENS_FOCAL_LENGTH);
        if (focal != null) data.FOCAL_LENGTH = Math.round(100 * Double.parseDouble(focal.toString())) + "/100";
        // P618: ApertureValue is the APEX 2 log2(N), not the f-number
        final Float lensAperture = result.get(LENS_APERTURE);
        data.APERTURE_VALUE = lensAperture == null ? null : apertureValue(lensAperture);
        final Long exposureNs = result.get(SENSOR_EXPOSURE_TIME);
        if (exposureNs != null) data.EXPOSURE_TIME = getTime(exposureNs);
        final Long frameNs = result.get(SENSOR_FRAME_DURATION);
        if (frameNs != null) data.FRAME_DURATION = getTime(frameNs);
        final Integer awb = result.get(CONTROL_AWB_MODE);
        if (awb != null) data.WHITE_BALANCE = awb == CONTROL_AWB_MODE_AUTO ? "0" : "1";
        time(data, captureTimeMs);
        data.COMPRESSION = COMPRESSION_JPEG;
        data.COLOR_SPACE = "sRGB";
        data.EXIF_VERSION = "0231";
        return data;
    }

    /** EXIF Compression (259) of a JPEG file: 6; the encoder quality used to be written here (98). */
    public static final String COMPRESSION_JPEG = "6";

    /** P618: ISO as PhotographicSensitivity (a SHORT: 65535 above it) and ISOSpeed (SensitivityType 3 = ISO speed). */
    static void iso(ExifData data, int iso) {
        data.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        data.PHOTOGRAPHIC_SENSITIVITY = iso > 0 ? String.valueOf(com.scamera.app.processing.ShotExif.iso16(iso)) : null;
        data.ISO_SPEED = iso > 0 ? String.valueOf(iso) : null;
    }

    /** P618: ApertureValue (APEX) of the f-number {@code fNumber}, as a rational; null for none. */
    static String apertureValue(double fNumber) {
        final double apex = com.scamera.app.processing.ShotExif.apex(fNumber);
        return apex >= 0 ? com.scamera.app.processing.ShotExif.rationalText(
                com.scamera.app.processing.ShotExif.rational(apex, 1000)) : null;
    }

    /** P618: every time tag of {@code data} for the moment {@code ms} in the phone's zone. */
    static void time(ExifData data, long ms) {
        final TimeZone zone = TimeZone.getDefault();
        data.captureTimeMs = ms;
        data.DATETIME = com.scamera.app.processing.ShotExif.dateTime(ms, zone);
        data.SUBSEC_TIME = com.scamera.app.processing.ShotExif.subSec(ms);
        data.OFFSET_TIME = com.scamera.app.processing.ShotExif.offset(ms, zone);
    }

    public static void syncWithParameters(ExifData data, Parameters parameters) {
        // P618: the facts the shot's DNG records too (ShotExif.of): the same time, ISO, exposure and lens. The time only when
        // the shot has one (Parameters.captureTimeMs, set by ScamProcessor from parse's): never this sync's clock
        final com.scamera.app.processing.ShotExif.Facts f = com.scamera.app.processing.ShotExif.of(
                parameters, parameters.photoFieldZoom, data.MAKE, data.MODEL);
        if (parameters.captureTimeMs > 0) time(data, parameters.captureTimeMs);
        iso(data, f.iso);
        // rounded, as the DNG's: the cast wrote 9.11 mm for the float 9.12 (9.1199998)
        data.FOCAL_LENGTH = f.focalLength > 0 ? Math.round(100 * f.focalLength) + "/100" : null;
        data.F_NUMBER = f.fNumber > 0 ? String.valueOf((float) f.fNumber) : null;
        data.APERTURE_VALUE = f.fNumber > 0 ? apertureValue(f.fNumber) : null;
        data.EXPOSURE_TIME = getTime((long) (parameters.exposureTime * 1000000000L));
        data.FOCAL_LENGTH_35MM = f.focal35 > 0 ? String.valueOf(f.focal35) : null;
        data.LENS_MAKE = f.make;
        data.LENS_MODEL = f.lensModel();
        data.LENS_SPECIFICATION = com.scamera.app.processing.ShotExif.rationalText(
                com.scamera.app.processing.ShotExif.lensSpecificationRationals(f.lensSpecification()));
        // P59b: the merge route the shot went through, for the gallery's «Склейка» row (GalleryFormat.routeOf)
        data.IMAGE_DESCRIPTION = parameters.description() + routeLine();
    }

    /**
     * P380: the description again once the shot's film is known (Parameters.filmRecipe, after PostPipeline.takeFilm): with a film it
     * gains the recipe line; without one it is left as {@link #syncWithParameters} wrote it.
     */
    public static void describeFilm(ExifData data, Parameters parameters) {
        if (parameters.filmRecipe == null || parameters.filmRecipe.isEmpty()) return;
        data.IMAGE_DESCRIPTION = parameters.description() + routeLine();
    }

    /** "\n Route=SHDR": the merge every shot goes through (P129; older photos carry «Hybrid» / «SCAM HDR»). */
    public static String routeLine() {
        return "\n Route=SHDR";
    }

    /** Writes {@code data} into the file's EXIF (not saved: the caller saves); null when the file cannot be read. */
    public static ExifInterface setAllAttributes(File file, ExifData data) {
        final ExifInterface exif;
        try {
            exif = new ExifInterface(file);
        } catch (IOException e) {
            Log.w(TAG, "EXIF of " + file + " not readable: " + e);
            return null;
        }
        exif.setAttribute(TAG_SENSITIVITY_TYPE, data.SENSITIVITY_TYPE);
        exif.setAttribute(TAG_PHOTOGRAPHIC_SENSITIVITY, data.PHOTOGRAPHIC_SENSITIVITY);
        putIfSet(exif, data.ISO_SPEED, TAG_ISO_SPEED);
        exif.setAttribute(TAG_F_NUMBER, data.F_NUMBER);
        exif.setAttribute(TAG_FOCAL_LENGTH, data.FOCAL_LENGTH);
        // P618: no Copyright: it said "SCAMERA" (the photographer's field); the app is the Software
        exif.setAttribute(TAG_SOFTWARE, data.SOFTWARE);
        exif.setAttribute(TAG_APERTURE_VALUE, data.APERTURE_VALUE);
        exif.setAttribute(TAG_EXPOSURE_TIME, data.EXPOSURE_TIME);
        exif.setAttribute(ExifInterface.TAG_DATETIME, data.DATETIME);
        // P618: the shot's time as the DNG records it: when it was taken, its milliseconds and zone (the gallery's «Дата съёмки»
        // reads DateTimeOriginal, which no photo had)
        putIfSet(exif, data.DATETIME, TAG_DATETIME_ORIGINAL, TAG_DATETIME_DIGITIZED);
        putIfSet(exif, data.SUBSEC_TIME, TAG_SUBSEC_TIME, TAG_SUBSEC_TIME_ORIGINAL, TAG_SUBSEC_TIME_DIGITIZED);
        putIfSet(exif, data.OFFSET_TIME, TAG_OFFSET_TIME, TAG_OFFSET_TIME_ORIGINAL, TAG_OFFSET_TIME_DIGITIZED);
        putIfSet(exif, data.FOCAL_LENGTH_35MM, TAG_FOCAL_LENGTH_IN_35MM_FILM);
        putIfSet(exif, data.LENS_MAKE, TAG_LENS_MAKE);
        putIfSet(exif, data.LENS_MODEL, TAG_LENS_MODEL);
        putIfSet(exif, data.LENS_SPECIFICATION, TAG_LENS_SPECIFICATION);
        final String[][] always = {{TAG_MODEL, data.MODEL}, {TAG_MAKE, data.MAKE}, {TAG_COMPRESSION, data.COMPRESSION},
                {TAG_COLOR_SPACE, data.COLOR_SPACE}, {TAG_EXIF_VERSION, data.EXIF_VERSION}, {TAG_IMAGE_DESCRIPTION, data.IMAGE_DESCRIPTION}};
        for (String[] tag : always) exif.setAttribute(tag[0], tag[1]);
        putIfSet(exif, data.WHITE_BALANCE, TAG_WHITE_BALANCE);
        return exif;
    }

    /** The value into each of the tags, only when there is one. */
    private static void putIfSet(ExifInterface exif, @androidx.annotation.Nullable String value, String... tags) {
        if (value == null) return;
        for (String tag : tags) exif.setAttribute(tag, value);
    }

    public static class ExifData {
        // P196: the phone's market name («vivo X200 Ultra») when the system has one, Build.MODEL otherwise (DeviceName);
        // every format takes these through setAllAttributes (JPEG, Ultra HDR, HEIC 8 / 10-bit, WebP, AVIF)
        public final String MODEL = com.scamera.app.util.DeviceName.model();
        public final String MAKE = com.scamera.app.util.DeviceName.make();
        /** P618: the app's version, as the DNG's Software (DngMetadata.software); Copyright ("SCAMERA") is gone. */
        public final String SOFTWARE = com.scamera.app.processing.DngMetadata.software();
        /** P618: the shot's wall clock (ms) the time tags are of; 0 = not set. */
        public long captureTimeMs;
        public String SUBSEC_TIME;
        public String OFFSET_TIME;
        public String ISO_SPEED;
        public String FOCAL_LENGTH_35MM;
        public String LENS_MAKE;
        public String LENS_MODEL;
        public String LENS_SPECIFICATION;
        public String SENSITIVITY_TYPE;
        public String APERTURE_VALUE;
        public String COMPRESSION;
        public String COLOR_SPACE;
        public String EXIF_VERSION;
        public String IMAGE_DESCRIPTION;
        public String DATETIME;
        public String EXPOSURE_TIME;
        public String F_NUMBER;
        public String FOCAL_LENGTH;
        public String PHOTOGRAPHIC_SENSITIVITY;
        public String WHITE_BALANCE;
        public String FRAME_DURATION;
    }
}