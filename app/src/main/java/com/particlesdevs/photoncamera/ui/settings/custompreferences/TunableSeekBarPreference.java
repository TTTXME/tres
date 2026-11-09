package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import com.particlesdevs.photoncamera.ui.controls.IosMotion;
import com.particlesdevs.photoncamera.ui.controls.IosSlider;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;

/** The bar snaps; numeric entry preserves the exact value. Binding is strictly read-only. */
public class TunableSeekBarPreference extends Preference {
    private float mMin = 0f, mMax = 100f, mStepPerUnit = 1f, mDefaultValue = 0f;
    private boolean isFloat;
    private float autoValue = Float.NaN;
    private String autoLabel;
    /** P574: the settings' slider, in progress steps 0 .. maxProgress(). */
    private IosSlider seekBar;
    private boolean instant;
    private TextView seekBarValue;

    public TunableSeekBarPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_seekbar);
        setIconSpaceReserved(true);
    }
    public void setMinValue(float value) { mMin = value; }
    public void setMaxValue(float value) { mMax = value; }
    public void setIsFloat(boolean value) { isFloat = value; }
    public void setStepPerUnit(float value) { mStepPerUnit = value > 0 ? value : 1f; }
    public void setDefaultValue(float value) { mDefaultValue = value; super.setDefaultValue(value); }
    /** P149: the value that means «the camera's own» (the RAW level rows: -1) shows {@code label} instead of the number. */
    public void setAutoValue(float value, String label) { autoValue = value; autoLabel = label; }
    /** The text of the value pill. */
    public String valueText(float value) {
        return autoLabel != null && value == autoValue ? autoLabel : PreferenceNumber.format(value, isFloat);
    }
    private int maxProgress() { return Math.max(1, Math.round((mMax - mMin) * mStepPerUnit)); }

    public float getFloatValue() {
        SharedPreferences prefs = getSharedPreferences();
        return PreferenceNumber.bounded(prefs == null ? null : com.particlesdevs.photoncamera.settings.PreferenceValue.get(prefs, getKey()),
                mDefaultValue, mMin, mMax);
    }
    @Override protected void onSetInitialValue(Object defaultValue) { refresh(); }
    @Override public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        seekBar = (IosSlider) holder.findViewById(R.id.seekbar);
        seekBarValue = (TextView) holder.findViewById(R.id.seekbar_value);
        instant = seekBar.getTag(R.id.settings_bound_preference) != this;
        seekBar.setTag(R.id.settings_bound_preference, this);
        seekBar.setListener(null);
        seekBar.setRange(0, maxProgress(), 1);
        seekBar.setDefaultValue(PreferenceNumber.progress(mDefaultValue, mMin, mStepPerUnit, maxProgress()));
        seekBar.setEnabled(isEnabled());
        refresh();
        instant = false;
        final TextView min = (TextView) holder.findViewById(R.id.seekbar_min), max = (TextView) holder.findViewById(R.id.seekbar_max);
        if (min != null) min.setText(valueText(mMin));
        if (max != null) max.setText(valueText(mMax));
        seekBar.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider s, float value, boolean fromUser) {
                if (!fromUser || !isEnabled()) return;
                if (ScameraApp.getVibration() != null) ScameraApp.getVibration().Tick();
                save((float) (Math.round(value) / (double) mStepPerUnit + mMin), false);
            }

            // the value pill lifts while the knob is held and pops when it is let go
            @Override public void onTracking(IosSlider s, boolean tracking) {
                if (seekBarValue == null) return;
                if (tracking) IosMotion.lift(seekBarValue, true, seekBarValue.getCurrentTextColor(), seekBarValue.getCurrentTextColor());
                else IosMotion.pop(seekBarValue);
            }
        });
        // P357: a double tap on the bar, the value or the row puts the camera's own value back (the stored one goes)
        final Runnable reset = this::resetToDefault;
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(seekBar, reset);
        // the exact reset is DoubleTapReset's alone: the slider's own double tap would write the step-snapped default first
        seekBar.setOnResetListener(() -> { });
        com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(holder.itemView, this::showPreciseValueDialog, reset);
        if (seekBarValue != null) com.particlesdevs.photoncamera.ui.controls.DoubleTapReset.attach(seekBarValue, this::showPreciseValueDialog, reset);
    }
    private void refresh() {
        float value = getFloatValue();
        // P574: not a row recycled for another preference since
        if (seekBar == null || seekBar.getTag(R.id.settings_bound_preference) != this) return;
        int progress = PreferenceNumber.progress(value, mMin, mStepPerUnit, maxProgress());
        if (instant) seekBar.setValueNow(progress); else seekBar.setValue(progress);
        if (seekBarValue != null) {
            seekBarValue.setText(valueText(value));
            // P168: off the default the pill turns to the theme's opposite (was green text)
            com.particlesdevs.photoncamera.ui.settings.SettingsStyle.paintPill(seekBarValue, isChanged(),
                    com.particlesdevs.photoncamera.ui.settings.SettingsStyle.accent(getContext()));
        }
    }
    private void save(float requested, boolean reset) {
        save(requested, reset, false);
    }
    /** {@code force}: also while the row is disabled (a page reset). */
    private void save(float requested, boolean reset, boolean force) {
        if (!force && !isEnabled() || !Float.isFinite(requested)) return;
        float value = PreferenceNumber.bounded(requested, mDefaultValue, mMin, mMax);
        if (!isFloat) value = Math.round(value);
        Object candidate = isFloat ? (Object) Float.valueOf(value) : Integer.valueOf(Math.round(value));
        if (!callChangeListener(candidate)) { refresh(); return; }
        SharedPreferences prefs = getSharedPreferences();
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (reset) editor.remove(getKey());
        else if (isFloat) editor.putFloat(getKey(), value);
        else editor.putInt(getKey(), Math.round(value));
        editor.apply();
        refresh();
    }

    /** P357: the camera's own value back (the stored one goes); true when one was stored. */
    public boolean resetToDefault() {
        return resetToDefault(false);
    }

    /** {@code force}: also while another setting disables the row (the page reset puts every row of its page back). */
    public boolean resetToDefault(boolean force) {
        if (!isChanged() || !force && !isEnabled()) return false;
        save(mDefaultValue, true, force);
        return true;
    }

    public float minimum(){return mMin;}
    public float maximum(){return mMax;}
    public boolean decimal(){return isFloat;}
    public float defaultNumber(){return mDefaultValue;}
    /** P168: a value of its own is stored (the tunable differs from the camera's default). */
    public boolean isChanged() {
        SharedPreferences prefs = getSharedPreferences();
        return prefs != null && prefs.contains(getKey());
    }
    public float stepPerUnit(){return mStepPerUnit;}
    private void showPreciseValueDialog() {
        if (!isEnabled()) return;
        com.particlesdevs.photoncamera.ui.controls.PrecisionEditor.show(getContext(),getKey(),String.valueOf(getTitle()),
            mMin,mMax,getFloatValue(),mDefaultValue,isFloat,1.0/mStepPerUnit,-1,value->save(value,false));   // P574: the slider's own step
    }
}
