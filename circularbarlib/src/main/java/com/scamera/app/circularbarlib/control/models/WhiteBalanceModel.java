package com.scamera.app.circularbarlib.control.models;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;

import com.scamera.app.circularbarlib.camera.ManualWhiteBalance;
import com.scamera.app.circularbarlib.control.ManualParamModel;
import com.scamera.app.circularbarlib.ui.views.knobview.KnobItemInfo;

/** White balance: auto, then 2000 K .. 10000 K in 100 K steps where the camera takes a manual colour transform. */
public final class WhiteBalanceModel extends ManualModel<Integer> {
    private static final int COOLEST = 2000, WARMEST = 10000, STEP = 100;

    public WhiteBalanceModel(Context context, CameraCharacteristics camera, ManualParamModel params, ValueChangedEvent onLabel,
                             Vibrator vibrator) {
        super(context, camera, new Range<>(COOLEST, WARMEST), params, onLabel, vibrator);
    }

    @Override
    protected void addStops() {
        chosen = makeAutoStop(0, null);
        getKnobInfoList().add(chosen);
        if (!ManualWhiteBalance.isSupported(cameraCharacteristics)) return;
        for (int k = COOLEST, stop = 1; k <= WARMEST; k += STEP, stop++) getKnobInfoList().add(new KnobItemInfo(k + "K", stop, k));
    }

    @Override
    public void onSelectedKnobItemChanged(KnobItemInfo stop) {
        chosen = stop;
        params.setWhiteBalanceKelvin((int) chosen.value);
    }
}
