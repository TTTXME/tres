/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.preference.SwitchPreferenceCompat;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.settings.PreferenceValue;
import com.scamera.app.settings.SettingsManager;

/**
 * A switch read and written through the app's {@link SettingsManager} (main settings), whatever form the value was stored
 * in ("0"/"1" text or a Boolean); it writes a Boolean, and only when the value really changes (showing a page is read-only).
 */
public class ManagedSwitchPreference extends SwitchPreferenceCompat {
    private boolean xmlDefault;

    public ManagedSwitchPreference(Context context) {
        super(context);
    }

    public ManagedSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ManagedSwitchPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    private SettingsManager manager() {
        ScameraApp app = ScameraApp.getInstance(getContext());
        return app == null ? null : app.getSettingsManager();
    }

    @Override
    public boolean getPersistedBoolean(boolean otherwise) {
        SettingsManager m = manager();
        return m == null ? otherwise : m.getBoolean(SettingsManager.SCOPE_GLOBAL, getKey(), otherwise);
    }

    @Override
    public boolean persistBoolean(boolean on) {
        SettingsManager m = manager();
        if (m == null) return false;
        SharedPreferences main = m.getDefaultPreferences();
        Object now = PreferenceValue.get(main, getKey());
        boolean same = now != null && PreferenceNumber.bool(now, !on) == on;
        if (!same) main.edit().putBoolean(getKey(), on).apply();
        return true;
    }

    /** The XML default (isChecked() reflects the stored value, which this class reads from the main settings). */
    public boolean defaultChecked() {
        return xmlDefault;
    }

    @Override
    protected void onSetInitialValue(Object given) {
        setChecked(getPersistedBoolean(given == null ? xmlDefault : (Boolean) given));
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        xmlDefault = a.getBoolean(index, false);
        return xmlDefault;
    }
}
