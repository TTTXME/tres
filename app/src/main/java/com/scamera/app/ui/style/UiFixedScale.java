package com.scamera.app.ui.style;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;

import androidx.annotation.Nullable;

/**
 * P582 (owner, 2026-11-09: «Убедись что масштаб самого смартфона и то какой у него в системе шрифт не влияет на наше
 * приложение и у всех интерфейс выглядит ± как на концептах»): every screen of the app is laid out at the concept's scale,
 * whatever the phone's «Размер экрана / Display size» and «Размер шрифта / Font size» settings say.
 *
 * <p>The concepts (research/concept/*.dc.html) are drawn for a {@value #CONCEPT_SHORT_SIDE_DP}dp-wide phone. Every Activity
 * (and the Application, for the code that sizes things with the app context) wraps its base context with
 * {@link #wrap}: the configuration gets {@code densityDpi = floor(short side px * 160 / 390)} - the short side of the real
 * display panel in pixels, so the portrait width is always 390dp (390 .. 390.7) - and {@code fontScale = 1}, so an sp is a dp
 * and the system font size never changes our text (nor the Bold text setting: {@code fontWeightAdjustment = 0}). The window's dp sizes ({@code screenWidthDp}, {@code screenHeightDp},
 * {@code smallestScreenWidthDp}) are restated at the new density, so the sw/w resource qualifiers and code reading them
 * agree with the layout. Pixels are not touched: the camera preview, TextureView / SurfaceView and the GL viewfinder keep
 * the real panel's pixel sizes.
 *
 * <p>A large screen (the system's smallest width of {@value #LARGE_SCREEN_DP}dp or more: a tablet, an unfolded foldable)
 * keeps the system density - a 390dp phone stretched over it would be a toy - and only the font scale is fixed there.
 */
public final class UiFixedScale {
    private UiFixedScale() {}

    /** The concept's phone width in dp (CSS px of the concept boards). */
    public static final int CONCEPT_SHORT_SIDE_DP = 390;
    /** From this system smallest width on the screen is a tablet's: the density is left as the system has it. */
    public static final int LARGE_SCREEN_DP = 600;

    /** {@code base} with the concept's density and font scale; {@code base} itself when nothing needs to change. */
    public static Context wrap(Context base) {
        if (base == null) return null;
        Configuration o = override(base.getResources().getConfiguration(), realShortSidePx(base));
        if (o == null) return base;
        return base.createConfigurationContext(o);
    }

    /**
     * The override for a window whose current configuration is {@code sys} on a display whose short side is
     * {@code shortSidePx}: only the fields that change (density, font scale and the dp sizes); null when the configuration
     * already is the concept's.
     */
    @Nullable
    public static Configuration override(Configuration sys, int shortSidePx) {
        // new Configuration(): every field undefined except fontScale = 1, and createConfigurationContext applies only
        // the defined ones - so the delta is the font scale, plus the density below
        Configuration o = new Configuration();
        o.fontScale = 1f;
        boolean changed = sys.fontScale != 1f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && sys.fontWeightAdjustment != 0
                && sys.fontWeightAdjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) {
            // «Жирный шрифт» / Bold text adds to every weight: the concept's weights stay as drawn
            o.fontWeightAdjustment = 0;
            changed = true;
        }
        int sysDpi = sys.densityDpi;
        int dpi = densityDpi(shortSidePx);
        boolean large = sys.smallestScreenWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
                && sys.smallestScreenWidthDp >= LARGE_SCREEN_DP;
        if (dpi > 0 && sysDpi > 0 && !large && dpi != sysDpi) {
            o.densityDpi = dpi;
            o.screenWidthDp = rescale(sys.screenWidthDp, sysDpi, dpi);
            o.screenHeightDp = rescale(sys.screenHeightDp, sysDpi, dpi);
            o.smallestScreenWidthDp = rescale(sys.smallestScreenWidthDp, sysDpi, dpi);
            changed = true;
        }
        return changed ? o : null;
    }

    /** The density at which {@code shortSidePx} is {@value #CONCEPT_SHORT_SIDE_DP}dp (rounded down: never under 390dp). */
    public static int densityDpi(int shortSidePx) {
        if (shortSidePx <= 0) return 0;
        return (int) ((long) shortSidePx * DisplayMetrics.DENSITY_DEFAULT / CONCEPT_SHORT_SIDE_DP);
    }

    private static int rescale(int dp, int fromDpi, int toDpi) {
        if (dp <= 0) return dp; // undefined stays undefined
        // rounded down as the system rounds px / density (a 390.6dp window is 390dp)
        return (int) Math.floor(dp * (double) fromDpi / toDpi + 1e-3);
    }

    /**
     * The short side of the real display panel in pixels: the context's own display when it has one (an activity), else
     * the default display; the context's window metrics only when neither can be read. Pixels do not depend on the
     * density, so this is the same before and after {@link #wrap}.
     */
    public static int realShortSidePx(Context c) {
        Display d = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                d = c.getDisplay();
            } catch (RuntimeException ignored) {
                // a context with no display of its own (the Application): the default display below
            }
        }
        if (d == null) {
            try {
                DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            } catch (RuntimeException ignored) {
            }
        }
        if (d != null) {
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            int s = Math.min(m.widthPixels, m.heightPixels);
            if (s > 0) return s;
        }
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.min(m.widthPixels, m.heightPixels);
    }

}
