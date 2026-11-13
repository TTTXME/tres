package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P511 (owner, 2026-11-05: Pixel 10 Pro XL, «в режиме качество всё ещё черные фото»; a Pixel 7 the same, both Android 17): the
 * SCAM merge's strips read back all zero while the fast merge worked - on PowerVR / Mali every pass goes in a quarter of the rows
 * per dispatch, each its own submission (byte-identical output: OPPO replay md5 239be1a5 both ways); the first zero strip probes
 * every buffer and a one-line compute self-test into the log. And the gallery's system delete / trash request on a row already
 * gone no longer crashes it («Invalid Uri», Pixel 10 Android 17).
 */
public class GpuSmallJobsTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void powerVrAndMaliRunShortComputeSubmissions() throws IOException {
        final String h = read("src/main/cpp/scam-hybrid.h");
        assertTrue(h.contains("smallJobs=renderer.find(\"PowerVR\")!=std::string::npos||renderer.find(\"Mali\")!=std::string::npos"));
        assertTrue(h.contains("if(smallJobs)chunk=std::max(8,(chunk/4)&~7);"));
        assertTrue(h.contains("if(flushEach||smallJobs)glFlush();"));
        assertTrue("the probe at the first strip still zero", h.contains("if(still&&!zeroProbed&&trace){zeroProbed=true;trace(zeroProbe(p.bank));}"));
        assertTrue(h.contains("self-test compute -> SSBO"));
    }

    @Test public void theGallerysSystemRequestNeverCrashesOnAGoneRow() throws IOException {
        final String t = read("src/main/java/com/scamera/app/gallery/files/GalleryTrash.java");
        assertTrue(t.contains("catch (IllegalArgumentException | SecurityException e)"));
        assertTrue(t.contains("safe(() -> MediaStore.createDeleteRequest("));
        assertTrue(t.contains("safe(() -> MediaStore.createTrashRequest("));
        final String o = read("src/main/java/com/scamera/app/gallery/files/GalleryFileOperations.java");
        assertTrue(o.contains("GalleryTrash.trashRequest(activity, uris, true)"));
        assertTrue(!o.contains("MediaStore.createTrashRequest(") && !o.contains("MediaStore.createDeleteRequest("));
    }
}
