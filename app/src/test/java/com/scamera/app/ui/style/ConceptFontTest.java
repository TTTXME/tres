package com.scamera.app.ui.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.UiFonts;
import com.scamera.app.ui.SplashActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P582: the concept's Roboto Flex is the font of every theme of the app (activities, dialogs), of the Material 3 type scale
 * that views set in code, and of layouts that still name a system family (ConceptViewInflater).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w390dp-h844dp-xxhdpi")
public class ConceptFontTest {
    /** Every activity theme of the manifest, the dialogs' overlay and the framework dialogs' fallback. */
    private static final int[] THEMES = {R.style.Theme_Scamera_BaseApp, R.style.Theme_Scamera_Splash,
            R.style.Theme_Scamera_SettingsActivity, R.style.AlertDialogTheme, R.style.AlertDialogTheme_Framework};
    private static final int[] TYPE_SCALE = {
            com.google.android.material.R.attr.textAppearanceDisplayLarge, com.google.android.material.R.attr.textAppearanceDisplayMedium,
            com.google.android.material.R.attr.textAppearanceDisplaySmall, com.google.android.material.R.attr.textAppearanceHeadlineLarge,
            com.google.android.material.R.attr.textAppearanceHeadlineMedium, com.google.android.material.R.attr.textAppearanceHeadlineSmall,
            com.google.android.material.R.attr.textAppearanceTitleLarge, com.google.android.material.R.attr.textAppearanceTitleMedium,
            com.google.android.material.R.attr.textAppearanceTitleSmall, com.google.android.material.R.attr.textAppearanceBodyLarge,
            com.google.android.material.R.attr.textAppearanceBodyMedium, com.google.android.material.R.attr.textAppearanceBodySmall,
            com.google.android.material.R.attr.textAppearanceLabelLarge, com.google.android.material.R.attr.textAppearanceLabelMedium,
            com.google.android.material.R.attr.textAppearanceLabelSmall};

    private static Context themed(int theme) {
        Context app = RuntimeEnvironment.getApplication();
        // the dialogs' themes are overlays over the activity's
        if (theme == R.style.AlertDialogTheme || theme == R.style.AlertDialogTheme_Framework) {
            app = new ContextThemeWrapper(app, R.style.Theme_Scamera_BaseApp);
        }
        return new ContextThemeWrapper(app, theme);
    }

    private static int fontRes(Context c, int attr) {
        TypedArray a = c.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getResourceId(0, 0);
        } finally {
            a.recycle();
        }
    }

    @Test public void everyThemeNamesRobotoFlex() {
        for (int theme : THEMES) {
            Context c = themed(theme);
            String name = c.getResources().getResourceEntryName(theme);
            assertEquals(name + ": android:fontFamily", R.font.roboto_flex, fontRes(c, android.R.attr.fontFamily));
            if (theme != R.style.AlertDialogTheme_Framework) {
                assertEquals(name + ": fontFamily", R.font.roboto_flex, fontRes(c, androidx.appcompat.R.attr.fontFamily));
            }
        }
    }

    @Test public void aTextViewOfEachThemeDrawsRobotoFlex() {
        for (int theme : THEMES) {
            Context c = themed(theme);
            String name = c.getResources().getResourceEntryName(theme);
            Typeface expected = c.getResources().getFont(R.font.roboto_flex);
            assertNotNull(expected);
            assertEquals(name + ": a plain TextView", expected, new TextView(c).getTypeface());
        }
    }

    @Test public void theMaterialTypeScaleIsRobotoFlex() {
        Context c = themed(R.style.Theme_Scamera_BaseApp);
        for (int attr : TYPE_SCALE) {
            int appearance = fontRes(c, attr);
            String name = c.getResources().getResourceEntryName(attr);
            assertTrue(name + " is ours", c.getResources().getResourceEntryName(appearance).startsWith("TextAppearance.Scamera."));
            TypedArray a = c.obtainStyledAttributes(appearance, new int[]{android.R.attr.fontFamily});
            int font = a.getResourceId(0, 0);
            a.recycle();
            assertTrue(name + ": " + font, font == R.font.roboto_flex || font == R.font.roboto_flex_500);
        }
        TypedArray a = c.obtainStyledAttributes(fontRes(c, com.google.android.material.R.attr.textAppearanceLabelLarge),
                new int[]{android.R.attr.fontFamily});
        assertEquals("labels (buttons, chips) at 500 as Material's medium", R.font.roboto_flex_500, a.getResourceId(0, 0));
        a.recycle();
    }

    @Test public void aLayoutNamingSansSerifMediumGetsRobotoFlex500() {
        Activity a = Robolectric.buildActivity(SplashActivity.class).setup().get();
        assertTrue(a instanceof AppCompatActivity);
        ViewGroup root = new FrameLayout(a);
        // preference_tile.xml: a TextView with android:fontFamily="sans-serif-medium"
        View tile = LayoutInflater.from(a).inflate(R.layout.preference_tile, root, false);
        TextView named = findNamed(tile);
        assertNotNull("the layout's sans-serif-medium TextView", named);
        assertSame(Fonts.of(a, Fonts.MEDIUM), named.getTypeface());
    }

    @Test public void systemFamiliesMapToTheConceptsWeights() {
        assertEquals(UiFonts.MEDIUM, UiFonts.weightOf("sans-serif-medium", Typeface.NORMAL));
        assertEquals(UiFonts.BOLD, UiFonts.weightOf("sans-serif-medium", Typeface.BOLD));
        assertEquals(UiFonts.REGULAR, UiFonts.weightOf("sans-serif", Typeface.NORMAL));
        assertEquals(UiFonts.BOLD, UiFonts.weightOf(null, Typeface.BOLD));
        assertEquals(UiFonts.REGULAR, UiFonts.weightOf("sans-serif-condensed", Typeface.NORMAL));
        assertEquals(UiFonts.HEAVY, UiFonts.weightOf("sans-serif-black", Typeface.NORMAL));
        assertEquals("monospace stays", -1, UiFonts.weightOf("monospace", Typeface.NORMAL));
        Context c = RuntimeEnvironment.getApplication();
        assertSame(Fonts.of(c, 500), Fonts.forFamily(c, "sans-serif-medium", Typeface.NORMAL));
        assertSame("one helper", UiFonts.of(c, 700), Fonts.of(c, Fonts.BOLD));
    }

    private static TextView findNamed(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findNamed(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }
}
