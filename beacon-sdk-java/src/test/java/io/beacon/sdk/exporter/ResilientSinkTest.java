package io.beacon.sdk.exporter;

import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.pipeline.BatchSink;
import io.beacon.sdk.record.LogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientSinkTest {

    private static LogRecord rec(int i) {
        return LogRecord.minimal(
                Instant.parse("2026-06-12T00:00:00Z").plusMillis(i),
                9, "INFO", "rec-" + i,
                Map.of("service.name", "t", "telemetry.sdk.language", "java"));
    }

    /** Test-only fallback that captures batches and increments the metric like the real impls. */
    static final class CapturingFallback implements FallbackSink {
        final List<List<LogRecord>> received = new ArrayList<>();
        private final SdkMetrics metrics;
        CapturingFallback(SdkMetrics metrics) { this.metrics = metrics; }
        @Override public void write(List<LogRecord> batch) {
            received.add(List.copyOf(batch));
            metrics.incFallbackWrite(batch.size());
        }
    }

    /** Zero-delay retry policy keeps tests fast. */
    private static RetryPolicy fastRetry(int maxRetries) {
        return new RetryPolicy(maxRetries, 1, 1);
    }

    @Test
    void first_attempt_success_no_retry_no_fallback() {
        SdkMetrics m = new SdkMetrics();
        AtomicInteger calls = new AtomicInteger();
        BatchSink ok = batch -> calls.incrementAndGet();
        CapturingFallback fb = new CapturingFallback(m);

        ResilientSink rs = new ResilientSink(ok, fastRetry(5), fb, m);
        rs.accept(List.of(rec(1), rec(2)));

        assertThat(calls).hasValue(1);
        assertThat(m.exported()).isEqualTo(2);
        assertThat(m.exportFailures()).isZero();
        assertThat(fb.received).isEmpty();
        assertThat(m.fallbackWrites()).isZero();
    }

    @Test
    void n_failures_then_success_no_fallback() {
        SdkMetrics m = new SdkMetrics();
        AtomicInteger calls = new AtomicInteger();
        BatchSink flakey = batch -> {
            if (calls.incrementAndGet() < 4) throw new RuntimeException("boom-" + calls.get());
        };
        CapturingFallback fb = new CapturingFallback(m);

        ResilientSink rs = new ResilientSink(flakey, fastRetry(5), fb, m);
        rs.accept(List.of(rec(1)));

        assertThat(calls).hasValue(4);             // 3 failures then success on 4th attempt
        assertThat(m.exportFailures()).isEqualTo(3);
        assertThat(m.exported()).isEqualTo(1);
        assertThat(fb.received).isEmpty();
    }

    @Test
    void all_attempts_fail_routes_to_fallback() {
        SdkMetrics m = new SdkMetrics();
        AtomicInteger calls = new AtomicInteger();
        BatchSink broken = batch -> {
            calls.incrementAndGet();
            throw new RuntimeException("unreachable");
        };
        CapturingFallback fb = new CapturingFallback(m);

        ResilientSink rs = new ResilientSink(broken, fastRetry(5), fb, m);
        rs.accept(List.of(rec(1), rec(2), rec(3)));

        assertThat(calls).hasValue(6);             // 1 initial + 5 retries
        assertThat(m.exportFailures()).isEqualTo(6);
        assertThat(m.exported()).isZero();
        assertThat(fb.received).hasSize(1);
        assertThat(fb.received.get(0)).hasSize(3);
        assertThat(m.fallbackWrites()).isEqualTo(3);
    }

    @Test
    void zero_max_retries_means_one_attempt_then_fallback() {
        SdkMetrics m = new SdkMetrics();
        AtomicInteger calls = new AtomicInteger();
        BatchSink broken = batch -> {
            calls.incrementAndGet();
            throw new RuntimeException("nope");
        };
        CapturingFallback fb = new CapturingFallback(m);

        ResilientSink rs = new ResilientSink(broken, fastRetry(0), fb, m);
        rs.accept(List.of(rec(1)));

        assertThat(calls).hasValue(1);
        assertThat(m.exportFailures()).isEqualTo(1);
        assertThat(fb.received).hasSize(1);
    }

    @Test
    void retry_actually_sleeps_between_attempts() {
        SdkMetrics m = new SdkMetrics();
        BatchSink broken = batch -> { throw new RuntimeException("x"); };
        CapturingFallback fb = new CapturingFallback(m);
        // base=20, max=20: each delay is in [0,20] -> 2 retries ~> [0,40]ms total sleep
        RetryPolicy rp = new RetryPolicy(2, 20, 20);

        ResilientSink rs = new ResilientSink(broken, rp, fb, m);
        long t0 = System.nanoTime();
        rs.accept(List.of(rec(1)));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(elapsedMs).isLessThanOrEqualTo(200L); // sanity ceiling
        assertThat(fb.received).hasSize(1);
        assertThat(m.exportFailures()).isEqualTo(3);     // 1 + 2 retries
    }
}
