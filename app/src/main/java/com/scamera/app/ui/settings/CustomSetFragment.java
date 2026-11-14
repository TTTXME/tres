package com.scamera.app.ui.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.R;
import com.scamera.app.settings.CustomSet;
import com.scamera.app.settings.SettingsLevel;
import com.scamera.app.ui.Motion;
import com.scamera.app.ui.controls.IosSwitch;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * P708 (owner, 2026-11-14: «добавь третий вариант кастомный, пользователь сам выберет что ему нужно, а что нет, оформи это красиво, с
 * анимациями и в стиле нашей камеры»): «Свой набор» - which settings the Custom mode shows. The settings in the groups of the
 * settings' own pages (a page of its own each; the main page's sections for their loose rows), a switch per setting and a three-state
 * mark per group (all / some / none: a tap shows all, or hides all when all are shown), a search, «Показать все» / «Только главное»
 * and a live count «Показано N из M». Every change is stored at once (CustomSet, as the user's changes against the levels: a setting
 * of a later version follows its level). Values never change.
 * <p>
 * Motion (Animators: the system's scale applies; transforms and alpha only): a group opens with its rows rising one after another
 * and closes with them fading (the settings' item animator), its chevron turns; the switches slide; the group's mark fills and its
 * dash morphs into a tick and back with a pop; the count ticks to its new number.
 */
public final class CustomSetFragment extends Fragment {
    static final String TAG = "custom_set";
    private static final Object CHANGED = new Object();

    /** One setting: its key, title and whether it is in the Simple set. */
    static final class Item {
        final String key, title;
        final boolean simple;
        Group group;

        Item(String key, String title, boolean simple) {
            this.key = key;
            this.title = title;
            this.simple = simple;
        }
    }

    /** A page (or a section of the main page) and its settings. */
    static final class Group {
        final String key, title, path;
        final List<Item> items = new ArrayList<>();

        Group(String key, String title, String path) {
            this.key = key;
            this.title = title;
            this.path = path;
        }

        List<String> keys() {
            final List<String> out = new ArrayList<>();
            for (Item i : items) out.add(i.key);
            return out;
        }

        List<Boolean> levels() {
            final List<Boolean> out = new ArrayList<>();
            for (Item i : items) out.add(i.simple);
            return out;
        }
    }

    // ───── the model (the settings' tree as this phone offers it)

    /**
     * The groups of {@code root}'s settings that {@code offered} keeps (rows hidden on this phone are left out): a page of its own
     * each, the main page's loose rows in its sections (or «Главная страница»); groups without a setting are dropped.
     */
    static List<Group> model(PreferenceGroup root, Predicate<Preference> offered) {
        final List<Group> out = new ArrayList<>();
        final Context c = root.getContext();
        final Group main = new Group(SettingsLevel.ROOT, Lang.t(c, "Главная страница", "Main page"), "");
        out.add(main);
        walk(root, main, "", offered, out);
        final List<Group> kept = new ArrayList<>();
        for (Group g : out) if (!g.items.isEmpty()) kept.add(g);
        return kept;
    }

    private static void walk(PreferenceGroup node, Group into, String path, Predicate<Preference> offered, List<Group> out) {
        for (int i = 0; i < node.getPreferenceCount(); i++) {
            final Preference p = node.getPreference(i);
            final String key = p.getKey();
            if (key != null && SettingsLevel.modeRow(key)) continue;
            if (!offered.test(p)) continue;
            final String title = p.getTitle() == null ? "" : p.getTitle().toString();
            if (SimpleMode.setting(p)) {
                if (!title.isEmpty()) {
                    final Item item = new Item(key, title, SimpleMode.simpleLevel(p));
                    item.group = into;
                    into.items.add(item);
                }
            } else if (p instanceof PreferenceScreen && key != null) {
                final Group g = new Group(key, title, path);
                out.add(g);
                walk((PreferenceGroup) p, g, path.isEmpty() ? title : path + " › " + title, offered, out);
            } else if (p instanceof PreferenceGroup) {
                // a section of the main page gathers its loose rows; deeper groups stay in their page's
                Group g = into;
                if (node.getParent() == null && key != null && !title.isEmpty()) {
                    g = new Group(key, title, path);
                    out.add(g);
                }
                walk((PreferenceGroup) p, g, g == into ? path : title, offered, out);
            }
        }
    }

    /**
     * The wizard's «Начать со всех» where the settings' tree was not built (the first run): every setting of {@code root} taken into
     * the set now. True when it did.
     */
    static boolean startAll(SharedPreferences prefs, @Nullable PreferenceGroup root) {
        final String start;
        try {
            start = prefs.getString(CustomSet.START_KEY, null);
        } catch (ClassCastException e) {
            return false;
        }
        if (root == null || !CustomSet.START_ALL.equals(start)) return false;
        final CustomSet set = SimpleMode.custom(prefs);
        set.showAll(SimpleMode.settings(root));
        SimpleMode.store(prefs, set);
        prefs.edit().remove(CustomSet.START_KEY).apply();
        return true;
    }

    // The model is handed over in memory, as the search's index (no saved state of a few hundred rows)
    private static volatile List<Group> sModel;

    static CustomSetFragment create(List<Group> model) {
        sModel = model;
        return new CustomSetFragment();
    }

    private List<Group> groups = new ArrayList<>();
    private SharedPreferences prefs;
    private CustomSet set;
    private final Set<String> open = new HashSet<>();
    /** The rows shown: a Group (its header) or an Item. */
    private final List<Object> rows = new ArrayList<>();
    private String query = "";
    private TextView counter;
    private int counted = -1;
    @Nullable private ValueAnimator tick;
    private RecyclerView list;
    private Adapter adapter;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        groups = sModel == null ? new ArrayList<>() : sModel;
        if (sModel == null) getParentFragmentManager().popBackStack();   // a process restored without the model
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        set = SimpleMode.custom(prefs);
        if (state != null) {
            query = state.getString("query", "");
            final ArrayList<String> o = state.getStringArrayList("open");
            if (o != null) open.addAll(o);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("query", query);
        state.putStringArrayList("open", new ArrayList<>(open));
    }

    private int dp(float v) {
        return SettingsStyle.dp(requireContext(), v);
    }

    @NonNull @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        final Context c = requireContext();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setTag(TAG);
        root.setPadding(dp(16), dp(16), dp(16), 0);
        root.setBackgroundColor(SettingsStyle.PAGE);
        final SettingsStyle.Header header = SettingsStyle.header(c, () -> requireActivity().getOnBackPressedDispatcher().onBackPressed(), null);
        header.heading.setText(Lang.t(c, "Свой набор", "Custom set"));
        header.subtitle.setVisibility(View.GONE);
        header.crumb.setText("");
        root.addView(header.view);
        counter = SettingsStyle.text(c, "", 13, SettingsStyle.SUMMARY);
        counter.setTag("custom_counter");
        counter.setPadding(dp(4), 0, dp(4), dp(10));
        root.addView(counter);
        // the search capsule (the settings search's look)
        final LinearLayout search = new LinearLayout(c);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(SettingsStyle.shape(c, SettingsStyle.ROW, 0, 22));
        search.setPadding(dp(12), 0, dp(12), 0);
        final ImageView glass = new ImageView(c);
        glass.setImageResource(R.drawable.cs_search);
        glass.setColorFilter(SettingsStyle.SECTION);
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        search.addView(glass, new LinearLayout.LayoutParams(dp(19), dp(19)));
        final EditText input = new EditText(c);
        input.setTag("custom_search");
        input.setSingleLine(true);
        input.setBackground(null);
        input.setTextColor(SettingsStyle.ROW_TITLE);
        input.setHintTextColor(SettingsStyle.SECTION);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setHint(Lang.t(c, "Найти настройку", "Find a setting"));
        input.setPadding(dp(8), 0, dp(4), 0);
        input.setText(query);
        search.addView(input, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(search, new LinearLayout.LayoutParams(-1, -2));
        // the two shortcuts
        final LinearLayout quick = new LinearLayout(c);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        final TextView all = SettingsStyle.quietCapsule(c, Lang.t(c, "Показать все", "Show all"), SettingsStyle.ROW_TITLE);
        all.setTag("custom_all");
        final TextView essentials = SettingsStyle.quietCapsule(c, Lang.t(c, "Только главное", "Only essentials"),
                SettingsStyle.accent(c));
        essentials.setTag("custom_essentials");
        for (TextView b : new TextView[]{all, essentials}) {
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            b.setMinHeight(dp(40));
            Motion.press(b);
        }
        all.setOnClickListener(v -> showAll());
        essentials.setOnClickListener(v -> essentials());
        final LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, -2, 1);
        ap.setMarginEnd(dp(8));
        quick.addView(all, ap);
        quick.addView(essentials, new LinearLayout.LayoutParams(0, -2, 1));
        final LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.topMargin = dp(10);
        qp.bottomMargin = dp(6);
        root.addView(quick, qp);
        list = new RecyclerView(c);
        list.setLayoutManager(new LinearLayoutManager(c));
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(28));
        list.setVerticalScrollBarEnabled(false);
        list.setItemAnimator(new SettingsMotion.ItemAnimator(c));
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull android.graphics.Rect out, @NonNull View v, @NonNull RecyclerView rv,
                                                 @NonNull RecyclerView.State s) {
                final int pos = rv.getChildAdapterPosition(v);
                out.top = pos > 0 && pos < rows.size() && rows.get(pos) instanceof Group ? dp(10) : 0;
            }
        });
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        rebuild();
        count(false);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {
                query = s.toString();
                rebuild();
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        return root;
    }

    @Override public void onDestroyView() {
        if (tick != null) tick.cancel();
        counter = null;
        list = null;
        super.onDestroyView();
    }

    // ───── what is shown

    private static String normal(String s) {
        return s.toLowerCase(Locale.ROOT).replace('\u0451', '\u0435');   // yo as ye, as the settings search
    }

    /** The rows for the query (a group whose title matches keeps all its settings; matches open their groups). */
    private void rebuild() {
        rows.clear();
        final String q = normal(query.trim());
        for (Group g : groups) {
            if (q.isEmpty()) {
                rows.add(g);
                if (open.contains(g.key)) rows.addAll(g.items);
                continue;
            }
            final boolean whole = normal(g.title).contains(q);
            final List<Item> hits = new ArrayList<>();
            for (Item i : g.items) if (whole || normal(i.title).contains(q)) hits.add(i);
            if (hits.isEmpty()) continue;
            rows.add(g);
            rows.addAll(hits);
        }
    }

    /** How many settings are shown, of all. */
    int[] shown() {
        int n = 0, all = 0;
        for (Group g : groups) {
            n += set.count(g.keys(), g.levels());
            all += g.items.size();
        }
        return new int[]{n, all};
    }

    /** «Показано N из M», ticking from the number before ({@code animate}). */
    private void count(boolean animate) {
        if (counter == null) return;
        final int[] n = shown();
        if (tick != null) tick.cancel();
        if (!animate || counted < 0 || counted == n[0] || !SettingsMotion.enabled(counter)) {
            counted = n[0];
            counter.setText(countText(n[0], n[1]));
            return;
        }
        final int from = counted;
        tick = ValueAnimator.ofInt(from, n[0]);
        tick.setDuration(Math.min(420, 160 + 12L * Math.abs(n[0] - from)));
        tick.setInterpolator(SettingsMotion.EASE_OUT);
        tick.addUpdateListener(a -> {
            counted = (int) a.getAnimatedValue();
            if (counter != null) counter.setText(countText(counted, n[1]));
        });
        tick.start();
        Motion.pop(counter);
    }

    private String countText(int n, int all) {
        return Lang.t(requireContext(), "Показано " + n + " из " + all, "Shown " + n + " of " + all);
    }

    private void stored() {
        SimpleMode.store(prefs, set);
        count(true);
    }

    /** One setting shown or hidden. */
    void setItem(Item item, boolean on) {
        set.set(item.key, item.simple, on);
        stored();
        changed(item.group, false);
    }

    /** A group's mark tapped: all of it shown, or none when all were. */
    void toggleGroup(Group g) {
        set.toggle(g.keys(), g.levels());
        stored();
        changed(g, true);
    }

    /** «Показать все». */
    void showAll() {
        final List<String> keys = new ArrayList<>();
        for (Group g : groups) keys.addAll(g.keys());
        set.showAll(keys);
        stored();
        if (adapter != null) adapter.notifyItemRangeChanged(0, rows.size(), CHANGED);
    }

    /** «Только главное»: the Simple set. */
    void essentials() {
        set.essentials();
        stored();
        if (adapter != null) adapter.notifyItemRangeChanged(0, rows.size(), CHANGED);
    }

    /** A group opened or closed (its rows rise in or fade out). */
    void toggleOpen(Group g) {
        if (!query.trim().isEmpty() || adapter == null) return;
        final int at = rows.indexOf(g);
        if (at < 0) return;
        if (open.remove(g.key)) {
            rows.subList(at + 1, at + 1 + g.items.size()).clear();
            adapter.notifyItemRangeRemoved(at + 1, g.items.size());
        } else {
            open.add(g.key);
            rows.addAll(at + 1, g.items);
            adapter.notifyItemRangeInserted(at + 1, g.items.size());
        }
        adapter.notifyItemChanged(at, CHANGED);
        if (open.contains(g.key) && list != null) {
            final int last = at + g.items.size();
            list.postDelayed(() -> { if (list != null) SettingsMotion.revealSection(list, at, last); }, 260);
        }
    }

    /** The rows of {@code g} (its header, and with {@code items} its settings) bound again in place. */
    private void changed(Group g, boolean items) {
        if (adapter == null) return;
        final int at = rows.indexOf(g);
        if (at >= 0) adapter.notifyItemChanged(at, CHANGED);
        if (items) for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Item && ((Item) rows.get(i)).group == g)
            adapter.notifyItemChanged(i, CHANGED);
    }

    List<Group> groups() {
        return groups;
    }

    CustomSet set() {
        return set;
    }

    // ───── the list

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int position) {
            return rows.get(position) instanceof Group ? 0 : 1;
        }

        @Override public int getItemCount() {
            return rows.size();
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            final Context c = parent.getContext();
            final LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            final LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = SettingsStyle.text(c, "", type == 0 ? 16 : 15, SettingsStyle.ROW_TITLE);
            com.scamera.app.ui.style.Fonts.apply(title, type == 0 ? com.scamera.app.ui.style.Fonts.SEMIBOLD
                    : com.scamera.app.ui.style.Fonts.REGULAR);
            final TextView line = SettingsStyle.text(c, "", 13, SettingsStyle.SUMMARY);
            line.setPadding(0, dp(2), 0, 0);
            texts.addView(title);
            texts.addView(line);
            if (type == 0) {
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                row.setMinimumHeight(dp(60));
                final ImageView chevron = new ImageView(c);
                chevron.setImageResource(R.drawable.cs_chevron);
                chevron.setColorFilter(SettingsStyle.CHEVRON);
                chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                final LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(9), dp(15));
                cp.setMarginEnd(dp(14));
                row.addView(chevron, cp);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                final TriCheck check = new TriCheck(c);
                final LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(dp(44), dp(44));
                row.addView(check, kp);
                return new Header(row, chevron, title, line, check);
            }
            row.setPadding(dp(16), dp(8), dp(12), dp(8));
            row.setMinimumHeight(dp(52));
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            final IosSwitch toggle = new IosSwitch(c);
            row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
            return new Leaf(row, title, line, toggle);
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            bind(h, position, false);
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position, @NonNull List<Object> payloads) {
            bind(h, position, !payloads.isEmpty());
        }

        private void bind(RecyclerView.ViewHolder h, int position, boolean animate) {
            final Context c = h.itemView.getContext();
            final Object o = rows.get(position);
            final float r = dp(18);
            if (h instanceof Header && o instanceof Group) {
                final Group g = (Group) o;
                final Header v = (Header) h;
                final boolean expanded = open.contains(g.key) || !query.trim().isEmpty();
                v.title.setText(g.title);
                final int n = set.count(g.keys(), g.levels());
                v.line.setText((g.path.isEmpty() ? "" : g.path + " · ") + Lang.t(c, n + " из " + g.items.size(),
                        n + " of " + g.items.size()));
                v.itemView.setBackground(corners(c, r, expanded ? 0 : r));
                v.itemView.setTag("custom_group_" + g.key);
                v.check.setTag("custom_group_check_" + g.key);
                v.check.setState(set.state(g.keys(), g.levels()), animate);
                v.check.setContentDescription(g.title);
                v.check.setOnClickListener(x -> toggleGroup(g));
                v.itemView.setOnClickListener(x -> toggleOpen(g));
                if (animate) SettingsMotion.turn(v.chevron, expanded ? 90 : 0);
                else v.chevron.setRotation(expanded ? 90 : 0);
            } else if (h instanceof Leaf && o instanceof Item) {
                final Item item = (Item) o;
                final Leaf v = (Leaf) h;
                v.title.setText(item.title);
                v.line.setVisibility(item.simple ? View.VISIBLE : View.GONE);
                v.line.setText(Lang.t(c, "Главная", "Essential"));
                v.line.setTextColor(SettingsStyle.accent(c));
                final int i = rows.indexOf(item);
                final boolean last = i + 1 >= rows.size() || !(rows.get(i + 1) instanceof Item);
                v.itemView.setBackground(corners(c, 0, last ? r : 0));
                v.toggle.setTag("custom_leaf_" + item.key);
                v.toggle.setOnCheckedChangeListener(null);
                final boolean on = set.visible(item.key, item.simple);
                if (animate) v.toggle.setChecked(on);
                else v.toggle.setCheckedNow(on);
                v.toggle.setContentDescription(item.title);
                v.toggle.setOnCheckedChangeListener((s, checked) -> setItem(item, checked));
                v.itemView.setOnClickListener(x -> v.toggle.toggle());
            }
        }
    }

    /** A card piece: the row colour with {@code top} / {@code bottom} corner radii. */
    private static GradientDrawable corners(Context c, float top, float bottom) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(SettingsStyle.ROW);
        d.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        return d;
    }

    private static final class Header extends RecyclerView.ViewHolder {
        final ImageView chevron;
        final TextView title, line;
        final TriCheck check;

        Header(View v, ImageView chevron, TextView title, TextView line, TriCheck check) {
            super(v);
            this.chevron = chevron;
            this.title = title;
            this.line = line;
            this.check = check;
        }
    }

    private static final class Leaf extends RecyclerView.ViewHolder {
        final TextView title, line;
        final IosSwitch toggle;

        Leaf(View v, TextView title, TextView line, IosSwitch toggle) {
            super(v);
            this.title = title;
            this.line = line;
            this.toggle = toggle;
        }
    }

    /**
     * A group's mark: none (a ring), some (an accent square with a dash), all (with a tick). Between them the square fills and its
     * dash bends into the tick on a spring, with a pop.
     */
    static final class TriCheck extends View {
        private static final SettingsMotion.Spring SPRING = new SettingsMotion.Spring(0.34f, 0.74f);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mark = new Path();
        private final RectF box = new RectF();
        private final int accent;
        private int state = -1;
        private float fill, tick;
        @Nullable private ValueAnimator anim;

        TriCheck(Context c) {
            super(c);
            accent = SettingsStyle.accent(c);
            setClickable(true);
            setFocusable(true);
        }

        int state() {
            return state;
        }

        void setState(int s, boolean animate) {
            if (s == state) return;
            final boolean first = state < 0;
            state = s;
            final float f1 = s == CustomSet.NONE ? 0f : 1f, t1 = s == CustomSet.ALL ? 1f : 0f;
            if (anim != null) anim.cancel();
            if (first || !animate || !SettingsMotion.enabled(this)) {
                fill = f1;
                tick = t1;
                invalidate();
                return;
            }
            final float f0 = fill, t0 = tick;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(SPRING.durationMs);
            anim.setInterpolator(SPRING);
            anim.addUpdateListener(a -> {
                final float k = (float) a.getAnimatedValue();
                fill = Math.max(0f, Math.min(1f, f0 + (f1 - f0) * k));
                tick = t0 + (t1 - t0) * k;
                invalidate();
            });
            anim.start();
            Motion.pop(this);
        }

        @Override protected void onDraw(Canvas canvas) {
            final float d = getResources().getDisplayMetrics().density, side = 22 * d;
            final float l = (getWidth() - side) / 2f, t = (getHeight() - side) / 2f;
            box.set(l, t, l + side, t + side);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * d);
            paint.setColor(SettingsMotion.mix(0x66FFFFFF, accent, fill));
            canvas.drawRoundRect(box, 7 * d, 7 * d, paint);
            if (fill <= 0f) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            paint.setAlpha(Math.round(255 * fill));
            canvas.drawRoundRect(box, 7 * d, 7 * d, paint);
            // the dash (0) bends into the tick (1): three points between their two places
            final float k = Math.max(-.2f, Math.min(1.2f, tick));
            final float[] dash = {.28f, .5f, .5f, .5f, .72f, .5f}, check = {.27f, .52f, .44f, .68f, .75f, .35f};
            mark.reset();
            for (int i = 0; i < 3; i++) {
                final float x = l + side * (dash[2 * i] + (check[2 * i] - dash[2 * i]) * k);
                final float y = t + side * (dash[2 * i + 1] + (check[2 * i + 1] - dash[2 * i + 1]) * k);
                if (i == 0) mark.moveTo(x, y);
                else mark.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(SettingsStyle.INK);
            paint.setAlpha(Math.round(255 * fill));
            canvas.drawPath(mark, paint);
        }

        @Override public CharSequence getAccessibilityClassName() {
            return android.widget.CheckBox.class.getName();
        }

        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setCheckable(true);
            info.setChecked(state == CustomSet.ALL);
        }
    }
}
