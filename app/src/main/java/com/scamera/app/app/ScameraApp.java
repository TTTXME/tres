package com.scamera.app.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.os.HandlerCompat;

import com.scamera.app.api.Settings;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.control.Gravity;
import com.scamera.app.control.Gyro;
import com.scamera.app.control.Vibration;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.MigrationManager;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.SplashActivity;
import com.scamera.app.util.AssetLoader;
import com.scamera.app.util.Log;
import com.scamera.app.util.ScameraCrashReporter;
import com.scamera.app.util.SimpleStorageHelper;
import com.scamera.app.util.log.ActivityLifecycleMonitor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScameraApp extends Application {
    /** Verbose lifecycle logging (the activity / fragment monitors); off in every build. */
    public static final boolean DEBUG = false;
    private static ScameraApp sApp;
    /** One low-priority thread for the app's own background jobs (the launcher icon's state). */
    private final ExecutorService background = Executors.newSingleThreadExecutor(ScameraApp::backgroundThread);
    private final Handler main = HandlerCompat.createAsync(Looper.getMainLooper());
    private volatile Settings settings;
    private volatile Gravity gravity;
    private volatile Gyro gyro;
    private volatile Vibration vibration;
    private CaptureController captureController;
    /** Set in onCreate, read by the background job and any thread after it. */
    private volatile SettingsManager settingsManager;
    private volatile AssetLoader assetLoader;

    @Nullable
    public static ScameraApp getInstance(Context context) {
        final Application app = context instanceof Activity ? ((Activity) context).getApplication() : null;
        return app instanceof ScameraApp ? (ScameraApp) app : null;
    }

    @Nullable
    public static Context getAppContext() {
        return sApp;
    }

    public static Handler getMainHandler() {
        return sApp.main;
    }

    /*
     * P645 (owner, 2026-11-12: the gallery slow from the launcher too): the camera's own objects are made at their first use, not
     * at every process start - the gallery's process (its launcher icon, a VIEW from another app) never uses them, and the sensor
     * service (Gravity, Gyro) is looked up only then. The camera gets the same objects as before, at its first call.
     */
    public static Settings getSettings() {
        final ScameraApp a = sApp;
        Settings s = a.settings;
        if (s == null) synchronized (a) {
            if ((s = a.settings) == null) a.settings = s = new Settings();
        }
        return s;
    }

    public static Gravity getGravity() {
        final ScameraApp a = sApp;
        Gravity g = a.gravity;
        if (g == null) synchronized (a) {
            if ((g = a.gravity) == null) a.gravity = g = new Gravity(a.sensors());
        }
        return g;
    }

    public static Gyro getGyro() {
        final ScameraApp a = sApp;
        Gyro g = a.gyro;
        if (g == null) synchronized (a) {
            if ((g = a.gyro) == null) a.gyro = g = new Gyro(a.sensors());
        }
        return g;
    }

    public static Vibration getVibration() {
        final ScameraApp a = sApp;
        if (a == null) return null;
        Vibration v = a.vibration;
        if (v == null) synchronized (a) {
            if ((v = a.vibration) == null) a.vibration = v = new Vibration(a);
        }
        return v;
    }

    private SensorManager sensors() {
        return (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }


    public static CaptureController getCaptureController() {
        return sApp.captureController;
    }

    public static void setCaptureController(CaptureController controller) {
        sApp.captureController = controller;
    }

    public static AssetLoader getAssetLoader() {
        final ScameraApp a = sApp;
        AssetLoader l = a.assetLoader;
        if (l == null) synchronized (a) {
            if ((l = a.assetLoader) == null) a.assetLoader = l = new AssetLoader(a);
        }
        return l;
    }

    /** P156: a restart is on its way (an imported config or a reset): nothing may move the module profiles meanwhile. */
    public static volatile boolean restarting;

    /** The process's end of a restart (tests replace it). */
    static Runnable exit = () -> System.exit(0);
    /** How often a restart waiting for a shot looks again, ms. */
    static final long RESTART_POLL_MS = 500;
    /**
     * The longest a restart waits for a shot, ms: the config or reset is already written, and a busy flag that never clears
     * must not keep the old settings (and {@link #restarting}) for the rest of the process.
     */
    static final long RESTART_MAX_WAIT_MS = 5 * 60_000L;

    /**
     * The app restarted after {@code delayMs} (an imported config, a reset). Audit 2026-10-27 (lab-app-2): never while a shot is
     * captured, queued or processed (ProcessingKeepAlive.busy): the exit killed its merge and the photo was lost. It waits for
     * the shot and says so once; after {@link #RESTART_MAX_WAIT_MS} it restarts anyway.
     */
    public static void restartWithDelay(Context context, long delayMs) {
        restarting = true;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final Handler main = sApp != null && sApp.main != null ? sApp.main : new Handler(Looper.getMainLooper());
        final boolean[] told = {false};
        final long until = android.os.SystemClock.uptimeMillis() + Math.max(0, delayMs) + RESTART_MAX_WAIT_MS;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean busy;
                try {
                    busy = com.scamera.app.processing.ProcessingKeepAlive.busy.getAsBoolean();
                } catch (RuntimeException e) {
                    busy = false;
                }
                if (busy && android.os.SystemClock.uptimeMillis() >= until) {
                    Log.w("ScameraApp", "restart: a shot still processed after " + RESTART_MAX_WAIT_MS / 1000 + " s, restarting anyway");
                    busy = false;
                }
                if (busy) {
                    if (!told[0]) {
                        told[0] = true;
                        Toast.makeText(app, com.scamera.app.util.Lang.t(app, "Перезапуск после сохранения снимка",
                                "Restarting once the photo is saved"), Toast.LENGTH_LONG).show();
                    }
                    main.postDelayed(this, RESTART_POLL_MS);
                    return;
                }
                restartApp(app);
            }
        }, delayMs);
    }

    public static void restartApp(Context context) {
        context.startActivity(new Intent(context, SplashActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        exit.run();
    }

    public static void showToast(String text) {
        getMainHandler().post(() -> Toast.makeText(sApp, text, Toast.LENGTH_LONG).show());
    }

    public static void showToast(@StringRes int text) {
        getMainHandler().post(() -> Toast.makeText(sApp, text, Toast.LENGTH_LONG).show());
    }

    public static Resources getResourcesStatic() {
        return sApp.getResources();
    }

    public static String getStringStatic(@StringRes int id) {
        return sApp.getString(id);
    }

    public static PackageInfo getPackageInfo() throws PackageManager.NameNotFoundException {
        return sApp.getPackageManager().getPackageInfo(sApp.getPackageName(), 0);
    }

    public static String getVersion() {
        try {
            final PackageInfo own = getPackageInfo();
            return own.versionName + '(' + own.versionCode + ')';
        } catch (PackageManager.NameNotFoundException unknown) {
            Log.w("ScameraApp", "no package info: " + unknown);
            return "";
        }
    }

    public SettingsManager getSettingsManager() {
        return settingsManager;
    }
    
    public static SettingsManager getSettingsManagerStatic() {
        return sApp != null ? sApp.settingsManager : null;
    }

    /**
     * P582: the app context at the concept's scale and font scale too (UiFixedScale), so sizes and text set up with it (a
     * toast's own view, dimensions read through it) agree with the activities' instead of the system's display size.
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(com.scamera.app.ui.style.UiFixedScale.wrap(base));
    }

    @Override
    public void onCreate() {
        // First thing of all: a crash during initModules() leaves no logcat for the user,
        // so make sure it always lands in a file we can ask for. P214b: in the :scam process too - the reporter only
        // sets the default handler and, on a crash, appends to Download/SCAMERA/SCAMERA-crash.log (no camera, no GPU,
        // no settings), and its report names the process.
        ScameraCrashReporter.install(this);
        // The firmware test has its own process. Do not open cameras, initialize
        // GPU pipelines or start background modules inside that process.
        // P640: neither does the vivo SDK lease's guard process (LeaseGuardService): nothing of the app runs there.
        if (android.os.Build.VERSION.SDK_INT >= 28 &&
                (android.app.Application.getProcessName().endsWith(":scam")
                        || android.app.Application.getProcessName().endsWith(":leaseguard"))) {
            super.onCreate();
            return;
        }
        if (DEBUG) registerActivityLifecycleCallbacks(new ActivityLifecycleMonitor()); // no callbacks at all otherwise
        sApp = this;
        // P214b: the build in logcat at every process start (the daily file gets it when the camera screen attaches it)
        android.util.Log.i("ScameraBuild", com.scamera.app.util.BuildIdentity.line());
        Log.d("ScameraApp", "Initializing modules");
        createStart = android.os.SystemClock.uptimeMillis();
        initModules();
        com.scamera.app.util.ScameraDebugLog.init(this);
        super.onCreate();
        step("debug log");
        // Why the previous process ended (a native crash or a kill leaves no line of ours): into the log once
        com.scamera.app.util.ExitReasons.logPrevious(this);
        // P640: a vivo SDK lease nobody holds goes back (a process of ours that died with it), the guard is armed for the next
        com.scamera.app.capture.LeaseGuard.install(this);
        step("lease guard");
        createEnd = android.os.SystemClock.uptimeMillis();
        android.util.Log.i("ScameraApp", "app onCreate " + (createEnd - createStart) + " ms (" + steps + ")");
    }

    // ---------------------------------------------------------------- P645: the app start's own time, for the gallery's log

    /** The app's onCreate (uptime ms; 0: not run in this process). */
    private static volatile long createStart, createEnd, lastStep;
    private static final StringBuilder steps = new StringBuilder();

    /** One step of the app's onCreate done: its time since the step before into {@link #steps}. */
    private static void step(String what) {
        final long now = android.os.SystemClock.uptimeMillis();
        synchronized (steps) {
            if (steps.length() > 0) steps.append(", ");
            steps.append(what).append(' ').append(now - (lastStep > 0 ? lastStep : createStart));
        }
        lastStep = now;
    }

    /** P645: when this process's app onCreate ran and what took its time (GalleryOpenTrace's «process: cold start» line). */
    public static final class StartTimes {
        public final long createStart, createEnd;
        public final String steps;

        StartTimes(long createStart, long createEnd, String steps) {
            this.createStart = createStart;
            this.createEnd = createEnd;
            this.steps = steps;
        }
    }

    public static StartTimes startTimes() {
        synchronized (steps) {
            return new StartTimes(createStart, createEnd, steps.toString());
        }
    }

    private void initModules() {

        SimpleStorageHelper.init(this);

        // P645: Gravity, Gyro, Vibration, Settings and AssetLoader at their first use (their getters)
        step("services");

        settingsManager = new SettingsManager(this);
        step("settings + migrations");

        MigrationManager.migrate(settingsManager);

        PreferenceKeys.initialise(settingsManager);
        step("preference keys");

        // Initialize gallery icon visibility based on preference. P645: off the start (a package manager call or two; the
        // launcher's state for a setting changed in the settings screen, which applies it itself too)
        background.execute(this::syncGalleryIcon);
    }
    
    /** The gallery's launcher icon shown or hidden as the setting says (the launcher keeps its own state across starts). */
    private void syncGalleryIcon() {
        final boolean hidden = settingsManager.getBoolean(SettingsManager.SCOPE_GLOBAL, PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON);
        try {
            com.scamera.app.gallery.GalleryLauncherIcon.apply(this, hidden);
        } catch (Exception failed) {
            Log.e("ScameraApp", "gallery icon not updated: " + failed.getMessage());
        }
    }

    private static Thread backgroundThread(Runnable job) {
        final Thread thread = new Thread(job, "scamera-app");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        background.shutdownNow();
        captureController = null;
        sApp = null;
    }
}
