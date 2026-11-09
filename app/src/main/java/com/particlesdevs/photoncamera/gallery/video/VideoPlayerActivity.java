package com.particlesdevs.photoncamera.gallery.video;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaFormat;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.files.GalleryVideo;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * P499 (owner, 2026-11-04: «свой видеоплеер с соответствующим нашему интерфейсом ... функционал mx player, у нас должен быть такой
 * же»): the gallery's video player on its own {@link VideoEngine}. At the top: back, the name, the sound track, subtitles, the decoder
 * (HW / HW+ / SW) and «Ещё»; under it the quick buttons (repeat, speed, screenshot, background play, rotation, sleep timer, sound,
 * customise - labels behind the chevron); at the bottom the seek bar, lock, previous / play / next, the picture fit and the
 * picture-in-picture. Gestures: tap - the controls; double tap at the sides - ∓10 s (repeated taps add up), in the middle - pause;
 * a horizontal drag - seek; a vertical drag - brightness (left) / volume up to a 200 % boost (right); pinch - zoom and pan; a long
 * press - 2x while held. Where a video was left is kept ({@link VideoResume}). Its own task, so it can float in picture-in-picture.
 */
public final class VideoPlayerActivity extends AppCompatActivity implements VideoEngine.Listener, SurfaceHolder.Callback,
        VideoPlaybackService.Controller {
    private static final String TAG = "VideoPlayer";
    static final String EXTRA_URIS = "uris", EXTRA_NAMES = "names", EXTRA_PATHS = "paths", EXTRA_INDEX = "index";
    private static final String PREFS = "video_player";
    private static final String ACTION_PIP = "com.scamera.video.PIP";
    /**
     * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the buttons and pills over the
     * video are the camera's dark bars (rgba(20, 20, 22, .9), a 0.5dp light edge), the panels floating #1C1C1E sheets with 34dp
     * corners, the seek and speed the shared slider, a choice ticked in the accent at the row's end, switches the shared switch.
     */
    private static final int PANEL = GalleryUi.BAR, SCRIM = 0x99000000;
    private static final long HIDE_MS = 3500;

    /** Opens the player on {@code index} of the list (the gallery's videos in its order, for previous / next). */
    public static void open(Context c, List<Uri> uris, List<String> names, List<String> paths, int index) {
        Intent i = new Intent(c, VideoPlayerActivity.class)
                .putParcelableArrayListExtra(EXTRA_URIS, new ArrayList<>(uris))
                .putStringArrayListExtra(EXTRA_NAMES, new ArrayList<>(names))
                .putStringArrayListExtra(EXTRA_PATHS, new ArrayList<>(paths))
                .putExtra(EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    enum Repeat { OFF, ALL, ONE }

    enum Rotate { VIDEO, SENSOR, LANDSCAPE, PORTRAIT }

    enum Fit { FIT, CROP, STRETCH, ORIGINAL, R16_9, R4_3 }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // ---- the list
    private ArrayList<Uri> uris = new ArrayList<>();
    private ArrayList<String> names = new ArrayList<>(), paths = new ArrayList<>();
    private int index;

    // ---- playback
    @Nullable private VideoEngine engine;
    private boolean prepared;
    @Nullable private android.view.Surface surface;
    private List<Subtitles.Cue> cues = Collections.emptyList();
    private String subsChoice = "off"; // off | track:<n> | file:<uri>
    @Nullable private Uri subsFile;
    private float speed = 1f;
    private boolean muted, locked, labelsShown, backgroundOn, pip, resumeOnFocus, wasPlayingBeforeDrag;
    private Repeat repeat = Repeat.OFF;
    private Rotate rotate = Rotate.VIDEO;
    private Fit fit = Fit.FIT;
    private float zoom = 1f, panX, panY;
    private long sleepAt; // elapsed ms, 0: off; -1: at the end of this video
    private long lastSaveAt;
    @Nullable private LoudnessEnhancer boost;
    private float boostLevel; // 0..1 above the full system volume
    @Nullable private AudioFocusRequest focusRequest;
    private boolean noisyRegistered;

    // ---- views
    private FrameLayout root, videoBox, controls, panelLayer, hud;
    private SurfaceView surfaceView;
    private TextView subs, title, sub, decoderPill, time, total, hudBig, hudSmall, pill;
    private ImageView hudIcon;
    private ProgressBar hudBar;
    private LinearLayout topBar, bottomBar, quickRow;
    private View unlockDisc, playButton, prevButton, nextButton, audioDisc, subsDisc;
    private ImageView playIcon;
    private com.particlesdevs.photoncamera.ui.controls.IosSlider seek;
    private boolean dragging, showRemaining;
    private final List<Quick> quicks = new ArrayList<>();
    private View chevron;

    private ActivityResultLauncher<String[]> pickSubs;

    private final Runnable autoHide = () -> showControls(false);
    private final Runnable hideHud = () -> fadeOut(hud);
    private final Runnable hidePill = () -> fadeOut(pill);
    private final Runnable hideUnlock = () -> fadeOut(unlockDisc);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            ui.postDelayed(this, 120);
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pausePlayback(); // the headphones came out
        }
    };

    private final BroadcastReceiver pipActions = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String cmd = i.getStringExtra("cmd");
            if ("prev".equals(cmd)) previous();
            else if ("next".equals(cmd)) next();
            else togglePlay();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> ui.post(() -> {
        if (engine == null) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocus = false;
            pausePlayback();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocus = engine.isPlaying();
            pausePlayback();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            engine.setVolume(muted ? 0f : .25f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            engine.setVolume(muted ? 0f : 1f);
            if (resumeOnFocus) startPlayback();
            resumeOnFocus = false;
        }
    });

    // ================================================================== lifecycle

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        repeat = enumPref("repeat", Repeat.OFF);
        rotate = enumPref("rotate", Rotate.VIDEO);
        fit = enumPref("fit", Fit.FIT);
        backgroundOn = prefs.getBoolean("background", false);
        labelsShown = prefs.getBoolean("labels", false);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setNavigationBarColor(0);
        getWindow().setStatusBarColor(0);
        if (Build.VERSION.SDK_INT >= 28)
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        pickSubs = registerForActivityResult(new ActivityResultContracts.OpenDocument(), u -> {
            if (u != null) loadSubsFile(u);
        });
        build();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (panelLayer.getVisibility() == View.VISIBLE) closePanel();
                else finishPlayer();
            }
        });
        ContextCompat.registerReceiver(this, pipActions, new IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED);
        readIntent(getIntent());
        ui.post(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_URIS)) {
            setIntent(intent);
            readIntent(intent);
        }
    }

    private void readIntent(Intent i) {
        ArrayList<Uri> u = i.getParcelableArrayListExtra(EXTRA_URIS);
        if (u == null || u.isEmpty()) {
            if (engine == null) finish(); // reopened from the notification after the player had gone
            return;
        }
        uris = u;
        names = orEmpty(i.getStringArrayListExtra(EXTRA_NAMES), u.size());
        paths = orEmpty(i.getStringArrayListExtra(EXTRA_PATHS), u.size());
        load(Math.max(0, Math.min(u.size() - 1, i.getIntExtra(EXTRA_INDEX, 0))));
    }

    private static ArrayList<String> orEmpty(@Nullable ArrayList<String> l, int n) {
        ArrayList<String> out = l != null ? l : new ArrayList<>();
        while (out.size() < n) out.add("");
        return out;
    }

    @Override
    protected void onStart() {
        super.onStart();
        VideoPlaybackService.stop(this);
        immersive(controls.getVisibility() == View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveResume();
        // background play: start the service while the player is still visible (a start from the background is refused)
        if (backgroundOn && engine != null && engine.isPlaying() && !isFinishing() && !isChangingConfigurations() && !isPipNow())
            VideoPlaybackService.start(this, this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isPipNow()) { // the picture-in-picture window was closed
            pausePlayback();
            finishAndRemoveTask();
            return;
        }
        if (!(backgroundOn && engine != null && engine.isPlaying())) pausePlayback();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        saveResume();
        releaseEngine();
        VideoPlaybackService.stop(this);
        try {
            unregisterReceiver(pipActions);
        } catch (RuntimeException ignored) {
        }
        if (noisyRegistered) {
            unregisterReceiver(noisy);
            noisyRegistered = false;
        }
        abandonFocus();
        super.onDestroy();
    }

    private boolean isPipNow() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private void finishPlayer() {
        saveResume();
        releaseEngine();
        finishAndRemoveTask();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        root.post(this::layoutVideo);
    }

    // ================================================================== the engine

    private void load(int i) {
        saveResume();
        releaseEngine();
        index = i;
        prepared = false;
        cues = Collections.emptyList();
        subsChoice = "off";
        subsFile = null;
        subs.setText("");
        zoom = 1f;
        panX = panY = 0;
        if (sleepAt == -1) sleepAt = 0;
        title.setText(stem(names.get(i)));
        sub.setText("");
        time.setText("0:00");
        total.setText("");
        seek.setValueNow(0);
        bindNav();
        engine = new VideoEngine(this, uris.get(i), this);
        if (surface != null) engine.setSurface(surface);
        engine.setDecoder(enumPref("decoder", VideoEngine.Decoder.HW_PLUS));
        engine.prepare();
        showControls(true);
    }

    private void releaseEngine() {
        if (engine == null) return;
        engine.setSurface(null);
        engine.release();
        engine = null;
        if (boost != null) {
            boost.release();
            boost = null;
        }
    }

    @Override
    public void onPrepared() {
        VideoEngine e = engine;
        if (e == null) return;
        prepared = true;
        long dur = e.durationMs();
        seek.setRange(0, Math.max(1, Math.min(Integer.MAX_VALUE, dur)), 0);
        total.setText(GalleryVideo.clock(dur));
        bindSub();
        applyRotation();
        layoutVideo();
        long resume = VideoResume.load(this, key());
        Log.d(TAG, "resume load " + resume);
        e.setSpeed(speed);
        e.setVolume(muted ? 0f : 1f);
        e.start(resume * 1000, true);
        onPlayStateChanged();
        requestFocus();
        if (resume > 0) showPill(Lang.t("Продолжено с ", "Resumed at ") + GalleryVideo.clock(resume), Lang.t("С начала", "From the start"), () -> {
            if (engine != null) engine.seekTo(0, true);
            fadeOut(pill);
        });
        autoSubs();
        if (boostLevel > 0) applyBoost();
        scheduleHide();
    }

    @Override
    public void onCompleted() {
        VideoResume.save(this, key(), 0, 0); // watched to the end: next time from the start
        if (sleepAt == -1) {
            sleepAt = 0;
            onPlayStateChanged();
            showHud(R.drawable.ui_ic_moon, Lang.t("Таймер сна", "Sleep timer"), Lang.t("Видео закончилось", "The video ended"), -1);
            return;
        }
        if (repeat == Repeat.ONE && engine != null) {
            engine.seekTo(0, true);
            engine.play();
        } else if (repeat == Repeat.ALL && uris.size() > 1) next();
        else if (repeat == Repeat.ALL && engine != null) {
            engine.seekTo(0, true);
            engine.play();
        } else {
            onPlayStateChanged();
            showControls(true);
        }
    }

    @Override
    public void onError(String message) {
        String text;
        if ("hw".equals(message)) text = Lang.t("Аппаратный декодер не открыл видео. Выберите HW+ или SW", "The hardware decoder could not open the video. Choose HW+ or SW");
        else if ("decoder".equals(message)) text = Lang.t("Нет декодера для этого видео", "No decoder for this video");
        else if (!prepared) text = Lang.t("Не удалось открыть видео", "Cannot open the video");
        else text = Lang.t("Ошибка декодирования", "Decoding error");
        showPill(text, null, null);
        showControls(true);
        onPlayStateChanged();
    }

    @Override
    public void onDecoder(String name, boolean fellBack) {
        bindDecoderPill();
        if (fellBack && engine != null && engine.decoder() == VideoEngine.Decoder.HW_PLUS)
            showPill(Lang.t("Аппаратный декодер не справился, включён программный", "The hardware decoder failed, the software one took over"), null, null);
    }

    private String key() {
        return uris.isEmpty() ? "" : uris.get(index).toString();
    }

    private void saveResume() {
        if (engine == null || !prepared || engine.isCompleted()) return;
        VideoResume.save(this, key(), engine.positionMs(), engine.durationMs());
        Log.d(TAG, "resume save " + engine.positionMs() + "/" + engine.durationMs());
        lastSaveAt = android.os.SystemClock.elapsedRealtime();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surface = h.getSurface();
        if (engine != null) engine.setSurface(surface);
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surface = null;
        if (engine != null) engine.setSurface(null);
    }

    // ================================================================== play / pause

    private void startPlayback() {
        if (engine == null || !prepared) return;
        requestFocus();
        engine.play();
        onPlayStateChanged();
        scheduleHide();
    }

    private void pausePlayback() {
        if (engine == null) return;
        engine.pause();
        saveResume();
        onPlayStateChanged();
    }

    private void togglePlay() {
        if (engine == null) return;
        if (engine.isPlaying()) {
            pausePlayback();
            showControls(true);
        } else startPlayback();
        Motion.pop(playIcon);
    }

    private void onPlayStateChanged() {
        boolean playing = engine != null && engine.isPlaying();
        playIcon.setImageResource(playing ? R.drawable.ui_ic_pause_bars : R.drawable.ui_ic_play);
        playButton.setContentDescription(playing ? Lang.t("Пауза", "Pause") : Lang.t("Смотреть", "Play"));
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (playing && !noisyRegistered) {
            ContextCompat.registerReceiver(this, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED);
            noisyRegistered = true;
        }
        updatePip();
        VideoPlaybackService.refresh(this);
    }

    private void requestFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null || Build.VERSION.SDK_INT < 26) return;
        if (focusRequest == null)
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(focusListener, ui)
                    .build();
        am.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null && focusRequest != null && Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(focusRequest);
    }

    // ---- VideoPlaybackService.Controller

    @Override
    public boolean playing() {
        return engine != null && engine.isPlaying();
    }

    @Override
    public String title() {
        return uris.isEmpty() ? "" : stem(names.get(index));
    }

    @Override
    public void toggle() {
        ui.post(this::togglePlay);
    }

    @Override
    public void close() {
        ui.post(this::finishPlayer);
    }

    // ================================================================== the list

    private boolean hasPrevious() {
        return index > 0 || (repeat == Repeat.ALL && uris.size() > 1);
    }

    private boolean hasNext() {
        return index < uris.size() - 1 || (repeat == Repeat.ALL && uris.size() > 1);
    }

    private void previous() {
        if (engine != null && engine.positionMs() > 3000) { // MX: the first press goes back to the start
            engine.seekTo(0, true);
            return;
        }
        if (!hasPrevious()) return;
        load(index > 0 ? index - 1 : uris.size() - 1);
    }

    private void next() {
        if (!hasNext()) return;
        load(index < uris.size() - 1 ? index + 1 : 0);
    }

    private void bindNav() {
        GalleryUi.setEnabledLook(prevButton, true);
        GalleryUi.setEnabledLook(nextButton, hasNext());
    }

    // ================================================================== the tick

    private void onTick() {
        VideoEngine e = engine;
        if (e == null || !prepared) return;
        long pos = e.positionMs(), dur = e.durationMs();
        if (!dragging) {
            seek.setValueNow(Math.min(Integer.MAX_VALUE, pos));
            time.setText(GalleryVideo.clock(pos));
            total.setText(showRemaining ? "-" + GalleryVideo.clock(Math.max(0, dur - pos)) : GalleryVideo.clock(dur));
        }
        String s = cues.isEmpty() ? null : Subtitles.at(cues, pos);
        if (s == null) s = "";
        if (!s.contentEquals(subs.getText())) subs.setText(s);
        long now = android.os.SystemClock.elapsedRealtime();
        if (e.isPlaying() && now - lastSaveAt > 3000) saveResume();
        if (sleepAt > 0) {
            if (now >= sleepAt) {
                sleepAt = 0;
                pausePlayback();
                showHud(R.drawable.ui_ic_moon, Lang.t("Таймер сна", "Sleep timer"), Lang.t("Пауза", "Paused"), -1);
            }
            bindQuick();
        }
    }

    // ================================================================== layout of the picture

    private void layoutVideo() {
        VideoEngine e = engine;
        int bw = videoBox.getWidth(), bh = videoBox.getHeight();
        if (e == null || e.width() <= 0 || bw <= 0 || bh <= 0) return;
        int[] size = VideoLayout.size(fit, e.width(), e.height(), bw, bh, zoom);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != size[0] || lp.height != size[1]) {
            lp.width = size[0];
            lp.height = size[1];
            lp.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(lp);
        }
        float maxX = Math.max(0, (size[0] - bw) / 2f), maxY = Math.max(0, (size[1] - bh) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        surfaceView.setTranslationX(panX);
        surfaceView.setTranslationY(panY);
    }

    private void applyRotation() {
        VideoEngine e = engine;
        int o;
        switch (rotate) {
            case SENSOR:
                o = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
                break;
            case LANDSCAPE:
                o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                break;
            case PORTRAIT:
                o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
            default:
                o = e != null && e.width() > 0 && e.height() > e.width() ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
        if (getRequestedOrientation() != o) setRequestedOrientation(o);
    }

    // ================================================================== building the screen

    @SuppressLint("ClickableViewAccessibility")
    private void build() {
        Context c = this;
        root = new FrameLayout(c);
        root.setBackgroundColor(0xFF000000);
        videoBox = new FrameLayout(c);
        videoBox.setClipChildren(true);
        surfaceView = new SurfaceView(c);
        surfaceView.getHolder().addCallback(this);
        videoBox.addView(surfaceView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        videoBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(() -> {
                layoutVideo();
                compact(r - l);
            });
        });
        root.addView(videoBox, match());

        subs = new TextView(c);
        subs.setTextColor(0xFFFFFFFF);
        subs.setShadowLayer(GalleryUi.dp(c, 3), 0, GalleryUi.dp(c, 1), 0xE6000000);
        subs.setGravity(Gravity.CENTER);
        subs.setTextSize(subSize());
        subs.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sp.leftMargin = sp.rightMargin = GalleryUi.dp(c, 32);
        sp.bottomMargin = GalleryUi.dp(c, 28);
        root.addView(subs, sp);

        View touch = new View(c);
        installGestures(touch);
        root.addView(touch, match());

        hud = buildHud(c);
        root.addView(hud, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        controls = new FrameLayout(c);
        topBar = buildTop(c);
        controls.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        bottomBar = buildBottom(c);
        controls.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(controls, match());

        unlockDisc = disc(c, R.drawable.ui_ic_unlock, Lang.t("Разблокировать экран", "Unlock the screen"), v -> setLocked(false));
        unlockDisc.setVisibility(View.GONE);
        FrameLayout.LayoutParams up = new FrameLayout.LayoutParams(GalleryUi.dp(c, 52), GalleryUi.dp(c, 52), Gravity.START | Gravity.BOTTOM);
        up.leftMargin = GalleryUi.dp(c, 24);
        up.bottomMargin = GalleryUi.dp(c, 28);
        root.addView(unlockDisc, up);

        pill = GalleryUi.text(c, "", 13.5f, GalleryUi.TEXT);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 10), GalleryUi.dp(c, 16), GalleryUi.dp(c, 10));
        pill.setBackground(GalleryUi.bar(c, 999));
        pill.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        pp.bottomMargin = GalleryUi.dp(c, 150);
        pp.leftMargin = pp.rightMargin = GalleryUi.dp(c, 16);
        root.addView(pill, pp);

        panelLayer = new FrameLayout(c);
        panelLayer.setBackgroundColor(SCRIM);
        panelLayer.setVisibility(View.GONE);
        panelLayer.setClickable(true);
        panelLayer.setOnClickListener(v -> closePanel());
        root.addView(panelLayer, match());

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            controls.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            panelLayer.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root);
        bindQuick();
        bindDecoderPill();
    }

    /** A narrow (portrait) screen: the sound and subtitle buttons move into «Ещё», the play button keeps less room. */
    private void compact(int width) {
        boolean narrow = width < GalleryUi.dp(this, 560);
        audioDisc.setVisibility(narrow ? View.GONE : View.VISIBLE);
        subsDisc.setVisibility(narrow ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams pl = (LinearLayout.LayoutParams) playButton.getLayoutParams();
        int m = GalleryUi.dp(this, narrow ? 10 : 22);
        if (pl.leftMargin != m) {
            pl.leftMargin = pl.rightMargin = m;
            playButton.setLayoutParams(pl);
        }
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout buildTop(Context c) {
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 10), GalleryUi.dp(c, 12), GalleryUi.dp(c, 28));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        top.setClickable(true);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(disc(c, R.drawable.ui_ic_back, Lang.t("Назад", "Back"), v -> finishPlayer()), size(c, 44));
        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        title = GalleryUi.fitText(c, "", 16, GalleryUi.TEXT);
        title.setTypeface(GalleryUi.semibold());
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        sub = GalleryUi.text(c, "", 12.5f, 0xB3FFFFFF);
        sub.setSingleLine(true);
        meta.addView(title);
        meta.addView(sub);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mp.leftMargin = mp.rightMargin = GalleryUi.dp(c, 10);
        row.addView(meta, mp);
        audioDisc = disc(c, R.drawable.ui_ic_music, Lang.t("Звуковая дорожка", "Sound track"), v -> openAudioPanel());
        row.addView(audioDisc, gap(size(c, 40), 6));
        subsDisc = disc(c, R.drawable.ui_ic_subtitles, Lang.t("Субтитры", "Subtitles"), v -> openSubsPanel());
        row.addView(subsDisc, gap(size(c, 40), 6));
        decoderPill = GalleryUi.text(c, "HW+", 13, GalleryUi.TEXT);
        decoderPill.setTypeface(Typeface.DEFAULT_BOLD);
        decoderPill.setGravity(Gravity.CENTER);
        decoderPill.setMinWidth(GalleryUi.dp(c, 52));
        decoderPill.setPadding(GalleryUi.dp(c, 10), 0, GalleryUi.dp(c, 10), 0);
        decoderPill.setBackground(GalleryUi.pressable(GalleryUi.bar(c, 999)));
        decoderPill.setClickable(true);
        decoderPill.setFocusable(true);
        decoderPill.setOnClickListener(v -> openDecoderPanel());
        Motion.press(decoderPill);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, GalleryUi.dp(c, 40));
        dp.leftMargin = GalleryUi.dp(c, 6);
        row.addView(decoderPill, dp);
        row.addView(disc(c, R.drawable.ui_ic_more, Lang.t("Ещё", "More"), v -> openMorePanel()), gap(size(c, 40), 6));
        top.addView(row);

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        quickRow = new LinearLayout(c);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.TOP);
        scroll.addView(quickRow);
        quicks.add(new Quick(c, "customize", R.drawable.ui_ic_sliders, Lang.t("Настройка", "Customise"), this::openCustomizePanel));
        quicks.add(new Quick(c, "repeat", R.drawable.ui_ic_repeat, Lang.t("Повтор", "Repeat"), this::cycleRepeat));
        quicks.add(new Quick(c, "speed", 0, Lang.t("Скорость", "Speed"), this::openSpeedPanel));
        quicks.add(new Quick(c, "shot", R.drawable.ui_ic_screenshot, Lang.t("Скриншот", "Screenshot"), this::screenshot));
        quicks.add(new Quick(c, "background", R.drawable.ui_ic_headphones, Lang.t("В фоне", "Background"), this::toggleBackground));
        quicks.add(new Quick(c, "rotate", R.drawable.ui_ic_rotate, Lang.t("Поворот", "Rotation"), this::cycleRotate));
        quicks.add(new Quick(c, "sleep", R.drawable.ui_ic_moon, Lang.t("Сон", "Sleep"), this::openSleepPanel));
        quicks.add(new Quick(c, "mute", R.drawable.ui_ic_sound, Lang.t("Звук", "Sound"), this::toggleMute));
        for (Quick q : quicks) quickRow.addView(q.box);
        chevron = disc(c, R.drawable.ui_ic_chev, Lang.t("Подписи кнопок", "Button labels"), v -> {
            labelsShown = !labelsShown;
            prefs.edit().putBoolean("labels", labelsShown).apply();
            bindQuick();
            scheduleHide();
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 34), GalleryUi.dp(c, 34));
        cp.leftMargin = GalleryUi.dp(c, 4);
        cp.topMargin = GalleryUi.dp(c, 5);
        quickRow.addView(chevron, cp);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qp.topMargin = GalleryUi.dp(c, 12);
        top.addView(scroll, qp);
        return top;
    }

    private LinearLayout buildBottom(Context c) {
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 36), GalleryUi.dp(c, 12), GalleryUi.dp(c, 12));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        bottom.setClickable(true);
        LinearLayout seekRow = new LinearLayout(c);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        time = GalleryUi.text(c, "0:00", 13, GalleryUi.TEXT);
        time.setMinWidth(GalleryUi.dp(c, 44));
        seekRow.addView(time);
        // P579: the shared slider (the relative drag, the glass knob, a tap jumps there)
        seek = new com.particlesdevs.photoncamera.ui.controls.IosSlider(c);
        seek.setContentDescription(Lang.t("Перемотка", "Seek"));
        int accent = GalleryUi.accent(c);
        seek.setAccent(accent);
        seek.setTrackColour(0x66FFFFFF);
        seek.setRange(0, 1, 0);
        seek.setListener(new com.particlesdevs.photoncamera.ui.controls.IosSlider.Listener() {
            private long lastPreview;

            @Override
            public void onValue(com.particlesdevs.photoncamera.ui.controls.IosSlider s, float value, boolean fromUser) {
                if (!fromUser || engine == null) return;
                long p = (long) value;
                time.setText(GalleryVideo.clock(p));
                // a step by the keyboard or the accessibility actions: no finger, the seek is exact at once
                if (!s.isTracking()) {
                    engine.seekTo(p, true);
                    return;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastPreview > 90) {
                    lastPreview = now;
                    engine.seekTo(p, false);
                }
            }

            @Override
            public void onTracking(com.particlesdevs.photoncamera.ui.controls.IosSlider s, boolean tracking) {
                if (tracking) {
                    dragging = true;
                    ui.removeCallbacks(autoHide);
                    wasPlayingBeforeDrag = engine != null && engine.isPlaying();
                    if (engine != null) engine.pause();
                    return;
                }
                dragging = false;
                if (engine != null) {
                    engine.seekTo((long) s.getValue(), true);
                    if (wasPlayingBeforeDrag) engine.play();
                }
                onPlayStateChanged();
                scheduleHide();
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, GalleryUi.dp(c, 38), 1);
        seekParams.leftMargin = seekParams.rightMargin = GalleryUi.dp(c, 6);
        seekRow.addView(seek, seekParams);
        total = GalleryUi.text(c, "", 13, GalleryUi.TEXT);
        total.setMinWidth(GalleryUi.dp(c, 44));
        total.setGravity(Gravity.END);
        total.setOnClickListener(v -> {
            showRemaining = !showRemaining;
            onTick();
        });
        seekRow.addView(total);
        bottom.addView(seekRow);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(disc(c, R.drawable.ui_ic_lock, Lang.t("Заблокировать экран", "Lock the screen"), v -> setLocked(true)), size(c, 44));
        row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        prevButton = disc(c, R.drawable.ui_ic_prev, Lang.t("Предыдущее видео", "Previous video"), v -> previous());
        row.addView(prevButton, size(c, 48));
        playButton = new FrameLayout(c);
        GradientDrawable pd = new GradientDrawable();
        pd.setShape(GradientDrawable.OVAL);
        pd.setColor(accent);
        playButton.setBackground(GalleryUi.pressable(pd));
        playButton.setClickable(true);
        playButton.setFocusable(true);
        playButton.setOnClickListener(v -> togglePlay());
        Motion.press(playButton);
        playIcon = GalleryUi.icon(c, R.drawable.ui_ic_play, GalleryUi.INK, 30);
        ((FrameLayout) playButton).addView(playIcon, new FrameLayout.LayoutParams(GalleryUi.dp(c, 30), GalleryUi.dp(c, 30), Gravity.CENTER));
        LinearLayout.LayoutParams pl = size(c, 62);
        pl.leftMargin = pl.rightMargin = GalleryUi.dp(c, 22);
        row.addView(playButton, pl);
        nextButton = disc(c, R.drawable.ui_ic_next, Lang.t("Следующее видео", "Next video"), v -> next());
        row.addView(nextButton, size(c, 48));
        row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(disc(c, R.drawable.ui_ic_aspect, Lang.t("Размер картинки", "Picture fit"), v -> cycleFit()), size(c, 44));
        View pipButton = disc(c, R.drawable.ui_ic_pip, Lang.t("Картинка в картинке", "Picture-in-picture"), v -> enterPip());
        if (Build.VERSION.SDK_INT < 26 || !getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
            pipButton.setVisibility(View.GONE);
        row.addView(pipButton, gap(size(c, 44), 8));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = GalleryUi.dp(c, 6);
        bottom.addView(row, rp);
        return bottom;
    }

    private FrameLayout buildHud(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(GalleryUi.bar(c, 26));
        card.setPadding(GalleryUi.dp(c, 22), GalleryUi.dp(c, 14), GalleryUi.dp(c, 22), GalleryUi.dp(c, 14));
        hudIcon = GalleryUi.icon(c, R.drawable.ui_ic_sun, GalleryUi.TEXT, 26);
        card.addView(hudIcon, new LinearLayout.LayoutParams(GalleryUi.dp(c, 26), GalleryUi.dp(c, 26)));
        hudBig = GalleryUi.text(c, "", 22, GalleryUi.TEXT);
        hudBig.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hudBig.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = GalleryUi.dp(c, 6);
        card.addView(hudBig, bp);
        hudSmall = GalleryUi.text(c, "", 13, GalleryUi.MUTED);
        hudSmall.setGravity(Gravity.CENTER);
        card.addView(hudSmall);
        hudBar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        hudBar.setMax(1000);
        hudBar.setProgressTintList(ColorStateList.valueOf(GalleryUi.accent(c)));
        hudBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x5C787880));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 150), GalleryUi.dp(c, 6));
        hp.topMargin = GalleryUi.dp(c, 10);
        card.addView(hudBar, hp);
        f.addView(card);
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return f;
    }

    private static LinearLayout.LayoutParams size(Context c, int dp) {
        return new LinearLayout.LayoutParams(GalleryUi.dp(c, dp), GalleryUi.dp(c, dp));
    }

    private LinearLayout.LayoutParams gap(LinearLayout.LayoutParams lp, int leftDp) {
        lp.leftMargin = GalleryUi.dp(this, leftDp);
        return lp;
    }

    /** A round button with a line icon; P579: the camera's dark bar with its light edge. */
    static FrameLayout disc(Context c, int icon, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        GradientDrawable d = GalleryUi.bar(c, 999);
        d.setShape(GradientDrawable.OVAL);
        b.setBackground(GalleryUi.pressable(d));
        b.setContentDescription(description);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        Motion.press(b);
        b.addView(GalleryUi.icon(c, icon, GalleryUi.TEXT, 22), new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.CENTER));
        return b;
    }

    /** One quick button: a 46dp disc (an icon, or the speed as text) with its label under it while the labels are shown. */
    private final class Quick {
        final String id;
        final LinearLayout box;
        final FrameLayout disc;
        @Nullable final ImageView icon;
        @Nullable final TextView text;
        final TextView label;
        final String baseLabel;

        Quick(Context c, String id, int iconRes, String label, Runnable click) {
            this.id = id;
            this.baseLabel = label;
            box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(GalleryUi.dp(c, 4), 0, GalleryUi.dp(c, 4), 0);
            disc = new FrameLayout(c);
            GradientDrawable d = GalleryUi.bar(c, 999);
            d.setShape(GradientDrawable.OVAL);
            disc.setBackground(GalleryUi.pressable(d));
            disc.setClickable(true);
            disc.setFocusable(true);
            disc.setContentDescription(label);
            disc.setOnClickListener(v -> {
                click.run();
                scheduleHide();
            });
            Motion.press(disc);
            if (iconRes != 0) {
                icon = GalleryUi.icon(c, iconRes, GalleryUi.TEXT, 22);
                disc.addView(icon, new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.CENTER));
                text = null;
            } else {
                icon = null;
                text = GalleryUi.text(c, "1x", 14, GalleryUi.TEXT);
                text.setTypeface(Typeface.DEFAULT_BOLD);
                text.setGravity(Gravity.CENTER);
                disc.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            box.addView(disc, new LinearLayout.LayoutParams(GalleryUi.dp(c, 46), GalleryUi.dp(c, 46)));
            this.label = GalleryUi.text(c, label, 11, GalleryUi.TEXT);
            this.label.setGravity(Gravity.CENTER);
            this.label.setMaxWidth(GalleryUi.dp(c, 76));
            this.label.setShadowLayer(GalleryUi.dp(c, 2), 0, 0, 0xCC000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = GalleryUi.dp(c, 4);
            box.addView(this.label, lp);
        }

        void set(int iconRes, boolean on, String label) {
            if (icon != null) {
                icon.setImageResource(iconRes);
                // P570: a colour icon keeps its colours (on: its pressed pose); a plain glyph takes the accent when on
                if (com.particlesdevs.photoncamera.ui.Icons.colour(box.getContext(), iconRes)) { com.particlesdevs.photoncamera.ui.Icons.own(icon); icon.setActivated(on); }
                else icon.setColorFilter(on ? GalleryUi.accent(box.getContext()) : GalleryUi.TEXT);
            }
            this.label.setText(label);
            disc.setContentDescription(label);
            ViewCompat.setStateDescription(disc, on ? Lang.t("включено", "on") : null);
        }
    }

    private Set<String> hiddenQuicks() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
    }

    private void bindQuick() {
        Set<String> hidden = hiddenQuicks();
        for (Quick q : quicks) {
            boolean shown = q.id.equals("customize") ? labelsShown : !hidden.contains(q.id);
            q.box.setVisibility(shown ? View.VISIBLE : View.GONE);
            q.label.setVisibility(labelsShown ? View.VISIBLE : View.GONE);
            switch (q.id) {
                case "repeat":
                    q.set(repeat == Repeat.ONE ? R.drawable.ui_ic_repeat_one : R.drawable.ui_ic_repeat, repeat != Repeat.OFF,
                            repeat == Repeat.OFF ? Lang.t("Повтор: выкл.", "Repeat: off") : repeat == Repeat.ALL
                                    ? Lang.t("Повтор всех", "Repeat all") : Lang.t("Повтор одного", "Repeat one"));
                    break;
                case "speed":
                    if (q.text != null) q.text.setText(speedLabel(speed));
                    q.set(0, speed != 1f, Lang.t("Скорость ", "Speed ") + speedLabel(speed));
                    if (q.text != null) q.text.setTextColor(speed != 1f ? GalleryUi.accent(this) : GalleryUi.TEXT);
                    break;
                case "background":
                    q.set(R.drawable.ui_ic_headphones, backgroundOn, backgroundOn ? Lang.t("В фоне: вкл.", "Background: on") : Lang.t("В фоне", "Background"));
                    break;
                case "rotate":
                    q.set(R.drawable.ui_ic_rotate, rotate != Rotate.VIDEO, rotateName(rotate));
                    break;
                case "sleep":
                    String s = sleepAt == -1 ? Lang.t("До конца", "At the end") : sleepAt > 0
                            ? GalleryVideo.clock(Math.max(0, sleepAt - android.os.SystemClock.elapsedRealtime())) : Lang.t("Сон", "Sleep");
                    q.set(R.drawable.ui_ic_moon, sleepAt != 0, s);
                    break;
                case "mute":
                    q.set(muted ? R.drawable.ui_ic_mute : R.drawable.ui_ic_sound, muted, muted ? Lang.t("Без звука", "Muted") : Lang.t("Звук", "Sound"));
                    break;
                default:
                    break;
            }
        }
        chevron.setRotation(labelsShown ? 180 : 0);
    }

    static String speedLabel(float s) {
        String v = String.format(Locale.ROOT, "%.2f", s);
        v = v.replaceAll("0+$", "").replaceAll("\\.$", "");
        return v + "x";
    }

    private void bindDecoderPill() {
        VideoEngine.Decoder d = engine != null ? engine.decoder() : enumPref("decoder", VideoEngine.Decoder.HW_PLUS);
        decoderPill.setText(d == VideoEngine.Decoder.HW ? "HW" : d == VideoEngine.Decoder.SW ? "SW" : "HW+");
        decoderPill.setContentDescription(Lang.t("Декодер ", "Decoder ") + decoderPill.getText());
    }

    private void bindSub() {
        VideoEngine e = engine;
        if (e == null) return;
        StringBuilder s = new StringBuilder(GalleryVideo.clock(e.durationMs()));
        if (e.width() > 0) s.append(" · ").append(e.width()).append('×').append(e.height());
        VideoEngine.Track v = e.videoTrack();
        if (v != null) s.append(" · ").append(VideoEngine.codecLabel(v.mime));
        if (v != null && hdr(v)) s.append(" · HDR");
        sub.setText(s);
    }

    private static boolean hdr(VideoEngine.Track v) {
        int t = v.integer(MediaFormat.KEY_COLOR_TRANSFER, 0);
        return t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG || v.mime.equals("video/dolby-vision");
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ================================================================== controls visibility

    private void showControls(boolean shown) {
        if (pip) shown = false;
        if (locked && shown) {
            flashUnlock();
            return;
        }
        ui.removeCallbacks(autoHide);
        boolean now = controls.getVisibility() == View.VISIBLE;
        if (shown != now) {
            if (shown) {
                controls.setVisibility(View.VISIBLE);
                controls.setAlpha(0f);
                topBar.setTranslationY(-GalleryUi.dp(this, 16));
                bottomBar.setTranslationY(GalleryUi.dp(this, 16));
                controls.animate().alpha(1f).setDuration(Motion.enabled(controls) ? 180 : 0).start();
                topBar.animate().translationY(0).setInterpolator(Motion.SHEET).setDuration(Motion.enabled(controls) ? 260 : 0).start();
                bottomBar.animate().translationY(0).setInterpolator(Motion.SHEET).setDuration(Motion.enabled(controls) ? 260 : 0).start();
            } else {
                controls.animate().alpha(0f).setDuration(Motion.enabled(controls) ? 180 : 0)
                        .withEndAction(() -> controls.setVisibility(View.GONE)).start();
            }
            subs.animate().translationY(shown ? -GalleryUi.dp(this, 110) : 0).setDuration(200).start();
            immersive(shown);
        }
        if (shown) scheduleHide();
    }

    private void scheduleHide() {
        ui.removeCallbacks(autoHide);
        if (engine != null && engine.isPlaying() && !dragging && panelLayer.getVisibility() != View.VISIBLE)
            ui.postDelayed(autoHide, HIDE_MS);
    }

    private void immersive(boolean barsShown) {
        WindowInsetsControllerCompat w = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        w.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        w.setAppearanceLightStatusBars(false);
        if (barsShown && !locked) w.show(WindowInsetsCompat.Type.systemBars());
        else w.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void setLocked(boolean on) {
        locked = on;
        if (on) {
            showControls(false);
            controls.setVisibility(View.GONE);
            immersive(false);
            flashUnlock();
            showHud(R.drawable.ui_ic_lock, Lang.t("Экран заблокирован", "Screen locked"), Lang.t("Нажмите на замок, чтобы открыть", "Tap the lock to unlock"), -1);
        } else {
            ui.removeCallbacks(hideUnlock);
            fadeOut(unlockDisc);
            showControls(true);
        }
    }

    private void flashUnlock() {
        unlockDisc.setVisibility(View.VISIBLE);
        unlockDisc.setAlpha(1f);
        ui.removeCallbacks(hideUnlock);
        ui.postDelayed(hideUnlock, 2500);
    }

    private static void fadeOut(View v) {
        if (v.getVisibility() != View.VISIBLE) return;
        v.animate().alpha(0f).setDuration(200).withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    /** The centre card: an icon, a big value, a line under it and a bar ({@code level} 0..1, or -1 for none). */
    private void showHud(int icon, String big, @Nullable String small, float level) {
        hudIcon.setImageResource(icon);
        hudBig.setText(big);
        hudSmall.setText(small == null ? "" : small);
        hudSmall.setVisibility(small == null ? View.GONE : View.VISIBLE);
        hudBar.setVisibility(level < 0 ? View.GONE : View.VISIBLE);
        if (level >= 0) hudBar.setProgress(Math.round(Math.max(0, Math.min(1, level)) * 1000));
        hud.animate().cancel();
        if (hud.getVisibility() != View.VISIBLE) {
            hud.setVisibility(View.VISIBLE);
            hud.setAlpha(0f);
            hud.setScaleX(.92f);
            hud.setScaleY(.92f);
            hud.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
        } else hud.setAlpha(1f);
        ui.removeCallbacks(hideHud);
        ui.postDelayed(hideHud, 900);
    }

    private void showPill(String text, @Nullable String action, @Nullable Runnable onAction) {
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(text);
        if (action != null) {
            s.append("   ");
            int a = s.length();
            s.append(action);
            s.setSpan(new android.text.style.ForegroundColorSpan(GalleryUi.accent(this)), a, s.length(), 0);
            s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, s.length(), 0);
        }
        pill.setText(s);
        pill.setOnClickListener(onAction == null ? null : v -> onAction.run());
        pill.setClickable(onAction != null);
        pill.animate().cancel();
        pill.setVisibility(View.VISIBLE);
        pill.setAlpha(0f);
        pill.setTranslationY(GalleryUi.dp(this, 10));
        pill.animate().alpha(1f).translationY(0).setInterpolator(Motion.SHEET).setDuration(240).start();
        ui.removeCallbacks(hidePill);
        ui.postDelayed(hidePill, action != null ? 6000 : 3500);
    }

    // ================================================================== gestures

    private enum Drag { NONE, SEEK, BRIGHTNESS, VOLUME }

    @SuppressLint("ClickableViewAccessibility")
    private void installGestures(View touch) {
        final float edge = GalleryUi.dp(this, 44);
        final Drag[] drag = {Drag.NONE};
        final float[] start = new float[2]; // the value at the drag's start (position ms / brightness / volume)
        final boolean[] scaling = {false}, holding = {false};
        final float[] heldSpeed = {1f};
        final long[] doubleTapAt = {0};
        final int[] doubleTapSum = {0};
        final long[] lastPreview = {0};
        final float[] holdX = {0};
        final boolean[] spedUp = {false}, holdSeek = {false};
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (locked) flashUnlock();
                else showControls(controls.getVisibility() != View.VISIBLE);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (locked || engine == null || !prepared) return true;
                float x = e.getX() / Math.max(1, touch.getWidth());
                if (x > .35f && x < .65f) {
                    togglePlay();
                    showHud(engine.isPlaying() ? R.drawable.ui_ic_play : R.drawable.ui_ic_pause_bars,
                            engine.isPlaying() ? Lang.t("Смотреть", "Play") : Lang.t("Пауза", "Pause"), null, -1);
                    return true;
                }
                int dir = x < .5f ? -1 : 1;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - doubleTapAt[0] > 1200 || Integer.signum(doubleTapSum[0]) != dir) doubleTapSum[0] = 0;
                final int step = seekStep();
                doubleTapSum[0] += dir * step;
                doubleTapAt[0] = now;
                engine.seekTo(Math.max(0, engine.positionMs() + dir * step * 1000L), true);
                showHud(dir < 0 ? R.drawable.ui_ic_undo : R.drawable.ui_ic_redo,
                        (dir < 0 ? "−" : "+") + Math.abs(doubleTapSum[0]) + Lang.t(" с", " s"), null, -1);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (locked || engine == null || !prepared || scaling[0] || drag[0] != Drag.NONE) return;
                // held: 2x while playing; moved sideways afterwards, a seek by how far (MX)
                holding[0] = true;
                holdX[0] = e.getX();
                heldSpeed[0] = speed;
                spedUp[0] = engine.isPlaying();
                if (spedUp[0]) {
                    engine.setSpeed(speed >= 2f ? Math.min(4f, speed * 2) : 2f);
                    showHoldHud();
                }
                touch.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (locked || scaling[0] || holding[0] || e1 == null || engine == null || !prepared) return false;
                float w = Math.max(1, touch.getWidth()), h = Math.max(1, touch.getHeight());
                float mx = e2.getX() - e1.getX(), my = e2.getY() - e1.getY();
                if (drag[0] == Drag.NONE) {
                    if (e1.getY() < edge || e1.getY() > h - edge) return false; // the system's swipe zones
                    if (Math.abs(mx) < GalleryUi.dp(VideoPlayerActivity.this, 14) && Math.abs(my) < GalleryUi.dp(VideoPlayerActivity.this, 14)) return false;
                    if (Math.abs(mx) > Math.abs(my)) {
                        drag[0] = Drag.SEEK;
                        start[0] = engine.positionMs();
                        wasPlayingBeforeDrag = engine.isPlaying();
                        engine.pause();
                    } else if (e1.getX() < w / 2) {
                        drag[0] = Drag.BRIGHTNESS;
                        start[0] = brightness();
                    } else {
                        drag[0] = Drag.VOLUME;
                        start[0] = volumeLevel();
                    }
                }
                switch (drag[0]) {
                    case SEEK:
                        seekPreview(mx / w, start, lastPreview);
                        break;
                    case BRIGHTNESS: {
                        float b = Math.max(.01f, Math.min(1f, start[0] - my / h * 1.3f));
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.screenBrightness = b;
                        getWindow().setAttributes(lp);
                        showHud(R.drawable.ui_ic_sun, Math.round(b * 100) + " %", Lang.t("Яркость", "Brightness"), b);
                        break;
                    }
                    case VOLUME:
                        setVolumeLevel(start[0] - my / h * 1.3f);
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        gd.setIsLongpressEnabled(true);
        ScaleGestureDetector sgd = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float fx, fy;

            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (locked || engine == null) return false;
                scaling[0] = true;
                fx = d.getFocusX();
                fy = d.getFocusY();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoom = Math.max(.5f, Math.min(4f, zoom * d.getScaleFactor()));
                panX += d.getFocusX() - fx;
                panY += d.getFocusY() - fy;
                fx = d.getFocusX();
                fy = d.getFocusY();
                layoutVideo();
                showHud(R.drawable.ui_ic_search, Math.round(zoom * 100) + " %", Lang.t("Масштаб", "Zoom"), -1);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                if (Math.abs(zoom - 1f) < .06f) {
                    zoom = 1f;
                    panX = panY = 0;
                    layoutVideo();
                }
            }
        });
        touch.setOnTouchListener((v, e) -> {
            sgd.onTouchEvent(e);
            if (e.getPointerCount() == 1 && !scaling[0]) gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE && holding[0] && engine != null && e.getPointerCount() == 1) {
                float dx = e.getX() - holdX[0];
                if (!holdSeek[0] && Math.abs(dx) > GalleryUi.dp(this, 16)) {
                    holdSeek[0] = true;
                    if (spedUp[0]) engine.setSpeed(heldSpeed[0]);
                    spedUp[0] = false;
                    drag[0] = Drag.SEEK;
                    start[0] = engine.positionMs();
                    start[1] = start[0];
                    wasPlayingBeforeDrag = engine.isPlaying();
                    engine.pause();
                }
                if (holdSeek[0]) seekPreview(dx / Math.max(1, touch.getWidth()), start, lastPreview);
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (drag[0] == Drag.SEEK && engine != null) {
                    engine.seekTo((long) start[1], true);
                    if (wasPlayingBeforeDrag) engine.play();
                    onPlayStateChanged();
                }
                if (spedUp[0] && engine != null) {
                    engine.setSpeed(heldSpeed[0]);
                    ui.removeCallbacks(hideHud);
                    ui.postDelayed(hideHud, 200);
                }
                holding[0] = spedUp[0] = holdSeek[0] = false;
                drag[0] = Drag.NONE;
                scaling[0] = false;
            }
            return true;
        });
    }

    /** A sideways drag of {@code share} of the width: up to 90 s across the screen (the whole video when shorter), previewed. */
    private void seekPreview(float share, float[] start, long[] lastPreview) {
        VideoEngine e = engine;
        if (e == null) return;
        long dur = e.durationMs();
        float span = Math.min(dur, 90_000);
        long target = (long) Math.max(0, Math.min(dur, start[0] + share * span));
        long delta = target - (long) start[0];
        showHud(delta < 0 ? R.drawable.ui_ic_undo : R.drawable.ui_ic_redo, GalleryVideo.clock(target),
                "[" + (delta < 0 ? "−" : "+") + GalleryVideo.clock(Math.abs(delta)) + "]", dur > 0 ? target / (float) dur : -1);
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPreview[0] > 90) {
            lastPreview[0] = now;
            e.seekTo(target, false);
        }
        start[1] = target;
    }

    /** The double tap's step in seconds (the customise panel: 5 / 10 / 15 / 20 / 30). */
    private int seekStep() {
        return prefs.getInt("seek_step", 10);
    }

    private void showHoldHud() {
        showHud(R.drawable.ui_ic_redo, speedLabel(engine != null ? engine.speed() : 2f), Lang.t("Пока держите", "While held"), -1);
        ui.removeCallbacks(hideHud); // stays while held
    }

    private float brightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try {
            return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (android.provider.Settings.SettingNotFoundException e) {
            return .5f;
        }
    }

    /** 0..1 the system volume, 1..2 the boost over it. */
    private float volumeLevel() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return 1f;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        float v = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        return v >= 1f ? 1f + boostLevel : v;
    }

    private void setVolumeLevel(float level) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        level = Math.max(0f, Math.min(2f, level));
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int step = Math.round(Math.min(1f, level) * max);
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != step) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
            } catch (SecurityException e) {
                Log.w(TAG, "volume: " + e); // Do Not Disturb
            }
        }
        float b = Math.max(0f, level - 1f);
        if (b != boostLevel) {
            boostLevel = b;
            applyBoost();
        }
        if (muted && level > 0) toggleMute();
        String big = level > 1f ? Math.round(level * 100) + " %" : String.valueOf(step);
        showHud(level <= 0 ? R.drawable.ui_ic_mute : R.drawable.ui_ic_sound, big,
                level > 1f ? Lang.t("Усиление звука", "Volume boost") : Lang.t("Громкость", "Volume"), level / 2f);
    }

    private void applyBoost() {
        VideoEngine e = engine;
        if (e == null) return;
        try {
            if (boost == null && boostLevel > 0) boost = new LoudnessEnhancer(e.sessionId);
            if (boost != null) {
                boost.setTargetGain(Math.round(boostLevel * 1000)); // up to +10 dB
                boost.setEnabled(boostLevel > 0);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "boost: " + ex);
        }
    }

    // ================================================================== quick actions

    private void cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length];
        prefs.edit().putString("repeat", repeat.name()).apply();
        bindQuick();
        bindNav();
        showHud(repeat == Repeat.ONE ? R.drawable.ui_ic_repeat_one : R.drawable.ui_ic_repeat, quickLabel("repeat"), null, -1);
    }

    private void toggleBackground() {
        backgroundOn = !backgroundOn;
        prefs.edit().putBoolean("background", backgroundOn).apply();
        bindQuick();
        showHud(R.drawable.ui_ic_headphones, backgroundOn ? Lang.t("Фоновое воспроизведение", "Background play") : Lang.t("Без фона", "No background play"),
                backgroundOn ? Lang.t("Звук продолжится, когда вы выйдете", "The sound goes on when you leave") : null, -1);
    }

    private void cycleRotate() {
        rotate = Rotate.values()[(rotate.ordinal() + 1) % Rotate.values().length];
        prefs.edit().putString("rotate", rotate.name()).apply();
        applyRotation();
        bindQuick();
        showHud(R.drawable.ui_ic_rotate, rotateName(rotate), null, -1);
    }

    private static String rotateName(Rotate r) {
        switch (r) {
            case SENSOR:
                return Lang.t("Автоповорот", "Auto-rotate");
            case LANDSCAPE:
                return Lang.t("Альбомная", "Landscape");
            case PORTRAIT:
                return Lang.t("Портретная", "Portrait");
            default:
                return Lang.t("По видео", "By the video");
        }
    }

    private void toggleMute() {
        muted = !muted;
        if (engine != null) engine.setVolume(muted ? 0f : 1f);
        bindQuick();
        showHud(muted ? R.drawable.ui_ic_mute : R.drawable.ui_ic_sound, muted ? Lang.t("Без звука", "Muted") : Lang.t("Звук включён", "Sound on"), null, -1);
    }

    private void cycleFit() {
        fit = Fit.values()[(fit.ordinal() + 1) % Fit.values().length];
        prefs.edit().putString("fit", fit.name()).apply();
        zoom = 1f;
        panX = panY = 0;
        layoutVideo();
        showHud(R.drawable.ui_ic_aspect, fitName(fit), null, -1);
    }

    private static String fitName(Fit f) {
        switch (f) {
            case CROP:
                return Lang.t("Заполнить", "Fill");
            case STRETCH:
                return Lang.t("Растянуть", "Stretch");
            case ORIGINAL:
                return "100 %";
            case R16_9:
                return "16:9";
            case R4_3:
                return "4:3";
            default:
                return Lang.t("Вписать", "Fit");
        }
    }

    private String quickLabel(String id) {
        for (Quick q : quicks) if (q.id.equals(id)) return q.label.getText().toString();
        return "";
    }

    private void screenshot() {
        VideoEngine e = engine;
        if (e == null || e.width() <= 0 || surface == null || Build.VERSION.SDK_INT < 24) return;
        int w = e.width(), h = e.height();
        float s = Math.min(1f, 8192f / Math.max(w, h));
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), Bitmap.Config.ARGB_8888);
        long at = e.positionMs();
        PixelCopy.request(surfaceView, bmp, result -> {
            if (result != PixelCopy.SUCCESS) {
                showPill(Lang.t("Не удалось сделать скриншот", "Cannot take the screenshot"), null, null);
                return;
            }
            String name = stem(names.get(index)) + "_" + GalleryVideo.clock(at).replace(':', '-') + ".jpg";
            new Thread(() -> {
                boolean ok = saveJpeg(bmp, name);
                ui.post(() -> showPill(ok ? Lang.t("Скриншот сохранён: Pictures/SCAMERA", "Screenshot saved: Pictures/SCAMERA")
                        : Lang.t("Не удалось сохранить скриншот", "Cannot save the screenshot"), null, null));
            }, "video-shot").start();
        }, ui);
        View flash = new View(this);
        flash.setBackgroundColor(0xFFFFFFFF);
        root.addView(flash, match());
        flash.animate().alpha(0f).setDuration(260).withEndAction(() -> root.removeView(flash)).start();
    }

    private boolean saveJpeg(Bitmap bmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SCAMERA");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(u)) {
                    if (out == null || !bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) return false;
                }
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(u, v, null, null);
                return true;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SCAMERA");
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) return false;
            }
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "screenshot: " + e);
            return false;
        } finally {
            bmp.recycle();
        }
    }

    // ================================================================== picture-in-picture

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26 || engine == null) return;
        try {
            enterPictureInPictureMode(pipParams());
        } catch (RuntimeException e) {
            Log.w(TAG, "pip: " + e);
        }
    }

    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        VideoEngine e = engine;
        if (e != null && e.width() > 0 && e.height() > 0) {
            float r = Math.max(1 / 2.39f, Math.min(2.39f, e.width() / (float) e.height()));
            b.setAspectRatio(r >= 1 ? new Rational(Math.round(r * 1000), 1000) : new Rational(1000, Math.round(1000 / r)));
        }
        List<RemoteAction> actions = new ArrayList<>();
        boolean playing = e != null && e.isPlaying();
        actions.add(pipAction("prev", R.drawable.ui_ic_prev, Lang.t("Предыдущее", "Previous"), 1));
        actions.add(pipAction("toggle", playing ? R.drawable.ui_ic_pause_bars : R.drawable.ui_ic_play, playing ? Lang.t("Пауза", "Pause") : Lang.t("Смотреть", "Play"), 2));
        actions.add(pipAction("next", R.drawable.ui_ic_next, Lang.t("Следующее", "Next"), 3));
        b.setActions(actions);
        return b.build();
    }

    private RemoteAction pipAction(String cmd, int icon, String label, int code) {
        PendingIntent pi = PendingIntent.getBroadcast(this, code, new Intent(ACTION_PIP).setPackage(getPackageName()).putExtra("cmd", cmd),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(this, icon), label, label, pi);
    }

    private void updatePip() {
        if (Build.VERSION.SDK_INT >= 26 && pip) {
            try {
                setPictureInPictureParams(pipParams());
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        pip = inPip;
        if (inPip) {
            closePanel();
            controls.setVisibility(View.GONE);
            hud.setVisibility(View.GONE);
            pill.setVisibility(View.GONE);
            unlockDisc.setVisibility(View.GONE);
            subs.setTextSize(11);
            subs.setTranslationY(0);
        } else {
            subs.setTextSize(subSize());
            showControls(true);
        }
        root.post(this::layoutVideo);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (prefs.getBoolean("pip_on_home", false) && engine != null && engine.isPlaying()) enterPip();
    }

    // ================================================================== panels

    private LinearLayout panelCard(String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GalleryUi.card(this, GalleryUi.SHEET, 34)); // P579: the floating sheet, 34dp corners
        card.setPadding(GalleryUi.dp(this, 16), GalleryUi.dp(this, 18), GalleryUi.dp(this, 16), GalleryUi.dp(this, 14));
        card.setClickable(true); // taps inside do not close it
        TextView t = GalleryUi.text(this, heading, 17, GalleryUi.TEXT);
        t.setTypeface(GalleryUi.semibold());
        t.setAccessibilityHeading(true);
        t.setPadding(GalleryUi.dp(this, 4), 0, 0, GalleryUi.dp(this, 10));
        card.addView(t);
        return card;
    }

    private void showPanel(LinearLayout card) {
        panelLayer.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(card);
        int w = Math.min(root.getWidth() - GalleryUi.dp(this, 32), GalleryUi.dp(this, 440));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.topMargin = lp.bottomMargin = GalleryUi.dp(this, 16);
        panelLayer.addView(scroll, lp);
        panelLayer.setVisibility(View.VISIBLE);
        panelLayer.setAlpha(0f);
        panelLayer.animate().alpha(1f).setDuration(160).start();
        scroll.setScaleX(.94f);
        scroll.setScaleY(.94f);
        scroll.animate().scaleX(1f).scaleY(1f).setInterpolator(Motion.SHEET).setDuration(240).start();
        ui.removeCallbacks(autoHide);
    }

    private void closePanel() {
        if (panelLayer.getVisibility() != View.VISIBLE) return;
        panelLayer.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            panelLayer.setVisibility(View.GONE);
            panelLayer.removeAllViews();
        }).start();
        scheduleHide();
    }

    /** A choice row: the title and a grey line under it; P579: the chosen one ticked in the accent at the end (no radio). */
    private View optionRow(String main, @Nullable String under, boolean chosen, Runnable pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(GalleryUi.dp(this, 48));
        row.setPadding(GalleryUi.dp(this, 12), GalleryUi.dp(this, 10), GalleryUi.dp(this, 12), GalleryUi.dp(this, 10));
        row.setBackground(GalleryUi.pressable(GalleryUi.card(this, 0, 16)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(GalleryUi.text(this, main, 16, GalleryUi.TEXT));
        if (under != null) {
            TextView u = GalleryUi.text(this, under, 13, GalleryUi.MUTED);
            u.setPadding(0, GalleryUi.dp(this, 2), 0, 0);
            texts.addView(u);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView tick = GalleryUi.tick(this, chosen);
        ((LinearLayout.LayoutParams) tick.getLayoutParams()).setMarginStart(GalleryUi.dp(this, 12));
        row.addView(tick);
        row.setSelected(chosen);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(main + (under != null ? ", " + under : ""));
        ViewCompat.setStateDescription(row, chosen ? Lang.t("выбрано", "selected") : null);
        row.setOnClickListener(v -> {
            pick.run();
            closePanel();
        });
        Motion.press(row, .98f);
        return row;
    }

    private View actionRow(int icon, String main, Runnable run) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(GalleryUi.dp(this, 48));
        row.setPadding(GalleryUi.dp(this, 12), GalleryUi.dp(this, 10), GalleryUi.dp(this, 12), GalleryUi.dp(this, 10));
        row.setBackground(GalleryUi.pressable(GalleryUi.card(this, 0, 16)));
        row.addView(GalleryUi.icon(this, icon, GalleryUi.accent(this), 22));
        TextView t = GalleryUi.text(this, main, 16, GalleryUi.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = GalleryUi.dp(this, 14);
        row.addView(t, tp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(main);
        row.setOnClickListener(v -> {
            closePanel();
            run.run();
        });
        Motion.press(row, .98f);
        return row;
    }

    private void openDecoderPanel() {
        LinearLayout card = panelCard(Lang.t("Декодер", "Decoder"));
        VideoEngine.Decoder now = engine != null ? engine.decoder() : enumPref("decoder", VideoEngine.Decoder.HW_PLUS);
        String used = engine != null ? engine.decoderName() : "";
        card.addView(optionRow("HW", Lang.t("Аппаратный: быстрый и экономный", "Hardware: fast and frugal"), now == VideoEngine.Decoder.HW,
                () -> setDecoder(VideoEngine.Decoder.HW)));
        card.addView(optionRow("HW+", Lang.t("Аппаратный, при сбое — программный (по умолчанию)", "Hardware, software on a failure (default)"),
                now == VideoEngine.Decoder.HW_PLUS, () -> setDecoder(VideoEngine.Decoder.HW_PLUS)));
        card.addView(optionRow("SW", Lang.t("Программный: на процессоре, для проблемных файлов", "Software: on the CPU, for troublesome files"),
                now == VideoEngine.Decoder.SW, () -> setDecoder(VideoEngine.Decoder.SW)));
        if (!used.isEmpty()) {
            TextView t = GalleryUi.text(this, Lang.t("Сейчас: ", "In use: ") + used, 12.5f, GalleryUi.MUTED);
            t.setPadding(GalleryUi.dp(this, 12), GalleryUi.dp(this, 8), 0, 0);
            card.addView(t);
        }
        showPanel(card);
    }

    private void setDecoder(VideoEngine.Decoder d) {
        prefs.edit().putString("decoder", d.name()).apply();
        if (engine != null) engine.setDecoder(d);
        bindDecoderPill();
    }

    private void openAudioPanel() {
        LinearLayout card = panelCard(Lang.t("Звуковая дорожка", "Sound track"));
        VideoEngine e = engine;
        List<VideoEngine.Track> tracks = e != null ? e.audioTracks() : Collections.emptyList();
        int now = e != null ? e.audioIndex() : -1;
        if (tracks.isEmpty()) card.addView(GalleryUi.text(this, Lang.t("В видео нет звука", "The video has no sound"), 14, GalleryUi.MUTED));
        for (int i = 0; i < tracks.size(); i++) {
            VideoEngine.Track t = tracks.get(i);
            final int k = i;
            card.addView(optionRow(Lang.t("Дорожка ", "Track ") + (i + 1) + (t.language != null ? " · " + t.language : ""), audioLine(t), k == now, () -> {
                if (engine != null) engine.setAudioTrack(k);
            }));
        }
        if (!tracks.isEmpty())
            card.addView(optionRow(Lang.t("Выключить звук", "No sound"), null, now < 0, () -> {
                if (engine != null) engine.setAudioTrack(-1);
            }));
        if (e != null && e.audioSuspended()) {
            TextView t = GalleryUi.text(this, Lang.t("На этой скорости звук недоступен", "No sound at this speed"), 12.5f, GalleryUi.WARN);
            t.setPadding(GalleryUi.dp(this, 12), GalleryUi.dp(this, 8), 0, 0);
            card.addView(t);
        }
        showPanel(card);
    }

    private static String audioLine(VideoEngine.Track t) {
        StringBuilder s = new StringBuilder(VideoEngine.codecLabel(t.mime));
        int rate = t.integer(MediaFormat.KEY_SAMPLE_RATE, 0), ch = t.integer(MediaFormat.KEY_CHANNEL_COUNT, 0);
        if (rate > 0) s.append(" · ").append(GalleryUi.decimal(rate / 1000.0, 1)).append(Lang.t(" кГц", " kHz"));
        if (ch == 1) s.append(Lang.t(" · моно", " · mono"));
        else if (ch == 2) s.append(Lang.t(" · стерео", " · stereo"));
        else if (ch > 2) s.append(" · ").append(ch).append(Lang.t(" кан.", " ch"));
        return s.toString();
    }

    private void openSubsPanel() {
        LinearLayout card = panelCard(Lang.t("Субтитры", "Subtitles"));
        card.addView(optionRow(Lang.t("Выключены", "Off"), null, subsChoice.equals("off"), () -> {
            subsChoice = "off";
            cues = Collections.emptyList();
            subs.setText("");
        }));
        VideoEngine e = engine;
        List<VideoEngine.Track> tracks = e != null ? e.textTracks() : Collections.emptyList();
        for (int i = 0; i < tracks.size(); i++) {
            VideoEngine.Track t = tracks.get(i);
            final int k = i;
            card.addView(optionRow(Lang.t("Встроенные ", "Built-in ") + (i + 1) + (t.language != null ? " · " + t.language : ""),
                    VideoEngine.codecLabel(t.mime), subsChoice.equals("track:" + i), () -> loadSubsTrack(k)));
        }
        File beside = Subtitles.besideVideo(paths.get(index));
        if (beside != null)
            card.addView(optionRow(beside.getName(), Lang.t("Файл рядом с видео", "A file beside the video"),
                    subsChoice.equals("file:" + Uri.fromFile(beside)), () -> loadSubsFile(Uri.fromFile(beside))));
        if (subsFile != null && !subsFile.getScheme().equals("file"))
            card.addView(optionRow(Lang.t("Выбранный файл", "The picked file"), subsFile.getLastPathSegment(), subsChoice.equals("file:" + subsFile),
                    () -> loadSubsFile(subsFile)));
        card.addView(actionRow(R.drawable.ui_ic_folder, Lang.t("Открыть файл субтитров…", "Open a subtitle file…"), () -> {
            try {
                pickSubs.launch(new String[]{"application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*"});
            } catch (RuntimeException ex) {
                showPill(Lang.t("Нет приложения для выбора файла", "No app to pick a file"), null, null);
            }
        }));
        TextView label = GalleryUi.text(this, Lang.t("Размер", "Size"), 13, GalleryUi.MUTED);
        label.setPadding(GalleryUi.dp(this, 4), GalleryUi.dp(this, 12), 0, GalleryUi.dp(this, 6));
        card.addView(label);
        LinearLayout sizes = new LinearLayout(this);
        String[] sz = {Lang.t("Мелкий", "Small"), Lang.t("Средний", "Medium"), Lang.t("Крупный", "Large")};
        int chosen = prefs.getInt("sub_size", 1);
        for (int i = 0; i < sz.length; i++) {
            final int k = i;
            TextView chip = GalleryUi.chip(this, sz[i], -1, i == chosen);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt("sub_size", k).apply();
                subs.setTextSize(subSize());
                closePanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = GalleryUi.dp(this, 8);
            sizes.addView(chip, lp);
        }
        card.addView(sizes);
        showPanel(card);
    }

    private float subSize() {
        int s = prefs.getInt("sub_size", 1);
        return s == 0 ? 16 : s == 2 ? 26 : 20;
    }

    /** The first in-file text track, or a .srt / .vtt beside the video, comes on by itself. */
    private void autoSubs() {
        VideoEngine e = engine;
        if (e == null) return;
        File beside = Subtitles.besideVideo(paths.get(index));
        if (beside != null) loadSubsFile(Uri.fromFile(beside));
        else if (!e.textTracks().isEmpty()) loadSubsTrack(0);
    }

    private void loadSubsTrack(int k) {
        VideoEngine e = engine;
        if (e == null || k >= e.textTracks().size()) return;
        VideoEngine.Track t = e.textTracks().get(k);
        Uri video = e.uri;
        subsChoice = "track:" + k;
        new Thread(() -> {
            try {
                List<Subtitles.Cue> c = Subtitles.readTrack(this, video, t);
                ui.post(() -> {
                    if (engine != null && engine.uri.equals(video) && subsChoice.equals("track:" + k)) cues = c;
                });
            } catch (Exception ex) {
                Log.w(TAG, "subtitle track: " + ex);
            }
        }, "video-subs").start();
    }

    private void loadSubsFile(Uri u) {
        VideoEngine e = engine;
        if (e == null) return;
        Uri video = e.uri;
        subsChoice = "file:" + u;
        if (!"file".equals(u.getScheme())) subsFile = u;
        new Thread(() -> {
            try {
                List<Subtitles.Cue> c = Subtitles.readFile(this, u);
                ui.post(() -> {
                    if (engine == null || !engine.uri.equals(video)) return;
                    cues = c;
                    if (c.isEmpty()) showPill(Lang.t("В файле нет субтитров", "No subtitles in the file"), null, null);
                });
            } catch (Exception ex) {
                Log.w(TAG, "subtitle file: " + ex);
                ui.post(() -> showPill(Lang.t("Не удалось прочитать субтитры", "Cannot read the subtitles"), null, null));
            }
        }, "video-subs").start();
    }

    private void openSpeedPanel() {
        LinearLayout card = panelCard(Lang.t("Скорость", "Speed"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView value = GalleryUi.text(this, speedLabel(speed), 26, GalleryUi.TEXT);
        value.setTypeface(GalleryUi.semibold());
        value.setGravity(Gravity.CENTER);
        // P579: the shared slider, 0.25 .. 4.00 in 0.05 steps (a double tap goes back to 1x)
        com.particlesdevs.photoncamera.ui.controls.IosSlider bar = new com.particlesdevs.photoncamera.ui.controls.IosSlider(this);
        bar.setRange(.25f, 4f, .05f);
        bar.setDefaultValue(1f);
        bar.setValueNow(speed);
        bar.setContentDescription(Lang.t("Скорость", "Speed"));
        View minus = disc(this, R.drawable.ui_ic_minus, Lang.t("Медленнее", "Slower"), v -> bar.setValue(bar.getValue() - .05f));
        View plus = disc(this, R.drawable.ui_ic_plus, Lang.t("Быстрее", "Faster"), v -> bar.setValue(bar.getValue() + .05f));
        row.addView(minus, size(this, 44));
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(plus, size(this, 44));
        card.addView(row);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(this, 40));
        bp.topMargin = GalleryUi.dp(this, 8);
        bp.leftMargin = bp.rightMargin = GalleryUi.dp(this, 4);
        card.addView(bar, bp);
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presets = new LinearLayout(this);
        presetScroll.addView(presets);
        float[] values = {.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f};
        for (float v : values) {
            TextView chip = GalleryUi.chip(this, speedLabel(v), -1, false);
            chip.setOnClickListener(x -> bar.setValue(v));
            Motion.press(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = GalleryUi.dp(this, 8);
            presets.addView(chip, lp);
        }
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = GalleryUi.dp(this, 6);
        card.addView(presetScroll, pp);
        bar.setListener(new com.particlesdevs.photoncamera.ui.controls.IosSlider.Listener() {
            @Override
            public void onValue(com.particlesdevs.photoncamera.ui.controls.IosSlider s, float v, boolean fromUser) {
                speed = Math.round(v * 100) / 100f;
                value.setText(speedLabel(speed));
                if (engine != null) engine.setSpeed(speed);
                bindQuick();
            }

            @Override
            public void onTracking(com.particlesdevs.photoncamera.ui.controls.IosSlider s, boolean tracking) {
                if (!tracking) com.particlesdevs.photoncamera.ui.controls.IosMotion.pop(value);
            }
        });
        card.addView(actionRow(R.drawable.ui_ic_undo, Lang.t("Сбросить на 1x", "Reset to 1x"), () -> {
            speed = 1f;
            if (engine != null) engine.setSpeed(1f);
            bindQuick();
        }));
        showPanel(card);
    }

    private void openSleepPanel() {
        LinearLayout card = panelCard(Lang.t("Таймер сна", "Sleep timer"));
        card.addView(optionRow(Lang.t("Выключен", "Off"), null, sleepAt == 0, () -> setSleep(0)));
        int[] mins = {15, 30, 45, 60, 90};
        for (int m : mins)
            card.addView(optionRow(m + Lang.t(" мин", " min"), null, false, () -> setSleep(m)));
        card.addView(optionRow(Lang.t("В конце видео", "At the end of the video"), null, sleepAt == -1, () -> setSleep(-1)));
        showPanel(card);
    }

    private void setSleep(int minutes) {
        sleepAt = minutes < 0 ? -1 : minutes == 0 ? 0 : android.os.SystemClock.elapsedRealtime() + minutes * 60_000L;
        bindQuick();
        if (minutes != 0)
            showHud(R.drawable.ui_ic_moon, minutes < 0 ? Lang.t("В конце видео", "At the end") : minutes + Lang.t(" мин", " min"),
                    Lang.t("Потом пауза", "Then pause"), -1);
    }

    private void openCustomizePanel() {
        LinearLayout card = panelCard(Lang.t("Настройки плеера", "Player settings"));
        Set<String> hidden = hiddenQuicks();
        for (Quick q : quicks) {
            if (q.id.equals("customize")) continue;
            boolean on = !hidden.contains(q.id);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(GalleryUi.dp(this, 48));
            row.setPadding(GalleryUi.dp(this, 12), GalleryUi.dp(this, 6), GalleryUi.dp(this, 8), GalleryUi.dp(this, 6));
            row.setBackground(GalleryUi.pressable(GalleryUi.card(this, 0, 16)));
            TextView t = GalleryUi.text(this, q.baseLabel, 16, GalleryUi.TEXT);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            View sw = com.particlesdevs.photoncamera.gallery.ui.GallerySheets.toggleView(this, on);
            row.addView(sw);
            row.setTag(on);
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(q.baseLabel);
            ViewCompat.setStateDescription(row, on ? Lang.t("показана", "shown") : Lang.t("скрыта", "hidden"));
            row.setOnClickListener(v -> {
                boolean now = !(Boolean) row.getTag();
                row.setTag(now);
                com.particlesdevs.photoncamera.gallery.ui.GallerySheets.setToggle(sw, now);
                Set<String> h = hiddenQuicks();
                if (now) h.remove(q.id);
                else h.add(q.id);
                prefs.edit().putStringSet("hidden", h).apply();
                bindQuick();
            });
            card.addView(row);
        }
        TextView stepLabel = GalleryUi.text(this, Lang.t("Двойное касание: перемотка на", "Double tap seeks by"), 13, GalleryUi.MUTED);
        stepLabel.setPadding(GalleryUi.dp(this, 4), GalleryUi.dp(this, 14), 0, GalleryUi.dp(this, 8));
        card.addView(stepLabel);
        // P579: the step on the shared segmented control (its thumb glides; the choice is stored at once)
        final int[] stepValues = {5, 10, 15, 20, 30, 60};
        String[] stepNames = new String[stepValues.length];
        int stepChosen = -1;
        for (int i = 0; i < stepValues.length; i++) {
            stepNames[i] = stepValues[i] + Lang.t(" с", " s");
            if (stepValues[i] == seekStep()) stepChosen = i;
        }
        card.addView(GalleryUi.segmented(this, stepNames, stepChosen, i -> prefs.edit().putInt("seek_step", stepValues[i]).apply()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean pipHome = prefs.getBoolean("pip_on_home", false);
        if (Build.VERSION.SDK_INT >= 26) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(GalleryUi.dp(this, 12), GalleryUi.dp(this, 14), GalleryUi.dp(this, 8), GalleryUi.dp(this, 10));
            row.addView(GalleryUi.text(this, Lang.t("Картинка в картинке при выходе", "Picture-in-picture on leaving"), 16, GalleryUi.TEXT),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            View sw = com.particlesdevs.photoncamera.gallery.ui.GallerySheets.toggleView(this, pipHome);
            row.addView(sw);
            row.setTag(pipHome);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                boolean now = !(Boolean) row.getTag();
                row.setTag(now);
                com.particlesdevs.photoncamera.gallery.ui.GallerySheets.setToggle(sw, now);
                prefs.edit().putBoolean("pip_on_home", now).apply();
            });
            card.addView(row);
        }
        showPanel(card);
    }

    private void openMorePanel() {
        LinearLayout card = panelCard(Lang.t("Ещё", "More"));
        card.addView(actionRow(R.drawable.ui_ic_music, Lang.t("Звуковая дорожка", "Sound track"), this::openAudioPanel));
        card.addView(actionRow(R.drawable.ui_ic_subtitles, Lang.t("Субтитры", "Subtitles"), this::openSubsPanel));
        card.addView(actionRow(R.drawable.ui_ic_info, Lang.t("Сведения о видео", "Video details"), this::openInfoPanel));
        card.addView(actionRow(R.drawable.ui_ic_share, Lang.t("Поделиться", "Share"), () -> {
            Intent s = new Intent(Intent.ACTION_SEND).setType(GalleryVideo.mime(names.get(index)))
                    .putExtra(Intent.EXTRA_STREAM, uris.get(index)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startSafely(Intent.createChooser(s, Lang.t("Поделиться видео", "Share the video")));
        }));
        card.addView(actionRow(R.drawable.ui_ic_open, Lang.t("Открыть в другом приложении", "Open in another app"), () -> {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(uris.get(index), GalleryVideo.mime(names.get(index)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            pausePlayback();
            startSafely(Intent.createChooser(v, Lang.t("Открыть в", "Open with")));
        }));
        card.addView(actionRow(R.drawable.ui_ic_sliders, Lang.t("Настройки плеера", "Player settings"), this::openCustomizePanel));
        showPanel(card);
    }

    private void startSafely(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            showPill(Lang.t("Нет подходящего приложения", "No app for this"), null, null);
        }
    }

    private void openInfoPanel() {
        LinearLayout card = panelCard(Lang.t("Сведения о видео", "Video details"));
        VideoEngine e = engine;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{Lang.t("Файл", "File"), names.get(index)});
        if (!paths.get(index).isEmpty()) rows.add(new String[]{Lang.t("Путь", "Path"), paths.get(index)});
        long bytes = fileSize();
        if (bytes > 0) rows.add(new String[]{Lang.t("Размер", "Size"), GalleryUi.size(bytes)});
        if (e != null) {
            rows.add(new String[]{Lang.t("Длительность", "Length"), GalleryVideo.clock(e.durationMs())});
            VideoEngine.Track v = e.videoTrack();
            if (v != null) {
                StringBuilder s = new StringBuilder(VideoEngine.codecLabel(v.mime)).append(" · ").append(e.width()).append('×').append(e.height());
                int fps = v.integer(MediaFormat.KEY_FRAME_RATE, 0);
                if (fps > 0) s.append(" · ").append(fps).append(Lang.t(" к/с", " fps"));
                if (hdr(v)) s.append(" · HDR");
                rows.add(new String[]{Lang.t("Видео", "Video"), s.toString()});
                if (bytes > 0 && e.durationMs() > 0)
                    rows.add(new String[]{Lang.t("Поток", "Bit rate"), GalleryUi.decimal(bytes * 8.0 / e.durationMs() / 1000.0, 1) + Lang.t(" Мбит/с", " Mbit/s")});
            }
            for (int i = 0; i < e.audioTracks().size(); i++)
                rows.add(new String[]{Lang.t("Звук ", "Sound ") + (i + 1), audioLine(e.audioTracks().get(i))});
            if (!e.decoderName().isEmpty()) rows.add(new String[]{Lang.t("Декодер", "Decoder"), e.decoderName()});
            rows.add(new String[]{Lang.t("Пропущено кадров", "Dropped frames"), String.valueOf(e.droppedFrames())});
        }
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(GalleryUi.dp(this, 6), GalleryUi.dp(this, 6), GalleryUi.dp(this, 6), GalleryUi.dp(this, 6));
            row.addView(GalleryUi.text(this, r[0], 12.5f, GalleryUi.MUTED));
            TextView v = GalleryUi.text(this, r[1], 15, GalleryUi.TEXT);
            v.setTextIsSelectable(true);
            row.addView(v);
            card.addView(row);
        }
        showPanel(card);
    }

    private long fileSize() {
        try (android.database.Cursor c = getContentResolver().query(uris.get(index), new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    private <E extends Enum<E>> E enumPref(String key, E fallback) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), prefs.getString(key, fallback.name()));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
