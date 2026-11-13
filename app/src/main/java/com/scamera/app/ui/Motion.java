package com.scamera.app.ui;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import com.scamera.app.circularbarlib.ui.UiScale;
import com.scamera.app.ui.settings.SettingsMotion;

import java.util.List;

/**
 * P495 (owner, 2026-11-03: «сделай полностью по всей камере анимации в стиле MacOS/IOS»): the camera's shared motion - a press that
 * gives under the finger and springs back, an icon's pop on a toggle, controls coming in one after another, the iOS sheet curve. The
 * springs are the settings' (SettingsMotion.Spring, a damped spring with a response and a damping ratio); every animation is an
 * Animator, so the system's animator scale and «remove animations» apply, and without motion everything is at its end at once.
 */
public final class Motion {
    private Motion() {}

    /** UIKit's sheet curve (presenting / dismissing a sheet). */
    public static final TimeInterpolator SHEET = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    /** An entry: Material's emphasised decelerate, close to iOS' default curve. */
    public static final TimeInterpolator EASE_OUT = SettingsMotion.EASE_OUT;
    /** A control coming in: a soft spring with a little overshoot. */
    public static final SettingsMotion.Spring ENTER = new SettingsMotion.Spring(0.42f, 0.7f);
    /** A control coming back after the finger. */
    public static final SettingsMotion.Spring RELEASE = new SettingsMotion.Spring(0.3f, 0.6f);

    /** Motion runs: the system's animators are on and the view is on screen. */
    public static boolean enabled(@Nullable View v) {
        return SettingsMotion.enabled(v);
    }

    /** {@link #press(View, float)} at iOS' .94. */
    public static void press(View v) {
        press(v, .94f);
    }

    /**
     * The view gives under the finger ({@code scale} in 110 ms) and springs back on release. A StateListAnimator on the pressed
     * state, so the view's own touch and click handling stay as they are.
     */
    public static void press(View v, float scale) {
        final StateListAnimator s = new StateListAnimator();
        final ObjectAnimator down = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.SCALE_X, scale), PropertyValuesHolder.ofFloat(View.SCALE_Y, scale));
        down.setDuration(110);
        down.setInterpolator(EASE_OUT);
        final ObjectAnimator up = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f));
        up.setDuration(RELEASE.durationMs);
        up.setInterpolator(RELEASE);
        s.addState(new int[]{android.R.attr.state_pressed}, down);
        s.addState(new int[0], up);
        v.setStateListAnimator(s);
    }

    /** A toggle's icon: in to .82, out past 1 to 1.12 and back to 1 (420 ms), the «breath» of a switched control. */
    public static void pop(@Nullable View icon) {
        if (icon == null) return;
        icon.animate().cancel();
        final Object running = icon.getTag(com.scamera.app.R.id.motion_pop);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        if (!enabled(icon)) return;
        final ValueAnimator a = ValueAnimator.ofFloat(1f, .82f, 1.12f, 1f);
        a.setDuration(420);
        a.setInterpolator(EASE_OUT);
        a.addUpdateListener(x -> {
            final float k = (float) x.getAnimatedValue();
            icon.setScaleX(k);
            icon.setScaleY(k);
        });
        icon.setTag(com.scamera.app.R.id.motion_pop, a);
        a.start();
    }

    /**
     * Controls come in one after another ({@code staggerMs} apart, after {@code delayMs}): from 14 dp lower, scaled to .92 and
     * faded in, on the {@link #ENTER} spring.
     */
    public static void enter(List<? extends View> views, long delayMs, long staggerMs) {
        for (int i = 0; i < views.size(); i++) {
            final View v = views.get(i);
            if (v == null) continue;
            v.animate().cancel();
            if (!enabled(v)) {
                rest(v);
                continue;
            }
            v.setAlpha(0f);
            v.setTranslationY(UiScale.dpF(v.getContext(), 14));
            v.setScaleX(.92f);
            v.setScaleY(.92f);
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(delayMs + i * staggerMs)
                    .setDuration(ENTER.durationMs).setInterpolator(ENTER).withEndAction(() -> rest(v)).start();
        }
    }

    /**
     * P495 (owner, 2026-11-03: «лёгкую анимацию при спуске затвора»): the shot's blink, as the iPhone's - the viewfinder dims to
     * 45 % black in 60 ms and comes back in 240 ms. A foreground of {@code host} (the viewfinder is a SurfaceView: its own alpha
     * does not reach the picture); none with the system's animations off.
     */
    public static void blink(@Nullable View host) {
        if (host == null || !enabled(host)) return;
        Object tag = host.getTag(com.scamera.app.R.id.motion_pop);
        if (tag instanceof ValueAnimator) ((ValueAnimator) tag).cancel();
        final android.graphics.drawable.ColorDrawable veil = new android.graphics.drawable.ColorDrawable(0xFF000000);
        veil.setAlpha(0);
        host.setForeground(veil);
        final ValueAnimator a = ValueAnimator.ofInt(0, 115, 0);
        a.setDuration(300);
        a.setInterpolator(EASE_OUT);
        a.addUpdateListener(x -> veil.setAlpha((int) x.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (host.getForeground() == veil) host.setForeground(null);
            }
        });
        host.setTag(com.scamera.app.R.id.motion_pop, a);
        a.start();
    }

    /** A view at rest: no offset, full size, opaque. */
    public static void rest(View v) {
        v.setAlpha(1f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    /** One view shown or hidden: a fade with a small scale and rise on the sheet curve (240 / 180 ms); GONE at the end of a hide. */
    public static void fade(@Nullable View v, boolean shown) {
        if (v == null) return;
        v.animate().cancel();
        if (!enabled(v)) {
            v.setVisibility(shown ? View.VISIBLE : View.GONE);
            rest(v);
            return;
        }
        if (shown) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setScaleX(.96f);
                v.setScaleY(.96f);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(0).setDuration(240).setInterpolator(SHEET)
                    .withEndAction(null).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setStartDelay(0).setDuration(180).setInterpolator(EASE_OUT)
                    .withEndAction(() -> {
                        v.setVisibility(View.GONE);
                        rest(v);
                    }).start();
        }
    }

}
