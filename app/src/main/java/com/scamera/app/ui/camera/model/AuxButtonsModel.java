/*
 *
 *  PhotonCamera
 *  AuxButtonsModel.java
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

package com.scamera.app.ui.camera.model;

import androidx.databinding.BaseObservable;
import androidx.databinding.Bindable;

import com.scamera.app.BR;
import com.scamera.app.ui.camera.data.CameraLensData;
import com.scamera.app.ui.camera.views.AuxButtonsLayout;

import java.util.List;

/**
 * The lens strip's data: the front and back lenses (sorted by the view model), who hears a lens tap, whether taps count now
 * (not during a burst), and the camera in use - the one bound property (camera_bottom_bar's setActiveId).
 */
public class AuxButtonsModel extends BaseObservable {
    private List<CameraLensData> front, back;
    private String activeCamera;
    private AuxButtonsLayout.AuxButtonListener taps;
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    public AuxButtonsLayout.AuxButtonListener getAuxButtonListener() {
        return taps;
    }

    public void setAuxButtonListener(AuxButtonsLayout.AuxButtonListener listener) {
        taps = listener;
    }

    public List<CameraLensData> getFrontCameras() {
        return front;
    }

    public void setFrontCameras(List<CameraLensData> lenses) {
        front = lenses;
    }

    public List<CameraLensData> getBackCameras() {
        return back;
    }

    public void setBackCameras(List<CameraLensData> lenses) {
        back = lenses;
    }

    @Bindable
    public String getCurrentCameraId() {
        return activeCamera;
    }

    public void setCurrentCameraId(String id) {
        activeCamera = id;
        notifyPropertyChanged(BR.currentCameraId);
    }
}
