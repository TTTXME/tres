package com.scamera.app.processing;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.scamera.app.util.Log;
import com.scamera.app.api.ParseExif;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.control.GyroBurst;
import com.scamera.app.processing.processor.ScamProcessor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

public class DefaultSaver extends SaverImplementation {
    private static final String TAG = "DefaultSaver";
    final ScamProcessor scamProcessor;
    ArrayList<ImageFrame> ownedFrames;
    Path ownedDngFile, ownedImageFile;
    /** P618: the queued shot's wall clock (when its files were named), 0 = not queued. */
    long ownedShotTimeMs;
    java.util.List<com.scamera.app.processing.parameters.IsoExpoSelector.ExpoPair> ownedPairs;
    /** P27: the merge route the shot was captured for (null: read the setting when processing starts). */
    Boolean ownedHybridRoute;
    Boolean ownedIszAspect4x3;
    /** P190: the FULLRES series of the queued shot (ImageSaver.fullResSeries / fullResRequested). */
    int ownedFullResSeries, ownedFullResRequested;
    /** P320: the queued shot is a DCG shot (ImageSaver.dcgShot). */
    boolean ownedDcgShot;
    /** P212f (W2 = M3): the hand-set white balance's gains at the press of the queued shot (null: automatic). */
    android.hardware.camera2.params.RggbChannelVector ownedManualWb;
    /** P212g (W6 = M6): the mosaic white balance's route at the press of the queued shot (null: none). */
    com.scamera.app.processing.color.MosaicNeutralResolver.Press ownedMosaicWb;
    /** The queued shot's digital-zoom crop (null: not queued) and camera at the press (ImageSaver.zoomResidual / cameraIdAtPress). */
    Float ownedZoomResidual;
    String ownedCameraId;
    /** The queued shot's capture plan (ImageSaver.planNote). */
    String ownedPlanNote;
    /** The queued shot's lens filter at the press (ImageSaver.lensFilterAtPress). */
    com.scamera.app.processing.opengl.postpipeline.LensFilter.Choice ownedLensFilter;
    /** The queued shot's film, LUT and «Цвет» at the press (ImageSaver.lookAtPress). */
    com.scamera.app.processing.opengl.postpipeline.ShotLook ownedLook;
    com.scamera.app.settings.MotionMode.Snapshot ownedMotion;

    public DefaultSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
        this.scamProcessor = new ScamProcessor(processingEventsListener);
    }

    public void runRaw(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        super.runRaw(imageFormat, characteristics, captureResult,captureRequest, burstShakiness, cameraRotation, exposures);
        scamProcessor.ownedIszAspect4x3 = ownedIszAspect4x3 != null ? ownedIszAspect4x3 : iszAspect4x3;
        ownedIszAspect4x3 = null;
        scamProcessor.ownedZoomResidual = ownedZoomResidual != null ? ownedZoomResidual : zoomResidual;
        scamProcessor.ownedCameraId = ownedFrames != null ? ownedCameraId : cameraIdAtPress;
        scamProcessor.ownedPlanNote = ownedFrames != null ? ownedPlanNote : planNote;
        scamProcessor.ownedLensFilter = ownedFrames != null ? ownedLensFilter : lensFilterAtPress;
        scamProcessor.ownedLook = ownedFrames != null ? ownedLook : lookAtPress;
        scamProcessor.ownedMotion = ownedFrames != null ? ownedMotion : motionAtPress;
        ownedZoomResidual = null;
        ownedCameraId = null;
        ownedPlanNote = null;
        ownedLensFilter = null;
        ownedLook = null;
        ownedMotion = null;
        if (ownedFrames != null) {
            // Queued shot: frames and exposure pairs were detached from the
            // shared buffers on the camera thread when the burst completed.
            ArrayList<ImageFrame> frames = ownedFrames;
            ownedFrames = null;
            scamProcessor.ownedPairs = ownedPairs;
            scamProcessor.ownedHybridRoute = ownedHybridRoute;
            ownedHybridRoute = null;
            scamProcessor.ownedFullResSeries = ownedFullResSeries;
            scamProcessor.ownedFullResRequested = ownedFullResRequested;
            ownedFullResSeries = ownedFullResRequested = 0;
            scamProcessor.ownedDcgShot = ownedDcgShot;
            ownedDcgShot = false;
            scamProcessor.ownedManualWb = ownedManualWb;
            ownedManualWb = null;
            scamProcessor.ownedMosaicWb = ownedMosaicWb;
            ownedMosaicWb = null;
            scamProcessor.configure(ScameraApp.getSettings().alignAlgorithm,
                    ScameraApp.getSettings().rawSaver, ScameraApp.getSettings().selectedMode);
            final long shotTime = ownedShotTimeMs > 0 ? ownedShotTimeMs : System.currentTimeMillis();
            ownedShotTimeMs = 0;
            scamProcessor.start(ownedDngFile, ownedImageFile,
                    ParseExif.parse(captureResult, captureRequest, shotTime), burstShakiness, frames, exposures,
                    imageFormat, cameraRotation, characteristics, captureResult, captureRequest,
                    processingCallback);
            return;
        }
        // at least one frame, and the capture thread done with the buffer (P659: yielding instead of a bare spin)
        Log.d(TAG, "waiting for frames, " + IMAGE_BUFFER.size() + " so far");
        while (bufferLock || IMAGE_BUFFER.isEmpty()) Thread.yield();
        bufferLock = true;
        Log.d(TAG, "frames: " + IMAGE_BUFFER.size());
        final long shotTime = System.currentTimeMillis(); // P618: the name's instant is the shot's EXIF / DNG time
        final String name = ImagePath.newBaseName(shotTime); // one name for both (P119)
        final Path dngFile = ImagePath.dngFile(name);
        final Path imageFile = ImagePath.imageFile(name);
        scamProcessor.configure(ScameraApp.getSettings().alignAlgorithm, ScameraApp.getSettings().rawSaver,
                ScameraApp.getSettings().selectedMode);
        // this shot's frames out of the shared buffer (the next shot's stay); the capture may add again from here
        final ArrayList<ImageFrame> buffered = IMAGE_BUFFER;
        final ArrayList<ImageFrame> shot = new ArrayList<>(buffered.subList(0, frameCount));
        IMAGE_BUFFER = new ArrayList<>(buffered.subList(frameCount, buffered.size()));
        buffered.clear();
        bufferLock = false;
        shot.removeIf(java.util.Objects::isNull);
        Log.d(TAG, "processing " + shot.size() + " frames");
        scamProcessor.start(dngFile, imageFile, ParseExif.parse(captureResult, captureRequest, shotTime), burstShakiness, shot,
                exposures, imageFormat, cameraRotation, characteristics, captureResult, captureRequest, processingCallback);
        shot.clear();
    }


}
