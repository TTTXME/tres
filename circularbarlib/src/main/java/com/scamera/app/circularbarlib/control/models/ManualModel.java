package com.scamera.app.circularbarlib.control.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Range;

import com.scamera.app.circularbarlib.R;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stops of one manual parameter (ISO, shutter, EV, focus, white balance) and the chosen one; choosing a stop writes it
 * to {@link ManualParamModel}, tells the panel its label and, once the module's value was restored, remembers it under
 * {@code pref_manual_<model class>} (so the class names are part of the stored keys).
 *
 * @param <T> the parameter's value type in the camera's range
 */
public abstract class ManualModel<T extends Comparable<? super T>> {
    protected final ManualParamModel params;
    protected final CameraCharacteristics cameraCharacteristics;
    protected final Range<T> range;
    protected final Context context;
    private final List<KnobItemInfo> stops = new ArrayList<>();
    private final ValueChangedEvent onLabel;
    private final Vibrator haptics;
    private final VibrationEffect tickEffect;
    protected KnobItemInfo chosen, autoStop;
    /** Set once the module's stored value was put back: from then on every choice is stored. */
    private boolean remembering;

    protected ManualModel(Context host, CameraCharacteristics characteristics, Range<T> span, ManualParamModel model,
                          ValueChangedEvent onLabel, Vibrator vibrator) {
        this.context = host;
        this.cameraCharacteristics = characteristics;
        this.range = span;
        this.params = model;
        this.onLabel = onLabel;
        haptics = vibrator;
        tickEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                : VibrationEffect.createOneShot(9, 255);
        addStops();
    }

    /** Adds the parameter's stops (the auto stop first) and sets {@link #chosen}; called by the constructor. */
    protected abstract void addStops();

    /** The model's part of a choice: the value into {@link #params} and {@link #chosen}. */
    public abstract void onSelectedKnobItemChanged(KnobItemInfo stop);

    /** The auto stop, labelled {@code label} or «Авто». */
    protected KnobItemInfo makeAutoStop(double value, String label) {
        autoStop = new KnobItemInfo(label != null ? label : context.getString(R.string.manual_mode_auto), 0, value);
        return autoStop;
    }

    public List<KnobItemInfo> stopList() {
        return stops;
    }

    public KnobItemInfo chosenStop() {
        return chosen;
    }

    /** Tells the panel the auto stop's label. */
    public void showAutoLabel() {
        label(autoStop == null ? context.getString(R.string.manual_mode_auto) : autoStop.label);
    }

    private void label(String text) {
        if (onLabel != null) onLabel.onValueChanged(text);
    }

    private String storeKey() {
        return "pref_manual_" + getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private SharedPreferences store() {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /** Chooses {@code next} (the scale's drag, the console); a tick of the vibrator for each step. */
    public void choose(KnobItemInfo next) {
        choose(null, next);
    }

    /** As {@link #choose(KnobItemInfo)}; nothing changes when {@code shown} (the stop the caller showed) is {@code next}. */
    public void choose(KnobItemInfo shown, KnobItemInfo next) {
        if (haptics != null) haptics.vibrate(tickEffect);
        if (shown == next) return;
        onSelectedKnobItemChanged(next);
        label(next.label);
        if (remembering) store().edit().putString(storeKey(), Double.toString(next.cameraValue)).apply();
    }

    /** Puts back the module's stored stop (the nearest to the stored value), then remembers every later choice. */
    public void restoreModuleValue() {
        SharedPreferences p = store();
        String key = storeKey();
        if (p.contains(key)) {
            try {
                double wanted = Double.parseDouble(p.getString(key, "0"));
                KnobItemInfo nearest = null;
                for (KnobItemInfo s : stops)
                    if (nearest == null || Math.abs(s.cameraValue - wanted) < Math.abs(nearest.cameraValue - wanted)) nearest = s;
                if (nearest != null) choose(nearest);
            } catch (NumberFormatException unreadable) {
                // a value another version wrote: the stop stays
            }
        }
        remembering = true;
    }

    /** Back to the auto stop. */
    public void resetModel() {
        if (autoStop != null) choose(autoStop);
    }

    /** Told the label of each chosen stop. */
    public interface ValueChangedEvent {
        void onValueChanged(String value);
    }
}
