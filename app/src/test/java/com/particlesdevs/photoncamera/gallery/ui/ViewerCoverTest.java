package com.particlesdevs.photoncamera.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P637: the viewer's placeholder is on its view the moment it is asked for (no post, no load): the shutter frame under the
 * processing pill for a shot in work, a plain picture while the photo's page is decoded; it takes no touches and recycles only
 * the bitmaps it made.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ViewerCoverTest {
    private static Bitmap bitmap() {
        return Bitmap.createBitmap(54, 72, Bitmap.Config.ARGB_8888);
    }

    @Test
    public void aPendingShotShowsItsFrameAndThePillSynchronously() {
        ViewerCover cover = new ViewerCover(RuntimeEnvironment.getApplication());
        assertEquals(ViewerCover.State.NONE, cover.state());
        assertEquals(View.GONE, cover.view.getVisibility());
        Bitmap frame = bitmap();
        final long t0 = System.nanoTime();
        cover.show(frame, true, false);
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        // nothing was posted: the placeholder is part of the view tree before the main looper runs again
        assertEquals(ViewerCover.State.PENDING, cover.state());
        assertEquals(View.VISIBLE, cover.view.getVisibility());
        assertSame(frame, ((BitmapDrawable) cover.picture.getDrawable()).getBitmap());
        assertEquals(ImageView.ScaleType.FIT_CENTER, cover.picture.getScaleType());
        assertEquals(View.VISIBLE, cover.pill.getVisibility());
        assertEquals(ViewerCover.processingText(), cover.label.getText().toString());
        assertFalse("the pages under it swipe", cover.view.isClickable());
        assertTrue("showing the placeholder is not a load: " + ms + " ms", ms < 200);
        System.out.println("P637 placeholder shown in " + ms + " ms (host)");
    }

    @Test
    public void thePillStaysUntilTheSavedPhotosPageIsReady() {
        ViewerCover cover = new ViewerCover(RuntimeEnvironment.getApplication());
        Bitmap frame = bitmap();
        cover.show(frame, true, false);
        cover.waitForPage();
        assertEquals(ViewerCover.State.PAGE, cover.state());
        assertEquals("still «Обработка» while the file is decoded", View.VISIBLE, cover.pill.getVisibility());
        assertEquals(View.VISIBLE, cover.view.getVisibility());
        cover.drop(false);
        assertEquals(ViewerCover.State.NONE, cover.state());
        assertEquals(View.GONE, cover.view.getVisibility());
        assertEquals(View.GONE, cover.pill.getVisibility());
        assertNull(cover.picture.getDrawable());
        assertFalse("the camera's bitmap is not this cover's to recycle", frame.isRecycled());
    }

    @Test
    public void aPageCoverHasNoPillAndRecyclesItsOwnCopy() {
        ViewerCover cover = new ViewerCover(RuntimeEnvironment.getApplication());
        Bitmap copy = bitmap();
        cover.show(copy, false, true);
        assertEquals(ViewerCover.State.PAGE, cover.state());
        assertEquals(View.GONE, cover.pill.getVisibility());
        cover.drop(true);
        assertEquals("the pages are the viewer's again at once (the fade is only drawn)", ViewerCover.State.NONE, cover.state());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));
        assertEquals(View.GONE, cover.view.getVisibility());
        assertTrue(copy.isRecycled());
    }

    @Test
    public void aCoverShownAgainWhileItFadesStays() {
        ViewerCover cover = new ViewerCover(RuntimeEnvironment.getApplication());
        cover.show(bitmap(), false, true);
        cover.drop(true);
        Bitmap next = bitmap();
        cover.show(next, false, true);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));
        assertEquals(ViewerCover.State.PAGE, cover.state());
        assertEquals(View.VISIBLE, cover.view.getVisibility());
        assertFalse(next.isRecycled());
        cover.destroy();
        assertTrue(next.isRecycled());
    }

    @Test
    public void noPictureIsABlackCoverWithThePill() {
        ViewerCover cover = new ViewerCover(RuntimeEnvironment.getApplication());
        cover.show(null, true, false);
        assertEquals(ViewerCover.State.PENDING, cover.state());
        assertNull(cover.picture.getDrawable());
        assertEquals(View.VISIBLE, cover.pill.getVisibility());
    }
}
