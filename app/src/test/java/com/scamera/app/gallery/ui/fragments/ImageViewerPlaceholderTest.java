package com.scamera.app.gallery.ui.fragments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentFactory;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager.widget.ViewPager;

import com.scamera.app.R;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.ui.CameraHandoff;
import com.scamera.app.gallery.ui.GalleryNavRecreateTest;
import com.scamera.app.gallery.viewmodel.GalleryViewModel;
import com.scamera.app.util.Lang;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * P637 (owner, 2026-11-12: the viewer opened by the camera's thumbnail right after a shot was black for ~6 s): the real viewer
 * fragment in the gallery's layout, the camera's hand-over as GalleryActivity sets it. Its first view - before the activity is
 * resumed, before any read of MediaStore and without the main looper running a single posted task - already holds the
 * placeholder: the shutter frame under «Обработка снимка…» for a shot in work, the camera's last photo as a page otherwise.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ImageViewerPlaceholderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** GalleryActivity's layout with the real viewer; what the camera handed over is set as GalleryActivity.onCreate does. */
    public static class Host extends FragmentActivity {
        static CameraHandoff.Open open;

        @Override
        protected void onCreate(Bundle saved) {
            getSupportFragmentManager().setFragmentFactory(new FragmentFactory() {
                @NonNull
                @Override
                public Fragment instantiate(@NonNull ClassLoader cl, @NonNull String className) {
                    if (className.equals(ImageViewerFragment.class.getName())) return new ImageViewerFragment();
                    if (className.startsWith("com.scamera.app.gallery")) return new GalleryNavRecreateTest.Dummy();
                    return super.instantiate(cl, className);
                }
            });
            super.onCreate(saved);
            GalleryViewModel vm = new ViewModelProvider(this).get(GalleryViewModel.class);
            vm.setOpening(open);
            if (open != null && open.latest != null) vm.showFront(open.latest, true);
            setContentView(R.layout.activity_gallery);
        }
    }

    @Before
    public void setUp() {
        Host.open = null;
        CameraHandoff.reset();
        com.scamera.app.processing.ProcessingKeepAlive.busy = () -> true;
    }

    @After
    public void tearDown() {
        Host.open = null;
        CameraHandoff.listen(null);
        com.scamera.app.processing.ProcessingKeepAlive.busy = () -> false;
    }

    private File photo(String name) throws Exception {
        File f = tmp.newFile(name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        }
        return f;
    }

    private static CameraHandoff.Open handOver(Bitmap shown, boolean busy) {
        CameraHandoff.open(shown, busy);
        return CameraHandoff.take();
    }

    private static <T extends View> T tagged(FragmentActivity a, String tag) {
        return a.findViewById(android.R.id.content).findViewWithTag(tag);
    }

    private static ViewPager pager(View root) {
        if (root instanceof ViewPager) return (ViewPager) root;
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
                ViewPager p = pager(((ViewGroup) root).getChildAt(i));
                if (p != null) return p;
            }
        return null;
    }

    private static boolean shows(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root.isShown() || root.getVisibility() == View.VISIBLE;
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) if (shows(((ViewGroup) root).getChildAt(i), text)) return true;
        return false;
    }

    @Test
    public void aShotInWorkIsOnTheViewersFirstViewAndItsFileTakesItsPlace() throws Exception {
        CameraHandoff.saved(photo("SCA_before.jpg").getAbsolutePath()); // an earlier photo of this session
        Bitmap frame = Bitmap.createBitmap(54, 72, Bitmap.Config.ARGB_8888);
        CameraHandoff.shutter(frame, 0);
        Host.open = handOver(Bitmap.createBitmap(20, 26, Bitmap.Config.ARGB_8888), true);
        Intent camera = new Intent().putExtra("CameraFragment", true);
        final long t0 = System.nanoTime();
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class, camera)) {
            c.create().start(); // the viewer's view exists; nothing posted has run, MediaStore was not asked
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            Host a = c.get();
            View cover = tagged(a, "viewer_cover");
            assertNotNull("the placeholder is part of the viewer's first view", cover);
            assertEquals(View.VISIBLE, cover.getVisibility());
            ImageView picture = (ImageView) ((ViewGroup) cover).getChildAt(0);
            assertSame("the viewfinder's frame at the shutter", frame, ((BitmapDrawable) picture.getDrawable()).getBitmap());
            View pill = tagged(a, "viewer_processing");
            assertEquals("a processing indicator, not a black screen", View.VISIBLE, pill.getVisibility());
            assertTrue(shows(a.findViewById(android.R.id.content), Lang.t("Обработка…", "Processing…")));
            GalleryViewModel vm = new ViewModelProvider(a).get(GalleryViewModel.class);
            assertNull("no list yet: the placeholder did not wait for one", vm.getCurrentFolderImages().getValue());
            assertNull(pager(a.findViewById(android.R.id.content)).getAdapter());
            System.out.println("P637 pending placeholder on the viewer's first view: " + ms + " ms from the activity's creation (host)");
            c.resume().visible();

            // the camera saves the shot: its file is the viewer's page at once (no MediaStore row yet), under the cover until decoded
            File saved = photo("SCA_new.jpg");
            Thread worker = new Thread(() -> CameraHandoff.saved(saved.getAbsolutePath()));
            worker.start();
            worker.join();
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ImageViewerFragment.SAVED_SETTLE_MS + 5));
            List<GalleryItem> shown = vm.getCurrentFolderImages().getValue();
            assertNotNull(shown);
            assertEquals("SCA_new.jpg", shown.get(0).mediaFile().displayName());
            assertEquals("file", shown.get(0).mediaFile().contentUri().getScheme());
            ViewPager pager = pager(a.findViewById(android.R.id.content));
            assertNotNull(pager.getAdapter());
            assertEquals(0, pager.getCurrentItem());
            assertEquals("the cover stays over the page until it has its picture", View.VISIBLE, cover.getVisibility());

            // the scanner's row: the same item, the page is not bound again
            Object adapter = pager.getAdapter();
            Uri row = Uri.parse("content://media/external_primary/images/media/91");
            CameraHandoff.scanned(saved.getAbsolutePath(), row);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(row, shown.get(0).mediaFile().contentUri());
            assertSame(adapter, pager.getAdapter());
        }
    }

    @Test
    public void anIdleCamerasLastPhotoIsAPageBeforeTheMediaLists() throws Exception {
        com.scamera.app.processing.ProcessingKeepAlive.busy = () -> false;
        File last = photo("SCA_last.jpg");
        CameraHandoff.saved(last.getAbsolutePath());
        Bitmap thumb = Bitmap.createBitmap(20, 26, Bitmap.Config.ARGB_8888);
        Host.open = handOver(thumb, false);
        assertFalse(Host.open.pending);
        Intent camera = new Intent().putExtra("CameraFragment", true);
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class, camera)) {
            c.create().start();
            Host a = c.get();
            ViewPager pager = pager(a.findViewById(android.R.id.content));
            assertNotNull("the last photo's page is bound in the first view, from its file", pager.getAdapter());
            assertEquals(1, pager.getAdapter().getCount());
            View cover = tagged(a, "viewer_cover");
            assertEquals("the camera's thumbnail until that page has its picture", View.VISIBLE, cover.getVisibility());
            assertSame(Host.open.picture, ((BitmapDrawable) ((ImageView) ((ViewGroup) cover).getChildAt(0)).getDrawable()).getBitmap());
            assertEquals(thumb.getWidth(), Host.open.picture.getWidth());
            assertEquals("no processing pill for a saved photo", View.GONE, tagged(a, "viewer_processing").getVisibility());
        }
    }

    @Test
    public void aShotThatEndsWithoutAFileGivesThePagesBack() throws Exception {
        CameraHandoff.shutter(Bitmap.createBitmap(54, 72, Bitmap.Config.ARGB_8888), 0);
        Host.open = handOver(null, true);
        Intent camera = new Intent().putExtra("CameraFragment", true);
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class, camera).setup()) {
            Host a = c.get();
            View cover = tagged(a, "viewer_cover");
            assertEquals(View.VISIBLE, cover.getVisibility());
            com.scamera.app.processing.ProcessingKeepAlive.busy = () -> false; // the shot failed: nothing is in work
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3 * ImageViewerFragment.PENDING_POLL_MS + 500));
            assertEquals("no placeholder for ever", View.GONE, cover.getVisibility());
        }
    }
}
