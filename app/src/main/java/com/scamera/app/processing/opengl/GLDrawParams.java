package com.scamera.app.processing.opengl;

/** Shared sizes of the processing passes. */
public class GLDrawParams {
    /** Rows of one tile of a block read-back (GLCoreBlockProcessing) and of the tiled passes. */
    public static int TILE_ROWS = 256; // the post's bands and output tiles (P683: 256 for every picture, no 8-row tiles under 8 MP)
    /** Channels of the pipeline's working textures (RGBA). */
    public final static int WORK_CHANNELS = 4;

    /** The processing contexts' EGL display, pbuffer config (RGBA8888, no depth / stencil, ES 2+) and ES 3 context. */
    final static int EGLDisplay = android.opengl.EGL14.EGL_DEFAULT_DISPLAY;
    final static int[] attribList = {
            android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8, android.opengl.EGL14.EGL_BLUE_SIZE, 8,
            android.opengl.EGL14.EGL_ALPHA_SIZE, 8, android.opengl.EGL14.EGL_DEPTH_SIZE, 0, android.opengl.EGL14.EGL_STENCIL_SIZE, 0,
            android.opengl.EGL14.EGL_BIND_TO_TEXTURE_RGBA, android.opengl.EGL14.EGL_TRUE,
            android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_NONE};
    final static int[] contextAttributeList = {android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, android.opengl.EGL14.EGL_NONE};

    /** Where a read-back of GLCoreBlockProcessing lands: nowhere (GPU only), a direct buffer or the Java heap. */
    public enum Allocate { None, Direct, Heap }
}
