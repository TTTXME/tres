package com.scamera.app.processing.opengl;

import android.graphics.Point;

import com.scamera.app.util.Log;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.HashMap;

import static android.opengl.GLES31.*;
import static com.scamera.app.processing.opengl.GLCoreBlockProcessing.checkEglError;

/**
 * An immutable-storage 2D texture of the processing context (glTexStorage2D, one level) with its sampling, its optional
 * framebuffer (render target) and its bookkeeping: P477's pool (a data-less texture may take a closed one's name and
 * storage), the inventory of live names (closeAll) and P479's timing marks. Editing never disturbs the caller's binding of
 * GL_TEXTURE_2D.
 */
public class GLTexture implements AutoCloseable {
    public Point mSize;
    /** The sized internal format of the storage. */
    public final int mGLFormat;
    public final int mTextureID;
    /** The framebuffer of this texture once {@link #Bufferize} made one. */
    public int mBuffer;
    public boolean isBuffered = false;
    public final GLFormat mFormat;

    /** The names this class made and has not deleted yet (closeAll); a texture frees its own slot (GLTextureSlots). */
    private static final GLTextureSlots SLOTS = new GLTextureSlots(256);
    /** Framebuffer of each texture name that has one (Bufferize), for closeAll. */
    private static final HashMap<Integer, Integer> FRAMEBUFFERS = new HashMap<>();
    private static int created = 0;
    /** This texture's slot in {@link #SLOTS} (0: not tracked, or closed). */
    private int slot;

    // --- the shapes callers make textures in; every one ends in make() ---
    public GLTexture(GLTexture like, GLFormat format) {
        this(like.mSize, new GLFormat(format), null, like.mFormat.filter, like.mFormat.wrap, 0);
    }
    public GLTexture(GLTexture like) {
        this(like.mSize, like.mFormat, null, like.mFormat.filter, like.mFormat.wrap, 0);
    }
    public GLTexture(int w, int h, GLFormat format, Buffer pixels) {
        this(new Point(w, h), new GLFormat(format), pixels, GL_LINEAR, GL_CLAMP_TO_EDGE, 0);
    }
    public GLTexture(int w, int h, GLFormat format, Buffer pixels, int filter, int wrap) {
        this(new Point(w, h), new GLFormat(format), pixels, filter, wrap, 0);
    }
    public GLTexture(Point size, GLFormat format, Buffer pixels, int filter, int wrap) {
        this(new Point(size), new GLFormat(format), pixels, filter, wrap, 0);
    }
    public GLTexture(Point size, GLFormat format, Buffer pixels) {
        this(new Point(size), new GLFormat(format), pixels, GL_LINEAR, GL_CLAMP_TO_EDGE, 0);
    }
    public GLTexture(int w, int h, GLFormat format, int level) {
        this(new Point(w, h), new GLFormat(format), null, GL_LINEAR, GL_CLAMP_TO_EDGE, level);
    }
    public GLTexture(int w, int h, GLFormat format) {
        this(new Point(w, h), new GLFormat(format), null, GL_LINEAR, GL_CLAMP_TO_EDGE, 0);
    }
    public GLTexture(int w, int h, GLFormat format, int filter, int wrap) {
        this(new Point(w, h), new GLFormat(format), null, filter, wrap, 0);
    }
    public GLTexture(Point size, GLFormat format, int level) {
        this(new Point(size), new GLFormat(format), null, GL_LINEAR, GL_CLAMP_TO_EDGE, level);
    }
    /** The format's own sampling. */
    public GLTexture(Point size, GLFormat format) {
        this(new Point(size), new GLFormat(format), null, format.filter, format.wrap, 0);
    }
    public GLTexture(Point size, GLFormat format, int filter, int wrap) {
        this(new Point(size), new GLFormat(format), null, filter, wrap, 0);
    }
    public GLTexture(GLImage image) {
        this(image, 0);
    }
    public GLTexture(GLImage image, int level) {
        this(image, GL_LINEAR, GL_CLAMP_TO_EDGE, level);
    }
    /** The image's pixels (its own GLFormat object takes the sampling); never a pooled name. */
    public GLTexture(GLImage image, int filter, int wrap, int level) {
        this(image.size, image.glFormat, rewound(image.byteBuffer), filter, wrap, level, false);
    }
    /** {@code format} is this texture's own from now on (its filter and wrap are set here). */
    public GLTexture(Point size, GLFormat format, Buffer pixels, int filter, int wrap, int level) {
        this(size, format, pixels, filter, wrap, level, true);
    }

    private static Buffer rewound(ByteBuffer pixels) {
        pixels.position(0);
        return pixels;
    }

    private GLTexture(Point size, GLFormat format, Buffer pixels, int filter, int wrap, int level, boolean poolable) {
        mFormat = format;
        format.filter = filter;
        format.wrap = wrap;
        mSize = size;
        mGLFormat = format.getGLFormatInternal();
        // P477: a texture without data may take a closed one's name and storage of its size and format ("tex_pool")
        final int pooled = poolable && pixels == null ? GLTexturePool.take(size.x, size.y, mGLFormat) : 0;
        mTextureID = pooled != 0 ? pooled : newName();
        // Shot speed (W1.0): no log line per texture; the count per run is in the "runAll programs" line (takeCreated)
        created++;
        slot = SLOTS.take(mTextureID);
        GLTexturePool.allocated(mTextureID, bytes(), describe(), pooled != 0);
        final int previous = beginEdit();
        try {
            final long t0 = GLGpuTimer.measuring() ? System.nanoTime() : 0L; // P479: post_sync only (profiling)
            if (pooled == 0) {
                glTexStorage2D(GL_TEXTURE_2D, 1, mGLFormat, size.x, size.y);
                checkEglError("glTexStorage2D");
            }
            if (t0 != 0L) GLGpuTimer.alloc(System.nanoTime() - t0, bytes());
            if (pixels != null) {
                final String was = GLGpuTimer.mark("upload");
                glTexSubImage2D(GL_TEXTURE_2D, level, 0, 0, size.x, size.y, format.getGLFormatExternal(), format.getGLType(), pixels);
                if (was != null) GLGpuTimer.mark(was);
            }
            checkEglError("glTexSubImage2D");
            applySampling();
            checkEglError("Tex glTexParameter");
        } finally {
            glBindTexture(GL_TEXTURE_2D, previous);
        }
    }

    private static int newName() {
        final int[] name = new int[1];
        glGenTextures(1, name, 0);
        return name[0];
    }

    /** Binds this texture for an edit and returns the GL_TEXTURE_2D binding to restore (names are not units). */
    private int beginEdit() {
        final int[] bound = new int[1];
        glGetIntegerv(GL_TEXTURE_BINDING_2D, bound, 0);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
        return bound[0];
    }

    /** The format's filter (minification and magnification) and wrap (both axes) on the bound texture. */
    private void applySampling() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, mFormat.filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mFormat.filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, mFormat.wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, mFormat.wrap);
    }

    /**
     * P187: this texture's min/mag filter from now on, for a texture that takes over another's role (the pipeline input as
     * main2: a main samples GL_LINEAR).
     */
    public void setFilter(int filter) {
        mFormat.filter = filter;
        final int previous = beginEdit();
        try {
            applySampling();
        } finally {
            glBindTexture(GL_TEXTURE_2D, previous);
        }
    }

    /** P187: RGBA16F, the format of the pipeline's ping-pong textures (main1 / main2). */
    public boolean mainFormat() {
        return mFormat.mFormat == GLFormat.DataType.FLOAT_16 && mFormat.mChannels == GLDrawParams.WorkDim;
    }

    /** New pixels for the whole level 0. */
    public void loadData(Buffer pixels) {
        final int previous = beginEdit();
        try {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, mSize.x, mSize.y, mFormat.getGLFormatExternal(), mFormat.getGLType(), pixels);
        } finally {
            glBindTexture(GL_TEXTURE_2D, previous);
        }
    }

    /** Makes this texture's framebuffer once (attached when bound: BindBuffer / BufferLoad). */
    public void Bufferize() {
        if (isBuffered) return;
        final int[] fbo = new int[1];
        glGenFramebuffers(1, fbo, 0);
        mBuffer = fbo[0];
        isBuffered = true;
        synchronized (FRAMEBUFFERS) {
            FRAMEBUFFERS.put(mTextureID, mBuffer);
        }
    }

    public void BindBuffer() {
        glBindFramebuffer(GL_FRAMEBUFFER, mBuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mTextureID, 0);
    }

    /** Renders into this texture from now on: its framebuffer bound with it attached, the viewport its size. */
    public void BufferLoad() {
        Bufferize();
        BindBuffer();
        glViewport(0, 0, mSize.x, mSize.y);
        checkEglError("Tex BufferLoad");
    }

    /** Binds this texture to texture unit {@code unit} (GL_TEXTURE0 + n). */
    public void bind(int unit) {
        glActiveTexture(unit);
        glBindTexture(GL_TEXTURE_2D, mTextureID);
        checkEglError("Tex " + mTextureID + " bind");
    }

    /** Reads the bound framebuffer (this texture after BufferLoad) into {@code output} in {@code format}. */
    public void textureBuffer(GLFormat format, ByteBuffer output) {
        glReadPixels(0, 0, mSize.x, mSize.y, format.getGLFormatExternal(), format.getGLType(), output);
    }

    /**
     * The bound framebuffer read into a new direct buffer (full-resolution reads are hundreds of MB: never the Java heap).
     * FLOAT_16 is transferred as GL_FLOAT, 4 bytes per channel.
     */
    public ByteBuffer textureBuffer(GLFormat format) {
        final int bytesPerChannel = format.mFormat == GLFormat.DataType.FLOAT_16 ? 4 : format.mFormat.mSize;
        final ByteBuffer out = ByteBuffer.allocateDirect(GLLimits.bufferBytes(mSize.x, mSize.y,
                (long) bytesPerChannel * format.mChannels, "texture readback"));
        textureBuffer(format, out);
        return out;
    }

    /** As {@link #textureBuffer(GLFormat)}; the buffer is direct either way. */
    public ByteBuffer textureBuffer(GLFormat format, boolean direct) {
        return textureBuffer(format);
    }

    /** P477: the bytes of this texture's storage (one level). */
    long bytes() {
        return (long) mSize.x * mSize.y * mFormat.mFormat.mSize * mFormat.mChannels;
    }

    private String describe() {
        return mSize.x + "x" + mSize.y + " " + mFormat.mFormat + "x" + mFormat.mChannels;
    }

    @Override
    public String toString() {
        return "GLTexture{mSize=" + mSize + ", mGLFormat=" + mGLFormat + ", mTextureID=" + mTextureID + ", mFormat=" + mFormat + '}';
    }

    public static void notClosed() {
        Log.d("GLTexture", "notClosed:" + SLOTS.inUse());
    }

    /** Textures created since the last call (per pipeline run: the "runAll programs" line). */
    public static synchronized int takeCreated() {
        final int n = created;
        created = 0;
        return n;
    }

    /** Deletes every texture still open, the pool's kept names and the framebuffers of the open textures. */
    public static void closeAll() {
        GLTexturePool.end(); // P477: the pool's kept names go too
        final int[] open = SLOTS.takeAll();
        if (open.length > 0) glDeleteTextures(open.length, open, 0);
        // W1.1: a texture attached to a framebuffer kept its storage until the EGL context went; the old model
        // (PostGlMode.legacy) left them to the context
        final int[] fbos;
        synchronized (FRAMEBUFFERS) {
            fbos = new int[FRAMEBUFFERS.size()];
            int i = 0;
            for (int fb : FRAMEBUFFERS.values()) fbos[i++] = fb;
            FRAMEBUFFERS.clear();
        }
        if (fbos.length > 0 && !PostGlMode.legacy()) glDeleteFramebuffers(fbos.length, fbos, 0);
    }

    @Override
    public void close() {
        final long t0 = GLGpuTimer.measuring() ? System.nanoTime() : 0L; // P479: post_sync only (profiling)
        // P477: kept for the next texture of its size and format (GLTexturePool, during a post run), else deleted
        final boolean kept = GLTexturePool.give(mTextureID, mSize.x, mSize.y, mGLFormat, bytes());
        if (!kept) glDeleteTextures(1, new int[]{mTextureID}, 0);
        GLTexturePool.closed(mTextureID, bytes(), kept);
        // its own slot, not the one its name would index (another texture's once a raw glGenTextures took a name: P369)
        SLOTS.release(slot, mTextureID);
        slot = 0;
        if (t0 != 0L) GLGpuTimer.free(System.nanoTime() - t0);
        if (!isBuffered) return;
        synchronized (FRAMEBUFFERS) {
            FRAMEBUFFERS.remove(mTextureID);
        }
        if (PostGlMode.legacy()) {
            // the old model (post_ab only): the FBO name went to glDeleteBuffers - framebuffer and storage lived on until the
            // EGL context was destroyed
            glDeleteBuffers(1, new int[]{mBuffer}, 0);
        } else {
            // W1.1: the framebuffer itself, which releases the texture's storage now
            glDeleteFramebuffers(1, new int[]{mBuffer}, 0);
            isBuffered = false;
        }
    }
}
