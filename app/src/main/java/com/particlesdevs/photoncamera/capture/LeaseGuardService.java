package com.particlesdevs.photoncamera.capture;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * P640 (owner, 2026-11-12, X200 Ultra: «после какого-то времени использования нашей камеры в сток камере становится черный
 * видоискатель, помогает только ребут»): the guard of the vivo SDK lease, in a process of its own (:leaseguard).
 * <p>
 * The lease ({@link VivoSdkRawSession}: VivoCameraManager 40 = 1, 33 = 2) is the phone's state, not the app's: a camera process
 * that dies holding it (a native crash, a kill - no code of its own runs) leaves it set for every camera app until the phone
 * restarts. The camera process hands this service a Binder of its own and the values to give back when it takes the lease; the
 * Binder's death is the process's death, and the values go back from here. A normal give-back tells the service to stand down.
 * <p>
 * Nothing of the app runs in this process (the application class returns at once for it): reflection on the phone's manager and a log line.
 */
public final class LeaseGuardService extends Service {
    static final int HOLD = 1, RELEASE = 2;
    static final String TOKEN = "token", SDK = "sdk", VCF = "vcf";
    private static final String TAG = "SCAM_LEASE_GUARD";
    /** The dead process's camera is disconnected by the camera service first. */
    private static final long AFTER_DEATH_MS = 250, RETRY_MS = 700;
    private static final int TRIES = 5;

    private final Handler handler = new Handler(Looper.getMainLooper(), this::handle);
    private final Messenger messenger = new Messenger(handler);
    @Nullable private IBinder token;
    @Nullable private IBinder.DeathRecipient recipient;
    private int sdk, vcf, tries;
    private final Runnable restore = this::restore;

    @Override public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private boolean handle(Message message) {
        if (message.what == HOLD) {
            final Bundle data = message.getData();
            final IBinder holder = data == null ? null : data.getBinder(TOKEN);
            if (holder == null) return true;
            stand();
            handler.removeCallbacks(restore); // a new holder: a restore still pending for a dead one would take its lease
            sdk = data.getInt(SDK);
            vcf = data.getInt(VCF);
            token = holder;
            recipient = () -> handler.post(() -> died(holder));
            try {
                holder.linkToDeath(recipient, 0);
                Log.i(TAG, "watching the lease's holder (gives back SDK RAW " + sdk + ", VCF " + vcf + ")");
            } catch (RemoteException dead) {
                died(holder);
            }
        } else if (message.what == RELEASE) {
            stand();
            Log.i(TAG, "the lease is back: standing down");
        }
        return true;
    }

    /** Stops watching the current holder (its lease is back, or a new holder takes its place). */
    private void stand() {
        final IBinder t = token;
        final IBinder.DeathRecipient r = recipient;
        token = null;
        recipient = null;
        if (t != null && r != null) try { t.unlinkToDeath(r, 0); } catch (RuntimeException ignored) { }
    }

    private void died(IBinder holder) {
        if (token != holder) return;
        token = null;
        recipient = null;
        tries = 0;
        Log.w(TAG, "the lease's holder died: giving its values back");
        handler.postDelayed(restore, AFTER_DEATH_MS);
    }

    private void restore() {
        if (token != null) return; // a new holder took the lease meanwhile
        final String result = VivoSdkRawSession.restoreAfterDeath(sdk, vcf);
        Log.w(TAG, "lease of a dead camera process: " + result + " (SDK RAW " + sdk + ", VCF " + vcf + ")");
        if (result.startsWith("failed") && ++tries < TRIES) handler.postDelayed(restore, RETRY_MS);
    }
}
