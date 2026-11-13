/*
 *
 *  PhotonCamera
 *  TopBarSettingsData.java
 *  Copyright (C) 2020 - 2021  Vibhor Srivastava
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

/** A camera control's change as the shade reports it: which control ({@code type}, a SettingType) and its new value. */
public class TopBarSettingsData<T, V extends Comparable<? super V>> {
    private final T type;
    private V value; // null until the control reports one

    public TopBarSettingsData(T control) {
        this(control, null);
    }

    public TopBarSettingsData(T control, V initial) {
        type = control;
        value = initial;
    }

    public T getType() {
        return type;
    }

    public V getValue() {
        return value;
    }

    public void setValue(V now) {
        value = now;
    }
}
