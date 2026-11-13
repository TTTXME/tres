package com.scamera.app.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** P662: the per-frame stabilisation trace (STAB_TRACE) runs only with the debug log on (or scam_dev stab_trace 1). */
@RunWith(RobolectricTestRunner.class)
public class StabilizationTraceGateTest {
    @After public void tearDown() {
        com.scamera.app.util.ScameraDebugLog.setEnabled(false);
    }

    @Test
    public void withoutTheDebugLogTheTraceRecordsNothing() {
        com.scamera.app.util.ScameraDebugLog.setEnabled(false);
        assertFalse(StabilizationTrace.traceOn());
        final StabilizationTrace trace = new StabilizationTrace();
        trace.startSession("0", null);
        assertFalse(trace.enabled());
        trace.markShot("submit");
        assertFalse("a shot does not turn it on", trace.enabled());
        trace.record(null, null); // returns before any read of the request or the result
    }

    @Test
    public void theDebugLogTurnsItOn() {
        com.scamera.app.util.ScameraDebugLog.setEnabled(true);
        assertTrue(StabilizationTrace.traceOn());
        final StabilizationTrace trace = new StabilizationTrace();
        trace.startSession("0", null);
        assertTrue(trace.enabled());
    }
}
