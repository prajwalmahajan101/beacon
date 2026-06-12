# ADR-0003 — Bounded buffer + drop policy

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-06-12 (backfilled, decisions land in M1.2 / 2026-06-11) |
| Milestone | M1.2 |
| Supersedes | — |

## Context

M1.2 inserts a bounded in-memory buffer between `emit()` and the (still-stubbed) exporter. The buffer is the load-bearing piece of spec §2.1–2.2: emit must be non-blocking even when the exporter is slow or down, and the SDK must apply a configurable drop policy when full. C2 (non-blocking emit) and C3 (drop policy honored) are the acceptance gates.

The choice space:

- **Backing structure** — `ArrayBlockingQueue`, `LinkedBlockingQueue`, `ConcurrentLinkedDeque` with a counter, or a custom ring buffer.
- **Drop policy** — what happens when `offer` hits a full buffer.
- **Where metrics live** — on the buffer itself, on the SDK, or as a separate observable.

## Decision

### 1. **`ArrayBlockingQueue<LogRecord>`** as the backing structure

Bounded at construction, fixed-size array (no resize), wait-free `offer()` returns `false` on full, supports `poll(timeout)` and `drainTo(...)` natively. The SDK doesn't need head/tail pointers or ring math — `ArrayBlockingQueue` already gives the exact set of operations the flusher and shutdown drain will eventually need. No custom ring buffer; no `LinkedBlockingQueue` (per-record allocation defeats the "cheap emit" goal).

### 2. Drop policies — **`DROP_OLDEST` (default), `DROP_NEWEST`, `SPILL_FALLBACK` (deferred)**

- `DROP_OLDEST` — evicts head and accepts new. Default. Matches operator intuition for log streams ("the most recent failure matters most"). Always returns `true` from `offer()`.
- `DROP_NEWEST` — keeps existing, drops the incoming record. For callers who want stable historical samples under load.
- `SPILL_FALLBACK` — spec lists it; deferred to M1.4 because it requires the fallback sink, which doesn't exist until then. Current impl throws `UnsupportedOperationException("M1.4: SPILL_FALLBACK requires FallbackSink")` so picking it pre-M1.4 fails loudly.

Drop accounting (`metrics.incDropped()`) increments per evicted/rejected record. `DROP_OLDEST` increments by one per offer when the buffer was full; `DROP_NEWEST` increments by one per rejected offer.

### 3. **Metrics live on `SdkMetrics`, not the buffer**

The buffer holds a reference to `SdkMetrics` and increments `enqueued` / `dropped` / `bufferDepth` directly. Reason:

- `SdkMetrics` is the spec's observability surface (§3) — six counters/gauges across the whole pipeline. Having the buffer own its own counters would duplicate state.
- `bufferDepth` is the only gauge in the spec; tying it to the buffer's `size()` after each mutation keeps it consistent without a separate observer.

Cost: the buffer constructor takes a `SdkMetrics` argument (small surface-area increase). Acceptable.

### 4. **`offer(LogRecord)` returns `boolean`, not `void`**

Tests assert the result for `DROP_NEWEST` (false on full). Production code (`BeaconSdk.emit`) ignores the return value — the metric is the observable signal. Keeping the return value preserves testability without forcing callers to handle it.

### 5. `drainTo(...)` is exposed for the M1.3 batch flusher

The buffer is the producer side; the flusher (M1.3) is the consumer. `drainTo(sink, maxRecords)` lets the flusher pull batches of up to `batchMaxRecords` without holding a lock for the full pull duration. Updates `bufferDepth` after each drain so the gauge stays accurate.

## Consequences

**Positive**
- C2 (non-blocking emit) is structural: `offer` never blocks regardless of consumer state.
- C3 (drop policy) is direct: the policy enum drives the behavior.
- Spec §3 metrics are accurate without a separate observer.
- M1.3 plugs in via `drainTo` without changing the buffer's contract.

**Negative**
- Capacity is fixed at construction — no dynamic resize. Acceptable: dynamic resize would invalidate the bounded-memory guarantee that emit relies on.
- `ArrayBlockingQueue` allocates the backing array at full capacity up-front, even when empty. For default `buffer_capacity=10_000` that's 80 KB of `Object[]`. Fine for any realistic JVM; flag if a future spec change pushes capacity into the millions.

**Neutral**
- `SPILL_FALLBACK` is a known TODO until M1.4. Documented in `BoundedBuffer.offer` and `BeaconConfig`.

## Usage

- **Default:** `BeaconConfig.defaults()` → `bufferCapacity=10_000`, `dropPolicy=DROP_OLDEST`.
- **Override:** `BeaconConfig.defaults().withBufferCapacity(100).withDropPolicy(DropPolicy.DROP_NEWEST)`.
- **Observe:** `sdk.metrics().enqueued()` / `.dropped()` / `.bufferDepth()`.
- **Pull (M1.3+ only):** `buffer.drainTo(sink, maxRecords)` and `buffer.poll(timeoutMs)`.

A future ADR amends this one if (a) we move off `ArrayBlockingQueue` (e.g. for ABQ's known contention issues at very high producer counts), or (b) `SPILL_FALLBACK` requires a different in-buffer mechanism than a simple sink hand-off.
