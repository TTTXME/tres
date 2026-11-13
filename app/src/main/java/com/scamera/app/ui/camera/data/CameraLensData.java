/*
 *
 *  PhotonCamera
 *  CameraLensData.java
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

package com.scamera.app.ui.camera.data;

import androidx.annotation.NonNull;

import com.google.gson.annotations.SerializedName;


/**
 * One lens of the phone as the lens list stores it (JSON in the cameras preference file: the short names below are the
 * stored format): its camera id, facing, focal length (real and 35 mm), aperture, flash and zoom against its side's main lens.
 * Two lenses are the same module when facing, focal length, aperture and flash agree (the id and zoom do not count).
 */
public class CameraLensData {
    @SerializedName("id")
    private final String lensId;
    @SerializedName("face")
    private int lensFacing;
    @SerializedName("fl")
    private float focalMm;
    @SerializedName("ap")
    private float fNumber;
    @SerializedName("fl35")
    private float focal35Mm;
    @SerializedName("zf")
    private float zoom;
    @SerializedName("fs")
    private boolean flash;

    public CameraLensData(String id) {
        lensId = id;
    }

    public String getCameraId() { return lensId; }

    public int getFacing() { return lensFacing; }

    public void setFacing(int lensFacing) { this.lensFacing = lensFacing; }

    public float getCameraFocalLength() { return focalMm; }

    public void setCameraFocalLength(float mm) { this.focalMm = mm; }

    public float getCamera35mmFocalLength() { return focal35Mm; }

    public void setCamera35mmFocalLength(float mm) { this.focal35Mm = mm; }

    public float getZoomFactor() { return zoom; }

    public void setZoomFactor(float zoom) { this.zoom = zoom; }

    public void setCameraAperture(float fNumber) { this.fNumber = fNumber; }

    public void setFlashSupported(boolean flash) { this.flash = flash; }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof CameraLensData) || other.getClass() != getClass()) return false;
        final CameraLensData lens = (CameraLensData) other;
        return lensFacing == lens.lensFacing && flash == lens.flash
                && Float.compare(focalMm, lens.focalMm) == 0
                && Float.compare(fNumber, lens.fNumber) == 0;
    }

    /**
     * P66 (CameraManager2's camera scan): equal lens data and, with byField, 35 mm focal lengths within 1 mm, i.e. the same field
     * of view (a sensor-crop ID of the same lens is another module).
     */
    public static boolean sameLens(CameraLensData a, CameraLensData b, boolean byField) {
        if (a == null || b == null || !a.equals(b)) return false;
        return !byField || Math.abs(a.getCamera35mmFocalLength() - b.getCamera35mmFocalLength()) < 1f;
    }

    @Override
    public int hashCode() {
        int hash = lensFacing;
        hash = 31 * hash + Float.floatToIntBits(focalMm);
        hash = 31 * hash + Float.floatToIntBits(fNumber);
        return 31 * hash + (flash ? 1 : 0);
    }

    @Override
    @NonNull
    public String toString() {
        return "CameraLensData{cameraId='" + lensId + "', facing=" + lensFacing + ", cameraFocalLength=" + focalMm
                + ", cameraAperture=" + fNumber + ", camera35mmFocalLength=" + focal35Mm
                + ", zoomFactor=" + zoom + "}\n";
    }
}
