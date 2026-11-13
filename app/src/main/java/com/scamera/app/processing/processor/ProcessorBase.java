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
import com.scamera.app.processing.ProcessingEventsListener;
import com.scamera.app.processing.render.Parameters;

import java.nio.file.Path;

/**
 * Created by Vibhor Srivastava on 02/Jan/2021
 */
public abstract class ProcessorBase {
    protected final ProcessingEventsListener processingEventsListener;
    /** Where the shot's DNG and photo go. */
    protected Path dngFile;
    protected Path imageFile;
    /** The camera and the frame the shot was captured with. */
    protected CameraCharacteristics characteristics;
    protected CaptureResult captureResult;
    protected CaptureRequest captureRequest;
    protected ParseExif.ExifData exifData;
    protected int cameraRotation;
    /** Told when the processing starts, fails and ends. */
    protected ProcessingCallback callback;

    public ProcessorBase(ProcessingEventsListener listener) {
        processingEventsListener = listener;
    }

    /** Runs the processing of the shot (a processor without its own does nothing). */
    public void process() {
    }

    /** The stages of a shot's processing a saver follows. */
    public interface ProcessingCallback {
        void onStarted();

        void onFailed();

        void onFinished();
    }
}
