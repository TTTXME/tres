package com.scamera.app.capture;
/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.scamera.app.util.Allocator;
import com.scamera.app.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.scamera.app.R;
import com.scamera.app.api.Camera2ApiAutoFix;
import com.scamera.app.api.CameraEvents;
import com.scamera.app.api.CameraManager2;
import com.scamera.app.api.CameraMode;
import com.scamera.app.api.CameraReflectionApi;
import com.scamera.app.api.Settings;
import com.scamera.app.api.VendorTagUtils;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.circularbarlib.api.ManualModeConsole;
import com.scamera.app.control.GyroBurst;
import com.scamera.app.control.TouchFocus;
import com.scamera.app.manual.ParamController;
import com.scamera.app.processing.ImageSaver;
import com.scamera.app.processing.parameters.ExposureIndex;
import com.scamera.app.processing.parameters.IsoExpoSelector;
import com.scamera.app.processing.parameters.ResolutionSolution;
import com.scamera.app.processing.LiveRawFrame;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.processing.RingFrame;
import com.scamera.app.settings.SensorConfigInjector;
import com.scamera.app.settings.annotations.SensorConfig;
import com.scamera.app.ui.camera.CameraFragment;
import com.scamera.app.ui.camera.viewmodel.TimerFrameCountViewModel;
import com.scamera.app.ui.camera.views.viewfinder.GLPreview;
import com.scamera.app.util.log.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import android.media.Image;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.scamera.app.processing.ImageFrame;
import com.scamera.app.processing.RingArenaMove;
import com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst;
import com.scamera.app.processing.ImageSaverSelector;
import com.scamera.app.processing.SaverImplementation;
import com.scamera.app.util.Lang;

import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
import static android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH;
import static android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_REGIONS;
import static android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AF_REGIONS;
import static android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE;
import static android.hardware.camera2.CaptureRequest.FLASH_MODE;

/**
 * SCAMERA's capture core: opens the lens a module asks for, builds its Camera2 session (RAW ring, viewfinder streams, vendor
 * keys), keeps the preview request and 3A running, and turns a shutter press into the frames the processing merges.
 * <p>
 * The camera screen hears about everything through its {@link CameraEvents}.
 */
public class CaptureController {
    public static final int RAW_STREAM_FORMAT = ImageFormat.RAW_SENSOR;
    public static final int YUV_STREAM_FORMAT = ImageFormat.YUV_420_888;
    private static final String TAG = "CaptureController";
    private final ExecutorService processExecutor;

    // The shutter's 3A wait (mShotState): idle preview -> AF lock -> AE precapture start -> AE precapture end -> shot.
    private static final int SHOT_IDLE = 0;
    private static final int SHOT_AF_LOCK = SHOT_IDLE + 1;
    private static final int SHOT_AE_START = SHOT_IDLE + 2;
    private static final int SHOT_AE_END = SHOT_IDLE + 3;
    private static final int SHOT_FIRED = SHOT_IDLE + 4;
    private static final int SHOT_CLOSED = SHOT_IDLE + 5;
    /** P590: a flash shot waits for its AE precapture (FlashMode.Precapture), then takes its frame (takeStill). */
    private static final int SHOT_FLASH_AE = SHOT_IDLE + 6;
    /** How long the shutter waits for AF / AE to settle before it fires anyway, ms. */
    private static final long THREE_A_WAIT_MS = 100;

    /** The repeating requests ask for the sensor's maximum-resolution mode (a FULLRES way that streams it). */
    private boolean useMaximumResolutionKey;

    /** Characteristics of every camera id this run has read (logical and physical), by id. */
    /**
     * P680: filled off the main thread (the constructor's {@link #mCharacteristicsRead}) while the main thread and the open read
     * it: concurrent; a null id or value is no entry (as a HashMap's get of a missing one answered).
     */
    private final Map<String, CameraCharacteristics> mCameraCharacteristicsMap = new CharacteristicsMap();

    /** P680: a ConcurrentHashMap that takes a null key or value as no entry. */
    static final class CharacteristicsMap extends java.util.concurrent.ConcurrentHashMap<String, CameraCharacteristics> {
        @Override public CameraCharacteristics get(Object key) { return key == null ? null : super.get(key); }
        @Override public boolean containsKey(Object key) { return key != null && super.containsKey(key); }
        @Override public CameraCharacteristics remove(Object key) { return key == null ? null : super.remove(key); }
        @Override public CameraCharacteristics put(String key, CameraCharacteristics value) {
            if (key == null) return null;
            return value == null ? super.remove(key) : super.put(key, value);
        }
    }
    /** The open lens's characteristics and its latest still metadata, read across the app. */
    public static volatile CameraCharacteristics mCameraCharacteristics;
    public static volatile CaptureResult mCaptureResult;
    public static volatile CaptureRequest mCaptureRequest;
    /** The newest viewfinder result and the request that made it (publishPreview). */
    public static volatile CaptureResult mPreviewCaptureResult;
    public static volatile CaptureRequest mPreviewCaptureRequest;
    /**
     * Format of the extra ImageReader; its JPEG sizes also bound
     * the RAW size choice. Always JPEG since «Формат превью» (which only added an unread stream to the plain session) is gone.
     */
    public static final int mPreviewTargetFormat = ImageFormat.JPEG;

    /** The module's «Тип сессии камеры» (0 regular; see sessionOperationMode for what a session runs). */
    @SensorConfig(title = "Session Type", min = 0, max = 65535, step = 0, defaultValue = 0,
            description = "Режим сессии камеры: 0 — обычный, 32768–65535 — режимы производителя (неверный может не открыть камеру). В FULLRES всегда 0. Применяется при следующем открытии камеры.")
    public int sessionType;
    /**
     * P211 (owner's X7 Ultra, 2026-10-17/18: «Тип сессии камеры» 32777 with FULLRES on rebooted the phone - kernel panic «Unable to
     * handle kernel paging request», reproduced on PHY110): the operation mode a session runs. The module's «Тип сессии камеры»
     * only as Android takes it (0 normal; 0x8000..0xFFFF vendor modes; 1 = constrained high speed and 2..0x7FFF are refused for
     * our streams) and never in a FULLRES session, whose sensor mode is set by its own keys (the vivo forceSensorMode, the OPPO
     * agingtest mode); a vendor mode the camera refused falls back to 0 for this camera (this run).
     */
    static int sessionOperationMode(int setting, boolean fullRes) {
        if (fullRes || setting < 0x8000 || setting > 0xFFFF) return 0;
        return setting;
    }
    /** P211: the module's vendor operation mode the running session was configured with (0: none). */
    private volatile int mVendorOperationMode;
    /** P211: cameras whose session refused the module's vendor operation mode (this run): they run mode 0. */
    private static final java.util.Set<String> sVendorModeRefused = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** P230: the DCG route's operation mode the running session was configured with (Xiaomi 15 Ultra 0x9203; 0: none). */
    private volatile int mDcgOperationMode;
    /** P230: the running session is a DCG route's that sets its keys on every request (Xiaomi 15 Ultra; X200 Ultra Telegram). */
    private volatile boolean mDcgRequestKeys;
    /**
     * P214g: the session settings (settings/SessionKeys: the @SensorConfig fields above, the module's vendor keys, the
     * preferences the build reads) the last session was built with, and the module scope of its sensor keys; null before the
     * first session. Kept over closeCamera, so the camera screen can say on the way back from the settings what the new
     * session takes, and can tell whether a session still running beside the settings (multi-window) needs a restart.
     */
    private volatile Map<String, String> mSessionSettings;
    private volatile String mSessionSettingsScope;

    /** P214g: what this session is built with (startSession, right after the @SensorConfig injection). */
    private void recordSessionSettings() {
        try {
            String scope = com.scamera.app.settings.ModuleSensorSettings.runtimeScope(physicalID);
            mSessionSettings = com.scamera.app.settings.SessionKeys.snapshot(
                    ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getAll(), scope);
            mSessionSettingsScope = scope;
        } catch (RuntimeException e) {
            mSessionSettings = null;
            Log.w(TAG, "session settings not recorded: " + e);
        }
    }

    /**
     * P214g: the session settings whose value differs from the one the last session was built with (empty when none did or
     * no session was built yet), sorted.
     */
    public List<String> sessionSettingsChanged() {
        final Map<String, String> built = mSessionSettings;
        List<String> changed = null;
        if (built != null) try {
            final Map<String, String> now = com.scamera.app.settings.SessionKeys.snapshot(
                    ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getAll(), mSessionSettingsScope);
            changed = com.scamera.app.settings.SessionKeys.changed(built, now);
        } catch (RuntimeException unreadable) {
            Log.w(TAG, "session settings not compared: " + unreadable);
        }
        return changed != null ? changed : Collections.emptyList();
    }

    /** P214g: a configured session runs now (not closed, not opening): a restart has a session to replace. */
    public boolean sessionRunning() {
        return isCameraResumed && mCaptureSession != null;
    }

    /** The module's «Оптическая стабилизация»: 0 on, 1 auto (off on a tripod), 2 off. */
    @SensorConfig(title = "OIS Mode", defaultValue = 0, entryValues = {"0", "1", "2"}, entries = {"On", "Auto", "Off"},
            description = "Оптическая стабилизация. Авто выключает её на штативе, чтобы кадр не плыл.")
    public int oisMode;

    /**
     * P266 (owner, 2026-10-19, a per-lens camera setup to copy: «Дополнительная поверхность ISP: Выкл. - невидимый поток Android,
     * который может влиять на доступность режимов сенсора»): the ISP's viewfinder stream in the session. Auto: out of the
     * session for a module that forces a sensor mode (vivo.control.forceSensorMode: the X300 Ultra's HAL kept its own mode with
     * the stream there, 2026-10-19 log), in it otherwise. Off: the RAW stream alone, the viewfinder developed from the RAW.
     */
    @SensorConfig(title = "ISP Surface", defaultValue = 0, entryValues = {"0", "1", "2", "3"}, entries = {"Auto", "On", "Off", "Fake off"},
            description = "Поток ISP в сессии камеры. Он может мешать режимам сенсора (ISZ, DCG): без него видоискатель строится из RAW. Авто — без потока ISP для модулей с режимом сенсора. Фиктивное откл. — поток в сессии, но камера его не заполняет.")
    public int ispSurface;

    /**
     * P266: the RAW frame rate asked of the camera (the AE target range [f, f] in every request and in the session's parameters):
     * 0 = the lens's own (the rates Camera2 declares, the viewfinder's range); a rate of its own may pick an undocumented sensor
     * mode.
     */
    @SensorConfig(title = "RAW Frame Rate", min = 0, max = 240, step = 1, defaultValue = 0,
            description = "Частота кадров RAW, к/с. 0 — по умолчанию для объектива; своё значение может выбрать недокументированный режим сенсора.")
    public int rawFps;

    /**
     * P266: the RAW viewfinder's resolution, its longest side (the RAW's aspect kept, the photo unchanged): 0 = auto, -1 = the full
     * frame.
     */
    @SensorConfig(title = "RAW Viewfinder Size", defaultValue = 0,
            entryValues = {"0", "640", "960", "1440", "1920", "2880", "-1"}, entries = {"Auto", "640", "960", "1440", "1920", "2880", "Full"},
            description = "Размер кадра видоискателя из RAW по длинной стороне; соотношение сторон RAW сохраняется, фото не меняется. Меньше — быстрее и холоднее.")
    public int rawViewfinder;

    /** P266: the session has no ISP viewfinder stream (ispSurface): the RAW stream alone, the RAW viewfinder. */
    private boolean mIspSurfaceOff;
    /**
     * P315: the repeating preview request targets the ISP stream ({@code surface}). The series frames (P90 series_viewfinder) and
     * the AE restore frame (P54c) touch that stream only then: the OPPO Find X8 Ultra's 2x ISZ module (sensor mode 8, «Поток ISP»
     * «Фиктивное откл.» - the stream in the session, never started) failed every request of its series that targeted it and its
     * HAL died (owner's log 2026-10-21 13:33 / 13:36); the vivo X300 Ultra's ISZ session (no ISP stream in it) got the stream
     * added to its preview builder by the AE restore frame's re-add (P54c) and every later preview request failed («CaptureRequest
     * contains unconfigured Input/Output Surface», owner's log 2026-10-21 07:26: the camera restarted ~6 s after each shot).
     */
    private volatile boolean mRepeatingIsp;

    /** P266: the module's own fixed RAW frame rate as an AE range, null for the lens's own. */
    @Nullable
    private Range<Integer> moduleFps() {
        // not in the SDK FULLRES session: its own automatic range (a fixed rate gave empty RAW frames on the PD2454)
        return rawFps > 0 && mVivoSdkRawSession == null ? new Range<>(rawFps, rawFps) : null;
    }

    /** The still format this run captures in (RAW unless a camera has no RAW stream: YUV then). */
    private static int mTargetFormat = RAW_STREAM_FORMAT;
    private final ParamController paramController;
    private final UnspektraCameraAe unspektraAe = new UnspektraCameraAe(this);

    public UnspektraCameraAe unspektraAe() { return unspektraAe; }
    /** Tap-to-focus / metering (null until the viewfinder is ready). */
    public TouchFocus mTouchFocus;
    /** P42: face detection and tracking AF (null until the viewfinder is ready). */
    public volatile com.scamera.app.control.subject.SubjectFocus mSubjectFocus;

    private final CameraEvents cameraEventsListener;
    /** Held from an open request until the device answers (openSettled), so closeCamera waits for an open in flight. */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    /** An open is under way (requested, not answered yet): a second request is dropped, not queued. */
    private final AtomicBoolean mCameraOpening = new AtomicBoolean();
    /**
     * The camera screen is in the foreground (screenResumed .. closeCamera): only then may a camera be opened or kept; an
     * open answered after the screen left is closed again at once.
     */
    private volatile boolean isCameraResumed;
    private final CameraManager mCameraManager;
    private final CameraManager2 mLensDirectory;
    private final Activity activity;

    // The newest viewfinder exposure: written on the camera thread, read by the manual controls, the AE and the processing.
    public volatile long mPreviewExposureTime;
    public volatile int mPreviewIso;
    public volatile Rational[] mPreviewTemp;
    public volatile ColorSpaceTransform mColorSpaceTransform;

    /** The open camera (null while none is). */
    public volatile CameraDevice mCameraDevice;
    /** The camera thread's handler: session, request and image callbacks run there. */
    public volatile Handler mBackgroundHandler;
    /** The JPEG / YUV reader beside the RAW one (null when the session has none). */
    public volatile ImageReader mImageReaderPreview;
    public volatile ImageReader mImageReaderRaw;
    /**
     * P208: the viewfinder's RAW stream of a still-only FULLRES session (the X100 / X300 Ultra's 200 MP): the camera's own RAW size
     * in its normal mode; mImageReaderRaw then takes the 200 MP stills only.
     */
    private volatile ImageReader mImageReaderRawView;
    private ImageReader mVivoSdkJpeg;
    private volatile VivoSdkRawSession mVivoSdkRawSession;
    /** P275: the vivo SDK lease (VCF bypass, SDK RAW) an X300 Ultra ISZ module holds while its camera is open. */
    private volatile VivoSdkRawSession mIszLease;
    /** P640: the lease's open / close steps (the camera thread, the device's callbacks, the lease thread). */
    private final Object mIszLeaseLock = new Object();
    private volatile CameraDevice mVivoSdkRawDevice;
    private volatile VivoSdkFullResCapture mVivoSdkCapture;
    private String mVivoSdkCameraId;
    private long mVivoSdkPreviewLogMs;
    private final Object mVivoSdkLock = new Object();
    private HandlerThread mVivoSdkCallbackThread;
    private Handler mVivoSdkCallbackHandler;
    private volatile boolean mVivoSdkRestorePending;
    private int mVivoSdkRestoreAttempts;
    private volatile java.util.concurrent.CountDownLatch mVivoSdkClosed = new java.util.concurrent.CountDownLatch(0);
    /** The viewfinder's request as it is being edited, and the last one handed to setRepeatingRequest. */
    public volatile CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mRepeatingRequest;
    /** Where the shutter's 3A wait stands (SHOT_*). */
    public int mShotState = SHOT_IDLE;
    /** The lens's SENSOR_ORIENTATION and the shot's rotation from it and the gravity. */
    public volatile int mSensorOrientation;
    public volatile int cameraRotation;
    public boolean seriesRunning;
    public boolean seriesStarted;
    /** The gyro's motion during each frame of the shot in flight. */
    public volatile ArrayList<GyroBurst> burstShake;
    /** Takes the still frames to the processing. */
    public volatile ImageSaver mImageSaver;
    /** The shot's exposure products (s x ISO) by sensor time. */
    public volatile HashMap<Long, Double> exposureByFrame = new HashMap<>();
    private final java.util.concurrent.ConcurrentSkipListMap<Long,CaptureResult> rawMetadata =
            new java.util.concurrent.ConcurrentSkipListMap<>();
    private void rememberRawMetadata(CaptureResult result) {
        Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);
        if(timestamp==null || timestamp<=0) return;
        rawMetadata.put(timestamp,result);
        while(rawMetadata.size()>128) rawMetadata.pollFirstEntry();
    }
    public CaptureResult takeRawMetadata(long timestamp) { return rawMetadata.remove(timestamp); }


    private final ArrayDeque<RingFrame> mZslRingBuffer = new ArrayDeque<>();
    /** P91: the ring's memfd (null: the camera's own Images are held, as before or when no memfd is available). */
    private ZslRing mZslRing;
    private final Object mRingLock = new Object();
    private final java.util.LinkedHashMap<Long, TotalCaptureResult> mHexZslResults = new java.util.LinkedHashMap<>();
    private volatile boolean mZslCapturing = false;
    /**
     * True from the shutter press until the burst's frames have been handed to
     * processing. The raw viewfinder must not intercept frames during that
     * window: they belong to the shot.
     */
    private volatile boolean mShotInProgress = false;
    // Stock-like shot-to-shot: a finished SCAM burst is processed here while the
    // camera already takes the next one. No count limit: each queued shot holds
    // ~175 MB of RAW, so only free system memory bounds the queue.
    private static final ExecutorService SCAM_PROCESSING = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SCAMERA-SCAM-processing");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final java.util.concurrent.atomic.AtomicInteger sScamPending = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * P200 (owner, X7 / X100 Ultra 2026-10-17: «обработка с фулрезом не заканчивается»): FULLRES 50 MP merges waiting or running.
     * The app was killed for memory 1-3 min into each merge: the worker budgets 80 % of MemAvailable at its start, and the camera
     * then built a new 50 MP ZSL ring (up to 16 x 96 MB) and a plain one (66 x 24 MB). While one runs, no 50 MP ring is kept and
     * the plain ring holds SUSPENDED_RING frames (as during a 200 MP merge, P179).
     */
    private static final java.util.concurrent.atomic.AtomicInteger sFullResMerges = new java.util.concurrent.atomic.AtomicInteger();

    static boolean fullResMergeRunning() {
        return sFullResMerges.get() > 0;
    }
    private long mShutterGeneration;
    private boolean mShutterIszAspect4x3;
    /** P212f (W2 = M3): the hand-set white balance's gains at the press (null: automatic), the shot's white point on a mosaic photo. */
    private android.hardware.camera2.params.RggbChannelVector mShutterManualWb;
    /** P212g (W6 = M6): the mosaic white balance's route at the press (the vivo native route, P208's still-only one; null: none). */
    private com.scamera.app.processing.color.MosaicNeutralResolver.Press mShutterMosaicWb;
    /** Owner, 2026-10-28: the lens filter the viewfinder showed at the press (a change in the tray during the merge stays out). */
    private com.scamera.app.processing.opengl.postpipeline.LensFilter.Choice mShutterLensFilter;
    /** The film, LUT and «Цвет» at the press (ShotLook.now), carried to the shot's processing as its lens filter. */
    private com.scamera.app.processing.opengl.postpipeline.ShotLook mShutterLook;
    private com.scamera.app.settings.MotionMode.Snapshot mShutterMotion;
    /** P212g (W6): the still-only session's viewfinder neutrals (its normal mode), and whether this session feeds them. */
    private final com.scamera.app.processing.color.MosaicNeutralResolver.Ring mVfNeutrals =
            new com.scamera.app.processing.color.MosaicNeutralResolver.Ring();
    private volatile boolean mVfNeutralFeed;
    /** P212g (W7 = M12): the AWB colour temperature label of a plain result (Qualcomm; vivo and OPPO report it). */
    private static final CaptureResult.Key<int[]> AWB_CCT_LABEL =
            new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlCCT", int[].class);
    private volatile boolean mLiveRawSession;
    private boolean mPortraitVendorWindow;
    /**
     * P13: the stream of this session is a colour-block mosaic (a sensor mode without remosaic, MosaicStream): its ISP preview
     * is purple, so the ZSL frames also go to the developed-RAW viewfinder, whose shader bins the colour blocks.
     */
    private volatile boolean mMosaicPreview;
    /** scam_dev.txt "mosaic_preview 0": no measurement and no mosaic preview (diagnostics). */
    private volatile boolean mMosaicMeasure = true;
    private volatile boolean mNativeRawPslCapture;
    private TotalCaptureResult mNativeZslBase;
    private boolean mLiveRawRejected;
    private final PreviewFrameMatcher<Image, TotalCaptureResult> mLiveMetadata =
            new PreviewFrameMatcher<>(this::onMatchedLiveRaw, Image::close);
    /** P36: the session generation whose first capture start armed the viewfinder transform. */
    private int mTransformArmedGeneration = -1;
    /** P38: the last SCAM-routed shot flushed the HAL queue before its series (picks the default re-arm). */
    private volatile boolean mLastShotFlushed;
    /** P38: a flush re-arm waits for the first repeating-preview result after the series (until the deadline). */
    private volatile boolean mRearmPending;
    private volatile long mRearmDeadlineMs;
    /** P38: the session the pending re-arm belongs to (a new session never runs an old session's re-arm). */
    private volatile CameraCaptureSession mRearmSession;
    /** P38: preview stabilisation keys per frame around a shot (tag STAB_TRACE). */
    private final StabilizationTrace mStabTrace = new StabilizationTrace();
    /** P44: the viewfinder freeze of every shot from the preview timestamps (tag PREVIEW_GAP, one line per shot). */
    private final PreviewGapMeter mPreviewGap = new PreviewGapMeter();
    /** P44: preview frames the last shot queued right after its flush, ahead of its series (PreviewContinuity). */
    private volatile int mLastShotLead;
    private final TimestampFrameRouter<Image> mLiveRawRouter = new TimestampFrameRouter<>((image, still) -> {
        if (still) mImageSaver.initProcess(image);
        else if (mZslCapturing) image.close();
        else mLiveMetadata.image(image.getTimestamp(), image);
    }, Image::close);
    /**
     * CFA pattern of the stream being previewed, resolved once per session.
     *
     * mCameraCharacteristics is swapped between logical and physical cameras
     * while the session runs, so reading the pattern per frame returned BGGR on
     * some frames and RGGB on others - the demosaic then decoded the same
     * sensor two different ways from one frame to the next, which is the colour
     * flickering.
     */
    private volatile int mLiveCfaPattern = -1;
    private volatile boolean mHybridZslCapture = false;
    // SCAM: keep pre-shutter RAWs while waiting for the stock AE plan.
    private volatile boolean mScamRingFrozen = false;
    // SCAM fast shutter: the preview keeps running during the L/S/ES tail and RAWs
    // are routed by capture-start timestamp (bracket -> saver, preview -> dropped)
    // instead of stopRepeating + restart, which froze the viewfinder, emptied the
    // ZSL ring and threw AE off for the next shot.
    private volatile boolean mScamRouted = false;
    /**
     * Preview stall watchdog (PreviewStall): the last preview result, the configured session and the frame duration
     * (elapsedRealtime ms), and the restarts in a row that brought no frame.
     */
    private volatile long mLastPreviewResultMs, mPreviewSessionStartMs, mPreviewFrameMs;
    private volatile int mStallRestarts;
    private final Runnable mStallCheck = this::checkPreviewStall;
    /** P315: the shot (its submit time) whose stalled preview was issued again once (checkPreviewStall). */
    private volatile long mStallRearmSubmitNs;
    // Shutter presses made while a SCAM shot is still capturing; fired in order.
    private int mScamQueuedShots = 0;
    // Sensor timestamp of the newest tail frame (L/S/ES) of the current SCAM shot.
    private volatile long mScamTailTimestamp = 0;
    private volatile boolean mScamFireWaiting = false;
    private List<ImageFrame> mPendingZslNormalFrames = new ArrayList<>();
    private volatile java.util.concurrent.CountDownLatch mZslCopyDone;
    // The stock-AE observer attaches to the camera provider after the session
    // starts; a queue flush while it attaches hung the HAL once (no bracket for
    // 65 s, provider restarted). Flush only once the observer has delivered a plan.
    private volatile boolean mStockObserverReady;

    /** The still reader beside the RAW one: its frames go straight to the saver. */
    private final ImageReader.OnImageAvailableListener mStillReaderListener = reader -> mImageSaver.initProcess(reader);
    private final ImageReader.OnImageAvailableListener mRawReaderListener = this::onRawImage;

    /** Closes the newest frame of a reader nobody reads any more (a closed session's, a paused screen's). */
    private static void discardStale(ImageReader reader) {
        try {
            final Image stale = reader.acquireLatestImage();
            if (stale != null) stale.close();
        } catch (IllegalStateException ignored) {
            // the reader closed under us
        }
    }

    /** The next frame of a reader, or null when it has none or closed meanwhile. */
    @Nullable
    private static Image nextImage(ImageReader reader) {
        try {
            return reader.acquireNextImage();
        } catch (IllegalStateException closed) {
            return null;
        }
    }

    /**
     * A RAW frame arrived: it goes to the shot that asked for it (routed by its capture-start time), to a FULLRES capture, to
     * the ZSL ring (after the viewfinder's meters looked at it) or, outside ZSL, to the saver.
     */
    private void onRawImage(ImageReader reader) {
        if (!isCameraResumed || reader != mImageReaderRaw) {
            discardStale(reader);
            return;
        }
        // P635b: the proof still of a recipe session (no shot in flight: a shot's frames go their own way below)
        if (mRecipeProof == 2 && mRecipePlan != null && !mNativeRawPslCapture && !mScamRouted) {
            final Image proof = nextImage(reader);
            if (proof != null) recipeProofFrame(proof);
            return;
        }
        if (mVivoSdkRawSession != null) {
            final Image image = nextImage(reader);
            if (image == null) return;
            final VivoSdkFullResCapture capture = mVivoSdkCapture;
            if (capture != null) capture.image(image);
            else if (nativeVivoFullRes()) mLiveRawRouter.image(image.getTimestamp(), image);
            else image.close();
            return;
        }
        final boolean zsl = isZslMode();
        if (mNativeRawPslCapture || mScamRouted || (!zsl && mLiveRawSession)) {
            // A shutter press is not a frame boundary: old preview images can arrive during AF.
            // Route by the matching capture-start timestamp, never by the current UI state.
            final Image image = nextImage(reader);
            if (image != null) mLiveRawRouter.image(image.getTimestamp(), image);
            return;
        }
        if (zsl) {
            if (mHybridZslCapture) mImageSaver.initProcess(reader);
            else ringFrame(reader);
            return;
        }
        if (seriesRunning && !seriesStarted) return;
        // a single frame is saved off this callback (the camera thread keeps delivering), a series inline
        if (ScameraApp.getSettings().frameCount == 1) mBackgroundHandler.post(() -> mImageSaver.initProcess(reader));
        else mImageSaver.initProcess(reader);
    }

    /** A ZSL session's frame outside a shot: measured for the viewfinder, then kept in the ring (the oldest dropped). */
    private void ringFrame(ImageReader reader) {
        final Image img = nextImage(reader);
        if (img == null) return;
        if (mZslCapturing || mLiveRawSession) {
            if (mZslCapturing) img.close(); // the shot is copying the ring: this frame is not its
            else mLiveMetadata.image(img.getTimestamp(), img);
            return;
        }
        if (!passModeBarrier(img)) { img.close(); return; }
        watchRawPayload(img);
        observeRawLevel(img, null);
        observeMosaic(img);
        // P626: a nubia per-shot way's RAW outside a shot is its proof frame: measured above, not metered, not in the ring
        if (perShot()) { img.close(); return; } // P635: a recipe's capture RAW outside a shot as well
        plainMeter(img, null); // P212b: the native AE's start, sampled in the plain session (also while a merge runs)
        plainTeacher(img, null); // P212f: the mosaic white balance's teacher (log only)
        // P187: the plain session of a running 200 MP merge takes no shot (takeStill refuses it), so its
        // frames stay out of the ZSL ring: 125 MB of memfd less under the merge and the pipeline
        if (mClosedForProcessing) { img.close(); return; }
        // P356: ETTR with the RAW viewfinder off too (before the ring copy closes img); not under a merge (no shot there,
        // the camera reopens after it: no meter, no request rebuild)
        ettrPlain(img);
        synchronized (mRingLock) {
            if (!isCameraResumed || mRingClosing || reader != mImageReaderRaw) { img.close(); return; }
            final long copyStart = android.os.SystemClock.elapsedRealtimeNanos();
            mZslRingBuffer.addLast(ringFrameLocked(img));
            LiveRawFrame.noteRingCopy(android.os.SystemClock.elapsedRealtimeNanos() - copyStart); // P365 step 1
            for (int excess = mZslRingBuffer.size() - ringCapacityLocked(); excess > 0; excess--) {
                final RingFrame oldest = mZslRingBuffer.pollFirst();
                if (oldest != null) oldest.close();
            }
        }
        oldstyleViewfinder(); // P630e
    }
    /** The AE target range the viewfinder asks for by default: the lens's slowest declared rate up to 30 fps. */
    private Range<Integer> FpsRangeAuto;
    /** P224: the fastest rate of this session's RAW stream (0: not known); a slower mode keeps its range (ViewfinderFps). */
    private double mRawMaxFps;
    /** The lens's CONTROL_AF_AVAILABLE_MODES (a fixed-focus lens lists one). */
    private int[] afModesOfCamera;
    /** The viewfinder view's size the camera was opened for. */
    private int previewW;
    private int previewH;
    /** The requests of the shot being set up / in flight, and the callback that collects its results. */
    private ArrayList<CaptureRequest> mShotRequests;
    private CameraCaptureSession.CaptureCallback mShotCallback;
    /**
     * P27: completes the shot in flight with the frames that arrived (the outstanding requests count as lost); null when no
     * shot is in flight. A camera close, error, disconnect or a stalled HAL then still gives a photo and frees the shutter.
     */
    private volatile Runnable mInFlightRescue;
    /** P222: the reason of the last rescueInFlightShot, read by the rescue it runs. */
    private volatile String mInFlightRescueReason;
    /** P684: a failed 200 MP still-only series noted its next variant (StillOnly200): a camera error right after fails no row. */
    private volatile boolean mStillOnlyNextVariant;
    /** P27: manual ISO / shutter this camera's HAL honours (learned from shots; null before the first session). */
    private volatile ExposureLimits mExposureLimits;
    /** Unpacked copy of a RAW10 / RAW12 preview frame for the mosaic measurement (reused). */
    private java.nio.ByteBuffer mMosaicUnpacked;
    /** P30: the arena of the shot being set up / in flight (released when the shot completes or fails). */
    private volatile com.scamera.app.util.ShotArena mShotArena;
    /** P27: watchRawPayload's session restart waits until the shot in flight is complete. */
    private volatile boolean mPendingPayloadRestart;
    /** The clip being recorded (video mode). */
    private File mVideoFile;
    /** Frames the last series really captured (the JPEG saver's count). */
    public int mMeasuredFrameCnt;
    /** A still is being processed (the shutter waits, the reader stays open). */
    public static boolean isProcessing;
    /** The GL viewfinder the ISP stream draws into. */
    private GLPreview mViewfinder;
    /** The running session (null while none is configured). */
    private volatile CameraCaptureSession mCaptureSession;
    private final Object mPreviewStateLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger mSessionGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private volatile ScamStockAe mStockAe;
    private int mConfiguredSessionGeneration = -1;
    private MediaRecorder videoRecorder;
    public boolean recordingVideo;
    /** The lens's last focus distance (diopters) the viewfinder reported. */
    public float focusDiopters;
    // The viewfinder's AF / AE modes and metering regions now, and the session's defaults the touch focus returns to.
    public int mPreviewAFMode, mPreviewAEMode;
    public MeteringRectangle[] mPreviewMeteringAF, mPreviewMeteringAE;
    private MeteringRectangle[] mDefaultAfRegions, mDefaultAeRegions;
    /** The ISP viewfinder stream's size and the GL buffer it is drawn from. */
    public Size mPreviewSize, mBufferSize;
    /** The camera thread (mBackgroundHandler's looper). */
    private HandlerThread mCameraThread;
    /** When the shutter's 3A wait started (elapsedRealtime, ms): THREE_A_WAIT_MS later the shot fires anyway. */
    private long mThreeAWaitStart;
    /** The lens has a flash unit (FLASH_INFO_AVAILABLE). */
    private boolean mFlashSupported;
    /** P590: the flash shot the next takeStill takes (set after its precapture; null: a normal shot). */
    private volatile FlashMode.Shot mFlashShot;
    /** P590: the flash shot waiting for its precapture (SHOT_FLASH_AE) and the wait. */
    private FlashMode.Shot mFlashPending;
    private FlashMode.Precapture mFlashPrecapture;
    /**
     * P590: from a flash press until its frame fired (or the normal shot of the press took the ring): the ring keeps the frames and
     * results of the press (a flash that did not fire falls back to them), no preview result is added to the ring's metadata.
     */
    private volatile boolean mFlashRingHold;
    /** P590: the route whose «no flash» toast was shown (once a session: the route and the session generation). */
    private String mFlashBlockToast;
    /** A shot's series is being captured: the viewfinder's request is left alone until it ends. */
    public static volatile boolean burst;
    private static volatile long sTimelineSubmitNs;
    /** The viewfinder's repeating / one-shot requests' results: 3A, the ring's metadata, the meters, the trace. */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            synchronized (mPreviewStateLock) {
                if (!isCurrentPreviewSession(session)) return;
                // P36: the first frame of this session: the viewfinder takes the camera's orientation / mirror from it.
                if (mTransformArmedGeneration != mConfiguredSessionGeneration) {
                    mTransformArmedGeneration = mConfiguredSessionGeneration;
                    if (mViewfinder != null) mViewfinder.armPendingTransform(timestamp);
                }
                if (mNativeRawPslCapture || mScamRouted || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, false);
                VivoSdkFullResCapture nativeCapture = mVivoSdkCapture;
                if (nativeCapture != null) nativeCapture.previewStarted(timestamp);
            }
        }


        @Override public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                              @NonNull CaptureFailure failure) {
            // P44: a flush drops the preview requests in flight this way (counted in the shot's PREVIEW_GAP line).
            synchronized (mPreviewStateLock) {
                if (isCurrentPreviewSession(session)) mPreviewGap.previewFailed();
            }
        }

        /**
         * Advances the shutter's 3A wait (AF lock, AE precapture) by one result; a shot whose wait is over or timed out fires
         * {@link #takeStill()}.
         */
        private void advanceShotState(CaptureResult result) {
            final int state = mShotState;
            if (state == SHOT_IDLE) return;
            if (state == SHOT_FLASH_AE) {
                // P590: completed results only (a partial one may carry no AE state)
                if (result instanceof TotalCaptureResult) flashPrecaptureProcess(result);
                return;
            }
            if (state != SHOT_AF_LOCK && state != SHOT_AE_START && state != SHOT_AE_END) return;
            final boolean late = threeAWaitOver();
            // CONTROL_AE_STATE is absent on some HALs: an unknown state never holds the shot
            final Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
            if (state == SHOT_AF_LOCK) {
                if (late) Log.w(TAG, "3A wait over its time: the shot fires unlocked");
                final Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                if (late || af == null) { fireAfterWait(); return; }
                final boolean afSettled = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
                if (!afSettled) return;
                if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) fireAfterWait();
                else startAePrecapture();
            } else if (state == SHOT_AE_START) {
                if (late) { fireAfterWait(); return; }
                final boolean started = ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                        || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
                // a manual exposure runs no precapture: nothing to wait for
                if (started || paramController.offAuto()) mShotState = SHOT_AE_END;
            } else if (late || ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                fireAfterWait();
            }
        }

        private void fireAfterWait() {
            mShotState = SHOT_FIRED;
            takeStill();
        }

        /** The 3A consumers of every preview result, partial ones included: the shutter's wait and the touch focus. */
        private void feedThreeA(CaptureResult result) {
            advanceShotState(result);
            final TouchFocus touch = mTouchFocus;
            if (touch != null) touch.onCaptureResult(result);
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partial) {
            synchronized (mPreviewStateLock) {
                if (isCurrentPreviewSession(session)) feedThreeA(partial);
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            synchronized (mPreviewStateLock) {
                if (!isCurrentPreviewSession(session)) return;
                // read once: the ring's metadata, the live RAW pairing and the gap meter below all key on it
                final Long sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP);
                // P192: a plain session keeps its colour for the native mode, a native result gets it; P214j: on every phone of
                // the route (OPPO, OnePlus, realme) for a camera with full-size modes - was the PHY110 only (OppoNativeFullRes.keepsColour)
                if (mVivoSdkRawSession == null) {
                    if (OppoNativeFullRes.keepsColour(physicalID)) OppoNativeFullRes.rememberPlain(physicalID, result);
                } else if (VivoSdkFullRes.oppo()) OppoNativeFullRes.complete(mVivoSdkCameraId, result);
                // P212g (W6 = M6): P208's still-only viewfinder is the normal mode (its ISP AWB valid): its neutrals for the press
                // P408: with the reference app's rule every session's (its live stream's last result at the press)
                if (mVfNeutralFeed && mVivoSdkRawSession != null
                        || com.scamera.app.processing.color.MosaicNeutralResolver.whyMode())
                    mVfNeutrals.offer(physicalID, android.os.SystemClock.elapsedRealtime(), result);
                // P212g (W7 = M12): a converged plain result teaches the camera's colour table (about once a second)
                if (mVivoSdkRawSession == null) plainColour(result);
                if (mLastPreviewResultMs == 0) {
                    markModuleGood();
                    OpenTimeline.firstFrame(); // P680: the PERF line of this start
                }
                mLastPreviewResultMs = android.os.SystemClock.elapsedRealtime();
                // P575: the nubia row's reported mode; one that never shows fails over (posted: not inside the result callback)
                final String nubiaWhy = NubiaFullRes.watchResult(physicalID, result);
                if (nubiaWhy != null) {
                    final SensorModePick.Pick nubiaPick = SensorModePick.session();
                    // P608: a maximum-resolution session whose results never report its full row (the HAL stays binned) fails its way
                    final Object nubiaRun = mFullResSession ? NubiaRemosaicProbe.token() : null;
                    mainHandler().post(() -> {
                        if (nubiaPick != null && SensorModePick.session() == nubiaPick) retryWithoutRequestedSensorMode(mCaptureSession, nubiaWhy);
                        else if (nubiaRun != null && mFullResSession && NubiaRemosaicProbe.token() == nubiaRun)
                            retryWithoutFullRes(mCaptureSession, nubiaWhy);
                    });
                }
                // P626: a per-shot way proves the full mode by a short MAXIMUM_RESOLUTION burst on the RAW alone (once, unproven)
                if (mFullResSession && NubiaRemosaicProbe.proofWanted(physicalID)) {
                    final Object nubiaRun = NubiaRemosaicProbe.token();
                    mainHandler().post(() -> sendNubiaMaxResProof(nubiaRun));
                }
                // P635b: an unproven recipe's proof still, once the session's viewfinder runs
                if (mRecipeProof == 1 && mRecipePlan != null && session == mCaptureSession) sendRecipeProof();
                // P608: a frame verdict of the maximum-resolution session that waited for the mode, once the full row is reported
                final int[] nubiaPending = mFullResSession ? NubiaRemosaicProbe.takePending(physicalID) : null;
                if (nubiaPending != null) {
                    final Object nubiaRun = NubiaRemosaicProbe.token();
                    mainHandler().post(() -> {
                        if (mFullResSession && NubiaRemosaicProbe.token() == nubiaRun)
                            nubiaMaxResVerdict(nubiaPending[0], nubiaPending[1], nubiaPending[2]);
                    });
                }
                // P586: the OnePlus 16's ISZ / DCG / DAG row or native FULLRES mode its results report; one that never shows fails over
                final String oplusWhy = OplusIsz.watchResult(physicalID, result);
                if (oplusWhy != null) {
                    final boolean oplusFull = OplusIsz.watchingFullRes();
                    final SensorModePick.Pick oplusPick = SensorModePick.session();
                    mainHandler().post(() -> {
                        if (oplusFull) {
                            if (mVivoSdkRawSession != null) retryWithoutVivoSdkFullRes(mCaptureSession, oplusWhy);
                        } else if (oplusPick != null && SensorModePick.session() == oplusPick) retryWithoutRequestedSensorMode(mCaptureSession, oplusWhy);
                    });
                }
                // P593: the DCG / DAG row's frames must be wider than declared; a plain row is noted and the next candidate starts
                final String dcgWhy = OplusDcg.watchResult(physicalID, result);
                if (dcgWhy != null) {
                    final SensorModePick.Pick dcgPick = SensorModePick.session();
                    mainHandler().post(() -> {
                        if (dcgPick != null && dcgPick.dcg() && SensorModePick.session() == dcgPick) retryWithoutRequestedSensorMode(mCaptureSession, dcgWhy);
                    });
                }
                // Restarts count as successful only after a second of frames: one result per new session reset the count and the
                // stall restarts never stopped (Xiaomi 13 Ultra, 14 restarts in a row, 2026-10-10)
                if (mPreviewSessionStartMs > 0 && mLastPreviewResultMs - mPreviewSessionStartMs > 1000) mStallRestarts = 0;
                Long frameNs = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                if (frameNs != null) mPreviewFrameMs = frameNs / 1_000_000L;
                final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                if (exposure != null) mPreviewExposureTime = exposure;
                if (iso != null) mPreviewIso = iso;
                // P212b: the camera's own plain HAL exposure, the native AE's last fallback start (never another camera's value)
                if (mVivoSdkRawSession == null && exposure != null && iso != null && OppoNativeFullRes.brand())
                    NativeAeSeed.plainHal(physicalID, (long) exposure, (int) iso, mLastPreviewResultMs);
                if (mVivoSdkRawSession != null && mLastPreviewResultMs - mVivoSdkPreviewLogMs >= 2000) {
                    mVivoSdkPreviewLogMs = mLastPreviewResultMs;
                    Log.d("SCAM_CAPTURE", "SDK preview camera=" + physicalID + " exposureNs=" + exposure + " iso=" + iso
                            + " aeState=" + result.get(CaptureResult.CONTROL_AE_STATE)
                            + " aeMode=" + request.get(CaptureRequest.CONTROL_AE_MODE)
                            + " aeLock=" + request.get(CaptureRequest.CONTROL_AE_LOCK)
                            + " ev=" + request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
                            + " requestedNs=" + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                            + " requestedISO=" + request.get(CaptureRequest.SENSOR_SENSITIVITY));
                }
                if (iso != null) widenSensitivityRange((int) iso);
                // P27: the AE restore frame after a shot is a manual request at N: it re-checks the learned limits.
                final ExposureLimits limits = mExposureLimits;
                if (limits != null) limits.observeManual(request, result);
                paramController.onPreviewResult(result); // P82 shutter / ISO priority
                com.scamera.app.processing.opengl.postpipeline.RawBoost.observe(result); // P501
                observeParkedLens(result);
                observeFpsProbe(result); // P266
                // P87: the ISO extremes the camera's own AE uses (the manual rulers' range)
                if (iso != null) {
                    Integer aeMode = request.get(CaptureRequest.CONTROL_AE_MODE);
                    if (aeMode != null && aeMode != CaptureRequest.CONTROL_AE_MODE_OFF)
                        ExposureRanges.observeAutoIso(physicalID, (int) iso);
                }
                final Float lensDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                if (lensDistance != null) focusDiopters = lensDistance;
                final Rational[] neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
                if (neutral != null) mPreviewTemp = neutral;
                else if (mPreviewTemp == null) mPreviewTemp = flatNeutral();
                mColorSpaceTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
                if (mTorchLogFrames > 0 && --mTorchLogFrames % 5 == 0) Log.i("SCAM_CAPTURE", "torch: camera " + physicalID
                        + " result flash mode " + result.get(CaptureResult.FLASH_MODE) + " state " + result.get(CaptureResult.FLASH_STATE));
                // P590: a flash press keeps the ring's metadata of the press
                if (sensorTs != null && isZslMode() && !mFlashRingHold) keepRingResult(sensorTs, result);
                if (mLiveRawSession && session == mCaptureSession) pairLiveResult(sensorTs, result);
                publishPreview(request, result);
                if (mRecipePlan != null && session == mCaptureSession) recipeAfTrace(request, result); // P635c
                logSensorModeIndex(result);
                VivoQcomModes.onResult(result, physicalID);
                if (mDcgOperationMode != 0 && XiaomiDcg.device()) XiaomiDcg.onResult(result, mCameraCharacteristics, physicalID); // P230: the mode the HAL runs
                // P679: a LOFIC session's results: kept for the pairs of their timestamp, and its setup's checks
                if (XiaomiLofic.sessionActive() && session == mCaptureSession) {
                    if (sensorTs != null) mLoficPairs.result(result, sensorTs);
                    if (XiaomiLofic.onResult(result) == XiaomiLofic.Verdict.FAILED) loficNextSetup();
                }
                // P641: the Xiaomi 17 Ultra's dual-gain variant: what the HAL reports, the depth it shows, the verdict once
                if (XiaomiDualGain.sessionActive() && session == mCaptureSession
                        && XiaomiDualGain.onResult(result, mCameraCharacteristics) == XiaomiDualGain.Verdict.FAILED) dualGainNextVariant();
                if (mDcgRequestKeys && mImageReaderRaw != null) SensorModeBand.noteResult(mCameraCharacteristics, result,
                        mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight(), physicalID);
                if (XiaomiTeleZoom.active() && isCurrentPreviewSession(session)
                        && XiaomiTeleZoom.onResult(result, xiaomiLensResult(result), PreferenceKeys.scamDevSwitch("xiaomi_lens_check", true)))
                    onZoomChanged();
                mStabTrace.record(request, result);
                if (sensorTs != null) {
                    final String gapLine = mPreviewGap.preview(sensorTs, android.os.SystemClock.elapsedRealtimeNanos());
                    if (gapLine != null) Log.i(PreviewGapMeter.TAG, gapLine);
                }
                maybeRunPendingRearm(session, request, result);
                maybeReapplyAutoOis(session); // P316: «Оптическая стабилизация» Авто follows the tripod during the session
                // P680: the constant first (useStockBracketPlanner is false since P129): no settings read per frame for it
                if(PreferenceKeys.useStockBracketPlanner() && PreferenceKeys.isScamEnabled() && isCurrentPreviewSession(session)) {
                    ScamStockAe stock=mStockAe;
                    if(stock==null || stock.generation!=mSessionGeneration.get()) {
                        if(stock!=null)stock.close();
                        stock=new ScamStockAe(ScameraApp.getAppContext(),mSessionGeneration.get(),physicalID);
                        mStockAe=stock;
                    }
                    stock.offer(result);
                }
                feedThreeA(result);
                final com.scamera.app.control.subject.SubjectFocus subject = mSubjectFocus;
                if (subject != null) subject.onPreviewResult(result);
                cameraEventsListener.previewResult(result);
                // P680: the AF mode from the session's settings snapshot (read again once any setting changed), after the cheap checks
                if(!burst && (mTouchFocus == null || !mTouchFocus.isTouchFocus) && sessionPrefs().afMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                    CaptureRequest.Builder builder = mPreviewRequestBuilder;
                    if (builder != null && isCurrentPreviewSession(session)) {
                        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                        rebuildPreviewBuilderOneShot();
                        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    }
                }
            }
        }

    };

    /** P680: the settings the viewfinder's results read per frame (camera thread; a new session reads them again). */
    private volatile com.scamera.app.settings.SessionPrefs mSessionPrefs;

    private com.scamera.app.settings.SessionPrefs sessionPrefs() {
        final com.scamera.app.settings.SessionPrefs p = com.scamera.app.settings.SessionPrefs.refresh(mSessionPrefs);
        mSessionPrefs = p;
        return p;
    }

    /** One main-thread handler for the controller's posts (was a new Handler for each of them). */
    private static volatile Handler sMainHandler;

    private static Handler mainHandler() {
        final Looper looper = Looper.getMainLooper();
        Handler main = sMainHandler;
        // the main looper never changes on a phone; a host test's sandbox may give a new one
        if (main == null || main.getLooper() != looper) sMainHandler = main = new Handler(looper);
        return main;
    }

    /** The neutral point a result without one stands for: a flat 1.01 on all three channels (the processing's old default). */
    private static Rational[] flatNeutral() {
        final Rational[] flat = new Rational[3];
        Arrays.fill(flat, new Rational(101, 100));
        return flat;
    }

    /** The ZSL ring's metadata: the result kept by its sensor time, the oldest dropped past the ring's capacity (+16). */
    private void keepRingResult(long sensorTs, TotalCaptureResult result) {
        synchronized (mRingLock) {
            mHexZslResults.put(sensorTs, result);
            final int keep = zslRingCapacity() + 16;
            final java.util.Iterator<Long> oldest = mHexZslResults.keySet().iterator();
            for (int extra = mHexZslResults.size() - keep; extra > 0 && oldest.hasNext(); extra--) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** The live RAW stream's metadata: by the logical result's time and, when the lens reports its own, by that one too. */
    private void pairLiveResult(@Nullable Long sensorTs, TotalCaptureResult result) {
        if (sensorTs != null) mLiveMetadata.result(sensorTs, result);
        if (Build.VERSION.SDK_INT < 28) return;
        final CaptureResult lens = result.getPhysicalCameraResults().get(physicalID);
        final Long lensTs = lens == null ? null : lens.get(CaptureResult.SENSOR_TIMESTAMP);
        if (lensTs != null && !lensTs.equals(sensorTs)) mLiveMetadata.result(lensTs, result);
    }

    /** The newest preview request / result, read by the touch focus, the manual controls and the processing. */
    private static void publishPreview(CaptureRequest request, TotalCaptureResult result) {
        mPreviewCaptureRequest = request;
        mPreviewCaptureResult = result;
    }

    /** An open attempt has its answer: the open/close lock is free again and a new open may start. */
    private void openSettled() {
        mCameraOpenCloseLock.release();
        mCameraOpening.set(false);
    }

    /** The device is gone (disconnected, failed): its open settled, the device closed and forgotten. */
    private void dropDevice(CameraDevice device) {
        openSettled();
        device.close();
        mCameraDevice = null;
    }

    /** The camera device's open / close / error answers. */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice device) {
            OpenTimeline.opened(); // P680
            synchronized (mPreviewStateLock) {
                if (mVivoSdkRawSession != null) mVivoSdkRawDevice = device;
                openSettled();
                // an open still in flight when the screen went to the background: a hidden app holds no camera
                if (!isCameraResumed) {
                    Log.d(TAG, "camera " + device.getId() + " opened after the pause: closed again");
                    device.close();
                    return;
                }
                mCameraDevice = device;
                if (mVivoSdkRawSession == null) VivoQcomModes.onCameraOpened();
                mImageSaver = new ImageSaver(cameraEventsListener);
                startSession(false);
            }
        }

        @Override public void onClosed(@NonNull CameraDevice cameraDevice) {
            closeVivoSdkRawSession(cameraDevice);
            closeIszLease();
            synchronized (mDeviceClosedLock) { // P662: awaitClosedForProcessing
                if (cameraDevice == mClosingDevice) {
                    mClosingDevice = null;
                    mDeviceClosed.countDown();
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            if (mVivoSdkRawSession != null && mVivoSdkRawDevice == null) mVivoSdkRawDevice = cameraDevice;
            // P635: a recipe session the camera service dropped before its first frame or under a shot (the documents'
            // «disconnected» of a wrong field set): the profile is remembered as failed, the reopen below is the normal session
            if (mRecipe != null && (mLastPreviewResultMs == 0 || mShotInProgress || mZslCapturing)) recipeFailed("camera disconnected");
            rescueInFlightShot("camera disconnected");
            stopStallWatch();
            dropDevice(cameraDevice);
            // another client took the camera (split screen, a pop-up window) or the camera service dropped it: nothing reopened
            // it while the activity stayed resumed (no onResume follows) - the viewfinder froze until a module switch
            if (isCameraResumed) awaitCameraBack(cameraDevice.getId());
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mDeviceErrorMs = android.os.SystemClock.elapsedRealtime();
            // the session's own callback runs on another thread: a refusal the HAL answers with this error at the same moment
            // (vivo X300 log: both within 1 ms) is let to choose its retry first (at most 0.5 s)
            for (int i = 0; i < 50 && mConfiguringDevice == cameraDevice; i++) android.os.SystemClock.sleep(10);
            // owner, 2026-10-19 (vivo X300, «убедись что нигде не будет такой ошибки»): the HAL closes the device after refusing
            // a session (the vivo EIS session key there); the retry with the next configuration is under way, so the error is
            // expected: no overlay, no message, no module revert - the camera reopens with what the retry chose
            final boolean afterRetry = cameraDevice == mRetryDevice;
            mRetryDevice = null;
            // P263 (owner, 2026-10-19: «исправь ошибку код4 чтобы её не было ни на одном телефоне»; the OPPO showed «Сбой камеры
            // (код 4), перезапуск» once after an update, the camera of the old process still being released): the first device /
            // service error since the last configured session reopens the same camera and module quietly - no message, no module
            // revert, no overlay for the first seconds. A second one before a session configures goes the old way (revert,
            // recovery); scheduleRecovery says so only when it gives up.
            final boolean quietError = !afterRetry && (error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_SERVICE) && !mQuietErrorSpent;
            if (quietError) mQuietErrorSpent = true;
            if (isCameraResumed && !afterRetry && !quietError) recovering(true, "camera error " + error); // P240: every path below opens the camera again
            if (mVivoSdkRawSession != null && mVivoSdkRawDevice == null) mVivoSdkRawDevice = cameraDevice;
            rescueInFlightShot("camera error " + error);
            stopStallWatch();
            dropDevice(cameraDevice);
            Log.w(TAG, "camera " + cameraDevice.getId() + " device error " + error);
            if (afterRetry) {
                Log.w("SCAM_CAPTURE", "camera " + physicalID + " closed by the HAL (error " + error
                        + ") after a refused session configuration: reopened quietly with the retried configuration");
                undoFallbackStep(physicalID);
                mQuietUntilMs = android.os.SystemClock.elapsedRealtime() + QUIET_REOPEN_MS;
                if (mRecoveryHandler == null) mRecoveryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mRecoveryHandler.postDelayed(() -> {
                    if (isCameraResumed && mCameraDevice == null && !mCameraOpening.get()) restartCamera();
                }, 300);
                return;
            }
            // P635: a camera error in a recipe session fails the profile (the documents' device error 4 of a wrong field set):
            // the camera reopens with its normal session
            if (retryWithoutRecipe(null, "camera error " + error)) return;
            // P679: a camera error in a LOFIC session fails its setup; the camera reopens with the next one (or the normal session)
            if (XiaomiLofic.sessionActive()) {
                XiaomiLofic.sessionFailed("died with camera error " + error);
                mDcgOperationMode = 0;
                mDcgRequestKeys = false;
            }
            // P641: a camera error in a session of a dual-gain variant fails that variant; the camera reopens with the next one
            if (XiaomiDualGain.sessionFailed("died with camera error " + error)) {
                mDcgOperationMode = 0;
                mDcgRequestKeys = false;
                SensorModeBand.sessionEnd();
            }
            if (retryWithoutVivoSdkFullRes(null, "camera error " + error)) return;
            // P598: a nubia remosaic-probe session the camera died in before its first frame: that setting failed, the next one
            // P626: or while the per-shot way's proof burst was in flight (its MAXIMUM_RESOLUTION requests)
            if (mFullResSession && (mLastPreviewResultMs == 0 || NubiaRemosaicProbe.proofPending(physicalID))
                    && NubiaRemosaicProbe.running(physicalID) && retryNubiaProbe(null, "camera error " + error)) return;
            // P315: a picked sensor mode is blamed only when its session never delivered a frame - the X8U's mode 8 had streamed
            // for minutes when its HAL died at a shot, and the module went back to Auto with «Режим сенсора 8 отклонён камерой»;
            // an error after the mode's own frames takes the quiet path below (the same module and mode reopen)
            // (the DCG / sensor-preset routes keep their P230 fallback: DCG off, a plain module, one restart)
            if ((SensorModePick.session() == null || mLastPreviewResultMs == 0)
                    && retryWithoutRequestedSensorMode(null, "camera error " + error)) return;
            if (mXiaomiRouted) {
                // P41b: the logical camera failed for this app: the tele alone from now on
                mXiaomiRouted = false;
                XiaomiTeleZoom.disableRoute("logical camera error " + error);
                scheduleRecovery("Xiaomi logical camera error " + error);
                return;
            }
            if (quietError) {
                Log.w("SCAM_CAPTURE", "camera " + physicalID + " error " + error + ": the first since its last session, reopened quietly");
                mQuietUntilMs = android.os.SystemClock.elapsedRealtime() + QUIET_ERROR_MS;
                scheduleRecovery("quiet onError " + error);
                return;
            }
            if (revertToLastGoodModule("error " + error)) return;
            if (revertToLastGoodCamera("error " + error)) return;
            if (error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_SERVICE) {
                // P263: the overlay «Камера перезапускается…» says it; a message only when the recovery gives up
                scheduleRecovery("onError " + error);
            } else {
                recovering(false, "camera error " + error + ": no recovery");
                showError(Lang.t("Камера недоступна (код ", "Camera unavailable (code ") + error + ")");
            }
        }
    };

    /** P240: the camera screen's «Камера перезапускается…» overlay is up (an error is being recovered from). */
    private volatile boolean mRecovering;

    /** P240 (speed-ux C4): the recovery overlay on / off, once per change, with the reason in the log. */
    private void recovering(boolean on, String why) {
        if (mRecovering == on) return;
        mRecovering = on;
        Log.i(TAG, "camera recovery " + (on ? "started" : "ended") + " (" + why + ")");
        try { cameraEventsListener.onCameraRecovery(on, why); } catch (RuntimeException e) { Log.w(TAG, "recovery overlay: " + e); }
    }

    // Recovery after the camera HAL or service died: the vivo X100 Ultra (owner's log 2026-10-05) answered
    // ERROR_CAMERA_DEVICE, every id was 'unknown device' for ~4.5 s while the provider restarted, and nothing reopened the
    // camera until the user tapped a module (27 s of a dead viewfinder once). 1, 2, 4 s, at most three times a minute.
    private android.os.Handler mRecoveryHandler;
    private final java.util.ArrayDeque<Long> mRecoveries = new java.util.ArrayDeque<>();

    /**
     * Session fallbacks per camera (vivo X200 Pro, owner's log 2026-10-07: the front camera 1 and camera 5 answered every session
     * of the preview + RAW streams with onConfigureFailed, the viewfinder froze on the last frame of the main camera with a toast,
     * and camera 5 died with ERROR_CAMERA_DEVICE on every reopen). A failed configuration retries once with the next smaller RAW
     * size the camera lists, then once without a RAW stream (YUV), then returns to the last camera that ran in this process.
     * Kept for the process: the next switch to that camera starts at the step that worked.
     */
    private static final java.util.Map<String, Integer> sRawFallback = new java.util.concurrent.ConcurrentHashMap<>();
    /** P559 / P560: cameras whose session refused a packed RAW10 / RAW12 stream: they take RAW_SENSOR (the process). */
    private static final java.util.Set<String> sRaw16 = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** P559 / P560: this session streams a packed RAW (RAW10 / RAW12). */
    private volatile boolean mPickPacked;
    /**
     * A YUV session (no RAW stream) that failed to configure on a camera that never ran: 1 = the preview stream at a guaranteed
     * size (at most 1080p), 2 = the YUV stream as well (SafeStreamSizes).
     */
    private static final java.util.Map<String, Integer> sSafeStreams = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Size> sRawSizeAlt = new java.util.concurrent.ConcurrentHashMap<>();
    /** Cameras (full camera ID setting) whose session was configured in this process, and the last one of them. */
    private static final java.util.Set<String> sGoodCameras = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String sLastGoodCamera;
    /** The RAW target format this session replaced with YUV (no RAW stream on this camera); restored for the next camera. */
    private static volatile int sDemotedFrom = 0;
    private volatile boolean mReverting;

    /** RAW sizes of the target format the camera lists, largest first (plain stream configuration only). */
    private static Size[] rawSizesLargestFirst(StreamConfigurationMap map, int format) {
        Size[] sizes = map == null ? null : map.getOutputSizes(format);
        if (sizes == null) return new Size[0];
        sizes = sizes.clone();
        Arrays.sort(sizes, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        return sizes;
    }

    /**
     * Next fallback step after onConfigureFailed of this camera: true when a session is being created again (smaller RAW, then no
     * RAW stream). Not while recording, and only for a session with a RAW stream.
     */
    private boolean retryConfigureFallback(CameraCaptureSession session) {
        final CameraDevice device = mCameraDevice;
        if (device == null || recordingVideo) return false;
        final String id = physicalID;
        if (!isRawFormat(mTargetFormat) && YuvMergeRoute.refuseTenBit(id)) {
            // P646: the YUV route's P010 / HLG10 session refused: the same route at 8 bits
            try { session.close(); } catch (Exception ignored) {}
            final Handler handler = mBackgroundHandler;
            if (handler == null) return false;
            handler.post(() -> {
                if (!isCameraResumed || mCameraDevice != device) return;
                loadLens(id);
                startSession(false);
            });
            return true;
        }
        if (!isRawFormat(mTargetFormat)) {
            // A YUV session (the camera lists no RAW size, or its RAW sessions failed): retrying it as it is only fails again
            // (vivo X200 Pro front camera, owner's log 2026-10-08). Smaller streams next: the preview, then the YUV stream.
            if (sGoodCameras.contains(ScameraApp.getSettings().cameraId)) return false;
            final int safe = sSafeStreams.getOrDefault(id, 0);
            if (safe >= 2) return false;
            sSafeStreams.put(id, safe + 1);
            mFallbackUndo = () -> sSafeStreams.put(id, safe);
            Log.w(TAG, "camera " + id + ": session configuration failed (preview " + mBufferSize + ", "
                    + (mImageReaderRaw == null ? "no reader" : "reader " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight()
                    + " format " + mImageReaderRaw.getImageFormat()) + "); retry with "
                    + (safe == 0 ? "a preview of a guaranteed size" : "a YUV stream of a guaranteed size too"));
            try { session.close(); } catch (Exception ignored) {}
            final Handler handler = mBackgroundHandler;
            if (handler == null) return false;
            handler.post(() -> {
                if (!isCameraResumed || mCameraDevice != device) return;
                loadLens(id);
                startSession(false);
            });
            return true;
        }
        // P559 / P560: a session that refused its packed RAW (the setting's, «auto»'s RAW10): once more with RAW_SENSOR (before any
        // other step)
        if ((mPickPacked || mTargetFormat == ImageFormat.RAW10 || mTargetFormat == ImageFormat.RAW12) && sRaw16.add(id)) {
            mPickPacked = false;
            mFallbackUndo = () -> sRaw16.remove(id);
            Log.w(TAG, "camera " + id + ": the session failed with a packed RAW stream; retry with RAW_SENSOR (P559 / P560)");
            if (mTargetFormat == ImageFormat.RAW10 || mTargetFormat == ImageFormat.RAW12) mTargetFormat = ImageFormat.RAW_SENSOR;
            try { session.close(); } catch (Exception ignored) {}
            final Handler handler = mBackgroundHandler;
            if (handler == null) return false;
            handler.post(() -> {
                if (!isCameraResumed || mCameraDevice != device) return;
                loadLens(id);
                startSession(false);
            });
            return true;
        }
        final int stage = sRawFallback.getOrDefault(id, 0);
        if (stage >= 2) return false;
        // A camera whose session configured in this process keeps its streams: a later failure is transient (the old path), not a
        // reason to give up its RAW size or RAW stream for the rest of the process.
        if (sGoodCameras.contains(ScameraApp.getSettings().cameraId)) return false;
        int next = stage + 1;
        Size alt = null;
        // Not with the maximum-resolution pixel mode (Quad / remosaic): a size of the default map would switch the sensor mode.
        if (next == 1 && mImageReaderRaw != null && isRawFormat(mTargetFormat) && !useMaximumResolutionKey) {
            final long current = (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight();
            CameraCharacteristics c = mCameraCharacteristicsMap.get(id);
            StreamConfigurationMap map = c == null ? null : c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            for (Size size : rawSizesLargestFirst(map, mTargetFormat))
                if ((long) size.getWidth() * size.getHeight() < current) { alt = size; break; }
        }
        if (alt == null) next = 2; else sRawSizeAlt.put(id, alt);
        sRawFallback.put(id, next);
        mFallbackUndo = () -> { if (stage == 0) sRawFallback.remove(id); else sRawFallback.put(id, stage); };
        Log.w(TAG, "camera " + id + ": session configuration failed (preview " + mBufferSize + ", "
                + (mImageReaderRaw == null ? "no reader" : "reader " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight()
                + " format " + mImageReaderRaw.getImageFormat()) + "); retry " + (next == 1 ? "with RAW " + alt : "without the RAW stream (YUV)"));
        try { session.close(); } catch (Exception ignored) {}
        final Handler handler = mBackgroundHandler;
        if (handler == null) return false;
        handler.post(() -> {
            if (!isCameraResumed || mCameraDevice != device) return;
            loadLens(id);
            startSession(false);
        });
        return true;
    }

    /**
     * A camera that never ran in this process failed (configuration, device error): back to the last camera that did, instead of a
     * frozen viewfinder or reopening the failing one again and again. False when there is none to go back to.
     */
    /**
     * Modules (slots) that delivered a preview frame in this process, and the last of them. A module is its Camera ID plus its
     * vendor keys: the OPPO Find X9 Ultra / Realme GT8 Pro ISZ modules (camera 4 + agingtest.mode.select) killed the HAL while
     * the plain camera 4 ran, so the camera-level record (marked at onConfigured) never reverted and the recovery reopened the
     * failing module again and again (2026-10-10 logs).
     */
    private static final java.util.Set<String> sGoodModules = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String sLastGoodModule;

    private void markModuleGood() {
        try {
            String slot = com.scamera.app.settings.ModuleRegistry.active();
            sGoodModules.add(slot);
            sLastGoodModule = slot;
        } catch (RuntimeException ignored) {
        }
    }

    /** A module that never delivered a frame failed: back to the last module that did (another slot), with a toast. */
    private boolean revertToLastGoodModule(String reason) {
        final String current;
        try {
            current = com.scamera.app.settings.ModuleRegistry.active();
        } catch (RuntimeException e) {
            return false;
        }
        final String good = sLastGoodModule;
        if (good == null || good.equals(current) || sGoodModules.contains(current) || mReverting || !isCameraResumed) return false;
        mReverting = true;
        final String label = com.scamera.app.settings.ModuleRegistry.label(current);
        Log.e(TAG, "module " + current + " (" + label + ") failed (" + reason + ") before its first frame: back to module " + good);
        showError(Lang.t("Модуль " + label + " не запустился, возврат к предыдущему", "Module " + label + " did not start, back to the previous one"));
        mainHandler().post(() -> {
            mReverting = false;
            com.scamera.app.settings.ModuleRegistry.select(good);
            PreferenceKeys.setCameraID(com.scamera.app.settings.ModuleRegistry.camera(good));
            restartCamera();
        });
        return true;
    }

    private boolean revertToLastGoodCamera(String reason) {
        final String current = ScameraApp.getSettings().cameraId;
        final String good = sLastGoodCamera;
        if (good == null || good.equals(current) || sGoodCameras.contains(current) || mReverting || !isCameraResumed) return false;
        mReverting = true;
        Log.e(TAG, "camera " + current + " failed (" + reason + ") and never ran in this session: back to camera " + good);
        showError(Lang.t("Камера " + current + " не запустилась, возврат к камере " + good, "Camera " + current + " did not start, back to camera " + good));
        mainHandler().post(() -> {
            mReverting = false;
            PreferenceKeys.setCameraID(good);
            restartCamera();
        });
        return true;
    }

    /**
     * Owner's X300 log 2026-10-19: after the refused EIS key the HAL restarted; the quiet reopen's session failed meanwhile
     * (Broken pipe -32) and the configure fallback took camera 2 to a YUV session without RAW for the rest of the process. A
     * configure failure whose device the HAL then closed says nothing about the streams: that step is undone (once per camera
     * and process, so a configuration that really kills the HAL still steps down the next time).
     */
    private void undoFallbackStep(String id) {
        final Runnable undo = mFallbackUndo;
        mFallbackUndo = null;
        if (undo == null || id == null || !sFallbackUndone.add(id)) return;
        undo.run();
        Log.w("SCAM_CAPTURE", "camera " + id + ": the HAL closed the device after the failed configuration: the stream fallback step is undone");
    }

    /** The undo of the last configure fallback step of the current session (retryConfigureFallback), null without one. */
    private volatile Runnable mFallbackUndo;
    private static final java.util.Set<String> sFallbackUndone = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Until when the camera is being reopened quietly after a refused configuration (the HAL restarting: open retries). */
    private volatile long mQuietUntilMs;
    private static final long QUIET_REOPEN_MS = 12_000;
    /** P263: the quiet window of a first device error: open retries after it show the overlay. */
    private static final long QUIET_ERROR_MS = 6_000;
    /** P263: a device / service error was answered quietly since the last configured session. */
    private volatile boolean mQuietErrorSpent;

    private boolean quietRecovery() {
        return android.os.SystemClock.elapsedRealtime() < mQuietUntilMs;
    }

    private void scheduleRecovery(String reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (mRecoveries) {
            while (!mRecoveries.isEmpty() && now - mRecoveries.peekFirst() > 60_000) mRecoveries.pollFirst();
            if (mRecoveries.size() >= 3) {
                Log.e(TAG, "camera recovery stopped after three restarts within a minute (" + reason + ")");
                recovering(false, "gave up: " + reason);
                showError(Lang.t("Камера не отвечает. Выберите другой модуль или перезапустите приложение", "The camera is not responding. Choose another module or restart the app"));
                return;
            }
            mRecoveries.addLast(now);
        }
        long delay = 1000L << (mRecoveries.size() - 1);
        Log.w(TAG, "camera recovery in " + delay + " ms (" + reason + ")");
        if (mRecoveryHandler == null) mRecoveryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mRecoveryHandler.postDelayed(() -> {
            if (isCameraResumed && mCameraDevice == null && !mCameraOpening.get()) restartCamera();
        }, delay);
    }
    /** P186: when the open camera last died (onError), and how often its return has been waited for since. */
    private volatile long mDeviceErrorMs;
    private int mProviderWaits;
    /** Set when filling the characteristics failed (camera provider died during startup): refilled before the next open. */
    private volatile boolean mCharacteristicsIncomplete;
    /** Waits for the requested camera after a fallback to the main camera of its side (see {@link #awaitRequestedCamera}). */
    private CameraManager.AvailabilityCallback mAwaitCallback;
    private static final long AWAIT_REQUESTED_MS = 60_000;

    /**
     * Characteristics of the requested camera (Settings.mCameraID, "logical-physical" or a plain ID); logicalID / physicalID are
     * set to the camera that will open. A camera missing from the list read at start is asked for directly first: the
     * cached list can lack a hidden lens the camera service has. Without it, the main camera of the requested module's side
     * opens in its place and the module strip shows that camera's module: the old fallback took the first entry of the map,
     * so on the OPPO PHY110 a restart on the 2.8x tele (camera 4, list [1, 2, 3]) opened the front camera under the back
     * "2.8x" label. Null when that side has no camera at all (a recovery is scheduled).
     */
    private CameraCharacteristics requestedCharacteristics() {
        String curID = ScameraApp.getSettings().cameraId;
        if (curID.contains("-")) {
            logicalID = curID.split("-")[0];
            physicalID = curID.split("-")[1];
        } else {
            logicalID = curID;
            physicalID = curID;
        }
        Log.d(TAG, "ID:" + mCameraCharacteristicsMap.get(physicalID));
        CameraCharacteristics chars = mCameraCharacteristicsMap.get(physicalID);
        if (chars == null) chars = characteristicsOnDemand(physicalID);
        if (chars != null) {
            mProviderWaits = 0;
            routeXiaomiTele();
            return chars;
        }
        // P186 (X300 Ultra: a FULLRES mode the camera could not run killed it; the provider restarts and hides its cameras for a
        // moment): shortly after a device error the same camera is waited for, not replaced by the main camera of its side
        if (mDeviceErrorMs > 0 && android.os.SystemClock.elapsedRealtime() - mDeviceErrorMs < 15_000 && mProviderWaits < 12) {
            mProviderWaits++;
            Log.w(TAG, "camera " + physicalID + " not back yet after a device error: again in 700 ms (" + mProviderWaits + ")");
            mainHandler().postDelayed(() -> {
                if (isCameraResumed && mCameraDevice == null && !mCameraOpening.get()) restartCamera();
            }, 700);
            return null;
        }
        awaitAllCharacteristics(); // P680: the fallback is chosen from the whole list
        String requestedSlot = com.scamera.app.settings.ModuleRegistry.active();
        int wanted = com.scamera.app.settings.ModuleChoice.wantedFacing(requestedSlot, null);
        String side = com.scamera.app.settings.ModuleChoice.side(wanted);
        Map<String, Integer> facing = new HashMap<>();
        java.util.Set<String> auxiliary = new java.util.HashSet<>();
        for (Map.Entry<String, CameraCharacteristics> e : mCameraCharacteristicsMap.entrySet()) {
            CameraCharacteristics c = e.getValue();
            if (c == null) continue;
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (f != null) facing.put(e.getKey(), f);
            if (CameraManager2.isAuxiliarySensor(c)) auxiliary.add(e.getKey());
        }
        String fallback = com.scamera.app.settings.ModuleChoice.fallbackCamera(facing, auxiliary, wanted);
        if (fallback == null) {
            Log.e(TAG, "No characteristics for physicalID=" + physicalID + " (mCameraID=" + curID + ", module " + requestedSlot
                    + ") and no other " + side + " camera: not opened");
            scheduleRecovery("no " + side + " camera with characteristics");
            return null;
        }
        Log.e(TAG, "No characteristics for physicalID=" + physicalID + " (mCameraID=" + curID + ", module " + requestedSlot
                + "). Falling back to the main " + side + " camera " + fallback + ".");
        String module = com.scamera.app.settings.ModuleRegistry.moduleForCamera(side, fallback);
        if (module != null) {
            // The strip, the label and the zoom follow the camera that really opens.
            com.scamera.app.settings.ModuleRegistry.select(module);
            com.scamera.app.control.ZoomController.onButton(module);
        }
        PreferenceKeys.setCameraID(fallback);
        ScameraApp.getSettings().cameraId = fallback;
        awaitRequestedCamera(curID, requestedSlot, fallback, module);
        // P263 (owner: no camera errors on screen): the requested camera usually comes back at once (awaitRequestedCamera, OPPO
        // X7U 2.8x 2026-10-19); the note shows only when the main camera is still standing in for it after 3 s
        final String missing = logicalID;
        mainHandler().postDelayed(() -> {
            if (isCameraResumed && fallback.equals(ScameraApp.getSettings().cameraId))
                showError(Lang.t("Камера " + missing + " недоступна, открыта основная камера " + fallback,
                        "Camera " + missing + " is not available, the main camera " + fallback + " is open"));
        }, 3000);
        logicalID = fallback;
        physicalID = fallback;
        return mCameraCharacteristicsMap.get(fallback);
    }

    /** P41b: this camera runs through the logical SAT camera of the Xiaomi 17 Ultra (see {@link #routeXiaomiTele}). */
    private volatile boolean mXiaomiRouted;

    /**
     * P41b (the owner's 17U stock capture, 2026-10-09): the tele opens through the logical SAT camera that holds it, as the stock
     * camera does ({@link XiaomiTeleZoom#logicalRoute}). logicalID becomes that camera, physicalID stays the tele (its RAW
     * stream and characteristics); Settings.mCameraID is not changed. A "logical-physical" ID chosen by the user stays as it is.
     */
    private void routeXiaomiTele() {
        mXiaomiRouted = false;
        if (mVivoSdkRawSession != null) return;
        if (!Objects.equals(logicalID, physicalID)) return;
        // P641: a session that asks the 75 mm's sensor for a row of its own - the dual-gain switch (XiaomiDualGain) or a row picked
        // on the module («Режим сенсора»: the 4x ISZ kit's rows 12 / 26 / 29) - opens the camera alone: in the logical SAT session
        // the HAL chooses the row itself
        final String direct = XiaomiTeleIsz.directReason(physicalID, mCameraCharacteristicsMap.get(physicalID));
        if (direct != null) {
            Log.i(TAG, "Xiaomi 17 Ultra camera " + physicalID + " opens alone, not through the logical camera: " + direct + " (P641)");
            return;
        }
        if (VivoQcomModes.device() && VivoQcomModes.spoofPackage() != null
                && PreferenceKeys.scamDevSwitch("vivo_qcom_logical", false)) {
            try {
                CameraCharacteristics sat = mCameraManager.getCameraCharacteristics("2");
                if (sat.getPhysicalCameraIds().contains(physicalID)) {
                    logicalID = "2";
                    Log.i(TAG, "vivo Qualcomm: physical camera " + physicalID + " through logical camera 2");
                    return;
                }
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "vivo Qualcomm logical route unavailable: " + e.getMessage());
            }
        }
        try {
            String logical = XiaomiTeleZoom.logicalRoute(mCameraManager, physicalID, mCameraCharacteristicsMap.get(physicalID),
                    PreferenceKeys.scamDevSwitch("xiaomi_logical", true));
            if (logical != null && !logical.equals(physicalID)) {
                logicalID = logical;
                mXiaomiRouted = true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Xiaomi logical route: " + e.getMessage());
        }
    }

    /** P41b: the logical camera did not open: the tele opens alone (the route is off for this process). */
    private boolean retryWithoutXiaomiRoute(int width, int height, Exception e) {
        final Handler handler = mBackgroundHandler;
        if (!mXiaomiRouted || handler == null) return false;
        mXiaomiRouted = false;
        XiaomiTeleZoom.disableRoute("logical camera " + logicalID + " does not open: " + e.getMessage());
        handler.post(() -> openCamera(width, height));
        return true;
    }

    /** P41b: the logical camera refused the session or stalled: a regular session on it, then the tele alone. */
    private boolean retryXiaomiRoute(CameraCaptureSession session, String reason) {
        if (!mXiaomiRouted) return false;
        final boolean again = XiaomiTeleZoom.stepDownRoute(reason);
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        if (again) {
            final CameraDevice device = mCameraDevice;
            final Handler handler = mBackgroundHandler;
            if (device == null || handler == null) return false;
            handler.post(() -> {
                if (!isCameraResumed || mCameraDevice != device) return;
                startSession(false);
            });
        } else {
            mainHandler().post(() -> {
                if (isCameraResumed) restartCamera();
            });
        }
        return true;
    }

    /** Asks the camera service for a camera the start-up list did not have; null when it does not know it either. */
    private CameraCharacteristics characteristicsOnDemand(String id) {
        try {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            if (c != null) {
                mCameraCharacteristicsMap.put(id, c);
                Log.i(TAG, "characteristics of camera " + id + " fetched on demand");
            }
            return c;
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(TAG, "no characteristics for camera " + id + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * After a fallback: when the requested camera appears within {@link #AWAIT_REQUESTED_MS} (hidden lenses can show up later
     * in the process) and the fallback is still what runs, its module is selected again and the camera restarts on it.
     */
    private void awaitRequestedCamera(String requestedId, String requestedSlot, String fallbackId, String fallbackModule) {
        stopAwaitingRequestedCamera();
        if (requestedSlot == null || com.scamera.app.settings.ModuleChoice.side(requestedSlot) == null) return;
        final String logical = requestedId.contains("-") ? requestedId.split("-")[0] : requestedId;
        final String physical = requestedId.contains("-") ? requestedId.split("-")[1] : requestedId;
        final long deadline = SystemClock.elapsedRealtime() + AWAIT_REQUESTED_MS;
        final Handler main = new Handler(Looper.getMainLooper());
        CameraManager.AvailabilityCallback callback = new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(@NonNull String cameraId) {
                if (mAwaitCallback != this || !(logical.equals(cameraId) || physical.equals(cameraId))) return;
                boolean stillFallback = fallbackId.equals(PreferenceKeys.getCameraID()) && (fallbackModule == null
                        || fallbackModule.equals(com.scamera.app.settings.ModuleRegistry.active()));
                if (SystemClock.elapsedRealtime() > deadline || !stillFallback) { stopAwaitingRequestedCamera(); return; }
                if (!isCameraResumed || characteristicsOnDemand(physical) == null) return; // a later report may still work
                stopAwaitingRequestedCamera();
                Log.i(TAG, "camera " + requestedId + " appeared: back to module " + requestedSlot);
                com.scamera.app.settings.ModuleRegistry.select(requestedSlot);
                com.scamera.app.control.ZoomController.onButton(requestedSlot);
                PreferenceKeys.setCameraID(requestedId);
                ScameraApp.getSettings().cameraId = requestedId;
                restartCamera();
            }
        };
        mAwaitCallback = callback;
        try {
            mCameraManager.registerAvailabilityCallback(callback, main);
        } catch (RuntimeException e) {
            mAwaitCallback = null;
            return;
        }
        main.postDelayed(() -> { if (mAwaitCallback == callback) stopAwaitingRequestedCamera(); }, AWAIT_REQUESTED_MS);
    }

    /** The one-shot wait for the camera after onDisconnected (awaitCameraBack), null without one. */
    private volatile CameraManager.AvailabilityCallback mCameraBackCallback;
    /** When awaitCameraBack last reopened the camera (elapsedRealtime ms; 0: never). */
    private volatile long mCameraBackAtMs;
    /**
     * At most one such reopen per this time (no open / disconnect loop): one due sooner waits for the rest of it, the wait staying
     * registered (a disconnect right after a reopen is waited for too - the user tapping back and forth in split screen).
     */
    static final long CAMERA_BACK_MIN_MS = 3000;
    /** The window focus looked at again this long after a priorities change found it unfocused. */
    static final long CAMERA_BACK_FOCUS_MS = 300;

    /**
     * After onDisconnected while the camera screen stays resumed: the camera reopens (restartCamera, on the main thread) once it is
     * available again (the other client closed it, the service came back) or the camera access priorities changed while this
     * window has the focus (the user tapped back into it: it is the top one again). One shot; closeCamera ends it.
     */
    private void awaitCameraBack(String id) {
        stopAwaitingCameraBack();
        final CameraManager manager = mCameraManager;
        if (manager == null || id == null) return;
        final Handler main = new Handler(Looper.getMainLooper());
        final CameraManager.AvailabilityCallback callback = new CameraManager.AvailabilityCallback() {
            /** The camera reported available and not taken again since (the registration reports its state at once). */
            private boolean available;
            /** A reopen held back by CAMERA_BACK_MIN_MS is posted. */
            private boolean held;

            @Override
            public void onCameraAvailable(@NonNull String cameraId) {
                if (!id.equals(cameraId)) return;
                available = true;
                reopen("camera " + cameraId + " available again");
            }

            @Override
            public void onCameraUnavailable(@NonNull String cameraId) {
                if (id.equals(cameraId)) available = false;
            }

            @Override
            public void onCameraAccessPrioritiesChanged() {
                // only for the focused window: an open without the priority fails with «camera in use»
                if (focused()) { reopen("camera access priorities changed"); return; }
                // the window focus and the priorities reach the main thread separately, in no fixed order (in split screen the
                // other app keeps the camera when it loses the focus: no onCameraAvailable follows): looked at again shortly
                // (reopen ignores a callback that is no longer the wait)
                main.postDelayed(() -> { if (focused()) reopen("camera access priorities changed"); }, CAMERA_BACK_FOCUS_MS);
            }

            private boolean focused() {
                final Activity a = activity;
                return a != null && a.hasWindowFocus();
            }

            private void reopen(String why) {
                if (mCameraBackCallback != this) return;
                final long wait = mCameraBackAtMs == 0 ? 0
                        : CAMERA_BACK_MIN_MS - (SystemClock.elapsedRealtime() - mCameraBackAtMs);
                if (wait > 0) {
                    // held back, still waiting: then it reopens if the camera is still available or this window has the focus
                    // (an open without the priority fails with «camera in use»), else at the next change
                    if (held) return;
                    held = true;
                    Log.i(TAG, "camera " + id + " reopen (" + why + ") held back " + wait + " ms after the last one");
                    main.postDelayed(() -> {
                        held = false;
                        if (available || focused()) reopen(why);
                    }, wait);
                    return;
                }
                stopAwaitingCameraBack();
                if (!isCameraResumed || mCameraDevice != null || mCameraOpening.get()) return;
                Log.i(TAG, "camera " + id + " disconnected while resumed: reopened (" + why + ")");
                mCameraBackAtMs = SystemClock.elapsedRealtime();
                restartCamera();
            }
        };
        mCameraBackCallback = callback;
        try {
            manager.registerAvailabilityCallback(callback, main);
        } catch (RuntimeException e) {
            mCameraBackCallback = null;
            Log.w(TAG, "no wait for camera " + id + " after the disconnect: " + e);
        }
    }

    private void stopAwaitingCameraBack() {
        final CameraManager.AvailabilityCallback callback = mCameraBackCallback;
        mCameraBackCallback = null;
        final CameraManager manager = mCameraManager;
        if (callback != null && manager != null) {
            try { manager.unregisterAvailabilityCallback(callback); } catch (RuntimeException ignored) {}
        }
    }

    private void stopAwaitingRequestedCamera() {
        CameraManager.AvailabilityCallback callback = mAwaitCallback;
        mAwaitCallback = null;
        if (callback != null) {
            try { mCameraManager.unregisterAvailabilityCallback(callback); } catch (RuntimeException ignored) {}
        }
    }

    /** The GL viewfinder's texture events: the camera opens once the texture exists, a resize fits the transform again. */
    public final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            viewfinderTextureReady(texture);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            Log.d(TAG, "viewfinder texture now " + width + "x" + height);
            fitViewfinderTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            return true; // the GL viewfinder releases its own texture
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
            // every frame: nothing to do here (the GL viewfinder draws it)
        }
    };

    /** The viewfinder's texture exists: the camera the settings ask for opens at the preview size for this display. */
    private void viewfinderTextureReady(SurfaceTexture texture) {
        // the callback comes through the main thread: a texture the GL viewfinder replaced meanwhile is not the one to open for
        final SurfaceTexture live = mViewfinder == null ? null : mViewfinder.getSurfaceTexture();
        if (live != null && live != texture) {
            Log.d(TAG, "viewfinder texture replaced before its callback: ignored");
            return;
        }
        // a theme change recreates the activity after this fragment resumed: the dying fragment's preview may still report
        // its texture once its executor is shut down - the new fragment opens the camera
        if (processExecutor.isShutdown()) {
            Log.d(TAG, "viewfinder texture of a destroyed controller: ignored");
            return;
        }
        try {
            Log.d(TAG, "cameras known: " + mCameraCharacteristicsMap.keySet());
            final CameraCharacteristics chars = requestedCharacteristics();
            if (chars == null) return;
            final Size openSize = getPreviewOutputSize(chars, ScameraApp.getSettings().mode);
            openCamera(openSize.getWidth(), openSize.getHeight());
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            showError("Error onSurfaceTextureAvailable:" + e.getLocalizedMessage());
        }
    }

    public CaptureController(Activity host, ExecutorService pool, CameraEvents listener) {
        final long constructStart = System.nanoTime();
        trace("CaptureController.<init>");
        activity = host;
        cameraEventsListener = listener;
        processExecutor = pool;
        mViewfinder = host.findViewById(R.id.texture);
        mCameraManager = (CameraManager) host.getSystemService(Context.CAMERA_SERVICE);
        // Before the first camera list read: vendors filter hidden cameras by package.
        com.scamera.app.capture.spoof.CameraPackageSpoof.apply(host, mCameraManager);
        mLensDirectory = CameraManager2.get(mCameraManager, ScameraApp.getInstance(host).getSettingsManager()); // P680: cached
        PreferenceKeys.addIds(mLensDirectory.getCameraIdList());
        paramController = new ParamController(this);
        // P92: the processing foreground service runs while a shot is captured, queued or merged
        // P617: held weakly (the static supplier kept this controller and its activity after the activity was destroyed)
        com.scamera.app.processing.ProcessingKeepAlive.busy = com.scamera.app.processing.ProcessingKeepAlive.weakly(
                this, CaptureController::nextShotPending, () -> sScamPending.get() > 0 || isProcessing);
        // P680 (performance audit: the constructor ran on the main thread of CameraFragment.onViewCreated, the characteristics of
        // every camera read there): they are read on the processing pool, queued before any open; the open's loadLens reads its
        // own camera if it comes first, and whatever needs the whole list waits for them (awaitAllCharacteristics)
        mCharacteristicsRead = new java.util.concurrent.FutureTask<>(() -> {
            final long start = System.nanoTime();
            trace("CaptureController.readAllCharacteristics");
            try {
                readAllCharacteristics();
            } finally {
                endTrace();
            }
            Log.i("PERF", "camera characteristics of " + mCameraCharacteristicsMap.size() + " cameras read in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms on " + Thread.currentThread().getName());
        }, null);
        try {
            pool.execute(mCharacteristicsRead);
        } catch (RuntimeException rejected) {
            mCharacteristicsRead.run();
        }
        endTrace();
        Log.i("PERF", "CaptureController constructed in " + (System.nanoTime() - constructStart) / 1_000_000 + " ms on "
                + Thread.currentThread().getName() + " (the characteristics read on the pool)");
    }

    /** P680: the start-up read of every camera's characteristics (run on the pool by the constructor). */
    private final java.util.concurrent.FutureTask<Void> mCharacteristicsRead;

    /**
     * P680: the start-up read is done: run here when the pool has not started it yet, else waited for (at most 5 s). For what needs
     * the whole list (the fallback camera of a side, the viewfinder size without the open camera's characteristics).
     */
    void awaitAllCharacteristics() {
        final java.util.concurrent.FutureTask<Void> read = mCharacteristicsRead;
        if (read == null || read.isDone()) return;
        final long start = System.nanoTime();
        read.run(); // a no-op while the pool runs it
        try {
            read.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            Log.w(TAG, "camera characteristics not read: " + e);
        }
        Log.i("PERF", "camera characteristics awaited " + (System.nanoTime() - start) / 1_000_000 + " ms on " + Thread.currentThread().getName());
    }

    /** The camera id a listed id names: "logical-physical" ids stand for their physical camera. */
    static String listedCameraId(String listed) {
        final String[] parts = listed.split("-");
        return parts.length > 1 ? parts[1] : listed;
    }

    /**
     * Reads the characteristics of every camera the lens directory lists into {@link #mCameraCharacteristicsMap}; a camera the
     * provider cannot answer for leaves the map incomplete and schedules the bounded recovery (which reads them again).
     */
    private void readAllCharacteristics() {
        String failure = null;
        for (String listed : mLensDirectory.getCameraIdList()) {
            final String id = listedCameraId(listed);
            try {
                mCameraCharacteristicsMap.put(id, mCameraManager.getCameraCharacteristics(id));
            } catch (CameraAccessException | IllegalArgumentException e) {
                // IllegalArgumentException "unknown device" when the camera provider dies during startup (OPPO PHY110,
                // 2026-10-07: the app crashed in the CaptureController constructor). The other cameras are still read.
                Log.e(TAG, "characteristics of camera " + id + " unavailable: " + e.getMessage());
                if (failure == null) failure = "camera " + id + ": " + e.getMessage();
            }
        }
        mCharacteristicsIncomplete = failure != null;
        // The usual bounded recovery (three restarts a minute); the restart reads the characteristics again.
        if (failure != null) scheduleRecovery("camera characteristics unavailable (" + failure + ")");
    }

    public ParamController getParamController() { return paramController; }

    /** RAW_SENSOR (16-bit container), RAW10 or RAW12 — every one is unpacked to uint16 on copy. */
    public static boolean isRawFormat(int format) {
        return format == ImageFormat.RAW_SENSOR || format == ImageFormat.RAW10 || format == ImageFormat.RAW12;
    }
    /**
     * The RAW stream format of this camera: pref_raw_stream_format auto (RAW_SENSOR when the camera offers it,
     * else RAW10, else RAW12), or the forced format when the camera offers it. The ZSL ring and the live RAW viewfinder unpack
     * RAW10 / RAW12 (P316 LiveRawFrame; P365 the ring on arrival, ZslRing.store).
     * P365: "raw10" only where the camera's samples fit 10 bits ({@link #raw10Fits}): a lens of a wider white level keeps the auto
     * order, so nothing 12-bit is cut to 10.
     */
    private static int resolveRawFormat(StreamConfigurationMap map, @Nullable Integer whiteLevel, @Nullable CameraCharacteristics c,
                                        @Nullable String cameraId) {
        String mode = PreferenceKeys.getRawStreamFormat();
        // P560 (owner, 2026-11-08: «Ставь на всех смартфонах точно рав10 из коробки, кроме тех которые не поддерживают рав10»):
        // «auto» is RAW10 wherever it holds the camera's samples (autoRaw10); a camera whose packed session failed takes RAW_SENSOR
        if ("auto".equals(mode) && autoRaw10(whiteLevel, c, cameraId)) mode = "raw10";
        if (cameraId != null && sRaw16.contains(cameraId) && ("raw10".equals(mode) || "raw12".equals(mode))) mode = "raw16";
        if ("raw10".equals(mode) && !raw10Fits(whiteLevel)) mode = "auto";
        int[] order;
        if ("raw10".equals(mode)) order = new int[]{ImageFormat.RAW10, ImageFormat.RAW_SENSOR, ImageFormat.RAW12};
        else if ("raw12".equals(mode)) order = new int[]{ImageFormat.RAW12, ImageFormat.RAW_SENSOR, ImageFormat.RAW10};
        else order = new int[]{ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12};
        if (map != null) for (int format : order) {
            Size[] sizes = map.getOutputSizes(format);
            if (sizes != null && sizes.length > 0) return format;
        }
        return ImageFormat.RAW_SENSOR;
    }

    /**
     * P560: «auto» takes RAW10 (first, RAW_SENSOR then RAW12 where the camera lists none) when RAW10 holds the camera's samples: its
     * declared white fits 10 bits ({@link #raw10Fits}) and the session asks for no 14-bit DCG frames (Dcg14Mode.requested: the
     * switch turned on in our camera on the vivo X300 / X200 Ultra or the Xiaomi 15 Ultra, the Pixel 10 Pro's main) - every brand,
     * vivo / iQOO too, but not on a MediaTek SoC (P562: no RAW10 there, {@link #mediatek}) (owner, 2026-11-08: «Рав сенсор должен выбираться только в случае если используется DCG, из коробки у виво DCG
     * не работает, только при активации у нас в камере»). The DCG switch restarts the session, so the format follows it.
     * scam_dev.txt "auto_raw10 0": RAW_SENSOR first as before. RAW10 keeps the black where the HAL declares it on the OPPO Find X8
     * Ultra (its RAW16 drifted 64 -> 60.6 or lost it).
     */
    static boolean autoRaw10(@Nullable Integer whiteLevel, @Nullable CameraCharacteristics c, @Nullable String cameraId) {
        if (!raw10Fits(whiteLevel) || !PreferenceKeys.scamDevSwitch("auto_raw10", true)) return false;
        // P562 (owner, 2026-11-08: «На смартфонах на мтк рав10 не поддерживается»): MediaTek HALs keep RAW_SENSOR
        if (mediatek(Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER : null, Build.HARDWARE, Build.BOARD)) return false;
        try {
            if (Dcg14Mode.requested(c)) return false;
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    /** P562: a MediaTek SoC (SOC_MANUFACTURER on Android 12+, else the hardware / board name: mt6989, mt6991 ...). */
    static boolean mediatek(@Nullable String soc, @Nullable String hardware, @Nullable String board) {
        if (soc != null && soc.toLowerCase(java.util.Locale.ROOT).contains("mediatek")) return true;
        for (String n : new String[]{hardware, board}) {
            if (n == null) continue;
            final String l = n.toLowerCase(java.util.Locale.ROOT);
            if (l.startsWith("mt6") || l.startsWith("mt8") || l.contains("mediatek")) return true;
        }
        return false;
    }

    /** P365: the camera's declared white level (SENSOR_INFO_WHITE_LEVEL) fits a RAW10 sample (known and at most 1023). */
    static boolean raw10Fits(@Nullable Integer whiteLevel) {
        return whiteLevel != null && whiteLevel > 0 && whiteLevel <= 1023;
    }

    /**
     * The still size a stream's list gives: its largest size up to {@link ResolutionSolution#PHOTO_PIXELS} (30 MP), else the next
     * size down (a 200 MP sensor's binned size); {@code fallback} when the list is empty or holds just one size above the bound.
     * Ties in area keep the list's order (the later one counts as larger). The list itself is left as it is.
     */
    static Size stillSize(@Nullable Size[] sizes, @Nullable Size fallback) {
        if (sizes == null || sizes.length == 0) return fallback;
        final Size[] byArea = sizes.clone();
        Arrays.sort(byArea, BY_AREA);
        final int top = byArea.length - 1;
        if (byArea[top].getWidth() * byArea[top].getHeight() <= ResolutionSolution.PHOTO_PIXELS) return byArea[top];
        return top > 0 ? byArea[top - 1] : fallback;
    }

    /**
     * Takes the camera the settings name ("logical-physical" or one id) for the next session and reads what its session
     * needs; a camera that cannot be read shows the camera error (an SDK FULLRES setup reports it to its caller instead).
     */
    private void selectCamera(int width, int height) {
        previewW = width;
        previewH = height;
        try {
            final String[] ids = ScameraApp.getSettings().cameraId.split("-");
            logicalID = ids[0];
            physicalID = ids.length > 1 ? ids[1] : ids[0];
            routeXiaomiTele();
            loadLens(physicalID);
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            showError(activity.getString(R.string.camera_error));
            if (mVivoSdkRawSession != null) throw new IllegalStateException("SDK camera output setup failed", e);
        }
    }

    /** The session's callback belongs to the running, configured session of the foreground screen. */
    private boolean isCurrentPreviewSession(CameraCaptureSession session) {
        return isCameraResumed && session != null && session == mCaptureSession
                && mConfiguredSessionGeneration == mSessionGeneration.get() && mPreviewRequestBuilder != null;
    }

    private void clearZslPreviewFrames() {
        mOldstyleFrameLogs = 0; // P651: a new session logs its viewfinder frames again
        synchronized (mRingLock) {
            mHexZslResults.clear();
            for (RingFrame frame; (frame = mZslRingBuffer.pollFirst()) != null; ) frame.close();
        }
    }

    /**
     * Waits (on the processing pool, at most ~1 s in 1 ms steps) until the gyro has a sample for every frame of the series that
     * still counts and the saver holds them all (the ring's normal frames included).
     */
    private static void awaitSeriesFrames(@Nullable ScamCaptureSequence series, ImageSaver saver, int seriesFrames, int ringFrames) {
        final long start = System.nanoTime();
        if (!ImageSaver.awaitFrames(() -> seriesFramesIn(series, saver, seriesFrames, ringFrames), SERIES_WAIT_MS, SERIES_WAIT_SLICE_MS)) {
            Log.d(TAG, "GyroBurstTimeout");
            return;
        }
        final long waited = (System.nanoTime() - start) / 1_000_000;
        if (waited > 0) Log.i("PERF", "series frames awaited " + waited + " ms off the processing pool");
    }

    /** The series' frames are all there (see {@link #awaitSeriesFrames}). */
    private static boolean seriesFramesIn(@Nullable ScamCaptureSequence series, ImageSaver saver, int seriesFrames, int ringFrames) {
        final int expected = seriesFrames - (series != null ? series.droppedCount() : 0);
        return ScameraApp.getGyro().capturingNumber >= expected && saver.bufferSize() >= ringFrames + expected;
    }

    /** The series wait's limit (was 1000 sleeps of 1 ms) and its look between two signals (ImageSaver.framesChanged). */
    static final long SERIES_WAIT_MS = 1000, SERIES_WAIT_SLICE_MS = 5;

    /**
     * P680 (performance audit: the wait held one of the processing pool's two threads for up to 1 s, the pool the camera's open and
     * resume run on): {@code then} runs on the pool once the series' frames are in - at once when they are, else after a wait on a
     * thread of {@link #SERIES_WAIT} (signalled by the saver); inline when the pool is gone (the fragment destroyed).
     */
    private void awaitSeriesFramesThen(@Nullable ScamCaptureSequence series, ImageSaver saver, int seriesFrames, int ringFrames, Runnable then) {
        final Runnable onPool = () -> {
            try {
                processExecutor.execute(then);
            } catch (RuntimeException rejected) {
                then.run();
            }
        };
        if (seriesFramesIn(series, saver, seriesFrames, ringFrames)) {
            onPool.run();
            return;
        }
        try {
            SERIES_WAIT.execute(() -> {
                awaitSeriesFrames(series, saver, seriesFrames, ringFrames);
                onPool.run();
            });
        } catch (RuntimeException rejected) {
            onPool.run();
        }
    }

    /** P680: the threads the series waits run on (none while no shot waits). */
    private static final java.util.concurrent.ExecutorService SERIES_WAIT = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        final Thread t = new Thread(r, "SCAMERA-series-wait");
        t.setDaemon(true);
        return t;
    });

    /** P179: a shot above this many pixels (200 MP) is merged with the camera in its plain session. */
    static final long HUGE_SHOT_PIXELS = 100_000_000L;
    /** P609: the stream key suffix of a 50 MP shot binned from the 200 MP Tetra row (its own stored block: the Quad). */
    static final String TETRA_BIN_KEY = "|tetra-bin2";
    /** P179 (owner): frames of a 200 MP series, the last one ultrashort; the shortest ultrashort exposure asked for. */
    static final int HUGE_SERIES = 4;
    static final long ULTRASHORT_MIN_NS = 50_000L;
    /** P179: the camera was closed for a 200 MP merge; it opens again after it while the screen is up. */
    private volatile boolean mClosedForProcessing;
    /** P179: counted down once the camera closed for a 200 MP merge (the merge's memory budget is read after it). */
    private volatile java.util.concurrent.CountDownLatch mClosedForProcessingDone = new java.util.concurrent.CountDownLatch(0);

    /**
     * P179 (200 MP: the app was killed for LOW_MEMORY in the merge): the camera's 200 MP RAW buffers (4 x 401 MB) and the HAL's
     * own stay allocated while the 200 MP session runs. For a 200 MP shot, once its frames are copied, the camera goes over to
     * its plain session (FULLRES suspended: a live viewfinder, owner: «чтобы не было ... черного видоискателя в конце», at a
     * fraction of the memory; the SDK lease goes back as the device closes) and back to 200 MP after the merge.
     */
    private void closeCameraForProcessing(int megapixels) {
        mClosedForProcessing = true;
        // P214h: the switch and the shutter name the shot being merged (50 MP since P200, not only 200 MP)
        PixelPro10.suspendFullRes(true, megapixels);
        final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        mClosedForProcessingDone = closed;
        // posted (never run inside the capture's completion, which still clears the shot's state after this call)
        mainHandler().post(() -> {
            if (!mClosedForProcessing) { closed.countDown(); return; }
            Log.i("SCAM_CAPTURE", "FULLRES: the camera goes to its plain session for the merge (the FULLRES buffers go back)");
            try {
                if (isCameraResumed) restartCamera(); else closeCamera();
            } finally {
                closed.countDown();
            }
        });
    }

    /**
     * P179: the merge of a 200 MP shot starts once the camera's buffers are back (at most 3 s). P662 (series_done -> plain_ready
     * 342-374 ms of every 50 / 200 MP shot, a fixed 300 ms sleep after the close): it waits for the device's onClosed (the camera
     * service has closed the HAL device and its streams), the ZSL ring's release and the memory available to stop growing, within
     * {@link #CLOSED_FOR_PROCESSING_MS} - never longer than the old sleep - and logs the memory the merge's budget reads next.
     */
    private void awaitClosedForProcessing() {
        try {
            if (!mClosedForProcessingDone.await(3, TimeUnit.SECONDS)) {
                Log.w("SCAM_CAPTURE", "FULLRES: the camera did not close in 3 s");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        final java.util.concurrent.CountDownLatch device;
        synchronized (mDeviceClosedLock) { device = mDeviceClosed; }
        final ClosedWait wait = ClosedWait.run(CLOSED_FOR_PROCESSING_MS,
                left -> { try { return device.await(left, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; } },
                left -> com.scamera.app.capture.ZslRing.awaitReleases(left) >= 0 && com.scamera.app.capture.ZslRing.pendingReleases() == 0,
                com.scamera.app.processing.opengl.postpipeline.ScamHybridBurst::memoryAvailableNow,
                android.os.SystemClock::elapsedRealtime, android.os.SystemClock::sleep);
        Log.i("SCAM_CAPTURE", "PERF FULLRES: camera closed for the merge: " + wait);
    }

    /** P662: the longest awaitClosedForProcessing waits after the close (the fixed sleep it replaces). */
    static final long CLOSED_FOR_PROCESSING_MS = 300;

    /**
     * P662: the wait of awaitClosedForProcessing, its steps injected (unit tests): the device's onClosed, then the ring's
     * release, then the memory available read every {@link #STEP_MS} until it grew less than {@link #SETTLED_BYTES} a step for
     * {@link #SETTLED_STEPS} steps in a row - all within {@code budgetMs}.
     */
    static final class ClosedWait {
        static final long STEP_MS = 10;
        static final long SETTLED_BYTES = 16L << 20;
        /** Steps in a row the memory must stay within SETTLED_BYTES (30 ms: a HAL that frees its buffers in bursts). */
        static final int SETTLED_STEPS = 3;
        interface Await { boolean await(long leftMs); }
        interface Now { long get(); }
        interface Sleep { void sleep(long ms); }
        boolean closed, released, settled;
        long closedMs = -1, releasedMs = -1, totalMs, availBytes = -1;

        static ClosedWait run(long budgetMs, Await device, Await ring, Now avail, Now clock, Sleep sleep) {
            final ClosedWait w = new ClosedWait();
            final long start = clock.get(), end = start + budgetMs;
            w.closed = device.await(Math.max(0, end - clock.get()));
            w.closedMs = clock.get() - start;
            w.released = ring.await(Math.max(0, end - clock.get()));
            w.releasedMs = clock.get() - start;
            long previous = avail.get();
            int still = 0;
            while (previous > 0 && clock.get() + STEP_MS <= end) {
                sleep.sleep(STEP_MS);
                final long now = avail.get();
                still = now - previous < SETTLED_BYTES ? still + 1 : 0;
                previous = now;
                if (still >= SETTLED_STEPS) { w.settled = true; break; }
            }
            w.availBytes = previous;
            w.totalMs = clock.get() - start;
            return w;
        }

        @Override public String toString() {
            return "onClosed " + (closed ? "after " + closedMs + " ms" : "not seen") + ", ring " + (released ? "released by " + releasedMs + " ms" : "release pending")
                    + ", memory " + (settled ? "settled" : "still growing") + " at " + (availBytes >> 20) + " MB available, "
                    + totalMs + " ms in all (was a fixed " + CLOSED_FOR_PROCESSING_MS + " ms)";
        }
    }

    private void reopenCameraAfterProcessing() {
        mainHandler().post(() -> {
            if (!mClosedForProcessing) return;
            mClosedForProcessing = false;
            PixelPro10.suspendFullRes(false);
            boolean resumed = activity instanceof androidx.lifecycle.LifecycleOwner
                    && ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().getCurrentState()
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
            if (!resumed) return; // left meanwhile: onResume opens it at 200 MP
            Log.i("SCAM_CAPTURE", "FULLRES: the merge is done, the camera goes back to FULLRES");
            restartCamera();
        });
    }

    public void closeCamera() {
        closeCamera(false, false);
    }

    /**
     * P680 (performance audit: onPause closed the camera on the main thread, 97 ms in the log): a pause stops the viewfinder's
     * repeating request here and closes the session, the device and the readers on {@link #CAMERA_CLOSE}, as a restart does
     * (P662). P662 kept the pause synchronous so that the camera's outputs stop before the GL viewfinder drops their texture: the
     * repeating request stops here, and the texture itself is released only once the close is done (MainRenderer waits for
     * {@link #awaitCameraOutputsClosed} before it releases the old one). Synchronous as before while a vendor lease or SDK session
     * is held (its give-back follows the device's close at once, P640: the stock camera may open right after the pause), a video is
     * recorded, a FULLRES merge closed the camera, or scam_dev pause_close_async is 0.
     */
    public void closeCameraOnPause() {
        final boolean async = pauseCloseAsync(mIszLease != null, mVivoSdkRawSession != null || mVivoSdkRestorePending,
                recordingVideo || videoRecorder != null, mClosedForProcessing, PreferenceKeys.scamDevSwitch("pause_close_async", true));
        if (async) {
            final CameraCaptureSession session = mCaptureSession;
            try {
                if (session != null) session.stopRepeating();
            } catch (CameraAccessException | RuntimeException e) {
                Log.d(TAG, "pause: viewfinder request not stopped (" + e.getMessage() + "), the close stops it");
            }
        }
        closeCamera(false, async);
    }

    /** P680: whether a pause closes the camera off the main thread (see {@link #closeCameraOnPause}). */
    static boolean pauseCloseAsync(boolean leaseHeld, boolean sdkSession, boolean video, boolean closedForProcessing, boolean switchOn) {
        return switchOn && !leaseHeld && !sdkSession && !video && !closedForProcessing;
    }

    /** P680: counted down when the last async close is done (MainRenderer releases the old viewfinder texture after it). */
    private static volatile java.util.concurrent.CountDownLatch sOutputsClosed = new java.util.concurrent.CountDownLatch(0);

    /**
     * P680: waits (at most {@code timeoutMs}) until the camera closed off the main thread has closed its device - no output of it
     * writes into the viewfinder's texture any more; the ms waited.
     */
    public static long awaitCameraOutputsClosed(long timeoutMs) {
        final java.util.concurrent.CountDownLatch outputs = sOutputsClosed;
        if (outputs.getCount() == 0) return 0;
        final long start = System.nanoTime();
        try {
            outputs.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * P662: {@code keepRing} - a restart of the camera keeps the ZSL ring for the next session (reused when it fits, else released
     * on its first frame; {@link #releaseZslRing}); {@code async} - the session, the device, the readers and the surfaces close
     * on {@link #CAMERA_CLOSE} (65-95 ms on the main thread per lens switch on the OPPO X7U) while the open lock is held, so the
     * next open (openOnPool takes the lock) starts only once the old camera is closed. The fields are cleared here, before this
     * returns, as before. A pause closed synchronously (its outputs stop before the GL viewfinder drops their texture); P680:
     * {@link #closeCameraOnPause} keeps that order with the close off the main thread.
     */
    void closeCamera(boolean keepRing, boolean async) { // package-private: CameraResumeTest verifies the restart's close
        final long closeStart = System.nanoTime();
        synchronized (mRingLock) { mRingClosing = true; } // P662: a late RAW of this camera makes no new ring (onRawImage race)
        if (mBackgroundHandler != null) mBackgroundHandler.removeCallbacks(mDropParkedRing);
        stopAwaitingCameraBack();
        boolean wasOpening = mCameraOpening.get();
        VivoSdkFullResCapture sdkCapture = mVivoSdkCapture;
        mVivoSdkCapture = null;
        if (sdkCapture != null) sdkCapture.cancel();
        rescueInFlightShot("camera closed");
        // P371 (review M2d): a merge worker started at the shutter and not taken by its job yet holds the ZSL ring's memfd.
        // P662: not when the camera closes for this shot's own merge (FULLRES): the worker started for its arena is the merge's
        if (!mClosedForProcessing)
            com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.discardStandby("camera closed");
        com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.sessionArena(0, 0); // P662: no ring until the next session's
        final long ringStart = System.nanoTime();
        releaseZslRing(keepRing && !mClosedForProcessing);
        final long ringNs = System.nanoTime() - ringStart;
        mStabTrace.stop();
        mPreviewSessionStartMs = 0;
        if (mBackgroundHandler != null) mBackgroundHandler.removeCallbacks(mStallCheck);
        mScamQueuedShots = 0;
        ScamStockAe stock=mStockAe;mStockAe=null;if(stock!=null)stock.close();
        mScamRingFrozen=false;
        clearFlashShot(); // P590
        // Invalidate callbacks before releasing their resources. onConfigured and
        // capture results can still arrive after returning from Settings.
        synchronized (mPreviewStateLock) {
            isCameraResumed = false;
            mSessionGeneration.incrementAndGet();
            mPreviewRequestBuilder = null;
            mPreviewCaptureResult = null;
            mPreviewCaptureRequest = null;
            mShotInProgress = false;
        }
        clearZslPreviewFrames();
        mMeterUnpacked = null; // P365 (review): the meters' unpacked frame (meterSamples), made again when a meter needs it
        mVfWbCopy = null; // P597: the white balance's copy (24 MB at 4080 x 3072; a measurement still running holds its own reference)
        resetEttr(); // P356: the next camera meters its own boost (the ISP viewfinder's display gain goes with it)
        LiveRawFrame.setEnabled(false);
        LiveRawFrame.setMosaicPreview(false);
        mLiveRawSession = false;
        unspektraAe.reset();
        mMosaicPreview = false;
        mNativeRawPslCapture = false;
        mLiveRawRouter.clear();
        mLiveMetadata.clear();
        mCameraOpening.set(false);
        try {
            mCameraOpenCloseLock.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        }
        boolean handedOff = false;
        try {
            final CameraCaptureSession closingSession = mCaptureSession;
            mCaptureSession = null;
            final CameraDevice device = mCameraDevice;
            mCameraDevice = null;
            if (device != null) {
                // P662: onClosed of this device counts it down (awaitClosedForProcessing waits for it, not a fixed 300 ms)
                synchronized (mDeviceClosedLock) {
                    mClosingDevice = device;
                    mDeviceClosed = new java.util.concurrent.CountDownLatch(1);
                }
            }
            final ImageReader sdkJpeg = mVivoSdkJpeg;
            mVivoSdkJpeg = null;
            // a still being processed still reads from its readers: they close with the next session instead
            final ImageReader still, raw, view, companion;
            final boolean readers = !isProcessing;
            if (readers) {
                still = mImageReaderPreview; raw = mImageReaderRaw; view = mImageReaderRawView;
                companion = mRecipeCompanion; // P635
                mImageReaderPreview = null;
                mImageReaderRaw = null;
                mImageReaderRawView = null;
                mRecipeCompanion = null;
            } else {
                still = raw = view = companion = null;
            }
            final MediaRecorder recorder = videoRecorder;
            videoRecorder = null;
            final Surface viewSurface = surface;
            surface = null;
            mShotState = SHOT_CLOSED;
            // P680: what the close below gives back is taken now - an open may run beside an async close up to the device's open
            // (openOnPool) and make its own readers, LOFIC readers or (after its wait) lease; the close never touches those
            final VivoSdkRawSession leaseAtClose = mIszLease;
            final VivoSdkRawSession sdkAtClose = mVivoSdkRawSession;
            final ImageReader loficReader = readers ? mLoficReader : null;
            final ImageReader[] loficReaders = readers ? loficSessionReaders() : null;
            final java.util.concurrent.CountDownLatch outputs = async ? new java.util.concurrent.CountDownLatch(1) : null;
            if (outputs != null) sOutputsClosed = outputs;
            // the same calls in the same order as before P662, on this thread or on the close thread
            final Runnable close = () -> {
                final long start = System.nanoTime();
                trace("CaptureController.closeDevice");
                try {
                    if (closingSession != null) closingSession.close();
                    if (device != null) {
                        device.close();
                        giveIszLeaseBack(leaseAtClose); // P640
                    } else if (!wasOpening) {
                        synchronized (mVivoSdkLock) { if (mVivoSdkRawSession == sdkAtClose) closeVivoSdkRawSession(null); }
                        giveIszLeaseBack(leaseAtClose); // P640: a lease taken for an open that failed (no device, so no onClosed) went nowhere
                    }
                    closeReader(sdkJpeg);
                    if (readers) {
                        closeReader(still);
                        closeReader(raw);
                        closeReader(view);
                        closeReader(companion);
                        closeLoficReader(loficReader); // P641d
                        closeLoficSession(loficReaders); // P679
                    }
                    if (recorder != null) recorder.release();
                    if (viewSurface != null) viewSurface.release();
                } catch (RuntimeException e) {
                    Log.w(TAG, "camera close: " + e);
                } finally {
                    endTrace();
                    if (outputs != null) outputs.countDown();
                    mCloseDoneNs = System.nanoTime();
                    mAsyncCloseInFlight = false;
                    mCameraOpenCloseLock.release();
                }
                if (async) Log.i("PERF", "camera session / device / readers closed off the main thread in "
                        + (System.nanoTime() - start) / 1_000_000 + " ms");
            };
            if (async) {
                mAsyncCloseInFlight = true;
                try {
                    CAMERA_CLOSE.execute(close);
                    handedOff = true;
                } catch (RuntimeException rejected) {
                    mAsyncCloseInFlight = false;
                    outputs.countDown(); // closed below, on this thread
                }
            }
            if (!handedOff) {
                handedOff = true; // the close below releases the lock itself
                close.run();
            }
        } finally {
            if (!handedOff) mCameraOpenCloseLock.release();
        }
        // P662: the main thread's share of the close (the ring's release runs on its own thread since P662)
        Log.i("PERF", "camera closed in " + (System.nanoTime() - closeStart) / 1_000_000 + " ms (ring release "
                + ringNs / 1_000_000 + (async ? ", device close off the main thread" : "") + ") on "
                + Thread.currentThread().getName());
    }

    /** P662: the thread the camera's session, device and readers close on for a restart (closeCamera(…, true)). */
    private static final java.util.concurrent.ExecutorService CAMERA_CLOSE = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "SCAMERA-camera-close");
        t.setDaemon(true);
        return t;
    });
    /** P662: a close handed to {@link #CAMERA_CLOSE} still holds the open lock (openOnPool waits longer for it). */
    private volatile boolean mAsyncCloseInFlight;
    /** P680: when the last close finished (System.nanoTime; the open's PERF line tells the overlap). */
    private volatile long mCloseDoneNs;

    /** P680: android.os.Trace sections (Perfetto) of the camera's start; never a failure of their own. */
    static void trace(String name) {
        try {
            android.os.Trace.beginSection(name);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    static void endTrace() {
        try {
            android.os.Trace.endSection();
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
    /** P662: the device being closed and the latch its onClosed counts down. */
    private final Object mDeviceClosedLock = new Object();
    private CameraDevice mClosingDevice;
    private java.util.concurrent.CountDownLatch mDeviceClosed = new java.util.concurrent.CountDownLatch(0);
    /**
     * P662: set (under mRingLock) when the camera starts closing, cleared once the next session is configured: a RAW frame of the
     * closing camera that arrives after the ring went (closeCamera releases it first) is closed instead of making a new 1.3-1.6
     * GB ring (OPPO log: «ZSL ring in memfd» 68-190 ms after a restart, ring 47 instead of 50 - the old ring was still mapped).
     */
    private boolean mRingClosing;

    private static void closeReader(@Nullable ImageReader reader) {
        if (reader != null) reader.close();
    }

    /** Starts the camera thread the session, request and image callbacks run on (once; it lives until stopBackgroundThread). */
    public void startBackgroundThread() {
        if (mCameraThread != null) return;
        final HandlerThread thread = new HandlerThread("CameraBackground");
        thread.start();
        mCameraThread = thread;
        mBackgroundHandler = new Handler(thread.getLooper());
        Log.d(TAG, "camera thread started (asked on " + Thread.currentThread().getName() + ")");
    }

    /** Lets the camera thread finish what it has queued and waits for it to end. */
    public void stopBackgroundThread() {
        final HandlerThread thread = mCameraThread;
        if (thread == null) return;
        thread.quitSafely();
        try {
            thread.join();
            mCameraThread = null;
            mBackgroundHandler = null;
            Log.d(TAG, "camera thread stopped (asked on " + Thread.currentThread().getName() + ")");
        } catch (InterruptedException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * P99: the sensor mode the Qualcomm HAL reports (com.qti.sensorbps.mode_index; the Galaxy S24 Ultra showed 2..34), logged
     * when it changes, so a vendor key added per module (Settings, sensor settings per module, «Добавить vendor tag») can be
     * seen to switch the mode. Silent where the HAL has no such tag.
     */
    private void logSensorModeIndex(CaptureResult result) {
        if (mCroppedRawSession && result != null) {
            android.graphics.Rect crop = CroppedRaw.of(result, physicalID, logicalID);
            // P100b (S24 Ultra, 2026-05-18: x1.0 at zoom 2.62 for ~1 s): which result held the crop, and the zoom the HAL ran
            final boolean physical = !Objects.equals(physicalID, logicalID) && crop != null
                    && Objects.equals(crop, CroppedRaw.of(physicalResult(result)));
            Float ratio = null;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    ratio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
                } catch (RuntimeException ignored) {
                    // not reported
                }
            }
            final boolean zoomStep = ratio != null && Math.abs(ratio - mLastRawCropZoom) >= 0.25f;
            if (crop != null && (!CroppedRaw.same(crop, mLastRawCrop) || zoomStep)) {
                android.graphics.Rect active = mCameraCharacteristics == null ? null
                        : mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Log.i("SCAM_CAPTURE", "raw crop " + crop + " of " + active + ": x" + CroppedRaw.factor(crop, active)
                        + (CroppedRaw.factor(crop, active) > 1f ? " (in-sensor zoom)" : "")
                        + " [" + (physical ? "physical " + physicalID : "logical " + logicalID) + " result, zoomRatio " + ratio + "]");
                mLastRawCrop = crop;
                if (ratio != null) mLastRawCropZoom = ratio;
            }
        }
        if (sModeIndexMissing || result == null) return;
        int[] v;
        try {
            // the physical camera's own mode in a logical session (S24 Ultra), else the logical result's
            CaptureResult own = physicalResult(result);
            v = own != null ? own.get(SENSOR_MODE_INDEX) : null;
            if (v == null) v = result.get(SENSOR_MODE_INDEX);
        } catch (RuntimeException unknown) {
            sModeIndexMissing = true;
            return;
        }
        if (v == null || v.length == 0 || v[0] == mLastModeIndex) return;
        Log.i("SCAM_CAPTURE", "sensor mode index " + mLastModeIndex + " -> " + v[0] + " (camera " + physicalID + ")");
        mLastModeIndex = v[0];
    }

    private float mLastRawCropZoom = -1f;

    /** The physical camera's own result inside a logical session's TotalCaptureResult, or null. */
    private CaptureResult physicalResult(CaptureResult result) {
        if (!(result instanceof TotalCaptureResult) || Build.VERSION.SDK_INT < 28 || Objects.equals(physicalID, logicalID)) return null;
        try {
            return ((TotalCaptureResult) result).getPhysicalCameraResults().get(physicalID);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final CaptureResult.Key<int[]> SENSOR_MODE_INDEX = new CaptureResult.Key<>("com.qti.sensorbps.mode_index", int[].class);
    private static volatile boolean sModeIndexMissing;
    private int mLastModeIndex = Integer.MIN_VALUE;

    /** The viewfinder's AE range: the fixed rate the fps setting picks (1: 24, 2: 30, 3: 60), else the lens's own. */
    private Range<Integer> videoFpsRange() {
        final int mode = ScameraApp.getSettings().previewFps;
        final int fixed = mode == 1 ? 24 : mode == 2 ? 30 : mode == 3 ? 60 : 0;
        return fixed > 0 ? new Range<>(fixed, fixed) : FpsRangeAuto;
    }
    /** Digital/optical zoom of the current module: CONTROL_ZOOM_RATIO (R+) or SCALER_CROP_REGION. */
    private void applyZoom(CaptureRequest.Builder builder, boolean force) {
        // P17: the Xiaomi 17 Ultra tele zooms optically (75-100 mm) and by ISZ (150-200 mm) over vendor keys
        if (builder != null && mCameraCharacteristics != null) {
            try {
                // P41: a shot request takes the preview's keys as they are (it never toggles the sensor mode itself)
                if (builder != mPreviewRequestBuilder && XiaomiTeleZoom.applyLast(builder, physicalID)) {
                    com.scamera.app.control.ZoomController.overrideResidual(XiaomiTeleZoom.last().residual);
                    return;
                }
                boolean wasIsz = XiaomiTeleZoom.isz();
                final float moduleZoom = com.scamera.app.settings.ModuleRegistry.zoom(com.scamera.app.settings.ModuleRegistry.active());
                final float zoom = com.scamera.app.control.ZoomController.zoom();
                boolean wasActive = XiaomiTeleZoom.active();
                // P41, 17U dumps 2026-10-08: the HAL's own optical zoom report / target (dev switches to go back)
                XiaomiTeleZoom.halOptics(PreferenceKeys.scamDevSwitch("xiaomi_hal_optics", true),
                        PreferenceKeys.scamDevSwitch("xiaomi_opt_target", true));
                XiaomiTeleZoom.Plan plan = XiaomiTeleZoom.apply(builder, mCameraCharacteristics,
                        ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getBoolean(XiaomiTeleZoom.PREF, true),
                        moduleZoom, zoom, physicalID, PreferenceKeys.scamDevSwitch("xiaomi_lens_check", true),
                        PreferenceKeys.scamDevSwitch("xiaomi_crop_mode", false),
                        PreferenceKeys.scamDevSwitch("xiaomi_isz", false));
                if (plan != null) {
                    com.scamera.app.control.ZoomController.overrideResidual(plan.residual);
                    // P41: the first request of a session already carries its mode; only a change inside the session re-measures
                    if (plan.isz != wasIsz && (wasActive || plan.isz)) onSensorModeChangedInSession(plan.isz);
                    // a toggle held back by the debounce happens once it expires, also when the slider stopped
                    Handler handler = mBackgroundHandler;
                    if (builder == mPreviewRequestBuilder && XiaomiTeleZoom.togglePending(moduleZoom, zoom) && handler != null) {
                        // one retry at a time, and only for this session (not after a module switch or a closed camera)
                        mXiaomiToggleGeneration = mSessionGeneration.get();
                        handler.removeCallbacks(mXiaomiToggleRetry);
                        handler.postDelayed(mXiaomiToggleRetry, XiaomiTeleZoom.TOGGLE_MS);
                    }
                    return;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Xiaomi tele zoom: " + e.getMessage());
            }
        }
        final float z = com.scamera.app.control.ZoomController.residual();
        if (builder == null || (z <= 1.001f && !force)) return;
        try {
            final CameraCharacteristics lens = mCameraCharacteristics;
            if (lens == null) return;
            // Android 11+: the zoom ratio key (held within the lens's range); older: a centred crop of the active array
            final Range<Float> ratios = Build.VERSION.SDK_INT >= 30 ? lens.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
            if (ratios != null) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratios.clamp(z));
                return;
            }
            final Rect active = lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active != null) builder.set(CaptureRequest.SCALER_CROP_REGION, centredCrop(active, z));
        } catch (RuntimeException e) {
            Log.w(TAG, "applyZoom: " + e.getMessage());
        }
    }

    /** The crop of {@code active} a digital zoom {@code z} shows: centred, each side divided by z (rounded). */
    static Rect centredCrop(Rect active, float z) {
        final int w = Math.round(active.width() / z);
        final int h = Math.round(active.height() / z);
        final int left = active.left + (active.width() - w) / 2;
        final int top = active.top + (active.height() - h) / 2;
        return new Rect(left, top, left + w, top + h);
    }

    /** P41: the deferred ISZ toggle of the Xiaomi tele; dropped when the session it was posted for is gone. */
    private volatile int mXiaomiToggleGeneration = -1;
    private final Runnable mXiaomiToggleRetry = () -> {
        if (isCameraResumed && mXiaomiToggleGeneration == mSessionGeneration.get() && XiaomiTeleZoom.active()) onZoomChanged();
    };

    /** P41: the result that carries the tele's own focal length (its physical result for a physical stream of a logical camera). */
    private CaptureResult xiaomiLensResult(TotalCaptureResult result) {
        if (Build.VERSION.SDK_INT >= 28 && !Objects.equals(physicalID, logicalID)) {
            try {
                CaptureResult physical = result.getPhysicalCameraResults().get(physicalID);
                if (physical != null) return physical;
            } catch (RuntimeException ignored) {
                // no physical results: the logical one
            }
        }
        return result;
    }

    /**
     * The stream changed its sensor mode inside the session (Xiaomi ISZ): the buffered ZSL frames belong to the other mode and
     * are dropped as on a module switch, and the colour block of the stream is measured again (ISZ may deliver a Quad mosaic).
     */
    private void onSensorModeChangedInSession(boolean isz) {
        clearZslPreviewFrames();
        // P41: the frames already in flight still come from the old mode (the 17U log measured the old mode's block for the
        // new one and stored it). Until a frame of the new mode arrives, RAW frames neither enter the ZSL ring nor the
        // colour-block measurement; the measurement of the new mode starts with that frame.
        mModeBarrierKey = mosaicStreamKey() + (isz ? "|isz" : "");
        mModeBarrierFrames = 0;
        mModeBarrierMatching = 0;
        mModeBarrier = true;
        // the new mode's key at once (a shot meanwhile finds its stored block); no frame is measured before the barrier opens
        com.scamera.app.processing.MosaicStream.startSession(mMosaicMeasure ? mModeBarrierKey : "off");
        // P41: the Xiaomi tele's ISP preview outside ISZ; in ISZ (raw colour mosaic from the ISP) the developed RAW viewfinder,
        // at once when this mode's block is stored, else once the measurement finds it
        if (mMosaicPreview && (XiaomiTeleZoom.ispPreview() || com.scamera.app.processing.MosaicStream.block() <= 1)) {
            mMosaicPreview = false;
            LiveRawFrame.setMosaicPreview(false);
            if (!mLiveRawSession) LiveRawFrame.setEnabled(false);
        } else if (!mMosaicPreview && !mLiveRawSession && !recordingVideo && mMosaicMeasure && !XiaomiTeleZoom.ispPreview()
                && com.scamera.app.processing.MosaicStream.block() > 1) {
            mMosaicPreview = true;
            LiveRawFrame.setEnabled(true);
            LiveRawFrame.setMosaicPreview(true);
            Log.i(TAG, "ISZ: stored colour block " + com.scamera.app.processing.MosaicStream.block() + ", developed RAW viewfinder");
        }
        Log.i(TAG, "sensor mode changed in session (ISZ " + (isz ? "on" : "off") + "): ZSL ring dropped, waiting for the first frame of the new mode");
    }

    /** P41: RAW frames after an in-session sensor mode change are dropped until one of the new mode arrives. */
    private volatile boolean mModeBarrier;
    private volatile String mModeBarrierKey = "";
    private int mModeBarrierFrames, mModeBarrierMatching;

    /** True when the RAW frame may be used (no barrier, or the first frame of the new mode releases it). */
    private boolean passModeBarrier(Image img) {
        return passModeBarrier(img, null);
    }

    /** {@code matched}: the frame's own result when the caller has it (live RAW matcher). */
    private boolean passModeBarrier(Image img, TotalCaptureResult matched) {
        if (!mModeBarrier) return true;
        TotalCaptureResult r = matched;
        if (r == null) synchronized (mRingLock) { r = mHexZslResults.get(img.getTimestamp()); }
        // an image whose result has not arrived yet is newer than the latest completed result: that one stands for it
        CaptureResult latest = mPreviewCaptureResult;
        if (r == null && latest instanceof TotalCaptureResult) r = (TotalCaptureResult) latest;
        mModeBarrierFrames++;
        if (XiaomiTeleZoom.requestMatches(r)) mModeBarrierMatching++;
        if (!XiaomiTeleZoom.frameReady(r, mModeBarrierFrames, mModeBarrierMatching)) return false;
        mModeBarrier = false;
        Log.i(TAG, "first frame of the new sensor mode after " + mModeBarrierFrames + " frames"
                + (r == null ? " (no result)" : " (request mode " + XiaomiTeleZoom.requestMode(r) + ", reported " + XiaomiTeleZoom.resultMode(r) + ")"));
        return true;
    }

    /** One zoom update waits at a time: the slider sends many, the request is rebuilt at most once per pending update. */
    private final java.util.concurrent.atomic.AtomicBoolean mZoomPending = new java.util.concurrent.atomic.AtomicBoolean();

    /** Zoom changed inside the current module: push it to the repeating preview request. */
    public void onZoomChanged() {
        final Handler handler = mBackgroundHandler;
        if (handler == null) pushZoom();
        else if (mZoomPending.compareAndSet(false, true)) handler.post(mZoomUpdate); // the pending one reads the newest zoom
    }

    private final Runnable mZoomUpdate = this::pushZoom;

    private void pushZoom() {
        mZoomPending.set(false);
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b == null || mCaptureSession == null) return;
        applyZoom(b, true);
        rebuildPreviewBuilder();
    }

    /** P411: «Мерцание ламп» changed (the shade): the repeating request takes it at once, as the reference's updateAntibanding. */
    public void applyAntibanding() {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b == null) return;
        Antibanding.apply(b, mCameraCharacteristics);
        rebuildPreviewBuilder();
    }

    /** The viewfinder's request as the repeating request now (its edits take effect); nothing while no session runs. */
    public void rebuildPreviewBuilder() {
        sendPreview(true);
    }

    /**
     * The viewfinder's request once, beside the repeating one (an AF trigger that must not repeat); per frame in the auto AF
     * mode, so nothing is logged on the way.
     */
    public void rebuildPreviewBuilderOneShot() {
        sendPreview(false);
    }

    private void sendPreview(boolean repeating) {
        final CameraCaptureSession session = mCaptureSession;
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        if (burst || builder == null || session == null) return;
        try {
            if (repeating && mVivoSdkRawSession != null) applyVivoSdkPreview(builder);
            unspektraAe.apply(builder);
            if (repeating) session.setRepeatingRequest(mRepeatingRequest = builder.build(), mCaptureCallback, mBackgroundHandler);
            else session.capture(builder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            // a session closing under us
            Logger.warnShort(TAG, repeating ? "Cannot rebuildPreviewBuilder()!" : "Cannot rebuildPreviewBuilderOneShot()!", e);
        } catch (CameraAccessException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * The viewfinder's transform for a view of this size once the preview size is known: the GL viewfinder rotates and fits
     * the frame itself, only the mirror is ours.
     */
    private void fitViewfinderTransform(int viewWidth, int viewHeight) {
        if (mViewfinder != null && mPreviewSize != null) applyViewfinderMirror();
    }

    /**
     * Orientation and mirror of the viewfinder for the camera being opened. P36: they apply from the new camera's first
     * frame (GLPreview / MainRenderer), not to the previous camera's frame still on screen.
     */
    private void applyViewfinderMirror() {
        final CameraCharacteristics lens = mCameraCharacteristics;
        if (mViewfinder == null || lens == null) return;
        final Integer facing = lens.get(CameraCharacteristics.LENS_FACING);
        final boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        mViewfinder.setViewfinderTransform(mSensorOrientation + 90, front);
    }

    /** The sizes the open lens lists for the still format (empty when it lists none). */
    private ArrayList<Size> listedRawSizes() {
        final StreamConfigurationMap streams =
                mCameraCharacteristicsMap.get(physicalID).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        final Size[] listed = streams.getOutputSizes(mTargetFormat);
        return listed == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(listed));
    }
    @SuppressLint("MissingPermission")
    /**
     * Preview stall watchdog, every second on the camera thread while a session runs: the camera stopped delivering preview
     * frames (black viewfinder, vivo 5x tele) while no shot is in flight: log the state and restart the camera, as switching the
     * module did by hand. At most PreviewStall.MAX_RESTARTS restarts in a row without a frame in between.
     */
    /**
     * A dead device: the preview-stall watchdog stops (it restarted the camera behind the error recovery and past its limit on
     * the OPPO Find X9 Ultra, 2026-10-10), and the session is dropped so no control reaches the closed device.
     */
    private void stopStallWatch() {
        mPreviewSessionStartMs = 0;
        if (mBackgroundHandler != null) mBackgroundHandler.removeCallbacks(mStallCheck);
        mCaptureSession = null;
    }

    private void checkPreviewStall() {
        final Handler handler = mBackgroundHandler;
        if (handler == null || !isCameraResumed || mPreviewSessionStartMs == 0) return;
        handler.postDelayed(mStallCheck, 1000);
        final CameraMode mode = ScameraApp.getSettings().mode;
        if (recordingVideo || mShotInProgress || mZslCapturing || mHybridZslCapture || mScamRingFrozen || burst
                || (mode != CameraMode.PHOTO && mode != CameraMode.NIGHT && mode != CameraMode.MOTION)) return;
        final long now = android.os.SystemClock.elapsedRealtime();
        // A shot stops or holds the preview for a moment on some routes: none of that counts.
        if (sTimelineSubmitNs > 0 && now - sTimelineSubmitNs / 1_000_000L < 6000) return;
        if (!PreviewStall.stalled(now, mLastPreviewResultMs, mPreviewSessionStartMs, mPreviewFrameMs)) return;
        final long noFrame = now - Math.max(mLastPreviewResultMs, mPreviewSessionStartMs);
        Log.w(TAG, "PREVIEW_STALL camera " + physicalID + ": no preview frame for " + noFrame + " ms ("
                + (mLastPreviewResultMs == 0 ? "none since the session started" : "frames stopped") + ", session "
                + (now - mPreviewSessionStartMs) + " ms) liveRaw=" + mLiveRawSession + " mosaicPreview=" + mMosaicPreview
                + " block=" + com.scamera.app.processing.MosaicStream.block() + " scamRouted=" + mScamRouted
                + " frameMs=" + mPreviewFrameMs + " restartsInARow=" + mStallRestarts);
        // P635: a recipe session without a viewfinder frame: the profile fails, the normal session opens (never a black viewfinder)
        if (mRecipe != null && retryWithoutRecipe(mCaptureSession, "no preview frame for " + noFrame + " ms ("
                + (mLastPreviewResultMs == 0 ? "none since the session started" : "frames stopped") + ")")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        // P679: a LOFIC session without viewfinder frames: its setup failed, the next one (or the normal session) opens
        if (XiaomiLofic.sessionActive() && retryWithoutLofic(mCaptureSession, "no preview frame for " + noFrame + " ms ("
                + (mLastPreviewResultMs == 0 ? "none since the session started" : "frames stopped") + ")")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        // P186: a native FULLRES session that never gave a frame runs a mode the camera cannot: FULLRES goes off (its mode is
        // noted on the X100 / X300 Ultra) instead of the same session again and again
        if (mLastPreviewResultMs == 0 && mVivoSdkRawSession != null && nativeVivoFullRes()
                && retryWithoutVivoSdkFullRes(mCaptureSession, "no preview frame for " + noFrame + " ms")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        // P604: a DCG / DAG row whose frames stopped before it proved its depth (the PYB110 tele's row 14: three restarts of the
        // same row, no verdict, log 2026-11-10 16:06:05 - 16:06:15) fails like one that never gave a frame; the next row runs
        if (mLastPreviewResultMs != 0 && SensorModePick.session() != null && SensorModePick.session().dcg() && OplusDcg.unproven(physicalID)
                && retryWithoutRequestedSensorMode(mCaptureSession, "no preview frame for " + noFrame + " ms (the frames stopped before the row proved its depth)")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        // P575: a nubia row's session (FULLRES or the «2×») that never gave a frame: the row is not used, the normal session opens
        if (mLastPreviewResultMs == 0 && SensorModePick.session() != null
                && (NubiaFullRes.device() || SensorModePick.session().route != null) // P586: an OPPO-family ISZ / DCG / DAG row too
                && retryWithoutRequestedSensorMode(mCaptureSession, "no preview frame for " + noFrame + " ms")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        // P598: a nubia remosaic-probe session that never gave a frame: that setting failed, the next one runs
        if (mLastPreviewResultMs == 0 && mFullResSession && NubiaRemosaicProbe.running(physicalID)
                && retryNubiaProbe(mCaptureSession, "no preview frame for " + noFrame + " ms")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        // P230: a DCG route's session (Xiaomi 15 Ultra op mode 0x9203, X200 Ultra Telegram) that never gave a frame: DCG off
        if (mLastPreviewResultMs == 0 && retryWithoutDcgRoute(mCaptureSession, "no preview frame for " + noFrame + " ms")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        // P315 (owner, 2026-10-21: «проверь чтобы ни на одном смартфоне такого не было и не было перезапуска после снятия
        // кадра»): a stall that began at a shot (Xiaomi 12X session type 0x9001: the series complete, then no preview frame)
        // gets the repeating preview request once more before the camera restarts; the restart follows a second later if no
        // frame comes
        final long submitMs = sTimelineSubmitNs / 1_000_000L;
        if (sTimelineSubmitNs > 0 && mLastPreviewResultMs > 0 && mLastPreviewResultMs <= submitMs + 2000
                && mStallRearmSubmitNs != sTimelineSubmitNs && mCaptureSession != null && mPreviewRequestBuilder != null) {
            mStallRearmSubmitNs = sTimelineSubmitNs;
            try {
                mCaptureSession.setRepeatingRequest(mRepeatingRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                Log.w(TAG, "PREVIEW_STALL after a shot: the repeating preview issued again, the camera restarts only if no frame follows");
                return;
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "PREVIEW_STALL after a shot: the repeating preview could not be issued again (" + e + ")");
            }
        }
        if (!PreviewStall.mayRestart(mStallRestarts)) {
            mPreviewSessionStartMs = 0;   // one log line, no more restarts until the next session
            Log.w(TAG, "PREVIEW_STALL: " + mStallRestarts + " restarts brought no frame, leaving the camera as it is");
            return;
        }
        // P41b: a stalled logical session steps down (a regular session, then the tele alone) before the restart
        if (mXiaomiRouted) XiaomiTeleZoom.stepDownRoute("preview stall, no frame for " + noFrame + " ms", mLastPreviewResultMs != 0);
        // P631: a picked Xiaomi row whose session had the ISP stream: the restart's session leaves it out (the 17 Ultra's full-size
        // row stopped after six frames with «Поток ISP» On and failed every request with «Фиктивное откл.», log of 31256)
        final SensorModePick.Pick stalledPick = SensorModePick.session();
        if (stalledPick != null && !mIspSurfaceOff && XiaomiSensorRow.device())
            XiaomiSensorRow.noteIspRefused(physicalID, stalledPick.mode, "no preview frame for " + noFrame + " ms, "
                    + (mLastPreviewResultMs == 0 ? "none since the session started" : "frames stopped"));
        mStallRestarts++;
        mPreviewSessionStartMs = 0;
        handler.removeCallbacks(mStallCheck);
        activity.runOnUiThread(() -> {
            if (!isCameraResumed) return;
            Log.w(TAG, "PREVIEW_STALL: restarting the camera");
            restartCamera();
        });
    }

    /**
     * Closes the camera and opens it again through the resume path (readers and outputs are prepared before the device opens
     * on a live camera thread - opening first raced onOpened against the reader replacement).
     */
    public void restartCamera() {
        Log.d(TAG, "camera restart (asked on " + Thread.currentThread().getName() + ")");
        com.scamera.app.ui.camera.views.viewfinder.VfDrawMeter.restart(); // P616: the reopen's gap is no stutter
        if (recordingVideo) stopVideoClip();
        closeCamera(true, true); // P662: the ring kept for the next session, the device closed off this thread
        if (mCharacteristicsIncomplete) readAllCharacteristics();
        mLiveRawRejected = false;
        cameraEventsListener.cameraRestarted();
        startBackgroundThread();
        screenResumed();
    }
    /** The viewfinder's aspect as short : long side: 9:16 for video and the 16:9 setting, else 3:4. */
    private static Size viewfinderAspect(CameraMode mode) {
        final boolean wide = mode == CameraMode.VIDEO || mode == CameraMode.RAWVIDEO || ScameraApp.getSettings().wide169;
        return wide ? new Size(9, 16) : new Size(3, 4);
    }

    /** The viewfinder view's display, or the window's while the view is not attached. */
    private Display getSafeDisplay() {
        final Display own = mViewfinder == null ? null : mViewfinder.getDisplay();
        //noinspection deprecation
        return own != null ? own : activity.getWindowManager().getDefaultDisplay();
    }

    /** The GL viewfinder's drawing size: the screen's short side across, the aspect's long side along. */
    private static Size getTextureOutputSize(Display display, CameraMode mode) {
        final Size aspect = viewfinderAspect(mode);
        final Point screen = new Point();
        display.getRealSize(screen);
        final int across = Math.min(screen.x, screen.y);
        return new Size(across * aspect.getHeight() / aspect.getWidth(), across);
    }

    /**
     * The ISP viewfinder stream's size (portrait, short x long): the first SurfaceTexture size the lens lists in the viewfinder's
     * aspect within {@link ResolutionSolution#PREVIEW_PIXELS}; 800 x 600 when the lens is unknown or lists none.
     */
    private Size getPreviewOutputSize(@Nullable CameraCharacteristics lens, CameraMode mode) {
        if (lens == null) {
            awaitAllCharacteristics(); // P680: the start-up read runs on the pool
            if (mCameraCharacteristicsMap.isEmpty()) readAllCharacteristics();
            lens = mCameraCharacteristicsMap.get(physicalID);
            if (lens != null) mCameraCharacteristics = lens;
        }
        final StreamConfigurationMap streams = lens == null ? null : lens.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        final Size fit = streams == null ? null : firstPreviewFit(streams.getOutputSizes(SurfaceTexture.class), viewfinderAspect(mode));
        return fit != null ? fit : new Size(800, 600);
    }

    /** The first of {@code sizes} exactly in {@code aspect} (short : long) within the preview pixel bound, portrait; null if none. */
    @Nullable
    static Size firstPreviewFit(@Nullable Size[] sizes, Size aspect) {
        if (sizes == null) return null;
        for (Size size : sizes) {
            final int across = Math.min(size.getWidth(), size.getHeight());
            final int along = Math.max(size.getWidth(), size.getHeight());
            final boolean inAspect = along % aspect.getHeight() == 0 && across == aspect.getWidth() * along / aspect.getHeight();
            if (inAspect && across * along <= ResolutionSolution.PREVIEW_PIXELS) return new Size(across, along);
        }
        return null;
    }

    /** P365 step 9: the long side of the ISP stream under the RAW viewfinder (the reference app's 1440x1080 on the X7 Ultra). */
    static final int LIGHT_PREVIEW_LONG = 1440;

    /**
     * P365 step 9: the smallest of {@code sizes} (sensor-oriented SurfaceTexture sizes) of {@code aspect} (short : long, as
     * getAspect) whose long side is at least {@link #LIGHT_PREVIEW_LONG}, portrait (short x long) as getPreviewOutputSize gives
     * {@code current}; {@code current} when none is smaller.
     */
    static Size lightPreviewSize(@Nullable Size[] sizes, Size aspect, Size current) {
        Size best = current;
        long bestArea = (long) current.getWidth() * current.getHeight();
        if (sizes != null) for (Size size : sizes) {
            final int sizeShort = Math.min(size.getWidth(), size.getHeight()), sizeLong = Math.max(size.getWidth(), size.getHeight());
            if (sizeLong < LIGHT_PREVIEW_LONG || sizeLong % aspect.getHeight() != 0
                    || sizeShort != aspect.getWidth() * sizeLong / aspect.getHeight()) continue;
            final long area = (long) sizeShort * sizeLong;
            if (area < bestArea) {
                best = new Size(sizeShort, sizeLong);
                bestArea = area;
            }
        }
        return best;
    }

    /**
     * P365 step 9 (scam_dev vf_isp_light; default on the OPPO Find X7 Ultra, DeviceDefaults.ispLightViewfinder): under the RAW
     * viewfinder the ISP stream is the reference app's - 1440x1080 class, noise reduction and edge off on the repeating request.
     */
    private static boolean ispLight() {
        return PreferenceKeys.scamDevSwitch("vf_isp_light", com.scamera.app.settings.DeviceDefaults.ispLightViewfinder());
    }

    /** P365 step 9: the repeating request's noise reduction / edge modes before vf_isp_light set them off (null: not applied). */
    private volatile Integer[] mIspLightModes;

    /**
     * P365 step 9: noise reduction and edge OFF on the repeating request while the RAW viewfinder is shown (the camera must list
     * the OFF mode); the stills keep the template's modes (mIspLightModes), the ISP picture the user sees is never made softer.
     */
    private void applyIspLight(CaptureRequest.Builder builder) {
        mIspLightModes = null;
        if (builder == null || !mLiveRawSession || recordingVideo || mFullResSession || !ispLight()) return;
        CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
        if (c == null) c = mCameraCharacteristics;
        if (c == null) return;
        final boolean nr = listed(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        final boolean edge = listed(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CaptureRequest.EDGE_MODE_OFF);
        if (!nr && !edge) return;
        mIspLightModes = new Integer[]{builder.get(CaptureRequest.NOISE_REDUCTION_MODE), builder.get(CaptureRequest.EDGE_MODE)};
        if (nr) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        if (edge) builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        Log.i("SCAM_CAPTURE", "camera " + physicalID + ": RAW viewfinder, the repeating request's" + (nr ? " noise reduction" : "")
                + (nr && edge ? " and" : "") + (edge ? " edge" : "") + " off (P365 vf_isp_light)");
    }

    /** P365 step 9: the ISP picture is what the user sees now (the RAW viewfinder gave up): the template's modes again. */
    private void restoreIspModes() {
        final Integer[] modes = mIspLightModes;
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        if (modes == null || builder == null) return;
        mIspLightModes = null;
        if (modes[0] != null) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, modes[0]);
        if (modes[1] != null) builder.set(CaptureRequest.EDGE_MODE, modes[1]);
        Log.i("SCAM_CAPTURE", "camera " + physicalID + ": ISP viewfinder, noise reduction / edge back to " + modes[0] + " / " + modes[1] + " (P365)");
        rebuildPreviewBuilder();
    }

    private static boolean listed(@Nullable int[] modes, int mode) {
        if (modes != null) for (int m : modes) if (m == mode) return true;
        return false;
    }

    /**
     * The shutter's first 3A step: one AF trigger START (the repeating request goes back to IDLE at once - owner, 2026-10-18
     * «оппо не фокусируется»: a trigger left in the repeating request re-sent itself on every frame); the shot waits for the lock.
     */
    private void lockFocus() {
        final CameraCaptureSession session = mCaptureSession;
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        if (burst) return;
        if (builder == null || session == null) {
            Log.w(TAG, "AF lock with no running session (builder=" + builder + " session=" + session + ")");
            return;
        }
        startThreeAWait();
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        mShotState = SHOT_AF_LOCK;
        try {
            session.capture(builder.build(), mCaptureCallback, mBackgroundHandler);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(builder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | RuntimeException e) {
            failPendingShutter(e);
        }
    }

    /** The shutter's second 3A step, once AF has locked with AE still moving: one AE precapture trigger START. */
    private void startAePrecapture() {
        final CameraCaptureSession session = mCaptureSession;
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        if (burst || builder == null || session == null) return;
        try {
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mShotState = SHOT_AE_START;
            session.capture(builder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | RuntimeException e) {
            failPendingShutter(e);
        }
    }

    private String physicalID = "";
    /** P120: ImageReader.newInstance aborts the process natively at 63 images (SCAM TEST: X300 Ultra, nubia NX563J); a session takes 59. */
    static final int MAX_READER_IMAGES = 59;

    /** P87: the physical camera the session's RAW and characteristics come from (the manual rulers' range is kept per camera). */
    public String physicalId() {
        return physicalID;
    }
    private String logicalID = "";

    /**
     * Opens the camera specified by {@link Settings#mCameraID}.
     */
    private void prepareVivoSdkRawSession() throws Exception {
        if (mVivoSdkRestorePending) closeVivoSdkRawSession(null);
        // P179: a FULLRES lease left by a process of ours that died (LOW_MEMORY) is given back before a camera opens
        // (any phone of the native route, its switch on or off: a lease of a session that was killed must come back)
        // P640: and whatever else is set and held by nobody (a give that failed, values left with no journal)
        if (mVivoSdkRawSession == null && VivoNativeFullRes.phone() != null) {
            final String swept = VivoSdkRawSession.sweep(ScameraApp.getAppContext());
            if (!"none".equals(swept) && !"held".equals(swept)) Log.w("SCAM_CAPTURE", "vivo SDK lease left set (a camera opens): " + swept);
        }
        if (!mVivoSdkClosed.await(2000, TimeUnit.MILLISECONDS))
            throw new IllegalStateException("Previous Vivo SDK camera has not closed");
        String id = ScameraApp.getSettings().cameraId;
        CameraMode mode = ScameraApp.getSettings().mode;
        // P192: an OPPO camera's table from the characteristics the app reads (its own CameraManager is not used); P214j: OnePlus
        // and realme too
        if (OppoNativeFullRes.family()) {
            CameraCharacteristics known = mCameraCharacteristicsMap.get(id);
            if (known == null) try { known = mCameraManager.getCameraCharacteristics(id); } catch (Exception ignored) { known = null; }
            OppoNativeFullRes.learn(id, known);
        }
        closeIszLease(); // P275: a lease of the camera before (normally given back when it closed)
        if (!VivoSdkFullRes.available(id) || !PixelPro10.fullResOn()
                || !PreferenceKeys.isScamEnabled()
                || !VivoSdkFullRes.supportsMode(mode) || recordingVideo) {
            openIszLease(id);
            return;
        }
        synchronized (mVivoSdkLock) {
            // P192: the OPPO Find X7 Ultra's native modes need no vivo SDK lease
            mVivoSdkRawSession = VivoSdkFullRes.oppo() ? VivoSdkRawSession.inert() : VivoSdkRawSession.open();
            mVivoSdkCameraId = id;
            mVivoSdkRestorePending = false;
            mVivoSdkRestoreAttempts = 0;
            mVivoSdkClosed = new java.util.concurrent.CountDownLatch(1);
            mVivoSdkCallbackThread = new HandlerThread("VivoSdkCameraClose");
            mVivoSdkCallbackThread.start();
            mVivoSdkCallbackHandler = new Handler(mVivoSdkCallbackThread.getLooper());
            mCameraCharacteristicsMap.remove(id);
        }
        // P198: a native 50 MP session takes its shots' N from a ZSL ring of its frames; 200 MP and the SDK route stay serial PSL
        final String route = nativeZslSession() ? "ZSL ring + the Hybrid plan's frames after the shutter" : "serial PSL";
        Log.i("SCAM_CAPTURE", (nativeVivoFullRes() ? "NATIVE FULLRES" : "SDK FULLRES50") + " camera=" + id + " size=" + VivoSdkFullRes.profile(id).size
                + (VivoSdkFullRes.oppo() ? ": own RAW AE and white balance (the mode's HAL has no statistics), " + route + ", SHDR processing"
                : ": automatic AE, " + route + ", SHDR processing"));
    }

    /**
     * P198 (owner, 2026-10-17: «Так же сделай так чтобы 50мп снимались в zsl и только 200мп снимались в psl»): the running session
     * is a native FULLRES one of up to 100 MP (VivoSdkFullResCapture.zslRoute): its frames go to the ZSL ring and a shot takes its N
     * from there, as the 12 MP Hybrid. scam_dev.txt "fullres_zsl 0": the serial PSL series of every native size, as before.
     */
    private boolean nativeZslSession() {
        if (mVivoSdkRawSession == null || !PreferenceKeys.isScamEnabled() || !PreferenceKeys.isFullRes50Zsl()) return false;
        return VivoSdkFullResCapture.zslRoute(VivoSdkFullRes.profile(mVivoSdkCameraId));
    }

    /** P198: the N frames a native 50 MP shot asks of the ring (P190's «Кадров в снимке 50 Мп», 0 = the merge's frame setting). */
    private static int fullResZslWanted() {
        return VivoSdkFullResCapture.zslRequested(PreferenceKeys.getFullRes50Frames(), PreferenceKeys.getHybridZslFrames());
    }

    /** The FULLRES session runs the native route: a Quad / Tetra row, or P194's hardware-remosaic Bayer row (X100 / X300 Ultra). */
    /** P223: this session runs without the ISP preview stream (set with mLiveRawSession). */
    private boolean mIspPreviewOff;

    /** P320: the shot about to be taken comes from a DCG sensor mode (its processing knows it even if the switch moves). */
    private boolean dcgShotNow() {
        try {
            return Dcg14Mode.frames(mCameraCharacteristics);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean nativeVivoFullRes() {
        VivoSdkFullRes.Profile profile = mVivoSdkRawSession == null ? null : VivoSdkFullRes.profile(mVivoSdkCameraId);
        return profile != null && profile.nativeRoute();
    }

    /** P609: the ISP stream of this OPPO-family native FULLRES session (OppoNativeFullRes.ispStream: 0 none, 1 fake off, 2 on). */
    private int oppoIspStream() {
        return mVivoSdkRawSession == null || !VivoSdkFullRes.oppo() ? 0
                : OppoNativeFullRes.ispStream(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId));
    }

    /** P684: what a still-only series' stills proved, for the log: their mosaic and the HAL's reported full-size mode. */
    static String mosaicWords(@Nullable VivoSdkFullResCapture series) {
        final int block = series == null ? -1 : series.deliveredBlock();
        final Long seamless = series == null ? null : series.seamlessSeen();
        return (block == 4 ? "the sensor's native Tetra (block 4)" : block == 2 ? "a native Quad (block 2)"
                : block == 1 ? "the HAL's remosaicked Bayer (block 1)" : "no confident colour block")
                + ", seamlessMode " + (seamless == null ? "-" : String.valueOf(seamless));
    }

    /** P684: the variant of this 200 MP still-only session (StillOnly200), null in any other session. */
    @Nullable private volatile StillOnly200.Choice mStillChoice;

    /** P684: this still-only session runs the X300 Ultra stock camera's full-size keys (X300FullSize). */
    private boolean fullSizeRecipe() {
        final StillOnly200.Choice c = mStillChoice;
        return c != null && c.variant.recipe && stillOnlyFullRes();
    }

    /**
     * P684: the decision frame of the stock full-size recipe: the viewfinder request without the viewfinder RAW (the YUV companion
     * only), at the viewfinder's shutter and the lowest ISO, the widest AE range, com.vivo.current_ui_module 74; the viewfinder's
     * builder as it was afterwards. Null without the companion or a viewfinder result.
     */
    @Nullable
    private CaptureRequest fullSizeDecision(@Nullable CameraCharacteristics c) {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        final ImageReader view = mImageReaderRawView, companion = mRecipeCompanion;
        final CaptureResult latest = mPreviewCaptureResult;
        final Long ns = latest == null ? null : latest.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (b == null || view == null || companion == null || ns == null || ns <= 0) return null;
        final Range<Integer> iso = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        final Range<Integer>[] ranges = c == null ? null : c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int lo = Integer.MAX_VALUE, hi = 0;
        if (ranges != null) for (Range<Integer> r : ranges) { lo = Math.min(lo, r.getLower()); hi = Math.max(hi, r.getUpper()); }
        final Integer aeMode = b.get(CaptureRequest.CONTROL_AE_MODE), isoBefore = b.get(CaptureRequest.SENSOR_SENSITIVITY);
        final Long nsBefore = b.get(CaptureRequest.SENSOR_EXPOSURE_TIME), frameBefore = b.get(CaptureRequest.SENSOR_FRAME_DURATION);
        final Range<Integer> fpsBefore = b.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        try {
            b.removeTarget(view.getSurface());
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso == null ? 100 : iso.getLower());
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(ns, 33_333_333L));
            if (hi > 0 && lo <= hi) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(lo, hi));
            final List<String> unknown = X300FullSize.applyDecision(b);
            if (!unknown.isEmpty()) Log.w("SCAM_CAPTURE", "200 MP decision frame keys unknown to this firmware: " + unknown + " (P684)");
            return b.build();
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "200 MP decision frame not built: " + e + " (P684)");
            return null;
        } finally {
            X300FullSize.clearDecision(b);
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, nsBefore);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoBefore);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameBefore);
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsBefore);
            b.addTarget(view.getSurface());
        }
    }

    /** P208: the native FULLRES session keeps its viewfinder in the normal mode, the full-size stream takes the stills only. */
    private boolean stillOnlyFullRes() {
        VivoSdkFullRes.Profile profile = mVivoSdkRawSession == null ? null : VivoSdkFullRes.profile(mVivoSdkCameraId);
        return profile != null && profile.nativeRoute() && profile.stillOnly;
    }

    /**
     * P275 (owner, 2026-10-19: «для того чтобы ISZ и фулрез работали на виво, в том числе и 200у, не обязательна подпись
     * android.camera.cts»): ISZ ran on the X300 Ultra while the app was org.codeaurora.snapcam (log 2026-10-15 / 16: raw crop
     * x2.0 / x4.0, Quad / Tetra blocks) and stopped with the CTS package (2026-10-19: forceSensorMode on the session and every
     * request, the normal mode). The vivo SDK lease of FULLRES - VivoCameraManager 33 (VCF) = 2, 40 (SDK RAW) = 1 - taken before
     * the camera opens hands the HAL our vendor keys whatever the package; the values found are given back when it closes.
     */
    private void openIszLease(String id) {
        synchronized (mIszLeaseLock) {
            if (mIszLease != null || recordingVideo || !(VivoQcomIsz.leaseWanted(id) || VivoPd2454NativeRaw.leaseWanted(id) || SensorModePick.leaseWanted(id))) return;
            try {
                mIszLease = VivoSdkRawSession.open();
                mCameraCharacteristicsMap.remove(id);
                Log.i("SCAM_CAPTURE", "ISZ camera " + id + ": vivo SDK lease taken (VCF 33 = 2, SDK RAW 40 = 1), given back on close (found "
                        + mIszLease.found() + ")");
            } catch (Exception failure) {
                mIszLease = null;
                Log.w("SCAM_CAPTURE", "ISZ camera " + id + ": vivo SDK lease refused: " + failure);
            }
        }
    }

    private void closeIszLease() {
        synchronized (mIszLeaseLock) {
            final VivoSdkRawSession lease = mIszLease;
            if (lease == null) return;
            mIszLease = null;
            try {
                lease.close();
                Log.i("SCAM_CAPTURE", "ISZ: vivo SDK lease given back");
            } catch (Exception failure) {
                Log.e(TAG, "ISZ: vivo SDK properties not restored: " + failure);
                LeaseGuard.sweep("a give-back failed", 700); // P640: the values stay on the lease's books until they are back
            }
        }
    }

    /**
     * P640 (owner, X200 Ultra: the stock camera's viewfinder black after ours until a reboot): the lease of the camera that was
     * just closed goes back on the lease thread now - CameraDevice.close() has disconnected the HAL - without waiting for
     * onClosed (a callback of a thread that may be gone). Only that lease: a camera opened meanwhile has its own.
     */
    private void giveIszLeaseBack() {
        giveIszLeaseBack(mIszLease);
    }

    /** P680: {@link #giveIszLeaseBack()} for {@code lease}, the lease of the camera closeCamera closed (taken when it scheduled the close). */
    private void giveIszLeaseBack(@Nullable VivoSdkRawSession lease) {
        try {
            if (lease != null) LeaseGuard.executor().execute(() -> {
                synchronized (mIszLeaseLock) {
                    if (mIszLease == lease) closeIszLease();
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "ISZ: lease give-back not queued: " + e);
        }
        LeaseGuard.later(this::leaseWithoutCamera, 1200);
    }

    /**
     * P640: 1.2 s after a camera closed - a lease this controller still holds with no camera open and none opening went
     * nowhere (a callback that never came): it goes back; then what is set and held by nobody ({@link LeaseGuard#sweep}).
     */
    private void leaseWithoutCamera() {
        if (mCameraDevice == null && !mCameraOpening.get()) {
            synchronized (mIszLeaseLock) {
                if (mIszLease != null) {
                    Log.w("SCAM_CAPTURE", "ISZ: vivo SDK lease held with no camera open: given back");
                    closeIszLease();
                }
            }
            synchronized (mVivoSdkLock) {
                if (mVivoSdkRawSession != null && !VivoSdkFullRes.oppo() && mCameraDevice == null && !mCameraOpening.get()) {
                    Log.w("SCAM_CAPTURE", "FULLRES: vivo SDK lease held with no camera open: given back");
                    closeVivoSdkRawSession(mVivoSdkRawDevice);
                }
            }
        }
        LeaseGuard.sweep("a camera closed", 0);
    }

    private void closeVivoSdkRawSession(@Nullable CameraDevice device) {
        synchronized (mVivoSdkLock) {
            if (mVivoSdkRawSession == null || device != mVivoSdkRawDevice) return;
            try {
                mVivoSdkRawSession.close();
                mVivoSdkRawSession = null;
                mNativeAe = null;
                OppoNativeFullRes.end();
                mVivoSdkRawDevice = null;
                mVivoSdkRestorePending = false;
                mCameraCharacteristicsMap.remove(mVivoSdkCameraId);
                mVivoSdkCameraId = null;
                mVivoSdkClosed.countDown();
                if (mVivoSdkCallbackThread != null) mVivoSdkCallbackThread.quitSafely();
                mVivoSdkCallbackThread = null;
                mVivoSdkCallbackHandler = null;
            } catch (Exception failure) {
                mVivoSdkRawDevice = null;
                mVivoSdkRestorePending = true;
                Log.e(TAG, "SDK provider properties not restored: " + failure);
                if (++mVivoSdkRestoreAttempts <= 3 && mVivoSdkCallbackHandler != null)
                    mVivoSdkCallbackHandler.postDelayed(() -> closeVivoSdkRawSession(null), 500);
                else showError(Lang.t("Не удалось восстановить параметры SDK камеры", "Camera SDK properties could not be restored"));
            }
        }
    }

    /**
     * Opens the camera the settings name, for a viewfinder of this size, on the processing pool. The texture listener and
     * screenResumed can both ask for the same surface event: a second open while one is in flight is dropped (it would fail
     * with CAMERA_IN_USE and kill the preview).
     */
    public void openCamera(int width, int height) {
        if (mCameraDevice != null || !mCameraOpening.compareAndSet(false, true)) {
            Log.d(TAG, "camera open already under way: this one dropped");
            return;
        }
        if (processExecutor.isShutdown()) {
            // This controller's fragment is destroyed (activity recreated); never open a camera nobody closes.
            mCameraOpening.set(false);
            Log.d(TAG, "camera open for a destroyed controller: dropped");
            return;
        }
        processExecutor.execute(() -> openOnPool(width, height));
    }

    private void openOnPool(int width, int height) {
        trace("CaptureController.openOnPool");
        try {
            openOnPoolTraced(width, height);
        } finally {
            endTrace();
        }
    }

    /**
     * P680: whether an open waits for the previous camera's async close before it prepares anything (as P662 did): while a vendor
     * lease or SDK session is held (the close gives it back after the device: the open must not give it back first), or when this
     * open takes one (vivo / OPPO-family FULLRES, ISZ, the PD2454 native RAW, a sensor-mode pick - taken only once the previous
     * device closed, P640). Otherwise only the device's open waits (the open lock) and the readers, sizes and characteristics are
     * made meanwhile; the sweep of a dead process's lease (vivo) finds the cameras busy then and waits for the next open.
     */
    static boolean closeWaitFirst(boolean leaseOrSdkHeld, boolean leaseWanted, boolean sdkWanted) {
        return leaseOrSdkHeld || leaseWanted || sdkWanted;
    }

    private boolean closeWaitFirst(String id) {
        boolean leaseWanted, sdkWanted;
        try {
            leaseWanted = VivoQcomIsz.leaseWanted(id) || VivoPd2454NativeRaw.leaseWanted(id) || SensorModePick.leaseWanted(id);
            sdkWanted = VivoSdkFullRes.offered(id);
        } catch (RuntimeException e) {
            return true;
        }
        return closeWaitFirst(mIszLease != null || mVivoSdkRawSession != null || mVivoSdkRestorePending, leaseWanted, sdkWanted);
    }

    /** P662: waits (at most 5 s) until a close handed to CAMERA_CLOSE released the open lock; the ms waited. */
    private long awaitAsyncClose() {
        final long waitStart = System.nanoTime();
        try {
            if (mCameraOpenCloseLock.tryAcquire(5000, TimeUnit.MILLISECONDS)) mCameraOpenCloseLock.release();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return (System.nanoTime() - waitStart) / 1_000_000;
    }

    private void openOnPoolTraced(int width, int height) {
        final long openStart = System.nanoTime();
        // P662: a restart's close runs on CAMERA_CLOSE holding the open lock. P680: an open of the vendor lease path waits for it
        // here, before the SDK lease, the readers and the device, in the order it had when the close ran on the main thread; any
        // other open makes its readers, sizes and characteristics meanwhile and waits only before the device's open (the lock)
        final boolean closing = mAsyncCloseInFlight;
        final boolean waitFirst = closing && closeWaitFirst(ScameraApp.getSettings().cameraId);
        if (waitFirst) Log.i("PERF", "camera open waited " + awaitAsyncClose() + " ms for the previous camera's close (vendor lease path)");
        final boolean permitted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!permitted || !isCameraResumed) {
            mCameraOpening.set(false);
            if (permitted) Log.d(TAG, "camera open after the screen left: dropped");
            return;
        }
        try {
            prepareVivoSdkRawSession();
        } catch (Exception failure) {
            mCameraOpening.set(false);
            if (VivoSdkFullRes.offered(ScameraApp.getSettings().cameraId)) PixelPro10.setFullRes(false);
            closeVivoSdkRawSession(null);
            Log.e(TAG, "SDK camera setup failed: " + failure);
            cameraEventsListener.processingFailed(failure.getMessage());
            return;
        }
        // Attaching the stock AE observer takes seconds; start it with the
        // camera instead of on the first SCAM preview frame.
        if (mVivoSdkRawSession == null && PreferenceKeys.isScamEnabled() && PreferenceKeys.useStockBracketPlanner())
            ScamStockAe.warmUp(ScameraApp.getAppContext());
        try {
            cameraEventsListener.cameraOpening(mCameraManager);
            // The package spoof first: a module only the spoof lists has no characteristics without it.
            com.scamera.app.capture.spoof.CameraPackageSpoof.apply(activity, mCameraManager);
            selectCamera(width, height);
            final boolean sdkIncomplete = mVivoSdkRawSession != null && (mCameraCharacteristics == null || mImageReaderRaw == null
                    || !nativeVivoFullRes() && mVivoSdkJpeg == null);
            if (sdkIncomplete) throw new IllegalStateException("SDK RAW outputs are incomplete");
            fitViewfinderTransform(width, height);
        } catch (RuntimeException failure) {
            mCameraOpening.set(false);
            if (mVivoSdkRawSession != null) PixelPro10.setFullRes(false);
            closeVivoSdkRawSession(null);
            Log.e(TAG, "Camera outputs failed before open: " + failure);
            cameraEventsListener.processingFailed(failure.getMessage());
            return;
        }
        if (!isCameraResumed) {
            mCameraOpening.set(false);
            closeVivoSdkRawSession(null);
            Log.d(TAG, "screen left while the camera's outputs were set up: no open");
            return;
        }
        boolean lockHeld = false;
        try {
            // P662: a restart's close runs on its own thread holding the lock: the open waits for it (as it waited on the main thread)
            final long lockStart = System.nanoTime();
            trace("CaptureController.awaitClose");
            final boolean locked;
            try {
                locked = mCameraOpenCloseLock.tryAcquire(mAsyncCloseInFlight ? 5000 : 1000, TimeUnit.MILLISECONDS);
            } finally {
                endTrace();
            }
            if (!locked) {
                mCameraOpening.set(false);
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            lockHeld = true;
            if (closing && !waitFirst) {
                // P680: the open's preparation ran beside the previous camera's close; the device opens only after it
                final long prepared = (lockStart - openStart) / 1_000_000, waited = (System.nanoTime() - lockStart) / 1_000_000;
                final long overlap = Math.max(0, Math.min(lockStart, mCloseDoneNs) - openStart) / 1_000_000;
                Log.i("PERF", "camera open overlapped the previous camera's close: prepared in " + prepared + " ms, then waited "
                        + waited + " ms for the close (" + overlap + " ms of the close ran beside the preparation)");
                if (!isCameraResumed) {
                    openRefused(true);
                    Log.d(TAG, "screen left while the previous camera closed: no open");
                    return;
                }
            }
            OpenTimeline.deviceAsked();
            // "x-y": logical camera x, physical camera y; one id: both
            final String[] ids = ScameraApp.getSettings().cameraId.split("-");
            logicalID = ids[0];
            physicalID = ids.length > 1 ? ids[1] : ids[0];
            routeXiaomiTele();
            if (mXiaomiRouted) Log.i(TAG, "camera " + physicalID + " opens through logical camera " + logicalID + " (P41b)");
            // The retry budget belongs to one camera: switching modules during a provider restart used it up for the next one.
            if (!logicalID.equals(mOpenRetryId)) { mOpenRetryId = logicalID; mOpenRetries = 0; }
            this.mCameraManager.openCamera(logicalID, mStateCallback,
                    mVivoSdkRawSession != null ? mVivoSdkCallbackHandler : mBackgroundHandler);
            mOpenRetries = 0;
        } catch (CameraAccessException e) {
            openRefused(lockHeld);
            Log.e(TAG, Log.getStackTraceString(e));
            if (retryWithoutXiaomiRoute(width, height, e)) return;
            // CAMERA_ERROR / CAMERA_DISCONNECTED while the provider restarts: the same retry as an unknown device.
            if (e.getReason() == CameraAccessException.CAMERA_ERROR || e.getReason() == CameraAccessException.CAMERA_DISCONNECTED)
                retryOpen(width, height, e);
        } catch (IllegalArgumentException e) {
            // "Unknown device" while the vendor camera provider restarts after a HAL crash: the device list comes back a few
            // seconds later - a retry, not the app taken down with the HAL.
            openRefused(lockHeld);
            if (retryWithoutXiaomiRoute(width, height, e)) return;
            retryOpen(width, height, e);
        } catch (InterruptedException e) {
            openRefused(lockHeld);
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        } catch (RuntimeException e) {
            openRefused(lockHeld);
            cameraEventsListener.processingFailed(e.getMessage());
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * An open the camera service refused (no device, so no onClosed follows): the SDK session and the ISZ lease (P640) go back
     * here, and the open lock taken for it is released (it used to stay held, so the next open waited 1 s and failed with "Time
     * out waiting to lock camera opening").
     */
    private void openRefused(boolean lockHeld) {
        mCameraOpening.set(false);
        closeVivoSdkRawSession(null);
        closeIszLease();
        if (lockHeld) mCameraOpenCloseLock.release();
    }
    private String mOpenRetryId = "";

    /** Up to 8 retries 0.5, 1, 2, 4, 4 ... s apart (~27 s): the X100 Ultra provider needed ~4.5 s, the old 5 x 1 s ran out. */
    private void retryOpen(int width, int height, Exception e) {
        if (mOpenRetries < 8 && mBackgroundHandler != null && isCameraResumed) {
            // P240; not while a camera the HAL closed after a refused configuration is reopened quietly (the HAL restarts)
            if (!quietRecovery()) recovering(true, "open failed: " + e.getMessage());
            long delay = Math.min(4000L, 500L << mOpenRetries++);
            Log.w(TAG, "openCamera(" + logicalID + ") failed (" + e.getMessage() + "), retry " + mOpenRetries + " in " + delay + " ms");
            mBackgroundHandler.postDelayed(() -> openCamera(width, height), delay);
        } else {
            Log.e(TAG, "openCamera(" + logicalID + ") failed after retries: " + Log.getStackTraceString(e));
            recovering(false, "open failed after retries");
        }
    }

    /**
     * Makes {@code cameraId} the open lens: its characteristics (read now if the start-up list missed it), the still format,
     * the readers and their depth, the viewfinder sizes, the AE range, AF modes and flash it offers.
     */
    public void loadLens(String cameraId) {
        CameraCharacteristics characteristics = mCameraCharacteristicsMap.get(cameraId);
        if (characteristics == null) {
            // A camera ID the list filled at start did not have: a hidden module that only the package spoof lists (OPPO tele
            // 5 when the app started on a lens without the spoof). Without its characteristics no reader was set up and the
            // session start failed on a null ImageReader, leaving the device open and unconfigured (frozen viewfinder).
            try {
                characteristics = mCameraManager.getCameraCharacteristics(cameraId);
                mCameraCharacteristicsMap.put(cameraId, characteristics);
                Log.i(TAG, "characteristics of camera " + cameraId + " fetched on demand");
            } catch (CameraAccessException | IllegalArgumentException e) {
                Log.w(TAG, "no characteristics for camera " + cameraId + ": " + e.getMessage());
            }
        }
        mCameraCharacteristics = characteristics;
        final StreamConfigurationMap map = characteristics == null ? null
                : characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return;
        final boolean sdkFullRes = mVivoSdkRawSession != null;
        if (sdkFullRes) { mTargetFormat = ImageFormat.RAW_SENSOR; useMaximumResolutionKey = false; }
        if (sDemotedFrom != 0) { // the previous camera ran without a RAW stream: this one starts from the RAW format again
            if (mTargetFormat == YUV_STREAM_FORMAT || mTargetFormat == YuvMergeRoute.P010) mTargetFormat = sDemotedFrom;
            sDemotedFrom = 0;
        }
        // P646 «Склейка YUV»: a lens with the route on runs the YUV session (its largest YUV / P010 stream is the shot's reader)
        final YuvMergeRoute.Session yuvRoute = YuvMergeRoute.session(sdkFullRes || recordingVideo ? null : String.valueOf(physicalID),
                map, characteristics);
        if (yuvRoute != null) {
            if (isRawFormat(mTargetFormat)) sDemotedFrom = mTargetFormat;
            mTargetFormat = yuvRoute.format;
        }
        if (isRawFormat(mTargetFormat)) {
            final boolean noRawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) == null && map.getOutputSizes(ImageFormat.RAW10) == null
                    && map.getOutputSizes(ImageFormat.RAW12) == null;
            if (noRawSizes || sRawFallback.getOrDefault(physicalID, 0) >= 2) {
                Log.w(TAG, "camera " + physicalID + ": no RAW stream (" + (noRawSizes ? "the camera lists no RAW size"
                        : "its RAW sessions failed to configure") + "), YUV session");
                sDemotedFrom = mTargetFormat;
                mTargetFormat = YUV_STREAM_FORMAT;
            }
        }
        if (isRawFormat(mTargetFormat)) {
            final Integer white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            int resolved = resolveRawFormat(map, white, characteristics, physicalID);
            Log.i(TAG, "RAW stream format " + mTargetFormat + " -> " + resolved + " (setting " + PreferenceKeys.getRawStreamFormat()
                    + ("auto".equals(PreferenceKeys.getRawStreamFormat()) && autoRaw10(white, characteristics, physicalID) ? ": RAW10 where it fits (P560)" : "")
                    + ", camera " + physicalID + ("raw10".equals(PreferenceKeys.getRawStreamFormat()) && !raw10Fits(white)
                    ? ", white level " + white + " is wider than RAW10: the auto order (P365)" : "") + ")");
            mTargetFormat = resolved;
        }
        // Packed RAW10 / RAW12 samples onto the camera's declared levels (Pixel 10 Pro XL main: white 4095 over RAW10)
        {
            Integer declaredWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            int dw = declaredWhite == null ? 0 : declaredWhite;
            int s10 = com.scamera.app.util.RawUnpack.shiftFor(ImageFormat.RAW10, dw);
            int s12 = com.scamera.app.util.RawUnpack.shiftFor(ImageFormat.RAW12, dw);
            com.scamera.app.util.RawUnpack.setPackedShift(s10, s12);
            if (s10 > 0 || s12 > 0) Log.i(TAG, "camera " + physicalID + ": declared white " + dw + ", packed RAW10 << " + s10 + ", RAW12 << " + s12);
        }
        // P108: the Pixel 10 Pro / Pro XL's full-resolution switch: the RAW stream of the max-resolution map (RAW10 8160x6144)
        mFullResSession = false;
        NubiaRemosaicProbe.noSession(); // P598: a maximum-resolution nubia FULLRES below starts its setting again
        Size fullRes = sdkFullRes ? VivoSdkFullRes.profile(mVivoSdkCameraId).size : null;
        // P635: the lens's full-resolution recipe (its switch on, a profile that can run here and did not fail): the capture RAW
        // of the profile for the stills beside the viewfinder's own streams; it stands in for every other full-size route
        recipeSession(sdkFullRes, map, characteristics);
        if (sdkFullRes && VivoSdkFullRes.oppo()) {
            // P192: the own AE of the OPPO native mode meters the native RAW. P212b: it starts from this camera's own history
            // (NativeAeSeed: its last settled native exposure or a fresh plain RAW sample, else its plain HAL exposure), never
            // from the global mPreviewExposureTime that any camera's result overwrites
            final NativeRawExposure ae = new NativeRawExposure(characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE), OppoNativeFullRes.frameNs(mVivoSdkCameraId), 0L, 0);
            // P212a: the ISO the mode delivers, stored by earlier sessions (the key CC uses for mExposureLimits below: the physical
            // camera and the RAW reader's size, which is the profile's size in the native session), so the first split is capped
            final int cap = PreferenceKeys.scamDevSwitch("native_ae_cap", true)
                    ? ExposureLimits.of(String.valueOf(physicalID), fullRes.getWidth(), fullRes.getHeight(), false).isoCap() : Integer.MAX_VALUE;
            ae.isoCap(cap);
            ae.flicker(PreferenceKeys.getAntibandingHz(), nativeFlickerKnown()); // P209; P212d: a guess bands least at both
            ae.smoothing(PreferenceKeys.scamDevSwitch("native_ae_smooth", true)); // P212c
            final NativeAeSeed.Seed seed = PreferenceKeys.scamDevSwitch("native_ae_seed", true)
                    ? NativeAeSeed.pick(mVivoSdkCameraId, nativeAeEv(characteristics), android.os.SystemClock.elapsedRealtime(),
                    System.currentTimeMillis())
                    : new NativeAeSeed.Seed(mPreviewExposureTime > 0 && mPreviewIso > 0 ? (double) mPreviewExposureTime * mPreviewIso / 100.0 : 0,
                    "the last preview exposure (native_ae_seed 0)");
            ae.seed(seed.total);
            mNativeAeStartMs = android.os.SystemClock.elapsedRealtime();
            mNativeAeSettledMs = 0;
            mNativeAeStoredMs = 0;
            mNativeAeStoredTotal = 0;
            mNativeAe = ae;
            OppoNativeFullRes.begin(mVivoSdkCameraId, ae);
            final VivoSdkFullRes.Profile oppoProfile = VivoSdkFullRes.profile(mVivoSdkCameraId);
            Log.i("SCAM_CAPTURE", "OPPO native FULLRES camera " + mVivoSdkCameraId + ": mode "
                    + oppoProfile.sensorMode + " " + fullRes + " block " + oppoProfile.nativeBlock
                    + (oppoProfile.tetraBin ? " (P609: the 50 MP size = this 200 MP mode, every still 2x2 binned per Tetra cell -> "
                    + oppoProfile.shotSize() + " Quad, block 2; the sensor mode unchanged)" : "")
                    + ", " + OppoNativeFullRes.ispWords(OppoNativeFullRes.ispStream(mVivoSdkCameraId, oppoProfile))
                    + " [trial " + OppoNativeFullRes.rowLabel(OppoNativeFullRes.currentId(mVivoSdkCameraId, oppoProfile)) + "]"
                    + ", own AE from " + ae + " (" + seed.source
                    + (cap == Integer.MAX_VALUE ? "" : ", ISO cap " + cap) + ")");
        }
        if (mRecipePlan != null) {
            mTargetFormat = ImageFormat.RAW_SENSOR;
            fullRes = new Size(mRecipePlan.recipe.width, mRecipePlan.recipe.height);
            mFullResSession = true;
        }
        if (mRecipePlan == null && !sdkFullRes && isRawFormat(mTargetFormat) && !sFullResRefused.contains(physicalID) && !recordingVideo) {
            PixelPro10.FullRes fr = PixelPro10.fullRes(characteristics);
            // P588: the nubia's FULLRES without root is the generic maximum-resolution session. P598: each such session runs one
            // setting against the HAL's remosaic (NubiaRemosaicProbe); none left on this firmware: the normal session, 12 Мп
            if (fr == null && PixelPro10.fullResOn() && NubiaFullRes.maxRes(physicalID)) {
                if (NubiaRemosaicProbe.sessionStart(physicalID)) fr = PixelPro10.fullResStream(characteristics);
                else PixelPro10.setFullRes(false);
            }
            if (fr != null) {
                mTargetFormat = fr.format;
                fullRes = fr.size;
                mFullResSession = true;
                Log.i("SCAM_CAPTURE", "camera " + physicalID + ": full resolution " + fr.size + " format " + fr.format
                        + (nubiaPerShot() ? " (P626 per shot: the RAW in MAXIMUM_RESOLUTION for the stills only, the viewfinder DEFAULT)"
                        : " (every stream and request in MAXIMUM_RESOLUTION pixel mode, ZSL ring on, RAW viewfinder binned)"));
            }
        }
        // P276: the module's sensor mode picked from the camera's SensorModeTable (SensorModePick): its RAW size, its mode key
        final SensorModePick.Pick modePick = sdkFullRes || mFullResSession || recordingVideo || !isRawFormat(mTargetFormat)
                ? null : SensorModePick.active(physicalID, characteristics);
        SensorModePick.session(physicalID, modePick);
        XiaomiSensorRow.armFrameLog(physicalID, modePick); // P631: the session's first frame described once
        if (modePick != null) Log.i("SCAM_CAPTURE", "camera " + physicalID + ": the module's sensor mode " + modePick
                + " (SensorModeTable, P276)");
        // P575: a nubia session watches the mode its results report (the picked row proves itself; a normal one logs its own).
        // P608: a maximum-resolution FULLRES session must report the full row it asks (NubiaFullRes.maxResRow)
        if (mFullResSession && NubiaRemosaicProbe.running(physicalID)) NubiaFullRes.watchMaxRes(physicalID, nubiaPerShot()); // P626
        else NubiaFullRes.watchSession(physicalID, modePick);
        // P586: the OnePlus 16's sessions watch their reported mode too (an ISZ / DCG / DAG row, a native FULLRES mode, or the own one)
        OplusIsz.watchSession(physicalID, modePick, sdkFullRes && VivoSdkFullRes.oppo() ? VivoSdkFullRes.profile(mVivoSdkCameraId) : null);
        // P593: a DCG / DAG row has to prove its depth by its frames (the tele's row 2 ran as a plain 10-bit row, log 2026-11-09)
        OplusDcg.watchSession(physicalID, modePick, characteristics);
        if (modePick != null && modePick.route != null)
            Log.i("SCAM_CAPTURE", "OPPO family " + modePick.route + " camera " + physicalID + ": sensor mode row " + modePick.mode + " "
                    + modePick.size + ", " + OppoNativeFullRes.MODE_KEY + " on the session and every request, merge block "
                    + (modePick.block > 0 ? modePick.block : "measured") + (modePick.rawOnly() ? ", ISP stream off (RAW viewfinder)"
                    : ", the ISP stream as «Поток ISP» has it") + " (P586)");
        if (modePick != null && NubiaFullRes.device())
            Log.i("SCAM_CAPTURE", "nubia " + (modePick.fullRes ? "FULLRES (the size switch)" : "ISZ / module row") + " camera " + physicalID
                    + ": sensor mode row " + modePick.mode + " " + modePick.size + ", " + SensorModePick.QCOM_KEY
                    + " on the session and every request, no ISP stream (RAW viewfinder), merge block "
                    + SensorModePick.declaredBlock(SensorModeBand.table(characteristics), modePick.mode) + " (P575)");
        ArrayList<Size> allTargets = listedRawSizes();
        // P108: every request of the full-resolution session in MAXIMUM_RESOLUTION pixel mode (VendorTagUtils.builderSessionApply)
        // P626: set for every session (it stayed true after a FULLRES session: the next normal session's DEFAULT streams got
        // MAXIMUM_RESOLUTION requests - «Request settings CONTROL_SENSOR_PIXEL_MODE are not consistent with streams configured»,
        // PREVIEW_STALL, log of 31256); a nubia per-shot way repeats in DEFAULT (NubiaMaxResPlan)
        useMaximumResolutionKey = NubiaMaxResPlan.repeatingMaxRes(mFullResSession, perShot()); // P635: a recipe's requests say their own

        Size preview = stillSize(map.getOutputSizes(mPreviewTargetFormat), null);

        int maxjpg = 3;
        // The raw viewfinder consumes from this reader continuously, so it
        // needs its own headroom even when ZSL is off.
        if (PreferenceKeys.isLiveViewfinderRawEnabled()) maxjpg = 6;
        // P365 (owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the
        // phone's own reader depth (DeviceDefaults.zslReaderImages: 8 on the OPPO Find X7 Ultra, the reference app's reader there at
        // 30 fps) as the default and the floor; 0 = no rule: 20, at least 16
        final int deviceReader = com.scamera.app.settings.DeviceDefaults.zslReaderImages();
        if (isZslMode())
            // The ring buffer can hold up to zslRingCapacity() frames, and the
            // reader has to have room for all of them plus the in-flight ones,
            // otherwise acquireNextImage starves once the ring is full.
            // P91: with the ring in the app's memfd the reader only passes frames through (copied on arrival) and holds the
            // post-shutter series: scam_dev "zsl_reader" (default 20; LMC keeps 50, WhyCam 1.05 min(max(2 x frames, 8), 48))
            // A YUV ring (no RAW) still holds the camera's Images: reader = ring + 3, as before.
            maxjpg = PreferenceKeys.scamDevSwitch("zsl_memfd_ring", true) && isRawFormat(mTargetFormat)
                    // floor 16: the Galaxy S24 Ultra's RAW stream ran at 15 fps with 8 reader buffers, 28 with 12, 30 from 16 (SCAM
                    // TEST ring depth search, 2026-05-18); the «Движение» ring of 8 frames would otherwise get a reader of 11
                    ? Math.max(deviceReader > 0 ? deviceReader : 16, Math.min(Math.min(zslRingCapacity() + 3, MAX_READER_IMAGES),
                            (int) PreferenceKeys.scamDevNumber("zsl_reader", deviceReader > 0 ? deviceReader : 20f)))
                    : Math.min(zslRingCapacity() + 3, MAX_READER_IMAGES);
        Size target = stillSize(allTargets.toArray(new Size[0]), preview);
        // Diagnostics: the vivo X200 Pro's 4096x3072 RAW holds its image in 4000x3000 (zeros elsewhere, black stripe at the edge);
        // whether the arrays say so decides how a crop to the valid area can be done.
        Log.i(TAG, "camera " + physicalID + " arrays: pixel=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                + " active=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                + " preCorrection=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                + " level=" + characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                + " capabilities=" + Arrays.toString(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))
                + " target=" + target + " format=" + mTargetFormat
                + " rawSizes=" + Arrays.toString(isRawFormat(mTargetFormat) ? rawSizesLargestFirst(map, mTargetFormat) : new Size[0]));
        if (!isRawFormat(mTargetFormat) && sSafeStreams.getOrDefault(physicalID, 0) >= 2) {
            final Size safe = SafeStreamSizes.capped(map.getOutputSizes(mTargetFormat), target, SafeStreamSizes.PREVIEW_AREA);
            Log.w(TAG, "camera " + physicalID + ": YUV " + safe + " instead of " + target + " (session fallback)");
            target = safe;
        }
        final Size rawAlt = isRawFormat(mTargetFormat) && sRawFallback.getOrDefault(physicalID, 0) == 1 ? sRawSizeAlt.get(physicalID) : null;
        if (rawAlt != null) {
            Log.w(TAG, "camera " + physicalID + ": RAW " + rawAlt + " instead of " + target + " (session fallback)");
            target = rawAlt;
        }
        Size aspect = viewfinderAspect(ScameraApp.getSettings().mode);
        // the still reader beside the RAW one in the viewfinder's aspect: its long side kept, the short one from the aspect
        final int previewLong = Math.max(preview.getWidth(), preview.getHeight());
        final int previewShort = previewLong * aspect.getWidth() / aspect.getHeight();
        preview = preview.getWidth() > preview.getHeight() ? new Size(previewLong, previewShort) : new Size(previewShort, previewLong);
        closeReader(mImageReaderPreview);
        if (mFullResSession) {
            // P108: a size of the max-resolution map (the stream is a MAXIMUM_RESOLUTION one), the RAW's aspect
            Size fullPreview = mRecipePlan != null ? null : PixelPro10.maxResSize(characteristics, mPreviewTargetFormat, fullRes); // P635
            if (fullPreview != null) preview = fullPreview;
            maxjpg = Math.min(maxjpg, 8); // 50 MP frames: a short reader, every frame is copied on arrival (P91)
        }
        final ImageReader stillReader = sdkFullRes ? null
                : ImageReader.newInstance(preview.getWidth(), preview.getHeight(), mPreviewTargetFormat, maxjpg);
        if (stillReader != null) stillReader.setOnImageAvailableListener(mStillReaderListener, mBackgroundHandler);
        mImageReaderPreview = stillReader;
        mBufferSize = getPreviewOutputSize(characteristics, ScameraApp.getSettings().mode);
        // P365 step 9 (owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»;
        // scam_dev vf_isp_light, on by default on the X7 Ultra): under the RAW viewfinder the ISP stream is only its fallback - the
        // reference app's 1440x1080 class (the smallest of the session's aspect with a long side of at least 1440)
        if (mBufferSize != null && fullRes == null && !recordingVideo && !mLiveRawRejected && isRawFormat(mTargetFormat) && ispLight()
                && PreferenceKeys.isLiveViewfinderRawEnabled() && isZslMode()) {
            final Size[] textureSizes = map.getOutputSizes(SurfaceTexture.class);
            final Size light = lightPreviewSize(textureSizes, aspect, mBufferSize);
            if (!light.equals(mBufferSize)) {
                Log.i("SCAM_CAPTURE", "camera " + physicalID + ": ISP stream " + light.getHeight() + "x" + light.getWidth() + " instead of "
                        + mBufferSize.getHeight() + "x" + mBufferSize.getWidth() + " under the RAW viewfinder (P365 vf_isp_light)");
                mBufferSize = light;
            }
        }
        if (sSafeStreams.getOrDefault(physicalID, 0) >= 1 && mBufferSize != null) {
            // mBufferSize is portrait (short x long); the stream sizes are sensor-oriented
            final Size sensor = new Size(Math.max(mBufferSize.getWidth(), mBufferSize.getHeight()),
                    Math.min(mBufferSize.getWidth(), mBufferSize.getHeight()));
            final Size safe = SafeStreamSizes.capped(map.getOutputSizes(SurfaceTexture.class), sensor, SafeStreamSizes.PREVIEW_AREA);
            Log.w(TAG, "camera " + physicalID + ": preview " + safe + " instead of " + sensor + " (session fallback)");
            mBufferSize = new Size(safe.getHeight(), safe.getWidth());
        }

        if (fullRes != null) {
            target = fullRes;
            // P108: the viewfinder stream too is a size of the max-resolution map (Pixel 10 Pro XL: 1440x1080 for 4:3)
            Size st = sdkFullRes ? new Size(1440, 1080) : PixelPro10.maxResSize(characteristics, -1, fullRes);
            // P626: a nubia per-shot way's viewfinder is the default map's (binned) ISP stream in DEFAULT
            if (st != null && !perShot()) mBufferSize = new Size(Math.min(st.getWidth(), st.getHeight()), Math.max(st.getWidth(), st.getHeight()));
            Log.i("SCAM_CAPTURE", "full resolution: viewfinder " + (perShot() ? mBufferSize + " (per shot, P626)" : st) + ", YUV "
                    + preview + ", readers " + maxjpg);
        }
        clearZslPreviewFrames();
        mPickPacked = false; // P559: set again by a picked sensor mode's packed reader below
        if(mImageReaderRaw != null)
            mImageReaderRaw.close();
        if (mImageReaderRawView != null) { mImageReaderRawView.close(); mImageReaderRawView = null; }
        if (mRecipeCompanion != null) { mRecipeCompanion.close(); mRecipeCompanion = null; } // P635
        if (mVivoSdkJpeg != null) { mVivoSdkJpeg.close(); mVivoSdkJpeg = null; }
        mStillChoice = null; // P684: chosen again below for a 200 MP still-only session
        if (sdkFullRes) {
            mTargetFormat = ImageFormat.RAW_SENSOR;
            mImageReaderRaw = ImageReader.newInstance(fullRes.getWidth(), fullRes.getHeight(), ImageFormat.RAW_SENSOR,
                    nativeVivoFullRes() ? 4 : 3, 0);
            if (stillOnlyFullRes()) {
                // P208: the viewfinder is the camera's own (largest listed) RAW stream; its frames go the live RAW way
                final Size[] own = rawSizesLargestFirst(map, ImageFormat.RAW_SENSOR);
                if (own.length == 0) throw new IllegalStateException("No RAW stream for the 200 MP viewfinder");
                mImageReaderRawView = ImageReader.newInstance(own[0].getWidth(), own[0].getHeight(), ImageFormat.RAW_SENSOR, 4);
                mImageReaderRawView.setOnImageAvailableListener(reader -> {
                    Image image;
                    try { image = reader.acquireNextImage(); } catch (IllegalStateException closed) { return; }
                    if (image == null) return;
                    if (!isCameraResumed || reader != mImageReaderRawView) { image.close(); return; }
                    mLiveRawRouter.image(image.getTimestamp(), image);
                }, mBackgroundHandler);
                Log.i("SCAM_CAPTURE", "FULLRES 200 MP still-only: viewfinder RAW " + own[0] + " in the normal mode, "
                        + fullRes + " for the stills (P208)");
                // P684: the session's variant of the still-only route (StillOnly200), the same for its presses; the X300 Ultra stock
                // camera's full-size recipe adds a small YUV companion stream (its frames dropped; the decision frame's target)
                final VivoSdkFullRes.Profile stillProfile = VivoSdkFullRes.profile(mVivoSdkCameraId);
                if (StillOnly200.applies(stillProfile, VivoSdkFullRes.oppo())) {
                    mStillChoice = StillOnly200.choose(mVivoSdkCameraId, stillProfile);
                    Size companion = null;
                    if (mStillChoice.variant.recipe) {
                        companion = X300FullSize.companion(map.getOutputSizes(ImageFormat.YUV_420_888), own[0].getWidth(), own[0].getHeight());
                        if (companion != null) {
                            final ImageReader reader = ImageReader.newInstance(companion.getWidth(), companion.getHeight(), ImageFormat.YUV_420_888, 2);
                            reader.setOnImageAvailableListener(rd -> { // its frames are not used: the stream only has to run
                                try {
                                    final Image image = rd.acquireLatestImage();
                                    if (image != null) image.close();
                                } catch (IllegalStateException closed) { }
                            }, mBackgroundHandler);
                            mRecipeCompanion = reader;
                        }
                    }
                    Log.i("SCAM_CAPTURE", "200 MP still-only session camera " + mVivoSdkCameraId + " " + fullRes + " block "
                            + stillProfile.nativeBlock + ": variant " + mStillChoice + (mStillChoice.variant.recipe
                            ? ", stock full-size keys, YUV companion " + (companion == null ? "none (no YUV size)" : companion) : "") + " (P684)");
                }
            }
            if (!nativeVivoFullRes()) {
            mVivoSdkJpeg = ImageReader.newInstance(fullRes.getWidth(), fullRes.getHeight(), ImageFormat.JPEG, 3);
            mVivoSdkJpeg.setOnImageAvailableListener(reader -> {
                try {
                    Image image;
                    while ((image = reader.acquireNextImage()) != null) {
                        long timestamp = image.getTimestamp();
                        image.close();
                        VivoSdkFullResCapture capture = mVivoSdkCapture;
                        if (capture != null) capture.jpegDrained(timestamp);
                    }
                } catch (IllegalStateException closed) { }
            }, mBackgroundHandler);
            }
        } else if (mRecipePlan != null) {
            recipeReaders(mRecipePlan); // P635: the capture RAW (stills), the viewfinder's binned RAW, the companion
        } else if (modePick != null) {
            // P276: the row's size, also one StreamConfigurationMap does not list (usage 0: no CPU usage bits for the HAL's
            // stream validator, the planes still read on the CPU - the native FULLRES readers, WhyCam)
            // P559 (owner, 2026-11-08: «Точно ли у нас работает переключатель рава на raw10? В Арк камере фиксированный уровень чёрного
            // 64»): the picked mode took RAW_SENSOR whatever the setting resolved - the X8U's ISZ shots and viewfinder were RAW16
            // (readers f20 in the owner's logs, «setting raw10» printed beside them), whose black its HAL delivers drifted (64 ->
            // 60.6) or lost (P114 / P315): green shadows. The resolved packed format (RAW10 / RAW12: P388's RAW10 on the X8U) is kept;
            // a mode whose session refuses it falls back to RAW_SENSOR once (retryConfigureFallback, sRaw16)
            // P593: a DCG / DAG row's frames are wider than RAW10 / RAW12 hold (the OnePlus 16's row 28: 14 bit): RAW_SENSOR whatever
            // «Формат RAW» says (the module's «raw10» would pack them into 10 bits)
            final boolean packed = (mTargetFormat == ImageFormat.RAW10 || mTargetFormat == ImageFormat.RAW12) && !sRaw16.contains(physicalID)
                    && !modePick.dcg();
            if (modePick.dcg() && mTargetFormat != ImageFormat.RAW_SENSOR)
                Log.i("SCAM_CAPTURE", "camera " + physicalID + " DCG / DAG row: RAW stream format " + mTargetFormat + " -> " + ImageFormat.RAW_SENSOR + " (its frames are wider than the packed formats)");
            mPickPacked = packed;
            if (!packed) mTargetFormat = ImageFormat.RAW_SENSOR;
            else Log.i("SCAM_CAPTURE", "camera " + physicalID + " sensor mode pick: RAW stream format " + mTargetFormat + " (the setting's, P559)");
            target = modePick.size;
            mImageReaderRaw = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg, 0)
                    : ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg);
        } else if (VivoPd2454NativeRaw.active(physicalID)) {
            mTargetFormat = ImageFormat.RAW_SENSOR;
            target = new Size("5".equals(physicalID) ? 4080 : 4096, 3072);
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && VivoPd2454Isz.active(physicalID)) {
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg, 0);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !PLAIN_RAW_READER_BRANDS.contains(Build.BRAND.toLowerCase(Locale.ROOT))) {
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg, 0x00100000);
        } else {
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg);
        }
        mImageReaderRaw.setOnImageAvailableListener(mRawReaderListener, mBackgroundHandler);
        // P319: the size the session really captures, for the photo size pill (PhotoSize.badge)
        // P688: a FULLRES session's stream is never the «Обычный» size (a cold start with 50 MP chosen named the plain row «50 Мп»)
        com.scamera.app.ui.camera.PhotoSize.stream(physicalID, mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight(),
                mVivoSdkRawSession == null && !mFullResSession);
        mRawMaxFps = ViewfinderFps.maxFps(characteristics, mTargetFormat, mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight(),
                useMaximumResolutionKey);
        // P230: a DCG mode (60 fps only) has its own limits, not the normal mode's of the same RAW size
        // P641: the Xiaomi 17 Ultra's route by its switch (its variant is chosen when the session is configured)
        mExposureLimits = ExposureLimits.of(physicalID + ((XiaomiDualGain.device() ? Dcg14Mode.active(characteristics)
                        : Dcg14Mode.requestKeys(characteristics)) ? "/dcg" : ""),
                mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight(), useMaximumResolutionKey);
        mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        FpsRangeAuto = new Range<>(slowestAeRate(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)), 30);
        afModesOfCamera = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        mFlashSupported = Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        Camera2ApiAutoFix.Init();
        // the video recorder is made when a clip is set up (setUpMediaRecorder), not with every camera
        final CameraCharacteristics updated = characteristics;
        activity.runOnUiThread(() -> {
            // the GL viewfinder's drawing size and aspect for this lens
            mPreviewSize = getTextureOutputSize(getSafeDisplay(), ScameraApp.getSettings().mode);
            mViewfinder.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            applyViewfinderMirror();
            // The fragment may be gone by now (activity recreated, e.g. on a language change).
            final CameraEvents listener = cameraEventsListener;
            if (listener != null) listener.lensChanged(updated);
            if (ScameraApp.getSettings().fullDebug) showToast("preview:" + new Point(previewW, previewH));
        });
    }

    /** Brands whose HAL takes the RAW reader without the 0x00100000 usage flag the others get (Android 10+). */
    private static final java.util.Set<String> PLAIN_RAW_READER_BRANDS = new java.util.HashSet<>(Arrays.asList(
            "oppo", "vivo", "oneplus", "realme", "iqoo", "nothing", "google", "xiaomi", "redmi", "poco"));

    /** The lowest rate of the lens's AE target ranges (14 when it lists none): the start of the viewfinder's default range. */
    static int slowestAeRate(@Nullable Range<Integer>[] ranges) {
        int slowest = Integer.MAX_VALUE;
        if (ranges != null) for (Range<Integer> range : ranges) slowest = Math.min(slowest, range.getLower());
        return slowest == Integer.MAX_VALUE ? 14 : slowest;
    }

    /** A session answer for a device or a session generation that is gone (closed, replaced): the session is closed unused. */
    private boolean staleSession(CameraCaptureSession session, CameraDevice device, int generation) {
        if (isCameraResumed && device == mCameraDevice && generation == mSessionGeneration.get()) return false;
        session.close();
        return true;
    }

    /** The session is configured: it becomes the running one and the viewfinder's repeating request starts. */
    private void sessionReady(CameraCaptureSession session, int generation, boolean sdkFullRes, boolean scamPreview) {
        mCaptureSession = session;
        final String runningId = ScameraApp.getSettings().cameraId;
        if (sGoodCameras.add(runningId) && sRawFallback.getOrDefault(physicalID, 0) > 0) {
            final int step = sRawFallback.get(physicalID);
            Log.w(TAG, "camera " + physicalID + " runs with the session fallback " + step);
            showToast(step == 1 ? Lang.t("Камера " + physicalID + ": RAW " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight(),
                    "Camera " + physicalID + ": RAW " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight())
                    : Lang.t("Камера " + physicalID + " без RAW-потока: только обычная съёмка",
                    "Camera " + physicalID + " has no RAW stream: plain capture only"));
        }
        sLastGoodCamera = runningId;
        mConfiguredSessionGeneration = generation;
        mSessionPrefs = null; // P680: the new session's settings are read at its first result
        synchronized (mRingLock) { mRingClosing = false; } // P662: this session's frames go into the ring
        if (mBackgroundHandler != null) { // P662: a kept ring this session never takes goes after a while
            mBackgroundHandler.removeCallbacks(mDropParkedRing);
            mBackgroundHandler.postDelayed(mDropParkedRing, PARKED_RING_MS);
        }
        mLastPreviewResultMs = 0;
        mStillOnlyNextVariant = false; // P684: only for an error under the series that noted it
        mPreviewSessionStartMs = android.os.SystemClock.elapsedRealtime();
        // P212d: the scene-flicker report of a session counts after its first results, never in a native route
        // with its own RAW viewfinder (no HAL statistics there; the still-only route keeps the normal viewfinder)
        MainsFlicker.newSession(nativeVivoFullRes() && !stillOnlyFullRes());
        mVfCcmSession++; // P212z: the native viewfinder's colour matrix is looked up again in this session
        // P212g (W6): a still-only vivo session's viewfinder neutrals, this session's only
        mVfNeutrals.clear();
        mVfNeutralFeed = stillOnlyFullRes() && !VivoSdkFullRes.oppo();
        final Handler cameraThread = mBackgroundHandler;
        if (cameraThread != null) {
            cameraThread.removeCallbacks(mStallCheck);
            cameraThread.postDelayed(mStallCheck, 1000);
        }
        scheduleProgramWarmUp(); // P237
        recovering(false, "session configured"); // P240
        mRetryDevice = null;
        mQuietErrorSpent = false; // P263: the next device error is quiet again
        startFpsProbeIfAsked(); // P266
        experimentalRouteNotice(); // P240
        com.scamera.app.util.ThermalPolicy.watch(ScameraApp.getAppContext()); // P238
        mPreviewCaptureResult = null;
        mPreviewCaptureRequest = null;
        try {
            startRepeatingPreview(sdkFullRes, scamPreview);
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            if (retryWithoutVivoSdkFullRes(session, "preview: " + e.getMessage())) return;
            if (retryWithoutLiveRaw(session)) return;
        }
        final MediaRecorder recorder = videoRecorder;
        if (recordingVideo && recorder != null) activity.runOnUiThread(recorder::start);
    }

    /**
     * The viewfinder's request of a new session, key by key in this order: AE mode and regions, the API fixes, the vendor
     * session keys, the Xiaomi zoom, the SCAM preview controls, the shading map, OIS / EIS, the trace's OIS samples, the AE
     * range (module / recipe / DCG override it), then the repeating request and the AF reset.
     */
    private void startRepeatingPreview(boolean sdkFullRes, boolean scamPreview) throws CameraAccessException {
        final CaptureRequest.Builder preview = mPreviewRequestBuilder;
        restorePreviewAe();
        applyAeRegions(preview);
        Camera2ApiAutoFix.applyPrev(preview);
        if (!sdkFullRes) VendorTagUtils.builderSessionApply(preview, false, useMaximumResolutionKey, physicalID);
        XiaomiTeleZoom.applyLast(preview, physicalID);
        if (scamPreview) {
            try {
                // Detector controls only: VCF2 JPEG stream usage and SnapshotJpegStreamMap do not describe this RAW session.
                ScamPreview.applyRepeating(preview);
                Log.i("SCAM_CAPTURE", "preview SCAM AUTO; capture route=Camera2_RAW"
                        + " stockAeSource=preview_metadata stockPlanApplied=false");
            } catch (IllegalArgumentException unsupported) {
                Log.w("SCAM_CAPTURE", "SCAM preview controls unavailable", unsupported);
            }
        }
        try {
            preview.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        } catch (Exception e) {
            Log.d(TAG, "lens shading map not available: " + e);
        }
        applyOisMode(preview, false);
        applyVideoStabilization(preview);
        if (!sdkFullRes && ScamPreview.supported() && PreferenceKeys.isEisPhotoOn() && !ForcedStabilization.active() && !PlainPreviewCameras.contains(physicalID)) {
            ScamPreview.applyPreviewEis(preview);
        }
        if (mForcedStab) Log.i("SCAM_CAPTURE", "forced stabilisation (P54b) camera " + physicalID + ": preview "
                + ForcedStabilization.apply(preview, oisForceable(), mForcedStabEis)
                + (ForcedStabilization.noFlush() ? ", no HAL flush on the shot" : "")
                + (ForcedStabilization.rawOnlySwitch() ? ", AE restore frame on the RAW stream only (P54c)" : ""));
        // P38: stabilisation trace of this session; OIS samples only on the dev switch.
        CameraCharacteristics traceChars = mCameraCharacteristicsMap.get(physicalID);
        if (traceChars == null) traceChars = mCameraCharacteristics;
        mStabTrace.startSession(physicalID, traceChars);
        mPreviewGap.reset();
        mRearmPending = false;
        if (StabilizationTrace.wantsOisSamples(traceChars))
            preview.set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CaptureRequest.STATISTICS_OIS_DATA_MODE_ON);
        // P224: the viewfinder never below 20 fps (the automatic ranges' floor, ViewfinderFps)
        preview.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                ViewfinderFps.raise(scamPreview ? ScamPreview.STOCK_FPS : videoFpsRange(), mRawMaxFps));
        final Range<Integer> moduleFps = moduleFps(); // P266: the module's own RAW frame rate wins
        if (moduleFps != null) {
            preview.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, moduleFps);
            Log.i("SCAM_CAPTURE", "camera " + physicalID + ": the module's RAW frame rate " + moduleFps + " (P266)");
        }
        // P635: a recipe's frame rate on the repeating request as on its session and stills (a range that differs
        // between them makes the camera service reconfigure where the range is a session key, P84c)
        if (mRecipePlan != null && mRecipePlan.recipe.fpsLow > 0)
            preview.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(mRecipePlan.recipe.fpsLow, mRecipePlan.recipe.fpsHigh));
        // P230: the DCG mode on every request and its 60 fps (the shot's frames take the viewfinder's range)
        if (mDcgRequestKeys) Log.i("SCAM_CAPTURE", "DCG viewfinder requests camera " + physicalID + ": "
                + Dcg14Mode.applyRequest(preview, mCameraCharacteristics));
        if (sdkFullRes) applyVivoSdkPreview(preview);
        mRepeatingRequest = preview.build();
        mCaptureSession.setRepeatingRequest(mRepeatingRequest, mCaptureCallback, mBackgroundHandler);
        unlockFocus();
    }

    /**
     * The camera refused the session's configuration: the fallback chain tries the next lighter one, in this order; a HAL that
     * closes the device after the refusal (onError 4, vivo X300 2026-10-19) has its camera reopened quietly (mRetryDevice).
     */
    private void sessionRefused(CameraCaptureSession refused, CameraDevice device) {
        mRetryDevice = device;
        mFallbackUndo = null;
        if (retryWithoutLofic(refused, "session configuration failed")) return; // P679
        if (retryWithoutRecipe(refused, "session configuration failed")) return; // P635
        if (retryWithoutDcgRoute(refused, "session configuration failed")) return; // P230
        if (retryWithoutVendorMode(refused)) return; // P211
        if (retryWithoutVivoSdkFullRes(refused, "session configuration failed")) return;
        if (retryWithoutRequestedSensorMode(refused, "session configuration failed")) return;
        if (retryXiaomiRoute(refused, "session configuration")) return;
        if (retryWithoutCroppedRaw(refused)) return;
        if (retryWithoutFullRes(refused, "session configuration failed")) return;
        if (retryWithoutLiveRaw(refused)) return;
        if (retryWithoutScamSession(refused)) return;
        if (retryConfigureFallback(refused)) return;
        mRetryDevice = null; // nothing left to retry: a following error is a real one
        if (revertToLastGoodCamera("session configuration")) return;
        showError(activity.getString(R.string.session_on_configure_failed));
    }
    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT of the physical camera (the logical one's when the map lacks it); -1 unknown. */
    private int liveCfaPattern() {
        try {
            CameraCharacteristics lens = mCameraCharacteristicsMap.get(physicalID);
            if (lens == null) lens = mCameraCharacteristics;
            final Integer cfa = lens == null ? null : lens.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            return cfa == null ? -1 : cfa;
        } catch (Exception e) {
            return -1;
        }
    }

    /** The ISP viewfinder stream's surface on the GL viewfinder's texture (made once per texture, released on close). */
    Surface surface;

    /**
     * Builds the session for the open camera: its streams (RAW ring, ISP viewfinder, extra readers), vendor session keys and
     * the viewfinder's repeating request. A burst session leaves the RAW viewfinder out.
     */
    public void startSession(boolean isBurstSession) {
        final int generation = mSessionGeneration.incrementAndGet();
        unspektraAe.reset();
        final CameraDevice sessionDevice = mCameraDevice;
        if (!isCameraResumed || sessionDevice == null) return;
        final boolean sdkFullRes = mVivoSdkRawSession != null;
        try {
            if (!sdkFullRes) SensorConfigInjector.fillForModule(physicalID, this);
            recordSessionSettings();
            final SurfaceTexture texture = mViewfinder.getSurfaceTexture();
            if (texture == null) {
                Log.w(TAG, "session waits for the viewfinder's texture");
                mViewfinder.setSurfaceTextureListener(mSurfaceTextureListener);
                return;
            }
            Log.d(TAG, "session for camera " + ScameraApp.getSettings().cameraId + " (device " + sessionDevice.getId() + ", logical "
                    + logicalID + ", physical " + physicalID + "): ISP stream " + mBufferSize + ", viewfinder " + mPreviewSize);
            // The CFA pattern of the physical camera being previewed, read once here: per frame from mCameraCharacteristics
            // (swapped between the logical and physical cameras while the session runs) it flipped between BGGR and RGGB.
            mLiveCfaPattern = liveCfaPattern();
            Log.d(TAG, "live viewfinder CFA: " + mLiveCfaPattern + " (physical " + physicalID + ")");
            // the ISP stream's buffers at the stream size (the texture is sensor-oriented: long side first)
            texture.setDefaultBufferSize(mBufferSize.getHeight(), mBufferSize.getWidth());
            // P679: the Xiaomi 17 Ultra's LOFIC pair (the «DCG+LOFIC» switch on its main camera opened directly): a setup of the
            // recipe or, with none left, the normal session; the viewfinder stream at the setup's own size
            final boolean loficWanted = !isBurstSession && !recordingVideo && !sdkFullRes && !mFullResSession && mRecipePlan == null
                    && (ScameraApp.getSettings().mode == CameraMode.PHOTO || ScameraApp.getSettings().mode == CameraMode.NIGHT)
                    && !(mXiaomiRouted && !Objects.equals(physicalID, logicalID)) && Objects.equals(physicalID, logicalID)
                    && XiaomiLofic.wanted(physicalID, isRawFormat(mTargetFormat));
            final String[] loficNote = {null};
            closeLoficSession();
            final XiaomiLofic.Setup lofic = XiaomiLofic.sessionStart(loficWanted, loficNote);
            if (lofic != null && !openLoficSession(lofic)) {
                XiaomiLofic.sessionFailed("its readers could not be made");
                loficNote[0] = Lang.t("DCG+LOFIC: потоки не созданы — обычный режим", "DCG+LOFIC: no streams - the normal mode");
            }
            final XiaomiLofic.Setup loficSetup = XiaomiLofic.setup();
            if (loficSetup != null) texture.setDefaultBufferSize(loficSetup.previewW, loficSetup.previewH);
            // the viewfinder's shape follows the stream (16:9 for the 16:10 setup), and back after a LOFIC session
            if ((loficSetup != null || mLoficAspect) && mViewfinder != null) {
                final Size lens = mPreviewSize;
                final int aw = loficSetup != null ? loficSetup.previewH : lens != null ? lens.getHeight() : 0;
                final int ah = loficSetup != null ? loficSetup.previewW : lens != null ? lens.getWidth() : 0;
                mLoficAspect = loficSetup != null;
                if (aw > 0 && ah > 0) activity.runOnUiThread(() -> mViewfinder.setAspectRatio(aw, ah));
            }
            if (surface == null) surface = new Surface(texture);
            // P27: the routing of a shot in flight stays (its post-shutter RAWs would be closed as preview frames).
            if (!shotInFlight()) mLiveRawRouter.clear();
            mLiveMetadata.clear();
            boolean photoMode = ScameraApp.getSettings().mode == CameraMode.PHOTO
                    || ScameraApp.getSettings().mode == CameraMode.NIGHT
                    || ScameraApp.getSettings().mode == CameraMode.MOTION;
            VivoQcomModes.ensureModules(ScameraApp.getAppContext());
            VivoPd2454Isz.ensureModules(ScameraApp.getAppContext());
            VivoPd2454NativeRaw.ensureModules(ScameraApp.getAppContext());
            final boolean nativeIsz = VivoPd2454NativeRaw.active(physicalID);
            final boolean vivoQcomIsz = nativeIsz || VivoQcomModes.activeFactor() > 0 || VivoPd2454Isz.active(physicalID);
            // P214i: the X300 Ultra camera's role (by its CameraType or focal length: the declared block of an ISZ module's merge)
            // and the frame check of an ISZ module's session
            CameraCharacteristics iszCamera = mCameraCharacteristicsMap.get(physicalID);
            VivoQcomIsz.learn(physicalID, iszCamera != null ? iszCamera : mCameraCharacteristics);
            mIszCheck = VivoQcomIsz.check(physicalID);
            // Vendor detector tags exist only on a vivo HAL and were recorded on the X200 Ultra, the one vivo phone SCAM HDR runs
            // on (P54: none on the X300 Ultra and other vivo phones); elsewhere SCAM HDR uses the plain preview.
            final boolean scamPreview = PreferenceKeys.isScamEnabled() && photoMode
                    && ScamPreview.vendorKeys() && !isBurstSession && !recordingVideo && !vivoQcomIsz && !sdkFullRes
                    && !PlainPreviewCameras.contains(physicalID);
            mScamPreviewActive = scamPreview;
            // P54b: X300 Ultra - stabilisation held on for the whole session (OIS + vivo EIS key in every request, no flush)
            mForcedStab = photoMode && !isBurstSession && !recordingVideo && !sdkFullRes && ForcedStabilization.active();
            mForcedStabEis = mForcedStab && ForcedStabilization.eisKey() && !PlainPreviewCameras.contains(physicalID);
            mPayloadFrames = 0;
            mParkedLens.reset(); // P252
            mParkedFocus = -1f;
            mPayloadBad = false;
            mPreviewLayouts.clear();
            mLayoutTs = Long.MIN_VALUE;
            Log.i("SCAM_CAPTURE", "session mode=" + ScameraApp.getSettings().mode
                    + " route=" + (PreferenceKeys.isScamHybridEnabled() ? "SCAM_HYBRID" : PreferenceKeys.isScamEnabled() ? "SCAM_RAW" : "SCAMERA"));
            // RAW_SENSOR, RAW10 and RAW12: packed rows are unpacked for the viewfinder (RawUnpack).
            // P108: the full-resolution session always shows the RAW viewfinder (the Pixel ISP's own one is green in that mode)
            // P223: «ISP-превью в FULLRES и ISZ» off - such a session shows the RAW viewfinder, the only stream it repeats
            // P223 / P266: the module's «Поток ISP» Off in FULLRES / ISZ (the old global «ISP-превью в FULLRES и ISZ» is this now)
            // P276 «Фиктивное откл.» (WhyCam's fake off): the ISP stream stays in the session, no request targets it
            // P266: the ISP stream out of the session (the module's «Поток ISP»; Auto: a module that forces a sensor mode)
            final boolean forcedMode = vivoQcomIsz
                    || com.scamera.app.settings.ModuleRegistry.sensorMode(com.scamera.app.settings.ModuleRegistry.active()) > 0;
            // owner, 2026-11-13: the X7 Ultra's 50 MP focuses only with the ISP stream as fake off - its Auto reads so there
            final int ispSurface = SensorModePick.ispSurfaceFor(this.ispSurface, SensorModePick.session(), forcedMode,
                    mFullResSession, com.scamera.app.settings.DeviceDefaults.findX7Ultra());
            if (ispSurface != this.ispSurface)
                Log.i("SCAM_CAPTURE", "camera " + physicalID + ": «Поток ISP» Auto -> fake off (the X7 Ultra's focus in 50 MP)");
            mIspPreviewOff = photoMode && (ispSurface == 2 && (mFullResSession || IszCapturePolicy.mosaicModule(
                    com.scamera.app.settings.ModuleRegistry.active())) || ispSurface == 3);
            // P575 (owner: «Фулрез на Нубии работает при ... отключении ISP превью»): a nubia row runs without it; P586 (owner:
            // «При съемке без ремозаика isp превью у него должно отключаться»): an OPPO-family ISZ row whatever «Поток ISP», while
            // its DCG / DAG row keeps the setting (SensorModePick.ispStreamOff)
            mIspSurfaceOff = photoMode && !isBurstSession && !recordingVideo && !sdkFullRes && mVivoSdkRawSession == null
                    && !NubiaMaxResPlan.keepsIspStream(nubiaPerShot()) // P626: the per-shot way's viewfinder
                    && mRecipePlan == null // P635: the profile's viewfinder, below
                    && isRawFormat(mTargetFormat) && !mLiveRawRejected
                    && (SensorModePick.ispStreamOff(ispSurface, SensorModePick.session(), forcedMode, NubiaFullRes.device())
                    || mFullResSession && NubiaFullRes.device()); // P588: the nubia's maximum-resolution FULLRES without the ISP stream
            if (mIspSurfaceOff) {
                mIspPreviewOff = true;
                final String xiaomiWhy = XiaomiSensorRow.ispOffReason(physicalID, SensorModePick.session()); // P631
                Log.i("SCAM_CAPTURE", "camera " + physicalID + ": no ISP stream in the session (" + (SensorModePick.session() != null
                        && SensorModePick.session().rawOnly() ? "an ISZ row without remosaic, P586" : xiaomiWhy != null
                        ? xiaomiWhy + (ispSurface == 1 ? "; the setting says On" : ispSurface == 3 ? "; the setting says fake off" : "")
                        : ispSurface == 2 ? "the module's setting"
                        : "a forced sensor mode") + "), ISP stream off (RAW viewfinder) (P266)");
            }
            // P252 A/B (scam_dev.txt "isp_preview_off_always 1"): every photo session repeats the RAW stream only (the ISP preview in
            // the session, not in the request) - on the OPPO X7U that picks the HAL's ZSL use case; does its AF focus at night?
            if (photoMode && PreferenceKeys.scamDevSwitch("isp_preview_off_always", false)) mIspPreviewOff = true;
            if (mRecipePlan != null) { // P635: the viewfinder's streams are the profile's, whatever «Поток ISP» says
                mIspSurfaceOff = !mRecipePlan.ispStream();
                mIspPreviewOff = mIspSurfaceOff;
            }
            if (loficSetup != null) { // P679: the recipe's viewfinder is its PRIVATE stream (output 1)
                mIspSurfaceOff = false;
                mIspPreviewOff = false;
            }
            final boolean rawDisplaySession = nativeIsz || nativeVivoFullRes() || PreferenceKeys.isLiveViewfinderRawEnabled()
                    || mFullResSession || mIspPreviewOff;
            final boolean liveRawWanted = photoMode && !isBurstSession && !recordingVideo && !mLiveRawRejected && (!sdkFullRes || nativeVivoFullRes())
                    && isRawFormat(mTargetFormat) && (rawDisplaySession
                    || com.scamera.app.settings.UnspektraAePreferences.selected(physicalID));
            // P626: the per-shot way's RAW is no repeating stream; P635: a recipe's RAW viewfinder is its binned RAW stream's
            mLiveRawSession = mRecipePlan != null ? liveRawWanted && mRecipePlan.rawViewfinder() && mImageReaderRawView != null
                    : NubiaMaxResPlan.liveRaw(nubiaPerShot(), liveRawWanted);
            if (loficSetup != null) mLiveRawSession = false; // P679: the RAW streams are the pair's, not the viewfinder's
            // P653: a recipe's binned RAW viewfinder waits for results that run a few frames late; every other session: the default
            mLiveMetadata.imageLimit(mRecipePlan != null && mLiveRawSession && mRecipePlan.viewfinderImagesKept() > 0
                    ? mRecipePlan.viewfinderImagesKept() : PreviewFrameMatcher.DEFAULT_IMAGES);
            if (mRecipePlan != null && !mLiveRawSession && !mRecipePlan.ispStream()) {
                // P635: the profile's viewfinder is the binned RAW stream alone and the RAW viewfinder cannot run (rejected by this
                // camera before): no stream would be left to repeat - the normal session instead of a black viewfinder
                retryWithoutRecipe(null, "the RAW viewfinder cannot run in this session, and the profile has no ISP viewfinder");
                return;
            }
            LiveRawFrame.setMaxLongSide(rawViewfinder); // P266
            LiveRawFrame.setEnabled(false); // invalidate the previous session even when RAW remains enabled
            mPortraitVendorWindow = IszCapturePolicy.mosaicModule(com.scamera.app.settings.ModuleRegistry.active())
                    || SensorModePick.session() != null || mIszLease != null;
            resetEttr(); // P356: each session meters its own boost (before setupPreview reads ettrIndex)
            LiveRawFrame.setEnabled(mLiveRawSession && rawDisplaySession);
            // P13: a module whose stream was measured as a mosaic before starts on the RAW viewfinder at once
            mMosaicMeasure = PreferenceKeys.scamDevSwitch("mosaic_preview", true);
            com.scamera.app.processing.MosaicStream.startSession(mMosaicMeasure ? mosaicStreamKey() : "off");
            // P60: a vendor module measures its field of view against the plain module of its camera (cached once measured)
            if (photoMode && !XiaomiTeleZoom.phone() && PreferenceKeys.scamDevSwitch("fov_check", true)) try {
                com.scamera.app.control.FovSelfCheck.get().session(ScameraApp.getAppContext(), physicalID,
                        com.scamera.app.settings.ModuleRegistry.active(),
                        com.scamera.app.settings.TunableKeyManager.signature(physicalID), this::onZoomChanged);
            } catch (RuntimeException e) {
                Log.w(TAG, "FOV self-check: " + e.getMessage());
            }
            updateStreamCrop(false);
            // P35 / P33: the GPU programs of this module's merge route (its stored / declared colour block) built now, not on the shutter
            // (an optimisation only: a failure here must never stop the session from starting)
            if (photoMode && !isBurstSession && !recordingVideo && PreferenceKeys.isScamHybridEnabled()) try {
                com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.prewarmHybridGpu(ScameraApp.getAppContext(),
                        com.scamera.app.processing.MosaicBlockStore.blockForModule(
                                com.scamera.app.processing.MosaicStream.key(),
                                // P214i: the declared block by the X300 Ultra's list (elsewhere 5 -> 2, 7 -> 4 as before)
                                VivoQcomIsz.declaredBlockOf(com.scamera.app.settings.ModuleRegistry.active())));
            } catch (RuntimeException e) {
                Log.w(TAG, "Hybrid GPU prewarm skipped: " + e);
            }
            mMosaicPreview = loficSetup == null && mMosaicMeasure && !mLiveRawSession && photoMode && !isBurstSession && !recordingVideo && isZslMode()
                    && isRawFormat(mTargetFormat) && com.scamera.app.processing.MosaicStream.block() > 1;
            if (mMosaicPreview) LiveRawFrame.setEnabled(true);
            LiveRawFrame.setMosaicPreview(mMosaicPreview);
            mModeBarrier = false;
            final boolean xiaomiLogical = mXiaomiRouted && !Objects.equals(physicalID, logicalID);
            XiaomiTeleZoom.startSession(xiaomiLogical, PreferenceKeys.scamDevSwitch("xiaomi_tele_fallback", false));
            // P641: the Xiaomi 17 Ultra's dual-gain route (DCG, DCG + LOFIC, the ultra wide's DSG) chooses this session's
            // variant before anything asks for its keys or its streams; a kind with nothing left steps down here and says so
            // P679: a LOFIC session (or one whose LOFIC setups are all gone: the normal session) runs no dual-gain variant
            final boolean dualGainWanted = !xiaomiLogical && !sdkFullRes && !mFullResSession && mRecipePlan == null
                    && !recordingVideo && XiaomiDualGain.device() && Dcg14Mode.active(mCameraCharacteristics) && !loficWanted;
            String dualGainNote = XiaomiDualGain.sessionStart(physicalID, mCameraCharacteristics, dualGainWanted);
            if (loficNote[0] != null) dualGainNote = loficNote[0];
            // P641d: the «lofic-pair» variant needs its second stream's reader; without one that variant failed, the next runs
            if (XiaomiDualGain.pairWanted()) {
                if (!openLoficReader() && XiaomiDualGain.sessionFailed("has no reader for the overflow stream (RAW10 needs Android 13)")) {
                    final String note = XiaomiDualGain.sessionStart(physicalID, mCameraCharacteristics, dualGainWanted);
                    if (note != null) dualGainNote = note;
                    if (XiaomiDualGain.pairWanted()) XiaomiDualGain.sessionEnd(); // never twice: the plain session
                }
            } else closeLoficReader();
            if (dualGainNote != null) showToast(dualGainNote);
            newPreviewRequest();
            if (mLoficReader != null) mPreviewRequestBuilder.addTarget(mLoficReader.getSurface()); // P641d: every frame has its pair
            if (mMosaicPreview && XiaomiTeleZoom.ispPreview()) {
                // P41: the switch owns the tele's sensor mode (a module's tunable current_mode is overridden): ISP preview
                mMosaicPreview = false;
                LiveRawFrame.setMosaicPreview(false);
                if (!mLiveRawSession) LiveRawFrame.setEnabled(false);
            }

            List<Surface> surfaces = loficSetup != null ? loficSurfaces() : sessionOutputs(isBurstSession); // P679: the recipe's six
            final Surface loficSurface = mLoficReader == null ? null : mLoficReader.getSurface(); // P641d
            if (loficSurface != null) {
                surfaces = new ArrayList<>(surfaces);
                surfaces.add(loficSurface);
            }
            Log.d(TAG, "session outputs: " + surfaces);
            final ArrayList<OutputConfiguration> outputConfigurations = new ArrayList<>(surfaces.size());
            boolean croppedRaw = false;
            final Surface rawSurface = mImageReaderRaw == null ? null : mImageReaderRaw.getSurface();
            final Surface yuvSurface = mImageReaderPreview == null ? null : mImageReaderPreview.getSurface();
            for (Surface out : surfaces) {
                final OutputConfiguration config = new OutputConfiguration(out);
                // P646: the YUV route's 10-bit stream is the HLG10 profile's (the viewfinder stays STANDARD)
                if (out == rawSurface && mTargetFormat == YuvMergeRoute.P010 && Build.VERSION.SDK_INT >= 33)
                    config.setDynamicRangeProfile(android.hardware.camera2.params.DynamicRangeProfiles.HLG10);
                // P41b: on the Xiaomi logical camera the viewfinder (and a recording) is the logical camera's stream, zoomed and
                // switched by the HAL as for the stock camera; the image readers stay the tele's physical streams
                final boolean vivoLogicalStream = VivoQcomModes.wantsStreamUsage() && "2".equals(logicalID);
                final boolean logicalStream = vivoLogicalStream || xiaomiLogical && out != rawSurface && out != yuvSurface;
                if(!Objects.equals(physicalID, logicalID) && !logicalStream && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
                    config.setPhysicalCameraId(physicalID);
                }
                // P100: in-sensor zoom (ISZ) through the RAW stream's CROPPED_RAW use case where the camera lists it; not on the
                // Xiaomi tele (P41 / P41b crop the photo from the HAL's own zoom report: a second crop source would divide twice)
                // P214i: not on an X300 Ultra module that forces a sensor mode (the mode is the sensor crop; the HAL's own ISZ by
                // the zoom ratio may remosaic or override it): its RAW stream is of the default use case
                if (out == rawSurface && mTargetFormat == ImageFormat.RAW_SENSOR && Build.VERSION.SDK_INT >= 34
                        && !xiaomiLogical && !XiaomiTeleZoom.active() && !vivoQcomIsz && mRecipePlan == null
                        && !sdkFullRes && VivoQcomIsz.croppedRawAllowed(physicalID)
                        && !sCroppedRawRefused.contains(physicalID) && CroppedRaw.supported(mCameraCharacteristics)) {
                    try {
                        config.setStreamUseCase(CroppedRaw.USE_CASE);
                        croppedRaw = true;
                    } catch (RuntimeException e) {
                        Log.w(TAG, "CROPPED_RAW not set: " + e);
                    }
                }
                // P108: every stream of the full-resolution session (sizes of the max-resolution map) in MAXIMUM_RESOLUTION mode
                // P626: a nubia per-shot way - the RAW alone, the viewfinder DEFAULT (NubiaMaxResPlan)
                // P635: a recipe's outputs - the vendor stream use case of the capture RAW alone, pixel modes only when annotated
                // P641d: the overflow stream as the vendor's stream sets have it - RAW10 of the vendor use case 524554
                if (out == loficSurface && Build.VERSION.SDK_INT >= 33) config.setStreamUseCase(LoficPair.USE_CASE);
                if (mRecipePlan != null) recipeOutput(config, out);
                else if (mFullResSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    for (int pixelMode : NubiaMaxResPlan.pixelModes(true, nubiaPerShot(), out == rawSurface ? NubiaMaxResPlan.Stream.RAW
                            : out == surface ? NubiaMaxResPlan.Stream.VIEWFINDER : NubiaMaxResPlan.Stream.OTHER))
                        config.addSensorPixelModeUsed(pixelMode);
                outputConfigurations.add(config);
            }
            if (mFullResSession && NubiaRemosaicProbe.running(physicalID)) // P626: which stream and request runs which pixel mode
                Log.i("SCAM_CAPTURE", NubiaMaxResPlan.describe(physicalID, NubiaRemosaicProbe.describe(), nubiaPerShot(),
                        mBufferSize == null ? null : new Size(mBufferSize.getHeight(), mBufferSize.getWidth()),
                        mImageReaderRaw == null ? null : new Size(mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight()),
                        NubiaRemosaicProbe.operationMode(physicalID)));
            if (mRecipePlan != null) Log.i("SCAM_CAPTURE", mRecipePlan.describe(String.valueOf(physicalID))); // P635: the whole field set
            mCroppedRawSession = croppedRaw;
            mLastRawCrop = null;
            mLastRawCropZoom = -1f;
            if (croppedRaw) Log.i("SCAM_CAPTURE", "camera " + physicalID + ": RAW stream with CROPPED_RAW (in-sensor zoom from ~2x, P100)");
            final VivoQcomIsz.StreamCheck iszCheck = mIszCheck;
            if (iszCheck != null) Log.i("SCAM_CAPTURE", VivoQcomIsz.describe(iszCheck, croppedRaw));
            if (xiaomiLogical) Log.i(TAG, "P41b: session on logical camera " + logicalID + ": viewfinder logical, readers on camera "
                    + physicalID + ", operation mode 0x" + Integer.toHexString(XiaomiTeleZoom.operationMode(xiaomiOperationModeSetting())));

            final CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession configured) {
                    OpenTimeline.configured(); // P680
                    if (mConfiguringDevice == sessionDevice) mConfiguringDevice = null;
                    synchronized (mPreviewStateLock) {
                        Log.d(TAG, "session configured: " + configured);
                        if (staleSession(configured, sessionDevice, generation)) return;
                        sessionReady(configured, generation, sdkFullRes, scamPreview);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession refused) {
                    try {
                        synchronized (mPreviewStateLock) {
                            if (staleSession(refused, sessionDevice, generation)) return;
                            Log.d(TAG, "session configuration refused");
                            sessionRefused(refused, sessionDevice);
                        }
                    } finally {
                        if (mConfiguringDevice == sessionDevice) mConfiguringDevice = null; // onError waits for this
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // P211: the module's «Тип сессии камеры» as Android takes it, never in FULLRES, 0 after the camera refused it
                final int vendorMode = sVendorModeRefused.contains(physicalID) ? 0 : sessionOperationMode(sessionType, sdkFullRes || mFullResSession);
                // P230: the DCG route's own operation mode (Xiaomi 15 Ultra: 0x9203) wins over the module's session type; a refusal
                // turns DCG off (retryWithoutDcgRoute), not the module's mode
                final boolean dcgRoute = !xiaomiLogical && !sdkFullRes && !mFullResSession && Dcg14Mode.requestKeys(mCameraCharacteristics);
                final int dcgOperationMode = dcgRoute ? Dcg14Mode.operationMode(mCameraCharacteristics) : 0;
                mDcgOperationMode = dcgOperationMode;
                mDcgRequestKeys = dcgRoute;
                SensorModeBand.sessionStart(dcgRoute && loficSetup == null, mCameraCharacteristics, XiaomiDualGain.sessionActive() ? XiaomiDualGain.askedRow()
                        : dcgOperationMode != 0 ? XiaomiDcg.dcgMode() : -1);
                XiaomiDcg.newSession();
                mVendorOperationMode = xiaomiLogical || dcgOperationMode != 0 ? 0 : vendorMode;
                // P598: the nubia remosaic probe's setting may be the stock QCFA mode's session operation mode (0x8012); a refusal
                // fails that setting (retryWithoutFullRes), not the module's mode
                final int probeMode = mFullResSession && !xiaomiLogical && dcgOperationMode == 0 ? NubiaRemosaicProbe.operationMode(physicalID) : 0;
                if (probeMode != 0) Log.i("SCAM_CAPTURE", "nubia FULLRES camera " + physicalID + ": session operation mode 0x"
                        + Integer.toHexString(probeMode) + " (remosaic probe " + NubiaRemosaicProbe.describe() + ")");
                if (!xiaomiLogical && sessionType != 0)
                    Log.i("SCAM_CAPTURE", "camera " + physicalID + ": session operation mode " + vendorMode + " (setting " + sessionType
                            + (vendorMode == sessionType ? ")" : sdkFullRes || mFullResSession ? ": FULLRES runs 0)"
                            : sVendorModeRefused.contains(physicalID) ? ": refused by this camera, 0)" : ": not a vendor mode 32768..65535, 0)"));
                if (dcgOperationMode != 0)
                    Log.i("SCAM_CAPTURE", "camera " + physicalID + ": DCG session operation mode 0x" + Integer.toHexString(dcgOperationMode)
                            + " (" + dcgOperationMode + ")" + (sessionType != 0 ? ", instead of the module's " + sessionType : ""));
                // P604: an OPPO-family FULLRES row tried in its seamless entry's operation mode (the PYB110 main's row 25: 0x8001); a
                // refusal fails that row's trial (retryWithoutVivoSdkFullRes), not the module's mode
                final int fullResOpMode = sdkFullRes && VivoSdkFullRes.oppo() ? OppoNativeFullRes.operationMode(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId)) : 0;
                if (fullResOpMode != 0) Log.i("SCAM_CAPTURE", "OPPO native FULLRES camera " + mVivoSdkCameraId + ": session operation mode 0x"
                        + Integer.toHexString(fullResOpMode) + " (the row's seamless entry, P604)");
                SessionConfiguration configuration = new SessionConfiguration(
                        xiaomiLogical ? XiaomiTeleZoom.operationMode(xiaomiOperationModeSetting())
                                : mRecipePlan != null ? mRecipePlan.recipe.operationMode // P635: the profile's (0: regular)
                                : dcgOperationMode != 0 ? dcgOperationMode
                                : probeMode != 0 ? probeMode : fullResOpMode != 0 ? fullResOpMode : vendorMode,
                        outputConfigurations,
                        processExecutor,
                        stateCallback
                );
                boolean vivoEis = !sdkFullRes && ScamPreview.supported() && PreferenceKeys.isEisPhotoOn() && !ForcedStabilization.active() && !vivoQcomIsz && !PlainPreviewCameras.contains(physicalID);
                mVivoEisSession = vivoEis;
                // P54d's stabilisation mode as a session parameter on vivo only: on the Xiaomi 13 Ultra and the OPPO Find X9 Ultra
                // (2026-10-10 logs) every session with session parameters / PREVIEW_STABILIZATION next to the RAW stream stopped
                // delivering frames or killed the HAL (onError 4); before P54d no other brand got session parameters.
                int vsMode = vivoBrand() ? getPreferredVideoStabilizationMode(mCameraCharacteristics, recordingVideo, PreferenceKeys.isEisPhotoOn())
                        : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
                // P102: vendor keys the owner set on the module («Добавить vendor tag») are session parameters as well (the
                // Motorola Edge 50 Pro's com.lenovo.moto.control.super_zoom is a session key); only when there are such keys
                final boolean moduleKeys = !com.scamera.app.settings.TunableKeyManager.signature(physicalID).isEmpty();
                // P103: the HAL features of «Движение» and Ideal RAW are session keys
                final boolean hiddenKeys = HiddenFeatures.wantsSession(mCameraCharacteristics);
                final boolean dcg14 = Dcg14Mode.active(mCameraCharacteristics);
                final boolean vivoQcomStreams = VivoQcomModes.wantsStreamUsage();
                // P320: a DCG session always learns its depth, clip and floor from the samples (its white is never the declared
                // 1023): scam_dev raw_wide 0 keeps it for the plain modes only
                final boolean dcgFrames = Dcg14Mode.requested(mCameraCharacteristics);
                com.scamera.app.processing.RawPayloadCheck.setWideEnabled(PreferenceKeys.scamDevSwitch("raw_wide", true) || dcgFrames);
                com.scamera.app.processing.RawLevels.noteDcgFrames(dcgFrames); // P320
                // P230: the DCG route's 14-bit samples resolve as such from the first frame (the results confirm or take it back)
                if (dcgOperationMode != 0) com.scamera.app.processing.RawLevels.noteKnownBits(
                        com.scamera.app.processing.RawLevels.staticBlack(mCameraCharacteristics),
                        com.scamera.app.processing.RawLevels.staticWhite(mCameraCharacteristics),
                        Dcg14Mode.knownBits(mCameraCharacteristics), "Xiaomi DCG session");
                // P679: the Long stream is 14 bit (black 1024, white 16383) whatever the metadata says
                if (loficSetup != null) com.scamera.app.processing.RawLevels.noteKnownBits(
                        com.scamera.app.processing.RawLevels.staticBlack(mCameraCharacteristics),
                        com.scamera.app.processing.RawLevels.staticWhite(mCameraCharacteristics), 14, "Xiaomi LOFIC Long stream");
                VivoQcomIsz.sessionStart(); // P274: the session's forceSensorMode is known once its parameters are built
                if (loficSetup != null) {
                    // P679: the recipe's session parameters: TEMPLATE_RECORD, CONTROL_MODE_AUTO, the exact AE range and the vendor keys
                    final CaptureRequest.Builder loficSession = sessionDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    Log.i("SCAM_CAPTURE", "XIAOMI LOFIC camera " + physicalID + " session parameters (type 0x"
                            + Integer.toHexString(loficSetup.sessionType) + "): " + XiaomiLofic.apply(loficSession, loficSetup, true) + " (P679)");
                    configuration.setSessionParameters(loficSession.build());
                } else if (sdkFullRes) {
                    CaptureRequest.Builder sdkSession = sessionDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    if (nativeVivoFullRes()) applyVivoSdkPreview(sdkSession);
                    if (fullSizeRecipe()) { // P684: the stock full-size recipe's session keys
                        final List<String> unknown = X300FullSize.applySession(sdkSession);
                        Log.i("SCAM_CAPTURE", "200 MP full-size session keys on camera " + physicalID
                                + (unknown.isEmpty() ? ": all set" : ": unknown to this firmware (skipped) " + unknown) + " (P684)");
                    }
                    else {
                        mVivoSdkRawSession.apply(sdkSession, false, 0);
                        mVivoSdkRawSession.applyStreamUsage(sdkSession);
                    }
                    configuration.setSessionParameters(sdkSession.build());
                } else if (scamPreview || mForcedStabEis || xiaomiLogical || vivoEis || moduleKeys || hiddenKeys || dcg14 || vivoQcomIsz || vivoQcomStreams
                        || rawFps > 0 || SensorModePick.session() != null || NubiaRemosaicProbe.wantsSession(physicalID)
                        || mRecipePlan != null
                        || vsMode != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
                    try {
                        // P635: a recipe's session parameters from the profile's template
                        CaptureRequest.Builder scamSession = sessionDevice.createCaptureRequest(mRecipePlan != null
                                && mRecipePlan.recipe.sessionTemplate > 0 ? mRecipePlan.recipe.sessionTemplate : CameraDevice.TEMPLATE_PREVIEW);
                        VendorTagUtils.builderSessionApply(scamSession, false, useMaximumResolutionKey, physicalID);
                        if (vivoQcomStreams) {
                            int[] roles = new int[surfaces.size()];
                            for (int i = 0; i < roles.length; i++) {
                                Surface output = surfaces.get(i);
                                roles[i] = output == rawSurface ? (recordingVideo ? 12 : 7)
                                        : output == yuvSurface ? 2 : recordingVideo ? 6 : 1;
                            }
                            Log.i("SCAM_CAPTURE", "vivo Qualcomm stream purposes: "
                                    + VivoQcomModes.applyStreamUsage(scamSession, roles));
                        }
                        if (dcg14) Log.i("SCAM_CAPTURE", "DCG 14 bit on camera " + physicalID + ": "
                                + Dcg14Mode.apply(scamSession, mCameraCharacteristics, physicalID));
                        if (vivoQcomIsz) Log.i("SCAM_CAPTURE", "vivo Qualcomm ISZ session keys on camera " + physicalID + ": "
                                + VivoQcomModes.apply(scamSession, mCameraCharacteristics, physicalID));
                        if (scamPreview) ScamPreview.applySession(scamSession);
                        // P54b: the stock Photo mode sets the EIS key as a session parameter too
                        if (mForcedStabEis) ForcedStabilization.apply(scamSession, false, true);
                        if (vivoEis) ScamPreview.applyPreviewEis(scamSession);
                        if (vsMode != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
                            scamSession.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vsMode);
                        }
                        // P41b: the stock camera's session keys of the logical camera
                        if (xiaomiLogical) Log.i(TAG, "P41b: logical session keys: " + XiaomiTeleZoom.applySessionKeys(scamSession));
                        // P122: the module's session-only vendor keys (vivo X300 Ultra: forceSensorMode)
                        com.scamera.app.settings.TunableKeyManager.applySessionKeys(scamSession, physicalID);
                        VivoPd2454NativeRaw.apply(scamSession, physicalID);
                        if (SensorModePick.session() != null) // P276: the picked mode on the session (and on every request)
                            Log.i("SCAM_CAPTURE", "camera " + physicalID + " sensor mode pick on the session: " + SensorModePick.apply(scamSession));
                        // P598: the remosaic probe's keys came with builderSessionApply (on every request as well); P608: the full-size
                        // route's way and the full row's mode key too
                        if (NubiaRemosaicProbe.wantsSession(physicalID)) Log.i("SCAM_CAPTURE", "nubia FULLRES camera " + physicalID
                                + " maximum-resolution session parameters: " + NubiaRemosaicProbe.describe());
                        if (rawFps > 0) scamSession.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(rawFps, rawFps)); // P266
                        if (mRecipePlan != null) { // P635: the profile's frame rate and session tags (the request tags came above)
                            if (mRecipePlan.recipe.fpsLow > 0) scamSession.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    new Range<>(mRecipePlan.recipe.fpsLow, mRecipePlan.recipe.fpsHigh));
                            Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + " session parameters: "
                                    + FullResRecipeTags.apply(scamSession, mRecipePlan.sessionTags()));
                        }
                        if (VivoQcomIsz.device()) Log.i("SCAM_CAPTURE", "X300 Ultra camera " + physicalID + " sensor mode: "
                                + VivoQcomIsz.sessionBuilt(scamSession));
                        configuration.setSessionParameters(scamSession.build());
                    } catch (IllegalArgumentException unsupported) {
                        Log.w("SCAM_CAPTURE", "SCAM session control unavailable", unsupported);
                    }
                }
                mConfiguringDevice = mCameraDevice;
                mCameraDevice.createCaptureSession(configuration);
            } else {
                mCameraDevice.createCaptureSession(surfaces, stateCallback, mBackgroundHandler);
            }
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            if (retryWithoutLofic(null, "session: " + e.getMessage())) return; // P679
            if (retryWithoutRecipe(null, "session: " + e.getMessage())) return; // P635
            if (retryWithoutDcgRoute(null, "session: " + e.getMessage())) return; // P230
            if (retryWithoutVendorMode(null)) return; // P211
            if (retryWithoutVivoSdkFullRes(null, "session: " + e.getMessage())) return;
            if (retryWithoutRequestedSensorMode(null, "session: " + e.getMessage())) return;
            // P41b: a logical session the framework refuses outright (no onConfigureFailed follows) steps down as well
            if (mXiaomiRouted) retryXiaomiRoute(null, "session: " + e.getMessage());
            else retryWithoutFullRes(null, "session: " + e.getMessage());
        }
    }


    /** P41b: dev switch {@code xiaomi_opmode}: the logical session's operation mode (stock 0x9002 = 36866, 0 = regular). */
    private static int xiaomiOperationModeSetting() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi_opmode", XiaomiTeleZoom.STOCK_OPERATION_MODE));
    }

    /**
     * A camera that refuses the session with the vivo stock preview profile in its session parameters (vivo X200 Pro front
     * camera, owner's log 2026-10-08: every stream size failed, the back cameras of the same session code ran) retries once
     * with the plain Camera2 preview, before smaller streams; the camera keeps the plain preview on this firmware (owner,
     * 2026-10-19, vivo X300: every camera refused the EIS session key once per app run). The HAL may close the device after
     * the refusal (onError 4): that error reopens the camera quietly ({@link #mRetryDevice}).
     */
    private boolean retryWithoutScamSession(CameraCaptureSession session) {
        final CameraDevice device = mCameraDevice;
        final Handler handler = mBackgroundHandler;
        if ((!mScamPreviewActive && !mForcedStabEis && !mVivoEisSession) || device == null || handler == null || !PlainPreviewCameras.addRefused(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": session configuration failed with the vivo "
                + (mScamPreviewActive ? "stock preview profile" : "EIS session key") + "; retry with the plain Camera2 preview");
        try { session.close(); } catch (Exception ignored) {}
        handler.post(() -> {
            if (!isCameraResumed || mCameraDevice != device) return;
            startSession(false);
        });
        return true;
    }

    /**
     * P108: a full-resolution session the camera refuses (or that the framework rejects) runs binned again: the camera restarts
     * with the normal RAW stream (this camera, this run) and the owner sees why.
     */
    private boolean retryWithoutVivoSdkFullRes(@Nullable CameraCaptureSession session, String why) {
        final String note;
        synchronized (mVivoSdkLock) {
            if (mVivoSdkRawSession == null || !PixelPro10.fullResOn()) return false;
            // P684: the camera died under a 200 MP still-only series whose next variant is noted (StillOnly200): the series'
            // failure has asked for the restart already, the row and FULLRES stay for that variant
            if (mStillOnlyNextVariant && why.startsWith("camera error")) {
                mStillOnlyNextVariant = false;
                Log.w("SCAM_CAPTURE", "200 MP still-only: " + why + " under the series; its next variant the next press, the row stays (P684)");
                if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
                return true;
            }
            // P186: the X100 / X300 Ultra note the row that failed and try the next one of its size the next time
            note = !nativeVivoFullRes() ? null : VivoSdkFullRes.oppo() // P192: an OPPO camera tries its next mode the next time
                    ? OppoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), why)
                    : VivoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), why);
            PixelPro10.setFullRes(false);
        }
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        Log.w("SCAM_CAPTURE", "SDK FULLRES50 refused: " + why);
        // P229 (rawr's X300 Ultra notes): vivo's own camera open in the background makes full-size sessions fail (error 3 / 4)
        final String vivoHint = VivoSdkFullRes.oppo() ? "" : Lang.t(" Если в фоне открыта камера vivo — закройте её и попробуйте снова.",
                " If vivo's camera is open in the background, close it and try again.");
        showError((note != null ? note : Lang.t("SDK 50 Мп: камера отказала, обычный RAW", "SDK 50 MP: camera refused, normal RAW")) + vivoHint);
        mainHandler().post(this::restartCamera);
        return true;
    }

    private boolean retryWithoutRequestedSensorMode(@Nullable CameraCaptureSession session, String why) {
        // P276: a module's picked SensorModeTable row the camera refused goes back to Auto (said once, the camera reopens)
        final SensorModePick.Pick pick = SensorModePick.session();
        if (pick != null && (pick.rawOnly() || pick.dcg())) {
            // P586: the OnePlus 16's ISZ row (the module crops digitally from now on) or DCG / DAG row (the switch off, the next
            // row the next time) failed: noted for this firmware, with a notice, the normal session
            final String note = pick.rawOnly()
                    ? OplusIsz.failed(physicalID, com.scamera.app.settings.ModuleRegistry.active(), pick, why)
                    : OplusDcg.failed(physicalID, pick.mode, why);
            SensorModePick.session(null, null);
            if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
            // P593: a DCG / DAG row that gave a plain RAW while a candidate is left: an info, the restart takes the next row
            if (pick.dcg() && OplusDcg.searching()) showToast(note);
            else showError(note);
            mainHandler().post(this::restartCamera);
            return true;
        }
        if (pick != null && pick.fullRes) {
            // P575: the nubia size switch's row failed: noted for this firmware (the 50 Мп size dims), FULLRES off, the normal session
            final String note = NubiaFullRes.failed(physicalID, pick.mode, why);
            PixelPro10.setFullRes(false);
            SensorModePick.session(null, null);
            if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
            showError(note);
            mainHandler().post(this::restartCamera);
            return true;
        }
        if (pick != null) {
            final String slot = com.scamera.app.settings.ModuleRegistry.active();
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putString(SensorModePick.prefKey(slot), "-1").apply();
            SensorModePick.session(null, null);
            if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
            Log.w("SCAM_CAPTURE", "camera " + physicalID + ": sensor mode pick " + pick + " refused (" + why + "): the module is on Auto again");
            showError(Lang.t("Режим сенсора " + pick.mode + " отклонён камерой; модуль снова в «Авто»",
                    "Sensor mode " + pick.mode + " refused by the camera; the module is on Auto again"));
            mainHandler().post(this::restartCamera);
            return true;
        }
        final boolean dcg = Dcg14Mode.on();
        try {
            String slot = com.scamera.app.settings.ModuleRegistry.active();
            // P575: the nubia «2×» has its own pick branch above (its row refused); an error without the pick recovers quietly
            if (!dcg && (!SensorModeSwitches.sensorPreset(slot) || NubiaFullRes.iszModule(slot))) return false;
            if (!SensorModeSwitches.selectPlain()) {
                Dcg14Mode.setMode(Dcg14Mode.OFF);
                showError(Lang.t("Режим сенсора отклонён; выберите обычный модуль", "Sensor mode refused; choose a plain module"));
                return false;
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(false);
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        Log.w("SCAM_CAPTURE", "requested " + (dcg ? "DCG" : "sensor preset") + " refused: " + why);
        showError(Lang.t("Режим сенсора отклонён; обычный модуль", "Sensor mode refused; using a plain module"));
        mainHandler().post(this::restartCamera);
        return true;
    }

    // ---------------------------------------------------------------- P635: the full-resolution recipe

    /**
     * P635: decides this session's recipe (FullResRecipeStore.active: the lens's switch, a profile that can run and did not fail)
     * and its plan - the viewfinder's streams as the profile has them, the capture RAW, the companion. Photo modes only.
     */
    private void recipeSession(boolean sdkFullRes, StreamConfigurationMap map, CameraCharacteristics characteristics) {
        mRecipe = null;
        mRecipePlan = null;
        mRecipeCompanionLostLogged = false;
        FullResRecipeSession.session(null);
        final CameraMode mode = ScameraApp.getSettings().mode;
        if (sdkFullRes || recordingVideo || !isRawFormat(mTargetFormat)
                || (mode != CameraMode.PHOTO && mode != CameraMode.NIGHT && mode != CameraMode.MOTION)) return;
        final FullResRecipeStore.Active active = FullResRecipeStore.active(physicalID, characteristics);
        if (active == null) return;
        Size viewfinder = null;
        try {
            viewfinder = getPreviewOutputSize(characteristics, mode);
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "FULLRES RECIPE: no viewfinder size (" + e + ")");
        }
        // the viewfinder's RAW: the camera's own largest listed RAW stream, 16-bit where it lists one
        Size[] own = rawSizesLargestFirst(map, ImageFormat.RAW_SENSOR);
        int ownFormat = ImageFormat.RAW_SENSOR;
        if (own.length == 0) {
            own = rawSizesLargestFirst(map, mTargetFormat);
            ownFormat = mTargetFormat;
        }
        final List<int[]> yuv = new ArrayList<>();
        try {
            StreamConfigurationMap sizes = map;
            if (active.recipe.pixelMode == FullResRecipe.PixelMode.ANNOTATE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                final StreamConfigurationMap max = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
                if (max != null) sizes = max;
            }
            final Size[] listed = sizes.getOutputSizes(ImageFormat.YUV_420_888);
            if (listed != null) for (Size s : listed) yuv.add(new int[]{s.getWidth(), s.getHeight()});
        } catch (RuntimeException ignored) {
        }
        // P653: the binned stream beside the full-size mode (at most half its sides), not a larger mode the map may list
        final List<int[]> ownSizes = new ArrayList<>();
        for (Size s : own) ownSizes.add(new int[]{s.getWidth(), s.getHeight()});
        final int[] ownView = FullResRecipePlan.viewfinderRaw(ownSizes, active.recipe.width, active.recipe.height);
        final FullResRecipePlan plan = FullResRecipePlan.of(active.recipe, new FullResRecipePlan.Env(
                viewfinder == null ? 0 : Math.max(viewfinder.getWidth(), viewfinder.getHeight()),
                viewfinder == null ? 0 : Math.min(viewfinder.getWidth(), viewfinder.getHeight()),
                ownView == null ? 0 : ownView[0], ownView == null ? 0 : ownView[1], ownFormat, yuv));
        if (plan == null || !plan.consistent(true) || !plan.consistent(false)) {
            Log.w("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + " " + active.recipe + ": no session for it here ("
                    + (plan == null ? "no viewfinder stream" : "a request's pixel mode does not fit its streams") + "), the normal session (P635)");
            return;
        }
        mRecipe = active;
        mRecipePlan = plan;
        FullResRecipeSession.session(plan);
        // P635b: an unproven profile's first session takes one still to be judged before the owner shoots
        mRecipeProof = FullResRecipeStore.provedActive(active) ? 0 : 1;
        Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + " on: " + active.recipe + " (module " + active.slot
                + (active.autoRow >= 0 ? ", instead of the module's sensor mode row " + active.autoRow + ": that row alone gives no full frame, P635b" : "")
                + (mRecipeProof == 1 ? ", not proven on this firmware yet: one proof still follows" : ", proven on this firmware") + ", P635)");
    }

    /**
     * P635b: the proof still of a recipe session - one request as a shot's (the profile's template, the capture RAW and the
     * companion, the still tags), sent after the session's first preview result; its frame is judged (FullResTruth) and dropped.
     * Not the sensor's full readout, a failed request or no frame in {@link #RECIPE_PROOF_TIMEOUT_MS}: the profile fails and the
     * normal session opens before the owner takes a shot; a real frame is remembered for the lens and this firmware.
     */
    private void sendRecipeProof() {
        final CameraCaptureSession session = mCaptureSession;
        final CameraDevice device = mCameraDevice;
        final FullResRecipePlan plan = mRecipePlan;
        final ImageReader raw = mImageReaderRaw;
        if (session == null || device == null || plan == null || raw == null || mRecipeProof != 1 || shotInFlight() || mShotInProgress) return;
        mRecipeProof = 2;
        try {
            final CaptureRequest.Builder b = device.createCaptureRequest(plan.recipe.stillTemplate > 0 ? plan.recipe.stillTemplate : CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(raw.getSurface());
            final ImageReader companion = mRecipeCompanion;
            if (companion != null) b.addTarget(companion.getSurface());
            else if (plan.viewfinderCompanion() && surface != null) b.addTarget(surface);
            VendorTagUtils.builderSessionApply(b, true, false, physicalID);
            recipeFocus(b); // P635c: the proof still does not let go of the viewfinder's focus
            final Integer pixelMode = plan.pixelMode(true);
            if (pixelMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.set(CaptureRequest.SENSOR_PIXEL_MODE, pixelMode);
            if (plan.recipe.fpsLow > 0) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(plan.recipe.fpsLow, plan.recipe.fpsHigh));
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureFailed(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    if (mRecipeProof != 2 || mRecipePlan != plan) return;
                    mRecipeProof = 0;
                    mainHandler().post(() -> retryWithoutRecipe(mCaptureSession, "the proof still failed (reason " + failure.getReason() + ")"));
                }

                @Override public void onCaptureBufferLost(@NonNull CameraCaptureSession s, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
                    if (mRecipeProof != 2 || mRecipePlan != plan) return;
                    // P653: the companion's buffer is dropped by the HAL on full-size stills; only the capture RAW's fails the proof
                    if (target != raw.getSurface()) {
                        recipeCompanionLost(frameNumber, "the proof still");
                        return;
                    }
                    mRecipeProof = 0;
                    mainHandler().post(() -> retryWithoutRecipe(mCaptureSession, "the proof still's full-size buffer was lost (frame " + frameNumber + ")"));
                }
            }, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + ": proof still sent (template " + FullResRecipe.templateName(plan.recipe.stillTemplate)
                    + " -> " + plan.targets(true) + ", P635b)");
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            mRecipeProof = 0;
            retryWithoutRecipe(session, "the proof still was refused (" + e.getMessage() + ")");
            return;
        }
        mainHandler().postDelayed(() -> {
            if (mRecipeProof != 2 || mRecipePlan != plan) return;
            mRecipeProof = 0;
            // a shot taken meanwhile is judged by its own first frame
            if (!shotInFlight() && !mShotInProgress) retryWithoutRecipe(mCaptureSession, "no frame of the proof still in " + RECIPE_PROOF_TIMEOUT_MS + " ms");
        }, RECIPE_PROOF_TIMEOUT_MS);
    }

    /**
     * P653: a lost buffer on a recipe still's companion: ignored (the HAL drops it on full-size stills; only the capture RAW's
     * fails a still), said once a session.
     */
    private void recipeCompanionLost(long frameNumber, String what) {
        if (mRecipeCompanionLostLogged) return;
        mRecipeCompanionLostLogged = true;
        Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + ": the companion's buffer of " + what + " lost (frame " + frameNumber
                + "): ignored, only a lost full-size buffer fails a still; said once a session (P653)");
    }

    private volatile boolean mRecipeCompanionLostLogged;

    /** P635b: the proof still's frame: judged, closed; the verdict fails the profile or is remembered for the lens. */
    private void recipeProofFrame(Image img) {
        final FullResRecipeStore.Active active = mRecipe;
        FullResTruth.Result truth = null;
        try {
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            final float black = com.scamera.app.processing.RawLevels.current(c, null).meanBlack();
            if (img.getFormat() == ImageFormat.RAW_SENSOR)
                truth = FullResTruth.check(img.getPlanes()[0].getBuffer(), img.getWidth(), img.getHeight(), img.getPlanes()[0].getRowStride(), black);
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "FULLRES RECIPE proof: " + e);
        } finally {
            img.close();
        }
        mRecipeProof = 0;
        if (truth == null || active == null) return;
        Log.i("SCAM_CAPTURE", truth.line("FULLRES RECIPE proof") + " (camera " + physicalID + ", P635b)");
        if (truth.notReal(XiaomiSensorRow.device())) {
            final String why = "the proof still is not the sensor's full readout (" + truth.line("proof") + ")";
            mainHandler().post(() -> retryWithoutRecipe(mCaptureSession, why));
        } else if (truth.verdict == FullResTruth.Verdict.REAL) {
            FullResRecipeStore.proveActive(active);
        }
    }

    /**
     * P635b: the first frame of a picked row's session is not the sensor's full readout (FullResTruth: a picture in a part of the
     * buffer only, a doubled frame, no pedestal where the camera declares one): the row is not used as if it worked - the module
     * goes back to «Авто», the camera restarts with its normal session, and the message names the way to the full resolution.
     */
    private void rowNotReal(SensorModePick.Pick pick, FullResTruth.Result truth) {
        final String slot = com.scamera.app.settings.ModuleRegistry.active();
        boolean preset = false, failed = false;
        try {
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            preset = FullResRecipeStore.presetFor(String.valueOf(physicalID), c) != null;
            failed = preset && FullResRecipeStore.failedHere(slot, String.valueOf(physicalID), c);
            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putString(SensorModePick.prefKey(slot), "-1").apply();
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "sensor mode row: " + e);
        }
        Log.w("SCAM_CAPTURE", "camera " + physicalID + " sensor mode row " + pick.mode + " " + pick.size + " gives no full frame ("
                + truth.line("FULLRES ROW") + "): the module is back on Auto, the normal session opens (P635b)");
        final CameraCaptureSession session = mCaptureSession;
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        final String base = Lang.t("Режим сенсора ", "Sensor mode ") + pick.mode + " (" + pick.size + ")"
                + Lang.t(" отдаёт не полный кадр: в буфере только часть картинки. Модуль снова в «Авто».",
                " delivers no full frame: only a part of the buffer holds a picture. The module is back on Auto.");
        showError(base + (!preset ? ""
                : failed ? Lang.t(" Рецепт полного разрешения на этом объективе не запустился — причина в настройках объектива.",
                " The full-resolution recipe did not run on this lens: the reason is in the lens settings.")
                : Lang.t(" Для полного разрешения включите «Полное разрешение (рецепт)» в настройках объектива.",
                " For the full resolution turn on “Full resolution (recipe)” in the lens settings.")));
        mainHandler().post(this::restartCamera);
    }

    /** P635: the readers of a recipe session: the capture RAW (the stills'), the viewfinder's binned RAW, the companion. */
    private void recipeReaders(FullResRecipePlan plan) {
        final FullResRecipePlan.Output capture = plan.output(FullResRecipePlan.Stream.RAW_CAPTURE);
        final int images = Math.max(4, Math.min(10, plan.recipe.frames + 2));
        mImageReaderRaw = capture.usage != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ImageReader.newInstance(capture.width, capture.height, capture.format, images, capture.usage)
                : ImageReader.newInstance(capture.width, capture.height, capture.format, images);
        final FullResRecipePlan.Output view = plan.output(FullResRecipePlan.Stream.RAW_PREVIEW);
        if (view != null) {
            final ImageReader reader = ImageReader.newInstance(view.width, view.height, view.format, FullResRecipePlan.RAW_PREVIEW_IMAGES);
            reader.setOnImageAvailableListener(r -> {
                Image image;
                try { image = r.acquireNextImage(); } catch (IllegalStateException closed) { return; }
                if (image == null) return;
                if (!isCameraResumed || r != mImageReaderRawView) { image.close(); return; }
                // as the main RAW listener: routed by its timestamp while a shot is in flight, else the viewfinder's frame
                if (mNativeRawPslCapture || mScamRouted) mLiveRawRouter.image(image.getTimestamp(), image);
                else if (mZslCapturing || !mLiveRawSession) image.close();
                else mLiveMetadata.image(image.getTimestamp(), image);
            }, mBackgroundHandler);
            mImageReaderRawView = reader;
        }
        final FullResRecipePlan.Output companion = plan.output(FullResRecipePlan.Stream.COMPANION);
        if (companion != null) {
            final ImageReader reader = ImageReader.newInstance(companion.width, companion.height, companion.format, 2);
            reader.setOnImageAvailableListener(r -> { // its frames are not used: the stream only has to be in the request
                try {
                    final Image image = r.acquireLatestImage();
                    if (image != null) image.close();
                } catch (IllegalStateException closed) { }
            }, mBackgroundHandler);
            mRecipeCompanion = reader;
        }
    }

    /** P635: the surfaces of a recipe session, in the plan's order. */
    private List<Surface> recipeSurfaces() {
        final List<Surface> out = new ArrayList<>();
        if (mRecipePlan.ispStream() && surface != null) out.add(surface);
        if (mImageReaderRawView != null) out.add(mImageReaderRawView.getSurface());
        out.add(mImageReaderRaw.getSurface());
        if (mRecipeCompanion != null) out.add(mRecipeCompanion.getSurface());
        return out;
    }

    /** P635: one output of a recipe session: the capture RAW's vendor stream use case, the pixel modes of an annotated profile. */
    private void recipeOutput(OutputConfiguration config, Surface output) {
        final FullResRecipePlan plan = mRecipePlan;
        if (plan == null) return;
        final FullResRecipePlan.Stream stream = output == surface ? FullResRecipePlan.Stream.VIEWFINDER
                : mImageReaderRawView != null && output == mImageReaderRawView.getSurface() ? FullResRecipePlan.Stream.RAW_PREVIEW
                : mRecipeCompanion != null && output == mRecipeCompanion.getSurface() ? FullResRecipePlan.Stream.COMPANION
                : FullResRecipePlan.Stream.RAW_CAPTURE;
        final FullResRecipePlan.Output o = plan.output(stream);
        if (o == null) return;
        if (o.useCase != 0 && Build.VERSION.SDK_INT >= 33) {
            try {
                config.setStreamUseCase(o.useCase);
            } catch (RuntimeException e) {
                Log.w("SCAM_CAPTURE", "FULLRES RECIPE: stream use case 0x" + Long.toHexString(o.useCase) + " not set: " + e);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) for (int pixelMode : o.pixelModes) config.addSensorPixelModeUsed(pixelMode);
    }

    /** P635: the running profile failed for {@code why}: remembered for this lens (until a field changes), said in the log with its field set. */
    private void recipeFailed(String why) {
        final FullResRecipeStore.Active active = mRecipe;
        final FullResRecipePlan plan = mRecipePlan;
        if (active == null) return;
        FullResRecipeStore.failActive(active, why);
        Log.w("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + " failed (" + why + "): the normal session opens; the lens keeps this"
                + " until a field of the profile changes. " + (plan == null ? String.valueOf(active.recipe) : plan.describe(String.valueOf(physicalID))));
        mRecipe = null;
        mRecipePlan = null;
        mRecipeShot = null;
        FullResRecipeSession.session(null);
        mFullResSession = false;
    }

    /**
     * P635: a recipe session failed (a refused configuration, a camera error, no viewfinder frame, a shot without a still): the
     * profile is remembered as failed and the camera restarts with its normal session - the viewfinder comes back. False outside a
     * recipe session.
     */
    private boolean retryWithoutRecipe(@Nullable CameraCaptureSession session, String why) {
        if (mRecipe == null) return false;
        recipeFailed(why);
        if (session != null) try { session.close(); } catch (Exception ignored) { }
        showError(Lang.t("Полное разрешение (рецепт): камера отказала, обычный режим. Причина — в настройках объектива.",
                "Full resolution (recipe): refused by the camera, normal mode. The reason is in the lens settings."));
        mainHandler().post(this::restartCamera);
        return true;
    }

    /**
     * P635: the verdict of a recipe shot's first frame once the shot is complete - a doubled frame (FullResTruth: the binned
     * frame written twice over) was merged as the binned one: said on the screen, and the profile is remembered as failed (the
     * next session of the lens is the normal one).
     */
    private void recipeVerdict(FullResRecipeShot shot) {
        final FullResTruth.Result verdict = shot.verdict();
        if (verdict == null) return;
        // P635b: a real frame proves the profile for the lens and this firmware (no proof still at its next sessions)
        if (!shot.notReal()) {
            if (verdict.verdict == FullResTruth.Verdict.REAL) FullResRecipeStore.proveActive(mRecipe);
            return;
        }
        com.scamera.app.util.UiMessages.info(Lang.t("Рецепт полного разрешения не дал настоящего полного кадра: снимок сохранён обычным, если это возможно.",
                "The full-resolution recipe gave no real full frame: the photo is saved as a normal one where that is possible."));
        if (mRecipe != null) {
            recipeFailed("the stills are not the sensor's full readout (" + verdict.line() + ")");
            mainHandler().post(this::restartCamera);
        }
    }

    private boolean retryWithoutFullRes(@Nullable CameraCaptureSession session, String why) {
        // P598: a nubia remosaic-probe session: its setting failed, not the camera's FULLRES
        if (mFullResSession && NubiaFullRes.device() && NubiaRemosaicProbe.running(physicalID)) return retryNubiaProbe(session, why);
        if (!mFullResSession || !sFullResRefused.add(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": full resolution refused (" + why + "), binned RAW again");
        mFullResSession = false;
        PixelPro10.setFullRes(false);
        if (session != null) try { session.close(); } catch (Exception ignored) {}
        showError(Lang.t("Полное разрешение: камера отказала, обычный режим", "Full resolution: refused by the camera, normal mode"));
        final Handler main = new Handler(android.os.Looper.getMainLooper());
        main.post(this::restartCamera);
        return true;
    }

    /** P626: the running nubia maximum-resolution way is a per-shot one (NubiaMaxResPlan). */
    private boolean nubiaPerShot() {
        return mFullResSession && NubiaRemosaicProbe.perShot(physicalID);
    }

    /**
     * P626: the per-shot way's proof - {@link NubiaMaxResPlan#PROOF_FRAMES} still requests in MAXIMUM_RESOLUTION on the RAW stream
     * alone (the way's still keys, the HAL's AE), through the viewfinder's callback (the mode watch reads their results); their
     * frames go the ring's way to observeMosaic (the fill line, the binned-frame check, MosaicStream's verdict) and are closed
     * there. No verdict within {@link NubiaMaxResPlan#PROOF_TIMEOUT_MS}: the way fails «noproof», the next one runs.
     */
    private void sendNubiaMaxResProof(Object run) {
        final CameraCaptureSession session = mCaptureSession;
        final CameraDevice device = mCameraDevice;
        final ImageReader raw = mImageReaderRaw;
        if (session == null || device == null || raw == null || !nubiaPerShot() || NubiaRemosaicProbe.token() != run) return;
        try {
            final CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(raw.getSurface());
            VendorTagUtils.builderSessionApply(b, true, false, physicalID);
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, NubiaMaxResPlan.requestPixelMode(true, true, true));
            final List<CaptureRequest> burst = new ArrayList<>();
            for (int i = 0; i < NubiaMaxResPlan.PROOF_FRAMES; i++) burst.add(b.build());
            session.captureBurst(burst, mCaptureCallback, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", "nubia FULLRES camera " + physicalID + ": proof burst of " + NubiaMaxResPlan.PROOF_FRAMES
                    + " MAXIMUM_RESOLUTION requests on the RAW " + raw.getWidth() + "x" + raw.getHeight() + " alone (" + NubiaRemosaicProbe.describe() + ")");
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w("SCAM_CAPTURE", "nubia FULLRES camera " + physicalID + ": proof burst refused: " + e);
            retryWithoutFullRes(session, "nubia: the MAXIMUM_RESOLUTION test requests were refused (" + e.getMessage() + ")");
            return;
        }
        mainHandler().postDelayed(() -> {
            if (mFullResSession && NubiaRemosaicProbe.token() == run && NubiaRemosaicProbe.proofPending(physicalID))
                retryWithoutFullRes(mCaptureSession, NubiaRemosaicProbe.noProofWhy(NubiaMaxResPlan.PROOF_TIMEOUT_MS));
        }, NubiaMaxResPlan.PROOF_TIMEOUT_MS);
    }

    /**
     * P598: the setting of a nubia maximum-resolution FULLRES session (NubiaRemosaicProbe) failed for {@code why} - a plain Bayer
     * (the HAL's remosaic), another block, a refused session, a camera error, no frame: noted per camera and firmware; the camera
     * restarts with FULLRES on and the next setting while one is left (a short notice), else with FULLRES off and the one notice
     * that this firmware gives only the remosaic (the camera's 50 Мп dimmed from now on). Bounded by the settings: each fails once.
     */
    private boolean retryNubiaProbe(@Nullable CameraCaptureSession session, String why) {
        final NubiaRemosaicProbe.Outcome outcome = NubiaRemosaicProbe.failed(physicalID, why);
        if (outcome == null) return false;
        mFullResSession = false;
        if (!outcome.next) PixelPro10.setFullRes(false);
        if (session != null) try { session.close(); } catch (Exception ignored) {}
        if (outcome.next) showToast(outcome.message);
        else showError(outcome.message);
        mainHandler().post(this::restartCamera);
        return true;
    }

    /**
     * P230: a DCG route's session the camera refused (Xiaomi 15 Ultra: its operation mode 0x9203; X200 Ultra: the Telegram route),
     * or one that never gave a frame, runs again as a normal session: DCG goes off (the switch shows it), so the next session is
     * mode 0 without the DCG keys and the same refusal cannot come back by itself (one camera restart, no loop); a tap on «DCG»
     * tries again.
     */
    private boolean retryWithoutDcgRoute(@Nullable CameraCaptureSession session, String why) {
        final int mode = mDcgOperationMode;
        if (!mDcgRequestKeys || mCameraDevice == null) return false;
        mDcgOperationMode = 0;
        mDcgRequestKeys = false;
        SensorModeBand.sessionEnd();
        // P641: on the Xiaomi 17 Ultra the refused session's variant failed, the switch stays: the restarted camera takes the next
        // variant (or steps down when none is left - XiaomiDualGain.sessionStart). Each failure is remembered, so this ends.
        if (XiaomiDualGain.sessionFailed(why)) {
            if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
            mainHandler().post(this::restartCamera);
            return true;
        }
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        final String what = mode != 0 ? "session mode 0x" + Integer.toHexString(mode) : "DCG route";
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": DCG session (" + what + ") refused (" + why + "): DCG off, a normal session");
        showError(Lang.t("DCG: камера " + physicalID + " не приняла " + (mode != 0 ? "режим сессии 0x" + Integer.toHexString(mode)
                        : "режим DCG") + ", обычный режим",
                "DCG: camera " + physicalID + " refused " + what + ", normal mode"));
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        // one restart (the DCG button and the camera's state follow the switch); DCG is off, so it does not come back
        mainHandler().post(this::restartCamera);
        return true;
    }

    /** P100: a session refused with the CROPPED_RAW RAW stream is configured again without it (this camera, this run). */
    /** P211: a session the camera refused with the module's vendor operation mode runs again in mode 0 (this camera, this run). */
    private boolean retryWithoutVendorMode(@Nullable CameraCaptureSession session) {
        if (mVendorOperationMode == 0 || mCameraDevice == null || !sVendorModeRefused.add(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": session refused with operation mode " + mVendorOperationMode + ", again in mode 0");
        showError(Lang.t("Камера " + physicalID + ": режим сессии " + mVendorOperationMode + " не принят, обычный режим",
                "Camera " + physicalID + ": session mode " + mVendorOperationMode + " refused, normal mode"));
        mVendorOperationMode = 0;
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        final Handler handler = mBackgroundHandler;
        if (handler != null) handler.post(() -> { if (isCameraResumed && mCameraDevice != null) startSession(false); });
        return true;
    }

    private boolean retryWithoutCroppedRaw(CameraCaptureSession session) {
        if (!mCroppedRawSession || mCameraDevice == null || !sCroppedRawRefused.add(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": session refused with CROPPED_RAW, again without it");
        mCroppedRawSession = false;
        session.close();
        startSession(false);
        return true;
    }

    private static final java.util.Set<String> sCroppedRawRefused = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** P108: Pixel 10 Pro / Pro XL full-resolution session (RAW at the max-resolution size, MAXIMUM_RESOLUTION pixel mode). */
    private volatile boolean mFullResSession;
    /** P635: the full-resolution recipe this session runs (null: none), its plan, its companion reader and its shot in flight. */
    /** P635c: the floor of a recipe session's viewfinder frames (RaisedPedestal), measured four times a second. */
    private final com.scamera.app.processing.RaisedPedestal mRecipeViewFloor =
            new com.scamera.app.processing.RaisedPedestal();
    private int mRecipeViewFloorSession = Integer.MIN_VALUE;
    private long mRecipeViewFloorAtNs, mRecipeViewFloorLogNs;
    private float mRecipeViewBlackLogged = Float.NaN;
    /** P635c: the autofocus of a recipe session, a log line a second (RecipeAfTrace). */
    private final RecipeAfTrace mRecipeAfTrace = new RecipeAfTrace();
    private int mRecipeAfSession = Integer.MIN_VALUE;

    private boolean recipeViewFloorOn() {
        return mRecipePlan != null && PreferenceKeys.scamDevSwitch("recipe_vf_floor", true);
    }

    /**
     * P635c: the levels of a viewfinder frame for everything that reads its samples (the developed RAW viewfinder, the motion
     * level, the RAW meters): the session's (RawLevels), and in a recipe session with the black its frames show themselves when
     * they sit on a pedestal far above the declared one (RaisedPedestal: the Xiaomi 17 Ultra's binned stream beside the
     * full-size one, ~560 DN for a declared 64 - the magenta viewfinder of the owner's log 2026-11-12). The photo's levels are
     * its own frames' (Parameters), not these.
     */
    private com.scamera.app.processing.RawLevels.Levels liveLevels(CameraCharacteristics c, CaptureResult result) {
        final com.scamera.app.processing.RawLevels.Levels levels =
                com.scamera.app.processing.RawLevels.current(c, result);
        if (!recipeViewFloorOn()) return levels;
        final float[] black = mRecipeViewFloor.black(mSessionGeneration.get(), levels.black, levels.white);
        return black == levels.black ? levels : levels.withBlack(black, "black = the viewfinder frames' own floor");
    }

    /**
     * P635c: a recipe session's viewfinder frame joins the session's floor (four times a second; the first frame at once, before
     * anything develops it). One log line a session - the frame's block means by CFA site, the declared levels, the black used,
     * the white balance gains and the exposure - and again when the black used moved by 2 % of the range.
     */
    private void recipeViewFloor(Image img, CaptureResult result) {
        if (!recipeViewFloorOn() || img == null || img.getFormat() != ImageFormat.RAW_SENSOR || mPayloadBad || !mPreviewLayouts.isEmpty()) return;
        final int generation = mSessionGeneration.get();
        final long now = android.os.SystemClock.elapsedRealtimeNanos();
        final boolean first = generation != mRecipeViewFloorSession;
        if (!first && now - mRecipeViewFloorAtNs < 250_000_000L) return;
        mRecipeViewFloorSession = generation;
        mRecipeViewFloorAtNs = now;
        try {
            final Image.Plane plane = img.getPlanes()[0];
            if (plane.getPixelStride() != 2) return;
            final java.nio.ByteBuffer samples = plane.getBuffer().duplicate();
            samples.position(0);
            final com.scamera.app.processing.RaisedPedestal.Stats stats =
                    com.scamera.app.processing.RaisedPedestal.measure(samples.order(java.nio.ByteOrder.nativeOrder())
                            .asShortBuffer(), img.getWidth(), img.getHeight(), plane.getRowStride() / 2);
            mRecipeViewFloor.observe(generation, stats);
            if (stats == null) return;
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            if (c == null) return;
            final com.scamera.app.processing.RawLevels.Levels declared =
                    com.scamera.app.processing.RawLevels.current(c, result);
            final float used = mRecipeViewFloor.black(generation, declared.black, declared.white)[0];
            if (first) { mRecipeViewBlackLogged = Float.NaN; mRecipeViewFloorLogNs = 0; }
            final boolean moved = Float.isNaN(mRecipeViewBlackLogged) || Math.abs(used - mRecipeViewBlackLogged) > 0.02f * declared.white;
            if (!moved || !first && now - mRecipeViewFloorLogNs < 2_000_000_000L) return;
            mRecipeViewBlackLogged = used;
            mRecipeViewFloorLogNs = now;
            final android.hardware.camera2.params.RggbChannelVector wb = result == null ? null : result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            final Long exposure = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
            Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + " viewfinder levels: frame " + img.getWidth() + "x" + img.getHeight()
                    + " block means by CFA site " + stats + " (" + stats.blocks + " blocks); declared black " + declared.meanBlack() + " white "
                    + declared.white + " -> black used " + used + (used > declared.meanBlack() + 1f ? " (the frames' own floor: a pedestal above the declared black)"
                    : " (declared)") + "; WB gains " + (wb == null ? "none" : String.format(java.util.Locale.ROOT, "%.3f/%.3f/%.3f", wb.getRed(),
                    (wb.getGreenEven() + wb.getGreenOdd()) * 0.5f, wb.getBlue())) + "; " + (exposure == null ? "?" : String.format(java.util.Locale.ROOT,
                    "%.3f", exposure / 1e6)) + " ms ISO " + (iso == null ? "?" : iso) + " (P635c)");
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "FULLRES RECIPE viewfinder levels: " + e);
        }
    }

    /** P635c: one total result of a recipe session's repeating request for the AF line (RecipeAfTrace). */
    private void recipeAfTrace(CaptureRequest request, TotalCaptureResult result) {
        final FullResRecipePlan plan = mRecipePlan;
        if (plan == null || result == null) return;
        try {
            final int generation = mSessionGeneration.get();
            if (generation != mRecipeAfSession) { mRecipeAfSession = generation; mRecipeAfTrace.reset(); }
            boolean regions = false;
            final MeteringRectangle[] af = request == null ? null : request.get(CaptureRequest.CONTROL_AF_REGIONS);
            if (af != null) for (MeteringRectangle r : af) regions |= r != null && r.getMeteringWeight() > 0 && r.getWidth() > 0;
            final String line = mRecipeAfTrace.frame(android.os.SystemClock.elapsedRealtime(), result.get(CaptureResult.CONTROL_AF_MODE),
                    result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.LENS_STATE), result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    request == null ? null : request.get(CaptureRequest.CONTROL_AF_TRIGGER), regions);
            if (line != null) Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + " AF: " + line + "; viewfinder request tags "
                    + (plan.requestTags(false).isEmpty() ? "none" : "on") + ", the result's: " + FullResRecipeTags.read(result, plan.recipe.tags)
                    + "; viewfinder pairing: " + mLiveMetadata.stats() + " (P635c, P653)");
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "FULLRES RECIPE AF: " + e);
        }
    }

    /**
     * P635c: the viewfinder's focus on a recipe still (its template alone, nothing copied from the preview): manual focus - AF
     * off and the hand-set distance, without which the template's own would move the lens for the shot.
     */
    private void recipeFocus(CaptureRequest.Builder still) {
        final CaptureRequest.Builder preview = mPreviewRequestBuilder;
        if (preview == null || still == null) return;
        final Integer af = preview.get(CaptureRequest.CONTROL_AF_MODE);
        final Float distance = preview.get(CaptureRequest.LENS_FOCUS_DISTANCE);
        if (af == null || af != CaptureRequest.CONTROL_AF_MODE_OFF || distance == null) return;
        still.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        still.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);
    }

    private volatile FullResRecipeStore.Active mRecipe;
    private volatile FullResRecipePlan mRecipePlan;
    private volatile ImageReader mRecipeCompanion;

    // ---------------------------------------------------------------- P641d: DCG + LOFIC as a pair of frames (LoficPair)

    /** The reader of the overflow stream (RAW10, vendor use case 524554) of a «lofic-pair» session; null in any other. */
    private volatile ImageReader mLoficReader;
    /** The newest overflow frames, by sensor timestamp (copies; 8 x 15 MB at 12 MP). */
    private final LoficPair.Ring mLoficRing = new LoficPair.Ring(8);
    /** A checked main frame's blocks waiting for the overflow frame of the same timestamp, and its levels. */
    private LoficPair.Stats mLoficMainStats;
    private long mLoficMainTs;
    private float mLoficMainBlack;
    private int mLoficMainWhite;
    /** Frames of one exposure share the sensor timestamp; half a millisecond covers a HAL that stamps the streams apart. */
    private static final long LOFIC_PAIR_NS = 500_000L;

    private void closeLoficReader() {
        closeLoficReader(mLoficReader);
    }

    /**
     * P680: the overflow reader {@code r} closes (the one closeCamera took when it scheduled the close); its field and the pairs'
     * frames go only while it is still the session's (an open meanwhile may have made its own).
     */
    private void closeLoficReader(@Nullable ImageReader r) {
        final boolean current = mLoficReader == r;
        if (current) mLoficReader = null;
        if (r != null) try { r.close(); } catch (RuntimeException ignored) { }
        if (current) synchronized (mLoficRing) {
            mLoficRing.clear();
            mLoficMainStats = null;
        }
    }

    /** The overflow stream's reader at the main RAW's size; false when it cannot be made here (the rung fails at once). */
    private boolean openLoficReader() {
        closeLoficReader();
        final ImageReader raw = mImageReaderRaw;
        if (Build.VERSION.SDK_INT < 33 || raw == null || mBackgroundHandler == null) return false;
        try {
            final ImageReader reader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(), ImageFormat.RAW10, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireNextImage();
                    if (img == null) return;
                    final Image.Plane plane = img.getPlanes()[0];
                    final long ts = img.getTimestamp();
                    synchronized (mLoficRing) {
                        mLoficRing.store(ts, plane.getBuffer(), img.getWidth(), img.getHeight(), plane.getRowStride());
                        loficProof(ts);
                    }
                } catch (RuntimeException e) {
                    Log.w("SCAM_CAPTURE", "XIAOMI DCG+LOFIC: overflow frame not read: " + e);
                } finally {
                    if (img != null) img.close();
                }
            }, mBackgroundHandler);
            mLoficReader = reader;
            Log.i("SCAM_CAPTURE", "XIAOMI DCG+LOFIC camera " + physicalID + ": overflow stream " + raw.getWidth() + "x" + raw.getHeight()
                    + " RAW10, use case 0x" + Long.toHexString(LoficPair.USE_CASE) + ", in the repeating request and the stills; its frames"
                    + " are kept by timestamp (8 newest) and proven against the main frames before any is used (P641d)");
            return true;
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI DCG+LOFIC camera " + physicalID + ": no reader for the overflow stream: " + e);
            return false;
        }
    }

    /** A checked main frame of a pair session: its blocks wait for (or meet) the overflow frame of its timestamp. */
    private void loficMainFrame(Image img, @Nullable com.scamera.app.processing.RawPayloadCheck.Layout layout) {
        if (mLoficReader == null || !XiaomiDualGain.pairWanted() || img.getFormat() != ImageFormat.RAW_SENSOR
                || com.scamera.app.processing.RawLevels.knownBits() < 12) return;
        try {
            final java.nio.ByteBuffer buffer = img.getPlanes()[0].getBuffer();
            final LoficPair.Samples samples = layout == null ? LoficPair.plain16(buffer, img.getPlanes()[0].getRowStride(), 0)
                    : LoficPair.packed(buffer, layout.packedBits(), layout.stride, 0);
            final com.scamera.app.processing.RawLevels.Levels levels =
                    com.scamera.app.processing.RawLevels.current(mCameraCharacteristics, null);
            float black = 0f;
            for (float b : levels.black) black += b / levels.black.length;
            synchronized (mLoficRing) {
                mLoficMainStats = LoficPair.stats(samples, img.getWidth(), img.getHeight());
                mLoficMainTs = img.getTimestamp();
                mLoficMainBlack = black;
                mLoficMainWhite = levels.white;
                loficProof(mLoficMainTs);
            }
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI DCG+LOFIC: main frame not measured: " + e);
        }
    }

    /** Under mLoficRing: the waiting main frame and the overflow frame of {@code timestamp} are both here: the pair is measured. */
    private void loficProof(long timestamp) {
        final LoficPair.Stats main = mLoficMainStats;
        if (main == null || Math.abs(mLoficMainTs - timestamp) > LOFIC_PAIR_NS) return;
        final LoficPair.Frame f = mLoficRing.find(mLoficMainTs, LOFIC_PAIR_NS);
        if (f == null) return;
        mLoficMainStats = null;
        XiaomiDualGain.onPair(LoficPair.measure(main, mLoficMainBlack, mLoficMainWhite,
                LoficPair.stats(LoficPair.packed(f.data, 10, f.stride, 0), f.width, f.height), 1023), mLoficMainTs);
    }

    /**
     * A shot of a proven pair session: the newest N frames of the ring take the overflow frames of their timestamps along (a
     * copy of the packed frame; two at most - the merge takes HybridPlan.bentoFrames of them). The ring frames are copied later
     * (deferred), so each pair is measured where the burst is assembled, at the shot's levels and against the session's ratio
     * (ScamProcessor.addOverflowFrames): one that is no pair there is left out.
     */
    private void attachLoficPartners(List<ImageFrame> normals) {
        final double sessionRatio = XiaomiDualGain.pairRatio();
        if (mLoficReader == null || normals == null || normals.isEmpty()) return;
        if (!(sessionRatio > 0)) {
            if (XiaomiDualGain.pairWanted()) Log.i("SCAM_HDR", "DCG+LOFIC: the overflow pair is not proven in this session yet: the shot is"
                    + " the DCG frames alone (P641d)");
            return;
        }
        final List<ImageFrame> newest = new ArrayList<>(normals);
        newest.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        int made = 0, missing = 0;
        final String rung = "lofic-pair";
        synchronized (mLoficRing) {
            for (ImageFrame f : newest) {
                if (made >= 2) break;
                if (f == null || f.width <= 0 || f.height <= 0) continue;
                final LoficPair.Frame o = mLoficRing.find(f.timestamp, LOFIC_PAIR_NS);
                if (o == null || o.width != f.width) { missing++; continue; }
                // the shot's 16:9 cut of the main frame (Wide169) is the overflow frame's too
                final int first = o.height == f.height ? 0
                        : com.scamera.app.processing.Wide169.rows(o.width, o.height) == f.height
                        ? com.scamera.app.processing.Wide169.firstRow(o.width, o.height) : -1;
                if (first < 0) { missing++; continue; }
                try {
                    f.overflow = LoficPair.partner(o, f.width, f.height, first, sessionRatio, rung);
                    made++;
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w("SCAM_HDR", "DCG+LOFIC: frame " + f.number + "'s overflow frame not taken: " + e);
                }
            }
        }
        Log.i("SCAM_HDR", "DCG+LOFIC shot: " + made + " of the " + normals.size() + " ring frames take their overflow frame along (rung "
                + rung + ", session ratio " + String.format(Locale.ROOT, "%.3f", sessionRatio) + ", " + missing + " without a frame of their"
                + " timestamp; overflow ring " + mLoficRing.describe() + "; P641d)");
    }
    // ---------------------------------------------------------------- P679: the Xiaomi 17 Ultra's LOFIC pair (XiaomiLofic)

    /** The recipe's readers of a LOFIC session (null in any other): the dummy RAW, the video stream, the JPEG, RAW A and RAW B. */
    private volatile ImageReader mLoficDummy, mLoficVideo, mLoficJpeg, mLoficRawA, mLoficRawB;
    /** The session's newest pairs (copies of the used bytes). */
    private final XiaomiLofic.Ring mLoficPairs = new XiaomiLofic.Ring(XiaomiLofic.PAIRS);
    /** Images measured exactly (the used length) at the session's start; later ones by the quick test. */
    private int mLoficExact;
    /** The last kept timestamp: at 60 fps every second frame is kept (the copies' cost). */
    private long mLoficKeptTs;

    private static void closeQuietly(@Nullable ImageReader r) {
        if (r != null) try { r.close(); } catch (RuntimeException ignored) { }
    }

    /** The LOFIC readers and pairs go (the session ended, or the next one is not a LOFIC one). */
    private void closeLoficSession() {
        closeLoficSession(loficSessionReaders());
    }

    /** P680: the LOFIC session's readers now (closeCamera takes them when it schedules the close). */
    private ImageReader[] loficSessionReaders() {
        return new ImageReader[]{mLoficDummy, mLoficVideo, mLoficJpeg, mLoficRawA, mLoficRawB};
    }

    /**
     * P680: the LOFIC readers {@code readers} ({@link #loficSessionReaders}) close; a field still holding its reader is cleared,
     * and the pairs go unless a new LOFIC session has its readers already.
     */
    private void closeLoficSession(ImageReader[] readers) {
        if (mLoficDummy == readers[0]) mLoficDummy = null;
        if (mLoficVideo == readers[1]) mLoficVideo = null;
        if (mLoficJpeg == readers[2]) mLoficJpeg = null;
        if (mLoficRawA == readers[3]) mLoficRawA = null;
        if (mLoficRawB == readers[4]) mLoficRawB = null;
        for (ImageReader r : readers) closeQuietly(r);
        if (mLoficRawA == null) mLoficPairs.clear();
    }

    /** The recipe's readers for {@code s}; false when one cannot be made here. */
    private boolean openLoficSession(XiaomiLofic.Setup s) {
        closeLoficSession();
        if (Build.VERSION.SDK_INT < 29 || mBackgroundHandler == null) return false;
        try {
            mLoficDummy = ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 2);
            mLoficVideo = ImageReader.newInstance(s.videoW, s.videoH, ImageFormat.PRIVATE, 3,
                    android.hardware.HardwareBuffer.USAGE_VIDEO_ENCODE);
            mLoficJpeg = ImageReader.newInstance(s.jpegW, s.jpegH, ImageFormat.JPEG, 1);
            mLoficRawA = ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 4);
            mLoficRawB = ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 4);
            mLoficVideo.setOnImageAvailableListener(r -> {
                try { final Image i = r.acquireLatestImage(); if (i != null) i.close(); } catch (RuntimeException ignored) { }
            }, mBackgroundHandler);
            mLoficRawA.setOnImageAvailableListener(this::onLoficRaw, mBackgroundHandler);
            mLoficRawB.setOnImageAvailableListener(this::onLoficRaw, mBackgroundHandler);
            mLoficPairs.capacity(XiaomiLofic.pairsWanted() + 2);
            mLoficExact = 0;
            mLoficKeptTs = 0;
            return true;
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI LOFIC: readers not made: " + e + " (P679)");
            closeLoficSession();
            return false;
        }
    }

    /** The six outputs of the recipe in its order (XiaomiLofic.outputs). */
    private List<Surface> loficSurfaces() {
        return Arrays.asList(mLoficDummy.getSurface(), surface, mLoficVideo.getSurface(), mLoficJpeg.getSurface(),
                mLoficRawA.getSurface(), mLoficRawB.getSurface());
    }

    /**
     * An image of RAW A or RAW B: classified by its used length (exactly for the session's first images, which also prove the
     * setup's rows; then by the quick test), its used bytes copied into the ring of pairs, the reader's buffer given back at once.
     */
    private void onLoficRaw(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireNextImage();
            final XiaomiLofic.Setup s = XiaomiLofic.setup();
            if (img == null || s == null || mClosedForProcessing) return;
            final java.nio.ByteBuffer b = img.getPlanes()[0].getBuffer();
            final long ts = img.getTimestamp();
            final boolean exact = mLoficExact < 8 || !XiaomiLofic.proven();
            final XiaomiLofic.Stream stream;
            final int used;
            if (exact) {
                mLoficExact++;
                used = XiaomiLofic.usedBytes(b);
                stream = XiaomiLofic.classify(used);
                if (mLoficExact <= 4) Log.i("SCAM_CAPTURE", "XIAOMI LOFIC " + s.label + ": " + (reader == mLoficRawA ? "RAW A" : "RAW B")
                        + " image " + img.getWidth() + "x" + img.getHeight() + " stride " + img.getPlanes()[0].getRowStride()
                        + ", used " + used + " B -> " + stream + " " + XiaomiLofic.rows(used, stream) + " rows (P679)");
            } else {
                stream = XiaomiLofic.quick(b, s.rows);
                used = stream == XiaomiLofic.Stream.LONG ? s.longBytes() : stream == XiaomiLofic.Stream.SHORT ? s.shortBytes() : 0;
            }
            // at 60 fps every second exposure is kept (both of its images: the second one finds the pair begun)
            final long gap = s.fps >= 50 ? 25_000_000L : 0L;
            if (!exact && gap > 0 && !mLoficPairs.has(ts) && ts - mLoficKeptTs < gap) return;
            final XiaomiLofic.Pair done = mLoficPairs.store(stream, ts, b, used);
            if (done == null && stream != XiaomiLofic.Stream.NONE) mLoficKeptTs = ts;
            if (done != null && exact && XiaomiLofic.onPair(done.longImage.rows, done.shortImage.rows, ts) == XiaomiLofic.Verdict.FAILED)
                loficNextSetup();
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI LOFIC: image not read: " + e + " (P679)");
        } finally {
            if (img != null) img.close();
        }
    }

    /** A LOFIC setup failed its checks (XiaomiLofic logged which): the session opens again with the next setup or the normal one. */
    private void loficNextSetup() {
        final Handler handler = mBackgroundHandler;
        if (handler == null) return;
        final int generation = mSessionGeneration.get();
        final String why = XiaomiLofic.lastFailure();
        handler.post(() -> {
            if (!isCameraResumed || generation != mSessionGeneration.get() || mCaptureSession == null || !XiaomiLofic.sessionActive()) return;
            XiaomiLofic.sessionFailed(why);
            mDcgOperationMode = 0;
            mDcgRequestKeys = false;
            if (shotInFlight()) { mPendingPayloadRestart = true; return; }
            mCaptureSession.close();
            startSession(false);
        });
    }

    /** P679: a LOFIC session refused, or failed while being built: its setup failed, the camera reopens with the next one. */
    private boolean retryWithoutLofic(@Nullable CameraCaptureSession session, String why) {
        if (!XiaomiLofic.sessionActive() || mCameraDevice == null) return false;
        mDcgOperationMode = 0;
        mDcgRequestKeys = false;
        XiaomiLofic.sessionFailed(why);
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        mainHandler().post(this::restartCamera);
        return true;
    }

    /**
     * A shot of a LOFIC session: the newest pairs before the press (ExposureCount 2: the Short read usable) as the shot's N
     * frames, each Long frame unpacked (14 bit) with its Short read along (ImageFrame.lofic, merged by ScamProcessor before the
     * burst merge). Without a pair of ExposureCount 2 the Long frames alone. Throws while the setup is not proven or the ring is
     * empty (the shot is refused, as P316's empty ring).
     */
    private List<ImageFrame> drainLoficPairs(int requested) {
        final XiaomiLofic.Setup s = XiaomiLofic.setup();
        if (s == null || !XiaomiLofic.proven()) {
            Log.w("SCAM_CAPTURE", "XIAOMI LOFIC shot refused: the setup is not proven yet (" + XiaomiLofic.lastFailure() + ") (P679)");
            throw new IllegalStateException(Lang.t("LOFIC: камера ещё готовится. Повторите снимок.", "LOFIC: the camera is still getting ready. Take the shot again."));
        }
        final int want = Math.max(1, Math.min(requested > 0 ? requested : XiaomiLofic.PAIRS, XiaomiLofic.pairsWanted()));
        mLoficPairs.freeze();
        final String before = mLoficPairs.describe();
        final boolean usable = mLoficPairs.usableCount() > 0;
        final List<XiaomiLofic.Pair> pairs = usable ? mLoficPairs.take(want, scamZslShutterTimestamp)
                : mLoficPairs.takeLongOnly(want, scamZslShutterTimestamp);
        Log.i("SCAM_HDR", "XIAOMI LOFIC shot (" + s.label + "): " + pairs.size() + " of " + want + " pairs wanted"
                + (usable ? " with ExposureCount 2" : " - no pair with ExposureCount 2: the Long frames ALONE") + "; ring " + before
                + "; cutoff " + scamZslShutterTimestamp + " (P679)");
        if (pairs.isEmpty())
            throw new IllegalStateException(Lang.t("LOFIC: нет кадров. Повторите снимок.", "LOFIC: no frames yet. Take the shot again."));
        Allocator.binning = false; // the Short read has the Long frame's grid
        final List<ImageFrame> out = new ArrayList<>();
        for (XiaomiLofic.Pair p : pairs) {
            final XiaomiLofic.Image l = p.longImage;
            final ImageFrame f = new ImageFrame(l.data, com.scamera.app.util.RawUnpack.RAW14, XiaomiLofic.RAW_W, XiaomiLofic.LONG_ROW, 0,
                    l.rows * XiaomiLofic.LONG_ROW);
            f.timestamp = l.timestamp;
            f.fromZsl = true;
            f.sourceFormat = ImageFormat.RAW_SENSOR;
            f.setCaptureMetadata(p.result);
            f.width = XiaomiLofic.RAW_W;
            f.height = l.rows;
            if (usable && p.shortImage != null) {
                f.lofic = new ImageFrame.Lofic();
                f.lofic.shortPacked = p.shortImage.data;
                f.lofic.rows = p.shortImage.rows;
            }
            exposureByFrame.put(f.timestamp, f.measuredExposure > 0 && f.measuredIso > 0 ? f.measuredExposure / 1e9 * f.measuredIso : 1.0);
            out.add(f);
            Log.i("SCAM_HDR", "XIAOMI LOFIC pair " + l.timestamp + ": Long " + l.rows + " rows, Short "
                    + (p.shortImage == null ? "none" : p.shortImage.rows + " rows") + ", ExposureCount " + p.exposureCount + ", exposure "
                    + f.measuredExposure + " ns ISO " + f.measuredIso + " (P679)");
        }
        out.sort(java.util.Comparator.comparingLong(f -> f.timestamp));
        final CaptureResult newest = pairs.get(0).result; // the newest pair
        mNativeZslBase = newest instanceof TotalCaptureResult ? (TotalCaptureResult) newest : null;
        return out;
    }

    /** The shot taking frames now is a LOFIC one (its frames carry the Short read). */
    private volatile boolean mLoficShot;
    /** The viewfinder runs in a LOFIC setup's shape (set back when a normal session follows). */
    private volatile boolean mLoficAspect;

    private volatile FullResRecipeShot mRecipeShot;
    /** P635b: the proof still of a recipe session whose profile is not proven on this firmware: 0 none, 1 wanted, 2 sent. */
    private volatile int mRecipeProof;
    static final long RECIPE_PROOF_TIMEOUT_MS = 4000;

    /** P626 / P635: the session's capture RAW is targeted by the stills only (a nubia per-shot way, a full-resolution recipe). */
    private boolean perShot() {
        return nubiaPerShot() || mRecipePlan != null;
    }
    /** P108: cameras whose full-resolution session was refused (this run): they run binned. */
    private static final java.util.Set<String> sFullResRefused = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean mCroppedRawSession;
    private android.graphics.Rect mLastRawCrop;

    private boolean retryWithoutLiveRaw(CameraCaptureSession session) {
        if (VivoPd2454NativeRaw.active(physicalID)) return false;
        if (!mLiveRawSession || mLiveRawRejected || mCameraDevice == null || isZslMode()) return false;
        mLiveRawRejected = true;
        mLiveRawSession = false;
        LiveRawFrame.setEnabled(false);
        mLiveRawRouter.clear();
        mLiveMetadata.clear();
        session.close();
        showError(Lang.t("RAW-превью недоступно с текущими потоками. Переключаемся на обычный видоискатель.", "RAW preview is not available with the current streams. Switching to the normal viewfinder."));
        startSession(false);
        return true;
    }

    /** The session's streams for the route this camera runs (the ISP viewfinder, the RAW reader, the extra readers). */
    @NotNull
    private List<Surface> sessionOutputs(boolean isBurstSession) {
        if (mRecipePlan != null) return recipeSurfaces(); // P635
        final Surface raw = mImageReaderRaw.getSurface();
        if (stillOnlyFullRes() && mImageReaderRawView != null) return fullSizeRecipe() && mRecipeCompanion != null // P684
                ? Arrays.asList(mImageReaderRawView.getSurface(), mRecipeCompanion.getSurface(), raw)
                : Arrays.asList(mImageReaderRawView.getSurface(), raw);
        // P609: an OPPO-family FULLRES row tried with the viewfinder's ISP stream in its session (fake off / on), as the stock camera
        if (nativeVivoFullRes() && oppoIspStream() > 0 && surface != null) return Arrays.asList(surface, raw);
        if (nativeVivoFullRes() || VivoPd2454NativeRaw.active(physicalID)) return Collections.singletonList(raw);
        if (mVivoSdkRawSession != null) return Arrays.asList(surface, raw, mVivoSdkJpeg.getSurface());
        if (mIspSurfaceOff) return Collections.singletonList(raw); // P266
        if (!recordingVideo) return Arrays.asList(surface, raw); // the viewfinder and the RAW stream
        // a recording: the viewfinder and the recorder (no RAW)
        setUpMediaRecorder();
        final Surface recorder = videoRecorder.getSurface();
        mPreviewRequestBuilder.addTarget(recorder);
        return Arrays.asList(surface, recorder);
    }

    /**
     * A new viewfinder request for the session (TEMPLATE_RECORD while recording, else TEMPLATE_PREVIEW) with its targets, its
     * default AF / AE regions remembered for the touch focus, and the viewfinder-only keys (antibanding, stabilisation, hidden
     * features, zoom, faces, the light ISP); the ring of the old session is emptied first.
     */
    private void newPreviewRequest() throws CameraAccessException {
        mPreviewRequestBuilder = null;
        mIspLightModes = null;
        final XiaomiLofic.Setup loficSetup = mLoficRawA != null ? XiaomiLofic.setup() : null; // P679: the recipe's template
        final CaptureRequest.Builder b = mCameraDevice.createCaptureRequest(
                recordingVideo || loficSetup != null ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
        mPreviewRequestBuilder = b;
        Antibanding.apply(b, mCameraCharacteristics); // P411
        // P223: without the ISP preview (FULLRES / ISZ, the RAW viewfinder on) the repeating request carries the RAW stream only
        final boolean ispOff = mIspPreviewOff && mLiveRawSession && mVivoSdkRawSession == null && !recordingVideo;
        mRepeatingIsp = !nativeVivoFullRes() && !VivoPd2454NativeRaw.active(physicalID) && !ispOff
                && (mRecipePlan == null || mRecipePlan.ispStream()); // P635: a recipe's session may have no ISP stream at all
        if (mRepeatingIsp) b.addTarget(surface);
        if (ispOff) Log.i("SCAM_CAPTURE", "camera " + physicalID + ": ISP preview off (P223), the repeating request has the RAW stream only");
        if (loficSetup != null) {
            // P679: the repeating request of the recipe: the viewfinder, the video stream, RAW A and RAW B (the dummy RAW and the JPEG
            // are configured only), CONTROL_MODE_AUTO, AE on, continuous video AF, the exact AE range and the vendor keys
            b.addTarget(mLoficVideo.getSurface());
            b.addTarget(mLoficRawA.getSurface());
            b.addTarget(mLoficRawB.getSurface());
            rememberDefaultRegions(b);
            mPreviewAFMode = CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            mPreviewAEMode = CONTROL_AE_MODE_ON;
            b.set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            Log.i("SCAM_CAPTURE", "XIAOMI LOFIC camera " + physicalID + " repeating request (TEMPLATE_RECORD): "
                    + XiaomiLofic.apply(b, loficSetup, false) + " (P679)");
            return;
        }
        if (mVivoSdkRawSession != null) {
            if (nativeVivoFullRes()) b.addTarget(stillOnlyFullRes() && mImageReaderRawView != null
                    ? mImageReaderRawView.getSurface() : mImageReaderRaw.getSurface());
            // P684: the stock full-size recipe's viewfinder repeats its YUV companion too
            if (fullSizeRecipe() && mRecipeCompanion != null) b.addTarget(mRecipeCompanion.getSurface());
            // P609: the «ISP stream on» variant repeats the viewfinder's ISP stream too (fake off: in the session, in no request)
            if (nativeVivoFullRes() && oppoIspStream() == 2 && surface != null) b.addTarget(surface);
            clearZslPreviewFrames();
            rememberDefaultRegions(b);
            mPreviewAFMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            mPreviewAEMode = CONTROL_AE_MODE_ON;
            applyVivoSdkPreview(b);
            return;
        }
        synchronized (mRingLock) {
            mHexZslResults.clear();
            for (RingFrame frame; (frame = mZslRingBuffer.pollFirst()) != null; ) frame.close();
        }
        // frames still queued in the RAW reader would leak into the next non-ZSL capture's FRAME_QUEUE
        drainReader(mImageReaderRaw);
        // P635: a recipe's viewfinder RAW is the camera's own binned stream (the capture RAW is the stills' alone)
        if (mRecipePlan != null && mLiveRawSession && mImageReaderRawView != null) b.addTarget(mImageReaderRawView.getSurface());
        // P653: the profile's «YUV на видоискателе»: the companion on every request too (its frames dropped by its reader; the
        // request has one result, so the RAW viewfinder's pairing sees nothing of it)
        final ImageReader recipeViewCompanion = mRecipeCompanion;
        if (mRecipePlan != null && recipeViewCompanion != null && mRecipePlan.targets(false).contains(FullResRecipePlan.Stream.COMPANION))
            b.addTarget(recipeViewCompanion.getSurface());
        // the ZSL ring and the RAW viewfinder take the RAW stream in every frame (P626: a nubia per-shot way in its stills only)
        if ((isZslMode() || mLiveRawSession) && NubiaMaxResPlan.rawInRepeating(perShot())) b.addTarget(mImageReaderRaw.getSurface());
        mDefaultAfRegions = b.get(CONTROL_AF_REGIONS);
        mPreviewMeteringAF = mDefaultAfRegions;
        mPreviewAFMode = recordingVideo ? CONTROL_AF_MODE_CONTINUOUS_VIDEO : PreferenceKeys.getAfMode();
        if (recordingVideo) b.set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        applyVideoStabilization(b);
        mDefaultAeRegions = b.get(CONTROL_AE_REGIONS);
        mPreviewMeteringAE = mDefaultAeRegions;
        // P103: zoom override and the low-light boost of the viewfinder, where the camera lists them
        HiddenFeatures.applyPreview(b, mCameraCharacteristics, recordingVideo);
        mPreviewAEMode = b.get(CONTROL_AE_MODE);
        applyZoom(b, false);
        // P42: face statistics on the preview request only (modes the module advertises).
        com.scamera.app.control.subject.SubjectFocus.applyFaceDetectMode(b, mCameraCharacteristics);
        applyIspLight(b); // P365 step 9
    }

    /** The template's AF / AE regions: the touch focus's defaults and the current metering. */
    private void rememberDefaultRegions(CaptureRequest.Builder b) {
        mDefaultAfRegions = b.get(CONTROL_AF_REGIONS);
        mPreviewMeteringAF = mDefaultAfRegions;
        mDefaultAeRegions = b.get(CONTROL_AE_REGIONS);
        mPreviewMeteringAE = mDefaultAeRegions;
    }

    /** Closes every image a reader still holds queued (a closed reader is left alone). */
    private static void drainReader(@Nullable ImageReader reader) {
        if (reader == null) return;
        try {
            for (Image stale; (stale = reader.acquireNextImage()) != null; ) stale.close();
        } catch (Exception ignored) {
            // closed meanwhile
        }
    }

    /**
     * P214f (speed-ux.md C3): the camera screen's message card (UiMessages, MessageCard) instead of a ~2 s system toast: it
     * shows 1.5 s + 50 ms per character (at most 7 s), merges repeats into one message with a counter and keeps the last 20
     * (Система → Диагностика → «Последние сообщения», or a long press on the card). Any thread.
     */
    private void showToast(String msg) {
        com.scamera.app.util.UiMessages.info(msg);
    }

    /**
     * P214f: a failure the owner has to see (the camera failed or refused what was asked, a fallback was taken): the card keeps
     * it, in the warning colour, until it is tapped; also in the history.
     */
    private void showError(String msg) {
        com.scamera.app.util.UiMessages.error(msg);
    }

    private void applyVivoSdkPreview(CaptureRequest.Builder builder) {
        if (nativeVivoFullRes()) {
            VivoPd2454NativeRaw.applyFullRes(builder, VivoSdkFullRes.profile(mVivoSdkCameraId));
            // P224: the viewfinder not below 20 fps; a still keeps the camera's whole range
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, builder == mPreviewRequestBuilder ? ViewfinderFps.raise(FpsRangeAuto, mRawMaxFps) : FpsRangeAuto);
            if (builder == mPreviewRequestBuilder && fullSizeRecipe()) X300FullSize.applyRequest(builder); // P684
            // P201: the manual mode over the FULLRES keys of the viewfinder (not the session parameters; the OPPO's own exposure
            // takes the hand-set ISO / shutter itself)
            if (builder == mPreviewRequestBuilder) {
                if (VivoSdkFullRes.oppo()) paramController.applyManualWbFocusTo(builder);
                else paramController.applyManualTo(builder);
            }
            return;
        }
        mVivoSdkRawSession.apply(builder, false, 0);
        // Fixed 30 fps returns empty FULLRES RAW on PD2454 ultra-wide; this graph captures serial stills, not a RAW ring.
        // P224: a range, never fixed - its floor 20 fps on the viewfinder only
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, builder == mPreviewRequestBuilder ? ViewfinderFps.raise(FpsRangeAuto, mRawMaxFps) : FpsRangeAuto);
        VivoSdkRawSession.applyAutomaticExposure(builder);
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (builder == mPreviewRequestBuilder) paramController.applyManualTo(builder); // P201
    }

    /**
     * Initiate a still image capture.
     */
    private boolean scamQueueMemoryAvailable() {
        android.app.ActivityManager manager = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        // P113: a «Движение» shot is views of the ring's memfd (no new memory) and fewer frames behind a queue: only the
        // system's low-memory state stops it (was: 1.5 GB free, so a merge in progress blocked the next press on most phones)
        if (com.scamera.app.settings.MotionMode.on()) return !info.lowMemory;
        return !info.lowMemory && info.availMem > 1536L * 1024 * 1024;
    }

    public boolean takePicture() {
        // P92: the shot survives the app going behind the screen (started here, while the app is in front)
        com.scamera.app.processing.ProcessingKeepAlive.hold(ScameraApp.getAppContext(), this);
        synchronized (mPreviewStateLock) {
            Log.i(TAG, "SHUTTER camera=" + physicalID + " state=" + mShotState
                    + " processing=" + isProcessing + " zsl=" + mZslCapturing
                    + " shot=" + mShotInProgress + " burst=" + burst);
            boolean processingBlocks = mVivoSdkRawSession != null ? isProcessing || sScamPending.get() > 0
                    : isProcessing && !(PreferenceKeys.isScamEnabled() && scamQueueMemoryAvailable());
            if (scamShutterQueues() && !processingBlocks
                    && (mZslCapturing || mShotInProgress || burst || mScamFireWaiting)) {
                // Stock-like: a press during the tail capture is kept, not dropped.
                if (mScamQueuedShots < 4) {
                    mScamQueuedShots++;
                    Log.i("SCAM_CAPTURE", "shutter queued behind current capture pending=" + mScamQueuedShots);
                    return true;
                }
            }
            // P113 «Движение»: a press with no ring frame since the previous shot (faster than one frame) waits for one,
            // instead of exposing frames after the shutter
            final boolean motionWaited = mMotionWaitExpired;
            mMotionWaitExpired = false;
            // P315: so does a press in an OPPO-family picked sensor mode (its shots come from the ring alone)
            if (scamShutterQueues() && !mScamFireWaiting && !motionWaited
                    && (com.scamera.app.settings.MotionMode.on() || !SensorModePick.seriesAllowed())
                    && !mZslCapturing && !mShotInProgress && !burst && motionRingFrames() == 0
                    && !perShot()) { // P626: a nubia per-shot way has no ring: its N come after the shutter; P635: a recipe neither
                Log.i("SCAM_CAPTURE", "motion: shutter waits for a ring frame");
                mScamFireWaiting = true;
                fireQueuedScamShot(android.os.SystemClock.elapsedRealtime() + MOTION_RING_WAIT_MS);
                return true;
            }
            // P315: the wait ran out with an empty ring in an OPPO-family picked sensor mode: no shot rather than frames after the
            // shutter (the X8U's HAL died on them)
            if (motionWaited && scamShutterQueues() && !SensorModePick.seriesAllowed() && !mZslCapturing && !mShotInProgress && !burst
                    && motionRingFrames() == 0) {
                Log.w("SCAM_CAPTURE", "sensor mode: no ring frame after the wait, the shot is not taken (" + SensorModePick.SERIES_REASON + ")");
                cameraEventsListener.processingFailed(Lang.t("Камера ещё готовится. Повторите снимок.", "The camera is still getting ready. Take the shot again."));
                return false;
            }
            if (scamShutterQueues() && !mScamFireWaiting && mScamTailTimestamp != 0 && !previewFreshAfterTail()) {
                // Pressed just after a tail: the newest preview result still predates
                // it, so wait for fresh frames exactly like a queued press.
                Log.i("SCAM_CAPTURE", "shutter waits for fresh preview after previous tail");
                mScamFireWaiting = true;
                fireQueuedScamShot(android.os.SystemClock.elapsedRealtime() + 2000);
                return true;
            }
            if (mZslCapturing || processingBlocks || mShotInProgress || burst) {
                showToast(Lang.t("Предыдущий снимок ещё обрабатывается. Дождитесь завершения.", "The previous shot is still processing. Wait until it finishes."));
                return false;
            }
            if (!isCurrentPreviewSession(mCaptureSession) ||
                    mCameraDevice == null || mPreviewCaptureResult == null) {
                Log.w(TAG, "takePicture(): waiting for current-session preview after resume");
                cameraEventsListener.processingFailed(Lang.t("Камера ещё готовится. Повторите снимок.", "The camera is still getting ready. Take the shot again."));
                return false;
            }
            Long previewTimestamp = mPreviewCaptureResult.get(CaptureResult.SENSOR_TIMESTAMP);
            scamZslShutterTimestamp = previewTimestamp == null ? 0 : previewTimestamp;
            // P111 «Движение»: the frame on the screen when the finger touched the shutter, not the one at its release (a tap
            // lasts ~0.1 s, three ring frames at 30 fps); only a touch of this session after the previous shot's tail
            final long touched = pressTimestamp(scamZslShutterTimestamp);
            if (touched > 0) {
                Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "motion: shutter at the touch, cutoff %.1f ms before the release",
                        (scamZslShutterTimestamp - touched) / 1e6));
                scamZslShutterTimestamp = touched;
            }
            // The vendor shutter AE snapshot (diagnostic only, never applied) walked
            // every vendor key of the result: 37 ms on the UI thread per press, and
            // this HAL does not publish it for Camera2 clients anyway.
            if(PreferenceKeys.isScamEnabled()) Log.i("SCAM_CAPTURE","shutter camera="+physicalID
                    +" mode="+ScameraApp.getSettings().mode+" zslMode="+isZslMode()
                    +" sensorCutoffNs="+scamZslShutterTimestamp+" cutoffSource=latest_preview_result"
                    +" route="+PreferenceKeys.mergeRoute());
            mShotInProgress = true;
            // P80: «Замер времени проходов GPU» on: this shot's timing lines also go to Download/SCAMERA/SCAMERA-timings.log
            com.scamera.app.util.ScameraTimingLog.shot(ScameraApp.getAppContext(), PreferenceKeys.hybridSwitch("profile", false),
                    android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (" + android.os.Build.DEVICE + ") camera " + physicalID
                    + " route " + PreferenceKeys.mergeRoute());
            com.scamera.app.processing.ShotTimeline.begin("shutter");
            // P238 (speed-ux D2): the thermal state at the press (status / headroom), also the policy's current status
            final com.scamera.app.util.ThermalState.Sample thermal = com.scamera.app.util.ThermalState.now();
            com.scamera.app.util.ThermalPolicy.observed(thermal);
            Log.i("SCAM_CAPTURE", "THERMAL at the press: " + thermal + (com.scamera.app.util.ThermalPolicy.severe()
                    ? " (severe: viewfinder at most " + com.scamera.app.util.ThermalPolicy.SEVERE_VF_FPS + " fps, FULLRES ring paused)" : ""));
            final long shotGeneration = ++mShutterGeneration;
            mShutterIszAspect4x3 = IszCapturePolicy.isIsz(
                    com.scamera.app.settings.ModuleRegistry.active(), mCameraCharacteristics,
                    mPreviewCaptureResult, physicalID, logicalID, mCroppedRawSession);
            mShutterManualWb = paramController.manualWhiteBalanceGains(); // P212f: a later switch to Auto stays out of the shot
            mShutterMosaicWb = mosaicWbAtPress(); // P212g (W6 = M6)
            mShutterLensFilter = com.scamera.app.processing.opengl.postpipeline.LensFilter.Choice.now();
            mShutterLook = com.scamera.app.processing.opengl.postpipeline.ShotLook.now();
            mShutterMotion = com.scamera.app.settings.MotionMode.snapshot();
            mFlashShot = null;
            if (mVivoSdkRawSession != null) {
                flashShotAtPress(); // P590: FULLRES takes no flash shot (its toast once, the torch stays)
                mBackgroundHandler.post(this::captureVivoSdkFullRes);
                return true;
            }
            if (isZslMode()) {
                mScamRingFrozen = PreferenceKeys.isScamEnabled();
                // P590: On, or Auto with the AE asking for the flash: the precapture, then one fresh frame with the flash (not the ring)
                final FlashMode.Shot flash = flashShotAtPress();
                if (flash != null) {
                    startFlashShot(flash);
                    return true;
                }
                takeStill();
                return true;
            }
            startThreeAWait();
            // Some auxiliary cameras never signal AF/AE convergence. Bound
            // every precapture state, including missing preview callbacks.
            final CameraCaptureSession shotSession = mCaptureSession;
            final int generation = mSessionGeneration.get();
            if (mBackgroundHandler != null) mBackgroundHandler.postDelayed(() -> {
                synchronized (mPreviewStateLock) {
                    if (shotGeneration != mShutterGeneration || generation != mSessionGeneration.get()
                            || !isCurrentPreviewSession(shotSession)
                            || !mShotInProgress || !inThreeAWait()) return;
                    Log.w(TAG, "SHUTTER precapture timeout camera=" + physicalID + " state=" + mShotState);
                    mShotState = SHOT_FIRED;
                    takeStill();
                }
            }, 1000);
            // a focus held by hand or by the P252 fallback (AF off) has nothing to lock: no wait for the lock's 1 s timeout
            if (afModesOfCamera != null && afModesOfCamera.length > 1 && !afOffInPreview()) lockFocus();
            else {
                try {
                    mShotState = SHOT_AE_END;
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                            mBackgroundHandler);
                } catch (CameraAccessException | RuntimeException e) {
                    failPendingShutter(e);
                    return false;
                }
            }
            return mShotInProgress;
        }
    }

    /**
     * P590: why this session's route takes no flash shot (FlashMode.routeBlock; null: it can). {@code atPress}: with the press's ISZ
     * decision (IszCapturePolicy, taken at the press), else the session's own state (the preview's AE mode).
     */
    @Nullable
    private String flashRouteBlock(boolean atPress) {
        final String slot = com.scamera.app.settings.ModuleRegistry.active();
        int forcedMode = 0;
        try { forcedMode = com.scamera.app.settings.ModuleRegistry.sensorMode(slot); } catch (RuntimeException ignored) {}
        boolean dcgSwitch = false;
        try { dcgSwitch = Dcg14Mode.active(mCameraCharacteristics); } catch (RuntimeException ignored) {}
        return FlashMode.routeBlock(mVivoSdkRawSession != null || mFullResSession || PixelPro10.fullResOn(),
                dcgSwitch || mDcgRequestKeys || mDcgOperationMode != 0,
                atPress && mShutterIszAspect4x3 || mCroppedRawSession || XiaomiTeleZoom.active() && XiaomiTeleZoom.isz(),
                SensorModePick.session() != null || forcedMode != 0);
    }

    /** P590: the camera lists CONTROL_AE_MODE_OFF (a manual flash frame: AE off, FLASH_MODE_SINGLE). */
    private boolean aeOffListed() {
        final int[] modes = mCameraCharacteristics == null ? null : mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        return listed(modes, CaptureRequest.CONTROL_AE_MODE_OFF);
    }

    /** P590: a toast once a session (route and session), the log line every press. */
    private void flashNotice(String key, String log, String toast) {
        Log.i("SCAM_CAPTURE", log);
        final String once = key + "@" + mSessionGeneration.get();
        if (once.equals(mFlashBlockToast)) return;
        mFlashBlockToast = once;
        showToast(toast);
    }

    /**
     * P590: the press's flash shot, or null for the normal shot: On, or Auto with the latest preview result's AE state
     * FLASH_REQUIRED, on a lens with a flash unit, on a route that takes it (not ISZ / DCG / DAG / FULLRES: Auto / On act as off
     * there, one toast). A manual exposure takes its frame AE off at the manual values with FLASH_MODE_SINGLE where the camera lists
     * AE off, else no flash.
     */
    @Nullable
    private FlashMode.Shot flashShotAtPress() {
        final int mode = FlashMode.effective(PreferenceKeys.getAeMode(), mFlashSupported, false);
        if (mode != FlashMode.AUTO && mode != FlashMode.ON) return null;
        final String blocked = flashRouteBlock(true);
        if (blocked != null) {
            flashNotice(blocked, "flash: mode " + FlashMode.name(mode) + " in the " + blocked + " route: the shot is taken without the flash (P590)",
                    FlashMode.routeBlockText(blocked));
            return null;
        }
        final CaptureResult latest = mPreviewCaptureResult;
        final Integer aeState = latest == null ? null : latest.get(CaptureResult.CONTROL_AE_STATE);
        if (!FlashMode.flashShot(mode, aeState, mFlashSupported)) {
            Log.i("SCAM_CAPTURE", "flash: mode auto, AE state " + FlashMode.aeStateName(aeState) + ": no flash needed, the normal shot");
            return null;
        }
        if (!PreferenceKeys.isScamHybridEnabled()) {
            Log.i("SCAM_CAPTURE", "flash: the merge route is not the Hybrid: the normal shot (P590)");
            return null;
        }
        final long manualNs = paramController.manualShotShutterNs();
        final int manualIso = paramController.manualIsoValue();
        if (manualNs > 0 || manualIso > 0) {
            final long ns = manualNs > 0 ? manualNs : mPreviewExposureTime;
            final int iso = manualIso > 0 ? manualIso : mPreviewIso;
            if (!aeOffListed() || ns <= 0 || iso <= 0) {
                flashNotice("manual", "flash: manual exposure without AE off (or no exposure known): the shot is taken without the flash (P590)",
                        Lang.t("Вспышка с ручной экспозицией здесь не работает: снимок без вспышки",
                                "The flash does not work with the manual exposure here: the shot is taken without it"));
                return null;
            }
            return new FlashMode.Shot(mode, aeState, ns, iso);
        }
        return new FlashMode.Shot(mode, aeState, 0, 0);
    }

    /**
     * P590: a flash press: the ring keeps the frames of the press (a flash that does not fire falls back to them), the AE precapture
     * runs on the preview (the pre-flash), then takeStill takes the flash frame; a manual flash frame needs no precapture.
     */
    private void startFlashShot(FlashMode.Shot flash) {
        mFlashRingHold = true;
        mZslCapturing = true; // no preview RAW (the pre-flash) enters the ring from here
        if (flash.manual() || mPreviewRequestBuilder == null || mCaptureSession == null) {
            mFlashShot = flash;
            mShotState = SHOT_FIRED;
            takeStill();
            return;
        }
        final long start = android.os.SystemClock.elapsedRealtime();
        mFlashPending = flash;
        mFlashPrecapture = new FlashMode.Precapture(start);
        mShotState = SHOT_FLASH_AE;
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        try {
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mCaptureSession.capture(builder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "flash shot: precapture trigger failed (" + e + "): the frame without the precapture");
            endFlashPrecapture("trigger failed");
            return;
        } finally {
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        }
        final FlashMode.Precapture wait = mFlashPrecapture;
        if (mBackgroundHandler != null) mBackgroundHandler.postDelayed(() -> {
            synchronized (mPreviewStateLock) {
                if (mShotState == SHOT_FLASH_AE && mFlashPrecapture == wait) endFlashPrecapture("timeout, no result");
            }
        }, FlashMode.PRECAPTURE_TIMEOUT_MS + 300);
    }

    /** P590: the flash fired: the ring's frames and results held since the press go (the next shot's ring starts after this one). */
    private void releaseFlashRing() {
        synchronized (mRingLock) {
            for (RingFrame image : mZslRingBuffer) image.close();
            mZslRingBuffer.clear();
            mHexZslResults.clear();
        }
        mFlashRingHold = false;
    }

    /** P590: no flash shot pending or holding the ring (a shot's end, its failure, the camera closed). */
    private void clearFlashShot() {
        mFlashShot = null;
        mFlashPending = null;
        mFlashPrecapture = null;
        mFlashRingHold = false;
        if (mShotState == SHOT_FLASH_AE) mShotState = SHOT_IDLE;
    }

    /** P590: a preview result while the flash shot waits for its precapture. */
    private void flashPrecaptureProcess(CaptureResult result) {
        final FlashMode.Precapture wait = mFlashPrecapture;
        if (wait == null) return;
        final CaptureRequest request = result.getRequest();
        final String end = wait.offer(request == null ? null : request.get(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER),
                result.get(CaptureResult.CONTROL_AE_STATE), android.os.SystemClock.elapsedRealtime());
        if (end != null) endFlashPrecapture(end);
    }

    /** P590: the precapture ended ({@code why}): the flash frame. */
    private void endFlashPrecapture(String why) {
        final FlashMode.Shot flash = mFlashPending;
        final FlashMode.Precapture wait = mFlashPrecapture;
        mFlashPending = null;
        mFlashPrecapture = null;
        if (flash == null) return;
        flash.precaptureMs = wait == null ? 0 : wait.elapsed(android.os.SystemClock.elapsedRealtime());
        flash.precaptureEnd = why;
        Log.i("SCAM_CAPTURE", flash.describe());
        mFlashShot = flash;
        mShotState = SHOT_FIRED;
        takeStill();
    }

    /**
     * P590: the flash did not fire on the flash frame: the normal shot of the same press - its ring frames, held since the press
     * (the shot's cutoff kept), merged as any shot; the shutter stays busy until that shot's end.
     */
    private void flashFallbackShot() {
        synchronized (mPreviewStateLock) {
            mFlashShot = null;
            mFlashRingHold = false;
            if (!isCameraResumed || mCaptureSession == null || mCameraDevice == null) {
                mZslCapturing = false;
                finishScamShot();
                return;
            }
            Log.i("SCAM_CAPTURE", "flash shot: the flash did not fire, the normal shot of the press from the ring (P590)");
            mScamRingFrozen = PreferenceKeys.isScamEnabled();
            mShotState = SHOT_FIRED;
            takeStill();
        }
    }

    /** SCAM ZSL shots accept presses during capture; the UI keeps the shutter enabled. */
    public boolean scamShutterQueues() {
        // Only the SCAM route (no remosaic backend) frees the shutter through
        // finishScamShot(); any other route would leave queued presses stuck.
        return PreferenceKeys.isScamEnabled() && isZslMode();
    }

    /**
     * The vendor AE resumes from the last applied sensor settings: after the ES
     * frame (e.g. 10 ms, ISO 77) the preview ramped back over ~8 frames and the next
     * shot found no ZSL RAWs at the planned N. One preview frame at the pre-shutter
     * exposure, queued behind the tail, hands AE back at the right values.
     * P38 dev switch scam_dev.txt "ae_restore": 0 = no restore frame, 1 = the manual frame (default),
     * 2 = one AE ON + AE lock frame instead of the manual one (the vendor AE / stabilisation never sees AE OFF).
     */
    private void queueScamAeRestore() {
        final int mode = Math.round(PreferenceKeys.scamDevNumber("ae_restore", 1f));
        if (mode == 0) {
            mStabTrace.event("AE restore frame skipped (ae_restore 0)");
            return;
        }
        if (mode == 2) {
            queueScamAeLockFrame();
            return;
        }
        CaptureResult base = mNativeZslBase != null ? mNativeZslBase : mPreviewCaptureResult;
        if (base == null || mPreviewRequestBuilder == null) return;
        Long exposure = base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = base.get(CaptureResult.SENSOR_SENSITIVITY);
        Long duration = base.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (exposure == null || iso == null || exposure <= 0 || iso <= 0) return;
        CaptureRequest.Builder b = mPreviewRequestBuilder;
        Integer aeMode = b.get(CaptureRequest.CONTROL_AE_MODE);
        Long oldExposure = b.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
        Integer oldIso = b.get(CaptureRequest.SENSOR_SENSITIVITY);
        Long oldDuration = b.get(CaptureRequest.SENSOR_FRAME_DURATION);
        // P54c: under the forced stabilisation (X300U) the manual frame skips the viewfinder stream, as WhyCam's manual
        // frames do: the HAL's preview / stabilisation path never sees an AE OFF request. Same exposure, ISO and RAW.
        final Surface viewfinder = surface;
        final boolean dropViewfinder = viewfinder != null && mRepeatingIsp && ForcedStabilization.dropViewfinder(mForcedStab,
                ForcedStabilization.rawOnlySwitch(), isZslMode() || mLiveRawSession, recordingVideo);
        boolean viewfinderRemoved = false;
        try {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            if (duration != null && duration > 0) b.set(CaptureRequest.SENSOR_FRAME_DURATION, duration);
            if (dropViewfinder) {
                b.removeTarget(viewfinder);
                viewfinderRemoved = true;
            }
            CaptureRequest restore = b.build();
            mCaptureSession.capture(restore, mCaptureCallback, mBackgroundHandler);
            String where = viewfinderRemoved ? " (RAW stream only, P54c)" : "";
            Log.i("SCAM_CAPTURE", "AE restore frame queued exposureNs=" + exposure + " ISO=" + iso + where);
            mStabTrace.event("AE restore frame queued (AE OFF) exposureNs=" + exposure + " ISO=" + iso + where);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "AE restore frame not queued: " + e);
        } finally {
            if (viewfinderRemoved) b.addTarget(viewfinder);
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, oldExposure);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, oldIso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, oldDuration);
        }
    }

    /** P38 "ae_restore 2": one preview frame with AE ON and AE locked behind the tail, the repeating preview unlocks it. */
    private void queueScamAeLockFrame() {
        CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b == null || mCaptureSession == null) return;
        Boolean oldLock = b.get(CaptureRequest.CONTROL_AE_LOCK);
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            mCaptureSession.capture(b.build(), mCaptureCallback, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", "AE restore frame queued as AE ON + lock (ae_restore 2)");
            mStabTrace.event("AE restore frame queued (AE ON + lock)");
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "AE lock frame not queued: " + e);
        } finally {
            b.set(CaptureRequest.CONTROL_AE_LOCK, oldLock);
        }
    }

    /**
     * P38: the vivo X300 Ultra preview lost its stabilisation after a shot (the HAL queue flush before the series is
     * the first suspect: the HAL went idle and restarted its pipeline on a bracket request, not on the preview). Once
     * the shot's series has completed, the preview is re-armed - off the shutter path, so the flush keeps its latency
     * gain. Requested from finishScamShot, when every RAW of the series is in memory. scam_dev.txt "stab_rearm":
     *   0 = off;
     *   1 = re-issue the repeating preview request (settings re-sent to the HAL);
     *   2 = one preview frame with OIS (and video stabilisation, when the preview sets it) OFF, then the repeating
     *       preview with the normal keys: the HAL sees a stabilisation mode change OFF -> ON;
     *   3 = flush the HAL queue again and restart the repeating preview, so the pipeline restarts on a preview request.
     *       It waits for the first result of the repeating preview after the series: the AE restore frame queued
     *       behind the series is then done (a flush from finishScamShot itself dropped it on the OPPO, 0.47 s after
     *       the press) and only preview frames are dropped;
     *   default (no line) on a vivo HAL (where the loss was seen) = 3 after a flushed shot, 1 otherwise; elsewhere 0 (the OPPO
     *   and the other cameras keep the preview exactly as before).
     * Never under a queued or waiting next shot, nor under another shot's frames: that shot flushes and re-arms on its own.
     */
    private void requestPreviewRearm(boolean shotFlushed, boolean skip) {
        int mode = previewRearmMode(shotFlushed, mLastShotLead);
        if (mode <= 0 || mode > 3) return;
        if (skip) {
            mStabTrace.event("re-arm skipped: a queued shot follows or the session restarts");
            return;
        }
        if (mode == 3) {
            mRearmDeadlineMs = android.os.SystemClock.elapsedRealtime() + 1500;
            mRearmSession = mCaptureSession;
            mRearmPending = true;
            mStabTrace.event("re-arm (flush) waits for the first preview frame after the series");
            return;
        }
        rearmPreviewStabilisation(mCaptureSession, mode);
    }

    /** P38 / P44: the re-arm mode after a shot (scam_dev "stab_rearm" wins over the default of PreviewContinuity). */
    private static int previewRearmMode(boolean shotFlushed, int leadFrames) {
        int fallback = PreviewContinuity.defaultRearm(ScamPreview.supported(), shotFlushed, leadFrames);
        return Math.round(PreferenceKeys.scamDevNumber("stab_rearm", fallback));
    }

    /**
     * P44 scam_dev "preview_lead N": N frames of the normal preview request right after the flush, ahead of the series. The
     * viewfinder gets a frame as soon as the HAL has restarted, and the restart happens on a preview request (the P38 re-arm
     * flush after the series is then not needed). Their RAWs are routed as preview frames (dropped during the shot) like the
     * AE restore frame's. Each one starts the series a preview frame later.
     */
    private void queuePreviewLead(int frames) {
        CaptureRequest.Builder b = mPreviewRequestBuilder;
        CameraCaptureSession session = mCaptureSession;
        if (b == null || session == null) return;
        try {
            CaptureRequest lead = b.build();
            for (int i = 0; i < frames; i++) session.capture(lead, mCaptureCallback, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", "preview lead frames queued ahead of the series: " + frames);
            mStabTrace.event("preview lead frames queued ahead of the series: " + frames);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "preview lead frames not queued: " + e);
        }
    }

    /** P38: from the preview callback: the first repeating-preview result after the series runs the pending re-arm. */
    private void maybeRunPendingRearm(CameraCaptureSession session, CaptureRequest request, CaptureResult result) {
        if (!mRearmPending || request != mRepeatingRequest) return;
        if (android.os.SystemClock.elapsedRealtime() > mRearmDeadlineMs || session != mRearmSession || nextShotPending()) {
            mRearmPending = false;  // a new shot flushes on its own; a lost moment is not chased
            mStabTrace.event("pending re-arm dropped");
            return;
        }
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null || ts <= mScamTailTimestamp) return;
        mRearmPending = false;
        final android.os.Handler handler = mBackgroundHandler;
        if (handler != null) handler.post(() -> rearmPreviewStabilisation(session, 3));
    }

    /** P38: a shot is in flight, pressed, queued or waiting for fresh preview frames (read under mPreviewStateLock to be exact). */
    private boolean nextShotPending() {
        return shotInFlight() || mShotInProgress || mScamFireWaiting || mScamQueuedShots > 0;
    }

    private void rearmPreviewStabilisation(CameraCaptureSession session, int mode) {
        synchronized (mPreviewStateLock) {
            if (!isCameraResumed || session == null || session != mCaptureSession || mPreviewRequestBuilder == null) return;
            // Checked under the lock takePicture() sets its flags under: a press is either fully before (skipped here) or
            // waits for this re-arm and then takes the frames after it. A re-arm never flushes or re-issues the preview under
            // another shot's requests (it would drop them, or put preview frames ahead of its bracket).
            if (nextShotPending()) {
                mStabTrace.event("re-arm mode=" + mode + " skipped: another shot is pending");
                return;
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            try {
                if (mode == 2) {
                    CaptureRequest.Builder b = mPreviewRequestBuilder;
                    Integer ois = b.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE);
                    Integer vs = b.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
                    try {
                        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                        if (vs != null && vs != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                        session.capture(b.build(), mCaptureCallback, mBackgroundHandler);
                    } finally {
                        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, ois);
                        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vs);
                    }
                } else if (mode == 3) {
                    // The series and its AE restore frame are done: only preview frames are dropped.
                    flushDevice(session);
                }
                session.setRepeatingRequest(mRepeatingRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                String what = "preview stabilisation re-armed mode=" + mode + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms"
                        + " keys[" + StabilizationTrace.stabKeysLine(mRepeatingRequest) + "]";
                Log.i("SCAM_CAPTURE", what);
                mStabTrace.event(what);
            } catch (CameraAccessException | RuntimeException e) {
                Log.w("SCAM_CAPTURE", "preview stabilisation re-arm failed: " + e);
                // A failed flush leaves the preview stopped: bring it back.
                try {
                    session.setRepeatingRequest(mRepeatingRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                } catch (CameraAccessException | RuntimeException ignored) { }
            }
        }
    }

    /**
     * P27: the camera closed, failed or stalled while a shot was in flight: the frames that arrived are processed (the
     * outstanding requests count as lost) instead of vanishing with the shutter left locked (shot flags, static burst, ring
     * routing, ~25 MB of native memory per deferred ZSL frame).
     */
    private void rescueInFlightShot(String reason) {
        final Runnable rescue = mInFlightRescue;
        mInFlightRescue = null;
        if (rescue == null) {
            mNativeRawPslCapture = false; mZslCapturing = false; mHybridZslCapture = false; burst = false;
            clearFlashShot(); // P590
            return;
        }
        Log.w("SCAM_CAPTURE", "shot in flight completed with the frames that arrived: " + reason);
        mInFlightRescueReason = reason; // P222: a FULLRES series tells a camera error from the camera closed under it
        try {
            rescue.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "shot rescue failed: " + Log.getStackTraceString(e));
        }
    }

    /** P111: the newest preview result's sensor time when the finger touched the shutter, its session and wall time. */
    private volatile long mPressPreviewTimestamp, mPressAtMs;
    private volatile int mPressGeneration;

    /**
     * P111: the finger is on the shutter (the shot fires on release): the frame on the screen now is the moment, as the stock
     * vivo snapshot mode sends its displayTimeStamp: the viewfinder's last shown camera frame, else the newest preview result.
     */
    public void notePressDown() {
        final CaptureResult latest = mPreviewCaptureResult;
        final Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        final long newest = ts == null ? 0 : ts;
        final long shown = com.scamera.app.ui.camera.views.viewfinder.MainRenderer.shownTimestamp();
        mPressGeneration = mSessionGeneration.get();
        mPressAtMs = android.os.SystemClock.elapsedRealtime();
        mPressPreviewTimestamp = shown > 0 && shown <= newest && newest - shown < 300_000_000L ? shown : newest;
    }

    /**
     * P111: the touch's frame for the «Движение» cutoff, 0 when it does not apply (mode off, no touch: the volume key fires on
     * key-down already; a touch of an older session, older than 1.5 s, before the previous shot's tail, or not before
     * {@code releaseCutoff}). Used once.
     */
    private long pressTimestamp(long releaseCutoff) {
        final long touched = mPressPreviewTimestamp;
        mPressPreviewTimestamp = 0;
        if (touched <= 0 || !com.scamera.app.settings.MotionMode.on()) return 0;
        if (mPressGeneration != mSessionGeneration.get() || android.os.SystemClock.elapsedRealtime() - mPressAtMs > 1500) return 0;
        if (touched >= releaseCutoff || (mScamTailTimestamp != 0 && touched <= mScamTailTimestamp)) return 0;
        return touched;
    }

    /** P27: a shot is between its press and the end of its post-shutter frames. */
    private boolean shotInFlight() {
        return mInFlightRescue != null || mZslCapturing || burst;
    }

    /** P42: face / tracking regions must not change the repeating request while a shot is being captured. */
    public boolean subjectRegionsFrozen() {
        return shotInFlight() || mShotInProgress;
    }

    private void finishScamShot() {
        final boolean sessionRestart = mPendingPayloadRestart && mBackgroundHandler != null;
        if (sessionRestart) {
            mPendingPayloadRestart = false;
            final int generation = mSessionGeneration.get();
            mBackgroundHandler.post(() -> {
                if (!isCameraResumed || generation != mSessionGeneration.get() || mCaptureSession == null) return;
                mCaptureSession.close();
                startSession(false);
            });
        }
        final boolean fire;
        synchronized (mPreviewStateLock) {
            mShotInProgress = false;
            mScamRingFrozen = false;
            clearFlashShot(); // P590
            mShotState = SHOT_IDLE;
            fire = mScamQueuedShots > 0;
            if (fire) mScamQueuedShots--;
        }
        // P38: every frame of the series is in memory and the shot's flags are clear: re-arm the preview stabilisation (the
        // preview kept running on this route). Not when a queued press fires now (that shot re-arms after its own series), nor
        // when the session is restarted anyway.
        requestPreviewRearm(mLastShotFlushed, fire || mScamFireWaiting || sessionRestart);
        Log.i("SCAM_CAPTURE", "shutter free (preview kept running) queuedNext=" + fire);
        if (fire) fireQueuedScamShot(android.os.SystemClock.elapsedRealtime() + 2000);
    }

    /**
     * After the manual tail the HAL resumes preview results late, so the newest
     * preview frame at this moment can still predate the bracket (no fresh AE plan,
     * no ZSL RAWs at the planned N). Fire once five preview frames follow the tail:
     * four ring RAWs plus the frame the shutter timestamp is taken from.
     */
    private boolean previewFreshAfterTail() {
        // P113 «Движение»: the next shot fires as soon as the ring holds a frame (the stock snapshot mode drops to fewer frames
        // when it is pressed fast, it does not wait); the ring's frames are all newer than the previous shot's (it drained them)
        if (com.scamera.app.settings.MotionMode.on()) return motionRingFrames() > 0;
        CaptureResult latest = mPreviewCaptureResult;
        Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null || ts <= mScamTailTimestamp + 5 * 33_000_000L) return false;
        // Preview RAWs arrive later than their results: wait until the ring holds
        // four RAWs taken after the previous tail, so N come from ZSL instead of
        // being re-shot after the press (0.9 s and shutter lag).
        // Count the newest run of ring RAWs after the tail that share one exposure
        // and ISO: the first frames after a tail can still carry AE recovery.
        int run = 0;
        synchronized (mRingLock) {
            Long exposure = null; Integer iso = null;
            java.util.Iterator<RingFrame> it = mZslRingBuffer.descendingIterator();
            while (it.hasNext()) {
                RingFrame image = it.next();
                if (image.getTimestamp() <= mScamTailTimestamp) break;
                TotalCaptureResult result = mHexZslResults.get(image.getTimestamp());
                if (result == null) { if (run == 0) continue; else break; }
                Long e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer s = result.get(CaptureResult.SENSOR_SENSITIVITY);
                if (e == null || s == null) break;
                if (exposure == null) { exposure = e; iso = s; }
                else if (!exposure.equals(e) || !iso.equals(s)) break;
                run++;
            }
        }
        return run >= 4;
    }

    /** P113: how long a «Движение» press waits for its first ring frame before the shot takes its frames after the shutter. */
    static final long MOTION_RING_WAIT_MS = 300;
    /** P113: the wait ran out without a ring frame: the next takePicture shoots without waiting again. */
    private volatile boolean mMotionWaitExpired;

    /** P113: ring frames with their result (a shot can use them), newer than the previous shot's tail. */
    private int motionRingFrames() {
        int n = 0;
        synchronized (mRingLock) {
            for (RingFrame image : mZslRingBuffer)
                if (image.getTimestamp() > mScamTailTimestamp && mHexZslResults.get(image.getTimestamp()) != null) n++;
        }
        return n;
    }

    private void fireQueuedScamShot(long deadline) {
        if (mBackgroundHandler == null) {mScamFireWaiting = false; return;}
        CaptureResult latest = mPreviewCaptureResult;
        Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        long tail = mScamTailTimestamp;
        boolean fresh = previewFreshAfterTail();
        if (!fresh && android.os.SystemClock.elapsedRealtime() < deadline) {
            mScamFireWaiting = true;
            // P113: «Движение» looks every 5 ms (a ring frame is the whole wait), the other modes every 30 ms
            mBackgroundHandler.postDelayed(() -> fireQueuedScamShot(deadline),
                    com.scamera.app.settings.MotionMode.on() ? 5 : 30);
            return;
        }
        Log.i("SCAM_CAPTURE", "queued shutter fires fresh=" + fresh + " previewAfterTailMs="
                + (ts == null ? -1 : (ts - tail) / 1_000_000));
        if (!fresh) mMotionWaitExpired = true;
        mScamFireWaiting = false;
        mScamTailTimestamp = 0;
        if (!takePicture()) Log.w("SCAM_CAPTURE", "queued shutter could not start");
    }

    private boolean inThreeAWait() {
        return mShotState == SHOT_AF_LOCK || mShotState == SHOT_AE_START
                || mShotState == SHOT_AE_END;
    }

    private void failPendingShutter(Exception error) {
        mScamRingFrozen = false;
        clearFlashShot(); // P590
        mShotInProgress = false;
        mShotState = SHOT_IDLE;
        Log.e(TAG, "SHUTTER failed camera=" + physicalID, error);
        cameraEventsListener.processingFailed(Lang.t("Не удалось запустить съёмку: ", "Could not start the capture: ") + error.getMessage());
    }

    /** One AF trigger in a single request beside the repeating one (whose trigger the caller sets back to IDLE). */
    private void afTriggerOnce(int trigger) {
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger);
        rebuildPreviewBuilderOneShot();
    }

    /** After a SCAM-routed shot: the AF lock of lockFocus released (CANCEL once, the repeating request IDLE). */
    void releaseShotAfLock() {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (burst || b == null || mCaptureSession == null) return;
        final Integer mode = b.get(CaptureRequest.CONTROL_AF_MODE);
        if (mode == null || mode == CaptureRequest.CONTROL_AF_MODE_OFF) return;
        final TouchFocus touch = mTouchFocus;
        if (touch != null && (touch.isTouchFocus || touch.isLocked())) return; // the user's point stays
        afTriggerOnce(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        rebuildPreviewBuilder();
    }

    /**
     * After a shot and at every session start: the shutter is free again, AF goes back to its own scan (CANCEL, the 3A reset,
     * START, CANCEL - each in one request) and the viewfinder's repeating request carries IDLE (owner, 2026-10-18 «оппо не
     * фокусируется», «на всех»: a CANCEL left in the builder was sent on every frame, continuous AF never finished a scan).
     */
    public void unlockFocus() {
        // the shot is done with the reader: the RAW viewfinder may take frames again
        mShotInProgress = false;
        mScamQueuedShots = 0;
        if (mPreviewRequestBuilder == null || mCaptureSession == null) {
            Log.d(TAG, "AF reset with no running session (builder=" + mPreviewRequestBuilder + ", session=" + mCaptureSession + ")");
            return;
        }
        try {
            afTriggerOnce(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            restore3A();
            afTriggerOnce(CaptureRequest.CONTROL_AF_TRIGGER_START);
            afTriggerOnce(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            paramController.setupPreview();
            mShotState = SHOT_IDLE;
            rebuildPreviewBuilder();
        } catch (Exception e) {
            Log.d(TAG, "AF reset failed: " + e);
        }
    }

    public boolean isZslMode() {
        if (mVivoSdkRawSession != null) return false;
        if (VivoQcomModes.wantsStreamUsage()
                && PreferenceKeys.scamDevSwitch("vivo_qcom_raw_psl", false)) return false;
        CameraMode mode=ScameraApp.getSettings().mode;
        return mode==CameraMode.MOTION || mode==CameraMode.PHOTO || mode==CameraMode.NIGHT;
    }

    /** Both routes (the SCAM Hybrid and SCAM HDR) bracket after the ZSL N frames. */
    private boolean needsExposureBracket() {
        return PreferenceKeys.isScamEnabled();
    }

    /**
     * How many pre-shutter RAW frames the ZSL ring keeps.
     *
     * The user setting wins when set; 0 means follow the burst frame count, the
     * behaviour before the setting was wired up.
     *
     * Every frame in the ring is a full-size RAW buffer - about 25 MB at 12.6 MP
     * on this sensor - so the ring is the largest single memory consumer in the
     * app. 100 frames is roughly 2.5 GB and will not fit; the setting allows it
     * because it was asked for, but the practical ceiling is where the device
     * stops allocating, and past it the reader starves rather than buffering
     * more.
     */
    /**
     * Hand the newest preview RAW frame to the viewfinder.
     *
     * Called from the image callback, on the frame that is about to go into the
     * ZSL ring - the same data, so no extra stream and no extra HAL load. The
     * copy inside LiveRawFrame is what the viewfinder develops; the Image goes
     * on to the ring untouched.
     */
    // RawPayloadCheck on the preview stream: the first frames of every session, then every 30th.
    private int mPayloadFrames;
    private volatile boolean mPayloadBad;
    /**
     * Packed layouts seen behind this session's preview RAW_SENSOR frames (Xiaomi 17 Ultra tele: MIPI RAW10 in mode 4; in the
     * in-sensor zoom, mode 9, RAW10 and RAW14 frames alternate, P72). Empty: plain 16-bit. Every frame picks its own by where its
     * data ends ({@link #frameLayout}); the full check runs only on a frame none fits.
     */
    private final java.util.List<com.scamera.app.processing.RawPayloadCheck.Layout> mPreviewLayouts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private long mLayoutTs = Long.MIN_VALUE;
    private com.scamera.app.processing.RawPayloadCheck.Layout mLayoutOfTs;
    private synchronized com.scamera.app.processing.RawPayloadCheck.Layout frameLayout(Image img) {
        if (mPreviewLayouts.isEmpty() || img.getFormat() != ImageFormat.RAW_SENSOR) return null;
        if (img.getTimestamp() == mLayoutTs) return mLayoutOfTs;
        java.nio.ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        final int end = com.scamera.app.processing.RawPayloadCheck.dataEnd(buffer);
        com.scamera.app.processing.RawPayloadCheck.Layout found = null;
        for (com.scamera.app.processing.RawPayloadCheck.Layout l : mPreviewLayouts)
            if (com.scamera.app.processing.RawPayloadCheck.fits(l, end, img.getWidth(), img.getHeight())) { found = l; break; }
        if (found == null && end < buffer.capacity() - img.getWidth()) {
            found = packedLayout(img);
            if (found != null) addPreviewLayout(found);
        }
        mLayoutTs = img.getTimestamp();
        mLayoutOfTs = found;
        return found;
    }
    private void addPreviewLayout(com.scamera.app.processing.RawPayloadCheck.Layout layout) {
        for (com.scamera.app.processing.RawPayloadCheck.Layout l : mPreviewLayouts) if (l.format == layout.format && l.stride == layout.stride) return;
        mPreviewLayouts.add(layout);
        Log.i(TAG, "preview RAW of camera " + physicalID + ": " + layout.describe() + ", unpacked for the viewfinder");
    }
    /** The format a RAW frame is read as: its packed layout behind RAW_SENSOR ({@link #frameLayout}), else its own. */
    private int readFormat(Image img) {
        com.scamera.app.processing.RawPayloadCheck.Layout l = frameLayout(img);
        return l != null ? l.format : img.getFormat();
    }
    private int readStride(Image img) {
        com.scamera.app.processing.RawPayloadCheck.Layout l = frameLayout(img);
        return l != null ? l.stride : img.getPlanes()[0].getRowStride();
    }
    private boolean mScamPreviewActive;
    /** The device whose session configuration was refused and is being retried (onConfigureFailed's chain) until one configures. */
    private volatile CameraDevice mRetryDevice;
    /** The device whose session (SessionConfiguration) is being configured: no onConfigured / onConfigureFailed yet. */
    private volatile CameraDevice mConfiguringDevice;
    /** P54b: this session holds the stabilisation on (ForcedStabilization: X300 Ultra); with the vivo EIS key. */
    private boolean mForcedStab;
    private boolean mForcedStabEis;
    private boolean mVivoEisSession;
    /*
     * Cameras whose preview RAW was not plain 16-bit while ScamPreview's stock profile ran (vivo X100 Ultra main: packed
     * 10-bit ZSL frames under the PD2454 stagger / HDR preview tags) run the plain Camera2 preview from then on (this process;
     * PlainPreviewCameras); the ring check still guards the shot when the plain preview is packed too.
     */

    /**
     * P110: the RAW level of a ring frame for the «Движение» shutter ceiling (RawLevel), only while the mode is on and the
     * payload is a plain 16-bit RAW (no packed layout in use). The previewed physical camera's levels at the stream's bit depth
     * (P127 RawLevels, with the frame's own metadata when its result is at hand).
     */
    private void observeRawLevel(Image img, CaptureResult result) {
        if (!com.scamera.app.settings.MotionMode.on()) return;
        CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
        if (c == null) c = mCameraCharacteristics;
        if (c == null) return;
        final com.scamera.app.processing.RawLevels.Levels levels = liveLevels(c, result);
        // P365 (review): a RAW10 / RAW12 stream's frame too, unpacked (meterSamples)
        if (img == null || mPayloadBad || !mPreviewLayouts.isEmpty()) return;
        final java.nio.ByteBuffer samples = meterSamples(img);
        if (samples != null) {
            final CaptureResult newest = mPreviewCaptureResult;
            motionRegion(result != null ? result : newest, c);
            final Long newestTs = newest == null ? null : newest.get(CaptureResult.SENSOR_TIMESTAMP);
            final long observed = MotionPolicy.observedAtNs(System.nanoTime(), img.getTimestamp(), newestTs == null ? 0 : newestTs);
            final int block = com.scamera.app.processing.MosaicStream.block();
            RawLevel.observe(samples, img.getWidth(), img.getHeight(), mMeterStride, img.getTimestamp(), levels.meanBlack(), levels.white,
                    observed, block);
        }
    }

    private void motionRegion(CaptureResult result, CameraCharacteristics characteristics) {
        MotionMeter.region(0, 0, 0, 0);
        if (result == null) return;
        android.graphics.Rect active = mCroppedRawSession ? CroppedRaw.of(result, physicalID, logicalID)
                : characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null || active.isEmpty()) return;
        android.graphics.Rect subject = null;
        final android.hardware.camera2.params.Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
        if (faces != null) for (android.hardware.camera2.params.Face face : faces) {
            android.graphics.Rect bounds = face.getBounds();
            if (face.getScore() >= 50 && (subject == null || (long) bounds.width() * bounds.height() > (long) subject.width() * subject.height()))
                subject = bounds;
        }
        final android.hardware.camera2.params.MeteringRectangle[] af = result.get(CaptureResult.CONTROL_AF_REGIONS);
        if (subject == null && af != null) for (android.hardware.camera2.params.MeteringRectangle r : af) {
            android.graphics.Rect bounds = r.getRect();
            if (r.getMeteringWeight() > 0 && !bounds.isEmpty()
                    && (long) bounds.width() * bounds.height() < 0.5 * active.width() * active.height()) { subject = bounds; break; }
        }
        if (subject != null) MotionMeter.region((subject.left - active.left) / (float) active.width(),
                (subject.top - active.top) / (float) active.height(), (subject.right - active.left) / (float) active.width(),
                (subject.bottom - active.top) / (float) active.height());
    }

    /** P365 (review): the unpacked packed frame of meterSamples (reused; released with the camera), its frame and row stride. */
    private java.nio.ByteBuffer mMeterUnpacked;
    private long mMeterUnpackedTs = Long.MIN_VALUE;
    private int mMeterStride;

    /**
     * P365 (review; owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the
     * plain 16-bit samples of a live RAW frame for the per-frame RAW meters (plainMeter, plainTeacher, ettrMeter, observeRawLevel),
     * asked for only when one of them is due - the camera's plane of a RAW_SENSOR frame; a packed RAW10 / RAW12 frame (the Find X7
     * Ultra's stream since DeviceDefaults v7, the X8 Ultra's since v2) unpacked once per frame into a reused buffer (RawUnpack:
     * liballocator, rows on several cores) that the meters of that frame share - they read the camera's Image before the ring
     * copy, so the ring's own unpack (ZslRing.store) is not theirs. Before this a packed frame was skipped (RAW_SENSOR only: the
     * native AE's plain sample, the WB teacher, the «Движение» level) or read as 16-bit (ETTR: p99.9 ~1.0, always "clipped").
     * Null: no plain samples (another format, a pixel stride other than 2, an unpack that failed); else {@link #mMeterStride} is
     * its row stride in bytes.
     */
    @Nullable
    private java.nio.ByteBuffer meterSamples(Image img) {
        final int format = img.getFormat();
        final Image.Plane plane = img.getPlanes()[0];
        if (format == ImageFormat.RAW_SENSOR) {
            if (plane.getPixelStride() != 2) return null;
            mMeterStride = plane.getRowStride();
            return plane.getBuffer();
        }
        if (!com.scamera.app.util.RawUnpack.isPacked(format)) return null;
        final int w = img.getWidth(), h = img.getHeight(), bytes = w * h * 2;
        java.nio.ByteBuffer buffer = mMeterUnpacked;
        if (buffer == null || buffer.capacity() < bytes || mMeterUnpackedTs != img.getTimestamp()) {
            if (buffer == null || buffer.capacity() < bytes)
                mMeterUnpacked = buffer = java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
            mMeterUnpackedTs = Long.MIN_VALUE;
            if (!com.scamera.app.util.RawUnpack.unpack(plane.getBuffer().duplicate(), format, w, h, plane.getRowStride(), buffer))
                return null;
            mMeterUnpackedTs = img.getTimestamp();
        }
        mMeterStride = w * 2;
        final java.nio.ByteBuffer out = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder());
        out.position(0);
        out.limit(bytes);
        return out;
    }

    /** P266 «Проверить частоты кадров»: the running probe of this session, null without one. */
    private volatile FpsProbe mFpsProbe;

    /** P266: the module's sensor page asked for the frame-rate probe: it runs a second after this session configured. */
    private void startFpsProbeIfAsked() {
        final String slot = com.scamera.app.settings.ModuleRegistry.active();
        if (mVivoSdkRawSession != null || recordingVideo || !FpsProbe.take(ScameraApp.getAppContext(), slot)) return;
        final Handler handler = mBackgroundHandler;
        final CameraCharacteristics c = mCameraCharacteristics;
        if (handler == null || c == null) return;
        final int generation = mSessionGeneration.get();
        handler.postDelayed(() -> {
            if (generation != mSessionGeneration.get() || mPreviewRequestBuilder == null) return;
            final FpsProbe probe = new FpsProbe(FpsProbe.candidates(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)));
            if (!probe.next(android.os.SystemClock.elapsedRealtimeNanos())) return;
            mFpsProbe = probe;
            com.scamera.app.util.UiMessages.info(Lang.t("Проверка частот кадров…", "Checking the frame rates…"));
            applyProbeRate(probe);
        }, 1000);
    }

    private void applyProbeRate(FpsProbe probe) {
        final Integer f = probe.current();
        if (f == null || mPreviewRequestBuilder == null) return;
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(f, f));
        rebuildPreviewBuilder();
    }

    /** P266: a preview frame for the running probe: the next rate when this one is measured, the result after the last. */
    private void observeFpsProbe(CaptureResult result) {
        final FpsProbe probe = mFpsProbe;
        final Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (probe == null || ts == null) return;
        final long now = android.os.SystemClock.elapsedRealtimeNanos();
        if (!probe.frame(now, ts, mLastModeIndex)) return;
        final Handler handler = mBackgroundHandler;
        if (probe.next(now)) {
            if (handler != null) handler.post(() -> { if (mFpsProbe == probe) applyProbeRate(probe); });
            return;
        }
        mFpsProbe = null;
        final String slot = com.scamera.app.settings.ModuleRegistry.active();
        FpsProbe.keep(ScameraApp.getAppContext(), slot, probe.result());
        Log.i("SCAM_CAPTURE", "frame-rate probe, camera " + physicalID + " (asked -> delivered fps (sensor mode)): " + probe.result());
        com.scamera.app.util.UiMessages.info(Lang.t("Частоты кадров: ", "Frame rates: ") + probe.result());
        if (handler != null) handler.post(this::applyFpsSetting); // the module's own range back
    }

    /** P252: the lens the HAL's AF left at its rest stop (ParkedLens) and the distance the fallback holds (-1: none). */
    private final ParkedLens mParkedLens = new ParkedLens();
    private volatile float mParkedFocus = -1f;

    /** The repeating request runs AF off (a hand-set focus or the P252 fallback). */
    private boolean afOffInPreview() {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        final Integer af = b == null ? null : b.get(CaptureRequest.CONTROL_AF_MODE);
        return af != null && af == CaptureRequest.CONTROL_AF_MODE_OFF;
    }

    /** P252: the AF result of a preview frame through ParkedLens; takes the lens to the hyperfocal distance or gives it back. */
    private void observeParkedLens(CaptureResult result) {
        if (paramController.focusSet >= 0 || recordingVideo) return; // a hand-set focus, the video: theirs
        final CameraCharacteristics c = mCameraCharacteristics;
        if (c == null) return;
        final Float min = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        final Long exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        final Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        final double exposure = exposureNs == null || iso == null ? 0 : exposureNs / 1e9 * iso;
        final int act = mParkedLens.observe(ts != null ? ts : android.os.SystemClock.elapsedRealtimeNanos(),
                result.get(CaptureResult.CONTROL_AF_MODE), result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                result.get(CaptureResult.LENS_STATE), min == null ? 0f : min, exposure, result.get(CaptureResult.CONTROL_AF_STATE));
        if (act == ParkedLens.NOTHING) return;
        if (act == ParkedLens.YIELD) { mParkedFocus = -1f; return; }
        final Handler handler = mBackgroundHandler;
        final int generation = mSessionGeneration.get();
        if (act == ParkedLens.TAKE) {
            final float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            final float[] n = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            final android.util.SizeF size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            final Size pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            final float pitch = size == null || pixels == null || pixels.getWidth() <= 0 ? 0f : size.getWidth() / pixels.getWidth();
            final float target = ParkedLens.hyperfocalDiopters(f == null || f.length == 0 ? 0f : f[0], n == null || n.length == 0 ? 0f : n[0],
                    pitch, min == null ? 0f : min);
            mParkedFocus = target;
            Log.w("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "camera %s: the HAL's AF left the lens at %s dpt beyond its %.2f dpt"
                    + " near limit (exposure x ISO %.1f): hyperfocal %.3f dpt until the scene gets brighter (P252)", physicalID,
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE), min, exposure, target));
            if (handler != null) handler.post(() -> {
                if (generation != mSessionGeneration.get() || mPreviewRequestBuilder == null || mParkedFocus < 0f) return;
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mParkedFocus);
                rebuildPreviewBuilder();
            });
        } else {
            mParkedFocus = -1f;
            Log.i("SCAM_CAPTURE", "camera " + physicalID + ": the scene got brighter, the HAL's AF takes the lens again (P252)");
            if (handler != null) handler.post(() -> {
                if (generation != mSessionGeneration.get() || mPreviewRequestBuilder == null || mParkedFocus >= 0f) return;
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, PreferenceKeys.getAfMode());
                rebuildPreviewBuilder();
            });
        }
    }

    /** P110: the ISO the HAL honours for AE-off requests of this camera and RAW size (P27), MAX_VALUE while none is learned. */
    public int honouredIsoCap() {
        final ExposureLimits limits = mExposureLimits;
        return limits == null ? Integer.MAX_VALUE : limits.isoCap();
    }

    /**
     * P110: the repeating viewfinder request must not run AE off (P54c, X300 Ultra forced stabilisation: the HAL's preview
     * stabilisation path never sees an AE OFF request).
     */
    public boolean previewAeOffUnsafe() {
        return mForcedStab && ForcedStabilization.rawOnlySwitch();
    }

    /**
     * P641: the running dual-gain variant (Xiaomi 17 Ultra) did not prove itself: the session is configured again, with the next
     * variant (XiaomiDualGain.sessionStart) - after a shot in flight, never under it.
     */
    private void dualGainNextVariant() {
        final Handler handler = mBackgroundHandler;
        if (handler == null) return;
        final int generation = mSessionGeneration.get();
        handler.post(() -> {
            if (!isCameraResumed || generation != mSessionGeneration.get() || mCaptureSession == null) return;
            if (shotInFlight()) { mPendingPayloadRestart = true; return; }
            mCaptureSession.close();
            startSession(false);
        });
    }

    private void watchRawPayload(Image img) {
        // P641: a dual-gain session whose results changed depth: this frame is checked now (its payload may have changed)
        if (XiaomiDualGain.sessionActive() && XiaomiDualGain.takeRecheck()) mPayloadFrames = 0;
        int n = mPayloadFrames++;
        if (n >= 4 && n % 30 != 0) return;
        com.scamera.app.processing.RawPayloadCheck.Result payload =
                com.scamera.app.processing.RawPayloadCheck.check(img, rawPayloadWhite(null));
        OplusDcg.watchFrame(img.getFormat() == ImageFormat.RAW_SENSOR); // P593: a frame the depth watch can count on
        if (payload.plain()) {
            mPayloadBad = false;
            mPreviewLayouts.clear();
            // P641: a plain frame of a dual-gain session: the depth its samples proved (0: within the declared white)
            if (XiaomiDualGain.sessionActive() && img.getFormat() == ImageFormat.RAW_SENSOR) {
                XiaomiDualGain.onFrame(0, 0, com.scamera.app.processing.RawLevels.sampledBits(), mCameraCharacteristics);
                loficMainFrame(img, null); // P641d
            }
            return;
        }
        if (img.getFormat() == ImageFormat.RAW_SENSOR) {
            // Xiaomi 17 Ultra tele on the logical camera: packed MIPI RAW10 / RAW14 behind RAW_SENSOR, read in that layout by the
            // colour-block measurement and the developed RAW viewfinder (the forced 2x ISZ shows that viewfinder)
            com.scamera.app.processing.RawPayloadCheck.Layout layout = packedLayout(img);
            if (layout != null) {
                addPreviewLayout(layout);
                mPayloadBad = false;
                // P641: a packed frame of a dual-gain session: its packing's depth and its darkest samples
                if (XiaomiDualGain.sessionActive()) {
                    XiaomiDualGain.onFrame(layout.packedBits(), layout.low, 0, mCameraCharacteristics);
                    loficMainFrame(img, layout); // P641d
                }
                return;
            }
        }
        if (!mPayloadBad) {
            Log.w(TAG, "preview RAW of camera " + physicalID + ": " + payload.error
                    + (mScamPreviewActive ? " (vivo stock preview profile on)" : ""));
            com.scamera.app.processing.RawPayloadCheck.dumpOnce(img, payload, physicalID);
        }
        mPayloadBad = true;
        if (mScamPreviewActive && PlainPreviewCameras.add(physicalID) && mBackgroundHandler != null) {
            final int generation = mSessionGeneration.get();
            Log.w("SCAM_CAPTURE", "camera " + physicalID + ": vivo stock preview profile off, session restarted with the plain Camera2 preview");
            mBackgroundHandler.post(() -> {
                if (!isCameraResumed || generation != mSessionGeneration.get() || mCaptureSession == null) return;
                // P27: never under a shot in flight (its post-shutter RAWs are still routed): after it, from finishScamShot.
                if (shotInFlight()) { mPendingPayloadRestart = true; return; }
                mCaptureSession.close();
                startSession(false);
            });
        }
    }

    private void unspektraMeter(Image img, TotalCaptureResult matched) {
        if (!unspektraAe.eligible() || matched == null || burst || mShotInProgress || shotInFlight() || mPayloadBad
                || !mPreviewLayouts.isEmpty() || !isRawFormat(img.getFormat())) return;
        final long revision = unspektraAe.revision();
        try {
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            if (c == null) return;
            CaptureResult result = matched;
            if (Build.VERSION.SDK_INT >= 28) {
                CaptureResult physical = matched.getPhysicalCameraResults().get(physicalID);
                if (physical != null) result = physical;
            }
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (timestamp == null || timestamp != img.getTimestamp()) return;
            Integer arrangement = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            int cfa = mLiveCfaPattern >= 0 ? mLiveCfaPattern : arrangement == null ? -1 : arrangement;
            if (cfa < 0 || cfa > 3) return;
            com.scamera.app.processing.RawLevels.Levels levels =
                    liveLevels(c, result);
            android.hardware.camera2.params.RggbChannelVector wb = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            android.hardware.camera2.params.ColorSpaceTransform transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            if (wb == null || transform == null) return;
            float[] gains = {wb.getRed(), (wb.getGreenEven() + wb.getGreenOdd()) * .5f, wb.getBlue()};
            for (float gain : gains) if (!(gain > 0) || !Float.isFinite(gain)) return;
            float[] column = new float[9];
            for (int col = 0; col < 3; col++) for (int row = 0; row < 3; row++)
                column[col * 3 + row] = transform.getElement(col, row).floatValue();
            int block = stillOnlyFullRes() ? Math.max(1, com.scamera.app.processing.MosaicStream.block())
                    : nativeVivoFullRes() ? VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock
                    : mRecipePlan != null ? 1 // P635: the viewfinder's frames are the camera's own binned stream
                    : mFullResSession ? NubiaRemosaicProbe.fullResBlock(physicalID) // P605: 1 on the nubia remosaic route
                    : Math.max(PreferenceKeys.mosaicBlock(), com.scamera.app.processing.MosaicStream.block());
            float[] photo = viewfinderMatrix(c, result, gains, column, block > 1);
            if (photo == null) return;
            float[] rowMajor = NativeViewfinder.columnMajor(photo);
            java.nio.ByteBuffer samples = meterSamples(img);
            if (samples == null) return;
            double ev = UnspektraRawMeter.measure(samples, img.getWidth(), img.getHeight(), mMeterStride, cfa, block,
                    levels.black, levels.white, gains, rowMajor, meterSetup(c, result, null), unspektraAe.method());
            if (Double.isFinite(ev) && !unspektraAe.ownsExposure()) paramController.prepareUnspektraAe();
            if (unspektraAe.observe(result, ev, revision)) rebuildPreviewBuilder();
        } catch (RuntimeException error) {
            Log.w("SCAM_AE", "Unspektra RAW meter: " + error);
        }
    }

    private void onMatchedLiveRaw(Image img, TotalCaptureResult result) {
        boolean retained = false;
        java.nio.ByteBuffer ringView = null; // P365: a view of the frame's ring slot until the viewfinder takes it
        // P597: the camera thread's time of this frame, its gap to the frame before and its delivery lag (VF_RAW "stages")
        final long stageStart = System.nanoTime();
        try {
            if (!isCameraResumed || !mLiveRawSession || mZslCapturing || mHybridZslCapture || mScamRingFrozen) return;
            final long lag = android.os.SystemClock.elapsedRealtimeNanos() - img.getTimestamp();
            com.scamera.app.processing.VfStages.arrived(stageStart, lag >= 0 && lag < 2_000_000_000L ? lag : Long.MIN_VALUE);
            recipeViewFloor(img, result); // P635c: before every meter of the frame
            watchRawPayload(img);
            observeRawLevel(img, result);
            xiaomiRowFrame(img, result); // P631
            if (mRecipePlan == null) observeMosaic(img); // P635: the viewfinder's binned stream says nothing of the capture RAW's block
            plainMeter(img, result); // P212b: a plain session's sample for the native AE's start
            plainTeacher(img, result); // P212f: the mosaic white balance's teacher (log only)
            // P212a: the native AE's ISO probe is no viewfinder frame, no AE frame and no ring frame (the plan never sees it)
            final boolean probe = isNativeIsoProbe(result);
            // P238: at SEVERE the developed RAW viewfinder shows at most ThermalPolicy.SEVERE_VF_FPS frames a second (the AE, the
            // ring and the stream see every frame)
            final boolean shown = !probe && com.scamera.app.util.ThermalPolicy.viewfinderFrameDue(img.getTimestamp());
            // the ZSL ring's mode barrier (P41, below), decided before the viewfinder takes the frame (P365)
            // P635: a recipe's viewfinder frames (the binned stream) never enter the ring - the shot is the capture RAW's alone
            final boolean zslPass = isZslMode() && mRecipePlan == null && passModeBarrier(img, result);
            // P365 step 4 (owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»;
            // scam_dev vf_ring_ref): a shown frame the ring copies anyway as plain 16-bit reaches the viewfinder from its slot after
            // the ring copy (ringFrameView) instead of a copy of its own out of the camera's buffer; its parameters are read now,
            // while the Image is open
            final boolean viaRing = shown && zslPass && !mClosedForProcessing && ringRefFrame(img);
            final LiveRawFrame.Meta ringMeta = viaRing ? liveRawMeta(img, result) : null;
            if (shown && !viaRing) publishLiveRawFrame(img, result);
            if (!probe) unspektraMeter(img, result);
            if (!probe) nativeExposure(img, result);
            if (!probe) ettrMeter(img, result); // P356
            vivoAeAudit(img, result); // P212h: the vivo HAL AE against our RAW meter (log only)
            // P365 step 2 (scam_dev vf_one_request): a published frame asks for its draw itself (publishLiveRawFrame, the ring view
            // below); a second request made the GL thread draw the same frame again whenever it had begun the first
            if (mViewfinder != null && (probe || shown && !PreferenceKeys.scamDevSwitch("vf_one_request", true))) mViewfinder.requestRender();
            // P41: after an in-session sensor mode change (Xiaomi ISZ) frames of the old mode stay out of the ZSL ring
            // P187: not while a 200 MP merge runs (its plain session takes no shot)
            // P198: the native 50 MP FULLRES session rings its frames as well (the viewfinder keeps running from them), not while a
            // shot takes its frames (from the press until its series is complete: the ring holds the moment of the press)
            // P200: none while a FULLRES merge runs (its memory)
            // P238: none at SEVERE (ThermalPolicy): the ring's frames go, a press takes the series after the shutter
            final boolean ringIdle = nativeZslSession() && nativeRingPaused();
            final boolean nativeRing = !isZslMode() && mVivoSdkCapture == null && !mShotInProgress && !fullResMergeRunning() && !probe
                    && !ringIdle && nativeZslSession() && nativeRingDue(android.os.SystemClock.elapsedRealtimeNanos());
            if ((zslPass || nativeRing && nativeRingFrame(result)) && !mClosedForProcessing)
                synchronized (mRingLock) {
                if (!isCameraResumed || !mLiveRawSession || mRingClosing) return; // P662: mRingClosing
                final long timestamp = img.getTimestamp(), copyStart = android.os.SystemClock.elapsedRealtimeNanos();
                final RingFrame frame = ringFrameLocked(img); // P91: copied into the ring's memfd (img closed) or held as it is
                retained = true;
                final long copyNs = android.os.SystemClock.elapsedRealtimeNanos() - copyStart;
                LiveRawFrame.noteRingCopy(copyNs); // P365 step 1: VF_RAW "copy ... ring="
                if (nativeRing) nativeRingCopied(copyStart, copyNs);
                if (ringMeta != null) ringView = ringFrameView(frame, img, ringMeta);
                if (nativeRing && frame.arenaSlot() == null) {
                    // P198: never the camera's own image: the native reader holds 4, one held by the ring stalls the viewfinder
                    frame.close();
                    return;
                }
                mZslRingBuffer.addLast(frame);
                if (nativeRing) {
                    // the plain ZSL session keeps the results from the preview callback; the native one has them here, matched
                    mHexZslResults.put(timestamp, result);
                    while (mHexZslResults.size() > ringCapacityLocked() + 16)
                        mHexZslResults.remove(mHexZslResults.keySet().iterator().next());
                }
                while (mZslRingBuffer.size() > ringCapacityLocked()) {
                    RingFrame old = mZslRingBuffer.pollFirst();
                    if (old != null) old.close();
                }
            }
            if (zslPass || nativeRing) oldstyleViewfinder(); // P630e
            // P365 step 4: handed over outside the ring's lock (the view pins the slot whatever the ring does with its frame meanwhile)
            if (ringView != null) {
                final java.nio.ByteBuffer view = ringView;
                ringView = null;
                // takes the view (kept pinned or freed); P597: a frame that is binned first is published on the viewfinder's own
                // thread, and its draw is asked for there, once it is published
                LiveRawFrame.publishRef(view, ringMeta, () -> {
                    final GLPreview preview = mViewfinder;
                    if (preview != null) preview.requestRender();
                });
            }
        } finally {
            if (!retained) img.close();
            if (ringView != null) Allocator.free(ringView); // never handed over (an exception on the way)
            com.scamera.app.processing.VfStages.note(com.scamera.app.processing.VfStages.Stage.CAMERA,
                    System.nanoTime() - stageStart);
        }
    }

    /** P238: why the native ring rests now (null: it runs); its frames were dropped when it began. */
    private String mNativeRingPause;
    /** P239: the native ring's rate and rest (FullResRingPolicy). */
    private final FullResRingPolicy mRingPolicy = new FullResRingPolicy();

    /**
     * P238: the native 50 MP ring rests - at SEVERE (ThermalPolicy); P239 (speed-ux D1): also with no touch for
     * FullResRingPolicy.idleSeconds() and a still gyro. On the change its frames go (stale frames would make a later press's ZSL
     * frames older than the press by the pause), and the log says why; it runs again on the next frame after.
     */
    private boolean nativeRingPaused() {
        final long nowMs = android.os.SystemClock.elapsedRealtime();
        final int idleS = FullResRingPolicy.idleSeconds();
        int shake = -1;
        try { shake = ScameraApp.getGyro().getFilteredShakiness(); } catch (RuntimeException ignored) { }
        final boolean idle = mRingPolicy.idle(nowMs, shake, FullResRingPolicy.lastTouchMs(), idleS);
        final String why = com.scamera.app.util.ThermalPolicy.severe() ? "the phone is hot (thermal status SEVERE)"
                : idle ? "no touch for " + idleS + " s and the phone still" : null;
        if (java.util.Objects.equals(why, mNativeRingPause)) return why != null;
        final String before = mNativeRingPause;
        mNativeRingPause = why;
        if (why != null && !mShotInProgress && mVivoSdkCapture == null) {
            synchronized (mRingLock) {
                if (mZslRing != null && mZslRing.fullRes()) {
                    while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.pollFirst().close();
                    mHexZslResults.clear();
                }
            }
        }
        Log.i("SCAM_CAPTURE", why != null ? "50 MP ZSL ring paused: " + why + " (a press takes its frames after the shutter)"
                : "50 MP ZSL ring runs again (" + before + " is over)");
        return why != null;
    }

    private int mNativeRingRejected;
    /** P198: start and length of the last 50 MP ring copy, frames copied this session. */
    private long mNativeRingLastNs, mNativeRingCopyNs;
    private int mNativeRingCopies;

    /**
     * P198: a 96 MB copy out of the camera's buffer (uncached reads, ~2 GB/s a core, startZslCopy) can take longer than a frame of the
     * 30 fps native viewfinder, on the thread that also draws the viewfinder and runs the own AE: a frame is copied into the ring only
     * when at least twice the last copy's time has passed since the last one began (the copies take at most half of that thread).
     */
    static boolean ringCopyDue(long nowNs, long lastStartNs, long lastCopyNs) {
        return lastStartNs <= 0 || nowNs - lastStartNs >= 2 * lastCopyNs;
    }

    private boolean nativeRingDue(long nowNs) {
        // P239: and at most FullResRingPolicy.fps() copies a second (12: every second frame of 24 fps)
        return ringCopyDue(nowNs, mNativeRingLastNs, mNativeRingCopyNs)
                && FullResRingPolicy.rateDue(nowNs, mNativeRingLastNs, FullResRingPolicy.fps());
    }

    private void nativeRingCopied(long startNs, long copyNs) {
        mNativeRingLastNs = startNs;
        mNativeRingCopyNs = copyNs;
        if (++mNativeRingCopies == 1 || mNativeRingCopies % 150 == 0)
            Log.i("SCAM_CAPTURE", "50 MP ZSL ring: copy " + copyNs / 1_000_000 + " ms (" + mNativeRingCopies + " frames this session, ring "
                    + mZslRingBuffer.size() + ", at most " + FullResRingPolicy.fps() + " a second)");
    }

    /**
     * P198: a native frame may enter the 50 MP ZSL ring: its result does not report another sensor mode than the profile's (the rule
     * of the stills, VivoSdkFullResCapture.modeMatches); an OPPO result gets the own neutral, the plain session's colour transform
     * and shading map first (OppoNativeFullRes.complete), as a still's does before anything reads it.
     */
    private boolean nativeRingFrame(TotalCaptureResult result) {
        final VivoSdkFullRes.Profile profile = VivoSdkFullRes.profile(mVivoSdkCameraId);
        if (profile == null || result == null) return false;
        int[] mode = null;
        try { mode = result.get(SENSOR_MODE_INDEX); } catch (IllegalArgumentException ignored) { }
        if (!VivoSdkFullResCapture.modeMatches(profile, mode)) {
            if (++mNativeRingRejected == 1 || mNativeRingRejected % 120 == 0)
                Log.w("SCAM_CAPTURE", "50 MP ZSL: " + mNativeRingRejected + " frames of another sensor mode ("
                        + (mode == null || mode.length == 0 ? "none" : String.valueOf(mode[0])) + ", profile " + profile.sensorMode
                        + ") kept out of the ring");
            return false;
        }
        if (VivoSdkFullRes.oppo()) OppoNativeFullRes.completeFrame(mVivoSdkCameraId, result); // P212e: its white balance recorded
        return true;
    }

    /**
     * The crop the stream already holds (ZoomController.streamCrop): a module with vendor requests whose binned-size stream is a
     * Quad / Tetra mosaic reads the centre of the sensor at full resolution (ISZ by a vendor tag), so its zoom must not be
     * cropped again. {@code reapply}: re-issue the preview zoom when it changed (after the stream was measured).
     */
    private void updateStreamCrop(boolean reapply) {
        try {
            if (nativeVivoFullRes()) {
                if (com.scamera.app.control.ZoomController.setStreamCrop(1f) && reapply) onZoomChanged();
                return;
            }
            String slot = com.scamera.app.settings.ModuleRegistry.active();
            int block = com.scamera.app.processing.MosaicStream.block();
            boolean vendor = !com.scamera.app.settings.TunableKeyManager.signature(physicalID).isEmpty();
            float crop = XiaomiTeleZoom.phone() ? 1f : com.scamera.app.control.ZoomController.streamCropFor(block, vendor,
                    com.scamera.app.settings.ModuleRegistry.sensorCrop(slot),
                    com.scamera.app.settings.ModuleRegistry.sensorCropExplicit(slot), useMaximumResolutionKey);
            // P60: a measured field of view wins over the block rule (an explicit sensor-crop setting still wins over both)
            float measured = com.scamera.app.control.FovSelfCheck.get().sessionCrop();
            if (vendor && !XiaomiTeleZoom.phone() && !Float.isNaN(measured)
                    && com.scamera.app.settings.ModuleRegistry.sensorCropExplicit(slot) == null
                    && !com.scamera.app.settings.ModuleRegistry.sensorCrop(slot)) crop = measured;
            if (com.scamera.app.control.ZoomController.setStreamCrop(crop) && reapply) onZoomChanged();
        } catch (RuntimeException e) {
            Log.w(TAG, "stream crop: " + e.getMessage());
        }
    }

    /**
     * The frame verdict of a nubia maximum-resolution FULLRES session ({@code verdict}: MosaicStream's block of its own frames);
     * true when the session failed (the camera restarts). P605: the route takes a confident Bayer (block 1) at the maximum-resolution
     * size as the HAL's remosaic, a Quad as the sensor's own mosaic; another block or size fails its way. P598: a probe setting is
     * kept by a Quad, failed by anything else. P608: the verdict waits while the results report another mode than the full row
     * (NubiaRemosaicProbe.defer: the frames are the binned mode's; the mode watch fails the session, or the verdict is applied
     * once the row is reported).
     */
    private boolean nubiaMaxResVerdict(int verdict, int width, int height) {
        if (NubiaRemosaicProbe.defer(physicalID, verdict, width, height)) return false;
        if (NubiaRemosaicProbe.routeRunning(physicalID)) {
            final String routeWhy = NubiaRemosaicProbe.frames(physicalID, verdict, width, height);
            return routeWhy != null && retryWithoutFullRes(mCaptureSession, routeWhy);
        }
        if (verdict == 2) {
            final String kept = NubiaRemosaicProbe.quad(physicalID);
            if (kept != null) showToast(kept);
            return false;
        }
        return retryWithoutFullRes(mCaptureSession, verdict == 1
                ? "nubia: the maximum-resolution frames are remosaicked by the HAL (plain Bayer, no mosaic)"
                : "nubia: the maximum-resolution frames are a block " + verdict + " mosaic, not the sensor's Quad");
    }

    /** Key of the stream's sensor mode for MosaicStream: the physical sensor, its vendor requests and the RAW size. */
    /**
     * P631: the first frame of a picked Xiaomi row's session, described once (XiaomiSensorRow.frameLine): the mode the result
     * reports, the frame's floor and its 2x2 site statistics - a Quad mosaic, the HAL's remosaic, or a binned frame written twice.
     */
    private void xiaomiRowFrame(Image img, TotalCaptureResult result) {
        final SensorModePick.Pick pick = SensorModePick.session();
        if (pick == null || img == null || img.getFormat() != ImageFormat.RAW_SENSOR || !XiaomiSensorRow.takeFrameLog(physicalID, pick.mode)) return;
        try {
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            final float black = com.scamera.app.processing.RawLevels.current(c, null).meanBlack();
            Log.i("SCAM_CAPTURE", XiaomiSensorRow.frameLine(String.valueOf(physicalID), pick, SensorModeBand.reportedMode(result),
                    img.getPlanes()[0].getBuffer(), img.getWidth(), img.getHeight(), img.getPlanes()[0].getRowStride(), black));
            // P635b: the row's first frame against the truth check - where the buffer holds a picture, its pedestal, its grain
            final FullResTruth.Result truth = FullResTruth.check(img.getPlanes()[0].getBuffer(), img.getWidth(), img.getHeight(),
                    img.getPlanes()[0].getRowStride(), black);
            Log.i("SCAM_CAPTURE", truth.line("FULLRES ROW") + " (camera " + physicalID + " " + pick + ", P635b)");
            if (truth.notReal(true) && PreferenceKeys.scamDevSwitch("fullres_row_truth", true)) rowNotReal(pick, truth);
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "Xiaomi sensor row frame: " + e);
        }
    }

    private String mosaicStreamKey() {
        String size = mImageReaderRaw == null ? "" : mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight();
        return physicalID + "|" + size + "|" + com.scamera.app.settings.TunableKeyManager.signature(physicalID)
                + "|" + com.scamera.app.settings.ModuleRegistry.sensorMode(com.scamera.app.settings.ModuleRegistry.active())
                + (nativeVivoFullRes() ? "|native-full-" + VivoSdkFullRes.profile(mVivoSdkCameraId).sensorMode : "")
                // P315: a picked SensorModeTable row is a stream of its own (the X8U's mode 1 and its Quad mode 8 are both 4096x3072:
                // the block of one was used for the other for ~100 ms after each switch)
                + (SensorModePick.session() != null ? "|pick-" + SensorModePick.session().mode : "")
                // P598: each nubia remosaic-probe setting is a stream of its own
                + (mFullResSession ? NubiaRemosaicProbe.streamKey() : "")
                + (mRecipePlan != null ? "|recipe" : ""); // P635: the capture RAW of a recipe is a stream of its own
    }

    /** P214i: the frame check of this session's X300 Ultra ISZ module (VivoQcomIsz.check), null for any other session. */
    private volatile VivoQcomIsz.StreamCheck mIszCheck;

    /**
     * P214i: one measurement of an X300 Ultra ISZ module's stream (VivoQcomIsz.Trial). Frames that prove a plain Bayer (the camera
     * did not run the mode as its mosaic: build 30912's tele 5 / 7) on the default RAW stream restart the camera with the
     * CROPPED_RAW stream, as a FULLRES row the HAL remosaicked makes the next start try the next row (P188); a plain Bayer there
     * too falls back: said in the log and on the message card (once per firmware), the shots merge as Bayer
     * (VivoQcomIsz.declaredBlock 0: the stored block 1) and the button's zoom becomes a crop (ModuleRegistry.sensorCrop false: the
     * residual follows here). The session key stays, so a later session whose frames are the mosaic moves the trial on.
     */
    private void onIszFrames(VivoQcomIsz.StreamCheck check, com.scamera.app.processing.MosaicBlockDetector.Result r) {
        final VivoQcomIsz.StreamCheck.Verdict verdict = check.observe(r.confident, r.block, r.votes);
        if (verdict == VivoQcomIsz.StreamCheck.Verdict.NONE) return;
        final VivoQcomIsz.Outcome outcome = VivoQcomIsz.onVerdict(check, verdict, r.toString(), mCroppedRawSession);
        if (outcome.restart) {
            mainHandler().post(this::restartCamera);
            return;
        }
        if (outcome.zoom) {
            com.scamera.app.control.ZoomController.refreshResidual();
            onZoomChanged();
        }
        if (verdict == VivoQcomIsz.StreamCheck.Verdict.PLAIN && PreferenceKeys.isScamHybridEnabled()) try {
            // the merge of this session's shots is a Bayer one now: its programs built ahead (an optimisation only)
            com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.prewarmHybridGpu(ScameraApp.getAppContext(), 1);
        } catch (RuntimeException e) {
            Log.w(TAG, "Hybrid GPU prewarm skipped: " + e);
        }
        if (outcome.card != null) showError(outcome.card);
    }

    /**
     * P13: colour block of the ZSL stream from its first frames (MosaicStream: three agreeing measurements). A mosaic stream
     * switches the viewfinder to the developed RAW; its frames are published with the latest matching result (the colour
     * parameters of the viewfinder change slowly, the ZSL frames are not paired one by one outside the live RAW session).
     */
    private void observeMosaic(Image img) {
        try {
            if (!mMosaicMeasure || !isRawFormat(img.getFormat()) || !isCameraResumed || mPayloadBad) return;
            if (mRecipePlan != null) return; // P635: a recipe shot's block is measured on its own stills (the truth check, the merge)
            // P35: the measurement is the background check of the stored block: never while a shot takes its frames.
            if (!shotInFlight() && !mShotInProgress && com.scamera.app.processing.MosaicStream.wantsFrame()) {
                Image.Plane plane = img.getPlanes()[0];
                CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
                if (c == null) c = mCameraCharacteristics;
                // P127: the stream's levels at its bit depth (RawLevels)
                final com.scamera.app.processing.RawLevels.Levels levels =
                        com.scamera.app.processing.RawLevels.current(c, null);
                final float black = levels.meanBlack(), white = levels.white;
                java.nio.ByteBuffer samples = plane.getBuffer();
                int sampleStride = readStride(img);
                final int readFormat = readFormat(img);
                if (com.scamera.app.util.RawUnpack.isPacked(readFormat)) {
                    // RAW10 / RAW12: measured on the unpacked frame (only the first frames of a session are measured)
                    final int bytes = img.getWidth() * img.getHeight() * 2;
                    if (mMosaicUnpacked == null || mMosaicUnpacked.capacity() < bytes)
                        mMosaicUnpacked = java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
                    if (!com.scamera.app.util.RawUnpack.unpack(samples.duplicate(), readFormat, img.getWidth(), img.getHeight(),
                            sampleStride, mMosaicUnpacked)) return;
                    samples = mMosaicUnpacked.duplicate().order(java.nio.ByteOrder.nativeOrder());
                    samples.position(0); samples.limit(bytes);
                    sampleStride = img.getWidth() * 2;
                }
                // P608: a binned frame packed into the nubia's maximum-resolution buffer (or an in-sensor-zoom crop) fails the
                // session from its rows alone - MosaicStream took the quarter of rows that hold it for a «confident plain Bayer»
                // P626: what the first full-size frame of the session fills (the log tells binned from full at a glance)
                if (NubiaFullRes.device() && mFullResSession && NubiaRemosaicProbe.fillOnce(physicalID))
                    Log.i("SCAM_CAPTURE", NubiaFullRes.fillLine(physicalID, NubiaRemosaicProbe.streamKey().replace("|probe-", "way "), img.getWidth(), img.getHeight(),
                            sampleStride, NubiaFullRes.pictureRows(samples, img.getWidth(), img.getHeight(), sampleStride, black)));
                if (NubiaFullRes.device() && mFullResSession && NubiaRemosaicProbe.running(physicalID)
                        && NubiaFullRes.partialFrame(samples, img.getWidth(), img.getHeight(), sampleStride, black)
                        && retryWithoutFullRes(mCaptureSession, NubiaFullRes.partialWhy(img.getWidth(), img.getHeight(),
                        NubiaFullRes.maxResModeState(physicalID) == 1))) return;
                // P631: a picked Xiaomi row's stream came without its black pedestal: measured over the frame's own floor (the
                // declared black of 64 left every dark tile out - no answer in 12 frames, log of 31256)
                final float detectorBlack = SensorModePick.session() != null && XiaomiSensorRow.device()
                        ? XiaomiSensorRow.detectorBlack(black, XiaomiSensorRow.frameFloor(samples, img.getWidth(), img.getHeight(), sampleStride))
                        : black;
                final com.scamera.app.processing.MosaicBlockDetector.Result measured =
                        com.scamera.app.processing.MosaicBlockDetector.detect(samples, img.getWidth(), img.getHeight(),
                                sampleStride, detectorBlack, white, 32);
                int block = com.scamera.app.processing.MosaicStream.observe(measured);
                // P187: measured - the unpacked frame (2 B a pixel, 401 MB on a packed 200 MP stream) is not kept for the
                // rest of the session
                if (!com.scamera.app.processing.MosaicStream.wantsFrame()) mMosaicUnpacked = null;
                // P214i: an X300 Ultra ISZ module's frames against the mosaic its mode declares (before the stream crop below,
                // which reads the module's sensor crop)
                final VivoQcomIsz.StreamCheck iszCheck = mIszCheck;
                if (iszCheck != null && measured != null) onIszFrames(iszCheck, measured);
                updateStreamCrop(true);
                // P575: a nubia row declared a mosaic (its full size beside the binned one) whose frames are a plain Bayer is the
                // HAL's remosaic: not used (FULLRES off / the module on Auto, with a notice), the normal session opens
                // (this session's own verdict: a stored block 1 of the row would fail every retry at once)
                if ((NubiaFullRes.device() || SensorModePick.session() != null && SensorModePick.session().rawOnly()) // P586
                        && com.scamera.app.processing.MosaicStream.sessionVerdict() == 1
                        && SensorModePick.session() != null
                        && SensorModePick.sessionBlock(c) > 1
                        && retryWithoutRequestedSensorMode(mCaptureSession, "the frames are remosaicked by the HAL (plain Bayer, no mosaic)")) return;
                // P588: the nubia's maximum-resolution FULLRES whose frames are a plain Bayer is the HAL's remosaic. P598: that
                // session's setting against the remosaic failed (NubiaRemosaicProbe: the next setting in the next session, every one
                // failed - FULLRES off on this firmware with one notice); a confident Quad keeps the setting for the camera
                if (NubiaFullRes.device() && mFullResSession) {
                    final int verdict = com.scamera.app.processing.MosaicStream.sessionVerdict();
                    if (verdict != 0 && nubiaMaxResVerdict(verdict, img.getWidth(), img.getHeight())) return;
                }
                // P188 (owner, 2026-10-17: «Нам нужен фулрез без ремозаика»): a native FULLRES row whose frames are a plain Bayer
                // was remosaicked by the HAL (X300 Ultra main camera, 50 MP row 2): that row counts as failed and the next start
                // tries the next row of the table, instead of shooting the HAL's remosaic
                if (block == 1 && nativeVivoFullRes()) {
                    final VivoSdkFullRes.Profile full = VivoSdkFullRes.profile(mVivoSdkCameraId);
                    // P208: not the still-only route, whose viewfinder is the normal mode's Bayer
                    if (full != null && full.nativeBlock > 1 && !full.stillOnly
                            && retryWithoutVivoSdkFullRes(mCaptureSession, "the frames are remosaicked by the HAL (plain Bayer, no mosaic)")) return;
                }
                // P586: the OnePlus 16's 200 MP row (declared 4x4) whose frames are a confident Quad: accepted as a Quad (noted for
                // this firmware), the session restarts with block 2
                if (block == 2 && nativeVivoFullRes() && VivoSdkFullRes.oppo()
                        && com.scamera.app.processing.MosaicStream.sessionVerdict() == 2) {
                    final VivoSdkFullRes.Profile full = VivoSdkFullRes.profile(mVivoSdkCameraId);
                    if (full != null && full.nativeBlock == 4 && OppoNativeFullRes.acceptQuad(mVivoSdkCameraId, full)) {
                        mainHandler().post(this::restartCamera);
                        return;
                    }
                }
                // P194: the other way round, a hardware-remosaic row (plain Bayer expected) whose frames are a Quad / Tetra mosaic is
                // no hardware remosaic: noted, its camera takes the own mosaic from the next start
                if (block > 1 && nativeVivoFullRes()) {
                    final VivoSdkFullRes.Profile full = VivoSdkFullRes.profile(mVivoSdkCameraId);
                    if (full != null && full.nativeBayer() && !full.stillOnly // P684: a still-only viewfinder is the normal mode's
                            && retryWithoutVivoSdkFullRes(mCaptureSession, "the hardware-remosaic row gives a block " + block + " mosaic, not a Bayer")) return;
                }
                if (block > 1 && !mMosaicPreview && !mLiveRawSession && !XiaomiTeleZoom.ispPreview()) {
                    mMosaicPreview = true;
                    LiveRawFrame.setEnabled(true);
                    LiveRawFrame.setMosaicPreview(true);
                    Log.i(TAG, "mosaic stream (block " + block + "): developed RAW viewfinder");
                } else if (block == 1 && mMosaicPreview) {
                    mMosaicPreview = false;
                    LiveRawFrame.setMosaicPreview(false);
                    LiveRawFrame.setEnabled(false);
                }
            }
            if (mMosaicPreview && !mZslCapturing && !mHybridZslCapture && !mScamRingFrozen) {
                TotalCaptureResult r;
                synchronized (mRingLock) { r = mHexZslResults.get(img.getTimestamp()); }
                CaptureResult latest = mPreviewCaptureResult;
                if (r == null && latest instanceof TotalCaptureResult) r = (TotalCaptureResult) latest;
                publishLiveRawFrame(img, r);
                // P365 step 2 (vf_one_request): publishLiveRawFrame asked for the draw of a frame it published
                if (mViewfinder != null && !PreferenceKeys.scamDevSwitch("vf_one_request", true)) mViewfinder.requestRender();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "mosaic preview: " + e.getMessage());
        }
    }

    /** P192: the own exposure of the OPPO native FULLRES session (null otherwise); the PHY110's frame time (24 fps). */
    private volatile NativeRawExposure mNativeAe;
    static final long NATIVE_FRAME_NS = 41_666_666L;
    private int mNativeFrames;
    private long mNativeAeLogMs;
    /** P212a / b: the native session's start, since when its AE holds, when it was last stored (elapsedRealtime ms; 0: not). */
    private long mNativeAeStartMs, mNativeAeSettledMs, mNativeAeStoredMs;
    private double mNativeAeStoredTotal;
    /** P212c: the unthrottled trace of the native AE (scam_dev.txt "native_ae_trace 1"), one line a frame, flushed once a second. */
    private final StringBuilder mNativeAeTrace = new StringBuilder();
    private long mNativeAeTraceMs;
    /**
     * P212c: the gains (copyGainFactors order) of the shading map the meter last read, reused while the results carry the same. P212z:
     * the same factors and size (meterGains), not the same map object (a result builds a new one on every get); the map's size and
     * the copy buffer a frame's factors are read into.
     */
    private float[] mMeterGains;
    private int mMeterCols, mMeterRows;
    private final float[][] mMeterScratch = new float[1][];
    /** P212b: the last plain-session sample for the native AE's start (elapsedRealtime ms). */
    private long mPlainMeterMs;
    /**
     * P212a: the native AE's ISO probe request: its frame stays out of the viewfinder, the AE and the 50 MP ring. P212z (review): the
     * request object itself (a result's getRequest() is the request submitted), no longer a tag set on the shared preview builder for
     * the build - a rebuild from another thread in between (a WB dial, a tap, a zoom step) took the tag into the repeating request,
     * and every frame after it was a "probe": no RAW viewfinder, no AE, an empty native ring until the next UI rebuild.
     */
    private volatile CaptureRequest mNativeIsoProbeRequest;

    /**
     * P212d: the native AE's flicker is known - set by hand, or «Авто» latched from evidence (MainsFlicker.known) - or scam_dev.txt
     * "native_ae_flicker_both 0" snaps to the region's guess as before; false: long shutters band least at both frequencies.
     */
    private static boolean nativeFlickerKnown() {
        return PreferenceKeys.antibandingKnown() || !PreferenceKeys.scamDevSwitch("native_ae_flicker_both", true);
    }

    /**
     * P192: the OPPO native mode's own AE and white balance from the frame just shown: the frame is metered and the exposure moved
     * (the repeating request rebuilt) when it is off the setpoint; every 8th frame the neutral is measured (MosaicNeutral, on the
     * camera's curve). Not while a series is taken: its frames keep the exposure of the shutter.
     * P212: the ISO cap the mode delivers before the update (A1), the settled exposure stored for the next start (A2), the meter of
     * NativeRawExposure.meter2 (A4: crop, centre / tap weights, the result's shading map, per-site clipping), the trace.
     */
    private void nativeExposure(Image img, TotalCaptureResult result) {
        final NativeRawExposure ae = mNativeAe;
        if (ae == null || img == null || result == null || mVivoSdkCapture != null || burst || !nativeVivoFullRes()) return;
        try {
            final Image.Plane plane = img.getPlanes()[0];
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            final com.scamera.app.processing.RawLevels.Levels levels =
                    liveLevels(c, result);
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            final int block = VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock;
            final java.nio.ByteBuffer buffer = plane.getBuffer();
            // P212c (scam_dev.txt "native_ae_meter 0": the P192 meter of the whole frame)
            final NativeRawExposure.Metered metered = PreferenceKeys.scamDevSwitch("native_ae_meter", true)
                    ? NativeRawExposure.meter2(buffer, img.getWidth(), img.getHeight(), plane.getRowStride(), cfa, block, levels.black,
                    levels.white, meterSetup(c, result, OppoNativeFullRes.neutral(mVivoSdkCameraId))) : null;
            final float[] m = metered != null ? new float[]{metered.p50, metered.p98}
                    : NativeRawExposure.meter(buffer, img.getWidth(), img.getHeight(), plane.getRowStride(), cfa, block, levels.black, levels.white);
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            // P212a: the ISO the mode really delivers (ExposureLimits, learned from these frames and stored): no request above it
            // (they came back at ISO 1600, 2 EV short, and were rebuilt every 6 frames); first, so a hand-set ISO stops there too
            final boolean capOn = PreferenceKeys.scamDevSwitch("native_ae_cap", true);
            if (!unspektraAe.ownsExposure() && ae.isoCap(capOn ? honouredIsoCap() : Integer.MAX_VALUE)) {
                Log.i("SCAM_CAPTURE", "native AE camera " + mVivoSdkCameraId + ": ISO cap "
                        + (ae.isoCap() == Integer.MAX_VALUE ? "-" : String.valueOf(ae.isoCap())) + " -> " + ae);
                rebuildPreviewBuilder();
            }
            // P201: a hand-set ISO / shutter holds (both: no metering, one: the other follows)
            if (!unspektraAe.ownsExposure() && ae.setManual(paramController.manualIsoValue(), paramController.manualPreviewShutterNs())) {
                Log.i("SCAM_CAPTURE", "native AE camera " + mVivoSdkCameraId + ": manual -> " + ae);
                rebuildPreviewBuilder();
            }
            // P209: whole periods of the place's lamp flicker («Частота мерцания»; Auto: latched from the camera's report)
            // P212d: «Авто» without evidence (the PHY110's report is a placeholder: MainsFlicker.known false) is the region's guess, and
            // a shutter from 30 ms takes the one of 30 / 33.3 / 40 / 41.7 ms that bands least at both 100 and 120 Hz
            final int flickerHz = PreferenceKeys.getAntibandingHz();
            final boolean flickerKnown = nativeFlickerKnown();
            if (!unspektraAe.ownsExposure() && ae.flicker(flickerHz, flickerKnown)) {
                Log.i("SCAM_CAPTURE", "native AE camera " + mVivoSdkCameraId + ": flicker " + flickerHz + " Hz ("
                        + (flickerKnown ? "known" : MainsFlicker.source() + ", both bands from 30 ms") + ") -> " + ae);
                rebuildPreviewBuilder();
            }
            final float ev = nativeAeEv(c);
            final boolean changed = !unspektraAe.ownsExposure() && m != null && exposure != null && iso != null && ae.update(m[0], m[1], exposure, iso, ev);
            final long now = android.os.SystemClock.elapsedRealtime();
            if (changed) {
                if (now - mNativeAeLogMs >= 1000) {
                    mNativeAeLogMs = now;
                    Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT,
                            "native AE camera %s: median %.4f p98 %.3f%s at %.3f ms ISO %d (ev %+.1f) -> %s%s",
                            mVivoSdkCameraId, m[0], m[1], metered == null ? "" : String.format(java.util.Locale.ROOT, " clip %.4f", metered.clip),
                            exposure / 1e6, iso, ev, ae, ae.deficitEv() > 0.05 ? String.format(java.util.Locale.ROOT,
                                    ", %.2f EV short of the meter (cap %s)", ae.deficitEv(),
                                    ae.isoCap() == Integer.MAX_VALUE ? "-" : String.valueOf(ae.isoCap())) : ""));
                }
                rebuildPreviewBuilder();
            }
            // P212b: a settled exposure (a second inside the band or at the deliverable limit) is the next session's start
            if (!unspektraAe.ownsExposure() && exposure != null && iso != null && ae.settled() && !ae.handSet()) {
                if (mNativeAeSettledMs == 0) mNativeAeSettledMs = now;
                else if (now - mNativeAeSettledMs >= NativeAeSeed.SETTLED_HOLD_MS) {
                    final double delivered = (double) exposure * iso / 100.0;
                    if (NativeAeSeed.storeDue(now, mNativeAeStoredMs, delivered, mNativeAeStoredTotal)) {
                        mNativeAeStoredMs = now;
                        mNativeAeStoredTotal = delivered;
                        NativeAeSeed.storeSettled(mVivoSdkCameraId, delivered, ev, System.currentTimeMillis());
                    }
                }
            } else mNativeAeSettledMs = 0;
            // P212a: once a session, a request above the cap shows whether the firmware gives more gain after all (never while a
            // shot is pending: its frame would be in the ring; isNativeIsoProbe keeps it out of the ring and the AE anyway)
            if (!unspektraAe.ownsExposure() && capOn && !mShotInProgress && !shotInFlight() && !fullResMergeRunning()) {
                final int probe = ae.probeIso(now - mNativeAeStartMs);
                if (probe > 0) sendNativeIsoProbe(ae, probe);
            }
            if (PreferenceKeys.scamDevSwitch("native_ae_trace", false)) traceNativeAe(ae, metered, m, exposure, iso, ev, changed, now);
            final android.hardware.camera2.params.RggbChannelVector wb = paramController.manualWhiteBalanceGains();
            final boolean wbFilter = OppoNativeWb.on();
            if (wb != null) {
                // P201: a hand-set white balance is the shot's neutral (the inverse of its gains), not the own estimate
                final float g = 0.5f * (wb.getGreenEven() + wb.getGreenOdd());
                if (wb.getRed() > 0f && wb.getBlue() > 0f) {
                    if (wbFilter) OppoNativeWb.hand(mVivoSdkCameraId, new float[]{g / wb.getRed(), 1f, g / wb.getBlue()});
                    else OppoNativeFullRes.setNeutral(mVivoSdkCameraId, g / wb.getRed(), g / wb.getBlue()); // P212f: not averaged
                }
            } else if (wbFilter) {
                // P212e (W3 = M7): the session's gated filter - measured every 8th frame while the AE holds (nothing pending, no
                // rebuild in its last 3 frames) and the frame is neither dark nor clipped; a gated frame waits for the next one
                OppoNativeWb.hand(mVivoSdkCameraId, null);
                final boolean alternative = unspektraAe.ownsExposure();
                final boolean settled = alternative ? unspektraAe.stable() : ae.settled();
                if (OppoNativeWb.wants(mVivoSdkCameraId, alternative ? !settled : ae.pending(), alternative ? (settled ? 3 : 0) : ae.sinceRebuild(), settled, m == null ? -1f : m[0],
                        m == null ? -1f : m[1], metered == null ? -1f : metered.clip)) {
                    final com.scamera.app.processing.color.MosaicNeutral.Result own =
                            nativeOwnNeutral(buffer, img, plane, cfa, levels, block, c, result);
                    final String how = OppoNativeWb.measure(mVivoSdkCameraId, own == null ? null : own.neutral, own == null ? 0 : own.cells,
                            settled, now);
                    if (how != null) Log.i("SCAM_CAPTURE", "native white balance camera " + mVivoSdkCameraId + ": "
                            + (own == null ? "too few usable cells" : own.how + ", " + own.cells + " cells") + how);
                }
            } else if ((mNativeFrames++ & 7) == 0) {
                final com.scamera.app.processing.color.MosaicNeutral.Result own =
                        nativeOwnNeutral(buffer, img, plane, cfa, levels, block, c, result);
                if (own != null) OppoNativeFullRes.measuredNeutral(mVivoSdkCameraId, own.neutral[0], own.neutral[2]);
                if ((mNativeFrames & 127) == 1 && own != null) Log.i("SCAM_CAPTURE", "native white balance camera " + mVivoSdkCameraId + ": " + own.how);
            }
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "native AE: " + e);
        }
    }

    /**
     * P212f: the own neutral of a native frame: the P121 statistic on the camera's locus (MosaicNeutral, the DNG prior for a curve
     * of fewer than 2 points); with scam_dev.txt "mosaic_awb_v2 1" the robust statistic (W4 = M10: the viewfinder's crop, the
     * result's shading map - else the stored plain one - and noise profile, the RawLevels black). Null: too few usable cells.
     */
    @Nullable
    private com.scamera.app.processing.color.MosaicNeutral.Result nativeOwnNeutral(java.nio.ByteBuffer buffer, Image img,
            Image.Plane plane, int cfa, com.scamera.app.processing.RawLevels.Levels levels, int block,
            @Nullable CameraCharacteristics c, TotalCaptureResult result) {
        com.scamera.app.processing.color.MosaicNeutral.init(ScameraApp.getAppContext());
        final com.scamera.app.processing.color.MosaicNeutral.Prior prior =
                com.scamera.app.processing.color.MosaicNeutral.prior(c, mVivoSdkCameraId);
        if (!PreferenceKeys.scamDevSwitch("mosaic_awb_v2", false))
            return com.scamera.app.processing.color.MosaicNeutral.estimate(mVivoSdkCameraId, buffer,
                    img.getWidth(), img.getHeight(), plane.getRowStride(), cfa, levels.black, levels.white, block, prior);
        final com.scamera.app.processing.color.MosaicNeutral.Stat stat =
                com.scamera.app.processing.color.MosaicNeutral.measure2(buffer, img.getWidth(), img.getHeight(),
                        plane.getRowStride(), cfa, levels.black, levels.white, block, mosaicSetup(c, result));
        return stat == null ? null : com.scamera.app.processing.color.MosaicNeutral.resolve(mVivoSdkCameraId, stat, prior);
    }

    /** P212f (W4): what MosaicNeutral.measure2 reads of a viewfinder frame: the meter's crop and shading map, the noise profile. */
    private com.scamera.app.processing.color.MosaicNeutral.Setup mosaicSetup(@Nullable CameraCharacteristics c,
                                                                                           @Nullable CaptureResult result) {
        final NativeRawExposure.MeterSetup m = meterSetup(c, result, null);
        final com.scamera.app.processing.color.MosaicNeutral.Setup s =
                new com.scamera.app.processing.color.MosaicNeutral.Setup();
        s.left = m.left;
        s.top = m.top;
        s.right = m.right;
        s.bottom = m.bottom;
        s.gains = m.gains;
        s.gainCols = m.gainCols;
        s.gainRows = m.gainRows;
        final Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
        return s.noise(result == null ? null : result.get(CaptureResult.SENSOR_NOISE_PROFILE), iso == null ? 100 : iso);
    }

    /**
     * P212g (W6 = M6): the press's route for the mosaic white balance (MosaicNeutralResolver): a vivo native FULLRES session (its
     * HAL 3A runs), on P208's still-only route with the median of the normal-mode viewfinder's converged neutrals of the last second;
     * null for any other shot (the OPPO native route and the 12 MP ISZ modules keep P121).
     */
    @Nullable
    private com.scamera.app.processing.color.MosaicNeutralResolver.Press mosaicWbAtPress() {
        try {
            // P408: «Баланс белого Quad / Tetra» as the reference app - the live stream's last result at the press
            if (com.scamera.app.processing.color.MosaicNeutralResolver.whyMode()) {
                // P599: with the viewfinder's proof that the HAL's AWB runs in this session (a still burst looks frozen)
                final String vfId = physicalID == null ? null : physicalID.substring(physicalID.lastIndexOf('/') + 1);
                final com.scamera.app.processing.color.MosaicNeutralResolver.Press p =
                        mVfNeutrals.why(physicalID, android.os.SystemClock.elapsedRealtime()).awbMoves(mVfMosaicWb.awbMoves(vfId, mVfCcmSession));
                Log.i("SCAM_CAPTURE", "mosaic white balance at the press: " + p);
                return p;
            }
            // P315: an OPPO-family module's picked sensor mode (the X8U's 2x ISZ): the HAL's neutral when it is valid
            if (mVivoSdkRawSession == null && SensorModePick.session() != null && SensorModePick.brand() == SensorModePick.Brand.OPLUS)
                return com.scamera.app.processing.color.MosaicNeutralResolver.Press.sensorMode();
            if (mVivoSdkRawSession == null || VivoSdkFullRes.oppo() || !nativeVivoFullRes()) return null;
            if (stillOnlyFullRes()) {
                final com.scamera.app.processing.color.MosaicNeutralResolver.Press p =
                        mVfNeutrals.stillOnly(physicalID, android.os.SystemClock.elapsedRealtime());
                Log.i("SCAM_CAPTURE", "mosaic white balance at the press: " + p);
                return p;
            }
            return com.scamera.app.processing.color.MosaicNeutralResolver.Press.vivoNative();
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "mosaic white balance at the press: " + e);
            return null;
        }
    }

    /**
     * P212g (W7 = M12): a plain-Bayer session's converged result (AWB AUTO and CONVERGED, block 1, no native session; at most once a
     * second a camera) teaches PlainColourMemory its neutral, the ISP's matrix - COLOR_CORRECTION_TRANSFORM when the ISP branch of
     * Parameters takes it, else the identity's OPPO ISP matrix com.oplus.custom.ccm (Parameters.oplusCcmInstead's rule) - and the
     * AWB label AWBFrameControlCCT (not the 3745 K init signature). Any brand; a mosaic photo of the camera reads it.
     */
    private void plainColour(TotalCaptureResult result) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (!com.scamera.app.processing.color.PlainColourMemory.due(physicalID, now)) return;
        try {
            // P315: never a picked sensor mode's results (its matrix is the mode's, not the plain mode's)
            if (com.scamera.app.processing.MosaicStream.block() != 1 || OppoNativeFullRes.running()
                    || SensorModePick.session() != null) return;
            final Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE), state = result.get(CaptureResult.CONTROL_AWB_STATE);
            if (awb == null || awb != CaptureResult.CONTROL_AWB_MODE_AUTO || state == null || state != CaptureResult.CONTROL_AWB_STATE_CONVERGED)
                return;
            final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n == null || n.length < 3 || !(n[1].floatValue() > 0f)) return;
            final android.hardware.camera2.params.ColorSpaceTransform cst = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            if (cst == null) return;
            final float[] m = new float[9];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r * 3 + c] = cst.getElement(c, r).floatValue();
            float[] ccm = null;
            if (com.scamera.app.processing.render.Parameters.ispCcmUsable(cst)) ccm = m;
            else if (PreferenceKeys.scamDevSwitch("oplus_ccm", true) && com.scamera.app.processing.color.OplusCcm.identity(m)) {
                try {
                    ccm = com.scamera.app.processing.color.OplusCcm.parse(result.get(new CaptureResult.Key<>(
                            com.scamera.app.processing.color.OplusCcm.TAG_NAME, byte[].class)));
                } catch (RuntimeException noTag) {
                    ccm = null;
                }
            }
            if (ccm == null) return;
            int[] label;
            try { label = result.get(AWB_CCT_LABEL); } catch (RuntimeException noTag) { label = null; }
            com.scamera.app.processing.color.PlainColourMemory.offer(physicalID, n[0].floatValue() / n[1].floatValue(),
                    n[2].floatValue() / n[1].floatValue(), ccm, com.scamera.app.processing.color.PlainColourMemory.label(label), now);
        } catch (RuntimeException ignored) {
        }
    }

    /** P212f (W5 = M13 phase 2): the last plain teacher sample (elapsedRealtime ms). */
    private long mPlainTeacherMs;
    static final long PLAIN_TEACHER_MS = 1000L;

    /**
     * P212f (W5 = M13 phase 2, log only): a plain-Bayer session's RAW frame about once a second teaches the mosaic white balance
     * (MosaicTeacher): our statistic of the frame (MosaicNeutral.measure2, block 1: the result's shading map and noise profile,
     * the viewfinder's crop) beside the ISP's neutral of the same result - the gates of ParamController.learnNeutral (block 1, AWB
     * AUTO converged / locked, no native session). Any brand; nothing a photo uses (scam_dev.txt "mosaic_awb_teacher 0": none).
     */
    private void plainTeacher(Image img, @Nullable TotalCaptureResult result) {
        // P365 (review): a RAW10 / RAW12 stream's frame too, unpacked (meterSamples)
        if (mVivoSdkRawSession != null || img == null || !isRawFormat(img.getFormat()) || mPayloadBad || !mPreviewLayouts.isEmpty())
            return;
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - mPlainTeacherMs < PLAIN_TEACHER_MS) return;
        try {
            if (com.scamera.app.processing.MosaicStream.block() != 1 || OppoNativeFullRes.running()
                    || SensorModePick.session() != null // P315: a picked mode's frame is no plain one (the X8U's Quad mode 8)
                    || !com.scamera.app.processing.color.MosaicTeacher.on()) return;
            if (result == null) synchronized (mRingLock) { result = mHexZslResults.get(img.getTimestamp()); }
            if (result == null) return;
            mPlainTeacherMs = now;
            final Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE), state = result.get(CaptureResult.CONTROL_AWB_STATE);
            if (awb == null || awb != CaptureResult.CONTROL_AWB_MODE_AUTO) return;
            if (state != null && state != CaptureResult.CONTROL_AWB_STATE_CONVERGED && state != CaptureResult.CONTROL_AWB_STATE_LOCKED) return;
            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n == null || n.length < 3 || !(n[1].floatValue() > 0f)) return;
            final float nr = n[0].floatValue() / n[1].floatValue(), nb = n[2].floatValue() / n[1].floatValue();
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            if (cfa > 3) return;
            final com.scamera.app.processing.RawLevels.Levels levels =
                    liveLevels(c, result);
            final java.nio.ByteBuffer samples = meterSamples(img);
            if (samples == null) return;
            final com.scamera.app.processing.color.MosaicNeutral.Stat m =
                    com.scamera.app.processing.color.MosaicNeutral.measure2(samples, img.getWidth(), img.getHeight(),
                            mMeterStride, cfa, levels.black, levels.white, 1, mosaicSetup(c, result));
            if (m == null) return;
            String id = physicalID;
            if (id == null) return;
            id = id.substring(id.lastIndexOf('/') + 1);
            com.scamera.app.processing.color.MosaicTeacher.init(ScameraApp.getAppContext());
            final com.scamera.app.processing.color.MosaicTeacher.Prediction before =
                    com.scamera.app.processing.color.MosaicTeacher.observe(id, m.r, m.b, nr, nb);
            final int seen = com.scamera.app.processing.color.MosaicTeacher.seen(id);
            if (seen <= 3 || seen % 30 == 0)
                Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT,
                        "WB TEACHER camera %s: m %.3f / %.3f (sd %.4f, %d cells) -> ISP %.3f / %.3f, predicted before %s%s [%d pairs, %d cells]",
                        id, m.r, m.b, m.sigma(), m.cells, nr, nb, before == null ? "-" : before.toString(),
                        before == null ? "" : String.format(java.util.Locale.ROOT, " (%.3f ln off)",
                                Math.hypot(Math.log(before.r / nr), Math.log(before.b / nb))),
                        seen, com.scamera.app.processing.color.MosaicTeacher.cells(id)));
        } catch (RuntimeException e) {
            mPlainTeacherMs = now;
        }
    }

    /** P212c: one trace line a metered frame, written once a second (one log entry). */
    private void traceNativeAe(NativeRawExposure ae, @Nullable NativeRawExposure.Metered metered, @Nullable float[] m, Long exposure,
                               Integer iso, float ev, boolean changed, long now) {
        if (m != null) {
            mNativeAeTrace.append('\n').append(String.format(java.util.Locale.ROOT,
                    "%d p50 %.5f p98 %.3f%s | ev %+.1f del %s ms ISO %s | %s%s", now - mNativeAeStartMs, m[0], m[1],
                    metered == null ? "" : String.format(java.util.Locale.ROOT, " P %.3f C %.4f lm %.5f%s law2 x%.3f now x%.3f",
                            metered.peak, metered.clip, metered.logMean, metered.shaded ? " lsc" : "",
                            NativeRawExposure.clipBudget(metered, ev), NativeRawExposure.multiplier(metered.p50, metered.p98, ev)),
                    ev, exposure == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", exposure / 1e6), iso, ae.state(),
                    changed ? " REBUILD" : ""));
        }
        if (now - mNativeAeTraceMs >= 1000 && mNativeAeTrace.length() > 0) {
            mNativeAeTraceMs = now;
            Log.i("SCAM_AE", "native AE trace camera " + mVivoSdkCameraId + ":" + mNativeAeTrace);
            mNativeAeTrace.setLength(0);
        }
    }

    /** P356: the ETTR boost of this session (capture/Ettr). */
    private final Ettr mEttr = new Ettr();
    /** P356: why the boost is held now (logged once a change), null while it runs. */
    private String mEttrHeld = "off";
    /** P377: the ISO was set by hand at the last meter (a change starts the boost over). */
    private boolean mEttrIsoByHand;
    /** P377: the boost or why there is none, for the stamp (MergeStamp's ETTR line: "+1.0", "✗ man" ...). */
    private volatile String mEttrNote = "✗ wait";
    /** P377: when the meter last logged its reading (one line every 2 s while ETTR runs). */
    private long mEttrLoggedAtNs;
    /** P377: the hold last said on the screen (null: none since ETTR ran or was switched off). */
    private static String sEttrTold;
    /** P377 (review): the frames whose shutter held within 3 % of the one before (the ISO by hand: metered once it is still). */
    private int mEttrStill;
    private double mEttrLastProduct;
    /** More than the viewfinder loop's step interval (0.2 s and more): an exposure this still has arrived. */
    static final int ETTR_STILL_FRAMES = 12;

    /**
     * P377 (review): whether a frame's exposure has settled for the ETTR meter - the camera's AE converged (AE on; a HAL that
     * reports no state: still), or the exposure still for ETTR_STILL_FRAMES frames (AE off: the viewfinder loop; the ISO set by
     * hand: the panel's free shutter).
     */
    private boolean ettrSettled(CaptureResult result, boolean isoByHand) {
        final boolean still = mEttrStill >= ETTR_STILL_FRAMES;
        if (isoByHand) return still;
        final Integer aeMode = result.get(CaptureResult.CONTROL_AE_MODE), aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        if (aeMode == null || aeMode == CaptureResult.CONTROL_AE_MODE_OFF || aeState == null) return still;
        return aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
    }

    /**
     * P356: the exposure compensation index of the request: the user's {@code userIndex} plus the ETTR boost of {@code cc}, in
     * range (static: a controller without its session state - a test's mock - adds nothing).
     */
    public static int ettrIndex(CaptureController cc, int userIndex) {
        if (cc != null && cc.unspektraAe != null && cc.unspektraAe.ownsExposure()) return userIndex;
        final Ettr ettr = cc == null ? null : cc.mEttr;
        final CameraCharacteristics c = cc == null ? null : cc.mCameraCharacteristics;
        // the switch turned off where nothing meters (the boost is cleared by the next meter or session): nothing added
        if (ettr == null || c == null || ettr.ev() <= 0f || !PreferenceKeys.isEttr()) return userIndex;
        final Range<Integer> range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        final android.util.Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (range == null || step == null) return userIndex;
        return Ettr.index(userIndex, ettr.ev(), step.floatValue(), range.getLower(), range.getUpper());
    }

    /** P377: the ETTR boost in force (EV, 0 none): ParamController's software loop brightens by it. */
    public static float ettrEv(CaptureController cc) {
        if (cc != null && cc.unspektraAe != null && cc.unspektraAe.ownsExposure()) return 0;
        return cc == null || cc.mEttr == null || !PreferenceKeys.isEttr() ? 0f : cc.mEttr.ev();
    }

    /** P377: the shot's ETTR for its plan (the stamp's line), null with the switch off. */
    @Nullable
    private String ettrNote() {
        return PreferenceKeys.isEttr() ? " | ETTR " + mEttrNote : null;
    }

    /**
     * P356 / P377 ETTR: meters a live RAW frame now and then and moves the boost (Ettr) - the frames of a live RAW session
     * (onMatchedLiveRaw) and of the plain ZSL session (the RAW viewfinder off). The automatic exposure gets it as light: shutter
     * priority (ParamController.setEttrShutter) at the AE's own shutter x 2^boost and the compensation plus it, so the AE keeps its
     * ISO; with the ISO set by hand the compensation alone, which the panel's free shutter takes. The ISP viewfinder shows the
     * matching display gain. Off (Ettr.hold: the switch, a hand-set shutter, the «Движение» mode, the own native AE, a vivo SDK
     * session, the forced stabilisation; a shot in progress): no boost.
     */
    private void ettrMeter(Image img, TotalCaptureResult result) {
        if (unspektraAe.ownsExposure()) { ettrStop("Unspektra AE"); return; }
        final boolean isoByHand = paramController.manualIsoValue() != 0;
        final String held = Ettr.hold(PreferenceKeys.isEttr(), mNativeAe != null, mVivoSdkCapture != null,
                com.scamera.app.settings.MotionMode.on() || paramController.motionCeilingActive(),
                paramController.manualPreviewShutterNs() != 0, previewAeOffUnsafe());
        if (held != null && !held.equals(mEttrHeld)) Log.i("SCAM_AE", "ETTR held: " + held);
        // said once per reason: not again at every lens or session (they start from "off"), again after the switch was off
        if (held == null || "off".equals(held)) sEttrTold = null;
        else if (!held.equals(sEttrTold)) {
            sEttrTold = held;
            ettrHeldMessage(held);
        }
        mEttrHeld = held;
        if (held != null) {
            mEttrNote = "✗ " + Ettr.holdCode(held);
            ettrStop(held);
            return;
        }
        // the panel's exposure changed under the boost (the ISO set by hand or back to auto): from 0 again
        if (isoByHand != mEttrIsoByHand) {
            mEttrIsoByHand = isoByHand;
            ettrStop(isoByHand ? "ISO set by hand" : "ISO automatic again");
        }
        // something dropped the ETTR shutter behind the boost (the manual console closed), or wrote AE on over the viewfinder
        // loop's AE-off request (a tap's metering, the 3A reset after a shot; review): from the AE again, which meters it
        if (!isoByHand && mEttr.ev() > 0f && paramController.ettrShutterNs() == 0) ettrStop("the shutter priority was dropped");
        if (!isoByHand && paramController.ettrLoopOverridden()) ettrStop("AE on over the ETTR loop (a tap or a 3A reset)");
        // P377 (review): the exposure takes the boost on its own time (the viewfinder loop moves two thirds of the way 0.2 s apart,
        // the camera's AE converges): only a settled frame is metered - a frame on its way asked for more again (pumping)
        final Long frameShutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer frameIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        final double product = frameShutter == null || frameIso == null ? 0 : (double) frameShutter * frameIso;
        mEttrStill = product > 0 && mEttrLastProduct > 0 && Math.abs(product / mEttrLastProduct - 1) <= 0.03 ? mEttrStill + 1 : 0;
        mEttrLastProduct = product;
        if (mShotInProgress) return; // the series keeps the boost of the press
        if (!mEttr.due()) return;
        if (!ettrSettled(result, isoByHand)) return;
        try {
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            final com.scamera.app.processing.RawLevels.Levels levels =
                    liveLevels(c, result);
            float black = 0f;
            for (float b : levels.black) black += b / levels.black.length;
            // P365 (review): the plain 16-bit samples - a RAW10 / RAW12 frame unpacked (meterSamples), never read as 16-bit
            final java.nio.ByteBuffer samples = meterSamples(img);
            if (samples == null) return;
            // P377: with the ultrashort frames the merge carries what the N frames clip: the brightest 0.5 % may pass the target
            final float share = Ettr.tail(HybridPlan.bentoMode() > 0);
            final float p = Ettr.tail(samples, img.getWidth(), img.getHeight(), mMeterStride, black, levels.white, 4, share);
            if (Float.isNaN(p)) return;
            final Long shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            final long cap = ettrCapNs(result);
            final float before = mEttr.ev();
            final String reading;
            final String why;
            boolean apply = false;
            if (isoByHand) {
                // the panel's ISO priority: the compensation goes into its free shutter, judged without the boost (no 0 / +1 EV
                // pumping), within one ring frame
                final float headroom = Ettr.shutterHeadroomEv(shutter, cap, before);
                reading = String.format(java.util.Locale.ROOT, "tail %.1f%% %.3f, shutter %s ISO %s (ISO by hand), ceiling %.2f ms, room %.2f EV",
                        100 * share, p, shutter, iso, cap / 1e6, headroom);
                apply = mEttr.update(p, headroom);
                why = mEttr.ev() > 0f ? null : !(headroom > 0f) ? "room" : p >= Ettr.CLIPPED ? "clip" : "hi";
            } else {
                // the automatic exposure: the AE's own shutter (a settled frame of the AE without a boost) grows by the boost - not
                // one still on its way back from a boost just dropped (the next one would start from the boosted shutter)
                final Integer aeMode = result.get(CaptureResult.CONTROL_AE_MODE);
                if (shutter != null && iso != null && aeMode != null && aeMode != CaptureResult.CONTROL_AE_MODE_OFF) mEttr.learnRef(shutter, iso);
                final long ref = mEttr.refNs();
                if (ref <= 0) {
                    mEttrNote = "✗ wait";
                    return;
                }
                final float headroom = cap > ref ? (float) (Math.log((double) cap / ref) / Math.log(2)) : 0f;
                // a settled frame has the boost in force: the AE keeps its brightness at it (a darker scene raises its ISO, the
                // boost stays the light over what the AE alone gives)
                final float want = mEttr.wanted(p, headroom);
                long s = Ettr.shutterNs(ref, want, cap);
                float got = Ettr.boostOf(s, ref);
                if (got < Ettr.MIN_STEP_EV) { // not worth taking the AE over (whole half periods of the mains left none)
                    got = 0f;
                    s = 0;
                }
                reading = String.format(java.util.Locale.ROOT, "tail %.1f%% %.3f, shutter %s ISO %s, AE shutter %.2f ms, ceiling %.2f ms, wants %+.2f EV",
                        100 * share, p, shutter, iso, ref / 1e6, cap / 1e6, want);
                // review: a ceiling that came down (the HAL reports motion) is a hard limit, not a change under the minimal step
                final boolean overCap = before > 0f && paramController.ettrShutterNs() > cap;
                if (Math.abs(want - before) >= Ettr.MIN_STEP_EV || overCap) {
                    final boolean changed = mEttr.deliver(got);
                    if (changed || overCap) {
                        // the compensation first when the boost grows (review: the priority AE would otherwise cut the ISO for a frame
                        // or two at the longer shutter), the shutter first when it shrinks
                        if (got > before) ettrApply(reading + String.format(java.util.Locale.ROOT, " -> %.2f ms", s / 1e6));
                        paramController.setEttrShutter(s, mEttr.refProduct());
                        if (got <= before) ettrApply(reading + (s > 0 ? String.format(java.util.Locale.ROOT, " -> %.2f ms", s / 1e6) : " -> the AE"));
                    }
                }
                why = mEttr.ev() > 0f ? null : !(headroom > 0f) ? "room" : p >= Ettr.CLIPPED ? "clip"
                        : want >= Ettr.MIN_STEP_EV ? "band" : "hi";
            }
            // a boost to apply, or a compensation something else wrote over it (the console's EV reset)
            final CaptureRequest.Builder b = mPreviewRequestBuilder;
            final Integer now = b == null ? null : b.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
            if (apply || now == null || now != ettrIndex(this, paramController.effectiveEv())) ettrApply(reading);
            mEttrNote = why == null ? String.format(java.util.Locale.ROOT, "+%.1f", mEttr.ev()) : "✗ " + why;
            final long t = System.nanoTime();
            if (t - mEttrLoggedAtNs > 2_000_000_000L) {
                mEttrLoggedAtNs = t;
                Log.i("SCAM_AE", String.format(java.util.Locale.ROOT, "ETTR meter: %s; boost %+.2f EV (%s)", reading, mEttr.ev(), mEttrNote));
            }
        } catch (RuntimeException e) {
            Log.w("SCAM_AE", "ETTR meter: " + e);
        }
    }

    /** P377: the boost back to 0 and the AE free again (the shutter priority dropped); logged and applied when it changed. */
    private void ettrStop(String why) {
        final boolean changed = mEttr.reset();
        paramController.setEttrShutter(0);
        if (changed) ettrApply(why);
    }

    /** P377: a hold the owner can change, said once on the screen (the switch was on and nothing happened: the bug). */
    private static void ettrHeldMessage(String held) {
        final String text;
        switch (held) {
            case "hand-set shutter":
                text = Lang.t("ETTR: выдержка задана вручную — ETTR не добавит света", "ETTR: the shutter is set by hand, ETTR cannot add light");
                break;
            case "the motion mode (short shutters)":
                text = Lang.t("ETTR: в режиме «Спорт» выдержка короткая — ETTR ждёт", "ETTR: the Sport mode keeps short shutters, ETTR waits");
                break;
            case "forced stabilisation":
                text = Lang.t("ETTR: недоступен при принудительной стабилизации", "ETTR: unavailable under the forced stabilisation");
                break;
            default:
                return;
        }
        com.scamera.app.util.UiMessages.info(text);
    }

    /** P377: the longest ETTR shutter: one ring frame (the viewfinder's AE range), shorter while the HAL reports motion. */
    private long ettrCapNs(@Nullable CaptureResult r) {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        final Range<Integer> fps = b == null ? null : b.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
        final long frame = fps != null && fps.getUpper() != null && fps.getUpper() > 0 ? 1_000_000_000L / fps.getUpper() : 33_333_333L;
        final long motion = HalMotion.motionCapNs(r);
        return motion > 0 ? Math.min(frame, motion) : frame;
    }

    /**
     * P356: the plain ZSL session's frame (the RAW viewfinder off - the default off the flagship SoCs: no live RAW session, so
     * onMatchedLiveRaw never metered and the switch did nothing) metered for ETTR with its own result; one that has not arrived
     * yet is newer than the latest completed result, which stands for it (as passModeBarrier). Off with no boost to clear (the
     * switch is no session key: turned off in the shade, ettrMeter's hold clears a boost), the plain frames cost nothing. A frame
     * that is no plain RAW (a bad or packed-as-16-bit payload: as plainMeter) is not metered.
     */
    private void ettrPlain(Image img) {
        if (!PreferenceKeys.isEttr() && mEttr.ev() <= 0f) return;
        if (img == null || !isRawFormat(img.getFormat()) || mPayloadBad || !mPreviewLayouts.isEmpty()) return;
        TotalCaptureResult r;
        synchronized (mRingLock) { r = mHexZslResults.get(img.getTimestamp()); }
        final CaptureResult latest = mPreviewCaptureResult;
        if (r == null && latest instanceof TotalCaptureResult) r = (TotalCaptureResult) latest;
        if (r != null && !isNativeIsoProbe(r)) ettrMeter(img, r);
    }

    /**
     * P356: no boost and no ETTR display gain from now on - the camera closed or a new session (each meters its own from 0; a
     * session that never meters - video, a burst - kept the last one: its ISP frames x2^-boost, the boost added to every EV
     * change). Before the session's request is built (setupPreview reads ettrIndex). P377: the ETTR shutter priority with it.
     */
    private void resetEttr() {
        mEttr.reset();
        mEttrHeld = "off";
        mEttrNote = "✗ wait";
        paramController.clearEttr();
        com.scamera.app.ui.camera.views.viewfinder.ViewfinderGain.setDisplayEv(0f);
    }

    private void ettrApply(String why) {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b == null) return;
        final int user = paramController.effectiveEv(), index = ettrIndex(this, user);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index);
        final CameraCharacteristics c = mCameraCharacteristics;
        final android.util.Rational step = c == null ? null : c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        final float shown = step == null ? 0f : (index - user) * step.floatValue();
        com.scamera.app.ui.camera.views.viewfinder.ViewfinderGain.setDisplayEv(shown);
        Log.i("SCAM_AE", String.format(java.util.Locale.ROOT, "ETTR boost %.2f EV (index %d, user %d): %s", shown, index, user, why));
        rebuildPreviewBuilder();
    }

    /** P212c: the user's exposure compensation of the viewfinder request (EV). */
    private float nativeAeEv(@Nullable CameraCharacteristics c) {
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        final Integer index = builder == null ? null : builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        final android.util.Rational step = c == null ? null : c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        return index != null && step != null ? index * step.floatValue() : 0f;
    }

    /**
     * P212z (review): the gains of {@code map} (copyGainFactors order): {@code last} itself when they are its factors at its size
     * ({@code lastCols} x {@code lastRows}) - the array NativeRawExposure.gainMedian caches its median by - else a new array. The
     * factors are read into {@code scratch[0]} (reused frame after frame); a copy that becomes the gains leaves it (never written again).
     */
    static float[] meterGains(android.hardware.camera2.params.LensShadingMap map, @Nullable float[] last, int lastCols, int lastRows,
                              float[][] scratch) {
        final int n = map.getGainFactorCount();
        float[] buffer = scratch[0];
        if (buffer == null || buffer.length != n) buffer = scratch[0] = new float[n];
        map.copyGainFactors(buffer, 0);
        if (last != null && lastCols == map.getColumnCount() && lastRows == map.getRowCount() && Arrays.equals(buffer, last)) return last;
        scratch[0] = null;
        return buffer;
    }

    /**
     * P212c: what the native AE's meter weights: the output crop (the result's crop region, inside it the zoom ratio around its
     * centre - CONTROL_ZOOM_RATIO leaves the crop region whole), the AE regions a tap or a subject set (they differ from the
     * metering mode's own), the result's shading map unless the RAW is shaded already, and the white point.
     */
    private NativeRawExposure.MeterSetup meterSetup(@Nullable CameraCharacteristics c, @Nullable CaptureResult result, @Nullable float[] neutral) {
        final NativeRawExposure.MeterSetup s = new NativeRawExposure.MeterSetup();
        final Rect active = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active != null && active.width() > 0 && active.height() > 0) {
            final Rect region = result == null || XiaomiTeleZoom.routed() ? null : result.get(CaptureResult.SCALER_CROP_REGION);
            if (region != null) {
                final Rect clipped = new Rect(region);
                if (clipped.intersect(active)) {
                    s.left = (clipped.left - active.left) / (float) active.width();
                    s.top = (clipped.top - active.top) / (float) active.height();
                    s.right = (clipped.right - active.left) / (float) active.width();
                    s.bottom = (clipped.bottom - active.top) / (float) active.height();
                }
            }
            Float ratio = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && result != null) {
                try { ratio = result.get(CaptureResult.CONTROL_ZOOM_RATIO); } catch (IllegalArgumentException ignored) { ratio = null; }
            }
            if (ratio != null && ratio > 1.001f) {
                final float cx = 0.5f * (s.left + s.right), cy = 0.5f * (s.top + s.bottom);
                final float hw = 0.5f * (s.right - s.left) / ratio, hh = 0.5f * (s.bottom - s.top) / ratio;
                s.left = cx - hw;
                s.right = cx + hw;
                s.top = cy - hh;
                s.bottom = cy + hh;
            }
            final CaptureRequest.Builder builder = mPreviewRequestBuilder;
            final MeteringRectangle[] regions = builder == null ? null : builder.get(CaptureRequest.CONTROL_AE_REGIONS);
            if (regions != null && regions.length > 0 && !Arrays.equals(regions, mPreviewMeteringAE)) {
                final java.util.ArrayList<float[]> taps = new java.util.ArrayList<>();
                for (MeteringRectangle q : regions) {
                    if (q == null || q.getMeteringWeight() <= 0 || q.getWidth() <= 0 || q.getHeight() <= 0) continue;
                    taps.add(new float[]{(q.getX() - active.left) / (float) active.width(), (q.getY() - active.top) / (float) active.height(),
                            (q.getX() + q.getWidth() - active.left) / (float) active.width(), (q.getY() + q.getHeight() - active.top) / (float) active.height()});
                }
                if (!taps.isEmpty()) s.regions = taps.toArray(new float[0][]);
            }
        }
        // the result's shading map (an OPPO native result has the plain session's, OppoNativeFullRes.complete); P212e (W1): a
        // stored plain map goes into s.gains when the result has none
        final android.hardware.camera2.params.LensShadingMap map = result == null ? null
                : result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (map != null && map.getColumnCount() > 1 && map.getRowCount() > 1
                && !com.scamera.app.processing.render.Parameters.rawShadingAlreadyApplied(c)) {
            // P212z (review): the key is synthesized, every get() builds a new LensShadingMap - the cache keyed on the map's
            // identity never hit, so every metered frame allocated new gains and NativeRawExposure.gainMedian (keyed on the gains
            // array's identity) re-ran its 64 x 48 grid and sort. The factors are compared instead: the same map keeps its array.
            mMeterGains = meterGains(map, mMeterGains, mMeterCols, mMeterRows, mMeterScratch);
            mMeterCols = map.getColumnCount();
            mMeterRows = map.getRowCount();
            s.gains = mMeterGains;
            s.gainCols = map.getColumnCount();
            s.gainRows = map.getRowCount();
        } else if (map == null && !com.scamera.app.processing.render.Parameters.rawShadingAlreadyApplied(c)) {
            // P212e (W1): a cold start's native result without a map: the camera's stored plain one (one array: the median caches)
            final OppoPlainState.Shading stored = nativeStoredShading(neutral);
            if (stored != null) {
                s.gains = stored.gains;
                s.gainCols = stored.cols;
                s.gainRows = stored.rows;
            }
        }
        if (neutral != null && neutral.length >= 3 && neutral[1] > 0f) {
            s.neutralR = neutral[0] / neutral[1];
            s.neutralB = neutral[2] / neutral[1];
        }
        return s;
    }

    /**
     * P212e (W1 = M2): the stored plain lens shading map of the running OPPO native session's camera (OppoPlainState, the bin of the
     * light nearest to {@code neutral}), for a frame whose result has none - a cold start straight into FULLRES, whose results
     * the stored map could not be put into (OppoNativeFullRes.complete). Null outside an OPPO native session or without one.
     */
    @Nullable
    private OppoPlainState.Shading nativeStoredShading(@Nullable float[] neutral) {
        if (mVivoSdkRawSession == null || !VivoSdkFullRes.oppo() || !nativeVivoFullRes()) return null;
        return OppoNativeFullRes.storedShading(mVivoSdkCameraId, neutral);
    }

    /**
     * P212b: a plain-session frame of an OPPO camera that has a native FULLRES mode, metered as the native AE meters (block 1) for
     * that AE's next start (NativeAeSeed), about 3 times a second, only while the HAL AE is converged / locked and the frame is a
     * plain 16-bit Bayer RAW (no colour-block mosaic, no packed layout). {@code result}: the matched one, else the ZSL results'.
     */
    private void plainMeter(Image img, @Nullable TotalCaptureResult result) {
        // P365 (review): a RAW10 / RAW12 stream's frame too, unpacked (meterSamples) - the Find X7 Ultra's plain session streams
        // RAW10 since DeviceDefaults v7, and this sample is its native FULLRES AE's start
        if (mVivoSdkRawSession != null || img == null || !isRawFormat(img.getFormat()) || mPayloadBad || !mPreviewLayouts.isEmpty())
            return;
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - mPlainMeterMs < 300) return;
        try {
            if (!OppoNativeFullRes.device() || OppoNativeFullRes.offer(physicalID) == null
                    || com.scamera.app.processing.MosaicStream.block() > 1) return;
            if (result == null) synchronized (mRingLock) { result = mHexZslResults.get(img.getTimestamp()); }
            if (result == null) return;
            final Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
            if (state == null || state != CaptureResult.CONTROL_AE_STATE_CONVERGED && state != CaptureResult.CONTROL_AE_STATE_LOCKED) return;
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exposure == null || iso == null || exposure <= 0 || iso <= 0) return;
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            if (cfa > 3) return;
            final com.scamera.app.processing.RawLevels.Levels levels =
                    liveLevels(c, result);
            float[] neutral = null;
            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n != null && n.length >= 3) neutral = new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()};
            final java.nio.ByteBuffer samples = meterSamples(img);
            mPlainMeterMs = now;
            if (samples == null) return;
            final NativeRawExposure.Metered metered = NativeRawExposure.meter2(samples, img.getWidth(), img.getHeight(),
                    mMeterStride, cfa, 1, levels.black, levels.white, meterSetup(c, result, neutral));
            if (metered != null)
                NativeAeSeed.plainSample(physicalID, metered.p50, metered.p98, (double) exposure * iso / 100.0, now);
        } catch (RuntimeException e) {
            mPlainMeterMs = now;
        }
    }

    /** P212h (A7 = M14 phase 0): the vivo AE audit's frame count and its second's samples (camera thread). */
    private final VivoAeAudit mVivoAeAudit = new VivoAeAudit();

    /**
     * P212h (A7 = M14 phase 0, log only): every 8th viewfinder RAW of a vivo session - the native FULLRES mosaic, an ISZ module's, P208's
     * still-only viewfinder, the plain Bayer stream - metered as the OPPO own AE meters (meter2 at the stream's colour block) and logged
     * once a second beside the HAL's exposure (VivoAeAudit). Only a plain 16-bit RAW, never under a shot.
     */
    private void vivoAeAudit(Image img, @Nullable TotalCaptureResult result) {
        if (img == null || result == null || img.getFormat() != ImageFormat.RAW_SENSOR || mPayloadBad || !mPreviewLayouts.isEmpty()
                || VivoSdkFullRes.oppo() || mVivoSdkCapture != null || burst || mShotInProgress || shotInFlight()
                || !VivoAeAudit.wanted() || !mVivoAeAudit.due()) return;
        try {
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exposure == null || iso == null || exposure <= 0 || iso <= 0) return;
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            final String kind;
            final int block;
            if (nativeVivoFullRes() && !stillOnlyFullRes()) {
                kind = "native";
                block = Math.max(1, VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock);
            } else {
                final int stream = com.scamera.app.processing.MosaicStream.block();
                block = Math.max(1, stream);
                kind = stillOnlyFullRes() ? "still-only viewfinder" : stream > 1 ? "isz" : "plain";
            }
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            if (cfa > 3) return;
            final com.scamera.app.processing.RawLevels.Levels levels =
                    liveLevels(c, result);
            float[] neutral = null;
            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n != null && n.length >= 3) neutral = new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()};
            final Image.Plane plane = img.getPlanes()[0];
            final NativeRawExposure.Metered m = NativeRawExposure.meter2(plane.getBuffer(), img.getWidth(), img.getHeight(),
                    plane.getRowStride(), cfa, block, levels.black, levels.white, meterSetup(c, result, neutral));
            if (m == null) return;
            final String line = mVivoAeAudit.add(physicalID, kind, block, m.p50, m.p98, m.peak, m.clip, m.shaded, exposure, iso,
                    result.get(CaptureResult.CONTROL_AE_STATE), nativeAeEv(c), android.os.SystemClock.elapsedRealtime());
            if (line != null) Log.i("SCAM_AE", line);
        } catch (RuntimeException e) {
            Log.d("SCAM_AE", "vivo AE audit: " + e);
        }
    }

    /** P212a: the native AE's ISO probe frame (P212z: its request is the probe request sent). */
    private boolean isNativeIsoProbe(@Nullable TotalCaptureResult result) {
        return isProbe(result == null ? null : result.getRequest(), mNativeIsoProbeRequest);
    }

    /** P212z: a result's request is the probe request (by identity: a repeating request built meanwhile is never one). */
    static boolean isProbe(@Nullable CaptureRequest request, @Nullable CaptureRequest probe) {
        return request != null && request == probe;
    }

    /**
     * P212a: one request of the viewfinder at {@code probeIso} (the AE's shutter, everything else as the repeating request): its
     * result goes through ExposureLimits.observeManual as every result does, so a delivered ISO above the cap clears the cap.
     */
    private void sendNativeIsoProbe(NativeRawExposure ae, int probeIso) {
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        final CameraCaptureSession session = mCaptureSession;
        if (builder == null || session == null || burst) return;
        try {
            final Integer before = builder.get(CaptureRequest.SENSOR_SENSITIVITY);
            final CaptureRequest probe;
            // P212z: the builder's tag stays (the probe is known by its request object); its ISO is restored right after the build
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, probeIso);
            try {
                probe = builder.build();
            } finally {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, before != null ? before : ae.iso());
            }
            mNativeIsoProbeRequest = probe;
            session.capture(probe, mCaptureCallback, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "native AE camera %s: ISO probe %d at %.3f ms (cap %d, %.2f EV short)",
                    mVivoSdkCameraId, probeIso, ae.exposureNs() / 1e6, ae.isoCap(), ae.deficitEv()));
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w("SCAM_CAPTURE", "native AE ISO probe: " + e);
        }
    }

    private void publishLiveRawFrame(Image img, TotalCaptureResult matchedResult) {
        final LiveRawFrame.Meta meta = liveRawMeta(img, matchedResult);
        if (meta == null) return;
        try {
            LiveRawFrame.publish(img.getPlanes()[0].getBuffer(), meta);
            if (mViewfinder != null) mViewfinder.requestRender();
        } catch (Exception e) {
            Log.w(TAG, "publishLiveRawFrame: " + e.getMessage());
        }
    }

    /**
     * P365 step 4: whether a shown frame reaches the viewfinder from its memfd ring slot (scam_dev vf_ring_ref): the ring copies it
     * there anyway (the memfd ring, one plane, not the native 50 MP ring) and the slot holds plain 16-bit samples - a RAW_SENSOR
     * stream with no packed layout behind it, or a RAW10 / RAW12 stream the ring unpacks on arrival (ringUnpack). Any other frame
     * keeps the viewfinder's own copy.
     */
    private boolean ringRefFrame(Image img) {
        if (!PreferenceKeys.scamDevSwitch("vf_ring_ref", true) || mZslRingUnavailable || !PreferenceKeys.scamDevSwitch("zsl_memfd_ring", true)
                || nativeZslSession() || img.getPlanes().length != 1) return false;
        final int format = img.getFormat();
        if (format == ImageFormat.RAW_SENSOR) return mPreviewLayouts.isEmpty();
        return ringUnpack() && ZslRing.unpacks(format, img.getWidth());
    }

    /**
     * P365 step 8 (scam_dev zsl_ring_unpack): a packed RAW10 / RAW12 frame of the 12 MP ring is unpacked into its slot as it arrives
     * (ZslRing.store) - the worker then takes a view of the slot instead of unpacking every ring frame after the press, and the
     * viewfinder draws the slot. Not the Pixel's 50 MP session (its ring copies the packed frames as before).
     */
    private boolean ringUnpack() {
        return !mFullResSession && PreferenceKeys.scamDevSwitch("zsl_ring_unpack", true);
    }

    /**
     * P365 step 4, under the ring's lock right after the ring copy: a view of the shown frame's slot for the viewfinder
     * (LiveRawFrame.publishRef: drawn where it is, or copied from the view when it cannot be), {@code meta} set to the slot's
     * layout; null when there is none - a ring that held the camera's Image itself (no memfd slot) has it copied here as before.
     */
    @Nullable
    private java.nio.ByteBuffer ringFrameView(RingFrame frame, Image img, LiveRawFrame.Meta meta) {
        try {
            final long[] slot = frame.arenaSlot();
            if (slot == null) {
                LiveRawFrame.publish(img.getPlanes()[0].getBuffer(), meta); // the ring holds the open Image (RingFrame.of)
                if (mViewfinder != null) mViewfinder.requestRender();
                return null;
            }
            if (ZslRing.unpacked(frame)) {
                meta.format = frame.getFormat();
                meta.rowStride = frame.rowStride();
            }
            return Allocator.arenaView((int) slot[0], slot[1], frame.buffer().capacity());
        } catch (RuntimeException e) {
            Log.w(TAG, "ringFrameView: " + e.getMessage());
            return null;
        }
    }

    /**
     * The viewfinder's parameters of this frame (levels, white balance, colour matrix, lens shading, crop, noise, gain range), null
     * when it is not to be published. P365: apart from the hand-off (publishLiveRawFrame copies the camera's plane,
     * onMatchedLiveRaw hands over the ring slot, ringFrameView), so it runs while the Image is open.
     */
    @Nullable
    private LiveRawFrame.Meta liveRawMeta(Image img, TotalCaptureResult matchedResult) {
        if (!LiveRawFrame.isEnabled() || img == null || matchedResult == null) return null; // no result: nothing to develop it with
        if (!isRawFormat(img.getFormat())) return null;
        if (mPayloadBad) {
            // The developed RAW viewfinder would show the same garbage: the ISP preview takes over for this session.
            LiveRawFrame.setEnabled(false);
            restoreIspModes(); // P365 step 9: the ISP picture is what the user sees now
            return null;
        }
        // P314: the viewfinder has not taken the last frame yet (drop-when-busy, as the reference viewfinder's hand-off): this one is
        // neither read nor copied (the callers' draw request then coalesces with the waiting frame's own)
        if (!LiveRawFrame.takesFrame()) return null;
        // P72: a packed session (17 Ultra tele) whose frame fits none of its layouts and is not plain either: skipped (the last
        // developed frame stays) instead of developing it as 16-bit
        if (!mPreviewLayouts.isEmpty() && img.getFormat() == ImageFormat.RAW_SENSOR && frameLayout(img) == null
                && com.scamera.app.processing.RawPayloadCheck.dataEnd(img.getPlanes()[0].getBuffer())
                   < img.getPlanes()[0].getBuffer().capacity() - img.getWidth()) return null;
        try {
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            int cfa = 0;
            if (c != null) {
                if (mLiveCfaPattern >= 0) {
                    cfa = mLiveCfaPattern;
                } else {
                    Integer arr = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                    if (arr != null) cfa = arr;
                }
            }
            // Exact CaptureResult paired with this image timestamp, never the latest result from another frame.
            float[] gains = new float[]{1, 1, 1};
            float[] ccm = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            CaptureResult colorResult=matchedResult;
            if(android.os.Build.VERSION.SDK_INT>=28 && colorResult instanceof TotalCaptureResult){
                CaptureResult physical=((TotalCaptureResult)colorResult).getPhysicalCameraResults().get(physicalID);
                if(physical!=null)colorResult=physical;
            }
            if(colorResult != null){
                android.hardware.camera2.params.RggbChannelVector wb=colorResult.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if(wb!=null){gains[0]=wb.getRed();gains[1]=(wb.getGreenEven()+wb.getGreenOdd())*.5f;gains[2]=wb.getBlue();}
                android.hardware.camera2.params.ColorSpaceTransform matrix=colorResult.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
                if(matrix!=null)for(int col=0;col<3;col++)for(int row=0;row<3;row++)ccm[col*3+row]=matrix.getElement(col,row).floatValue();
            }
            // P127: black and white at the stream's bit depth (RawLevels): the frame's dynamic black when it is in the stream's
            // units or only drifts (OPPO X8U 64 -> 60), rescaled when it is in other units (Realme GT8 Pro: 16 for 64, X300 Ultra
            // DCG: 1030.75 for 64 over 14-bit samples)
            final com.scamera.app.processing.RawLevels.Levels levels = liveLevels(c, colorResult);
            float white = levels.white;
            float[] black = levels.black.clone();
            String sensorPrefix=com.scamera.app.settings.ModuleSensorSettings.prefix(
                    com.scamera.app.settings.ModuleSensorSettings.runtimeScope(physicalID));
            android.content.SharedPreferences sensorValues=ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            float overrideBlack=(float)com.scamera.app.settings.PreferenceNumber.read(
                    com.scamera.app.settings.PreferenceValue.get(sensorValues,sensorPrefix+"blackleveloverride"),-1);
            float overrideWhite=(float)com.scamera.app.settings.PreferenceNumber.read(
                    com.scamera.app.settings.PreferenceValue.get(sensorValues,sensorPrefix+"whiteleveloverride"),-1);
            // P320: a module's hand-set levels are the plain mode's; a DCG frame keeps the levels its own data resolved
            if(Dcg14Mode.frames(c)){overrideWhite=-1;overrideBlack=-1;}
            if(overrideWhite>0)white=overrideWhite;
            if(overrideBlack>=0&&overrideBlack<white)java.util.Arrays.fill(black,overrideBlack);
            LiveRawFrame.setBlackFromData(overrideBlack < 0 && PreferenceKeys.isRawBlackFromData()); // P315: a stream without its pedestal
            // P108: the Pixel's full-resolution RAW is Quad-Bayer (measured block 2), binned for the viewfinder
            // P208: the still-only route's viewfinder is the normal mode's stream
            final int block = stillOnlyFullRes() ? Math.max(1, com.scamera.app.processing.MosaicStream.block())
                    : nativeVivoFullRes() ? VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock
                    : mRecipePlan != null ? 1 // P635: the viewfinder's frames are the camera's own binned stream
                    : mFullResSession ? NubiaRemosaicProbe.fullResBlock(physicalID) // P605: 1 on the nubia remosaic route
                    : com.scamera.app.processing.MosaicStream.block() > 1
                    ? com.scamera.app.processing.MosaicStream.block() : PreferenceKeys.mosaicBlock();
            // P370 (A2): the photo's matrix rules by stream - a colour-block mosaic (an ISZ lease, a picked sensor mode, FULLRES, a
            // native session) or plain Bayer (PhotoMatrix)
            final boolean mosaicStream = block > 1 || mIszLease != null || SensorModePick.session() != null || nativeVivoFullRes()
                    || mRecipePlan == null && mFullResSession && NubiaRemosaicProbe.fullResBlock(physicalID) > 1 || VivoPd2454NativeRaw.active(physicalID);
            // P370 (B6): a mosaic stream whose ISP runs no 3A shows the photo's own neutral, not the HAL's frozen gains
            if (com.scamera.app.processing.color.ViewfinderMosaicWb.on()) {
                final float[] own = viewfinderMosaicNeutral(img, c, colorResult, cfa, white, black, block, mosaicStream);
                if (own != null) gains = com.scamera.app.processing.color.ViewfinderMosaicWb.gains(own);
            } else mVfMosaicWb.reset();
            float[] dcpMatrix = com.scamera.app.processing.color.DcpProfiles.previewMatrix(gains);
            if (dcpMatrix != null) ccm = dcpMatrix;
            // P212h (A6 = M11): a native FULLRES viewfinder whose result transform is the identity (the X7 Ultra's, a vivo mosaic
            // result's) shows the photo's matrix at its neutral (NativeViewfinder; scam_dev.txt "vf_native_ccm 0": the identity).
            // P370 (A2): every RAW viewfinder stream, the photo's rules (PhotoMatrix): an identity transform takes the tuned matrix,
            // the frame's OPPO matrix (plain Bayer) or the plain-session table then the OPPO matrix (mosaic); any other transform its
            // rows normalised as the photo's
            else if (PreferenceKeys.scamDevSwitch("vf_native_ccm", true)) {
                final float[] photo = viewfinderMatrix(c, colorResult, gains, ccm, mosaicStream);
                if (photo != null) ccm = photo;
            }
            float[] shading=null;int sw=1,sh=1;
            android.hardware.camera2.params.LensShadingMap map=colorResult==null?null:colorResult.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
            final boolean lscSkipped=com.scamera.app.processing.render.Parameters.rawShadingAlreadyApplied(c);
            if(map!=null&&lscSkipped)map=null;
            if(map!=null){sw=map.getColumnCount();sh=map.getRowCount();shading=new float[sw*sh*3];
                for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){int i=(y*sw+x)*3;shading[i]=map.getGainFactor(0,x,y);shading[i+1]=(map.getGainFactor(1,x,y)+map.getGainFactor(2,x,y))*.5f;shading[i+2]=map.getGainFactor(3,x,y);}}
            else if(!lscSkipped){
                // P212e (W1): an OPPO native frame of a cold start (no plain session in this process): the stored plain map
                final OppoPlainState.Shading stored=nativeStoredShading(gains[0]>0f&&gains[2]>0f?new float[]{gains[1]/gains[0],1f,gains[1]/gains[2]}:null);
                if(stored!=null){sw=stored.cols;sh=stored.rows;shading=new float[sw*sh*3];final float[] g=stored.gains;
                    for(int i=0;i<sw*sh;i++){shading[3*i]=g[4*i];shading[3*i+1]=(g[4*i+1]+g[4*i+2])*.5f;shading[3*i+2]=g[4*i+3];}}
            }
            Rect imageCrop = img.getCropRect();
            float[] crop = {imageCrop.left/(float)img.getWidth(), imageCrop.top/(float)img.getHeight(),
                    imageCrop.width()/(float)img.getWidth(), imageCrop.height()/(float)img.getHeight()};
            // Camera2 crop coordinates are in the active sensor array, not in the RAW buffer.
            Rect active = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            // P41b: on the Xiaomi logical camera the result's crop region is the logical stream's, not the tele RAW frame's
            Rect sensorCrop = colorResult == null || XiaomiTeleZoom.routed() ? null : colorResult.get(CaptureResult.SCALER_CROP_REGION);
            if (active != null && sensorCrop != null && active.width()>0 && active.height()>0 && Rect.intersects(sensorCrop, active)) {
                // the digital zoom by CONTROL_ZOOM_RATIO crops the ISP streams only: the RAW frame is cropped here (the Xiaomi
                // tele's ratio is its previewCrop below)
                float ratio = 1f;
                if (Build.VERSION.SDK_INT >= 30 && !XiaomiTeleZoom.active()) {
                    Float z = colorResult.get(CaptureResult.CONTROL_ZOOM_RATIO);
                    if (z != null) ratio = z;
                }
                crop = CroppedRaw.viewCrop(active, sensorCrop, ratio,
                        mCroppedRawSession ? CroppedRaw.of(colorResult, physicalID, logicalID) : null);
            }
            // P41: the Xiaomi tele's preview crop (zoomRatio against the claimed optics) is not in SCALER_CROP_REGION
            final float teleCrop = XiaomiTeleZoom.active() ? XiaomiTeleZoom.previewCrop() : 1f;
            if (teleCrop > 1.001f) {
                float w = crop[2] / teleCrop, h = crop[3] / teleCrop;
                crop = new float[]{crop[0] + (crop[2] - w) * 0.5f, crop[1] + (crop[3] - h) * 0.5f, w, h};
            }
            double shotNoise=0,readNoise=0;
            android.util.Pair<Double,Double>[] noiseProfile=colorResult.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if(noiseProfile!=null&&noiseProfile.length>0){
                int count=0;for(android.util.Pair<Double,Double> n:noiseProfile)if(n!=null&&n.first!=null&&n.second!=null){shotNoise+=n.first;readNoise+=n.second;count++;}
                if(count>0){shotNoise/=count;readNoise/=count;}
            }
            // P110: the «Движение» shutter ceiling runs AE off for the camera AE: still the automatic rendering
            boolean automatic = !Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF).equals(colorResult.get(CaptureResult.CONTROL_AE_MODE))
                    || paramController.motionCeilingActive();
            // P212h (A6 = M11): the OPPO native results say AE off (its own AE): the display gain covers the exposure the mode cannot
            // deliver (the own AE's deficit), never a hand-set one (NativeViewfinder; scam_dev.txt "vf_native_gain")
            float minGain = 0f, maxGain = Float.POSITIVE_INFINITY;
            final NativeRawExposure nativeAe = mNativeAe;
            final boolean ownAe = nativeAe != null && nativeVivoFullRes() && VivoSdkFullRes.oppo();
            if (!automatic && ownAe) {
                final float[] range = NativeViewfinder.displayGain((int) PreferenceKeys.scamDevNumber("vf_native_gain", 1f),
                        nativeAe.manual(), nativeAe.deficitEv());
                if (range != null) {
                    automatic = true;
                    minGain = range[0];
                    maxGain = range[1];
                }
            }
            // P212h: the frame's median is the shutter / ISO priority's SceneLuma, but not where the own AE meters the other value
            final boolean sceneLuma = !ownAe && PreferenceKeys.scamDevSwitch("vf_raw_scene_luma", true);
            final LiveRawFrame.Meta m = new LiveRawFrame.Meta();
            m.width = img.getWidth();
            m.height = img.getHeight();
            m.rowStride = readStride(img);
            m.cfa = cfa;
            m.white = white;
            m.black = black;
            m.gains = gains;
            m.ccm = ccm;
            m.shading = shading;
            m.shadingWidth = sw;
            m.shadingHeight = sh;
            m.crop = crop;
            m.block = block;
            m.sensorIso = colorResult.get(CaptureResult.SENSOR_SENSITIVITY);
            // P383: the frame's own exposure for the viewfinder - only from its own result (the mosaic preview may develop a frame
            // with an older result: unknown then, the viewfinder holds its exposure)
            final Long resultTs = matchedResult.get(CaptureResult.SENSOR_TIMESTAMP);
            if (resultTs != null && resultTs == img.getTimestamp()) m.exposureNs = colorResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            m.analogIso = c == null ? null : c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
            m.automatic = automatic;
            m.shotNoise = shotNoise;
            m.readNoise = readNoise;
            m.format = readFormat(img);
            m.minGain = minGain;
            m.maxGain = maxGain;
            m.sceneLuma = sceneLuma;
            if (PreferenceKeys.isPortraitEnabled()) {
                final com.scamera.app.processing.portrait.PortraitFaces.Result faces =
                        com.scamera.app.processing.portrait.PortraitFaces.fromResult(c, matchedResult, physicalID, logicalID,
                                img.getWidth(), img.getHeight(), img.getTimestamp(), mPortraitVendorWindow || XiaomiTeleZoom.isz());
                m.portraitFaceDetected = faces.detected;
                m.portraitFaces = faces.boxes;
            }
            return m;
        } catch (Exception e) {
            Log.w(TAG, "publishLiveRawFrame: " + e.getMessage());
            return null;
        }
    }

    /** P212h (A6): the camera, neutral (ln r, ln b), matrix (column-major, null: none) and source of the last native viewfinder matrix. */
    private String mVfCcmCamera;
    private float mVfCcmLnR = Float.NaN, mVfCcmLnB = Float.NaN;
    @Nullable private float[] mVfCcm;
    @Nullable private String mVfCcmHow;
    /** P370 (A2): the stream kind (mosaic) and whether the transform was the identity, of the cached pick. */
    private boolean mVfCcmMosaic, mVfCcmIdentity;
    /** P370 (review): the reported transform (column-major) the cached matrix of a non-identity transform was made from. */
    private final float[] mVfCcmReported = new float[9];
    /** P370 (B4): the scene light of the cached pick (K, 0 unknown) and the camera of the DNG white estimate below. */
    private float mVfCct;
    @Nullable private String mVfDngCamera;
    @Nullable private com.scamera.app.gallery.dng.DngColor mVfDng;
    /**
     * P212z (review): counted up by every configured session (onConfigured), so a session never reuses the last one's cached matrix
     * - a cached null too: a cold start into FULLRES had no plain table, a plain session in between taught it this light (or the
     * X7 Ultra's 6x / front anchor), and the identity stayed while the neutral held within 0.01 ln. The session the cache is of.
     */
    private volatile int mVfCcmSession;
    private int mVfCcmSessionSeen = -1;

    /**
     * P212h (A6 = M11): the photo's colour matrix for a viewfinder frame, column-major as the viewfinder takes it, at the frame's
     * neutral (the inverse of its white-balance gains, which the OPPO session fills with its own). P370 (A2): every RAW viewfinder
     * stream by the photo's rules (PhotoMatrix.viewfinder): a transform that is no identity, its rows normalised (per frame); an
     * identity, the X7 Ultra's tuned matrix (OppoTunedColor.previewMatrix, any session), on a plain Bayer stream the frame's
     * com.oplus.custom.ccm, on a mosaic stream the camera's plain-session matrix at that light (PlainColourMemory) then the OPPO
     * matrix. Null: none (the identity stays). The pick is looked up again when the neutral moves by more than 0.01 ln, the stream
     * kind changes or a session starts; a change of source is logged once. P370 (B4): its scene light and the tuned flag go to the
     * viewfinder's look (PhotoMatrix.viewfinderSceneCct / viewfinderTuned).
     */
    @Nullable
    private float[] viewfinderMatrix(@Nullable CameraCharacteristics c, @Nullable CaptureResult result, float[] gains,
                                     float[] reportedColumnMajor, boolean mosaic) {
        if (c == null || gains == null || !(gains[0] > 0f) || !(gains[1] > 0f) || !(gains[2] > 0f)) return null;
        final float[] neutral = {gains[1] / gains[0], 1f, gains[1] / gains[2]};
        final float lnR = (float) Math.log(neutral[0]), lnB = (float) Math.log(neutral[2]);
        final String camera = physicalID;
        final int session = mVfCcmSession;
        if (session != mVfCcmSessionSeen) {
            // P212z (review): a new session looks again (and logs its source once)
            mVfCcmSessionSeen = session;
            mVfCcmCamera = null;
            mVfCcmHow = "";
        }
        // the transpose of an identity is one (OplusCcm.identity tests each element against the identity's)
        final boolean identity = com.scamera.app.processing.color.OplusCcm.identity(reportedColumnMajor);
        final boolean cached = camera.equals(mVfCcmCamera) && mosaic == mVfCcmMosaic && identity == mVfCcmIdentity
                && !NativeViewfinder.moved(mVfCcmLnR, mVfCcmLnB, lnR, lnB);
        if (!identity) {
            // the result's own transform, rows normalised; its scene light with the neutral. P370 (review): the same transform at a
            // cached neutral returns the matrix made for it - no arrays and no Pick per frame on the camera thread
            if (cached && mVfCcm != null && java.util.Arrays.equals(reportedColumnMajor, mVfCcmReported)) return mVfCcm;
            final float[] reported = NativeViewfinder.columnMajor(reportedColumnMajor); // the transpose: row-major
            final com.scamera.app.processing.color.PhotoMatrix.Pick pick =
                    com.scamera.app.processing.color.PhotoMatrix.viewfinder(reported, null, Float.NaN, mosaic, null, null);
            final float[] out = NativeViewfinder.columnMajor(pick.rowMajor);
            if (!cached) remember(c, result, camera, neutral, lnR, lnB, mosaic, false, pick, out);
            else mVfCcm = out;
            System.arraycopy(reportedColumnMajor, 0, mVfCcmReported, 0, 9);
            return out;
        }
        if (cached) return mVfCcm;
        com.scamera.app.processing.color.PhotoMatrix.Pick pick;
        try {
            float[] tuned = null;
            float tunedCct = Float.NaN;
            if (com.scamera.app.processing.render.OppoTunedColor.applies()) {
                tuned = com.scamera.app.processing.render.OppoTunedColor.previewMatrix(c, neutral, camera);
                if (tuned != null) tunedCct = com.scamera.app.processing.render.OppoTunedColor.previewCct(c, neutral, camera);
            }
            final com.scamera.app.processing.color.PlainColourMemory.Lookup table = tuned == null && mosaic
                    ? com.scamera.app.processing.color.PlainColourMemory.lookup(camera, neutral) : null;
            float[] oplus = null;
            if (tuned == null && table == null && result != null && PreferenceKeys.scamDevSwitch("oplus_ccm", true)) {
                try {
                    oplus = com.scamera.app.processing.color.OplusCcm.parse(result.get(new CaptureResult.Key<>(
                            com.scamera.app.processing.color.OplusCcm.TAG_NAME, byte[].class)));
                } catch (RuntimeException noTag) {
                    oplus = null;
                }
            }
            pick = com.scamera.app.processing.color.PhotoMatrix.viewfinder(null, tuned, tunedCct, mosaic, table, oplus);
        } catch (RuntimeException e) {
            pick = com.scamera.app.processing.color.PhotoMatrix.viewfinder(null, null, Float.NaN, mosaic, null, null);
        }
        remember(c, result, camera, neutral, lnR, lnB, mosaic, true, pick,
                pick.rowMajor == null ? null : NativeViewfinder.columnMajor(pick.rowMajor));
        return mVfCcm;
    }

    /** P370 (A2, B4): the cache of {@link #viewfinderMatrix}, its scene light for the look, the log line of a new source. */
    private void remember(CameraCharacteristics c, @Nullable CaptureResult result, String camera, float[] neutral, float lnR, float lnB,
                          boolean mosaic, boolean identity, com.scamera.app.processing.color.PhotoMatrix.Pick pick,
                          @Nullable float[] columnMajor) {
        mVfCcmCamera = camera;
        mVfCcmLnR = lnR;
        mVfCcmLnB = lnB;
        mVfCcmMosaic = mosaic;
        mVfCcmIdentity = identity;
        mVfCcm = columnMajor;
        if (!camera.equals(mVfDngCamera)) {
            mVfDngCamera = camera;
            try {
                mVfDng = com.scamera.app.processing.color.PhotoMatrix.dngWhite(c);
            } catch (RuntimeException e) {
                mVfDng = null;
            }
        }
        int label = 0;
        try {
            label = result == null ? 0 : com.scamera.app.processing.color.PlainColourMemory.label(result.get(AWB_CCT_LABEL));
        } catch (RuntimeException noTag) {
            label = 0;
        }
        mVfCct = com.scamera.app.processing.color.PhotoMatrix.sceneCct(pick, camera, neutral, mVfDng, label);
        com.scamera.app.processing.color.PhotoMatrix.viewfinderSceneCct = mVfCct;
        com.scamera.app.processing.color.PhotoMatrix.viewfinderTuned = pick.tuned;
        final String how = pick.how + (mosaic ? " (mosaic stream)" : "");
        if (!Objects.equals(how, mVfCcmHow)) {
            mVfCcmHow = how;
            Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "viewfinder colour camera %s: %s (neutral %.3f / %.3f, scene %.0f K)",
                    camera, how, neutral[0], neutral[2], mVfCct));
        }
    }

    /** P370 (B6): the mosaic viewfinder's own white balance (an ISP that runs no 3A), its cameras of the same sensor. */
    private final com.scamera.app.processing.color.ViewfinderMosaicWb mVfMosaicWb =
            new com.scamera.app.processing.color.ViewfinderMosaicWb();
    @Nullable private String mVfSameSensorCamera;
    @Nullable private java.util.List<String> mVfSameSensor;

    /**
     * P370 (B6): the neutral {R/G, 1, B/G} a mosaic viewfinder frame shows when its ISP runs no 3A (ViewfinderMosaicWb: the photo's
     * teacher / locus on the frame's statistic, every 12th frame, smoothed), null for the HAL's gains (a plain stream, a running ISP,
     * nothing measured).
     */
    @Nullable
    private float[] viewfinderMosaicNeutral(Image img, @Nullable CameraCharacteristics c, @Nullable CaptureResult result, int cfa,
                                            float white, float[] black, int block, boolean mosaic) {
        final String id = physicalID == null ? null : physicalID.substring(physicalID.lastIndexOf('/') + 1);
        if (!mosaic || result == null || id == null || c == null || cfa < 0 || cfa > 3) return mVfMosaicWb.frame(id, mVfCcmSession, null, false, null, null, null, 0L);
        try {
            com.scamera.app.processing.color.MosaicNeutral.init(ScameraApp.getAppContext());
            com.scamera.app.processing.color.MosaicTeacher.init(ScameraApp.getAppContext());
            final com.scamera.app.processing.color.MosaicNeutral.Prior prior =
                    com.scamera.app.processing.color.MosaicNeutral.prior(c, id);
            if (!id.equals(mVfSameSensorCamera)) {
                mVfSameSensorCamera = id;
                mVfSameSensor = com.scamera.app.processing.color.MosaicTeacher.sameSensor(id, c, other -> {
                    try {
                        return mCameraManager == null ? null : mCameraManager.getCameraCharacteristics(other);
                    } catch (Exception e) {
                        return null;
                    }
                });
            }
            final int effBlock = Math.max(1, block);
            final com.scamera.app.processing.color.ViewfinderMosaicWb.Measure measure =
                    new com.scamera.app.processing.color.ViewfinderMosaicWb.Measure() {
                        private java.nio.ByteBuffer samples;
                        private java.nio.ByteBuffer samples() {
                            if (samples == null) samples = meterSamples(img);
                            return samples;
                        }
                        @Override public com.scamera.app.processing.color.MosaicNeutral.Stat stat() {
                            final java.nio.ByteBuffer s = samples();
                            return s == null ? null : com.scamera.app.processing.color.MosaicNeutral.measure2(s, img.getWidth(),
                                    img.getHeight(), mMeterStride, cfa, black, white, effBlock, mosaicSetup(c, result));
                        }
                        @Override public com.scamera.app.processing.color.MosaicNeutral.Result own() {
                            final java.nio.ByteBuffer s = samples();
                            return s == null ? null : com.scamera.app.processing.color.MosaicNeutral.estimate(id, s, img.getWidth(),
                                    img.getHeight(), mMeterStride, cfa, black, white, effBlock, prior);
                        }
                        @Override public float[] hal() {
                            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
                            if (n == null || n.length < 3 || !(n[1].floatValue() > 0f)) return null;
                            return new float[]{n[0].floatValue() / n[1].floatValue(), 1f, n[2].floatValue() / n[1].floatValue()};
                        }

                        // P597: the same measurement on a copy of the frame (whole rows), read on ViewfinderMosaicWb's thread
                        @Override public com.scamera.app.processing.color.ViewfinderMosaicWb.Measure detached() {
                            final java.nio.ByteBuffer s = samples();
                            if (s == null) return null;
                            final long copyStart = System.nanoTime();
                            final int w = img.getWidth(), h = img.getHeight();
                            final java.nio.ByteBuffer copy = vfWbCopy(s, w, h, mMeterStride);
                            if (copy == null) return null;
                            com.scamera.app.processing.VfStages.note(
                                    com.scamera.app.processing.VfStages.Stage.WB_COPY, System.nanoTime() - copyStart);
                            final float[] neutral = hal(), levels = black.clone();
                            final com.scamera.app.processing.color.MosaicNeutral.Setup setup = mosaicSetup(c, result);
                            return new com.scamera.app.processing.color.ViewfinderMosaicWb.Measure() {
                                @Override public com.scamera.app.processing.color.MosaicNeutral.Stat stat() {
                                    return com.scamera.app.processing.color.MosaicNeutral.measure2(copy, w, h, w * 2, cfa,
                                            levels, white, effBlock, setup);
                                }
                                @Override public com.scamera.app.processing.color.MosaicNeutral.Result own() {
                                    return com.scamera.app.processing.color.MosaicNeutral.estimate(id, copy, w, h, w * 2,
                                            cfa, levels, white, effBlock, prior);
                                }
                                @Override public float[] hal() {
                                    return neutral;
                                }
                            };
                        }
                    };
            return mVfMosaicWb.frame(id, mVfCcmSession, result, true, measure, prior, mVfSameSensor, android.os.SystemClock.elapsedRealtime());
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "mosaic viewfinder white balance: " + e);
            return null;
        }
    }

    /** P597: the frame the mosaic viewfinder's white balance measures on its own thread (reused; released with the camera). */
    private java.nio.ByteBuffer mVfWbCopy;

    /**
     * P597: {@code h} rows of {@code w} 16-bit samples of {@code samples} ({@code stride} bytes a row) into {@link #mVfWbCopy}
     * (rows of w * 2 bytes, native order); null when the frame does not hold them. ViewfinderMosaicWb asks for a copy only while
     * none is being measured, so the buffer is never refilled under its reader.
     */
    @Nullable
    private java.nio.ByteBuffer vfWbCopy(java.nio.ByteBuffer samples, int w, int h, int stride) {
        final long bytes = (long) w * h * 2;
        if (w <= 0 || h <= 0 || stride < w * 2 || bytes > Integer.MAX_VALUE || (long) (h - 1) * stride + w * 2L > samples.capacity()) return null;
        java.nio.ByteBuffer copy = mVfWbCopy;
        if (copy == null || copy.capacity() < bytes)
            mVfWbCopy = copy = java.nio.ByteBuffer.allocateDirect((int) bytes).order(java.nio.ByteOrder.nativeOrder());
        copy.clear();
        final java.nio.ByteBuffer src = samples.duplicate();
        if (stride == w * 2) {
            src.limit((int) bytes).position(0);
            copy.put(src);
        } else for (int y = 0; y < h; y++) {
            src.limit(y * stride + w * 2).position(y * stride);
            copy.put(src);
        }
        copy.flip();
        return copy.duplicate().order(java.nio.ByteOrder.nativeOrder());
    }

    /**
     * P91: the frame for the ring. With «zsl_memfd_ring» (scam_dev, default on) the camera's RAW is copied into the ring's
     * memfd and its Image goes back at once (WhyCam 1.05 / MotionCam: the reader holds a few buffers, not the whole ring); the
     * oldest ring frame makes room when every slot is taken. Without a memfd the Image itself is held, as before.
     */
    private RingFrame ringFrameLocked(Image img) {
        if (mZslRingUnavailable || !PreferenceKeys.scamDevSwitch("zsl_memfd_ring", true)) return RingFrame.of(img);
        // A camera without RAW (vivo X200 Pro front: YUV only, SCAM TEST 2026-10-11) rings YUV: a slot holds one plane, so a
        // multi-plane frame is held as the camera's Image, as before P91.
        if (img.getPlanes().length != 1) return RingFrame.of(img);
        final long need = Math.max(img.getPlanes()[0].getBuffer().capacity(), (long) img.getWidth() * img.getHeight() * 2);
        // P198: the native 50 MP session's ring is sized for its 96 MB frames (ZslRing.createFullRes); a ring of the other kind goes
        final boolean fullRes = nativeZslSession();
        if (mZslRingParked) {
            // P662: the ring kept over the restart serves this session when it is the ring this session would make
            mZslRingParked = false;
            final int ringFrames = zslRingCapacity(), wanted = fullRes ? fullResZslWanted() : 0;
            if (mZslRing != null && mZslRing.reusableFor(need, fullRes, ringFrames, wanted)) {
                Log.i("SCAM_CAPTURE", "PERF ZSL ring reused from the previous session (" + mZslRing.ringSlots() + " ring slots x "
                        + (mZslRing.slotBytes() >> 20) + " MB, already touched: no new memfd)");
                sessionArenaChanged();
            } else if (mZslRing != null) {
                Log.i("SCAM_CAPTURE", "ZSL ring of the previous session not reused ("
                        + mZslRing.notReusableWhy(need, fullRes, ringFrames, wanted) + "): made again");
                mZslRing.close();
                mZslRing = null;
            }
        }
        if (mZslRing == null || mZslRing.slotBytes() < need || mZslRing.fullRes() != fullRes) {
            if (mZslRing != null) {
                // a larger stream (another lens / mode): the ring's frames go, then the ring
                while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.pollFirst().close();
                mZslRing.close();
            }
            mZslRing = fullRes ? ZslRing.createFullRes(ScameraApp.getAppContext(), zslRingCapacity(), fullResZslWanted(), need)
                    : ZslRing.create(ScameraApp.getAppContext(), zslRingCapacity(), need);
            if (mZslRing == null) { mZslRingUnavailable = true; return RingFrame.of(img); }
            sessionArenaChanged();
        }
        // P365 step 8: a RAW10 / RAW12 frame of the 12 MP ring unpacked into its slot on arrival (zsl_ring_unpack)
        final boolean unpack = !fullRes && ringUnpack();
        RingFrame f = mZslRing.store(img, unpack);
        while (f == null && !mZslRingBuffer.isEmpty()) {
            mZslRingBuffer.pollFirst().close();
            f = mZslRing.store(img, unpack);
        }
        return f != null ? f : RingFrame.of(img);
    }

    private boolean mZslRingUnavailable;

    /** P662: the session's ring (made or reused) is the arena a merge worker is kept ready for (ScamNeuralClient.sessionArena). */
    private void sessionArenaChanged() {
        final ZslRing ring = mZslRing;
        final ImageReader raw = mImageReaderRaw;
        if (ring == null || raw == null) return;
        com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.sessionArena(ring.arenaId(),
                (long) raw.getWidth() * raw.getHeight());
    }

    /** The frames the ring keeps: the setting, within the memfd's slots (without it: the reader's images, P120). */
    private int ringCapacityLocked() {
        return mZslRing != null ? Math.min(zslRingCapacity(), mZslRing.ringSlots()) : Math.min(zslRingCapacity(), MAX_READER_IMAGES - 3);
    }

    /**
     * P200: a FULLRES 50 MP shot's merge starts: the 50 MP ring goes (its frames, then the memfd: the slots no shot frame holds
     * are given back at once, Allocator.arenaRelease); a new one is built after the merge.
     */
    private void releaseFullResRing() {
        synchronized (mRingLock) {
            if (mZslRing == null || !mZslRing.fullRes()) return;
            while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.pollFirst().close();
            mZslRing.closeNow(); // the merge reads the memory available right after this
            mZslRing = null;
            mZslRingParked = false;
            mZslRingUnavailable = false;
        }
        Log.i("SCAM_CAPTURE", "50 MP ZSL ring released for the merge");
    }

    /**
     * P91: the ring's memfd goes (camera closed); frames a shot still holds keep it until they are freed. P662: its release runs
     * on the ring's memory thread (ZslRing.close); {@code keep} (a camera restart, not a pause and not the FULLRES merge's close)
     * keeps the ring itself - its frames go - for the next session, which reuses it if it is the ring it would make
     * (ringFrameLocked; ring copies of 9-21 ms for 2-4 s after each restart while a fresh memfd took its first-touch faults).
     */
    private void releaseZslRing(boolean keep) {
        synchronized (mRingLock) {
            while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.pollFirst().close();
            if (keep && mZslRing != null) {
                if (!mZslRingParked) mZslRingParkedAtMs = android.os.SystemClock.elapsedRealtime(); // P680
                mZslRingParked = true;
            } else {
                if (mZslRing != null) mZslRing.close();
                mZslRing = null;
                mZslRingParked = false;
            }
            mZslRingUnavailable = false;
        }
    }

    /** P662: the ring was kept over a restart (releaseZslRing(true)) and no frame of the new session has taken it yet. */
    private boolean mZslRingParked;
    /** P662: a kept ring the new session has not taken this long after its configuration (video, a mode without RAW) goes. */
    static final long PARKED_RING_MS = 3000;
    /**
     * P680 (performance audit: video and back went over the 3 s and made the ring again - a new memfd and its first-touch faults):
     * a kept ring stays up to this long in all while the memory allows it ({@link #keepParkedRing}), checked every
     * {@link #PARKED_RING_MS}.
     */
    static final long PARKED_RING_MAX_MS = 15_000;
    /** P680: the memory available a kept ring needs beside it to stay longer than PARKED_RING_MS. */
    static final long PARKED_RING_MIN_AVAIL = 2L << 30;
    /** P680: when the ring was kept (elapsedRealtime ms). */
    private long mZslRingParkedAtMs;

    /**
     * P680: a kept ring unused for {@code parkedMs} stays for another {@link #PARKED_RING_MS}: within PARKED_RING_MAX_MS, not on a
     * phone of the low memory class, the system not low on memory and {@link #PARKED_RING_MIN_AVAIL} available beside it.
     */
    static boolean keepParkedRing(long parkedMs, long availBytes, boolean lowMemory, boolean lowRamClass) {
        return parkedMs + PARKED_RING_MS <= PARKED_RING_MAX_MS && !lowMemory && !lowRamClass && availBytes >= PARKED_RING_MIN_AVAIL;
    }

    private final Runnable mDropParkedRing = () -> {
        final long parkedMs;
        synchronized (mRingLock) {
            if (!mZslRingParked) return;
            parkedMs = android.os.SystemClock.elapsedRealtime() - mZslRingParkedAtMs;
        }
        long avail = -1;
        boolean lowMemory = false;
        try {
            final android.app.ActivityManager am = (android.app.ActivityManager) ScameraApp.getAppContext().getSystemService(Context.ACTIVITY_SERVICE);
            final android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            avail = info.availMem;
            lowMemory = info.lowMemory;
        } catch (RuntimeException ignored) {
        }
        final Handler handler = mBackgroundHandler;
        if (handler != null && keepParkedRing(parkedMs, avail, lowMemory, com.scamera.app.util.RamClass.get().low())) {
            handler.postDelayed(this.mDropParkedRing, PARKED_RING_MS);
            Log.i("SCAM_CAPTURE", "ZSL ring kept for this session unused for " + parkedMs + " ms: kept " + PARKED_RING_MS
                    + " ms more (" + (avail >> 20) + " MB available)");
            return;
        }
        dropParkedRing(false, "kept for this session unused for " + parkedMs + " ms");
    };

    /**
     * P662: a kept ring no frame of this session has taken goes ({@code now}: on this thread, P684 before a 200 MP series asks the
     * HAL for its full-size buffers; else on the ring's memory thread). True when a ring went.
     */
    private boolean dropParkedRing(boolean now, String why) {
        final ZslRing ring;
        synchronized (mRingLock) {
            if (!mZslRingParked) return false;
            mZslRingParked = false;
            ring = mZslRing;
            mZslRing = null;
        }
        final long start = System.nanoTime();
        if (ring != null) {
            if (now) ring.closeNow();
            else ring.close();
        }
        Log.i("SCAM_CAPTURE", "ZSL ring " + why + ": released" + (now ? " in " + (System.nanoTime() - start) / 1_000_000 + " ms" : ""));
        return true;
    }

    /**
     * P91: the shot's own frames (converted ring frames, the post-shutter series) in free slots of the ring's memfd, so the
     * whole burst is one arena for the worker; a separate arena when the ring has none.
     */
    private com.scamera.app.util.ShotArena shotArena(int frames, long frameBytes) {
        ZslRing ring;
        synchronized (mRingLock) { ring = mZslRing; }
        com.scamera.app.util.ShotArena a = ring != null ? ring.reserve(frames, frameBytes) : null;
        return a != null ? a : com.scamera.app.util.ShotArena.create(frames, frameBytes);
    }

    /**
     * P235 (speed-ux B3): the shot's own arena for {@code ring} frames (views of the ring's memfd, null for a PSL series) and
     * {@code stills}, made at the press when the burst would otherwise not be one memfd for the worker - the ring's free slots
     * ({@code reserved}) do not take every still, or a ring frame is not in the ring's memfd. Null: keep {@code reserved} (all of
     * it in one memfd already, a burst of 16 MP or less that the merge never moves, no memfd, or scam_dev "fullres_press_arena" 0).
     */
    @Nullable
    private static com.scamera.app.util.ShotArena pressArena(@Nullable com.scamera.app.util.ShotArena reserved,
            @Nullable List<ImageFrame> ring, int stills, long frameBytes, long pixels) {
        final int ringFrames = ring == null ? 0 : ring.size();
        boolean together = reserved != null && reserved.slots() >= stills;
        if (together && ring != null)
            for (ImageFrame f : ring) {
                final long[] where = f.buffer == null ? null : com.scamera.app.util.Allocator.arenaOf(f.buffer);
                if (where == null || where[0] != reserved.id()) { together = false; break; }
            }
        if (!pressArenaWanted(together, pixels, ringFrames + stills)) return null;
        return com.scamera.app.util.ShotArena.create(ringFrames + stills, frameBytes);
    }

    /** P235: a press arena is made for a burst above 16 MP (the merge's moveIntoArena) not in one memfd, of at most 128 frames (ShotArena, P578). */
    static boolean pressArenaWanted(boolean oneMemfd, long pixels, int frames) {
        return !oneMemfd && pixels > ScamHybridBurst.CLASSIC_INPUT_PIXELS && frames > 0 && frames <= 128
                && PreferenceKeys.scamDevSwitch("fullres_press_arena", true);
    }

    /**
     * P237 (speed-ux B5): once per process, {@link #WARM_UP_DELAY_MS} after a session opened, the post-pipeline programs whose
     * driver binary is missing from the disk cache are compiled on a background thread (GLProg.warmUp) while the camera is idle -
     * no shot in flight, nothing processing, the camera open; a shot meanwhile stops it between two programs. scam_dev gl_warm_up 0
     * off (DevKeys).
     */
    private void scheduleProgramWarmUp() {
        if (sWarmUpScheduled || !PreferenceKeys.scamDevSwitch("gl_warm_up", true)) return;
        sWarmUpScheduled = true;
        final Thread t = new Thread(() -> {
            try {
                Thread.sleep(WARM_UP_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!cameraIdle()) {
                sWarmUpScheduled = false; // the next session tries again
                return;
            }
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            com.scamera.app.processing.opengl.GLProg.warmUp(this::cameraIdle);
        }, "scam-gl-warm-up");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean sWarmUpScheduled;
    static final long WARM_UP_DELAY_MS = 4000;

    /**
     * P240 (speed-ux C4): routes built but never run on a phone say so on their first use (once per install, the message card):
     * the X100 / X300 Ultra 200 MP the stock way (P208: the viewfinder in the normal mode, 200 MP for the stills only).
     */
    private void experimentalRouteNotice() {
        if (!stillOnlyFullRes() || VivoSdkFullRes.oppo()) return;
        final String text = ExperimentalRoutes.firstUse(ScameraApp.getAppContext(), "p208_stock_200mp",
                Lang.t("Экспериментально: 200 Мп как в стоковой камере (видоискатель в обычном режиме) ещё не проверено на телефоне. Если снимок не выйдет — выключите «vivo_fullres200_stock» в «Для разработчика»",
                        "Experimental: 200 MP the stock camera's way (the viewfinder in the normal mode) is not tested on a phone yet. If the shot fails, switch off “vivo_fullres200_stock” in “For developers”"));
        if (text != null) showToast(text);
    }

    /** P237: no shot in flight and nothing processing, the camera open (the idle warm-up's condition). */
    private boolean cameraIdle() {
        return isCameraResumed && !mShotInProgress && mVivoSdkCapture == null && !isProcessing && sScamPending.get() == 0
                && !fullResMergeRunning() && !mClosedForProcessing;
    }

    public static int zslRingCapacity() {
        // One global, user-adjustable capacity. Capture counts never resize the ring.
        // P179: the plain session that shows the viewfinder while a 200 MP shot is merged holds a small ring (no shot is taken
        // then; the merge needs the memory)
        if (PixelPro10.fullResSuspended() || fullResMergeRunning()) return Math.min(SUSPENDED_RING, PreferenceKeys.getZslBufferCountValue());
        // P646: the YUV route holds the camera's Images (12 MB, P010 24 MB each): its series after the shutter takes no ring frame
        // (the ring only meters), its ZSL capture the route's own frames
        final YuvMergeRoute.Session yuv = YuvMergeRoute.current();
        // P650: the YUV ring (the «Из буфера ZSL» route) holds the general «Кадры ZSL» count (+2 margin), the same the RAW ring
        // and the after-shutter YUV plan now use; getZslBufferCountValue stays the hard memory ceiling.
        // P656: and within the ring's memory (YuvMergePlan.ringFrames: a 10-bit ring of 65 + 2 frames held ~1.3 GB of Images)
        if (yuv != null) return yuv.afterShutter ? SUSPENDED_RING
                : YuvMergePlan.ringFrames(PreferenceKeys.getZslBufferCountValue(), PreferenceKeys.getHybridZslFrames(), yuv.frameBytes, SUSPENDED_RING);
        // P578 (owner, 2026-11-09: up to 65 frames in the merge): the ring holds at least the merge's N, so a long merge is not cut
        // to the buffer's 50 (ZslRing.create still keeps it within the memory budget)
        return Math.max(PreferenceKeys.getZslBufferCountValue(), Math.min(100, PreferenceKeys.getHybridZslFrames()));
    }

    /** P179: ring slots of the plain session during a 200 MP merge. */
    static final int SUSPENDED_RING = 4;

    private long scamZslShutterTimestamp;
    private int mOpenRetries;

    /**
     * P27: the Hybrid's N without the SCAM HDR planner (whose throws cost Hybrid shots): the ring frame at the shutter, else
     * the newest ring result before it, the preview result, the preview AE values. Null when no N exposure is known at all
     * (the Hybrid then lets the camera's AE expose its frames).
     */
    private ScamStockAe.Plan hybridNPlan() {
        return hybridNPlan(false);
    }

    private ScamStockAe.Plan hybridNPlan(boolean manualPsl) {
        // P84: a hand-set shutter longer than 1/8 s: N at that shutter, exposed after the shutter (PSL)
        final long pslNs=manualPsl?paramController.pslShutterNs():0;
        if(pslNs>0) {
            final int pslIso=paramController.pslIso(pslNs);
            mLastZslClipFraction=0;
            Log.i("SCAM_CAPTURE","bracket=HYBRID N="+pslNs+"ns ISO"+pslIso+" from=manual_psl (shutter longer than the viewfinder's 1/10 s)");
            return ScamStockAe.Plan.nOnly(pslNs,pslIso,scamZslShutterTimestamp,mSessionGeneration.get());
        }
        CaptureResult n;String source="zsl_cutoff";
        synchronized (mRingLock) {
            n=mHexZslResults.get(scamZslShutterTimestamp);
            if(n==null) {
                long best=Long.MIN_VALUE;source="zsl_newest";
                for(java.util.Map.Entry<Long,TotalCaptureResult> e:mHexZslResults.entrySet())
                    if(e.getKey()<=scamZslShutterTimestamp && e.getKey()>best){best=e.getKey();n=e.getValue();}
            }
        }
        if(n==null){n=mPreviewCaptureResult;source="preview";}
        Long ns=n==null?null:n.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso=n==null?null:n.get(CaptureResult.SENSOR_SENSITIVITY);
        long shutter=ns!=null&&ns>0?ns:0;int sensitivity=iso!=null&&iso>0?iso:0;
        if(shutter<=0||sensitivity<=0){shutter=mPreviewExposureTime;sensitivity=mPreviewIso;source="preview_ae";}
        mLastZslClipFraction=zslClipFraction();
        if(shutter<=0||sensitivity<=0){
            Log.w("SCAM_CAPTURE","bracket=HYBRID no N exposure known: the camera's AE exposes the frames");
            return null;
        }
        // P501 (Pixel 10 Pro, dark room: the merged RAW at 0.15 % of white, the post's gain x45): the camera's post-RAW gain of the
        // N frame (the viewfinder's brightness the RAW never gets), for the log
        Integer rawBoost=null;
        try{rawBoost=n==null?null:n.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);}catch(RuntimeException ignored){}
        Log.i("SCAM_CAPTURE","bracket=HYBRID N="+shutter+"ns ISO"+sensitivity+" from="+source
                +(rawBoost!=null&&rawBoost!=100?" postRawBoost="+rawBoost:""));
        return ScamStockAe.Plan.nOnly(shutter,sensitivity,scamZslShutterTimestamp,mSessionGeneration.get());
    }

    /** Fraction of clipped RAW samples in the newest buffered ZSL frame (sparse sample). */
    /** Clipped fraction of the newest buffered RAW at the last plan (the ring is drained before the hybrid plan is built). */
    private float mLastZslClipFraction;
    /** P409: the deep-shadow share of the frame zslClipFraction last read (HybridPlan.setDeepShadow; 0: unknown / packed). */
    private volatile float mLastZslDeepShadow;
    /** P410: the shadow share (within 1/16 of the range above black) of that frame; NaN: unknown. */
    private volatile float mLastZslShadow = Float.NaN;

    /**
     * P643d «ScamMaster»: the newest plain 16-bit ring frame as sw x sh linear RGB cells (each the mean of up to 3 x 3 of its RAW
     * quads; black / white of the stream, the frame's white balance and lens shading map; 1 = the frame's white) over the part of
     * the sensor the viewfinder shows (the zoom's crop, cut to the stream's aspect) - what the photo will be made of (P643: the
     * photo mode's capture, at the viewfinder's exposure) - and the frame's colour matrix (white-balanced RGB to linear sRGB; null:
     * none), for the tone preview's picture. Null when no plain frame is there (a packed stream: no preview of the tone).
     */
    @androidx.annotation.Nullable
    private float[][] oldstyleFrame(int sw, int sh) {
        if (mCameraCharacteristics == null) return null;
        try {
            synchronized (mRingLock) {
                RingFrame newest = null;
                CaptureResult meta = null;
                int looked = 0;
                // P651 (owner, 2026-11-13, vivo V2419A: a green viewfinder in ScamMaster): the newest ring frame often comes before
                // its capture result (MediaTek); without it the thumbnail took no white balance and no colour matrix - the camera's
                // raw green. A frame is taken only with its result (its gains and matrix), the newest such of the last 8.
                for (java.util.Iterator<RingFrame> it = mZslRingBuffer.descendingIterator(); it.hasNext() && looked < 8; looked++) {
                    final RingFrame candidate = it.next();
                    final CaptureResult r = mHexZslResults.get(candidate.getTimestamp());
                    if (r == null || r.get(CaptureResult.COLOR_CORRECTION_GAINS) == null) continue;
                    if (candidate.getFormat() == ImageFormat.RAW_SENSOR && com.scamera.app.processing.RawPayloadCheck.check(
                            candidate, rawPayloadWhite(r)).plain()) { newest = candidate; meta = r; break; }
                }
                if (newest == null) {
                    if (!mOldstyleNoResultLogged) {
                        mOldstyleNoResultLogged = true;
                        Log.i("SCAM_CAPTURE", "ScamMaster viewfinder: no ring frame with its capture result yet: the last picture stays");
                    }
                    return null;
                }
                mOldstyleNoResultLogged = false;
                final CameraCharacteristics lc = mCameraCharacteristicsMap.get(physicalID);
                final com.scamera.app.processing.RawLevels.Levels levels =
                        com.scamera.app.processing.RawLevels.current(lc == null ? mCameraCharacteristics : lc, null);
                // P651: the CFA of the physical camera the ring streams (the logical camera's may differ), as its levels above
                Integer cfa = lc == null ? null : lc.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                if (cfa == null) cfa = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                android.hardware.camera2.params.RggbChannelVector wb = meta == null ? null : meta.get(CaptureResult.COLOR_CORRECTION_GAINS);
                final float[] gains = wb == null ? new float[]{1f, 1f, 1f}
                        : new float[]{wb.getRed(), (wb.getGreenEven() + wb.getGreenOdd()) * 0.5f, wb.getBlue()};
                // the zoom: the crop region over the active array, and the zoom ratio round its centre
                final Rect active = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                final Rect region = meta == null ? null : meta.get(CaptureResult.SCALER_CROP_REGION);
                Float zoom = meta == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R ? null : meta.get(CaptureResult.CONTROL_ZOOM_RATIO);
                final float[] part = com.scamera.app.processing.oldstyle.OldstyleAe.shownPart(
                        active == null ? 0 : active.width(), active == null ? 0 : active.height(),
                        region == null || active == null ? null : new int[]{region.left - active.left, region.top - active.top, region.width(), region.height()},
                        zoom == null ? 1f : zoom);
                final int fw = newest.getWidth(), fh = newest.getHeight(), stride = newest.rowStride() / 2;
                final Size stream = mPreviewSize;
                final float[] shown = com.scamera.app.processing.oldstyle.OldstyleAe.shownPart(part, fw, fh, stream == null
                        ? 0f : Math.max(stream.getWidth(), stream.getHeight()) / (float) Math.max(1, Math.min(stream.getWidth(), stream.getHeight())));
                part[0] = shown[0];
                part[1] = shown[1];
                part[2] = shown[2];
                part[3] = shown[3];
                final int x0 = Math.min(fw - 16, Math.round(part[0] * fw)) & ~1, y0 = Math.min(fh - 16, Math.round(part[1] * fh)) & ~1;
                final int cw = Math.max(16, Math.min(fw - x0, Math.round(part[2] * fw))) & ~1, ch = Math.max(16, Math.min(fh - y0, Math.round(part[3] * fh))) & ~1;
                final java.nio.ShortBuffer all = newest.buffer().duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                all.position(Math.max(0, y0) * stride + Math.max(0, x0));
                final android.hardware.camera2.params.LensShadingMap map = meta == null
                        || com.scamera.app.processing.render.Parameters.rawShadingAlreadyApplied(lc == null ? mCameraCharacteristics : lc)
                        ? null : meta.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
                float[] lsc = null;
                if (map != null && map.getColumnCount() > 1 && map.getRowCount() > 1) {
                    lsc = new float[map.getGainFactorCount()];
                    map.copyGainFactors(lsc, 0);
                }
                final float[] rgb = com.scamera.app.processing.oldstyle.OldstyleAe.bayerBox(all.slice(), stride, cw, ch,
                        cfa == null ? 0 : cfa, levels.black, levels.white, gains, sw, sh, 3, lsc, map == null ? 0 : map.getColumnCount(),
                        map == null ? 0 : map.getRowCount(), x0, y0, fw, fh);
                final android.hardware.camera2.params.ColorSpaceTransform cst = meta == null ? null : meta.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
                float[] ccm = null;
                if (cst != null) {
                    ccm = new float[9];
                    for (int i = 0; i < 9; i++) ccm[i] = cst.getElement(i % 3, i / 3).floatValue();
                }
                if (mOldstyleFrameLogs < 3) { // P651: what the tone preview's picture is made of (the first frames of a session)
                    mOldstyleFrameLogs++;
                    Log.i("SCAM_CAPTURE", "ScamMaster viewfinder frame: camera " + physicalID + " " + fw + "x" + fh + " cfa " + cfa
                            + " gains " + java.util.Arrays.toString(gains) + " matrix " + (ccm == null ? "none" : "camera")
                            + " shading " + (lsc == null ? "none" : map.getColumnCount() + "x" + map.getRowCount()));
                }
                return new float[][]{rgb, ccm};
            }
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "Oldstyle: the viewfinder's frame for the tone preview not read", e);
            return null;
        }
    }

    /** P651: the «no frame with its result» line once until a frame with its result is found again. */
    private boolean mOldstyleNoResultLogged;
    private int mOldstyleFrameLogs;

    /** The tone preview's picture of the module at most this often (the ring's newest frame). */
    private static final long OLDSTYLE_VIEWFINDER_NS = 300_000_000L;
    private long mOldstyleMeterNs;
    private boolean mOldstyleToneLive;

    /**
     * P643d «ScamMaster»: the viewfinder's base under the style - the preview of the tone. The module itself draws the photo of the
     * ring's newest frame (a 160 x 120 thumbnail of the shown part: the tone's own plan of that frame, the settings, the style's
     * exposure, the frame's colour matrix - the photo is taken at this exposure, P643), and OldstyleLive carries the screen frame
     * to that picture (its bilateral grid); the version and the settings as they are now (a V1 / V2 switch, a moved row show with
     * the next picture). Nothing here touches the camera: the viewfinder's exposure is the photo mode's.
     */
    private void oldstyleViewfinder() {
        final com.scamera.app.processing.oldstyle.OldstyleLive live =
                com.scamera.app.processing.oldstyle.OldstyleLive.shared();
        if (!com.scamera.app.settings.OldstyleMode.on()) {
            if (mOldstyleToneLive || live.wantsScreen()) live.off(); // whoever turned it on (another controller instance too)
            mOldstyleToneLive = false;
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtimeNanos();
        if (mOldstyleToneLive && now - mOldstyleMeterNs < OLDSTYLE_VIEWFINDER_NS) return;
        mOldstyleMeterNs = now;
        mOldstyleToneLive = true;
        final boolean v2 = com.scamera.app.settings.OldstyleMode.v2();
        final com.scamera.app.processing.oldstyle.OldstyleAe.Params params =
                com.scamera.app.settings.OldstyleMode.params(v2);
        if (!params.vfTone) {
            live.off(); // «Тон в видоискателе» off: the photo mode's viewfinder under the style
            return;
        }
        // as the shot's snapshot takes it (ShotLook.Oldstyle): the style's exposure before the tone
        params.exposureEv = com.scamera.app.settings.OldstyleMode.style().exposureEv();
        // (the module's local tone depends a little on the frame's size: on the host a 160 x 120 picture's mean is within 7 % of
        // the photo's, a 240 x 180 one no closer on every scene, at twice the cost - tools/check_oldstyle_live.py)
        final int sw = 160, sh = 120;
        final float[][] frame = oldstyleFrame(sw, sh);
        if (frame == null) return; // no plain frame now: the last fit stays until it is stale (OldstyleLive.STALE_NS)
        live.photo(System.nanoTime(), com.scamera.app.processing.oldstyle.OldstyleLive.photoRgb(frame[0], sw, sh, frame[1], params),
                sw, sh, params);
    }

    private float zslClipFraction() {
        mLastZslDeepShadow = 0f;
        mLastZslShadow = Float.NaN;
        // The newest ring frame whose RAW is plain 16-bit: a packed payload reads as ~62 % clipped (X100 Ultra) and planned
        // the bracket from garbage. None plain: no clipping is assumed (the shot then takes N after the shutter).
        RingFrame newest = null;
        com.scamera.app.processing.RawPayloadCheck.Layout packed = null;
        synchronized (mRingLock) {
            int looked = 0;
            for (java.util.Iterator<RingFrame> it = mZslRingBuffer.descendingIterator(); it.hasNext() && looked < 4; looked++) {
                RingFrame candidate = it.next();
                com.scamera.app.processing.RawPayloadCheck.Result payload = com.scamera.app.processing.RawPayloadCheck.check(
                        candidate, rawPayloadWhite(mHexZslResults.get(candidate.getTimestamp())));
                if (payload.plain()) { newest = candidate; break; }
                // Xiaomi 17 Ultra tele (logical camera 0): packed MIPI RAW10 / RAW14 behind RAW_SENSOR; its clipping is read from
                // the high bytes (every tele shot planned clip = 0 and no short frame before, 2026-10-10)
                if (candidate.getFormat() == ImageFormat.RAW_SENSOR) {
                    packed = packedLayout(candidate);
                    if (packed != null) { newest = candidate; break; }
                }
            }
            if (newest == null && looked > 0) Log.w("SCAM_CAPTURE", "ZSL clip estimate: no plain 16-bit RAW among the newest " + looked);
        }
        if (newest == null || mCameraCharacteristics == null || newest.getFormat() != ImageFormat.RAW_SENSOR) return 0f;
        try {
            // P104 / P127: the stream clips at the white of its own bit depth (RawLevels: DCG 14 bit under a declared 1023)
            CameraCharacteristics lc = mCameraCharacteristicsMap.get(physicalID);
            int limit = (int) (com.scamera.app.processing.RawLevels.current(
                    lc == null ? mCameraCharacteristics : lc, null).white * 0.95f);
            if (packed != null) {
                // MIPI RAW10 / RAW14: bytes 0..3 of each 5- / 7-byte group are the high 8 bits of four samples (RAW14 read >> 4:
                // its high byte is the 10-bit value >> 2 as RAW10's)
                // P641: RAW12 (a dual-gain session's 12-bit payload): bytes 0..1 of each 3-byte group, the high 8 bits of two samples
                final boolean packed12 = packed.format == ImageFormat.RAW12;
                final int group = com.scamera.app.util.RawUnpack.isRaw14(packed.format) ? 7 : packed12 ? 3 : 5;
                java.nio.ByteBuffer bytes = newest.buffer().duplicate();
                int w = newest.getWidth(), h = newest.getHeight();
                int limitHigh = packed.format == com.scamera.app.util.RawUnpack.RAW14 ? limit >> 6 : packed12 ? limit >> 4 : limit >> 2;
                long clipped = 0, total = 0;
                byte[] row = new byte[com.scamera.app.util.RawUnpack.packedRowBytes(packed.format, w)];
                for (int y = 0; y < h; y += 8) {
                    bytes.position(y * packed.stride);
                    bytes.get(row, 0, row.length);
                    for (int g = 0; g + group - 1 < row.length; g += group) {
                        if ((row[g] & 0xff) >= limitHigh) clipped++;
                        total++;
                    }
                }
                return total == 0 ? 0f : (float) clipped / total;
            }
            java.nio.ShortBuffer data = newest.buffer().duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            int stride = newest.rowStride() / 2, w = newest.getWidth(), h = newest.getHeight();
            // P409: samples within 1/64 of the range above black: the deep shadows the bracketed frames are for (HybridPlan)
            final com.scamera.app.processing.RawLevels.Levels levels =
                    com.scamera.app.processing.RawLevels.current(lc == null ? mCameraCharacteristics : lc, null);
            float black = 0f;
            for (float b : levels.black) black += b / levels.black.length;
            final int deepBelow = Math.round(black + (levels.white - black) / 64f);
            final int shadowBelow = Math.round(black + (levels.white - black) / 16f);   // P410
            long clipped = 0, deep = 0, shadow = 0, total = 0;
            // Bulk row reads: per-sample get() on the direct view cost ~10 ms per press.
            short[] row = new short[w];
            for (int y = 0; y < h; y += 8) {
                data.position(y * stride);
                data.get(row, 0, w);
                for (int x = 0; x < w; x += 4) {
                    final int v = row[x] & 0xffff;
                    if (v >= limit) clipped++;
                    else if (v <= shadowBelow) {
                        shadow++;
                        if (v <= deepBelow) deep++;
                    }
                    total++;
                }
            }
            mLastZslDeepShadow = total == 0 ? 0f : (float) deep / total;
            mLastZslShadow = total == 0 ? Float.NaN : (float) shadow / total;
            return total == 0 ? 0f : (float) clipped / total;
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "ZSL clip estimate unavailable: " + e);
            return 0f;
        }
    }

    /** White level for RawPayloadCheck: the previewed physical camera, the frame's dynamic white when its result is known. */
    private int rawPayloadWhite(CaptureResult result) {
        CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
        return com.scamera.app.processing.RawPayloadCheck.whiteLevel(c == null ? mCameraCharacteristics : c, result);
    }

    /**
     * Ring RAWs whose payload is not plain 16-bit (RawPayloadCheck) leave the burst before anything reads them. With fewer than
     * four left the existing normal-back path takes N after the shutter (those frames were plain on the X100 Ultra).
     */
    private void dropNonPlainRaw(List<RingFrame> images, java.util.Map<Long, TotalCaptureResult> results) {
        mZslPackedStride.clear();
        int dropped = 0, packed = 0;
        String first = null, packedFirst = null;
        for (java.util.Iterator<RingFrame> it = images.iterator(); it.hasNext();) {
            RingFrame image = it.next();
            com.scamera.app.processing.RawPayloadCheck.Result payload =
                    com.scamera.app.processing.RawPayloadCheck.check(image, rawPayloadWhite(results.get(image.getTimestamp())));
            if (payload.plain()) continue;
            if (image.getFormat() == ImageFormat.RAW_SENSOR) {
                // Xiaomi 17 Ultra tele through logical camera 0: packed MIPI RAW10 (mode 4) or RAW14 (mode 9, P72) behind
                // RAW_SENSOR, unpacked on copy.
                com.scamera.app.processing.RawPayloadCheck.Layout layout = packedLayout(image);
                if (layout != null) {
                    mZslPackedStride.put(image.getTimestamp(), layout);
                    if (packedFirst == null) packedFirst = layout.describe();
                    packed++;
                    continue;
                }
            }
            if (first == null) {
                first = payload.error;
                com.scamera.app.processing.RawPayloadCheck.dumpOnce(image, payload, physicalID);
            }
            image.close();
            it.remove();
            dropped++;
        }
        if (dropped > 0) Log.w("SCAM_HDR", "ZSL: " + dropped + " ring RAWs dropped (" + first + "), " + images.size() + " kept");
        if (packed > 0) Log.w("SCAM_HDR", "ZSL: " + packed + " ring RAWs hold " + packedFirst + " (first), unpacked on copy");
    }

    /** P72: the packed layout behind a RAW_SENSOR image of this camera (RAW10 / RAW14), or null. */
    private com.scamera.app.processing.RawPayloadCheck.Layout packedLayout(Image image) {
        return packedLayout(RingFrame.of(image));
    }

    private com.scamera.app.processing.RawPayloadCheck.Layout packedLayout(RingFrame image) {
        CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
        return com.scamera.app.processing.RawPayloadCheck.packedLayout(image.buffer(),
                image.getWidth(), image.getHeight(),
                com.scamera.app.processing.RawPayloadCheck.blackLevel(c == null ? mCameraCharacteristics : c),
                rawPayloadWhite(null));
    }

    /** Ring RAWs (by timestamp) whose RAW_SENSOR buffer holds packed MIPI RAW10 / RAW14, with the layout. */
    private final java.util.Map<Long, com.scamera.app.processing.RawPayloadCheck.Layout> mZslPackedStride = new java.util.HashMap<>();

    private List<ImageFrame> drainZslNormalFrames(int requestedCount,ScamStockAe.Plan stockPlan,boolean defer) {
        return drainZslNormalFrames(requestedCount,stockPlan,defer,false);
    }

    /**
     * P27, {@code hybrid}: the Hybrid needs one usable N frame, never SCAM HDR's four at the plan's exact exposure. Its ring
     * selection keeps the frames at the newest frame's exposure (0.05 EV, as before) and, only when fewer than
     * min(requested, 8) remain (a press during an AE ramp, the first press after a lens switch), adds frames up to 0.5 EV
     * darker, nearest first; never brighter (the merge would accumulate their clipped samples). Each frame keeps its own
     * exposure (the worker normalises by it). The ring is never emptied for the Hybrid while one plain frame is left (X300
     * Ultra 2026-10-06: 3 of 15 ring frames matched, the shot fell into SCAM HDR's strict normal-back and was lost).
     */
    private List<ImageFrame> drainZslNormalFrames(int requestedCount,ScamStockAe.Plan stockPlan,boolean defer,boolean hybrid) {
        List<RingFrame> rawImages;
        java.util.Map<Long,TotalCaptureResult> selectedMetadata;
        synchronized (mRingLock) {
            rawImages = new ArrayList<>(mZslRingBuffer);
            mZslRingBuffer.clear();
            mScamRingFrozen = false;
            selectedMetadata = new HashMap<>(mHexZslResults);
            mHexZslResults.clear();
        }
        mNativeZslBase=null;
        dropNonPlainRaw(rawImages, selectedMetadata);
        if (PreferenceKeys.isScamEnabled()) {
            // RAW may arrive before its TotalCaptureResult. Select from matched
            // pairs BEFORE taking the newest N images, so older complete ZSL
            // frames can fill the burst without guessing another frame's ISO.
            boolean firstMismatch=true;
            for (java.util.Iterator<RingFrame> it = rawImages.iterator(); it.hasNext();) {
                RingFrame image = it.next();
                TotalCaptureResult result = selectedMetadata.get(image.getTimestamp());
                Long exposure = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
                boolean matchesPlan=true;
                if(stockPlan!=null && !hybrid)try{stockPlan.verifyZslNormal(result,scamZslShutterTimestamp);}
                catch(RuntimeException mismatch){matchesPlan=false;
                    if(result!=null && firstMismatch){firstMismatch=false;Log.w("SCAM_HDR",mismatch.getMessage());}}
                if (exposure == null || exposure <= 0 || iso == null || iso <= 0
                        || !matchesPlan
                        || (scamZslShutterTimestamp <= 0 || image.getTimestamp() > scamZslShutterTimestamp)) {
                    Log.w("SCAM_HDR", "Skip unpaired ZSL RAW timestamp=" + image.getTimestamp()
                            + " result=" + (result != null) + " exposureNs=" + exposure + " ISO=" + iso);
                    image.close();
                    it.remove();
                }
            }
            if (hybrid && !rawImages.isEmpty()) selectHybridRing(rawImages, selectedMetadata, requestedCount);
            Log.i("SCAM_HDR", "ZSL matched RAWs=" + rawImages.size() + " requested=" + requestedCount);
            if(!hybrid && stockPlan!=null && rawImages.size()<4) {
                // Stock normal-back (VAF scamNormalBack 0x29a714): when the
                // planned N exposure is not what the preview stream ran at
                // (low light: longer N than preview), the stock takes N after
                // the shutter. The caller submits all seven planned requests.
                for(RingFrame image:rawImages)image.close();
                Log.i("SCAM_HDR","normalBack: no four buffered RAWs at planned N; N captured after shutter");
                return new ArrayList<>();
            }
        }
        rawImages.sort(java.util.Comparator.comparingLong(RingFrame::getTimestamp));
        int take = Math.min(rawImages.size(), Math.max(0, requestedCount));
        int skip = rawImages.size() - take;
        for (int i = 0; i < skip; i++) rawImages.get(i).close();

        if (PreferenceKeys.isScamEnabled() && take > 0) {
            mNativeZslBase = selectedMetadata.get(rawImages.get(rawImages.size()-1).getTimestamp());
            Log.i("SCAM_HDR", "ZSL shutter cutoff=" + scamZslShutterTimestamp
                    + " selected=" + take + " newest=" + rawImages.get(rawImages.size()-1).getTimestamp());
        }
        double exposureSeconds = 1.0;
        double isoValue = 100.0;
        CaptureResult baseResult=mNativeZslBase!=null?mNativeZslBase:mPreviewCaptureResult;
        if (baseResult != null) {
            Long exp = baseResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = baseResult.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exp != null) exposureSeconds = exp / 1_000_000_000.0;
            if (iso != null) isoValue = iso.doubleValue();
        }
        double exposureProduct = exposureSeconds * isoValue;
        List<ImageFrame> selected = new ArrayList<>();
        for (int i = skip; i < rawImages.size(); i++) {
            RingFrame img = rawImages.get(i);
            int format = img.getFormat();
            int rowStride = img.rowStride();
            int pixelStride = img.pixelStride();
            int width = com.scamera.app.util.Allocator.isPackedRaw(format) ? img.getWidth()
                    : (pixelStride > 0 ? rowStride / pixelStride : img.getWidth());
            int height = img.getHeight();
            int capacity = img.buffer().capacity();
            com.scamera.app.processing.RawPayloadCheck.Layout packedLayout = mZslPackedStride.get(img.getTimestamp());
            if (packedLayout != null) {
                format = packedLayout.format;
                width = img.getWidth();
                rowStride = packedLayout.stride;
                capacity = packedLayout.stride * height;
            }
            int offset = 0;
            if (!mShutterIszAspect4x3 && ScameraApp.getSettings().wide169 && width > height) {
                // P118: the one 16:9 crop of every frame of the shot (whole colour blocks, the post-shutter frames the same size);
                // P230: of the DCG mode's rows, not of its empty band
                final int full = SensorModeBand.imageRows(width, img.getHeight());
                height = com.scamera.app.processing.Wide169.rows(width, full);
                offset = rowStride * com.scamera.app.processing.Wide169.firstRow(width, full);
                capacity = rowStride * height;
            }
            Allocator.binning = ScameraApp.getSettings().binning;
            // Deferred: the copy of ~20 RAWs (~200 ms) runs after the bracket is
            // submitted, in parallel with its exposure (startZslCopy).
            // P646: a YUV ring frame keeps its colour planes (packed at its copy)
            final boolean yuvFrame = com.scamera.app.processing.yuv.YuvFrames.isYuv(format);
            ImageFrame frame = defer || yuvFrame
                    ? ImageFrame.deferred(img, format, width, rowStride, offset, capacity)
                    : new ImageFrame(img.buffer(), format, width, rowStride, offset, capacity);
            if (yuvFrame) {
                com.scamera.app.processing.yuv.YuvFrames.deferPack(frame, img, format);
                if (!defer) frame.materialize();
            }
            // P365: a frame unpacked into its ring slot on arrival keeps its stream's RAW10 / RAW12 (the post-shutter frames' layout)
            frame.timestamp = img.getTimestamp();frame.fromZsl=true;frame.sourceFormat=ZslRing.sourceFormat(img, format);
            frame.setCaptureMetadata(selectedMetadata.get(frame.timestamp));
            frame.width = ScameraApp.getSettings().binning ? width / 2 : width;
            frame.height = ScameraApp.getSettings().binning ? height / 2 : height;
            if (yuvFrame) { frame.width = img.getWidth(); frame.height = img.getHeight(); } // the whole packed frame
            if (!defer) img.close();
            exposureByFrame.put(frame.timestamp, frame.measuredExposure > 0 && frame.measuredIso > 0
                    ? frame.measuredExposure / 1e9 * frame.measuredIso : exposureProduct);
            selected.add(frame);
        }
        return selected;
    }

    /**
     * P78: a camera without OIS (Redmi Note 11 Pro: 60 ms handheld frames at night, gyro shake 10..60 x apart inside one burst)
     * takes the steadiest of the newest 2 x requested ring frames at the exposure instead of simply the newest; the newest frame
     * stays (the shutter moment). Reorders {@code exact} (newest first) so that its first {@code requested} frames are the kept ones.
     */
    private void steadiestFirst(List<RingFrame> exact, java.util.Map<Long,TotalCaptureResult> metadata, int requested) {
        if (requested < 2 || exact.size() <= requested || hasOisHardware(mCameraCharacteristics)) return;
        if (!PreferenceKeys.scamDevSwitch("zsl_steadiest", true)) return;
        final boolean comparable = isGyroClockComparable();
        float[] all = new float[Math.min(exact.size(), 2 * requested)];
        int pool = 0; // the newest frames with gyro samples (the gyro ring holds ~2 s)
        for (; pool < all.length; pool++) {
            RingFrame image = exact.get(pool);
            CaptureResult r = metadata.get(image.getTimestamp());
            Long exposure = r != null ? r.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
            all[pool] = exposure != null ? ScameraApp.getGyro().zslShake(image.getTimestamp(), exposure, comparable) : Float.NaN;
            if (!(all[pool] >= 0f)) break;
        }
        String source = "gyro";
        if (pool <= requested) {
            // P98: the phone's gyro has no samples for these frames (clock not comparable, ring older than ~2 s): the shake
            // value the HAL put into each frame's result (OPPO gyro.data, vivo gyroDetect), when every candidate has one
            final int n = all.length;
            int hal = 0;
            for (; hal < n; hal++) {
                all[hal] = HalMotion.shake(metadata.get(exact.get(hal).getTimestamp()));
                if (!(all[hal] >= 0f)) break;
            }
            if (hal <= requested) { Log.i("SCAM_HDR", "hybrid ZSL steadiest: gyro samples for " + pool + " ring frames only, newest kept"); return; }
            pool = hal;
            source = "HAL shake";
        }
        int[] order = steadiestOrder(java.util.Arrays.copyOf(all, pool));
        if (order == null) { Log.i("SCAM_HDR", "hybrid ZSL steadiest: no gyro samples, newest frames kept"); return; }
        List<RingFrame> head = new ArrayList<>(exact.subList(0, pool));
        for (int i = 0; i < pool; i++) exact.set(i, head.get(order[i]));
        StringBuilder l = new StringBuilder("hybrid ZSL steadiest (no OIS, ").append(source).append("): ").append(requested).append(" of the newest ").append(pool)
                .append(", ring positions");
        for (int i = 0; i < requested; i++) l.append(' ').append(order[i]);
        Log.i("SCAM_HDR", l.toString());
    }

    /** Order of the pool frames (0 = newest, always first): then by gyro shakiness, lowest first; null when any is unknown. */
    static int[] steadiestOrder(float[] shake) {
        for (float s : shake) if (!(s >= 0f)) return null;
        Integer[] rest = new Integer[shake.length - 1];
        for (int i = 1; i < shake.length; i++) rest[i - 1] = i;
        java.util.Arrays.sort(rest, (a, b) -> shake[a] != shake[b] ? Float.compare(shake[a], shake[b]) : Integer.compare(a, b));
        int[] order = new int[shake.length];
        for (int i = 1; i < shake.length; i++) order[i] = rest[i - 1];
        return order;
    }

    /** P77: the longest gap between two ring frames of one shot (and 4 exposures of the base for long frames); the frames before it
     *  are another moment of the scene. */
    static final long ZSL_RING_MAX_GAP_NS = 500_000_000L;
    static long ringMaxGapNs(CaptureResult base) {
        Long exposure = base != null ? base.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
        return Math.max(ZSL_RING_MAX_GAP_NS, exposure != null ? 4 * exposure : 0L);
    }

    /** P111: {@code frame}'s shutter is more than 12 % longer than {@code base}'s (unknown: not longer). */
    static boolean longerShutter(CaptureResult base, CaptureResult frame) {
        Long b = base == null ? null : base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long f = frame == null ? null : frame.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        return b != null && f != null && b > 0 && f > b * 1.12;
    }

    /** P27: the Hybrid's ring selection (see drainZslNormalFrames); closes the frames it does not keep. */
    private void selectHybridRing(List<RingFrame> rawImages, java.util.Map<Long,TotalCaptureResult> metadata, int requestedCount) {
        if (com.scamera.app.settings.MotionMode.captureV2()) {
            rawImages.sort(java.util.Comparator.comparingLong(RingFrame::getTimestamp).reversed());
            final int skip = Math.min(2, rawImages.size() - 1);
            for (int i = 0; i < skip; i++) rawImages.remove(0).close();
            final long anchor = rawImages.get(0).getTimestamp();
            final MotionMeter.Sample motion = MotionMeter.sample();
            final boolean fresh = motion != null && System.nanoTime() - motion.atNs >= 0
                    && System.nanoTime() - motion.atNs <= 250_000_000L;
            final long window = MotionPolicy.windowNs(fresh ? motion.pxPerS() : Double.NaN);
            for (java.util.Iterator<RingFrame> it = rawImages.iterator(); it.hasNext();) {
                RingFrame f = it.next();
                if (anchor - f.getTimestamp() > window) { f.close(); it.remove(); }
            }
            Log.i("SCAM_HDR", "motion ring: anchor " + anchor + ", " + skip + " frames before press, window "
                    + window / 1_000_000 + " ms, meter " + (fresh ? "fresh" : "unavailable"));
            // P488: a 60 fps ring with an empty queue merges up to 12 of the frames inside the window (the mode's 8 left most unused)
            if (PreferenceKeys.scamDevSwitch("motion_fast_ring", true)) {
                final long[] gaps = new long[Math.max(0, rawImages.size() - 1)];
                for (int i = 0; i < gaps.length; i++) gaps[i] = rawImages.get(i).getTimestamp() - rawImages.get(i + 1).getTimestamp();
                java.util.Arrays.sort(gaps);
                final long gap = gaps.length > 0 ? gaps[gaps.length / 2] : 0;
                final int fast = com.scamera.app.settings.MotionMode.ringFrames(requestedCount, sScamPending.get(), rawImages.size(), gap);
                if (fast != requestedCount) {
                    Log.i("SCAM_HDR", "motion ring: fast ring (" + gap / 1000 + " us a frame), queue empty: " + fast + " frames instead of " + requestedCount);
                    requestedCount = fast;
                }
            }
        }
        RingFrame base = null;
        for (RingFrame image : rawImages) if (base == null || image.getTimestamp() > base.getTimestamp()) base = image;
        // P100: with CROPPED_RAW every frame says which part of the sensor it holds; frames of another crop (the in-sensor zoom
        // switched while they were taken) are not merged with the base, and the photo is not cropped again by what the RAW holds
        final android.graphics.Rect baseCrop = mCroppedRawSession ? CroppedRaw.of(metadata.get(base.getTimestamp()), physicalID, logicalID) : null;
        if (baseCrop != null) {
            int other = 0;
            for (java.util.Iterator<RingFrame> it = rawImages.iterator(); it.hasNext(); ) {
                RingFrame image = it.next();
                if (image != base && !CroppedRaw.same(baseCrop, CroppedRaw.of(metadata.get(image.getTimestamp()), physicalID, logicalID))) {
                    image.close();
                    it.remove();
                    other++;
                }
            }
            final android.graphics.Rect active = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final float isz = CroppedRaw.factor(baseCrop, active);
            com.scamera.app.control.ZoomController.rawCropOfShot(isz);
            Log.i("SCAM_HDR", "hybrid ZSL raw crop " + baseCrop + ": x" + isz + (isz > 1f ? " in-sensor zoom" : "")
                    + (other > 0 ? ", " + other + " ring frames of another crop dropped" : ""));
        }
        final double ref = product(metadata.get(base.getTimestamp()));
        List<RingFrame> exact = new ArrayList<>(), darker = new ArrayList<>(), rest = new ArrayList<>();
        for (RingFrame image : rawImages) {
            double ev = Math.log(product(metadata.get(image.getTimestamp())) / ref) / Math.log(2);
            if (Math.abs(ev) <= 0.05) exact.add(image);
            else if (ev >= -0.5 && ev < 0) darker.add(image);
            else rest.add(image);
        }
        // P111 «Движение»: a ring frame with a longer shutter than the moment's frame is smeared even at the same brightness
        // (the frames of the camera AE before the shutter ceiling took over); as the stock snapshot mode takes fresh frames when
        // its motion level changed, they stay out of the merge
        if (com.scamera.app.settings.MotionMode.on()) {
            final CaptureResult baseResult = metadata.get(base.getTimestamp());
            int longer = 0;
            for (List<RingFrame> tier : java.util.Arrays.asList(exact, darker)) {
                for (java.util.Iterator<RingFrame> it = tier.iterator(); it.hasNext(); ) {
                    RingFrame image = it.next();
                    if (image != base && longerShutter(baseResult, metadata.get(image.getTimestamp()))) {
                        rest.add(image);
                        it.remove();
                        longer++;
                    }
                }
            }
            if (longer > 0) Log.i("SCAM_HDR", "motion: " + longer + " ring frames with a longer shutter than the moment's frame left out");
        }
        exact.sort(java.util.Comparator.comparingLong(RingFrame::getTimestamp).reversed());
        // P77: frames of the same exposure behind a gap of the ring (the AE went away and came back) show another moment of the
        // scene: everything that moved since then ghosts (Redmi Note 11 Pro: 4 of 12 frames 3.6 s older than the other 8).
        int stale = 0;
        final long maxGap = ringMaxGapNs(metadata.get(base.getTimestamp()));
        for (int i = 1; i < exact.size(); i++) {
            if (exact.get(i - 1).getTimestamp() - exact.get(i).getTimestamp() > maxGap) {
                List<RingFrame> old = exact.subList(i, exact.size());
                stale = old.size();
                rest.addAll(old);
                old.clear();
                break;
            }
        }
        if (stale > 0) {
            final long oldest = exact.get(exact.size() - 1).getTimestamp() - maxGap;
            for (java.util.Iterator<RingFrame> it = darker.iterator(); it.hasNext(); ) {
                RingFrame image = it.next();
                if (image.getTimestamp() < oldest) { rest.add(image); it.remove(); }
            }
            Log.w("SCAM_HDR", "hybrid ZSL: " + stale + " frames at the newest exposure dropped: older than a gap of more than "
                    + maxGap / 1_000_000 + " ms in the ring");
        }
        if (!com.scamera.app.settings.MotionMode.captureV2()) steadiestFirst(exact, metadata, requestedCount);
        List<RingFrame> keep = new ArrayList<>(exact.subList(0, Math.min(exact.size(), Math.max(1, requestedCount))));
        for (RingFrame image : exact.subList(keep.size(), exact.size())) rest.add(image);
        final int fill = Math.min(Math.max(1, requestedCount), 8);
        int widened = 0;
        if (keep.size() < fill) {
            darker.sort(java.util.Comparator.comparingDouble(image -> -product(metadata.get(image.getTimestamp()))));
            for (RingFrame image : darker) {
                if (keep.size() < fill) { keep.add(image); widened++; } else rest.add(image);
            }
        } else rest.addAll(darker);
        // P27 (VERIFY-11): the ring statistics of every shot (tier 1 = at the newest exposure, tier 2 = darker down to -0.5 EV).
        Log.i("SCAM_HDR", "hybrid ZSL ring: tier1=" + (keep.size() - widened) + " tier2=" + widened + " requested=" + requestedCount
                + " exact=" + exact.size() + " darker=" + darker.size());
        if (widened > 0) Log.w("SCAM_HDR", "hybrid ZSL: " + exact.size() + " ring frames at the newest exposure, widened by " + widened
                + " frames down to -0.5 EV (AE ramp)");
        // W1.0: one summary line for the ring frames not used (one line each pushed the shot over OPPO's log quota).
        if (!rest.isEmpty()) {
            double evMin = Double.POSITIVE_INFINITY, evMax = Double.NEGATIVE_INFINITY;
            long oldest = Long.MAX_VALUE, newestUnused = Long.MIN_VALUE;
            for (RingFrame image : rest) {
                final double ev = Math.log(product(metadata.get(image.getTimestamp())) / ref) / Math.log(2);
                if (ev < evMin) evMin = ev;
                if (ev > evMax) evMax = ev;
                oldest = Math.min(oldest, image.getTimestamp());
                newestUnused = Math.max(newestUnused, image.getTimestamp());
            }
            Log.w("SCAM_HDR", "hybrid ZSL: " + rest.size() + " ring RAWs not used (exposure "
                    + String.format(java.util.Locale.ROOT, "%+.2f..%+.2f EV", evMin, evMax) + " from the newest frame, timestamps "
                    + oldest + ".." + newestUnused + ")");
        }
        for (RingFrame image : rest) image.close();
        rawImages.clear();
        rawImages.addAll(keep);
    }

    private static double product(CaptureResult result) {
        Long ns = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
        return ns == null || iso == null ? 0 : (double) ns * iso;
    }

    /**
     * P234 (speed-ux A4): the stills after the press a ZSL shot may take behind the instant-shot UI - the 12 MP plan's (about
     * 400 ms); above it the press shows the capture ring with the frame count and a haptic tick at its end.
     */
    static final int INSTANT_ZSL_STILLS = 6;
    public static final String ZSL_SERIES_STARTED = "ScamZslSeriesStarted";

    /** P234: the UI tag of a ZSL press with {@code stillsAfterPress} stills still to come. */
    static String zslStartTag(int stillsAfterPress) {
        return stillsAfterPress <= INSTANT_ZSL_STILLS ? "ScamZslCaptureStarted" : ZSL_SERIES_STARTED;
    }

    private void captureVivoSdkFullRes() {
        try {
            captureVivoSdkFullResChecked();
        } catch (RuntimeException failure) {
            if (mInFlightRescue != null) rescueInFlightShot("SDK setup: " + failure.getMessage());
            else {
                if (mShotArena != null) { mShotArena.release(); mShotArena = null; }
                failPendingShutter(failure);
            }
        }
    }

    /** P198: the ring part of a native 50 MP ZSL shot: its N frames (oldest first), their base, the plan after the shutter. */
    private static final class NativeZslShot {
        final List<ImageFrame> frames;
        final CaptureResult base;
        HybridPlan plan; // P232: the N after the shutter join it once the memory budget is known (withTopUp)
        final boolean verified;

        NativeZslShot(List<ImageFrame> frames, CaptureResult base, HybridPlan plan, boolean verified) {
            this.frames = frames;
            this.base = base;
            this.plan = plan;
            this.verified = verified;
        }
    }

    /**
     * P198: the N frames of a native 50 MP shot from the ZSL ring, selected as the 12 MP Hybrid's (drainZslNormalFrames: before the
     * press, matched results, the newest exposure tier, no stale gap) and checked as the series' stills are (VivoSdkFullResCapture):
     * a frame whose pixels are a confident mosaic of another colour block leaves the merge (the sensor mode was checked as it entered
     * the ring). The post-shutter plan is the 12 MP Hybrid's (HybridPlan.build) from the newest frame's exposure: the OPPO's frames
     * carry its own AE's (NativeRawExposure, AE off), the vivo's the HAL's. Null when no frame is left: the shot is the PSL series.
     */
    @Nullable
    private NativeZslShot takeNativeZsl(VivoSdkFullRes.Profile profile, int requested) {
        if (profile == null) return null;
        final float clip = zslClipFraction();
        mLastZslClipFraction = clip;
        mShutterIszAspect4x3 = true; // the native frames are never cropped to 16:9 (the series' saver: iszAspect4x3)
        final List<ImageFrame> frames = drainZslNormalFrames(requested, null, true, true);
        boolean verified = false;
        int wrongMosaic = 0, notCopied = 0;
        for (java.util.Iterator<ImageFrame> it = frames.iterator(); it.hasNext(); ) {
            final ImageFrame f = it.next();
            try {
                f.materialize(); // a view of its ring slot (plain RAW16), else a copy
            } catch (RuntimeException e) {
                f.close();
                it.remove();
                notCopied++;
                continue;
            }
            final boolean[] mosaic = VivoSdkFullResCapture.mosaicCheck(profile, f.buffer, f.width, f.height, f.width * 2);
            if (mosaic[1]) {
                f.close();
                it.remove();
                wrongMosaic++;
                continue;
            }
            verified |= mosaic[0];
        }
        if (wrongMosaic + notCopied > 0) Log.w("SCAM_CAPTURE", "50 MP ZSL: " + wrongMosaic + " ring frames of another colour block than "
                + profile.nativeBlock + ", " + notCopied + " not copied, left out of the merge");
        if (frames.isEmpty()) {
            Log.w("SCAM_CAPTURE", "50 MP ZSL: no usable ring frame, the series is taken after the shutter (PSL)");
            return null;
        }
        final CaptureResult base = frames.get(frames.size() - 1).getMatchedCaptureMetadata();
        final Long baseNs = base == null ? null : base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer baseIso = base == null ? null : base.get(CaptureResult.SENSOR_SENSITIVITY);
        final NativeRawExposure ae = mNativeAe;
        final long nNs = baseNs != null && baseNs > 0 ? baseNs : ae != null ? ae.exposureNs() : (long) mPreviewExposureTime;
        final int nIso = baseIso != null && baseIso > 0 ? baseIso : ae != null ? ae.iso() : mPreviewIso;
        logUnsettledPress(ae, "50 MP ZSL", nNs, nIso);
        HybridPlan plan;
        HybridPlan.setMotionCap(HalMotion.motionCapNs(base)); HybridPlan.setDeepShadow(mLastZslDeepShadow); HybridPlan.setShadow(mLastZslShadow); // P409 / P410
        try {
            plan = HybridPlan.build(nNs, nIso, clip, mCameraCharacteristics, mExposureLimits);
        } catch (RuntimeException noPlan) {
            Log.w("SCAM_CAPTURE", "50 MP ZSL: hybrid plan unavailable (" + noPlan.getMessage() + "): one more N frame after the shutter");
            plan = HybridPlan.single(nNs, nIso);
        } finally {
            HybridPlan.setMotionCap(0); HybridPlan.setDeepShadow(0f); HybridPlan.setShadow(Float.NaN);
        }
        // P219: every ultrashort frame of the plan, one long frame (HybridPlan.fullResLongFrames)
        plan = plan.withLongFrames(HybridPlan.fullResLongFrames(true, Math.max(1, profile.nativeBlock), PreferenceKeys.isHybridMosaicRemosaic()))
                .withNote(ettrNote()); // P377
        // P198: a ring smaller than the frames asked (96 MB a frame: 4-10 of them) takes the rest of the N right after the shutter -
        // the merge keeps the frame count of the series before P198 (fewer frames left more lattice and noise), the first frames
        // still come from before the press. P232 (speed-ux A2): that top-up is sized by the caller after the memory budget
        // (VivoSdkFullResCapture.zslSplit), so the budget shrinks the frames after the press, not the ring's zero-lag ones
        Log.i("SCAM_CAPTURE", "50 MP ZSL: " + frames.size() + " ring frames (asked " + requested + ", mosaic "
                + (verified ? "verified" : "not proven by the ring") + "); " + plan.description);
        return new NativeZslShot(frames, base, plan, verified);
    }

    /**
     * P212b (M8 step 5, the log-only variant): a press while the own AE is still moving. The shot keeps today's frames - the ring's
     * newest exposure tier (selectHybridRing) and the plan from the newest frame, so its N stay one exposure - instead of dropping
     * the ring frames off the AE's target and topping up after the shutter: that trades frames from before the press for later ones
     * (shutter lag, motion between them, fewer N when the memory guard trims the top-up) on every press of a hunting AE, and the
     * seeds (NativeAeSeed) and the EMA / hysteresis already make such presses rare. The line says how often and how far it happens.
     */
    private static void logUnsettledPress(@Nullable NativeRawExposure ae, String route, long nNs, int nIso) {
        if (ae == null || ae.settled()) return;
        Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "%s: press while AE unsettled: N %.3f ms ISO %d, the AE asks %s "
                        + "(%+.2f EV)%s", route, nNs / 1e6, nIso, ae, Math.log(ae.total() / Math.max(1.0, (double) nNs * nIso / 100.0)) / Math.log(2),
                ae.pending() ? ", its last change not shown yet" : ""));
    }

    /**
     * P202: the plan of a native 50 MP series after the shutter (PSL): N, then the 12 MP Hybrid plan's frames (ultrashort for
     * Bento, bracketed for Shasta) from the N exposure - the viewfinder's (the HAL's or the OPPO's own AE), the hand-set one
     * (P201), or a hand-set long shutter's plan (HybridPlan.manualLong); {@code requested} N as a ZSL shot's, all of them within
     * what {@code budget} (frames asked -> frames the memory holds) gives; P219: one long frame unless the route's colour
     * {@code block} is binned (HybridPlan.fullResLongFrames). Null when no plan can be made (all N, as before).
     */
    @Nullable
    private HybridPlan pslPlan(int requested, java.util.function.IntUnaryOperator budget, long manualPslNs, int block) {
        final CaptureResult latest = mPreviewCaptureResult;
        final Long previewNs = latest == null ? null : latest.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer previewIso = latest == null ? null : latest.get(CaptureResult.SENSOR_SENSITIVITY);
        final int manualIso = paramController.manualIsoValue();
        final long manualNs = paramController.manualPreviewShutterNs();
        final float clip = zslClipFraction();
        try {
            HybridPlan plan;
            if (manualPslNs > 0) {
                plan = HybridPlan.manualLong(manualPslNs, paramController.pslIso(manualPslNs), clip, mCameraCharacteristics, mExposureLimits);
            } else {
                final long nNs = manualNs > 0 ? manualNs : previewNs == null ? 0 : previewNs;
                final int nIso = manualIso > 0 ? manualIso : previewIso == null ? 0 : previewIso;
                if (nNs <= 0 || nIso <= 0) return null;
                logUnsettledPress(mNativeAe, "50 MP PSL", nNs, nIso);
                HybridPlan.setMotionCap(HalMotion.motionCapNs(latest)); HybridPlan.setDeepShadow(mLastZslDeepShadow); HybridPlan.setShadow(mLastZslShadow); // P409 / P410
                try {
                    plan = HybridPlan.build(nNs, nIso, clip, mCameraCharacteristics, mExposureLimits);
                } finally {
                    HybridPlan.setMotionCap(0); HybridPlan.setDeepShadow(0f); HybridPlan.setShadow(Float.NaN);
                }
            }
            // P219: every ultrashort frame of the plan, one long frame (HybridPlan.fullResLongFrames); the N take the slots freed
            plan = plan.withLongFrames(HybridPlan.fullResLongFrames(true, block, PreferenceKeys.isHybridMosaicRemosaic())).withNote(ettrNote()); // P377
            final int extras = plan.requests.size();
            final int frames = budget.applyAsInt(Math.max(1, requested) + extras);
            plan = plan.withLeadingNormals(Math.max(1, frames - extras));
            Log.i("SCAM_CAPTURE", "50 MP PSL: " + plan.requests.size() + " frames after the shutter (" + requested + " N asked, "
                    + frames + " by the memory); " + plan.description);
            return plan;
        } catch (RuntimeException noPlan) {
            Log.w("SCAM_CAPTURE", "50 MP PSL: no hybrid plan (" + noPlan.getMessage() + "): N frames only");
            return null;
        }
    }

    private void captureVivoSdkFullResChecked() {
        // the FULLRES shot's own digital-zoom crop (it never marked one: the last 12 MP shot's was used, or none)
        com.scamera.app.control.ZoomController.markShot();
        final VivoSdkRawSession sdk = mVivoSdkRawSession;
        final CameraCaptureSession session = mCaptureSession;
        // P238 (speed-ux D2): a FULLRES press on a hot phone is told why it will take longer (at most every 5 min)
        if (mImageReaderRaw != null) {
            final String hot = com.scamera.app.util.ThermalPolicy.pressNotice(android.os.SystemClock.elapsedRealtime(),
                    com.scamera.app.settings.FullResProfiles.megapixels(mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight()));
            if (hot != null) showToast(hot);
        }
        if (sdk == null || !isCurrentPreviewSession(session) || mCameraDevice == null
                || !nativeVivoFullRes() && mVivoSdkJpeg == null) {
            failPendingShutter(new IllegalStateException(Lang.t("Сессия SDK RAW закрыта", "SDK RAW session closed")));
            return;
        }
        // P609: a «50 Мп» binned from the 200 MP Tetra row: the series is a 50 MP one of its binned frames (8160x6144, 1/4 of the
        // bytes) taken after the shutter in the 200 MP session; the HAL's 200 MP buffers go back for the merge (camera closed)
        final VivoSdkFullRes.Profile shotProfile = VivoSdkFullRes.profile(mVivoSdkCameraId);
        final boolean tetraBin = shotProfile != null && shotProfile.tetraBin && nativeVivoFullRes();
        // P684 (X300 Ultra 200 MP, «SDK STILL buffer lost» at 3.2 GB free): the previous session's kept ZSL ring (~1.2 GB) goes before
        // the memory is read and the HAL asked for its full-size buffers, not up to 3 s into the 200 MP session (PARKED_RING_MS)
        if (stillOnlyFullRes() && mBackgroundHandler != null) {
            mBackgroundHandler.removeCallbacks(mDropParkedRing);
            dropParkedRing(true, "of the previous session at a 200 MP press (P684)");
        }
        final long readerBytes = (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight() * 2;
        final android.util.Size shotSize = tetraBin ? shotProfile.shotSize() : new android.util.Size(mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight());
        final long frameBytes = (long) shotSize.getWidth() * shotSize.getHeight() * 2;
        android.app.ActivityManager manager = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        final int rawWidth = shotSize.getWidth(), rawHeight = shotSize.getHeight();
        final long halFree = tetraBin ? 4L * readerBytes : 0; // P609 (VivoSdkFullRes.seriesFit)
        final boolean hugeSeries = (long) rawWidth * rawHeight > HUGE_SHOT_PIXELS && nativeVivoFullRes();
        // P179 (owner, 2026-10-16: «в склейке участвовало хотя бы 3 кадра и один из них был ультракороткий», «Сделай 4 кадра»):
        // a 200 MP series is its own count of frames whatever the merge's frame setting - the normal ones, then one ultrashort - as
        // far as the RAW memory guard allows (at least three). P190 (owner, 2026-10-17: «дай возможность ставить столько кадров для
        // 50/200мп сколько я хочу»): «Кадров в снимке 200 Мп» (default HUGE_SERIES) and «Кадров в снимке 50 Мп» (0 = as the merge's
        // frame setting, the path before P190); an explicit count is a series the merge keeps whole, sized here by the memory.
        final int fifty = hugeSeries ? 0 : PreferenceKeys.getFullRes50Frames();
        final boolean seriesMode = hugeSeries || fifty > 0;
        final int requested = hugeSeries ? PreferenceKeys.getFullRes200Frames() : fifty > 0 ? fifty : PreferenceKeys.getHybridZslFrames();
        final long availableBytes = manager == null ? -1 : memory.availMem;
        final int grid = !hugeSeries && !"sensor".equals(PreferenceKeys.hybridOutputMode()) ? 2 : 1;
        // P194: the native route's own colour block (Quad 2, the X100 / X300 Ultra's hardware-remosaic Bayer 1); the SDK route 1
        // P586: a 200 MP row its frames proved a Quad (the OnePlus 16) merges as one; the vivo 200 MP profiles are block 4 as before
        final int block = nativeVivoFullRes() ? Math.max(1, VivoSdkFullRes.profile(mVivoSdkCameraId).mergeBlock()) : hugeSeries ? 4 : 1; // P609
        final int guarded = VivoSdkFullRes.frameCount(requested, frameBytes, availableBytes);
        final int frames = seriesMode ? VivoSdkFullRes.seriesCount(requested, (long) rawWidth * rawHeight, rawWidth, frameBytes, block, grid,
                hugeSeries, availableBytes, hugeSeries ? 4L * frameBytes : halFree) : guarded;
        if (hugeSeries) {
            if (frames < 3) {
                failPendingShutter(new IllegalStateException(Lang.t("Недостаточно памяти для серии 200 Мп", "Not enough memory for a 200 MP series")));
                return;
            }
        }
        // P198: a native 50 MP shot takes its N from the ZSL ring (the frames before the press) and only the 12 MP Hybrid plan's
        // frames after the shutter (3 ultrashort for Bento, the bracketed ones for Shasta); none usable in the ring (just opened,
        // every frame of another mode or mosaic): the serial PSL series as before, whose stills keep the failure bookkeeping
        // P201: a hand-set shutter longer than the viewfinder's (PSL_SHUTTER_NS) is never in the ring: the series after the shutter
        final long manualPslNs = paramController.pslShutterNs();
        final NativeZslShot zsl = hugeSeries || tetraBin || !nativeZslSession() || manualPslNs > 0 ? null
                : takeNativeZsl(VivoSdkFullRes.profile(mVivoSdkCameraId), requested);
        final int count, shotN;
        boolean zslTrimmed = false;
        int zslTopUp = 0;
        HybridPlan pslPlanBuilt = null;
        if (zsl != null) {
            final int extras = zsl.plan.requests.size(); // the plan's ultrashort / bracketed stills (no N yet)
            final int ring = zsl.frames.size();
            // the ring frames are in memory already (the ring's memfd): the budget of the series counts their bytes as its own
            final long withRing = availableBytes < 0 ? -1 : availableBytes + (long) ring * frameBytes;
            // P232 (speed-ux A2): the budget first, then the split - the ring frames stay (the zero-lag ones, P198's point), the N
            // taken after the press shrink; before P232 the top-up was fixed first and the budget removed the oldest ring frames
            // (owner's X7U 20:20:35: «5 ring frames + 7 N after the shutter» -> «N 1 from the ring + 13 after the shutter»)
            final int[] split = VivoSdkFullResCapture.zslSplit(ring, extras, requested, all -> seriesMode
                    ? VivoSdkFullRes.seriesCount(all, (long) rawWidth * rawHeight, rawWidth, frameBytes, block, grid, false, withRing)
                    : VivoSdkFullRes.frameCount(all, frameBytes, withRing));
            final int keep = split[0];
            zslTopUp = split[1];
            zslTrimmed = split[2] < Math.max(ring, requested) + extras; // the memory, not the ring, held the N back
            while (zsl.frames.size() > keep) zsl.frames.remove(0).close(); // the oldest go, the moment of the press stays
            zsl.plan = zsl.plan.withLeadingNormals(zslTopUp);
            Log.i("SCAM_CAPTURE", "50 MP ZSL: " + keep + " of " + ring + " ring frames + " + zslTopUp + " N after the shutter (asked "
                    + requested + ", budget " + split[2] + " frames); " + zsl.plan.description);
            count = zsl.plan.requests.size();
            shotN = zsl.frames.size();
        } else {
            // P202 (owner, 2026-10-17: «В PSL у нас тоже должны быть брекет кадры»): a native 50 MP series after the shutter is N
            // and the 12 MP Hybrid plan's bracketed / ultrashort frames, as a ZSL shot's frames after the shutter
            pslPlanBuilt = hugeSeries || !nativeVivoFullRes() ? null : pslPlan(requested, all -> seriesMode
                    ? VivoSdkFullRes.seriesCount(all, (long) rawWidth * rawHeight, rawWidth, frameBytes, block, grid, false, availableBytes, halFree)
                    : VivoSdkFullRes.frameCount(all, frameBytes, availableBytes), manualPslNs, block);
            count = pslPlanBuilt != null ? pslPlanBuilt.requests.size() : frames;
            shotN = 0;
        }
        final HybridPlan pslPlan = pslPlanBuilt;
        final int total = shotN + count;
        if (count < 1 || (manager != null && memory.lowMemory)) {
            if (zsl != null) for (ImageFrame f : zsl.frames) f.close();
            failPendingShutter(new IllegalStateException(Lang.t("Недостаточно памяти для серии FULLRES", "Not enough memory for a FULLRES series")));
            return;
        }
        cameraEventsListener.burstSize(total);
        // P190: a series shorter than asked is said aloud (the RAW guard, the merge's memory, or the 2x output at 50 MP); P198 a ZSL
        // shot's N below the ask only when the memory cut it (the ring's own cap is in the log: ZslRing «50 MP ZSL ring in memfd»)
        // P233 (speed-ux A5): a ZSL shot's N are the ring's and those after the shutter (before P233 the ring's alone: «Снято 1 из
        // 12 кадров» while the worker merged 8 N); the worker's own count follows after the merge (ScamHybridBurst.notice)
        final int taken = zsl != null ? VivoSdkFullResCapture.zslTaken(shotN, zslTopUp) : count;
        final String cut = zsl != null ? (taken >= requested ? "" : zslTrimmed ? "memory" : "ring")
                : count >= requested ? "" : count < guarded && grid == 2 ? "2x" : "memory";
        if (tetraBin) Log.i("SCAM_CAPTURE", "50 MP from the 200 MP mode " + shotProfile.sensorMode + " " + shotProfile.size
                + ", 2x2 binned per Tetra cell -> " + shotSize + " Quad (block 2, P609): " + count + " stills after the shutter, "
                + (frameBytes >> 20) + " MB a frame (" + (readerBytes >> 20) + " MB unbinned), the HAL's " + (halFree >> 20)
                + " MB back for the merge");
        Log.i("SCAM_CAPTURE", (nativeVivoFullRes() ? "NATIVE FULLRES" : "SDK FULLRES50") + " frames=" + total
                + (zsl != null ? " (ZSL: N " + shotN + " from the ring + " + count + " after the shutter, N " + zslTopUp + " of them)" : "")
                + " requested=" + requested
                + " availableMB=" + (memory.availMem >> 20) + (seriesMode ? " series" : "") + (cut.isEmpty() ? "" : " cut=" + cut));
        if (!cut.isEmpty() && !"ring".equals(cut))
            showToast(Lang.t("Снято " + taken + " из " + requested + " кадров: свободно " + (memory.availMem >> 20) + " МБ"
                        + ("2x".equals(cut) ? ", вывод 2×" : ""),
                "Took " + taken + " of " + requested + " frames: " + (memory.availMem >> 20) + " MB free" + ("2x".equals(cut) ? ", 2× output" : "")));
        final ImageSaver saver = new ImageSaver(cameraEventsListener);
        saver.iszAspect4x3 = mShutterIszAspect4x3 || nativeVivoFullRes();
        saver.tetraBin = tetraBin; // P609
        saver.manualWbAtPress = mShutterManualWb;
        saver.mosaicWbAtPress = mShutterMosaicWb;
        saver.dcgShot = dcgShotNow();
        saver.zoomResidual = com.scamera.app.control.ZoomController.shotResidual(); // marked at the press (above)
        saver.cameraIdAtPress = ScameraApp.getSettings().cameraId;
        saver.planNote = pslPlan != null ? pslPlan.description : zsl != null ? zsl.plan.description : null;
        saver.lensFilterAtPress = mShutterLensFilter;
        saver.lookAtPress = mShutterLook;
        saver.motionAtPress = mShutterMotion;
        saver.setFrameCount(count);
        saver.fullResSeries = seriesMode ? total : 0; // P190: the merge keeps a series whole
        saver.fullResRequested = requested;
        saver.setImageFormat(ImageFormat.RAW_SENSOR);
        mImageSaver = saver;
        clearZslPreviewFrames();
        mPendingZslNormalFrames = new ArrayList<>();
        IsoExpoSelector.shotPlan.clear();
        burstShake = new ArrayList<>();
        final long[] times = new long[count];
        Arrays.fill(times, Math.max(1L, (long) mPreviewExposureTime));
        if (zsl != null) {
            // P198: the ring's N are the first frames of the shot (the saver's buffer, their exposure pairs and gyro windows), as the
            // 12 MP Hybrid's; the stills after the shutter follow
            SaverImplementation.FRAME_QUEUE.addAll(zsl.frames);
            final long[] zslTimestamps = new long[shotN];
            for (int i = 0; i < shotN; i++) {
                final ImageFrame f = zsl.frames.get(i);
                zslTimestamps[i] = f.timestamp;
                IsoExpoSelector.shotPlan.add(new IsoExpoSelector.ExpoPair(f.measuredExposure, f.measuredExposure, f.measuredExposure,
                        f.measuredIso, f.measuredIso, f.measuredIso, f.measuredIso));
            }
            for (int i = 0; i < count; i++) {
                final HybridPlan.Request r = zsl.plan.requests.get(i);
                if (!r.autoExposure && r.shutterNs > 0) times[i] = r.shutterNs;
            }
            final Long baseExposure = zsl.base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            ScameraApp.getGyro().ringFramesMotion(zslTimestamps, baseExposure != null ? baseExposure : 0L, burstShake,
                    isGyroClockComparable());
        }
        ScameraApp.getGyro().prepareSeries(times, burstShake, true);
        final CameraCharacteristics characteristics = mCameraCharacteristics;
        final int rotation = ScameraApp.getGravity().getCameraRotation(mSensorOrientation);
        // P235 (speed-ux B3): a burst that would not be one memfd (stills beyond the ring's free slots) gets its own arena at the
        // press - the stills straight into it, the ring frames moved in meanwhile (RingArenaMove) - instead of the whole burst moved
        // after the series (ScamHybridBurst.moveIntoArena, raw_write=606 / 903 ms on the critical path)
        final com.scamera.app.util.ShotArena arena;
        RingArenaMove ringMove = null;
        if (ScameraApp.getSettings().binning) arena = null;
        else {
            final com.scamera.app.util.ShotArena reserved = shotArena(count, frameBytes);
            final com.scamera.app.util.ShotArena own = pressArena(reserved, zsl == null ? null : zsl.frames, count,
                    frameBytes, (long) rawWidth * rawHeight);
            if (own != null) {
                arena = own;
                // the ring takes no frame until the series is done and is released for the merge then (P200): it goes now, so
                // each ring frame's pages go back as it is moved, and the slots no frame holds at once
                releaseFullResRing();
                if (zsl != null) ringMove = RingArenaMove.start(zsl.frames, own, frameBytes);
                Log.i("SCAM_CAPTURE", "FULLRES shot arena at the press: " + own + " for " + shotN + " ring frames + " + count
                        + " stills (the ring's free slots held " + (reserved == null ? 0 : reserved.slots()) + ")");
                if (reserved != null) reserved.release();
            } else arena = reserved;
        }
        final RingArenaMove pressMove = ringMove;
        // P179: a 200 MP shot is merged with the camera closed (its own RAW buffers and the HAL's are free for the merge)
        final boolean hugeShot = (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight() > HUGE_SHOT_PIXELS;
        // P200 (OPPO Find X7 Ultra, 2026-10-17): ColorOS force-stopped the app mid-merge for heat («o-stop(22)», thermal level
        // 14) - the 50 MP session kept streaming and binning 24 fps through a 1-4 min merge. A 50 MP shot is merged with the
        // camera in its plain session as well (its shutter waits for the merge anyway); back to FULLRES after it.
        final boolean plainForMerge = hugeShot || PreferenceKeys.scamDevSwitch("fullres_plain_merge", true);
        final int shotMegapixels = com.scamera.app.settings.FullResProfiles.megapixels(
                shotSize.getWidth(), shotSize.getHeight()); // P609: a binned 50 MP shot is merged as 50 MP
        saver.shotArena = arena;
        mShotArena = arena;
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        // P222: the stills the series keeps in flight (FullResInFlight: 6 up to 100 MP, P179's 3 above it or after a failure
        // with more, the SDK route one at a time); a failure of a series with more than 3 is noted for this camera and size
        final String seriesCamera = mVivoSdkCameraId;
        final VivoSdkFullRes.Profile seriesProfile = VivoSdkFullRes.profile(seriesCamera);
        // P684 (owner, 2026-11-14: «200мп не работают на 300U»): a 200 MP series of the X100 / X300 Ultra's still-only route (P208)
        // runs its camera's variant (StillOnly200: the mode key on the stills, one still in flight, P208 as before), remembered per
        // camera, size and firmware; a failed series moves it on for the next press
        final StillOnly200.Choice sessionChoice = mStillChoice;
        final StillOnly200.Choice stillVariant = !StillOnly200.applies(seriesProfile, VivoSdkFullRes.oppo()) ? null
                : sessionChoice != null ? sessionChoice : StillOnly200.choose(seriesCamera, seriesProfile);
        final FullResInFlight.Choice inFlight = seriesProfile == null ? new FullResInFlight.Choice(1, "no profile")
                : FullResInFlight.forSeries(seriesCamera, seriesProfile, count);
        if (stillVariant != null) Log.i("SCAM_CAPTURE", "200 MP still-only camera " + seriesCamera + " " + seriesProfile.size
                + ": variant " + stillVariant + " (P684)");
        Log.i("SCAM_CAPTURE", "FULLRES in flight: " + inFlight + " for " + count + " stills after the shutter, camera "
                + seriesCamera + (seriesProfile == null ? "" : " " + seriesProfile.size));
        final java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
        // the camera closed or failed under the series (rescueInFlightShot): its reason, which decides whether the count is noted
        final java.util.concurrent.atomic.AtomicReference<String> rescueCause = new java.util.concurrent.atomic.AtomicReference<>();
        // P684: the series of a still-only variant, for what its stills proved (their mosaic, the HAL's full-size mode)
        final VivoSdkFullResCapture[] seriesStats = new VivoSdkFullResCapture[1];
        final VivoSdkFullResCapture.Listener listener = new VivoSdkFullResCapture.Listener() {
            public void started() {
                cameraEventsListener.frameStarted(null);
            }

            public void result(int index, TotalCaptureResult result) {
                if (done.get()) return;
                // P192: the OPPO native still gets the own neutral, the plain session's colour transform and shading map
                if (VivoSdkFullRes.oppo()) {
                    final String filled = OppoNativeFullRes.completeFrame(mVivoSdkCameraId, result); // P212e: the photo's prior
                    if (index == 0) Log.i("SCAM_CAPTURE", "OPPO native still " + mVivoSdkCameraId + " at "
                            + result.get(CaptureResult.SENSOR_EXPOSURE_TIME) / 1e6 + " ms ISO " + result.get(CaptureResult.SENSOR_SENSITIVITY)
                            + (filled.isEmpty() ? ", nothing filled" : ", filled:" + filled));
                }
                rememberRawMetadata(result);
                mCaptureResult = result;
                mCaptureRequest = result.getRequest();
                long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                int iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                IsoExpoSelector.ExpoPair pair = new IsoExpoSelector.ExpoPair(exposure, exposure, exposure, iso, iso, iso, iso);
                final Object tag = result.getRequest() == null ? null : result.getRequest().getTag();
                final ImageFrame.CaptureRole role = tag instanceof ImageFrame.ScamCaptureTag ? ((ImageFrame.ScamCaptureTag) tag).role : null;
                pair.isLongFrame = role == ImageFrame.CaptureRole.LONG;
                pair.isHighlightFrame = role == ImageFrame.CaptureRole.SHORT || role == ImageFrame.CaptureRole.EXTRA_SHORT;
                IsoExpoSelector.shotPlan.add(pair);
                ScamCaptureLog.result(result.getRequest(), result, scamZslShutterTimestamp, false);
                ScameraApp.getGyro().seriesFrameStarted();
                cameraEventsListener.frameCaptured(new TimerFrameCountViewModel.FrameCntTime(shotN + index + 1, total, exposure / 1e9));
            }

            public void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures) {
                if (done.get()) return;
                try {
                    for (ImageFrame frame : saver.snapshotFrames())
                        if (frame == null || frame.rawPayloadError != null)
                            throw new IllegalStateException(frame == null ? "SDK RAW frame missing" : frame.rawPayloadError);
                    // P198: a ZSL shot's base is the newest ring frame (the moment of the press), as the 12 MP Hybrid's
                    CaptureResult base = zsl != null ? zsl.base : sequence.firstBoundResult(ImageFrame.CaptureRole.NORMAL);
                    if (base == null) throw new IllegalStateException("SDK RAW base metadata missing");
                    saver.updateFrameCount(zsl != null ? saver.bufferSize() : count);
                    saver.detachForQueue(true);
                    com.scamera.app.processing.ShotTimeline.capture("series_done"); // P236: the last still is in
                    // P235: the ring frames' move into the press arena ends before the arena takes no more frames
                    if (pressMove != null) {
                        pressMove.await();
                        com.scamera.app.processing.ShotTimeline.capture("arena_moved");
                        Log.i("SCAM_CAPTURE", "FULLRES shot arena: " + pressMove);
                    }
                    // Captured views pin the data; release the capture owner before processing.
                    if (arena != null) arena.release();
                    ScameraApp.getGyro().seriesDone();
                    final ArrayList<GyroBurst> shakiness = new ArrayList<>(burstShake);
                    // P609: the binned frames are a stream of their own (8160x6144 Quad, block 2 declared), not the 200 MP row's
                    final String mosaicKey = com.scamera.app.processing.MosaicStream.key() + (tetraBin ? TETRA_BIN_KEY : "");
                    final com.scamera.app.processing.ShotTimeline timeline =
                            com.scamera.app.processing.ShotTimeline.detach();
                    sScamPending.incrementAndGet();
                    if (plainForMerge) closeCameraForProcessing(shotMegapixels);
                    else {
                        sFullResMerges.incrementAndGet();
                        releaseFullResRing();
                    }
                    SCAM_PROCESSING.execute(() -> {
                        com.scamera.app.processing.ShotTimeline.attach(timeline);
                        try {
                            com.scamera.app.processing.MosaicBlockStore.setShotKey(mosaicKey, tetraBin ? 2 : 1);
                            if (plainForMerge) {
                                awaitClosedForProcessing();
                                com.scamera.app.processing.ShotTimeline.mark("plain_ready"); // P236
                            }
                            saver.runRaw(characteristics, base, base.getRequest(), shakiness, rotation, exposures);
                        } catch (Exception failure) {
                            Log.e(TAG, "SDK FULLRES50 processing: " + Log.getStackTraceString(failure));
                            cameraEventsListener.processingFailed(failure.getLocalizedMessage());
                        } finally {
                            if (arena != null) arena.release();
                            sScamPending.decrementAndGet();
                            if (!hugeShot) sFullResMerges.decrementAndGet();
                            com.scamera.app.processing.ShotTimeline.print();
                            if (plainForMerge) reopenCameraAfterProcessing();
                        }
                    });
                    done.set(true);
                    mMeasuredFrameCnt = zsl != null ? saver.bufferSize() : count;
                    mInFlightRescue = null;
                    mVivoSdkCapture = null;
                    mShotArena = null;
                    mNativeRawPslCapture = false;
                    mShotInProgress = false;
                    burst = false;
                    mShotState = SHOT_IDLE;
                    cameraEventsListener.burstCompleted(null);
                    if (zsl != null) {
                        final String dropped = sequence.droppedSummary();
                        Log.i("SCAM_CAPTURE", "NATIVE FULLRES ZSL: " + shotN + " ring RAWs + " + sequence.boundFutureCount() + " of " + count
                                + " after the shutter; Hybrid ZSL" + (dropped.isEmpty() ? "" : " (dropped: " + dropped + ")"));
                    } else Log.i("SCAM_CAPTURE", "SDK FULLRES50: " + count + " matched future RAWs; Hybrid PSL, stock ZSL=false");
                    Log.i("SCAM_CAPTURE", "FULLRES in flight: series done with " + inFlight.count + " in flight");
                    if (stillVariant != null) { // P684: the variant that completed a series is kept; the photo says what it got
                        final String kept = StillOnly200.succeeded(seriesCamera, seriesProfile, stillVariant);
                        if (kept != null) Log.i("SCAM_CAPTURE", kept);
                        Log.i("SCAM_CAPTURE", "200 MP still-only photo camera " + seriesCamera + ": variant " + stillVariant.variant + ", "
                                + mosaicWords(seriesStats[0]) + " (P684)");
                    }
                } catch (RuntimeException failure) {
                    failed(failure.getMessage());
                }
            }

            public void degraded(String reason) {
                if (!done.get()) FullResInFlight.failed(seriesCamera, seriesProfile, inFlight.count, reason);
            }

            public void failed(String reason) {
                if (!done.compareAndSet(false, true)) return;
                // P222: a series sent with more than 3 in flight that failed for a reason the count may cause: 3 from the next one
                if (sent.get()) {
                    final String cause = rescueCause.get();
                    FullResInFlight.failed(seriesCamera, seriesProfile, inFlight.count, cause != null ? cause : reason);
                }
                VivoSdkFullResCapture capture = mVivoSdkCapture;
                mVivoSdkCapture = null;
                if (capture != null) capture.cancel();
                mInFlightRescue = null;
                if (pressMove != null) pressMove.cancel(); // P235: before its frames are discarded
                saver.discardFrames();
                if (arena != null) arena.release();
                if (mShotArena == arena) mShotArena = null;
                ScameraApp.getGyro().seriesDone();
                mNativeRawPslCapture = false;
                mShotInProgress = false;
                burst = false;
                mShotState = SHOT_IDLE;
                cameraEventsListener.burstCompleted(null);
                // P684: a failed 200 MP still-only series moves its camera's size to the next variant for the next press; when
                // every variant has failed, the 200 MP row is noted failed (the size off, as a row that never gave a frame)
                boolean rowNoted = false, nextVariant = false;
                if (stillVariant != null && sent.get()) {
                    rowNoted = true; // the variants' trial owns this route's verdicts; the row only when every variant failed
                    final String cause = rescueCause.get() != null ? rescueCause.get() : reason;
                    final StillOnly200.Outcome outcome = StillOnly200.failed(seriesCamera, seriesProfile, stillVariant, cause);
                    Log.w("SCAM_CAPTURE", outcome.log + "; pixels: " + mosaicWords(seriesStats[0]) + " (P684)");
                    if (outcome.exhausted) {
                        final String note = VivoNativeFullRes.failed(seriesCamera, seriesProfile, cause);
                        if (note != null) showError(note);
                    } else if (outcome.noted) {
                        nextVariant = true;
                        // the camera died under the series (onError rescued it): that error fails no row (retryWithoutVivoSdkFullRes)
                        mStillOnlyNextVariant = cause != null && cause.startsWith("camera error");
                        showToast(StillOnly200.ownerNext(outcome));
                    }
                }
                // P192: an OPPO still that proved another mode or mosaic: the camera's next candidate the next time; P207 the X100 /
                // X300 Ultra's too (the X100 Ultra's log: its main's row 0 refused twice as no Quad, never noted as failed)
                if (!rowNoted && reason != null && (reason.contains("sensor mode is not active")
                        || reason.contains("mosaic does not match") || reason.contains("not been verified"))) {
                    if (VivoSdkFullRes.oppo())
                        showError(OppoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), reason));
                    else if (nativeVivoFullRes()) {
                        final String note = VivoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), reason);
                        if (note != null) showError(note);
                    }
                }
                cameraEventsListener.processingFailed(reason);
                Log.w("SCAM_CAPTURE", "SDK FULLRES50 failed: " + reason);
                // P179: a 200 MP series stopped the preview at the shutter: the camera opens again for it - P315: once (it restarted
                // twice, the 200 MP restart and the FULLRES-off one: vivo X300 Ultra log 2026-10-19). P684: with a next variant
                // to try the camera opens in 200 MP again, so the next press takes it
                if (nextVariant) Log.i("SCAM_CAPTURE", "200 MP still-only: FULLRES stays on, the next press tries the next variant (P684)");
                else PixelPro10.setFullRes(false);
                if (hugeShot) mainHandler().post(CaptureController.this::restartCamera);
                else if (isCameraResumed && mCaptureSession == session)
                    mainHandler().post(() -> {
                        if (isCurrentPreviewSession(session)) restartCamera();
                    });
            }
        };
        mNativeRawPslCapture = true;
        burst = true;
        mShotState = SHOT_FIRED;
        final VivoSdkFullResCapture capture;
        try {
            capture = new VivoSdkFullResCapture(mCameraDevice, session, sdk,
                mImageReaderRaw.getSurface(), mVivoSdkJpeg == null ? null : mVivoSdkJpeg.getSurface(), saver, mBackgroundHandler,
                () -> {
                    CaptureResult latest = mPreviewCaptureResult;
                    Long timestamp = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
                    return timestamp == null ? 0 : timestamp;
                }, rotation, mShutterGeneration, scamZslShutterTimestamp, count,
                VivoSdkFullRes.profile(mVivoSdkCameraId), mPreviewCaptureResult.getRequest(),
                characteristics.getAvailableSessionKeys(), listener,
                image -> mLiveMetadata.image(image.getTimestamp(), image), inFlight.count);
            if (stillVariant != null) { // P684: the variant's mode key and stock full-size keys (decision frame, still keys, checks)
                capture.modeKeyOnStills(stillVariant.variant.modeKey);
                if (stillVariant.variant.recipe) {
                    final CaptureRequest decision = fullSizeDecision(characteristics);
                    final ImageReader view = mImageReaderRawView;
                    final int expected = view == null ? 0 : X300FullSize.expectedSeamless(seriesProfile.size,
                            new android.util.Size(view.getWidth(), view.getHeight()));
                    capture.fullSize(stillVariant.variant, decision, expected);
                    Log.i("SCAM_CAPTURE", "200 MP full-size stills (" + stillVariant.variant + "): decision frame "
                            + (decision == null ? "none" : "built") + ", seamlessMode expected " + expected + " (P684)");
                }
                seriesStats[0] = capture;
            }
            // P198: the stills after the shutter are the Hybrid plan's (the native mode's keys on each, as every still of the series)
            if (zsl != null) {
                capture.plan(zsl.plan);
                capture.zsl(zsl.frames, zsl.verified);
            } else if (pslPlan != null) {
                capture.plan(pslPlan);
            } else {
                // P684: the stock full-size recipe takes its stills at the viewfinder's exposure (AE off), as the stock camera
                if (stillVariant != null && stillVariant.variant.recipe && paramController.manualIsoValue() <= 0
                        && paramController.manualPreviewShutterNs() <= 0 && manualPslNs <= 0) {
                    final CaptureResult vf = mPreviewCaptureResult;
                    final Long vfNs = vf == null ? null : vf.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    final Integer vfIso = vf == null ? null : vf.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (vfNs != null && vfIso != null && vfNs > 0 && vfIso > 0) {
                        capture.manualNormals(vfNs, vfIso);
                        Log.i("SCAM_CAPTURE", "200 MP full-size N at the viewfinder's exposure: " + vfNs + " ns ISO " + vfIso + " (P684)");
                    }
                }
                // P201: the series' N at the hand-set exposure: a long shutter at its ISO (pslIso), else the hand-set ISO / shutter
                // with the viewfinder's other value (the ZSL shot's N are its ring frames, the plan built from them)
                final CaptureResult latest = mPreviewCaptureResult;
                final Long previewNs = latest == null ? null : latest.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                final Integer previewIso = latest == null ? null : latest.get(CaptureResult.SENSOR_SENSITIVITY);
                final int manualIso = paramController.manualIsoValue();
                final long manualNs = paramController.manualPreviewShutterNs();
                long nNs = 0;
                int nIso = 0;
                if (manualPslNs > 0) {
                    nNs = manualPslNs;
                    nIso = paramController.pslIso(manualPslNs);
                } else if (manualIso > 0 || manualNs > 0) {
                    nNs = manualNs > 0 ? manualNs : previewNs == null ? 0 : previewNs;
                    nIso = manualIso > 0 ? manualIso : previewIso == null ? 0 : previewIso;
                }
                if (nNs > 0 && nIso > 0) {
                    capture.manualNormals(nNs, nIso);
                    Log.i("SCAM_CAPTURE", "FULLRES manual exposure: N " + nNs + " ns ISO " + nIso);
                }
            }
        } catch (RuntimeException failure) {
            listener.failed(failure.getMessage()); // the ring frames already in the saver's buffer go with it
            return;
        }
        // P192 (OPPO Find X7 Ultra 50 MP, 2026-10-17: the windows came out pink and without sky, the plain shot of the same scene
        // white with sky): a native 50 MP series ends with the ultrashort frame as well - the plain shots and ISZ have their
        // Bento frame, the native merge takes it (ISZ: «23U», «BENTO: applied»); without it the clipped green turned pink
        if (zsl == null && pslPlan == null && (hugeSeries || nativeVivoFullRes() && count >= 3) && HybridPlan.bentoMode() > 0) {
            // P179: the last still of a 200 MP series is the ultrashort (Bento) one: the preview's exposure over the ultrashort
            // factor at its sensitivity; with Bento off (the worker would leave it out) the series is all N
            CaptureResult latest = mPreviewCaptureResult;
            Long exposure = latest == null ? null : latest.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = latest == null ? null : latest.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exposure != null && iso != null && exposure > 0 && iso > 0) {
                long es = Math.max(ULTRASHORT_MIN_NS, Math.round(exposure / HybridPlan.ultrashortFactor()));
                capture.ultrashortLast(es, iso);
                Log.i("SCAM_CAPTURE", (hugeSeries ? "200 MP: " : "50 MP: ") + (count - 1) + " N + 1 ultrashort " + es + " ns ISO " + iso
                        + " (N " + exposure + " ns; " + count + " of " + requested + " frames asked)");
                // P212h (A7 = M14 phase 1b, scam_dev.txt "vivo_still_us_from_n", default off): P208's still-only viewfinder is the
                // normal mode; the ultrashort follows the first N the 200 MP mode delivers instead (from 4 stills)
                if (stillOnlyFullRes() && !VivoSdkFullRes.oppo() && PreferenceKeys.scamDevSwitch("vivo_still_us_from_n", false)) {
                    final android.util.Range<Integer> isoRange = characteristics == null ? null
                            : characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    capture.ultrashortFromFirstN(HybridPlan.ultrashortFactor(), ULTRASHORT_MIN_NS, isoRange == null ? 1 : isoRange.getLower());
                }
            }
        }
        // P212h (A7 = M14 phase 1a, scam_dev.txt "vivo_series_ae_lock", default off): a vivo 200 MP PSL series holds the exposure the
        // viewfinder AE converged to on its automatic N stills (AE lock); not converged at the press: AE on as before
        if (hugeSeries && zsl == null && !VivoSdkFullRes.oppo() && PreferenceKeys.scamDevSwitch("vivo_series_ae_lock", false)) {
            final CaptureResult latest = mPreviewCaptureResult;
            final Integer aeState = latest == null ? null : latest.get(CaptureResult.CONTROL_AE_STATE);
            final boolean converged = aeState != null && aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED;
            capture.lockNormals(converged);
            Log.i("SCAM_CAPTURE", "200 MP series: " + (converged ? "AE lock on the N stills (the viewfinder AE converged)"
                    : "AE on, no lock (the viewfinder AE state " + aeState + " at the press)"));
        }
        mVivoSdkCapture = capture;
        mInFlightRescue = () -> {
            rescueCause.set(mInFlightRescueReason);
            listener.failed(Lang.t("Серия SDK RAW прервана", "SDK RAW series interrupted"));
        };
        // P198: a ZSL shot's N are taken already: the press is an instant shot for the UI, as the 12 MP Hybrid's; P234: only while
        // the stills after the press fit the 12 MP plan, a longer series shows the capture ring
        cameraEventsListener.stillStarted(zsl != null ? zslStartTag(count) : "CaptureStarted!");
        if (zsl != null) Log.i("SCAM_CAPTURE", "50 MP ZSL UI: " + (count <= INSTANT_ZSL_STILLS ? "instant shot" : "capture ring")
                + " (" + count + " stills after the press)");
        cameraEventsListener.burstPrepared(null);
        ScameraApp.getGyro().startBurstRegistration();
        sent.set(true);
        capture.start();
    }

    private boolean isGyroClockComparable() {
        Integer source = mCameraCharacteristics == null ? null
                : mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        return source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
    }

    private void takeStill() {
        // SCAM may wait briefly for the stock AE plan; never block the UI thread.
        if (PreferenceKeys.isScamEnabled() && mBackgroundHandler != null
                && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mBackgroundHandler.post(this::takeStill);
            return;
        }
        // P590: a flash shot (startFlashShot, after its precapture): one fresh frame with the flash, the ring left alone
        final FlashMode.Shot flash = mFlashShot;
        mFlashShot = null;
        com.scamera.app.control.ZoomController.markShot();
        try {
            if (mClosedForProcessing) { // P179: the plain session of a running FULLRES merge (200 MP; P200 50 MP too) takes no shot
                failPendingShutter(new IllegalStateException(PixelPro10.suspendedText())); // P214h: the size being merged
                return;
            }
            if (null == mCameraDevice) {
                failPendingShutter(new IllegalStateException(Lang.t("Камера закрыта", "Camera closed")));
                return;
            }
            SensorConfigInjector.fillForModule(physicalID, this);
            // Every photo is merged by the SCAM Hybrid or SCAM HDR, both from the ZSL RAW stream (Photo and Night).
            if (!PreferenceKeys.isScamEnabled() || !isZslMode())
                throw new IllegalStateException(Lang.t("Нет маршрута склейки: Hybrid и SCAM HDR снимают из ZSL RAW-потока (режимы Фото и Ночь)", "No merge route: Hybrid and SCAM HDR shoot from the ZSL RAW stream (Photo and Night modes)"));
            final boolean scamCapture = PreferenceKeys.isScamEnabled();
            // P635: the session's full-resolution recipe (null: none): the stills are its template, targets and tags
            final FullResRecipePlan recipePlan = mRecipePlan;
            // The SCAM Hybrid takes the SCAM capture route with its own settings (independent of SCAM HDR; wins over it).
            final boolean scamHybridSelected = scamCapture && PreferenceKeys.isScamHybridEnabled();
            final boolean hybridZslRequested = isZslMode() && needsExposureBracket();
            // P84: the Hybrid with a hand-set shutter longer than 1/8 s takes its frames after the shutter (PSL), not from the ring
            // P315: not in an OPPO-family picked sensor mode (nothing is exposed after the shutter there: the ring at the
            // viewfinder's exposure)
            final boolean manualPsl = flash == null && scamHybridSelected && paramController.pslShutterNs() > 0 && SensorModePick.seriesAllowed();
            // P646 «Склейка YUV»: a YUV session's series after the shutter (our curve, NR minimal, edge off, brackets) takes no ring
            // frame; a YUV session from the ring (the camera's curve) takes no bracket
            final YuvMergeRoute.Session yuvRoute = isRawFormat(mTargetFormat) ? null : YuvMergeRoute.current();
            final boolean yuvAfter = flash == null && !manualPsl && yuvRoute != null && yuvRoute.afterShutter;
            final boolean yuvRing = !isRawFormat(mTargetFormat) && !yuvAfter;
            float[] yuvMeter = null;
            if (yuvAfter) synchronized (mRingLock) { yuvMeter = YuvMergeRoute.meter(mZslRingBuffer.peekLast()); }
            final float[] yuvScene = yuvMeter;
            ScamStockAe.Plan planned;
            {
                if(!hybridZslRequested)throw new IllegalStateException((scamHybridSelected?"Hybrid":"SCAM HDR")+Lang.t(" требует ZSL RAW-поток"," needs the ZSL RAW stream"));
                // The hybrid's N is the ring at the preview exposure (P27: its own N, never the SCAM HDR planner). Since P129 every
                // shot is the hybrid's (mergeRoute()): the SCAM HDR bracket planner and the vivo stock-AE plan are gone.
                planned=hybridNPlan(manualPsl);
            }
            final ScamStockAe.Plan stockPlan=planned;
            // P643 (owner: «Для Oldstyle убери своей AE, используй такой же как и у режима фото»): an Oldstyle shot has no branch
            // of its own here - the photo mode's capture as it is (the ring at the viewfinder's exposure, the same plan, manual
            // row, flash, sizes); what is the mode's is in the settings the plan reads: no ultrashort and no long frames
            // (PreferenceKeys.noHdrFrames) and its own frame count when the row is set (getHybridZslFrames).
            // The capture route of this shot (P27: a SCAM HDR shot without an S/ES plan is captured and merged by the Hybrid).
            final boolean scamHybridShot=scamHybridSelected;
            final HybridPlan[] hybridPlanHolder={null};
            final float[] normalBackLongRatio={0f};
            final boolean scamZslRequested = hybridZslRequested && scamCapture;
            if (scamZslRequested) {
                mLiveRawRouter.clear();
                mNativeRawPslCapture = true;
            }
            {
                mLiveRawRouter.clear();
                mNativeRawPslCapture=true;mZslCapturing=true;
                if(!hybridZslRequested || manualPsl || yuvAfter) synchronized(mRingLock) {
                    for(RingFrame image:mZslRingBuffer)image.close();
                    mZslRingBuffer.clear();mHexZslResults.clear();
                }
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder stillRequest;
            if(ScameraApp.getSettings().mode.equals(CameraMode.RAWVIDEO)) {
                stillRequest = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                stillRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, videoFpsRange());
            } else {
                // P635: a recipe's stills from the profile's template, left as the template has them (the owner's documents: a
                // request with the companion built from the preview template failed every frame)
                stillRequest = mCameraDevice.createCaptureRequest(recipePlan != null && recipePlan.recipe.stillTemplate > 0
                        ? recipePlan.recipe.stillTemplate : CameraDevice.TEMPLATE_STILL_CAPTURE);
                // P497: the series takes the repeating preview's settings (vendor keys included), its own exposure set below - a
                // series unlike the preview made the HAL switch pipelines each way (vivo X200 Ultra: 0.3 s without a frame before
                // and after it). scam_dev series_as_preview 0: the still template alone, as before
                if (recipePlan == null && hybridZslRequested && mPreviewRequestBuilder != null && PreferenceKeys.scamDevSwitch("series_as_preview", true)) {
                    try {
                        final long copyNs = android.os.SystemClock.elapsedRealtimeNanos(); // P625: the copy's share of submit=
                        final int copied = ScamPreview.copySettings(mPreviewRequestBuilder.build(), stillRequest);
                        Log.i("SCAM_CAPTURE", "series settings from the preview: " + copied + " keys in "
                                + (android.os.SystemClock.elapsedRealtimeNanos() - copyNs) / 1_000_000 + " ms");
                        com.scamera.app.processing.ShotTimeline.capture("series_keys");
                    } catch (RuntimeException e) {
                        Log.w("SCAM_CAPTURE", "series settings from the preview failed", e);
                    }
                }
            }
            Antibanding.apply(stillRequest, mCameraCharacteristics); // P411
            if (PreferenceKeys.isPortraitEnabled())
                com.scamera.app.control.subject.SubjectFocus.applyFaceDetectMode(stillRequest, mCameraCharacteristics);
            float focus = focusDiopters;
            double frametime = stockPlan != null ? ExposureIndex.time2sec(stockPlan.shutter(0)) : 1 / 30.0;
            //this.mCaptureSession.stopRepeating();
            // SCAM keeps the preview running: its L/S/ES must not reach the
            // viewfinder (they flashed dark/bright there), as in the stock camera.
            stillRequest.addTarget(mImageReaderRaw.getSurface());
            if (mLoficReader != null) stillRequest.addTarget(mLoficReader.getSurface()); // P641d: a still's overflow read with it
            // P635: the recipe's companion stream in the same request (without it the full-size frame is the binned one doubled)
            final ImageReader recipeCompanion = mRecipeCompanion;
            if (recipePlan != null && recipeCompanion != null) stillRequest.addTarget(recipeCompanion.getSurface());
            // P90 (owner: the viewfinder froze 0.7-0.8 s at every shot): as WhyCam and other stock cameras do, the series also feeds the viewfinder;
            // the renderer scales each series frame back to the viewfinder's exposure (ViewfinderGain), so nothing flashes.
            // scam_dev "series_viewfinder 0": RAW only, as before.
            // P646: not the YUV route's series (its own tone curve would flash on the viewfinder)
            if (surface != null && mRepeatingIsp && !VivoPd2454NativeRaw.active(physicalID) && !mIspSurfaceOff && !yuvAfter
                    // P626: a MAXIMUM_RESOLUTION still takes the RAW alone; P635: a recipe's still the viewfinder only as its companion
                    && (recipePlan != null ? recipePlan.viewfinderCompanion() : NubiaMaxResPlan.viewfinderInStill(nubiaPerShot()))
                    && PreferenceKeys.scamDevSwitch("series_viewfinder", true)) {
                stillRequest.addTarget(surface);
                com.scamera.app.ui.camera.views.viewfinder.ViewfinderGain.setReference(
                        mPreviewIso * (mPreviewExposureTime / 1e9));
            }
            Camera2ApiAutoFix.resetTileSize();
            applyZoom(stillRequest, false);
            cameraRotation = ScameraApp.getGravity().getCameraRotation(mSensorOrientation);
            // the shot's own rotation and camera, as the FULLRES path keeps them: its processing runs later (queued behind earlier
            // merges, after the gyro wait), when the next press has turned the field and a lens switch replaced the static
            final int shotRotation = cameraRotation;
            final CameraCharacteristics pressCharacteristics = mCameraCharacteristics;


            // the torch stays on through the shot's frames (the still template carries FLASH_MODE OFF); P590: only the torch (a
            // preview result of the precapture's pre-flash reports FIRED too), never on a flash frame (the AE runs the flash)
            if (flash == null && mFlashSupported && PreferenceKeys.getAeMode() == FlashMode.TORCH) stillRequest.set(FLASH_MODE, FLASH_MODE_TORCH);
            Log.d(TAG, "still request: focus " + focus);
            stillRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            // the series takes the shot's own OIS mode where the lens lists one (the viewfinder's could differ)
            if (oisRequestable(mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)))
                applyOisMode(stillRequest, true);
            // P54b: the series keeps the session's stabilisation (OIS ON, the vivo EIS key), as the stock app's requests do
            if (mForcedStab) ForcedStabilization.apply(stillRequest, oisForceable(), mForcedStabEis);

            Log.d(TAG, "still request: neutral " + Arrays.toString(mPreviewTemp));
            stillRequest.set(CaptureRequest.JPEG_ORIENTATION, ScameraApp.getGravity().getCameraRotation(mSensorOrientation));
            final TouchFocus touch = mTouchFocus;
            if (touch != null && touch.isTouchFocus) {
                // a tapped point: the still meters and focuses where the viewfinder does
                stillRequest.set(CaptureRequest.CONTROL_AE_REGIONS, mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_REGIONS));
                stillRequest.set(CaptureRequest.CONTROL_AF_REGIONS, mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_REGIONS));
            } else {
                applyAeRegions(stillRequest);
            }
            VendorTagUtils.builderSessionApply(stillRequest, true, useMaximumResolutionKey, physicalID);
            if (mDcgRequestKeys) Dcg14Mode.applyRequest(stillRequest, mCameraCharacteristics); // P230: the DCG mode
            // P108: the full-resolution RAW stream takes MAXIMUM_RESOLUTION requests only
            if (recipePlan != null) { // P635: only an annotated profile says a pixel mode
                final Integer pixelMode = recipePlan.pixelMode(true);
                if (pixelMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stillRequest.set(CaptureRequest.SENSOR_PIXEL_MODE, pixelMode);
            } else if (mFullResSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                stillRequest.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            XiaomiTeleZoom.applyLast(stillRequest, physicalID);
            try {
                stillRequest.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            } catch (Exception e) {
                Log.d(TAG, "Failed to set LENS_SHADING_MAP_MODE_ON:" + Log.getStackTraceString(e));
            }

            mShotRequests = new ArrayList<>();
            burstShake = new ArrayList<>();
            exposureByFrame = new HashMap<>();
            SaverImplementation.FRAME_QUEUE.clear();

            // SCAM HDR's plan: four N, short S and ES, long L (the N normally come from the ZSL ring); the hybrid
            // takes its own N count from the ring and its Bento / Shasta requests from HybridPlan.
            int denoiseFrameCount = 4, shortFrameCount = 2, longFrameCount = 1;
            // P494: the shade's HDR off as «Спорт»: no short and no long frames
            if (com.scamera.app.settings.PreferenceKeys.noHdrFrames()) shortFrameCount = longFrameCount = 0;
            if (flash != null) {
                // P590: the flash frame alone, after the shutter (as PSL: the viewfinder keeps running, its RAW routed by the
                // timestamp); the ring keeps the press's frames for a flash that does not fire (flashFallbackShot)
                denoiseFrameCount = 1;
                shortFrameCount = longFrameCount = 0;
                mHybridZslCapture = false;
                mZslCapturing = true;
                mPendingZslNormalFrames = new ArrayList<>();
                if (scamZslRequested) {
                    mLiveRawRouter.clear();
                    mScamRouted = true;
                }
            } else if (manualPsl || yuvAfter) {
                // as an empty ring: every frame after the shutter, the viewfinder keeps running, RAWs routed by their timestamp
                // (P646: the YUV route's series as well)
                mHybridZslCapture = false;
                mZslCapturing = true;
                mPendingZslNormalFrames = new ArrayList<>();
                if (scamZslRequested) {
                    mLiveRawRouter.clear();
                    mScamRouted = true;
                }
                Log.i("SCAM_CAPTURE", yuvAfter ? "YUV MERGE: the series after the shutter (" + yuvRoute + "), the ZSL ring is not used"
                        : "manual shutter " + paramController.pslShutterNs() + " ns: PSL, the ZSL ring is not used");
            } else if (hybridZslRequested) {
                // Block preview RAWs first. Do not route them into ImageSaver:
                // queued preview images would consume the bracket frame slots.
                mHybridZslCapture = false;
                mZslCapturing = true;
                // This graph consumes four N inputs; avoid selecting an old
                // reference from a longer series that the graph cannot use.
                // The graph consumes four N; frames beyond four (same exposure,
                // newest first) are merged into those slots by the worker.
                // P113: «Движение» behind a queue of merges takes fewer ring frames, as the stock snapshot mode
                final int zslWanted = scamHybridShot ? PreferenceKeys.getHybridZslFrames() : Math.max(4, PreferenceKeys.getScamZslFrames());
                final int zslFrames = scamHybridShot && com.scamera.app.settings.MotionMode.on()
                        ? com.scamera.app.settings.MotionMode.framesForQueue(zslWanted, sScamPending.get()) : zslWanted;
                if (zslFrames != zslWanted) Log.i("SCAM_CAPTURE", "motion: " + sScamPending.get() + " shots wait for their merge, "
                        + zslFrames + " ring frames for this one (of " + zslWanted + ")");
                // P679: a LOFIC session's shot takes its pairs (the main RAW stream is not in its session)
                mLoficShot = XiaomiLofic.sessionActive();
                if (mLoficShot) mPendingZslNormalFrames = drainLoficPairs(zslFrames);
                else mPendingZslNormalFrames = drainZslNormalFrames(zslFrames,stockPlan,scamZslRequested,scamHybridShot);
                if (mLoficReader != null) attachLoficPartners(mPendingZslNormalFrames); // P641d: DCG + LOFIC pairs
                if (!mPendingZslNormalFrames.isEmpty()) {
                    denoiseFrameCount = mPendingZslNormalFrames.size();
                    long[] zslTimestamps = new long[denoiseFrameCount];
                    for (int i = 0; i < denoiseFrameCount; i++) {
                        zslTimestamps[i] = mPendingZslNormalFrames.get(i).timestamp;
                    }
                    CaptureResult gyroBase=mNativeZslBase!=null?mNativeZslBase:mPreviewCaptureResult;
                    Long previewExposure = gyroBase != null ? gyroBase.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
                    ScameraApp.getGyro().ringFramesMotion(zslTimestamps,
                            previewExposure != null ? previewExposure : 0L, burstShake, isGyroClockComparable());
                } else {
                    // P316 (settings audit): not in an OPPO-family picked sensor mode - the normal-back plan below would expose the
                    // series P315 forbids (the press guard counts ring frames the drain may still drop); the shot is refused as the
                    // guard refuses it, the catch below cleans up (nothing is submitted yet)
                    if (SensorModePick.emptyRingRefused(true)) {
                        Log.w("SCAM_CAPTURE", "sensor mode: the ring gave no usable frame, the shot is not taken (" + SensorModePick.SERIES_REASON + ")");
                        throw new IllegalStateException(Lang.t("Камера ещё готовится. Повторите снимок.", "The camera is still getting ready. Take the shot again."));
                    }
                    // Camera was just opened, the ring has not filled yet or holds no usable frame: N is taken after the
                    // shutter this once (tolerant for both routes, P27).
                    Log.w(TAG, (scamHybridShot ? "Hybrid" : "SCAM HDR") + " ZSL ring empty; N frames taken after the shutter");
                }
                if (scamZslRequested) {
                    // Keep the viewfinder and AE running: requests submitted with
                    // capture() go ahead of the repeating preview, and every RAW is
                    // routed by its capture-start timestamp from here on.
                    mLiveRawRouter.clear();
                    mScamRouted = true;
                } else {
                    try {
                        mCaptureSession.stopRepeating();
                        mCaptureSession.abortCaptures();
                    } catch (CameraAccessException e) {
                        Log.w(TAG, "Could not fully stop ZSL preview before bracket", e);
                    }
                    drainReader(mImageReaderRaw);
                }
            }
            final boolean hybridZsl = hybridZslRequested && !mPendingZslNormalFrames.isEmpty();
            Log.i(TAG, "SCAM controls: normal=" + denoiseFrameCount + " short=" + shortFrameCount
                    + " long=" + longFrameCount + " route=" + PreferenceKeys.mergeRoute());
            // Keep the total inside the proven RAW buffer ceiling.
            int frameCount = denoiseFrameCount > 0
                    ? Math.min(37, denoiseFrameCount + shortFrameCount + longFrameCount)
                    : denoiseFrameCount;
            longFrameCount = denoiseFrameCount > 0
                    ? Math.max(0, frameCount - denoiseFrameCount - shortFrameCount) : 0;
            //if (frameCount == 1) frameCount++;
            // P635c: a recipe shot takes the profile's frames (the plan is cut to them below): the counter shows as many
            if (recipePlan != null) frameCount = Math.min(frameCount, recipePlan.recipe.frames);
            cameraEventsListener.burstSize(frameCount);
            Log.d(TAG, "shot: manual " + paramController.offAuto() + ", align " + ScameraApp.getSettings().alignMethod
                    + ", HDR " + IsoExpoSelector.bracketing);
            // the still holds the focus where the viewfinder's AF left it: AUTO with a CANCEL (no new scan in the series) unless
            // the viewfinder already runs AUTO by itself
            final Integer previewAf = mPreviewRequestBuilder.get(CONTROL_AF_MODE);
            final boolean afSetAuto = PreferenceKeys.getAfMode() == CaptureRequest.CONTROL_AF_MODE_AUTO
                    && !ScameraApp.getSettings().mode.equals(CameraMode.RAWVIDEO);
            if (previewAf != null && previewAf != CaptureRequest.CONTROL_AF_MODE_AUTO || afSetAuto) {
                stillRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                stillRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            }
            paramController.applyWhiteBalance(stillRequest);
            // P252: the viewfinder's hyperfocal fallback holds for the shot (the still template carries the HAL's AF)
            final float parked = mParkedFocus;
            if (parked >= 0f && afOffInPreview()) {
                stillRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                stillRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, parked);
            }
            IsoExpoSelector.tripodShot = ScameraApp.getGyro().getTripod();
            if(scamHybridShot) {
                // SCAM Hybrid: N from the ring, then the ultrashort (Bento) and the bracketed frames (Shasta) of the plan.
                // P27: one ring frame is enough; with an empty ring N is taken after the shutter (tolerant), without any
                // known N exposure the camera's AE exposes the frames. Planning never throws.
                IsoExpoSelector.shotPlan.clear();
                HybridPlan plan;
                // P98: while the HAL reports motion, a bracketed frame's shutter stays within its motion-safe exposure
                HybridPlan.setMotionCap(manualPsl?0:HalMotion.motionCapNs(mNativeZslBase!=null?mNativeZslBase:mPreviewCaptureResult)); HybridPlan.setDeepShadow(mLastZslDeepShadow); HybridPlan.setShadow(mLastZslShadow); // P409 / P410
                try {
                if(flash!=null) {
                    // P590: the flash frame's own exposure and white balance (no ring AE, no ETTR, no brackets)
                    plan=flash.manual() ? HybridPlan.flashManual(flash.manualShutterNs,flash.manualIso)
                            : HybridPlan.flash(FlashMode.stillAeMode(flash.mode));
                } else if(hybridZsl) {
                    // N = the newest ring frame (the base of the selection): its own exposure, not the cutoff plan's.
                    CaptureResult base=mNativeZslBase;
                    Long baseNs=base==null?null:base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer baseIso=base==null?null:base.get(CaptureResult.SENSOR_SENSITIVITY);
                    long nNs=baseNs!=null&&baseNs>0?baseNs:stockPlan!=null?stockPlan.shutter(0):0;
                    int nIso=baseIso!=null&&baseIso>0?baseIso:stockPlan!=null?stockPlan.iso(0):0;
                    try {
                        // P113: «Движение» shoots from the ring alone, as the stock snapshot mode (no frames after the shutter)
                        plan=mLoficShot ? HybridPlan.zslOnly(nNs,nIso,"LOFIC pairs from the ring: the session has no other RAW stream (P679)")
                                : com.scamera.app.settings.MotionMode.on() ? HybridPlan.zslOnly(nNs,nIso)
                                : !SensorModePick.seriesAllowed() ? HybridPlan.zslOnly(nNs,nIso,SensorModePick.SERIES_REASON)
                                // P646: YUV frames at the camera's curve: no bracket (its light cannot be compared)
                                : yuvRing ? HybridPlan.zslOnly(nNs,nIso,"YUV merge from the ring, the camera's curve: no brackets (P646)")
                                : HybridPlan.build(nNs,nIso,mLastZslClipFraction,mCameraCharacteristics,mExposureLimits);
                    } catch(RuntimeException noPlan) {
                        Log.w("SCAM_CAPTURE","hybrid plan unavailable ("+noPlan.getMessage()+"): one more N frame after the shutter");
                        plan=HybridPlan.single(nNs,nIso);
                    }
                } else if(yuvAfter) {
                    // P646: N + ES + L after the shutter at our curve (YuvMergePlan), from the viewfinder's metered exposure
                    final CaptureResult metered=mPreviewCaptureResult;
                    final Long mNs=metered==null?null:metered.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    final Integer mIso=metered==null?null:metered.get(CaptureResult.SENSOR_SENSITIVITY);
                    final long yNs=stockPlan!=null&&stockPlan.shutter(0)>0?stockPlan.shutter(0):mNs!=null?mNs:0;
                    final int yIso=stockPlan!=null&&stockPlan.iso(0)>0?stockPlan.iso(0):mIso!=null?mIso:0;
                    plan=YuvMergePlan.build(yNs,yIso,mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                            mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),yuvScene[0],yuvScene[1],
                            YuvMergePlan.Tuning.read(),com.scamera.app.processing.yuv.YuvFrames.packedBytes(
                                    mImageReaderRaw.getWidth(),mImageReaderRaw.getHeight(),yuvRoute.format==YuvMergeRoute.P010?10:8),
                            yuvRoute.curve||yuvRoute.hlg);
                } else {
                    plan=com.scamera.app.settings.MotionMode.on()
                            ? HybridPlan.autoExposure(Math.min(4, PreferenceKeys.getHybridZslFrames()))
                            : manualPsl && stockPlan!=null
                            ? HybridPlan.manualLong(stockPlan.shutter(0),stockPlan.iso(0),mLastZslClipFraction,mCameraCharacteristics,mExposureLimits)
                            : stockPlan!=null
                            ? HybridPlan.buildNormalBack(stockPlan.shutter(0),stockPlan.iso(0),mLastZslClipFraction,mCameraCharacteristics,4,mExposureLimits,
                                    paramController.motionCeilingNs())
                            : HybridPlan.autoExposure(4);
                }
                } finally { HybridPlan.setMotionCap(0); HybridPlan.setDeepShadow(0f); HybridPlan.setShadow(Float.NaN); }
                // P219: a full-resolution session (Pixel / Snapdragon MAXIMUM_RESOLUTION) asks for one long frame; its colour block
                // is the stream's (0 here: «Наш ремозаик» off keeps every frame for the binned merge)
                if(mFullResSession && !com.scamera.app.settings.MotionMode.on())
                    plan=plan.withLongFrames(HybridPlan.fullResLongFrames(true,0,PreferenceKeys.isHybridMosaicRemosaic()));
                if(recipePlan!=null) plan=plan.limitedTo(recipePlan.recipe.frames); // P635: the profile's frames a shot
                if(flash==null) plan=plan.withNote(ettrNote()); // P377: the shot's ETTR (the stamp's line, the log); P590: none on a flash frame
                hybridPlanHolder[0]=plan;
                Log.i("SCAM_CAPTURE",plan.description);
                for(ImageFrame normal:mPendingZslNormalFrames) {
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(normal.measuredExposure,normal.measuredExposure,normal.measuredExposure,
                            normal.measuredIso,normal.measuredIso,normal.measuredIso,normal.measuredIso);
                    IsoExpoSelector.shotPlan.add(pair);
                }
                // Focus and stabilisation as the preview that delivered the N frames (WhyCam / SCAM 9.6: continuous_picture, no
                // trigger). The still template above switches to AF_MODE_AUTO with a CANCEL trigger, which moved the lens on
                // PHY110: every post-shutter frame came out ~26 % softer than the N frames at the same shutter, so the Shasta
                // gate dropped all bracketed frames and the ultrashort (Bento) highlights were soft.
                for(CaptureRequest.Key<Integer> key:java.util.Arrays.asList(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.EDGE_MODE,
                        CaptureRequest.HOT_PIXEL_MODE)) {
                    // P635: a recipe's still keeps its template's capture intent
                    if(recipePlan!=null&&key==CaptureRequest.CONTROL_CAPTURE_INTENT) continue;
                    Integer v=mPreviewRequestBuilder.get(key);
                    // P365 step 9: the viewfinder's own noise reduction / edge off (vf_isp_light) stays off the series
                    final Integer[] ispLight=mIspLightModes;
                    if(ispLight!=null&&key==CaptureRequest.NOISE_REDUCTION_MODE) v=ispLight[0];
                    else if(ispLight!=null&&key==CaptureRequest.EDGE_MODE) v=ispLight[1];
                    if(v!=null) stillRequest.set(key,v);
                }
                // P84c: AE_TARGET_FPS_RANGE is a session parameter on the X200 Ultra (availableSessionKeys): a series request with
                // the still template's range and the viewfinder's repeating request with its own made the camera service
                // reconfigure between them, waiting for idle; behind three 4 s frames that wait passed its 12.5 s limit
                // ("reconfigureCamera: Can't idle device in 12.5 seconds") and the camera died (error 4). The series takes the
                // viewfinder's range (manual AE-off frames are timed by their exposure, not by this range).
                // P635c: a recipe's still is its template alone (no preview settings copied): a hand-set focus goes with its AF mode
                if(recipePlan!=null) recipeFocus(stillRequest);
                android.util.Range<Integer> fps=mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                if(fps!=null) stillRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);
                if(recipePlan!=null&&recipePlan.recipe.fpsLow>0) stillRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new android.util.Range<>(recipePlan.recipe.fpsLow,recipePlan.recipe.fpsHigh)); // P635
                stillRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                // P646: the YUV route's series: our tone curve, the camera's noise reduction minimal, its sharpening off
                if(yuvAfter) Log.i("SCAM_CAPTURE","YUV MERGE series controls: "+YuvMergeRoute.applyCaptureControls(stillRequest,mCameraCharacteristics,yuvRoute));
                long[] times=new long[plan.requests.size()];
                for(int i=0;i<plan.requests.size();i++) {
                    HybridPlan.Request r=plan.requests.get(i);
                    plan.apply(stillRequest,i);
                    // P98: a frame past the declared frame-duration limit (Xiaomi 17 Ultra: 1 s) lifts the Qualcomm FPS limit
                    if(!r.autoExposure&&LongExposureKeys.apply(stillRequest,r.shutterNs,
                            mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)))
                        Log.i("SCAM_CAPTURE","long frame "+r.shutterNs/1e6+" ms: Qualcomm FPS limit lifted (P98)");
                    stillRequest.setTag(new ImageFrame.ScamCaptureTag(mShutterGeneration,mShotRequests.size(),r.role));
                    CaptureRequest request=stillRequest.build();mShotRequests.add(request);mCaptureRequest=request;
                    times[i]=r.shutterNs;
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(r.shutterNs,r.shutterNs,r.shutterNs,r.iso,r.iso,r.iso,r.iso);
                    pair.isLongFrame=r.role==ImageFrame.CaptureRole.LONG;
                    pair.isHighlightFrame=r.role==ImageFrame.CaptureRole.SHORT||r.role==ImageFrame.CaptureRole.EXTRA_SHORT;
                    IsoExpoSelector.shotPlan.add(pair);
                }
                // W1.9: the sensor re-registration (~39 ms) runs after the submit (Gyro.startBurstRegistration).
                ScameraApp.getGyro().prepareSeries(times,burstShake,true);
            } else {
                IsoExpoSelector.shotPlan.clear();
                // L from the ZSL N frames: after the press only S and ES are exposed
                // (the 125 ms L frame cost ~0.25 s of shutter time).
                final boolean zslLong=hybridZsl && (PreferenceKeys.isScamZslLong() || stockPlan.longBeyondSensor);
                // P27: in normal-back an L the sensor cannot expose is built from the N taken after the shutter (six requests).
                final boolean backLong=!hybridZsl && stockPlan.longBeyondSensor;
                if(zslLong) {
                    final float ratio=(float)Math.max(1,Math.min(64,stockPlan.longRatio()));
                    for(ImageFrame normal:mPendingZslNormalFrames)normal.syntheticLongRatio=ratio;
                    Log.i("SCAM_CAPTURE","L from "+mPendingZslNormalFrames.size()+" ZSL N frames, ratio="+ratio);
                }
                normalBackLongRatio[0]=backLong?(float)Math.max(1,Math.min(64,stockPlan.longRatio())):0f;
                if(backLong)Log.i("SCAM_CAPTURE","normal-back: L beyond the sensor, built from the N frames, ratio="+normalBackLongRatio[0]);
                long[] times=new long[hybridZsl?(zslLong?2:3):backLong?6:7];int timeIndex=0;
                for(int i=0;i<7;i++) {
                    long ns;int iso;
                    if(i==4 && (zslLong||backLong))continue;
                    if(i<4 && hybridZsl) {
                        ImageFrame normal=mPendingZslNormalFrames.get(i);
                        stockPlan.verifyZslNormal(normal.getMatchedCaptureMetadata(),scamZslShutterTimestamp);
                        ns=normal.measuredExposure;iso=normal.measuredIso;
                    } else {
                        stockPlan.apply(stillRequest,i,mCameraCharacteristics);
                        stillRequest.setTag(new ImageFrame.ScamCaptureTag(mShutterGeneration,mShotRequests.size(),stockPlan.role(i)));
                        CaptureRequest request=stillRequest.build();mShotRequests.add(request);mCaptureRequest=request;
                        ns=stockPlan.shutter(i);iso=stockPlan.iso(i);times[timeIndex++]=ns;
                    }
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(ns,ns,ns,iso,iso,iso,iso);
                    pair.isLongFrame=i==4;pair.isHighlightFrame=i>=5;IsoExpoSelector.shotPlan.add(pair);
                }
                ScameraApp.getGyro().prepareSeries(times,burstShake,true);
            }

            //img
            Log.d(TAG, "FrameCount:" + frameCount);
            mImageSaver = new ImageSaver(cameraEventsListener);
            mImageSaver.iszAspect4x3 = mShutterIszAspect4x3;
            // P635: the recipe shot's truth check on its first frame (a doubled frame is taken back to the binned one)
            if (recipePlan != null && mRecipeProof == 2) mRecipeProof = 0; // P635b: the shot's own first frame is the proof now
            final FullResRecipeShot recipeShot = recipePlan == null ? null : new FullResRecipeShot(String.valueOf(physicalID),
                    recipePlan.recipe.id.isEmpty() ? "own profile" : recipePlan.recipe.id,
                    com.scamera.app.processing.RawLevels.current(mCameraCharacteristics, null).meanBlack(), XiaomiSensorRow.device());
            mRecipeShot = recipeShot;
            mImageSaver.recipeShot = recipeShot;
            if (recipePlan != null) Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID + " shot: " + mShotRequests.size() + " stills template "
                    + FullResRecipe.templateName(recipePlan.recipe.stillTemplate) + " -> " + recipePlan.targets(true) + ", still tags: "
                    + recipePlan.requestTags(true) + (recipePlan.pixelMode(true) == null ? ", no sensor pixel mode" : ", MAXIMUM_RESOLUTION") + " (P635)");
            mImageSaver.manualWbAtPress = mShutterManualWb;
            // P554 (owner, 2026-11-08, OPPO Find X8 Ultra: «ISZ все зелёные»): the press's Quad / Tetra white balance route (P408's
            // viewfinder neutral, P315's sensor mode) reached only the FULLRES saver; every ring / Camera2 mosaic shot lost it and
            // took P121's projection (0.491 / 0.524 for a viewfinder at 0.462 / 0.540: a white ceiling R/G 0.94, yellow-green)
            mImageSaver.mosaicWbAtPress = mShutterMosaicWb;
            if (flash != null) mImageSaver.mosaicWbAtPress = null; // P590: the flash frame's own white balance
            mImageSaver.dcgShot = dcgShotNow();
            // P679: a LOFIC shot is no DCG shot for the processing (its pairs are merged before the burst merge)
            mImageSaver.loficShot = mLoficShot && hybridZsl;
            if (mImageSaver.loficShot) mImageSaver.dcgShot = false;
            mLoficShot = false;
            // the shot's digital-zoom crop (after the drain: rawCropOfShot is in it) and camera, read by its processing, not the
            // next press's
            mImageSaver.zoomResidual = com.scamera.app.control.ZoomController.shotResidual();
            mImageSaver.cameraIdAtPress = ScameraApp.getSettings().cameraId;
            mImageSaver.planNote = hybridPlanHolder[0] != null ? hybridPlanHolder[0].description : null;
            mImageSaver.lensFilterAtPress = mShutterLensFilter;
            mImageSaver.lookAtPress = mShutterLook;
            mImageSaver.motionAtPress = mShutterMotion;
            final ScamCaptureSequence scamSequence = hybridPlanHolder[0]!=null
                        ? (hybridZsl ? ScamCaptureSequence.hybridZsl(mShotRequests,mPendingZslNormalFrames,scamZslShutterTimestamp)
                                     : ScamCaptureSequence.hybridFuture(mShotRequests,scamZslShutterTimestamp))
                        : !hybridZsl
                        ? ScamCaptureSequence.stockNormalBack(mShotRequests,scamZslShutterTimestamp)
                        : ScamCaptureSequence.stockZsl(mShotRequests,mPendingZslNormalFrames,scamZslShutterTimestamp);
            // Buffered RAWs are already present; only submitted requests consume reader slots.
            mImageSaver.setFrameCount(scamSequence != null ? mShotRequests.size() : frameCount);
            // P30: the Hybrid's RAWs (ring and post-shutter) are copied straight into one memfd the worker maps (ShotArena);
            // without it (no memfd, binning, packed RAW) they are copied into native buffers as before.
            com.scamera.app.util.ShotArena arenaForShot = null;
            if (scamHybridShot && !ScameraApp.getSettings().binning) {
                // P91: in free slots of the ring's memfd (ring frames of a plain RAW16 stream are views of their own slots and
                // take none); also for a shot without ring frames (PSL), its series then reaches the worker without the copy
                int frameBytes = 0, copies = 0;
                for (ImageFrame f : mPendingZslNormalFrames) {
                    frameBytes = Math.max(frameBytes, f.pendingBytes());
                    if (!f.viewable()) copies++;
                }
                if (frameBytes == 0 && mImageReaderRaw != null) frameBytes = mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight() * 2;
                if (frameBytes > 0) arenaForShot = shotArena(copies + mShotRequests.size(), frameBytes);
                if (arenaForShot != null) {
                    for (ImageFrame f : mPendingZslNormalFrames) f.arena = arenaForShot;
                    mImageSaver.shotArena = arenaForShot;
                    mShotArena = arenaForShot;
                    Log.i("SCAM_CAPTURE", "RAWs of the shot into " + arenaForShot);
                }
            }
            final com.scamera.app.util.ShotArena shotArena = arenaForShot;
            if (hybridZsl) {
                mImageSaver.setImageFormat(mTargetFormat);
                SaverImplementation.FRAME_QUEUE.addAll(mPendingZslNormalFrames);
                mImageSaver.saverImpl.frameCount = frameCount;
            }
            Log.d(TAG, "series of " + frameCount + " frames set up");
            final Surface scamRawSurface = scamSequence != null ? mImageReaderRaw.getSurface() : null;
            // P653: a recipe still's companion (its lost buffer fails nothing) and the stills' first result (the tags the HAL reports)
            final Surface recipeCompanionSurface = recipePlan != null && recipeCompanion != null ? recipeCompanion.getSurface() : null;
            final java.util.concurrent.atomic.AtomicBoolean recipeStillLogged = new java.util.concurrent.atomic.AtomicBoolean(recipePlan == null);
            final long[] baseFrameNumber = {0};
            final int[] maxFrameCount = {hybridZsl ? mShotRequests.size() : frameCount};
            final int zslNormalCount=hybridZsl ? mPendingZslNormalFrames.size() : 0;
            final boolean scamNormalBack=scamSequence!=null && !hybridZsl;
            // P27: one completion per shot (sequence completed, aborted, rescued after a camera close or a stall); callbacks of a
            // completed shot are ignored, the shot's own saver is used even if the field was replaced.
            final java.util.concurrent.atomic.AtomicBoolean shotDone=new java.util.concurrent.atomic.AtomicBoolean();
            // P27 (M6): request indices the HAL failed or lost the RAW of, and whether the queue was flushed (FlushLossStats).
            final java.util.Set<Integer> halLost=java.util.concurrent.ConcurrentHashMap.newKeySet();
            final java.util.concurrent.atomic.AtomicBoolean shotFlushed=new java.util.concurrent.atomic.AtomicBoolean();
            final FlushLossStats flushStats=FlushLossStats.of(String.valueOf(physicalID));
            final ImageSaver shotSaver=mImageSaver;
            final boolean shotHybridRoute=scamHybridShot;
            // P590: the flash frame came back without the flash (FLASH_STATE not FIRED): it is left out, the normal shot follows
            final boolean[] flashMissed={false};
            // P35: the stream key of this shot (its stored colour block skips the detector in the processing).
            // P635: a recipe shot's block is measured on its own frames every time (no stored block: a real frame and a doubled one
            // taken back to the binned frame are different mosaics under one stream key)
            final String shotMosaicKey=recipePlan!=null?"off":com.scamera.app.processing.MosaicStream.key();
            // P214i: by the X300 Ultra's list; 0 once the module's frames proved a plain Bayer (the stored 1 merges then)
            // P276: a picked full-size row of the SensorModeTable declares the sensor's own mosaic
            final int iszBlock=VivoQcomIsz.declaredBlockOf(com.scamera.app.settings.ModuleRegistry.active());
            // P631: a picked Xiaomi row whose frames proved a plain Bayer (the HAL's remosaic) merges as the Bayer it is
            final int shotDeclaredBlock=XiaomiSensorRow.shotBlock(iszBlock>0?iszBlock:SensorModePick.sessionBlock(mCameraCharacteristics),
                    SensorModePick.session()!=null&&XiaomiSensorRow.device(),
                    com.scamera.app.processing.MosaicBlockStore.get().stored(shotMosaicKey));
            final int nativeBaseIndex=denoiseFrameCount/2;
            final TotalCaptureResult[] nativeBaseResult={hybridZsl?mNativeZslBase:null};
            // W1.9: the vendor-key scan of every bracket result (10-15 ms on the camera callback thread) only with diagnostics.
            final boolean vendorScan=scamCapture&&PreferenceKeys.isScamDiagnosticsEnabled();

            // ZSL SCAM HDR: the N frames are already buffered and only the short tail is exposed, so the UI
            // treats the press as an instant shot (no capture ring, no locked controls, provisional thumbnail).
            cameraEventsListener.stillStarted(hybridZsl ? zslStartTag(mShotRequests.size()) : "CaptureStarted!"); // P234
            mMeasuredFrameCnt = 0;

            cameraEventsListener.burstPrepared(null);
            this.mShotCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    if (shotDone.get()) return;
                    Long startNs = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
                    Integer startIso = request.get(CaptureRequest.SENSOR_SENSITIVITY);
                    if (startNs != null && startIso != null) // P90: its viewfinder frame's brightness
                        com.scamera.app.ui.camera.views.viewfinder.ViewfinderGain.record(timestamp, startIso * (startNs / 1e9));
                    if (mNativeRawPslCapture || mScamRouted || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, true);
                    if (mScamRouted) mScamTailTimestamp = Math.max(mScamTailTimestamp, timestamp);
                    mPreviewGap.seriesStart(timestamp);
                    if (sTimelineSubmitNs > 0) Log.i("SCAM_TIMELINE", "bracket start frame=" + frameNumber + " dtMs=" + (timestamp - sTimelineSubmitNs) / 1_000_000
                            + " nowMs=" + (android.os.SystemClock.elapsedRealtimeNanos() - sTimelineSubmitNs) / 1_000_000);

                    final boolean first = baseFrameNumber[0] == 0;
                    if (first) {
                        baseFrameNumber[0] = frameNumber;
                        com.scamera.app.processing.ShotTimeline.capture("first_start");
                        // the gyro's record of the series starts with its first frame
                        if (maxFrameCount[0] != -1) ScameraApp.getGyro().seriesFrameStarted();
                    }
                    Log.v("BurstCounter", (first ? "series starts at frame " : "series frame ") + frameNumber);
                    cameraEventsListener.frameStarted(null);
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partial) {
                    // the shot's first metadata, until its first complete result takes over (no log: per partial result)
                    if (!shotDone.get() && mCaptureResult == null) mCaptureResult = partial;
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    if (shotDone.get()) return;
                    if (!recipeStillLogged.getAndSet(true)) Log.i("SCAM_CAPTURE", "FULLRES RECIPE camera " + physicalID
                            + " still result: " + FullResRecipeTags.read(result, recipePlan.recipe.tags) + ", " + recipePlan.requestTags(true).size()
                            + " still tags sent (P653: the sensor's full mode for the still alone)");
                    int frameCount = (int) (result.getFrameNumber() - baseFrameNumber[0]);
                    final ExposureLimits shotLimits = mExposureLimits;
                    if (shotLimits != null) shotLimits.observeManual(request, result);
                    if (flash != null) {
                        // P590: the frame's own flash state decides: FIRED - the flash shot (the ring's held frames go); else the
                        // frame leaves the shot and the normal shot of the press follows from the ring
                        final Integer flashState = result.get(CaptureResult.FLASH_STATE);
                        final Long flashNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        final Integer flashIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                        final boolean fired = FlashMode.fired(flashState);
                        Log.i("SCAM_CAPTURE", flash.describe() + ", frame flash state " + FlashMode.flashStateName(flashState)
                                + ", frame AE state " + FlashMode.aeStateName(result.get(CaptureResult.CONTROL_AE_STATE))
                                + String.format(java.util.Locale.ROOT, ", %.3f ms ISO %s", flashNs == null ? 0 : flashNs / 1e6, flashIso)
                                + ", 1 frame" + (fired ? "" : ": not FIRED, fallback to the normal shot"));
                        if (fired) releaseFlashRing();
                        else {
                            flashMissed[0] = true;
                            if (scamSequence != null) scamSequence.lost(request, "the flash did not fire");
                        }
                    }
                    if(scamSequence != null) {
                        // P27: a frame never costs the shot for its exposure. The Hybrid uses it in the role its measured
                        // exposure gives (HybridPlan.classify); SCAM HDR keeps it with its measured exposure (ScamBurst
                        // slots by measurement, else the Hybrid merges the burst).
                        if(hybridPlanHolder[0]!=null) {
                            ImageFrame.CaptureRole actual=hybridPlanHolder[0].classify(request,result);
                            if(actual==null)scamSequence.lost(request,"exposure outside every hybrid role");
                            else scamSequence.reclassify(request,actual);
                        } else if(stockPlan!=null)try{stockPlan.verify(request,result);}
                        catch(RuntimeException mismatch){Log.w("SCAM_CAPTURE",mismatch.getMessage()+"; kept with its measured exposure");}
                        scamSequence.completed(request, result);
                    }
                    if(scamCapture) ScamCaptureLog.result(request,result,scamZslShutterTimestamp,vendorScan);
                    Log.v("BurstCounter", "CaptureCompleted! FrameCount:" + frameCount);
                    com.scamera.app.util.ScameraDebugLog.frame(frameCount, result);
                    com.scamera.app.util.ScameraDebugLog.remosaicMetadata(
                            session.getDevice().getId() + "/" + physicalID,
                            mCameraCharacteristics, result);
                    rememberRawMetadata(result);
                    final Long frameTs = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    Log.d(TAG, "series frame at " + frameTs);
                    if (frameTs != null) {
                        // the frame's exposure product (s x ISO; ISO 100 when the result has none)
                        final Integer frameIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                        final int iso = frameIso != null ? frameIso : 100;
                        final Long frameNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        if (frameNs != null) {
                            exposureByFrame.put(frameTs, ExposureIndex.time2sec(frameNs) * iso);
                            Log.i(TAG, "HDR frame " + frameCount + " request=" + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                                    + "ns ISO" + request.get(CaptureRequest.SENSOR_SENSITIVITY) + " actual=" + frameNs + "ns ISO" + iso);
                        }
                    }
                    cameraEventsListener.frameCaptured(
                            new TimerFrameCountViewModel.FrameCntTime(hybridZsl?mPendingZslNormalFrames.size()+frameCount:frameCount,
                                    hybridZsl?mPendingZslNormalFrames.size()+maxFrameCount[0]:maxFrameCount[0], frametime));

                    if (seriesRunning && !seriesStarted) {
                        seriesStarted = true;
                        mImageSaver.processStart(mCameraCharacteristics, result, request, cameraRotation);
                    }
                    mCaptureResult = result; // the newest frame's metadata stands for the shot
                    if (maxFrameCount[0] != -1) ScameraApp.getGyro().seriesFrameStarted();
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    if (shotDone.get()) return;
                    Log.w("SCAM_CAPTURE", "capture failed: " + request.getTag() + " reason=" + failure.getReason()
                            + " imageCaptured=" + failure.wasImageCaptured() + " frame=" + failure.getFrameNumber());
                    if (scamSequence != null) scamSequence.lost(request, "HAL capture failure=" + failure.getReason());
                    if (request.getTag() instanceof ImageFrame.ScamCaptureTag) halLost.add(((ImageFrame.ScamCaptureTag) request.getTag()).index);
                }

                @Override
                public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
                    if (shotDone.get()) return;
                    if (recipeCompanionSurface != null && target == recipeCompanionSurface
                            && !FullResRecipePlan.lostBufferFailsStill(FullResRecipePlan.Stream.COMPANION)) {
                        recipeCompanionLost(frameNumber, "a still");
                        return;
                    }
                    Log.w("SCAM_CAPTURE", "buffer lost: " + request.getTag() + " frame=" + frameNumber + " raw=" + (target == scamRawSurface));
                    if (scamSequence != null && target == scamRawSurface)
                        scamSequence.lost(request, "RAW buffer lost frame=" + frameNumber);
                    if (target == scamRawSurface && request.getTag() instanceof ImageFrame.ScamCaptureTag)
                        halLost.add(((ImageFrame.ScamCaptureTag) request.getTag()).index);
                }

                @Override
                public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
                    if (shotDone.get()) return;
                    if (scamSequence != null) {
                        // P27: the frames that arrived (and the buffered N) still make the photo.
                        Log.w(TAG, "SHUTTER sequence aborted camera=" + physicalID + " sequence=" + sequenceId + ": merging the frames that arrived");
                        scamSequence.abandonOutstanding("sequence aborted");
                        onCaptureSequenceCompleted(session, sequenceId, -1);
                        return;
                    }
                    if (session != mCaptureSession) return;
                    if (!shotDone.compareAndSet(false, true)) return;
                    mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;mScamRouted=false;
                    mScamQueuedShots=0;mLiveRawRouter.clear();burst=false;
                    for(ImageFrame frame:mPendingZslNormalFrames)frame.close();mPendingZslNormalFrames=new ArrayList<>();
                    mHybridZslCapture=false;
                    Log.w(TAG, "SHUTTER sequence aborted camera=" + physicalID + " sequence=" + sequenceId);
                    cameraEventsListener.burstCompleted(null);
                    cameraEventsListener.processingFailed(Lang.t("Серия RAW прервана камерой. Повторите снимок.", "The camera interrupted the RAW burst. Take the shot again."));
                    unlockFocus();
                }

                @Override
                public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long lastFrameNumber) {
                    if (!shotDone.compareAndSet(false, true)) return;
                    mInFlightRescue = null;
                    // P605: the nubia remosaic 50 MP route proves itself on a shot the camera completes; a camera error in its shot
                    // turns it off on this firmware (one notice)
                    if (mFullResSession && mRecipePlan == null && NubiaFullRes.device()) {
                        final String shotWhy = NubiaRemosaicProbe.shot(physicalID, sequenceId >= 0, sequenceId >= 0 ? null : mInFlightRescueReason);
                        if (shotWhy != null) {
                            PixelPro10.setFullRes(false);
                            showError(shotWhy);
                        }
                    }
                    // P315: a series the rescue completed (sequenceId -1: watchdog, camera error, closed session) does not vote:
                    // the X8U's dead HAL in a sensor-mode session turned the flush off for its plain shots too
                    if (scamSequence != null && scamSequence.futureCount > 0 && sequenceId >= 0)
                        Log.i("SCAM_CAPTURE", flushStats.record(shotFlushed.get(), scamSequence.futureCount, halLost));
                    final int finalFrameCount = scamSequence != null ? scamSequence.futureCount
                            : (int) (lastFrameNumber - baseFrameNumber[0]) + 1;
                    Log.d("DefaultSaver", "series complete: " + finalFrameCount + " frames, last frame " + lastFrameNumber);
                    mMeasuredFrameCnt = finalFrameCount;
                    cameraEventsListener.burstCompleted(null);
                    burst = false;
                    final CameraMode shotMode = ScameraApp.getSettings().mode;
                    if (shotMode != CameraMode.UNLIMITED && shotMode != CameraMode.RAWVIDEO) {
                        awaitSeriesFramesThen(scamSequence, shotSaver, finalFrameCount, zslNormalCount, () -> {
                            ScameraApp.getGyro().seriesDone();
                            awaitZslCopy();
                            // P27: a ZSL frame whose copy did not finish is copied now; one that cannot be copied is dropped.
                            for (ImageFrame zsl : new ArrayList<>(mPendingZslNormalFrames)) {
                                try { zsl.materialize(); } catch (RuntimeException e) { Log.w("SCAM_CAPTURE", "ZSL frame " + zsl.timestamp + " not copied: " + e); }
                            }
                            final boolean routedScam = mScamRouted;
                            // The camera may be closed by now (a shot rescued from onPause / onDestroy): no handler, no preview.
                            final android.os.Handler unlockHandler = mBackgroundHandler;
                            if (!routedScam && unlockHandler != null) unlockHandler.post(CaptureController.this::unlockFocus);
                            // the SCAM route keeps its preview running (no unlockFocus): the shot's AF lock (lockFocus) is
                            // released here, so continuous AF scans again after the shot
                            if (routedScam && unlockHandler != null) unlockHandler.post(CaptureController.this::releaseShotAfLock);
                            try{
                            if (flashMissed[0]) {
                                // P590: the frame without the flash is not the photo: the normal shot of the press follows (finally)
                                shotSaver.discardFrames();
                                return;
                            }
                            if (scamSequence != null) {
                                java.util.List<ImageFrame> unmatched = scamSequence.bindAndValidate(shotSaver.snapshotFrames());
                                if (!unmatched.isEmpty()) shotSaver.removeFrames(unmatched);
                                String summary = scamSequence.droppedSummary();
                                if (!summary.isEmpty())
                                    Log.w("SCAM_CAPTURE", (shotHybridRoute ? "hybrid" : "SCAM HDR") + ": " + scamSequence.droppedCount() + " of "
                                            + scamSequence.futureCount + " post-shutter frames dropped, merging the rest: " + summary);
                                com.scamera.app.processing.ShotTimeline.capture("complete");
                                com.scamera.app.processing.ShotTimeline.capture("series_done"); // P236: as FULLRES
                                Log.i("SCAM_CAPTURE", "complete matched RAWs=" + shotSaver.bufferSize()
                                        + " futureRequests=" + scamSequence.futureCount + " bound=" + scamSequence.boundFutureCount()
                                        + " stockAePlan=" + (stockPlan!=null));
                                // Normal-back (no buffered N): the base metadata is the first N frame that arrived, else any frame.
                                if (scamNormalBack) {
                                    CaptureResult first = scamSequence.firstBoundResult(ImageFrame.CaptureRole.NORMAL);
                                    if (first == null) first = scamSequence.firstBoundResult(null);
                                    if (first instanceof TotalCaptureResult) nativeBaseResult[0] = (TotalCaptureResult) first;
                                }
                                if (normalBackLongRatio[0] > 0)
                                    for (ImageFrame frame : shotSaver.snapshotFrames())
                                        if (frame.getCaptureRole() == ImageFrame.CaptureRole.NORMAL) frame.syntheticLongRatio = normalBackLongRatio[0];
                            }
                            // P635: a recipe shot without a frame (every still failed) fails the profile: the normal session returns
                            if(shotSaver.bufferSize() == 0 && recipeShot != null
                                    && retryWithoutRecipe(session, "no still of the shot arrived (requests lost: " + halLost + ")")) return;
                            if(recipeShot != null) recipeVerdict(recipeShot);
                            if(shotSaver.bufferSize() == 0){
                                cameraEventsListener.processingFailed(Lang.t("Камера не передала RAW-кадры. Повторите снимок.", "The camera delivered no RAW frames. Take the shot again."));
                                return;
                            }
                            shotSaver.updateFrameCount(shotSaver.bufferSize());
                            if (shotSaver.bufferSize() != 0) {
                                boolean useZslBase = scamZslRequested && (hybridZsl || scamNormalBack);
                                CaptureResult baseMetadata = useZslBase ? nativeBaseResult[0] : mCaptureResult;
                                if (baseMetadata == null) {
                                    // P27: never without base metadata while a frame exists: the newest frame's own result.
                                    for (ImageFrame frame : shotSaver.snapshotFrames()) {
                                        CaptureResult own = frame.getMatchedCaptureMetadata();
                                        Long ts = own == null ? null : own.get(CaptureResult.SENSOR_TIMESTAMP);
                                        Long best = baseMetadata == null ? null : baseMetadata.get(CaptureResult.SENSOR_TIMESTAMP);
                                        if (own != null && (best == null || (ts != null && ts > best))) baseMetadata = own;
                                    }
                                    Log.w("SCAM_CAPTURE", "base metadata from the newest frame of the burst");
                                }
                                if (baseMetadata == null) throw new IllegalStateException(Lang.t("RAW: отсутствуют метаданные основного кадра", "RAW: base frame metadata missing"));
                                if (scamSequence != null && shotSaver.bufferSize() != scamSequence.presentCount())
                                    Log.w("SCAM_CAPTURE", "RAWs in the saver " + shotSaver.bufferSize() + ", in the series " + scamSequence.presentCount());
                                final ImageSaver saver = shotSaver;
                                final CameraCharacteristics shotCharacteristics = pressCharacteristics;
                                final CaptureResult shotResult = baseMetadata;
                                final CaptureRequest shotRequest = baseMetadata.getRequest() != null ? baseMetadata.getRequest() : mCaptureRequest;
                                final ArrayList<GyroBurst> shotShakiness = new ArrayList<>(burstShake);
                                final HashMap<Long, Double> shotExposures = exposureByFrame;
                                if (scamSequence != null) {
                                    saver.detachForQueue(shotHybridRoute);
                                    sScamPending.incrementAndGet();
                                    Log.i("SCAM_CAPTURE", "queued for processing pending=" + sScamPending.get());
                                    com.scamera.app.processing.ShotTimeline.capture("queued");
                                    final com.scamera.app.processing.ShotTimeline shotTimeline =
                                            com.scamera.app.processing.ShotTimeline.detach();
                                    SCAM_PROCESSING.execute(() -> {
                                        com.scamera.app.processing.ShotTimeline.attach(shotTimeline);
                                        com.scamera.app.processing.ShotTimeline.mark("proc");
                                        com.scamera.app.util.ThermalPolicy.processing(true); // P353: the viewfinder tones less meanwhile
                                        try {
                                            com.scamera.app.processing.MosaicBlockStore.setShotKey(shotMosaicKey, shotDeclaredBlock);
                                            saver.runRaw(shotCharacteristics, shotResult, shotRequest,
                                                    shotShakiness, shotRotation, shotExposures);
                                        } catch (Exception e) {
                                            Log.e(TAG, "runRaw:" + Log.getStackTraceString(e));
                                            cameraEventsListener.processingFailed(e.getLocalizedMessage());
                                        } finally {
                                            com.scamera.app.util.ThermalPolicy.processing(false);
                                            sScamPending.decrementAndGet();
                                            // P662: the next shot's worker starts now, its warm-up off that shot's path
                                            com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.afterShot(ScameraApp.getAppContext());
                                            // W1.0: the stage times of the shot in one line, after it was saved.
                                            com.scamera.app.processing.ShotTimeline.print();
                                        }
                                    });
                                } else {
                                    com.scamera.app.processing.MosaicBlockStore.setShotKey(shotMosaicKey, shotDeclaredBlock);
                                    saver.runRaw(shotCharacteristics, shotResult, shotRequest,
                                            shotShakiness, shotRotation, shotExposures);
                                }
                            }
                            } catch (Exception e){
                                Log.e(TAG, "runRaw:"+Log.getStackTraceString(e));
                                cameraEventsListener.processingFailed(e.getLocalizedMessage());
                                if (scamSequence != null) {
                                    shotSaver.discardFrames();
                                }
                            } finally {
                                // P30: no more frames for this shot's arena (it goes once the processing frees them)
                                if (shotArena != null) { shotArena.release(); if (mShotArena == shotArena) mShotArena = null; }
                                mScamRouted = false;
                                // P590: a flash that did not fire keeps the ring closed (its frames are the press's) for the normal shot
                                final boolean flashRetry = flashMissed[0];
                                mNativeRawPslCapture=false;mZslCapturing=flashRetry;mLiveRawRouter.clear();
                                if (hybridZslRequested) {
                                    mHybridZslCapture = false;
                                    mZslCapturing = flashRetry;
                                    mPendingZslNormalFrames = new ArrayList<>();
                                }
                                // Preview never stopped: no AF re-trigger or 3A reset, the
                                // shutter is free as soon as the tail RAWs are in memory.
                                final android.os.Handler finishHandler = mBackgroundHandler;
                                if (flashRetry && finishHandler != null) finishHandler.post(CaptureController.this::flashFallbackShot);
                                else if (routedScam && finishHandler != null) finishHandler.post(CaptureController.this::finishScamShot);
                            }
                        });
                    }
                }
            };
            burst = true;
            if (hybridZslRequested) {
                // SCAM uses capture-start timestamps to reject any late
                // preview RAWs; other hybrid paths retain their flushed routing.
                mHybridZslCapture = true;
            }
            if (!mScamRouted) mCaptureSession.stopRepeating();
            if(scamCapture) {
                Log.i("SCAM_CAPTURE","submit zslNormals="+mPendingZslNormalFrames.size()
                        +" futureRequests="+mShotRequests.size()+" hybrid="+hybridZsl
                        +" stockAePlan="+(stockPlan!=null)+" previewRunning="+mScamRouted);
                for(int i=0;i<mShotRequests.size();i++) ScamCaptureLog.request(mShotRequests.get(i),i);
            }
            if (!scamZslRequested) mCaptureSession.abortCaptures();
            // SCAM keeps the preview repeating; its in-flight requests (~12 frames on
            // this HAL) otherwise sit ahead of the bracket. Flushing them brings the
            // first bracket exposure ~0.25 s closer to the press; the preview is
            // restarted right behind the burst.
            final boolean flushWanted = mScamRouted && scamSequence != null && PreferenceKeys.isScamFastCapture()
                    && !mShotRequests.isEmpty() && (mStockObserverReady || !PreferenceKeys.useStockBracketPlanner());
            // P27 (M6): a camera whose HAL lost the first requests after the flush twice in a row is not flushed any more.
            // P54b: nor a camera under the forced stabilisation (the flush restarts the HAL pipeline; the stock app never flushes).
            final boolean forcedNoFlush = mForcedStab && ForcedStabilization.noFlush();
            // P497 (owner, 2026-11-04: «у нас сильнее задержка после съёмки чем на Арк камере и камера уходит сильнее», «должно
            // работать везде»): no flush before the series on any phone - the series queues behind the preview, as the stock apps'
            // and the reference's. A HAL restarts its pipeline after a flush (vivo X200 Ultra: the first series frame +385 ms after
            // the submit, the preview stopped 287 ms). scam_dev series_flush 1: flushed as before
            final boolean noFlush = !PreferenceKeys.scamDevSwitch("series_flush", false);
            final boolean flushQueue = ForcedStabilization.flush(flushWanted && !flushStats.skipFlush(), forcedNoFlush || noFlush);
            if (flushWanted && !flushQueue) Log.i("SCAM_CAPTURE", "HAL queue not flushed: camera " + flushStats.camera
                    + (forcedNoFlush ? " holds the forced stabilisation (P54b)" : noFlush ? ": the series queues behind the preview (P497)"
                    : " lost the first requests after a flush (FlushLossStats)"));
            shotFlushed.set(flushQueue);
            mLastShotFlushed = flushQueue;
            // P44: preview frames right after the flush, ahead of the series (scam_dev "preview_lead"; one by default).
            final int leadFrames = PreviewContinuity.leadFrames(
                    PreferenceKeys.scamDevNumber("preview_lead", PreviewContinuity.DEFAULT_LEAD), flushQueue);
            mLastShotLead = leadFrames;
            sTimelineSubmitNs = android.os.SystemClock.elapsedRealtimeNanos();
            {
                final String previousGap = mPreviewGap.markShot("camera=" + physicalID + " flush=" + flushQueue + " lead=" + leadFrames
                                + " rearm=" + previewRearmMode(flushQueue, leadFrames)
                                + " aeRestore=" + (mScamRouted ? Math.round(PreferenceKeys.scamDevNumber("ae_restore", 1f)) : -1),
                        sTimelineSubmitNs, mShotRequests.size(), isGyroClockComparable());
                if (previousGap != null) Log.i(PreviewGapMeter.TAG, previousGap + " (closed early by the next shot)");
            }
            com.scamera.app.processing.ShotTimeline.capture("submit");
            // P371 (speed, worker_prespawn): the merge worker starts now for this shot's arena (on a thread of its own) and warms up
            // during the capture; only with no shot waiting for its merge (the worker of that one would compete with it), up to 16 MP
            // P662: above 16 MP too when the memory allows it (the decision logged); a worker kept ready for the ring is kept
            if (scamHybridShot && shotArena != null && sScamPending.get() == 0 && mImageReaderRaw != null
                    && com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.standbyAllowedNow(
                            (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight(), "the shutter"))
                com.scamera.app.processing.opengl.postpipeline.ScamNeuralClient.prespawn(ScameraApp.getAppContext(), shotArena.id());
            mStabTrace.markShot("submit requests=" + mShotRequests.size() + " flush=" + flushQueue + " previewKept=" + mScamRouted);
            if (!mShotRequests.isEmpty()) {
                mStabTrace.seriesRequest(mShotRequests.get(0), 0);
                if (mShotRequests.size() > 1) mStabTrace.seriesRequest(mShotRequests.get(mShotRequests.size() - 1), mShotRequests.size() - 1);
            }
            if (flushQueue) {
                long t0 = android.os.SystemClock.elapsedRealtime();
                // Without stopping the repeating preview first, the HAL sometimes
                // returns from flush at once without dropping anything (+0.17 s).
                flushDevice(mCaptureSession);
                Log.i("SCAM_CAPTURE", "HAL queue flushed in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
                mStabTrace.event("HAL queue flushed in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
                // P44: the flush also cleared the repeating preview; lead frames go into the request queue ahead of the
                // series (capture() requests are taken in order, the series follows them without a preview frame between).
                if (leadFrames > 0) queuePreviewLead(leadFrames);
            }
            if(mShotRequests.isEmpty() && hybridZsl) {
                startZslCopy(mPendingZslNormalFrames);
                mShotCallback.onCaptureSequenceCompleted(mCaptureSession,0,-1);return;
            }
            // P27: from here the shot completes with what arrived whatever happens: a camera close / error, an abort, a
            // submit failure or a HAL that never reports the series (the watchdog; 65 s HAL stalls were seen).
            if (scamSequence != null) {
                final CameraCaptureSession.CaptureCallback shotCallback = mShotCallback;
                final CameraCaptureSession shotSession = mCaptureSession;
                final Runnable rescue = () -> {
                    if (shotDone.get()) return;
                    scamSequence.abandonOutstanding("camera stopped delivering the series");
                    for (ImageFrame zsl : new ArrayList<>(mPendingZslNormalFrames)) {
                        try { zsl.materialize(); } catch (RuntimeException e) { Log.w("SCAM_CAPTURE", "ZSL frame " + zsl.timestamp + " not copied: " + e); }
                    }
                    shotCallback.onCaptureSequenceCompleted(shotSession, -1, -1);
                };
                mInFlightRescue = rescue;
                long plannedNs = 0;
                for (CaptureRequest request : mShotRequests) {
                    Long t = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
                    plannedNs += t == null || t <= 0 ? 33_000_000L : t;
                }
                final long watchdogMs = 4000 + 3 * plannedNs / 1_000_000;
                if (mBackgroundHandler != null) mBackgroundHandler.postDelayed(() -> {
                    if (shotDone.get() || mInFlightRescue != rescue) return;
                    Log.w("SCAM_CAPTURE", "series not completed after " + watchdogMs + " ms: aborting it, merging the frames that arrived");
                    try { if (shotSession != null) shotSession.abortCaptures(); } catch (CameraAccessException | RuntimeException ignored) {}
                    rescueInFlightShot("watchdog " + watchdogMs + " ms");
                    // abortCaptures also dropped the repeating preview, which the SCAM route keeps running (no unlockFocus
                    // after the shot): bring it back while this session is still the camera's.
                    if (isCameraResumed && mCaptureSession == shotSession) rebuildPreviewBuilder();
                }, watchdogMs);
            }
                switch (ScameraApp.getSettings().mode) {
                    case UNLIMITED:
                        mCaptureSession.setRepeatingBurst(mShotRequests, mShotCallback, mBackgroundHandler);
                        break;
                    case RAWVIDEO:
                        mCaptureSession.setRepeatingRequest(mShotRequests.get(0), mShotCallback, mBackgroundHandler);
                        break;
                    case NIGHT:
                    case PHOTO:
                    case MOTION:
                        mCaptureSession.captureBurst(mShotRequests, mShotCallback, mBackgroundHandler);
                        if (mScamRouted && flash == null) queueScamAeRestore(); // P590: the AE runs on after a flash frame
                        if (flash != null) Log.i("SCAM_CAPTURE", "flash shot: frame submitted (" + (flash.manual() ? "AE off, FLASH_MODE_SINGLE"
                                : "AE mode " + FlashMode.stillAeMode(flash.mode)) + ", CONTROL_CAPTURE_INTENT_STILL_CAPTURE)");
                        if (flushQueue) {
                            mCaptureSession.setRepeatingRequest(
                                    mRepeatingRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                            mStabTrace.event("preview repeating restarted behind the series");
                        }
                        break;
                }
                // W1.9: the gyro's burst-rate registration, now that the series is submitted (its first exposure starts
                // 109 ms or more after the flush); before, it delayed the submit by ~39 ms.
                ScameraApp.getGyro().startBurstRegistration();
                startZslCopy(mPendingZslNormalFrames);
        } catch (CameraAccessException | RuntimeException e) {
            // P27: the series failed to submit after the shot was set up (session closed, device disconnecting): the
            // buffered N frames still make the photo.
            if (mInFlightRescue != null) {
                Log.w("SCAM_CAPTURE", "series submit failed (" + e + "): merging the buffered frames");
                rescueInFlightShot("submit failed: " + e.getMessage());
                return;
            }
            mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;mScamRingFrozen=false;
            clearFlashShot(); // P590
            mScamRouted=false;mScamQueuedShots=0;mLiveRawRouter.clear();
            if (mShotArena != null) { mShotArena.release(); mShotArena = null; }
            for(ImageFrame frame:mPendingZslNormalFrames)frame.close();mPendingZslNormalFrames=new ArrayList<>();
                mHybridZslCapture=false;burst=false;
            cameraEventsListener.burstCompleted(null);
            cameraEventsListener.processingFailed(e.getMessage());
            unlockFocus();
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * Some physical cameras publish a sensitivity range narrower than the sensor
     * runs: the vivo X200 Ultra 2.4x tele declares ISO 400..800 while its own AE
     * runs at ISO 50 in daylight and 6400 at night. Manual bracket frames were
     * clamped to 800, so at night the long frame could not reach N and the shot
     * failed. The range is widened to what the camera itself has delivered.
     */
    /**
     * Drops the in-flight preview requests ahead of the bracket. The idle
     * notification of the previous flush arrives after the preview is restarted,
     * so Camera2 believed the running device idle and skipped every second flush
     * (session: "already aborting"; device: early return), and the bracket waited
     * behind ~12 queued preview frames (+0.17 s). The stale idle flag is cleared
     * under the device lock first; abortCaptures() is the fallback.
     */
    private static void flushDevice(CameraCaptureSession session) throws CameraAccessException {
        try {
            java.lang.reflect.Field field = session.getClass().getDeclaredField("mDeviceImpl");
            field.setAccessible(true);
            Object device = field.get(session);
            Class<?> type = device.getClass();
            java.lang.reflect.Field lockField = type.getDeclaredField("mInterfaceLock");
            java.lang.reflect.Field idleField = type.getDeclaredField("mIdle");
            lockField.setAccessible(true);
            idleField.setAccessible(true);
            java.lang.reflect.Method flush = type.getMethod("flush");
            synchronized (lockField.get(device)) {
                idleField.setBoolean(device, false);
                flush.invoke(device);
            }
            return;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof CameraAccessException) throw (CameraAccessException) e.getCause();
            Log.w("SCAM_CAPTURE", "device flush failed, session abort: " + e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "device flush unavailable, session abort: " + e);
        }
        session.stopRepeating();
        session.abortCaptures();
    }

    private void widenSensitivityRange(int iso) {
        CameraCharacteristics c = mCameraCharacteristics;
        if (c == null || iso <= 0) return;
        android.util.Range<Integer> r = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (r == null || (iso >= r.getLower() && iso <= r.getUpper())) return;
        android.util.Range<Integer> wider = new android.util.Range<>(Math.min(r.getLower(), iso), Math.max(r.getUpper(), iso));
        try {
            com.scamera.app.api.CameraReflectionApi.set(c, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, wider);
            Log.i("SCAM_CAPTURE", "sensitivity range camera=" + physicalID + " " + r + " -> " + wider + " (observed ISO " + iso + ")");
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot widen sensitivity range: " + e);
        }
    }

    /** Copies deferred ZSL frames out of the RAW reader while the bracket exposes. */
    private void startZslCopy(List<ImageFrame> frames) {
        final List<ImageFrame> pending = new ArrayList<>(frames);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        mZslCopyDone = done;
        if (pending.isEmpty()) { done.countDown(); return; }
        // Reads from camera buffers are slow per core (~2 GB/s, uncached) and the
        // previous shot's processing competes for them: copy on four threads.
        final int workers = Math.min(4, pending.size());
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(pending.size());
        final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger(workers);
        final long t0 = android.os.SystemClock.elapsedRealtime();
        for (int w = 0; w < workers; w++) {
            Thread copy = new Thread(() -> {
                try {
                    // Newest first: the reference frame is ready earliest.
                    for (int i = next.decrementAndGet(); i >= 0; i = next.decrementAndGet()) pending.get(i).materialize();
                } catch (RuntimeException e) {
                    Log.e(TAG, "ZSL copy failed: " + Log.getStackTraceString(e));
                } finally {
                    if (running.decrementAndGet() == 0) {
                        done.countDown();
                        Log.i("SCAM_CAPTURE", "ZSL copy " + pending.size() + " RAWs in "
                                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms (after submit)");
                    }
                }
            }, "zsl-copy-" + w);
            copy.setPriority(Thread.MAX_PRIORITY);
            copy.start();
        }
    }

    private void awaitZslCopy() {
        java.util.concurrent.CountDownLatch done = mZslCopyDone;
        if (done == null) return;
        try {
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) Log.w(TAG, "ZSL copy timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Drops every request of the running session the camera has not finished (a closing session is left alone). */
    public void abortCaptures() {
        final CameraCaptureSession session = mCaptureSession;
        if (session == null) return;
        try {
            session.abortCaptures();
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /** The viewfinder's AE and AF modes back to the settings' (the regions as the request has them), sent at once. */
    public void restore3A() {
        applyAeMode(mPreviewRequestBuilder, PreferenceKeys.getAeMode());
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b != null) {
            // the regions are set again as they stand, so the HAL takes the AF mode with them
            b.set(CaptureRequest.CONTROL_AF_REGIONS, b.get(CONTROL_AF_REGIONS));
            b.set(CaptureRequest.CONTROL_AE_REGIONS, b.get(CONTROL_AE_REGIONS));
            b.set(CaptureRequest.CONTROL_AF_MODE, PreferenceKeys.getAfMode());
        }
        rebuildPreviewBuilder();
    }

    /** The flash / torch setting on the viewfinder at once (vivo: a new session for the torch, torchNeedsSession). */
    public void applyFlashSetting(int aeMode) {
        applyAeMode(mPreviewRequestBuilder, aeMode);
        rebuildPreviewBuilder();
        mTorchLogFrames = aeMode == 0 && mFlashSupported ? 15 : 0;
        if (aeMode == 0 && mFlashSupported && torchNeedsSession()) restartSessionForTorch(0);
    }

    /**
     * Owner, 2026-10-19 (X200 Ultra): the torch lit when the session started with it on, but switched on in the running
     * repeating request it stayed dark (off worked). On vivo the session is created again with the torch in its first
     * request - the path that lights it.
     */
    static boolean torchNeedsSession() {
        return ScamPreview.supported();
    }

    private void restartSessionForTorch(int attempt) {
        final Handler handler = mBackgroundHandler;
        if (handler == null) return;
        final int generation = mSessionGeneration.get();
        handler.postDelayed(() -> {
            if (!isCameraResumed || mCaptureSession == null || mCameraDevice == null || generation != mSessionGeneration.get()
                    || PreferenceKeys.getAeMode() != 0) return;
            if (shotInFlight()) { // never under a shot: after it
                if (attempt < 20) restartSessionForTorch(attempt + 1);
                return;
            }
            Log.i("SCAM_CAPTURE", "torch on: camera " + physicalID + " session created again (vivo lights the torch from the session's start)");
            mCaptureSession.close();
            startSession(false);
        }, attempt == 0 ? 0 : 500);
    }

    /** The preview results still to log after the torch was switched on (FLASH_MODE / FLASH_STATE as the HAL reports them). */
    private volatile int mTorchLogFrames;

    /** The fps setting on the viewfinder at once: the module's own RAW rate wins (P266), else the lens's range (>= 20 fps, P224). */
    public void applyFpsSetting() {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b == null) return;
        ScameraApp.getSettings().previewFps = PreferenceKeys.getFpsMode();
        final Range<Integer> moduleFps = moduleFps();
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, moduleFps != null ? moduleFps : ViewfinderFps.raise(videoFpsRange(), mRawMaxFps));
        if (mDcgRequestKeys) Dcg14Mode.applyRequest(b, mCameraCharacteristics); // P230: DCG runs 60 fps only
        rebuildPreviewBuilder();
    }

    /** The AE metering setting on the viewfinder at once. */
    public void applyMeteringSetting() {
        final CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b == null) return;
        applyAeRegions(b);
        rebuildPreviewBuilder();
    }

    /** The AE metering setting's regions on {@code builder} (-1: the template's own), where the lens meters by regions at all. */
    private void applyAeRegions(CaptureRequest.Builder builder) {
        final int mode = PreferenceKeys.getAeMeteringStd();
        Log.d(TAG, "AE metering mode " + mode);
        final MeteringRectangle[] regions = mode == -1 ? mDefaultAeRegions : meteringRegions(mode);
        final Integer maxRegions = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if (maxRegions == null || maxRegions <= 0) return;
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
        if (builder == mPreviewRequestBuilder) mPreviewMeteringAE = regions;
    }

    /** A region of {@code fraction} of each side of a {@code width} x {@code height} array, centred (sides truncated). */
    static MeteringRectangle centredRegion(int width, int height, double fraction, int weight) {
        final int w = (int) (width * fraction);
        final int h = (int) (height * fraction);
        return new MeteringRectangle((width - w) / 2, (height - h) / 2, w, h, weight);
    }

    /**
     * The AE regions of a metering mode over the active array: 0 centre-weighted (three nested regions 70 / 45 / 20 % weighted
     * 200 / 300 / 500 where the lens takes three, else one 60 % region), 1 the whole frame, 2 a spot of ~2.5 % of the area
     * (15.8 % of each side); null for any other mode or an unknown array.
     */
    private MeteringRectangle[] meteringRegions(int mode) {
        final Rect active = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null) return null;
        final int w = active.width(), h = active.height();
        final int top = MeteringRectangle.METERING_WEIGHT_MAX;
        if (mode == 1) return new MeteringRectangle[]{new MeteringRectangle(0, 0, w, h, top)};
        if (mode == 2) return new MeteringRectangle[]{centredRegion(w, h, 0.158, top)};
        if (mode != 0) return null;
        final Integer regions = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if (regions == null || regions < 3) return new MeteringRectangle[]{centredRegion(w, h, 0.60, top)};
        return new MeteringRectangle[]{centredRegion(w, h, 0.70, 200), centredRegion(w, h, 0.45, 300), centredRegion(w, h, 0.20, 500)};
    }

    /** The viewfinder's AE / flash mode as the setting says (not sent: the caller's next request carries it). */
    public void restorePreviewAe() {
        applyAeMode(mPreviewRequestBuilder, PreferenceKeys.getAeMode());
    }

    /** AE and flash mode of {@code requestBuilder} for the flash setting {@code aeMode} (0 torch / off, 1 auto, 2 on, 3 ...). */
    private void applyAeMode(CaptureRequest.Builder requestBuilder, int aeMode) {
        if (requestBuilder == null) return;
        // P590: Авто / Вкл. run the preview in ON_AUTO_FLASH / ON_ALWAYS_FLASH (the AE reports FLASH_REQUIRED, the still request
        // fires the flash); the torch is FLASH_MODE_TORCH under ON (pref 0 is never AE off); a lens without a flash, the video and
        // the routes without the flash shot (ISZ / DCG / DAG / FULLRES) run ON
        final boolean video = ScameraApp.getSettings().mode == CameraMode.VIDEO;
        final boolean flashShots = mFlashSupported && flashRouteBlock(false) == null;
        int ae = FlashMode.previewAeMode(aeMode, flashShots, video);
        final int[] listedModes = mCameraCharacteristics == null ? null : mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (listedModes != null && !listed(listedModes, ae)) ae = CONTROL_AE_MODE_ON;
        requestBuilder.set(CONTROL_AE_MODE, ae);
        requestBuilder.set(CaptureRequest.FLASH_MODE, FlashMode.previewFlashMode(aeMode, mFlashSupported));
        if (requestBuilder == mPreviewRequestBuilder) mPreviewAEMode = ae; // a tap's restore keeps it (TouchFocus)
    }

    /** The shutter's 3A wait starts now. */
    private void startThreeAWait() {
        mThreeAWaitStart = SystemClock.elapsedRealtime();
    }

    /** The shutter's 3A wait ran past THREE_A_WAIT_MS. */
    private boolean threeAWaitOver() {
        return SystemClock.elapsedRealtime() - mThreeAWaitStart > THREE_A_WAIT_MS;
    }

    /** The endless series ends: its frames are processed, the session starts again for the viewfinder. */
    public void stopEndlessSeries() {
        seriesRunning = false;
        mBackgroundHandler.post(() -> mImageSaver.finishShot()); // the saver of the camera at that moment
        abortCaptures();
        startSession(false);
        seriesStarted = false;
    }

    public void startEndlessSeries() {
        seriesRunning = true;
        takePicture();
    }

    /** Stops the clip being recorded. */
    public void stopVideoClip() {
        recordingVideo = false;
        stopRecordingVideo();
    }

    /** A recording session (the viewfinder and the recorder) replaces the photo one. */
    public void startVideoClip() {
        recordingVideo = true;
        startSession(false);
    }

    /** The camcorder profile for the video resolution setting: the asked quality first, then the others, else QUALITY_HIGH. */
    private static CamcorderProfile resolveVideoProfile(int cameraId, String resolution) {
        final int uhd = CamcorderProfile.QUALITY_2160P, fhd = CamcorderProfile.QUALITY_1080P, hd = CamcorderProfile.QUALITY_720P;
        final int[] order = "3840x2160".equals(resolution) ? new int[]{uhd, fhd, hd}
                : "1280x720".equals(resolution) ? new int[]{hd, fhd, uhd} : new int[]{fhd, hd, uhd};
        for (int quality : order) if (CamcorderProfile.hasProfile(cameraId, quality)) return CamcorderProfile.get(cameraId, quality);
        return CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH);
    }

    /**
     * Prepares the recorder for a clip of the open camera (made here on first use: a photo session never pays for one): the
     * camcorder profile of the video resolution setting, AAC sound, H.264 picture, a DCIM/Camera/VID_<time>.mp4 file.
     */
    private void setUpMediaRecorder() {
        if (videoRecorder == null) videoRecorder = new MediaRecorder();
        final MediaRecorder rec = videoRecorder;
        rec.reset();
        rec.setAudioSource(MediaRecorder.AudioSource.MIC);
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        int legacyId;
        try {
            legacyId = Integer.parseInt(ScameraApp.getSettings().cameraId.split("-")[0]);
        } catch (NumberFormatException e) {
            legacyId = 0;
        }
        final CamcorderProfile profile = resolveVideoProfile(legacyId, PreferenceKeys.getVideoResolution());
        rec.setVideoFrameRate(profile.videoFrameRate);
        rec.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        rec.setVideoEncodingBitRate(profile.videoBitRate);
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        rec.setAudioEncodingBitRate(profile.audioBitRate);
        rec.setAudioSamplingRate(profile.audioSampleRate);
        rec.setOnInfoListener(this::recorderInfo);
        rec.setOrientationHint(ScameraApp.getGravity().getCameraRotation(mSensorOrientation));
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final File folder = new File(Environment.getExternalStorageDirectory() + "//DCIM//Camera//");
        mVideoFile = new File(folder.getAbsolutePath(), "VID_" + stamp + ".mp4");
        try {
            mVideoFile.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
        rec.setOutputFile(mVideoFile.getAbsolutePath());
        try {
            rec.prepare();
            Log.d(TAG, "video recorder ready: " + mVideoFile.getName());
        } catch (Exception e) {
            Log.d(TAG, "video recorder not ready: " + e);
        }
    }

    /** Ends the clip (a recorder that cannot stop leaves no half file) and goes back to the viewfinder's session. */
    private void stopRecordingVideo() {
        recordingVideo = false;
        final MediaRecorder rec = videoRecorder;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception stopFailure) {
                Log.d(TAG, "Failed to stop recording " + Log.getStackTraceString(stopFailure));
                // P214f: one message on the camera screen's card (two English-only system toasts before)
                final boolean removed = mVideoFile != null && mVideoFile.delete();
                showError(Lang.t("Запись видео не остановилась" + (removed ? "; файл удалён" : ""),
                        "The recording did not stop" + (removed ? "; the video file was removed" : "")));
            }
            rec.reset();
            if (mVideoFile != null) cameraEventsListener.scanMedia(Uri.fromFile(mVideoFile));
        }
        startSession(false);
    }

    /** The recorder's notice: a clip at its longest duration ends the recording. */
    private void recorderInfo(MediaRecorder recorder, int what, int extra) {
        if (what != MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) return;
        Log.v(TAG, "the clip reached its longest duration: recording stops");
        stopRecordingVideo();
    }

    /**
     * The camera screen is in the foreground: the camera opens as soon as the viewfinder's texture exists (now, when the GL
     * viewfinder already has one, else from the texture listener).
     */
    public void screenResumed() {
        isCameraResumed = true;
        processExecutor.execute(() -> {
            if (mViewfinder == null) mViewfinder = new GLPreview(activity);
            // a live texture (a restart without a pause, or the one onSurfaceCreated made after the pause): open against it
            if (mViewfinder.isAvailable()) {
                openForViewfinder();
                return;
            }
            mViewfinder.setSurfaceTextureListener(mSurfaceTextureListener);
            // the availability callback comes through the main thread: it may have fired between the check above and the
            // listener - checked again, so the camera never waits for an event that already happened
            if (mViewfinder.isAvailable()) openForViewfinder();
        });
    }

    private void openForViewfinder() {
        final CameraCharacteristics chars = requestedCharacteristics();
        if (chars == null) return;
        final Size openSize = getPreviewOutputSize(chars, ScameraApp.getSettings().mode);
        openCamera(openSize.getWidth(), openSize.getHeight());
    }

    /** Sizes by pixel count, smallest first (long products: no overflow). */
    static final Comparator<Size> BY_AREA =
            (a, b) -> Long.compare((long) a.getWidth() * a.getHeight(), (long) b.getWidth() * b.getHeight());

    /**
     * Returns true if the optical image stabilization modes indicate OIS hardware support.
     * Android spec: LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION contains supported modes.
     * Qualcomm HALs typically return [0, 1] (OFF, ON); MediaTek/Vivo HALs often return [1] (ON only).
     * Any array containing LENS_OPTICAL_STABILIZATION_MODE_ON (1) supports OIS.
     */
    public static boolean isOisSupported(int[] modes) {
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOisSupported(CameraCharacteristics chars) {
        if (chars == null) return false;
        return isOisSupported(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
    }

    /**
     * Whether the lens is optically stabilised, also where Camera2 does not say so: the OPPO Find X7 / X8 / X9 Ultra list OIS
     * OFF only on every module (SCAM TEST reports, 2026-10-11) while every module has OIS (owner; qcom,ois nodes in the X7U
     * device tree) that the HAL drives itself. Only for decisions about the frames; OIS modes are still sent only where listed.
     */
    static boolean hasOisHardware(CameraCharacteristics chars) {
        return isOisSupported(chars) || halHiddenOis(Build.MANUFACTURER, Build.MODEL);
    }

    static boolean halHiddenOis(String manufacturer, String model) {
        if (model == null) return false;
        final String m = model.toUpperCase(java.util.Locale.ROOT);
        // OnePlus 15 (SCAM TEST 2026-10-11): OIS OFF only in the characteristics, ON in the main / tele templates (0, 2, 4)
        if ("OnePlus".equalsIgnoreCase(manufacturer)) return m.equals("CPH2747");
        if (!"OPPO".equalsIgnoreCase(manufacturer)) return false;
        return m.equals("PHY110") || m.equals("PKJ110") || m.equals("CPH2841");
    }

    /**
     * Selects the best video/preview stabilization mode supported by this camera and Android version.
     * In video mode: prefers CONTROL_VIDEO_STABILIZATION_MODE_ON.
     * In photo mode: if EIS Photo is enabled, prefers CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
     * (Android 13+, API 33) to stabilize the viewfinder without affecting still capture framing.
     * On cameras lacking OIS (e.g. UltraWide/Front), falls back to CONTROL_VIDEO_STABILIZATION_MODE_ON.
     */
    public static int getPreferredVideoStabilizationMode(CameraCharacteristics chars, boolean isRecordingVideo, boolean eisPhotoOn) {
        if (!eisPhotoOn || chars == null) {
            return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
        int[] vsModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (vsModes == null || vsModes.length == 0) {
            return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
        boolean hasPreviewStab = false;
        boolean hasOn = false;
        for (int m : vsModes) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) {
                hasPreviewStab = true;
            }
            if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                hasOn = true;
            }
        }
        if (isRecordingVideo) {
            if (hasOn) return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            if (hasPreviewStab) return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
        } else if (vivoBrand()) {
            // Photo mode: P54d (vivo X200 FE / X300 Ultra) only. Elsewhere the photo preview keeps no stabilisation mode, as
            // before P54d (Xiaomi 13 Ultra / OPPO Find X9 Ultra: mode 2 next to the RAW stream stalled or killed the HAL).
            if (hasPreviewStab) {
                return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
            }
            if (!isOisSupported(chars) && hasOn) {
                return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            }
        }
        return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
    }

    /** vivo / iQOO: the brands P54d's photo-mode stabilisation (PREVIEW_STABILIZATION, session parameter) was made for. */
    static boolean vivoBrand() {
        return "vivo".equalsIgnoreCase(Build.BRAND) || "iqoo".equalsIgnoreCase(Build.BRAND) || "vivo".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private void applyVideoStabilization(CaptureRequest.Builder builder) {
        int vsMode = getPreferredVideoStabilizationMode(mCameraCharacteristics, recordingVideo, PreferenceKeys.isEisPhotoOn());
        if (vsMode != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vsMode);
        }
    }

    /**
     * P316 (settings audit): the tripod state «Оптическая стабилизация» Авто last built the preview's OIS for (applyOisMode), null
     * when the preview's OIS does not follow it (another choice, no OIS).
     */
    private volatile Boolean mOisAutoTripod;
    private volatile boolean mOisReapplyPosted;

    /**
     * P316 (settings audit): Auto turns OIS off on a tripod - also when the phone is put on one (or picked up) after the session
     * started (Gyro refreshes its tripod state every 600 samples): the preview's request takes the new state. It was applied once
     * per session, and the series copies the preview's OIS (the lens must not move between the ring and the series), so a tripod
     * found later never turned OIS off while the HUD showed it off. Not under forced stabilisation (P54b: OIS stays on), not while
     * a shot is pending or its preview re-arm waits (the next preview result tries again).
     */
    private void maybeReapplyAutoOis(CameraCaptureSession session) {
        final Boolean built = mOisAutoTripod;
        if (built == null || oisMode != 1 || mForcedStab || mOisReapplyPosted || session == null || session != mCaptureSession) return;
        if ((ScameraApp.getGyro() != null && ScameraApp.getGyro().getTripod()) == built) return;
        final android.os.Handler handler = mBackgroundHandler;
        if (handler == null) return;
        mOisReapplyPosted = true;
        handler.post(() -> {
            mOisReapplyPosted = false;
            synchronized (mPreviewStateLock) {
                if (!isCameraResumed || session != mCaptureSession || mPreviewRequestBuilder == null || recordingVideo
                        || mOisAutoTripod == null || oisMode != 1 || mForcedStab || mRearmPending || nextShotPending()) return;
                applyOisMode(mPreviewRequestBuilder, false);
                try {
                    session.setRepeatingRequest(mRepeatingRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                    Log.i("SCAM_CAPTURE", "OIS Auto: " + (Boolean.TRUE.equals(mOisAutoTripod) ? "tripod, OIS off" : "hand-held, OIS on")
                            + " on the preview");
                } catch (CameraAccessException | RuntimeException e) {
                    Log.w("SCAM_CAPTURE", "OIS Auto: the preview could not take the tripod state (" + e + ")");
                }
            }
        });
    }

    /** P54b: OIS can be forced on: the camera has OIS and the user did not choose «OIS off» (oisMode 2). */
    private boolean oisForceable() {
        int[] modes = mCameraCharacteristics == null ? null
                : mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        return oisRequestable(modes) && oisMode != 2;
    }

    /**
     * P95 (owner, 2026-10-11: «нужен рабочий OIS на оппо, форсировать его работу на всех смартах»): the OIS mode is sent on
     * every camera, also where Camera2 lists OFF only. The OPPO Find X8 / X9 Ultra HALs list OFF only yet their own preview and
     * still templates carry ON on the main and tele modules and the results report ON (SCAM TEST, 2026-10-11): the HAL takes
     * the key, it only hides it from the list. scam_dev "ois_force 0" goes back to the listed modes only.
     */
    private boolean oisRequestable(int[] modes) {
        if (isOisSupported(modes)) return true;
        if (!PreferenceKeys.scamDevSwitch("ois_force", true)) return false;
        String id = physicalID;
        if (id != null && sOisForcedLogged.add(id))
            Log.i("SCAM_CAPTURE", "OIS forced ON on camera " + id + " (Camera2 lists " + java.util.Arrays.toString(modes) + ")");
        return true;
    }

    private static final java.util.Set<String> sOisForcedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Dynamically applies Optical Image Stabilization (OIS) configuration to a CaptureRequest.
     * Respects user preference (Default/Smart/Off) and physical hardware limitations.
     *
     * @param builder        the builder for which to configure OIS
     * @param isStillCapture true if configuring a still capture request, false for preview stream
     */
    private void applyOisMode(CaptureRequest.Builder builder, boolean isStillCapture) {
        if (!isStillCapture) mOisAutoTripod = null;
        int[] stabilizationModes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (oisRequestable(stabilizationModes)) {
            int oisMode = this.oisMode;
            if (oisMode == 2) {
                // Always Off
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            } else if (oisMode == 1) {
                // Smart Auto: off on a tripod (P316: the retired Unlimited mode is read as Photo, CameraMode.valueOf). The preview
                // follows the tripod state after the session started too (maybeReapplyAutoOis)
                boolean isTripod = ScameraApp.getGyro() != null && ScameraApp.getGyro().getTripod();
                if (!isStillCapture) mOisAutoTripod = isTripod;

                if (isTripod) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                } else {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                }
            } else {
                // Default: OIS ON for both preview and still capture.
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            }
        }
    }

    /**
     * P365 step 7 (owner, 2026-10-27: «В Why камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»):
     * whether a shot's series is being taken - the flags under which onMatchedLiveRaw publishes no viewfinder frame (MainRenderer
     * keeps the last RAW frame on the screen meanwhile, scam_dev vf_series_hold).
     */
    public static boolean seriesInFlight() {
        final CaptureController c = ScameraApp.getCaptureController();
        return c != null && (c.mZslCapturing || c.mHybridZslCapture || c.mScamRingFrozen);
    }
}
