package com.scamera.app.processing.opengl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** P671: the «PERF post GPU nodes» line sums each node's programs in the run's order. */
public class GLGpuTimerTest {
    @Test
    public void nodesSummedInRunOrder() {
        final Map<String, long[]> sums = new LinkedHashMap<>();
        sums.put("/source", new long[]{500_000L, 1});
        sums.put("ScamRgb/(node)", new long[]{100_000L, 1});
        sums.put("ScamRgb/scamhdr/scamrgb", new long[]{200_000_000L, 1});
        sums.put("ScamRgb/scamhdr/clipband", new long[]{50_000_000L, 1});
        sums.put("WhyCombine/why/combine", new long[]{900_000_000L, 1});
        sums.put("final output/upload", new long[]{1_250_000L, 2});
        assertEquals("PERF post GPU nodes ms total 1151.9: (start) 0.5, ScamRgb 250.1, WhyCombine 900.0, final output 1.3",
                GLGpuTimer.nodeLine("post", sums, 1_151_900_000L, false));
        assertEquals("PERF post GPU nodes ms total 0.0 (disjoint):", GLGpuTimer.nodeLine("post", new LinkedHashMap<>(), 0L, true));
    }
}
