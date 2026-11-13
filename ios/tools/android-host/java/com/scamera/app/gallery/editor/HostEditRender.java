package com.scamera.app.gallery.editor;

import android.app.Application;
import android.content.Context;
import android.opengl.GLES30;

import com.scamera.app.gallery.dng.DngRaw;
import com.scamera.app.processing.opengl.GLContext;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The photo editor's reference renders for the iOS port (ios/tools/edit_check.cpp compares its C++ against these): Android's own
 * EditRenderer / EditMath / CropMath / DngRaw / EditSource.RawColour on the host GPU (Mesa) under Robolectric.
 * SCAM_HOST_EDIT_IN: a folder with picture.rgba (16-byte header, RGBA8), *.dng and cases.txt (one EditState JSON a line);
 * SCAM_HOST_EDIT_OUT: the dumps. For each source and case: the preview (EditorView's frame of a 640 x 480 view, the crop tab's
 * frame, the histogram's 160 px render) and the copy (EditExporter.export's tile loop; picture: BitmapReader's crops, RAW:
 * RawReader's regions), each as RGBA8 with the same header.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = {scam.host.ShadowGLES20.class, scam.host.ShadowGLES30.class,
        scam.host.ShadowGLES31.class, scam.host.ShadowGLES32.class, scam.host.ShadowEGL14.class})
public class HostEditRender {
    private static final float[] BG = {0x12 / 255f, 0x12 / 255f, 0x12 / 255f};

    private static String env(String name) {
        final String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalStateException(name + " is not set");
        return v;
    }

    /** A picture or a RAW, as EditSource holds it for the preview and the export. */
    private static final class Src {
        String name;
        int w, h, orientation = 1;
        ByteBuffer rgba;            // the picture
        DngRaw.Image preview, full; // the RAW: preview decode, export decode
        EditMath.SourceColour colour;
    }

    @Test public void render() throws Exception {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        if (System.getenv("SCAM_HOST_EDIT_IN") == null && System.getenv("SCAM_HOST_WM_DIR") != null) return; // the record's check alone
        final File in = new File(env("SCAM_HOST_EDIT_IN")), out = new File(env("SCAM_HOST_EDIT_OUT"));
        out.mkdirs();
        final long outMaxSmall = Long.parseLong(System.getenv().getOrDefault("SCAM_HOST_EDIT_OUTMAX", "1000000"));
        final List<String> cases = Files.readAllLines(new File(in, "cases.txt").toPath(), StandardCharsets.UTF_8);
        final Context context = RuntimeEnvironment.getApplication();
        // P619: the film's arrays are the app's assets (FilmSpectral reads them through ScameraApp's context, as on the phone)
        final org.mockito.MockedStatic<com.scamera.app.app.ScameraApp> app =
                org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class);
        app.when(com.scamera.app.app.ScameraApp::getAppContext).thenReturn(context);
        GLContext gl = new GLContext(1, 1);
        try {
            EditRenderer r = new EditRenderer();
            r.init(context.getAssets());
            File[] files = in.listFiles();
            java.util.Arrays.sort(files);
            for (File f : files) {
                Src s = open(f);
                if (s == null) continue;
                for (int c = 0; c < cases.size(); c++) {
                    if (cases.get(c).trim().isEmpty()) continue;
                    EditState st = EditState.fromJson(cases.get(c).trim());
                    if (st == null) throw new IOException("case " + c + " is not a state: " + cases.get(c));
                    String tag = s.name + "_" + c;
                    preview(r, s, st, out, tag);
                    long outMax = c % 2 == 1 ? outMaxSmall : EditSource.OUT_MAX;
                    export(r, s, st, outMax, out, tag);
                    System.out.println("HOST EDIT: " + tag + " done");
                }
            }
            r.release();
        } finally {
            gl.close();
            app.close();
        }
    }

    private static Src open(File f) throws IOException {
        Src s = new Src();
        String n = f.getName();
        if (n.endsWith(".rgba")) {
            byte[] b = Files.readAllBytes(f.toPath());
            ByteBuffer h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            s.w = h.getInt(4);
            s.h = h.getInt(8);
            s.rgba = ByteBuffer.allocateDirect(s.w * s.h * 4).order(ByteOrder.nativeOrder());
            s.rgba.put(b, 16, s.w * s.h * 4).rewind();
            s.colour = EditMath.display(false, false);
        } else if (n.endsWith(".dng")) {
            ByteBuffer map;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
                map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            }
            // EditSource.openRaw (DNG route): the preview binned to about PREVIEW_SIDE x 1.25
            DngRaw.Info info = DngRaw.probe(map);
            int bin = 1;
            while (true) {
                int[] sz = info.size(bin);
                if (Math.max(sz[0], sz[1]) <= EditSource.PREVIEW_SIDE * 1.25 || bin >= 16) break;
                bin *= 2;
            }
            s.preview = DngRaw.decode(map, bin);
            s.orientation = s.preview.info.orientation;
            s.colour = new EditSource.RawColour(s.preview.info.colour, (float) s.preview.info.baselineExposure + s.preview.autoLiftStops);
            // EditSource.RawReader: full resolution (the test's DNGs are under RAW_MAX)
            s.full = DngRaw.decode(map, 1);
            s.w = s.preview.width;
            s.h = s.preview.height;
        } else return null;
        s.name = n.substring(0, n.lastIndexOf('.'));
        return s;
    }

    private static int pictureTexture(ByteBuffer rgba, int fullW, int x, int y, int w, int h) {
        ByteBuffer part = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int j = 0; j < h; j++) {
            ByteBuffer row = rgba.duplicate();
            row.position(((y + j) * fullW + x) * 4).limit(((y + j) * fullW + x + w) * 4);
            part.put(row);
        }
        part.rewind();
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, part);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
        EditGl.params(true, false);
        return t[0];
    }

    /** EditorView.onDrawFrame's chain at the fit (no zoom): the view frame, the crop tab's frame, the histogram's render. */
    private static void preview(EditRenderer r, Src s, EditState st, File out, String tag) throws IOException {
        r.invalidate();
        int srcTex;
        boolean own;
        if (s.preview != null) {
            srcTex = r.rawSource(s.preview, 0, 0, s.preview.width, s.preview.height);
            own = false;
        } else {
            srcTex = pictureTexture(s.rgba, s.w, 0, 0, s.w, s.h);
            own = true;
        }
        r.setProxySource(srcTex, s.w, s.h);
        EditMath.Uniforms u = EditMath.Uniforms.of(st, s.colour);
        // P619: the «LUT» tab's look as EditorView applies it (the tables of the state, EditLook.blocking as the export takes them)
        r.setLook(EditLook.on(st) ? EditLook.applied(st, EditLook.blocking(RuntimeEnvironment.getApplication(), st, null, 0)) : null);
        double[] o = CropMath.swapsSides(s.orientation) ? new double[]{s.h, s.w} : new double[]{s.w, s.h};
        r.updateProxy(u);
        r.renderSource(srcTex, s.w, s.h, Math.max(s.w, s.h), CropMath.Affine.IDENTITY, u, true);
        EditRenderer.Target view = new EditRenderer.Target();
        for (int mode = 0; mode < 2; mode++) {
            final int vw = 640, vh = 480;
            view.ensure(vw, vh, GLES30.GL_RGBA8, false, 1);
            android.graphics.RectF c = EditorView.canvas(vw, vh, 0f, st, o[0], o[1], mode == 1);
            EditRenderer.Finish f = finishFor(r, st, c, mode == 1, o, s.orientation);
            f.fbo = view.fbo;
            f.w = vw;
            f.h = vh;
            f.flipY = false;
            r.finish(f, st, u);
            dump(new File(out, tag + (mode == 0 ? "_view.rgba" : "_crop.rgba")), read(vw, vh), vw, vh);
        }
        // the histogram's render (EditorView.histogram)
        double[] px = CropMath.cropPixels(st, o[0], o[1]);
        double k = 160.0 / Math.max(px[0], px[1]);
        int w = Math.max(8, (int) Math.round(px[0] * k)), h = Math.max(8, (int) Math.round(px[1] * k));
        r.hist.ensure(w, h, GLES30.GL_RGBA8, false, 1);
        EditRenderer.Finish f = finishFor(r, st, new android.graphics.RectF(0, 0, w, h), false, o, s.orientation);
        f.fbo = r.hist.fbo;
        f.w = w;
        f.h = h;
        r.finish(f, st, u);
        dump(new File(out, tag + "_hist.rgba"), read(w, h), w, h);
        view.release();
        if (own) EditGl.deleteTexture(srcTex);
    }

    /** EditorView.finishFor (its RectF canvas; the background GalleryUi.BG's grey). */
    private static EditRenderer.Finish finishFor(EditRenderer r, EditState st, android.graphics.RectF c, boolean crop, double[] o, int orientation) {
        EditRenderer.Finish f = new EditRenderer.Finish();
        f.mode = crop ? 1 : 0;
        CropMath.Affine toCanvas = CropMath.Affine.translate(-c.left, -c.top).then(CropMath.Affine.scale(1.0 / c.width(), 1.0 / c.height()));
        f.outToBase = crop ? toCanvas : toCanvas.then(CropMath.cropToBase(st));
        f.baseToImg = CropMath.baseToOriented(st, o[0], o[1]);
        f.imgToTex = CropMath.orientationToDisplay(orientation).inverse();
        f.bg = BG.clone();
        f.outLongSide = crop ? Math.max(c.width() * (st.cropR - st.cropL), c.height() * (st.cropB - st.cropT)) : Math.max(c.width(), c.height());
        double[] px = CropMath.cropPixels(st, o[0], o[1]);
        r.latticeAspect = (float) (px[0] / Math.max(1e-6, px[1]));
        return f;
    }

    /** EditExporter.export's GL part: the proxy from the preview's decode, then the tiles. */
    private static void export(EditRenderer r, Src s, EditState st, long outMax, File out, String tag) throws IOException {
        r.invalidate();
        int previewTex;
        boolean own = s.preview == null;
        if (s.preview != null) previewTex = r.rawSource(s.preview, 0, 0, s.preview.width, s.preview.height);
        else previewTex = pictureTexture(s.rgba, s.w, 0, 0, s.w, s.h);
        r.setProxySource(previewTex, s.w, s.h);
        if (own) EditGl.deleteTexture(previewTex);
        r.updateProxy(EditMath.Uniforms.of(st, s.colour));
        final int sw = s.full != null ? s.full.width : s.w, sh = s.full != null ? s.full.height : s.h;
        final EditExporter.Plan plan = EditExporter.plan(st, sw, sh, s.orientation, outMax);
        final int W = plan.W, H = plan.H;
        final CropMath.Affine outToBase = plan.outToBase, baseToImg = plan.baseToImg, imgToSrcUv = plan.imgToSrcUv;
        final CropMath.Affine outToSrcPx = plan.outToSrcPx;
        int overlap = EditLook.overlap(st, Math.max(sw, sh)) + 2;
        // EditExporter.export: the look from the tables the preview ended at (EditorViewModel: EditLook.blocking)
        r.setLook(EditLook.on(st) ? EditLook.applied(st, EditLook.blocking(RuntimeEnvironment.getApplication(), st, null, 0)) : null);
        EditRenderer.Target tile = new EditRenderer.Target();
        final int TILE = EditExporter.TILE;
        ByteBuffer pixels = ByteBuffer.allocateDirect(TILE * TILE * 4).order(ByteOrder.LITTLE_ENDIAN);
        int[] argb = new int[TILE * TILE];
        byte[] image = new byte[W * H * 4];
        int tilesX = (W + TILE - 1) / TILE, tilesY = (H + TILE - 1) / TILE;
        boolean even = s.full != null && s.full.info.kind == DngRaw.KIND_BAYER;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int ox = tx * TILE, oy = ty * TILE, tw = Math.min(TILE, W - ox), th = Math.min(TILE, H - oy);
                int[] reg = EditExporter.region(outToSrcPx, ox, oy, tw, th, overlap, sw, sh, even);
                int src = s.full != null ? r.rawSource(s.full, reg[0], reg[1], reg[2], reg[3]) : pictureTexture(s.rgba, s.w, reg[0], reg[1], reg[2], reg[3]);
                EditMath.Uniforms u = EditMath.Uniforms.of(st, s.colour);
                CropMath.Affine texToSrcUv = CropMath.Affine.scale(reg[2], reg[3]).then(CropMath.Affine.translate(reg[0], reg[1]))
                        .then(CropMath.Affine.scale(1.0 / sw, 1.0 / sh));
                r.renderSource(src, reg[2], reg[3], Math.max(sw, sh), texToSrcUv, u, false);
                if (s.full == null) EditGl.deleteTexture(src);
                tile.ensure(tw, th, GLES30.GL_RGBA8, false, 1);
                EditRenderer.Finish f = new EditRenderer.Finish();
                f.fbo = tile.fbo;
                f.w = tw;
                f.h = th;
                f.originX = ox;
                f.originY = oy;
                f.swap = true;
                f.mode = 0;
                f.outToBase = outToBase;
                f.baseToImg = baseToImg;
                f.imgToTex = imgToSrcUv.then(texToSrcUv.inverse());
                f.outLongSide = plan.longSide();
                r.latticeAspect = plan.latticeAspect();
                r.finish(f, st, u);
                pixels.clear();
                GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels);
                IntBuffer ib = pixels.asIntBuffer();
                ib.get(argb, 0, tw * th);
                // out.setPixels(argb, ...): the ARGB ints as R, G, B, A bytes
                for (int j = 0; j < th; j++)
                    for (int i = 0; i < tw; i++) {
                        int v = argb[j * tw + i], at = ((oy + j) * W + ox + i) * 4;
                        image[at] = (byte) (v >> 16);
                        image[at + 1] = (byte) (v >> 8);
                        image[at + 2] = (byte) v;
                        image[at + 3] = (byte) (v >>> 24);
                    }
            }
        }
        tile.release();
        dump(new File(out, tag + "_export.rgba"), image, W, H);
    }

    /**
     * P633: the watermark's record (processing/watermark WatermarkPatch / WatermarkStore) against the iPhone's (ios/Editor/
     * WatermarkPatch.hpp). SCAM_HOST_WM_DIR: what `edit_check --wm make` wrote (picture.rgba, rects.txt, spec.txt, the containers'
     * bases, the iPhone's cpp.jpg / .webp / .heic and cpp_big.rec). Writes Android's records of the same pixels (java.rec 8-bit,
     * java10.rec 10-bit, java_big.rec over the piece limit; the predictive code, as WatermarkPatch codes them without WebP) and
     * java.jpg / .webp / .heic (WatermarkStore.append), and android_<name>.words: the iPhone's records as WatermarkStore.read,
     * verify and wordsOf give them (pieces, then each piece's rect and words). `edit_check --wm check` compares.
     */
    @Test public void watermarkRecord() throws Exception {
        final String dirName = System.getenv("SCAM_HOST_WM_DIR");
        if (dirName == null || dirName.isEmpty()) return;
        final File dir = new File(dirName);
        final Class<?> patch = Class.forName("com.scamera.app.processing.watermark.WatermarkPatch");
        final Class<?> piece = Class.forName("com.scamera.app.processing.watermark.WatermarkPatch$Piece");
        final java.lang.reflect.Method split = patch.getDeclaredMethod("split", List.class);
        final java.lang.reflect.Method crc = patch.getDeclaredMethod("crc", int[].class);
        final java.lang.reflect.Method med = patch.getDeclaredMethod("med", int[].class, int.class, int.class, int.class);
        final java.lang.reflect.Method wordsOf = patch.getDeclaredMethod("wordsOf", int.class);
        final java.lang.reflect.Constructor<?> newPiece = piece.getDeclaredConstructor(android.graphics.Rect.class, int.class, int.class, byte[].class);
        final java.lang.reflect.Constructor<?> newPatch = patch.getDeclaredConstructor(int.class, int.class, int.class, int.class, String.class, List.class);
        for (java.lang.reflect.AccessibleObject o : new java.lang.reflect.AccessibleObject[]{split, crc, med, wordsOf, newPiece, newPatch})
            o.setAccessible(true);
        final java.lang.reflect.Field piecesField = patch.getDeclaredField("pieces");
        final java.lang.reflect.Field rectField = piece.getDeclaredField("rect");
        piecesField.setAccessible(true);
        rectField.setAccessible(true);
        final String spec = new String(Files.readAllBytes(new File(dir, "spec.txt").toPath()), StandardCharsets.UTF_8);

        // the picture (RGBA8 after a 16-byte header) and the synthetic wide one, as R-in-the-low-byte words
        byte[] pic = Files.readAllBytes(new File(dir, "picture.rgba").toPath());
        ByteBuffer hdr = ByteBuffer.wrap(pic, 0, 16).order(ByteOrder.LITTLE_ENDIAN);
        hdr.getInt();
        final int pw = hdr.getInt(), ph = hdr.getInt();
        final int[] picWords = new int[pw * ph], pic10 = new int[pw * ph];
        for (int i = 0; i < pw * ph; i++) {
            int r = pic[16 + 4 * i] & 255, g = pic[17 + 4 * i] & 255, b = pic[18 + 4 * i] & 255;
            picWords[i] = r | g << 8 | b << 16 | 0xFF000000;
            pic10[i] = ten(r) | ten(g) << 10 | ten(b) << 20 | 3 << 30;
        }
        final int bw = 4160, bh = 1040;
        final int[] bigWords = new int[bw * bh];
        for (int y = 0; y < bh; y++)
            for (int x = 0; x < bw; x++)
                bigWords[y * bw + x] = ((x * 7 + y * 3) & 255) | ((x ^ y) & 255) << 8 | (((x * y) >> 4) & 255) << 16 | 0xFF000000;
        List<android.graphics.Rect> rects = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(new File(dir, "rects.txt").toPath(), StandardCharsets.UTF_8)) {
            String[] v = line.trim().split(" ");
            if (v.length == 4) rects.add(new android.graphics.Rect(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3])));
        }
        List<android.graphics.Rect> wide = java.util.Collections.singletonList(new android.graphics.Rect(0, 0, bw, bh));
        Object[][] made = {{"java.rec", picWords, pw, ph, 0, 0, rects}, {"java10.rec", pic10, pw, ph, 1, 0, rects},
                {"java_big.rec", bigWords, bw, bh, 0, 1, wide}};
        byte[] javaRec = null;
        for (Object[] m : made) {
            int[] words = (int[]) m[1];
            int w = (Integer) m[2], h = (Integer) m[3], format = (Integer) m[4];
            List<Object> pieces = new java.util.ArrayList<>();
            @SuppressWarnings("unchecked") List<android.graphics.Rect> parts = (List<android.graphics.Rect>) split.invoke(null, m[6]);
            for (android.graphics.Rect r : parts) {
                int[] part = new int[r.width() * r.height()];
                for (int y = 0; y < r.height(); y++) System.arraycopy(words, (r.top + y) * w + r.left, part, y * r.width(), r.width());
                pieces.add(newPiece.newInstance(new android.graphics.Rect(r), 0, (Integer) crc.invoke(null, (Object) part),
                        (byte[]) med.invoke(null, part, r.width(), r.height(), format)));
            }
            Object p = newPatch.newInstance(w, h, format, (Integer) m[5], spec, pieces);
            byte[] bytes = (byte[]) patch.getMethod("toBytes").invoke(p);
            Files.write(new File(dir, (String) m[0]).toPath(), bytes);
            System.out.println("Android record " + m[0] + ": " + p);
            if (javaRec == null) javaRec = bytes;
        }
        for (String kind : new String[]{"jpg", "webp", "heic"}) {
            File f = new File(dir, "java." + kind);
            Files.copy(new File(dir, "base." + kind).toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!com.scamera.app.processing.watermark.WatermarkStore.append(f, javaRec))
                throw new IOException("not appended to " + kind);
        }
        // the iPhone's records as Android reads them
        for (String name : new String[]{"cpp.jpg", "cpp.webp", "cpp.heic", "cpp_big.rec"}) {
            File f = new File(dir, name);
            Object p;
            if (name.endsWith(".rec")) p = patch.getMethod("fromBytes", byte[].class).invoke(null, (Object) Files.readAllBytes(f.toPath()));
            else p = com.scamera.app.processing.watermark.WatermarkStore.read(f);
            if (p == null) throw new IOException(name + ": no record read");
            if (!(Boolean) patch.getMethod("verify").invoke(p)) throw new IOException(name + ": the record does not verify");
            if (!spec.equals(patch.getField("spec").get(p))) throw new IOException(name + ": another spec");
            System.out.println("iPhone record " + name + ": " + p);
            List<?> pieces = (List<?>) piecesField.get(p);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            ByteBuffer n = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pieces.size());
            bytes.write(n.array());
            for (int i = 0; i < pieces.size(); i++) {
                android.graphics.Rect r = (android.graphics.Rect) rectField.get(pieces.get(i));
                int[] words = (int[]) wordsOf.invoke(p, i);
                ByteBuffer b = ByteBuffer.allocate(16 + 4 * words.length).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(r.left).putInt(r.top).putInt(r.right).putInt(r.bottom);
                for (int v : words) b.putInt(v);
                bytes.write(b.array());
            }
            Files.write(new File(dir, "android_" + name + ".words").toPath(), bytes.toByteArray());
        }
    }

    /** An 8-bit value as 10 bits (the high bits repeated), as edit_check --wm makes them. */
    private static int ten(int v) {
        return (v << 2) | (v >> 6);
    }

    private static byte[] read(int w, int h) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b);
        byte[] a = new byte[w * h * 4];
        b.get(a);
        return a;
    }

    private static void dump(File f, byte[] rgba, int w, int h) throws IOException {
        ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(0x38524353).putInt(w).putInt(h).putInt(4);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(hdr.array());
            o.write(rgba);
        }
    }
}
