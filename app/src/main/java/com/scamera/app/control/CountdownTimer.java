package com.scamera.app.control;

import android.os.CountDownTimer;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** The self-timer: each second its number grows and fades in the view, then the callback fires the shot. */
public class CountdownTimer extends CountDownTimer {
    private final TextView label;
    private final TimerCallback done;
    private final long tickMs;

    public CountdownTimer(TextView tv, long millisInFuture, long countDownInterval, TimerCallback callback) {
        super(millisInFuture, countDownInterval);
        label = tv;
        done = callback;
        tickMs = countDownInterval;
    }

    @Override
    public void onTick(long millisUntilFinished) {
        final long left = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) + 1;
        label.post(() -> {
            label.setText(String.format(Locale.ROOT, "%d", left));
            label.setAlpha(1);
            label.setScaleX(3);
            label.setScaleY(3);
            label.animate().scaleXBy(2).scaleYBy(2).alpha(0).setDuration(tickMs - 50).start();
        });
    }

    @Override
    public void onFinish() {
        label.post(() -> {
            label.setText("");
            label.setAlpha(1);
            label.setScaleX(1);
            label.setScaleY(1);
        });
        done.onFinished();
    }

    public interface TimerCallback {
        void onFinished();
    }
}
