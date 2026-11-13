package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P635c (owner, 2026-11-12: «PDAF судя по всему не работает с фулрезом»): the AF line of a recipe session - a second's AF mode,
 * states, lens travel and triggers, so the next log says whether the lens moves at all.
 */
public class RecipeAfTraceTest {
    /** A second of 30 frames from {@code t0}; the last frame's line. */
    private static String second(RecipeAfTrace t, long t0, int mode, int[] states, float from, float to, int moving, int trigger) {
        String line = null;
        for (int i = 0; i <= 30; i++) {
            final String l = t.frame(t0 + i * 1000L / 30 + (i == 30 ? 1 : 0), mode, states[i % states.length], i < moving ? 1 : 0,
                    from + (to - from) * i / 30f, i == 0 && trigger > 0 ? 1 : 0, trigger > 0);
            if (l != null) line = l;
        }
        return line;
    }

    @Test public void aSecondSaysModeStatesLensAndTriggers() {
        final RecipeAfTrace t = new RecipeAfTrace();
        // the owner's log: PASSIVE_SCAN and PASSIVE_FOCUSED alternating
        final String line = second(t, 0, 4, new int[]{1, 2}, 5.164f, 5.164f, 0, 0);
        assertNotNull(line);
        assertTrue(line, line.startsWith("mode CONTINUOUS_PICTURE, states PASSIVE_SCAN>PASSIVE_FOCUSED>PASSIVE_SCAN"));
        assertTrue(line, line.contains("(30 changes in 31 frames)"));
        assertTrue(line, line.contains("lens 5.164 dpt, moving 0 frames"));
        assertTrue(line, line.contains("AF triggers 0, AF region none"));
    }

    @Test public void aMovingLensShowsItsTravel() {
        final RecipeAfTrace t = new RecipeAfTrace();
        final String line = second(t, 0, 1, new int[]{3}, 1.0f, 4.0f, 12, 1);
        assertNotNull(line);
        assertTrue(line, line.startsWith("mode AUTO, states ACTIVE_SCAN (0 changes"));
        assertTrue(line, line.contains("lens 1.000..4.000 dpt, moving 12 frames"));
        assertTrue(line, line.contains("AF triggers 1, AF region set"));
    }

    @Test public void theFirstSecondsAlwaysThenOnlyAChange() {
        final RecipeAfTrace t = new RecipeAfTrace();
        long at = 0;
        for (int i = 0; i < RecipeAfTrace.ALWAYS; i++, at += 1001)
            assertNotNull("second " + i, second(t, at, 4, new int[]{2}, 2f, 2f, 0, 0));
        assertNull("nothing changed", second(t, at, 4, new int[]{2}, 2f, 2f, 0, 0));
        at += 1001;
        assertNull(second(t, at, 4, new int[]{2}, 2.01f, 2.01f, 0, 0));
        at += 1001;
        assertNotNull("the lens moved", second(t, at, 4, new int[]{2}, 2.01f, 2.6f, 0, 0));
        at += 1001;
        assertNotNull("it stands elsewhere now", second(t, at, 4, new int[]{2}, 2.6f, 2.6f, 0, 0));
        at += 1001;
        assertNull(second(t, at, 4, new int[]{2}, 2.6f, 2.6f, 0, 0));
        at += 1001;
        assertNotNull("another state", second(t, at, 4, new int[]{1, 2}, 2.6f, 2.6f, 0, 0));
        at += 1001;
        assertNotNull("a trigger", second(t, at, 4, new int[]{1, 2}, 2.6f, 2.6f, 0, 1));
        at += 1001;
        assertNotNull("the mode", second(t, at, 0, new int[]{0}, 2.6f, 2.6f, 0, 0));
        // a new session writes its first seconds again
        t.reset();
        assertNotNull(second(t, at + 5000, 0, new int[]{0}, 2.6f, 2.6f, 0, 0));
    }

    @Test public void aCameraThatReportsNothingSaysSo() {
        final RecipeAfTrace t = new RecipeAfTrace();
        String line = null;
        for (int i = 0; i <= 30; i++) {
            final String l = t.frame(i * 34L, null, null, null, null, null, false);
            if (l != null) line = l;
        }
        assertNotNull(line);
        assertTrue(line, line.startsWith("mode not reported, states none (0 changes"));
        assertTrue(line, line.contains("lens not reported"));
        assertEquals("NOT_FOCUSED_LOCKED", RecipeAfTrace.stateName(5));
        assertEquals("PASSIVE_UNFOCUSED", RecipeAfTrace.stateName(6));
        assertEquals("OFF", RecipeAfTrace.modeName(0));
    }
}
