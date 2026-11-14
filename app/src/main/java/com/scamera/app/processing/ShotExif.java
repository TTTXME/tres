package com.scamera.app.processing;

import androidx.annotation.Nullable;

import com.scamera.app.processing.render.Parameters;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * P618 (owner, 2026-11-11: «Перепроверь exif данные которые записываются в dng, у нас там всё ещё остались старые хвосты от
 * Photon Camera, исправь и обнови под нашу нынешнюю камеру»): the values both writers of a shot store - the DNG (DngCreator) and
 * the photo's EXIF (ParseExif: JPEG, Ultra HDR, HEIC, WebP, AVIF) - as plain arithmetic the unit tests check.
 *
 * <ul>
 * <li>Time: the shot's wall clock (the moment it was named, ImageSaver) with its milliseconds (SubSecTime*) and the zone's offset
 * at that moment (OffsetTime*, EXIF 2.31). The DNG wrote the time it was written, without either; the photo the time its
 * processing began.</li>
 * <li>ISO: EXIF's PhotographicSensitivity is a SHORT - above 65535 it says 65535 and ISOSpeed (a LONG) the value; the DNG cast
 * it to a short (ISO 102400 read 36864).</li>
 * <li>ApertureValue is the APEX 2 log2(N); the photo wrote the f-number there.</li>
 * <li>The lens: LensMake / LensModel of the camera the RAW came from and LensSpecification / LensInfo of its declared focal
 * lengths and apertures; FocalLengthIn35mmFilm only where the image's field of view is known.</li>
 * <li>UniqueCameraModel: "MODEL-BRAND-MANUFACTURER" as every earlier DNG of the phone, the key DCP profiles are bound to.</li>
 * </ul>
 */
public final class ShotExif {
    private ShotExif() {}

    /** The diagonal of a 36 x 24 mm frame (CIPA DC-008, FocalLengthIn35mmFilm). */
    public static final double DIAGONAL_35MM = Math.hypot(36, 24);

    /** EXIF / TIFF DateTime: "yyyy:MM:dd HH:mm:ss" of {@code ms} in {@code zone}. */
    public static String dateTime(long ms, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(ms));
    }

    /** SubSecTime*: the milliseconds of {@code ms}, three digits. */
    public static String subSec(long ms) {
        return String.format(Locale.US, "%03d", (int) Math.floorMod(ms, 1000L));
    }

    /** OffsetTime*: "+HH:MM" / "-HH:MM", the offset of {@code zone} from UTC at {@code ms} (summer time included). */
    public static String offset(long ms, TimeZone zone) {
        final int minutes = zone.getOffset(ms) / 60000;
        final int a = Math.abs(minutes);
        return String.format(Locale.US, "%c%02d:%02d", minutes < 0 ? '-' : '+', a / 60, a % 60);
    }

    /** PhotographicSensitivity (SHORT): the ISO, 65535 above it; 0 for none. */
    public static int iso16(int iso) {
        return iso <= 0 ? 0 : Math.min(65535, iso);
    }

    /**
     * An unsigned rational {numerator, denominator} close to {@code v} (>= 0): {@code v} exactly when it is a fraction of
     * {@code denominator}, else rounded to it. Null for a negative or non-finite value or one past 2^32.
     */
    @Nullable
    public static long[] rational(double v, long denominator) {
        if (!(v >= 0) || Double.isInfinite(v) || denominator <= 0) return null;
        final double n = Math.rint(v * denominator);
        if (n > 0xFFFFFFFFL) return null;
        return new long[]{(long) n, denominator};
    }

    /**
     * ExposureTime: 1/n for a time under a second within 0.5 % of it (what a camera says: 1/852), else in microseconds -
     * the value to 1 us (seconds: 30/1, 2.5 s 2500000/1000000).
     */
    @Nullable
    public static long[] exposureRational(double seconds) {
        if (!(seconds > 0) || Double.isInfinite(seconds)) return null;
        if (seconds < 1) {
            final long n = Math.round(1 / seconds);
            if (n > 0 && Math.abs(1.0 / n - seconds) <= 0.005 * seconds) return new long[]{1, n};
        }
        final long[] r = rational(seconds, 1000000);
        if (r != null && r[0] % 1000000 == 0) return new long[]{r[0] / 1000000, 1};
        return r;
    }

    /** ApertureValue (APEX): 2 log2(N) of the f-number {@code fNumber}; NaN for none. */
    public static double apex(double fNumber) {
        return fNumber > 0 ? 2 * Math.log(fNumber) / Math.log(2) : Double.NaN;
    }

    /**
     * FocalLengthIn35mmFilm: {@code focalMm} times 35 mm film's diagonal over the diagonal of the sensor area the image shows -
     * the sensor's {@code sensorW} x {@code sensorH} mm over {@code zoom} (1: the whole field, 2: half its width). 0 when
     * anything is unknown (EXIF: 0 = unknown).
     */
    public static int focal35(double focalMm, double sensorW, double sensorH, double zoom) {
        if (!(focalMm > 0) || !(sensorW > 0) || !(sensorH > 0) || !(zoom > 0)
                || Double.isInfinite(focalMm) || Double.isInfinite(sensorW) || Double.isInfinite(sensorH) || Double.isInfinite(zoom))
            return 0;
        final long eq = Math.round(focalMm * DIAGONAL_35MM / Math.hypot(sensorW, sensorH) * zoom);
        return eq < 1 || eq > 65535 ? 0 : (int) eq;
    }

    /**
     * LensSpecification / LensInfo: {shortest focal length, longest, the smallest f-number at the shortest, at the longest} of
     * the camera's declared focal lengths and apertures (a phone camera: one of each; several apertures of a variable-aperture
     * lens: its widest at both ends, the camera does not say which focal length has which). Null when either is missing.
     */
    @Nullable
    public static double[] lensSpecification(@Nullable float[] focals, @Nullable float[] apertures) {
        final double minF = min(focals), maxF = max(focals), minN = min(apertures);
        if (!(minF > 0) || !(maxF > 0) || !(minN > 0)) return null;
        return new double[]{minF, maxF, minN, minN};
    }

    /** LensSpecification as rationals (focal lengths to 0.01 mm, f-numbers to 0.01). */
    @Nullable
    public static long[] lensSpecificationRationals(@Nullable double[] spec) {
        if (spec == null || spec.length != 4) return null;
        final long[] out = new long[8];
        for (int i = 0; i < 4; i++) {
            final long[] r = rational(spec[i], 100);
            if (r == null) return null;
            out[2 * i] = r[0];
            out[2 * i + 1] = r[1];
        }
        return out;
    }

    /**
     * LensModel: the phone, the camera's side and its lens - "vivo X200 Ultra back camera 9.12mm f/1.69" (the form of the stock
     * cameras' "Pixel 7 back camera 6.81mm f/1.85"). Without a focal length or f-number that part is left out.
     */
    public static String lensModel(@Nullable String device, @Nullable String facing, double focalMm, double fNumber) {
        StringBuilder b = new StringBuilder();
        if (device != null && !device.trim().isEmpty()) b.append(device.trim()).append(' ');
        b.append(facing == null || facing.isEmpty() ? "" : facing + " ").append("camera");
        if (focalMm > 0 && !Double.isInfinite(focalMm)) b.append(' ').append(trim(focalMm)).append("mm");
        if (fNumber > 0 && !Double.isInfinite(fNumber)) b.append(" f/").append(trim(fNumber));
        return ascii(b.toString());
    }

    /**
     * UniqueCameraModel: "<Build.MODEL>-<Build.BRAND>-<Build.MANUFACTURER>" - "V2454A-vivo-vivo". P696 (owner, 2026-11-14:
     * «нет возможности выбирать свои DCP профили в лайтруме»): Lightroom / ACR and other developers offer a DCP only to the
     * raws whose UniqueCameraModel is the profile's; P647's "vivo V2454A camera 3" hid every profile made for the phone's
     * DNGs, so the key is the one they were made for, the same for every camera of the phone.
     */
    public static String uniqueCameraModel(@Nullable String buildModel, @Nullable String brand, @Nullable String manufacturer) {
        return ascii(part(buildModel, "Android") + "-" + part(brand, "Android") + "-" + part(manufacturer, "Android"));
    }

    private static String part(@Nullable String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s.trim();
    }

    /** "back" / "front" / "external" of a CameraCharacteristics.LENS_FACING value, null for none. */
    @Nullable
    public static String facing(@Nullable Integer lensFacing) {
        if (lensFacing == null) return null;
        switch (lensFacing) {
            case 0: return "front";   // LENS_FACING_FRONT
            case 1: return "back";    // LENS_FACING_BACK
            case 2: return "external";
            default: return null;
        }
    }

    /** The facts of a shot both files record (see the class comment). */
    public static final class Facts {
        public long timeMs;
        public TimeZone zone;
        /** EXIF Make (DeviceName.make) and the phone's name (DeviceName / PhotoInfo.device: «vivo X200 Ultra»). */
        public String make, device;
        public int iso;
        /** Seconds, f-number, mm; NaN / 0 = unknown. */
        public double exposureTime = Double.NaN, fNumber = Double.NaN, focalLength = Double.NaN;
        @Nullable public float[] focalLengths, apertures;
        @Nullable public String facing;
        /** FocalLengthIn35mmFilm, 0 = unknown. */
        public int focal35;

        public String dateTime() { return ShotExif.dateTime(timeMs, zone); }
        public String subSec() { return ShotExif.subSec(timeMs); }
        public String offset() { return ShotExif.offset(timeMs, zone); }
        @Nullable public double[] lensSpecification() { return ShotExif.lensSpecification(focalLengths, apertures); }
        public String lensModel() { return ShotExif.lensModel(device, facing, focalLength, fNumber); }
    }

    /**
     * The facts of the shot {@code p} for a file whose field of view is {@code fieldZoom} times the lens's (Parameters
     * dngFieldZoom / photoFieldZoom; 0: unknown); {@code make} / {@code model} the EXIF Make and Model (DeviceName).
     */
    public static Facts of(Parameters p, double fieldZoom, String make, String model) {
        Facts f = new Facts();
        f.timeMs = p.captureTimeMs > 0 ? p.captureTimeMs : System.currentTimeMillis();
        f.zone = TimeZone.getDefault();
        f.make = make;
        f.device = com.scamera.app.gallery.ui.PhotoInfo.device(make, model);
        f.iso = p.iso;
        f.exposureTime = p.exposureTime;
        f.fNumber = p.exifAperture();
        f.focalLength = p.exifFocalLength();
        f.focalLengths = p.lensFocalLengths;
        f.apertures = p.lensApertures;
        f.facing = facing(p.lensFacing);
        f.focal35 = p.sensorSizeMm == null ? 0
                : focal35(f.focalLength, p.sensorSizeMm.getWidth(), p.sensorSizeMm.getHeight(), fieldZoom);
        return f;
    }

    /** One tag of a DNG: IFD 0 or the Exif IFD, one of the value kinds set. */
    public static final class Tag {
        public static final int IFD0 = 0, EXIF = 1;
        public final int ifd, tag;
        @Nullable public final String ascii;
        @Nullable public final int[] shorts;
        @Nullable public final long[] longs, rationals;

        Tag(int ifd, int tag, @Nullable String ascii, @Nullable int[] shorts, @Nullable long[] longs, @Nullable long[] rationals) {
            this.ifd = ifd;
            this.tag = tag;
            this.ascii = ascii;
            this.shorts = shorts;
            this.longs = longs;
            this.rationals = rationals;
        }

        @Override
        public String toString() {
            return (ifd == IFD0 ? "IFD0 " : "Exif ") + tag + "=" + (ascii != null ? ascii : shorts != null ? java.util.Arrays.toString(shorts)
                    : longs != null ? java.util.Arrays.toString(longs) : java.util.Arrays.toString(rationals));
        }
    }

    // TIFF / EXIF tag numbers (EXIF 2.32, DNG 1.6)
    public static final int DATE_TIME = 306, EXPOSURE_TIME = 33434, F_NUMBER = 33437, ISO = 34855, SENSITIVITY_TYPE = 34864,
            ISO_SPEED = 34867, DATE_TIME_ORIGINAL = 36867, DATE_TIME_DIGITIZED = 36868, OFFSET_TIME = 36880,
            OFFSET_TIME_ORIGINAL = 36881, OFFSET_TIME_DIGITIZED = 36882, APERTURE_VALUE = 37378, FOCAL_LENGTH = 37386,
            SUBSEC_TIME = 37520, SUBSEC_TIME_ORIGINAL = 37521, SUBSEC_TIME_DIGITIZED = 37522, FOCAL_LENGTH_35MM = 41989,
            LENS_SPECIFICATION = 42034, LENS_MAKE = 42035, LENS_MODEL = 42036, LENS_INFO = 50736;
    /** SensitivityType 3: ISO speed (Camera2 SENSOR_SENSITIVITY is ISO 12232's), so ISOSpeed carries it. */
    public static final int SENSITIVITY_ISO_SPEED = 3;

    /**
     * The time, exposure and lens tags of a DNG (DngCreator writes them; IFD 0 keeps its TIFF/EP ExposureTime, FNumber, ISO and
     * FocalLength from the older setters). Unknown values give no tag.
     */
    public static List<Tag> dngTags(Facts f) {
        List<Tag> t = new ArrayList<>();
        final String time = f.dateTime(), sub = f.subSec(), off = f.offset();
        final long[] exposure = exposureRational(f.exposureTime);
        if (exposure != null) t.add(new Tag(Tag.EXIF, EXPOSURE_TIME, null, null, null, exposure));
        final long[] fn = f.fNumber > 0 ? rational(f.fNumber, 100) : null;
        if (fn != null) t.add(new Tag(Tag.EXIF, F_NUMBER, null, null, null, fn));
        if (f.iso > 0) {
            t.add(new Tag(Tag.EXIF, ISO, null, new int[]{iso16(f.iso)}, null, null));
            t.add(new Tag(Tag.EXIF, SENSITIVITY_TYPE, null, new int[]{SENSITIVITY_ISO_SPEED}, null, null));
            t.add(new Tag(Tag.EXIF, ISO_SPEED, null, null, new long[]{f.iso}, null));
        }
        for (int tag : new int[]{DATE_TIME_ORIGINAL, DATE_TIME_DIGITIZED}) t.add(new Tag(Tag.EXIF, tag, time, null, null, null));
        for (int tag : new int[]{OFFSET_TIME, OFFSET_TIME_ORIGINAL, OFFSET_TIME_DIGITIZED}) t.add(new Tag(Tag.EXIF, tag, off, null, null, null));
        for (int tag : new int[]{SUBSEC_TIME, SUBSEC_TIME_ORIGINAL, SUBSEC_TIME_DIGITIZED}) t.add(new Tag(Tag.EXIF, tag, sub, null, null, null));
        final double av = apex(f.fNumber);
        final long[] apertureValue = av >= 0 ? rational(av, 1000) : null;
        if (apertureValue != null) t.add(new Tag(Tag.EXIF, APERTURE_VALUE, null, null, null, apertureValue));
        final long[] focal = f.focalLength > 0 ? rational(f.focalLength, 100) : null;
        if (focal != null) t.add(new Tag(Tag.EXIF, FOCAL_LENGTH, null, null, null, focal));
        if (f.focal35 > 0) t.add(new Tag(Tag.EXIF, FOCAL_LENGTH_35MM, null, new int[]{f.focal35}, null, null));
        final long[] spec = lensSpecificationRationals(f.lensSpecification());
        if (spec != null) {
            t.add(new Tag(Tag.EXIF, LENS_SPECIFICATION, null, null, null, spec));
            t.add(new Tag(Tag.IFD0, LENS_INFO, null, null, null, spec));
        }
        if (f.make != null && !f.make.trim().isEmpty()) t.add(new Tag(Tag.EXIF, LENS_MAKE, ascii(f.make.trim()), null, null, null));
        t.add(new Tag(Tag.EXIF, LENS_MODEL, f.lensModel(), null, null, null));
        return t;
    }

    /**
     * The DNG's ImageDescription: the shot's description and what this file holds - the merged burst (16 bit over its headroom,
     * P165) or one frame as the sensor gave it (the reference RAW).
     */
    public static String dngDescription(Parameters p) {
        return p.description() + (p.dngMerged ? "\n RAW=merged burst" : "\n RAW=one frame")
                + com.scamera.app.api.ParseExif.routeLine();
    }

    /** The DefaultCrop {x, y, w, h} of the photo's 4:3 crop of a {@code width} x {@code height} DNG; null when it keeps it all. */
    @Nullable
    public static int[] aspectCrop(int width, int height) {
        final int[] b;
        try {
            b = IszAspectCrop.box(width, height);
        } catch (IllegalArgumentException tooSmall) {
            return null;
        }
        return b[0] == 0 && b[1] == 0 && b[2] == width && b[3] == height ? null : b;
    }

    /** "num/den,num/den,...": rationals as ExifInterface takes them. */
    @Nullable
    public static String rationalText(@Nullable long[] pairs) {
        if (pairs == null || pairs.length < 2 || pairs.length % 2 != 0) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) b.append(',');
            b.append(pairs[i]).append('/').append(pairs[i + 1]);
        }
        return b.toString();
    }

    /** "9.12" / "23" / "1.7": up to two decimals, no trailing zeros. */
    static String trim(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** TIFF ASCII: printable ASCII only (anything else becomes '?'). */
    static String ascii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            b.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return b.toString();
    }

    private static double min(@Nullable float[] v) {
        double m = Double.NaN;
        if (v != null) for (float x : v) if (x > 0 && !Float.isInfinite(x) && !(m <= x)) m = x;
        return m;
    }

    private static double max(@Nullable float[] v) {
        double m = Double.NaN;
        if (v != null) for (float x : v) if (x > 0 && !Float.isInfinite(x) && !(m >= x)) m = x;
        return m;
    }
}
