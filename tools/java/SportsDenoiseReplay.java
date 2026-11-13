package com.scamera.app.processing.opengl.postpipeline;

import java.util.Arrays;

public final class SportsDenoiseReplay {
    public static void main(String[] args) {
        float snr = Float.parseFloat(args[0]), policySnr = Float.parseFloat(args[1]);
        boolean sports = Boolean.parseBoolean(args[2]);
        SportsDenoisePolicy p = SportsDenoisePolicy.of(sports, sports, policySnr, Float.NaN, Float.NaN);
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config(); p.apply(c);
        float model = 3.25f;
        float[][] ms = {{.012f * .2f * model * .4f, .02f * .5f * model * .2f},
                {.012f * .2f * model * .1f, .02f * .5f * model * .4f},
                {.012f * .2f * model * .35f, .02f * .5f * model * .6f},
                {0f, .02f * .5f * model * .6f}};
        float[] motion = {.3f, .5f}; p.applyMs(ms, motion);
        System.out.println("{\"sharp\":" + p.sharpKeep + ",\"q\":" + p.lowSnr + ",\"grain\":" + p.residualGrain(.5f)
                + ",\"luma\":" + Arrays.deepToString(ScamDenoiseTables.luma(c, snr, null))
                + ",\"chroma\":" + Arrays.deepToString(ScamDenoiseTables.chroma(c, snr, null))
                + ",\"ms\":" + Arrays.deepToString(ms) + ",\"motion\":" + Arrays.toString(motion) + "}");
    }
}
