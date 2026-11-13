package com.scamera.app.ui.camera.views.viewfinder;

import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import com.scamera.app.util.Log;

import androidx.annotation.NonNull;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.circularbarlib.api.ManualModeConsole;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the viewfinder on the GL thread: the ISP stream's external texture or the developed RAW frame, turned and mirrored for
 * the open camera, with the lens filter, focus peaking, the WHY tone and the meters that read the drawn frame.
 */
public class MainRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    /** The ISP stream's external texture. */
    private int ispTex;
    /** The full-screen quad and its texture coordinates (made once). */
    private final FloatBuffer quadXY = quad(1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f);
    private final FloatBuffer quadUV = quad(1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f);
    /** The texture's turn for the camera's orientation (a z rotation; starts at 180 degrees). */
    private final float[] turn = new float[16];
    private SurfaceTexture ispSurface;
    private boolean glReady;
    /** A new ISP frame waits to be latched (onFrameAvailable). */
    private boolean ispPending;
    private Boolean mLastRawActive;
    private volatile boolean mirrored;
    private final GLPreview host;
    /** Measures the live stream's brightness for the manual panel's software shutter / ISO priority (SceneLuma). */
    private final LiveSceneMeter mSceneMeter = new LiveSceneMeter();
    /** Develops the preview RAW stream; replaces the ISP image when it has a frame. */
    private final LiveRawRenderer mRawRenderer = new LiveRawRenderer();
    private final LiveLensFilter mLensFilter = new LiveLensFilter();
    /**
     * P300 (owner, 2026-10-20: «исправь лайв превью для ISZ, isp поток для isz fullres отключаем»): the ISZ and FULLRES sessions
     * have no ISP stream, so their viewfinder is the developed RAW, which had no lens filter. With a filter chosen the RAW frame
     * is drawn off screen and goes through the ISP viewfinder's own filter shaders as a 2D texture (its LUT is in the RAW frame
     * already, rawdevelop_fs: none here; so the filter comes after the LUT there, before it on the ISP path).
     */
    private final LiveLensFilter mRawLensFilter = new LiveLensFilter(false);
    /** P301: the photo's WHY tone on the developed-RAW viewfinder (the RAW frame's linear values through the photo's shaders). */
    private final com.scamera.app.processing.opengl.postpipeline.LiveWhyTone mWhyTone =
            new com.scamera.app.processing.opengl.postpipeline.LiveWhyTone();
    /** P656: the WHY tone's state last logged ({@link #whyLogState}; 0: none in this context). */
    private int mWhyLogged;
    /** P656: the logged words of each {@link #whyLogState}. */
    static final String[] WHY_LOG_STATES = {"", "on", "not drawn (failed)", "not drawn (the fused pass failed)", "not drawn (no frame)"};

    /** P656: the WHY tone's state of a draw for its log line: 1 on, 2 failed, 3 the fused pass failed, 4 no frame. */
    static int whyLogState(boolean drawn, boolean failed, boolean fusedFailed) {
        return drawn ? 1 : failed ? 2 : fusedFailed ? 3 : 4;
    }
    /**
     * P312: the developed-RAW viewfinder's programs linked in this context (with the WHY tone's: mRawProgramsWhy, P314 the
     * LiveWhyTone mode whose set was asked for, 0 none); asked at.
     */
    private boolean mRawProgramsReady;
    private int mRawProgramsWhy;
    private long mRawProgramsAsked;
    /** P312: when an ISP frame was latched last (a RAW-only session - ISZ, FULLRES - never latches one). */
    private long mIspLatchNs;
    /** P312: after this long the draws stop waiting for the background compile (a stuck one: they compile as before P312). */
    static final long RAW_PROGRAMS_WAIT_MS = 8000;
    private int hProgram2d, aXY2d, uPeak2d, uGain2d, uMirror2d, resolution2d;
    private int rawFbo, rawTex, rawW, rawH;
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final String VS_2D = "in vec2 vPosition;\nout vec2 texCoord;\nvoid main() {\n    texCoord = vPosition * 0.5 + 0.5;\n"
            + "    gl_Position = vec4(vPosition, 0.0, 1.0);\n}\n";
    /** P42: downscaled copies of the drawn frame for the tracking autofocus (only while it asks for them). */
    private final SubjectFrameGrabber mSubjectGrabber = new SubjectFrameGrabber();
    /** The manual panel: its focus mode decides the automatic focus peaking. */
    private ManualModeConsole manualPanel;

    public void setManualModeConsole(ManualModeConsole console) {
        manualPanel = console;
    }

    MainRenderer(GLPreview owner) {
        host = owner;
        setOrientation(180);
    }

    /** A direct float buffer of these values, read from its start. */
    private static FloatBuffer quad(float... values) {
        final FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    public void onDrawFrame(GL10 gl) {
        if (!glReady)
            return;
        final long t0 = System.nanoTime();
        drawFrame();
        VfDrawMeter.frame(System.nanoTime() - t0, mLastRawActive != null && mLastRawActive); // P57; P616: RAW or ISP drawn
    }

    /** P111: sensor time of the camera frame the viewfinder showed last (the «Движение» shot's moment at the touch). */
    private static volatile long sShownTimestamp;

    public static long shownTimestamp() {
        return sShownTimestamp;
    }

    /**
     * P345: developed RAW frames drawn so far (GL thread the only writer): a RAW-only session (ISZ, FULLRES without an ISP stream)
     * never latches an ISP frame, so its viewfinder's first frame ends the frozen picture by this count, not shownTimestamp.
     */
    private static volatile long sRawDraws;

    public static long rawDraws() {
        return sRawDraws;
    }

    /** P319: the key of the tone in rawFbo (Long.MIN_VALUE: none), when it was drawn, and the redraws that reused it. */
    private long mToneKey = Long.MIN_VALUE, mToneAtNs, mToneReused;
    /** P319: the VF_RAW line every 2 s while the tone draws: new toned frames, reused redraws, frames the hand-off dropped. */
    private long mVfRawAtNs, mVfRawToned, mVfRawReused, mVfRawDropped0;
    /** P573: new RAW frames drawn (a re-tone of the same frame - its rotation or look changed - counts in toned, not here). */
    private long mVfRawFresh, mVfFreshVersion = Long.MIN_VALUE;
    /** P319: a tone older than this is drawn again even for the same frame (a stalled stream falls back as before). */
    static final long TONE_REUSE_NS = 300_000_000L;
    /**
     * P365: the GPU passes of the last tone for VF_RAW (1 the fused pass straight to the screen, 2 the fused pass and the filter /
     * peaking pass, 3 P314's linear frame, tone and filter pass; P662: 4 P314's linear frame and the tone straight to the screen) and the way the last tone was logged (the same numbers, 0 none).
     */
    private int mVfRawPasses, mFusedLogged;
    /**
     * P365 step 7 (owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»;
     * scam_dev vf_series_hold): while the shot's series is taken (CaptureController.seriesInFlight: the RAW frames stop from the
     * press until its frames are in, 0.38-0.40 s on the OPPO) the ISP frames are only latched for up to this long after the last
     * RAW draw - the screen keeps the last RAW frame instead of the ISP look appearing after RAW_SHOWN_NS and going again (a draw
     * asked for meanwhile draws that RAW frame, up to this old). After it, or with the switch off, as before.
     */
    static final long SERIES_HOLD_NS = 2_000_000_000L;
    /** P365: the RAW frame of the fused pass (LiveWhyTone.drawFused) with this renderer's vertices, rotation and mirror. */
    private final com.scamera.app.processing.opengl.postpipeline.LiveWhyTone.RawSource mFusedRaw =
            new com.scamera.app.processing.opengl.postpipeline.LiveWhyTone.RawSource() {
                @Override
                public boolean bind(int program, com.scamera.app.processing.color.FilmSim.Settings film) {
                    return mRawRenderer.bindFrame(program, turn, mirrored, film);
                }

                @Override
                public boolean fresh() {
                    return mRawRenderer.boundFresh();
                }

                @Override
                public com.scamera.app.processing.LiveWhyMeter.View view() {
                    return mRawRenderer.lastView();
                }

                @Override
                public void draw() {
                    mRawRenderer.drawBound(quadXY, quadUV);
                }
            };

    /**
     * P365: whether the fused tone goes straight to the screen in one pass - the tone at the viewport's size (a 1x Bayer frame; a
     * mosaic's colour grid is smaller: drawn into the tone target, the filter pass scales it up), no lens filter and no focus
     * peaking (their pass reads the tone target).
     */
    static boolean directPass(int toneW, int toneH, int viewportW, int viewportH, boolean lensFilter, int peaking) {
        return toneW == viewportW && toneH == viewportH && !lensFilter && peaking == 0;
    }

    /**
     * P662 (item 5): whether P314's tone is drawn straight to the screen - the tone at the viewport's size at its origin, and the
     * copy pass after it (main_fs on the 2D target) the identity: no lens filter, no peaking, no Oldstyle style (its gain is 1, the
     * RAW lens filter has no LUT of its own). Each output pixel was then the target's texel at its centre - the 8-bit value the
     * combine pass wrote, which it now writes into the 8-bit window instead (tools/checks/vf_direct_tone_gl.py). Not after a window
     * with redraws of the same frame ({@code reuseSeen}): those reuse the tone target. {@code on}: scam_dev vf_direct_tone.
     */
    static boolean directTone(int toneW, int toneH, int[] viewport, boolean lensFilter, int peaking, boolean oldstyle, boolean reuseSeen,
                              boolean on) {
        return on && !reuseSeen && !oldstyle && viewport[0] == 0 && viewport[1] == 0
                && directPass(toneW, toneH, viewport[2], viewport[3], lensFilter, peaking);
    }

    /** P662: the last VF_RAW window had redraws of the same frame (the tone target is kept for them). */
    private boolean mReuseSeen;
    /** P662: the window's config has 8-bit red, green and blue (GLSurfaceView's default 8 / 8 / 8 / 0): the tone target's precision. */
    private boolean mWindow8;

    private static boolean window8(EGLConfig config) {
        try {
            final javax.microedition.khronos.egl.EGL10 egl =
                    (javax.microedition.khronos.egl.EGL10) javax.microedition.khronos.egl.EGLContext.getEGL();
            final javax.microedition.khronos.egl.EGLDisplay display = egl.eglGetCurrentDisplay();
            final int[] v = new int[1];
            for (int attribute : new int[]{javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE,
                    javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE}) {
                if (!egl.eglGetConfigAttrib(display, config, attribute, v) || v[0] != 8) return false;
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** P365 step 7: whether the ISP frames wait (vf_series_hold on, a series in flight, a RAW frame drawn less than SERIES_HOLD_NS ago). */
    static boolean holdsSeries(boolean on, boolean inFlight, long sinceRawNs) {
        return on && inFlight && sinceRawNs >= 0 && sinceRawNs < SERIES_HOLD_NS;
    }

    private boolean seriesHold(long now) {
        final long since = now - mRawShownNs;
        // the cheap tests first: the ISP frames' thread asks this ~30 times a second
        return since >= 0 && since < SERIES_HOLD_NS && CaptureController.seriesInFlight()
                && holdsSeries(com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_series_hold", true), true, since);
    }

    /**
     * P319: the RAW frame (session, version), the tone target and the geometry the WHY tone in rawFbo was drawn for; P369: and the
     * look's table (a film's scene table or the chosen LUT once it is loaded, the strengths: LiveWhyTone.lookKey).
     */
    private long toneKey() {
        long k = ((long) com.scamera.app.processing.LiveRawFrame.currentSession() << 32)
                ^ (com.scamera.app.processing.LiveRawFrame.getVersion() & 0xffffffffL);
        k = 31 * k + rawW;
        k = 31 * k + rawH;
        k = 31 * k + rawFbo;
        k = 31 * k + java.util.Arrays.hashCode(turn);
        k = 31 * k + mWhyTone.lookKey();
        return 31 * k + (mirrored ? 1 : 0);
    }

    private void drawFrame() {
        // GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        latchIsp();
        applyPendingTransform();
        // Developed RAW takes over the viewfinder when a frame is available, so
        // what is framed is what the pipeline produces rather than the ISP's own
        // rendering. Falls through to the ISP path whenever a frame is missing,
        // the shaders failed, or nothing asks for it (the setting off and a session
        // with its ISP stream and no mosaic: CaptureController forces the RAW
        // viewfinder without them) - the viewfinder must never go blank because of this.
        // P13: a colour-block mosaic stream (sensor mode without remosaic) has a purple ISP preview: developed RAW then too
        final boolean rawLook =
                com.scamera.app.settings.PreferenceKeys.isLiveViewfinderRawEnabled()
                        || com.scamera.app.processing.LiveRawFrame.isEnabled()
                        || com.scamera.app.processing.LiveRawFrame.isMosaicPreview();
        // P301 (owner, 2026-10-20: «Результат должен совпадать с итоговым фото»): the photo's WHY tone - the RAW frame's linear
        // values through the photo's shaders into the off-screen target, then the filter / peaking pass puts it on screen
        boolean rawActive = false;
        // P312: the developed RAW only once its programs are linked (from their driver binaries; a missing one is compiled in the
        // background, or here one per frame): until then the ISP frame stays - the viewfinder no longer stops for a compile, and
        // goes from the ISP frame straight to the WHY tone
        final boolean rawReady = rawLook && rawProgramsReady();
        // a RAW-only session has no ISP frame to keep: its plain RAW look as soon as rawdevelop is linked, the WHY tone after
        final boolean rawPlain = rawReady || (rawLook && mRawRenderer.ready() && System.nanoTime() - mIspLatchNs > 300_000_000L);
        // P365 step 7: while a series is held the RAW frame drawn last stays drawable for SERIES_HOLD_NS (MAX_AGE_NS otherwise)
        mRawRenderer.setMaxAge(seriesHold(System.nanoTime()) ? SERIES_HOLD_NS : LiveRawRenderer.MAX_AGE_NS);
        // P314: the tone at the frame's colour resolution (LiveRawRenderer.toneSize), the filter / peaking pass scales it up
        final boolean whyOn = rawReady && hProgram2d != 0
                && com.scamera.app.processing.opengl.postpipeline.LiveWhyTone.wanted() && !mWhyTone.failed();
        // P365 (owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»; scam_dev
        // vf_fused): vf_why 1 in one fused pass from the RAW texture - straight to the screen when the tone is the viewport's size
        // and nothing reads it afterwards (no lens filter, no peaking: one pass, no target of its own), else into the tone target
        // and the filter / peaking pass (two). Failing, it is off for the context and P314's passes draw once their programs are
        // built (the viewfinder's own look or the ISP picture meanwhile, as at a start).
        final int[] tone = whyOn ? toneSizeNow() : null;
        final boolean fused = whyOn && mWhyTone.fused();
        final int peaking = peakMode();
        final boolean direct = fused && directPass(tone[0], tone[1], mViewport[2], mViewport[3], mRawLensFilter.active(), peaking);
        // P662 (item 5): P314's tone straight to the screen when the copy pass after it would change nothing (directTone)
        final boolean directP314 = whyOn && !fused && mWindow8 && directTone(tone[0], tone[1], mViewport, mRawLensFilter.active(), peaking,
                sOldstyle != null, mReuseSeen, com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_direct_tone", true));
        final boolean why = whyOn && (direct || directP314 || rawTarget(tone[0], tone[1]));
        boolean fusedFailedNow = false;
        if (why) {
            // P319 (user report: «Лайв-вью работает гораздо тормознее»): an ISP frame wakes this thread too (1x sessions keep the
            // ISP stream), and each such draw toned the same RAW frame again - up to half the draws. The tone in rawFbo is reused
            // while the RAW frame, the target and the geometry are the same and it is fresh; only the filter / peaking pass runs.
            // A setting change shows with the next RAW frame. scam_dev vf_tone_reuse 0: every draw tones (as before).
            // P365: the direct pass has no tone to reuse: a redraw of the same frame draws it again from its texture (no upload,
            // no AE), counted as reused
            final long now = System.nanoTime();
            if (direct) {
                rawActive = mWhyTone.drawFused(mFusedRaw, 0, mViewport[0], mViewport[1], mViewport[2], mViewport[3]);
                mToneKey = Long.MIN_VALUE;
                if (rawActive) {
                    mVfRawPasses = 1;
                    if (mRawRenderer.boundFresh()) mVfRawToned++;
                    else mVfRawReused++;
                    releaseRawTarget();
                }
            } else if (directP314) {
                // P662: the combine pass draws into the screen itself (its 8-bit window: the bytes the tone target held, which the
                // copy pass put on the screen unchanged: an 8-bit window, mWindow8); a redraw of the same frame tones it again (no target to reuse)
                rawActive = mWhyTone.draw(film -> mRawRenderer.drawLinear(quadXY, quadUV, turn, mirrored, film),
                        mRawRenderer::lastView, 0, mViewport[2], mViewport[3]);
                mToneKey = Long.MIN_VALUE;
                if (rawActive) {
                    mVfRawPasses = 4;
                    if (mRawRenderer.boundFresh()) mVfRawToned++;
                    else mVfRawReused++;
                    releaseRawTarget();
                }
            } else {
                final long key = toneKey();
                if (key == mToneKey && now - mToneAtNs < TONE_REUSE_NS
                        && com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_tone_reuse", true)) {
                    rawActive = true;
                    mToneReused++;
                    mVfRawReused++;
                } else {
                    if (fused) {
                        rawActive = mWhyTone.drawFused(mFusedRaw, rawFbo, 0, 0, rawW, rawH);
                        if (rawActive) mVfRawPasses = 2;
                    } else {
                        rawActive = mWhyTone.draw(film -> mRawRenderer.drawLinear(quadXY, quadUV, turn, mirrored, film),
                                mRawRenderer::lastView, rawFbo, rawW, rawH);
                        if (rawActive) mVfRawPasses = 3;
                    }
                    mToneKey = rawActive ? key : Long.MIN_VALUE;
                    mToneAtNs = now;
                    if (rawActive) mVfRawToned++;
                }
                if (rawActive) drawRawFiltered(peaking);
            }
            if (rawActive) {
                final long version = ((long) com.scamera.app.processing.LiveRawFrame.currentSession() << 32)
                        ^ (com.scamera.app.processing.LiveRawFrame.getVersion() & 0xffffffffL);
                if (version != mVfFreshVersion) {
                    mVfFreshVersion = version;
                    mVfRawFresh++;
                    // P597: a new frame's whole draw on this thread (its upload and meters in it); the GL calls only queue the
                    // GPU's work - scam_dev vf_stage_gpu 1 waits for it (glFinish) so that "gpu" is the GPU's time of the frame
                    final long drawn = System.nanoTime();
                    com.scamera.app.processing.VfStages.note(com.scamera.app.processing.VfStages.Stage.DRAW, drawn - now);
                    if (com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_stage_gpu", false)) {
                        GLES20.glFinish();
                        com.scamera.app.processing.VfStages.note(com.scamera.app.processing.VfStages.Stage.GPU,
                                System.nanoTime() - drawn);
                    }
                }
            }
            fusedFailedNow = fused && mWhyTone.fusedFailed();
            if (fusedFailedNow) {
                // P365: P314's programs are asked for (their background compile), this frame takes the fall-back below
                mRawProgramsReady = false;
                mFusedLogged = 0;
            }
            if (now - mVfRawAtNs >= 2_000_000_000L) {
                final long dropped = com.scamera.app.processing.LiveRawFrame.droppedFrames();
                if (mVfRawAtNs != 0) {
                    final double s = (now - mVfRawAtNs) / 1e9;
                    // P365: the passes of the tone and the camera thread's copies per frame (LiveRawFrame.copyStats)
                    Log.i("VF_RAW", String.format(java.util.Locale.ROOT, "fresh %.1f/s toned %.1f/s reused %.1f/s dropped %.1f/s tone %dx%d passes=%d %s",
                            mVfRawFresh / s, mVfRawToned / s, mVfRawReused / s, (dropped - mVfRawDropped0) / s, tone[0], tone[1], mVfRawPasses,
                            com.scamera.app.processing.LiveRawFrame.copyStats()));
                    // P597: the same 2 s stage by stage and thread by thread (VfStages)
                    Log.i("VF_RAW", com.scamera.app.processing.VfStages.take());
                } else com.scamera.app.processing.VfStages.take(); // the first window starts here
                mVfRawAtNs = now;
                // P662: a window with redraws of the same frame keeps the tone target (they reuse it), the next one too
                mReuseSeen = mVfRawReused > 0;
                mVfRawToned = mVfRawReused = mVfRawFresh = 0;
                mVfRawDropped0 = dropped;
            }
            if (rawActive && mFusedLogged != mVfRawPasses) {
                mFusedLogged = mVfRawPasses;
                Log.d("MainRenderer", "viewfinder: " + (mVfRawPasses == 1 ? "fused WHY tone on (direct)"
                        : mVfRawPasses == 4 ? "the WHY tone in P314's passes, the tone straight to the screen (no copy pass)"
                        : mVfRawPasses == 2 ? "fused WHY tone on (2 passes: tone " + rawW + "x" + rawH + ", filter / peaking)"
                        : "the WHY tone in P314's passes" + (com.scamera.app.processing.opengl.postpipeline.LiveWhyTone.fusedSwitch()
                        && com.scamera.app.processing.opengl.postpipeline.LiveWhyTone.mode()
                        == com.scamera.app.processing.opengl.postpipeline.LiveWhyTone.POINT ? " (the fused pass failed)" : "")));
            }
            // P656: once per change of the state (owner's logs of 11-12: «not drawn (no frame)» on every draw - 875 of 4158
            // lines of log 7, 590 of log 2 - while the RAW stream had no frame: the old flag was reset by the very line it gated)
            final int whyState = whyLogState(rawActive, mWhyTone.failed(), fusedFailedNow);
            if (whyState != mWhyLogged) {
                mWhyLogged = whyState;
                Log.d("MainRenderer", "viewfinder: the photo's WHY tone " + WHY_LOG_STATES[whyState]);
            }
        }
        // P300: with a lens filter the RAW frame is drawn off screen first, the filter pass puts it on screen
        // P314: not after the WHY tone found no frame (the plain draw has none either; its viewport-sized target would be made
        // anew, and the tone's again with the next frame) - only when the tone failed (P365: or its fused pass, this frame)
        if (!rawActive && (!why || mWhyTone.failed() || fusedFailedNow)) {
            final boolean rawFilter = rawPlain && hProgram2d != 0 && mRawLensFilter.active() && rawTarget();
            if (rawFilter) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rawFbo);
                // P662 (item 2): the plain RAW look's quad covers the viewport-sized target
                com.scamera.app.processing.opengl.postpipeline.LiveWhyTone.invalidateBound(rawFbo);
            }
            rawActive = rawPlain && mRawRenderer.draw(quadXY, quadUV, turn, mirrored, peakMode());
            if (rawFilter) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                if (rawActive) drawRawFiltered(0);
            }
        }
        if (mLastRawActive == null || mLastRawActive != rawActive) {
            Log.d("MainRenderer", "viewfinder requestedRAW="+rawLook+" displaying="+(rawActive?"developed RAW":"ISP (RAW disabled, waiting, stale or unavailable)"));
            mLastRawActive = rawActive;
        }
        com.scamera.app.control.subject.SubjectFrames.setRawDisplayed(rawActive);
        mRawShownNs = rawActive ? System.nanoTime() : Long.MIN_VALUE / 2;
        if (rawActive) {
            sRawDraws++; // P345: a RAW-only session's frames end the frozen viewfinder too
            mSubjectGrabber.afterDraw();
            GLES20.glUseProgram(ispProgram);
            bindIspTexture();
            return;
        }

        // P228: the live lens filter's blur pyramid of this frame (its own program and framebuffer), before the main pass
        mLensFilter.prepass(quadXY, quadUV, turn, ispTex, mirrored, mFrameGain);
        // Every pass owns its GL state: RAW and histogram passes use other programs/attributes.
        GLES20.glUseProgram(ispProgram);
        bindIspTexture();
        GLES20.glEnableVertexAttribArray(aXY);
        GLES20.glEnableVertexAttribArray(aUV);
        quadXY.position(0);
        quadUV.position(0);
        GLES20.glUniformMatrix4fv(uTurn, 1, false, turn, 0);
        int peakEnabled = peakMode();
        GLES20.glUniform1i(uPeak, peakEnabled);
        GLES20.glUniform1i(uMirror, mirrored ? 1 : 0);

        GLES20.glUniform1f(uGain, mFrameGain);
        // P643d: the tone preview's grid of this draw (null: off or not ready - the frame as it is)
        final com.scamera.app.processing.oldstyle.OldstyleLive.Grid liveTone = readyTone();
        oldstyleUniforms(uOsOn, uOsAdjust, liveTone != null);
        oldstyleTone(uOtTone, liveTone);
        mLensFilter.apply();

        pointQuad();
        drawQuad();

        // Measure after drawing, so a failure here cannot leave the viewfinder
        // blank, and re-bind this program afterwards since the meter uses its
        // own. Only runs on one frame in eight.
        // P82: while the manual panel's software shutter / ISO priority needs the viewfinder's brightness
        // P643b: or while the Oldstyle tone preview follows this frame's lumas (more often: the series frames of a shot)
        final boolean toneLevel = com.scamera.app.processing.oldstyle.OldstyleLive.shared().wantsScreen();
        if (toneLevel || com.scamera.app.manual.SceneLuma.wanted()) {
            if (toneLevel) mSceneMeter.measure(quadXY, quadUV, ispTex, LiveSceneMeter.LIVE_TONE_FRAMES, mFrameGain);
            else mSceneMeter.measure(quadXY, quadUV, ispTex);
            GLES20.glUseProgram(ispProgram);
            bindIspTexture();
        }
        mSubjectGrabber.afterDraw();
        // GLES20.glFlush();
    }

    private int ispProgram;
    private int uTurn;

    private int aXY;
    private int aUV;
    private int uPeak;
    private int uMirror;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        glReady = false;
        mWindow8 = window8(config);
        mToneLut.contextLost(); // P630f: the Oldstyle tone's LUT texture went with the old GL context
        // P57: the viewfinder's GL thread above the processing threads (the post pipeline, encoders, the worker run at the
        // default priority and took its CPU time after a shot: the viewfinder stuttered while the camera kept 30 fps)
        // (scam_dev.txt "vf_priority 0": as before, for A/B)
        try {
            if (com.scamera.app.settings.PreferenceKeys.scamDevNumber("vf_priority", 1f) != 0f)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (RuntimeException e) {
            Log.w("MainRenderer", "viewfinder thread priority not raised: " + e);
        }
        ispPending = false;
        mRawRenderer.onContextCreated();
        mWhyTone.onContextCreated();
        mWhyLogged = 0;
        mFusedLogged = 0;
        mRawProgramsReady = false;
        mRawProgramsWhy = 0;
        mRawProgramsAsked = 0;
        mIspLatchNs = 0;
        mSceneMeter.onContextCreated();
        mSubjectGrabber.onContextCreated();
        makeIspTexture();
        // the previous context's SurfaceTexture (its context is gone; the camera was closed before the pause and opens again on
        // the new one): released, not left to the finalizer with its BufferQueue and last preview buffer
        final SurfaceTexture old = ispSurface;
        if (old != null) {
            old.setOnFrameAvailableListener(null);
            old.release();
        }
        ispSurface = new SurfaceTexture(ispTex);
        // P90: frame callbacks on their own thread, not the main looper (a SurfaceTexture made on the GL thread, which has no
        // looper, posts them to the main thread: every busy moment of the UI during processing held the viewfinder back)
        ispSurface.setOnFrameAvailableListener(this, frameHandler());

        String vertexSrc = ScameraApp.getAssetLoader().getString("shaders/preview/main_vs.glsl");
        String fragmentSrc = oldstyleSplice(LiveLensFilter.splice(ScameraApp.getAssetLoader().getString("shaders/preview/main_fs.glsl"),
                ScameraApp.getAssetLoader().getString("shaders/preview/lensfilter_live.glsl")),
                ScameraApp.getAssetLoader().getString("shaders/utils/import_oldstyle_adjust.glsl"),
                ScameraApp.getAssetLoader().getString("shaders/utils/import_oldstyle_tone_live.glsl"));
        int ispProg = linkProgram(vertexSrc, fragmentSrc);
        ispProgram = ispProg;
        GLES20.glUseProgram(ispProg);
        uTurn = GLES20.glGetUniformLocation(ispProg, "uTexRotateMatrix");
        GLES20.glUniformMatrix4fv(uTurn, 1, false, turn, 0);
        aXY = GLES20.glGetAttribLocation(ispProg, "vPosition");
        aUV = GLES20.glGetAttribLocation(ispProg, "vTexCoord");
        uPeak = GLES20.glGetUniformLocation(ispProg, "enablePeak");
        uGain = GLES20.glGetUniformLocation(ispProg, "uGain");
        uMirror = GLES20.glGetUniformLocation(ispProg, "mirror");
        uOsOn = GLES20.glGetUniformLocation(ispProg, "uOsOn");
        uOsAdjust = locateOldstyle(ispProg);
        uOtTone = locateTone(ispProg);
        pinSamplers(ispProg, fragmentSrc); // P631b: every sampler on its own unit from the first draw on
        mLensFilter.locate(ispProg);
        mLensFilter.locatePrep(linkProgram(vertexSrc, ScameraApp.getAssetLoader().getString("shaders/preview/lensfilter_prep.glsl")));
        // P300: the same filter for the developed-RAW frame drawn off screen (a 2D texture, the screen's layout)
        rawFbo = rawTex = rawW = rawH = 0;
        hProgram2d = linkProgram(VS_2D, LiveLensFilter.to2d(fragmentSrc));
        if (hProgram2d != 0) {
            aXY2d = GLES20.glGetAttribLocation(hProgram2d, "vPosition");
            uPeak2d = GLES20.glGetUniformLocation(hProgram2d, "enablePeak");
            uGain2d = GLES20.glGetUniformLocation(hProgram2d, "uGain");
            uMirror2d = GLES20.glGetUniformLocation(hProgram2d, "mirror");
            resolution2d = GLES20.glGetUniformLocation(hProgram2d, "resolution");
            uOsOn2d = GLES20.glGetUniformLocation(hProgram2d, "uOsOn");
            uOsAdjust2d = locateOldstyle(hProgram2d);
            uOtTone2d = locateTone(hProgram2d);
            GLES20.glUseProgram(hProgram2d);
            pinSamplers(hProgram2d, LiveLensFilter.to2d(fragmentSrc)); // P631b
            mRawLensFilter.locate(hProgram2d);
            mRawLensFilter.locatePrep(linkProgram(VS_2D, LiveLensFilter.to2d(ScameraApp.getAssetLoader().getString("shaders/preview/lensfilter_prep.glsl"))));
        }
        GLES20.glUseProgram(ispProg);
        pointQuad();
        GLES20.glEnableVertexAttribArray(aXY);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(ispProg, "resolution"), host.getWidth(), host.getHeight());
        glReady = ispProg != 0;
        host.fireOnSurfaceTextureAvailable(ispSurface, 0, 0);
        // P312: with the RAW viewfinder on, its programs are linked (or their background compile started) while the camera opens
        if (com.scamera.app.settings.PreferenceKeys.isLiveViewfinderRawEnabled()) rawProgramsReady();
    }

    /**
     * P312: links the developed-RAW viewfinder's programs (rawdevelop, and the photo's WHY tone when it is wanted) before the
     * first RAW frame needs them: their driver binaries at once; the ones without a binary are compiled by ViewfinderPrograms in
     * the background (asked once per context), else here, at most one per call. True when they are all linked (or failed: the
     * draw falls back as before).
     */
    private boolean rawProgramsReady() {
        // P314: which of the tone's program sets (vf_why 1 per pixel / 2 the guided-filter chain; 0 none); P365: the fused pass's
        // set or P314's (LiveWhyTone.programSet)
        final int whyMode = hProgram2d != 0 && !mWhyTone.failed() ? mWhyTone.programSet() : 0;
        final boolean why = whyMode != 0;
        if (mRawProgramsReady && (!why || mRawProgramsWhy == whyMode)) return true;
        final long now = android.os.SystemClock.elapsedRealtime();
        try {
            if (mRawProgramsAsked == 0 || (why && mRawProgramsWhy != whyMode)) {
                // first ask in this context, or the WHY tone wanted now (vf_why switched on, or to the other set): its programs built
                // in the background too
                mRawProgramsReady = false;
                mRawProgramsAsked = now;
                mRawProgramsWhy = whyMode;
                final java.util.ArrayList<String> keys = new java.util.ArrayList<>();
                keys.add(LiveRawRenderer.programKey());
                if (why) keys.addAll(mWhyTone.programKeys());
                ViewfinderPrograms.prepare(keys);
            }
            final boolean waiting = now - mRawProgramsAsked < RAW_PROGRAMS_WAIT_MS;
            final java.util.function.Predicate<String> elsewhere = k -> waiting && ViewfinderPrograms.pending(k);
            if (waiting && ViewfinderPrograms.busy()) {
                // no binary is loaded while the background thread links (a driver's compiler lock would hold this thread), except
                // rawdevelop's in a RAW-only session: it has no ISP frame to show meanwhile, the plain RAW look draws until the WHY tone
                if (System.nanoTime() - mIspLatchNs > 300_000_000L) mRawRenderer.prepare(elsewhere);
                return false;
            }
            final java.util.function.Predicate<String> sendOnce = k -> {
                if (!waiting || ViewfinderPrograms.tried(k)) return false;
                ViewfinderPrograms.prepare(java.util.Collections.singletonList(k));
                return true;
            };
            mRawProgramsReady = mRawRenderer.prepare(elsewhere) && (!why || mWhyTone.prepare(elsewhere, sendOnce));
        } catch (RuntimeException e) {
            Log.w("MainRenderer", "viewfinder programs: " + e);
            mRawProgramsReady = true;   // the draws fall back as before
        } finally {
            // the programs' set-up leaves its program and texture units bound
            GLES20.glUseProgram(ispProgram);
            unitZero();
        }
        if (mRawProgramsReady)
            Log.d("MainRenderer", "viewfinder: RAW programs linked " + (android.os.SystemClock.elapsedRealtime() - mRawProgramsAsked)
                    + " ms after they were asked for (WHY " + why + ", set " + whyMode + ": vf_why " + whyMode / 2
                    + (whyMode % 2 == 1 ? " fused" : "") + ")");
        return mRawProgramsReady;
    }

    /** P300: the off-screen target of the developed-RAW frame, the viewport's size (made or resized here); false without one. */
    private boolean rawTarget() {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, mViewport, 0);
        return rawTarget(mViewport[2], mViewport[3]);
    }

    private final int[] mViewport = new int[4];

    /**
     * P314: the size of the WHY tone: the visible crop's colour grid of the frame drawn last (LiveRawRenderer.toneSize: a 2x ISZ
     * Quad frame 810 x 1080 instead of the 1440 x 1920 viewport, a 1x Bayer frame the viewport), the viewport before a first frame;
     * P365: only the size (the fused pass straight to the screen makes no target), mViewport read.
     */
    private int[] toneSizeNow() {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, mViewport, 0);
        final com.scamera.app.processing.LiveWhyMeter.View v = mRawRenderer.lastView();
        final boolean busy = com.scamera.app.util.ThermalPolicy.reducedRawViewfinder();
        if (v == null) return RawPreviewBudget.size(mViewport[2], mViewport[3], busy);
        // the crop fitted to the screen, not the one drawn last: that one was fitted to the previous target's rounded aspect, and
        // the two could flip each other every frame (review: 139 of ~84k viewport / frame / zoom cases in a 2-cycle)
        final boolean quarterTurn = Math.abs(turn[1]) > Math.abs(turn[0]);
        final int[] crop = LiveRawRenderer.visibleCrop(v.frame, mViewport[2], mViewport[3], quarterTurn);
        // P616: a zoomed view keeps the whole frame while a shot is processed (LiveRawFrame.viewCap halves only an unzoomed one)
        com.scamera.app.processing.LiveRawFrame.setBusyHalfFits(LiveRawRenderer.halfFits(crop, v.frame.width, v.frame.height));
        final int[] size = LiveRawRenderer.toneSize(mViewport[2], mViewport[3], crop[2], crop[3], v.frame.cfaBlock, quarterTurn);
        return RawPreviewBudget.size(size[0], size[1], busy);
    }

    /** P365: the off-screen target goes while the fused tone is drawn straight to the screen (11 MB at 1440 x 1920). */
    private void releaseRawTarget() {
        if (rawFbo == 0) return;
        GLES30.glDeleteFramebuffers(1, new int[]{rawFbo}, 0);
        GLES20.glDeleteTextures(1, new int[]{rawTex}, 0);
        rawFbo = rawTex = rawW = rawH = 0;
    }

    /** P300 / P314: the off-screen target at w x h (made or resized here); false without one. */
    private boolean rawTarget(int width, int height) {
        int w = Math.max(1, width), h = Math.max(1, height);
        if (rawFbo != 0 && w == rawW && h == rawH) return true;
        if (rawFbo != 0) GLES30.glDeleteFramebuffers(1, new int[]{rawFbo}, 0);
        if (rawTex != 0) GLES20.glDeleteTextures(1, new int[]{rawTex}, 0);
        rawFbo = rawTex = rawW = rawH = 0;
        int[] t = new int[1], f = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h);
        linearClamp(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES30.glGenFramebuffers(1, f, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w("MainRenderer", "RAW filter target incomplete: 0x" + Integer.toHexString(status));
            GLES30.glDeleteFramebuffers(1, f, 0);
            GLES20.glDeleteTextures(1, t, 0);
            hProgram2d = 0;   // no filter on the RAW viewfinder in this context, as before
            return false;
        }
        rawFbo = f[0];
        rawTex = t[0];
        rawW = w;
        rawH = h;
        return true;
    }

    /**
     * P300: the off-screen RAW frame through the live lens filter onto the screen (no gain or mirror: the RAW pass did them);
     * P301: the focus peaking here when the frame came from the WHY tone (the RAW pass drew none).
     */
    private void drawRawFiltered(int peaking) {
        mRawLensFilter.prepass(quadXY, quadUV, IDENTITY, rawTex, GLES20.GL_TEXTURE_2D, false, 1f);
        GLES20.glUseProgram(hProgram2d);
        unitZero();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rawTex);
        GLES20.glUniform1i(uPeak2d, peaking);
        GLES20.glUniform1i(uMirror2d, 0);
        GLES20.glUniform1f(uGain2d, 1f);
        GLES20.glUniform2f(resolution2d, rawW, rawH);
        // P643d: the grid maps the ISP's frame (fitted on LiveSceneMeter's tile of it): the developed-RAW frame is another input
        // (our own development, its own tone and colour) - no grid for it, the frame as it is under the style
        oldstyleUniforms(uOsOn2d, uOsAdjust2d, false);
        oldstyleTone(uOtTone2d, null);
        mRawLensFilter.apply();
        quadXY.position(0);
        GLES20.glEnableVertexAttribArray(aXY2d);
        GLES20.glVertexAttribPointer(aXY2d, 2, GLES20.GL_FLOAT, false, 4 * 2, quadXY);
        drawQuad();
        GLES20.glDisableVertexAttribArray(aXY2d);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /** P662: the GL context is about to be destroyed (GLPreview's EGLContextFactory, GL thread). */
    void onContextDestroying() {
        mRawRenderer.onContextDestroying();
    }

    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES30.glViewport(0, 0, w, h);
        mSubjectGrabber.onSurfaceChanged(w, h);
    }

    public SurfaceTexture ispSurface() {
        return ispSurface;
    }

    /** The full-screen quad: four vertices as a strip. */
    private static void drawQuad() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Back to texture unit 0, where the frame's sampler reads. */
    private static void unitZero() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    /** Unit 0 holds the ISP stream's texture. */
    private void bindIspTexture() {
        unitZero();
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ispTex);
    }

    /** The ISP program's position and texture coordinate attributes read the full-screen quad (2 floats a vertex). */
    private void pointQuad() {
        GLES20.glVertexAttribPointer(aXY, 2, GLES20.GL_FLOAT, false, 8, quadXY);
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 8, quadUV);
    }

    /** The bound texture of {@code target}: clamped at the edges, linear both ways. */
    private static void linearClamp(int target) {
        for (int wrap : new int[]{GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T})
            GLES20.glTexParameteri(target, wrap, GLES20.GL_CLAMP_TO_EDGE);
        for (int filter : new int[]{GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER})
            GLES20.glTexParameteri(target, filter, GLES20.GL_LINEAR);
    }

    /** The ISP stream's external texture: clamped at the edges, linear both ways. */
    private void makeIspTexture() {
        final int oes = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        final int[] made = new int[1];
        GLES20.glGenTextures(1, made, 0);
        ispTex = made[0];
        GLES20.glBindTexture(oes, ispTex);
        linearClamp(oes);
    }

    private static android.os.Handler sFrameHandler;

    private static synchronized android.os.Handler frameHandler() {
        if (sFrameHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("VfFrames", android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            sFrameHandler = new android.os.Handler(t.getLooper());
        }
        return sFrameHandler;
    }

    /**
     * P630 / P630c «Oldstyle»: the mode's style on the viewfinder (OldstyleStyle.uniforms, 16 floats; null: off - another mode, or
     * everything at 0). The UI sets it (OldstyleControls); the same function as the photo's (utils/import_oldstyle_adjust.glsl).
     */
    private static volatile float[] sOldstyle;
    private int uOsOn = -1, uOsOn2d = -1;
    private int[] uOsAdjust = {-1, -1, -1, -1}, uOsAdjust2d = {-1, -1, -1, -1};
    static final String OLDSTYLE_MARKER = "//#oldstyle_adjust";

    public static void setOldstyleAdjust(float[] uniform) {
        sOldstyle = uniform == null ? null : uniform.clone();
    }

    public static float[] oldstyleAdjust() {
        final float[] a = sOldstyle;
        return a == null ? null : a.clone();
    }

    static String oldstyleSplice(String main, String adjust) {
        return main.replace(OLDSTYLE_MARKER, adjust);
    }

    /** P630e: the style and the version's live tone (utils/import_oldstyle_tone_live.glsl) in their places. */
    static String oldstyleSplice(String main, String adjust, String tone) {
        return oldstyleSplice(main, adjust).replace(OLDSTYLE_TONE_MARKER, tone);
    }

    static final String OLDSTYLE_TONE_MARKER = "//#oldstyle_tone";

    /**
     * P643d «ScamMaster»: the viewfinder's base under the style - the preview of the tone: OldstyleLive's bilateral grid (the ISP
     * frame carried to the module's own picture of the viewfinder's frame; the GLSL of utils/import_oldstyle_tone_live). The style's
     * «Экспозиция» is in the module's picture (CaptureController.oldstyleViewfinder), so the style's own exposure is 0 on the screen
     * while the preview is drawn (oldstyleUniforms).
     */
    private int[] uOtTone = {-1, -1, -1}, uOtTone2d = {-1, -1, -1};
    /**
     * P630f (owner, 2026-11-12, OPPO PHY110: «После первого снимка или включения лога чёрный экран»): the tone's texture of the GL
     * context in use. The renderer outlives its context (the gallery after a shot, the settings and back: a new context), the
     * texture does not - its name kept from the old context read as a texture with no image, a black viewfinder while the mode
     * was on. Forgotten with the context ({@link #contextLost}, onSurfaceCreated); P643d: with it the grid's generation it holds,
     * so a new context uploads the grid again.
     */
    static final class ToneLut {
        int texture, generation = Integer.MIN_VALUE;

        /** A texture of this context is there. */
        boolean ready() {
            return texture != 0;
        }

        void created(int texture) {
            this.texture = texture;
            generation = Integer.MIN_VALUE;
        }

        /** A new GL context: nothing of the old one is there. */
        void contextLost() {
            texture = 0;
            generation = Integer.MIN_VALUE;
        }
    }

    private final ToneLut mToneLut = new ToneLut();
    /** The texture unit of the tone's grid (units of their own: the frame 0, uLut 1, the lens filter's 2 / 3; {@link #samplerUnit}). */
    static final int OT_LUT_UNIT = 13;
    /** The grid's texture: GX texels across, (3 rows x GL bins x GY) down (OldstyleLive.index). */
    static final int OT_TEX_W = com.scamera.app.processing.oldstyle.OldstyleLive.GX,
            OT_TEX_H = 3 * com.scamera.app.processing.oldstyle.OldstyleLive.GL
                    * com.scamera.app.processing.oldstyle.OldstyleLive.GY;

    private static int[] locateTone(int program) {
        return new int[]{GLES20.glGetUniformLocation(program, "uOtOn"), GLES20.glGetUniformLocation(program, "uOtLut"),
                GLES20.glGetUniformLocation(program, "uOtLevel")};
    }

    /**
     * P643d: the grid to draw this frame with (OldstyleLive.grid; its coefficients only when the texture of this context does not
     * hold its generation yet), null: off or not ready (no fit yet, the module's picture or the frame's tile stale) - the frame as
     * it is, never a half-made tone.
     */
    com.scamera.app.processing.oldstyle.OldstyleLive.Grid toneNow() {
        return com.scamera.app.processing.oldstyle.OldstyleLive.shared().grid(System.nanoTime(),
                mToneLut.ready() ? mToneLut.generation : Integer.MIN_VALUE);
    }

    /** {@link #toneNow} in this context's texture (uploaded when it is a new generation); null: no tone this draw. */
    private com.scamera.app.processing.oldstyle.OldstyleLive.Grid readyTone() {
        final com.scamera.app.processing.oldstyle.OldstyleLive.Grid g = toneNow();
        if (g == null) return null;
        if (g.coefficients == null && mToneLut.ready() && mToneLut.generation == g.generation) return g;
        if (g.coefficients == null) return null;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + OT_LUT_UNIT);
        if (!mToneLut.ready()) {
            final int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            if (t[0] == 0) {
                unitZero();
                return null;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            linearClamp(GLES20.GL_TEXTURE_2D);
            mToneLut.created(t[0]);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mToneLut.texture);
        // RGBA16F (filterable in ES 3.0) from floats
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, OT_TEX_W, OT_TEX_H, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT,
                mGridUpload = gridUpload(mGridUpload, g.coefficients));
        unitZero();
        mToneLut.generation = g.generation;
        return g;
    }

    /**
     * P656: the grid's upload buffer, kept: the grid moves to each new fit by small steps, a new generation up to every 30 ms
     * (OldstyleLive.advance), and each upload made a direct buffer of its 72 KB on this thread (~2.4 MB a second of garbage
     * under the viewfinder while the ScamMaster preview converges).
     */
    private java.nio.FloatBuffer mGridUpload;

    /** P656: {@code coefficients} in {@code kept} (a new buffer only when there is none or it is too small), ready to upload. */
    static java.nio.FloatBuffer gridUpload(java.nio.FloatBuffer kept, float[] coefficients) {
        if (kept == null || kept.capacity() < coefficients.length)
            kept = ByteBuffer.allocateDirect(coefficients.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        kept.clear();
        kept.put(coefficients).flip();
        return kept;
    }

    private void oldstyleTone(int[] l, com.scamera.app.processing.oldstyle.OldstyleLive.Grid g) {
        // P631b: the grid's sampler on its own unit with the tone off too - left at its default 0 it shared the unit of the
        // camera's external frame (two sampler types on one unit: every draw of this program failed, GL_INVALID_OPERATION)
        if (l[1] >= 0) GLES20.glUniform1i(l[1], OT_LUT_UNIT);
        if (l[0] >= 0) GLES20.glUniform1i(l[0], g != null ? 1 : 0);
        if (g == null) return;
        if (l[2] >= 0) GLES20.glUniform1f(l[2], g.level);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + OT_LUT_UNIT);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mToneLut.texture);
        unitZero();
    }

    // ---------------------------------------------------------------- P631b: the main program's samplers

    /** The camera's frame: the external texture of the ISP viewfinder (a 2D one in the developed-RAW filter pass), unit 0. */
    static final String FRAME_SAMPLER = "sTexture";
    /** The first unit given to a sampler {@link #samplerUnit} does not know (above the tone's LUT). */
    static final int SPARE_UNIT = OT_LUT_UNIT + 1;
    private static final java.util.regex.Pattern SAMPLER = java.util.regex.Pattern.compile(
            "(?m)^\\s*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(sampler\\w+)\\s+(\\w+)\\s*;");

    /**
     * P631b (owner, 2026-11-12, build 31257: «Чёрный экран у 17U»): the texture unit of a sampler of the viewfinder's main
     * program (main_fs with its spliced parts), -1 for one this table does not know. GL ES fails every draw
     * (GL_INVALID_OPERATION) of a program whose samplers of different types point at one unit, and a sampler nobody sets
     * points at unit 0 - the external frame's. P630e's uOtLut was set only while the Oldstyle tone was live: outside that mode
     * the ISP viewfinder drew nothing, and the error it left failed the RAW viewfinder's upload check.
     */
    static int samplerUnit(String name) {
        switch (name) {
            case FRAME_SAMPLER: return 0;
            case "uLut": return LiveLensFilter.UNIT_LUT;
            case "uLfHiTex": return LiveLensFilter.UNIT_HI;
            case "uLfLinTex": return LiveLensFilter.UNIT_LIN;
            case "uOtLut": return OT_LUT_UNIT;
            default: return -1;
        }
    }

    /** The sampler uniforms a fragment source declares: {type, name} in their order. */
    static java.util.List<String[]> samplers(String fragment) {
        final java.util.List<String[]> out = new java.util.ArrayList<>();
        final java.util.regex.Matcher m = SAMPLER.matcher(fragment == null ? "" : fragment);
        while (m.find()) out.add(new String[]{m.group(1), m.group(2)});
        return out;
    }

    /**
     * P631b: {name, unit} of every sampler of {@code fragment}: its unit of {@link #samplerUnit}; one the table does not know
     * gets a spare unit of its own ({@link #SPARE_UNIT} up), so that a part spliced in later can never share the frame's unit.
     */
    static java.util.List<Object[]> samplerPins(String fragment) {
        final java.util.List<Object[]> out = new java.util.ArrayList<>();
        int spare = SPARE_UNIT;
        for (String[] u : samplers(fragment)) {
            final int unit = samplerUnit(u[1]);
            out.add(new Object[]{u[1], unit >= 0 ? unit : spare++});
        }
        return out;
    }

    /** P631b: where {@link #pinSamplers} sets the units (the GL program in use; a recorder in the tests). */
    interface SamplerSink {
        /** The uniform's location, -1 when the program has none of that name (optimised away). */
        int location(String name);

        void unit(int location, int unit);
    }

    /** P631b: the samplers of {@code program} (in use) on their units, once after it is linked or loaded. */
    static void pinSamplers(final int program, String fragment) {
        if (program == 0) return;
        pinSamplers(fragment, new SamplerSink() {
            @Override public int location(String name) { return GLES20.glGetUniformLocation(program, name); }
            @Override public void unit(int location, int unit) { GLES20.glUniform1i(location, unit); }
        });
    }

    static void pinSamplers(String fragment, SamplerSink gl) {
        for (Object[] pin : samplerPins(fragment)) {
            final String name = (String) pin[0];
            final int unit = (Integer) pin[1], location = gl.location(name);
            if (samplerUnit(name) < 0)
                Log.w("MainRenderer", "viewfinder sampler " + name + " has no unit of its own in MainRenderer.samplerUnit: spare unit " + unit);
            if (location >= 0) gl.unit(location, unit);
        }
    }

    /** P630c: the style's four vec4 (uOs0..uOs3, OldstyleStyle.uniforms). */
    private static int[] locateOldstyle(int program) {
        final int[] l = new int[4];
        for (int i = 0; i < 4; i++) l[i] = GLES20.glGetUniformLocation(program, "uOs" + i);
        return l;
    }

    private static void oldstyleUniforms(int on, int[] adjust, boolean toneLive) {
        final float[] a = styleAfterTone(sOldstyle, toneLive);
        if (on >= 0) GLES20.glUniform1i(on, a != null ? 1 : 0);
        if (a == null || a.length < 16) return;
        for (int i = 0; i < 4; i++) if (adjust[i] >= 0) GLES20.glUniform4f(adjust[i], a[i * 4], a[i * 4 + 1], a[i * 4 + 2], a[i * 4 + 3]);
    }

    /** P630e: the style's uniforms on the screen - its exposure at 0 while the tone is live (the tone's gains took it). */
    static float[] styleAfterTone(float[] style, boolean toneLive) {
        if (style == null || !toneLive || style.length < 3) return style;
        final float[] u = style.clone();
        u[2] = 0f;
        return u;
    }

    /** P90: the gain of the frame on the texture (a series frame scaled to the viewfinder's exposure), and its uniform. */
    private float mFrameGain = 1f;
    private int uGain = -1;

    public synchronized void onFrameAvailable(SurfaceTexture st) {
        ispPending = true;
        VfDrawMeter.arrived(false); // P616: a gap without a new frame is the camera's
        // P353: while the developed RAW frame is on screen (drawn less than RAW_SHOWN_NS ago) an ISP frame is only latched (its
        // buffer goes back to the camera) - each such wake-up drew the same RAW frame again, ~25 of the ~55 draws a second on the
        // OPPO, on the GPU the merge needs. The RAW frames ask for their own draws; without one for RAW_SHOWN_NS the ISP frames
        // draw again (the fall-back to the ISP picture). scam_dev vf_isp_latch 0: every ISP frame draws (as before).
        final long now = System.nanoTime();
        if (now - mRawShownNs < RAW_SHOWN_NS
                && com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_isp_latch", true)) {
            host.queueEvent(this::latchIsp);
            return;
        }
        // P365 step 7: while the shot's series is taken the last RAW frame stays on the screen (up to SERIES_HOLD_NS)
        if (seriesHold(now)) {
            host.queueEvent(this::latchIsp);
            return;
        }
        host.requestRender();
    }

    /** P353: when the developed RAW frame was put on screen last (System.nanoTime). */
    private volatile long mRawShownNs = Long.MIN_VALUE / 2;
    static final long RAW_SHOWN_NS = 300_000_000L;

    /**
     * Whether the pending ISP frame can be latched: one is pending, the context is set up and current - a latch queued by
     * onFrameAvailable (queueEvent) runs even while the GL thread is paused (GLThread takes its events before the pause check),
     * with no context: updateTexImage threw there, on the GL thread.
     */
    static boolean latchable(boolean update, boolean glInit, boolean contextCurrent) {
        return update && glInit && contextCurrent;
    }

    /** The pending ISP frame onto the texture (GL thread). */
    private synchronized void latchIsp() {
        // no context: ispPending stays, the next draw latches the frame
        if (!latchable(ispPending, glReady, !android.opengl.EGL14.EGL_NO_CONTEXT.equals(android.opengl.EGL14.eglGetCurrentContext())))
            return;
        try {
            ispSurface.updateTexImage();
        } catch (RuntimeException e) {
            Log.w("MainRenderer", "ISP latch skipped: " + e);
            return;
        }
        ispPending = false;
        mHaveFrame = true;
        mIspLatchNs = System.nanoTime();
        final long shown = ispSurface.getTimestamp(); // one JNI read for both
        mFrameGain = ViewfinderGain.gainFor(shown); // P90
        sShownTimestamp = shown; // P111
    }

    /** Before each viewfinder shader: GLSL ES 3.00, the source's lines counted from 1 (part of the kept binaries' key). */
    private static final String GLSL_HEAD = "#version 300 es\n #line 1\n";

    private static int linkProgram(String vertex, String fragment) {
        // P312: from the kept driver binary after the first start (these were compiled before the first frame at every start)
        final int program = com.scamera.app.processing.opengl.GLProg.viewfinderProgram(
                com.scamera.app.processing.opengl.GLProg.viewfinderKey(GLSL_HEAD + vertex, GLSL_HEAD + fragment));
        if (program == 0) Log.e("MainRenderer", "viewfinder program failed (GLProgram logged why)");
        return program;
    }

    public void setMirror(boolean on) {
        mirrored = on;
    }

    // P36: the viewfinder transform of a new camera (sensor orientation 90 vs 270, the front mirror) is applied from
    // the new camera's first frame. Applied at once, the frame on screen - still the previous camera's - was redrawn
    // upside down (and mirrored) until the new camera delivered: the 180 degree flip on a switch to the front camera.
    private final Object mPendingLock = new Object();
    private float[] mPendingMatrix;
    private boolean mPendingMirror;
    private int mPendingOrientation;
    private long mPendingSetNs, mPendingArmNs, mPendingFromTs = Long.MAX_VALUE;
    private int mPendingRawSession;
    private int mOrientation = 180;
    private volatile boolean mHaveFrame;
    private int mFramesToLog;
    /** Without a first frame of the new session (no capture start seen) the transform still goes on after this. */
    private static final long PENDING_UNARMED_NS = 3_000_000_000L, PENDING_ARMED_NS = 700_000_000L;

    /**
     * P36: orientation (degrees for the texture rotation) and mirror of the camera about to open. Before any frame was
     * drawn, or when nothing changes, it applies at once; otherwise it waits for {@link #armPendingTransform} and the
     * first frame with a timestamp at or after the new session's first capture.
     */
    public void setViewfinderTransform(int orientation, boolean uMirror) {
        synchronized (mPendingLock) {
            if (!mHaveFrame || (orientation % 360 == mOrientation % 360 && uMirror == mirrored)) {
                mPendingMatrix = null;
                if (orientation % 360 != mOrientation % 360) setOrientation(orientation);
                mirrored = uMirror;
                return;
            }
            // The same transform again (fitViewfinderTransform, then selectCamera' UI post): an armed one stays armed.
            if (mPendingMatrix != null && orientation % 360 == mPendingOrientation % 360 && uMirror == mPendingMirror) return;
            float[] m = new float[16];
            android.opengl.Matrix.setRotateM(m, 0, orientation, 0f, 0f, 1f);
            mPendingMatrix = m;
            mPendingOrientation = orientation;
            mPendingMirror = uMirror;
            mPendingSetNs = System.nanoTime();
            mPendingArmNs = 0;
            mPendingFromTs = Long.MAX_VALUE;
            mPendingRawSession = com.scamera.app.processing.LiveRawFrame.currentSession();
            Log.d("MainRenderer", "viewfinder transform pending orientation=" + orientation + " mirror=" + uMirror
                    + " (now " + mOrientation + "/" + mirrored + ")");
        }
    }

    /** P36: the new session's first capture started at this sensor timestamp; frames from it on get the new transform. */
    public void armPendingTransform(long sensorTimestampNs) {
        synchronized (mPendingLock) {
            if (mPendingMatrix == null || mPendingArmNs != 0) return;
            mPendingFromTs = sensorTimestampNs;
            mPendingArmNs = System.nanoTime();
        }
    }

    /** GL thread, before drawing: the frame in the texture (or the RAW frame) belongs to the new camera. */
    private void applyPendingTransform() {
        synchronized (mPendingLock) {
            if (mPendingMatrix == null) {
                if (mFramesToLog > 0 && ispSurface != null) {
                    mFramesToLog--;
                    Log.d("MainRenderer", "viewfinder frame ts=" + ispSurface.getTimestamp() + " orientation=" + mOrientation + " mirror=" + mirrored);
                }
                return;
            }
            long now = System.nanoTime();
            long ts = ispSurface == null ? 0 : ispSurface.getTimestamp();
            String reason = null;
            if (mPendingArmNs != 0 && ts >= mPendingFromTs) reason = "first frame of the new session ts=" + ts;
            else if (com.scamera.app.processing.LiveRawFrame.peekSession() > mPendingRawSession) reason = "first RAW frame of the new session";
            else if (mPendingArmNs != 0 && now - mPendingArmNs > PENDING_ARMED_NS) reason = "timeout after the first capture";
            else if (now - mPendingSetNs > PENDING_UNARMED_NS) reason = "timeout";
            if (reason == null) {
                Log.d("MainRenderer", "viewfinder frame ts=" + ts + " keeps orientation=" + mOrientation + " mirror=" + mirrored + " (previous camera)");
                return;
            }
            System.arraycopy(mPendingMatrix, 0, turn, 0, 16);
            mOrientation = mPendingOrientation;
            mirrored = mPendingMirror;
            mPendingMatrix = null;
            mFramesToLog = 3;
            Log.d("MainRenderer", "viewfinder transform applied orientation=" + mOrientation + " mirror=" + mirrored + " on " + reason
                    + " (" + (now - mPendingSetNs) / 1_000_000 + " ms after it was set)");
        }
    }

    /** Focus peaking: 1 drawn, 0 not. The setting 1 always, 2 (auto) while the manual focus is on or its dial is chosen. */
    private int peakMode() {
        final int setting = ScameraApp.getSettings().peakingColour;
        if (setting == 1) return 1;
        final ManualModeConsole manual = manualPanel;
        final boolean manualFocus = manual != null && (manual.manualFocusOn() || manual.focusKnobOpen());
        return setting == 2 && manualFocus ? 1 : 0;
    }

    /** The texture's turn for an orientation (degrees about z). */
    public void setOrientation(int degrees) {
        android.opengl.Matrix.setRotateM(turn, 0, degrees, 0f, 0f, 1f);
        mOrientation = degrees;
    }
}
