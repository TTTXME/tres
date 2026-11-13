package com.scamera.app.ui.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.scamera.app.circularbarlib.ui.AccentPalette;
import com.scamera.app.ui.settings.SettingsMotion;

/**
 * P574 (owner, 2026-11-09: «Ползунки … полностью все сделай ближе к IOS и сделай красивую отпрыгивающую анимацию при их
 * изменении/регулировки», the concept's slider): the iOS slider of the whole app - a thin track, a white knob that turns into a
 * glass capsule under the finger, a rubber band past both ends that springs back, the knob's bounce on release.
 * <ul>
 *   <li>The drag is relative (no jump where the finger lands); a tap without a move jumps there, a double tap goes to the
 *   default ({@link #setDefaultValue}), both on a spring.</li>
 *   <li>A vertical move is left to the list (the view asks its parent not to intercept only once the move is horizontal).</li>
 *   <li>A haptic tick at both ends and when the value passes the default.</li>
 *   <li>Every animation is an Animator: the system's animator scale and «remove animations» apply.</li>
 * </ul>
 * The value is a float in [min, max] on a grid of {@code step} (0: continuous).
 */
public class IosSlider extends View {
    /** The value changed: {@code fromUser} false for {@link #setValue} calls. */
    public interface Listener {
        void onValue(IosSlider s, float value, boolean fromUser);

        /**
         * The user started ({@code true}) or ended ({@code false}) changing the value: told once a touch is a horizontal drag
         * or a tap (its finger-up), never for a touch the list takes as a vertical scroll, a touch cancelled before it was
         * either, a hold or a double tap's second touch. A drag cancelled after it started ends normally (the value shown is
         * the one dragged to). A value the user sets with no touch tracked ({@link #isTracking} false: a double tap, the
         * keyboard, TalkBack) is told by {@link #onValue} alone.
         */
        default void onTracking(IosSlider s, boolean tracking) {}
    }

    private static final SettingsMotion.Spring SETTLE = new SettingsMotion.Spring(0.5f, 0.55f);
    private static final SettingsMotion.Spring MORPH = new SettingsMotion.Spring(0.35f, 0.62f);

    private float min = 0f, max = 1f, step = 0f, value = 0f, def = Float.NaN;
    private int accent;
    private int trackColour = 0x5C787880;
    @Nullable private Listener listener;
    /** The double tap's own action instead of the default value ({@link #setOnResetListener}). */
    @Nullable private Runnable onReset;

    // what is drawn: the knob's position (0..1, eased towards the value), the rubber band (px), the drag morph (0..1), the bounce
    private float shownK = 0f, over = 0f, morph = 0f, bounce = 0f;
    @Nullable private ValueAnimator kAnim, overAnim, morphAnim, bounceAnim;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final int touchSlop;
    private final GestureDetector taps;

    /** down: a touch this slider still follows; tracking: told to the listener ({@link Listener#onTracking}). */
    private boolean down, dragging, tracking;
    /** P574: the second tap of a double tap went to the default, or a hold was a long click: its finger-up is no tap that jumps. */
    private boolean doubleTapped;
    private float downX, downY, downK;

    public IosSlider(Context c) { this(c, null); }

    public IosSlider(Context c, @Nullable AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        accent = AccentPalette.camera(c);
        setFocusable(true);
        setClickable(true);
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (onReset == null && Float.isNaN(def)) return false;
                doubleTapped = true;
                if (onReset != null) onReset.run(); else userSet(def, true);
                bounce();
                return true;
            }

            // P574: a hold without a move is the view's long click (a parameter's description), and its release no tap
            @Override public void onLongPress(MotionEvent e) {
                if (dragging || !isLongClickable()) return;
                if (performLongClick()) doubleTapped = true;
            }
        });
    }

    // ───── API ─────

    public void setRange(float min, float max, float step) {
        this.min = min;
        this.max = Math.max(min, max);
        this.step = Math.max(0f, step);
        value = clamp(value);
        shownK = k(value);
        invalidate();
    }

    public float getMin() { return min; }
    public float getMax() { return max; }
    public float getValue() { return value; }

    /** The value without telling the listener a user moved it; the knob glides there when it is on screen. */
    public void setValue(float v) {
        final float c = snap(clamp(v));
        if (c == value && shownK == k(c)) return;
        value = c;
        if (!dragging) glideTo(k(c), isAttachedToWindow() && SettingsMotion.enabled(this) && Math.abs(k(c) - shownK) > 0.002f);
        if (listener != null) listener.onValue(this, value, false);
    }

    /** The value at once (a recycled row bound to another preference: no glide from the old one). */
    public void setValueNow(float v) {
        value = snap(clamp(v));
        cancel(kAnim);
        shownK = k(value);
        invalidate();
    }

    /** The double tap's value (NaN: none). */
    public void setDefaultValue(float v) { def = Float.isNaN(v) ? Float.NaN : snap(clamp(v)); }

    public void setAccent(int colour) { accent = colour; invalidate(); }

    /** The track's empty part (default: iOS' fill colour on dark, rgba(120, 120, 128, .36)). */
    public void setTrackColour(int colour) { trackColour = colour; invalidate(); }

    public void setListener(@Nullable Listener l) { listener = l; }

    /**
     * A double tap runs {@code r} instead of setting the default value (a preference's own exact reset); null: the default
     * value ({@link #setDefaultValue}) again.
     */
    public void setOnResetListener(@Nullable Runnable r) { onReset = r; }

    /** A touch is changing the value (between {@link Listener#onTracking} true and false). */
    public boolean isTracking() { return tracking; }

    /**
     * P574: {@code v} as if the user chose it (the precise value, a test): snapped, told with {@code fromUser} true while no finger
     * is down ({@link #isTracking} false: a listener writing on the finger's release writes it at once), the knob glides there.
     */
    public void setValueFromUser(float v) { userSet(v, isAttachedToWindow()); }

    // ───── geometry ─────

    private float dp(float v) { return v * density; }

    private float clamp(float v) { return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v)); }

    private float snap(float v) {
        if (step <= 0f) return v;
        final float s = min + Math.round((v - min) / step) * step;
        return Math.max(min, Math.min(max, s));
    }

    private float k(float v) { return max > min ? (v - min) / (max - min) : 0f; }

    /** The knob's travel: inset by its radius so it never leaves the track. */
    private float travel() { return Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight() - dp(28)); }

    private float left() { return getPaddingLeft() + dp(14); }

    private boolean rtl() { return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }

    /** The knob's centre for {@code k} (0..1): from the start edge, the right one in RTL. */
    private float knobX(float k) {
        return rtl() ? getWidth() - getPaddingRight() - dp(14) - k * travel() : left() + k * travel();
    }

    /** {@code x} on the track as 0..1 from the start edge (unclamped). */
    private float kAt(float x) {
        return rtl() ? (getWidth() - getPaddingRight() - dp(14) - x) / travel() : (x - left()) / travel();
    }

    @Override protected void onMeasure(int w, int h) {
        final int want = (int) Math.ceil(dp(38)) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w), resolveSize(want, h));
    }

    // ───── drawing ─────

    @Override protected void onDraw(Canvas c) {
        final float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        final float trackH = dp(6) + dp(3) * morph;
        final float x0 = getPaddingLeft(), x1 = getWidth() - getPaddingRight();
        final float w = x1 - x0;
        // the rubber band: the track stretched from its far end, the knob carried along
        final float stretch = w > 0 ? Math.abs(over) / w : 0f;
        // over is along the value (past max > 0); on screen it points the other way in RTL
        final float overX = rtl() ? -over : over;
        c.save();
        if (overX != 0f) c.scale(1f + stretch, 1f, overX < 0 ? x1 : x0, cy);
        final float r = trackH / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(trackColour);
        rect.set(x0, cy - r, x1, cy + r);
        c.drawRoundRect(rect, r, r, paint);
        final float knobX = knobX(shownK);
        paint.setColor(accent);
        if (rtl()) rect.set(Math.min(x1 - 2 * r, knobX), cy - r, x1, cy + r);
        else rect.set(x0, cy - r, Math.max(x0 + 2 * r, knobX), cy + r);
        c.drawRoundRect(rect, r, r, paint);
        c.restore();
        final float kx = knobX + overX * 0.9f;
        // the knob: a white disc at rest, a glass capsule while held; the bounce squashes it after the finger leaves
        final float kw = dp(28) + dp(14) * morph, kh = dp(28) - dp(2) * morph;
        final float sx = 1f + 0.2f * bounce, sy = 1f - 0.16f * bounce;
        final float hw = kw * sx / 2f, hh = kh * sy / 2f;
        rect.set(kx - hw, cy - hh, kx + hw, cy + hh);
        final float kr = Math.min(hw, hh);
        // shadow: two soft rings under the disc (no software layer)
        paint.setStyle(Paint.Style.FILL);
        final int shadowA = (int) (60 * (1f - morph * 0.4f));
        paint.setColor(shadowA << 24);
        rect.offset(0, dp(1.5f));
        c.drawRoundRect(rect, kr, kr, paint);
        rect.offset(0, -dp(1.5f));
        paint.setColor(blend(0xFFFFFFFF, 0x2EFFFFFF, morph));
        c.drawRoundRect(rect, kr, kr, paint);
        if (morph > 0.01f) {
            // the glass: a bright rim and the accent seen through the lens
            paint.setColor((int) (0x30 * morph) << 24 | (accent & 0xFFFFFF));
            c.drawRoundRect(rect, kr, kr, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor((int) (0xE6 * morph) << 24 | 0xFFFFFF);
            rect.inset(dp(0.75f), dp(0.75f));
            c.drawRoundRect(rect, kr, kr, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private static int blend(int a, int b, float t) {
        return SettingsMotion.mix(a, b, Math.max(0f, Math.min(1f, t)));
    }

    // ───── touch ─────

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        taps.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // the listener hears of it once the touch is a horizontal drag or a tap: a scroll of the list writes nothing
                down = true;
                dragging = false;
                tracking = false;
                downX = e.getX();
                downY = e.getY();
                downK = k(value);
                if (!doubleTapped) {   // the double tap's glide to the default goes on
                    cancel(kAnim);
                    shownK = downK;
                }
                morphTo(1f);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!down) return false;   // let go for a vertical move: the rest of the touch is the list's
                final float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging) {
                    if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop) return true;
                    if (Math.abs(dy) >= Math.abs(dx)) {
                        // a vertical move: the list scrolls, the slider lets go
                        release(false);
                        return false;
                    }
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    startTracking();
                }
                final float raw = downK + (rtl() ? -dx : dx) / travel();
                final float ex = raw < 0 ? raw * travel() : raw > 1 ? (raw - 1) * travel() : 0f;
                cancel(overAnim);
                over = Math.signum(ex) * dp(20) * (float) (1 - Math.exp(-Math.abs(ex) / dp(70)));
                userSet(min + Math.max(0f, Math.min(1f, raw)) * (max - min), false);
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!down) { doubleTapped = false; return false; }   // let go for a vertical move: no tap
                if (!dragging && !doubleTapped) {
                    // a tap: the knob jumps where the finger was, on the spring (not the double tap's second: it went to the default)
                    final float k = Math.max(0f, Math.min(1f, kAt(e.getX())));
                    startTracking();
                    userSet(min + k * (max - min), true);
                }
                release(true);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release(true);
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void startTracking() {
        if (tracking) return;
        tracking = true;
        if (listener != null) listener.onTracking(this, true);
    }

    private void release(boolean withBounce) {
        doubleTapped = false;
        dragging = false;
        if (!down) return;
        down = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        morphTo(0f);
        springOver();
        if (withBounce) bounce();
        if (!tracking) return;
        tracking = false;
        if (listener != null) listener.onTracking(this, false);
    }

    /** A value the user chose: snapped, told, with a tick at the ends and at the default; glides unless the finger drags it. */
    private void userSet(float v, boolean glide) {
        final float before = value, c = snap(clamp(v));
        if (c != before) {
            final boolean crossedDefault = !Float.isNaN(def) && (before - def) * (c - def) <= 0 && c != before;
            if (c == min || c == max || crossedDefault) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            value = c;
            if (listener != null) listener.onValue(this, value, true);
        }
        glideTo(k(value), glide && SettingsMotion.enabled(this));
    }

    // ───── motion ─────

    private static void cancel(@Nullable ValueAnimator a) { if (a != null) a.cancel(); }

    private void glideTo(float k, boolean animate) {
        cancel(kAnim);
        if (!animate) {
            shownK = k;
            invalidate();
            return;
        }
        final float from = shownK;
        kAnim = ValueAnimator.ofFloat(0f, 1f);
        kAnim.setDuration(SETTLE.durationMs);
        kAnim.setInterpolator(SETTLE);
        kAnim.addUpdateListener(a -> { shownK = from + (k - from) * (float) a.getAnimatedValue(); invalidate(); });
        kAnim.start();
    }

    private void morphTo(float target) {
        cancel(morphAnim);
        if (!SettingsMotion.enabled(this)) { morph = target; invalidate(); return; }
        final float from = morph;
        morphAnim = ValueAnimator.ofFloat(from, target);
        morphAnim.setDuration(MORPH.durationMs);
        morphAnim.setInterpolator(MORPH);
        morphAnim.addUpdateListener(a -> { morph = Math.max(0f, (float) a.getAnimatedValue()); invalidate(); });
        morphAnim.start();
    }

    private void springOver() {
        cancel(overAnim);
        if (over == 0f) return;
        if (!SettingsMotion.enabled(this)) { over = 0f; invalidate(); return; }
        final float from = over;
        // the spring overshoots: the band snaps a little past straight before it rests
        overAnim = ValueAnimator.ofFloat(0f, 1f);
        overAnim.setDuration(SETTLE.durationMs);
        overAnim.setInterpolator(SETTLE);
        overAnim.addUpdateListener(a -> { over = from * (1f - (float) a.getAnimatedValue()); invalidate(); });
        overAnim.start();
    }

    /** The release squash: wide and flat, then a little tall, then round (iOS' knob after the finger). */
    private void bounce() {
        cancel(bounceAnim);
        if (!SettingsMotion.enabled(this)) { bounce = 0f; invalidate(); return; }
        bounceAnim = ValueAnimator.ofFloat(0f, 1f);
        bounceAnim.setDuration(550);
        bounceAnim.addUpdateListener(a -> {
            final float t = (float) a.getAnimatedValue();
            // a damped wave from +1: 0.3 -> wide, 0.6 -> a little tall, 1 -> at rest
            bounce = (float) (Math.exp(-4.0 * t) * Math.cos(2 * Math.PI * 1.6 * t)) * (t < 0.08f ? t / 0.08f : 1f);
            invalidate();
        });
        bounceAnim.start();
    }

    @Override protected void onDetachedFromWindow() {
        cancel(kAnim); cancel(overAnim); cancel(morphAnim); cancel(bounceAnim);
        over = 0f; morph = 0f; bounce = 0f; shownK = k(value); down = false; dragging = false; tracking = false;
        super.onDetachedFromWindow();
    }

    // ───── keyboard and accessibility ─────

    private float keyStep() { return step > 0 ? step : (max - min) / 100f; }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (isEnabled() && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // towards the end edge raises the value: the left one in RTL
            final boolean up = (code == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl();
            userSet(value + (up ? keyStep() : -keyStep()), true);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public CharSequence getAccessibilityClassName() { return android.widget.SeekBar.class.getName(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, min, max, value));
        if (isEnabled()) {
            if (value > min) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (value < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
    }

    @Override public boolean performAccessibilityAction(int action, @Nullable Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            userSet(value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? keyStep() : -keyStep()), true);
            return true;
        }
        // as SeekBar: TalkBack's exact value, told as the user's (no touch tracked: the listener commits it)
        if (action == android.R.id.accessibilityActionSetProgress && isEnabled() && args != null
                && args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
            userSet(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE), true);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
