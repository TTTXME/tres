package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.SizeF;

import androidx.exifinterface.media.ExifInterface;

import com.scamera.app.api.ParseExif;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.util.DeviceName;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowSystemProperties;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * P618 (owner, 2026-11-11: «Перепроверь exif данные которые записываются в dng, у нас там всё ещё остались старые хвосты от
 * Photon Camera»): the shot's DNG and photo record the same facts - the time the shot was named with its milliseconds and zone,
 * the ISO above 65535, the APEX ApertureValue, the lens of the camera the RAW came from (no placeholder), the 35 mm focal length
 * where the field of view is known - and nothing of PhotonCamera's (its settings list in the description, its
 * "MODEL-BRAND-MANUFACTURER" UniqueCameraModel, a Copyright of the app's name). tools/check_dng_metadata.cpp checks the native
 * writer lays these tags out in the file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShotExifTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** 2026-11-11 14:03:07.042 in Moscow (UTC+3, no summer time). */
    private static final long SHOT = 1794394987042L;
    private TimeZone zone;

    @Before
    public void setUp() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        ShadowBuild.setBrand("vivo");
        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("V2454A");
        ShadowSystemProperties.override("ro.vivo.market.name", "vivo X200 Ultra");
        DeviceName.forget();
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(zone);
        ShadowSystemProperties.reset();
        DeviceName.forget();
    }

    @Test
    public void timeHasMillisecondsAndTheZoneOfThatMoment() {
        final TimeZone moscow = TimeZone.getTimeZone("Europe/Moscow");
        assertEquals("2026:11:11 14:03:07", ShotExif.dateTime(SHOT, moscow));
        assertEquals("042", ShotExif.subSec(SHOT));
        assertEquals("+03:00", ShotExif.offset(SHOT, moscow));
        assertEquals("000", ShotExif.subSec(1000L));
        // the offset of the moment: New York's summer and winter time, half-hour zones
        final TimeZone ny = TimeZone.getTimeZone("America/New_York");
        assertEquals("-05:00", ShotExif.offset(SHOT, ny));
        assertEquals("-04:00", ShotExif.offset(1783000000000L, ny)); // 2026-07
        assertEquals("+05:30", ShotExif.offset(SHOT, TimeZone.getTimeZone("Asia/Kolkata")));
        assertEquals("+00:00", ShotExif.offset(SHOT, TimeZone.getTimeZone("UTC")));
    }

    @Test
    public void isoAboveAShortIsClampedWithIsoSpeedTheValue() {
        assertEquals(65535, ShotExif.iso16(102400));
        assertEquals(3200, ShotExif.iso16(3200));
        assertEquals(0, ShotExif.iso16(0));
        final Map<Integer, ShotExif.Tag> t = byTag(ShotExif.dngTags(facts(102400)));
        assertArrayEquals(new int[]{65535}, t.get(ShotExif.ISO).shorts);
        assertArrayEquals(new long[]{102400}, t.get(ShotExif.ISO_SPEED).longs);
        assertArrayEquals(new int[]{ShotExif.SENSITIVITY_ISO_SPEED}, t.get(ShotExif.SENSITIVITY_TYPE).shorts);
    }

    @Test
    public void rationalsAreTheValuesACameraWrites() {
        assertArrayEquals(new long[]{1, 852}, ShotExif.exposureRational(1 / 852.0));
        assertArrayEquals(new long[]{1, 8000}, ShotExif.exposureRational(1 / 8000.0));
        assertArrayEquals(new long[]{300000, 1000000}, ShotExif.exposureRational(0.3));
        assertArrayEquals(new long[]{2500000, 1000000}, ShotExif.exposureRational(2.5));
        assertArrayEquals(new long[]{30, 1}, ShotExif.exposureRational(30));
        assertNull(ShotExif.exposureRational(0));
        assertNull(ShotExif.exposureRational(Double.NaN));
        assertArrayEquals(new long[]{912, 100}, ShotExif.rational(9.12, 100));
        assertNull(ShotExif.rational(-1, 100));
        // ApertureValue is APEX: 2 log2(N) (f/1.69 -> 1.514, f/2 -> 2, f/1 -> 0), not the f-number the photo wrote
        assertEquals(1.514, ShotExif.apex(1.69), 0.001);
        assertEquals(2.0, ShotExif.apex(2.0), 1e-12);
        assertEquals(0.0, ShotExif.apex(1.0), 1e-12);
        assertEquals("1514/1000", ShotExif.rationalText(ShotExif.rational(ShotExif.apex(1.69), 1000)));
    }

    @Test
    public void focalLengthIn35mmFilmFromTheFieldShown() {
        // Pixel 7's main: 6.81 mm on a 9.6 x 7.2 mm sensor - Google's "24 mm" lens (diagonal 12 mm -> 24.6)
        assertEquals(25, ShotExif.focal35(6.81, 9.6, 7.2, 1));
        assertEquals(49, ShotExif.focal35(6.81, 9.6, 7.2, 2));
        assertEquals(0, ShotExif.focal35(6.81, 9.6, 7.2, 0)); // the field not known: no tag
        assertEquals(0, ShotExif.focal35(Double.NaN, 9.6, 7.2, 1));
        assertEquals(0, ShotExif.focal35(6.81, 0, 7.2, 1));
    }

    @Test
    public void lensStringsNameThePhoneAndTheCamera() {
        assertEquals("vivo X200 Ultra back camera 9.12mm f/1.69", ShotExif.lensModel("vivo X200 Ultra", "back", 9.12, 1.69));
        assertEquals("vivo X200 Ultra front camera 4.5mm f/2", ShotExif.lensModel("vivo X200 Ultra", "front", 4.5, 2.0));
        assertEquals("vivo X200 Ultra camera", ShotExif.lensModel("vivo X200 Ultra", null, Double.NaN, Double.NaN));
        assertEquals("back", ShotExif.facing(1));
        assertEquals("front", ShotExif.facing(0));
        assertNull(ShotExif.facing(null));
        assertEquals("vivo V2454A camera 5", ShotExif.uniqueCameraModel("vivo", "V2454A", 5));
        assertArrayEquals(new double[]{6.7, 9.12, 1.69, 1.69},
                ShotExif.lensSpecification(new float[]{9.12f, 6.7f}, new float[]{2.2f, 1.69f}), 1e-6);
        assertNull(ShotExif.lensSpecification(null, new float[]{1.69f}));
        assertEquals("670/100,912/100,169/100,169/100", ShotExif.rationalText(ShotExif.lensSpecificationRationals(
                new double[]{6.7, 9.12, 1.69, 1.69})));
    }

    @Test
    public void dngTagsHoldTheExifIfdAndLensInfo() {
        final List<ShotExif.Tag> list = ShotExif.dngTags(facts(800));
        final Map<Integer, ShotExif.Tag> t = byTag(list);
        assertEquals(list.size(), t.size()); // each tag once
        for (int tag : new int[]{ShotExif.DATE_TIME_ORIGINAL, ShotExif.DATE_TIME_DIGITIZED})
            assertEquals("2026:11:11 14:03:07", t.get(tag).ascii);
        for (int tag : new int[]{ShotExif.SUBSEC_TIME, ShotExif.SUBSEC_TIME_ORIGINAL, ShotExif.SUBSEC_TIME_DIGITIZED})
            assertEquals("042", t.get(tag).ascii);
        for (int tag : new int[]{ShotExif.OFFSET_TIME, ShotExif.OFFSET_TIME_ORIGINAL, ShotExif.OFFSET_TIME_DIGITIZED})
            assertEquals("+03:00", t.get(tag).ascii);
        assertArrayEquals(new long[]{1, 852}, t.get(ShotExif.EXPOSURE_TIME).rationals);
        assertArrayEquals(new long[]{169, 100}, t.get(ShotExif.F_NUMBER).rationals);
        assertArrayEquals(new long[]{912, 100}, t.get(ShotExif.FOCAL_LENGTH).rationals);
        assertArrayEquals(new int[]{35}, t.get(ShotExif.FOCAL_LENGTH_35MM).shorts);
        assertEquals("vivo", t.get(ShotExif.LENS_MAKE).ascii);
        assertEquals("vivo X200 Ultra back camera 9.12mm f/1.69", t.get(ShotExif.LENS_MODEL).ascii);
        assertEquals(ShotExif.Tag.IFD0, t.get(ShotExif.LENS_INFO).ifd);
        assertArrayEquals(t.get(ShotExif.LENS_SPECIFICATION).rationals, t.get(ShotExif.LENS_INFO).rationals);
        for (ShotExif.Tag x : list) if (x.tag != ShotExif.LENS_INFO) assertEquals(x.toString(), ShotExif.Tag.EXIF, x.ifd);
        // unknown values give no tag
        final ShotExif.Facts f = facts(0);
        f.exposureTime = Double.NaN;
        f.fNumber = Double.NaN;
        f.focalLength = Double.NaN;
        f.focal35 = 0;
        f.focalLengths = null;
        final Map<Integer, ShotExif.Tag> u = byTag(ShotExif.dngTags(f));
        for (int tag : new int[]{ShotExif.EXPOSURE_TIME, ShotExif.F_NUMBER, ShotExif.ISO, ShotExif.ISO_SPEED, ShotExif.APERTURE_VALUE,
                ShotExif.FOCAL_LENGTH, ShotExif.FOCAL_LENGTH_35MM, ShotExif.LENS_SPECIFICATION, ShotExif.LENS_INFO})
            assertFalse(String.valueOf(tag), u.containsKey(tag));
        assertEquals("vivo X200 Ultra back camera", u.get(ShotExif.LENS_MODEL).ascii);
    }

    @Test
    public void factsTakeTheShotsLensNeverThePlaceholder() {
        final Parameters p = shot();
        ShotExif.Facts f = ShotExif.of(p, 1, DeviceName.make(), DeviceName.model());
        assertEquals(9.12, f.focalLength, 1e-6);
        assertEquals(1.69, f.fNumber, 1e-6);
        assertEquals("vivo X200 Ultra", f.device);
        assertEquals("back", f.facing);
        assertEquals(SHOT, f.timeMs);
        assertEquals(ShotExif.focal35(9.12, 9.83, 7.37, 1), f.focal35);
        // no focal length / aperture in the result or request: the camera's declared lens
        p.focalLength = 4.75f;
        p.aperture = 1.8f;
        p.focalLengthOfShot = p.apertureOfShot = false;
        f = ShotExif.of(p, 1, DeviceName.make(), DeviceName.model());
        assertEquals(9.12, f.focalLength, 1e-6);
        assertEquals(1.69, f.fNumber, 1e-6);
        // nor declared: none (FillDynamicParameters' 4.75 mm / f/1.8 stay for the processing only)
        p.lensFocalLengths = null;
        p.lensApertures = null;
        f = ShotExif.of(p, 1, DeviceName.make(), DeviceName.model());
        assertTrue(Double.isNaN(f.focalLength));
        assertTrue(Double.isNaN(f.fNumber));
        assertEquals(0, f.focal35);
        // an ISZ frame: the field unknown, no 35 mm focal length
        assertEquals(0, ShotExif.of(shot(), 0, DeviceName.make(), DeviceName.model()).focal35);
    }

    @Test
    public void descriptionIsOursWithoutPhotonCamerasSettings() {
        final Parameters p = shot();
        final String d = p.description();
        for (String old : new String[]{"parameters:", "Noise Merging", "Align=", "Version=", "FocalL=", "hasGainMap", "Photon"})
            assertFalse(old, d.contains(old));
        assertTrue(d, d.startsWith("SCAMERA\n Frames=8\n Camera=5"));
        p.dngMerged = true;
        assertTrue(ShotExif.dngDescription(p).endsWith("\n RAW=merged burst\n Route=SHDR"));
        p.dngMerged = false;
        assertTrue(ShotExif.dngDescription(p).endsWith("\n RAW=one frame\n Route=SHDR"));
        // the gallery still reads the route of the photo's description
        final ParseExif.ExifData data = new ParseExif.ExifData();
        ParseExif.syncWithParameters(data, p);
        assertEquals("SHDR", com.scamera.app.gallery.ui.GalleryFormat.routeOf(data.IMAGE_DESCRIPTION));
    }

    @Test
    public void aspectCropIsThePhotos43Crop() {
        assertArrayEquals(new int[]{0, 6, 4080, 3060}, ShotExif.aspectCrop(4080, 3072));
        assertArrayEquals(new int[]{0, 384, 4096, 3072}, ShotExif.aspectCrop(4096, 3840));
        assertNull(ShotExif.aspectCrop(4000, 3000)); // already 4:3
        assertNull(ShotExif.aspectCrop(4, 3)); // too small for the crop
    }

    @Test
    public void thePhotoRecordsWhatTheDngRecords() throws Exception {
        final Parameters p = shot();
        final ParseExif.ExifData data = new ParseExif.ExifData();
        data.COMPRESSION = ParseExif.COMPRESSION_JPEG;
        data.COLOR_SPACE = "sRGB";
        ParseExif.syncWithParameters(data, p);
        final ShotExif.Facts dng = ShotExif.of(p, p.photoFieldZoom, DeviceName.make(), DeviceName.model());
        // the JPEG writer (its APP1, ExifBlock) and an ExifInterface rewrite of a plain JPEG (the other formats' tags)
        for (boolean rewrite : new boolean[]{false, true}) {
            final Path jpg = tmp.getRoot().toPath().resolve("p" + rewrite + ".jpg");
            if (rewrite) {
                try (java.io.OutputStream o = java.nio.file.Files.newOutputStream(jpg)) {
                    assertTrue(picture(64, 48).compress(Bitmap.CompressFormat.JPEG, 90, o));
                }
                final ExifInterface w = ParseExif.setAllAttributes(jpg.toFile(), data);
                assertNotNull(w);
                w.saveAttributes();
            } else {
                assertTrue(ImageSaver.Util.saveBitmapAsJPG(jpg, picture(64, 48), 95, data, true));
            }
            final ExifInterface e = new ExifInterface(jpg.toFile());
            assertTrue(e.getAttribute(ExifInterface.TAG_SOFTWARE), e.getAttribute(ExifInterface.TAG_SOFTWARE).startsWith("SCAMERA "));
            assertEquals(DngMetadata.software(), e.getAttribute(ExifInterface.TAG_SOFTWARE));
            assertNull(e.getAttribute(ExifInterface.TAG_COPYRIGHT));
            assertEquals(dng.dateTime(), e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
            assertEquals(dng.dateTime(), e.getAttribute(ExifInterface.TAG_DATETIME));
            assertEquals("042", e.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL));
            assertEquals("+03:00", e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
            assertEquals("vivo", e.getAttribute(ExifInterface.TAG_MAKE));
            assertEquals("vivo X200 Ultra", e.getAttribute(ExifInterface.TAG_MODEL));
            assertEquals("vivo", e.getAttribute(ExifInterface.TAG_LENS_MAKE));
            assertEquals(dng.lensModel(), e.getAttribute(ExifInterface.TAG_LENS_MODEL));
            assertEquals(String.valueOf(dng.focal35), e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
            assertEquals(1.514, e.getAttributeDouble(ExifInterface.TAG_APERTURE_VALUE, 0), 0.001);
            assertEquals(1.69, e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0), 0.001);
            assertEquals(9.12, e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0), 0.001);
            assertEquals("800", e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY));
            assertEquals("800", e.getAttribute(ExifInterface.TAG_ISO_SPEED));
            assertNotNull(e.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION));
            final String desc = e.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
            assertFalse(desc, desc.toLowerCase(Locale.ROOT).contains("photon"));
        }
        // the photo of a 2x digital zoom shows half the field: twice the 35 mm focal length
        p.photoFieldZoom = 2f;
        ParseExif.syncWithParameters(data, p);
        assertEquals(String.valueOf(ShotExif.focal35(9.12, 9.83, 7.37, 2)), data.FOCAL_LENGTH_35MM);
        // an ISZ frame: none
        p.photoFieldZoom = 0f;
        ParseExif.syncWithParameters(data, p);
        assertNull(data.FOCAL_LENGTH_35MM);
    }

    private static Parameters shot() {
        final Parameters p;
        // its field initialiser reads the app's settings (as ScamLabParamsTest)
        try (org.mockito.MockedStatic<com.scamera.app.app.ScameraApp> app =
                     org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class)) {
            app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(new com.scamera.app.api.Settings());
            p = new Parameters();
        }
        p.captureTimeMs = SHOT;
        p.iso = 800;
        p.exposureTime = 1 / 852.0;
        p.focalLength = 9.12f;
        p.aperture = 1.69f;
        p.focalLengthOfShot = p.apertureOfShot = true;
        p.lensFocalLengths = new float[]{9.12f};
        p.lensApertures = new float[]{1.69f};
        p.lensFacing = 1;
        p.sensorSize = new SizeF(9.83f, 7.37f);
        p.photoFieldZoom = 1f;
        p.dngFieldZoom = 1f;
        p.multiFrameCount = 8;
        p.cameraID = "5";
        p.physicalID = 5;
        return p;
    }

    private static ShotExif.Facts facts(int iso) {
        final ShotExif.Facts f = new ShotExif.Facts();
        f.timeMs = SHOT;
        f.zone = TimeZone.getTimeZone("Europe/Moscow");
        f.make = "vivo";
        f.device = "vivo X200 Ultra";
        f.iso = iso;
        f.exposureTime = 1 / 852.0;
        f.fNumber = 1.69;
        f.focalLength = 9.12;
        f.focalLengths = new float[]{9.12f};
        f.apertures = new float[]{1.69f};
        f.facing = "back";
        f.focal35 = 35;
        return f;
    }

    private static Map<Integer, ShotExif.Tag> byTag(List<ShotExif.Tag> list) {
        final Map<Integer, ShotExif.Tag> m = new HashMap<>();
        for (ShotExif.Tag t : list) m.put(t.tag, t);
        return m;
    }

    private static Bitmap picture(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.setPixel(x, y, Color.rgb(x * 255 / w, y * 255 / h, 90));
        return b;
    }
}
