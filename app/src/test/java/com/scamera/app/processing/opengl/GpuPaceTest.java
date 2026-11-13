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
            if (gpuFreeAt < now) {
                idleNs += gpuFreeAt == 0 ? 0 : now - gpuFreeAt;
                gpuFreeAt = now;
            }
            gpuFreeAt += blockNs;
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

    @Test
    public void theSummaryCarriesTheCost() {
        final FakeGpu gpu = new FakeGpu(10_000_000L);
        final GpuPace.Pacer p = new GpuPace.Pacer(gpu, 2);
        for (int i = 0; i < 6; i++) { gpu.queueBlock(); p.submitted(); }
        final String s = p.summary();
        assertTrue(s, s.startsWith("post GPU pace: at most 2 blocks in flight, 6 fences, 4 waits "));
        assertTrue(s, s.contains("0 finishes"));
    }
}
