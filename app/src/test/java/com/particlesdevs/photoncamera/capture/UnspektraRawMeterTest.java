package com.particlesdevs.photoncamera.capture;

import com.particlesdevs.photoncamera.manual.UnspektraMeter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class UnspektraRawMeterTest {
    private static final int[][] SITES = {{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
    private static final float[] ID = {1,0,0,0,1,0,0,0,1};

    @Test public void colourSitesPaddingLevelsAndNativeMosaics() {
        for (int cfa = 0; cfa < 4; cfa++) for (int block : new int[]{1,2,4}) {
            int w = 64, h = 48, stride = w * 2 + 16;
            ByteBuffer raw = ByteBuffer.allocate(stride * (h - 1) + w * 2).order(ByteOrder.nativeOrder());
            float[] black = {32,48,64,80};
            int[] signal = {200,300,400,500};
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int site = (y / block % 2) * 2 + x / block % 2;
                int channel = 0;
                while (SITES[cfa][channel] != site) channel++;
                raw.putShort(y * stride + x * 2, (short)(signal[channel] + black[site]));
            }
            float white = 1023;
            float[] gains = {2,1,1.25f};
            float[] expected = new float[3];
            for (int channel = 0; channel < 4; channel++) {
                float value = signal[channel] / (white - black[SITES[cfa][channel]]);
                if (channel == 0) expected[0] = value * gains[0];
                else if (channel == 3) expected[2] = value * gains[2];
                else expected[1] += value * .5f;
            }
            NativeRawExposure.MeterSetup setup = new NativeRawExposure.MeterSetup();
            double ev = UnspektraRawMeter.measure(raw, w, h, stride, cfa, block, black, white, gains, ID, setup,
                    UnspektraMeter.Method.CENTRE_WEIGHTED);
            double y = .2126 * expected[0] + .7152 * expected[1] + .0722 * expected[2];
            assertEquals("CFA " + cfa + " block " + block, -Math.log(y * 5.4347825050354) / Math.log(2), ev, 2e-7);
            assertEquals(0, raw.position());
        }
    }

    @Test public void cropShadingAndRowMajorColourTransform() {
        int w = 32, h = 24;
        ByteBuffer raw = ByteBuffer.allocate(w * h * 2).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            raw.putShort((y * w + x) * 2, (short)(64 + (x < w / 2 ? 100 : 400)));
        NativeRawExposure.MeterSetup setup = new NativeRawExposure.MeterSetup();
        setup.left = .5f;
        setup.gainCols = setup.gainRows = 1;
        setup.gains = new float[]{2,3,3,4};
        float[] ccm = {1,.1f,.2f,.3f,1,.4f,.5f,.6f,1};
        float[] base = {2,3,4};
        double y = 0;
        float[] weights = {.2126f,.7152f,.0722f};
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            y += weights[row] * ccm[row * 3 + col] * base[col] * 400 / 959;
        double ev = UnspektraRawMeter.measure(raw, w, h, w * 2, 0, 1, new float[]{64,64,64,64}, 1023,
                new float[]{1,1,1}, ccm, setup, UnspektraMeter.Method.MEDIAN);
        assertEquals(-Math.log(y * 5.4347825050354) / Math.log(2), ev, 2e-7);
    }

    @Test public void invalidSamplesNeverBecomeAValidExposureMeasurement() {
        NativeRawExposure.MeterSetup setup = new NativeRawExposure.MeterSetup();
        assertTrue(Double.isNaN(UnspektraRawMeter.measure(ByteBuffer.allocate(4), 8, 8, 16, 0, 1,
                new float[4], 1023, new float[]{1,1,1}, ID, setup, UnspektraMeter.Method.MEDIAN)));
        assertTrue(Double.isNaN(UnspektraRawMeter.measure(ByteBuffer.allocate(128), 8, 8, 16, 0, 1,
                new float[4], 1023, new float[]{Float.NaN,1,1}, ID, setup, UnspektraMeter.Method.MEDIAN)));
    }
}
