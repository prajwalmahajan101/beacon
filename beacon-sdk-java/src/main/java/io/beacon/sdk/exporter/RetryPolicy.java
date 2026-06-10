package io.beacon.sdk.exporter;

/**
 * Exponential backoff + jitter, capped at backoff_max_ms, up to max_retries attempts.
 * After exhaustion, the batch is handed to {@link FallbackSink}. See spec/02 §2.4.
 * Implemented in M1.4.
 */
public final class RetryPolicy {

    private final int maxRetries;
    private final long baseMs;
    private final long maxMs;

    public RetryPolicy(int maxRetries, long baseMs, long maxMs) {
        this.maxRetries = maxRetries;
        this.baseMs = baseMs;
        this.maxMs = maxMs;
    }

    public long nextDelayMs(int attempt) {
        throw new UnsupportedOperationException("M1.4: retry backoff with jitter");
    }

    public int maxRetries() {
        return maxRetries;
    }
}
