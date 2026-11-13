package com.scamera.app.gallery.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import static com.scamera.app.gallery.helper.Constants.DOUBLE_TAP_ZOOM_DURATION_MS;

/**
 * The viewer's zoomable photo: EXIF orientation, quick scale, a double tap zooms (Constants.DOUBLE_TAP_ZOOM_DURATION_MS),
 * full-colour tiles; reports touches to its {@link TouchCallBack} (the compare panes follow each other) and a swipe down at
 * the fit scale (the viewer closes).
 */
public class CustomSSIV extends SubsamplingScaleImageView {
    private static final int MIN_DPI = 40;
    private TouchCallBack touchCallBack;

    public CustomSSIV(Context context) {
        super(context);
        setUp();
    }

    public CustomSSIV(Context context, AttributeSet attrs) {
        super(context, attrs);
        setUp();
    }

    private void setUp() {
        setMinimumDpi(MIN_DPI);
        setOrientation(ORIENTATION_USE_EXIF);
        setQuickScaleEnabled(true);
        setEagerLoadingEnabled(false);
        setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS);
        setPreferredBitmapConfig(Bitmap.Config.ARGB_8888);
    }

    /** P59b: a swipe down at the fit scale (the viewer closes); set by the viewer. */
    private Runnable swipeDown;
    private float downX, downY;
    private boolean multi;

    public void setSwipeDownListener(Runnable listener) {
        swipeDown = listener;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (touchCallBack != null) touchCallBack.onTouchedView(this);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                multi = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                break;
            case MotionEvent.ACTION_UP:
                if (swipeDown != null && isSwipeDown(event.getX() - downX, event.getY() - downY)) post(swipeDown);
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    /** One finger, at the fit scale, more than 110 dp down and more down than sideways. */
    private boolean isSwipeDown(float dx, float dy) {
        float far = 110 * getResources().getDisplayMetrics().density;
        return !multi && isReady() && getScale() <= getMinScale() * 1.05f && dy > far && dy > Math.abs(dx);
    }

    public void setTouchCallBack(TouchCallBack callBack) {
        touchCallBack = callBack;
    }

    public interface TouchCallBack {
        void onTouched(int id);

        /** Before the view handles the event. The view itself: the two compare panes show pages of the same id when they show the same photo. */
        default void onTouchedView(CustomSSIV view) {
            onTouched(view.getId());
        }
    }
}

