package com.scamera.app.processing.render;

import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.os.Build;

import com.scamera.app.settings.annotations.SensorConfig;
import com.scamera.app.settings.annotations.Tunable;
import com.scamera.app.util.Log;
import android.util.Rational;
import android.util.SizeF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.parameters.ExposureIndex;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.util.Allocator;

import java.util.Arrays;


public class Parameters {
    /** Frozen for the submitted RAW processing job. */
    public boolean scamHdrMode = false;
    public float scamHdrRawScale = 1f;
    /** Pixel scale of the merged RGB against the sensor grid (SCAM HDR hybrid on the Sabre 2x grid: 0.98..2). */
    public float outputScale = 1f;
    /** SCAM HDR hybrid 2x grid: final image size (sensor orientation) produced by the GPU resize node at the end of the pipeline; null = no resize. */
    public android.graphics.Point hybridFinalSize;
    /** Processor-owned linear sensor RGB; null unless SCAM completed a real burst. */
    public java.nio.ByteBuffer scamRgb;
    /** scamRgb is an Allocator buffer the pipeline may free as soon as it is uploaded (replaced by a decimated copy). */
    public boolean scamRgbOwned;
    /**
     * P190: frames of a FULLRES series the merge keeps whole (an explicit «Кадров в снимке 50 / 200 Мп», every 200 MP shot), 0 for
     * any other shot; the frames the shot asked for (0: not FULLRES).
     */
    public int fullResSeries, fullResRequested;
    /** P320: the frames come from a DCG sensor mode (one exposure, high- and low-gain reads fused): see ScamProcessor.dcgLinearity. */
    public boolean dcgShot;
    /**
     * P679: a LOFIC shot whose pairs were merged before the burst merge (processing.LoficMerge): the frames' codes hold 2^loficLog2
     * times the Long range, the Long clip at 1 / 2^loficLog2 of white; 0 for every other shot.
     */
    public int loficLog2;
    /**
     * P320: how many times the ISO a DCG mode reports exceeds the gain its photons saw: the stream's range against the declared
     * one when the white is a measured clip (X300 Ultra DCG: (8694 - 1030.75) / (1023 - 64) = 8, the 1336-for-167 ISO of the
     * owner's log, HybridPlan P163); 1 = not a DCG shot or not known. ISO-keyed noise and night rules divide by it.
     */
    public float dcgIsoScale = 1f;
    /**
     * P701: a YUV merge shot (processing/yuv/YuvBurst; 0 for every other shot): the WHY AE target's factor that meters the scene before
     * the YUV route's shadow toe (its geometric mean after / before), and the shot keeps the day target (WhyTone.yuvExposure).
     */
    public float yuvAeScale;
    /**
     * P320: the DCG shot's noise the merge measured on its own frames (worker «HYBRID NOISE CHECK DCG»), normalised units:
     * {slope, high-gain offset, low-gain offset, knee}; var = slope x + (x < knee ? hcg : lcg). Null: not measured.
     */
    public float[] dcgNoise;
    private static final String TAG = "Parameters";
    private int analogIso;
    public int iso;
    /** P501: the shot's post-RAW gain of the camera (RawBoost: 1 = none). */
    public float postRawBoost = 1f;
    public int multiFrameCount; // Actual equal-exposure RAWs merged by the native backend.
    public double exposureTime = 1.0/30.0; // Default to 1/30s if not available
    public byte cfaPattern;
    /** Physical 2x2 CFA reported by Camera2 (cfaPattern after a remosaic is the order it wrote). */
    public byte baseCfaPattern;
    // Per-capture policy, not a live settings lookup during asynchronous processing.
    /** Per-shot display correction; never changes sensor exposure, VST or Bayer DNG. */
    public Point rawSize;
    /** P306: the whole shot's size when these are a lab fragment's (null otherwise): the 200 MP denoise is decided on it. */
    public Point labWholeSize;
    /**
     * P378 review: a lab fragment's origin in the whole shot, a share of its width and height (null otherwise): WhySky takes the
     * whole frame's sky cells (LabFrame) at the fragment's place.
     */
    public android.graphics.PointF labOrigin;
    /**
     * P369: the share of the pipeline image's long side the saved photo keeps (the digital zoom's crop, the ISZ 4:3 crop: both
     * after the pipeline), 1 = all of it - the frame the film's grain, scatter and halation are sized for (FilmHalation.filmLongPx).
     * Set by ScamProcessor per shot, before the lab's snapshot: a lab render of the (uncropped) field sizes the effects as the
     * photo had them; a lab shot of an older build has none (FilmHalation reads 0 as 1).
     */
    public float filmFrameScale = 1f;
    /**
     * P380 (owner, 2026-10-28: the pair with Unspektrawesome's SPEKTRA - its JPEG carries its recipe, ours had none): the shot's
     * film recipe (FilmSim.Settings.recipe of the film snapshot), set by ScamProcessor after PostPipeline.takeFilm; null without a
     * film (the description then as before).
     */
    @Nullable
    public String filmRecipe;
    /** P27: linear factor the RAW frames were binned by (RawBin) before the merge; 1 = as captured. Set before fillFromCamera. */
    public int rawBinning = 1;
    /**
     * P162: the part of the frames RawCrop kept (in the frame as captured, {@link #rawCropFull} in size), null when they are
     * whole: the lens shading map and the hot pixels follow it. Set before fillFromFrame.
     */
    public com.scamera.app.processing.RawCrop.Area rawCrop;
    public Point rawCropFull;
    /**
     * P230: rows the kept part sits lower on the sensor than in the buffer (RawCrop.Shot.sensorShiftY: a sensor mode's centred
     * window written to the buffer's top, Xiaomi 15 Ultra DCG +256): the lens shading map over the active array is read there.
     */
    public int rawCropSensorShiftY;
    /**
     * P576: the crop shot's window (SabreCrop): the frames were cut to it before the merge; null for the whole frame. Set by
     * {@link #applySabreCrop} after the levels and the colour were measured on the whole frames.
     */
    public com.scamera.app.processing.processor.SabreCrop.Plan sabreCrop;
    public boolean portraitEnabled, portraitSkinWarmth, portraitFaceDetected;
    /** Face boxes in the cropped linear RGB, normalized and in sensor orientation. */
    public float[] portraitFaces = new float[0];
    public transient com.scamera.app.processing.opengl.postpipeline.ShotLook portraitLookAtPress;
    /** P165: BaselineExposure of the DNG (EV; 0 = none): set for the merged DNG written over its headroom. */
    public float dngBaselineExposure;
    /**
     * P214c: NoiseProfile of the DNG being written ({S_R, O_R, S_G, O_G, S_B, O_B}, DngMetadata.noiseProfile), set by
     * ScamProcessor around its DNG; null = the noise modeler's computeModel as before.
     */
    public double[] dngNoiseProfile;
    /**
     * P318: colour block of the mosaic the DNG being written holds (2 Quad, 4 Tetra; 1 Bayer), set by ScamProcessor around its
     * DNG: the file declares its CFA, black level and GainMap over the blocks (DngCreator.setMosaicBlock).
     */
    public int dngMosaicBlock = 1;
    /** P618: the DNG being written holds the merged burst (else one frame as the sensor gave it), set by ScamProcessor around it. */
    public boolean dngMerged;
    /**
     * P618: the shot's wall clock (ms since 1970: the moment ImageSaver named its files), the time both its DNG and its photo
     * record; 0 = unknown (the writer's own clock).
     */
    public long captureTimeMs;
    /** P618: the camera's declared lens (LENS_INFO_AVAILABLE_FOCAL_LENGTHS / _APERTURES, LENS_FACING); null = not declared. */
    public float[] lensFocalLengths, lensApertures;
    public Integer lensFacing;
    /**
     * P618: {@link #focalLength} / {@link #aperture} are the shot's (its result or request) - not the camera's declared lens nor
     * the 4.75 mm / f/1.8 placeholders, which stay for the processing that reads them but are never written as the lens.
     */
    public boolean focalLengthOfShot, apertureOfShot;
    /**
     * P618: the field of view of the DNG and of the photo as a zoom of the lens's whole field (1: all of it, 2: half its width),
     * for FocalLengthIn35mmFilm; 0 = unknown (an ISZ / native-mosaic frame: the sensor's crop is not in the metadata), no tag.
     */
    public float dngFieldZoom, photoFieldZoom;
    /** P618: the photo is the 4:3 crop of the frame (ImageSaver.iszAspect4x3): the DNG declares it as its DefaultCrop. */
    public boolean photoAspect4x3;
    /** The black came from the frame's dynamic tag (RawLevels.Levels.dynamicBlack). */
    public boolean dynamicBlackUsed = false;
    /** RAW black per CFA site (R, Gr, Gb, B order of the stream's 2x2), and the camera neutral (AsShotNeutral) of the shot. */
    public float[] cfaBlack = new float[4];
    public float[] asShotNeutral = new float[3];
    /** Correlated colour temperature of the scene light (K): the DNG estimate, or the neutral-based one on the OPPO HAL. */
    public float sceneCct = 6504f;
    /** P370 (B1): where {@link #sceneCct} came from (log, README), and the Duv of the DNG estimate (NaN: not computed). */
    public String sceneCctSource = "";
    public float sceneDuv = Float.NaN;
    /**
     * P370 (B1): the route that rendered the shot's colour, one of {@link ColourSource} by name ("" before resolveColour), and why
     * when it is a fallback.
     */
    public String colourSource = "";
    public String colourSourceWhy = "";
    /** P370 (B1): the colour routes resolveColour can take (README colourSource=). */
    public enum ColourSource {
        /** the active DCP profile */ DCP,
        /** P408: a colour matrix profile of the reference app's registry (CcmPresets) */ CCM_PRESET,
        /** P408: the colour matrix set by hand */ CCM_OVERRIDE,
        /** the OPPO PHY110 tuned forward matrix per CCT */ OPPO_TUNED,
        /** the camera's plain-session matrix at the neutral (PlainColourMemory) */ PLAIN_TABLE,
        /** the result's COLOR_CORRECTION_TRANSFORM (rows normalised) */ ISP_CST,
        /** the result's transform because the DNG forward matrices are the sRGB -> XYZ placeholder */ ISP_CST_PLACEHOLDER,
        /** the legacy FM1 == FM2 / «Источник цвета» «Захват» branch */ LEGACY_CAPTURED,
        /** the frame's com.oplus.custom.ccm in place of an identity transform */ OPLUS_CCM,
        /** the DNG forward matrices interpolated at the neutral */ DNG_FM,
        /** the DNG colour matrices alone (inverse, Bradford to D50) */ DNG_CM_ONLY,
        /** placeholder forward matrices and nothing better: camera RGB taken as sRGB */ IDENTITY_PLACEHOLDER,
        /** the colour metadata could not be used: the white balance alone */ IDENTITY_FALLBACK
    }
    /** P370 (B1): timestamp, ISO and the Qualcomm AWB label (K, 0: none) of the result that supplied the colour. */
    public long colourFrameTimestamp;
    public int colourFrameIso, awbLabelK;
    /** P370 (B1): that result's COLOR_CORRECTION_TRANSFORM as reported (row-major, null: none). */
    public float[] resultCst;
    /** P370 (B1): the camera's DNG colour tags as rationals and the placeholder flags (README). */
    public String colourTags = "";
    public int whiteLevel = 1023;
    /** P127: bit depth of the shot's RAW stream (RawLevels) and where its black / white came from. */
    public int levelBits;
    public String levelSource = "";
    /** P135: SNR of the finish input the denoise measured (ScamDenoise), NaN until it ran: the sharpening backs off by it. */
    public float finishSnr = Float.NaN;
    /** P648c: the finish input's measured luma noise, variance finishNoiseS y + finishNoiseO (DenoiseStats.finishSnrEnd); NaN until then. */
    public float finishNoiseS = Float.NaN, finishNoiseO = Float.NaN;
    /**
     * P214e: colour block of the image the merge returned (ScamHybridBurst.mergedBlock): 2 Quad / 4 Tetra when the worker
     * merged a mosaic stream on its mosaic (the ISZ modules, FULLRES native mosaic), 1 otherwise (a Bayer stream, a mosaic
     * binned into plain Bayer, a block nobody measured, every shot that is not a hybrid merge). Above 1 WhyLumaSharpen
     * sharpens the 3x3-smoothed luma (R1).
     */
    public int mosaicBlock = 1;
    /** The frame's lens shading map is real (not the flat placeholder) and the development applies it. */
    public boolean shadingMapUsed;
    /** The OPPO PHY110 tuned ISP matrix (bright-scene group) rendered the shot instead of the DNG matrices. */
    public boolean oppoTunedCcm;
    /** Lens shading: the grid (columns x rows) and its gains, four per cell; flat 1x1 when the frame has none. */
    public Point shadingGrid;
    public float[] shadingMap;
    /** The active array in the frame's pixels. */
    public Rect activeArea;
    /** Output matrices: ProPhoto to sRGB and the camera's RGB to ProPhoto (row-major 3x3). */
    public float[] proPhotoToSrgb = new float[9];
    public float[] cameraToProPhoto = new float[9];
    /**
     * P243: the colour tags of the DNG being written when they carry the JPEG's own colour (DngColour.jpegProfile: ColorMatrix1,
     * ForwardMatrix1, one illuminant), set by ScamProcessor around its DNG writes; null: the camera's dual profile (DngCreator).
     */
    public com.scamera.app.processing.color.DngColour.Profile dngColour;
    /** 0..1 user control for retaining short-frame highlight headroom in SDR tone mapping. */
    public float highlightSuppressionStrength = 1.0f;
    /** Hot pixels of the frame (STATISTICS_HOT_PIXEL_MAP, in the kept part's coordinates). */
    public Point[] hotSites;
    /** The shot's optics: focal length (mm) and f-number, the placeholders 4.75 mm / f/1.8 when unknown. */
    public float focalMm;
    public float fNumber;
    /** Degrees the saved photo is turned by. */
    public int rotationDeg;
    public NoiseModeler noiseModel;
    public double effectiveStackSamples=Double.NaN;
    public ColorCorrectionTransform colorTransform;
    /** SENSOR_INFO_PHYSICAL_SIZE, mm. */
    public SizeF sensorSizeMm;
    /** Per-channel tone curves of a lab shot (null rows: none). */
    public float[][] toneCurves = new float[3][];

    /** The camera's DNG colour tags: reference illuminants (EXIF light source codes, -1: none) and the 3x3 matrices. */
    public int refIlluminant1 = -1, refIlluminant2 = -1;
    public float[] cameraCalibration1 = new float[9], cameraCalibration2 = new float[9];
    public float[] forwardMatrix1 = new float[9], forwardMatrix2 = new float[9];
    public float[] colorMatrix1 = new float[9], colorMatrix2 = new float[9];

    /** A front camera's photo is mirrored (unless «Disable front mirror»). */
    public boolean mirrored = false;
    public String cameraID = ScameraApp.getSettings().cameraId;
    public int physicalID = 0;
    public int logicalID = 0;
    /**
     * The shot's camera (Settings.mCameraID at its press, ImageSaver.cameraIdAtPress; null: the one open now) and the
     * characteristics it was captured with (fillFromCamera'): a shot queued behind a merge is processed after a lens switch
     * replaced the open camera (CaptureController.mCameraCharacteristics) - its levels, matrices and module settings stay its
     * own. Transient: not in a lab shot's parameters.
     */
    public transient String cameraIdAtPress;
    transient CameraCharacteristics shotCharacteristics;

    /** The characteristics the shot was captured with (null: none given). */
    public CameraCharacteristics shotCharacteristics() {
        return shotCharacteristics;
    }

    /** The shot's camera characteristics (the open camera's when none were given). */
    public CameraCharacteristics camera() {
        return shotCharacteristics != null ? shotCharacteristics : CaptureController.mCameraCharacteristics;
    }

    /**
     * The static keys the development reads from {@code shot} (the characteristics a shot was captured with: the levels, the colour
     * matrices, the noise model's analogue limit) whose values differ from {@code open}'s (the camera open at its processing,
     * which they were read from before), as "key shot=... open=..."; "" when all agree, null for the same object or a missing one.
     * A FULLRES shot merged in the plain session has the SDK session's object, the open one is re-read without the lease: where
     * they agree, the photo is what it was.
     */
    public static String shotCameraDiff(CameraCharacteristics shot, CameraCharacteristics open) {
        if (shot == null || open == null || shot == open) return null;
        final CameraCharacteristics.Key<?>[] keys = {
                CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL, CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN,
                CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1, CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2,
                CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1, CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2,
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM1, CameraCharacteristics.SENSOR_COLOR_TRANSFORM2,
                CameraCharacteristics.SENSOR_FORWARD_MATRIX1, CameraCharacteristics.SENSOR_FORWARD_MATRIX2,
                CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY};
        final StringBuilder out = new StringBuilder();
        for (CameraCharacteristics.Key<?> key : keys) {
            final Object a, b;
            try {
                a = shot.get(key);
                b = open.get(key);
            } catch (RuntimeException e) {
                continue;
            }
            if (java.util.Objects.equals(a, b)) continue;
            if (out.length() > 0) out.append("; ");
            out.append(key.getName()).append(" shot=").append(a).append(" open=").append(b);
        }
        return out.toString();
    }

    @Tunable(title = "Use Dynamic Black Level", category = "Parameters", defaultValue = 0, min = 0, max = 1, step = 1,
            description = "Уровень чёрного из метаданных каждого кадра (что сообщает камера), а не из её паспорта. Оценка по пикселям — «Уровень чёрного по кадру». На части телефонов работает нестабильно."
    )
    boolean useDynamicBlackLevel;

    @Tunable(title = "Use Dynamic White Level", category = "Parameters", defaultValue = 1, min = 0, max = 1, step = 1,
            description = "Уровень белого из метаданных каждого кадра (что сообщает камера), а не из её паспорта. На части телефонов работает нестабильно."
    )
    boolean useDynamicWhiteLevel;

    @SensorConfig(title = "White Level Override", min = -1, max = 65535, step = 1, defaultValue = -1,
            description = "Белый уровень RAW вручную, в единицах RAW этого модуля; Авто — от камеры и по отсчётам кадра. Больше — снимок темнее; меньше — света выгорают раньше.")
    int whiteLevelOverride;
    /** P320: the camera's declared white of this frame (the session's measured clip belongs to that camera only). */
    int declaredWhite;
    /** P320: the module's hand-set levels are in effect (never on a DCG frame: they are the plain mode's). */
    boolean whiteOverride() { return whiteLevelOverride >= 0 && !dcgShot; }
    boolean blackOverride() { return blackLevelOverride >= 0 && !dcgShot; }

    @Tunable(title = "Disable front mirror", category = "Parameters", defaultValue = 0, min = 0, max = 1, step = 1,
            description = "Фото с фронтальной камеры сохраняются без зеркального отражения, как видит камера.")
    boolean disableMirror;

    @SensorConfig(title = "Black Level Override",
            description = "Чёрный уровень RAW вручную, в единицах RAW этого модуля; Авто — от камеры, ручное значение выключает «Уровень чёрного по кадру». Больше — тени темнее и проваливаются; меньше — тени серые.",
            min = -1.0f, max = 8192.0f, step = 1.0f, defaultValue = -1.0f
    )
    float blackLevelOverride;

    public void fillFromCamera(CameraCharacteristics characteristics, Point size) {
        com.scamera.app.settings.TunableInjector.inject(this);
        shotCharacteristics = characteristics;
        cameraID = cameraIdAtPress != null ? cameraIdAtPress : ScameraApp.getSettings().cameraId;
        // "logical-physical" for a lens of a logical camera, else one id for both
        final int dash = cameraID.indexOf('-');
        logicalID = Integer.parseInt(dash >= 0 ? cameraID.substring(0, dash) : cameraID);
        physicalID = Integer.parseInt(dash >= 0 ? cameraID.substring(dash + 1).split("-")[0] : cameraID);
        com.scamera.app.settings.SensorConfigInjector.fillForModule(String.valueOf(physicalID), this);

        rawSize = size;
        final Integer maxAnalogue = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        analogIso = maxAnalogue != null ? maxAnalogue : 100;
        java.util.Arrays.fill(cfaBlack, 0, 4, 64);
        final Integer arrangement = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (arrangement != null) cfaPattern = (byte) (int) arrangement;
        final int forcedCfa = ScameraApp.getSettings().forcedCfa;
        if (forcedCfa >= 0) cfaPattern = (byte) forcedCfa;
        baseCfaPattern = cfaPattern >= 0 && cfaPattern <= 3 ? cfaPattern : 0;

        // P618: the lens as the camera declares it (the EXIF lens tags); the optics below fall back to 4.75 mm, f/1.8
        lensFocalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        lensApertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
        focalLengthOfShot = apertureOfShot = false;
        final float focal = lensFocalLengths != null && lensFocalLengths.length > 0 ? lensFocalLengths[0] : 4.75f;
        sensorSizeMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        focalMm = focal;
        fNumber = lensApertures != null && lensApertures.length > 0 ? lensApertures[0] : 1.8f;
        Log.d(TAG, "lens: " + focalMm + " mm, f/" + fNumber + ", sensor " + sensorSizeMm + " mm");

        final Integer white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (white != null) whiteLevel = white;
        flatShading();
        activeArea = activeArray(characteristics);
        if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) mirrored = true;
        if (disableMirror) mirrored = false;
    }

    /**
     * The active array in the frame's pixels: divided by the RAW binning; P108: a full-resolution RAW (Pixel 10 Pro / Pro XL:
     * 8160x6144 against the binned 4080x3072 array) by the max-resolution array, or the whole frame.
     */
    private Rect activeArray(CameraCharacteristics characteristics) {
        Rect area = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (area == null) area = new Rect(0, 0, rawSize.x, rawSize.y);
        else if (rawBinning > 1)
            area = new Rect(area.left / rawBinning, area.top / rawBinning, area.right / rawBinning, area.bottom / rawBinning);
        if (rawSize.x > area.width() * 3 / 2 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            final Rect max = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION);
            area = max != null && max.width() <= rawSize.x && max.height() <= rawSize.y ? new Rect(max) : new Rect(0, 0, rawSize.x, rawSize.y);
        }
        return area;
    }

    /**
     * P320: rebuilds the noise model of a DCG shot at ISO / dcgIsoScale (NoiseModeler.withIsoScale: a chosen profile and the
     * no-profile fallback read the gain the photons saw, the HAL profile stays as reported). dcgIsoScale is known only after
     * fillFromFrame (ScamProcessor.dcgLinearity), so this runs right after it is set; a no-op for any other shot, a
     * scale <= 1 or no model, and idempotent (the rebuild always starts from the reported ISO).
     */
    public void applyDcgIsoScale() {
        if (!dcgShot || !(dcgIsoScale > 1f) || noiseModel == null) return;
        noiseModel = noiseModel.withIsoScale(dcgIsoScale);
    }

    private static <T> T orElse(T value, T fallback) {
        return value != null ? value : fallback;
    }

    /** No lens shading correction: one flat cell (gain 1 at the four sites) and the map not applied. */
    private void flatShading() {
        shadingGrid = new Point(1, 1);
        shadingMap = new float[]{1f, 1f, 1f, 1f};
        shadingMapUsed = false;
    }

    /**
     * The frame's lens shading map. A map that is 1.0 at three probe points (an eighth, half and five eighths into it, at a
     * site's first channel) is a placeholder: no gain map; on a Huawei the averaged static map (Const) stands in for it.
     */
    private void takeShadingMap(LensShadingMap lensMap, boolean isHuawei) {
        final float[] gains = new float[lensMap.getGainFactorCount()];
        lensMap.copyGainFactors(gains, 0);
        shadingGrid = new Point(lensMap.getColumnCount(), lensMap.getRowCount());
        shadingMap = gains;
        final int n = gains.length;
        boolean flat = true;
        for (int at : new int[]{n / 8, n / 2, n / 2 + n / 8}) flat &= gains[at - at % 4] == 1.0;
        shadingMapUsed = !flat;
        if (!flat || !isHuawei) return;
        Log.d(TAG, "placeholder lens shading map: the static Huawei map instead");
        shadingMap = FallbackShading.gains();
        shadingGrid = new Point(FallbackShading.COLUMNS, FallbackShading.ROWS);
    }

    public void fillFromFrame(CaptureResult result, CaptureRequest request, int ISO) {
        portraitEnabled = portraitLookAtPress != null ? portraitLookAtPress.portraitEnabled : PreferenceKeys.isPortraitEnabled();
        portraitSkinWarmth = portraitLookAtPress != null ? portraitLookAtPress.portraitSkinWarmth : PreferenceKeys.isPortraitSkinWarmthEnabled();
        portraitFaceDetected = false;
        portraitFaces = new float[0];
        if (portraitEnabled && rawSize != null && result != null) {
            final Point full = rawCropFull != null ? rawCropFull : new Point(rawSize.x * rawBinning, rawSize.y * rawBinning);
            final Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            final com.scamera.app.processing.portrait.PortraitFaces.Result faces =
                    com.scamera.app.processing.portrait.PortraitFaces.fromResult(camera(), result,
                            String.valueOf(physicalID), String.valueOf(logicalID), full.x, full.y, ts == null ? 0L : ts,
                            portraitLookAtPress != null && portraitLookAtPress.portraitSensorWindowUnknown);
            portraitFaceDetected = faces.detected;
            portraitFaces = rawCrop == null ? faces.boxes
                    : com.scamera.app.processing.portrait.PortraitFaces.crop(faces.boxes,
                            rawCrop.x / (float) full.x, (rawCrop.y + rawCropSensorShiftY) / (float) full.y,
                            rawCrop.width / (float) full.x, rawCrop.height / (float) full.y);
        }
        // Selection may change the reference after the first metadata pass.
        // Missing tags must not retain another frame's dynamic calibration.
        dynamicBlackUsed = false;
        shadingMapUsed = false;
        Integer staticWhite = camera().get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (staticWhite != null) whiteLevel = staticWhite;
        declaredWhite = staticWhite == null ? 0 : staticWhite;
        // the frame's own values, else what was asked for, else the caller's ISO / 1/30 s
        final Integer reportedIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        final int sensitivity = reportedIso != null ? reportedIso : orElse(request.get(CaptureRequest.SENSOR_SENSITIVITY), ISO);
        iso = sensitivity;
        postRawBoost = com.scamera.app.processing.opengl.postpipeline.RawBoost.of(result); // P501
        noiseModel = new NoiseModeler(NoiseModeler.sanitizedOffsets(result.get(CaptureResult.SENSOR_NOISE_PROFILE), whiteLevel), analogIso, sensitivity, cfaPattern);
        final Long reportedExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        exposureTime = ExposureIndex.time2sec(reportedExposure != null ? reportedExposure
                : orElse(request.get(CaptureRequest.SENSOR_EXPOSURE_TIME), 1_000_000_000L / 30));

        BlackLevelPattern level = camera().get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (result != null) {
            boolean isHuawei = Build.BRAND.equals("Huawei");

            float[] reportedBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
            Log.i(TAG, "black level static=" + level + " dynamic=" + java.util.Arrays.toString(reportedBlack)
                    + " whiteDynamic=" + result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));
            try {
                shadingGrid = new Point(1, 1);
                shadingMap = new float[]{1f, 1f, 1f, 1f};
                final LensShadingMap lensMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
                if (lensMap == null) applyStoredShading(result);
                else takeShadingMap(lensMap, isHuawei);
            } catch (Exception e){
                Log.d(TAG, "Error retrieving lens shading map, disabling gain map: " + Log.getStackTraceString(e));
            }
            if (shadingMapUsed && rawShadingAlreadyApplied(camera())) {
                // The HAL delivered RAW with lens shading already corrected; applying the
                // map again brightens the edges up to ~5x and lifts their blacks.
                Log.i(TAG, "RAW lens shading already applied by HAL, gain map skipped");
                flatShading();
            }
            // P118 «16:9»: the frames are a centred crop (Wide169) and the map covers the whole frame: its rows of the crop only
            // (P162: of the frame as captured, before RawCrop)
            final com.scamera.app.api.Settings s = ScameraApp.getSettings();
            final Point captured = rawCrop != null && rawCropFull != null ? rawCropFull : rawSize;
            if (shadingMapUsed && shadingGrid.y > 1 && s != null && s.wide169 && captured != null && activeArea != null
                    && captured.x > captured.y && activeArea.width() > 0) {
                final int full = (int) Math.round((double) captured.x * activeArea.height() / activeArea.width());
                if (com.scamera.app.processing.Wide169.rows(captured.x, full) == captured.y)
                    shadingMap = com.scamera.app.processing.Wide169.cropGainMap(shadingMap, shadingGrid.x, shadingGrid.y, full,
                            com.scamera.app.processing.Wide169.firstRow(captured.x, full), captured.y);
                else Log.w(TAG, "16:9 frame " + captured + " is not the crop of " + captured.x + "x" + full + ": lens shading map kept");
            }
            // P162: the map over the kept part of the frame only
            if (shadingMapUsed && rawCrop != null && rawCropFull != null)
                shadingMap = com.scamera.app.processing.RawCrop.cropGainMap(shadingMap, shadingGrid.x, shadingGrid.y,
                        rawCropFull.x, rawCropFull.y, rawCropSensorShiftY == 0 ? rawCrop
                                : new com.scamera.app.processing.RawCrop.Area(rawCrop.x, rawCrop.y + rawCropSensorShiftY,
                                rawCrop.width, rawCrop.height));
            hotSites = result.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP);
            if (rawBinning > 1) hotSites = new Point[0]; // sensor coordinates; a binned site averages it away
            else if (hotSites != null && rawCrop != null) hotSites = keptHotPixels(hotSites, rawCrop);
            resolveColour(false, result);
        }
        frameLevels(result, staticWhite, level, true);
        // the optics of the shot: its result, else its request, else the f/1.8, 4.75 mm placeholders (never written as the lens)
        final Float shotF = shotValue(result, CaptureResult.LENS_APERTURE, request, CaptureRequest.LENS_APERTURE);
        apertureOfShot = shotF != null;
        fNumber = apertureOfShot ? shotF : 1.8f;
        final Float shotFocal = shotValue(result, CaptureResult.LENS_FOCAL_LENGTH, request, CaptureRequest.LENS_FOCAL_LENGTH);
        focalLengthOfShot = shotFocal != null;
        focalMm = focalLengthOfShot ? shotFocal : 4.75f;

        binnedLevels();
    }

    /**
     * P440 (owner, 2026-10-27: «нам нужно иметь как минимум не хуже скорость обработки чем у Why камеры»): the RAW levels of one
     * frame alone - black, white, their bit depth and source, the declared white, the binned scale - exactly as
     * {@link #fillFromFrame} sets them for {@code result}, nothing else and without its log lines. ScamProcessor's first
     * metadata pass (the last burst callback) ran the whole fillFromFrame (lens shading, noise model, colour: resolveColour
     * and ~70 log lines); the reference frame's pass replaced every one of its values before anything read them, except these
     * levels: the darkest frame's black floor is measured at them (refineBlackLevel) and the reference frame's pass reuses that
     * measurement. A null result throws as fillFromFrame' first read of it did.
     */
    public void fillLevelsOnly(CaptureResult result) {
        java.util.Objects.requireNonNull(result, "result");
        final Integer staticWhite = camera().get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (staticWhite != null) whiteLevel = staticWhite;
        declaredWhite = staticWhite == null ? 0 : staticWhite;
        frameLevels(result, staticWhite, camera().get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN), false);
        binnedLevels();
    }

    /**
     * P127: black and white at the bit depth of the stream (RawLevels: declared levels, the frame's dynamic ones in their own
     * units, what the session's samples proved), then the user's overrides. P440: one method for fillFromFrame and
     * fillLevelsOnly; {@code log}: its lines (fillFromFrame only).
     */
    private void frameLevels(@Nullable CaptureResult result, @Nullable Integer staticWhite, @Nullable BlackLevelPattern level,
                             boolean log) {
        final int[] blarr = new int[4];
        if (level != null) level.copyTo(blarr, 0);
        final Integer dynamicWhite = result == null ? null : result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        final com.scamera.app.processing.RawLevels.Levels levels =
                com.scamera.app.processing.RawLevels.forFrame(
                        level == null ? null : new float[]{blarr[0], blarr[1], blarr[2], blarr[3]},
                        staticWhite == null ? 0 : staticWhite,
                        result == null ? null : result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
                        dynamicWhite == null ? 0 : dynamicWhite, useDynamicBlackLevel, useDynamicWhiteLevel);
        System.arraycopy(levels.black, 0, cfaBlack, 0, 4);
        whiteLevel = levels.white;
        dynamicBlackUsed = levels.dynamicBlack;
        levelBits = levels.bits;
        levelSource = levels.source;
        // P320: a module's hand-set levels are the plain mode's; a DCG frame keeps the levels its own data resolved
        if (dcgShot && (whiteLevelOverride >= 0 || blackLevelOverride >= 0)) {
            if (log) Log.i(TAG, "RAW levels: DCG frame, the module's level overrides (white " + whiteLevelOverride + ", black "
                    + blackLevelOverride + ") are the plain mode's and are not applied");
        } else {
            if (whiteLevelOverride >= 0) whiteLevel = whiteLevelOverride;
            if (blackLevelOverride >= 0) {
                for (int i = 0; i < 4; i++) cfaBlack[i] = blackLevelOverride;
            }
        }
        if (log) Log.i(TAG, "RAW levels: " + levels + (whiteLevelOverride >= 0 ? ", white override " + whiteLevelOverride : "")
                + (blackLevelOverride >= 0 ? ", black override " + blackLevelOverride : ""));
    }

    /** The levels of a binned (2x2 summed, Allocator.binning) frame: four times black and white. */
    private void binnedLevels() {
        if (!Allocator.binning) return;
        for (int c = 0; c < cfaBlack.length; c++) cfaBlack[c] = Math.min(4f * cfaBlack[c], 65535f);
        whiteLevel = Math.min(4 * whiteLevel, 65535);
    }

    /** The shot's value of a lens key: its result's, else its request's (null: neither has it). */
    @Nullable
    private static <T> T shotValue(CaptureResult result, CaptureResult.Key<T> resultKey, @Nullable CaptureRequest request,
                                   CaptureRequest.Key<T> requestKey) {
        final T own = result.get(resultKey);
        return own != null || request == null ? own : request.get(requestKey);
    }


    /**
     * Lowers blackLevel when the RAW itself proves it too high. Signal cannot sit below
     * black, so the dark floor of the frame is an upper bound for it. OPPO Find X8 Ultra
     * RAW_SENSOR reports 64 while the data floor drifts to ~60 per frame; subtracting 64
     * clips the weaker R and B channels first and turns the shadows green.
     * Estimate: third-lowest mean of 32x32-sample blocks per CFA channel (one dead column
     * or defect block cannot drive it), common for all channels. Never raises black.
     * buffer: packed 16-bit RAW, rowStride = width*2. A plain Bayer stream (colour block 1).
     */
    public void refineBlackLevel(java.nio.ByteBuffer buffer, int width, int height) {
        refineBlackLevel(buffer, width, height, 1);
    }

    /**
     * {@link #refineBlackLevel(java.nio.ByteBuffer, int, int)} of a stream of colour block {@code block} (2 Quad, 4 Tetra: each
     * CFA colour a block x block square; anything else is read as plain Bayer). P386: the four channels are the sites
     * ((c &amp; 1) x block, (c &gt;&gt; 1) x block) of each 2 block-wide cell - read as plain Bayer, every channel of a Quad
     * stream was the top-left colour's (GRBG: green) and the red and blue floors were never seen.
     */
    public void refineBlackLevel(java.nio.ByteBuffer buffer, int width, int height, int block) {
        refineBlackLevel(buffer, width, height, block, null);
    }

    /**
     * {@link #refineBlackLevel(java.nio.ByteBuffer, int, int, int)} of a frame of the stream {@code stream} (MosaicStream's key
     * of the shot, MosaicBlockStore.shotKey; null, "" or "off": none). P386: a frame of a stream whose pedestal was proven lost
     * (PedestalMemory: an earlier shot's frame or the RAW viewfinder's) takes the stream's floor when it cannot prove the loss
     * itself - no deep shade - and its darkest block means stay below PedestalMemory.BACK x the declared black.
     */
    public void refineBlackLevel(java.nio.ByteBuffer buffer, int width, int height, int block, String stream) {
        if (buffer == null || width < 128 || height < 128) return;
        block = block == 2 || block == 4 ? block : 1;
        final boolean floorOn = !blackOverride() && PreferenceKeys.isRawBlackFromData();
        // Same frame again (SCAM re-reads the reference metadata): reuse what it showed.
        if (buffer == blackFloorSource && block == blackFloorBlock && java.util.Objects.equals(stream, blackFloorStream)) {
            widenToSamples(blackFloorMax, blackFloorLow);
            clipWhiteToPlateau(blackFloorPlateau); // P558
            if (floorOn && blackFloorEstimate != Float.MAX_VALUE) applyBlackFloor(blackFloorEstimate);
            return;
        }
        java.nio.ShortBuffer data = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
        if (data.capacity() < width * height) return;
        final int bs = 32;
        int bw = width / 2 / bs, bh = height / 2 / bs;
        // Padding is not signal: the vivo X200 Pro (MediaTek, owner's log 2026-10-07) delivers its 4096x3072 RAW_SENSOR with
        // the image in 4000x3000 and the rest of the buffer exactly 0. Those blocks drove the floor to 0, black 64 was never
        // subtracted and every photo came out pink (black x WB gains R 2.5 / B 1.4). A sample below a quarter of the reported
        // black is padding or a masked area, never a drift of the black level (the OPPO's drift is 64 -> ~60): a block with
        // one is left out (blocks on the border of the padding hold both).
        float reportedMin = Float.MAX_VALUE;
        for (float b : cfaBlack) reportedMin = Math.min(reportedMin, b);
        final float paddingBelow = reportedMin > 4f ? reportedMin * 0.25f : -1f;
        final int blocks = bw * bh;
        // P386: the level at or below which a floor is the black itself (~3 DN of a 10-bit range), counted per block by the scan
        final float nearZero = com.scamera.app.processing.PedestalMemory.nearZero(whiteLevel);
        final BlockScan scan = BlockScan.of(data, width, bw, bh, paddingBelow, PreferenceKeys.scamDevSwitch("black_scan_bulk", true), block,
                nearZero);
        final float[][] means = scan.means;
        final boolean[][] low = scan.low, zero = scan.zero, hit = scan.hit;
        final int[][] smallest = scan.smallest, near = scan.near;
        final int max2 = scan.max2;
        final float lowest = scan.lowest;
        // P127: the frame's own samples beyond its white: a wider stream than the session had shown (black and white rescaled; the
        // padding test below depends on the black, so the blocks are read again at the new levels)
        final float darkest = lowest == Float.MAX_VALUE ? -1f : lowest;
        if (widenToSamples(max2, darkest)) {
            refineBlackLevel(buffer, width, height, block, stream);
            return;
        }
        blackFloorSource = buffer;
        blackFloorBlock = block;
        blackFloorStream = stream;
        blackFloorMax = max2;
        blackFloorLow = darkest;
        // P558: the white from the frame's clip plateau (at the depth the samples resolved above)
        blackFloorPlateau = clipPlateau(scan.values, scan.counted);
        clipWhiteToPlateau(blackFloorPlateau);
        blackFloorEstimate = Float.MAX_VALUE;
        pedestalGone = false;
        pedestalRemembered = false;
        if (!floorOn) return;
        // P386 (review): a strip of small non-zero values at an edge (padding or a masked area that is not exact zeros) is not the
        // scene: its blocks count neither for the proof of a lost pedestal nor for the floors (else a frame with its pedestal drops
        // to the strip's ~2 DN: the X200 Pro's pink). Zero padding is left out by nearZeroBlock as before.
        final boolean[] strip = paddingBelow > 0 ? edgeStrips(low, means, bw, bh, 2f * paddingBelow) : new boolean[blocks];
        int stripBlocks = 0, padding = 0, hits = 0;
        for (int i = 0; i < blocks; i++) {
            if (strip[i]) { stripBlocks++; continue; }
            for (int c = 0; c < 4; c++) {
                if (low[c][i]) padding++;
                if (hit[c][i]) hits++;
            }
        }
        final int counted = 4 * (blocks - stripBlocks);
        // P114: padding is a strip of the frame (X200 Pro: 4.6 % of it), a noisy night dips below the quarter now and then. When
        // most of the frame holds such samples the HAL delivered the RAW without its black pedestal (OPPO Find X8 Ultra, owner's
        // log 2026-10-13 11:51-12:43 until the phone was restarted: 80-97 % of the blocks, data floor 18-23 DN for a reported 64,
        // the bracket's measured gains +20 % / -20 %, teal shadows): the floor is then measured over every block but the zero
        // padding (blocks of exact zeros and their neighbours), else the darkest blocks of the frame are the ones left out.
        final boolean pedestalLost = paddingBelow > 0 && 2 * padding > counted;
        // The three floors: of the blocks without a sample below the quarter (P114's padding rule), of every block but zero padding
        // and edge strips, and (P386) the smallest non-zero samples of those; and the blocks of those that are dark themselves
        // (mean within 3 x nearZero) with 1 / 16 of their samples at or below nearZero.
        float skipFloor = Float.MAX_VALUE, allFloor = Float.MAX_VALUE, sampleFloor = Float.MAX_VALUE;
        int nearBlocks = 0;
        final int nearQuota = (BlockScan.BS / 4) * (BlockScan.BS / 4) / 16;
        for (int c = 0; c < 4; c++) {
            float s1 = Float.MAX_VALUE, s2 = Float.MAX_VALUE, s3 = Float.MAX_VALUE;
            float a1 = Float.MAX_VALUE, a2 = Float.MAX_VALUE, a3 = Float.MAX_VALUE;
            int m1 = Integer.MAX_VALUE, m2 = Integer.MAX_VALUE, m3 = Integer.MAX_VALUE;
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                final int i = by * bw + bx;
                final float mean = means[c][i];
                if (!low[c][i]) {
                    if (mean < s1) { s3 = s2; s2 = s1; s1 = mean; }
                    else if (mean < s2) { s3 = s2; s2 = mean; }
                    else if (mean < s3) s3 = mean;
                }
                if (strip[i] || nearZeroBlock(zero[c], bw, bh, bx, by)) continue;
                if (mean < a1) { a3 = a2; a2 = a1; a1 = mean; }
                else if (mean < a2) { a3 = a2; a2 = mean; }
                else if (mean < a3) a3 = mean;
                if (near[c][i] >= nearQuota && mean <= 3f * nearZero) nearBlocks++;
                final int m = smallest[c][i];
                if (m <= 0) continue;
                if (m < m1) { m3 = m2; m2 = m1; m1 = m; }
                else if (m < m2) { m3 = m2; m2 = m; }
                else if (m < m3) m3 = m;
            }
            skipFloor = Math.min(skipFloor, s3);
            allFloor = Math.min(allFloor, a3);
            if (m3 != Integer.MAX_VALUE) sampleFloor = Math.min(sampleFloor, m3);
        }
        // P386 (vivo X200 Ultra camera 5, a 4096x3072 GRBG Quad stream, owner's zip 2026-10-28 15:38: the 2.4x photo teal all over):
        // the RAW came without its 64 DN pedestal in daylight. Only a third of the blocks held a sample below 16 DN (the shadows),
        // so P114 took the floor of the rest, 24 DN: every channel lost 24 DN, red (x2.41 white balance) went negative on the brick
        // wall. P315's proof of the viewfinder decides as well: non-zero samples below the quarter in >= 2 % of the blocks (zero
        // padding is exactly 0, read noise around a pedestal and a drift never reach it) - taken only when the darkest block means
        // themselves sit below half the black, which no noise of a 64-sample mean and no drift reach either.
        final boolean belowHalf = paddingBelow > 0 && allFloor < 2f * paddingBelow;
        final boolean proven = pedestalLost || (paddingBelow > 0 && hits >= com.scamera.app.processing.PedestalFloor.SHARE * counted);
        final boolean lost = proven && belowHalf;
        // The black of a RAW proven without its pedestal: the darkest block means are only an upper bound of it (the darkest 64x64 px
        // of a daylight frame still hold light: ~6 DN of red on the shot). The floor of the smallest non-zero samples (~1 DN there)
        // is taken only when it is near zero AND dark blocks hold it - at least 3 blocks whose mean is within 3 x nearZero (~9 DN)
        // with 1 / 16 of their samples at or below nearZero: the noise tail of a black left partly above zero (a high-ISO night,
        // read noise 5 - 15 DN around 20) reaches ~1 DN in a full 4096x3072 frame too, but its blocks sit at ~20 - that black keeps
        // the block means' floor (P386 review: the samples' floor under-subtracted it by 11-16 DN, magenta shadows). A black the
        // rule takes for ~1 is at most the mean of such blocks: ~9 DN.
        final boolean atZero = sampleFloor <= nearZero && nearBlocks >= 3;
        float estimate = lost ? (atZero ? Math.min(allFloor, sampleFloor) : allFloor) : pedestalLost ? allFloor : skipFloor;
        if (lost) Log.w(TAG, "RAW black floor: " + hits + " of " + counted + " blocks hold non-zero samples below " + paddingBelow
                + " DN (" + padding + " any below it), the darkest block means at " + allFloor + " DN, the smallest samples at "
                + (sampleFloor == Float.MAX_VALUE ? "-" : sampleFloor) + " DN (" + nearBlocks + " dark blocks hold samples at or below "
                + nearZero + (atZero ? "" : ": not the black") + "; colour block " + block
                + "): not padding, the RAW came without its black pedestal; measured over every block but zero padding");
        else if (pedestalLost) Log.w(TAG, "RAW black floor: " + padding + " of " + counted + " blocks hold samples below " + paddingBelow
                + " DN: not padding, the RAW came without its black pedestal; measured over every block but zero padding");
        else if (padding > 0) Log.i(TAG, "RAW black floor: " + padding + " of " + counted + " blocks below " + paddingBelow
                + " DN skipped (padding / masked area, not signal)");
        if (stripBlocks > 0) Log.i(TAG, "RAW black floor: " + stripBlocks + " of " + blocks + " block places in edge strips below "
                + paddingBelow + " DN left out (padding / masked area)");
        // P386 (review): a frame of the same stream without deep shade (a sunlit facade, the sky) proves nothing - its floor was 18.8 /
        // 39.6 DN for a true 0 - and lost that much of every channel: the stream's proven floor applies to it (PedestalMemory), while
        // its darkest block means stay below PedestalMemory.BACK x the declared black (no frame with its pedestal sits there); a frame
        // whose darkest block means come back to that clears it (P114: the X8U's pedestal came back after a restart).
        if (estimate != Float.MAX_VALUE && reportedMin > 4f && allFloor != Float.MAX_VALUE
                && com.scamera.app.processing.PedestalMemory.storable(stream)
                && PreferenceKeys.scamDevSwitch("pedestal_memory", true)) {
            final float known = com.scamera.app.processing.PedestalMemory.floor(stream, whiteLevel);
            if (allFloor >= com.scamera.app.processing.PedestalMemory.BACK * reportedMin) {
                com.scamera.app.processing.PedestalMemory.clear(stream, "the photo's darkest block means at " + allFloor
                        + " DN of a declared " + reportedMin);
            } else if (known < estimate && !(lost && atZero)) {
                Log.w(TAG, "RAW black floor: the stream " + stream + " came without its black pedestal ("
                        + com.scamera.app.processing.PedestalMemory.source(stream) + "): black " + known
                        + " DN instead of the frame's own " + estimate + " (its darkest block means at " + allFloor
                        + " DN: no deep shade proves it here)");
                estimate = known;
                pedestalRemembered = true;
            } else if (lost) {
                com.scamera.app.processing.PedestalMemory.remember(stream, estimate, whiteLevel, "photo");
            }
        }
        pedestalGone = lost || pedestalRemembered;
        if (estimate == Float.MAX_VALUE) return;
        blackFloorEstimate = estimate;
        applyBlackFloor(estimate);
    }

    /**
     * P558: the value the frame clips on, 0 when its samples show no clip - RawPayloadCheck.clipPlateau's rule on a value histogram:
     * at least 0.2 % of them (16 at least) within 0.1 % (2 DN at least) of the top value (the 4th largest sample: a few hot pixels
     * above a clip are no plateau), and the band of three times that width under it holding fewer than half as many. A blown area
     * piles up on one value; bright content, however flat, spreads with its noise.
     */
    static int clipPlateau(int[] values, long n) {
        if (values == null || n < 16) return 0;
        int top = 0, seen = 0;
        for (int v = values.length - 1; v > 0; v--) {
            seen += values[v];
            if (seen >= 4) { top = v; break; }
        }
        if (top <= 0) return 0;
        final int tol = Math.max(2, top / 1000);
        long at = 0, below = 0;
        for (int v = Math.max(0, top - 4 * tol); v <= Math.min(values.length - 1, top + tol); v++) {
            if (v >= top - tol) at += values[v];
            else below += values[v];
        }
        return at >= Math.max(16, n / 500) && 2 * below < at ? top : 0;
    }

    /**
     * P558 (owner, 2026-11-08: «автоматическое определение уровня чёрного и белого ... на всех смартах»): a stream clipping under its
     * declared white (a HAL clamp, a full well under the converter's range) left its blown areas short of 1: they were not flagged
     * clipped and the white balance turned their equal channels pink. The frame's clip plateau, when under the white by more than
     * its tolerance and above twice the black, is the shot's white (the user's override kept; scam_dev.txt "raw_white_plateau 0":
     * as before). True when changed.
     */
    boolean clipWhiteToPlateau(int plateau) {
        if (whiteOverride() || plateau <= 0 || !PreferenceKeys.scamDevSwitch("raw_white_plateau", true)) return false;
        float black = 0f;
        for (float b : cfaBlack) black = Math.max(black, b);
        final int tol = Math.max(2, whiteLevel / 1000);
        if (plateau >= whiteLevel - tol || plateau <= 2f * black) return false;
        Log.i(TAG, "RAW white level from data: the frame clips at " + plateau + " under the white " + whiteLevel + " (P558)");
        whiteLevel = plateau;
        levelSource += ", white = the frame's clip " + plateau;
        return true;
    }

    /** P386: no edge strip is wider than this share of its side (as RawCrop.MAX_BAND_DIVISOR). */
    static final int STRIP_DIVISOR = 8;

    /**
     * P386 (review): the block places of the frame's edge strips - whole block rows / columns from an edge in which every block of
     * every channel holds a sample below the quarter of the black ({@code low}), at most 1 / STRIP_DIVISOR of the side, followed
     * inward by a row / column whose block means all reach {@code clear} (half the black): a padded or masked strip, which ends on a
     * pixel row / column. A dark part of the scene at an edge (a shaded foreground, a night sky) neither ends straight nor is
     * followed by a line lit above half the black in every colour, so its blocks still prove a lost pedestal.
     */
    static boolean[] edgeStrips(boolean[][] low, float[][] means, int bw, int bh, float clear) {
        final boolean[] strip = new boolean[bw * bh];
        for (int side = 0; side < 4; side++) {
            final int lines = side < 2 ? bh : bw, along = side < 2 ? bw : bh;
            final int max = Math.max(1, lines / STRIP_DIVISOR);
            int k = 0;
            while (k <= max && k < lines && stripLine(low, means, bw, bh, side, k, along, clear, true)) k++;
            if (k == 0 || k > max || k >= lines || !stripLine(low, means, bw, bh, side, k, along, clear, false)) continue;
            for (int j = 0; j < k; j++) for (int a = 0; a < along; a++) strip[stripIndex(bw, bh, side, j, a)] = true;
        }
        return strip;
    }

    /** {@code low}: every block of the line low in every channel; else: every block mean of the line at or above {@code clear}. */
    private static boolean stripLine(boolean[][] low, float[][] means, int bw, int bh, int side, int line, int along, float clear,
                                     boolean lowLine) {
        for (int c = 0; c < 4; c++) for (int a = 0; a < along; a++) {
            final int i = stripIndex(bw, bh, side, line, a);
            if (lowLine ? !low[c][i] : means[c][i] < clear) return false;
        }
        return true;
    }

    /** Block index of place {@code a} on the {@code line}-th row / column from edge {@code side} (0 top, 1 bottom, 2 left, 3 right). */
    private static int stripIndex(int bw, int bh, int side, int line, int a) {
        switch (side) {
            case 0: return line * bw + a;
            case 1: return (bh - 1 - line) * bw + a;
            case 2: return a * bw + line;
            default: return a * bw + (bw - 1 - line);
        }
    }

    /**
     * The 32x32-sample blocks of {@link #refineBlackLevel} per CFA channel: every 4th sample of a block both ways (64 per block,
     * the block mean well below 1 DN of noise at 1/16 of the full-frame read cost). P371 (speed): {@code bulk} copies each sampled
     * row of a block row into an array first - the 786 k single reads through the frame's ShortBuffer view took ~20 ms of the
     * shot's Java prep (X8U 41.600 -> 41.620) - and visits the same samples: the same sums, blocks, padding and largest values
     * (none of them depends on the order). scam_dev black_scan_bulk 0: a read per sample, as before.
     * P386: {@code block} 2 / 4 (a Quad / Tetra stream) reads channel c at the sites ((c &amp; 1) x block, (c &gt;&gt; 1) x block) of
     * its 2 block-wide cells - the 8 px sample step is a whole number of cells for blocks 1, 2 and 4, so each sample of a channel is
     * that colour's; block 1 reads exactly the samples it read before. Also counted: the blocks with a non-zero sample below
     * {@code paddingBelow} (hits, P315's proof; {@code hit} per block), the smallest non-zero sample of each block (0: none) and
     * (P386 review) its samples at or below {@code nearZero} (zeros too; none counted when {@code nearZero} is negative).
     */
    static final class BlockScan {
        static final int BS = 32;
        final float[][] means;
        final boolean[][] low, zero, hit;
        final int[][] smallest, near;
        int padding, hits, max, max2;
        float lowest = Float.MAX_VALUE;
        /** P558: the scanned samples by value (the clip plateau, {@link #clipPlateau}) and their count. */
        final int[] values = new int[65536];
        long counted;

        private BlockScan(int blocks) {
            means = new float[4][blocks];
            low = new boolean[4][blocks];
            zero = new boolean[4][blocks];
            hit = new boolean[4][blocks];
            smallest = new int[4][blocks];
            near = new int[4][blocks];
        }

        static BlockScan of(java.nio.ShortBuffer data, int width, int bw, int bh, float paddingBelow, boolean bulk) {
            return of(data, width, bw, bh, paddingBelow, bulk, 1);
        }

        static BlockScan of(java.nio.ShortBuffer data, int width, int bw, int bh, float paddingBelow, boolean bulk, int block) {
            return of(data, width, bw, bh, paddingBelow, bulk, block, -1f);
        }

        static BlockScan of(java.nio.ShortBuffer data, int width, int bw, int bh, float paddingBelow, boolean bulk, int block,
                            float nearZero) {
            final int bs = BS, blocks = bw * bh, b = block == 2 || block == 4 ? block : 1;
            final BlockScan s = new BlockScan(blocks);
            final short[] row = bulk ? new short[bw * bs * 2] : null;
            final java.nio.ShortBuffer view = bulk ? data.duplicate() : null;
            final long[] sums = new long[bw];
            final int[] small = new int[bw], nearCount = new int[bw];
            final boolean[] padded = new boolean[bw], allZero = new boolean[bw], hit = new boolean[bw];
            for (int c = 0; c < 4; c++) {
                final int ox = (c % 2) * b, oy = (c / 2) * b;
                for (int by = 0; by < bh; by++) {
                    java.util.Arrays.fill(sums, 0L);
                    java.util.Arrays.fill(small, Integer.MAX_VALUE);
                    java.util.Arrays.fill(nearCount, 0);
                    java.util.Arrays.fill(padded, false);
                    java.util.Arrays.fill(allZero, true);
                    java.util.Arrays.fill(hit, false);
                    for (int y = 0; y < bs; y += 4) {
                        final int rowIndex = (by * bs + y) * 2 + oy;
                        if (bulk) {
                            view.position(rowIndex * width);
                            view.get(row, 0, bw * bs * 2);
                        }
                        for (int bx = 0; bx < bw; bx++) {
                            final int base = bx * bs * 2 + ox;
                            long sum = 0;
                            int least = small[bx], atZero = 0;
                            boolean pad = false, zeros = true, under = false;
                            for (int x = 0; x < bs; x += 4) {
                                final int v = (bulk ? row[base + x * 2] : data.get(rowIndex * width + base + x * 2)) & 0xffff;
                                sum += v;
                                s.values[v]++;
                                if (v <= nearZero) atZero++;
                                if (v > s.max) { s.max2 = s.max; s.max = v; } else if (v > s.max2) s.max2 = v;
                                pad |= v < paddingBelow; // a block on the border of the padding: its mean is half signal
                                zeros &= v == 0;
                                if (v > 0) {
                                    under |= v < paddingBelow;
                                    if (v < least) least = v;
                                }
                            }
                            sums[bx] += sum;
                            s.counted += bs / 4;
                            small[bx] = least;
                            nearCount[bx] += atZero;
                            padded[bx] |= pad;
                            allZero[bx] &= zeros;
                            hit[bx] |= under;
                        }
                    }
                    for (int bx = 0; bx < bw; bx++) {
                        final int i = by * bw + bx;
                        s.means[c][i] = sums[bx] / (float) ((bs / 4) * (bs / 4));
                        if (!allZero[bx]) s.lowest = Math.min(s.lowest, s.means[c][i]);
                        s.low[c][i] = padded[bx];
                        s.zero[c][i] = allZero[bx];
                        s.smallest[c][i] = small[bx] == Integer.MAX_VALUE ? 0 : small[bx];
                        s.near[c][i] = nearCount[bx];
                        s.hit[c][i] = hit[bx];
                        if (padded[bx]) s.padding++;
                        if (hit[bx]) s.hits++;
                    }
                }
            }
            return s;
        }
    }

    /**
     * P127: two samples of the shot's frame above its white (the second largest: one hot pixel is no range) prove a stream wider
     * than the levels were resolved for (RawLevels.sampledBits: its range, 14 instead of 12 bits when the frame's darkest block
     * sits on the 14-bit pedestal): black and white rescaled to that depth (the user's overrides kept). True when changed.
     */
    boolean widenToSamples(int secondMax, float lowestBlock) {
        if (whiteOverride() || secondMax <= whiteLevel || !com.scamera.app.processing.RawLevels.wideEnabled())
            return false;
        final int from = levelBits > 0 ? levelBits : com.scamera.app.processing.RawLevels.bitsOfWhite(whiteLevel);
        float black = Float.MAX_VALUE;
        for (float b : cfaBlack) black = Math.min(black, b);
        final int bits = com.scamera.app.processing.RawLevels.sampledBits(secondMax, whiteLevel, black, lowestBlock);
        if (from <= 0 || bits <= from) return false;
        final int k = bits - from;
        if (!blackOverride()) for (int i = 0; i < 4; i++) cfaBlack[i] = Math.min(65535f, cfaBlack[i] * (1 << k));
        whiteLevel = (int) Math.min(65535L, ((whiteLevel + 1L) << k) - 1);
        // P320: the scaled white of a DCG frame is a guess; the clip its session measured is the real one (X300 Ultra: 8694)
        final int clip = com.scamera.app.processing.RawLevels.saturationFor(declaredWhite);
        if (dcgShot && clip > 2f * black * (1 << k) && clip < whiteLevel) {
            whiteLevel = clip;
            levelSource += ", white = measured clip " + clip;
        }
        levelBits = bits;
        levelSource += ", widened to the frame's samples (" + secondMax + ")";
        Log.i(TAG, "RAW levels: the frame's samples reach " + secondMax + ": " + from + " -> " + bits + " bit, black "
                + Arrays.toString(cfaBlack) + ", white " + whiteLevel);
        return true;
    }

    /** P114: a block of exact zeros (padding) or next to one (its border holds both). */
    private static boolean nearZeroBlock(boolean[] zero, int bw, int bh, int bx, int by) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int x = bx + dx, y = by + dy;
            if (x >= 0 && y >= 0 && x < bw && y < bh && zero[y * bw + x]) return true;
        }
        return false;
    }

    private java.nio.ByteBuffer blackFloorSource;
    private int blackFloorBlock = 1;
    private String blackFloorStream;
    private int blackFloorMax;
    private float blackFloorLow = -1f;
    /** P558: the clip plateau of the frame the floor was measured on (0: none). */
    private int blackFloorPlateau;
    /** P386: the shot's RAW proved to have come without its black pedestal (refineBlackLevel), itself or by its stream's floor. */
    private boolean pedestalGone, pedestalRemembered;
    /** Where the black and white levels of the shot came from (diagnostics): bit depth, override / data floor / dynamic / static. */
    public String levelSources() {
        String black = blackOverride() ? "override" : blackFloorSource != null && blackFloorEstimate != Float.MAX_VALUE
                ? "data floor (" + blackFloorEstimate + (pedestalRemembered ? ", the stream's RAW without its pedestal"
                : pedestalGone ? ", the RAW without its pedestal" : "")
                + (blackFloorBlock > 1 ? ", colour block " + blackFloorBlock : "") + ")" : dynamicBlackUsed ? "dynamic" : "static";
        String white = whiteOverride() ? "override" : useDynamicWhiteLevel ? "dynamic if reported" : "static";
        return levelBits + " bit, black=" + black + " white=" + white + " [" + levelSource + "]";
    }
    private float blackFloorEstimate = Float.MAX_VALUE;

    private void applyBlackFloor(float estimate) {
        // ~1 DN of a 10-bit range; below that it is noise in the estimate, not a wrong black.
        float margin = Math.max(1f, whiteLevel / 1023f);
        boolean changed = false;
        float[] before = cfaBlack.clone();
        for (int i = 0; i < 4; i++) {
            if (cfaBlack[i] > estimate + margin) { cfaBlack[i] = estimate; changed = true; }
        }
        if (changed) Log.i(TAG, "RAW black level from data: " + Arrays.toString(before)
                + " -> " + Arrays.toString(cfaBlack) + " (data floor " + estimate + ")");
    }

    /**
     * Whether RAW_SENSOR already carries the HAL's lens shading correction, so the
     * STATISTICS_LENS_SHADING_CORRECTION_MAP must not be applied again.
     * Setting pref_raw_lsc_mode: auto | apply | skip; only "skip" drops the map.
     * SENSOR_INFO_LENS_SHADING_APPLIED is not trusted: vivo X200 Ultra reports true,
     * yet its RAW renders correctly only with the map applied.
     */
    public static boolean rawShadingAlreadyApplied(CameraCharacteristics characteristics) {
        return "skip".equals(PreferenceKeys.getRawLscMode());
    }

    /**
     * P212e (W1 = M2): a frame without a lens shading map that is an OPPO camera's native FULLRES frame (its native size is the
     * key of a stored plain state, OppoPlainState) takes the camera's stored plain map - the bin of the light nearest to the
     * result's neutral - so a cold start straight into FULLRES no longer develops with 'lens shading map 1x1' (10 of 22 OPPO
     * FULLRES photos: corners 1.2-2.2 EV dark, colour-shaded). The floats are LensShadingMap.copyGainFactors' order, so the
     * crops below and the DNG gain map take them as they take a reported map. True when it did.
     */
    boolean applyStoredShading(@androidx.annotation.Nullable CaptureResult result) {
        if (rawSize == null || !com.scamera.app.capture.OppoNativeFullRes.brand()) return false;
        float[] neutral = null;
        final Rational[] n = result == null ? null : result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (n != null && n.length >= 3) neutral = new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()};
        final com.scamera.app.capture.OppoPlainState.Shading stored =
                com.scamera.app.capture.OppoPlainState.shading(String.valueOf(physicalID), rawSize.x, rawSize.y, neutral);
        if (stored == null) return false;
        shadingMap = stored.gains.clone();
        shadingGrid = new Point(stored.cols, stored.rows);
        shadingMapUsed = true;
        Log.i(TAG, "lens shading map " + stored + " (stored, the camera's plain session)");
        return true;
    }

    /**
     * P212e (W1): a usable white point for {@code wp} (the result's neutral as read) when it is none - NaN, Inf, zero or negative
     * (the OPPO native mode reports NaN; complete() leaves it when it has nothing to fill): the inverse of the result's
     * white-balance gains, else the camera's stored plain neutral, else equal gains. Null when {@code wp} is usable.
     */
    @androidx.annotation.Nullable
    static float[] unusableNeutralFallback(@androidx.annotation.Nullable float[] wp,
                                           @androidx.annotation.Nullable android.hardware.camera2.params.RggbChannelVector gains,
                                           @androidx.annotation.Nullable float[] stored) {
        if (usableNeutral(wp)) return null;
        if (gains != null) {
            final float g = 0.5f * (gains.getGreenEven() + gains.getGreenOdd());
            final float[] inv = {g / gains.getRed(), 1f, g / gains.getBlue()};
            if (g > 0f && usableNeutral(inv)) return inv;
        }
        if (usableNeutral(stored)) return new float[]{stored[0] / stored[1], 1f, stored[2] / stored[1]};
        return new float[]{1f, 1f, 1f};
    }

    private static boolean usableNeutral(@androidx.annotation.Nullable float[] wp) {
        if (wp == null || wp.length < 3) return false;
        for (int i = 0; i < 3; i++) if (!(wp[i] > 0f) || Float.isInfinite(wp[i])) return false;
        return true;
    }

    public float[] customNeutral;
    /** P121: the neutral is SCAMERA's own (a mosaic stream): the ISP's colour transform picked at the camera's neutral does not fit. */
    public boolean ownNeutral;
    /**
     * P212f (W2 = M3): the hand-set white balance's gains at the press (P201, ManualWhiteBalance.gains on the camera's
     * characteristics, as the requests got them; null: automatic then), so a switch back to Auto during the series does not leak
     * into the shot. Set before the merge.
     */
    @androidx.annotation.Nullable
    public android.hardware.camera2.params.RggbChannelVector manualWbAtPress;
    /** P212f: where the kept hand-set white balance came from (log), empty when none. */
    public String handSetSource = "";
    /**
     * P212g (W7 = M12): a mosaic shot (ScamProcessor.applyMosaicNeutral found a colour block above 1): without a usable ISP matrix
     * for its neutral it is rendered with the camera's plain-session matrix at that neutral (PlainColourMemory) instead of the DNG
     * matrices.
     */
    public boolean mosaicShot;
    /** P212g (W7): where the mosaic shot's colour matrix came from (log), empty for any other shot. */
    public String mosaicMatrixSource = "";
    /**
     * P212g (W6 = M6): what the press knew about the mosaic white balance - the vivo native route, or P208's still-only route with
     * its viewfinder's neutral (MosaicNeutralResolver); null: neither. Set before the merge.
     */
    @androidx.annotation.Nullable
    public com.scamera.app.processing.color.MosaicNeutralResolver.Press mosaicWbAtPress;

    /**
     * P212g (W7 = M12): a mosaic shot takes the plain-session table's matrix when no tuned one renders it and the result's transform
     * does not fit its neutral - our own neutral (the ISP's matrix was picked at the camera's), or none the ISP branch accepts (the
     * vivo mosaic results' identity). P212z: resolveColour asks the table only when PlainColourMemory.wanted - P366: every mosaic shot
     * by default ("mosaic_ccm_table 2"; the owner's green X200U ISZ photos were the A/B), FULLRES only with 1.
     */
    static boolean plainTableApplies(boolean mosaicShot, boolean oppoTuned, boolean ownNeutral, boolean ispUsable) {
        return mosaicShot && !oppoTuned && (ownNeutral || !ispUsable);
    }

    /** P212f (W2): the white point {G / R, 1, G / B} of hand-set gains (the formula of resolveColour's AWB-off branch); null: none. */
    @androidx.annotation.Nullable
    static float[] handSetNeutral(@androidx.annotation.Nullable android.hardware.camera2.params.RggbChannelVector gains) {
        if (gains == null) return null;
        final float green = (gains.getGreenEven() + gains.getGreenOdd()) * .5f;
        if (!(green > 0) || !(gains.getRed() > 0) || !(gains.getBlue() > 0)) return null;
        final float[] wp = {green / gains.getRed(), 1, green / gains.getBlue()};
        return usableNeutral(wp) ? wp : null;
    }

    /**
     * P212f (W2 = M3): a mosaic shot (ISZ / FULLRES) keeps a hand-set white balance (P201) instead of its own estimate - the
     * mosaic white balance replaced it on every such photo and its DNG (resolveColour(true) skips the AWB-off branch): the gains at
     * the press ({@link #manualWbAtPress}) first, else those of a result that says AWB OFF (an OPPO native result carries the
     * inverse of the neutral complete() filled - exactly the hand-set one, OppoNativeFullRes.setNeutral / OppoNativeWb.hand),
     * else, AWB OFF without gains, the white point already read. Sets {@link #customNeutral} (ownNeutral false: the ISP's colour
     * transform stays usable as on a 12 MP shot); the caller runs resolveColour(true). False: automatic white balance.
     */
    public boolean keepHandSetWhiteBalance(@androidx.annotation.Nullable CaptureResult meta) {
        float[] hand = handSetNeutral(manualWbAtPress);
        String how = "the press";
        if (hand == null) {
            final Integer awb = meta == null ? null : meta.get(CaptureResult.CONTROL_AWB_MODE);
            if (awb == null || awb != CaptureRequest.CONTROL_AWB_MODE_OFF) return false;
            hand = handSetNeutral(meta.get(CaptureResult.COLOR_CORRECTION_GAINS));
            how = "the result's gains (AWB off)";
            if (hand == null) {
                if (!usableNeutral(asShotNeutral)) return false;
                hand = asShotNeutral.clone();
                how = "the result's neutral (AWB off)";
            }
        }
        customNeutral = hand;
        ownNeutral = false;
        handSetSource = how;
        return true;
    }

    public void resolveColour(boolean customNeutr, CaptureResult result) {
        final CameraCharacteristics characteristics = camera();
        final Rational[] reportedNeutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        final Integer awbMode = result.get(CaptureResult.CONTROL_AWB_MODE);
        final android.hardware.camera2.params.RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        colourFrame(result);
        asShotNeutral = customNeutr ? customNeutral : resultNeutral(reportedNeutral, gains, awbMode);
        // P370 (A5): the neutral with green 1 (the HALs report it so; the white estimate and the white balance read it so)
        if (asShotNeutral != null && asShotNeutral.length >= 3 && asShotNeutral[1] > 0f && asShotNeutral[1] != 1f && Float.isFinite(asShotNeutral[1])) {
            Log.i(TAG, "the neutral " + Arrays.toString(asShotNeutral) + " is normalised to green 1");
            final float g = asShotNeutral[1];
            asShotNeutral = new float[]{asShotNeutral[0] / g, 1f, asShotNeutral[2] / g};
        }
        final ColourTags tags = new ColourTags(characteristics);
        final int ref1 = tags.ref1, ref2 = tags.ref2;
        refIlluminant1 = tags.ref1;
        refIlluminant2 = tags.ref2;
        final boolean sameForward = tags.sameForward, noForward = tags.noForward;
        if (tags.missing.length() > 0)
            Log.w(TAG, "camera colour tags missing or unusable:" + tags.missing + " (the other calibration stands in)");
        Converter.convertColorspaceTransform(tags.cc1, cameraCalibration1);
        Converter.convertColorspaceTransform(tags.cc2, cameraCalibration2);
        Converter.convertColorspaceTransform(tags.fm1, forwardMatrix1);
        Converter.convertColorspaceTransform(tags.fm2, forwardMatrix2);
        final boolean colourMatrices = tags.cm1 != null;
        if (colourMatrices) {
            Converter.convertColorspaceTransform(tags.cm1, colorMatrix1);
            Converter.convertColorspaceTransform(tags.cm2, colorMatrix2);
        } else {
            Arrays.fill(colorMatrix1, 0f);
            Arrays.fill(colorMatrix2, 0f);
        }
        float[] normalizedForwardTransform1 = rowsToD50(forwardMatrix1);
        float[] normalizedForwardTransform2 = rowsToD50(forwardMatrix2);
        // P370 (A1): the scene light and the interpolation weight from the DNG SDK's white estimate (DngColor: each ColorMatrix
        // normalised as one, the xy iteration, CCT and Duv off the Planckian locus); a white more than DUV_GUARD off the locus is
        // the matrices' artefact, not a light: unknown (scene light 0, the daylight calibration). scam_dev "dng_white_spec 0": the
        // former estimate (row-normalised matrices, Converter's McCamy iteration, the mired lerp of the factor).
        // P370 (A5): an estimate that fails (no or singular matrices) gives the daylight calibration and an unknown light.
        double interpolationFactor = 0;
        sceneDuv = Float.NaN;
        String colourFailure = null;
        com.scamera.app.gallery.dng.DngColor dngWhite = null;
        // P370 (review): both colour matrices placeholders (the Pixel 7: CM2 is XYZ -> sRGB, CM1 x XYZ(A) = 1.001 / 1 / 0.998): the
        // spec estimate reads no light off them (|Duv| 0.033 - 0.038 at real neutrals), the former estimate gave the phone its light
        final boolean placeholderCms = colourMatrices && placeholderColorMatrix(colorMatrix1, ref1)
                && placeholderColorMatrix(colorMatrix2, ref2);
        try {
            if (!colourMatrices) throw new IllegalArgumentException("no colour matrix");
            if (whiteSpec() && !placeholderCms) {
                dngWhite = dngWhite(ref1, ref2);
                if (!dngWhite.usable()) throw new IllegalArgumentException("singular or non-finite colour matrices");
                // P370 (review): no silent D50 for a white the matrices cannot place (an XYZ sum <= 0, a singular step)
                final double[] xy = dngWhite.estimateXy(toDouble(asShotNeutral));
                if (xy == null) throw new IllegalArgumentException("no chromaticity for the neutral " + Arrays.toString(asShotNeutral));
                final double[] cd = com.scamera.app.gallery.dng.DngColor.cctDuv(xy);
                if (!(cd[0] > 0) || !Double.isFinite(cd[0]) || !Double.isFinite(cd[1]))
                    throw new IllegalArgumentException("no colour temperature for the neutral " + Arrays.toString(asShotNeutral));
                sceneDuv = (float) cd[1];
                if (Math.abs(cd[1]) > DUV_GUARD) {
                    // P370 (review): the calibration keeps the spec's weight (the DNG SDK has no such guard: the colour chain would
                    // switch at |Duv| 0.025 from the A calibration to the daylight one); only the scene light is unknown
                    interpolationFactor = dngWhite.weight2(cd[0]);
                    sceneCct = 0f;
                    sceneCctSource = String.format(java.util.Locale.ROOT, "unknown: the DNG estimate %.0f K is Duv %.4f off the locus (> %.3f)",
                            cd[0], cd[1], DUV_GUARD);
                    Log.w(TAG, "scene light " + sceneCctSource + ": the calibration weight of the estimate kept");
                } else {
                    interpolationFactor = dngWhite.weight2(cd[0]);
                    sceneCct = (float) cd[0];
                    sceneCctSource = "the DNG estimate";
                }
                Log.d(TAG, String.format(java.util.Locale.ROOT, "DNG white: %.0f K Duv %.4f, calibration 2 weight %.4f (illuminants %d / %d)",
                        cd[0], cd[1], interpolationFactor, ref1, ref2));
            } else {
                // the former estimate: each colour matrix scaled by rows like a forward matrix, Converter's McCamy iteration
                final float[] rowCm1 = rowsToD50(colorMatrix1), rowCm2 = rowsToD50(colorMatrix2);
                Log.d(TAG, "former white estimate: CC " + Arrays.toString(cameraCalibration1) + " / " + Arrays.toString(cameraCalibration2)
                        + ", row-scaled CM " + Arrays.toString(rowCm1) + " / " + Arrays.toString(rowCm2));
                interpolationFactor = Converter.findDngInterpolationFactor(ref1, ref2, cameraCalibration1, cameraCalibration2,
                        rowCm1, rowCm2, asShotNeutral);
                float mired1 = 1e6f / OppoTunedColor.cct(ref1), mired2 = 1e6f / OppoTunedColor.cct(ref2);
                sceneCct = 1e6f / (mired1 + (float) interpolationFactor * (mired2 - mired1));
                sceneCctSource = placeholderCms && whiteSpec()
                        ? "the former DNG estimate (row-normalised matrices): both colour matrices are placeholders"
                        : "the DNG estimate (row-normalised matrices, dng_white_spec 0)";
            }
        } catch (RuntimeException e) {
            interpolationFactor = daylightWeight2(ref1, ref2);
            sceneCct = 0f;
            sceneDuv = Float.NaN;
            colourFailure = "the white estimate failed (" + e.getMessage() + ")";
            sceneCctSource = "unknown: " + colourFailure;
            Log.w(TAG, colourFailure);
        }
        Log.d(TAG, "calibration 2 weight " + interpolationFactor);
        boolean oppoTuned = false;
        if (OppoTunedColor.applies()) {
            // P212g (W7 = M12 item 4): a sensor without a measured 6500 K anchor (22.36 mm, 3.23 mm) takes the one its plain table learned
            final String camera = String.valueOf(physicalID);
            float cctK = OppoTunedColor.estimateCct(characteristics, asShotNeutral, camera);
            {
                float[] fl = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                float f0 = fl != null && fl.length > 0 ? fl[0] : 0f;
                Log.i(TAG, String.format(java.util.Locale.ROOT, "OPPO CCT: focal %.2f mm neutral R/G %.4f B/G %.4f ln(R/B) %.4f anchor %s -> %.0f K",
                        f0, asShotNeutral[0] / asShotNeutral[1], asShotNeutral[2] / asShotNeutral[1], Math.log(asShotNeutral[0] / asShotNeutral[2]),
                        OppoTunedColor.describeAnchor(f0, camera), cctK));
            }
            sceneCct = cctK;
            sceneCctSource = "the OPPO tuned estimate";
            float[] tunedForward = OppoTunedColor.forwardAt(characteristics, cctK);
            if (tunedForward != null) {
                normalizedForwardTransform1 = rowsToD50(tunedForward);
                normalizedForwardTransform2 = normalizedForwardTransform1.clone();
                oppoTuned = true;
                oppoTunedCcm = true;
                Log.d(TAG, "OPPO tuned forward matrix at " + cctK + " K: " + Arrays.toString(normalizedForwardTransform1));
            }
        }
        // P370 (A5): a calibration that cannot be inverted (or a non-finite chain) never loses the shot: the fallback below
        boolean chainFailed = false;
        try {
            forwardChain(normalizedForwardTransform1, normalizedForwardTransform2, interpolationFactor);
        } catch (RuntimeException e) {
            chainFailed = true;
            colourFailure = (colourFailure == null ? "" : colourFailure + "; ") + "camera -> XYZ failed (" + e.getMessage() + ")";
            if (!oppoTuned) {
                sceneCct = 0f;
                sceneCctSource = "unknown: " + colourFailure;
            }
            Log.w(TAG, colourFailure);
        }
        //ColorSpaceTransform CST = ScameraApp.getCaptureController().mColorSpaceTransform;//= result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        ColorSpaceTransform CST = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        final ColorSpaceTransform reportedCst = CST;
        CST = oplusCcmInstead(CST, result);
        final boolean oplusCst = CST != reportedCst;
        Log.d(TAG, "result colorCorrection transform=" + CST + " gains=" + result.get(CaptureResult.COLOR_CORRECTION_GAINS));
        // P212g (W7 = M12): a mosaic shot without a usable ISP matrix for its neutral takes the camera's plain-session matrix at that
        // neutral (PlainColourMemory) - the colour the ISP renders that light with in the normal mode - instead of the generic DNG
        // matrices (scam_dev.txt "mosaic_ccm_table 0": as before)
        final boolean ispUsable = ispCcmUsable(CST);
        com.scamera.app.processing.color.PlainColourMemory.Lookup plainTable = null;
        boolean plainTableAsked = false;
        // P212z (review): FULLRES photos only with "mosaic_ccm_table 1"; P366 (owner, 2026-10-27: «вот такие фото получаю с исз,
        // исправь»): the 12 MP ISZ modules' mosaic photos as well by default (2) - the generic DNG matrices rendered them green
        // P315: a camera whose DNG forward matrices are the sRGB -> XYZ placeholder (X8U camera 2) never renders a mosaic shot through
        // them: the plain-session table off FULLRES as well, else the ISP's matrix even with our own neutral. P370 (A3): known on
        // every shot (a 12 MP shot that would end on them takes the fallbacks below)
        final boolean placeholderFm = OppoTunedColor.placeholderForward(forwardMatrix1)
                && OppoTunedColor.placeholderForward(forwardMatrix2);
        if (placeholderFm) Log.i(TAG, "the DNG forward matrices are the sRGB -> XYZ placeholder: the plain-session table or the ISP matrix (P315)");
        final boolean plainTableWanted = com.scamera.app.processing.color.PlainColourMemory.wanted(fullResRequested > 0)
                || placeholderFm;
        if (plainTableApplies(mosaicShot, oppoTuned, ownNeutral, ispUsable) && plainTableWanted) {
            plainTableAsked = true;
            plainTable = com.scamera.app.processing.color.PlainColourMemory.lookup(String.valueOf(physicalID), asShotNeutral);
            if (plainTable != null) CST = plainTable.transform();
        }
        final int colorMethod = ScameraApp.getSettings().colourMethod;
        boolean capturedTransform = legacyCaptured(sameForward, colorMethod, CST, oppoTuned);
        // vivo X200 Ultra: the DNG matrices in the characteristics are generic (the interpolation always lands near D65), but the
        // HAL reports the colour transform its ISP tuning uses at the scene's colour temperature, together with the
        // matching white balance gains. SCAM HDR renders with that matrix (the same idea as the OPPO tuned CCM per CCT).
        ColourSource route = oppoTuned ? ColourSource.OPPO_TUNED : ColourSource.DNG_FM;
        String why = "";
        boolean ispBranch = false;
        if (!oppoTuned && (plainTable != null || (!ownNeutral || placeholderFm) && ispUsable)) {
            capturedTransform = true;
            ispBranch = true;
            route = plainTable != null ? ColourSource.PLAIN_TABLE : oplusCst ? ColourSource.OPLUS_CCM
                    : ownNeutral && placeholderFm ? ColourSource.ISP_CST_PLACEHOLDER : ColourSource.ISP_CST;
            Log.d(TAG, plainTable != null ? "Using the camera's plain-session colour matrix (mosaic shot)"
                    : "Using the ISP colour transform of the capture result (camera " + physicalID + ")");
        }
        if (capturedTransform && !ispBranch) route = ColourSource.LEGACY_CAPTURED;
        final float[] dcpXyz = com.scamera.app.processing.color.DcpProfiles.activeToXyz(asShotNeutral);
        float[] cmOnly = null;
        if (chainFailed && !capturedTransform && dcpXyz == null) {
            // P370 (A5): the DNG chain failed: the result's transform when its shape is a colour matrix's, else the white balance alone
            why = colourFailure;
            if (CST != null && ispCcmShape(CST)) {
                capturedTransform = true;
                route = oplusCst ? ColourSource.OPLUS_CCM : ColourSource.ISP_CST;
            } else {
                route = ColourSource.IDENTITY_FALLBACK;
            }
            Log.w(TAG, "colour: " + route + " (" + why + ")");
        } else if (!chainFailed && !capturedTransform && !oppoTuned && dcpXyz == null && (placeholderFm || noForward)) {
            // P370 (A3): the forward matrices are the sRGB -> XYZ placeholder (X8U cameras 2 and 4, Pixel 7) or absent, and the shot
            // would end on them (isp_ccm 0, SCAM off, «Источник цвета» «Характеристики», an identity transform without an OPPO ISP
            // matrix): camera RGB would be taken as sRGB (0.50-0.71 of the chroma). In order: the result's transform when its shape
            // is a colour matrix's (the gates aside), the camera's plain-session matrix, the frame's OPPO ISP matrix, the colour
            // matrices alone (not placeholders, the white on the locus), else the placeholder with a warning.
            why = noForward ? "no forward matrix" : "the forward matrices are the sRGB -> XYZ placeholder";
            if (colorMethod == 1) Log.w(TAG, "«Источник цвета» «Характеристики» refused: " + why);
            com.scamera.app.processing.color.PlainColourMemory.Lookup table = null;
            if (CST != null && !oplusCst && ispCcmShape(CST)) {
                capturedTransform = true;
                route = ColourSource.ISP_CST_PLACEHOLDER;
                why += ": the result's transform (isp_ccm / SCAM / the colour source setting set aside)";
            } else if ((table = com.scamera.app.processing.color.PlainColourMemory.lookup(String.valueOf(physicalID), asShotNeutral)) != null) {
                plainTable = table;
                CST = table.transform();
                capturedTransform = true;
                route = ColourSource.PLAIN_TABLE;
                why += ": " + table.how;
            } else if (oplusCst && rowsNormalisable(CST)) {
                capturedTransform = true;
                route = ColourSource.OPLUS_CCM;
                why += ": the frame's OPPO ISP matrix";
            } else if ((cmOnly = colourMatrixRoute(ref1, ref2, dngWhite)) != null) {
                route = ColourSource.DNG_CM_ONLY;
                why += ": the colour matrices alone";
            } else {
                route = ColourSource.IDENTITY_PLACEHOLDER;
                why += ": nothing better, camera RGB taken as sRGB";
            }
            Log.w(TAG, "colour: " + route + " (" + why + ")");
        }
        if (capturedTransform || route == ColourSource.IDENTITY_FALLBACK) {
            whiteBalanceOnly();
            Log.d(TAG, "colour: the white balance alone before the transform");
        } else if (cmOnly != null) {
            System.arraycopy(cmOnly, 0, cameraToProPhoto, 0, 9);
            Log.d(TAG, "Using the colour matrices alone");
        } else {
            Log.d(TAG, "colour: the DNG camera -> ProPhoto chain");
        }
        Log.d(TAG, "sensorToProPhoto " + Arrays.toString(cameraToProPhoto));
        proPhotoToSrgbOfProPhoto(proPhotoToSrgb);
        if (CST != null && capturedTransform) {
            // the result's transform, each row scaled to sum 1 (a grey stays grey)
            Converter.convertColorspaceTransform(CST, proPhotoToSrgb);
            if (!normalize(proPhotoToSrgb)) Log.w(TAG, "a row of the transform sums to about 0: kept as it is " + Arrays.toString(proPhotoToSrgb));
        } else if (route == ColourSource.IDENTITY_FALLBACK) {
            proPhotoToSrgb = new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
        }
        renderWith(proPhotoToSrgb);
        if (dcpXyz != null) {
            // a DCP profile: its camera -> XYZ, then the standard ProPhoto -> sRGB
            Converter.multiply(Converter.sXYZtoProPhoto, dcpXyz, cameraToProPhoto);
            proPhotoToSrgbOfProPhoto(proPhotoToSrgb);
            renderWith(proPhotoToSrgb);
            route = ColourSource.DCP;
            why = "";
        } else {
            // P408 (the reference app's order under its DCP profile): a profile of its matrix registry, else the matrix set by hand,
            // white-balanced camera RGB -> linear sRGB with the rows to 1 (a grey stays grey), per module / FULLRES profile
            final int presetId = ccmPresetId();
            float[] own = presetId > 0 ? com.scamera.app.processing.color.CcmPresets.matrix(presetId) : null;
            final boolean preset = own != null;
            if (own == null && ccmOverride()) own = com.scamera.app.processing.color.CcmPresets.parse(ccmMatrixText());
            if (own != null && normalize(own)) {
                whiteBalanceOnly();
                renderWith(own);
                route = preset ? ColourSource.CCM_PRESET : ColourSource.CCM_OVERRIDE;
                why = preset ? "profile " + presetId : "";
            }
        }
        colourSource = route.name();
        colourSourceWhy = why == null ? "" : why;
        // vivo: the DNG estimate above lands on a reference illuminant for every scene; the white balance of the shot
        // against the camera's daylight white gives the real colour temperature (the warm cast kept in the render,
        // the warm-light hue look).
        // P212g (W7): a mosaic shot on the plain-session matrix takes the colour temperature of its AWB labels at that light
        // P370 (A7): SceneIlluminant knows the physical camera ("0-3" of a logical-physical pair is no camera of its table)
        final String physical = String.valueOf(physicalID);
        if (!oppoTuned && dcpXyz == null && capturedTransform) {
            if (plainTable != null && plainTable.cct > 0f) {
                Log.d(TAG, "Scene light from the plain-session AWB labels: " + plainTable.cct + " K (DNG estimate " + sceneCct + " K, camera " + physical + ")");
                sceneCct = plainTable.cct;
                sceneCctSource = "the plain-session AWB labels";
            } else {
                float wbCct = com.scamera.app.processing.color.SceneIlluminant.cct(physical, asShotNeutral, colorTransform.matrix);
                if (wbCct > 0f) {
                    Log.d(TAG, "Scene light from the white balance: " + wbCct + " K (DNG estimate " + sceneCct + " K, camera " + physical + ")");
                    sceneCct = wbCct;
                    sceneCctSource = "the white balance against the camera's daylight neutral (SceneIlluminant)";
                }
            }
        }
        // P370 (B7): a light the estimate cannot tell (off the locus, failed) takes the frame's Qualcomm AWB label - only the DNG's
        // ColorMatrix1 (its As-Shot temperature) and keep warm / cool read sceneCct; the summary logs the label beside it always.
        // Review: not a mosaic shot with the app's own neutral - the ISP read that mosaic as Bayer (P121) or ran no 3A at all (the
        // X200U ISZ lease, MosaicNeutralResolver.ispIdle: its label is the frozen one)
        if (!(sceneCct > 0f) && awbLabelK > 0 && !(mosaicShot && ownNeutral)) {
            sceneCct = awbLabelK;
            sceneCctSource = "the AWB label (AWBFrameControlCCT; " + sceneCctSource + ")";
        }
        Log.i(TAG, colourSummary());
        if (mosaicShot) {
            // P315: what the branch above rendered with (the placeholder case takes the ISP transform with our own neutral too)
            final boolean ispRendered = capturedTransform && plainTable == null && (!ownNeutral || placeholderFm) && ispUsable;
            final boolean fallback = !why.isEmpty() || route == ColourSource.LEGACY_CAPTURED;
            mosaicMatrixSource = fallback && route != ColourSource.PLAIN_TABLE
                    ? (route == ColourSource.LEGACY_CAPTURED ? "the result's transform (LEGACY_CAPTURED: FM1 == FM2 or colour source 2, capture)"
                    : route + " (" + why + ")")
                    : mosaicMatrixSource(dcpXyz != null, oppoTuned, plainTable, ispRendered, ownNeutral && placeholderFm,
                    plainTableAsked, plainTableWanted, String.valueOf(physicalID), asShotNeutral);
            Log.i(TAG, "mosaic colour matrix: " + mosaicMatrixSource + String.format(java.util.Locale.ROOT, ", scene %.0f K", sceneCct));
        }
    }

    /**
     * The white point of the result (resolveColour without a custom neutral): its SENSOR_NEUTRAL_COLOR_POINT written into the
     * current {@link #whitePoint} (as many entries as both have); P212e (W1) a NaN / missing neutral never reaches the photo (the
     * OPPO native mode's, with nothing to fill it: unusableNeutralFallback); with AWB off and gains, the gains' {G / R, 1, G / B}.
     */
    private float[] resultNeutral(@androidx.annotation.Nullable Rational[] reported,
                                  @androidx.annotation.Nullable android.hardware.camera2.params.RggbChannelVector gains,
                                  @androidx.annotation.Nullable Integer awbMode) {
        float[] n = asShotNeutral;
        if (reported != null) {
            final int count = Math.min(reported.length, n.length);
            for (int i = 0; i < count; i++) n[i] = reported[i].floatValue();
        }
        final float[] read = reported == null ? null : n;
        final float[] fixed = usableNeutral(read) ? null : unusableNeutralFallback(read, gains, storedPlainNeutral());
        if (fixed != null) {
            Log.w(TAG, "the result's neutral " + Arrays.toString(n) + " is no white point: " + Arrays.toString(fixed) + " used");
            n = fixed;
        }
        if (gains != null && awbMode != null && awbMode == CaptureRequest.CONTROL_AWB_MODE_OFF) {
            final float green = 0.5f * (gains.getGreenEven() + gains.getGreenOdd());
            final float r = gains.getRed(), b = gains.getBlue();
            if (green > 0 && r > 0 && b > 0) n = new float[]{green / r, 1, green / b};
        }
        return n;
    }

    /** The OPPO camera's stored plain-session neutral at this size (OppoPlainState; null: none or not OPPO). */
    @androidx.annotation.Nullable
    private float[] storedPlainNeutral() {
        if (!com.scamera.app.capture.OppoNativeFullRes.brand() || rawSize == null) return null;
        return com.scamera.app.capture.OppoPlainState.neutral(String.valueOf(physicalID), rawSize.x, rawSize.y);
    }

    /**
     * The camera's DNG colour tags as resolveColour renders with them. P370 (A5): a missing tag never loses the shot - no
     * illuminant 1: D65; no second calibration: the first one (CM, CC); a missing or zero forward matrix takes the other one,
     * never the identity. P580 (CCM audit): an all-zero or non-finite matrix of a bad HAL is no matrix (it was taken as one: the
     * white estimate failed, the daylight weight then picked the zero calibration and the chain fell to camera RGB as sRGB),
     * and a calibration with an unknown illuminant (code 0) or no usable ColorMatrix2 is a single-illuminant profile - the
     * first calibration alone, as the DNG SDK renders it (dng_color_spec: ColorMatrix2 empty or a temperature 0).
     */
    private static final class ColourTags {
        int ref1, ref2;
        ColorSpaceTransform cc1, cc2, cm1, cm2, fm1, fm2;
        /** FM1 == FM2 as reported (both missing count as two equal identities): the legacy capture branch's test. */
        final boolean sameForward;
        /** Neither forward matrix usable: both are the identity. */
        final boolean noForward;
        /** What was missing or unusable, for the log (empty: nothing). */
        final StringBuilder missing = new StringBuilder();

        ColourTags(CameraCharacteristics camera) {
            final Integer illuminant1 = camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
            final Object illuminant2 = camera.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
            ref1 = illuminant1 == null ? CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65 : illuminant1;
            ref2 = illuminant2 == null ? ref1 : (byte) illuminant2;
            if (illuminant1 == null) missing.append(" illuminant 1 (D65 taken)");
            cc1 = matrixOrNull(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
            cc2 = matrixOrNull(camera.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
            cm1 = matrixOrNull(camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
            final ColorSpaceTransform cm2Tag = camera.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2);
            cm2 = matrixOrNull(cm2Tag);
            fm1 = camera.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1);
            fm2 = camera.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2);
            final boolean single = cm1 != null && (ref1 == 0 || ref2 == 0 || cm2Tag != null && cm2 == null);
            if (single) {
                missing.append(" calibration 2 (illuminants ").append(ref1).append(" / ").append(ref2)
                        .append(cm2 == null ? ", ColorMatrix2 unusable" : "").append(": the first calibration alone)");
                ref2 = ref1;
                cc2 = null;
                cm2 = null; // FM2 stays for the FM1 == FM2 test below (the tags as reported)
            }
            if (cc1 == null) {
                cc1 = cc2 == null ? identityTransform() : cc2;
                missing.append(" CC1");
            }
            if (cc2 == null) {
                cc2 = cc1;
                missing.append(" CC2");
            }
            if (cm1 == null && cm2 != null) {
                cm1 = cm2;
                missing.append(" CM1");
            }
            if (cm2 == null && cm1 != null) {
                cm2 = cm1;
                missing.append(" CM2");
            }
            if (cm1 == null) missing.append(" CM1 CM2");
            final boolean usable1 = usableForward(fm1), usable2 = usableForward(fm2);
            sameForward = usable1 == usable2 && (!usable1 || sameDiagonalAndB(fm1, fm2));
            if (usable1 && !usable2) {
                fm2 = fm1;
                missing.append(" FM2");
            } else if (usable2 && !usable1) {
                fm1 = fm2;
                missing.append(" FM1");
            }
            if (single && usable1) fm2 = fm1;
            noForward = !usable1 && !usable2;
            if (noForward) {
                Log.d(TAG, "Forward matrices are not invertible, using identity");
                fm1 = fm2 = identityTransform();
                missing.append(" FM1 FM2");
            }
        }

        private static ColorSpaceTransform matrixOrNull(@androidx.annotation.Nullable ColorSpaceTransform m) {
            return com.scamera.app.processing.color.PhotoMatrix.usableMatrix(m) ? m : null;
        }

        /** The legacy FM1 == FM2 test: the diagonal and the (1, 2) element equal as floats. */
        private static boolean sameDiagonalAndB(ColorSpaceTransform a, ColorSpaceTransform b) {
            for (int[] at : new int[][]{{0, 0}, {1, 1}, {2, 2}, {1, 2}})
                if (a.getElement(at[0], at[1]).floatValue() != b.getElement(at[0], at[1]).floatValue()) return false;
            return true;
        }
    }

    /** A copy of {@code m} scaled by rows so that camera white (1, 1, 1) lands on the D50 white (Converter.normalizeFM). */
    private static float[] rowsToD50(float[] m) {
        final float[] copy = m.clone();
        Converter.normalizeFM(copy);
        return copy;
    }

    /** Camera RGB -> XYZ (D50) of the forward matrices at the calibration-2 weight, -> ProPhoto into sensorToProPhoto; throws when it fails. */
    private void forwardChain(float[] forward1, float[] forward2, double weight2) {
        Log.d(TAG, "row-scaled forward matrices " + Arrays.toString(forward1) + " / " + Arrays.toString(forward2));
        final float[] toXyz = new float[9];
        Converter.calculateCameraToXYZD50Transform(forward1, forward2, cameraCalibration1, cameraCalibration2, asShotNeutral,
                weight2, toXyz);
        for (float v : toXyz)
            if (!Float.isFinite(v)) throw new IllegalArgumentException("non-finite camera -> XYZ " + Arrays.toString(toXyz));
        Log.d(TAG, "camera -> XYZ D50 " + Arrays.toString(toXyz));
        Converter.multiply(Converter.sXYZtoProPhoto, toXyz, cameraToProPhoto);
    }

    /**
     * LEGACY_CAPTURED («Источник цвета» «Захват», or the DNG forward matrices equal: FM1 == FM2): the result's transform renders
     * the shot - never with the OPPO tuned matrix, without a transform or with one of 4 non-zero elements or fewer (a diagonal).
     * P370 (A5): nor one with a row summing to about 0 (inf after the row normalisation). Review: only that (rowsNormalisable) -
     * the colour-matrix shape test also refused a HAL transform with a gain folded in, which this branch took before, and dropped
     * the user's 0 / 2 to the DNG route without a word. {@code colorMethod} 1 «Характеристики» never takes it.
     */
    private static boolean legacyCaptured(boolean sameForward, int colorMethod, @androidx.annotation.Nullable ColorSpaceTransform cst,
                                          boolean oppoTuned) {
        if (oppoTuned || cst == null || nonZeroElements(cst) <= 4) return false;
        if (colorMethod != 2 && (colorMethod == 1 || !sameForward)) return false;
        if (rowsNormalisable(cst)) return true;
        Log.w(TAG, "the result's transform " + cst + " has a row summing to about 0 (|sum| < 0.25): not taken");
        return false;
    }

    private static int nonZeroElements(ColorSpaceTransform m) {
        int n = 0;
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) if (m.getElement(c, r).floatValue() != 0f) n++;
        return n;
    }

    /** Camera RGB -> ProPhoto as the white balance alone: diag(1 / whitePoint). */
    private void whiteBalanceOnly() {
        for (int i = 0; i < 9; i++) cameraToProPhoto[i] = i % 4 == 0 ? 1f / asShotNeutral[i / 3] : 0f;
    }

    /** The standard ProPhoto -> XYZ -> linear sRGB into {@code out}. */
    private static void proPhotoToSrgbOfProPhoto(float[] out) {
        Converter.multiply(Converter.sXYZtoSRGB, Converter.sProPhotoToXYZ, out);
    }

    /** {@code matrix} as the render's ProPhoto -> sRGB and a new {@link #CCT} carrying it. */
    private void renderWith(float[] matrix) {
        proPhotoToSrgb = matrix;
        colorTransform = new ColorCorrectionTransform();
        colorTransform.matrix = matrix;
    }

    /** P408: «Профиль матрицы» (pref_scam_hybrid_why_ccm_profile; 0 the system matrix, the shot's own route). */
    static int ccmPresetId() {
        try {
            return Integer.parseInt(PreferenceKeys.hybridString("why_ccm_profile", "0"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** P408: «Своя матрица 3x3» on (pref_scam_hybrid_why_ccm_override). */
    static boolean ccmOverride() {
        try {
            return PreferenceKeys.hybridSwitch("why_ccm_override", false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** P408: the matrix set by hand (pref_scam_hybrid_why_ccm_matrix, nine numbers by rows). */
    static String ccmMatrixText() {
        try {
            return PreferenceKeys.hybridString("why_ccm_matrix", CCM_IDENTITY);
        } catch (RuntimeException e) {
            return CCM_IDENTITY;
        }
    }

    /** P408: the hand-set matrix's default and reset value (the reference's «Сбросить матрицу к стандарту D65»: no change). */
    public static final String CCM_IDENTITY = "1, 0, 0, 0, 1, 0, 0, 0, 1";

    /** P370 (A1): |Duv| above which the DNG white estimate is the matrices' artefact (real X200U shot 0.012; the artefact 0.054-0.057). */
    static final float DUV_GUARD = 0.025f;
    /** P370 (A1): the light an unknown estimate takes its calibration weight at. */
    static final double DAYLIGHT_K = 6504.0;
    /** The identity as a ColorSpaceTransform (made when needed: a plain JVM test loads this class without Android). */
    private static ColorSpaceTransform identityTransform() {
        return new ColorSpaceTransform(new int[]{1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1});
    }

    /**
     * P370 (A1): the weight of calibration 2 at daylight (DAYLIGHT_K, 1/T between the two illuminants' DNG SDK kelvin, clamped):
     * what an unknown light renders with - calibration 1 on the phones whose first illuminant is D65 (vivo, OPPO), not the tungsten
     * one of a phone whose first is standard A (Pixel).
     */
    static double daylightWeight2(int ref1, int ref2) {
        final double t1 = com.scamera.app.gallery.dng.DngColor.illuminantTemperature(ref1, 2850);
        final double t2 = com.scamera.app.gallery.dng.DngColor.illuminantTemperature(ref2, 6500);
        if (t1 == t2) return 0;
        final double lo = Math.min(t1, t2), hi = Math.max(t1, t2), t = Math.max(lo, Math.min(hi, DAYLIGHT_K));
        final double low = (1 / t - 1 / hi) / (1 / lo - 1 / hi); // the weight of the low-temperature calibration
        return t2 <= t1 ? low : 1 - low;
    }

    /** P370 (A1): scam_dev.txt "dng_white_spec" (default 1): the DNG SDK's white estimate; 0 the former row-normalised one. */
    static boolean whiteSpec() {
        return PreferenceKeys.scamDevSwitch("dng_white_spec", true);
    }

    /** P370 (A1): the DNG white estimate of the camera's colour matrices and calibrations as read (DngColor.forCamera). */
    private com.scamera.app.gallery.dng.DngColor dngWhite(int ref1, int ref2) {
        return com.scamera.app.gallery.dng.DngColor.forCamera(toDouble(colorMatrix1), toDouble(colorMatrix2),
                toDouble(cameraCalibration1), toDouble(cameraCalibration2), ref1, ref2);
    }

    static double[] toDouble(float[] f) {
        final double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = f[i];
        return d;
    }

    /** A forward matrix with every column non-zero (the former "invertible" test); null: none. */
    private static boolean usableForward(@androidx.annotation.Nullable ColorSpaceTransform fm) {
        // P580: a non-finite element (a rational over 0) is no forward matrix either (it passed: NaN sums are not 0)
        if (!com.scamera.app.processing.color.PhotoMatrix.usableMatrix(fm)) return false;
        for (int i = 0; i < 3; i++) {
            float sum = 0.0f;
            for (int j = 0; j < 3; j++) sum += Math.abs(fm.getElement(i, j).floatValue());
            if (sum == 0.0f) return false;
        }
        return true;
    }

    /**
     * P370 (A3): camera RGB -> ProPhoto through the colour matrices alone (the DNG route without a forward matrix: the inverse of
     * the interpolated ColorMatrix, Bradford from the white to D50), its white at the forward route's level (Y of the white = the
     * largest component of CC^-1 n); null when a matrix is a placeholder (CM . XYZ of its light = 1, 1, 1 or CM = XYZ -> sRGB),
     * the estimate is unusable or its white is more than DUV_GUARD off the locus.
     */
    @androidx.annotation.Nullable
    private float[] colourMatrixRoute(int ref1, int ref2, @androidx.annotation.Nullable com.scamera.app.gallery.dng.DngColor dc) {
        try {
            if (placeholderColorMatrix(colorMatrix1, ref1) || placeholderColorMatrix(colorMatrix2, ref2)) return null;
            if (dc == null) dc = dngWhite(ref1, ref2);
            if (!dc.usable()) return null;
            final double[] n = toDouble(asShotNeutral);
            final double[] xy = dc.estimateXy(n);
            if (xy == null) return null;
            final double[] cd = com.scamera.app.gallery.dng.DngColor.cctDuv(xy);
            if (!(Math.abs(cd[1]) <= DUV_GUARD)) return null;
            final double[] c2x = dc.cameraToXyzD50(n);
            final float[] cc = new float[9], ccInv = new float[9];
            final double[] part = dc.cameraPartAt(cd[0]);
            for (int i = 0; i < 9; i++) cc[i] = (float) part[i];
            if (!Converter.invert(cc, ccInv)) return null;
            double target = 0;
            for (int r = 0; r < 3; r++) target = Math.max(target, ccInv[r * 3] * n[0] + ccInv[r * 3 + 1] * n[1] + ccInv[r * 3 + 2] * n[2]);
            final double y = c2x[3] * n[0] + c2x[4] * n[1] + c2x[5] * n[2];
            if (!(y > 1e-9) || !(target > 0)) return null;
            final float[] toXyz = new float[9], out = new float[9];
            for (int i = 0; i < 9; i++) toXyz[i] = (float) (c2x[i] * target / y);
            Converter.multiply(Converter.sXYZtoProPhoto, toXyz, out);
            for (float v : out) if (!Float.isFinite(v)) return null;
            return out;
        } catch (RuntimeException e) {
            Log.w(TAG, "the colour matrices alone: " + e);
            return null;
        }
    }

    /** XYZ (Y 1) of the standard illuminants the placeholder test knows; others on the Planckian locus at the DNG SDK's kelvin. */
    private static double[] illuminantXyz(int code) {
        switch (code) {
            case 21: return new double[]{0.95047, 1, 1.08883};
            case 17: case 3: return new double[]{1.09850, 1, 0.35585};
            case 23: return new double[]{0.96422, 1, 0.82521};
            case 20: return new double[]{0.95682, 1, 0.92149};
            case 22: return new double[]{0.94972, 1, 1.22638};
            default: {
                final double t = com.scamera.app.gallery.dng.DngColor.illuminantTemperature(code, 6504);
                final double[] xy = com.scamera.app.gallery.dng.DngColor.xyFromCctDuv(t, 0);
                return new double[]{xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]};
            }
        }
    }

    /** XYZ (D65) -> linear sRGB, the matrix a placeholder ColorMatrix copies. */
    private static final double[] XYZ_TO_SRGB_D65 = {3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252};

    /**
     * P370 (A3): a ColorMatrix that describes no sensor: it maps the white of its own illuminant to (1, 1, 1) within 2 % (camera
     * RGB taken as white-balanced sRGB), or it is XYZ -> sRGB itself (D65 or the D50-adapted one, within 0.01 a component).
     * An absent (all zero) matrix counts as one.
     */
    static boolean placeholderColorMatrix(@androidx.annotation.Nullable float[] cm, int illuminant) {
        if (cm == null || cm.length < 9) return true;
        boolean zero = true;
        for (float v : cm) zero &= v == 0f;
        if (zero) return true;
        final double[] xyz = illuminantXyz(illuminant);
        final double[] v = new double[3];
        for (int r = 0; r < 3; r++) v[r] = cm[r * 3] * xyz[0] + cm[r * 3 + 1] * xyz[1] + cm[r * 3 + 2] * xyz[2];
        final double lo = Math.min(v[0], Math.min(v[1], v[2])), hi = Math.max(v[0], Math.max(v[1], v[2]));
        if (lo > 0 && hi / lo <= 1.02) return true;
        boolean d65 = true, d50 = true;
        for (int i = 0; i < 9; i++) {
            d65 &= Math.abs(cm[i] - XYZ_TO_SRGB_D65[i]) < 0.01;
            d50 &= Math.abs(cm[i] - Converter.sXYZtoSRGB[i]) < 0.01;
        }
        return d65 || d50;
    }

    /**
     * P370 (A3): the DNG camera profile (no JPEG colour, P243 off) carries the forward matrices - not when they are the sRGB -> XYZ
     * placeholder and the colour matrices are real (a raw converter then renders through the ColorMatrix, as the DNG spec does
     * without a ForwardMatrix, instead of taking camera RGB as sRGB).
     */
    public boolean dngForwardMatrices() {
        return !(OppoTunedColor.placeholderForward(forwardMatrix1) && OppoTunedColor.placeholderForward(forwardMatrix2)
                && !placeholderColorMatrix(colorMatrix1, refIlluminant1) && !placeholderColorMatrix(colorMatrix2, refIlluminant2));
    }

    /**
     * P370 (review): a colour matrix the DNG may carry - finite, not all zero (a camera without SENSOR_COLOR_TRANSFORM1 / 2 leaves
     * them zero, A5) and invertible; DngCreator writes no other, and the shot then carries the JPEG's own profile (DngColour).
     */
    public static boolean dngColourMatrixUsable(@androidx.annotation.Nullable float[] cm) {
        if (cm == null || cm.length < 9) return false;
        for (float v : cm) if (!Float.isFinite(v)) return false;
        final double det = (double) cm[0] * ((double) cm[4] * cm[8] - (double) cm[5] * cm[7])
                - (double) cm[1] * ((double) cm[3] * cm[8] - (double) cm[5] * cm[6])
                + (double) cm[2] * ((double) cm[3] * cm[7] - (double) cm[4] * cm[6]);
        return Math.abs(det) > 1e-9 && Double.isFinite(det);
    }

    /** P370 (review): the camera's ColorMatrix1 is one a DNG may carry ({@link #dngColourMatrixUsable}). */
    public boolean dngColourMatrices() {
        return dngColourMatrixUsable(colorMatrix1);
    }

    /** Every row of the matrix sums to at least 0.25 in magnitude (Parameters.normalize scales it). */
    private static boolean rowsNormalisable(@androidx.annotation.Nullable ColorSpaceTransform cst) {
        if (cst == null) return false;
        final float[] m = rowMajor(cst);
        for (int r = 0; r < 3; r++) {
            final float s = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
            if (!(Math.abs(s) >= 0.25f) || !Float.isFinite(s)) return false;
        }
        return true;
    }

    /** P370 (B1): the Qualcomm AWB colour temperature label of a result (vivo and OPPO report it). */
    static final CaptureResult.Key<int[]> AWB_CCT_LABEL =
            new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlCCT", int[].class);
    /** P370 (B1): the cameras whose DNG colour tags were logged in this process. */
    private static final java.util.Set<String> TAGS_LOGGED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * P370 (B1): what the README and the log say about the result that supplies the colour: its timestamp, ISO, AWB label, its
     * transform as reported (row-major) and the camera's DNG colour tags as rationals (logged once per camera).
     */
    private void colourFrame(CaptureResult result) {
        final Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        colourFrameTimestamp = ts == null ? 0L : ts;
        colourFrameIso = iso == null ? 0 : iso;
        int[] label;
        try {
            label = result.get(AWB_CCT_LABEL);
        } catch (RuntimeException noTag) {
            label = null;
        }
        awbLabelK = com.scamera.app.processing.color.PlainColourMemory.label(label);
        final ColorSpaceTransform cst = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        resultCst = cst == null ? null : rowMajor(cst);
        final CameraCharacteristics c = camera(); // the shot's characteristics (resolveColour's)
        if (c == null) return;
        final StringBuilder t = new StringBuilder();
        t.append("illuminants ").append(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).append(" / ")
                .append(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2));
        final Object[][] tags = {{"CM1", CameraCharacteristics.SENSOR_COLOR_TRANSFORM1}, {"CM2", CameraCharacteristics.SENSOR_COLOR_TRANSFORM2},
                {"FM1", CameraCharacteristics.SENSOR_FORWARD_MATRIX1}, {"FM2", CameraCharacteristics.SENSOR_FORWARD_MATRIX2},
                {"CC1", CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1}, {"CC2", CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2}};
        boolean placeholderFm = true;
        for (Object[] tag : tags) {
            @SuppressWarnings("unchecked") final ColorSpaceTransform m = c.get((CameraCharacteristics.Key<ColorSpaceTransform>) tag[1]);
            t.append(' ').append(tag[0]).append(' ').append(m == null ? "none" : rationals(m));
            if (((String) tag[0]).startsWith("FM")) placeholderFm &= m != null && OppoTunedColor.placeholderForward(rowMajor(m));
        }
        t.append(placeholderFm ? "; forward matrices: the sRGB -> XYZ placeholder" : "");
        colourTags = t.toString();
        if (TAGS_LOGGED.add(physicalID + "|" + colourTags)) Log.i(TAG, "camera " + physicalID + " DNG colour tags: " + colourTags);
    }

    private static float[] rowMajor(ColorSpaceTransform m) {
        final float[] out = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) out[r * 3 + c] = m.getElement(c, r).floatValue();
        return out;
    }

    private static String rationals(ColorSpaceTransform m) {
        final Rational[] r = new Rational[9];
        m.copyElements(r, 0);
        final StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < 9; i++) s.append(i == 0 ? "" : i % 3 == 0 ? "; " : ", ").append(r[i].getNumerator()).append('/').append(r[i].getDenominator());
        return s.append(']').toString();
    }

    /**
     * P370 (B1): camera RGB -> linear sRGB of the render before the «Цвет» look, row-major: CCT.matrix x sensorToProPhoto (the
     * shaders multiply the white-balanced input by the neutral first: camera RGB = ScamRgb x whitePoint). Null before
     * resolveColour. SceneIlluminant keeps the post-white-balance CCT.matrix (this product would apply the white balance twice).
     */
    @androidx.annotation.Nullable
    public float[] cameraToSrgb() {
        if (colorTransform == null || colorTransform.matrix == null) return null;
        final float[] out = new float[9];
        Converter.multiply(colorTransform.matrix, cameraToProPhoto, out);
        return out;
    }

    /** P370 (B1): one line of the shot's colour route (log). */
    public String colourSummary() {
        return String.format(java.util.Locale.ROOT, "colour: %s%s, scene %.0f K (%s%s), neutral %s, frame ts %d ISO %d AWB label %d K",
                colourSource, colourSourceWhy.isEmpty() ? "" : " (" + colourSourceWhy + ")", sceneCct, sceneCctSource,
                Float.isNaN(sceneDuv) ? "" : String.format(java.util.Locale.ROOT, ", Duv %.4f", sceneDuv), Arrays.toString(asShotNeutral),
                colourFrameTimestamp, colourFrameIso, awbLabelK);
    }

    /** P212g (W7): the log text of where a mosaic shot's colour matrix came from. */
    static String mosaicMatrixSource(boolean dcp, boolean oppoTuned,
                                     @androidx.annotation.Nullable com.scamera.app.processing.color.PlainColourMemory.Lookup table,
                                     boolean isp, boolean asked, boolean wanted, String camera, float[] neutral) {
        return mosaicMatrixSource(dcp, oppoTuned, table, isp, false, asked, wanted, camera, neutral);
    }

    static String mosaicMatrixSource(boolean dcp, boolean oppoTuned,
                                     @androidx.annotation.Nullable com.scamera.app.processing.color.PlainColourMemory.Lookup table,
                                     boolean isp, boolean placeholder, boolean asked, boolean wanted, String camera, float[] neutral) {
        if (dcp) return "the active DCP profile";
        if (oppoTuned) return "the OPPO tuned matrix";
        if (table != null) return table.how;
        if (isp) return placeholder ? "the ISP transform of the result (the DNG forward matrices are the sRGB -> XYZ placeholder, P315)"
                : "the ISP transform of the result (it fits the camera's own neutral)";
        if (!com.scamera.app.processing.color.PlainColourMemory.on()) return "the DNG matrices (mosaic_ccm_table 0)";
        if (!wanted) return "the DNG matrices (not a FULLRES shot: mosaic_ccm_table 1 gives the plain-session table to FULLRES only)";
        return asked ? String.format(java.util.Locale.ROOT, "the DNG matrices (camera %s's plain table has no bin near ln(r/b) %.3f)", camera,
                Math.log(neutral[0] / neutral[2])) : "the DNG matrices";
    }

    /**
     * P98: an identity colorCorrection.transform (OPPO Find X7 Ultra) is replaced by the OPPO ISP matrix of the same frame
     * (com.oplus.custom.ccm) when there is a valid one; any other transform is kept. scam_dev "oplus_ccm 0" turns it off.
     */
    private static ColorSpaceTransform oplusCcmInstead(ColorSpaceTransform cst, CaptureResult result) {
        if (cst == null || result == null || !PreferenceKeys.scamDevSwitch("oplus_ccm", true)) return cst;
        Rational[] r = new Rational[9];
        cst.copyElements(r, 0);
        float[] m = new float[9];
        for (int i = 0; i < 9; i++) m[i] = r[i].floatValue();
        if (!com.scamera.app.processing.color.OplusCcm.identity(m)) return cst;
        float[] oplus;
        try {
            oplus = com.scamera.app.processing.color.OplusCcm.parse(result.get(new CaptureResult.Key<>(
                    com.scamera.app.processing.color.OplusCcm.TAG_NAME, byte[].class)));
        } catch (RuntimeException noTag) {
            return cst;
        }
        if (oplus == null) return cst;
        int[] q = new int[18];
        for (int i = 0; i < 9; i++) { q[2 * i] = Math.round(oplus[i] * 1024f); q[2 * i + 1] = 1024; }
        Log.d(TAG, "Identity colour transform replaced by the OPPO ISP matrix " + Arrays.toString(oplus));
        return new ColorSpaceTransform(q);
    }

    /**
     * The reported transform of a vivo SCAM HDR capture: a full matrix whose rows sum to about 1 (white stays white). P212g: public
     * (the plain-session colour table takes what this branch would).
     */
    public static boolean ispCcmUsable(ColorSpaceTransform cst) {
        return cst != null && ispCcmGates() && ispCcmShape(cst);
    }

    /** P370 (A3): the switches the ISP branch obeys: SCAM on and scam_dev "isp_ccm" not 0. */
    public static boolean ispCcmGates() {
        return PreferenceKeys.isScamEnabled() && PreferenceKeys.routeInternalValue("isp_ccm", 1f) > 0f;
    }

    /**
     * P370 (A3): a transform shaped as an ISP colour matrix - at least 7 non-zero elements, each row summing to 0.85 .. 1.15 (white
     * stays white), each diagonal element at least 0.8. False for null.
     */
    public static boolean ispCcmShape(@androidx.annotation.Nullable ColorSpaceTransform cst) {
        if (cst == null) return false;
        Rational[] r = new Rational[9];
        cst.copyElements(r, 0);
        int nonZero = 0;
        for (int i = 0; i < 9; i++) if (r[i].floatValue() != 0f) nonZero++;
        if (nonZero < 7) return false;
        for (int row = 0; row < 3; row++) {
            float sum = r[row * 3].floatValue() + r[row * 3 + 1].floatValue() + r[row * 3 + 2].floatValue();
            if (sum < 0.85f || sum > 1.15f || r[row * 3 + row].floatValue() < 0.8f) return false;
        }
        return true;
    }

    /**
     * Every row scaled to sum to 1 (white stays white). P370 (A5): a row summing to less than 0.25 in magnitude (or not finite) is
     * kept as it is - its division gave inf - and the result is false.
     */
    static boolean normalize(float[] in) {
        // P370 (A2): the rule the RAW viewfinder shares (PhotoMatrix)
        return com.scamera.app.processing.color.PhotoMatrix.normaliseRows(in);
    }

    /**
     * P576: the shot's frames were cut to {@code plan.area} of the {@link #rawSize} frame (SabreCrop): the RAW size, the lens
     * shading map (the window's part, the same grid), the hot pixels and the face boxes follow the window; the RAW crop area is
     * the window in the frame as captured (P162's area and the window together). The levels, the white balance, the noise model
     * and the colour stay the whole frame's (the window keeps the frame's CFA phase: its origin is on whole colour periods).
     */
    public void applySabreCrop(com.scamera.app.processing.processor.SabreCrop.Plan plan) {
        if (plan == null || rawSize == null) return;
        final com.scamera.app.processing.RawCrop.Area a = plan.area;
        final int fw = rawSize.x, fh = rawSize.y;
        if (shadingMap != null && shadingGrid != null && shadingGrid.x >= 2 && shadingGrid.y >= 2)
            shadingMap = com.scamera.app.processing.RawCrop.cropGainMap(shadingMap, shadingGrid.x, shadingGrid.y, fw, fh, a);
        if (hotSites != null) hotSites = keptHotPixels(hotSites, a);
        if (portraitFaces != null && portraitFaces.length > 0)
            portraitFaces = com.scamera.app.processing.portrait.PortraitFaces.crop(portraitFaces,
                    a.x / (float) fw, a.y / (float) fh, a.width / (float) fw, a.height / (float) fh);
        if (rawCrop != null) {
            rawCrop = new com.scamera.app.processing.RawCrop.Area(rawCrop.x + a.x, rawCrop.y + a.y, a.width, a.height);
        } else {
            rawCrop = a;
            rawCropFull = new Point(fw, fh);
        }
        rawSize = new Point(a.width, a.height);
        sabreCrop = plan;
    }

    /** P162: the hot pixels inside {@code area}, in its own coordinates. */
    public static Point[] keptHotPixels(Point[] hot, com.scamera.app.processing.RawCrop.Area area) {
        java.util.ArrayList<Point> kept = new java.util.ArrayList<>();
        for (Point p : hot) {
            if (p == null) continue;
            final int x = p.x - area.x, y = p.y - area.y;
            if (x >= 0 && y >= 0 && x < area.width && y < area.height) kept.add(new Point(x, y));
        }
        return kept.toArray(new Point[0]);
    }

    /**
     * The post pipeline's own copy of the shot's development state: the arrays and points are cloned (the next frame's pass must
     * not reach into a shot being developed), the noise model, the colour transform and the characteristics are shared (never
     * changed after they are made). Fields not listed keep their defaults, as the post pipeline never reads them.
     */
    protected Parameters postCopy() {
        final Parameters c = new Parameters();
        c.multiFrameCount = multiFrameCount;
        c.cfaPattern = cfaPattern;
        c.baseCfaPattern = baseCfaPattern;
        // levels and the light
        c.dynamicBlackUsed = dynamicBlackUsed;
        c.cfaBlack = cfaBlack.clone();
        c.asShotNeutral = asShotNeutral.clone();
        c.sceneCct = sceneCct;
        c.whiteLevel = whiteLevel;
        c.levelBits = levelBits;
        c.levelSource = levelSource;
        c.finishSnr = finishSnr;
        c.finishNoiseS = finishNoiseS;
        c.finishNoiseO = finishNoiseO;
        c.mosaicBlock = mosaicBlock;
        // lens shading over the active area
        c.shadingMapUsed = shadingMapUsed;
        c.shadingGrid = new Point(shadingGrid);
        c.shadingMap = shadingMap.clone();
        c.activeArea = new Rect(activeArea);
        // what was done to the frames before the merge
        c.rawBinning = rawBinning;
        c.dcgShot = dcgShot;
        c.loficLog2 = loficLog2;
        c.dcgIsoScale = dcgIsoScale;
        c.yuvAeScale = yuvAeScale;
        c.dcgNoise = dcgNoise == null ? null : dcgNoise.clone();
        c.rawCrop = rawCrop;
        c.rawCropFull = rawCropFull == null ? null : new Point(rawCropFull);
        c.rawCropSensorShiftY = rawCropSensorShiftY;
        c.hotSites = hotSites.clone();
        // the portrait look
        c.portraitEnabled = portraitEnabled;
        c.portraitSkinWarmth = portraitSkinWarmth;
        c.portraitFaceDetected = portraitFaceDetected;
        c.portraitFaces = portraitFaces.clone();
        // colour
        c.proPhotoToSrgb = proPhotoToSrgb.clone();
        c.cameraToProPhoto = cameraToProPhoto.clone();
        c.colorTransform = colorTransform;
        // the lens and the moment, for the EXIF
        c.focalMm = focalMm;
        c.focalLengthOfShot = focalLengthOfShot;
        c.apertureOfShot = apertureOfShot;
        c.lensFocalLengths = lensFocalLengths;
        c.lensApertures = lensApertures;
        c.lensFacing = lensFacing;
        c.captureTimeMs = captureTimeMs;
        c.rotationDeg = rotationDeg;
        c.shotCharacteristics = shotCharacteristics;
        c.cameraIdAtPress = cameraIdAtPress;
        return c;
    }

    @NonNull
    @Override
    public String toString() {
        return description();
    }

    /**
     * P618: the ImageDescription of the shot's photo and DNG (ParseExif adds the route, the DNG what it holds). PhotonCamera's
     * "parameters:" list it replaces named settings SCAMERA no longer has («Noise Merging» always 1.00, «Align»), a frame count
     * nothing sets (FrameNumberSelector) and a "0.98(30966)" version the Software tag now carries in both files.
     */
    public String description() {
        StringBuilder b = new StringBuilder("SCAMERA");
        if (multiFrameCount > 0) b.append("\n Frames=").append(multiFrameCount);
        b.append("\n Camera=").append(cameraID);
        if (colourSource != null && !colourSource.isEmpty()) b.append("\n Colour=").append(colourSource);
        return b + filmLine(filmRecipe);
    }

    /** P618: the shot's focal length for EXIF: the shot's own, else the camera's declared one; NaN when neither is known. */
    public double exifFocalLength() {
        if (focalLengthOfShot && focalMm > 0) return focalMm;
        return lensFocalLengths != null && lensFocalLengths.length > 0 && lensFocalLengths[0] > 0 ? lensFocalLengths[0] : Double.NaN;
    }

    /** P618: the shot's f-number for EXIF: the shot's own, else the camera's declared one; NaN when neither is known. */
    public double exifAperture() {
        if (apertureOfShot && fNumber > 0) return fNumber;
        return lensApertures != null && lensApertures.length > 0 && lensApertures[0] > 0 ? lensApertures[0] : Double.NaN;
    }

    /** P380: "\n Film=<recipe>" with a film, "" without one. */
    public static String filmLine(@Nullable String recipe) {
        return recipe == null || recipe.isEmpty() ? "" : "\n Film=" + recipe;
    }
}
