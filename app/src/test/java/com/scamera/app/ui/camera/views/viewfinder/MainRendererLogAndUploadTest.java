package com.scamera.app.ui.camera.views.viewfinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P656: the viewfinder's WHY tone line once per change of its state (owner's logs of 11-12: «the photo's WHY tone not drawn (no
 * frame)» on every draw - 875 of the 4158 lines of log 7, 590 of log 2), and the ScamMaster grid's upload buffer kept between the
 * grid's generations (a 72 KB direct buffer per generation before, up to every 30 ms on the GL thread).
 */
public class MainRendererLogAndUploadTest {
    private static String src() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java").toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void theWhyToneStatesAreDistinct() {
        assertEquals(1, MainRenderer.whyLogState(true, true, true));
        assertEquals(2, MainRenderer.whyLogState(false, true, true));
        assertEquals(3, MainRenderer.whyLogState(false, false, true));
        assertEquals(4, MainRenderer.whyLogState(false, false, false));
        assertEquals("on", MainRenderer.WHY_LOG_STATES[1]);
        assertEquals("not drawn (no frame)", MainRenderer.WHY_LOG_STATES[4]);
    }

    /** A run of draws without a RAW frame logs one line, not one a draw; a change of the state logs again. */
    @Test
    public void theLineIsWrittenOnlyWhenTheStateChanges() throws Exception {
        final String s = src().replace("\r\n", "\n");
        final int state = s.indexOf("final int whyState = whyLogState(rawActive, mWhyTone.failed(), fusedFailedNow);");
        final int gate = s.indexOf("if (whyState != mWhyLogged) {", state);
        final int keep = s.indexOf("mWhyLogged = whyState;", gate);
        final int log = s.indexOf("Log.d(\"MainRenderer\", \"viewfinder: the photo's WHY tone \" + WHY_LOG_STATES[whyState]);", gate);
        assertTrue(state > 0 && gate > state && keep > gate && log > gate);
        assertFalse("the old flag the line itself reset", s.contains("mWhyLogged = rawActive;"));
        // the same simulated: 100 draws without a frame, 1 with, 100 without -> 3 lines
        int last = 0, lines = 0;
        for (int i = 0; i < 201; i++) {
            final int now = MainRenderer.whyLogState(i == 100, false, false);
            if (now != last) { last = now; lines++; }
        }
        assertEquals(3, lines);
    }

    @Test
    public void theGridsUploadBufferIsKept() throws Exception {
        final float[] a = new float[com.scamera.app.processing.oldstyle.OldstyleLive.FLOATS];
        for (int i = 0; i < a.length; i++) a[i] = i * 0.5f;
        final FloatBuffer first = MainRenderer.gridUpload(null, a);
        assertEquals(0, first.position());
        assertEquals(a.length, first.remaining());
        assertEquals(a[a.length - 1], first.get(a.length - 1), 0f);
        final float[] b = a.clone();
        b[7] = -3f;
        final FloatBuffer second = MainRenderer.gridUpload(first, b);
        assertSame("the same buffer for the next generation", first, second);
        assertEquals(0, second.position());
        assertEquals(b.length, second.remaining());
        assertEquals(-3f, second.get(7), 0f);
        // a larger grid (never today): a new buffer
        assertFalse(second == MainRenderer.gridUpload(second, new float[a.length + 1]));
        assertTrue(src().contains("mGridUpload = gridUpload(mGridUpload, g.coefficients)"));
        assertFalse(src().contains("ByteBuffer.allocateDirect(g.coefficients.length * 4)"));
    }
}
