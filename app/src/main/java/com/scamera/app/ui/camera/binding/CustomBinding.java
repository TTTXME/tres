package com.scamera.app.ui.camera.binding;

import android.view.View;
import android.view.ViewGroup;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.databinding.BindingAdapter;

import com.scamera.app.ui.camera.model.AuxButtonsModel;
import com.scamera.app.ui.camera.model.CameraFragmentModel;
import com.scamera.app.ui.camera.views.AuxButtonsLayout;

/** The camera layouts' own binding attributes (the attribute names are what the layouts write). */
public class CustomBinding {
    /** {@code bindRotate="@{uimodel}"}: the view turns to the controls' rotation in the model's time. */
    @BindingAdapter("bindRotate")
    public static void turnWithScreen(View view, CameraFragmentModel screen) {
        if (screen == null) return;
        view.animate().rotation(screen.getOrientation()).setDuration(screen.getDuration()).start();
    }

    /**
     * {@code bindViewGroupChildrenRotate="@{uimodel}"}: the group's children turn, not the group; the lens strip turns its
     * labels itself (their tiles keep their places).
     */
    @BindingAdapter("bindViewGroupChildrenRotate")
    public static void turnChildrenWithScreen(ViewGroup group, CameraFragmentModel screen) {
        if (screen == null) return;
        final int degrees = screen.getOrientation();
        final long ms = screen.getDuration();
        if (group instanceof AuxButtonsLayout) {
            ((AuxButtonsLayout) group).rotateLabels(degrees, ms);
            return;
        }
        for (int at = 0, n = group.getChildCount(); at < n; at++) group.getChildAt(at).animate().rotation(degrees).setDuration(ms).start();
    }

    @BindingAdapter("android:selected")
    public static void select(View view, Boolean on) {
        if (view != null && on != null) view.setSelected(on);
    }

    @BindingAdapter("setAuxButtonModel")
    public static void lensModel(AuxButtonsLayout strip, AuxButtonsModel lenses) {
        if (lenses != null) strip.setAuxButtonsModel(lenses);
    }

    @BindingAdapter("setActiveId")
    public static void activeLens(AuxButtonsLayout strip, String cameraId) {
        if (cameraId != null) strip.setActiveId(cameraId);
    }

    /**
     * The manual panel follows the shade: shown while the shade is HIDDEN, faded and slid out at the other levels. The panel
     * keeps its own state (open or closed, the selected parameter), so it comes back as it was.
     */
    @BindingAdapter("manualPanelShown")
    public static void setManualPanelShown(View view, boolean shown) {
        if (view instanceof com.scamera.app.circularbarlib.ui.ExpandingManualPanel) {
            ((com.scamera.app.circularbarlib.ui.ExpandingManualPanel) view).setShadeShown(shown, view.isLaidOut());
        } else if (view != null) {
            view.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** A screen up to 16:9: the camera block hangs from the top of the screen (a taller one keeps the layout's anchor). */
    @BindingAdapter("adjustCameraContainer")
    public static void anchorCameraBlock(ConstraintLayout block, float screenAspect) {
        if (screenAspect > 16f / 9) return;
        final ConstraintLayout.LayoutParams anchor = (ConstraintLayout.LayoutParams) block.getLayoutParams();
        anchor.topToBottom = ConstraintLayout.LayoutParams.UNSET;
        anchor.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
    }

    /**
     * The top bar's margin in dp (pixels and dp must not be mixed). P583 (the concept: the row's items centred 40dp down, the
     * preview 70dp down): on a tall display the 64dp bar starts 6dp down (its row 4dp lower inside it), so the preview starts
     * at 70dp.
     */
    @BindingAdapter("adjustTopBar")
    public static void placeTopBar(View bar, float screenAspect) {
        final ConstraintLayout.LayoutParams margins = (ConstraintLayout.LayoutParams) bar.getLayoutParams();
        margins.topMargin = Math.round((screenAspect > 1.9f ? 6 : 4) * bar.getResources().getDisplayMetrics().density);
        bar.setLayoutParams(margins);
    }

    /** {@code setAspectRatio}: a ConstraintLayout child's dimension ratio ("3:4", or a width : height number). */
    @BindingAdapter("setAspectRatio")
    public static void frameRatio(View view, String ratio) {
        if (view == null || ratio == null || ratio.isEmpty()) return;
        if (!(view.getLayoutParams() instanceof ConstraintLayout.LayoutParams)) return;
        final ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) view.getLayoutParams();
        lp.dimensionRatio = ratio;
        view.setLayoutParams(lp);
    }
}
