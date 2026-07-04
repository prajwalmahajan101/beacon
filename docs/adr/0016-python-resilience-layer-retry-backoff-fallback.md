# ADR-0016 — Python resilience layer: retry, backoff + jitter, and fallback sink

| Field         | Value                                                                                 |
| ------------- | ------------------------------------------------------------------------------------- |
| Status        | Accepted                                                                              |
| Date          | 2026-07-04                                                                            |
| Milestone     | M2.3 — Python OTLP exporter + retry/backoff + fallback                                 |
| Mirrors       | ADR-0005 (Java resilience layer: retry, backoff + jitter, fallback) — this is the Python idiom of it |
| Supersedes    | —                                                                                     |
| Superseded by | —                                                                                     |

## Context

M2.3 ships the Python SDK's first network-side concerns — the same
network-boundary phase Java ADR-0005 names, one milestone later on the Python
side. It wraps `opentelemetry-exporter-otlp` (gRPC default / HTTP option, pinned
`== 1.43.0` per ADR-0013), retries transient export failures with exponential
backoff + jitter up to `max_retries`, and routes an exhausted batch to a local
fallback sink (`stderr` or file) — **never silently dropped**. Three scenarios
gate the phase, exactly as in Java: **C6** (retry-then-fallback), **C7**
(unreachable → fallback), **C8** (recovery without restart).

This is the layer that fills the seam M2.2 left. The M2.2 `BatchFlusher`
(ADR-0015) drains the M2.1 bounded buffer into batches and hands each batch to a
`BatchSink`, defaulting to a `NOOP` discard. M2.3 substitutes a real
`BatchSink` — the resilient OTLP sink — behind that same interface, and `NOOP`
retires.

The phase is scoped per **locked decision #3** in M2.0's `04-CONTEXT.md`:
**sync-only** — `time.sleep` on the flusher thread, **no `asyncio`** HTTP client
or event loop. This ADR is the **Python idiom of ADR-0005**: it does not
re-litigate the decisions ADR-0005 already settled (resilience as a decorator,
full-jitter backoff, a fallback interface with stderr/file impls, transport
delegated to the OTel SDK, bounded `max_retries + 1` attempts, synchronous retry
on the flusher thread). It records where the Python implementation *must* diverge
from Java because the standard library and OTel-Python give different primitives,
and — the load-bearing addition — reconciles a **contract tension** the
M2-roadmap's success criterion #4 introduced against the frozen cross-SDK
`config-keys.yaml`.

The choice space mirrors ADR-0005:

- **Where retry/backoff lives** — inside `OtlpExporter`, or as a separate wrapper.
- **Jitter algorithm** — equal / decorrelated / full jitter.
- **Fallback sink shape** — a class with a `Target` enum, a Protocol with impls, or a callback.
- **OTel transport conversion** — hand-build `LogRecordData`, or delegate to `LoggerProvider`.
- **429 handling** — whether (and how far) to plumb a `Retry-After` hint OTel-Python does not surface.
- **Fallback file path + rotation** — whether to honor the roadmap's `${BEACON_FALLBACK_DIR}` default + size-cap rotation, or the cross-SDK `fallback-sink` contract.

## Decision

### 1. Resilience is a `BatchSink` decorator (`ResilientSink`), not exporter-internal logic

`ResilientSink` implements the `BatchSink` Protocol and wraps a *delegate*
`BatchSink` (the transport — `OtlpExporter`) + a `RetryPolicy` + a `FallbackSink`
+ `SdkMetrics`. This keeps:

- `OtlpExporter` transport-only and **fail-fast** — it talks the wire and raises
  `OtlpExportError` on a failed `force_flush`, owning no retry/backoff.
- Conformance tests free of a live OTLP collector — C6/C7/C8 inject fake delegate
  `BatchSink`s (`_FailNTimesDelegate`, `_UnreachableDelegate`,
  `_DownThenUpDelegate`) + a `CapturingFallback` and verify the resilience
  contract independently.
- Production wiring composable and explicit. The real sink handed to the M2.2
  flusher is:

  ```python
  ResilientSink.of(OtlpExporter(endpoint, transport="grpc"), config, metrics)
  ```

  `ResilientSink.of(delegate, config, metrics)` builds `RetryPolicy.from_config`
  + `fallback_from_config` and wraps the delegate — **this is the `BatchSink`
  that fills the M2.2 `BatchFlusher` `NOOP` seam**. (The actual
  `BatchFlusher(buffer, sink=ResilientSink.of(...), ...)` assembly lands in
  M2.4 / M2.6 — there is no top-level SDK assembler yet; the composition is
  documented + `of()`-tested.)

Rejected, mirroring ADR-0005: putting retry inside `OtlpExporter` would couple
the two concerns and force any alternative transport to re-implement the loop.

### 2. AWS "full jitter" backoff — uniform random in `[0, min(base_ms * 2^attempt, max_ms)]`

`RetryPolicy.next_delay_ms(attempt) = random.randint(0, min(base_ms * 2 **
min(attempt, 30), max_ms))`. Per the AWS Architecture Blog "Exponential Backoff
and Jitter." Full jitter de-correlates retry storms across many SDK instances (a
single bad downstream doesn't trigger a synchronized retry stampede). The shift
exponent is **capped at 30** for cross-SDK parity with Java's overflow-safe
`1L << min(n, 30)` — Python ints don't overflow, but the cap keeps the ceiling
bounded and byte-for-byte matches the Java ceiling table (100, 200, 400, 800,
1600, 3200, then capped at `max_ms`). `random.randint` (not `secrets`) is
correct — jitter is not security-sensitive; this mirrors Java's
`ThreadLocalRandom`.

Rejected: equal jitter (`base/2 + rand(0, base/2)`) gives a minimum delay every
attempt — slightly worse de-correlation under fleet load. Decorrelated jitter is
viable; full jitter wins on simplicity + the AWS recommendation and matches
ADR-0005.

### 3. Synchronous `time.sleep` backoff on the flusher thread — the sync-only constraint

`ResilientSink.accept` runs on the M2.2 `beacon-batch-flusher` daemon thread and
sleeps `time.sleep(next_delay_ms(attempt) / 1000)` between attempts — the Python
idiom of Java's `Thread.sleep` in `ResilientSink`. Per **locked decision #3**
there is **no `asyncio` HTTP client**.

**Stall tradeoff (documented, accepted-for-v1).** Worst case:
`max_retries × backoff_max_ms` = `5 × 5000` = **~25 s** per failing batch blocks
the flush loop. During that window the **M2.1 bounded-buffer drop policy is the
back-pressure escape valve** (the buffer keeps accepting non-blocking `offer`s
and drops per policy rather than blocking the host application's logging thread).
This is the same property Java accepted in ADR-0005 §7. Async retry (an
`asyncio`-based sink, or moving retry off the flusher thread) is **deferred
post-v1**. Cross-referenced as **Pitfall #25** (sync-retry stall).

A jitter roll of `0` (possible when `base_ms == max_ms == 1`, as the conformance
tests configure for speed) is guarded by `if delay_ms > 0: time.sleep(...)` so a
zero delay skips the syscall entirely — the retry *count* (`max_retries + 1`
attempts), not the sleep count, is the contract.

### 4. `FallbackSink` Protocol + `StderrFallbackSink` (default) + `FileFallbackSink` + a conformance-only `CapturingFallback`

`FallbackSink` is a `runtime_checkable typing.Protocol` (`write(batch) -> None`),
the Python idiom of Java's `FallbackSink` interface. Production impls live in
`beacon.exporter.fallback`:

- **`StderrFallbackSink`** (default) — writes canonical JSON, one record per
  line, to a stream (injectable for tests).
- **`FileFallbackSink`** — UTF-8 append-only, one canonical-JSON record per line,
  parent directories auto-created; **raises on `OSError`** (the last-resort path
  fails loud — the resilient sink does not catch it, mirroring "no silent loss"
  at its most critical).
- **`CapturingFallback`** (conformance/test support, kept in source so the
  M0-frozen conformance tree can import it) — records batches for assertions.

`fallback_from_config` selects by the canonical `fallback-sink` key:
`None`/blank/`"stderr"` → `StderrFallbackSink`; `"file:<path>"` →
`FileFallbackSink`; anything else → `ValueError`. Every successful write bumps
`SdkMetrics.fallback_writes` by batch size. Mirrors Java `FallbackSink.fromConfig`.

Rejected, mirroring ADR-0005: a single `FallbackSink` class with a `Target` enum.
Protocol + impls makes fake-injection trivial and localizes each impl's I/O
assumptions.

### 5. CONTRACT RECONCILIATION (criterion #4 — the load-bearing decision)

The M2-roadmap success criterion #4 asks for a fallback file path that
**defaults to `${BEACON_FALLBACK_DIR}/beacon-fallback.log`** and a sink that
**rotates at a size cap**.

But `beacon-s0-contract/conformance/config-keys.yaml` — an **ADR-0010 cross-SDK
source-of-truth artifact**, mirrored by the Java SDK — defines **only**
`fallback-sink` (`BEACON_FALLBACK_SINK` / `beacon.fallback-sink`, default
`stderr`, spec `stderr | file:<path>`). There is **no `fallback-dir` key, no
rotation-cap key**, and the Java `FileFallbackSink` (ADR-0005 §3) does **not
rotate**.

Adding `BEACON_FALLBACK_DIR` or a rotation-cap key would be a **cross-SDK
CONTRACT CHANGE**: it would diverge the Python config surface from Java and
desync the ADR-0010 drift gate's parity intent (the `check_contract_drift.py`
gate asserts every `config-keys.yaml` `BEACON_*` literal appears in both SDKs;
a Python-only `BEACON_FALLBACK_DIR` would either trip the gate or force a
one-sided carve-out).

**Decision: honor the cross-SDK `fallback-sink` contract.** `stderr` default,
`file:<path>` opt-in, **NO new `BEACON_*` keys**. This keeps the drift gate at
exit 0, keeps Java parity intact, and keeps C6/C7/C8 green. The roadmap's
`BEACON_FALLBACK_DIR` default + size-cap rotation are **DEFERRED**:

- Rotation is **not shipped in M2.3**.
- If added later it must be **EITHER** an internal behavior of the file sink (a
  constant / behavior, **not** a new `BEACON_*` surface) **OR** a contract change
  gated by a fresh ADR + an additive `config-keys.yaml` carve-out following the
  ADR-0010 process.

This is the planner's contract-tension resolution; it is consistent with the
locked OTel pin (ADR-0013) and Java parity, so it did **not** require a human
checkpoint. Recorded here as an explicit deferred item + a flagged follow-up. (It
is also reconciled in the planning `ROADMAP.md` M2.3 row, whose criterion #4 is
struck through with a pointer to this ADR.)

### 6. Retry-After-on-429 hint (criterion #2 — a scoped addition over Java)

Java has no 429 handling. Python adds hint *plumbing*: `OtlpExportError` carries
an optional `retry_after_ms`, and `ResilientSink` **floors** the computed jitter
delay with it (`delay_ms = max(delay_ms, hint)`; full jitter still applies above
the floor). `parse_retry_after(header)` converts a delta-seconds header (`int` or
numeric `str`) to ms, returning `None` for `None` / unparseable / HTTP-date
forms. This end-to-end path is **unit-tested** (`test_retry_after_hint_floors_delay`,
`test_parse_retry_after`).

**Wiring the hint to a real OTel-Python HTTP 429 response is a flagged post-v1
follow-up.** `SimpleLogRecordProcessor.force_flush()` returns only a `bool` and
hides the HTTP status; surfacing a `Retry-After` header would require a custom
session / response hook — a fragile dependency on OTel HTTP internals that could
destabilize C6/C7/C8 determinism. In the production gRPC/HTTP path today
`force_flush`'s bool leaves `retry_after_ms = None` and backoff falls back to
full jitter. The hint is ready for the day the wiring lands; the wiring is out of
M2.3 scope.

### 7. Three new `SdkMetrics` counters — `records_exported` / `export_failures` / `fallback_writes`

`records_exported` (+= batch size on a successful export), `export_failures`
(++ per failed attempt), `fallback_writes` (+= batch size on a fallback write),
each lock-guarded plain `int` — the Python `AtomicLong` idiom established in
ADR-0014. With these three, `SdkMetrics` now owns 6 of the 6 spec/02 §3 emit /
export / flush counters that M2.1–M2.3 cover; `redactor_timeouts` fills in M2.5.

## Consequences

**Positive**

- C6/C7/C8 are structural without a live collector — fake delegate `BatchSink`s +
  `CapturingFallback` substitute for the transport.
- `OtlpExporter` is transport-only and swappable; any future `BatchSink` (a Kafka
  producer, a custom HTTP shim) inherits retry/fallback for free.
- Cross-SDK config parity + the ADR-0010 drift gate stay green — **no new
  `BEACON_*` keys** (criterion #4 reconciled to the `fallback-sink` contract).
- AWS full-jitter de-correlates retry storms; the ceiling table matches Java.
- Fallback output is canonical JSON — an operator can re-ingest spilled records.

**Negative**

- Synchronous retry can stall the `beacon-batch-flusher` thread ~25 s worst case
  under retry pressure (**Pitfall #25**; the M2.1 drop policy mitigates via
  back-pressure). Async retry deferred post-v1.
- No fallback-file rotation in v1 — deferred to a future ADR-gated carve-out or an
  internal file-sink behavior.
- The `Retry-After`-429 hint is plumbed + tested but **not wired** to OTel's HTTP
  internals — deferred post-v1.
- `FileFallbackSink` writes are synchronous per batch — fine for the log volumes
  the spec assumes, not designed for sustained high throughput. Not a v1 concern.

**Neutral**

- `transport` (`"grpc" | "http"`) is a **Python-local wiring field** on
  `ExporterConfig`, not a contract key — no `transport` / `protocol` key exists in
  `config-keys.yaml`; it mirrors Java `OtlpExporter.Transport` GRPC/HTTP.
- The resilience layer verifies against fake delegates, not a real OTLP endpoint;
  live-transport verification is deferred to a sample service against an actual
  collector (M2.6-era, mirroring ADR-0005's neutral note).

## Usage

- **Production wiring (once the assembler lands, M2.4 / M2.6):**
  `BatchFlusher(buffer, sink=ResilientSink.of(OtlpExporter(endpoint, transport="grpc"), ExporterConfig(...), metrics), ...)`
  — the `ResilientSink` replaces `NOOP`.
- **Resilience-only test wiring:**
  `ResilientSink(fake_delegate, RetryPolicy(max_retries, base_ms, max_ms), CapturingFallback(metrics), metrics)`.
- **Tune retry / fallback via the canonical config keys:** `endpoint`,
  `max-retries`, `backoff-base-ms`, `backoff-max-ms`, `fallback-sink`
  (`stderr` | `file:<path>`).

A future ADR amends this one if (a) the sink contract goes async / retry moves
off the flusher thread, (b) fallback-file rotation or a `BEACON_FALLBACK_DIR`
surface is added (a fresh ADR + `config-keys.yaml` carve-out per ADR-0010), or
(c) the `Retry-After`-429 hint is wired to a real OTel-Python HTTP response.
