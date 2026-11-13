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
    public JPEGSaver(ProcessingEvents listener) {
        super(listener);
    }

    private static Path writeJpeg(ByteBuffer jpeg) throws IOException {
        final Path file = PhotoFormat.JPEG.fileFor(ImagePath.newPhotoPath()); // the camera's own bytes: always .jpg
        final byte[] bytes = new byte[jpeg.remaining()];
        jpeg.get(bytes);
        Files.write(file, bytes);
        return file;
    }

    @Override
    public void addImage(Image image) {
        final ByteBuffer jpeg = image.getPlanes()[0].getBuffer();
        try {
            FRAME_QUEUE.add(getFrame(image));
            final boolean single = ScameraApp.getSettings().frameCount == 1;
            if (!single && FRAME_QUEUE.size() == ScameraApp.getCaptureController().mMeasuredFrameCnt) {
                writeJpeg(jpeg.duplicate());
                FRAME_QUEUE.clear();
            }
            if (single) {
                FRAME_QUEUE.clear();
                final Path file = writeJpeg(jpeg);
                image.close();
                processingEventsListener.processingFinished("JPEG: Single Frame, Not Processed!");
                processingEventsListener.imageSaved(true, file);
            }
        } catch (IOException | NullPointerException e) {
            Log.e("JPEGSaver", "JPEG not saved: " + e);
        }
    }
}
