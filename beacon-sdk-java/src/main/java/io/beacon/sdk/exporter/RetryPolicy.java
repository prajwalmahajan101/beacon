package io.beacon.sdk.exporter;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff + full jitter, capped at {@code maxMs}, up to
 * {@code maxRetries} attempts. After exhaustion, the batch is handed to a
 * {@link FallbackSink}. See spec/02 §2.4.
 *
 * <p>Delay schedule: ceiling for attempt {@code n} (0-indexed) is
 * {@code min(baseMs * 2^n, maxMs)}; the actual delay is a uniform random
 * value in {@code [0, ceiling]} (full-jitter, per AWS Architecture Blog).</p>
 */
public final class RetryPolicy {

    private final int maxRetries;
    private final long baseMs;
    private final long maxMs;

    public RetryPolicy(int maxRetries, long baseMs, long maxMs) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        if (baseMs <= 0) {
            throw new IllegalArgumentException("baseMs must be > 0, got " + baseMs);
        }
        if (maxMs < baseMs) {
            throw new IllegalArgumentException("maxMs must be >= baseMs, got " + maxMs);
        }
        this.maxRetries = maxRetries;
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    /**
     * Returns a backoff delay for the given 0-indexed retry {@code attempt}.
     * Negative attempts collapse to {@code 0}. Overflow-safe: the {@code 1L << n}
     * shift caps at 30 so {@code baseMs * 2^30} never overflows {@code long}.
     */
    public long nextDelayMs(int attempt) {
        if (attempt <= 0) attempt = 0;
        int shift = Math.min(attempt, 30);
        long ceiling = Math.min(baseMs << shift, maxMs);
        return ThreadLocalRandom.current().nextLong(0L, ceiling + 1L);
    }

    public int maxRetries() { return maxRetries; }
    public long baseMs() { return baseMs; }
    public long maxMs() { return maxMs; }
}
