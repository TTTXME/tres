package com.scamera.app.circularbarlib.console;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;
import android.view.View;
import android.widget.Toast;

import com.scamera.app.circularbarlib.R;
import com.scamera.app.circularbarlib.api.ManualModeConsole;
import com.scamera.app.circularbarlib.camera.CameraProperties;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.control.models.EvModel;
import com.scamera.app.circularbarlib.control.models.FocusModel;
import com.scamera.app.circularbarlib.control.models.IsoModel;
import com.scamera.app.circularbarlib.control.models.ManualModel;
import com.scamera.app.circularbarlib.control.models.ShutterModel;
import com.scamera.app.circularbarlib.control.models.WhiteBalanceModel;
import com.scamera.app.circularbarlib.model.KnobModel;
import com.scamera.app.circularbarlib.model.ManualModeModel;
import com.scamera.app.circularbarlib.ui.ManualPanelState;
import com.scamera.app.circularbarlib.ui.ViewObserver;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.Observer;
import java.util.function.Consumer;

import static com.scamera.app.circularbarlib.ui.ManualPanelState.COUNT;
import static com.scamera.app.circularbarlib.ui.ManualPanelState.EV;
import static com.scamera.app.circularbarlib.ui.ManualPanelState.FOCUS;
import static com.scamera.app.circularbarlib.ui.ManualPanelState.ISO;
import static com.scamera.app.circularbarlib.ui.ManualPanelState.SHUTTER;
import static com.scamera.app.circularbarlib.ui.ManualPanelState.WB;

/**
 * The manual panel of one camera module: the five parameter models (ISO, shutter, EV, focus, white balance, in the strip's
 * order), which one has its ruler open, and the panel's models that {@link ViewObserver} draws.
 */
public class ManualModeConsoleImpl implements ManualModeConsole {
    private static ManualModeConsole shared;
    private final ManualModeModel panel = new ManualModeModel();
    private final KnobModel ruler = new KnobModel();
    private final ManualParamModel params = new ManualParamModel();
    /** The models by parameter (ManualPanelState order); null before {@link #attachCamera}. */
    private final ManualModel<?>[] byParam = new ManualModel<?>[COUNT];
    /** The parameter whose ruler is open, or null. */
    private ManualModel<?> open;
    private ViewObserver view;
    private Context context;
    private float evStep = 1f / 3;
    private Consumer<CharSequence> messages;
    private boolean whiteBalanceSupported = true;
    // The module the models were built for, and the ranges the ISO and shutter rulers cover.
    private CameraCharacteristics characteristics;
    private Vibrator vibrator;
    private Range<Integer> isoRange;
    private Range<Long> exposureRange;
    // The latest metered values, kept across a re-init (a lens switch builds a new ViewObserver).
    private int meteredIso;
    private long meteredExposure;
    private float meteredFocus = Float.NaN;
    private int meteredKelvin;

    private ManualModeConsoleImpl() {}

    public static ManualModeConsole getInstance() {
        if (shared == null) shared = newInstance();
        return shared;
    }

    public static ManualModeConsole newInstance() {
        return new ManualModeConsoleImpl();
    }

    /** What the panel's chips and ruler ask for. */
    private final ViewObserver.Actions actions = new ViewObserver.Actions() {
        @Override public void onChipTap(int param, View chip) { ManualModeConsoleImpl.this.onChipTap(param, chip); }

        @Override public boolean onChipLongPress(int param) { return ManualModeConsoleImpl.this.onChipLongPress(param); }

        @Override public void onResetAll() { resetAllToAuto(); }

        @Override public void onCollapse() { closeRuler(); }

        @Override public void onStateChanged() { ManualModeConsoleImpl.this.onStateChanged(); }
    };

    // ───── set-up

    @Override
    public void attachCamera(Activity activity, CameraCharacteristics camera) {
        context = activity;
        view = new ViewObserver(activity);
        observe();
        build(activity, camera);
        for (int p : new int[]{EV, FOCUS, SHUTTER, ISO, WB}) if (byParam[p] != null) byParam[p].showAutoLabel();
        for (int p : new int[]{FOCUS, EV, ISO, SHUTTER, WB}) if (byParam[p] != null) byParam[p].restoreModuleValue();
        view.bind(models(), params, evStep, actions);
        view.setMetered(meteredIso, meteredExposure, meteredFocus, meteredKelvin);
    }

    private void build(Context c, CameraCharacteristics camera) {
        CameraProperties ranges = new CameraProperties(camera);
        params.reset();
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        byParam[FOCUS] = new FocusModel(c, camera, ranges.focusRange, params, panel::setFocusText, v);
        EvModel ev = new EvModel(c, camera, ranges.evRange, params, panel::setEvText, v);
        evStep = camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
        ev.setEvStep(evStep);
        byParam[EV] = ev;
        byParam[ISO] = new IsoModel(c, camera, ranges.isoRange, params, panel::setIsoText, v);
        byParam[SHUTTER] = new ShutterModel(c, camera, ranges.expRange, params, panel::setExposureText, v);
        characteristics = camera;
        vibrator = v;
        isoRange = ranges.isoRange;
        exposureRange = ranges.expRange;
        open = null; // a new module: the old parameter's ruler is gone
        byParam[WB] = new WhiteBalanceModel(c, camera, params, panel::setWbText, v);
        whiteBalanceSupported = byParam[WB].stopList().size() > 1;
        view.setWhiteBalanceSupported(whiteBalanceSupported);
        ruler.setKnobVisible(false);
        panel.setCheckedTextViewId(-1);
    }

    /**
     * Before the ISO or shutter ruler opens: the module's real range may have changed since the models were built (the
     * capture path widens SENSOR_INFO_SENSITIVITY_RANGE to an ISO the camera was seen to use, e.g. a vivo tele that
     * reports [400, 800]). Then the model is rebuilt over the current range, with its own value logic, and the stored
     * value restored, so the ruler covers exactly what the camera can do.
     */
    void refreshRange(int param) {
        if (characteristics == null || context == null) return;
        CameraProperties now;
        try {
            now = new CameraProperties(characteristics);
        } catch (RuntimeException unavailable) {
            return;
        }
        ManualModel<?> rebuilt = null;
        if (param == ISO && now.isoRange != null && !now.isoRange.equals(isoRange)) {
            isoRange = now.isoRange;
            rebuilt = new IsoModel(context, characteristics, isoRange, params, panel::setIsoText, vibrator);
        } else if (param == SHUTTER && now.expRange != null && !now.expRange.equals(exposureRange)) {
            exposureRange = now.expRange;
            rebuilt = new ShutterModel(context, characteristics, exposureRange, params, panel::setExposureText, vibrator);
        }
        if (rebuilt == null) return;
        byParam[param] = rebuilt;
        rebuilt.restoreModuleValue();
        view.bind(models(), params, evStep, actions);
    }

    /** The ISO range the ISO ruler covers (tests). */
    public Range<Integer> isoRange() {
        return isoRange;
    }

    /** The models in the strip's order: ISO, shutter, EV, focus, white balance. */
    public ManualModel<?>[] models() {
        return byParam.clone();
    }

    ManualModel<?> model(int param) {
        return byParam[param];
    }

    // ───── state

    /** The panel's state from the models (which parameters are manual, the EV lock). */
    public ManualPanelState state() {
        boolean[] m = new boolean[COUNT];
        for (int p = 0; p < COUNT; p++) m[p] = ViewObserver.isManual(byParam[p], p);
        return new ManualPanelState(m[ISO], m[SHUTTER], m[EV], m[FOCUS], m[WB]);
    }

    private boolean available(int param) {
        ManualModel<?> m = byParam[param];
        return m != null && m.stopList().size() > 1 && (param != WB || whiteBalanceSupported);
    }

    private boolean evBlocked(int param) {
        if (param != EV || !state().evLocked()) return false;
        message(string(R.string.manual_ev_locked));
        return true;
    }

    /** The parameter whose ruler is open, or -1. */
    public int selectedParam() {
        for (int p = 0; p < COUNT; p++) if (byParam[p] != null && byParam[p] == open) return p;
        return -1;
    }

    // ───── the panel's gestures

    /**
     * A tap: the chip's ruler opens (a tap on the open chip closes it). A locked EV says why instead; a parameter the
     * camera lacks does nothing (white balance says why).
     */
    public void onChipTap(int param, View chip) {
        if (evBlocked(param)) return;
        if (open == null || open != byParam[param]) refreshRange(param);
        if (!available(param)) {
            if (param == WB) message(string(R.string.manual_wb_unavailable));
            return;
        }
        ManualModel<?> m = byParam[param];
        if (m == open) {
            closeRuler();
        } else if (m.stopList().size() > 1) {
            ruler.setManualModel(m);
            ruler.setKnobVisible(true);
            panel.setCheckedTextViewId(chip.getId());
            open = m;
        }
    }

    /** A long press: the parameter back to auto, with a toast «<name>: авто». */
    public boolean onChipLongPress(int param) {
        if (evBlocked(param) || !available(param)) return true;
        byParam[param].resetModel();
        message(context == null ? "" : context.getString(R.string.manual_toast_auto, view.name(param)));
        return true;
    }

    @Override
    public void resetAllToAuto() {
        // SIMPLE_UI §3: the «Всё на авто» chip with nothing manual only says so
        if (!state().anyManual()) {
            message(string(R.string.manual_toast_already_auto));
            return;
        }
        for (int p = 0; p < COUNT; p++) if (ViewObserver.isManual(byParam[p], p)) byParam[p].resetModel();
        message(string(R.string.manual_toast_all_auto));
    }

    /** The EV lock sets in while its ruler is open: the ruler closes. */
    void onStateChanged() {
        if (open != null && open == byParam[EV] && state().evLocked()) closeRuler();
    }

    /** Closes the ruler and forgets the open parameter (a collapse, a second tap, the EV lock). */
    private void closeRuler() {
        if (open == null) return;
        open = null; // first: the notifications below refresh the panel, which asks onStateChanged again
        ruler.setManualModel(null);
        ruler.setKnobVisible(false);
        panel.setCheckedTextViewId(-1);
    }

    @Override
    public void collapseKnobs() {
        ruler.setKnobVisible(false);
        open = null;
        for (int p : new int[]{FOCUS, SHUTTER, ISO, EV, WB}) if (byParam[p] != null) byParam[p].resetModel();
        panel.setCheckedTextViewId(-1);
    }

    // ───── EV for the viewfinder's own control

    @Override
    public float evCompensation() {
        KnobItemInfo now = byParam[EV] == null ? null : byParam[EV].chosenStop();
        return now == null ? 0f : (float) now.cameraValue;
    }

    @Override
    public float[] evRange() {
        float lo = 0f, hi = 0f;
        if (byParam[EV] != null) for (KnobItemInfo s : byParam[EV].stopList()) {
            lo = Math.min(lo, (float) s.cameraValue);
            hi = Math.max(hi, (float) s.cameraValue);
        }
        return new float[]{lo, hi};
    }

    @Override
    public boolean evLocked() {
        return byParam[EV] != null && state().evLocked();
    }

    @Override
    public void setEvCompensation(float ev) {
        ManualModel<?> m = byParam[EV];
        if (m == null || evLocked()) return;
        KnobItemInfo nearest = null;
        for (KnobItemInfo s : m.stopList())
            if (nearest == null || Math.abs(s.cameraValue - ev) < Math.abs(nearest.cameraValue - ev)) nearest = s;
        if (nearest == null || nearest == m.chosenStop()) return;
        m.choose(m.chosenStop(), nearest);
        onStateChanged();
    }

    // ───── messages, metering, life cycle

    private String string(int id) {
        return context == null ? "" : context.getString(id);
    }

    private void message(CharSequence text) {
        if (text == null || text.length() == 0) return;
        if (messages != null) messages.accept(text);
        else if (context != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void setMessageSink(Consumer<CharSequence> sink) {
        messages = sink;
    }

    @Override
    public void setMeteredValues(int iso, long exposureNs, float focusDiopters, int awbKelvin) {
        boolean same = iso == meteredIso && exposureNs == meteredExposure && awbKelvin == meteredKelvin
                && Float.compare(focusDiopters, meteredFocus) == 0;
        if (same) return;
        meteredIso = iso;
        meteredExposure = exposureNs;
        meteredFocus = focusDiopters;
        meteredKelvin = awbKelvin;
        if (view != null) view.setMetered(iso, exposureNs, focusDiopters, awbKelvin);
    }

    @Override
    public void resumePanel() {
        if (view != null) view.enableOrientationListener();
        observe();
    }

    @Override
    public void pausePanel() {
        if (view != null) view.disableOrientationListener();
        unobserve();
    }

    @Override
    public void destroyPanel() {
        shared = null;
    }

    private void observe() {
        if (view == null) return;
        unobserve();
        ruler.addObserver(view);
        panel.addObserver(view);
    }

    private void unobserve() {
        ruler.deleteObservers();
        panel.deleteObservers();
    }

    @Override
    public void observeValues(Observer observer) {
        params.addObserver(observer);
    }

    @Override
    public void stopObservingValues() {
        params.deleteObservers();
    }

    @Override
    public ManualParamModel valueModel() {
        return params;
    }

    @Override
    public void setPanelShown(boolean visible) {
        panel.setManualPanelVisible(visible);
        if (!visible) params.reset();
    }

    @Override
    public boolean offAuto() {
        return params.offAuto();
    }

    @Override
    public void clearValues() {
        params.reset();
    }

    @Override
    public boolean isPanelShown() {
        return panel.isManualPanelVisible();
    }

    @Override
    public boolean focusKnobOpen() {
        return open instanceof FocusModel;
    }

    @Override
    public boolean manualFocusOn() {
        KnobItemInfo now = byParam[FOCUS] == null ? null : byParam[FOCUS].chosenStop();
        return now != null && now.cameraValue != ManualParamModel.FOCUS_AUTO_DIOPTERS;
    }
}
