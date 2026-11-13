package com.scamera.app.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P640: the camera process's side of the vivo SDK lease's safety net.
 * <ul>
 * <li>{@link VivoSdkRawSession.Watch}: while the lease is held the guard process (LeaseGuardService) is bound and holds a
 * Binder of this process - it gives the values back if this process dies (scam_dev vivo_lease_guard 0: no guard).</li>
 * <li>{@link #sweep}: off the caller's thread, what is still set and held by nobody goes back - at the app's start and a
 * moment after a camera closed.</li>
 * </ul>
 * Only on a phone of the lease (VivoNativeFullRes.phone); never throws into the camera.
 */
public final class LeaseGuard implements VivoSdkRawSession.Watch, ServiceConnection {
    private static final String TAG = "SCAM_LEASE";
    private static final LeaseGuard INSTANCE = new LeaseGuard();
    private static final ScheduledExecutorService WORK = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "SCAMERA-lease");
        t.setDaemon(true);
        return t;
    });
    private static volatile Context app;

    private final Binder token = new Binder();
    @Nullable private Messenger guard;
    private boolean bound, held;
    private int sdk, vcf;

    private LeaseGuard() { }

    /** The lease thread: give-backs that must not wait on the camera's callbacks or block the screen. */
    public static Executor executor() {
        return WORK;
    }

    /** {@code task} on the lease thread after {@code delayMs} (never throws; a task's own failure is logged). */
    public static void later(Runnable task, long delayMs) {
        try {
            WORK.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException | LinkageError e) {
                    Log.w(TAG, "lease task: " + e);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // the executor refused: the next close
        }
    }

    /** At the app's start (the main process): the guard is armed for later leases and a lease nobody holds goes back. */
    public static void install(Context context) {
        try {
            if (VivoNativeFullRes.phone() == null) return;
            app = context.getApplicationContext();
            VivoSdkRawSession.watch(INSTANCE);
            sweep("app start", 0);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "lease guard not installed: " + e);
        }
    }

    /** {@link VivoSdkRawSession#sweep} on the lease thread after {@code delayMs}; a result other than "none" / "held" is logged. */
    public static void sweep(String why, long delayMs) {
        final Context context = app;
        if (context == null) return;
        try {
            WORK.schedule(() -> {
                try {
                    final String result = VivoSdkRawSession.sweep(context);
                    if (!"none".equals(result) && !"held".equals(result))
                        Log.w(TAG, "vivo SDK lease left set (" + why + "): " + result);
                } catch (RuntimeException | LinkageError e) {
                    Log.w(TAG, "lease sweep (" + why + "): " + e);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // the executor refused: the next sweep
        }
    }

    @Override public synchronized void taken(int sdk, int vcf) {
        final Context context = app;
        if (context == null || !PreferenceKeys.scamDevSwitch("vivo_lease_guard", true)) return;
        this.sdk = sdk;
        this.vcf = vcf;
        held = true;
        try {
            if (guard != null) {
                send(LeaseGuardService.HOLD);
            } else if (!bound) {
                bound = context.bindService(new Intent(context, LeaseGuardService.class), this, Context.BIND_AUTO_CREATE);
                if (!bound) Log.w(TAG, "lease guard: the guard process could not be bound (the journal still covers a death)");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "lease guard: " + e);
        }
    }

    @Override public synchronized void back() {
        held = false;
        if (guard != null) send(LeaseGuardService.RELEASE);
    }

    @Override public synchronized void onServiceConnected(ComponentName name, IBinder service) {
        guard = new Messenger(service);
        if (held) send(LeaseGuardService.HOLD);
    }

    @Override public synchronized void onServiceDisconnected(ComponentName name) {
        guard = null; // the guard process died: BIND_AUTO_CREATE brings it back and onServiceConnected sends the hold again
    }

    private void send(int what) {
        final Messenger to = guard;
        if (to == null) return;
        try {
            final Message m = Message.obtain(null, what);
            if (what == LeaseGuardService.HOLD) {
                final Bundle data = new Bundle();
                data.putBinder(LeaseGuardService.TOKEN, token);
                data.putInt(LeaseGuardService.SDK, sdk);
                data.putInt(LeaseGuardService.VCF, vcf);
                m.setData(data);
            }
            to.send(m);
        } catch (android.os.RemoteException | RuntimeException e) {
            Log.w(TAG, "lease guard: " + e);
        }
    }
}
