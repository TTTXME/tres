package com.scamera.app.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P483: the streamed JPEG (the final readback's mapped buffer straight into jpegli) must give the bytes of the bitmap path. The
 * native library does not run on the JVM host, so this pins the structure that makes them equal - both JNI entries run the one
 * encodePixels body with the same settings, the buffer entry with the caller's row stride - and the Java wiring: no bitmap is made for
 * a sink, a sink result whose buffer was lost is dropped, off ("jpeg_stream 0") = no sink. The byte equality itself is checked on
 * the phone by the complete30 harness v7 (stream_self_check: a synthetic 4096 x 3072 image through both entries).
 */
public class JpegStreamContractTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    @Test public void bothNativeEntriesShareOneEncodeBody() throws IOException {
        final String cpp = read("src/main/cpp/scamera-jpeg.cpp");
        assertEquals("encodePixels: its definition and the two entries' calls", 3, count(cpp, "encodePixels("));
        // the parallel strips and the one-thread fallback only in the shared body
        assertEquals(1, count(cpp, "scamera_jpeg::parallel::encodeRgba("));
        assertEquals(1, count(cpp, "scamera_jpeg::encodeRgba("));
        final String buffer = cpp.substring(cpp.indexOf("JpegliEncoder_encodeBuffer"));
        assertTrue("the rows at the caller's stride (a sub-rectangle: the bytes of a bitmap of it)",
                buffer.contains("size_t(stride), quality, out, threads, modeOut, aq,") && buffer.contains("int64_t(stride) < int64_t(width) * 4"));
        assertTrue("the same settings as the bitmap entry", buffer.contains("scamera_jpeg::adaptiveQuantisation().load()")
                && buffer.contains("scamera_jpeg::parallel::fastHuffmanTables().load()"));
    }

    @Test public void javaEntriesShareTheirSettings() throws IOException {
        final String java = read("src/main/java/com/scamera/app/processing/JpegliEncoder.java");
        assertEquals("the bitmap and the buffer compress run the one private body", 1,
                count(java, "private static void compress(Bitmap bitmap, java.nio.ByteBuffer rgba"));
        assertTrue(java.contains("encode(bitmap, quality, out, threads(), mode) : encodeBuffer(rgba, width, height, stride, quality, out, threads(), mode)"));
    }

    @Test public void sinkWiring() throws IOException {
        final String gl = read("src/main/java/com/scamera/app/processing/opengl/GLCoreBlockProcessing.java");
        final int noSink = gl.indexOf("if (sink == null) {");
        assertTrue("no bitmap is made for a sink", noSink > 0
                && noSink < gl.indexOf("dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);\n                    JpegliEncoder.touchPixels(dst);"));
        assertTrue("the sink sees the mapping before the bitmap copy",
                gl.indexOf("sink.accept(src, outW, outH)") < gl.indexOf("JpegliEncoder.copyPixels(src, dst)"));
        final String post = read("src/main/java/com/scamera/app/processing/opengl/postpipeline/PostPipeline.java");
        assertTrue("off: no sink", post.contains("PreferenceKeys.scamDevSwitch(\"jpeg_stream\", true)"));
        assertTrue("a result whose buffer was lost is dropped", post.contains("if (!glproc.sinkTook) streamedJpeg = null;"));
        assertFalse("never for the 10-bit output", post.contains("rgbaSink = jpegQuality > 0 && !legacyRun && tenBitOutput"));
    }
}
