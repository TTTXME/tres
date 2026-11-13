package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.UiTokens;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.settings.PhotoStyle;
import com.scamera.app.ui.settings.PageReset;
import com.scamera.app.ui.settings.SettingsStyle;
import com.scamera.app.util.Lang;

/**
 * SIMPLE_UI §4 «Стиль снимка» (the concept's style page): one card of three radio rows - Обычный (natural colours),
 * Чёрно-белый (monochrome), Плёнка (the film LUT's colours) - over the black-and-white filter and the film LUT (PhotoStyle).
 * The row of the colour page shows the style as its value.
 */
public class PhotoStylePreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener, PageReset.Resettable {
    @Nullable private LinearLayout card;

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
        if (PhotoStyle.MONO_KEY.equals(key) || FilmLut.KEY.equals(key)) fill();
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
        card.setBackground(UiTokens.shape(c, SettingsStyle.ROW, 0, 24));   // P574: a group card of the settings
        card.setClipToOutline(true);
        card.setTag("photo_style");
        host.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fill();
    }

    private void fill() {
        LinearLayout box = card;
        SharedPreferences prefs = getSharedPreferences();
        if (box == null || prefs == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c), now = PhotoStyle.current(prefs);
        box.removeAllViews();
        String[][] rows = {{Lang.t(c, "Обычный", "Normal"), Lang.t(c, "Естественные цвета, как их видит камера. Плёночные цвета и чёрно-белый режим выключены.",
                        "Natural colours as the camera sees them. Film colours and black and white are off.")},
                {Lang.t(c, "Чёрно-белый", "Black and white"), Lang.t(c, "Снимок без цвета. Ниже можно выбрать цветной фильтр, как у чёрно-белой плёнки: он меняет яркость неба, листвы и кожи.",
                        "A photo without colour. Below you can pick a colour filter as on black-and-white film: it changes how bright the sky, leaves and skin come out.")},
                {Lang.t(c, "Плёнка", "Film"), Lang.t(c, "Снимок в цветах плёнки: их задаёт выбранный ниже LUT (таблица цветов) или симуляция плёнки.",
                        "The photo in a film's colours: the LUT (colour table) or the film simulation chosen below sets them.")}};
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(SettingsStyle.HAIRLINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiTokens.dp(c, .5f)));
                lp.setMarginStart(UiTokens.dp(c, 60));
                box.addView(line, lp);
            }
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(UiTokens.dp(c, 52));
            row.setPadding(UiTokens.dp(c, 16), UiTokens.dp(c, 9), UiTokens.dp(c, 16), UiTokens.dp(c, 9));
            row.setBackgroundResource(R.drawable.settings_row);
            FrameLayout tile = new FrameLayout(c);
            ImageView icon = new ImageView(c);
            icon.setImageResource(R.drawable.ui_ic_palette);
            // P574: the tonal tile of the settings rows
            int[] pair = com.scamera.app.ui.settings.SettingsTiles.pair(icon.getDrawable());
            tile.setBackground(UiTokens.shape(c, pair[0], 0, 9));
            com.scamera.app.ui.settings.SettingsTiles.ink(icon, pair[1]);
            tile.addView(icon, new FrameLayout.LayoutParams(UiTokens.dp(c, 22), UiTokens.dp(c, 22), Gravity.CENTER));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(UiTokens.dp(c, 32), UiTokens.dp(c, 32));
            tp.setMarginEnd(UiTokens.dp(c, 12));
            row.addView(tile, tp);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = SettingsStyle.text(c, rows[i][0], 16, SettingsStyle.ROW_TITLE);
            t.setTypeface(com.scamera.app.ui.style.Fonts.of(t.getContext(), 600));
            texts.addView(t);
            // P592: the option's name alone; what it gives is its long press
            final String name = rows[i][0], about = rows[i][1];
            row.setOnLongClickListener(v -> {
                com.scamera.app.ui.settings.SettingHelp.show(v.getContext(), name, about);
                return true;
            });
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(SettingsStyle.radio(c, i == now, accent), new LinearLayout.LayoutParams(UiTokens.dp(c, 24), UiTokens.dp(c, 24)));
            row.setTag("style_option_" + i);
            row.setSelected(i == now);
            row.setContentDescription(rows[i][0]);
            final int style = i;
            row.setOnClickListener(v -> PhotoStyle.apply(prefs, style));
            box.addView(row);
        }
    }
}
