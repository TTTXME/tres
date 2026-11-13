package com.scamera.app.gallery.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.robolectric.RuntimeEnvironment;

import com.scamera.app.gallery.files.GalleryTrash;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.ocr.OcrIndex;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** P502 / P504: the gallery search (names, folders, kinds, dates, the text read on a photo) and the trash's days left. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GallerySearchTest {
    static final class File extends MediaFile {
        final long id, modified;
        final String name, path;

        File(long id, String path, long modified) {
            this.id = id;
            this.path = path;
            this.name = path.substring(path.lastIndexOf('/') + 1);
            this.modified = modified;
        }

        @Override public long getId() { return id; }
        @Override public Uri getFileUri() { return Uri.parse("content://media/external/images/media/" + id); }
        @Override public long getLastModified() { return modified; }
        @Override public String getDisplayName() { return name; }
        @Override public long getSize() { return 1; }
        @Override public String getAbsolutePath() { return path; }
    }

    private static long at(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m, d, 12, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void wordsAreLowerCaseWithYoAsYe() {
        assertEquals(Arrays.asList("ёлка".replace('ё', 'е'), "2026"), GallerySearch.words("  Ёлка, 2026 "));
        assertTrue(GallerySearch.words(null).isEmpty());
    }

    @Test
    public void findsByKindFolderMonthAndToday() {
        long now = at(2026, Calendar.NOVEMBER, 5);
        GalleryItem shot = new GalleryItem(new File(1, "/storage/DCIM/Screenshots/Screenshot_1.png", now));
        GalleryItem clip = new GalleryItem(new File(2, "/storage/DCIM/Camera/VID_2.mp4", at(2025, Calendar.MARCH, 2)));
        GalleryItem raw = new GalleryItem(new File(3, "/storage/DCIM/Camera/IMG_3.dng", at(2025, Calendar.MARCH, 9)));
        List<GalleryItem> all = Arrays.asList(shot, clip, raw);
        assertEquals(Collections.singletonList(shot), GallerySearch.find(all, "скриншот", Collections.emptySet(), now, null));
        assertEquals(Collections.singletonList(shot), GallerySearch.find(all, "сегодня", Collections.emptySet(), now, null));
        assertEquals(Collections.singletonList(clip), GallerySearch.find(all, "видео марта", Collections.emptySet(), now, null));
        assertEquals(Arrays.asList(clip, raw), GallerySearch.find(all, "camera 2025", Collections.emptySet(), now, null));
        assertEquals(Collections.singletonList(raw), GallerySearch.find(all, "RAW", Collections.emptySet(), now, null));
        assertEquals(all, GallerySearch.find(all, " ", Collections.emptySet(), now, null));
    }

    @Test
    public void findsTheTextReadOnAPhoto() {
        long now = at(2026, Calendar.NOVEMBER, 5);
        File sign = new File(41, "/storage/DCIM/Camera/IMG_41.jpg", now - 1000);
        List<GalleryItem> all = new ArrayList<>(Collections.singletonList(new GalleryItem(sign)));
        assertTrue(GallerySearch.find(all, "аптека", Collections.emptySet(), now, null).isEmpty());
        OcrIndex.put(RuntimeEnvironment.getApplication(), 41, sign.getLastModified(), "АПТЕКА 24 часа\nPharmacy");
        assertEquals(1, GallerySearch.find(all, "аптека pharmacy", Collections.emptySet(), now, null).size());
        // a changed photo (another modification time) is read again: its old text no longer matches
        File edited = new File(41, "/storage/DCIM/Camera/IMG_41.jpg", now);
        assertNull(OcrIndex.searchText(edited));
    }

    @Test
    public void trashDaysRoundUp() {
        long day = 86_400_000L;
        assertEquals(30, GalleryTrash.daysLeft(30 * day, 1));
        assertEquals(1, GalleryTrash.daysLeft(day + 5, day));
        assertEquals(0, GalleryTrash.daysLeft(5, 10));
    }
}
