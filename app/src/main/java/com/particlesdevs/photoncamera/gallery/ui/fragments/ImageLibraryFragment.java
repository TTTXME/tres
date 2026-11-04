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

import com.google.android.material.snackbar.Snackbar;
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
import com.particlesdevs.photoncamera.gallery.ui.FastScroller;
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
 */
public class ImageLibraryFragment extends Fragment implements LibraryAdapter.Host {
    private static final String PREFS = "gallery_ui", KEY_CHIPS = "show_folder_chips";
    /** P491 (owner, 2026-11-03: «галерея должна запоминать мой выбор отображаемых папок»): the folder chip last chosen. */
    private static final String KEY_FOLDER = "active_folder";
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
    private HorizontalScrollView chipsScroll;
    private LinearLayout chips;
    private View selectionBar;
    private SelectionBar bar;
    private String activeFolder;
    private List<GalleryItem> folders = new ArrayList<>();
    private Set<String> favourites = new HashSet<>();
    private boolean firstFill = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context c = requireContext();
        navController = NavHostFragment.findNavController(this);
        favourites = GalleryView.favourites(c);
        root = new FrameLayout(c);
        root.setBackgroundColor(GalleryUi.BG);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View toCamera = GalleryUi.squareButton(c, R.drawable.ui_ic_camera, Lang.t("К камере", "Back to the camera"), v -> requireActivity().finish());
        View toView = GalleryUi.squareButton(c, R.drawable.ui_ic_filter, Lang.t("Вид галереи", "Gallery view"), v -> openView());
        toView.setTag("gallery_view");
        View toFolders = GalleryUi.squareButton(c, R.drawable.ui_ic_folders, Lang.t("Папки галереи", "Gallery folders"), v -> openFolders());
        column.addView(GalleryUi.header(c, Lang.t("Галерея", "Gallery"), toCamera, toView, toFolders));

        chipsScroll = new HorizontalScrollView(c);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipToPadding(false);
        chipsScroll.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 4), GalleryUi.dp(c, 16), GalleryUi.dp(c, 10));
        chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chips);
        column.addView(chipsScroll);

        FrameLayout body = new FrameLayout(c);
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        grid = new RecyclerView(c);
        grid.setClipToPadding(false);
        grid.setPadding(GalleryUi.dp(c, 9), 0, GalleryUi.dp(c, 9), GalleryUi.dp(c, 120));
        adapter = new LibraryAdapter(this);
        int columns = GalleryView.columns(c);
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

        bar = new SelectionBar(c, this::clearSelection, this::compareSelected, this::shareSelected, this::deleteSelected,
                this::favouriteSelected, this::selectAll);
        selectionBar = bar.view;
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.leftMargin = bp.rightMargin = GalleryUi.dp(c, 12);
        bp.bottomMargin = GalleryUi.dp(c, 14);
        root.addView(selectionBar, bp);
        selectionBar.setVisibility(View.GONE);

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
                if (selecting()) clearSelection();
                else if (leavesGallery(navController.getPreviousBackStackEntry(),
                        viewModel != null ? viewModel.getCurrentFolderImages().getValue() : null) || !navController.navigateUp())
                    requireActivity().finish();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
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
                activeFolder = viewModel.refreshKeeping(activeFolder);
                renderChips();
                viewModel.setUpdatePending(false);
            }
        });
        applyChipsVisibility();
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
    }

    private boolean filtered() {
        Context c = getContext();
        return c != null && (GalleryView.type(c) != GalleryView.Type.ALL || GalleryView.media(c) != GalleryView.Media.BOTH);
    }

    private void showFolders(List<GalleryItem> list) {
        folders = list != null ? list : new ArrayList<>();
        if (activeFolder == null && !folders.isEmpty()) {
            // a new library (or one recreated by a rotation) highlights the folder the list shows, else the first chip
            String current = viewModel != null ? viewModel.getCurrentFolderName() : null;
            activeFolder = folders.get(0).getDisplayName();
            for (GalleryItem f : folders) if (f.getDisplayName().equals(current)) activeFolder = current;
            // P491: a new library opens the folder chosen last time (rotation keeps the shown one: it is the same folder)
            final String remembered = prefs().getString(KEY_FOLDER, null);
            if (remembered != null && !remembered.equals(activeFolder)) for (GalleryItem f : folders) {
                if (!f.getDisplayName().equals(remembered)) continue;
                activeFolder = remembered;
                viewModel.setCurrentFolderImages(f);
                break;
            }
        }
        renderChips();
    }

    /** The chip name of a folder: «Все», «Избранное» or the folder's own. */
    static String chipName(String folder) {
        if ("ALL".equals(folder)) return Lang.t("Все", "All");
        if (GalleryView.FAVOURITES.equals(folder)) return "♥ " + Lang.t("Избранное", "Favourites");
        return folder;
    }

    private void renderChips() {
        Context c = getContext();
        if (c == null || chips == null) return;
        chips.removeAllViews();
        if (filtered()) {
            // P498: the active filter as the first chip; a tap shows everything again
            String what = GalleryView.media(c) == GalleryView.Media.PHOTOS ? Lang.t("Только фото", "Photos only")
                    : GalleryView.media(c) == GalleryView.Media.VIDEOS ? Lang.t("Только видео", "Videos only") : null;
            GalleryView.Type t = GalleryView.type(c);
            String type = t == GalleryView.Type.ALL ? null : t == GalleryView.Type.VIDEO ? Lang.t("Видео", "Video") : t.name();
            String label = (what != null && type != null ? what + " · " + type : what != null ? what : type) + "  ✕";
            TextView chip = GalleryUi.chip(c, label, -1, true);
            chip.setContentDescription(Lang.t("Фильтр: ", "Filter: ") + label + ", " + Lang.t("сбросить", "clear"));
            Motion.press(chip);
            chip.setOnClickListener(v -> {
                GalleryView.setType(c, GalleryView.Type.ALL);
                GalleryView.setMedia(c, GalleryView.Media.BOTH);
                viewModel.applyView();
                renderChips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = GalleryUi.dp(c, 8);
            chips.addView(chip, lp);
        }
        for (GalleryItem f : folders) {
            String name = chipName(f.getDisplayName());
            boolean active = f.getDisplayName().equals(activeFolder);
            TextView chip = GalleryUi.chip(c, name, f.getFiles().size(), active);
            chip.setContentDescription(name + ", " + GalleryUi.shots(f.getFiles().size()));
            Motion.press(chip);
            chip.setOnClickListener(v -> {
                clearSelection();
                activeFolder = f.getDisplayName();
                prefs().edit().putString(KEY_FOLDER, activeFolder).apply(); // P491
                viewModel.setCurrentFolderImages(f);
                renderChips();
                if (grid != null) grid.scrollToPosition(0);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = GalleryUi.dp(c, 8);
            chips.addView(chip, lp);
        }
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
                    setColumns(layout.getSpanCount() - 1);
                    acc = 1f;
                } else if (acc < .8f) {
                    setColumns(layout.getSpanCount() + 1);
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

    private void setColumns(int n) {
        if (layout == null || grid == null || adapter == null) return;
        n = GalleryView.clampColumns(n);
        if (n == layout.getSpanCount()) return;
        GalleryView.setColumns(requireContext(), n);
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
            renderChips();
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
        if (selecting()) {
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
        boolean on = selecting();
        adapter.refreshSelection(grid);
        GalleryMotion.rise(selectionBar, on);
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
        if (root != null) Snackbar.make(root, all ? Lang.t("Убрано из избранного: ", "Removed from favourites: ") + GalleryUi.shots(s.size())
                : Lang.t("В избранном: ", "Added to favourites: ") + GalleryUi.shots(s.size()), Snackbar.LENGTH_SHORT).show();
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
            if (root != null) Snackbar.make(root, Lang.t("Удалено: ", "Deleted: ") + GalleryUi.shots(n), Snackbar.LENGTH_SHORT).show();
        } else if (root != null) {
            Snackbar.make(root, Lang.t("Не удалось удалить", "Deletion failed"), Snackbar.LENGTH_SHORT).show();
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
        GallerySheets.show(c, GallerySheets.folders(c, chipsShown(), rows, (folder, on) -> {
            if (folder == null) {
                prefs().edit().putBoolean(KEY_CHIPS, on).apply();
                applyChipsVisibility();
                return;
            }
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

    private boolean chipsShown() {
        return prefs().getBoolean(KEY_CHIPS, true);
    }

    private void applyChipsVisibility() {
        if (chipsScroll != null) chipsScroll.setVisibility(chipsShown() ? View.VISIBLE : View.GONE);
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
