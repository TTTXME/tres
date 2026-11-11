package com.particlesdevs.photoncamera.gallery.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GalleryViewModel extends AndroidViewModel {
    private final MutableLiveData<GalleryItem> allSelectedImagesFolder = new MutableLiveData<>(GalleryItem.createEmpty());
    // P617: no value until the first read (the lists come from the loader thread now): an empty list first sent the viewer
    // opened from the camera on to the grid («nothing to show») before the photos were listed
    private final MutableLiveData<List<GalleryItem>> selectedDisplayFolders = new MutableLiveData<>();

    private final MutableLiveData<List<GalleryItem>> currentFolderImages = new MutableLiveData<>();
    private final MutableLiveData<Boolean> updatePendingLiveData=new MutableLiveData<>(false);


    public MutableLiveData<Boolean> getUpdatePending() {
        return updatePendingLiveData;
    }

    public void setUpdatePending(boolean updatePending) {
        updatePendingLiveData.setValue(updatePending);
    }

    public GalleryViewModel(@NonNull Application application) {
        super(application);
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
            allFolders = GalleryFileOperations._fetchSelectedFolders(getApplication().getContentResolver()).stream().map((Function<GalleryFileOperations.ImagesFolder, GalleryItem>) imagesFolder -> {
                GalleryItem folder = new GalleryItem(imagesFolder.getTopImage());
                imagesFolder.getAllImageFiles().forEach(imageFile -> folder.getFiles().add(new GalleryItem(imageFile)));
                folder.setDisplayName(imagesFolder.getFolderName());
                return folder;
            }).collect(Collectors.toList());
            all = (ArrayList<ImageFile>) GalleryFileOperations.extractAllSelectedImages();
        }
        GalleryItem allFolder;
        if (!all.isEmpty()) {
            allFolder = new GalleryItem(all.get(0));
            allFolder.setDisplayName("ALL");
            allFolder.getFiles().addAll(all.stream().map(GalleryItem::new).collect(Collectors.toList()));
            allFolders.add(0, allFolder);
            // P498: «Избранное» after «Все» when there are favourites
            java.util.Set<String> fav = com.particlesdevs.photoncamera.gallery.model.GalleryView.favourites(getApplication());
            List<GalleryItem> favs = new ArrayList<>();
            for (GalleryItem i : allFolder.getFiles()) if (fav.contains(com.particlesdevs.photoncamera.gallery.model.GalleryView.key(i.getFile()))) favs.add(i);
            if (!favs.isEmpty()) {
                GalleryItem favourites = new GalleryItem(favs.get(0).getFile());
                favourites.setDisplayName(com.particlesdevs.photoncamera.gallery.model.GalleryView.FAVOURITES);
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
        allSelectedImagesFolder.setValue(m.all);
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
        final long t0 = android.os.SystemClock.uptimeMillis();
        LOADER.execute(() -> {
            Media m = null;
            try {
                m = read();
            } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
                // the main thread's read threw out of the activity; here the gallery shows its empty state and the log says why
                com.particlesdevs.photoncamera.util.Log.w("GalleryMedia", "load: " + e);
            }
            final Media read = m;
            final long readMs = android.os.SystemClock.uptimeMillis() - t0;
            main.post(() -> {
                if (seq == loadSeq) publish(read != null ? read : empty()); // a failed read: the gallery's empty state, not a crash
                com.particlesdevs.photoncamera.gallery.ui.GalleryOpenTrace.listed(read != null ? read.all.getFiles().size() : -1, readMs);
                if (then != null) then.run();
            });
        });
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
            shown = allSelectedImagesFolder.getValue();
            keep = folders == null || folders.isEmpty() ? null : folders.get(0).getDisplayName();
        }
        setCurrentFolderImages(shown);
        return keep;
    }

    public LiveData<GalleryItem> getAllSelectedImageFolder() {
        return allSelectedImagesFolder;
    }

    public MutableLiveData<List<GalleryItem>> getCurrentFolderImages() {
        return currentFolderImages;
    }

    public void setCurrentFolderImages(GalleryItem currentFolder) {
        currentFolderName = currentFolder != null ? currentFolder.getDisplayName() : null;
        currentFolder_ = currentFolder;
        // P498: the view's filter / order and the photos pending deletion (GalleryView), the same list for the grid and the viewer
        final boolean fav = com.particlesdevs.photoncamera.gallery.model.GalleryView.FAVOURITES.equals(currentFolderName);
        currentFolderImages.setValue(com.particlesdevs.photoncamera.gallery.model.GalleryView.active(getApplication(), fav)
                ? com.particlesdevs.photoncamera.gallery.model.GalleryView.apply(getApplication(), currentFolder.getFiles(), fav)
                : currentFolder.getFiles());
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
            if (folder == null) folder = allSelectedImagesFolder.getValue();
        }
        List<GalleryItem> list;
        if (folder != null && currentFolderName != null) {
            setCurrentFolderImages(folder);
            list = currentFolderImages.getValue();
        } else {
            list = new ArrayList<>(currentFolderImages.getValue() != null ? currentFolderImages.getValue() : new ArrayList<>());
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
            currentFolderImages.setValue(list);
        }
        pendingSelect = select;
        setUpdatePending(true);
    }

    /**
     * The same MediaStore row: equal URIs, or equal row ids (content://media/external_primary/images/media/42 is the gallery's
     * content://media/external/images/media/42).
     */
    static boolean sameMedia(ImageFile a, @androidx.annotation.Nullable com.particlesdevs.photoncamera.gallery.files.MediaFile b) {
        if (b == null) return false;
        if (a.getFileUri() != null && a.getFileUri().equals(b.getFileUri())) return true;
        return a.getId() >= 0 && b instanceof ImageFile && a.getId() == b.getId();
    }

    public MutableLiveData<List<GalleryItem>> getSelectedDisplayFolders() {
        return selectedDisplayFolders;
    }
}
