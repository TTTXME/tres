package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.scamera.app.util.Lang;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** P706: the details sheet's property sections - only rows with a value, sections without rows left out, values in words. */
public class PhotoPropertiesTest {
    private static PhotoProperties.Section find(List<PhotoProperties.Section> list, String tag) {
        for (PhotoProperties.Section s : list) if (s.tag.equals(tag)) return s;
        return null;
    }

    private static String value(PhotoProperties.Section s, String title) {
        for (String[] r : s.rows) if (r[0].equals(title)) return r[1];
        return null;
    }

    @Test public void withoutExifOnlyTheFile() {
        PhotoProperties.Facts f = new PhotoProperties.Facts();
        f.name = "IMG_1.jpg";
        f.path = "/storage/emulated/0/DCIM/Camera/IMG_1.jpg";
        List<PhotoProperties.Section> s = PhotoProperties.sections(f);
        assertEquals(1, s.size());
        assertEquals("file", s.get(0).tag);
        assertEquals("DCIM/Camera", value(s.get(0), Lang.t("Папка", "Folder")));
        assertEquals(f.path, value(s.get(0), Lang.t("Путь к файлу", "File path")));
        for (String[] r : s.get(0).rows) assertTrue(r[0], r[1] != null && !r[1].trim().isEmpty());
        assertTrue(PhotoProperties.sections(new PhotoProperties.Facts()).isEmpty());
    }

    @Test public void aScameraShot() {
        PhotoProperties.Facts f = new PhotoProperties.Facts();
        f.name = "IMG_20261114_094107.jpg";
        f.width = 4080;
        f.height = 3072;
        f.bytes = 3 * 1024 * 1024;
        f.make = "vivo";
        f.model = "vivo X200 Ultra";
        f.lensModel = "vivo X200 Ultra back camera 6.7mm f/1.69";
        f.focalMm = 6.7;
        f.focal35 = 24;
        f.fNumber = 1.69;
        f.exposureS = 0.01;
        f.iso = 100;
        f.flash = 0;
        f.description = "SCAMERA\n Frames=8\n Camera=3\n Colour=ISP CCM\n RAW=merged burst\n Route=Hybrid";
        f.software = "SCAMERA 0.98 (31254) 5b8d8d04";
        f.ultraHdr = true;
        List<PhotoProperties.Section> s = PhotoProperties.sections(f);
        PhotoProperties.Section cam = find(s, "camera"), proc = find(s, "processing");
        assertEquals("vivo X200 Ultra", value(cam, Lang.t("Устройство", "Device")));
        assertEquals(PhotoProperties.decimal(6.7, 1) + Lang.t(" мм", " mm") + " (24" + Lang.t(" мм экв.", " mm equiv.") + ")",
                value(cam, Lang.t("Фокусное расстояние", "Focal length")));
        assertEquals("1/100" + Lang.t(" с", " s"), value(cam, Lang.t("Выдержка", "Shutter speed")));
        assertEquals("ISO 100", value(cam, Lang.t("Чувствительность", "Sensitivity")));
        assertEquals("SHDR", value(proc, Lang.t("Склейка", "Merge")));
        assertEquals("8", value(proc, Lang.t("Кадров склеено", "Frames merged")));
        assertEquals("3", value(proc, Lang.t("Камера (ID)", "Camera ID")));
        assertEquals(Lang.t("Склеенная серия", "Merged burst"), value(proc, "RAW"));
        assertEquals(f.software, value(proc, Lang.t("Программа", "Software")));
        assertNotNull(find(s, "hdr"));
        assertNull("no GPS: no place", find(s, "place"));
        assertTrue(value(find(s, "file"), Lang.t("Разрешение", "Dimensions")).startsWith("4080 × 3072 · 12"));
    }

    @Test public void datesWithTheirZone() {
        assertEquals("14.11.2026 09:41:07 UTC+03:00", PhotoProperties.modified(1_794_638_467_000L, TimeZone.getTimeZone("GMT+03:00")));
        assertEquals("01.01.1970 00:00:01 UTC+00:00", PhotoProperties.modified(1000, TimeZone.getTimeZone("UTC")));
        assertEquals("UTC+03:00", PhotoProperties.zoneLabel("+03:00"));
        assertEquals("", PhotoProperties.zoneLabel(null));
        PhotoProperties.Facts f = new PhotoProperties.Facts();
        f.taken = "2026:11:14 09:41:07";
        f.takenOffset = "+03:00";
        assertEquals("14.11.2026 09:41:07 UTC+03:00", value(PhotoProperties.file(f), Lang.t("Дата съёмки", "Taken")));
    }

    @Test public void aPlaceOpensTheMap() {
        PhotoProperties.Facts f = new PhotoProperties.Facts();
        f.latitude = 55.751244;
        f.longitude = 37.618423;
        f.altitude = 151.6;
        PhotoProperties.Section p = PhotoProperties.place(f);
        assertEquals(2, p.rows.size());
        assertEquals("55.751244, 37.618423", p.rows.get(0)[1]);
        assertEquals("geo:55.751244,37.618423?q=55.751244,37.618423", p.rows.get(0)[2]);
        assertEquals("152" + Lang.t(" м", " m"), p.rows.get(1)[1]);
        f.latitude = 0;
        f.longitude = 0;
        assertTrue("0, 0 is no place", PhotoProperties.place(f).rows.isEmpty());
    }

    @Test public void anotherAppsDescriptionAndWords() {
        assertNull(PhotoProperties.shotFacts("Taken with another app"));
        Map<String, String> m = PhotoProperties.shotFacts("SCAMERA\n Frames=4\n Film=Portra 400");
        assertEquals("4", m.get("Frames"));
        assertEquals("Portra 400", m.get("Film"));
        PhotoProperties.Facts f = new PhotoProperties.Facts();
        f.description = "Taken with another app";
        assertEquals("Taken with another app", value(PhotoProperties.processing(f), Lang.t("Описание", "Description")));
        assertNull(PhotoProperties.folder("content://media/external/images/media/5"));
        assertNull(PhotoProperties.dimensions(0, 10));
        assertNull(PhotoProperties.shutter(Double.NaN));
        assertEquals("2" + Lang.t(" с", " s"), PhotoProperties.shutter(2));
        assertNull(PhotoProperties.orientation(0, false));
        assertEquals(Lang.t("Отражено", "Mirrored"), PhotoProperties.orientation(0, true));
        assertTrue(PhotoProperties.size(13_002_384).contains("13 002 384"));
    }
}
