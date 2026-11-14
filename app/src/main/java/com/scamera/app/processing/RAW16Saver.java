package com.scamera.app.processing;

import android.media.Image;
import android.os.AsyncTask;
import com.scamera.app.util.Log;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;

/** Keeps the RAW frames of a burst (RAW_SENSOR, RAW10, RAW12) for the merge. */
public class RAW16Saver extends DefaultSaver {
    public RAW16Saver(ProcessingEvents listener) {
        super(listener);
    }

    @Override
    public void addImage(Image image) {
        Log.d("RAW16Saver", "frames before this one: " + FRAME_QUEUE.size());
        FRAME_QUEUE.add(getFrame(image));
        image.close();
        bufferLock = false;
    }
}
