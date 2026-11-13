package com.scamera.app.util.log;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;

/**
 * A debugging aid: the fragments' main lifecycle steps in the log. Registered only in a {@link ScameraApp#DEBUG} build; the
 * messages are built only then.
 */
public class FragmentLifeCycleMonitor extends FragmentManager.FragmentLifecycleCallbacks {
    private static final String TAG = "FragmentMonitor";

    private static void trace(Fragment who, String step, @Nullable Object detail) {
        if (!ScameraApp.DEBUG) return;
        Log.d(TAG, who.getClass().getSimpleName() + ": " + step + (detail != null ? " (" + detail + ")" : ""));
    }

    @Override
    public void onFragmentCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @Nullable Bundle saved) {
        trace(f, "created", saved);
    }

    @Override
    public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull View v, @Nullable Bundle saved) {
        trace(f, "view created", saved);
    }

    @Override
    public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
        trace(f, "resumed", null);
    }

    @Override
    public void onFragmentPaused(@NonNull FragmentManager fm, @NonNull Fragment f) {
        trace(f, "paused", null);
    }

    @Override
    public void onFragmentSaveInstanceState(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull Bundle state) {
        trace(f, "state saved", state);
    }

    @Override
    public void onFragmentViewDestroyed(@NonNull FragmentManager fm, @NonNull Fragment f) {
        trace(f, "view destroyed", null);
    }

    @Override
    public void onFragmentDestroyed(@NonNull FragmentManager fm, @NonNull Fragment f) {
        trace(f, "destroyed", null);
    }
}
