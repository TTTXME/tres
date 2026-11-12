package com.particlesdevs.photoncamera.processing.watermark;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.particlesdevs.photoncamera.processing.MergeStamp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P642: the watermark and the ES / L stamp drawn into the streamed JPEG's rows are the bitmap route's - for every layout, corner,
 * colour, field set, size, margin and opacity of the watermark's matrix, on landscape, portrait (the upright photo of a rotated
 * shot: the marks are drawn after the rotation on either route) and odd-sized photos, in a buffer with a row stride and a crop
 * (the ISZ 4:3 cut), with the stamp, without it, the stamp alone: every byte of the rows equals the bitmap the bitmap route
 * draws on (Bitmap.copyPixelsFromBuffer of the same rows, MergeStamp.draw, PhotoWatermark.draw), and the record («SCWMEND1» payload: WatermarkPatch.toBytes) is the same bytes. The encoder and the file assembly are the stream's own of
 * P483 (the same pixels give the same JPEG bytes on either route: JpegExifStreamTest, the phone's P483 A/B), so the files are the
 * same. A drawing taken back leaves the rows as they were; the frame layout is left to the bitmap route with the rows untouched.
 * The counts go to build/reports/stream-marks/identity.txt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StreamMarksTest {
    /** Photo sizes: landscape, portrait (a rotated shot), 16:9, odd sides, a small one. */
    static final int[][] SIZES = {{1200, 900}, {900, 1200}, {1600, 900}, {1023, 769}, {771, 1031}, {640, 481}};
    static final String[] STAMP = {"ES ✓ 3/3", "L ✗ sh62%"};

    /** The rows of a photo in a wider buffer: {@code pad} pixels beside every row, {@code lead} rows above (a crop's offset). */
    static final class Frame {
        final ByteBuffer buffer;
        final int width, height, stride;

        Frame(int width, int height, int padPixels, int leadRows, long seed) {
            this.width = width;
            this.height = height;
            this.stride = 4 * (width + padPixels);
            final int[] words = WatermarkRecordTest.texture(width, height, seed, 8);
            final byte[] all = new byte[stride * (height + leadRows) + 4 * padPixels];
            Arrays.fill(all, (byte) 0x5A);
            final int base = stride * leadRows + 4 * (padPixels / 2);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    final int w = words[y * width + x], at = base + y * stride + 4 * x;
                    all[at] = (byte) w;
                    all[at + 1] = (byte) (w >> 8);
                    all[at + 2] = (byte) (w >> 16);
                    all[at + 3] = (byte) (w >> 24);
                }
            buffer = ByteBuffer.allocateDirect(all.length);
            buffer.put(all);
            buffer.position(base);
        }

        RgbaRows rows() {
            return new RgbaRows(buffer, width, height, stride);
        }

        /** The photo's packed bytes (what the encoder reads). */
        byte[] photo() {
            return rows().bytes(new Rect(0, 0, width, height));
        }

        /** Everything in the buffer, the padding too. */
        byte[] everything() {
            final ByteBuffer d = buffer.duplicate();
            d.clear();
            final byte[] out = new byte[d.capacity()];
            d.get(out);
            return out;
        }

        /** The bitmap route's bitmap of these rows (the final readback copied into it). */
        Bitmap bitmap() {
            final Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(ByteBuffer.wrap(photo()));
            return b;
        }
    }

    static byte[] bytes(Bitmap b) {
        final byte[] out = new byte[4 * b.getWidth() * b.getHeight()];
        b.copyPixelsToBuffer(ByteBuffer.wrap(out));
        return out;
    }

    /** One case on both routes; returns {the watermark was streamed ? 1 : 0, pixels the marks changed}. */
    static long[] both(String name, Frame f, WatermarkSpec spec, boolean stamp, boolean later) {
        // the bitmap route: ScamProcessor's order - the stamp, then the watermark with its record
        Bitmap photo = f.bitmap();
        final byte[] clean = bytes(photo);
        if (stamp) photo = MergeStamp.draw(photo, STAMP);
        PhotoWatermark.Drawn drawn = spec == null ? null
                : PhotoWatermark.draw(photo, spec, WatermarkLayoutTest.info(), WatermarkLayoutTest.fonts(), true, later);
        if (drawn != null) photo = drawn.photo;
        final byte[] want = bytes(photo);
        final WatermarkPatch wantPatch = drawn == null ? null : drawn.patch();
        // the stream: the same two into the rows
        final byte[] padBefore = f.everything();
        final RgbaRows rows = f.rows();
        final RgbaRows.Undo stampUndo = stamp ? MergeStamp.drawInto(rows, STAMP) : null;
        PhotoWatermark.DrawnInto into = null;
        boolean streamed = true;
        if (spec != null) {
            try {
                into = PhotoWatermark.drawInto(rows, spec, WatermarkLayoutTest.info(), WatermarkLayoutTest.fonts(), true, later);
            } catch (PhotoWatermark.NotStreamed frame) {
                streamed = false;
            }
        }
        if (!streamed) {
            // left to the bitmap route: only the stamp is in the rows, and taking it back gives the readback's bytes
            if (stampUndo != null) stampUndo.run();
            assertArrayEquals(name + ": a watermark that is not streamed leaves the rows as they were", padBefore, f.everything());
            photo.recycle();
            return new long[]{0, 0};
        }
        final byte[] got = f.photo();
        if (!Arrays.equals(want, got)) {
            int first = -1, count = 0;
            for (int i = 0; i < want.length; i++) if (want[i] != got[i]) { if (first < 0) first = i; count++; }
            final StringBuilder where = new StringBuilder();
            for (int i = 0, shown = 0; i < want.length && shown < 12; i += 4) {
                if (want[i] == got[i] && want[i + 1] == got[i + 1] && want[i + 2] == got[i + 2] && want[i + 3] == got[i + 3]) continue;
                where.append(String.format(java.util.Locale.ROOT, " (%d,%d) %02x%02x%02x%02x / %02x%02x%02x%02x was %02x%02x%02x%02x", i / 4 % f.width, i / 4 / f.width,
                        want[i], want[i + 1], want[i + 2], want[i + 3], got[i], got[i + 1], got[i + 2], got[i + 3], clean[i], clean[i + 1], clean[i + 2], clean[i + 3]));
                shown++;
            }
            fail(name + ": " + count + " bytes differ from the bitmap route (bitmap / stream, before):" + where);
        }
        final WatermarkPatch gotPatch = into == null || into.record == null ? null : into.record.get();
        assertEquals(name + ": a record on both routes or on neither", wantPatch == null, gotPatch == null);
        if (wantPatch != null) {
            assertArrayEquals(name + ": the record's bytes", wantPatch.toBytes(), gotPatch.toBytes());
            assertTrue(name + ": the record verifies", gotPatch.verify());
        }
        if (drawn != null && drawn.plan != null) {
            assertNotNull(name, into.plan);
            assertEquals(name + ": the ink", drawn.plan.whiteInk, into.plan.whiteInk);
        }
        // nothing outside the photo's own pixels was written (the row padding, the rows above a crop)
        final byte[] padAfter = f.everything();
        final ByteBuffer d = f.buffer.duplicate();
        final int base = f.buffer.position();
        long changed = 0;
        for (int i = 0; i < padAfter.length; i++) {
            if (padAfter[i] == padBefore[i]) continue;
            final int rel = i - base;
            assertTrue(name + ": a byte outside the photo changed", rel >= 0 && rel / f.stride < f.height && rel % f.stride < 4 * f.width);
            changed++;
        }
        assertTrue(name + ": the marks changed pixels", changed > 0 == (stamp || (drawn != null && drawn.plan != null)));
        // taken back (a stream that fails after drawing): the readback's bytes again, in the reverse order
        if (into != null && into.undo != null) into.undo.run();
        if (stampUndo != null) stampUndo.run();
        assertArrayEquals(name + ": the drawing taken back", padBefore, f.everything());
        assertArrayEquals(name, clean, f.photo());
        photo.recycle();
        return new long[]{1, changed / 4};
    }

    @Test public void theRowsAreTheBitmapRoutesBitmap() throws Exception {
        int cases = 0, streamed = 0, notStreamed = 0, records = 0;
        long seed = 6420;
        final StringBuilder report = new StringBuilder();
        for (int[] size : SIZES) {
            int sizeCases = 0, sizeStreamed = 0;
            for (WatermarkSpec base : WatermarkRecordTest.specs()) {
                for (WatermarkSpec.Position pos : new WatermarkSpec.Position[]{WatermarkSpec.Position.BOTTOM_LEFT, WatermarkSpec.Position.TOP_RIGHT,
                        WatermarkSpec.Position.BOTTOM_CENTER, WatermarkSpec.Position.TOP_LEFT, WatermarkSpec.Position.BOTTOM_RIGHT}) {
                    // the corner's own variation of colour, size, margin, opacity and font (every value is met on every layout)
                    final int v = cases % 6;
                    WatermarkSpec s = base.copy();
                    s.position = pos;
                    s.colour = WatermarkSpec.Colour.values()[v % 3];
                    s.size = new float[]{WatermarkSpec.DEFAULT_SIZE, WatermarkSpec.MIN_SIZE, 12, WatermarkSpec.MAX_SIZE, 5, 9}[v];
                    s.margin = new float[]{WatermarkSpec.DEFAULT_MARGIN, 0, WatermarkSpec.MAX_MARGIN, 1, 6, 2}[v];
                    s.opacity = new float[]{1f, .1f, .55f, 1f, .8f, .35f}[v];
                    s.font = WatermarkSpec.Font.values()[v % WatermarkSpec.Font.values().length];
                    final boolean stamp = cases % 2 == 0;
                    final Frame f = new Frame(size[0], size[1], cases % 3 == 0 ? 0 : 7, cases % 4 == 1 ? 3 : 0, seed++);
                    final String name = size[0] + "x" + size[1] + " " + s.layout + " " + pos + " " + s.colour + " size " + s.size
                            + " margin " + s.margin + (stamp ? " + stamp" : "");
                    final long[] r = both(name, f, s, stamp, cases % 5 == 0);
                    cases++;
                    sizeCases++;
                    if (r[0] == 1) { streamed++; sizeStreamed++; records++; } else notStreamed++;
                    if (s.layout == WatermarkSpec.Layout.FRAME) assertEquals(name + ": the frame layout is the bitmap route's", 0, r[0]);
                }
            }
            report.append(size[0]).append('x').append(size[1]).append(": ").append(sizeCases).append(" cases, ").append(sizeStreamed)
                    .append(" streamed with every byte and the record equal, ").append(sizeCases - sizeStreamed).append(" left to the bitmap route\n");
        }
        report.append("total: ").append(cases).append(" cases, ").append(streamed).append(" streamed (0 bytes differ), ").append(notStreamed)
                .append(" not streamed (a rectangle over 1/4 of the photo - the frame layout, the largest bars: the rows untouched)\n");
        final File dir = new File("build/reports/stream-marks");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileWriter w = new FileWriter(new File(dir, "identity.txt"))) {
            w.write(report.toString());
        }
        assertTrue("the matrix ran", cases >= 400 && streamed >= 300);
        assertTrue("the frame layout was met", notStreamed > 0);
    }

    @Test public void theStampAloneAndNothingAtAll() {
        long seed = 77;
        for (int[] size : SIZES) {
            final long[] r = both(size[0] + "x" + size[1] + " the stamp alone", new Frame(size[0], size[1], 5, 2, seed++), null, true, false);
            assertTrue(r[1] > 0);
            // every field and the glyph off: nothing drawn on either route
            WatermarkSpec off = new WatermarkSpec().only(false, false, false, false, false, false, false, false);
            off.logo = false;
            final Frame f = new Frame(size[0], size[1], 0, 0, seed++);
            final byte[] before = f.everything();
            final PhotoWatermark.DrawnInto none = PhotoWatermark.drawInto(f.rows(), off, WatermarkLayoutTest.info(), WatermarkLayoutTest.fonts(), true, true);
            assertNull(none.undo);
            assertNull(none.record);
            assertArrayEquals(before, f.everything());
            assertNull(MergeStamp.drawInto(f.rows()));
        }
    }

    @Test public void theRowsOfABuffer() {
        final Frame f = new Frame(64, 40, 3, 2, 9);
        final RgbaRows rows = f.rows();
        final Rect r = new Rect(5, 7, 33, 21);
        final byte[] was = rows.bytes(r);
        final Bitmap cut = rows.cut(r);
        assertArrayEquals("a cut holds the rows' bytes", was, bytes(cut));
        // the window: the photo's size, the rectangle's bytes at their place, nothing else in it
        final Bitmap window = rows.window();
        assertEquals(64, window.getWidth());
        assertEquals(40, window.getHeight());
        rows.load(window, r);
        final byte[] w = bytes(window);
        for (int y = 0; y < 40; y++)
            for (int x = 0; x < 64; x++)
                for (int c = 0; c < 4; c++) {
                    final byte want = r.contains(x, y) ? was[((y - r.top) * r.width() + x - r.left) * 4 + c] : 0;
                    assertEquals("window " + x + "," + y, want, w[(y * 64 + x) * 4 + c]);
                }
        // changed through the window and stored: only the rectangle's bytes of the rows change; taken back: all as before
        window.eraseColor(0xFF102030);
        final Bitmap filled = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888);
        filled.eraseColor(0xFF102030);
        final byte[] all = f.everything();
        rows.store(window, r);
        final byte[] now = f.everything();
        int changed = 0;
        for (int i = 0; i < all.length; i++) if (all[i] != now[i]) changed++;
        assertTrue(changed > 0 && changed <= 4 * r.width() * r.height());
        final byte[] stored = rows.bytes(r);
        assertArrayEquals("the window's words as they are", bytes(filled), stored);
        assertFalse(Arrays.equals(was, stored));
        rows.undo(r, was).run();
        assertArrayEquals(all, f.everything());
        for (Rect bad : new Rect[]{new Rect(-1, 0, 4, 4), new Rect(0, 0, 65, 4), new Rect(0, 39, 4, 41), new Rect(3, 3, 3, 9)}) {
            try {
                rows.bytes(bad);
                fail("a rectangle outside the photo: " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        try {
            new RgbaRows(ByteBuffer.allocateDirect(64 * 40 * 4 - 1), 64, 40, 256);
            fail("rows past the buffer");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }
}
