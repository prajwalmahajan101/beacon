package io.beacon.sdk.pipeline;

/**
 * Flushes the buffer when EITHER batch_max_records is reached OR flush_interval_ms elapses,
 * whichever comes first. See spec/02 §2.3.
 * Implemented in M1.3.
 */
public final class BatchFlusher {

    private final int batchMaxRecords;
    private final long flushIntervalMs;

    public BatchFlusher(int batchMaxRecords, long flushIntervalMs) {
        this.batchMaxRecords = batchMaxRecords;
        this.flushIntervalMs = flushIntervalMs;
    }

    public void start() {
        throw new UnsupportedOperationException("M1.3: batch flusher");
    }

    public void stop() {
        throw new UnsupportedOperationException("M1.3");
    }
}
