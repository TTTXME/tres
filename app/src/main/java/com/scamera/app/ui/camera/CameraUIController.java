package com.scamera.app.ui.camera;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.CountDownTimer;
import android.preference.PreferenceManager;
import android.view.View;

import androidx.lifecycle.Observer;

import com.scamera.app.R;
import com.scamera.app.api.CameraManager2;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.capture.FlashMode;
import com.scamera.app.control.CountdownTimer;
import com.scamera.app.processing.parameters.IsoExpoSelector;
import com.scamera.app.settings.FormatChoice;
import com.scamera.app.settings.OldstyleMode;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingType;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.settings.ShadeCatalog;
import com.scamera.app.ui.Motion;
import com.scamera.app.ui.camera.model.TopBarSettingsData;
import com.scamera.app.ui.camera.views.AuxButtonsLayout;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

/**
 * The camera screen's taps as actions: the shutter (with the self-timer), the top row's toggles, the bottom
 * row's modes, the lens buttons and the front / back switch; the shade's camera controls arrive as {@link TopBarSettingsData}.
 */
final class CameraUIController implements CameraUIEventsListener, Observer<TopBarSettingsData<?, ?>>, AuxButtonsLayout.AuxButtonListener {
    private static final String TAG = "CameraUIController";
    private final CameraFragment screen;
    /** The shutter of the last tap: hovered while the self-timer counts (P319), not activated while a shot is busy. */
    private View shutter;
    private CountDownTimer selfTimer;

    CameraUIController(CameraFragment screen) {
        this.screen = screen;
    }

    private CaptureController camera() {
        return screen.captureController;
    }

    @Override
    public void onClick(View v) {
        final int id = v.getId();
        if (id == R.id.shutter_key) shutterTapped(v);
        else if (id == R.id.gear_button) screen.launchSettings();
        else if (id == R.id.mode_sport || id == R.id.mode_photo || id == R.id.mode_oldstyle) modeTapped(v, id);
        else if (id == R.id.torch_button) flashTapped();
        else if (id == R.id.timer_button) timerTapped(v);
        else if (id == R.id.grid_button) gridTapped(v.getContext());
        else if (id == R.id.horizon_button) levelTapped(v.getContext());
        else if (id == R.id.uhdr_button) ultraHdrTapped(v.getContext());
        else if (id == R.id.dcg_badge) {
            // SIMPLE_UI §1: DCG 14 bit, the same switch as «Для опытных → 14 бит (DCG)» (Dcg14Mode)
            screen.showSnackBar(Dcg14Button.toggleMode(screen));
            screen.refreshTopBar();
        } else if (id == R.id.format_badge || id == R.id.format_button) screen.openFormatChooser();
        else if (id == R.id.gallery_thumb) screen.launchGallery();
        else if (id == R.id.flip_button) flipTapped(v);
    }

    /** The shutter: the self-timer starts (a tap while it counts stops it), the shot follows it. */
    private void shutterTapped(View button) {
        shutter = button;
        if (button.isHovered()) stopSelfTimer();
        else startSelfTimer();
    }

    /**
     * P574: the bottom row's Sport | Photo switch, P630 with Oldstyle; a tap on the chosen mode does nothing. P86 / P630: the
     * mode's AE frame rate and Oldstyle's manual values are read when the camera opens - the session starts again.
     */
    private void modeTapped(View button, int id) {
        final OldstyleMode.Mode mode = id == R.id.mode_sport ? OldstyleMode.Mode.SPORT
                : id == R.id.mode_photo ? OldstyleMode.Mode.PHOTO : OldstyleMode.Mode.OLDSTYLE;
        final Context context = button.getContext();
        if (!OldstyleMode.select(mode, PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()))) return;
        screen.refreshTopBar();
        screen.onCameraModeChanged();
        final String name;
        if (mode == OldstyleMode.Mode.SPORT) name = Lang.t(context, "Спорт", "Sport");
        else if (mode == OldstyleMode.Mode.PHOTO) name = Lang.t(context, "Фото", "Photo");
        else name = OldstyleMode.NAME + " " + (OldstyleMode.v2() ? "V2" : "V1");
        screen.showSnackBar(name);
        Log.i(TAG, "camera mode " + mode);
        reopenCamera();
    }

    /**
     * P590: the flash modes (pref_ae_mode 0 torch, 1 off, 2 auto, 3 on); a tap takes the next: off, auto, on, torch, off (a
     * lens without a flash: off and torch).
     */
    private void flashTapped() {
        final int mode = FlashMode.tap();
        PreferenceKeys.setAeMode(mode);
        camera().applyFlashSetting(PreferenceKeys.getAeMode());
        screen.refreshTopBar();
        screen.showSnackBar(FlashMode.label(mode));
        Log.i(TAG, "flash mode " + FlashMode.name(mode));
    }

    /** SIMPLE_UI §1: off, 3 s, 10 s, off. */
    private void timerTapped(View button) {
        final int[] seconds = button.getResources().getIntArray(R.array.self_timer_seconds);
        final int next = (PreferenceKeys.getCountdownTimerIndex() + 1) % seconds.length;
        PreferenceKeys.setCountdownTimerIndex(next);
        screen.refreshTopBar();
        final int s = seconds[next];
        screen.showSnackBar(s > 0 ? Lang.t(button.getContext(), "Таймер: " + s + " с", "Timer: " + s + " s")
                : Lang.t(button.getContext(), "Таймер: выкл.", "Timer: off"));
    }

    /** P574: the top row's grid (the shade's «Сетка»: off and 3x3; a stored 4x4 / Φ goes off first). */
    private void gridTapped(Context context) {
        final boolean on = PreferenceKeys.getGridValue() <= 0;
        PreferenceKeys.setGridValue(on ? 1 : 0);
        screen.onTopRowSettingWritten("pref_show_grid_key");
        screen.showSnackBar(Lang.t(context, on ? "Сетка: вкл." : "Сетка: выкл.", on ? "Grid: on" : "Grid: off"));
    }

    /** P574: the top row's level (the shade's «Горизонт»). */
    private void levelTapped(Context context) {
        final boolean on = !PreferenceKeys.isHorizonOn();
        writeGlobal(ShadeCatalog.HORIZON, on);
        screen.showSnackBar(Lang.t(context, on ? "Горизонт: вкл." : "Горизонт: выкл.", on ? "Level: on" : "Level: off"));
    }

    /** P574: the top row's Ultra HDR (the shade's «UHDR»; a session-time key restarts the camera there). */
    private void ultraHdrTapped(Context context) {
        final boolean on = !PreferenceKeys.isUltraHdrOn();
        writeGlobal("pref_ultrahdr_key", on);
        screen.showSnackBar(Lang.t(context, on ? "Ultra HDR: вкл." : "Ultra HDR: выкл.", on ? "Ultra HDR: on" : "Ultra HDR: off"));
    }

    private void writeGlobal(String key, boolean on) {
        ScameraApp.getSettingsManagerStatic().set(SettingsManager.SCOPE_GLOBAL, key, on);
        screen.onTopRowSettingWritten(key);
    }

    /**
     * Front / back: P495, the glyph turns half a round on a spring, from the half turn it is heading for (a quick second tap
     * ends on a half turn too); the camera starts again on the other side.
     */
    private void flipTapped(View button) {
        screen.onLensSwitch();
        button.animate().cancel();
        final float heading = Math.round(button.getRotation() / 180f) * 180f;
        button.animate().rotation(heading + 180f).setDuration(Motion.ENTER.durationMs).setInterpolator(Motion.ENTER).start();
        openLens(screen.flip(PreferenceKeys.getCameraID()));
    }

    private void startSelfTimer() {
        final View button = shutter;
        if (button == null) return;
        button.setHovered(true);
        final int[] seconds = button.getResources().getIntArray(R.array.self_timer_seconds);
        final int wait = seconds[PreferenceKeys.getCountdownTimerIndex()];
        // before start(): a 0 s timer (no self-timer) finishes inside start() and takes the shot at once
        if (wait > 0) screen.sounds().timerStart();
        selfTimer = new CountdownTimer(screen.findViewById(R.id.self_timer_count), wait * 1000L, 1000, this::selfTimerDone).start();
    }

    private void stopSelfTimer() {
        if (selfTimer != null) selfTimer.cancel();
        screen.sounds().timerStop();
        if (shutter != null) shutter.setHovered(false);
    }

    /** P319: a self-timer counts down (the shutter is hovered from its start to the shot, as a tap reads it). */
    boolean timerRunning() {
        return shutter != null && shutter.isHovered();
    }

    /** P319: Back stops the countdown; no shot is taken. */
    void cancelTimer() {
        stopSelfTimer();
    }

    /**
     * The count is over: the shot, the shutter busy until it is done - unless the controller did not take it (busy, not ready)
     * or SCAM queues further presses during the capture, which leaves the shutter usable.
     */
    private void selfTimerDone() {
        screen.sounds().timerStop(); // cut at the timer's end; the shutter sound follows at the shot
        final View button = shutter;
        button.setHovered(false);
        button.setActivated(false);
        button.setClickable(false);
        final CaptureController camera = camera();
        if (camera.takePicture() && !camera.scamShutterQueues()) return;
        button.setActivated(true);
        button.setClickable(true);
    }

    /**
     * A lens button. A service camera (a vivo teleconverter profile, MONO / NIR) opens too: it is on a button only when the user
     * gave a module its Camera ID - the automatic list leaves them out (owner, 2026-10-28; the X300 Ultra's camera 9 turns the
     * picture for the telephoto extender).
     */
    @Override
    public void onAuxButtonClicked(String id) {
        Log.d(TAG, "lens button " + id);
        final Context context = screen.getContext();
        if (context != null && CameraManager2.isAuxiliarySensor((CameraManager) context.getSystemService(Context.CAMERA_SERVICE), id))
            Log.i(TAG, "camera " + id + " is a service camera (vivo profile / MONO / NIR): opened as the module asks");
        screen.onLensSwitch();
        openLens(id);
    }

    private void openLens(String id) {
        PreferenceKeys.setCameraID(String.valueOf(id));
        reopenCamera();
    }

    @Override
    public void onPause() {
        stopSelfTimer();
    }

    private void reopenCamera() {
        stopSelfTimer();
        camera().restartCamera();
    }

    /** A camera control set in the shade: stored the way the settings store it, applied to the running session where it acts live. */
    @Override
    public void onChanged(TopBarSettingsData<?, ?> change) {
        if (change == null || !(change.getType() instanceof SettingType) || change.getValue() == null) return;
        final Object picked = change.getValue();
        switch ((SettingType) change.getType()) {
            case FLASH:
                PreferenceKeys.setAeMode((Integer) picked); // P590: 0 torch, 1 off, 2 auto, 3 on (FlashMode)
                camera().applyFlashSetting(PreferenceKeys.getAeMode());
                break;
            case GRID:
                PreferenceKeys.setGridValue((Integer) picked);
                screen.invalidateSurfaceView();
                break;
            case FPS_60:
                PreferenceKeys.setFpsMode((Integer) picked);
                camera().applyFpsSetting();
                break;
            case TIMER:
                PreferenceKeys.setCountdownTimerIndex((Integer) picked);
                break;
            case EIS:
                PreferenceKeys.setEisPhoto(picked.equals(1));
                break;
            case RAW:
                PreferenceKeys.setSaveRaw((Integer) picked);
                break;
            case FORMAT_CHOICE: {
                // the save mode and the codec together; the capture and the saving read them as before
                final FormatChoice was = FormatChoice.current();
                FormatChoice.store(FormatChoice.values()[(Integer) picked]);
                Log.i(TAG, "format choice " + was + " -> " + FormatChoice.current());
                break;
            }
            case BRACKETING:
                PreferenceKeys.setBracketingMode((Integer) picked);
                IsoExpoSelector.bracketing = (Integer) picked > 0; // the HDR plan follows the bracketing
                break;
            case AE_METERING_STD:
                PreferenceKeys.setAeMeteringStd((Integer) picked);
                camera().applyMeteringSetting();
                break;
            case HYBRID_OUTPUT:
                PreferenceKeys.setHybridOutputIndex((Integer) picked);
                break;
            case HYBRID_DOWNSAMPLER:
                PreferenceKeys.setHybridDownsamplerIndex((Integer) picked);
                break;
            default:
                break;
        }
        screen.cameraViews.topRow.invalidateAll();
    }
}
