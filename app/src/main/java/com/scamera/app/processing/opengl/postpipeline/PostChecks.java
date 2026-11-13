package com.scamera.app.processing.opengl.postpipeline;

import android.opengl.GLES30;

import com.scamera.app.processing.opengl.GLProg;

/**
 * P662 (item 3): the post pipeline's one-time checks of the driver, run by the idle warm-up (GLProg.warmUp) in the shared post
 * context instead of the first shot of a process - HalfRgbUpload.exact (15 - 42 ms there) and WhyStats.halfReadbackExact (21 - 55
 * ms). Each runs once per process wherever it runs first; their results, logs and the paths they choose are the same.
 */
public final class PostChecks {
    private PostChecks() {}

    /** Both checks not run yet in this process, on {@code prog}'s context (current); "" when none ran, else what each found. */
    public static String warmUp(GLProg prog) {
        final StringBuilder out = new StringBuilder();
        if (!HalfRgbUpload.checked()) out.append("half RGB upload ").append(HalfRgbUpload.exact(prog) ? "exact" : "CPU repack");
        if (!WhyStats.halfReadbackChecked()) {
            final int halfType = halfReadTypeOfRgba16f();
            if (halfType != 0) {
                if (out.length() > 0) out.append(", ");
                out.append("WHY half read-back ").append(WhyStats.halfReadbackExact(halfType) ? "exact" : "floats");
            }
        }
        return out.toString();
    }

    /** WhyStats.halfReadType of an RGBA16F read framebuffer (whyLow's format), 0 when not halves or on a GL error. */
    static int halfReadTypeOfRgba16f() {
        final int[] texture = new int[1], framebuffer = new int[1], oldRead = new int[1], oldTexture = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, oldTexture, 0);
        try {
            GLES30.glGenTextures(1, texture, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0]);
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, 4, 4);
            GLES30.glGenFramebuffers(1, framebuffer, 0);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture[0], 0);
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return 0;
            return WhyStats.halfReadType();
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, oldTexture[0]);
            if (framebuffer[0] != 0) GLES30.glDeleteFramebuffers(1, framebuffer, 0);
            if (texture[0] != 0) GLES30.glDeleteTextures(1, texture, 0);
            GLES30.glGetError();
        }
    }
}
