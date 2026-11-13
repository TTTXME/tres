package com.scamera.app.processing.processor;

import android.app.Application;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.IntFunction;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * P610: the mosaic white balance statistic measures its frames at once (ScamProcessor.firstMeasured) and keeps the sequential
 * loop's list: the same values in the same order, a later frame only where an earlier one gave none, each frame measured once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamProcessorStatFramesTest {
    private static List<String> sequential(int count, int want, IntFunction<String> measure) {
        final List<String> out = new ArrayList<>();
        for (int k = 0; k < count && out.size() < want; k++) {
            final String v = measure.apply(k);
            if (v != null) out.add(v);
        }
        return out;
    }

    @Test public void theLoopsListWithEachFrameMeasuredOnce() {
        final boolean[][] nulls = {{}, {false, false, false, false, false}, {true, false, false, false, false}, {false, true, true, false, false, true, false},
                {true, true, true, true}, {false}, {false, false}};
        for (boolean[] pattern : nulls) for (int want : new int[]{1, 2, 3, 5}) {
            final int count = pattern.length;
            final AtomicIntegerArray calls = new AtomicIntegerArray(Math.max(1, count));
            final IntFunction<String> measure = k -> { calls.incrementAndGet(k); return pattern[k] ? null : "frame " + k; };
            final List<String> parallel = ScamProcessor.firstMeasured(count, want, measure);
            final List<String> loop = sequential(count, want, k -> pattern[k] ? null : "frame " + k);
            assertEquals(Arrays.toString(pattern) + " want " + want, loop, parallel);
            for (int k = 0; k < count; k++) assertTrue("measured at most once", calls.get(k) <= 1);
        }
    }

    @Test public void anItemsFailureReachesTheCaller() {
        try {
            ScamProcessor.firstMeasured(3, 3, k -> { if (k == 1) throw new IllegalStateException("frame 1"); return "ok"; });
            fail("no exception");
        } catch (IllegalStateException expected) {
            assertEquals("frame 1", expected.getMessage());
        }
    }
}
