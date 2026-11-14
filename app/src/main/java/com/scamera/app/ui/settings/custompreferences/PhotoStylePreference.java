package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.UiTokens;
import com.scamera.app.settings.PhotoStyle;
import com.scamera.app.ui.settings.PageReset;
import com.scamera.app.ui.settings.SettingsStyle;
import com.scamera.app.ui.settings.StyleCards;

/**
 * SIMPLE_UI §4 «Стиль снимка» (the concept's style page). P718: the tone presets as the wizard shows them - three cards with
 * thumbnails, Насыщенный / Обычный / Нейтральный (StyleCards, PhotoStyle) - and a line on what the chosen one does; a tap stores the
 * preset and puts «Why тон» on V2. Black and white and film have their own rows below. The row of the colour page shows the style
 * as its value.
 */
public class PhotoStylePreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener, PageReset.Resettable {
    @Nullable private LinearLayout card;
    /** The preset the cards show (a store from elsewhere - the shade's tile, a reset - rebuilds them). */
    private int shown = -1;

    public PhotoStylePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public PhotoStylePreference(Context context) {
        this(context, null);
    }

    /** The page reset (audit 2026-10-27: a non-persistent row, it was skipped): «Обычный» again; true when another style was on. */
    @Override
    public boolean resetToDefault() {
        final SharedPreferences prefs = getSharedPreferences();
        if (prefs == null || PhotoStyle.current(prefs) == PhotoStyle.NORMAL) return false;
        PhotoStyle.apply(prefs, PhotoStyle.NORMAL);
        return true;
    }

    @Override
    public void onAttached() {
        super.onAttached();
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDetached() {
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetached();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (PhotoStyle.KEY.equals(key) && PhotoStyle.current(prefs) != shown) fill();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag("photo_style");   // P718: no group card - the preset cards are cards themselves, as in the wizard
        card.setPadding(0, UiTokens.dp(c, 4), 0, UiTokens.dp(c, 4));
        host.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fill();
    }

    private void fill() {
        final LinearLayout box = card;
        final SharedPreferences prefs = getSharedPreferences();
        if (box == null || prefs == null) return;
        final Context c = getContext();
        shown = PhotoStyle.current(prefs);
        box.removeAllViews();
        final TextView about = SettingsStyle.text(c, PhotoStyle.about(c, shown), 13, SettingsStyle.SUMMARY);
        about.setTag("style_about");
        about.setPadding(UiTokens.dp(c, 4), UiTokens.dp(c, 10), UiTokens.dp(c, 4), 0);
        box.addView(StyleCards.row(c, "style_option_", shown, style -> {
            shown = style;
            about.setText(PhotoStyle.about(c, style));
            PhotoStyle.apply(prefs, style);
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(about);
    }
}
