package com.scamera.app.ui.settings.custompreferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.ui.settings.DialogStyle;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * A row choosing one square PNG from {@link PngLibrary}'s folder. Tapping lists «None» and the stored files; the neutral
 * button imports another through the activity's picker, a long press on a file offers to remove it. The chosen file
 * name is the stored value (no value for «None») and the row's line.
 */
public class TunablePngPreference extends Preference {
    private static final String TAG = "PngRow";
    /** The row whose dialog started the picker: the activity hands the picked file back to it. */
    private static WeakReference<TunablePngPreference> picking = new WeakReference<>(null);
    private static ActivityResultLauncher<String[]> picker;

    private int[] edges = {};
    private String fallback = "";

    public TunablePngPreference(Context context) {
        super(context);
        setIconSpaceReserved(false);
    }

    public void setAllowedPngSizes(int[] allowed) {
        edges = allowed == null ? new int[0] : allowed;
    }

    public void setDefaultValue(String value) {
        fallback = value == null ? "" : value;
    }

    public static void setImportLauncher(ActivityResultLauncher<String[]> launcher) {
        picker = launcher;
    }

    public static File getPngsDir(Context context) {
        return PngLibrary.folder(context);
    }

    public static List<String> getAvailablePngs(Context context) {
        return PngLibrary.names(context);
    }

    /** Imports the picked file into the row that opened the picker; null on success, else the reason. */
    public static String handleImportResult(Context context, Uri uri) {
        TunablePngPreference row = picking.get();
        if (row == null) {
            Log.w(TAG, "picker result without an open row");
            return Lang.t(context, "нет открытого пункта", "No active preference");
        }
        if (uri == null) return Lang.t(context, "файл не выбран", "No file selected");
        String name = PngLibrary.nameFor(context, uri);
        String refused = PngLibrary.store(context, uri, name, row.edges);
        if (refused == null) row.choose(name);
        return refused;
    }

    /** Shows the row's list again after an import, with the new file in it. */
    public static void refreshActivePreference() {
        TunablePngPreference row = picking.get();
        if (row == null) return;
        row.setSummary(row.state());
        row.openList();
    }

    @Override
    protected void onClick() {
        picking = new WeakReference<>(this);
        openList();
    }

    /** P592: the row's line is its state - the chosen file, never the description (SettingHelp.describe holds that). */
    @Override
    public CharSequence getSummary() {
        return state();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        // Written into the view: setSummary() here would notify the adapter during the RecyclerView's layout pass.
        TextView line = (TextView) holder.findViewById(android.R.id.summary);
        if (line != null) line.setText(state());
    }

    private String chosen() {
        return getPersistedString(fallback);
    }

    private String state() {
        String c = chosen();
        return c == null || c.isEmpty() ? Lang.t(getContext(), "Не выбран", "None selected") : c;
    }

    private void choose(String name) {
        persistString(name);
        setSummary(state());
    }

    private void openList() {
        Context context = getContext();
        if (context == null) return;
        List<String> files = PngLibrary.names(context);
        String[] rows = new String[files.size() + 1];
        rows[0] = Lang.t(context, "Нет", "None");
        for (int i = 0; i < files.size(); i++) rows[i + 1] = files.get(i);
        int checked = files.indexOf(chosen()) + 1; // 0 = «None» when nothing (or a missing file) is chosen

        AlertDialog list = DialogStyle.builder(context)
                .setTitle(getTitle())
                .setSingleChoiceItems(rows, checked, (d, which) -> {
                    choose(which == 0 ? "" : rows[which]);
                    d.dismiss();
                })
                .setNeutralButton(Lang.t(context, "Загрузить PNG", "Import new PNG"), (d, w) -> startImport(context))
                .setNegativeButton(Lang.t(context, "Отмена", "Cancel"), null)
                .create();
        list.setOnShowListener(d -> {
            ListView view = list.getListView();
            if (view == null) return;
            view.setOnItemLongClickListener((parent, item, position, id) -> {
                if (position > 0) confirmRemoval(context, list, rows[position]);
                return true; // «None» cannot be removed
            });
        });
        list.show();
    }

    private static void startImport(Context context) {
        if (picker != null) {
            picker.launch(new String[]{"image/png"});
            return;
        }
        Log.w(TAG, "no picker registered");
        ScameraApp.showToast(Lang.t(context, "Не удалось открыть выбор файла", "Cannot open file picker"));
    }

    private void confirmRemoval(Context context, AlertDialog list, String name) {
        DialogStyle.builder(context)
                .setTitle(Lang.t(context, "Удалить PNG", "Remove PNG"))
                .setMessage(Lang.t(context, "Удалить " + name + "?", "Remove " + name + "?"))
                .setPositiveButton(Lang.t(context, "Удалить", "Remove"), (d, w) -> {
                    PngLibrary.delete(context, name);
                    if (name.equals(chosen())) choose("");
                    list.dismiss();
                    openList();
                })
                .setNegativeButton(Lang.t(context, "Отмена", "Cancel"), null)
                .show();
    }

    /** Only a stored key counts; a stored null reads as the fallback. */
    @Override
    protected String getPersistedString(String fallbackValue) {
        SharedPreferences p = getPreferenceManager().getSharedPreferences();
        String v = p != null && p.contains(getKey()) ? p.getString(getKey(), fallbackValue) : null;
        return v == null ? fallbackValue : v;
    }

    /** «None» removes the key rather than storing an empty name. */
    @Override
    protected boolean persistString(String value) {
        SharedPreferences p = getPreferenceManager().getSharedPreferences();
        if (p == null) return true;
        SharedPreferences.Editor e = p.edit();
        (value == null || value.isEmpty() ? e.remove(getKey()) : e.putString(getKey(), value)).apply();
        return true;
    }
}
