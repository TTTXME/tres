package com.scamera.app.control;

import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.scamera.app.R;
import com.scamera.app.circularbarlib.api.ManualModeConsole;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.control.subject.SubjectFocus;
import com.scamera.app.databinding.CameraScreenBinding;
import com.scamera.app.ui.camera.CameraFragment;
import com.scamera.app.ui.camera.LutTray;
import com.scamera.app.ui.camera.model.CameraFragmentModel;
import com.scamera.app.ui.camera.viewmodel.CameraFragmentViewModel;
import com.scamera.app.util.Log;

/**
 * The viewfinder's touches: a tap focuses (or closes the FULL sheet / the LUT tray first), a long press tracks the subject or
 * locks focus and exposure, a pinch zooms, and the flings go to {@link Router} (the shade, the top row's sheet, the LUT steps).
 */
public class Swipe {
    private static final String TAG = "Swipe";
    private final CameraFragment screen;
    private ManualModeConsole manual;
    private CameraFragmentViewModel model;
    private Router router;

    public Swipe(CameraFragment screen) {
        this.screen = screen;
    }

    /** Once the screen's views and the manual panel exist: the touch handling onto the texture holder. */
    public void init() {
        manual = screen.getManualModeConsole();
        model = screen.getCameraFragmentViewModel();
        manual.setPanelVisibility(true);
        final GestureDetector gestures = new GestureDetector(screen.getContext(), new Taps());
        final ScaleGestureDetector pinch = new ScaleGestureDetector(screen.getContext(), new Pinch());
        router = new Router(screen.getResources().getDisplayMetrics().density, new RouterHost());
        final View holder = screen.findViewById(R.id.camera_root);
        Log.d(TAG, "viewfinder touches on " + holder);
        if (holder == null) return;
        holder.setOnTouchListener((v, touch) -> {
            router.observe(touch);
            // no pinch zoom under the FULL sheet's scrim
            if (sheetFull()) return gestures.onTouchEvent(touch);
            final int fingers = touch.getPointerCount();
            if (fingers > 1) Log.d(TAG, "pinch touch pointers=" + fingers + " action=" + touch.getActionMasked());
            pinch.onTouchEvent(touch);
            return pinch.isInProgress() || fingers > 1 || gestures.onTouchEvent(touch);
        });
    }

    private boolean sheetFull() {
        return model.getSheetLevel() == CameraFragmentModel.SHEET_FULL;
    }

    private boolean autoFocus() {
        return manual.getManualParamModel().getCurrentFocusValue() == ManualParamModel.FOCUS_AUTO;
    }

    /**
     * The touch as a point in the camera container (where the focus ring lives), or null outside the viewfinder: its left /
     * right / bottom edges with the container's own vertical shift (a screen of another ratio moves the container down).
     */
    private float[] viewfinderPoint(MotionEvent touch) {
        final View container = screen.findViewById(R.id.camera_stage);
        final View finder = screen.findViewById(R.id.vf_layer);
        if (container == null || finder == null) return null;
        final float x = touch.getX(), y = touch.getY(), top = container.getY();
        final boolean inside = x >= finder.getLeft() && x < finder.getRight() && y >= top && y < finder.getBottom() + top;
        return inside ? new float[]{x - container.getLeft(), y - container.getTop()} : null;
    }

    private void tapFocus(MotionEvent touch) {
        final float[] at = viewfinderPoint(touch);
        if (at == null || !autoFocus()) return;
        // P42: in «tap» mode the tap starts / stops tracking; otherwise a running track gives way to the tap focus
        final SubjectFocus subject = screen.getSubjectFocus();
        if (subject != null && subject.onTap(touch.getRawX(), touch.getRawY())) return;
        screen.getTouchFocus().processTouchToFocus(at[0], at[1]);
    }

    /**
     * P42: a long press tracks the subject when «Следить за объектом» is on long press; otherwise (SIMPLE_UI §1) it locks the
     * focus and the exposure there («Закреплено») until a tap elsewhere. Auto focus only, as the tap.
     */
    private void longPress(MotionEvent touch) {
        if (!autoFocus()) return;
        final SubjectFocus subject = screen.getSubjectFocus();
        final boolean done = (subject != null && subject.onLongPress(touch.getRawX(), touch.getRawY())) || lockAt(touch);
        if (!done) return;
        final View holder = screen.findViewById(R.id.camera_root);
        if (holder != null) holder.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    /** SIMPLE_UI §1: the long press's lock, at the same point mapping as a tap. */
    private boolean lockAt(MotionEvent touch) {
        final TouchFocus focus = screen.getTouchFocus();
        final float[] at = focus == null ? null : viewfinderPoint(touch);
        if (at == null) return false;
        final SubjectFocus subject = screen.getSubjectFocus();
        if (subject != null) subject.stopTracking(false);
        focus.lockAt(at[0], at[1]);
        return true;
    }

    /** SIMPLE_UI §1: the previous (-1) or next (+1) LUT while the quick sheet is closed, wiped in from that side (P319). */
    private void stepLut(int direction) {
        if (model.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN) return;
        final LutTray tray = screen.lutTray;
        if (tray == null) return;
        final Runnable change = () -> screen.showCardToast(tray.step(direction));
        final CameraScreenBinding views = screen.cameraViews;
        if (views == null || views.vfLayer.lutWipe == null) change.run();
        else views.vfLayer.lutWipe.wipe(views.vfLayer.texture, direction, change);
    }

    private final class Taps extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent touch) {
            return true;
        }

        /**
         * In FULL the scrim takes the touches; one that gets here only lowers the sheet to PEEK (P25, owner's answer 3). A tap
         * on the empty viewfinder closes an open LUT tray (owner, 2026-10-20). Neither focuses.
         */
        @Override
        public boolean onSingleTapUp(MotionEvent touch) {
            if (sheetFull()) {
                model.setSheetLevel(CameraFragmentModel.SHEET_PEEK);
                return true;
            }
            final LutTray tray = screen.lutTray;
            if (tray != null && tray.isOpen()) {
                tray.setOpen(false);
                return true;
            }
            tapFocus(touch);
            return false;
        }

        @Override
        public void onLongPress(MotionEvent touch) {
            if (!sheetFull()) longPress(touch);
        }

        @Override
        public boolean onFling(MotionEvent from, MotionEvent to, float vx, float vy) {
            return router.fling(from, to, vx, vy); // P574: the routing is the Router's
        }
    }

    private final class Pinch extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector pinch) {
            ZoomController.syncToActive(); // P37: a pinch starts from the active module's zoom
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector pinch) {
            screen.zoomTo(ZoomController.zoom() * pinch.getScaleFactor());
            return true;
        }
    }

    /** What the router reads and does on this screen; the sheet goes up / down a level (HIDDEN, PEEK, FULL). */
    private final class RouterHost implements Router.Host {
        @Override public int sheetLevel() { return model.getSheetLevel(); }
        @Override public boolean shadeSettling() { return screen.isShadeSettling(); }
        @Override public boolean lutOpen() { return screen.lutTray != null && screen.lutTray.isOpen(); }
        @Override public boolean topRowOpen() { return screen.isTopRowSheetShowing(); }
        @Override public float topZoneBottom() { return screen.topRowZoneBottom(); }
        @Override public void openTopRow() { screen.openTopRowSheet(); }
        @Override public void closeTopRow() { screen.closeTopRowSheet(); }
        // SIMPLE_UI §1: raising the sheet no longer resets the focus or the tracking (the quick sheet must not lose the subject)
        @Override public void sheetUp() { model.sheetLevelUp(); }
        @Override public void sheetDown() { model.sheetLevelDown(); }
        @Override public void stepLut(int direction) { Swipe.this.stepLut(direction < 0 ? -1 : 1); }
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
