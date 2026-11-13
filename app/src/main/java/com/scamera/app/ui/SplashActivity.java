package com.scamera.app.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.scamera.app.ui.camera.CameraActivity;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {
    /** P582: laid out at the concept's 390dp width with the font scale 1, whatever the phone's display / font size. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.scamera.app.ui.style.UiFixedScale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Ensure splash screen starts in portrait mode
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        startActivity(new Intent(SplashActivity.this, CameraActivity.class));
        finish();
    }
}
