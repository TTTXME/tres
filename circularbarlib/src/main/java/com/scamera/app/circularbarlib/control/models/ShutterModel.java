package com.scamera.app.circularbarlib.control.models;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;

import com.scamera.app.circularbarlib.camera.ManualStops;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.List;

/** Shutter: auto, then the exposure times of {@link ManualStops#shutter} within the sensor's range (ns). */
public class ShutterModel extends ManualModel<Long> {

    public ShutterModel(Context context, CameraCharacteristics characteristics, Range<Long> range,
                        ManualParamModel params, ValueChangedEvent onLabel, Vibrator vibrator) {
        super(context, characteristics, range, params, onLabel, vibrator);
    }

    @Override
    protected void addStops() {
        chosen = makeAutoStop(ManualParamModel.SHUTTER_AUTO_NS, null);
        stopList().add(chosen);
        if (range == null) return;
        List<Long> times = ManualStops.shutter(range.getLower().longValue(), range.getUpper().longValue());
        for (int i = 0; i < times.size(); i++) {
            long ns = times.get(i);
            KnobItemInfo stop = new KnobItemInfo(ManualStops.shutterLabel(ns), i + 1, ns);
            stop.majorTick = i == 0 || i == times.size() - 1 || ManualStops.majorShutter(ns);
            stopList().add(stop);
        }
    }

    @Override
    public void onSelectedKnobItemChanged(KnobItemInfo stop) {
        chosen = stop;
        params.setShutterValueNs(chosen.cameraValue);
    }
}
