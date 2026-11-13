package com.scamera.app.util;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BatchedLogWriterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void batchesRealFileWritesAndFlushesBeforeShareAndClear() throws Exception {
        File path = temp.newFile("session.txt");
        java.io.BufferedWriter file = Files.newBufferedWriter(path.toPath());
        AtomicInteger writes = new AtomicInteger();
        BatchedLogWriter queue = new BatchedLogWriter(new BatchedLogWriter.Sink() {
            public void write(String s) throws Exception { writes.incrementAndGet(); file.write(s); }
            public void flush() throws Exception { file.flush(); }
        });
        for (int i = 0; i < 1000; i++) queue.append(i + "\n");
        CountDownLatch done = new CountDownLatch(1);
        File snapshot = new File(temp.getRoot(), "shared.txt");
        assertTrue(queue.afterFlush(() -> {
            try {
                Files.copy(path.toPath(), snapshot.toPath());
                file.close();
                Files.write(path.toPath(), new byte[0]);
            } catch (Exception e) { throw new AssertionError(e); }
            finally { done.countDown(); }
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        String text = new String(Files.readAllBytes(snapshot.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("0\n1\n"));
        assertTrue(text.endsWith("998\n999\n"));
        assertEquals(1000, text.lines().count());
        assertEquals(0, path.length());
        assertTrue("not one write/flush task per record", writes.get() < 20);
    }

    @Test public void stalledStorageCannotBlockCallerOrGrowQueueWithoutBound() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        StringBuilder saved = new StringBuilder();
        BatchedLogWriter queue = new BatchedLogWriter(new BatchedLogWriter.Sink() {
            public void write(String s) throws Exception { entered.countDown(); release.await(); saved.append(s); }
            public void flush() {}
        });
        queue.append("first\n");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 10000; i++) queue.append("queued " + i + "\n");
        assertTrue(queue.pendingRecords() <= BatchedLogWriter.MAX_RECORDS);
        assertTrue(queue.pendingChars() <= BatchedLogWriter.MAX_CHARS);
        assertTrue(queue.afterFlush(done::countDown));
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(saved.toString().contains("log queue overflow: dropped"));
        assertTrue(saved.toString().contains("first\n"));
        assertEquals(0, queue.pendingRecords());
    }

    @Test public void failedStorageRecoversWithoutLosingTheWriterThread() throws Exception {
        CountDownLatch failed = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        StringBuilder saved = new StringBuilder();
        BatchedLogWriter queue = new BatchedLogWriter(new BatchedLogWriter.Sink() {
            public void write(String text) throws Exception {
                if (attempts.getAndIncrement() == 0) { failed.countDown(); throw new java.io.IOException("unavailable"); }
                saved.append(text);
            }
            public void flush() {}
        });
        queue.append("unavailable record\n");
        assertTrue(failed.await(5, TimeUnit.SECONDS));
        queue.append("recovered\n");
        assertTrue(queue.afterFlush(done::countDown));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(saved.toString().contains("storage unavailable: lost 1 records"));
        assertTrue(saved.toString().endsWith("recovered\n"));
    }

    @Test public void oversizedRecordHasAnExplicitTruncationMarker() throws Exception {
        StringBuilder saved = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        BatchedLogWriter queue = new BatchedLogWriter(new BatchedLogWriter.Sink() {
            public void write(String text) { saved.append(text); }
            public void flush() {}
        });
        queue.append("x".repeat(BatchedLogWriter.MAX_RECORD_CHARS + 1000));
        assertTrue(queue.afterFlush(done::countDown));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(saved.toString().endsWith("[log record truncated]\n"));
        assertTrue(saved.length() < BatchedLogWriter.MAX_RECORD_CHARS + 100);
    }

    @Test public void repeatedUiControlsAreAlsoBoundedDuringAStorageStall() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(32);
        BatchedLogWriter queue=new BatchedLogWriter(new BatchedLogWriter.Sink() {
            public void write(String text) throws Exception { entered.countDown(); release.await(); }
            public void flush() {}
        });
        queue.append("stalled\n");
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        for(int i=0;i<32;i++) assertTrue(queue.afterFlush(done::countDown));
        assertFalse(queue.afterFlush(() -> fail("must reject excess controls")));
        release.countDown();
        assertTrue(done.await(5,TimeUnit.SECONDS));
    }

    @Test public void shutdownFlushCannotBeRejectedByAFullControlQueue() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean enabled=new java.util.concurrent.atomic.AtomicBoolean(true);
        BatchedLogWriter queue=new BatchedLogWriter(new BatchedLogWriter.Sink() {
            public void write(String text) throws Exception { entered.countDown(); release.await(); }
            public void flush() { if(!enabled.get()) closed.countDown(); }
        });
        queue.append("stalled\n");assertTrue(entered.await(5,TimeUnit.SECONDS));
        for(int i=0;i<32;i++) assertTrue(queue.afterFlush(() -> {}));
        assertFalse(queue.afterFlush(() -> fail("control queue must remain bounded")));
        enabled.set(false);
        queue.flushSoon();
        release.countDown();
        assertTrue(closed.await(5,TimeUnit.SECONDS));
    }
}
