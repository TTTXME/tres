package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;

import com.scamera.app.gallery.files.ImageFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * P637: what the camera hands to the gallery it opens - the shutter frame of a shot still processed, else its thumbnail and its
 * last photo's file - and the saves it reports while the gallery is open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CameraHandoffTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() {
        CameraHandoff.reset();
    }

    @After
    public void tearDown() {
        CameraHandoff.reset();
    }

    private File photo(String name) throws Exception {
        File f = tmp.newFile(name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        }
        return f;
    }

    @Test
    public void aShotInWorkHandsItsShutterFrameAndNoFile() throws Exception {
        File earlier = photo("SCA_1.jpg");
        CameraHandoff.saved(earlier.getAbsolutePath());
        Bitmap frame = Bitmap.createBitmap(540, 720, Bitmap.Config.ARGB_8888);
        CameraHandoff.shutter(frame, 0);
        Bitmap thumb = Bitmap.createBitmap(200, 267, Bitmap.Config.ARGB_8888);
        CameraHandoff.open(thumb, true);
        CameraHandoff.Open o = CameraHandoff.take();
        assertNotNull(o);
        assertTrue(o.pending);
        assertSame("the frame of the shot in work, not the thumbnail of the photo before it", frame, o.picture);
        assertNull("the earlier photo is not the one the tap is for", o.latest);
        assertEquals("shutter frame", o.what);
        assertTrue(o.tapAt > 0);
        assertNull("handed over once", CameraHandoff.take());
    }

    @Test
    public void theShutterFrameIsKeptUpright() {
        Bitmap frame = Bitmap.createBitmap(540, 720, Bitmap.Config.ARGB_8888);
        assertSame(frame, CameraHandoff.upright(frame, 0));
        Bitmap side = CameraHandoff.upright(frame, 90);
        assertEquals("a landscape shot: the frame turned as the photo will be", 720, side.getWidth());
        assertEquals(540, side.getHeight());
        Bitmap other = CameraHandoff.upright(frame, -90);
        assertEquals(720, other.getWidth());
        Bitmap down = CameraHandoff.upright(frame, 180);
        assertEquals(540, down.getWidth());
        assertEquals(720, down.getHeight());
    }

    @Test
    public void anIdleCameraHandsItsThumbnailAndItsLastFile() throws Exception {
        File saved = photo("SCA_2.jpg");
        CameraHandoff.shutter(Bitmap.createBitmap(54, 72, Bitmap.Config.ARGB_8888), 0);
        CameraHandoff.saved(saved.getAbsolutePath());
        Bitmap thumb = Bitmap.createBitmap(200, 267, Bitmap.Config.ARGB_8888);
        CameraHandoff.open(thumb, false);
        CameraHandoff.Open o = CameraHandoff.take();
        assertFalse(o.pending);
        assertNotNull(o.picture);
        assertEquals("the camera's thumbnail, as the gallery's own bitmap", 200, o.picture.getWidth());
        assertEquals(267, o.picture.getHeight());
        assertEquals("camera thumbnail", o.what);
        assertNotNull("the last photo by its file: shown before MediaStore is read", o.latest);
        assertEquals("SCA_2.jpg", o.latest.displayName());
        assertEquals(saved.getAbsolutePath(), o.latest.filePath());
        assertEquals("file", o.latest.contentUri().getScheme());
        assertEquals(saved.length(), o.latest.sizeBytes());
        assertTrue("no MediaStore row yet: nothing to share or delete", CameraHandoff.unfiled(o.latest));
    }

    @Test
    public void theScannersRowReplacesTheFilesPath() throws Exception {
        File saved = photo("SCA_3.jpg");
        CameraHandoff.saved(saved.getAbsolutePath());
        Uri row = Uri.parse("content://media/external_primary/images/media/42");
        CameraHandoff.scanned("/another/file.jpg", Uri.parse("content://media/external_primary/images/media/7"));
        assertNull("another file's row is not this one's", CameraHandoff.lastSaved().row);
        CameraHandoff.scanned(saved.getAbsolutePath(), row);
        assertEquals(row, CameraHandoff.lastSaved().row);
        CameraHandoff.open(null, false);
        CameraHandoff.Open o = CameraHandoff.take();
        assertEquals(row, o.latest.contentUri());
        assertEquals(42, o.latest.rowId());
        assertFalse(CameraHandoff.unfiled(o.latest));
        assertEquals("none", o.what);
    }

    @Test
    public void mediaStoresNewestStandsInUntilThisProcessSaves() throws Exception {
        ImageFile store = new ImageFile(9, Uri.parse("content://media/external/images/media/9"), "OLD.jpg", 1000, 5, "/sdcard/DCIM/Camera/OLD.jpg");
        CameraHandoff.latest(store);
        CameraHandoff.open(null, false);
        assertSame("a new process: the photo the thumbnail was made from", store, CameraHandoff.take().latest);
        File saved = photo("SCA_4.jpg");
        CameraHandoff.saved(saved.getAbsolutePath());
        CameraHandoff.latest(store); // the camera's resume reads MediaStore before the scanner filed the new photo
        CameraHandoff.open(null, false);
        assertEquals("the photo saved a moment ago stays the last one", "SCA_4.jpg", CameraHandoff.take().latest.displayName());
        // deleted in the gallery (moved to the trash): MediaStore's newest again
        assertTrue(saved.delete());
        CameraHandoff.latest(store);
        CameraHandoff.open(null, false);
        assertSame(store, CameraHandoff.take().latest);
    }

    @Test
    public void aMissingFileIsNoPhoto() {
        assertNull(CameraHandoff.fileOf("/nowhere/SCA_5.jpg", null));
        assertNull(CameraHandoff.fileOf(null, null));
        CameraHandoff.saved("/nowhere/SCA_5.jpg");
        CameraHandoff.open(null, false);
        assertNull(CameraHandoff.take().latest);
    }

    @Test
    public void theOpenGalleryHearsSavesOnTheMainThread() throws Exception {
        final List<String> heard = new ArrayList<>();
        CameraHandoff.Listener l = new CameraHandoff.Listener() {
            @Override public void saved(String path) { heard.add("saved " + new File(path).getName() + " " + (Looper.myLooper() == Looper.getMainLooper())); }
            @Override public void scanned(String path, Uri row) { heard.add("scanned " + row.getLastPathSegment()); }
        };
        CameraHandoff.listen(l);
        final File f = photo("SCA_6.jpg");
        Thread worker = new Thread(() -> {
            CameraHandoff.saved(f.getAbsolutePath());
            CameraHandoff.scanned(f.getAbsolutePath(), Uri.parse("content://media/external_primary/images/media/77"));
        });
        worker.start();
        worker.join();
        assertTrue("not on the saving thread", heard.isEmpty());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, heard.size());
        assertEquals("saved SCA_6.jpg true", heard.get(0));
        assertEquals("scanned 77", heard.get(1));
        // a viewer that is gone hears nothing, also of an event already posted
        CameraHandoff.saved(f.getAbsolutePath());
        CameraHandoff.listen(null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, heard.size());
    }
}
