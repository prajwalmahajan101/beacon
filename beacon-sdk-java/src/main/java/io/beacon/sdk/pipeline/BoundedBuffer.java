package io.beacon.sdk.pipeline;

import io.beacon.sdk.config.BeaconConfig.DropPolicy;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.LogRecord;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded non-blocking buffer with a configurable drop policy.
 *
 * <p>Backed by {@link ArrayBlockingQueue}. Capacity is fixed at construction;
 * {@link #offer(LogRecord)} never blocks (spec/02 §2.1) and applies the configured
 * {@link DropPolicy} when full (spec/02 §2.2).</p>
 */
public final class BoundedBuffer {

    private final int capacity;
    private final DropPolicy policy;
    private final SdkMetrics metrics;
    private final ArrayBlockingQueue<LogRecord> queue;

    public BoundedBuffer(int capacity, DropPolicy policy, SdkMetrics metrics) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Enqueue a record without blocking. Returns {@code true} if the record was
     * accepted into the buffer, {@code false} if the policy was {@code DROP_NEWEST}
     * and the buffer was full.
     *
     * <p>{@code DROP_OLDEST} always returns {@code true} — it evicts the head and
     * accepts the new record, incrementing the dropped counter per eviction.</p>
     */
    public boolean offer(LogRecord record) {
        Objects.requireNonNull(record, "record");
        switch (policy) {
            case DROP_NEWEST -> {
                if (queue.offer(record)) {
                    metrics.incEnqueued();
                    metrics.setBufferDepth(queue.size());
                    return true;
                }
                metrics.incDropped();
                return false;
            }
            case DROP_OLDEST -> {
                while (!queue.offer(record)) {
                    if (queue.poll() != null) {
                        metrics.incDropped();
                    }
                }
                metrics.incEnqueued();
                metrics.setBufferDepth(queue.size());
                return true;
            }
            case SPILL_FALLBACK -> throw new UnsupportedOperationException(
                    "M1.4: SPILL_FALLBACK requires FallbackSink");
            default -> throw new IllegalStateException("Unknown drop policy: " + policy);
        }
    }

    public int size() { return queue.size(); }

    public int capacity() { return capacity; }

    public DropPolicy policy() { return policy; }

    /** Drain up to {@code maxRecords} into the sink. Used by the M1.3 batch flusher. */
    public int drainTo(Collection<? super LogRecord> sink, int maxRecords) {
        int drained = queue.drainTo(sink, maxRecords);
        metrics.setBufferDepth(queue.size());
        return drained;
    }
}
