package com.particlesdevs.photoncamera.ui.settings;

import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.style.Fonts;

/**
 * P584 (owner, 2026-11-09: «шрифты так же не совпадают с концептом»): the settings and the gallery in the concept's font. Every
 * text of a view tree takes Roboto Flex at the weight it was given (the system's sans-serif-medium 500, a semibold 600,
 * DEFAULT_BOLD 700 ...), and its size in dp where it was in sp (the concept's px are dp: the phone's font scale does not change
 * the interface). {@link #follow} keeps a window's tree so: rows bound later, sheets, menus, a recycled view.
 */
public final class ConceptFonts {
    private ConceptFonts() {}

    /** The tree under {@code v} in Roboto Flex now. */
    public static void apply(View v) {
        if (v instanceof TextView) one((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    /** {@link #apply} now and after every layout of {@code root}'s window (once per root). */
    public static void follow(View root) {
        if (root == null) return;
        apply(root);
        if (root.getTag(R.id.concept_fonts) != null) return;
        final ViewTreeObserver.OnGlobalLayoutListener l = () -> apply(root);
        root.setTag(R.id.concept_fonts, l);
        root.getViewTreeObserver().addOnGlobalLayoutListener(l);
    }

    /** The weight {@code t} was made at (400 when it says nothing). */
    static int weightOf(Typeface t) {
        if (t == null) return Fonts.REGULAR;
        if (android.os.Build.VERSION.SDK_INT >= 28) return Math.max(Fonts.REGULAR, Math.min(Fonts.HEAVY, t.getWeight()));
        return t.isBold() ? Fonts.BOLD : Fonts.REGULAR;
    }

    private static final int[] FACES = {R.font.roboto_flex_400, R.font.roboto_flex_500, R.font.roboto_flex_600, R.font.roboto_flex_700,
            R.font.roboto_flex_800};

    /**
     * Whether {@code t} is Roboto Flex already: a face of Fonts.of, or one a layout set (@font/roboto_flex_600: the cached face
     * of that resource). Those stay as they are (a static instance's own weight class need not say its weight).
     */
    static boolean flex(TextView v, Typeface t) {
        if (t == null) return false;
        for (int w = Fonts.REGULAR; w <= Fonts.HEAVY; w += 100) if (t == Fonts.of(v.getContext(), w)) return true;
        for (int id : FACES) {
            try {
                if (t == v.getResources().getFont(id) || t == androidx.core.content.res.ResourcesCompat.getFont(v.getContext(), id)) return true;
            } catch (RuntimeException ignored) { /* not loadable here */ }
        }
        return false;
    }

    private static java.util.Map<Typeface, Integer> system;

    /**
     * The system's own sans-serif faces (the ones code and old layouts set) and the weight each stands for; any other face (a
     * layout's @font/roboto_flex_*, monospace) is left as it is.
     */
    private static synchronized java.util.Map<Typeface, Integer> system() {
        if (system != null) return system;
        java.util.Map<Typeface, Integer> m = new java.util.IdentityHashMap<>();
        m.put(Typeface.DEFAULT, Fonts.REGULAR);
        m.put(Typeface.SANS_SERIF, Fonts.REGULAR);
        m.put(Typeface.DEFAULT_BOLD, Fonts.BOLD);
        String[][] families = {{"sans-serif", "400"}, {"sans-serif-light", "400"}, {"sans-serif-thin", "400"}, {"sans-serif-medium", "500"},
                {"sans-serif-condensed", "400"}, {"sans-serif-black", "800"}};
        for (String[] f : families) {
            m.put(Typeface.create(f[0], Typeface.NORMAL), Integer.parseInt(f[1]));
            m.put(Typeface.create(f[0], Typeface.BOLD), Fonts.BOLD);
        }
        m.put(Typeface.create(Typeface.DEFAULT, Typeface.BOLD), Fonts.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) for (int w = 100; w <= 900; w += 100) {
            int to = Math.max(Fonts.REGULAR, Math.min(Fonts.HEAVY, w));
            m.put(Typeface.create(Typeface.SANS_SERIF, w, false), to);
            m.put(Typeface.create(Typeface.DEFAULT, w, false), to);
            m.put(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), w, false), to);
        }
        return system = m;
    }

    private static void one(TextView v) {
        Typeface t = v.getTypeface();
        Integer w = t == null ? Integer.valueOf(Fonts.REGULAR) : system().get(t);
        if (w != null && !flex(v, t)) v.setTypeface(Fonts.of(v.getContext(), w));
        if (android.os.Build.VERSION.SDK_INT >= 30 && v.getTextSizeUnit() == TypedValue.COMPLEX_UNIT_SP) {
            DisplayMetrics m = v.getResources().getDisplayMetrics();
            float scale = v.getResources().getConfiguration().fontScale;
            v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, v.getTextSize() / (m.density * (scale > 0 ? scale : 1f)));
        }
    }
}
