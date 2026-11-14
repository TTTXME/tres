package com.scamera.app.ui.camera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient;
import com.scamera.app.ui.settings.DialogStyle;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;
import com.scamera.app.util.WorkerSpawn;

/**
 * P470 (owner, 2026-11-03: «давай сделаем компиляцию всего что надо при первом запуске. На экране по центру будет появляться
 * уведомление с прогресс баром»): after an install, an update or a new system build (a new GPU driver), a dialog in the middle of
 * the screen compiles the merge's GPU programs (the worker's prewarm of the plain, Quad and Tetra routes, the texture maps among
 * them) and the kept post-pipeline programs into their caches, so the first shot no longer compiles them (~2 s on the OPPO
 * Find X7 Ultra). The stamp is written when it finished; an interrupted run starts again at the next launch.
 * P683: the post's programs include the APK's default recipes (every program the code names: GLProg.warmUp with defaults), then a
 * short GPU calibration (GpuProfile: ns per pixel of a few programs, kept under this stamp) gives the post's band sizing and the RAW
 * viewfinder's tone size their starting values; each phase's ms is logged.
 */
public final class FirstRunCompile {
    private static final String PREFS = "scam_first_run", KEY = "compiled_for";
    private static final int[] BLOCKS = {1, 2, 4};
    private static volatile boolean running;

    private FirstRunCompile() {}

    public static String stamp(Context c) {
        long installed = 0;
        try {
            installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        return installed + "|" + Build.FINGERPRINT;
    }

    /** Shows the dialog and compiles when this install / system build has not been compiled for yet. */
    public static void maybeRun(Activity activity) {
        final Context app = activity.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String stamp = stamp(app);
        if (running || stamp.equals(prefs.getString(KEY, null))) return;
        running = true;
        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        final TextView text = new TextView(activity);
        text.setGravity(Gravity.CENTER);
        text.setText(Lang.t("Компиляция шейдеров для быстрой обработки снимков. Это нужно один раз после установки.",
                "Compiling shaders for fast photo processing. This is needed once after an install."));
        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(text);
        layout.addView(bar);
        final AlertDialog dialog = DialogStyle.builder(activity)
                .setTitle(Lang.t("Подготовка камеры", "Preparing the camera"))
                .setView(layout)
                .setCancelable(false)
                .create();
        dialog.show();
        final int phases = BLOCKS.length + 2;
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final Thread worker = new Thread(() -> {
            boolean done = false;
            // P683: each phase's ms in the log line
            final StringBuilder times = new StringBuilder();
            try {
                if (WorkerSpawn.available(app)) {
                    for (int i = 0; i < BLOCKS.length; i++) {
                        final int phase = i;
                        final long p0 = android.os.SystemClock.elapsedRealtime();
                        ScamNeuralClient.prewarmHybridGpuBlocking(app, BLOCKS[i], (d, n) -> show(activity, bar, phase, d, n, phases));
                        times.append("merge block ").append(BLOCKS[i]).append(' ').append(android.os.SystemClock.elapsedRealtime() - p0).append(" ms, ");
                    }
                }
                // P683: every post program the code names (the APK's default recipes) besides the kept ones, so the first shot
                // finds all of them in the disk cache
                long p0 = android.os.SystemClock.elapsedRealtime();
                GLProg.warmUp(() -> true, (d, n) -> show(activity, bar, phases - 2, d, n, phases), true);
                times.append("post programs ").append(android.os.SystemClock.elapsedRealtime() - p0).append(" ms, ");
                // P683: the GPU's ns per pixel of a few programs - the starting band sizes of the post (GpuPace) and the RAW
                // viewfinder's starting tone size (RawPreviewLoad); the film data stays decoded at its first use (FilmSpectral,
                // libscameraFilm): a decode here would be lost with this process, a decoded copy on disk is the size P683 saved
                p0 = android.os.SystemClock.elapsedRealtime();
                show(activity, bar, phases - 1, 0, 1, phases);
                com.scamera.app.processing.opengl.GpuProfile.calibrate(app, stamp, () -> true);
                show(activity, bar, phases - 1, 1, 1, phases);
                times.append("GPU calibration ").append(android.os.SystemClock.elapsedRealtime() - p0).append(" ms");
                done = true;
            } catch (RuntimeException | LinkageError e) {
                Log.w("SCAM_HDR", "first-run compile: " + e);
            } finally {
                if (done) prefs.edit().putString(KEY, stamp).apply();
                running = false;
                Log.i("SCAM_HDR", "first-run compile " + (done ? "done" : "stopped") + " in "
                        + (android.os.SystemClock.elapsedRealtime() - t0) + " ms (" + times + ")");
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed() && dialog.isShowing()) dialog.dismiss();
                });
            }
        }, "first-run-compile");
        worker.start();
    }

    private static void show(Activity activity, ProgressBar bar, int phase, int done, int total, int phases) {
        final int value = Math.round(1000f * (phase + (total > 0 ? Math.min(1f, done / (float) total) : 1f)) / phases);
        activity.runOnUiThread(() -> bar.setProgress(Math.max(bar.getProgress(), value)));
    }
}
