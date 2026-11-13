package com.scamera.app.gallery.ui.fragments;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.navigation.fragment.NavHostFragment;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.scamera.app.R;
import com.scamera.app.gallery.compare.PaneLink;
import com.scamera.app.gallery.compare.SSIVListener;
import com.scamera.app.gallery.ui.GallerySheets;
import com.scamera.app.gallery.ui.GalleryUi;
import com.scamera.app.gallery.views.CustomSSIV;
import com.scamera.app.util.Lang;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.file.Files;

import static com.scamera.app.gallery.helper.Constants.*;

/**
 * Created by Vibhor Srivastava on 09-Jan-2021
 *
 * <p>P59b: compare in the card style (GALLERY_TASK.md §5): header «Сравнение» with back and swap, two panes (stacked;
 * side by side in landscape) each with its label card (route + format icons, the EXIF line, P196 resolution · phone, file name; a tap on either
 * pane switches both to «time · ISO» and back) and zoom
 * pill, the «Зум и сдвиг вместе» switch card (both panes follow each other through the photos: PaneLink) and the share card button (the screenshot of
 * both panes, as before). Back returns to where compare was opened from.
 */
public class ImageCompareFragment extends Fragment {
    /** «Зум и сдвиг вместе»; on again for every new comparison. */
    private static boolean toSync = true;
    private final PaneSync ssivListener = new PaneSync();
    private ImageViewerFragment fragment1 = new ImageViewerFragment();
    private ImageViewerFragment fragment2 = new ImageViewerFragment();
    private int pos1, pos2;
    /** The shared picture's folder (cache/images, the FileProvider's path). */
    private File shareDir;
    private Context appContext;
    private View root, header, bar, panes;
    private int pane1Id, pane2Id;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        appContext = requireContext();
        shareDir = new File(appContext.getCacheDir(), "images");
        if (!shareDir.isDirectory() && !shareDir.mkdirs()) com.scamera.app.util.Log.w("GALLERY_DECODE", "no " + shareDir);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context c = requireContext();
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(0xFF000000);
        View back = GalleryUi.squareButton(c, R.drawable.ui_ic_back, Lang.t("Назад", "Back"), v -> NavHostFragment.findNavController(this).navigateUp());
        View swap = GalleryUi.squareButton(c, R.drawable.ui_ic_swap, Lang.t("Поменять местами", "Swap"), v -> swap());
        header = GalleryUi.header(c, Lang.t("Сравнение", "Compare"), back, swap);
        header.setBackgroundColor(GalleryUi.BG);
        column.addView(header);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        FrameLayout a = new FrameLayout(c), b = new FrameLayout(c);
        pane1Id = View.generateViewId();
        pane2Id = View.generateViewId();
        a.setId(pane1Id);
        b.setId(pane2Id);
        LinearLayout.LayoutParams ap = landscape ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        LinearLayout.LayoutParams bp = landscape ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        if (landscape) bp.leftMargin = GalleryUi.dp(c, 4);
        else bp.topMargin = GalleryUi.dp(c, 4);
        p.addView(a, ap);
        p.addView(b, bp);
        panes = p;
        column.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout cbar = new LinearLayout(c);
        cbar.setOrientation(LinearLayout.HORIZONTAL);
        cbar.setGravity(Gravity.CENTER_VERTICAL);
        cbar.setBackgroundColor(GalleryUi.BG);
        cbar.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 8), GalleryUi.dp(c, 12), GalleryUi.dp(c, 12));
        cbar.addView(syncCard(c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View share = GalleryUi.squareButton(c, R.drawable.ui_ic_share, Lang.t("Поделиться сравнением", "Share the comparison"), v -> onShareClick());
        // P579 (owner, 2026-11-09: «… в ней надо стиль также обновить»): the round 48dp disc beside the switch card
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 48), GalleryUi.dp(c, 48));
        sp.setMarginStart(GalleryUi.dp(c, 10));
        cbar.addView(share, sp);
        bar = cbar;
        column.addView(cbar);
        root = column;
        return column;
    }

    /** «Зум и сдвиг вместе»: a switch card (replaces the old sync toggle). */
    private View syncCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(GalleryUi.dp(c, 52));
        // P579: a row card (#2C2C2E, 24dp corners) with the shared switch
        card.setBackground(GalleryUi.pressable(GalleryUi.card(c, GalleryUi.CARD, 24)));
        card.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 8), GalleryUi.dp(c, 12), GalleryUi.dp(c, 8));
        card.addView(GalleryUi.icon(c, R.drawable.ui_ic_link, GalleryUi.accent(c), 22));
        TextView t = GalleryUi.fitText(c, Lang.t("Зум и сдвиг вместе", "Zoom and pan together"), 15, GalleryUi.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMarginStart(GalleryUi.dp(c, 12));
        tp.setMarginEnd(GalleryUi.dp(c, 10));
        card.addView(t, tp);
        View sw = GallerySheets.toggleView(c, toSync);
        card.addView(sw);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(Lang.t("Зум и сдвиг вместе", "Zoom and pan together"));
        androidx.core.view.ViewCompat.setStateDescription(card, toSync ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
        card.setOnClickListener(v -> {
            toSync = !toSync;
            // On again: linked as the panes are shown now, so a zoom or place set while it was off is kept
            if (toSync) ssivListener.relink(false);
            GallerySheets.setToggle(sw, toSync);
            androidx.core.view.ViewCompat.setStateDescription(card, toSync ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
        });
        return card;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        final Bundle args = getArguments();
        if (args == null) return;
        // a new comparison: the panes linked, both with the full info (P196)
        toSync = true;
        ImageViewerFragment.paneFullExif = true;
        pos1 = args.getInt(ARG_FIRST_IMAGE);
        pos2 = args.getInt(ARG_SECOND_IMAGE);
        attach(pos1, pos2);
    }

    private void attach(int first, int second) {
        FragmentTransaction trans = getChildFragmentManager().beginTransaction();
        if (fragment1.isAdded()) trans.remove(fragment1);
        if (fragment2.isAdded()) trans.remove(fragment2);
        fragment1 = pane(first);
        fragment2 = pane(second);
        trans.add(pane1Id, fragment1, "image_container1");
        trans.add(pane2Id, fragment2, "image_container2");
        trans.commitAllowingStateLoss();
        ssivListener.relink(true);
    }

    private ImageViewerFragment pane(int position) {
        ImageViewerFragment f = new ImageViewerFragment();
        Bundle b = new Bundle();
        b.putString(ARG_MODE, MODE_COMPARE);
        b.putInt(ARG_POSITION, position);
        f.setArguments(b);
        f.setSsivListener(ssivListener);
        return f;
    }

    /** P196: both panes' labels again (a tap on either pane switches the full info of both). */
    void refreshLabels() {
        fragment1.refreshPaneLabel();
        fragment2.refreshPaneLabel();
    }

    /** Swaps the two photos between the panes. */
    private void swap() {
        int t = pos1;
        pos1 = pos2;
        pos2 = t;
        attach(pos1, pos2);
    }

    /**
     * Shares both panes as one JPEG. P117: the panes are drawn here, on the main thread (a background draw raced their own
     * tile decodes and recycling); only the file is written on a background thread. The bars hide while it runs.
     */
    private void onShareClick() {
        if (root == null || panes == null) return;
        showBars(false);
        final Bitmap shot;
        try {
            shot = picture(panes);
        } catch (RuntimeException e) {
            com.scamera.app.util.Log.w("GALLERY_DECODE", "compare screenshot failed: " + e);
            showBars(true);
            com.scamera.app.gallery.ui.GalleryToast.show(appContext, Lang.t("Не удалось", "Failed"));
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        Thread writer = new Thread(() -> {
            Uri file = shot == null ? null : write(shot);
            if (shot != null) shot.recycle();
            main.post(() -> {
                if (!isAdded()) return;
                if (file != null) send(file);
                else com.scamera.app.gallery.ui.GalleryToast.show(appContext, Lang.t("Не удалось", "Failed"));
                showBars(true);
            });
        }, "ScreenshotThread");
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    private void send(Uri file) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, file)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType(URLConnection.guessContentTypeFromName(file.toString()));
        share.setClipData(ClipData.newUri(appContext.getContentResolver(), "", file));
        startActivity(Intent.createChooser(share, null));
    }

    private void showBars(boolean shown) {
        int v = shown ? View.VISIBLE : View.INVISIBLE;
        if (header != null) header.setVisibility(v);
        if (bar != null) bar.setVisibility(v);
    }

    /** The picture as cache/images/compare_screenshot.jpg behind the app's FileProvider; null when it could not be written. */
    private Uri write(Bitmap picture) {
        File out = new File(shareDir, "compare_screenshot.jpg");
        try (OutputStream to = Files.newOutputStream(out.toPath())) {
            picture.compress(Bitmap.CompressFormat.JPEG, 100, to);
        } catch (IOException e) {
            com.scamera.app.util.Log.w("GALLERY_DECODE", "compare picture not written: " + e);
            return null;
        }
        try {
            return FileProvider.getUriForFile(appContext, appContext.getPackageName() + ".provider", out);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** What the view shows, drawn into a bitmap; null before it has a size. */
    private static Bitmap picture(View v) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    @Override
    public void onDestroyView() {
        root = header = bar = panes = null;
        super.onDestroyView();
    }

    /**
     * «Зум и сдвиг вместе» through the photos, not their pixels (PaneLink): the pane being touched leads, the other follows
     * through the link, so photos of a different resolution keep the same relative zoom and place, and panes at a different
     * zoom (one magnified while the switch was off, a 2x photo next to a 1x one) keep the same scene point while either pans
     * or zooms. Before: the leading pane's scale and centre in source pixels were copied, which put a 12 MP photo and a 50 MP
     * one at different zooms and places.
     */
    private class PaneSync extends SSIVListener {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private SubsamplingScaleImageView touched;
        private PaneLink link;
        private SubsamplingScaleImageView linked1, linked2;
        /** Link again at the next touch; by the photos' field of view (new photos) or as the panes are shown (switch on). */
        private boolean relink = true, byFieldOfView = true;

        void relink(boolean fieldOfView) {
            relink = true;
            byFieldOfView = fieldOfView;
        }

        @Override
        public void onScaleChanged(float scale, int source) {
            update();
        }

        @Override
        public void onCenterChanged(PointF centre, int source) {
            update();
        }

        @Override
        public void onTouched(int pointer) {
            // the view itself comes through onTouchedView
        }

        @Override
        public void onTouchedView(CustomSSIV view) {
            touched = view;
            // Before the gesture moves anything: the link is the panes' state at the touch-down
            if (toSync) ensureLink();
        }

        private void ensureLink() {
            SubsamplingScaleImageView v1 = fragment1.getCurrentSSIV(), v2 = fragment2.getCurrentSSIV();
            if (v1 != linked1 || v2 != linked2) relink(true); // another photo in a pane
            if (!relink || v1 == null || v2 == null || !v1.isReady() || !v2.isReady()) return;
            PointF c1 = v1.getCenter(), c2 = v2.getCenter();
            if (c1 == null || c2 == null) return;
            float f1 = fragment1.currentFocal35(), f2 = fragment2.currentFocal35();
            if (byFieldOfView && f1 > 0f && f2 > 0f && Math.abs(f2 / f1 - 1f) > 0.02f) {
                link = PaneLink.byFieldOfView(diagonal(v1), c1.x, c1.y, f1, diagonal(v2), c2.x, c2.y, f2);
            } else {
                link = PaneLink.asShown(v1.getScale(), c1.x, c1.y, v2.getScale(), c2.x, c2.y);
            }
            linked1 = v1;
            linked2 = v2;
            relink = false;
        }

        private float diagonal(SubsamplingScaleImageView v) {
            return (float) Math.hypot(v.getSWidth(), v.getSHeight());
        }

        public void update() {
            // Posted: either pane (or this fragment) may be destroyed when it runs; both helpers tolerate that.
            mainHandler.post(() -> {
                if (toSync) follow(fragment1.getCurrentSSIV(), fragment2.getCurrentSSIV());
                fragment1.updateScaleText();
                fragment2.updateScaleText();
            });
        }

        private void follow(SubsamplingScaleImageView v1, SubsamplingScaleImageView v2) {
            if (v1 == null || v2 == null || touched == null || !v1.isReady() || !v2.isReady()) return;
            ensureLink();
            if (link == null || v1 != linked1 || v2 != linked2) return;
            PointF c;
            if (touched == v1 && (c = v1.getCenter()) != null) {
                v2.setScaleAndCenter(link.scale2(v1.getScale()), new PointF(link.x2(c.x), link.y2(c.y)));
            } else if (touched == v2 && (c = v2.getCenter()) != null) {
                v1.setScaleAndCenter(link.scale1(v2.getScale()), new PointF(link.x1(c.x), link.y1(c.y)));
            }
        }
    }
}
