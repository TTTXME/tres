package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P600 (owner, 2026-11-10: «убедись что переключатель шумоподавления в настройках работает и при его отключении абсолютно весь
 * шумодав отключается ПОЛНОСТЬЮ»): every stage of the photo route whose purpose is noise removal, and the one switch all of them
 * ask - «Все шумодавы» (pref_scam_hybrid_denoise, {@link PreferenceKeys#isHybridDenoiseEnabled}; scam_dev.txt "hybrid_denoise 0/1").
 * <p>
 * Off: the photo is the frame merge (alignment, rejection and frame weights, the kernel's accumulation, the base demosaic), the
 * highlights, the colour, the tone and the sharpening as usual - nothing whose purpose is to remove noise. The post stages skip
 * (their nodes ask {@link #on}); the worker gets {@link #WORKER_OFF} and turns its own off (scam-hybrid.h hybridDenoiseSwitch,
 * kHybridDenoiseStages: the CPU stream and the GPU passes take the same tuning). On (the default): nothing changes, the worker
 * gets no line at all.
 * <p>
 * DenoiseStagesTest scans the sources, so a new stage cannot bypass the switch: a class that runs a shader of a stage asks
 * {@link #on}, every shader of the denoise folders is a stage's or listed in {@link #NOT_DENOISE} with the reason, and the worker
 * stages here are the worker's list.
 */
public final class DenoiseStages {
    private DenoiseStages() {}

    public enum Where { POST, WORKER }

    public enum Stage {
        /** ScamHdrDenoise -> WhyResolveDenoise: the reference's three 1 / 2 / 4 px YUV bilateral passes (and its grain). */
        RESOLVE(Where.POST, "ScamHdrDenoise", "scamdn/why_resolve"),
        /**
         * WhyMsDenoise «Пирамидальный шумодав»: the corner (lens shading) chroma cleanup and the quad speckle median (resolvedn/optics),
         * the Oklab Laplacian pyramid (its own switch why_ms_denoise as well).
         */
        MS_DENOISE(Where.POST, "WhyMsDenoise", "resolvedn/prepare", "resolvedn/optics", "resolvedn/frames", "resolvedn/lab",
                "resolvedn/down", "resolvedn/base", "resolvedn/detail", "resolvedn/rebuild", "resolvedn/rgb", "resolvedn/finish"),
        /** WhyFusion «Шумодав кадров светов»: bracket_denoise of the Bento range of whyLow. */
        BRACKET_DN(Where.POST, "WhyFusion", "why/bracket_dn"),
        /** why/combine's chroma denoise of strongly lifted shadows (chromaDnU): 0 on every route (WhyCombine, LiveWhyTone). */
        LIFTED_CHROMA(Where.POST, "WhyCombine"),
        /** The worker's chroma median of the plain Bayer merge (dual 5-point on R/G, B/G over the whole frame, HYBRID PLAIN). */
        PLAIN_CHROMA_MEDIAN(Where.WORKER, "plainChromaMedian"),
        /** The worker's 5 x 5 chroma smoothing of the gained ultrashort colour in the Bento blend (kHybUsBlend). */
        BENTO_CHROMA(Where.WORKER, "bentoBlendChroma"),
        /** P603: the worker's range-gated 5 x 5 smoothing of a Tetra merge where few frames merged (moving subjects). */
        TETRA_MOTION_SMOOTH(Where.WORKER, "mosaicTetraMotionSmooth");

        public final Where where;
        /** The node that asks the switch (POST), the worker's HybridTuning field (WORKER). */
        public final String owner;
        /** The shaders it runs, as GLProg.useAssetProgram names them. */
        public final String[] shaders;

        Stage(Where where, String owner, String... shaders) {
            this.where = where;
            this.owner = owner;
            this.shaders = shaders;
        }
    }

    /** The master switch every stage asks. */
    public static boolean on() {
        return PreferenceKeys.isHybridDenoiseEnabled();
    }

    /** The worker's tuning line of the switch off (scam-hybrid.h HybridTuning::denoise); on writes none. */
    public static final String WORKER_OFF = "denoise 0\n";

    /** The worker tuning line of this shot's switch: {@link #WORKER_OFF} or "". */
    public static String workerTuning() {
        return on() ? "" : WORKER_OFF;
    }

    /** The pipeline's log of the switch: "on", or "off" and the stages skipped. */
    public static String summary() {
        if (on()) return "on";
        final StringBuilder s = new StringBuilder("off, skipped:");
        for (Stage st : Stage.values()) s.append(' ').append(st.name().toLowerCase(java.util.Locale.ROOT)).append(st.where == Where.WORKER ? "(worker)" : "");
        return s.toString();
    }

    /**
     * Shaders of the denoise folders that are no denoise stage of the photo route, by the reason (DenoiseStagesTest: every other
     * shader there must be a stage's).
     */
    public static final Map<String, String> NOT_DENOISE;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        final String snr = "statistics: the shot's SNR (DenoiseStats.finishSnr, read by the sharpening and the detail); no pixel changes";
        for (String s : new String[]{"scamdn/stats", "scamdn/stats_cs", "scamdn/yuv", "scamdn/down2x"}) m.put(s, snr);
        m.put("chromadn/noiseest", "statistics: the noise level (ScamHdrDenoise.estimateNoise); no pixel changes");
        m.put("chromadn/noisequantile", "statistics: the noise level's quantile (NoiseQuantileGpu); no pixel changes");
        m.put("scamdn/hl_dezipper", "the highlight chroma dezipper (WhyHlDezipper, P480): the demosaic zipper and purple of clipped lights");
        final String host = "no node of the app runs it (the former ScamDenoise / NLM / despeckle engines and the first ms denoise; host checks only)";
        for (String s : new String[]{"scamdn/cbf", "scamdn/lbf", "scamdn/lgrain", "scamdn/llow", "scamdn/lrecon", "scamdn/strmap",
                "scamdn/final2x", "scamdn/down4", "scamdn/down8", "chromadn/apply", "chromadn/coarse", "chromadn/despeckle",
                "chromadn/down", "chromadn/down4", "chromadn/filter", "chromadn/luma", "chromadn/nlm", "why/msdn"}) m.put(s, host);
        m.put("editor/nr", "the gallery editor's own noise slider (an edit the user makes on a saved photo, not the photo route)");
        NOT_DENOISE = Collections.unmodifiableMap(m);
    }
}
