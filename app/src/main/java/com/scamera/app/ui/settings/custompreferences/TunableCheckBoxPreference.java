package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.color.MaterialColors;
import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.settings.PreferenceValue;
import com.scamera.app.util.Lang;

/**
 * The switch of a 0..1 {@code @Tunable}. A value equal to the default is not stored (the key is removed), so a later change
 * of the annotation's default reaches every user who never chose; a chosen value is stored as int 0/1 and the title turns
 * green while one is stored. The long press holds the description and the reset (P592).
 */
public class TunableCheckBoxPreference extends SwitchPreferenceCompat {
    private static final int CHOSEN_COLOUR = 0xFF4CAF50;
    private int fallback;
    private TextView title;
    /** False while the row is bound or initialised: setChecked then must not store anything. */
    private boolean byUser;

    public TunableCheckBoxPreference(Context context) {
        super(context);
        card();
    }

    public TunableCheckBoxPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        card();
    }

    public TunableCheckBoxPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        card();
    }

    private void card() {
        setLayoutResource(R.layout.preference_card);
        setIconSpaceReserved(true);
    }

    /** The default, 0 or 1 (also given to the switch as a boolean). */
    public void setDefaultValue(int value) {
        fallback = value;
        super.setDefaultValue(value != 0);
    }

    public int defaultInt() {
        return fallback;
    }

    private SharedPreferences prefs() {
        return getPreferenceManager().getSharedPreferences();
    }

    private boolean chosen() {
        SharedPreferences p = prefs();
        return p != null && p.contains(getKey());
    }

    /** The stored value (any stored form), else the default. */
    private boolean storedOn(boolean otherwise) {
        return PreferenceNumber.bool(PreferenceValue.get(getSharedPreferences(), getKey()), otherwise);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        byUser = false;
        super.onBindViewHolder(holder);
        title = (TextView) holder.findViewById(android.R.id.title);
        setChecked(storedOn(fallback != 0));
        paintTitle();
        byUser = true; // P592: the long press opens the row's description (SettingsFragment's adapter)
    }

    /** Nothing is stored at first: the default stays the annotation's until the user chooses. */
    @Override
    protected void onSetInitialValue(Object given) {
        boolean on;
        if (chosen()) on = storedOn(fallback != 0);
        else if (given instanceof Boolean) on = (Boolean) given;
        else if (given instanceof Integer) on = (Integer) given != 0;
        else on = fallback != 0;
        setChecked(on);
    }

    @Override
    protected boolean persistBoolean(boolean on) {
        if (byUser) {
            SharedPreferences p = prefs();
            int v = on ? 1 : 0;
            if (p != null && v != fallback) p.edit().putInt(getKey(), v).apply();
            else if (p != null && p.contains(getKey())) p.edit().remove(getKey()).apply(); // back to the default: not chosen
        }
        paintTitle();
        return true;
    }

    @Override
    protected boolean getPersistedBoolean(boolean otherwise) {
        return storedOn(otherwise);
    }

    /** Green while a chosen value is stored (also one equal to a later default), the text colour otherwise. */
    private void paintTitle() {
        if (title == null) return;
        title.setTextColor(chosen() ? CHOSEN_COLOUR
                : MaterialColors.getColor(getContext(), android.R.attr.textColorPrimary, 0xFFFFFF));
    }

    /** Forgets the chosen value: the row shows the default again. */
    public void resetToDefault() {
        boolean on = fallback != 0;
        if (!isEnabled() || !callChangeListener(on)) return;
        boolean wasUser = byUser;
        byUser = false;
        SharedPreferences p = prefs();
        if (p != null) p.edit().remove(getKey()).apply();
        setChecked(on);
        paintTitle();
        byUser = wasUser;
        ScameraApp.showToast(Lang.t(getContext(), "По умолчанию: " + (on ? "вкл." : "выкл."),
                "Reset to default: " + (on ? "on" : "off")));
    }
}
