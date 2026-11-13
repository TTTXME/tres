package com.scamera.app.circularbarlib.control.models;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Log;
import android.util.Range;

import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exposure compensation: auto (0 EV), then quarter-stop EV values from the top of the camera's range down to its bottom,
 * zero left out. The camera gets the value divided by its compensation step ({@link #setEvStep}).
 */
public class EvModel extends ManualModel<Float> {
    private static final String TAG = "EvModel";
    private static final float QUARTER = 0.25f;
    private float evStep;

    public EvModel(Context context, CameraCharacteristics characteristics, Range<Float> range,
                   ManualParamModel params, ValueChangedEvent onLabel, Vibrator vibrator) {
        super(context, characteristics, range, params, onLabel, vibrator);
    }

    public void setEvStep(float evStep) {
        this.evStep = evStep;
    }

    private static boolean nearZero(float ev) {
        return Math.abs(ev) <= 0.001;
    }

    @Override
    protected void addStops() {
        if (range == null || (range.getLower() == 0f && range.getUpper() == 0f)) {
            Log.d(TAG, "no compensation range");
            return;
        }
        chosen = makeAutoStop(ManualParamModel.EV_AUTO, null);
        getKnobInfoList().add(chosen);
        // Stepped down in float from the top (the values the requests get), rounded to 1e-4; the last is the bottom itself.
        List<Float> evs = new ArrayList<>();
        int above = 0, below = 0;
        for (float ev = range.getUpper(); ev >= range.getLower(); ev -= QUARTER) {
            evs.add(Math.round(10000f * ev) / 10000f);
            if (nearZero(ev)) continue;
            if (ev > 0f) above++;
            else below++;
        }
        if (!evs.isEmpty()) evs.set(evs.size() - 1, range.getLower());
        // Ticks count away from zero: +above .. 1 above it, 0 .. -below under it (index i sits at count - i).
        for (int i = 0; i < evs.size(); i++) {
            float ev = evs.get(i);
            if (nearZero(ev)) continue;
            int tick = (ev > 0f ? above : below) - i;
            getKnobInfoList().add(new KnobItemInfo(String.format(Locale.ROOT, "%.2f", ev), tick, ev));
        }
    }

    @Override
    public void onSelectedKnobItemChanged(KnobItemInfo stop) {
        chosen = stop;
        params.setCurrentEvValue((int) (chosen.value / evStep));
    }
}
