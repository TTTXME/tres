package com.scamera.app.gallery.ui;

import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.gallery.model.GalleryItem;

import java.util.List;

/**
 * P622: a view of the library other than the grid - its rows over the same items (headers and photos keep LibraryAdapter's row
 * types, so selection, the fast scroller, the range line and the preloader work unchanged; a timeline's month or year card is a
 * {@link #GROUP} row), its view types (above LibraryAdapter.PHOTO, except a {@link LibraryAdapter#HEADER} it leaves to the grid's
 * day header), spans and holders.
 */
public interface RowStyle {
    /** A row that stands for a run of items (a month or a year card): {@code item} the first, {@code count} how many. */
    int GROUP = 2;

    List<LibraryAdapter.Row> rows(List<GalleryItem> items, long now);

    int viewType(LibraryAdapter.Row row);

    int span(LibraryAdapter.Row row, int columns);

    RecyclerView.ViewHolder create(ViewGroup parent, int viewType, LibraryAdapter adapter);

    void bind(RecyclerView.ViewHolder holder, LibraryAdapter.Row row, LibraryAdapter adapter);

    /** The selection changed: the bound holder shows it (animated), without reloading its picture. */
    void refreshSelection(RecyclerView.ViewHolder holder, LibraryAdapter adapter);
}
