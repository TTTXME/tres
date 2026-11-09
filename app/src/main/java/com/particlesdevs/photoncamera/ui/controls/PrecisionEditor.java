package com.particlesdevs.photoncamera.ui.controls;

import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;
import com.particlesdevs.photoncamera.ui.settings.SettingsMotion;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The exact-value editor of a settings slider. P137: a sheet in the camera's style instead of the stock dialog, the setting
 * pinned to the shade from it. P574 (owner, 2026-11-09, the approved number editor): a floating sheet 8dp from the screen's
 * edges (34dp corners, a grabber), «Отмена» / the title / «Готово» on top; the value big in the middle (tap: type it in place),
 * in the accent and a little larger while the slider is held, with a pop when it changes; «Сбросить до N» under it (quiet
 * «По умолчанию» at the default); the slider over the whole range between its ends; a card with «Точно» (a − | + stepper, one
 * step of the setting, repeating while held) and «Плитка в шторке» (the shade pin, where the shade can show the setting).
 * «Готово» applies; «Отмена», a tap outside, Back and a swipe down close without applying.
 */
public final class PrecisionEditor extends LinearLayout {
    public interface Save {void value(float value);}

    /** The grey of the stepper capsule (rgba(118, 118, 128, .24)) and the sheet's edge. */
    private static final int STEPPER = 0x3D767680, EDGE = 0x1FFFFFFF;

    private final double min, max, step, fallback;
    private final boolean decimal;
    /** Decimals the value is shown with (at least; a typed value keeps its own). */
    private final int decimals;
    /** Slider positions: 0..steps, position i = min + i * step (the last one max). */
    private final int steps;
    private final int accent;
    private double value;
    private boolean held;

    private final TextView number, reset, minus, plus;
    private final EditText field;
    private final IosSlider slider;
    private final LinearLayout card;

    public PrecisionEditor(Context c, double min, double max, double value, double fallback, boolean decimal) {
        this(c, min, max, value, fallback, decimal, 0, -1);
    }

    /**
     * {@code step}: the setting's own step (the slider's grid and the stepper's ±; 0: 0.1 for decimals, 1 otherwise);
     * {@code decimals}: the decimals the setting shows (-1: those of the step and the range).
     */
    public PrecisionEditor(Context c, double min, double max, double value, double fallback, boolean decimal, double step, int decimals) {
        super(c);
        setOrientation(VERTICAL);
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.decimal = decimal;
        double grid = step > 0 && Double.isFinite(step) ? step : decimal ? .1 : 1;
        this.step = decimal ? grid : Math.max(1, Math.round(grid));   // a whole-number setting moves by whole numbers
        this.decimals = !decimal ? 0 : Math.min(6, decimals >= 0 ? decimals
                : Math.max(PreferenceNumber.decimalsOf(text6(this.step)), Math.max(PreferenceNumber.decimalsOf(text6(this.min)),
                PreferenceNumber.decimalsOf(text6(this.max)))));
        this.steps = (int) Math.max(1, Math.min(1_000_000, Math.round((this.max - this.min) / this.step)));
        this.fallback = clamp(fallback);
        this.value = clamp(Double.isFinite(value) ? value : this.fallback);
        if (!decimal) this.value = Math.round(this.value);
        accent = SettingsStyle.accent(c);   // the phone's theme colour, as every control of the settings

        // ── the value: 64sp bold, tabular figures; a tap types it in place ──
        number = SettingsStyle.text(c, format(this.value), 64, SettingsStyle.ROW_TITLE);
        number.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(c, com.particlesdevs.photoncamera.ui.style.Fonts.BOLD));
        number.setFontFeatureSettings("tnum");
        number.setGravity(Gravity.CENTER);
        number.setSingleLine(true);
        number.setTag("precision_value");
        number.setFocusable(true);
        number.setClickable(true);
        number.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        final String typeHint = Lang.t(c, "Ввести число", "Type the number");
        number.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, typeHint));
            }
        });
        number.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) fitNumber(); });
        LayoutParams np = new LayoutParams(-1, dp(76));
        np.topMargin = dp(18);
        np.setMarginStart(dp(20));
        np.setMarginEnd(dp(20));
        addView(number, np);

        field = new EditText(c);
        field.setSingleLine(true);
        field.setTextSize(34);
        field.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(c, com.particlesdevs.photoncamera.ui.style.Fonts.BOLD));
        field.setGravity(Gravity.CENTER);
        field.setTextColor(SettingsStyle.ROW_TITLE);
        field.setBackground(SettingsStyle.shape(c, SettingsStyle.ROW, 0, 12));
        field.setPadding(dp(16), dp(6), dp(16), dp(6));
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTag("precision_field");
        field.setVisibility(GONE);
        tintCursor(field, accent);
        addView(field, np);

        reset = SettingsStyle.text(c, "", 14, accent);
        reset.setGravity(Gravity.CENTER);
        reset.setMinHeight(dp(30));
        reset.setPadding(dp(12), 0, dp(12), 0);
        reset.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, SettingsStyle.shape(c, 0xFFFFFFFF, 0, 15)));
        reset.setTag("precision_reset");
        reset.setOnClickListener(v -> { if (commitField()) set(this.fallback, true); });
        LayoutParams xp = new LayoutParams(-2, -2);
        xp.gravity = Gravity.CENTER_HORIZONTAL;
        xp.topMargin = dp(2);
        xp.bottomMargin = dp(6);
        addView(reset, xp);

        // ── the slider over the whole range between its ends ──
        LinearLayout range = new LinearLayout(c);
        range.setGravity(Gravity.CENTER_VERTICAL);
        range.setPadding(dp(20), dp(8), dp(20), dp(4));
        range.setClipChildren(false);
        range.setClipToPadding(false);
        TextView lo = SettingsStyle.text(c, format(this.min), 12, SettingsStyle.RANGE);
        lo.setGravity(Gravity.END);
        lo.setMinWidth(dp(22));
        lo.setTag("precision_min");
        range.addView(lo, new LayoutParams(-2, -2));
        slider = new IosSlider(c);
        slider.setAccent(accent);
        slider.setRange(0, steps, 1);
        slider.setDefaultValue(position(this.fallback));
        slider.setValueNow(position(this.value));
        slider.setTag("precision_slider");
        slider.setContentDescription(Lang.t(c, "Значение", "Value"));
        LayoutParams sp = new LayoutParams(0, -2, 1f);
        sp.setMarginStart(dp(10));
        sp.setMarginEnd(dp(10));
        range.addView(slider, sp);
        TextView hi = SettingsStyle.text(c, format(this.max), 12, SettingsStyle.RANGE);
        hi.setMinWidth(dp(22));
        hi.setTag("precision_max");
        range.addView(hi, new LayoutParams(-2, -2));
        setClipChildren(false);
        addView(range, new LayoutParams(-1, -2));
        slider.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider s, float progress, boolean fromUser) {
                if (!fromUser) return;
                if (field.getVisibility() == VISIBLE) closeField();
                PrecisionEditor.this.value = at(Math.round(progress));
                show(false);
            }

            @Override public void onTracking(IosSlider s, boolean tracking) {
                hold(tracking);
                if (!tracking) pop();
            }
        });

        // ── the card: «Точно» with the stepper, «Плитка в шторке» (added by show() for a pinnable setting) ──
        card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setBackground(SettingsStyle.shape(c, SettingsStyle.ROW, 0, 24));
        card.setClipToOutline(true);
        LinearLayout fine = row(c, Lang.t(c, "Точно", "Fine"));
        LinearLayout stepper = new LinearLayout(c);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.setBackground(SettingsStyle.shape(c, STEPPER, 0, 17));
        stepper.setClipToOutline(true);
        minus = stepButton(c, "−", Lang.t(c, "Меньше", "Less"), -1);
        minus.setTag("precision_minus");
        plus = stepButton(c, "+", Lang.t(c, "Больше", "More"), 1);
        plus.setTag("precision_plus");
        stepper.addView(minus, new LayoutParams(dp(48), -1));
        View divider = new View(c);
        divider.setBackgroundColor(0x40FFFFFF);
        LayoutParams dv = new LayoutParams(Math.max(1, Math.round(.5f * getResources().getDisplayMetrics().density)), -1);
        dv.topMargin = dv.bottomMargin = dp(8);
        stepper.addView(divider, dv);
        stepper.addView(plus, new LayoutParams(dp(48), -1));
        fine.addView(stepper, new LayoutParams(-2, dp(34)));
        card.addView(fine, new LayoutParams(-1, -2));
        LayoutParams cp = new LayoutParams(-1, -2);
        cp.topMargin = dp(14);
        cp.setMarginStart(dp(16));
        cp.setMarginEnd(dp(16));
        addView(card, cp);

        number.setOnClickListener(v -> openField());
        field.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { commitField(); return true; }
            return false;
        });
        show(false);
    }

    // ───── values ─────

    private double clamp(double v) { return Math.max(min, Math.min(max, v)); }

    /** The slider position nearest {@code v}. */
    private int position(double v) {
        return (int) Math.max(0, Math.min(steps, Math.round((clamp(v) - min) / step)));
    }

    /** The value of slider position {@code i}: on the setting's grid, its float noise rounded away. */
    private double at(int i) {
        double v = i >= steps ? max : min + i * step;
        return decimal ? round(clamp(v), Math.max(decimals, PreferenceNumber.decimalsOf(text6(step)))) : Math.round(clamp(v));
    }

    private static double round(double v, int places) {
        return BigDecimal.valueOf(v).setScale(Math.max(0, Math.min(9, places)), RoundingMode.HALF_UP).doubleValue();
    }

    private static String text6(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** {@code v} with the setting's decimals (more when it has more of its own: a typed exact value). */
    String format(double v) {
        if (!decimal) return Long.toString(Math.round(v));
        BigDecimal b = BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        if (b.scale() < decimals) b = b.setScale(decimals, RoundingMode.HALF_UP);
        return b.toPlainString();
    }

    private boolean atDefault() {
        return Math.abs(value - fallback) <= Math.max(1e-9, Math.pow(10, -Math.max(decimals, 0) - 1));
    }

    /** One step down ({@code dir} -1) or up from the value; false at the end of the range. */
    private boolean nudge(int dir) {
        if (!commitField()) return false;
        double next = clamp(value + dir * step);
        if (decimal) next = round(next, Math.max(Math.max(decimals, PreferenceNumber.decimalsOf(text6(step))), PreferenceNumber.decimalsOf(text6(value))));
        else next = Math.round(next);
        if (next == value) return false;
        set(next, true);
        return true;
    }

    /** The value set by a tap (the stepper, the reset, a typed number): the slider glides there, the number pops. */
    private void set(double v, boolean popIt) {
        value = decimal ? clamp(v) : Math.round(clamp(v));
        slider.setValue(position(value));
        show(popIt);
    }

    /** The number, the reset line and the stepper's ends for the current value. */
    private void show(boolean popIt) {
        String shown = format(value);
        if (!TextUtils.equals(shown, number.getText())) {
            number.setText(shown);
            fitNumber();
            if (popIt) pop();
        }
        boolean def = atDefault();
        reset.setText(def ? Lang.t(getContext(), "По умолчанию", "Default")
                : Lang.t(getContext(), "Сбросить до ", "Reset to ") + format(fallback));
        reset.setTextColor(def ? 0x59FFFFFF : accent);
        reset.setEnabled(!def);
        paintStep(minus, value > min);
        paintStep(plus, value < max);
    }

    /** P574: a long number shrinks to the sheet's width (64sp at most, never cut). */
    private void fitNumber() {
        int room = number.getWidth() - number.getPaddingLeft() - number.getPaddingRight();
        float full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 64, getResources().getDisplayMetrics());
        number.getPaint().setTextSize(full);
        float wide = number.getPaint().measureText(number.getText().toString());
        float size = room > 0 && wide > room ? full * room / wide * .98f : full;
        number.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    /** The value without the field: a typed number into the value; false (and the field marked) when it is not one in the range. */
    private boolean commitField() {
        if (field.getVisibility() != VISIBLE) return true;
        try {
            double n = Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
            if (!Double.isFinite(n) || n < min || n > max) throw new NumberFormatException();
            closeField();
            set(decimal ? n : Math.round(n), true);
            return true;
        } catch (NumberFormatException ex) {
            field.setError(Lang.t(getContext(), "Введите число в указанном диапазоне", "Enter a number in the given range"));
            return false;
        }
    }

    private void openField() {
        field.setText(format(value));
        field.selectAll();
        number.setVisibility(GONE);
        field.setVisibility(VISIBLE);
        field.requestFocus();
        InputMethodManager ime = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.showSoftInput(field, 0);
    }

    private void closeField() {
        field.setError(null);
        field.setVisibility(GONE);
        number.setVisibility(VISIBLE);
        InputMethodManager ime = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    /** The value to save: decimals as shown, whole numbers rounded. */
    public float result() { return (float) (decimal ? value : Math.round(value)); }

    // ───── motion (Animators: the system's animator scale and «remove animations» apply) ─────

    /** Held: the number in the accent at 1.08; let go: white at 1. */
    private void hold(boolean on) {
        if (held == on) return;
        held = on;
        number.animate().cancel();
        Object running = number.getTag(com.particlesdevs.photoncamera.R.id.ios_motion);
        if (running instanceof android.animation.Animator) ((android.animation.Animator) running).cancel();
        number.setPivotX(number.getWidth() / 2f);
        number.setPivotY(number.getHeight() / 2f);
        int to = on ? accent : SettingsStyle.ROW_TITLE;
        float scale = on ? 1.08f : 1f;
        if (!SettingsMotion.enabled(number)) {
            number.setTextColor(to);
            number.setScaleX(scale);
            number.setScaleY(scale);
            return;
        }
        ValueAnimator colour = ValueAnimator.ofArgb(number.getCurrentTextColor(), to);
        colour.setDuration(200);
        colour.addUpdateListener(a -> number.setTextColor((int) a.getAnimatedValue()));
        colour.start();
        number.animate().scaleX(scale).scaleY(scale).setDuration(350).setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    /** One pop: 1 -> 1.12 -> 1 over 450 ms, overshooting on the way back (a value set by a tap or the slider's release). */
    private void pop() {
        if (held || !SettingsMotion.enabled(number)) return;
        number.animate().cancel();
        number.setPivotX(number.getWidth() / 2f);
        number.setPivotY(number.getHeight() / 2f);
        Keyframe k0 = Keyframe.ofFloat(0f, number.getScaleX()), k1 = Keyframe.ofFloat(.35f, 1.12f), k2 = Keyframe.ofFloat(1f, 1f);
        k2.setInterpolator(new OvershootInterpolator(2f));
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(number,
                PropertyValuesHolder.ofKeyframe(View.SCALE_X, k0, k1, k2), PropertyValuesHolder.ofKeyframe(View.SCALE_Y, k0, k1, k2));
        a.setDuration(450);
        number.setTag(com.particlesdevs.photoncamera.R.id.ios_motion, a);
        a.start();
    }

    // ───── parts ─────

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** A row of the card: 52dp, the title at the start in white. */
    private LinearLayout row(Context c, CharSequence title) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPaddingRelative(dp(16), 0, dp(12), 0);
        TextView t = SettingsStyle.text(c, title, 16, SettingsStyle.ROW_TITLE);
        row.addView(t, new LayoutParams(0, -2, 1f));
        return row;
    }

    /** A half of the stepper: a tap is one step, a hold repeats it until the finger leaves. */
    private TextView stepButton(Context c, String label, CharSequence description, int dir) {
        TextView b = SettingsStyle.text(c, label, 22, SettingsStyle.ROW_TITLE);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        b.setOnClickListener(v -> { if (nudge(dir)) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); });
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            if (!b.isPressed() || !nudge(dir)) return;
            b.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            b.postDelayed(repeat[0], 70);
        };
        b.setOnLongClickListener(v -> {
            if (!nudge(dir)) return true;
            b.postDelayed(repeat[0], 70);
            return true;
        });
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) b.removeCallbacks(repeat[0]);
            return false;
        });
        return b;
    }

    private static void paintStep(TextView b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : .3f);
    }

    private static void tintCursor(EditText field, int accent) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(accent);
        cursor.setSize(Math.round(2 * field.getResources().getDisplayMetrics().density), 1);
        field.setTextCursorDrawable(cursor);
        field.setHighlightColor(SettingsStyle.tint(accent, .35f));
    }

    /** «Плитка в шторке» in the card: the shade pin of {@code key} on the shared switch (the switch tagged "sheet_pin"). */
    private void addPin(Context c, String key) {
        final android.content.SharedPreferences prefs =
                com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        View hair = new View(c);
        hair.setBackgroundColor(SettingsStyle.HAIRLINE);
        LayoutParams hp = new LayoutParams(-1, Math.max(1, Math.round(.5f * getResources().getDisplayMetrics().density)));
        hp.setMarginStart(dp(16));
        card.addView(hair, hp);
        LinearLayout row = row(c, Lang.t(c, "Плитка в шторке", "Tile in the shade"));
        row.setTag("precision_pin_row");
        IosSwitch sw = new IosSwitch(c);
        sw.setColours(accent, 0xFF39393C);
        sw.setTag("sheet_pin");
        sw.setFocusable(false);
        sw.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        sw.setCheckedNow(com.particlesdevs.photoncamera.settings.ShadeTiles.isPinned(prefs, key));
        sw.setOnCheckedChangeListener((s, on) -> {
            if (on != com.particlesdevs.photoncamera.settings.ShadeTiles.isPinned(prefs, key))
                com.particlesdevs.photoncamera.settings.ShadeTiles.toggle(prefs, key);
            boolean pinned = com.particlesdevs.photoncamera.settings.ShadeTiles.isPinned(prefs, key);
            if (s.isChecked() != pinned) s.setChecked(pinned);
            row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
        });
        row.addView(sw, new LayoutParams(-2, -2));
        row.setFocusable(true);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        row.setOnClickListener(v -> sw.toggle());
        row.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(sw.isChecked());
            }
        });
        card.addView(row, new LayoutParams(-1, -2));
    }

    // ───── the sheet ─────

    public static BottomSheetDialog show(Context c, String title, double min, double max, double value, double fallback, boolean decimal, Save save) {
        return show(c, null, title, min, max, value, fallback, decimal, save);
    }

    /** {@code key}: the setting, for «Плитка в шторке» (null: no pin). */
    public static BottomSheetDialog show(Context c, String key, String title, double min, double max, double value, double fallback,
                                         boolean decimal, Save save) {
        return show(c, key, title, min, max, value, fallback, decimal, 0, -1, save);
    }

    /**
     * As {@link #show(Context, String, String, double, double, double, double, boolean, Save)} with the setting's {@code step}
     * (0: 0.1 for decimals, 1 otherwise) and the {@code decimals} it shows (-1: those of the step and the range).
     */
    public static BottomSheetDialog show(Context c, String key, String title, double min, double max, double value, double fallback,
                                         boolean decimal, double step, int decimals, Save save) {
        PrecisionEditor editor = new PrecisionEditor(c, min, max, value, fallback, decimal, step, decimals);
        if (SettingsStyle.pinnable(c, key)) editor.addPin(c, key);
        int accent = editor.accent;

        // the floating sheet: 8dp from the screen's sides and bottom, 34dp corners, a hairline edge, the grabber
        FrameLayout outer = new FrameLayout(c);
        outer.setPadding(editor.dp(8), 0, editor.dp(8), editor.dp(8));
        outer.setClipToPadding(false);
        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SettingsStyle.PAGE);
        bg.setCornerRadius(editor.dp(34));
        bg.setStroke(Math.max(1, Math.round(.5f * c.getResources().getDisplayMetrics().density)), EDGE);
        sheet.setBackground(bg);
        sheet.setElevation(editor.dp(12));
        sheet.setPadding(0, editor.dp(6), 0, editor.dp(18));
        sheet.setTag("precision_sheet");
        View grab = new View(c);
        grab.setBackground(SettingsStyle.shape(c, 0x38FFFFFF, 0, 3));
        LayoutParams gp = new LayoutParams(editor.dp(36), editor.dp(5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(grab, gp);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(editor.dp(8), 0, editor.dp(8), 0);
        TextView cancel = headButton(c, Lang.t(c, "Отмена", "Cancel"), accent, false);
        cancel.setTag("sheet_cancel");
        TextView name = SettingsStyle.text(c, title, 17, SettingsStyle.ROW_TITLE);
        name.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(name.getContext(), 600));
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTag("sheet_title");
        if (android.os.Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        TextView done = headButton(c, Lang.t(c, "Готово", "Done"), accent, true);
        done.setTag("sheet_save");
        head.addView(cancel, new LayoutParams(-2, editor.dp(44)));
        head.addView(name, new LayoutParams(0, -2, 1f));
        head.addView(done, new LayoutParams(-2, editor.dp(44)));
        LayoutParams hp = new LayoutParams(-1, editor.dp(48));
        sheet.addView(head, hp);
        sheet.addView(editor, new LayoutParams(-1, -2));
        outer.addView(sheet, new FrameLayout.LayoutParams(-1, -2));

        BottomSheetDialog d = SettingsStyle.sheet(c, outer);
        cancel.setOnClickListener(v -> d.cancel());
        done.setOnClickListener(v -> {
            if (!editor.commitField()) return;
            save.value(editor.result());
            d.dismiss();
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        return d;
    }

    /** «Отмена» / «Готово»: accent text buttons of the header (17sp; «Готово» bold). */
    private static TextView headButton(Context c, CharSequence label, int accent, boolean bold) {
        TextView b = SettingsStyle.text(c, label, 17, accent);
        if (bold) b.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(c, com.particlesdevs.photoncamera.ui.style.Fonts.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(SettingsStyle.dp(c, 12), 0, SettingsStyle.dp(c, 12), 0);
        b.setFocusable(true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, SettingsStyle.shape(c, 0xFFFFFFFF, 0, 22)));
        return b;
    }
}
