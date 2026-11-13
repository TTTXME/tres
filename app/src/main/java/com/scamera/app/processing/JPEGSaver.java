package com.scamera.app.processing;

import android.media.Image;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The camera's own JPEGs: a single-frame shot is written as it came (not processed); a burst's frames are kept, and the
 * last one of a measured burst is written as well before the burst is let go.
 */
public class JPEGSaver extends DefaultSaver {
    public JPEGSaver(ProcessingEventsListener listener) {
        super(listener);
    }

    private static Path writeJpeg(ByteBuffer jpeg) throws IOException {
        final Path file = PhotoFormat.JPEG.fileFor(ImagePath.newImageFilePath()); // the camera's own bytes: always .jpg
        final byte[] bytes = new byte[jpeg.remaining()];
        jpeg.get(bytes);
        Files.write(file, bytes);
        return file;
    }

    @Override
    public void addImage(Image image) {
        final ByteBuffer jpeg = image.getPlanes()[0].getBuffer();
        try {
            IMAGE_BUFFER.add(getFrame(image));
            final boolean single = ScameraApp.getSettings().frameCount == 1;
            if (!single && IMAGE_BUFFER.size() == ScameraApp.getCaptureController().mMeasuredFrameCnt) {
                writeJpeg(jpeg.duplicate());
                IMAGE_BUFFER.clear();
            }
            if (single) {
                IMAGE_BUFFER.clear();
                final Path file = writeJpeg(jpeg);
                image.close();
                processingEventsListener.onProcessingFinished("JPEG: Single Frame, Not Processed!");
                processingEventsListener.notifyImageSavedStatus(true, file);
            }
        } catch (IOException | NullPointerException e) {
            Log.e("JPEGSaver", "JPEG not saved: " + e);
        }
    }
}
