package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * P371 (worker_prespawn): the lines of a worker spawned at the shutter reach its job in their order - those before the job
 * took it first, then every later one - and the end of its output once, whenever it came.
 */
public class WorkerStandbyTest {
    @Test public void earlyLinesComeFirstThenTheLaterOnesInOrder() throws Exception {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        // fewer than EARLY_LIMIT in all: whenever the job attaches (the reader goes on beside it), none may be lost
        final int total = ScamNeuralClient.LineRoute.EARLY_LIMIT - 100;
        final CountDownLatch half = new CountDownLatch(1);
        final Thread reader = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                route.line("line " + i);
                if (i == 50) half.countDown();
            }
            route.ended();
        });
        reader.start();
        assertTrue(half.await(5, TimeUnit.SECONDS));
        final List<String> got = new ArrayList<>();
        final AtomicInteger ends = new AtomicInteger();
        final CountDownLatch end = new CountDownLatch(1);
        route.attach(l -> { synchronized (got) { got.add(l); } }, () -> { ends.incrementAndGet(); end.countDown(); });
        assertTrue(end.await(10, TimeUnit.SECONDS));
        reader.join(5000);
        assertEquals(1, ends.get());
        synchronized (got) {
            // every line once, in its order (those before the attach first)
            assertEquals(total, got.size());
            for (int i = 0; i < total; i++) assertEquals("line " + i, got.get(i));
        }
    }

    @Test public void beyondTheEarlyLimitOnlyTheFirstLinesAreKeptThenEveryLaterOne() {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        final int limit = ScamNeuralClient.LineRoute.EARLY_LIMIT;
        for (int i = 0; i < limit + 10; i++) route.line("line " + i);
        final List<String> got = new ArrayList<>();
        route.attach(got::add, () -> { });
        for (int i = limit + 10; i < limit + 15; i++) route.line("line " + i);
        assertEquals(limit + 5, got.size());
        for (int i = 0; i < limit; i++) assertEquals("line " + i, got.get(i));
        for (int i = 0; i < 5; i++) assertEquals("line " + (limit + 10 + i), got.get(limit + i));
    }

    @Test public void anOutputThatEndedBeforeTheJobEndsAtOnceAtAttach() {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        route.line("SCAM Neural native executable v31");
        route.line("STOP: SCAM burst was not delivered by the app");
        route.ended();
        final List<String> got = new ArrayList<>();
        final AtomicInteger ends = new AtomicInteger();
        route.attach(got::add, ends::incrementAndGet);
        assertEquals(2, got.size());
        assertEquals("STOP: SCAM burst was not delivered by the app", got.get(1));
        assertEquals(1, ends.get());
    }

    @Test public void aDiscardedStandbyGivesItsLinesForTheLog() {
        final ScamNeuralClient.LineRoute route = new ScamNeuralClient.LineRoute();
        for (int i = 0; i < ScamNeuralClient.LineRoute.EARLY_LIMIT + 10; i++) route.line("HYBRID PROGRESS " + i);
        final List<String> dropped = route.drop();
        assertEquals(ScamNeuralClient.LineRoute.EARLY_LIMIT, dropped.size());
        assertEquals("HYBRID PROGRESS 0", dropped.get(0));
        assertTrue(route.drop().isEmpty());
    }
    /**
     * P602: a worker spawned at the shutter reads the keys it needs before its go from its folder; they are the last value of each
     * pre-go key of the tuning text as the worker's own loop reads it ("key value" pairs, a later pair wins, the read stops at a value
     * that is not a number), and a job whose tuning gives other lines does not take that worker.
     */
    @Test public void theTuningReadBeforeTheGoIsTheLastValueOfEachPreGoKey() {
        assertEquals("", ScamNeuralClient.preGoTuning(null));
        assertEquals("", ScamNeuralClient.preGoTuning(""));
        assertEquals("", ScamNeuralClient.preGoTuning("cdm 0.2\nboost 6.0\n"));
        // the settings' lines of the Pixel 7 shot (laGpu 2, localAlign 1), then P474's fast tuning (fastMerge 1 last)
        final String fast = "rejPool 0\nrejVarExact 0\nlaStride 16\nlaIters 0\nfastMerge 1\n";
        assertEquals("fastMerge 1\nlaGpu 2.0\nlocalAlign 1.0\n",
                ScamNeuralClient.preGoTuning("cdm 0.2\nlocalAlign 1.0\nlaGpu 2.0\nfastMerge 0\n" + fast));
        // a later pair wins; the read stops at a value that is no number (what follows is not read by the worker either)
        assertEquals("fastMerge 0\n", ScamNeuralClient.preGoTuning("fastMerge 1\nfastMerge 0\nbad x\nlaGpu 2\n"));
        // every key in the worker's order, whatever the order of the lines
        assertEquals("fastMerge 1\ncreLazy 0\ngpuAlign 1\nlaGpu 0\nlocalAlign 0\n",
                ScamNeuralClient.preGoTuning("localAlign 0 laGpu 0 gpuAlign 1 creLazy 0 fastMerge 1"));
        // the burst's own lines that hold no pre-go key change nothing: the job takes the worker
        final String settings = "laGpu 2\nlocalAlign 1\n" + fast;
        assertEquals(ScamNeuralClient.preGoTuning(settings),
                ScamNeuralClient.preGoTuning(settings + "mosaicRemosaic 1\nusSceneEv 13.70\nwbR 0.45898\nwbB 0.68652\n"));
        // a retry's conservative lines do (localAlign 0): that job would not take it (a retry never does anyway)
        assertNotEquals(ScamNeuralClient.preGoTuning(settings), ScamNeuralClient.preGoTuning(settings + "localAlign 0\nsabre61 0\n"));
    }
}
