package com.scamera.app.ui.camera;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import com.scamera.app.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.app.base.BaseActivity;
import com.scamera.app.settings.MigrationManager;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.ui.settings.DialogStyle;
import com.anggrayudi.storage.contract.RequestStorageAccessContract;
import com.anggrayudi.storage.contract.RequestStorageAccessResult;
import com.anggrayudi.storage.file.StorageType;
import com.scamera.app.util.FileManager;
import com.scamera.app.util.SimpleStorageHelper;
import com.scamera.app.util.log.FragmentLifeCycleMonitor;

import java.util.Arrays;
import java.util.List;

import static android.os.Build.VERSION.SDK_INT;


public class CameraActivity extends BaseActivity {
    private static final String TAG = "CameraActivity";
    /** P239 (speed-ux D1): a touch-down or key on the camera screen wakes the resting FULLRES ring (FullResRingPolicy). */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        com.scamera.app.capture.FullResRingPolicy.touched(android.os.SystemClock.elapsedRealtime());
        if (idleScreen != null) idleScreen.touched(); // P705
    }

    /** P705: the display kept on only while the screen is used (IdleScreen); the touches' refresh rate (TouchRefresh, or null). */
    private IdleScreen idleScreen;
    private TouchRefresh touchRefresh;
    private boolean touchRefreshRead;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (touchRefresh != null) touchRefresh.onTouch(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onPause() {
        if (idleScreen != null) idleScreen.pause();
        if (touchRefresh != null) touchRefresh.pause();
        super.onPause();
    }


    private static final String STATE_PERMISSION_PLAN = "permission_plan";
    private static final String STATE_PERMISSION_PENDING = "permission_pending";
    private static final String STATE_AWAITING_SETTINGS = "permission_awaiting_settings";

    /** Startup permissions, asked like WhyCam does: one system request, no dialog of ours in front (P31). */
    private PermissionPlan permissionPlan;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<RequestStorageAccessContract.Options> dcimLauncher;
    /** A runtime request or the DCIM picker is on screen; its result callback continues the flow. */
    private boolean permissionStepPending;
    /** The user was sent to the app settings; onResume checks the permissions again. */
    private boolean awaitingSettings;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Log.d(TAG, "camera activity created" + (saved != null ? " again" : ""));
        // P206: an edited copy re-signed with another key does not open the camera
        if (!com.scamera.app.util.OriginalBuild.check(this)) {
            android.widget.Toast.makeText(this, com.scamera.app.util.Lang.t(
                    "Это изменённая копия SCAMERA. Установите оригинальную сборку.",
                    "This is a modified copy of SCAMERA. Install the original build."), android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        showScreen();

        PreferenceManager.setDefaultValues(this, R.xml.preferences, MigrationManager.readAgain);
        PreferenceKeys.setDefaults(this);
        // P156: a fresh start of the app opens on the back 1x module, not on the module used last
        String home = com.scamera.app.settings.ModuleRegistry.openHomeOnce(saved == null);
        if (home != null) Log.d(TAG, "fresh start: module " + home + " (1x)");
        ScameraApp.getSettings().loadCache();

        permissionPlan = PermissionPlan.restore(SDK_INT,
                saved == null ? null : saved.getIntArray(STATE_PERMISSION_PLAN));
        boolean stepPending = saved != null && saved.getBoolean(STATE_PERMISSION_PENDING);
        if (saved != null && saved.getBoolean(STATE_AWAITING_SETTINGS)) {
            // Recreated while the user was in the app settings: this start is the return from there.
            permissionPlan.restart();
        }

        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::permissionsAnswered);
        dcimLauncher = registerForActivityResult(
                new RequestStorageAccessContract(this, StorageType.EXTERNAL, SimpleStorageHelper.DCIM_DIR), this::dcimAnswered);

        if (ScameraApp.DEBUG) getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentLifeCycleMonitor(), true);

        // After a recreation with a request on screen its result arrives through the launcher callback.
        if (!stepPending) advancePermissions();
        else permissionStepPending = true;
    }

    private void permissionsAnswered(java.util.Map<String, Boolean> answers) {
        permissionStepPending = false;
        Log.d(TAG, "permissions answered: " + answers);
        permissionPlan.onResult(!answers.isEmpty());
        advancePermissions();
    }

    /** The DCIM picker's answer: the root of DCIM granted, the picker cancelled, or another folder (asked again). */
    private void dcimAnswered(Object answer) {
        permissionStepPending = false;
        if (answer instanceof RequestStorageAccessResult.RootPathPermissionGranted) {
            Log.d(TAG, "DCIM access granted");
            SimpleStorageHelper.updateFileManagerPaths(this);
            permissionPlan.onDcimGranted();
        } else if (answer instanceof RequestStorageAccessResult.CanceledByUser) {
            Log.e(TAG, "DCIM picker cancelled");
            permissionPlan.onDcimCancelled();
        } else {
            Log.e(TAG, "DCIM access refused or another folder picked: " + answer);
        }
        advancePermissions();
    }

    /** Takes the next step of the startup permission flow; the camera starts once nothing is missing. */
    private void advancePermissions() {
        String[] missing = permissionPlan.missing(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        boolean hasDcimAccess = !permissionPlan.needsDcimAccess() || SimpleStorageHelper.hasStorageAccess(this);
        PermissionPlan.Step step = permissionPlan.next(missing, this::shouldShowRequestPermissionRationale, hasDcimAccess);
        Log.d(TAG, "Permission step " + step + " missing=" + Arrays.toString(missing));
        switch (step) {
            case REQUEST:
                // One batch of everything missing; ColorOS shows it as one combined system sheet.
                permissionPlan.onRequest();
                permissionStepPending = true;
                permissionLauncher.launch(missing);
                break;
            case SETTINGS:
                showSettingsRedirectDialog(missing);
                break;
            case PICK_DCIM:
                launchDcimPicker();
                break;
            case DCIM_FAILED:
                showDcimFailedDialog();
                break;
            case START:
                startCamera();
                break;
        }
    }

    private void launchDcimPicker() {
        Toast.makeText(this, permissionPlan.dcimPicks() == 0 ? R.string.perm_dcim_hint : R.string.perm_dcim_wrong_folder,
                Toast.LENGTH_LONG).show();
        permissionPlan.onDcimPick();
        try {
            permissionStepPending = true;
            dcimLauncher.launch(new RequestStorageAccessContract.Options(SimpleStorageHelper.createDcimInitialPath(this)));
        } catch (RuntimeException noPicker) {
            permissionStepPending = false;
            Log.e(TAG, "no DCIM folder picker: " + noPicker);
            permissionPlan.onDcimCancelled();
            showDcimFailedDialog();
        }
    }

    private void showSettingsRedirectDialog(String[] missing) {
        List<PermissionPlan.Group> groups = permissionPlan.groups(missing);
        final boolean media = PermissionPlan.mediaOnly(groups);
        final StringBuilder names = new StringBuilder();
        for (PermissionPlan.Group group : groups) names.append(names.length() > 0 ? ", " : "").append(getString(groupName(group)));
        DialogStyle.builder(this).setCancelable(false)
                .setTitle(media ? getString(R.string.perm_rationale_media_title) : getString(R.string.perm_settings_title))
                .setMessage(media ? getString(R.string.perm_rationale_media_settings) : getString(R.string.perm_settings_message, names))
                .setPositiveButton(R.string.perm_open_settings, (d, button) -> openAppSettings())
                .setNegativeButton(R.string.cancel, (d, button) -> finish())
                .show();
    }

    private static int groupName(PermissionPlan.Group group) {
        switch (group) {
            case CAMERA:
                return R.string.perm_group_camera;
            case PHOTOS:
                return R.string.perm_group_photos;
            case FILES:
                return R.string.perm_group_files;
            default:
                return R.string.perm_group_storage;
        }
    }

    private void openAppSettings() {
        awaitingSettings = true;
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException noScreen) {
            awaitingSettings = false;
            Log.e(TAG, "no app settings screen: " + noScreen);
            advancePermissions();
        }
    }

    private void showDcimFailedDialog() {
        DialogStyle.builder(this).setCancelable(false)
                .setTitle(R.string.perm_rationale_dcim_title)
                .setMessage(R.string.perm_dcim_failed)
                .setPositiveButton(R.string.perm_retry, (d, button) -> {
                    permissionPlan.restart();
                    advancePermissions();
                })
                .setNegativeButton(R.string.cancel, (d, button) -> finish())
                .show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putIntArray(STATE_PERMISSION_PLAN, permissionPlan.save());
        state.putBoolean(STATE_PERMISSION_PENDING, permissionStepPending);
        state.putBoolean(STATE_AWAITING_SETTINGS, awaitingSettings);
    }

    /** Every permission is there: the folders, the log and the camera screen (once). */
    private void startCamera() {
        if (SDK_INT >= Build.VERSION_CODES.R) SimpleStorageHelper.updateFileManagerPaths(this);
        // P680: the folders' mkdirs (shared storage, a FUSE round trip each) off the main thread; nothing is saved this early
        new Thread(FileManager::CreateFolders, "SCAMERA-folders").start();
        // P302: photos and RAW files of Download/SCAMERA/DCIM back to DCIM, once (in the background)
        com.scamera.app.util.DcimMoveBack.runOnce(getApplicationContext());
        Log.setLogFolder(getApplicationContext());
        final androidx.fragment.app.FragmentManager fragments = getSupportFragmentManager();
        if (fragments.findFragmentById(R.id.camera_host) == null)
            fragments.beginTransaction().replace(R.id.camera_host, new CameraFragment()).commit();
    }

    @Override
    public void onBackPressed() {
        final Fragment shown = getSupportFragmentManager().findFragmentById(R.id.camera_host);
        if (!(shown instanceof BackPressedListener) || !((BackPressedListener) shown).onBackPressed())
            super.onBackPressed();
    }

    private String appliedThemeSignature;
    private String themeSignature() {
        com.scamera.app.settings.SettingsManager sm=ScameraApp.getSettingsManagerStatic();
        return sm.getString("default_scope",PreferenceKeys.Key.KEY_THEME)+"/"+
                sm.getString("default_scope",PreferenceKeys.Key.KEY_THEME_ACCENT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String signature=themeSignature();
        if(appliedThemeSignature!=null && !appliedThemeSignature.equals(signature)) {
            appliedThemeSignature=signature;
        if (idleScreen != null) idleScreen.resume(PreferenceKeys.scamDevNumber("idle_screen_min", IdleScreen.DEFAULT_MINUTES));
        if (!touchRefreshRead) {
            touchRefreshRead = true;
            touchRefresh = TouchRefresh.of(getWindow(), SDK_INT >= Build.VERSION_CODES.R ? getDisplay() : null,
                    !com.scamera.app.util.RamClass.get(this).low() && PreferenceKeys.scamDevSwitch("ui_touch_refresh", true));
        }
            recreate();
            return;
        }
        appliedThemeSignature=signature;
        if (idleScreen != null) idleScreen.resume(PreferenceKeys.scamDevNumber("idle_screen_min", IdleScreen.DEFAULT_MINUTES));
        if (!touchRefreshRead) {
            touchRefreshRead = true;
            touchRefresh = TouchRefresh.of(getWindow(), SDK_INT >= Build.VERSION_CODES.R ? getDisplay() : null,
                    !com.scamera.app.util.RamClass.get(this).low() && PreferenceKeys.scamDevSwitch("ui_touch_refresh", true));
        }
        // P470: the first launch of this install / system build compiles the GPU programs behind a progress dialog
        if(checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED&&!AllFilesAccess.maybeAsk(this)){
            FirstRunCompile.maybeRun(this);
            // P708: a fresh install's setup wizard (Simple / Advanced and a few choices), over the camera and the compile dialog
            com.scamera.app.ui.settings.SettingsWizard.maybeShowFirstRun(this);
        }
        // P156: the settings may have shown another module's profile for editing: the camera's module again
        com.scamera.app.settings.ModuleRegistry.syncProfile();
        immersive(); // again on every return (no flicker of the bars, portrait as the screen is laid out)
        if (awaitingSettings) {
            // Back from the app settings: check again and go on, no restart of the app needed.
            awaitingSettings = false;
            permissionPlan.restart();
            advancePermissions();
        }
    }


    /** The volume keys take a shot (a press each; nothing while the shutter is busy or not there yet, as the permission flow runs). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent key) {
        final int code = key.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(key);
        if (key.getAction() == KeyEvent.ACTION_DOWN) {
            final View shutter = findViewById(R.id.shutter_key);
            if (shutter != null && shutter.isClickable()) shutter.performClick();
        }
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    /** Portrait, and the content under hidden status and navigation bars (immersive: a swipe from the edge shows them). */
    @SuppressWarnings("deprecation") // setSystemUiVisibility: the flags every API level this app runs on reads the same way
    private void immersive() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    /** Full screen, portrait and awake before the first frame (no flicker of the bars, as the gallery), then the layout. */
    private void showScreen() {
        immersive();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // P705: from the first resume on the flag stays only while the screen is used
        idleScreen = new IdleScreen(getWindow(), () -> com.scamera.app.processing.ProcessingKeepAlive.busy.getAsBoolean());
        setContentView(R.layout.activity_camera);
    }
}
