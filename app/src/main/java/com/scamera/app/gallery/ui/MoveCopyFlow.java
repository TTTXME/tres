package com.scamera.app.gallery.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.UiTokens;
import com.scamera.app.gallery.files.GalleryFileOperations;
import com.scamera.app.gallery.files.GalleryMoveCopy;
import com.scamera.app.gallery.files.GalleryMoveCopy.Mode;
import com.scamera.app.gallery.files.GalleryMoveCopy.Target;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * P697 (owner, 2026-11-14: «добавь в галерее возможность перемещать и копировать фото по папкам»): «Переместить в папку» /
 * «Копировать в папку» of the selected photos and videos (the library's selection bar and a row's menu, the viewer's «Ещё»). The
 * gallery's sheet lists «Новая папка…» (a name; the folder goes under DCIM/) and the folders of the albums; a tap starts the work
 * on a worker thread (GalleryMoveCopy): a toast while it runs and one with the result (done, already there, failed), then
 * {@code changed} (the lists read again). A move of another app's files first asks the system's write request (Android 11+; its
 * dialog is the confirmation, as for a delete); the answer comes back through GalleryActivity ({@link #writeAnswered}).
 */
public final class MoveCopyFlow {
    private MoveCopyFlow() {}

    private static final String TAG = "GalleryMoveCopy";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "GalleryMoveCopy"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** One operation: what, which files (in groups that go together), where, and what runs after it. */
    private static final class Work {
        final Mode mode;
        final List<List<MediaFile>> groups;
        final Target target;
        final Runnable changed;

        Work(Mode mode, List<List<MediaFile>> groups, Target target, Runnable changed) {
            this.mode = mode;
            this.groups = groups;
            this.target = target;
            this.changed = changed;
        }
    }

    /** A move waiting for the system's write request. */
    @Nullable private static Work waiting;

    /** The sheet's and the menus' labels. */
    public static String label(Mode mode) {
        return mode == Mode.MOVE ? Lang.t("Переместить в папку", "Move to a folder") : Lang.t("Копировать в папку", "Copy to a folder");
    }

    /** The icon of the action: a folder for a move, the stacked layers for a copy. */
    public static int icon(Mode mode) {
        return mode == Mode.MOVE ? R.drawable.ui_ic_folder : R.drawable.ui_ic_layers;
    }

    /**
     * The folder sheet for {@code selected} (photos and videos with a MediaStore row), then the work; {@code changed} runs on the
     * main thread after it (also when only a part went).
     */
    public static void start(Activity a, Mode mode, List<? extends MediaFile> selected, Runnable changed) {
        List<MediaFile> files = new ArrayList<>();
        for (MediaFile f : selected) if (f != null && f.contentUri() != null && f.rowId() >= 0) files.add(f);
        if (files.isEmpty()) return;
        // the albums of the last scan: their folders, and the files a selected RAW or photo pairs with
        List<MediaFile> all = new ArrayList<>();
        Map<String, List<String>> albums = new LinkedHashMap<>();
        for (GalleryFileOperations.ImagesFolder f : GalleryFileOperations.getAllFolders()) {
            List<String> paths = albums.get(f.getFolderName());
            if (paths == null) albums.put(f.getFolderName(), paths = new ArrayList<>());
            for (ImageFile i : f.getAllImageFiles()) {
                all.add(i);
                paths.add(i.filePath());
            }
        }
        List<List<MediaFile>> groups = GalleryMoveCopy.groups(files, all);
        List<Target> targets = GalleryMoveCopy.targets(albums);
        final BottomSheetDialog[] d = new BottomSheetDialog[1];
        LinearLayout s = sheet(a, mode, files.size(), groups, targets, t -> {
            if (d[0] != null) d[0].dismiss();
            proceed(a, new Work(mode, groups, t, changed));
        }, () -> {
            if (d[0] != null) d[0].dismiss();
            askName(a, t -> proceed(a, new Work(mode, groups, t, changed)));
        });
        d[0] = GallerySheets.show(a, s);
    }

    /**
     * The sheet: the title, the count (and the paired files that go along), «Новая папка…», then one row per folder (its name and
     * place). For a move, a folder that holds every file already is dimmed.
     */
    @VisibleForTesting
    static LinearLayout sheet(Context c, Mode mode, int selected, List<List<MediaFile>> groups, List<Target> targets,
                              Consumer<Target> picked, Runnable newFolder) {
        LinearLayout s = GallerySheets.frame(c);
        s.addView(GallerySheets.title(c, label(mode)));
        int files = GalleryMoveCopy.count(groups), paired = files - selected;
        s.addView(GallerySheets.sub(c, GalleryUi.shots(selected) + (paired > 0
                ? Lang.t(" · с ними пары RAW + фото того же имени: ", " · with the RAW + photo pairs of the same name: ") + paired : "")));
        List<View> shown = new ArrayList<>();
        LinearLayout top = GallerySheets.column(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = GalleryUi.dp(c, 12);
        s.addView(top, rp);
        LinearLayout create = row(c, R.drawable.ui_ic_plus, Lang.t("Новая папка…", "New folder…"), GalleryMoveCopy.NEW_FOLDER_PARENT, true);
        create.setTag("move_new_folder");
        create.setOnClickListener(v -> newFolder.run());
        top.addView(create);
        shown.add(create);
        s.addView(GalleryUi.sectionLabel(c, Lang.t("Папки", "Folders")));
        if (targets.isEmpty()) s.addView(GallerySheets.sub(c, Lang.t("Других папок пока нет", "No other folders yet")));
        else {
            LinearLayout list = GallerySheets.column(c);
            s.addView(list);
            List<MediaFile> flat = new ArrayList<>();
            for (List<MediaFile> g : groups) flat.addAll(g);
            for (Target t : targets) {
                boolean here = mode == Mode.MOVE && allIn(flat, t);
                LinearLayout r = row(c, R.drawable.ui_ic_folder, t.name, here ? Lang.t("Уже здесь", "Already here") : place(t), !here);
                r.setTag("move_target");
                if (!here) r.setOnClickListener(v -> picked.accept(t));
                list.addView(r);
                shown.add(r);
            }
        }
        com.scamera.app.ui.Motion.enter(shown, 60, 28);
        return s;
    }

    private static boolean allIn(List<MediaFile> files, Target t) {
        for (MediaFile f : files) if (!GalleryMoveCopy.inFolder(f, t)) return false;
        return true;
    }

    /** Where a folder is: its relative path, with the SD card's name when it is not on the phone's storage. */
    static String place(Target t) {
        String rel = t.relativePath.endsWith("/") ? t.relativePath.substring(0, t.relativePath.length() - 1) : t.relativePath;
        if ("external_primary".equals(t.volume())) return rel;
        return Lang.t("Карта ", "Card ") + t.volume().toUpperCase(java.util.Locale.ROOT) + ": " + rel;
    }

    /** A row of the sheet: the accent icon, the name and the place under it, the chevron; dimmed when it cannot be picked. */
    private static LinearLayout row(Context c, int icon, String title, String sub, boolean enabled) {
        LinearLayout row = GallerySheets.cardRow(c);
        row.addView(GalleryUi.icon(c, icon, GalleryUi.accent(c), 22));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tt = GalleryUi.text(c, title, 16, GalleryUi.TEXT);
        tt.setMaxLines(2);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView st = GalleryUi.text(c, sub, 13, GalleryUi.MUTED);
        st.setMaxLines(2);
        st.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        st.setPadding(0, GalleryUi.dp(c, 3), 0, 0);
        texts.addView(tt);
        texts.addView(st);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMarginStart(GalleryUi.dp(c, 14));
        tp.setMarginEnd(GalleryUi.dp(c, 8));
        row.addView(texts, tp);
        ImageView chev = GalleryUi.icon(c, R.drawable.cs_chevron, GalleryUi.SECTION, 16);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (chev.getDrawable() != null) chev.getDrawable().mutate().setAutoMirrored(true);
        row.addView(chev);
        row.setContentDescription(title + ", " + sub);
        row.setClickable(enabled);
        row.setFocusable(enabled);
        GalleryUi.setEnabledLook(row, enabled);
        return row;
    }

    /**
     * «Новая папка»: the gallery's alert card with the name field, «Отмена» and «Создать» (the keyboard's done too); an empty or
     * unusable name keeps it open with a toast.
     */
    static android.app.Dialog askName(Context c, Consumer<Target> created) {
        final android.app.Dialog[] d = new android.app.Dialog[1];
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GallerySheets.alertCard(c));
        card.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 20), GalleryUi.dp(c, 16), GalleryUi.dp(c, 16));
        TextView title = GallerySheets.title(c, Lang.t("Новая папка", "New folder"));
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView what = GalleryUi.text(c, Lang.t("Папка появится в DCIM и в «Альбомах».", "The folder appears in DCIM and in «Albums»."), 14, 0xB3FFFFFF);
        what.setGravity(Gravity.CENTER);
        what.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = GalleryUi.dp(c, 6);
        card.addView(what, wp);
        EditText field = new EditText(c);
        field.setBackground(GalleryUi.card(c, GalleryUi.CAPSULE, 14));
        field.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 10), GalleryUi.dp(c, 14), GalleryUi.dp(c, 10));
        field.setMinHeight(GalleryUi.dp(c, 46));
        field.setHint(Lang.t("Название папки", "Folder name"));
        field.setHintTextColor(GalleryUi.SECTION);
        field.setTextColor(GalleryUi.TEXT);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTag("move_folder_name");
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = GalleryUi.dp(c, 14);
        card.addView(field, fp);
        Runnable create = () -> {
            String name = GalleryMoveCopy.cleanFolderName(field.getText().toString());
            if (name == null) {
                GalleryToast.show(c, Lang.t("Введите название папки", "Enter a folder name"));
                return;
            }
            if (d[0] != null) d[0].dismiss();
            created.accept(GalleryMoveCopy.newFolder(name, primaryRoot()));
        };
        field.setOnEditorActionListener((v, action, e) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            create.run();
            return true;
        });
        TextView no = GallerySheets.bigButton(c, Lang.t("Отмена", "Cancel"), false);
        no.setTag("confirm_no");
        no.setOnClickListener(v -> {
            if (d[0] != null) d[0].dismiss();
        });
        TextView yes = GalleryUi.capsule(c, Lang.t("Создать", "Create"), GalleryUi.PRIMARY);
        UiTokens.setTextSp(yes, 17);
        yes.setTag("confirm_yes");
        yes.setOnClickListener(v -> create.run());
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(no, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        yp.setMarginStart(GalleryUi.dp(c, 8));
        buttons.addView(yes, yp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = GalleryUi.dp(c, 18);
        card.addView(buttons, bp);
        card.setContentDescription(Lang.t("Новая папка", "New folder"));
        d[0] = GallerySheets.alert(c, card);
        if (d[0].getWindow() != null)
            d[0].getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // after the alert's own focus on the card (its show listener): the field, with the keyboard
        field.postDelayed(() -> {
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
        return d[0];
    }

    @SuppressWarnings("deprecation") // the primary storage's path: the root of a new folder's absolute path (before Android 10)
    private static String primaryRoot() {
        try {
            return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        } catch (RuntimeException e) {
            return "/storage/emulated/0/";
        }
    }

    /** A move of another app's files asks the system first (Android 11+); then the work. */
    private static void proceed(Activity a, Work w) {
        if (w.mode != Mode.MOVE || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            execute(a, w);
            return;
        }
        final Context app = a.getApplicationContext();
        IO.execute(() -> {
            List<MediaFile> files = new ArrayList<>();
            for (List<MediaFile> g : w.groups) for (MediaFile f : g) if (!GalleryMoveCopy.inFolder(f, w.target)) files.add(f);
            List<Uri> foreign = GalleryMoveCopy.foreignUris(app, files);
            MAIN.post(() -> {
                PendingIntent ask = foreign.isEmpty() ? null : GalleryMoveCopy.writeRequest(app, foreign);
                if (ask == null || a.isFinishing()) {
                    execute(a, w); // nothing to ask (or a row gone: each file then fails or goes on its own)
                    return;
                }
                waiting = w;
                try {
                    ActivityCompat.startIntentSenderForResult(a, ask.getIntentSender(), GalleryMoveCopy.REQUEST_PERM_WRITE, null, 0, 0, 0, null);
                } catch (IntentSender.SendIntentException e) {
                    Log.w(TAG, "system write request: " + e);
                    waiting = null;
                    execute(a, w);
                }
            });
        });
    }

    /** The system's write request answered (GalleryActivity.onActivityResult): the waiting move runs, or is cancelled. */
    public static void writeAnswered(Activity a, boolean ok) {
        Work w = waiting;
        waiting = null;
        if (w == null) return;
        if (ok) execute(a, w);
        else GalleryToast.show(a, Lang.t("Перемещение отменено", "Move cancelled"));
    }

    /** The work on the worker thread: a toast now (and the count on the way for many files), the result's toast, {@code changed}. */
    private static void execute(Activity a, Work w) {
        final int total = GalleryMoveCopy.count(w.groups);
        GalleryToast.show(a, progressText(w.mode, 0, total));
        final Context app = a.getApplicationContext();
        final long[] lastShown = {SystemClock.uptimeMillis()};
        IO.execute(() -> {
            GalleryMoveCopy.Result r;
            try {
                r = GalleryMoveCopy.run(app, w.mode, w.groups, w.target, (finished, all) -> {
                    long now = SystemClock.uptimeMillis();
                    if (all < 5 || finished >= all || now - lastShown[0] < 700) return;
                    lastShown[0] = now;
                    MAIN.post(() -> GalleryToast.show(a, progressText(w.mode, finished, all)));
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "run: " + e);
                r = new GalleryMoveCopy.Result();
                r.failed = total;
            }
            final GalleryMoveCopy.Result done = r;
            MAIN.post(() -> {
                GalleryToast.show(a, resultText(w.mode, w.target, done), done.failed > 0);
                w.changed.run();
            });
        });
    }

    /** «Перемещение: 3 снимка…», with «N из M» on the way. */
    static String progressText(Mode mode, int finished, int total) {
        String what = mode == Mode.MOVE ? Lang.t("Перемещение: ", "Moving: ") : Lang.t("Копирование: ", "Copying: ");
        if (finished <= 0) return what + GalleryUi.shots(total) + "…";
        return what + finished + Lang.t(" из ", " of ") + total + "…";
    }

    /** The result: what went where, how many were there already, how many failed. */
    static String resultText(Mode mode, Target t, GalleryMoveCopy.Result r) {
        String where = "«" + t.name + "»";
        if (r.done == 0) {
            if (r.failed == 0 && r.skipped > 0) return Lang.t("Уже в папке ", "Already in ") + where;
            return mode == Mode.MOVE ? Lang.t("Не удалось переместить", "Could not move") : Lang.t("Не удалось скопировать", "Could not copy");
        }
        StringBuilder b = new StringBuilder(mode == Mode.MOVE ? Lang.t("Перемещено в ", "Moved to ") : Lang.t("Скопировано в ", "Copied to "));
        b.append(where).append(": ").append(GalleryUi.shots(r.done));
        if (r.skipped > 0) b.append(Lang.t(", уже там: ", ", already there: ")).append(r.skipped);
        if (r.failed > 0) b.append(Lang.t(", не удалось: ", ", failed: ")).append(r.failed);
        return b.toString();
    }
}
