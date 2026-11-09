package com.particlesdevs.photoncamera.ui.controls;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.settings.SettingsMotion;

/**
 * P574: the value label's motion next to an {@link IosSlider} (the concept's «отпрыгивающая анимация»): it grows a little and takes
 * the accent while the knob is held ({@link #lift}), and pops once when the finger leaves ({@link #pop}). Animators only (the
 * system's animator scale and «remove animations» apply).
 */
public final class IosMotion {
    private IosMotion() {}

    private static final SettingsMotion.Spring LIFT = new SettingsMotion.Spring(0.35f, 0.62f);

    /** Held ({@code on}): scaled to 1.16 and in {@code accent}; let go: back to 1 and {@code rest}. The pivot is the label's end. */
    public static void lift(@Nullable TextView label, boolean on, int accent, int rest) {
        if (label == null) return;
        cancel(label);
        label.setPivotX(label.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 0 : label.getWidth());
        label.setPivotY(label.getHeight() / 2f);
        final float to = on ? 1.16f : 1f;
        final int colourTo = on ? accent : rest;
        if (!SettingsMotion.enabled(label)) {
            label.setScaleX(to);
            label.setScaleY(to);
            label.setTextColor(colourTo);
            return;
        }
        final float from = label.getScaleX();
        final int colourFrom = label.getCurrentTextColor();
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(LIFT.durationMs);
        a.setInterpolator(LIFT);
        a.addUpdateListener(x -> {
            final float t = (float) x.getAnimatedValue();
            final float s = from + (to - from) * t;
            label.setScaleX(s);
            label.setScaleY(s);
            label.setTextColor(SettingsMotion.mix(colourFrom, colourTo, Math.max(0f, Math.min(1f, t))));
        });
        label.setTag(R.id.ios_motion, a);
        a.start();
    }

    /** One pop: 1 -> 1.22 -> 1 over half a second on a spring (a value set by the user's release, a tap or a double tap). */
    public static void pop(@Nullable View v) {
        if (v == null) return;
        cancel(v);
        if (!SettingsMotion.enabled(v)) {
            // no motion: back to rest at once (a lift without motion left it at its held size)
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }
        v.setPivotX(v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 0 : v.getWidth());
        v.setPivotY(v.getHeight() / 2f);
        final float base = v.getScaleX();
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(500);
        a.addUpdateListener(x -> {
            final float t = (float) x.getAnimatedValue();
            // up fast, down on a damped spring
            final float bump = (float) (Math.sin(Math.PI * Math.min(1f, t / 0.35f) / 2) * Math.exp(-5.0 * Math.max(0f, t - 0.35f)));
            final float s = 1f + (base - 1f) * (1f - t) + 0.22f * bump;
            v.setScaleX(s);
            v.setScaleY(s);
        });
        v.setTag(R.id.ios_motion, a);
        a.start();
    }

    private static void cancel(View v) {
        final Object running = v.getTag(R.id.ios_motion);
        if (running instanceof Animator) ((Animator) running).cancel();
        v.setTag(R.id.ios_motion, null);
    }
}
