package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.controls.IosSegmented;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/**
 * P490 (owner, 2026-11-03: the «Быстрый / Качество» choice at the top of the main screen as left-to-right segments, in the
 * current settings style): a ListPreference drawn as a settings row with its choice under the title. P574: the choice is the
 * settings' segmented control (IosSegmented, the chosen label in the camera's accent); more than four entries make a list row
 * with its pull-down menu. Key, entries, search and backups stay the ListPreference's; a segment only calls setValue.
 */
public class SegmentedListPreference extends ListPreference {
    /** P574: up to this many entries the choice is a segmented control under the title; more open the list's pull-down menu. */
    public static final int MAX_SEGMENTS = 4;

    public SegmentedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (segments()) setLayoutResource(R.layout.preference_segments);
    }

    /** Whether the choice is drawn as segments (else it is a list row with its menu). */
    public boolean segments() {
        final CharSequence[] entries = getEntries();
        return entries == null || entries.length <= MAX_SEGMENTS;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View found = holder.findViewById(R.id.choice_segments);
        if (!(found instanceof IosSegmented)) return;
        holder.itemView.setClickable(false);
        final IosSegmented row = (IosSegmented) found;
        final Context c = row.getContext();
        final CharSequence[] entries = getEntries(), values = getEntryValues();
        if (entries == null || values == null) return;
        final int n = Math.min(entries.length, values.length);
        // P574: a recycled row takes this preference's segments and selection at once; the same row glides its thumb
        final boolean same = row.getTag(R.id.settings_bound_preference) == this && row.getChildCount() == n;
        row.setTag(R.id.settings_bound_preference, this);
        if (!same) {
            final CharSequence[] labels = new CharSequence[n];
            System.arraycopy(entries, 0, labels, 0, n);
            row.setItems(labels);
        }
        row.setColours(SettingsStyle.accent(c), 0x3D767680, 0xFF636366);
        for (int i = 0; i < row.getChildCount() && i < n; i++) {
            row.getChildAt(i).setTag("choice_" + values[i]);
            row.getChildAt(i).setEnabled(isEnabled());
        }
        row.setSelected(findIndexOfValue(getValue()), same);
        row.setListener((s, i) -> {
            if (i < 0 || i >= n) return;
            final String value = values[i].toString();
            if (value.equals(getValue())) return;
            if (callChangeListener(value)) setValue(value); // setValue -> notifyChanged binds the row again: the thumb stays
            else s.setSelected(findIndexOfValue(getValue()), true);
        });
    }

    /** The segments are the control: tapping the row does not open the list (a long list keeps its menu). */
    @Override
    protected void onClick() {
        if (!segments()) super.onClick();
    }
}
