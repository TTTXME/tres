package com.scamera.app.gallery.viewmodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

import com.scamera.app.gallery.files.GalleryFileOperations;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.model.GalleryItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The gallery's lists after a delete, a folders change or «Сохранить копию» (MediaStore replaced by fixed folders). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryViewModelTest {
    private MockedStatic<GalleryFileOperations> files;
    private ArrayList<GalleryFileOperations.ImagesFolder> folders = new ArrayList<>();
    private GalleryViewModel vm;

    private static ImageFile photo(long id, String name) {
        Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        return new ImageFile(id, u, name, id, 1000, "/storage/emulated/0/DCIM/x/" + name);
    }

    private static GalleryFileOperations.ImagesFolder folder(String name, ImageFile... images) {
        GalleryFileOperations.ImagesFolder f = mock(GalleryFileOperations.ImagesFolder.class);
        when(f.getFolderName()).thenReturn(name);
        when(f.getAllImageFiles()).thenReturn(new ArrayList<>(Arrays.asList(images)));
        when(f.getTopImage()).thenReturn(images[0]);
        return f;
    }

    /** What MediaStore holds from now on (the selected folders). */
    private void store(GalleryFileOperations.ImagesFolder... selected) {
        folders = new ArrayList<>(Arrays.asList(selected));
    }

    @Before
    public void setUp() {
        files = mockStatic(GalleryFileOperations.class);
        files.when(() -> GalleryFileOperations._fetchSelectedFolders(any(ContentResolver.class))).thenAnswer(inv -> folders);
        files.when(GalleryFileOperations::extractAllSelectedImages).thenAnswer(inv -> {
            ArrayList<ImageFile> all = new ArrayList<>();
            for (GalleryFileOperations.ImagesFolder f : folders) all.addAll(f.getAllImageFiles());
            return all;
        });
        vm = new GalleryViewModel(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        files.close();
    }

    /** Runs the main looper until {@code done} (the loader thread posts its lists there). */
    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 500 && !done.getAsBoolean(); i++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("the load came back", done.getAsBoolean());
    }

    @Test
    public void noListBeforeTheFirstReadThenTheLoaderSetsThem() throws InterruptedException {
        // P617: the viewer opened from the camera observes this before MediaStore is read: an empty list sent it to the grid
        assertNull(vm.getCurrentFolderImages().getValue());
        assertNull(vm.getSelectedDisplayFolders().getValue());
        final boolean[] ran = {false};
        // the loader thread reads the real (here empty) MediaStore; the lists are set on the main thread before «then»
        vm.loadAsync(() -> {
            ran[0] = true;
            assertEquals("ALL", vm.getAllSelectedImageFolder().getValue().getDisplayName());
            assertTrue(vm.getSelectedDisplayFolders().getValue() != null);
        });
        waitFor(() -> ran[0]);
    }

    @Test
    public void aNewerReadWinsOverAnOlderLoad() throws InterruptedException {
        store(folder("Camera", photo(1, "a.jpg"), photo(2, "b.jpg")));
        final boolean[] ran = {false};
        vm.loadAsync(() -> ran[0] = true); // older: started first (the loader thread sees an empty MediaStore)
        vm.fetchAllMedia(); // newer: a delete's refresh on this thread
        waitFor(() -> ran[0]);
        assertEquals("the older load did not put its lists over the newer ones", 2, vm.getAllSelectedImageFolder().getValue().getFiles().size());
    }

    @Test
    public void emptySelectionClearsAll() {
        store(folder("Camera", photo(1, "a.jpg"), photo(2, "b.jpg")));
        vm.fetchAllMedia();
        assertEquals(2, vm.getAllSelectedImageFolder().getValue().getFiles().size());
        store(); // every photo deleted
        vm.fetchAllMedia();
        assertTrue("no stale «ALL» with the deleted photos", vm.getAllSelectedImageFolder().getValue().getFiles().isEmpty());
        assertEquals("ALL", vm.getAllSelectedImageFolder().getValue().getDisplayName());
        assertNull("no chips", vm.refreshKeeping("ALL"));
        assertTrue("the grid and the viewer are empty", vm.getCurrentFolderImages().getValue().isEmpty());
    }

    @Test
    public void refreshKeepsTheChipFolder() {
        store(folder("Camera", photo(1, "a.jpg"), photo(3, "c.jpg")), folder("Raw", photo(2, "b.dng")));
        vm.fetchAllMedia();
        // a delete in the «Camera» chip: Camera minus the photo, in the grid and in the viewer alike
        store(folder("Camera", photo(3, "c.jpg")), folder("Raw", photo(2, "b.dng")));
        assertEquals("Camera", vm.refreshKeeping("Camera"));
        assertEquals("Camera", vm.getCurrentFolderName());
        List<GalleryItem> shown = vm.getCurrentFolderImages().getValue();
        assertEquals(1, shown.size());
        assertEquals("c.jpg", shown.get(0).getFile().getDisplayName());
        GalleryItem camera = null;
        for (GalleryItem f : vm.getSelectedDisplayFolders().getValue()) if ("Camera".equals(f.getDisplayName())) camera = f;
        assertSame("the chip's own list", camera.getFiles(), shown);
    }

    @Test
    public void refreshFallsBackToAllWhenTheFolderIsGone() {
        store(folder("Camera", photo(1, "a.jpg")), folder("Raw", photo(2, "b.dng")));
        vm.fetchAllMedia();
        store(folder("Raw", photo(2, "b.dng")));
        assertEquals("the first chip", "ALL", vm.refreshKeeping("Camera"));
        assertSame(vm.getAllSelectedImageFolder().getValue().getFiles(), vm.getCurrentFolderImages().getValue());
        assertEquals("ALL", vm.refreshKeeping(null));
    }

    @Test
    public void savedCopyIsNotListedTwice() {
        store(folder("Camera", photo(42, "IMG_edit.jpg"), photo(1, "IMG.jpg")));
        vm.fetchAllMedia();
        vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue());
        Uri listed = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 42);
        vm.showNewFile(new ImageFile(42, listed, "IMG_edit.jpg", 0, 0, null));
        assertEquals(2, vm.getCurrentFolderImages().getValue().size());
        assertEquals(listed, vm.takePendingSelect());
        // the volume-specific URI of the same row (what the insert into VOLUME_EXTERNAL_PRIMARY returns)
        Uri primary = Uri.parse("content://media/external_primary/images/media/42");
        vm.showNewFile(new ImageFile(42, primary, "IMG_edit.jpg", 0, 0, null));
        assertEquals("the copy once", 2, vm.getCurrentFolderImages().getValue().size());
        assertEquals("the viewer selects the listed row", listed, vm.takePendingSelect());
        assertNull(vm.takePendingSelect());
    }

    // ---------------------------------------------------------------- P637: the camera's newest photo ahead of the lists

    private static ImageFile unfiled(String name) {
        String path = "/storage/emulated/0/DCIM/x/" + name;
        return new ImageFile(-1, Uri.fromFile(new java.io.File(path)), name, 5000, 1000, path);
    }

    @Test
    public void theCamerasLastPhotoIsTheListBeforeTheFirstRead() {
        ImageFile last = photo(7, "last.jpg");
        vm.showFront(last, true);
        List<GalleryItem> shown = vm.getCurrentFolderImages().getValue();
        assertEquals("the viewer has a page before MediaStore is read", 1, shown.size());
        assertSame(last, shown.get(0).getFile());
        assertEquals(last.getFileUri(), vm.takePendingSelect());
        // the read brings the same row: the lists' own item from then on, selected
        store(folder("Camera", photo(9, "newer.mp4"), photo(7, "last.jpg"), photo(3, "old.jpg")));
        vm.fetchAllMedia();
        vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue());
        shown = vm.getCurrentFolderImages().getValue();
        assertEquals("not twice", 3, shown.size());
        assertNull(vm.front());
        assertEquals("the viewer stays on the photo the thumbnail showed", shown.get(1).getFile().getFileUri(), vm.takePendingSelect());
    }

    @Test
    public void aSavedFileLeadsTheListUntilMediaStoreHasIt() {
        store(folder("Camera", photo(2, "b.jpg"), photo(1, "a.jpg")));
        vm.fetchAllMedia();
        vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue());
        assertEquals(2, vm.getCurrentFolderImages().getValue().size());
        // the camera saved new.jpg: no MediaStore row yet
        ImageFile saved = unfiled("new.jpg");
        vm.showFront(saved, true);
        List<GalleryItem> shown = vm.getCurrentFolderImages().getValue();
        assertEquals(3, shown.size());
        assertSame(saved, shown.get(0).getFile());
        assertEquals("b.jpg", shown.get(1).getFile().getDisplayName());
        assertEquals(saved.getFileUri(), vm.takePendingSelect());
        assertEquals("«ALL» itself is MediaStore's list", 2, vm.getAllSelectedImageFolder().getValue().getFiles().size());
        // a read before the scanner filed it keeps it first
        vm.fetchAllMedia();
        vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue());
        assertEquals(3, vm.getCurrentFolderImages().getValue().size());
        assertNull("nothing selected by a later read", vm.takePendingSelect());
        // the scanner's row: the same item (the viewer's page stays), now with a row to share / delete
        GalleryItem front = vm.front();
        Uri row = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 30);
        assertTrue(!vm.frontScanned("/storage/emulated/0/DCIM/x/other.jpg", row));
        assertTrue(vm.frontScanned(saved.getAbsolutePath(), row));
        assertSame(front, vm.getCurrentFolderImages().getValue().get(0));
        assertEquals(row, front.getFile().getFileUri());
        assertEquals(30, front.getFile().getId());
        assertTrue("the library reads MediaStore again when it is shown", vm.getUpdatePending().getValue());
        // MediaStore has it (by its path): no front photo any more
        store(folder("Camera", photo(30, "new.jpg"), photo(2, "b.jpg"), photo(1, "a.jpg")));
        vm.fetchAllMedia();
        vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue());
        assertEquals(3, vm.getCurrentFolderImages().getValue().size());
        assertNull(vm.front());
    }

    @Test
    public void aViewerOnAnotherPhotoKeepsItWhenAShotIsSaved() {
        store(folder("Camera", photo(2, "b.jpg"), photo(1, "a.jpg")));
        vm.fetchAllMedia();
        vm.setCurrentFolderImages(vm.getAllSelectedImageFolder().getValue());
        Uri viewed = vm.getCurrentFolderImages().getValue().get(1).getFile().getFileUri();
        vm.selectNext(viewed);
        vm.showFront(unfiled("new.jpg"), false);
        assertEquals("new.jpg", vm.getCurrentFolderImages().getValue().get(0).getFile().getDisplayName());
        assertEquals("the photo viewed, not the new one", viewed, vm.takePendingSelect());
    }

    @Test
    public void onlyAllGetsTheFrontPhoto() {
        store(folder("Camera", photo(2, "b.jpg")), folder("Raw", photo(5, "r.dng")));
        vm.fetchAllMedia();
        vm.showFront(unfiled("new.jpg"), true);
        assertEquals("Raw", vm.refreshKeeping("Raw"));
        assertEquals("a folder's own list", 1, vm.getCurrentFolderImages().getValue().size());
        assertEquals("ALL", vm.refreshKeeping("ALL"));
        assertEquals(3, vm.getCurrentFolderImages().getValue().size());
    }

    @Test
    public void theCamerasHandOverIsTakenOnce() {
        assertTrue(!vm.openedByCamera());
        com.scamera.app.gallery.ui.CameraHandoff.open(null, true);
        com.scamera.app.gallery.ui.CameraHandoff.Open o = com.scamera.app.gallery.ui.CameraHandoff.take();
        vm.setOpening(o);
        assertTrue(vm.openedByCamera());
        assertSame(o, vm.takeOpening());
        assertNull("the viewer's first view only", vm.takeOpening());
        assertTrue(vm.openedByCamera());
    }
}
