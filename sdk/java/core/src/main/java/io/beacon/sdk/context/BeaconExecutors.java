package io.beacon.sdk.context;

import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;

/**
 * Public helper for propagating Beacon-relevant context (OTel {@link Context} + SLF4J {@link MDC})
 * across executor boundaries.
 *
 * <p>Use {@link #wrap(Executor)} / {@link #wrap(ExecutorService)} for executors you control, or
 * {@link #wrap(Runnable)} / {@link #wrap(Callable)} for ad-hoc {@code
 * CompletableFuture.supplyAsync(BeaconExecutors.wrap(...), executor)} calls.
 *
 * <p>OTel half: delegates to {@link Context#taskWrapping(Executor)} — the official, tested
 * propagation API. MDC half: a {@code Map<String,String>} snapshot taken at submission and restored
 * on execution, with prior worker-thread MDC state restored in a {@code finally} block.
 *
 * <p>See ADR-0008 ({@code docs/adr/0008-async-context-propagation.md}) for the rationale.
 *
 * <p><strong>Misuse:</strong> submitting work to a non-wrapped executor silently loses context.
 * Wrap the executor (or wrap the {@link Runnable}/{@link Callable}) at the boundary — that is the
 * caller's responsibility.
 */
public final class BeaconExecutors {

  private BeaconExecutors() {
    // utility class
  }

  /**
   * Wrap an {@link Executor} so every submitted {@link Runnable} carries the caller-thread OTel
   * Context + MDC at the moment of {@code execute(...)}.
   */
  public static Executor wrap(Executor delegate) {
    Objects.requireNonNull(delegate, "delegate");
    Executor otel = Context.taskWrapping(delegate);
    return runnable -> otel.execute(captureMdc(runnable));
  }

  /**
   * Wrap an {@link ExecutorService} so every submitted {@link Runnable}/{@link Callable} carries
   * the caller-thread OTel Context + MDC. Lifecycle methods pass through.
   */
  public static ExecutorService wrap(ExecutorService delegate) {
    Objects.requireNonNull(delegate, "delegate");
    ExecutorService otel = Context.taskWrapping(delegate);
    return new MdcWrappingExecutorService(otel);
  }

  /**
   * Wrap a {@link Runnable} so it carries the caller-thread OTel Context + MDC when it runs on
   * another thread (e.g. via {@code CompletableFuture.runAsync(wrap(r), executor)}).
   */
  public static Runnable wrap(Runnable r) {
    Objects.requireNonNull(r, "runnable");
    Context ctx = Context.current();
    Runnable otelWrapped = ctx.wrap(r);
    return captureMdc(otelWrapped);
  }

  /**
   * Wrap a {@link Callable} so it carries the caller-thread OTel Context + MDC when it runs on
   * another thread (e.g. via {@code CompletableFuture.supplyAsync(wrap(c), executor)}).
   */
  public static <T> Callable<T> wrap(Callable<T> c) {
    Objects.requireNonNull(c, "callable");
    Context ctx = Context.current();
    Callable<T> otelWrapped = ctx.wrap(c);
    Map<String, String> snapshot = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> prev = MDC.getCopyOfContextMap();
      applyMdc(snapshot);
      try {
        return otelWrapped.call();
      } finally {
        applyMdc(prev);
      }
    };
  }

  // ── internals ─────────────────────────────────────────────────────────

  private static Runnable captureMdc(Runnable inner) {
    Map<String, String> snapshot = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> prev = MDC.getCopyOfContextMap();
      applyMdc(snapshot);
      try {
        inner.run();
      } finally {
        applyMdc(prev);
      }
    };
  }

  private static void applyMdc(Map<String, String> map) {
    if (map == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(map);
    }
  }

  /**
   * Thin wrapper that decorates every submitted task with a per-submission MDC snapshot. The
   * OTel-context half is already applied by {@code delegate} (returned from {@link
   * Context#taskWrapping(ExecutorService)} — see {@link #wrap(ExecutorService)}).
   */
  private static final class MdcWrappingExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    MdcWrappingExecutorService(ExecutorService delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(captureMdc(command));
    }

    @Override
    public Future<?> submit(Runnable task) {
      return delegate.submit(captureMdc(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return delegate.submit(captureMdc(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      return delegate.submit(decorate(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      return delegate.invokeAll(decorateAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      return delegate.invokeAll(decorateAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, java.util.concurrent.ExecutionException {
      return delegate.invokeAny(decorateAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
      return delegate.invokeAny(decorateAll(tasks), timeout, unit);
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    private static <T> Callable<T> decorate(Callable<T> task) {
      // Capture MDC at submission; the OTel context is already wrapped by the outer delegate
      // (Context.taskWrapping wraps Runnable/Callable submissions for us).
      Map<String, String> snapshot = MDC.getCopyOfContextMap();
      return () -> {
        Map<String, String> prev = MDC.getCopyOfContextMap();
        applyMdc(snapshot);
        try {
          return task.call();
        } finally {
          applyMdc(prev);
        }
      };
    }

    private static <T> List<Callable<T>> decorateAll(Collection<? extends Callable<T>> tasks) {
      List<Callable<T>> out = new ArrayList<>(tasks.size());
      for (Callable<T> t : tasks) out.add(decorate(t));
      return out;
    }
  }
}
