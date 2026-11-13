package com.scamera.app.processing.opengl.postpipeline;

import com.scamera.app.settings.PreferenceKeys;

import java.util.Locale;

/**
 * P648 «Подстройка под свет (SNR)» (owner, 2026-11-12: «by day better detail, at night less noise, detail not much worse»; 2026-11-13:
 * «Ночью зерно можно сгладить, но сгладить так, чтобы деталь не была потеряна», «Главное чтобы ночью фото не выглядело пластиковым»):
 * the Resolve's night smoothing of the flat areas. The one measure is the shot's SNR the post pipeline already has -
 * {@link DenoiseStats#finishSnrEnd} (Parameters.finishSnr: the merged RGB's measured noise at the display mid grey, the merge's frame
 * count and the AE's gain included; WhyLumaSharpen's low-light share and WhyCombine's detail boost read the same number) - and the
 * luma noise those statistics measured (Parameters.finishNoiseS / O).
 * <p>
 * {@link #night}: 0 at {@link #DAY} and above (and when the SNR is unknown), 1 at {@link #NIGHT} and below, a smoothstep in log SNR
 * between. On the phones' logs a daylight shot reads 260 - 520, ISO ~1000 indoors 26 - 97, the night shots 9 - 50.
 * <p>
 * scamdn/why_resolve (flatNightU = FLAT x night): pass 0 measures r, the merged luma's 3 x 3 variance over the measured noise, pass 1
 * averages r over ~ 7 x 7 pixels (flat 1 below 0.5, 0 from 0.8: only noise there), pass 2 takes min(own, its neighbourhood's); on a flat
 * pixel the frames merged on (N >= 3 and >= 0.95 of the median for all of it) the still areas' revert («Сохранение сырой резкости
 * деталей») gives way to the denoised luma and the factor («Шумоподавление Resolve») widens up to {@link #FLAT_BOOST}x; an even grain
 * ({@link #FLAT_GRAIN} x the pixel's sigma, by the flat share) is given back there. Texture, edges, a rejected moving subject and
 * every daylight shot keep the Resolve as it was; with the switch off every shot is bit for bit as before P648.
 * <p>
 * The limits it was tuned to (PLAN.md P648; every simulated night level, Bayer / Quad / Tetra / a moving subject, after / before): the
 * 1 - 2 px band's transfer >= 0.95 (worst 0.955), the colour transitions >= 0.95 (worst 0.984), the flat fine grain >= 0.6 (worst 0.84:
 * no wax), the 8 - 32 px noise / the fine grain <= 1.3 (worst 1.16: no blotches), no change beside strong edges above the flat areas'.
 */
final class PostSnr {
    private PostSnr() {}

    /** The SNR from which the shot is daylight (no change) and the one at and below which the night's full share applies. */
    static final float DAY = 30f, NIGHT = 6f;
    /** The flat smoothing's night share, the factor on a flat pixel and the even grain given back there (why_resolve uniforms). */
    static final float FLAT = 1f, FLAT_BOOST = 3f, FLAT_GRAIN = 1f;
    /**
     * P666 (owner, 2026-11-13: «проверь работу нашего шумодава, он сильно мылит тени»): the shadows' texture is not called flat. In the
     * shadows - the local SNR of the merged luma (its 3 x 3 mean over the measured sigma there, the frames merged included) under
     * {@link #shadowBand}'s upper end, all of it under its lower end - the flat test also reads the coarse variance (nine 3 x 3 means
     * of a 9 x 9 window) over the noise, / {@link #SHADOW_COARSE} (its share of pure noise): bark, foliage, brick or fabric under the
     * pixel noise stands out of it there and keeps its texture, a flat shadow is smoothed as before. Above the shadows the test is
     * P648c's. The band follows the shot: SHADOW_REL_LO / HI x its SNR, at most SHADOW_LO / HI (a very dark shot's lit parts sit at a
     * local SNR of 2 - 3 and stay P648c's).
     */
    static final float SHADOW_LO = 2f, SHADOW_HI = 6f, SHADOW_REL_LO = 0.2f, SHADOW_REL_HI = 0.5f, SHADOW_COARSE = 0.2f;

    /** P666: the shadows' local-SNR band {lo, hi} of a shot of SNR {@code snr} (0, 0 for an unknown SNR: no shadow test). */
    static float[] shadowBand(float snr) {
        if (!(snr > 0f) || Float.isInfinite(snr)) return new float[]{0f, 0f};
        return new float[]{Math.min(SHADOW_LO, SHADOW_REL_LO * snr), Math.min(SHADOW_HI, SHADOW_REL_HI * snr)};
    }

    /** P666's shadow test (scam_dev.txt "hybrid_post_snr_shadow 0": P648c's test alone, as before). */
    static boolean shadowTexture() {
        return PreferenceKeys.hybridValue("post_snr_shadow", 1f) > 0f;
    }

    /** The switch (pref_scam_hybrid_post_snr, on by default; scam_dev.txt "hybrid_post_snr 0" too). */
    static boolean on() {
        return PreferenceKeys.hybridSwitch("post_snr", true);
    }

    /** 0 at {@link #DAY} and above and for an unknown SNR, 1 at {@link #NIGHT} and below, a smoothstep of log SNR between. */
    static float night(float snr) {
        if (!(snr > 0f) || Float.isInfinite(snr) || snr >= DAY) return 0f;
        if (snr <= NIGHT) return 1f;
        final double t = Math.log(DAY / snr) / Math.log(DAY / NIGHT);
        return (float) (t * t * (3.0 - 2.0 * t));
    }

    /**
     * The flat-area smoothing's share for this shot: FLAT x night; 0 off, by day and without the measured noise (the flat test reads
     * the finish statistics' luma noise, Parameters.finishNoiseS / O).
     */
    static float flatNight(float snr, boolean on, float noiseS, float noiseO) {
        if (!on || !(noiseS >= 0f) || !(noiseO >= 0f) || !(noiseS + noiseO > 0f)) return 0f;
        return FLAT * night(snr);
    }

    /** The shot's line of the log (once per shot, ScamHdrDenoise). */
    static String describe(float snr, boolean on) {
        if (!on) return "post SNR (P648): off";
        final float n = night(snr);
        return String.format(Locale.ROOT, "post SNR (P648): snr=%.2f night=%.3f flat %.3f (factor up to x%.1f, grain %.1f)",
                snr, n, FLAT * n, FLAT_BOOST, FLAT_GRAIN);
    }
}
