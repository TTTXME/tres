package com.scamera.app.gallery.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.content.Context;
import android.net.Uri;

import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.files.MediaFile;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** P617: the text index's list: what is left to read, a new list taken by the running index, a stop. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OcrIndexTest {
    private static ImageFile file(long id, String name) {
        return new ImageFile(id, Uri.parse("content://media/external/images/media/" + id), name, 1000 + id, 1, null);
    }

    @After
    public void tearDown() {
        OcrIndex.stop();
    }

    @Test
    public void pendingSkipsWhatWasReadAndWhatIsNotAPhoto() {
        Context c = RuntimeEnvironment.getApplication();
        ImageFile read = file(1, "a.jpg"), left = file(2, "b.jpg"), video = file(3, "c.mp4"), changed = file(4, "d.jpg");
        OcrIndex.put(c, 1, read.getLastModified(), "EXIT");
        OcrIndex.put(c, 4, 1, "old"); // the photo changed since it was read
        List<MediaFile> todo = OcrIndex.pending(Arrays.asList(read, left, video, changed));
        assertEquals(Arrays.asList(left, changed), todo);
        assertEquals("exit", OcrIndex.searchText(read));
        assertNull(OcrIndex.searchText(changed));
    }

    @Test
    public void aSecondStartKeepsTheRunningIndexAndAStopEndsIt() {
        Context c = RuntimeEnvironment.getApplication();
        OcrIndex.start(c, new ArrayList<>(), null);
        Thread first = OcrIndex.runningWorker();
        assertNotNull(first);
        OcrIndex.start(c, Arrays.asList(file(9, "x.jpg")), null);
        assertSame("the new list goes to the index already running (no new start, no new delay)", first, OcrIndex.runningWorker());
        OcrIndex.stop();
        assertNull(OcrIndex.runningWorker());
    }
}
