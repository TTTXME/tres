package com.particlesdevs.photoncamera.api;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.util.Log;
import androidx.exifinterface.media.ExifInterface;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import static android.hardware.camera2.CaptureResult.*;
import static androidx.exifinterface.media.ExifInterface.*;

public class ParseExif {
    public static final SimpleDateFormat sFormatter;
    private static final String TAG = "ParseExif";

    static {
        sFormatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        sFormatter.setTimeZone(TimeZone.getDefault());
    }

    public static String getTime(long exposureTime) {
        String out;
        long sec = 1000000000;
        double time = (double) (exposureTime) / sec;
        out = String.valueOf((time));
        return out;
    }

    public static String resultget(CaptureResult res, Key<?> key) {
        Object out = res.get(key);
        if (out != null) return out.toString();
        else return "";
    }

    public static ExifData parse(CaptureResult result, CaptureRequest request) {
        return parse(result, request, System.currentTimeMillis());
    }

    /**
     * P618: {@code captureTimeMs} is the shot's wall clock (the moment ImageSaver named its files): DateTime, DateTimeOriginal /
     * Digitized, SubSecTime* and OffsetTime* (the processing start, a queued shot's minutes later, stood here, without a zone).
     */
    public static ExifData parse(CaptureResult result, CaptureRequest request, long captureTimeMs) {
        ExifData data = new ExifData();

        // P319: only logged here. A queued shot can start after the camera screen is gone (no controller): no NPE for it.
        final com.particlesdevs.photoncamera.capture.CaptureController controller = ScameraApp.getCaptureController();
        int rotation = controller != null ? controller.cameraRotation : 0;
        String TAG = "ParseExif";
        Log.d(TAG, "Gravity rotation:" + ScameraApp.getGravity().getRotation());
        Log.d(TAG, "Sensor rotation:" + (controller != null ? controller.mSensorOrientation : -1));
        int orientation = ORIENTATION_NORMAL;
        switch (rotation) {
            case 90:
                orientation = ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case 180:
                orientation = ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case 270:
                orientation = ExifInterface.ORIENTATION_ROTATE_270;
                break;
        }
        Log.d(TAG, "rotation:" + rotation);
        Log.d(TAG, "orientation:" + orientation);

        Integer iso = result.get(SENSOR_SENSITIVITY);
        int isonum = 100;
        if (iso != null) isonum = (int) (iso * IsoExpoSelector.getMPY());
        Log.d(TAG, "sensitivity:" + isonum);
        iso(data, isonum);
        data.F_NUMBER = resultget(result, LENS_APERTURE);
        String focal = resultget(result, LENS_FOCAL_LENGTH);
        if (!focal.isEmpty()) {
            data.FOCAL_LENGTH = Math.round(100 * Double.parseDouble(focal)) + "/100";
        }
        // P618: ApertureValue is the APEX 2 log2(N), not the f-number
        final Float lensAperture = result.get(LENS_APERTURE);
        data.APERTURE_VALUE = lensAperture == null ? null : apertureValue(lensAperture);
        String exposure = resultget(result, SENSOR_EXPOSURE_TIME);
        if (!exposure.isEmpty()) {
            data.EXPOSURE_TIME = getTime(Long.parseLong(exposure));
        }
        Long frameDuration = result.get(SENSOR_FRAME_DURATION);
        if (frameDuration != null) {
            data.FRAME_DURATION = getTime(frameDuration);
        }
        Integer awbMode = result.get(CONTROL_AWB_MODE);
        if (awbMode != null) {
            data.WHITE_BALANCE = (awbMode == CONTROL_AWB_MODE_AUTO) ? "0" : "1";
        }
        time(data, captureTimeMs);
        data.COMPRESSION = COMPRESSION_JPEG;
        data.COLOR_SPACE = "sRGB";
        data.EXIF_VERSION = "0231";
        /*
        //saving for later use
        float sensorWidth = CameraFragment.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE).getWidth();
        String mm35 = String.valueOf((short) (36 * (result.get(LENS_FOCAL_LENGTH) / sensorWidth)));
        inter.setAttribute(TAG_FOCAL_LENGTH_IN_35MM_FILM, mm35);
        Log.d(TAG, "Saving 35mm FocalLength = " + mm35);
        */
        return data;
    }

    /** EXIF Compression (259) of a JPEG file: 6; the encoder quality used to be written here (98). */
    public static final String COMPRESSION_JPEG = "6";

    /** P618: ISO as PhotographicSensitivity (a SHORT: 65535 above it) and ISOSpeed (SensitivityType 3 = ISO speed). */
    static void iso(ExifData data, int iso) {
        data.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        data.PHOTOGRAPHIC_SENSITIVITY = iso > 0 ? String.valueOf(com.particlesdevs.photoncamera.processing.ShotExif.iso16(iso)) : null;
        data.ISO_SPEED = iso > 0 ? String.valueOf(iso) : null;
    }

    /** P618: ApertureValue (APEX) of the f-number {@code fNumber}, as a rational; null for none. */
    static String apertureValue(double fNumber) {
        final double apex = com.particlesdevs.photoncamera.processing.ShotExif.apex(fNumber);
        return apex >= 0 ? com.particlesdevs.photoncamera.processing.ShotExif.rationalText(
                com.particlesdevs.photoncamera.processing.ShotExif.rational(apex, 1000)) : null;
    }

    /** P618: every time tag of {@code data} for the moment {@code ms} in the phone's zone. */
    static void time(ExifData data, long ms) {
        final TimeZone zone = TimeZone.getDefault();
        data.captureTimeMs = ms;
        data.DATETIME = com.particlesdevs.photoncamera.processing.ShotExif.dateTime(ms, zone);
        data.SUBSEC_TIME = com.particlesdevs.photoncamera.processing.ShotExif.subSec(ms);
        data.OFFSET_TIME = com.particlesdevs.photoncamera.processing.ShotExif.offset(ms, zone);
    }

    public static void syncWithParameters(ExifData data, Parameters parameters) {
        // P618: the facts the shot's DNG records too (ShotExif.of): the same time, ISO, exposure and lens. The time only when
        // the shot has one (Parameters.captureTimeMs, set by ScamProcessor from parse's): never this sync's clock
        final com.particlesdevs.photoncamera.processing.ShotExif.Facts f = com.particlesdevs.photoncamera.processing.ShotExif.of(
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
        data.LENS_SPECIFICATION = com.particlesdevs.photoncamera.processing.ShotExif.rationalText(
                com.particlesdevs.photoncamera.processing.ShotExif.lensSpecificationRationals(f.lensSpecification()));
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

    public static ExifInterface setAllAttributes(File file, ExifData data) {
        ExifInterface inter = null;
        try {
            inter = new ExifInterface(file);
        } catch (IOException e) {
            e.printStackTrace();
            return inter;
        }
        inter.setAttribute(TAG_SENSITIVITY_TYPE, data.SENSITIVITY_TYPE);
        inter.setAttribute(TAG_PHOTOGRAPHIC_SENSITIVITY, data.PHOTOGRAPHIC_SENSITIVITY);
        if (data.ISO_SPEED != null) inter.setAttribute(TAG_ISO_SPEED, data.ISO_SPEED);
        inter.setAttribute(TAG_F_NUMBER, data.F_NUMBER);
        inter.setAttribute(TAG_FOCAL_LENGTH, data.FOCAL_LENGTH);
        // P618: no Copyright: it said "SCAMERA" (the photographer's field); the app is the Software
        inter.setAttribute(TAG_SOFTWARE, data.SOFTWARE);
        inter.setAttribute(TAG_APERTURE_VALUE, data.APERTURE_VALUE);
        inter.setAttribute(TAG_EXPOSURE_TIME, data.EXPOSURE_TIME);
        inter.setAttribute(ExifInterface.TAG_DATETIME, data.DATETIME);
        // P618: the shot's time as the DNG records it: when it was taken, its milliseconds and zone (the gallery's «Дата съёмки»
        // reads DateTimeOriginal, which no photo had)
        if (data.DATETIME != null) {
            inter.setAttribute(TAG_DATETIME_ORIGINAL, data.DATETIME);
            inter.setAttribute(TAG_DATETIME_DIGITIZED, data.DATETIME);
        }
        if (data.SUBSEC_TIME != null) {
            inter.setAttribute(TAG_SUBSEC_TIME, data.SUBSEC_TIME);
            inter.setAttribute(TAG_SUBSEC_TIME_ORIGINAL, data.SUBSEC_TIME);
            inter.setAttribute(TAG_SUBSEC_TIME_DIGITIZED, data.SUBSEC_TIME);
        }
        if (data.OFFSET_TIME != null) {
            inter.setAttribute(TAG_OFFSET_TIME, data.OFFSET_TIME);
            inter.setAttribute(TAG_OFFSET_TIME_ORIGINAL, data.OFFSET_TIME);
            inter.setAttribute(TAG_OFFSET_TIME_DIGITIZED, data.OFFSET_TIME);
        }
        if (data.FOCAL_LENGTH_35MM != null) inter.setAttribute(TAG_FOCAL_LENGTH_IN_35MM_FILM, data.FOCAL_LENGTH_35MM);
        if (data.LENS_MAKE != null) inter.setAttribute(TAG_LENS_MAKE, data.LENS_MAKE);
        if (data.LENS_MODEL != null) inter.setAttribute(TAG_LENS_MODEL, data.LENS_MODEL);
        if (data.LENS_SPECIFICATION != null) inter.setAttribute(TAG_LENS_SPECIFICATION, data.LENS_SPECIFICATION);
        inter.setAttribute(TAG_MODEL, data.MODEL);
        inter.setAttribute(TAG_MAKE, data.MAKE);
        inter.setAttribute(TAG_COMPRESSION, data.COMPRESSION);
        inter.setAttribute(TAG_COLOR_SPACE, data.COLOR_SPACE);
        inter.setAttribute(TAG_EXIF_VERSION, data.EXIF_VERSION);
        inter.setAttribute(TAG_IMAGE_DESCRIPTION, data.IMAGE_DESCRIPTION);
        if (data.WHITE_BALANCE != null) inter.setAttribute(TAG_WHITE_BALANCE, data.WHITE_BALANCE);
        return inter;
    }

    public static int getOrientation(int cameraRotation) {
        Log.d(TAG, "Gravity rotation:" + ScameraApp.getGravity().getRotation());
        Log.d(TAG, "Sensor rotation:" + ScameraApp.getCaptureController().mSensorOrientation);
        int orientation = ORIENTATION_NORMAL;
        switch (cameraRotation) {
            case 90:
                orientation = ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case 180:
                orientation = ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case 270:
                orientation = ExifInterface.ORIENTATION_ROTATE_270;
                break;
        }
        return orientation;
    }

    public static class ExifData {
        // P196: the phone's market name («vivo X200 Ultra») when the system has one, Build.MODEL otherwise (DeviceName);
        // every format takes these through setAllAttributes (JPEG, Ultra HDR, HEIC 8 / 10-bit, WebP, AVIF)
        public final String MODEL = com.particlesdevs.photoncamera.util.DeviceName.model();
        public final String MAKE = com.particlesdevs.photoncamera.util.DeviceName.make();
        /** P618: the app's version, as the DNG's Software (DngMetadata.software); Copyright ("SCAMERA") is gone. */
        public final String SOFTWARE = com.particlesdevs.photoncamera.processing.DngMetadata.software();
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