package com.scamera.app.gallery.model;

import android.widget.Checkable;

import java.util.ArrayList;

/**
 * The checked items of a selection, in the order they were picked; each item's own checked flag follows. A selection
 * counts as started from its first pick until {@link #deselectAll}.
 *
 * @param <T> the items
 */
public class SelectionHelper<T extends Checkable> {
    private final ArrayList<T> picked = new ArrayList<>();
    private boolean started;

    public boolean isSelectionStarted() {
        return started;
    }

    public boolean isEmpty() {
        return picked.isEmpty();
    }

    public ArrayList<T> getSelectedItems() {
        return picked;
    }

    /** @return whether the item is selected now */
    public boolean toggleSelection(T item) {
        boolean was = picked.contains(item);
        if (was) deselectItem(item);
        else selectItem(item);
        return !was;
    }

    public void selectItem(T item) {
        item.setChecked(true);
        picked.add(item);
        started = true;
    }

    public void deselectItem(T item) {
        picked.remove(item);
        item.setChecked(false);
    }

    public void deselectAll() {
        for (T item : picked) item.setChecked(false);
        picked.clear();
        started = false;
    }
}
