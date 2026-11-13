package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * P482 (ahb_transport): the route's tuning gate (the worker reads the last fastMerge line) and the client's contract: off, a
 * merged DNG, above 16 MP or without the fast merge the job has no socket and no marker (the memfd as before); the RGB leaves the
 * memfd only on the worker's 'A' answer; a view of the hardware buffer is freed by Allocator.free like the memfd's view.
 */
public class AhbRouteTest {
    @Test
    public void tuningValueTakesTheLastLine() {
        assertEquals(0, AhbRoute.tuningValue(null, "fastMerge"));
        assertEquals(0, AhbRoute.tuningValue("laStride 16\n", "fastMerge"));
        assertEquals(1, AhbRoute.tuningValue("laStride 16\nfastMerge 1\n", "fastMerge"));
        assertEquals(0, AhbRoute.tuningValue("fastMerge 1\nfastMergeTetra 1\nfastMerge 0\n", "fastMerge"));
        assertEquals(1, AhbRoute.tuningValue("fastMerge 0\n  fastMerge 1  \n", "fastMerge"));
        assertEquals(0, AhbRoute.tuningValue("fastMergeSport 3\n", "fastMerge"));
        assertEquals(0, AhbRoute.tuningValue("fastMerge x\n", "fastMerge"));
    }

    private static String source(String path) throws Exception {
        final Path p = Paths.get("src/main/" + path);
        return new String(Files.readAllBytes(Files.exists(p) ? p : Paths.get("app/src/main/" + path)), StandardCharsets.UTF_8);
    }

    @Test
    public void clientKeepsTheMemfdUnlessTheWorkerAnswersA() throws Exception {
        final String route = source("java/com/scamera/app/processing/opengl/postpipeline/AhbRoute.java");
        assertTrue("off by default", route.contains("scamDevSwitch(\"ahb_transport\", false)"));
        assertTrue("16 MP, no merged DNG", route.contains("outputPixels <= ScamHybridBurst.CLASSIC_INPUT_PIXELS && !mergedDng"));
        assertTrue("fast merge only", route.contains("tuningValue(tuning, \"fastMerge\") > 0"));
        final String client = source("java/com/scamera/app/processing/opengl/postpipeline/ScamNeuralClient.java");
        assertTrue("direct worker only", client.contains("ahbWanted=direct&&AhbRoute.wanted("));
        assertTrue("answer decides", client.contains("ahb.answer(200)==AhbRoute.IN_BUFFER"));
        assertTrue("memfd layout without the RGB only then", client.contains("final long expected=rgbInAhb?0:rgbBytes;"));
        assertTrue("socket closed with the job", client.contains("if(ahb!=null)ahb.close();"));
        final String free = source("cpp/allocator.cpp");
        assertTrue("Allocator.free releases a view", free.contains("if (scamAhbFreeView(p)) return;"));
        final String upload = source("java/com/scamera/app/processing/opengl/postpipeline/HalfRgbUpload.java");
        assertTrue("imported, else uploaded from the view", upload.contains("if (imported != 0 && runImported(prog, tex, size.x, size.y, rgb, imported)) return tex;"));
        assertTrue("the view's free waits for the post's read", upload.contains("HardwareRgb.fence(view)"));
    }
}
