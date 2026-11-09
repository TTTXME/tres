package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.files.GalleryVideo;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.model.GallerySearch;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * P502 (owner, 2026-11-05: the reference video's «Поиск»): the search page - the field (search icon, clear), then while it is
 * empty the recent queries (a tap repeats one, the bin clears them), quick searches (types, today, this month, the years of the
 * library) and the albums; with text, the matching photos and videos in a 4-column grid that opens the viewer on them.
 * Matching: GallerySearch (names, folders, kinds, dates; ru and en) and the text read on the photos (P504, OcrIndex).
 */
public final class SearchPage {
    public interface Host {
        /** The library to search (every shown folder) and the favourites' keys. */
        List<GalleryItem> library();

        Set<String> favourites();

        List<AlbumsPage.Album> albums();

        void openResults(List<GalleryItem> results, int index, String title);

        void openAlbum(GalleryItem folder, String title);
    }

    private static final String KEY_RECENT = "search_recent";
    private static final int RECENT_MAX = 12;

    public final LinearLayout view;
    private final Host host;
    private final SharedPreferences prefs;
    private final EditText field;
    private final ImageView clear;
    private final ScrollView suggestions;
    private final LinearLayout suggestionColumn;
    private final LinearLayout results;
    private final TextView resultCount;
    private final ThumbGrid grid;
    private final Map<GalleryItem, String> cache = new IdentityHashMap<>();
    private List<GalleryItem> found = new ArrayList<>();

    public SearchPage(Context c, Host host) {
        this.host = host;
        prefs = c.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE);
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        // P579 (owner, 2026-11-09: «… в ней надо стиль также обновить»): the settings' search capsule - #2C2C2E, 44dp, no outline,
        // the glass and the hint at 55 % white
        box.setBackground(GalleryUi.card(c, GalleryUi.CARD, 22));
        box.setPadding(GalleryUi.dp(c, 12), 0, GalleryUi.dp(c, 4), 0);
        box.setMinimumHeight(GalleryUi.dp(c, 44));
        ImageView glass = GalleryUi.icon(c, R.drawable.ui_ic_search, GalleryUi.SECTION, 20);
        com.particlesdevs.photoncamera.ui.Icons.ink(glass, R.drawable.ui_ic_search, GalleryUi.SECTION);
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(glass);
        field = new EditText(c);
        field.setBackground(null);
        field.setHint(Lang.t("Текст на фото, «видео», «октябрь 2026»…", "Text in a photo, “video”, “October 2026”…")); // P504
        field.setHintTextColor(GalleryUi.SECTION);
        field.setTextColor(GalleryUi.TEXT);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        fp.setMarginStart(GalleryUi.dp(c, 8));
        box.addView(field, fp);
        clear = GalleryUi.icon(c, R.drawable.ui_ic_close, GalleryUi.SECTION, 20);
        int cp = GalleryUi.dp(c, 10);
        clear.setPadding(cp, cp, cp, cp);
        clear.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 999)));
        clear.setContentDescription(Lang.t("Очистить", "Clear"));
        clear.setOnClickListener(v -> field.setText(""));
        box.addView(clear, new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 40)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = bp.rightMargin = GalleryUi.dp(c, 16);
        bp.bottomMargin = GalleryUi.dp(c, 8);
        view.addView(box, bp);

        FrameLayout body = new FrameLayout(c);
        view.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        suggestions = new ScrollView(c);
        suggestions.setClipToPadding(false);
        suggestionColumn = new LinearLayout(c);
        suggestionColumn.setOrientation(LinearLayout.VERTICAL);
        suggestionColumn.setPadding(GalleryUi.dp(c, 16), 0, GalleryUi.dp(c, 16), GalleryUi.dp(c, 120));
        suggestions.addView(suggestionColumn);
        body.addView(suggestions);

        results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        resultCount = GalleryUi.text(c, "", 13, GalleryUi.MUTED);
        resultCount.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 4), GalleryUi.dp(c, 18), GalleryUi.dp(c, 8));
        results.addView(resultCount);
        grid = new ThumbGrid(new ThumbGrid.Listener() {
            @Override
            public void onClick(int index) {
                remember(field.getText().toString());
                hideKeyboard();
                if (index >= 0 && index < found.size()) host.openResults(found, index, field.getText().toString().trim());
            }

            @Override
            public void onLongClick(int index) {
                onClick(index);
            }
        });
        RecyclerView rv = ThumbGrid.view(c, grid, 4);
        rv.setPadding(GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8), GalleryUi.dp(c, 120));
        results.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(results);
        results.setVisibility(View.GONE);

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void afterTextChanged(Editable s) {
                run();
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                remember(field.getText().toString());
                hideKeyboard();
                return true;
            }
            return false;
        });
        run();
    }

    private int ocrVersion = -1;

    /** Photos were read by the text index: an open query runs again (its results may grow). */
    public void ocrChanged() {
        if (!field.getText().toString().trim().isEmpty() && view.isShown()) run();
    }

    /** The library changed (a delete, a new photo): the results read again. */
    public void refresh() {
        cache.clear();
        run();
    }

    /** The page is shown again: the suggestions (recent queries, albums) up to date. */
    public void onShown() {
        run();
    }

    private void run() {
        String q = field.getText().toString();
        boolean empty = q.trim().isEmpty();
        clear.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            results.setVisibility(View.GONE);
            suggestions.setVisibility(View.VISIBLE);
            renderSuggestions();
            return;
        }
        // P504: photos read since the last search add their text
        int ocr = com.particlesdevs.photoncamera.gallery.ocr.OcrIndex.version();
        if (ocr != ocrVersion) {
            cache.clear();
            ocrVersion = ocr;
        }
        found = GallerySearch.find(host.library(), q, host.favourites(), System.currentTimeMillis(), cache);
        int videos = 0;
        for (GalleryItem i : found) if (GalleryVideo.isVideoName(i.getFile().getDisplayName())) videos++;
        resultCount.setText(found.isEmpty() ? Lang.t("Ничего не найдено", "Nothing found")
                : Lang.t("Найдено: ", "Found: ") + GalleryUi.dayCount(found.size() - videos, videos));
        List<ThumbGrid.Cell> cells = new ArrayList<>();
        for (GalleryItem i : found) {
            boolean video = GalleryVideo.isVideoName(i.getFile().getDisplayName());
            cells.add(new ThumbGrid.Cell(i.getFile().getFileUri(), null, video));
        }
        grid.set(cells);
        suggestions.setVisibility(View.GONE);
        results.setVisibility(View.VISIBLE);
    }

    private void renderSuggestions() {
        Context c = view.getContext();
        suggestionColumn.removeAllViews();
        List<String> recent = recent();
        if (!recent.isEmpty()) {
            LinearLayout head = heading(c, Lang.t("Недавние запросы", "Recent searches"));
            ImageView bin = GalleryUi.icon(c, R.drawable.ui_ic_trash, GalleryUi.SECTION, 18);
            com.particlesdevs.photoncamera.ui.Icons.ink(bin, R.drawable.ui_ic_trash, GalleryUi.SECTION);
            int p = GalleryUi.dp(c, 10);
            bin.setPadding(p, p, p, p);
            bin.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 999)));
            bin.setContentDescription(Lang.t("Очистить историю", "Clear history"));
            bin.setOnClickListener(v -> {
                prefs.edit().remove(KEY_RECENT).apply();
                renderSuggestions();
            });
            head.addView(bin, new LinearLayout.LayoutParams(GalleryUi.dp(c, 38), GalleryUi.dp(c, 38)));
            suggestionColumn.addView(head);
            suggestionColumn.addView(chips(c, recent));
        }
        suggestionColumn.addView(heading(c, Lang.t("Быстрый поиск", "Quick search")));
        List<String> quick = new ArrayList<>(Arrays.asList(Lang.t("сегодня", "today"), Lang.t("видео", "video"), "raw", "heic",
                Lang.t("избранное", "favourite"), Lang.t("скриншот", "screenshot")));
        Calendar now = Calendar.getInstance();
        String[] months = {Lang.t("январь", "january"), Lang.t("февраль", "february"), Lang.t("март", "march"), Lang.t("апрель", "april"),
                Lang.t("май", "may"), Lang.t("июнь", "june"), Lang.t("июль", "july"), Lang.t("август", "august"), Lang.t("сентябрь", "september"),
                Lang.t("октябрь", "october"), Lang.t("ноябрь", "november"), Lang.t("декабрь", "december")};
        quick.add(1, months[now.get(Calendar.MONTH)] + " " + now.get(Calendar.YEAR));
        Set<Integer> years = new TreeSet<>((a, b) -> b - a);
        for (GalleryItem i : host.library()) if (i.getFile() != null && i.getFile().getLastModified() > 0)
            years.add(com.particlesdevs.photoncamera.gallery.model.GalleryView.yearOf(i.getFile().getLastModified()));
        for (Integer y : years) quick.add(String.valueOf(y));
        suggestionColumn.addView(chips(c, quick));
        List<AlbumsPage.Album> albums = host.albums();
        if (!albums.isEmpty()) {
            suggestionColumn.addView(heading(c, Lang.t("Альбомы", "Albums")));
            HorizontalScrollView scroll = new HorizontalScrollView(c);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (AlbumsPage.Album a : albums) {
                LinearLayout r = AlbumsPage.row(c, a.icon, a.title, String.valueOf(a.count()), v -> host.openAlbum(a.folder, a.title));
                r.setBackground(GalleryUi.pressable(GalleryUi.card(c, GalleryUi.CARD, 18))); // P579: a card of its own in the row
                Motion.press(r);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(GalleryUi.dp(c, 8));
                row.addView(r, lp);
            }
            scroll.addView(row);
            suggestionColumn.addView(scroll);
        }
    }

    private LinearLayout heading(Context c, String title) {
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 16), 0, GalleryUi.dp(c, 6));
        // P579: the settings' section caption (12sp semibold capitals, 55 % white)
        TextView t = GalleryUi.text(c, title.toUpperCase(Lang.ru() ? new java.util.Locale("ru") : java.util.Locale.ROOT), 12, GalleryUi.SECTION);
        t.setTypeface(GalleryUi.semibold());
        t.setLetterSpacing(.06f);
        t.setAccessibilityHeading(true);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return head;
    }

    /** Wrapping chips: a tap puts the text into the field. */
    private View chips(Context c, List<String> words) {
        FlowLayout flow = new FlowLayout(c, GalleryUi.dp(c, 8));
        List<View> made = new ArrayList<>();
        for (String w : words) {
            TextView chip = GalleryUi.text(c, w, 15, GalleryUi.TEXT);
            chip.setBackground(GalleryUi.pressable(GalleryUi.card(c, GalleryUi.CARD, 999))); // P579: a #2C2C2E capsule
            chip.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 9), GalleryUi.dp(c, 14), GalleryUi.dp(c, 9));
            chip.setClickable(true);
            chip.setOnClickListener(v -> {
                field.setText(w);
                field.setSelection(w.length());
                remember(w);
            });
            Motion.press(chip);
            flow.addView(chip);
            made.add(chip);
        }
        if (Motion.enabled(flow)) Motion.enter(made, 20, 12);
        return flow;
    }

    private List<String> recent() {
        String s = prefs.getString(KEY_RECENT, "");
        List<String> out = new ArrayList<>();
        for (String w : s.split("\n")) if (!w.trim().isEmpty()) out.add(w);
        return out;
    }

    private void remember(String q) {
        q = q.trim();
        if (q.isEmpty()) return;
        Set<String> s = new LinkedHashSet<>();
        s.add(q);
        s.addAll(recent());
        List<String> list = new ArrayList<>(s);
        prefs.edit().putString(KEY_RECENT, String.join("\n", list.subList(0, Math.min(RECENT_MAX, list.size())))).apply();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    /** The page left: the keyboard goes. */
    public void onHidden() {
        hideKeyboard();
    }

    /** A row layout that wraps its children onto new lines. */
    static final class FlowLayout extends ViewGroup {
        private final int gap;

        FlowLayout(Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); ++i) {
                View ch = getChildAt(i);
                measureChild(ch, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + ch.getMeasuredWidth() > width) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                }
                x += ch.getMeasuredWidth() + gap;
                line = Math.max(line, ch.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + line);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); ++i) {
                View ch = getChildAt(i);
                if (x > 0 && x + ch.getMeasuredWidth() > width) {
                    x = 0;
                    y += line + gap;
                    line = 0;
                }
                ch.layout(x, y, x + ch.getMeasuredWidth(), y + ch.getMeasuredHeight());
                x += ch.getMeasuredWidth() + gap;
                line = Math.max(line, ch.getMeasuredHeight());
            }
        }
    }
}
