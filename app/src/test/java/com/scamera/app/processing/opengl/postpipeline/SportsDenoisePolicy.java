package com.scamera.app.processing.opengl.postpipeline;

/** Noise-driven compromise between sports detail retention and technical denoising. */
final class SportsDenoisePolicy {
    final float snr, lowSnr, sharpKeep;
    final String source;
    private final boolean sharpPriority;

    private SportsDenoisePolicy(boolean sports, boolean sharpPriority, float snr, String source) {
        this.snr = snr;
        this.source = source;
        this.sharpPriority = sharpPriority;
        float t = sports && Float.isFinite(snr) && snr >= 0f ? Math.max(0f, Math.min(1f, (24f - snr) / 16f)) : 0f;
        lowSnr = t * t * (3f - 2f * t);
        sharpKeep = sharpPriority ? 0.85f * (1f - lowSnr) : 0f;
    }

    static SportsDenoisePolicy of(boolean sports, boolean sharpPriority, float measuredSnr, float noiseS, float noiseO) {
        if (Float.isFinite(measuredSnr) && measuredSnr >= 0f)
            return new SportsDenoisePolicy(sports, sharpPriority, measuredSnr, "measured");
        if (Float.isFinite(noiseS) && Float.isFinite(noiseO) && noiseS >= 0f && noiseO >= 0f && (noiseS > 0f || noiseO > 0f)) {
            double variance = 0.18 * noiseS + noiseO;
            float snr = (float) (0.18 / Math.sqrt(variance));
            if (Float.isFinite(snr)) return new SportsDenoisePolicy(sports, sharpPriority, snr, "model@0.18");
        }
        return new SportsDenoisePolicy(sports, sharpPriority, Float.NaN, "unknown");
    }

    void apply(ScamDenoiseTables.Config c) {
        if (!(lowSnr > 0f)) return;
        c.lumaMult *= 1f + 0.3f * lowSnr;
        c.chromaMult *= 1f + 0.35f * lowSnr;
        c.revertMult *= 1f - 0.65f * lowSnr;
    }

    float residualGrain(float amount) {
        return lowSnr > 0f ? amount * (1f - 0.75f * lowSnr) : amount;
    }

    void applyMs(float[][] thresholds, float[] motion) {
        if (lowSnr > 0f) {
            for (float[] band : thresholds) {
                band[0] *= 1f + lowSnr;
                band[1] *= 1f + 0.35f * lowSnr;
            }
        }
        if (sharpPriority) motion[0] = lowSnr > 0f ? -0.75f + (motion[0] + 0.75f) * lowSnr : -0.75f;
    }

    @Override public String toString() {
        return source + ":snr=" + snr + ",low=" + lowSnr + ",sharp=" + sharpKeep;
    }
}
