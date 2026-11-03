package com.particlesdevs.photoncamera.util;

import java.util.ArrayDeque;

/** One bounded queue; storage never runs on a capture, GL or UI thread. */
final class BatchedLogWriter {
    interface Sink {
        void write(String text) throws Exception;
        void flush() throws Exception;
    }

    static final int MAX_RECORDS = 4096;
    static final int MAX_CHARS = 512 * 1024;
    static final int MAX_RECORD_CHARS = 128 * 1024;
    private static final int BATCH_CHARS = 64 * 1024;
    private static final int MAX_CONTROLS = 32;
    private static final long FLUSH_MS = 250;
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private final Sink sink;
    private int chars, records, controls;
    private boolean flushRequested;
    private long dropped;

    BatchedLogWriter(Sink sink) {
        this.sink = sink;
        Thread thread = new Thread(this::loop, "SCAMERA-log-writer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    synchronized void append(String text) {
        if (text == null || text.isEmpty()) return;
        if (text.length() > MAX_RECORD_CHARS)
            text = text.substring(0, MAX_RECORD_CHARS) + "\n[log record truncated]\n";
        if (records >= MAX_RECORDS || chars + text.length() > MAX_CHARS) {
            dropped++;
            return;
        }
        boolean empty = queue.isEmpty();
        queue.addLast(text);
        records++;
        chars += text.length();
        if (empty || chars >= BATCH_CHARS) notifyAll();
    }

    synchronized boolean afterFlush(Runnable action) {
        if (controls >= MAX_CONTROLS) return false;
        queue.addLast(action);
        controls++;
        notifyAll();
        return true;
    }

    synchronized int pendingChars() { return chars; }
    synchronized int pendingRecords() { return records; }

    synchronized void flushSoon() { flushRequested = true; notifyAll(); }

    private void loop() {
        long failedRecords = 0;
        for (;;) {
            StringBuilder batch = new StringBuilder();
            Runnable control = null;
            int batchRecords = 0;
            synchronized (this) {
                while (queue.isEmpty() && !flushRequested) {
                    try { wait(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (!flushRequested && controls == 0 && chars < BATCH_CHARS) {
                    try { wait(FLUSH_MS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                flushRequested = false;
                if (dropped != 0) {
                    batch.append("[log queue overflow: dropped ").append(dropped).append(" records]\n");
                    dropped = 0;
                }
                while (!queue.isEmpty() && batch.length() < BATCH_CHARS) {
                    Object entry = queue.removeFirst();
                    if (entry instanceof Runnable) {
                        control = (Runnable) entry;
                        controls--;
                        break;
                    }
                    String text = (String) entry;
                    chars -= text.length();
                    records--;
                    batchRecords++;
                    batch.append(text);
                }
            }
            try {
                if (batch.length() != 0) {
                    if (failedRecords != 0)
                        batch.insert(0, "[log storage unavailable: lost " + failedRecords + " records]\n");
                    sink.write(batch.toString());
                    failedRecords = 0;
                }
                sink.flush();
            } catch (Exception | Error e) { // an Error too: it would end this thread and the log with it, silently
                failedRecords += batchRecords;
            }
            if (control != null) {
                try { control.run(); }
                catch (RuntimeException | Error ignored) { /* A failed export must not stop logging. */ }
            }
        }
    }
}
