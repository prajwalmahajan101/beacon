package io.beacon.sdk.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.beacon.sdk.config.BeaconConfig.DropPolicy;
import io.beacon.sdk.metrics.SdkMetrics;
import io.beacon.sdk.record.LogRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class BatchFlusherTest {

  private static LogRecord rec(int i) {
    return LogRecord.minimal(
        Instant.parse("2026-06-11T00:00:00Z").plusMillis(i),
        9,
        "INFO",
        "rec-" + i,
        Map.of("service.name", "t", "telemetry.sdk.language", "java"));
  }

  /** Captures every batch the flusher emits, preserving order. */
  private static final class CapturingSink implements BatchSink {
    final CopyOnWriteArrayList<List<LogRecord>> batches = new CopyOnWriteArrayList<>();

    @Override
    public void accept(List<LogRecord> batch) {
      batches.add(batch);
    }

    int totalRecords() {
      return batches.stream().mapToInt(List::size).sum();
    }
  }

  private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (cond.getAsBoolean()) return;
      Thread.sleep(5);
    }
  }

  @Test
  void ctor_rejects_nonpositive_triggers() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(10, DropPolicy.DROP_NEWEST, m);
    assertThatThrownBy(() -> new BatchFlusher(b, BatchSink.NOOP, 0, 100, m))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BatchFlusher(b, BatchSink.NOOP, 5, 0, m))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void size_trigger_emits_one_batch_when_capacity_reached() throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(100, DropPolicy.DROP_NEWEST, m);
    CapturingSink sink = new CapturingSink();
    BatchFlusher f = new BatchFlusher(b, sink, 10, 60_000, m);
    f.start();
    try {
      for (int i = 0; i < 10; i++) b.offer(rec(i));
      awaitTrue(() -> m.batchesFlushed() == 1, 1_000);

      assertThat(sink.batches).hasSize(1);
      assertThat(sink.batches.get(0)).hasSize(10);
      assertThat(m.recordsFlushed()).isEqualTo(10);
    } finally {
      f.stop();
    }
  }

  @Test
  void interval_trigger_emits_partial_batch_after_deadline() throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(100, DropPolicy.DROP_NEWEST, m);
    CapturingSink sink = new CapturingSink();
    BatchFlusher f = new BatchFlusher(b, sink, 10_000, 100, m);
    f.start();
    try {
      for (int i = 0; i < 3; i++) b.offer(rec(i));
      awaitTrue(() -> m.batchesFlushed() >= 1, 500);

      assertThat(sink.batches).hasSize(1);
      assertThat(sink.batches.get(0)).hasSize(3);
      assertThat(m.recordsFlushed()).isEqualTo(3);
    } finally {
      f.stop();
    }
  }

  @Test
  void idle_does_not_invoke_sink() throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(10, DropPolicy.DROP_NEWEST, m);
    CapturingSink sink = new CapturingSink();
    BatchFlusher f = new BatchFlusher(b, sink, 10, 50, m);
    f.start();
    try {
      Thread.sleep(200);
      assertThat(sink.batches).isEmpty();
      assertThat(m.batchesFlushed()).isZero();
    } finally {
      f.stop();
    }
  }

  @Test
  void mixed_workload_emits_full_batches_plus_partial_on_interval() throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(100, DropPolicy.DROP_NEWEST, m);
    CapturingSink sink = new CapturingSink();
    BatchFlusher f = new BatchFlusher(b, sink, 10, 100, m);
    f.start();
    try {
      for (int i = 0; i < 23; i++) b.offer(rec(i));
      awaitTrue(() -> sink.totalRecords() == 23, 1_000);

      assertThat(sink.totalRecords()).isEqualTo(23);
      assertThat(m.recordsFlushed()).isEqualTo(23);
      assertThat(m.batchesFlushed()).isGreaterThanOrEqualTo(3); // 10+10+3 (or finer)
    } finally {
      f.stop();
    }
  }

  @Test
  void stop_is_idempotent_and_marks_not_running() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(10, DropPolicy.DROP_NEWEST, m);
    BatchFlusher f = new BatchFlusher(b, BatchSink.NOOP, 10, 100, m);
    f.start();
    assertThat(f.isRunning()).isTrue();
    f.stop();
    assertThat(f.isRunning()).isFalse();
    f.stop(); // no-op
    assertThat(f.isRunning()).isFalse();
  }

  @Test
  void drainAndStop_flushes_inflight_batch_and_buffer_remainder() throws InterruptedException {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(1_000, DropPolicy.DROP_NEWEST, m);
    CapturingSink sink = new CapturingSink();
    // Large size + long interval so neither trigger fires before drainAndStop.
    BatchFlusher f = new BatchFlusher(b, sink, 10_000, 60_000, m);
    f.start();

    for (int i = 0; i < 200; i++) b.offer(rec(i));
    // Give the flusher a moment to pull at least the first record into its in-flight batch.
    Thread.sleep(20);

    f.drainAndStop(2_000);

    assertThat(sink.totalRecords()).isEqualTo(200);
    assertThat(m.recordsFlushed()).isEqualTo(200);
    assertThat(b.size()).isZero();
    assertThat(f.isRunning()).isFalse();
  }

  @Test
  void drainAndStop_is_idempotent() {
    SdkMetrics m = new SdkMetrics();
    BoundedBuffer b = new BoundedBuffer(10, DropPolicy.DROP_NEWEST, m);
    CapturingSink sink = new CapturingSink();
    BatchFlusher f = new BatchFlusher(b, sink, 10, 100, m);
    f.start();

    f.drainAndStop(500);
    assertThat(f.isRunning()).isFalse();
    f.drainAndStop(500); // no-op, no exceptions
    assertThat(f.isRunning()).isFalse();
  }
}
