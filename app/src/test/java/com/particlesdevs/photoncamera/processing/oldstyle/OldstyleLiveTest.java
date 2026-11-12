package com.particlesdevs.photoncamera.processing.oldstyle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * P630g «Oldstyle»: the viewfinder's live tone state (owner's recording, a vivo: the viewfinder burnt out in the mode and changed
 * brightness; «Фото» and the saved photo right) - a simulation of a viewfinder: a scene, RAW frames of it at the viewfinder's
 * exposure with noise and AE jitter, metered every 300 ms as CaptureController does, and the ISP's frames of the same RAW with the
 * ISP's own gain (which nobody reports), measured as LiveSceneMeter does. Through OldstyleLive: nothing before a plan and a level;
 * the screen's mean where the photo's will be, whatever the ISP's gain; the two exposures in the plan's ratio; a static scene
 * steady from frame to frame; no light burnt out that the photo keeps; a shot's series frames (another level) leave the picture
 * where it was; the HDR / SDR decision does not flip on a metering or two.
 */
public class OldstyleLiveTest {
    static final long MS = 1_000_000L;
    static final int W = 64, H = 48;

    /** A room: a dim wall, a bright window (HDR) or a lit wall (SDR), linear scene light. */
    static float[] scene(boolean window) {
        final float[] s = new float[W * H * 3];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            float v = 0.02f + 0.05f * x / (W - 1) + 0.02f * y / (H - 1);
            if (window && x > 40 && y > 8 && y < 30) v = 0.9f;
            if (!window && x > 40 && y > 8 && y < 30) v = 0.16f;
            s[(y * W + x) * 3] = v * 1.05f;
            s[(y * W + x) * 3 + 1] = v;
            s[(y * W + x) * 3 + 2] = v * 0.9f;
        }
        return s;
    }

    /** The RAW frame of the scene at an exposure: clipped at white, with noise. */
    static float[] raw(float[] scene, float exposure, Random noise) {
        final float[] r = new float[scene.length];
        for (int i = 0; i < r.length; i++) {
            final float v = scene[i] * exposure;
            r[i] = Math.max(0f, Math.min(1f, v + (noise == null ? 0f : (float) (noise.nextGaussian() * (0.002 + 0.02 * Math.sqrt(v))))));
        }
        return r;
    }

    /** The ISP's frame of a RAW frame: its gain, a soft knee into white (reached at 1), display-encoded (1 / 2.2), 8 bit. */
    static int[] isp(float[] raw, float gain) {
        final int[] d = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            float v = raw[i] * gain;
            if (v > 0.7f) v = v >= 1f ? 1f : 0.7f + 0.3f * (float) ((1 - Math.exp(-1.5 * (v - 0.7f) / 0.3f)) / (1 - Math.exp(-1.5)));
            d[i] = Math.round(255f * (float) Math.pow(Math.max(0f, Math.min(1f, v)), 1 / 2.2));
        }
        return d;
    }

    /** The linear mean of an ISP frame's display luma, as LiveSceneMeter.linearMean. */
    static float level(int[] isp, float seriesGain) {
        double sum = 0;
        for (int i = 0; i < isp.length; i += 3) {
            final double y = (0.2126 * isp[i] + 0.7152 * isp[i + 1] + 0.0722 * isp[i + 2]) / 255.0;
            sum += Math.min(1.0, Math.pow(y, 2.2) * seriesGain);
        }
        return (float) (sum / (isp.length / 3));
    }

    /** A stand-in for the version's curve and long weight (the module's LUT on the phone): display of a linear value, 0..1. */
    static double curve(double x) {
        return Math.pow(Math.max(0.0, Math.min(1.0, x)), 1 / 2.2);
    }

    static double longWeight(double longLinear) {
        return Math.max(0.0, Math.min(1.0, (0.85 - curve(longLinear)) / 0.15 + 0.5));
    }

    /** The tone of linear values with two gains (the live shader's V1 form: the curves of both exposures by the long weight). */
    static double[] tone(double[] linear, float gainShort, float gainLong) {
        return tone(linear, gainShort, gainLong, 1f);
    }

    /** With the live shader's white boost: the frame's top (OldstyleLive.WHITE_FROM up) lifted before the gains. */
    static double[] tone(double[] linear, float gainShort, float gainLong, float boost) {
        final double[] o = new double[linear.length];
        for (int i = 0; i < linear.length; i += 3) {
            final double top = Math.max(linear[i], Math.max(linear[i + 1], linear[i + 2]));
            final double t = Math.max(0.0, Math.min(1.0, (top - OldstyleLive.WHITE_FROM) / (1.0 - OldstyleLive.WHITE_FROM)));
            final double lift = 1.0 + (boost - 1.0) * t * t * (3.0 - 2.0 * t);
            final double m = top * lift * gainLong;
            final double w = gainShort < gainLong ? longWeight(m) : 1.0;
            for (int c = 0; c < 3; c++)
                o[i + c] = (1 - w) * curve(linear[i + c] * lift * gainShort) + w * curve(linear[i + c] * lift * gainLong);
        }
        return o;
    }

    static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    static double burnt(double[] a) {
        int n = 0;
        for (double v : a) if (v >= 0.98) n++;
        return n / (double) a.length;
    }

    static double[] linearOfIsp(int[] isp, float seriesGain) {
        final double[] l = new double[isp.length];
        for (int i = 0; i < isp.length; i++) l[i] = Math.min(1.0, Math.pow(isp[i] / 255.0, 2.2) * seriesGain);
        return l;
    }

    /** The photo of the scene by the capture plan: the burst at S (or the SDR TET), the tone's gains over it. */
    static double[] photo(float[] scene, float exposure, OldstyleAe.Capture c, float maxGain) {
        final float frame = (float) c.frameTet;                       // over the viewfinder frame's TET (1)
        final float[] merged = raw(scene, exposure * frame, null);
        final float[] g = c.hdr ? OldstyleAe.fusionGains(frame, (float) c.shortTet, (float) c.longTet, 1f, maxGain)
                : new float[]{(float) (c.longCandidate / frame), (float) (c.longCandidate / frame)};
        final double[] l = new double[merged.length];
        for (int i = 0; i < l.length; i++) l[i] = merged[i];
        return tone(l, g[0], g[1]);
    }

    /** A viewfinder run: {worst frame-to-frame change of the screen's mean, the last mean, the photo's mean, burnt screen, burnt photo}. */
    static double[] run(boolean window, boolean v31, float ispGain, int frames, long seed) {
        final float[] s = scene(window);
        final Random noise = new Random(seed);
        final OldstyleLive live = new OldstyleLive();
        final float maxGain = 16f;
        double worst = 0, last = Double.NaN;
        OldstyleAe.Capture press = null;
        double[] screen = null;
        float exposure = 4f;
        for (int f = 0; f < frames; f++) {
            final long now = f * 33L * MS;
            exposure = 4f * (1f + 0.01f * (float) Math.sin(f * 0.7));           // the AE's small hunting
            final float[] r = raw(s, exposure, noise);
            if (f % 9 == 0) {                                                    // the ring's newest RAW, every 300 ms
                final OldstyleAe.Meter m = OldstyleAe.meter(r, W, H, v31, 1f, true);
                press = OldstyleAe.capture(m, v31, 1.0);
                live.plan(now, v31 ? 2 : 1, press, maxGain);
            }
            final int[] frame = isp(r, ispGain);
            if (f % 2 == 1) live.level(now, level(frame, 1f));                   // the tile drawn on one frame, read on the next
            final float[] t = live.gains(now);
            if (t == null) continue;
            screen = tone(linearOfIsp(frame, 1f), t[1], t[2], t[4]);
            final double mean = mean(screen);
            if (f > 45 && !Double.isNaN(last)) worst = Math.max(worst, Math.abs(mean - last) / last);
            last = mean;
        }
        final double[] photo = photo(s, exposure, press, maxGain);
        return new double[]{worst, last, mean(photo), burnt(screen), burnt(photo)};
    }

    @Test public void nothingBeforeAPlanAndALevel() {
        final OldstyleLive live = new OldstyleLive();
        assertNull(live.gains(0));
        assertFalse(live.wantsLevel());
        live.level(0, 0.2f);
        assertNull("a level alone is no tone", live.gains(1));
        final OldstyleLive other = new OldstyleLive();
        final OldstyleAe.Capture c = OldstyleAe.capture(OldstyleAe.meter(raw(scene(true), 4f, null), W, H, false, 1f, true), false, 1.0);
        other.plan(0, 1, c, 16f);
        assertTrue(other.wantsLevel());
        assertNull("a plan alone is no tone: the frame's level is not known", other.gains(10 * MS));
        other.level(20 * MS, 0.2f);
        assertNotNull(other.gains(30 * MS));
        // a plan without its RAW mean is not taken; a stale level is no level; off: nothing again
        final OldstyleLive bare = new OldstyleLive();
        bare.plan(0, 1, new OldstyleAe.Capture(1, 2, 0.8f, true, true, 1, 2, 0f), 16f);
        assertFalse(bare.wantsLevel());
        assertNull(other.gains(30 * MS + OldstyleLive.LEVEL_STALE_NS + 1));
        other.off();
        assertNull(other.gains(40 * MS));
        // a frame nobody measures: the assumed level
        other.plan(50 * MS, 1, c, 16f);
        assertNotNull(other.gains(60 * MS, OldstyleAe.ISP_LINEAR_MEAN));
    }

    @Test public void theScreensMeanIsWhereThePhotosWillBeWhateverTheIspsGain() {
        for (boolean v31 : new boolean[]{false, true})
            for (boolean window : new boolean[]{false, true})
                for (float k : new float[]{1f, 2f, 3.5f, 6f}) {
                    final double[] r = run(window, v31, k, 120, 7);
                    final String tag = (v31 ? "3.1" : "2.7") + (window ? " window" : " wall") + " ISP x" + k + ": screen "
                            + r[1] + " photo " + r[2] + " burnt " + r[3] + " / " + r[4] + " step " + r[0];
                    assertEquals(tag, r[2], r[1], 0.05 * r[2]);                 // the mean within 5 % of the photo's
                    assertTrue(tag, r[3] <= r[4] + 0.005);                      // nothing burnt out that the photo keeps
                    assertTrue(tag, r[0] < 0.01);                               // a static scene: under 1 % from frame to frame
                }
    }

    @Test public void theRawPlansGainsOnTheIspFrameWereTheBurntViewfinder() {
        // P630e drew the ISP frame with the RAW frame's gains: with the ISP's own x3.5 on top the wall's mean goes far past the photo's
        final float[] s = scene(false);
        final float[] r = raw(s, 4f, null);
        final OldstyleAe.Capture c = OldstyleAe.capture(OldstyleAe.meter(r, W, H, false, 1f, true), false, 1.0);
        final float[] rawGains = OldstyleAe.viewfinderGains(c, 16f);
        final int[] frame = isp(r, 3.5f);
        final double before = mean(tone(linearOfIsp(frame, 1f), rawGains[0], rawGains[1]));
        final OldstyleLive live = new OldstyleLive();
        live.plan(0, 1, c, 16f);
        live.level(0, level(frame, 1f));
        final float[] t = live.gains(0);
        final double now = mean(tone(linearOfIsp(frame, 1f), t[1], t[2], t[4])), photo = mean(photo(s, 4f, c, 16f));
        assertTrue(before > photo * 1.25);
        assertEquals(photo, now, 0.05 * photo);
        // the two exposures keep the plan's ratio
        assertEquals(rawGains[1] / rawGains[0], t[2] / t[1], 1e-3f);
    }

    @Test public void aShotsSeriesFramesLeaveThePictureWhereItWas() {
        final float[] s = scene(true);
        final OldstyleLive live = new OldstyleLive();
        final float[] r = raw(s, 4f, null);
        final OldstyleAe.Capture c = OldstyleAe.capture(OldstyleAe.meter(r, W, H, false, 1f, true), false, 1.0);
        long now = 0;
        for (int f = 0; f < 30; f++, now += 33 * MS) {
            if (f % 9 == 0) live.plan(now, 1, c, 16f);
            live.level(now, level(isp(r, 3.5f), 1f));
        }
        final float[] before = live.gains(now);
        final double was = mean(tone(linearOfIsp(isp(r, 3.5f), 1f), before[1], before[2], before[4]));
        // the series: AE off, the ISP without its usual gain (x1.2 instead of x3.5) - no plan arrives (the ring is not filled)
        final int[] series = isp(r, 1.2f);
        live.level(now, level(series, 1f));
        final float[] during = live.gains(now + 33 * MS);
        final double is = mean(tone(linearOfIsp(series, 1f), during[1], during[2], during[4]));
        assertEquals("the level's jump is taken at once", was, is, 0.06 * was);
        assertTrue(during[2] > before[2] * 2f);
        // and back
        live.level(now + 700 * MS, level(isp(r, 3.5f), 1f));
        final float[] after = live.gains(now + 733 * MS);
        assertEquals(before[2], after[2], before[2] * 0.02f);
    }

    /**
     * A window clipped on the screen: the plan (S* below the frame's exposure for a light it assumes above white) would paint it
     * grey at its lowest gain; the frame's top is lifted so that its white stays white, as the photo with the light really there
     * keeps it; the wall beside it is not touched.
     */
    @Test public void aClippedWindowStaysWhiteOnTheScreen() {
        final float[] s = scene(true);
        final float[] r = raw(s, 4f, null);
        final OldstyleAe.Meter m = OldstyleAe.meter(r, W, H, true, 1f, true);
        assertTrue(m.clipped);
        final OldstyleAe.Capture c = OldstyleAe.capture(m, true, 1.0);
        final OldstyleLive live = new OldstyleLive();
        final int[] frame = isp(r, 1f);
        live.plan(0, 2, c, 16f);
        live.level(0, level(frame, 1f));
        final float[] t = live.gains(0);
        assertTrue("the lowest gain " + t[1] + " darkens the frame's white", t[1] < 0.9f);
        // the screen's gain over the RAW is ~1 here: its white is the RAW's clipped samples, each the light the plan assumes there
        // (twice the white), at most white in the photo
        final double k = OldstyleLive.screenGain(c.meterLuma, c.meterMean, level(frame, 1f));
        assertEquals(1.0, k, 0.05);
        assertEquals(t[4], OldstyleLive.whiteBoost(c.meterTop, 1f, k, t[1]), 1e-4f);
        assertEquals(Math.min(1f / t[1], 1f / OldstyleAe.CLIPPED_STEP), t[4], 0.1f);
        final double[] lin = linearOfIsp(frame, 1f), plain = tone(lin, t[1], t[2], 1f), kept = tone(lin, t[1], t[2], t[4]);
        final int window = (20 * W + 50) * 3, wall = (40 * W + 10) * 3;
        assertTrue(plain[window] < 0.85 && kept[window] > 0.93);
        assertEquals(plain[wall], kept[wall], 0.0);
        // the ISP's gain past the RAW's light: the screen's white is the RAW samples from 1 / k up, their mean in screen units
        final float[] top = {0.1f, 0.2f, 0.3f, 0.4f, 0.6f};
        assertEquals(1f, OldstyleLive.whiteBoost(top, 1f, 1.5, 0.5), 0f);                // nothing reaches the screen's white
        // 0.4 and 0.6 at k 2.5 (1.0 and 1.5 of the screen's white), drawn at gain 0.5: the display mean of 0.5 and 0.75, over 0.5
        final double two = Math.pow((Math.pow(0.5, 1 / 2.2) + Math.pow(0.75, 1 / 2.2)) / 2, 2.2) / 0.5;
        assertEquals((float) two, OldstyleLive.whiteBoost(top, 1f, 2.5, 0.5), 1e-5f);
        assertTrue(two > 1.0 && two < 1.25);
        // at a gain that takes them all past white the screen's white is white: 1 / gain
        assertEquals(1f / 0.9f, OldstyleLive.whiteBoost(top, 1f, 4.0, 0.9), 1e-5f);
        assertEquals(1f, OldstyleLive.whiteBoost(top, 1f, 4.0, 1.5), 0f);                // never below 1
        assertEquals(OldstyleLive.WHITE_BOOST_MAX, OldstyleLive.whiteBoost(new float[]{1f}, 1f, 100.0, 0.01), 0f);
        assertEquals(1f, OldstyleLive.whiteBoost(null, 1f, 3.0, 0.5), 0f);
        // the screen's gain over the RAW: the two means' ratio while nothing clips, more than it once the screen clips
        final float[] luma = {0.05f, 0.1f, 0.2f, 0.4f};
        assertEquals(2.0, OldstyleLive.screenGain(luma, 0.1875, 0.375), 0.01);
        final double clippedLevel = (0.2 + 0.4 + 0.8 + 1.0) / 4;                         // k = 4: the last sample past white
        assertEquals(4.0, OldstyleLive.screenGain(luma, 0.1875, clippedLevel), 0.02);
        assertTrue(clippedLevel / 0.1875 < 3.3);
        assertEquals(2.0, OldstyleLive.screenGain(null, 0.1, 0.2), 1e-9);
    }

    @Test public void theDecisionDoesNotFlipOnAMeteringOrTwo() {
        final OldstyleLive live = new OldstyleLive();
        final OldstyleAe.Capture hdr = new OldstyleAe.Capture(1.0, 1.5, 0.8f, true, true, 1.0, 1.5, 0f);
        final OldstyleAe.Capture sdr = new OldstyleAe.Capture(1.0, 1.4, 0.8f, false, false, 1.0, 1.4, 0f);
        hdr.meterMean = sdr.meterMean = 0.05f;
        live.plan(0, 1, hdr, 16f);
        live.level(0, 0.18f);
        assertTrue(live.hdr());
        // the ratio hovers at the rule's 1.45: SDR, HDR, SDR ... every 300 ms - the decision holds, the gains barely move
        final float[] first = live.gains(0);
        float worst = 0f;
        float[] last = first;
        for (int i = 1; i <= 20; i++) {
            final long now = i * 300L * MS;
            live.plan(now, 1, i % 2 == 1 ? sdr : hdr, 16f);
            live.level(now, 0.18f);
            for (int f = 0; f < 9; f++) {
                final float[] t = live.gains(now + f * 33L * MS);
                worst = Math.max(worst, Math.abs(t[1] / last[1] - 1f));
                worst = Math.max(worst, Math.abs(t[2] / last[2] - 1f));
                last = t;
            }
            assertTrue(live.hdr());
        }
        assertTrue("step " + worst, worst < 0.01f);
        // SDR for good: after the hold the decision follows, the short gain glides to the long one (no jump)
        float step = 0f;
        for (int i = 21; i <= 40; i++) {
            final long now = i * 300L * MS;
            live.plan(now, 1, sdr, 16f);
            for (int f = 0; f < 9; f++) {
                live.level(now + f * 33L * MS, 0.18f);
                final float[] t = live.gains(now + f * 33L * MS);
                step = Math.max(step, Math.abs(t[1] / last[1] - 1f));
                last = t;
            }
        }
        assertFalse(live.hdr());
        assertEquals(0f, last[3], 0f);
        assertEquals(last[2], last[1], 0f);
        assertTrue("step " + step, step < 0.04f);
        // another version: its own state at once (another LUT)
        live.plan(41 * 300L * MS, 2, hdr, 16f);
        assertTrue(live.hdr());
        assertArrayEquals(new float[]{2f}, new float[]{live.gains(41 * 300L * MS + MS)[0]}, 0f);
    }

    /** A stand-in LUT: a 1 / 2.2 curve, the long weight falling from 0.6 to 0.9 of white. */
    static byte[] lut() {
        final byte[] l = new byte[4096];
        for (int i = 0; i < 1024; i++) {
            final double x = i / 1023.0;
            final byte v = (byte) Math.round(255 * Math.pow(x, 1 / 2.2));
            l[i * 4] = l[i * 4 + 1] = l[i * 4 + 2] = v;
            l[i * 4 + 3] = (byte) Math.round(255 * Math.max(0.0, Math.min(1.0, (0.9 - x) / 0.3)));
        }
        return l;
    }

    /**
     * The calibration on the module's own picture: the shader's picture of the metered samples (screenMean) is brought to the
     * module's mean (photoMean) by the gains - a display ratio to the power 2.2, both exposures alike, at most x2 either way; no
     * photo mean or no LUT: the plan as it is.
     */
    @Test public void theGainsAreCalibratedOnTheModulesPicture() {
        final byte[] lut = lut();
        final float[] grey = new float[W * H * 3];
        java.util.Arrays.fill(grey, 0.18f);
        assertEquals((float) Math.pow(0.18, 1 / 2.2), OldstyleLive.screenMean(grey, lut, 1, 1f, 1f), 0.004f);
        assertEquals((float) Math.pow(0.36, 1 / 2.2), OldstyleLive.screenMean(grey, lut, 2, 2f, 2f), 0.004f);
        // two exposures: the long weight of the long exposure's light (0.18 x 4 = 0.72: weight 0.6), V1 in the output
        final float w = (0.9f - 0.72f) / 0.3f;
        assertEquals((1 - w) * (float) Math.pow(0.09, 1 / 2.2) + w * (float) Math.pow(0.72, 1 / 2.2), OldstyleLive.screenMean(grey, lut, 1, 0.5f, 4f), 0.006f);
        final float[] r = raw(scene(false), 4f, null);
        final OldstyleAe.Capture c = OldstyleAe.capture(OldstyleAe.meter(r, W, H, false, 1f, true), false, 1.0);
        c.meterRgb = r;
        c.meterW = W;
        c.meterH = H;
        final float[] plain = OldstyleAe.viewfinderGains(c, 16f);
        final float screen = OldstyleLive.screenMean(r, lut, 1, plain[0], plain[1]);
        final OldstyleLive live = new OldstyleLive();
        live.plan(0, 1, c, 16f, screen * 0.9f, lut);           // the module's picture 10 % darker than the shader's
        live.level(0, c.meterMean);                           // the screen frame is the RAW frame here (gain 1)
        final float[] t = live.gains(0);
        assertEquals(plain[1] * (float) Math.pow(0.9, 2.2), t[2], plain[1] * 0.01f);
        assertEquals(screen * 0.9f, OldstyleLive.screenMean(r, lut, 1, t[1], t[2]), screen * 0.02f);
        // at most x2; nothing to calibrate on: the plan's gains
        final OldstyleLive far = new OldstyleLive(), none = new OldstyleLive(), noLut = new OldstyleLive();
        far.plan(0, 1, c, 16f, screen * 3f, lut);
        far.level(0, c.meterMean);
        assertEquals(plain[1] * (float) OldstyleLive.CALIBRATION_MAX, far.gains(0)[2], plain[1] * 0.01f);
        none.plan(0, 1, c, 16f, -1f, lut);
        none.level(0, c.meterMean);
        assertEquals(plain[1], none.gains(0)[2], plain[1] * 0.005f);
        noLut.plan(0, 1, c, 16f, screen * 0.5f, null);
        noLut.level(0, c.meterMean);
        assertEquals(plain[1], noLut.gains(0)[2], plain[1] * 0.005f);
    }

    /** P630h: the rows of «Видоискатель» (and the mode's exposure and fusion base) in the live tone. */
    @Test public void theViewfinderRows() {
        final byte[] lut = lut();
        final float[] r = raw(scene(false), 4f, null);
        final OldstyleAe.Capture c = OldstyleAe.capture(OldstyleAe.meter(r, W, H, false, 1f, true), false, 1.0);
        c.meterRgb = r;
        c.meterW = W;
        c.meterH = H;
        final OldstyleAe.Params plain = new OldstyleAe.Params();
        // the module's picture of the samples a fifth darker than the shader's: something to match
        final float[] own = OldstyleAe.viewfinderGains(c, 16f);
        final float photo = OldstyleLive.screenMean(r, lut, 1, own[0], own[1]) * 0.8f;
        // untouched: the plan with the settings is the plan before them
        final OldstyleLive before = new OldstyleLive(), with = new OldstyleLive();
        before.plan(0, 1, c, 16f, photo, lut);
        with.plan(0, 1, c, plain, photo, lut);
        before.level(0, c.meterMean);
        with.level(0, c.meterMean);
        final float[] base = before.gains(0);
        assertArrayEquals(base, with.gains(0), 0f);
        // «Тон в видоискателе» off: nothing is drawn with the tone, and the level is not asked for
        final OldstyleAe.Params off = new OldstyleAe.Params();
        off.vfTone = false;
        with.plan(33 * MS, 1, c, off, photo, lut);
        assertNull(with.gains(33 * MS));
        assertFalse(with.wantsLevel());
        // «Яркость видоискателя»: a stop is both screen gains doubled, whatever the match to the photo
        final OldstyleAe.Params brighter = new OldstyleAe.Params();
        brighter.vfEv = 1f;
        final OldstyleLive up = new OldstyleLive();
        up.plan(0, 1, c, brighter, photo, lut);
        up.level(0, c.meterMean);
        assertEquals(base[1] * 2f, up.gains(0)[1], base[1] * 1e-4f);
        assertEquals(base[2] * 2f, up.gains(0)[2], base[2] * 1e-4f);
        // «Подгонка под снимок»: 0 is the plan's own gains (no photo to match), 0.5 half the way (in stops)
        final OldstyleAe.Params noMatch = new OldstyleAe.Params(), half = new OldstyleAe.Params();
        noMatch.vfMatch = 0f;
        half.vfMatch = 0.5f;
        final OldstyleLive a = new OldstyleLive(), b = new OldstyleLive();
        a.plan(0, 1, c, noMatch, photo, lut);
        a.level(0, c.meterMean);
        b.plan(0, 1, c, half, photo, lut);
        b.level(0, c.meterMean);
        assertEquals(own[1], a.gains(0)[2], own[1] * 1e-4f);
        assertEquals(Math.sqrt(own[1] * (double) base[2]), b.gains(0)[2], own[1] * 1e-3f);
        assertTrue("the match moved the gains here", Math.abs(base[2] / own[1] - 1f) > 0.02f);
        // «Плавность видоискателя»: a new plan (twice the light gone) is followed faster with a shorter time
        final double[] moved = new double[2];
        int i = 0;
        for (float seconds : new float[]{0.05f, 2f}) {
            final OldstyleAe.Params p = new OldstyleAe.Params(), next = new OldstyleAe.Params();
            p.vfSmooth = next.vfSmooth = seconds;
            next.vfEv = 1f;                                              // a plan a stop brighter from 300 ms on
            final OldstyleLive live = new OldstyleLive();
            live.plan(0, 1, c, p, -1f, null);
            live.level(0, c.meterMean);
            final float first = live.gains(0)[2];
            live.plan(300 * MS, 1, c, next, -1f, null);
            live.level(300 * MS, c.meterMean);
            live.plan(600 * MS, 1, c, next, -1f, null);
            moved[i++] = Math.log(live.gains(600 * MS)[2] / first) / Math.log(2);
        }
        // 300 ms after the new plan: all of the stop with 0.05 s, about a seventh of it with 2 s
        assertEquals(1.0, moved[0], 0.02);
        assertEquals(1 - Math.exp(-0.3 / 2), moved[1], 0.02);
        assertTrue(moved[0] + " > " + moved[1], moved[0] > moved[1] * 2);
        // the mode's exposure shows in the viewfinder as in the photo: a stop is the gains doubled (no photo to match here)
        final OldstyleAe.Params ev = new OldstyleAe.Params();
        ev.baseEv = -1f;
        final OldstyleLive darker = new OldstyleLive(), same = new OldstyleLive();
        darker.plan(0, 1, c, ev, -1f, null);
        darker.level(0, c.meterMean);
        same.plan(0, 1, c, plain, -1f, null);
        same.level(0, c.meterMean);
        assertEquals(same.gains(0)[2] / 2f, darker.gains(0)[2], same.gains(0)[2] * 1e-4f);
        // the tone curve's shape is another LUT for the renderer (its key), the same shape the same one
        final int key = OldstyleLive.lutKey(1);
        OldstyleLive.curve(0f, 0f);
        assertEquals(key, OldstyleLive.lutKey(1));
        OldstyleLive.curve(0.5f, 0f);
        final int shaped = OldstyleLive.lutKey(1);
        assertTrue(shaped != key && shaped % 4 == 1 && OldstyleLive.lutKey(2) % 4 == 2);
        OldstyleLive.curve(0.5f, 0f);
        assertEquals(shaped, OldstyleLive.lutKey(1));
        OldstyleLive.curve(0f, 0f);
        assertTrue(OldstyleLive.lutKey(1) != shaped && OldstyleLive.lutKey(1) != key);
    }
}
