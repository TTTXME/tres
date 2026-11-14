package com.scamera.app.ui.camera.model;

import android.graphics.Bitmap;

import androidx.databinding.BaseObservable;
import androidx.databinding.Bindable;

import com.scamera.app.BR;

/**
 * The camera screen's bound state: the controls' rotation (and how long they take to turn), the gallery thumbnail, the
 * settings sheet's level and the frame's aspect.
 */
public class CameraFragmentModel extends BaseObservable {
    /** Settings sheet below the edge; only its handle is on screen. */
    public static final int SHEET_HIDDEN = 0;
    /** Settings sheet shows the handle, the quick buttons and the group cells. */
    public static final int SHEET_PEEK = 1;
    /** Settings sheet shows the full settings list. */
    public static final int SHEET_FULL = 2;

    /** P705: rotation is read on the camera thread too (CameraFragment.showLiveMetrics). */
    private volatile int rotation;
    private int turnMs;
    private Bitmap thumb;
    private int sheet = SHEET_HIDDEN;
    private float screenAspect = 9f / 16;
    private String frameAspect = "16:9";

    @Bindable
    public float getScreenAspectRatio() {
        return screenAspect;
    }

    public void setScreenAspectRatio(float aspect) {
        screenAspect = aspect;
        notifyPropertyChanged(BR.screenAspectRatio);
    }

    @Bindable
    public Bitmap getBitmap() {
        return thumb;
    }

    /** The gallery card's picture; every binding is told (the card's state follows it). */
    public void setBitmap(Bitmap picture) {
        thumb = picture;
        notifyChange();
    }

    @Bindable
    public int getOrientation() {
        return rotation;
    }

    /** The controls' rotation in degrees; every binding is told (they all turn). */
    public void setOrientation(int degrees) {
        rotation = degrees;
        notifyChange();
    }

    public int getDuration() {
        return turnMs;
    }

    public void setDuration(int ms) {
        turnMs = ms;
    }

    /** Settings sheet level: {@link #SHEET_HIDDEN}, {@link #SHEET_PEEK} or {@link #SHEET_FULL}. */
    @Bindable
    public int getSheetLevel() {
        return sheet;
    }

    /**
     * The settings sheet's level, clamped to HIDDEN..FULL. The same level again changes nothing; a new one notifies this
     * property's bindings only (the sheet is not asked to move again for its own level).
     */
    public void setSheetLevel(int level) {
        final int clamped = Math.min(SHEET_FULL, Math.max(SHEET_HIDDEN, level));
        if (clamped == sheet) return;
        sheet = clamped;
        notifyPropertyChanged(BR.sheetLevel);
    }

    /** The viewfinder's frame aspect ("3:4", or a width : height ratio as a number) for the layout's dimension ratio. */
    @Bindable
    public String getDummyAspectRatio() {
        return frameAspect;
    }

    public void setDummyAspectRatio(String ratio) {
        frameAspect = ratio;
        notifyPropertyChanged(BR.dummyAspectRatio);
    }
}
