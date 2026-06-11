package io.beacon.sdk.pipeline;

import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.LogRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Drains {@link BoundedBuffer} into batches per spec/02 §2.3.
 *
 * <p>Flush triggers (whichever fires first):</p>
 * <ul>
 *   <li><b>Size</b> — accumulated batch reaches {@code batchMaxRecords}.</li>
 *   <li><b>Interval</b> — {@code flushIntervalMs} elapsed since the first record
 *       of the current batch was buffered.</li>
 * </ul>
 *
 * <p>Empty intervals do NOT invoke the sink: a batch only counts once at least
 * one record has been received and the interval clock starts on that record.</p>
 *
 * <p>Threading: a single daemon thread blocks on {@link BoundedBuffer#poll(long)}
 * with a deadline derived from the interval. Size trigger fires naturally as
 * soon as the Nth record arrives because the loop re-checks after each poll.</p>
 *
 * <p>{@link #stop()} clears the running flag, interrupts the thread, joins with
 * a short timeout. Buffer drain on shutdown is deferred to M1.5 (C9).</p>
 */
public final class BatchFlusher {

    private final BoundedBuffer buffer;
    private final BatchSink sink;
    private final int batchMaxRecords;
    private final long flushIntervalMs;
    private final SdkMetrics metrics;

    private volatile boolean running;
    private Thread thread;

    public BatchFlusher(BoundedBuffer buffer,
                        BatchSink sink,
                        int batchMaxRecords,
                        long flushIntervalMs,
                        SdkMetrics metrics) {
        if (batchMaxRecords <= 0) {
            throw new IllegalArgumentException("batchMaxRecords must be > 0, got " + batchMaxRecords);
        }
        if (flushIntervalMs <= 0) {
            throw new IllegalArgumentException("flushIntervalMs must be > 0, got " + flushIntervalMs);
        }
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.batchMaxRecords = batchMaxRecords;
        this.flushIntervalMs = flushIntervalMs;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runLoop, "beacon-batch-flusher");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private void runLoop() {
        List<LogRecord> batch = new ArrayList<>(batchMaxRecords);
        long batchStartNanos = 0L;

        try {
            while (running) {
                if (batch.isEmpty()) {
                    // Idle: wait up to one interval for the first record. If
                    // nothing arrives we loop — empty intervals don't flush.
                    LogRecord first = buffer.poll(flushIntervalMs);
                    if (first == null) continue;
                    batch.add(first);
                    batchStartNanos = System.nanoTime();
                } else {
                    long elapsedMs = (System.nanoTime() - batchStartNanos) / 1_000_000L;
                    long remaining = flushIntervalMs - elapsedMs;
                    if (remaining <= 0) {
                        flush(batch);
                        continue;
                    }
                    LogRecord next = buffer.poll(remaining);
                    if (next != null) batch.add(next);
                }

                // Opportunistically drain the rest up to the size cap.
                int room = batchMaxRecords - batch.size();
                if (room > 0) buffer.drainTo(batch, room);

                if (batch.size() >= batchMaxRecords) {
                    flush(batch);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // M1.5: drain remaining buffer to sink before stop (C9). For M1.3 we drop.
    }

    private void flush(List<LogRecord> batch) {
        List<LogRecord> snapshot = List.copyOf(batch);
        batch.clear();
        try {
            sink.accept(snapshot);
        } catch (RuntimeException sinkFailure) {
            // M1.4: sink failures route to retry/backoff + fallback. For M1.3
            // we swallow so a misbehaving sink can't kill the flusher thread.
        }
        metrics.incBatchesFlushed();
        metrics.incRecordsFlushed(snapshot.size());
    }

    public boolean isRunning() { return running; }
    public int batchMaxRecords() { return batchMaxRecords; }
    public long flushIntervalMs() { return flushIntervalMs; }
}
