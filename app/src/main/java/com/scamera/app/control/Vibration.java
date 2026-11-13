package com.scamera.app.control;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.scamera.app.util.Log;

/** The app's haptic pulses: a tick (seek bars), a click (the shade's tiles) and the soft pulse of the level (P245). */
public class Vibration {
    /** P245: a soft, light pulse's length (ms) and amplitude (1..255) where the vibrator controls its amplitude. */
    // P298 (owner, 2026-10-20: «у уровня горизонта всё ещё не работает вибро отклик»): 18 ms at 70 was not felt on the X200
    static final int SOFT_MS = 30, SOFT_AMPLITUDE = 120;

    private final Vibrator vibrator;
    private final VibrationEffect tick, click, soft;

    public Vibration(Context context) {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        // the system's tick from Android 10 on; before it a short full-strength one-shot (9 ms tick, 13 ms click)
        tick = systemTickOr(9);
        click = systemTickOr(13);
        soft = softEffect(vibrator != null && vibrator.hasAmplitudeControl());
    }

    private static VibrationEffect systemTickOr(int ms) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) : VibrationEffect.createOneShot(ms, 255);
    }

    /**
     * P245 (owner, 2026-10-18: «Для уровня горизонта когда он стоит ровно добавь мягкую легкую вибрацию»):
     * at a low amplitude where the vibrator has amplitude control, the system's light tick otherwise (a View's CLOCK_TICK, used
     * before, is silent on the OPPO / vivo phones).
     */
    static VibrationEffect softEffect(boolean amplitudeControl) {
        if (amplitudeControl) return VibrationEffect.createOneShot(SOFT_MS, SOFT_AMPLITUDE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        return VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    /**
     * The soft pulse (P245). P298: its switch is the setting that asks for it (the horizon tile), not the system's touch
     * feedback - vivo ships «touch vibration» off, and a USAGE_TOUCH pulse follows that intensity too; so no touch usage.
     */
    public void Soft() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            vibrator.vibrate(soft);
        } catch (RuntimeException e) {
            Log.w("Vibration", "soft pulse: " + e);
        }
    }

    public void Tick() {
        vibrator.vibrate(tick);
    }

    public void Click() {
        vibrator.vibrate(click);
    }
}
