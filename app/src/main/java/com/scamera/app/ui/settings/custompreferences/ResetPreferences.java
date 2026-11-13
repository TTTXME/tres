package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.DialogPreference;
import androidx.preference.Preference;

import com.google.android.material.snackbar.Snackbar;
import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.BackupRestoreUtil;
import com.scamera.app.util.Lang;

/** «Сбросить все настройки»: a row that stores nothing itself; its dialog texts are what {@link #confirm} shows. */
public class ResetPreferences extends DialogPreference {
    public ResetPreferences(Context context, AttributeSet attrs) {
        super(context, attrs);
        setDialogTitle(context.getString(android.R.string.dialog_alert_title));
        setDialogMessage(context.getString(R.string.reset_preferences_warning));
        setPositiveButtonText(context.getString(R.string.yes));
        setPersistent(false);
    }

    /**
     * P574 (owner, 2026-11-09, the concept): the full reset asks in the settings' centred confirmation (DialogStyle.confirm) -
     * «Отмена» / red «Сбросить» - with the warning of this preference; confirmed, every setting goes back to its default.
     */
    public static androidx.appcompat.app.AlertDialog confirm(android.content.Context c, Preference p) {
        return com.scamera.app.ui.settings.DialogStyle.confirm(c,
                Lang.t(c, "Сбросить все настройки?", "Reset all settings?"),
                p instanceof DialogPreference && ((DialogPreference) p).getDialogMessage() != null
                        ? ((DialogPreference) p).getDialogMessage() : c.getString(R.string.reset_preferences_warning),
                Lang.t(c, "Сбросить", "Reset"), () -> resetAll(c));
    }

    /** Every setting back to its default, then the app restarts. */
    static void resetAll(android.content.Context c) {
        android.app.Activity activity = activity(c);
        String status = c.getString(R.string.app_will_restart);
        if (!BackupRestoreUtil.resetPreferences(c)) status = Lang.t(c, "Не удалось сбросить настройки", "Reset failed");
        if (activity != null) Snackbar.make(activity.findViewById(android.R.id.content), status, Snackbar.LENGTH_SHORT).show();
        ScameraApp.restartWithDelay(c, 1000);
    }

    @androidx.annotation.Nullable
    private static android.app.Activity activity(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

}