package com.particlesdevs.photoncamera.gallery.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.model.GalleryView;
import com.particlesdevs.photoncamera.gallery.viewmodel.GalleryViewModel;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P498 (owner, 2026-11-04: «полноценная галерея», «удобства»): deleting this app's own photos and videos with an undo, as stock
 * galleries do - they leave the grid and the viewer at once (GalleryView.hide), a pill «Удалено: 3 снимка · Вернуть» stays 5 s, and
 * only then are the files removed (this app's own MediaStore rows: no system dialog). «Вернуть» brings them back untouched. A new
 * delete, or the pill's time running out with the gallery closed, removes the earlier ones first. Another app's files still go
 * through the system's delete request (its own dialog is the confirmation), without the undo.
 */
public final class GalleryUndo {
    private GalleryUndo() {}

    static final long WINDOW_MS = 5000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "GalleryDelete"));
    @Nullable private static Pending pending;

    private static final class Pending {
        final List<ImageFile> files;
        final Context app;
        final GalleryViewModel vm;
        @Nullable View pill;
        final Runnable commit = this::commit;

        Pending(List<ImageFile> files, Context app, GalleryViewModel vm) {
            this.files = files;
            this.app = app;
            this.vm = vm;
        }

        void commit() {
            MAIN.removeCallbacks(commit);
            if (pending == this) pending = null;
            GalleryMotion.rise(pill, false);
            IO.execute(() -> {
                ContentResolver r = app.getContentResolver();
                // P502: into the system's trash («Недавно удаленные», 30 days) where there is one; else deleted as before
                java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
                for (ImageFile f : files) uris.add(f.getFileUri());
                if (com.particlesdevs.photoncamera.gallery.files.GalleryTrash.setOwnTrashed(app, uris, true)) {
                    MAIN.post(() -> {
                        GalleryView.unhide(files);
                        vm.setUpdatePending(true);
                    });
                    return;
                }
                for (ImageFile f : files) {
                    try {
                        r.delete(f.getFileUri(), Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? null : MediaStore.MediaColumns._ID + "= ?",
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? null : new String[]{String.valueOf(f.getId())});
                    } catch (RuntimeException e) {
                        com.particlesdevs.photoncamera.util.Log.w("GalleryUndo", "delete " + f.getFileUri() + ": " + e);
                    }
                }
                MAIN.post(() -> {
                    GalleryView.unhide(files);
                    vm.setUpdatePending(true); // the lists read again: the rows are gone now
                });
            });
        }

        void undo() {
            MAIN.removeCallbacks(commit);
            if (pending == this) pending = null;
            GalleryView.unhide(files);
            vm.applyView();
            GalleryMotion.rise(pill, false);
        }
    }

    /** Removes the files at once if an undo is still pending (the gallery is closing). */
    public static void flush() {
        Pending p = pending;
        if (p != null) p.commit();
    }

    /**
     * Hides {@code files} now and deletes them after the undo window; the pill goes into {@code host} at its bottom,
     * {@code bottomMarginPx} above the edge.
     */
    public static void delete(Context c, FrameLayout host, int bottomMarginPx, List<ImageFile> files, GalleryViewModel vm) {
        flush();
        if (files.isEmpty()) return;
        Pending p = new Pending(new ArrayList<>(files), c.getApplicationContext(), vm);
        pending = p;
        GalleryView.hide(p.files);
        vm.applyView();
        p.pill = pill(c, files.size() == 1 ? Lang.t("Удалено", "Deleted") : Lang.t("Удалено: ", "Deleted: ") + GalleryUi.shots(files.size()), p::undo);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = bottomMarginPx;
        p.pill.setVisibility(View.GONE);
        host.addView(p.pill, lp);
        GalleryMotion.rise(p.pill, true);
        MAIN.postDelayed(p.commit, WINDOW_MS);
    }

    /** The pill: «Удалено: 3 снимка» and the accent «Вернуть». */
    static LinearLayout pill(Context c, String text, Runnable undo) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(GalleryUi.round(c, 0xF21B2023, GalleryUi.LINE, 999));
        p.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 4), GalleryUi.dp(c, 6), GalleryUi.dp(c, 4));
        p.setElevation(GalleryUi.dp(c, 6));
        TextView t = GalleryUi.text(c, text, 14, GalleryUi.TEXT);
        p.addView(t);
        TextView back = GalleryUi.text(c, Lang.t("Вернуть", "Undo"), 14, GalleryUi.accent(c));
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setGravity(Gravity.CENTER);
        back.setMinHeight(GalleryUi.dp(c, 40));
        back.setPadding(GalleryUi.dp(c, 14), 0, GalleryUi.dp(c, 14), 0);
        back.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 999)));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> undo.run());
        back.setTag("gallery_undo");
        Motion.press(back);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = GalleryUi.dp(c, 8);
        p.addView(back, bp);
        p.setContentDescription(text);
        p.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return p;
    }
}
