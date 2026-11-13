package com.scamera.app.ui.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.scamera.app.ui.settings.SettingsMotion;

/**
 * P574: the iOS segmented control of the concept - a capsule track, one thumb that glides to the chosen segment on a spring, the
 * chosen label in the accent. Segments are equal; a label that does not fit in its lines (two, or the label's own maxLines) makes
 * every label smaller, down from the size it was given (13 sp unless the caller set one) to 11 sp at the least.
 * In RTL the first segment is the right one, the thumb with it.
 */
public class IosSegmented extends LinearLayout {
    public interface Listener { void onPick(IosSegmented s, int index); }

    private static final SettingsMotion.Spring GLIDE = new SettingsMotion.Spring(0.4f, 0.72f);

    private int selected = -1;
    private float thumbAt = -1f;
    private int accent, ink = 0xBFFFFFFF, track = 0x3D767680, thumb = 0xFF636366;
    @Nullable private ValueAnimator anim;
    @Nullable private Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    /** Each label's own size (px, the caller's) and the size the fit gave it last (NaN: none yet). */
    private float[] basePx = new float[0], fittedPx = new float[0];

    public IosSegmented(Context c) { this(c, null); }

    public IosSegmented(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        accent = com.scamera.app.circularbarlib.ui.AccentPalette.camera(c);
        setOrientation(HORIZONTAL);
        final int pad = Math.round(dp(3));
        setPadding(pad, pad, pad, pad);
        setWillNotDraw(false);
    }

    private float dp(float v) { return v * density; }

    public void setListener(@Nullable Listener l) { listener = l; }

    public void setColours(int accent, int track, int thumb) {
        this.accent = accent;
        this.track = track;
        this.thumb = thumb;
        paintLabels();
        invalidate();
    }

    public int getSelected() { return selected; }

    /** The segments' labels; the selection is kept when it still exists. */
    public void setItems(CharSequence[] labels) {
        removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final TextView t = new TextView(getContext());
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTypeface(com.scamera.app.ui.style.Fonts.of(getContext(), com.scamera.app.ui.style.Fonts.MEDIUM));
            t.setMinHeight(Math.round(dp(34)));
            final int px = Math.round(dp(6));
            t.setPadding(px, Math.round(dp(4)), px, Math.round(dp(4)));
            final int index = i;
            t.setOnClickListener(v -> {
                setSelected(index, true);
                if (listener != null) listener.onPick(this, index);
            });
            t.setContentDescription(labels[i]);
            addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        basePx = new float[labels.length];
        fittedPx = new float[labels.length];
        java.util.Arrays.fill(fittedPx, Float.NaN);
        if (selected >= labels.length) selected = -1;
        thumbAt = selected;
        paintLabels();
        requestLayout();
    }

    /** The chosen segment; {@code animate} glides the thumb there (a tap), else it is there at once (a bind). */
    public void setSelected(int index, boolean animate) {
        if (index == selected && thumbAt == index) return;
        selected = index;
        paintLabels();
        if (anim != null) anim.cancel();
        if (!animate || thumbAt < 0 || index < 0 || !SettingsMotion.enabled(this)) {
            thumbAt = index;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(thumbAt, index);
        anim.setDuration(GLIDE.durationMs);
        anim.setInterpolator(GLIDE);
        anim.addUpdateListener(a -> { thumbAt = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private void paintLabels() {
        for (int i = 0; i < getChildCount(); i++) {
            final View v = getChildAt(i);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(i == selected ? accent : ink);
                v.setSelected(i == selected);
                v.setAccessibilityDelegate(null);
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // a label that wraps: one size for all, the largest that keeps every label in its lines
        post(this::fitLabels);
    }

    void fitLabels() {
        final int n = getChildCount();
        if (n == 0 || n != basePx.length) return;
        final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final float stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 0.5f, dm);
        // start from the caller's size (a label whose size is not the fit's own was set since: that is the caller's now); only shrink
        float start = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            final float now = ((TextView) getChildAt(i)).getTextSize();
            if (Float.isNaN(fittedPx[i]) || Math.abs(now - fittedPx[i]) > 0.01f) basePx[i] = now;
            start = Math.min(start, basePx[i]);
        }
        final float floor = Math.min(start, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, dm));
        final Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
        float size = start;
        for (; size > floor; size = Math.max(floor, size - stepPx)) {
            boolean fits = true;
            for (int i = 0; i < n; i++) {
                final TextView t = (TextView) getChildAt(i);
                final int room = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
                if (room <= 0) return;
                final int lines = t.getMaxLines();
                if (lines <= 0 || lines == Integer.MAX_VALUE) continue;   // no line limit: it always fits
                probe.set(t.getPaint());
                probe.setTextSize(size);
                if (probe.measureText(t.getText().toString()) > room * lines * 0.96f) { fits = false; break; }
            }
            if (fits) break;
        }
        for (int i = 0; i < n; i++) {
            final TextView t = (TextView) getChildAt(i);
            if (Math.abs(t.getTextSize() - size) > 0.01f) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            fittedPx[i] = t.getTextSize();
        }
    }

    @Override protected void onDraw(Canvas c) {
        final float h = getHeight(), w = getWidth();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(track);
        rect.set(0, 0, w, h);
        final float r = Math.min(h / 2f, dp(20));
        c.drawRoundRect(rect, r, r, paint);
        final int n = getChildCount();
        if (n == 0 || thumbAt < 0) return;
        final float inner = w - getPaddingLeft() - getPaddingRight();
        final float segW = inner / n;
        // the children run from the right in RTL (LinearLayout), the thumb with them
        final float x = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? w - getPaddingRight() - (thumbAt + 1) * segW
                : getPaddingLeft() + thumbAt * segW;
        rect.set(x, getPaddingTop(), x + segW, h - getPaddingBottom());
        final float tr = Math.min(rect.height() / 2f, dp(17));
        paint.setColor(0x26000000);
        rect.offset(0, dp(1));
        c.drawRoundRect(rect, tr, tr, paint);
        rect.offset(0, -dp(1));
        paint.setColor(thumb);
        c.drawRoundRect(rect, tr, tr, paint);
    }
}
