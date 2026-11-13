package com.scamera.app.processing.opengl.postpipeline;

import androidx.annotation.Nullable;

import com.scamera.app.processing.color.ColourLook;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.settings.PreferenceKeys;

/**
 * The shot's look at its press (owner, 2026-10-28: «плёнка, LUT, теплота и оттенок по-прежнему читаются при обработке, а не в
 * момент нажатия … добавь в план»): the film simulation, the chosen LUT with its strength and the «Цвет» rows (warmth, tint and
 * the rest), as the viewfinder showed them when the shutter was pressed. The processing used to read them after the merge, ~5 s
 * later: a film, LUT or colour changed meanwhile (the tray) went into the photo taken before. Taken with LensFilter.Choice
 * (CaptureController), carried by the shot's saver to its processing (ScamProcessor -> PostPipeline.look); null there - the lab's
 * renders - reads the settings, under their overlay, as before.
 */
public final class ShotLook {
    /** The film simulation (FilmSim.current(): null without a stock or at strength 0). */
    @Nullable
    public final FilmSim.Settings film;
    /** The chosen LUT ("" none) and its strength. */
    public final String lut;
    public final float lutStrength;
    /** The «Цвет» rows (WhyTone.colourLook()). */
    public final ColourLook colour;
    public final boolean portraitEnabled, portraitSkinWarmth;
    public final boolean portraitSensorWindowUnknown;
    /** P614: «Why тон» V2 chosen at the press (PreferenceKeys.whyToneV2). */
    public final boolean toneV2;
    /**
     * P630: the «Oldstyle» mode at the press - its tone version, settings and style (null: another mode, the
     * «Why тон» of {@link #toneV2}).
     */
    @Nullable
    public final Oldstyle oldstyle;

    /** P630: what an «Oldstyle» shot takes of the mode at its press. */
    public static final class Oldstyle {
        public final boolean v2, postview;
        public final com.scamera.app.processing.oldstyle.OldstyleAe.Params params;
        public final com.scamera.app.processing.oldstyle.OldstyleStyle style;
        public Oldstyle(boolean v2, boolean postview, com.scamera.app.processing.oldstyle.OldstyleAe.Params params,
                        com.scamera.app.processing.oldstyle.OldstyleStyle style) {
            this.v2 = v2;
            this.postview = postview;
            this.params = params;
            this.style = style;
            // P630e: the style's exposure is the tone's (before it, both TETs), the rest of the style after it
            params.exposureEv = style.exposureEv();
        }

        /** The mode's state now, null outside the mode. */
        @Nullable
        public static Oldstyle now() {
            if (!com.scamera.app.settings.OldstyleMode.on()) return null;
            final boolean v2 = com.scamera.app.settings.OldstyleMode.v2();
            return new Oldstyle(v2, com.scamera.app.settings.OldstyleMode.v2Postview(),
                    com.scamera.app.settings.OldstyleMode.params(v2),
                    com.scamera.app.settings.OldstyleMode.style());
        }

        @Override public String toString() {
            return "Oldstyle " + (v2 ? "V2" : "V1") + " " + style;
        }
    }

    ShotLook(@Nullable FilmSim.Settings film, String lut, float lutStrength, ColourLook colour) {
        this.film = film;
        this.lut = lut;
        this.lutStrength = lutStrength;
        this.colour = colour;
        portraitEnabled = PreferenceKeys.isPortraitEnabled();
        portraitSkinWarmth = PreferenceKeys.isPortraitSkinWarmthEnabled();
        toneV2 = PreferenceKeys.whyToneV2();
        oldstyle = Oldstyle.now();
        portraitSensorWindowUnknown = portraitEnabled && (
                com.scamera.app.capture.IszCapturePolicy.mosaicModule(com.scamera.app.settings.ModuleRegistry.active())
                        || com.scamera.app.capture.SensorModePick.session() != null
                        || com.scamera.app.capture.XiaomiTeleZoom.isz());
    }

    /** The look the settings give now (the press). */
    public static ShotLook now() {
        final float strength = PreferenceKeys.filmLutStrength();
        return new ShotLook(FilmSim.current(), strength > 0f ? PreferenceKeys.filmLut() : "", strength, WhyTone.colourLook());
    }

    @Override
    public String toString() {
        // P380: the whole recipe (a diffusion or the couplers changed meanwhile shows too)
        return "film " + (film == null ? "none" : film.recipe()) + ", LUT " + (lut.isEmpty() ? "none" : lut + " " + lutStrength)
                + (oldstyle != null ? ", " + oldstyle : "");
    }
}
