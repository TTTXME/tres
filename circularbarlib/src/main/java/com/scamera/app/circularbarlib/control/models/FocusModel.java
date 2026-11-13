package com.scamera.app.circularbarlib.control.models;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;

import com.scamera.app.circularbarlib.R;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.Locale;

/**
 * Focus: auto, then 41 distances (diopters) from the near end of the lens's range to the far end. A fixed-focus lens
 * (no range) has its «fixed» stop only.
 */
public class FocusModel extends ManualModel<Float> {
    private static final int SPANS = 40;

    public FocusModel(Context context, CameraCharacteristics characteristics, Range<Float> range,
                      ManualParamModel params, ValueChangedEvent onLabel, Vibrator vibrator) {
        super(context, characteristics, range, params, onLabel, vibrator);
    }

    @Override
    protected void addStops() {
        if (range == null) {
            chosen = makeAutoStop(-1.0, context.getString(R.string.manual_mode_fixed));
            stopList().add(chosen);
            return;
        }
        chosen = makeAutoStop(ManualParamModel.FOCUS_AUTO_DIOPTERS, null);
        stopList().add(chosen);
        float near = range.getUpper(), far = range.getLower();
        float step = (near - far) / SPANS;
        // Stepped down in float as before (the stop values are what the requests get); the last stop is the far end itself.
        java.util.List<Float> distances = new java.util.ArrayList<>();
        if (step > 0) for (float d = near; d >= far; d -= step) distances.add(d);
        else distances.add(far); // a range of one distance: it looped forever before
        if (!distances.isEmpty()) distances.set(distances.size() - 1, far);
        for (int i = 0; i < distances.size(); i++) {
            float d = distances.get(i);
            stopList().add(new KnobItemInfo(String.format(Locale.ROOT, "%.2f", d), i + 1, (double) d));
        }
    }

    @Override
    public void onSelectedKnobItemChanged(KnobItemInfo stop) {
        chosen = stop;
        params.setFocusValue(chosen.cameraValue);
    }
}
