package com.particlesdevs.photoncamera.gallery.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * P645 (vivo X200 Ultra: «other RAW 61 rows 5772 ms» before the gallery had a list): with a MediaStore whose RAW query (the Files
 * collection) hangs, the gallery's first list - the images - is out at once; the RAWs join when their query is back, in date
 * order, without duplicates; the next open has them in its first list from the cache.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryFirstListTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    /** MediaStore: three photos and a video in «Camera»; the Files collection (the RAW query) waits for {@link #release}. */
    public static final class FakeMedia extends ContentProvider {
        static volatile CountDownLatch release = new CountDownLatch(0);
        static volatile boolean rawAsked, rawServed;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            String path = uri.getPath() != null ? uri.getPath() : "";
            MatrixCursor c = new MatrixCursor(projection);
            if (path.contains("/images/")) {
                // newest first: 30, 20, 10 (the RAW, 25, belongs between the first two)
                for (long id = 1; id <= 3; id++) c.addRow(row(projection, id, "IMG_" + id + ".jpg", 40 - id * 10, "image"));
            } else if (path.contains("/video/")) {
                c.addRow(row(projection, 9, "VID_9.mp4", 5, "video"));
            } else if (path.endsWith("/file")) {
                rawAsked = true;
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                c.addRow(row(projection, 7, "IMG_7.CR3", 25, "raw"));
                c.addRow(row(projection, 2, "IMG_2.jpg", 20, "image")); // an image the images query has: not twice
                rawServed = true;
            }
            return c;
        }

        private static Object[] row(String[] projection, long id, String name, long added, String kind) {
            Object[] r = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                switch (projection[i]) {
                    case MediaStore.MediaColumns._ID: r[i] = id; break;
                    case MediaStore.MediaColumns.DISPLAY_NAME: r[i] = name; break;
                    case MediaStore.MediaColumns.DATE_ADDED: r[i] = added; break;
                    case MediaStore.MediaColumns.SIZE: r[i] = 1000 + id; break;
                    case MediaStore.MediaColumns.DATA: r[i] = "/storage/emulated/0/DCIM/Camera/" + name; break;
                    case MediaStore.MediaColumns.BUCKET_ID: r[i] = 77L; break;
                    case MediaStore.MediaColumns.BUCKET_DISPLAY_NAME: r[i] = "Camera"; break;
                    case MediaStore.Video.Media.DURATION: r[i] = 3000L; break;
                    default: r[i] = null;
                }
            }
            return r;
        }

        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }

    /** The settings' key names come from the app's resources (no ScameraApp in a host test). */
    private org.mockito.MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> app;

    @Before
    public void setUp() {
        final android.content.Context c = RuntimeEnvironment.getApplication();
        app = org.mockito.Mockito.mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        app.when(() -> com.particlesdevs.photoncamera.app.ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> c.getString(inv.getArgument(0)));
        // the keys' class initialised here (a static mock is this thread's only; the gallery reads on its loader thread)
        assertNotNull(com.particlesdevs.photoncamera.settings.PreferenceKeys.Key.FOLDERS_LIST.mValue);
        Robolectric.setupContentProvider(FakeMedia.class, MediaStore.AUTHORITY);
        OtherRawCache.forget();
        GalleryFileOperations.rawFileForTests = new java.io.File(dir.getRoot(), OtherRawCache.FILE_NAME);
        FakeMedia.rawAsked = FakeMedia.rawServed = false;
    }

    @After
    public void tearDown() {
        FakeMedia.release.countDown();
        GalleryFileOperations.rawFileForTests = null;
        OtherRawCache.forget();
        app.close();
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 1000 && !done.getAsBoolean(); i++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue(done.getAsBoolean());
    }

    private static int size(GalleryViewModel vm) {
        List<GalleryItem> l = vm.getCurrentFolderImages().getValue();
        return l == null ? -1 : l.size();
    }

    private static GalleryViewModel open() {
        GalleryViewModel vm = new GalleryViewModel(RuntimeEnvironment.getApplication());
        vm.loadAsync(() -> vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue()));
        return vm;
    }

    @Test
    public void theFirstListIsOutWhileTheRawQueryHangsAndTheRawJoinsInOrder() throws Exception {
        FakeMedia.release = new CountDownLatch(1);
        GalleryViewModel vm = open();
        waitFor(() -> size(vm) >= 0);
        assertFalse("the grid's first list did not wait for the RAW query", FakeMedia.rawServed);
        assertEquals("three photos and the video", 4, size(vm));
        waitFor(() -> FakeMedia.rawAsked); // the check runs after the list
        FakeMedia.release.countDown();
        waitFor(() -> size(vm) == 5);
        List<GalleryItem> all = vm.getCurrentFolderImages().getValue();
        assertNotNull(all);
        Set<Long> ids = new HashSet<>();
        long last = Long.MAX_VALUE;
        StringBuilder names = new StringBuilder();
        for (GalleryItem i : all) {
            assertTrue("no duplicates", ids.add(i.getFile().getId()));
            assertTrue("newest first", i.getFile().getLastModified() <= last);
            last = i.getFile().getLastModified();
            names.append(i.getFile().getDisplayName()).append(' ');
        }
        assertEquals("IMG_1.jpg IMG_7.CR3 IMG_2.jpg IMG_3.jpg VID_9.mp4 ", names.toString());
    }

    @Test
    public void theNextOpenHasTheRawInItsFirstList() throws Exception {
        GalleryViewModel first = open();
        waitFor(() -> size(first) == 5); // the check found the RAW and wrote the cache
        OtherRawCache.forget();          // a new process: only the file is left
        FakeMedia.rawServed = false;
        FakeMedia.release = new CountDownLatch(1); // its RAW query (no generation on the host: each check reads all) hangs
        GalleryViewModel again = open();
        waitFor(() -> size(again) >= 0);
        assertEquals("the RAW from the cache in the first list, before any query came back", 5, size(again));
        assertFalse(FakeMedia.rawServed);
        FakeMedia.release.countDown();
    }
}
