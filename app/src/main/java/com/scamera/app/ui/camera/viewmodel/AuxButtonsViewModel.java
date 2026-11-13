/*
 *
 *  PhotonCamera
 *  AuxButtonsViewModel.java
 *  Copyright (C) 2020 - 2021  Vibhor
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 * /
 */

package com.scamera.app.ui.camera.viewmodel;

import android.hardware.camera2.CameraCharacteristics;

import androidx.lifecycle.ViewModel;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.camera.data.CameraLensData;
import com.scamera.app.ui.camera.model.AuxButtonsModel;
import com.scamera.app.ui.camera.views.AuxButtonsLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The lens strip's model for the camera screen: the lens lists (made once per screen), the active camera, the burst lock. */
public class AuxButtonsViewModel extends ViewModel {
    /** The longest lens first. */
    private static final Comparator<CameraLensData> LONGEST_FIRST = (a, b) -> Double.compare(b.getZoomFactor(), a.getZoomFactor());
    private final AuxButtonsModel lenses = new AuxButtonsModel();
    private boolean listed;
    private boolean enabled = true;

    /** The cameras by side, the ones hidden in the settings («hidden_camera_ids») left out; only the first call counts. */
    public void initCameraLists(Map<String, CameraLensData> byId) {
        if (listed) return;
        final SettingsManager settings = ScameraApp.getSettingsManagerStatic();
        final Set<String> hidden = settings != null
                ? settings.getStringSet("default_scope", "hidden_camera_ids", new java.util.HashSet<>()) : Collections.emptySet();
        final List<CameraLensData> back = new ArrayList<>(), front = new ArrayList<>();
        for (Map.Entry<String, CameraLensData> e : byId.entrySet()) {
            if (hidden.contains(e.getKey())) continue;
            final int facing = e.getValue().getFacing();
            if (facing == CameraCharacteristics.LENS_FACING_BACK) back.add(e.getValue());
            else if (facing == CameraCharacteristics.LENS_FACING_FRONT) front.add(e.getValue());
        }
        back.sort(LONGEST_FIRST);
        front.sort(LONGEST_FIRST);
        lenses.setBackCameras(back);
        lenses.setFrontCameras(front);
        listed = true;
    }

    public void setAuxButtonListener(AuxButtonsLayout.AuxButtonListener listener) {
        lenses.setAuxButtonListener(listener);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        lenses.setEnabled(on);
    }

    public void setActiveId(String cameraId) {
        lenses.setCurrentCameraId(cameraId);
    }

    public AuxButtonsModel getAuxButtonsModel() {
        return lenses;
    }
}
