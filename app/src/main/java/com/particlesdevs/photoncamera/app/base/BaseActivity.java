package com.particlesdevs.photoncamera.app.base;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

public class BaseActivity extends AppCompatActivity {
    /** P582: laid out at the concept's 390dp width with the font scale 1, whatever the phone's display / font size. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.particlesdevs.photoncamera.ui.style.UiFixedScale.wrap(base));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        PreferenceKeys.setActivityTheme(BaseActivity.this);
        super.onCreate(savedInstanceState);
    }
    public interface BackPressedListener{
        boolean onBackPressed();
    }
}
