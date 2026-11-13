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
    private static final String TAG = "ParseExif";

    /** Nanoseconds as EXIF's decimal seconds (the double's own text, e.g. "0.01"). */
    static String seconds(long nanos) {
        return String.valueOf(nanos / 1e9);
    }

    /** The result's value as text, "" when it has none. */
    static String text(CaptureResult result, Key<?> key) {
        final Object value = result.get(key);
        return value == null ? "" : value.toString();
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
        final int iso = sensitivity != null ? (int) (sensitivity * IsoExpoSelector.isoMultiplier()) : 100;
        Log.d(TAG, "sensitivity:" + iso);
        iso(data, iso);
        data.fNumber = text(result, LENS_APERTURE);
        final Float focal = result.get(LENS_FOCAL_LENGTH);
        if (focal != null) data.focalLength = Math.round(100 * Double.parseDouble(focal.toString())) + "/100";
        // P618: ApertureValue is the APEX 2 log2(N), not the f-number
        final Float lensAperture = result.get(LENS_APERTURE);
        data.apertureApex = lensAperture == null ? null : apertureValue(lensAperture);
        final Long exposureNs = result.get(SENSOR_EXPOSURE_TIME);
        if (exposureNs != null) data.exposureTime = seconds(exposureNs);
        final Long frameNs = result.get(SENSOR_FRAME_DURATION);
        if (frameNs != null) data.frameDuration = seconds(frameNs);
        final Integer awb = result.get(CONTROL_AWB_MODE);
        if (awb != null) data.whiteBalance = awb == CONTROL_AWB_MODE_AUTO ? "0" : "1";
        time(data, captureTimeMs);
        data.compression = COMPRESSION_JPEG;
        data.colorSpace = "sRGB";
        data.exifVersion = "0231";
        return data;
    }

    /** EXIF Compression (259) of a JPEG file: 6; the encoder quality used to be written here (98). */
    public static final String COMPRESSION_JPEG = "6";

    /** P618: ISO as PhotographicSensitivity (a SHORT: 65535 above it) and ISOSpeed (SensitivityType 3 = ISO speed). */
    static void iso(ExifData data, int iso) {
        data.sensitivityType = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        data.isoValue = iso > 0 ? String.valueOf(com.scamera.app.processing.ShotExif.iso16(iso)) : null;
        data.isoSpeed = iso > 0 ? String.valueOf(iso) : null;
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
        data.dateTime = com.scamera.app.processing.ShotExif.dateTime(ms, zone);
        data.subSecTime = com.scamera.app.processing.ShotExif.subSec(ms);
        data.offsetTime = com.scamera.app.processing.ShotExif.offset(ms, zone);
    }

    public static void syncWithParameters(ExifData data, Parameters parameters) {
        // P618: the facts the shot's DNG records too (ShotExif.of): the same time, ISO, exposure and lens. The time only when
        // the shot has one (Parameters.captureTimeMs, set by ScamProcessor from parse's): never this sync's clock
        final com.scamera.app.processing.ShotExif.Facts f = com.scamera.app.processing.ShotExif.of(
                parameters, parameters.photoFieldZoom, data.make, data.model);
        if (parameters.captureTimeMs > 0) time(data, parameters.captureTimeMs);
        iso(data, f.iso);
        // rounded, as the DNG's: the cast wrote 9.11 mm for the float 9.12 (9.1199998)
        data.focalLength = f.focalLength > 0 ? Math.round(100 * f.focalLength) + "/100" : null;
        data.fNumber = f.fNumber > 0 ? String.valueOf((float) f.fNumber) : null;
        data.apertureApex = f.fNumber > 0 ? apertureValue(f.fNumber) : null;
        data.exposureTime = seconds((long) (parameters.exposureTime * 1000000000L));
        data.focal35mm = f.focal35 > 0 ? String.valueOf(f.focal35) : null;
        data.lensMake = f.make;
        data.lensModel = f.lensModel();
        data.lensSpec = com.scamera.app.processing.ShotExif.rationalText(
                com.scamera.app.processing.ShotExif.lensSpecificationRationals(f.lensSpecification()));
        // P59b: the merge route the shot went through, for the gallery's «Склейка» row (GalleryFormat.routeOf)
        data.description = parameters.description() + routeLine();
    }

    /**
     * P380: the description again once the shot's film is known (Parameters.filmRecipe, after PostPipeline.takeFilm): with a film it
     * gains the recipe line; without one it is left as {@link #syncWithParameters} wrote it.
     */
    public static void describeFilm(ExifData data, Parameters parameters) {
        if (parameters.filmRecipe == null || parameters.filmRecipe.isEmpty()) return;
        data.description = parameters.description() + routeLine();
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
        exif.setAttribute(TAG_SENSITIVITY_TYPE, data.sensitivityType);
        exif.setAttribute(TAG_PHOTOGRAPHIC_SENSITIVITY, data.isoValue);
        putIfSet(exif, data.isoSpeed, TAG_ISO_SPEED);
        exif.setAttribute(TAG_F_NUMBER, data.fNumber);
        exif.setAttribute(TAG_FOCAL_LENGTH, data.focalLength);
        // P618: no Copyright: it said "SCAMERA" (the photographer's field); the app is the Software
        exif.setAttribute(TAG_SOFTWARE, data.software);
        exif.setAttribute(TAG_APERTURE_VALUE, data.apertureApex);
        exif.setAttribute(TAG_EXPOSURE_TIME, data.exposureTime);
        exif.setAttribute(ExifInterface.TAG_DATETIME, data.dateTime);
        // P618: the shot's time as the DNG records it: when it was taken, its milliseconds and zone (the gallery's «Дата съёмки»
        // reads DateTimeOriginal, which no photo had)
        putIfSet(exif, data.dateTime, TAG_DATETIME_ORIGINAL, TAG_DATETIME_DIGITIZED);
        putIfSet(exif, data.subSecTime, TAG_SUBSEC_TIME, TAG_SUBSEC_TIME_ORIGINAL, TAG_SUBSEC_TIME_DIGITIZED);
        putIfSet(exif, data.offsetTime, TAG_OFFSET_TIME, TAG_OFFSET_TIME_ORIGINAL, TAG_OFFSET_TIME_DIGITIZED);
        putIfSet(exif, data.focal35mm, TAG_FOCAL_LENGTH_IN_35MM_FILM);
        putIfSet(exif, data.lensMake, TAG_LENS_MAKE);
        putIfSet(exif, data.lensModel, TAG_LENS_MODEL);
        putIfSet(exif, data.lensSpec, TAG_LENS_SPECIFICATION);
        final String[][] always = {{TAG_MODEL, data.model}, {TAG_MAKE, data.make}, {TAG_COMPRESSION, data.compression},
                {TAG_COLOR_SPACE, data.colorSpace}, {TAG_EXIF_VERSION, data.exifVersion}, {TAG_IMAGE_DESCRIPTION, data.description}};
        for (String[] tag : always) exif.setAttribute(tag[0], tag[1]);
        putIfSet(exif, data.whiteBalance, TAG_WHITE_BALANCE);
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
        public final String model = com.scamera.app.util.DeviceName.model();
        public final String make = com.scamera.app.util.DeviceName.make();
        /** P618: the app's version, as the DNG's Software (DngMetadata.software); Copyright ("SCAMERA") is gone. */
        public final String software = com.scamera.app.processing.DngMetadata.software();
        /** P618: the shot's wall clock (ms) the time tags are of; 0 = not set. */
        public long captureTimeMs;
        public String subSecTime;
        public String offsetTime;
        public String isoSpeed;
        public String focal35mm;
        public String lensMake;
        public String lensModel;
        public String lensSpec;
        public String sensitivityType;
        public String apertureApex;
        public String compression;
        public String colorSpace;
        public String exifVersion;
        public String description;
        public String dateTime;
        public String exposureTime;
        public String fNumber;
        public String focalLength;
        public String isoValue;
        public String whiteBalance;
        public String frameDuration;
    }
}