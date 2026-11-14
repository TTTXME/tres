package com.scamera.app.processing.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P616: the post pipeline's GPU pacing. A fake GPU runs the submitted blocks one after another, each {@code blockNs} long, on a
 * clock the pacer's sleeps advance: the queue never holds more than the limit, the wait is for the oldest fence, a finish waits for
 * everything, and the GPU never idles between blocks while the thread keeps up (the cost is only the poll's granularity).
 */
public class GpuPaceTest {
    /** A GPU that finishes block k at (k + 1) * blockNs after the first submission, unless it fell idle in between. */
    static class FakeGpu implements GpuPace.Gpu {
        final long blockNs;
        long now;
        long gpuFreeAt;
        long nextFence = 1;
        final Map<Long, Long> doneAt = new HashMap<>();
        final List<Long> deleted = new ArrayList<>();
        int finishes, polls;
        long idleNs;
        boolean noFences;

        FakeGpu(long blockNs) { this.blockNs = blockNs; }

        /** One block of work queued (what the pass did before its fence). */
        void queueBlock() {
            queueBlock(blockNs);
        }

        /** P668: one block of {@code ns} of GPU work queued. */
        void queueBlock(long ns) {
            if (gpuFreeAt < now) {
                idleNs += gpuFreeAt == 0 ? 0 : now - gpuFreeAt;
                gpuFreeAt = now;
            }
            gpuFreeAt += ns;
        }

        /** P668: the GPU work queued after {@code at} (what a viewfinder draw submitted then waits behind). */
        long aheadAt(long at) {
            return Math.max(0L, gpuFreeAt - at);
        }

        int queued() {
            int n = 0;
            for (Map.Entry<Long, Long> e : doneAt.entrySet()) if (!deleted.contains(e.getKey()) && e.getValue() > now) n++;
            return n;
        }

        @Override public long fence() {
            if (noFences) return 0;
            final long f = nextFence++;
            doneAt.put(f, Math.max(now, gpuFreeAt));
            return f;
        }
        @Override public boolean signalled(long fence) { polls++; return doneAt.get(fence) <= now; }
        @Override public void delete(long fence) { deleted.add(fence); }
        @Override public void finish() { finishes++; now = Math.max(now, gpuFreeAt); }
        @Override public void pause(long ns) { now += ns; }
        @Override public long nanoTime() { return now; }
    }

    @Test
    public void theQueueNeverHoldsMoreThanTheLimitAndTheGpuNeverIdles() {
        final FakeGpu gpu = new FakeGpu(10_000_000L); // 10 ms blocks (the resolve's 4096 x 256 on the X200 Ultra, estimated)
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 2);
        int maxQueued = 0;
        for (int i = 0; i < 48; i++) { // ScamHdrDenoise: 2 strips x 3 passes x 8 blocks
            gpu.now += 200_000L; // the CPU's encoding of a block
            gpu.queueBlock();
            p.submitted();
            maxQueued = Math.max(maxQueued, gpu.queued());
            assertTrue("pending fences within the limit", p.pendingCount() <= 2);
        }
        assertTrue("at most the limit's blocks ahead of a viewfinder draw: " + maxQueued, maxQueued <= 2);
        assertEquals("the GPU had the next block queued every time", 0, gpu.idleNs);
        assertEquals(48, p.fences);
        // the thread's time is the GPU's time of the blocks less the last two still running
        assertTrue(gpu.now <= 46 * 10_000_000L + 2 * GpuPace.POLL_NS + 200_000L);
        p.close();
        assertEquals("every fence deleted", 48, gpu.deleted.size());
    }

    @Test
    public void withoutPacingTheQueueIsAsDeepAsTheCpuIsFast() {
        // the case of the 31254 log: the CPU queues 48 blocks in ~10 ms, the viewfinder's draw waits behind ~480 ms of them
        final FakeGpu gpu = new FakeGpu(10_000_000L);
        int maxQueued = 0;
        for (int i = 0; i < 48; i++) {
            gpu.now += 200_000L;
            gpu.queueBlock();
            gpu.doneAt.put(gpu.nextFence++, gpu.gpuFreeAt);
            maxQueued = Math.max(maxQueued, gpu.queued());
        }
        assertTrue(String.valueOf(maxQueued), maxQueued >= 40);
    }

    @Test
    public void aFinishWaitsForEverythingByPollingAndDropsTheOlderFences() {
        final FakeGpu gpu = new FakeGpu(5_000_000L);
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 3);
        for (int i = 0; i < 3; i++) { gpu.queueBlock(); p.submitted(); }
        gpu.queueBlock(); // a dispatch, then computeManual's finish
        p.finish();
        assertEquals("all four blocks done when the finish returns", 20_000_000L, gpu.now, GpuPace.POLL_NS);
        assertEquals("no driver glFinish: a polled fence", 0, gpu.finishes);
        assertEquals(0, p.pendingCount());
        assertEquals(1, p.finishes);
    }

    @Test
    public void noFenceMeansNoPacingAndTheDriversFinish() {
        final FakeGpu gpu = new FakeGpu(5_000_000L);
        gpu.noFences = true;
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 1);
        for (int i = 0; i < 10; i++) { gpu.queueBlock(); p.submitted(); }
        assertEquals("nothing tracked, nothing waited", 0, p.fences);
        assertEquals(0, gpu.now);
        p.finish();
        assertEquals("the driver's glFinish", 1, gpu.finishes);
    }

    @Test
    public void aFenceThatNeverSignalsIsGivenUp() {
        final FakeGpu gpu = new FakeGpu(1_000_000L) {
            @Override public boolean signalled(long fence) { polls++; return false; }
        };
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 1);
        gpu.queueBlock(); p.submitted();
        gpu.queueBlock(); p.submitted();
        assertEquals(1, p.givenUp);
        assertTrue(gpu.now > GpuPace.GIVE_UP_NS && gpu.now < GpuPace.GIVE_UP_NS + 10 * GpuPace.POLL_NS);
        assertTrue(p.summary(), p.summary().contains("given up"));
        p.finish();
        assertEquals("a finish whose fence is given up still finishes (the driver's)", 1, gpu.finishes);
    }

    @Test
    public void theLimitComesFromTheSettingOrTheGpu() {
        assertEquals("absent: Adreno paced", GpuPace.DEFAULT_IN_FLIGHT, GpuPace.inFlightFor(Float.NaN, "Adreno (TM) 830"));
        assertEquals("absent: Mali as before", 0, GpuPace.inFlightFor(Float.NaN, "Mali-G925-Immortalis MC12"));
        assertEquals("absent: no renderer", 0, GpuPace.inFlightFor(Float.NaN, null));
        assertEquals("0: off", 0, GpuPace.inFlightFor(0f, "Adreno (TM) 830"));
        assertEquals("N on any GPU", 3, GpuPace.inFlightFor(3f, "Mali-G710"));
        assertEquals("capped", 64, GpuPace.inFlightFor(1000f, "Adreno"));
    }

    /**
     * P668: GLProg.drawBlocks' loop over one pass on the fake GPU - each band's rows from the pacer, its GPU time {@code nsPerPixel}
     * per pixel, a little CPU between bands. Returns the rows of every band; {@code maxAhead[0]}: the most GPU work queued ahead of
     * a viewfinder draw once the first {@code settle} bands went.
     */
    private static java.util.List<Integer> pass(FakeGpu gpu, GpuPace.Pacer p, String name, int w, int h, double nsPerPixel, int settle,
                                                long[] maxAhead) {
        final java.util.List<Integer> rows = new ArrayList<>();
        for (int y = 0; y < h; ) {
            final int r = Math.min(h - y, p.bandRows(name, w, 256));
            gpu.now += 100_000L; // the CPU's draw call, flush and fence
            gpu.queueBlock((long) (nsPerPixel * w * r));
            p.submitted(name, (long) w * r);
            if (rows.size() >= settle) maxAhead[0] = Math.max(maxAhead[0], gpu.aheadAt(gpu.now));
            rows.add(r);
            y += r;
        }
        return rows;
    }

    @Test
    public void aSlowPassGetsNarrowerBandsAndTheViewfinderWaitsLess() {
        GpuPace.forgetBands();
        final FakeGpu gpu = new FakeGpu(0L);
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 2, 10f);
        final long[] ahead = {0L};
        // the Xiaomi 12X's combine: a 4096 x 256 band ~100 ms (31266: «slow draw 311 ms», pace max wait 140 ms)
        final java.util.List<Integer> rows = pass(gpu, p, "why/combine", 4096, 3072, 100.0, 6, ahead);
        assertEquals("the first bands before a measurement: 256 rows", Integer.valueOf(256), rows.get(0));
        int sum = 0, y = 0;
        for (int i = 0; i < rows.size(); i++) {
            assertEquals("band " + i + " starts on a 16-row boundary", 0, y % 16);
            y += rows.get(i);
            sum += rows.get(i);
        }
        assertEquals("every row drawn once", 3072, sum);
        // 10 ms / (100 ns x 4096 px) = 24.4 rows -> 16 (a multiple of 16)
        assertEquals("narrowed to the 16-row step", Integer.valueOf(16), rows.get(rows.size() - 1));
        // band 0 is not measured (nothing seen done before it: its start is unknown), band 1 is when band 3 waits for it
        assertEquals("256-row bands until the first measurement: " + rows.subList(0, 6), Integer.valueOf(256), rows.get(3));
        assertEquals("narrowed from the fifth band on: " + rows.subList(0, 6), Integer.valueOf(16), rows.get(4));
        assertTrue("then at most two ~6.6 ms bands ahead of a viewfinder draw: " + ahead[0] / 1e6 + " ms", ahead[0] <= 2 * 6_600_000L + 200_000L);
        assertEquals("the GPU never idled between bands", 0, gpu.idleNs);
        // the next shot's same pass starts narrow
        final java.util.List<Integer> next = pass(gpu, p, "why/combine", 4096, 3072, 100.0, 0, new long[1]);
        assertEquals("measured once, narrow from the first band of the next run", Integer.valueOf(16), next.get(0));
        final String s = p.summary();
        assertTrue(s, s.contains("bands of 10 ms") && s.contains("narrowed: why/combine 256->16 rows x"));
    }

    @Test
    public void aFastPassKeepsItsBands() {
        GpuPace.forgetBands();
        final FakeGpu gpu = new FakeGpu(0L);
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 2, 10f);
        // the Adreno 830's combine: a 4096 x 256 band ~8 ms
        final java.util.List<Integer> rows = pass(gpu, p, "why/combine", 4096, 3072, 7.5, 0, new long[1]);
        for (int r : rows) assertEquals(256, r);
        assertTrue(p.summary(), p.summary().contains("none narrowed"));
        // 30 ms bands on a 256-row pass: 10 ms / (30 ns x 4096) = 81 rows -> 80
        final java.util.List<Integer> mid = pass(gpu, p, "why/stats", 4096, 3072, 30.0, 0, new long[1]);
        assertEquals(Integer.valueOf(80), mid.get(mid.size() - 2));
    }

    @Test
    public void theMeasurementIsNeverMoreThanTheBandsGpuTime() {
        GpuPace.forgetBands();
        final FakeGpu gpu = new FakeGpu(0L);
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 2, 10f);
        // the CPU sometimes slower than the GPU (idle gaps): what is measured is a lower bound of 50 ns per pixel
        for (int i = 0; i < 40; i++) {
            gpu.now += (i % 3 == 0) ? 30_000_000L : 100_000L;
            gpu.queueBlock(50L * 4096 * 64);
            p.submitted("scamdn/x", 4096L * 64);
            final Float m = GpuPace.measuredNsPerPixel("scamdn/x");
            // a lower bound up to the poll's granularity (the fence is seen at most POLL_NS after it signalled)
            if (m != null) assertTrue("measured " + m + " ns/px", m * 4096.0 * 64 <= 50.0 * 4096 * 64 + GpuPace.POLL_NS + 1);
        }
        assertTrue("measured at all", GpuPace.measuredNsPerPixel("scamdn/x") != null);
        assertTrue(p.measured > 0);
    }

    @Test
    public void bandsAsAskedWithoutPacingSizingOrRoom() {
        GpuPace.forgetBands();
        final FakeGpu gpu = new FakeGpu(0L);
        final GpuPace.Pacer sized = new GpuPace.Pacer(gpu, 2, 10f);
        pass(gpu, sized, "why/combine", 4096, 3072, 100.0, 0, new long[1]);
        assertEquals("not paced (no pacer on this thread): as asked", 256, GpuPace.bandRows("why/combine", 4096, 256));
        assertEquals("post_band_ms 0: as asked", 256, new GpuPace.Pacer(gpu, 2, 0f).bandRows("why/combine", 4096, 256));
        assertEquals("8-row tiles (SMALL_PIXELS) as they are", 8, sized.bandRows("why/combine", 4096, 8));
        assertEquals("no measurement of that program: as asked", 256, sized.bandRows("other", 4096, 256));
        assertEquals("unnamed program: as asked", 256, sized.bandRows(null, 4096, 256));
        assertEquals("absent: 10 ms", GpuPace.DEFAULT_BAND_MS, GpuPace.bandMsFor(Float.NaN), 0f);
        assertEquals("0: off", 0f, GpuPace.bandMsFor(0f), 0f);
        assertEquals(5f, GpuPace.bandMsFor(5f), 0f);
    }

    @Test
    public void theSummaryCarriesTheCost() {
        final FakeGpu gpu = new FakeGpu(10_000_000L);
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 2);
        for (int i = 0; i < 6; i++) { gpu.queueBlock(); p.submitted(); }
        final String s = p.summary();
        assertTrue(s, s.startsWith("post GPU pace: at most 2 blocks in flight, 6 fences, 4 waits "));
        assertTrue(s, s.contains("0 finishes"));
    }

    /**
     * P685: while the viewfinder is paused (the gallery, the settings) the post goes unpaced - no fence, no wait, its bands as asked;
     * from the next block after it draws again the pace is as before.
     */
    @Test
    public void aPausedViewfinderLeavesThePostUnpaced() {
        GpuPace.forgetBands();
        final FakeGpu gpu = new FakeGpu(10_000_000L);
        final boolean[] hidden = {true};
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 2, 10f, () -> hidden[0]);
        for (int i = 0; i < 20; i++) { gpu.now += 200_000L; gpu.queueBlock(); p.submitted("why/combine", 4096L * 256); }
        assertEquals("no fence while paused", 0, p.fences);
        assertEquals(0, p.waits);
        assertEquals(20, p.unpaced);
        assertEquals("bands as asked", 256, p.bandRows("why/combine", 4096, 256));
        assertTrue(p.summary(), p.summary().contains("20 blocks unpaced"));
        hidden[0] = false;
        int maxQueued = 0;
        for (int i = 0; i < 20; i++) {
            gpu.now += 200_000L;
            gpu.queueBlock();
            p.submitted("why/combine", 4096L * 256);
            maxQueued = Math.max(maxQueued, gpu.queued());
        }
        assertTrue("paced again: fences", p.fences >= 20);
        assertTrue("paced again: waits", p.waits > 0);
        assertTrue("paced again: at most 2 blocks queued", maxQueued <= 2);
        GpuPace.forgetBands();
    }
}

