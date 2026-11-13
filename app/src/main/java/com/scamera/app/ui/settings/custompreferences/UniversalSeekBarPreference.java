package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.control.Vibration;
import com.scamera.app.settings.DeviceDefaults;
import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.settings.PreferenceValue;
import com.scamera.app.ui.controls.DoubleTapReset;
import com.scamera.app.ui.controls.IosMotion;
import com.scamera.app.ui.controls.IosSlider;
import com.scamera.app.ui.controls.PrecisionEditor;
import com.scamera.app.ui.settings.SettingsStyle;

/**
 * A numeric settings row on a step grid: the slider (IosSlider, P574) moves in steps of {@code 1/stepPerUnit} from
 * {@code minValue} to {@code maxValue}; a tap on the value or the row opens exact entry, a double tap puts the default back.
 *
 * <p>The value is stored as text. Positions are computed in double precision and rounded (a float difference such as
 * {@code (-0.15f - -0.2f) * 20} is 0.99999994 and truncated to the wrong step). Showing the row never rewrites the stored
 * value, so an exact value between two steps survives; only the user's drag or entry writes, and an out-of-range or
 * unreadable value is clamped and written once.
 */
public class UniversalSeekBarPreference extends Preference {
    /** What {@link #storedText} returns for a key that has no value yet. */
    private static final String ABSENT = "\u0000__np__";
    private final Vibration haptics;
    private final float low, high;
    private final boolean decimal, showsValue;
    private final float perUnit;
    /** The last step index (the slider runs 0 .. steps). */
    private final int steps;
    /** Decimals of a value on the grid (PreferenceNumber.gridDecimals): 4 for steps of 0.0001, at least 2. */
    private final int gridDecimals;
    private TextView pill;
    private IosSlider slider;
    /** The next refresh moves the knob at once (a row bound to this preference afresh), not on its glide. */
    private boolean jump;
    /** The XML default, or this phone's factory value (DeviceDefaults). */
    private String defaultText;

    public UniversalSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.UniversalSeekBarPreference, defStyleAttr, defStyleRes);
        try {
            high = a.getFloat(R.styleable.UniversalSeekBarPreference_maxValue, 100f);
            low = a.getFloat(R.styleable.UniversalSeekBarPreference_minValue, 0f);
            decimal = a.getBoolean(R.styleable.UniversalSeekBarPreference_isFloat, false);
            showsValue = a.getBoolean(R.styleable.UniversalSeekBarPreference_showSeekBarValue, true);
            float grid = a.getFloat(R.styleable.UniversalSeekBarPreference_stepPerUnit, 1f);
            perUnit = !decimal && grid > 1 ? 1f : grid; // whole-number rows step by at least 1
        } finally {
            a.recycle();
        }
        haptics = ScameraApp.getVibration();
        setLayoutResource(R.layout.preference_seekbar); // P6b: the card slider row, also where SettingsStyle does not run
        // Rounded: (1.0f - -0.2f) * 20 is 24.00000095 in float32, and 0.3f * 10 would truncate to 2.
        steps = Math.max(1, (int) Math.round(((double) high - low) * perUnit));
        gridDecimals = PreferenceNumber.gridDecimals(perUnit, low);
    }

    public UniversalSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public UniversalSeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UniversalSeekBarPreference(Context context) {
        this(context, null);
    }

    // ---- numbers --------------------------------------------------------------------------------------------------

    private float inRange(float v) {
        return Math.max(low, Math.min(high, v));
    }

    private int onGrid(int step) {
        return Math.max(0, Math.min(steps, step));
    }

    private float number(String text, float otherwise) {
        return text == null ? otherwise : (float) PreferenceNumber.read(text, otherwise);
    }

    /** The nearest step of a value (double precision, rounded: float subtraction lands just below most multiples). */
    private int stepOf(float v) {
        return onGrid((int) Math.round(((double) v - low) * perUnit));
    }

    /** A step's value as the bar writes it: on the grid's decimals, or whole. */
    private String gridValue(int step) {
        double v = step / (double) perUnit + low;
        return decimal ? PreferenceNumber.gridText(v, gridDecimals) : String.valueOf(Math.round(v));
    }

    /** An exact value as typed (trailing zeros dropped). */
    private String exact(float v) {
        return PreferenceNumber.format(v, decimal);
    }

    private String storedText(String otherwise) {
        SharedPreferences p = getSharedPreferences();
        Object v = p == null ? null : PreferenceValue.get(p, getKey());
        return v == null ? otherwise : v.toString();
    }

    // ---- binding --------------------------------------------------------------------------------------------------

    private boolean boundHere() {
        return slider != null && slider.getTag(R.id.settings_bound_preference) == this;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        slider = (IosSlider) holder.findViewById(R.id.seekbar);
        pill = (TextView) holder.findViewById(R.id.seekbar_value);
        // P574: a recycled row shows this preference's value at once; the same row glides to a new one (a reset, an entry)
        jump = !boundHere();
        slider.setTag(R.id.settings_bound_preference, this);
        slider.setListener(null);
        slider.setRange(0, steps, 1);
        slider.setDefaultValue(stepOf(inRange(defaultNumber())));
        slider.setEnabled(isEnabled());
        refresh();
        jump = false;
        // the ends as the pill writes a value (P584: a decimal comma on a Russian screen)
        endLabel(holder, R.id.seekbar_min, low);
        endLabel(holder, R.id.seekbar_max, high);
        slider.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider s, float value, boolean fromUser) {
                if (!fromUser || !isEnabled()) return;
                if (haptics != null) haptics.Tick();
                dragTo(Math.round(value));
            }

            // the pill lifts while the knob is held and pops when it is let go (the concept's bounce)
            @Override public void onTracking(IosSlider s, boolean held) {
                if (pill == null) return;
                if (held) IosMotion.lift(pill, true, pill.getCurrentTextColor(), pill.getCurrentTextColor());
                else IosMotion.pop(pill);
            }
        });
        // P357: a double tap on the bar, the value or the row puts the default back; a single tap on the value or row is entry.
        final Runnable reset = () -> {
            if (isEnabled() && callChangeListener(exact(inRange(defaultNumber())))) resetToDefault();
        };
        DoubleTapReset.attach(slider, reset);
        slider.setOnResetListener(() -> { }); // the slider's own double tap would store the step-snapped default first
        if (pill != null) DoubleTapReset.attach(pill, this::openEntry, reset);
        DoubleTapReset.attach(holder.itemView, this::openEntry, reset);
    }

    private void endLabel(PreferenceViewHolder holder, int id, float v) {
        TextView end = (TextView) holder.findViewById(id);
        if (end != null) end.setText(SettingsStyle.number(getContext(), decimal ? PreferenceNumber.gridText(v, gridDecimals) : exact(v)));
    }

    /** First run only: the default is stored, so backups carry the key. Later a pure refresh. */
    @Override
    protected void onSetInitialValue(Object given) {
        Object start = given == null ? defaultText : given;
        if (!ABSENT.equals(storedText(ABSENT))) {
            refresh();
            return;
        }
        String text = start == null ? Float.toString(low) : start.toString();
        float v = inRange(number(text, low));
        // The XML default exactly (1.414 on a 0.01 grid stays 1.414), else as the bar writes it.
        String seed = decimal ? PreferenceNumber.gridText(v, Math.max(gridDecimals, PreferenceNumber.decimalsOf(text))) : exact(v);
        show(seed, stepOf(v));
        persistString(seed);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        String xml = a.getString(index);
        // this phone's factory value is the default of the double tap, the «changed» mark and the page reset (the full reset
        // applies it again); androidx reads the key before the default
        Object factory = getKey() == null ? null : DeviceDefaults.factory(getContext()).get(getKey());
        defaultText = factory != null ? String.valueOf(factory) : xml;
        return xml;
    }

    /** Bar and pill from the stored text, written back only when it was out of range or unreadable. */
    private void refresh() {
        String stored = storedText(defaultText == null ? "0" : defaultText);
        float raw = number(stored, number(defaultText, low)), v = inRange(raw);
        boolean readable = Double.isFinite(PreferenceNumber.read(stored, Double.NaN));
        if (v == raw && readable) {
            show(stored, stepOf(v)); // exactly what is stored, an off-grid value included
            return;
        }
        String fixed = exact(v);
        show(fixed, stepOf(v));
        persistString(fixed);
    }

    private void show(String text, int step) {
        moveKnob(step);
        if (pill == null || (slider != null && !boundHere())) return; // P574: the row now shows another preference
        if (!showsValue) {
            pill.setVisibility(View.GONE);
            return;
        }
        pill.setVisibility(View.VISIBLE);
        pill.setText(SettingsStyle.number(getContext(), text));
        // P168: off the default the pill turns to the theme's opposite, at once while the bar moves
        SettingsStyle.paintPill(pill, differs(text), SettingsStyle.accent(getContext()));
    }

    private void moveKnob(int step) {
        if (!boundHere()) return;
        if (jump) slider.setValueNow(onGrid(step));
        else slider.setValue(onGrid(step));
    }

    // ---- writes ---------------------------------------------------------------------------------------------------

    /** The user dragged: the value snaps to the grid. */
    private void dragTo(int step) {
        if (!isEnabled()) return;
        int s = onGrid(step);
        commit(gridValue(s), s);
    }

    /** Exact entry: the value is kept as typed, the bar shows the nearest step. */
    private void enter(float value) {
        if (!isEnabled() || !Float.isFinite(value)) return;
        float v = inRange(value);
        commit(exact(v), stepOf(v));
    }

    private void commit(String text, int step) {
        if (!callChangeListener(text)) {
            refresh();
            return;
        }
        show(text, step);
        persistString(text);
    }

    /** P162: a value set from elsewhere (a dragged edge of the crop picture): stored exactly and shown, as typed. */
    public void setNumber(float value) {
        enter(value);
        notifyChanged();
    }

    /** Back to the default, through the preference's own store (module and profile scoping kept). */
    public void resetToDefault() {
        persistString(exact(inRange(defaultNumber())));
        refresh();
        notifyChanged();
    }

    private void openEntry() {
        if (!isEnabled()) return;
        // P574: the slider's own step and decimals
        PrecisionEditor.show(getContext(), getKey(), String.valueOf(getTitle()), low, high,
                inRange(number(getValue(), defaultNumber())), defaultNumber(), decimal, 1.0 / perUnit,
                decimal ? gridDecimals : 0, this::enter);
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    /** P168: the stored value is off the default (a float within 1e-5 of it counts as the default). */
    public boolean isChanged() {
        return differs(getValue());
    }

    private boolean differs(String text) {
        float d = defaultNumber(), v = number(text, d);
        return Math.abs(v - d) > Math.max(1e-6f, Math.abs(d) * 1e-5f);
    }

    public String getValue() {
        return storedText(defaultText);
    }

    public float minimum() { return low; }

    public float maximum() { return high; }

    public boolean decimal() { return decimal; }

    public float defaultNumber() { return number(defaultText, low); }

    /** Steps per unit of the bar (1 for integer sliders); the quick-settings shade uses the same grid. */
    public float stepPerUnit() { return perUnit; }
}
