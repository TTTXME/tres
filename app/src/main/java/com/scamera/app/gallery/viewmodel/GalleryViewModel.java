package com.scamera.app.gallery.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.scamera.app.gallery.files.GalleryFileOperations;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.model.GalleryItem;

import java.util.ArrayList;
import java.util.List;

public class GalleryViewModel extends AndroidViewModel {
    /** «ALL»: every photo of the chosen folders, newest first. */
    private final MutableLiveData<GalleryItem> allPhotos = new MutableLiveData<>(GalleryItem.createEmpty());
    // P617: no value until the first read (the lists come from the loader thread now): an empty list first sent the viewer
    // opened from the camera on to the grid («nothing to show») before the photos were listed
    private final MutableLiveData<List<GalleryItem>> selectedDisplayFolders = new MutableLiveData<>();

    /** The photos the viewer pages through. */
    private final MutableLiveData<List<GalleryItem>> shownPhotos = new MutableLiveData<>();
    /** The lists must be read again (a folder choice changed, a file was deleted elsewhere). */
    private final MutableLiveData<Boolean> stale = new MutableLiveData<>(false);

    public GalleryViewModel(@NonNull Application application) {
        super(application);
    }

    public MutableLiveData<Boolean> getUpdatePending() {
        return stale;
    }

    public void setUpdatePending(boolean pending) {
        stale.setValue(pending);
    }

    /** The media lists read now (on the calling thread) and set. */
    public void fetchAllMedia() {
        loadSeq++; // a load still running is older than this
        publish(read());
    }

    /** P617: what one read of MediaStore gives: the chips' folders («ALL» and «Избранное» first) and «ALL». */
    static final class Media {
        final List<GalleryItem> folders;
        final GalleryItem all;

        Media(List<GalleryItem> folders, GalleryItem all) {
            this.folders = folders;
            this.all = all;
        }
    }

    /** The selected folders, «ALL» and «Избранное» from MediaStore; any thread (the folder lists are one lock). */
    Media read() {
        List<GalleryItem> allFolders;
        ArrayList<ImageFile> all;
        synchronized (GalleryFileOperations.class) {
            allFolders = new ArrayList<>();
            for (GalleryFileOperations.ImagesFolder source : GalleryFileOperations._fetchSelectedFolders(getApplication().getContentResolver())) {
                GalleryItem folder = new GalleryItem(source.getTopImage()); // its cover: the newest file
                folder.setDisplayName(source.getFolderName());
                for (ImageFile f : source.getAllImageFiles()) folder.getFiles().add(new GalleryItem(f));
                allFolders.add(folder);
            }
            all = (ArrayList<ImageFile>) GalleryFileOperations.extractAllSelectedImages();
        }
        GalleryItem allFolder;
        if (!all.isEmpty()) {
            allFolder = new GalleryItem(all.get(0));
            allFolder.setDisplayName("ALL");
            for (ImageFile f : all) allFolder.getFiles().add(new GalleryItem(f));
            allFolders.add(0, allFolder);
            // P498: «Избранное» after «Все» when there are favourites
            java.util.Set<String> fav = com.scamera.app.gallery.model.GalleryView.favourites(getApplication());
            List<GalleryItem> favs = new ArrayList<>();
            for (GalleryItem i : allFolder.getFiles()) if (fav.contains(com.scamera.app.gallery.model.GalleryView.key(i.getFile()))) favs.add(i);
            if (!favs.isEmpty()) {
                GalleryItem favourites = new GalleryItem(favs.get(0).getFile());
                favourites.setDisplayName(com.scamera.app.gallery.model.GalleryView.FAVOURITES);
                favourites.getFiles().addAll(favs);
                allFolders.add(1, favourites);
            }
        } else {
            // every selected folder is empty: no stale «ALL» (its deleted photos came back in the grid and the viewer)
            allFolder = GalleryItem.createEmpty();
            allFolder.setDisplayName("ALL");
        }
        return new Media(allFolders, allFolder);
    }

    private static Media empty() {
        GalleryItem none = GalleryItem.createEmpty();
        none.setDisplayName("ALL");
        return new Media(new ArrayList<>(), none);
    }

    /** On the main thread: the lists of {@code m} for the chips, the grid and the viewer. */
    private void publish(Media m) {
        allPhotos.setValue(m.all);
        selectedDisplayFolders.setValue(m.folders);
    }

    /**
     * P617 (owner, 2026-11-11: «иногда очень долго открывается галерея», ~9000 photos): MediaStore read on the gallery's loader
     * thread instead of the main one (the window and its first frame waited for three queries over the whole library), the lists
     * set on the main thread, then {@code then}. A later load or {@link #fetchAllMedia} wins over an older one still running (its
     * lists are not set; {@code then} still runs).
     */
    public void loadAsync(@androidx.annotation.Nullable Runnable then) {
        final int seq = ++loadSeq;
        final long asked = android.os.SystemClock.uptimeMillis();
        LOADER.execute(() -> {
            final long t0 = android.os.SystemClock.uptimeMillis(); // P637: after the wait for an older read
            Media m = null;
            try {
                m = read();
            } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
                // the main thread's read threw out of the activity; here the gallery shows its empty state and the log says why
                com.scamera.app.util.Log.w("GalleryMedia", "load: " + e);
            }
            final Media read = m;
            final long done = android.os.SystemClock.uptimeMillis(), readMs = done - t0;
            main.post(() -> {
                if (seq == loadSeq) publish(read != null ? read : empty()); // a failed read: the gallery's empty state, not a crash
                // P637: where the time went - waiting for the loader thread, the read, waiting for the main thread
                com.scamera.app.gallery.ui.GalleryOpenTrace.listed(read != null ? read.all.getFiles().size() : -1, readMs,
                        t0 - asked, android.os.SystemClock.uptimeMillis() - done);
                if (then != null) then.run();
                if (read != null) checkOtherRaw(); // P645: after the list is out, never ahead of it
            });
        });
    }

    // ---------------------------------------------------------------- P645: the other RAWs join after the first list

    /** P645: the other-RAW check, one at a time and off the loader thread (a later load does not wait for it). */
    private static final java.util.concurrent.ExecutorService RAW_CHECK = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalleryRaw");
        t.setDaemon(true);
        return t;
    });
    /** A check is queued and not started yet: another ask adds nothing. */
    private static final java.util.concurrent.atomic.AtomicBoolean RAW_QUEUED = new java.util.concurrent.atomic.AtomicBoolean();
    /** The shown list is being replaced by the RAW join: the grid and the viewer keep the item they show (main thread). */
    private boolean joining;

    /**
     * P645 (owner, 2026-11-12, vivo X200 Ultra: «other RAW 61 rows 5772 ms» of the 5877 ms before the gallery had any list): the
     * RAWs MediaStore does not file as images are brought up to date on their own thread after the list is set (OtherRawCache: no
     * query while MediaStore is unchanged, else the known rows by id and the rows changed since); a changed set is read into the
     * lists and joins «ALL» where the grid and the viewer show it, keeping the item on the screen ({@link #joining}).
     */
    private void checkOtherRaw() {
        if (!RAW_QUEUED.compareAndSet(false, true)) return;
        final android.app.Application app = getApplication();
        final android.content.ContentResolver resolver = app.getContentResolver();
        final long asked = android.os.SystemClock.uptimeMillis();
        try {
            RAW_CHECK.execute(() -> {
                RAW_QUEUED.set(false);
                com.scamera.app.gallery.files.OtherRawCache.Result r = null;
                try {
                    r = GalleryFileOperations.checkOtherRaw(app, resolver);
                } catch (RuntimeException | LinkageError e) {
                    com.scamera.app.util.Log.w("GalleryMedia", "other RAW check: " + e);
                }
                if (r == null || !r.changed) return;
                final com.scamera.app.gallery.files.OtherRawCache.Result found = r;
                main.post(() -> joinOtherRaw(found, asked));
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            RAW_QUEUED.set(false);
        }
    }

    /** P645: the changed RAWs into the lists (read again: the images and videos are reused or read in ~0.1 s). Main thread. */
    private void joinOtherRaw(com.scamera.app.gallery.files.OtherRawCache.Result r, long asked) {
        loadAsync(() -> {
            com.scamera.app.gallery.ui.GalleryOpenTrace.rawJoined(r.rows, android.os.SystemClock.uptimeMillis() - asked);
            // only «ALL» is re-shown (an album or «Избранное» keeps its list; its chip / the albums page get the new lists)
            if (!"ALL".equals(currentFolderName) || shownBase == null) return;
            joining = true;
            try {
                showKeeping("ALL");
            } finally {
                joining = false;
            }
        });
    }

    /** P645: the list set now is the RAW join: whoever shows a list keeps the item it shows (main thread, during the set). */
    public boolean joining() {
        return joining;
    }

    /** P617: the gallery's MediaStore reads, one at a time, off the main thread. */
    private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalleryMedia");
        t.setDaemon(true);
        return t;
    });
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** The newest load (main thread). */
    private int loadSeq;

    /** P617: {@link #refreshKeeping} with MediaStore read on the loader thread; {@code done} gets its result on the main thread. */
    public void refreshKeepingAsync(@androidx.annotation.Nullable String keep, java.util.function.Consumer<String> done) {
        loadAsync(() -> done.accept(showKeeping(keep)));
    }

    /**
     * The media lists read again and the folder named {@code keep} shown again («ALL» when it is gone or null), so the library's
     * grid and the viewer use the same list after a delete or a folders change. Returns the name of the folder shown (the first
     * chip when {@code keep} is gone), null without folders.
     */
    @androidx.annotation.Nullable
    public String refreshKeeping(@androidx.annotation.Nullable String keep) {
        fetchAllMedia();
        return showKeeping(keep);
    }

    /** The folder named {@code keep} of the lists read last shown («ALL» when it is gone); the name shown, null without folders. */
    @androidx.annotation.Nullable
    private String showKeeping(@androidx.annotation.Nullable String keep) {
        List<GalleryItem> folders = selectedDisplayFolders.getValue();
        GalleryItem shown = null;
        if (folders != null && keep != null) for (GalleryItem f : folders) if (keep.equals(f.getDisplayName())) shown = f;
        if (shown == null) {
            shown = allPhotos.getValue();
            keep = folders == null || folders.isEmpty() ? null : folders.get(0).getDisplayName();
        }
        setCurrentFolderImages(shown);
        return keep;
    }

    public LiveData<GalleryItem> getAllSelectedImageFolder() {
        return allPhotos;
    }

    public MutableLiveData<List<GalleryItem>> getCurrentFolderImages() {
        return shownPhotos;
    }

    public void setCurrentFolderImages(GalleryItem currentFolder) {
        currentFolderName = currentFolder != null ? currentFolder.getDisplayName() : null;
        currentFolder_ = currentFolder;
        // P498: the view's filter / order and the photos pending deletion (GalleryView), the same list for the grid and the viewer
        final boolean fav = com.scamera.app.gallery.model.GalleryView.FAVOURITES.equals(currentFolderName);
        // P637: the viewer showed the camera's last photo alone so far: it stays on that photo in the list read now
        if (shownBase == null && front != null) frontSelect = true;
        shownBase = com.scamera.app.gallery.model.GalleryView.active(getApplication(), fav)
                ? com.scamera.app.gallery.model.GalleryView.apply(getApplication(), currentFolder.getFiles(), fav)
                : currentFolder.getFiles();
        shownPhotos.setValue(withFront(shownBase));
    }

    // ---------------------------------------------------------------- P637: the camera's newest photo ahead of the lists

    /**
     * P637 (owner, 2026-11-12: the viewer opened from the camera stayed black until MediaStore was read and, right after a shot,
     * until the shot was saved and scanned): the camera's newest photo, known by its file in this process, shown first while the
     * media lists do not have it - before the first read, and between its save and the scanner's row. It leaves when a read
     * finds the same file (by path or row). Only «ALL» (and the list before the first read) gets it.
     */
    @androidx.annotation.Nullable private GalleryItem front;
    /** The viewer moves to the front photo when it binds the next list. */
    private boolean frontSelect;
    /** The folder shown, filtered, without the front photo; null before the first list. */
    @androidx.annotation.Nullable private List<GalleryItem> shownBase;

    /**
     * Shows {@code file} first in the viewer's list now ({@code select}: and moves the viewer to it; false: the viewer stays on
     * the photo it shows, the caller says which with {@link #selectNext}). A file the list already has is only selected.
     */
    public void showFront(ImageFile file, boolean select) {
        front = new GalleryItem(file);
        frontSelect = select;
        shownPhotos.setValue(withFront(shownBase != null ? shownBase : new ArrayList<>()));
    }

    /** What the camera handed to this gallery at its tap (CameraHandoff), for the viewer's first view; null otherwise. */
    @androidx.annotation.Nullable private com.scamera.app.gallery.ui.CameraHandoff.Open opening;

    public void setOpening(@androidx.annotation.Nullable com.scamera.app.gallery.ui.CameraHandoff.Open o) {
        opening = o;
        if (o != null) byCamera = true;
    }

    private boolean byCamera;

    /** This gallery was opened by the camera's thumbnail (its viewer hears the camera's saves). */
    public boolean openedByCamera() {
        return byCamera;
    }

    /** Once: the viewer's first view takes it. */
    @androidx.annotation.Nullable
    public com.scamera.app.gallery.ui.CameraHandoff.Open takeOpening() {
        com.scamera.app.gallery.ui.CameraHandoff.Open o = opening;
        opening = null;
        return o;
    }

    /** The front photo while the lists do not have it, else null. */
    @androidx.annotation.Nullable
    public GalleryItem front() {
        return front;
    }

    /**
     * The scanner filed the front photo: the same item gets its MediaStore row (the viewer's page stays as it is - the picture
     * is the same file - and share / edit / delete have a row to work on). False when {@code path} is not the front photo.
     */
    public boolean frontScanned(String path, android.net.Uri row) {
        if (front == null || path == null || !path.equals(front.getFile().getAbsolutePath())) return false;
        com.scamera.app.gallery.files.MediaFile f = front.getFile();
        long id;
        try {
            id = android.content.ContentUris.parseId(row);
        } catch (RuntimeException e) {
            return false;
        }
        front.setFile(new ImageFile(id, row, f.getDisplayName(), f.getLastModified(), f.getSize(), f.getAbsolutePath()));
        setUpdatePending(true); // MediaStore has the row now: the library reads it when it is next shown
        return true;
    }

    /** {@code list} with the front photo first when it applies and the list does not have it; selects it when asked. */
    private List<GalleryItem> withFront(List<GalleryItem> list) {
        if (front == null) return list;
        if (currentFolderName != null && !"ALL".equals(currentFolderName)) return list;
        final com.scamera.app.gallery.files.MediaFile f = front.getFile();
        for (GalleryItem i : list) {
            if (!sameFile(f, i.getFile())) continue;
            // the lists have it now: their item from here on
            if (frontSelect || (pendingSelect != null && pendingSelect.equals(f.getFileUri()))) pendingSelect = i.getFile().getFileUri();
            front = null;
            frontSelect = false;
            return list;
        }
        List<GalleryItem> out = new ArrayList<>(list.size() + 1);
        out.add(front);
        out.addAll(list);
        if (frontSelect) pendingSelect = f.getFileUri();
        frontSelect = false;
        return out;
    }

    /** The same file: equal paths, else the same MediaStore row. */
    public static boolean sameFile(com.scamera.app.gallery.files.MediaFile a, @androidx.annotation.Nullable com.scamera.app.gallery.files.MediaFile b) {
        if (b == null) return false;
        if (a.getAbsolutePath() != null && a.getAbsolutePath().startsWith("/") && a.getAbsolutePath().equals(b.getAbsolutePath())) return true;
        return a instanceof ImageFile && sameMedia((ImageFile) a, b);
    }

    /** P498: the folder shown, before the view's filter. */
    @androidx.annotation.Nullable private GalleryItem currentFolder_;

    /** P498: the shown folder filtered again (a view option, a favourite or a pending delete changed). */
    public void applyView() {
        if (currentFolder_ != null) setCurrentFolderImages(currentFolder_);
    }

    /** P351: the folder the viewer shows («ALL» for every selected folder), null for a list built by hand (another app's file). */
    @androidx.annotation.Nullable private String currentFolderName;
    /** P351: the photo the viewer moves to when it next binds its list (the editor's new copy), null for none. */
    @androidx.annotation.Nullable private android.net.Uri pendingSelect;

    /** The folder shown now («ALL» for every selected folder), null for a list built by hand. */
    @androidx.annotation.Nullable
    public String getCurrentFolderName() {
        return currentFolderName;
    }

    /** The photo the viewer moves to when it next binds its list (the photo another app asked to view), null for none. */
    public void selectNext(@androidx.annotation.Nullable android.net.Uri uri) {
        pendingSelect = uri;
    }

    @androidx.annotation.Nullable
    public android.net.Uri takePendingSelect() {
        android.net.Uri u = pendingSelect;
        pendingSelect = null;
        return u;
    }

    /**
     * P351: after «Сохранить копию»: the media lists read again, the viewer's folder rebuilt (or every folder), the copy put
     * first when the lists do not have it (a folder the gallery does not show, another app's file), and selected next.
     */
    public void showNewFile(ImageFile copy) {
        fetchAllMedia();
        GalleryItem folder = null;
        List<GalleryItem> folders = selectedDisplayFolders.getValue();
        if (folders != null) {
            for (GalleryItem f : folders)
                if (currentFolderName != null && currentFolderName.equals(f.getDisplayName())) folder = f;
            if (folder == null) folder = allPhotos.getValue();
        }
        List<GalleryItem> list;
        if (folder != null && currentFolderName != null) {
            setCurrentFolderImages(folder);
            list = shownPhotos.getValue();
        } else {
            list = new ArrayList<>(shownPhotos.getValue() != null ? shownPhotos.getValue() : new ArrayList<>());
        }
        boolean found = false;
        android.net.Uri select = copy.getFileUri();
        if (list != null)
            for (GalleryItem i : list)
                if (sameMedia(copy, i.getFile())) {
                    found = true;
                    select = i.getFile().getFileUri(); // the list's own form of the row's URI (the viewer matches it)
                }
        if (!found && list != null) {
            list = new ArrayList<>(list);
            list.add(0, new GalleryItem(copy));
            shownPhotos.setValue(list);
        }
        pendingSelect = select;
        setUpdatePending(true);
    }

    /**
     * The same MediaStore row: equal URIs, or equal row ids (content://media/external_primary/images/media/42 is the gallery's
     * content://media/external/images/media/42).
     */
    static boolean sameMedia(ImageFile a, @androidx.annotation.Nullable com.scamera.app.gallery.files.MediaFile b) {
        if (b == null) return false;
        if (a.getFileUri() != null && a.getFileUri().equals(b.getFileUri())) return true;
        return a.getId() >= 0 && b instanceof ImageFile && a.getId() == b.getId();
    }

    public MutableLiveData<List<GalleryItem>> getSelectedDisplayFolders() {
        return selectedDisplayFolders;
    }
}
