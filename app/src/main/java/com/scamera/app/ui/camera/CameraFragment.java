/*
 *
 *  PhotonCamera
 *  CameraFragment.java
 *  Copyright (C) 2020 - 2021  Eszdman
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.scamera.app.ui.camera;
import android.graphics.Bitmap;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;

import com.scamera.app.ui.camera.views.viewfinder.HorizonIndicatorView;
import com.scamera.app.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.scamera.app.R;
import com.scamera.app.api.CameraEvents;
import com.scamera.app.api.CameraManager2;
import com.scamera.app.api.CameraReflectionApi;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.app.base.BaseActivity;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.capture.CaptureEvents;
import com.scamera.app.circularbarlib.api.ManualInstanceProvider;
import com.scamera.app.circularbarlib.api.ManualModeConsole;
import com.scamera.app.control.Swipe;
import com.scamera.app.control.TouchFocus;
import com.scamera.app.databinding.CameraScreenBinding;
import com.scamera.app.gallery.ui.GalleryActivity;
import com.scamera.app.processing.ProcessingEvents;
import com.scamera.app.processing.parameters.ExposureIndex;
import com.scamera.app.processing.parameters.IsoExpoSelector;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.camera.binding.CustomBinding;
import com.scamera.app.ui.camera.data.CameraLensData;
import com.scamera.app.ui.camera.model.CameraFragmentModel;
import com.scamera.app.ui.camera.viewmodel.*;
import com.scamera.app.ui.camera.views.settingsbar.SettingsBarLayout;
import com.scamera.app.ui.camera.views.viewfinder.GLPreview;
import com.scamera.app.ui.camera.views.viewfinder.SurfaceViewOverViewfinder;
import com.scamera.app.ui.settings.SettingsActivity;
import com.scamera.app.util.log.Logger;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The camera screen: the viewfinder, the controls around it and the capture controller behind it. It owns the screen's view
 * models and bindings, opens and closes the camera with its lifecycle and hears the controller's and the processing's events.
 */
public class CameraFragment extends Fragment implements BaseActivity.BackPressedListener {
    static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";
    private static final String TAG = "CameraFragment";
    // savedInstanceState keys of the last back / front camera, the processing notification's channel and id
    private static final String ACTIVE_BACKCAM_ID = "ACTIVE_BACKCAM_ID";
    private static final String ACTIVE_FRONTCAM_ID = "ACTIVE_FRONTCAM_ID";
    private static final String PROCESSING_CHANNEL = "NOTIFICATION_CHANNEL_ID";
    private static final int NOTIFICATION_ID = 1;
    /** FULL settings sheet height as a share of the viewfinder height (P25, owner's answer 3). */
    private static final float SHEET_MAX_HEIGHT_FRACTION = 0.86f;
    /**
     * The back / front camera last used: the stored camera on a fresh start, the saved one after a configuration change
     * (onViewStateRestored). The back one is never "1", the usual front camera id.
     */
    public static String sActiveBackCamId = "0", sActiveFrontCamId = "1";
    /** Two threads for the camera's open / session work and the screen's own background jobs. */
    private final ExecutorService processPool = Executors.newFixedThreadPool(2);
    public SurfaceViewOverViewfinder finderOverlay;
    public Map<String, CameraLensData> lensData;
    public Activity hostActivity;
    public CaptureController captureController;
    public AuxButtonsViewModel auxModel;
    public CameraScreenBinding cameraViews;
    public Swipe swipeGestures;
    public GLPreview finderView;
    /** The screen's long : short side ratio (the layout's notch margin and camera container follow it). */
    public float screenAspect;
    private TimerFrameCountViewModel timerModel;
    private CameraFragmentViewModel fragmentModel;
    private CameraUIView uiView;
    private CameraUIController uiController;
    private TouchFocus tapFocus;
    /** P42: face detection and tracking autofocus, created with the touch focus. */
    private com.scamera.app.control.subject.SubjectFocus mSubjectFocus;
    /** Shutter and self-timer sounds (P16). */
    private CameraSounds sounds;
    private NotificationManagerCompat notifications;
    private SettingsManager lensSettings;
    private ManualModeConsole manualConsole;
    private HorizonIndicatorView horizonView;


    public TouchFocus getTouchFocus() { return tapFocus; }

    @Nullable
    public com.scamera.app.control.subject.SubjectFocus getSubjectFocus() { return mSubjectFocus; }

    public CaptureController getCaptureController() { return captureController; }

    /** Pinch / dial: continuous zoom over the configured modules. */
    public void zoomTo(float requested) {
        final String slot = com.scamera.app.control.ZoomController.onZoomChanged(requested);
        if (slot != null) {
            // Same path as tapping the module button: profile + Camera ID, session restart.
            com.scamera.app.settings.ModuleRegistry.select(slot);
            uiController.onAuxButtonClicked(com.scamera.app.settings.ModuleRegistry.camera(slot));
        } else if (captureController != null) {
            captureController.onZoomChanged();
        }
        Log.d("ZoomController", "zoomTo " + requested + " zoom=" + com.scamera.app.control.ZoomController.zoom()
                + " residual=" + com.scamera.app.control.ZoomController.residual() + " switch=" + slot);
        showZoomLabel();
    }

    public ManualModeConsole sharedConsole() { return manualConsole; }

    public CameraFragmentViewModel getCameraFragmentViewModel() { return fragmentModel; }

    @Override
    public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        hostActivity = requireActivity();
        notifications = NotificationManagerCompat.from(hostActivity);
        lensSettings = Objects.requireNonNull(ScameraApp.getInstance(hostActivity)).getSettingsManager();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        cameraViews = DataBindingUtil.inflate(inflater, R.layout.camera_screen, container, false);
        Log.d(TAG, "camera screen layout inflated");
        takeViews();
        bindModels();
        return cameraViews.getRoot();
    }

    /** The view models, the screen's aspect, the manual console and the viewfinder views of the inflated layout. */
    private void takeViews() {
        final ViewModelProvider models = new ViewModelProvider(this);
        fragmentModel = models.get(CameraFragmentViewModel.class);
        timerModel = models.get(TimerFrameCountViewModel.class);
        auxModel = models.get(AuxButtonsViewModel.class);
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        logScreen(dm);
        screenAspect = (float) Math.max(dm.heightPixels, dm.widthPixels) / Math.min(dm.heightPixels, dm.widthPixels);
        fragmentModel.setScreenAspectRatio(screenAspect);
        manualConsole = ManualInstanceProvider.newConsole();
        // P87: the manual rulers' ISO / shutter range (vendor pro range, frame-duration ceiling, the AE's own ISO extremes)
        com.scamera.app.circularbarlib.camera.CameraProperties.rangeProvider =
                new com.scamera.app.circularbarlib.camera.CameraProperties.RangeProvider() {
                    @Override
                    public android.util.Range<Integer> iso(CameraCharacteristics c) {
                        return com.scamera.app.capture.ExposureRanges.iso(c,
                                captureController == null ? null : captureController.physicalId());
                    }

                    @Override
                    public android.util.Range<Long> exposure(CameraCharacteristics c) {
                        return com.scamera.app.capture.ExposureRanges.shutter(c);
                    }
                };
        finderOverlay = cameraViews.vfLayer.vfOverlay;
        finderView = cameraViews.vfLayer.texture;
    }

    /** The models into the layouts: the screen model everywhere, the timer to the shutter row and the viewfinder, the lens buttons. */
    private void bindModels() {
        final CameraFragmentModel screen = fragmentModel.getCameraFragmentModel();
        cameraViews.setUimodel(screen);
        cameraViews.topRow.setUimodel(screen);
        cameraViews.bottomBar.shutterRow.setUimodel(screen);
        final com.scamera.app.ui.camera.model.TimerFrameCountModel timer = timerModel.getTimerFrameCountModel();
        cameraViews.bottomBar.shutterRow.setTimermodel(timer);
        cameraViews.vfLayer.setTimermodel(timer);
        cameraViews.setAuxmodel(auxModel.getAuxButtonsModel());
    }

    /**
     * The layout editor's preview of the camera screen (CameraLayout calls it while inflating; no fragment, view model or data
     * binding runs there): what the bindings set at runtime, through the same CustomBinding adapters - the 3:4 bottom-bar
     * anchor and viewfinder, the hidden settings sheet, the notch margin and container anchor of the preview device's aspect.
     */
    static void preparePreviewLayout(View root) {
        CustomBinding.frameRatio(root.findViewById(R.id.frame_anchor), "3:4");
        final View viewfinder = root.findViewById(R.id.vf_layer);
        final ViewGroup.LayoutParams lp = viewfinder == null ? null : viewfinder.getLayoutParams();
        if (lp instanceof ConstraintLayout.LayoutParams) {
            final ConstraintLayout.LayoutParams block = (ConstraintLayout.LayoutParams) lp;
            block.height = 0; // measured from the ratio
            block.dimensionRatio = "3:4";
            viewfinder.setLayoutParams(block);
        }
        // without the bindings the editor would show the sheet's initial PEEK
        final View sheet = root.findViewById(R.id.settings_bar);
        if (sheet != null) sheet.setVisibility(View.INVISIBLE);
        final DisplayMetrics metrics = root.getResources().getDisplayMetrics();
        final float aspect = (float) Math.max(metrics.heightPixels, metrics.widthPixels)
                / Math.min(metrics.heightPixels, metrics.widthPixels);
        CustomBinding.placeTopBar(root.findViewById(R.id.top_row), aspect);
        CustomBinding.anchorCameraBlock(root.findViewById(R.id.camera_stage), aspect);
    }

    @Override
    public void onViewCreated(@NonNull final View view, Bundle saved) {
        uiView = new CameraUIViewImpl(this);
        uiController = new CameraUIController(this);
        uiView.listenTo(uiController);
        captureController = new CaptureController(hostActivity, processPool, new FragmentCameraEvents());
        manualConsole.observeValues(captureController.getParamController());
        // The manual panel's toasts («ISO: авто», the EV lock) use the camera screen's card toast.
        manualConsole.setMessageSink(this::showCardToast);
        // P214f: the card above the bottom bar shows the one message channel (UiMessages): CaptureController, the shade,
        // the manual panel, the processing notices; a long press opens the last 20 messages.
        if (messageCard != null) messageCard.release();
        messageCard = new MessageCard(cameraViews.shadeToast, this::openMessageHistory);
        com.scamera.app.util.UiMessages.attach(messageCard, requireContext());
        finderView.setManualModeConsole(manualConsole);
        ScameraApp.setCaptureController(captureController);
        swipeGestures = new Swipe(this);
        horizonView = cameraViews.vfLayer.levelView;
        final HorizonIndicatorView horizon = horizonView;
        if (horizon != null) {
            final var motion = ScameraApp.getGyro();
            if (motion != null) {
                horizon.updateDisplayRotation(fragmentModel.getCameraFragmentModel().getOrientation());
                horizon.setGyro(motion);
            }
            horizon.setVisible(PreferenceKeys.isHorizonOn());
        }
        setUpSettingsBar();
        initZoomDial(view);
        initBottomChrome();
        // P214g: on the window's decor view, which stays attached until the hostActivity goes (this view is detached before onDestroy)
        if (hostActivity.getWindow() != null)
            hostActivity.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(sessionFocus);
    }

    /**
     * P32: the bottom controls sit 10 % of the bottom bar's height higher, and the shade's HIDDEN handle starts at the
     * viewfinder's bottom edge instead of covering it; the manual palette and the zoom ruler keep clear of the handle
     * (after initZoomDial).
     */
    private void initBottomChrome() {
        attachBottomChrome(cameraViews, zoomDial);
        // SIMPLE_UI §1 row 1: «Про» opens / closes the Pro panel (it replaces the panel's own toggle card), «Ещё» opens the
        // quick sheet (it replaces the sheet's handle)
        View bar = cameraViews.bottomBar.getRoot();
        View panelView = cameraViews.proPanel;
        if (panelView instanceof com.scamera.app.circularbarlib.ui.ExpandingManualPanel) {
            com.scamera.app.circularbarlib.ui.ExpandingManualPanel panel =
                    (com.scamera.app.circularbarlib.ui.ExpandingManualPanel) panelView;
            // SIMPLE_UI §1: the LUT pill and tray; Pro and the tray close each other
            // P574 (the approved concept): the tray's button is the top row's round LUT item
            lutTray = new LutTray(cameraViews.topRow.lutButton, cameraViews.lutTray,
                    cameraViews.vfLayer.texture, this::openLutSettings, true);
            lutTray.setPageOpener(this::openSettingsPage);
            // P293 (owner, 2026-10-20: «одновременно открытые шторка и меню фильтров налазят друг на друга»): the tray closes Pro
            // and the quick sheet; the sheet, at any level reached in any way (finger, «Ещё», a swipe, the model), closes the tray
            lutTray.setOnOpen(() -> {
                // P630: the «Oldstyle» mode keeps its manual row; the tray closes the adjustment card instead
                if (oldstyleControls != null && oldstyleControls.on()) oldstyleControls.setOpen(false, true);
                else if (panel.isExpanded()) panel.setExpanded(false, true);
                if (fragmentModel.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN)
                    fragmentModel.setSheetLevel(CameraFragmentModel.SHEET_HIDDEN);
            });
            if (sheetTrayCallback != null) fragmentModel.getCameraFragmentModel().removeOnPropertyChangedCallback(sheetTrayCallback);
            sheetTrayCallback = new androidx.databinding.Observable.OnPropertyChangedCallback() {
                @Override public void onPropertyChanged(androidx.databinding.Observable sender, int id) {
                    if (id != com.scamera.app.BR.sheetLevel) return;
                    if (lutTray != null && fragmentModel.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN) lutTray.setOpen(false);
                    if (oldstyleControls != null)
                        oldstyleControls.setShadeShown(fragmentModel.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN);
                    showLutPill(panel);
                }
            };
            fragmentModel.getCameraFragmentModel().addOnPropertyChangedCallback(sheetTrayCallback);
            panel.setOnExpandedListener(open -> {
                com.scamera.app.ui.camera.views.BottomChrome.setSideOn(bar, R.id.pro_button, open);
                if (open && lutTray != null) lutTray.setOpen(false);
                showLutPill(panel);
            });
            // P495 (owner, 2026-11-03): «Про» with the shade up lowers the shade and opens the Pro panel
            bar.findViewById(R.id.pro_button).setOnClickListener(v -> {
                // P630: «Oldstyle» holds the row open («Про» steps aside there)
                if (com.scamera.app.settings.OldstyleMode.on()) return;
                if (fragmentModel.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN) {
                    fragmentModel.setSheetLevel(CameraFragmentModel.SHEET_HIDDEN);
                    panel.setExpanded(true, true);
                } else panel.setExpanded(!panel.isExpanded(), true);
            });
            com.scamera.app.ui.camera.views.BottomChrome.setSideOn(bar, R.id.pro_button, panel.isExpanded());
            // P630: the «Oldstyle» mode's controls (the manual row held open, the adjustments button and card)
            oldstyleControls = new OldstyleControls(cameraViews.oldstyleControls, cameraViews.oldstyleHud,
                    cameraViews.vfLayer.texture, text -> showSnackBar(text.toString()));
            oldstyleControls.apply(com.scamera.app.settings.OldstyleMode.on(), panel, bar.findViewById(R.id.pro_button), false);
        }
        // P495 (owner, 2026-11-03): «Ещё» again lowers the shade; P574: «Ещё» is the bottom grabber now (a tap as a swipe up)
        final View more = bar.findViewById(R.id.more_button);
        more.setOnClickListener(v -> fragmentModel.setSheetLevel(
                fragmentModel.getSheetLevel() == CameraFragmentModel.SHEET_HIDDEN ? CameraFragmentModel.SHEET_PEEK : CameraFragmentModel.SHEET_HIDDEN));
        // the 56 x 18 dp grabber answers to 48dp; TalkBack hears what a tap does at the sheet's level (open, or close it again)
        TouchTargets.ensure(more, Math.round(48 * more.getResources().getDisplayMetrics().density));
        bindMoreButton(more);
        if (moreButtonCallback != null) fragmentModel.getCameraFragmentModel().removeOnPropertyChangedCallback(moreButtonCallback);
        moreButtonCallback = new androidx.databinding.Observable.OnPropertyChangedCallback() {
            @Override public void onPropertyChanged(androidx.databinding.Observable sender, int id) {
                if (id == com.scamera.app.BR.sheetLevel) bindMoreButton(more);
            }
        };
        fragmentModel.getCameraFragmentModel().addOnPropertyChangedCallback(moreButtonCallback);
        placeMessageBand(cameraViews);
        attachTopGrabber();
    }

    /**
     * SIMPLE_UI §1: the message band sits in the free band just above the bottom bar's row 1 (Про · lenses · Ещё), 3dp over
     * it (the concept's .toast), 22dp x k high with 12sp x k text; measured again on every layout of the bar.
     */
    public static void placeMessageBand(CameraScreenBinding binding) {
        android.widget.TextView band = binding.shadeToast;
        View bar = binding.bottomBar.getRoot();
        android.content.Context c = band.getContext();
        ViewGroup.LayoutParams lp = band.getLayoutParams();
        // P583 (the concept's toast): a 34px capsule of rgba(20, 20, 22, .9), 13px semibold, 14px sides, 14px under the
        // preview's top edge (over the picture, never over a control)
        lp.height = com.scamera.app.circularbarlib.ui.UiScale.dp(c, 34);
        band.setLayoutParams(lp);
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(band, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE);
        ConceptStyle.text(band, 13, com.scamera.app.ui.style.Fonts.SEMIBOLD);
        band.setBackground(ConceptStyle.shape(c, 0xE6141416, 17, false));
        final int bandPad = com.scamera.app.circularbarlib.ui.UiScale.dp(c, 14);
        band.setPadding(bandPad, 0, bandPad, 0);
        final View preview = binding.vfLayer.texture;
        View.OnLayoutChangeListener place = (v, l, t, r, b, ol, ot, or, ob) -> {
            View strip = bar.findViewById(R.id.lens_strip), pro = null; // P574: the strip is the top of the bottom controls
            if (strip == null || band.getHeight() == 0 && band.getVisibility() == View.VISIBLE) return;
            int[] at = new int[2], bandAt = new int[2];
            int top = Integer.MAX_VALUE;
            for (View row : new View[]{strip, pro}) {
                if (row == null || row.getVisibility() != View.VISIBLE) continue;
                row.getLocationInWindow(at);
                top = Math.min(top, at[1]);
            }
            if (top == Integer.MAX_VALUE) return;
            float want = top - com.scamera.app.circularbarlib.ui.UiScale.dpF(c, 3);
            for (View item : new View[]{band, binding.zoomReadout}) {
                item.getLocationInWindow(bandAt);
                float h = item.getLayoutParams().height > 0 ? item.getLayoutParams().height : item.getHeight();
                if (item == band && preview.getHeight() > 0) {
                    // the toast: its top 14px under the preview's top
                    preview.getLocationInWindow(at);
                    item.setTranslationY(at[1] + com.scamera.app.circularbarlib.ui.UiScale.dpF(c, 14) - (bandAt[1] - item.getTranslationY()));
                    continue;
                }
                item.setTranslationY(want - (bandAt[1] - item.getTranslationY() + h));
            }
        };
        bar.addOnLayoutChangeListener(place);
        band.addOnLayoutChangeListener(place);
        preview.addOnLayoutChangeListener(place);
    }

    /** The bottom chrome's wiring on the camera screen's views (also used by the layout tests, P43). */
    public static void attachBottomChrome(CameraScreenBinding binding,
                                          @Nullable com.scamera.app.ui.camera.views.ZoomDialView zoomDial) {
        // P157: the bottom bar's controls in proportion to the screen's width (before attach: it reads the row's margin)
        View bar = binding.bottomBar.getRoot();
        com.scamera.app.ui.camera.views.BottomChrome.scaleBar(bar,
                com.scamera.app.ui.camera.views.BottomChrome.scale(bar.getContext()));
        com.scamera.app.ui.camera.views.BottomChrome.attach(binding.bottomBar.getRoot(),
                binding.bottomBar.shutterRow.getRoot(), binding.bottomBar.lensStrip,
                binding.settingsSheetHandleSlot, binding.vfLayer.texture, zoomDial, binding.proPanel,
                // P176: at 16:9 the shade's HIDDEN handle is a pill the shade grows out of
                floats -> binding.settingsBar.setPillHandle(floats));
    }

    public void showZoomLabel() {
        showZoomLabel(true);
    }

    /**
     * The active lens shows the live zoom; SIMPLE_UI §1: the zoom readout in the message band while the zoom changes (a
     * drag on the strip, a pinch), not for a tap on a lens.
     */
    private void showZoomLabel(boolean readout) {
        float zoom = com.scamera.app.control.ZoomController.zoom();
        if (readout && zoomReadout != null) {
            refreshZoomReadout();
            zoomReadout.show(zoom);
            // the readout takes the band's place from any message meanwhile
            if (cameraViews != null) {
                View band = cameraViews.shadeToast;
                band.setAlpha(0f);
                band.removeCallbacks(bandBack);
                band.postDelayed(bandBack, com.scamera.app.ui.camera.views.ZoomReadoutView.HIDE_MS + 250);
            }
        }
        View layout = findViewById(R.id.lens_strip);
        if (layout instanceof com.scamera.app.ui.camera.views.AuxButtonsLayout)
            ((com.scamera.app.ui.camera.views.AuxButtonsLayout) layout).setZoomLabel(zoom);
    }

    private final Runnable bandBack = () -> {
        if (cameraViews != null) cameraViews.shadeToast.setAlpha(1f);
    };

    /** SIMPLE_UI §1: the zoom readout (the old 232 x 48dp dial card over the viewfinder is gone). */
    @Nullable private com.scamera.app.ui.camera.views.ZoomReadoutView zoomReadout;
    /** Kept null: BottomChrome's ruler hooks see no dial. */
    @Nullable private com.scamera.app.ui.camera.views.ZoomDialView zoomDial;
    /** The strip drag's target zoom and the zoom shown (eased towards it every frame). */
    private float zoomTarget = 1f, zoomShown = 1f;
    private boolean zoomEasing;
    private final android.view.Choreographer.FrameCallback zoomStep = frameTimeNanos -> stepZoom();
    /** One stop (x2) of zoom per this many dp of drag (the concept's 70px). */
    static final float ZOOM_STOP_DP = 70f;
    /** The shown zoom moves this share of the way (in log) to the target per frame. */
    static final float ZOOM_EASE = 0.22f;

    /**
     * SIMPLE_UI §1, the owner's requirement «свайп по полосе = плавный зум»: a horizontal drag on the lens strip sets a
     * target zoom on a log scale (one stop per {@link #ZOOM_STOP_DP}); the zoom eases to it every frame
     * ({@link #ZOOM_EASE}), so it never jumps; crossing a module mark switches the module (the existing path, a light
     * haptic tick). Taps on lenses and the 17 Ultra presets work as before.
     */
    private void initZoomDial(View root) {
        if (cameraViews != null) zoomReadout = cameraViews.zoomReadout;
        View strip = root.findViewById(R.id.lens_strip);
        if (!(strip instanceof com.scamera.app.ui.camera.views.AuxButtonsLayout)) return;
        com.scamera.app.ui.camera.views.AuxButtonsLayout lenses = (com.scamera.app.ui.camera.views.AuxButtonsLayout) strip;
        lenses.setZoomDrag(this::dragZoom);
        lenses.setDialRefresh(() -> showZoomLabel(false));
        lenses.setPresetZoom(this::zoomTo); // P72 17U presets
        refreshZoomReadout();
    }

    /** A drag step on the strip ({@code dx} px since the last one; right zooms in). */
    void dragZoom(float dx) {
        final float density = getResources().getDisplayMetrics().density;
        if (!zoomEasing) {
            com.scamera.app.control.ZoomController.syncToActive();
            zoomShown = zoomTarget = com.scamera.app.control.ZoomController.zoom();
        }
        zoomTarget = nextZoomTarget(zoomTarget, dx, ZOOM_STOP_DP * density,
                com.scamera.app.control.ZoomController.minZoom(), com.scamera.app.control.ZoomController.maxZoom());
        if (!zoomEasing) {
            zoomEasing = true;
            android.view.Choreographer.getInstance().postFrameCallback(zoomStep);
        }
    }

    /** The target after a drag of {@code dx} px: x2 per {@code stopPx}, within min..max. */
    static float nextZoomTarget(float target, float dx, float stopPx, float min, float max) {
        float next = (float) (target * Math.pow(2, dx / stopPx));
        return Math.max(min, Math.min(max, next));
    }

    /** One frame of the ease (in log): returns the new shown zoom. */
    static float easeZoom(float shown, float target) {
        double a = Math.log(shown), b = Math.log(target);
        double n = Math.abs(b - a) < .002 ? b : a + (b - a) * ZOOM_EASE;
        return (float) Math.exp(n);
    }

    private void stepZoom() {
        if (cameraViews == null) {
            zoomEasing = false;
            return;
        }
        zoomShown = easeZoom(zoomShown, zoomTarget);
        String before = com.scamera.app.settings.ModuleRegistry.active();
        zoomTo(zoomShown);
        if (before != null && !before.equals(com.scamera.app.settings.ModuleRegistry.active()))
            cameraViews.bottomBar.lensStrip.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (Math.abs(Math.log(zoomShown) - Math.log(zoomTarget)) > .0005) android.view.Choreographer.getInstance().postFrameCallback(zoomStep);
        else zoomEasing = false;
    }

    private void refreshZoomReadout() {
        if (zoomReadout == null) return;
        com.scamera.app.control.ZoomController.syncToActive();
        java.util.List<Float> ratios = new java.util.ArrayList<>();
        for (String slot : com.scamera.app.control.ZoomController.lenses())
            ratios.add(com.scamera.app.settings.ModuleRegistry.zoom(slot));
        zoomReadout.configure(com.scamera.app.control.ZoomController.minZoom(),
                com.scamera.app.control.ZoomController.maxZoom(), ratios);
    }

    private void setUpSettingsBar() {
        com.scamera.app.settings.SettingsMigration.resetRemovedSettings(getResources());
        SettingsBarLayout sheet = cameraViews.settingsBar;
        sheet.attach(shadeHost);
        // The HIDDEN handle is the sheet's top edge (the strip lives in the bottom bar now, so nothing passes through);
        // since P32 it lies under the viewfinder, at the bottom bar's top (initBottomChrome); at 16:9 a pill (P176).
        // The sheet styles it.
        // SIMPLE_UI §1: no handle on the camera screen any more - «Ещё» and a swipe up open the quick sheet
        sheet.setHiddenHandle(null, null);
        // A level reached by a finger (or settled after a request) goes back to the model as is,
        // so a later rebind (invalidateAll on a camera restart or mode switch, notifyChange on
        // rotation or a new thumbnail) keeps the sheet there. Handle taps and flings ask for
        // their level the same way.
        // The model setter is idempotent, so a level the model asked for is not sent again.
        sheet.setOnSheetLevelListener(level -> {
            fragmentModel.setSheetLevel(level);
            // SIMPLE_UI §1: the LUT tray, like the Pro panel, only while the quick sheet is closed
            if (level != CameraFragmentModel.SHEET_HIDDEN && lutTray != null) lutTray.setOpen(false);
        });
        // FULL: a scrim over the viewfinder and the top bar; the viewfinder piece starts under the top bar.
        View scrim = cameraViews.shadeScrim, scrimTop = cameraViews.shadeScrimTop;
        sheet.setScrim(scrim, scrimTop);
        cameraViews.getRoot().addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int overlap = Math.max(0, cameraViews.topRow.getRoot().getBottom() - cameraViews.cameraStage.getTop());
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) scrim.getLayoutParams();
            if (params.topMargin != overlap) {
                params.topMargin = overlap;
                scrim.post(() -> scrim.setLayoutParams(params));
            }
            if (cameraViews != null) placeViewfinderPills(cameraViews);
        });
        // FULL is 86% of the viewfinder height.
        cameraViews.vfLayer.getRoot().addOnLayoutChangeListener(
                (v, l, t, r, b, l0, t0, r0, b0) -> sheet.setMaxSheetHeight(Math.round((b - t) * SHEET_MAX_HEIGHT_FRACTION)));
        // The tiles inflate the settings tree once: build them when the camera screen is idle, before the first swipe.
        android.os.Looper.myQueue().addIdleHandler(() -> {
            if (cameraViews != null) cameraViews.settingsBar.prewarm();
            return false;
        });
    }

    /** What the quick-settings shade needs from the camera screen. */
    private final com.scamera.app.ui.camera.views.settingsbar.ShadeHost shadeHost =
            new com.scamera.app.ui.camera.views.settingsbar.ShadeHost() {
        @Override
        public void applyCameraControl(com.scamera.app.settings.SettingType type, int value) {
            if (uiController != null)
                uiController.onChanged(new com.scamera.app.ui.camera.model.TopBarSettingsData<>(type, value));
        }

        @Override
        public void onSettingWritten(com.scamera.app.settings.ShadeCatalog.Entry entry) {
            if ("pref_show_grid_key".equals(entry.key)) invalidateSurfaceView();
            // P245: the «Горизонт» tile shows / hides the level at once (it was read on resume only)
            if (com.scamera.app.settings.ShadeCatalog.HORIZON.equals(entry.key) && horizonView != null)
                horizonView.setVisible(PreferenceKeys.isHorizonOn());
            // Read when the session is built (live RAW, 16:9, ... P214g: settings/SessionKeys): restart now (owner's answer 7).
            if (entry.sessionTime && captureController != null) captureController.restartCamera();
            // P411: «Мерцание ламп» to the camera's AE at once
            else if (com.scamera.app.settings.ShadeCatalog.FLICKER.equals(entry.key) && captureController != null)
                captureController.applyAntibanding();
        }

        @Override
        public void showMessage(CharSequence text) {
            showCardToast(text);
        }

        @Override
        public void openCatalog() {
            CameraFragment.this.openCatalog();
        }

        @Override
        public void openSettings() {
            launchSettings();
        }

        @Override
        public boolean openFormatSheet() {
            openFormatChooser();
            return true;
        }

        @Override
        public boolean openSizeSheet() {
            TopBar.openSizeSheet(CameraFragment.this);
            return true;
        }

        @Override
        public void onValuesChanged() {
            if (uiView != null) uiView.rebindTopBar();
        }

        @Override
        public void importConfigFile() {
            // P143: the settings screen owns the picker; it opens it at once and imports (the app restarts)
            watchSessionSettings();
            Intent intent = new Intent(hostActivity, SettingsActivity.class);
            intent.setAction(SettingsActivity.ACTION_IMPORT_CONFIG);
            openSettingsScreen(intent);
        }

        @Override
        public void openConfigFolder() {
            // P143: the settings screen owns the folder picker; it opens it at once and comes back
            watchSessionSettings();
            Intent intent = new Intent(hostActivity, SettingsActivity.class);
            intent.setAction(SettingsActivity.ACTION_OPEN_FOLDER);
            openSettingsScreen(intent);
        }
    };

    /** SIMPLE_UI §1: the first-run hint, once, in the message band for 4.5 s (no floating card over the controls). */
    private static final String FIRST_RUN_HINT = "ui_first_run_hint_shown";

    private void showHintOnce() {
        android.content.SharedPreferences prefs = lensSettings.getDefaultPreferences();
        if (prefs.getBoolean(FIRST_RUN_HINT, false) || cameraViews == null || messageCard == null) return;
        prefs.edit().putBoolean(FIRST_RUN_HINT, true).apply();
        messageCard.showFor(getString(R.string.first_run_hint), 4500);
    }

    private com.scamera.app.ui.camera.views.settingsbar.ShadeCatalogView catalogView;

    /**
     * The catalog «Добавить в шторку»: a full-screen page over the camera screen, the camera keeps running (an Activity
     * would close it and drop the shade to HIDDEN). Built on the first open.
     */
    void openCatalog() {
        if (cameraViews == null || hostActivity == null) return;
        ViewGroup root = (ViewGroup) cameraViews.getRoot();
        if (catalogView == null) {
            catalogView = new com.scamera.app.ui.camera.views.settingsbar.ShadeCatalogView(hostActivity,
                    com.scamera.app.settings.ShadeCatalog.get(hostActivity), cameraViews.settingsBar.pins(), this::closeCatalog);
            catalogView.setElevation(getResources().getDisplayMetrics().density * 40);
            // Below the status bar and above the navigation bar, wherever the camera screen draws.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(catalogView, (v, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                        | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                int base = Math.round(getResources().getDisplayMetrics().density * 12);
                v.setPadding(v.getPaddingLeft(), base + bars.top, v.getPaddingRight(), bars.bottom);
                return insets;
            });
            ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(0, 0);
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            root.addView(catalogView, lp);
            androidx.core.view.ViewCompat.requestApplyInsets(catalogView);
        } else {
            catalogView.reset();
        }
        catalogView.animate().cancel();
        catalogView.setVisibility(View.VISIBLE);
        catalogView.setTranslationY(root.getHeight() > 0 ? root.getHeight() : 2000);
        // P495: rises as an iOS sheet does, leaves quicker (closeCatalog)
        catalogView.animate().translationY(0).setDuration(340)
                .setInterpolator(com.scamera.app.ui.Motion.SHEET).start();
    }

    private boolean isCatalogOpen() {
        return catalogView != null && catalogView.getVisibility() == View.VISIBLE;
    }

    private void closeCatalog() {
        if (!isCatalogOpen()) return;
        final View panel = catalogView;
        panel.animate().cancel();
        panel.animate().translationY(panel.getHeight()).setDuration(240)
                .setInterpolator(com.scamera.app.ui.settings.SettingsMotion.EXIT).withEndAction(() -> panel.setVisibility(View.GONE)).start();
        if (cameraViews != null) cameraViews.settingsBar.refresh();
    }

    /** P214f: the message card of this screen (the one message channel's sink; null after onDestroy). */
    private MessageCard messageCard;
    /** SIMPLE_UI §1: the LUT pill and tray of the viewfinder. */
    @Nullable public LutTray lutTray;

    /** P574: the «Верхняя строка» sheet while it is up (or leaving); null before the first. */
    @Nullable private TopRowSheet.Handle topRowSheet;

    /** P574: the top row's sheet is up or still leaving (its window takes every touch until it is gone). */
    public boolean isTopRowSheetShowing() {
        return topRowSheet != null && topRowSheet.isShowing();
    }

    /** P574: closes the top row's sheet as «Отмена» (a swipe up, Back). */
    public void closeTopRowSheet() {
        if (topRowSheet != null) topRowSheet.close();
    }

    /** P574: the quick-settings shade is being dragged or settling (its BottomSheetBehavior). */
    public boolean isShadeSettling() {
        if (cameraViews == null) return false;
        try {
            final int state = com.google.android.material.bottomsheet.BottomSheetBehavior.from(cameraViews.settingsBar).getState();
            return state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING
                    || state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING;
        } catch (IllegalArgumentException noBehaviour) {
            return false;
        }
    }

    /** P574: the top zone's bottom in the camera screen's touch coordinates: the viewfinder's middle (the top bar above it too). */
    public float topRowZoneBottom() {
        if (cameraViews == null) return 0f;
        final View root = cameraViews.getRoot(), texture = cameraViews.vfLayer.texture;
        final int[] r = new int[2], t = new int[2];
        root.getLocationInWindow(r);
        texture.getLocationInWindow(t);
        return t[1] - r[1] + texture.getHeight() / 2f;
    }

    /**
     * P574 (the approved concept): opens the «Верхняя строка» sheet - a swipe down from the top zone or the top grabber - only while
     * the quick-settings shade is HIDDEN and settled (a swipe down with the shade up lowers the shade, as before) and never twice.
     * The LUT tray closes first. «Готово» stores the row (TopRow) and the top bar glides to it.
     */
    public void openTopRowSheet() {
        if (cameraViews == null || isTopRowSheetShowing() || !isResumed()) return;
        if (fragmentModel.getSheetLevel() != CameraFragmentModel.SHEET_HIDDEN || isShadeSettling()) return;
        if (lutTray != null && lutTray.isOpen()) lutTray.setOpen(false);
        final android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
        topRowSheet = TopRowSheet.show(cameraViews.getRoot(), com.scamera.app.settings.TopRow.load(prefs),
                TopBar::available, items -> {
                    com.scamera.app.settings.TopRow.save(prefs, items);
                    refreshTopBar();
                });
    }

    /**
     * P574: an item the top row's sheet can add was switched in the top row (the grid, the level, Ultra HDR): what the shade does
     * after its tile writes the same setting (the grid redrawn, the level shown, a session-time key restarts the camera), the
     * shade's values and the top bar again.
     */
    public void onTopRowSettingWritten(String key) {
        final android.content.Context c = getContext();
        if (c == null) return;
        final com.scamera.app.settings.ShadeCatalog.Entry e = com.scamera.app.settings.ShadeCatalog.get(c).entry(key);
        if (e != null) shadeHost.onSettingWritten(e);
        if (cameraViews != null) cameraViews.settingsBar.refresh();
        refreshTopBar();
    }

    /** The bottom grabber's description: a tap opens the quick settings, or closes them while they are up. */
    private void bindMoreButton(View more) {
        if (fragmentModel == null) return;
        more.setContentDescription(more.getContext().getString(fragmentModel.getSheetLevel() == CameraFragmentModel.SHEET_HIDDEN
                ? R.string.sheet_handle_open : R.string.sheet_handle_close));
    }

    /** The model's sheet level: the bottom grabber's description (removed with the fragment). */
    @Nullable private androidx.databinding.Observable.OnPropertyChangedCallback moreButtonCallback;

    /** P574: the top grabber - a tap or a short drag down on it opens the top row's sheet. */
    private void attachTopGrabber() {
        if (cameraViews == null) return;
        final View grab = cameraViews.topRowGrabber;
        // the 56 x 12 dp grabber answers to 48dp (its parent's touch delegate: the top row's items keep their taps)
        TouchTargets.ensure(grab, Math.round(48 * grab.getResources().getDisplayMetrics().density));
        grab.setOnClickListener(v -> {
            if (lutTray != null && lutTray.isOpen()) lutTray.setOpen(false);
            openTopRowSheet();
        });
        final float[] downY = {0f};
        grab.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) downY[0] = e.getY();
            else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    && e.getY() - downY[0] > 24 * v.getResources().getDisplayMetrics().density) {
                openTopRowSheet();
                return true;
            }
            return false;
        });
        grab.setBackground(com.scamera.app.ui.camera.views.settingsbar.ShadeStyle.ripple(grab.getContext(), 8));
        cameraViews.topRowGrip.setBackground(com.scamera.app.ui.settings.SettingsStyle.shape(grab.getContext(), 0x59FFFFFF, 0, 2));
    }
    /** P293: the model's sheet level closes the LUT tray (removed with the fragment). */
    @Nullable private androidx.databinding.Observable.OnPropertyChangedCallback sheetTrayCallback;

    /** «Все LUT ›»: the settings at the film LUT row. */
    private void openLutSettings() {
        openSettingsPage("film_lut_screen", com.scamera.app.processing.color.FilmLut.KEY);
    }

    /** A settings page ({@code page}, scrolled to {@code key}) from the camera, as the LUT tray's «Все ›» opens its section's. */
    private void openSettingsPage(String page, String key) {
        levelBeforeSettings = fragmentModel.getSheetLevel();
        watchSessionSettings();
        Intent intent = new Intent(hostActivity, SettingsActivity.class);
        intent.setAction(SettingsActivity.ACTION_OPEN_PAGE);
        intent.putExtra(SettingsActivity.EXTRA_PAGE, page);
        intent.putExtra(SettingsActivity.EXTRA_KEY, key);
        openSettingsScreen(intent);
    }

    /** P214f: «Последние сообщения», the last 20 messages of the card (a long press on it). */
    private void openMessageHistory() {
        android.content.Context c = getContext();
        if (c != null) com.scamera.app.ui.settings.MessageHistorySheet.show(c);
    }

    /**
     * The top bar's format chooser: the card-style list sheet of the format choice (FormatChoice) with each option's icon
     * and name, the current one marked. A pick goes through CameraUIController like the shade's FORMAT tile.
     */
    public void openFormatChooser() {
        openFormatChooser(CameraSheets.TAB_FORMAT);
    }

    /**
     * P574 (the approved concept): the top row's format badge opens one sheet with the tabs «Формат | Размер» ({@code tab} shown
     * first; a long press on the badge and the shade's size tile open «Размер»). A size picked goes through PhotoSize as the old
     * size button and sheet did.
     */
    public void openFormatChooser(int tab) {
        android.content.Context c = getContext();
        if (c == null || uiController == null) return;
        // SIMPLE_UI §2: «Формат файла» - «Фото» and «RAW (DNG)» as two segmented choices (CameraSheets)
        java.util.List<com.scamera.app.settings.FormatChoice> offered =
                com.scamera.app.settings.FormatChoice.offered(android.os.Build.VERSION.SDK_INT);
        CameraSheets.formatAndSize(c, offered, com.scamera.app.settings.FormatChoice.current(), PreferenceKeys.getPhotoFormat(), choice -> {
            if (uiController == null) return;
            uiController.onChanged(new com.scamera.app.ui.camera.model.TopBarSettingsData<>(
                    com.scamera.app.settings.SettingType.FORMAT_CHOICE, choice.ordinal()));
            if (uiView != null) uiView.rebindTopBar();
            if (cameraViews != null) cameraViews.settingsBar.refresh();
        }, new CameraSheets.SizeSource() {
            @Override public int current() { return PhotoSize.current(); }
            @Override public boolean full() { return PhotoSize.fullAvailable(); }
            @Override public boolean max() { return PhotoSize.maxAvailable(); }
            @Override public void pick(int size) {
                String message = PhotoSize.set(CameraFragment.this, size);
                if (message != null) showSnackBar(message);
                refreshTopBar();
            }
            @Override public void refuse(CharSequence why) { showSnackBar(why.toString()); }
            @Override public boolean dead(int size) { return PhotoSize.dead(size); }
        }, tab);
    }

    /** The AWB colour temperature for the white-balance chip in auto. */
    private final AwbKelvin awbKelvin = new AwbKelvin();
    /** Uptime of the last metered values sent to the manual panel (at most 4 per second). */
    private volatile long lastMeteredPost;

    /**
     * The camera's metered values (ISO, shutter, focus distance, AWB kelvin) for the manual chips of parameters in auto.
     * Called on the camera thread for every preview result; posted to the main thread at most every 250 ms.
     */
    private void postMetered(CaptureResult result) {
        final long now = android.os.SystemClock.uptimeMillis();
        if (result == null || now - lastMeteredPost < 250) return;
        lastMeteredPost = now;
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        Integer awbMode = result.get(CaptureResult.CONTROL_AWB_MODE);
        int kelvin = awbMode != null && awbMode == CaptureResult.CONTROL_AWB_MODE_AUTO
                ? awbKelvin.kelvin(result.get(CaptureResult.COLOR_CORRECTION_GAINS)) : 0;
        final View host = finderView;
        if (host == null) return;
        host.post(() -> {
            if (manualConsole != null && cameraViews != null)
                manualConsole.setMeteredValues(iso == null ? 0 : iso, exposure == null ? 0 : exposure,
                        focus == null ? Float.NaN : focus, kelvin);
        });
    }

    /**
     * A short message in the card style (CARD, LINE, MUTED text) above the bottom bar. P214f: through the one message channel
     * (UiMessages -> MessageCard): shown 1.5 s + 50 ms per character (at most 7 s; it was 1.5 s for any text), a repeat
     * merged with a counter, kept in the history.
     */
    public void showCardToast(CharSequence text) {
        com.scamera.app.util.UiMessages.info(text);
    }

    /** A lens or camera switch: the shade closes its slider card. */
    void onLensSwitch() {
        if (cameraViews != null) cameraViews.settingsBar.onLensSwitch();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(ACTIVE_BACKCAM_ID, sActiveBackCamId);
        state.putString(ACTIVE_FRONTCAM_ID, sActiveFrontCamId);
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle saved) {
        super.onViewStateRestored(saved);
        if (ScameraApp.DEBUG) Log.d("FragmentMonitor", "[CameraFragment] view state restored from " + saved);
        if (saved == null) return;
        sActiveFrontCamId = saved.getString(ACTIVE_FRONTCAM_ID);
        sActiveBackCamId = saved.getString(ACTIVE_BACKCAM_ID);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] asked, @NonNull int[] answers) {
        if (code != REQUEST_CAMERA_PERMISSION) {
            super.onRequestPermissionsResult(code, asked, answers);
            return;
        }
        final boolean granted = answers.length == 1 && answers[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) showErrorDialog(R.string.request_permission);
    }

    /** Shutter and self-timer sounds, created on first use. */
    public synchronized CameraSounds sounds() {
        if (sounds == null) sounds = new CameraSounds(hostActivity != null ? hostActivity : requireContext());
        return sounds;
    }

    /**
     * Back in the foreground: the shade and controls refreshed, the sensors and the
     * sounds started off the main thread, then the camera thread, the GL viewfinder and the camera, the touch focus last.
     */
    @Override
    public void onResume() {
        if (lutTray != null) lutTray.bindPill();
        super.onResume();
        showFrozenViewfinder();
        screenFlashShown = true; // P507
        updateScreenFlash();
        cameraViews.settingsBar.refresh();
        swipeGestures.init();
        uiView.refresh(CaptureController.isProcessing); // once, after the swipe's set-up (it ran before and after it)
        // P705: on the sensors' registration thread, in order with the pause's unregister (SensorThread.register)
        com.scamera.app.control.SensorThread.register(() -> {
            ScameraApp.getGyro().register();
            ScameraApp.getGravity().register();
        });
        AsyncTask.execute(() -> {
            // onPause may already have released the sounds if the app was backgrounded before this task ran: load() makes what is missing
            sounds().load();
            fragmentModel.updateGalleryThumb(null);
        });
        fragmentModel.onResume();
        // Back from the settings: the shade comes back at the level it was opened from (owner's answer 11).
        if (levelBeforeSettings > CameraFragmentModel.SHEET_HIDDEN) fragmentModel.setSheetLevel(levelBeforeSettings);
        levelBeforeSettings = -1;
        showHintOnce();
        auxModel.setAuxButtonListener(uiController);
        if (horizonView != null) horizonView.setVisible(PreferenceKeys.isHorizonOn());
        cameraViews.settingsBar.setLive(true);
        final CaptureController camera = captureController;
        camera.startBackgroundThread();
        finderView.onResume();
        settleSessionSettings(false); // P214g: before the new session reads them
        camera.screenResumed();
        setUpTapFocus();
        manualConsole.resumePanel();
    }

    /**
     * SIMPLE_UI §1: the brightness sun beside the focus ring - shown with the ring (hidden while EV is locked), its value the
     * Pro EV's in both directions.
     */
    private void attachExposureSun(TouchFocus touchFocus) {
        if (cameraViews == null) return;
        com.scamera.app.ui.camera.views.ExposureSunView sun = cameraViews.vfLayer.evSun;
        sun.setListener(ev -> { if (manualConsole != null) manualConsole.setEvCompensation(ev); });
        touchFocus.setRingListener(new TouchFocus.RingListener() {
            @Override
            public void shown(float cx, float cy, float radius) {
                if (manualConsole == null || manualConsole.evLocked()) {
                    sun.setVisibility(View.GONE);
                    return;
                }
                float[] range = manualConsole.evRange();
                if (!(range[1] > range[0])) {
                    sun.setVisibility(View.GONE);
                    return;
                }
                sun.setRange(range[0], range[1]);
                sun.setEv(manualConsole.evCompensation());
                View parent = (View) sun.getParent();
                sun.placeBeside(cx, cy, radius, parent.getWidth(), parent.getHeight());
                sun.animate().cancel();
                sun.setAlpha(1f);
                sun.setVisibility(View.VISIBLE);
            }

            @Override
            public void hidden() {
                if (sun.isDragging() || sun.getVisibility() != View.VISIBLE) return;
                sun.animate().alpha(0f).setDuration(150).withEndAction(() -> sun.setVisibility(View.GONE)).start();
            }
        });
    }

    /**
     * P509 / P510 (owner, 2026-11-05: «кнопка LUT должна скрываться, когда открыта шторка»): the LUT button (the viewfinder's bottom
     * centre) gives way to the Pro panel and to the shade at any level above HIDDEN, and comes back when both are gone.
     */
    private void showLutPill(com.scamera.app.circularbarlib.ui.ExpandingManualPanel panel) {
        if (cameraViews == null) return;
        // P518 (owner, 2026-11-05: «Кнопку lut можно не скрывать теперь»): under the viewfinder it covers nothing - always shown
        final boolean shown = true;
        final View pill = cameraViews.topRow.lutButton;
        if (pill.isEnabled() == shown) return;
        pill.setEnabled(shown);
        pill.animate().alpha(shown ? 1f : 0f).setDuration(shown ? 220 : 120).start();
    }

    /**
     * SIMPLE_UI §6: the lock pill and the HUD sit 12dp under the viewfinder's top, or under the top bar where it lies over the
     * viewfinder (a window too short for the 3:4 preview and both bars, e.g. 600 x 960dp), so it never hides them.
     */
    public static void placeViewfinderPills(com.scamera.app.databinding.CameraScreenBinding b) {
        int[] bar = new int[2], vf = new int[2];
        b.topRow.getRoot().getLocationInWindow(bar);
        b.vfLayer.texture.getLocationInWindow(vf);
        int overlap = Math.max(0, bar[1] + b.topRow.getRoot().getHeight() - vf[1]);
        float d = b.getRoot().getResources().getDisplayMetrics().density;
        setTopMargin(b.vfLayer.lockPill, Math.round(12 * d) + overlap);
        // P510 (owner's 31228 screenshot: «Инфо» under the shade): the HUD (shutter, ISO, focus ...) in the viewfinder's top-left
        // corner again, under the top bar where it lies over the viewfinder - it followed the LUT pill (P293), which P509 moved down
        // (the HUD's view may start above the preview: the margin counts from the preview's own top)
        int[] hud = new int[2];
        b.vfLayer.vfOverlay.getLocationInWindow(hud);
        b.vfLayer.vfOverlay.setHudTop(Math.max(0, vf[1] - hud[1]) + Math.round(12 * d) + overlap);
        // P574: the LUT button is in the top row now (P518 placed the pill in the band above the lens row)
    }

    private static void setTopMargin(View v, int margin) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp == null || lp.topMargin == margin) return;
        lp.topMargin = margin;
        v.post(() -> v.setLayoutParams(lp));
    }

    /** SIMPLE_UI §1: the compact «Закреплено» pill (accent, 13dp lock icon) while a long press holds focus and exposure. */
    private void attachLockPill(TouchFocus touchFocus) {
        if (cameraViews == null) return;
        android.widget.TextView pill = cameraViews.vfLayer.lockPill;
        styleLockPill(pill);
        pill.setVisibility(View.GONE);
        touchFocus.setLockListener(on -> pill.setVisibility(on ? View.VISIBLE : View.GONE));
    }

    public static void styleLockPill(android.widget.TextView pill) {
        android.content.Context c = pill.getContext();
        int accent = com.scamera.app.ui.camera.views.settingsbar.ShadeStyle.accent(c);
        int ink = com.scamera.app.ui.camera.views.settingsbar.ShadeStyle.INK;
        pill.setText(com.scamera.app.util.Lang.t(c, "Закреплено", "Locked"));
        pill.setContentDescription(com.scamera.app.util.Lang.t(c, "Фокус и яркость закреплены", "Focus and exposure locked"));
        pill.setTextColor(ink);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(accent);
        bg.setCornerRadius(com.scamera.app.circularbarlib.ui.UiScale.dpF(c, 10));
        pill.setBackground(bg);
        int pad = com.scamera.app.circularbarlib.ui.UiScale.dp(c, 8);
        pill.setPaddingRelative(com.scamera.app.circularbarlib.ui.UiScale.dp(c, 6), 0, pad, 0);
        android.graphics.drawable.Drawable icon = com.scamera.app.ui.Icons.inked(c, R.drawable.ui_ic_lock, ink); // P570: tonal mono
        if (icon != null) {
            int s = com.scamera.app.circularbarlib.ui.UiScale.dp(c, 13);
            icon.setBounds(0, 0, s, s);
        }
        pill.setCompoundDrawablesRelative(icon, null, null, null);
        pill.setCompoundDrawablePadding(com.scamera.app.circularbarlib.ui.UiScale.dp(c, 4));
    }

    private void setUpTapFocus() {
        if (cameraViews != null && captureController != null) {
            View focusCircle = cameraViews.vfLayer.focusRing;
            finderView.post(() -> {
                // The fragment may be destroyed before this runs (hostActivity relaunched at start-up, e.g. a screen layout or
                // language change): its capture controller is gone then.
                if (captureController == null || uiView == null) return;
                tapFocus = new TouchFocus(captureController,focusCircle,finderView);
                captureController.mTouchFocus = tapFocus;
                attachExposureSun(tapFocus);
                attachLockPill(tapFocus);
                if (mSubjectFocus != null) mSubjectFocus.release();
                mSubjectFocus = null;
                // Paused before this ran: the next resume creates it (it owns a worker thread and the frame sink).
                if (!isResumed() || cameraViews == null) return;
                mSubjectFocus = new com.scamera.app.control.subject.SubjectFocus(captureController, finderView,
                        cameraViews.vfLayer.subjectOverlay, tapFocus);
                captureController.mSubjectFocus = mSubjectFocus;
            });
        }
    }

    /**
     * P345 (owner, 2026-10-26, X200 Ultra screen recording: back from the gallery the viewfinder was black ~1.5 s): the camera closes
     * in onPause and reopens in onResume, and until the new session's first frame the viewfinder is cleared. The last frame is kept
     * here (half size, PixelCopy of the composited viewfinder, waited for at most 80 ms) and shown over the viewfinder from onResume
     * until a new camera frame is drawn (MainRenderer.shownTimestamp or, RAW-only sessions, rawDraws moves), or 4 s; older than a
     * minute (real time, FrozenViewfinder) it is not shown.
     */
    private volatile android.graphics.Bitmap frozenVf;
    private volatile long frozenVfAtMs;
    /** P551: one thread for the copies (a new one started per pause before), the bitmap reused while its size holds. */
    private static android.os.Handler freezeHandler;
    private android.graphics.Bitmap freezeBuffer;
    private volatile int freezeGeneration;

    private static synchronized android.os.Handler freezeHandler() {
        if (freezeHandler == null) {
            android.os.HandlerThread t = new android.os.HandlerThread("VfFreeze");
            t.start();
            freezeHandler = new android.os.Handler(t.getLooper());
        }
        return freezeHandler;
    }

    private void freezeViewfinder() {
        frozenVf = null;
        final com.scamera.app.ui.camera.views.viewfinder.GLPreview view = finderView;
        if (view == null || android.os.Build.VERSION.SDK_INT < 24 || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        try {
            final int w = Math.max(1, view.getWidth() / 2), h = Math.max(1, view.getHeight() / 2);
            if (freezeBuffer == null || freezeBuffer.getWidth() != w || freezeBuffer.getHeight() != h)
                freezeBuffer = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            final android.graphics.Bitmap bmp = freezeBuffer;
            final int generation = ++freezeGeneration;
            // P551: not waited for on the main thread (up to 80 ms per pause before): the copy lands when it is done; a resume
            // before it shows no frozen frame
            android.view.PixelCopy.request(view, bmp, result -> {
                if (result == android.view.PixelCopy.SUCCESS && generation == freezeGeneration) {
                    // the real time (deep sleep counted): uptime stops while the screen is off, an hour-old frame looked fresh
                    frozenVfAtMs = android.os.SystemClock.elapsedRealtime();
                    frozenVf = bmp;
                }
            }, freezeHandler());
        } catch (RuntimeException e) {
            Log.d(TAG, "viewfinder freeze failed: " + e);
        }
    }

    private void showFrozenViewfinder() {
        final android.graphics.Bitmap bmp = frozenVf;
        frozenVf = null;
        if (bmp != null && bmp == freezeBuffer) freezeBuffer = null; // shown: the next pause copies into a new one (no tearing)
        if (cameraViews == null) return;
        final android.widget.ImageView freeze = cameraViews.vfLayer.vfFreeze;
        if (freeze == null) return;
        if (bmp == null || FrozenViewfinder.tooOld(frozenVfAtMs, android.os.SystemClock.elapsedRealtime())) {
            freeze.setVisibility(View.GONE);
            freeze.setImageDrawable(null);
            return;
        }
        freeze.animate().cancel();
        freeze.setImageBitmap(bmp);
        freeze.setAlpha(1f);
        freeze.setVisibility(View.VISIBLE);
        final long shownBefore = com.scamera.app.ui.camera.views.viewfinder.MainRenderer.shownTimestamp();
        final long rawBefore = com.scamera.app.ui.camera.views.viewfinder.MainRenderer.rawDraws();
        final long startedMs = android.os.SystemClock.uptimeMillis();
        freeze.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (freeze.getVisibility() != View.VISIBLE) return;
                // an ISP frame latched or (a RAW-only session: no ISP frame ever) a developed RAW frame drawn
                boolean fresh = FrozenViewfinder.fresh(shownBefore,
                        com.scamera.app.ui.camera.views.viewfinder.MainRenderer.shownTimestamp(), rawBefore,
                        com.scamera.app.ui.camera.views.viewfinder.MainRenderer.rawDraws());
                if (fresh || android.os.SystemClock.uptimeMillis() - startedMs > FrozenViewfinder.MAX_SHOWN_MS || !isResumed()) {
                    freeze.animate().alpha(0f).setDuration(fresh ? 120 : 0).withEndAction(() -> {
                        freeze.setVisibility(View.GONE);
                        freeze.setImageDrawable(null);
                    }).start();
                    return;
                }
                freeze.postDelayed(this, 40);
            }
        }, 40);
    }

    @Override
    public void onPause() {
        // P574: the top row's sheet goes with the screen, unsaved (no window left over a paused camera)
        if (topRowSheet != null) topRowSheet.dismissNow();
        screenFlashShown = false; // P507: the system brightness back while the camera is not in front
        updateScreenFlash();
        if (mSubjectFocus != null) {
            if (captureController != null) captureController.mSubjectFocus = null;
            mSubjectFocus.release();
            mSubjectFocus = null;
        }
        // P705: off the main thread (a sensor HAL call each), after any registration asked for before; the gravity's last reading
        // stays for a shot still finishing
        com.scamera.app.control.SensorThread.register(() -> {
            ScameraApp.getGravity().unregister();
            ScameraApp.getGyro().unregister();
        });
        ScameraApp.getSettings().saveID();
        // P214g: the camera closes here, so the session after the settings is a new one
        if (sessionWatch) sessionWatchPaused = true;
        freezeViewfinder();
        // the camera's outputs stop before the GL viewfinder drops their texture; P680: the viewfinder's request stops here, the
        // device closes off the main thread where it may, and the texture is released only after that close (MainRenderer)
        final CaptureController camera = captureController;
        camera.closeCameraOnPause();
        finderView.onPause();
        finderOverlay.clear();
        fragmentModel.onPause();
        uiController.onPause();
        auxModel.setAuxButtonListener(null);
        cameraViews.settingsBar.setLive(false);
        // The sounds are loaded asynchronously in onResume; release() handles what is there.
        sounds().release();
        // The settings sheet comes back HIDDEN, whatever level it was left at.
        fragmentModel.setSheetLevel(CameraFragmentModel.SHEET_HIDDEN);
        manualConsole.pausePanel();
        super.onPause();
    }

    /** P319: the time in which a second Back during a capture leaves anyway (the first one only says so). */
    private static final long BACK_AGAIN_MS = 2000;
    /** P319: when Back last warned that a shot is still being captured (uptime ms, 0: never). */
    private long backWarnedAt;

    /**
     * P319 (user review 2026-10-22, item 8: «Кнопка "назад" все же должна работать как выход из камеры»). Back closes one
     * thing per press (CameraBack): a running self-timer, the catalog, the shade's drag / edit mode, the quick sheet, the LUT
     * tray, the Pro panel. With nothing open it leaves the camera: false lets CameraActivity finish; while a shot is
     * captured, queued or merged the task goes to the background as with Home instead (ProcessingKeepAlive keeps the
     * process, this controller stays for the queued shots). While the frames of a shot still arrive the first Back only says
     * so; a second one within {@link #BACK_AGAIN_MS} leaves and the shot is merged from the frames that arrived (P27).
     */
    @Override
    public boolean onBackPressed() {
        if (cameraViews == null) return false;
        // P574: the top row's sheet first (its own window normally takes Back; this covers a Back that reached the screen)
        if (isTopRowSheetShowing()) {
            closeTopRowSheet();
            return true;
        }
        final View panelView = cameraViews.proPanel;
        final com.scamera.app.circularbarlib.ui.ExpandingManualPanel pro =
                panelView instanceof com.scamera.app.circularbarlib.ui.ExpandingManualPanel
                        ? (com.scamera.app.circularbarlib.ui.ExpandingManualPanel) panelView : null;
        final long now = android.os.SystemClock.uptimeMillis();
        final boolean capturing = captureController != null && captureController.subjectRegionsFrozen();
        final CameraBack.Step step = CameraBack.next(
                uiController != null && uiController.timerRunning(),
                isCatalogOpen(),
                cameraViews.settingsBar.isEditing(),
                fragmentModel.getSheetLevel(),
                lutTray != null && lutTray.isOpen(),
                pro != null && pro.isExpanded(),
                capturing && (backWarnedAt == 0 || now - backWarnedAt > BACK_AGAIN_MS),
                com.scamera.app.processing.ProcessingKeepAlive.busy.getAsBoolean());
        Log.i(TAG, "back: " + step);
        switch (step) {
            case TIMER:
                uiController.cancelTimer();
                return true;
            case CATALOG:
                catalogView.close();
                return true;
            case SHADE_EDIT:
                cameraViews.settingsBar.onBackPressed(); // the drag first, then the edit mode
                return true;
            case SHEET:
                fragmentModel.sheetLevelDown(); // SIMPLE_UI §2: straight to HIDDEN
                return true;
            case TRAY:
                lutTray.setOpen(false);
                return true;
            case PRO:
                pro.setExpanded(false, true); // collapsing closes the ruler too
                return true;
            case WARN_CAPTURING:
                backWarnedAt = now;
                showSnackBar(com.scamera.app.util.Lang.t(
                        "Снимок ещё снимается. Нажмите «Назад» ещё раз, чтобы выйти",
                        "The shot is still being taken. Press Back again to leave"));
                return true;
            case BACKGROUND:
                backWarnedAt = 0;
                requireActivity().moveTaskToBack(true);
                return true;
            default: // LEAVE
                backWarnedAt = 0;
                return false;
        }
    }

    @Override
    public void onDestroy() {
        if (sheetTrayCallback != null && fragmentModel != null) {
            fragmentModel.getCameraFragmentModel().removeOnPropertyChangedCallback(sheetTrayCallback);
            sheetTrayCallback = null;
        }
        if (moreButtonCallback != null && fragmentModel != null) {
            fragmentModel.getCameraFragmentModel().removeOnPropertyChangedCallback(moreButtonCallback);
            moreButtonCallback = null;
        }
        super.onDestroy();
        final CaptureController camera = captureController;
        try {
            camera.stopBackgroundThread();
        } catch (Exception e) {
            Log.w(TAG, "camera thread not stopped: " + e);
        }
        if (cameraViews != null) cameraViews.settingsBar.setLive(false);
        if (hostActivity != null && hostActivity.getWindow() != null) {
            android.view.ViewTreeObserver observer = hostActivity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(sessionFocus); // P214g
        }
        // P214f: the message channel falls back to system toasts until the next camera screen attaches its card
        if (messageCard != null) messageCard.release();
        messageCard = null;
        cameraViews = null;
        final CameraUIView screen = uiView;
        uiView = null; // the controller's late callbacks see the detached UI from here on
        uiController = null;
        screen.release();
        manualConsole.destroyPanel();
        // the app's controller only when it is still this screen's (a newer screen may have set its own)
        if (ScameraApp.getCaptureController() == camera) ScameraApp.setCaptureController(null);
        captureController = null;
        // The preview may still post a texture callback after this point; nobody here handles it any more.
        if (finderView != null) finderView.setSurfaceTextureListener(null);
        processPool.shutdown();
        Log.d(TAG, "camera screen destroyed");
    }

    /** P551: the HUD runnable at most every 50 ms (it was posted to the main thread for every preview frame, 30 - 60 Hz). */
    private long lastScreenLogPostMs;
    private Integer lastScreenLogAf;
    /** The information line, the histogram and the debug table on the viewfinder overlay («Данные AF»). */
    private final ViewfinderHud hud = new ViewfinderHud();

    /** P705: the controls' orientation the last HUD runnable carried (camera thread). */
    private int lastScreenLogOrientation = Integer.MIN_VALUE;

    private void showLiveMetrics(CaptureResult result) {
        final long nowMs = android.os.SystemClock.uptimeMillis();
        final Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
        final int orientation = getCameraFragmentViewModel().getCameraFragmentModel().getOrientation();
        final SurfaceViewOverViewfinder overlay = finderOverlay;
        // P705: with «Данные AF» off the runnable only passes the AF state and the orientation on: not posted to the main thread
        // (20 times a second before) while neither changed
        if (!ViewfinderHud.postDue(nowMs - lastScreenLogPostMs, !java.util.Objects.equals(af, lastScreenLogAf),
                PreferenceKeys.getAfDataValue(), orientation != lastScreenLogOrientation, overlay != null && overlay.drawn)) return;
        lastScreenLogPostMs = nowMs;
        lastScreenLogAf = af;
        lastScreenLogOrientation = orientation;
        if (overlay != null) overlay.post(() -> drawScreenLog(result));
    }

    /** On the main thread: the horizon, the focus ring's state and what «Данные AF» shows (1 line, 2 line + histogram, 3 debug). */
    private void drawScreenLog(CaptureResult result) {
        final int orientation = getCameraFragmentViewModel().getCameraFragmentModel().getOrientation();
        if (horizonView != null) horizonView.updateDisplayRotation(orientation);
        finderOverlay.setOrientation(orientation);
        if (tapFocus != null) tapFocus.setState(result.get(CaptureResult.CONTROL_AF_STATE));
        final int shown = PreferenceKeys.getAfDataValue();
        if (shown == 1 || shown == 2) {
            if (hud.lineDue()) drawHudLine(result, shown == 2);
        } else if (shown == 3) {
            drawDebugTable(result);
        } else if (finderOverlay.drawn) {
            finderOverlay.clear();
        }
    }

    private void drawHudLine(CaptureResult result, boolean histogram) {
        final String[] exposure = ViewfinderHud.exposureAndIso(result, captureController, "ISO ");
        final CameraCharacteristics lens = CaptureController.mCameraCharacteristics;
        final boolean manualFocus = manualConsole != null && manualConsole.manualFocusOn();
        final String focus = ViewfinderHud.focus(result.get(CaptureResult.CONTROL_AF_MODE), result.get(CaptureResult.CONTROL_AF_STATE),
                result.get(CaptureResult.LENS_FOCUS_DISTANCE), manualFocus);
        final String wb = ViewfinderHud.whiteBalance(result, captureController != null ? captureController.mPreviewTemp : null);
        final boolean tripod = ScameraApp.getGyro() != null && ScameraApp.getGyro().getTripod();
        final boolean[] ois = ViewfinderHud.ois(lens, captureController, tripod);
        finderOverlay.showColumn(exposure[0], exposure[1], ViewfinderHud.lens(result, lens), focus, wb, tripod, ois[0], ois[1]);
        finderOverlay.refresh();
        if (histogram) hud.sampleHistogram(finderView, finderOverlay, processPool);
    }

    /** «Данные AF» 3: the result's 3A fields, the exposure, the gyro and the metering regions as a table on the viewfinder. */
    private void drawDebugTable(CaptureResult result) {
        final String[] exposure = ViewfinderHud.exposureAndIso(result, captureController, "");
        final LinkedHashMap<String, String> table = new LinkedHashMap<>();
        table.put("AF_MODE", ViewfinderHud.constantName("CONTROL_AF_MODE_", result.get(CaptureResult.CONTROL_AF_MODE)));
        table.put("AF_TRIGGER", ViewfinderHud.constantName("CONTROL_AF_TRIGGER_", result.get(CaptureResult.CONTROL_AF_TRIGGER)));
        table.put("AF_STATE", ViewfinderHud.constantName("CONTROL_AF_STATE_", result.get(CaptureResult.CONTROL_AF_STATE)));
        table.put("AE_MODE", ViewfinderHud.constantName("CONTROL_AE_MODE_", result.get(CaptureResult.CONTROL_AE_MODE)));
        table.put("FLASH_MODE", ViewfinderHud.constantName("FLASH_MODE_", result.get(CaptureResult.FLASH_MODE)));
        table.put("FOCUS_DISTANCE", String.valueOf(result.get(CaptureResult.LENS_FOCUS_DISTANCE)));
        table.put("EXPOSURE_TIME", exposure[0]);
        table.put("ISO", exposure[1]);
        table.put("Shakiness", String.valueOf(ScameraApp.getGyro().shakeNow()));
        table.put("TripodShakiness", String.valueOf(ScameraApp.getGyro().tripodShakiness));
        table.put("Tripod", String.valueOf(ScameraApp.getGyro().getTripod()));
        table.put("FrameNumber", String.valueOf(result.getFrameNumber()));
        final android.util.Rational[] neutral = captureController.mPreviewTemp;
        table.put("White Point", String.format(Locale.ROOT, "%.3f %.3f %.3f",
                neutral[0].floatValue(), neutral[1].floatValue(), neutral[2].floatValue()));
        finderOverlay.showAfRegion(debugRegion(table, "AF_RECT", result.get(CaptureResult.CONTROL_AF_REGIONS)));
        finderOverlay.showAeRegion(debugRegion(table, "AE_RECT", result.get(CaptureResult.CONTROL_AE_REGIONS)));
        finderOverlay.showDebugTable(Logger.createTextFrom(table));
        finderOverlay.refresh();
    }

    /** The regions into the table (and the first one in view pixels); the first one on screen, null without regions. */
    @Nullable
    private RectF debugRegion(Map<String, String> table, String key, @Nullable MeteringRectangle[] regions) {
        table.put(key, Arrays.deepToString(regions));
        if (regions == null || regions.length == 0) return null;
        final RectF onScreen = ViewfinderHud.screenRect(regions[0], captureController, finderView);
        table.put(key + "(px)", onScreen.toString());
        return onScreen;
    }

    /**
     * An information message on the camera screen's card (P25 card style; any thread). P214f: through the one message
     * channel (UiMessages); a system toast only when the camera screen is gone.
     *
     * @param text The message to show
     */
    public void showToast(final String message) {
        com.scamera.app.util.UiMessages.info(message);
    }

    /** P214f: an error on the card: it stays, in the warning colour, until it is tapped (any thread). */
    public void showError(final String text) {
        com.scamera.app.util.UiMessages.error(text);
    }

    /** P214f: the button notices (FULLRES, DCG, motion mode...) were short snackbars; now the same card as the rest. */
    /** SIMPLE_UI §1: the top bar again from the settings (after a top-bar control changed one). */
    /** P630: the «Oldstyle» mode's controls (null before the bottom chrome is built). */
    @androidx.annotation.Nullable
    OldstyleControls oldstyleControls;

    /** P630: a mode of the bottom switch was chosen: the «Oldstyle» controls in or out. */
    public void onCameraModeChanged() {
        if (oldstyleControls == null || cameraViews == null) return;
        final View panelView = cameraViews.proPanel;
        oldstyleControls.apply(com.scamera.app.settings.OldstyleMode.on(),
                panelView instanceof com.scamera.app.circularbarlib.ui.ExpandingManualPanel
                        ? (com.scamera.app.circularbarlib.ui.ExpandingManualPanel) panelView : null,
                cameraViews.bottomBar.getRoot().findViewById(R.id.pro_button), true);
    }

    public void refreshTopBar() {
        if (uiView != null) uiView.rebindTopBar();
    }

    /** P507: the open lens faces the user (set from its characteristics). */
    private boolean frontLens;
    /** P507: the white screen over the viewfinder while the front lens's torch is on (null until first needed). */
    private View screenFlash;
    /** P507: between onResume and onPause. */
    private boolean screenFlashShown;

    /**
     * P507 (owner, 2026-11-05: «на фронтальной камере при включении фонарика чтобы видоискатель становился белым и яркость становилась
     * максимально возможной для того чтобы подсвечивать лицо»): with a front lens and the torch on (pref_ae_mode 0) a white frame
     * round the live preview (owner: «Кнопки и видоискатель должны быть видны») and the window takes the highest brightness; anything else (torch off, a back lens, the
     * fragment paused) gives the system brightness back and hides it. Called on every lens change, torch change and resume / pause.
     */
    public void updateScreenFlash() {
        final android.app.Activity a = getActivity();
        if (a == null || cameraViews == null) return;
        final boolean on = frontLens && screenFlashShown && PreferenceKeys.getAeMode() == 0;
        final ViewGroup container = cameraViews.cameraStage;
        if (on && screenFlash == null) {
            // a white frame round a window over the live preview (ScreenFlashView): the viewfinder and every control stay visible
            screenFlash = new com.scamera.app.ui.camera.views.ScreenFlashView(a, cameraViews.vfLayer.texture);
            final int at = container.indexOfChild(cameraViews.vfLayer.getRoot());
            container.addView(screenFlash, at + 1, new ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (screenFlash != null) screenFlash.setVisibility(on ? View.VISIBLE : View.GONE);
        final android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        final float want = on ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want;
            a.getWindow().setAttributes(lp);
        }
    }

    public void showSnackBar(final String message) {
        com.scamera.app.util.UiMessages.info(message);
    }

    /** The screen's size, aspect and smallest width in the log (tag DisplayProps). */
    private static void logScreen(DisplayMetrics dm) {
        final int longSide = Math.max(dm.heightPixels, dm.widthPixels), shortSide = Math.min(dm.heightPixels, dm.widthPixels);
        Log.i("DisplayProps", "ScreenResolution = " + longSide + "x" + shortSide);
        Log.i("DisplayProps", "AspectRatio = " + ((float) longSide / shortSide));
        Log.i("DisplayProps", "SmallestWidth = " + (int) (shortSide / (dm.densityDpi / 160f)) + "dp");
    }

    /**
     * The lens list of this phone; the remembered back / front cameras are re-anchored to real ones of their facing (the
     * static "0" / "1" may not exist, and after a process restart no saved state brings them back).
     */
    public void initCameraIDLists(CameraManager manager) {
        lensData = CameraManager2.get(manager, lensSettings).getCameraLensDataMap(); // P680: cached while nothing changes
        if (!lensData.containsKey(sActiveBackCamId)) {
            final String back = firstOfFacing(CameraCharacteristics.LENS_FACING_BACK);
            if (back != null) sActiveBackCamId = back;
        }
        if (!lensData.containsKey(sActiveFrontCamId)) {
            final String front = firstOfFacing(CameraCharacteristics.LENS_FACING_FRONT);
            if (front != null) sActiveFrontCamId = front;
        }
    }

    @Nullable
    private String firstOfFacing(int facing) {
        for (Map.Entry<String, CameraLensData> lens : lensData.entrySet())
            if (lens.getValue().getFacing() == facing) return lens.getKey();
        return null;
    }

    /**
     * Back <-> front. The module last used on the other side is selected again (its zoom, profile and sensor mode): the flip
     * used to keep only the Camera ID, and the module strip then picked the module of that camera nearest the front camera's
     * 1x, so back0 "2x" (Quad mode 29 on the OPPO) came back as back4 "1x". Returns the Camera ID to open.
     */
    public String flip(String savedCameraID) {
        com.scamera.app.settings.ModuleRegistry.rememberActive();
        String target = cycler(savedCameraID);
        CameraLensData lens = lensData.get(target);
        String side = lens != null && lens.getFacing() == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back";
        String slot = com.scamera.app.settings.ModuleRegistry.lastOnSide(side, lensData.keySet());
        if (slot == null) return target;
        com.scamera.app.settings.ModuleRegistry.select(slot);
        com.scamera.app.control.ZoomController.onButton(slot);
        String camera = com.scamera.app.settings.ModuleRegistry.camera(slot);
        Log.d(TAG, "flip to " + side + ": module " + slot + " (camera " + camera + ")");
        return camera;
    }

    /** The camera on the other side of {@code current}, which becomes the remembered camera of its own side. */
    public String cycler(String current) {
        final boolean back = Objects.requireNonNull(lensData.get(current)).getFacing() == CameraCharacteristics.LENS_FACING_BACK;
        if (back) sActiveBackCamId = current;
        else sActiveFrontCamId = current;
        return back ? sActiveFrontCamId : sActiveBackCamId;
    }

    /** A saved photo into the media store: a file by its format's MIME type (P637: the answer goes to an open gallery), else the broadcast. */
    public void triggerMediaScanner(Uri saved) {
        final Activity host = hostActivity;
        if (host == null) return;
        final String path = saved != null && "file".equals(saved.getScheme()) ? saved.getPath() : null;
        // the photo format's MIME type (image/heic, image/webp, ...): older Android versions do not map those extensions
        final String mime = path == null ? null : com.scamera.app.processing.PhotoFormat.mimeForName(path);
        if (mime != null) {
            android.media.MediaScannerConnection.scanFile(host.getApplicationContext(), new String[]{path}, new String[]{mime},
                    com.scamera.app.gallery.ui.CameraHandoff::scanned);
            return;
        }
        host.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).setData(saved));
    }

    public void launchGallery() {
        // P637: the viewer's first frame - the thumbnail shown here (or the shutter frame of a shot still processed) and the last
        // saved file, handed over in the process; the tap's time for the gallery's log
        boolean busy = false;
        try {
            busy = com.scamera.app.processing.ProcessingKeepAlive.busy.getAsBoolean();
        } catch (RuntimeException ignored) {
            // no keep-alive yet
        }
        com.scamera.app.gallery.ui.CameraHandoff.open(
                fragmentModel != null ? fragmentModel.getCameraFragmentModel().getBitmap() : null, busy);
        startActivity(new Intent(hostActivity, GalleryActivity.class).putExtra("CameraFragment", true), null);
        pushTransition();
    }

    /** Shade level when the settings were opened, restored on the way back; -1 otherwise. */
    private int levelBeforeSettings = -1;

    /** The settings screen; the three entry points (top-bar gear, shade header gear, «Все настройки») all come here. */
    public void launchSettings() {
        levelBeforeSettings = fragmentModel.getSheetLevel();
        watchSessionSettings();
        startActivity(new Intent(hostActivity, SettingsActivity.class));
        pushTransition();
    }

    /**
     * P495: the iOS push - the settings or the gallery slide in from the right over the camera, which moves a third to the left
     * and dims (res/anim/motion_push_*); their finish() plays it backwards. Window animations: the system's transition scale
     * applies, nothing runs in the app per frame.
     */
    /** The settings screen opened, the iOS push in. */
    private void openSettingsScreen(Intent open) {
        startActivity(open);
        pushTransition();
    }

    @SuppressWarnings("deprecation") // overridePendingTransition: the activity transitions of every API level this app runs on
    private void pushTransition() {
        if (hostActivity != null) hostActivity.overridePendingTransition(R.anim.motion_push_enter, R.anim.motion_push_exit);
    }

    /**
     * P214g (speed-ux C5): the settings screen was started from here; {@link #sessionWatchPaused}: this screen was paused since
     * (the usual way: the settings cover the camera, onPause closes it).
     */
    private boolean sessionWatch, sessionWatchPaused;

    private void watchSessionSettings() {
        sessionWatch = true;
        sessionWatchPaused = false;
    }

    /** P214g: back to the camera window (multi-window: the settings beside it lose the focus). */
    private final android.view.ViewTreeObserver.OnWindowFocusChangeListener sessionFocus = hasFocus -> {
        if (hasFocus) settleSessionSettings(true);
    };

    /**
     * P214g: back from the settings screen, the settings a camera session reads when it is built (settings/SessionKeys:
     * «Тип сессии камеры», «Оптическая стабилизация», the module's vendor keys, 16:9, live RAW ...) reach the camera at once.
     * Paused meanwhile (the usual way), the camera was closed in onPause and onResume builds a new session, which reads them
     * all again: the changed ones are only logged. Not paused (a settings screen in multi-window beside the camera: both stay
     * resumed), the session built before the change still runs: it restarts when a setting it was built with changed.
     */
    private void settleSessionSettings(boolean focusBack) {
        if (!sessionWatch || captureController == null) return;
        if (focusBack && sessionWatchPaused) return; // onResume settles it
        final boolean paused = sessionWatchPaused;
        sessionWatch = false;
        sessionWatchPaused = false;
        java.util.List<String> changed = captureController.sessionSettingsChanged();
        if (changed.isEmpty()) return;
        if (paused) {
            Log.i("SCAM_CAPTURE", "session settings changed in the settings " + changed + ": the camera opens a new session with them");
        } else if (isResumed() && captureController.sessionRunning()) {
            Log.i("SCAM_CAPTURE", "session settings changed in the settings beside the camera " + changed + ": the camera restarts");
            captureController.restartCamera();
        }
    }

    public <T extends View> T findViewById(@IdRes int viewId) {
        return hostActivity.findViewById(viewId);
    }

    /** A dialog with the message whose OK closes the camera screen. */
    public void showErrorDialog(String message) {
        ErrorDialog.saying(message).show(getChildFragmentManager(), FRAGMENT_DIALOG);
    }

    /** The same with a string resource (its number when the resource is missing). */
    public void showErrorDialog(@StringRes int textRes) {
        String text;
        try {
            text = getString(textRes);
        } catch (Resources.NotFoundException missing) {
            text = String.valueOf(textRes);
        }
        showErrorDialog(text);
    }

    public void invalidateSurfaceView() {
        final SurfaceViewOverViewfinder overlay = finderOverlay;
        if (overlay != null) overlay.invalidate();
    }

    /** The processing notification's channel was registered in this process (it never changes: once is enough). */
    private boolean processingChannelMade;

    /** The ongoing «processing» notification while a shot is processed (low importance, an indeterminate bar). */
    private void postProcessingNote(String job) {
        if (!processingChannelMade) {
            // P655: the channel's name in the system settings (its id stays, so the phone keeps the user's choice for it)
            notifications.createNotificationChannel(new NotificationChannel(PROCESSING_CHANNEL,
                    com.scamera.app.util.Lang.t(hostActivity, "Обработка снимка в камере", "Shot processing in the camera"),
                    NotificationManager.IMPORTANCE_LOW));
            processingChannelMade = true;
        }
        final android.app.Notification processing = new NotificationCompat.Builder(hostActivity, PROCESSING_CHANNEL)
                .setSmallIcon(R.drawable.ic_round_photo_camera_24).setOngoing(true).setProgress(0, 0, true)
                .setContentTitle(hostActivity.getString(R.string.app_name))
                .setContentText(hostActivity.getString(R.string.processing_processname, job))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        notifications.notify(NOTIFICATION_ID, processing);
    }

    private void dropProcessingNote() {
        notifications.cancel(NOTIFICATION_ID);
    }

    /** An error dialog; its OK closes the hostActivity behind it. */
    public static class ErrorDialog extends DialogFragment {
        private static final String KEY_TEXT = "message";

        public static ErrorDialog saying(String message) {
            final Bundle args = new Bundle();
            args.putString(KEY_TEXT, message);
            final ErrorDialog shown = new ErrorDialog();
            shown.setArguments(args);
            return shown;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle state) {
            final Activity host = getActivity();
            final Bundle args = requireArguments();
            return com.scamera.app.ui.settings.DialogStyle.builder(host)
                    .setMessage(args.getString(KEY_TEXT))
                    .setPositiveButton(android.R.string.ok, (shownDialog, which) -> {
                        if (host != null) host.finish();
                    })
                    .create();
        }
    }

    // ---- the capture controller's events --------------------------------------------------------------------------------

    /** A CameraUIView that ignores every call (see FragmentCameraEvents.ui()). */
    private static final CameraUIView DETACHED_UI = new CameraUIView() {
    };

    private class FragmentCameraEvents extends CameraEvents {
        /**
         * This fragment's UI, or a no-op one after onDestroy: the capture controller of a destroyed fragment can still call
         * back (the hostActivity is recreated on a language change while a camera callback or a shot's processing is pending).
         */
        private CameraUIView ui() {
            final CameraUIView view = uiView;
            return view != null ? view : DETACHED_UI;
        }

        /** True once this fragment is destroyed: callbacks that only update its views do nothing. */
        private boolean detached() {
            return uiView == null;
        }

        // -- the processing (ProcessingEvents) --

        @Override
        public void processingStarted(String job) {
            debug("processing " + job + " started");
            final CameraUIView screen = ui();
            screen.processingRunning(true);
            screen.shutterReady(true);
            postProcessingNote(job);
        }

        @Override
        public void processingProgress(Object payload) {
            // P158: a milestone of the pipeline for the ring round the gallery card
            if (payload instanceof Float && !detached()) ui().processingStage((Float) payload);
            // P220: the large-shot hint and the merge worker's measured progress
            else if (payload instanceof com.scamera.app.processing.ProcessingProgress && !detached())
                ui().processingStage((com.scamera.app.processing.ProcessingProgress) payload);
            // P190: a notice of the merge (a FULLRES series it cut)
            else if (payload instanceof String) showSnackBar((String) payload);
        }

        @Override
        public void processingFinished(Object payload) {
            debug("processing finished: " + payload);
            final CameraUIView screen = ui();
            screen.processingRunning(false);
            screen.shutterReady(true);
            screen.lockForBurst(false);
            dropProcessingNote();
        }

        @Override
        public void imageSaved(boolean saved, Path savedPath) {
            if (!saved) {
                error("the shot was not saved");
                showError(com.scamera.app.util.Lang.t("Не удалось сохранить снимок", "The photo could not be saved"));
                return;
            }
            final Uri photo = savedPath == null ? null : Uri.fromFile(savedPath.toFile());
            if (photo != null) {
                // P637: an open gallery shows the file at once (from its path, before MediaStore has its row)
                com.scamera.app.gallery.ui.CameraHandoff.saved(savedPath.toAbsolutePath().toString());
                triggerMediaScanner(photo);
                debug("photo saved: " + savedPath);
            }
            fragmentModel.updateGalleryThumb(photo);
        }

        @Override
        public void processingFailed(Object payload) {
            final CameraUIView screen = ui();
            if (payload instanceof String) showError((String) payload); // P214f: a failed shot stays on the card until it is tapped
            screen.lockForBurst(false);
            processingFinished("processing ended with an error");
        }

        // -- the capture (CaptureEvents) --

        @Override
        public void burstSize(int frames) {
            // P705: the capture ring it sized is gone (hidden since SIMPLE_UI: one ring, round the gallery card)
        }

        /** ZSL shot: the frames are already buffered, the press must feel instant (SCAM): no capture ring in the
         *  viewfinder, controls stay live, the gallery thumbnail shows the viewfinder frame at once. */
        private boolean instantShot;
        /**
         * P234 (speed-ux A4): a ZSL shot whose stills after the press exceed the 12 MP plan (CaptureController.zslStartTag): the
         * capture ring with the frame count (the ring's frames counted taken), controls locked, a haptic tick when the last still
         * is in - the owner held still for 1.2-2.3 s behind an instant-shot UI before.
         */
        private boolean zslSeries;

        @Override
        public void stillStarted(Object event) {
            instantShot = "ScamZslCaptureStarted".equals(event) || "ZSLCaptureStarted!".equals(event);
            zslSeries = com.scamera.app.capture.CaptureController.ZSL_SERIES_STARTED.equals(event);
            sounds().shutter(); // one shutter sound per shot, at the press (P16)
            final View vf = finderView; // P495: the shot's blink over the viewfinder
            if (vf != null && vf.getParent() instanceof View) vf.post(() -> com.scamera.app.ui.Motion.blink((View) vf.getParent()));
            snapshotViewfinderThumb(instantShot); // P637: every shot's shutter frame (the gallery's placeholder)
            if (!instantShot) {
                ui().lockForBurst(true);
            }
            //finderView.post(() -> finderView.setAlpha(0.8f));
        }

        /**
         * Copies the composited viewfinder into a small bitmap: the provisional gallery thumbnail of an instant shot ({@code thumb},
         * 200 px as before) and - P637 - the placeholder of a gallery opened while the shot is still processed (CameraHandoff, 540 px,
         * upright).
         */
        private void snapshotViewfinderThumb(boolean thumb) {
            final com.scamera.app.ui.camera.views.viewfinder.GLPreview view = finderView;
            if (view == null || android.os.Build.VERSION.SDK_INT < 24) return;
            final int vw = view.getWidth(), vh = view.getHeight();
            if (vw <= 0 || vh <= 0) return;
            try {
                final int w = com.scamera.app.gallery.ui.CameraHandoff.FRAME_WIDTH, h = Math.max(1, Math.round((float) w * vh / vw));
                final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                final int turn = fragmentModel.getCameraFragmentModel().getOrientation();
                android.view.PixelCopy.request(view, bmp, result -> {
                    if (result != android.view.PixelCopy.SUCCESS) return;
                    com.scamera.app.gallery.ui.CameraHandoff.shutter(bmp, turn);
                    if (thumb) fragmentModel.setProvisionalThumb(
                            Bitmap.createScaledBitmap(bmp, 200, Math.max(1, Math.round(200f * vh / vw)), true));
                }, new android.os.Handler(android.os.Looper.getMainLooper()));
            } catch (RuntimeException e) {
                debug("viewfinder snapshot failed: " + e);
            }
        }

        @Override
        public void frameStarted(Object event) {
        }

        @Override
        public void burstPrepared(Object event) {
        }

        @Override
        public void frameCaptured(Object event) {
            if (instantShot) return; // one sound at the press
            final TimerFrameCountViewModel.FrameCntTime frame = event instanceof TimerFrameCountViewModel.FrameCntTime
                    ? (TimerFrameCountViewModel.FrameCntTime) event : null;
            // the time left of a long series under the shutter (burst_left)
            if (frame != null) timerModel.setFrameTimeCnt(frame);
        }

        @Override
        public void burstCompleted(Object event) {
            // P234: the end of a long ZSL series is felt: the phone may move now
            if (zslSeries) {
                zslSeries = false;
                final View tick = finderView;
                if (tick != null) tick.post(() -> tick.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                        ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.CLOCK_TICK));
            }
            timerModel.clearFrameTimeCnt();
            final CameraUIView screen = ui();
            screen.lockForBurst(false);
            // Stock-like: the next shot is allowed once the burst is captured; earlier shots go on processing in the queue
            screen.shutterReady(true);
            final View viewfinder = finderView;
            viewfinder.post(() -> viewfinder.setAlpha(1f));
        }

        @Override
        public void previewResult(CaptureResult frame) {
            showLiveMetrics(frame);
            postMetered(frame);
        }

        /**
         * Implementation of abstract methods of {@link CameraEvents}
         */

        @Override
        public void cameraOpening(CameraManager manager) {
            if (detached()) return;
            initCameraIDLists(manager);
            auxModel.initCameraLists(lensData);
        }

        /** P240 (speed-ux C4): «Камера перезапускается…» over the viewfinder while the camera is brought back after an error. */
        @Override
        public void onCameraRecovery(boolean on, String why) {
            final View root = cameraViews == null ? null : cameraViews.vfLayer.getRoot();
            if (root == null) return;
            root.post(() -> {
                if (detached() || !(root instanceof android.widget.FrameLayout)) return;
                final android.widget.FrameLayout frame = (android.widget.FrameLayout) root;
                if (on) {
                    if (recoveryOverlay == null) {
                        final android.widget.TextView t = new android.widget.TextView(frame.getContext());
                        t.setText(R.string.camera_restarting);
                        t.setTextColor(0xFFFFFFFF);
                        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
                        t.setGravity(android.view.Gravity.CENTER);
                        t.setBackgroundColor(0x99000000);
                        t.setClickable(false);
                        recoveryOverlay = t;
                    }
                    if (recoveryOverlay.getParent() == null)
                        frame.addView(recoveryOverlay, new android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                    recoveryOverlay.setVisibility(View.VISIBLE);
                    // never left up: a recovery the controller did not end (the hostActivity paused meanwhile) hides after 30 s
                    recoveryOverlay.removeCallbacks(hideRecovery);
                    recoveryOverlay.postDelayed(hideRecovery, 30_000);
                } else if (recoveryOverlay != null) {
                    recoveryOverlay.removeCallbacks(hideRecovery);
                    recoveryOverlay.setVisibility(View.GONE);
                }
            });
        }

        private android.widget.TextView recoveryOverlay;
        private android.widget.TextView diagBadge;

        /**
         * P241 (speed-ux C6): «DIAG» in the viewfinder's corner while diagnostics dumps are on (they slow every shot); the stored
         * switch goes off by itself after 24 h (DiagnosticsAutoOff), said on the message card.
         */
        private void updateDiagBadge() {
            final String off = com.scamera.app.settings.DiagnosticsAutoOff.check(hostActivity, System.currentTimeMillis());
            if (off != null) com.scamera.app.util.UiMessages.info(off);
            final boolean on = com.scamera.app.settings.DiagnosticsAutoOff.dumpsOn();
            final View root = cameraViews == null ? null : cameraViews.vfLayer.getRoot();
            if (root == null) return;
            root.post(() -> {
                if (detached() || !(root instanceof android.widget.FrameLayout)) return;
                final android.widget.FrameLayout frame = (android.widget.FrameLayout) root;
                if (!on) { if (diagBadge != null) diagBadge.setVisibility(View.GONE); return; }
                if (diagBadge == null) {
                    final android.widget.TextView t = new android.widget.TextView(frame.getContext());
                    t.setText(R.string.diag_badge);
                    t.setTextColor(0xFFFFD54F);
                    t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
                    com.scamera.app.ui.style.Fonts.apply(t, com.scamera.app.ui.style.Fonts.BOLD);
                    final int pad = Math.round(4 * frame.getResources().getDisplayMetrics().density);
                    t.setPadding(2 * pad, pad, 2 * pad, pad);
                    t.setBackgroundColor(0x99000000);
                    diagBadge = t;
                }
                if (diagBadge.getParent() == null) {
                    final android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.TOP | android.view.Gravity.START);
                    final int m = Math.round(12 * frame.getResources().getDisplayMetrics().density);
                    lp.setMargins(m, m, m, m);
                    frame.addView(diagBadge, lp);
                }
                diagBadge.setVisibility(View.VISIBLE);
            });
        }
        private final Runnable hideRecovery = () -> { if (recoveryOverlay != null) recoveryOverlay.setVisibility(View.GONE); };

        @Override
        public void cameraRestarted() {
            if (detached()) return;
            finderOverlay.clear();
            ui().refresh(CaptureController.isProcessing);
            if (mSubjectFocus != null) mSubjectFocus.stopTracking(false);
            tapFocus.resetFocusCircle();
        }

        @Override
        public void lensChanged(CameraCharacteristics characteristics) {
            if (detached()) return;
            finderOverlay.clear();
            auxModel.setActiveId(PreferenceKeys.getCameraID());
            final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            frontLens = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            // P507: a front lens without a flash lights the face with the screen (updateScreenFlash): the torch stays available
            ui().flashAvailable(Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) || frontLens);
            updateScreenFlash();
            awbKelvin.setCharacteristics(characteristics);
            manualConsole.attachCamera(hostActivity, characteristics);
            manualConsole.resumePanel();
            // SIMPLE_UI §1: DCG and the photo sizes are buttons of the top bar now (no overlays over the manual strip)
            TopBar.bindCamera(characteristics);
            if (uiView != null) uiView.rebindTopBar();
            updateDiagBadge(); // P241
        }

        @Override
        public void cameraError(Object event) {
            if (event instanceof Integer) showErrorDialog((int) (Integer) event);
            else if (event instanceof String) showErrorDialog((String) event);
        }

        @Override
        public void cameraFatal(String message) {
            error("fatal camera error: " + message);
            hostActivity.finish();
        }

        @Override
        public void scanMedia(Uri uri) {
            triggerMediaScanner(uri);
        }
    }




}