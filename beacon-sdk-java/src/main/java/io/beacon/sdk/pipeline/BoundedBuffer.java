package io.beacon.sdk.pipeline;

import io.beacon.sdk.config.BeaconConfig.DropPolicy;
import io.beacon.sdk.record.LogRecord;

/**
 * Bounded non-blocking buffer with a configurable drop policy.
 * Capacity, policy → spec/02 §2.2. Non-blocking emit → §2.1.
 * Implemented in M1.2.
 */
public final class BoundedBuffer {

    private final int capacity;
    private final DropPolicy policy;

    public BoundedBuffer(int capacity, DropPolicy policy) {
        this.capacity = capacity;
        this.policy = policy;
    }

    public boolean offer(LogRecord record) {
        throw new UnsupportedOperationException("M1.2: bounded buffer + drop policy");
    }

    public int size() {
        throw new UnsupportedOperationException("M1.2");
    }

    public int capacity() {
        return capacity;
    }

    public DropPolicy policy() {
        return policy;
    }
}
