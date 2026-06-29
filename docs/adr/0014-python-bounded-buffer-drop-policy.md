# ADR-0014 — Python bounded buffer + drop policy

| Field         | Value                                                                          |
| ------------- | ------------------------------------------------------------------------------ |
| Status        | Accepted                                                                       |
| Date          | 2026-06-29                                                                     |
| Milestone     | M2.1 — Python bounded buffer + drop policy                                     |
| Mirrors       | ADR-0003 (Java bounded buffer + drop policy) — this is the Python idiom of it  |
| Supersedes    | —                                                                              |
| Superseded by | —                                                                              |

## Context

M2.1 inserts a bounded in-memory buffer between the (future) Python `emit()`
path and the (future M2.2) batch flusher. It is the load-bearing piece of
spec §2.1–2.2 on the Python side: emit must be non-blocking even when the
exporter is slow or down, and the SDK must apply a configurable drop policy when
the buffer is full. **C2** (non-blocking emit) and **C3** (drop policy honored)
are the acceptance gates — the same two gates ADR-0003 names for Java.

This ADR is the **Python idiom of ADR-0003**. It does not re-litigate the
decisions ADR-0003 already settled (a bounded structure, three drop policies,
metrics on `SdkMetrics`, a tested return value, a flusher pull seam); it records
where the Python implementation *must diverge* from Java because the standard
library gives different primitives, and pins those divergences so M2.2+ build on
a known foundation.

The phase is scoped per locked decision #3 in M2.0's `04-CONTEXT.md`: **sync-only**
(no `asyncio` `aemit` in v1), `threading` + `queue.Queue.put_nowait()`. A
`queue.Queue.put_nowait()` is a few microseconds, so async callers can call sync
emit from async code without contention — async support is explicitly out of
scope here.

The choice space mirrors ADR-0003:

- **Backing structure** — `queue.Queue`, `collections.deque(maxlen=N)`, or a
  custom ring buffer.
- **Drop policy** — what happens when a non-blocking put hits a full buffer.
- **Where metrics live** — on the buffer itself, on `SdkMetrics`, or as a
  separate observable.
- **Thread-safety primitive for counters** — Python has no `AtomicLong`.

## Decision

### 1. **`queue.Queue(maxsize=N)`** as the backing structure — the Python idiom of `ArrayBlockingQueue`

`queue.Queue(maxsize=N)` is the standard-library bounded, thread-safe FIFO:
`put_nowait()` / `get_nowait()` are non-blocking (raise `queue.Full` /
`queue.Empty` rather than blocking), and it already provides the operations the
flusher and shutdown drain will need. No custom ring buffer; `deque(maxlen=N)`
was rejected because its silent-overwrite-on-full semantics bypass the drop
accounting the spec's metrics require (a `maxlen` overwrite increments no
counter and can't distinguish DROP_OLDEST from DROP_NEWEST).

**The key semantic gap vs Java (Pitfall #24):** `queue.Queue` has **no atomic
evict-head-then-put**. Java's `ArrayBlockingQueue.offer` is internally atomic
per call, so the DROP_OLDEST "evict head, then accept new" sequence cannot
interleave across concurrent producers. `queue.Queue` exposes only the separate
`get_nowait()` + `put_nowait()` primitives, so two producers can interleave
(A evicts, B evicts, A puts, B puts → **two** evictions for **one** logical
insert). Therefore DROP_OLDEST holds a single `threading.Lock`
(`self._policy_lock`) around the evict+put critical section. The
`except queue.Empty: pass` inside the evict loop is the Python mirror of Java's
`if (poll() != null)` consumer-race guard — it tolerates a consumer emptying the
queue between the `Full` signal and the `get_nowait`. DROP_NEWEST is a single
`put_nowait` and needs **no** lock.

### 2. Drop policies — **`DROP_OLDEST` (default), `DROP_NEWEST`, `SPILL_FALLBACK` (deferred)**

Same semantics as ADR-0003 §2:

- `DROP_OLDEST` — evicts head (under the lock above) and accepts the new record.
  **Default.** Matches operator intuition for log streams ("the most recent
  failure matters most"). `offer()` always returns `True`.
- `DROP_NEWEST` — keeps the existing buffer contents, drops the incoming record.
  For callers who want stable historical samples under load. `offer()` returns
  `False` on full.
- `SPILL_FALLBACK` — spec lists it; **deferred to M2.3** because it requires the
  fallback sink, which doesn't exist until then. The current implementation
  raises `NotImplementedError("M2.3: SPILL_FALLBACK requires FallbackSink")` —
  the Python idiom of Java's `UnsupportedOperationException` — so selecting it
  pre-M2.3 fails *loudly* rather than silently degrading to a different policy.

Drop accounting (`metrics.inc_dropped()`) increments per evicted/rejected
record: DROP_OLDEST by one per eviction when the buffer was full; DROP_NEWEST by
one per rejected offer.

### 3. **Metrics live on `SdkMetrics`, guarded by a single `threading.Lock` — the Python idiom of Java `AtomicLong`**

The buffer holds a reference to `SdkMetrics` and increments
`records_enqueued` / `records_dropped` / `buffer_depth` directly, mirroring
ADR-0003 §3 (the buffer does not own its own counters; `SdkMetrics` is the spec
§3 observability surface).

Java backs each counter with an `AtomicLong`. **Python has no atomic long.** The
options were: (a) plain `int` + a single `threading.Lock` around every read and
write; (b) `itertools.count`. `itertools.count` was **rejected** — you cannot
read its current value without consuming it, and concurrent `next()` plus a
separate read loses updates, so it is not safe for the read-the-gauge pattern
`buffer_depth` needs. A single lock over plain `int`s is correct and cheap at
these mutation rates; an 8×1000 concurrent-increment unit test proves no lost
updates. M2.1 owns 3 of the 6 spec/02 §3 counters (`records_enqueued`,
`records_dropped`, `buffer_depth`); the rest fill in across M2.2 (flusher) /
M2.3 (exporter + resilience) / M2.5 (redactor), mirroring the Java staged
surface.

### 4. **`offer(record)` returns `bool`, not `None`**

Tests assert the result for DROP_NEWEST (`False` on full). Production code (the
future `BeaconSdk.emit`) ignores the return value — the metric is the production
observable. Keeping the return value preserves testability without forcing
callers to handle it. Mirrors ADR-0003 §4.

### 5. **`drain_to(sink, max)` / `get(timeout_ms)` exposed for the M2.2 flusher**

The buffer is the producer side; the flusher (M2.2) is the consumer. `drain_to`
(Python idiom of Java `drainTo`) pulls a batch of up to `max` records;
`get(timeout_ms)` (Python idiom of Java `poll`, converting ms→s for
`queue.Queue.get`) supports the timed-pull path. Both update `buffer_depth`
after the pull so the gauge stays accurate. Mirrors ADR-0003 §5.

## Consequences

**Positive**

- C2 (non-blocking emit) is structural: `offer` never blocks regardless of
  consumer state (`put_nowait` + bounded `get_nowait` evict). Observed offer
  latency in M2.1 is p99 ~5.3 µs in-process / ~28 µs standalone — three orders
  of magnitude under the 1 ms budget.
- C3 (drop policy) is direct: the `DropPolicy` enum drives the behavior via a
  `match` dispatch; C3 measured exactly 900 drops for capacity 100 + 1000 offers
  under DROP_OLDEST.
- Spec §3 metrics are accurate without a separate observer.
- M2.2 plugs in via `drain_to` / `get` without changing the buffer's contract.

**Negative**

- `queue.Queue`'s evict-then-put needs an **external `threading.Lock`** where
  Java's `ArrayBlockingQueue.offer` loop did not (the per-call atomicity Java
  gets for free). This is a slight contention cost under many concurrent
  producers on the DROP_OLDEST path only — acceptable for the emit volumes in
  scope, and DROP_NEWEST pays nothing. If a future profile shows lock contention
  dominating at very high producer counts, a lock-free `deque` + a separate drop
  counter is the escape hatch (at the cost of the policy-distinguishing
  accounting). Documented here and in `BoundedBuffer.offer`.
- The single-lock `SdkMetrics` serializes all counter mutations. Negligible at
  current rates; revisit only if a counter becomes a hot path.

**Neutral**

- `SPILL_FALLBACK` is a known `NotImplementedError` TODO until M2.3. Documented
  in `BoundedBuffer.offer` and `DropPolicy`.

## Usage

- **Default:** `BufferConfig()` → `buffer_capacity=10_000`,
  `drop_policy=DropPolicy.DROP_OLDEST` (Python idiom of Java
  `BeaconConfig.defaults()` for these two slots).
- **Override:** `BufferConfig(buffer_capacity=100, drop_policy=DropPolicy.DROP_NEWEST)`.
- **Observe:** `metrics.enqueued` / `.dropped` / `.buffer_depth` (read
  properties).
- **Pull (M2.2+ only):** `buffer.drain_to(sink, max)` and
  `buffer.get(timeout_ms)`.

A future ADR amends this one if (a) we move off `queue.Queue` (e.g. to a
lock-free `deque` for the DROP_OLDEST contention noted above), or (b)
`SPILL_FALLBACK` requires a different in-buffer mechanism than a simple sink
hand-off when the M2.3 fallback sink lands.
