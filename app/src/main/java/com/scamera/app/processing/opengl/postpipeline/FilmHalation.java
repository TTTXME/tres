package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;

import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.processing.opengl.nodes.Node;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P281: the film simulation's halation, on the linear scene light before the tone (where a light source still has its real
 * energy): light that passed the emulsion and came back from the film base exposes the red layer (a little of the green) in a
 * wide halo. The halo is the image's own Gaussian pyramid summed around the film's halation size (the stock's um over the
 * frame's pixel size: a small format makes it wider in the picture), three bounces each wider and weaker; energy kept:
 * out = (E + a (K * E)) / (1 + a) per channel, a = the stock's antihalation leak times the setting.
 * P286 (the reference model's apply_halation_um): first the in-emulsion scatter for a stock of the reference model (core
 * Gaussian 2.2 / 2.0 / 1.6 um, exponential tail 9.3 / 9.7 / 9.1 um of weight 0.78 / 0.65 / 0.67, scaled by «Мягкость плёнки»;
 * film/scatter.glsl), then the halation as the reference's sum of three bounces G(sigma_h sqrt(k)) weighted 1, 1/2, 1/4: each
 * a Gaussian of the remaining width (film/blur.glsl) on the pyramid level whose own blur is under half the first bounce's.
 */
public final class FilmHalation extends Node {
    private final FilmSim.Settings set;

    FilmHalation(FilmSim.Settings set) {
        super("", "FilmHalation");
        this.set = set;
    }

    /** The node for the shot's film snapshot (P369: PostPipeline.film), null without a simulation, a format or halation. */
    public static FilmHalation from(@androidx.annotation.Nullable FilmSim.Settings s) {
        if (s == null || s.format.longMm <= 0) return null;
        final float[] h = s.stock.halation;
        final boolean halo = s.halation > 0f && h[0] + h[1] + h[2] > 0f, scatter = s.stock.spectral != null && s.softness > 0f;
        if (!halo && !scatter) return null;
        return new FilmHalation(s);
    }

    /**
     * P369 (texture-zoom, outputs-3, texture-lab, outputs-4): the long side of the film's frame in pixels of an image
     * {@code imageLong} pixels long. The film covers the photo as saved: the digital zoom's crop and the ISZ 4:3 crop come after
     * the film effects (Parameters.filmFrameScale: their share of the image's long side), and a lab fragment stands for its whole
     * shot (labWholeSize over rawSize), so the grain, the scatter and the halation keep their size in the photo. imageLong itself
     * when neither applies (a film shot without zoom: as before, bit for bit).
     */
    public static int filmLongPx(int imageLong, @androidx.annotation.Nullable Parameters p) {
        if (p == null) return imageLong;
        final float frame = p.filmFrameScale > 0f ? p.filmFrameScale : 1f;
        final float lab = p.labWholeSize != null && p.rawSize != null && Math.max(p.rawSize.x, p.rawSize.y) > 0
                ? Math.max(p.labWholeSize.x, p.labWholeSize.y) / (float) Math.max(p.rawSize.x, p.rawSize.y) : 1f;
        if (frame == 1f && lab == 1f) return imageLong;
        return Math.max(1, Math.round(imageLong * frame * lab));
    }

    /**
     * P369: Parameters.filmFrameScale of a shot - the ISZ 4:3 crop's share of the long side ({@code iszAspect4x3}, of the
     * pipeline's {@code rawSize}) over the digital zoom's crop {@code zoomCrop} (ScamProcessor's rule: a crop above 1.005).
     */
    public static float filmFrameScale(float zoomCrop, boolean iszAspect4x3, @androidx.annotation.Nullable android.graphics.Point rawSize) {
        final float isz = iszAspect4x3 && rawSize != null ? com.scamera.app.processing.IszAspectCrop.longRatio(rawSize.x, rawSize.y) : 1f;
        return zoomCrop > 1.005f ? isz / zoomCrop : isz;
    }

    /** The pyramid level whose blur is about {@code sigmaPx} (a gaussdown level k blurs about 0.8 * 2^k). */
    static int level(float sigmaPx, int levels) {
        final int k = Math.round((float) (Math.log(Math.max(1f, sigmaPx / .8f)) / Math.log(2)));
        return Math.max(1, Math.min(levels - 2, k));
    }

    @Override
    public void bindShader() {}

    @Override
    public void runPass() {
        final GLTexture in = prevPass.passOut;
        passOut = in;
        final long started = System.currentTimeMillis();
        final float um = set.pixelUm(filmLongPx(Math.max(in.size.x, in.size.y), hostPipeline.shotParams));
        if (um <= 0f) return;
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        final List<GLTexture> temp = new ArrayList<>();
        try {
            final float[] a = set.halationAmounts(); // x «Сила симуляции»
            final boolean halo = a[0] + a[1] + a[2] > 0f;
            GLTexture e1 = in;
            if (set.stock.spectral != null && set.softness > 0f) {
                // P286: the scatter (the reference's scatter_amount 1, spatial scale = the setting)
                final float k = set.softnessApplied() / um; // x «Сила симуляции»
                final GLTexture h = new GLTexture(in.size, rgba), h2 = halo ? new GLTexture(in.size, rgba) : hostPipeline.getMain();
                temp.add(h);
                if (halo) temp.add(h2);
                for (int pass = 0; pass < 2; pass++) {
                    final GLTexture dst = pass == 0 ? h : h2;
                    shaderProg.useAssetProgram("film/scatter", false);
                    shaderProg.setTexture("InputBuffer", pass == 0 ? in : h);
                    shaderProg.setVar("dir", pass == 0 ? 1f : 0f, pass == 0 ? 0f : 1f);
                    shaderProg.setVar("core", SCATTER_CORE[0] * k, SCATTER_CORE[1] * k, SCATTER_CORE[2] * k);
                    shaderProg.setVar("tail", SCATTER_TAIL[0] * k, SCATTER_TAIL[1] * k, SCATTER_TAIL[2] * k);
                    shaderProg.setVar("tailWeight", SCATTER_W[0], SCATTER_W[1], SCATTER_W[2]);
                    shaderProg.drawBlocks(dst);
                }
                shaderProg.closed = true;
                e1 = h2;
                if (!halo) passOut = e1;
            }
            if (!halo) return;
            final float sigma = set.halationUm() / um; // P487: x «Halation size»
            // the pyramid down to the level whose own blur (about 0.8 x 2^k) is under half the first bounce's width
            final List<GLTexture> v = new ArrayList<>();
            v.add(e1);
            while (0.8f * (1 << v.size()) <= sigma * .5f && Math.max(v.get(v.size() - 1).size.x, v.get(v.size() - 1).size.y) > 8) {
                final GLTexture last = v.get(v.size() - 1);
                final GLTexture next = glKit.gaussianDownsample(last, new Point(Math.max(1, last.size.x / 2), Math.max(1, last.size.y / 2)));
                temp.add(next);
                v.add(next);
            }
            final int lv = v.size() - 1;
            final GLTexture base = v.get(lv);
            final float own = lv == 0 ? 0f : 0.8f * (1 << lv), scale = 1f / (1 << lv);
            final GLTexture[] bounce = new GLTexture[3];
            for (int k = 0; k < 3; k++) {
                final float sk = sigma * (float) Math.sqrt(k + 1);
                final float rest = (float) Math.sqrt(Math.max(sk * sk - own * own, 0.25f)) * scale;
                final GLTexture hx = new GLTexture(base.size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                final GLTexture hy = new GLTexture(base.size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.add(hx);
                temp.add(hy);
                shaderProg.useAssetProgram("film/blur", false);
                shaderProg.setTexture("InputBuffer", base);
                shaderProg.setVar("dir", 1f, 0f);
                shaderProg.setVar("sigma", rest);
                shaderProg.drawBlocks(hx);
                shaderProg.useAssetProgram("film/blur", false);
                shaderProg.setTexture("InputBuffer", hx);
                shaderProg.setVar("dir", 0f, 1f);
                shaderProg.setVar("sigma", rest);
                shaderProg.drawBlocks(hy);
                bounce[k] = hy;
            }
            final GLTexture haloTex = new GLTexture(base.size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            temp.add(haloTex);
            shaderProg.useAssetProgram("film/halo_sum", false);
            shaderProg.setTexture("B1", bounce[0]);
            shaderProg.setTexture("B2", bounce[1]);
            shaderProg.setTexture("B3", bounce[2]);
            shaderProg.drawBlocks(haloTex);
            // The output (a main, made again on its next use above 100 MP or with early release) is taken before the samplers
            // are bound: a new texture binds itself on unit 1 + its name, which may be a sampler's unit (the pass then read its
            // own output as Halo).
            final GLTexture out = hostPipeline.getMain();
            shaderProg.useAssetProgram("film/halation", false);
            shaderProg.setTexture("InputBuffer", e1);
            shaderProg.setTexture("Halo", haloTex);
            shaderProg.setVar("amount", a[0], a[1], a[2]);
            passOut = out;
            shaderProg.drawBlocks(passOut);
            shaderProg.closed = true;
            Log.i("SCAM_PIPELINE", "film halation " + set.stock.id + " " + set.format.id + String.format(java.util.Locale.ROOT,
                    " pixel %.2f um sigma %.1f px level %d amount %.3f/%.3f/%.3f ms=%d", um, sigma, lv, a[0], a[1], a[2],
                    System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : temp) t.close();
        }
    }

    /** The reference's in-emulsion scatter (HalationParams): core sigma and tail decay (um), the tail's weight. */
    static final float[] SCATTER_CORE = {2.2f, 2.0f, 1.6f}, SCATTER_TAIL = {9.3f, 9.7f, 9.1f}, SCATTER_W = {.78f, .65f, .67f};
}
