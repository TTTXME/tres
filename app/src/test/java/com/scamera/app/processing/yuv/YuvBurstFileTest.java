package com.scamera.app.processing.yuv;

import android.app.Application;

import com.scamera.app.processing.ImageFrame;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P690 (owner, 2026-11-14: «При включенном YUV бурст не сохраняется»): a YUV shot's series is written by the app itself. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class YuvBurstFileTest {
    private static ImageFrame frame(int w, int h, int seed, long exposure, int iso) {
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 3 / 2 * 2);
        for (int i = 0; i < b.capacity(); i++) b.put(i, (byte) (i * 7 + seed));
        final ImageFrame f = ImageFrame.wrap(b, w, h);
        f.yuvBits = 10;
        f.yuvRange = 1;
        f.measuredExposure = exposure;
        f.measuredIso = iso;
        f.timestamp = 1000L + seed;
        return f;
    }

    @Test public void theSeriesIsWrittenWithItsHeaderAndEveryFrame() throws Exception {
        final ImageFrame a = frame(16, 8, 1, 10_000_000L, 100), b = frame(16, 8, 2, 1_250_000L, 100);
        a.buffer.position(5); // a frame's position must not matter or move
        final File out = File.createTempFile("yuvseries", ".bin");
        assertTrue(YuvBurstFile.write(Arrays.asList(a, b), "3", out));
        assertEquals(5, a.buffer.position());
        final byte[] all = Files.readAllBytes(out.toPath());
        assertArrayEquals(YuvBurstFile.MAGIC, Arrays.copyOf(all, 8));
        final int len = ByteBuffer.wrap(all, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        final JSONObject h = new JSONObject(new String(all, 12, len, StandardCharsets.UTF_8));
        assertEquals(16, h.getInt("width"));
        assertEquals(8, h.getInt("height"));
        assertEquals(10, h.getInt("bits"));
        assertEquals(2, h.getInt("sampleBytes"));
        assertEquals("3", h.getString("camera"));
        assertEquals(2, h.getJSONArray("frames").length());
        assertEquals(1_250_000L, h.getJSONArray("frames").getJSONObject(1).getLong("exposureNs"));
        final int bytes = 16 * 8 * 3 / 2 * 2;
        assertEquals(12 + len + 2 * bytes, all.length);
        for (int i = 0; i < bytes; i++) {
            assertEquals(a.buffer.get(i), all[12 + len + i]);
            assertEquals(b.buffer.get(i), all[12 + len + bytes + i]);
        }
        //noinspection ResultOfMethodCallIgnored
        out.delete();
    }

    @Test public void nothingToWriteIsRefused() throws Exception {
        final File out = File.createTempFile("yuvseries", ".bin");
        assertFalse(YuvBurstFile.write(java.util.Collections.emptyList(), null, out));
        //noinspection ResultOfMethodCallIgnored
        out.delete();
    }
}
