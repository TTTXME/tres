package com.particlesdevs.photoncamera.processing.render;

import android.util.Pair;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** P473: a HAL noise offset in sensor units (Nubia / ZTE) is brought to the [0, 1] scale; a normal one is kept. */
@RunWith(RobolectricTestRunner.class)
public class NoiseModelerHalOffsetTest {
    @Test public void normalisedOffsetsAreKept() {
        assertEquals(2.2e-6, NoiseModeler.halOffset(2.2e-6, 1023), 0);
        assertEquals(4e-4, NoiseModeler.halOffset(4e-4, 1023), 0);
        assertEquals(0.01, NoiseModeler.halOffset(0.01, 1023), 0);
        assertEquals("no white level: as reported", 0.0256, NoiseModeler.halOffset(0.0256, 0), 0);
        assertEquals(Double.NaN, NoiseModeler.halOffset(Double.NaN, 1023), 0);
    }

    @Test public void sensorUnitOffsetsAreDividedByTheWhiteLevel() {
        assertEquals(0.0256 / 1023, NoiseModeler.halOffset(0.0256, 1023), 1e-15);
        assertEquals(26.0 / (1023.0 * 1023.0), NoiseModeler.halOffset(26.0, 1023), 1e-15);
    }

    @SuppressWarnings("unchecked")
    @Test public void profileArrayOnlyChangesWhenAnOffsetDoes() {
        Pair<Double, Double>[] good = new Pair[]{new Pair<>(1.2e-3, 2e-6), new Pair<>(1.2e-3, 2e-6)};
        assertSame(good, NoiseModeler.sanitizedOffsets(good, 1023));
        Pair<Double, Double>[] bad = new Pair[]{new Pair<>(1.2e-3, 0.0256), new Pair<>(1.2e-3, 2e-6)};
        Pair<Double, Double>[] fixed = NoiseModeler.sanitizedOffsets(bad, 1023);
        assertEquals(0.0256 / 1023, fixed[0].second, 1e-15);
        assertEquals(1.2e-3, fixed[0].first, 0);
        assertSame(bad[1], fixed[1]);
        assertEquals("the input is not changed", 0.0256, bad[0].second, 0);
        assertEquals(null, NoiseModeler.sanitizedOffsets(null, 1023));
    }
}
