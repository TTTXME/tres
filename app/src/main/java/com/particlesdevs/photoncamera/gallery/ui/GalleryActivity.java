package com.particlesdevs.photoncamera.gallery.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.base.BaseActivity;
import com.particlesdevs.photoncamera.databinding.ActivityGalleryBinding;
import com.particlesdevs.photoncamera.gallery.editor.PhotoEditorFragment;
import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.ui.fragments.ImageLibraryFragment;
import com.particlesdevs.photoncamera.gallery.ui.fragments.ImageViewerFragment;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.ArrayList;
import java.util.List;

public class GalleryActivity extends BaseActivity {
    private ActivityGalleryBinding activityGalleryBinding;
    private GalleryViewModel viewModel;

    private boolean externalUsage = false;

    /** P495: from the camera, the iOS back - the gallery leaves to the right over the camera coming back. */
    @SuppressWarnings("deprecation")
    @Override
    public void finish() {
        GalleryUndo.flush(); // P498: a delete still waiting for «Вернуть» is done before the camera is back
        super.finish();
        if (!externalUsage) overridePendingTransition(R.anim.motion_pop_enter, R.anim.motion_pop_exit);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide system UI immediately to prevent flickering
        hideSystemUI();
        
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            Object fragment = getIntent().getExtras().get("CameraFragment");
            externalUsage = fragment == null;
        } else {
            externalUsage = true;
        }

        getDelegate().setLocalNightMode(PreferenceKeys.getThemeValue());
        activityGalleryBinding = DataBindingUtil.setContentView(this, R.layout.activity_gallery);
        // P584: the gallery in the settings concept's font (Roboto Flex at each text's weight, sizes in dp)
        com.particlesdevs.photoncamera.ui.settings.ConceptFonts.follow(getWindow().getDecorView());
        viewModel = new ViewModelProvider(this).get(GalleryViewModel.class);
        
        // A recreation (rotation, a theme change, process death): the NavHostFragment restored the back stack and each entry's
        // arguments, so nothing navigates again (setGraph with another start popped it: the editor and its unsaved edits were lost,
        // the launcher route went back to the library). The lists are read again only for a new GalleryViewModel (the first start,
        // or after process death); a surviving one keeps the folder the viewer shows.
        boolean fresh = savedInstanceState == null;
        List<GalleryItem> shown = viewModel.getCurrentFolderImages().getValue();
        boolean refill = fresh || shown == null || shown.isEmpty();
        Bundle editArgs = null;
        // P621: what the intent asks for (routeOf); a video VIEW / REVIEW plays in the gallery's player
        Route route = routeOf(intent, intent.getData() != null ? nameOf(intent.getData()) : null);
        if (route == Route.VIDEO) {
            com.particlesdevs.photoncamera.gallery.video.VideoPlayerActivity.openExternal(this, intent.getData(), nameOf(intent.getData()));
            finish();
            return;
        }
        picking = route == Route.PICK;
        secureReview = route == Route.SECURE_REVIEW;
        if (secureReview && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) setShowWhenLocked(true);
        if (picking) {
            externalUsage = true;
            pickKind = pickKindOf(intent);
        }
        // Check if this is a VIEW or REVIEW intent from another app (like HedgeCam2)
        if (isViewIntent(intent)) {
            if (refill) handleExternalViewIntent(intent.getData(), fresh);
        } else if (isEditIntent(intent)) {
            externalUsage = true;
            if (refill) editArgs = editorFor(intent.getData(), intent.getType());
        } else if (refill) {
            // Normal gallery mode - show all media. P617: MediaStore read off the main thread (the window waited for it); the viewer
            // and the grid fill when the lists arrive
            // P637: opened by the camera's thumbnail - what the camera hands over (its thumbnail or the shutter frame of a shot still
            // processed, its last saved file) is on screen in the viewer's first frame, before any read of MediaStore
            CameraHandoff.Open handed = route == Route.CAMERA && fresh ? CameraHandoff.take() : null;
            GalleryOpenTrace.opened(externalUsage ? "launcher" : "camera", handed != null ? handed.tapAt : 0);
            if (handed != null) {
                viewModel.setOpening(handed);
                if (handed.latest != null) {
                    viewModel.showFront(handed.latest, true);
                    GalleryOpenTrace.front(handed.latest.getDisplayName(), !CameraHandoff.unfiled(handed.latest));
                }
            }
            boostDecodes();
            viewModel.loadAsync(() -> viewModel.setCurrentFolderImages(viewModel.getAllSelectedImageFolder().getValue()));
        }
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
        if (host != null) startNavigation(host.getNavController(), intent, fresh, editArgs);
    }

    /** P621: MediaStore.ACTION_REVIEW / ACTION_REVIEW_SECURE (Android 10+), what a camera sends to show the photo it took. */
    static final String ACTION_REVIEW_Q = "android.provider.action.REVIEW", ACTION_REVIEW_SECURE = "android.provider.action.REVIEW_SECURE";
    /** P621: the launcher's «gallery» category (Intent.CATEGORY_APP_GALLERY; a main-selector intent of the system or a launcher). */
    static final String CATEGORY_APP_GALLERY = "android.intent.category.APP_GALLERY";

    /**
     * P621 (owner, 2026-11-11: «сделай чтобы нашу галерею можно было выбирать в системе как по умолчанию»): the screen an intent
     * opens - VIEWER: a photo (its folder's neighbours when MediaStore knows it, else just that item); SECURE_REVIEW: the photo
     * alone over the lock screen; VIDEO: the gallery's player; EDIT: the editor; PICK: the library, a tap returns the item;
     * LIBRARY: the launcher icon, the «gallery» category; CAMERA: the camera's own thumbnail (the newest photo).
     */
    enum Route { VIEWER, SECURE_REVIEW, VIDEO, EDIT, PICK, LIBRARY, CAMERA }

    static Route routeOf(Intent intent, @Nullable String name) {
        if (isViewIntent(intent)) {
            if (isVideo(intent.getType(), intent.getData(), name)) return Route.VIDEO;
            return ACTION_REVIEW_SECURE.equals(intent.getAction()) ? Route.SECURE_REVIEW : Route.VIEWER;
        }
        if (isEditIntent(intent)) return Route.EDIT;
        if (isPickIntent(intent)) return Route.PICK;
        if (Intent.ACTION_MAIN.equals(intent.getAction())
                && (intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.hasCategory(CATEGORY_APP_GALLERY))) return Route.LIBRARY;
        return Route.CAMERA;
    }

    static boolean isViewIntent(Intent intent) {
        String action = intent.getAction();
        return (Intent.ACTION_VIEW.equals(action) || Constants.ACTION_REVIEW.equals(action) || ACTION_REVIEW_Q.equals(action)
                || ACTION_REVIEW_SECURE.equals(action)) && intent.getData() != null;
    }

    /** P621: a video by its type, MediaStore's video collection, or its name (file managers send no type or octet-stream). */
    static boolean isVideo(@Nullable String type, @Nullable Uri uri, @Nullable String name) {
        if (type != null && (type.startsWith("video/") || type.equals("vnd.android.cursor.item/video"))) return true;
        if (type != null && type.startsWith("image/")) return false;
        if (uri != null && "media".equals(uri.getAuthority()) && uri.getPath() != null && uri.getPath().contains("/video/")) return true;
        return com.particlesdevs.photoncamera.gallery.files.GalleryVideo.isVideoName(name);
    }

    /**
     * P621: PICK / GET_CONTENT of photos or videos, a single item back; any type ("*" + "/" + "*") too (the system matches it to
     * every filter with a type: a photo or a video is an answer to it).
     */
    static boolean isPickIntent(Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_PICK.equals(a) && !Intent.ACTION_GET_CONTENT.equals(a)) return false;
        String type = intent.getType();
        if (type == null) return Intent.ACTION_PICK.equals(a) && intent.getData() != null && "media".equals(intent.getData().getAuthority());
        return type.equals("*/*") || type.startsWith("image/") || type.startsWith("video/") || type.equals("vnd.android.cursor.dir/image")
                || type.equals("vnd.android.cursor.dir/video");
    }

    /** P621: what a pick may return: «image», «video» or «any» (a MediaStore collection URI of a type-less PICK). */
    static String pickKindOf(Intent intent) {
        String type = intent.getType();
        if (type == null && intent.getData() != null && intent.getData().getPath() != null)
            type = intent.getData().getPath().contains("/video/") ? "video/*" : intent.getData().getPath().contains("/images/") ? "image/*" : null;
        if (type == null) return "any";
        if (type.startsWith("image/") || type.equals("vnd.android.cursor.dir/image")) return "image";
        if (type.startsWith("video/") || type.equals("vnd.android.cursor.dir/video")) return "video";
        return "any";
    }

    /** P621: whether a VIEW of {@code uri} shows its neighbours: a MediaStore item, or a file path (matched in the library). */
    static boolean withNeighbours(@Nullable Uri uri) {
        if (uri == null) return false;
        return "file".equals(uri.getScheme()) || ("content".equals(uri.getScheme()) && "media".equals(uri.getAuthority()));
    }

    /** P621: whether a picked item fits the pick's kind. */
    static boolean pickable(String kind, @Nullable com.particlesdevs.photoncamera.gallery.files.MediaFile f) {
        if (f == null || f.getFileUri() == null) return false;
        boolean video = com.particlesdevs.photoncamera.gallery.files.GalleryVideo.isVideo(f);
        return "any".equals(kind) || ("video".equals(kind) == video);
    }

    /** P621: the result of a pick: the item's URI, readable by the caller (a grant and ClipData for GET_CONTENT callers). */
    static Intent pickResult(Uri uri) {
        Intent r = new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        r.setClipData(android.content.ClipData.newRawUri("", uri));
        return r;
    }

    private boolean picking, secureReview;
    private String pickKind = "any";

    /** P621: the library returns a tapped item instead of opening it. */
    public boolean picking() {
        return picking;
    }

    /** P621: a lock-screen review: the photo alone, leaving it closes the gallery (no library over the lock screen). */
    public boolean secureReview() {
        return secureReview;
    }

    /** P621: a tapped item while picking: returned when it is of the kind asked for, else false (the caller says so). */
    public boolean pick(@Nullable GalleryItem item) {
        if (item == null || !pickable(pickKind, item.getFile())) return false;
        setResult(RESULT_OK, pickResult(item.getFile().getFileUri()));
        finish();
        return true;
    }

    /** The display name of a URI (OpenableColumns), else its last path segment; null when neither. */
    @Nullable
    private String nameOf(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) >= 0) {
                String n = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    static boolean isEditIntent(Intent intent) {
        return !isViewIntent(intent) && Intent.ACTION_EDIT.equals(intent.getAction()) && intent.getData() != null;
    }

    /**
     * The start destination of a new gallery. P351: ACTION_EDIT from another app starts on the editor ({@code editArgs}). P347
     * (owner, 2026-10-26: «когда открывал галерею через иконку на рабочем столе открывалось не последнее фото, а открывался наш
     * список по папкам как обычно»): the gallery's own launcher icon starts on the library (the folders and the photo grid); the
     * camera's thumbnail and VIEW / REVIEW still open the viewer (the graph's start destination). Back from a photo opened there
     * returns to the library. When {@code fresh} is false (a recreation) the restored back stack stays and only the start
     * destination is put back into the graph the NavHostFragment inflated again.
     */
    static void startNavigation(NavController nav, Intent intent, boolean fresh, @Nullable Bundle editArgs) {
        int start;
        Route route = routeOf(intent, null);
        if (route == Route.EDIT) start = R.id.photoEditorFragment;
        else if (route == Route.LIBRARY || route == Route.PICK) start = R.id.imageLibraryFragment; // P621: the «gallery» category, a pick
        else return;
        if (!fresh) {
            // the XML graph again (start: the viewer) under the restored back stack: navigateUp at the root library (or editor)
            // relaunched the gallery on the viewer at the newest photo instead of leaving
            nav.getGraph().setStartDestination(start);
            return;
        }
        androidx.navigation.NavGraph graph = nav.getNavInflater().inflate(R.navigation.gallery_nav_graph);
        graph.setStartDestination(start);
        nav.setGraph(graph, start == R.id.photoEditorFragment ? editArgs : null);
    }

    /** The position of another app's photo in the gallery's list: by URI, absolute path, or display name and size; -1 for none. */
    static int positionOf(List<GalleryItem> items, Uri uri, ImageFile external) {
        String externalPath = uri.getPath();
        String externalDisplayName = external.getDisplayName();
        for (int i = 0; i < items.size(); i++) {
            GalleryItem item = items.get(i);
            if (item.getFile() == null) continue;
            // Try to match by URI (most reliable for content:// URIs)
            if (uri.equals(item.getFile().getFileUri())) return i;
            // Try to match by absolute path (for file:// URIs)
            if (externalPath != null && item.getFile().getAbsolutePath() != null && externalPath.equals(item.getFile().getAbsolutePath()))
                return i;
            // Try to match by display name and size as last resort
            if (externalDisplayName != null && externalDisplayName.equals(item.getFile().getDisplayName())
                    && external.getSize() == item.getFile().getSize()) return i;
        }
        return -1;
    }

    /**
     * P637: the photo another app asked for among the library's ({@code items}: «ALL»): its item there, or - a file the library
     * does not have - put first. Returns the URI of the item the viewer moves to.
     */
    static Uri amongNeighbours(List<GalleryItem> items, Uri uri, ImageFile asked) {
        int at = positionOf(items, uri, asked);
        if (at < 0) {
            items.add(0, new GalleryItem(asked));
            at = 0;
        }
        return items.get(at).getFile().getFileUri();
    }

    /** P637: the open's decodes at the default priority for its first seconds (ahead of a shot still processed). */
    private static void boostDecodes() {
        boolean busy = false;
        try {
            busy = com.particlesdevs.photoncamera.processing.ProcessingKeepAlive.busy.getAsBoolean();
        } catch (RuntimeException ignored) {
        }
        com.particlesdevs.photoncamera.gallery.glide.DecodeBoost.forOpen(busy);
    }

    /**
     * Handle VIEW intent from external apps (like HedgeCam2)
     * Loads all gallery images but starts viewing at the selected image ({@code select}: a new gallery; after process death the
     * viewer's saved position applies)
     */
    private void handleExternalViewIntent(Uri uri, boolean select) {
        GalleryOpenTrace.opened(secureReview ? "secure review" : "view");
        boostDecodes();
        if (secureReview || !withNeighbours(uri)) {
            // P621: another app's file (a FileProvider, Downloads, a document) or a lock-screen review: that item alone, without
            // reading the library (it was put in front of the whole library before)
            List<GalleryItem> single = new ArrayList<>();
            single.add(new GalleryItem(createImageFileFromUri(uri)));
            viewModel.getCurrentFolderImages().setValue(single);
            return;
        }
        if (select) {
            // P637: a new gallery - the photo asked for is the viewer's page at once; MediaStore (the photo's neighbours) is read
            // on the loader thread and the list put under it (the window waited for three queries over the whole library before)
            final ImageFile asked = createImageFileFromUri(uri);
            List<GalleryItem> single = new ArrayList<>();
            single.add(new GalleryItem(asked));
            viewModel.getCurrentFolderImages().setValue(single);
            viewModel.loadAsync(() -> {
                GalleryItem all = viewModel.getAllSelectedImageFolder().getValue();
                if (all == null || all.getFiles().isEmpty()) return; // no library: the photo alone, as it is shown
                viewModel.selectNext(amongNeighbours(all.getFiles(), uri, asked));
                viewModel.setCurrentFolderImages(all);
            });
            return;
        }
        try {
            // Load all media from the gallery first
            viewModel.fetchAllMedia();
            
            // Create an ImageFile from the external URI
            ImageFile externalImageFile = createImageFileFromUri(uri);
            
            // Get all images from the gallery
            GalleryItem allImagesFolder = viewModel.getAllSelectedImageFolder().getValue();
            
            int initialPosition = 0;
            
            if (allImagesFolder != null && !allImagesFolder.getFiles().isEmpty()) {
                List<GalleryItem> allImages = allImagesFolder.getFiles();

                // Try to find the external image in the existing gallery
                // Match by URI, absolute path, or display name
                int foundPosition = positionOf(allImages, uri, externalImageFile);

                if (foundPosition >= 0) {
                    // Image found in gallery, use its position
                    initialPosition = foundPosition;
                } else {
                    // Image not in gallery, add it at the beginning
                    GalleryItem externalGalleryItem = new GalleryItem(externalImageFile);
                    allImages.add(0, externalGalleryItem);
                    initialPosition = 0;
                }
                
                // Set the current folder images
                viewModel.setCurrentFolderImages(allImagesFolder);
            } else {
                // No gallery images found, create a single-item list with just the external image
                GalleryItem externalGalleryItem = new GalleryItem(externalImageFile);
                List<GalleryItem> singleItemList = new ArrayList<>();
                singleItemList.add(externalGalleryItem);
                viewModel.getCurrentFolderImages().setValue(singleItemList);
            }
            
            // The viewer (the graph's start, already created without arguments by the NavHostFragment) moves to the photo when it
            // binds the list. A start position passed by setGraph with the same graph was dropped: the newest photo opened instead.
            List<GalleryItem> shown = viewModel.getCurrentFolderImages().getValue();
            if (select && shown != null && initialPosition < shown.size())
                viewModel.selectNext(shown.get(initialPosition).getFile().getFileUri());
        } catch (Exception e) {
            e.printStackTrace();
            // Fallback to normal gallery mode if something goes wrong
            viewModel.fetchAllMedia();
            viewModel.setCurrentFolderImages(viewModel.getAllSelectedImageFolder().getValue());
        }
    }
    
    /**
     * P351: another app asked this gallery to edit a photo (ACTION_EDIT): the editor alone (the graph starts there,
     * {@link #startNavigation}), the copy's URI returned with RESULT_OK; back without saving returns RESULT_CANCELED. A file
     * outside MediaStore's DCIM / Pictures gets its copy in Pictures/SCAMERA. Returns the editor's arguments.
     */
    private Bundle editorFor(Uri uri, @Nullable String type) {
        ImageFile file = createImageFileFromUri(uri);
        List<GalleryItem> single = new ArrayList<>();
        single.add(new GalleryItem(file));
        viewModel.getCurrentFolderImages().setValue(single);
        // P367: the zoom test hook's extras (scam_edit_zoom / _cx / _cy) pass through; nothing when absent
        return com.particlesdevs.photoncamera.gallery.editor.ExternalEdit.withZoomHook(
                com.particlesdevs.photoncamera.gallery.editor.ExternalEdit.args(uri, file.getDisplayName(), null, type, true), getIntent());
    }

    /**
     * Creates an ImageFile object from a URI
     * Extracts file information like name, size, etc. from the URI
     */
    private ImageFile createImageFileFromUri(Uri uri) {
        String displayName = "Unknown";
        long size = 0;
        long lastModified = System.currentTimeMillis();
        
        // Try to get file information from the ContentResolver
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                
                if (nameIndex != -1) {
                    displayName = cursor.getString(nameIndex);
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // P352: a file:// URI (or a provider without a name) is named by its path, so its extension (a RAW) is known
        if ("Unknown".equals(displayName) && uri.getLastPathSegment() != null) {
            String seg = uri.getLastPathSegment();
            displayName = seg.substring(seg.lastIndexOf('/') + 1);
        }
        // Create and return the ImageFile
        // Use a negative ID to indicate this is an external file
        return new ImageFile(-1, uri, displayName, lastModified, size, uri.toString());
    }

    /*public void onBackArrowClicked(View view) {
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
        NavController navController = navHostFragment.getNavController();
        navController.navigateUp();
    }*/

    @Override
    protected void onResume() {
        super.onResume();
        // Apply hideSystemUI in onResume to prevent flickering when returning to the gallery
        hideSystemUI();
    }

    /*
     * P299 (owner, 2026-10-20: «если в камере зайти в галерею и свернуть, а потом нажать на иконку камеры, она откроется на
     * галерее») closed the gallery whenever the user left it; P491 (owner, 2026-11-03: «если в нашей камере открыть галерею и
     * свернуть, затем развернуть приложение, то оно открывается ... не на галерее, открывается видоискатель»): the manifest's
     * finishOnTaskLaunch does both - the launcher icon (a task launch) closes the gallery and shows the camera, the recents
     * (resuming the task) keep the gallery where the user left it. Shortcut: unsaved editor work is dropped by the icon now
     * (P299 kept it), keep it in mind if the owner reports lost edits.
     */

    @Override
    protected void onDestroy() {
        super.onDestroy();
        activityGalleryBinding = null;
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float displayAspectRatio = (float) Math.max(dm.heightPixels, dm.widthPixels) / Math.min(dm.heightPixels, dm.widthPixels);
            if (displayAspectRatio <= (16f / 9) || dm.densityDpi > 440) {
                hideSystemUI();
            }
        }
    }

    private void hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        // Set the content to appear under the system bars so that the
                        // content doesn't resize when the system bars hide and show.
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        // Hide the nav bar and status bar
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GalleryFileOperations.REQUEST_PERM_DELETE) {
            NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
            Fragment fragment = null;
            if (navHostFragment != null) {
                fragment = navHostFragment.getChildFragmentManager().getFragments().get(0);
            }
            if (fragment instanceof ImageViewerFragment) {
                ((ImageViewerFragment) fragment).handleImagesDeletedCallback(resultCode == Activity.RESULT_OK);
            } else if (fragment instanceof ImageLibraryFragment) {
                ((ImageLibraryFragment) fragment).handleImagesDeletedCallback(resultCode == Activity.RESULT_OK);
            }
        }
        // P502: a restore / delete for good of «Недавно удаленные» that went through the system's dialog
        if (requestCode == com.particlesdevs.photoncamera.gallery.files.GalleryTrash.REQUEST_TRASH_ACTION) {
            NavHostFragment host = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.gallery_navigation_host);
            Fragment fragment = host != null && !host.getChildFragmentManager().getFragments().isEmpty()
                    ? host.getChildFragmentManager().getFragments().get(0) : null;
            if (fragment instanceof ImageLibraryFragment) ((ImageLibraryFragment) fragment).handleTrashResult(resultCode == Activity.RESULT_OK);
        }
    }
}
