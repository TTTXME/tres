package com.particlesdevs.photoncamera.control;

import android.graphics.RectF;
import com.particlesdevs.photoncamera.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.ui.camera.CameraFragment;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.camera.viewmodel.CameraFragmentViewModel;

public class Swipe {
    private static final String TAG = "Swipe";
    private final CameraFragment cameraFragment;
    private final CaptureController captureController;
    private GestureDetector gestureDetector;
    private Router router;
    private ManualModeConsole manualModeConsole;
    private CameraFragmentViewModel cameraFragmentViewModel;

    public Swipe(CameraFragment cameraFragment) {
        this.cameraFragment = cameraFragment;
        this.captureController = cameraFragment.getCaptureController();
    }

    public void init() {
        Log.d(TAG, "SwipeDetection - ON");
        manualModeConsole = cameraFragment.getManualModeConsole();
        cameraFragmentViewModel = cameraFragment.getCameraFragmentViewModel();
        manualModeConsole.setPanelVisibility(true);
        gestureDetector = new GestureDetector(cameraFragment.getContext(), new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY_THRESHOLD = 100;

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                // In FULL the scrim takes the touches; should one get here, it only lowers the sheet
                // to PEEK, without focus (P25, owner's answer 3).
                if (cameraFragmentViewModel.getSheetLevel() == CameraFragmentModel.SHEET_FULL) {
                    cameraFragmentViewModel.setSheetLevel(CameraFragmentModel.SHEET_PEEK);
                    return true;
                }
                // owner, 2026-10-20: a tap on the empty viewfinder closes the LUT tray, without focus
                com.particlesdevs.photoncamera.ui.camera.LutTray tray = cameraFragment.lutTray;
                if (tray != null && tray.isOpen()) {
                    tray.setOpen(false);
                    return true;
                }
                startTouchToFocus(e);
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // P42: a long press locks the tracking autofocus onto the subject (setting «Tracking autofocus»).
                if (cameraFragmentViewModel.getSheetLevel() == CameraFragmentModel.SHEET_FULL) return;
                startTracking(e);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                // P574: the routing (the shade, the top row's sheet, the LUT steps) is the Router's
                return router.fling(e1, e2, velocityX, velocityY);
            }
        });
        android.view.ScaleGestureDetector scaleDetector = new android.view.ScaleGestureDetector(cameraFragment.getContext(),
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(android.view.ScaleGestureDetector detector) {
                        ZoomController.syncToActive(); // P37: a pinch starts from the active module's zoom
                        return true;
                    }

                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        cameraFragment.zoomTo(ZoomController.zoom() * detector.getScaleFactor());
                        return true;
                    }
                });
        router = new Router(cameraFragment.getResources().getDisplayMetrics().density, new Router.Host() {
            @Override public int sheetLevel() { return cameraFragmentViewModel.getSheetLevel(); }
            @Override public boolean shadeSettling() { return cameraFragment.isShadeSettling(); }
            @Override public boolean lutOpen() { return cameraFragment.lutTray != null && cameraFragment.lutTray.isOpen(); }
            @Override public boolean topRowOpen() { return cameraFragment.isTopRowSheetShowing(); }
            @Override public float topZoneBottom() { return cameraFragment.topRowZoneBottom(); }
            @Override public void openTopRow() { cameraFragment.openTopRowSheet(); }
            @Override public void closeTopRow() { cameraFragment.closeTopRowSheet(); }
            @Override public void sheetUp() { SwipeUp(); }
            @Override public void sheetDown() { SwipeDown(); }
            @Override public void stepLut(int direction) { if (direction < 0) SwipeRight(); else SwipeLeft(); }
        });
        View.OnTouchListener touchListener = (view, motionEvent) -> {
            router.observe(motionEvent);
            // No pinch zoom under the FULL sheet's scrim.
            if (cameraFragmentViewModel.getSheetLevel() == CameraFragmentModel.SHEET_FULL) return gestureDetector.onTouchEvent(motionEvent);
            if (motionEvent.getPointerCount() > 1) Log.d(TAG, "pinch touch pointers=" + motionEvent.getPointerCount() + " action=" + motionEvent.getActionMasked());
            scaleDetector.onTouchEvent(motionEvent);
            if (scaleDetector.isInProgress() || motionEvent.getPointerCount() > 1) return true;
            return gestureDetector.onTouchEvent(motionEvent);
        };
        View holder = cameraFragment.findViewById(R.id.textureHolder);
        Log.d(TAG, "input:" + holder);
        if (holder != null) holder.setOnTouchListener(touchListener);
    }

    private void startTouchToFocus(MotionEvent event) {
        //takes into consideration the top and bottom translation of camera_container(if it has been moved due to different display ratios)
        // for calculation of size of viewfinder RectF.(for touch focus detection)
        ConstraintLayout camera_container = cameraFragment.findViewById(R.id.camera_container);
        FrameLayout layout_viewfinder = cameraFragment.findViewById(R.id.layout_viewfinder);
        RectF viewfinderRect = new RectF(
                layout_viewfinder.getLeft(),//left edge of viewfinder
                camera_container.getY(), //y position of camera_container
                layout_viewfinder.getRight(), //right edge of viewfinder
                layout_viewfinder.getBottom() + camera_container.getY() //bottom edge of viewfinder + y position of camera_container
        );
        // Interface.getCameraFragment().showToast(previewRect.toString()+"\nCurX"+event.getX()+"CurY"+event.getY());
        if (viewfinderRect.contains(event.getX(), event.getY())) {
            float translateX = event.getX() - camera_container.getLeft();
            float translateY = event.getY() - camera_container.getTop();
            if (manualModeConsole.getManualParamModel().getCurrentFocusValue() == ManualParamModel.FOCUS_AUTO) {
                // P42: in «tap» mode the tap starts / stops tracking; otherwise a running track gives way to the tap focus.
                com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
                if (subject != null && subject.onTap(event.getRawX(), event.getRawY())) return;
                cameraFragment.getTouchFocus().processTouchToFocus(translateX, translateY);
            }
        }
    }

    /**
     * P42: long press on the viewfinder -> tracking autofocus when «Следить за объектом» is on long press; otherwise
     * (SIMPLE_UI §1) it locks the focus and the exposure there («Закреплено»), a tap elsewhere releases them. Auto focus only,
     * as the tap focus.
     */
    private void startTracking(MotionEvent event) {
        if (manualModeConsole.getManualParamModel().getCurrentFocusValue() != ManualParamModel.FOCUS_AUTO) return;
        com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
        boolean done = subject != null && subject.onLongPress(event.getRawX(), event.getRawY());
        if (!done) done = lockFocusAndExposure(event);
        if (done) {
            android.view.View holder = cameraFragment.findViewById(R.id.textureHolder);
            if (holder != null) holder.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /** SIMPLE_UI §1: the long press's lock, at the same point mapping as a tap. */
    private boolean lockFocusAndExposure(MotionEvent event) {
        TouchFocus touchFocus = cameraFragment.getTouchFocus();
        if (touchFocus == null) return false;
        ConstraintLayout camera_container = cameraFragment.findViewById(R.id.camera_container);
        FrameLayout layout_viewfinder = cameraFragment.findViewById(R.id.layout_viewfinder);
        if (camera_container == null || layout_viewfinder == null) return false;
        RectF viewfinderRect = new RectF(layout_viewfinder.getLeft(), camera_container.getY(), layout_viewfinder.getRight(),
                layout_viewfinder.getBottom() + camera_container.getY());
        if (!viewfinderRect.contains(event.getX(), event.getY())) return false;
        com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = cameraFragment.getSubjectFocus();
        if (subject != null) subject.stopTracking(false);
        touchFocus.lockAt(event.getX() - camera_container.getLeft(), event.getY() - camera_container.getTop());
        return true;
    }

    /**
     * Raises the settings sheet one level: HIDDEN -> PEEK -> FULL. SIMPLE_UI §1: it no longer resets the focus or the
     * tracking (opening the quick sheet must not lose the subject).
     */
    public void SwipeUp() {
        cameraFragmentViewModel.sheetLevelUp();
    }

    /** Lowers the settings sheet one level: FULL -> PEEK -> HIDDEN. */
    public void SwipeDown() {
        cameraFragmentViewModel.sheetLevelDown();
    }


    /** SIMPLE_UI §1: the previous LUT, its name in the message band (only while the quick sheet is closed). */
    public void SwipeRight() {
        stepLut(-1);
    }

    /** SIMPLE_UI §1: the next LUT. */
    public void SwipeLeft() {
        stepLut(1);
    }

    private void stepLut(int direction) {
        if (cameraFragmentViewModel.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN) return;
        com.particlesdevs.photoncamera.ui.camera.LutTray tray = cameraFragment.lutTray;
        if (tray == null) return;
        // P319: the new look wipes in from the side the finger moved to (LutWipeView), the name in the message band
        final Runnable change = () -> cameraFragment.showCardToast(tray.step(direction));
        final com.particlesdevs.photoncamera.databinding.CameraFragmentBinding b = cameraFragment.cameraFragmentBinding;
        if (b == null || b.layoutViewfinder.lutWipe == null) { change.run(); return; }
        b.layoutViewfinder.lutWipe.wipe(b.layoutViewfinder.texture, direction, change);
    }


    /**
     * P574 (owner, 2026-11-09: «верхняя и нижняя шторка в открытом состоянии закрываются противоположными жестами и при этом не
     * возникает никаких ошибок»): what a fling on the camera screen does.
     * <ul>
     *   <li>A fling after a second finger came down (a pinch) does nothing.</li>
     *   <li>The top row's sheet up: a fling up closes it, nothing else runs (its own window takes the touches anyway).</li>
     *   <li>Horizontal (|dx| > 60dp and > 1.5 |dy|): the next / previous LUT (SIMPLE_UI §1).</li>
     *   <li>Up (|dy| > 100 px): the quick-settings shade one level up, as before.</li>
     *   <li>Down: with the shade HIDDEN and settled, the LUT tray closed and the swipe starting in the top zone (the top bar and
     *   the viewfinder's upper half), the top row's sheet opens; else the shade one level down, as before (at HIDDEN a no-op).</li>
     * </ul>
     * Taps (focus), long presses (tracking, the lock) and pinches are no flings; the exposure sun and the bottom controls take
     * their own touches.
     */
    public static final class Router {
        /** What the router reads and does on the camera screen. */
        public interface Host {
            int sheetLevel();

            /** The shade is moving (dragged or settling). */
            boolean shadeSettling();

            boolean lutOpen();

            boolean topRowOpen();

            /** The touch view's y below which a swipe down no longer starts in the top zone. */
            float topZoneBottom();

            void openTopRow();

            void closeTopRow();

            void sheetUp();

            void sheetDown();

            /** -1 the previous LUT (a swipe right), 1 the next. */
            void stepLut(int direction);
        }

        static final int SWIPE_THRESHOLD = 100, SWIPE_VELOCITY_THRESHOLD = 100;
        private final float density;
        private final Host host;
        private boolean multiTouch;

        public Router(float density, Host host) {
            this.density = density;
            this.host = host;
        }

        /** Every touch of the gesture: a second finger makes it a pinch (no fling acts then). */
        public void observe(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) multiTouch = false;
            if (e.getPointerCount() > 1) multiTouch = true;
        }

        public boolean fling(@androidx.annotation.Nullable MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || multiTouch) return false;
            final float dy = e2.getY() - e1.getY(), dx = e2.getX() - e1.getX();
            if (host.topRowOpen()) {
                if (dy < 0 && Math.abs(dy) > Math.abs(dx)) host.closeTopRow();
                return true;
            }
            if (Math.abs(dx) > 1.5f * Math.abs(dy)) {
                if (Math.abs(dx) > 60 * density && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    host.stepLut(dx > 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
            if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                if (dy < 0) {
                    host.sheetUp();
                } else if (host.sheetLevel() == CameraFragmentModel.SHEET_HIDDEN && !host.shadeSettling() && !host.lutOpen()
                        && e1.getY() <= host.topZoneBottom()) {
                    host.openTopRow();
                } else {
                    host.sheetDown();
                }
                return true;
            }
            return false;
        }
    }
}
