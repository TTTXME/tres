package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;

import androidx.preference.DialogPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.google.android.material.snackbar.Snackbar;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.BackupRestoreUtil;
import com.particlesdevs.photoncamera.util.Lang;

public class ResetPreferences extends DialogPreference {
    public ResetPreferences(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setPersistent(false);
        setDialogTitle(android.R.string.dialog_alert_title);
        setDialogMessage(R.string.reset_preferences_warning);
        setPositiveButtonText(R.string.yes);
    }

    /**
     * P574 (owner, 2026-11-09, the concept): the full reset asks in the settings' centred confirmation (DialogStyle.confirm) -
     * «Отмена» / red «Сбросить» - with the warning of this preference; confirmed, it resets as {@link Dialog} did.
     */
    public static androidx.appcompat.app.AlertDialog confirm(android.content.Context c, Preference p) {
        return com.particlesdevs.photoncamera.ui.settings.DialogStyle.confirm(c,
                Lang.t(c, "Сбросить все настройки?", "Reset all settings?"),
                p instanceof DialogPreference && ((DialogPreference) p).getDialogMessage() != null
                        ? ((DialogPreference) p).getDialogMessage() : c.getString(R.string.reset_preferences_warning),
                Lang.t(c, "Сбросить", "Reset"), () -> resetAll(c));
    }

    /** Every setting back to its default, then the app restarts (the reset of {@link Dialog}). */
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

    public static class Dialog extends PreferenceDialogFragmentCompat {
        public static Dialog newInstance(Preference preference) {
            final Dialog fragment = new Dialog();
            final Bundle bundle = new Bundle(1);
            bundle.putString(ARG_KEY, preference.getKey());
            fragment.setArguments(bundle);
            return fragment;
        }

        @Override
        public void onDialogClosed(boolean positiveResult) {
            if (positiveResult) {
                if (getContext() != null && getActivity() != null) {
                    String status = getString(R.string.app_will_restart);
                    if (!BackupRestoreUtil.resetPreferences(getContext()))
                        status = Lang.t(getContext(), "Не удалось сбросить настройки", "Reset failed");
                    Snackbar.make(getActivity().findViewById(android.R.id.content), status, Snackbar.LENGTH_SHORT).show();
                    ScameraApp.restartWithDelay(getContext(), 1000);
                }
            }
        }
    }
}