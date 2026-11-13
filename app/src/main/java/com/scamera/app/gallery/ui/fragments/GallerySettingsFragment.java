package com.scamera.app.gallery.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.scamera.app.R;
import com.scamera.app.gallery.files.GalleryFileOperations;
import com.scamera.app.gallery.viewmodel.GalleryViewModel;
import com.scamera.app.settings.PreferenceKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The gallery's settings: which photo folders the library shows. P491: a folder is stored by its name (a merged folder's id
 * changes with its newest photo); a stored folder that no longer exists is dropped and the library reloads.
 */
public class GallerySettingsFragment extends PreferenceFragmentCompat {
    /** The gallery's shared model: told to reload when the folder choice changes. */
    private GalleryViewModel library;

    @Override
    public void onCreatePreferences(Bundle saved, String root) {
        library = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        setPreferencesFromResource(R.xml.gallery_preferences, root);
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle saved) {
        MultiSelectListPreference folders = findPreference(PreferenceKeys.Key.FOLDERS_LIST.mValue);
        if (folders != null) {
            Set<String> existing = new HashSet<>();
            List<String> names = new ArrayList<>();
            for (GalleryFileOperations.ImagesFolder f : GalleryFileOperations.FindAllFoldersWithImages(requireActivity().getContentResolver())) {
                names.add(f.getFolderName());
                existing.add(f.getFolderName());
            }
            String[] entries = names.toArray(new String[0]);
            folders.setEntries(entries);
            folders.setEntryValues(entries);
            folders.setOnPreferenceChangeListener((row, chosen) -> {
                @SuppressWarnings("unchecked") Set<String> picked = (Set<String>) chosen;
                folders.setSummary(keepExisting(folders, picked, existing));
                library.setUpdatePending(true);
                return true;
            });
            folders.setSummary(keepExisting(folders, folders.getValues(), existing));
        }
        return super.onCreateView(inflater, parent, saved);
    }

    /** Stores the chosen folders that exist (a missing one asks for a reload) and returns them, sorted, as the row's line. */
    private String keepExisting(MultiSelectListPreference row, Set<String> chosen, Set<String> existing) {
        Set<String> kept = new HashSet<>();
        for (String name : chosen) {
            if (existing.contains(name)) kept.add(name);
            else library.setUpdatePending(true);
        }
        row.setValues(kept);
        List<String> shown = new ArrayList<>(kept);
        shown.sort(String::compareTo);
        return String.join(", ", shown);
    }
}
