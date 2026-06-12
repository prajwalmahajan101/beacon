# ADR-0004 — Batch flusher concurrency model

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-12 (backfilled, decisions land in M1.3 / 2026-06-11) |
| Milestone | M1.3 |
| Supersedes | — |

## Context

M1.3 connects the bounded buffer to a downstream sink. The spec (§2.3) demands batching with two independent triggers: a size cap (`batch_max_records`) and a time deadline (`flush_interval_ms`), whichever fires first. The first record of a batch starts the interval clock; empty intervals must not invoke the sink. C4 (size trigger) and C5 (interval trigger) are the acceptance gates.

The choice space:

- **Threading model** — single daemon thread, `ScheduledExecutorService`, or a `ForkJoinPool` worker.
- **Wait mechanism** — busy-poll with `Thread.sleep`, `BlockingQueue.poll(timeout)`, or `LockSupport.parkNanos`.
- **Sink contract** — a class, an `Exporter`-shaped interface, or a generic `Consumer<List<LogRecord>>`.
- **Lifecycle** — who starts/stops the flusher; how `close()` interacts.

## Decision

### 1. **Single daemon thread + `BoundedBuffer.poll(timeoutMs)`** — not `ScheduledExecutorService`

The flusher runs in one named daemon thread (`"beacon-batch-flusher"`). The loop blocks on `buffer.poll(remainingMs)` where `remainingMs` is the time left until the interval deadline (or `flushIntervalMs` when the batch is empty and the interval clock hasn't started). When a record arrives early, `poll` returns immediately and the loop opportunistically `drainTo`s up to the size cap; when no record arrives, `poll` returns null at the deadline and the loop flushes whatever it has.

Rejected: `ScheduledExecutorService` would fire on a fixed cadence regardless of buffer state — size triggers would be reactive only on the next tick (latency floor = tick interval), and idle intervals would still wake up. `LockSupport.parkNanos` would re-implement what `ArrayBlockingQueue.poll(timeout)` already gives.

Cost: a custom thread instead of a managed pool. Acceptable — the flusher is one process-wide singleton; pool overhead would be larger than the savings.

### 2. **Empty intervals do NOT invoke the sink**

When the batch is empty and the interval elapses, the loop continues without calling `sink.accept`. This is the strict reading of spec §2.3 ("batch reaches size OR interval since first record"). The alternative (flush an empty list on every tick) wastes downstream work and confuses the OTLP exporter's batching.

### 3. **`BatchSink` is a `@FunctionalInterface`** — `void accept(List<LogRecord>)`

Functional interface so production wiring is `ResilientSink::of` and test wiring is a lambda. Default `BatchSink.NOOP` discards the batch, which lets the flusher run end-to-end before M1.4 lands a real exporter. The interface owns no metric — `batchesFlushed` / `recordsFlushed` increment from inside the flusher's `flush()`, so sink failures don't lose the counter.

Rejected: a heavyweight `Exporter` interface with `start()` / `flush()` / `shutdown()` (premature; OTel already gives that shape for the transport layer in M1.4).

### 4. **Lifecycle: flusher starts in `BeaconSdk` constructor, stops on `close()`**

The flusher is wired in `BeaconSdk(BeaconConfig, BatchSink)` and `start()`ed immediately so the SDK is "live" after `builder().build()`. `close()` (M1.5) calls `flusher.drainAndStop(...)`. `start()` and `stop()` are `synchronized` and idempotent.

### 5. **Sink failures are swallowed inside `flush(...)` for M1.3**

If the sink throws, the flusher catches the `RuntimeException` and continues. M1.4's `ResilientSink` (ADR-0005) takes over the retry/backoff/fallback contract — the M1.3 swallow is the "no infinite loop, no thread death" guarantee that lets a misbehaving sink not kill the daemon.

### 6. **In-flight batch retention**

The flusher's `batch` list lives inside `runLoop`'s stack frame. Records sit there from the moment they leave the buffer until `flush(batch)` returns. M1.5's drain-on-shutdown explicitly flushes this list on loop exit so records aren't lost when the daemon terminates.

## Consequences

**Positive**
- Size trigger fires on the Nth record's arrival (no tick latency).
- Interval trigger fires exactly at the deadline (no tick over-/under-shoot).
- Idle SDKs do no work — the flusher parks in `poll` until a record arrives.
- One process-wide thread: trivially debuggable, easy to dump in profilers.

**Negative**
- One thread per SDK instance. Multiple SDKs in one JVM = multiple flusher threads. Acceptable: SDK instances should be singletons per-application; the M1.7 Spring Boot starter enforces this.
- Sink runs on the flusher thread, so a slow sink slows all flushes. M1.4 keeps this property by design (retries block the daemon); revisit in M1.7 if production workloads surface starvation.

**Neutral**
- The flusher does not honor backpressure from the sink (no async signaling). Buffer drop policy is the existing back-pressure mechanism.

## Usage

- **Default wiring:** `BeaconSdk.builder().build()` — flusher starts with `BatchSink.NOOP`.
- **Inject sink:** `BeaconSdk.builder().sink(myBatchSink).build()`.
- **Tune triggers:** `BeaconConfig.defaults().withBatchMaxRecords(512).withFlushIntervalMs(1_000)`.
- **Direct stop (non-draining, tests):** `sdk.flusher().stop()`.
- **Drain + stop (production, M1.5+):** `sdk.close()`.

A future ADR amends this one if (a) we move to an async sink contract (e.g. `CompletableFuture<Void> accept(...)`), (b) sink execution moves off the flusher thread, or (c) a future scenario demands tick-driven flushes instead of poll-driven.
