package com.scamera.app.circularbarlib.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.scamera.app.circularbarlib.R;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.control.models.ManualModel;
import com.scamera.app.circularbarlib.model.KnobModel;
import com.scamera.app.circularbarlib.model.ManualModeModel;
import com.scamera.app.circularbarlib.ui.views.ManualChipView;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.scamera.app.circularbarlib.ui.views.scaleview.LinearScaleView;

import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Binds the five manual models to the card-style panel (MANUAL_TASK.md): the chips (value, state, selection, the EV lock),
 * the toggle's dot, the ruler card (header pill, «Всё на авто», the scale) and the top-bar summary when the camera screen
 * has one ({@code R.id.manual_summary}).
 * <p>
 * Created by vibhorSrv; card style by the manual-controls task.
 */
public class ViewObserver implements Observer {
    /** What the chips and the ruler ask of the console. */
    public interface Actions {
        void onChipTap(int param, View chip);

        boolean onChipLongPress(int param);

        void onResetAll();

        void onCollapse();

        /** The models changed (the EV lock may have set in). */
        void onStateChanged();
    }

    private final Activity host;
    private final ExpandingManualPanel manualMode;
    private final LinearScaleView linearScaleView;
    private final ManualChipView[] chips = new ManualChipView[ManualPanelState.COUNT];
    private final String[] names = new String[ManualPanelState.COUNT];
    private final View knobContainer;
    private final ImageView rulerIcon;
    private final TextView rulerValue, allAuto;
    private final ManualSummaryView summary;
    private final OrientationEventListener turning;
    private final ManualFormat.Units units;
    private int rotation = 0;
    private int accent;
    /** Model backing whichever parameter is on screen, shared by both controls. */
    private ManualModel<?> currentModel;
    /** Last item the scale reported, so the model receives a real previous value. */
    private KnobItemInfo lastScaleItem;

    private ManualParamModel params;
    /** Models in the strip's order (ISO, shutter, EV, focus, WB); entries may be null before init. */
    private ManualModel<?>[] models = new ManualModel<?>[ManualPanelState.COUNT];
    /** Camera2 AE compensation step: the applied EV is index × step. */
    private float evStep = 1f / 3;
    private Actions actions;
    private boolean whiteBalanceSupported = true;
    // The camera's metered values (capture results); NaN / 0 while unknown.
    private int meteredIso;
    private long meteredExposure;
    private float meteredFocus = Float.NaN;
    private int meteredKelvin;

    /** SIMPLE_UI §3: the concept's line icons of the five parameters (tools/make_concept_icons.py). */
    public static final int[] ICONS = {R.drawable.pro_ic_iso, R.drawable.pro_ic_shutter, R.drawable.pro_ic_ev,
            R.drawable.pro_ic_focus, R.drawable.pro_ic_wb};
    /** The short names under the chips and in the messages («ISO», «Выдержка», «Яркость», «Фокус», «Цвет»). */
    private static final int[] SHORT_NAMES = {R.string.manual_name_iso, R.string.manual_name_shutter, R.string.manual_name_ev,
            R.string.manual_name_focus, R.string.manual_name_wb};
    /** SIMPLE_UI §3: the sixth chip, «Всё на авто». */
    private final ManualChipView allAutoChip;
    private static final int[] CHIP_IDS = {R.id.iso_option_tv, R.id.exposure_option_tv, R.id.ev_option_tv, R.id.focus_option_tv,
            R.id.wb_option_tv};

    public ViewObserver(Activity activity) {
        host = activity;
        manualMode = findViewById(R.id.pro_panel);
        knobContainer = findViewById(R.id.knobViewContainer);
        linearScaleView = findViewById(R.id.linearScaleView);
        rulerIcon = findViewById(R.id.manual_ruler_icon);
        rulerValue = findViewById(R.id.manual_ruler_value);
        allAuto = findViewById(R.id.manual_all_auto);
        allAutoChip = findViewById(R.id.all_auto_option_tv);
        summary = findViewById(R.id.manual_summary);
        units = ManualFormat.Units.of(activity);
        accent = UiTokens.cameraAccent(activity);
        names[ManualPanelState.ISO] = activity.getString(R.string.manual_tab_iso);
        names[ManualPanelState.SHUTTER] = activity.getString(R.string.manual_tab_shutter);
        names[ManualPanelState.EV] = activity.getString(R.string.manual_tab_ev);
        names[ManualPanelState.FOCUS] = activity.getString(R.string.manual_tab_focus);
        names[ManualPanelState.WB] = activity.getString(R.string.manual_tab_wb);
        for (int i = 0; i < chips.length; i++) {
            chips[i] = findViewById(CHIP_IDS[i]);
            if (chips[i] == null) continue;
            chips[i].setIcon(ICONS[i]);
            chips[i].setName(activity.getString(SHORT_NAMES[i]));
            chips[i].setAccent(accent);
            chips[i].setTag(names[i]);
            chips[i].setContentDescription(names[i]);
        }
        if (manualMode != null) {
            manualMode.setAccent(accent);
            manualMode.setOnCollapseListener(() -> { if (actions != null) actions.onCollapse(); });
        }
        if (rulerIcon != null) rulerIcon.setImageTintList(ColorStateList.valueOf(accent));
        if (allAuto != null) {
            allAuto.setTextColor(accent);
            allAuto.setOnClickListener(v -> { if (actions != null) actions.onResetAll(); });
        }
        if (allAutoChip != null) {
            allAutoChip.setAccent(accent);
            allAutoChip.setIconAccent(true);
            allAutoChip.setName(activity.getString(R.string.manual_allauto_name));
            allAutoChip.setContentDescription(activity.getString(R.string.manual_all_auto));
            // a tap returns all five to auto; while nothing is manual it only says so (the console's message)
            allAutoChip.setOnClickListener(v -> { if (actions != null) actions.onResetAll(); });
        }
        if (summary != null) summary.setAccent(accent);
        if (linearScaleView != null) {
            linearScaleView.setAccent(accent);
            linearScaleView.setAutoLabel(activity.getString(R.string.manual_value_auto));
            linearScaleView.setListener(new LinearScaleView.OnValueChangedListener() {
                @Override
                public void onValueChanged(KnobItemInfo item, boolean fromUser) {
                    // The stop shown before goes along: the model ignores a choice of the stop it already has.
                    if (currentModel != null && item != null && fromUser && item != lastScaleItem) {
                        currentModel.choose(lastScaleItem, item);
                        lastScaleItem = item;
                    }
                }

                @Override
                public void onAutoRequested() {
                    // The model decides what auto means for its parameter.
                    if (currentModel != null) {
                        currentModel.resetModel();
                        lastScaleItem = currentModel.chosenStop();
                        syncScale();
                    }
                }

                @Override
                public void onDragStateChanged(boolean dragging) {
                    // nothing follows the drag itself: each stop reached is a choice (onValueChanged)
                }
            });
        }

        turning = new OrientationEventListener(activity.getBaseContext()) {
            private static final int TURN_MS = 350;
            private int shownBucket = OrientationEventListener.ORIENTATION_UNKNOWN;

            @Override
            public void onOrientationChanged(int degrees) {
                // The bucket the phone is in (Surface.ROTATION_*) and the icons' angle for it; a reading between the
                // buckets keeps them. (degrees < 20 also takes ORIENTATION_UNKNOWN, -1, as upright, as before.)
                int bucket, angle;
                if (degrees >= 340 || degrees < 20 && rotation != 0) {
                    bucket = Surface.ROTATION_0;
                    angle = 0;
                } else if (degrees >= 70 && degrees < 110 && rotation != 90) {
                    bucket = Surface.ROTATION_270;
                    angle = -90;
                } else if (degrees >= 160 && degrees < 200 && rotation != 180) {
                    bucket = Surface.ROTATION_180;
                    angle = 180;
                } else if (degrees >= 250 && degrees < 290 && rotation != 270) {
                    bucket = Surface.ROTATION_90;
                    angle = 90;
                } else {
                    bucket = OrientationEventListener.ORIENTATION_UNKNOWN;
                    angle = rotation;
                }
                boolean turns = shownBucket != bucket && degrees != OrientationEventListener.ORIENTATION_UNKNOWN;
                // Nothing would change: the auto-rotate setting (a settings provider query) is not asked on every reading.
                if (!turns && angle == rotation) return;
                if (Settings.System.getInt(host.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0)
                    return; // auto-rotate off
                rotation = angle;
                if (!turns) return;
                shownBucket = bucket;
                if (bucket == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                // The chips keep their portrait layout; only their icons turn (as the lens strip's labels).
                for (ManualChipView chip : chips)
                    if (chip != null) chip.getIcon().animate().rotation(rotation).setDuration(TURN_MS).start();
            }
        };
    }

    /** The models in the strip's order, the parameter model, the AE compensation step and the console's actions. */
    public void bind(ManualModel<?>[] models, ManualParamModel params, float evStep, Actions actions) {
        this.models = models.clone();
        this.params = params;
        if (evStep > 0) this.evStep = evStep;
        this.actions = actions;
        for (int i = 0; i < chips.length; i++) {
            final int param = i;
            ManualChipView chip = chips[i];
            if (chip == null) continue;
            chip.setOnClickListener(v -> { if (this.actions != null) this.actions.onChipTap(param, v); });
            chip.setOnLongClickListener(v -> this.actions != null && this.actions.onChipLongPress(param));
        }
        refresh();
    }

    /** The camera's metered values from the latest capture result (0 / NaN while unknown). */
    public void setMetered(int iso, long exposureNs, float focusDiopters, int kelvin) {
        meteredIso = iso;
        meteredExposure = exposureNs;
        meteredFocus = focusDiopters;
        meteredKelvin = kelvin;
        refresh();
    }

    private void syncScale() {
        if (currentModel != null && linearScaleView != null) {
            lastScaleItem = currentModel.chosenStop();
            linearScaleView.setSelectedItem(lastScaleItem);
        }
    }

    public void setWhiteBalanceSupported(boolean supported) {
        whiteBalanceSupported = supported;
        refresh();
    }

    public void enableOrientationListener() {
        if (turning.canDetectOrientation()) turning.enable();
    }

    public void disableOrientationListener() {
        turning.disable();
    }

    private <T extends View> T findViewById(int id) {
        return host.findViewById(id);
    }

    // ───── state

    private static final double[] AUTO_VALUES = {ManualParamModel.ISO_AUTO_VALUE, ManualParamModel.SHUTTER_AUTO_NS,
            ManualParamModel.EV_AUTO_STEP, ManualParamModel.FOCUS_AUTO_DIOPTERS, 0};

    /** Whether the parameter's model holds a value other than its auto item. */
    public static boolean isManual(ManualModel<?> model, int param) {
        if (model == null || model.chosenStop() == null) return false;
        return model.chosenStop().cameraValue != AUTO_VALUES[param];
    }

    /** Whether the camera offers the parameter at all (a fixed-focus lens, no manual white balance). */
    private boolean available(int param) {
        ManualModel<?> m = models[param];
        if (m == null || m.stopList().size() <= 1) return false;
        return param != ManualPanelState.WB || whiteBalanceSupported;
    }

    public ManualPanelState state() {
        boolean[] manual = new boolean[ManualPanelState.COUNT];
        for (int i = 0; i < manual.length; i++) manual[i] = isManual(models[i], i);
        return new ManualPanelState(manual[0], manual[1], manual[2], manual[3], manual[4]);
    }

    /** The value a chip, the pill and the summary show for a parameter set by the user. */
    public String manualText(int param) {
        ManualModel<?> m = models[param];
        KnobItemInfo item = m == null ? null : m.chosenStop();
        if (item == null) return "";
        switch (param) {
            case ManualPanelState.ISO:
                return ManualFormat.iso(item.cameraValue);
            case ManualPanelState.SHUTTER:
                return ManualFormat.shutter((long) item.cameraValue, units);
            case ManualPanelState.EV:
                // What the request gets: the compensation index times the camera's step.
                double index = params != null ? params.evIndex() : Math.round(item.cameraValue / evStep);
                return ManualFormat.ev(index * evStep);
            case ManualPanelState.FOCUS:
                return ManualFormat.focus(item.cameraValue, units);
            default:
                return ManualFormat.wb((int) Math.round(item.cameraValue));
        }
    }

    /** The camera's current value of a parameter in auto: metered ISO / shutter / focus / AWB, else «Авто». */
    public String meteredText(int param) {
        String auto = host.getString(R.string.manual_value_auto);
        switch (param) {
            case ManualPanelState.ISO:
                return meteredIso > 0 ? ManualFormat.iso(meteredIso) : auto;
            case ManualPanelState.SHUTTER:
                return meteredExposure > 0 ? ManualFormat.shutter(meteredExposure, units) : auto;
            case ManualPanelState.EV:
                return ManualFormat.ev(0);
            case ManualPanelState.FOCUS:
                return Float.isNaN(meteredFocus) || meteredFocus < 0 ? auto : ManualFormat.focus(meteredFocus, units);
            default:
                return meteredKelvin > 0 ? ManualFormat.wb(meteredKelvin) : auto;
        }
    }

    /** The metered value as a model value (for the scale's start in auto), NaN when unknown. */
    private double meteredValue(int param) {
        switch (param) {
            case ManualPanelState.ISO: return meteredIso > 0 ? meteredIso : Double.NaN;
            case ManualPanelState.SHUTTER: return meteredExposure > 0 ? meteredExposure : Double.NaN;
            case ManualPanelState.EV: return 0;
            case ManualPanelState.FOCUS: return Float.isNaN(meteredFocus) ? Double.NaN : meteredFocus;
            default: return meteredKelvin > 0 ? meteredKelvin : Double.NaN;
        }
    }

    private int paramOf(ManualModel<?> model) {
        for (int i = 0; i < models.length; i++) if (models[i] != null && models[i] == model) return i;
        return -1;
    }

    /** Chips, toggle dot, ruler header and summary from the models; closes the EV ruler when the lock sets in. */
    public void refresh() {
        ManualPanelState state = state();
        boolean locked = state.evLocked();
        for (int i = 0; i < chips.length; i++) {
            ManualChipView chip = chips[i];
            if (chip == null) continue;
            String a11y;
            if (!available(i)) {
                chip.bind("—", ManualChipView.UNAVAILABLE);
                a11y = i == ManualPanelState.WB ? host.getString(R.string.manual_wb_unavailable)
                        : host.getString(R.string.manual_a11y_unavailable, names[i]);
            } else if (i == ManualPanelState.EV && locked) {
                chip.bind("—", ManualChipView.LOCKED);
                a11y = host.getString(R.string.manual_a11y_locked, names[i]);
            } else if (state.isManual(i)) {
                String v = manualText(i);
                chip.bind(v, ManualChipView.MANUAL);
                a11y = host.getString(R.string.manual_a11y_manual, names[i], v);
            } else {
                String v = meteredText(i);
                chip.bind(v, ManualChipView.AUTO);
                a11y = host.getString(R.string.manual_a11y_auto, names[i], v);
            }
            chip.setContentDescription(a11y);
        }
        if (manualMode != null) manualMode.setManualDot(state.anyApplied());
        if (allAuto != null) allAuto.setVisibility(state.anyManual() ? View.VISIBLE : View.GONE);
        if (allAutoChip != null) allAutoChip.bind(host.getString(R.string.manual_allauto_value),
                state.anyManual() ? ManualChipView.AUTO : ManualChipView.DIMMED);
        bindHeader(state);
        if (summary != null) bindSummary(state);
        if (linearScaleView != null && currentModel != null) {
            int p = paramOf(currentModel);
            if (p >= 0) linearScaleView.setMeteredValue(meteredValue(p));
        }
        if (actions != null) actions.onStateChanged();
    }

    /** The ruler header: the parameter icon, its value in an accent pill (a muted pill with the metered value in auto). */
    private void bindHeader(ManualPanelState state) {
        if (rulerValue == null || currentModel == null) return;
        int p = paramOf(currentModel);
        if (p < 0) return;
        if (rulerIcon != null) rulerIcon.setImageResource(ICONS[p]);
        boolean manual = state.isManual(p);
        String v = manual ? manualText(p) : meteredText(p);
        rulerValue.setText(v);
        rulerValue.setTextColor(manual ? accent : UiTokens.MUTED);
        rulerValue.setTypeface(UiFonts.of(rulerValue.getContext(), manual ? UiFonts.BOLD : UiFonts.REGULAR));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(UiTokens.dp(host, 999));
        pill.setColor(manual ? UiTokens.chipBg(accent) : UiTokens.MUTED_PILL);
        rulerValue.setBackground(pill);
        rulerValue.setContentDescription(host.getString(manual ? R.string.manual_a11y_manual : R.string.manual_a11y_auto, names[p], v));
    }

    /** The top-bar summary: one item per applied manual parameter in the strip's order; gone without any. */
    private void bindSummary(ManualPanelState state) {
        List<Integer> applied = state.summary();
        if (applied.isEmpty()) {
            summary.setItems(new int[0], new String[0], "");
            summary.setVisibility(View.GONE);
            return;
        }
        int[] icons = new int[applied.size()];
        String[] values = new String[applied.size()];
        StringBuilder a11y = new StringBuilder();
        for (int k = 0; k < applied.size(); k++) {
            int p = applied.get(k);
            icons[k] = ICONS[p];
            values[k] = manualText(p);
            if (k > 0) a11y.append(", ");
            a11y.append(names[p]).append(' ').append(values[k]);
        }
        summary.setItems(icons, values, host.getString(R.string.manual_summary, a11y));
        summary.setVisibility(View.VISIBLE);
    }

    public ManualChipView chip(int param) {
        return chips[param];
    }

    /** The parameter's short name (the chip's name line; the messages: «Яркость: авто»). */
    public String name(int param) {
        return host.getString(SHORT_NAMES[param]);
    }

    @Override
    public void update(Observable source, Object what) {
        if (source == null || what == null) return;
        if (source instanceof KnobModel) {
            KnobModel card = (KnobModel) source;
            switch ((KnobModel.KnobModelFields) what) {
                case VISIBILITY:
                    int shown = card.isKnobVisible() ? View.VISIBLE : View.GONE;
                    knobContainer.setVisibility(shown);
                    if (linearScaleView != null) linearScaleView.setVisibility(shown);
                    break;
                case MANUAL_MODEL:
                    currentModel = card.getManualModel();
                    if (linearScaleView != null && currentModel != null) {
                        int p = paramOf(currentModel);
                        List<KnobItemInfo> items = currentModel.stopList();
                        linearScaleView.setMode(LinearScaleView.modeFor(p), units);
                        linearScaleView.setItems(items, currentModel.chosenStop(), autoItemOf(currentModel, p));
                        // New parameter, new baseline: otherwise the first drag on the next parameter is compared against
                        // the previous parameter's item and discarded.
                        lastScaleItem = currentModel.chosenStop();
                    }
                    refresh();
                    break;
            }
        }
        if (source instanceof ManualModeModel) {
            ManualModeModel panelModel = (ManualModeModel) source;
            switch ((ManualModeModel.ManualModelFields) what) {
                case EV_TEXT:
                case EXP_TEXT:
                case ISO_TEXT:
                case FOCUS_TEXT:
                case WB_TEXT:
                    syncScale();
                    refresh();
                    break;
                case SELECTED_TV:
                    int selected = panelModel.getSelectedTextViewId();
                    for (ManualChipView chip : chips) if (chip != null) chip.setChosen(chip.getId() == selected && selected != -1);
                    break;
                case PANEL_VISIBILITY:
                    Binding.togglePanelVisibility(manualMode, panelModel.isManualPanelVisible());
                    break;
                default:
                    break;
            }
        }
    }

    /** The model's auto item: the list entry with the parameter's auto value. */
    private static KnobItemInfo autoItemOf(ManualModel<?> model, int param) {
        if (param < 0) return null;
        for (KnobItemInfo item : model.stopList()) if (item.cameraValue == AUTO_VALUES[param]) return item;
        return null;
    }
}
