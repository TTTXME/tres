package com.scamera.app.ui.camera.views.viewfinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.TextureView;
import com.scamera.app.circularbarlib.api.ManualModeConsole;
import com.scamera.app.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * The viewfinder: a GL surface drawn on demand by {@link MainRenderer} (the ISP stream's texture, or the RAW viewfinder),
 * measured to the camera's aspect. It tells the camera code when its SurfaceTexture is there, as a TextureView would.
 */
public class GLPreview extends GLSurfaceView {
    static final float DEFAULT_GPU_PRIORITY = 1f;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098, PRIORITY = 0x3100, HIGH = 0x3101;
    private static final int PLACEHOLDER_FILL = Color.rgb(24, 24, 24), PLACEHOLDER_LINE = Color.rgb(58, 58, 58);
    MainRenderer mRenderer;
    /** The aspect the view is measured to (0: the parent's size). */
    private int aspectW, aspectH;
    private TextureView.SurfaceTextureListener textureListener;
    private Handler main;
    /** The layout editor's stand-in: no GL there. */
    private boolean editorPreview;
    private Paint placeholderFill, placeholderLine;
    // True while the renderer owns a SurfaceTexture bound to a live GL surface: TextureView#isAvailable() for the camera code,
    // so it need not wait for one-shot callbacks.
    private volatile boolean surfaceReady;

    public GLPreview(Context context) {
        super(context);
        setUp();
    }

    public GLPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        setUp();
    }

    private void setUp() {
        // the layout editor has no EGL context and no ScameraApp: a static placeholder there
        if (isInEditMode()) {
            editorPreview = true;
            placeholderFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            placeholderLine = new Paint(Paint.ANTI_ALIAS_FLAG);
            setBackgroundColor(PLACEHOLDER_FILL);
            return;
        }
        main = new Handler(Looper.getMainLooper());
        mRenderer = new MainRenderer(this);
        // ES 3.0: the RAW viewfinder uploads the sensor plane as an R16UI texture read with texelFetch from a usampler2D - neither
        // exists in ES 2.0 (glTexImage2D rejected the format, the viewfinder came out black or in garbage colours while the
        // shader compiled fine). ES 3.0 runs the ES 2.0 preview shaders as they are.
        setEGLContextClientVersion(3);
        // The shot's GPU contexts now run at medium priority. Keep the short viewfinder draws ahead of them;
        // unsupported or refused priority hints retain the ordinary ES 3 context (vf_gpu_priority 0 for comparison).
        setEGLContextFactory(new EGLContextFactory() {
            @Override
            public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
                return createViewfinderContext(egl, display, config,
                        com.scamera.app.settings.PreferenceKeys.scamDevNumber("vf_gpu_priority", DEFAULT_GPU_PRIORITY) != 0f);
            }

            @Override
            public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
                // P662: the RAW viewfinder's mapped pixel-unpack buffers go with the context: revoked first (no copy into them after)
                final MainRenderer renderer = mRenderer;
                if (renderer != null) renderer.onContextDestroying();
                egl.eglDestroyContext(display, context);
            }
        });
        setRenderer(mRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    static EGLContext createViewfinderContext(EGL10 egl, EGLDisplay display, EGLConfig config, boolean high) {
        final String extensions = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS);
        final boolean supported = extensions != null && java.util.Arrays.asList(extensions.trim().split("\\s+"))
                .contains("EGL_IMG_context_priority");
        EGLContext context = null;
        if (high && supported) {
            context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                    new int[]{EGL_CONTEXT_CLIENT_VERSION, 3, PRIORITY, HIGH, EGL10.EGL_NONE});
            if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                final int error = egl.eglGetError();
                Log.w("GLPreview", "viewfinder GPU priority request rejected: 0x" + Integer.toHexString(error));
            }
        }
        if (context == null || context == EGL10.EGL_NO_CONTEXT) {
            context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                    new int[]{EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE});
        }
        String granted = "unknown";
        if (supported && context != null && context != EGL10.EGL_NO_CONTEXT) {
            final int[] value = new int[1];
            if (egl.eglQueryContext(display, context, PRIORITY, value)) {
                granted = value[0] == HIGH ? "high" : value[0] == 0x3102 ? "medium"
                        : value[0] == 0x3103 ? "low" : "0x" + Integer.toHexString(value[0]);
            } else {
                egl.eglGetError();
            }
        }
        Log.i("GLPreview", "viewfinder GPU priority requested=" + (high ? "high" : "normal")
                + " supported=" + supported + " granted=" + granted);
        return context;
    }

    @Override
    public void draw(Canvas canvas) {
        if (editorPreview) drawEditorPreview(canvas);
        else super.draw(canvas);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (editorPreview) drawEditorPreview(canvas);
        else super.onDraw(canvas);
    }

    /** The layout editor's stand-in: a dark frame with a 3:4 frame inside. */
    private void drawEditorPreview(Canvas canvas) {
        final int w = canvas.getWidth(), h = canvas.getHeight();
        placeholderFill.setColor(PLACEHOLDER_FILL);
        canvas.drawRect(0, 0, w, h, placeholderFill);
        placeholderLine.setColor(PLACEHOLDER_LINE);
        placeholderLine.setStyle(Paint.Style.STROKE);
        placeholderLine.setStrokeWidth(Math.max(2f, h / 150f));
        canvas.drawRect(0, 0, w, h, placeholderLine);
        canvas.drawRect(0, 0, w * 3f / 4f, h * 3f / 4f, placeholderLine);
    }

    /**
     * The renderer made a fresh SurfaceTexture for the current GL surface: from now on the camera can open against it (the
     * listener hears it on the main thread).
     */
    public void fireOnSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int w, int h) {
        surfaceReady = true;
        main.post(() -> {
            final TextureView.SurfaceTextureListener listener = textureListener;
            if (listener != null) listener.onSurfaceTextureAvailable(surfaceTexture, w, h);
        });
    }

    private void textureGone(SurfaceTexture surfaceTexture) {
        main.post(() -> {
            final TextureView.SurfaceTextureListener listener = textureListener;
            if (listener != null) listener.onSurfaceTextureDestroyed(surfaceTexture);
        });
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // the system tore the GL surface down (activity stopped, app in the background): the old texture gets no frames any more
        surfaceReady = false;
        super.surfaceDestroyed(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);
        main.post(() -> {
            final TextureView.SurfaceTextureListener listener = textureListener;
            if (listener != null) listener.onSurfaceTextureSizeChanged(getSurfaceTexture(), w, h);
        });
    }

    @Override
    public void onPause() {
        textureGone(getSurfaceTexture());
        // the EGL context goes with the pause (it is not preserved): the renderer makes a new SurfaceTexture in onSurfaceCreated on
        // resume and releases this one, so the camera must not open against it (screenResumed waits for the new one instead)
        surfaceReady = false;
        super.onPause();
        // P616: the GL thread is paused now (no draw after this one): the gap until the next draw is not a stutter (the settings
        // screen, another app)
        VfDrawMeter.hidden();
    }

    /** The aspect (any scale: 2:3 and 4:6 alike) the view is measured to; the layout is asked again. */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Size cannot be negative.");
        aspectW = width;
        aspectH = height;
        post(this::requestLayout);
    }

    /** The parent's size until an aspect is set, then the aspect's own numbers (the camera's preview size, portrait). */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (aspectW == 0 || aspectH == 0) setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        else setMeasuredDimension(aspectW, aspectH);
    }

    /** The texture the ISP stream draws into (null before the renderer made one). */
    public SurfaceTexture getSurfaceTexture() {
        return mRenderer == null ? null : mRenderer.ispSurface();
    }

    public void setManualModeConsole(ManualModeConsole console) {
        if (mRenderer != null) mRenderer.setManualModeConsole(console);
    }

    /** P36: orientation and mirror of the camera being opened, applied from its first frame (see MainRenderer). */
    public void setViewfinderTransform(int orientation, boolean mirror) {
        mRenderer.setViewfinderTransform(orientation, mirror);
    }

    /** P36: the new session's first capture started at this sensor timestamp. */
    public void armPendingTransform(long sensorTimestampNs) {
        mRenderer.armPendingTransform(sensorTimestampNs);
    }

    /** The texture exists on a live GL surface (TextureView#isAvailable()). */
    public boolean isAvailable() {
        return surfaceReady && mRenderer != null && mRenderer.ispSurface() != null;
    }

    public void setSurfaceTextureListener(TextureView.SurfaceTextureListener listener) {
        textureListener = listener;
    }
}
