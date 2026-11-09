package com.particlesdevs.photoncamera.ui.controls;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.ui.settings.SettingsMotion;

import java.util.function.IntConsumer;

/**
 * P574 (owner, 2026-11-09, the concept: «Все вот такие подменю … сделай ближе по стилю», a list's bottom sheet of radio buttons):
 * a pull-down menu anchored at the row that opened it - a rounded dark card under the row (above it when there is no room), the
 * chosen item ticked in the accent, grown from the anchor's corner on a spring and shrunk back on close; the screen behind is
 * dimmed a little. Long lists scroll inside the card.
 */
public final class IosMenu {
    private IosMenu() {}

    private static final SettingsMotion.Spring OPEN = new SettingsMotion.Spring(0.42f, 0.72f);

    /**
     * Shows the menu under (or above) {@code anchor}: {@code title} in grey on top (null: none), the {@code labels} with
     * {@code checked} ticked (-1: none), {@code onPick} with the index after the close begins.
     */
    public static PopupWindow show(View anchor, @Nullable CharSequence title, CharSequence[] labels, int checked, IntConsumer onPick) {
        return show(anchor, title, labels, null, checked, onPick);
    }

    /** As {@link #show(View, CharSequence, CharSequence[], int, IntConsumer)}, each item tagged "option_" + its tag (null: none). */
    public static PopupWindow show(View anchor, @Nullable CharSequence title, CharSequence[] labels, @Nullable CharSequence[] tags,
                                   int checked, IntConsumer onPick) {
        final Context c = anchor.getContext();
        final float d = c.getResources().getDisplayMetrics().density;
        final int accent = AccentPalette.camera(c);
        final LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, Math.round(6 * d));
        if (title != null && title.length() > 0) {
            final TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTextColor(0x8CFFFFFF);
            t.setPadding(Math.round(18 * d), Math.round(12 * d), Math.round(18 * d), Math.round(6 * d));
            list.addView(t);
        }
        final ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(list);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF22C2C2E);
        bg.setCornerRadius(26 * d);
        bg.setStroke(Math.max(1, Math.round(0.5f * d)), 0x24FFFFFF);
        final FrameLayout card = new FrameLayout(c);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(24 * d);
        card.addView(scroll);
        final PopupWindow pw = new PopupWindow(card, Math.round(262 * d), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(0));
        pw.setOutsideTouchable(true);
        pw.setElevation(24 * d);
        pw.setAnimationStyle(0);
        final boolean[] closing = {false};
        for (int i = 0; i < labels.length; i++) {
            final LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Math.round(46 * d));
            row.setPadding(Math.round(12 * d), Math.round(6 * d), Math.round(18 * d), Math.round(6 * d));
            row.setBackground(pressed(c));
            final ImageView tick = new ImageView(c);
            tick.setImageResource(R.drawable.ios_menu_check);
            tick.setColorFilter(accent);
            tick.setVisibility(i == checked ? View.VISIBLE : View.INVISIBLE);
            row.addView(tick, new LinearLayout.LayoutParams(Math.round(18 * d), Math.round(18 * d)));
            final TextView label = new TextView(c);
            label.setText(labels[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setTextColor(0xFFFFFFFF);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setMaxLines(3);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(Math.round(8 * d));
            row.addView(label, lp);
            row.setContentDescription(labels[i]);
            if (i == checked) tick.setTag("ticked");
            if (tags != null && i < tags.length && tags[i] != null) row.setTag("option_" + tags[i]);
            // a choice is checkable; the pin row («Добавить в шторку» / «Убрать из шторки») is an action, not a choice
            if (!"option_pin".equals(row.getTag())) row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCheckable(true);
                    info.setChecked(host.findViewWithTag("ticked") != null);
                }
            });
            final int index = i;
            row.setOnClickListener(v -> {
                if (closing[0]) return;
                close(pw, card, closing);
                onPick.accept(index);
            });
            list.addView(row);
        }
        // where: under the anchor, its end aligned 20 dp from the screen's edge; above when there is no room; centred when neither
        final Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        final int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        card.measure(View.MeasureSpec.makeMeasureSpec(Math.round(262 * d), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int margin = Math.round(12 * d), maxH = frame.height() - 2 * margin;
        final int h = Math.min(card.getMeasuredHeight(), maxH);
        pw.setHeight(h);
        int y = at[1] + anchor.getHeight() + Math.round(6 * d);
        boolean below = true;
        if (y + h > frame.bottom - margin) {
            y = at[1] - h - Math.round(6 * d);
            below = false;
        }
        if (y < frame.top + margin) y = frame.top + Math.max(margin, (frame.height() - h) / 2);
        // RTL: the mirror - its left edge 20 dp from the screen's left edge, the card growing from there (pivot 0)
        final boolean rtl = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int x = rtl ? frame.left + Math.round(20 * d) : frame.right - Math.round(20 * d) - Math.round(262 * d);
        card.setPivotX(rtl ? 0 : Math.round(262 * d));
        card.setPivotY(below ? 0 : h);
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, rtl ? Math.min(frame.right - margin - Math.round(262 * d), x)
                : Math.max(frame.left + margin, x), y);
        dim(pw, 0.18f);
        if (checked >= 0 && checked < labels.length) {
            final int target = checked;
            scroll.post(() -> {
                final View row = list.getChildAt(target + (list.getChildCount() - labels.length));
                if (row != null && row.getBottom() > scroll.getHeight()) scroll.scrollTo(0, row.getTop() - scroll.getHeight() / 3);
            });
        }
        if (SettingsMotion.enabled(anchor)) {
            card.setScaleX(0.25f);
            card.setScaleY(0.25f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(OPEN.durationMs).setInterpolator(OPEN).start();
        }
        return pw;
    }

    private static void close(PopupWindow pw, View card, boolean[] closing) {
        closing[0] = true;
        if (!SettingsMotion.enabled(card)) { safeDismiss(pw); return; }
        card.animate().cancel();
        card.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(170).setInterpolator(SettingsMotion.EXIT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { safeDismiss(pw); }
                }).start();
    }

    private static void safeDismiss(PopupWindow pw) {
        try { pw.dismiss(); } catch (RuntimeException ignored) { /* the window went with its activity */ }
    }

    /** The screen behind the popup dimmed by {@code amount} (the popup's own window flag). */
    private static void dim(PopupWindow pw, float amount) {
        try {
            final View root = pw.getContentView().getRootView();
            final WindowManager wm = (WindowManager) root.getContext().getSystemService(Context.WINDOW_SERVICE);
            final ViewGroup.LayoutParams p = root.getLayoutParams();
            if (wm == null || !(p instanceof WindowManager.LayoutParams)) return;
            final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p;
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = amount;
            wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) { /* no dim: the menu still works */ }
    }

    private static android.graphics.drawable.Drawable pressed(Context c) {
        final android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x1FFFFFFF));
        s.addState(new int[0], new ColorDrawable(0));
        return s;
    }
}
