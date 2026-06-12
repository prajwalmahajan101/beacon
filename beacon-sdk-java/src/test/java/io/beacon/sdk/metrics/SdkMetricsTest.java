package io.beacon.sdk.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SdkMetricsTest {

    @Test
    void counters_start_at_zero() {
        SdkMetrics m = new SdkMetrics();
        assertThat(m.enqueued()).isZero();
        assertThat(m.dropped()).isZero();
        assertThat(m.bufferDepth()).isZero();
        assertThat(m.batchesFlushed()).isZero();
        assertThat(m.recordsFlushed()).isZero();
        assertThat(m.exported()).isZero();
        assertThat(m.exportFailures()).isZero();
        assertThat(m.fallbackWrites()).isZero();
    }

    @Test
    void increment_and_set_track_correctly() {
        SdkMetrics m = new SdkMetrics();
        m.incEnqueued();
        m.incEnqueued();
        m.incDropped();
        m.setBufferDepth(7);
        m.incBatchesFlushed();
        m.incBatchesFlushed();
        m.incRecordsFlushed(10);
        m.incRecordsFlushed(3);
        m.incExported(10);
        m.incExported(3);
        m.incExportFailure();
        m.incFallbackWrite(5);

        assertThat(m.enqueued()).isEqualTo(2);
        assertThat(m.dropped()).isEqualTo(1);
        assertThat(m.bufferDepth()).isEqualTo(7);
        assertThat(m.batchesFlushed()).isEqualTo(2);
        assertThat(m.recordsFlushed()).isEqualTo(13);
        assertThat(m.exported()).isEqualTo(13);
        assertThat(m.exportFailures()).isEqualTo(1);
        assertThat(m.fallbackWrites()).isEqualTo(5);
    }

    @Test
    void enqueued_counter_is_atomic_under_contention() throws InterruptedException {
        SdkMetrics m = new SdkMetrics();
        int threads = 8;
        int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) m.incEnqueued();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(m.enqueued()).isEqualTo((long) threads * perThread);
    }

}
