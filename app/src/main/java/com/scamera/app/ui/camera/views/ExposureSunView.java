package com.scamera.app.ui.camera.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.ManualFormat;
import com.scamera.app.circularbarlib.ui.UiScale;

/**
 * SIMPLE_UI §1, the concept's brightness control beside the focus brackets: a vertical line (P583: 1.5dp, the accent at
 * 80 %) broken by the line sun (20dp, the accent) at the current exposure compensation, its value («+0,7») to the right of
 * the sun while it is not 0. Dragging it up brightens, down darkens; the value is the Pro panel's EV (one state), set through
 * {@link Listener}. 96dp x k high, 22dp x k wide plus the label; it never takes a touch outside itself.
 */
public class ExposureSunView extends View {
    /** The concept's sizes before the scale. */
    // P583 (the concept's sun beside the focus brackets): a 96dp column 22dp wide, 11dp after the brackets; the sun 20dp
    public static final float HEIGHT_DP = 96f, WIDTH_DP = 22f, KNOB_DP = 20f, GAP_DP = 11f, LABEL_DP = 48f;

    public interface Listener {
        /** A drag moved the value (EV, already within the range). */
        void onEv(float ev);

        /** The finger came down / went up on the control (the ring stays while it is held). */
        default void onDrag(boolean dragging) { }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable private final Drawable sun;
    private float min = -2f, max = 2f, ev;
    @Nullable private Listener listener;
    private float downY, downEv;
    private boolean dragging;

    public ExposureSunView(Context context) {
        this(context, null);
    }

    public ExposureSunView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        Drawable d = context.getDrawable(R.drawable.cv_sun);
        sun = d == null ? null : d.mutate();
        // P583: the concept's line sun in the camera accent (its yellow)
        if (sun != null) sun.setTint(com.scamera.app.circularbarlib.ui.AccentPalette.camera(context));
        label.setColor(0xFFFFFFFF);
        label.setTextSize(UiScale.spPx(context, 12));
        label.setTypeface(com.scamera.app.ui.style.Fonts.of(context, com.scamera.app.ui.style.Fonts.SEMIBOLD));
        label.setShadowLayer(UiScale.dpF(context, 3), 0, UiScale.dpF(context, 1), 0xFF000000);
        setContentDescription(context.getString(R.string.ev_sun_description));
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** The EV range of the camera (the Pro EV's). */
    public void setRange(float min, float max) {
        if (max > min) {
            this.min = min;
            this.max = max;
        }
        invalidate();
    }

    public void setEv(float ev) {
        this.ev = Math.max(min, Math.min(max, ev));
        invalidate();
    }

    public float getEv() {
        return ev;
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(UiScale.dp(getContext(), WIDTH_DP + LABEL_DP), UiScale.dp(getContext(), HEIGHT_DP));
    }

    /** px per 1 EV (0 EV at {@link #ZERO} of the height). */
    float pxPerEv() {
        // P583: one scale both ways - the room on the shorter side of 0 EV over its end of the range
        float r = UiScale.dpF(getContext(), KNOB_DP) / 2f, zero = zeroY();
        float up = (zero - r) / Math.max(1e-3f, max), down = (getHeight() - r - zero) / Math.max(1e-3f, -min);
        return Math.max(0f, Math.min(up, down));
    }

    /**
     * P583 (the concept: the line split 1 : 1.4 around the 20px sun in its 96px column): 0 EV sits {@link #ZERO} of the way
     * down, not in the middle.
     */
    static final float ZERO = (HEIGHT_DP - KNOB_DP) / 2.4f / HEIGHT_DP + KNOB_DP / 2f / HEIGHT_DP;

    float zeroY() {
        return getHeight() * ZERO;
    }

    float knobY() {
        return zeroY() - ev * pxPerEv();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float cx = UiScale.dpF(c, WIDTH_DP) / 2f, r = UiScale.dpF(c, KNOB_DP) / 2f;
        // P583 (the concept): a 1.5dp line in the accent at 80 %, broken by the sun (no disc under it)
        final int accent = com.scamera.app.circularbarlib.ui.AccentPalette.camera(c);
        paint.setColor((accent & 0x00FFFFFF) | 0xCC000000);
        paint.setStrokeWidth(UiScale.dpF(c, 1.5f));
        paint.setStrokeCap(Paint.Cap.BUTT);
        float y = knobY();
        if (y - r > 0) canvas.drawLine(cx, 0, cx, y - r, paint);
        if (y + r < getHeight()) canvas.drawLine(cx, y + r, cx, getHeight(), paint);
        if (sun != null) {
            int s = Math.round(UiScale.dpF(c, KNOB_DP) / 2f);
            sun.setBounds(Math.round(cx) - s, Math.round(y) - s, Math.round(cx) + s, Math.round(y) + s);
            sun.draw(canvas);
        }
        if (Math.abs(ev) >= 0.05f) {
            String text = ManualFormat.ev(ev);
            Paint.FontMetrics fm = label.getFontMetrics();
            canvas.drawText(text, UiScale.dpF(c, WIDTH_DP + 4), y - (fm.ascent + fm.descent) / 2f, label);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float knobW = UiScale.dpF(getContext(), WIDTH_DP);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // only the line and the knob take the touch (the label's room is for show)
                if (e.getX() > knobW + UiScale.dpF(getContext(), 6)) return false;
                downY = e.getY();
                downEv = ev;
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onDrag(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                float next = Math.max(min, Math.min(max, downEv - (e.getY() - downY) / pxPerEv()));
                // thirds of a stop, as the Pro ruler's EV
                next = Math.round(next * 3f) / 3f;
                if (Math.abs(next - ev) > 1e-4f) {
                    ev = next;
                    invalidate();
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.onEv(ev);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging && listener != null) listener.onDrag(false);
                dragging = false;
                return true;
            default:
                return dragging;
        }
    }

    /**
     * Places the control beside a ring of radius {@code ringR} centred at ({@code cx}, {@code cy}) in its parent: on the
     * right, {@link #GAP_DP} after the ring, or on the left where the right side has no room; centred on the ring's height
     * and kept inside {@code parentW} x {@code parentH}.
     */
    public void placeBeside(float cx, float cy, float ringR, int parentW, int parentH) {
        Context c = getContext();
        float w = UiScale.dpF(c, WIDTH_DP), total = UiScale.dpF(c, WIDTH_DP + LABEL_DP), h = UiScale.dpF(c, HEIGHT_DP);
        float gap = UiScale.dpF(c, GAP_DP);
        float x = cx + ringR + gap;
        if (x + total > parentW) x = cx - ringR - gap - w;
        float y = Math.max(0f, Math.min(parentH - h, cy - h / 2f));
        setX(Math.max(0f, x));
        setY(y);
    }
}
