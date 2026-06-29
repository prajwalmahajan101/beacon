# ADR-0015 — Python batch flusher concurrency model

| Field         | Value                                                                          |
| ------------- | ------------------------------------------------------------------------------ |
| Status        | Accepted                                                                       |
| Date          | 2026-06-29                                                                     |
| Milestone     | M2.2 — Python batch flusher background thread                                  |
| Mirrors       | ADR-0004 (Java batch flusher concurrency model) — this is the Python idiom of it |
| Supersedes    | —                                                                              |
| Superseded by | —                                                                              |

## Context

M2.2 inserts the batch flusher between the M2.1 bounded buffer
(`beacon.pipeline.BoundedBuffer`) and the (future M2.3) OTLP exporter. It has the
same load-bearing role on the Python side as Java ADR-0004 names: drain the
buffer into batches on a background thread, flush on a size cap OR a time
interval (whichever fires first), and own a clean start/stop lifecycle. The first
record of a batch starts the interval clock; empty intervals must not invoke the
sink. **C4** (size trigger) and **C5** (interval trigger) are the acceptance
gates — the same two gates ADR-0004 names for Java.

This ADR is the **Python idiom of ADR-0004**. It does not re-litigate the
decisions ADR-0004 already settled (a single background worker, a poll-with-
timeout wait, a functional sink seam with a NOOP default, swallowed sink failures
for now, idempotent lifecycle); it records where the Python implementation *must
diverge* from Java because the standard library gives different primitives, and
pins those divergences so M2.3+ build on a known foundation.

The phase is scoped per locked decision #3 in M2.0's `04-CONTEXT.md`: **sync-only**
— `threading.Thread` + `queue.Queue`, **no `asyncio`** task / event loop. The
flusher is one process-wide daemon thread, mirroring the Java single-flusher
model.

The choice space mirrors ADR-0004:

- **Threading model** — single daemon `threading.Thread`, a
  `concurrent.futures` scheduled executor, `sched`, or an `asyncio` task.
- **Wait mechanism** — busy-poll with `time.sleep`, `queue.Queue.get(timeout)`,
  or a condition variable.
- **Stop mechanism** — Python has no `Thread.interrupt`; how does `stop()` wake a
  thread parked in a blocking `get`?
- **Sink contract** — a class, an exporter-shaped interface, or a bare callable.
- **Interval clock** — `time.time()` (wall clock) vs `time.monotonic_ns()`.

## Decision

### 1. **Single named daemon `threading.Thread` (`beacon-batch-flusher`) + `buffer.get(timeout)` timed poll** — not a scheduled executor

The flusher runs in one named daemon thread (`"beacon-batch-flusher"`). The loop
blocks on `BoundedBuffer.get(timeout_ms)` — the Python idiom of Java's
`buffer.poll(timeoutMs)` — where the timeout is the time left until the interval
deadline (or `flush_interval_ms` when the batch is empty and the interval clock
hasn't started). When a record arrives early, `get` returns it and the loop
opportunistically accumulates up to the size cap; when no record arrives, `get`
returns `None` at the deadline and the loop flushes whatever it has. The size
trigger fires naturally as the loop re-checks the accumulated count after each
poll.

Rejected (mirroring ADR-0004's rejection of `ScheduledExecutorService`): a
`concurrent.futures` scheduled executor / `sched.scheduler` / an `asyncio`
periodic task would fire on a fixed cadence regardless of buffer state — size
triggers would be reactive only on the next tick (latency floor = tick interval),
and idle intervals would still wake up. `asyncio` is out of scope per the
sync-only decision; a managed pool's overhead is larger than the savings for one
process-wide singleton.

### 2. **The chunked poll — the load-bearing Python divergence from Java**

`queue.Queue.get(timeout)` is **NOT interruptible by `threading.Event.set()`**.
Python has no `Thread.interrupt` equivalent (Java ADR-0004 wakes the parked
flusher via interrupt on `stop()`). A single long blocking
`buffer.get(flush_interval_ms)` would mean a `stop()` issued while the loop is
parked on a large interval (e.g. C4's `flush_interval_ms=60000`, where the loop
goes idle right after a SIZE flush) could not wake until the full interval
elapsed — `stop()`'s `join(1.0)` would time out and the thread would leak.

Therefore `_run_loop` caps each `buffer.get()` at
`min(remaining_ms, _POLL_CHUNK_MS=50)` and rechecks `self._stop.is_set()` between
chunks in **both** the idle branch (waiting for the first record) and the
non-empty branch (waiting out the remaining interval), accumulating
`time.monotonic_ns` elapsed so the INTERVAL trigger still fires at exactly
`flush_interval_ms` while `stop()` + `join(1.0)` stays bounded for **any**
`flush_interval_ms`. This is the Python answer to Java's interrupt-driven poll:
a small constant chunk + a stop-flag recheck replaces the interrupt. Verified:
`stop()` returns in **~0.3 ms at `flush_interval_ms=60000`** (budget 1.0 s); the
INTERVAL trigger fires at **~200.6 ms for a 200 ms config** (chunked accumulation
lands essentially on the configured interval).

### 3. **Empty intervals do NOT flush; the interval clock is `time.monotonic_ns`**

When the batch is empty and the interval elapses, the loop continues without
calling `sink.accept` (the strict reading of spec §2.3 — "batch reaches size OR
interval since first record"). The interval clock starts on the first record of a
batch, not on a fixed wall-clock cadence. The clock is **`time.monotonic_ns`, not
`time.time`** — wall-clock jumps (NTP step, DST, manual set) would corrupt the
deadline arithmetic (this is PITFALLS #5's ns-precision/clock hazard on the
timing side). Mirrors ADR-0004 §2.

### 4. **`BatchSink` is a `runtime_checkable typing.Protocol` + a `NOOP` default**

The "flush-to-what before the M2.3 exporter exists" seam (the Python idiom of
Java's functional `BatchSink` + `BatchSink.NOOP`). `BatchSink` is a
`runtime_checkable typing.Protocol` (`accept(batch: list[LogRecord]) -> None`),
**not** a bare `Callable[[list[LogRecord]], None]`, for parity with the *named*
Java `@FunctionalInterface BatchSink`: it documents the `accept` contract, is
structurally satisfied by the M2.3 OTLP exporter, and `runtime_checkable` lets
tests `isinstance`-check it. The default `NOOP` (a module-level `_NoopSink()`
instance) discards the batch, which lets the flusher run end-to-end before M2.3
lands a real exporter — analogous to M2.1's `SPILL_FALLBACK` seam. M2.3
substitutes the OTLP exporter (with retry/backoff + jitter + fallback sink,
ADR-0005's Python idiom) behind the same `BatchSink` interface; `NOOP` then
retires.

Rejected (mirroring ADR-0004): a heavyweight exporter interface with
`start()` / `flush()` / `shutdown()` — premature; OTel already gives that shape
for the transport layer in M2.3.

### 5. **Sink failures are swallowed inside `_flush` for M2.2**

If the sink throws, `_flush` catches `Exception` and continues so a misbehaving
sink cannot kill the flusher thread; the counters still bump. M2.3's resilient
sink (the Python idiom of Java `ResilientSink` / ADR-0005) takes over the
retry/backoff/fallback routing — the M2.2 swallow is the "no thread death" floor
that lets a bad sink not take the daemon down. Mirrors ADR-0004 §5 (Java
`catch (RuntimeException)`).

### 6. **`threading.Event` stop flag (not `volatile` + interrupt); lock-guarded idempotent lifecycle**

The stop flag is a `threading.Event` — the clean Python primitive — set by
`stop()`. Combined with the chunked poll (decision #2), `buffer.get(timeout)`
returning within one `_POLL_CHUNK_MS` lets the loop wake and re-check the flag,
so **no thread interrupt is needed**. `start()` / `stop()` are guarded by a
`threading.Lock` and are idempotent (mirror Java `synchronized`). `stop()` is the
non-draining halt (the in-flight batch is flushed on loop exit so records aren't
silently lost). Note the M2.4 lifecycle will both `set()` this Event **and** can
post a wake sentinel (the roadmap's "sentinel posted by `lifecycle.shutdown`") —
the `threading.Event` is the M2.2 primitive; the sentinel is M2.4 wiring.

### 7. **`drain_and_stop` is the M2.4 seam — fail-loud `NotImplementedError` in M2.2**

Graceful drain (drain the buffer, flush the tail within
`shutdown_drain_timeout_ms`, then stop) is C9 / M2.4's scope, wired to
`atexit` + a SIGTERM `signal` handler. M2.2 leaves `drain_and_stop` as a
fail-loud `NotImplementedError("M2.4: graceful drain")` seam (mirrors the M2.1
`SPILL_FALLBACK` precedent and Java's `UnsupportedOperationException`) rather than
implementing a half-form now — selecting it pre-M2.4 fails *loudly* rather than
silently skipping the drain.

### 8. **Two new `SdkMetrics` counters: `batches_flushed` + `records_flushed`**

`_flush` bumps `batches_flushed` (+1 per non-empty flush) and `records_flushed`
(+n for the batch size) — lock-guarded plain `int`s (the Python `AtomicLong`
idiom established in ADR-0014). The counters live on `SdkMetrics`, not on the
flusher or the sink, so a sink failure does not lose the counter (the increment
happens inside `_flush` after the swallow). M2.2 fills 2 of the 6 spec/02 §3
counters; `redactor_timeouts` fills in M2.5.

## Consequences

**Positive**

- C4 (size trigger) and C5 (interval trigger) are structural: the size trigger
  fires on the Nth record's arrival (no tick latency); the interval trigger fires
  at the deadline (chunked accumulation lands on `flush_interval_ms`).
- Single-thread model matches Java's ADR-0004 — trivially debuggable, one named
  thread to dump in a profiler, one thread per SDK instance.
- `time.monotonic_ns` is immune to wall-clock jumps, so the interval deadline
  cannot be corrupted by NTP steps / DST.
- The chunked poll makes `stop()` deterministic and bounded (`join(1.0)`) for
  **any** `flush_interval_ms`, despite `queue.Queue.get` being non-interruptible.

**Negative**

- A fixed `_POLL_CHUNK_MS=50` wake-and-recheck cadence means the idle daemon wakes
  ~20×/s even when nothing is happening. Negligible CPU at this rate, and the
  alternative (a single long blocking `get`) leaks the thread on `stop()`. The
  chunk size is the latency/wakeup tradeoff knob if a future profile cares.
- `drain_and_stop` is a known `NotImplementedError` TODO until M2.4 — `stop()`
  is non-draining (it flushes the in-flight batch but does not drain the buffer).
- Sink runs on the flusher thread (mirrors Java), so a slow sink slows all
  flushes. M2.3's resilient sink keeps this property by design (retries block the
  daemon); revisit if production workloads surface starvation.

**Neutral**

- The default `NOOP` sink discards everything until M2.3 substitutes a real sink.
  Documented in `BatchFlusher` and `BatchSink`.
- The flusher does not honor sink backpressure (no async signaling). The buffer
  drop policy (ADR-0014) is the back-pressure mechanism.

## Usage

- **Construct:** `BatchFlusher(buffer, sink, batch_max_records, flush_interval_ms, metrics)`
  — `batch_max_records` / `flush_interval_ms` come from `FlusherConfig`
  (defaults `512` / `1000`, the canonical `config-keys.yaml` C4/C5 values).
- **Run / halt:** `start()` to launch the daemon; `stop()` to halt (non-draining,
  flushes the in-flight batch on exit). Both idempotent and lock-guarded.
- **Default sink:** `NOOP` (discards) until M2.3; inject a real sink via the
  `sink` parameter.
- **Observe:** `metrics.batches_flushed` / `metrics.records_flushed` (read
  properties).
- **Graceful drain (M2.4+):** `drain_and_stop(...)` — currently a fail-loud
  `NotImplementedError` seam.

A future ADR amends this one if (a) M2.3's resilient sink moves sink execution
off the flusher thread, (b) the async-emit decision is revisited (an `asyncio`
flush task), or (c) M2.4's `drain_and_stop` + lifecycle sentinel changes the stop
mechanism (e.g. a poison-pill sentinel posted to the buffer in addition to the
`threading.Event`).
