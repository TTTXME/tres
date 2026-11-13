package com.scamera.app.processing.processor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Point;
import android.graphics.Rect;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.processing.RawCrop;
import com.scamera.app.processing.RingFrame;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.util.Allocator;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * P576 (owner, 2026-11-09: «У нас при цифровом кропе должны работать алгоритмы суперразрешения Sabre»): a crop shot's burst is
 * cut to the crop's window before the merge. The window keeps the frame's colour phase (its origin on whole colour periods), is
 * centred (its sides frame - 2 x origin), holds the crop, and is merged on the grid its photo needs; the photo's framing and size
 * are today's digital zoom result; the lens shading map, the hot pixels and the faces follow the window.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = SabreCropTest.HeapAllocator.class,
        instrumentedPackages = "com.scamera.app.util")
public class SabreCropTest {
    /** Native memory as Java direct buffers; no shot arena (the cut falls back to plain buffers). */
    @Implements(Allocator.class)
    public static class HeapAllocator {
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static ByteBuffer allocate(int capacity) {
            return ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
        }
        @Implementation protected static void free(ByteBuffer buffer) { }
        @Implementation protected static int arenaCreate(long bytes) { return 0; }
    }

    private static final SabreCrop.Limits PHONE = new SabreCrop.Limits(16384, true, -1);

    @Test public void theWindowsOriginIsOnWholeColourPeriods() {
        assertEquals(2, SabreCrop.period(1));
        assertEquals(4, SabreCrop.period(2));
        assertEquals(8, SabreCrop.period(4));
        assertEquals("an unknown block takes Tetra's period (Bayer and Quad divide it)", 8, SabreCrop.period(0));
    }

    /** Every frame, block and zoom: origin on the period, symmetric even sides, the crop inside, at most a period a side more. */
    @Test public void theWindowIsCentredAndHoldsTheCrop() {
        final int[][] frames = {{4096, 3072}, {4080, 3072}, {8192, 6144}, {4000, 3000}, {9248, 6944}, {1024, 768}};
        for (int[] f : frames)
            for (int block : new int[]{1, 2, 4, 0})
                for (double z = 1.006; z <= 8.0; z += 0.0137) {
                    final int p = SabreCrop.period(block);
                    final RawCrop.Area a = SabreCrop.window(f[0], f[1], z, p);
                    final String at = f[0] + "x" + f[1] + " block " + block + " zoom " + z;
                    if (a == null) continue;
                    assertEquals(at, 0, a.x % p);
                    assertEquals(at, 0, a.y % p);
                    assertEquals(at + ": symmetric", f[0], 2 * a.x + a.width);
                    assertEquals(at + ": symmetric", f[1], 2 * a.y + a.height);
                    assertEquals(0, a.width & 1);
                    assertEquals(0, a.height & 1);
                    final double cw = f[0] / z, ch = f[1] / z;
                    assertTrue(at + ": holds the crop", a.width >= cw - 1e-6 && a.height >= ch - 1e-6);
                    assertTrue(at + ": at most a period a side more", a.width <= cw + 2 * p + 1e-6 && a.height <= ch + 2 * p + 1e-6);
                }
    }

    @Test public void noCropNoWindow() {
        assertNull(SabreCrop.window(4096, 3072, 1.0, 2));
        assertNull("the zoom's own threshold (ScamProcessor: above 1.005f)", SabreCrop.window(4096, 3072, 1.005f, 2));
        assertNull("a crop smaller than a period a side keeps the frame", SabreCrop.window(1024, 768, 1.006, 8));
        assertNull(SabreCrop.plan(4096, 3072, 1f, 1, true, new Point(4096, 3072), PHONE).plan);
        assertNull("a window under 64 px", SabreCrop.window(512, 384, 8.5, 2));
    }

    /** The owner's examples: a 2x zoom of a 12 MP Bayer -> 3 MP window -> 2x grid -> 12 MP; nubia 50 MP Quad 2x -> 12.5 -> 50 MP. */
    @Test public void aTwoTimesCropIsMergedOnTheSabre2xGridAtThePhotoSize() {
        SabreCrop.Plan bayer = SabreCrop.plan(4096, 3072, 2f, 1, true, new Point(4096, 3072), PHONE).plan;
        assertNotNull(bayer);
        assertEquals(new RawCrop.Area(1024, 768, 2048, 1536), bayer.area);
        assertEquals(4096, bayer.outWidth);
        assertEquals(3072, bayer.outHeight);
        assertTrue(bayer.sabre());
        assertNull("nothing left to crop", SabreCrop.cropRect(4096, 3072, bayer));
        SabreCrop.Plan nubia = SabreCrop.plan(8192, 6144, 2f, 2, true, new Point(8192, 6144), PHONE).plan;
        assertNotNull(nubia);
        assertEquals(new RawCrop.Area(2048, 1536, 4096, 3072), nubia.area);
        assertEquals(8192, nubia.outWidth);
        assertEquals(6144, nubia.outHeight);
        // Tetra 4080: the origin goes down to 8 px (1016), the window 2048 wide holds the 2040 crop
        SabreCrop.Plan tetra = SabreCrop.plan(4080, 3072, 2f, 4, true, new Point(4080, 3072), PHONE).plan;
        assertEquals(new RawCrop.Area(1016, 768, 2048, 1536), tetra.area);
        assertEquals(4096, tetra.outWidth);
        Rect rest = SabreCrop.cropRect(tetra.outWidth, tetra.outHeight, tetra);
        assertEquals("the 2040 sensor px of the crop on the 2x grid", 4080, rest.width());
        assertEquals(3072, rest.height());
        assertEquals("centred", 8, rest.left);
    }

    /** A zoom between 1x and 2x: the window's Sabre scale is the photo's (no merge above it), its crop the photo's size exactly. */
    @Test public void aScaleBetweenOneAndTwoGivesThePhotoSizeWithoutAResample() {
        SabreCrop.Plan p = SabreCrop.plan(4096, 3072, 1.3f, 1, true, new Point(4096, 3072), PHONE).plan;
        assertNotNull(p);
        assertEquals(new RawCrop.Area(472, 354, 3152, 2364), p.area);
        assertEquals(0, p.outWidth % 4);
        assertEquals(0, p.outHeight % 2);
        assertTrue(p.outWidth >= p.area.width && p.outWidth <= 2 * p.area.width);
        assertTrue("about the photo's size", Math.abs(p.outWidth - 4096) <= 8 && Math.abs(p.outHeight - 3072) <= 4);
        Rect rest = SabreCrop.cropRect(p.outWidth, p.outHeight, p);
        assertEquals(4096, rest.width());
        assertEquals(3072, rest.height());
        // a photo below the crop (a resize target): the sensor grid, merged smaller
        SabreCrop.Plan small = SabreCrop.plan(4096, 3072, 1.3f, 1, true, new Point(2048, 1536), PHONE).plan;
        assertFalse(small.sabre());
    }

    /**
     * «Увеличивать кадр с кропом» off: the window on the sensor grid and the crop of it - the same size and the same sensor
     * pixels as today's crop of the whole merge (ZoomController.crop), for every zoom.
     */
    @Test public void withoutUpscaleThePhotoIsTodaysCropPixelForPixel() {
        for (int[] f : new int[][]{{4096, 3072}, {4080, 3072}, {8192, 6144}})
            for (float z = 1.01f; z < 6f; z += 0.031f) {
                SabreCrop.Plan p = SabreCrop.plan(f[0], f[1], z, 1, false, new Point(f[0], f[1]), PHONE).plan;
                if (p == null) continue;
                assertFalse(p.sabre());
                assertNull(p.target);
                Rect rest = SabreCrop.cropRect(p.outWidth, p.outHeight, p);
                // today: ZoomController.crop of the f[0] x f[1] merge
                final int w = Math.max(2, Math.round(f[0] / z) & ~1), h = Math.max(2, Math.round(f[1] / z) & ~1);
                final int x = (f[0] - w) / 2, y = (f[1] - h) / 2;
                final int rw = rest == null ? p.area.width : rest.width(), rh = rest == null ? p.area.height : rest.height();
                final int rx = rest == null ? 0 : rest.left, ry = rest == null ? 0 : rest.top;
                assertEquals("zoom " + z, w, rw);
                assertEquals("zoom " + z, h, rh);
                assertEquals("zoom " + z + ": the same sensor columns", x, p.area.x + rx);
                assertEquals("zoom " + z + ": the same sensor rows", y, p.area.y + ry);
            }
    }

    /** With the upscale: the remaining crop is centred on the frame's centre within half a pixel of the merged grid. */
    @Test public void theFramingIsTodaysWithinAPixel() {
        for (int block : new int[]{1, 2, 4})
            for (float z = 1.02f; z < 7f; z += 0.047f) {
                SabreCrop.Plan p = SabreCrop.plan(4080, 3072, z, block, true, new Point(4080, 3072), PHONE).plan;
                if (p == null) continue;
                Rect rest = SabreCrop.cropRect(p.outWidth, p.outHeight, p);
                final double sx = p.outWidth / (double) p.area.width, sy = p.outHeight / (double) p.area.height;
                final double l = rest == null ? 0 : rest.left, t = rest == null ? 0 : rest.top;
                final double w = rest == null ? p.outWidth : rest.width(), h = rest == null ? p.outHeight : rest.height();
                // the photo's centre in sensor px
                final double cx = p.area.x + (l + w / 2) / sx, cy = p.area.y + (t + h / 2) / sy;
                assertEquals("zoom " + z + " block " + block, 2040, cx, 0.5 / sx + 1e-9);
                assertEquals("zoom " + z + " block " + block, 1536, cy, 0.5 / sy + 1e-9);
                // the photo's field: the crop's, within a merged pixel a side
                // (the rounding to even px, and the photo's own size taken when within SNAP_PX)
                assertEquals(4080 / z, w / sx, 3.6 / sx);
                assertEquals(3072 / z, h / sy, 3.6 / sy);
            }
    }

    /** The bitmap may come turned by 90 degrees (the pipeline's rotation): the crop's factors and the photo's size turn with it. */
    @Test public void aTurnedBitmapTakesTheTurnedCrop() {
        SabreCrop.Plan p = SabreCrop.plan(4080, 3072, 2f, 4, true, new Point(4080, 3072), PHONE).plan;
        Rect straight = SabreCrop.cropRect(p.outWidth, p.outHeight, p);
        Rect turned = SabreCrop.cropRect(p.outHeight, p.outWidth, p);
        assertEquals(straight.width(), turned.height());
        assertEquals(straight.height(), turned.width());
        assertEquals(new Point(3072, 4080), SabreCrop.target(p.outHeight, p.outWidth, p));
        assertEquals(new Point(4080, 3072), SabreCrop.target(p.outWidth, p.outHeight, p));
    }

    /** A window whose grid does not fit keeps the whole frame (today's path) and says why. */
    @Test public void aWindowThatDoesNotFitKeepsTheWholeFrame() {
        // nubia 50 MP at 1.1x on the SCAM route: its 2x grid 165 MP
        SabreCrop.Decision scam = SabreCrop.plan(8192, 6144, 1.1f, 2, true, new Point(8192, 6144), new SabreCrop.Limits(16384, false, -1));
        assertNull(scam.plan);
        assertTrue(scam.why, scam.why.contains("MP grid above"));
        // the fast route merges at the photo's scale itself: 50 MP, as the uncropped shot
        assertNotNull(SabreCrop.plan(8192, 6144, 1.1f, 2, true, new Point(8192, 6144), PHONE).plan);
        SabreCrop.Decision side = SabreCrop.plan(4096, 3072, 2f, 1, true, new Point(4096, 3072), new SabreCrop.Limits(4000, true, -1));
        assertNull(side.plan);
        assertTrue(side.why, side.why.contains("GPU side"));
        SabreCrop.Decision memory = SabreCrop.plan(4096, 3072, 2f, 1, true, new Point(4096, 3072), new SabreCrop.Limits(16384, true, 100L << 20));
        assertNull(memory.plan);
        assertTrue(memory.why, memory.why.contains("memory"));
        // a Quad window under 64 binned px a side
        assertNull(SabreCrop.plan(1024, 768, 7f, 2, true, new Point(1024, 768), PHONE).plan);
    }

    /** The photo a crop is brought to: the uncropped photo's size (P226), as ScamProcessor gives it today. */
    @Test public void thePhotoSizeIsTheUncroppedPhotos() {
        assertEquals(new Point(4096, 3072), SabreCrop.uncroppedPhoto(4096, 3072, "sensor", true, new Point(4096, 3072), new Point(4096, 3072)));
        assertEquals(new Point(8192, 6144), SabreCrop.uncroppedPhoto(4096, 3072, "2x", true, new Point(8192, 6144), new Point(8192, 6144)));
        assertEquals("2x -> 12 MP", new Point(4032, 3024), SabreCrop.uncroppedPhoto(4096, 3072, "2x", true, new Point(8192, 6144), new Point(4032, 3024)));
        assertEquals("the 2x grid does not fit: the sensor grid", new Point(8192, 6144),
                SabreCrop.uncroppedPhoto(8192, 6144, "2x", false, new Point(16384, 12288), new Point(16384, 12288)));
        // 2x output: the window on the 2x grid, then enlarged to the 2x photo
        SabreCrop.Plan p = SabreCrop.plan(4096, 3072, 2f, 1, true, new Point(8192, 6144), PHONE).plan;
        assertEquals(4096, p.outWidth);
        assertNull(SabreCrop.cropRect(p.outWidth, p.outHeight, p));
    }

    /** An uncropped shot (residual 1, or at the zoom's 1.005 threshold) is not touched: no plan, the frames and their buffers as they were. */
    @Test public void anUncroppedShotIsLeftAlone() {
        List<ImageFrame> frames = new ArrayList<>();
        frames.add(frame(256, 192, 1));
        final ByteBuffer before = frames.get(0).buffer;
        for (float residual : new float[]{1f, 1.005f, 0.5f, Float.NaN}) {
            assertNull(SabreCrop.cut(frames, null, residual, true, 16384));
            assertTrue(before == frames.get(0).buffer);
            assertEquals(256, frames.get(0).width);
            assertEquals(192, frames.get(0).height);
        }
    }

    /** A synthetic frame: phase (x &amp; 1, y &amp; 1) of every 2 x block cell has its own black, the rest a ramp. */
    private static int sample(int x, int y, int block) {
        final int phase = ((y / block) & 1) * 2 + ((x / block) & 1);
        return 60 + phase * 3 + ((x * 7 + y * 13) % 5) * 4;
    }

    private static ImageFrame frame(int w, int h, int block) {
        ImageFrame f = ImageFrame.deferred((RingFrame) null, 0x20, w, w * 2, 0, w * h * 2);
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer s = b.asShortBuffer();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) s.put(y * w + x, (short) sample(x, y, block));
        f.buffer = b;
        f.width = w;
        f.height = h;
        return f;
    }

    /**
     * The cut frames keep the frame's colour phase: every window site has the colour (and the black) of its frame site for Bayer,
     * Quad and Tetra; the per-phase black levels stay the frame's. An origin off the period (odd) would swap the phases.
     */
    @Test public void theCutKeepsEveryCfaPhaseAndItsBlack() throws Exception {
        for (int block : new int[]{1, 2, 4}) {
            final int w = 1024, h = 768;
            List<ImageFrame> frames = new ArrayList<>();
            for (int i = 0; i < 2; i++) frames.add(frame(w, h, block));
            SabreCrop.Plan p = SabreCrop.plan(w, h, 2.3f, block, true, new Point(w, h), PHONE).plan;
            assertNotNull(p);
            RawCrop.apply(frames, w, h, p.area);
            for (ImageFrame f : frames) {
                assertEquals(p.area.width, f.width);
                assertEquals(p.area.height, f.height);
                ShortBuffer s = f.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                final double[] mean = new double[4], count = new double[4];
                for (int y = 0; y < f.height; y++)
                    for (int x = 0; x < f.width; x++) {
                        assertEquals(sample(x + p.area.x, y + p.area.y, block), s.get(y * f.width + x));
                        final int phase = ((y / block) & 1) * 2 + ((x / block) & 1);
                        mean[phase] += s.get(y * f.width + x) - ((x + p.area.x) * 7 + (y + p.area.y) * 13) % 5 * 4;
                        count[phase]++;
                    }
                for (int c = 0; c < 4; c++) assertEquals("block " + block + " phase " + c + ": its own black", 60 + c * 3, mean[c] / count[c], 1e-9);
            }
            // the negative: an origin one colour site off the period reads phase 1's black (63) where phase 0's (60) belongs
            final int x = p.area.x + block, y = p.area.y;
            assertEquals(63, sample(x, y, block) - (x * 7 + y * 13) % 5 * 4);
        }
    }

    /** The lens shading map (the window's part, the same grid), the hot pixels and the face boxes follow the window. */
    @Test public void theParametersFollowTheWindow() {
        final com.scamera.app.api.Settings settings = new com.scamera.app.api.Settings();
        settings.cameraId = "0";
        try (org.mockito.MockedStatic<com.scamera.app.app.ScameraApp> app =
                     org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class)) {
            app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(settings);
            parametersFollowTheWindow();
        }
    }

    private void parametersFollowTheWindow() {
        Parameters p = new Parameters();
        p.rawSize = new Point(400, 300);
        final int cols = 5, rows = 4;
        p.shadingGrid = new Point(cols, rows);
        p.shadingMap = new float[cols * rows * 4];
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++)
                for (int c = 0; c < 4; c++) p.shadingMap[(j * cols + i) * 4 + c] = 1f + i * 0.25f + j * 0.5f + c;
        p.hotSites = new Point[]{new Point(10, 10), new Point(150, 120), new Point(399, 300)};
        p.portraitFaces = new float[]{0.4f, 0.4f, 0.6f, 0.6f};
        SabreCrop.Plan plan = SabreCrop.plan(400, 300, 2f, 1, false, null, PHONE).plan;
        RawCrop.Area a = plan.area;
        assertEquals(new RawCrop.Area(100, 74, 200, 152), a);
        p.applySabreCrop(plan);
        assertEquals(new Point(200, 152), p.rawSize);
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++) {
                // the map is linear in x and y, so its resampling is exact: the frame's map at the window's grid points
                final double x = (100 + (double) i / (cols - 1) * 199) / 399 * (cols - 1);
                final double y = (74 + (double) j / (rows - 1) * 151) / 299 * (rows - 1);
                assertEquals(1f + x * 0.25f + y * 0.5f, p.shadingMap[(j * cols + i) * 4], 1e-4);
            }
        assertArrayEquals(new Point[]{new Point(50, 46)}, p.hotSites);
        assertEquals((0.4f * 400 - 100) / 200f, p.portraitFaces[0], 1e-4f);
        assertEquals(a, p.rawCrop);
        assertEquals(new Point(400, 300), p.rawCropFull);
        assertEquals(plan, p.sabreCrop);
        // on top of P162's RAW crop: the window in the frame as captured
        Parameters q = new Parameters();
        q.rawSize = new Point(4000, 3000);
        q.rawCrop = new RawCrop.Area(0, 0, 4000, 3000);
        q.rawCropFull = new Point(4096, 3072);
        q.applySabreCrop(SabreCrop.plan(4000, 3000, 2f, 1, true, new Point(4000, 3000), PHONE).plan);
        assertEquals(new RawCrop.Area(1000, 750, 2000, 1500), q.rawCrop);
        assertEquals(new Point(4096, 3072), q.rawCropFull);
    }

    /** The processor cuts before the merge, after the whole frames' levels and colour, and crops what remains after the pipeline. */
    @Test public void theProcessorCutsBeforeTheMergeAndCropsTheRestAfter() throws Exception {
        String s = new String(Files.readAllBytes(src("src/main/java/com/scamera/app/processing/processor/ScamProcessor.java").toPath()),
                StandardCharsets.UTF_8);
        int neutral = s.indexOf("applyMosaicNeutral(processingParameters, scamReference, referenceMetadata, images, shotCamera, iszAspect4x3);");
        int cut = s.indexOf("sabrePlan = SabreCrop.cut(images, processingParameters, zoomResidual,", neutral);
        int merge = s.indexOf("ScamHybridBurst.process(", cut);
        int rest = s.indexOf("final Rect rest = SabreCrop.cropRect(baseWidth, baseHeight, window);", merge);
        int old = s.indexOf("img = com.scamera.app.control.ZoomController.crop(img, zoomCrop);", rest);
        assertTrue(neutral > 0 && cut > neutral && merge > cut && rest > merge && old > rest);
        String h = new String(Files.readAllBytes(src("src/main/java/com/scamera/app/processing/opengl/postpipeline/ScamHybridBurst.java").toPath()),
                StandardCharsets.UTF_8);
        assertTrue("the window's grid is the plan's", h.contains("final android.graphics.Point sabre = window != null ? new android.graphics.Point(window.outWidth, window.outHeight)"));
        assertTrue("no output resize of a window before its crop", h.contains("finalWidth = window != null ? outWidth : fin.x;"));
    }

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }
}
