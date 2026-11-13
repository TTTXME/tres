package com.scamera.app.util.log;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;

/** A debugging aid: the activities' lifecycle in the log, registered only in a {@link ScameraApp#DEBUG} build. */
public class ActivityLifecycleMonitor implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "ActivityMonitor";

    private static void trace(Activity who, String step) {
        if (ScameraApp.DEBUG) Log.d(TAG, who.getLocalClassName() + ": " + step);
    }

    @Override
    public void onActivityCreated(@NonNull Activity a, @Nullable Bundle saved) {
        trace(a, saved == null ? "created" : "created again from " + saved);
    }

    @Override
    public void onActivityStarted(@NonNull Activity a) {
        trace(a, "started");
    }

    @Override
    public void onActivityResumed(@NonNull Activity a) {
        trace(a, "resumed");
    }

    @Override
    public void onActivityPaused(@NonNull Activity a) {
        trace(a, "paused");
    }

    @Override
    public void onActivityStopped(@NonNull Activity a) {
        trace(a, "stopped");
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle state) {
        trace(a, "state saved");
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity a) {
        trace(a, "destroyed");
    }
}
