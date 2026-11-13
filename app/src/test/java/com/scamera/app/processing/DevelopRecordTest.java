package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

/**
 * P665: the gallery developer's record appended to our DNG (DevelopRecord): its bytes and sections come back, the file before it
 * is untouched, a damaged / foreign / cut record is refused, a file without one reads as none.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DevelopRecordTest {
    static DevelopRecord sample() throws Exception {
        DevelopRecord r = new DevelopRecord();
        r.meta.put("v", DevelopRecord.VERSION);
        r.meta.put("params", new JSONObject().put("rawSize", new JSONObject().put("x", 8).put("y", 6)));
        r.meta.put("prefs", new JSONObject().put("pref_scam_hybrid_why_sharp_gain", new org.json.JSONArray().put("s").put("1.2")));
        DevelopRecord.Worker w = new DevelopRecord.Worker();
        w.bentoApplied = true;
        w.bentoFactor = 8f;
        w.effectiveMedian = 7.5f;
        w.normals = 9;
        w.report = "HYBRID MERGE FACTORS: 1:0.9";
        r.meta.put("worker", w.toJson());
        r.meta.put("dng", new JSONObject().put("headroom", 4.0).put("w", 8).put("h", 6));
        r.clipFlags = new byte[48];
        r.clipFlags[5] = 7;
        r.clipW = 8;
        r.clipH = 6;
        r.frames = new byte[48];
        for (int i = 0; i < 48; i++) r.frames[i] = (byte) (60 + i);
        r.framesW = 8;
        r.framesH = 6;
        r.hlMask = new byte[4 * 3 * 2];
        r.hlMask[3] = 0x3C;
        r.hlW = 4;
        r.hlH = 3;
        return r;
    }

    @Test public void bytesComeBack() throws Exception {
        DevelopRecord r = sample();
        DevelopRecord back = DevelopRecord.fromBytes(r.toBytes());
        assertNotNull(back);
        assertEquals(r.meta.toString(), back.meta.toString());
        assertArrayEquals(r.clipFlags, back.clipFlags);
        assertArrayEquals(r.frames, back.frames);
        assertArrayEquals(r.hlMask, back.hlMask);
        assertEquals(8, back.clipW);
        assertEquals(6, back.framesH);
        assertEquals(4, back.hlW);
        assertEquals(4f, back.headroom(), 0);
        DevelopRecord.Worker w = back.worker();
        assertTrue(w.bentoApplied);
        assertEquals(8f, w.bentoFactor, 0);
        assertEquals(7.5f, w.effectiveMedian, 0);
        assertEquals(9, w.normals);
        assertEquals("HYBRID MERGE FACTORS: 1:0.9", w.report);
    }

    @Test public void appendedAfterTheFileItLeavesAlone() throws Exception {
        File f = File.createTempFile("dev", ".dng");
        try {
            byte[] dng = new byte[4096];
            for (int i = 0; i < dng.length; i++) dng[i] = (byte) (i * 31);
            Files.write(f.toPath(), dng);
            assertNull("no record yet", DevelopRecord.read(f));
            DevelopRecord.append(f, sample());
            byte[] all = Files.readAllBytes(f.toPath());
            for (int i = 0; i < dng.length; i++) assertEquals(dng[i], all[i]);
            DevelopRecord back = DevelopRecord.read(f);
            assertNotNull(back);
            assertEquals(9, back.worker().normals);
            // a byte of the record damaged: refused (CRC)
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                raf.seek(dng.length + 40);
                int b = raf.read();
                raf.seek(dng.length + 40);
                raf.write(b ^ 0x55);
            }
            assertNull(DevelopRecord.read(f));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test public void foreignOrCutRecordsAreRefused() throws Exception {
        byte[] b = sample().toBytes();
        byte[] magic = b.clone();
        magic[0] = 'X';
        assertNull(DevelopRecord.fromBytes(magic));
        byte[] version = b.clone();
        version[7] = 99;
        assertNull(DevelopRecord.fromBytes(version));
        assertNull(DevelopRecord.fromBytes(java.util.Arrays.copyOf(b, b.length - 10)));
        assertNull(DevelopRecord.fromBytes(new byte[5]));
        // a map whose size does not match its bytes
        DevelopRecord r = sample();
        r.clipW = 9;
        assertNull(DevelopRecord.fromBytes(r.toBytes()));
    }

    @Test public void copyTakesTheBufferStart() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(10);
        for (int i = 0; i < 10; i++) b.put((byte) i);
        b.position(7);
        assertArrayEquals(new byte[]{0, 1, 2, 3}, DevelopRecord.copy(b, 4));
        assertEquals(7, b.position());
        assertNull(DevelopRecord.copy(b, 11));
        assertNull(DevelopRecord.copy(null, 4));
    }
}
