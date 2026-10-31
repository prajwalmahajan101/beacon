package io.beacon.sdk.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit coverage for {@link BeaconExecutors} — OTel Context + SLF4J MDC capture/restore across
 * executor boundaries. Every test shuts the per-test executor down in @AfterEach and clears MDC, so
 * a misbehaving wrap doesn't pollute neighbouring tests.
 */
class BeaconExecutorsTest {

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String SPAN_ID = "00f067aa0ba902b7";

  private ExecutorService executor;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (executor != null) {
      executor.shutdownNow();
      executor.awaitTermination(2, TimeUnit.SECONDS);
      executor = null;
    }
    MDC.clear();
  }

  @Test
  void runnable_carries_mdc_to_worker_thread() throws Exception {
    executor = Executors.newSingleThreadExecutor();
    MDC.put("trace_id", "abc");
    AtomicReference<String> captured = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);

    Runnable wrappedRun =
        BeaconExecutors.wrap(
            () -> {
              captured.set(MDC.get("trace_id"));
              done.countDown();
            });
    executor.submit(wrappedRun);

    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(captured.get()).isEqualTo("abc");
  }

  @Test
  void runnable_restores_prior_mdc_on_worker() throws Exception {
    executor = Executors.newSingleThreadExecutor();
    // Pre-populate the worker thread's MDC.
    executor.submit(() -> MDC.put("key", "worker")).get();

    // Caller has its own MDC.
    MDC.put("key", "caller");
    Runnable wrapped =
        BeaconExecutors.wrap(
            () -> {
              // body intentionally empty — we only care about the restoration in finally
            });

    // Run on the worker; before+after observation done on the worker.
    AtomicReference<String> before = new AtomicReference<>();
    AtomicReference<String> after = new AtomicReference<>();
    executor
        .submit(
            () -> {
              before.set(MDC.get("key"));
              wrapped.run();
              after.set(MDC.get("key"));
            })
        .get();

    assertThat(before.get()).isEqualTo("worker");
    assertThat(after.get()).isEqualTo("worker"); // prior worker state restored
  }

  @Test
  void runnable_clears_mdc_when_caller_had_none() throws Exception {
    executor = Executors.newSingleThreadExecutor();
    // Caller has no MDC.
    MDC.clear();
    // Build wrap on the caller (snapshot is null/empty).
    AtomicReference<String> insideAfterApply = new AtomicReference<>();
    Runnable wrapped = BeaconExecutors.wrap(() -> insideAfterApply.set(MDC.get("key")));

    AtomicReference<String> afterRestore = new AtomicReference<>();
    executor
        .submit(
            () -> {
              // Worker pre-populates its own MDC.
              MDC.put("key", "worker-pre-existing");
              // Now run the wrapped body — caller snapshot was null, so MDC inside body must be
              // cleared.
              wrapped.run();
              // After the wrapped body returns, the worker's pre-existing state is restored.
              afterRestore.set(MDC.get("key"));
            })
        .get();

    assertThat(insideAfterApply.get()).isNull();
    assertThat(afterRestore.get()).isEqualTo("worker-pre-existing");
  }

  @Test
  void callable_carries_mdc() throws Exception {
    executor = Executors.newSingleThreadExecutor();
    MDC.put("trace_id", "abc");
    Callable<String> wrapped = BeaconExecutors.wrap(() -> MDC.get("trace_id"));

    Future<String> f = executor.submit(wrapped);
    assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("abc");
  }

  @Test
  void wrap_executor_decorates_execute() throws Exception {
    executor = Executors.newSingleThreadExecutor();
    Executor wrapped = BeaconExecutors.wrap((Executor) executor);
    MDC.put("trace_id", "from-caller");
    AtomicReference<String> captured = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);

    wrapped.execute(
        () -> {
          captured.set(MDC.get("trace_id"));
          done.countDown();
        });

    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(captured.get()).isEqualTo("from-caller");
  }

  @Test
  void wrap_executor_service_decorates_submit_callable() throws Exception {
    executor = BeaconExecutors.wrap(Executors.newSingleThreadExecutor());
    MDC.put("trace_id", "from-caller");

    Future<String> f = executor.submit(() -> MDC.get("trace_id"));
    assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("from-caller");
  }

  @Test
  void otel_context_propagates_via_wrap() throws Exception {
    executor = Executors.newSingleThreadExecutor();
    SpanContext sc =
        SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getDefault(), TraceState.getDefault());
    AtomicReference<String> capturedTrace = new AtomicReference<>();
    AtomicReference<String> capturedSpan = new AtomicReference<>();

    try (Scope ignored = Span.wrap(sc).makeCurrent()) {
      Callable<Void> task =
          BeaconExecutors.wrap(
              () -> {
                SpanContext seen = Span.current().getSpanContext();
                capturedTrace.set(seen.getTraceId());
                capturedSpan.set(seen.getSpanId());
                return null;
              });
      executor.submit(task).get(2, TimeUnit.SECONDS);
    }

    assertThat(capturedTrace.get()).isEqualTo(TRACE_ID);
    assertThat(capturedSpan.get()).isEqualTo(SPAN_ID);
  }

  @Test
  void exception_in_task_still_restores_mdc() throws Exception {
    executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> MDC.put("key", "worker-state")).get();

    MDC.put("key", "caller");
    Runnable boom =
        BeaconExecutors.wrap(
            (Runnable)
                () -> {
                  throw new RuntimeException("boom");
                });

    AtomicReference<String> afterFailure = new AtomicReference<>();
    Future<?> f =
        executor.submit(
            () -> {
              try {
                boom.run();
              } catch (RuntimeException expected) {
                /* ignore */
              }
              afterFailure.set(MDC.get("key"));
            });
    f.get(2, TimeUnit.SECONDS);

    // The wrapped task threw, but the finally block must have restored the prior worker state.
    assertThat(afterFailure.get()).isEqualTo("worker-state");
  }
}
