package com.scamera.app.manual;

import org.junit.Test;

import static org.junit.Assert.*;

public class UnspektraAeTest {
    private static final UnspektraAe.Bounds BOUNDS = new UnspektraAe.Bounds(72, 6400, 20_000, 33_333_333);

    private static UnspektraAe.Exposure sample(UnspektraAe ae, double ev,
            UnspektraAe.Exposure measured, long at) {
        return ae.update(ev, measured, at, ae.generation(), ae.meterRevision(), BOUNDS,
                0, 0, 72, 50f, measured.iso, true);
    }

    @Test public void convergesFromDarkSceneWithoutLeavingLensLimits() {
        UnspektraAe ae = new UnspektraAe();
        UnspektraAe.Exposure measured = new UnspektraAe.Exposure(100, 10_000_000);
        ae.reset(UnspektraAe.Mode.AUTO, measured);
        for (int frame = 0; frame < 400; frame++) {
            double product = measured.iso * (double) measured.timeNs;
            double ev = Math.log(2_000_000_000. / product) / Math.log(2);
            UnspektraAe.Exposure next = sample(ae, ev, measured, 1_000_000_000L + frame * 33_333_333L);
            if (next != null) {
                assertTrue(next.iso >= BOUNDS.minIso && next.iso <= BOUNDS.maxIso);
                assertTrue(next.timeNs >= BOUNDS.minTimeNs && next.timeNs <= BOUNDS.maxTimeNs);
                assertTrue(Math.abs(Math.log((double) next.iso / measured.iso) / Math.log(2)) < .22);
                assertTrue(Math.abs(Math.log((double) next.timeNs / measured.timeNs) / Math.log(2)) < .22);
                measured = next;
            }
        }
        assertEquals(0, Math.log(measured.iso * (double) measured.timeNs / 2_000_000_000.) / Math.log(2), .06);
    }

    @Test public void priorityKeepsTheManualParameter() {
        for (UnspektraAe.Mode mode : new UnspektraAe.Mode[]{UnspektraAe.Mode.ISO_PRIORITY,
                UnspektraAe.Mode.SHUTTER_PRIORITY}) {
            UnspektraAe ae = new UnspektraAe();
            UnspektraAe.Exposure measured = new UnspektraAe.Exposure(400, 5_000_000);
            ae.reset(mode, measured);
            for (int frame = 0; frame < 120; frame++) {
                UnspektraAe.Exposure next = sample(ae, 1, measured, 1_000_000_000L + frame * 33_333_333L);
                if (next != null) {
                    if (mode == UnspektraAe.Mode.ISO_PRIORITY) assertEquals(400, next.iso);
                    else assertEquals(5_000_000, next.timeNs);
                    measured = next;
                }
            }
        }
    }

    @Test public void manualAndObsoleteMeasurementsCannotChangeExposure() {
        UnspektraAe ae = new UnspektraAe();
        UnspektraAe.Exposure e = new UnspektraAe.Exposure(100, 10_000_000);
        long oldSession = ae.reset(UnspektraAe.Mode.AUTO, e);
        long oldMeter = ae.meterRevision();
        ae.resetMeter();
        assertNull(ae.update(2, e, 1_000_000_000, oldSession, oldMeter, BOUNDS, 0, 0, 72, 50f, 100, true));
        ae.reset(UnspektraAe.Mode.AUTO, e);
        assertNull(ae.update(2, e, 1_000_000_000, oldSession, ae.meterRevision(), BOUNDS, 0, 0, 72, 50f, 100, true));
        assertNotNull(sample(ae, 2, e, 1_000_000_000));
        assertNull(sample(ae, 2, e, 1_000_000_000));
        assertNull(sample(ae, 2, e, 999_000_000));
        assertNull(sample(ae, 2, e, 1_008_000_000));
        ae.reset(UnspektraAe.Mode.MANUAL, e);
        assertNull(sample(ae, 2, e, 2_000_000_000));
    }

    @Test public void invalidStatisticsDoNotPoisonTheNextFrame() {
        UnspektraAe ae = new UnspektraAe();
        UnspektraAe.Exposure e = new UnspektraAe.Exposure(100, 10_000_000);
        ae.reset(UnspektraAe.Mode.AUTO, e);
        assertNull(sample(ae, Double.NaN, e, 1_000_000_000));
        assertNull(sample(ae, Double.POSITIVE_INFINITY, e, 1_000_000_000));
        assertNotNull(sample(ae, 2, e, 1_000_000_000));
        assertTrue(Double.isFinite(ae.errorEv()));
    }

    @Test public void boundedSceneReportsUnattainableExposure() {
        UnspektraAe ae = new UnspektraAe();
        UnspektraAe.Exposure e = new UnspektraAe.Exposure(6400, 33_333_333);
        ae.reset(UnspektraAe.Mode.AUTO, e);
        sample(ae, 8, e, 1_000_000_000);
        assertTrue(ae.limited());
        assertEquals(8, ae.errorEv(), 0);
    }
}
