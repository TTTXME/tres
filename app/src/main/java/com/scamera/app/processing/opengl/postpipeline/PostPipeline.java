package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.opengl.GLES30;

import androidx.annotation.Nullable;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.control.ZoomController;
import com.scamera.app.processing.JpegliEncoder;
import com.scamera.app.processing.ShotTimeline;
import com.scamera.app.processing.color.FilmChain;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.opengl.GLBasePipeline;
import com.scamera.app.processing.opengl.GLCoreBlockProcessing;
import com.scamera.app.processing.opengl.GLDrawParams;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLImage;
import com.scamera.app.processing.opengl.GLInterface;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.GLTexturePool;
import com.scamera.app.processing.opengl.PostGlMode;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.processing.parameters.ResolutionSolution;
import com.scamera.app.processing.render.Converter;
import com.scamera.app.processing.render.NoiseModeler;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The photo's GPU pipeline after the merge: the merged RGB of SCAM Hybrid / SCAM HDR through colour, denoise, the light
 * nodes, the tone (WHY V1 / V2 or Oldstyle) and the tail (texture, final size, film grain, rotation), read back as the
 * finished bitmap (or streamed into a JPEG); then, for Ultra HDR, the gain map of that picture ({@link SceneGainMap}).
 *
 * <p>The nodes reach the shot's state through the public fields below (the pipeline is their shared blackboard).
 */
public class PostPipeline extends GLBasePipeline {
    private static final String TAG = "PostPipeline";
    private static final String LOG_ROUTE = "SCAM_PIPELINE";

    /** P614: the largest grid «Why тон» V2 runs on (the 200 MP grids keep V1). */
    static final long V2_MAX_PIXELS = 100_000_000L;
    /** W1.3: the largest shot whose GL teardown may wait until the JPEG is saved. */
    private static final long DEFERRED_TEARDOWN_PIXELS = 16_000_000L;

    // ---- the shot's blackboard (written and read by the nodes) ----

    /**
     * The lens-shading map (ScamRgb uploads it); P349 the glow share of the fully clipped cores (ScamRgb.sunBloom, read by
     * WhyCombine's sun bloom); P378 the nearness of a light «Inpaint opposed» rebuilt (ScamRgb.opposedShare, WhyCombine's
     * highlight landing). The two shares come with the image size they cover and their block, px (P277: the whole frame's on
     * a lab fragment).
     */
    public GLTexture GainMap, sunBloom, opposedShare;
    public Point sunBloomSize, opposedShareSize;
    public int sunBloomBlock, opposedShareBlock;
    /**
     * The WHY exposure of the shot as a linear display gain (WhyStats / WhyAe); the noise sigma ScamHdrDenoise estimated
     * (variance-stabilised units, 0: not measured).
     */
    public float linearDisplayGain = 1f, scamNoiseSigma;
    /**
     * The working RGB holds signed values: set by ScamRgb (scamrgb signedU, SCAM Hybrid), cleared by a noise reduction
     * that writes its clamped output (the SCAM engine; the NLM engine keeps it: chromadn/apply clips once, downstream).
     * WhyCombine then clamps its B-spline colour after the interpolation; otherwise (SCAM HDR, or the hybrid after the
     * SCAM denoise) per tap as before, so negative taps of the colour matrix change nothing there.
     */
    public boolean signedRgb;
    /** WHY tone of the SCAM Hybrid: state handed from WhyStats to WhyCombine; null outside that route. */
    public WhyTone.State why;
    /** P614 «Why тон» V2: state handed from WhyV2Stats to WhyV2Fusion and WhyV2Tone; null outside that route. */
    public WhyV2Stats.State whyV2;
    /**
     * P378 review (Ultra HDR keeps the sky term's change: the gain map's boost is the scene plane over the SDR, so a lower SDR sky
     * came back to its old light on an HDR display): the SDR's own ratio of the sky term (WhyCombine: why/combine at whyLow with
     * and without it, why/skyratio), per pixel of a grid of whyLow (sensor layout, its longer side at most WhyCombine.SKY_RATIO_SIDE),
     * row-major; null without a non-backlit sky or Ultra HDR. RunHDRGainMap scales the scene plane by it, then lets it go.
     */
    public float[] skyHdrRatio;
    public Point skyHdrRatioSize;
    /**
     * P277: a lab shot's run records the whole frame's statistics here ({@link LabFrame}); a lab run on a fragment of it sets
     * {@link #frozen} to those, used in place of the fragment's own. Both null in an ordinary run.
     */
    public LabFrame record, frozen;
    /** Diagnostic strips ScamProcessor appends under the photo; no node fills it at present. */
    public final List<Bitmap> debugData = new ArrayList<>(0);
    /**
     * The crop the rotation works on; the final output size of the hybrid 2x pipeline (sensor orientation, made by
     * {@link HybridFinalResize}; null: the output at rawSize) and whether that node ran.
     */
    public Point cropSize, finalSize;
    public boolean finalResized;
    /**
     * P391 (review): the noise model of the WHY fusion's caps (WhyStats: the deep floor, the fine bands) - the one-frame pair as
     * noiseS / noiseO, but on a DCG shot with measured zones the deep shadows' zone (high gain under the knee), where the clamp's
     * pair is mid grey's (low gain above the knee); NaN until computeNoise.
     */
    public float fusionNoiseS = Float.NaN, fusionNoiseO = Float.NaN;

    // ---- what the caller sets before Run ----

    /**
     * W1.3: {@link #Run} leaves the GL state (the EGL context and the textures still open) for {@link #finishDeferredTeardown},
     * which the caller runs after the JPEG is saved. Only at 16 MP or less without Ultra HDR ({@link #canDeferTeardown}):
     * above it the encode would hold the GL working set next to the bitmap, which the earlier release exists for.
     */
    public boolean deferTeardown;
    /**
     * 10-bit HEIC / AVIF (heif.Heic10Support.wanted, avif.AvifEncoder.tenBitImageWanted): the last pass renders into an
     * RGB10_A2 target and Run returns an RGBA_1010102 bitmap; off: the RGBA8 target and ARGB_8888 bitmap.
     * P46 «Цветовое пространство» Display P3: WhyCombine renders in Display P3 primaries (why/combine.glsl with P3_OUT 1);
     * off: the sRGB shader exactly as before (the define is not set).
     */
    public boolean tenBitOutput, p3Output;
    /**
     * Owner, 2026-10-28 («в видоискателе вижу эффект, на конечном фото нет»): the shot's lens filter as the viewfinder showed it
     * at the press (ScamProcessor sets it from the shot's saver); null: none taken, the settings now (the lab's renders, under
     * their overlay).
     */
    @Nullable
    public LensFilter.Choice lensFilter;
    /** The shot's film, LUT and «Цвет» at the press (ShotLook; ScamProcessor sets it); null: the settings now (the lab's renders). */
    @Nullable
    public ShotLook look;

    // ---- the film snapshot ----

    /**
     * P369 (owner, 2026-10-27: «Убедись что при включении симуляций плёнки наши алгоритмы не будут мешать симуляциям»): the shot's
     * film simulation (null without a stock or at strength 0) and the chosen LUT with its strength, taken once per shot by
     * {@link #takeFilm} - before the teardown decision and the pipeline - so every film gate of the shot (the nodes, the scene
     * feed of WhyCombine, the gain map) reads one snapshot, never the settings again while the shot is processed.
     */
    @Nullable
    public FilmSim.Settings film;
    public boolean filmTaken;
    public String filmUserLut = "";
    public float filmUserStrength;
    /** The reference film engine's state (ReferenceFilm writes it, ReferenceFilmMix reads it). */
    GLTexture referenceFilmTexture;
    FilmSim.Settings referenceFilmSettings;
    FilmLut referenceFilmUserLut;
    boolean referenceFilmApplied, referenceFilmNativeP3;
    /** Nodes the reference engine replaces once it ran: its effects always, its tone at full strength. */
    private final Set<Node> referenceFilmEffects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Node> referenceFilmTone = Collections.newSetFromMap(new IdentityHashMap<>());

    // ---- Ultra HDR, JPEG streaming, teardown ----

    /** Whether this run keeps the linear scene light for the gain map (LinearExposure, WhyCombine's sky ratio read it). */
    public boolean captureDemosaic;
    private final SceneGainMap gainMapPass = new SceneGainMap();
    /** P483: the JPEG {@link #streamJpeg} asked for, when Run returned null; else null. */
    public com.scamera.app.processing.ChunkedBytes streamedJpeg;
    private int jpegQuality = -1;
    private JpegPlan jpegPlan;
    private boolean teardownPending;
    /**
     * The processing {@link #Run} worked on, left by {@link #RunHDRGainMap} for a context of its own; {@link #close} closes it after
     * the gain-map one (closing it earlier SEGV'd on Adreno; never closing it leaked a context per HDR shot).
     */
    private GLCoreBlockProcessing retiredProcessing;

    public PostPipeline() {
        super("PostPipeline");
    }

    @Override protected boolean skipNode(Node node) {
        if (!referenceFilmApplied) return false;
        return referenceFilmEffects.contains(node) || referenceFilmSettings.strength >= 1f && referenceFilmTone.contains(node);
    }

    /** The reference film engine (native profiles) is on for this film. */
    private static boolean referenceEngine(@Nullable FilmSim.Settings film) {
        return film != null && PreferenceKeys.scamDevSwitch("film_reference", true) && ReferenceFilm.supported(film);
    }

    /**
     * P369: the shot's film snapshot ({@link #film}, the LUT and its strength): the press's ({@link #look}), else the settings now
     * (the lab's overlay too). A film of the old route whose data cannot be read is dropped (the photo without it).
     */
    public void takeFilm() {
        final ShotLook press = look;
        if (press != null) {
            film = press.film;
            filmUserStrength = press.lutStrength;
            filmUserLut = press.lut;
        } else {
            film = FilmSim.current();
            filmUserStrength = PreferenceKeys.filmLutStrength();
            filmUserLut = filmUserStrength > 0f ? PreferenceKeys.filmLut() : "";
        }
        filmTaken = true;
        if (press != null) {
            final String now = ShotLook.now().toString();
            if (!now.equals(press.toString()))
                Log.i(LOG_ROUTE, "look of the press: " + press + " (the settings now: " + now + ")");
        }
        // the reference engine reads its own profiles; only the old route needs the LUT chain
        if (film == null || referenceEngine(film)) return;
        if (FilmChain.scene(ScameraApp.getAppContext(), film, filmUserLut, filmUserStrength) == null) {
            Log.w(LOG_ROUTE, "film data unreadable (" + film.stock.id + "): the photo without the film");
            film = null;
        }
    }

    /**
     * The nodes on the linear scene light before the tone: the lens filter of the shot (P228; none chosen: no node), then the
     * film's own light nodes ({@link #filmLightNodes}). Without a film: the lens filter's node alone.
     */
    static List<Node> lightNodes(LensFilter.Choice shotFilter, @Nullable FilmSim.Settings film) {
        final List<Node> nodes = new ArrayList<>(3);
        final LensFilter filter = LensFilter.of(shotFilter);
        if (filter != null) nodes.add(filter);
        nodes.addAll(filmLightNodes(film));
        return nodes;
    }

    /**
     * P380 the film's camera diffusion (LensFilter.film: spektrafilm's order, the camera's filter before the film's halation),
     * P281 the film's halation and scatter (FilmHalation.from); each only when the film has it.
     */
    private static List<Node> filmLightNodes(@Nullable FilmSim.Settings film) {
        final List<Node> nodes = new ArrayList<>(2);
        final LensFilter diffusion = LensFilter.film(film);
        if (diffusion != null) nodes.add(diffusion);
        final FilmHalation halation = FilmHalation.from(film);
        if (halation != null) nodes.add(halation);
        return nodes;
    }

    /** The shot's lens filter: the press's ({@link #lensFilter}), else the settings now. */
    public LensFilter.Choice lensFilterOfShot() {
        return lensFilter == null ? LensFilter.Choice.now() : lensFilter;
    }

    /**
     * P369 (outputs-1): the gain map (Ultra HDR, the HDR HEIC / AVIF) only without a film: its scene-anchored boost undoes the
     * film's highlight rendering and divides out its grain on an HDR screen, so a film photo is saved in SDR.
     */
    public static boolean gainMapWanted(boolean setting, @Nullable FilmSim.Settings film) {
        return film == null && setting;
    }

    /**
     * P369 (texture-local-contrast, settings-6): «Текстура» (ScamLocalContrast) runs after WhyCombine, on the film's print: it
     * boosted the print's 1 - 16 px bands by 25 - 50 %. Not with a film.
     */
    static boolean wantsLocalContrast(boolean texture, @Nullable FilmSim.Settings film) {
        return film == null && texture;
    }

    /** The shot's rotation in degrees (the parameters'); logs what the device reports beside it. */
    public int getRotation() {
        final int degrees = shotParams.rotationDeg;
        try {
            Log.d("ParseExif", "Gravity rotation:" + ScameraApp.getGravity().getRotation()
                    + " sensor:" + ScameraApp.getCaptureController().mSensorOrientation + " shot:" + degrees);
        } catch (RuntimeException noCamera) {
            // P277: the lab renders with no camera open (no gravity sensor or capture controller in the process yet)
            Log.d("ParseExif", "rotation " + degrees + " (no camera open)");
        }
        return degrees;
    }

    private static boolean quarterTurn(int degrees) {
        return degrees == 90 || degrees == 270;
    }

    /** {@code size} as the picture stands after the rotation (width and height swapped on a quarter turn). */
    @SuppressWarnings("SuspiciousNameCombination")
    private static Point turned(Point size, int degrees) {
        return quarterTurn(degrees) ? new Point(size.y, size.x) : size;
    }

    /** The rotate-style shaders' index of a rotation: the quarter turns counted the other way (90 -> 3, 180 -> 2, 270 -> 1). */
    private static int rotateIndex(int degrees) {
        return degrees == 90 || degrees == 180 || degrees == 270 ? (360 - degrees) / 90 : 0;
    }

    /** The mean of the three channel models' slopes ({@code slope}) or offsets, summed in float as R + G + B. */
    private static float channelMean(NoiseModeler modeler, boolean slope) {
        float sum = 0f;
        final android.util.Pair<Double, Double>[] model = modeler.stackModel;
        for (int c = 0; c < 3; c++) sum += (slope ? model[c].first : model[c].second).floatValue();
        return sum / 3.f;
    }

    /** At least the smallest normal float: the noise pair divides downstream. */
    private static float positive(float v) {
        return Math.max(v, Float.MIN_NORMAL);
    }

    private static boolean hasModel(NoiseModeler modeler) {
        if (modeler == null || modeler.stackModel == null || modeler.stackModel.length < 3) return false;
        for (int c = 0; c < 3; c++) if (modeler.stackModel[c] == null) return false;
        return true;
    }

    /**
     * The shot's noise pair (noiseS / noiseO: the channels' mean of the processing model) and the fusion's pair
     * ({@link #fusionNoiseS}). P320: a DCG shot's clamp (WhyAe.gainClamp, WhyStats' only reader of noiseS / noiseO) takes the
     * measured zones - 0.5's zone (low gain above the knee) in place of the green one-frame pair, scaled as the model averaged
     * here - and P391 the fusion the deep shadows' high-gain zone; without zones the camera's curve stays (logged).
     */
    void computeNoise(Parameters parameters) {
        final NoiseModeler modeler = parameters.noiseModel;
        if (!hasModel(modeler)) {
            Log.w(TAG, "Missing noise model; using conservative fallback");
            noiseS = 1.0e-4f;
            noiseO = 1.0e-6f;
            fusionNoiseS = noiseS;
            fusionNoiseO = noiseO;
            return;
        }
        noiseS = channelMean(modeler, true);
        noiseO = channelMean(modeler, false);
        float fusionS = Float.NaN, fusionO = Float.NaN;
        if (parameters.dcgShot && !PostGlMode.legacy()) {
            final float[] zones = NoiseModeler.dcgZones(parameters.dcgNoise);
            final boolean green = modeler.rawModel != null && modeler.rawModel.length > 1 && modeler.rawModel[1] != null;
            final double greenS = green ? modeler.rawModel[1].first : Double.NaN;
            final double greenO = green ? modeler.rawModel[1].second : Double.NaN;
            final float[] clamp = zones != null ? WhyAe.dcgClampModel(zones, noiseS, noiseO, greenS, greenO) : null;
            if (clamp == null) {
                Log.i(TAG, "DCG gain clamp: no measured noise zones, the camera's single curve");
            } else {
                final float[] deep = WhyAe.dcgShadowModel(zones, noiseS, noiseO, greenS, greenO);
                fusionS = deep[0];
                fusionO = deep[1];
                Log.i(TAG, "DCG fusion noise model (the deep shadows' high-gain zone): S " + fusionS + ", O " + fusionO);
                Log.i(TAG, "DCG gain clamp model: S " + noiseS + " -> " + clamp[0] + ", O " + noiseO + " -> " + clamp[1]
                        + " (zones " + Arrays.toString(zones) + ", offset of 0.5's zone)");
                noiseS = clamp[0];
                noiseO = clamp[1];
            }
        }
        Log.d(TAG, "NoiseS:" + noiseS + "\nNoiseO:" + noiseO);
        noiseS = positive(noiseS);
        noiseO = positive(noiseO);
        fusionNoiseS = positive(Float.isNaN(fusionS) ? noiseS : fusionS);
        fusionNoiseO = positive(Float.isNaN(fusionO) ? noiseO : fusionO);
    }

    /**
     * P483 (speed): a caller that only saves the photo as a JPEG asks for it here before {@link #Run}: the final readback's mapped
     * pixels are encoded straight away (JpegliEncoder, the bytes of the bitmap path) and Run returns null with the file in
     * {@link #streamedJpeg}. Taken only on the pixel-pack buffer path (up to 16 MP, 8-bit, not the old model of post_ab) and with
     * scam_dev.txt "jpeg_stream" on; otherwise Run returns the bitmap as before and streamedJpeg stays null. One Run each call.
     */
    public void streamJpeg(int quality) {
        streamJpeg(quality, null);
    }

    /** P483: {@link #streamJpeg(int)} with the caller's crop and stream wrapper ({@code plan} null: the whole frame, plain JPEG). */
    public void streamJpeg(int quality, @Nullable JpegPlan plan) {
        jpegQuality = quality;
        jpegPlan = plan;
    }

    /**
     * P483: what a save-only caller does to the streamed JPEG - the part of the frame it keeps ({@link #crop}, null: all of it;
     * a sub-rectangle is the bytes of Bitmap.createBitmap of it) and the stream the encoder writes into ({@link #wrap}: the EXIF
     * / ICC insertion of the file, the cropped size given).
     */
    public interface JpegPlan {
        @Nullable Rect crop(int width, int height);

        OutputStream wrap(OutputStream out, int width, int height) throws IOException;

        /** P620: {@link #wrap} given the pixels about to be encoded (RGBA_8888, rows {@code strideBytes} apart; read only). */
        default OutputStream wrap(OutputStream out, int width, int height, @Nullable ByteBuffer pixels, int strideBytes)
                throws IOException {
            return wrap(out, width, height);
        }

        /**
         * P642: whether {@link #draw} has something to draw into the pixels (the watermark, the ES / L stamp): the readback is
         * then mapped for writing as well; where it cannot be, the photo takes the bitmap route.
         */
        default boolean draws() {
            return false;
        }

        /**
         * P642: draws into the pixels about to be encoded (before {@link #wrap}, whose thumbnails then show it), the part
         * {@link #crop} kept. Returns how to take the drawing back - run when the stream fails afterwards, because the bitmap
         * route then reads the same pixels and draws on them itself; null: nothing drawn. Throws with the pixels as they
         * were to give the photo to the bitmap route.
         */
        @Nullable
        default Runnable draw(ByteBuffer pixels, int width, int height, int strideBytes) {
            return null;
        }
    }

    /** Clears what a previous run of this pipeline left on the blackboard. */
    private void clearShotState() {
        gainMapPass.newRun();
        referenceFilmApplied = referenceFilmNativeP3 = false;
        referenceFilmTexture = null;
        referenceFilmSettings = null;
        referenceFilmUserLut = null;
        referenceFilmEffects.clear();
        referenceFilmTone.clear();
        linearDisplayGain = 1f;
        scamNoiseSigma = 0f;
        signedRgb = false;
        whyV2 = null;
        sunBloom = opposedShare = null;
        sunBloomSize = opposedShareSize = null;
        sunBloomBlock = opposedShareBlock = 0;
        skyHdrRatio = null;
        skyHdrRatioSize = null;
        finalSize = null;
        finalResized = false;
        streamedJpeg = null;
    }

    /** The photo: the nodes over the merged RGB, read back as the bitmap (null when {@link #streamJpeg} took it). */
    public Bitmap develop(ByteBuffer inBuffer, Parameters parameters) {
        // the old-model run of post_ab is not the shot's own: its times are in the POST AB line, not in the SHOT TIMELINE
        final boolean oldModel = PostGlMode.legacy();
        if (!oldModel) ShotTimeline.mark("post_start");
        PostGlMode.setSyncNodes(!oldModel && PreferenceKeys.scamDevSwitch("post_sync", false));
        PostGlMode.setInvalidate(!oldModel && PreferenceKeys.scamDevSwitch("post_invalidate", true));
        // P477 (memory): the run's texture inventory and reuse of closed textures of the same size and format (scam_dev.txt
        // "tex_pool"; off and in the old model: every texture its own allocation)
        GLTexturePool.begin(!oldModel && PreferenceKeys.scamDevSwitch("tex_pool", false));
        beginPass(parameters, parameters.rawSize);
        // P369: ScamProcessor took the film before the teardown decision; the lab's sides take it here, under their overlay
        if (!filmTaken) takeFilm();
        captureDemosaic = gainMapWanted(shotSettings.gainMapPass(), film);
        clearShotState();
        // P145: the hybrid's final size is made on the GPU (HybridFinalResize before the rotation), Ultra HDR included:
        // the output, the crop and the rotation work on the resized image
        final Point fin = parameters.hybridFinalSize;
        final boolean shrinks = fin != null && fin.x < parameters.rawSize.x && fin.y < parameters.rawSize.y;
        if (shrinks) finalSize = new Point(fin);
        cropSize = new Point(shrinks ? fin : parameters.rawSize);
        // «16:9»: the frames arrive cropped (Wide169); a second crop here made the output a row taller than its data (P118)
        final Point picture = turned(shrinks ? finalSize : parameters.rawSize, getRotation());
        GLDrawParams.TILE_ROWS = (long) parameters.rawSize.x * parameters.rawSize.y < ResolutionSolution.SMALL_PIXELS ? 8 : 256;
        final GLFormat rgba8 = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);
        // the last pass goes tile by tile straight into the bitmap (no full-frame readback buffer); P353: on the context
        // kept for the process (its programs linked once)
        final GLCoreBlockProcessing glproc = new GLCoreBlockProcessing(picture, new GLImage(picture, rgba8, false), rgba8,
                GLDrawParams.Allocate.None, tenBitOutput, true);
        glIface = new GLInterface(glproc);
        glIface.shotParams = parameters;
        final StreamedJpegSink sink = jpegSink(glproc, oldModel);
        glproc.rgbaSink = sink;

        buildNodes();
        // the peak is the GL working set + the bitmap, without the full-frame readback buffer (~200 MB at 50 MP)
        final Bitmap picturePixels = runAllToBitmap();
        glproc.rgbaSink = null;
        if (sink != null) streamedJpeg = sink.jpeg;
        if (!glproc.sinkTook) streamedJpeg = null; // P483: a sink result whose buffer was lost afterwards is not the photo
        if (tenBitOutput) {
            final boolean eight = picturePixels.getConfig() == Bitmap.Config.ARGB_8888;
            Log.i(TAG, "final image " + picturePixels.getWidth() + "x" + picturePixels.getHeight() + " " + picturePixels.getConfig()
                    + (eight ? " (10-bit target unavailable)" : " for the 10-bit HEIC / AVIF"));
        }
        if (!oldModel) ShotTimeline.mark("post_readback");
        if (deferTeardown && canDeferTeardown(parameters, film)) {
            teardownPending = true; // W1.3: released after the encode (finishDeferredTeardown), off the press-to-saved path
            return picturePixels;
        }
        // the GL working set (three FP16 textures = 1.2 GB on the 50 MP Sabre 2x grid) goes right after the readback, while
        // the EGL context is current; the gain map works from the CPU copy of the linear light (LinearExposure)
        dropTextures();
        return picturePixels;
    }

    /** What Run and RunHDRGainMap both start with: the shot's parameters, the settings, the work size and the noise pair. */
    private void beginPass(Parameters parameters, Point work) {
        shotParams = parameters;
        shotSettings = ScameraApp.getSettings();
        frameSize = new Point(work);
        computeNoise(parameters);
    }

    /** Waits for the queued GL work, then deletes every open texture and framebuffer (on the current context). */
    private static void dropTextures() {
        try {
            GLES30.glFinish();
        } catch (RuntimeException ignored) {
            // nothing pending to wait for
        }
        GLTexture.closeAll();
    }

    /** P483: the JPEG sink of this run, or null (no request, the old model, 10-bit output, switched off, no encoder). */
    @Nullable
    private StreamedJpegSink jpegSink(GLCoreBlockProcessing glproc, boolean oldModel) {
        final int quality = jpegQuality;
        final JpegPlan plan = jpegPlan;
        jpegQuality = -1;
        jpegPlan = null;
        glproc.sinkWrites = plan != null && plan.draws(); // P642
        final boolean stream = quality > 0 && !oldModel && !tenBitOutput
                && PreferenceKeys.scamDevSwitch("jpeg_stream", true) && JpegliEncoder.available();
        return stream ? new StreamedJpegSink(glproc, quality, plan) : null;
    }

    /**
     * W1.3: the GL teardown may wait until the JPEG is saved: 16 MP or less, no Ultra HDR pass, not the old model of post_ab.
     * P369: the gain-map pass as the shot's film snapshot decides it ({@link #gainMapWanted}).
     */
    public static boolean canDeferTeardown(Parameters parameters, @Nullable FilmSim.Settings film) {
        if (parameters == null || parameters.rawSize == null) return false;
        if (gainMapWanted(ScameraApp.getSettings().gainMapPass(), film)) return false;
        return (long) parameters.rawSize.x * parameters.rawSize.y <= DEFERRED_TEARDOWN_PIXELS && !PostGlMode.legacy();
    }

    /**
     * W1.3: the teardown {@link #Run} skipped with {@link #deferTeardown} (textures, framebuffers, EGL context), after the
     * JPEG encode; also after a run with {@link #deferTeardown} that failed half way (its textures are still open).
     */
    public void finishDeferredTeardown() {
        final boolean due = teardownPending || deferTeardown;
        teardownPending = false;
        if (due && glIface != null) {
            deferTeardown = false;
            try {
                final GLCoreBlockProcessing context = glIface.blockCtx;
                if (context != null) context.makeCurrent();
                dropTextures();
            } catch (RuntimeException failed) {
                Log.e(TAG, "deferred GL teardown failed: " + failed);
            }
        }
        close();
    }

    /**
     * LinearExposure's hand-over of the linear scene light for the gain map; P145: at the hybrid's final size when the pipeline
     * resizes to it (16 B a pixel: 800 MB of the 50 MP grid -> 320 MB of 20 MP; the 200 MP grid fits under 2 GB).
     */
    public void captureDemosaicLinear(GLTexture full, GLProg prog) {
        gainMapPass.keep(full, prog, finalSize);
    }

    /**
     * The Ultra HDR gain map of {@code sdr}, the picture {@link #Run} returned (see {@link SceneGainMap}): one byte per
     * pixel of 1/{@code down} of its size per axis, log2 gains over {@code scale} stops ({@link
     * com.scamera.app.processing.ultrahdr.GainMapComputer#SCALE}). Throws when the light was not kept or does not line up
     * with the picture (the caller then saves SDR).
     */
    public GainMapRaw RunHDRGainMap(ByteBuffer inBuffer, Parameters parameters, Bitmap sdr, int down, float scale) {
        if (!gainMapPass.holdsLight())
            throw new IllegalStateException("no linear scene light kept: Run must finish first, with the gain map wanted");
        // P145: the light and the picture are at the hybrid's final size when Run resized on the GPU
        final Point base = new Point(finalSize != null ? finalSize : parameters.rawSize);
        beginPass(parameters, base);
        captureDemosaic = false;
        cropSize = new Point(base);
        final int degrees = getRotation();
        final Point picture = turned(base, degrees);
        // a map off the picture by a pixel or a turn moves the boost off the scene: refuse it (the caller saves SDR)
        if (sdr.getWidth() != picture.x || sdr.getHeight() != picture.y)
            throw new IllegalStateException("SDR/gain-map size mismatch: sdr=" + sdr.getWidth() + "x" + sdr.getHeight()
                    + " map=" + picture.x + "x" + picture.y);
        final GLFormat rgba16f = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        // a placeholder output on the Direct allocation path; the previous context stays alive until close() (destroying it
        // before the textures were made again SEGV'd on waffle/Adreno)
        useGainMapContext(new GLCoreBlockProcessing(picture, new GLImage(picture, rgba16f, false), rgba16f,
                GLDrawParams.Allocate.Direct));
        glIface.shotParams = parameters;
        final Point light = gainMapPass.lightSize();
        if (light.x != frameSize.x || light.y != frameSize.y)
            throw new IllegalStateException("the kept light is " + light.x + "x" + light.y + ", the pass works at "
                    + frameSize.x + "x" + frameSize.y + ": no scene plane");
        final SceneGainMap.Shot shot = new SceneGainMap.Shot();
        shot.parameters = parameters;
        shot.prog = glIface.prog;
        shot.pictureSize = picture;
        shot.base = base;
        shot.cropSize = cropSize;
        shot.rotateIndex = rotateIndex(degrees);
        shot.p3 = p3Output;
        shot.skyRatio = skyHdrRatio;
        shot.skyRatioSize = skyHdrRatioSize;
        skyHdrRatio = null;
        skyHdrRatioSize = null;
        try {
            // P370 (B3): the scene's luminance as the render sees it (its colour matrix and white balance)
            shot.lumaWeights = sceneLumaWeights(WhyTone.colourMatrix(this), parameters.cameraToProPhoto, parameters.asShotNeutral);
        } catch (RuntimeException e) {
            gainMapPass.abandon();
            throw e;
        }
        return gainMapPass.measure(shot, sdr, down, scale);
    }

    /** RunHDRGainMap's context takes over from runPass()'s, which is kept for {@link #close} ({@link #retiredProcessing}). */
    void useGainMapContext(GLCoreBlockProcessing gainMapProcessing) {
        final GLCoreBlockProcessing runs = glIface != null ? glIface.blockCtx : null;
        if (runs != null && runs != gainMapProcessing) retiredProcessing = runs;
        glIface = new GLInterface(gainMapProcessing);
    }

    @Override
    public void close() {
        if (teardownPending) {
            finishDeferredTeardown(); // W1.3, an error path: the textures go before the context
            return;
        }
        gainMapPass.release(); // the gain-map pass may never have run
        super.close();
        // runPass()'s context after the gain-map one: made current, its tile target deleted, then released or destroyed
        final GLCoreBlockProcessing retired = retiredProcessing;
        retiredProcessing = null;
        if (retired == null) return;
        try {
            retired.close();
        } catch (Exception e) {
            Log.e(TAG, "retired GL context close failed: " + e);
        }
    }

    /**
     * P370 (B3): the Ultra HDR scene plane's luminance weights of white-balanced camera RGB (ultrahdr/sceneluma wU): Rec.709
     * of the render's linear sRGB, LUMA^T . intermediateToSRGB . sensorToProPhoto . diag(neutral) (why/low.glsl: the input times
     * the neutral, then the two matrices). A white maps to the render's white: 1 on the ISP routes.
     */
    static float[] sceneLumaWeights(float[] intermediateToSRGB, float[] sensorToProPhoto, float[] neutral) {
        final float[] toSrgb = new float[9];
        Converter.multiply(intermediateToSRGB, sensorToProPhoto, toSrgb);
        final float[] rec709 = {0.2126f, 0.7152f, 0.0722f};
        final float[] weights = new float[3];
        for (int col = 0; col < 3; col++) {
            float y = 0f;
            for (int row = 0; row < 3; row++) y += rec709[row] * toSrgb[row * 3 + col];
            weights[col] = y * neutral[col];
        }
        return weights;
    }

    /** The encoded gain map of a picture with what it takes to decode and follow the picture's later resizes and crops. */
    public static final class GainMapRaw {
        public final Bitmap bitmap;
        /** Size in pixels, the downsample per axis against the picture, the log2 range of the encoding. */
        public final int w, h, down;
        public final float scale;
        /**
         * P145: GainMapMax when the GPU already requantised the map to [0, it] (GainMapComputer.normalised); NaN for the
         * encoded map that GainMapComputer.compute normalises. A resize or crop afterwards filters the requantised bytes, which
         * the same range still decodes (within one step of 1/255).
         */
        public final float normalisedMax;

        GainMapRaw(Bitmap bitmap, int w, int h, int down, float scale) {
            this(bitmap, w, h, down, scale, Float.NaN);
        }

        GainMapRaw(Bitmap bitmap, int w, int h, int down, float scale, float normalisedMax) {
            this.bitmap = bitmap;
            this.w = w;
            this.h = h;
            this.down = down;
            this.scale = scale;
            this.normalisedMax = normalisedMax;
        }

        private int perAxis() {
            return Math.max(1, down);
        }

        /** The same encoding over another bitmap of the map. */
        private GainMapRaw with(Bitmap other, int width, int height) {
            return new GainMapRaw(other, width, height, down, scale, normalisedMax);
        }

        /**
         * The map following a resize of the SDR base to {@code baseW} x {@code baseH} (the hybrid's final size after the
         * 2x pipeline): the same field of view at 1/down of the new size. The values are log gains, so bilinear filtering
         * averages in the log domain. Box-averaged through halvings first (like the "area" downsampler), then to the exact
         * size. Recycles this map's bitmap when it is replaced.
         */
        public GainMapRaw resizedFor(int baseW, int baseH) {
            final int tw = Math.max(1, baseW / perAxis()), th = Math.max(1, baseH / perAxis());
            if (tw == w && th == h) return this;
            Bitmap step = bitmap;
            while (step.getWidth() >= 2 * tw && step.getHeight() >= 2 * th) {
                final Bitmap halved = Bitmap.createScaledBitmap(step, step.getWidth() / 2, step.getHeight() / 2, true);
                if (step != bitmap) step.recycle();
                step = halved;
            }
            final Bitmap exact = Bitmap.createScaledBitmap(step, tw, th, true);
            if (step != bitmap && step != exact) step.recycle();
            if (exact != bitmap) bitmap.recycle();
            return with(exact, tw, th);
        }

        /** The map following a centre crop of the base (digital zoom): the same crop factor (ZoomController.crop). */
        public GainMapRaw croppedBy(float factor) {
            final Bitmap cropped = ZoomController.crop(bitmap, factor);
            return cropped == bitmap ? this : with(cropped, cropped.getWidth(), cropped.getHeight());
        }

        /** The map following the crop {@code crop} of a {@code baseWidth} x {@code baseHeight} base; recycles this bitmap. */
        public GainMapRaw croppedTo(Rect crop, int baseWidth, int baseHeight) {
            final boolean inside = baseWidth > 0 && baseHeight > 0 && !crop.isEmpty() && crop.left >= 0 && crop.top >= 0
                    && crop.right <= baseWidth && crop.bottom <= baseHeight;
            if (!inside) throw new IllegalArgumentException("Invalid SDR/gain-map crop");
            if (crop.width() == baseWidth && crop.height() == baseHeight && crop.left == 0 && crop.top == 0) return this;
            final int tw = Math.max(1, crop.width() / perAxis()), th = Math.max(1, crop.height() / perAxis());
            // the crop in the map's own pixels (each bound scaled as crop * w / baseWidth)
            final RectF from = new RectF((float) crop.left * w / baseWidth, (float) crop.top * h / baseHeight,
                    (float) crop.right * w / baseWidth, (float) crop.bottom * h / baseHeight);
            final Matrix fit = new Matrix();
            fit.setRectToRect(from, new RectF(0, 0, tw, th), Matrix.ScaleToFit.FILL);
            final Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            try {
                new Canvas(out).drawBitmap(bitmap, fit, new Paint(Paint.FILTER_BITMAP_FLAG));
            } catch (RuntimeException failure) {
                out.recycle();
                throw failure;
            }
            bitmap.recycle();
            return with(out, tw, th);
        }
    }

    /** The node list of the shot: the merge's RGB, denoise, the light nodes, the tone of the shot, the tail. */
    private void buildNodes() {
        if (shotParams.scamRgb == null)
            throw new IllegalStateException("no merge route: the photo pipeline takes the RGB of Hybrid or SCAM HDR");
        Log.i(LOG_ROUTE,"route="+(PreferenceKeys.isHybridShot()?"SCAM_hybrid":"SCAM_HDR")
                +" import=SCAM_linear_RGB WB_LSC=SCAMERA TCE=not_connected"
                +" denoise="+DenoiseStages.summary()
                +" tone=WHY (WhyStats/WhyFusion/WhyCombine)"
                +"; actual node order/timings follow in Pipeline log");
        add(new ScamRgb()); // P129: SHDR recovers the highlights per channel here
        add(new ScamHdrDenoise());
        // P480: the reference's highlight chroma dezipper right after its resolve, only with the worker's base highlight mask
        final ScamHybridBurst.HlMask hlMask = ScamHybridBurst.lastHlMask;
        if (hlMask != null) add(new WhyHlDezipper(hlMask));
        // P402 «Пирамидальный шумодав» (the reference app's ms denoise): only when switched on (no node otherwise)
        if (DenoiseStages.on()) add(new WhyMsDenoise());
        final boolean reference = referenceEngine(film);
        addLightNodes(reference);
        // the WhyCam 1.23 photo tone has its own AE; LinearExposure only keeps the Ultra HDR linear snapshot
        if (captureDemosaic) add(new LinearExposure());
        if (addOldstyleTone()) return;
        // P614 «Why тон» V2 (pref_scam_hybrid_tone_version "v2", of the press): the reference tone instead of V1's, with our
        // sharpening between them; a film of the old route (not the reference film engine) and grids above 100 MP keep V1
        final Point grid = shotParams.rawSize;
        final boolean v2 = whyToneV2(look, film, reference, grid == null ? 0L : (long) grid.x * grid.y);
        if (!v2 && (look != null ? look.toneV2 : PreferenceKeys.whyToneV2()))
            Log.i(LOG_ROUTE, "WHY tone V2 chosen, V1 used (" + (film != null && !reference ? "film of the old route" : "above 100 MP") + ")");
        for (Node n : v2 ? toneV2() : toneV1()) {
            add(n);
            if (reference) referenceFilmTone.add(n);
        }
        if (v2) Log.i(LOG_ROUTE, "WHY tone V2 (WhyV2Stats/WhyLumaSharpen/WhyV2Highlights/WhyV2Fusion/WhyV2Tone)");
        if (reference) add(new ReferenceFilmMix());
        tail(film, reference);
    }

    /**
     * P228: the lens filter of the press (owner, 2026-10-28: not of the settings after the merge) on the linear RGB, before the
     * tone; P281 / P380 the film's light nodes; with the reference engine the film's own effects run around ReferenceFilm and
     * are skipped once it applied.
     */
    private void addLightNodes(boolean reference) {
        final LensFilter.Choice shotFilter = lensFilterOfShot();
        final LensFilter.Choice settingsNow = lensFilter != null ? LensFilter.Choice.now() : shotFilter;
        if (!settingsNow.toString().equals(shotFilter.toString()))
            Log.i(LOG_ROUTE, "lens filter of the press: " + shotFilter + " (the settings now: " + settingsNow + ")");
        if (!reference) {
            for (Node n : lightNodes(shotFilter, film)) add(n);
            return;
        }
        for (Node n : lightNodes(shotFilter, null)) add(n);
        add(new ReferenceFilm());
        for (Node n : filmLightNodes(film)) {
            referenceFilmEffects.add(n);
            add(n);
        }
    }

    /**
     * P630 «Oldstyle»: the mode's tone (V1 2.7 / V2 3.1) in place of «Why тон», then its style, and the tail without a film;
     * false (nothing added) without the mode or with a film simulation, which keeps the Why tone its print is built on.
     */
    private boolean addOldstyleTone() {
        final ShotLook.Oldstyle oldstyle = look != null ? look.oldstyle : ShotLook.Oldstyle.now();
        if (oldstyle == null) return false;
        if (film != null) {
            Log.i(LOG_ROUTE, "Oldstyle mode with a film simulation: the Why tone used");
            return false;
        }
        add(new OldstyleTone(oldstyle.v2, oldstyle.params, oldstyle.postview));
        if (!oldstyle.style.neutralAfterTone()) add(new OldstyleStyleNode(oldstyle.style));
        Log.i(LOG_ROUTE, "tone Oldstyle " + (oldstyle.v2 ? "V2" : "V1") + " style " + oldstyle.style);
        tail(null, false);
        return true;
    }

    /**
     * «Why тон» V1: WhyStats, WhyFusion, WhyLumaSharpen (the only sharpening: WhyCam's own before the detail delta; P118), WhyCombine.
     * W1.6: in the G_CLEAN domain the sharpening needs nothing of the AE, so WhyStats issues its GPU passes before the CPU
     * Smart-HDR statistics (WhyLumaSharpen then only passes the image on).
     */
    private static Node[] toneV1() {
        final WhyStats stats = new WhyStats();
        final WhyFusion fusion = new WhyFusion();
        final WhyLumaSharpen sharpen = new WhyLumaSharpen();
        stats.earlySharpen = sharpen;
        return new Node[]{stats, fusion, sharpen, new WhyCombine()};
    }

    /** P614 «Why тон» V2: the reference tone's stats, highlights, fusion and tone, our sharpening after the stats. */
    private static Node[] toneV2() {
        return new Node[]{new WhyV2Stats(), new WhyLumaSharpen(), new WhyV2Highlights(), new WhyV2Fusion(), new WhyV2Tone()};
    }

    /**
     * P614: whether this shot takes «Why тон» V2 - the press's choice ({@code look}, ShotLook.toneV2; the settings now without
     * one), never with a film of the old route ({@code film} set and not {@code referenceFilm}: its scene feed is V1's) nor on a
     * grid above {@link #V2_MAX_PIXELS} ({@code pixels}; 0 unknown): V2 keeps the clean RGB at full size beside the image (8 B a
     * pixel, 1.6 GB at 200 MP), which V1's 200 MP budget (P187) has no room for.
     */
    static boolean whyToneV2(@Nullable ShotLook look, @Nullable FilmSim.Settings film, boolean referenceFilm, long pixels) {
        if (pixels > V2_MAX_PIXELS || film != null && !referenceFilm) return false;
        return look != null ? look.toneV2 : PreferenceKeys.whyToneV2();
    }

    /** The nodes after the tone: texture (on request only), the final size, the film's sharpness and grain, the rotation. */
    private void tail(@Nullable FilmSim.Settings shotFilm, boolean reference) {
        final boolean texture = PreferenceKeys.hybridSwitch("why_texture", false);
        Log.i(LOG_ROUTE, "WHY tail: sharpen=why (WhyLumaSharpen) texture=" + texture
                + (texture && shotFilm != null ? " (off: film simulation)" : ""));
        if (wantsLocalContrast(texture, shotFilm)) add(new ScamLocalContrast());
        if (finalSize != null) add(new HybridFinalResize(finalSize, PreferenceKeys.hybridDownsampler()));
        final FilmGrain grain = FilmGrain.from(shotFilm);
        if (grain != null) {
            if (reference) referenceFilmEffects.add(grain);
            add(grain);
        }
        add(new RotateWatermark(getRotation()));
    }
}
