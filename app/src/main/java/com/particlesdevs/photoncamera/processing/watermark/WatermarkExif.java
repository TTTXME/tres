package com.particlesdevs.photoncamera.processing.watermark;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.settings.PreferenceNumber;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * P633: the watermark's facts of a photo the editor opens, from the photo's own EXIF - the phone (Make / Model, as PhotoInfo names
 * it), the focal length (its 35 mm equivalent first), aperture, exposure time, ISO and the time it was taken (DateTimeOriginal in
 * its own OffsetTimeOriginal, else the phone's zone: the wall clock as the camera wrote it). A fact the EXIF lacks stays unknown and
 * {@link #has} says so: the editor greys its row, WatermarkText leaves it out.
 */
public final class WatermarkExif {
    /** The facts a field shows; TEXT (the own text) is always there. */
    public enum Field { TEXT, DEVICE, LENS, APERTURE, SHUTTER, ISO, TIME }

    private WatermarkExif() {}

    /** The facts of {@code exif} (null: none known). */
    public static WatermarkText.Info info(@Nullable ExifInterface exif) {
        WatermarkText.Info i = new WatermarkText.Info();
        if (exif == null) return i;
        i.device = com.particlesdevs.photoncamera.gallery.ui.PhotoInfo.device(exif.getAttribute(ExifInterface.TAG_MAKE),
                exif.getAttribute(ExifInterface.TAG_MODEL));
        i.focalLength = PhotoWatermark.rational(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
        i.focal35 = (int) Math.round(PreferenceNumber.read(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM), 0));
        i.fNumber = number(exif.getAttribute(ExifInterface.TAG_F_NUMBER));
        i.exposureTime = number(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
        String iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
        if (iso == null) iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED);
        i.iso = (int) Math.round(PreferenceNumber.read(firstOf(iso), 0));
        String date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        String offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL);
        if (date == null) {
            date = exif.getAttribute(ExifInterface.TAG_DATETIME);
            offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME);
        }
        TimeZone zone = zone(offset);
        i.zone = zone;
        i.timeMs = time(date, zone);
        return i;
    }

    /** A rational («1/120»), a decimal, NaN for none. */
    static double number(@Nullable String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        return s.indexOf('/') >= 0 ? PhotoWatermark.rational(s.trim()) : PreferenceNumber.read(s.trim(), Double.NaN);
    }

    /** «100,100» (a multi-valued ISO) -> «100». */
    @Nullable
    static String firstOf(@Nullable String s) {
        if (s == null) return null;
        int comma = s.indexOf(',');
        return comma > 0 ? s.substring(0, comma).trim() : s.trim();
    }

    /** «+03:00» -> GMT+03:00; null / anything else: the phone's zone. */
    static TimeZone zone(@Nullable String offset) {
        if (offset != null && offset.trim().matches("[+-]\\d{2}:\\d{2}")) return TimeZone.getTimeZone("GMT" + offset.trim());
        return TimeZone.getDefault();
    }

    /** «2026:11:11 14:05:09» in {@code zone} -> ms; 0 for none. */
    static long time(@Nullable String date, TimeZone zone) {
        if (date == null || date.trim().length() < 19) return 0;
        SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        f.setTimeZone(zone);
        f.setLenient(false);
        try {
            java.util.Date d = f.parse(date.trim().substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    /** Whether {@code info} knows what {@code field} shows. */
    public static boolean has(WatermarkText.Info info, Field field) {
        switch (field) {
            case DEVICE: return info.device != null && !info.device.trim().isEmpty();
            case LENS: return !WatermarkText.focal(info).isEmpty();
            case APERTURE: return !WatermarkText.aperture(info.fNumber).isEmpty();
            case SHUTTER: return !WatermarkText.shutter(info.exposureTime).isEmpty();
            case ISO: return info.iso > 0;
            case TIME: return info.timeMs > 0;
            default: return true;
        }
    }
}
