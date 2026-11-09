package com.particlesdevs.photoncamera.gallery.ui.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.gallery.adapters.DragSelectionItemTouchListener;
import com.particlesdevs.photoncamera.gallery.files.GalleryFileOperations;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.files.MediaFile;
import com.particlesdevs.photoncamera.gallery.helper.Constants;
import com.particlesdevs.photoncamera.gallery.interfaces.OnItemInteractionListener;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.model.GalleryView;
import com.particlesdevs.photoncamera.gallery.model.SelectionHelper;
import com.particlesdevs.photoncamera.gallery.ui.AlbumsPage;
import com.particlesdevs.photoncamera.gallery.ui.FastScroller;
import com.particlesdevs.photoncamera.gallery.ui.GalleryHeader;
import com.particlesdevs.photoncamera.gallery.ui.GalleryMenu;
import com.particlesdevs.photoncamera.gallery.ui.GalleryTabBar;
import com.particlesdevs.photoncamera.gallery.ui.SearchPage;
import com.particlesdevs.photoncamera.gallery.ui.TrashPage;
import com.particlesdevs.photoncamera.gallery.ui.GalleryMotion;
import com.particlesdevs.photoncamera.gallery.ui.GallerySheets;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUndo;
import com.particlesdevs.photoncamera.gallery.ui.LibraryAdapter;
import com.particlesdevs.photoncamera.gallery.ui.SelectionBar;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P59b: the library in the card style (GALLERY_TASK.md §1): header (camera / folders), folder chips with counts, day
 * sections over a 3-column grid with format and Ultra HDR badges, long-press + drag selection, a floating selection bar
 * (× · «3 снимка» · Сравнить · Поделиться · Удалить) and the delete / folders sheets. The data and file logic are the old
 * ones: GalleryViewModel's folders, SelectionHelper, GalleryFileOperations.deleteImageFiles, the share intent, the folders
 * preference, compare navigation by item positions.
 * <p>
 * P498 (owner, 2026-11-04: «сделай из нашей галереи полноценную галерею», «добавь красивые анимации», videos, «только фото / только
 * видео / фото и видео»): a «Вид» sheet (media kind, file type, order, columns), the «Избранное» chip and hearts, a filter chip that
 * resets the filter, pinch to change the columns, a fast scroller with the day, a whole day selected from its header, «Избранное»
 * and select-all in the selection bar, this app's own files deleted with an undo, the right MIME type when sharing, and the photo
 * opening out of its thumbnail (its cell's place goes to the viewer).
 * <p>
 * P502 (owner, 2026-11-05: «Сделай галерею аналогичной тому что на этом видео, но в стиле нашей шторки», the stock vivo gallery):
 * three tabs on the shade's glass bar - «Фото» (every selected folder; the header's search, select and «⋮»: filter, larger / smaller,
 * order, the tab's folders; the date range of the rows on screen and a jump to the start / the end), «Альбомы» (AlbumsPage: the
 * frequent albums, every other folder, the types, «Недавно удаленные»; an album opens on the «Фото» page with a back arrow) and
 * «Поиск» (SearchPage). Deletes go to Android 11's trash (TrashPage puts them back). The folder chips are gone: the albums replace
 * them.
 */
public class ImageLibraryFragment extends Fragment implements LibraryAdapter.Host {
    private static final String PREFS = "gallery_ui";
    /** P498: the tapped cell's window rect for the viewer's zoom (int[4]: left, top, right, bottom). */
    public static final String CELL_KEY = "galleryCell";

    private GalleryViewModel viewModel;
    private NavController navController;
    private final SelectionHelper<GalleryItem> selection = new SelectionHelper<>();
    private Set<GalleryItem> dragBase = new LinkedHashSet<>();
    private LibraryAdapter adapter;
    private RecyclerView grid;
    private GridLayoutManager layout;
    private FrameLayout root;
    private TextView empty;
    private View selectionBar;
    private SelectionBar bar;
    private String activeFolder;
    private List<GalleryItem> folders = new ArrayList<>();
    private Set<String> favourites = new HashSet<>();
    private boolean firstFill = true;
    // P502 (owner, 2026-11-05: the gallery «аналогичной тому что на этом видео, но в стиле нашей шторки»): the tabs and their pages
    private GalleryTabBar tabBar;
    private View photosPage, albumsPage, searchPage;
    private GalleryHeader photosHeader;
    private AlbumsPage albums;
    private SearchPage search;
    private TrashPage trash;
    private TextView range, filterChip;
    private View jump;
    /** The tab shown (kept over the view's re-creation, e.g. the viewer and back). */
    private int tab = GalleryTabBar.PHOTOS;
    /** The album opened from «Альбомы» on the «Фото» page (its title), null for the «Фото» tab's own library. */
    @Nullable private String albumTitle;
    @Nullable private GalleryItem albumFolder;
    private boolean trashOpen, selectMode;
    private List<AlbumsPage.Album> albumList = new ArrayList<>(), frequentAlbums = new ArrayList<>(), typeAlbums = new ArrayList<>();
    private List<GalleryItem> everything = new ArrayList<>();
    private int trashCount = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context c = requireContext();
        navController = NavHostFragment.findNavController(this);
        favourites = GalleryView.favourites(c);
        root = new FrameLayout(c);
        root.setBackgroundColor(GalleryUi.BG);

        // ---- «Фото»: the header (search, select, «⋮»), the date range of the rows shown with the filter chip and the jump, the grid
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        photosPage = column;
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View albumBack = GalleryUi.squareButton(c, R.drawable.ui_ic_back, Lang.t("К альбомам", "Back to albums"), v -> closeAlbum());
        View toSearch = GalleryUi.squareButton(c, R.drawable.ui_ic_search, Lang.t("Поиск", "Search"), v -> showTab(GalleryTabBar.SEARCH));
        View toSelect = GalleryUi.squareButton(c, R.drawable.ui_ic_select_all, Lang.t("Выбрать", "Select"), v -> startSelectMode());
        View photosMore = GalleryUi.squareButton(c, R.drawable.ui_ic_more, Lang.t("Ещё", "More"), null);
        photosMore.setOnClickListener(v -> photosMenu(v));
        photosMore.setTag("gallery_view");
        photosHeader = new GalleryHeader(c, Lang.t("Фото", "Photos"), albumBack, toSearch, toSelect, photosMore);
        column.addView(photosHeader.view);

        LinearLayout rangeRow = new LinearLayout(c);
        rangeRow.setOrientation(LinearLayout.HORIZONTAL);
        rangeRow.setGravity(Gravity.CENTER_VERTICAL);
        rangeRow.setPadding(GalleryUi.dp(c, 18), 0, GalleryUi.dp(c, 16), GalleryUi.dp(c, 6));
        range = GalleryUi.fitText(c, "", 15, GalleryUi.TEXT);
        range.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(range.getContext(), 600)); // P579: the settings' row title weight
        rangeRow.addView(range, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        filterChip = GalleryUi.chip(c, Lang.t("Фильтр  ✕", "Filter  ✕"), -1, true);
        filterChip.setContentDescription(Lang.t("Сбросить фильтр", "Clear the filter"));
        Motion.press(filterChip);
        filterChip.setOnClickListener(v -> {
            GalleryView.clearFilter(c);
            viewModel.applyView();
            refreshFilterChip();
        });
        LinearLayout.LayoutParams fcp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fcp.setMarginEnd(GalleryUi.dp(c, 8));
        rangeRow.addView(filterChip, fcp);
        jump = GalleryUi.squareButton(c, R.drawable.ui_ic_jump, Lang.t("В начало или в конец", "To the start or the end"), v -> jump());
        rangeRow.addView(jump, new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 40)));
        column.addView(rangeRow);

        FrameLayout body = new FrameLayout(c);
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        grid = new RecyclerView(c);
        grid.setClipToPadding(false);
        grid.setPadding(GalleryUi.dp(c, 9), 0, GalleryUi.dp(c, 9), GalleryUi.dp(c, 120));
        adapter = new LibraryAdapter(this);
        int columns = GalleryView.columns(c) * GalleryUi.widthFactor(c); // P502: more columns on a wide screen
        layout = new GridLayoutManager(c, columns);
        layout.setSpanSizeLookup(adapter.spans(columns));
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        grid.setItemViewCacheSize(12);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false); // a cell rebound in place does not blink
        animator.setMoveDuration(260);
        animator.setRemoveDuration(180);
        animator.setAddDuration(220);
        grid.setItemAnimator(animator);
        updateThumbSize(columns);
        body.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(new FastScroller(c, grid, p -> adapter != null ? adapter.dayLabelAt(p) : ""),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = GalleryUi.text(c, Lang.t("В этой папке пока нет снимков", "No photos in this folder yet"), 15, GalleryUi.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(GalleryUi.dp(c, 20), GalleryUi.dp(c, 60), GalleryUi.dp(c, 20), GalleryUi.dp(c, 60));
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                updateRange();
            }
        });

        // ---- «Альбомы»
        LinearLayout albumsColumn = new LinearLayout(c);
        albumsColumn.setOrientation(LinearLayout.VERTICAL);
        View albumsSearch = GalleryUi.squareButton(c, R.drawable.ui_ic_search, Lang.t("Поиск", "Search"), v -> showTab(GalleryTabBar.SEARCH));
        View albumsMore = GalleryUi.squareButton(c, R.drawable.ui_ic_more, Lang.t("Ещё", "More"), null);
        albumsMore.setOnClickListener(v -> albumsMenu(v));
        albumsColumn.addView(new GalleryHeader(c, Lang.t("Альбомы", "Albums"), null, albumsSearch, albumsMore).view);
        albums = new AlbumsPage(c, new AlbumsPage.Host() {
            @Override public void openAlbum(GalleryItem folder, String title) { ImageLibraryFragment.this.openAlbum(folder, title); }

            @Override public void openTrash() { ImageLibraryFragment.this.openTrash(); }

            @Override public void openPhotoFolders() { openFolders(); }
        });
        albumsColumn.addView(albums.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        albumsPage = albumsColumn;
        root.addView(albumsColumn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- «Поиск»
        LinearLayout searchColumn = new LinearLayout(c);
        searchColumn.setOrientation(LinearLayout.VERTICAL);
        searchColumn.addView(new GalleryHeader(c, Lang.t("Поиск", "Search"), null).view);
        search = new SearchPage(c, new SearchPage.Host() {
            @Override public List<GalleryItem> library() { return everything; }

            @Override public Set<String> favourites() { return favourites; }

            @Override public List<AlbumsPage.Album> albums() {
                List<AlbumsPage.Album> all = new ArrayList<>(frequentAlbums);
                all.addAll(albumList);
                return all;
            }

            @Override public void openResults(List<GalleryItem> results, int index, String title) { ImageLibraryFragment.this.openResults(results, index); }

            @Override public void openAlbum(GalleryItem folder, String title) { ImageLibraryFragment.this.openAlbum(folder, title); }
        });
        searchColumn.addView(search.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        searchPage = searchColumn;
        root.addView(searchColumn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- «Недавно удаленные» (over the tabs)
        trash = new TrashPage(c, new TrashPage.Host() {
            @Override public android.app.Activity activity() { return requireActivity(); }

            @Override public void closeTrash() { ImageLibraryFragment.this.closeTrash(); }

            @Override public void trashChanged() { if (viewModel != null) viewModel.setUpdatePending(true); }
        });
        root.addView(trash.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        trash.view.setVisibility(View.GONE);

        bar = new SelectionBar(c, this::clearSelection, this::compareSelected, this::shareSelected, this::deleteSelected,
                this::favouriteSelected, this::selectAll);
        selectionBar = bar.view;
        root.addView(selectionBar, GalleryTabBar.tabParams(c));
        selectionBar.setVisibility(View.GONE);

        tabBar = new GalleryTabBar(c, this::showTab, this::reselectTab);
        root.addView(tabBar.view, GalleryTabBar.tabParams(c));

        grid.addOnItemTouchListener(pinch(c));
        grid.addOnItemTouchListener(new DragSelectionItemTouchListener(c, new OnItemInteractionListener() {
            @Override
            public void onItemClicked(RecyclerView view, RecyclerView.ViewHolder holder, int position) {}

            @Override
            public void onLongItemClicked(RecyclerView view, RecyclerView.ViewHolder holder, int position) {
                int item = adapter.itemAt(position);
                if (item < 0) return;
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                dragBase = new LinkedHashSet<>(selection.getSelectedItems());
                GalleryItem g = adapter.items().get(item);
                if (!selection.getSelectedItems().contains(g)) selection.selectItem(g);
                onSelectionChanged();
            }

            @Override
            public void onMultipleViewHoldersSelected(RecyclerView view, List<RecyclerView.ViewHolder> s) {}

            @Override
            public void onViewHolderHovered(RecyclerView view, RecyclerView.ViewHolder holder) {}

            @Override
            public void onDragRange(RecyclerView view, int from, int to) {
                selectRange(from, to);
            }
        }));

        OnBackPressedCallback back = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (selecting() || selectMode) clearSelection();
                else if (trashOpen) closeTrash();
                else if (albumTitle != null) closeAlbum();
                else if (tab != GalleryTabBar.PHOTOS) showTab(GalleryTabBar.PHOTOS);
                else if (leavesGallery(navController.getPreviousBackStackEntry(),
                        viewModel != null ? viewModel.getCurrentFolderImages().getValue() : null) || !navController.navigateUp())
                    requireActivity().finish();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        applyPages(false);
        if (trashOpen) {
            trash.view.setVisibility(View.VISIBLE);
            tabBar.view.setVisibility(View.GONE);
            trash.reload();
        }
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        viewModel.getCurrentFolderImages().observe(getViewLifecycleOwner(), this::showItems);
        viewModel.getSelectedDisplayFolders().observe(getViewLifecycleOwner(), this::showFolders);
        viewModel.getUpdatePending().observe(getViewLifecycleOwner(), pending -> {
            if (pending) {
                // the chip's folder read again (ALL when it is gone): the grid and the viewer then show the same list (the grid kept
                // the old folder while the viewer got ALL, so a tap opened another photo)
                if (albumTitle != null && albumFolder != null) {
                    // an open album read again: its folder's new list (a delete, a restore)
                    viewModel.fetchAllMedia();
                    buildAlbums();
                    GalleryItem again = findAlbum(albumTitle);
                    if (again != null) {
                        albumFolder = again;
                        viewModel.setCurrentFolderImages(again);
                    }
                } else if (tab == GalleryTabBar.PHOTOS || tab == GalleryTabBar.ALBUMS) {
                    activeFolder = viewModel.refreshKeeping("ALL");
                } else viewModel.fetchAllMedia();
                refreshFilterChip();
                if (search != null) search.refresh();
                loadTrashCount();
                viewModel.setUpdatePending(false);
            }
        });
        refreshFilterChip();
    }

    /**
     * Back from the grid to a viewer with nothing to show (its last photo deleted, or an empty gallery opened from the camera)
     * leaves the gallery: that viewer sends the user straight back to the grid, so back never left it.
     */
    static boolean leavesGallery(@Nullable NavBackStackEntry previous, @Nullable List<GalleryItem> shown) {
        return previous != null && previous.getDestination().getId() == R.id.imageViewerFragment && shown != null && shown.isEmpty();
    }

    private void showItems(List<GalleryItem> items) {
        if (items == null || adapter == null) return;
        selection.deselectAll();
        favourites = GalleryView.favourites(requireContext());
        if (firstFill && !items.isEmpty() && Motion.enabled(grid)) {
            // P498: the first fill comes in cell by cell (a short fade and rise)
            android.view.animation.AnimationSet a = new android.view.animation.AnimationSet(true);
            a.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
            a.addAnimation(new android.view.animation.TranslateAnimation(0, 0, GalleryUi.dp(grid.getContext(), 14), 0));
            a.setDuration(260);
            a.setInterpolator(new android.view.animation.PathInterpolator(.05f, .7f, .1f, 1f)); // Motion.EASE_OUT's curve
            // (not GridLayoutAnimationController: RecyclerView gives its children only the plain animation parameters)
            android.view.animation.LayoutAnimationController lac = new android.view.animation.LayoutAnimationController(a, .04f);
            grid.setLayoutAnimation(lac);
            grid.scheduleLayoutAnimation();
        }
        if (!items.isEmpty()) firstFill = false;
        adapter.setItems(items);
        empty.setText(filtered() ? Lang.t("Нет снимков по этому фильтру", "No photos match this filter")
                : GalleryView.FAVOURITES.equals(activeFolder) ? Lang.t("В избранном пока пусто: сердце в просмотре добавляет снимок",
                "No favourites yet: the heart in the viewer adds a photo") : Lang.t("В этой папке пока нет снимков", "No photos in this folder yet"));
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        onSelectionChanged();
        if (grid != null) grid.post(this::updateRange);
    }

    private boolean filtered() {
        Context c = getContext();
        return c != null && GalleryView.filtering(c);
    }

    private void showFolders(List<GalleryItem> list) {
        folders = list != null ? list : new ArrayList<>();
        // P502: the «Фото» tab is the selected folders together («ALL»); a folder of its own opens from «Альбомы»
        if (activeFolder == null) activeFolder = "ALL";
        buildAlbums();
        refreshFilterChip();
    }

    /** The chip name of a folder: «Все», «Избранное» or the folder's own. */
    static String chipName(String folder) {
        if ("ALL".equals(folder)) return Lang.t("Все", "All");
        if (GalleryView.FAVOURITES.equals(folder)) return "♥ " + Lang.t("Избранное", "Favourites");
        return folder;
    }

    // ---------------------------------------------------------------- P502: tabs, albums, search, trash

    private void showTab(int which) {
        if (which == GalleryTabBar.PHOTOS && albumTitle != null) albumTitle = null;
        if (which == GalleryTabBar.PHOTOS && viewModel != null) {
            // back to the «Фото» tab's own library (an album or a search's results may be the list now)
            GalleryItem all = viewModel.getAllSelectedImageFolder().getValue();
            if (all != null && !"ALL".equals(viewModel.getCurrentFolderName())) viewModel.setCurrentFolderImages(all);
            albumFolder = null;
        }
        if (tab == GalleryTabBar.SEARCH && which != GalleryTabBar.SEARCH && search != null) search.onHidden();
        clearSelection();
        tab = which;
        applyPages(true);
        if (which == GalleryTabBar.SEARCH && search != null) search.onShown();
        if (which == GalleryTabBar.ALBUMS) loadTrashCount();
    }

    /** The page of {@link #tab} (an open album: the «Фото» page under the «Альбомы» tab). */
    private void applyPages(boolean animate) {
        if (photosPage == null) return;
        boolean photos = tab == GalleryTabBar.PHOTOS || albumTitle != null;
        View show = photos ? photosPage : tab == GalleryTabBar.ALBUMS ? albumsPage : searchPage;
        for (View v : new View[]{photosPage, albumsPage, searchPage}) {
            if (v == show) {
                if (v.getVisibility() != View.VISIBLE && animate && Motion.enabled(v)) {
                    v.setAlpha(0f);
                    v.setTranslationY(GalleryUi.dp(v.getContext(), 10));
                    v.setVisibility(View.VISIBLE);
                    v.animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(Motion.SHEET).start();
                } else v.setVisibility(View.VISIBLE);
            } else v.setVisibility(View.GONE);
        }
        tabBar.select(albumTitle != null ? GalleryTabBar.ALBUMS : tab);
        photosHeader.set(albumTitle != null ? albumTitle : Lang.t("Фото", "Photos"), albumTitle != null);
        refreshFilterChip();
    }

    private void reselectTab(int which) {
        if (which == GalleryTabBar.PHOTOS || albumTitle != null) {
            if (grid != null) grid.smoothScrollToPosition(0);
        } else if (which == GalleryTabBar.ALBUMS && albums != null) albums.view.smoothScrollTo(0, 0);
    }

    private void openAlbum(GalleryItem folder, String title) {
        if (viewModel == null) return;
        clearSelection();
        albumTitle = title;
        albumFolder = folder;
        tab = GalleryTabBar.ALBUMS;
        viewModel.setCurrentFolderImages(folder);
        applyPages(true);
        if (grid != null) grid.scrollToPosition(0);
    }

    private void closeAlbum() {
        albumTitle = null;
        albumFolder = null;
        showTab(GalleryTabBar.ALBUMS);
    }

    /** A search's results in the viewer (the viewer reads the view model's list). */
    private void openResults(List<GalleryItem> results, int index) {
        if (viewModel == null || results.isEmpty()) return;
        GalleryItem folder = new GalleryItem(results.get(0).getFile());
        folder.setDisplayName("SEARCH");
        folder.getFiles().addAll(results);
        viewModel.setCurrentFolderImages(folder);
        Bundle b = new Bundle();
        b.putInt(Constants.IMAGE_POSITION_KEY, Math.max(0, Math.min(index, results.size() - 1)));
        navController.navigate(R.id.action_imageLibraryFragment_to_imageViewerFragment, b);
    }

    private void openTrash() {
        if (trash == null) return;
        trashOpen = true;
        trash.reload();
        trash.view.setVisibility(View.VISIBLE);
        if (Motion.enabled(trash.view)) {
            trash.view.setTranslationX(trash.view.getWidth() > 0 ? trash.view.getWidth() : GalleryUi.dp(trash.view.getContext(), 360));
            trash.view.animate().translationX(0).setDuration(320).setInterpolator(Motion.SHEET).start();
        }
        tabBar.shown(false);
    }

    private void closeTrash() {
        if (trash == null) return;
        trashOpen = false;
        if (Motion.enabled(trash.view) && trash.view.getWidth() > 0)
            trash.view.animate().translationX(trash.view.getWidth()).setDuration(260).setInterpolator(Motion.SHEET)
                    .withEndAction(() -> trash.view.setVisibility(View.GONE)).start();
        else trash.view.setVisibility(View.GONE);
        tabBar.shown(true);
        loadTrashCount();
    }

    /** The system's dialog of a trash action came back. */
    public void handleTrashResult(boolean ok) {
        if (trash != null) trash.done();
    }

    private void loadTrashCount() {
        final Context c = getContext();
        if (c == null || !com.particlesdevs.photoncamera.gallery.files.GalleryTrash.supported()) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            int n = com.particlesdevs.photoncamera.gallery.files.GalleryTrash.load(app).size();
            if (root != null) root.post(() -> {
                trashCount = n;
                if (albums != null) albums.bind(frequentAlbums, albumList, typeAlbums, trashCount);
            });
        }, "GalleryTrashCount").start();
    }

    /** «Альбомы» from every folder the last scan found: the frequent ones, the other folders, the types. */
    private void buildAlbums() {
        Context c = getContext();
        if (c == null) return;
        List<GalleryItem> all = new ArrayList<>();
        List<GalleryItem> camera = new ArrayList<>(), shots = new ArrayList<>(), records = new ArrayList<>();
        List<AlbumsPage.Album> others = new ArrayList<>();
        for (GalleryFileOperations.ImagesFolder f : GalleryFileOperations.getAllFolders()) {
            GalleryItem folder = new GalleryItem(f.getTopImage());
            for (ImageFile file : f.getAllImageFiles()) if (!GalleryView.hidden(file)) folder.getFiles().add(new GalleryItem(file));
            if (folder.getFiles().isEmpty()) continue;
            folder.setDisplayName(f.getFolderName());
            all.addAll(folder.getFiles());
            String n = f.getFolderName().toLowerCase(java.util.Locale.ROOT);
            if (n.equals("camera") || n.contains("scamera")) camera.addAll(folder.getFiles());
            else if (n.contains("screenshot")) shots.addAll(folder.getFiles());
            else if (n.contains("screenrecord") || n.contains("screen record") || n.contains("записи экрана")) records.addAll(folder.getFiles());
            else others.add(new AlbumsPage.Album(f.getFolderName(), folder, R.drawable.ui_ic_folder));
        }
        all.sort((a, b) -> Long.compare(b.getFile().getLastModified(), a.getFile().getLastModified()));
        everything = all;
        // P504: the photos not read yet get their text in the background while the gallery is open (the newest first)
        List<com.particlesdevs.photoncamera.gallery.files.MediaFile> files = new ArrayList<>();
        for (GalleryItem i : all) if (i.getFile() != null) files.add(i.getFile());
        com.particlesdevs.photoncamera.gallery.ocr.OcrIndex.start(c, files, () -> { if (search != null) search.ocrChanged(); });
        List<GalleryItem> fav = new ArrayList<>(), video = new ArrayList<>(), raw = new ArrayList<>(), heic = new ArrayList<>(), jpeg = new ArrayList<>();
        Set<String> favKeys = GalleryView.favourites(c);
        for (GalleryItem i : all) {
            if (favKeys.contains(GalleryView.key(i.getFile()))) fav.add(i);
            switch (com.particlesdevs.photoncamera.gallery.ui.GalleryFormat.of(i.getFile().getDisplayName(), false)) {
                case VIDEO: video.add(i); break;
                case RAW: raw.add(i); break;
                case HEIC: heic.add(i); break;
                case JPEG: jpeg.add(i); break;
                default: break;
            }
        }
        List<AlbumsPage.Album> frequent = new ArrayList<>();
        addAlbum(frequent, Lang.t("Все", "All"), all, R.drawable.ui_ic_photos);
        addAlbum(frequent, Lang.t("Камера", "Camera"), sorted(camera), R.drawable.ui_ic_camera);
        addAlbum(frequent, Lang.t("Избранное", "Favourites"), fav, R.drawable.ui_ic_heart);
        addAlbum(frequent, Lang.t("Видео", "Videos"), video, R.drawable.ui_ic_video);
        addAlbum(frequent, "RAW", raw, R.drawable.ui_ic_raw);
        addAlbum(frequent, Lang.t("Скриншоты", "Screenshots"), sorted(shots), R.drawable.ui_ic_screenshot);
        addAlbum(frequent, Lang.t("Записи экрана", "Screen recordings"), sorted(records), R.drawable.ui_ic_video);
        others.sort((a, b) -> Long.compare(b.folder.getFiles().get(0).getFile().getLastModified(), a.folder.getFiles().get(0).getFile().getLastModified()));
        List<AlbumsPage.Album> types = new ArrayList<>();
        addAlbum(types, Lang.t("Видео", "Videos"), video, R.drawable.ui_ic_video);
        addAlbum(types, "RAW", raw, R.drawable.ui_ic_raw);
        addAlbum(types, "HEIC", heic, R.drawable.ui_ic_format);
        addAlbum(types, "JPEG", jpeg, R.drawable.ui_ic_format);
        frequentAlbums = frequent;
        albumList = others;
        typeAlbums = types;
        if (albums != null) albums.bind(frequent, others, types, trashCount);
    }

    private static List<GalleryItem> sorted(List<GalleryItem> items) {
        items.sort((a, b) -> Long.compare(b.getFile().getLastModified(), a.getFile().getLastModified()));
        return items;
    }

    private static void addAlbum(List<AlbumsPage.Album> out, String title, List<GalleryItem> files, int icon) {
        if (files.isEmpty()) return;
        GalleryItem folder = new GalleryItem(files.get(0).getFile());
        folder.setDisplayName(title);
        folder.getFiles().addAll(files);
        out.add(new AlbumsPage.Album(title, folder, icon));
    }

    @Nullable
    private GalleryItem findAlbum(String title) {
        for (AlbumsPage.Album a : frequentAlbums) if (a.title.equals(title)) return a.folder;
        for (AlbumsPage.Album a : albumList) if (a.title.equals(title)) return a.folder;
        for (AlbumsPage.Album a : typeAlbums) if (a.title.equals(title)) return a.folder;
        return null;
    }

    /** «Вчера — сегодня»: the days of the first and the last row on screen. */
    private void updateRange() {
        if (range == null || layout == null || adapter == null) return;
        List<GalleryItem> items = adapter.items();
        if (items.isEmpty()) {
            range.setText("");
            return;
        }
        int first = Math.max(0, layout.findFirstVisibleItemPosition()), last = Math.max(first, layout.findLastVisibleItemPosition());
        int a = firstItemFrom(first, 1), b = firstItemFrom(last, -1);
        if (a < 0) a = 0;
        if (b < 0) b = items.size() - 1;
        long now = System.currentTimeMillis();
        long ta = items.get(a).getFile().getLastModified(), tb = items.get(b).getFile().getLastModified();
        String la = GalleryUi.dayLabel(Math.max(ta, tb), now), lb = GalleryUi.dayLabel(Math.min(ta, tb), now);
        range.setText(la.equals(lb) ? la : lb + " — " + la);
        if (jump != null) jump.setRotation(layout.findFirstVisibleItemPosition() <= 0 ? 0 : 180);
    }

    /** The item index at adapter position {@code p}, else the nearest item stepping by {@code dir}. */
    private int firstItemFrom(int p, int dir) {
        for (int i = p; i >= 0 && i < adapter.getItemCount(); i += dir) {
            int item = adapter.itemAt(i);
            if (item >= 0) return item;
        }
        return -1;
    }

    private void jump() {
        if (grid == null || adapter == null || adapter.getItemCount() == 0) return;
        boolean atTop = layout.findFirstVisibleItemPosition() <= 0;
        grid.scrollToPosition(atTop ? adapter.getItemCount() - 1 : 0);
        grid.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        grid.post(this::updateRange);
    }

    private void refreshFilterChip() {
        Context c = getContext();
        if (c == null || filterChip == null) return;
        filterChip.setVisibility(GalleryView.filtering(c) ? View.VISIBLE : View.GONE);
    }

    private void startSelectMode() {
        selectMode = true;
        onSelectionChanged();
    }

    /** The «Фото» page's «⋮»: filter, bigger / smaller cells, order, the tab's folders, the camera. */
    private void photosMenu(View anchor) {
        Context c = requireContext();
        List<GalleryMenu.Item> items = new ArrayList<>();
        items.add(new GalleryMenu.Item(R.drawable.ui_ic_filter, Lang.t("Фильтр", "Filter"), true, this::openFilter));
        items.add(new GalleryMenu.Item(R.drawable.ui_ic_zoom_in, Lang.t("Крупнее", "Larger"),
                GalleryView.columns(c) > GalleryView.MIN_COLUMNS, () -> setColumns(GalleryView.columns(c) - 1)));
        items.add(new GalleryMenu.Item(R.drawable.ui_ic_zoom_out, Lang.t("Мельче", "Smaller"),
                GalleryView.columns(c) < GalleryView.MAX_COLUMNS, () -> setColumns(GalleryView.columns(c) + 1)));
        boolean oldest = GalleryView.oldestFirst(c);
        items.add(new GalleryMenu.Item(R.drawable.ui_ic_sort, oldest ? Lang.t("Сначала новые", "Newest first") : Lang.t("Сначала старые", "Oldest first"),
                true, () -> {
                    GalleryView.setOldestFirst(c, !oldest);
                    viewModel.applyView();
                }));
        if (albumTitle == null)
            items.add(new GalleryMenu.Item(R.drawable.ui_ic_folders, Lang.t("Папки во вкладке «Фото»", "Folders in «Photos»"), true, this::openFolders));
        items.add(new GalleryMenu.Item(R.drawable.ui_ic_camera, Lang.t("К камере", "Back to the camera"), true, () -> requireActivity().finish()));
        GalleryMenu.show(anchor, items);
    }

    private void albumsMenu(View anchor) {
        List<GalleryMenu.Item> items = new ArrayList<>();
        items.add(new GalleryMenu.Item(R.drawable.ui_ic_folders, Lang.t("Папки во вкладке «Фото»", "Folders in «Photos»"), true, this::openFolders));
        if (com.particlesdevs.photoncamera.gallery.files.GalleryTrash.supported())
            items.add(new GalleryMenu.Item(R.drawable.ui_ic_trash, Lang.t("Недавно удаленные", "Recently deleted"), true, this::openTrash));
        items.add(new GalleryMenu.Item(R.drawable.ui_ic_camera, Lang.t("К камере", "Back to the camera"), true, () -> requireActivity().finish()));
        GalleryMenu.show(anchor, items);
    }

    private void openFilter() {
        Context c = requireContext();
        Set<Integer> years = new java.util.TreeSet<>((a, b) -> b - a);
        List<GalleryItem> base = albumFolder != null ? albumFolder.getFiles() : everything;
        for (GalleryItem i : base) if (i.getFile() != null && i.getFile().getLastModified() > 0) years.add(GalleryView.yearOf(i.getFile().getLastModified()));
        GallerySheets.showFilter(c, years, () -> {
            clearSelection();
            viewModel.applyView();
            refreshFilterChip();
        });
    }

    // ---------------------------------------------------------------- P498: columns, view sheet

    /** Pinch on the grid: spread for bigger thumbnails (fewer columns), pinch for more columns. */
    private RecyclerView.OnItemTouchListener pinch(Context c) {
        final ScaleGestureDetector detector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            float acc = 1f;

            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                acc = 1f;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector d) {
                acc *= d.getScaleFactor();
                if (acc > 1.25f) {
                    setColumns(GalleryView.columns(c) - 1);
                    acc = 1f;
                } else if (acc < .8f) {
                    setColumns(GalleryView.columns(c) + 1);
                    acc = 1f;
                }
                return true;
            }
        });
        return new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                detector.onTouchEvent(e);
                return e.getPointerCount() > 1 || detector.isInProgress();
            }

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                detector.onTouchEvent(e);
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {}
        };
    }

    /** {@code n}: the portrait column setting; the grid shows it times the screen's width factor (P502). */
    private void setColumns(int n) {
        if (layout == null || grid == null || adapter == null) return;
        n = GalleryView.clampColumns(n);
        GalleryView.setColumns(requireContext(), n);
        n *= GalleryUi.widthFactor(requireContext());
        if (n == layout.getSpanCount()) return;
        if (Motion.enabled(grid)) {
            android.transition.ChangeBounds move = new android.transition.ChangeBounds();
            move.setDuration(300);
            move.setInterpolator(Motion.SHEET);
            android.transition.TransitionManager.beginDelayedTransition(grid, move);
        }
        layout.setSpanCount(n);
        layout.setSpanSizeLookup(adapter.spans(n));
        updateThumbSize(n);
        grid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void updateThumbSize(int columns) {
        if (adapter == null) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        adapter.setThumbPx(width / Math.max(1, columns));
    }

    private void openView() {
        Context c = requireContext();
        GallerySheets.show(c, GallerySheets.viewOptions(c, () -> {
            clearSelection();
            setColumns(GalleryView.columns(c));
            viewModel.applyView();
            refreshFilterChip();
        }));
    }

    // ---------------------------------------------------------------- selection

    @Override
    public boolean selecting() {
        return selection.isSelectionStarted() && !selection.isEmpty();
    }

    @Override
    public boolean isSelected(GalleryItem item) {
        return selection.getSelectedItems().contains(item);
    }

    @Override
    public boolean isFavourite(GalleryItem item) {
        return favourites.contains(GalleryView.key(item.getFile()));
    }

    @Override
    public void onDayClicked(int firstItem, int count) {
        List<GalleryItem> day = adapter.dayItems(firstItem, count);
        boolean all = selection.getSelectedItems().containsAll(day);
        for (GalleryItem g : day) {
            if (all) selection.deselectItem(g);
            else if (!selection.getSelectedItems().contains(g)) selection.selectItem(g);
        }
        if (grid != null) grid.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        onSelectionChanged();
    }

    @Override
    public void onPhotoClicked(int itemIndex, View view) {
        if (selecting() || selectMode) {
            selection.toggleSelection(adapter.items().get(itemIndex));
            onSelectionChanged();
            return;
        }
        Bundle b = new Bundle();
        b.putInt(Constants.IMAGE_POSITION_KEY, itemIndex);
        int[] at = new int[2];
        view.getLocationInWindow(at);
        b.putIntArray(CELL_KEY, new int[]{at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight()});
        navController.navigate(R.id.action_imageLibraryFragment_to_imageViewerFragment, b);
    }

    private void selectRange(int fromPosition, int toPosition) {
        int a = Math.min(fromPosition, toPosition), b = Math.max(fromPosition, toPosition);
        Set<GalleryItem> want = new LinkedHashSet<>(dragBase);
        for (int p = a; p <= b; ++p) {
            int item = adapter.itemAt(p);
            if (item >= 0) want.add(adapter.items().get(item));
        }
        for (GalleryItem g : new ArrayList<>(selection.getSelectedItems())) if (!want.contains(g)) selection.deselectItem(g);
        for (GalleryItem g : want) if (!selection.getSelectedItems().contains(g)) selection.selectItem(g);
        onSelectionChanged();
    }

    private void clearSelection() {
        selectMode = false;
        selection.deselectAll();
        dragBase = new LinkedHashSet<>();
        onSelectionChanged();
    }

    /** P498: a tap on the count: every photo shown selected, or the selection cleared when all already are. */
    private void selectAll() {
        if (adapter == null) return;
        List<GalleryItem> all = adapter.items();
        if (selection.getSelectedItems().size() >= all.size()) {
            clearSelection();
            return;
        }
        for (GalleryItem g : all) if (!selection.getSelectedItems().contains(g)) selection.selectItem(g);
        onSelectionChanged();
    }

    private void onSelectionChanged() {
        if (adapter == null || grid == null) return;
        int n = selection.getSelectedItems().size();
        if (n == 0 && selection.isSelectionStarted()) selection.deselectAll();
        boolean on = selecting() || selectMode;
        adapter.refreshSelection(grid);
        GalleryMotion.rise(selectionBar, on);
        if (tabBar != null && !trashOpen) tabBar.shown(!on);
        bar.bind(n);
        bar.bindFavourite(n > 0 && selection.getSelectedItems().stream().allMatch(this::isFavourite));
    }

    private void favouriteSelected() {
        List<GalleryItem> s = new ArrayList<>(selection.getSelectedItems());
        if (s.isEmpty()) return;
        boolean all = s.stream().allMatch(this::isFavourite);
        List<MediaFile> files = s.stream().map(GalleryItem::getFile).collect(Collectors.toList());
        GalleryView.setFavourite(requireContext(), files, !all);
        favourites = GalleryView.favourites(requireContext());
        View root = getView();
        if (root != null) com.particlesdevs.photoncamera.gallery.ui.GalleryToast.show(root.getContext(), all ? Lang.t("Убрано из избранного: ", "Removed from favourites: ") + GalleryUi.shots(s.size())
                : Lang.t("В избранном: ", "Added to favourites: ") + GalleryUi.shots(s.size()));
        clearSelection();
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        viewModel.setUpdatePending(true); // the «Избранное» chip and its count
    }

    private void compareSelected() {
        List<GalleryItem> s = selection.getSelectedItems();
        if (s.size() != 2) return;
        List<GalleryItem> items = adapter.items();
        Bundle b = new Bundle(2);
        b.putInt(Constants.IMAGE1_KEY, items.indexOf(s.get(0)));
        b.putInt(Constants.IMAGE2_KEY, items.indexOf(s.get(1)));
        navController.navigate(R.id.action_imageLibraryFragment_to_imageCompareFragment, b);
    }

    private void shareSelected() {
        List<GalleryItem> s = selection.getSelectedItems();
        ArrayList<Uri> uris = s.stream().map(g -> g.getFile().getFileUri()).collect(Collectors.toCollection(ArrayList::new));
        if (uris.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        // P498: the files' own type (a video is not image/*)
        share.setType(GalleryUi.shareType(s.stream().map(g -> g.getFile().getDisplayName()).collect(Collectors.toList())));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, null));
    }

    private List<GalleryItem> pendingDelete = new ArrayList<>();

    private void deleteSelected() {
        List<GalleryItem> toDelete = new ArrayList<>(selection.getSelectedItems());
        if (toDelete.isEmpty()) return;
        List<ImageFile> files = toDelete.stream().map(g -> (ImageFile) g.getFile()).collect(Collectors.toList());
        if (GalleryFileOperations.needsSystemDeleteRequest(requireContext(), files)) {
            // P345: another app's files - the system's own dialog is the only confirmation
            pendingDelete = toDelete;
            GalleryFileOperations.deleteImageFiles(getActivity(), files, this::handleImagesDeletedCallback);
            return;
        }
        // P498: this app's own files leave the grid at once, with «Вернуть» for 5 s
        clearSelection();
        GalleryUndo.delete(requireContext(), root, GalleryUi.dp(requireContext(), 24), files, viewModel);
    }

    public void handleImagesDeletedCallback(boolean isDeleted) {
        View root = getView();
        if (isDeleted) {
            List<GalleryItem> removed = pendingDelete.isEmpty() ? new ArrayList<>(selection.getSelectedItems()) : pendingDelete;
            int n = removed.size();
            pendingDelete = new ArrayList<>();
            clearSelection();
            // the refresh puts the folder read again into the grid (MediaStore deletes are done by now)
            viewModel.setUpdatePending(true);
            if (root != null) com.particlesdevs.photoncamera.gallery.ui.GalleryToast.show(root.getContext(), Lang.t("Удалено: ", "Deleted: ") + GalleryUi.shots(n));
        } else if (root != null) {
            com.particlesdevs.photoncamera.gallery.ui.GalleryToast.show(root.getContext(), Lang.t("Не удалось удалить", "Deletion failed"));
        }
    }

    // ---------------------------------------------------------------- folders sheet

    private void openFolders() {
        Context c = requireContext();
        ArrayList<GalleryFileOperations.ImagesFolder> all = GalleryFileOperations.FindAllFoldersWithImages(c.getContentResolver());
        Set<String> chosen = new HashSet<>(PreferenceKeys.getStringSet(PreferenceKeys.Key.FOLDERS_LIST));
        if (chosen.isEmpty()) for (GalleryFileOperations.ImagesFolder f : GalleryFileOperations.getSelectedFolders()) chosen.add(String.valueOf(f.getFolderId()));
        List<GallerySheets.Folder> rows = new ArrayList<>();
        for (GalleryFileOperations.ImagesFolder f : all) {
            String id = String.valueOf(f.getFolderId());
            rows.add(new GallerySheets.Folder(id, f.getFolderName(), folderPath(f), chosen.contains(id) || chosen.contains(f.getFolderName())));
        }
        GallerySheets.show(c, GallerySheets.folders(c, (Boolean) null, rows, (folder, on) -> {
            if (folder == null) return;
            Set<String> set = new HashSet<>();
            // P491 (owner, 2026-11-03: the shown folders were not remembered): stored by name - the folders are merged by their
            // name and a merged one took the id of the bucket with the newest photo, so a stored id stopped matching after new photos
            for (GallerySheets.Folder r : rows) if (r.on) set.add(r.name);
            ScameraApp.getSettingsManagerStatic().set("default_scope", PreferenceKeys.Key.FOLDERS_LIST, set);
            clearSelection();
            viewModel.setUpdatePending(true);
        }));
    }

    private static String folderPath(GalleryFileOperations.ImagesFolder f) {
        ImageFile top = f.getTopImage();
        String p = top != null ? top.getAbsolutePath() : null;
        if (p == null) return f.getFolderName();
        int slash = p.lastIndexOf('/');
        String dir = slash > 0 ? p.substring(0, slash) : p;
        String root = "/storage/emulated/0/";
        return dir.startsWith(root) ? dir.substring(root.length()) : dir;
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }



    @Override
    public void onStop() {
        com.particlesdevs.photoncamera.gallery.ocr.OcrIndex.stop(); // P504: the text index reads only while the gallery is open
        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (everything != null && getContext() != null) {
            List<com.particlesdevs.photoncamera.gallery.files.MediaFile> files = new ArrayList<>();
            for (GalleryItem i : everything) if (i.getFile() != null) files.add(i.getFile());
            com.particlesdevs.photoncamera.gallery.ocr.OcrIndex.start(getContext(), files, () -> { if (search != null) search.ocrChanged(); });
        }
    }

    @Override
    public void onDestroyView() {
        grid = null;
        adapter = null;
        layout = null;
        root = null;
        super.onDestroyView();
    }
}
