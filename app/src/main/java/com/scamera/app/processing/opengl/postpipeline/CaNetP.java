package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.settings.PreferenceKeys;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_TEXTURE0;

/**
 * P493 (owner, 2026-11-03: «Не забудь добавить нашу нейросеть для подавления хроматических аберраций, я должен иметь возможность
 * включать либо её, либо старый способ подавления»; «настройки для нового способа ... тоже добавь»): CaNetP v3, our colour-fringe
 * net with the camera's P399 inside (handoff-canet, README_CANETP.md), as GLES 3.1 fragment passes (shaders/canet/canet_p.glsl)
 * right after ScamRgb, where scamrgb's purpleFringe (P399) ran. «Подавление аберраций» (pref_scam_hybrid_fringe_method): «Нейросеть»
 * (default) = this net and purpleU 0 in scamrgb (P399 is inside the net: both would apply it twice); «Классическое» = P399 as before.
 * The old switch hybrid_highlight_purple (scam_dev, 0..1) takes both off at 0 and scales either. The net's input is scamrgb's output
 * through render() (WhyMsDenoise's camera -> render chain, the base frames' white = 1), its output goes back to camera RGB.
 * Passes: MODE 4 (full: P399's weight w, the gate g; 48 taps at REACH 12) -> MODE 0 (half: 4 features) -> MODE 5 (half: the mean of w)
 * -> c1 .. c5 (MODE 1, one draw per 4 output channels) -> c6 (MODE 2: the half-res chroma correction) -> MODE 3 (full: the gated
 * correction at the pixel's own luminance, the no-flip guard, P399's boost). Weights: assets/canet/canetp_v4.bin (sha256 checked once;
 * a wrong or missing file, a program the GPU cannot build, or a frame above {@link #MAX_PIXELS}: P399 in scamrgb, never neither).
 */
public final class CaNetP {
    static final String SHADER = "canet/canet_p";
    /**
     * P615 (owner, 2026-11-11: «Доучить порог»): v4 = v3 fine-tuned on the reference app's bursts (real fringe pairs: the merge -> its
     * CA-free estimate) for the gate's lower light floor ({@link Settings#ybLo} 0.05), the hue kept from turning where the target is
     * unsure; the layer table is v3's.
     */
    static final String WEIGHTS = "canet/canetp_v4.bin";
    static final String WEIGHTS_SHA256 = "8b9be75e5bac76408353fcf60954786d452d380b048ea7a5b07f1176d7e60386";
    /** c1 .. c6: {cin, cout, k, dilation} (weights_v3/weights.json; c1: the 4 features, P399's mean weight and 3 zero inputs). */
    static final int[][] LAYERS = {{8, 16, 3, 1}, {16, 16, 3, 1}, {16, 16, 3, 2}, {16, 16, 3, 4}, {32, 16, 1, 1}, {16, 2, 3, 1}};
    /** P493: above this the net's half-res textures (~24 B a pixel at the peak, c5) stay off the GPU: P399 instead (50 MP takes it). */
    static final long MAX_PIXELS = 64_000_000L;
    public static final String NEURAL = "neural", CLASSIC = "classic";
    /** P493: rows per submission of the two full-size passes (a glFlush each): short GPU jobs the viewfinder and the GPU watchdog see. */
    static final int STRIP = 64;

    private CaNetP() {}

    /** One RGBA32F texel per 4 output channels of every (tap, input) and the bias texels (reference.pack()). */
    static int texels(int[] layer) {
        final int og = (layer[1] + 3) / 4;
        return layer[2] * layer[2] * layer[0] * og + og;
    }

    static int totalTexels() {
        int n = 0;
        for (int[] l : LAYERS) n += texels(l);
        return n;
    }

    /** Which fringe suppression a shot gets. */
    enum Route { NONE, CLASSIC, NEURAL }

    /**
     * P493: «Нейросеть» or «Классическое» for a shot: {@code purple} is scamrgb's P399 strength (0 without the per-channel recovery
     * or with hybrid_highlight_purple 0: none of the two); the net only where it can run (its weights and programs {@code ready},
     * the frame not above {@link #MAX_PIXELS}), else P399 - never both, never neither.
     */
    static Route route(float purple, String method, long pixels, boolean ready) {
        if (!(purple > 0f)) return Route.NONE;
        if (CLASSIC.equals(method)) return Route.CLASSIC;
        return ready && pixels > 0 && pixels <= MAX_PIXELS ? Route.NEURAL : Route.CLASSIC;
    }

    /**
     * P493: «Подавление аберраций» as stored (scam_dev.txt "hybrid_fringe_method 0 / 1" = neural / classic). Default «Классическое»
     * until the net is proven on the phones (2026-11-03: the first OPPO runs hung the Adreno 750 in the net's passes).
     */
    static final String DEFAULT_METHOD = CLASSIC;
    static String method() {
        return NEURAL.equals(PreferenceKeys.hybridString("fringe_method", DEFAULT_METHOD)) ? NEURAL : CLASSIC;
    }

    /** The net's settings («Настройки нейросети»); every default is the trained value (v3's README_CANETP.md; YB_LO P615's). */
    static final class Settings {
        float strength = 1f;     // strengthU: the share of the net's correction
        int reach = 12;          // REACH: the brighter light looked for up to 6 or 12 px away
        float hueLo = 205f;      // HUE_LO: the lowest fringe hue
        float nearLo = 1.5f;     // NEAR_LO: how many times brighter the light next to it must be
        float ybLo = 0.05f;      // YB_LO: how bright that light must be (share of white; P615: 0.12 -> 0.05, the weights v4)
        boolean green = true;    // GREEN: the axial green fringe too
        boolean glow = true;     // SRC_RULES: a coloured light's own glow is kept
        boolean boost = true;    // BOOST: P399's own reduction where the net corrects, if it removes more
        float boostMin = 0.3f;   // BOOST_MIN (not a row: the trained value)
        boolean noFlip = true;   // NO_FLIP: never the opposite hue

        static Settings read() {
            final Settings s = new Settings();
            s.strength = clamp(PreferenceKeys.hybridValue("fringe_net_strength", 1f), 0f, 1f);
            s.reach = PreferenceKeys.hybridValue("fringe_net_reach", 12f) >= 9f ? 12 : 6;
            s.hueLo = clamp(PreferenceKeys.hybridValue("fringe_net_hue", 205f), 180f, 260f);
            s.nearLo = clamp(PreferenceKeys.hybridValue("fringe_net_near", 1.5f), 1.1f, 4f);
            s.ybLo = clamp(PreferenceKeys.hybridValue("fringe_net_light", 0.05f), 0.02f, 0.6f);
            s.green = PreferenceKeys.hybridSwitch("fringe_net_green", true);
            s.glow = PreferenceKeys.hybridSwitch("fringe_net_glow", true);
            s.boost = PreferenceKeys.hybridSwitch("fringe_net_boost", true);
            s.noFlip = PreferenceKeys.hybridSwitch("fringe_net_noflip", true);
            return s;
        }

        /** MODE 4's defines (the gate). */
        Map<String, String> gateDefines() {
            final Map<String, String> d = new LinkedHashMap<>();
            d.put("MODE", "4");
            d.put("HUE_LO", num(hueLo));
            d.put("GREEN", green ? "1" : "0");
            d.put("SRC_RULES", glow ? "1" : "0");
            d.put("NEAR_LO", num(nearLo));
            d.put("YB_LO", num(ybLo));
            d.put("REACH", Integer.toString(reach));
            return d;
        }

        /** MODE 3's defines (the boost and the no-flip guard). */
        Map<String, String> applyDefines() {
            final Map<String, String> d = new LinkedHashMap<>();
            d.put("MODE", "3");
            d.put("BOOST", boost ? "1" : "0");
            d.put("BOOST_MIN", num(boostMin));
            d.put("NO_FLIP", noFlip ? "1" : "0");
            return d;
        }

        /** strengthU: the net's share times the old P399 strength (hybrid_highlight_purple, 1 by default). */
        float strengthUniform(float purple) { return clamp(strength * purple, 0f, 1f); }
    }

    static String num(float v) { return String.format(Locale.ROOT, "%.4f", v); }
    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /** MODE 1 / 2 defines of layer i (c1 .. c6). */
    static Map<String, String> layerDefines(int i) {
        final int[] l = LAYERS[i];
        final Map<String, String> d = new LinkedHashMap<>();
        d.put("MODE", i == 5 ? "2" : "1");
        d.put("CIN", Integer.toString(l[0]));
        d.put("K", Integer.toString(l[2]));
        d.put("COUT", Integer.toString(l[1]));
        d.put("POOL", i == 1 ? "1" : "0");
        d.put("UPCAT", i == 4 ? "1" : "0");
        d.put("GF", "4");
        return d;
    }

    // ---- weights ----

    private static volatile float[] weights;
    private static volatile String weightsError;

    /** The v4 weights (asset, sha256 and size checked once), or null (the reason in {@link #weightsError}). */
    static float[] weights() {
        float[] w = weights;
        if (w != null) return w;
        synchronized (CaNetP.class) {
            if (weights != null) return weights;
            final long t0 = System.nanoTime(); // P662: the weights are compressed in the APK now (PERF: their read)
            try (InputStream in = ScameraApp.getAssetLoader().getInputStream(WEIGHTS)) {
                weights = parse(readAll(in));
                com.scamera.app.util.Log.i("PERF", "CANET weights read+check ms=" + (System.nanoTime() - t0) / 1000000.0);
                weightsError = null;
            } catch (Exception failure) {
                weightsError = String.valueOf(failure);
            }
            return weights;
        }
    }

    /** The asset's bytes as floats after the sha256 and layout checks (an exception names what is wrong). */
    static float[] parse(byte[] blob) throws Exception {
        final String sha = sha256(blob);
        if (!WEIGHTS_SHA256.equals(sha)) throw new IllegalStateException("weights sha256 " + sha + " != " + WEIGHTS_SHA256);
        if (blob.length != 16 * totalTexels())
            throw new IllegalStateException("weights " + blob.length + " B != " + 16 * totalTexels() + " B of the layer table");
        final float[] out = new float[blob.length / 4];
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        for (float v : out) if (!Float.isFinite(v)) throw new IllegalStateException("weights: a non-finite value");
        return out;
    }

    static String sha256(byte[] blob) throws Exception {
        final byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(blob);
        final StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(40000);
        final byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---- GPU ----

    /** Every program of a shot: compiled (or taken from the program cache) before scamrgb draws, so a GPU that cannot build one gets P399. */
    static boolean prepare(GLProg g, Settings s) {
        try {
            if (weights() == null) {
                com.scamera.app.util.Log.w("SCAM_PIPELINE", "fringe net (P493): weights unusable (" + weightsError + "), P399");
                return false;
            }
            use(g, s.gateDefines());
            use(g, single("MODE", "0"));
            use(g, single("MODE", "5"));
            for (int i = 0; i < LAYERS.length; i++) use(g, layerDefines(i));
            use(g, s.applyDefines());
            g.closed = true;
            return true;
        } catch (RuntimeException failure) {
            com.scamera.app.util.Log.w("SCAM_PIPELINE", "fringe net (P493): " + failure + ", P399");
            return false;
        }
    }

    private static Map<String, String> single(String k, String v) {
        final Map<String, String> d = new LinkedHashMap<>();
        d.put(k, v);
        return d;
    }

    private static void use(GLProg g, Map<String, String> defines) {
        for (Map.Entry<String, String> e : defines.entrySet()) g.setDefine(e.getKey(), e.getValue());
        g.useAssetProgram(SHADER, false);
    }

    /** Units 0, 1, 2 ... in order (GLProg.setTexture steps by 2: 12 inputs and the weights would pass the GLES minimum of 16). */
    private static void bind(GLProg g, String name, GLTexture t, int unit) {
        t.bind(GL_TEXTURE0 + unit);
        g.setVar(name, unit);
    }

    /** render(): camera RGB x neutral -> the render's linear RGB (ScamRgb.toRender: WhyMsDenoise's chain), white = 1. */
    private static void renderUniforms(GLProg g, float[] toRender, float[] fromRender, float[] neutral, float invWhite, float strength) {
        g.setVar("toRenderU", toRender);
        g.setVar("fromRenderU", fromRender);
        g.setVar("neutralU", neutral[0], neutral[1], neutral[2]);
        g.setVar("invWhiteU", invWhite);
        g.setVar("strengthU", strength);
    }

    /**
     * The base frames' white in the render's units: scamrgb's P399 white (the first channel to clip, white-balanced) through
     * render() as a neutral, its luminance.
     */
    static float renderWhite(float[] clipLo, float[] wp, float[] toRender) {
        float white = 1f;
        if (clipLo != null && clipLo[1] > 0f) {
            white = Float.MAX_VALUE;
            for (int c = 0; c < 3; c++) white = Math.min(white, clipLo[c] / Math.max(wp[c], 1e-6f));
            white = Math.max(white, 1e-6f);
        }
        final float[] luma = {0.2126f, 0.7152f, 0.0722f};
        float y = 0f;
        for (int r = 0; r < 3; r++) {
            float v = 0f;
            for (int c = 0; c < 3; c++) v += toRender[3 * r + c] * white * wp[c];
            y += luma[r] * v;
        }
        return y > 1e-6f ? y : white;
    }

    private static GLTexture tex(Point size, int channels, List<GLTexture> owned) {
        final GLTexture t = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, channels), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        owned.add(t);
        return t;
    }

    private static void free(GLTexture t, List<GLTexture> owned) {
        if (t == null) return;
        owned.remove(t);
        t.close();
    }

    /** MODE 1 / 2: one draw per output group into its own RGBA16F texture (c6: one RG16F). */
    private static GLTexture[] layer(GLProg g, int i, GLTexture[] in, GLTexture wt, Point size, List<GLTexture> owned) {
        final int[] l = LAYERS[i];
        final int groups = i == 5 ? 1 : (l[1] + 3) / 4;
        final GLTexture[] out = new GLTexture[groups];
        for (int og = 0; og < groups; og++) {
            use(g, layerDefines(i));
            for (int k = 0; k < in.length; k++) bind(g, "In" + k, in[k], k);
            bind(g, "Wt", wt, in.length);
            g.setVar("dilU", l[3]);
            if (i != 5) g.setVar("ogU", og);
            out[og] = tex(size, i == 5 ? 2 : 4, owned);
            g.drawBlocks(out[og]);
        }
        return out;
    }

    /**
     * The net on {@code in} (scamrgb's output) into {@code out}; the textures it makes are gone when it returns. {@code purple}:
     * scamrgb's P399 strength (hybrid_highlight_purple), the net's strength scales with it.
     */
    static void run(GLProg g, GLTexture in, GLTexture out, PostPipeline p, float[] clipLo, Settings s, float purple) {
        final long t0 = System.nanoTime();
        final float[] w = weights();
        if (w == null) throw new IllegalStateException("fringe net weights: " + weightsError);
        final float[] toRender = ScamRgb.toRender(p), fromRender = WhyMsDenoise.inverse3(toRender);
        if (fromRender == null) throw new IllegalStateException("fringe net: singular colour matrix");
        final float[] wp = p.shotParams.asShotNeutral;
        final float invWhite = 1f / renderWhite(clipLo, wp, toRender);
        final float strength = s.strengthUniform(purple);
        final Point full = in.size, half = new Point((full.x + 1) / 2, (full.y + 1) / 2),
                quarter = new Point((half.x + 1) / 2, (half.y + 1) / 2);
        final List<GLTexture> owned = new ArrayList<>();
        try {
            final GLTexture[] wt = new GLTexture[LAYERS.length];
            for (int i = 0, off = 0; i < LAYERS.length; i++) {
                final int n = texels(LAYERS[i]);
                final ByteBuffer row = ByteBuffer.allocateDirect(16 * n).order(ByteOrder.nativeOrder());   // GLTexture uploads a ByteBuffer
                row.asFloatBuffer().put(w, 4 * off, 4 * n);
                wt[i] = new GLTexture(new Point(n, 1), new GLFormat(GLFormat.DataType.FLOAT_32, 4), row, GL_NEAREST, GL_CLAMP_TO_EDGE);
                owned.add(wt[i]);
                off += n;
            }
            // MODE 4: P399's weight and the gate, full resolution (RG16F)
            final GLTexture gate = tex(full, 2, owned);
            use(g, s.gateDefines());
            bind(g, "In0", in, 0);
            renderUniforms(g, toRender, fromRender, wp, invWhite, strength);
            g.drawBlocks(gate, STRIP);
            // MODE 0: the 4 features at half resolution; MODE 5: the half-res mean of w
            GLTexture feat = tex(half, 4, owned);
            use(g, single("MODE", "0"));
            bind(g, "In0", in, 0);
            renderUniforms(g, toRender, fromRender, wp, invWhite, strength);
            g.drawBlocks(feat);
            GLTexture featP = tex(half, 4, owned);   // RGBA: c1's inputs 6 .. 8 must read 0 (an R16F texture samples A as 1)
            use(g, single("MODE", "5"));
            bind(g, "In0", gate, 0);
            g.drawBlocks(featP);
            final GLTexture[] f1 = layer(g, 0, new GLTexture[]{feat, featP}, wt[0], half, owned);
            free(feat, owned); free(featP, owned);
            final GLTexture[] f2 = layer(g, 1, f1, wt[1], quarter, owned);
            final GLTexture[] f3 = layer(g, 2, f2, wt[2], quarter, owned);
            for (GLTexture t : f2) free(t, owned);
            final GLTexture[] f4 = layer(g, 3, f3, wt[3], quarter, owned);
            for (GLTexture t : f3) free(t, owned);
            final GLTexture[] cat = new GLTexture[f1.length + f4.length];
            System.arraycopy(f1, 0, cat, 0, f1.length);
            System.arraycopy(f4, 0, cat, f1.length, f4.length);
            final GLTexture[] f5 = layer(g, 4, cat, wt[4], half, owned);
            for (GLTexture t : cat) free(t, owned);
            final GLTexture delta = layer(g, 5, f5, wt[5], half, owned)[0];
            for (GLTexture t : f5) free(t, owned);
            // MODE 3: the gated correction, back to camera RGB
            use(g, s.applyDefines());
            bind(g, "In0", in, 0);
            bind(g, "In1", delta, 1);
            bind(g, "In2", gate, 2);
            renderUniforms(g, toRender, fromRender, wp, invWhite, strength);
            g.drawBlocks(out, STRIP);
            g.closed = true;
        } finally {
            for (GLTexture t : owned) t.close();
        }
        com.scamera.app.util.Log.i("SCAM_PIPELINE", "fringe net (P493) CaNetP v4 " + full.x + "x" + full.y + " reach " + s.reach
                + " strength " + strength + " white " + String.format(Locale.ROOT, "%.3f", 1f / invWhite)
                + " submit ms=" + (System.nanoTime() - t0) / 1000000);
    }
}
