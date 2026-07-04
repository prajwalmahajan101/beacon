package io.beacon.sdk.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.beacon.sdk.config.BeaconConfig.DropPolicy;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.LogRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedBufferTest {

  private static LogRecord rec(int i) {
    return LogRecord.minimal(
        Instant.parse("2026-06-11T00:00:00Z").plusMillis(i),
        9,
        "INFO",
        "rec-" + i,
        Map.of("service.name", "t", "telemetry.sdk.language", "java"));
  }

  @Test
  void constructor_rejects_nonpositive_capacity() {
    SdkMetrics m = new SdkMetrics();
    assertThatThrownBy(() -> new BoundedBuffer(0, DropPolicy.DROP_OLDEST, m))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedBuffer(-1, DropPolicy.DROP_OLDEST, m))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void offer_below_capacity_accepts_all_records() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(5, DropPolicy.DROP_NEWEST, m);
    for (int i = 0; i < 5; i++) {
      assertThat(b.offer(rec(i))).isTrue();
    }
    assertThat(b.size()).isEqualTo(5);
    assertThat(m.enqueued()).isEqualTo(5);
    assertThat(m.dropped()).isZero();
    assertThat(m.bufferDepth()).isEqualTo(5);
  }

  @Test
  void drop_newest_rejects_when_full() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(3, DropPolicy.DROP_NEWEST, m);
    b.offer(rec(0));
    b.offer(rec(1));
    b.offer(rec(2));
    assertThat(b.offer(rec(3))).isFalse();
    assertThat(b.offer(rec(4))).isFalse();
    assertThat(b.size()).isEqualTo(3);
    assertThat(m.dropped()).isEqualTo(2);
    assertThat(m.enqueued()).isEqualTo(3);
  }

  @Test
  void drop_oldest_evicts_head_and_accepts_new() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(3, DropPolicy.DROP_OLDEST, m);
    for (int i = 0; i < 10; i++) {
      assertThat(b.offer(rec(i))).isTrue();
    }
    assertThat(b.size()).isEqualTo(3);
    assertThat(m.enqueued()).isEqualTo(10);
    assertThat(m.dropped()).isEqualTo(7);
  }

  @Test
  void spill_fallback_throws_until_m1_4() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(2, DropPolicy.SPILL_FALLBACK, m);
    assertThatThrownBy(() -> b.offer(rec(0)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("M1.4");
  }

  @Test
  void drain_to_yields_records_and_updates_depth() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(10, DropPolicy.DROP_NEWEST, m);
    for (int i = 0; i < 5; i++) b.offer(rec(i));

    List<LogRecord> sink = new ArrayList<>();
    int drained = b.drainTo(sink, 3);

    assertThat(drained).isEqualTo(3);
    assertThat(sink).hasSize(3);
    assertThat(b.size()).isEqualTo(2);
    assertThat(m.bufferDepth()).isEqualTo(2);
  }

  @Test
  void poll_returns_record_when_available() throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(4, DropPolicy.DROP_NEWEST, m);
    b.offer(rec(0));
    b.offer(rec(1));

    LogRecord first = b.poll(100);
    assertThat(first).isNotNull();
    assertThat(b.size()).isEqualTo(1);
    assertThat(m.bufferDepth()).isEqualTo(1);
  }

  @Test
  void poll_returns_null_after_timeout_when_empty() throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(4, DropPolicy.DROP_NEWEST, m);
    long t0 = System.nanoTime();
    LogRecord r = b.poll(25);
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

    assertThat(r).isNull();
    assertThat(elapsedMs).isGreaterThanOrEqualTo(20);
  }

  @Test
  void concurrent_offers_drop_oldest_never_block_and_total_processed_matches()
      throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(100, DropPolicy.DROP_OLDEST, m);
    int threads = 8;
    int perThread = 2_000;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
      final int tid = t;
      pool.execute(
          () -> {
            try {
              start.await();
              for (int i = 0; i < perThread; i++) b.offer(rec(tid * perThread + i));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
    pool.shutdown();

    long total = (long) threads * perThread;
    // Every offer either landed in the buffer or evicted/dropped someone.
    // enqueued counts every accepted offer (= total, since DROP_OLDEST always accepts).
    assertThat(m.enqueued()).isEqualTo(total);
    // Dropped is total - whatever still sits in the queue at end.
    assertThat(m.dropped()).isEqualTo(total - b.size());
    assertThat(b.size()).isLessThanOrEqualTo(100);
  }
}
