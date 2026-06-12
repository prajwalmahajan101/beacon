# ADR-0006 — Graceful shutdown drain

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-12 |
| Milestone | M1.5 |
| Supersedes | — |

## Context

Through M1.4 the SDK's `close()` only stopped the flusher daemon — anything still in the buffer or in the flusher's in-flight batch was silently dropped on shutdown. Spec §2.6 (FR-SDK-8) demands the opposite:

> On shutdown (JVM hook / `atexit`+signal), the SDK MUST attempt to flush all buffered records within `shutdown_drain_timeout_ms`. Records still unsent at timeout MUST be written to the fallback sink.

C9 is the gate: 200 pending records → `expect_flushed_or_fallback=200` within 5 s.

The choice space:

- **Where the drain primitive lives** — inside `BatchFlusher`, inside `BeaconSdk`, or a third "drainer" class.
- **What gets drained** — buffer only, or buffer + flusher's in-flight batch.
- **Sink path during drain** — direct buffer-to-sink, or reuse the existing flush path.
- **`close()` interaction with in-flight `emit()`** — does emit-after-close throw, drop, or fall through to a dead buffer?

## Decision

### 1. **`BatchFlusher.drainAndStop(long timeoutMs)`** owns the drain primitive

The flusher already understands the in-flight batch and the buffer cursor. Putting drain on `BatchFlusher` keeps the lifecycle in one place: `start()` / `stop()` (non-draining, retained for tests) / `drainAndStop(timeoutMs)` (the production close path).

`BeaconSdk.close()` is a thin wrapper that delegates: `flusher.drainAndStop(config.shutdownDrainTimeoutMs())`. The SDK doesn't grow drain logic of its own; the flusher gets one new method.

Rejected: a separate `Drainer` class would force a third reference into `BeaconSdk` and split the lifecycle state across two owners.

### 2. **Drain covers both the flusher's in-flight batch AND the buffer remainder**

- **In-flight batch**: `runLoop` gains an exit hook — after the `while (running)` loop ends (clean stop or `InterruptedException`), if `batch` is non-empty, `flush(batch)`. Without this, any records the flusher had poll-pulled but not yet sized/timed-out would be lost.
- **Buffer remainder**: after `thread.join(timeoutMs)` returns, `drainAndStop` does one final `buffer.drainTo(remaining, MAX_VALUE)` and, if non-empty, `flush(remaining)`.

Both paths use the existing private `flush(...)` helper, so `batchesFlushed` + `recordsFlushed` increment consistently regardless of which trigger drained the record.

### 3. **Drain reuses the configured sink path — no shortcut to fallback**

The drain hands records to the same `BatchSink` the flusher uses during normal operation. When the sink is `ResilientSink` (the production wiring per ADR-0005), drain-time failures automatically retry + route to the fallback sink per spec §2.6. When the sink is raw (test wiring), drain failures bubble up.

This means the spec's "or fallback" guarantee is structural, not a special code path: as long as production wires through `ResilientSink`, the drain inherits the contract for free.

### 4. **`close()` is idempotent via `AtomicBoolean closed`**

`compareAndSet(false, true)` gates the drain. Second and subsequent `close()` calls are no-ops. Matches the JVM shutdown-hook lifecycle (M1.7) where the hook might race a manual `close()`.

### 5. **Join is best-effort, not enforced**

`thread.join(timeoutMs)` returns when the thread exits *or* the timeout elapses — whichever comes first. If a misbehaving sink retries past the timeout, the flusher thread lives briefly beyond `close()` returning. Acceptable: JVM teardown follows immediately, the records still end up in fallback (`ResilientSink`'s contract), and the alternative (force-stop the thread) would risk torn writes to the fallback file.

Documented in `BeaconSdk.close()`'s Javadoc.

### 6. **`emit()` after `close()` is NOT gated in M1.5**

Records emitted after `close()` continue to push to the (now drained) bounded buffer with no consumer. They sit there until GC. The C9 test doesn't exercise this, and adding the gate is non-trivial (every public emit entry point needs the check, including future Logback/Log4j2 appenders). Deferred to a future phase when production data shows it matters.

Documented in the ADR's "Consequences" section so a future contributor doesn't assume it's intentional.

## Consequences

**Positive**
- C9 passes structurally: every pending record either reaches the sink or the fallback within budget.
- Production wiring inherits spec §2.6 compliance for free via `ResilientSink`.
- One drain entry point (`drainAndStop`) — easy to invoke from a JVM shutdown hook in M1.7.
- Metrics stay consistent during drain (no special drain-only counter).

**Negative**
- `emit()` after `close()` is a silent no-op (records lost). Documented gap, not a regression — the prior behavior was the same plus the buffer also got drained. Revisit if production needs catch this.
- `thread.join(timeoutMs)` can return before the flusher actually exits. The flusher might log/write to fallback briefly after `close()` returns. Acceptable during JVM teardown.

**Neutral**
- The drain runs synchronously on the caller's thread. A 200-record drain through an in-memory `CapturingSink` completes in ms; through a real OTLP exporter it depends on the network. Bounded by `shutdownDrainTimeoutMs`.

## Usage

- **Production:** `try (BeaconSdk sdk = BeaconSdk.builder().config(cfg).sink(ResilientSink.of(otlp, cfg, metrics)).build()) { ... }`. The try-with-resources `close()` triggers the drain.
- **Tune the budget:** `BeaconConfig.defaults().withShutdownDrainTimeoutMs(10_000)`.
- **Non-draining stop (tests only):** `sdk.flusher().stop()` — skips the drain entirely.
- **JVM hook integration (M1.7):** `Runtime.getRuntime().addShutdownHook(new Thread(sdk::close))`. Idempotent guard handles the race with explicit `close()`.

A future ADR amends this one if (a) we add backpressure on `emit()` after `close()`, (b) the drain moves off the caller's thread, or (c) the M1.7 shutdown hook needs a different timeout contract than the in-process `close()` path.
