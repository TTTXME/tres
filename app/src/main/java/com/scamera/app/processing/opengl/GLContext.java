package com.scamera.app.processing.opengl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES31;

import com.scamera.app.settings.PreferenceKeys;

/**
 * An off-screen EGL context for the processing passes: an RGBA8888 pbuffer config (every pass renders into textures or
 * renderbuffers, the pbuffer only has to exist), OpenGL ES 3, and the context's {@link GLProg} with its linked programs.
 *
 * <p>P353: the post pipeline's context is kept for the process ({@code shared}): one pipeline at a time takes it, with its
 * programs (a fresh context loaded ~46 program binaries again, 60 ms); another one meanwhile makes a context of its own.
 * Textures and framebuffers still go at the end of every shot (GLTexture.closeAll, GLCoreBlockProcessing.close), and the
 * GL state is reset to the defaults when the kept context is taken. scam_dev post_shared_context 0: a new one every shot.
 */
public class GLContext implements AutoCloseable {
    /** EGL_IMG_context_priority (P57 / P364). */
    static final int EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100, EGL_CONTEXT_PRIORITY_MEDIUM_IMG = 0x3102, EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;

    // the context kept for the process (P353) and whether a pipeline holds it
    private static EGLDisplay keptDisplay;
    private static EGLContext keptContext;
    private static EGLSurface keptSurface;
    private static GLProg keptProgram;
    private static boolean keptTaken;

    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface surface;
    private boolean holdsKept;
    public GLProg program;
    /** The tile target of GLCoreBlockProcessing: its framebuffer and renderbuffer names. */
    public final int[] tileFramebuffer = new int[1], tileRenderbuffer = new int[1];

    public GLContext(int surfaceWidth, int surfaceHeight) {
        createContext(surfaceWidth, surfaceHeight);
    }

    public GLContext(int surfaceWidth, int surfaceHeight, boolean shared) {
        final boolean keep = shared && sharingAllowed();
        if (keep && takeKept()) return;
        createContext(surfaceWidth, surfaceHeight);
        if (keep) offerAsKept();
    }

    private static boolean sharingAllowed() {
        try {
            return PreferenceKeys.scamDevSwitch("post_shared_context", true);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private boolean takeKept() {
        synchronized (GLContext.class) {
            if (keptContext == null || keptTaken) return false;
            if (!EGL14.eglMakeCurrent(keptDisplay, keptSurface, keptSurface, keptContext)) return false;
            keptTaken = true;
            display = keptDisplay;
            context = keptContext;
            surface = keptSurface;
            program = keptProgram;
            holdsKept = true;
        }
        resetState();
        return true;
    }

    /** P662: whether a context is kept for the process already (a shot made it: the idle warm-up then makes its own). */
    public static boolean keptExists() {
        synchronized (GLContext.class) {
            return keptContext != null;
        }
    }

    /** P662: whether this is the context kept for the process (taken or just made and offered). */
    public boolean holdsKept() {
        return holdsKept;
    }

    /** This new context becomes the kept one when none is kept yet. */
    private void offerAsKept() {
        synchronized (GLContext.class) {
            if (keptContext != null) return;
            keptDisplay = display;
            keptContext = context;
            keptSurface = surface;
            keptProgram = program;
            keptTaken = true;
            holdsKept = true;
        }
    }

    /** The defaults of a fresh context, for the state the passes rely on (the kept context comes from the last shot). */
    private static void resetState() {
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        GLES31.glBindRenderbuffer(GLES31.GL_RENDERBUFFER, 0);
        GLES31.glUseProgram(0);
        GLES31.glBindVertexArray(0);
        for (int target : new int[]{GLES31.GL_ARRAY_BUFFER, GLES31.GL_ELEMENT_ARRAY_BUFFER, GLES31.GL_PIXEL_PACK_BUFFER,
                GLES31.GL_PIXEL_UNPACK_BUFFER, GLES31.GL_SHADER_STORAGE_BUFFER, GLES31.GL_UNIFORM_BUFFER})
            GLES31.glBindBuffer(target, 0);
        for (int unit = 0; unit < 16; unit++) {
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, 0);
        }
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
        for (int capability : new int[]{GLES31.GL_BLEND, GLES31.GL_DEPTH_TEST, GLES31.GL_SCISSOR_TEST, GLES31.GL_CULL_FACE})
            GLES31.glDisable(capability);
        GLES31.glColorMask(true, true, true, true);
        GLES31.glPixelStorei(GLES31.GL_PACK_ALIGNMENT, 4);
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ALIGNMENT, 4);
        GLES31.glPixelStorei(GLES31.GL_UNPACK_ROW_LENGTH, 0);
        GLES31.glPixelStorei(GLES31.GL_PACK_ROW_LENGTH, 0);
        //noinspection StatementWithEmptyBody: an error left from the last shot is not this one's
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) { }
    }

    public void createContext(int surfaceWidth, int surfaceHeight) {
        display = EGL14.eglGetDisplay(GLDrawParams.EGLDisplay);
        final int[] version = new int[2];
        EGL14.eglInitialize(display, version, 0, version, 1);
        // the first config of the sorted list is the one taken (P659: asked for directly, no second query for the whole list)
        final EGLConfig[] config = new EGLConfig[1];
        final int[] found = new int[1];
        if (!EGL14.eglChooseConfig(display, GLDrawParams.attribList, 0, config, 0, 1, found, 0)) {
            throw new RuntimeException("OpenGL config loading failed");
        }
        if (found[0] == 0) throw new RuntimeException("OpenGL config count zero");
        if (config[0] == null) throw new RuntimeException("OpenGL config is null");
        context = createProcessingContext(display, config[0]);
        // P27 any resolution: a frame wider than EGL_MAX_PBUFFER_* gets a pbuffer at that limit (it is never drawn to)
        final int width = pbufferSide(config[0], EGL14.EGL_MAX_PBUFFER_WIDTH, surfaceWidth);
        final int height = pbufferSide(config[0], EGL14.EGL_MAX_PBUFFER_HEIGHT, surfaceHeight);
        surface = EGL14.eglCreatePbufferSurface(display, config[0],
                new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(display, surface, surface, context);
        program = new GLProg();
    }

    private int pbufferSide(EGLConfig config, int limitAttribute, int wanted) {
        final int[] limit = new int[1];
        return EGL14.eglGetConfigAttrib(display, config, limitAttribute, limit, 0) && limit[0] > 0 && wanted > limit[0] ? limit[0] : wanted;
    }

    /**
     * P57 / P364: the processing context at a lower GPU priority (EGL_IMG_context_priority) so the viewfinder's draws preempt
     * the post pipeline - medium by default (scam_dev gpu_low_priority 2), low with 1, normal with 0 or without the
     * extension; a refused hint falls back to a plain ES 3 context. Rendering results are unchanged.
     */
    private static EGLContext createProcessingContext(EGLDisplay display, EGLConfig config) {
        final String extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
        final float mode = PreferenceKeys.scamDevNumber("gpu_low_priority", 2f);
        if (extensions != null && extensions.contains("EGL_IMG_context_priority") && mode != 0f) {
            final int level = mode == 1f ? EGL_CONTEXT_PRIORITY_LOW_IMG : EGL_CONTEXT_PRIORITY_MEDIUM_IMG;
            final EGLContext prioritised = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, level, EGL14.EGL_NONE}, 0);
            if (prioritised != null && prioritised != EGL14.EGL_NO_CONTEXT) return prioritised;
            EGL14.eglGetError();
        }
        return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, GLDrawParams.contextAttributeList, 0);
    }

    /**
     * Makes this context current on the calling thread again (W1.3: the deferred teardown after the JPEG encode must delete
     * the textures of this context even if another one was made current in between).
     */
    public void makeCurrent() {
        if (display != null && context != null) EGL14.eglMakeCurrent(display, surface, surface, context);
    }

    private interface Step { void run(); }

    /** EGL teardown steps never stop the teardown (a lost context throws on some drivers). */
    private static void quietly(Step step) {
        try {
            step.run();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        if (display == null) return;
        final EGLDisplay d = display;
        final EGLContext c = context;
        final EGLSurface s = surface;
        display = null;
        context = null;
        surface = null;
        if (holdsKept) { // P353: released for the next shot, the context and its programs kept
            holdsKept = false;
            quietly(() -> EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT));
            synchronized (GLContext.class) { keptTaken = false; }
            return;
        }
        quietly(() -> { if (program != null) program.close(); });
        quietly(() -> EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT));
        quietly(() -> { if (c != null) EGL14.eglDestroyContext(d, c); });
        quietly(() -> { if (s != null) EGL14.eglDestroySurface(d, s); });
        quietly(() -> EGL14.eglTerminate(d));
    }
}
