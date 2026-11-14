package com.scamera.app.ui.camera.viewmodel;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.ViewModel;

import com.scamera.app.ui.camera.model.TimerFrameCountModel;

/**
 * The series' counters (frames left, the time left as m:s) from the capture thread's frame events, set on the main thread.
 * The time shows for a series longer than 4 s.
 */
public class TimerFrameCountViewModel extends ViewModel {
    private final TimerFrameCountModel counters = new TimerFrameCountModel();
    private final Handler main = new Handler(Looper.getMainLooper());

    public TimerFrameCountModel getTimerFrameCountModel() {
        return counters;
    }

    public void setFrameTimeCnt(FrameCntTime at) {
        final int last = at.maxframe;
        final int frame = at.frame;
        final double perFrame = at.time;
        main.post(() -> show(frame, last, perFrame));
    }

    public void clearFrameTimeCnt() {
        main.post(() -> {
            counters.setFrameCount("");
            counters.setTimerCount("");
        });
    }

    private void show(int frame, int last, double perFrame) {
        counters.setFrameCount(String.valueOf(Math.abs(last - frame)));
        if (perFrame * last <= 4.0 && last != 0) return;
        final double left = Math.abs(perFrame * last - perFrame * frame);
        counters.setTimerCount((int) (left / 60) + ":" + ((int) left) % 60);
    }

    /** One frame of a series: its number (1-based), the series' frames and each frame's time in seconds. */
    public static class FrameCntTime {
        final int frame;
        final int maxframe;
        final double time;

        public FrameCntTime(int number, int of, double seconds) {
            frame = number;
            maxframe = of;
            time = seconds;
        }

        /** P234: the frame number (1-based) of the shot, the ring's frames counted first. */
        public int frame() {
            return frame;
        }
    }
}
