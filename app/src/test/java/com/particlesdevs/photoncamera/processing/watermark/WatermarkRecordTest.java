package com.particlesdevs.photoncamera.processing.watermark;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Rect;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * P633: the watermark's record - the original pixels under it and its spec, kept in the photo's file - takes the watermark off
 * exactly: every pixel the drawing changes lies in the kept rectangles (every layout, position, field set, size, margin, in landscape
 * and portrait), and a draw -> record -> bytes -> restore gives the unwatermarked render back word for word in each pixel format the
 * camera writes (ARGB_8888 sRGB / Display P3, RGBA_1010102 and its 8-bit files) with both codes (lossless WebP, the predictive one).
 * Through the files: a JPEG with its record restores to the JPEG of the unwatermarked render outside the record and to that render
 * inside it; a lossless WebP restores to the render everywhere; the record's form keeps every byte before it (Ultra HDR's MPF
 * offsets, the RIFF / ISO BMFF structure stays valid). A changed spec: restore and redraw = the new watermark drawn on the original.
 * The size overhead for 12 / 50 MP goes to build/reports/watermark-record/stats.txt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WatermarkRecordTest {
    static final int[][] SMALL = {{1200, 900}, {900, 1200}, {1600, 900}};

    @After public void codes() {
        WatermarkPatch.webpAllowed = true;
    }

    // ───── photos ─────

    /** A photo-like picture: value noise of four octaves per channel and a little grain (seeded). */
    static int[] texture(int w, int h, long seed, int bits) {
        Random r = new Random(seed);
        final int max = (1 << bits) - 1;
        final int[] cells = {256, 64, 16, 4};
        final double[] amp = {.30, .12, .05, .02};
        double[][][] grids = new double[3 * cells.length][][];
        for (int c = 0; c < 3; c++)
            for (int o = 0; o < cells.length; o++) {
                int gw = w / cells[o] + 2, gh = h / cells[o] + 2;
                double[][] g = new double[gh][gw];
                for (double[] row : g) for (int i = 0; i < gw; i++) row[i] = r.nextDouble() - .5;
                grids[c * cells.length + o] = g;
            }
        int[] out = new int[w * h];
        double[] v = new double[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    double s = .45 + .1 * c + .25 * Math.sin((x + 2 * y) / (double) (w + h) * 3);
                    for (int o = 0; o < cells.length; o++) {
                        double fx = x / (double) cells[o], fy = y / (double) cells[o];
                        int ix = (int) fx, iy = (int) fy;
                        double tx = fx - ix, ty = fy - iy;
                        double[][] g = grids[c * cells.length + o];
                        double a = g[iy][ix] * (1 - tx) + g[iy][ix + 1] * tx, b = g[iy + 1][ix] * (1 - tx) + g[iy + 1][ix + 1] * tx;
                        s += amp[o] * (a * (1 - ty) + b * ty);
                    }
                    v[c] = s;
                }
                int R = clamp((v[0] + (r.nextDouble() - .5) * .012) * max, max), G = clamp((v[1] + (r.nextDouble() - .5) * .012) * max, max),
                        B = clamp((v[2] + (r.nextDouble() - .5) * .012) * max, max);
                out[y * w + x] = bits == 10 ? R | G << 10 | B << 20 | 3 << 30 : R | G << 8 | B << 16 | 0xFF000000;
            }
        }
        return out;
    }

    static int clamp(double v, int max) {
        return (int) Math.max(0, Math.min(max, Math.round(v)));
    }

    /** {@code words} as a mutable bitmap of {@code config} in {@code space}. */
    static Bitmap bitmap(int[] words, int w, int h, Bitmap.Config config, ColorSpace space) {
        return WatermarkPatch.bitmap(words, w, h, config, space);
    }

    static Bitmap photo(int w, int h, long seed) {
        return bitmap(texture(w, h, seed, 8), w, h, Bitmap.Config.ARGB_8888, ColorSpace.get(ColorSpace.Named.SRGB));
    }

    static int[] words(Bitmap b) {
        return WatermarkPatch.words(b);
    }

    static List<WatermarkSpec> specs() {
        List<WatermarkSpec> out = new ArrayList<>();
        WatermarkSpec base = new WatermarkSpec();
        for (WatermarkSpec.Layout l : WatermarkSpec.Layout.values()) {
            WatermarkSpec def = base.copy();
            def.layout = l;
            out.add(def);
            WatermarkSpec all = def.only(true, true, true, true, true, true, true, true);
            out.add(all);
            out.add(def.only(false, false, false, false, false, false, true, false));
            out.add(def.only(false, false, false, false, false, false, false, true));
        }
        return out;
    }

    static boolean[] mask(List<Rect> rects, int w, int h) {
        boolean[] m = new boolean[w * h];
        for (Rect r : rects) for (int y = r.top; y < r.bottom; y++) Arrays.fill(m, y * w + r.left, y * w + r.right, true);
        return m;
    }

    // ───── what is kept ─────

    @Test public void everyPixelTheDrawingChangesIsKept() {
        int draws = 0;
        for (int[] size : SMALL) {
            final int w = size[0], h = size[1];
            final Bitmap original = photo(w, h, w * 31L + h);
            final int[] before = words(original);
            for (WatermarkSpec base : specs()) {
                for (WatermarkSpec.Position pos : WatermarkSpec.Position.values()) {
                    for (float sz : new float[]{3, 7, 20}) {
                        for (float margin : base.layout == WatermarkSpec.Layout.FRAME ? new float[]{0, 3, 10} : new float[]{0, 3}) {
                            WatermarkSpec s = base.copy();
                            s.position = pos;
                            s.size = sz;
                            s.margin = margin;
                            s.opacity = sz == 3 ? .5f : 1f;
                            Bitmap b = original.copy(Bitmap.Config.ARGB_8888, true);
                            PhotoWatermark.Drawn d = PhotoWatermark.draw(b, s, WatermarkLayoutTest.info(), WatermarkLayoutTest.fonts(), false);
                            assertNotNull(d.plan);
                            List<Rect> rects = WatermarkArea.of(d.plan);
                            boolean[] kept = mask(rects, w, h);
                            int[] after = words(b);
                            int changed = 0;
                            for (int i = 0; i < after.length; i++) {
                                if (after[i] == before[i]) continue;
                                changed++;
                                if (!kept[i]) fail(s.toJson() + " on " + w + "x" + h + ": pixel " + (i % w) + "," + (i / w)
                                        + " changed outside the kept " + rects);
                            }
                            assertTrue(s.toJson() + ": something drawn", changed > 0);
                            for (Rect r : rects) {
                                assertEquals(0, r.left % WatermarkArea.BLOCK);
                                assertEquals(0, r.top % WatermarkArea.BLOCK);
                                assertTrue(r.right % WatermarkArea.BLOCK == 0 || r.right == w);
                                assertTrue(r.bottom % WatermarkArea.BLOCK == 0 || r.bottom == h);
                            }
                            b.recycle();
                            draws++;
                        }
                    }
                }
            }
        }
        assertEquals(3 * 6 * 3 * (12 * 2 + 4 * 3), draws);
    }

    @Test public void rectanglesAreBlocksWithTheirNeighboursAndDoNotOverlap() {
        List<android.graphics.RectF> parts = new ArrayList<>();
        parts.add(new android.graphics.RectF(40, 40, 41, 41)); // one block: it and its 8 neighbours
        List<Rect> r = WatermarkArea.rects(parts, 200, 100);
        assertEquals(1, r.size());
        assertEquals(new Rect(16, 16, 64, 64), r.get(0));
        parts.add(new android.graphics.RectF(150, 90, 199, 99)); // at the corner: cut by the photo
        r = WatermarkArea.rects(parts, 200, 100);
        long px = 0;
        boolean[] seen = new boolean[200 * 100];
        for (Rect q : r) {
            for (int y = q.top; y < q.bottom; y++)
                for (int x = q.left; x < q.right; x++) {
                    assertFalse("overlap at " + x + "," + y, seen[y * 200 + x]);
                    seen[y * 200 + x] = true;
                }
            px += (long) q.width() * q.height();
            assertTrue(q.right <= 200 && q.bottom <= 100);
        }
        assertEquals(48 * 48 + (200 - 128) * (100 - 64), px);
        assertTrue(WatermarkArea.rects(new ArrayList<>(), 100, 100).isEmpty());
    }

    // ───── restoring ─────

    /** Draws {@code spec} on a copy of {@code original} with its record; the record through its bytes. */
    static Object[] drawRecorded(Bitmap original, WatermarkSpec spec) throws IOException {
        Bitmap b = original.copy(original.getConfig(), true);
        PhotoWatermark.Drawn d = PhotoWatermark.draw(b, spec, WatermarkLayoutTest.info(), WatermarkLayoutTest.fonts(), true);
        WatermarkPatch patch = d.patch();
        assertNotNull("record of " + spec.toJson(), patch);
        WatermarkPatch back = WatermarkPatch.fromBytes(patch.toBytes());
        assertTrue(back.verify());
        assertEquals(spec.toJson(), back.spec);
        return new Object[]{d.photo, back, patch};
    }

    @Test public void removingGivesTheUnwatermarkedRenderBackInEveryFormat() throws IOException {
        int checked = 0;
        for (int[] size : new int[][]{{1200, 900}, {900, 1200}}) {
            final int w = size[0], h = size[1];
            for (String format : new String[]{"srgb-webp", "srgb-med", "p3", "ten"}) {
                WatermarkPatch.webpAllowed = !format.equals("srgb-med");
                Bitmap original;
                if (format.equals("ten")) {
                    original = bitmap(texture(w, h, 7, 10), w, h, Bitmap.Config.RGBA_1010102, ColorSpace.get(ColorSpace.Named.SRGB));
                } else {
                    ColorSpace cs = ColorSpace.get(format.equals("p3") ? ColorSpace.Named.DISPLAY_P3 : ColorSpace.Named.SRGB);
                    original = bitmap(texture(w, h, 5, 8), w, h, Bitmap.Config.ARGB_8888, cs);
                }
                int[] want = words(original);
                for (WatermarkSpec.Layout l : WatermarkSpec.Layout.values()) {
                    for (WatermarkSpec.Position p : new WatermarkSpec.Position[]{WatermarkSpec.Position.BOTTOM_LEFT,
                            WatermarkSpec.Position.TOP_CENTER, WatermarkSpec.Position.BOTTOM_RIGHT}) {
                        WatermarkSpec s = new WatermarkSpec().only(true, true, true, true, true, true, true, true);
                        s.layout = l;
                        s.position = p;
                        Object[] r = drawRecorded(original, s);
                        Bitmap marked = (Bitmap) r[0];
                        WatermarkPatch rec = (WatermarkPatch) r[1];
                        assertEquals(format.equals("ten") ? WatermarkPatch.FORMAT_1010102 : WatermarkPatch.FORMAT_8888, rec.format);
                        assertEquals(format.equals("p3") ? WatermarkPatch.SPACE_P3 : WatermarkPatch.SPACE_SRGB, rec.colourSpace);
                        assertFalse(Arrays.equals(want, words(marked)));
                        rec.restore(marked);
                        assertArrayEquals(format + " " + s.toJson(), want, words(marked));
                        if (format.equals("ten")) {
                            // the 10-bit shot's 8-bit files (JPEG / WebP / 8-bit HEIC) take the 8-bit record: Bitmap.copy of the render
                            WatermarkPatch eight = WatermarkPatch.fromBytes(((WatermarkPatch) r[2]).eightBit().toBytes());
                            assertEquals(WatermarkPatch.FORMAT_8888, eight.format);
                            Bitmap marked8 = ((Bitmap) drawRecorded(original, s)[0]).copy(Bitmap.Config.ARGB_8888, true);
                            eight.restore(marked8);
                            assertArrayEquals(words(original.copy(Bitmap.Config.ARGB_8888, false)), words(marked8));
                        }
                        checked++;
                    }
                }
            }
        }
        assertEquals(2 * 4 * 4 * 3, checked);
    }

    @Test public void aRecordCodedBesideTheEncodingIsTheSame() {
        for (Bitmap original : new Bitmap[]{photo(1600, 1200, 13),
                bitmap(texture(1200, 900, 14, 10), 1200, 900, Bitmap.Config.RGBA_1010102, ColorSpace.get(ColorSpace.Named.SRGB))}) {
            WatermarkSpec s = new WatermarkSpec().only(true, true, true, true, true, true, true, true);
            s.layout = WatermarkSpec.Layout.FRAME;
            PhotoWatermark.Drawn now = PhotoWatermark.draw(original.copy(original.getConfig(), true), s, WatermarkLayoutTest.info(),
                    WatermarkLayoutTest.fonts(), true, false);
            Bitmap target = original.copy(original.getConfig(), true);
            PhotoWatermark.Drawn later = PhotoWatermark.draw(target, s, WatermarkLayoutTest.info(), WatermarkLayoutTest.fonts(), true, true);
            assertArrayEquals("drawn the same", words(now.photo), words(later.photo));
            assertArrayEquals(now.patch().toBytes(), later.patch().toBytes());
            assertArrayEquals(now.patch().eightBit().toBytes(), later.patch().eightBit().toBytes());
        }
    }

    @Test public void changingTheSpecIsTheNewWatermarkOnTheOriginal() throws IOException {
        Bitmap original = photo(1600, 1200, 11);
        WatermarkSpec a = new WatermarkSpec().only(true, true, false, false, false, false, true, true);
        WatermarkSpec b = a.copy();
        b.layout = WatermarkSpec.Layout.FRAME;
        b.position = WatermarkSpec.Position.TOP_RIGHT;
        b.colour = WatermarkSpec.Colour.BLACK;
        b.showIso = true;
        Object[] ra = drawRecorded(original, a);
        Bitmap edited = (Bitmap) ra[0];
        ((WatermarkPatch) ra[1]).restore(edited);
        Object[] rb = drawRecorded(edited, b);
        Object[] direct = drawRecorded(original, b);
        assertArrayEquals(words((Bitmap) direct[0]), words((Bitmap) rb[0]));
        ((WatermarkPatch) rb[1]).restore((Bitmap) rb[0]);
        assertArrayEquals(words(original), words((Bitmap) rb[0]));
    }

    @Test public void aDamagedRecordIsRefused() throws IOException {
        Bitmap original = photo(800, 600, 3);
        WatermarkPatch p = (WatermarkPatch) drawRecorded(original, new WatermarkSpec())[2];
        byte[] bytes = p.toBytes();
        byte[] bad = bytes.clone();
        bad[bad.length - 5] ^= 0x5A; // inside the last piece's data
        WatermarkPatch damaged;
        try {
            damaged = WatermarkPatch.fromBytes(bad);
        } catch (IOException refused) {
            damaged = null;
        }
        if (damaged != null) assertFalse(damaged.verify());
        try {
            WatermarkPatch.fromBytes(Arrays.copyOf(bytes, bytes.length / 2));
            fail("a cut record");
        } catch (IOException expected) {
            // refused
        }
        try {
            WatermarkPatch.fromBytes("JFIF".getBytes());
            fail("not a record");
        } catch (IOException expected) {
            // refused
        }
    }

    // ───── files ─────

    static byte[] compress(Bitmap b, Bitmap.CompressFormat f, int q) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        assertTrue(b.compress(f, q, o));
        return o.toByteArray();
    }

    static File file(byte[] bytes, String name) throws IOException {
        File f = File.createTempFile("wmrec", name);
        f.deleteOnExit();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(bytes);
        }
        return f;
    }

    @Test public void aJpegRestoresToTheRenderInsideAndToItsOwnJpegOutside() throws IOException {
        for (int[] size : new int[][]{{1200, 900}, {900, 1200}}) {
            final int w = size[0], h = size[1];
            Bitmap original = photo(w, h, 21);
            Bitmap plain = BitmapFactory.decodeByteArray(compress(original, Bitmap.CompressFormat.JPEG, 95), 0,
                    compress(original, Bitmap.CompressFormat.JPEG, 95).length);
            int[] plainWords = words(plain.copy(Bitmap.Config.ARGB_8888, false));
            int[] want = words(original);
            for (WatermarkSpec.Layout l : WatermarkSpec.Layout.values()) {
                WatermarkSpec s = new WatermarkSpec().only(true, true, true, true, true, true, true, true);
                s.layout = l;
                s.position = l == WatermarkSpec.Layout.BADGE ? WatermarkSpec.Position.TOP_RIGHT : WatermarkSpec.Position.BOTTOM_LEFT;
                Object[] r = drawRecorded(original, s);
                byte[] jpeg = compress((Bitmap) r[0], Bitmap.CompressFormat.JPEG, 95);
                File f = file(jpeg, ".jpg");
                assertTrue(WatermarkStore.append(f, ((WatermarkPatch) r[2]).eightBit().toBytes()));
                byte[] stored = Files.readAllBytes(f.toPath());
                assertArrayEquals("every byte of the JPEG stays (MPF offsets of an Ultra HDR too)", jpeg, Arrays.copyOf(stored, jpeg.length));
                WatermarkPatch rec = WatermarkStore.read(f);
                assertNotNull(rec);
                assertTrue(rec.verify());
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inMutable = true;
                Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                assertNotNull("decodes with the record after its EOI", decoded);
                rec.restore(decoded);
                int[] got = words(decoded);
                boolean[] kept = mask(rec.rects(), w, h);
                for (int i = 0; i < got.length; i++) {
                    int expect = kept[i] ? want[i] : plainWords[i];
                    if (got[i] != expect) fail(l + " " + w + "x" + h + " pixel " + (i % w) + "," + (i / w) + (kept[i] ? " in" : " outside")
                            + " the record: " + Integer.toHexString(got[i]) + " != " + Integer.toHexString(expect));
                }
            }
        }
    }

    @Test public void aWebpRestoresLosslesslyAndStaysAValidRiff() throws IOException {
        Bitmap original = photo(1000, 750, 23);
        int[] want = words(original);
        for (boolean lossless : new boolean[]{true, false}) {
            WatermarkSpec s = new WatermarkSpec().only(true, true, true, true, true, true, true, true);
            s.layout = WatermarkSpec.Layout.BAR;
            Object[] r = drawRecorded(original, s);
            byte[] webp = compress((Bitmap) r[0], lossless ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP_LOSSY, lossless ? 25 : 90);
            File f = file(webp, ".webp");
            assertTrue(WatermarkStore.append(f, ((WatermarkPatch) r[2]).toBytes()));
            byte[] stored = Files.readAllBytes(f.toPath());
            assertEquals(stored.length - 8, ByteBuffer.wrap(stored, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            // the chunks walk to the end, the last is ours
            int at = 12;
            String last = null;
            while (at < stored.length) {
                last = new String(stored, at, 4, "US-ASCII");
                int len = ByteBuffer.wrap(stored, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                at += 8 + len + (len & 1);
            }
            assertEquals(stored.length, at);
            assertEquals("SCWM", last);
            assertArrayEquals(Arrays.copyOfRange(webp, 8, webp.length), Arrays.copyOfRange(stored, 8, webp.length));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            assertNotNull(decoded);
            WatermarkPatch rec = WatermarkStore.read(f);
            assertNotNull(rec);
            rec.restore(decoded);
            int[] got = words(decoded);
            boolean[] kept = mask(rec.rects(), 1000, 750);
            for (int i = 0; i < got.length; i++) {
                if (lossless || kept[i]) assertEquals("pixel " + i, want[i], got[i]);
            }
        }
    }

    /** A HEIC / AVIF shape: ftyp, meta, mdat; the record a top-level uuid box after them. */
    @Test public void anIsoBmffFileTakesTheRecordAsItsLastBox() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Object[] box : new Object[][]{{"ftyp", new byte[]{'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1', 'h', 'e', 'i', 'c'}},
                {"meta", new byte[40]}, {"mdat", new byte[1001]}}) {
            byte[] data = (byte[]) box[1];
            o.write(ByteBuffer.allocate(4).putInt(8 + data.length).array());
            o.write(((String) box[0]).getBytes("US-ASCII"));
            o.write(data);
        }
        byte[] heif = o.toByteArray();
        File f = file(heif, ".heic");
        Bitmap original = bitmap(texture(640, 480, 9, 10), 640, 480, Bitmap.Config.RGBA_1010102, ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        Object[] r = drawRecorded(original, new WatermarkSpec());
        byte[] record = ((WatermarkPatch) r[2]).toBytes();
        assertTrue(WatermarkStore.append(f, record));
        byte[] stored = Files.readAllBytes(f.toPath());
        assertArrayEquals(heif, Arrays.copyOf(stored, heif.length));
        int at = 0;
        String last = null;
        while (at < stored.length) {
            int len = ByteBuffer.wrap(stored, at, 4).getInt();
            last = new String(stored, at + 4, 4, "US-ASCII");
            if (last.equals("uuid")) assertArrayEquals(WatermarkStore.UUID, Arrays.copyOfRange(stored, at + 8, at + 24));
            at += len;
        }
        assertEquals(stored.length, at);
        assertEquals("uuid", last);
        WatermarkPatch back = WatermarkStore.read(f);
        assertNotNull(back);
        assertEquals(WatermarkPatch.FORMAT_1010102, back.format);
        assertEquals(WatermarkPatch.SPACE_P3, back.colourSpace);
        Bitmap marked = (Bitmap) r[0];
        back.restore(marked);
        assertArrayEquals(words(original), words(marked));
    }

    @Test public void filesWithoutARecordReadNone() throws IOException {
        Bitmap b = photo(320, 240, 1);
        assertNull(WatermarkStore.read(file(compress(b, Bitmap.CompressFormat.JPEG, 90), ".jpg")));
        assertNull(WatermarkStore.read(file(new byte[3], ".bin")));
        File other = file("plain text, not a photo".getBytes(), ".txt");
        assertFalse(WatermarkStore.append(other, new byte[]{1, 2, 3}));
        assertEquals(WatermarkStore.Container.JPEG, WatermarkStore.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, 3));
        // a footer that claims more than the file holds
        File liar = file(compress(b, Bitmap.CompressFormat.JPEG, 90), ".jpg");
        try (RandomAccessFile f = new RandomAccessFile(liar, "rw")) {
            f.seek(f.length());
            f.write(ByteBuffer.allocate(4).putInt(1 << 30).array());
            f.write(WatermarkStore.END);
        }
        assertNull(WatermarkStore.read(liar));
    }

    @Test public void theSpecSurvivesItsJson() {
        Random r = new Random(4);
        for (int i = 0; i < 200; i++) {
            WatermarkSpec s = new WatermarkSpec();
            s.layout = WatermarkSpec.Layout.values()[r.nextInt(4)];
            s.position = WatermarkSpec.Position.values()[r.nextInt(6)];
            s.font = WatermarkSpec.Font.values()[r.nextInt(4)];
            s.colour = WatermarkSpec.Colour.values()[r.nextInt(3)];
            s.dateFormat = WatermarkSpec.DateFormat.values()[r.nextInt(5)];
            s = s.only(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean());
            s.text = i % 7 == 0 ? "" : "Текст «" + i + "» — 📷";
            s.size = 3 + r.nextInt(18);
            s.opacity = (10 + r.nextInt(91)) / 100f;
            s.margin = r.nextInt(11);
            WatermarkSpec back = WatermarkSpec.fromJson(s.toJson());
            assertNotNull(back);
            assertEquals(s.toJson(), back.toJson());
            assertEquals(s, back);
        }
        assertNull(WatermarkSpec.fromJson("not json"));
        assertNull(WatermarkSpec.fromJson(""));
        WatermarkSpec wild = WatermarkSpec.fromJson("{\"layout\":\"spiral\",\"size\":500,\"opacity\":-1,\"margin\":\"x\"}");
        assertNotNull(wild);
        assertEquals(WatermarkSpec.Layout.TEXT, wild.layout);
        assertEquals(WatermarkSpec.MAX_SIZE, wild.size, 0f);
        assertEquals(WatermarkSpec.MIN_OPACITY / 100f, wild.opacity, 1e-6f);
        assertEquals(WatermarkSpec.DEFAULT_MARGIN, wild.margin, 0f);
    }

    // ───── size ─────

    /** The record's size for the default fields and for every field, per layout, on a 12 MP and a 50 MP photo. */
    @Test public void sizeOverheadFor12And50Mp() throws IOException {
        StringBuilder out = new StringBuilder("P633 watermark record overhead (synthetic photo-like texture; JPEG q95 by Bitmap.compress as the reference)\n");
        String real = System.getenv("WM_STATS_PHOTO");
        List<Object[]> photos = new ArrayList<>();
        photos.add(new Object[]{"12 MP synthetic", photo(4000, 3000, 12)});
        photos.add(new Object[]{"50 MP synthetic", photo(8160, 6144, 50)});
        if (real != null && new File(real).isFile()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            Bitmap p = BitmapFactory.decodeFile(real, o);
            if (p != null) photos.add(new Object[]{"real " + p.getWidth() + "x" + p.getHeight() + " (a phone JPEG decoded)", p});
        }
        for (Object[] ph : photos) {
            Bitmap original = (Bitmap) ph[1];
            long jpeg = compress(original, Bitmap.CompressFormat.JPEG, 95).length;
            out.append(String.format(Locale.ROOT, "%s %dx%d, JPEG %.2f MB\n", ph[0], original.getWidth(), original.getHeight(), jpeg / 1e6));
            for (WatermarkSpec.Layout l : WatermarkSpec.Layout.values()) {
                for (boolean all : new boolean[]{false, true}) {
                    WatermarkSpec s = new WatermarkSpec();
                    s.layout = l;
                    if (all) s = s.only(true, true, true, true, true, true, true, true);
                    long t0 = System.nanoTime();
                    Object[] r = drawRecorded(original, s);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    WatermarkPatch p = (WatermarkPatch) r[2];
                    long bytes = p.toBytes().length;
                    double area = 100.0 * p.pixels() / ((double) original.getWidth() * original.getHeight());
                    out.append(String.format(Locale.ROOT, "  %-5s %-8s kept %5.2f %% of the photo, record %8.1f KB = %5.1f %% of the JPEG, %.2f bit/px kept, %d ms\n",
                            l, all ? "all" : "default", area, bytes / 1024.0, 100.0 * bytes / jpeg, bytes * 8.0 / p.pixels(), ms));
                    ((Bitmap) r[0]).recycle();
                }
            }
        }
        File dir = new File("build/reports/watermark-record");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "stats.txt"))) {
            o.write(out.toString().getBytes("UTF-8"));
        }
        System.out.println(out);
    }
}
