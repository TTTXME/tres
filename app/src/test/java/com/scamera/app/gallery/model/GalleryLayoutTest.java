package com.scamera.app.gallery.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import com.scamera.app.gallery.files.ImageFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** P622: the gallery's view setting (grid / list / timeline), the timeline's zoom steps, the last photo and the albums' new files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryLayoutTest {
    private Context c;
    private MockedStatic<com.scamera.app.app.ScameraApp> app;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences(GalleryLayout.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        app = org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class);
        app.when(com.scamera.app.app.ScameraApp::getAppContext).thenReturn(c); // Lang reads the UI language from it
    }

    @After
    public void tearDown() {
        app.close();
    }

    @Test
    public void gridIsTheDefaultAndTheChoicePersists() {
        assertEquals(GalleryLayout.Mode.GRID, GalleryLayout.mode(c));
        GalleryLayout.setMode(c, GalleryLayout.Mode.LIST);
        assertEquals(GalleryLayout.Mode.LIST, GalleryLayout.mode(c));
        GalleryLayout.setMode(c, GalleryLayout.Mode.TIMELINE);
        assertEquals(GalleryLayout.Mode.TIMELINE, GalleryLayout.mode(c));
        // stored in the gallery's own preferences, by name
        assertEquals("TIMELINE", c.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE).getString("view_mode", null));
        GalleryLayout.setMode(c, null);
        assertEquals(GalleryLayout.Mode.GRID, GalleryLayout.mode(c));
    }

    @Test
    public void unknownStoredModeFallsBackToGrid() {
        assertEquals(GalleryLayout.Mode.GRID, GalleryLayout.parse("MOSAIC"));
        assertEquals(GalleryLayout.Mode.GRID, GalleryLayout.parse(null));
        c.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE).edit().putString("view_mode", "bogus").commit();
        assertEquals(GalleryLayout.Mode.GRID, GalleryLayout.mode(c));
        c.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE).edit().putString("timeline_level", "bogus").commit();
        assertEquals(GalleryLayout.Level.ALL, GalleryLayout.level(c));
    }

    @Test
    public void modeAndLevelLabels() {
        assertEquals("Grid", GalleryLayout.Mode.GRID.label());
        assertEquals("List", GalleryLayout.Mode.LIST.label());
        assertEquals("Timeline", GalleryLayout.Mode.TIMELINE.label());
        assertEquals("Years", GalleryLayout.Level.YEARS.label());
        assertEquals("Months", GalleryLayout.Level.MONTHS.label());
        assertEquals("All photos", GalleryLayout.Level.ALL.label());
    }

    @Test
    @Config(qualifiers = "ru")
    public void modeLabelsInRussian() {
        assertEquals("Сетка", GalleryLayout.Mode.GRID.label());
        assertEquals("Список", GalleryLayout.Mode.LIST.label());
        assertEquals("Лента", GalleryLayout.Mode.TIMELINE.label());
        assertEquals("Все фото", GalleryLayout.Level.ALL.label());
    }

    @Test
    public void timelineColumnsAndLevelPersistClamped() {
        assertEquals(GalleryLayout.TL_DEFAULT_COLUMNS, GalleryLayout.timelineColumns(c));
        GalleryLayout.setTimelineColumns(c, 99);
        assertEquals(GalleryLayout.TL_MAX_COLUMNS, GalleryLayout.timelineColumns(c));
        GalleryLayout.setTimelineColumns(c, 1);
        assertEquals(GalleryLayout.TL_MIN_COLUMNS, GalleryLayout.timelineColumns(c));
        assertEquals(GalleryLayout.Level.ALL, GalleryLayout.level(c));
        GalleryLayout.setLevel(c, GalleryLayout.Level.YEARS);
        assertEquals(GalleryLayout.Level.YEARS, GalleryLayout.level(c));
    }

    @Test
    public void pinchWalksColumnsThenLevels() {
        int all = GalleryLayout.Level.ALL.ordinal(), months = GalleryLayout.Level.MONTHS.ordinal(), years = GalleryLayout.Level.YEARS.ordinal();
        // pinch (zoom out): more columns, then months, then years, then nothing further
        assertArrayEquals(new int[]{all, 5}, GalleryLayout.zoomStep(GalleryLayout.Level.ALL, 4, false));
        assertArrayEquals(new int[]{months, 7}, GalleryLayout.zoomStep(GalleryLayout.Level.ALL, 7, false));
        assertArrayEquals(new int[]{years, 7}, GalleryLayout.zoomStep(GalleryLayout.Level.MONTHS, 7, false));
        assertArrayEquals(new int[]{years, 7}, GalleryLayout.zoomStep(GalleryLayout.Level.YEARS, 7, false));
        // spread (zoom in): years to months, months to every photo at the most columns, then fewer columns down to the least
        assertArrayEquals(new int[]{months, 4}, GalleryLayout.zoomStep(GalleryLayout.Level.YEARS, 4, true));
        assertArrayEquals(new int[]{all, GalleryLayout.TL_MAX_COLUMNS}, GalleryLayout.zoomStep(GalleryLayout.Level.MONTHS, 4, true));
        assertArrayEquals(new int[]{all, 3}, GalleryLayout.zoomStep(GalleryLayout.Level.ALL, 4, true));
        assertArrayEquals(new int[]{all, 3}, GalleryLayout.zoomStep(GalleryLayout.Level.ALL, 3, true));
    }

    @Test
    public void lastOpenedPersists() {
        assertEquals("", GalleryLayout.lastOpened(c));
        GalleryLayout.setLastOpened(c, "id:42");
        assertEquals("id:42", GalleryLayout.lastOpened(c));
        GalleryLayout.setLastOpened(c, null);
        assertEquals("", GalleryLayout.lastOpened(c));
    }

    private static List<GalleryItem> files(long... times) {
        List<GalleryItem> out = new ArrayList<>();
        for (int i = 0; i < times.length; i++) out.add(new GalleryItem(new ImageFile(i + 1, null, "IMG_" + i + ".jpg", times[i], 1000, "/x/IMG_" + i + ".jpg")));
        return out;
    }

    @Test
    public void newFilesCountFromTheLastVisit() {
        List<GalleryItem> first = files(3000, 2000, 1000);
        // the first sighting records the newest file: no badge
        assertEquals(0, GalleryLayout.newCount(c, "Camera", first));
        List<GalleryItem> later = files(5000, 4000, 3000, 2000, 1000);
        assertEquals(2, GalleryLayout.newCount(c, "Camera", later));
        // opening the album counts everything as seen
        GalleryLayout.markSeen(c, "Camera", later);
        assertEquals(0, GalleryLayout.newCount(c, "Camera", later));
        // another album has its own mark
        assertEquals(0, GalleryLayout.newCount(c, "Screenshots", later));
        assertEquals(0, GalleryLayout.newCount(c, "Empty", new ArrayList<>()));
        assertEquals(3, GalleryLayout.countNewer(Arrays.asList(later.get(0), later.get(1), later.get(2)), 2500));
    }

    @Test
    public void badgeText() {
        assertNull(GalleryLayout.badge(0));
        assertNull(GalleryLayout.badge(-3));
        assertEquals("7", GalleryLayout.badge(7));
        assertEquals("99", GalleryLayout.badge(99));
        assertEquals("99+", GalleryLayout.badge(100));
    }
}
