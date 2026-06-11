package io.beacon.sdk.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkMetricsTest {

    @Test
    void counters_start_at_zero() {
        SdkMetrics m = new SdkMetrics();
        assertThat(m.enqueued()).isZero();
        assertThat(m.dropped()).isZero();
        assertThat(m.bufferDepth()).isZero();
    }

    @Test
    void increment_and_set_track_correctly() {
        SdkMetrics m = new SdkMetrics();
        m.incEnqueued();
        m.incEnqueued();
        m.incDropped();
        m.setBufferDepth(7);

        assertThat(m.enqueued()).isEqualTo(2);
        assertThat(m.dropped()).isEqualTo(1);
        assertThat(m.bufferDepth()).isEqualTo(7);
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

    @Test
    void m14_methods_remain_unimplemented_until_exporter_lands() {
        SdkMetrics m = new SdkMetrics();
        assertThatThrownBy(m::incExported).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(m::incExportFailure).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(m::incFallbackWrite).isInstanceOf(UnsupportedOperationException.class);
    }
}
