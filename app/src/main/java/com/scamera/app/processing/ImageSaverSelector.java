package com.scamera.app.processing;

import android.graphics.ImageFormat;
import com.scamera.app.processing.yuv.YuvFrames;
import com.scamera.app.util.Log;

/** The saver of each image format the capture delivers (one per format, made with the processing listener: init). */
public class ImageSaverSelector {
    private static final String TAG = "ImageSaverSelector";
    private static JPEGSaver jpeg;
    private static YUVSaver yuv;
    private static RAW16Saver raw;

    public static void init(SaverImplementation base) {
        jpeg = new JPEGSaver(base.processingEventsListener);
        yuv = new YUVSaver(base.processingEventsListener);
        raw = new RAW16Saver(base.processingEventsListener);
    }

    /**
     * The saver of {@code format}; an unknown format keeps {@code current} (logged). P650: the YUV route's 10-bit frames
     * (YCBCR_P010, YuvFrames.P010) take the 8-bit ones' saver - they fell through as "unexpected image format:54" and the
     * after-shutter P010 series never reached the YUV merge.
     */
    public static SaverImplementation getImageSaver(int format, SaverImplementation current) {
        if (format == ImageFormat.JPEG) return jpeg;
        if (format == ImageFormat.YUV_420_888 || format == YuvFrames.P010) return yuv;
        if (format == ImageFormat.RAW10 || format == ImageFormat.RAW12 || format == ImageFormat.RAW_SENSOR) {
            Log.d(TAG, "Selected RAW16Saver for format: " + format);
            return raw;
        }
        Log.e(TAG, "Cannot save image, unexpected image format:" + format);
        return current;
    }
}
