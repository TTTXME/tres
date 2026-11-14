package com.scamera.app.processing.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.scamera.app.processing.ProcessingEvents;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * P681: the order in which the camera screen and the media scanner hear of a shot's DNG (DngReport). A DNG beside a photo never
 * reaches the gallery card (its thumbnail was decoded from the whole DNG, then replaced by the photo's) and is scanned once,
 * after the photo and its preview; a DNG that is the shot is reported as before; a shot that ends without its photo reports
 * its saved DNG.
 */
public class DngReportTest {
    private static final Path DNG = Paths.get("SCA_1.dng");

    /** The events and scans in the order they came. */
    static final class Trace implements ProcessingEvents {
        final List<String> log = new ArrayList<>();

        @Override public void processingStarted(String name) {}
        @Override public void processingProgress(Object what) { if (what instanceof String) log.add("notice"); }
        @Override public void processingFinished(Object what) {}
        @Override public void imageSaved(boolean saved, Path file) { log.add("saved " + saved + " " + file); }
        @Override public void processingFailed(Object what) {}

        void scan(Path p) { log.add("scan " + p); }
    }

    private static DngReport report(Trace t, int rawSaving, boolean mosaicOnly) {
        return new DngReport(t, t::scan, DNG, rawSaving, mosaicOnly);
    }

    @Test public void aDngBesideAPhotoIsScannedOnceAfterThePhoto() {
        final Trace t = new Trace();
        final DngReport r = report(t, 1, false);
        assertTrue(r.photoFollows);
        r.written(true);
        assertEquals("nothing on the card or in the scanner at the write", 0, t.log.size());
        t.imageSaved(true, Paths.get("SCA_1.jpg")); // the photo (ScamProcessor's events.imageSaved)
        r.photoSaved();
        r.scan(); // after the DNG's preview (and its develop record)
        r.scan();
        r.shotFailed();
        assertEquals("[saved true SCA_1.jpg, scan SCA_1.dng]", t.log.toString());
    }

    @Test public void aFailedDngBesideAPhotoIsANoticeAndNeverScanned() {
        final Trace t = new Trace();
        final DngReport r = report(t, 1, false);
        r.written(false);
        r.photoSaved();
        r.scan();
        r.shotFailed();
        assertEquals("[notice]", t.log.toString());
        assertFalse(r.saved());
    }

    @Test public void aDngThatIsTheShotIsReportedAtItsWrite() {
        for (int[] c : new int[][]{{2, 0}, {1, 1}}) {
            final Trace t = new Trace();
            final DngReport r = report(t, c[0], c[1] == 1);
            assertFalse(r.photoFollows);
            r.written(true);
            r.photoSaved();
            r.scan(); // the screen's imageSaved scanned it already
            r.shotFailed();
            assertEquals("[saved true SCA_1.dng]", t.log.toString());
        }
    }

    @Test public void aShotWithoutItsPhotoReportsItsSavedDng() {
        final Trace t = new Trace();
        final DngReport r = report(t, 1, false);
        r.written(true);
        r.shotFailed();
        r.shotFailed();
        r.scan();
        assertEquals("[saved true SCA_1.dng]", t.log.toString());
    }

    @Test public void theWriteGoesBesideThePostOnlyWithRoomForIt() {
        final long gb = 1L << 30;
        assertTrue("50 MP on a 12 GB phone", DngReport.offPostPath(true, true, 50_000_000L, 11 * gb));
        assertFalse("switched off", DngReport.offPostPath(false, true, 50_000_000L, 11 * gb));
        assertFalse("no photo: nothing to overlap", DngReport.offPostPath(true, false, 50_000_000L, 11 * gb));
        assertFalse("200 MP: synchronous", DngReport.offPostPath(true, true, 200_000_000L, 16 * gb));
        assertFalse("8 GB: synchronous", DngReport.offPostPath(true, true, 12_000_000L, 8 * gb));
        assertFalse("an 8 GB phone (7.4 GiB reported)", DngReport.offPostPath(true, true, 12_000_000L, 7_900_000_000L));
        assertFalse("RAM unknown", DngReport.offPostPath(true, true, 12_000_000L, 0));
    }

    @Test public void theReferenceRawIsCopiedOutOnlyWhenRead() {
        assertFalse("a hybrid shot with its merged DNG", ScamProcessor.referenceRawNeeded(false, true, true, false));
        assertTrue("the reference frame's DNG", ScamProcessor.referenceRawNeeded(true, true, true, false));
        assertTrue("post_ab", ScamProcessor.referenceRawNeeded(false, true, true, true));
        assertTrue("not a hybrid shot", ScamProcessor.referenceRawNeeded(false, false, true, false));
        assertTrue("no owned RGB", ScamProcessor.referenceRawNeeded(false, true, false, false));
    }
}
