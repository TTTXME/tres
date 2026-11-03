package com.particlesdevs.photoncamera.ui.camera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import com.particlesdevs.photoncamera.ui.settings.DialogStyle;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P475 (owner, 2026-11-03: «Возможно нам нужно чтобы при первом запуске приложение просило это разрешение?»): «Доступ ко всем
 * файлам» (MANAGE_EXTERNAL_STORAGE). On Android 11+ the app may only read and write the files of Download/SCAMERA it made
 * itself; a scam_dev.txt from a PC, adb or the package before com.scamera.app (the OPPO probe: AccessDeniedException) could
 * not be changed from the developer page. Asked once per install at the first launch; the developer page asks again on a
 * refused write.
 */
public final class AllFilesAccess {
    private static final String PREFS = "scam_first_run", KEY = "all_files_asked";

    private AllFilesAccess() {}

    /** Whether the app may use every file of the shared storage (always below Android 11: the storage permissions apply). */
    public static boolean granted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    /** The system page of this app's «Доступ ко всем файлам»; false when the phone has none. */
    public static boolean openSettings(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            context.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())));
            return true;
        } catch (RuntimeException e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return true;
            } catch (RuntimeException e2) {
                Log.w("AllFilesAccess", "no all-files settings page: " + e2);
                return false;
            }
        }
    }

    /** The first launch of this install without the access: the request; true when it was shown (the caller waits). */
    public static boolean maybeAsk(Activity activity) {
        if (granted()) return false;
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String stamp = FirstRunCompile.stamp(activity.getApplicationContext());
        if (stamp.equals(prefs.getString(KEY, null))) return false;
        prefs.edit().putString(KEY, stamp).apply();
        DialogStyle.builder(activity)
                .setTitle(Lang.t("Доступ к файлам", "File access"))
                .setMessage(Lang.t("SCAMERA хранит настройки разработчика, логи и серии в Download/SCAMERA. Чтобы менять файлы, "
                                + "положенные туда с ПК или прежней версией, разрешите «Управление всеми файлами».",
                        "SCAMERA keeps its developer settings, logs and bursts in Download/SCAMERA. To change files put there from a PC "
                                + "or by an earlier version, allow “All files access”."))
                .setPositiveButton(Lang.t("Разрешить", "Allow"), (d, w) -> openSettings(activity))
                .setNegativeButton(Lang.t("Не сейчас", "Not now"), null)
                .show();
        return true;
    }
}
