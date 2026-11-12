package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.TextureView;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

public class GLPreview extends GLSurfaceView {
    static final float DEFAULT_GPU_PRIORITY = 1f;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098, PRIORITY = 0x3100, HIGH = 0x3101;
    MainRenderer mRenderer;
    private int mRatioWidth;
    private int mRatioHeight;
    public Point cameraSize;
    private TextureView.SurfaceTextureListener surfaceTextureListener;
    private Handler handler;
    private boolean isPlaceholder;
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // True while the renderer owns a SurfaceTexture bound to a live GL surface.
    // Mirrors TextureView#isAvailable() so the camera stack can tell whether
    // the preview consumer is ready without waiting for one-shot callbacks.
    private volatile boolean surfaceReady = false;

    public GLPreview(Context context) {
        super(context);
        init();
    }

    public GLPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // In the layout editor there is no EGL context and the ScameraApp singleton
        // is never created, so skip GL/OpenGL setup and render a static placeholder.
        if (isInEditMode()) {
            isPlaceholder = true;
            setBackgroundColor(Color.rgb(24, 24, 24));
            return;
        }
        handler = new Handler(Looper.getMainLooper());
        mRenderer = new MainRenderer(this);

        // ES 3.0, not 2.0. The raw viewfinder uploads the sensor plane as an
        // R16UI integer texture and reads it with texelFetch through a
        // usampler2D - neither exists in ES 2.0, so glTexImage2D rejected the
        // internal format, the texture stayed undefined, and the viewfinder came
        // out black or full of garbage colours. The shader compiled anyway,
        // which is why the log looked healthy. ES 3.0 is backwards compatible
        // with the existing ES 2.0 preview shaders.
        setEGLContextClientVersion(3);
        // The shot's GPU contexts now run at medium priority. Keep the short viewfinder draws ahead of them;
        // unsupported or refused priority hints retain the ordinary ES 3 context (vf_gpu_priority 0 for comparison).
        setEGLContextFactory(new EGLContextFactory() {
            @Override
            public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
                return createViewfinderContext(egl, display, config,
                        com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("vf_gpu_priority", DEFAULT_GPU_PRIORITY) != 0f);
            }

            @Override
            public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
                egl.eglDestroyContext(display, context);
            }
        });
        setRenderer(mRenderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
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
        if (isPlaceholder) {
            drawPlaceholder(canvas);
            return;
        }
        super.draw(canvas);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isPlaceholder) {
            drawPlaceholder(canvas);
            return;
        }
        super.onDraw(canvas);
    }

    private void drawPlaceholder(Canvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        placeholderPaint.setColor(Color.rgb(24, 24, 24));
        canvas.drawRect(0, 0, w, h, placeholderPaint);
        // A framed rectangle standing in for the camera viewfinder.
        placeholderFramePaint.setColor(Color.rgb(58, 58, 58));
        placeholderFramePaint.setStyle(Paint.Style.STROKE);
        placeholderFramePaint.setStrokeWidth(Math.max(2f, h / 150f));
        canvas.drawRect(0, 0, w, h, placeholderFramePaint);
        canvas.drawRect(0, 0, w * 3f / 4f, h * 3f / 4f, placeholderFramePaint);
    }

    public void fireOnSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int w, int h) {
        // The renderer only calls this right after creating a fresh SurfaceTexture
        // for the current GL surface, so from this point on the preview consumer
        // exists and the camera can be opened against it.
        surfaceReady = true;
        handler.post(() -> {
            if (surfaceTextureListener != null)
                surfaceTextureListener.onSurfaceTextureAvailable(surfaceTexture, w, h);
        });
    }

    public void fireOnSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            if (surfaceTextureListener != null)
                surfaceTextureListener.onSurfaceTextureDestroyed(surfaceTexture);
        });
    }

    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // The system tore the GL surface down (activity stopped, app sent to
        // background); the old SurfaceTexture can no longer receive frames.
        surfaceReady = false;
        super.surfaceDestroyed(holder);
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);
        handler.post(() -> {
            if (surfaceTextureListener != null)
                surfaceTextureListener.onSurfaceTextureSizeChanged(getSurfaceTexture(), w, h);
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        // mRenderer.onResume();
    }

    @Override
    public void onPause() {
        fireOnSurfaceTextureDestroyed(getSurfaceTexture());
        // mRenderer.onPause();
        // the EGL context goes with the pause (it is not preserved): the renderer makes a new SurfaceTexture in onSurfaceCreated on
        // resume and releases this one, so the camera must not open against it (resumeCamera waits for the new one instead)
        surfaceReady = false;
        super.onPause();
        // P616: the GL thread is paused now (no draw after this one): the gap until the next draw is not a stutter (the settings
        // screen, another app)
        VfDrawMeter.hidden();
    }

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured
     * based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters
     * don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same
     * result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }

        mRatioWidth = width;
        mRatioHeight = height;
        this.post(this::requestLayout);

    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (mRatioWidth == 0 || mRatioHeight == 0)
            setMeasuredDimension(width, height);
        else {
            if (width > height * mRatioWidth / mRatioHeight)
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
            else
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
            setMeasuredDimension(mRatioWidth, mRatioHeight);
        }
    }

    public SurfaceTexture getSurfaceTexture() {
        return mRenderer == null ? null : mRenderer.getmSTexture();
    }

    public void setTransform(Matrix matrix) {

    }

    public void setManualModeConsole(ManualModeConsole console) {
        if (mRenderer != null) {
            mRenderer.setManualModeConsole(console);
        }
    }

    public void setOrientation(int or) {
        mRenderer.setOrientation(or);
    }

    public void setMirror(boolean mirror) {
        mRenderer.setMirror(mirror);
        requestRender();
    }

    /** P36: orientation and mirror of the camera being opened, applied from its first frame (see MainRenderer). */
    public void setViewfinderTransform(int orientation, boolean mirror) {
        mRenderer.setViewfinderTransform(orientation, mirror);
    }

    /** P36: the new session's first capture started at this sensor timestamp. */
    public void armPendingTransform(long sensorTimestampNs) {
        mRenderer.armPendingTransform(sensorTimestampNs);
    }

    public boolean isAvailable() {
        return surfaceReady && mRenderer != null && mRenderer.getmSTexture() != null;
    }

    public void setSurfaceTextureListener(TextureView.SurfaceTextureListener l) {
        this.surfaceTextureListener = l;
    }

    public void scale(int in_width, int in_height, int out_width, int out_height, int or) {
        mRenderer.scale(in_width, in_height, out_width, out_height, or);
    }
}
