package com.scamera.app.ui.camera;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.scamera.app.R;
import com.scamera.app.api.CameraMode;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.databinding.CameraScreenBinding;
import com.scamera.app.databinding.CameraShutterRowBinding;
import com.scamera.app.databinding.CameraTopRowBinding;
import com.scamera.app.processing.ProcessingProgress;
import com.scamera.app.settings.MotionMode;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.ui.Icons;
import com.scamera.app.ui.Motion;
import com.scamera.app.ui.camera.views.BottomChrome;
import com.scamera.app.ui.camera.views.ProcessingRing;
import com.scamera.app.ui.camera.views.settingsbar.ShadeStyle;
import com.scamera.app.ui.camera.viewmodel.AuxButtonsViewModel;
import com.scamera.app.util.Utilities;

/**
 * The camera screen's views as the capture side drives them ({@link CameraUIView}), built over the fragment's binding in
 * {@link CameraFragment#onViewCreated}.
 * <p>
 * P25 chrome: the top bar shows the card badges and the settings gear; the shutter row is gallery card | ring shutter |
 * front / back switch. The photo mode is the only camera mode (CameraMode): a number an older build stored for a retired mode is
 * stored as the photo mode's (migrateMode).
 */
public class CameraUIViewImpl implements CameraUIView {
    static final int SHUTTER_WHITE = 0xFFFFFFFF;
    /** P583: the concept's shutter disc round the centre (#3A3A3C). */
    static final int SHUTTER_TONAL = 0xFF3A3A3C;
    private static final float DIMMED = 0.5f;

    private final CameraFragment screen;
    private final ImageButton shutter;
    private final ProcessingRing processingRing;
    /** The ring shutter in the camera accent (pressed, self-timer, busy). */
    private final Drawable shutterRing;
    private CameraTopRowBinding topRow;
    private CameraShutterRowBinding shutterRow;
    private CameraUIEventsListener clicks;

    CameraUIViewImpl(CameraFragment screen) {
        this.screen = screen;
        final CameraScreenBinding views = screen.cameraViews;
        topRow = views.topRow;
        shutterRow = views.bottomBar.shutterRow;
        processingRing = shutterRow.processingRing;
        shutter = shutterRow.shutterKey;
        final Context context = views.getRoot().getContext();
        shutterRing = shutter(context, ShadeStyle.accent(context));
        // P111: the touch marks the moment of a «Движение» shot (the shot itself still fires on release)
        shutter.setOnTouchListener((v, touch) -> {
            if (touch.getActionMasked() == MotionEvent.ACTION_DOWN && screen.captureController != null)
                screen.captureController.notePressDown();
            return false;
        });
        topRow.setTopBarClickListener(v -> clicks.onClick(v));
        shutterRow.setBottomBarClickListener(v -> clicks.onClick(v));
        migrateMode();
        styleControls();
        // SIMPLE_UI §1 / P574: a long press on the format badge (its second line is the size) opens the combined sheet on its
        // «Размер» tab (500 ms, haptic)
        topRow.formatButton.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            TopBar.openSizeSheet(screen);
            return true;
        });
        photoViews();
        rebindTopBar();
    }

    /** The photo mode is the only camera mode (owner's answer 4, P702): any other stored number is stored as the photo mode's. */
    private static void migrateMode() {
        if (PreferenceKeys.getStoredCameraMode() != CameraMode.STORED) PreferenceKeys.setStoredCameraMode(CameraMode.STORED);
    }

    /** SIMPLE_UI: the concept's glyphs in the text colour (the front / back switch), the empty gallery card's icon muted. */
    private void styleControls() {
        Icons.mono(shutterRow.galleryPlaceholder, ShadeStyle.MUTED); // P570: the empty card's tonal mono
        Icons.own(shutterRow.flipButton);
        shutter.setBackground(shutterRing);
        // P495: the controls give under the finger and spring back (the shutter deeper); the gallery card follows its
        // thumbnail's press (the card, the picture and the processing ring as one)
        Motion.press(shutter, .9f);
        shutterRow.galleryCard.setAddStatesFromChildren(true);
        Motion.press(shutterRow.galleryCard);
        Motion.press(shutterRow.flipButton);
        // P574: the bottom row (the gear, the modes, «Про») gives under the finger too
        final View bar = screen.cameraViews.bottomBar.getRoot();
        for (int id : new int[]{R.id.pro_button, R.id.gear_button, R.id.mode_sport, R.id.mode_photo, R.id.mode_oldstyle}) {
            final View control = bar.findViewById(id);
            if (control != null) Motion.press(control);
        }
    }

    /**
     * The shutter as the concept draws it (SIMPLE_UI): ready (activated) white; pressed the centre shrinks; the self-timer
     * counting (hovered) the ring in the accent; busy (not activated) the centre in the accent.
     */
    static Drawable shutter(Context context, int accent) {
        final StateListDrawable byState = new StateListDrawable();
        byState.addState(new int[]{android.R.attr.state_pressed}, disc(context, SHUTTER_WHITE, SHUTTER_WHITE, true));
        byState.addState(new int[]{android.R.attr.state_hovered}, disc(context, accent, SHUTTER_WHITE, false));
        byState.addState(new int[]{android.R.attr.state_activated}, disc(context, SHUTTER_WHITE, SHUTTER_WHITE, false));
        byState.addState(new int[0], disc(context, SHUTTER_WHITE, accent, false));
        return byState;
    }

    /**
     * One shutter state: a tonal glass disc (P495: white is the tiles' tonal fill, a colour that colour at 55 %, no hard line)
     * with a round centre inset by 8 dp of the concept's 86 dp disc (P583), pressed 7 % of the 70 dp centre deeper - all
     * times the screen's scale (BottomChrome.scale).
     */
    private static Drawable disc(Context context, int discColor, int centreColor, boolean pressed) {
        final float scale = BottomChrome.scale(context);
        final GradientDrawable outer = oval(discColor == SHUTTER_WHITE ? SHUTTER_TONAL : (discColor & 0x00FFFFFF) | 0x8C000000);
        final LayerDrawable both = new LayerDrawable(new Drawable[]{outer, oval(centreColor)});
        final int inset = ShadeStyle.dp(context, 8 * scale + (pressed ? 70 * scale * .07f : 0f));
        both.setLayerInset(1, inset, inset, inset, inset);
        return both;
    }

    private static GradientDrawable oval(int color) {
        final GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    @Override
    public void rebindTopBar() {
        if (topRow != null) bindBadges(topRow);
        // P638: «Спорт» switched on by the shade, the settings or a restored backup while «Oldstyle» was the mode: one mode,
        // and the Oldstyle controls leave the screen with it
        try {
            MotionMode.settleWithOldstyle(android.preference.PreferenceManager.getDefaultSharedPreferences(ScameraApp.getAppContext()));
        } catch (RuntimeException noContext) {
            // the modes below show the stored flags as they are
        }
        if (MotionMode.takeSettled()) screen.onCameraModeChanged();
        // P574: the bottom row's Sport | Photo switch follows the setting (a tap, the shade, the settings)
        final CameraScreenBinding views = screen.cameraViews;
        if (views != null) BottomChrome.bindModes(views.bottomBar.getRoot(), true);
        screen.updateScreenFlash(); // P507: the torch from the top bar or the shade
    }

    /** SIMPLE_UI §1: the top bar from the settings (TopBar; P574: format / size, torch, timer, LUT, DCG). */
    public static void bindBadges(CameraTopRowBinding topBar) {
        TopBar.bind(topBar);
    }

    @Override
    public void shutterReady(boolean ready) {
        shutter.post(() -> {
            shutter.setActivated(ready);
            shutter.setClickable(ready);
        });
    }

    /** On a screen up to 16:9 the camera block hangs from the top bar. */
    private void anchorCameraBlock() {
        if (screen.screenAspect > 16f / 9f) return;
        final View block = screen.cameraViews.cameraRoot.findViewById(R.id.camera_stage);
        final ConstraintLayout.LayoutParams anchor = (ConstraintLayout.LayoutParams) block.getLayoutParams();
        anchor.topToTop = -1;
        anchor.topToBottom = R.id.top_row;
    }

    @Override
    public void refresh(boolean processing) {
        screen.cameraViews.invalidateAll();
        photoViews();
        rebindTopBar();
        if (processing) return;
        shutterReady(true);
        processingRunning(false);
        lockForBurst(false);
    }

    @Override
    public void processingRunning(boolean running) {
        processingRing.post(running ? processingRing::start : processingRing::finish);
    }

    @Override
    public void processingStage(float fraction) {
        processingRing.post(() -> processingRing.setStage(fraction));
    }

    @Override
    public void processingStage(ProcessingProgress progress) {
        processingRing.post(() -> {
            if (progress.large) processingRing.setLarge();
            else processingRing.setMeasured(progress.merge);
        });
    }

    @Override
    public void flashAvailable(boolean available) {
        screen.cameraViews.settingsBar.setFlashAvailable(available);
    }

    /**
     * The views are taken now: the posted actions can run after the fragment is destroyed (a rescued shot completes from
     * onPause / onDestroy), when its binding and the bottom row are already null. The shutter stays: it controls the burst.
     */
    @Override
    public void lockForBurst(boolean locked) {
        if (shutterRow != null) setEnabledLater(shutterRow.galleryThumb, !locked, false);
        final CameraScreenBinding views = screen.cameraViews;
        if (views != null) {
            final AuxButtonsViewModel lenses = screen.auxModel;
            setEnabledLater(views.bottomBar.lensStrip, !locked, true);
            if (lenses != null) views.bottomBar.lensStrip.post(() -> lenses.setEnabled(!locked));
            setEnabledLater(views.settingsBar, !locked, true);
            setEnabledLater(views.proPanel, !locked, true);
        }
        // no tap focus or swipe on the viewfinder during a burst
        final View finder = screen.finderView;
        if (finder != null) setEnabledLater(finder, !locked, false);
    }

    /** {@code view} enabled or not on its own thread; {@code dim}: a disabled view at half opacity. */
    private static void setEnabledLater(View view, boolean enabled, boolean dim) {
        view.post(() -> {
            view.setEnabled(enabled);
            if (dim) view.setAlpha(enabled ? 1f : DIMMED);
        });
    }

    @Override
    public void listenTo(CameraUIEventsListener listener) {
        clicks = listener;
    }

    @Override
    public void release() {
        topRow = null;
        shutterRow = null;
    }

    /**
     * The 16:9 frame: on a screen up to 16:9 the anchor stays 3:4, on a taller one it takes the mean of 4:3 and 16:9; the
     * bottom bar goes translucent over the wide backdrop.
     */
    private void wideFrame() {
        final CameraScreenBinding views = screen.cameraViews;
        final float meanTall = ((4f / 3f) + (16f / 9f)) / 2f;
        views.getUimodel().setDummyAspectRatio(screen.screenAspect <= 16f / 9f ? "3:4" : String.valueOf(1.0f / meanTall));
        views.bottomBar.bottomBar.setBackgroundResource(R.color.panel_transparency);
        views.getRoot().setBackgroundResource(R.drawable.camera_wide_backdrop);
    }

    /** The 3:4 frame on the theme's camera background, the bottom bar without its own. */
    private void plainFrame() {
        final CameraScreenBinding views = screen.cameraViews;
        views.getUimodel().setDummyAspectRatio("3:4");
        views.bottomBar.bottomBar.setBackground(null);
        views.getRoot().setBackground(Utilities.resolveDrawable(screen.requireActivity(), R.attr.cameraFragmentBackground));
    }

    /**
     * The photo mode's views: SIMPLE_UI §1, one progress indicator (the thumbnail's ring) - the frame timer over the viewfinder
     * stays hidden (the retired series modes showed it).
     */
    private void photoViews() {
        screen.cameraViews.vfLayer.selfTimerCount.setVisibility(View.GONE);
        shutter.setBackground(shutterRing);
        if (ScameraApp.getSettings().wide169) wideFrame();
        else plainFrame();
        anchorCameraBlock();
    }
}
