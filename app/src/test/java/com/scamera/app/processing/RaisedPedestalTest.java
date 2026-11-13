package com.scamera.app.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ShortBuffer;
import java.util.Random;

/**
 * P635c: the viewfinder stream of a full-resolution recipe session on a pedestal far above the declared black (the Xiaomi 17
 * Ultra's binned stream beside the full-size one: ~560 DN for a declared 64, the magenta viewfinder of the owner's log
 * 2026-11-12) - the frames' own floor is the black, a normal stream keeps the declared one.
 */
public class RaisedPedestalTest {
    static final int W = 512, H = 384;
    static final float[] DECLARED = {64, 64, 64, 64};

    /**
     * A Bayer frame: {@code pedestal} under every sample, a scene from {@code dark} to {@code bright} DN across the frame, the
     * four sites at {@code r / g / g / b} of it, read noise of 2 DN.
     */
    static ShortBuffer frame(float pedestal, float dark, float bright, float r, float g, float b, long seed) {
        final ShortBuffer d = ShortBuffer.allocate(W * H);
        final Random random = new Random(seed);
        final float[] site = {r, g, g, b};
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            final float scene = dark + (bright - dark) * x / (W - 1f);
            final double v = pedestal + scene * site[(x & 1) + 2 * (y & 1)] + random.nextGaussian() * 2;
            d.put(y * W + x, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        return d;
    }

    @Test public void theDocumentsBinnedStreamIsOnAPedestalOfItsOwn() {
        // the owner's documents, one session, one scene: the still 94.0 / 138.4 / 138.4 / 113.9 on black 64, the binned
        // viewfinder frame 608.9 / 684.5 / 684.5 / 645.9 - no black of 64 explains both
        assertTrue(RaisedPedestal.raised(558f, 64f, 1023f));
        assertFalse("the declared black with its drift", RaisedPedestal.raised(61f, 64f, 1023f));
        assertFalse("a dim scene's darkest block", RaisedPedestal.raised(150f, 64f, 1023f));
        assertFalse("14-bit levels scale with the range", RaisedPedestal.raised(2500f, 1024f, 16383f));
        assertTrue(RaisedPedestal.raised(9000f, 1024f, 16383f));
    }

    @Test public void aFrameOnARaisedPedestalIsDevelopedFromItsOwnFloor() {
        final RaisedPedestal p = new RaisedPedestal();
        final RaisedPedestal.Stats s = RaisedPedestal.measure(frame(560, 2, 300, 0.45f, 1f, 0.6f, 1), W, H, W);
        assertNotNull(s);
        p.observe(7, s);
        final float[] black = p.black(7, DECLARED, 1023f);
        // the darkest blocks: the pedestal and a few DN of the scene's shade
        for (float b : black) assertEquals(560f, b, 6f);
        // what the viewfinder subtracted before: 64 - (560 - 64) / 959 = 0.52 of the range left on every channel
        assertEquals(0.52f, (560f - DECLARED[0]) / (1023f - DECLARED[0]), 0.01f);
        // the declared black's array is never the one handed out raised
        assertFalse(black == DECLARED);
        assertEquals(64f, DECLARED[0], 0f);
    }

    @Test public void aNormalStreamKeepsItsDeclaredBlack() {
        final RaisedPedestal p = new RaisedPedestal();
        p.observe(1, RaisedPedestal.measure(frame(64, 0, 700, 0.45f, 1f, 0.6f, 2), W, H, W));
        assertSame(DECLARED, p.black(1, DECLARED, 1023f));
        // a bright frame of a normal stream: its darkest block under an eighth of the range above the black
        final RaisedPedestal q = new RaisedPedestal();
        q.observe(1, RaisedPedestal.measure(frame(64, 80, 900, 0.45f, 1f, 0.6f, 3), W, H, W));
        assertSame(DECLARED, q.black(1, DECLARED, 1023f));
    }

    @Test public void aWhiteWallIsNoPedestalOnceTheSceneShowsItsShade() {
        final RaisedPedestal p = new RaisedPedestal();
        // a normal stream looking at a bright wall: every block far above the black - read as raised until shade is seen
        p.observe(3, RaisedPedestal.measure(frame(64, 500, 600, 1f, 1f, 1f, 4), W, H, W));
        assertTrue(p.black(3, DECLARED, 1023f)[0] > 400f);
        p.observe(3, RaisedPedestal.measure(frame(64, 0, 600, 0.45f, 1f, 0.6f, 5), W, H, W));
        assertSame("the session's lowest floor came down to the declared black", DECLARED, p.black(3, DECLARED, 1023f));
        // and never goes up again within the session
        p.observe(3, RaisedPedestal.measure(frame(64, 500, 600, 1f, 1f, 1f, 6), W, H, W));
        assertSame(DECLARED, p.black(3, DECLARED, 1023f));
    }

    @Test public void theFloorIsTheSessionsLowestAndANewSessionStartsOver() {
        final RaisedPedestal p = new RaisedPedestal();
        p.observe(1, RaisedPedestal.measure(frame(560, 120, 300, 1f, 1f, 1f, 7), W, H, W));
        final float bright = p.floor(1);
        assertEquals(680f, bright, 8f);
        p.observe(1, RaisedPedestal.measure(frame(560, 3, 300, 1f, 1f, 1f, 8), W, H, W));
        // the darkest blocks of the ramp 3..300: the pedestal, the shade and the ramp's first 32 pixels (~9 DN)
        final float dark = p.floor(1);
        assertEquals(569f, dark, 5f);
        p.observe(1, RaisedPedestal.measure(frame(560, 120, 300, 1f, 1f, 1f, 9), W, H, W));
        assertEquals("a brighter frame does not raise it", dark, p.floor(1), 0f);
        // another session's frames are not this one's
        assertTrue(Float.isNaN(p.floor(2)));
        assertSame(DECLARED, p.black(2, DECLARED, 1023f));
        p.observe(2, null);
        assertTrue("a new session forgets", Float.isNaN(p.floor(2)));
        assertNull(p.last(2));
        assertSame(DECLARED, p.black(2, DECLARED, 1023f));
    }

    @Test public void theStatsAreByCfaSiteForTheLogLine() {
        final RaisedPedestal.Stats s = RaisedPedestal.measure(frame(560, 100, 100, 0.5f, 1f, 0.25f, 10), W, H, W);
        assertNotNull(s);
        // a flat scene of 100 DN: the sites at 0.5 / 1 / 1 / 0.25 of it over the pedestal
        assertEquals(610f, s.median[0], 2f);
        assertEquals(660f, s.median[1], 2f);
        assertEquals(660f, s.median[2], 2f);
        assertEquals(585f, s.median[3], 2f);
        assertEquals(585f, s.lowest(), 3f);
        assertTrue(s.max >= 660 && s.max < 700);
        assertTrue(s.toString(), s.toString().startsWith("floor ") && s.toString().contains(" median ") && s.toString().contains(" p99 "));
        // a frame too small to say anything, a buffer shorter than its rows
        assertNull(RaisedPedestal.measure(ShortBuffer.allocate(64 * 64), 64, 64, 64));
        assertNull(RaisedPedestal.measure(ShortBuffer.allocate(W * H / 2), W, H, W));
        // a padded row stride reads the same samples
        final ShortBuffer padded = ShortBuffer.allocate((W + 32) * H);
        final ShortBuffer plain = frame(560, 2, 300, 0.45f, 1f, 0.6f, 11);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) padded.put(y * (W + 32) + x, plain.get(y * W + x));
        assertEquals(RaisedPedestal.measure(plain, W, H, W).toString(), RaisedPedestal.measure(padded, W, H, W + 32).toString());
    }
}
