package com.particlesdevs.photoncamera.processing.watermark;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiFonts;

/**
 * P628: the watermark's fonts - «SCAM» is the app's own Roboto Flex (bundled, UiFonts: the phone's system font never changes it),
 * the others the system's condensed sans, serif and monospace at the nearest weight.
 */
public final class WatermarkFonts implements WatermarkPainter.Fonts {
    @Nullable private final Context context;

    public WatermarkFonts(@Nullable Context context) {
        this.context = context;
    }

    @Override
    public Typeface of(WatermarkSpec.Font font, int weight) {
        switch (font) {
            case CONDENSED: return weighted(Typeface.create("sans-serif-condensed", Typeface.NORMAL), weight);
            case SERIF: return weighted(Typeface.SERIF, weight);
            case MONO: return weighted(Typeface.MONOSPACE, weight);
            default:
                if (context != null) {
                    try {
                        return UiFonts.of(context, weight);
                    } catch (RuntimeException ignored) {
                        // no resources (a bare process): the system sans below
                    }
                }
                return weighted(Typeface.SANS_SERIF, weight);
        }
    }

    private static Typeface weighted(Typeface family, int weight) {
        if (family == null) family = Typeface.DEFAULT;
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(family, weight, false);
        return Typeface.create(family, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }
}
