package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IszAspectCropTest {
    @Test public void nativeFourThirdsNeedsNoCropAndWideSensorKeepsAnExactEvenCenter() {
        assertEquals(new Rect(0, 0, 4096, 3072), IszAspectCrop.bounds(4096, 3072));
        assertEquals(new Rect(344, 2, 3752, 2558), IszAspectCrop.bounds(4096, 2560));
        assertEquals(new Rect(0, 6, 4080, 3066), IszAspectCrop.bounds(4080, 3072));
        assertEquals(new Rect(2, 344, 2558, 3752), IszAspectCrop.bounds(2560, 4096));
    }

    @Test public void fourThirdsBitmapIsReturnedWithoutCopying() {
        Bitmap original = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
        assertSame(original, IszAspectCrop.crop(original, IszAspectCrop.bounds(80, 60)));
        assertFalse(original.isRecycled());
        original.recycle();
    }

    @Test public void cropCopiesNativeTenBitPixelsExactlyWithoutResamplingOrChangingTheSource() {
        int width = 80, height = 50;
        int[] input = new int[width * height];
        Random random = new Random(521);
        for (int i = 0; i < input.length; i++) input[i] = 0xc0000000 | random.nextInt(1 << 30);
        ByteBuffer words = ByteBuffer.allocateDirect(input.length * 4).order(ByteOrder.nativeOrder());
        words.asIntBuffer().put(input);
        Bitmap original = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_1010102);
        original.copyPixelsFromBuffer(words);
        Rect region = IszAspectCrop.bounds(width, height);
        Bitmap cropped = IszAspectCrop.crop(original, region);
        assertEquals(Bitmap.Config.RGBA_1010102, cropped.getConfig());
        assertEquals(64, cropped.getWidth());
        assertEquals(48, cropped.getHeight());
        ByteBuffer output = ByteBuffer.allocateDirect(cropped.getByteCount()).order(ByteOrder.nativeOrder());
        cropped.copyPixelsToBuffer(output);
        output.rewind();
        int[] actual = new int[cropped.getWidth() * cropped.getHeight()];
        output.asIntBuffer().get(actual);
        for (int y = 0; y < cropped.getHeight(); y++) for (int x = 0; x < cropped.getWidth(); x++)
            assertEquals(input[(region.top + y) * width + region.left + x], actual[y * cropped.getWidth() + x]);
        assertFalse(original.isRecycled());
        cropped.recycle();
        original.recycle();
    }

    private static Bitmap opaque(int width, int height, int[] input, int seed) {
        final Random random = new Random(seed);
        for (int i = 0; i < input.length; i++) input[i] = 0xff000000 | random.nextInt(1 << 24);
        final ByteBuffer words = ByteBuffer.allocateDirect(input.length * 4).order(ByteOrder.nativeOrder());
        words.asIntBuffer().put(input);
        final Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(words);
        return b;
    }

    private static int[] pixels(Bitmap b) {
        final ByteBuffer out = ByteBuffer.allocateDirect(b.getByteCount()).order(ByteOrder.nativeOrder());
        b.copyPixelsToBuffer(out);
        out.rewind();
        final int[] px = new int[b.getWidth() * b.getHeight()];
        out.asIntBuffer().get(px);
        return px;
    }

    /** P610: what ScamPostDownscale.cropInPlace relies on: reconfigure keeps the pixel bytes and gives rows of the new width's length. */
    @Test public void reconfigureKeepsThePixelBytesAtTheNewRowLength() {
        final int width = 80, height = 50;
        final int[] input = new int[width * height];
        final Bitmap b = opaque(width, height, input, 610);
        b.reconfigure(64, 48, Bitmap.Config.ARGB_8888);
        assertEquals(64 * 4, b.getRowBytes());
        final int[] after = pixels(b);
        for (int i = 0; i < after.length; i++) assertEquals(input[i], after[i]);
        b.reconfigure(width, height, Bitmap.Config.ARGB_8888);
        assertArrayEquals(input, pixels(b));
        b.recycle();
    }

    /** P610: cropOwned gives the copy's pixels; without the native crop (here) the photo is copied and stays as it was. */
    @Test public void ownedCropGivesTheCopysPixels() {
        final int width = 80, height = 50;
        final int[] input = new int[width * height];
        final Bitmap original = opaque(width, height, input, 611);
        final Rect region = IszAspectCrop.bounds(width, height);
        final Bitmap owned = IszAspectCrop.cropOwned(original, region);
        assertEquals(region.width(), owned.getWidth());
        assertEquals(region.height(), owned.getHeight());
        final int[] actual = pixels(owned);
        for (int y = 0; y < owned.getHeight(); y++) for (int x = 0; x < owned.getWidth(); x++)
            assertEquals(input[(region.top + y) * width + region.left + x], actual[y * owned.getWidth() + x]);
        if (owned != original) {
            assertEquals(width, original.getWidth());
            assertEquals(height, original.getHeight());
            assertArrayEquals(input, pixels(original));
            owned.recycle();
        }
        original.recycle();
        final Bitmap whole = opaque(width, 60, new int[width * 60], 612);
        assertSame(whole, IszAspectCrop.cropOwned(whole, IszAspectCrop.bounds(width, 60)));
        whole.recycle();
    }
}
