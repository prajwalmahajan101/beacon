package io.beacon.sdk.exporter;

import io.beacon.sdk.config.BeaconConfig;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;

import java.util.List;
import java.util.Objects;

/**
 * Wraps a delegate {@link BatchSink} with retry + fallback per spec/02 §2.4–2.5.
 *
 * <p>On {@link #accept(List)}: invoke the delegate; if it throws, sleep for
 * {@link RetryPolicy#nextDelayMs(int)} and try again, up to {@code maxRetries}
 * retries (i.e. {@code maxRetries + 1} total attempts). On final exhaustion the
 * batch is handed to {@link FallbackSink} — never silently dropped.</p>
 *
 * <p>Metric semantics:</p>
 * <ul>
 *   <li>{@code exported += batch.size()} on the first successful delegate call.</li>
 *   <li>{@code export_failures} increments once per failed delegate attempt.</li>
 *   <li>{@code fallback_writes += batch.size()} fires from inside {@code FallbackSink}.</li>
 * </ul>
 *
 * <p>Threading: this sink is called on the {@code BatchFlusher} daemon thread.
 * {@code Thread.sleep} blocks that thread for up to
 * {@code maxRetries * backoffMaxMs}; the bounded buffer's drop policy provides
 * back-pressure during that window.</p>
 */
public final class ResilientSink implements BatchSink {

    private final BatchSink delegate;
    private final RetryPolicy retryPolicy;
    private final FallbackSink fallback;
    private final SdkMetrics metrics;

    public ResilientSink(BatchSink delegate,
                         RetryPolicy retryPolicy,
                         FallbackSink fallback,
                         SdkMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Production wiring: build a ResilientSink from {@link BeaconConfig} defaults. */
    public static ResilientSink of(BatchSink delegate, BeaconConfig config, SdkMetrics metrics) {
        Objects.requireNonNull(config, "config");
        RetryPolicy rp = new RetryPolicy(config.maxRetries(), config.backoffBaseMs(), config.backoffMaxMs());
        FallbackSink fb = FallbackSink.fromConfig(config, metrics);
        return new ResilientSink(delegate, rp, fb, metrics);
    }

    @Override
    public void accept(List<LogRecord> batch) {
        int totalAttempts = retryPolicy.maxRetries() + 1; // initial + retries
        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            try {
                delegate.accept(batch);
                metrics.incExported(batch.size());
                return;
            } catch (RuntimeException failure) {
                metrics.incExportFailure();
                boolean lastAttempt = (attempt == totalAttempts - 1);
                if (lastAttempt) break;
                long delayMs = retryPolicy.nextDelayMs(attempt);
                if (delayMs > 0L) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        // Abandon retries on interrupt and route the batch to fallback
                        // so records aren't silently dropped on shutdown.
                        break;
                    }
                }
            }
        }
        fallback.write(batch);
    }
}
