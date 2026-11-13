package com.scamera.app.processing.processor;
/*
 * Abstract Class defining base for an image processor class.
 *
 *         Copyright (C) 2020-2021  Eszdman
 *         This program is free software: you can redistribute it and/or modify
 *         it under the terms of the GNU General Public License as published by
 *         the Free Software Foundation, either version 3 of the License, or
 *         (at your option) any later version.
 *
 *         This program is distributed in the hope that it will be useful,
 *         but WITHOUT ANY WARRANTY; without even the implied warranty of
 *         MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *         GNU General Public License for more details.
 *
 *         You should have received a copy of the GNU General Public License
 *         along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.scamera.app.api.ParseExif;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.ProcessingEvents;
import com.scamera.app.processing.render.Parameters;

import java.nio.file.Path;

/**
 * Created by Vibhor Srivastava on 02/Jan/2021
 */
public abstract class ProcessorBase {
    /** Told about the processing's progress and the saved file. */
    protected final ProcessingEvents events;
    /** Told when the processing starts, fails and ends. */
    protected ProcessingCallback stages;
    // the shot: where its DNG and photo go, the camera and the frame it was captured with, its EXIF and rotation
    protected Path dngPath, photoPath;
    protected CameraCharacteristics shotCamera;
    protected CaptureResult shotResult;
    protected CaptureRequest shotRequest;
    protected ParseExif.ExifData shotExif;
    protected int shotRotation;

    public ProcessorBase(ProcessingEvents events) {
        this.events = events;
    }

    /** Runs the processing of the shot (a processor without its own does nothing). */
    public void process() {
    }

    /** The stages of a shot's processing a saver follows. */
    public interface ProcessingCallback {
        void started();

        void failed();

        void finished();
    }
}
