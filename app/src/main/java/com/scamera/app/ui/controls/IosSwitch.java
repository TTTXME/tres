package com.scamera.app.ui.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Checkable;

import androidx.annotation.Nullable;

import com.scamera.app.ui.settings.SettingsMotion;

/**
 * P574: the switch of the approved concept - 51 x 31 dp, a big light knob that slides on a spring and widens under the finger; on,
 * the track takes the phone's theme colour like the rest of the interface (owner, 2026-11-09: «Цвет пускай будет зависеть от темы
 * смартфона, как и остальной интерфейс»: AccentPalette.camera - the system's Material You colour, else the chosen accent), off a
 * grey track with a hairline edge (the owner's mix of the concept's switch and Material You's).
 * {@link Checkable}: a preference row's widget (SwitchPreferenceCompat syncs any Checkable widget); a row tap toggles through the
 * preference, a tap on the switch itself toggles it here and tells {@link #setOnCheckedChangeListener}'s listener.
 * {@link #setCheckedNow} sets it without the slide (a recycled row bound to another preference).
 */
public class IosSwitch extends View implements Checkable {
    public interface OnCheckedChangeListener { void onCheckedChanged(IosSwitch s, boolean checked); }

    private static final SettingsMotion.Spring SLIDE = new SettingsMotion.Spring(0.38f, 0.68f);
    public static final int GREEN = 0xFF30D158;

    private boolean checked;
    private float pos, press;
    private int onColour = GREEN, offColour = 0xFF39393C;
    @Nullable private ValueAnimator posAnim, pressAnim;
    @Nullable private OnCheckedChangeListener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    public IosSwitch(Context c) { this(c, null); }

    public IosSwitch(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        onColour = com.scamera.app.circularbarlib.ui.AccentPalette.camera(c);
    }

    private float dp(float v) { return v * density; }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize((int) Math.ceil(dp(51)) + getPaddingLeft() + getPaddingRight(), w),
                resolveSize((int) Math.ceil(dp(31)) + getPaddingTop() + getPaddingBottom(), h));
    }

    public void setColours(int on, int off) { onColour = on; offColour = off; invalidate(); }

    public void setOnCheckedChangeListener(@Nullable OnCheckedChangeListener l) { listener = l; }

    @Override public boolean isChecked() { return checked; }

    @Override public void setChecked(boolean c) {
        if (c == checked) return;
        checked = c;
        slide(SettingsMotion.enabled(this));
        refreshDrawableState();
        // no accessibility event here: a bind (a recycled row) is no click, and a tap's performClick sends its own
    }

    /** No slide: the knob at its end at once. */
    public void setCheckedNow(boolean c) {
        checked = c;
        if (posAnim != null) posAnim.cancel();
        pos = c ? 1f : 0f;
        invalidate();
    }

    @Override public void toggle() {
        setChecked(!checked);
        if (listener != null) listener.onCheckedChanged(this, checked);
    }

    @Override public boolean performClick() {
        toggle();
        return super.performClick();
    }

    private void slide(boolean animate) {
        if (posAnim != null) posAnim.cancel();
        final float to = checked ? 1f : 0f;
        if (!animate) { pos = to; invalidate(); return; }
        posAnim = ValueAnimator.ofFloat(pos, to);
        posAnim.setDuration(SLIDE.durationMs);
        posAnim.setInterpolator(SLIDE);
        posAnim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
        posAnim.start();
    }

    private void pressTo(float to) {
        if (pressAnim != null) pressAnim.cancel();
        if (!SettingsMotion.enabled(this)) { press = to; invalidate(); return; }
        pressAnim = ValueAnimator.ofFloat(press, to);
        pressAnim.setDuration(180);
        pressAnim.addUpdateListener(a -> { press = (float) a.getAnimatedValue(); invalidate(); });
        pressAnim.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: pressTo(1f); break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: pressTo(0f); break;
            default: break;
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onDraw(Canvas c) {
        final float w = dp(51), h = dp(31);
        final float x = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - w) / 2f;
        final float y = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - h) / 2f;
        final float p = Math.max(0f, Math.min(1f, pos));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SettingsMotion.mix(offColour, onColour, p));
        if (!isEnabled()) paint.setAlpha(paint.getAlpha() / 2);
        rect.set(x, y, x + w, y + h);
        c.drawRoundRect(rect, h / 2f, h / 2f, paint);
        // the hairline edge of the off track (gone under the colour when on)
        if (p < 1f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(0.6f));
            paint.setColor(((int) (0x21 * (1f - p)) << 24) | 0xFFFFFF);
            rect.inset(dp(0.3f), dp(0.3f));
            c.drawRoundRect(rect, h / 2f - dp(0.3f), h / 2f - dp(0.3f), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // the knob: 24 dp off (white 88 %), 26.5 dp on (white with a touch of the colour), wider under the finger, on the spring
        final float kd = dp(24f) + dp(2.5f) * p, grow = dp(7) * press;
        final float inset = (h - kd) / 2f;
        final float travel = w - 2 * inset - kd;
        final float kx = x + inset + travel * pos;
        final float left = kx - (checked ? grow : 0f), right = kx + kd + (checked ? 0f : grow);
        rect.set(left, y + inset, right, y + inset + kd);
        paint.setColor(0x4D000000);
        rect.offset(0, dp(2f));
        c.drawRoundRect(rect, kd / 2f, kd / 2f, paint);
        rect.offset(0, -dp(2f));
        final int knob = SettingsMotion.mix(0xE0FFFFFF, SettingsMotion.mix(0xFFFFFFFF, onColour, 0.18f), p);
        paint.setColor(isEnabled() ? knob : 0xFFBDBDBD);
        c.drawRoundRect(rect, kd / 2f, kd / 2f, paint);
    }

    @Override public CharSequence getAccessibilityClassName() { return android.widget.Switch.class.getName(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(checked);
    }
}
