package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/**
 * P490 (owner, 2026-11-03: the «Быстрый / Качество» choice at the top of the main screen as left-to-right segments, in the
 * current settings style): a ListPreference drawn as a settings row with one equal-width chip per entry (the film page's
 * chips: accent when chosen, card with a line otherwise). Key, entries, search and backups stay the ListPreference's; a chip
 * only calls setValue.
 */
public class SegmentedListPreference extends ListPreference {
    public SegmentedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_segments);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        final LinearLayout row = (LinearLayout) holder.findViewById(R.id.choice_segments);
        if (row == null) return;
        final Context c = row.getContext();
        final int accent = SettingsStyle.accent(c);
        final CharSequence[] entries = getEntries(), values = getEntryValues();
        final int checked = findIndexOfValue(getValue());
        row.removeAllViews();
        for (int i = 0; entries != null && values != null && i < entries.length; i++) {
            final boolean sel = i == checked;
            final TextView b = SettingsStyle.text(c, entries[i], 13.5f, sel ? UiTokens.INK : UiTokens.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(UiTokens.dp(c, 38));
            b.setBackground(UiTokens.shape(c, sel ? accent : UiTokens.CARD, sel ? accent : UiTokens.LINE, 999));
            b.setSelected(sel);
            b.setEnabled(isEnabled());
            b.setTag("choice_" + values[i]);
            final String value = values[i].toString();
            b.setOnClickListener(v -> {
                if (value.equals(getValue())) return;
                if (callChangeListener(value)) setValue(value); // setValue -> notifyChanged rebinds the chips
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(UiTokens.dp(c, 6));
            row.addView(b, lp);
        }
    }

    /** The chips are the control: tapping the row does not open the list dialog. */
    @Override
    protected void onClick() {}
}
