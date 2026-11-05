package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.particlesdevs.photoncamera.circularbarlib.R;

/**
 * The manual panel in the shade's card style (MANUAL_TASK.md §1-§2): the ruler card above a strip of the 52dp toggle card
 * and the card of five chips. The toggle collapses or expands the chips (collapsed: invisible, scaled to 60 % from the left);
 * collapsing also closes the ruler. While any applied parameter is manual the toggle shows a small accent dot.
 * <p>
 * The panel is on screen only while the shade is HIDDEN ({@link #setShadeShown}): it fades and slides out at the other
 * levels and comes back in the same state.
 */
public class ExpandingManualPanel extends LinearLayout {
    private View tabs, scale, toggle, dot;
    private android.widget.FrameLayout endSlot;
    private ImageView toggleIcon;
    private ValueAnimator animator;
    private float progress;
    private boolean expanded;
    private Runnable onCollapse;
    private int accent;

    public ExpandingManualPanel(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        tabs = findViewById(R.id.buttons_container);
        scale = findViewById(R.id.knobViewContainer);
        toggle = findViewById(R.id.manual_toggle);
        endSlot = findViewById(R.id.manual_end_slot);
        dot = findViewById(R.id.manual_toggle_dot);
        toggleIcon = findViewById(R.id.manual_toggle_icon);
        Context c = getContext();
        // P509 (owner, 2026-11-05: «Подменю про режима тоже перерисуй под стиль нашей новой шторки»): the shade's glass plates
        tabs.setBackground(UiTokens.glass(c, 18));
        scale.setBackground(UiTokens.glass(c, 18));
        toggle.setBackground(UiTokens.shape(c, UiTokens.CARD, UiTokens.LINE, 20));
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        dot.setBackground(round);
        // The ruler header's text in sp, grown with the font size only up to UiTokens.FONT_SCALE_CAP (P43).
        capText(R.id.manual_ruler_value, 13f);
        capText(R.id.manual_all_auto, 12.5f);
        setAccent(UiTokens.cameraAccent(c));
        tabs.setVisibility(INVISIBLE);
        tabs.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        toggle.setOnClickListener(v -> setExpanded(!expanded, true));
        // SIMPLE_UI §3: the bottom bar's «Про» opens the panel; the old toggle card is gone, the chips take the whole width
        toggle.setVisibility(GONE);
        ViewGroup.MarginLayoutParams chipsLp = (ViewGroup.MarginLayoutParams) tabs.getLayoutParams();
        chipsLp.setMarginStart(0);
        tabs.setLayoutParams(chipsLp);
        progress = 0;
        updateFrame();
    }

    private void capText(int id, float sp) {
        View view = findViewById(id);
        if (view instanceof android.widget.TextView) UiTokens.setTextSp((android.widget.TextView) view, sp);
    }

    /** The camera accent of the toggle icon and dot. */
    public void setAccent(int accent) {
        this.accent = accent;
        if (toggleIcon != null) toggleIcon.setImageTintList(ColorStateList.valueOf(accent));
        if (dot != null && dot.getBackground() instanceof GradientDrawable) ((GradientDrawable) dot.getBackground()).setColor(accent);
    }

    private java.util.function.Consumer<Boolean> onExpanded;

    /** SIMPLE_UI: told every open / close (the bottom bar's «Про» disc shows the state). */
    public void setOnExpandedListener(java.util.function.Consumer<Boolean> listener) {
        onExpanded = listener;
    }

    /** Called when the strip collapses: the console closes the ruler and forgets the selected parameter. */
    public void setOnCollapseListener(Runnable listener) {
        onCollapse = listener;
    }

    /** The accent dot on the toggle: any applied parameter is manual. */
    public void setManualDot(boolean shown) {
        if (dot != null) dot.setVisibility(shown ? VISIBLE : GONE);
    }

    public boolean isManualDotShown() {
        return dot != null && dot.getVisibility() == VISIBLE;
    }

    /**
     * The slot at the strip's right end, opposite the toggle, for one square button of the app (empty and gone by default).
     * Shown while the strip is collapsed; the expanded tabs take its place.
     */
    public android.widget.FrameLayout endSlot() {
        return endSlot;
    }

    /** Re-evaluates the end slot after the app added or removed its view. */
    public void refreshEndSlot() {
        updateFrame();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean value, boolean animate) {
        expanded = value;
        if (onExpanded != null) onExpanded.accept(value);
        if (animator != null) animator.cancel();
        if (!value) {
            // Collapsing closes the ruler (the owner's rule), it does not just hide it.
            scale.setVisibility(GONE);
            if (onCollapse != null) onCollapse.run();
        }
        tabs.setVisibility(VISIBLE);
        tabs.setImportantForAccessibility(value ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        toggle.setContentDescription(getContext().getString(value ? R.string.manual_panel_close : R.string.manual_panel_open));
        if (!animate) {
            progress = value ? 1 : 0;
            updateFrame();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, value ? 1 : 0);
        // P509 (owner, 2026-11-05: «красивую анимацию в стиле iOS / macOS»): it grows out of «Про» on a damped spring (a small
        // overshoot) and its tiles rise one after another; it goes back into the button on the exit curve, quicker
        animator.setDuration(value ? OPEN.durationMs : 220);
        animator.setInterpolator(value ? OPEN : EXIT);
        if (value) riseTiles();
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            updateFrame();
        });
        animator.start();
    }

    /** P509: how small the strip is collapsed into «Про». */
    static final float FROM_BUTTON_SCALE = .3f;

    /**
     * Collapsed = opacity 0 and {@link #FROM_BUTTON_SCALE} towards «Про» (the bottom bar's left disc, under the strip's left end); it
     * takes no touches then. The spring's overshoot (progress a little above 1) grows the strip a little past its size.
     */
    private void updateFrame() {
        float p = Math.max(0, Math.min(1, progress));
        tabs.setAlpha(Math.min(1f, p * 2.2f));
        tabs.setPivotX(dp(30));
        tabs.setPivotY(tabs.getHeight() + dp(40));
        final float s = FROM_BUTTON_SCALE + (1f - FROM_BUTTON_SCALE) * Math.max(0f, progress);
        tabs.setScaleX(s);
        tabs.setScaleY(s);
        // The first animation frame has p=0. Later frames must restore visibility.
        tabs.setVisibility(expanded || p > 0 ? VISIBLE : INVISIBLE);
        if (endSlot != null) {
            boolean filled = false;
            for (int i = 0; i < endSlot.getChildCount(); i++) filled |= endSlot.getChildAt(i).getVisibility() == VISIBLE;
            endSlot.setVisibility(filled && !expanded && p == 0 ? VISIBLE : GONE);
        }
    }

    /** P509: the tiles rise into the opening strip one after another (18 ms apart, as the shade's tiles). */
    private void riseTiles() {
        if (!(tabs instanceof ViewGroup)) return;
        final ViewGroup g = (ViewGroup) tabs;
        for (int i = 0; i < g.getChildCount(); i++) {
            final View t = g.getChildAt(i);
            t.animate().cancel();
            // its own alpha (a locked parameter's dim one) is where it ends; 0 only when a rise before was cut short
            final float to = t.getAlpha() > 0f ? t.getAlpha() : 1f;
            t.setTranslationY(dp(12));
            t.setAlpha(0f);
            t.animate().translationY(0f).alpha(to).setStartDelay(40L + 18L * i)
                    .setDuration(320).setInterpolator(RISE).start();
        }
    }

    /** P509: UIKit-like damped spring 0 -> 1: x(t) = 1 - e^(-z w t)(cos wd t + z w / wd sin wd t), the last frame exactly 1. */
    static final class Spring implements android.animation.TimeInterpolator {
        final float response, damping;
        final long durationMs;

        Spring(float response, float damping) {
            this.response = response;
            this.damping = damping;
            this.durationMs = Math.max(120L, Math.round(1000.0 * Math.log(1000.0) / (damping * 2 * Math.PI / response)));
        }

        @Override public float getInterpolation(float f) {
            if (f >= 1f) return 1f;
            final double t = f * durationMs / 1000.0, w = 2 * Math.PI / response, z = damping, wd = w * Math.sqrt(1 - z * z);
            return (float) (1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t)));
        }
    }

    /** P509: the opening spring (as the LUT tray's), the exit and the tiles' rise. */
    static final Spring OPEN = new Spring(0.42f, 0.8f);
    static final android.animation.TimeInterpolator EXIT = new android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    static final android.animation.TimeInterpolator RISE = new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    /** Whether the shade allows the panel on screen (the shade is HIDDEN). */
    private boolean shadeShown = true;
    /** Duration of the fade and slide when the shade leaves or returns to HIDDEN (P495: the return on the sheet curve, the leave quicker). */
    static final int SHADE_RETURN_MS = 240, SHADE_LEAVE_MS = 180;
    /** P495: UIKit's sheet curve (the app's Motion.SHEET; this library cannot see the app). */
    static final android.animation.TimeInterpolator SHEET = new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f);
    /** How far the panel slides down while it fades out. */
    static final float SHADE_SLIDE_DP = 16;

    public boolean isShadeShown() {
        return shadeShown;
    }

    /**
     * Shows the panel while the shade is HIDDEN and hides it at the other levels: a short fade and slide (alpha, 16dp down),
     * INVISIBLE at the end so it takes no touches. Nothing else changes, so it comes back open or closed with the same
     * parameter selected.
     */
    public void setShadeShown(boolean shown, boolean animate) {
        boolean changed = shown != shadeShown;
        shadeShown = shown;
        animate().cancel();
        float slide = dp(SHADE_SLIDE_DP);
        if (!animate) {
            setAlpha(shown ? 1f : 0f);
            setTranslationY(shown ? 0f : slide);
            setVisibility(shown ? VISIBLE : INVISIBLE);
            return;
        }
        if (!changed && (shown ? getVisibility() == VISIBLE && getAlpha() == 1f : getVisibility() != VISIBLE)) return;
        if (shown) {
            setVisibility(VISIBLE);
            animate().alpha(1f).translationY(0f).setDuration(SHADE_RETURN_MS).setInterpolator(SHEET).start();
        } else {
            animate().alpha(0f).translationY(slide).setDuration(SHADE_LEAVE_MS).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> { if (!shadeShown) setVisibility(INVISIBLE); }).start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
