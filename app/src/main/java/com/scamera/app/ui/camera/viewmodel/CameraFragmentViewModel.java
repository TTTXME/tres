package com.scamera.app.ui.camera.viewmodel;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import com.scamera.app.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.scamera.app.gallery.files.GalleryFileOperations;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.ui.camera.CustomOrientationEventListener;
import com.scamera.app.ui.camera.model.CameraFragmentModel;

/**
 * The camera screen's state for its bindings (no view reference here): the screen model, the phone's orientation turned into
 * the controls' rotation, the gallery thumbnail.
 */
public class CameraFragmentViewModel extends AndroidViewModel {
    private static final String TAG = "CameraFragmentViewModel";
    /** The controls turn to a new orientation in this many ms. */
    private static final int TURN_MS = 350;
    private final CameraFragmentModel screen = new CameraFragmentModel();
    private final CustomOrientationEventListener orientation;

    public CameraFragmentViewModel(@NonNull Application app) {
        super(app);
        orientation = new CustomOrientationEventListener(app) {
            @Override
            public void onSimpleOrientationChanged(int quarter) {
                final int degrees = controlsRotation(quarter);
                Log.d(TAG, "controls turn to " + degrees);
                screen.setDuration(TURN_MS);
                screen.setOrientation(degrees);
            }
        };
    }

    /** The controls' rotation for a quarter: left side up -90, right side up 90, upside down 180, upright 0. */
    static int controlsRotation(int quarter) {
        if (quarter == 2) return -90;
        if (quarter == 4) return 90;
        return quarter == 3 ? 180 : 0;
    }

    public CameraFragmentModel getCameraFragmentModel() {
        return screen;
    }

    public void onResume() {
        orientation.enable();
        screen.setSheetLevel(CameraFragmentModel.SHEET_HIDDEN);
    }

    public void onPause() {
        orientation.disable();
    }

    /** Provisional gallery thumbnail shown the moment a ZSL shot is taken (SCAM: the viewfinder frame), replaced when the JPEG is saved. */
    public void setProvisionalThumb(@Nullable Bitmap bitmap) {
        if (bitmap == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> screen.setBitmap(bitmap));
    }
    /** The gallery card's picture: {@code uri}, else the newest photo in the gallery (P637: the one the card opens). */
    public void updateGalleryThumb(@Nullable Uri uri) {
        Uri shown = uri;
        if (shown == null) {
            final ImageFile newest = GalleryFileOperations.fetchLatestImage(getApplication().getContentResolver());
            com.scamera.app.gallery.ui.CameraHandoff.latest(newest); // P637: the photo the thumbnail opens
            if (newest != null) shown = newest.contentUri();
        }
        if (shown == null) return;
        // a 200 px bitmap for the round gallery button
        Glide.with(getApplication()).asBitmap().load(shown).override(200).into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap thumb, @Nullable Transition<? super Bitmap> transition) {
                screen.setBitmap(thumb);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable gone) {
                // the model keeps its last thumbnail
            }
        });
    }

    public void setScreenAspectRatio(float aspect) {
        screen.setScreenAspectRatio(aspect);
    }

    /** Settings sheet level, one of {@link CameraFragmentModel#SHEET_HIDDEN SHEET_*}. */
    public int getSheetLevel() {
        return screen.getSheetLevel();
    }

    public void setSheetLevel(int level) {
        screen.setSheetLevel(level);
    }

    /** One level up: HIDDEN -> PEEK -> FULL; stays at FULL. */
    public void sheetLevelUp() {
        // SIMPLE_UI §2: two states, HIDDEN and OPEN (PEEK): the open sheet never grows, its content scrolls
        setSheetLevel(Math.min(CameraFragmentModel.SHEET_PEEK, getSheetLevel() + 1));
    }

    /** One level down: FULL -> PEEK -> HIDDEN; stays at HIDDEN. */
    public void sheetLevelDown() {
        setSheetLevel(CameraFragmentModel.SHEET_HIDDEN);
    }

}
