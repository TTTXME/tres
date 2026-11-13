package com.scamera.app.circularbarlib.control.models;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;

import com.scamera.app.circularbarlib.camera.ManualStops;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;

import java.util.List;

/** ISO: auto, then the stops of {@link ManualStops#iso} within the sensor's range. */
public class IsoModel extends ManualModel<Integer> {

    public IsoModel(Context context, CameraCharacteristics characteristics, Range<Integer> range,
                    ManualParamModel params, ValueChangedEvent onLabel, Vibrator vibrator) {
        super(context, characteristics, range, params, onLabel, vibrator);
    }

    @Override
    protected void addStops() {
        chosen = makeAutoStop(ManualParamModel.ISO_AUTO, null);
        getKnobInfoList().add(chosen);
        if (range == null) return;
        List<Long> isos = ManualStops.iso(range.getLower().longValue(), range.getUpper().longValue());
        for (int i = 0; i < isos.size(); i++) {
            long iso = isos.get(i);
            KnobItemInfo stop = new KnobItemInfo(String.valueOf(iso), i + 1, iso);
            stop.majorTick = i == 0 || i == isos.size() - 1 || ManualStops.majorIso(iso);
            getKnobInfoList().add(stop);
        }
    }

    @Override
    public void onSelectedKnobItemChanged(KnobItemInfo stop) {
        chosen = stop;
        params.setCurrentISOValue(chosen.value);
    }
}
