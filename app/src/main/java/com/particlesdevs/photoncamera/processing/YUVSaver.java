package com.particlesdevs.photoncamera.processing;

import android.media.Image;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P646 «Склейка YUV»: the frames of a YUV session's shot (a camera without RAW, or a lens with «Склейка YUV» on) are kept as the
 * RAW16Saver keeps RAWs - packed planar copies (processing/yuv/YuvFrames) - and merged by ScamProcessor's YUV route. Before P646
 * this saver dropped every frame ("YUV: Single Frame, Not Processed!") and ScamProcessor skipped the format: no photo at all.
 */
public class YUVSaver extends DefaultSaver {
    private static final String TAG = "YUVSaver";
    public YUVSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void addImage(Image image) {
        Log.d(TAG, "start buffer size:" + IMAGE_BUFFER.size());
        final ImageFrame frame = getFrame(image);
        if (frame != null) IMAGE_BUFFER.add(frame);
        image.close();
        bufferLock = false;
    }
}
