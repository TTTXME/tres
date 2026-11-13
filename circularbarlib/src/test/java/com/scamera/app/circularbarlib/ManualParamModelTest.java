package com.scamera.app.circularbarlib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.scamera.app.circularbarlib.control.ManualParamModel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The panel's value model: each change names the value that changed; a reset puts everything back to auto at once. */
public class ManualParamModelTest {
    @Test
    public void changesAreNamedAndResetGoesBackToAuto() {
        final ManualParamModel model = new ManualParamModel();
        final List<Object> told = new ArrayList<>();
        model.addObserver((o, what) -> told.add(what));
        model.setIsoSetting(400);
        model.setKelvinSetting(5200);
        assertEquals(java.util.Arrays.asList(ManualParamModel.CHANGED_ISO, ManualParamModel.CHANGED_WB), told);
        assertTrue(model.offAuto());
        model.reset();
        assertFalse(model.offAuto());
        assertEquals(ManualParamModel.FOCUS_AUTO_DIOPTERS, model.focusValue(), 0);
        assertEquals(0, model.kelvinSetting());
        assertEquals(ManualParamModel.ALL_AUTO, told.get(told.size() - 1));
    }
}
