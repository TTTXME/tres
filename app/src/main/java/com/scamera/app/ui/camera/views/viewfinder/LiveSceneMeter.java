package com.scamera.app.ui.camera.views.viewfinder;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Measures the mean brightness of the live stream on the GPU for the manual panel's software shutter / ISO
 * priority (P82, SceneLuma): the external preview texture is rendered into a small offscreen target and read back.
 *
 * <p>Cost is bounded by measuring at {@link #TILE} squared and only every
 * {@link #EVERY_N_FRAMES} frames: the readback is the expensive part because it
 * stalls the GL thread until the render completes, and a 64x64 RGBA readback is
 * 16 KB.
 */
final class LiveSceneMeter {

    /** Measurement resolution. Tone placement is a global statistic; it does not need pixels. */
    private static final int TILE = 64;
    /** Frames between measurements. At 30 fps this is about four updates a second. */
    private static final int EVERY_N_FRAMES = 8;

    private static final String VS =
            "attribute vec2 vPosition;\n" +
            "attribute vec2 vTexCoord;\n" +
            "varying vec2 texCoord;\n" +
            "void main() {\n" +
            "  texCoord = vTexCoord;\n" +
            "  gl_Position = vec4(vPosition, 0.0, 1.0);\n" +
            "}\n";

    /**
     * Averages a block of the preview per output texel (four taps a quarter of a texel round its centre): its display RGB, and in
     * alpha its (display-encoded) luminance (SceneLuma). P643d: the RGB is the ScamMaster tone preview's sample of the frame.
     */
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform vec2 uQuarter;\n" +
            "varying vec2 texCoord;\n" +
            "void main() {\n" +
            "  vec3 c = 0.25 * (texture2D(sTexture, texCoord + uQuarter).rgb + texture2D(sTexture, texCoord - uQuarter).rgb\n" +
            "      + texture2D(sTexture, texCoord + vec2(uQuarter.x, -uQuarter.y)).rgb + texture2D(sTexture, texCoord + vec2(-uQuarter.x, uQuarter.y)).rgb);\n" +
            "  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
            "  gl_FragColor = vec4(c, y);\n" +
            "}\n";

    private int program = 0;
    private int fbo = 0;
    private int tex = 0;
    private int aPosition, aTexCoord, uTexture, uQuarter;
    private boolean failed = false;
    private int frameCounter = 0;
    private boolean pendingRead = false;
    private final int[] prevFbo = new int[1], prevViewport = new int[4];

    private final ByteBuffer readback =
            ByteBuffer.allocateDirect(TILE * TILE * 4).order(ByteOrder.nativeOrder());

    /**
     * Called once per drawn frame with the vertex buffers already prepared by
     * the renderer. Does nothing on most frames; the caller's GL state is
     * restored before returning.
     */
    void measure(java.nio.FloatBuffer pVertex, java.nio.FloatBuffer pTexCoord, int previewTexture) {
        measure(pVertex, pTexCoord, previewTexture, EVERY_N_FRAMES, 1f);
    }

    /** P643d: frames between measurements while the ScamMaster tone preview follows the frame's tile (about 15 a second). */
    static final int LIVE_TONE_FRAMES = 2;
    /** The series gain of the frame in the tile waiting to be read, and frames since SceneLuma got a value. */
    private float pendingGain = 1f;
    private int sinceScene = EVERY_N_FRAMES;

    /**
     * As above every {@code every} frames; {@code gain}: the frame's series gain (MainRenderer's uGain: the tone sees the frame
     * times it). P643d: the tile also gives the frame's RGB to the ScamMaster tone preview (OldstyleLive.screen).
     */
    void measure(java.nio.FloatBuffer pVertex, java.nio.FloatBuffer pTexCoord, int previewTexture, int every, float gain) {
        if (failed) return;
        sinceScene++;
        if (pendingRead) { // P551: read the tile one frame after it was drawn - the GPU has finished it, no pipeline stall
            pendingRead = false;
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            readback.position(0);
            GLES20.glReadPixels(0, 0, TILE, TILE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
            publish();
            return;
        }
        if (++frameCounter < Math.max(1, every)) return;
        frameCounter = 0;
        pendingGain = gain;

        if (program == 0 && !init()) return;

        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, TILE, TILE);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, previewTexture);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glUniform2f(uQuarter, 0.25f / TILE, 0.25f / TILE);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        pVertex.position(0);
        pTexCoord.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, pVertex);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, pTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        // MainRenderer rebinds and enables its own attributes before the next draw.

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        pendingRead = true;
    }

    void onContextCreated() {
        program = fbo = tex = 0;
        failed = false;
        frameCounter = 0;
        pendingRead = false;
    }

    private void publish() {
        readback.position(0);
        long sum = 0;
        for (int i = 0; i < TILE * TILE; i++) sum += readback.get(i * 4 + 3) & 0xFF;
        // P82: the mean brightness for the manual panel's shutter / ISO priority (at its own rate: one tile in eight frames)
        if (com.scamera.app.manual.SceneLuma.wanted() && sinceScene >= EVERY_N_FRAMES) {
            sinceScene = 0;
            com.scamera.app.manual.SceneLuma.publish(sum / (255.0 * TILE * TILE));
        }
        // P643d: the frame's tile for the ScamMaster tone preview
        final com.scamera.app.processing.oldstyle.OldstyleLive live =
                com.scamera.app.processing.oldstyle.OldstyleLive.shared();
        if (live.wantsScreen()) live.screen(System.nanoTime(), tileRgb(readback, TILE, pendingGain), TILE, TILE);
    }

    private static final float[] LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) LINEAR[i] = (float) Math.pow(i / 255.0, 2.2);
    }

    /**
     * P643d: the tile ({@code size} x {@code size} RGBA8, read from the bottom row up) as display RGB 0..1, rows in the order of the
     * external texture's t (the tile is drawn with the texture coordinates as they are: its bottom row is t = 1), with the frame's
     * series gain in light (the power 2.2 and back, at most white) when it is not 1 - the frame as the viewfinder's shader has it.
     */
    static float[] tileRgb(ByteBuffer rgba, int size, float gain) {
        final float[] out = new float[size * size * 3];
        for (int j = 0; j < size; j++) {
            final int row = size - 1 - j;   // the readback's row j is at t = 1 - (j + 0.5) / size
            for (int i = 0; i < size; i++)
                for (int c = 0; c < 3; c++) {
                    final int v = rgba.get((j * size + i) * 4 + c) & 0xFF;
                    out[(row * size + i) * 3 + c] = gain == 1f ? v / 255f : (float) Math.pow(Math.min(1f, LINEAR[v] * gain), 1.0 / 2.2);
                }
        }
        return out;
    }

    private boolean init() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        if (vs == 0 || fs == 0) {
            failed = true;
            return false;
        }
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glBindAttribLocation(program, 0, "vPosition");
        GLES20.glBindAttribLocation(program, 1, "vTexCoord");
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e("LiveSceneMeter", "link failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
            failed = true;
            return false;
        }
        aPosition = GLES20.glGetAttribLocation(program, "vPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "vTexCoord");
        uTexture = GLES20.glGetUniformLocation(program, "sTexture");
        uQuarter = GLES20.glGetUniformLocation(program, "uQuarter");

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, TILE, TILE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("LiveSceneMeter", "incomplete framebuffer: " + status);
            failed = true;
            return false;
        }
        Log.d("LiveSceneMeter", "measuring at " + TILE + "x" + TILE
                + " every " + EVERY_N_FRAMES + " frames");
        return true;
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e("LiveSceneMeter", "compile failed: " + GLES20.glGetShaderInfoLog(sh));
            GLES20.glDeleteShader(sh);
            return 0;
        }
        return sh;
    }
}
