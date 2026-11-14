package com.scamera.app.gallery.ui.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.Observable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.viewpager.widget.ViewPager;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.scamera.app.R;
import com.scamera.app.gallery.adapters.DepthPageTransformer;
import com.scamera.app.gallery.adapters.ImageAdapter;
import com.scamera.app.gallery.ui.CameraHandoff;
import com.scamera.app.gallery.ui.GalleryOpenTrace;
import com.scamera.app.gallery.ui.ViewerCover;
import com.scamera.app.gallery.compare.SSIVListener;
import com.scamera.app.gallery.files.GalleryFileOperations;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.helper.Constants;
import com.scamera.app.gallery.helper.UltraHdrGalleryUtil;
import com.scamera.app.gallery.model.ExifDialogModel;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.files.GalleryVideo;
import com.scamera.app.gallery.model.GalleryView;
import com.scamera.app.gallery.ui.GalleryFormat;
import com.scamera.app.gallery.ui.GalleryMotion;
import com.scamera.app.gallery.ui.GalleryUndo;
import com.scamera.app.ui.Motion;
import com.scamera.app.gallery.ui.GallerySheets;
import com.scamera.app.gallery.ui.GalleryUi;
import com.scamera.app.gallery.ui.ViewerChrome;
import com.scamera.app.gallery.viewmodel.ExifDialogViewModel;
import com.scamera.app.gallery.viewmodel.GalleryViewModel;
import com.scamera.app.gallery.views.CustomSSIV;
import com.scamera.app.gallery.views.Histogram;
import com.scamera.app.processing.ImagePath;
import com.scamera.app.util.Lang;

import org.apache.commons.io.FileUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Created by Vibhor Srivastava on 02-Dec-2020
 *
 * <p>P59b: the viewer in the card style (GALLERY_TASK.md §2): top row (back, «Сегодня, 11:17 / 24 мм · ISO 100 · 1/250»,
 * the [route | format] card), the «HDR вкл / выкл» pill, the filmstrip and button cards; swipe changes the photo
 * (DepthPageTransformer), double tap zooms to 2.5x and back with a «250 %» pill, pinch as before, a tap hides / shows all
 * controls (a mini-EXIF pill while hidden; they are visible again the next time the viewer opens), a swipe down at 1x
 * closes the viewer. In compare mode (a pane of ImageCompareFragment) only the photo, its label card and the zoom pill.
 * The Ultra HDR, colour-mode, edit, share and delete logic is the one before.
 * <p>P498 (owner, 2026-11-04: «полноценная галерея», «красивые анимации», videos): the photo opens out of its thumbnail and folds
 * back into it, the bars slide off and back, a heart («Избранное») and «Ещё» (slideshow, set as, open with) in the top row, this
 * app's own files deleted with an undo instead of the confirmation, and a video page - its poster frame with a play button - plays
 * in {@link com.scamera.app.gallery.video.VideoPlayerActivity}.
 */
public class ImageViewerFragment extends Fragment implements ImageAdapter.HdrStateListener {
    private static final String TAG = ImageViewerFragment.class.getSimpleName();
    private List<GalleryItem> photos = new ArrayList<>(0);
    private Set<String> rawBases = new java.util.HashSet<>();
    private ExifDialogViewModel factsModel;
    private ViewPager pagerView;
    private ImageAdapter pageAdapter;
    private NavController navigation;
    private FrameLayout root;
    private ViewerChrome chrome;
    private TextView zoomPill, miniExif, paneLabel, paneZoom;
    private LinearLayout overlays;
    private boolean chromeVisible = true;
    /**
     * P196: the compare panes' label shows the full info (time, ISO, shutter, aperture, focal length, format; resolution and
     * phone; file name) by default, and a tap on either pane switches BOTH between that and «time · ISO». Before it was one
     * flag per pane, off by default: after a tap on one pane the other still showed only «11:55 · ISO 50» (owner's
     * screenshot, 2026-10-17), which read as a photo without shutter / aperture / focal length.
     */
    static boolean paneFullExif = true;
    private String screenMode;
    private int shownPage = 0;
    private int hdrPage = -1;
    private Histogram histogramView;
    private Observable.OnPropertyChangedCallback histogramCallback;
    /** P492: the histogram overlay (the viewer's switch; on until it is switched off, remembered) and its model listener. */
    private Histogram liveHistogram;
    private FrameLayout liveHistogramCard;
    private Observable.OnPropertyChangedCallback liveHistogramCallback;
    private boolean histogramOn;
    private static final String UI_PREFS = "gallery_ui", KEY_HISTOGRAM = "show_histogram";
    /** P498: the play button over a video page (P499: it opens the gallery's own player). */
    private View playDisc;
    /** P498: the slideshow (3 s a photo, a tap stops it). */
    private boolean slideshow;
    private final Runnable slideTick = this::nextSlide;
    static final long SLIDE_MS = 3000;
    private SSIVListener zoomListener = new SSIVListener() {
        @Override
        public void onScaleChanged(float scale, int source) {
            updateScaleText();
        }

        @Override
        public void onCenterChanged(PointF centre, int source) {
        }

        @Override
        public void onTouched(int pointer) {
        }
    };
    private int deletingAt = -1;
    private GalleryViewModel library;
    /** The shown photo's position across a recreation (rotation, process death). */
    static final String KEY_SEEK = "viewer_seek";

    /**
     * The position is taken once per fragment instance: the view is built again on the same instance when the editor or compare
     * is left, and the arguments (the photo first tapped in the grid) then sent the viewer back to that photo, not to the one
     * edited. A recreated instance starts at its saved position (it opened on the newest photo or the first-tapped one).
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shownPage = startPosition(savedInstanceState, getArguments());
    }

    /** The position a new viewer instance starts at: its saved one, else the arguments' one, else the first photo. */
    static int startPosition(@Nullable Bundle saved, @Nullable Bundle args) {
        if (saved != null && saved.containsKey(KEY_SEEK)) return saved.getInt(KEY_SEEK, 0);
        return args != null ? args.getInt(Constants.ARG_POSITION, 0) : 0;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SEEK, pagerView != null ? current() : shownPage);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle bundle = getArguments();
        if (bundle != null) screenMode = bundle.getString(Constants.ARG_MODE);
        Context c = requireContext();
        library = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        factsModel = new ViewModelProvider(this).get(ExifDialogViewModel.class);
        navigation = NavHostFragment.findNavController(this);
        root = new FrameLayout(c);
        root.setBackgroundColor(0xFF000000);
        pagerView = new ViewPager(c);
        pagerView.setId(View.generateViewId());
        root.addView(pagerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (isCompareMode()) buildPaneOverlay(c);
        else {
            // P637: over the pages, under the bars
            cover = new ViewerCover(c);
            root.addView(cover.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // a single photo shown first (the camera's last one, the one another app asked for) and the lists read under it
            seamlessUntil = android.os.SystemClock.uptimeMillis() + SEAMLESS_MS;
            buildChrome(c);
            beginOpening(library.takeOpening());
        }
        library.getCurrentFolderImages().observe(getViewLifecycleOwner(), this::bindPhotos);
        return root;
    }

    // ---------------------------------------------------------------- P637: opened by the camera's thumbnail

    /** P637: the placeholder over the pages (null in compare mode). */
    @Nullable private ViewerCover cover;
    /** P637: this viewer's gallery was opened by the camera's thumbnail: it hears the camera's saves. */
    private boolean cameraOpened;
    /** P637: until then (uptime ms) a new list under a shown page is bound behind a copy of that page (no black flash). */
    private long seamlessUntil;
    /** P637: the saved shot's page is awaited («final photo ready» in the log). */
    private boolean awaitingFinal;
    /** P637: the shot the viewer was opened for was saved before this view could wait for it: its file is the one to show. */
    private boolean expectSaved;
    private int idlePolls;
    @Nullable private String savedPath;
    private final Runnable pendingPoll = this::pollPending, applySaved = this::applySaved, coverTimeout = this::coverTimedOut;
    static final long PENDING_POLL_MS = 500, SAVED_SETTLE_MS = 60, COVER_MAX_MS = 5000, SEAMLESS_MS = 20_000;

    private final CameraHandoff.Listener handoffListener = new CameraHandoff.Listener() {
        @Override
        public void saved(String path) {
            if (root == null) return;
            // the photo format comes last (a RAW of the same shot a moment before it): one rebind for the shot
            savedPath = path;
            root.removeCallbacks(applySaved);
            root.postDelayed(applySaved, SAVED_SETTLE_MS);
        }

        @Override
        public void scanned(String path, Uri row) {
            if (root == null || library == null) return;
            if (library.frontScanned(path, row) && pagerView != null) onPageShown(current());
        }
    };

    /**
     * P637 (owner, 2026-11-12: «галерея очень долго открывается» - the viewer black for ~6 s when the camera's thumbnail was
     * tapped right after a shot): the first view of a gallery the camera opened shows what the camera handed over at once -
     * the shutter frame of a shot still processed under «Обработка снимка…» (the final photo takes its place when the camera
     * saves it, from its file, before MediaStore knows it), else the camera's thumbnail until the last photo's page has its
     * picture. {@code o} null: not opened by the camera, or not the first view.
     */
    private void beginOpening(@Nullable CameraHandoff.Open o) {
        if (library.openedByCamera()) {
            cameraOpened = true;
            CameraHandoff.listen(handoffListener);
        }
        if (o == null || cover == null) return;
        boolean pending = o.pending;
        if (pending) {
            CameraHandoff.Saved s = CameraHandoff.lastSaved();
            if (s != null && s.seq > o.saves) pending = false; // saved between the tap and this view: its file is shown below
        }
        final android.graphics.Bitmap picture = o.picture != null && !o.picture.isRecycled() ? o.picture : null;
        if (pending || picture != null) cover.show(picture, pending, false);
        GalleryOpenTrace.placeholder(o.what, pending, picture != null ? picture.getWidth() : 0, picture != null ? picture.getHeight() : 0);
        if (pending) {
            bindPendingMeta();
            idlePolls = 0;
            root.postDelayed(pendingPoll, PENDING_POLL_MS);
        } else {
            if (o.pending) { // the shot was saved meanwhile
                CameraHandoff.Saved s = CameraHandoff.lastSaved();
                savedPath = s != null ? s.path : null;
                expectSaved = true;
                root.post(applySaved);
            }
            if (picture != null) root.postDelayed(coverTimeout, COVER_MAX_MS);
        }
        // the frame that carries the placeholder
        final View r = root;
        r.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                r.getViewTreeObserver().removeOnPreDrawListener(this);
                r.post(() -> GalleryOpenTrace.placeholderOnScreen(picture != null));
                return true;
            }
        });
    }

    /** «Сегодня, 13:41» / «Обработка…» in the title while the shot's file does not exist. */
    private void bindPendingMeta() {
        if (chrome == null) return;
        long now = System.currentTimeMillis();
        chrome.bindMeta(GalleryUi.dayTime(now, now), Lang.t("Обработка…", "Processing…"), null, null, false);
        if (miniExif != null) miniExif.setText("");
    }

    private boolean coverPending() {
        return cover != null && cover.state() == ViewerCover.State.PENDING;
    }

    /** While the shot is processed: has the camera stopped working without a saved file (a failed shot)? */
    private void pollPending() {
        if (root == null || !coverPending()) return;
        boolean busy;
        try {
            busy = com.scamera.app.processing.ProcessingKeepAlive.busy.getAsBoolean();
        } catch (RuntimeException e) {
            busy = false;
        }
        idlePolls = busy ? 0 : idlePolls + 1;
        if (idlePolls >= 2) {
            // nothing is in work: the shot was saved (the event missed, or saved a moment before the tap while the camera
            // still counted it as in work) - its file, which MediaStore may not have yet - or it failed
            CameraHandoff.Saved s = CameraHandoff.lastSaved();
            if (s != null) {
                savedPath = s.path;
                expectSaved = true;
                applySaved();
            }
            if (coverPending()) endPending(s != null ? "the saved file is gone" : "no shot is in work any more");
            return;
        }
        root.postDelayed(pendingPoll, PENDING_POLL_MS);
    }

    /** The wait for the shot ends without its photo: the pages as they are. */
    private void endPending(String why) {
        if (!coverPending()) return;
        if (root != null) root.removeCallbacks(pendingPoll);
        cover.drop(true);
        GalleryOpenTrace.shotGone(why);
        refreshFacts();
        if (pagerView != null && pageAdapter != null) onPageShown(current());
    }

    /**
     * The camera saved a photo while this viewer is open: put first in the list from its file. A viewer still waiting under the
     * placeholder moves to it (the placeholder stays until its page has a picture); one showing another photo keeps that photo.
     */
    private void applySaved() {
        final String path = savedPath;
        final boolean expected = expectSaved;
        savedPath = null;
        expectSaved = false;
        if (root == null || library == null || path == null) return;
        CameraHandoff.Saved s = CameraHandoff.lastSaved();
        final ImageFile f = CameraHandoff.fileOf(path, s != null && path.equals(s.path) ? s.row : null);
        if (f == null) return;
        final boolean waited = coverPending() || expected;
        root.removeCallbacks(pendingPoll);
        if (waited) {
            cover.waitForPage();
            awaitingFinal = true;
            root.removeCallbacks(coverTimeout);
            root.postDelayed(coverTimeout, COVER_MAX_MS);
        } else {
            GalleryItem shown = currentItem();
            if (shown != null) library.selectNext(shown.mediaFile().contentUri()); // the photo viewed stays
            coverWithPage();
        }
        GalleryOpenTrace.shotSaved(f.displayName(), waited);
        boolean busy = false;
        try {
            busy = com.scamera.app.processing.ProcessingKeepAlive.busy.getAsBoolean();
        } catch (RuntimeException ignored) {
        }
        com.scamera.app.gallery.glide.DecodeBoost.forOpen(busy);
        library.showFront(f, waited || currentItem() == null);
    }

    /** The shown page's picture copied over the pages, so a new list is bound behind it (no black frame between two binds). */
    private void coverWithPage() {
        if (cover == null || pagerView == null || root == null || cover.state() != ViewerCover.State.NONE) return;
        CustomSSIV page = getCurrentSSIV();
        int w = pagerView.getWidth(), h = pagerView.getHeight();
        if (page == null || !page.isReady() || w <= 0 || h <= 0) return;
        try {
            android.graphics.Bitmap copy = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            pagerView.draw(new android.graphics.Canvas(copy));
            cover.show(copy, false, true);
            root.removeCallbacks(coverTimeout);
            root.postDelayed(coverTimeout, COVER_MAX_MS);
        } catch (RuntimeException | OutOfMemoryError e) {
            // a hardware bitmap on the page (an Ultra HDR rendition) cannot be drawn into a copy: the plain rebind
            com.scamera.app.util.Log.d(TAG, "no copy of the page for the rebind: " + e);
        }
    }

    /** The page under the cover has a picture: the cover goes. */
    private void pageReadyUnderCover() {
        if (cover == null || cover.state() != ViewerCover.State.PAGE) return;
        CustomSSIV page = getCurrentSSIV();
        if (page == null || !page.isReady()) return;
        if (root != null) root.removeCallbacks(coverTimeout);
        cover.drop(true);
    }

    /** The saved shot's page has its whole picture (the base tiles, not only the embedded preview): «final photo ready». */
    private void finalPhotoLoaded() {
        if (!awaitingFinal) return;
        CustomSSIV page = getCurrentSSIV();
        if (page == null || !page.isImageLoaded()) return;
        awaitingFinal = false;
        GalleryOpenTrace.finalPhotoReady();
    }

    /** A page that never got its picture (a decode failure) does not keep the cover. */
    private void coverTimedOut() {
        if (cover != null && cover.state() == ViewerCover.State.PAGE) cover.drop(true);
    }

    /**
     * P637: while the placeholder stands for a shot still processed, the bars' actions have no photo to act on (the page under
     * it is an older one); a photo known only by its file has no MediaStore row yet (a moment, until the scanner files it).
     */
    private boolean notYet(@Nullable GalleryItem item) {
        if (coverPending()) {
            com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Снимок ещё обрабатывается", "The photo is still being processed"));
            return true;
        }
        if (item != null && CameraHandoff.unfiled(item.mediaFile())) {
            com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Снимок ещё сохраняется", "The photo is still being saved"));
            return true;
        }
        return false;
    }

    private void buildChrome(Context c) {
        chrome = new ViewerChrome(c, new ViewerChrome.Actions() {
            @Override public void back() { onBackArrow(); }
            @Override public void grid() { onGalleryButtonClick(); }
            @Override public void share() { onShareButtonClick(); }
            @Override public void edit() { onEditButtonClick(); }
            @Override public void compare() { onQuickCompare(); }
            @Override public void info() { onExifButtonClick(); }
            @Override public void delete() { onDeleteButtonClick(); }
            @Override public void hdr() { onHdrToggleClicked(); }
            @Override public void histogram() { onHistogramToggle(); }
            @Override public void favourite() { onFavouriteClick(); }
            @Override public void more() { onMoreClick(); }
            @Override public void openPosition(int position) { if (pagerView != null) pagerView.setCurrentItem(position, true); }
        });
        if (secureReview()) chrome.grid.setVisibility(View.GONE); // P621: no library over the lock screen
        chrome.edit.setOnLongClickListener(v -> {
            onEditElsewhere(); // P351: the long press keeps the old «edit in another app»
            return true;
        });
        root.addView(chrome.top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        buildLiveHistogram(c);
        root.addView(chrome.bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        overlays = new LinearLayout(c);
        overlays.setOrientation(LinearLayout.VERTICAL);
        overlays.setGravity(Gravity.CENTER_HORIZONTAL);
        zoomPill = GalleryUi.pill(c, "", true);
        zoomPill.setVisibility(View.GONE);
        miniExif = GalleryUi.pill(c, "", false);
        miniExif.setVisibility(View.GONE);
        overlays.addView(zoomPill);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = GalleryUi.dp(c, 6);
        overlays.addView(miniExif, mp);
        overlays.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(overlays, op);
        chrome.bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeOverlays());
        // P498: the play button of a video page
        FrameLayout disc = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(GalleryUi.BAR); // P579: the camera's dark bar with its light edge
        d.setStroke(Math.max(1, GalleryUi.dp(c, .5f)), GalleryUi.EDGE);
        disc.setBackground(GalleryUi.pressable(d));
        disc.setContentDescription(Lang.t("Смотреть видео", "Play the video"));
        disc.setClickable(true);
        disc.setFocusable(true);
        disc.setTag("viewer_play");
        disc.addView(GalleryUi.icon(c, R.drawable.ui_ic_play, 0xFFFFFFFF, 34),
                new FrameLayout.LayoutParams(GalleryUi.dp(c, 34), GalleryUi.dp(c, 34), Gravity.CENTER));
        disc.setOnClickListener(v -> openPlayer());
        Motion.press(disc);
        disc.setVisibility(View.GONE);
        playDisc = disc;
        root.addView(disc, new FrameLayout.LayoutParams(GalleryUi.dp(c, 76), GalleryUi.dp(c, 76), Gravity.CENTER));
    }

    @Nullable
    private GalleryItem currentItem() {
        if (pagerView == null || photos.isEmpty()) return null;
        int position = current();
        return position >= 0 && position < photos.size() ? photos.get(position) : null;
    }

    private boolean currentIsVideo() {
        GalleryItem item = currentItem();
        return item != null && GalleryVideo.isVideo(item.mediaFile());
    }

    /** P499: the shown video in the gallery's own player; the other videos of the list are its previous / next. */
    private void openPlayer() {
        GalleryItem item = currentItem();
        if (item == null || item.mediaFile().contentUri() == null || getContext() == null) return;
        stopSlideshow();
        List<android.net.Uri> uris = new ArrayList<>();
        List<String> names = new ArrayList<>(), paths = new ArrayList<>();
        int at = 0;
        for (GalleryItem g : photos) {
            if (!GalleryVideo.isVideo(g.mediaFile()) || g.mediaFile().contentUri() == null) continue;
            if (g == item) at = uris.size();
            uris.add(g.mediaFile().contentUri());
            names.add(g.mediaFile().displayName() != null ? g.mediaFile().displayName() : "");
            paths.add(g.mediaFile().filePath() != null ? g.mediaFile().filePath() : "");
        }
        // a long gallery: the 400 videos around this one (the intent stays small)
        int from = Math.max(0, Math.min(at - 200, uris.size() - 400)), to = Math.min(uris.size(), from + 400);
        com.scamera.app.gallery.video.VideoPlayerActivity.open(requireContext(), uris.subList(from, to),
                names.subList(from, to), paths.subList(from, to), at - from);
    }

    private void bindPlayDisc() {
        if (playDisc == null) return;
        playDisc.setVisibility(currentIsVideo() ? View.VISIBLE : View.GONE);
        playDisc.setAlpha(1f);
    }

    /** P498: the heart: the shown photo into / out of «Избранное». */
    private void onFavouriteClick() {
        GalleryItem item = currentItem();
        if (item == null || getContext() == null || chrome == null || notYet(item)) return;
        boolean on = !GalleryView.isFavourite(requireContext(), item.mediaFile());
        GalleryView.setFavourite(requireContext(), Collections.singletonList(item.mediaFile()), on);
        chrome.setFavourite(on, true);
        if (root != null) root.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
    }

    /** P498: «Ещё» - slideshow, set as (photos), open with another app; P697: move / copy into a folder. */
    private void onMoreClick() {
        GalleryItem item = currentItem();
        if (item == null || getContext() == null || notYet(item)) return;
        Context c = requireContext();
        boolean video = GalleryVideo.isVideo(item.mediaFile());
        List<GallerySheets.Action> actions = new ArrayList<>();
        if (photos.size() > 1)
            actions.add(new GallerySheets.Action(R.drawable.ui_ic_play, Lang.t("Слайд-шоу", "Slideshow"), this::startSlideshow));
        if (!video)
            actions.add(new GallerySheets.Action(R.drawable.ui_ic_wallpaper, Lang.t("Установить как…", "Set as…"), () -> sendTo(item, Intent.ACTION_ATTACH_DATA)));
        // P504: the text on the photo, to copy (OCR)
        if (!video && com.scamera.app.gallery.ocr.OcrIndex.indexable(item.mediaFile().displayName()))
            actions.add(new GallerySheets.Action(R.drawable.ui_ic_text, Lang.t("Текст на фото", "Text in the photo"),
                    () -> com.scamera.app.gallery.ui.TextSheet.show(c, item.mediaFile())));
        actions.add(new GallerySheets.Action(R.drawable.ui_ic_open, Lang.t("Открыть в другом приложении", "Open in another app"), () -> sendTo(item, Intent.ACTION_VIEW)));
        // P697: the shown photo or video into a folder (a file of the library: one opened from another app has no row of its own)
        if (item.mediaFile().rowId() >= 0)
            for (com.scamera.app.gallery.files.GalleryMoveCopy.Mode mode : com.scamera.app.gallery.files.GalleryMoveCopy.Mode.values())
                actions.add(new GallerySheets.Action(com.scamera.app.gallery.ui.MoveCopyFlow.icon(mode), com.scamera.app.gallery.ui.MoveCopyFlow.label(mode),
                        () -> toFolder(item, mode)));
        // P579 (owner, 2026-11-09: «… в ней надо стиль также обновить»): the pull-down menu at the «Ещё» button (the shared menu's look);
        // the sheet of rows when the button is not on screen
        View anchor = chrome != null ? chrome.more : null;
        if (anchor != null && anchor.isAttachedToWindow() && anchor.getWindowToken() != null && anchor.isShown()) {
            List<com.scamera.app.gallery.ui.GalleryMenu.Item> items = new ArrayList<>();
            for (GallerySheets.Action a : actions) items.add(new com.scamera.app.gallery.ui.GalleryMenu.Item(a.icon, a.label, true, a.run));
            com.scamera.app.gallery.ui.GalleryMenu.show(anchor, items);
            return;
        }
        GallerySheets.showActions(c, Lang.t("Ещё", "More"), actions);
    }

    /** P697: {@code item} moved / copied into a folder; the lists are read again when the grid shows (its pending update). */
    private void toFolder(GalleryItem item, com.scamera.app.gallery.files.GalleryMoveCopy.Mode mode) {
        if (getActivity() == null) return;
        final com.scamera.app.gallery.viewmodel.GalleryViewModel vm = library;
        com.scamera.app.gallery.ui.MoveCopyFlow.start(requireActivity(), mode, Collections.singletonList(item.mediaFile()), () -> {
            if (vm != null) vm.setUpdatePending(true);
        });
    }

    private void sendTo(GalleryItem item, String action) {
        Uri uri = item.mediaFile().contentUri();
        if (uri == null || getContext() == null) return;
        Intent i = new Intent(action);
        i.setDataAndType(uri, mimeOf(item));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Intent.ACTION_ATTACH_DATA.equals(action)) i.putExtra("mimeType", mimeOf(item));
        try {
            startActivity(Intent.createChooser(i, null));
        } catch (android.content.ActivityNotFoundException e) {
            com.scamera.app.gallery.ui.GalleryToast.show(requireContext(), Lang.t("Нет подходящего приложения", "No app for this"));
        }
    }

    private static String mimeOf(GalleryItem item) {
        String name = item.mediaFile().displayName();
        return GalleryVideo.isVideo(item.mediaFile()) ? GalleryVideo.mime(name) : mediaTypeOf(name);
    }

    private void startSlideshow() {
        if (pagerView == null || photos.size() < 2 || getContext() == null) return;
        slideshow = true;
        if (chromeVisible) onPhotoTapped(pagerView);
        if (root != null) root.setKeepScreenOn(true);
        com.scamera.app.gallery.ui.GalleryToast.show(requireContext(), Lang.t("Слайд-шоу: касание останавливает", "Slideshow: tap to stop"));
        pagerView.removeCallbacks(slideTick);
        pagerView.postDelayed(slideTick, SLIDE_MS);
    }

    private void nextSlide() {
        if (!slideshow || pagerView == null) return;
        int next = current() + 1;
        if (next >= photos.size()) {
            stopSlideshow();
            if (!chromeVisible) onPhotoTapped(pagerView);
            return;
        }
        pagerView.setCurrentItem(next, true);
        pagerView.postDelayed(slideTick, SLIDE_MS);
    }

    private void stopSlideshow() {
        if (!slideshow) return;
        slideshow = false;
        if (pagerView != null) pagerView.removeCallbacks(slideTick);
        if (root != null) root.setKeepScreenOn(false);
    }

    private boolean openedOnce;

    /**
     * P498: the viewer opens out of the tapped thumbnail and, closed on the same photo, folds back into it (the grid keeps its
     * scroll position across the viewer, so the cell is where it was); other cases keep the graph's fade.
     */
    @Nullable
    @Override
    public android.view.animation.Animation onCreateAnimation(int transit, boolean enter, int nextAnim) {
        Bundle args = getArguments();
        int[] cell = args != null ? args.getIntArray(ImageLibraryFragment.CELL_KEY) : null;
        View content = getActivity() != null ? getActivity().findViewById(android.R.id.content) : null;
        if (cell == null || cell.length != 4 || content == null || isCompareMode()) return super.onCreateAnimation(transit, enter, nextAnim);
        // entering: only from the grid (not back from the editor / compare); leaving: only back to the grid on the photo opened
        boolean fromGrid;
        if (enter) fromGrid = !openedOnce;
        else // the nav back stack is already popped when the transaction asks for the animation
            fromGrid = pagerView != null && current() == args.getInt(Constants.ARG_POSITION, -1) && navigation != null
                    && navigation.getCurrentDestination() != null && navigation.getCurrentDestination().getId() == R.id.gallery_library;
        if (enter) openedOnce = true;
        if (!fromGrid || !Motion.enabled(content)) return super.onCreateAnimation(transit, enter, nextAnim);
        int[] at = new int[2];
        content.getLocationInWindow(at);
        android.view.animation.Animation a = GalleryMotion.zoom(new android.graphics.Rect(cell[0], cell[1], cell[2], cell[3]),
                content.getWidth(), content.getHeight(), at[0], at[1], !enter);
        return a != null ? a : super.onCreateAnimation(transit, enter, nextAnim);
    }

    private void buildPaneOverlay(Context c) {
        paneLabel = GalleryUi.text(c, "", 12, GalleryUi.TEXT);
        paneLabel.setBackground(GalleryUi.bar(c, 14)); // P579: the dark bar over the photo
        paneLabel.setPadding(GalleryUi.dp(c, 9), GalleryUi.dp(c, 5), GalleryUi.dp(c, 9), GalleryUi.dp(c, 5));
        paneLabel.setCompoundDrawablePadding(GalleryUi.dp(c, 6));
        paneLabel.setMaxLines(4); // P196: EXIF line, resolution · phone, file name (one may wrap on a narrow pane)
        paneLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = lp.topMargin = lp.rightMargin = GalleryUi.dp(c, 10);
        root.addView(paneLabel, lp);
        paneZoom = GalleryUi.pill(c, "", true);
        paneZoom.setVisibility(View.GONE);
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        zp.rightMargin = zp.bottomMargin = GalleryUi.dp(c, 10);
        root.addView(paneZoom, zp);
    }

    private void placeOverlays() {
        if (overlays == null || chrome == null) return;
        FrameLayout.LayoutParams op = (FrameLayout.LayoutParams) overlays.getLayoutParams();
        op.bottomMargin = chromeVisible ? chrome.bottom.getHeight() + GalleryUi.dp(root.getContext(), 10) : GalleryUi.dp(root.getContext(), 16);
        overlays.setLayoutParams(op);
    }

    /**
     * SubsamplingScaleImageView decodes tiles on background threads and reports them (onReady / onScaleChanged) on the main
     * thread after this view may be gone: the pages are detached from every listener and recycled here, and every callback
     * below checks for a destroyed view. A fragment kept on the back stack (compare, settings) must also not keep the pages'
     * tile bitmaps or a full-resolution Ultra HDR bitmap alive.
     */
    @Override
    public void onDestroyView() {
        stopSlideshow();
        playDisc = null;
        // P637
        if (cameraOpened) CameraHandoff.listen(null);
        if (root != null) {
            root.removeCallbacks(pendingPoll);
            root.removeCallbacks(applySaved);
            root.removeCallbacks(coverTimeout);
        }
        if (cover != null) cover.destroy();
        cover = null;
        awaitingFinal = false;
        if (liveHistogramCallback != null) factsModel.getExifDataModel().removeOnPropertyChangedCallback(liveHistogramCallback);
        liveHistogramCallback = null;
        liveHistogram = null;
        liveHistogramCard = null;
        if (pagerView != null) {
            pagerView.clearOnPageChangeListeners();
            for (int i = 0; i < pagerView.getChildCount(); i++) {
                View child = pagerView.getChildAt(i);
                if (child instanceof SubsamplingScaleImageView) ImageAdapter.detachPage((SubsamplingScaleImageView) child);
            }
        }
        if (pageAdapter != null) {
            pageAdapter.setImageEventListener(null);
            pageAdapter.setSsivListener(null);
            pageAdapter.setImageViewClickListener(null);
            pageAdapter.setHdrStateListener(null);
            pageAdapter.releaseAllHdr(getContext());
        }
        if (histogramCallback != null) factsModel.getExifDataModel().removeOnPropertyChangedCallback(histogramCallback);
        if (histogramView != null) histogramView.setHistogramLoadingListener(null);
        histogramCallback = null;
        histogramView = null;
        pageAdapter = null;
        pagerView = null;
        chrome = null;
        root = null;
        hdrPage = -1;
        super.onDestroyView();
    }

    private void bindPhotos(List<GalleryItem> photos) {
        if (photos == null) return;
        final int before = this.photos != null ? this.photos.size() : 0;
        // P645: the other RAWs joining the list under a page shown: that page stays (by its file, not its index)
        GalleryItem keep = null;
        if (library != null && library.joining() && pageAdapter != null && pagerView != null && this.photos != null) {
            int at = current();
            if (at >= 0 && at < this.photos.size()) keep = this.photos.get(at);
        }
        this.photos = photos;
        rawBases = GalleryFormat.rawBaseNames(photos);
        // View destroyed (late edit/delete result): the LiveData observer rebuilds the adapter with the view.
        if (pagerView == null) return;
        if (photos.isEmpty() && !isCompareMode()) { // nothing to show: the grid says so (its empty message, its folders)
            if (coverPending()) return; // P637: the first shot of an empty library is still processed: its photo comes
            pagerView.post(this::onGalleryButtonClick);
            return;
        }
        // P637: the media lists arriving under the camera's last photo already shown: bound behind a copy of its page
        if (pageAdapter != null && before == 1 && photos.size() > 1 && android.os.SystemClock.uptimeMillis() < seamlessUntil) coverWithPage();
        else if (keep != null) coverWithPage(); // P645: no black flash for the RAW join's rebind either
        pageAdapter = new ImageAdapter(this.photos);
        pageAdapter.setImageViewClickListener(this::onPhotoTapped);
        pageAdapter.setHdrStateListener(this);
        if (zoomListener != null) pageAdapter.setSsivListener(zoomListener);
        pageAdapter.setImageEventListener(new SubsamplingScaleImageView.DefaultOnImageEventListener() {
            @Override
            public void onReady() {
                // P617: once per gallery open; P637: not for an older photo's page under the placeholder of a shot in work
                if (!coverPending()) com.scamera.app.gallery.ui.GalleryOpenTrace.viewerReady();
                pageReadyUnderCover();
                CustomSSIV page = getCurrentSSIV();
                if (page != null) {
                    page.setDoubleTapZoomScale(page.getMinScale() * 2.5f);
                    page.setSwipeDownListener(isCompareMode() ? null : ImageViewerFragment.this::onGalleryButtonClick);
                }
                updateScaleText();
            }

            @Override
            public void onImageLoaded() {
                pageReadyUnderCover();
                finalPhotoLoaded();
            }

            @Override
            public void onImageLoadError(Exception e) {
                coverTimedOut(); // P637: no picture will come for this page
            }
        });
        pagerView.setAdapter(pageAdapter);
        if (chrome != null) chrome.filmstrip.setItems(photos);
        // P351: after «Сохранить копию» the viewer opens on the copy
        Uri select = library.takePendingSelect();
        if (select != null)
            for (int i = 0; i < photos.size(); i++)
                if (select.equals(photos.get(i).mediaFile().contentUri())) shownPage = i;
        if (select == null && keep != null)
            for (int i = 0; i < photos.size(); i++)
                if (GalleryViewModel.sameFile(keep.mediaFile(), photos.get(i).mediaFile())) shownPage = i;
        int position = Math.max(0, Math.min(shownPage, photos.size() - 1));
        pagerView.setCurrentItem(position);
        final ViewPager pager = pagerView;
        pager.post(() -> {
            if (pagerView != pager) return;
            hdrForPage(position);
            onPageShown(position);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        pagerView.setPageTransformer(true, new DepthPageTransformer());
        // P117: every page of HEIC / WebP / AVIF / DNG is one decoded bitmap (up to 4096 px, ~50 MB): 3 kept on each side were
        // 350 MB a viewer, twice that in compare and again while a swap built the new panes (the app died on the swap button)
        // P706: one a side on a phone of at most 4 GB (GalleryTier)
        pagerView.setOffscreenPageLimit(com.scamera.app.gallery.glide.GalleryTier.viewerOffscreenPages(
                com.scamera.app.gallery.glide.GalleryTier.get(), isCompareMode()));
        pagerView.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (root == null) return;
                shownPage = position;
                onPageShown(position);
                hdrForPage(position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                // P637: a swipe under the placeholder of a shot in work: the older photos (the new one is put first when saved)
                if (state == ViewPager.SCROLL_STATE_DRAGGING && coverPending()) endPending("the viewer was swiped");
                // P498: the play button stays with its page (hidden while a page slides)
                if (playDisc != null && currentIsVideo())
                    Motion.fade(playDisc, state == ViewPager.SCROLL_STATE_IDLE);
            }
        });
    }

    /** P492: the overlay under the top bar at the right, shown while the switch is on (the state of the last session). */
    private void buildLiveHistogram(Context c) {
        histogramOn = c.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HISTOGRAM, false);
        liveHistogram = new Histogram(c, null);
        liveHistogram.setContentDescription(Lang.t("Гистограмма RGB", "RGB histogram"));
        liveHistogramCard = new FrameLayout(c);
        liveHistogramCard.setBackground(GalleryUi.bar(c, 16)); // P579: the dark bar over the photo
        int pad = GalleryUi.dp(c, 8);
        liveHistogramCard.setPadding(pad, pad, pad, pad);
        liveHistogramCard.setTag("viewer_histogram_overlay");
        liveHistogramCard.addView(liveHistogram, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 176), GalleryUi.dp(c, 92), Gravity.TOP | Gravity.END);
        lp.topMargin = GalleryUi.dp(c, 92);
        lp.rightMargin = GalleryUi.dp(c, 12);
        root.addView(liveHistogramCard, lp);
        final ExifDialogModel model = factsModel.getExifDataModel();
        final Histogram view = liveHistogram;
        liveHistogramCallback = new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable sender, int propertyId) {
                if (histogramOn && model.getHistogramModel() != null) view.post(() -> view.setHistogramModel(model.getHistogramModel()));
            }
        };
        model.addOnPropertyChangedCallback(liveHistogramCallback);
        applyHistogramSwitch();
    }

    private void onHistogramToggle() {
        histogramOn = !histogramOn;
        if (getContext() != null)
            requireContext().getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HISTOGRAM, histogramOn).apply();
        applyHistogramSwitch();
    }

    /** The switch's state on the overlay and the button; on: the current photo's histogram (each photo shown recomputes it). */
    private void applyHistogramSwitch() {
        if (liveHistogramCard == null || chrome == null) return;
        liveHistogramCard.setVisibility(histogramOn ? View.VISIBLE : View.GONE);
        chrome.setHistogram(histogramOn);
        if (histogramOn && pagerView != null && !photos.isEmpty()) liveHistogramFor(current());
    }

    private void liveHistogramFor(int position) {
        if (!histogramOn || position < 0 || position >= photos.size()) return;
        factsModel.updateHistogramView((ImageFile) photos.get(position).mediaFile());
    }

    /** The current photo's meta, badges, filmstrip and zoom. */
    private void onPageShown(int position) {
        liveHistogramFor(position); // P492
        refreshFacts();
        updateScaleText();
        if (chrome != null) chrome.centre(position);
        GalleryItem shown = position >= 0 && position < photos.size() ? photos.get(position) : null;
        if (chrome != null && shown != null && getContext() != null) {
            chrome.setFavourite(GalleryView.isFavourite(requireContext(), shown.mediaFile()), false);
            chrome.setVideo(GalleryVideo.isVideo(shown.mediaFile()));
        }
        bindPlayDisc();
        CustomSSIV page = pageView(position);
        if (pageAdapter != null) pageAdapter.reloadIfGaveUp(page, position); // P81
        if (page != null && page.isReady()) {
            page.setDoubleTapZoomScale(page.getMinScale() * 2.5f);
            page.setSwipeDownListener(isCompareMode() ? null : this::onGalleryButtonClick);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pageAdapter == null || pagerView == null) return;
        int page = current();
        pageAdapter.reloadIfGaveUp(pageView(page), page); // P81
        boolean hdrOn = pageAdapter.isHdrActive(page);
        if (hdrOn) {
            UltraHdrGalleryUtil.setWindowHdr(getActivity(), true);
        } else if (!isCompareMode()) {
            applyWindowColour(page, false);
            probePageColour(page);
            pageAdapter.loadHdrForPosition(pageView(page), page);
        }
        showHdrPill(pageAdapter.isHdrAvailable(page), hdrOn);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopSlideshow();
        UltraHdrGalleryUtil.setWindowHdr(getActivity(), false); // the window leaves HDR with the viewer
        if (pageAdapter != null && pagerView != null) showHdrPill(pageAdapter.isHdrAvailable(current()), false);
    }

    /**
     * Loads the Ultra HDR rendition for the newly selected page and releases the previous page's HDR bitmap so at most one
     * full-resolution HDR bitmap stays in memory. The window colour mode is re-synchronised with the visible page.
     */
    private void hdrForPage(int position) {
        if (pageAdapter == null) return;
        if (hdrPage >= 0 && hdrPage != position) pageAdapter.releaseHdrForPosition(pageView(hdrPage), hdrPage);
        hdrPage = position;
        // P117: no full-resolution Ultra HDR rendition in compare (one per pane, ~200 MB each at 50 MP, again on every swap; the
        // window cannot show two panes in HDR anyway): the panes compare the SDR pictures
        if (!isCompareMode()) pageAdapter.loadHdrForPosition(pageView(position), position);
        if (!isCompareMode() && getActivity() != null) {
            applyWindowColour(position, pageAdapter.isHdrActive(position));
            probePageColour(position);
        }
        showHdrPill(pageAdapter.isHdrAvailable(position), pageAdapter.isHdrActive(position));
    }

    /** P46: the window colour mode of the page (HDR for its Ultra HDR rendition, else by the colour of its file). */
    private void applyWindowColour(int position, boolean hdr) {
        if (getActivity() == null || pageAdapter == null) return;
        UltraHdrGalleryUtil.setWindowMode(getActivity(), hdr, pageAdapter.colourOf(position));
    }

    /** P46: learns the colour of the page's file; a wide-gamut / HLG page switches the window when it is still shown. */
    private void probePageColour(int position) {
        if (pageAdapter == null) return;
        pageAdapter.probeColour(position, getContext(), () -> {
            if (pagerView != null && pageAdapter != null && position == current() && !isCompareMode()
                    && !pageAdapter.isHdrActive(position)) applyWindowColour(position, false);
        });
    }

    @Override
    public void onHdrStateChanged(int position, boolean hdrOn) {
        if (getActivity() == null || pagerView == null || position != current()) return;
        applyWindowColour(position, hdrOn);
        showHdrPill(pageAdapter != null && pageAdapter.isHdrAvailable(position), hdrOn);
    }

    /**
     * The «HDR» pill: on releases the full-resolution HDR bitmap and restores the tiled SDR source and the default window
     * mode; off loads the gainmap-backed rendition again.
     */
    private void onHdrToggleClicked() {
        if (pageAdapter == null || pagerView == null) return;
        int position = current();
        if (!pageAdapter.isHdrAvailable(position)) return;
        if (pageAdapter.isHdrActive(position)) {
            pageAdapter.releaseHdrForPosition(pageView(position), position);
            applyWindowColour(position, false);
            showHdrPill(true, false);
        } else {
            pageAdapter.loadHdrForPosition(pageView(position), position);
        }
    }

    @Override
    public void onHdrAvailabilityChanged(int position, boolean ultraHdr) {
        if (pagerView == null || position != current()) return;
        showHdrPill(ultraHdr, pageAdapter != null && pageAdapter.isHdrActive(position));
        refreshFacts();
    }

    /** The «HDR» pill: shown for an Ultra HDR photo, lit while its HDR rendition is on. */
    private void showHdrPill(boolean available, boolean lit) {
        if (chrome != null) chrome.bindHdr(available, lit);
    }

    /** Compare mode: the pane's zoom and moves go to the comparison (it links both panes). */
    public void setSsivListener(SSIVListener listener) {
        zoomListener = listener;
    }

    public CustomSSIV getCurrentSSIV() {
        return pagerView == null ? null : pageView(current());
    }

    /** The page on screen (the pager must be there). */
    private int current() {
        return pagerView.getCurrentItem();
    }

    private Uri focal35Uri;
    private float focal35;

    /**
     * The 35 mm equivalent focal length of the shown photo (EXIF FocalLengthIn35mmFilm), 0 when it has none: the compare
     * panes link a 2x photo to a 1x one by it (PaneLink.byFieldOfView). Read once per photo.
     */
    public float currentFocal35() {
        if (pagerView == null || getContext() == null) return 0f;
        int position = current();
        if (position < 0 || position >= photos.size()) return 0f;
        Uri uri = photos.get(position).mediaFile().contentUri();
        if (uri == null) return 0f;
        if (!uri.equals(focal35Uri)) {
            focal35Uri = uri;
            focal35 = 0f;
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in != null) focal35 = Math.max(0, new androidx.exifinterface.media.ExifInterface(in)
                        .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0));
            } catch (java.io.IOException | RuntimeException e) {
                com.scamera.app.util.Log.d(TAG, "no 35 mm focal length: " + e.getMessage());
            }
        }
        return focal35;
    }

    private CustomSSIV pageView(int position) {
        if (pageAdapter == null || pagerView == null) return null;
        // The pages only (Xiaomi 17 Ultra crash, 2026-10-09): findViewById tests the pager itself first, and its generated id
        // can equal a page id (BASE_ID + position), which returned the ViewPager and failed the cast.
        int id = pageAdapter.getSsivId(position);
        for (int i = 0; i < pagerView.getChildCount(); i++) {
            View child = pagerView.getChildAt(i);
            if (child instanceof CustomSSIV && child.getId() == id) return (CustomSSIV) child;
        }
        return null;
    }

    private void onQuickCompare() {
        if (notYet(currentItem())) return; // P637
        if (photos.size() >= 2 && pagerView != null) {
            Bundle b = new Bundle(2);
            int image1pos = current();
            int image2pos = image1pos + 1;
            if (image1pos == photos.size() - 1) {
                image2pos = image1pos;
                image1pos -= 1;
            }
            b.putInt(Constants.ARG_FIRST_IMAGE, image1pos);
            b.putInt(Constants.ARG_SECOND_IMAGE, image2pos);
            navigation.navigate(R.id.push_compare, b);
        } else if (getContext() != null) {
            com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Нет снимка для сравнения", "No photo to compare with"));
        }
    }

    /** Back: to the grid (the library when the viewer was the start). */
    private void onGalleryButtonClick() {
        if (navigation == null) return;
        // P621: a lock-screen review (REVIEW_SECURE) never shows the library: leaving the photo closes the gallery
        if (secureReview()) {
            getActivity().finish();
            return;
        }
        // P706: the grid comes back scrolled to the photo shown
        if (library != null && pagerView != null && !photos.isEmpty() && !coverPending()) {
            final int at = current();
            if (at >= 0 && at < photos.size()) library.revealInGrid(photos.get(at).mediaFile());
        }
        toGrid(navigation);
    }

    private boolean secureReview() {
        return getActivity() instanceof com.scamera.app.gallery.ui.GalleryActivity
                && ((com.scamera.app.gallery.ui.GalleryActivity) getActivity()).secureReview();
    }

    /**
     * P706: the back arrow is the system's back - to the grid the viewer was opened from, else (the camera's thumbnail, another
     * app's photo) out of the gallery to where it came from; the feed button beside it ({@link #onGalleryButtonClick}) opens the
     * grid from anywhere.
     */
    private void onBackArrow() {
        if (navigation == null || getActivity() == null) return;
        if (backReturnsToGrid(navigation)) onGalleryButtonClick();
        else getActivity().getOnBackPressedDispatcher().onBackPressed();
    }

    /** P706: whether back from the viewer lands on the grid (opened from it) rather than leaving the gallery. */
    static boolean backReturnsToGrid(NavController nav) {
        final androidx.navigation.NavBackStackEntry previous = nav.getPreviousBackStackEntry();
        return previous != null && previous.getDestination().getId() == R.id.gallery_library;
    }

    /** From the viewer back to the grid: up, or to the library when the viewer is the graph's start (navigateUp does nothing there). */
    static void toGrid(NavController nav) {
        if (nav.getPreviousBackStackEntry() == null) {
            Bundle none = new Bundle();
            nav.navigate(R.id.fold_to_library, none);
        } else nav.navigateUp();
    }

    /**
     * P351 (owner, 2026-10-26: «нам нужен редактор изображения в галерее с базовыми функциями как в мобильном приложении
     * лайтрума»): «Изменить» opens the gallery's own editor (PhotoEditorFragment); a long press on it - or a file the editor
     * does not take - goes to another app's editor as before (ExternalEdit).
     */
    private void onEditButtonClick() {
        if (pagerView == null || photos == null || getContext() == null || photos.isEmpty() || navigation == null) return;
        if (notYet(currentItem())) return; // P637
        GalleryItem galleryItem = photos.get(current());
        String fileName = galleryItem.mediaFile().displayName();
        Uri uri = galleryItem.mediaFile().contentUri();
        if (uri == null) return;
        String mime = mediaTypeOf(fileName);
        if (!com.scamera.app.gallery.editor.ExternalEdit.editable(fileName, mime)) {
            onEditElsewhere();
            return;
        }
        shownPage = current();
        navigation.navigate(R.id.push_editor,
                com.scamera.app.gallery.editor.ExternalEdit.args(uri, fileName, galleryItem.mediaFile().filePath(), mime, false));
    }

    /** The old route: another app's editor (ACTION_EDIT chooser without this gallery). */
    private void onEditElsewhere() {
        if (pagerView == null || photos == null || getContext() == null || photos.isEmpty()) return;
        if (notYet(currentItem())) return; // P637
        GalleryItem galleryItem = photos.get(current());
        if (galleryItem.mediaFile().contentUri() == null) return;
        com.scamera.app.gallery.editor.ExternalEdit.openInOtherApp(this, galleryItem.mediaFile().contentUri(),
                galleryItem.mediaFile().displayName());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constants.REQ_EXTERNAL_EDIT && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Сохранено: ", "Saved: ") + data.getData().getPath(), true);
            library.fetchAllMedia();
            bindPhotos(library.getCurrentFolderImages().getValue());
            refreshFacts();
        }
    }

    private void onDeleteButtonClick() {
        if (pagerView == null || photos.isEmpty() || getContext() == null) return;
        if (notYet(currentItem())) return; // P637
        int position = current();
        GalleryItem item = photos.get(position);
        Runnable delete = () -> {
            deletingAt = position;
            GalleryFileOperations.deleteImageFiles(getActivity(), Collections.singletonList((ImageFile) item.mediaFile()), this::handleImagesDeletedCallback);
        };
        // P345: one confirmation - the system's own dialog when it is needed; P498: this app's own file goes at once, with «Вернуть»
        if (GalleryFileOperations.needsSystemDeleteRequest(requireContext(), Collections.singletonList((ImageFile) item.mediaFile()))) delete.run();
        else {
            shownPage = position; // the next photo takes its place
            int above = chrome != null && chromeVisible ? chrome.bottom.getHeight() + GalleryUi.dp(requireContext(), 10) : GalleryUi.dp(requireContext(), 24);
            GalleryUndo.delete(requireContext(), root, above, Collections.singletonList((ImageFile) item.mediaFile()), library);
        }
    }

    /** MIME type for share / edit: MimeTypeMap lacks HEIC on older Android versions, the photo formats are mapped here. */
    private static String mediaTypeOf(String fileName) {
        return com.scamera.app.gallery.editor.ExternalEdit.mediaTypeOf(fileName);
    }

    private void onShareButtonClick() {
        if (pagerView == null || photos.isEmpty()) return;
        if (notYet(currentItem())) return; // P637
        GalleryItem galleryItem = photos.get(current());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_STREAM, galleryItem.mediaFile().contentUri());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(mimeOf(galleryItem)); // P498: a video is not image/*
        startActivity(Intent.createChooser(intent, null));
    }

    /** «Сведения»: the details sheet of the current photo (histogram filled when it is ready). */
    private void onExifButtonClick() {
        if (pagerView == null || photos.isEmpty() || getContext() == null) return;
        if (coverPending()) { // P637: no file to describe yet
            notYet(null);
            return;
        }
        int position = current();
        GalleryItem item = photos.get(position);
        refreshFacts();
        Context c = requireContext();
        histogramView = new Histogram(c, null);
        ExifDialogModel model = factsModel.getExifDataModel();
        if (histogramCallback != null) model.removeOnPropertyChangedCallback(histogramCallback);
        final Histogram view = histogramView;
        histogramCallback = new Observable.OnPropertyChangedCallback() {
            @Override
            public void onPropertyChanged(Observable sender, int propertyId) {
                if (model.getHistogramModel() != null) view.post(() -> view.setHistogramModel(model.getHistogramModel()));
            }
        };
        model.addOnPropertyChangedCallback(histogramCallback);
        factsModel.updateHistogramView((ImageFile) item.mediaFile());
        final boolean ultraHdr = pageAdapter != null && pageAdapter.isHdrAvailable(position);
        final GallerySheets.Details d = details(item, model, ultraHdr);
        final View sheet = GallerySheets.details(c, d, histogramView);
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog = GallerySheets.show(c, sheet);
        // P706: the file's properties by section, read off the main thread (the EXIF read here before held the tap)
        com.scamera.app.gallery.ui.PhotoPropertiesReader.load(c, item.mediaFile(), d.width, d.height, d.format, ultraHdr, sections -> {
            if (dialog.isShowing()) GallerySheets.fillSections(sheet, sections);
        });
    }

    private GallerySheets.Details details(GalleryItem item, ExifDialogModel m, boolean ultraHdr) {
        GallerySheets.Details d = new GallerySheets.Details();
        d.fileName = item.mediaFile().displayName();
        d.when = GalleryUi.dayTime(item.mediaFile().modifiedMs(), System.currentTimeMillis());
        d.folder = folderOf(item.mediaFile().filePath());
        // P498: a video has no photo EXIF (the model still holds the last photo's); its length in the shutter's place
        boolean video = GalleryVideo.isVideo(item.mediaFile());
        long len = GalleryVideo.durationMs(item.mediaFile());
        d.iso = video ? null : m.getIsoValue();
        d.shutter = video ? (len > 0 ? GalleryVideo.clock(len) : null) : m.getShutterValue();
        d.fnum = video ? null : m.getFnumValue();
        d.focal = video ? null : m.getFocalValue();
        d.route = video ? null : GalleryFormat.routeOf(m.getDescription());
        GalleryFormat f = GalleryFormat.of(item, rawBases);
        d.format = f.label.isEmpty() ? GalleryFormat.extension(d.fileName).toUpperCase(Locale.ROOT) : f.label;
        d.formatIcon = f.icon;
        d.ultraHdr = ultraHdr;
        d.lens = !video && m.getFocal35Value() != null ? m.getFocal35Value() + Lang.t(" экв.", " equiv.") : null;
        d.device = video ? null : m.getMakeModel();
        d.width = video ? 0 : m.getWidth();
        d.height = video ? 0 : m.getHeight();
        d.bytes = item.mediaFile().sizeBytes();
        return d;
    }

    /** «12 Мп» of a width x height, null without a size. */
    @Nullable
    static String megapixels(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        return Math.max(1, Math.round(w * (float) h / 1e6f)) + Lang.t(" Мп", " MP");
    }

    static String folderOf(@Nullable String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return "";
        String dir = path.substring(0, slash);
        int prev = dir.lastIndexOf('/');
        return prev >= 0 ? dir.substring(prev + 1) : dir;
    }

    private void onPhotoTapped(View view) {
        if (isCompareMode()) {
            paneFullExif = !paneFullExif;
            if (getParentFragment() instanceof ImageCompareFragment) ((ImageCompareFragment) getParentFragment()).refreshLabels();
            else refreshFacts();
            return;
        }
        if (slideshow && view != pagerView) stopSlideshow(); // a tap on the photo stops the slideshow (and brings the bars back)
        chromeVisible = !chromeVisible;
        if (chrome != null) GalleryMotion.bars(chrome.top, chrome.bottom, chromeVisible); // P498: slid off and back
        if (miniExif != null) Motion.fade(miniExif, !chromeVisible && !currentIsVideo() && !slideshow);
        placeOverlays();
    }

    /**
     * The zoom pill relative to the fit scale («250 %»), hidden at 1x. Also reached from SubsamplingScaleImageView callbacks
     * and the compare fragment's posted zoom sync, possibly after onDestroyView: then there is nothing to update.
     */
    public void updateScaleText() {
        if (root == null) return;
        SubsamplingScaleImageView view = getCurrentSSIV();
        TextView pill = isCompareMode() ? paneZoom : zoomPill;
        if (pill == null) return;
        if (view == null || !view.isReady() || view.getMinScale() <= 0) {
            pill.setVisibility(View.GONE);
            return;
        }
        float rel = view.getScale() / view.getMinScale();
        if (isCompareMode()) {
            // P196 (owner, 2026-10-17: «они должны начинаться не со 100%, а с 0%. т.е. без кропа 0%»): the compare panes count the
            // zoom past the whole photo, shown at every scale
            pill.setText(comparePercent(rel));
            pill.setVisibility(View.VISIBLE);
            return;
        }
        // P491 (owner, 2026-11-03: «Про 0%, я имел ввиду ... просто при кропе фото»): the viewer's zoom counts from 0 % for the
        // whole photo as the compare panes do («115 %» was 15 % past it)
        pill.setText(comparePercent(rel));
        pill.setVisibility(rel > 1.02f ? View.VISIBLE : View.GONE);
    }

    /** P196: a compare pane's zoom past the fitted photo: «0 %» for the whole photo, «100 %» at twice its fit scale. */
    static String comparePercent(float relativeScale) {
        return String.format(Locale.ROOT, "%d %%", Math.max(0, Math.round((relativeScale - 1f) * 100f)));
    }

    /** P196: the compare pane's label again (a tap on the other pane switched the full info). */
    void refreshPaneLabel() {
        if (paneLabel != null) refreshFacts();
    }

    private void refreshFacts() {
        if (pagerView == null || root == null || getContext() == null) return;
        int position = current();
        if (coverPending()) { // P637: the title is the shot's in work, not the older photo's under the placeholder
            bindPendingMeta();
            return;
        }
        if (photos.isEmpty() || position < 0 || position >= photos.size()) return;
        GalleryItem item = photos.get(position);
        if (GalleryVideo.isVideo(item.mediaFile())) { // P498: «Сегодня, 11:17» / «Видео · 0:42 · MP4»
            long len = GalleryVideo.durationMs(item.mediaFile());
            String when = GalleryUi.dayTime(item.mediaFile().modifiedMs(), System.currentTimeMillis());
            String sub = joinDot(Lang.t("Видео", "Video"), len > 0 ? GalleryVideo.clock(len) : null,
                    GalleryFormat.extension(item.mediaFile().displayName()).toUpperCase(Locale.ROOT));
            if (chrome != null) chrome.bindMeta(when, sub, null, GalleryFormat.of(item, rawBases), false);
            if (miniExif != null) miniExif.setText(sub);
            if (paneLabel != null) paneLabel.setText(paneText(joinDot(when, sub), item.mediaFile().displayName()));
            return;
        }
        // P551: the file read on the EXIF thread; the page's labels bound when it is done (if the page is still shown)
        factsModel.updateModelAsync(requireContext().getContentResolver(), item.mediaFile(), () -> {
            if (pagerView == null || root == null || getContext() == null || current() != position
                    || position >= photos.size() || photos.get(position) != item || coverPending()) return;
            bindExif(position, item);
        });
    }

    private void bindExif(int position, GalleryItem item) {
        ExifDialogModel m = factsModel.getExifDataModel();
        GalleryFormat format = GalleryFormat.of(item, rawBases);
        boolean uhdr = pageAdapter != null && pageAdapter.isHdrAvailable(position);
        String route = GalleryFormat.routeOf(m.getDescription());
        String when = GalleryUi.dayTime(item.mediaFile().modifiedMs(), System.currentTimeMillis());
        String lens = m.getFocal35Value() != null ? m.getFocal35Value() : m.getFocalValue();
        // SIMPLE_UI §5: with what, not how («24 мм · 12 Мп · JPEG»); ISO and shutter are in «Сведения»
        String sub = joinDot(lens, megapixels(m.getWidth(), m.getHeight()), format.label.isEmpty() ? null : format.label);
        if (chrome != null) chrome.bindMeta(when, sub, route, format, uhdr);
        if (miniExif != null) miniExif.setText(joinDot(m.getShutterValue(), m.getIsoValue(), m.getFnumValue(), m.getFocalValue()));
        if (paneLabel != null) bindPaneLabel(item, m, format, route, when);
    }

    private void bindPaneLabel(GalleryItem item, ExifDialogModel m, GalleryFormat format, @Nullable String route, String when) {
        Context c = paneLabel.getContext();
        String time = when.substring(Math.max(0, when.lastIndexOf(' ') + 1));
        String text = paneFullExif ? joinDot(time, m.getIsoValue(), m.getShutterValue(), m.getFnumValue(), m.getFocalValue(), format.label)
                : joinDot(time, m.getIsoValue());
        // P196: the file's real resolution and the phone on their own line («8192×6144 · 50 Мп · vivo X200 Ultra»)
        String file = paneFullExif ? fileLine(m.getResolutionValue(), m.getMakeModel()) : null;
        // P65: the file name on a second, smaller and muted line (which two shots are compared)
        String name = item.mediaFile().displayName();
        paneLabel.setText(paneText(text, file, name));
        // P570: a colour icon in tonal mono of the accent, a plain glyph tinted
        int s = GalleryUi.dp(c, 15), accent = GalleryUi.accent(c);
        android.graphics.drawable.Drawable routeD = route != null ? com.scamera.app.ui.Icons.inked(c, GalleryFormat.routeIcon(route), accent) : null;
        android.graphics.drawable.Drawable fmt = com.scamera.app.ui.Icons.inked(c, format.icon, accent);
        android.graphics.drawable.Drawable icons = combine(c, routeD, fmt, s);
        paneLabel.setCompoundDrawables(icons, null, null, null);
        paneLabel.setContentDescription((route != null ? route + ", " : "") + format.label + ", " + text
                + (file == null || file.isEmpty() ? "" : ", " + file) + (name == null || name.isEmpty() ? "" : ", " + name));
    }

    /** P196: «8192×6144 · 50 Мп · vivo X200 Ultra»; empty without either. */
    static String fileLine(@Nullable String resolution, @Nullable String device) {
        return joinDot(resolution, device);
    }

    /** The label's text: the EXIF line, then the file name (85 %, muted) when there is one. */
    static CharSequence paneText(String text, @Nullable String name) {
        return paneText(text, null, name);
    }

    /** P196: the EXIF line, the resolution · phone line, then the file name (85 %, muted); empty lines left out. */
    static CharSequence paneText(String text, @Nullable String fileLine, @Nullable String name) {
        if (fileLine != null && !fileLine.isEmpty()) text = text.isEmpty() ? fileLine : text + "\n" + fileLine;
        if (name == null || name.isEmpty()) return text;
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(text);
        if (s.length() > 0) s.append('\n');
        int start = s.length();
        s.append(name);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(GalleryUi.MUTED), start, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private static android.graphics.drawable.Drawable combine(Context c, @Nullable android.graphics.drawable.Drawable a,
                                                              @Nullable android.graphics.drawable.Drawable b, int s) {
        List<android.graphics.drawable.Drawable> list = new ArrayList<>();
        for (android.graphics.drawable.Drawable d : new android.graphics.drawable.Drawable[]{a, b}) if (d != null) list.add(d);
        if (list.isEmpty()) return null;
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(list.toArray(new android.graphics.drawable.Drawable[0]));
        int gap = GalleryUi.dp(c, 4);
        for (int i = 0; i < list.size(); ++i) {
            l.setLayerSize(i, s, s);
            l.setLayerInsetLeft(i, i * (s + gap));
        }
        l.setBounds(0, 0, list.size() * s + (list.size() - 1) * gap, s);
        return l;
    }

    static String joinDot(String... parts) {
        StringBuilder s = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (s.length() > 0) s.append(" · ");
            s.append(p);
        }
        return s.toString();
    }

    private boolean isCompareMode() {
        return screenMode != null && screenMode.equalsIgnoreCase(Constants.MODE_COMPARE);
    }

    public void handleImagesDeletedCallback(boolean isDeleted) {
        if (isDeleted && deletingAt >= 0 && deletingAt < photos.size()) {
            // P706: a copy without the photo - the list is the view model's (the folder's own list when no view filter is on,
            // shared with the grid): removing from it changed the folder under the grid before its reload
            final List<GalleryItem> rest = new ArrayList<>(photos);
            rest.remove(deletingAt);
            photos = rest;
            shownPage = Math.min(deletingAt, Math.max(0, photos.size() - 1));
            if (!photos.isEmpty()) bindPhotos(photos);
            if (getContext() != null) com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Удалено: ", "Deleted: ") + GalleryUi.shots(1));
            deletingAt = -1;
            if (photos.isEmpty()) {
                library.setUpdatePending(true);
                // the grid: navigateUp alone did nothing for the viewer opened from the camera (the deleted photo stayed shown)
                if (root != null && navigation != null) onGalleryButtonClick();
            } else library.setUpdatePending(true);
        } else if (getContext() != null) {
            com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Не удалось удалить", "Deletion failed"));
        }
    }
}
