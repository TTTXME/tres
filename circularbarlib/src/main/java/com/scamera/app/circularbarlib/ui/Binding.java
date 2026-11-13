package com.scamera.app.circularbarlib.ui;

import android.view.View;
import android.view.ViewGroup;

import com.scamera.app.circularbarlib.R;

/** The manual panel's show / hide animation. */
public final class Binding {
    private static final long SLIDE_MS = 100;

    private Binding() {}

    /** Slides the panel in (fades up) or out (drops by 20dp, then gone); the shade owns it while it is above HIDDEN. */
    public static void togglePanelVisibility(ViewGroup panel, Boolean visible) {
        boolean show = Boolean.TRUE.equals(visible);
        if (show && panel instanceof ExpandingManualPanel && !((ExpandingManualPanel) panel).isShadeShown()) return;
        panel.post(() -> {
            if (show) {
                panel.animate().translationY(0).alpha(1f).setDuration(SLIDE_MS).start();
                panel.setVisibility(View.VISIBLE);
            } else {
                panel.animate().translationY(panel.getResources().getDimension(R.dimen.standard_20)).alpha(0f)
                        .setDuration(SLIDE_MS).withEndAction(() -> panel.setVisibility(View.GONE)).start();
            }
        });
    }
}
