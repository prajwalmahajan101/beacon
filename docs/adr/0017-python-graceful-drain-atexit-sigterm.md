# ADR-0017 — Python graceful drain: atexit + SIGTERM

| Field         | Value                                                                                     |
| ------------- | ----------------------------------------------------------------------------------------- |
| Status        | Accepted                                                                                  |
| Date          | 2026-07-04                                                                                |
| Milestone     | M2.4 — Python graceful drain (atexit + SIGTERM)                                            |
| Mirrors       | ADR-0006 (Java graceful shutdown drain) — this is the Python idiom of it                   |
| Supersedes    | —                                                                                         |
| Superseded by | —                                                                                         |

## Context

Through M2.3 the Python pipeline could *drain* but nothing *triggered* the drain
on process exit. The M2.2 flusher left `BatchFlusher.drain_and_stop` as a
`NotImplementedError("M2.4: graceful drain")` seam (ADR-0015); M2.4 Plan 01
implemented that primitive (in-flight batch + buffer remainder → configured sink,
bounded best-effort join, idempotent). What was still missing is the *lifecycle
wiring*: something that calls `drain_and_stop` when the host process is going
away.

Spec §2.6 (FR-SDK-8) demands the same contract Java ADR-0006 names:

> On shutdown (`atexit` + signal), the SDK MUST attempt to flush all buffered
> records within `shutdown_drain_timeout_ms`. Records still unsent at timeout MUST
> be written to the fallback sink.

C9 is the gate: 200 pending records → `expect_flushed_or_fallback=200` within 5 s.

This is the **Python idiom of ADR-0006**. It does not re-litigate the decisions
ADR-0006 already settled (drain primitive on the flusher, drain covers both the
in-flight batch and the buffer remainder, drain reuses the configured sink so
"or fallback" is structural, idempotent close, best-effort join, emit-after-close
ungated). What it *must* record is where Python diverges from the JVM, because the
exit model is fundamentally different:

- Java has **one** JVM shutdown hook (`Runtime.addShutdownHook`) that fires on
  both normal exit and SIGTERM. One hook, one drain.
- Python has **two** exit paths that do **not** natively converge: `atexit`
  handlers run on normal interpreter exit but **NOT on SIGTERM** (the default
  disposition kills the process immediately, skipping `atexit`); a `SIGTERM`
  handler is a *separate* registration. Worse, a naive SIGTERM handler that drains
  and then lets the process exit normally will **also** trigger `atexit`, firing
  the drain a **second** time.

The choice space, mirroring ADR-0006 plus the Python-specific wrinkles:

- **Where the drain primitive lives** — settled by ADR-0006 #1 / M2.4 Plan 01:
  `BatchFlusher.drain_and_stop`. This ADR covers the *orchestration* above it.
- **Where the orchestration lives** — a top-level `close()`, or a
  `beacon.lifecycle` module.
- **When the exit hooks are installed** — at import time, or lazily.
- **Whether SIGTERM is installed unconditionally** — always, or main-thread-only.
- **How the SIGTERM path converges with `atexit`** — chain to the previous
  handler, re-raise under `SIG_DFL`, or convert to a normal exit — such that the
  drain runs **exactly once** regardless of path or ordering.

## Decision

The core drain contract mirrors ADR-0006's six decisions in Python idiom; the
Python-specific lifecycle wiring adds three more (7–9). `beacon_shutdown()` is the
Python idiom of Java's `BeaconSdk.close()` — a thin orchestrator over the flusher
primitive.

### 1. `BatchFlusher.drain_and_stop(timeout_ms)` owns the drain primitive; `beacon_shutdown()` orchestrates it

Parity with ADR-0006 #1. `drain_and_stop` (M2.4 Plan 01) stops the loop, joins the
worker best-effort within `timeout_ms`, then drains the buffer remainder through
the configured sink. `beacon.lifecycle.beacon_shutdown()` is the thin orchestrator
that snapshots the registered flusher + timeout and calls
`flusher.drain_and_stop(timeout)` — the Python idiom of `BeaconSdk.close()`
delegating to `flusher.drainAndStop(...)`. The lifecycle module grows no drain
logic of its own.

Rejected, mirroring ADR-0006: a separate drainer/orchestrator class would split
the (single, module-level) lifecycle state across two owners.

### 2. Drain covers the in-flight batch AND the buffer remainder

Parity with ADR-0006 #2. The **in-flight batch** is flushed by the existing
`_run_loop` loop-exit hook. The **buffer remainder** is flushed by
`drain_and_stop`'s final `buffer.drain_to(remaining, sys.maxsize)` +
`_flush(remaining)` (the Python idiom of Java's `drainTo(..., Integer.MAX_VALUE)`).
Both paths route through the same private `_flush` helper, so `batches_flushed` /
`records_flushed` increment consistently.

### 3. Drain reuses the configured sink — "or fallback" is structural

Parity with ADR-0006 #3. `build_pipeline` (M2.4 Plan 02) wires
`ResilientSink.of(OtlpExporter(...))` as the flusher's sink, **retiring the M2.2
`NOOP` seam**. When the drain hands the remainder to that `ResilientSink`,
drain-time export failures retry and route to the file/stderr fallback per
spec §2.6 — not a special drain-only branch, the same structural guarantee Java
gets by wiring through `ResilientSink` (ADR-0005 / ADR-0016). With a raw sink
(test wiring) drain failures bubble up.

### 4. Idempotent drain-once via `threading.Lock` + `_shutdown_done` bool — the convergence guard (load-bearing)

Parity with ADR-0006 #4's `AtomicBoolean` `compareAndSet`. `beacon_shutdown()`
takes `_lock`, and if `_shutdown_done` is already set it returns immediately (the
no-op); otherwise it flips `_shutdown_done = True`, snapshots the flusher +
timeout, and **releases the lock before** the blocking `drain_and_stop` call
(mirroring the Plan-01 gate-then-drain-outside-the-monitor discipline, and Java
gating on `compareAndSet` then draining outside the monitor).

**Both the `atexit` callback AND the `SIGTERM` handler call the SAME guarded
`beacon_shutdown()`.** This is *the* load-bearing Python-specific decision: because
the two exit paths do not natively converge (decision #8), the double-fire case —
SIGTERM drains, then `atexit` fires on the ensuing normal exit — must drain
*exactly once*. The lock + bool guarantees the second call is a no-op regardless of
ordering. This is the invariant recorded as Pitfall #26 (atexit-ordering vs
SIGTERM double-fire).

`beacon_shutdown(*args)` accepts a variadic signature so **one** function serves
both call shapes: the zero-arg `atexit` callback and the `(signum, frame)` signal
handler. A drain exception is logged with context (never swallowed silently, never
allowed to crash interpreter teardown).

### 5. Best-effort join — no force-kill on timeout

Parity with ADR-0006 #5. `drain_and_stop` does `thread.join(timeout_ms/1000)` and,
if the worker is still alive at timeout, proceeds to drain the buffer remainder
anyway rather than force-killing. Records still reach the sink (or its fallback);
the daemon thread may live briefly past `beacon_shutdown()` returning, which is
acceptable at interpreter teardown (the alternative — force-stopping the thread —
would risk torn writes to the fallback file).

### 6. `emit()` after shutdown is NOT gated in M2.4

Parity with ADR-0006 #6, and doubly moot here: there is **no top-level `emit()`
yet** (the `BeaconLoggingHandler` lands in M2.6). Records pushed to a drained
buffer would sit with no consumer until GC. Gating every future emit entry point is
deferred; documented so a future contributor does not assume the current
no-gate behavior is an oversight.

### 7. Lazy `atexit` registration on first emit — no import-time side effects (Python-specific)

`ensure_shutdown_registered()` — the seam the future top-level `emit()` calls, and
which `build_pipeline` calls on assembly — registers `atexit.register(beacon_shutdown)`
on **first use, never at import**. Importing `beacon.lifecycle` installs **no** hook
and mutates **no** global process state.

Rationale: importing a library must not silently mutate the process's `atexit`
table or signal disposition — that is surprising, un-composable, and hostile to a
host application (or a test harness) that manages its own lifecycle. The
registration is a register-once operation guarded by `_atexit_registered`, so
repeated `ensure_shutdown_registered()` calls install the hook at most once.
Success criterion #1; verified in-process by a no-side-effects test and
cross-process by the M2.4 Plan-03 subprocess test.

### 8. Main-thread-only SIGTERM install (`threading.main_thread()` guard) + convert-to-normal-exit convergence (Python-specific)

`ensure_shutdown_registered()` installs `signal.signal(SIGTERM, _sigterm_handler)`
**only** when `threading.current_thread() is threading.main_thread()`, wrapped in
`try/except ValueError`. Two reasons: (a) `signal.signal` **raises `ValueError`
off the main thread** — CPython only allows signal handler installation from the
main thread; (b) when Beacon is imported inside a daemon/worker thread of a larger
application, that application (or its process manager) **owns** the process's signal
disposition — Beacon must not steal SIGTERM from it. Off the main thread the SIGTERM
install is **skipped entirely** (atexit-only path remains); the `ValueError` guard
also covers embedded-interpreter edge cases. Success criterion #2.

**Convergence mechanism: `_sigterm_handler` drains then `raise SystemExit(0)`.**
On SIGTERM the handler calls `beacon_shutdown(signum, frame)` (the *first* drain),
then raises `SystemExit(0)`. This is the decision that makes the two exit paths
converge:

- A **raw** SIGTERM (default disposition) does **not** run `atexit` — the process
  is killed outright and pending records are lost. Converting SIGTERM into a normal
  interpreter exit (`SystemExit`) is what makes `atexit` fire at all, *and* lets a
  container actually stop cleanly (returncode 0, not `-SIGTERM`).
- Because `atexit` now fires on the SystemExit unwind, its `beacon_shutdown()` call
  would be a *second* drain — made a harmless no-op by the decision-#4 guard.

Rejected alternatives: **chain to the previous SIGTERM handler** — brittle (the
previous handler may itself `os._exit`, skipping our drain, or may not exist);
**restore `SIG_DFL` and re-raise SIGTERM** — kills the process before `atexit`
runs, losing the normal-exit drain path and the clean returncode. Converting to a
normal exit is the only mechanism that makes *both* paths route through the *one*
guarded drain.

## Consequences

**Positive**

- C9 passes structurally: 200 pending records → flushed (or fallback) within
  `shutdown_drain_timeout_ms`, driven directly through the drain primitive.
- Container SIGTERM drains within budget and exits cleanly (returncode 0) — proven
  cross-process by the M2.4 Plan-03 subprocess + real-`os.kill(SIGTERM)` test that
  reads N drained records back from a `file:<tmp>` fallback.
- Both exit paths converge on ONE guarded drain — no double-drain regardless of
  SIGTERM-then-atexit ordering (Pitfall #26).
- Lazy registration keeps `import beacon.lifecycle` pure — no `atexit`/signal
  mutation until first use.
- Wiring the drain through `ResilientSink.of(OtlpExporter(...))` inherits the file/
  stderr fallback for free — "or fallback" needs no drain-specific code.

**Negative**

- Best-effort join can let the flusher live briefly past `beacon_shutdown()`
  returning (parity with ADR-0006 #5; acceptable at teardown).
- `emit()`-after-shutdown is ungated (deferred; moot until the M2.6 top-level
  emit path exists).
- The SIGTERM handler mutates global signal disposition — but only on the main
  thread, only after first emit, and it captures + can restore the previous handler
  (`_reset_for_tests`).

**Neutral**

- The drain is **synchronous / blocking** on the calling thread — the `atexit`
  thread on normal exit, or the main thread handling SIGTERM (locked decision #3:
  no `asyncio`, no event loop). A 200-record in-memory drain is sub-ms; a real OTLP
  drain is bounded by `shutdown_drain_timeout_ms`.
- `shutdown_drain_timeout_ms` is passed as a plain `build_pipeline` param defaulting
  to the canonical `shutdown-drain-timeout-ms` value 5000 (config-keys.yaml / C9) —
  **not** a new `BEACON_*` key; the `BEACON_SHUTDOWN_DRAIN_TIMEOUT_MS` anchor already
  exists, so the drift gate stays at exit 0. A full env/sysprop loader is later-M2
  growth.

## Usage

- **Assembly (drives the whole pipeline + arms the hooks):**

  ```python
  flusher = build_pipeline(
      BufferConfig(),
      FlusherConfig(),
      ExporterConfig(endpoint="http://collector:4317",
                     fallback_sink="file:/var/log/beacon-fallback.log"),
      metrics,
      drain_timeout_ms=5000,   # canonical shutdown-drain-timeout-ms default
  )
  ```

  `build_pipeline` assembles `BoundedBuffer → BatchFlusher →
  ResilientSink.of(OtlpExporter(...))`, registers the flusher + timeout, calls
  `ensure_shutdown_registered()` (arming `atexit` + a main-thread-only SIGTERM
  handler), and starts the flusher. The future top-level `emit()` (M2.6) will also
  call `ensure_shutdown_registered()` on first use.

- **On exit:** normal interpreter exit → `atexit` → `beacon_shutdown()` → drain;
  container `SIGTERM` → `_sigterm_handler` → `beacon_shutdown()` → drain →
  `SystemExit(0)` → `atexit` fires (guarded no-op). Either way the pipeline drains
  within `shutdown_drain_timeout_ms`.

- **Tune the budget:** the `drain_timeout_ms` param / the canonical
  `shutdown-drain-timeout-ms` key (default 5000).

- **Non-draining stop (tests only):** `flusher.stop()` — skips the drain entirely.

A future ADR amends this one if (a) `emit()`-after-shutdown gains backpressure/
gating, (b) the drain moves off the caller's thread (an async/worker-thread
redesign), or (c) the M2.6 handler needs a different timeout contract than the
lifecycle path.
