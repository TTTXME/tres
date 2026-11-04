package com.particlesdevs.photoncamera.gallery.video;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.lang.ref.WeakReference;

/**
 * P499: «Фоновое воспроизведение» - while the player is off the screen with it on, this foreground service (media playback) keeps
 * the process alive and shows the video's name with «Пауза / Смотреть» and «Закрыть». The playback itself stays in the player.
 */
public final class VideoPlaybackService extends Service {
    private static final String TAG = "VideoPlaybackService";
    private static final String CHANNEL = "video_background";
    private static final int ID = 7310;
    private static final String TOGGLE = "toggle", CLOSE = "close";

    public interface Controller {
        boolean playing();

        String title();

        void toggle();

        void close();
    }

    private static WeakReference<Controller> controller = new WeakReference<>(null);
    private static boolean running;

    static void start(Context c, Controller ctl) {
        controller = new WeakReference<>(ctl);
        try {
            Intent i = new Intent(c, VideoPlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            running = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "start refused: " + e); // the sound may still go on until the system stops the app
        }
    }

    static void stop(Context c) {
        if (!running) return;
        running = false;
        c.stopService(new Intent(c, VideoPlaybackService.class));
    }

    /** The play state changed: the notification follows. */
    static void refresh(Context c) {
        if (!running) return;
        Controller ctl = controller.get();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && ctl != null) nm.notify(ID, notification(c, ctl));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Controller ctl = controller.get();
        String action = intent != null ? intent.getAction() : null;
        if (ctl != null && TOGGLE.equals(action)) ctl.toggle();
        if (CLOSE.equals(action) || ctl == null) {
            if (ctl != null) ctl.close();
            stopForeground(true);
            stopSelf();
            running = false;
            return START_NOT_STICKY;
        }
        try {
            Notification n = notification(this, ctl);
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground refused: " + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private static Notification notification(Context c, Controller ctl) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Lang.t("Видео в фоне", "Video in the background"),
                    NotificationManager.IMPORTANCE_LOW));
        int imm = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, VideoPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), imm);
        PendingIntent toggle = PendingIntent.getService(c, 1, new Intent(c, VideoPlaybackService.class).setAction(TOGGLE), imm);
        PendingIntent close = PendingIntent.getService(c, 2, new Intent(c, VideoPlaybackService.class).setAction(CLOSE), imm);
        boolean playing = ctl.playing();
        return new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ui_ic_headphones)
                .setContentTitle(ctl.title())
                .setContentText(playing ? Lang.t("Воспроизведение в фоне", "Playing in the background") : Lang.t("Пауза", "Paused"))
                .setContentIntent(open)
                .addAction(playing ? R.drawable.ui_ic_pause : R.drawable.ui_ic_play, playing ? Lang.t("Пауза", "Pause") : Lang.t("Смотреть", "Play"), toggle)
                .addAction(R.drawable.ui_ic_close, Lang.t("Закрыть", "Close"), close)
                .setOngoing(playing)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
